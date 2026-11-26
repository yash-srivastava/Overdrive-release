package com.overdrive.app.monitor;

import java.io.BufferedReader;
import java.io.IOException;

/** Incrementally sums untagged per-UID traffic from Android network stats sources. */
final class NetstatsCounters {
    private NetstatsCounters() {}

    /** Read legacy qtaguid rows without splitting every line into an array of tokens. */
    static long[] readQtaguid(BufferedReader reader, int appUid, int shellUid) throws IOException {
        long wifi = 0, mobile = 0, other = 0, app = 0, system = 0;
        Fields fields = new Fields();
        String line;
        while ((line = reader.readLine()) != null) {
            fields.reset(line);
            if (!fields.next() || !fields.next()) continue; // index, iface
            int ifaceStart = fields.start;
            int ifaceEnd = fields.end;
            if (fields.equalsToken("iface") || fields.equalsToken("lo")) continue;
            if (!fields.next()) continue; // tag
            int tagStart = fields.start;
            int tagEnd = fields.end;
            if (!fields.next()) continue; // uid
            long uid = fields.uid();
            boolean isApp = appUid > 0 && uid == appUid;
            if (uid != shellUid && !isApp) continue;
            if (!isUntagged(line.substring(tagStart, tagEnd))) continue;
            if (!fields.next() || !fields.next()) continue; // counter set, rx bytes
            int rxStart = fields.start;
            int rxEnd = fields.end;
            if (!fields.next() || !fields.next()) continue; // rx packets, tx bytes
            long rx, tx;
            try {
                rx = Long.parseLong(line.substring(rxStart, rxEnd));
                tx = Long.parseLong(line.substring(fields.start, fields.end));
            } catch (NumberFormatException malformed) {
                continue;
            }
            long bytes = rx + tx;
            if (startsWith(line, ifaceStart, ifaceEnd, "wlan")) wifi += bytes;
            else if (startsWith(line, ifaceStart, ifaceEnd, "rmnet")
                    || startsWith(line, ifaceStart, ifaceEnd, "ccmni")
                    || startsWith(line, ifaceStart, ifaceEnd, "rndis")
                    || startsWith(line, ifaceStart, ifaceEnd, "ppp")) mobile += bytes;
            else other += bytes;
            if (isApp) app += bytes; else system += bytes;
        }
        return new long[]{wifi, mobile, other, app, system};
    }

    private static boolean startsWith(String line, int start, int end, String prefix) {
        return end - start >= prefix.length() && line.startsWith(prefix, start);
    }

    /** Reusable spans matching the old trim().split("\\s+") field boundaries. */
    private static final class Fields {
        String line;
        int start, end, limit;

        void reset(String line) {
            this.line = line;
            end = 0;
            limit = line.length();
            while (end < limit && line.charAt(end) <= ' ') end++;
            while (limit > end && line.charAt(limit - 1) <= ' ') limit--;
        }

        boolean next() {
            while (end < limit && whitespace(line.charAt(end))) end++;
            start = end;
            while (end < limit && !whitespace(line.charAt(end))) end++;
            return end > start;
        }

        boolean equalsToken(String token) {
            return end - start == token.length() && line.startsWith(token, start);
        }

        // Java's default regex \s is ASCII whitespace, not all Unicode whitespace.
        private static boolean whitespace(char c) {
            return c == ' ' || (c >= '\t' && c <= '\r');
        }

        /** Match Integer.parseInt semantics, without token allocation or malformed-row exceptions. */
        long uid() {
            int i = start;
            char first = line.charAt(i);
            boolean negative = first == '-';
            if (negative || first == '+') i++;
            if (i == end) return Long.MIN_VALUE;
            long value = 0;
            long max = negative ? 2147483648L : Integer.MAX_VALUE;
            for (; i < end; i++) {
                char c = line.charAt(i);
                int digit = c >= '0' && c <= '9' ? c - '0' : Character.digit(c, 10);
                if (digit < 0) return Long.MIN_VALUE;
                value = value * 10 + digit;
                if (value > max) return Long.MIN_VALUE;
            }
            return negative ? -value : value;
        }
    }

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
