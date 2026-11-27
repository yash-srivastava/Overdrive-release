package com.overdrive.app.safekeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.TimeZone;

public class NamingTest {
    private static final long CAPTURE = Instant.parse("2024-02-29T14:03:12Z").toEpochMilli();
    private static final String EVENT = "event_20240229_140312_front.mp4";
    private static final String SIDECAR = "event_20240229_140312_front.json";
    private static final String FOLDER = "2024-02-29 14-03-12 Event";
    private TimeZone originalZone;

    @Before
    public void useFixedTimeZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(originalZone);
    }

    private static SafeKeepConfig config(String layout) throws Exception {
        JSONObject destination = new JSONObject()
                .put("id", "backup")
                .put("layout", layout)
                .put("params", new JSONObject().put("prefix", "/archive/"));
        return SafeKeepConfig.from(new JSONObject().put(SafeKeepConfig.SECTION,
                new JSONObject().put("vehicleName", "Test Car")
                        .put("vehicleId", "car-123")
                        .put("destinations", new JSONArray().put(destination))));
    }

    @Test
    public void classifiesRecordingNamesAndSidecars() {
        assertEquals(Naming.Kind.EVENT_CLIP, Naming.classify("EVENT_20240229_140312.MP4"));
        assertEquals(Naming.Kind.EVENT_SIDECAR, Naming.classify(SIDECAR));
        assertEquals(Naming.Kind.PROXIMITY_CLIP, Naming.classify("proximity_20240229_140312.mp4"));
        assertEquals(Naming.Kind.REPLAY_CLIP, Naming.classify("replay_20240229_140312.mp4"));
        assertEquals(Naming.Kind.DVR_CLIP, Naming.classify("dvr_20240229_140312.mp4"));
        assertEquals(Naming.Kind.LOOP_CLIP, Naming.classify("cam1_20240229_140312.mp4"));
        assertEquals(Naming.Kind.CLIP_SIDECAR, Naming.classify("cam1_20240229_140312.srt"));
        assertNull(Naming.classify("unrelated.mp4"));
        assertNull(Naming.classify(null));
    }

    @Test
    public void humanLayoutKeepsEventFilesTogether() throws Exception {
        SafeKeepConfig cfg = config("human");
        SafeKeepConfig.Destination dest = cfg.destination("backup");
        String folder = "archive/OverDrive/Test Car/Events/2024-02/" + FOLDER + "/";
        assertEquals(folder + EVENT,
                Naming.mediaPath(cfg, dest, Naming.Kind.EVENT_CLIP, EVENT, CAPTURE, FOLDER, false));
        assertEquals(folder + SIDECAR + ".age",
                Naming.mediaPath(cfg, dest, Naming.Kind.EVENT_SIDECAR, SIDECAR, CAPTURE, FOLDER, true));
    }

    @Test
    public void machineLayoutKeepsEventFilesTogether() throws Exception {
        SafeKeepConfig cfg = config("machine");
        SafeKeepConfig.Destination dest = cfg.destination("backup");
        String folder = "archive/overdrive/v1/car-123/events/2024/02/29/event_20240229_140312_front/";
        assertEquals(folder + EVENT,
                Naming.mediaPath(cfg, dest, Naming.Kind.EVENT_CLIP, EVENT, CAPTURE, FOLDER, false));
        assertEquals(folder + SIDECAR,
                Naming.mediaPath(cfg, dest, Naming.Kind.EVENT_SIDECAR, SIDECAR, CAPTURE, FOLDER, false));
    }

    @Test
    public void dashcamSidecarsAndLoopClipsUseTheirOwnDirectories() throws Exception {
        String dashcam = "dvr_20240229_140312.mp4";
        String sidecar = "dvr_20240229_140312.srt";
        String loop = "cam1_20240229_140312.mp4";
        SafeKeepConfig human = config("human");
        SafeKeepConfig.Destination humanDest = human.destination("backup");
        String humanRoot = "archive/OverDrive/Test Car/";
        assertEquals(humanRoot + "Dashcam/2024-02-29/" + dashcam,
                Naming.mediaPath(human, humanDest, Naming.Kind.DVR_CLIP, dashcam, CAPTURE, null, false));
        assertEquals(humanRoot + "Dashcam/2024-02-29/" + sidecar,
                Naming.mediaPath(human, humanDest, Naming.Kind.CLIP_SIDECAR, sidecar, CAPTURE, null, false));
        assertEquals(humanRoot + "Recordings/2024-02-29/" + loop,
                Naming.mediaPath(human, humanDest, Naming.Kind.LOOP_CLIP, loop, CAPTURE, null, false));

        SafeKeepConfig machine = config("machine");
        SafeKeepConfig.Destination machineDest = machine.destination("backup");
        String machineRoot = "archive/overdrive/v1/car-123/";
        assertEquals(machineRoot + "dvr/2024/02/29/" + sidecar,
                Naming.mediaPath(machine, machineDest, Naming.Kind.CLIP_SIDECAR, sidecar, CAPTURE, null, false));
        assertEquals(machineRoot + "loop/2024/02/29/" + loop + ".age",
                Naming.mediaPath(machine, machineDest, Naming.Kind.LOOP_CLIP, loop, CAPTURE, null, true));
    }

    @Test
    public void tripAndSettingsPathsPreserveExistingLayout() throws Exception {
        SafeKeepConfig human = config("human");
        SafeKeepConfig.Destination humanDest = human.destination("backup");
        String humanRoot = "archive/OverDrive/Test Car/";
        assertEquals(humanRoot + ".overdrive/trips/" + CAPTURE + ".json",
                Naming.tripRowPath(human, humanDest, CAPTURE, false));
        assertEquals(humanRoot + ".overdrive/telemetry/" + CAPTURE + ".jsonl.gz.age",
                Naming.telemetryPath(human, humanDest, CAPTURE, true));
        assertEquals(humanRoot + "Trips/2024-02/2024-02-29 14-03 12.5 km.gpx",
                Naming.gpxPath(human, humanDest, CAPTURE, 12.5, false));
        assertEquals(humanRoot + ".overdrive/settings/settings-20240229-140312.json.gz.age",
                Naming.settingsPath(human, humanDest, CAPTURE, true));

        SafeKeepConfig machine = config("machine");
        SafeKeepConfig.Destination machineDest = machine.destination("backup");
        String machineRoot = "archive/overdrive/v1/car-123/";
        assertEquals(machineRoot + "trips/" + CAPTURE + ".json.age",
                Naming.tripRowPath(machine, machineDest, CAPTURE, true));
        assertEquals(machineRoot + "telemetry/" + CAPTURE + ".jsonl.gz",
                Naming.telemetryPath(machine, machineDest, CAPTURE, false));
        assertEquals(machineRoot + "trips/gpx/2024/02/" + CAPTURE + ".gpx",
                Naming.gpxPath(machine, machineDest, CAPTURE, 12.5, false));
        assertEquals(machineRoot + "settings/settings-20240229-140312.json.gz",
                Naming.settingsPath(machine, machineDest, CAPTURE, false));
    }
}
