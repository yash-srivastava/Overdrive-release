package com.overdrive.app.server;

import java.io.File;

final class RecordingFileFingerprint {
    enum Decision { ADD, REFRESH, UNCHANGED }

    final File file;
    final String recordingId;
    final String rootId;
    final String absolutePath;
    final long sizeBytes;
    final long mp4Mtime;
    final long sidecarMtime;

    private RecordingFileFingerprint(File file, String recordingId, String rootId,
                                     String absolutePath, long sizeBytes,
                                     long mp4Mtime, long sidecarMtime) {
        this.file = file;
        this.recordingId = recordingId;
        this.rootId = rootId;
        this.absolutePath = absolutePath;
        this.sizeBytes = sizeBytes;
        this.mp4Mtime = mp4Mtime;
        this.sidecarMtime = sidecarMtime;
    }

    static RecordingFileFingerprint from(File mp4) {
        File sidecar = sidecarFor(mp4);
        RecordingIdentity identity = RecordingIdentity.fromFile(mp4);
        return new RecordingFileFingerprint(
                mp4,
            identity.recordingId,
            identity.rootId,
                mp4.getAbsolutePath(),
                mp4.length(),
                mp4.lastModified(),
            sidecar.canRead() ? sidecar.lastModified() : 0L);
    }

    static File sidecarFor(File mp4) {
        String name = mp4.getName();
        String stem = name.endsWith(".mp4")
                ? name.substring(0, name.length() - ".mp4".length())
                : name;
        return new File(mp4.getParentFile(), stem + ".json");
    }

    boolean matches(String indexedPath, long indexedSizeBytes,
                    long indexedMp4Mtime, long indexedSidecarMtime) {
        return absolutePath.equals(indexedPath)
                && sizeBytes == indexedSizeBytes
                && mp4Mtime == indexedMp4Mtime
                && sidecarMtime == indexedSidecarMtime;
    }

    Decision decisionAgainst(String indexedPath, long indexedSizeBytes,
                             long indexedMp4Mtime, long indexedSidecarMtime) {
        if (indexedPath == null) return Decision.ADD;
        return matches(indexedPath, indexedSizeBytes, indexedMp4Mtime, indexedSidecarMtime)
                ? Decision.UNCHANGED : Decision.REFRESH;
    }
}