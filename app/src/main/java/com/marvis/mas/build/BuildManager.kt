package com.marvis.mas.build

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * Manages Gradle-based APK builds by invoking ./gradlew in the user's project directory.
 * Android SDK tools (aapt2, d8, apksigner) are bundled in assets/android-sdk/.
 */
class BuildManager(private val context: Context) {

    companion object {
        private const val TAG = "BuildManager"
    }

    private val sdkDir: File
        get() = File(context.filesDir, "android-sdk")

    private val buildToolsDir: File
        get() = File(sdkDir, "build-tools/34.0.0")

    private val platformsDir: File
        get() = File(sdkDir, "platforms/android-34")

    /**
     * The default workspace where projects live.
     */
    val workspaceDir: File
        get() = File(context.getExternalFilesDir(null), "projects")

    /**
     * Check if Android SDK tools are extracted.
     */
    fun isSdkReady(): Boolean {
        return File(buildToolsDir, "aapt2").exists() &&
                File(buildToolsDir, "d8").exists() &&
                File(buildToolsDir, "apksigner").exists() &&
                File(buildToolsDir, "zipalign").exists() &&
                File(platformsDir, "android.jar").exists()
    }

    /**
     * Extract Android SDK from assets to private storage.
     * Expected assets structure: assets/android-sdk/build-tools/34.0.0/* and platforms/android-34/
     */
    fun extractSdk(onProgress: (String) -> Unit = {}): Result<Unit> {
        return try {
            if (isSdkReady()) {
                onProgress("SDK already extracted")
                return Result.success(Unit)
            }

            sdkDir.mkdirs()
            extractAssetDir("android-sdk", sdkDir)

            // Make all build tools executable
            buildToolsDir.listFiles()?.forEach { it.setExecutable(true, false) }

            onProgress("Android SDK extracted to ${sdkDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract SDK", e)
            Result.failure(e)
        }
    }

    private fun extractAssetDir(assetPath: String, destDir: File) {
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
                    childDest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    /**
     * Build a project using Gradle.
     *
     * @param projectDir the project root directory (must contain gradlew)
     * @param task the Gradle task to run, e.g. "assembleDebug"
     * @param onOutput callback for each line of build output
     */
    suspend fun build(
        projectDir: File,
        task: String = "assembleDebug",
        onOutput: (String) -> Unit = {}
    ): BuildResult = withContext(Dispatchers.IO) {

        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.exists()) {
            return@withContext BuildResult.Failure("gradlew not found in ${projectDir.absolutePath}")
        }
        gradlew.setExecutable(true, false)

        val env = mutableMapOf<String, String>().apply {
            putAll(System.getenv())
            put("ANDROID_HOME", sdkDir.absolutePath)
            put("ANDROID_SDK_ROOT", sdkDir.absolutePath)
            put("JAVA_HOME", context.filesDir.absolutePath + "/jdk")
            put("GRADLE_USER_HOME", File(context.filesDir, ".gradle").absolutePath)
        }

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

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                // Find the generated APK
                val apkDir = File(projectDir, "app/build/outputs/apk/debug")
                val apk = apkDir.listFiles()?.find { it.extension == "apk" }

                BuildResult.Success(
                    apkFile = apk,
                    output = output.toString(),
                    exitCode = exitCode
                )
            } else {
                BuildResult.Failure(
                    message = "Build failed with exit code $exitCode",
                    output = output.toString(),
                    exitCode = exitCode
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Build error", e)
            BuildResult.Failure(
                message = e.message ?: "Unknown build error",
                output = output.toString(),
                exitCode = -1
            )
        }
    }

    /**
     * Install an APK on the device.
     */
    fun install(apkPath: String): Result<Unit> {
        return try {
            val process = ProcessBuilder()
                .command("pm", "install", "-r", apkPath)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0 || output.contains("Success")) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(output))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

sealed class BuildResult {
    data class Success(
        val apkFile: File?,
        val output: String,
        val exitCode: Int
    ) : BuildResult()

    data class Failure(
        val message: String,
        val output: String,
        val exitCode: Int
    ) : BuildResult()

    val isSuccess: Boolean get() = this is Success
}
