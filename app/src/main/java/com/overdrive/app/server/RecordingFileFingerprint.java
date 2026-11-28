package com.overdrive.app.server;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import java.io.File;

final class RecordingFileFingerprint {
    enum Decision { ADD, REFRESH, UNCHANGED }

    final File file;
    final String recordingId;
    final String absolutePath;
    final long sizeBytes;
    final long mp4Mtime;
    final long sidecarMtime;

    private RecordingFileFingerprint(File file, String recordingId,
                                     String absolutePath, long sizeBytes,
                                     long mp4Mtime, long sidecarMtime) {
        this.file = file;
        this.recordingId = recordingId;
        this.absolutePath = absolutePath;
        this.sizeBytes = sizeBytes;
        this.mp4Mtime = mp4Mtime;
        this.sidecarMtime = sidecarMtime;
    }

    static RecordingFileFingerprint from(File mp4) {
        return from(mp4, RecordingIdentity.fromFile(mp4));
    }

    /** Reuse the identity already computed for this file by the root scan. */
    static RecordingFileFingerprint from(File mp4, RecordingIdentity identity) {
        return fromStamp(mp4, identity, RemovableStorageFs.fileStamp(mp4), sidecarFor(mp4));
    }

    /** The root listing can establish that a sidecar was absent without a per-file stat. */
    static RecordingFileFingerprint fromScan(File mp4, RecordingIdentity identity, File sidecar) {
        RemovableStorageFs.FileStamp stamp = RemovableStorageFs.fileStampIfFile(mp4);
        if (stamp == null || stamp.length <= 0) return null;
        return fromStamp(mp4, identity, stamp, sidecar);
    }

    private static RecordingFileFingerprint fromStamp(File mp4, RecordingIdentity identity,
                                                      RemovableStorageFs.FileStamp stamp, File sidecar) {
        return new RecordingFileFingerprint(
                mp4,
                identity.recordingId,
                mp4.getAbsolutePath(),
                stamp.length,
                stamp.mtime,
                sidecar == null ? 0L : RemovableStorageFs.readableLastModified(sidecar));
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
