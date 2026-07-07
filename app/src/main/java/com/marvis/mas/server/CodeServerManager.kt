package com.marvis.mas.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages a local code-server process that serves VS Code via HTTP.
 * The ARM64 node + code-server binaries are bundled in assets/code-server/.
 */
class CodeServerManager(private val context: Context) {

    companion object {
        private const val TAG = "CodeServerManager"
        private const val PORT = 18080
        private const val BIND_ADDR = "127.0.0.1"
        private const val MAX_STARTUP_RETRIES = 60
        private const val RETRY_DELAY_MS = 1000L
    }

    val url: String get() = "http://$BIND_ADDR:$PORT"

    private var processRef: Process? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The private directory where binaries are extracted. */
    private val serverDir: File
        get() = File(context.filesDir, "code-server")

    /** Node binary extracted from assets */
    private val nodeExe: File get() = File(serverDir, "lib/node")
    /** System dynamic linker to bypass SELinux execution restrictions */
    private val systemLinker: File get() = File("/system/bin/linker64")
    private val codeServerJs: File get() = File(serverDir, "out/node/entry.js")

    /**
     * Returns true if assets have already been extracted.
     */
    fun isExtracted(): Boolean = codeServerJs.exists()

    /**
     * Extract code-server assets from APK to private storage.
     * Assets are stored under assets/code-server/ in the APK.
     */
    fun extractAssets(onProgress: (String) -> Unit = {}): Result<Unit> {
        return try {
            if (isExtracted()) {
                onProgress("Assets already extracted")
                return Result.success(Unit)
            }

            serverDir.mkdirs()
            val assetsBase = "code-server"
            extractRecursive(assetsBase, serverDir, onProgress)

            // Verify critical files were extracted
            val missing = mutableListOf<String>()
            if (!nodeExe.exists()) missing.add("lib/node")
            if (!codeServerJs.exists()) missing.add("out/node/entry.js")
            
            if (missing.isNotEmpty()) {
                // Debug: list what actually got extracted
                val extracted = serverDir.walk().filter { it.isFile }.take(20).joinToString("\n") { 
                    it.relativeTo(serverDir).path 
                }
                Log.e(TAG, "Missing: ${missing.joinToString()}. Extracted files:\n$extracted")
                return Result.failure(Exception(
                    "Missing critical files: ${missing.joinToString()}. Check logcat for details."
                ))
            }

            // Node binary is already in nativeLibraryDir (installed via jniLibs)
            onProgress("Assets extracted to ${serverDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract assets", e)
            Result.failure(e)
        }
    }

// Manifest file that lists all files under assets/code-server/
    // Generated at build time by scripts/generate_asset_manifest.sh
    private val ASSETS_MANIFEST = "asset_manifest.txt"

    private fun loadManifest(): List<String> {
        return try {
            context.assets.open(ASSETS_MANIFEST).bufferedReader().use { it.readLines() }
        } catch (e: Exception) {
            Log.w(TAG, "No asset manifest found, falling back to directory listing")
            emptyList()
        }
    }

    /**
     * Copy a single asset file to the destination.
     * Uses context.assets.open() which always works regardless of compression.
     */
    private fun copyAssetFile(assetPath: String, dest: File) {
        try {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skip missing asset: $assetPath")
        }
    }

    private fun extractRecursive(
        assetPath: String,
        destDir: File,
        onProgress: (String) -> Unit = {}
    ) {
        // Use manifest if available (faster & works with compressed assets)
        val manifest = loadManifest()
        if (manifest.isNotEmpty()) {
            val prefix = "$assetPath/"
            val files = manifest.filter { it.startsWith(prefix) }
            var count = 0
            for (entry in files) {
                val relPath = entry.removePrefix(prefix)
                copyAssetFile(entry, File(destDir, relPath))
                count++
                if (count % 100 == 0) onProgress("Extracted $count files...")
            }
            onProgress("Extracted $count files (manifest)")
            return
        }

        // Fallback: directory listing (only works with uncompressed assets)
        val children = context.assets.list(assetPath) ?: return

        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)

