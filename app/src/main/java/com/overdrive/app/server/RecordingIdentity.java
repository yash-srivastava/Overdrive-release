package com.overdrive.app.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class RecordingIdentity {
    final String recordingId;
    final String rootId;
    final String volumeId;
    final String relativePath;

    private RecordingIdentity(String recordingId, String rootId,
                              String volumeId, String relativePath) {
        this.recordingId = recordingId;
        this.rootId = rootId;
        this.volumeId = volumeId;
        this.relativePath = relativePath;
    }

    static RecordingIdentity fromFile(File file) {
        return fromPath(file.getAbsolutePath());
    }

    static RecordingIdentity fromPath(String absolutePath) {
        String path = normalize(absolutePath);
        String volumeId;
        String relativePath;

        final String emulated = "/storage/emulated/0/";
        final String storage = "/storage/";
        final String mediaRw = "/mnt/media_rw/";
        final String mnt = "/mnt/";

        if (path.startsWith(emulated)) {
            volumeId = "internal";
            relativePath = path.substring(emulated.length());
        } else if (path.startsWith(storage)) {
            int slash = path.indexOf('/', storage.length());
            String volume = slash > 0 ? path.substring(storage.length(), slash)
                    : path.substring(storage.length());
            volumeId = "portable:" + volume.toLowerCase(Locale.US);
            relativePath = slash > 0 ? path.substring(slash + 1) : new File(path).getName();
        } else if (path.startsWith(mediaRw)) {
            int slash = path.indexOf('/', mediaRw.length());
            String volume = slash > 0 ? path.substring(mediaRw.length(), slash)
                    : path.substring(mediaRw.length());
            volumeId = "portable:" + volume.toLowerCase(Locale.US);
            relativePath = slash > 0 ? path.substring(slash + 1) : new File(path).getName();
        } else if (path.startsWith(mnt)) {
            int slash = path.indexOf('/', mnt.length());
            String volume = slash > 0 ? path.substring(mnt.length(), slash)
                    : path.substring(mnt.length());
            volumeId = "mount:" + volume.toLowerCase(Locale.US);
            relativePath = slash > 0 ? path.substring(slash + 1) : new File(path).getName();
        } else if (path.startsWith("/data/")) {
            volumeId = "data";
            relativePath = path.substring(1);
        } else {
            File file = new File(path);
            String parent = file.getParent() != null ? file.getParent() : "/";
            volumeId = "path:" + digest(parent, 16);
            relativePath = file.getName();
        }

        int lastSlash = relativePath.lastIndexOf('/');
        String relativeRoot = lastSlash >= 0 ? relativePath.substring(0, lastSlash) : "";
        String rootId = digest(volumeId + "|" + relativeRoot, 24);
        String recordingId = digest(volumeId + "|" + relativePath, 32);
        return new RecordingIdentity(recordingId, rootId, volumeId, relativePath);
    }

    static String rootIdFor(File directory) {
        RecordingIdentity marker = fromPath(new File(directory, ".root").getAbsolutePath());
        return marker.rootId;
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/unknown";
        String normalized = path.replace('\\', '/');
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        return normalized;
    }

    private static String digest(String value, int chars) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format(Locale.US, "%02x", b & 0xff));
            return hex.substring(0, chars);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}