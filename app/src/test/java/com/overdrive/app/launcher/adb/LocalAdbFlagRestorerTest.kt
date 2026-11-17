package com.overdrive.app.launcher.adb

import org.junit.Assert.*
import org.junit.Test

class LocalAdbFlagRestorerTest {
    private class Fake : LocalAdbFlagRestorer.Gateway {
        var flag: Int? = 0
        var permission = true
        var accepted = true
        var applyWrite = true
        var writes = 0
        var reads = 0
        var permissionChecks = 0
        var readHook: () -> Unit = {}
        var writeHook: () -> Unit = {}
        var readFailure = false
        var writeFailure = false
        var permissionFailure = false
        override fun readAdbEnabled(): Int? {
            reads++
            if (readFailure) throw IllegalStateException("provider unavailable")
            readHook()
            return flag
        }
        override fun hasWriteSecureSettings(): Boolean {
            permissionChecks++
            if (permissionFailure) throw SecurityException("permission unavailable")
            return permission
        }
        override fun writeAdbEnabled(): Boolean {
            writes++
            if (writeFailure) throw SecurityException("write rejected")
            if (applyWrite) flag = 1
            writeHook()
            return accepted
        }
    }

    @Test fun cancelledAdmissionDoesNotEvenReadTheProvider() {
        val fake = Fake()
        assertEquals(LocalAdbFlagRestorer.Outcome.CANCELLED, LocalAdbFlagRestorer(fake).restore { false }.outcome)
        assertEquals(0, fake.reads)
        assertEquals(0, fake.writes)
    }

    @Test fun enabledFlagReturnsBeforeCheckingPermission() {
        val fake = Fake().apply { flag = 1; permission = false }
        val result = LocalAdbFlagRestorer(fake).restore { true }
        assertEquals(LocalAdbFlagRestorer.Outcome.ALREADY_ENABLED, result.outcome)
        assertTrue(result.settingEnabled)
        assertFalse(result.writeAttempted)
        assertEquals(0, fake.permissionChecks)
        assertEquals(0, fake.writes)
    }

    @Test fun deniedPermissionCannotWriteAFlag() {
        val fake = Fake().apply { permission = false }
        assertEquals(LocalAdbFlagRestorer.Outcome.MISSING_PERMISSION, LocalAdbFlagRestorer(fake).restore { true }.outcome)
        assertEquals(0, fake.writes)
        assertEquals(0, fake.flag)
    }

    @Test fun acceptedWriteAndReadbackAreBothRequired() {
        val fake = Fake()
        val result = LocalAdbFlagRestorer(fake).restore { true }
        assertEquals(LocalAdbFlagRestorer.Outcome.ENABLED, result.outcome)
        assertTrue(result.settingEnabled)
        assertTrue(result.writeAttempted)
        assertEquals(1, fake.writes)
        assertEquals(2, fake.reads)
    }

    @Test fun falseReturnIsNotSuccessEvenIfTheObservedFlagIsOne() {
        val fake = Fake().apply { accepted = false }
        val result = LocalAdbFlagRestorer(fake).restore { true }
        assertEquals(LocalAdbFlagRestorer.Outcome.WRITE_REJECTED, result.outcome)
        assertTrue(result.settingEnabled)
        assertTrue(result.writeAttempted)
    }

    @Test fun firmwareThatIgnoresAWriteCannotReportEnabled() {
        val fake = Fake().apply { applyWrite = false }
        val result = LocalAdbFlagRestorer(fake).restore { true }
        assertEquals(LocalAdbFlagRestorer.Outcome.READBACK_FAILED, result.outcome)
        assertFalse(result.settingEnabled)
        assertTrue(result.writeAttempted)
    }

    @Test fun unavailableInitialReadCannotAuthorizeAWrite() {
        for (throws in listOf(false, true)) {
            val fake = Fake().apply { flag = null; readFailure = throws }
            assertEquals(LocalAdbFlagRestorer.Outcome.READ_FAILED, LocalAdbFlagRestorer(fake).restore { true }.outcome)
            assertEquals(0, fake.writes)
        }
    }

    @Test fun failedReadbackIsNotSuccess() {
        val fake = Fake().apply { writeHook = { readFailure = true } }
        assertEquals(LocalAdbFlagRestorer.Outcome.READBACK_FAILED, LocalAdbFlagRestorer(fake).restore { true }.outcome)
    }

    @Test fun permissionAndWriteExceptionsAreContained() {
        val noPermission = Fake().apply { permissionFailure = true }
        assertEquals(LocalAdbFlagRestorer.Outcome.MISSING_PERMISSION, LocalAdbFlagRestorer(noPermission).restore { true }.outcome)
        assertEquals(0, noPermission.writes)
        val rejectedWrite = Fake().apply { writeFailure = true }
        assertEquals(LocalAdbFlagRestorer.Outcome.WRITE_REJECTED, LocalAdbFlagRestorer(rejectedWrite).restore { true }.outcome)
    }

    @Test fun changedAuthorityAfterTheReadPreventsTheWrite() {
        var current = true
        val fake = Fake().apply { readHook = { current = false } }
        assertEquals(LocalAdbFlagRestorer.Outcome.CANCELLED, LocalAdbFlagRestorer(fake).restore { current }.outcome)
        assertEquals(0, fake.writes)
    }

    @Test fun cancelledInFlightWriteIsReportedWithoutUndoingItsReadback() {
        var current = true
        val fake = Fake().apply { writeHook = { current = false } }
        val result = LocalAdbFlagRestorer(fake).restore { current }
        assertEquals(LocalAdbFlagRestorer.Outcome.CANCELLED, result.outcome)
        assertTrue(result.settingEnabled)
        assertTrue(result.writeAttempted)
        assertEquals(1, fake.flag)
        assertEquals(1, fake.writes)
    }

    @Test fun nonOneValuesFollowTheReferenceWriteSequence() {
        val fake = Fake().apply { flag = 2 }
        assertEquals(LocalAdbFlagRestorer.Outcome.ENABLED, LocalAdbFlagRestorer(fake).restore { true }.outcome)
        assertEquals(1, fake.flag)
    }
}
