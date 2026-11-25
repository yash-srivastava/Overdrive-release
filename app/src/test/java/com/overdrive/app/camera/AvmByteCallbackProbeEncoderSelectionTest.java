package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.media.MediaCodecInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Regression coverage for matching the AVM probe's color format to its encoder. */
public class AvmByteCallbackProbeEncoderSelectionTest {

    @Test
    public void prefersSemiPlanarCodecAndKeepsItsName() {
        AvmByteCallbackProbe.EncoderChoice choice = AvmByteCallbackProbe.chooseEncoder(Arrays.asList(
                new AvmByteCallbackProbe.EncoderCandidate("software-flexible",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible}, false),
                new AvmByteCallbackProbe.EncoderCandidate("hardware-semi-planar",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar}, true)));

        assertNotNull(choice);
        assertEquals("hardware-semi-planar", choice.codecName);
        assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                choice.colorFormat);
    }

    @Test
    public void prefersHardwareWhenSeveralCodecsSupportTheSameFormat() {
        AvmByteCallbackProbe.EncoderChoice choice = AvmByteCallbackProbe.chooseEncoder(Arrays.asList(
                new AvmByteCallbackProbe.EncoderCandidate("software",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar}, false),
                new AvmByteCallbackProbe.EncoderCandidate("hardware",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar}, true)));

        assertNotNull(choice);
        assertEquals("hardware", choice.codecName);
        assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                choice.colorFormat);
    }

    @Test
    public void usesFlexibleFormatWhenNoSemiPlanarCodecExists() {
        AvmByteCallbackProbe.EncoderChoice choice = AvmByteCallbackProbe.chooseEncoder(Arrays.asList(
                new AvmByteCallbackProbe.EncoderCandidate("flexible-only",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible}, true)));

        assertNotNull(choice);
        assertEquals("flexible-only", choice.codecName);
        assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                choice.colorFormat);
    }

    @Test
    public void prefersSemiPlanarEvenWhenFlexibleCodecIsHardwareAccelerated() {
        AvmByteCallbackProbe.EncoderChoice choice = AvmByteCallbackProbe.chooseEncoder(Arrays.asList(
                new AvmByteCallbackProbe.EncoderCandidate("hardware-flexible",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible}, true),
                new AvmByteCallbackProbe.EncoderCandidate("software-semi-planar",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar}, false)));

        assertNotNull(choice);
        assertEquals("software-semi-planar", choice.codecName);
        assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                choice.colorFormat);
    }

    @Test
    public void returnsNullWhenNoCodecSupportsTheProbeFormats() {
        assertNull(AvmByteCallbackProbe.chooseEncoder(Arrays.asList(
                new AvmByteCallbackProbe.EncoderCandidate("planar-only",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar}, true),
                new AvmByteCallbackProbe.EncoderCandidate("surface-only",
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface}, false),
                new AvmByteCallbackProbe.EncoderCandidate("no-formats", new int[0], true),
                new AvmByteCallbackProbe.EncoderCandidate("null-formats", null, false))));
        assertNull(AvmByteCallbackProbe.chooseEncoder(Collections.emptyList()));
        assertNull(AvmByteCallbackProbe.chooseEncoder(null));
    }
}
