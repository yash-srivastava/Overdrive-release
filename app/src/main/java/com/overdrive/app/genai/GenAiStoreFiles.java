package com.overdrive.app.genai;

import com.overdrive.app.util.ScratchRoot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/** Shared on-disk JSON lifecycle. Callers own synchronization and schema validation. */
final class GenAiStoreFiles {
    private final File home;
    private final File file;
    private final File tmp;
    private final File backup;

    static GenAiStoreFiles forName(String name) {
        return new GenAiStoreFiles(new File(System.getProperty(
                GenAiInsights.HOME_PROPERTY, ScratchRoot.path(".genai"))), name);
    }

    GenAiStoreFiles(File home, String name) {
        this.home = home;
        file = new File(home, name);
        tmp = new File(home, name + ".tmp");
        backup = new File(home, name + ".bak");
    }

    File file() {
        return file;
    }

    JSONObject load(int maxBytes) {
        makeOwnerOnly(home, true);
        makeOwnerOnly(file, false);
        makeOwnerOnly(backup, false);
        JSONObject root = GenAiStoreReader.read(file, maxBytes);
        return root != null ? root : GenAiStoreReader.read(backup, maxBytes);
    }

    /** Retain each caller's write limit and post-rename permission policy. */
    boolean save(Callable<JSONObject> contents, int maxBytes, boolean refreshPermissions) {
        if (!home.exists() && !home.mkdirs()) return false;
        makeOwnerOnly(home, true);
        try {
            byte[] bytes = contents.call().toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes) return false;
            if (tmp.exists() && !tmp.delete()) return false;
            if (!tmp.createNewFile()) return false;
            makeOwnerOnly(tmp, false);
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (backup.exists() && !backup.delete()) return false;
            if (file.exists() && !file.renameTo(backup)) return false;
            if (!tmp.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file);
                return false;
            }
            if (refreshPermissions) {
                makeOwnerOnly(file, false);
                makeOwnerOnly(backup, false);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void makeOwnerOnly(File file, boolean directory) {
        if (file == null || !file.exists()) return;
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) file.setExecutable(true, true);
    }
}
