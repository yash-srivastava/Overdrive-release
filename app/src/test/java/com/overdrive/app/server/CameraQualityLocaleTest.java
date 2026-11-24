package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality;
import com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality;

import java.util.Locale;
import org.junit.Test;

/** Camera quality identifiers are protocol values, not localized words. */
public class CameraQualityLocaleTest {

    @Test
    public void recordingQualityParsesIndependentlyOfTurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(RecordingQuality.HIGH, RecordingQuality.fromString("high"));
            assertEquals(RecordingQuality.PREMIUM, RecordingQuality.fromString("premium"));
            assertEquals(RecordingQuality.STANDARD, RecordingQuality.fromString("standard"));
            assertEquals(RecordingQuality.STANDARD, RecordingQuality.fromString(null));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void streamQualityParsesAndPersistsIndependentlyOfTurkishDefaultLocale() {
        Locale previousLocale = Locale.getDefault();
        String previousQuality = StreamingApiHandler.getStreamingQuality();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(StreamingQuality.MEDIUM, StreamingQuality.fromString("medium"));
            assertEquals(StreamingQuality.ULTRA_HIGH, StreamingQuality.fromString("ultra_high"));
            assertEquals(StreamingQuality.LOW, StreamingQuality.fromString("lq"));
            assertEquals(StreamingQuality.MEDIUM, StreamingQuality.fromString(null));

            StreamingApiHandler.setStreamingQuality("medium");
            assertEquals("MEDIUM", StreamingApiHandler.getStreamingQuality());
            StreamingApiHandler.setStreamingQuality("ultra_high");
            assertEquals("ULTRA_HIGH", StreamingApiHandler.getStreamingQuality());
        } finally {
            StreamingApiHandler.setStreamingQuality(previousQuality);
            Locale.setDefault(previousLocale);
        }
    }
}
