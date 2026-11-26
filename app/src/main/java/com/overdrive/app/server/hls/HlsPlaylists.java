package com.overdrive.app.server.hls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** VOD media playlists with one segment per GOP. */
public final class HlsPlaylists {
    public interface IndexLoader {
        Mp4Index load(String recordingId) throws Mp4Exception;
    }

    /** One playback-plan item; wall times are epoch ms and clipStartWall is the wall time of media time 0. */
    public static final class Entry {
        public final String id;
        public final String fp;
        public final long clipStartWall;
        public final long playFromWall;
        public final long playToWall;

        public Entry(String id, String fp, long clipStartWall, long playFromWall, long playToWall) {
            this.id = id;
            this.fp = fp;
            this.clipStartWall = clipStartWall;
            this.playFromWall = playFromWall;
            this.playToWall = playToWall;
        }
    }

    private static final String HEADER = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:";
    private static final String HEADER_REST = "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
            + "#EXT-X-INDEPENDENT-SEGMENTS\n";

    private HlsPlaylists() {}

    public static String clipPlaylist(Mp4Index idx, Long startWallMs) {
        return single(idx, 0, startWallMs, "init.mp4", "s");
    }

    /** k-x keyframe skim: one keyframe per segment, EXTINF = GOP duration / k, PDT = wall time of the GOP. */
    public static String skimPlaylist(Mp4Index idx, int k, Long startWallMs) {
        Fmp4Writer.checkK(k);
        return single(idx, k, startWallMs, "initv.mp4", "k" + k + "s");
    }

    /**
     * Concatenates plan items: a clip contributes the GOPs whose wall span intersects
     * [playFromWall, playToWall) and is not already covered by earlier segments. Unloadable clips are skipped.
     * GOP tables are cached by id + fp, so the loader only runs for clips not seen with that fingerprint.
     * When any contributing clip has audio, video-only clips use their silent-audio variants (?sa=1)
     * so every clip carries the same track layout; skim playlists stay video-only.
     */
    public static String timelinePlaylist(List<Entry> plan, IndexLoader loader, Integer skimK) {
        int k = skimK == null ? 0 : skimK;
        if (skimK != null) Fmp4Writer.checkK(k);
        if (plan == null) plan = Collections.emptyList();
        List<Pick> picks = new ArrayList<>();
        boolean anyAudio = false;
        long coveredUs = Long.MIN_VALUE;
        for (Entry e : plan) {
            if (e == null || !safeToken(e.id) || !safeToken(e.fp) || e.playToWall <= e.playFromWall) continue;
            String key = e.id + '/' + e.fp;
            GopTable gt = GopTable.cached(key);
            if (gt == null) {
                Mp4Index idx;
                try {
                    idx = loader.load(e.id);
                } catch (Mp4Exception ex) {
                    continue;
                }
                if (idx == null) continue;
                gt = idx.gops;
                GopTable.put(key, gt);
            }
            long clipUs = e.clipStartWall * 1000L;
            long fromUs = e.playFromWall * 1000L;
            long toUs = e.playToWall * 1000L;
            // GOP boundaries are monotonic (validated by Mp4Index). Skip the
            // leading GOPs outside this window or already covered by earlier
            // clips, and size the scratch array for the window rather than the
            // entire recording — long clips often contribute just a few GOPs.
            int first = firstGopEndingAfter(gt.us, clipUs, Math.max(fromUs, coveredUs));
            int end = firstGopStartingAtOrAfter(gt.us, clipUs, toUs);
            if (first >= end) continue;
            int[] gops = new int[end - first];
            int count = 0;
            for (int n = first; n < end; n++) {
                long endUs = clipUs + gt.us[n + 1];
                // Equal-duration boundaries may still be covered by a prior GOP.
                if (endUs <= coveredUs) continue;
                gops[count++] = n;
                coveredUs = Math.max(coveredUs, endUs);
            }
            if (count == 0) continue;
            picks.add(new Pick(e, gt, Arrays.copyOf(gops, count)));
            anyAudio |= gt.audio;
        }
        boolean pad = anyAudio && k == 0;
        TimeZone tz = TimeZone.getDefault();
        StringBuilder body = startPlaylist(4096);
        long maxMs = 0;
        boolean first = true;
        for (Pick p : picks) {
            String dir = "/hls/id/" + p.entry.id + "/" + p.entry.fp + "/";
            String query = pad && !p.gt.audio ? "?sa=1" : "";
            if (!first) body.append("#EXT-X-DISCONTINUITY\n");
            first = false;
            body.append("#EXT-X-MAP:URI=\"").append(dir).append(k == 0 ? "init.mp4" : "initv.mp4")
                    .append(query).append("\"\n");
            for (int n : p.gops) {
                long ms = p.gt.segmentMs(n, k);
                maxMs = Math.max(maxMs, ms);
                pdt(body, p.entry.clipStartWall + p.gt.startMs(n), tz);
                extinf(body, ms);
                body.append(dir);
                if (k == 0) body.append('s');
                else body.append('k').append(k).append('s');
                body.append(n).append(".m4s").append(query).append('\n');
            }
        }
        return finish(maxMs, body);
    }

