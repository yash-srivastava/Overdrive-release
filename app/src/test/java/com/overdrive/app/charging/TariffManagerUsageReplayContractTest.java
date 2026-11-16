package com.overdrive.app.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Source-level concurrency contract for authoritative tariff-usage replay. */
public class TariffManagerUsageReplayContractTest {

    @Test
    public void usageReplayReadsPatchesAndPersistsInsideOneConfigLock()
            throws IOException {
        String method = reconcileUsageSource();

        int lock = method.indexOf("UnifiedConfigManager.runUnderConfigLock");
        int durableRead = method.indexOf(
                "UnifiedConfigManager.readDurableConfigStrict()", lock);
        int profileLookup = method.indexOf(
                "id.equals(candidate.optString(\"id\", \"\"))", durableRead);
        int useCountMutation = method.indexOf(
                "target.put(\"useCount\", normalizedCount)", profileLookup);
        int lastUsedMutation = method.indexOf(
                "target.put(\"lastUsedAt\", normalizedLastUsed)", useCountMutation);
        int persist = method.indexOf(
                "UnifiedConfigManager.saveConfig(root)", lastUsedMutation);
        int lockEnd = method.indexOf("\n            });", persist);
        int publishProfiles = method.indexOf(
                "profiles = result.profiles", lockEnd);
        int publishDefault = method.indexOf(
                "defaultTariffId = result.defaultTariffId", publishProfiles);

        assertTrue(lock >= 0);
        assertTrue(durableRead > lock);
        assertTrue(profileLookup > durableRead);
        assertTrue(useCountMutation > profileLookup);
        assertTrue(lastUsedMutation > useCountMutation);
        assertTrue(persist > lastUsedMutation);
        assertTrue(lockEnd > persist);
        assertTrue(publishProfiles > lockEnd);
        assertTrue(publishDefault > publishProfiles);
    }

    @Test
    public void usageReplayCannotRewriteTariffOrDefaultSnapshots()
            throws IOException {
        String method = reconcileUsageSource();

        assertEquals(1, occurrences(method,
                "target.put(\"useCount\", normalizedCount)"));
        assertEquals(1, occurrences(method,
                "target.put(\"lastUsedAt\", normalizedLastUsed)"));
        assertEquals(2, occurrences(method, "target.put("));
        assertFalse(method.contains("save()"));
        assertFalse(method.contains("updateSection("));
        assertFalse(method.contains("KEY_DEFAULT_ID"));
        assertFalse(method.contains("root.put("));
    }

    @Test
    public void failedPersistenceCannotPublishTheCandidateSnapshot()
            throws IOException {
        String method = reconcileUsageSource();

        int rejectedSave = method.indexOf(
                "if (!UnifiedConfigManager.saveConfig(root))");
        int failureResult = method.indexOf(
                "return UsageReconcileResult.failure(", rejectedSave);
        int successGuard = method.indexOf(
                "if (!result.success)", failureResult);
        int failedReturn = method.indexOf(
                "return false;", successGuard);
        int publish = method.indexOf(
                "profiles = result.profiles", failedReturn);

        assertTrue(rejectedSave >= 0);
        assertTrue(failureResult > rejectedSave);
        assertTrue(successGuard > failureResult);
        assertTrue(failedReturn > successGuard);
        assertTrue(publish > failedReturn);
    }

    @Test
    public void tariffMutationsUseOneFreshLockedWriteBase()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        int start = source.indexOf(
                "private synchronized MutationCommit commitMutation(");
        int end = source.indexOf(
                "private static PublishedImage imageFromSection", start);
        String method = source.substring(start, end);

        int lock = method.indexOf(
                "UnifiedConfigManager.runUnderConfigLock");
        int read = method.indexOf(
                "new TariffDocument(readDurableConfigLocked())", lock);
        int patch = method.indexOf(
                "mutation.apply(document)", read);
        int save = method.indexOf(
                "UnifiedConfigManager.saveConfig(document.root)", patch);

