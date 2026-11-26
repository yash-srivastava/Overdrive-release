package com.overdrive.app.launcher.dilink5.cluster

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver
import android.view.Surface
import com.overdrive.app.byd.dilink5.TrustedAppUidResolver
import com.overdrive.app.monitor.BoundedShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Daemon-side owner of hosted content displays. No service is published on a network. */
object DiLink5HostedProjectionBridge {
    const val PROTOCOL_VERSION = 1
    const val DESCRIPTOR = "com.overdrive.app.cluster.IHostedProjection"
    const val ACTION_PROBE_DISPLAY = "com.overdrive.app.action.PROBE_HOSTED_PROJECTION_DISPLAY"
    const val ACTION_CREATE = "com.overdrive.app.action.CREATE_HOSTED_PROJECTION"
    const val ACTION_RELEASE = "com.overdrive.app.action.RELEASE_HOSTED_PROJECTION"
    const val EXTRA_BRIDGE = "hosted_projection_bridge"
    const val EXTRA_RESULT_RECEIVER = "hosted_projection_result_receiver"
    const val EXTRA_GENERATION = "hosted_projection_generation"
    const val EXTRA_PHYSICAL_DISPLAY = "hosted_projection_physical_display"
    const val EXTRA_PHYSICAL_NAME = "hosted_projection_physical_name"
    const val EXTRA_LEFT = "hosted_projection_left"
    const val EXTRA_TOP = "hosted_projection_top"
    const val EXTRA_WIDTH = "hosted_projection_width"
    const val EXTRA_HEIGHT = "hosted_projection_height"
    const val EXTRA_BUFFER_WIDTH = "hosted_projection_buffer_width"
    const val EXTRA_BUFFER_HEIGHT = "hosted_projection_buffer_height"
    const val EXTRA_DENSITY = "hosted_projection_density"
    internal const val EXTRA_AAOS_MAP_AREA = "hosted_projection_aaos_map_area"
    internal const val RESULT_DISPLAY_ID = "display_id"
    internal const val RESULT_DISPLAY_WIDTH = "display_width"
    internal const val RESULT_DISPLAY_HEIGHT = "display_height"
    internal const val RESULT_DISPLAY_DENSITY = "display_density"
    internal const val RESULT_CONFIRMED_MAP_BOUNDS = "confirmed_map_bounds"

    private const val TX_CREATE = IBinder.FIRST_CALL_TRANSACTION
    internal const val TX_RELEASE_FROM_APP = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val WAIT_MS = 3_000L
    private const val APP_OPS_WAIT_MS = 3_000L
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"
    internal const val FLAGS_PREFERRED = 323
    internal const val FLAGS_FALLBACK = 322
    private val lock = Any()
    private val sessions = HashMap<Long, Record>()
    private val creatingGenerations = HashSet<Long>()
    /** Allocations that could not be published and whose release threw; retried by generation. */
    private val retainedDisplays = HashMap<Long, VirtualDisplay>()
    private val pending = HashMap<Long, Pending>()
    private val pendingReleases = HashMap<Long, ReleasePending>()
    @Volatile private var context: Context? = null

    data class AppDisplayInfo @JvmOverloads constructor(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val confirmedMapBounds: HostedProjectionGeometry.Bounds? = null,
    )

    data class HostedSession(
        val generation: Long,
        val physicalDisplayId: Int,
        val physicalDisplayName: String,
        val contentDisplayId: Int,
        val virtualDisplayName: String
    )

    private class Pending {
        val complete = CountDownLatch(1)
        @Volatile var session: HostedSession? = null
        @Volatile var cancelled = false
    }

    private class ReleasePending {
        val complete = CountDownLatch(1)
        @Volatile var confirmed = false
    }

    private data class Record(
        val session: HostedSession,
        val display: VirtualDisplay,
        val owner: IBinder,
        val death: IBinder.DeathRecipient
    ) {
        @Volatile var surfaceLost = false
    }

