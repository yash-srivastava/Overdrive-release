package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AvcSpsInfoTest {

    @Test
    public void parsesAnnexBBaselineLevelFiveSpsWithoutChangingBufferPosition() {
        ByteBuffer csd = ByteBuffer.wrap(new byte[] {
                0, 0, 0, 1, 0x67, 0x42, 0, 0x32, (byte) 0x80
        });
        csd.position(0);

        AvcSpsInfo info = AvcSpsInfo.parse(csd);

        assertNotNull(info);
        assertEquals(66, info.profileIdc);
        assertEquals(50, info.levelIdc);
        assertEquals("Baseline", info.profileName());
        assertEquals("5.0", info.levelName());
        assertEquals(0, csd.position());
    }

    @Test
    public void parsesRawSpsNalWithoutStartCode() {
        AvcSpsInfo info = AvcSpsInfo.parse(ByteBuffer.wrap(new byte[] {
                0x67, 0x64, 0, 0x33, (byte) 0x80
        }));

        assertNotNull(info);
        assertEquals("High", info.profileName());
        assertEquals("5.1", info.levelName());
    }

    @Test
    public void ignoresNonSpsAndTruncatedConfigurationData() {
        assertNull(AvcSpsInfo.parse(ByteBuffer.wrap(new byte[] {0, 0, 1, 0x68, 1, 2, 3})));
        assertNull(AvcSpsInfo.parse(ByteBuffer.wrap(new byte[] {0x67, 0x42, 0})));
        assertNull(AvcSpsInfo.parse(null));
    }
}
