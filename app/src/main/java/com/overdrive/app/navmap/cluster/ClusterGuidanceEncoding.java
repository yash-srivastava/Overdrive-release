package com.overdrive.app.navmap.cluster;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encoder for the OEM 18-field byd.fbs.naviInfo.NaviInfo table, documented in
 * DashCast's NaviInfo.java. Fixed scalar/string layout avoids another runtime dependency.
 * Strings in this table are UTF-8; the separate instrument CAN road register is UTF-16LE.
 */
public final class ClusterGuidanceEncoding {
    private ClusterGuidanceEncoding() {}

    public static byte[] streetBytes(String street) {
        return street.getBytes(StandardCharsets.UTF_16LE);
    }

    public static byte[] active(ClusterGuidanceFrame frame) {
        return table(1, frame.roadName, frame.distanceMeters, frame.clusterIcon(),
                frame.remainingTimeSeconds, frame.remainingDistanceMeters, frame.roundaboutExit);
    }

    public static byte[] clear() {
        // OEM reset: 0 can mean "unchanged" (icon) or a real 0 m distance.
        return table(9, "", -1, -1, -1, -1, 0);
    }

    private static byte[] table(int state, String street, int distance, int icon,
            int time, int remaining, int exit) {
        final int fields = 18, vtable = 4, table = 44, objectSize = 76;
        ByteBuffer b = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, table);
        b.putShort(vtable, (short) (4 + fields * 2));
        b.putShort(vtable + 2, (short) objectSize);
        b.putInt(table, table - vtable);
        for (int i = 0; i < fields; i++) b.putShort(vtable + 4 + i * 2, (short) (4 + i * 4));
        int[] ints = { state, 0, distance, 0, icon, time, remaining, 0, 0, 0, 0, 0, 0,
                -1, -1, 0, exit, 0 };
        for (int i = 0; i < fields; i++) b.putInt(table + 4 + i * 4, ints[i]);
        int cursor = table + objectSize;
        int[] stringFields = {1, 3, 7, 8, 9, 10, 11, 12, 15};
        for (int field : stringFields) {
            cursor = (cursor + 3) & ~3;
            byte[] bytes = (field == 1 ? street : "").getBytes(StandardCharsets.UTF_8);
            int offsetPosition = table + 4 + field * 4;
            b.putInt(offsetPosition, cursor - offsetPosition);
            b.putInt(cursor, bytes.length);
            cursor += 4;
            b.position(cursor);
            b.put(bytes).put((byte) 0);
            cursor += bytes.length + 1;
        }
        return Arrays.copyOf(b.array(), cursor);
    }
}
