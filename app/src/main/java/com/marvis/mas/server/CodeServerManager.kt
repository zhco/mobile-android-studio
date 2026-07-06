package com.marvis.mas.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
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
        private const val MAX_STARTUP_RETRIES = 30
        private const val RETRY_DELAY_MS = 1000L
    }

    val url: String get() = "http://$BIND_ADDR:$PORT"

    private var processRef: Process? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The private directory where binaries are extracted. */
    private val serverDir: File
        get() = File(context.filesDir, "code-server")

    /** Node binary from jniLibs (system-extracted, SELinux-safe) */
    private val nodeExe: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode_exec.so")
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

            if (!isExtracted()) {
                return Result.failure(IllegalStateException("Assets not extracted. Call extractAssets() first."))
            }

            // Resolve node binary: prefer nativeLibraryDir (SELinux-safe), fallback to assets-extracted
            val actualNodeExe = if (nodeExe.exists()) {
                nodeExe
            } else {
                val fallbackNode = File(serverDir, "lib/node")
                if (fallbackNode.exists()) {
                    fallbackNode.setExecutable(true, false)
                    Log.w(TAG, "Using fallback node binary: ${fallbackNode.absolutePath}")
                    fallbackNode
                } else {
                    return Result.failure(IllegalStateException("Node binary not found in nativeLibraryDir or assets"))
                }
            }

            val userDataDir = File(context.filesDir, "code-server-user-data")
            val workspaceDir = File(context.getExternalFilesDir(null), "projects")
            userDataDir.mkdirs()
            workspaceDir.mkdirs()

            val env = mapOf(
                "HOME" to context.filesDir.absolutePath,
                "USER" to "mas",
                "PATH" to "${nodeExe.parent}:${System.getenv("PATH")}"
            )

            processRef = ProcessBuilder()
                .directory(serverDir)
                .command(
                    actualNodeExe.absolutePath,
                    codeServerJs.absolutePath,
                    "--bind-addr", "$BIND_ADDR:$PORT",
                    "--auth", "none",
                    "--disable-telemetry",
                    "--disable-update-check",
                    "--disable-getting-started-override",
                    "--user-data-dir", userDataDir.absolutePath,
                    "--extensions-dir", File(context.filesDir, "extensions").absolutePath,
                    workspaceDir.absolutePath
                )
                .apply {
                    environment().putAll(env)
                }
                .redirectErrorStream(true)
                .start()

            isRunning = true
            onStatus("code-server starting...")

            // Monitor output in background
            scope.launch {
                processRef?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        Log.d(TAG, line)
                        if (line.contains("HTTP server listening")) {
                            onStatus("code-server ready on $url")
                        }
                    }
                }
            }

            // Wait for server to be actually ready
            scope.launch {
                for (i in 1..MAX_STARTUP_RETRIES) {
                    delay(RETRY_DELAY_MS)
                    if (isHttpReady()) {
                        onStatus("code-server ready on $url")
                        return@launch
                    }
                }
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
