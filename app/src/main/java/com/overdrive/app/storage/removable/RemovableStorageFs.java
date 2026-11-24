package com.overdrive.app.storage.removable;

import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.overdrive.app.camera.DiLink100Platform;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * File calls for the daemons. On DiLink 100 the SD card / USB stick is mounted only for user 10, so
 * user-0 calls on it go through MediaStore; every other path is the plain call, failures included.
 */
public final class RemovableStorageFs {
    private static final String TAG = "RemovableStorageFs";
    /** How long a listing's metadata answers length()/lastModified() without a new query. */
    static final long LISTING_TTL_MS = 3_000;

    private static volatile long lastErrorLogMs = -5_000;

    private RemovableStorageFs() {
    }

    // ---------------------------------------------------------------- routing

    /** True in the DiLink 100 daemons (user 0), where removable storage is reached through MediaStore. */
    public static boolean isActive() {
        return DiLink100Platform.isByd100f() && android.os.Process.myUid() / 100000 == 0;
    }

    public static boolean isRouted(File f) {
        return f != null && isRouted(f.getAbsolutePath());
    }

    public static boolean isRouted(String path) {
        if (path == null || !path.startsWith("/storage/") || path.startsWith("/storage/emulated")
                || !isActive()) {
            return false;
        }
        return MediaStoreBackend.volumeOf(path) != null;
    }

    /** Mount paths ({@code /storage/<uuid>}) of the mounted removable volumes. */
    public static String[] volumeRoots() {
        if (!isActive()) return new String[0];
        MediaStoreBackend.Volume[] vols = MediaStoreBackend.volumes();
        String[] out = new String[vols.length];
        for (int i = 0; i < vols.length; i++) out[i] = vols[i].root;
        return out;
    }

    // ---------------------------------------------------------------- metadata

    /** One stat, or a listing's snapshot. */
    static final class Stat {
        static final Stat MISSING = new Stat(false, false, 0L, 0L);
        final boolean exists, dir;
        final long length, mtime;

        Stat(boolean exists, boolean dir, long length, long mtime) {
            this.exists = exists;
            this.dir = dir;
            this.length = length;
            this.mtime = mtime;
        }
    }

    /** Existence and type only. */
    private static Stat stat(File f) {
        return stat(f, false);
    }

    /** With {@code exact}, size and time come from the file itself (see MediaStoreBackend.stat). */
    private static Stat stat(File f, boolean exact) {
        if (f instanceof ListedFile) {
            Stat snap = ((ListedFile) f).snapshot(exact);
            if (snap != null) return snap;
        }
        try {
            return MediaStoreBackend.stat(f.getAbsolutePath(), exact);
        } catch (IOException e) {
            logError("stat " + f, e);
            return Stat.MISSING;
        }
    }

    public static boolean exists(File f) {
        return isRouted(f) ? stat(f).exists : f.exists();
    }

    /** For deletes of index rows or locks: true only when the volume answered that f is absent. */
    public static boolean isConfirmedAbsent(File f) {
        if (!isRouted(f)) return !f.exists();
        try {
            return !MediaStoreBackend.stat(f.getAbsolutePath(), false).exists;
        } catch (IOException e) {
            logError("stat " + f, e);
            return false;
        }
    }

    public static boolean isDirectory(File f) {
        return isRouted(f) ? stat(f).dir : f.isDirectory();
    }

    public static boolean isFile(File f) {
        if (!isRouted(f)) return f.isFile();
        Stat s = stat(f);
        return s.exists && !s.dir;
    }

    public static long length(File f) {
        return isRouted(f) ? stat(f, true).length : f.length();
    }

    /** "length:mtime" from the listing, without reading the file; lags while a writer holds it open. */
    public static String listingSignature(File f) {
        if (f instanceof ListedFile) {
            Stat snap = ((ListedFile) f).rawSnapshot();
            return snap.length + ":" + snap.mtime;
        }
        return length(f) + ":" + lastModified(f);
    }

    /** A listing entry's mtime as MediaStore recorded it (current once its writer closed it). */
    public static long listedLastModified(File f) {
        return f instanceof ListedFile ? ((ListedFile) f).rawSnapshot().mtime : lastModified(f);
    }

