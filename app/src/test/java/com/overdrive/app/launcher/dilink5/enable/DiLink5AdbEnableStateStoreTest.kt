package com.overdrive.app.launcher.dilink5.enable

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5AdbEnableStateStoreTest {
    @Test
    fun concurrentLateAndCurrentOwnersArePersistedWithoutReplacementLoss() {
        val persisted = mutableListOf<List<DiLink5AdbEnableStateStore.FlagMutation>>()
        val records = DiLink5AdbOwnershipRecords(emptyList()) { snapshot ->
            synchronized(persisted) { persisted += snapshot }
            true
        }
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val first = mutation("worker.one", "prior-one", late = true)
        val second = mutation("worker.two", "prior-two", late = false)

        listOf(first, second).forEach { record ->
            Thread {
                start.await(1, TimeUnit.SECONDS)
                records.save(record)
                finished.countDown()
            }.start()
        }
        start.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(setOf(first, second), records.snapshot().toSet())
        assertEquals(setOf(first, second), synchronized(persisted) { persisted.last().toSet() })
    }

    @Test
    fun clearingRestoredOwnerUpdatesThePersistedSource() {
        val first = mutation("worker.one", "zero", late = true)
        val second = mutation("worker.two", "legacy", late = false)
        var persisted = emptyList<DiLink5AdbEnableStateStore.FlagMutation>()
        val records = DiLink5AdbOwnershipRecords(listOf(first, second)) {
            persisted = it
            true
        }

        records.clear(first)

        assertEquals(listOf(second), records.snapshot())
        assertEquals(listOf(second), persisted)
    }

    @Test
    fun failedSaveRetainsOwnerButDoesNotPromiseDurabilityAndRetryKeepsEarliestBaseline() {
        val first = mutation("worker.one", "zero", late = true)
        var writesSucceed = false
        var persisted = emptyList<DiLink5AdbEnableStateStore.FlagMutation>()
        val records = DiLink5AdbOwnershipRecords(emptyList()) {
            if (writesSucceed) persisted = it
            writesSucceed
        }

        assertEquals(DiLink5AdbOwnershipRecords.SaveResult(true, false), records.save(first))
        assertEquals(listOf(first), records.snapshot())
        assertFalse(records.isDurable())
        assertTrue(persisted.isEmpty())

        writesSucceed = true
        val later = first.copy(priorValue = "later-baseline")
        assertEquals(DiLink5AdbOwnershipRecords.SaveResult(false, true), records.save(later))
        assertEquals(listOf(first), persisted)
        assertTrue(records.isDurable())
    }

    @Test
    fun failedClearRetainsOwnerUntilJournalRemovalIsDurable() {
        val first = mutation("worker.one", "zero", late = true)
        var writesSucceed = false
        val records = DiLink5AdbOwnershipRecords(listOf(first)) { writesSucceed }

        assertFalse(records.clear(first))
        assertEquals(listOf(first), records.snapshot())
        assertFalse(records.isDurable())

        writesSucceed = true
        assertTrue(records.clear(first))
        assertTrue(records.snapshot().isEmpty())
        assertTrue(records.isDurable())
    }

    @Test
    fun persistenceExceptionRetainsTheOwnerAndReportsFailure() {
        val first = mutation("worker.one", "zero", late = true)
        val records = DiLink5AdbOwnershipRecords(emptyList()) { error("storage unavailable") }

        assertFalse(records.save(first).durable)
        assertFalse(records.clear(first))
        assertEquals(listOf(first), records.snapshot())
    }

    private fun mutation(worker: String, prior: String, late: Boolean) =
        DiLink5AdbEnableStateStore.FlagMutation(
            worker,
            DiLink5AccountServiceClient.FLAG_PATH,
            DiLink5AccountServiceClient.ReadState.VALUE,
            priorValue = prior,
            expectedValue = DiLink5AccountServiceClient.ENABLE_CONTENT,
            certainty = DiLink5AdbEnableStateStore.MutationCertainty.CONFIRMED,
            source = if (late) {
                DiLink5AdbEnableStateStore.OwnershipSource.LATE_RESULT
            } else {
                DiLink5AdbEnableStateStore.OwnershipSource.ORDINARY
            },
        )
}
