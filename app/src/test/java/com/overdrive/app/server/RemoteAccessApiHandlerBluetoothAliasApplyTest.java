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
 * The Proximity page's "Keep Bluetooth on" switch is saved as a standalone
 * {@code {keepBluetoothOn}} POST (the alias branch of {@code saveConfig}). Until 55.5 that branch
 * never issued {@code bridge("apply")}, and apply is the only call that starts the app-process
 * phone collector, so turning the switch ON started nothing (6 h export: zero {@code Proximity:
 * phone=} lines). {@code RemoteAccessApiHandler} cannot run on the JVM, so the wiring is pinned by
 * source, in the style of the other handler tests.
 */
public class RemoteAccessApiHandlerBluetoothAliasApplyTest {
    private static final String HANDLER = "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java";
    private static final String ALIAS_START = "if (patch.length() == 1 && patch.has(\"keepBluetoothOn\")) {";
    private static final String GENERIC_START = "next.put(\"revision\", current.optLong(\"revision\") + 1);";

    private static String saveConfig(String handler) {
        int start = handler.indexOf("    private static void saveConfig(String body, OutputStream out) throws Exception {");
        int end = handler.indexOf("\n    }\n", start);
        assertTrue(start > 0 && end > start);
        return handler.substring(start, end);
    }

    private static String aliasBranch(String handler) {
        String save = saveConfig(handler);
        int start = save.indexOf(ALIAS_START);
        int end = save.indexOf(GENERIC_START, start);
        assertTrue(start > 0 && end > start);
        return save.substring(start, end);
    }

    private static String method(String handler, String signature) {
        int start = handler.indexOf(signature);
        assertTrue(signature, start > 0);
        return handler.substring(start, handler.indexOf("\n    }\n", start));
    }