        assertTrue(lock >= 0);
        assertTrue(read > lock);
        assertTrue(patch > read);
        assertTrue(save > patch);
        assertFalse(method.contains("updateSection("));
        assertFalse(method.substring(read, save)
                .contains("profiles"));
    }

    @Test
    public void verifiedLoadCannotValidateALaterCommitThanItReturns()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        int start = source.indexOf(
                "public static JSONObject loadVerifiedConfig()");
        int end = source.indexOf(
                "/** Caller must hold UnifiedConfigManager", start);
        String method = source.substring(start, end);

        assertTrue(method.contains(
                "return UnifiedConfigManager.readDurableConfigStrict()"));
        assertFalse(method.contains("forceReload()"));
        assertFalse(method.contains("loadConfig()"));
    }

    @Test
    public void tariffMutationCommitsRepriceIntentBeforeConfigSave()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        int start = source.indexOf(
                "private synchronized MutationCommit commitMutation(\n"
                        + "            String repriceKey");
        int end = source.indexOf(
                "private static PublishedImage imageFromSection", start);
        String method = source.substring(start, end);

        assertOrdered(
                method,
                "mutation.apply(document)",
                "queuePendingReprice(document.section, repriceKey)",
                "document.root.put(SECTION, document.section)",
                "UnifiedConfigManager.saveConfig(document.root)");
        assertTrue(source.contains(
                "commitMutation(p.getId(), document ->"));
        assertTrue(source.contains(
                "commitMutation(id, document ->"));
        assertEquals(2, occurrences(
                source, "commitMutation(REPRICE_ALL, document ->"));
    }

    @Test
    public void pendingReplayClearsOnlyAfterDatabaseCompletion()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        int start = source.indexOf("private void replayPendingReprices()");
        int end = source.indexOf(
                "/**\n     * Reconcile display-only usage metadata", start);
        String replay = source.substring(start, end);

        assertOrdered(
                replay,
                "database.repriceSessionsForTariff(",
                "completePendingReprice(");
        assertTrue(replay.contains("catch (Throwable deferred)"));
        assertTrue(replay.contains("return;"));

        String cleanup = source.substring(
                source.indexOf("public synchronized boolean completePendingReprice("),
                start);
        assertOrdered(
                cleanup,
                "readDurableConfigLocked()",
                "intent.token.equals(tokens.get(intent.key))",
                "pending.remove(intent.key)",
                "section.put(KEY_PENDING_REPRICES",
                "UnifiedConfigManager.saveConfig(root)",
                "publishImage(committed)");
    }

    @Test
    public void pendingReplayNeverCarriesTariffMonitorIntoDatabase()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        String load = between(
                source,
                "public boolean load()",
                "/** Parse and atomically publish");
        String ensureLoaded = between(
                source,
                "private boolean ensureLoaded()",
                "private interface LockedTariffMutation");
        String replay = between(
                source,
                "private void replayPendingReprices()",
                "/**\n     * Reconcile display-only usage metadata");

        assertFalse(source.contains("public synchronized boolean load()"));
        assertOrdered(
                load,
                "synchronized (this)",
                "loadStrict()",
                "\n        }\n\n        // Database repricing",
                "replayPendingReprices()");
        assertFalse(ensureLoaded.contains("synchronized (this)"));
        assertOrdered(
                replay,
                "nextPendingRepriceIntent()",
                "database.repriceSessionsForTariff(intent.tariffId())",
                "completePendingReprice(intent)");
        assertFalse(replay.contains("synchronized (this)"));
        assertTrue(source.contains(
                "private synchronized RepriceIntent nextPendingRepriceIntent()"));
        assertTrue(source.contains(
                "if (!intent.token.equals(tokens.get(intent.key)))"));
    }

    @Test
    public void tariffApiUsesOneRootForTariffsAndGlobalFallbacks()
            throws IOException {
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");
        int start = api.indexOf(
                "private JSONObject handleGetTariffsFromRoot(JSONObject root)");
        int end = api.indexOf(
                "/**\n     * Create or update a tariff", start);
        String handler = api.substring(start, end);

        assertOrdered(
                handler,
                ".toStatusJson(root, loc[0], loc[1])",
                "cfg.loadFromRoot(root)",
                "payload.put(\"globalRate\"",
                "payload.put(\"globalDcRate\"",
                "payload.put(\"currency\"");

        String bootstrap = api.substring(
                api.indexOf("private JSONObject handleGetBootstrap("),
                api.indexOf(
                        "/**\n     * One refresh payload", api.indexOf(
                                "private JSONObject handleGetBootstrap(")));
        assertTrue(bootstrap.contains(
                "() -> handleGetConfigFromRoot(configRoot)"));
        assertTrue(bootstrap.contains(
                "() -> handleGetTariffsFromRoot(configRoot)"));
    }

    @Test
    public void verifiedStatusSerializationCannotRepublishStaleManagerState()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        int start = source.indexOf(
                "public synchronized JSONObject toStatusJson(\n"
                        + "            JSONObject verifiedRoot");
        int end = source.indexOf("\n    }\n}", start);
        String method = source.substring(start, end);

        assertTrue(method.contains(
                "resolveInCircleSnapshot(image.profiles"));
        assertFalse(method.contains("publishImage("));
        assertFalse(method.contains("profiles ="));
        assertFalse(method.contains("pendingRepriceKeys ="));
        assertFalse(method.contains("lastLoadFailure ="));
    }

    @Test
    public void legacySingleObjectTariffIsReadableAsOneProfile()
            throws Exception {
        JSONObject lone = new JSONObject()
                .put("id", "home")
                .put("label", "Home")
                .put("lat", 32.08)
                .put("lng", 34.78)
                .put("radiusM", 50)
                .put("acRate", 1.0)
                .put("enabled", true);
        JSONObject section = new JSONObject().put("tariffs", lone);
        JSONArray normalized = TariffManager.tariffsArrayFromSection(section);
        assertEquals(1, normalized.length());
        assertEquals("home", normalized.getJSONObject(0).getString("id"));

        JSONObject root = new JSONObject().put("chargingAnalytics", section);
        JSONObject status = TariffManager.getInstance().toStatusJson(root, 0, 0);
        assertEquals(1, status.getJSONArray("tariffs").length());
    }

    @Test
    public void statusMatchesDcOnlyTariffAtCurrentLocation()
            throws Exception {
        JSONObject tariff = new JSONObject()
                .put("id", "dc-only")
                .put("label", "Fast charger")
                .put("lat", 3.339791)
                .put("lng", 101.250876)
                .put("radiusM", 50)
                .put("acRate", 0.0)
                .put("dcRate", 1.25)
                .put("enabled", true);
        JSONObject root = new JSONObject().put(
                "chargingAnalytics",
                new JSONObject().put("tariffs", new JSONArray().put(tariff)));

        JSONObject status = TariffManager.getInstance().toStatusJson(
                root, 3.339791, 101.250876);

        assertEquals("dc-only", status.getString("matchedTariffId"));
    }

    @Test
    public void apiKeepsConfigIntentUntilDatabaseAndCleanupComplete()
            throws IOException {
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");
        int start = api.indexOf(
                "private RepriceOutcome repriceHistory(");
        int end = api.indexOf(
                "/** Current GPS fix", start);
        String reprice = api.substring(start, end);

        assertOrdered(
                reprice,
                "database.repriceSessionsForTariff(",
                ".completePendingReprice(intent)",
                "return completedReprice(changed)");
        assertTrue(reprice.contains("return pendingReprice()"));
        assertTrue(reprice.contains("catch (Throwable t)"));
    }

    @Test
    public void canonicalUpdatePreservesPeerOwnedProfileFields()
            throws Exception {
        JSONObject durable = new JSONObject()
                .put("id", "tariff-1")
                .put("label", "Old")
                .put("lat", 12.0)
                .put("lng", 77.0)
                .put("radiusM", 50)
                .put("acRate", 2.0)
                .put("dcRate", 0.0)
                .put("peerRevision", 19);
        TariffProfile profile = new TariffProfile(durable);
        profile.setLabel("New");

        JSONObject merged =
                TariffManager.mergeCanonicalProfile(
                        durable, profile);

        assertEquals("New", merged.getString("label"));
        assertEquals(19, merged.getInt("peerRevision"));
        assertEquals(19, durable.getInt("peerRevision"));
        assertEquals("Old", durable.getString("label"));
    }

    @Test
    public void newerMutationRotatesIntentTokenAndWildcardSubsumesTargets()
            throws Exception {
        Method queue = TariffManager.class.getDeclaredMethod(
                "queuePendingReprice", JSONObject.class, String.class);
        queue.setAccessible(true);
        JSONObject section = new JSONObject();

        queue.invoke(null, section, "home");
        String first = section
                .getJSONObject("pendingTariffRepriceTokens")
                .getString("home");
        queue.invoke(null, section, "home");
        String second = section
                .getJSONObject("pendingTariffRepriceTokens")
                .getString("home");
        assertNotEquals(first, second);
        assertEquals(1,
                section.getJSONArray("pendingTariffReprices").length());

        queue.invoke(null, section, "");
        JSONArray wildcard =
                section.getJSONArray("pendingTariffReprices");
        String wildcardBefore = section
                .getJSONObject("pendingTariffRepriceTokens")
                .getString("*");
        assertEquals(1, wildcard.length());
        assertEquals("*", wildcard.getString(0));

        queue.invoke(null, section, "office");
        String wildcardAfter = section
                .getJSONObject("pendingTariffRepriceTokens")
                .getString("*");
        assertNotEquals(wildcardBefore, wildcardAfter);
        assertEquals(1,
                section.getJSONArray("pendingTariffReprices").length());
        assertEquals("*",
                section.getJSONArray("pendingTariffReprices")
                        .getString(0));
    }

    @Test
    public void wildcardCompletionClearsPendingKeysNotOnlyTokens()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        String cleanup = between(
                source,
                "synchronized boolean completePendingReprice(RepriceIntent intent)",
                "private synchronized boolean beginPendingRepriceReplay()");

        // The wildcard branch must clear BOTH the pending list and the token
        // map before persisting. Clearing tokens alone leaves "*" in pending,
        // which pendingRepriceTokensFromSection re-issues a legacy token for on
        // the next load — resurrecting the intent and looping the replay
        // (full-history repricing) forever.
        assertOrdered(
                cleanup,
                "REPRICE_ALL.equals(intent.key)",
                "changed = !pending.isEmpty();",
                "pending.clear();",
                "tokens.clear();",
                "section.put(KEY_PENDING_REPRICES",
                "UnifiedConfigManager.saveConfig(root)");
        // Targeted completion keeps its own removal path.
        assertTrue(cleanup.contains("changed = pending.remove(intent.key);"));
        assertTrue(cleanup.contains("tokens.remove(intent.key);"));
    }

    @Test
    public void replayAbortsWhenCompletionMakesNoDurableProgress()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        String replay = between(
                source,
                "private void replayPendingReprices()",
                "/**\n     * Reconcile display-only usage metadata");

        // The guard must trip BEFORE the (expensive) database repricing runs
        // again, compare BOTH key and token (a rotated token is legitimate new
        // work), and only ever record an intent as completed AFTER
        // completePendingReprice reported success.
        assertOrdered(
                replay,
                "nextPendingRepriceIntent()",
                "intent.key.equals(lastCompletedKey)",
                "intent.token.equals(lastCompletedToken)",
                "logger.error(",
                "return;",
                "database.repriceSessionsForTariff(",
                "completePendingReprice(intent)",
                "lastCompletedKey = intent.key;",
                "lastCompletedToken = intent.token;");
    }

    private static String reconcileUsageSource() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        int start = source.indexOf(
                "public synchronized boolean reconcileUsage(");
        int end = source.indexOf(
                "/** Build an immutable manager image", start);
        if (start < 0 || end <= start) {
            throw new AssertionError("Could not isolate reconcileUsage");
        }
        return source.substring(start, end);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle, position > previous);
            previous = position;
        }
    }

    private static String between(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        if (start < 0 || end <= start) {
            throw new AssertionError(
                    "Could not isolate source between " + startNeedle + " and " + endNeedle);
        }
        return source.substring(start, end);
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
        throw new AssertionError("Could not locate " + relativePath);
    }
}
