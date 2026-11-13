package com.overdrive.app.navmap;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Small RFC 6184 H.264/RTP depacketizer for the Android Auto cluster stream.
 *
 * <p>The paired receiver emits single-NAL and FU-A packets with a 12-byte RTP
 * header and payload type 96. This parser also accepts CSRC lists, RTP header
 * extensions, padding and STAP-A packets so harmless sender variations do not
 * corrupt the video stream.
 *
 * <p>One returned AccessUnit is one RTP timestamp ending at the marker bit.
 * Packet loss drops the incomplete access unit; callers should wait for the
 * next IDR before resuming a decoder.
 */
final class RtpH264Depacketizer {

    private static final byte[] START_CODE = new byte[] {0, 0, 0, 1};
    private static final int PAYLOAD_TYPE = 96;
    private static final int NAL_STAP_A = 24;
    private static final int NAL_FU_A = 28;
    private static final int NAL_SPS = 7;
    private static final int NAL_PPS = 8;
    private static final int NAL_IDR = 5;
    private static final int MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FRAGMENT_BYTES = 4 * 1024 * 1024;

    static final class AccessUnit {
        final byte[] annexB;
        final long timestamp90k;
        final boolean keyframe;
        final boolean discontinuity;
        final byte[] sps;
        final byte[] pps;

        AccessUnit(byte[] annexB, long timestamp90k, boolean keyframe,
                   boolean discontinuity, byte[] sps, byte[] pps) {
            this.annexB = annexB;
            this.timestamp90k = timestamp90k;
            this.keyframe = keyframe;
            this.discontinuity = discontinuity;
            this.sps = sps == null ? null : sps.clone();
            this.pps = pps == null ? null : pps.clone();
        }
    }

    private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream(64 * 1024);
    private ByteArrayOutputStream fragmentedNal;
    private int fragmentHeader;
    private int expectedSequence = -1;
    private long currentTimestamp = -1L;
    private boolean droppingUntilMarker;
    private boolean discontinuityPending;
    private boolean currentHasSps;
    private boolean currentHasPps;
    private boolean currentHasIdr;
    private byte[] cachedSps;
    private byte[] cachedPps;

    synchronized AccessUnit accept(byte[] packet, int length) {
        if (packet == null || length < 12 || length > packet.length) return null;
        int first = packet[0] & 0xff;
        if ((first >>> 6) != 2) return null;

        int second = packet[1] & 0xff;
        if ((second & 0x7f) != PAYLOAD_TYPE) return null;

        int sequence = u16(packet, 2);
        long timestamp = u32(packet, 4);
        boolean marker = (second & 0x80) != 0;

        if (expectedSequence >= 0 && sequence != expectedSequence) {
            // Local RTP should not reorder. A missing fragment invalidates the
            // complete access unit, so discard through its marker.
            dropCurrent(true);
            droppingUntilMarker = true;
        }
        expectedSequence = (sequence + 1) & 0xffff;

        if (droppingUntilMarker) {
            if (marker) {
                droppingUntilMarker = false;
                currentTimestamp = -1L;
            }
            return null;
        }

        int headerLength = 12 + (first & 0x0f) * 4;
        if (headerLength > length) {
            dropCurrent(true);
            droppingUntilMarker = !marker;
            return null;
        }
        if ((first & 0x10) != 0) {
            if (headerLength + 4 > length) {
                dropCurrent(true);
                droppingUntilMarker = !marker;
                return null;
            }
            int extensionWords = u16(packet, headerLength + 2);
            long extensionBytes = 4L + (long) extensionWords * 4L;
            if (extensionBytes > length - headerLength) {
                dropCurrent(true);
                droppingUntilMarker = !marker;
                return null;
            }
            headerLength += (int) extensionBytes;
        }

        int payloadEnd = length;
        if ((first & 0x20) != 0) {
            int padding = packet[length - 1] & 0xff;
            if (padding == 0 || padding > length - headerLength) {
                dropCurrent(true);
                droppingUntilMarker = !marker;
                return null;
            }
            payloadEnd -= padding;
        }
        if (headerLength >= payloadEnd) {
            dropCurrent(true);
            droppingUntilMarker = !marker;
            return null;
        }

        if (currentTimestamp != -1L && timestamp != currentTimestamp) {
            // A new frame arrived before the previous marker: never join frames.
            dropCurrent(true);
        }
        currentTimestamp = timestamp;

        int payloadOffset = headerLength;
        int payloadLength = payloadEnd - payloadOffset;
        int nalType = packet[payloadOffset] & 0x1f;

        if (nalType >= 1 && nalType <= 23) {
            appendNal(packet, payloadOffset, payloadLength);
        } else if (nalType == NAL_STAP_A) {
            appendStapA(packet, payloadOffset, payloadLength);
        } else if (nalType == NAL_FU_A) {
            appendFuA(packet, payloadOffset, payloadLength);
        } else {
            // Unsupported aggregation/fragmentation mode: drop this access unit.
            dropCurrent(true);
            droppingUntilMarker = true;
        }

        if (droppingUntilMarker) {
            if (marker) {
                droppingUntilMarker = false;
                currentTimestamp = -1L;
            }
            return null;
        }
        if (!marker) return null;
        if (fragmentedNal != null) {
            dropCurrent(true);
            return null;
        }

        byte[] frame = accessUnit.toByteArray();
        boolean keyframe = currentHasIdr;
        byte[] sps = cachedSps;
        byte[] pps = cachedPps;
        if (frame.length == 0) {
            resetCurrent();
            return null;
        }

        if (keyframe && (!currentHasSps || !currentHasPps)) {
            ByteArrayOutputStream withParameters = new ByteArrayOutputStream(frame.length + 256);
            if (!currentHasSps && sps != null) {
                withParameters.write(START_CODE, 0, START_CODE.length);
                withParameters.write(sps, 0, sps.length);
            }
            if (!currentHasPps && pps != null) {
                withParameters.write(START_CODE, 0, START_CODE.length);
                withParameters.write(pps, 0, pps.length);
            }
            withParameters.write(frame, 0, frame.length);
            frame = withParameters.toByteArray();
        }

        AccessUnit result = new AccessUnit(frame, timestamp, keyframe,
                discontinuityPending, sps, pps);
        resetCurrent();
        discontinuityPending = false;
        return result;
    }

