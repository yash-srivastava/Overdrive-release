package com.overdrive.app.launcher.adb

import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Local-only TLS 1.3 ADB transport used to request the classic network listener. */
class AdbTlsTransport(
    private val identity: AdbIdentityProvider.Identity,
    private val certificate: X509Certificate,
    private val connectTimeoutMs: Int = 3_000,
    private val socketTimeoutMs: Int = 5_000,
    private val attemptTimeoutMs: Int = 30_000,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    sealed class Result(open val openDispatched: Boolean) {
        data object RestartObserved : Result(true)
        data object RestartAmbiguous : Result(true)
        data class Complete(val reply: ByteArray) : Result(true)
        data object AuthorizationRejected : Result(false)
        data class TlsFailure(
            override val openDispatched: Boolean = false,
        ) : Result(openDispatched)
        data class Timeout(override val openDispatched: Boolean) : Result(openDispatched)
        data class Cancelled(override val openDispatched: Boolean) : Result(openDispatched)
        data class TransportFailure(
            val reason: String,
            override val openDispatched: Boolean = false,
        ) : Result(openDispatched)
    }

    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeSocket: Socket? = null

    fun openClassicListener(address: InetAddress, port: Int): Result {
        if (port !in 1..65535 || !isLocalAddress(address)) {
            return Result.TransportFailure("non_local_endpoint")
        }
        if (cancelled.get()) return Result.Cancelled(false)
        val deadlineNanos = monotonicNanos() + TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMs.toLong())
        var openDispatched = false
        var plain: Socket? = null
        var tls: SSLSocket? = null
        val deadlineWatcher = Thread({
            val remaining = deadlineNanos - monotonicNanos()
            if (remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(remaining)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            try { activeSocket?.close() } catch (_: Exception) {}
        }, "AdbTlsDeadline").apply { isDaemon = true }
        deadlineWatcher.start()
        return try {
            plain = Socket()
            activeSocket = plain
            applyRemainingTimeout(plain, deadlineNanos)
            plain.connect(
                InetSocketAddress(address, port),
                minOf(connectTimeoutMs, remainingMillis(deadlineNanos)),
            )
            beforeIo(plain, deadlineNanos)
            AdbWireProtocol.writePacket(
                plain.getOutputStream(),
                AdbWireProtocol.Packet(
                    AdbWireProtocol.CNXN,
                    AdbWireProtocol.VERSION,
                    AdbWireProtocol.MAX_PAYLOAD,
                    "host::features=\u0000".toByteArray(),
                ),
            )
            beforeIo(plain, deadlineNanos)
            val response = AdbWireProtocol.readPacket(plain.getInputStream())
            if (response.command == AdbWireProtocol.CNXN ||
                response.command == AdbWireProtocol.AUTH
            ) {
                return authenticate(plain, deadlineNanos, response) {
                    openDispatched = true
                }
            }
            if (response.command != AdbWireProtocol.STLS ||
                response.arg0 != AdbWireProtocol.STLS_VERSION
            ) {
                return Result.TransportFailure("unexpected_plain_handshake_packet")
            }
            beforeIo(plain, deadlineNanos)
            AdbWireProtocol.writePacket(
                plain.getOutputStream(),
                AdbWireProtocol.Packet(
                    AdbWireProtocol.STLS,
                    AdbWireProtocol.STLS_VERSION,
                    0,
                    ByteArray(0),
                ),
            )
            beforeIo(plain, deadlineNanos)
            tls = createContext().socketFactory.createSocket(
                plain,
                address.hostAddress,
                port,
                true,
            ) as SSLSocket
            activeSocket = tls
            tls.enabledProtocols = arrayOf("TLSv1.3")
            applyRemainingTimeout(tls, deadlineNanos)
            tls.startHandshake()
            beforeIo(tls, deadlineNanos)
            authenticate(tls, deadlineNanos, null) { openDispatched = true }
        } catch (_: CancelledException) {
            Result.Cancelled(openDispatched)
        } catch (_: DeadlineException) {
            Result.Timeout(openDispatched)
        } catch (_: SocketTimeoutException) {
            Result.Timeout(openDispatched)
        } catch (_: javax.net.ssl.SSLException) {
            if (deadlineExpired(deadlineNanos)) Result.Timeout(openDispatched)
            else Result.TlsFailure(openDispatched)
        } catch (_: EOFException) {
            if (openDispatched) Result.RestartAmbiguous
            else Result.TransportFailure("eof_before_open")
        } catch (error: Exception) {
            if (deadlineExpired(deadlineNanos)) Result.Timeout(openDispatched)
            else Result.TransportFailure(error.javaClass.simpleName, openDispatched)
        } finally {
            deadlineWatcher.interrupt()
            try { tls?.close() } catch (_: Exception) {}
            if (tls == null) try { plain?.close() } catch (_: Exception) {}
            activeSocket = null
        }
    }

    private fun authenticate(
        socket: Socket,
        deadlineNanos: Long,
        firstPacket: AdbWireProtocol.Packet?,
        onOpenDispatching: () -> Unit,
    ): Result {
        val input = socket.inputStream
        val output = socket.outputStream
        var authStage = 0
        var pendingPacket = firstPacket
        repeat(6) {
            beforeIo(socket, deadlineNanos)
            val packet = pendingPacket ?: AdbWireProtocol.readPacket(input, AdbWireProtocol.VERSION)
            pendingPacket = null
            when (packet.command) {
                AdbWireProtocol.CNXN -> {
                    val stream = AdbWireProtocol.openService(
                        input,
                        output,
                        "tcpip:5555",
                        minOf(packet.arg0, AdbWireProtocol.VERSION),
                        beforeIo = { beforeIo(socket, deadlineNanos) },
                        onOpenDispatching = onOpenDispatching,
                    )
                    return when (stream) {
                        is AdbWireProtocol.StreamResult.Complete -> Result.Complete(stream.payload)
                        is AdbWireProtocol.StreamResult.Incomplete -> when (stream.reason) {
                            "eof" -> Result.RestartAmbiguous
                            "timeout" -> Result.Timeout(true)
                            else -> Result.TransportFailure("incomplete_${stream.reason}", true)
                        }
                    }
                }
                AdbWireProtocol.AUTH -> {
                    if (packet.arg0 != AdbWireProtocol.AUTH_TOKEN) {
                        return Result.TransportFailure("unexpected_auth_kind")
                    }
                    when (authStage) {
                        0 -> {
                            val signature = AdbAuthTokenSigner.sign(
                                packet.payload,
                                identity.javaKeyPair.private,
                            )
                            beforeIo(socket, deadlineNanos)
                            AdbWireProtocol.writePacket(
                                output,
                                AdbWireProtocol.Packet(
                                    AdbWireProtocol.AUTH,
                                    AdbWireProtocol.AUTH_SIGNATURE,
                                    0,
                                    signature,
                                ),
                            )
                            authStage = 1
                        }
                        1 -> {
                            beforeIo(socket, deadlineNanos)
                            AdbWireProtocol.writePacket(
                                output,
                                AdbWireProtocol.Packet(
                                    AdbWireProtocol.AUTH,
                                    AdbWireProtocol.AUTH_PUBLIC_KEY,
                                    0,
                                    identity.adbPublicKey,
                                ),
                            )
                            authStage = 2
                        }
                        else -> return Result.AuthorizationRejected
                    }
                }
                else -> return Result.TransportFailure("unexpected_handshake_packet")
            }
        }
        return Result.TransportFailure("handshake_packet_limit")
    }

    private fun beforeIo(socket: Socket, deadlineNanos: Long) {
        checkCancelled()
        applyRemainingTimeout(socket, deadlineNanos)
    }

    private fun applyRemainingTimeout(socket: Socket, deadlineNanos: Long) {
        socket.soTimeout = minOf(socketTimeoutMs, remainingMillis(deadlineNanos)).coerceAtLeast(1)
    }

    private fun remainingMillis(deadlineNanos: Long): Int {
        val remaining = deadlineNanos - monotonicNanos()
        if (remaining <= 0) throw DeadlineException()
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun deadlineExpired(deadlineNanos: Long): Boolean = monotonicNanos() >= deadlineNanos

    private fun createContext(): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setKeyEntry("adb", identity.javaKeyPair.private, CHAR_PASSWORD, arrayOf(certificate))
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(store, CHAR_PASSWORD)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                require(!chain.isNullOrEmpty()) { "TLS peer supplied no certificate" }
                chain.first().checkValidity()
            }
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(keyManagers.keyManagers, arrayOf<TrustManager>(trust), null)
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancelledException()
    }

    override fun close() {
        cancelled.set(true)
        try { activeSocket?.close() } catch (_: Exception) {}
    }

    companion object {
        private val CHAR_PASSWORD = "adb-local".toCharArray()

        fun isLocalAddress(address: InetAddress): Boolean =
            address.isLoopbackAddress || try {
                NetworkInterface.getByInetAddress(address) != null
            } catch (_: Exception) {
                false
            }
    }

    private class CancelledException : Exception()
    private class DeadlineException : Exception()
}

