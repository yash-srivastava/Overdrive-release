package com.overdrive.app.navmap

import com.overdrive.app.navmap.nav.CongestionSpan
import com.overdrive.app.navmap.nav.NavRoute

/**
 * Process-wide active-navigation state, shared between the two map instances:
 *  - the INTERACTIVE map on the infotainment display (display 0) PUBLISHES here
 *    when the user selects/starts/stops a route (it's the control surface);
 *  - the VIEW-ONLY cluster mirror (the RoadSenseClusterMapActivity alias on the
 *    fission display) OBSERVES and re-renders the same route in real time.
 *
 * Both instances live in the SAME app process, so this is a plain in-memory
 * singleton + listener set — no IPC. The cluster is non-touch, so it never
 * writes here; it only listens. Publishing is idempotent and cheap.
 *
 * Threading: all publish() / addListener() / removeListener() calls are expected
 * on the main thread (both Activities drive their map there). The listener set is
 * a CopyOnWriteArrayList so a late add/remove during dispatch is safe.
 */
object NavSession {

    /** Immutable snapshot of the active trip. route == null ⇒ no active nav. */
    data class State(
        val route: NavRoute?,
        val destLabel: String,
        val navigating: Boolean
    )

    @Volatile
    var state: State = State(null, "", false)
        private set

    /** elapsedRealtime of the last guidance step on either screen: guidance left running behind a closed map goes quiet. */
    @Volatile
    var guidanceTickAt = 0L
        private set

    /** The same for the head unit's map alone (it owns arrival). */
    @Volatile
    var headUnitTickAt = 0L
        private set

    fun guidanceTick(headUnit: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        guidanceTickAt = now
        if (headUnit) headUnitTickAt = now
    }

    fun interface Listener {
        fun onNavStateChanged(state: State)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /**
     * Add a listener and immediately replay the current state to it (so a cluster
     * instance launched AFTER a route was set still picks it up — edge-only
     * dispatch would otherwise miss the existing trip; cf. the lock-gate replay
     * lesson). Returns the listener for symmetric removal.
     */
    fun addListener(l: Listener): Listener {
        listeners.add(l)
        try { l.onNavStateChanged(state) } catch (_: Throwable) {}
        return l
    }

    fun removeListener(l: Listener?) { if (l != null) listeners.remove(l) }

    /** Publish an active route (selected on the infotainment map). navigating=true. */
    fun publishRoute(route: NavRoute, destLabel: String) {
        update(State(route, destLabel, navigating = true))
    }

    /** Publish a route PREVIEW (chosen but guidance not started yet). navigating=false. */
    fun publishPreview(route: NavRoute, destLabel: String) {
        update(State(route, destLabel, navigating = false))
    }

    /** Clear the active trip (route ended / cancelled). */
    fun clear() {
        if (state.route == null && !state.navigating) return
        update(State(null, "", false))
    }

    private fun update(next: State) {
        if (!next.navigating) {
            com.overdrive.app.navmap.cluster.ClusterGuidanceClient.clear("roadsense")
        }
        if (next.route == null || traffic?.route !== next.route) traffic = null
        state = next
        for (l in listeners) {
            try { l.onNavStateChanged(next) } catch (_: Throwable) {}
        }
    }

    /** Latest live-traffic refresh for the active route; separate from [State] so a refresh never re-arms guidance. */
    data class Traffic(
        val route: NavRoute,
        val spans: List<CongestionSpan>,
        val remainingEtaSeconds: Double,
        /** SystemClock.elapsedRealtime() of the refresh, so a late subscriber can age the ETA. */
        val atMs: Long
    )

    fun interface TrafficListener {
        fun onTrafficChanged(traffic: Traffic)
    }

    @Volatile
    var traffic: Traffic? = null
        private set

    private val trafficListeners = java.util.concurrent.CopyOnWriteArrayList<TrafficListener>()

    fun addTrafficListener(l: TrafficListener): TrafficListener {
        trafficListeners.add(l)
        traffic?.let { t -> try { l.onTrafficChanged(t) } catch (_: Throwable) {} }
        return l
    }

    fun removeTrafficListener(l: TrafficListener?) { if (l != null) trafficListeners.remove(l) }

    fun publishTraffic(route: NavRoute, spans: List<CongestionSpan>, remainingEtaSeconds: Double) {
        val next = Traffic(route, spans, remainingEtaSeconds, android.os.SystemClock.elapsedRealtime())
        traffic = next
        for (l in trafficListeners) {
            try { l.onTrafficChanged(next) } catch (_: Throwable) {}
        }
    }
}
