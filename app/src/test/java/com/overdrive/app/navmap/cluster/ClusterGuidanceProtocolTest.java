package com.overdrive.app.navmap.cluster;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ClusterGuidanceProtocolTest {
    private static ByteBuffer bytes(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static int field(ByteBuffer b, int index) {
        int table = b.getInt(0);
        int vtable = table - b.getInt(table);
        assertEquals(40, b.getShort(vtable));
        return table + b.getShort(vtable + 4 + index * 2);
    }
    private static String text(ByteBuffer b, int index) {
        int at = field(b, index);
        int string = at + b.getInt(at);
        byte[] value = new byte[b.getInt(string)];
        b.position(string + 4); b.get(value);
        assertEquals(0, b.get());
        return new String(value, StandardCharsets.UTF_8);
    }
    @Test public void decodesOemFieldOrderAndUtf8OffsetsIncludingNonAscii() {
        ClusterGuidanceFrame frame = new ClusterGuidanceFrame(
                "roadsense", 15, 300, "רחוב / 北京 / 🚗", 12340, 845, 0);
        ByteBuffer b = bytes(ClusterGuidanceEncoding.active(frame));
        assertEquals(1, b.getInt(field(b, 0)));
        assertEquals(frame.roadName, text(b, 1));
        assertEquals(300, b.getInt(field(b, 2)));
        assertEquals("", text(b, 3));
        assertEquals(2, b.getInt(field(b, 4)));
        assertEquals(845, b.getInt(field(b, 5)));
        assertEquals(12340, b.getInt(field(b, 6)));
        assertEquals("", text(b, 15));
    }
    @Test public void clearCarriesStoppedStateAndExplicitNegativeSentinels() {
        ByteBuffer b = bytes(ClusterGuidanceEncoding.clear());
        assertEquals(9, b.getInt(field(b, 0)));
        for (int index : new int[]{2, 4, 5, 6, 13, 14}) assertEquals(-1, b.getInt(field(b, index)));
        assertEquals("", text(b, 1));
    }
    @Test public void canStreetUsesUtf16LeWithoutBom() {
        assertArrayEquals(new byte[]{65, 0, (byte) 0xd0, 5}, ClusterGuidanceEncoding.streetBytes("Aא"));
    }
    @Test public void clusterNamespaceKeepsRightUturnDistinctFromNoop() {
        ClusterGuidanceFrame frame = new ClusterGuidanceFrame("roadsense", 12, 20, "", 40, 10, 0);
        assertEquals(10, frame.canIcon());
        assertEquals(19, frame.clusterIcon());
        assertEquals(48, new ClusterGuidanceFrame("roadsense", 4, 0, "", 0, 0, 0).canIcon());
    }
    @Test public void numberedRoundaboutCarriesSeparateExitField() {
        ClusterGuidanceFrame frame = new ClusterGuidanceFrame("roadsense", 26, 80, "", 400, 80, 3);
        ByteBuffer b = bytes(ClusterGuidanceEncoding.active(frame));
        assertEquals(27, frame.canIcon());
        assertEquals(17, b.getInt(field(b, 4)));
        assertEquals(3, b.getInt(field(b, 16)));
    }
    @Test public void streetTruncationPreservesSurrogatePairs() {
        String road = new String(new char[127]).replace('\0', 'A') + "🚗tail";
        ClusterGuidanceFrame frame = new ClusterGuidanceFrame("roadsense", 15, 1, road, -1, -1, 0);
        assertEquals(127, frame.roadName.length());
    }
}
