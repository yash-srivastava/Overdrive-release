package com.overdrive.app.launcher.dilink5.enable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5AdbWorkerProcessTest {
    @Test
    fun candidatesRemainInRequiredOrderWithDistinctPrivateWorkers() {
        assertEquals(
            listOf(
                "com.byd.launchermap",
                "com.byd.naviauto",
                "com.byd.automap",
                "com.btet.automap",
                "com.byd.diLinkAccount",
            ),
            DiLink5AdbWorkerProcess.candidates.map { it.processName },
        )
        assertEquals(5, DiLink5AdbWorkerProcess.candidates.map { it.serviceClass }.distinct().size)
        assertEquals("com.byd.launchermap", DiLink5AdbWorkerProcess.initialInspectionCandidate.packageName)
        assertEquals("com.byd.launchermap", DiLink5AdbWorkerProcess.initialInspectionCandidate.processName)
    }

    @Test
    fun absentPackageInspectionStillDispatchesOnlyTheFirstPrivateWorker() {
        var dispatched: DiLink5AdbWorkerProcess.Candidate? = null
        var operation = -1L
        val result = dispatchInitialInspection(73L) { candidate, operationId ->
            dispatched = candidate
            operation = operationId
            "bounded-result"
        }

        assertEquals("bounded-result", result)
        assertEquals(DiLink5AdbWorkerProcess.initialInspectionCandidate, dispatched)
        assertEquals(73L, operation)
    }

    @Test
    fun missingAndLateInitialInspectionStayBoundedAndCorrelated() {
        val dispatcher = DiLink5WorkerResultDispatcher<String>(20L)
        var completion: ((Long, String?) -> Unit)? = null
        var late: String? = null
        val started = System.nanoTime()
        val result = dispatchInitialInspection(91L) { candidate, operationId ->
            dispatcher.dispatch(
                candidate.processName,
                operationId,
                onLateResult = { late = it },
                start = {
                    completion = it
                    true
                },
            )
        }
        val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - started)
        assertEquals(null, result)
        assertTrue(elapsedMs < 1_000L)
        completion!!(90L, "wrong")
        assertEquals(null, late)
        completion!!(91L, "late")
        assertEquals("late", late)
    }

    @Test
    fun onlyExactWorkerNamesUseMinimalStartup() {
        DiLink5AdbWorkerProcess.candidates.forEach { assertTrue(DiLink5AdbWorkerProcess.isWorkerProcess(it.processName)) }
        assertFalse(DiLink5AdbWorkerProcess.isWorkerProcess("com.overdrive.app"))
        assertFalse(DiLink5AdbWorkerProcess.isWorkerProcess("com.byd.warning"))
        assertFalse(DiLink5AdbWorkerProcess.isWorkerProcess("com.byd.launchermap:other"))
        assertFalse(DiLink5AdbWorkerProcess.isWorkerProcess(null))
    }
}
