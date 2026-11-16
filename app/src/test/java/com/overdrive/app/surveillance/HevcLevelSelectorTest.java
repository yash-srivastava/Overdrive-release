package com.overdrive.app.surveillance;

import android.media.MediaCodecInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HevcLevelSelectorTest {

    @Test
    public void highResolutionSurveillanceStreamRequiresLevelFive() {
        int selected = HevcLevelSelector.selectAdvertisedMainTierLevel(
                2560, 1920, 26, 14_880_000,
                new int[] {
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                });

        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5, selected);
        assertEquals("5", HevcLevelSelector.describe(selected));
    }

    @Test
    public void selectorChoosesLowestAdvertisedLevelThatFits() {
        int selected = HevcLevelSelector.selectAdvertisedMainTierLevel(
                1920, 1080, 30, 8_000_000,
                new int[] {
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
                });

        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4, selected);
    }

    @Test
    public void selectorDoesNotInventAnUnadvertisedLevel() {
        int selected = HevcLevelSelector.selectAdvertisedMainTierLevel(
                2560, 1920, 26, 14_880_000,
                new int[] {
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
                });

        assertEquals(0, selected);
    }
}
