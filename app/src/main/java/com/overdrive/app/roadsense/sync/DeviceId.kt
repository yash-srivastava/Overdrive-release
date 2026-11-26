package com.overdrive.app.roadsense.sync

import com.overdrive.app.util.RotatingAnonymousId

/**
 * Rotating anonymous device identity for crowdsource uploads (R-CRD-7, D-009).
 *
 * The id is ONLY used server-side for consensus distinct-device counting — never
 * to reconstruct a track or identify a person. It is:
 *   - cryptographically random (UUID v4),
 *   - stored in the `roadSense` UCM section (`deviceId`), NOT Android Keystore
 *     (that's for crypto keys) and NOT EncryptedSharedPreferences (per-UID, splits
 *     the daemon vs app — see feedback_credentials_unified_pattern). UCM is
 *     file-backed so daemon + app see the same id,
 *   - **rotated** periodically (every [ROTATE_DAYS]) so uploads can't be linked
 *     across long spans into a movement profile. Rotation is privacy hygiene; it
 *     does NOT hurt consensus because confirmation happens within R/T (days), far
 *     shorter than the rotation window.
 *
 * Daemon-side use. First call mints + persists; later calls reuse until rotation.
 */
object DeviceId {

    private const val SECTION = "roadSense"
    private const val KEY_ID = "deviceId"
    private const val KEY_MINTED = "deviceIdMintedMs"
    private const val ROTATE_DAYS = 30L
    private const val ROTATE_MS = ROTATE_DAYS * 24L * 60L * 60L * 1000L

    private val identity = RotatingAnonymousId(SECTION, KEY_ID, KEY_MINTED, ROTATE_MS)

    /**
     * Current anonymous id, minting or rotating as needed. [nowMs] injected for
     * testability. Returns a stable id within a rotation window.
     */
    fun current(nowMs: Long): String = identity.current(nowMs)
}
