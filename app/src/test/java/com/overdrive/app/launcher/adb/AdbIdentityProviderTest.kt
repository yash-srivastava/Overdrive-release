package com.overdrive.app.launcher.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdbIdentityProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newIdentityIsNotTrustedUntilClassicAuthenticationIsRecorded() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val provider = AdbIdentityProvider(directory)
        val first = provider.load()
        assertEquals(AdbIdentityProvider.TrustState.NEW, first.trustState)
        assertFalse(first.previouslyAuthenticated)
        assertTrue(provider.markAuthenticated(first.identity))
        AdbIdentityProvider.clearCacheForTests()
        val restarted = AdbIdentityProvider(directory).load()
        assertEquals(AdbIdentityProvider.TrustState.AUTHENTICATED, restarted.trustState)
        assertTrue(restarted.previouslyAuthenticated)
        assertArrayEquals(first.identity.javaKeyPair.public.encoded, restarted.identity.javaKeyPair.public.encoded)
    }

    @Test fun existingIdentityWithoutTrustMarkerRemainsUnverified() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val first = AdbIdentityProvider(directory).load()
        AdbIdentityProvider.clearCacheForTests()
        val second = AdbIdentityProvider(directory).load()
        assertEquals(AdbIdentityProvider.TrustState.UNVERIFIED, second.trustState)
        assertFalse(second.previouslyAuthenticated)
        assertArrayEquals(first.identity.javaKeyPair.public.encoded, second.identity.javaKeyPair.public.encoded)
    }

    @Test fun corruptPrivateKeyReportsTrustLossAndReplacesIdentity() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val provider = AdbIdentityProvider(directory)
        val first = provider.load()
        assertTrue(provider.markAuthenticated(first.identity))
        File(directory, "adbkey").writeText("corrupt")
        AdbIdentityProvider.clearCacheForTests()
        val second = AdbIdentityProvider(directory).load()
        assertEquals(AdbIdentityProvider.TrustState.REPLACED, second.trustState)
        assertTrue(second.trustLost)
        assertFalse(second.previouslyAuthenticated)
        assertFalse(first.identity.javaKeyPair.public.encoded.contentEquals(second.identity.javaKeyPair.public.encoded))
    }

    @Test fun missingOneKeyFileReplacesIdentityAndClearsTrust() {
        val directory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val provider = AdbIdentityProvider(directory)
        val first = provider.load()
        assertTrue(provider.markAuthenticated(first.identity))
        File(directory, "adbkey.pub").delete()
        AdbIdentityProvider.clearCacheForTests()
        val replaced = AdbIdentityProvider(directory).load()
        assertEquals(AdbIdentityProvider.TrustState.REPLACED, replaced.trustState)
        assertFalse(replaced.previouslyAuthenticated)
    }

    @Test fun mismatchedPublicKeyReplacesIdentityAndClearsTrust() {
        val firstDirectory = temporary.newFolder()
        val secondDirectory = temporary.newFolder()
        AdbIdentityProvider.clearCacheForTests()
        val firstProvider = AdbIdentityProvider(firstDirectory)
        val first = firstProvider.load()
        assertTrue(firstProvider.markAuthenticated(first.identity))
        AdbIdentityProvider.clearCacheForTests()
        AdbIdentityProvider(secondDirectory).load()
        File(secondDirectory, "adbkey.pub").copyTo(File(firstDirectory, "adbkey.pub"), overwrite = true)
        AdbIdentityProvider.clearCacheForTests()
        val replaced = AdbIdentityProvider(firstDirectory).load()
        assertEquals(AdbIdentityProvider.TrustState.REPLACED, replaced.trustState)
        assertFalse(replaced.previouslyAuthenticated)
    }
}
