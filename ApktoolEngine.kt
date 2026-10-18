
package com.thaz.duality

import android.content.Context
import brut.androlib.ApkBuilder
import brut.androlib.ApkDecoder
import brut.androlib.android.ApktoolAndroid
import java.io.File
import java.util.zip.ZipFile


data class ApktoolBuildResult(
    val packageName: String,
    val decodedDirectory: File,
    val outputApk: File,
    val outputSizeBytes: Long
)

object ApktoolEngine {

    fun decodeAndRebuild(
        context: Context,
        packageName: String,
        sourceApk: File
    ): ApktoolBuildResult {
        require(sourceApk.isFile && sourceApk.canRead()) {
            "The source APK cannot be read."
        }

        val root = File(
            context.filesDir,
            "duality/apktool"
        ).apply {
            check(isDirectory || mkdirs()) {
                "Could not create the Apktool workspace."
            }
        }

        val safeName = packageName.replace(
            Regex("[^A-Za-z0-9._-]"),
            "_"
        )

        val jobDirectory = File(
            root,
            "${safeName}_${System.currentTimeMillis()}"
        ).apply {
            check(mkdirs()) {
                "Could not create the job directory."
            }
        }

        val inputApk = File(jobDirectory, "input.apk")
        val decodedDirectory = File(jobDirectory, "decoded")
        val outputDirectory = File(
            context.filesDir,
            "duality/output"
        ).apply {
            check(isDirectory || mkdirs()) {
                "Could not create the output directory."
            }
        }

        val outputApk = File(
            outputDirectory,
            "${safeName}_rebuilt_unsigned.apk"
        )

        sourceApk.copyTo(inputApk, overwrite = true)

        val config = ApktoolAndroid.configure(context)
        config.isForced = true

        // Decode the APK into editable files and resources.
        ApkDecoder(inputApk, config).decode(decodedDirectory)

        check(decodedDirectory.isDirectory) {
            "Apktool did not create the decoded directory."
        }

        // Rebuild the decoded project into an APK.
        ApkBuilder(decodedDirectory, config).build(outputApk)

        check(outputApk.isFile && outputApk.length() > 0L) {
            "Apktool did not produce a valid output file."
        }

        // Basic container validation, not a signature or installability test.
        ZipFile(outputApk).use { zip ->
            check(zip.getEntry("AndroidManifest.xml") != null) {
                "The rebuilt APK has no Android manifest."
            }
        }

        return ApktoolBuildResult(
            packageName = packageName,
            decodedDirectory = decodedDirectory,
            outputApk = outputApk,
            outputSizeBytes = outputApk.length()
        )
    }
}