    public static long lastModified(File f) {
        return isRouted(f) ? stat(f, true).mtime : f.lastModified();
    }

    public static boolean canWrite(File f) {
        if (!isRouted(f)) return f.canWrite();
        return MediaStoreBackend.isWritable(f.getAbsolutePath()) && stat(f).exists;
    }

    public static boolean canRead(File f) {
        return isRouted(f) ? stat(f).exists : f.canRead();
    }

    /** MediaStore cannot set modification times: false for routed paths, as on failure. */
    public static boolean setLastModified(File f, long time) {
        return !isRouted(f) && f.setLastModified(time);
    }

    /** exFAT has no permission bits; succeeds for routed paths (as setReadable does there). */
    public static boolean setReadable(File f, boolean readable, boolean ownerOnly) {
        return isRouted(f) ? exists(f) : f.setReadable(readable, ownerOnly);
    }

    public static boolean setWritable(File f, boolean writable, boolean ownerOnly) {
        return isRouted(f) ? exists(f) : f.setWritable(writable, ownerOnly);
    }

    public static boolean setExecutable(File f, boolean executable, boolean ownerOnly) {
        return isRouted(f) ? exists(f) : f.setExecutable(executable, ownerOnly);
    }

    // ---------------------------------------------------------------- space

    private static final int TOTAL = 0, FREE = 1, USABLE = 2;

    public static long getTotalSpace(File f) {
        return isRouted(f) ? fsStat(f.getAbsolutePath(), TOTAL) : f.getTotalSpace();
    }

    public static long getFreeSpace(File f) {
        return isRouted(f) ? fsStat(f.getAbsolutePath(), FREE) : f.getFreeSpace();
    }

    public static long getUsableSpace(File f) {
        return isRouted(f) ? fsStat(f.getAbsolutePath(), USABLE) : f.getUsableSpace();
    }

    /** {@code new StatFs(path).getTotalBytes()}; throws like StatFs for an unreachable path. */
    public static long statFsTotalBytes(String path) {
        if (!isRouted(path)) return new android.os.StatFs(path).getTotalBytes();
        return fsStatOrThrow(path, TOTAL);
    }

    /** {@code new StatFs(path).getAvailableBytes()}. */
    public static long statFsAvailableBytes(String path) {
        if (!isRouted(path)) return new android.os.StatFs(path).getAvailableBytes();
        return fsStatOrThrow(path, USABLE);
    }

    /** {@code new StatFs(path).getFreeBytes()}. */
    public static long statFsFreeBytes(String path) {
        if (!isRouted(path)) return new android.os.StatFs(path).getFreeBytes();
        return fsStatOrThrow(path, FREE);
    }

    private static long fsStat(String path, int which) {
        try {
            return MediaStoreBackend.fsStat(path)[which];
        } catch (IOException e) {
            logError("fsstat " + path, e);
            return 0L;
        }
    }

    private static long fsStatOrThrow(String path, int which) {
        try {
            return MediaStoreBackend.fsStat(path)[which];
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid path: " + path, e);
        }
    }

    // ---------------------------------------------------------------- tree changes

    public static boolean delete(File f) {
        if (!isRouted(f)) return f.delete();
        invalidate(f);
        try {
            return MediaStoreBackend.delete(f.getAbsolutePath());
        } catch (IOException e) {
            logError("delete " + f, e);
            return false;
        }
    }

    /** Like File.renameTo: replaces an existing file, fails (false) across volumes. */
    public static boolean renameTo(File from, File to) {
        boolean a = isRouted(from), b = isRouted(to);
        if (!a && !b) return from.renameTo(to);
        if (a != b) return false;
        invalidate(from);
        invalidate(to);
        try {
            return MediaStoreBackend.rename(from.getAbsolutePath(), to.getAbsolutePath());
        } catch (IOException e) {
            logError("rename " + from + " -> " + to, e);
            return false;
        }
    }

    public static boolean mkdirs(File f) {
        if (!isRouted(f)) return f.mkdirs();
        // File.mkdirs() is false when the directory already existed.
        if (stat(f).dir) return false;
        invalidate(f);
        try {
            return MediaStoreBackend.mkdirs(f.getAbsolutePath());
        } catch (IOException e) {
            logError("mkdirs " + f, e);
            return false;
        }
    }

