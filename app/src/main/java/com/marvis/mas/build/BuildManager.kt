package com.marvis.mas.build

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BuildManager(private val context: Context) {

    companion object {
        private const val TAG = "BuildManager"
    }

    private val sdkDir get() = File(context.filesDir, "android-sdk")
    private val buildToolsDir get() = File(sdkDir, "build-tools/34.0.0")
    private val platformsDir get() = File(sdkDir, "platforms/android-34")
    val workspaceDir get() = File(context.getExternalFilesDir(null), "projects")

    fun isSdkReady() = File(buildToolsDir, "aapt2").exists() &&
            File(buildToolsDir, "d8").exists() &&
            File(buildToolsDir, "apksigner").exists() &&
            File(buildToolsDir, "zipalign").exists() &&
            File(platformsDir, "android.jar").exists()

    fun extractSdk(onProgress: (String) -> Unit): Result<Unit> {
        return try {
            if (isSdkReady()) {
                onProgress("SDK already extracted")
                return Result.success(Unit)
            }
            sdkDir.mkdirs()
            extractAssetDir("android-sdk", sdkDir)
            buildToolsDir.listFiles()?.forEach { it.setExecutable(true, false) }
            onProgress("Android SDK extracted")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract SDK", e)
            Result.failure(e)
        }
    }

    // Manifest file that lists all asset files (one per line)
    private val ASSETS_MANIFEST = "asset_manifest.txt"

    private fun loadManifest(): List<String> {
        return try {
            context.assets.open(ASSETS_MANIFEST).bufferedReader().use { it.readLines() }
        } catch (e: Exception) {
            Log.w(TAG, "No asset manifest found, falling back to directory listing")
            emptyList()
        }
    }

    private fun extractAssetDir(assetPath: String, destDir: File) {
        // Use manifest if available (works with compressed assets)
        val manifest = loadManifest()
        if (manifest.isNotEmpty()) {
            val prefix = "$assetPath/"
            val files = manifest.filter { it.startsWith(prefix) }
            for (entry in files) {
                val relPath = entry.removePrefix(prefix)
                val dest = File(destDir, relPath)
                dest.parentFile?.mkdirs()
                context.assets.open(entry).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return
        }

        // Fallback: directory listing (only works with uncompressed assets)
        val children = context.assets.list(assetPath) ?: return
        for (child in children) {
            val childPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            val sub = context.assets.list(childPath)
            if (sub != null && sub.isNotEmpty()) {
                childDest.mkdirs()
                extractAssetDir(childPath, childDest)
            } else {
                context.assets.open(childPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    suspend fun build(
        projectDir: File,
        task: String = "assembleDebug",
        onOutput: (String) -> Unit
    ): BuildResult = withContext(Dispatchers.IO) {
        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.exists()) {
            return@withContext BuildResult.Failure("gradlew not found", "", -1)
        }
        gradlew.setExecutable(true, false)
        val env = HashMap(System.getenv())
        env["ANDROID_HOME"] = sdkDir.absolutePath
        env["ANDROID_SDK_ROOT"] = sdkDir.absolutePath
        env["GRADLE_USER_HOME"] = File(context.filesDir, ".gradle").absolutePath

        val output = StringBuilder()
        try {
            val process = ProcessBuilder()
                .directory(projectDir)
                .command(gradlew.absolutePath, task, "--no-daemon", "--stacktrace")
                .apply { environment().putAll(env) }
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { reader ->
                reader.lines().forEach { line ->
                    output.appendLine(line)
                    onOutput(line)
                }
            }
            val code = process.waitFor()
            if (code == 0) {
                val apk = File(projectDir, "app/build/outputs/apk/debug")
                    .listFiles()?.find { it.extension == "apk" }
                BuildResult.Success(apk, output.toString(), code)
            } else {
                BuildResult.Failure("Build failed: exit $code", output.toString(), code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Build error", e)
            BuildResult.Failure(e.message ?: "Unknown", output.toString(), -1)
        }
    }

    fun install(apkPath: String): Result<Unit> {
        return try {
            val process = ProcessBuilder().command("pm", "install", "-r", apkPath)
                .redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code == 0 || text.contains("Success")) Result.success(Unit)
            else Result.failure(Exception(text))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

sealed class BuildResult {
    val isSuccess get() = this is Success
    data class Success(val apkFile: File?, val output: String, val exitCode: Int) : BuildResult()
    data class Failure(val message: String, val output: String, val exitCode: Int) : BuildResult()
}
