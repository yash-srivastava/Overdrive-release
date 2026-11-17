package com.overdrive.app.trips;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Guards the trip-start → GPS → telemetry journal chain documented for blank
 * trip-detail maps (distance from CAN, route from GpsMonitor lat/lon).
 */
public class TripGpsPipelineContractTest {

  @Test
  public void tripStartEnsuresGpsFeedBeforeRecorder() throws IOException {
    String src = read("app/src/main/java/com/overdrive/app/trips/TripAnalyticsManager.java");
    assertTrue(
        "handleTripStarted must wake LocationSidecar and reload cache before recording",
        src.contains("ensureTripRecordingFeed()"));
    assertTrue(
        "recorder must count GPS with the same predicate as the map API",
        read("app/src/main/java/com/overdrive/app/trips/TripTelemetryRecorder.java")
            .contains("TelemetrySample.hasGpsFix(lat, lon)"));
  }

  private static String read(String relative) throws IOException {
    Path p = Paths.get(relative);
    if (!Files.isRegularFile(p)) {
      p = Paths.get("..", relative);
    }
    return Files.readString(p, StandardCharsets.UTF_8);
  }
}