package com.overdrive.app.storage.removable;

import android.content.AttributionSource;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.StructStat;
import android.system.StructStatVfs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files on a volume mounted for another user, through that user's MediaProvider over Binder (shell).
 * MediaStore quirks: MIME follows the extension, rename never replaces (target deleted first), dot names
 * are rewritten (refused here), no mkdir (placeholder file), mtimes cannot be set.
 */
final class MediaStoreBackend {
    private static final String SHELL_PKG = "com.android.shell";
    private static final int FORMAT_DIRECTORY = 0x3001;
    private static final String[] STAT_COLUMNS = {"_id", "format", "_size", "date_modified"};
    private static final String[] LIST_COLUMNS = {"_display_name", "format", "_size", "date_modified", "mime_type"};
    private static final long VOLUMES_TTL_MS = 3_000;
    private static final long FSSTAT_TTL_MS = 2_000;

    private MediaStoreBackend() {
    }

    // ---------------------------------------------------------------- volumes

    /** A mounted public volume: its path as apps see it and its MediaStore volume name. */
    static final class Volume {
        final String root;       // /storage/<fsUuid>
        final String name;       // <fsuuid> lower case
        final int userId;        // user the volume is mounted for
        final boolean writable;

        Volume(String root, String name, int userId, boolean writable) {
            this.root = root;
            this.name = name;
            this.userId = userId;
            this.writable = writable;
        }
    }

    private static volatile Volume[] volumes = new Volume[0];
    private static volatile long volumesAtMs = -VOLUMES_TTL_MS;

    /** Mounted public volumes, from the storage service (cached briefly). */
    static Volume[] volumes() {
        long now = SystemClock.elapsedRealtime();
        if (now - volumesAtMs < VOLUMES_TTL_MS) return volumes;
        synchronized (MediaStoreBackend.class) {
            if (now - volumesAtMs < VOLUMES_TTL_MS) return volumes;
            Volume[] fresh;
            try {
                fresh = queryVolumes();
            } catch (Throwable t) {
                RemovableStorageFs.logError("volume query failed", t);
                fresh = new Volume[0];
            }
            volumes = fresh;
            volumesAtMs = SystemClock.elapsedRealtime();
            return fresh;
        }
    }

