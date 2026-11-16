package com.overdrive.app.surveillance;

import android.media.MediaCodecInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AvcLevelSelectorTest {

    @Test
    public void surveillanceResolutionSelectsLevelFiveInsteadOfThreePointOne() {
        int selected = AvcLevelSelector.selectAdvertisedBaselineLevel(
                2560, 1920, 26, 14_880_000,
                new int[] {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel5,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51
                });

        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel5, selected);
        assertEquals("5", AvcLevelSelector.describe(selected));
    }

    @Test
    public void selectorChoosesSmallestAdvertisedLevelThatFits() {
        int selected = AvcLevelSelector.selectAdvertisedBaselineLevel(
                1280, 720, 30, 8_000_000,
                new int[] {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel41
                });

        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31, selected);
    }

    @Test
    public void selectorDoesNotRequestAnUnadvertisedOrInsufficientLevel() {
        int selected = AvcLevelSelector.selectAdvertisedBaselineLevel(
                2560, 1920, 26, 14_880_000,
                new int[] {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42
                });

        assertEquals(0, selected);
    }

    @Test
    public void selectorRejectsInvalidInputs() {
        assertEquals(0, AvcLevelSelector.selectAdvertisedBaselineLevel(
                0, 1920, 26, 14_880_000,
                new int[] {MediaCodecInfo.CodecProfileLevel.AVCLevel5}));
    }
}