    /** {@code f.createNewFile()}; on the card check and create are two steps, so racing creators can both win. */
    public static boolean createNewFile(File f) throws IOException {
        if (!isRouted(f)) return f.createNewFile();
        if (stat(f).exists) return false;
        invalidate(f);
        try (ParcelFileDescriptor ignored = MediaStoreBackend.open(f.getAbsolutePath(), "w")) {
            return true;
        }
    }

    public static boolean mkdir(File f) {
        if (!isRouted(f)) return f.mkdir();
        File parent = f.getParentFile();
        if (parent == null || !isDirectory(parent) || exists(f)) return false;
        return mkdirs(f);
    }

    // ---------------------------------------------------------------- listing

    public static String[] list(File dir) {
        if (!isRouted(dir)) return dir.list();
        File[] kids = listFiles(dir);
        if (kids == null) return null;
        String[] names = new String[kids.length];
        for (int i = 0; i < kids.length; i++) names[i] = kids[i].getName();
        return names;
    }

    public static String[] list(File dir, FilenameFilter filter) {
        if (!isRouted(dir)) return dir.list(filter);
        String[] names = list(dir);
        if (names == null || filter == null) return names;
        List<String> out = new ArrayList<>();
        for (String n : names) if (filter.accept(dir, n)) out.add(n);
        return out.toArray(new String[0]);
    }

    public static File[] listFiles(File dir) {
        if (!isRouted(dir)) return dir.listFiles();
        try {
            List<MediaStoreBackend.Entry> entries = MediaStoreBackend.list(dir.getAbsolutePath());
            if (entries == null) return null;
            long at = android.os.SystemClock.elapsedRealtime();
            File[] out = new File[entries.size()];
            for (int i = 0; i < out.length; i++) {
                MediaStoreBackend.Entry e = entries.get(i);
                out[i] = new ListedFile(dir, e.name, e.dir, e.length, e.mtimeMs, e.trusted, at);
            }
            return out;
        } catch (IOException e) {
            logError("list " + dir, e);
            return null;
        }
    }

    public static File[] listFiles(File dir, FilenameFilter filter) {
        if (!isRouted(dir)) return dir.listFiles(filter);
        File[] all = listFiles(dir);
        if (all == null || filter == null) return all;
        List<File> out = new ArrayList<>();
        for (File f : all) if (filter.accept(dir, f.getName())) out.add(f);
        return out.toArray(new File[0]);
    }

    public static File[] listFiles(File dir, FileFilter filter) {
        if (!isRouted(dir)) return dir.listFiles(filter);
        File[] all = listFiles(dir);
        if (all == null || filter == null) return all;
        List<File> out = new ArrayList<>();
        for (File f : all) if (filter.accept(f)) out.add(f);
        return out.toArray(new File[0]);
    }

    // ---------------------------------------------------------------- content

    /** {@code new FileInputStream(f)}. */
    public static FileInputStream openInput(File f) throws FileNotFoundException {
        if (!isRouted(f)) return new FileInputStream(f);
        return new ParcelFileDescriptor.AutoCloseInputStream(openPfd(f, "r"));
    }

    /** {@code new FileOutputStream(f, append)}. */
    public static FileOutputStream openOutput(File f, boolean append) throws FileNotFoundException {
        if (!isRouted(f)) return new FileOutputStream(f, append);
        return new ParcelFileDescriptor.AutoCloseOutputStream(openPfd(f, append ? "wa" : "wt"));
    }

    public static FileOutputStream openOutput(File f) throws FileNotFoundException {
        return openOutput(f, false);
    }

    /** A ParcelFileDescriptor mode ("r", "w", "wt", "wa", "rw", "rwt"); writing modes create the file. */
    public static ParcelFileDescriptor openPfd(File f, String mode) throws FileNotFoundException {
        if (!isRouted(f)) {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
        }
        if (!mode.equals("r")) invalidate(f);
        try {
            return MediaStoreBackend.open(f.getAbsolutePath(), mode);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            FileNotFoundException fnf = new FileNotFoundException(f + ": " + e.getMessage());
            fnf.initCause(e);
            throw fnf;
        }
    }

