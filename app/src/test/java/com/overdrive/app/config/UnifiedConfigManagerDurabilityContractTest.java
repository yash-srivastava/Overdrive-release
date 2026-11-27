package com.overdrive.app.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONObject;
import org.junit.Test;

/** Source-level durability guards for Android-only config persistence paths. */
public class UnifiedConfigManagerDurabilityContractTest {

    @Test
    public void lifecycleSnapshotDoesNotEnterTheCrossProcessConfigLock()
            throws IOException {
        String source = managerSource();
        String snapshot = between(
                source,
                "fun isVehicleOnOnlyModeSnapshot(): Boolean",
                "// ==================== LISTENERS");

        assertTrue(snapshot.contains("JSONObject(file.readText())"));
        assertTrue(snapshot.contains(
                "length > LIFECYCLE_SNAPSHOT_MAX_BYTES"));
        assertTrue(snapshot.contains(
                ".optString(\"operatingMode\", \"onAndOff\") == \"onOnly\""));
        assertFalse(snapshot.contains("loadConfig()"));
        assertFalse(snapshot.contains("withConfigFileLock"));
        assertFalse(snapshot.contains("cachedConfig"));
    }

    @Test
    public void everyFileWriteFlushesAndSyncsBeforeSuccess() throws IOException {
        String source = managerSource();
        String writer = between(
                source,
                "private fun writeFileAndSync(",
                "private fun syncDirectoryAfterRename(");

        int write = writer.indexOf("output.write(");
        int flush = writer.indexOf("output.flush()", write);
        int permissions = writer.indexOf("file.setWritable(true, false)", flush);
        int sync = writer.indexOf("output.fd.sync()", permissions);

        assertTrue(write >= 0);
        assertTrue(flush > write);
        assertTrue(permissions > flush);
        assertTrue(sync > permissions);
        assertFalse(source.contains("FileWriter("));
    }

    @Test
    public void atomicPrimaryNeverTruncatesDestinationAndPublishesUncertainCommit()
            throws IOException {
        String save = between(
                managerSource(),
                "private fun saveConfigInternal(",
                "// ==================== SECTION GETTERS");

        int tmpWrite = save.indexOf(
                "writeFileAndSync(tmpFile, payload, worldAccessible = true)");
        int rename = save.indexOf(
                "if (tmpFile.renameTo(configFile))", tmpWrite);
        int directorySync = save.indexOf(
                "syncDirectoryAfterRenameWithRetry(configFile.parentFile)",
                rename);
        int durable = save.indexOf(
                "ConfigWriteState.COMMITTED_DURABLE", directorySync);
        int reconcile = save.indexOf(
                "reconcileCommittedRename(", durable);
        int uncertain = save.indexOf(
                "ConfigWriteState.COMMITTED_UNCERTAIN", reconcile);
        int renameFailure = save.indexOf(
                "Atomic rename failed; live config left untouched", uncertain);
        int tmpFailure = save.indexOf(
                "live config left untouched and mutation deferred",
                renameFailure);
        int notCommitted = save.indexOf(
                "ConfigWriteState.NOT_COMMITTED", tmpFailure);

        assertTrue(tmpWrite >= 0);
        assertTrue(rename > tmpWrite);
        assertTrue(directorySync > rename);
        assertTrue(durable > directorySync);
        assertTrue(reconcile > durable);
        assertTrue(uncertain > reconcile);
        assertTrue(renameFailure > uncertain);
        assertTrue(tmpFailure > renameFailure);
        assertTrue(notCommitted > tmpFailure);
        assertFalse(save.contains("writeFileAndSync(configFile"));
        assertFalse(save.contains("FileOutputStream(configFile"));
        assertFalse(save.contains("falling back to direct write"));
    }

    @Test
    public void directorySyncFailureRetriesAndReconcilesCommittedDestination()
            throws IOException {
        String source = managerSource();
        String retry = between(
                source,
                "private fun syncDirectoryAfterRenameWithRetry(",
                "/**\n     * Re-read the destination");
        String reconcile = between(
                source,
                "private fun reconcileCommittedRename(",
                "/**\n     * Mirror the current good config");
        String save = between(
                source,
                "private fun saveConfigLocked(",
                "/**\n     * Truncate/write");

        assertTrue(retry.contains("repeat(DIRECTORY_SYNC_ATTEMPTS)"));
        assertTrue(retry.contains("syncDirectoryAfterRename(directory)"));
        assertTrue(retry.contains("return false"));
        assertTrue(reconcile.contains("configFile.readText()"));
        assertTrue(reconcile.contains("JSONObject(actualPayload)"));
        assertTrue(reconcile.contains(
                "return JSONObject(expectedConfig.toString())"));
        assertOrdered(
                save,
                "val writeResult = saveConfigInternal(config)",
                "if (writeResult.committed)",
                "val committed = writeResult.committedConfig ?: config",
                "cachedConfig = committed",
                "writeBackupCopy(committed)",
                "notifyListeners(\"all\", committed)",
                "return writeResult.committed");
    }

