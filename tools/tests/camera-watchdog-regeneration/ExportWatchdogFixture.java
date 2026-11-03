import com.overdrive.app.launcher.DaemonLauncher;
import com.overdrive.app.util.ScratchRoot;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exports the real compiled generator's scripts; never loads cameras or JNI. */
public final class ExportWatchdogFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected a new fixture directory");
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        if (root.toString().contains("\n") || root.toString().contains("\r")) {
            throw new IllegalArgumentException("fixture directory contains a line separator");
        }
        Files.createDirectory(root);
        Method pin = ScratchRoot.class.getDeclaredMethod("setResolvedDirForTest", String.class);
        pin.setAccessible(true);
        List<String> metadata = new ArrayList<>();
        try {
            pin.invoke(null, ScratchRoot.LEGACY_DIR);
            for (String generation : Arrays.asList("old", "new")) {
                Path apk = root.resolve(generation).resolve("base.apk");
                Files.createDirectories(apk.getParent());
                Files.createFile(apk);
                String nativeDir = root.resolve(generation + "-native")
                        .resolve("authoritative-location").toString();
                String script = String.join("\n", DaemonLauncher.Companion
                        .buildCamDaemonWatchdogScript(apk.toString(), nativeDir, "/output", ""));
                // Defensive remapping covers both roots, independent of the
                // caller's environment and the pinned source policy.
                script = script.replace(ScratchRoot.LEGACY_DIR, root.toString())
                        .replace(ScratchRoot.ALT_DIR, root.toString());
                if (script.contains(ScratchRoot.LEGACY_DIR) || script.contains(ScratchRoot.ALT_DIR)) {
                    throw new AssertionError("a production scratch path remains in the fixture");
                }
                Files.write(root.resolve(generation + ".sh"),
                        (script + "\n").getBytes(StandardCharsets.UTF_8));
                metadata.add(apk.toString());
                metadata.add(nativeDir);
            }
            Files.write(root.resolve("metadata.txt"), metadata, StandardCharsets.UTF_8);
        } finally {
            pin.invoke(null, new Object[] {null});
        }
    }
}
