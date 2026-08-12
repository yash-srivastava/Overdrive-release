package com.overdrive.app.storage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

public class RecordingDirectoryRegistryTest {

    @Test
    public void recordingsAreActiveFirstAndIncludeBothLegacyLayouts() {
        File active = new File("/active/recordings");
        File internal = new File("/internal/recordings");

        assertEquals(List.of(
                active.getAbsolutePath(),
                internal.getAbsolutePath(),
                RecordingDirectoryRegistry.LEGACY_RECORDINGS,
                RecordingDirectoryRegistry.LEGACY_BASE
        ), paths(RecordingDirectoryRegistry.recordings(active, internal, null, null)));
    }

    @Test
    public void categoryRegistriesIncludeTheirLegacyRoots() {
        assertEquals(List.of(RecordingDirectoryRegistry.LEGACY_SENTRY),
                paths(RecordingDirectoryRegistry.surveillance(null, null, null, null)));
        assertEquals(List.of(RecordingDirectoryRegistry.LEGACY_PROXIMITY),
                paths(RecordingDirectoryRegistry.proximity(null, null, null, null)));
    }

    @Test
    public void duplicatePathsAreReturnedOnce() {
        File same = new File("/same/recordings");

        List<File> directories = RecordingDirectoryRegistry.recordings(
                same, same, same, same);

        assertEquals(3, directories.size());
        assertEquals(same.getAbsolutePath(), directories.get(0).getAbsolutePath());
    }

    private static List<String> paths(List<File> directories) {
        return directories.stream().map(File::getAbsolutePath).collect(Collectors.toList());
    }
}