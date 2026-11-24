package com.overdrive.app.navmap.cluster

import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_EXPIRED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_ALREADY_FULL
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_GUARD_FAILED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_MODE_CHANGED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_SENT
import com.overdrive.app.navmap.cluster.ClusterCommDispatcher.Reply
import com.overdrive.app.navmap.cluster.DiLink5InstrumentFullSession.Lifecycle
import com.overdrive.app.navmap.cluster.DiLink5InstrumentFullSession.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5InstrumentFullSessionTest {
    private val owner = Owner(42, "boot-id")

    @Test
    fun offIsJournaledBeforeOpeningAndReleaseIsJournaledBeforeClosing() {
        val io = FakeIo()
        val lifecycle = Lifecycle(io)
        assertTrue(lifecycle.start(owner.generation, owner.bootId) { true })
        assertTrue(lifecycle.isSelectionCurrent(owner.generation))
        assertFalse(lifecycle.isSelectionCurrent(owner.generation + 1))
        assertEquals(1, io.mode)
        assertTrue(lifecycle.release(owner.generation, owner.bootId))
        assertEquals(listOf(owner, owner.copy(closing = true)), io.commands)
        assertEquals(io.commands + listOf(null), io.writes)
        assertNull(io.marker)
        assertEquals(1, io.mode)
    }

    @Test
    fun alreadyFullNeedsNoCommandOrOwnershipEvenWithoutAccConfirmation() {
        val io = FakeIo().apply { mode = 4; acc = false }
        val lifecycle = Lifecycle(io)
        assertTrue(lifecycle.start(owner.generation, owner.bootId) { true })
        assertTrue(lifecycle.isSelectionCurrent(owner.generation))
        io.mode = 1
        assertFalse(lifecycle.isSelectionCurrent(owner.generation))
        assertTrue(lifecycle.release(owner.generation, owner.bootId))
        assertTrue(io.commands.isEmpty())
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun offWithoutAccConfirmationDoesNotStartTemporaryFull() {
        val io = FakeIo().apply { acc = false }
        assertFalse(Lifecycle(io).start(owner.generation, owner.bootId) { true })
        assertTrue(io.commands.isEmpty())
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun unknownOrSimpleModeNeverStartsTemporaryFull() {
        for (mode in listOf(-1, 0, 2)) {
            val io = FakeIo().apply { this.mode = mode }
            assertFalse(Lifecycle(io).start(owner.generation, owner.bootId) { true })
            assertTrue(io.commands.isEmpty())
            assertTrue(io.writes.isEmpty())
        }
    }

    @Test
    fun supersededStartBeforeDispatchClearsMarkerWithoutAnOemCommand() {
        val io = FakeIo()
        assertFalse(Lifecycle(io).start(owner.generation, owner.bootId) { io.marker == null })
        assertEquals(listOf(owner, null), io.writes)
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun interruptedOrPartiallySentOpenRemainsRecoverable() {
        val uncertain = Reply(true, false, -1, "", 0, "app_unavailable")
        val partial = answered(RESULT_EXPIRED, 1)
        for (reply in listOf(uncertain, partial)) {
            val io = FakeIo().apply { nextReply = reply }
            assertFalse(Lifecycle(io).start(owner.generation, owner.bootId) { true })
            assertEquals(owner, io.marker)
            io.nextReply = answered(RESULT_SENT, 5)
            assertTrue(Lifecycle(io).release(null, owner.bootId))
            assertEquals(listOf(owner, owner.copy(closing = true)), io.commands)
            assertNull(io.marker)
        }
    }

    @Test
    fun lateManualFullIsAdoptedWithoutRestoreOwnership() {
        val io = FakeIo().apply {
            nextReply = answered(RESULT_INSTRUMENT_ALREADY_FULL)
            observedModeAtDispatch = 4
        }
        assertTrue(Lifecycle(io).start(owner.generation, owner.bootId) { true })
        assertTrue(Lifecycle(io).isSelectionCurrent(owner.generation))
        assertNull(io.marker)
        assertTrue(Lifecycle(io).release(owner.generation, owner.bootId))
        assertEquals(listOf(owner), io.commands)
    }

    @Test
    fun manualSelectionDuringOwnedSessionDefersToDriverSelection() {
        val io = FakeIo().apply { marker = owner; mode = 4 }
        val lifecycle = Lifecycle(io)
        assertFalse(lifecycle.isSelectionCurrent(owner.generation))
        io.nextReply = answered(RESULT_INSTRUMENT_MODE_CHANGED)
        assertTrue(lifecycle.release(owner.generation, owner.bootId))
        assertNull(io.marker)
        assertEquals(4, io.mode)
        assertEquals(RESULT_INSTRUMENT_MODE_CHANGED,
            DiLink5InstrumentFullSession.savedModeDisposition(4, closing = true))
        assertEquals(RESULT_INSTRUMENT_MODE_CHANGED,
            DiLink5InstrumentFullSession.savedModeDisposition(2, closing = true))
        assertEquals(RESULT_INSTRUMENT_GUARD_FAILED,
            DiLink5InstrumentFullSession.savedModeDisposition(-1, closing = true))
        assertNull(DiLink5InstrumentFullSession.savedModeDisposition(1, closing = true))
    }

    @Test
    fun unmatchedGenerationAndOffAccDoNotTouchCurrentOwnership() {
        val io = FakeIo().apply { marker = owner; acc = false }
        val lifecycle = Lifecycle(io)
        assertTrue(lifecycle.release(81, owner.bootId))
        assertFalse(lifecycle.release(owner.generation, owner.bootId))
        assertFalse(lifecycle.release(null, ""))
        assertEquals(owner, io.marker)
        assertTrue(io.commands.isEmpty())
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun anotherBootDiscardsOwnershipWithoutAnOemCommand() {
        val io = FakeIo().apply { marker = owner; acc = false }
        assertTrue(Lifecycle(io).release(null, "next-boot"))
        assertNull(io.marker)
        assertTrue(io.commands.isEmpty())
    }

    @Test
    fun missingDurableWritePreventsOpenAndFailedCloseRemainsPending() {
        val io = FakeIo().apply { canWrite = false }
        val lifecycle = Lifecycle(io)
        assertFalse(lifecycle.start(owner.generation, owner.bootId) { true })
        assertTrue(io.commands.isEmpty())

        io.marker = owner
        assertFalse(lifecycle.release(owner.generation, owner.bootId))
        assertEquals(owner, io.marker)
        assertTrue(io.commands.isEmpty())

        io.canWrite = true
        io.nextReply = answered(RESULT_INSTRUMENT_GUARD_FAILED)
        assertFalse(lifecycle.release(owner.generation, owner.bootId))
        assertEquals(owner.copy(closing = true), io.marker)
    }

    @Test
    fun ownerAndClosingPhaseRoundTripAsDistinctRequestIdentities() {
        val close = owner.copy(closing = true)
        assertEquals(owner, Owner.decode(owner.encode()))
        assertEquals(close, Owner.decode(close.encode()))
        assertFalse(Owner.decode(owner.encode()) == close)
        assertNull(Owner.decode("""{"generation":42,"bootId":"boot-id"}"""))
    }

    private class FakeIo : DiLink5InstrumentFullSession.Io {
        var marker: Owner? = null
        var mode = 1
        var acc = true
        var canWrite = true
        var observedModeAtDispatch: Int? = null
        var nextReply = answered(RESULT_SENT, 5)
        val writes = mutableListOf<Owner?>()
        val commands = mutableListOf<Owner>()

        override fun readOwner() = marker

        override fun writeOwner(owner: Owner?): Boolean {
            if (!canWrite) return false
            marker = owner
            writes += owner
            return true
        }

        override fun readMode() = mode

        override fun accOn() = acc

        override fun dispatch(owner: Owner): Reply {
            assertEquals(marker, owner)
            commands += owner
            observedModeAtDispatch?.let { mode = it }
            return nextReply
        }
    }

    companion object {
        private fun answered(code: Int, sent: Int = 0) = Reply(true, true, code, "service/1", sent, "")
    }
}
