package com.overdrive.app.byd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoServiceBridgeGenerationTest {
    @Test
    fun diLink5InitializationResolvesWithoutDispatchingLegacyProbe() {
        val resolver = RecordingResolver()
        val controller = AutoServiceProtocol.Controller(resolver)

        val status = controller.initialize(mode("dilink5", "a"))

        assertEquals(1, resolver.resolveCount)
        assertTrue(resolver.sessions.single().operations.isEmpty())
        assertTrue(status.connected)
    }

    @Test
    fun diLink5RejectsEveryLegacyOperationBeforeResolutionOrDispatch() {
        val resolver = RecordingResolver()
        val controller = AutoServiceProtocol.Controller(resolver)
        val selected = mode("dilink5", "a")
        val operations = listOf(
            AutoServiceProtocol.Operation.legacyGetInt(0, 0),
            AutoServiceProtocol.Operation.legacyGetBuffer(0, 0),
            AutoServiceProtocol.Operation.legacyRemoteControl(5),
            AutoServiceProtocol.Operation.legacySendCan("0x123 00")
        )

        for (operation in operations) {
            val result = controller.dispatch(selected, operation)
            assertFalse(result.accepted)
            assertEquals(AutoServiceProtocol.Failure.UNSUPPORTED_MODE, result.raw.failure)
        }
        assertEquals(0, resolver.resolveCount)
    }

    @Test
    fun legacyModesRetainExactOperations() {
        for (legacyMode in listOf("default", "dilink4", "camera2")) {
            val resolver = RecordingResolver()
            val controller = AutoServiceProtocol.Controller(resolver)
            val snapshot = mode(legacyMode, "generation")
            assertTrue(controller.dispatch(
                snapshot, AutoServiceProtocol.Operation.legacyGetInt(9, 2)
            ).accepted)
            assertTrue(controller.dispatch(
                snapshot, AutoServiceProtocol.Operation.legacyGetBuffer(4, 3)
            ).accepted)
            assertTrue(controller.dispatch(
                snapshot, AutoServiceProtocol.Operation.legacyRemoteControl(7)
            ).accepted)
            assertTrue(controller.dispatch(
                snapshot, AutoServiceProtocol.Operation.legacySendCan("0x100 01")
            ).accepted)
            assertEquals(listOf(6, 8, 40, 42),
                resolver.sessions.single().operations.map { it.transaction })
        }
    }

    @Test
    fun modeGenerationChangeClosesOldSessionBeforeResolvingAnother() {
        val resolver = RecordingResolver()
        val controller = AutoServiceProtocol.Controller(resolver)

        controller.dispatch(mode("dilink5", "a"), AutoServiceProtocol.Operation.typedInt(1, 2))
        val first = resolver.sessions.single()
        controller.dispatch(mode("dilink5", "b"), AutoServiceProtocol.Operation.typedInt(3, 4))

        assertTrue(first.closed)
        assertEquals(2, resolver.resolveCount)
        assertEquals(listOf(3, 4), resolver.sessions[1].operations.single().arguments
            .map { (it as AutoServiceProtocol.Argument.IntValue).value })
    }

    @Test
    fun binderDeathCallbackInvalidatesSessionAndPreventsReuse() {
        val resolver = RecordingResolver()
        val controller = AutoServiceProtocol.Controller(resolver)
        val selected = mode("dilink5", "a")

        controller.dispatch(selected, AutoServiceProtocol.Operation.typedInt(1, 2))
        val first = resolver.sessions.single()
        resolver.deathCallbacks.single().invoke()
        controller.dispatch(selected, AutoServiceProtocol.Operation.typedInt(5, 6))

        assertTrue(first.closed)
        assertEquals(2, resolver.resolveCount)
        assertEquals(1, first.operations.size)
        assertEquals(1, resolver.sessions[1].operations.size)
    }

    @Test
    fun configuredTransportDoesNotClaimConnectionWhenResolutionFails() {
        val controller = AutoServiceProtocol.Controller(
            AutoServiceProtocol.Resolver { _, _, _ -> null }
        )

        val status = controller.status(mode("dilink5", "a"))

        assertEquals("binder", status.configuredTransport)
        assertFalse(status.connected)
        assertFalse(status.dispatchAvailable)
        assertNull(status.readable)
    }

    @Test
    fun readabilityTracksTypedReplyWithoutHidingZeroOrStatusFailure() {
        val resolver = RecordingResolver()
        val controller = AutoServiceProtocol.Controller(resolver)
        val selected = mode("dilink5", "a")

        resolver.nextReply = AutoServiceProtocol.RawReply.words(0, 0)
        assertTrue(controller.dispatch(
            selected, AutoServiceProtocol.Operation.typedInt(1, 2)
        ).status.readable == true)

        resolver.nextReply = AutoServiceProtocol.RawReply.words(9, 42)
        assertTrue(controller.dispatch(
            selected, AutoServiceProtocol.Operation.typedInt(1, 3)
        ).status.readable == false)
    }

    @Test
    fun unavailableResolvedSessionIsClosedAndNeverCached() {
        var session: RecordingSession? = null
        val controller = AutoServiceProtocol.Controller(
            AutoServiceProtocol.Resolver { _, _, _ ->
                RecordingSession(RecordingResolver(), available = false).also { session = it }
            }
        )

        val result = controller.dispatch(
            mode("dilink5", "a"), AutoServiceProtocol.Operation.typedInt(1, 2)
        )

        assertEquals(AutoServiceProtocol.Failure.NO_BINDER, result.raw.failure)
        assertTrue(session!!.closed)
        assertTrue(session!!.operations.isEmpty())
    }

    @Test
    fun resolverFailureIsReportedAsDisconnected() {
        val controller = AutoServiceProtocol.Controller(
            AutoServiceProtocol.Resolver { _, _, _ -> throw IllegalStateException("dead") }
        )
        val status = controller.status(mode("dilink5", "a"))
        assertFalse(status.connected)
        assertFalse(status.dispatchAvailable)
    }

    @Test
    fun changedExpectedModeRejectsBeforeResolutionOrDispatch() {
        val resolver = RecordingResolver()
        val controller = AutoServiceProtocol.Controller(resolver)
        val admitted = mode("dilink5", "a")
        val changed = mode("default", "b")

        val dispatch = controller.dispatch(
            admitted,
            AutoServiceProtocol.Operation.typedInt(1, 2)
        ) { changed }
        val status = controller.status(admitted) { changed }

        assertFalse(dispatch.accepted)
        assertEquals(AutoServiceProtocol.Failure.UNSUPPORTED_MODE, dispatch.raw.failure)
        assertFalse(status.connected)
        assertFalse(status.dispatchAvailable)
        assertEquals("unavailable", status.configuredTransport)
        assertEquals(0, resolver.resolveCount)
    }

    @Test
    fun modeFlipInsideResolverPreventsLegacyProbeAndDispatch() {
        val legacy = mode("default", "a")
        val changed = mode("dilink5", "b")
        val operations = legacyOperations()

        for (operation in operations) {
            var current = legacy
            val resolver = RecordingResolver().apply {
                beforeLegacyProbe = { current = changed }
            }
            val controller = AutoServiceProtocol.Controller(resolver)

            val result = controller.dispatch(legacy, operation) { current }

            assertFalse(result.accepted)
            assertEquals(AutoServiceProtocol.Failure.UNSUPPORTED_MODE, result.raw.failure)
            assertTrue(resolver.probeTransactions.isEmpty())
            assertTrue(resolver.sessions.single().operations.isEmpty())
            assertTrue(resolver.sessions.single().closed)
        }
    }

    @Test
    fun modeFlipAtCachedSessionBoundaryPreventsEveryLegacyTransaction() {
        val legacy = mode("default", "a")
        val changed = mode("dilink5", "b")

        for (operation in legacyOperations()) {
            var current = legacy
            val resolver = RecordingResolver()
            val controller = AutoServiceProtocol.Controller(resolver)
            controller.dispatch(
                legacy,
                AutoServiceProtocol.Operation.legacyGetInt(1, 1)
            ) { current }
            val session = resolver.sessions.single()
            session.operations.clear()
            resolver.probeTransactions.clear()
            resolver.beforeSessionDispatch = { current = changed }

            val result = controller.dispatch(legacy, operation) { current }

            assertFalse(result.accepted)
            assertEquals(AutoServiceProtocol.Failure.UNSUPPORTED_MODE, result.raw.failure)
            assertTrue(session.operations.isEmpty())
            assertTrue(resolver.probeTransactions.isEmpty())
            assertTrue(session.closed)
        }
    }

    @Test
    fun missingModeFailsClosedWithoutResolution() {
        val resolver = RecordingResolver()
        val controller = AutoServiceProtocol.Controller(resolver)
        val result = controller.dispatch(null, AutoServiceProtocol.Operation.legacyGetInt(1, 2))
        assertFalse(result.accepted)
        assertEquals(0, resolver.resolveCount)
    }

    private fun legacyOperations() = listOf(
        AutoServiceProtocol.Operation.legacyGetInt(0, 0),
        AutoServiceProtocol.Operation.legacyGetBuffer(0, 0),
        AutoServiceProtocol.Operation.legacyRemoteControl(5),
        AutoServiceProtocol.Operation.legacySendCan("0x123 00")
    )

    private fun mode(name: String, generation: String) =
        AutoServiceProtocol.ModeSnapshot(name, generation)

    private class RecordingResolver : AutoServiceProtocol.Resolver {
        val sessions = mutableListOf<RecordingSession>()
        val deathCallbacks = mutableListOf<() -> Unit>()
        val probeTransactions = mutableListOf<Int>()
        var resolveCount = 0
        var nextReply = AutoServiceProtocol.RawReply.words(0, 7)
        var beforeLegacyProbe: (() -> Unit)? = null
        var beforeSessionDispatch: (() -> Unit)? = null

        override fun resolve(
            snapshot: AutoServiceProtocol.ModeSnapshot,
            onDeath: () -> Unit,
            generationCurrent: () -> Boolean
        ): AutoServiceProtocol.Session {
            resolveCount++
            deathCallbacks += onDeath
            if (snapshot.supportsLegacy) {
                beforeLegacyProbe?.invoke()
                if (generationCurrent()) {
                    probeTransactions += AutoServiceProtocol.TX_LEGACY_GET_BUFFER
                }
            }
            return RecordingSession(this).also { sessions += it }
        }
    }

    private class RecordingSession(
        private val resolver: RecordingResolver,
        private val available: Boolean = true
    ) : AutoServiceProtocol.Session {
        val operations = mutableListOf<AutoServiceProtocol.Operation>()
        var closed = false
        override val configuredTransport = "binder"
        override val observedConnected: Boolean get() = available
        override val availableForDispatch: Boolean get() = available

        override fun dispatch(
            operation: AutoServiceProtocol.Operation,
            generationCurrent: () -> Boolean
        ): AutoServiceProtocol.RawReply {
            resolver.beforeSessionDispatch?.invoke()
            if (!generationCurrent()) {
                return AutoServiceProtocol.RawReply.failed(
                    AutoServiceProtocol.Failure.UNSUPPORTED_MODE
                )
            }
            operations += operation
            return resolver.nextReply
        }

        override fun close() {
            closed = true
        }
    }
}
