package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Guard behaviour against a fake Bluetooth stack and store; no device access. */
public class ParkedPhoneAudioGuardTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:FF";

    private final FakePlatform platform = new FakePlatform();
    private final FakeStore store = new FakeStore();
    private final long[] now = {100_000L};
    private final List<String> log = new ArrayList<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ParkedPhoneAudioGuard guard = newGuard();

    private ParkedPhoneAudioGuard newGuard() {
        return new ParkedPhoneAudioGuard(platform, store, () -> now[0], log::add, executor);
    }

    @After public void shutdown() { executor.shutdownNow(); }

    private void tick(boolean setting, int power, boolean connected) {
        guard.tick(PHONE, setting, true, () -> power, connected);
    }

    private void advance(long ms) { now[0] += ms; }

    @Test public void parkRefusesCallAudioAndDropsMediaThenPowerOnRestoresBoth() {
        platform.mediaConnected = true;
        tick(true, 0, true);
        assertFalse(platform.callAudioAllowed);
        assertFalse(platform.mediaConnected);
        assertEquals(1, platform.mediaDisconnects);
        assertTrue(store.records.containsKey(PHONE));
        assertEquals("blocked", guard.snapshot(PHONE).optString("state"));
        assertTrue(guard.snapshot(PHONE).optBoolean("callAudioRefused"));

        tick(true, 1, true);
        assertTrue(platform.callAudioAllowed);
        assertTrue(platform.mediaConnected);
        assertTrue(store.records.isEmpty());
        assertEquals("restored", guard.snapshot(PHONE).optString("state"));
        assertTrue(platform.released);
    }

    @Test public void recordIsWrittenBeforeTheChange() {
        platform.onSetAllowed = allowed -> {
            if (!allowed) assertTrue("record must exist first", store.records.containsKey(PHONE));
        };
        tick(true, 0, true);
        assertFalse(platform.callAudioAllowed);
    }

    @Test public void unsavableRecordChangesNothing() {
        store.failSaves = true;
        tick(true, 0, true);
        assertTrue(platform.callAudioAllowed);
        assertEquals(0, platform.setCalls);
        assertEquals("failed", guard.snapshot(PHONE).optString("state"));
    }

    @Test public void crashWhileParkedRestoresOnTheNextProcessAndKeepsTheOriginalPrior() {
        tick(true, 0, true);
        assertFalse(platform.callAudioAllowed);
        // New process: in-memory state is gone, the persisted record is not.
        guard = newGuard();
        // Parked again: re-apply must not overwrite the prior with the refused value.
        tick(true, 0, true);
        assertEquals(Boolean.TRUE, store.records.get(PHONE).priorAllowed);
        guard = newGuard();
        assertTrue(guard.restoreAll(() -> false));
        assertTrue(platform.callAudioAllowed);
        assertTrue(store.records.isEmpty());
    }

    @Test public void restoreReturnsAPriorRefusalUnchanged() {
        platform.callAudioAllowed = false;
        tick(true, 0, true);
        tick(true, 1, true);
        assertFalse(platform.callAudioAllowed);
        assertTrue(store.records.isEmpty());
    }

    @Test public void permissionRefusalMarksUnsupportedAndLeavesNoRecord() {
        platform.setFailure = new SecurityException("privileged");
        tick(true, 0, true);
        assertTrue(platform.callAudioAllowed);
        assertTrue(store.records.isEmpty());
        assertEquals("unsupported", guard.snapshot(PHONE).optString("state"));
        int calls = platform.setCalls;
        advance(ParkedPhoneAudioPolicy.RETRY_INTERVAL_MS);
        tick(true, 0, true);
        assertEquals("no retry this park", calls, platform.setCalls);
    }

    @Test public void transientFailureKeepsTheRecordAndRetriesUpToTheBudget() {
        platform.setFailure = new IllegalStateException("binder");
        tick(true, 0, true);
        assertTrue(store.records.containsKey(PHONE));
        advance(ParkedPhoneAudioPolicy.RETRY_INTERVAL_MS);
        tick(true, 0, true);
        int calls = platform.setCalls;
        assertEquals(ParkedPhoneAudioPolicy.MAX_APPLY_FAILURES, calls);
        advance(ParkedPhoneAudioPolicy.RETRY_INTERVAL_MS);
        tick(true, 0, true);
        assertEquals(calls, platform.setCalls);
        // Power-on still restores the record.
        platform.setFailure = null;
        tick(true, 1, true);
        assertTrue(store.records.isEmpty());
        assertTrue(platform.callAudioAllowed);
    }

    @Test public void ignoredRequestIsReportedAndNeedsNoRestore() {
        platform.ignoreSet = true;
        tick(true, 0, true);
        assertTrue(platform.callAudioAllowed);
        assertTrue(store.records.isEmpty());
        assertEquals("call_audio_not_honoured", guard.snapshot(PHONE).optString("detail"));
    }

    @Test public void reconnectedPhoneIsRefusedAgain() {
        tick(true, 0, true);
        tick(true, 0, false);
        // A new HFP state machine comes back with audio allowed.
        platform.callAudioAllowed = true;
        tick(true, 0, true);
        assertFalse(platform.callAudioAllowed);
    }

    @Test public void mediaThatKeepsComingBackIsLeftAloneAfterTheBudget() {
        platform.mediaConnected = true;
        tick(true, 0, true);
        for (int i = 0; i < 5; i++) {
            platform.mediaConnected = true;
            advance(ParkedPhoneAudioPolicy.MAINTAIN_INTERVAL_MS);
            tick(true, 0, true);
        }
        assertEquals(ParkedPhoneAudioPolicy.MAX_MEDIA_DISCONNECTS, platform.mediaDisconnects);
        assertEquals("media_keeps_reconnecting", guard.snapshot(PHONE).optString("detail"));
    }

    @Test public void mediaPermissionRefusalDoesNotReconnectMediaAtRestore() {
        platform.mediaConnected = true;
        platform.mediaFailure = new SecurityException("privileged");
        tick(true, 0, true);
        assertFalse(store.records.get(PHONE).mediaDisconnected);
        tick(true, 1, true);
        assertEquals(0, platform.mediaConnects);
    }

    @Test public void transientMediaFailureDoesNotReconnectMediaTheUserDroppedThemselves() {
        platform.mediaConnected = true;
        platform.mediaFailure = new IllegalStateException("busy");
        for (int i = 0; i < ParkedPhoneAudioPolicy.MAX_MEDIA_DISCONNECTS; i++) {
            tick(true, 0, true);
        }
        assertFalse(store.records.get(PHONE).mediaDisconnected);
        platform.mediaConnected = false; // dropped outside OverDrive
        tick(true, 1, true);
        assertEquals(0, platform.mediaConnects);
    }

    @Test public void failedRestoreStaysPendingAndRetries() {
        tick(true, 0, true);
        platform.setFailure = new IllegalStateException("radio");
        tick(true, 1, true);
        assertTrue(store.records.containsKey(PHONE));
        assertEquals("restore_pending", guard.snapshot(PHONE).optString("state"));
        platform.setFailure = null;
        advance(ParkedPhoneAudioPolicy.RETRY_INTERVAL_MS);
        tick(true, 1, true);
        assertTrue(store.records.isEmpty());
        assertTrue(platform.callAudioAllowed);
    }

    @Test public void restoreAllSkipsWhileStillWanted() {
        tick(true, 0, true);
        assertFalse(guard.restoreAll(() -> true));
        assertFalse(platform.callAudioAllowed);
        assertTrue(guard.restoreAll(() -> false));
        assertTrue(platform.callAudioAllowed);
    }

    @Test public void disabledFeatureWithNothingPendingNeverReadsPowerOrBluetooth() {
        AtomicBoolean powerRead = new AtomicBoolean();
        guard.tick(PHONE, false, true, () -> { powerRead.set(true); return 0; }, true);
        assertFalse(powerRead.get());
        assertEquals(0, platform.calls);
        assertEquals("off", guard.snapshot(PHONE).optString("state"));
    }

    @Test public void queuedTickAfterTheCollectorStopsRestoresInsteadOfApplying() throws Exception {
        tick(true, 0, true);
        AtomicBoolean active = new AtomicBoolean(true);
        active.set(false); // stop() happens after the worker queued its tick
        guard.submit(PHONE, true, active::get, () -> 0, true);
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertTrue(platform.callAudioAllowed);
        assertTrue(store.records.isEmpty());
    }

    @Test public void asyncRestoreRetriesUntilItSucceeds() throws Exception {
        tick(true, 0, true);
        platform.setFailure = new IllegalStateException("radio");
        guard.restoreAllAsync(() -> false);
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertTrue(store.records.containsKey(PHONE));
        platform.setFailure = null;
        assertTrue(guard.restoreAll(() -> false));
        assertTrue(platform.callAudioAllowed);
    }

    @Test public void permanentClassification() {
        assertTrue(ParkedPhoneAudioGuard.permanent(new SecurityException()));
        assertTrue(ParkedPhoneAudioGuard.permanent(new RuntimeException(new NoSuchMethodException())));
        assertTrue(ParkedPhoneAudioGuard.permanent(new UnsupportedOperationException()));
        assertFalse(ParkedPhoneAudioGuard.permanent(new IllegalStateException()));
    }

    @Test public void recordsRoundTripAndMalformedPriorMeansAllowed() {
        Map<String, ParkedPhoneAudioGuard.Record> records = new LinkedHashMap<>();
        records.put(PHONE, new ParkedPhoneAudioGuard.Record(false, true));
        records.put("11:22:33:44:55:66", new ParkedPhoneAudioGuard.Record(null, false));
        Map<String, ParkedPhoneAudioGuard.Record> decoded =
                AndroidParkedPhoneAudio.decode(AndroidParkedPhoneAudio.encode(records));
        assertEquals(Boolean.FALSE, decoded.get(PHONE).priorAllowed);
        assertTrue(decoded.get(PHONE).mediaDisconnected);
        assertNull(decoded.get("11:22:33:44:55:66").priorAllowed);
        assertTrue(AndroidParkedPhoneAudio.decode("not json").isEmpty());
        ParkedPhoneAudioGuard.Record odd = AndroidParkedPhoneAudio.decode("{\"" + PHONE + "\":1}").get(PHONE);
        assertNull(odd.priorAllowed);
        assertTrue(odd.mediaDisconnected);
    }

    private static final class FakePlatform implements ParkedPhoneAudioGuard.Platform {
        interface SetHook { void on(boolean allowed); }

        boolean callAudioAllowed = true;
        boolean mediaConnected;
        boolean ignoreSet;
        boolean released;
        int setCalls, mediaDisconnects, mediaConnects, calls;
        Exception setFailure, mediaFailure;
        SetHook onSetAllowed;

        @Override public boolean callAudioAllowed(String address) { calls++; return callAudioAllowed; }

        @Override public void setCallAudioAllowed(String address, boolean allowed) throws Exception {
            calls++;
            setCalls++;
            if (setFailure != null) throw setFailure;
            if (onSetAllowed != null) onSetAllowed.on(allowed);
            if (!ignoreSet) callAudioAllowed = allowed;
        }

        @Override public boolean mediaConnected(String address) { calls++; return mediaConnected; }

        @Override public boolean disconnectMedia(String address) throws Exception {
            calls++;
            mediaDisconnects++;
            if (mediaFailure != null) throw mediaFailure;
            mediaConnected = false;
            return true;
        }

        @Override public boolean connectMedia(String address) {
            calls++;
            mediaConnects++;
            mediaConnected = true;
            return true;
        }

        @Override public void release() { released = true; }
    }

    private static final class FakeStore implements ParkedPhoneAudioGuard.Store {
        Map<String, ParkedPhoneAudioGuard.Record> records = new HashMap<>();
        boolean failSaves;

        @Override public Map<String, ParkedPhoneAudioGuard.Record> load() { return new HashMap<>(records); }

        @Override public boolean save(Map<String, ParkedPhoneAudioGuard.Record> next) {
            if (failSaves) return false;
            records = new HashMap<>(next);
            return true;
        }
    }
}
