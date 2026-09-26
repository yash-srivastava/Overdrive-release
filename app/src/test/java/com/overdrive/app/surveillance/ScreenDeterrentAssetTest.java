package com.overdrive.app.surveillance;
import com.overdrive.app.util.ScratchPaths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScreenDeterrentAssetTest {

    @Test
    public void onlyFinalAssetsInsideTheManagedDirectoryAreAccepted() {
        assertTrue(ScreenDeterrentAsset.isAllowedPath(
                ScratchPaths.path(".overdrive/screen_deterrent_asset.123.mp4")));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                ScratchPaths.path(".overdrive/screen_deterrent_asset.upload.tmp")));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                ScratchPaths.path(".overdrive/SCREEN_DETERRENT_ASSET.123.mp4")));
        assertFalse(ScreenDeterrentAsset.isAllowedPath(
                ScratchPaths.path(".overdrive/../secret.mp4")));
        assertFalse(ScreenDeterrentAsset.isAllowedPath("/etc/passwd"));
    }
}