    /** {@code new MediaMuxer(path, format)}; MediaMuxer dups the descriptor, so ours is closed at once. */
    public static MediaMuxer newMuxer(File f, int format) throws IOException {
        if (!isRouted(f)) return new MediaMuxer(f.getAbsolutePath(), format);
        try (ParcelFileDescriptor pfd = openPfd(f, "rwt")) {
            return new MediaMuxer(pfd.getFileDescriptor(), format);
        }
    }

    /**
     * True when a retriever must read {@code f} through a MediaDataSource: card files on DiLink 100,
     * and any file in a process without an Application on Android 12+, where the FileDescriptor
     * overload calls FileUtils.convertToModernFd and that dereferences the null Application.
     */
    public static boolean needsMediaDataSource(File f) {
        return isRouted(f) || (android.os.Build.VERSION.SDK_INT >= 31 && !hasApplication());
    }

    private static boolean hasApplication() {
        try {
            return Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code retriever.setDataSource(path)}, or a MediaDataSource when {@link #needsMediaDataSource}.
     * release() closes it.
     */
    public static void setDataSource(MediaMetadataRetriever retriever, File f) throws IOException {
        if (!needsMediaDataSource(f)) {
            retriever.setDataSource(f.getAbsolutePath());
            return;
        }
        FdDataSource source = new FdDataSource(openInput(f));
        try {
            retriever.setDataSource(source);
        } catch (RuntimeException e) {
            source.close();
            throw e;
        }
    }

    /** Positional reads over an open descriptor. */
    private static final class FdDataSource extends android.media.MediaDataSource {
        private final FileInputStream in;
        private final java.nio.channels.FileChannel channel;

        FdDataSource(FileInputStream in) {
            this.in = in;
            this.channel = in.getChannel();
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (size == 0) return 0;
            int n = channel.read(ByteBuffer.wrap(buffer, offset, size), position);
            return n < 0 ? -1 : n;
        }

        @Override
        public long getSize() throws IOException {
            return channel.size();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** {@code extractor.setDataSource(f.getPath())}. */
    public static void setDataSource(MediaExtractor extractor, File f) throws IOException {
        if (!isRouted(f)) {
            extractor.setDataSource(f.getAbsolutePath());
            return;
        }
        try (ParcelFileDescriptor pfd = openPfd(f, "r")) {
            extractor.setDataSource(pfd.getFileDescriptor());
        }
    }

    /** {@code Files.move}; on the card the target is replaced, but not atomically. */
    public static void move(File from, File to, java.nio.file.CopyOption... options) throws IOException {
        if (!isRouted(from) && !isRouted(to)) {
            java.nio.file.Files.move(from.toPath(), to.toPath(), options);
            return;
        }
        boolean replace = false;
        for (java.nio.file.CopyOption o : options) {
            if (o == java.nio.file.StandardCopyOption.REPLACE_EXISTING) replace = true;
        }
        if (!replace && exists(to)) throw new java.nio.file.FileAlreadyExistsException(to.getPath());
        if (!renameTo(from, to)) throw new IOException("move failed: " + from + " -> " + to);
    }

    /** {@code Files.readAllBytes(f.toPath())}. */
    public static byte[] readAllBytes(File f) throws IOException {
        if (!isRouted(f)) return java.nio.file.Files.readAllBytes(f.toPath());
        try (FileInputStream in = openInput(f)) {
            long size = in.getChannel().size();
            if (size > Integer.MAX_VALUE - 8) throw new OutOfMemoryError("file too large: " + f);
            byte[] out = new byte[(int) size];
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
            return off == out.length ? out : java.util.Arrays.copyOf(out, off);
        }
    }

    /** {@code Files.copy(from, to, REPLACE_EXISTING)} for any mix of card and plain paths. */
    public static void copy(File from, File to) throws IOException {
        if (from.getAbsolutePath().equals(to.getAbsolutePath())) return;
        byte[] buf = new byte[1 << 20];
        try (FileInputStream in = openInput(from); FileOutputStream out = openOutput(to)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        }
    }

    /** Media-scanner notification; card files are created through MediaStore and already indexed. */
    public static void scanMedia(File f) {
        // intentionally empty: MediaStore is the index
    }

    // ---------------------------------------------------------------- helpers

    /** A change through the adapter makes that entry's listing snapshot obsolete. */
    private static void invalidate(File f) {
        if (f instanceof ListedFile) ((ListedFile) f).invalidate();
    }

    static void logError(String what, Throwable e) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastErrorLogMs < 5_000) return;
        lastErrorLogMs = now;
        Log.w(TAG, what + ": " + e.getMessage());
        com.overdrive.app.logging.DaemonLogger.getInstance(TAG).warn(what + ": " + e.getMessage());
    }

    /** A listFiles entry: answers from the listing for LISTING_TTL_MS and routes its File methods. */
    static final class ListedFile extends File {
        private final Stat snap;
        private final boolean trusted;
        private final long atMs;
        private volatile boolean stale;

        ListedFile(File parent, String name, boolean dir, long length, long mtime, boolean trusted,
                long atMs) {
            super(parent, name);
            this.snap = new Stat(true, dir, length, mtime);
            this.trusted = trusted;
            this.atMs = atMs;
        }

        /** The listing's metadata, while fresh; for size/time only if MediaStore's are trusted. */
        Stat snapshot(boolean exact) {
            if (stale || android.os.SystemClock.elapsedRealtime() - atMs >= LISTING_TTL_MS) return null;
            return exact && !trusted ? null : snap;
        }

        void invalidate() {
            stale = true;
        }

        /** The listing's metadata as recorded, regardless of age or trust. */
        Stat rawSnapshot() {
            return snap;
        }

        @Override
        public boolean exists() {
            return isRouted(this) ? RemovableStorageFs.exists(this) : super.exists();
        }

        @Override
        public boolean isDirectory() {
            return isRouted(this) ? RemovableStorageFs.isDirectory(this) : super.isDirectory();
        }

        @Override
        public boolean isFile() {
            return isRouted(this) ? RemovableStorageFs.isFile(this) : super.isFile();
        }

        @Override
        public long length() {
            return isRouted(this) ? RemovableStorageFs.length(this) : super.length();
        }

        @Override
        public long lastModified() {
            return isRouted(this) ? RemovableStorageFs.lastModified(this) : super.lastModified();
        }

        @Override
        public boolean canWrite() {
            return isRouted(this) ? RemovableStorageFs.canWrite(this) : super.canWrite();
        }

        @Override
        public boolean canRead() {
            return isRouted(this) ? RemovableStorageFs.canRead(this) : super.canRead();
        }

        @Override
        public boolean delete() {
            return isRouted(this) ? RemovableStorageFs.delete(this) : super.delete();
        }

        @Override
        public boolean renameTo(File dest) {
            return isRouted(this) ? RemovableStorageFs.renameTo(this, dest) : super.renameTo(dest);
        }

        @Override
        public boolean mkdirs() {
            return isRouted(this) ? RemovableStorageFs.mkdirs(this) : super.mkdirs();
        }

        @Override
        public boolean mkdir() {
            return isRouted(this) ? RemovableStorageFs.mkdir(this) : super.mkdir();
        }

        @Override
        public boolean setLastModified(long time) {
            return isRouted(this) ? RemovableStorageFs.setLastModified(this, time)
                    : super.setLastModified(time);
        }

        @Override
        public String[] list() {
            return isRouted(this) ? RemovableStorageFs.list(this) : super.list();
        }

        @Override
        public String[] list(FilenameFilter filter) {
            return isRouted(this) ? RemovableStorageFs.list(this, filter) : super.list(filter);
        }

        @Override
        public File[] listFiles() {
            return isRouted(this) ? RemovableStorageFs.listFiles(this) : super.listFiles();
        }

        @Override
        public File[] listFiles(FilenameFilter filter) {
            return isRouted(this) ? RemovableStorageFs.listFiles(this, filter) : super.listFiles(filter);
        }

        @Override
        public File[] listFiles(FileFilter filter) {
            return isRouted(this) ? RemovableStorageFs.listFiles(this, filter) : super.listFiles(filter);
        }
    }
}
