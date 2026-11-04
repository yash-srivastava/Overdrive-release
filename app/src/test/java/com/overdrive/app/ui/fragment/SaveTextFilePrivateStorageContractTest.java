package com.overdrive.app.ui.fragment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression test for run-2 finding
 * {@code webview-bridge-saveTextFile-recovery-key-public-downloads}:
 * AndroidBridge.saveTextFile carried the SafeKeep recovery kit (age private
 * key) into the world-readable public Downloads directory. It must write to
 * app-private storage only.
 */
public class SaveTextFilePrivateStorageContractTest {

    @Test
    public void saveTextFileNeverTouchesPublicExternalStorage() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt")),
                StandardCharsets.UTF_8);
        int saveIdx = s.indexOf("fun saveTextFile(");
        assertTrue(saveIdx > 0);
        String saveBody = s.substring(saveIdx, s.indexOf("fun ", saveIdx + 10) > 0
                ? s.indexOf("fun ", saveIdx + 10) : s.length());
        assertTrue("saveTextFile must call the app-private writer",
                saveBody.contains("writePrivate("));
        assertFalse("saveTextFile must not use the public Downloads dir",
                saveBody.contains("getExternalStoragePublicDirectory"));
        assertTrue("writePrivate must use app-private storage",
                s.contains("getExternalFilesDir") || s.contains("context?.filesDir"));
        // public Downloads writer still exists for non-secret trip exports
        assertTrue(s.contains("getExternalStoragePublicDirectory"));
    }
}
