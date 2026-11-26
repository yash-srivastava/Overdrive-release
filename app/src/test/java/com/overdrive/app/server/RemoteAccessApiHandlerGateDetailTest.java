package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.DiLink5LockRail;
import com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * D1: the DI5 rail detail is appended to {@code recordAction} messages only for non-typed rails,
 * never carries an age, and the numeric gate predicate plus its "unknown" text stay verbatim.
 * {@code RemoteAccessApiHandler} cannot run on the JVM, so the wiring is pinned by source.
 */
public class RemoteAccessApiHandlerGateDetailTest {

    private static BydDataCollector.DiLink5LockEvidence evidence(DiLink5LockRail rail) {
        return new BydDataCollector.DiLink5LockEvidence(
                BydDataCollector.DOOR_STATE_LOCK, 1_000L, rail, 2, 0L);
    }

    @Test
    public void detailSuffixIsEmptyForNullAndTypedAndNamesOtherRails() {
        assertEquals("", DiLink5GateActuationPolicy.detailSuffix(null));
        assertEquals("", DiLink5GateActuationPolicy.detailSuffix(
                BydDataCollector.DiLink5LockEvidence.unknown()));
        assertEquals("", DiLink5GateActuationPolicy.detailSuffix(evidence(DiLink5LockRail.TYPED_CABIN)));
        assertEquals(" (rail=carsvc)", DiLink5GateActuationPolicy.detailSuffix(evidence(DiLink5LockRail.CARSVC)));
        assertEquals(" (rail=ota)", DiLink5GateActuationPolicy.detailSuffix(evidence(DiLink5LockRail.OTA)));
    }

    @Test
    public void detailSuffixNeverCarriesAnAgeSoRecordActionDedupKeepsMatching() {
        for (DiLink5LockRail rail : DiLink5LockRail.values()) {
            String suffix = DiLink5GateActuationPolicy.detailSuffix(evidence(rail));
            assertFalse(suffix, suffix.contains("ageMs"));
            assertFalse(suffix, suffix.matches(".*\\d.*"));
        }
        // Age lives in the log-only description.
        assertEquals("rail=carsvc state=2 ageMs=4500",
                DiLink5GateActuationPolicy.describe(evidence(DiLink5LockRail.CARSVC), 5_500L));
        assertEquals("rail=none state=0 ageMs=-1",
                DiLink5GateActuationPolicy.describe(BydDataCollector.DiLink5LockEvidence.unknown(), 5_500L));
        assertEquals("rail=none state=0 ageMs=-1", DiLink5GateActuationPolicy.describe(null, 5_500L));
    }

    @Test
    public void performDecisionKeepsTheNumericGateAndUnknownTextVerbatim() throws Exception {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java");
        assertTrue(handler.contains("if (before != 1 && before != 2) {"));
        assertTrue(handler.contains("\"Local lock state is unknown\""));
        assertEquals(1, count(handler, "\"Local lock state is unknown\""));
        // The unknown message gets no suffix.
        assertTrue(handler.contains(
                "            if (before != 1 && before != 2) {\n"
                        + "                recordAction(action, \"blocked\", \"Local lock state is unknown\");\n"
                        + "                return;\n"
                        + "            }"));
        // Evidence is read right after the numeric gate state, DI5 only, through the pure policy.
        int before = handler.indexOf("int before = collector.readDoorLockState();");
        int evidence = handler.indexOf(
                "BydDataCollector.DiLink5LockEvidence beforeEvidence =\n"
                        + "                    DiLink5Platform.isSelected() ? collector.readDiLink5GateDoorLockEvidence() : null;",
                before);
        int suffix = handler.indexOf(
                "String railDetail = DiLink5GateActuationPolicy.detailSuffix(beforeEvidence);", evidence);
        int gate = handler.indexOf("if (before != 1 && before != 2) {", suffix);
        assertTrue(before > 0 && evidence > before && suffix > evidence && gate > suffix);
        // Suffix only on the two already-at-target messages and the accepted message.
        assertEquals(2, count(handler,
                "\"Driver door is already \" + (\"unlock\".equals(action) ? \"unlocked\" : \"locked\") + railDetail"));
        assertEquals(1, count(handler,
                "\"Local command accepted; awaiting driver-door readback\" + railDetail"));
        assertEquals(3, count(handler, "+ railDetail"));
        // Age goes to the daemon log, never into a recordAction message.
        assertTrue(handler.contains(
                "com.overdrive.app.daemon.CameraDaemon.log(\"Proximity: DI5 gate evidence at dispatch \""));
        int recordAction = handler.indexOf("private static void recordAction(");
        assertTrue(recordAction > 0);
        for (int at = handler.indexOf("recordAction(action,"); at >= 0 && at < recordAction;
                at = handler.indexOf("recordAction(action,", at + 1)) {
            String call = handler.substring(at, handler.indexOf(");", at));
            assertFalse(call, call.contains("ageMs"));
            assertFalse(call, call.contains("describe("));
        }
        // Other blocked/confirmed texts are unchanged.
        assertTrue(handler.contains("recordAction(action, \"confirmed\", confirmed);"));
        assertTrue(handler.contains(
                "recordAction(action, \"blocked\", decision.blockedReason(\"Signal or lock state expired before command\"));"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
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
