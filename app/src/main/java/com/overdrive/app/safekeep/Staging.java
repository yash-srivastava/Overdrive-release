package com.overdrive.app.safekeep;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.config.ConfigBackupService;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.trips.TripAnalyticsManager;
import com.overdrive.app.trips.TripDatabase;
import com.overdrive.app.trips.TripRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.zip.GZIPOutputStream;

/**
 * Materialises DB-derived objects (trip rows, GPX, settings) into immutable staging files so
 * a resumed upload re-sends exactly the same bytes. temp → fsync → atomic rename.
 */
public final class Staging {

    public static final File DIR = new File(com.overdrive.app.util.ScratchRoot.path(".safekeep/staging"));

    /** Sections never sent off the car: secrets, tunnel material and live runtime state. */
    static final String[] CLOUD_DENY_SECTIONS = {
            "bydCloud", "navMap", "telegram", "genAi", SafeKeepSecrets.SECTION, "auth", "pinLock",
            "cloudflared", "zrok", "tailscale", "frp", "singbox", "vpn", "hotspotState"};
    /** Counters and timestamps that change on their own; ignored when deciding "settings changed". */
    private static final java.util.Set<String> VOLATILE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "dataUsedBytes", "updatedAt", "lastUpdated", "lastSeen", "lastSeenAt"));

    private Staging() {}

    /** Returns the staged file for an item, creating it on first use. Null if the source is gone. */
    static File materialize(Ledger.Item it) throws Exception {
        File f = new File(DIR, it.id + "-" + it.generation + ext(it));
        if (RemovableStorageFs.isFile(f) && RemovableStorageFs.length(f) > 0) return f;
        byte[] data;
        Naming.Kind kind = Naming.Kind.parse(it.kind);
        JSONObject meta = it.meta();
        if (kind == Naming.Kind.TRIP_ROW) {
            JSONObject row = tripRow(meta.optLong("tripId", -1));
            if (row == null) return null;
            data = new JSONObject().put("v", 1).put("type", "overdrive.trip")
                    .put("startTime", meta.optLong("startTime", 0)).put("trip", row)
                    .toString().getBytes(StandardCharsets.UTF_8);
            // What this generation really carries: change detection compares against it.
            meta.put("rowHash", Enqueuer.sha256(row.toString().getBytes(StandardCharsets.UTF_8)));
            it.metaJson = meta.toString();
        } else if (kind == Naming.Kind.TRIP_GPX) {
            TripRecord t = trip(meta.optLong("tripId", -1));
            if (t == null) return null;
            File tel = t.telemetryFilePath == null ? null : new File(t.telemetryFilePath);
            data = TripExporter.gpx(t, tel).getBytes(StandardCharsets.UTF_8);
        } else if (kind == Naming.Kind.SETTINGS) {
            JSONObject bundle = settingsForCloud();
            // The hash of what is really sent: a change made after queueing is not recorded as uploaded.
            meta.put("hash", settingsHash(bundle));
            it.metaJson = meta.toString();
            data = gzip(bundle.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            return null;
        }
        writeAtomic(f, data);
        return f;
    }

    static void discard(Ledger.Item it) {
        File f = new File(DIR, it.id + "-" + it.generation + ext(it));
        if (RemovableStorageFs.exists(f) && !RemovableStorageFs.delete(f)) f.deleteOnExit();
    }

    /** Removes staging files not referenced by a live item id (boot hygiene). */
    static void sweep(java.util.Set<Long> liveIds) {
        File[] files = RemovableStorageFs.listFiles(DIR);
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            int dash = n.indexOf('-');
            if (dash <= 0) {
                if (!RemovableStorageFs.delete(f)) f.deleteOnExit();
                continue;
            }
            try {
                if (!liveIds.contains(Long.parseLong(n.substring(0, dash)))) {
                    if (!RemovableStorageFs.delete(f)) f.deleteOnExit();
                }
            } catch (NumberFormatException e) {
                if (!RemovableStorageFs.delete(f)) f.deleteOnExit();
            }
        }
    }

    private static String ext(Ledger.Item it) {
        Naming.Kind k = Naming.Kind.parse(it.kind);
        if (k == Naming.Kind.TRIP_ROW) return ".json";
        if (k == Naming.Kind.TRIP_GPX) return ".gpx";
        return ".json.gz";
    }

    static TripDatabase tripDb() {
        TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
        return tam == null ? null : tam.getDatabase();
    }

    static TripRecord trip(long id) {
        TripDatabase db = tripDb();
        return (db == null || id <= 0) ? null : db.getTrip(id);
    }

    static JSONObject tripRow(long id) {
        TripDatabase db = tripDb();
        return (db == null || id <= 0) ? null : db.exportTripJson(id);
    }

    /** The settings bundle minus every secret: credential sections, the DID, and any ENC: value. */
    static JSONObject settingsForCloud() throws Exception {
        JSONObject bundle = ConfigBackupService.buildBundle(appVersion(), android.os.Build.MODEL,
                System.currentTimeMillis());
        stripForCloud(bundle);
        JSONObject manifest = bundle.optJSONObject("manifest");
        if (manifest != null) manifest.put("cloudCopy", true).put("secretsStripped", true);
        return bundle;
    }

    /**
     * Removes everything that must never leave the car or come back from storage: the DID,
     * secret and tunnel sections, runtime state, ownership keys and any ENC: value. Used on
     * upload and again on restore, so a planted bundle can't install auth or tunnel settings.
     */
    static void stripForCloud(JSONObject bundle) {
        bundle.remove("did");
        JSONObject settings = bundle.optJSONObject("settings");
        JSONObject unified = settings == null ? null : settings.optJSONObject("unified");
        if (unified == null) return;
        for (String s : CLOUD_DENY_SECTIONS) unified.remove(s);
        for (String s : ConfigBackupService.credentialSectionNames()) unified.remove(s);
        unified.remove(SafeKeepConfig.SECTION);
        // Same reasoning as the deny list above, for the two flags that arm arbitrary
        // shell: whoever can write to the storage could plant them, and a restore must
        // not grant a capability the uploader never had.
        ConfigBackupService.stripShellGates(unified);
        JSONObject hotspot = unified.optJSONObject("hotspot");
        if (hotspot != null) hotspot.remove("password");
        JSONObject community = unified.optJSONObject("community");
        if (community != null) community.remove("publisherId");
        stripEncrypted(unified, 0);
    }

    /** Hash of the user's settings only (volatile counters and timestamps left out). */
    static String settingsHash(JSONObject bundle) throws Exception {
        JSONObject settings = bundle.optJSONObject("settings");
        JSONObject copy = new JSONObject(settings == null ? "{}" : settings.toString());
        dropVolatile(copy, 0);
        return Enqueuer.sha256(copy.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void dropVolatile(Object node, int depth) {
        if (depth > 8) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : VOLATILE_KEYS) o.remove(k);
            Iterator<String> it = o.keys();
            while (it.hasNext()) dropVolatile(o.opt(it.next()), depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) dropVolatile(a.opt(i), depth + 1);
        }
    }

    private static void stripEncrypted(Object node, int depth) {
        if (depth > 8) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            java.util.List<String> drop = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if (v instanceof String && ((String) v).startsWith("ENC:")) drop.add(k);
                else stripEncrypted(v, depth + 1);
            }
            for (String k : drop) o.remove(k);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) stripEncrypted(a.opt(i), depth + 1);
        }
    }

    static String appVersion() {
        try {
            return com.overdrive.app.BuildConfig.VERSION_NAME;
        } catch (Throwable t) {
            return "";
        }
    }

    static byte[] gzip(byte[] in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.max(64, in.length / 3));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(in);
        }
        return bos.toByteArray();
    }

    static void writeAtomic(File f, byte[] data) throws Exception {
        File dir = f.getParentFile();
        if (dir != null && !RemovableStorageFs.isDirectory(dir) && !RemovableStorageFs.mkdirs(dir) && !RemovableStorageFs.isDirectory(dir)) {
            throw new java.io.IOException("Cannot create " + dir);
        }
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream fos = RemovableStorageFs.openOutput(tmp)) {
            OutputStream os = fos;
            os.write(data);
            os.flush();
            fos.getFD().sync();
        }
        RemovableStorageFs.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