    /** First GOP whose end is strictly after cutoffUs (or count if none). */
    static int firstGopEndingAfter(long[] us, long clipUs, long cutoffUs) {
        int lo = 0;
        int hi = us.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (clipUs + us[mid + 1] <= cutoffUs) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** First GOP whose start is at or after toUs (or count if none). */
    static int firstGopStartingAtOrAfter(long[] us, long clipUs, long toUs) {
        int lo = 0;
        int hi = us.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (clipUs + us[mid] < toUs) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static final class Pick {
        final Entry entry;
        final GopTable gt;
        final int[] gops;

        Pick(Entry entry, GopTable gt, int[] gops) {
            this.entry = entry;
            this.gt = gt;
            this.gops = gops;
        }
    }

    private static String single(Mp4Index idx, int k, Long startWall, String init, String prefix) {
        GopTable gt = idx.gops;
        int gops = gt.count();
        StringBuilder body = startPlaylist(96 * (gops + 2));
        body.append("#EXT-X-MAP:URI=\"").append(init).append("\"\n");
        TimeZone tz = TimeZone.getDefault();
        long maxMs = 0;
        for (int n = 0; n < gops; n++) {
            long ms = gt.segmentMs(n, k);
            maxMs = Math.max(maxMs, ms);
            if (startWall != null) pdt(body, startWall + gt.startMs(n), tz);
            extinf(body, ms);
            body.append(prefix).append(n).append(".m4s\n");
        }
        return finish(maxMs, body);
    }

    private static StringBuilder startPlaylist(int capacity) {
        // Reserve one digit for the target duration, normally a single digit.
        // Write the body directly into the final builder rather than keeping
        // another full playlist-sized backing array alive at completion.
        return new StringBuilder(capacity).append(HEADER).append('0').append(HEADER_REST);
    }

    private static String finish(long maxMs, StringBuilder body) {
        long target = Math.max(1, (maxMs + 999) / 1000);
        if (target < 10) {
            body.setCharAt(HEADER.length(), (char) ('0' + target));
        } else {
            body.replace(HEADER.length(), HEADER.length() + 1, Long.toString(target));
        }
        return body.append("#EXT-X-ENDLIST\n").toString();
    }

    private static void extinf(StringBuilder sb, long ms) {
        sb.append("#EXTINF:").append(ms / 1000).append('.');
        pad(sb, ms % 1000, 3).append(",\n");
    }

    private static void pdt(StringBuilder sb, long epochMs, TimeZone tz) {
        sb.append("#EXT-X-PROGRAM-DATE-TIME:");
        appendIso(sb, epochMs, tz);
        sb.append('\n');
    }

    /** yyyy-MM-dd'T'HH:mm:ss.SSSXXX with ASCII digits whatever the default locale. */
    static void appendIso(StringBuilder sb, long epochMs, TimeZone tz) {
        int off = tz.getOffset(epochMs);
        long local = epochMs + off;
        long days = Math.floorDiv(local, 86_400_000L);
        int msOfDay = (int) Math.floorMod(local, 86_400_000L);
        long z = days + 719_468L;
        long era = Math.floorDiv(z, 146_097L);
        long doe = z - era * 146_097L;
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long day = doy - (153 * mp + 2) / 5 + 1;
        long month = mp < 10 ? mp + 3 : mp - 9;
        long year = yoe + era * 400 + (month <= 2 ? 1 : 0);
        pad(sb, year, 4).append('-');
        pad(sb, month, 2).append('-');
        pad(sb, day, 2).append('T');
        pad(sb, msOfDay / 3_600_000, 2).append(':');
        pad(sb, msOfDay / 60_000 % 60, 2).append(':');
        pad(sb, msOfDay / 1000 % 60, 2).append('.');
        pad(sb, msOfDay % 1000, 3);
        if (off == 0) {
            sb.append('Z');
            return;
        }
        int min = Math.abs(off) / 60_000;
        sb.append(off < 0 ? '-' : '+');
        pad(sb, min / 60, 2).append(':');
        pad(sb, min % 60, 2);
    }

    private static StringBuilder pad(StringBuilder sb, long v, int width) {
        // Normal timestamp components and EXTINF fractions fit in four digits.
        // Emit ASCII digits directly, avoiding a temporary decimal String for
        // each component. Use the general padding path for wider widths or
        // values outside the fast path's range.
        if (width <= 4 && v >= 0 && v < 10_000) {
            int n = (int) v;
            if (width >= 4 || n >= 1000) sb.append((char) ('0' + n / 1000));
            if (width >= 3 || n >= 100) sb.append((char) ('0' + n / 100 % 10));
            if (width >= 2 || n >= 10) sb.append((char) ('0' + n / 10 % 10));
            return sb.append((char) ('0' + n % 10));
        }
        String s = Long.toString(v);
        for (int i = s.length(); i < width; i++) sb.append('0');
        return sb.append(s);
    }

    private static boolean safeToken(String s) {
        if (s == null || s.isEmpty() || s.length() > 96) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
