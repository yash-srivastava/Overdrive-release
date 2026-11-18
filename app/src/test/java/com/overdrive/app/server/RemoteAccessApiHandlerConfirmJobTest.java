package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * 18 Oct snappy proximity, handler half (S2–S5). {@code RemoteAccessApiHandler} cannot run on the JVM,
 * so the wiring is pinned by source in the style of the other handler tests; the pure parts
 * ({@code DiLink5GateActuationPolicy} helpers, {@code ProximityCooldownPolicy}) have JVM tests. Rules
 * pinned: the ACTION monitor is released right after the SDK dispatch and the {@code accepted} record;
 * the readback/legacy re-send/cloud leg/terminal outcome run on the single-use daemon thread
 * {@code proximity-confirm}; one job per command — the same direction is answered "Command already in
 * progress" with no second dispatch, the opposite direction interrupts the pending job and proceeds;
 * the dispatch bookkeeping keeps its three writers; nothing throws out of the job and it logs one
 * {@code Proximity: confirm …} line; the frozen methods are byte-identical and still called from the
 * job; {@code confirmWithVehicle=false} records {@code sent} with no readback, re-send or cloud leg;
 * the relock grace and the post-lock quiet period come from the effective settings.
 */
public class RemoteAccessApiHandlerConfirmJobTest {
    private static final String HANDLER = "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java";
    private static final String BACKUP = ".agents/tasks/snappy-proximity-impl-20261018/backup/" + HANDLER;
    private static final String PERFORM =
            "    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {";
    private static final String CONFIRM =
            "    private static void runProximityConfirmJob(ConfirmJob job, ActionDecision decision, JSONObject settings,";
    private static final String JOB_CLASS = "    private static final class ConfirmJob extends Thread {";
    private static final String[] FROZEN = {
            "    private static void confirmLegacyUnlock(ActionDecision decision, JSONObject settings,",
            "    private static boolean legacyUnlockResendAllowed(ActionDecision decision, BydDataCollector collector)",
            "    public static void logCollectorLiveness() {",
            "    private static String applyCollectorAfterRadioChange() {",
            "    private static void mergeApplied(JSONObject applied) throws Exception {",
            "    private static void reset(JSONObject next) throws Exception {",
            "    private static java.util.List<MultiPhoneProximityPolicy.Observation> phoneDepartureStates() {",
            "    private static String phoneLockBlockedText() {",
            "    private static CloudLeg dispatchCloudFallback(ActionDecision decision, JSONObject settings,",
            "    private static VehicleCommandRouter.CommandResult runCloudLegBounded(",
            "    private static DiLink5GateActuationPolicy.Confirmation confirmLocallyAfterCloud(BydDataCollector collector,",
            "    private static void recordCloudFallbackOutcome(ActionDecision decision, JSONObject settings,"};
    /**
     * 19 Oct (keyFobUnlockTrigger): reset() is the ONE sanctioned handler hunk of that change — the fob
     * event evaluator is built from the fob rail's own trigger (default near) instead of the phones'
     * unlockTrigger. The frozen-method check below substitutes exactly this hunk back before comparing,
     * so every other byte of reset() is still pinned to the pre-18-Oct-edit handler.
     */
    private static final String RESET_FOB_TRIGGER_BEFORE =
            "        if (!retainKeyFob)\n"
            + "            keyFob = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(\n"
            + "                    effectiveConfig.optString(\"unlockTrigger\", \"away_to_near\")), effectiveConfig.optLong(\"revision\"),";
    private static final String RESET_FOB_TRIGGER_AFTER =
            "        // 19 Oct: the fob rail runs its own trigger (keyFobUnlockTrigger, default near: the first fresh\n"
            + "        // detection of a locked, off car unlocks); the phones' unlockTrigger no longer selects its mode.\n"
            + "        if (!retainKeyFob)\n"
            + "            keyFob = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(\n"
            + "                    effectiveConfig.optString(\"keyFobUnlockTrigger\", \"near\")), effectiveConfig.optLong(\"revision\"),";
    /**
     * 20 Oct (snappy locks): the second sanctioned reset() hunk — the presence evaluator is built with the
     * {@code keyFobLockSamples} setting. Substituted back like the fob-trigger hunk before comparing.
     */
    private static final String RESET_FOB_LOCK_SAMPLES_BEFORE =
            "                    effectiveConfig.optLong(\"keyFobLockWindowMs\", 10_000));";
    private static final String RESET_FOB_LOCK_SAMPLES_AFTER =
            "                    effectiveConfig.optLong(\"keyFobLockWindowMs\", 10_000), RemoteAccessSettings.keyFobLockSamples(effectiveConfig));";