    @Test
    public void directorySyncOnlyWaivesUnsupportedFilesystems()
            throws IOException {
        String sync = between(
                managerSource(),
                "private fun syncDirectoryAfterRename(",
                "private fun isUnsupportedDirectorySync(");
        String unsupported = between(
                managerSource(),
                "private fun isUnsupportedDirectorySync(",
                "/**\n     * Mirror the current good config");

        assertTrue(sync.contains("Os.open("));
        assertTrue(sync.contains("Os.fsync(opened)"));
        assertTrue(sync.contains("throw IOException(\"Directory fsync failed"));
        assertTrue(unsupported.contains("OsConstants.EINVAL"));
        assertTrue(unsupported.contains("OsConstants.ENOSYS"));
        assertTrue(unsupported.contains("OsConstants.ENOTSUP"));
        assertTrue(unsupported.contains("OsConstants.EOPNOTSUPP"));
        assertFalse(unsupported.contains("OsConstants.EIO"));
        assertFalse(unsupported.contains("OsConstants.EACCES"));
        assertFalse(unsupported.contains("OsConstants.EPERM"));
    }

    @Test
    public void bothBackupRenamesSyncBytesAndDirectory() throws IOException {
        String source = managerSource();
        String sticky = between(
                source,
                "private fun writeBackupCopy(",
                "private fun seqOf(");
        String appPrivate = between(
                source,
                "private fun writeAppPrivateBackup(",
                "private fun readAppPrivateBackup(");

        assertOrdered(
                sticky,
                "writeFileAndSync(bakTmp",
                "bakTmp.renameTo(bakFile)",
                "syncDirectoryAfterRename(bakFile.parentFile)");
        assertOrdered(
                appPrivate,
                "writeFileAndSync(staging",
                "staging.renameTo(bak)",
                "syncDirectoryAfterRename(bak.parentFile)");

        assertTrue(sticky.contains(
                "Do NOT fall back to a non-atomic direct"));
        assertTrue(appPrivate.contains(
                "drop the tmp rather than truncate the bak"));
    }

    @Test
    public void stableLockIsTheOnlyCrossProcessLockTarget()
            throws IOException {
        String source = managerSource();
        String target = between(
                source,
                "private fun lockTargetFor(): File",
                "private class ConfigLockUnavailableException");
        String locking = between(
                source,
                "private fun <T> withConfigFileLock(body: () -> T): T",
                "/**\n     * Boolean mutation entry points");

        assertTrue(target.contains("File(LOCK_PATH)"));
        assertFalse(target.contains("File(CONFIG_PATH)"));
        assertTrue(locking.contains(
                "android.os.Process.myUid() != SHELL_DAEMON_UID "
                        + "&& !lockFile.isFile"));
        assertTrue(locking.contains(
                "local mutation must retry after daemon initialization"));
        assertFalse(locking.contains("monitor-only"));

        int reentrantBody = locking.indexOf("return body()");
        int open = locking.indexOf(
                "RandomAccessFile(lockFile, \"rw\")", reentrantBody);
        int acquire = locking.indexOf("lock = tryLockUntil(", open);
        int markHeld = locking.indexOf(
                "holdingFileLock.set(true)", acquire);
        int mutationBody = locking.indexOf("return body()", markHeld);

        assertTrue(reentrantBody >= 0);
        assertTrue(open > reentrantBody);
        assertTrue(acquire > open);
        assertTrue(markHeld > acquire);
        assertTrue(mutationBody > markHeld);
    }

