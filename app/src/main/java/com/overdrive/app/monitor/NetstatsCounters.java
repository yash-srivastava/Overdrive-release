package com.overdrive.app.monitor;

import java.io.BufferedReader;
import java.io.IOException;

/** Incrementally sums untagged per-UID traffic from Android's netstats report. */
final class NetstatsCounters {
    private NetstatsCounters() {}

    /** Return {wifi, mobile, other, app, system}, or null if UID stats are absent.
     * The caller owns the reader; an I/O failure must not publish partial totals. */
    static long[] read(BufferedReader reader, int appUid, int shellUid) throws IOException {
        long wifi = 0, mobile = 0, other = 0, app = 0, system = 0;
        boolean sawUidSection = false, inUidSection = false;
        // State for the current ident block.
        boolean blockMatches = false;
        boolean blockIsApp = false;
        int blockTransport = 0; // 0=other,1=wifi,2=mobile
        String raw;
        while ((raw = reader.readLine()) != null) {
            // Section boundaries: a header at column 0 (no leading spaces).
            if (!raw.startsWith(" ") && raw.endsWith(":")) {
                inUidSection = "UID stats:".equals(raw.trim());
                if (inUidSection) sawUidSection = true;
                blockMatches = false;
                continue;
            }
            if (!inUidSection) continue;
            String line = raw.trim();

            if (line.startsWith("ident=")) {
                // New per-UID block header. Decide if it's one of ours + untagged.
                blockMatches = false;
                int uid = parseIntAfter(line, "uid=");
                boolean isApp = appUid > 0 && uid == appUid;
                boolean ours = (uid == shellUid) || isApp;
                if (ours && isUntagged(parseTokenAfter(line, "tag="))) {
                    blockMatches = true;
                    blockIsApp = isApp;
                    String type = parseTokenAfter(line, "type=");   // WIFI / MOBILE / ...
                    blockTransport = "WIFI".equals(type) ? 1
                            : ("MOBILE".equals(type) ? 2 : 0);
                }
                continue;
            }
            if (blockMatches && line.startsWith("st=")) {
                long rb = parseLongAfter(line, "rb=");
                long tb = parseLongAfter(line, "tb=");
                long bytes = rb + tb;
                if (blockTransport == 1) wifi += bytes;
                else if (blockTransport == 2) mobile += bytes;
                else other += bytes;
                if (blockIsApp) app += bytes; else system += bytes;
            }
            // "NetworkStatsHistory:" and other lines inside a block are ignored.
        }
        if (!sawUidSection) return null;   // unexpected format → let caller warn
        return new long[]{wifi, mobile, other, app, system};
    }

    /** Integer immediately after {@code key} (e.g. "uid=") up to the next
     *  whitespace/comma/brace. Returns -1 if absent/unparseable. */
    private static int parseIntAfter(String line, String key) {
        String tok = parseTokenAfter(line, key);
        try { return Integer.parseInt(tok); } catch (Exception e) { return -1; }
    }

    private static long parseLongAfter(String line, String key) {
        String tok = parseTokenAfter(line, key);
        try { return Long.parseLong(tok); } catch (Exception e) { return 0L; }
    }

    /** The token right after {@code key}, delimited by whitespace, ',', '}', ']'.
     *  "" if key not present. */
    private static String parseTokenAfter(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        int start = i + key.length();
        int end = start;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (c == ' ' || c == ',' || c == '}' || c == ']' || c == '\t') break;
            end++;
        }
        return line.substring(start, end);
    }

    /** True when the acct_tag_hex column is the untagged/grand-total tag (0x0),
     *  in either the short "0x0" or zero-padded "0x00...0" form. Parse the hex and
     *  compare to 0 so any width is handled; a malformed tag is treated as tagged
     *  (skip) so we never accidentally sum a subset row into the total. */
    static boolean isUntagged(String acctTagHex) {
        if (acctTagHex == null) return false;
        String s = acctTagHex.startsWith("0x") || acctTagHex.startsWith("0X")
                ? acctTagHex.substring(2) : acctTagHex;
        if (s.isEmpty()) return false;
        try {
            return Long.parseLong(s, 16) == 0L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

}