    private static String method(String handler, String signature) {
        int start = handler.indexOf(signature);
        assertTrue(signature, start > 0);
        return handler.substring(start, handler.indexOf("\n    }\n", start));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    // ── S2: the monitor is released after dispatch + accepted; the sequence runs on the job ───────

    @Test
    public void actionIsHeldForGateAndDispatchOnlyAndTheSequenceStartsOnTheJob() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = method(handler, PERFORM);
        // One ACTION hold, and the job start is the last thing inside it.
        assertEquals(1, count(decision, "synchronized (ACTION)"));
        int dispatch = decision.indexOf("VehicleCommandRouter.getInstance().executeSdkOnly(command)");
        int accepted = decision.indexOf(
                "recordAction(action, \"accepted\", \"Local command accepted; awaiting driver-door readback\" + railDetail);", dispatch);
        String start = "            ConfirmJob job = new ConfirmJob(decision, settings, collector, command, result, finalEvidence,\n"
                + "                    actuationRail, typedEvidence, target, cloudFallback, legacyDispatch, dispatchedAtElapsed);\n"
                + "            pendingConfirmJob = job;\n"
                + "            job.start();\n"
                + "        }";
        int job = decision.indexOf(start, accepted);
        assertTrue(dispatch > 0 && accepted > dispatch && job > accepted);
        assertTrue(decision.endsWith(start));
        // Nothing that waits on the car is left under the monitor.
        for (String waits : new String[] {"confirmByDistinctReadbacks(", "confirmLegacyUnlock(", "dispatchCloudFallback(",
                "confirmLocallyAfterCloud(", "recordCloudFallbackOutcome(", "requestDiLink5CarSvcLockBurst(",
                "Thread.sleep", "Thread::sleep", "lastCommandConfirmed = true;", "seedKeyFobArmingAfterProximityLock(",
                "\"unconfirmed\", \"Command accepted; driver-door change could not be confirmed\""})
            assertEquals(waits, 0, count(decision, waits));
        // … and all of it runs on the job method, exactly as before, from the frozen legacy sequence to
        // the terminal records; the job method never takes ACTION.
        String confirm = method(handler, CONFIRM);
        assertEquals(0, count(confirm, "synchronized (ACTION)"));
        assertEquals(1, count(confirm, "confirmLegacyUnlock(decision, settings, collector);"));
        assertEquals(1, count(confirm, "DiLink5GateActuationPolicy.confirmByDistinctReadbacks("));
        assertEquals(1, count(confirm, "if (actuationRail == DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(12_000L);"));
        assertEquals(4, count(confirm, "dispatchCloudFallback("));
        assertEquals(1, count(confirm, "recordAction(action, \"confirmed\", \"Driver-door lock state confirmed\");"));
        assertEquals(1, count(confirm, "recordAction(action, \"unconfirmed\", \"Command accepted; driver-door change could not be confirmed\");"));
        assertEquals(1, count(confirm, "lastCommandConfirmed = true;"));
        assertTrue(confirm.contains("        CloudLeg cloudLeg = null;"));
        int finallyAt = confirm.indexOf("        } finally {\n");
        assertTrue(finallyAt > 0);
        assertTrue(confirm.endsWith(
                "            job.path = cloudLeg == null ? DiLink5GateActuationPolicy.PATH_SDK : cloudLeg.path;\n"
                + "            job.cloudAcknowledged = cloudLeg != null && cloudLeg.acknowledged();\n"
                + "        }"));
        assertTrue(confirm.indexOf("job.path = ", finallyAt) > finallyAt);
        // The job method is reached from the thread only.
        assertEquals(1, count(handler, "runProximityConfirmJob(this,"));
        assertEquals(2, count(handler, "runProximityConfirmJob("));
    }