    private val binder = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            when (code) {
                TX_CREATE -> handleCreate(data, reply)
                TX_RELEASE_FROM_APP -> handleAppRelease(data, reply)
                else -> super.onTransact(code, data, reply, flags)
            }
    }

    private fun handleCreate(data: Parcel, reply: Parcel?): Boolean {
        var surface: Surface? = null
        try {
            data.enforceInterface(DESCRIPTOR)
            val daemonContext = context ?: return rejected(reply)
            val expectedUid = TrustedAppUidResolver(daemonContext).resolve()
            val version = data.readInt()
            val generation = data.readLong()
            val physicalId = data.readInt()
            val width = data.readInt()
            val height = data.readInt()
            val density = data.readInt()
            val owner = data.readStrongBinder()
            val surfacePresent = data.readInt()
            if (!HostedProjectionBoundary.admit(
                    Binder.getCallingUid(), expectedUid, version, generation, physicalId,
                    width, height, density, owner != null, surfacePresent, data.dataAvail()
                )) return rejected(reply)
            surface = Surface.CREATOR.createFromParcel(data)
            if (!HostedProjectionBoundary.completeCreateFrame(data.dataAvail()) || !surface.isValid) {
                return rejected(reply)
            }
            val created = createVirtualDisplay(
                daemonContext,
                generation,
                physicalId,
                width,
                height,
                density,
                surface,
                owner ?: return rejected(reply),
            ) ?: return rejected(reply)
            reply?.writeNoException()
            reply?.writeInt(1)
            reply?.writeInt(created.contentDisplayId)
            return true
        } catch (_: Throwable) {
            return rejected(reply)
        } finally {
            try { surface?.release() } catch (_: Throwable) {}
        }
    }

    private fun handleAppRelease(data: Parcel, reply: Parcel?): Boolean {
        return try {
            data.enforceInterface(DESCRIPTOR)
            val daemonContext = context ?: return rejected(reply)
            val expectedUid = TrustedAppUidResolver(daemonContext).resolve()
            val version = data.readInt()
            val generation = data.readLong()
            val overlayReleased = data.readInt() == 1
            if (Binder.getCallingUid() != expectedUid || version != PROTOCOL_VERSION ||
                generation <= 0 || data.dataAvail() != 0
            ) return rejected(reply)
            val expectedRelease = synchronized(lock) {
                pending.remove(generation)?.let {
                    it.cancelled = true
                    it.complete.countDown()
                }
                sessions[generation]?.surfaceLost = true
                pendingReleases[generation]
            }
            // An unexpected Surface loss must not destroy the display that a density lease
            // still needs. Cast owns task reclamation and density restoration before release.
            val confirmed = HostedProjectionBoundary.finishAppSurfaceRelease(
                expectedRelease != null,
                overlayReleased,
                releaseDisplay = { releaseDisplayOnly(generation) },
                onUnexpectedLoss = {
                    com.overdrive.app.launcher.DiLink5ClusterCast.onHostedSurfaceLost(generation)
                },
            )
            expectedRelease?.let {
                it.confirmed = confirmed
                it.complete.countDown()
            }
            reply?.writeNoException()
            reply?.writeInt(if (confirmed) 1 else 0)
            true
        } catch (_: Throwable) {
            rejected(reply)
        }
    }

    @JvmStatic
    fun attachDaemonContext(daemonContext: Context) {
        context = daemonContext.applicationContext ?: daemonContext
    }

    @JvmStatic
    @JvmOverloads
    fun probeAppDisplay(
        daemonContext: Context, physicalDisplayId: Int, aaosMapArea: Boolean = false,
    ): AppDisplayInfo? {
        if (physicalDisplayId <= 0) return null
        attachDaemonContext(daemonContext)
        // PROJECT_MEDIA may make the OEM display visible to the app UID. Probe the actual
        // app display even if the grant/readback failed; a shell-only decision needs that proof.
        if (!aaosMapArea) ensureProjectionAppOps()
        val complete = CountDownLatch(1)
        val observed = AtomicReference<AppDisplayInfo?>()
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == 1 && resultData != null) {
                    val width = resultData.getInt(RESULT_DISPLAY_WIDTH, 0)
                    val height = resultData.getInt(RESULT_DISPLAY_HEIGHT, 0)
                    val info = AppDisplayInfo(
                        resultData.getInt(RESULT_DISPLAY_ID, -1),
                        width,
                        height,
                        resultData.getInt(RESULT_DISPLAY_DENSITY, 0),
                        HostedProjectionBoundary.mapBoundsFromProbe(
                            resultData.getIntArray(RESULT_CONFIRMED_MAP_BOUNDS), width, height,
                        ),
                    )
                    if ((!aaosMapArea || info.confirmedMapBounds != null) && HostedProjectionBoundary.appDisplayInfoValid(
                            info.displayId,
                            physicalDisplayId,
                            info.width,
                            info.height,
                            info.densityDpi,
                        )
                    ) observed.set(info)
                }
                complete.countDown()
            }
        }
        return try {
            val request = Intent(context, DiLink5HostedProjectionService::class.java)
                .setAction(ACTION_PROBE_DISPLAY)
                .putExtra(EXTRA_PHYSICAL_DISPLAY, physicalDisplayId)
                .putExtra(EXTRA_AAOS_MAP_AREA, aaosMapArea)
                .putExtra(EXTRA_RESULT_RECEIVER, receiver)
            if (context?.startService(request) == null) return null
            if (complete.await(WAIT_MS, TimeUnit.MILLISECONDS)) observed.get() else null
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun isAppDisplayVisible(daemonContext: Context, physicalDisplayId: Int): Boolean =
        probeAppDisplay(daemonContext, physicalDisplayId) != null

    @JvmStatic
    @JvmOverloads
    fun start(
        daemonContext: Context,
        generation: Long,
        physicalDisplayId: Int,
        physicalDisplayName: String,
        geometry: HostedProjectionGeometry,
        aaosMapArea: Boolean = false,
    ): HostedSession? {
        if (generation <= 0 || physicalDisplayId <= 0) return null
        attachDaemonContext(daemonContext)
        if (!aaosMapArea && !ensureProjectionAppOps()) return null
        val ticket = Pending()
        synchronized(lock) {
            if (sessions.containsKey(generation) || retainedDisplays.containsKey(generation) ||
                pending.put(generation, ticket) != null
            ) {
                return null
            }
        }
        val extras = Bundle().apply { putBinder(EXTRA_BRIDGE, binder) }
        val intent = Intent(context, DiLink5HostedProjectionService::class.java)
            .setAction(ACTION_CREATE)
            .putExtras(extras)
            .putExtra(EXTRA_GENERATION, generation)
            .putExtra(EXTRA_PHYSICAL_DISPLAY, physicalDisplayId)
            .putExtra(EXTRA_PHYSICAL_NAME, physicalDisplayName)
            .putExtra(EXTRA_AAOS_MAP_AREA, aaosMapArea)
            .putExtra(EXTRA_LEFT, geometry.overlay.left)
            .putExtra(EXTRA_TOP, geometry.overlay.top)
            .putExtra(EXTRA_WIDTH, geometry.overlay.width())
            .putExtra(EXTRA_HEIGHT, geometry.overlay.height())
            .putExtra(EXTRA_BUFFER_WIDTH, geometry.bufferWidth)
            .putExtra(EXTRA_BUFFER_HEIGHT, geometry.bufferHeight)
            .putExtra(EXTRA_DENSITY, geometry.densityDpi)
        var accepted = false
        return try {
            context?.startService(intent) ?: return null
            val result = if (!HostedProjectionBoundary.awaitSurface(ticket.complete, WAIT_MS)) {
                null
            } else {
                synchronized(lock) {
                    ticket.session?.takeIf {
                        !ticket.cancelled && sessions[generation]?.session === it &&
                            sessions[generation]?.surfaceLost == false
                    }?.copy(physicalDisplayName = physicalDisplayName)
                }
            }
            accepted = result != null
            result
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Throwable) {
            null
        } finally {
            synchronized(lock) { pending.remove(generation, ticket) }
            if (!accepted) {
                if (ticket.session != null && !ticket.cancelled) release(generation)
                else requestAppRelease(generation)
            }
        }
    }

    @JvmStatic
    fun release(generation: Long): Boolean {
        if (generation <= 0) return false
        synchronized(lock) { pending.remove(generation) }?.let {
            it.cancelled = true
            it.complete.countDown()
        }
        val ticket = synchronized(lock) {
            if (pendingReleases.containsKey(generation)) return false
            ReleasePending().also { pendingReleases[generation] = it }
        }
        if (!releaseDisplayOnly(generation) || !requestAppRelease(generation)) {
            synchronized(lock) { pendingReleases.remove(generation, ticket) }
            return false
        }
        val completed = try {
            ticket.complete.await(WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        synchronized(lock) { pendingReleases.remove(generation, ticket) }
        return HostedProjectionBoundary.releaseConfirmed(completed, ticket.confirmed)
    }

    private fun releaseDisplayOnly(generation: Long): Boolean {
        // A Binder allocation may outlive the 3s caller wait. Keep its journal until that allocator
        // finishes and either publishes a handle or retains an unconfirmed release for retry.
        if (!HostedProjectionBoundary.mayConfirmDisplayRelease(
                synchronized(lock) { generation in creatingGenerations },
            )
        ) return false
        val record = synchronized(lock) { sessions[generation] }
        if (record != null) {
            try {
                record.display.release()
            } catch (_: Throwable) {
                return false
            }
            try { record.owner.unlinkToDeath(record.death, 0) } catch (_: Throwable) {}
            return synchronized(lock) {
                if (sessions[generation] === record) sessions.remove(generation)
                sessions[generation] !== record
            }
        }
        val retained = synchronized(lock) { retainedDisplays[generation] } ?: return true
        try {
            retained.release()
        } catch (_: Throwable) {
            return false
        }
        return synchronized(lock) {
            if (retainedDisplays[generation] === retained) retainedDisplays.remove(generation)
            retainedDisplays[generation] !== retained
        }
    }

    private fun releaseOrRetain(generation: Long, display: VirtualDisplay): Boolean {
        return try {
            display.release()
            true
        } catch (_: Throwable) {
            synchronized(lock) {
                retainedDisplays.putIfAbsent(generation, display)
            }
            false
        }
    }

    @JvmStatic
    fun contentDisplayId(generation: Long): Int = synchronized(lock) {
        sessions[generation]?.session?.contentDisplayId ?: -1
    }

    @JvmStatic
    fun isSurfaceAlive(generation: Long): Boolean = synchronized(lock) {
        sessions[generation]?.surfaceLost == false
    }

    /** Withdraw the physical window immediately; Cast restores the task before releasing its VD. */
    internal fun withdrawMapArea(generation: Long) {
        synchronized(lock) { sessions[generation]?.surfaceLost = true }
        requestAppRelease(generation)
        com.overdrive.app.launcher.DiLink5ClusterCast.onHostedSurfaceLost(generation)
    }

    /**
     * Surface loss and publication share this monitor. A producer that has already died cannot
     * replace a working host; loss after publication observes the newly published generation.
     * Callbacks into Cast must continue to run after releasing [lock].
     */
    internal fun <T> withLiveSurfaceGeneration(generation: Long, operation: () -> T): T? =
        HostedProjectionBoundary.withLiveSurfaceAuthority(
            lock,
            surfaceAlive = { sessions[generation]?.surfaceLost == false },
            operation = operation,
        )

    /**
     * Only the exact private display created by our verified shell identity qualifies. A failed
     * query or an inventory without the default display cannot be used as disappearance proof.
     */
    @JvmStatic
    fun confirmedOwnedContentDisplayRetired(ownership: DiLink5ProjectionOwnership): Boolean? {
        if (ownership.transport != "hosted" || !ownership.virtualDisplayOwned ||
            ownership.contentDisplayId <= 0 ||
            ownership.contentDisplayId == ownership.physicalDisplayId ||
            ownership.virtualDisplayName != "OverDriveCluster-${ownership.generation}" ||
            ownership.bootId.isEmpty()
        ) return null
        val daemonContext = context ?: return null
        val shell = shellDisplayContext(daemonContext) ?: return null
        return try {
            val manager = shell.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return null
            val inventory = manager.displays
            if (inventory.none { it.displayId == 0 && it.isValid }) return null
            HostedProjectionBoundary.ownedContentDisplayRetired(
                ownership.contentDisplayId,
                ownership.virtualDisplayName,
                inventory.map { HostedProjectionBoundary.DisplayIdentity(
                    it.displayId, it.name, it.isValid,
                ) },
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun createVirtualDisplay(
        daemonContext: Context,
        generation: Long,
        physicalId: Int,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        owner: IBinder
    ): HostedSession? {
        val ticket = synchronized(lock) {
            pending[generation]?.takeIf {
                !it.cancelled && creatingGenerations.add(generation)
            }
        } ?: return null
        try {
        val displayContext = shellDisplayContext(daemonContext) ?: return null
        val manager = displayContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        val name = "OverDriveCluster-$generation"
        fun create(flags: Int): VirtualDisplay? = try {
            manager.createVirtualDisplay(name, width, height, density, surface, flags)
        } catch (_: Throwable) { null }
        val allocation = HostedProjectionBoundary.preferredThenFallback(::create) ?: return null
        val display = allocation.value
        val id = display.display?.displayId ?: run {
            releaseOrRetain(generation, display)
            return null
        }
        if (id <= 0) {
            releaseOrRetain(generation, display)
            return null
        }
        val session = HostedSession(generation, physicalId, "", id, name)
        val death = IBinder.DeathRecipient {
            synchronized(lock) {
                sessions[generation]?.surfaceLost = true
                pending.remove(generation)?.let {
                    it.cancelled = true
                    it.complete.countDown()
                }
            }
            com.overdrive.app.launcher.DiLink5ClusterCast.onHostedSurfaceLost(generation)
        }
        try { owner.linkToDeath(death, 0) } catch (_: Throwable) {
            releaseOrRetain(generation, display)
            return null
        }
        val record = Record(session, display, owner, death)
        val published = synchronized(lock) {
            if (!HostedProjectionBoundary.canPublish(
                    pending[generation] === ticket,
                    sessions.containsKey(generation),
                ) || ticket.cancelled
            ) {
                false
            } else {
                sessions[generation] = record
                ticket.session = session
                ticket.complete.countDown()
                true
            }
        }
        if (!published) {
            try { owner.unlinkToDeath(death, 0) } catch (_: Throwable) {}
            releaseOrRetain(generation, display)
            return null
        }
        return session
        } finally {
            synchronized(lock) { creatingGenerations.remove(generation) }
        }
    }

    private fun shellDisplayContext(base: Context): Context? {
        if (android.os.Process.myUid() != SHELL_UID) return null
        return try {
            val shell = base.createPackageContext(
                SHELL_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val app = shell.applicationInfo
            val installed = shell.packageManager.getApplicationInfo(SHELL_PACKAGE, 0)
            val opPackage = Context::class.java.getMethod("getOpPackageName").invoke(shell) as? String
            shell.takeIf {
                HostedProjectionBoundary.shellIdentityAccepted(
                    android.os.Process.myUid(),
                    it.packageName,
                    app.uid,
                    installed.uid,
                    opPackage,
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureProjectionAppOps(): Boolean {
        val appContext = context ?: return false
        val resolver = TrustedAppUidResolver(appContext)
        val expectedUid = resolver.resolve()
        fun identityCurrent(): Boolean = try {
            HostedProjectionBoundary.appIdentityAccepted(
                android.os.Process.myUid(),
                appContext.packageName,
                appContext.applicationInfo.uid,
                expectedUid,
            ) && resolver.resolve() == expectedUid
        } catch (_: Throwable) {
            false
        }
        if (!identityCurrent()) return false
        val deadline = android.os.SystemClock.elapsedRealtime() + APP_OPS_WAIT_MS
        fun run(operation: HostedProjectionBoundary.AppOp, grant: Boolean): BoundedShell.Result? {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0 || !identityCurrent()) return null
            return BoundedShell.run(
                HostedProjectionBoundary.projectionAppOpCommand(
                    appContext.packageName, expectedUid, operation, grant,
                ),
                remaining,
            )
        }
        return HostedProjectionBoundary.ensureOwnProjectionAppOps(
            identityCurrent = ::identityCurrent,
            readAllowed = { operation ->
                val result = run(operation, grant = false)
                result != null && result.ok() &&
                    HostedProjectionBoundary.appOpReadbackAllowed(operation, result.output)
            },
            grant = { operation -> run(operation, grant = true)?.ok() == true },
        )
    }

    private fun requestAppRelease(generation: Long): Boolean {
        val appContext = context ?: return false
        return try {
            val extras = Bundle().apply { putBinder(EXTRA_BRIDGE, binder) }
            appContext.startService(
                Intent(appContext, DiLink5HostedProjectionService::class.java)
                    .setAction(ACTION_RELEASE)
                    .putExtras(extras)
                    .putExtra(EXTRA_GENERATION, generation)
            ) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun rejected(reply: Parcel?): Boolean {
        try { reply?.writeNoException(); reply?.writeInt(0); reply?.writeInt(-1) }
        catch (_: Throwable) {}
        return true
    }
}

/** Pure admission/allocation seam used by the daemon Binder and lifecycle tests. */
internal object HostedProjectionBoundary {
    data class Allocation<T>(val value: T, val flags: Int)
    data class DisplayIdentity(val id: Int, val name: String, val valid: Boolean)
    enum class AppOp { SYSTEM_ALERT_WINDOW, PROJECT_MEDIA }

    fun admit(
        callingUid: Int,
        expectedUid: Int,
        version: Int,
        generation: Long,
        physicalId: Int,
        width: Int,
        height: Int,
        density: Int,
        ownerPresent: Boolean,
        surfacePresent: Int,
        remainingSurfaceBytes: Int,
    ): Boolean = expectedUid >= 0 && callingUid == expectedUid &&
        version == DiLink5HostedProjectionBridge.PROTOCOL_VERSION && generation > 0 &&
        physicalId > 0 && width in 1..8192 && height in 1..8192 && density in 1..10_000 &&
        width.toLong() * height <= 33_554_432L && ownerPresent && surfacePresent == 1 &&
        remainingSurfaceBytes > 0

    fun <T> preferredThenFallback(create: (Int) -> T?): Allocation<T>? {
        create(DiLink5HostedProjectionBridge.FLAGS_PREFERRED)?.let {
            return Allocation(it, DiLink5HostedProjectionBridge.FLAGS_PREFERRED)
        }
        return create(DiLink5HostedProjectionBridge.FLAGS_FALLBACK)?.let {
            Allocation(it, DiLink5HostedProjectionBridge.FLAGS_FALLBACK)
        }
    }

    fun awaitSurface(latch: CountDownLatch, timeoutMs: Long): Boolean =
        timeoutMs > 0 && latch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun shouldRelease(ownerAlive: Boolean, surfaceAlive: Boolean, generationCurrent: Boolean): Boolean =
        !ownerAlive || !surfaceAlive || !generationCurrent

    fun canPublish(pendingTicketCurrent: Boolean, sessionAlreadyExists: Boolean): Boolean =
        pendingTicketCurrent && !sessionAlreadyExists

    fun releaseConfirmed(callbackCompleted: Boolean, bothResourcesConfirmed: Boolean): Boolean =
        callbackCompleted && bothResourcesConfirmed

    fun mayConfirmDisplayRelease(allocationInFlight: Boolean): Boolean = !allocationInFlight

    fun <T> withLiveSurfaceAuthority(
        lock: Any,
        surfaceAlive: () -> Boolean,
        operation: () -> T,
    ): T? = synchronized(lock) {
        if (surfaceAlive()) operation() else null
    }

    fun finishAppSurfaceRelease(
        expected: Boolean,
        overlayReleased: Boolean,
        releaseDisplay: () -> Boolean,
        onUnexpectedLoss: () -> Unit,
    ): Boolean {
        if (expected) return overlayReleased && releaseDisplay()
        onUnexpectedLoss()
        return overlayReleased
    }

    /** An incomplete/failed shell inventory cannot prove that a private owned VD disappeared. */
    fun ownedContentDisplayRetired(
        contentId: Int,
        expectedName: String,
        inventory: List<DisplayIdentity>?,
    ): Boolean? {
        if (contentId <= 0 || expectedName.isEmpty() ||
            inventory == null || inventory.none { it.id == 0 && it.valid }
        ) return null
        val named = inventory.firstOrNull { it.name == expectedName }
        val present = named ?: inventory.firstOrNull { it.id == contentId }
        return when {
            present == null -> true
            !present.valid -> null
            present.name == expectedName -> false
            else -> true // a recycled ID belongs to a different display
        }
    }

    fun appIdentityAccepted(
        processUid: Int,
        packageName: String?,
        contextUid: Int,
        installedUid: Int,
    ): Boolean = processUid == 2000 && packageName == TrustedAppUidResolver.APP_PACKAGE &&
        installedUid >= 0 && installedUid / 100_000 == processUid / 100_000 &&
        contextUid == installedUid

    fun projectionAppOpCommand(packageName: String, installedUid: Int, operation: AppOp, grant: Boolean): String {
        require(packageName == TrustedAppUidResolver.APP_PACKAGE && installedUid >= 0)
        val userId = installedUid / 100_000
        return "/system/bin/appops ${if (grant) "set" else "get"} --user $userId " +
            "'com.overdrive.app' ${operation.name}${if (grant) " allow" else ""}"
    }

    /** Android 11 prints UID overrides as "Uid mode: OP: mode"; they precede package modes. */
    fun appOpReadbackAllowed(operation: AppOp, output: String): Boolean {
        val uidRows = output.lineSequence().map(String::trim)
            .filter { it.startsWith("Uid mode:") }.toList()
        if (uidRows.isNotEmpty()) {
            val uidMode = Regex("^Uid mode:\\s*${operation.name}:\\s*([a-z_]+)(?:[;\\s]|$)")
            // An explicit UID mode overrides the package mode. Unknown or conflicting UID
            // output cannot establish permission, even when the package row says allow.
            return uidRows.all { uidMode.find(it)?.groupValues?.get(1) == "allow" }
        }
        val namedModes = Regex(
            "^\\s*${operation.name}:\\s*([a-z_]+)(?:[;\\s]|$)",
            RegexOption.MULTILINE,
        ).findAll(output).map { it.groupValues[1] }.toList()
        return namedModes.isNotEmpty() && namedModes.all { it == "allow" }
    }

    /** No success cache: every projection attempt observes current modes and installed identity. */
    fun ensureOwnProjectionAppOps(
        identityCurrent: () -> Boolean,
        readAllowed: (AppOp) -> Boolean,
        grant: (AppOp) -> Boolean,
    ): Boolean {
        for (operation in AppOp.entries) {
            if (!identityCurrent()) return false
            if (readAllowed(operation)) continue
            if (!identityCurrent() || !grant(operation) || !identityCurrent() ||
                !readAllowed(operation)
            ) return false
        }
        return identityCurrent()
    }

    fun appDisplayInfoValid(
        observedDisplayId: Int,
        expectedDisplayId: Int,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean = expectedDisplayId > 0 && observedDisplayId == expectedDisplayId &&
        width > 1 && height > 1 && densityDpi > 0

    fun mapBoundsFromProbe(values: IntArray?, panelWidth: Int, panelHeight: Int): HostedProjectionGeometry.Bounds? {
        if (values == null || values.size != 4) return null
        val (left, top, right, bottom) = values
        if (left < 0 || top < 0 || right > panelWidth || bottom > panelHeight ||
            right <= left || bottom <= top) return null
        return HostedProjectionGeometry.Bounds(left, top, right, bottom)
    }

    fun appDisplayVisible(
        displayPresent: Boolean,
        displayValid: Boolean,
        observedDisplayId: Int,
        expectedDisplayId: Int,
    ): Boolean = displayPresent && displayValid &&
        appDisplayInfoValid(observedDisplayId, expectedDisplayId, 2, 2, 1)

    fun completeCreateFrame(remainingBytes: Int): Boolean = remainingBytes == 0

    fun shellIdentityAccepted(
        processUid: Int,
        packageName: String?,
        applicationUid: Int,
        installedUid: Int,
        opPackageName: String?,
    ): Boolean = processUid == 2000 && packageName == "com.android.shell" &&
        applicationUid == 2000 && installedUid == 2000 && opPackageName == "com.android.shell"
}

/** Removes a resource from its owner map only after release is confirmed. */
internal object RetriableHostedResources {
    fun <K, V> release(
        resources: MutableMap<K, V>,
        key: K,
        release: (V) -> Boolean,
    ): Boolean {
        val resource = resources[key] ?: return true
        if (!release(resource)) return false
        resources.remove(key, resource)
        return resources[key] != resource
    }
}
