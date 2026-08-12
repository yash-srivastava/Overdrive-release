package com.overdrive.app.ui.adapter

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightLoadCoordinatorTest {

    @Test
    fun sameKeySharesOneInFlightLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = InFlightLoadCoordinator<String, Int>(scope, maxConcurrent = 2)
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()

            val first = coordinator.load("recording") {
                calls.incrementAndGet()
                release.await()
                42
            }
            val second = coordinator.load("recording") {
                calls.incrementAndGet()
                99
            }

            assertSame(first, second)
            release.complete(Unit)
            assertEquals(listOf(42, 42), awaitAll(first, second))
            assertEquals(1, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun distinctLoadsRespectConcurrencyLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = InFlightLoadCoordinator<Int, Int>(scope, maxConcurrent = 2)
            val entered = Channel<Unit>(Channel.UNLIMITED)
            val release = Channel<Unit>(Channel.UNLIMITED)
            val active = AtomicInteger()
            val peak = AtomicInteger()

            val loads = (0 until 4).map { key ->
                coordinator.load(key) {
                    val nowActive = active.incrementAndGet()
                    peak.accumulateAndGet(nowActive, ::maxOf)
                    entered.send(Unit)
                    try {
                        release.receive()
                        key
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            repeat(2) { entered.receive() }
            assertEquals(2, active.get())
            assertEquals(4, coordinator.inFlightCount())

            repeat(2) { release.send(Unit) }
            repeat(2) { entered.receive() }
            repeat(2) { release.send(Unit) }

            assertEquals(listOf(0, 1, 2, 3), loads.awaitAll().sorted())
            assertEquals(2, peak.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelSharedLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = InFlightLoadCoordinator<String, Int>(scope, maxConcurrent = 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val shared = coordinator.load("recording") {
                entered.complete(Unit)
                release.await()
                42
            }
            val waiter = launch { shared.await() }

            entered.await()
            waiter.cancelAndJoin()
            assertFalse(shared.isCancelled)

            release.complete(Unit)
            assertEquals(42, shared.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledScopeCannotLeaveStaleInFlightEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InFlightLoadCoordinator<String, Int>(scope, maxConcurrent = 1)
        scope.cancel()

        val first = coordinator.load("recording") { 1 }
        first.join()
        assertEquals(0, coordinator.inFlightCount())

        val second = coordinator.load("recording") { 2 }
        second.join()
        assertNotSame(first, second)
        assertEquals(0, coordinator.inFlightCount())
    }

    @Test
    fun completedLoadIsRemovedSoARefreshCanRun() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = InFlightLoadCoordinator<String, Int>(scope, maxConcurrent = 1)
            val first = coordinator.load("recording") { 1 }
            assertEquals(1, first.await())

            val second = coordinator.load("recording") { 2 }
            assertNotSame(first, second)
            assertEquals(2, second.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recordingAdapterUsesSharedBoundedCoordinator() {
        val source = readRepositoryFile(
            "app/src/main/java/com/overdrive/app/ui/adapter/RecordingAdapter.kt"
        )

        assertTrue(source.contains("InFlightLoadCoordinator<String, Bitmap?>"))
        assertTrue(source.contains("maxConcurrent = 2"))
        assertTrue(source.contains("holder.cancelThumbnailWait()"))
        assertTrue(source.contains("thumbnailScope.cancel()"))
        assertTrue(source.contains("if (!thumbnailScope.isActive)"))
        assertFalse(source.contains("CoroutineScope(Dispatchers.IO).launch"))
    }

    private fun readRepositoryFile(relativePath: String): String {
        var current = Paths.get(System.getProperty("user.dir"))
            .toAbsolutePath().normalize()
        while (true) {
            val candidate = current.resolve(relativePath)
            if (Files.isRegularFile(candidate)) {
                return String(Files.readAllBytes(candidate), StandardCharsets.UTF_8)
            }
            val fromModule = current.resolve(relativePath.removePrefix("app/"))
            if (Files.isRegularFile(fromModule)) {
                return String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8)
            }
            current = current.parent ?: break
        }
        throw AssertionError("Could not locate repository file: $relativePath")
    }
}