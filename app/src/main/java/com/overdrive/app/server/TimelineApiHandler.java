package com.overdrive.app.server;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.server.hls.Fmp4Writer;
import com.overdrive.app.server.hls.HlsPlaylists;
import com.overdrive.app.server.hls.Mp4Exception;
import com.overdrive.app.server.hls.Mp4Index;
import com.overdrive.app.server.hls.Mp4IndexCache;
import com.overdrive.app.server.timeline.FrameService;
import com.overdrive.app.server.timeline.TimelineData;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import com.overdrive.app.storage.removable.SeekableFileReader;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recordings timeline routes (GET only): window and day JSON, scrub frames, per-clip and window HLS.
 * Files come only from index rows and pass {@link RecordingsApiHandler#indexPathAllowed}.
 */
public final class TimelineApiHandler {

    private static final long DAY_MS = 86_400_000L;
    private static final long WINDOW_MAX_MS = 8 * DAY_MS;
    private static final long DAYS_MAX_MS = 62 * DAY_MS;
    // Clients ask for 70 min; a day of 1 s GOPs would be a 90k-entry playlist.
    private static final long PLAYLIST_MAX_MS = 3 * 3_600_000L;
    // 2100-01-01: keeps from/to arithmetic far from overflow.
    private static final long WALL_MAX_MS = 4_102_444_800_000L;
    // Each stream or build holds one of the server's 32 workers.
    private static final int STREAMS_MAX = 12;
    private static final int BUILDS_MAX = 2;
    private static final int FRAME_WAITERS_MAX = 6;
    // A peer that accepts no bytes for this long is gone; closing frees the worker.
    private static final long STALL_MS = 20_000L;
    private static final long AT_MAX_MS = 6 * 3_600_000L;
    private static final int WIDTH_MIN = 160;
    private static final int WIDTH_MAX = 1280;

    private static final List<String> TYPES =
            Arrays.asList("normal", "sentry", "proximity", "replay", "oemDashcam");
    private static final List<String> STORAGES = Arrays.asList("INTERNAL", "SD_CARD", "USB");
    private static final List<String> INCLUDES =
            Arrays.asList("marks", "sessions", "trips", "states", "reaped", "road", "live");

    private static final Pattern RECORDING_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9-]{3,64}");
    private static final Pattern SEGMENT = Pattern.compile("s(0|[1-9][0-9]{0,6})\\.m4s");
    private static final Pattern SKIM_SEGMENT =
            Pattern.compile("k([48])s(0|[1-9][0-9]{0,6})\\.m4s");
    private static final Pattern SKIM_PLAYLIST = Pattern.compile("skim([48])\\.m3u8");

    private static final String PLAYLIST_TYPE = "application/vnd.apple.mpegurl";
    private static final String MEDIA_CACHE = "private, max-age=86400";
    private static final String NO_STORE = "no-store";
    // Stored but revalidated on every use, so a poll can be answered with a bodiless 304.
    private static final String REVALIDATE = "private, no-cache";

    private static final Semaphore STREAMS = new Semaphore(STREAMS_MAX);
    // Window/day queries run under the recordings index lock the recorder also takes: a few at a time.
    private static final Semaphore QUERIES = new Semaphore(2);
    // A cold moov parse can take tens of MB: at most two at once.
    private static final Semaphore PARSES = new Semaphore(2);
    private static final Semaphore BUILDS = new Semaphore(BUILDS_MAX);
    private static final Semaphore FRAME_WAITERS = new Semaphore(FRAME_WAITERS_MAX);
    private static final ThreadLocal<Socket> CLIENT = new ThreadLocal<>();
    private static final ThreadLocal<String> FETCH_SITE = new ThreadLocal<>();
    private static volatile ScheduledExecutorService stallTimer;

    private TimelineApiHandler() {}

    /** Called by the server per request (null to clear) so streams can be cut and embeds refused. */
    public static void bindRequest(Socket client, String secFetchSite) {
        if (client == null) {
            CLIENT.remove();
            FETCH_SITE.remove();
            return;
        }
        CLIENT.set(client);
        FETCH_SITE.set(secFetchSite);
    }

    public static boolean handle(String method, String path, String rangeHeader,
                                 OutputStream out) throws Exception {
        return handle(method, path, rangeHeader, null, out);
    }

    /**
     * @param ifNoneMatch the request's If-None-Match header, or null
     * @return false when {@code path} is not a timeline route. Range is ignored: bodies are
     *         always complete 200 responses with an exact Content-Length.
     */
    public static boolean handle(String method, String path, String rangeHeader,
                                 String ifNoneMatch, OutputStream out) throws Exception {
        if (path == null) return false;
        int q = path.indexOf('?');
        String route = q >= 0 ? path.substring(0, q) : path;
        boolean api = route.equals("/api/timeline") || route.startsWith("/api/timeline/");
        if (!api && !route.startsWith("/hls/")) return false;
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }
        // Footage is for this site's own pages and native clients, never another site's embed.
        String site = FETCH_SITE.get();
        if (site != null && (site.equalsIgnoreCase("cross-site") || site.equalsIgnoreCase("same-site"))) {
            HttpResponse.sendJsonNoCors(out, 403, "{\"error\":\"forbidden\"}");
            return true;
        }
        Map<String, String> query = parseQuery(q >= 0 ? path.substring(q + 1) : null);
        try {
            if (route.equals("/api/timeline")) serveWindow(query, ifNoneMatch, out);
            else if (route.equals("/api/timeline/days")) serveDays(query, out);
            else if (route.equals("/api/timeline/frame")) serveFrame(query, out);
            else if (route.equals("/hls/timeline.m3u8")) serveWindowPlaylist(query, out);
            else if (route.startsWith("/hls/id/")) serveClip(route.substring("/hls/id/".length()), query, out);
            else throw new Reject(404, "not_found");
        } catch (Reject r) {
            HttpResponse.sendJsonNoCors(out, r.status, "{\"error\":\"" + r.code + "\"}");
        } catch (RuntimeException | Error e) {
            // Streaming paths never rethrow after their headers, so nothing is committed here.
            log("route " + route + " failed: " + e);
            HttpResponse.sendJsonNoCors(out, 500, "{\"error\":\"internal\"}");
        }
        return true;
    }

    private static void serveWindow(Map<String, String> query, String ifNoneMatch,
                                    OutputStream out) throws Exception {
        long[] window = window(query, WINDOW_MAX_MS);
        Set<String> types = csv(query, "types", TYPES);
        Set<String> storages = csv(query, "storage", STORAGES);
        Set<String> include = csv(query, "include", INCLUDES);
        JSONObject body;
        if (!QUERIES.tryAcquire(3, TimeUnit.SECONDS)) {
            sendBusy(out);
            return;
        }
        try {
            body = TimelineData.windowJson(window[0], window[1], types, storages, include);
        } catch (Throwable t) {
            log("window failed: " + t);
            body = null;
        } finally {
            QUERIES.release();
        }
        if (body == null) throw new Reject(503, "unavailable");
        sendWindowJson(out, body, ifNoneMatch);
    }

    /** The ETag covers all but "now" (new on every call): a 304 leaves the client its cached "now". */
    private static void sendWindowJson(OutputStream out, JSONObject body, String ifNoneMatch)
            throws Exception {
        Object now = body.remove("now");
        String stable = body.toString();
        if (stable == null || stable.length() < 2 || !stable.endsWith("}")) {
            throw new Reject(503, "unavailable");
        }
        String etag = etag(stable);
        if (etagMatches(ifNoneMatch, etag)) {
            String head = "HTTP/1.1 304 Not Modified\r\n"
                    + "ETag: " + etag + "\r\n"
                    + "Cache-Control: " + REVALIDATE + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return;
        }
        String json = now == null ? stable : stable.substring(0, stable.length() - 1)
                + (stable.length() > 2 ? "," : "") + "\"now\":" + now + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Cache-Control: " + REVALIDATE + "\r\n"
                + "ETag: " + etag + "\r\n"
                + HttpResponse.SECURITY_HEADERS
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** Strong validator over the body text. */
    static String etag(String body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("\"tl");
            for (int i = 0; i < 12; i++) sb.append(String.format(Locale.US, "%02x", d[i] & 0xff));
            return sb.append('"').toString();
        } catch (Exception e) {
            return "\"tl" + Integer.toHexString(body.hashCode()) + "-" + body.length() + "\"";
        }
    }

    /** If-None-Match list (weak comparison, "*" matches any). */
    static boolean etagMatches(String header, String etag) {
        if (header == null || header.isEmpty()) return false;
        for (String part : header.split(",")) {
            String tag = part.trim();
            if (tag.startsWith("W/")) tag = tag.substring(2);
            if (tag.equals("*") || tag.equals(etag)) return true;
        }
        return false;
    }

    private static void serveDays(Map<String, String> query, OutputStream out) throws Exception {
        long[] window = window(query, DAYS_MAX_MS);
        JSONObject body;
        if (!QUERIES.tryAcquire(3, TimeUnit.SECONDS)) {
            sendBusy(out);
            return;
        }
        try {
            body = TimelineData.daysJson(window[0], window[1]);
        } catch (Throwable t) {
            log("days failed: " + t);
            body = null;
        } finally {
            QUERIES.release();
        }
        sendJsonBody(out, body);
    }

    private static void sendJsonBody(OutputStream out, JSONObject body) throws Exception {
        String json = body == null ? null : body.toString();
        if (json == null) throw new Reject(503, "unavailable");
        HttpResponse.sendJsonNoCors(out, json);
    }

    private static void serveFrame(Map<String, String> query, OutputStream out) throws Exception {
        String id = query.get("id");
        if (!validRecordingId(id)) throw new Reject(400, "id");
        String fp = query.get("fp");
        if (fp == null || !FINGERPRINT.matcher(fp).matches()) throw new Reject(400, "fp");
        long at = number(query, "at", "at");
        if (at < 0 || at > AT_MAX_MS) throw new Reject(400, "at");
        long width = number(query, "w", "w");
        if (width < WIDTH_MIN || width > WIDTH_MAX) throw new Reject(400, "w");
        TimelineData.ClipRef clip = resolve(id, fp);
        File file = servableFile(clip);
        if (clip.end != null && clip.end > clip.start) at = Math.min(at, clip.end - clip.start);
        if (!FRAME_WAITERS.tryAcquire()) {
            sendBusy(out);
            return;
        }
        FrameService.Frame frame;
        try {
            frame = FrameService.frame(id, fp, file, at, (int) width);
        } finally {
            FRAME_WAITERS.release();
        }
        if (frame.status == FrameService.OK) {
            HttpResponse.sendImageBytes(out, frame.jpeg, "image/jpeg");
        } else if (frame.status == FrameService.BUSY) {
            sendBusy(out);
        } else {
            throw new Reject(422, "frame");
        }
    }

    private static void serveClip(String rest, Map<String, String> query, OutputStream out)
            throws Exception {
        String[] parts = rest.split("/", -1);
        if (parts.length != 3) throw new Reject(404, "not_found");
        String id = parts[0];
        String fp = parts[1];
        String name = parts[2];
        if (!validRecordingId(id)) throw new Reject(400, "id");
        if (!FINGERPRINT.matcher(fp).matches()) throw new Reject(400, "fp");
        int skim = 0;
        int gop = -1;
        Matcher m;
        if ((m = SEGMENT.matcher(name)).matches()) {
            gop = Integer.parseInt(m.group(1));
        } else if ((m = SKIM_SEGMENT.matcher(name)).matches()) {
            skim = Integer.parseInt(m.group(1));
            gop = Integer.parseInt(m.group(2));
        } else if ((m = SKIM_PLAYLIST.matcher(name)).matches()) {
            skim = Integer.parseInt(m.group(1));
        } else if (!name.equals("main.m3u8") && !name.equals("init.mp4")
                && !name.equals("initv.mp4")) {
            throw new Reject(404, "not_found");
        }
        TimelineData.ClipRef clip = resolve(id, fp);
        File file = servableFile(clip);
        if (!PARSES.tryAcquire(3, TimeUnit.SECONDS)) {
            sendBusy(out);
            return;
        }
        Mp4Index index;
        try {
            index = loadIndex(file);
        } finally {
            PARSES.release();
        }
        // sa=1 pads a video-only clip with silent audio inside mixed window playlists; clips with audio ignore it.
        boolean silentAudio = "1".equals(query.get("sa")) && !index.hasAudio();
        if (gop >= 0) {
            if (!STREAMS.tryAcquire()) {
                sendBusy(out);
                return;
            }
            try {
                streamSegment(out, file, index, gop, skim, silentAudio && skim == 0);
            } finally {
                STREAMS.release();
            }
            return;
        }
        Long startWall = clip.start > 0 ? Long.valueOf(clip.start) : null;
        String playlist = null;
        byte[] init = null;
        try {
            if (name.equals("main.m3u8")) playlist = HlsPlaylists.clipPlaylist(index, startWall);
            else if (skim > 0) playlist = HlsPlaylists.skimPlaylist(index, skim, startWall);
            else if (name.equals("init.mp4") && silentAudio) init = Fmp4Writer.initSegmentSilentAudio(index);
            else if (name.equals("init.mp4")) init = Fmp4Writer.initSegment(index, index.hasAudio());
            else init = Fmp4Writer.initVideoOnly(index);
        } catch (Throwable t) {
            log(name + " failed for " + id + ": " + t);
            throw new Reject(500, "hls");
        }
        if (playlist != null) {
            sendBody(out, PLAYLIST_TYPE, playlist.getBytes(StandardCharsets.UTF_8), NO_STORE);
        } else if (init != null && init.length > 0) {
            sendBody(out, "video/mp4", init, MEDIA_CACHE);
        } else {
            throw new Reject(500, "hls");
        }
    }

    private static void streamSegment(OutputStream out, File file, Mp4Index index, int gop,
                                      int skim, boolean silentAudio) throws Exception {
        long length;
        try {
            if (gop >= index.gopCount()) throw new Reject(404, "not_found");
            length = skim > 0 ? Fmp4Writer.skimSegmentLength(index, gop, skim)
                    : silentAudio ? Fmp4Writer.mediaSegmentLength(index, gop, true)
                    : Fmp4Writer.mediaSegmentLength(index, gop);
        } catch (Reject r) {
            throw r;
        } catch (Throwable t) {
            log("segment " + gop + " length failed: " + t);
            throw new Reject(500, "hls");
        }
        if (length <= 0) throw new Reject(500, "hls");
        SeekableFileReader source;
        try {
            source = new SeekableFileReader(file);
        } catch (IOException gone) {
            throw new Reject(410, "gone");
        }
        try {
            FileChannel channel = source.getChannel();
            if (channel.size() != index.fileLength()) throw new Reject(409, "stale");
            BoundedOutput body = new BoundedOutput(out, length);
            ScheduledFuture<?> watch = watchStall(body, CLIENT.get());
            try {
                writeHead(out, "video/iso.segment", length, MEDIA_CACHE);
                if (skim > 0) Fmp4Writer.writeSkimSegment(index, channel, gop, skim, body);
                else if (silentAudio) Fmp4Writer.writeMediaSegment(index, channel, gop, true, body);
                else Fmp4Writer.writeMediaSegment(index, channel, gop, body);
                out.flush();
            } catch (Throwable t) {
                // Headers are out: a short body is the only failure signal left. Peer aborts stay quiet.
                if (body.overrun || !(t instanceof IOException)) {
                    log("segment " + gop + " of " + file.getName() + " aborted: " + t);
                }
                return;
            } finally {
                if (watch != null) watch.cancel(false);
            }
            if (body.count != length) {
                log("segment " + gop + " of " + file.getName() + " wrote " + body.count + "/" + length);
            }
        } finally {
            try {
                source.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void serveWindowPlaylist(Map<String, String> query, OutputStream out)
            throws Exception {
        long[] window = window(query, PLAYLIST_MAX_MS);
        Set<String> types = csv(query, "types", TYPES);
        String prefer = query.get("prefer");
        if (prefer != null && !prefer.isEmpty()) {
            prefer = canonical(prefer, TYPES);
            if (prefer == null) throw new Reject(400, "prefer");
        } else {
            prefer = null;
        }
        Integer skim = null;
        String skimParam = query.get("skim");
        if (skimParam != null && !skimParam.isEmpty()) {
            if (!skimParam.equals("4") && !skimParam.equals("8")) throw new Reject(400, "skim");
            skim = Integer.valueOf(skimParam);
        }
        if (!BUILDS.tryAcquire()) {
            sendBusy(out);
            return;
        }
        try {
            serveWindowPlaylist(window, types, prefer, skim, out);
        } finally {
            BUILDS.release();
        }
    }

    private static void serveWindowPlaylist(long[] window, Set<String> types, String prefer,
                                            Integer skim, OutputStream out) throws Exception {
        List<TimelineData.PlanItem> plan;
        try {
            plan = TimelineData.playbackPlan(window[0], window[1], types, prefer);
        } catch (Throwable t) {
            log("plan failed: " + t);
            throw new Reject(503, "unavailable");
        }
        Map<String, TimelineData.ClipRef> clips = new HashMap<>();
        Set<String> rejected = new HashSet<>();
        List<TimelineData.PlanItem> items = new ArrayList<>();
        if (plan != null) {
            for (TimelineData.PlanItem item : plan) {
                TimelineData.ClipRef clip = item == null ? null : item.clip;
                if (clip == null || !validRecordingId(clip.id)
                        || item.playToWall <= item.playFromWall || rejected.contains(clip.id)) {
                    continue;
                }
                if (!clips.containsKey(clip.id)) {
                    try {
                        servableFile(clip);
                    } catch (Reject r) {
                        rejected.add(clip.id);
                        continue;
                    }
                    clips.put(clip.id, clip);
                }
                items.add(item);
            }
        }
        String playlist = windowPlaylist(items, clips, skim);
        sendBody(out, PLAYLIST_TYPE, playlist.getBytes(StandardCharsets.UTF_8), NO_STORE);
    }

    /** The builder skips clips the loader refuses; a window with no segments left is a 404. */
    private static String windowPlaylist(List<TimelineData.PlanItem> items,
                                         Map<String, TimelineData.ClipRef> clips,
                                         Integer skim) throws Reject {
        HlsPlaylists.IndexLoader loader = recordingId -> {
            TimelineData.ClipRef clip = recordingId == null ? null : clips.get(recordingId);
            if (clip == null) throw new Mp4Exception("not in plan");
            try {
                return loadIndex(servableFile(clip));
            } catch (Reject r) {
                throw new Mp4Exception(r.code);
            }
        };
        List<HlsPlaylists.Entry> entries = new ArrayList<>();
        for (TimelineData.PlanItem item : items) {
            TimelineData.ClipRef clip = clips.get(item.clip.id);
            entries.add(new HlsPlaylists.Entry(clip.id, clip.fp(), clip.start,
                    item.playFromWall, item.playToWall));
        }
        String playlist;
        try {
            playlist = entries.isEmpty() ? null : HlsPlaylists.timelinePlaylist(entries, loader, skim);
        } catch (Throwable t) {
            log("window playlist failed: " + t);
            throw new Reject(500, "hls");
        }
        if (playlist == null || !playlist.contains("#EXTINF:")) throw new Reject(404, "empty");
        return playlist;
    }

    private static TimelineData.ClipRef resolve(String id, String fp) throws Reject {
        TimelineData.ClipRef clip;
        try {
            clip = TimelineData.clip(id);
        } catch (Throwable t) {
            log("clip lookup failed: " + t);
            throw new Reject(503, "unavailable");
        }
        if (clip == null || !id.equals(clip.id)) throw new Reject(404, "not_found");
        if (!fp.equals(clip.fp())) throw new Reject(409, "stale");
        return clip;
    }

    /** The clip's file when it may be served now; same 404/410 split as /video/id/. */
    private static File servableFile(TimelineData.ClipRef clip) throws Reject {
        if (!clip.available || clip.absPath == null || !clip.absPath.endsWith(".mp4")) {
            throw new Reject(404, "not_found");
        }
        File file = new File(clip.absPath);
        if (!RecordingsApiHandler.indexPathAllowed(file)) throw new Reject(404, "not_found");
        if (!RemovableStorageFs.isFile(file) || !RemovableStorageFs.canRead(file)) throw new Reject(410, "gone");
        // URLs carry the fingerprint and are cached for a day: bytes must match it.
        if (RemovableStorageFs.length(file) != clip.sizeBytes || RemovableStorageFs.lastModified(file) != clip.mp4Mtime) {
            throw new Reject(409, "stale");
        }
        return file;
    }

    private static Mp4Index loadIndex(File file) throws Reject {
        Mp4Index index;
        try {
            index = Mp4IndexCache.get(file);
        } catch (Throwable t) {
            if (t instanceof Mp4Exception) throw new Reject(422, "unplayable");
            log("index failed for " + file.getName() + ": " + t);
            throw new Reject(500, "hls");
        }
        if (index == null) throw new Reject(422, "unplayable");
        return index;
    }

    private static void writeHead(OutputStream out, String type, long length, String cache)
            throws IOException {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
    }

    private static void sendBody(OutputStream out, String type, byte[] body, String cache)
            throws IOException {
        writeHead(out, type, body.length, cache);
        out.write(body);
        out.flush();
    }

    private static void sendBusy(OutputStream out) throws IOException {
        byte[] body = "{\"error\":\"busy\"}".getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 503 Service Unavailable\r\n"
                + "Content-Type: application/json\r\n"
                + "Cache-Control: " + NO_STORE + "\r\n"
                + "Retry-After: 1\r\n"
                + HttpResponse.SECURITY_HEADERS
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static long[] window(Map<String, String> query, long maxSpanMs) throws Reject {
        long from = number(query, "from", "range");
        long to = number(query, "to", "range");
        if (from < 0 || to <= from || to > WALL_MAX_MS || to - from > maxSpanMs) {
            throw new Reject(400, "range");
        }
        return new long[] {from, to};
    }

    /** Closes the socket when the body makes no progress for {@link #STALL_MS}. */
    private static ScheduledFuture<?> watchStall(BoundedOutput body, Socket client) {
        if (client == null) return null;
        try {
            return timer().scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - body.progressNs > STALL_MS * 1_000_000L) {
                    log("segment stream stalled " + STALL_MS / 1000 + " s; closing");
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                    throw new IllegalStateException("closed");
                }
            }, STALL_MS / 4, STALL_MS / 4, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ScheduledExecutorService timer() {
        ScheduledExecutorService t = stallTimer;
        if (t != null) return t;
        synchronized (TimelineApiHandler.class) {
            if (stallTimer == null) {
                stallTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r, "TimelineStallWatch");
                    th.setDaemon(true);
                    return th;
                });
            }
            return stallTimer;
        }
    }

    private static long number(Map<String, String> query, String name, String code) throws Reject {
        String value = query.get(name);
        if (value == null || value.isEmpty()) throw new Reject(400, code);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new Reject(400, code);
        }
    }

    /** Whitelisted CSV, canonical spelling; absent or empty means every allowed value. */
    private static Set<String> csv(Map<String, String> query, String name, List<String> allowed)
            throws Reject {
        Set<String> values = new LinkedHashSet<>();
        String raw = query.get(name);
        if (raw != null) {
            for (String token : raw.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                String value = canonical(t, allowed);
                if (value == null) throw new Reject(400, name);
                values.add(value);
            }
        }
        return values.isEmpty() ? new LinkedHashSet<>(allowed) : values;
    }

    private static String canonical(String token, List<String> allowed) {
        for (String value : allowed) {
            if (value.equalsIgnoreCase(token)) return value;
        }
        return null;
    }

    private static boolean validRecordingId(String recordingId) {
        return recordingId != null && RECORDING_ID.matcher(recordingId).matches();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                // A literal '+' stays '+' (clients send encodeURIComponent output).
                String key = kv[0];
                String val = kv[1];
                try {
                    key = java.net.URLDecoder.decode(key.replace("+", "%2B"), "UTF-8");
                    val = java.net.URLDecoder.decode(val.replace("+", "%2B"), "UTF-8");
                } catch (Exception ignored) {
                }
                params.put(key, val);
            }
        }
        return params;
    }

    private static void log(String message) {
        CameraDaemon.log("Timeline: " + message);
    }

    private static final class Reject extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final String code;

        Reject(int status, String code) {
            super(code, null, false, false);
            this.status = status;
            this.code = code;
        }
    }

    /** Caps a segment body at its declared Content-Length; never closes the socket stream. */
    private static final class BoundedOutput extends OutputStream {
        private final OutputStream out;
        private final long limit;
        long count;
        boolean overrun;
        volatile long progressNs = System.nanoTime();

        BoundedOutput(OutputStream out, long limit) {
            this.out = out;
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            if (count >= limit) {
                overrun = true;
                throw new IOException("segment longer than declared");
            }
            out.write(b);
            count++;
            progressNs = System.nanoTime();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || len > limit - count) {
                overrun = true;
                throw new IOException("segment longer than declared");
            }
            out.write(b, off, len);
            count += len;
            progressNs = System.nanoTime();
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
