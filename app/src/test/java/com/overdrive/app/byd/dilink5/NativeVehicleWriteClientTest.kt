package com.overdrive.app.byd.dilink5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVehicleWriteClientTest {
    @Test
    fun coldCaptureNeverAdoptsStopBeforeInitialOfferSampling() {
        val boundary = NativeVehicleWriteBoundary(
            { 10123 },
            { _, _, _ -> NativeVehicleWriteBoundary.TransportReply.handled(0) },
        )
        var offered: NativeVehicleWriteClient.CaptureTransport? = null
        var queries = 0
        var bootstraps = 0

        val olderOpen = NativeVehicleWriteClient.captureTailgateStopGeneration(offered) {
            bootstraps++
            // The daemon processes STOP before it handles the asynchronous bootstrap broadcast.
            boundary.cancelTailgateOpen()
            offered = object : NativeVehicleWriteClient.CaptureTransport {
                override fun transact(code: Int, descriptor: String): Long {
                    queries++
                    return boundary.captureTailgateStopGeneration()
                }
            }
        }

        assertEquals(null, olderOpen)
        assertEquals(1, bootstraps)
        assertEquals(0, queries)

        // Bootstrap still succeeds. Only a new user request can use the now-warm authority.
        val newerOpen = NativeVehicleWriteClient.captureTailgateStopGeneration(offered) {
            throw AssertionError("A warm capture must not bootstrap or wait")
        }
        assertEquals(1L, newerOpen)
        assertEquals(1, queries)
    }

    @Test
    fun coldCaptureFailsClosedWithoutAnyOffer() {
        var bootstraps = 0
        assertEquals(null, NativeVehicleWriteClient.captureTailgateStopGeneration(null) {
            bootstraps++
        })
        assertEquals(1, bootstraps)
    }

    @Test
    fun captureTransactionUsesExactCodeDescriptorAndDecodesGeneration() {
        var code = -1
        var descriptor = ""
        val captured = NativeVehicleWriteClient.captureTailgateStopGeneration(
            object : NativeVehicleWriteClient.CaptureTransport {
                override fun transact(requestCode: Int, requestDescriptor: String): Long? {
                    code = requestCode
                    descriptor = requestDescriptor
                    return 41L
                }
            },
        )

        assertEquals(41L, captured)
        assertEquals(NativeVehicleWriteService.TX_CAPTURE_TAILGATE_STOP_GENERATION, code)
        assertEquals(NativeVehicleWriteBoundary.DESCRIPTOR, descriptor)
        assertEquals(null, NativeVehicleWriteClient.captureTailgateStopGeneration(
            object : NativeVehicleWriteClient.CaptureTransport {
                override fun transact(code: Int, descriptor: String) = -1L
            },
        ))
    }

    @Test
    fun requestEnvelopeCarriesCapturedStopGenerationThroughFinalField() {
        val written = mutableListOf<Any?>()
        val owner = Any()
        NativeVehicleWriteClient.writeRequestEnvelope(
            object : NativeVehicleWriteClient.RequestEnvelopeWriter {
                override fun writeInterfaceToken(value: String) { written += value }
                override fun writeInt(value: Int) { written += value }
                override fun writeString(value: String?) { written += value }
                override fun writeLong(value: Long) { written += value }
                override fun writeOwner(value: Any) { written += value }
            },
            NativeVehicleAction.TAILGATE_OPEN,
            "mode-generation",
            8_000L,
            17L,
            owner,
            23L,
        )

        assertEquals(
            listOf(
                NativeVehicleWriteBoundary.DESCRIPTOR,
                NativeVehicleAction.TAILGATE_OPEN.device,
                NativeVehicleAction.TAILGATE_OPEN.featureId,
                NativeVehicleAction.TAILGATE_OPEN.value,
                "mode-generation",
                8_000L,
                17L,
                owner,
                23L,
            ),
            written,
        )
    }

    @Test
    fun completeCanonicalRepliesPreserveStatusAndOptionalPresence() {
        val accepted = decode(
            NativeVehicleWriteBoundary.Kind.ACCEPTED.ordinal,
            1, 0, 0, 0,
        )
        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED, accepted.kind)
        assertTrue(accepted.hasStatus)
        assertEquals(0, accepted.status)
        assertFalse(accepted.hasOptionalWord)
        assertFalse(accepted.observed)

        val positive = decode(
            NativeVehicleWriteBoundary.Kind.ACCEPTED.ordinal,
            1, 9, 1, 77, 0,
        )
        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED, positive.kind)
        assertTrue(positive.hasOptionalWord)
        assertEquals(77, positive.optionalWord)

        val rejected = decode(
            NativeVehicleWriteBoundary.Kind.REJECTED.ordinal,
            1, -4, 0, 0,
        )
        assertEquals(NativeVehicleWriteBoundary.Kind.REJECTED, rejected.kind)
        assertEquals(-4, rejected.status)
    }

    @Test
    fun advertisedStatusOrOptionalWordCannotBeTruncated() {
        assertEquals(
            NativeVehicleWriteBoundary.Kind.MISSING_STATUS,
            decode(NativeVehicleWriteBoundary.Kind.ACCEPTED.ordinal, 1).kind,
        )
        assertEquals(
            NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR,
            decode(NativeVehicleWriteBoundary.Kind.ACCEPTED.ordinal, 1, 0, 1).kind,
        )
    }

    @Test
    fun incompleteOrImpossibleEnvelopesFailClosed() {
        val malformed = listOf(
            intArrayOf(),
            intArrayOf(999, 0, 0, 0),
            intArrayOf(NativeVehicleWriteBoundary.Kind.ACCEPTED.ordinal, 0, 0, 0),
            intArrayOf(NativeVehicleWriteBoundary.Kind.REJECTED.ordinal, 1, 0, 0, 0),
            intArrayOf(NativeVehicleWriteBoundary.Kind.ACCEPTED.ordinal, 1, -1, 0, 0),
            intArrayOf(NativeVehicleWriteBoundary.Kind.DEAD_SERVICE.ordinal, 1, 0, 0, 0),
            intArrayOf(NativeVehicleWriteBoundary.Kind.DEAD_SERVICE.ordinal, 0, 1, 2, 0),
            intArrayOf(NativeVehicleWriteBoundary.Kind.DEAD_SERVICE.ordinal, 0, 0, 1),
            intArrayOf(NativeVehicleWriteBoundary.Kind.DEAD_SERVICE.ordinal, 0, 0, 0, 99),
            intArrayOf(NativeVehicleWriteBoundary.Kind.DEAD_SERVICE.ordinal, 2, 0, 0),
        )
        for (words in malformed) {
            assertEquals(
                NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR,
                decode(*words).kind,
            )
        }
    }

    private fun decode(vararg words: Int): NativeVehicleWriteClient.WriteResult {
        var index = 0
        return NativeVehicleWriteClient.decodeReply(
            object : NativeVehicleWriteClient.ReplyReader {
                override fun remaining(): Int = (words.size - index) * 4
                override fun readInt(): Int = words[index++]
            },
        )
    }
}
