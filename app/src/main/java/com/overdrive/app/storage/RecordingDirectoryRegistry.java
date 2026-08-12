package com.overdrive.app.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RecordingDirectoryRegistry {
    static final String LEGACY_BASE =
            "/storage/emulated/0/Android/data/com.overdrive.app/files";
    static final String LEGACY_RECORDINGS = LEGACY_BASE + "/recordings";
    static final String LEGACY_SENTRY = LEGACY_BASE + "/sentry_events";
    static final String LEGACY_PROXIMITY = LEGACY_BASE + "/proximity_events";

    private RecordingDirectoryRegistry() {}

    static List<File> recordings(File active, File internal, File sd, File usb) {
        return unique(active, internal, sd, usb,
                new File(LEGACY_RECORDINGS), new File(LEGACY_BASE));
    }

    static List<File> surveillance(File active, File internal, File sd, File usb) {
        return unique(active, internal, sd, usb, new File(LEGACY_SENTRY));
    }

    static List<File> proximity(File active, File internal, File sd, File usb) {
        return unique(active, internal, sd, usb, new File(LEGACY_PROXIMITY));
    }

    private static List<File> unique(File... candidates) {
        List<File> directories = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File candidate : candidates) {
            if (candidate == null) continue;
            if (seen.add(candidate.getAbsolutePath())) directories.add(candidate);
        }
        return directories;
    }
}