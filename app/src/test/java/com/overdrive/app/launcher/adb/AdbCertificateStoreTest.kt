package com.overdrive.app.launcher.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdbCertificateStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun corruptCertificateIsRepairedWithoutRotatingKey() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val identity = AdbIdentityProvider(directory).load().identity
        val store = AdbCertificateStore(directory)
        val first = store.loadOrCreate(identity)
        assertTrue(first.repaired)
        File(directory, "adbkey.cert").writeText("corrupt")
        val repaired = store.loadOrCreate(identity)
        assertTrue(repaired.repaired)
        assertArrayEquals(identity.javaKeyPair.public.encoded, repaired.certificate.publicKey.encoded)
        assertArrayEquals(identity.javaKeyPair.public.encoded, first.certificate.publicKey.encoded)
        assertFalse(repaired.certificate.encoded.contentEquals("corrupt".toByteArray()))
    }

    @Test fun validCertificateIsReused() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val identity = AdbIdentityProvider(directory).load().identity
        val store = AdbCertificateStore(directory)
        val first = store.loadOrCreate(identity)
        val second = store.loadOrCreate(identity)
        assertFalse(second.repaired)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }
}
