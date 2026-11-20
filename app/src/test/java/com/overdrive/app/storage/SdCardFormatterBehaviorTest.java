package com.overdrive.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Behavioural checks of pure parsing, media IDs and the older-DiLink PM contract. */
public class SdCardFormatterBehaviorTest {
    @Test public void acceptsShark6AndAospVolumeIdsButRejectsInvalidMedia() {
        SdCardFormatter.Volume bare = SdCardFormatter.parseVolume("8:1 mounted 1234-ABCD");
        assertNotNull(bare);
        assertEquals("8:1", bare.id);
        assertEquals("1234-ABCD", bare.uuid);
        assertNotNull(SdCardFormatter.parseVolume("public:179,65 unmounted null"));
        assertFalse(StorageManager.isPublicVolumeId(null));
        assertNull(SdCardFormatter.parseVolume("private:179,65 mounted ABCD-1234"));
        assertNull(SdCardFormatter.parseVolume("8:1 checking ABCD-1234"));
        assertNull(SdCardFormatter.parseVolume("8:1 mounted ABCD-1234 extra"));
        assertTrue(SdCardFormatter.isValidUuid("1234-ABCD"));
        assertFalse(SdCardFormatter.isValidUuid("../bad"));
        assertFalse(SdCardFormatter.isValidUuid("null"));
    }

    @Test public void restoreNeedsNonDisabledPackageAndActualApkPath() {
        assertTrue(SdCardFormatter.isFactoryDvrRestored(
                "package:com.example.disabled\n", "package:/system/app/CDR/base.apk\n"));
        assertFalse(SdCardFormatter.isFactoryDvrRestored(
                "package:com.byd.cdr\n", "package:/system/app/CDR/base.apk\n"));
        assertFalse(SdCardFormatter.isFactoryDvrRestored("", "Error: unknown package"));
        assertFalse(SdCardFormatter.isFactoryDvrRestored("", "package:com.byd.cdr"));
    }

    @Test public void onlyMmcCidCanIdentifyPhysicalSdMedia() {
        assertTrue(SdCardFormatter.isValidMmcCid("1a2b3c4d5e6f00112233445566778899"));
        assertFalse(SdCardFormatter.isValidMmcCid("12345")); // Linux diskseq is NOT SD proof
        assertFalse(SdCardFormatter.isValidMmcCid("0000000000000000000000000000000Z"));
        assertFalse(SdCardFormatter.isValidMmcCid(null));
    }

    @Test public void confirmationTokenBindsPhysicalCardEvenWithSameFilesystemUuid() {
        SdCardFormatter.Target first = new SdCardFormatter.Target(
                "1234-ABCD", "public:179,65", 100, 10,
                "mmc-cid:1a2b3c4d5e6f00112233445566778899");
        SdCardFormatter.Target same = new SdCardFormatter.Target(
                "1234-ABCD", "public:179,65", 100, 10,
                "mmc-cid:1a2b3c4d5e6f00112233445566778899");
        SdCardFormatter.Target replacement = new SdCardFormatter.Target(
                "1234-ABCD", "public:179,65", 100, 10,
                "mmc-cid:9a2b3c4d5e6f00112233445566778899");
        assertEquals(first.identityToken, same.identityToken);
        assertFalse(first.identityToken.equals(replacement.identityToken));
        assertNull(new SdCardFormatter.Target(
                "1234-ABCD", "public:8,1", 100, 10, null).identityToken);
    }

    @Test public void refusalReasonCodesRemainStable() {
        assertEquals("dilink100_unsupported", SdCardFormatter.reasonCode(
                "DiLink 100 multi-user removable storage cannot be formatted safely."));
        assertEquals("media_identity", SdCardFormatter.reasonCode(
                "Cannot verify SD media identity on this firmware; format blocked."));
        assertEquals("cdr_running", SdCardFormatter.reasonCode(
                "Factory BYD CDR is running. Stop it before formatting."));
        assertEquals("unverified", SdCardFormatter.reasonCode(
                "Format outcome unverified: no changed mounted UUID"));
        assertEquals("changed", SdCardFormatter.reasonCode("SD card changed before format"));
        assertEquals("storage_busy", SdCardFormatter.reasonCode(
                "Storage is busy. Try again when SD activity has stopped."));
    }
}