    /** T1: an alias save with the master on re-applies and merges the app's runtime like the generic branch. */
    @Test
    public void aliasSaveWithMasterOnAppliesAndMergesRuntime() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String alias = aliasBranch(handler);
        // Order: persist the shared switch, refresh, reconcile the radio, then apply, then respond.
        int persist = alias.indexOf("UnifiedConfigManager.setBluetoothKeepAliveEnabled(keepBluetoothOn)");
        int refresh = alias.indexOf("synchronized (STATE) { refreshSettings(); }");
        int radio = alias.indexOf("boolean applied = reconcileBluetoothKeepAlive(true);");
        int apply = alias.indexOf("String collectorOutcome = next.optBoolean(\"enabled\")\n"
                + "                        ? applyCollectorAfterRadioChange() : \"master switch off\";");
        int respond = alias.indexOf("HttpResponse.sendJson(out, response.toString());");
        int ret = alias.indexOf("return;");
        assertTrue(persist > 0 && refresh > persist && radio > refresh && apply > radio
                && respond > apply && ret > respond);
        // The helper is the bridge apply plus the SAME merge the generic branch uses.
        String helper = method(handler, "    private static String applyCollectorAfterRadioChange() {");
        assertTrue(helper.contains("JSONObject applied = bridge(\"apply\");"));
        assertTrue(helper.contains("synchronized (STATE) { mergeApplied(applied); }"));
        assertTrue(helper.indexOf("bridge(\"apply\")") < helper.indexOf("mergeApplied(applied)"));
        String merge = method(handler, "    private static void mergeApplied(JSONObject applied) throws Exception {");
        assertTrue(merge.contains("runtime = applied == null ? new JSONObject().put(\"active\", false).put(\"cleanupPending\", true)\n"
                + "                : applied.optJSONObject(\"runtime\");"));
        assertTrue(merge.contains("if (applied != null && applied.optJSONArray(\"devices\") != null) pairedDevices = applied.getJSONArray(\"devices\");"));
        // Generic branch: byte-identical behaviour, now through the shared helper, still after the
        // revoked-generation decision (a failed apply with the master on must not merge).
        String generic = saveConfig(handler).substring(saveConfig(handler).indexOf(GENERIC_START));
        assertTrue(generic.contains("JSONObject applied = bridge(\"apply\");"));
        assertTrue(generic.contains(
                "if (next.optBoolean(\"enabled\") && (applied == null || !applied.optBoolean(\"success\"))) {"));
        assertTrue(generic.contains("} else synchronized (STATE) {\n"
                + "                mergeApplied(applied);\n"
                + "                event(\"settings\", next.optBoolean(\"enabled\") ? \"Proximity monitoring enabled\" : \"Master switch off\");\n"
                + "            }"));
        assertEquals(1, count(generic, "mergeApplied(applied);"));
        assertEquals(1, count(generic, "bridge(\"apply\")"));
        // There are exactly two apply sites in the handler: the generic branch and the alias helper.
        assertEquals(2, count(handler, "bridge(\"apply\")"));
        // The outcome text reports whether a phone worker is actually running after the apply.
        assertTrue(helper.contains("return \"collector started/refreshed; active=\"\n"
                + "                        + (appRuntime != null && appRuntime.optBoolean(\"active\"));"));
    }

    /** T2: with the master off the alias save does not apply (nothing to start) and says so. */
    @Test
    public void aliasSaveWithMasterOffDoesNotApply() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String alias = aliasBranch(handler);
        assertTrue(alias.contains("next.optBoolean(\"enabled\")\n"
                + "                        ? applyCollectorAfterRadioChange() : \"master switch off\";"));
        // The alias branch itself never calls the bridge apply directly; only the guarded helper does.
        assertFalse(alias.contains("bridge(\"apply\")"));
        assertEquals(1, count(alias, "applyCollectorAfterRadioChange()"));
        // The radio reconciliation still runs in both directions and before the apply decision.
        assertTrue(alias.indexOf("reconcileBluetoothKeepAlive(true)") < alias.indexOf("applyCollectorAfterRadioChange()"));
    }

    /**
     * T3: an apply failure during an alias save leaves enabled and revision unchanged, writes
     * nothing, never runs the revoked-generation path, and surfaces the failure in the event text.
     */
    @Test
    public void aliasApplyFailureIsReportedNotActedOn() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String alias = aliasBranch(handler);
        String helper = method(handler, "    private static String applyCollectorAfterRadioChange() {");
        for (String forbidden : new String[] {
                "updateSection(", "put(\"enabled\", false)", "bridge(\"stop\")", "reset(",
                "validateRequiredPairings", "Monitoring could not start" }) {
            assertFalse(forbidden, helper.contains(forbidden));
            assertFalse(forbidden, alias.contains(forbidden));
        }
        // Nothing throws out of saveConfig because of the new call: the helper has no throw at all,
        // and the alias branch's only throw is the pre-existing one for a failed preference write.
        assertFalse(helper.contains("throw "));
        assertEquals(1, count(alias, "throw "));
        assertTrue(alias.contains("throw new IllegalStateException(\"Bluetooth setting could not be saved\");"));
        // The only settings write in the alias branch is the shared radio preference itself.
        assertEquals(1, count(alias, "UnifiedConfigManager."));
        assertTrue(alias.contains("UnifiedConfigManager.setBluetoothKeepAliveEnabled(keepBluetoothOn)"));
        // Bounded and exception-safe: the helper swallows everything and reports it.
        assertTrue(helper.contains("try {"));
        assertTrue(helper.contains("} catch (Exception unavailable) {\n"
                + "            return \"collector not started: \" + unavailable.getClass().getSimpleName();"));
        assertTrue(helper.contains("return \"collector not started: \" + (applied == null\n"
                + "                    ? \"car app unavailable\" : applied.optString(\"error\", \"unavailable\"));"));
        // The outcome lands in the one settings event and in the response.
        assertTrue(alias.contains("event(\"settings\", \"Shared parked Bluetooth \" + (keepBluetoothOn ? \"on\" : \"off\")\n"
                + "                            + \" (\" + collectorOutcome + \")\");"));
        assertTrue(alias.contains(".put(\"collectorApply\", collectorOutcome)"));
        assertEquals(1, count(alias, "event(\"settings\""));
        assertFalse(alias.contains("event(\"error\""));
        // Existing response fields are kept.
        assertTrue(alias.contains("status().put(\"bluetoothApplyPending\", !applied)"));
    }

    /** T4: an alias save never bumps the revision; the only bump is the generic branch's. */
    @Test
    public void aliasSaveNeverBumpsRevision() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String alias = aliasBranch(handler);
        String helper = method(handler, "    private static String applyCollectorAfterRadioChange() {");
        // No code touches the revision key (comments may name it).
        assertFalse(alias.contains("\"revision\""));
        assertFalse(helper.contains("\"revision\""));
        // The alias branch never writes into the settings object: its only put() calls build the response.
        assertFalse(alias.contains("next.put("));
        assertEquals(2, count(alias, ".put("));
        assertTrue(alias.contains("status().put(\"bluetoothApplyPending\", !applied)\n"
                + "                            .put(\"collectorApply\", collectorOutcome);"));
        String save = saveConfig(handler);
        // The alias branch returns before the first revision assignment.
        int aliasReturn = save.indexOf("return;", save.indexOf(ALIAS_START));
        int bump = save.indexOf(GENERIC_START);
        assertTrue(aliasReturn > 0 && bump > aliasReturn);
        assertEquals(1, count(save, GENERIC_START));
    }

    /**
     * T5 (wiring half; the evaluator behaviour is in KeyFobEventEvaluatorRadioRetentionTest):
     * reset() retains the keyfob event evaluator under the SAME predicate as the presence evaluator.
     */
    @Test
    public void resetRetainsTheKeyFobEventEvaluatorAcrossARadioOnlyChange() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String reset = method(handler, "    private static void reset(JSONObject next) throws Exception {");
        assertTrue(reset.contains(
                "boolean radioOnlyChange = java.util.Objects.equals(presenceModeGeneration, nextModeGeneration)\n"
                + "                && KeyFobPresenceEvaluator.canRetainAcrossRadioChange(\n"
                + "                        config, effectiveConfig, next, nextEffective);"));
        assertTrue(reset.contains("boolean retainPresence = keyFobPresence != null && radioOnlyChange;"));
        // A1: the event evaluator is also kept when no fob rule changed (same mode generation), and
        // a kept evaluator adopts the new revision; the presence evaluator keeps the radio-only rule.
        assertTrue(reset.contains(
                "boolean fobRulesUnchanged = java.util.Objects.equals(presenceModeGeneration, nextModeGeneration)\n"
                + "                && KeyFobEventEvaluator.canRetainAcrossSettingsChange(effectiveConfig, nextEffective);"));
        assertTrue(reset.contains("boolean retainKeyFob = keyFob != null && (radioOnlyChange || fobRulesUnchanged);"));
        assertTrue(reset.contains("if (!retainKeyFob)\n"
                + "            keyFob = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting("));
        assertTrue(reset.contains("        else keyFob.adoptRevision(effectiveConfig.optLong(\"revision\"));\n"
                + "        if (!retainPresence)\n"
                + "            keyFobPresence = new KeyFobPresenceEvaluator("));
        assertEquals(1, count(reset, "adoptRevision("));
        assertFalse(reset.contains("keyFobPresence.adopt"));
        // Exactly one predicate evaluation each, one construction site per evaluator.
        assertEquals(1, count(reset, "canRetainAcrossRadioChange("));
        assertEquals(1, count(reset, "canRetainAcrossSettingsChange("));
        assertEquals(1, count(reset, "new KeyFobEventEvaluator("));
        assertEquals(1, count(reset, "new KeyFobPresenceEvaluator("));
        // The predicate order is unchanged: radio-only first, then the fob-rule predicate, then both
        // retention decisions, before config/effectiveConfig are replaced.
        int radio = reset.indexOf("boolean radioOnlyChange =");
        int rules = reset.indexOf("boolean fobRulesUnchanged =", radio);
        int presence = reset.indexOf("boolean retainPresence =", rules);
        int fob = reset.indexOf("boolean retainKeyFob =", presence);
        int replace = reset.indexOf("config = new JSONObject(next.toString());", fob);
        assertTrue(radio > 0 && rules > radio && presence > rules && fob > presence && replace > fob);
        // The rest of reset() is as before: phones rebuilt, runtime reset, listeners woken.
        assertTrue(reset.contains("PHONES.clear();"));
        assertTrue(reset.contains("runtime = new JSONObject().put(\"active\", false).put(\"readerRegistered\", false).put(\"radioOwned\", false);"));
        assertTrue(reset.contains("changed();"));
        assertTrue(reset.contains("pendingActuation = null;"));
    }

    /** The other actor's methods in this file were left exactly as found (pre-existing hygiene items). */
    @Test
    public void concurrentActorsMethodsAreUntouched() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        assertEquals(1, count(handler, "    private static void confirmLegacyUnlock(ActionDecision decision, JSONObject settings,"));
        assertEquals(1, count(handler, "    private static boolean legacyUnlockResendAllowed(ActionDecision decision, BydDataCollector collector)"));
        assertEquals(1, count(handler, "    public static void logCollectorLiveness() {"));
        // None of the frozen methods gained a fob-latency hook.
        String[] frozen = {
                "    private static void confirmLegacyUnlock(ActionDecision decision, JSONObject settings,",
                "    private static boolean legacyUnlockResendAllowed(ActionDecision decision, BydDataCollector collector)",
                "    public static void logCollectorLiveness() {",
                "    private static String applyCollectorAfterRadioChange() {",
                "    private static void mergeApplied(JSONObject applied) throws Exception {"};
        for (String signature : frozen) {
            String body = method(handler, signature);
            for (String hook : new String[] {"seedKeyFob", "relockGrace", "lastDoorOpenedElapsed",
                    "noteProximityUnlockDispatched", "phoneLockBlockedText"})
                assertFalse(signature + " contains " + hook, body.contains(hook));
        }
    }

    /**
     * A2 (wiring half; the evaluator behaviour is in KeyFobEventEvaluatorTest): a lock OverDrive
     * dispatched and confirmed by readback seeds the fob arming rail, after the confirmed record and
     * before the outcome notification, through a helper that refuses on Di5 and without the fob
     * locking opt-in. The "already locked" paths and the legacy unlock path never seed.
     */
    @Test
    public void confirmedProximityLockSeedsTheFobRailAfterTheConfirmedRecord() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        // S2 (18 Oct): the readback and its confirmed branch run on the confirm job, not under ACTION.
        String decision = method(handler, "    private static void runProximityConfirmJob(ConfirmJob job, ActionDecision decision, JSONObject settings,");
        String gate = method(handler, "    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {");
        assertEquals(1, count(decision, "seedKeyFobArmingAfterProximityLock("));
        assertEquals(0, count(gate, "seedKeyFobArmingAfterProximityLock("));
        assertTrue(decision.contains(
                "                recordAction(action, \"confirmed\", confirmed);\n"
                + "                if (\"lock\".equals(action)) seedKeyFobArmingAfterProximityLock(decision);\n"
                + "                notifyOutcome(decision, settings, \"confirmed\", confirmed);"));
        // The pinned confirmation block stays contiguous; the seed is strictly after it.
        int confirmed = decision.indexOf("if (confirmation == DiLink5GateActuationPolicy.Confirmation.CONFIRMED) {\n"
                + "                lastCommandConfirmed = true;\n"
                + "                decision.satisfied();\n"
                + "                String confirmed = driverDoorStateConfirmed(action);\n"
                + "                recordAction(action, \"confirmed\", confirmed);");
        int seed = decision.indexOf("seedKeyFobArmingAfterProximityLock(decision);", confirmed);
        int notify = decision.indexOf("notifyOutcome(decision, settings, \"confirmed\"", seed);
        assertTrue(confirmed > 0 && seed > confirmed && notify > seed);
        // Never from the "Driver door is already locked" short-circuits (still in the gate).
        assertEquals(2, count(gate, "\"Driver door is already \""));
        for (int at = gate.indexOf("\"Driver door is already \""); at >= 0;
                at = gate.indexOf("\"Driver door is already \"", at + 1)) {
            String tail = gate.substring(at, gate.indexOf("return;", at));
            assertFalse(tail.contains("seedKeyFob"));
        }
        String helper = method(handler, "    private static void seedKeyFobArmingAfterProximityLock(ActionDecision decision) {");
        assertTrue(helper.contains("if (DiLink5Platform.isSelected()) return;"));
        assertTrue(helper.contains("synchronized (STATE) {"));
        assertTrue(helper.contains("refreshSettings();"));
        assertTrue(helper.contains("if (!config.optBoolean(\"keyFobEnabled\") || !KeyFobAccessMonitor.platformSupported()\n"
                + "                        || !config.optBoolean(\"keyFobLockEnabled\")) return;"));
        // S4 (18 Oct): the quiet period is the postLockQuietMs setting, read live under STATE.
        assertTrue(helper.contains("quietMs = RemoteAccessSettings.postLockQuietMs(effectiveConfig);"));
        assertTrue(helper.contains("applied = keyFob.seedAbsence(SystemClock.elapsedRealtime(), quietMs);"));
        assertTrue(helper.contains("catch (Throwable ignored)"));
        assertTrue(helper.contains("\"Proximity: fob armingSeeded source=\" + decision.source"));
        assertTrue(helper.contains("+ \" quietMs=\" + quietMs"));
        assertFalse(helper.contains("+ \" quietMs=\" + KeyFobEventEvaluator.POST_LOCK_QUIET_MS"));
        // The seed never issues or re-sends a vehicle command.
        assertFalse(helper.contains("VehicleCommandRouter"));
        assertFalse(helper.contains("recordAction("));
        assertFalse(method(handler, "    private static void confirmLegacyUnlock(ActionDecision decision, JSONObject settings,")
                .contains("seedKeyFob"));
        assertEquals(1, count(handler, "keyFob.seedAbsence("));
        assertEquals(2, count(handler, "new VehicleCommandRouter.UnlockCommand(false)"));
    }

    /**
     * B4 (wiring half; the predicate is in KeyFobPresenceEvaluatorTest): a lock within 15 s of a
     * dispatched proximity unlock is held unless a door opened since. Checked after the first
     * cooldown and before the door read, for "lock" and non-Di5 only; the door observer is
     * registered lazily from the unlock dispatch; the three dispatch sites that write
     * lastActionKind are unchanged, so a manual fob unlock never arms the grace.
     */
    @Test
    public void relockGraceIsCheckedAfterTheCooldownAndOnlyForLocks() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = method(handler, "    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {");
        assertEquals(1, count(decision, "KeyFobPresenceEvaluator.relockGraceBlocks(lastActionKind, lastActionElapsed,"));
        // S4 (18 Oct): the grace is the relockGraceMs setting of the effective configuration (10 s default).
        assertTrue(decision.contains(
                "            long relockGraceMs = RemoteAccessSettings.relockGraceMs(settings);\n"
                + "            if (\"lock\".equals(action) && !DiLink5Platform.isSelected()\n"
                + "                    && KeyFobPresenceEvaluator.relockGraceBlocks(lastActionKind, lastActionElapsed,\n"
                + "                            lastDoorOpenedElapsed, SystemClock.elapsedRealtime(), relockGraceMs)) {\n"
                + "                recordAction(action, \"blocked\", \"Relock grace: a door must open or \" + relockGraceMs / 1000\n"
                + "                        + \" s must pass after the proximity unlock\");\n"
                + "                return;\n"
                + "            }\n"
                + "            int[] doors = collector.readAllDoorOpenStates();"));
        assertFalse(decision.contains("15 s must pass"));
        int firstCooldown = decision.indexOf("recordAction(action, \"blocked\", \"Cooldown active\");");
        int grace = decision.indexOf("relockGraceBlocks(", firstCooldown);
        int doors = decision.indexOf("int[] doors = collector.readAllDoorOpenStates();", grace);
        int secondCooldown = decision.indexOf("recordAction(action, \"blocked\", \"Cooldown active\");", doors);
        assertTrue(firstCooldown > 0 && grace > firstCooldown && doors > grace && secondCooldown > doors);
        assertEquals(2, count(handler, "\"Cooldown active\""));
        // The observer is registered once the first proximity unlock has been accepted.
        assertEquals(1, count(decision, "noteProximityUnlockDispatched(collector)"));
        int accepted = decision.indexOf(
                "recordAction(action, \"accepted\", \"Local command accepted; awaiting driver-door readback\" + railDetail);");
        int note = decision.indexOf("if (\"unlock\".equals(action)) noteProximityUnlockDispatched(collector);", accepted);
        int job = decision.indexOf("pendingConfirmJob = job;", note);
        assertTrue(accepted > 0 && note > accepted && job > note);
        // S2: the legacy unlock hook runs on the confirm job, after the accepted record was written under ACTION.
        String confirm = method(handler, "    private static void runProximityConfirmJob(ConfirmJob job, ActionDecision decision, JSONObject settings,");
        assertEquals(1, count(confirm, "if (legacyDispatch && \"unlock\".equals(action)) {"));
        assertEquals(0, count(decision, "if (legacyDispatch && \"unlock\".equals(action)) {"));
        String note2 = method(handler, "    private static void noteProximityUnlockDispatched(BydDataCollector collector) {");
        assertTrue(note2.contains("if (DiLink5Platform.isSelected() || doorOpenObserverRegistered) return;"));
        assertTrue(note2.contains("collector.addDoorStateListener(DOOR_OPEN_OBSERVER);"));
        assertTrue(note2.contains("catch (Throwable ignored)"));
        assertFalse(note2.contains("removeDoorStateListener"));
        // The observer stores only the elapsed time of an "open" edge.
        assertTrue(handler.contains(
                "    private static final BydDataCollector.DoorStateListener DOOR_OPEN_OBSERVER = (area, state) -> {\n"
                + "        if (state == com.overdrive.app.byd.bodywork.BodyworkConstants.STATE_OPEN)\n"
                + "            lastDoorOpenedElapsed = SystemClock.elapsedRealtime();\n"
                + "    };"));
        assertTrue(handler.contains("private static volatile long lastDoorOpenedElapsed = -1;"));
        assertEquals(1, count(handler, "lastDoorOpenedElapsed = SystemClock.elapsedRealtime();"));
        // Dispatch sites unchanged: Di5, legacy, legacy re-send. Nothing else writes the action kind.
        assertEquals(3, count(handler, "lastActionKind = action;"));
        assertEquals(3, count(handler, "lastActionElapsed = SystemClock.elapsedRealtime();"));
        // No new monitor in the handler: STATE, MUTATION, ACTION only.
        assertEquals(0, count(handler, "synchronized (DOOR"));
        assertFalse(handler.contains("new Object(); // relock"));
        // B2: the daemon snapshot log reports the live cadence and the required sample count.
        String diagnostic = method(handler, "    private static void logKeyFobPresenceDiagnostic(KeyFobPresenceEvaluator.Snapshot presence,");
        assertTrue(diagnostic.contains(" windowMs=\" + presence.windowMs\n"
                + "                + \" intervalMs=\" + presence.pollIntervalMs + \" requiredSamples=\" + presence.requiredSamples"));
    }

    /**
     * A3: the phone-departure block text is chosen by a side-effect-free helper; the decision logic
     * (the same {@code decision.valid()} check at the same place) is unchanged and the original text
     * still exists exactly once, inside the helper.
     */
    @Test
    public void alreadyLockedPhoneDepartureBlockTextNoLongerBlamesANearbyKey() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String decision = method(handler, "    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {");
        assertTrue(decision.contains(
                "            if (!decision.valid()) {\n"
                + "                recordAction(action, \"blocked\", decision.blockedReason(\"lock\".equals(action)\n"
                + "                        ? decision.requiresBluetooth\n"
                + "                            ? phoneLockBlockedText()\n"
                + "                            : \"Fresh confirmed keyfob departure is required\"\n"
                + "                        : \"Newer signal revoked this transition\"));\n"
                + "                return;\n"
                + "            }"));
        assertEquals(1, count(decision, "phoneLockBlockedText()"));
        assertEquals(1, count(handler, "    private static String phoneLockBlockedText() {"));
        assertEquals(2, count(handler, "phoneLockBlockedText()"));
        String literal = "\"Every active Bluetooth phone must confirm Away; a nearby key prevents locking\"";
        assertEquals(1, count(handler, literal));
        String helper = method(handler, "    private static String phoneLockBlockedText() {");
        assertTrue(helper.contains(literal));
        assertTrue(helper.contains("\"presence_search_paused_locked\".equals("));
        assertTrue(helper.contains("config.optBoolean(\"keyFobEnabled\") && config.optBoolean(\"keyFobLockEnabled\")"));
        assertTrue(helper.contains("return \"Driver door is already locked; the key fob search is paused while locked\";"));
        assertTrue(helper.contains("catch (Throwable ignored)"));
        // No vehicle read, settings refresh or state mutation: it only picks a message.
        for (String forbidden : new String[] {"collector", "refreshSettings", "recordAction(", "readDoorLockState",
                "invalidate(", "vetoFreshApproach", " = "})
            assertFalse(forbidden, helper.contains(forbidden));
        // The new text does not collide with the already-at-target pins of the gate tests.
        assertFalse(helper.contains("\"Driver door is already \" + ("));
    }

    /** Part C was removed: the relay reply is unchanged and no wake search exists anywhere. */
    @Test
    public void relayKeyFobIsUnchangedAndNoWakeSearchExists() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        assertEquals(0, count(handler, "searchRequested"));
        assertEquals(0, count(handler, "keyFobWakeSearchEnabled"));
        int start = handler.indexOf("    private static void relayKeyFob(");
        int end = handler.indexOf("    private static String lastPresenceDiagnostic", start);
        assertTrue(start > 0 && end > start);
        String relay = handler.substring(start, end);
        String reply = "HttpResponse.sendJson(out, new JSONObject().put(\"success\", true).toString());";
        assertEquals(1, count(relay, reply));
        assertEquals(2, count(handler, reply));
        for (String hook : new String[] {"seedKeyFob", "relockGrace", "searchRequested",
                "noteProximityUnlockDispatched", "lastDoorOpenedElapsed"})
            assertFalse(hook, relay.contains(hook));
        // The relay still rejects a stale revision before the evaluator sees the envelope.
        int revoked = relay.indexOf("|| observation.revision != settings.optLong(\"revision\")) {");
        int observe = relay.indexOf("evaluated = keyFob.observe(observation, SystemClock.elapsedRealtime(),", revoked);
        assertTrue(revoked > 0 && observe > revoked);
        // The only arrival assignment in the evaluator is still the mask-8 branch, and the handler
        // still calls performKeyFob for ARRIVAL only.
        String evaluator = readRepositoryFile("app/src/main/java/com/overdrive/app/remoteaccess/KeyFobEventEvaluator.java");
        assertEquals(1, count(evaluator, "event = ProximityEvaluator.Event.ARRIVAL;"));
        assertTrue(evaluator.contains("} else if (value.mask == 8) {"));
        assertTrue(evaluator.indexOf("} else if (value.mask == 8) {") < evaluator.indexOf("event = ProximityEvaluator.Event.ARRIVAL;"));
        assertTrue(evaluator.contains("if (!quiet && driverLock == 2 && !lastNear && (armed || mode == ProximityEvaluator.UnlockTrigger.NEAR))\n"
                + "                    event = ProximityEvaluator.Event.ARRIVAL;"));
        assertTrue(relay.contains("if (evaluated.event == ProximityEvaluator.Event.ARRIVAL) {"));
        assertEquals(1, count(handler, "performKeyFob(observation, settings, evaluated.event);"));
    }

    /** A disabled safe-zone option must neither arm the parked-radio latch nor register its door observer. */
    @Test
    public void parkedBluetoothLatchIsOptInAndDoesNotUseUnlockDispatchHookOnLock() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        int performStart = handler.indexOf("    private static void perform(ProximityEvaluator.Snapshot reading, JSONObject settings, long observed,");
        int performEnd = handler.indexOf("    private static void performKeyFob(", performStart);
        assertTrue(performStart > 0 && performEnd > performStart);
        String perform = handler.substring(performStart, performEnd);
        assertTrue(perform.contains("if (!arriving && settings.optBoolean(\"parkedBluetoothSafeZoneEnabled\"))"));
        assertTrue(perform.contains("registerDoorOpenObserver(BydDataCollector.getInstance())"));
        assertFalse(perform.contains("ensureDoorOpenObserver"));
        assertFalse(perform.contains("noteProximityUnlockDispatched"));

        String satisfied = handler.substring(handler.indexOf("@Override void satisfied() throws Exception {", performStart),
                handler.indexOf("private boolean capturedEpisodeCurrent", performStart));
        assertTrue(satisfied.contains("config.optBoolean(\"parkedBluetoothSafeZoneEnabled\")"));
        assertTrue(satisfied.contains("!parkedBluetoothExplicitOnOverride"));
        assertTrue(satisfied.indexOf("parkedBluetoothLockConfirmed = true")
                > satisfied.indexOf("config.optBoolean(\"parkedBluetoothSafeZoneEnabled\")"));
    }

    /** Explicit Bluetooth ON from RadioControl clears the daemon-owned directive before the next reply. */
    @Test
    public void explicitAutomationBluetoothOnClearsParkedSafeZoneLatch() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String clear = method(handler, "    public static void noteBluetoothExplicitlyEnabled() {");
        for (String field : new String[] {"parkedBluetoothLockConfirmed = false",
                "parkedBluetoothDepartureOnly = false",
                "parkedBluetoothLockConfirmedAtElapsedMs = -1",
                "parkedBluetoothSafeZoneDisableDirective = false",
                "parkedBluetoothSafeZoneDirectiveAtElapsedMs = -1",
                "parkedBluetoothExplicitOnOverride = true"})
            assertTrue(field, clear.contains(field));
        String radio = readRepositoryFile("app/src/main/java/com/overdrive/app/byd/RadioControl.java");
        int save = radio.indexOf("UnifiedConfigManager.setBluetoothKeepAliveEnabled(enable)");
        int explicitOn = radio.indexOf("if (enable)", save);
        int clearCall = radio.indexOf("RemoteAccessApiHandler.noteBluetoothExplicitlyEnabled()", explicitOn);
        int dispatch = radio.indexOf("String cmd =", clearCall);
        assertTrue(save > 0 && explicitOn > save && clearCall > explicitOn && dispatch > clearCall);
    }

    /** Every latch clear also clears the departure-only flavour; the normal lock path resets it to a full lock. */
    @Test
    public void departureOnlyFlagIsClearedWithEveryLatchClear() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        int clears = count(handler, "parkedBluetoothLockConfirmed = false;");
        assertTrue(clears >= 8);
        for (int at = handler.indexOf("parkedBluetoothLockConfirmed = false;"); at >= 0;
                at = handler.indexOf("parkedBluetoothLockConfirmed = false;", at + 1)) {
            String next = handler.substring(at, handler.indexOf(";", handler.indexOf("\n", at) + 1) + 1);
            assertTrue(next, next.contains("parkedBluetoothDepartureOnly = false;"));
        }
        int performStart = handler.indexOf("    private static void perform(ProximityEvaluator.Snapshot reading, JSONObject settings, long observed,\n"
                + "                                String instance, long serial, String generation)");
        String satisfied = handler.substring(handler.indexOf("@Override void satisfied() throws Exception {", performStart),
                handler.indexOf("@Override void lockBlockedInsideSafeZone(", performStart));
        int set = satisfied.indexOf("parkedBluetoothLockConfirmed = true;");
        assertTrue(set > 0 && satisfied.indexOf("parkedBluetoothDepartureOnly = false;", set) > set);
        assertFalse(satisfied.contains("parkedBluetoothDepartureOnly = true"));
        // Exactly one place arms the departure-only flavour.
        assertEquals(1, count(handler, "parkedBluetoothDepartureOnly = true;"));
    }

    /** Only a confident Inside fix that blocks a lock, with the Bluetooth option on, can reach the hook. */
    @Test
    public void departureOnlyHookRunsOnlyForALockBlockedInsideASafeZone() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String bind = method(handler, "        final boolean bindSafeZones(JSONObject settings) {");
        assertTrue(bind.contains("safeZoneLockBlockedInside = safeZoneLease == null && \"lock\".equals(action)\n"
                + "                    && \"inside_safe_zone\".equals(result.actionReason(action)) && result.shouldDisableBluetooth();"));
        String decision = handler.substring(handler.indexOf("    private static void performDecision(ActionDecision decision, JSONObject settings)"));
        int lockEnabled = decision.indexOf("if (!settings.optBoolean(\"unlock\".equals(action) ? \"unlockEnabled\" : \"lockEnabled\")) {");
        int bound = decision.indexOf("if (!decision.bindSafeZones(live)) {");
        int hook = decision.indexOf("if (decision.safeZoneLockBlockedInside) decision.lockBlockedInsideSafeZone(live);");
        int blocked = decision.indexOf("recordAction(action, \"blocked\", decision.blockedReason(\"Safe-zone restriction paused this action\"));");
        assertTrue(lockEnabled > 0 && bound > lockEnabled && hook > bound && blocked > hook);
        assertEquals(1, count(handler, "decision.lockBlockedInsideSafeZone("));
        // Key-fob decisions keep the no-op default: they never arm the phone walk-away latch.
        int fobStart = handler.indexOf("    private static void performKeyFob(");
        int fobEnd = handler.indexOf("    /** Both sources share the same final command guards and outcome delivery. */", fobStart);
        assertTrue(fobStart > 0 && fobEnd > fobStart);
        assertFalse(handler.substring(fobStart, fobEnd).contains("lockBlockedInsideSafeZone"));
    }

    /** The phone hook re-validates like a dispatched lock before arming, and arms once per episode. */
    @Test
    public void departureOnlyLatchNeedsAValidFreshWalkAwayAndIsIdempotent() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String hook = method(handler, "            @Override void lockBlockedInsideSafeZone(JSONObject live) throws Exception {");
        int sourceValid = hook.indexOf("!sourceValid()");
        int fresh = hook.indexOf("!BluetoothProximityDecisionGuard.fresh(observed, SystemClock.elapsedRealtime(), freshnessMs)");
        int power = hook.indexOf("readVehiclePowerNow() != 0 || UnifiedConfigManager.isVehicleOnOnlyMode()");
        int keepAlive = hook.indexOf("!UnifiedConfigManager.isBluetoothKeepAliveEnabled()) return;");
        int arm = hook.indexOf("parkedBluetoothLockConfirmed = true;");
        assertTrue(sourceValid > 0 && fresh > sourceValid && power > fresh && keepAlive > power && arm > keepAlive);
        assertTrue(hook.indexOf("live.optBoolean(\"dryRun\", true)") < sourceValid);
        assertTrue(hook.indexOf("settings.optBoolean(\"dryRun\", true)") < sourceValid);
        for (String gate : new String[] {"refreshSettings();", "!capturedEpisodeCurrent(current)",
                "current.satisfiedEpisodeId == reading.episodeId", "!config.optBoolean(\"lockEnabled\")",
                "!config.optBoolean(\"restrictLockToOutsideSafeZones\")",
                "!config.optBoolean(\"parkedBluetoothSafeZoneEnabled\")",
                "parkedBluetoothExplicitOnOverride || lastObservedPower != 0",
                "episode.equals(parkedBluetoothDepartureOnlyEpisode)) return;"}) {
            assertTrue(gate, hook.contains(gate));
            assertTrue(gate, hook.indexOf(gate) < arm);
        }
        // Idempotent: the episode is remembered before the live-latch check, and a live latch keeps its time.
        int remember = hook.indexOf("parkedBluetoothDepartureOnlyEpisode = episode;");
        int liveLatch = hook.indexOf("&& scope.equals(parkedBluetoothLockConfirmedScope)) return;");
        assertTrue(remember > 0 && liveLatch > remember && arm > liveLatch);
        assertTrue(hook.contains("&& lastDoorOpenedElapsed < parkedBluetoothLockConfirmedAtElapsedMs"));
        assertTrue(hook.indexOf("parkedBluetoothDepartureOnly = true;") > arm);
        assertTrue(hook.contains("parkedBluetoothLockConfirmedScope = scope;"));
    }

    /** A departure-only latch swaps "still locked" for "closures shut" and always needs door edges. */
    @Test
    public void departureOnlyDisableNeedsShutClosuresAndDoorEdgesOnEveryPlatform() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        String compute = method(handler, "    private static boolean computeParkedBluetoothSafeZoneDisable(int power) {");
        // Existing gates stay: key inside (#715), vehicle-on-only, explicit ON, power, scope, door edge.
        for (String gate : new String[] {"RemoteAccessSettings.lockWithKeyInside(config) && KEY_INSIDE.latched()",
                "UnifiedConfigManager.isVehicleOnOnlyMode()", "parkedBluetoothExplicitOnOverride",
                "!(config + \"|\" + presenceModeGeneration).equals(parkedBluetoothLockConfirmedScope)",
                "if (lastDoorOpenedElapsed >= lockConfirmedAt) {",
                "config.optLong(\"revision\") != settingsSnapshot.optLong(\"revision\")"})
            assertTrue(gate, compute.contains(gate));
        int captured = compute.indexOf("departureOnly = parkedBluetoothDepartureOnly;");
        int edges = compute.indexOf("if ((departureOnly || !DiLink5Platform.isSelected()) && !doorOpenObserverRegistered) return false;");
        int vehicle = compute.indexOf("if (departureOnly ? !parkedClosuresShut() : !parkedVehicleStillLocked()) {");
        int zones = compute.indexOf("ProximitySafeZonePolicy.Result safeZones = safeZoneSnapshot(settingsSnapshot);");
        assertTrue(captured > 0 && edges > captured && vehicle > edges && zones > vehicle);
        assertTrue(compute.indexOf("|| parkedBluetoothDepartureOnly != departureOnly", zones) > zones);
        String closures = method(handler, "    private static boolean parkedClosuresShut() {");
        assertTrue(closures.contains("readAllDoorOpenStates()"));
        assertTrue(closures.contains("DiLink5GateActuationPolicy.doorBlockReason(doors) == null"));
        assertFalse(closures.contains("readDoorLockState"));
        assertFalse(closures.contains("readDiLink5GateDoorLockEvidence"));
        String locked = method(handler, "    private static boolean parkedVehicleStillLocked() {");
        assertTrue(locked.indexOf("if (lockState != BydDataCollector.DOOR_STATE_LOCK) return false;")
                < locked.indexOf("return parkedClosuresShut();"));
    }

    /** Status exposes the flavour, and the page names the walk-away (not a lock) when it applies. */
    @Test
    public void departureOnlyIsReportedAndExplainedOnThePage() throws Exception {
        String handler = readRepositoryFile(HANDLER);
        assertTrue(handler.contains("departureOnly = walkAwayLockConfirmed && parkedBluetoothDepartureOnly;"));
        assertTrue(handler.contains(".put(\"departureOnly\", departureOnly)"));
        String js = readRepositoryFile("app/src/main/assets/web/shared/remote-access.js");
        int walkAway = js.indexOf("&& parkedBluetooth.departureOnly === true) {");
        int walkAwayText = js.indexOf("tr('safe_zones_bluetooth_disabled_walk_away_detail',", walkAway);
        int lockText = js.indexOf("tr('safe_zones_bluetooth_disabled_detail',", walkAway);
        assertTrue(walkAway > 0 && walkAwayText > walkAway && lockText > walkAwayText);
        String en = readRepositoryFile("app/src/main/assets/web/i18n/en.json");
        assertTrue(en.contains("\"safe_zones_bluetooth_disabled_walk_away_detail\": \"The walk-away is confirmed"));
        assertTrue(en.contains("or a confirmed walk-away while automatic lock is restricted to outside safe zones"));
        String html = readRepositoryFile("app/src/main/assets/web/local/remote-access.html");
        assertTrue(html.contains("or a confirmed walk-away while automatic lock is restricted to outside safe zones"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
