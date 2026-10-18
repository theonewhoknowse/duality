
package com.thaz.duality

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

data class ApkSigningResult(
    val outputApk: File,
    val certificateSha256: String,
    val outputSizeBytes: Long
)

object ApkSigningEngine {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    // New alias forces Android Keystore to generate a key with the
    // updated digest permissions instead of reusing the old key.
    private const val KEY_ALIAS = "duality_apk_signing_key_v2"

    private data class SigningIdentity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    fun sign(context: Context, unsignedApk: File): ApkSigningResult {
        require(unsignedApk.isFile && unsignedApk.canRead()) {
            "The APK to sign cannot be read."
        }

        val outputDirectory = File(
            context.filesDir,
            "duality/output"
        ).apply {
            check(isDirectory || mkdirs()) {
                "Could not create the signing output directory."
            }
        }

        val baseName = unsignedApk.nameWithoutExtension
            .removeSuffix("_unsigned")
            .removeSuffix("_rebuilt")

        val outputApk = File(
            outputDirectory,
            "${baseName}_signed.apk"
        )

        check(unsignedApk.canonicalFile != outputApk.canonicalFile) {
            "The input and output APK paths must be different."
        }

        val identity = getOrCreateIdentity()
        outputApk.delete()

        try {
            val signerConfig = ApkSigner.SignerConfig.Builder(
                "Duality",
                identity.privateKey,
                listOf(identity.certificate)
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(outputApk)
                .setMinSdkVersion(16)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()

            check(outputApk.isFile && outputApk.length() > 0L) {
                "The signer did not produce an output APK."
            }

            val verification = ApkVerifier.Builder(outputApk)
                .build()
                .verify()

            check(verification.isVerified) {
                "APK signature verification failed: " +
                        verification.errors.joinToString()
            }

            return ApkSigningResult(
                outputApk = outputApk,
                certificateSha256 = sha256(identity.certificate.encoded),
                outputSizeBytes = outputApk.length()
            )
        } catch (error: Exception) {
            outputApk.delete()

            throw IllegalStateException(
                "APK signing failed: " +
                        (error.message ?: error.javaClass.simpleName),
                error
            )
        }
    }

    private fun getOrCreateIdentity(): SigningIdentity {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val startDate = Date()

            val endDate = Calendar.getInstance().apply {
                time = startDate
                add(Calendar.YEAR, 25)
            }.time

            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEYSTORE_PROVIDER
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or
                        KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(
                    KeyProperties.DIGEST_SHA1,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA512
                )
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
                )
                .setCertificateSubject(
                    X500Principal("CN=Duality APK Signer")
                )
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(startDate)
                .setCertificateNotAfter(endDate)
                .build()

            generator.initialize(spec)
            generator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(
            KEY_ALIAS,
            null
        ) as? PrivateKey
            ?: throw IllegalStateException(
                "The Duality signing private key is unavailable."
            )

        val certificate = keyStore.getCertificate(
            KEY_ALIAS
        ) as? X509Certificate
            ?: throw IllegalStateException(
                "The Duality signing certificate is unavailable."
            )

        return SigningIdentity(
            privateKey = privateKey,
            certificate = certificate
        )
    }

    private fun sha256(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { byte ->
                "%02X".format(byte.toInt() and 0xFF)
            }
    }
}
