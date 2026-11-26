package com.overdrive.app.analytics

import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.util.RotatingAnonymousId
import org.json.JSONObject

/**
 * Rotating anonymous INSTALL identity for the privacy-preserving DAU/MAU counter
 * (analytics-edge). A deliberate SIBLING of, and separate from,
 * [com.overdrive.app.roadsense.sync.DeviceId]:
 *
 *   - Same construction (crypto-random UUID v4, stored in UnifiedConfig so daemon
 *     and app agree, rotated every [ROTATE_DAYS] as privacy hygiene), so it is
 *     NEVER the BYD device id / IMEI / serial / MAC / VIN or anything derivable
 *     from them — no PII, no device data.
 *   - But a DISTINCT id in its own UCM key. Reusing the RoadSense id would let the
 *     analytics dataset and the RoadSense/Community datasets be joined server-side
 *     (an install that pinged "active" on day X is the same UUID that reported a
 *     pothole). Minting a separate id keeps the two purposes unlinkable, matching
 *     the rotation ethos. This is the ONLY reason it isn't `DeviceId`.
 *
 * The id is used ONLY to COUNT DISTINCT installs per day server-side — never to
 * identify a person or reconstruct anything.
 *
 * ## Rotation period vs the MAU window (accuracy)
 * Rotation is [ROTATE_DAYS] = 180, deliberately MUCH LONGER than the server's
 * 30-day MAU window. If the two were equal (e.g. both 30), a continuously-active
 * install would cross a rotation boundary inside almost every trailing-30-day
 * window and be counted as TWO distinct ids → ~2× MAU inflation. At 180 vs 30 the
 * expected inflation is ≈ 1 + 30/180 ≈ +17%, the small residual cost of keeping
 * ids unlinkable. (DAU is unaffected — one id per single day regardless.)
 *
 * Daemon-side use. First call mints + persists; later calls reuse until rotation.
 */
object AnalyticsId {

    private const val SECTION = "analytics"
    private const val KEY_ID = "installId"
    private const val KEY_MINTED = "installIdMintedMs"
    // MUCH longer than the 30-day MAU window (see class docs) to keep MAU
    // over-count small while still rotating for privacy hygiene.
    private const val ROTATE_DAYS = 180L
    private const val ROTATE_MS = ROTATE_DAYS * 24L * 60L * 60L * 1000L

    private val identity = RotatingAnonymousId(SECTION, KEY_ID, KEY_MINTED, ROTATE_MS)

    /**
     * Current anonymous install id, minting or rotating as needed. [nowMs] injected
     * for testability. Returns a stable id within a rotation window.
     *
     * [preloadedSection] lets a caller that already read the `analytics` UCM section
     * (e.g. [AnalyticsPinger.maybePing]) pass it in to avoid a second
     * [UnifiedConfigManager.forceReload] disk/cross-UID read on the same tick. When
     * null we load it ourselves.
     */
    fun current(nowMs: Long, preloadedSection: JSONObject? = null): String =
        identity.current(nowMs, preloadedSection)
}
