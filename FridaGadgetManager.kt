package com.thaz.duality

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Helpers for adding an official Frida Gadget binary to a decoded APK workspace.
 *
 * This class stages the native library and a loopback-only Gadget configuration.
 * It does NOT make Android load the library by itself. A separate, app-specific
 * startup integration is required before Gadget can run.
 *
 * Use only with APKs you own or are authorized to instrument.
 */
object FridaGadgetManager {

    data class AbiInfo(
        val abi: String,
        val libraryEntries: List<String>
    )

    data class InjectionResult(
        val abi: String,
        val gadgetLibrary: File,
        val configFile: File,
        val sha256: String,
        val sizeBytes: Long
    )

    private val supportedAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    /**
     * Detects native ABI folders present in an APK.
     * The returned list is ordered by common Android device preference.
     */
    fun inspectApk(apkFile: File): List<AbiInfo> {
        require(apkFile.isFile && apkFile.canRead()) {
            "The APK cannot be read."
        }

        val entriesByAbi = linkedMapOf<String, MutableList<String>>()

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                val parts = entry.name.split('/')
                if (
                    parts.size >= 3 &&
                    parts[0] == "lib" &&
                    parts.last().endsWith(".so") &&
                    parts[1] in supportedAbis
                ) {
                    entriesByAbi.getOrPut(parts[1]) { mutableListOf() }
                        .add(entry.name)
                }
            }
        }

        return supportedAbis.mapNotNull { abi ->
            entriesByAbi[abi]?.let { AbiInfo(abi, it.sorted()) }
        }
    }

    /**
     * Chooses the first supported ABI in the supplied APK's native libraries.
     * Pure Java APKs return null and need an explicit target ABI choice.
     */
    fun detectPrimaryAbi(apkFile: File): String? =
        inspectApk(apkFile).firstOrNull()?.abi

    /**
     * Detects the ABI from an ELF shared-library header.
     */
    fun detectAbi(gadgetSo: File): String {
        require(gadgetSo.isFile && gadgetSo.canRead() && gadgetSo.length() > 20L) {
            "The selected Gadget file is missing, unreadable, or too small."
        }

        FileInputStream(gadgetSo).use { input ->
            val header = ByteArray(20)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }
            require(offset == header.size) {
                "Could not read the ELF architecture header."
            }
            require(
                header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            ) {
                "Selected file is not an ELF shared library."
            }
            require((header[5].toInt() and 0xFF) == 1) {
                "Only little-endian Android ELF libraries are supported."
            }

            val elfClass = header[4].toInt() and 0xFF
            val machine = (header[18].toInt() and 0xFF) or
                ((header[19].toInt() and 0xFF) shl 8)

            return when {
                machine == 40 && elfClass == 1 -> "armeabi-v7a"
                machine == 183 && elfClass == 2 -> "arm64-v8a"
                machine == 3 && elfClass == 1 -> "x86"
                machine == 62 && elfClass == 2 -> "x86_64"
                else -> throw IllegalArgumentException(
                    "Unsupported Gadget ELF architecture (machine=$machine, class=$elfClass)."
                )
            }
        }
    }

    /**
     * Copies a verified ELF shared library into the decoded Apktool project.
     * gadgetSo must be the official Frida Gadget binary for the chosen ABI.
     *
     * This stages the binary only. It does not patch smali or guarantee that
     * the target app will load Gadget.
     */
    fun stageGadget(
        decodedDirectory: File,
        gadgetSo: File,
        abi: String
    ): InjectionResult {
        require(abi in supportedAbis) {
            "Unsupported Android ABI: $abi"
        }
        require(decodedDirectory.isDirectory) {
            "Decoded APK directory does not exist."
        }
        require(gadgetSo.isFile && gadgetSo.canRead() && gadgetSo.length() > 4L) {
            "The Frida Gadget library is missing, unreadable, or empty."
        }

        validateElfArchitecture(gadgetSo, abi)

        val libRoot = File(decodedDirectory, "lib").canonicalFile
        val abiDirectory = File(libRoot, abi).canonicalFile
        require(
            abiDirectory.toPath().startsWith(libRoot.toPath())
        ) {
            "The ABI destination is outside the decoded APK."
        }

        if (!abiDirectory.exists() && !abiDirectory.mkdirs()) {
            throw IllegalStateException("Could not create lib/$abi in the decoded APK.")
        }

        val gadgetDestination = File(abiDirectory, "libfrida-gadget.so")
        copySynced(gadgetSo, gadgetDestination)

        val configDestination = File(abiDirectory, "libfrida-gadget.config.so")
        configDestination.writeText(
            """
            {
              "interaction": {
                "type": "listen",
                "address": "127.0.0.1",
                "port": 27042,
                "on_port_conflict": "fail"
              }
            }
            """.trimIndent() + "\n"
        )

        return InjectionResult(
            abi = abi,
            gadgetLibrary = gadgetDestination,
            configFile = configDestination,
            sha256 = sha256(gadgetDestination),
            sizeBytes = gadgetDestination.length()
        )
    }

    /**
     * Checks the ELF class and e_machine fields before copying a native library.
     */
    private fun validateElfArchitecture(file: File, abi: String) {
        FileInputStream(file).use { input ->
            val header = ByteArray(20)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }

            require(offset == header.size) {
                "The selected Gadget file is too small to be an ELF library."
            }
            require(
                header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            ) {
                "The selected file is not an ELF shared library. Extract libfrida-gadget.so from the official Frida Gadget archive first."
            }

            val elfClass = header[4].toInt() and 0xFF
            val dataEncoding = header[5].toInt() and 0xFF
            require(dataEncoding == 1) {
                "Only little-endian Android ELF libraries are supported."
            }

            val machine = (header[18].toInt() and 0xFF) or
                ((header[19].toInt() and 0xFF) shl 8)

            val expected = when (abi) {
                "armeabi-v7a" -> 40 to 1
                "arm64-v8a" -> 183 to 2
                "x86" -> 3 to 1
                "x86_64" -> 62 to 2
                else -> error("Unsupported Android ABI: $abi")
            }

            require(machine == expected.first && elfClass == expected.second) {
                "Gadget architecture mismatch: selected ABI is $abi, but the ELF header does not match."
            }
        }
    }

    private fun copySynced(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
    }
}
