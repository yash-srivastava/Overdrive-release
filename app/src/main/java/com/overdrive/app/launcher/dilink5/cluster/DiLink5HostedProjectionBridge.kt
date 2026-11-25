package com.overdrive.app.launcher.dilink5.cluster

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.ResultReceiver
import android.view.Display
import android.view.Surface
import com.overdrive.app.byd.VehicleActuatorBridge
import com.overdrive.app.byd.dilink5.TrustedAppUidResolver
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.monitor.BoundedShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Framework errors may include Intent, caller or window dumps. Retain only a bounded line
// and redact dump bodies, paths, addresses, and opaque identifiers.
private val frameworkErrorDetails by lazy { Regex("""\{.*\}|\S*[/@]\S*|(?i:\b(?:token|password|secret|deviceid|vin)\s*[:=]\s*\S+)|\b[A-Za-z0-9_-]{16,}\b|\p{Cntrl}""") }

internal fun hostedProjectionFailure(error: Throwable): String {
    val type = error.javaClass.simpleName
    return try {
        val message = error.message?.lineSequence()?.firstOrNull()
            ?.replace(frameworkErrorDetails, "[redacted]")?.take(200)?.takeIf { it.isNotBlank() }
        "$type: ${message ?: "no message"}"
    } catch (_: RuntimeException) {
        // Keep the original failure type even when the platform formatter fails.
        type
    }
}

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
    internal const val EXTRA_SEALION7_HDMI = "hosted_projection_sealion7_hdmi"
    internal const val RESULT_DISPLAY_ID = "display_id"
    internal const val RESULT_DISPLAY_WIDTH = "display_width"
    internal const val RESULT_DISPLAY_HEIGHT = "display_height"
    internal const val RESULT_DISPLAY_DENSITY = "display_density"
    internal const val RESULT_CONFIRMED_MAP_BOUNDS = "confirmed_map_bounds"

    private const val TX_CREATE = IBinder.FIRST_CALL_TRANSACTION
    internal const val TX_RELEASE_FROM_APP = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val WAIT_MS = 3_000L
    private const val APP_OPS_WAIT_MS = 3_000L
    internal const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"
    internal const val FLAGS_PREFERRED = 323
    internal const val FLAGS_FALLBACK = 322
    internal const val FLAGS_SEALION7 = FLAGS_PREFERRED or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private val logger by lazy { DaemonLogger.getInstance("DiLink5ClusterCast") }
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

    private class Pending(
        val physicalDisplayId: Int,
        val sealion7Geometry: HostedProjectionGeometry?,
    ) {
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
        sealion7Hdmi: Boolean = false,
    ): AppDisplayInfo? {
        val diagnostic = if (sealion7Hdmi) "Sealion 7 hosted probe display=$physicalDisplayId" else null
        if (physicalDisplayId <= 0) {
            diagnostic?.let { logger.warn("$it rejected: invalid display ID") }
            return null
        }
        if (diagnostic != null) {
            val eligibleId = if (aaosMapArea) null else Sealion7HdmiProjection.findDisplay(daemonContext)?.displayId
            if (eligibleId != physicalDisplayId) {
                logger.warn("$diagnostic rejected: mapArea=$aaosMapArea eligibleDisplay=${eligibleId ?: "unavailable"}")
                return null
            }
        }
        attachDaemonContext(daemonContext)
        // PROJECT_MEDIA may make the OEM display visible to the app UID. Probe the actual
        // app display even if the grant/readback failed; a shell-only decision needs that proof.
        if (!aaosMapArea && !sealion7Hdmi) ensureProjectionAppOps()
        val complete = CountDownLatch(1)
        val observed = AtomicReference<AppDisplayInfo?>()
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val details = if (resultCode == 1 && resultData != null) {
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
                    val accepted = (!aaosMapArea || info.confirmedMapBounds != null) &&
                        (!sealion7Hdmi || Sealion7HdmiProjection.geometry(info) != null) &&
                        HostedProjectionBoundary.appDisplayInfoValid(
                            info.displayId,
                            physicalDisplayId,
                            info.width,
                            info.height,
                            info.densityDpi,
                        )
                    if (accepted) observed.set(info)
                    diagnostic?.let {
                        "display=${info.displayId} metrics=${width}x$height@${info.densityDpi} accepted=$accepted"
                    }
                } else {
                    diagnostic?.let { "data=${resultData != null} accepted=false" }
                }
                complete.countDown()
                if (details != null) logger.info("$diagnostic response code=$resultCode $details")
            }
        }
        diagnostic?.let { logger.info("$it preparing service request") }
        return try {
            val request = Intent(context, DiLink5HostedProjectionService::class.java)
                .setAction(ACTION_PROBE_DISPLAY)
                .putExtra(EXTRA_PHYSICAL_DISPLAY, physicalDisplayId)
                .putExtra(EXTRA_AAOS_MAP_AREA, aaosMapArea)
                .putExtra(EXTRA_SEALION7_HDMI, sealion7Hdmi)
                .putExtra(EXTRA_RESULT_RECEIVER, receiver)
            if (!dispatchService(request, sealion7Hdmi, diagnostic)) return null
            if (complete.await(WAIT_MS, TimeUnit.MILLISECONDS)) observed.get() else {
                diagnostic?.let { logger.warn("$it timed out waiting for app response") }
                null
            }
        } catch (interrupted: InterruptedException) {
            diagnostic?.let { logger.warn("$it interrupted") }
            Thread.currentThread().interrupt()
            null
        } catch (error: Throwable) {
            diagnostic?.let { logger.warn("$it probe failed ${hostedProjectionFailure(error)}") }
            null
        }
    }

    private fun dispatchService(request: Intent, sealion7Hdmi: Boolean, diagnostic: String?): Boolean {
        val appContext = context ?: return false
        val component = try {
            HostedProjectionBoundary.startService(
                sealion7Hdmi, android.os.Process.myUid(),
                asShell = {
                    val started = VehicleActuatorBridge.startDiLink5BackgroundServiceAsShell(appContext, request)
                    if (started == null) diagnostic?.let { logger.warn("$it shell start returned null, trying context") }
                    started
                },
                fromContext = { appContext.startService(request) },
            )
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (error: Throwable) {
            diagnostic?.let {
                logger.warn("$it startService threw uid=${android.os.Process.myUid()} ${hostedProjectionFailure(error)}")
            }
            return false
        }
        if (sealion7Hdmi && component != null && component != request.component) {
            diagnostic?.let { logger.warn("$it startService rejected the requested component") }
            return false
        }
        diagnostic?.let {
            if (component == null) logger.warn("$it startService returned null uid=${android.os.Process.myUid()}")
            else logger.info("$it startService accepted")
        }
        return component != null
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
        sealion7Hdmi: Boolean = false,
    ): HostedSession? {
        val diagnostic = if (sealion7Hdmi) "Sealion 7 hosted create generation=$generation display=$physicalDisplayId" else null
        if (generation <= 0 || physicalDisplayId <= 0) return null
        if (sealion7Hdmi && (aaosMapArea ||
                Sealion7HdmiProjection.findDisplay(daemonContext)?.displayId != physicalDisplayId ||
                Sealion7HdmiProjection.geometry(AppDisplayInfo(
                    physicalDisplayId, geometry.panelWidth, geometry.panelHeight, geometry.densityDpi,
                )) != geometry)) return null
        attachDaemonContext(daemonContext)
        if (!aaosMapArea && !sealion7Hdmi && !ensureProjectionAppOps()) return null
        val ticket = Pending(physicalDisplayId, geometry.takeIf { sealion7Hdmi })
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
            .putExtra(EXTRA_SEALION7_HDMI, sealion7Hdmi)
            .putExtra(EXTRA_LEFT, geometry.overlay.left)
            .putExtra(EXTRA_TOP, geometry.overlay.top)
            .putExtra(EXTRA_WIDTH, geometry.overlay.width())
            .putExtra(EXTRA_HEIGHT, geometry.overlay.height())
            .putExtra(EXTRA_BUFFER_WIDTH, geometry.bufferWidth)
            .putExtra(EXTRA_BUFFER_HEIGHT, geometry.bufferHeight)
            .putExtra(EXTRA_DENSITY, geometry.densityDpi)
        var accepted = false
        return try {
            if (!dispatchService(intent, sealion7Hdmi, diagnostic)) return null
            val result = if (!HostedProjectionBoundary.awaitSurface(ticket.complete, WAIT_MS)) {
                diagnostic?.let { logger.warn("$it timed out waiting for the hosted surface") }
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
                if (ticket.session != null && !ticket.cancelled) release(generation, sealion7Hdmi)
                else requestAppRelease(generation, sealion7Hdmi)
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun release(generation: Long, sealion7Hdmi: Boolean = false): Boolean {
        if (generation <= 0) return false
        synchronized(lock) { pending.remove(generation) }?.let {
            it.cancelled = true
            it.complete.countDown()
        }
        val ticket = synchronized(lock) {
            if (pendingReleases.containsKey(generation)) return false
            ReleasePending().also { pendingReleases[generation] = it }
        }
        if (!releaseDisplayOnly(generation) || !requestAppRelease(generation, sealion7Hdmi)) {
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
    internal fun withdrawMapArea(generation: Long, sealion7Hdmi: Boolean) {
        synchronized(lock) { sessions[generation]?.surfaceLost = true }
        requestAppRelease(generation, sealion7Hdmi)
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
     * Only the exact hosted display created by our verified shell identity qualifies. A failed
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
        val shell = shellDisplayContext(
            daemonContext,
            sealion7Hdmi = ownership.sealion7Hdmi && Sealion7HdmiProjection.isTestedFirmware(),
        ) ?: return null
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
        val diagnostic = ticket.sealion7Geometry?.let { "Sealion 7 hosted allocation generation=$generation" }
        ticket.sealion7Geometry?.let {
            if (physicalId != ticket.physicalDisplayId || width != it.bufferWidth ||
                height != it.bufferHeight || density != it.densityDpi ||
                Sealion7HdmiProjection.findDisplay(daemonContext)?.displayId != physicalId) {
                logger.warn("$diagnostic declined: HDMI display or geometry changed")
                return null
            }
        }
        val displayContext = shellDisplayContext(daemonContext, ticket.sealion7Geometry != null, diagnostic) ?: return null
        val manager = displayContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (manager == null) {
            diagnostic?.let { logger.warn("$it could not obtain the shell display manager") }
            return null
        }
        val name = "OverDriveCluster-$generation"
        fun create(flags: Int): VirtualDisplay? = try {
            manager.createVirtualDisplay(name, width, height, density, surface, flags)
        } catch (error: Throwable) {
            diagnostic?.let { logger.warn("$it flags=$flags failed: ${hostedProjectionFailure(error)}") }
            null
        }
        val allocation = HostedProjectionBoundary.allocateDisplay(ticket.sealion7Geometry != null, ::create)
        if (allocation == null) {
            diagnostic?.let { logger.warn("$it produced no virtual display") }
            return null
        }
        val display = allocation.value
        val id = display.display?.displayId ?: Display.INVALID_DISPLAY
        if (id <= 0) {
            diagnostic?.let { logger.warn("$it flags=${allocation.flags} returned no usable display ID") }
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
        diagnostic?.let { logger.info("$it published content display $id flags=${allocation.flags}") }
        return session
        } finally {
            synchronized(lock) { creatingGenerations.remove(generation) }
        }
    }

    private fun shellDisplayContext(base: Context, sealion7Hdmi: Boolean, diagnostic: String? = null): Context? {
        val uid = android.os.Process.myUid()
        if (uid != SHELL_UID) {
            diagnostic?.let { logger.warn("$it rejected display context: process UID=$uid") }
            return null
        }
        return try {
            val shell = base.createPackageContext(
                SHELL_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val app = shell.applicationInfo
            val installed = shell.packageManager.getApplicationInfo(SHELL_PACKAGE, 0)
            val opPackage = Context::class.java.getMethod("getOpPackageName").invoke(shell) as? String
            val packageName = shell.packageName
            val accepted = HostedProjectionBoundary.shellIdentityAccepted(
                uid, packageName, app.uid, installed.uid, opPackage, sealion7Hdmi,
            )
            diagnostic?.let {
                val attribution = when (opPackage) {
                    SHELL_PACKAGE, "android" -> opPackage
                    null -> "missing"
                    else -> "other"
                }
                logger.info("$it display context accepted=$accepted shellPackage=${packageName == SHELL_PACKAGE} " +
                    "contextUid=${app.uid} installedUid=${installed.uid} opPackage=$attribution")
            }
            shell.takeIf { accepted }
        } catch (error: Throwable) {
            diagnostic?.let { logger.warn("$it display context lookup failed: ${hostedProjectionFailure(error)}") }
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

    private fun requestAppRelease(generation: Long, sealion7Hdmi: Boolean): Boolean {
        val appContext = context ?: return false
        val diagnostic = if (sealion7Hdmi) "Sealion 7 hosted release generation=$generation" else null
        return try {
            val extras = Bundle().apply { putBinder(EXTRA_BRIDGE, binder) }
            val request = Intent(appContext, DiLink5HostedProjectionService::class.java)
                .setAction(ACTION_RELEASE)
                .putExtras(extras)
                .putExtra(EXTRA_GENERATION, generation)
            if (sealion7Hdmi) request.putExtra(EXTRA_SEALION7_HDMI, true)
            dispatchService(request, sealion7Hdmi, diagnostic)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
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
    inline fun <T : Any> startService(
        sealion7Hdmi: Boolean, uid: Int, asShell: () -> T?, fromContext: () -> T?,
    ): T? {
        if (sealion7Hdmi && uid == DiLink5HostedProjectionBridge.SHELL_UID) {
            asShell()?.let { return it }
        }
        return fromContext()
    }

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

    fun <T> allocateDisplay(sealion7Hdmi: Boolean, create: (Int) -> T?): Allocation<T>? {
        // The Sealion app needs a public display for its own content. The private fallback
        // can allocate successfully while remaining invisible to the app on this firmware.
        if (sealion7Hdmi) {
            val flags = DiLink5HostedProjectionBridge.FLAGS_SEALION7
            return create(flags)?.let { Allocation(it, flags) }
        }
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
        sealion7Hdmi: Boolean = false,
    ): Boolean = processUid == 2000 && packageName == "com.android.shell" &&
        applicationUid == 2000 && installedUid == 2000 &&
        // The tested Sealion 7 firmware attributes this shell display context to the framework.
        (opPackageName == "com.android.shell" || (sealion7Hdmi && opPackageName == "android"))
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
