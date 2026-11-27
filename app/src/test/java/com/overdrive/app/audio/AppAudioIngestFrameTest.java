package com.overdrive.app.audio;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import org.junit.Test;

/** Ingest frames are sent in one write; their header must stay what DataOutputStream wrote. */
public class AppAudioIngestFrameTest {

    @Test
    public void headerBytesMatchDataOutputStream() throws Exception {
        int[] ints = {0, 1, 12 + 171, 4 + 3840, 0x01020304, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        long[] longs = {0L, 1L, 36_440_394_853L, 0x0102030405060708L, Long.MAX_VALUE, -1L, Long.MIN_VALUE};
        for (int total : ints) {
            for (long pts : longs) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(total);
                out.writeInt(2);
                out.writeLong(pts);

                byte[] frame = new byte[16];
                AppAudioCaptureController.putIntBE(frame, 0, total);
                AppAudioCaptureController.putIntBE(frame, 4, 2);
                AppAudioCaptureController.putLongBE(frame, 8, pts);

                assertArrayEquals(bytes.toByteArray(), frame);
            }
        }
    }
}