    @Test
    public void theJobIsASingleUseNamedDaemonThreadThatNeverThrowsAndLogsOneLine() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String job = method(handler, JOB_CLASS);
        assertTrue(job.contains("super(DiLink5GateActuationPolicy.CONFIRM_JOB_THREAD_NAME);"));
        assertTrue(job.contains("setDaemon(true);"));
        assertEquals("proximity-confirm", DiLink5GateActuationPolicy.CONFIRM_JOB_THREAD_NAME);
        // Single use: pending() is false for good once run() ended, however it ended.
        assertTrue(job.contains("boolean pending() { return !done; }"));
        assertTrue(job.contains("            } finally {\n"
                + "                done = true;\n"
                + "                com.overdrive.app.daemon.CameraDaemon.log(DiLink5GateActuationPolicy.confirmJobLogLine(action,\n"
                + "                        lastResult, path, SystemClock.elapsedRealtime() - startedAt, supersededBy));\n"
                + "            }"));
        // Nothing escapes: Throwable is caught, an interrupt is re-asserted, the fallback record is itself guarded.
        assertTrue(job.contains("} catch (Throwable failed) {\n"
                + "                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();"));
        assertTrue(job.contains("if (!terminalRecorded()) {"));
        assertTrue(job.contains("} catch (Throwable ignored) {"));
        assertTrue(job.contains("String status = DiLink5GateActuationPolicy.interruptedConfirmStatus(sdkAccepted, cloudAcknowledged);"));
        assertTrue(job.contains("recordAction(action, status, text);"));
        assertTrue(job.contains("notifyOutcome(decision, settings, published, text);"));
        // The opposite direction interrupts the thread; the job never takes ACTION or writes the dispatch bookkeeping.
        assertTrue(job.contains("void supersede(String newerAction) {\n"
                + "            supersededBy = newerAction;\n"
                + "            interrupt();\n"
                + "        }"));
        for (String forbidden : new String[] {"synchronized (ACTION)", "lastActionKind =", "lastActionElapsed =",
                "lastCommandConfirmed =", "executeSdkOnly", "executeCloudOnly", "new Thread("})
            assertFalse(forbidden, job.contains(forbidden));
        // recordAction notes the last result written on the job's thread (the log line; terminal-or-not).
        String record = method(handler, "    private static void recordAction(String action, String result, String message) throws Exception {");
        assertTrue(record.contains("if (current instanceof ConfirmJob) ((ConfirmJob) current).lastResult = result;"));
        // Thread name and class live in the handler only; no new import (everything new is qualified).
        String imports = handler.substring(0, handler.indexOf("\npublic final class"));
        assertEquals(30, count(imports, "\nimport "));
        for (String line : imports.split("\n")) {
            if (!line.startsWith("import ")) continue;
            assertFalse(line, line.contains("Thread") || line.contains("concurrent"));
        }
    }

    @Test
    public void sameDirectionIsAnsweredFromThePendingJobAndTheOppositeDirectionSupersedesIt() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = method(handler, PERFORM);
        String rule = "            ConfirmJob pendingJob = pendingConfirmJob;\n"
                + "            if (pendingJob != null && pendingJob.pending()) {\n"
                + "                if (pendingJob.action().equals(action)) {\n"
                + "                    recordAction(action, \"blocked\", \"Command already in progress\");\n"
                + "                    return;\n"
                + "                }\n"
                + "                pendingJob.supersede(action);\n"
                + "            }\n"
                + "            VehicleCommandRouter.VehicleCommand command = \"unlock\".equals(action)";
        assertEquals(1, count(decision, rule));
        assertEquals(1, count(handler, "\"Command already in progress\""));
        assertEquals("Command already in progress", DiLink5GateActuationPolicy.CONFIRM_JOB_IN_PROGRESS_TEXT);
        // After the last gate of today (the second cooldown check), before the command exists: every
        // existing gate text — "already unlocked", "Cooldown active" — still takes precedence.
        int secondCooldown = decision.lastIndexOf("recordAction(action, \"blocked\", \"Cooldown active\");");
        int pending = decision.indexOf(rule);
        int dispatch = decision.indexOf("executeSdkOnly(command)");
        assertTrue(secondCooldown > 0 && pending > secondCooldown && dispatch > pending);
        assertEquals(2, count(handler, "\"Cooldown active\""));
        // The reference is replaced under ACTION only; volatile for the gate's read.
        assertTrue(handler.contains("private static volatile ConfirmJob pendingConfirmJob;"));
        assertEquals(1, count(handler, "pendingConfirmJob = job;"));
        assertEquals(1, count(handler, "pendingJob.supersede(action);"));
    }

    @Test
    public void dispatchBookkeepingKeepsItsThreeWritersAndIsVisibleAcrossThreads() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        assertEquals(3, count(handler, "lastActionKind = action;"));
        assertEquals(3, count(handler, "lastActionElapsed = SystemClock.elapsedRealtime();"));
        // lastCommandConfirmed = true: the job's readback, the frozen legacy sequence, the cloud outcome.
        assertEquals(3, count(handler, "lastCommandConfirmed = true;"));
        assertEquals(1, count(method(handler, CONFIRM), "lastCommandConfirmed = true;"));
        assertEquals(1, count(method(handler, FROZEN[0]), "lastCommandConfirmed = true;"));
        assertEquals(1, count(method(handler, FROZEN[11]), "lastCommandConfirmed = true;"));
        assertTrue(handler.contains("private static volatile long lastActionElapsed = -1;"));
        assertTrue(handler.contains("private static volatile String lastActionKind = \"\";"));
        assertTrue(handler.contains("private static volatile boolean lastCommandConfirmed;"));
        // Set at dispatch, under ACTION, exactly as before.
        String decision = method(handler, PERFORM);
        assertEquals(2, count(decision, "lastActionKind = action;"));
        assertEquals(2, count(decision, "lastCommandConfirmed = false;"));
    }

    @Test
    public void frozenMethodsAreByteIdenticalToThePreEditHandlerAndStillCalledFromTheJob() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        Path backup = repositoryPath(BACKUP);
        if (backup != null) {
            String before = new String(Files.readAllBytes(backup), StandardCharsets.UTF_8);
            for (String signature : FROZEN) {
                String expected = method(before, signature), actual = method(handler, signature);
                if (signature.equals(FROZEN[5])) {
                    // reset(): the 19 Oct fob-trigger hunk is present exactly once; with it substituted
                    // back, the method is byte-identical to the pre-edit handler.
                    assertEquals(1, count(actual, RESET_FOB_TRIGGER_AFTER));
                    assertEquals(1, count(expected, RESET_FOB_TRIGGER_BEFORE));
                    actual = actual.replace(RESET_FOB_TRIGGER_AFTER, RESET_FOB_TRIGGER_BEFORE);
                    // … and the 20 Oct presence-evaluator construction line, likewise exactly once.
                    assertEquals(1, count(actual, RESET_FOB_LOCK_SAMPLES_AFTER));
                    assertEquals(1, count(expected, RESET_FOB_LOCK_SAMPLES_BEFORE));
                    actual = actual.replace(RESET_FOB_LOCK_SAMPLES_AFTER, RESET_FOB_LOCK_SAMPLES_BEFORE);
                }
                assertEquals(signature, expected, actual);
            }
            // The multi-phone fix's PhoneState and the alias branch are untouched too.
            assertEquals(method(before, "    private static final class PhoneState {"),
                    method(handler, "    private static final class PhoneState {"));
        }
        String confirm = method(handler, CONFIRM);
        assertTrue(confirm.contains("            if (legacyDispatch && \"unlock\".equals(action)) {\n"
                + "                confirmLegacyUnlock(decision, settings, collector);\n"));
        String legacy = method(handler, FROZEN[0]);
        assertTrue(legacy.contains("if (legacyUnlockResendAllowed(decision, collector)) {"));
        assertEquals(1, count(handler, "confirmLegacyUnlock(decision, settings, collector);"));
        assertEquals(1, count(handler, "legacyUnlockResendAllowed(decision, collector)"));
    }

    // ── S3: confirmWithVehicle ───────────────────────────────────────────────────────────────────

    @Test
    public void confirmationOffRecordsSentImmediatelyWithNoReadbackResendOrCloudLeg() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = method(handler, PERFORM);
        assertEquals(1, count(decision, "boolean confirmWithVehicle = RemoteAccessSettings.confirmWithVehicle(settings);"));
        // Read next to the cloud switch, before the gate; the gate itself never looks at it.
        int cloud = decision.indexOf("boolean cloudFallback = RemoteAccessSettings.cloudFallback(live);");
        int confirm = decision.indexOf("boolean confirmWithVehicle = RemoteAccessSettings.confirmWithVehicle(settings);");
        int quiet = decision.indexOf("if (decision.quietSatisfied()) return;");
        int dispatch = decision.indexOf("executeSdkOnly(command)");
        assertTrue(cloud > 0 && confirm > cloud && quiet > confirm && dispatch > quiet);
        assertEquals(0, count(decision.substring(quiet, dispatch), "confirmWithVehicle"));
        // After the dispatch: a refused leg is terminal unless the cloud leg (confirmation on) is due; an
        // accepted leg is recorded as sent and the decision ends before any job is created.
        assertTrue(decision.contains(
                "            if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS && !(cloudFallback && confirmWithVehicle)) {\n"
                + "                recordAction(action, \"failed\", result.displayMessage);\n"
                + "                notifyOutcome(decision, settings, \"failed\", result.displayMessage);\n"
                + "                return;\n"
                + "            }"));
        assertTrue(decision.contains(
                "                if (confirmWithVehicle)\n"
                + "                    recordAction(action, \"accepted\", \"Local command accepted; awaiting driver-door readback\" + railDetail);\n"
                + "                else recordAction(action, \"sent\", DiLink5GateActuationPolicy.CONFIRM_OFF_SENT_TEXT);\n"
                + "                if (\"unlock\".equals(action)) noteProximityUnlockDispatched(collector);"));
        assertTrue(decision.contains(
                "                if (!confirmWithVehicle) {\n"));
        assertTrue(decision.contains("collector.pollLockStateNow();"));
        assertTrue(decision.contains(
                "                    notifyOutcome(decision, settings, \"unconfirmed\",\n"
                + "                            DiLink5GateActuationPolicy.CONFIRM_OFF_SENT_TEXT);\n"
                + "                    return;\n"
                + "                }\n"
                + "            }\n"));
        int sentReturn = decision.indexOf("notifyOutcome(decision, settings, \"unconfirmed\",\n"
                + "                            DiLink5GateActuationPolicy.CONFIRM_OFF_SENT_TEXT);");
        int job = decision.indexOf("ConfirmJob job = new ConfirmJob(");
        assertTrue(sentReturn > 0 && job > sentReturn);
        assertEquals("Command sent; vehicle confirmation off", DiLink5GateActuationPolicy.CONFIRM_OFF_SENT_TEXT);
        // Confirmation-off may still mark the command confirmed when a fresh LOCAL read already shows the target.
        assertEquals(1, count(decision.substring(0, job), "lastCommandConfirmed = true;"));
        // The job never sees the switch: with confirmation on the sequence is exactly today's.
        assertEquals(0, count(method(handler, CONFIRM), "confirmWithVehicle"));
    }

    // ── S4: relock grace and post-lock quiet period from the settings ───────────────────────────

    @Test
    public void relockGraceAndPostLockQuietAreReadLiveFromTheEffectiveSettings() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = method(handler, PERFORM);
        assertTrue(decision.contains(
                "            long relockGraceMs = RemoteAccessSettings.relockGraceMs(settings);\n"
                + "            if (\"lock\".equals(action) && !DiLink5Platform.isSelected()\n"
                + "                    && KeyFobPresenceEvaluator.relockGraceBlocks(lastActionKind, lastActionElapsed,\n"
                + "                            lastDoorOpenedElapsed, SystemClock.elapsedRealtime(), relockGraceMs)) {\n"
                + "                recordAction(action, \"blocked\", \"Relock grace: a door must open or \" + relockGraceMs / 1000\n"
                + "                        + \" s must pass after the proximity unlock\");"));
        assertEquals(0, count(handler, "KeyFobPresenceEvaluator.RELOCK_GRACE_MS"));
        assertEquals(0, count(handler, "15 s must pass"));
        String seed = method(handler, "    private static void seedKeyFobArmingAfterProximityLock(ActionDecision decision) {");
        assertTrue(seed.contains("quietMs = RemoteAccessSettings.postLockQuietMs(effectiveConfig);"));
        assertTrue(seed.contains("applied = keyFob.seedAbsence(SystemClock.elapsedRealtime(), quietMs);"));
        assertTrue(seed.contains("+ \" quietMs=\" + quietMs"));
        // Read under STATE, after refreshSettings, like the rest of the seed.
        int state = seed.indexOf("synchronized (STATE) {");
        int refresh = seed.indexOf("refreshSettings();", state);
        int quiet = seed.indexOf("quietMs = RemoteAccessSettings.postLockQuietMs(effectiveConfig);", refresh);
        int applied = seed.indexOf("applied = keyFob.seedAbsence(", quiet);
        assertTrue(state > 0 && refresh > state && quiet > refresh && applied > quiet);
        assertEquals(1, count(handler, "keyFob.seedAbsence("));
        // The evaluators' constants are the defaults of their old signatures only (pinned in their own tests).
        String presence = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/KeyFobPresenceEvaluator.java");
        assertTrue(presence.contains("return relockGraceBlocks(lastActionKind, lastActionElapsed, lastDoorOpenedElapsed, now, RELOCK_GRACE_MS);"));
        String event = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/KeyFobEventEvaluator.java");
        assertTrue(event.contains("return seedAbsence(observedElapsedMs, POST_LOCK_QUIET_MS);"));
    }

    // ── S5: the cooldown check is unchanged (the policy's own test covers the 11 s retry) ────────

    @Test
    public void cooldownChecksAreUnchangedAndStillPassLastCommandConfirmed() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = method(handler, PERFORM);
        assertEquals(2, count(decision, "if (ProximityCooldownPolicy.blocks(action, lastActionKind, SystemClock.elapsedRealtime(),"));
        assertTrue(decision.contains("lastActionElapsed, settings.optLong(\"cooldownMs\"), lastCommandConfirmed, before)) {"));
        assertTrue(decision.contains("lastActionElapsed, settings.optLong(\"cooldownMs\"), lastCommandConfirmed, finalLock)) {"));
        String policy = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/ProximityCooldownPolicy.java");
        assertTrue(policy.contains("if (now - lastAttempt >= cooldownMs) return false;"));
    }

    // ── Pure helpers of the job (JVM) ───────────────────────────────────────────────────────────

    @Test
    public void interruptedJobOutcomeIsHonestAndNeverAConfirmation() {
        // Accepted local leg, superseded by a lock: unconfirmed, says why.
        assertEquals("unconfirmed", DiLink5GateActuationPolicy.interruptedConfirmStatus(true, false));
        assertEquals("Command accepted; confirmation stopped by a newer lock decision",
                DiLink5GateActuationPolicy.interruptedConfirmText(true, false, "lock", null));
        // The cloud already acknowledged: cloud_acknowledged, never "confirmed".
        assertEquals("cloud_acknowledged", DiLink5GateActuationPolicy.interruptedConfirmStatus(true, true));
        assertEquals("cloud_acknowledged", DiLink5GateActuationPolicy.interruptedConfirmStatus(false, true));
        assertEquals("Cloud command acknowledged; local confirmation stopped by a newer unlock decision",
                DiLink5GateActuationPolicy.interruptedConfirmText(true, true, "unlock", null));
        // Refused local leg whose cloud leg was abandoned: failed.
        assertEquals("failed", DiLink5GateActuationPolicy.interruptedConfirmStatus(false, false));
        assertEquals("Local command failed (rc=-1); cloud command abandoned for a newer lock decision",
                DiLink5GateActuationPolicy.interruptedConfirmText(false, false, "lock", "rc=-1"));
        // Not superseded (an unexpected failure): today's text.
        assertEquals("Local action interrupted or unavailable",
                DiLink5GateActuationPolicy.interruptedConfirmText(true, false, null, null));
        assertEquals("Local action interrupted or unavailable",
                DiLink5GateActuationPolicy.interruptedConfirmText(false, true, null, "x"));
        for (String text : new String[] {
                DiLink5GateActuationPolicy.interruptedConfirmText(true, false, "lock", null),
                DiLink5GateActuationPolicy.interruptedConfirmText(true, true, "lock", null),
                DiLink5GateActuationPolicy.interruptedConfirmText(false, false, "lock", "rc=-1")})
            assertFalse(text, text.toLowerCase(java.util.Locale.ROOT).contains("confirmed;") || text.startsWith("Driver-door lock state confirmed"));
    }

    @Test
    public void theJobLogLineCarriesActionOutcomePathAndElapsed() {
        assertEquals("Proximity: confirm action=unlock outcome=confirmed path=SDK ms=1234",
                DiLink5GateActuationPolicy.confirmJobLogLine("unlock", "confirmed", DiLink5GateActuationPolicy.PATH_SDK, 1234L, null));
        assertEquals("Proximity: confirm action=lock outcome=cloud_acknowledged path=SDK_THEN_CLOUD ms=0 supersededBy=unlock",
                DiLink5GateActuationPolicy.confirmJobLogLine("lock", "cloud_acknowledged",
                        DiLink5GateActuationPolicy.PATH_SDK_THEN_CLOUD, -5L, "unlock"));
        assertEquals("Proximity: confirm action=lock outcome=none path=SDK ms=7",
                DiLink5GateActuationPolicy.confirmJobLogLine("lock", null, null, 7L, null));
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
