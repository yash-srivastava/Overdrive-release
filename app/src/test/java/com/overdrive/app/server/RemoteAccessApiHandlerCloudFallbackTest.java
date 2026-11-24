package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * 17 Oct cloud fallback ACTUATOR for proximity lock/unlock (product decision; docs "Cloud fallback
 * actuator"). {@code RemoteAccessApiHandler} cannot run on the JVM, so the wiring is pinned by source
 * in the style of the other handler tests. The pure arithmetic lives in
 * {@code DiLink5GateActuationPolicy} (own test) and the revocation-only validity variants in the three
 * source guards (own tests). Binding rules pinned here: the safety gate runs unchanged before the SDK
 * leg; with the {@code cloudFallback} switch OFF the executed path is today's code (byte-equivalent
 * modulo the P3 closure naming); ONE cloud attempt per decision; cloud state never enters a readback;
 * "confirmed" only after a LOCAL readback; the lease re-check before the cloud leg is the product
 * owner's revocation-only list (no observation-age bound); the whole decision fits a 30 s budget.
 */
public class RemoteAccessApiHandlerCloudFallbackTest {
    private static final String HANDLER = "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java";
    /** Pre-edit copy of the handler taken by the 18 Oct snappy-proximity run (S2 moved the sequence off ACTION). */
    private static final String BACKUP = ".agents/tasks/snappy-proximity-impl-20261018/backup/" + HANDLER;
    private static final String PERFORM =
            "    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {";
    /**
     * S2 (18 Oct): everything after the SDK dispatch and the {@code accepted} record — the cloud legs,
     * the frozen legacy sequence, the readback windows and the terminal outcome — runs here, on the
     * single-use {@code proximity-confirm} thread, as the same code it was under ACTION.
     */
    private static final String CONFIRM =
            "    private static void runProximityConfirmJob(ConfirmJob job, ActionDecision decision, JSONObject settings,";
    private static final String DISPATCH = "    private static CloudLeg dispatchCloudFallback(ActionDecision decision, JSONObject settings,";
    private static final String BOUNDED = "    private static VehicleCommandRouter.CommandResult runCloudLegBounded(";
    private static final String SECOND_WINDOW =
            "    private static DiLink5GateActuationPolicy.Confirmation confirmLocallyAfterCloud(BydDataCollector collector,";
    private static final String OUTCOME = "    private static void recordCloudFallbackOutcome(ActionDecision decision, JSONObject settings,";
    private static final String SUMMARY = "    private static String cloudLegSummary(CloudLeg leg) {";
    private static final String[] FROZEN = {
            "    private static void confirmLegacyUnlock(ActionDecision decision, JSONObject settings,",
            "    private static boolean legacyUnlockResendAllowed(ActionDecision decision, BydDataCollector collector)",
            "    public static void logCollectorLiveness() {",
            "    private static String applyCollectorAfterRadioChange() {",
            "    private static void mergeApplied(JSONObject applied) throws Exception {",
            "    private static void relayActuationCheck(",
            "    private static void seedKeyFobArmingAfterProximityLock(ActionDecision decision) {",
            "    private static void noteProximityUnlockDispatched(BydDataCollector collector) {",
            "    private static String phoneLockBlockedText() {"};

    private static String method(String handler, String signature) {
        int start = handler.indexOf(signature);
        assertTrue(signature, start > 0);
        return handler.substring(start, handler.indexOf("\n    }\n", start));
    }

    /** The decision as one sequence: the gate + dispatch under ACTION, then the confirm job's part. */
    private static String decisionWithJob(String handler) {
        return method(handler, PERFORM) + "\n" + method(handler, CONFIRM);
    }

