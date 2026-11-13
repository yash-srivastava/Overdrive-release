package com.overdrive.app.navmap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RtpH264DepacketizerTest {

    @Test
    public void assemblesSpsPpsAndIdrIntoOneMarkedAccessUnit() {
        RtpH264Depacketizer parser = new RtpH264Depacketizer();
        assertNull(parser.accept(rtp(1, 100, false, 0x67, 0x42, 0x00, 0x1e), 16));
        assertNull(parser.accept(rtp(2, 100, false, 0x68, 0xce, 0x06, 0xe2), 16));

        byte[] idr = rtp(3, 100, true, 0x65, 0x88, 0x84);
        RtpH264Depacketizer.AccessUnit unit = parser.accept(idr, idr.length);

        assertNotNull(unit);
        assertTrue(unit.keyframe);
        assertFalse(unit.discontinuity);
        assertArrayEquals(new byte[] {0x67, 0x42, 0x00, 0x1e}, unit.sps);
        assertArrayEquals(new byte[] {(byte) 0x68, (byte) 0xce, 0x06, (byte) 0xe2}, unit.pps);
        assertEquals(3, countStartCodes(unit.annexB));
    }

    @Test
    public void reassemblesFuAIdrFragments() {
        RtpH264Depacketizer parser = new RtpH264Depacketizer();
        byte[] sps = rtp(1, 200, false, 0x67, 0x42, 0x00, 0x1e);
        byte[] pps = rtp(2, 200, false, 0x68, 0xce, 0x06, 0xe2);
        parser.accept(sps, sps.length);
        parser.accept(pps, pps.length);

        // FU indicator: F/NRI from the original NAL plus type 28.
        byte[] first = rtp(3, 200, false, 0x7c, (byte) 0x85, 0x11, 0x22);
        byte[] last = rtp(4, 200, true, 0x7c, 0x45, 0x33, 0x44);
        assertNull(parser.accept(first, first.length));
        RtpH264Depacketizer.AccessUnit unit = parser.accept(last, last.length);

        assertNotNull(unit);
        assertTrue(unit.keyframe);
        assertEquals(3, countStartCodes(unit.annexB));
        assertTrue(contains(unit.annexB, new byte[] {0x65, 0x11, 0x22, 0x33, 0x44}));
    }

    @Test
    public void sequenceGapDropsDamagedFrameAndMarksNextCompleteFrame() {
        RtpH264Depacketizer parser = new RtpH264Depacketizer();
        byte[] sps = rtp(10, 300, false, 0x67, 0x42, 0x00, 0x1e);
        byte[] pps = rtp(11, 300, false, 0x68, 0xce, 0x06, 0xe2);
        byte[] lostFrame = rtp(13, 300, true, 0x65, 0x01);
        parser.accept(sps, sps.length);
        parser.accept(pps, pps.length);
        assertNull(parser.accept(lostFrame, lostFrame.length));

        byte[] nextSps = rtp(14, 400, false, 0x67, 0x42, 0x00, 0x1e);
        byte[] nextPps = rtp(15, 400, false, 0x68, 0xce, 0x06, 0xe2);
        byte[] nextIdr = rtp(16, 400, true, 0x65, 0x02);
        parser.accept(nextSps, nextSps.length);
        parser.accept(nextPps, nextPps.length);
        RtpH264Depacketizer.AccessUnit unit = parser.accept(nextIdr, nextIdr.length);

        assertNotNull(unit);
        assertTrue(unit.keyframe);
        assertTrue(unit.discontinuity);
    }

    @Test
    public void cachedParameterSetsArePrependedToLaterIdr() {
        RtpH264Depacketizer parser = new RtpH264Depacketizer();
        byte[] sps = rtp(1, 500, false, 0x67, 0x42, 0x00, 0x1e);
        byte[] pps = rtp(2, 500, false, 0x68, 0xce, 0x06, 0xe2);
        byte[] pFrame = rtp(3, 500, true, 0x41, 0x10);
        parser.accept(sps, sps.length);
        parser.accept(pps, pps.length);
        RtpH264Depacketizer.AccessUnit p = parser.accept(pFrame, pFrame.length);
        assertNotNull(p);
        assertFalse(p.keyframe);

        byte[] idr = rtp(4, 600, true, 0x65, 0x20);
        RtpH264Depacketizer.AccessUnit key = parser.accept(idr, idr.length);
        assertNotNull(key);
        assertTrue(key.keyframe);
        assertEquals(3, countStartCodes(key.annexB));
    }

    private static byte[] rtp(int sequence, long timestamp, boolean marker, int... payload) {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0) | 96);
        packet[2] = (byte) (sequence >>> 8);
        packet[3] = (byte) sequence;
        packet[4] = (byte) (timestamp >>> 24);
        packet[5] = (byte) (timestamp >>> 16);
        packet[6] = (byte) (timestamp >>> 8);
        packet[7] = (byte) timestamp;
        packet[8] = 1;
        for (int i = 0; i < payload.length; i++) packet[12 + i] = (byte) payload[i];
        return packet;
    }

    private static int countStartCodes(byte[] bytes) {
        int count = 0;
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 0 && bytes[i + 3] == 1) count++;
        }
        return count;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
