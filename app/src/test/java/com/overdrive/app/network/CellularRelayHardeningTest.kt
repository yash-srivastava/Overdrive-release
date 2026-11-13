package com.overdrive.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Pure/JVM-level regression coverage for proxy credential isolation and admission. */
class CellularRelayHardeningTest {

    @Test
    fun originNeverSeesProxyCredentialOrProxyConnectionHeader() {
        val head = "GET http://example.com/data HTTP/1.1\r\n" +
            "Host: example.com\r\n" +
            "pRoXy-AuThOrIzAtIoN: Bearer sensitive-client-token\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Authorization: Bearer origin-token\r\n\r\n"
        val clean = CellularRelay.originRequestWithoutProxyHeaders(head)
        assertNotNull(clean)
        assertFalse(clean!!.contains("sensitive-client-token"))
        assertFalse(clean.contains("Proxy-Connection"))
        assertTrue(clean.contains("Authorization: Bearer origin-token\r\n"))
        assertTrue(clean.contains("Host: example.com\r\n"))
        assertTrue(clean.endsWith("\r\n\r\n"))
    }

    @Test
    fun plainRequestsWithoutProxyHeadersKeepTheirOriginalBytes() {
        val head = "POST http://example.com/submit HTTP/1.1\r\nHost: example.com\r\nContent-Length: 0\r\n\r\n"
        assertEquals(head, CellularRelay.originRequestWithoutProxyHeaders(head))
    }

    @Test
    fun malformedAndFoldedHeadersAreRejectedRatherThanLeaked() {
        assertNull(CellularRelay.originRequestWithoutProxyHeaders(
            "GET http://example.com/ HTTP/1.1\r\nProxy-Authorization: secret\r\n leaked\r\n\r\n"))
        assertNull(CellularRelay.originRequestWithoutProxyHeaders(
            "GET http://example.com/ HTTP/1.1\r\nProxy-Authorization : secret\r\n\r\n"))
        assertNull(CellularRelay.originRequestWithoutProxyHeaders(
            "GET http://example.com/ HTTP/1.1\nProxy-Authorization: secret\n\n"))
    }

    @Test
    fun admissionGateEnforcesCapAndReusesReleasedSlots() {
        val gate = RelayAdmissionGate(2)
        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        assertEquals(2, gate.activeCount())
        gate.release()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        gate.release()
        assertEquals(0, gate.activeCount())
    }

    @Test
    fun simultaneousAttemptsCannotExceedAdmissionLimit() {
        val gate = RelayAdmissionGate(8)
        val ready = CountDownLatch(32)
        val beginGate = CountDownLatch(1)
        val finished = CountDownLatch(32)
        val admitted = AtomicInteger()
        val threads = (0 until 32).map {
            Thread {
                ready.countDown()
                try {
                    beginGate.await()
                    if (gate.tryAcquire()) admitted.incrementAndGet()
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            beginGate.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(8, admitted.get())
            assertEquals(8, gate.activeCount())
        } finally {
            beginGate.countDown()
            threads.forEach { it.join(1000) }
            repeat(admitted.get()) { gate.release() }
        }
    }
}
