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

    @Test public void transientBusyIsNotCachedAndWorkerPreservesBusyReason() {
        SdCardFormatter.Preview busy = new SdCardFormatter.Preview(null, "storage_busy");
        SdCardFormatter.Preview missing = new SdCardFormatter.Preview(null, "no_card");
        assertFalse(SdCardFormatter.isCacheablePreview(busy));
        assertTrue(SdCardFormatter.isCacheablePreview(missing));
        assertEquals("Storage is busy. Try again when SD activity has stopped.",
                SdCardFormatter.preflightFailureMessage(busy,
                        "SD card changed before format; operation blocked."));
        assertEquals("SD card changed before format; operation blocked.",
                SdCardFormatter.preflightFailureMessage(missing,
                        "SD card changed before format; operation blocked."));
    }

    // Disk records read from a BYD100F (New E2): internal SD reader and a USB stick.
    private static final String SD_READER = "/sys//devices/platform/soc/a800000.ssusb/a800000.dwc3/"
            + "xhci-hcd.2.auto/usb3/3-1/3-1.4/3-1.4:1.0/host0/target0:0:0/0:0:0:0/block/sda";
    private static final String USB_STICK = "/sys//devices/platform/soc/a600000.ssusb/a600000.dwc3/"
            + "xhci-hcd.1.auto/usb1/1-1/1-1:1.0/host1/target1:0:0/1:0:0:0/block/sdb";

    @Test public void voldSdDiskIsBoundToItsReaderPortAndExactSize() {
        String sd = SdCardFormatter.voldSlotFingerprint(5, SD_READER, 256355860480L, "Generic- ");
        assertEquals("vold-sd:/sys/devices/platform/soc/a800000.ssusb/a800000.dwc3/"
                + "xhci-hcd.2.auto/usb3/3-1/3-1.4/3-1.4:1.0|256355860480|Generic-", sd);
        // Detection order can rename sda to sdb; the reader port is what identifies the slot.
        assertEquals(sd, SdCardFormatter.voldSlotFingerprint(5,
                SD_READER.replace("host0/target0:0:0/0:0:0:0/block/sda", "host2/target2:0:0/2:0:0:0/block/sdb"),
                256355860480L, "Generic-"));
        // A different card in the same slot has a different size.
        assertFalse(sd.equals(SdCardFormatter.voldSlotFingerprint(5, SD_READER, 63864569856L, "Generic-")));
    }

    @Test public void voldFallbackRefusesUsbDisksAndIncompleteRecords() {
        assertNull(SdCardFormatter.voldSlotFingerprint(9, USB_STICK, 61920509952L, "Kingston"));
        assertNull(SdCardFormatter.voldSlotFingerprint(4 | 8, SD_READER, 256355860480L, "Generic-"));
        assertNull(SdCardFormatter.voldSlotFingerprint(5, SD_READER, 0L, "Generic-"));
        assertNull(SdCardFormatter.voldSlotFingerprint(5, null, 256355860480L, "Generic-"));
        assertNull(SdCardFormatter.voldSlotFingerprint(5, "/dev/block/sda", 256355860480L, "Generic-"));
    }

    @Test public void persistentFlagIsReadFromPkgFlagsOnly() {
        assertTrue(SdCardFormatter.isPersistentPackage("  Package [com.byd.cdr]\n"
                + "    flags=[ SYSTEM HAS_CODE PERSISTENT ALLOW_CLEAR_USER_DATA ]\n"
                + "    pkgFlags=[ SYSTEM HAS_CODE PERSISTENT ALLOW_CLEAR_USER_DATA ]\n"));
        assertFalse(SdCardFormatter.isPersistentPackage(
                "    pkgFlags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]\n    persistent=true\n"));
        assertFalse(SdCardFormatter.isPersistentPackage("Unable to find package: com.byd.cdr\n"));
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
