package com.overdrive.app.launcher.adb

import dadb.AdbKeyPair
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Owns the single persisted ADB RSA identity used by classic and TLS transports. */
class AdbIdentityProvider(private val directory: File) {
    data class Identity(
        val dadbKeyPair: AdbKeyPair,
        val javaKeyPair: KeyPair,
        val adbPublicKey: ByteArray,
    )

    enum class TrustState {
        NEW,
        UNVERIFIED,
        AUTHENTICATED,
        REPLACED,
    }

    data class LoadResult(val identity: Identity, val trustState: TrustState) {
        val trustLost: Boolean get() = trustState == TrustState.REPLACED
        val previouslyAuthenticated: Boolean get() = trustState == TrustState.AUTHENTICATED
    }

    fun load(): LoadResult = synchronized(lock) {
        cached?.takeIf { cachedDirectory == directory.absolutePath }?.let { return@synchronized it }
        directory.mkdirs()
        val privateFile = File(directory, PRIVATE_FILE)
        val publicFile = File(directory, PUBLIC_FILE)
        val trustFile = File(directory, TRUST_FILE)
        val hadKeyMaterial = privateFile.exists() || publicFile.exists()
        var identity = tryLoad(privateFile, publicFile)
        val trustState: TrustState
        if (identity == null) {
            privateFile.delete()
            publicFile.delete()
            trustFile.delete()
            AdbKeyPair.generate(privateFile, publicFile)
            identity = requireNotNull(tryLoad(privateFile, publicFile)) {
                "Generated ADB identity could not be validated"
            }
            trustState = if (hadKeyMaterial) TrustState.REPLACED else TrustState.NEW
        } else {
            trustState = if (trustFile.readTrustFingerprint() == fingerprint(identity)) {
                TrustState.AUTHENTICATED
            } else {
                TrustState.UNVERIFIED
            }
        }
        LoadResult(identity, trustState).also {
            cachedDirectory = directory.absolutePath
            cached = it
        }
    }

    /** Records trust only after this exact key completes a classic authenticated handshake. */
    fun markAuthenticated(identity: Identity): Boolean = synchronized(lock) {
        val current = tryLoad(File(directory, PRIVATE_FILE), File(directory, PUBLIC_FILE))
            ?: return@synchronized false
        if (!current.javaKeyPair.public.encoded.contentEquals(identity.javaKeyPair.public.encoded)) {
            return@synchronized false
        }
        val marker = fingerprint(current)
        val trustFile = File(directory, TRUST_FILE)
        val temporary = File(directory, "$TRUST_FILE.tmp")
        try {
            temporary.writeText(marker)
            try {
                Files.move(
                    temporary.toPath(),
                    trustFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    trustFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (_: Exception) {
            temporary.delete()
            return@synchronized false
        }
        val result = LoadResult(current, TrustState.AUTHENTICATED)
        cachedDirectory = directory.absolutePath
        cached = result
        true
    }

    private fun File.readTrustFingerprint(): String? = try {
        takeIf { isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun fingerprint(identity: Identity): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(identity.javaKeyPair.public.encoded),
    )

    private fun tryLoad(privateFile: File, publicFile: File): Identity? {
        return try {
            if (!privateFile.isFile || !publicFile.isFile) null
            else {
                val privateKey = parsePrivateKey(privateFile)
                val rsaPrivate = privateKey as? RSAPrivateKey
                // Android 10's Conscrypt parses dadb keys without CRT fields: take e from adbkey.pub.
                val crtExponent = (privateKey as? RSAPrivateCrtKey)?.publicExponent
                val exponent = crtExponent ?: readAdbPublicExponent(publicFile)
                if (rsaPrivate == null || exponent == null) null
                else {
                    val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                        RSAPublicKeySpec(rsaPrivate.modulus, exponent),
                    ) as RSAPublicKey
                    val adbPublic = readAndValidateAdbPublicKey(publicFile, publicKey)
                    if (adbPublic == null || (crtExponent == null && !signsFor(privateKey, publicKey))) null
                    else Identity(
                        dadbKeyPair = AdbKeyPair.read(privateFile, publicFile),
                        javaKeyPair = KeyPair(publicKey, privateKey),
                        adbPublicKey = adbPublic,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePrivateKey(file: File) = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                file.readText()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", ""),
            ),
        ),
    )

    private fun readAdbPublicExponent(file: File): BigInteger? {
        val raw = Base64.getDecoder().decode(file.readText().trim().substringBefore(' '))
        if (raw.size != ADB_PUBLIC_KEY_BYTES) return null
        val exponent = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(raw.size - 4)
        return BigInteger.valueOf(exponent.toLong() and 0xffff_ffffL)
    }

    /** Proves a key pair built from two files belongs together. */
    private fun signsFor(privateKey: java.security.PrivateKey, publicKey: RSAPublicKey): Boolean {
        val probe = "overdrive-adb-identity".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(probe)
            sign()
        }
        return Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(probe)
            verify(signature)
        }
    }

    private fun readAndValidateAdbPublicKey(file: File, expected: RSAPublicKey): ByteArray? {
        val encoded = file.readText().trim().substringBefore(' ')
        val raw = Base64.getDecoder().decode(encoded)
        if (raw.size != ADB_PUBLIC_KEY_BYTES) return null
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != RSA_WORDS) return null
        buffer.int // n0inv
        val modulusLittleEndian = ByteArray(RSA_BYTES)
        buffer.get(modulusLittleEndian)
        val modulus = BigInteger(1, modulusLittleEndian.reversedArray())
        buffer.position(buffer.position() + RSA_BYTES)
        val exponent = buffer.int.toLong() and 0xffff_ffffL
        if (modulus != expected.modulus || exponent != expected.publicExponent.toLong()) return null
        return (file.readText().trim() + "\u0000").toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val PRIVATE_FILE = "adbkey"
        private const val PUBLIC_FILE = "adbkey.pub"
        private const val TRUST_FILE = "adbkey.authenticated"
        private const val RSA_BYTES = 256
        private const val RSA_WORDS = RSA_BYTES / 4
        private const val ADB_PUBLIC_KEY_BYTES = 524
        private val lock = Any()
        @Volatile private var cachedDirectory: String? = null
        @Volatile private var cached: LoadResult? = null

        @JvmStatic
        fun forFilesDirectory(directory: File): AdbIdentityProvider = AdbIdentityProvider(directory)

        internal fun clearCacheForTests() = synchronized(lock) {
            cachedDirectory = null
            cached = null
        }
    }
}
