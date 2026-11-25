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
}
