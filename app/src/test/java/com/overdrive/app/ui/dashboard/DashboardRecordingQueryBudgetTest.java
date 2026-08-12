package com.overdrive.app.ui.dashboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.ui.fragment.DashboardFragment;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DashboardRecordingQueryBudgetTest {

    @Test
    public void dashboardDoesNotMaterializeTheRecordingLibrary() throws IOException {
        String insights = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/dashboard/DashboardInsight.kt");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");

        assertFalse(insights.contains("RecordingScanner.scanRecordings"));
        assertFalse(fragment.contains("RecordingScanner.scanRecordings"));
        assertTrue(insights.contains("RecordingsApiClient.fetchStats()"));
        assertTrue(insights.contains("pageSize = 1"));
        assertTrue(fragment.contains("RecordingsApiClient.fetchStats()"));
    }

    @Test
    public void recordingInsightsRejectUnavailableOrWarmingStats() throws IOException {
        String insights = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/dashboard/DashboardInsight.kt");
        String fetchStats = methodBody(insights, "private fun fetchRecordingStats");

        assertTrue(fetchStats.contains("it.indexUnavailable || it.warming"));
    }

        @Test
        public void tileAcceptsOnlyAuthoritativeTodayCounts() {
        DashboardFragment.DashboardRecordingMetricPolicy policy =
            DashboardFragment.DashboardRecordingMetricPolicy.INSTANCE;

        assertNull(policy.authoritativeTodayCount(true, false, 12L));
        assertNull(policy.authoritativeTodayCount(false, true, 12L));
        assertNull(policy.authoritativeTodayCount(false, false, -1L));
        assertNull(policy.authoritativeTodayCount(
            false, false, ((long) Integer.MAX_VALUE) + 1L));
        assertEquals(Integer.valueOf(0),
            policy.authoritativeTodayCount(false, false, 0L));
        assertEquals(Integer.valueOf(12),
            policy.authoritativeTodayCount(false, false, 12L));
        }

        @Test
        public void newestSentryQueryReliesOnNewestFirstIndexOrdering() throws IOException {
        String insights = readRepositoryFile(
            "app/src/main/java/com/overdrive/app/ui/dashboard/DashboardInsight.kt");
        String index = readRepositoryFile(
            "app/src/main/java/com/overdrive/app/server/RecordingsIndex.java");

        String newest = methodBody(insights, "private fun fetchNewestSentryTimestamp");
        assertTrue(newest.contains("page = 1"));
        assertTrue(newest.contains("pageSize = 1"));
        assertTrue(index.contains("ORDER BY ts_ms DESC"));
        }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Could not locate " + signature);
        int openingBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openingBrace, i + 1);
            }
        }
        throw new AssertionError("Unbalanced method body for " + signature);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}