    private void appendStapA(byte[] packet, int offset, int length) {
        int end = offset + length;
        int cursor = offset + 1;
        while (cursor + 2 <= end) {
            int nalLength = u16(packet, cursor);
            cursor += 2;
            if (nalLength <= 0 || cursor + nalLength > end) {
                dropCurrent(true);
                droppingUntilMarker = true;
                return;
            }
            appendNal(packet, cursor, nalLength);
            cursor += nalLength;
        }
        if (cursor != end) {
            dropCurrent(true);
            droppingUntilMarker = true;
        }
    }

    private void appendFuA(byte[] packet, int offset, int length) {
        if (length < 3) {
            dropCurrent(true);
            droppingUntilMarker = true;
            return;
        }
        int indicator = packet[offset] & 0xff;
        int header = packet[offset + 1] & 0xff;
        boolean start = (header & 0x80) != 0;
        boolean end = (header & 0x40) != 0;
        int type = header & 0x1f;

        if (start) {
            if (fragmentedNal != null) {
                dropCurrent(true);
                droppingUntilMarker = true;
                return;
            }
            fragmentedNal = new ByteArrayOutputStream(Math.max(256, length * 4));
            fragmentHeader = (indicator & 0xe0) | type;
            fragmentedNal.write(fragmentHeader);
        } else if (fragmentedNal == null) {
            dropCurrent(true);
            droppingUntilMarker = true;
            return;
        }

        if (fragmentedNal.size() + length - 2 > MAX_FRAGMENT_BYTES) {
            dropCurrent(true);
            droppingUntilMarker = true;
            return;
        }
        fragmentedNal.write(packet, offset + 2, length - 2);
        if (end) {
            byte[] nal = fragmentedNal.toByteArray();
            fragmentedNal = null;
            appendNal(nal, 0, nal.length);
        }
    }

    private void appendNal(byte[] bytes, int offset, int length) {
        if (length <= 0) return;
        if (accessUnit.size() + START_CODE.length + length > MAX_ACCESS_UNIT_BYTES) {
            dropCurrent(true);
            droppingUntilMarker = true;
            return;
        }
        int type = bytes[offset] & 0x1f;
        if (type == NAL_SPS) {
            cachedSps = Arrays.copyOfRange(bytes, offset, offset + length);
            currentHasSps = true;
        } else if (type == NAL_PPS) {
            cachedPps = Arrays.copyOfRange(bytes, offset, offset + length);
            currentHasPps = true;
        } else if (type == NAL_IDR) {
            currentHasIdr = true;
        }
        accessUnit.write(START_CODE, 0, START_CODE.length);
        accessUnit.write(bytes, offset, length);
    }

    private void dropCurrent(boolean discontinuity) {
        resetCurrent();
        if (discontinuity) discontinuityPending = true;
    }

    private void resetCurrent() {
        accessUnit.reset();
        fragmentedNal = null;
        currentTimestamp = -1L;
        currentHasSps = false;
        currentHasPps = false;
        currentHasIdr = false;
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (long) (bytes[offset + 3] & 0xff);
    }
}
