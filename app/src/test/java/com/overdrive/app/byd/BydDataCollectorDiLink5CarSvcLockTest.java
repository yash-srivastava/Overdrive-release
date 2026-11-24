package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Stage 0 daemon diagnostic: the {@code DI5 car_service locks:} line on the charging poll. */
public class BydDataCollectorDiLink5CarSvcLockTest {

    private static String lockLine(int propId, int value, Integer status, String ts) {
        StringBuilder sb = new StringBuilder("lastEvent:Property:0x")
                .append(Integer.toHexString(propId)).append(',');
        if (status != null) sb.append("status: ").append(status).append(',');
        if (ts != null) sb.append("timestamp:").append(ts).append(',');
        sb.append("zone:0x0,floatValues: [],int32Values: [").append(value)
                .append("],int64Values: [],bytes: [],string:");
        return sb.toString();
    }

    @Test
    public void lockedLfIsMappedToLockWithEveryToken() {
        String text = lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 2, 0, "7000000000") + "\n"
                + lockLine(CarSvcTelemetry.RF_LOCK_PROP_ID, 2, 0, "7000000000");
        String line = BydDataCollector.formatDiLink5CarSvcLockDiagnostics(
                text, 9_500L, 320L, 10_000L, 10_000_000_000L, 9_000_000_000L,
                1_760_000_000_000L);
        assertEquals(
                "DI5 car_service locks: lf={v=2,status=0,ts=7000000000,ageS=3,lines=1}"
                        + " rf={v=2,status=0,ts=7000000000,ageS=3,lines=1} lr=absent rr=absent"
                        + " lfMapped=LOCK tsDomain=elapsed dumpAgeMs=500 dumpDurationMs=320"
                        + " nowElapsedNs=10000000000 nowUptimeNs=9000000000"
                        + " nowEpochMs=1760000000000",
                line);
    }

    @Test
    public void unlockedAndOtherValuesMapToUnlockAndInvalid() {
        String unlocked = lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 1, 0, "1");
        assertTrue(BydDataCollector.formatDiLink5CarSvcLockDiagnostics(
                unlocked, 0L, 0L, 0L, 0L, 0L, 0L).contains(" lfMapped=UNLOCK "));
        String other = lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 0, 2, "1");
        assertTrue(BydDataCollector.formatDiLink5CarSvcLockDiagnostics(
                other, 0L, 0L, 0L, 0L, 0L, 0L).contains(" lfMapped=INVALID "));
        assertEquals(1, BydDataCollector.CARSVC_LF_UNLOCKED);
        assertEquals(2, BydDataCollector.CARSVC_LF_LOCKED);
    }

    @Test
    public void absentLfIsInvalidAndUnknownDomain() {
        String line = BydDataCollector.formatDiLink5CarSvcLockDiagnostics(
                "", 0L, 0L, 0L, 0L, 0L, 0L);
        assertTrue(line, line.contains("lf=absent"));
        assertTrue(line, line.contains(" lfMapped=INVALID tsDomain=unknown "));
        String noDumpText = BydDataCollector.formatDiLink5CarSvcLockDiagnostics(
                null, 0L, 0L, 0L, 0L, 0L, 0L);
        assertTrue(noDumpText, noDumpText.contains(
                "lf=absent rf=absent lr=absent rr=absent lfMapped=INVALID tsDomain=unknown"));
    }

    @Test
    public void tsDomainClassifiesEachClockWithinFiveSeconds() {
        long elapsedNs = 50_000_000_000L;
        long uptimeNs = 30_000_000_000L;
        long epochMs = 1_760_000_000_000L;
        assertEquals("elapsed", BydDataCollector.classifyDiLink5CarSvcTsDomain(
                elapsedNs - 4_999_999_999L, elapsedNs, uptimeNs, epochMs));
        assertEquals("uptime", BydDataCollector.classifyDiLink5CarSvcTsDomain(
                uptimeNs + 5_000_000_000L, elapsedNs, uptimeNs, epochMs));
        assertEquals("epoch", BydDataCollector.classifyDiLink5CarSvcTsDomain(
                epochMs - 4_000L, elapsedNs, uptimeNs, epochMs));
        assertEquals("epoch", BydDataCollector.classifyDiLink5CarSvcTsDomain(
                epochMs * 1_000_000L + 1_000_000_000L, elapsedNs, uptimeNs, epochMs));
        assertEquals("unknown", BydDataCollector.classifyDiLink5CarSvcTsDomain(
                uptimeNs - 5_000_000_001L, elapsedNs, uptimeNs, epochMs));
        assertEquals("unknown", BydDataCollector.classifyDiLink5CarSvcTsDomain(
                0L, elapsedNs, uptimeNs, epochMs));
    }

    @Test
    public void tupleKeyChangesOnAnyPropertyAndIgnoresUnrelatedRows() {
        String a = lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 2, 0, "100");
        String b = lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 2, 0, "101");
        String c = a + "\n" + lockLine(0x21403407, 0, 2, "999");
        assertFalse(BydDataCollector.diLink5CarSvcLockTupleKey(a)
                .equals(BydDataCollector.diLink5CarSvcLockTupleKey(b)));
        assertEquals(BydDataCollector.diLink5CarSvcLockTupleKey(a),
                BydDataCollector.diLink5CarSvcLockTupleKey(c));
        assertEquals("absent|absent|absent|absent|",
                BydDataCollector.diLink5CarSvcLockTupleKey(""));
    }

    @Test
    public void chargingPollLogsLockDiagnosticsBeforeItsEarlyReturn() throws Exception {
        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int start = collector.indexOf("    private void pollDiLink5CarSvcCharging() {");
        assertTrue(start >= 0);
        int read = collector.indexOf(
                "CarSvcTelemetry.ChargingSignals s = CarSvcTelemetry.readChargingSignals();",
                start);
        int diag = collector.indexOf("logDiLink5CarSvcLockDiagnostics();", start);
        int ret = collector.indexOf(
                "if (s == null || Thread.currentThread().isInterrupted()) return;", start);
        assertTrue(read > start && diag > read && ret > diag);
        // The diagnostic call is fenced so the charging poll can never be broken by it.
        String between = collector.substring(read, ret);
        assertTrue(between.contains("try {"));
        assertTrue(between.contains("} catch (Throwable t) {"));

        int method = collector.indexOf("    private void logDiLink5CarSvcLockDiagnostics() {");
        int methodEnd = collector.indexOf("\n    }\n", method);
        String body = collector.substring(method, methodEnd);
        assertTrue(body.contains("CarSvcTelemetry.cachedDump()"));
        assertFalse(body.contains("dumpsysText("));
        assertFalse(body.contains("readChargingSignals("));
        assertFalse(body.contains("dumpSnapshot("));
        assertTrue(body.contains("\"DI5 car_service locks: no dump\""));
        assertTrue(body.contains("\"DI5 car_service dump stats: \" + CarSvcTelemetry.dumpStats()"));
        assertTrue(collector.contains("static final int CARSVC_LF_UNLOCKED = 1;"));
        assertTrue(collector.contains("static final int CARSVC_LF_LOCKED = 2;"));
        assertTrue(collector.contains("UNVERIFIED on this car"));
    }

    // ---- Stage 1, item 1.5a: the carsvc rail evaluator, poll guard, cadence and budgets ---------

    private static final int INVALID = BydDataCollector.DOOR_STATE_INVALID;
    private static final int UNLOCK = BydDataCollector.DOOR_STATE_UNLOCK;
    private static final int LOCK = BydDataCollector.DOOR_STATE_LOCK;
    /**
     * The injected HAL-event age bound for the rule tests below: 900 s, the Stage 1 starting value
     * (an explicit setting value, independent of the default). The default itself (24 h since
     * 55.5) is pinned by {@link #defaultEventAgeBoundIsADayAndEqualsTheSettingsDefault()}.
     */
    private static final long MAX_AGE_NS = 900L * 1_000_000_000L;
    /** A dump stamped at 100 s elapsed, completed 300 ms later; HAL event 60 s before the dump. */
    private static final long DUMP_AT = 100_000L;
    private static final long DURATION = 300L;
    private static final long NOW = DUMP_AT + DURATION;
    private static final long NOW_NS = NOW * 1_000_000L;
    private static final long HAL_TS = (DUMP_AT - 60_000L) * 1_000_000L;

    private static CarSvcTelemetry.LockEvent event(Integer value, Integer status, Long ts) {
        return new CarSvcTelemetry.LockEvent(value, status, ts, 1);
    }

    private static BydDataCollector.DiLink5CarSvcLockObservation evaluate(
            CarSvcTelemetry.LockEvent lf, long dumpAt, long now, long nowNs) {
        return BydDataCollector.evaluateDiLink5CarSvcLock(lf, dumpAt, now, nowNs, MAX_AGE_NS);
    }

    @Test
    public void dumpAgeRuleAcceptsTheTtlAndRejectsOneMillisecondMore() {
        CarSvcTelemetry.LockEvent locked = event(2, 0, HAL_TS);
        long ttl = CarSvcTelemetry.DUMP_TTL_MS;
        assertEquals(LOCK, evaluate(locked, DUMP_AT, DUMP_AT + ttl, (DUMP_AT + ttl) * 1_000_000L).state);
        assertEquals(INVALID,
                evaluate(locked, DUMP_AT, DUMP_AT + ttl + 1L, (DUMP_AT + ttl + 1L) * 1_000_000L).state);
        // A dump stamped after "now" is not evidence either.
        assertEquals(INVALID, evaluate(locked, DUMP_AT + 1L, DUMP_AT, DUMP_AT * 1_000_000L).state);
    }

    @Test
    public void statusMustBeExactlyZero() {
        assertEquals(LOCK, evaluate(event(2, 0, HAL_TS), DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(event(2, 1, HAL_TS), DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(event(2, 2, HAL_TS), DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(event(2, null, HAL_TS), DUMP_AT, NOW, NOW_NS).state);
        // The parsed row form behaves the same: an explicit status line and a missing one.
        CarSvcTelemetry.LockEvent parsedOk = CarSvcTelemetry.parseLockEvent(
                lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 2, 0, Long.toString(HAL_TS)),
                CarSvcTelemetry.LF_LOCK_PROP_ID);
        CarSvcTelemetry.LockEvent parsedNoStatus = CarSvcTelemetry.parseLockEvent(
                lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 2, null, Long.toString(HAL_TS)),
                CarSvcTelemetry.LF_LOCK_PROP_ID);
        assertEquals(LOCK, evaluate(parsedOk, DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(parsedNoStatus, DUMP_AT, NOW, NOW_NS).state);
    }

    @Test
    public void halEventAgeBoundIsInclusiveInTheElapsedRealtimeDomain() {
        long atBound = NOW_NS - MAX_AGE_NS;
        assertEquals(LOCK, evaluate(event(2, 0, atBound), DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(event(2, 0, atBound - 1L), DUMP_AT, NOW, NOW_NS).state);
        // Missing timestamp, or a timestamp in the future, is not evidence.
        assertEquals(INVALID, evaluate(event(2, 0, null), DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(event(2, 0, NOW_NS + 1L), DUMP_AT, NOW, NOW_NS).state);
        // The bound is the injected setting value: a tighter bound rejects the same event.
        assertEquals(INVALID, BydDataCollector.evaluateDiLink5CarSvcLock(
                event(2, 0, HAL_TS), DUMP_AT, NOW, NOW_NS, 59_999_999_999L).state);
        assertEquals(LOCK, BydDataCollector.evaluateDiLink5CarSvcLock(
                event(2, 0, HAL_TS), DUMP_AT, NOW, NOW_NS, 60_300_000_000L).state);
        assertEquals("elapsed", BydDataCollector.CARSVC_TS_DOMAIN_ELAPSED_REALTIME);
        assertEquals(BydDataCollector.CARSVC_TS_DOMAIN_ELAPSED_REALTIME,
                BydDataCollector.CARSVC_TS_DOMAIN);
    }

    @Test
    public void outOfDomainValuesAreInvalidButKeepTheirRaw() {
        BydDataCollector.DiLink5CarSvcLockObservation three =
                evaluate(event(3, 0, HAL_TS), DUMP_AT, NOW, NOW_NS);
        assertEquals(INVALID, three.state);
        assertEquals(3, three.raw);
        assertEquals(HAL_TS, three.halTimestampNs);
        assertEquals(INVALID, evaluate(event(0, 0, HAL_TS), DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(event(-1, 0, HAL_TS), DUMP_AT, NOW, NOW_NS).state);
        assertEquals(INVALID, evaluate(event(null, 0, HAL_TS), DUMP_AT, NOW, NOW_NS).state);
    }

    /**
     * The ONLY place to edit once the Stage 0 result note records this car's car_service LF
     * encoding: today 1 → UNLOCK and 2 → LOCK (assumed app/OTA domain, UNVERIFIED); an inversion
     * found on the car flips the two expected states here and the two constants in the collector.
     */
    @Test
    public void carsvcValueMappingMatchesStage0Note() {
        BydDataCollector.DiLink5CarSvcLockObservation unlocked =
                evaluate(event(BydDataCollector.CARSVC_LF_UNLOCKED, 0, HAL_TS), DUMP_AT, NOW, NOW_NS);
        BydDataCollector.DiLink5CarSvcLockObservation locked =
                evaluate(event(BydDataCollector.CARSVC_LF_LOCKED, 0, HAL_TS), DUMP_AT, NOW, NOW_NS);
        assertEquals(UNLOCK, unlocked.state);
        assertEquals(1, unlocked.raw);
        assertEquals(LOCK, locked.state);
        assertEquals(2, locked.raw);
        assertEquals(HAL_TS, locked.halTimestampNs);
        assertEquals(1, BydDataCollector.CARSVC_LF_UNLOCKED);
        assertEquals(2, BydDataCollector.CARSVC_LF_LOCKED);
    }

    @Test
    public void absentLfKeyIsInvalidWithNoRaw() {
        BydDataCollector.DiLink5CarSvcLockObservation absent = evaluate(null, DUMP_AT, NOW, NOW_NS);
        assertEquals(INVALID, absent.state);
        assertEquals(Integer.MIN_VALUE, absent.raw);
        assertEquals(0L, absent.halTimestampNs);
        // A dump without the LF row parses to null and therefore evaluates to INVALID.
        String rfOnly = lockLine(CarSvcTelemetry.RF_LOCK_PROP_ID, 2, 0, Long.toString(HAL_TS));
        assertEquals(INVALID, evaluate(CarSvcTelemetry.parseLockEvent(
                rfOnly, CarSvcTelemetry.LF_LOCK_PROP_ID), DUMP_AT, NOW, NOW_NS).state);
    }

    @Test
    public void absentLfAfterAValidObservationRevokesItNoStickyValue() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        BydDataCollector.DiLink5LockEvidenceLedger ledger =
                new BydDataCollector.DiLink5LockEvidenceLedger(lines::add);
        ledger.setEnabledRails(java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC));
        BydDataCollector.DiLink5CarSvcLockObservation locked =
                evaluate(event(2, 0, HAL_TS), DUMP_AT, NOW, NOW_NS);
        assertTrue(ledger.observe(DiLink5LockRail.CARSVC, locked.state, DUMP_AT, NOW, locked.raw,
                locked.halTimestampNs));
        assertEquals(LOCK, ledger.current(NOW));
        BydDataCollector.DiLink5LockEvidence evidence = ledger.evidence(NOW);
        assertEquals(DiLink5LockRail.CARSVC, evidence.rail);
        assertEquals(2, evidence.raw);
        assertEquals(HAL_TS, evidence.halTimestampNs);
        assertEquals(DUMP_AT, evidence.observedAtElapsedMs);
        // Next dump (6 s later) has no LF row: INVALID at that dump's time revokes the held LOCK.
        long nextDump = DUMP_AT + 6_000L;
        BydDataCollector.DiLink5CarSvcLockObservation absent =
                evaluate(null, nextDump, nextDump + DURATION, (nextDump + DURATION) * 1_000_000L);
        assertTrue(ledger.observe(DiLink5LockRail.CARSVC, absent.state, nextDump,
                nextDump + DURATION, absent.raw, absent.halTimestampNs));
        assertEquals(INVALID, ledger.current(nextDump + DURATION));
    }

    /** B-T6: the collector's default bound equals the settings default, and both are 24 h. */
    @Test
    public void defaultEventAgeBoundIsADayAndEqualsTheSettingsDefault() {
        assertEquals(86_400L, BydDataCollector.DILINK5_CARSVC_LOCK_MAX_EVENT_AGE_DEFAULT_SEC);
        assertEquals(com.overdrive.app.remoteaccess.RemoteAccessSettings.DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_DEFAULT,
                BydDataCollector.DILINK5_CARSVC_LOCK_MAX_EVENT_AGE_DEFAULT_SEC);
        // Bounds are unchanged; the default sits at the maximum.
        assertEquals(60L, BydDataCollector.DILINK5_CARSVC_LOCK_MAX_EVENT_AGE_MIN_SEC);
        assertEquals(86_400L, BydDataCollector.DILINK5_CARSVC_LOCK_MAX_EVENT_AGE_MAX_SEC);
        assertEquals(com.overdrive.app.remoteaccess.RemoteAccessSettings.DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MIN,
                BydDataCollector.DILINK5_CARSVC_LOCK_MAX_EVENT_AGE_MIN_SEC);
        assertEquals(com.overdrive.app.remoteaccess.RemoteAccessSettings.DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MAX,
                BydDataCollector.DILINK5_CARSVC_LOCK_MAX_EVENT_AGE_MAX_SEC);
        // With the 24 h bound a LOCK event from a 65-minute park (the 17 Oct long-park check) is
        // still evidence, while the old 900 s bound would have rejected it.
        long dayNs = BydDataCollector.DILINK5_CARSVC_LOCK_MAX_EVENT_AGE_DEFAULT_SEC * 1_000_000_000L;
        long parkNs = 65L * 60L * 1_000_000_000L;
        long now = 2L * 60L * 60L * 1_000L;   // 2 h elapsed, in ms
        long nowNs = now * 1_000_000L;
        assertEquals(LOCK, BydDataCollector.evaluateDiLink5CarSvcLock(
                event(2, 0, nowNs - parkNs), now - DURATION, now, nowNs, dayNs).state);
        assertEquals(INVALID, BydDataCollector.evaluateDiLink5CarSvcLock(
                event(2, 0, nowNs - parkNs), now - DURATION, now, nowNs, MAX_AGE_NS).state);
        assertEquals(INVALID, BydDataCollector.evaluateDiLink5CarSvcLock(
                event(2, 0, nowNs - dayNs - 1L), now - DURATION, now, nowNs, dayNs).state);
    }

    /**
     * B-T8: the NEW default rail set ({@code typed-cabin,carsvc}, read through the settings
     * accessor) polls exactly as the enabled set already tested below, while the explicit typed-only
     * rollback set stays inactive.
     */
    @Test
    public void carsvcPollFollowsTheNewDefaultRailSet() throws Exception {
        java.util.Set<DiLink5LockRail> defaults =
                com.overdrive.app.remoteaccess.RemoteAccessSettings.di5LockRails(
                        com.overdrive.app.remoteaccess.RemoteAccessSettings.defaults());
        assertEquals(java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC), defaults);
        assertTrue(BydDataCollector.shouldPollDiLink5CarSvcLock(defaults, false, true));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(defaults, true, true));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(defaults, false, false));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(defaults, true, false));
        java.util.Set<DiLink5LockRail> rollback =
                com.overdrive.app.remoteaccess.RemoteAccessSettings.di5LockRails(
                        new org.json.JSONObject().put("di5LockRails", "typed-cabin"));
        assertEquals(java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN), rollback);
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(rollback, false, true));
    }

    /**
     * D3: with carsvc in the default set, nothing may dump {@code car_service} or enable ledger
     * rails outside the DiLink 5 daemon process. Pinned by source: the executor that hosts the poll
     * is created only for the Di5 bridge consumer, every tick and burst re-checks that predicate,
     * the gate reads return before their carsvc refresh on non-Di5, and both handler enablement
     * sites sit inside {@code DiLink5Platform.isSelected()} blocks.
     */
    @Test
    public void carsvcPollAndLedgerEnablementAreConfinedToTheDi5Daemon() throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(collector.contains(
                "        boolean bridgeConsumer = isDiLink5Vehicle() && !isMainAppProcess();\n"
                        + "        diLink5BridgeConsumer = bridgeConsumer;"));
        assertEquals(1, count(collector, "startDiLink5CarSvcChargingPoll();"));
        assertTrue(collector.contains("        if (bridgeConsumer) startDiLink5CarSvcChargingPoll();"));
        assertTrue(collector.contains(
                "    private boolean isDiLink5BridgeConsumer() {\n"
                        + "        return diLink5BridgeConsumer && isDiLink5Vehicle() && !isMainAppProcess();\n"
                        + "    }"));
        assertTrue(collector.contains(
                "    private static boolean isDiLink5Vehicle() {\n"
                        + "        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();"));
        assertTrue(collector.contains(
                "    private void pollDiLink5CarSvcLock() {\n"
                        + "        if (!isDiLink5BridgeConsumer()) return;"));
        assertTrue(collector.contains(
                "    public void requestDiLink5CarSvcLockBurst(long windowMs) {\n"
                        + "        if (!isDiLink5BridgeConsumer()"));
        assertTrue(collector.contains(
                "    public int readDiLink5GateDoorLockState() {\n"
                        + "        if (!isDiLink5Vehicle() || isMainAppProcess()) return DOOR_STATE_INVALID;"));
        assertTrue(collector.contains(
                "    public DiLink5LockEvidence readDiLink5GateDoorLockEvidence() {\n"
                        + "        if (!isDiLink5Vehicle() || isMainAppProcess()) return DiLink5LockEvidence.unknown();"));
        // The collector's own settings mirror runs only inside the (Di5-only) lock tick.
        assertEquals(1, count(collector, "refreshDiLink5CarSvcLockSettings(now);"));
        // The only other dump trigger on this path is the Di5-only lock tick.
        int tick = collector.indexOf("    private void pollDiLink5CarSvcLock() {");
        int tickEnd = collector.indexOf("\n    }\n", tick);
        assertTrue(collector.substring(tick, tickEnd).contains("CarSvcTelemetry.INSTANCE.dumpsysText();"));

        String handler = read("app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java");
        assertEquals(2, count(handler, "setDiLink5LockRails("));
        int refresh = handler.indexOf("    private static void refreshSettings() throws Exception {");
        String refreshBody = handler.substring(refresh, handler.indexOf("\n    }\n", refresh));
        int refreshGuard = refreshBody.indexOf("if (DiLink5Platform.isSelected()) {");
        int refreshCall = refreshBody.indexOf("collector.setDiLink5LockRails(");
        assertTrue(refreshGuard > 0 && refreshCall > refreshGuard);
        assertFalse(refreshBody.substring(refreshGuard, refreshCall).contains("\n        }"));
        int decision = handler.indexOf("    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {");
        int decisionGuard = handler.indexOf("if (DiLink5Platform.isSelected()) {", decision);
        int decisionCall = handler.indexOf("gate.setDiLink5LockRails(", decision);
        assertTrue(decision > 0 && decisionGuard > decision && decisionCall > decisionGuard);
        assertFalse(handler.substring(decisionGuard, decisionCall).contains("\n            }"));
        // The handler's burst request is reachable only for a carsvc actuation rail, which needs
        // Di5 evidence (null outside Di5).
        assertTrue(handler.contains(
                "if (actuationRail == DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(12_000L);"));
        assertTrue(handler.contains(
                "DiLink5Platform.isSelected() ? collector.readDiLink5GateDoorLockEvidence() : null;"));
    }

    @Test
    public void carsvcPollIsInactiveUnlessEnabled() {
        java.util.Set<DiLink5LockRail> typedOnly = java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN);
        java.util.Set<DiLink5LockRail> withCarsvc =
                java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC);
        // Explicit typed-only setting (the rollback value): never polls, whatever ACC and the
        // feature flag say.
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(typedOnly, false, true));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(typedOnly, true, true));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(typedOnly, false, false));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(null, false, true));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(
                java.util.EnumSet.noneOf(DiLink5LockRail.class), false, true));
        // Enabled: polls while ACC is not authoritatively ON (unknown ACC → polls) and the
        // remote-access feature is enabled.
        assertTrue(BydDataCollector.shouldPollDiLink5CarSvcLock(withCarsvc, false, true));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(withCarsvc, true, true));
        assertFalse(BydDataCollector.shouldPollDiLink5CarSvcLock(withCarsvc, false, false));
        assertTrue(BydDataCollector.shouldPollDiLink5CarSvcLock(
                java.util.EnumSet.of(DiLink5LockRail.CARSVC), false, true));
        // The tick passes "isAccStateAuthoritative() && isAccOn()": an unknown (non-authoritative)
        // ACC state is therefore `false` here and the poll RUNS (LOW-2, fail toward evidence).
        assertTrue(BydDataCollector.shouldPollDiLink5CarSvcLock(withCarsvc, false, true));
    }

    /** One fixed-rate carsvc tick: dump stamped at {@code tickAt}, completed {@code DURATION} later. */
    private static void tick(BydDataCollector.DiLink5LockEvidenceLedger ledger, long tickAt) {
        long now = tickAt + DURATION;
        BydDataCollector.DiLink5CarSvcLockObservation o = BydDataCollector.evaluateDiLink5CarSvcLock(
                event(2, 0, HAL_TS), tickAt, now, now * 1_000_000L, MAX_AGE_NS);
        assertEquals(LOCK, o.state);
        assertTrue(ledger.observe(DiLink5LockRail.CARSVC, o.state, tickAt, now, o.raw,
                o.halTimestampNs));
    }

    private static BydDataCollector.DiLink5LockEvidenceLedger carsvcLedger() {
        BydDataCollector.DiLink5LockEvidenceLedger ledger =
                new BydDataCollector.DiLink5LockEvidenceLedger(s -> { });
        ledger.setEnabledRails(java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC));
        return ledger;
    }

    /**
     * Replays fixed-rate ticks at {@code t + k * period} against a real clock sweep: a tick whose
     * dump is discarded (slow/timeout/failed) observes nothing; the others land {@code DURATION}
     * after their stamp, exactly when the daemon would observe them.
     */
    private static void replayTicks(BydDataCollector.DiLink5LockEvidenceLedger ledger, long t,
            boolean[] discarded, long now) {
        long period = BydDataCollector.DILINK5_CARSVC_LOCK_POLL_MS;
        for (int k = 0; k < discarded.length; k++) {
            if (!discarded[k] && now == t + k * period + DURATION) tick(ledger, t + k * period);
        }
    }

    @Test
    public void fixedRateCadenceToleratesOneDiscardedDump() {
        BydDataCollector.DiLink5LockEvidenceLedger ledger = carsvcLedger();
        long t = DUMP_AT;
        boolean[] discarded = {false, true, false};   // T ok, T+6 discarded, T+12 ok
        for (long now = t + DURATION; now <= t + 14_000L; now++) {
            replayTicks(ledger, t, discarded, now);
            assertEquals("at +" + (now - t), LOCK, ledger.current(now));
        }
    }

    @Test
    public void twoConsecutiveDiscardsOpenAGapOfAtMostFiveSeconds() {
        BydDataCollector.DiLink5LockEvidenceLedger ledger = carsvcLedger();
        long t = DUMP_AT;
        long period = BydDataCollector.DILINK5_CARSVC_LOCK_POLL_MS;
        long window = BydDataCollector.DILINK5_AUTOMATION_MAX_AGE_MS;
        boolean[] discarded = {false, true, true, false};   // T ok, T+6 miss, T+12 miss, T+18 ok
        // Known until the first observation's window closes, unknown until the T+18 dump lands.
        long recoveredAt = t + 3 * period + DURATION;
        long firstInvalid = -1L;
        long lastInvalid = -1L;
        for (long now = t + DURATION; now <= t + 30_000L; now++) {
            replayTicks(ledger, t, discarded, now);
            int state = ledger.current(now);
            boolean expectedKnown = now <= t + window || now >= recoveredAt;
            assertEquals("at +" + (now - t), expectedKnown ? LOCK : INVALID, state);
            if (state == INVALID) {
                if (firstInvalid < 0L) firstInvalid = now;
                lastInvalid = now;
            }
        }
        assertEquals(t + window + 1L, firstInvalid);
        assertEquals(recoveredAt - 1L, lastInvalid);
        assertTrue("gap " + (lastInvalid - firstInvalid + 1L), lastInvalid - firstInvalid + 1L <= 5_000L);
    }

    @Test
    public void burstWindowPredicatesAreBoundedAndNeverHot() {
        long now = 500_000L;
        assertEquals(now + 12_000L, BydDataCollector.diLink5CarSvcLockBurstUntil(now, 20_000L));
        assertEquals(now + 12_000L, BydDataCollector.diLink5CarSvcLockBurstUntil(now, 12_000L));
        assertEquals(now + 5_000L, BydDataCollector.diLink5CarSvcLockBurstUntil(now, 5_000L));
        assertEquals(now, BydDataCollector.diLink5CarSvcLockBurstUntil(now, -1L));
        assertFalse(BydDataCollector.shouldContinueDiLink5CarSvcLockBurst(now, 0L));
        assertFalse(BydDataCollector.shouldContinueDiLink5CarSvcLockBurst(now, now));
        assertTrue(BydDataCollector.shouldContinueDiLink5CarSvcLockBurst(now, now + 1L));
        assertFalse(BydDataCollector.shouldContinueDiLink5CarSvcLockBurst(now + 12_000L, now + 12_000L));
        // Pause = until the dump's 2 s source TTL lapses (then dumpsysText really spawns again).
        long until = now + 12_000L;
        assertEquals(1_501L, BydDataCollector.diLink5CarSvcLockBurstPauseMs(now, now - 500L, until));
        assertEquals(2_000L, BydDataCollector.diLink5CarSvcLockBurstPauseMs(now, now, until));
        // Never past the deadline, never below 100 ms, a quarter TTL with no dump at all.
        assertEquals(300L, BydDataCollector.diLink5CarSvcLockBurstPauseMs(now, now - 500L, now + 300L));
        assertEquals(100L, BydDataCollector.diLink5CarSvcLockBurstPauseMs(now, now - 2_500L, until));
        assertEquals(100L, BydDataCollector.diLink5CarSvcLockBurstPauseMs(now, now - 500L, now + 10L));
        assertEquals(500L, BydDataCollector.diLink5CarSvcLockBurstPauseMs(now, 0L, until));
        assertEquals(100L, BydDataCollector.DILINK5_CARSVC_LOCK_BURST_MIN_PAUSE_MS);
        // Six spawns fit in one capped burst.
        assertTrue(BydDataCollector.DILINK5_CARSVC_LOCK_BURST_MAX_MS
                >= 6 * CarSvcTelemetry.DUMP_TTL_MS);
    }

    /**
     * M3: every carsvc timing budget asserted against the others. The two actuator-side bounds
     * ({@code DILINK5_GATE_EVIDENCE_WRITE_MAX_AGE_MS}, {@code MIN_ACTUATION_LEASE_MS}) are plan items
     * 1.6c/1.6a; their agreed values are pinned here so the rail cannot drift from them.
     */
    @Test
    public void timingBudgetsAreMutuallyConsistent() {
        long dumpTtl = CarSvcTelemetry.DUMP_TTL_MS;
        long poll = BydDataCollector.DILINK5_CARSVC_LOCK_POLL_MS;
        long window = BydDataCollector.DILINK5_AUTOMATION_MAX_AGE_MS;
        long burst = BydDataCollector.DILINK5_CARSVC_LOCK_BURST_MAX_MS;
        long gateEvidenceWriteMaxAgeMs = 6_000L;   // DILINK5_GATE_EVIDENCE_WRITE_MAX_AGE_MS (1.6c)
        long minActuationLeaseMs = 1_500L;          // MIN_ACTUATION_LEASE_MS (1.6a)
        assertEquals(2_000L, dumpTtl);
        assertEquals(6_000L, poll);
        assertEquals(15_000L, window);
        assertEquals(12_000L, burst);
        // One-miss tolerance: two polls plus a full dump TTL still fit in the gate window.
        assertTrue(2 * poll + dumpTtl <= window);
        // Write-time bound covers dump TTL + dump duration budget + two scheduling slacks, and
        // sits strictly inside the gate window.
        assertTrue(gateEvidenceWriteMaxAgeMs >= dumpTtl + 180L + 1_000L + 1_000L);
        assertTrue(gateEvidenceWriteMaxAgeMs < window);
        assertTrue(burst >= 6 * dumpTtl);
        assertTrue(minActuationLeaseMs
                < com.overdrive.app.remoteaccess.BluetoothProximityDecisionGuard.MAX_ACTION_LEASE_MS);
        assertEquals(5_000L,
                com.overdrive.app.remoteaccess.BluetoothProximityDecisionGuard.MAX_ACTION_LEASE_MS);
        assertEquals(30_000L, BydDataCollector.DILINK5_CARSVC_SETTINGS_READ_MS);
    }

    @Test
    public void lockPollIsFixedRateOnTheChargingExecutorAndTheChargingTaskIsUnchanged()
            throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int start = collector.indexOf("    private synchronized void startDiLink5CarSvcChargingPoll() {");
        int end = collector.indexOf("    private synchronized void stopDiLink5CarSvcChargingPoll() {", start);
        assertTrue(start > 0 && end > start);
        String body = collector.substring(start, end);
        int charging = body.indexOf("diLink5CarSvcChargingPoller.scheduleWithFixedDelay(() -> {");
        int lock = body.indexOf("diLink5CarSvcChargingPoller.scheduleAtFixedRate(() -> {");
        assertTrue(charging > 0 && lock > charging);
        assertTrue(body.contains("pollDiLink5CarSvcCharging();"));
        assertTrue(body.substring(lock).contains("pollDiLink5CarSvcLock();"));
        assertTrue(body.substring(lock).contains(
                "}, 0L, DILINK5_CARSVC_LOCK_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);"));
        // Exactly one executor, both tasks on it; the charging cadence is unchanged.
        assertEquals(1, count(body, "newSingleThreadScheduledExecutor"));
        assertTrue(body.contains("new Thread(r, \"DI5CarSvcCharging\")"));
        assertTrue(body.contains(
                "}, DILINK5_CARSVC_CHARGING_POLL_MS, DILINK5_CARSVC_CHARGING_POLL_MS,\n"
                        + "                java.util.concurrent.TimeUnit.MILLISECONDS);"));
        assertTrue(collector.contains("private static final long DILINK5_CARSVC_CHARGING_POLL_MS = 20_000L;"));
        assertTrue(collector.contains("static final long DILINK5_CARSVC_LOCK_POLL_MS = 6_000L;"));
        assertTrue(collector.contains("static final long DILINK5_CARSVC_LOCK_BURST_MAX_MS = 12_000L;"));

        // The charging tick itself is untouched apart from the Stage 0 diagnostic call.
        int chargingTick = collector.indexOf("    private void pollDiLink5CarSvcCharging() {");
        int chargingTickEnd = collector.indexOf("\n    }\n", chargingTick);
        String chargingBody = collector.substring(chargingTick, chargingTickEnd);
        assertTrue(chargingBody.startsWith(
                "    private void pollDiLink5CarSvcCharging() {\n"
                        + "        CarSvcTelemetry.ChargingSignals s = CarSvcTelemetry.readChargingSignals();"));
        assertFalse(chargingBody.contains("observeDiLink5CarSvcLock("));
        assertFalse(chargingBody.contains("pollDiLink5CarSvcLock("));
        assertFalse(chargingBody.contains("dumpsysText("));
    }

    @Test
    public void lockTickIsGuardedReadsSettingsOnItsThreadAndDumpsBeforeTheIngressLock()
            throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int start = collector.indexOf("    private void pollDiLink5CarSvcLock() {");
        int end = collector.indexOf("    private void refreshDiLink5CarSvcLockSettings(long nowElapsedMs) {", start);
        assertTrue(start > 0 && end > start);
        String tick = collector.substring(start, end);
        int settings = tick.indexOf("refreshDiLink5CarSvcLockSettings(now);");
        int guard = tick.indexOf("if (!shouldPollDiLink5CarSvcLock(");
        int dump = tick.indexOf("CarSvcTelemetry.INSTANCE.dumpsysText();");
        int observe = tick.indexOf("observeDiLink5CarSvcLock(dump, nowMs, SystemClock.elapsedRealtimeNanos());");
        int burst = tick.indexOf("if (!shouldContinueDiLink5CarSvcLockBurst(nowMs, burstUntil)) return;");
        assertTrue(settings > 0 && guard > settings && dump > guard && observe > dump && burst > observe);
        // LOW-2: the ACC argument is authoritative AND on, so an unknown ACC state keeps polling.
        assertTrue(tick.contains(
                "com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()\n"
                        + "                        && com.overdrive.app.monitor.AccMonitor.isAccOn(),"));
        assertTrue(tick.contains("diLink5RemoteAccessEnabled)) {\n            return;"));
        assertTrue(tick.contains("diLink5GateLock.enabledRails(),"));
        // The tick never takes the ingress lock itself: the dump completes, then the observer locks.
        assertFalse(tick.contains("synchronized"));
        assertTrue(tick.contains("Thread.sleep(pause);"));

        String settingsBody = collector.substring(end,
                collector.indexOf("    public void requestDiLink5CarSvcLockBurst(long windowMs) {", end));
        assertTrue(settingsBody.contains("com.overdrive.app.remoteaccess.RemoteAccessSettings.read();"));
        assertTrue(settingsBody.contains("diLink5RemoteAccessEnabled = settings.optBoolean(\"enabled\", false);"));
        assertTrue(settingsBody.contains("setDiLink5LockRails("));
        assertTrue(settingsBody.contains("setDiLink5CarSvcLockMaxEventAgeSec("));
        assertTrue(settingsBody.contains("DILINK5_CARSVC_SETTINGS_READ_MS"));
        assertTrue(settingsBody.contains("} catch (Throwable t) {"));

        int observer = collector.indexOf("    private void observeDiLink5CarSvcLock(");
        int observerEnd = collector.indexOf("\n    }\n", observer);
        String observerBody = collector.substring(observer, observerEnd);
        int parse = observerBody.indexOf("CarSvcTelemetry.parseLockEvent(dump.getText(), CarSvcTelemetry.LF_LOCK_PROP_ID);");
        int evaluateAt = observerBody.indexOf("evaluateDiLink5CarSvcLock(");
        int lock = observerBody.indexOf("synchronized (diLink5IngressLock) {");
        int observeAt = observerBody.indexOf(
                "diLink5GateLock.observe(DiLink5LockRail.CARSVC, o.state, dump.getObservedAtElapsedMs(),");
        assertTrue(parse > 0 && evaluateAt > parse && lock > evaluateAt && observeAt > lock);
        assertTrue(observerBody.contains("if (dump == null) return;"));
        assertTrue(observerBody.contains("diLink5CarSvcLockMaxEventAgeNs"));
        assertFalse(observerBody.contains("dumpsysText("));
        assertFalse(observerBody.contains("dumpSnapshot("));
        assertFalse(observerBody.contains("cloud"));

        int request = collector.indexOf("    public void requestDiLink5CarSvcLockBurst(long windowMs) {");
        int requestEnd = collector.indexOf("\n    }\n", request);
        String requestBody = collector.substring(request, requestEnd);
        assertTrue(requestBody.contains("diLink5GateLock.enabledRails().contains(DiLink5LockRail.CARSVC)"));
        assertTrue(requestBody.contains("diLink5CarSvcLockBurstUntil(SystemClock.elapsedRealtime(), windowMs)"));
        assertTrue(requestBody.contains("poller.execute(() -> {"));
        assertFalse(requestBody.contains("synchronized"));
        assertFalse(requestBody.contains("dumpsysText("));
        assertFalse(requestBody.contains("Future"));
    }

    @Test
    public void gateReadsRefreshFromTheCachedDumpOnlyWhenCarsvcIsEnabledAndNeverSpawn()
            throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        for (String signature : new String[] {
                "    public int readDiLink5GateDoorLockState() {",
                "    public DiLink5LockEvidence readDiLink5GateDoorLockEvidence() {" }) {
            int start = collector.indexOf(signature);
            assertTrue(signature, start > 0);
            int end = collector.indexOf("\n    }\n", start);
            String body = collector.substring(start, end);
            int guard = body.indexOf("if (diLink5GateLock.enabledRails().contains(DiLink5LockRail.CARSVC)) {");
            int refresh = body.indexOf("observeDiLink5CarSvcLock(CarSvcTelemetry.cachedDump(),");
            int lock = body.indexOf("synchronized (diLink5IngressLock) {");
            assertTrue(signature, guard > 0 && refresh > guard && lock > refresh);
            assertTrue(signature, body.contains("} catch (Throwable t) {"));
            assertFalse(signature, body.contains("dumpsysText("));
            assertFalse(signature, body.contains("dumpSnapshot("));
            assertFalse(signature, body.contains("readChargingSignals("));
            assertFalse(signature, body.contains("cloud"));
            assertFalse(signature, body.contains("Cloud"));
        }
        // The non-spawning accessor really is non-spawning.
        String carSvc = read("app/src/main/java/com/overdrive/app/byd/CarSvcTelemetry.kt");
        int cached = carSvc.indexOf("fun cachedDump(): CachedDump? =");
        assertTrue(cached > 0);
        String cachedBody = carSvc.substring(cached, carSvc.indexOf("\n\n", cached));
        assertFalse(cachedBody.contains("dumpSnapshot("));
        // stop() clears the burst deadline with the poller.
        assertTrue(collector.contains("        diLink5CarSvcLockBurstUntilElapsedMs = 0L;"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent()
                    .resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