    @Test
    public void lockFailureDefersBooleanMutationsWithoutRunningBody()
            throws IOException {
        String source = managerSource();
        String mutationLock = between(
                source,
                "private fun withConfigMutationLock(",
                "/**\n     * Load-time promotion and repair");
        String save = between(
                source,
                "fun saveConfig(config: JSONObject",
                "private fun saveConfigLocked(");
        String updateSection = between(
                source,
                "fun updateSection(section: String",
                "fun replaceSection(section: String");
        String updateValues = between(
                source,
                "fun updateValues(section: String",
                "// ==================== CONVENIENCE METHODS");

        assertOrdered(
                mutationLock,
                "withConfigFileLock(body)",
                "catch (e: ConfigLockUnavailableException)",
                "deferred:",
                "false");
        assertTrue(save.contains(
                "withConfigMutationLock(\"Whole-config save\")"));
        assertTrue(updateSection.contains(
                "withConfigMutationLock(\"Section '$section' update\")"));
        assertTrue(updateValues.contains(
                "withConfigMutationLock(\"Section '$section' values update\")"));
    }

    @Test
    public void optionalLoadTimeRepairsDoNotTurnLockAbsenceIntoCorruption()
            throws IOException {
        String source = managerSource();
        String optionalLock = between(
                source,
                "private fun <T> withConfigFileLockOrNull(",
                "/**\n     * Persist the geocoding-schema migration");

        assertTrue(optionalLock.contains(
                "catch (e: ConfigLockUnavailableException)"));
        assertTrue(optionalLock.contains("deferred:"));
        assertTrue(optionalLock.contains("null"));
        assertTrue(source.contains("\"Backup config promotion\""));
        assertTrue(source.contains("pendingRootPromotion"));
        assertTrue(source.contains("requestRootPromotion("));
    }

    @Test
    public void failedPostMutationSaveCannotLeakIntoCachedSnapshot()
            throws IOException {
        String source = managerSource();
        String isolated = between(
                source,
                "private fun loadIsolatedConfigForMutation(): JSONObject",
                "/**\n     * Check if config file exists");
        String updateSection = between(
                source,
                "fun updateSection(section: String",
                "fun replaceSection(section: String");
        String updateValues = between(
                source,
                "fun updateValues(section: String",
                "// ==================== CONVENIENCE METHODS");

        assertTrue(isolated.contains(
                "JSONObject(loadConfigFresh().toString())"));
        assertOrdered(
                updateSection,
                "val config = loadIsolatedConfigForMutation()",
                "existing.put(key, payload.get(key))",
                "config.put(section, existing)",
                "val success = saveConfig(config)");
        assertOrdered(
                updateValues,
                "val config = loadIsolatedConfigForMutation()",
                "sectionObj.put(key, payload.get(key))",
                "config.put(section, sectionObj)",
                "val success = saveConfig(config)");
        assertFalse(updateSection.contains("val config = loadConfigFresh()"));
        assertFalse(updateValues.contains("val config = loadConfigFresh()"));
    }

    @Test
    public void replacementAndKeyMutationClocksShareOneDominanceOrder()
            throws IOException {
        String source = managerSource();
        String applied = between(
                source,
                "private fun appliedMutationSequence(",
                "private fun recordMutationApplied(");

        assertTrue(applied.contains(
                "val replacementSequence =\n"
                        + "            sectionClock.optLong(\"*\", 0L)"));
        assertTrue(applied.contains(
                "if (key != \"*\")"));
        assertTrue(applied.contains(
                "sectionClock.optLong(key, 0L)"));
        assertTrue(applied.contains(
                "val keys = sectionClock.keys()"));
        assertTrue(applied.contains(
                "newest = maxOf("));
    }

    @Test
    public void replacementClockDominatesKeysAndNewerKeyDominatesReplacement()
            throws Exception {
        Class<?> stampType = Class.forName(
                "com.overdrive.app.config.UnifiedConfigManager$MutationStamp");
        Constructor<?> constructor =
                stampType.getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);
        Object stamp = constructor.newInstance("origin", 12L);
        Method applied = UnifiedConfigManager.class.getDeclaredMethod(
                "appliedMutationSequence",
                JSONObject.class, String.class, String.class, stampType);
        applied.setAccessible(true);

        JSONObject clocks = new JSONObject()
                .put("origin", new JSONObject()
                        .put("section", new JSONObject()
                                .put("*", 9L)
                                .put("field", 4L)));
        JSONObject root = new JSONObject()
                .put("__overdriveMutationClocks", clocks);
        assertEquals(9L, ((Number) applied.invoke(
                UnifiedConfigManager.INSTANCE,
                root, "section", "field", stamp)).longValue());

