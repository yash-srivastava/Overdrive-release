package com.overdrive.app.monitor;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class NetstatsCountersTest {
    private static final int APP_UID = 10234;
    private static final int SHELL_UID = 2000;

    private static long[] read(String report, int appUid) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(report))) {
            return NetstatsCounters.read(reader, appUid, SHELL_UID);
        }
    }

    @Test public void sumsOnlyUntaggedOwnedUidBucketsAcrossTransports() throws Exception {
        String report = "Dev stats:\n"
                + "  ident=[{type=WIFI}] uid=2000 tag=0x0\n    st=1 rb=999 tb=999\n"
                + "UID stats:\n"
                + "  ident=[{type=WIFI}] uid=10234 set=DEFAULT tag=0x0000\n"
                + "    NetworkStatsHistory: bucketDuration=1\n"
                + "    st=1 rb=10 rp=1 tb=20 tp=2\n    st=2 rb=2 tb=3\n"
                + "  ident=[{type=WIFI}] uid=10234 set=FOREGROUND tag=0X0\n"
                + "    st=1 rb=5 tb=5\n"
                + "  ident=[{type=MOBILE}] uid=2000 tag=0x0\n    st=1 rb=40 tb=60\n"
                + "  ident=[{type=ETHERNET}] uid=2000 tag=0x0\n    st=1 rb=7 tb=8\n"
                + "  ident=[{type=WIFI}] uid=10234 tag=0x1\n    st=1 rb=100 tb=100\n"
                + "  ident=[{type=MOBILE}] uid=9999 tag=0x0\n    st=1 rb=100 tb=100\n"
                + "UID tag stats:\n"
                + "  ident=[{type=WIFI}] uid=10234 tag=0x0\n    st=1 rb=999 tb=999";
        assertArrayEquals(new long[]{45, 100, 15, 45, 115}, read(report, APP_UID));
        assertArrayEquals(new long[]{0, 100, 15, 0, 115}, read(report, -1));
        assertArrayEquals(new long[]{45, 100, 15, 45, 115},
                read(report.replace("\n", "\r\n"), APP_UID));
    }

    @Test public void rejectsMissingSectionButAcceptsEmptyUidSection() throws Exception {
        assertNull(read("", APP_UID));
        assertNull(read("UID tag stats:\n  ident=[{type=WIFI}] uid=2000 tag=0x0\n", APP_UID));
        assertArrayEquals(new long[5], read("UID stats:\n", APP_UID));
    }

    @Test public void malformedTokensAndSectionChangesKeepExistingRules() throws Exception {
        String report = "UID stats:\n"
                + "  ident=[{type=WIFI}] uid=2000 tag=bad!\n    st=1 rb=99 tb=99\n"
                + "  ident=[{type=WIFI}] uid=oops tag=0x0\n    st=1 rb=99 tb=99\n"
                + "  ident=[{type=MOBILE}] uid=2000 tag=0x0\n    st=1 rb=bad tb=9\n"
                + "Other stats:\n    st=1 rb=99 tb=99\n"
                + "UID stats:\n    st=1 rb=99 tb=99\n"
                + "  ident=[{type=WIFI}] uid=10234 tag=0x0\n    st=1 rb=3000000000\n";
        assertArrayEquals(new long[]{3000000000L, 9, 0, 3000000000L, 9}, read(report, APP_UID));
    }

    @Test public void readFailureDoesNotReturnPartialTotals() throws Exception {
        try (BufferedReader reader = new BufferedReader(new StringReader("")) {
            private int line;
            @Override public String readLine() throws IOException {
                switch (line++) {
                    case 0: return "UID stats:";
                    case 1: return "  ident=[{type=WIFI}] uid=2000 tag=0x0";
                    case 2: return "    st=1 rb=10 tb=20";
                    default: throw new IOException("injected pipe failure");
                }
            }
        }) {
            try {
                NetstatsCounters.read(reader, APP_UID, SHELL_UID);
                fail("Partial totals must not be published");
            } catch (IOException expected) {
                // The caller handles the error and leaves the baseline unchanged.
            }
        }
    }
    private static long[] qtaguid(String report, int appUid, int shellUid) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(report))) {
            return NetstatsCounters.readQtaguid(reader, appUid, shellUid);
        }
    }

    @Test public void qtaguidPreservesTransportAndUidTotals() throws Exception {
        String report = "idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets\n"
                + "2 wlan0 0x0 10234 0 10 1 20 2 0\n"
                + "3 wlan1 0X0000 +0002000 1 30 1 40 2\n"
                + "4 rmnet0 0x0 2000 0 50 1 60 2\n"
                + "5 ccmni0 0x0 10234 0 1 1 2 2\n"
                + "6 rndis0 0x0 10234 0 3 1 4 2\n"
                + "7 ppp0 0x0 2000 0 5 1 6 2\n"
                + "8 eth0 0x0 2000 0 7 1 8 2\n"
                + "9 lo 0x0 2000 0 900 1 900 2\n"
                + "10 wlan0 0x1 10234 0 900 1 900 2\n"
                + "11 wlan0 0x0 9999 0 900 1 900 2\n";
        assertArrayEquals(new long[]{100, 131, 15, 40, 206}, qtaguid(report, APP_UID, SHELL_UID));
        assertArrayEquals(new long[]{70, 121, 15, 0, 206}, qtaguid(report, -1, SHELL_UID));
    }

    @Test public void qtaguidMatchesOldSplitParser() throws Exception {
        java.util.Random random = new java.util.Random(2301);
        String[] interfaces = {"wlan0", "wlan", "rmnet1", "ccmni0", "rndis1", "ppp0", "eth0", "lo", "iface", "wl", "wlan0\u0000x"};
        String[] tags = {"0x0", "0X000", "0", "+0", "-0", "0x1", "bad!", "0x", "0x00000000000000000000000000"};
        String[] uids = {"10234", "2000", "9999", "+0002000", "-0", "-2147483648", "2147483647", "2147483648", "-2147483649", "oops", "+", "２０００", "0000000000000000000000000002000"};
        String[] counters = {"0", "10", "-10", "+00020", "3000000000", "9223372036854775807", "-9223372036854775808", "9223372036854775808", "bad", "１２", "-"};
        String[] gaps = {" ", "  ", "\t", "\f", "\u000b", " \t "};
        int[] shells = {SHELL_UID, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, -1};
        for (int round = 0; round < 500; round++) {
            StringBuilder report = new StringBuilder();
            for (int row = 0; row < 100; row++) {
                String[] tokens = {"2", interfaces[random.nextInt(interfaces.length)], tags[random.nextInt(tags.length)],
                        uids[random.nextInt(uids.length)], "0", counters[random.nextInt(counters.length)],
                        "1", counters[random.nextInt(counters.length)], "2", "ignored"};
                int count = random.nextInt(4) == 0 ? random.nextInt(tokens.length) : tokens.length;
                if (random.nextBoolean()) report.append("\u0000 \t");
                for (int i = 0; i < count; i++) {
                    if (i > 0) report.append(gaps[random.nextInt(gaps.length)]);
                    report.append(tokens[i]);
                }
                if (random.nextBoolean()) report.append(" \t\u0001");
                report.append('\n');
            }
            int appUid = round % 3 == 0 ? -1 : APP_UID;
            int shellUid = shells[round % shells.length];
            String text = report.toString();
            assertArrayEquals(referenceQtaguid(text, appUid, shellUid), qtaguid(text, appUid, shellUid));
        }
    }

    @Test public void qtaguidReadFailureDoesNotPublishPartialTotals() throws Exception {
        try (BufferedReader reader = new BufferedReader(new StringReader("")) {
            private boolean first = true;
            @Override public String readLine() throws IOException {
                if (first) {
                    first = false;
                    return "2 wlan0 0x0 2000 0 10 1 20 2";
                }
                throw new IOException("injected read failure");
            }
        }) {
            try {
                NetstatsCounters.readQtaguid(reader, APP_UID, SHELL_UID);
                fail("Partial totals must not be published");
            } catch (IOException expected) {
                // The caller falls back to dumpsys.
            }
        }
    }

    // The former DataUsageMonitor qtaguid loop, retained only as a reference oracle.
    static long[] referenceQtaguid(String report, int appUid, int shellUid) throws IOException {
        long wifi = 0, mobile = 0, other = 0, app = 0, system = 0;
        try (BufferedReader reader = new BufferedReader(new StringReader(report))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 8) continue;
                String iface = p[1];
                if ("iface".equals(iface) || "lo".equals(iface)) continue;
                if (!NetstatsCounters.isUntagged(p[2])) continue;
                int uid;
                try { uid = Integer.parseInt(p[3]); } catch (NumberFormatException e) { continue; }
                boolean isApp = appUid > 0 && uid == appUid;
                if (uid != shellUid && !isApp) continue;
                long rx, tx;
                try {
                    rx = Long.parseLong(p[5]);
                    tx = Long.parseLong(p[7]);
                } catch (NumberFormatException e) { continue; }
                long bytes = rx + tx;
                if (iface.startsWith("wlan")) wifi += bytes;
                else if (iface.startsWith("rmnet") || iface.startsWith("ccmni")
                        || iface.startsWith("rndis") || iface.startsWith("ppp")) mobile += bytes;
                else other += bytes;
                if (isApp) app += bytes; else system += bytes;
            }
        }
        return new long[]{wifi, mobile, other, app, system};
    }

}
