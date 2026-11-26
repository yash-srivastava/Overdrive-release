package com.overdrive.app.ui.viewmodel

import androidx.lifecycle.Observer
import androidx.test.platform.app.InstrumentationRegistry
import com.overdrive.app.ui.model.LogEntry
import com.overdrive.app.ui.model.LogLevel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsViewModelAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun inactiveHistoryIsBoundedAndReopeningImmediatelyShowsLatestFilter() {
        val vm = LogsViewModel()
        repeat(750) { vm.debug(if (it % 2 == 0) "Camera" else "System", "$it") }
        val observed = AtomicReference<List<LogEntry>>()
        val observer = Observer<List<LogEntry>> { observed.set(it) }
        instrumentation.runOnMainSync {
            vm.setFilter("camera")
            vm.filteredLogs.observeForever(observer)
        }
        try {
            assertEquals((250 until 750 step 2).map { "$it" }, observed.get().map { it.message })
            assertTrue(observed.get().all { it.level == LogLevel.DEBUG })
            assertEquals(listOf("Camera", "System"), vm.getAvailableTags())
            instrumentation.runOnMainSync { vm.filteredLogs.removeObserver(observer) }
            repeat(600) { vm.info("Camera", "new$it") }
            instrumentation.runOnMainSync { vm.filteredLogs.observeForever(observer) }
            assertEquals((100 until 600).map { "new$it" }, observed.get().map { it.message })
        } finally {
            instrumentation.runOnMainSync { vm.filteredLogs.removeObserver(observer) }
        }
    }

    @Test
    fun burstPublishesOnceAndClearCancelsDelayedPublication() {
        val vm = LogsViewModel()
        val emissions = AtomicInteger()
        val latest = AtomicReference<List<LogEntry>>()
        val burst = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val observer = Observer<List<LogEntry>> {
            latest.set(it)
            val count = emissions.incrementAndGet()
            if (it.size == 500 && it.last().message == "999") burst.countDown()
            if (count > 2 && it.isEmpty()) cleared.countDown()
        }
        instrumentation.runOnMainSync {
            vm.logs.observeForever(observer)
            repeat(1000) { vm.info("System", "$it") }
        }
        try {
            assertTrue(burst.await(5, TimeUnit.SECONDS))
            assertEquals(2, emissions.get()) // Initial value and one burst snapshot.
            assertEquals((500 until 1000).map { "$it" }, latest.get().map { it.message })
            instrumentation.runOnMainSync {
                vm.info("System", "pending")
                vm.clearLogs()
            }
            assertTrue(cleared.await(5, TimeUnit.SECONDS))
            assertTrue(latest.get().isEmpty())
            assertTrue(vm.getAvailableTags().isEmpty())
        } finally {
            instrumentation.runOnMainSync { vm.logs.removeObserver(observer) }
        }
    }
}
