package com.overdrive.app.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NotificationLogPaginationTest {

    @Test
    public void missingAndMalformedValuesUseExistingDefaults() {
        assertPagination(null, null, 1, 20, 0);
        assertPagination("bad", "bad", 1, 20, 0);
        assertPagination("", "", 1, 20, 0);
    }

    @Test
    public void normalPagesAndExistingSizeLimitsArePreserved() {
        assertPagination("1", "20", 1, 20, 0);
        assertPagination("3", "20", 3, 20, 40);
        assertPagination(" 2 ", " 10 ", 2, 10, 10);
        assertPagination("0", "0", 1, 1, 0);
        assertPagination("-2", "201", 1, 200, 0);
    }

    @Test
    public void longValuesAreClampedBeforeConversionToInt() {
        assertPagination("4294967297", "4294967297",
                Integer.MAX_VALUE, 200, Integer.MAX_VALUE);
        assertPagination(Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE),
                Integer.MAX_VALUE, 200, Integer.MAX_VALUE);
        assertPagination(Long.toString(Long.MIN_VALUE), Long.toString(Long.MIN_VALUE),
                1, 1, 0);
    }

    @Test
    public void largePageOffsetsSaturateInsteadOfWrapping() {
        assertPagination("10737419", "200", 10737419, 200, 2147483600);
        assertPagination("10737420", "200", 10737420, 200, Integer.MAX_VALUE);
        assertPagination("2147483647", "1", Integer.MAX_VALUE, 1, Integer.MAX_VALUE - 1);
    }

    private static void assertPagination(String rawPage, String rawPageSize,
                                         int page, int pageSize, int offset) {
        NotificationApiHandler.LogPagination pagination =
                new NotificationApiHandler.LogPagination(rawPage, rawPageSize);
        assertEquals(page, pagination.page);
        assertEquals(pageSize, pagination.pageSize);
        assertEquals(offset, pagination.offset);
    }
}
