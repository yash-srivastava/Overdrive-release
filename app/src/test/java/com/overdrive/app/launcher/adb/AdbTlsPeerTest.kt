package com.overdrive.app.launcher.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AdbTlsPeerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun loopbackPeerNegotiatesTls13AndSeesSharedClientIdentity() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val identity = AdbIdentityProvider(directory).load().identity
        val certificate = AdbCertificateStore(directory).loadOrCreate(identity).certificate
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val seenCertificate = AtomicReference<X509Certificate>()
        val protocol = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val peer = Thread {
            try {
                listener.accept().use { plain ->
                    val cnxn = AdbWireProtocol.readPacket(plain.getInputStream())
                    assertEquals(AdbWireProtocol.CNXN, cnxn.command)
                    AdbWireProtocol.writePacket(
                        plain.getOutputStream(),
                        AdbWireProtocol.Packet(
                            AdbWireProtocol.STLS, AdbWireProtocol.STLS_VERSION, 0, ByteArray(0),
                        ),
                    )
                    val stls = AdbWireProtocol.readPacket(plain.getInputStream())
                    assertEquals(AdbWireProtocol.STLS, stls.command)
                    val tls = serverContext(identity, certificate).socketFactory.createSocket(
                        plain, "127.0.0.1", listener.localPort, false,
                    ) as SSLSocket
                    tls.use {
                        it.useClientMode = false
                        it.needClientAuth = true
                        it.enabledProtocols = arrayOf("TLSv1.3")
                        it.startHandshake()
                        protocol.set(it.session.protocol)
                        seenCertificate.set(it.session.peerCertificates.first() as X509Certificate)
                        AdbWireProtocol.writePacket(
                            it.outputStream,
                            AdbWireProtocol.Packet(
                                AdbWireProtocol.CNXN,
                                AdbWireProtocol.VERSION,
                                AdbWireProtocol.MAX_PAYLOAD,
                                "device::\u0000".toByteArray(),
                            ),
                        )
                        val open = AdbWireProtocol.readPacket(it.inputStream, AdbWireProtocol.VERSION)
                        assertEquals("tcpip:5555\u0000", String(open.payload))
                        AdbWireProtocol.writePacket(
                            it.outputStream,
                            AdbWireProtocol.Packet(AdbWireProtocol.OKAY, 7, open.arg0, ByteArray(0)),
                        )
                        AdbWireProtocol.writePacket(
                            it.outputStream,
                            AdbWireProtocol.Packet(AdbWireProtocol.CLSE, 7, open.arg0, ByteArray(0)),
                        )
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                listener.close()
                done.countDown()
            }
        }
        peer.start()
        val result = AdbTlsTransport(identity, certificate)
            .use { it.openClassicListener(InetAddress.getByName("127.0.0.1"), listener.localPort) }
        assertTrue(result is AdbTlsTransport.Result.Complete)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals("TLSv1.3", protocol.get())
        assertArrayEquals(identity.javaKeyPair.public.encoded, seenCertificate.get().publicKey.encoded)
        assertTrue(listener.isClosed)
    }

    @Test fun plaintextCnxnContinuesWithoutRequestingTlsOrSendingASecondCnxn() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val identity = AdbIdentityProvider(directory).load().identity
        val certificate = AdbCertificateStore(directory).loadOrCreate(identity).certificate
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                listener.accept().use { socket ->
                    val host = AdbWireProtocol.readPacket(socket.inputStream)
                    assertEquals(AdbWireProtocol.CNXN, host.command)
                    AdbWireProtocol.writePacket(
                        socket.outputStream,
                        AdbWireProtocol.Packet(
                            AdbWireProtocol.CNXN,
                            AdbWireProtocol.VERSION,
                            AdbWireProtocol.MAX_PAYLOAD,
                            "device::\u0000".toByteArray(),
                        ),
                    )
                    val open = AdbWireProtocol.readPacket(socket.inputStream, AdbWireProtocol.VERSION)
                    assertEquals(AdbWireProtocol.OPEN, open.command)
                    assertEquals("tcpip:5555\u0000", String(open.payload))
                    AdbWireProtocol.writePacket(
                        socket.outputStream,
                        AdbWireProtocol.Packet(AdbWireProtocol.OKAY, 9, open.arg0, ByteArray(0)),
                    )
                    AdbWireProtocol.writePacket(
                        socket.outputStream,
                        AdbWireProtocol.Packet(AdbWireProtocol.CLSE, 9, open.arg0, ByteArray(0)),
                    )
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                listener.close()
                done.countDown()
            }
        }.start()

        val result = AdbTlsTransport(identity, certificate)
            .use { it.openClassicListener(InetAddress.getByName("127.0.0.1"), listener.localPort) }

        assertTrue(result is AdbTlsTransport.Result.Complete)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
    }

    @Test fun plaintextAuthChallengeIsAnsweredBeforeClassicServiceOpen() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val identity = AdbIdentityProvider(directory).load().identity
        val certificate = AdbCertificateStore(directory).loadOrCreate(identity).certificate
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                listener.accept().use { socket ->
                    assertEquals(AdbWireProtocol.CNXN, AdbWireProtocol.readPacket(socket.inputStream).command)
                    val token = ByteArray(20) { it.toByte() }
                    AdbWireProtocol.writePacket(
                        socket.outputStream,
                        AdbWireProtocol.Packet(
                            AdbWireProtocol.AUTH,
                            AdbWireProtocol.AUTH_TOKEN,
                            0,
                            token,
                        ),
                    )
                    val signature = AdbWireProtocol.readPacket(socket.inputStream, AdbWireProtocol.VERSION)
                    assertEquals(AdbWireProtocol.AUTH, signature.command)
                    assertEquals(AdbWireProtocol.AUTH_SIGNATURE, signature.arg0)
                    AdbWireProtocol.writePacket(
                        socket.outputStream,
                        AdbWireProtocol.Packet(
                            AdbWireProtocol.CNXN,
                            AdbWireProtocol.VERSION,
                            AdbWireProtocol.MAX_PAYLOAD,
                            "device::\u0000".toByteArray(),
                        ),
                    )
                    val open = AdbWireProtocol.readPacket(socket.inputStream, AdbWireProtocol.VERSION)
                    assertEquals(AdbWireProtocol.OPEN, open.command)
                    AdbWireProtocol.writePacket(
                        socket.outputStream,
                        AdbWireProtocol.Packet(AdbWireProtocol.OKAY, 11, open.arg0, ByteArray(0)),
                    )
                    AdbWireProtocol.writePacket(
                        socket.outputStream,
                        AdbWireProtocol.Packet(AdbWireProtocol.CLSE, 11, open.arg0, ByteArray(0)),
                    )
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                listener.close()
                done.countDown()
            }
        }.start()

        val result = AdbTlsTransport(identity, certificate)
            .use { it.openClassicListener(InetAddress.getByName("127.0.0.1"), listener.localPort) }

        assertTrue(result is AdbTlsTransport.Result.Complete)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
    }

    @Test fun tlsAuthBranchesSendSignatureThenTheClassicPublicKey() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val identity = AdbIdentityProvider(directory).load().identity
        val certificate = AdbCertificateStore(directory).loadOrCreate(identity).certificate
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val verified = AtomicReference(false)
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                listener.accept().use { plain ->
                    AdbWireProtocol.readPacket(plain.inputStream)
                    AdbWireProtocol.writePacket(
                        plain.outputStream,
                        AdbWireProtocol.Packet(AdbWireProtocol.STLS, AdbWireProtocol.STLS_VERSION, 0, ByteArray(0)),
                    )
                    AdbWireProtocol.readPacket(plain.inputStream)
                    val tls = serverContext(identity, certificate).socketFactory.createSocket(
                        plain, "127.0.0.1", listener.localPort, false,
                    ) as SSLSocket
                    tls.use {
                        it.useClientMode = false
                        it.needClientAuth = true
                        it.enabledProtocols = arrayOf("TLSv1.3")
                        it.startHandshake()
                        val token = ByteArray(20) { index -> index.toByte() }
                        AdbWireProtocol.writePacket(
                            it.outputStream,
                            AdbWireProtocol.Packet(
                                AdbWireProtocol.AUTH, AdbWireProtocol.AUTH_TOKEN, 0, token,
                            ),
                        )
                        val signature = AdbWireProtocol.readPacket(it.inputStream, AdbWireProtocol.VERSION)
                        assertEquals(AdbWireProtocol.AUTH_SIGNATURE, signature.arg0)
                        val decoded = Cipher.getInstance("RSA/ECB/NoPadding").run {
                            init(Cipher.DECRYPT_MODE, identity.javaKeyPair.public)
                            doFinal(signature.payload)
                        }
                        val expectedTail = byteArrayOf(
                            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
                        ) + token
                        assertEquals(0, decoded[0].toInt())
                        assertEquals(1, decoded[1].toInt())
                        val separator = (2 until decoded.size).firstOrNull { decoded[it] == 0.toByte() } ?: -1
                        assertTrue(separator >= 10)
                        assertTrue(decoded.sliceArray(2 until separator).all { byte -> byte == 0xff.toByte() })
                        assertArrayEquals(expectedTail, decoded.copyOfRange(separator + 1, decoded.size))
                        AdbWireProtocol.writePacket(
                            it.outputStream,
                            AdbWireProtocol.Packet(
                                AdbWireProtocol.AUTH, AdbWireProtocol.AUTH_TOKEN, 0, token,
                            ),
                        )
                        val publicKey = AdbWireProtocol.readPacket(it.inputStream, AdbWireProtocol.VERSION)
                        assertEquals(AdbWireProtocol.AUTH_PUBLIC_KEY, publicKey.arg0)
                        assertArrayEquals(identity.adbPublicKey, publicKey.payload)
                        AdbWireProtocol.writePacket(
                            it.outputStream,
                            AdbWireProtocol.Packet(
                                AdbWireProtocol.AUTH, AdbWireProtocol.AUTH_TOKEN, 0, token,
                            ),
                        )
                        verified.set(true)
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                listener.close()
                done.countDown()
            }
        }.start()
        val result = AdbTlsTransport(identity, certificate)
            .use { it.openClassicListener(InetAddress.getByName("127.0.0.1"), listener.localPort) }
        assertTrue(result is AdbTlsTransport.Result.AuthorizationRejected)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertTrue(verified.get())
    }

    private fun serverContext(
        identity: AdbIdentityProvider.Identity,
        certificate: X509Certificate,
    ): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setKeyEntry("server", identity.javaKeyPair.private, PASSWORD, arrayOf(certificate))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, PASSWORD)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                require(!chain.isNullOrEmpty())
            }
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(keys.keyManagers, arrayOf<TrustManager>(trust), null)
        }
    }

    companion object { private val PASSWORD = "test".toCharArray() }
}