    private static Volume[] queryVolumes() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "mount");
        Object sm = Class.forName("android.os.storage.IStorageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object[] infos = (Object[]) sm.getClass().getMethod("getVolumes", int.class).invoke(sm, 0);
        List<Volume> out = new ArrayList<>();
        for (Object v : infos) {
            Class<?> c = v.getClass();
            int type = c.getField("type").getInt(v);
            int state = c.getField("state").getInt(v);
            String uuid = (String) c.getField("fsUuid").get(v);
            int user = c.getField("mountUserId").getInt(v);
            // VolumeInfo.TYPE_PUBLIC = 0; STATE_MOUNTED = 2, STATE_MOUNTED_READ_ONLY = 3.
            if (type != 0 || uuid == null || (state != 2 && state != 3)) continue;
            // Mounted for this process's own user: reachable by path.
            if (user == Process.myUid() / 100000) continue;
            out.add(new Volume("/storage/" + uuid, uuid.toLowerCase(Locale.ROOT), user, state == 2));
        }
        return out.toArray(new Volume[0]);
    }

    static Volume volumeOf(String path) {
        for (Volume v : volumes()) {
            if (path.equals(v.root) || path.startsWith(v.root + "/")) return v;
        }
        return null;
    }

    // ---------------------------------------------------------------- MediaProvider connection

    private static final class Conn {
        final Object provider;
        final AttributionSource attr;
        final Method insert, query, update, delete, openFile;

        Conn(int userId) throws Exception {
            Object ams = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Object holder = ams.getClass().getMethod("getContentProviderExternal",
                    String.class, int.class, IBinder.class, String.class)
                    .invoke(ams, "media", userId, new Binder(), "RemovableStorageFs");
            if (holder == null) throw new IOException("media provider not available for user " + userId);
            provider = holder.getClass().getField("provider").get(holder);
            attr = new AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PKG).build();
            Method i = null, q = null, u = null, d = null, o = null;
            for (Method m : provider.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || p[0] != AttributionSource.class) continue;
                switch (m.getName()) {
                    case "insert": if (p.length == 4) i = m; break;
                    case "query": if (p.length == 5) q = m; break;
                    case "update": if (p.length == 4) u = m; break;
                    case "delete": if (p.length == 3) d = m; break;
                    case "openFile": if (p.length == 4) o = m; break;
                    default:
                }
            }
            if (i == null || q == null || u == null || d == null || o == null) {
                throw new IOException("IContentProvider signatures not found");
            }
            insert = i;
            query = q;
            update = u;
            delete = d;
            openFile = o;
        }
    }

    private static final ConcurrentHashMap<Integer, Conn> CONNS = new ConcurrentHashMap<>();

    private interface Call<T> {
        T run(Conn c) throws Exception;
    }

    /** Runs a provider call, reconnecting once if MediaProvider restarted. */
    private static <T> T call(Volume v, Call<T> body) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Conn c;
            try {
                c = CONNS.get(v.userId);
                if (c == null) {
                    c = new Conn(v.userId);
                    CONNS.put(v.userId, c);
                }
                return body.run(c);
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                if (attempt == 0 && cause instanceof android.os.DeadObjectException) {
                    CONNS.remove(v.userId);
                    continue;
                }
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException(cause.toString(), cause);
            }
        }
    }

    private static Uri filesUri(Volume v) {
        return Uri.parse("content://media/" + v.name + "/file");
    }

    private static Uri itemUri(Volume v, long id) {
        return Uri.parse("content://media/" + v.name + "/file/" + id);
    }

    private static Cursor query(Conn c, Volume v, String[] cols, String sel, String[] args, int limit)
            throws Exception {
        Bundle q = new Bundle();
        q.putString("android:query-arg-sql-selection", sel);
        q.putStringArray("android:query-arg-sql-selection-args", args);
        q.putInt("android:query-arg-match-pending", 1);  // MediaStore.MATCH_INCLUDE
        if (limit > 0) q.putString("android:query-arg-sql-limit", String.valueOf(limit));
        return (Cursor) c.query.invoke(c.provider, c.attr, filesUri(v), cols, q, null);
    }

    // ---------------------------------------------------------------- paths

    /** Path relative to the volume root ("" for the root itself). */
    private static String rel(Volume v, String path) {
        return path.length() <= v.root.length() ? "" : path.substring(v.root.length() + 1);
    }

    /** MediaStore relative_path of the entries inside directory {@code dirPath}. */
    private static String childrenRelativePath(Volume v, String dirPath) {
        String r = rel(v, dirPath);
        return r.isEmpty() ? "/" : r + "/";
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- metadata

    /**
     * MediaStore's _size and date_modified are stale until its own descriptor closes or a rename (a
     * muxer's dup or a crashed writer leaves them stale), so {@code exact} reads them with fstat.
     */
    static RemovableStorageFs.Stat stat(String path, boolean exact) throws IOException {
        Volume v = volumeOf(path);
        if (v == null) return RemovableStorageFs.Stat.MISSING;
        if (path.equals(v.root)) return new RemovableStorageFs.Stat(true, true, 0L, 0L);
        Row row = row(v, path);
        if (row != null) {
            if (!row.dir && exact) refreshFromFd(v, row);
            return new RemovableStorageFs.Stat(true, row.dir, row.dir ? 0L : row.size, row.mtimeMs);
        }
        // A folder MediaStore has no row for, but which holds indexed files.
        return hasChildren(v, path) ? new RemovableStorageFs.Stat(true, true, 0L, 0L)
                : RemovableStorageFs.Stat.MISSING;
    }

    private static final class Row {
        final long id;
        final boolean dir;
        long size, mtimeMs;

        Row(long id, boolean dir, long size, long mtimeMs) {
            this.id = id;
            this.dir = dir;
            this.size = size;
            this.mtimeMs = mtimeMs;
        }
    }

    private static Row row(Volume v, String path) throws IOException {
        return call(v, c -> {
            try (Cursor cur = query(c, v, STAT_COLUMNS, "_data=?", new String[]{path}, 1)) {
                if (cur == null || !cur.moveToFirst()) return null;
                return new Row(cur.getLong(0), cur.getInt(1) == FORMAT_DIRECTORY,
                        cur.isNull(2) ? 0L : cur.getLong(2), cur.getLong(3) * 1000L);
            }
        });
    }

    /** MediaStore updates _size on close; for a file still being written, ask the file. */
    private static void refreshFromFd(Volume v, Row row) {
        try (ParcelFileDescriptor pfd = call(v, c -> (ParcelFileDescriptor)
                c.openFile.invoke(c.provider, c.attr, itemUri(v, row.id), "r", null))) {
            StructStat st = Os.fstat(pfd.getFileDescriptor());
            row.size = st.st_size;
            row.mtimeMs = st.st_mtime * 1000L;
        } catch (Exception ignored) {
            // keep the database values
        }
    }

    private static boolean hasChildren(Volume v, String dirPath) throws IOException {
        String childRel = childrenRelativePath(v, dirPath);
        return call(v, c -> {
            try (Cursor cur = query(c, v, new String[]{"_id"}, "relative_path=?", new String[]{childRel}, 1)) {
                return cur != null && cur.moveToFirst();
            }
        });
    }

    /** Entries of a directory with their metadata, or null if it isn't one. */
    static List<Entry> list(String dirPath) throws IOException {
        Volume v = volumeOf(dirPath);
        if (v == null) return null;
        if (!stat(dirPath, false).dir) return null;
        String childRel = childrenRelativePath(v, dirPath);
        return call(v, c -> {
            List<Entry> out = new ArrayList<>();
            try (Cursor cur = query(c, v, LIST_COLUMNS, "relative_path=?", new String[]{childRel}, 0)) {
                while (cur != null && cur.moveToNext()) {
                    String name = cur.getString(0);
                    if (name == null || name.isEmpty()) continue;
                    boolean dir = cur.getInt(1) == FORMAT_DIRECTORY;
                    // Media files are rescanned on the rename that finalizes them, so their
                    // database size and time are current. Temp files, journals and other
                    // generic data may still be open elsewhere: their metadata isn't trusted.
                    String mime = cur.getString(4);
                    boolean trusted = dir || (mime != null && !mime.equals("application/octet-stream")
                            && !cur.isNull(2) && cur.getLong(2) > 0);
                    out.add(new Entry(name, dir, dir || cur.isNull(2) ? 0L : cur.getLong(2),
                            cur.getLong(3) * 1000L, trusted));
                }
            }
            return out;
        });
    }

    static final class Entry {
        final String name;
        final boolean dir, trusted;
        final long length, mtimeMs;

        Entry(String name, boolean dir, long length, long mtimeMs, boolean trusted) {
            this.name = name;
            this.dir = dir;
            this.trusted = trusted;
            this.length = length;
            this.mtimeMs = mtimeMs;
        }
    }

    // ---------------------------------------------------------------- content

    /** Opens (creating it when the mode writes and it is missing) with a PFD mode. */
    static ParcelFileDescriptor open(String path, String mode) throws IOException {
        Volume v = volumeOf(path);
        if (v == null) throw new FileNotFoundException(path + ": volume not mounted");
        Row row = row(v, path);
        if (row != null && row.dir) throw new FileNotFoundException(path + ": is a directory");
        long id;
        if (row != null) {
            id = row.id;
        } else if (mode.contains("w")) {
            if (!v.writable) throw new FileNotFoundException(path + ": read-only volume");
            id = insert(v, path);
        } else {
            throw new FileNotFoundException(path + ": no such file");
        }
        ParcelFileDescriptor pfd = call(v, c -> (ParcelFileDescriptor)
                c.openFile.invoke(c.provider, c.attr, itemUri(v, id), mode, null));
        if (pfd == null) throw new FileNotFoundException(path + ": no descriptor");
        return pfd;
    }

    /** Inserts a row for a new file (its folder is created as needed); returns its id. */
    private static long insert(Volume v, String path) throws IOException {
        File f = new File(path);
        String name = f.getName();
        if (name.startsWith(".")) {
            throw new FileNotFoundException(path + ": names starting with '.' are not supported here");
        }
        String parent = f.getParent();
        if (parent == null || volumeOf(parent) != v || parent.equals(v.root)) {
            throw new FileNotFoundException(path + ": files must be inside a folder on the volume");
        }
        ContentValues cv = new ContentValues();
        cv.put("_display_name", name);
        cv.put("relative_path", childrenRelativePath(v, parent));
        cv.put("mime_type", mimeFor(name));
        Uri uri = call(v, c -> (Uri) c.insert.invoke(c.provider, c.attr, filesUri(v), cv, null));
        if (uri == null) throw new FileNotFoundException(path + ": insert refused");
        long id = Long.parseLong(uri.getLastPathSegment());
        Row check = row(v, path);
        if (check == null || check.id != id) {
            // MediaStore placed it under another name: undo, never write elsewhere.
            deleteRow(v, id);
            throw new FileNotFoundException(path + ": MediaStore changed the name");
        }
        return id;
    }

    // ---------------------------------------------------------------- tree changes

    /** Like File.delete(): a file, or an empty folder. */
    static boolean delete(String path) throws IOException {
        Volume v = volumeOf(path);
        if (v == null || path.equals(v.root)) return false;
        Row row = row(v, path);
        if (row == null) return false;
        if (row.dir && hasChildren(v, path)) return false;
        return deleteRow(v, row.id);
    }

    private static boolean deleteRow(Volume v, long id) throws IOException {
        Integer n = call(v, c -> (Integer) c.delete.invoke(c.provider, c.attr, itemUri(v, id), null));
        boolean deleted = n != null && n == 1;
        // Free space changed: a reaper loop re-reads it after each delete.
        if (deleted) FSSTAT.remove(v.root);
        return deleted;
    }

    /** Like rename(2) on one volume: replaces an existing file at the target. */
    static boolean rename(String from, String to) throws IOException {
        Volume v = volumeOf(from);
        if (v == null || volumeOf(to) != v || from.equals(to)) return false;
        File target = new File(to);
        if (target.getName().startsWith(".") || target.getParent() == null
                || target.getParent().equals(v.root)) {
            return false;
        }
        Row src = row(v, from);
        if (src == null) return false;
        Row dst = row(v, to);
        if (dst != null) {
            if (dst.dir || src.dir) return false;
            if (!deleteRow(v, dst.id)) return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("_display_name", target.getName());
        cv.put("relative_path", childrenRelativePath(v, target.getParent()));
        if (!src.dir) cv.put("mime_type", mimeFor(target.getName()));
        Integer n = call(v, c -> (Integer) c.update.invoke(c.provider, c.attr, itemUri(v, src.id), cv, null));
        if (n == null || n != 1) return false;
        Row moved = row(v, to);
        return moved != null && moved.id == src.id;
    }

    /** Creates the folder and any missing parents. True when it exists afterwards. */
    static boolean mkdirs(String path) throws IOException {
        Volume v = volumeOf(path);
        if (v == null || !v.writable) return false;
        RemovableStorageFs.Stat s = stat(path, false);
        if (s.exists) return s.dir;
        if (path.equals(v.root) || new File(path).getName().startsWith(".")) return false;
        // No mkdir in MediaStore: create and remove a placeholder; the folder stays.
        String placeholder = path + "/od_mkdir_" + System.nanoTime() + ".bin";
        try (ParcelFileDescriptor ignored = open(placeholder, "w")) {
            // created
        }
        Row row = row(v, placeholder);
        if (row != null) deleteRow(v, row.id);
        return stat(path, false).dir;
    }

    // ---------------------------------------------------------------- space

    private static final ConcurrentHashMap<String, long[]> FSSTAT = new ConcurrentHashMap<>();

    /** {total, free, usable} bytes of the volume holding {@code path}. */
    static long[] fsStat(String path) throws IOException {
        Volume v = volumeOf(path);
        if (v == null) throw new FileNotFoundException(path + ": volume not mounted");
        long[] cached = FSSTAT.get(v.root);
        if (cached != null && SystemClock.elapsedRealtime() - cached[3] < FSSTAT_TTL_MS) return cached;
        // statvfs of any open file on the volume reports the volume; a row can be stale, so try a few.
        List<Long> ids = call(v, c -> {
            List<Long> out = new ArrayList<>();
            try (Cursor cur = query(c, v, new String[]{"_id"}, "format<>?",
                    new String[]{String.valueOf(FORMAT_DIRECTORY)}, 5)) {
                while (cur != null && cur.moveToNext()) out.add(cur.getLong(0));
            }
            return out;
        });
        for (long id : ids) {
            try (ParcelFileDescriptor pfd = call(v, c -> (ParcelFileDescriptor)
                    c.openFile.invoke(c.provider, c.attr, itemUri(v, id), "r", null))) {
                if (pfd != null) return statVfs(v, pfd);
            } catch (IOException e) {
                // stale row: try the next one
            }
        }
        try (ParcelFileDescriptor pfd = open(v.root + "/Overdrive/od_space_probe.bin", "w")) {
            return statVfs(v, pfd);
        }
    }

    private static long[] statVfs(Volume v, ParcelFileDescriptor pfd) throws IOException {
        try {
            StructStatVfs s = Os.fstatvfs(pfd.getFileDescriptor());
            long[] out = {s.f_blocks * s.f_frsize, s.f_bfree * s.f_frsize, s.f_bavail * s.f_frsize,
                    SystemClock.elapsedRealtime()};
            FSSTAT.put(v.root, out);
            return out;
        } catch (Exception e) {
            throw new IOException("fstatvfs " + v.root + ": " + e.getMessage(), e);
        }
    }

    static boolean isWritable(String path) {
        Volume v = volumeOf(path);
        return v != null && v.writable;
    }

    private static Throwable unwrap(Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
        return e;
    }
}
