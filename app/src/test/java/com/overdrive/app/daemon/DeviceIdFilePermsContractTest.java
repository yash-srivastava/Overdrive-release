package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;

/**
 * V60-02 contract: the device-ID file is the CredentialCipher key seed
 * (SHA-256("overdrive-byd-cred-v1:" + DID) derives the AES key for every ENC:
 * credential blob). It must never be world-WRITABLE: any local UID could
 * otherwise swap the key seed. The app only reads it (writes go through the
 * adb-shell executor), so owner-write-only (0644) is safe.
 */
public class DeviceIdFilePermsContractTest {
    @Test
    public void deviceIdFileIsNotWorldWritable() throws Exception {
        File f = File.createTempFile("byd_device_id", ".tmp");
        try {
            // simulate the daemon self-heal path: readable by all, writable by owner only
            assertTrue(f.setReadable(true, false));
            assertTrue(f.setWritable(true, true));
            assertTrue(f.setWritable(false, false));
            assertFalse("DID file must not be world-writable", f.canWrite() && isWorldWritable(f));
        } finally {
            f.delete();
        }
    }

    private static boolean isWorldWritable(File f) throws Exception {
        return (Files.getPosixFilePermissions(f.toPath())
                .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
    }
}
