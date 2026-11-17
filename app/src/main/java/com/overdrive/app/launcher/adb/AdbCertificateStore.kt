package com.overdrive.app.launcher.adb

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/** Persists a self-signed client certificate without replacing the ADB RSA key. */
class AdbCertificateStore(
    private val directory: File,
    private val provider: BouncyCastleProvider = BouncyCastleProvider(),
) {
    data class Result(val certificate: X509Certificate, val repaired: Boolean)

    fun loadOrCreate(identity: AdbIdentityProvider.Identity): Result {
        directory.mkdirs()
        val file = File(directory, CERTIFICATE_FILE)
        val existing = read(file)
        if (existing != null && validForIdentity(existing, identity)) {
            return Result(existing, false)
        }
        val certificate = create(identity)
        val temporary = File(directory, "$CERTIFICATE_FILE.tmp")
        temporary.writeBytes(certificate.encoded)
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Unable to persist ADB client certificate", error)
        }
        return Result(certificate, true)
    }

    private fun read(file: File): X509Certificate? {
        return try {
            if (!file.isFile) null
            else file.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validForIdentity(
        certificate: X509Certificate,
        identity: AdbIdentityProvider.Identity,
    ): Boolean = try {
        certificate.checkValidity()
        certificate.verify(identity.javaKeyPair.public)
        certificate.publicKey.encoded.contentEquals(identity.javaKeyPair.public.encoded)
    } catch (_: Exception) {
        false
    }

    private fun create(identity: AdbIdentityProvider.Identity): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=OverDrive ADB")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(160, SecureRandom()).abs().max(BigInteger.ONE),
            Date(now - CLOCK_SKEW_MS),
            Date(now + VALIDITY_MS),
            subject,
            identity.javaKeyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(identity.javaKeyPair.private)
        return JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(signer))
            .also { it.verify(identity.javaKeyPair.public) }
    }

    companion object {
        private const val CERTIFICATE_FILE = "adbkey.cert"
        private const val CLOCK_SKEW_MS = 24L * 60 * 60 * 1000
        private const val VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
