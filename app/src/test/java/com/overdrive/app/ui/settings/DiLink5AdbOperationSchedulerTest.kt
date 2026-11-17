package com.overdrive.app.ui.fragment.settings

import com.overdrive.app.launcher.dilink5.enable.DiLink5AdbEnableCoordinator
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5AdbOperationSchedulerTest {
    @Test
    fun repeatedInputIsRejectedBeforeQueueWhileUncertainResultIsPending() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val scheduler = DiLink5AdbOperationScheduler(worker) { command -> command.run() }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val calls = AtomicInteger(0)
            val observed = AtomicReference<DiLink5AdbEnableCoordinator.Result>()

            assertTrue(scheduler.submit(
                operation = {
                    calls.incrementAndGet()
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    result(DiLink5AdbEnableCoordinator.Stage.FLAG_UNCERTAIN_TRANSITION)
                },
                onComplete = {
                    observed.set(it)
                    completed.countDown()
                },
            ))
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            assertFalse(scheduler.submit(
                operation = {
                    calls.incrementAndGet()
                    result(DiLink5AdbEnableCoordinator.Stage.FLAG_CONFIRMED)
                },
                onComplete = {},
            ))
            assertEquals(1, calls.get())

            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            val idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (scheduler.isRunning() && System.nanoTime() < idleDeadline) Thread.yield()
            assertFalse(scheduler.isRunning())
            assertEquals(
                DiLink5AdbEnableCoordinator.Stage.FLAG_UNCERTAIN_TRANSITION,
                observed.get().stage,
            )
            assertTrue(scheduler.submit(
                operation = { result(DiLink5AdbEnableCoordinator.Stage.DIAGNOSTICS) },
                onComplete = {},
            ))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun replacementLifecycleStaysBusyAndDispatchesNoSecondEffect() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val owner = DiLink5AdbOperationScheduler(worker) { command -> command.run() }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val replacementSawBusy = CountDownLatch(1)
            val effects = AtomicInteger(0)

            val firstObservation = owner.observeRunning { }
            assertTrue(owner.submit(
                operation = {
                    effects.incrementAndGet()
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    "first"
                },
                onComplete = {},
            ))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            firstObservation.close()

            val replacementObservation = owner.observeRunning { running ->
                if (running) replacementSawBusy.countDown()
            }
            assertTrue(replacementSawBusy.await(1, TimeUnit.SECONDS))
            assertFalse(owner.submit(
                operation = {
                    effects.incrementAndGet()
                    "replacement"
                },
                onComplete = {},
            ))
            assertEquals(1, effects.get())
            replacementObservation.close()
            release.countDown()
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun repeatedExportIsRejectedBeforeASecondExportCanStart() {
        assertSecondEffectRejected("export", "export")
    }

    @Test
    fun exportBlocksEnablementUntilExportCompletes() {
        assertSecondEffectRejected("export", "enable")
    }

    @Test
    fun settingsUseOneProcessOwnerForEveryRelatedControl() {
        val fragment = String(
            Files.readAllBytes(Paths.get("src/main/java/com/overdrive/app/ui/fragment/settings/SettingsAdbFragment.kt")),
            Charsets.UTF_8,
        )

        assertTrue(fragment.contains("private val ADB_OPERATION_SCHEDULER"))
        assertTrue(fragment.contains("ADB_OPERATION_SCHEDULER.observeRunning"))
        assertTrue(fragment.contains("ADB_OPERATION_SCHEDULER.submit("))
        assertTrue(fragment.contains("runDeveloperToolsExport()"))
        assertFalse(fragment.contains("executor().execute {\n            val result = DiLink5DeveloperToolsExport.export"))
        assertTrue(fragment.contains("setAdbOperationControlsEnabled(!running)"))
        assertTrue(fragment.contains("setAdbOperationControlsEnabled(false)"))
        val controlRenderer = fragment.substringAfter("private fun setAdbOperationControlsEnabled(")
            .substringBefore("private fun renderAdbResult(")
        val cancelEnablement = Regex(
            """btnAdbCancelRecovery\.isEnabled\s*=\s*([^\n]+)""",
        ).find(controlRenderer)?.groupValues?.get(1)?.trim()
        assertEquals(
            "localRecoveryManager().hasManualRecoveryOwner()",
            cancelEnablement,
        )
        val controls = fragment.substringAfter("adbOperationControls = listOf(")
            .substringBefore(")")
        assertFalse(controls.contains("btnAdbCancelRecovery"))
        listOf(
            "btnAdbEnable",
            "btnAdbPage",
            "btnAdbDiagnostics",
            "btnAdbRestore",
            "btnAdbExport",
        ).forEach { assertTrue(fragment.contains(it)) }
    }

    private fun assertSecondEffectRejected(firstName: String, secondName: String) {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val scheduler = DiLink5AdbOperationScheduler(worker) { command -> command.run() }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val effects = mutableListOf<String>()

            assertTrue(scheduler.submit(
                operation = {
                    synchronized(effects) { effects += firstName }
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    firstName
                },
                onComplete = {},
            ))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(scheduler.submit(
                operation = {
                    synchronized(effects) { effects += secondName }
                    secondName
                },
                onComplete = {},
            ))
            assertEquals(listOf(firstName), synchronized(effects) { effects.toList() })
            release.countDown()
        } finally {
            worker.shutdownNow()
        }
    }

    private fun result(stage: DiLink5AdbEnableCoordinator.Stage) =
        DiLink5AdbEnableCoordinator.Result(stage, false, emptyList())
}
