package com.overdrive.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileUtilsTest {

    @Test
    fun existingDirectoryReturnsTrue() {
        val dir = Files.createTempDirectory("overdrive-file-utils-").toFile()
        try {
            assertTrue(FileUtils.ensureDirectoryExists(dir.absolutePath))
        } finally {
            dir.delete()
        }
    }

    @Test
    fun existingRegularFileReturnsFalse() {
        val file = Files.createTempFile("overdrive-file-utils-", ".tmp").toFile()
        try {
            assertFalse(FileUtils.ensureDirectoryExists(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun createsNestedDirectories() {
        val parent = Files.createTempDirectory("overdrive-file-utils-").toFile()
        val nested = File(parent, "one/two")
        try {
            assertTrue(FileUtils.ensureDirectoryExists(nested.absolutePath))
            assertTrue(nested.isDirectory)
        } finally {
            nested.delete()
            nested.parentFile.delete()
            parent.delete()
        }
    }

    @Test
    fun regularFileInParentPathReturnsFalse() {
        val file = Files.createTempFile("overdrive-file-utils-", ".tmp").toFile()
        try {
            assertFalse(FileUtils.ensureDirectoryExists(File(file, "child").absolutePath))
        } finally {
            file.delete()
        }
    }
}