/** Encodes an already-digested ADB AUTH token as a raw PKCS#1 v1.5 SHA-1 signature. */
internal object AdbAuthTokenSigner {
    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    fun sign(token: ByteArray, privateKey: PrivateKey): ByteArray {
        require(token.size == SHA1_BYTES) { "ADB AUTH token must be a SHA-1 digest" }
        val keyBytes = (privateKey as? java.security.interfaces.RSAKey)
            ?.modulus?.bitLength()?.let { (it + 7) / 8 }
            ?: throw IllegalArgumentException("ADB identity is not RSA")
        val digestInfoSize = SHA1_DIGEST_INFO_PREFIX.size + token.size
        require(keyBytes >= digestInfoSize + 11) { "ADB RSA key is too short" }
        val encoded = ByteArray(keyBytes) { 0xff.toByte() }
        encoded[0] = 0
        encoded[1] = 1
        val separator = keyBytes - digestInfoSize - 1
        encoded[separator] = 0
        SHA1_DIGEST_INFO_PREFIX.copyInto(encoded, separator + 1)
        token.copyInto(encoded, separator + 1 + SHA1_DIGEST_INFO_PREFIX.size)
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, privateKey)
            doFinal(encoded)
        }
    }

    private const val SHA1_BYTES = 20
}