            val subChildren = context.assets.list(childAssetPath)
            if (subChildren != null && subChildren.isNotEmpty()) {
                childDest.mkdirs()
                extractRecursive(childAssetPath, childDest, onProgress)
            } else {
                copyAssetFile(childAssetPath, childDest)
            }
        }
    }

    /**
     * Start the code-server process in background.
     */
    fun start(onStatus: (String) -> Unit = {}): Result<Unit> {
        return try {
            if (isRunning) {
                onStatus("code-server already running on $url")
                return Result.success(Unit)
            }

            val diagFile = File(context.filesDir, "mas_diag.txt")
            diagFile.writeText("=== MAS Diagnostics ===\n")
            
            if (!isExtracted()) {
                return Result.failure(IllegalStateException("Assets not extracted. Call extractAssets() first."))
            }

            if (!nodeExe.exists()) {
                return Result.failure(IllegalStateException("Node binary not found: ${nodeExe.absolutePath}"))
            }
            nodeExe.setExecutable(true, false)
            val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            diagFile.appendText(now + " Node: " + nodeExe.absolutePath + " exists=" + nodeExe.exists() + "\n")
            diagFile.appendText(now + " Linker64: " + systemLinker.absolutePath + " exists=" + systemLinker.exists() + "\n")

            // Use system linker to bypass SELinux execution restrictions
            val useLinker = systemLinker.exists()
            if (useLinker) {
                try {
                    val probe = ProcessBuilder(listOf(
                        systemLinker.absolutePath,
                        nodeExe.absolutePath,
                        "--version"
                    ))
                    .directory(serverDir)
                    .redirectErrorStream(true)
                    probe.environment().put("LD_LIBRARY_PATH", nodeExe.parent)
                    probe.environment().put("HOME", context.filesDir.absolutePath)
                    val p = probe.start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    val exit = p.waitFor()
                    diag("Node probe: exit=$exit ver="$out"")
                    onStatus("Node probe: exit=$exit ver=$out")
                } catch (e: Exception) {
                    diagFile.appendText("Probe FAILED: " + e.message + "\n")
                    onStatus("Node probe FAILED: ${e.message}")
                }
            }

            val userDataDir = File(context.filesDir, "code-server-user-data")
            val workspaceDir = File(context.getExternalFilesDir(null), "projects")
            userDataDir.mkdirs()
            workspaceDir.mkdirs()

            val env = mapOf(
                "HOME" to context.filesDir.absolutePath,
                "USER" to "mas",
                "PATH" to "${nodeExe.parent}:${System.getenv("PATH")}",
                "LD_LIBRARY_PATH" to nodeExe.parent
            )

            val cmdArgs = mutableListOf<String>()
            if (useLinker) {
                cmdArgs.add(systemLinker.absolutePath)
                cmdArgs.add(nodeExe.absolutePath)
            } else {
                cmdArgs.add(nodeExe.absolutePath)
            }
            cmdArgs.addAll(listOf(
                codeServerJs.absolutePath,
                "--bind-addr", "$BIND_ADDR:$PORT",
                "--auth", "none",
                "--disable-telemetry",
                "--disable-update-check",
                "--disable-getting-started-override",
                "--user-data-dir", userDataDir.absolutePath,
                "--extensions-dir", File(context.filesDir, "extensions").absolutePath,
                workspaceDir.absolutePath
            ))

            processRef = ProcessBuilder(cmdArgs)
                .directory(serverDir)
                .apply { environment().putAll(env) }
                .redirectErrorStream(true)
                .start()

            isRunning = true
            diagFile.appendText(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " Cmd: " + cmdArgs.joinToString(" ") + "\n")
            onStatus("code-server starting... | diag: mas_diag.txt")

            // Forward process output to logcat and onStatus
            scope.launch {
                processRef?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        Log.d(TAG, line)
                        if (line.contains("HTTP server listening")) {
                            onStatus("code-server ready on $url")
                        }
                        // Forward errors/warnings to status for debugging
                        if (line.contains("Error") || line.contains("error") || 
                            line.contains("WARN") || line.contains("FATAL")) {
                            Log.e(TAG, line)
                        }
                    }
                }
                // Process exited - forward exit code
                val exitCode = processRef?.waitFor() ?: -1
                diagFile.appendText(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " code-server exited with code " + exitCode + "\n")
                onStatus("code-server exited code=$exitCode | diag: mas_diag.txt")
                isRunning = false
            }

            // Wait for server to be actually ready
            scope.launch {
                for (i in 1..MAX_STARTUP_RETRIES) {
                    delay(RETRY_DELAY_MS)
                    if (!isRunning) return@launch
                    if (isHttpReady()) {
                        onStatus("code-server ready on $url")
                        return@launch
                    }
                }
                diagFile.appendText(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " code-server start timeout after " + MAX_STARTUP_RETRIES + "s\n")
                onStatus("code-server start timeout after ${MAX_STARTUP_RETRIES}s")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start code-server", e)
            isRunning = false
            Result.failure(e)
        }
    }

    private fun isHttpReady(): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "HEAD"
            conn.responseCode in 200..399
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stop the code-server process.
     */
    fun stop() {
        processRef?.let { proc ->
            proc.destroy()
            try {
                if (proc.isAlive) {
                    proc.destroyForcibly()
                    proc.waitFor()
                }
            } catch (_: Exception) {}
        }
        processRef = null
        isRunning = false
        scope.cancel()
    }

    fun isAlive(): Boolean = isRunning && processRef?.isAlive == true

    fun destroy() {
        stop()
    }
}
