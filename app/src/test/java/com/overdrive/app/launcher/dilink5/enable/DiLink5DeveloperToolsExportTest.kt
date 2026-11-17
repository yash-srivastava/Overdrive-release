package com.overdrive.app.launcher.dilink5.enable

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5DeveloperToolsExportTest {
    @Test
    fun exportsOnlyTheBaseApkAndClosesBothStreams() {
        val backend = FakeBackend()
        val result = DiLink5DeveloperToolsExport.export(backend)

        assertEquals(DiLink5DeveloperToolsExport.Outcome.EXPORTED_BASE_APK, result.outcome)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), backend.output.toByteArray())
        assertTrue(backend.inputClosed)
        assertTrue(backend.outputClosed)
        assertTrue(backend.published)
        assertFalse(backend.deleted)
        assertTrue(result.detail.contains("Split APKs are not included"))
    }

    @Test
    fun failedPendingExportIsDeletedAndCleanupFailureRemainsVisible() {
        val cleaned = FakeBackend().apply { destinationAvailable = false }
        assertEquals(
            DiLink5DeveloperToolsExport.Outcome.FAILED,
            DiLink5DeveloperToolsExport.export(cleaned).outcome,
        )
        assertTrue(cleaned.deleted)

        val notCleaned = FakeBackend().apply {
            destinationAvailable = false
            deleteSucceeds = false
        }
        assertEquals(
            DiLink5DeveloperToolsExport.Outcome.FAILED_CLEANUP,
            DiLink5DeveloperToolsExport.export(notCleaned).outcome,
        )
    }

    @Test
    fun unsupportedPlatformCreatesNoDownloadsItem() {
        val backend = FakeBackend().apply { sdk = 28 }
        assertEquals(
            DiLink5DeveloperToolsExport.Outcome.UNSUPPORTED,
            DiLink5DeveloperToolsExport.export(backend).outcome,
        )
        assertFalse(backend.created)
    }

    private class FakeBackend : DiLink5DeveloperToolsExport.Backend {
        var sdk = 29
        var created = false
        var published = false
        var deleted = false
        var destinationAvailable = true
        var deleteSucceeds = true
        var inputClosed = false
        var outputClosed = false
        val output = ByteArrayOutputStream()

        override val sdkInt get() = sdk
        override fun source() = DiLink5DeveloperToolsExport.Source("/base.apk", "1.0")
        override fun createPending(displayName: String): String {
            created = true
            return "pending"
        }
        override fun openSource(source: DiLink5DeveloperToolsExport.Source): InputStream =
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
                override fun close() {
                    inputClosed = true
                    super.close()
                }
            }
        override fun openDestination(token: String): OutputStream? = if (destinationAvailable) {
            object : OutputStream() {
                override fun write(value: Int) = output.write(value)
                override fun write(value: ByteArray, offset: Int, length: Int) = output.write(value, offset, length)
                override fun close() { outputClosed = true }
            }
        } else null
        override fun publish(token: String): Boolean {
            published = true
            return true
        }
        override fun delete(token: String): Boolean {
            deleted = true
            return deleteSucceeds
        }
    }
}
