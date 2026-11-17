package com.overdrive.app.launcher.adb

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DiLink5AdbRecoveryStateStoreTest {
    @Test fun freshAndExplicitlyClearedLegacySectionsHaveNoSettingOwner() {
        for (section in listOf(
            JSONObject(),
            JSONObject().put("automaticRestore", true),
            JSONObject().put("settingOwnerGeneration", -1L)
                .put("settingOwnerNetwork", "").put("priorSettingValue", JSONObject.NULL),
        )) {
            val state = Journal(section).store().load()
            assertFalse(state.hasSettingOwnershipJournal)
            assertFalse(state.ownershipJournalInvalid)
        }
    }

    @Test fun onlyAGenuinelyAbsentDurableSectionCanBecomeAnEmptySnapshot() {
        assertEquals(
            0,
            DiLink5AdbRecoveryStateStore.sectionFromDurableRoot(JSONObject()).length(),
        )
        val present = JSONObject().put("settingOwnerGeneration", 17L)
        assertTrue(
            DiLink5AdbRecoveryStateStore.sectionFromDurableRoot(
                JSONObject().put(DiLink5AdbRecoveryStateStore.SECTION, present),
            ) === present,
        )
        for (value in listOf(JSONObject.NULL, false, 1, "{}", org.json.JSONArray())) {
            try {
                DiLink5AdbRecoveryStateStore.sectionFromDurableRoot(
                    JSONObject().put(DiLink5AdbRecoveryStateStore.SECTION, value),
                )
                fail("A present nonobject ADB section became an empty journal: $value")
            } catch (_: IllegalStateException) {}
        }
    }

    @Test fun unavailableDurableReadPreventsEveryPreferenceOrOwnershipMutation() {
        var writes = 0
        val store = DiLink5AdbRecoveryStateStore(
            readSection = { throw IllegalStateException("durable bytes unavailable") },
            writeSection = { writes++; true },
        )
        val mutations = listOf<() -> Boolean>(
            { store.setAutomaticRestore(true) },
            { store.setDialogAutoConfirm(true, "wifi-a") },
            { store.clearExhaustion() },
            { store.recordWrite("wifi-a", 100L, 17L, 0) },
            { store.recordRetry("wifi-a", "NO_LISTENER", true) },
            { store.transferOwnership(17L, 18L) },
            { store.clearOwnership(17L) },
        )
        for (mutate in mutations) {
            try {
                mutate()
                fail("Unavailable durable state authorized a mutation")
            } catch (_: IllegalStateException) {}
        }
        assertEquals(0, writes)
    }

    @Test fun aNewGenerationOrNetworkCannotReplaceAnUnresolvedJournal() {
        val journal = Journal()
        val store = journal.store()
        assertTrue(store.recordWrite("wifi-a", 100L, 17L, 2))
        val before = journal.section.toString()
        val writes = journal.writes

        assertFalse(store.recordWrite("wifi-a", 200L, 18L, 0))
        assertFalse(store.recordWrite("wifi-b", 200L, 17L, 0))
        assertEquals(before, journal.section.toString())
        assertEquals(writes, journal.writes)

        assertTrue(store.recordWrite("wifi-a", 300L, 17L, 0))
        val retry = store.load()
        assertEquals(2, retry.priorSettingValue)
        assertEquals(mapOf("wifi-a" to 300L), retry.networkWriteTimes)
    }

    @Test fun exactOwnershipTransferPreservesNetworkAndFirstPriorValue() {
        val journal = Journal()
        val store = journal.store()
        assertTrue(store.recordWrite("wifi-a", 100L, 17L, 2))

        assertFalse(store.transferOwnership(99L, 18L))
        assertTrue(store.transferOwnership(17L, 18L))
        assertFalse(store.recordWrite("wifi-a", 200L, 17L, 0))
        assertTrue(store.recordWrite("wifi-a", 200L, 18L, 0))

        val current = store.load()
        assertEquals(18L, current.settingOwnerGeneration)
        assertEquals("wifi-a", current.settingOwnerNetwork)
        assertEquals(2, current.priorSettingValue)
    }

    @Test fun malformedAndIncompleteOwnerFieldsCannotBeReplacedOrCleared() {
        val malformed = listOf(
            JSONObject().put("settingOwnerGeneration", "17")
                .put("settingOwnerNetwork", "wifi-a").put("priorSettingValue", 0),
            JSONObject().put("settingOwnerGeneration", 17L)
                .put("settingOwnerNetwork", "wifi-a"),
            JSONObject().put("settingOwnerGeneration", 17L)
                .put("settingOwnerNetwork", 1).put("priorSettingValue", 0),
            JSONObject().put("settingOwnerGeneration", 17L)
                .put("settingOwnerNetwork", "wifi-a").put("priorSettingValue", "0"),
            JSONObject().put("settingOwnerGeneration", -2L),
        )
        for (section in malformed) {
            val journal = Journal(section)
            val store = journal.store()
            assertTrue(store.load().hasSettingOwnershipJournal)
            assertTrue(store.load().ownershipJournalInvalid)
            assertFalse(store.recordWrite("wifi-a", 100L, 17L, 0))
            assertFalse(store.transferOwnership(17L, 18L))
            assertFalse(store.clearOwnership(17L))
            assertEquals(0, journal.writes)
        }
    }

    @Test fun failedPersistenceCannotInventAClearOrReplaceTheOriginalOwner() {
        val journal = Journal()
        val store = journal.store()
        assertTrue(store.recordWrite("wifi-a", 100L, 17L, 0))
        val original = store.load()
        journal.persist = false

        assertFalse(store.clearOwnership(17L))
        assertFalse(store.transferOwnership(17L, 18L))
        assertEquals(original, journal.store().load())
        assertFalse(store.recordWrite("wifi-b", 200L, 18L, 0))

        journal.persist = true
        assertTrue(store.clearOwnership(17L))
        assertFalse(journal.store().load().hasSettingOwnershipJournal)
        assertTrue(store.recordWrite("wifi-b", 200L, 18L, 0))
    }

    @Test(timeout = 5_000) fun concurrentStoreInstancesCannotBothClaimAnInitiallyEmptyJournal() {
        val journal = Journal()
        val firstStore = journal.store()
        val secondStore = journal.store()
        val firstInsideWrite = CountDownLatch(1)
        val allowFirstWrite = CountDownLatch(1)
        journal.beforePersist = {
            firstInsideWrite.countDown()
            assertTrue(allowFirstWrite.await(2, TimeUnit.SECONDS))
        }
        val firstResult = AtomicReference<Boolean>()
        val secondResult = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val first = Thread {
            try { firstResult.set(firstStore.recordWrite("wifi-a", 100L, 17L, 0)) }
            catch (error: Throwable) { failure.set(error) }
        }
        val second = Thread {
            try { secondResult.set(secondStore.recordWrite("wifi-b", 100L, 18L, 0)) }
            catch (error: Throwable) { failure.set(error) }
        }
        first.start()
        try {
            assertTrue(firstInsideWrite.await(2, TimeUnit.SECONDS))
            second.start()
        } finally {
            allowFirstWrite.countDown()
        }
        first.join(2_000)
        second.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        failure.get()?.let { throw it }
        assertEquals(true, firstResult.get())
        assertEquals(false, secondResult.get())
        assertEquals(1, journal.writes)
        assertEquals("wifi-a", secondStore.load().settingOwnerNetwork)
        assertEquals(17L, secondStore.load().settingOwnerGeneration)
    }

    private class Journal(initial: JSONObject = JSONObject()) {
        @Volatile var section = JSONObject(initial.toString())
        var persist = true
        var writes = 0
        var beforePersist: (() -> Unit)? = null

        fun store() = DiLink5AdbRecoveryStateStore(
            readSection = { JSONObject(section.toString()) },
            writeSection = { patch ->
                writes++
                beforePersist?.invoke()
                if (!persist) false
                else {
                    val next = JSONObject(section.toString())
                    patch.keys().forEach { key -> next.put(key, patch.get(key)) }
                    section = next
                    true
                }
            },
        )
    }
}
