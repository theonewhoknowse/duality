
package com.thaz.duality

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class WorkspaceApk(
    val originalPath: String,
    val workspaceFile: File,
    val sizeBytes: Long,
    val sha256: String
)

data class WorkspaceResult(
    val packageName: String,
    val directory: File,
    val apks: List<WorkspaceApk>,
    val totalBytes: Long
)

object ApkWorkspace {

    private const val WORKSPACE_DIRECTORY = "duality/workspace"

    /**
     * Copies APK files exposed by Android into Duality's private workspace.
     *
     * Call this from a background thread, such as Dispatchers.IO.
     * This does not modify the installed application.
     */
    fun prepare(
        context: Context,
        packageName: String,
        sourcePaths: List<String>
    ): WorkspaceResult {
        require(
            packageName.matches(
                Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
            )
        ) {
            "Invalid Android package name."
        }

        require(sourcePaths.isNotEmpty()) {
            "Android did not provide any APK paths for this app."
        }

        val uniquePaths = sourcePaths.distinct()
        val root = File(context.filesDir, WORKSPACE_DIRECTORY)
        val appDirectory = File(root, packageName)

        check(
            appDirectory.canonicalFile.toPath()
                .startsWith(root.canonicalFile.toPath())
        ) {
            "Workspace path is outside the permitted directory."
        }

        if (!appDirectory.exists() && !appDirectory.mkdirs()) {
            throw IllegalStateException("Could not create the APK workspace.")
        }

        val copiedApks = mutableListOf<WorkspaceApk>()

        try {
            uniquePaths.forEachIndexed { index, sourcePath ->
                val source = File(sourcePath)

                require(source.isFile && source.canRead()) {
                    "APK is not readable: $sourcePath"
                }

                val destinationName = when {
                    index == 0 -> "base.apk"
                    else -> "split_$index.apk"
                }

                val destination = File(appDirectory, destinationName)

                copyFile(source, destination)
                verifyApk(destination)

                copiedApks += WorkspaceApk(
                    originalPath = source.absolutePath,
                    workspaceFile = destination,
                    sizeBytes = destination.length(),
                    sha256 = sha256(destination)
                )
            }

            return WorkspaceResult(
                packageName = packageName,
                directory = appDirectory,
                apks = copiedApks,
                totalBytes = copiedApks.sumOf { it.sizeBytes }
            )
        } catch (error: Exception) {
            // Do not leave partial output looking like a successful workspace.
            copiedApks.forEach { it.workspaceFile.delete() }
            throw IllegalStateException(
                "Could not prepare APK workspace: " +
                        (error.message ?: error.javaClass.simpleName),
                error
            )
        }
    }

    private fun copyFile(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun verifyApk(file: File) {
        require(file.isFile && file.length() > 0L) {
            "Copied APK is empty."
        }

        ZipFile(file).use { zip ->
            require(zip.getEntry("AndroidManifest.xml") != null) {
                "The copied file does not contain an APK manifest."
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }
}
