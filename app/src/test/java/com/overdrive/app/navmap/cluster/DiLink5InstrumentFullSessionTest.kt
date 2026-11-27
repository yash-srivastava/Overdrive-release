package com.overdrive.app.navmap.cluster

import com.overdrive.app.monitor.BoundedShell
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_EXPIRED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_ALREADY_FULL
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_GUARD_FAILED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_MODE_CHANGED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_SENT
import com.overdrive.app.navmap.cluster.ClusterCommDispatcher.Reply
import com.overdrive.app.navmap.cluster.DiLink5InstrumentFullSession.Lifecycle
import com.overdrive.app.navmap.cluster.DiLink5InstrumentFullSession.Owner
import com.overdrive.app.navmap.cluster.DiLink5InstrumentFullSession.readDaemonSavedMode
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
        var output = "4"
        val io = FakeIo {
            readDaemonSavedMode { command, deadlineMs ->
                assertEquals("exec /system/bin/settings get global moet_extension_navi_display_mode", command)
                assertEquals(BoundedShell.DEFAULT_DEADLINE_MS, deadlineMs)
                output
            }
        }.apply { acc = false }
        val lifecycle = Lifecycle(io)
        assertTrue(lifecycle.start(owner.generation, owner.bootId) { true })
        assertTrue(lifecycle.isSelectionCurrent(owner.generation))
        output = "1"
        assertFalse(lifecycle.isSelectionCurrent(owner.generation))
        assertTrue(lifecycle.release(owner.generation, owner.bootId))
        assertTrue(io.commands.isEmpty())
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun offFromShellCanOpenAndFailedReadInvalidatesActiveSelection() {
        var output: String? = "1"
        val io = FakeIo { readDaemonSavedMode { _, _ -> output } }
        val lifecycle = Lifecycle(io)
        assertTrue(lifecycle.start(owner.generation, owner.bootId) { true })
        assertTrue(lifecycle.isSelectionCurrent(owner.generation))
        assertEquals(owner, io.marker)
        output = null
        assertFalse(lifecycle.isSelectionCurrent(owner.generation))
        assertEquals(owner, io.marker)
        assertEquals(listOf(owner), io.commands)
    }

    @Test
    fun offWithoutAccConfirmationDoesNotStartTemporaryFull() {
        val io = FakeIo().apply { acc = false }
        assertFalse(Lifecycle(io).start(owner.generation, owner.bootId) { true })
        assertTrue(io.commands.isEmpty())
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun unavailableMalformedSimpleOrSmallShellModeNeverStartsTemporaryFull() {
        for (output in listOf(null, "", "null", "-1", "0", "2", "3", "4\nsettings: error", "4294967300")) {
            val io = FakeIo { readDaemonSavedMode { _, _ -> output } }
            val lifecycle = Lifecycle(io)
            assertFalse("Unexpected start for $output", lifecycle.start(owner.generation, owner.bootId) { true })
            assertFalse(lifecycle.isSelectionCurrent(owner.generation))
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
            io.nextReply = answered(RESULT_SENT, 4)
            assertTrue(Lifecycle(io).release(null, owner.bootId))
            assertEquals(listOf(owner, owner.copy(closing = true)), io.commands)
            assertNull(io.marker)
        }
    }

    @Test
    fun successfulReplyWithAnUnexpectedCountLeavesTheNewSessionRecoverable() {
        for (sent in listOf(3, 5)) {
            val io = FakeIo().apply { nextReply = answered(RESULT_SENT, sent) }
            val lifecycle = Lifecycle(io)
            assertFalse(lifecycle.start(owner.generation, owner.bootId) { true })
            assertEquals(owner, io.marker)
            assertFalse(lifecycle.release(owner.generation, owner.bootId))
            assertEquals(owner.copy(closing = true), io.marker)

            io.nextReply = answered(RESULT_SENT, 4)
            assertTrue(lifecycle.release(owner.generation, owner.bootId))
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
            DiLink5InstrumentFullSession.savedModeDisposition(4, owner.copy(closing = true)))
        assertEquals(RESULT_INSTRUMENT_MODE_CHANGED,
            DiLink5InstrumentFullSession.savedModeDisposition(2, owner.copy(closing = true)))
        assertEquals(RESULT_INSTRUMENT_GUARD_FAILED,
            DiLink5InstrumentFullSession.savedModeDisposition(-1, owner.copy(closing = true)))
        assertNull(DiLink5InstrumentFullSession.savedModeDisposition(1, owner.copy(closing = true)))
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
    fun previousBootOpeningInFullRestoresOffAndRetriesIncompleteBatches() {
        for (legacy in listOf(false, true)) {
            val opening = owner.copy(legacyMapFocus = legacy)
            val closing = opening.copy(bootId = "next-boot", closing = true, rebootRestore = true)
            val io = FakeIo().apply { marker = opening; mode = 4; acc = false }
            val lifecycle = Lifecycle(io)

            assertFalse(lifecycle.release(null, "next-boot"))
            io.acc = true
            io.canWrite = false
            assertFalse(lifecycle.release(null, "next-boot"))
            assertEquals(opening, io.marker)
            assertTrue(io.commands.isEmpty())

            io.canWrite = true
            val expectedCount = if (legacy) 5 else 4
            io.nextReply = answered(RESULT_SENT, expectedCount - 1)
            assertFalse(lifecycle.release(null, "next-boot"))
            assertEquals(closing, io.marker)
            io.marker = Owner.decode(closing.encode())
            io.nextReply = answered(RESULT_SENT, expectedCount)
            assertTrue(Lifecycle(io).release(null, "next-boot"))
            assertEquals(listOf(closing, closing), io.commands)
            assertEquals(listOf(closing, closing.copy(savedOffPending = true), null), io.writes)
            assertEquals(1, io.mode)
            assertNull(io.marker)
        }
    }

    @Test
    fun previousBootClearsOtherSelectionsAndClosingOwnersWithoutCommands() {
        for (mode in listOf(1, 2, 3, 4)) {
            for (closing in listOf(false, true)) {
                if (mode == 4 && !closing) continue
                val io = FakeIo().apply { marker = owner.copy(closing = closing); this.mode = mode }
                assertTrue(Lifecycle(io).release(null, "next-boot"))
                assertNull(io.marker)
                assertTrue(io.commands.isEmpty())
            }
        }
    }

    @Test
    fun shutdownBroadcastFailureSurvivesEarlyOffUntilReadyFullThenRestoresExactlyOnce() {
        val io = FakeIo()
        assertTrue(Lifecycle(io).start(owner.generation, owner.bootId) { true })
        assertTrue(Lifecycle(io).isSelectionCurrent(owner.generation))
        io.mode = -1 // settings became unavailable during daemon shutdown
        io.nextReply = Reply(false, false, -1, "", 0, "broadcast_failed")
        assertFalse(Lifecycle(io).release(owner.generation, owner.bootId))
        val pending = owner.copy(closing = true, closeNotSent = true)
        assertEquals(pending, io.marker)
        io.marker = Owner.decode(pending.encode())
        val readsAtShutdown = io.modeReads
        io.mode = 1

        io.acc = false
        assertFalse(Lifecycle(io).release(null, "new-boot"))
        assertEquals(readsAtShutdown, io.modeReads)
        assertEquals(pending, io.marker)

        io.acc = true
        io.mode = 4
        io.nextReply = answered(RESULT_SENT, 4)
        assertTrue(Lifecycle(io).release(null, "new-boot"))
        assertTrue(Lifecycle(io).release(null, "new-boot"))
        val restore = owner.copy(bootId = "new-boot", closing = true, rebootRestore = true)
        assertEquals(listOf(owner, owner.copy(closing = true), restore), io.commands)
        assertEquals(listOf(owner, owner.copy(closing = true), pending, restore, restore.copy(savedOffPending = true), null), io.writes)
        assertEquals(1, io.mode)
        assertEquals(1, io.savedOffAttempts)
    }

    @Test
    fun observedDriverSelectionSurvivesLaterUnreadableShutdown() {
        for (selected in 2..4) {
            val io = FakeIo()
            val lifecycle = Lifecycle(io)
            assertTrue(lifecycle.start(owner.generation, owner.bootId) { true })
            io.mode = selected
            assertFalse(lifecycle.isSelectionCurrent(owner.generation))
            io.mode = -1
            assertTrue(lifecycle.release(null, owner.bootId))
            io.mode = 4
            assertTrue(lifecycle.release(null, "new-boot"))
            assertEquals(listOf(owner), io.commands)
            assertEquals(0, io.savedOffAttempts)
        }
    }

    @Test
    fun rebootSavedModeRetriesOnlyThePreferenceAfterUnconfirmedReadback() {
        val io = FakeIo().apply { marker = owner; mode = 4; modeAfterPut = 4 }
        assertFalse(Lifecycle(io).release(null, "new-boot"))
        val pending = owner.copy(bootId = "new-boot", closing = true, rebootRestore = true, savedOffPending = true)
        assertEquals(pending, io.marker)
        io.marker = Owner.decode(pending.encode())
        io.mode = -1
        assertFalse(Lifecycle(io).release(null, "new-boot"))
        io.mode = 4
        io.acc = false
        assertFalse(Lifecycle(io).release(null, "new-boot"))
        assertEquals(1, io.savedOffAttempts)
        io.acc = true
        io.modeAfterPut = 1
        assertTrue(Lifecycle(io).release(null, "new-boot"))
        assertEquals(2, io.savedOffAttempts)
        assertEquals(1, io.commands.size)
        assertNull(io.marker)
    }

    @Test
    fun confirmedHardwareCloseHonorsAnInterveningSelectionAndExpiresAtAnotherBoot() {
        for (selected in listOf(1, 2, 3, 4)) {
            val io = FakeIo().apply {
                marker = owner.copy(closing = true, rebootRestore = true, savedOffPending = true)
                mode = selected
            }
            val bootId = if (selected == 4) "another-boot" else owner.bootId
            assertTrue(Lifecycle(io).release(null, bootId))
            assertNull(io.marker)
            assertTrue(io.commands.isEmpty())
            assertEquals(0, io.savedOffAttempts)
            assertEquals(selected, io.mode)
        }
    }

    @Test
    fun failedRetryPermissionWriteOrUncertainDeliveryCannotOverrideFullAfterReboot() {
        val unknown = Reply(true, false, -1, "", 0, "app_unavailable")
        val unsent = Reply(false, false, -1, "", 0, "broadcast_failed")
        for (reply in listOf(unsent, unknown, answered(RESULT_EXPIRED, 2))) {
            val io = FakeIo().apply {
                marker = owner
                nextReply = reply
                if (reply === unsent) writeAllowed = { candidate -> candidate?.closeNotSent != true }
            }
            assertFalse(Lifecycle(io).release(null, owner.bootId))
            assertEquals(owner.copy(closing = true), io.marker)
            io.writeAllowed = { true }
            io.nextReply = unsent
            // One later failed attempt cannot prove the earlier close was never sent.
            assertFalse(Lifecycle(io).release(null, owner.bootId))
            assertEquals(owner.copy(closing = true), io.marker)
            io.mode = 4
            assertTrue(Lifecycle(io).release(null, "new-boot"))
            assertNull(io.marker)
            assertEquals(2, io.commands.size)
        }
    }

    @Test
    fun failedClearAfterCompletedOrSupersededClosePreservesDriverFullAcrossReboot() {
        for (driverSelectedFull in listOf(false, true)) {
            val io = FakeIo().apply {
                marker = owner.copy(closing = true, closeNotSent = true)
                observedModeAtDispatch = if (driverSelectedFull) 4 else 1
                writeAllowed = { candidate -> candidate != null }
            }
            assertFalse(Lifecycle(io).release(null, owner.bootId))
            assertEquals(owner.copy(closing = true), io.marker)
            io.mode = 4
            io.writeAllowed = { true }
            assertTrue(Lifecycle(io).release(null, "new-boot"))
            assertNull(io.marker)
            assertEquals(1, io.commands.size)
        }
    }

    @Test
    fun unsentCloseFromManualFullDoesNotAcquireCrossBootPermission() {
        val io = FakeIo().apply {
            marker = owner
            mode = 4
            nextReply = Reply(false, false, -1, "", 0, "broadcast_failed")
        }
        assertFalse(Lifecycle(io).release(null, owner.bootId))
        assertEquals(owner.copy(closing = true), io.marker)
        assertTrue(Lifecycle(io).release(null, "new-boot"))
        assertNull(io.marker)
        assertEquals(1, io.commands.size)
    }

    @Test
    fun previousBootDefersAnUnreadableModeAndHonorsTheSelectionAtDispatch() {
        val io = FakeIo().apply { marker = owner; mode = -1 }
        val lifecycle = Lifecycle(io)
        assertFalse(lifecycle.release(null, "next-boot"))
        assertEquals(owner, io.marker)
        assertTrue(io.commands.isEmpty())

        io.mode = 4
        io.observedModeAtDispatch = -1
        assertFalse(lifecycle.release(null, "next-boot"))
        val closing = owner.copy(bootId = "next-boot", closing = true, rebootRestore = true)
        assertEquals(closing, io.marker)
        for (mode in listOf(1, 2, 3)) {
            assertEquals(RESULT_INSTRUMENT_MODE_CHANGED,
                DiLink5InstrumentFullSession.savedModeDisposition(mode, closing))
        }
        io.observedModeAtDispatch = 3
        assertTrue(lifecycle.release(null, "next-boot"))
        assertNull(io.marker)
        assertEquals(3, io.mode)
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
        assertNull(Owner.decode(owner.copy(rebootRestore = true).encode()))
        assertNull(Owner.decode(owner.copy(closeNotSent = true).encode()))
        assertNull(Owner.decode(close.copy(savedOffPending = true).encode()))
        assertNull(Owner.decode(close.copy(rebootRestore = true, savedOffPending = true, closeNotSent = true).encode()))
        assertEquals(close, Owner.decode("""{"generation":42,"bootId":"boot-id","closing":true,"legacyMapFocus":false}"""))
        assertNull(Owner.decode("""{"generation":42,"bootId":"boot-id"}"""))
    }

    @Test
    fun sameBootRecoveryRetainsLegacyMapFocusCleanupUntilAllFiveCommandsAreSent() {
        for (closing in listOf(false, true)) {
            val legacy = Owner.decode("""{"generation":42,"bootId":"boot-id","closing":$closing}""")
            assertEquals(owner.copy(closing = closing, legacyMapFocus = true), legacy)
            val io = FakeIo().apply { marker = legacy }
            val lifecycle = Lifecycle(io)
            val expectedClose = owner.copy(closing = true, legacyMapFocus = true)

            assertFalse(lifecycle.release(null, owner.bootId))
            assertEquals(expectedClose, io.marker)
            assertEquals(expectedClose, Owner.decode(expectedClose.encode()))
            io.nextReply = answered(RESULT_SENT, 5)
            assertTrue(lifecycle.release(null, owner.bootId))
            assertEquals(listOf(expectedClose, expectedClose), io.commands)
            assertNull(io.marker)
        }
    }

    private class FakeIo(private val shellMode: (() -> Int)? = null) : DiLink5InstrumentFullSession.Io {
        var marker: Owner? = null
        var mode = 1
        var modeReads = 0
        var savedOffAttempts = 0
        var modeAfterPut = 1
        var acc = true
        var canWrite = true
        var writeAllowed: (Owner?) -> Boolean = { true }
        var observedModeAtDispatch: Int? = null
        var nextReply = answered(RESULT_SENT, 4)
        val writes = mutableListOf<Owner?>()
        val commands = mutableListOf<Owner>()

        override fun readOwner() = marker

        override fun writeOwner(owner: Owner?): Boolean {
            if (!canWrite || !writeAllowed(owner)) return false
            marker = owner
            writes += owner
            return true
        }

        override fun readMode(): Int {
            modeReads++
            return shellMode?.invoke() ?: mode
        }

        override fun restoreSavedOffIfFull(): Boolean {
            assertEquals(true, marker?.savedOffPending)
            assertEquals(4, mode)
            savedOffAttempts++
            mode = modeAfterPut
            return true
        }

        override fun accOn() = acc

        override fun dispatch(owner: Owner): Reply {
            assertEquals(marker, owner)
            commands += owner
            observedModeAtDispatch?.let { mode = it }
            if (!nextReply.dispatched) return nextReply
            val declined = DiLink5InstrumentFullSession.savedModeDisposition(mode, owner)
            return if (declined != null) answered(declined) else nextReply
        }
    }

    companion object {
        private fun answered(code: Int, sent: Int = 0) = Reply(true, true, code, "service/1", sent, "")
    }
}
