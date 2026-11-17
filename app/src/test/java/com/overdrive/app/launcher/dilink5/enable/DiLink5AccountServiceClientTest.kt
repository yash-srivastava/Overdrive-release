package com.overdrive.app.launcher.dilink5.enable

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5AccountServiceClientTest {
    @Test
    fun integerRepliesRequireACompleteResultWord() {
        assertFalse(DiLink5AccountServiceClient.hasReplyWord(0))
        assertFalse(DiLink5AccountServiceClient.hasReplyWord(3))
        assertTrue(DiLink5AccountServiceClient.hasReplyWord(4))
    }

    @Test
    fun writeParcelContractIsExact() {
        val request = DiLink5AccountServiceClient.writeRequest("1")
        assertEquals(1, request.transaction)
        assertEquals(
            listOf("/collect2/activate/", "../autotest", "1"),
            request.strings,
        )
        assertEquals("com.byd.accountservice.ISaveAndReadFileInterface", DiLink5AccountServiceClient.DESCRIPTOR)
    }

    @Test
    fun readAndExistenceContractsRemainDistinct() {
        assertEquals(2, DiLink5AccountServiceClient.readRequest().transaction)
        assertEquals(listOf("/collect2/activate/../autotest"), DiLink5AccountServiceClient.readRequest().strings)
        assertEquals(3, DiLink5AccountServiceClient.existsRequest().transaction)
        assertEquals(listOf("/collect2/autotest"), DiLink5AccountServiceClient.existsRequest().strings)
    }

    @Test
    fun deniedAndNullBindingsFailClosedAndOnlyAcceptedBindingIsUnbound() {
        val denied = FakeBinding(accepted = false)
        val deniedResult = DiLink5AccountServiceClient(denied, 10).inspect()
        assertFalse(deniedResult.bound)
        assertFalse(deniedResult.connected)
        assertEquals(0, denied.unbindCalls)

        val nullBinding = FakeBinding { it.onNullBinding() }
        val nullResult = DiLink5AccountServiceClient(nullBinding, 10).inspect()
        assertTrue(nullResult.bound)
        assertFalse(nullResult.connected)
        assertEquals(1, nullBinding.unbindCalls)
    }

    @Test
    fun transactFailuresAndMalformedRepliesRemainUnavailableAndAlwaysUnbind() {
        val service = FakeService().apply {
            reads.clear()
            reads += DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.UNAVAILABLE)
            reads += DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.UNAVAILABLE)
            writeResult = null
            existsResult = null
        }
        val binding = FakeBinding { it.onConnected(service) }

        val result = DiLink5AccountServiceClient(binding, 10).write("1")

        assertTrue(result.bound)
        assertTrue(result.connected)
        assertEquals(DiLink5AccountServiceClient.ReadState.UNAVAILABLE, result.prior.state)
        assertNull(result.writeResult)
        assertTrue(result.replyLostAfterDispatch)
        assertNull(result.exists)
        assertEquals(DiLink5AccountServiceClient.ReadState.UNAVAILABLE, result.after.state)
        assertEquals(1, binding.unbindCalls)
        assertEquals(1, service.unlinkCalls)
    }

    @Test
    fun conditionalRestoreComparesImmediatelyBeforeWrite() {
        val service = FakeService().apply {
            reads.clear()
            reads += DiLink5AccountServiceClient.ReadResult(
                DiLink5AccountServiceClient.ReadState.VALUE,
                "0",
            )
            reads += DiLink5AccountServiceClient.ReadResult(
                DiLink5AccountServiceClient.ReadState.VALUE,
                "legacy",
            )
        }
        val binding = FakeBinding { it.onConnected(service) }

        val result = DiLink5AccountServiceClient(binding, 10).restore("0", "legacy")

        assertEquals(true, result.conditionalMatched)
        assertEquals(1, service.writeCalls)
        assertEquals("0", result.prior.value)
        assertEquals("legacy", result.after.value)
        assertEquals(1, binding.unbindCalls)
    }

    @Test
    fun conditionalRestoreConfirmsAnOriginallyEmptyValue() {
        val service = FakeService().apply {
            reads.clear()
            reads += DiLink5AccountServiceClient.ReadResult(
                DiLink5AccountServiceClient.ReadState.EMPTY,
                "",
            )
            reads += DiLink5AccountServiceClient.ReadResult(
                DiLink5AccountServiceClient.ReadState.EMPTY,
                "",
            )
        }
        val result = DiLink5AccountServiceClient(
            FakeBinding { it.onConnected(service) },
            10,
        ).restore("", "")

        assertEquals(true, result.conditionalMatched)
        assertEquals(1, service.writeCalls)
        assertEquals(DiLink5AccountServiceClient.ReadState.EMPTY, result.after.state)
        assertTrue(result.writeDispatched)
        assertFalse(result.replyLostAfterDispatch)
    }

    @Test
    fun exceptionAfterWriteDispatchRetainsPossibleMutation() {
        val service = FakeService().apply { throwOnWrite = true }
        val result = DiLink5AccountServiceClient(
            FakeBinding { it.onConnected(service) },
            10,
        ).write("1")

        assertEquals(1, service.writeCalls)
        assertNull(result.writeResult)
        assertTrue(result.replyLostAfterDispatch)
    }

    @Test
    fun conditionalRestoreRejectsDriftAndUnreadableStateWithoutWrite() {
        listOf(
            DiLink5AccountServiceClient.ReadResult(
                DiLink5AccountServiceClient.ReadState.VALUE,
                "external",
            ),
            DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.EMPTY, ""),
            DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.UNAVAILABLE),
        ).forEach { current ->
            val service = FakeService().apply {
                reads.clear()
                reads += current
            }
            val binding = FakeBinding { it.onConnected(service) }

            val result = DiLink5AccountServiceClient(binding, 10).restore("1", "0")

            assertEquals(false, result.conditionalMatched)
            assertEquals(0, service.writeCalls)
            assertEquals(current, result.prior)
            assertEquals(current, result.after)
            assertEquals(1, binding.unbindCalls)
        }
    }

    @Test
    fun disconnectAndBinderDeathAreReportedSeparately() {
        val disconnected = FakeBinding { it.onDisconnected() }
        val disconnectedResult = DiLink5AccountServiceClient(disconnected, 10).inspect()
        assertFalse(disconnectedResult.connected)
        assertFalse(disconnectedResult.binderDied)
        assertEquals(1, disconnected.unbindCalls)

        val deadService = FakeService().apply {
            alive = false
            dieOnLink = true
        }
        val died = FakeBinding { it.onConnected(deadService) }
        val diedResult = DiLink5AccountServiceClient(died, 10).inspect()
        assertFalse(diedResult.connected)
        assertTrue(diedResult.binderDied)
        assertEquals(1, died.unbindCalls)
    }

    @Test
    fun operationExceptionStillUnlinksAndUnbinds() {
        val service = FakeService().apply { throwOnRead = true }
        val binding = FakeBinding { it.onConnected(service) }

        val result = DiLink5AccountServiceClient(binding, 10).inspect()

        assertFalse(result.connected)
        assertEquals(1, service.unlinkCalls)
        assertEquals(1, binding.unbindCalls)
    }

    @Test
    fun workerDispatcherRejectsWrongCorrelationButAcceptsMatchingCompletion() {
        val dispatcher = DiLink5WorkerResultDispatcher<String>(10)

        val result = dispatcher.dispatch("worker", 7) { complete ->
            complete(6, "wrong")
            complete(7, "right")
            true
        }

        assertEquals("right", result)
    }

    @Test
    fun timedOutWorkerStaysOwnedAndLateValueUsesOwnershipChannel() {
        val dispatcher = DiLink5WorkerResultDispatcher<String>(1)
        val late = AtomicReference<(Long, String?) -> Unit>()
        val ownership = AtomicReference<String>()

        assertNull(dispatcher.dispatch("worker", 11, ownership::set) { complete ->
            late.set(complete)
            true
        })
        assertNull(dispatcher.dispatch("worker", 12) { complete ->
            complete(12, "must not start")
            true
        })

        late.get()(11, "late")
        assertEquals("late", ownership.get())
        assertEquals("fresh", dispatcher.dispatch("worker", 13) { complete ->
            complete(13, "fresh")
            true
        })
    }

    @Test
    fun workerStartFailureReleasesOwnership() {
        val dispatcher = DiLink5WorkerResultDispatcher<String>(10)
        assertNull(dispatcher.dispatch("worker", 1) { false })
        assertEquals("next", dispatcher.dispatch("worker", 2) { complete ->
            complete(2, "next")
            true
        })
    }

    @Test
    fun componentAndActionAreExplicit() {
        assertEquals("byd.intent.action.SAVE_READ_FILE", DiLink5AccountServiceClient.ACTION)
        assertEquals("com.byd.accountservice", DiLink5AccountServiceClient.PACKAGE)
        assertEquals(
            "com.byd.accountservice.service.SaveAndReadFileService",
            DiLink5AccountServiceClient.SERVICE,
        )
    }

    @Test
    fun generationRevokedAfterBindPreventsTransactionOneDispatch() {
        val service = FakeService()
        val result = DiLink5AccountServiceClient(
            FakeBinding { it.onConnected(service) },
            10,
        ).write("1") { false }

        assertEquals(0, service.writeCalls)
        assertFalse(result.writeDispatched)
        assertFalse(result.replyLostAfterDispatch)
        assertEquals("0", result.prior.value)
        assertEquals(result.prior, result.after)
    }

    @Test
    fun definiteRejectedReplyIsDispatchedButNotUncertain() {
        val service = FakeService().apply { writeResult = 0 }
        val result = DiLink5AccountServiceClient(
            FakeBinding { it.onConnected(service) },
            10,
        ).write("1")

        assertEquals(0, result.writeResult)
        assertTrue(result.writeDispatched)
        assertFalse(result.replyLostAfterDispatch)
    }

    @Test
    fun workerModeFenceRequiresTheExactActiveDiLink5Generation() {
        assertTrue(DiLink5AdbWorkerModeFence.matches("g1", "dilink5", "g1"))
        assertFalse(DiLink5AdbWorkerModeFence.matches("g1", "dilink5", "g2"))
        assertFalse(DiLink5AdbWorkerModeFence.matches("g1", "default", "g1"))
        assertFalse(DiLink5AdbWorkerModeFence.matches(null, "dilink5", "g1"))
    }

    @Test
    fun anUnexpectedOemInterfaceCannotReceiveAccountFileTransactions() {
        val service = FakeService().apply { protocolCompatible = false }
        val client = DiLink5AccountServiceClient(FakeBinding { it.onConnected(service) }, 10)

        val result = client.write("1")

        assertNull(result.writeResult)
        assertFalse(result.writeDispatched)
        assertFalse(result.replyLostAfterDispatch)
        assertEquals(0, service.writeCalls)
        assertEquals(0, service.readCalls)
        assertEquals(0, service.existsCalls)
        assertTrue(result.detail.contains("unsupported account service interface"))
    }

    @Test
    fun readOnlyInspectionAlsoChecksAuthorityBeforeOemTransactions() {
        val service = FakeService()
        val client = DiLink5AccountServiceClient(FakeBinding { it.onConnected(service) }, 10)

        val result = client.inspect { false }

        assertEquals(DiLink5AccountServiceClient.ReadState.UNAVAILABLE, result.prior.state)
        assertNull(result.exists)
        assertEquals(0, service.readCalls)
        assertEquals(0, service.existsCalls)
    }

    private class FakeBinding(
        private val accepted: Boolean = true,
        private val onBind: (DiLink5AccountServiceClient.BindingCallback) -> Unit = {},
    ) : DiLink5AccountServiceClient.BindingGateway {
        var unbindCalls = 0

        override fun bind(callback: DiLink5AccountServiceClient.BindingCallback): Boolean {
            if (accepted) onBind(callback)
            return accepted
        }

        override fun unbind(callback: DiLink5AccountServiceClient.BindingCallback) {
            unbindCalls++
        }
    }

    private class FakeService : DiLink5AccountServiceClient.ServiceChannel {
        var alive = true
        var protocolCompatible = true
        var dieOnLink = false
        var throwOnRead = false
        var throwOnWrite = false
        var writeResult: Int? = 1
        var existsResult: Boolean? = true
        var writeCalls = 0
        var readCalls = 0
        var existsCalls = 0
        var unlinkCalls = 0
        val reads = ArrayDeque<DiLink5AccountServiceClient.ReadResult>().apply {
            add(DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.VALUE, "0"))
            add(DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.VALUE, "1"))
        }

        override val isAlive: Boolean get() = alive
        override val supportsProtocol: Boolean get() = protocolCompatible

        override fun linkToDeath(onDeath: () -> Unit) {
            if (dieOnLink) onDeath()
        }

        override fun unlinkToDeath() {
            unlinkCalls++
        }

        override fun write(request: DiLink5AccountServiceClient.ParcelRequest): Int? {
            writeCalls++
            if (throwOnWrite) error("reply lost after dispatch")
            return writeResult
        }

        override fun read(request: DiLink5AccountServiceClient.ParcelRequest): DiLink5AccountServiceClient.ReadResult {
            readCalls++
            if (throwOnRead) error("malformed reply")
            return reads.removeFirstOrNull()
                ?: DiLink5AccountServiceClient.ReadResult(DiLink5AccountServiceClient.ReadState.UNAVAILABLE)
        }

        override fun exists(request: DiLink5AccountServiceClient.ParcelRequest): Boolean? {
            existsCalls++
            return existsResult
        }
    }
}
