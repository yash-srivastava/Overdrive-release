package com.overdrive.app.launcher

import android.hardware.display.DisplayManager
import android.view.Display
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.daemon.CameraDaemon
import com.overdrive.app.launcher.dilink5.cluster.DiLink5HostedProjectionBridge
import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionConfig
import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionOwnership
import com.overdrive.app.monitor.AccMonitor
import com.overdrive.app.surveillance.ClusterProjectionController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/**
 * Optional density for a cast app. Density belongs to the entire display, so a lease
 * restores the previous override before another app or RoadSense inherits that display.
 * No preference means no wm command. Display 0 is rejected at every command boundary.
 */
object ClusterAppDensity {
    private const val PREF_KEY = "clusterAppDpi"
    private const val RECOVERY_KEY = "clusterDensityRecovery"
    private const val SUPPRESSION_KEY = "clusterDensitySuppression"
    private const val RECOVERY_WAIT_ATTEMPTS = 60
    private const val RECOVERY_WAIT_INTERVAL_MS = 500L
    private val leases = LinkedHashMap<String, Lease>()
    // Boot/ACC recovery must never restore a density just applied by a live cast.
    private val activeOwners = ConcurrentHashMap.newKeySet<String>()
    private val postLaunchLosses = ConcurrentHashMap<String, Int>()
    private val postLaunchLossEpisodes = LinkedHashMap<String, LinkedHashSet<String>>()
    private val suppressedPackages = ConcurrentHashMap.newKeySet<String>()
    private val densityVerdicts = LinkedHashMap<String, DirectDensityVerdict>()
    private val deferredRecoveryQueued = AtomicBoolean(false)
    private var suppressionLoaded = false
    private var loaded = false
    @Volatile private var lastReason = ""
    private var lastApplication: Application? = null
    private val recoveryWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ClusterDensityRecovery").apply { isDaemon = true }
    }

    private data class Lease(
        val owner: String, val packageName: String, val displayId: Int, val name: String,
        val uniqueId: String, val bootId: String,
        val previousOverride: Int?, val applied: Int,
        val transitionApplied: Int? = null,
        val predecessorWithoutPackage: Boolean = false,
    ) {
        fun json() = JSONObject().put("owner", owner).put("package", packageName)
            .put("displayId", displayId)
            .put("name", name).put("uniqueId", uniqueId).put("bootId", bootId)
            .put("previousOverride", previousOverride ?: 0).put("applied", applied)
            .put("transitionApplied", transitionApplied ?: 0)
            .put("predecessorWithoutPackage", predecessorWithoutPackage)

        fun ownsEffectiveDensity(value: Int): Boolean =
            densityJournalOwns(applied, transitionApplied, value)
    }

    private data class Identity(val name: String, val uniqueId: String)

    private data class Application(
        val pkg: String, val displayId: Int, val requestedDpi: Int,
        val state: String, val reason: String = ""
    ) {
        fun json() = JSONObject().put("package", pkg).put("displayId", displayId)
            .put("requestedDpi", requestedDpi).put("state", state).put("reason", reason)
    }

    @JvmStatic
    fun isValidPreferencePackage(pkg: String?): Boolean =
        AppLauncher.isValidPackageName(pkg)

    @JvmStatic
    fun preferredDpi(pkg: String): Int {
        if (!AppLauncher.isValidPackageName(pkg)) return 0
        val value = UnifiedConfigManager.loadConfig().optJSONObject("projection")
            ?.optJSONObject(PREF_KEY)?.optInt(pkg, 0) ?: 0
        return if (value in 96..480) value else 0
    }

    @JvmStatic
    fun savePreference(pkg: String, dpi: Int): Boolean {
        if (!AppLauncher.isValidPackageName(pkg) || (dpi != 0 && dpi !in 96..480)
            || pkg == "com.overdrive.app") return false
        return UnifiedConfigManager.runUnderConfigLock {
            val values = UnifiedConfigManager.forceReload().optJSONObject("projection")
                ?.optJSONObject(PREF_KEY)?.let { JSONObject(it.toString()) } ?: JSONObject()
            if (dpi == 0) values.remove(pkg) else values.put(pkg, dpi)
            UnifiedConfigManager.updateValues("projection", mapOf(PREF_KEY to values))
        }
    }

    /** Called on the cast worker, with its generation's liveness check. */
    @JvmStatic
    @Synchronized
    fun applyForLaunch(pkg: String, displayId: Int, owner: String, live: BooleanSupplier): Boolean =
        applyForLaunchWithPolicy(pkg, displayId, owner, live, null)

    @Synchronized
    private fun applyForLaunchWithPolicy(
        pkg: String,
        displayId: Int,
        owner: String,
        live: BooleanSupplier,
        hostedPolicy: Boolean?,
    ): Boolean {
        if (displayId <= 0 || pkg == "com.overdrive.app" || !live.asBoolean) return false
        var desired = preferredDpi(pkg)
        val hostedGeneration = owner.removePrefix("di5:").toLongOrNull()
        val hosted = hostedPolicy ?: (hostedGeneration != null &&
            DiLink5HostedProjectionBridge.contentDisplayId(hostedGeneration) > 0)
        val isDirectOwner = directDensityLearningEligible(owner, hosted)
        if (isDirectOwner) loadSuppression()
        val directScale = if (isDirectOwner) {
            if (pkg in suppressedPackages) 100 else try {
                DiLink5ProjectionConfig.from(
                    UnifiedConfigManager.loadConfig().optJSONObject("projection")
                ).directScalePercent
            } catch (_: Throwable) { 100 }
        } else 100
        lastApplication = Application(pkg, displayId, desired, "preparing")
        if (!densityWritesAllowed()) {
            return if (desired > 0 || directScale != 100) {
                applicationFailed("Density is waiting for confirmed ACC-on")
            } else false
        }
        loadLeases()
        // Handles replacement on the same live display, and a pending ACC-off restore
        // whose display disappeared before wm could inspect it.
        for (lease in leases.values.toList()) {
            if (!live.asBoolean) return false
            if (lease.displayId == displayId
                && (lease.owner != owner || lease.owner !in activeOwners)
                && !restore(lease, live)) {
                return applicationFailed("Previous cluster density could not be restored")
            }
        }
        if (!live.asBoolean) return false
        CameraDaemon.log("Cluster density: requested configuration for $pkg on display $displayId")
        val display = identity(displayId) ?: return applicationFailed("Cluster display is unavailable")
        val reading = readDensity(displayId)
            ?: return applicationFailed("Firmware did not report density for the cluster display")
        desired = densityForProjectionPolicy(
            preferredDpi = desired,
            nativeDensity = reading.physical,
            directScalePercent = directScale,
            directOwner = isDirectOwner,
        )
        if (isDirectOwner) {
            lastApplication = lastApplication?.copy(requestedDpi = desired)
        }
        if (desired == 0) {
            lastApplication = lastApplication?.copy(state = "unchanged")
            return false
        }
        leases[owner]?.let {
            if (it.displayId != displayId || it.applied != desired || !matches(it)) {
                return applicationFailed("An earlier density change is still awaiting restoration")
            }
            lastApplication = lastApplication?.copy(state = "existing_session")
            return false
        }
        if (reading.effective == desired &&
            !(isDirectOwner && directScale == 100 && reading.override != null)) {
            lastApplication = lastApplication?.copy(state = "already_applied")
            return false // existing value is not ours to restore
        }
        val lease = Lease(owner, pkg, displayId, display.name, display.uniqueId, bootId(),
            reading.override, desired)
        if (!live.asBoolean) return false
        leases[owner] = lease
        if (!persist()) {
            leases.remove(owner)
            return applicationFailed("Could not save the cluster density recovery record")
        }
        if (!live.asBoolean || !densityWritesAllowed()) {
            leases.remove(owner)
            persist()
            return false
        }
        if (!matches(lease)) {
            leases.remove(owner)
            persist()
            return applicationFailed("Cluster display changed before the density write")
        }
        if (!live.asBoolean || !densityWritesAllowed()) {
            leases.remove(owner)
            persist()
            return false
        }
        activeOwners.add(owner)
        val resetToNative = isDirectOwner && directScale == 100
        val result = runDensity(displayId, if (resetToNative) 0 else desired)
        // Once ACC-off cancels the generation, leave its durable lease for recovery.
        // Sending another wm command here would race the compositor teardown.
        if (!live.asBoolean || !densityWritesAllowed()) {
            activeOwners.remove(owner)
            return applicationFailed("Cast ended during density change; restoration is pending")
        }
        val verifiedReading = readDensity(displayId)
        val verified = result?.success == true && matches(lease) &&
            verifiedReading?.effective == desired &&
            (!resetToNative || verifiedReading.override == null)
        if (!live.asBoolean || !densityWritesAllowed()) {
            activeOwners.remove(owner)
            return applicationFailed("Cast ended during density change; restoration is pending")
        }
        if (!verified) {
            activeOwners.remove(owner)
            restore(lease, live)
            return applicationFailed("Firmware refused or did not confirm the cluster density change")
        }
        lastReason = ""
        lastApplication = lastApplication?.copy(state = "applied", reason = "")
        CameraDaemon.log("Cluster density: confirmed $desired DPI for $pkg on display $displayId")
        // wm density may relaunch activities; finish it before am start, on the worker.
        try { Thread.sleep(150) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            // Interruption is also a cancellation signal; defer restoration safely.
            activeOwners.remove(owner)
            return false
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun restoreForSession(owner: String, live: BooleanSupplier = BooleanSupplier { true }): Boolean {
        loadLeases()
        activeOwners.remove(owner)
        return leases[owner]?.let { restore(it, live) } ?: true
    }

    @JvmStatic
    @Synchronized
    fun hasLease(owner: String): Boolean {
        loadLeases()
        return leases.containsKey(owner)
    }

    @JvmStatic
    @Synchronized
    fun hasLeaseFamily(owner: String): Boolean {
        loadLeases()
        return leases.keys.any { it == owner || it.startsWith("$owner:resize:") }
    }

    /**
     * A shell-owned content VD can disappear when its daemon dies. Its wm override disappears with
     * that exact VD; a physical display losing visibility provides no equivalent proof.
     */
    @JvmStatic
    @Synchronized
    fun reconcileRetiredHostedDisplay(ownership: DiLink5ProjectionOwnership): Boolean {
        loadLeases()
        val candidates = leases.values.filter {
            it.displayId == ownership.contentDisplayId &&
                it.name == ownership.virtualDisplayName
        }
        if (candidates.isEmpty()) return true
        if (DiLink5HostedProjectionBridge.confirmedOwnedContentDisplayRetired(ownership) != true) {
            return false
        }
        if (candidates.any { lease ->
                !retiredHostedLeaseMayClear(
                    owner = lease.owner,
                    leasePackage = lease.packageName,
                    leaseDisplayId = lease.displayId,
                    leaseName = lease.name,
                    leaseBootId = lease.bootId,
                    ownership = ownership,
                    retirementConfirmed = true,
                    tasksReclaimed = taskReclamationConfirmed(lease),
                    active = lease.owner in activeOwners,
                )
            }
        ) return false
        return removeLeasesDurably(candidates)
    }

    /** Durable hosted records remain authoritative even if a crash preceded densityOwned=true. */
    @JvmStatic
    @Synchronized
    fun restoreForOwnedHostedDisplay(ownership: DiLink5ProjectionOwnership): Boolean {
        if (ownership.transport != "hosted" || !ownership.virtualDisplayOwned ||
            ownership.contentDisplayId <= 0 ||
            ownership.contentDisplayId == ownership.physicalDisplayId ||
            ownership.virtualDisplayName != "OverDriveCluster-${ownership.generation}"
        ) return !ownership.densityOwned
        loadLeases()
        val matching = leases.values.filter {
            it.displayId == ownership.contentDisplayId &&
                it.name == ownership.virtualDisplayName &&
                it.bootId == ownership.bootId
        }
        matching.forEach { activeOwners.remove(it.owner) }
        if (matching.isNotEmpty() &&
            DiLink5HostedProjectionBridge.confirmedOwnedContentDisplayRetired(ownership) == true
        ) return reconcileRetiredHostedDisplay(ownership)
        var restored = true
        matching.forEach {
            if (!restore(it)) restored = false
        }
        return restored
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun restoreForSessionExceptDisplay(
        owner: String,
        activeDisplayId: Int,
        live: BooleanSupplier = BooleanSupplier { true },
    ): Boolean {
        loadLeases()
        val lease = leases[owner] ?: return true
        if (lease.displayId == activeDisplayId) return true
        activeOwners.remove(owner)
        return restore(lease, live)
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun restoreForSessionFamily(
        owner: String,
        live: BooleanSupplier = BooleanSupplier { true },
    ): Boolean {
        loadLeases()
        val family = leases.keys.filter { it == owner || it.startsWith("$owner:resize:") }
        var complete = true
        family.forEach { member ->
            activeOwners.remove(member)
            leases[member]?.let { if (!restore(it, live)) complete = false }
        }
        return complete
    }

    /** Prepare any owned package density on the replacement display before task movement. */
    @JvmStatic
    @Synchronized
    fun prepareHostedReplacement(
        pkg: String,
        displayId: Int,
        canonicalOwner: String,
        transitionOwner: String,
        live: BooleanSupplier,
    ): HostedDensityPreparation {
        loadLeases()
        val current = leases[canonicalOwner]
        val canonicalStateValid = if (current == null) {
            canonicalOwner !in activeOwners
        } else {
            current.packageName == pkg && canonicalOwner in activeOwners
        }
        return runHostedReplacementPreparation(
            canonicalStateValid = canonicalStateValid,
            ownersDiffer = transitionOwner != canonicalOwner,
            apply = { applyForLaunchWithPolicy(pkg, displayId, transitionOwner, live, true) },
            inspect = {
                hostedDensityPreparationResult(
                    hasLease = leases.containsKey(transitionOwner),
                    active = transitionOwner in activeOwners,
                    applicationState = lastApplication?.takeIf {
                        it.pkg == pkg && it.displayId == displayId
                    }?.state,
                )
            },
        )
    }

    /** Promote a prepared replacement lease after the old display has been restored. */
    @JvmStatic
    @Synchronized
    fun promoteHostedReplacement(canonicalOwner: String, transitionOwner: String): Boolean {
        loadLeases()
        return promoteHostedLeaseTransition(
            leases,
            activeOwners,
            canonicalOwner,
            transitionOwner,
            rename = { lease, owner -> lease.copy(owner = owner) },
            persist = ::persist,
        )
    }

    @JvmStatic
    @Synchronized
    fun recordPostLaunchLoss(
        pkg: String,
        owner: String,
        lossEpisode: String,
        reason: DirectDensityLossReason,
    ): Boolean {
        if (!AppLauncher.isValidPackageName(pkg) || !directDensityLearningEligible(
                owner,
                hosted = owner.removePrefix("di5:").toLongOrNull()?.let {
                    DiLink5HostedProjectionBridge.contentDisplayId(it) > 0
                } == true,
            )
        ) return false
        loadSuppression()
        loadLeases()
        val lease = leases[owner] ?: return false
        if (owner !in activeOwners || !matches(lease)) return false
        val current = readDensity(lease.displayId) ?: return false
        if (lease.transitionApplied != null || !isConfirmedNonNativeDensity(
                lease.applied,
                current.physical,
                current.effective,
            )
        ) return false
        val episodes = postLaunchLossEpisodes.getOrPut(pkg) { LinkedHashSet() }
        if (!recordDirectDensityLossForAttempt(episodes, owner, lossEpisode)) {
            return pkg in suppressedPackages
        }
        val count = episodes.size.coerceAtMost(2)
        postLaunchLosses[pkg] = count
        if (count >= 2) {
            densityVerdicts[pkg] = DirectDensityVerdict(reason, System.currentTimeMillis())
            suppressedPackages.add(pkg)
        }
        persistSuppression()
        return pkg in suppressedPackages
    }

    @JvmStatic
    @Synchronized
    fun isDirectScaleSuppressed(pkg: String): Boolean {
        loadSuppression()
        return pkg in suppressedPackages
    }

    /**
     * After a second confirmed task loss under a direct scale, relaunch at native density while
     * retaining the original lease so a later stop can still restore the user's prior override.
     */
    @JvmStatic
    @Synchronized
    fun resetDirectScaleToNativeForRetry(
        owner: String,
        live: BooleanSupplier = BooleanSupplier { true },
    ): Boolean {
        loadLeases()
        val lease = leases[owner] ?: return true
        if (!live.asBoolean || !densityWritesAllowed() || !matches(lease)) return false
        val current = readDensity(lease.displayId) ?: return false
        if (!lease.ownsEffectiveDensity(current.effective)) return false
        val nativeDensity = current.physical
        if (nativeDensity <= 0) return false

        // Journal both values before changing the display. Recovery can safely restore the
        // prior override whether a crash lands before or after the native reset command.
        val transition = lease.copy(transitionApplied = nativeDensity)
        leases[owner] = transition
        if (!persist()) {
            leases[owner] = lease
            return false
        }
        if (!live.asBoolean || !densityWritesAllowed() || !matches(transition)) {
            leases[owner] = lease
            persist()
            return false
        }

        val reset = runDensity(lease.displayId, 0)
        val native = readDensity(lease.displayId)
        if (reset?.success != true || native == null || native.override != null ||
            native.physical != nativeDensity || !live.asBoolean || !matches(transition)
        ) {
            // Keep the transition journal when rollback cannot be durably confirmed; it owns
            // either the scaled or native value and remains recoverable after process death.
            if (native?.effective == lease.applied && live.asBoolean) {
                leases[owner] = lease
                persist()
            }
            return false
        }

        val updated = transition.copy(applied = nativeDensity, transitionApplied = null)
        leases[owner] = updated
        // A compaction failure is safe: the already-persisted transition accepts nativeDensity.
        persist()
        activeOwners.add(owner)
        lastApplication = lastApplication?.copy(
            requestedDpi = nativeDensity,
            state = "native_retry",
            reason = "",
        )
        return true
    }

    /** Cache-only ACC-off signal; never waits for a wm command or performs config I/O. */
    @JvmStatic
    fun deferRestoration(owner: String) {
        activeOwners.remove(owner)
    }

    /** Retry pending recovery before launching the map on an existing display. */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun restoreForDisplay(displayId: Int, live: BooleanSupplier = BooleanSupplier { true }): Boolean {
        if (displayId <= 0) return false
        loadLeases()
        var success = true
        for (lease in leases.values.toList()) {
            // A recycled ID belonging to a different display cannot inherit our lease.
            if (lease.displayId == displayId && matches(lease) && !restore(lease, live)) success = false
        }
        return success
    }

    @JvmStatic
    @Synchronized
    fun recoverAtBoot() {
        loadLeases()
        // Task reclamation owns display affinity during boot. Density writes wait until both
        // legacy and DiLink 5 recovery have released the shared projection fence.
        if (!densityRecoveryMayWrite(
                densityWritesAllowed(),
                taskRecoveryBlocksDensity(),
            )
        ) {
            if (densityWritesAllowed()) scheduleDeferredRecovery()
            return
        }
        restorePendingLeases()
    }

    @JvmStatic
    fun recoverWhenAccOn() {
        scheduleDeferredRecovery()
    }

    private fun scheduleDeferredRecovery() {
        if (!deferredRecoveryQueued.compareAndSet(false, true)) return
        recoveryWorker.execute {
            try {
                repeat(RECOVERY_WAIT_ATTEMPTS) {
                    if (!densityWritesAllowed()) return@execute
                    if (!taskRecoveryBlocksDensity()) {
                        synchronized(this) {
                            loadLeases()
                            if (densityRecoveryMayWrite(
                                    densityWritesAllowed(),
                                    taskRecoveryBlocksDensity(),
                                )
                            ) restorePendingLeases()
                        }
                        return@execute
                    }
                    try {
                        Thread.sleep(RECOVERY_WAIT_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
            } finally {
                deferredRecoveryQueued.set(false)
            }
        }
    }

    private fun restorePendingLeases() {
        for (lease in leases.values.toList()) {
            if (lease.owner !in activeOwners) restore(lease)
        }
    }

    @JvmStatic
    @Synchronized
    fun nativeDensityForDisplay(displayId: Int): Int? =
        readDensity(displayId)?.physical?.takeIf { it > 0 }

    @JvmStatic
    @Synchronized
    fun status(): JSONObject {
        loadLeases()
        return JSONObject().put("pendingRestores", JSONArray().also { a ->
            leases.values.forEach { a.put(it.json()) }
        }).put("reason", lastReason)
            .put("lastApplication", lastApplication?.json() ?: JSONObject.NULL)
            .put("freeform", ClusterFreeformWindow.freeformSupportStatus())
    }

    private fun taskReclamationConfirmed(lease: Lease): Boolean {
        if (lease.predecessorWithoutPackage) {
            return predecessorDensityReclamationConfirmed(
                predecessor = true,
                packageName = lease.packageName,
                displayReclaimed = ClusterFreeformWindow.displayTasksReclaimed(lease.displayId),
            )
        }
        if (!AppLauncher.isValidPackageName(lease.packageName)) return false
        return ClusterFreeformWindow.packageTasksReclaimedFromDisplay(
            lease.packageName,
            lease.displayId,
        ) == true
    }

    private fun restore(lease: Lease, live: BooleanSupplier = BooleanSupplier { true }): Boolean {
        if (!live.asBoolean || !densityWritesAllowed()) return false
        if (!matches(lease)) {
            val owned = DiLink5ProjectionOwnership.loadAll().firstOrNull {
                it.transport == "hosted" && it.contentDisplayId == lease.displayId &&
                    it.virtualDisplayName == lease.name
            }
            if (owned != null && reconcileRetiredHostedDisplay(owned)) return true
            return fail("Cluster density restore is waiting for its original display")
        }
        val current = readDensity(lease.displayId)
            ?: return fail("Could not read cluster density during restore")
        // A separate owner changed density after us. Preserve their value. A transition
        // journal owns both the pre-reset and post-reset effective values.
        if (!lease.ownsEffectiveDensity(current.effective)) {
            return removeLeasesDurably(listOf(lease))
        }
        if (!taskReclamationConfirmed(lease)) {
            return fail("Cluster density restore is waiting for confirmed task reclamation")
        }
        if (!live.asBoolean || !densityWritesAllowed()) return false
        if (!matches(lease)) return fail("Cluster display changed before density restoration")
        if (!live.asBoolean || !densityWritesAllowed()) return false
        val result = runDensity(lease.displayId, lease.previousOverride ?: 0)
        val restored = result?.success == true && matches(lease) &&
            readDensity(lease.displayId)?.override == lease.previousOverride
        if (!restored) return fail("Firmware did not confirm cluster density restoration")
        if (!removeLeasesDurably(listOf(lease))) {
            return fail("Could not clear the cluster density recovery record")
        }
        lastReason = ""
        return true
    }

    private fun removeLeasesDurably(removing: List<Lease>): Boolean =
        removeDensityLeasesDurably(
            leases,
            activeOwners,
            removing.associateBy { it.owner },
            ::persist,
        )

    private fun matches(lease: Lease): Boolean {
        val d = identity(lease.displayId) ?: return false
        if (d.name != lease.name) return false
        val currentUnique = d.uniqueId
        if (lease.uniqueId.isNotEmpty()) return currentUnique == lease.uniqueId
        // Without stable identity, a recycled ID after reboot is not safe to modify.
        return lease.bootId.isNotEmpty() && lease.bootId == bootId()
    }

    private fun identity(id: Int): Identity? {
        if (id <= 0) return null
        val visible = runCatching {
            (CameraDaemon.getAppContext()?.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as? DisplayManager)?.getDisplay(id)?.takeIf { it.isValid && it.displayId == id }
        }.getOrNull()
        if (visible != null) return Identity(visible.name, uniqueId(visible))
        val snapshot = ClusterDisplayReader.read(id) ?: return null
        return Identity(snapshot.name, snapshot.uniqueId)
    }

    private fun readDensity(displayId: Int): DensityReading? {
        if (displayId <= 0) return null
        val direct = runDensity(displayId, null)
        if (direct?.success != true) return null
        DensityReading.parse(direct.output)?.let { return it }
        if (!DensityReading.canUseWindowFallback(direct.output)) return null
        // Android 10's wm read checks private-display access and may print -1 even
        // though its privileged writer accepts the display. WMS's dump is scoped
        // independently and includes both the initial and current base density.
        return DensityReading.parseWindowDump(ClusterDisplayReader.readWindowDisplays(), displayId)
    }

    private fun uniqueId(d: Display): String = try {
        Display::class.java.getDeclaredMethod("getUniqueId").invoke(d) as? String ?: ""
    } catch (_: Exception) { "" }

    private fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (_: Exception) { "" }

    private fun taskRecoveryBlocksDensity(): Boolean =
        ClusterProjectionController.isLegacyRestoreInFlight() ||
            DiLink5ClusterCast.isTaskReclamationInFlight()

    private fun densityWritesAllowed(): Boolean =
        AccMonitor.isAccStateAuthoritative() && AccMonitor.isAccOn()

    private fun loadSuppression() {
        if (suppressionLoaded) return
        try {
            val saved = UnifiedConfigManager.forceReload().optJSONObject("projection")
                ?.optJSONObject(SUPPRESSION_KEY)
            densityVerdicts.putAll(readDirectDensityVerdicts(saved))
            suppressedPackages.addAll(densityVerdicts.keys)
            if (saved != null && !currentDirectDensityJournal(saved)) {
                // Earlier OverDrive rows contained episode IDs, not the observed task mode or
                // loss shape. None proves a generation-2 verdict; do not invent a "died" reason.
                // Recovery leases use their separate, still boot-bound journal.
                persistSuppression()
            }
        } catch (_: Throwable) {
            // Persistence may be unavailable in local JVM tests; in-memory suppression remains safe.
        }
        suppressionLoaded = true
    }

    private fun persistSuppression(): Boolean = try {
        UnifiedConfigManager.updateValues(
            "projection",
            mapOf(SUPPRESSION_KEY to serializeDirectDensityVerdicts(densityVerdicts)),
        )
    } catch (_: Throwable) {
        false
    }

    private fun loadLeases() {
        if (loaded) return
        val array = UnifiedConfigManager.forceReload().optJSONObject("projection")
            ?.optJSONArray(RECOVERY_KEY)
        if (array != null) for (i in 0 until array.length()) {
            val j = array.optJSONObject(i) ?: continue
            val id = j.optInt("displayId", -1)
            val owner = j.optString("owner")
            val previous = j.optInt("previousOverride", -1)
            val applied = j.optInt("applied", -1)
            val transition = j.optInt("transitionApplied", 0)
            if (id <= 0 || owner.isEmpty() || previous !in 0..10_000 ||
                !isValidPersistedDensity(applied) ||
                (transition != 0 && !isValidPersistedDensity(transition))
            ) continue
            val packageName = j.optString("package")
            leases[owner] = Lease(owner, packageName, id, j.optString("name"),
                j.optString("uniqueId"), j.optString("bootId"),
                previous.takeIf { it > 0 }, applied, transition.takeIf { it > 0 },
                isPredecessorDensityLease(j))
        }
        loaded = true
    }

    private fun persist(): Boolean = UnifiedConfigManager.updateValues(
        "projection", mapOf(RECOVERY_KEY to JSONArray().also { a ->
            leases.values.forEach { a.put(it.json()) }
        })
    )

    private fun fail(reason: String): Boolean {
        lastReason = reason
        CameraDaemon.log("Cluster density: $reason")
        return false
    }

    private fun applicationFailed(reason: String): Boolean {
        lastApplication = lastApplication?.copy(state = "failed", reason = reason)
        return fail(reason)
    }

    private data class CommandResult(val success: Boolean, val output: String)

    private fun runDensity(displayId: Int, value: Int?): CommandResult? {
        val args = DensityReading.command(displayId, value) ?: return null
        var process: Process? = null
        return try {
            val running = ProcessBuilder(args).redirectErrorStream(true).start()
            process = running
            val text = StringBuilder()
            val drain = Thread({
                try {
                    running.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (text.length < 16_384) text.append(line).append('\n')
                        }
                    }
                } catch (_: Exception) { }
            }, "ClusterDensityOutput").apply { isDaemon = true; start() }
            if (!running.waitFor(3, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                drain.join(300)
                null
            } else {
                drain.join(500)
                if (drain.isAlive) {
                    running.inputStream.close()
                    null
                } else CommandResult(running.exitValue() == 0, text.toString())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) { null } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
                try { it.inputStream.close() } catch (_: Exception) { }
            }
        }
    }
}

/** Shell output parsing kept separate from display and lifecycle side effects. */
internal fun isConfirmedNonNativeDensity(
    applied: Int,
    physical: Int,
    effective: Int,
): Boolean = applied > 0 && physical > 0 && applied != physical && effective == applied

internal fun densityJournalOwns(
    applied: Int,
    transitionApplied: Int?,
    observed: Int,
): Boolean = observed == applied || observed == transitionApplied

internal fun recordDistinctDensityLoss(
    episodes: LinkedHashSet<String>,
    lossEpisode: String,
): Boolean {
    if (lossEpisode.isBlank() || !episodes.add(lossEpisode)) return false
    while (episodes.size > 2) episodes.remove(episodes.first())
    return true
}

internal fun directDensityLearningEligible(owner: String, hosted: Boolean): Boolean =
    !hosted && Regex("^di5:[1-9]\\d*$").matches(owner)

/** Strikes belong to one watch attempt; only its completed unsafe verdict survives a restart. */
internal fun recordDirectDensityLossForAttempt(
    episodes: LinkedHashSet<String>,
    owner: String,
    lossEpisode: String,
): Boolean {
    if (!directDensityLearningEligible(owner, false)) return false
    val generation = owner.removePrefix("di5:")
    if (!Regex("^$generation:\\d+:-?\\d+$").matches(lossEpisode)) return false
    if (episodes.any { !it.startsWith("$generation:") }) episodes.clear()
    return recordDistinctDensityLoss(episodes, lossEpisode)
}

enum class DirectDensityLossReason {
    MISSING_TASK,
    RECREATED_TASK,
    TASK_MODE_RESET,
}

internal data class DirectDensityVerdict(
    val reason: DirectDensityLossReason,
    val learnedAtMillis: Long,
)

// This is a behavioral probe generation, independent of app updates and OS boot identity.
internal const val DIRECT_DENSITY_PROBE_GENERATION = 2
private const val DIRECT_DENSITY_JOURNAL_SCHEMA = 1
private const val DIRECT_DENSITY_EVIDENCE_MODEL = "verified-task-id-and-windowing-mode"

internal fun currentDirectDensityJournal(saved: JSONObject): Boolean =
    saved.optInt("schemaVersion", -1) == DIRECT_DENSITY_JOURNAL_SCHEMA &&
        saved.optInt("probeGeneration", -1) == DIRECT_DENSITY_PROBE_GENERATION &&
        saved.optString("evidenceModel") == DIRECT_DENSITY_EVIDENCE_MODEL

internal fun serializeDirectDensityVerdicts(verdicts: Map<String, DirectDensityVerdict>): JSONObject {
    val values = JSONObject()
    verdicts.forEach { (pkg, verdict) ->
        if (AppLauncher.isValidPackageName(pkg)) {
            values.put(pkg, JSONObject()
                .put("reason", verdict.reason.name)
                .put("learnedAtMillis", verdict.learnedAtMillis))
        }
    }
    return JSONObject().put("schemaVersion", DIRECT_DENSITY_JOURNAL_SCHEMA)
        .put("probeGeneration", DIRECT_DENSITY_PROBE_GENERATION)
        .put("evidenceModel", DIRECT_DENSITY_EVIDENCE_MODEL)
        .put("unsafePackages", values)
}

internal fun readDirectDensityVerdicts(saved: JSONObject?): Map<String, DirectDensityVerdict> {
    if (saved == null || !currentDirectDensityJournal(saved)) return emptyMap()
    val values = saved.optJSONObject("unsafePackages") ?: return emptyMap()
    val verdicts = LinkedHashMap<String, DirectDensityVerdict>()
    val packages = values.keys()
    while (packages.hasNext()) {
        val pkg = packages.next()
        if (!AppLauncher.isValidPackageName(pkg)) continue
        val value = values.optJSONObject(pkg) ?: continue
        val reason = DirectDensityLossReason.entries.firstOrNull {
            it.name == value.optString("reason")
        } ?: continue
        val learnedAt = value.optLong("learnedAtMillis", -1)
        if (learnedAt >= 0) verdicts[pkg] = DirectDensityVerdict(reason, learnedAt)
    }
    return verdicts
}

internal fun densityRecoveryMayWrite(
    writesAllowed: Boolean,
    taskRecoveryInFlight: Boolean,
): Boolean = writesAllowed && !taskRecoveryInFlight

/** The previous product schema had no package field. No other malformed lease gets this path. */
internal fun isPredecessorDensityLease(source: JSONObject): Boolean {
    val owner = source.optString("owner")
    if (!owner.startsWith("legacy:") ||
        owner.removePrefix("legacy:").toLongOrNull()?.let { it > 0 } != true ||
        source.optString("package").isNotEmpty()
    ) return false
    if (source.optBoolean("predecessorWithoutPackage", false)) return true
    if (source.has("package") || source.has("transitionApplied")) return false
    val oldFields = setOf(
        "owner", "displayId", "name", "uniqueId", "bootId", "previousOverride", "applied",
    )
    val fields = source.keys().asSequence().toSet()
    return fields == oldFields
}

internal fun predecessorDensityReclamationConfirmed(
    predecessor: Boolean,
    packageName: String,
    displayReclaimed: Boolean?,
): Boolean = predecessor && packageName.isEmpty() && displayReclaimed == true

internal fun retiredHostedLeaseMayClear(
    owner: String,
    leasePackage: String,
    leaseDisplayId: Int,
    leaseName: String,
    leaseBootId: String,
    ownership: DiLink5ProjectionOwnership,
    retirementConfirmed: Boolean,
    tasksReclaimed: Boolean,
    active: Boolean,
): Boolean = !active && retirementConfirmed && tasksReclaimed &&
    ownership.transport == "hosted" && ownership.virtualDisplayOwned &&
    ownership.contentDisplayId > 0 &&
    ownership.contentDisplayId != ownership.physicalDisplayId &&
    ownership.virtualDisplayName == "OverDriveCluster-${ownership.generation}" &&
    hostedDensityOwnerMatches(owner, ownership) &&
    leasePackage.isNotEmpty() && leasePackage == ownership.packageName &&
    leaseDisplayId == ownership.contentDisplayId && leaseName == ownership.virtualDisplayName &&
    leaseBootId.isNotEmpty() && leaseBootId == ownership.bootId

internal fun hostedDensityOwnerMatches(
    owner: String,
    ownership: DiLink5ProjectionOwnership,
): Boolean {
    if (!Regex("^di5:[1-9]\\d*(?::resize:[1-9]\\d*)?$").matches(owner)) return false
    if (ownership.densityOwner.isNotEmpty()) {
        val recorded = ownership.densityOwner
        if (!Regex("^di5:[1-9]\\d*(?::resize:[1-9]\\d*)?$").matches(recorded)) return false
        if (owner == recorded) return true
        // Promotion persists the canonical lease before the VD record is updated. A crash between
        // those writes still belongs to this exact transition's canonical owner, not any Di5 lease.
        val suffix = ":resize:${ownership.generation}"
        return recorded.endsWith(suffix) && owner == recorded.removeSuffix(suffix)
    }
    return owner == "di5:${ownership.generation}" ||
        (owner.startsWith("di5:") && owner.endsWith(":resize:${ownership.generation}"))
}

/** Failed persistence restores both the lease and its active-owner membership for the next retry. */
internal fun <T> removeDensityLeasesDurably(
    leases: MutableMap<String, T>,
    activeOwners: MutableSet<String>,
    removing: Map<String, T>,
    persist: () -> Boolean,
): Boolean {
    val previouslyActive = removing.keys.filter { it in activeOwners }
    removing.keys.forEach { leases.remove(it); activeOwners.remove(it) }
    if (runCatching(persist).getOrDefault(false)) return true
    leases.putAll(removing)
    activeOwners.addAll(previouslyActive)
    return false
}

internal fun isValidPersistedDensity(value: Int): Boolean = value in 1..10_000

internal fun densityForProjectionPolicy(
    preferredDpi: Int,
    nativeDensity: Int,
    directScalePercent: Int,
    directOwner: Boolean,
): Int {
    if (!directOwner) return preferredDpi
    return if (directScalePercent == 100) nativeDensity
        else directDensityForScale(nativeDensity, directScalePercent)
}

enum class HostedDensityPreparation {
    READY_WITH_LEASE,
    READY_UNCHANGED,
    FAILED,
}

internal fun runHostedReplacementPreparation(
    canonicalStateValid: Boolean,
    ownersDiffer: Boolean,
    apply: () -> Unit,
    inspect: () -> HostedDensityPreparation,
): HostedDensityPreparation {
    if (!canonicalStateValid || !ownersDiffer) return HostedDensityPreparation.FAILED
    return runHostedDensityPreparation(apply, inspect)
}

internal fun runHostedDensityPreparation(
    apply: () -> Unit,
    inspect: () -> HostedDensityPreparation,
): HostedDensityPreparation {
    apply()
    return inspect()
}

internal fun hostedDensityPreparationResult(
    hasLease: Boolean,
    active: Boolean,
    applicationState: String?,
): HostedDensityPreparation = when {
    hasLease && active -> HostedDensityPreparation.READY_WITH_LEASE
    !hasLease && applicationState in setOf("unchanged", "already_applied") ->
        HostedDensityPreparation.READY_UNCHANGED
    else -> HostedDensityPreparation.FAILED
}

internal fun <T> promoteHostedLeaseTransition(
    leases: MutableMap<String, T>,
    activeOwners: MutableSet<String>,
    canonicalOwner: String,
    transitionOwner: String,
    rename: (T, String) -> T,
    persist: () -> Boolean,
): Boolean {
    val transition = leases[transitionOwner] ?: return !leases.containsKey(canonicalOwner)
    if (leases.containsKey(canonicalOwner)) return false
    leases.remove(transitionOwner)
    activeOwners.remove(transitionOwner)
    leases[canonicalOwner] = rename(transition, canonicalOwner)
    activeOwners.add(canonicalOwner)
    if (persist()) return true
    leases.remove(canonicalOwner)
    activeOwners.remove(canonicalOwner)
    leases[transitionOwner] = transition
    activeOwners.add(transitionOwner)
    return false
}

internal fun directDensityForScale(nativeDensity: Int, scalePercent: Int): Int {
    if (nativeDensity <= 0 || scalePercent !in 50..150 || scalePercent == 100) return 0
    return (nativeDensity.toLong() * scalePercent / 100L)
        .coerceIn(1L, 10_000L).toInt()
}

internal data class DensityReading(val physical: Int, val override: Int?) {
    val effective: Int get() = override ?: physical

    companion object {
        fun parse(output: String): DensityReading? {
            val physicalMatches = Regex("(?m)^\\s*Physical density:\\s*(\\d+)\\s*$")
                .findAll(output).toList()
            if (physicalMatches.size != 1) return null
            val physical = physicalMatches.single().groupValues[1].toIntOrNull() ?: return null
            if (physical !in 1..10_000) return null
            val overrideMatches = Regex("(?m)^\\s*Override density:\\s*(\\d+)\\s*$")
                .findAll(output).toList()
            if (overrideMatches.size > 1) return null
            val raw = overrideMatches.singleOrNull()?.groupValues?.get(1)
            val override = raw?.toIntOrNull()
            if (raw != null && (override == null || override !in 1..10_000)) return null
            return DensityReading(physical, override)
        }

        fun parseWindowDump(dump: String?, displayId: Int): DensityReading? {
            if (dump == null || displayId <= 0) return null
            val headers = Regex("(?m)^\\s*Display:\\s*mDisplayId=(\\d+)\\s*$")
                .findAll(dump).toList()
            val target = headers.filter { it.groupValues[1].toIntOrNull() == displayId }
                .singleOrNull() ?: return null
            val next = headers.firstOrNull { it.range.first > target.range.first }
            val block = dump.substring(target.range.last + 1, next?.range?.first ?: dump.length)
            val initial = Regex("(?m)^\\s*init=\\d+x\\d+\\s+(\\d+)dpi\\b[^\\r\\n]*")
                .findAll(block).toList().singleOrNull() ?: return null
            val physical = initial.groupValues[1].toIntOrNull() ?: return null
            val baseMatches = Regex("\\bbase=\\d+x\\d+\\s+(\\d+)dpi\\b")
                .findAll(initial.value).toList()
            if (baseMatches.size > 1 || physical !in 1..10_000) return null
            val base = if (baseMatches.isEmpty()) physical
                else baseMatches.single().groupValues[1].toIntOrNull() ?: return null
            if (base !in 1..10_000) return null
            return DensityReading(physical, base.takeIf { it != physical })
        }

        fun canUseWindowFallback(output: String): Boolean =
            output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() ==
                listOf("Physical density: -1")

        fun command(displayId: Int, value: Int?): List<String>? {
            if (displayId <= 0 || (value != null && value !in 0..10_000)) return null
            val args = mutableListOf("/system/bin/wm", "density")
            if (value != null) args.add(if (value == 0) "reset" else value.toString())
            args.addAll(listOf("-d", displayId.toString()))
            return args
        }
    }
}