        clocks.getJSONObject("origin")
                .getJSONObject("section")
                .put("*", 3L)
                .put("field", 11L);
        assertEquals(11L, ((Number) applied.invoke(
                UnifiedConfigManager.INSTANCE,
                root, "section", "*", stamp)).longValue());
    }

    @Test
    public void loadReadAndFreshnessPublicationShareStableLock()
            throws IOException {
        String source = managerSource();
        String loadEntry = between(
                source,
                "fun loadConfig(): JSONObject",
                "/**\n     * Parse, migrate, and publish");
        String lockedLoad = between(
                source,
                "private fun loadConfigUnderFileLock(",
                "/**\n     * A read can precede daemon lock-file provisioning");
        String unlockedLoad = between(
                source,
                "private fun loadConfigWithoutStableLock(",
                "/**\n     * Best-effort recovery");
        String migration = between(
                source,
                "private fun persistMigrationUnderLock(): JSONObject?",
                "/**\n     * Force a fresh re-parse");

        assertOrdered(
                loadEntry,
                "withConfigFileLock {",
                "loadConfigUnderFileLock(configFile)");
        assertOrdered(
                lockedLoad,
                "val content = readLiveConfigText(configFile)",
                "var config = JSONObject(content)",
                "persistMigrationUnderLock()?.let",
                "config = committedMigration",
                "cachedConfig = config",
                "stampFreshness(configFile.lastModified())");
        assertTrue(unlockedLoad.contains("stampFreshness(0)"));
        assertFalse(unlockedLoad.contains("cachedConfig ="));
        assertOrdered(
                migration,
                "val committed = writeResult.committedConfig ?: fresh",
                "cachedConfig = committed",
                "stampFreshness(cf.lastModified())",
                "committed");
    }

    @Test
    public void allPostSendIpcFailuresReconcileBeforeFallback()
            throws IOException {
        String source = managerSource();
        String route = between(
                source,
                "private fun routeWriteIfApp(",
                "/**\n     * Resolve every post-send transport failure");
        String catchPath = between(
                route,
                "} catch (e: Exception) {",
                "} finally {");
        String reconcile = between(
                source,
                "private fun reconcileAmbiguousIpcWrite(",
                "/**\n     * Best-effort check");

        assertOrdered(
                route,
                "var requestMayHaveBeenSent = false",
                "requestMayHaveBeenSent = true",
                "writer.println(req.toString())",
                "reader.readLine()");
        assertTrue(route.contains(
                "?: return reconcileAmbiguousIpcWrite("));
        assertOrdered(
                catchPath,
                "if (requestMayHaveBeenSent)",
                "reconcileAmbiguousIpcWrite(");
        assertOrdered(
                reconcile,
                "repeat(IPC_RECONCILE_ATTEMPTS)",
                "withConfigFileLock {",
                "deltaPresentOnDisk(section, payload)",
                "val fresh = forceReload()",
                "writeAppPrivateBackup(fresh)",
                "true",
                "Thread.sleep(IPC_RECONCILE_DELAY_MS",
                "null");
    }

    @Test
    public void criticalNominalReadNeverUsesDefaultsOrRecovery()
            throws IOException {
        String strictRead = between(
                managerSource(),
                "fun readVehicleNominalKwhStrict(): Double",
                "fun getVehicle(): JSONObject");

        assertOrdered(
                strictRead,
                "withConfigFileLock {",
                "readLiveConfigText(file)",
                "config.optJSONObject(\"vehicle\")",
                "vehicle.getDouble(\"nominalKwh\")");
        assertFalse(strictRead.contains("loadConfig()"));
        assertFalse(strictRead.contains("createDefaultConfig()"));
        assertFalse(strictRead.contains("recoverFromBackup("));
        assertFalse(strictRead.contains("saveConfig"));
    }

    @Test
    public void strictDurableRootNeverUsesCacheDefaultsOrRecovery()
            throws IOException {
        String source = managerSource();
        String entry = between(
                source,
                "fun readDurableConfigStrict(): JSONObject",
                "/**\n     * Restore is allowed");
        String locked = between(
                source,
                "private fun readDurableConfigLockedStrict(): JSONObject",
                "@JvmStatic\n    fun loadConfig()");

        assertOrdered(
                entry,
                "withConfigFileLock {",
                "readDurableConfigLockedStrict()");
        assertOrdered(
                locked,
                "File(CONFIG_PATH)",
                "readLiveConfigText(file)",
                "JSONObject(encoded)");
        assertFalse(locked.contains("cachedConfig"));
        assertFalse(locked.contains("createDefaultConfig"));
        assertFalse(locked.contains("recoverFromBackup"));
        assertFalse(locked.contains("forceReload"));
    }

    @Test
    public void strictSelectedModelReadUsesExactDurableRoot()
            throws IOException {
        String strictModel = between(
                managerSource(),
                "fun getSelectedVehicleModelIdStrict(): String?",
                "/**\n     * Update vehicle appearance");

        assertOrdered(
                strictModel,
                "readDurableConfigStrict()",
                "root.opt(\"vehicle\")",
                "rawVehicle.opt(\"modelId\")",
                "rawVehicle.opt(\"modelSource\")",
                "VehicleModelSelection.resolvedModelId(");
        assertFalse(strictModel.contains("getVehicle()"));
        assertFalse(strictModel.contains("loadConfig()"));
    }

    @Test
    public void ipcMutationClockIsPersistedWithDeltaAndSuppressesStaleRetry()
            throws IOException {
        String source = managerSource();
        String updateSection = between(
                source,
                "fun updateSection(section: String",
                "private fun applyRootPromotion(");
        String deltaCheck = between(
                source,
                "private fun deltaPresentOnDisk(",
                "private fun rootPromotionPresentOnDisk(");

        assertOrdered(
                updateSection,
                "ensureMutationStamp(routedPayload)",
                "routeWriteIfApp(",
                "stripMutationStamp(routedPayload)",
                "loadIsolatedConfigForMutation()",
                "appliedMutationSequence(",
                "existing.put(key, payload.get(key))",
                "recordMutationApplied(",
                "saveConfig(config)");
        assertTrue(deltaCheck.contains(
                "appliedMutationSequence("));
        assertTrue(deltaCheck.contains(
                "onDisk, section, k, stamp"));
        assertTrue(deltaCheck.contains(">= stamp.sequence"));
        assertTrue(source.contains(
                "config.put(MUTATION_CLOCKS_KEY, clocks)"));
        assertTrue(source.contains(
                "payload.remove(MUTATION_ORIGIN_MARKER)"));
        assertTrue(source.contains(
                "payload.remove(MUTATION_SEQUENCE_MARKER)"));
    }

    @Test
    public void transientReadFailureCannotEnterDestructiveRecovery()
            throws IOException {
        String lockedLoad = between(
                managerSource(),
                "private fun loadConfigUnderFileLock(",
                "/**\n     * A read can precede daemon lock-file provisioning");
        String unavailable = between(
                lockedLoad,
                "catch (e: ConfigReadUnavailableException)",
                "catch (e: Exception)");
        String finalRepair = between(
                lockedLoad,
                "var peerReadUnavailable = false",
                "if (repaired != null)");

        assertTrue(unavailable.contains("stampFreshness(0)"));
        assertFalse(unavailable.contains("recoverFromBackup"));
        assertFalse(unavailable.contains("saveConfigInternal"));
        assertOrdered(
                finalRepair,
                "catch (_: ConfigReadUnavailableException)",
                "peerReadUnavailable = true",
                "if (peerReadUnavailable)",
                "null",
                "saveRecoveredConfigLocked(defaults)");
    }

    @Test
    public void equalMtimeCacheHasMonotonicRevalidationBound()
            throws IOException {
        String source = managerSource();
        String stamp = between(
                source,
                "private fun stampFreshness(",
                "/**\n     * True iff the cache");
        String fresh = between(
                source,
                "private fun isCacheFresh(",
                "// Raised when loadConfig()");

        assertTrue(stamp.contains("lastFreshnessCheckNanos.set("));
        assertTrue(stamp.contains("System.nanoTime()"));
        assertOrdered(
                fresh,
                "configFile.lastModified() != stamped",
                "lastFreshnessCheckNanos.get()",
                "System.nanoTime() - checkedAt",
                "CACHE_REVALIDATE_MS");
    }

    @Test
    public void backupPromotionRoutesAfterReleasingAppFileLock()
            throws IOException {
        String source = managerSource();
        String load = between(
                source,
                "fun loadConfig(): JSONObject",
                "/**\n     * Parse, migrate, and publish");
        String promotion = between(
                source,
                "private fun promoteNewerAppPrivateBackupIfNeeded(",
                "private fun saveConfigInternal(");
        String recovery = between(
                source,
                "private fun recoverFromBackup(",
                "fun saveConfig(config: JSONObject");

        assertOrdered(
                load,
                "withConfigFileLock {",
                "loadConfigUnderFileLock(configFile)",
                "promoteNewerAppPrivateBackupIfNeeded(loaded)");
        assertTrue(promotion.contains("requestRootPromotion("));
        assertTrue(promotion.contains("routeWriteIfApp("));
        assertFalse(promotion.contains("saveConfig(appBackup)"));
        assertOrdered(
                recovery,
                "android.os.Process.myUid() == SHELL_DAEMON_UID",
                "saveRecoveredConfigLocked(recovered)",
                "pendingRootPromotion =");
    }

    @Test
    public void corruptionRecoveryStagesModeAndCommitUnderOneRuntimeFence()
            throws IOException {
        String source = managerSource();
        String recoveryCommit = between(
                source,
                "private fun saveRecoveredConfigLocked(",
                "private fun writeFileAndSync(");

        assertOrdered(
                recoveryCommit,
                "synchronized(DiLink5Platform::class.java)",
                "DiLink5Platform.snapshotModeMarkers()",
                "stageCameraModeChange(config)",
                "saveConfigInternal(config)",
                "if (!result.committed",
                "DiLink5Platform.restoreModeMarkers(markerSnapshot)");
    }

    @Test
    public void wholeConfigWithoutVehicleModePreservesTheActiveRuntime()
            throws IOException {
        String stage = between(
                managerSource(),
                "private fun stageCameraModeChange(",
                "/**\n     * Recovery replaces an unreadable root");

        assertTrue(stage.contains(
                "DiLink5Platform.currentActiveMode()"));
        assertTrue(stage.contains(
                "if (!requestedCamera.has(\"cameraMode\"))"));
        assertTrue(stage.contains(
                "requestedCamera.put(\"cameraMode\", currentMode)"));
        assertTrue(stage.contains(
                "DiLink5Platform.normalizeConfiguredMode("));
        assertFalse(stage.contains(
                "optString(\"cameraMode\", \"default\")"));
        assertOrdered(
                stage,
                "val currentMode =",
                "val requestedCamera =",
                "requestedCamera.put(\"cameraMode\", currentMode)",
                "DiLink5Platform.stageConfiguredMode(requestedMode, currentMode)");
    }

    @Test
    public void appPrivateBackupPublicationIsSerializedAndMonotonic()
            throws IOException {
        String appPrivate = between(
                managerSource(),
                "private fun writeAppPrivateBackup(",
                "private fun readAppPrivateBackup(");

        assertOrdered(
                appPrivate,
                "synchronized(appPrivateBackupMonitor)",
                "RandomAccessFile(File(APP_PRIVATE_BAK_LOCK_PATH), \"rw\")",
                "backupLock = tryLockUntil(",
                "val published = try",
                "seqOf(published) >= seqOf(snapshot)",
                "val staging = File(",
                "writeFileAndSync(staging",
                "staging.renameTo(bak)",
                "syncDirectoryAfterRename(bak.parentFile)");
        assertTrue(appPrivate.contains(
                "android.os.Process.myPid()"));
        assertTrue(appPrivate.contains(
                "appPrivateBackupTempSequence.incrementAndGet()"));
        assertFalse(appPrivate.contains(
                "File(APP_PRIVATE_BAK_PATH + \".tmp\")"));
    }

    @Test
    public void chargingPricingMigrationIsLockedAndReconciledFromDisk()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/"
                        + "ChargingConfig.java");
        String load = between(
                source,
                "private JSONObject loadRootWithPricingMigration(",
                "/**\n     * Save current configuration");

        assertOrdered(
                load,
                "pricingMirrorNeedsReconciliation(loaded)",
                "UnifiedConfigManager.runUnderConfigLock",
                "UnifiedConfigManager.forceReload()",
                "reconcilePricingMirror(fresh)",
                "UnifiedConfigManager.saveConfig(fresh)",
                "UnifiedConfigManager.forceReload()");
        assertTrue(load.contains("reconcilePricingMirror(loaded)"));
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle, position > previous);
            previous = position;
        }
    }

    private static String between(
            String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start);
        assertTrue("Missing start: " + startNeedle, start >= 0);
        assertTrue("Missing end: " + endNeedle, end > start);
        return source.substring(start, end);
    }

    private static String managerSource() throws IOException {
        return readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/"
                        + "UnifiedConfigManager.kt");
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