    /** The decision reads the switch once, right after the revision check, and the gate is untouched. */
    @Test
    public void switchIsReadOnceAfterTheRevisionCheckAndTheGateRunsBeforeAnyCloudLeg() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = decisionWithJob(handler);
        String read = "boolean cloudFallback = RemoteAccessSettings.cloudFallback(live);";
        assertEquals(1, count(handler, read));
        int revision = decision.indexOf("if (!live.optBoolean(\"enabled\") || live.optLong(\"revision\") != settings.optLong(\"revision\")) return;");
        int switchRead = decision.indexOf(read);
        int quiet = decision.indexOf("if (decision.quietSatisfied()) return;");
        int dryRun = decision.indexOf("recordAction(action, \"dry_run\", \"Learning mode: no vehicle command sent\");");
        int firstLeg = decision.indexOf("dispatchCloudFallback(");
        assertTrue(revision > 0 && switchRead > revision && quiet > switchRead && dryRun > quiet && firstLeg > dryRun);
        // Every safety check of today precedes the first cloud leg, in today's order.
        String[] gate = {
                "\"Automatic unlock is disabled\"", "\"Approach reading expired before the command\"",
                "\"Vehicle must be confirmed off\"", "int before = collector.readDoorLockState();",
                "\"Local lock state is unknown\"", "\"Signal expired during driver-door readback\"",
                "ProximityCooldownPolicy.blocks(action, lastActionKind, SystemClock.elapsedRealtime(),",
                "KeyFobPresenceEvaluator.relockGraceBlocks(lastActionKind, lastActionElapsed,",
                "int[] doors = collector.readAllDoorOpenStates();",
                "String doorBlock = DiLink5GateActuationPolicy.doorBlockReason(doors);",
                "\"Signal or vehicle state changed before command\"",
                "BydDataCollector.DiLink5LockEvidence finalEvidence =",
                "\"Signal or lock state expired before command\"",
                "\"Insufficient lease left for local actuation\"",
                "VehicleCommandRouter.getInstance().executeSdkOnly(command)"};
        int at = 0;
        for (String step : gate) {
            int next = decision.indexOf(step, at);
            assertTrue(step, next > at);
            at = next;
        }
        assertTrue(at < firstLeg);
        // P3: the closure block keeps its predicate (any of six != 0) and names the closures; the old
        // text is gone from the handler; the frozen re-send guard keeps its own loop.
        assertEquals(0, count(handler, "Close all doors"));
        assertTrue(decision.contains(
                "            String doorBlock = DiLink5GateActuationPolicy.doorBlockReason(doors);\n"
                + "            if (doorBlock != null) {\n"
                + "                recordAction(action, \"blocked\", doorBlock);\n"
                + "                return;\n"
                + "            }"));
        assertTrue(method(handler, FROZEN[1]).contains("for (int i = 0; i < 6; i++) if (doors[i] != 0) return false;"));
    }

    /** Exactly four gated legs in performDecision, one definition, one router call, one bounded runner. */
    @Test
    public void oneCloudAttemptPerDecisionThroughOneBoundedRouterCall() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = decisionWithJob(handler);
        assertEquals(4, count(decision, "dispatchCloudFallback("));
        // S2: all four legs run on the confirm job; the gate + dispatch under ACTION sends none.
        assertEquals(0, count(method(handler, PERFORM), "dispatchCloudFallback("));
        assertEquals(1, count(handler, DISPATCH));
        assertEquals(5, count(handler, "dispatchCloudFallback("));
        for (int at = decision.indexOf("dispatchCloudFallback("); at >= 0; at = decision.indexOf("dispatchCloudFallback(", at + 1)) {
            assertTrue("leg at " + at + " is gated", insideGatedBlock(decision, at));
        }
        // The post-window leg is guarded by cloudLeg == null (the Di5 immediate leg may already have run).
        assertTrue(decision.contains("                if (cloudLeg == null) {\n"
                + "                    cloudLeg = dispatchCloudFallback(decision, settings, collector, command, finalEvidence != null,\n"
                + "                            actuationRail, target, true, dispatchedAtElapsed,\n"
                + "                            DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail), \"unconfirmed\");"));
        assertTrue(decision.contains("                } else if (confirmation == DiLink5GateActuationPolicy.Confirmation.UNCONFIRMED\n"
                + "                        && cloudLeg.worthASecondWindow()) {"));
        assertEquals(1, count(decision, "CloudLeg cloudLeg = null;"));
        // The router is reached exactly once in the file, from the bounded helper-thread runner only.
        assertEquals(1, count(handler, "executeCloudOnly("));
        String bounded = method(handler, BOUNDED);
        assertTrue(bounded.contains("outcome.set(VehicleCommandRouter.getInstance().executeCloudOnly(command));"));
        assertTrue(bounded.contains("}, \"proximity-cloud-fallback\");"));
        assertTrue(bounded.contains("helper.setDaemon(true);"));
        assertTrue(bounded.contains("helper.join(Math.max(1L, boundMs));"));
        assertTrue(bounded.contains("if (helper.isAlive()) {\n            helper.interrupt();"));
        assertTrue(bounded.contains("new java.util.concurrent.TimeoutException("));
        assertTrue(bounded.contains("catch (InterruptedException interrupted)"));
        assertTrue(bounded.contains("Thread.currentThread().interrupt();"));
        assertFalse(bounded.contains("for ("));
        assertFalse(bounded.contains("while ("));
        assertEquals(1, count(handler, "runCloudLegBounded(command, cloudTimeoutMs)"));
        assertTrue(method(handler, DISPATCH).contains("runCloudLegBounded(command, cloudTimeoutMs)"));
        // Rate-limited / offline / timed-out legs are terminal: none of the new helpers retries a cloud leg.
        for (String helper : new String[] {DISPATCH, BOUNDED, SECOND_WINDOW, OUTCOME, SUMMARY})
            assertEquals(helper, 0, count(method(handler, helper).toLowerCase(), "retry"));
        // No cloud state supplier anywhere near a readback.
        String secondWindow = method(handler, SECOND_WINDOW);
        assertFalse(secondWindow.contains("BydCloud"));
        assertFalse(secondWindow.contains("getStatusJson"));
        assertFalse(secondWindow.contains("executeCloudOnly"));
        assertFalse(secondWindow.contains("runCloudLegBounded"));
        // Only the policy's window arithmetic mentions the cloud; both readback suppliers are local rails.
        assertEquals(1, count(withoutCommentLines(secondWindow).replace("confirmLocallyAfterCloud", ""), "loud"));
        assertTrue(secondWindow.contains("diLink5 ? collector::readDiLink5GateDoorLockEvidence"));
        assertTrue(secondWindow.contains(": () -> DiLink5GateActuationPolicy.legacyReadback(\n"
                + "                                collector.readDoorLockState(), SystemClock.elapsedRealtime()),"));
        assertTrue(secondWindow.contains("DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(actuationRail, dispatchedAtElapsed,\n"
                + "                SystemClock.elapsedRealtime());"));
        assertTrue(secondWindow.contains("if (windowMs <= 0L) return DiLink5GateActuationPolicy.Confirmation.UNCONFIRMED;"));
        assertTrue(secondWindow.contains("if (actuationRail == DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(windowMs);"));
        assertTrue(secondWindow.contains("target, windowMs, SystemClock::elapsedRealtime, Thread::sleep);"));
        assertEquals(1, count(handler, "collector.readDiLink5GateDoorLockEvidenceFor(DiLink5LockRail.TYPED_CABIN)"));
    }

    /**
     * Product-owner re-validation list before the cloud leg: settings revision/enabled, the switch,
     * Learning mode, {@code decision.current()} (revocation only — never the observation age), power,
     * closures for a lock, duplicate suppression, budget. Everything else of the SDK gate is NOT redone.
     */
    @Test
    public void cloudLegRevalidatesRevocationOnlyThroughThePureVerdict() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String dispatch = method(handler, DISPATCH);
        String[] order = {
                "JSONObject liveNow = RemoteAccessSettings.read();",
                "boolean settingsCurrent = liveNow.optBoolean(\"enabled\")\n"
                        + "                    && liveNow.optLong(\"revision\") == settings.optLong(\"revision\");",
                "boolean decisionCurrent = decision.current();",
                "int power = readVehiclePowerNow();",
                "int[] doors = lock ? collector.readAllDoorOpenStates() : null;",
                "int local = diLink5 ? collector.readDiLink5GateDoorLockEvidence().state\n"
                        + "                    : collector.readDoorLockState();",
                "cloudTimeoutMs = DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(dispatchedAtElapsed,\n"
                        + "                    SystemClock.elapsedRealtime(), reserveMs, command.cloudTimeoutMs());",
                "skipped = DiLink5GateActuationPolicy.cloudLegSkipReason(settingsCurrent,\n"
                        + "                    RemoteAccessSettings.cloudFallback(liveNow), liveNow.optBoolean(\"dryRun\", true),\n"
                        + "                    decisionCurrent, decision.blockedReason(\"decision revoked\"), power, lock, doors,\n"
                        + "                    local, target, cloudTimeoutMs);",
                "} catch (Throwable t) {\n"
                        + "            skipped = \"re-validation failed: \" + t.getClass().getSimpleName();",
                "return new CloudLeg(null, skipped, DiLink5GateActuationPolicy.PATH_SDK);",
                "String transportSkip = VehicleCommandRouter.getInstance()\n"
                        + "                .proximityCloudFallbackTransportSkipReason(command);",
                "VehicleCommandRouter.CommandResult result = runCloudLegBounded(command, cloudTimeoutMs);",
                "if (result.outcome == VehicleCommandRouter.Outcome.AUTH_REQUIRED) skipped = \"cloud not configured\";",
                "else if (result.outcome == VehicleCommandRouter.Outcome.NOT_SUPPORTED) skipped = \"cloud leg not available for this command\";",
                "else if (result.outcome == VehicleCommandRouter.Outcome.BLOCKED_DRIVING) skipped = \"vehicle not confirmed parked\";",
                "recordAction(action, \"accepted\", \"Cloud command accepted; awaiting driver-door readback\"\n"
                        + "                    + DiLink5GateActuationPolicy.cloudFallbackDetail(path, actuationRail));",
                "if (\"unlock\".equals(action)) noteProximityUnlockDispatched(collector);",
                "collector.requestDiLink5CarSvcLockBurst(reserveMs);",
                "return new CloudLeg(result, null, path);"};
        int at = 0;
        for (String step : order) {
            int next = dispatch.indexOf(step, at);
            assertTrue(step, next > at);
            at = next;
        }
        // Never the observation-age lease, never the full valid(), never a cloud read, never a retry.
        for (String forbidden : new String[] {"decision.valid()", "BluetoothProximityDecisionGuard.fresh(",
                "decision.observed", "decision.freshnessMs", "insufficientLease", "BydCloud", "getStatusJson",
                "lastActionKind =", "lastActionElapsed =", "lastCommandConfirmed =", "decision.satisfied()",
                "\"Local lock state is unknown\"", "isVehicleOnOnlyMode", "isBluetoothKeepAliveEnabled"})
            assertFalse(forbidden, dispatch.contains(forbidden));
        assertEquals(1, count(dispatch, "decision.current()"));
        // The pure verdict lives in the policy; the handler carries no copy of its texts.
        for (String text : new String[] {"\"settings changed\"", "\"Learning mode\"", "\"lease expired\"",
                "\"vehicle no longer off\"", "\"local state already at target\"", "\"decision budget exhausted\""})
            assertEquals(text, 0, count(handler, text));
        assertTrue(method(handler, "    private static final class CloudLeg {")
                .contains("DiLink5GateActuationPolicy.CLOUD_LEG_SKIP_LOCAL_AT_TARGET.equals(skipped)"));
        // Numbers (latency, budget) go to the daemon log only; recordAction messages stay dedup-friendly.
        int recordAction = handler.indexOf("    private static void recordAction(");
        for (int call = handler.indexOf("recordAction(action,"); call >= 0 && call < recordAction;
                call = handler.indexOf("recordAction(action,", call + 1)) {
            String text = handler.substring(call, handler.indexOf(");", call));
            for (String number : new String[] {"ageMs", "describe(", "remainingMs", "latencyMs", "budgetMs", "boundMs"})
                assertFalse(text, text.contains(number));
        }
        assertTrue(dispatch.contains("+ \" latencyMs=\" + result.latencyMs + \" budgetMs=\" + cloudTimeoutMs"));
    }

    /** {@code ActionDecision.current()} is the revocation-only variant for all three sources. */
    @Test
    public void decisionCurrentIsTheRevocationOnlyVariantOfValidForEverySource() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        assertTrue(handler.contains("        final boolean valid() throws Exception { return sourceValid() && safeZonesCurrent(); }"));
        assertTrue(handler.contains("        final boolean current() throws Exception { return sourceCurrent() && safeZonesCurrent(); }"));
        assertTrue(handler.contains("        boolean sourceCurrent() throws Exception { return sourceValid(); }"));
        assertEquals(3, count(handler, "@Override boolean sourceCurrent() throws Exception {"));
        // Bluetooth: the handler helper mirrors decisionStillValid with the age-free guard.
        assertTrue(handler.contains("            @Override boolean sourceCurrent() throws Exception {\n"
                + "                return decisionStillCurrent(reading, settings, instance, serial, generation);\n"
                + "            }"));
        String current = method(handler, "    private static boolean decisionStillCurrent(ProximityEvaluator.Snapshot reading, JSONObject settings,");
        assertTrue(current.contains("BluetoothProximityDecisionGuard.allowsIgnoringObservationAge(arriving ? \"unlock\" : \"lock\","));
        assertTrue(current.contains("&& (arriving || lockConsensus(true, now));"));
        assertFalse(current.contains("BluetoothProximityDecisionGuard.allows("));
        assertFalse(current.contains("originalObserved"));
        // Key fob event + presence: the evaluators' own variants, with the same surrounding rules.
        assertTrue(handler.contains("&& keyFob.decisionStillCurrent(reading, SystemClock.elapsedRealtime(), event)\n"
                + "                             && (!locking || lockConsensus(false, SystemClock.elapsedRealtime()));"));
        assertTrue(handler.contains("&& keyFobPresence.decisionStillCurrent(reading, SystemClock.elapsedRealtime())\n"
                + "                            && lockConsensus(false, SystemClock.elapsedRealtime());"));
        // The SDK dispatch keeps the full checks exactly as before.
        assertEquals(1, count(handler, "keyFob.decisionStillValid(reading, SystemClock.elapsedRealtime(), event)"));
        assertEquals(1, count(handler, "keyFobPresence.decisionStillValid(reading, SystemClock.elapsedRealtime())"));
        // Two decision overrides plus the 5-arg overload's own delegation, exactly as before.
        assertEquals(3, count(handler, "return decisionStillValid(reading, settings, instance, serial, observed, generation);"));
        assertEquals(1, count(handler, "allowsIgnoringObservationAge("));
        assertEquals(1, count(handler, "= decision.current();"));
        String decision = decisionWithJob(handler);
        assertFalse(decision.contains("current()"));
        // The guards' own additive variants exist and the originals are untouched.
        String guard = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/BluetoothProximityDecisionGuard.java");
        assertEquals(1, count(guard, "public static boolean allowsIgnoringObservationAge(String action, long originalEpisode,"));
        assertTrue(guard.contains("|| !fresh(originalObserved, now, Math.min(staleMs, MAX_ACTION_LEASE_MS))"));
        assertEquals(1, count(guard, "!fresh(originalObserved, now, Math.min(staleMs, MAX_ACTION_LEASE_MS))"));
        String fob = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/KeyFobEventEvaluator.java");
        assertEquals(1, count(fob, "public boolean decisionStillCurrent(Observation value, long now, ProximityEvaluator.Event event) {"));
        assertTrue(fob.contains("                && ageFresh(now, value.eventElapsed) && ageFresh(now, lastValidElapsed);"));
        String presence = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/KeyFobPresenceEvaluator.java");
        assertEquals(1, count(presence, "public boolean decisionStillCurrent(Observation value, long now) {"));
        assertTrue(presence.contains("                && fresh(now, value.observed, KeyFobEventEvaluator.FRESH_MS);"));
    }

    /** The Di5 immediate rule and the non-Di5 legacy hook sit exactly where the plan puts them. */
    @Test
    public void di5ImmediateLegAndLegacyUnlockHookAreWiredAroundTheFrozenSequence() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = decisionWithJob(handler);
        int burst = decision.indexOf("if (actuationRail == DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(12_000L);");
        int immediate = decision.indexOf("if (cloudFallback && DiLink5GateActuationPolicy.cloudFallbackImmediate(finalEvidence != null, typedEvidence)) {", burst);
        int legacy = decision.indexOf("if (legacyDispatch && \"unlock\".equals(action)) {", immediate);
        assertTrue(burst > 0 && immediate > burst && legacy > immediate);
        assertTrue(decision.contains(
                "                cloudLeg = dispatchCloudFallback(decision, settings, collector, command, true, actuationRail, target,\n"
                + "                        true, dispatchedAtElapsed, DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail),\n"
                + "                        \"typed_none\");"));
        // The typed-rail evidence is read once, gated, right after the actuation evidence.
        int actuationEvidence = decision.indexOf("BydDataCollector.DiLink5LockEvidence actuationEvidence =");
        int typed = decision.indexOf("BydDataCollector.DiLink5LockEvidence typedEvidence = cloudFallback && finalEvidence != null\n"
                + "                    ? collector.readDiLink5GateDoorLockEvidenceFor(DiLink5LockRail.TYPED_CABIN) : null;", actuationEvidence);
        int q14 = decision.indexOf("// Q14:", typed);
        assertTrue(actuationEvidence > 0 && typed > actuationEvidence && q14 > typed);
        // Legacy unlock: the frozen sequence first, then the hook only when it did not confirm, with the
        // outcome recorded a second time only if a cloud command actually went out.
        assertTrue(decision.contains(
                "            if (legacyDispatch && \"unlock\".equals(action)) {\n"
                + "                confirmLegacyUnlock(decision, settings, collector);\n"));
        assertTrue(decision.contains("                if (cloudFallback && !lastCommandConfirmed) {\n"
                + "                    cloudLeg = dispatchCloudFallback(decision, settings, collector, command, false, null, target,\n"
                + "                            true, dispatchedAtElapsed, DiLink5GateActuationPolicy.confirmationWindowMs(null),\n"
                + "                            \"legacy_unconfirmed\");"));
        assertTrue(decision.contains("                            cloudLeg, null, true, null, true);\n"
                + "                }\n"
                + "                return;\n"
                + "            }"));
        String outcome = method(handler, OUTCOME);
        assertTrue(outcome.contains("if (terminalAlreadyRecorded && leg.result == null) return;"));
        // The refused-local-leg rule (b) runs its leg before today's failed record.
        assertTrue(decision.contains(
                "            if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS) {\n"
                + "                if (cloudFallback) {\n"));
        assertTrue(decision.contains(
                "                            actuationRail, target, false, dispatchedAtElapsed,\n"
                + "                            DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail), \"sdk_failed\");"));
        // Bookkeeping stays with the SDK dispatch sites (J4): three writers, as before.
        assertEquals(3, count(handler, "lastActionKind = action;"));
        assertEquals(3, count(handler, "lastActionElapsed = SystemClock.elapsedRealtime();"));
        assertEquals(2, count(handler, "new VehicleCommandRouter.UnlockCommand(false)"));
        assertEquals(3, count(handler, "+ railDetail"));
        assertEquals(2, count(handler, "\"Cooldown active\""));
        for (String helper : new String[] {DISPATCH, BOUNDED, SECOND_WINDOW, OUTCOME, SUMMARY, CONFIRM}) {
            String body = method(handler, helper);
            assertFalse(helper, body.contains("lastActionKind ="));
            assertFalse(helper, body.contains("lastActionElapsed ="));
        }
    }

    /** Outcome texts: "confirmed" only after a LOCAL readback; a cloud ack alone is cloud_acknowledged. */
    @Test
    public void cloudSuccessIsNeverReportedAsALocalConfirmation() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String outcome = method(handler, OUTCOME);
        // Exactly one confirmed branch, entered only on Confirmation.CONFIRMED, with the pinned bookkeeping order.
        assertEquals(1, count(outcome, "recordAction(action, \"confirmed\""));
        int confirmedIf = outcome.indexOf("if (confirmation == DiLink5GateActuationPolicy.Confirmation.CONFIRMED) {");
        int lastConfirmed = outcome.indexOf("lastCommandConfirmed = true;", confirmedIf);
        int satisfied = outcome.indexOf("decision.satisfied();", lastConfirmed);
        int record = outcome.indexOf("recordAction(action, \"confirmed\", text);", satisfied);
        int seed = outcome.indexOf("if (\"lock\".equals(action)) seedKeyFobArmingAfterProximityLock(decision);", record);
        int notify = outcome.indexOf("notifyOutcome(decision, settings, \"confirmed\", text);", seed);
        int firstReturn = outcome.indexOf("return;", notify);
        assertTrue(confirmedIf > 0 && lastConfirmed > confirmedIf && satisfied > lastConfirmed && record > satisfied
                && seed > record && notify > seed && firstReturn > notify);
        assertEquals(1, count(outcome, "lastCommandConfirmed = true;"));
        assertEquals(1, count(outcome, "decision.satisfied();"));
        assertTrue(lastConfirmed < firstReturn && satisfied < firstReturn);
        // Cloud acknowledged, no local readback: its own record, published as an unconfirmed notification (J5).
        assertTrue(outcome.contains("        if (leg.acknowledged()) {\n"
                + "            String text = \"Cloud command acknowledged; driver-door change could not be confirmed locally\" + cloudDetail;\n"
                + "            recordAction(action, \"cloud_acknowledged\", text);\n"
                + "            notifyOutcome(decision, settings, \"unconfirmed\", text);\n"
                + "            return;\n"
                + "        }"));
        assertEquals(1, count(handler, "recordAction(action, \"cloud_acknowledged\","));
        assertEquals(0, count(handler, "notifyOutcome(decision, settings, \"cloud_acknowledged\""));
        // The texts of the outcome table.
        for (String text : new String[] {
                "\"Driver-door lock state confirmed via cloud command\" + cloudDetail",
                "\"; cloud command not sent\" + cloudDetail",
                "\"; cloud command failed: \"",
                "\"Command accepted; driver-door change could not be confirmed; cloud command not sent: \"",
                "\"Local command unconfirmed; cloud command failed: \"",
                "\"Local command failed (\" + sdkFailureText + \"); cloud command failed: \"",
                "\"Local command failed (\" + sdkFailureText + \"); cloud command not sent: \" + leg.skipped + cloudDetail",
                "recordAction(action, sdkAccepted ? \"unconfirmed\" : \"failed\", text);",
                "recordAction(action, \"failed\", text);"})
            assertTrue(text, outcome.contains(text));
        assertTrue(outcome.contains("String localLeg = sdkAccepted ? \"via local command\"\n"
                + "                    : \"without a command (local command failed: \" + sdkFailureText + \")\";"));
        assertTrue(outcome.contains("String cloudDetail = DiLink5GateActuationPolicy.cloudFallbackDetail(leg.path, actuationRail);"));
        assertFalse(outcome.contains("railDetail"));
        // The summary labels; the cloud's onlineState is a label only, read inside a try/catch.
        String summary = method(handler, SUMMARY);
        assertTrue(summary.contains("com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getStatusJson()\n"
                + "                    .optString(\"onlineState\", \"unknown\");"));
        assertTrue(summary.contains("catch (Throwable ignored)"));
        assertTrue(summary.contains("leg.result.error instanceof java.util.concurrent.TimeoutException"));
        assertEquals(1, count(handler, "BydCloudDataProvider"));
        // The frozen methods gained no cloud hook.
        for (String signature : FROZEN) {
            String body = method(handler, signature);
            for (String hook : new String[] {"cloudFallback", "executeCloudOnly", "cloud_acknowledged",
                    "dispatchCloudFallback", "current()", "CloudLeg", "runCloudLegBounded"})
                assertFalse(signature + " contains " + hook, body.contains(hook));
        }
        // No new import: everything new is fully qualified.
        for (String line : handler.substring(0, handler.indexOf("\npublic final class")).split("\n")) {
            if (!line.startsWith("import ")) continue;
            assertFalse(line, line.contains("cloud") || line.contains("concurrent") || line.contains("Thread"));
        }
        assertEquals(30, count(handler.substring(0, handler.indexOf("\npublic final class")), "\nimport "));
    }

    /**
     * Setting OFF → today's code path: strip every {@code if (cloudFallback …) {…}} block, the gated
     * locals and the S2 plumbing that merely carries values to the confirm job from the decision (the
     * gate + dispatch under ACTION, then the confirm job's sequence), and what remains contains nothing
     * of the cloud fallback. When the pre-edit backup of the handler (taken by the 18 Oct
     * snappy-proximity run, before S2 moved the sequence onto the confirm job) is present, the safety
     * gate up to the SDK dispatch is byte-identical (comment lines aside) to the backup's gate with the
     * three deliberate 18 Oct edits substituted: the relock grace taken from the settings (S4), the
     * confirmation switch read next to the cloud switch (S3) and the pending-job rule placed after the
     * last cooldown check (S2). Every other gate predicate, text and order is the backup's.
     */
    @Test
    public void withTheSwitchOffPerformDecisionIsTodaysCodePath() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = decisionWithJob(handler);
        String off = withoutGatedBlocks(decision);
        for (String gated : new String[] {
                "            boolean cloudFallback = RemoteAccessSettings.cloudFallback(live);\n",
                "            BydDataCollector.DiLink5LockEvidence typedEvidence = cloudFallback && finalEvidence != null\n"
                        + "                    ? collector.readDiLink5GateDoorLockEvidenceFor(DiLink5LockRail.TYPED_CABIN) : null;\n",
                "        CloudLeg cloudLeg = null;"}) {
            assertEquals(gated, 1, count(off, gated));
            off = off.replace(gated, "");
        }
        off = off.replaceAll("            long dispatchedAtElapsed = SystemClock\\.elapsedRealtime\\(\\);[^\n]*\n", "");
        // S2/S3 plumbing: the switch values and the dispatch time travel to the job; nothing else.
        for (String[] plumbing : new String[][] {
                {"            if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS && !(cloudFallback && confirmWithVehicle)) {\n",
                 "            if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS) {\n"},
                {"            ConfirmJob job = new ConfirmJob(decision, settings, collector, command, result, finalEvidence,\n"
                 + "                    actuationRail, typedEvidence, target, cloudFallback, legacyDispatch, dispatchedAtElapsed);\n", ""},
                {CONFIRM + "\n"
                 + "            BydDataCollector collector, VehicleCommandRouter.VehicleCommand command,\n"
                 + "            VehicleCommandRouter.CommandResult result, BydDataCollector.DiLink5LockEvidence finalEvidence,\n"
                 + "            DiLink5LockRail actuationRail, BydDataCollector.DiLink5LockEvidence typedEvidence, int target,\n"
                 + "            boolean cloudFallback, boolean legacyDispatch, long dispatchedAtElapsed) throws Exception {\n", ""},
                {"            job.path = cloudLeg == null ? DiLink5GateActuationPolicy.PATH_SDK : cloudLeg.path;\n"
                 + "            job.cloudAcknowledged = cloudLeg != null && cloudLeg.acknowledged();\n", ""}}) {
            assertEquals(plumbing[0], 1, count(off, plumbing[0]));
            off = off.replace(plumbing[0], plumbing[1]);
        }
        off = withoutCommentLines(off);
        for (String token : new String[] {"cloudFallback", "cloudLeg", "CloudLeg", "typedEvidence", "dispatchedAtElapsed",
                "dispatchCloudFallback", "recordCloudFallbackOutcome", "confirmLocallyAfterCloud", "executeCloudOnly",
                "cloud_acknowledged", "cloudFallbackImmediate", "current()", "cloud"})
            assertEquals(token, 0, count(off, token));
        // Today's terminal lines are verbatim: the refused local leg is terminal under ACTION and (as the
        // job's unreachable fallback) on the job; the accepted record precedes the job; the frozen legacy
        // hook and the readback outcomes run on the job without any new statement in between.
        assertTrue(off.contains(
                "            if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS) {\n"
                + "                recordAction(action, \"failed\", result.displayMessage);\n"
                + "                notifyOutcome(decision, settings, \"failed\", result.displayMessage);\n"
                + "                return;\n"
                + "            }\n"));
        assertTrue(off.contains(
                "            if (result.outcome == VehicleCommandRouter.Outcome.SUCCESS) {\n"
                + "                if (confirmWithVehicle)\n"
                + "                    recordAction(action, \"accepted\", \"Local command accepted; awaiting driver-door readback\" + railDetail);\n"
                + "                else recordAction(action, \"sent\", DiLink5GateActuationPolicy.CONFIRM_OFF_SENT_TEXT);\n"
                + "                if (\"unlock\".equals(action)) noteProximityUnlockDispatched(collector);\n"));
        assertTrue(off.contains(
                "            if (legacyDispatch && \"unlock\".equals(action)) {\n"
                + "                confirmLegacyUnlock(decision, settings, collector);\n"
                + "                return;\n"
                + "            }"));
        assertTrue(off.contains(
                "                notifyOutcome(decision, settings, \"confirmed\", \"Driver-door lock state confirmed\");\n"
                + "                return;\n"
                + "            }\n"
                + "            recordAction(action, \"unconfirmed\", \"Command accepted; driver-door change could not be confirmed\");\n"
                + "            notifyOutcome(decision, settings, \"unconfirmed\", \"Command accepted; driver-door change could not be confirmed\");\n"
                + "        }"));
        Path backup = repositoryPath(BACKUP);
        if (backup != null) {
            String beforeGate = gateOf(withoutCommentLines(withoutGatedBlocks(
                    method(new String(Files.readAllBytes(backup), StandardCharsets.UTF_8), PERFORM))));
            String nowGate = gateOf(withoutCommentLines(withoutGatedBlocks(method(handler, PERFORM))));
            String expected = beforeGate
                    .replace("            if (\"lock\".equals(action) && !DiLink5Platform.isSelected()\n"
                            + "                    && KeyFobPresenceEvaluator.relockGraceBlocks(lastActionKind, lastActionElapsed,\n"
                            + "                            lastDoorOpenedElapsed, SystemClock.elapsedRealtime())) {\n"
                            + "                recordAction(action, \"blocked\", \"Relock grace: a door must open or 15 s must pass after the proximity unlock\");\n",
                            "            long relockGraceMs = RemoteAccessSettings.relockGraceMs(settings);\n"
                            + "            if (\"lock\".equals(action) && !DiLink5Platform.isSelected()\n"
                            + "                    && KeyFobPresenceEvaluator.relockGraceBlocks(lastActionKind, lastActionElapsed,\n"
                            + "                            lastDoorOpenedElapsed, SystemClock.elapsedRealtime(), relockGraceMs)) {\n"
                            + "                recordAction(action, \"blocked\", \"Relock grace: a door must open or \" + relockGraceMs / 1000\n"
                            + "                        + \" s must pass after the proximity unlock\");\n")
                    .replace("            boolean cloudFallback = RemoteAccessSettings.cloudFallback(live);\n",
                            "            boolean cloudFallback = RemoteAccessSettings.cloudFallback(live);\n"
                            + "            boolean confirmWithVehicle = RemoteAccessSettings.confirmWithVehicle(settings);\n")
                    + "            ConfirmJob pendingJob = pendingConfirmJob;\n"
                    + "            if (pendingJob != null && pendingJob.pending()) {\n"
                    + "                if (pendingJob.action().equals(action)) {\n"
                    + "                    recordAction(action, \"blocked\", \"Command already in progress\");\n"
                    + "                    return;\n"
                    + "                }\n"
                    + "                pendingJob.supersede(action);\n"
                    + "            }\n";
            assertEquals(expected, nowGate);
        }
    }

    /** The safety gate: everything before the SDK command is built (the last gate is the cooldown / pending job). */
    private static String gateOf(String decision) {
        int dispatch = decision.indexOf("            VehicleCommandRouter.VehicleCommand command = ");
        assertTrue(dispatch > 0);
        return decision.substring(0, dispatch);
    }

    private static boolean insideGatedBlock(String body, int at) {
        int open = body.lastIndexOf("if (cloudFallback", at);
        if (open < 0) return false;
        int brace = body.indexOf("{", open);
        int depth = 0;
        for (int i = brace; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return at < i;
        }
        return false;
    }

    private static String withoutGatedBlocks(String body) {
        StringBuilder out = new StringBuilder(body);
        int at;
        while ((at = out.indexOf("if (cloudFallback")) >= 0) {
            int lineStart = out.lastIndexOf("\n", at) + 1;
            int brace = out.indexOf("{", at);
            int depth = 0, end = -1;
            for (int i = brace; i < out.length(); i++) {
                char c = out.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { end = i; break; }
            }
            assertTrue(end > 0);
            out.delete(lineStart, out.indexOf("\n", end) + 1);
        }
        return out.toString();
    }

    private static String withoutCommentLines(String body) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.trim().startsWith("//")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static Path repositoryPath(String relative) {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        return Files.exists(path) ? path : null;
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = repositoryPath(relative);
        assertTrue(relative, path != null);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
