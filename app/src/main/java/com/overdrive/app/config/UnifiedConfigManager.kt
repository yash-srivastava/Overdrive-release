package com.overdrive.app.config

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * SOTA Unified Configuration Manager
 * 
 * Solves the UID permission problem by using a world-accessible location
 * that both the app (via IPC) and shell daemon can read/write.
 * 
 * Architecture:
 * - Single JSON file at /data/local/tmp/overdrive_config.json
 * - App UI writes via IPC to daemon (daemon has shell UID 2000)
 * - Web UI/daemon writes directly (already has shell UID 2000)
 * - Both read from the same file
 * - Change listeners for real-time sync
 * 
 * Config sections:
 * - surveillance: Detection settings (minObjectSize, flashImmunity, etc.)
 * - recording: Recording settings (bitrate, codec, pre/post buffer)
 * - streaming: Streaming quality settings
 * - telegram: Telegram bot settings
 */
object UnifiedConfigManager {
    private const val TAG = "UnifiedConfig"
    
    // Single source of truth - world-readable location
    private const val CONFIG_PATH = "/data/local/tmp/overdrive_config.json"

    // ==================== DOUBLE-BUFFERED, SEQ-STAMPED DURABILITY ====================
    //
    // The core OTA config-loss defense. The config lives in sticky
    // /data/local/tmp, where ONLY the daemon (UID 2000) can create the sibling
    // needed for an atomic tmp+rename. App-UID writes are routed to that daemon;
    // when it is unavailable, the mutation fails with the live file untouched
    // instead of falling back to a truncate-rewrite that `pm install -r` could
    // tear. Defenses, layered:
    //
    //  1. STICKY .bak  — daemon-maintained last-known-good in the shared dir.
    //                    World-readable so any process can recover from it.
    //  2. APP-PRIVATE .bak — a SECOND last-known-good the APP writes ATOMICALLY
    //                    in its own data dir (it CAN create siblings + rename
    //                    there), including after confirmed daemon IPC writes.
    //                    The daemon can't read this dir (0700 app-owned), which
    //                    is fine: the app heals itself from it, and seq-promotion
    //                    repairs the case where the daemon healed the live file
    //                    from an older sticky .bak first.
    //  3. configSeq — a MONOTONIC counter embedded in the config and bumped on
    //                    every saveConfig under the cross-process lock. Recovery
    //                    and load-time promotion pick the copy with the HIGHEST
    //                    seq, so the system can NEVER silently revert to an older
    //                    snapshot — even with ext4 second-granular mtime or a
    //                    skewed RTC (BYD head units boot with a wrong clock).
    //
    // Hardcoded app-private path (UCM is a Context-free singleton object). Must
    // match the application's package — Context.filesDir resolves here.
    private const val APP_PACKAGE = "com.overdrive.app"
    private const val APP_PRIVATE_DIR = "/data/data/$APP_PACKAGE/files"
    private const val APP_PRIVATE_BAK_PATH = "$APP_PRIVATE_DIR/overdrive_config.json.bak"
    private const val APP_PRIVATE_BAK_LOCK_PATH = "$APP_PRIVATE_BAK_PATH.lock"
    private const val DIRECTORY_SYNC_ATTEMPTS = 3
    // Monotonic write sequence. Absent (legacy configs) reads as 0; saveConfig
    // bumps it. Stripped from backup bundles (see ConfigBackupService) so an
    // imported bundle never injects a foreign seq.
    private const val SEQ_KEY = "configSeq"
    // Internal marker carried through the existing UPDATE_SECTION IPC command.
    // It is removed before persistence; normal callers never observe it.
    private const val REPLACE_SECTION_MARKER = "__overdriveReplaceSection"
    private const val MUTATION_ORIGIN_MARKER = "__overdriveMutationOrigin"
    private const val MUTATION_SEQUENCE_MARKER = "__overdriveMutationSequence"
    private const val MUTATION_CLOCKS_KEY = "__overdriveMutationClocks"
    private val processMutationOrigin = UUID.randomUUID().toString()
    private val processMutationSequence = AtomicLong(0)

    // Daemon IPC: the app process (UID >= 10000) cannot create the .tmp sibling
    // needed for an atomic write in sticky /data/local/tmp/. App-process writes are
    // forwarded to the daemon (UID 2000, which CAN atomic-rename) over the
    // existing SurveillanceIpcServer localhost socket; the daemon applies them
    // in-process via the atomic path. If routing is unavailable and a local
    // atomic sibling cannot be created, persistence returns false without
    // modifying the destination. See routeWriteIfApp().
    private const val DAEMON_IPC_HOST = "127.0.0.1"
    private const val DAEMON_IPC_PORT = 19877
    // The daemons (CameraDaemon, AccSentryDaemon, TelegramBotDaemon) are all
    // spawned via app_process and run as shell UID 2000 — they own the sticky
    // /data/local/tmp/ files and CAN atomic-rename, so they write locally.
    // ANY other UID (app 10xxx, or a future system-1000 writer) cannot reliably
    // create the .tmp sibling and so reroutes to the daemon. Gating on "== 2000"
    // (can-atomic-write) rather than "< 10000" deliberately routes a non-shell
    // privileged writer to the safe path instead of letting it truncate.
    private const val SHELL_DAEMON_UID = 2000
    // Localhost round-trip budget. Connect must fail fast when the daemon is
    // down (the update window) so the caller falls through to the guarded local
    // write without a long stall; read timeout covers the daemon's full-JSON
    // rewrite under CONFIG_LOCK contention.
    private const val IPC_CONNECT_TIMEOUT_MS = 1500
    private const val IPC_READ_TIMEOUT_MS = 5000
    private const val IPC_RECONCILE_ATTEMPTS = 4
    private const val IPC_RECONCILE_DELAY_MS = 75L
    private const val CACHE_REVALIDATE_MS = 1000L
    
    // Legacy paths for migration
    private const val LEGACY_SENTRY_CONFIG = "/data/local/tmp/sentry_config.json"
    private const val LEGACY_CAMERA_SETTINGS = "/data/local/tmp/camera_settings.json"
    private const val LEGACY_SYSTEM_CONFIG = "/data/data/com.android.providers.settings/sentry_config.json"
    private const val ROOT_PROMOTION_SECTION = "__overdriveRootPromotion"
    private const val ROOT_PROMOTION_MARKER = "__overdrivePromoteRoot"
    
    // In-memory cache
    @Volatile
    private var cachedConfig: JSONObject? = null
    private val lastModified = AtomicLong(0)
    private val lastFreshnessCheckNanos = AtomicLong(0)
    @Volatile
    private var pendingRootPromotion: JSONObject? = null
    private val appPrivateBackupMonitor = Any()
    private val appPrivateBackupTempSequence = AtomicLong(0)
    /**
     * Record the freshness key (the file mtime) for the bytes now cached. Call
     * everywhere the cache is (re)populated. 0 = "force re-read next time".
     */
    private fun stampFreshness(mtime: Long) {
        lastModified.set(mtime)
        lastFreshnessCheckNanos.set(
            if (mtime > 0L) System.nanoTime() else 0L
        )
    }

    /**
     * True iff the cache is still valid for [configFile].
     *
     * ext4 mtime is second-granular, so a cross-UID write committing in the
     * SAME wall-clock second as our cached snapshot yields an EQUAL mtime — the
     * old `fileModified <= lastModified` fast-path then served a stale config to
     * bare-loadConfig getters (getSurveillance/getRecording/…). Pairing mtime
     * with file SIZE was insufficient too: a pretty-printed same-length edit
     * (targetFps 15→30, a double 1.66→1.77, an enum of equal length) keeps the
     * byte count identical and slips through.
     *
     * Serve the cache only when the on-disk mtime is unchanged, the wall clock
     * has advanced past that second, and the monotonic revalidation TTL has not
     * expired. Any other case re-reads:
     *  - mtime advanced               → a newer write, reparse.
     *  - mtime LOWER than our stamp    → the RTC stepped backward (a documented
     *                                    BYD head-unit condition the rest of this
     *                                    file already distrusts) or a recovery
     *                                    wrote an older-mtime file; the content
     *                                    may be newer, so reparse rather than
     *                                    trust the clock.
     *  - mtime equal, still IN that    → a same-second write could share this
     *    second                          mtime; reparse to be safe.
     * The TTL bounds stale exposure even on filesystems whose timestamp
     * granularity is coarser than one second. The monotonic configSeq remains
     * the authoritative ordering signal for recovery/promotion.
     */
    private fun isCacheFresh(configFile: File): Boolean {
        val stamped = lastModified.get()
        if (stamped <= 0L) return false                          // sentinel → always re-read
        if (configFile.lastModified() != stamped) return false   // any mismatch → re-read
        // The wall-clock check protects same-second rewrites on ext4. The
        // monotonic TTL also forces a reparse on filesystems with coarser
        // timestamps (or external writers that preserve mtime), so equality can
        // never pin stale bytes indefinitely.
        if ((System.currentTimeMillis() / 1000L) <= (stamped / 1000L)) {
            return false
        }
        val checkedAt = lastFreshnessCheckNanos.get()
        if (checkedAt <= 0L) return false
        val elapsed = System.nanoTime() - checkedAt
        return elapsed >= 0L &&
            elapsed < java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(CACHE_REVALIDATE_MS)
    }

    // Raised when loadConfig() finds a NON-EMPTY but unparseable file on disk
    // (corruption from a legacy build or an external writer). While set, saveConfig()
    // refuses to overwrite the live file with defaults, so a transient
    // corruption can't cascade into a PERMANENT total settings wipe: the next
    // updateSection() would otherwise merge into a defaults-only in-memory
    // config and persist it over the user's real (recoverable) settings.
    // Cleared by a successful load or forceReload().
    @Volatile
    private var corruptionDetected = false

    // One-shot log latch for the tripAnalytics.enabled default migration.
    // applyDefaults is idempotent and re-runs on every reparse in the app UID
    // (persistMigrationUnderLock is daemon-only), so without this the migration
    // line would repeat on every config reload in that process.
    @Volatile
    private var tripEnabledMigrationLogged = false

    // Change listeners
    private val listeners = CopyOnWriteArrayList<ConfigChangeListener>()

    private enum class ConfigWriteState {
        NOT_COMMITTED,
        COMMITTED_DURABLE,
        COMMITTED_UNCERTAIN
    }

    /**
     * A rename is the commit point for the running system. Parent-directory
     * fsync only determines whether that commit is confirmed across sudden
     * power loss, so a post-rename sync failure must never be reported as an
     * ordinary rejected write.
     */
    private data class ConfigWriteResult(
        val state: ConfigWriteState,
        val committedConfig: JSONObject? = null
    ) {
        val committed: Boolean
            get() = state != ConfigWriteState.NOT_COMMITTED
    }
    
    interface ConfigChangeListener {
        fun onConfigChanged(section: String, config: JSONObject)
    }
    
    /**
     * Initialize and migrate from legacy configs if needed.
     */
    @JvmStatic
    fun init() {
        // Provision the cross-process lock file here. init() runs in the daemon
        // (UID 2000), which CAN create files in sticky /data/local/tmp; once it
        // exists 0666, the app UID (which canNOT create there) can still OPEN it
        // to acquire the lock. Without this, an app-UID writer in the daemon-down
        // window cannot create the lock itself. Best-effort here: if provisioning
        // is unavailable, app-side local mutation is explicitly deferred rather
        // than running without cross-process exclusion.
        provisionLockFile()
        // A config restore can span the config file and the credential DID file.
        // Recover its write-ahead journal before any daemon consumer can read
        // encrypted credentials from the unified config.
        withConfigFileLock {
            ConfigBackupService.recoverInterruptedRestoreUnderConfigLock()
        }

        val configFile = File(CONFIG_PATH)

        if (!configFile.exists()) {
            Log.i(TAG, "Unified config not found, migrating from legacy configs...")
            migrateFromLegacy()
        } else {
            // SOTA: Always re-assert 666 if we're the daemon. If the app
            // process wrote the file during the update window, it might
            // have different permissions than we expect on some builds (DiLink 3).
            if (android.os.Process.myUid() == SHELL_DAEMON_UID) {
                try {
                    configFile.setReadable(true, false)
                    configFile.setWritable(true, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-assert permissions: ${e.message}")
                }
            }
            Log.i(TAG, "Unified config exists at $CONFIG_PATH")
            loadConfig()
        }
    }

    /** Create the lock file 0666 (daemon UID) so the app UID can later open it. */
    private fun provisionLockFile() {
        try {
            val lf = File(LOCK_PATH)
            if (!lf.exists()) {
                lf.parentFile?.mkdirs()
                lf.createNewFile()
            }
            lf.setReadable(true, false)
            lf.setWritable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Lock file provisioning skipped: ${e.message}")
        }
    }
    
    /**
     * Migrate from legacy config files to unified config.
     */
    private fun migrateFromLegacy() {
        val unified = JSONObject()
        
        // Initialize sections
        unified.put("surveillance", JSONObject())
        unified.put("recording", JSONObject())
        unified.put("streaming", JSONObject())
        unified.put("telegram", JSONObject())
        unified.put("camera", JSONObject())
        unified.put("proximityGuard", JSONObject())
        unified.put("telemetryOverlay", JSONObject())
        unified.put("tripAnalytics", JSONObject())
        unified.put("oemDashcam", JSONObject())
        unified.put("keymap", JSONObject())
        unified.put("automation", JSONObject())
        unified.put("version", 1)
        unified.put("lastModified", System.currentTimeMillis())
        
        // Try to migrate from legacy sentry config
        try {
            val legacySentry = File(LEGACY_SENTRY_CONFIG)
            if (legacySentry.exists()) {
                val legacy = JSONObject(legacySentry.readText())
                val surveillance = unified.getJSONObject("surveillance")
                
                // Copy surveillance settings
                copyIfExists(legacy, surveillance, "blockSize")
                copyIfExists(legacy, surveillance, "requiredBlocks")
                copyIfExists(legacy, surveillance, "sensitivity")
                copyIfExists(legacy, surveillance, "flashImmunity")
                copyIfExists(legacy, surveillance, "temporalFrames")
                copyIfExists(legacy, surveillance, "useChroma")
                copyIfExists(legacy, surveillance, "minDistanceM")
                copyIfExists(legacy, surveillance, "maxDistanceM")
                copyIfExists(legacy, surveillance, "cameraHeightM")
                copyIfExists(legacy, surveillance, "cameraTiltDeg")
                copyIfExists(legacy, surveillance, "verticalFovDeg")
                copyIfExists(legacy, surveillance, "aiConfidence")
                copyIfExists(legacy, surveillance, "minObjectSize")
                copyIfExists(legacy, surveillance, "detectPerson")
                copyIfExists(legacy, surveillance, "detectCar")
                copyIfExists(legacy, surveillance, "detectBike")
                copyIfExists(legacy, surveillance, "detectAnimal")
                copyIfExists(legacy, surveillance, "preRecordSeconds")
                copyIfExists(legacy, surveillance, "postRecordSeconds")
                
                Log.i(TAG, "Migrated surveillance settings from $LEGACY_SENTRY_CONFIG")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from legacy sentry config: ${e.message}")
        }
        
        // Try to migrate from legacy camera settings
        try {
            val legacyCamera = File(LEGACY_CAMERA_SETTINGS)
            if (legacyCamera.exists()) {
                val legacy = JSONObject(legacyCamera.readText())
                val recording = unified.getJSONObject("recording")
                val streaming = unified.getJSONObject("streaming")
                
                // Copy recording settings
                copyIfExists(legacy, recording, "recordingBitrate", "bitrate")
                copyIfExists(legacy, recording, "recordingCodec", "codec")
                copyIfExists(legacy, recording, "recordingQuality", "quality")
                
                // Copy streaming settings
                copyIfExists(legacy, streaming, "streamingQuality", "quality")
                
                Log.i(TAG, "Migrated recording/streaming settings from $LEGACY_CAMERA_SETTINGS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from legacy camera settings: ${e.message}")
        }
        
        // Try system config as fallback
        try {
            val systemConfig = File(LEGACY_SYSTEM_CONFIG)
            if (systemConfig.exists()) {
                val legacy = JSONObject(systemConfig.readText())
                val surveillance = unified.getJSONObject("surveillance")
                
                // Only copy if not already set
                if (!surveillance.has("minObjectSize")) {
                    copyIfExists(legacy, surveillance, "minObjectSize")
                }
                if (!surveillance.has("flashImmunity")) {
                    copyIfExists(legacy, surveillance, "flashImmunity")
                }
                
                Log.i(TAG, "Migrated additional settings from $LEGACY_SYSTEM_CONFIG")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from system config: ${e.message}")
        }
        
        // Apply defaults for missing values
        applyDefaults(unified)

        // Persist cross-process-safely. This is the LAST write path that used
        // to bypass the file lock: two daemon JVMs cold-starting before the
        // file exists would both migrate-and-rename on the shared ${name}.tmp,
        // and a peer's already-committed section could be clobbered with our
        // defaults-only object. Acquire the lock and RE-READ under it: if the
        // file now exists (a peer migrated/wrote first), fold our defaults into
        // THOSE bytes for genuinely-absent keys only (applyDefaults is
        // idempotent) and keep the peer's sections; only write `unified` when
        // the file is still absent.
        val migrationPersisted = withConfigFileLockOrNull(
            "Legacy config migration"
        ) {
            val cf = File(CONFIG_PATH)
            val toPersist: JSONObject? = if (cf.exists() && cf.length() > 0L) {
                try {
                    val onDisk = JSONObject(readLiveConfigText(cf))
                    applyDefaults(onDisk)   // fill only absent keys; preserves peer sections
                    onDisk
                } catch (e: ConfigReadUnavailableException) {
                    Log.w(TAG, "Migration re-read unavailable; deferring without overwrite")
                    null
                } catch (e: Exception) {
                    // On-disk bytes unparseable (mid-write/corrupt) — fall back
                    // to our freshly-migrated object rather than crash.
                    Log.w(TAG, "Migration re-read failed (${e.message}); writing migrated object")
                    unified
                }
            } else {
                unified
            }
            if (toPersist == null) {
                return@withConfigFileLockOrNull false
            }
            // Stamp the monotonic sequence on the very first persist so the
            // backups seeded below carry a seq from creation (recovery /
            // promotion can then order them). max(onDisk, mem)+1 keeps it
            // monotonic if a peer already wrote a seq we re-read above.
            toPersist.put(SEQ_KEY, nextConfigSeq(toPersist))
            val writeResult = saveConfigInternal(toPersist)
            if (writeResult.committed) {
                val committed = writeResult.committedConfig ?: toPersist
                cachedConfig = committed
                stampFreshness(File(CONFIG_PATH).lastModified())
                // Seed the last-known-good copies AT CREATION so there is never
                // a "live file exists but no .bak" window for an app-UID torn
                // write to fall into. The daemon (which runs init/migrate) can
                // atomic-rename the sticky .bak; the app-private .bak is a no-op
                // here (daemon UID) and gets seeded on the app's first save.
                writeBackupCopy(committed)
                writeAppPrivateBackup(committed)
            } else {
                cachedConfig = toPersist
                // Persist failed (e.g. app UID, tmp-create denied). Force the
                // next loadConfig to re-read rather than serve this unstamped
                // in-memory object — explicit intent, not reliance on the
                // still-initial sentinel.
                stampFreshness(0)
            }
            writeResult.committed
        } ?: false

        if (migrationPersisted) {
            Log.i(TAG, "Migration complete. Unified config saved to $CONFIG_PATH")
        } else {
            Log.w(TAG, "Migration persistence deferred until the stable config lock is available")
        }
    }
    
    private fun copyIfExists(from: JSONObject, to: JSONObject, key: String, newKey: String = key) {
        if (from.has(key)) {
            to.put(newKey, from.get(key))
        }
    }
    
    /**
     * Public entry to [applyDefaults] for callers that build a config object
     * outside the normal load path (e.g. ConfigBackupService overlaying a
     * restored bundle) and need every section backfilled with current-app
     * defaults before persisting. Idempotent — only fills absent keys.
     */
    @JvmStatic
    fun ensureDefaults(config: JSONObject) = applyDefaults(config)

    /**
     * Run [body] while holding the SAME cross-process advisory lock that
     * [updateSection]/[updateValues] use, so a bulk read-modify-write done
     * outside this object (ConfigBackupService.applyBundle restoring a backup)
     * can't be interleaved by a peer daemon JVM's section write (stale-snapshot
     * lost update). Inside [body], call [forceReload] to get a fresh under-lock
     * read, then [saveConfig]; the lock is reentrant per-thread so saveConfig's
     * own paths nest harmlessly.
     */
    @JvmStatic
    fun <T> runUnderConfigLock(body: () -> T): T = withConfigFileLock(body)

    /**
     * Return the exact durable root parsed while holding the stable cross-process
     * lock. This path never consults the cache, applies defaults, promotes a
     * backup, or repairs the live file.
     */
    @JvmStatic
    fun readDurableConfigStrict(): JSONObject = withConfigFileLock {
        readDurableConfigLockedStrict()
    }

    /**
     * Restore is allowed to replace a genuinely absent or malformed root, but
     * an I/O or lock failure remains transient and must abort the operation.
     */
    @JvmStatic
    fun readDurableConfigForRestore(): JSONObject = withConfigFileLock {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return@withConfigFileLock createDefaultConfig()
        val encoded = readLiveConfigText(file)
        if (encoded.isBlank()) return@withConfigFileLock createDefaultConfig()
        try {
            JSONObject(encoded)
        } catch (malformed: Exception) {
            Log.w(TAG, "Restore is replacing malformed durable config: " +
                (malformed.message ?: malformed.javaClass.simpleName))
            createDefaultConfig()
        }
    }

    private fun applyDefaults(config: JSONObject) {
        // optJSONObject (not getJSONObject) for these three: a partially-formed
        // config missing any of them must NOT throw here. applyDefaults runs
        // inside loadConfig()'s try, whose catch falls back to
        // createDefaultConfig() — so a throw on one absent section would
        // silently reset EVERY section to defaults. Seed an empty object
        // instead and let the per-key fills below populate it.
        val surveillance = config.optJSONObject("surveillance") ?: JSONObject().also {
            config.put("surveillance", it)
        }
        val recording = config.optJSONObject("recording") ?: JSONObject().also {
            config.put("recording", it)
        }
        val streaming = config.optJSONObject("streaming") ?: JSONObject().also {
            config.put("streaming", it)
        }
        val camera = config.optJSONObject("camera") ?: JSONObject().also {
            config.put("camera", it)
        }
        val proximityGuard = config.optJSONObject("proximityGuard") ?: JSONObject().also {
            config.put("proximityGuard", it)
        }
        val blindspot = config.optJSONObject("blindspot") ?: JSONObject().also {
            config.put("blindspot", it)
        }
        // Camera-view section: an on-demand camera view (front/rear/left/right/all-4)
        // shown on the SAME native SurfaceControl lane the blind-spot feature uses,
        // arbitrated at render time with blind-spot priority. No default keys force
        // behavior (enabled defaults false, so a config without this section is
        // byte-identical to the shipping path); just guarantee the section exists so
        // per-key merges never miss the whole object. Keys (all optional):
        //   enabled(bool), mode(int 0=all/1=front/2=right/3=rear/4=left,
        //     7=rear+side left / 8=rear+side right — the blind-spot composite, which
        //     shares the blindspot.* merge/fisheye/rotation settings),
        //   target("head_unit"|"cluster"), geometry{sizePct,corner | x,y,w,h},
        //   geometryCluster{...}, autoHideSec(int; 0=until explicitly hidden).
        config.optJSONObject("camview") ?: JSONObject().also {
            config.put("camview", it)
        }
        // Power / battery-safety section. Holds the HV-SoC surveillance cutoff
        // read by SocCutoffMonitor.cutoffPercent() (key power.lowSocCutoffPercent).
        val power = config.optJSONObject("power") ?: JSONObject().also {
            config.put("power", it)
        }
        // Ensure the telegram section exists (createDefaultConfig seeds it, but
        // applyDefaults must also work on a partial config built elsewhere — e.g.
        // a restored backup whose telegram section was skipped on a key
        // mismatch). No default keys to seed (token/owner are user-set); just
        // guarantee the empty object is present so the persisted config is
        // never missing the whole section. Same for telegram as for bydCloud/
        // navMap below.
        config.optJSONObject("telegram") ?: JSONObject().also {
            config.put("telegram", it)
        }

        // Key-mapping section: {enabled, allowAdvanced, bindings:[...]}. Bindings
        // are user-authored (keycode + pressType + action), so there are no
        // default keys to seed — just guarantee the section object exists so a
        // partial/restored config is never missing it (same rationale as
        // telegram above). enabled/allowAdvanced default false at read time.
        config.optJSONObject("keymap") ?: JSONObject().also {
            config.put("keymap", it)
        }

        // Surveillance defaults
        if (!surveillance.has("minObjectSize")) surveillance.put("minObjectSize", 0.08)
        if (!surveillance.has("aiConfidence")) surveillance.put("aiConfidence", 0.25)
        if (!surveillance.has("flashImmunity")) surveillance.put("flashImmunity", 2)
        if (!surveillance.has("detectPerson")) surveillance.put("detectPerson", true)
        if (!surveillance.has("detectCar")) surveillance.put("detectCar", true)
        if (!surveillance.has("detectBike")) surveillance.put("detectBike", false)
        if (!surveillance.has("detectAnimal")) surveillance.put("detectAnimal", false)
        if (!surveillance.has("preRecordSeconds")) surveillance.put("preRecordSeconds", 5)
        if (!surveillance.has("postRecordSeconds")) surveillance.put("postRecordSeconds", 10)
        if (!surveillance.has("blockSize")) surveillance.put("blockSize", 32)
        if (!surveillance.has("requiredBlocks")) surveillance.put("requiredBlocks", 3)
        if (!surveillance.has("sensitivity")) surveillance.put("sensitivity", 0.04)
        if (!surveillance.has("surveillanceEnabled")) surveillance.put("surveillanceEnabled", false)
        // ACC-OFF mode: "smart" runs the existing motion + YOLO event pipeline;
        // "continuous" records a plain rolling 4-cam mosaic with no filters and
        // no AI. Branched at SurveillanceEngineGpu.enable(). Default smart so
        // behaviour matches the prior single-mode build.
        if (!surveillance.has("accOffMode")) surveillance.put("accOffMode", "smart")
        // Arm mode: WHEN surveillance arms after the car powers off.
        //   "lock"  — arm when the doors lock, disarm when they unlock (avoids
        //             recording the owner). Because most trims here can't read
        //             lock state reliably (cloud rarely fires lock events, OTA
        //             exposes only the LF door, the legacy device path returns
        //             INVALID on every field firmware), lock mode ALSO force-arms
        //             at DOOR_LOCK_ARM_TIMEOUT_MS (60s) when lock state stayed
        //             unreadable — otherwise it would never arm on those trims.
        //   "power" — arm immediately on ACC-off, disarm on ACC-on. No lock gate.
        //             Deterministic; works on every trim.
        // Branched in CameraDaemon's ACC-off dispatch + door-lock gate. Both modes
        // still honor the safe-zone and schedule suppression gates. Default "power"
        // for autonomous zero-cloud sentry activation.
        if (!surveillance.has("armMode")) surveillance.put("armMode", "power")
        // Keep ONLY the USB/data rail powered after ACC OFF (e.g. to charge a phone
        // while parked). DEFAULT TRUE so out-of-box behaviour is unchanged; user
        // opt-out (Surveillance → General) lets just that rail sleep on the next
        // ACC-OFF cycle to save the 12 V battery. Does NOT affect the cameras — the
        // camera/AVM/ISP power keep-alives are unconditional in AccSentryDaemon.
        if (!surveillance.has("keepUsbPowerOnAccOff")) surveillance.put("keepUsbPowerOnAccOff", true)
        // Parked cellular keep-alive. DEFAULT FALSE (opt-in): only needed on models
        // whose data module sleeps a while after ACC OFF; elsewhere holding the bearer
        // up just costs battery and data. Read by AccSentryDaemon's keep-alive loop.
        if (!surveillance.has("mobileDataKeepAlive")) surveillance.put("mobileDataKeepAlive", false)
        // Operating mode: WHICH lifecycle phases OverDrive is active for.
        //   "onAndOff" — full current behaviour: after the vehicle powers off the
        //                daemon keeps the head unit awake (MCU/USB/AP wake, keep-alive
        //                loop, voltage/SoC monitors) AND runs post-OFF surveillance /
        //                sentry. This is the DEFAULT so out-of-box behaviour and every
        //                existing/absent config is byte-identical to the prior build.
        //   "onOnly"   — everything that runs AFTER vehicle-OFF is disabled: no
        //                keep-awake, no post-OFF surveillance/recording, revival alarms
        //                stood down — the head unit is allowed to sleep normally when the
        //                car is off. Everything that runs WHILE the vehicle is ON
        //                (ACC-on dashcam, trips, charging analytics, live telemetry)
        //                keeps working. Gated at each post-OFF choke point via
        //                isVehicleOnOnlyMode(). Read fresh (mtime-gated) so a change
        //                applies on the next ACC cycle without a daemon restart.
        if (!surveillance.has("operatingMode")) surveillance.put("operatingMode", "onAndOff")
        if (!surveillance.has("deterrentAction")) surveillance.put("deterrentAction", "silent")
        if (!surveillance.has("deterrentCooldownSeconds")) surveillance.put("deterrentCooldownSeconds", 15)
        if (!surveillance.has("screenDeterrentEnabled")) surveillance.put("screenDeterrentEnabled", false)
        if (!surveillance.has("screenDeterrentDurationSeconds")) surveillance.put("screenDeterrentDurationSeconds", 8)
        if (!surveillance.has("screenDeterrentImagePath")) surveillance.put("screenDeterrentImagePath", "")
        if (!surveillance.has("screenDeterrentMessage")) surveillance.put("screenDeterrentMessage", "")

        // HV traction-battery SoC cutoff for parked surveillance. At or below
        // this %, SocCutoffMonitor arms a 60 s grace then self-shuts-down to
        // protect the pack. User-tunable from Surveillance → General (slider
        // 0..30). 0 = Off: SocCutoffMonitor.onElecPercentageChanged() early-
        // returns on pct<=0 BEFORE the cutoff compare, so a 0 cutoff can never
        // arm. Default 10 matches SocCutoffMonitor.DEFAULT_CUTOFF_PERCENT.
        if (!power.has("lowSocCutoffPercent")) power.put("lowSocCutoffPercent", 10)

        // Recording defaults. The canonical key is `recordingQuality` (ECONOMY..MAX).
        // `quality` is the legacy mirror; `bitrate` (LOW/MEDIUM/HIGH) is no longer
        // seeded — it would drift from the active tier and confuse cross-channel
        // readers. Keep it only if a user actually has it from a pre-migration install.
        if (!recording.has("mode")) recording.put("mode", "NONE")  // Default: no recording
        if (!recording.has("recordingQuality")) recording.put("recordingQuality", "STANDARD")
        if (!recording.has("quality")) recording.put("quality", recording.optString("recordingQuality", "STANDARD"))
        if (!recording.has("codec")) recording.put("codec", "H264")
        // Surveillance (ACC-off / parked sentry) quality tier. Independent of the
        // ACC-on recordingQuality above so parked footage can use a different
        // bitrate (e.g. thriftier while parked all day, or higher for evidence).
        //
        // DELIBERATELY NOT SEEDED. It resolves via read-time fallback in each
        // consumer instead: when this key is absent, the pano pipeline falls
        // back to recording.recordingQuality and the OEM dashcam falls back to
        // its own oemDashcam.recordingQuality → recording.recordingQuality
        // chain. Physically seeding it (e.g. from recording.recordingQuality)
        // would REGRESS the OEM axis whenever oemDashcam.recordingQuality
        // diverges from the pano tier — the seeded pano value would override
        // the OEM's own slot. Absence = byte-identical to the pre-split world
        // for BOTH pipelines; presence = user explicitly chose a surveillance
        // tier. Both flows resolve bitrate against the SHARED `codec` key.
        // Recording-side dewarp strength (Fitzgibbon division model). 0..100,
        // 0 = off (default). Single source of truth for both ACC-on dashcam
        // and ACC-off surveillance flows — both pipelines read the same key
        // so one slider in the UI applies everywhere. Only effective on the
        // legacy 4-strip layout; dilink4 cars get a clean 2x2 from the HAL
        // already and the recorder's shader gates the dewarp accordingly.
        if (!recording.has("rectifyStrength")) recording.put("rectifyStrength", 0)

        // Clip segment length in minutes (2/5/10). Single source of truth for
        // BOTH the ACC-on dashcam and ACC-off / OEM surveillance flows — both
        // pipelines read this one key so a single UI control applies
        // everywhere (mirrors rectifyStrength above).
        if (!recording.has("segmentDurationMinutes")) {
            recording.put("segmentDurationMinutes",
                com.overdrive.app.util.Constants.SEGMENT_DURATION_MINUTES)
        }

        // Streaming defaults
        if (!streaming.has("quality")) streaming.put("quality", "MEDIUM")

        // Camera defaults. cameraProfile=auto lets the runtime resolver infer
        // Tang vs legacy panoramic dims from ro.product.model. Existing
        // installs that only have probedCameraId continue to work unchanged.
        if (!camera.has("cameraProfile")) {
            camera.put("cameraProfile",
                com.overdrive.app.camera.CameraProfiles.PROFILE_AUTO)
        }
        if (!camera.has("targetFps"))         camera.put("targetFps", 15)
        // Surveillance (ACC-off / parked sentry) camera fps. Independent of the
        // ACC-on targetFps so parked recording can run at a lower rate to save
        // power/storage while the car sits all day.
        //
        // DELIBERATELY NOT SEEDED (see surveillanceQuality above for the full
        // rationale). Read-time fallback only: absent ⇒ the pano pipeline uses
        // camera.targetFps (15) and the OEM dashcam uses its own
        // oemDashcam.fps (30) → recording.fps chain. Seeding a single value
        // here would drop OEM parked fps from its 30 default to 15. Absence =
        // byte-identical per pipeline; presence = an explicit user choice.
        // ---- Parked-idle low-power throttle (default OFF = byte-identical) ----
        // When ARMED surveillance sits idle (no active motion), drop the shared
        // camera HAL to surveillanceIdleFps to cut capture-rail + compose/encode
        // power, then ramp back to the surveillance fps on first-motion. Detection
        // parity is preserved by holding the AI motion cadence CONSTANT at today's
        // rate (survFps/3 Hz) via a wall-clock readback gate — independent of the
        // HAL rate — so the frame-counted native pipeline sees identical cadence.
        // Master gate defaults FALSE: absent ⇒ nothing changes vs today.
        if (!camera.has("surveillanceIdleThrottle")) camera.put("surveillanceIdleThrottle", false)
        if (!camera.has("surveillanceIdleFps"))      camera.put("surveillanceIdleFps", 5)
        // Quiet tier (issue #174): once the idle throttle is active AND no motion
        // has occurred for surveillanceQuietTierMinutes, step the AI motion cadence
        // down to surveillanceQuietTierFps Hz (relaxing the idle-HAL parity floor to
        // match) so a long parked-idle tail stops paying ~10 Hz readbacks. First
        // motion restores full cadence. ON by default WHEN the throttle is on
        // (5 min / 3 Hz); minutes=0 disables the tier. Absent ⇒ these defaults.
        if (!camera.has("surveillanceQuietTierMinutes")) camera.put("surveillanceQuietTierMinutes", 5)
        if (!camera.has("surveillanceQuietTierFps"))     camera.put("surveillanceQuietTierFps", 3)
        if (!camera.has("probedCameraId"))    camera.put("probedCameraId", -1)
        if (!camera.has("probedSurfaceMode")) camera.put("probedSurfaceMode", -1)
        if (!camera.has("roleMappings"))      camera.put("roleMappings", JSONObject())

        // OEM Dashcam (separate forward sensor on vehicles that ship a DVR).
        // -1 = unset; 0..5 = picked AVMCamera id. The resolver in
        // resolveOemDashcamId() falls back to (panoId XOR 1) when the manual
        // override is false, so a typical Seal install (pano=1) auto-picks
        // dashcam=0 without the user touching anything.
        if (!camera.has("oemDashcamCameraId"))      camera.put("oemDashcamCameraId", -1)
        if (!camera.has("oemDashcamManualOverride")) camera.put("oemDashcamManualOverride", false)
        // Sticky probe result. -1 = unprobed, 0 = single-client only,
        // 1 = both AVMCamera ids deliver frames concurrently. Until probed,
        // the daemon defaults to single-pipeline operation (yield protocol
        // between pano and OEM dashcam).
        if (!camera.has("concurrentAvmSupported"))   camera.put("concurrentAvmSupported", -1)
        // OPT-IN gate for ConcurrentAvmProbe. The probe opens BOTH AVMCamera
        // ids (raw HAL open()+startPreview()) to test dual-client support —
        // a DESTRUCTIVE operation that truncates any in-flight recording on a
        // shared id. Its only real consumer is a minor OEM bitrate-budget
        // refinement (applyBitrateBudgetCap), and the unprobed default (-1)
        // already yields a safe sole-encoder full budget. So the probe is
        // OFF by default and only runs when the user explicitly opts in via
        // the camera-mapping dialog. Default false = never auto-probe.
        if (!camera.has("concurrentAvmProbeEnabled")) camera.put("concurrentAvmProbeEnabled", false)

        // Proximity Guard defaults
        if (!proximityGuard.has("enabled")) proximityGuard.put("enabled", false)
        if (!proximityGuard.has("triggerLevel")) proximityGuard.put("triggerLevel", "RED")
        if (!proximityGuard.has("preRecordSeconds")) proximityGuard.put("preRecordSeconds", 5)
        if (!proximityGuard.has("postRecordSeconds")) proximityGuard.put("postRecordSeconds", 10)

        // Blind Spot overlay defaults. `enabled` gates the indicator-triggered
        // native overlay; the 6 numerics are the dialed-in stitch calibration
        // for this car (rear+side panorama). See BlindSpotOverlayService.
        if (!blindspot.has("enabled")) blindspot.put("enabled", false)
        // Blind-spot global camera-fps profile, used when the camera is kept warm
        // ONLY for blind-spot (no recording mode owns it). BS renders to its own
        // SurfaceControl layer with NO encoder, so its sole quality/cost lever is
        // the shared camera HAL fps (live-settable via setCameraFps, no reopen).
        //   idleFps   — turn signal OFF: camera ticks slowly to keep the rails warm
        //               for an instant reveal while burning almost no GPU/encode.
        //   activeFps — turn signal ON: ramp up so the blind-spot view is smooth.
        // When a recording mode owns the camera, recording fps wins and these are
        // ignored (recording always gets full rate; see RecordingModeManager
        // .reconcileCameraProfile).
        if (!blindspot.has("idleFps")) blindspot.put("idleFps", 1)
        if (!blindspot.has("activeFps")) blindspot.put("activeFps", 15)
        // Display target: "head_unit" (default — 15.6" center screen, layerStack 0,
        // shipping behaviour) or "cluster" (driver gauge screen via OEM projection).
        if (!blindspot.has("target")) blindspot.put("target", "head_unit")
        if (!blindspot.has("rearFov")) blindspot.put("rearFov", 1.66)
        if (!blindspot.has("sideFov")) blindspot.put("sideFov", 1.98)
        if (!blindspot.has("yaw")) blindspot.put("yaw", 1.23)
        if (!blindspot.has("roll")) blindspot.put("roll", 0.25)
        if (!blindspot.has("pitch")) blindspot.put("pitch", -0.275)
        if (!blindspot.has("feather")) blindspot.put("feather", 0.38)
        // Additional opaque stitch-tuning scalars; defaults below = no change.
        if (!blindspot.has("projExp")) blindspot.put("projExp", 1.0)
        if (!blindspot.has("rearRoll")) blindspot.put("rearRoll", 0.0)
        if (!blindspot.has("rearPitch")) blindspot.put("rearPitch", 0.0)
        // Camera merge mode for the view: "both" (default — rear+side stitch),
        // "side" (side camera only), or "rear" (rear camera only). The single-camera
        // modes show one full-FOV feed instead of the merged panorama.
        if (!blindspot.has("mergeMode")) blindspot.put("mergeMode", "both")
        // On-screen card rotation. Either a fixed quarter turn (int 0/90/180/270) or
        // the string "auto". Applied by the SurfaceControl layer, and only honoured
        // for the single-camera merge modes (side/rear) — the merged panorama always
        // renders upright. 0 = shipping. In "auto" the daemon orients to direction of
        // travel: it holds "rotationBase" moving forward and flips 180° in reverse.
        if (!blindspot.has("rotation")) blindspot.put("rotation", 0)
        // Base quarter turn used as the forward-gear orientation when rotation="auto".
        if (!blindspot.has("rotationBase")) blindspot.put("rotationBase", 0)
        // PER-SIDE card rotation. The left camera (view 7, left turn) and the right
        // camera (view 8, right turn) are physically mirror-imaged, so each needs its
        // own on-screen rotation — one global angle that reads upright on the left cam
        // reads wrong on the right. rotationLeft/rotationRight are the fixed per-side
        // quarter turns (int 0/90/180/270 or the string "auto"); when rotation="auto",
        // rotationBaseLeft/rotationBaseRight are that side's forward-gear base (reverse
        // flips 180°). Defaults mirror the legacy global keys so an existing config is
        // unchanged; resolveBsRotation falls back to the global keys when these are
        // absent, so this is purely additive.
        if (!blindspot.has("rotationLeft")) blindspot.put("rotationLeft", blindspot.opt("rotation"))
        if (!blindspot.has("rotationRight")) blindspot.put("rotationRight", blindspot.opt("rotation"))
        if (!blindspot.has("rotationBaseLeft")) blindspot.put("rotationBaseLeft", blindspot.optInt("rotationBase", 0))
        if (!blindspot.has("rotationBaseRight")) blindspot.put("rotationBaseRight", blindspot.optInt("rotationBase", 0))
        // Fisheye / lens dewarp for the SINGLE-CAMERA views (side/rear) only. 0..100,
        // same two-parameter division-model dewarp as recording.rectifyStrength but a
        // SEPARATE knob so the blind-spot single-cam feed can be straightened
        // independently of the recording pipeline. 0 = off (default); ignored for the
        // merged 'both' view (libod already handles that projection).
        if (!blindspot.has("rectifyStrength")) blindspot.put("rectifyStrength", 0)
        // Speed window (km/h) the card is allowed to show in, and a reverse-gear
        // suppression switch. 0/0 = "any speed" (shipping behaviour): the gate is
        // OFF and the card shows on a turn signal at any speed, including when the
        // speed unit is undetected (readCurrentSpeedKmh → NaN). Setting either bound
        // ARMS the gate. suppressInReverse defaults FALSE so behaviour is unchanged
        // until the user opts in. See GpuSurveillancePipeline.bsSpeedGateAllows.
        if (!blindspot.has("minSpeedKmh")) blindspot.put("minSpeedKmh", 0)
        if (!blindspot.has("maxSpeedKmh")) blindspot.put("maxSpeedKmh", 0)
        if (!blindspot.has("suppressInReverse")) blindspot.put("suppressInReverse", false)

        // Telemetry Overlay defaults.
        //
        // Master-enable schema (which flows burn in the overlay AT ALL):
        //   enabled            (legacy) — pano/ACC-on fallback when panoEnabled absent
        //   panoEnabled        explicit pano/ACC-on gate; if absent, falls back to enabled
        //   surveillanceEnabled explicit ACC-off surveillance gate; default false
        //                      (parked burn-in is a deliberate opt-in — it keeps
        //                      the BYD-HAL poll alive while parked, a 12V draw)
        //   oemDashcamEnabled  explicit OEM Dashcam gate; default false (the OEM
        //                      front sensor doesn't need a stamp by default —
        //                      separate opt-in keeps pano clean while OEM is on)
        //
        // Per-flow FIELD selection (which fields each enabled flow draws) lives
        // under a nested `fields` object keyed by flow:
        //   fields.accOn / fields.surveillance / fields.oemDashcam = ["speed",...]
        // Each flow owns its OWN list — never shared. A MISSING list resolves to
        // the legacy eight-field default (see TelemetryFields.legacyDefault),
        // which is exactly what the overlay drew before this feature — so an app
        // update never changes what an existing overlay shows. New optional
        // fields (batteryPercent, voltage12v, lowBeam, highBeam, location)
        // default OFF because they aren't in the legacy default.
        //
        // We deliberately DON'T seed the per-flow lists here: absence === legacy
        // default at read time, so seeding would only risk drift from the
        // canonical default that TelemetryFields owns. The web UI writes an
        // explicit list the first time the user edits a flow.
        //
        // Master resolver: isTelemetryOverlayEnabledFor(flow). Field resolver:
        // getTelemetryOverlayFields(flow). Don't read the keys directly from
        // callers — the legacy fallbacks are part of the contract.
        val telemetryOverlay = config.optJSONObject("telemetryOverlay") ?: JSONObject().also {
            config.put("telemetryOverlay", it)
        }
        if (!telemetryOverlay.has("enabled")) telemetryOverlay.put("enabled", false)

        // OEM Dashcam toggles (separate from camera.* because they're feature
        // gates, not HAL config). disableNativeDvr is sticky-applied on every
        // daemon boot if true (handles OTA / factory reset un-doing the
        // pm disable-user). bitrateBudget is the soft cap for the combined
        // pano+OEM bitrate when both pipelines run; UI uses it to size the
        // sliders. 10 Mbps matches the Adreno 610 H.264 ceiling under encoder
        // isolation.
        val oemDashcam = config.optJSONObject("oemDashcam") ?: JSONObject().also {
            config.put("oemDashcam", it)
        }
        if (!oemDashcam.has("enabled")) oemDashcam.put("enabled", false)
        // Note: legacy accOffMode key is intentionally NOT seeded here.
        // migrateOemDashcamModes nulls it on upgrades; fresh installs simply
        // don't have it. The modern schema is recordingMode + surveillanceMode.
        if (!oemDashcam.has("disableNativeDvr")) oemDashcam.put("disableNativeDvr", false)
        // Surveillance integration: when enabled, OEM dashcam pipeline records
        // dvr_*.mp4 clips in parallel with pano event_*.mp4 on every motion
        // trigger. Reuses the existing surveillance gating (SafeLocation,
        // schedule, per-camera enable) — no duplicate config.
        val oemSurv = oemDashcam.optJSONObject("surveillance") ?: JSONObject().also {
            oemDashcam.put("surveillance", it)
        }
        if (!oemSurv.has("enabled")) oemSurv.put("enabled", false)
        if (!oemDashcam.has("bitrateBudget"))    oemDashcam.put("bitrateBudget", 10_000_000)
        // Per-pipeline quality slot. Pano reads recording.recordingQuality;
        // OEM reads this. Without a separate slot, both pipelines pulled
        // from the same key and the budget-cap math double-counted pano's
        // tier (MAX→2 Mbps clamp regression). STANDARD default keeps OEM
        // clips well under the budget without user tuning.
        if (!oemDashcam.has("recordingQuality")) oemDashcam.put("recordingQuality", "STANDARD")
        if (!oemDashcam.has("codec")) oemDashcam.put("codec", "H264")
        if (!oemDashcam.has("fps")) oemDashcam.put("fps", 30)
        // ---- OEM parked-idle low-power throttle (default OFF = byte-identical) ----
        // When parked + smart surveillance + vehicle OFF, the OEM dashcam is kept
        // warm but only records event clips (triggered by the pano detector). While
        // idle we throttle the ENCODE draw-stride to ~idleFps (the camera HAL rate
        // stays >=15 since live setCameraFps is HAL-rejectable and KEY_FRAME_RATE is
        // immutable — so we can snap to a clean full-fps event clip). Master gate
        // defaults FALSE; the drive/continuous fps floor is never touched.
        if (!oemDashcam.has("idleThrottleWhenParked")) oemDashcam.put("idleThrottleWhenParked", false)
        if (!oemDashcam.has("idleFps")) oemDashcam.put("idleFps", 5)
        if (!oemDashcam.has("priorityWhenContended")) {
            // "pano" | "oemDashcam" — whichever pipeline holds the AVMCamera
            // when concurrentAvmSupported=0. Default pano because pano feeds
            // both surveillance and the existing dashcam mode.
            oemDashcam.put("priorityWhenContended", "pano")
        }
        if (!oemDashcam.has("segmentRotateOffsetMs")) {
            // Stagger OEM segment rotation so MediaMuxer.stop() bursts on the
            // two pipelines don't collide. 30s into the pano cycle.
            oemDashcam.put("segmentRotateOffsetMs", 30_000)
        }

        // OS package policy (NOT feature gates). Each key is a `pm` state that a
        // firmware OTA silently undoes, so it is re-asserted on daemon boot —
        // see TrafficMonitorPolicy.enforceStickyDisableIfRequested.
        val systemApps = config.optJSONObject("systemApps") ?: JSONObject().also {
            config.put("systemApps", it)
        }
        if (!systemApps.has("disableTrafficMonitor")) {
            systemApps.put("disableTrafficMonitor", false)
        }

        // Trip Analytics defaults
        val tripAnalytics = config.optJSONObject("tripAnalytics") ?: JSONObject().also {
            config.put("tripAnalytics", it)
        }
        // Default ON — see TripConfig.DEFAULT_ENABLED. With this false the whole
        // trip subsystem is never constructed (TripAnalyticsManager.initComponents
        // is skipped), so the Trips page can only show "No trips recorded yet"
        // while the one enable switch sits on the Storage tab.
        if (!tripAnalytics.has("enabled")) tripAnalytics.put("enabled", true)
        // ONE-SHOT UPGRADE. Flipping the seed default above only helps FRESH
        // installs: every existing unit already has `enabled: false` persisted
        // from when false was the default, and load() honours a persisted value.
        // Those users would keep an inert trips page forever. Flip the stored
        // false exactly once, marked by enabledDefaultMigrated.
        //
        // HONEST LIMITATION: the marker only protects opt-outs made from NOW ON.
        // A config written before this build has no marker, so a `false` that the
        // user deliberately chose is indistinguishable from the old default and
        // IS overridden on this one upgrade. Trips capture GPS + 5Hz telemetry, so
        // that is a real (one-time) privacy change — release-note it rather than
        // claiming the design prevents it. After the flip, the marker makes every
        // subsequent opt-out permanent.
        if (!tripAnalytics.has("enabledDefaultMigrated")) {
            tripAnalytics.put("enabledDefaultMigrated", true)
            if (!tripAnalytics.optBoolean("enabled", true)) {
                tripAnalytics.put("enabled", true)
                // Log once per process. applyDefaults is idempotent and re-runs on
                // every reparse in the APP uid (persistMigrationUnderLock is
                // daemon-only, so the app never writes the marker back), which
                // would otherwise re-log this line on each reload.
                if (!tripEnabledMigrationLogged) {
                    tripEnabledMigrationLogged = true
                    Log.i(TAG, "tripAnalytics.enabled false→true (one-shot default migration)")
                }
            }
        }

        // Floating status pill segment visibility. Independent of whether the
        // underlying feature (recording / trip analytics) is enabled — these
        // only gate the pill segments so users can hide either without
        // surrendering SYSTEM_ALERT_WINDOW or disabling the feature itself.
        val statusOverlay = config.optJSONObject("statusOverlay") ?: JSONObject().also {
            config.put("statusOverlay", it)
        }
        if (!statusOverlay.has("cameraVisible")) statusOverlay.put("cameraVisible", true)
        if (!statusOverlay.has("tripVisible")) statusOverlay.put("tripVisible", true)
        if (!statusOverlay.has("replayVisible")) statusOverlay.put("replayVisible", true)

        // Remote communication defaults. The emergency latch is independent of
        // the two feature switches so one action can silence every remote surface
        // without forgetting the user's normal voice/message preferences.
        val remoteCommunication =
            config.optJSONObject("remoteCommunication") ?: JSONObject().also {
                config.put("remoteCommunication", it)
            }
        if (!remoteCommunication.has("voiceEnabled")) {
            remoteCommunication.put("voiceEnabled", true)
        }
        if (!remoteCommunication.has("outputLevel")) {
            remoteCommunication.put("outputLevel", 70)
        }
        if (!remoteCommunication.has("outputLevelOverrideEnabled")) {
            remoteCommunication.put("outputLevelOverrideEnabled", false)
        }
        if (!remoteCommunication.has("audioChannel")) {
            remoteCommunication.put("audioChannel", "media")
        }
        if (!remoteCommunication.has("listenerEnabled")) {
            remoteCommunication.put("listenerEnabled", false)
        }
        if (!remoteCommunication.has("messagesEnabled")) {
            remoteCommunication.put("messagesEnabled", true)
        }
        if (!remoteCommunication.has("emergencyDisabled")) {
            remoteCommunication.put("emergencyDisabled", false)
        }
        
        // BYD Cloud defaults
        val bydCloud = config.optJSONObject("bydCloud") ?: JSONObject().also {
            config.put("bydCloud", it)
        }
        if (!bydCloud.has("enabled")) bydCloud.put("enabled", false)

        // GenAI BYOK. Disabled by default; no provider client, worker, timer,
        // or network request exists until the user explicitly enables and
        // invokes it. The apiKey field is intentionally not seeded here —
        // GenAiConfig owns encrypted credential persistence.
        val genAi = config.optJSONObject("genAi") ?: JSONObject().also {
            config.put("genAi", it)
        }
        if (!genAi.has("enabled")) genAi.put("enabled", false)
        if (!genAi.has("provider")) genAi.put("provider", "openai")
        if (!genAi.has("baseUrl")) genAi.put("baseUrl", "https://api.openai.com")
        if (!genAi.has("model")) genAi.put("model", "")
        if (!genAi.has("realtimeModel")) genAi.put("realtimeModel", "")
        if (!genAi.has("maxOutputTokens")) genAi.put("maxOutputTokens", 1200)
        if (!genAi.has("insightSchedule")) genAi.put("insightSchedule", "off")
        if (!genAi.has("insightHour")) genAi.put("insightHour", 20)
        if (!genAi.has("insightMinute")) genAi.put("insightMinute", 0)
        if (!genAi.has("insightDay")) genAi.put("insightDay", 7)
        if (!genAi.has("insightMode")) genAi.put("insightMode", "overview")
        if (!genAi.has("insightDashboard")) genAi.put("insightDashboard", false)
        if (!genAi.has("insightNotifications")) genAi.put("insightNotifications", false)

        // Cloudflared defaults
        val cloudflared = config.optJSONObject("cloudflared") ?: JSONObject().also {
            config.put("cloudflared", it)
        }
        if (!cloudflared.has("isPaid")) cloudflared.put("isPaid", false)
        if (!cloudflared.has("token")) cloudflared.put("token", "")

        // RoadSense Map (navMap) — routing BYOK credential section. Basemap (OpenFreeMap)
        // needs no key; only the routing provider is bring-your-own-key, stored encrypted
        // via CredentialCipher (see NavMapConfig). Default disabled until the user adds a key.
        val navMap = config.optJSONObject("navMap") ?: JSONObject().also {
            config.put("navMap", it)
        }
        if (!navMap.has("enabled")) navMap.put("enabled", false)
        // Auto-project the map onto the driver cluster on ACC-on. Off by default;
        // the daemon reads this on power-up, the Map tab toggles it.
        if (!navMap.has("autoProjectCluster")) navMap.put("autoProjectCluster", false)
        // Daemon→Activity coordination flag: ClusterMapProjector sets it true on
        // start and false on stop/abort; the launched cluster map Activity polls it
        // and self-finishes when false (the OEM projection close never destroys the
        // fission display, so onDisplayRemoved can't be relied on). Default false =
        // no map projected.
        if (!navMap.has("clusterMapActive")) navMap.put("clusterMapActive", false)

        // Projection (native ProjectionFragment — cast an app onto the driver cluster).
        // autoStartOnAcc casts autoStartPackage onto the cluster on ACC-on, read by the
        // daemon in AccMonitor.notifyAccEdge exactly like navMap.autoProjectCluster does for
        // the map. Mutually exclusive with navMap.autoProjectCluster (single cluster takeover
        // surface); each toggle clears the sibling, and AccMonitor tiebreaks map-wins. The
        // package lives here (daemon-readable, uid 2000) rather than the fragment's app-UID
        // SharedPreferences (box geometry) because the daemon needs it at power-up. Off by default.
        val projection = config.optJSONObject("projection") ?: JSONObject().also {
            config.put("projection", it)
        }
        if (!projection.has("autoStartOnAcc")) projection.put("autoStartOnAcc", false)
        if (!projection.has("autoStartPackage")) projection.put("autoStartPackage", "")

        // Vehicle appearance defaults — selected 3D model and body paint color.
        // Stored unified so AVN and remote (phone-over-tunnel) clients show the
        // same vehicle. modelId must match an entry in models/manifest.json; the
        // bundled default 'seal' is always available offline.
        val storedVehicle = config.optJSONObject("vehicle")
        val hadPersistedModel = storedVehicle?.has("modelId") == true
        val vehicle = storedVehicle ?: JSONObject().also {
            config.put("vehicle", it)
        }
        // Keep Seal as the offline 3D-renderer fallback, but do not present it
        // to camera/SOH logic as a selected physical model on fresh installs.
        // Existing configs predate provenance, so preserve their prior model
        // behavior with the legacy source.
        val defaultModel = if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) "sealion7" else "seal"
        if (!vehicle.has("modelId")) vehicle.put("modelId", defaultModel)
        if (!vehicle.has("modelSource")) {
            vehicle.put(
                "modelSource",
                if (hadPersistedModel) {
                    VehicleModelSelection.SOURCE_LEGACY
                } else {
                    VehicleModelSelection.SOURCE_UNSET
                }
            )
        }
        if (!vehicle.has("color")) vehicle.put("color", "#E8E8EC")  // Aurora White

        // Geocoding (place-name tagging) defaults — opt-in, fully offline by
        // default so the upgrade is non-surprising for existing users.
        // Per-flow split (recording / surveillance) so dashcam and sentry
        // clips can be tagged independently.
        //
        // Migration: if a pre-split config has top-level enabled / allowOnline
        // / customNominatimBase / nominatimCooldownUntilMs fields, fold them
        // into the new shape. The fold is one-shot and idempotent — once the
        // sub-objects exist applyDefaults is a no-op. Without this, users who
        // had the feature enabled under the old schema would silently lose
        // the toggle on first run after upgrade.
        val geocoding = config.optJSONObject("geocoding") ?: JSONObject().also {
            config.put("geocoding", it)
        }

        // Capture legacy values BEFORE constructing nested defaults so the
        // sub-objects pick up the old settings instead of defaulting to off.
        val legacyEnabled = geocoding.has("enabled") && geocoding.optBoolean("enabled", false)
        val legacyOnline  = geocoding.has("allowOnline") && geocoding.optBoolean("allowOnline", false)
        val legacyCustomUrl = geocoding.optString("customNominatimBase", "")
        val legacyCooldown = geocoding.optLong("nominatimCooldownUntilMs", 0L)

        val recordingGeo = geocoding.optJSONObject("recording") ?: JSONObject().also {
            geocoding.put("recording", it)
        }
        if (!recordingGeo.has("enabled")) recordingGeo.put("enabled", legacyEnabled)
        if (!recordingGeo.has("allowOnline")) recordingGeo.put("allowOnline", legacyOnline)

        val surveillanceGeo = geocoding.optJSONObject("surveillance") ?: JSONObject().also {
            geocoding.put("surveillance", it)
        }
        if (!surveillanceGeo.has("enabled")) surveillanceGeo.put("enabled", legacyEnabled)
        if (!surveillanceGeo.has("allowOnline")) surveillanceGeo.put("allowOnline", legacyOnline)

        val advancedGeo = geocoding.optJSONObject("advanced") ?: JSONObject().also {
            geocoding.put("advanced", it)
        }
        if (!advancedGeo.has("customNominatimBase")) {
            advancedGeo.put("customNominatimBase", legacyCustomUrl)
        }
        if (!advancedGeo.has("nominatimCooldownUntilMs")) {
            advancedGeo.put("nominatimCooldownUntilMs", legacyCooldown)
        }

        // Strip the legacy top-level keys after migration so future writers
        // don't accidentally re-introduce stale values.
        if (geocoding.has("enabled")) geocoding.remove("enabled")
        if (geocoding.has("allowOnline")) geocoding.remove("allowOnline")
        if (geocoding.has("customNominatimBase")) geocoding.remove("customNominatimBase")
        if (geocoding.has("nominatimCooldownUntilMs")) geocoding.remove("nominatimCooldownUntilMs")
    }
    
    /**
     * Load config from file (with caching).
     */
    private class ConfigReadUnavailableException(
        message: String,
        cause: Throwable? = null
    ) : IllegalStateException(message, cause)

    private fun readLiveConfigText(configFile: File): String {
        return try {
            configFile.readText()
        } catch (e: Exception) {
            throw ConfigReadUnavailableException(
                "Config bytes unavailable at ${configFile.path}: " +
                    (e.message ?: e.javaClass.simpleName),
                e
            )
        }
    }

    /** Caller must hold [withConfigFileLock], directly or through a public wrapper. */
    private fun readDurableConfigLockedStrict(): JSONObject {
        val file = File(CONFIG_PATH)
        if (!file.isFile) {
            throw ConfigReadUnavailableException(
                "Durable config is absent at $CONFIG_PATH"
            )
        }
        val encoded = readLiveConfigText(file)
        if (encoded.isBlank()) {
            throw ConfigReadUnavailableException(
                "Durable config is empty at $CONFIG_PATH"
            )
        }
        return try {
            JSONObject(encoded)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Durable config is malformed at $CONFIG_PATH", e
            )
        }
    }

    @JvmStatic
    fun loadConfig(): JSONObject {
        val configFile = File(CONFIG_PATH)
        
        // Check if file changed since last load
        // Cheap fast-path: serve the cache only when isCacheFresh proves the
        // stamped mtime is unchanged and its coarse filesystem second has
        // elapsed. Equal-mtime reads within that second reparse, covering
        // same-second peer rewrites even when their byte length is unchanged.
        // Every write path also re-reads through loadConfigFresh() under the
        // stable file lock, so a mutation never merges against stale bytes.
        // Snapshot the volatile field ONCE into a local: this fast-path runs
        // OUTSIDE synchronized(this), and a concurrent writer (forceReload /
        // loadConfigFresh) nulls cachedConfig under the monitor. A check-then-`!!`
        // on the field directly is a TOCTOU — the null-write can land between the
        // null-check and the `!!` deref, throwing NPE. The local read is atomic
        // (field is @Volatile) so `cached` cannot become null after the check.
        val cached = cachedConfig
        if (cached != null && configFile.exists()) {
            if (isCacheFresh(configFile)) {
                return cached
            }
        }
        
        val loaded = try {
            withConfigFileLock {
                loadConfigUnderFileLock(configFile)
            }
        } catch (e: ConfigLockUnavailableException) {
            Log.w(TAG, "Config load proceeding without cache publication: ${e.message}")
            loadConfigWithoutStableLock(configFile)
        }
        // INVARIANT: promotion routes a blocking IPC write to the daemon, and the
        // daemon's write handler acquires the SAME stable file lock. If THIS
        // thread already holds it (updateSection/updateValues re-read through
        // loadConfigFresh → here, under withConfigMutationLock), routing would
        // stall the daemon behind our own lock until our socket timeouts unwind
        // it. Promotion is opportunistic and retries on the next UNLOCKED load,
        // so deferring here loses nothing.
        if (holdingFileLock.get() == true) {
            return loaded
        }
        return promoteNewerAppPrivateBackupIfNeeded(loaded)
    }

    /**
     * Parse, migrate, and publish one config snapshot while the stable
     * cross-process lock is held. Keeping the byte read through cache+mtime
     * publication in this critical section prevents old bytes from being
     * stamped with a peer replacement's newer mtime.
     */
    private fun loadConfigUnderFileLock(configFile: File): JSONObject {
        return try {
                if (configFile.exists()) {
                    val content = readLiveConfigText(configFile)
                    var config = JSONObject(content)
                    // Run schema migration on every load. applyDefaults is
                    // idempotent — it only fills in absent fields and only
                    // strips legacy top-level geocoding keys that have
                    // already been folded into the nested shape. Without
                    // this call, a user with a pre-split flat geocoding
                    // schema on disk would silently lose their toggle —
                    // getGeocoding() would return false because the new
                    // nested keys wouldn't exist.
                    //
                    // Detect "needs migration" cheaply (legacy key at the
                    // top of geocoding, or missing new sections).
                    val migrationNeeded = run {
                        if (!config.has("cloudflared")) return@run true
                        // Trips default-ON one-shot: existing installs carry a
                        // persisted `enabled:false` that applyDefaults must be
                        // given the chance to flip exactly once. Without this
                        // term the whole migration block is skipped on configs
                        // that are otherwise up to date, and trips stay dead.
                        val trips = config.optJSONObject("tripAnalytics")
                        if (trips == null || !trips.has("enabledDefaultMigrated")) return@run true
                        // Pre-provenance configs have modelId but no modelSource;
                        // applyDefaults must run once to classify them as legacy.
                        val vehicle = config.optJSONObject("vehicle") ?: return@run true
                        if (!vehicle.has("modelSource")) return@run true
                        val geo = config.optJSONObject("geocoding") ?: return@run true
                        geo.has("enabled") || geo.has("allowOnline")
                            || geo.has("customNominatimBase")
                            || geo.has("nominatimCooldownUntilMs")
                    }
                    if (migrationNeeded) {
                        applyDefaults(config)
                        // Persist the migrated shape so subsequent loads skip
                        // the migration check. DAEMON-ONLY + cross-process
                        // locked + re-read-under-lock: the previous unconditional
                        // saveConfigInternal here ran under only synchronized(this),
                        // so a peer daemon JVM's section write landing between our
                        // L550 read and this write was silently clobbered with our
                        // stale snapshot. persistMigrationUnderLock re-reads the
                        // CURRENT on-disk bytes under the file lock and re-applies
                        // the (idempotent) migration to those, so no peer section
                        // is lost. App UID skips persisting (keeps the migrated
                        // object in memory); the daemon persists on its next write.
                        persistMigrationUnderLock()?.let { committedMigration ->
                            config = committedMigration
                        }
                    }
                    // Parsed cleanly — any earlier corruption is resolved
                    // (e.g. the daemon rewrote a valid config). Re-arm saving.
                    corruptionDetected = false

                    cachedConfig = config
                    stampFreshness(configFile.lastModified())
                    // DEBUG-only: this fires on every genuine reparse (the mtime
                    // early-return above gates unchanged loads), so a 2-3Hz writer
                    // makes it the dominant logcat line. Each Log.d is a synchronous
                    // write; gate it out of release builds.
                    if (com.overdrive.app.BuildConfig.DEBUG) {
                        Log.d(TAG, "Config loaded from $CONFIG_PATH")
                    }
                    config
                } else {
                    Log.w(TAG, "Config file not found, initializing...")
                    init()
                    cachedConfig ?: createDefaultConfig()
                }
            } catch (e: ConfigReadUnavailableException) {
                // An I/O/permission/dependency failure is not evidence of
                // corruption. Never promote a backup or write defaults over a
                // live file that merely could not be read.
                stampFreshness(0)
                Log.w(TAG, "Config read deferred: ${e.message}")
                cachedConfig ?: createDefaultConfig()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config: ${e.message}")
                // The file exists and is non-empty but didn't parse — this is
                // corruption, not a fresh install. This can be left by a legacy
                // build's non-atomic fallback or by an external writer.
                //
                // Recovery, in order of preference:
                //   1. Second-chance re-read: an external writer can leave the
                //      file momentarily unparseable. Re-read once before
                //      declaring it dead so a transient race doesn't latch.
                //   2. Restore from the last-known-good .bak that saveConfig()
                //      mirrors after every successful write.
                //   3. Genuinely unparseable AND no usable .bak. The on-disk
                //      bytes are unrecoverable, so the corruption latch would
                //      only PERMANENTLY brick saving: saveConfig() refuses
                //      while latched, but the ONLY thing that clears the latch
                //      is a clean load, which needs a clean write the latch
                //      blocks — a deadlock (the "saves don't stick, reverts to
                //      original" report). Preserve the corrupt bytes to .bad,
                //      then — IF we can atomic-write (daemon UID 2000) — reset
                //      the live file to defaults so saving works again. The app
                //      UID leaves the repair to the daemon (its writes route
                //      there anyway) to avoid any non-atomic rewrite,
                //      and latches meanwhile to block a defaults-clobber.
                // (2) Transient-corruption second read.
                try {
                    if (configFile.exists() && configFile.length() > 0L) {
                        val retry = JSONObject(readLiveConfigText(configFile))
                        applyDefaults(retry)
                        cachedConfig = retry
                        stampFreshness(configFile.lastModified())
                        corruptionDetected = false
                        Log.w(TAG, "Config parsed on second read; treated as " +
                            "transient corruption (no latch)")
                        return retry
                    }
                } catch (readUnavailable: ConfigReadUnavailableException) {
                    stampFreshness(0)
                    Log.w(TAG, "Config re-read deferred without recovery: " +
                        readUnavailable.message)
                    return cachedConfig ?: createDefaultConfig()
                } catch (_: Exception) {
                    // Still unparseable — genuinely dead. Fall through to (3).
                }

                recoverFromBackup(configFile)?.let { return it }
                val backupArtifactExists =
                    File(configFile.parentFile, configFile.name + ".bak").exists() ||
                        (android.os.Process.myUid() != SHELL_DAEMON_UID &&
                            File(APP_PRIVATE_BAK_PATH).exists())
                if (backupArtifactExists) {
                    // A backup that currently cannot be parsed/read may still
                    // be recoverable once storage/permissions settle. Do not
                    // replace the live file with defaults while that dependency
                    // is unavailable.
                    corruptionDetected = true
                    stampFreshness(0)
                    Log.e(TAG, "Config recovery deferred: a backup artifact is " +
                        "present but unavailable")
                    return cachedConfig ?: createDefaultConfig()
                }

                // (3) Preserve corrupt bytes for forensics / manual recovery.
                try {
                    if (configFile.exists() && configFile.length() > 0L) {
                        val badFile = File(configFile.parentFile, configFile.name + ".bad")
                        if (configFile.copyTo(badFile, overwrite = true).exists()) {
                            badFile.setReadable(true, false)
                            Log.e(TAG, "Corrupt config preserved to ${badFile.path}")
                        }
                    }
                } catch (backupErr: Exception) {
                    Log.w(TAG, "Failed to back up corrupt config: ${backupErr.message}")
                }

                val defaults = createDefaultConfig()
                if (android.os.Process.myUid() == SHELL_DAEMON_UID) {
                    // We own the sticky-dir file and can atomic-rename: repair
                    // it so the feature isn't permanently bricked. The user's
                    // settings were already unrecoverable (corrupt + no .bak),
                    // so this loses nothing recoverable — it only restores the
                    // ability to save. Clear the latch since the file is now
                    // clean. Hold the cross-process lock so the repair rename
                    // doesn't interleave with a peer daemon's write.
                    //
                    // TOCTOU guard: a PEER daemon JVM may have repaired the file
                    // (its own recovery, or a real write) in the window between
                    // our parse-failure above and acquiring the lock here. So
                    // RE-READ on-disk UNDER the lock first: if it now parses,
                    // ADOPT the peer's bytes instead of clobbering them with
                    // defaults (which would also REGRESS configSeq). Only write
                    // defaults when the file is still genuinely unparseable, and
                    // compute the seq INSIDE the lock so it can't regress below a
                    // peer write that landed first.
                    val repaired = withConfigFileLockOrNull(
                        "Unrecoverable config repair"
                    ) {
                        val cf = File(CONFIG_PATH)
                        var peerReadUnavailable = false
                        val peerGood = try {
                            if (cf.exists() && cf.length() > 0L) {
                                JSONObject(readLiveConfigText(cf))
                            } else {
                                null
                            }
                        } catch (_: ConfigReadUnavailableException) {
                            peerReadUnavailable = true
                            null
                        } catch (_: Exception) { null }
                        if (peerReadUnavailable) {
                            // Dependency/read failure is not a proof that the
                            // live bytes remain corrupt. Defer instead of
                            // destructively replacing an unreadable valid file.
                            null
                        } else if (peerGood != null) {
                            // A peer already fixed it — adopt, don't overwrite.
                            cachedConfig = peerGood
                            stampFreshness(cf.lastModified())
                            corruptionDetected = false
                            Log.w(TAG, "Corrupt config was repaired by a peer (seq=${seqOf(peerGood)}); adopting")
                            peerGood
                        } else {
                            defaults.put(SEQ_KEY, nextConfigSeq(defaults))
                            val writeResult = saveConfigInternal(defaults)
                            if (writeResult.committed) {
                                val committed = writeResult.committedConfig ?: defaults
                                cachedConfig = committed
                                stampFreshness(cf.lastModified())
                                corruptionDetected = false
                                // Seed the sticky .bak from the repaired defaults
                                // so the very next write isn't operating without
                                // a recovery copy (the no-.bak window that made
                                // this branch reachable).
                                writeBackupCopy(committed)
                                Log.e(TAG, "Unrecoverable config (corrupt, no .bak); " +
                                    "reset to defaults so saving works again. Corrupt " +
                                    "bytes preserved at .bad")
                                committed
                            } else {
                                null
                            }
                        }
                    }
                    if (repaired != null) return repaired
                    Log.e(TAG, "Daemon repair-write of defaults failed; latching")
                }
                // App UID (or daemon repair failed): latch to block a
                // defaults-clobber and return defaults transiently. The daemon
                // repairs the live file on its next load (the very next
                // app-forwarded write triggers a daemon forceReload), after
                // which a clean load clears the latch everywhere. Don't cache
                // the defaults — a stale mtime check could otherwise keep
                // returning them after the daemon repairs the file.
                corruptionDetected = true
                cachedConfig ?: defaults
        }
    }

    /**
     * A read can precede daemon lock-file provisioning. Return a best-effort
     * snapshot in that narrow window, but never cache or stamp it: without the
     * stable lock, a peer rename could race either the byte read or metadata.
     */
    private fun loadConfigWithoutStableLock(configFile: File): JSONObject {
        return synchronized(this) {
            stampFreshness(0)
            try {
                if (configFile.exists() && configFile.length() > 0L) {
                    JSONObject(configFile.readText()).also { applyDefaults(it) }
                } else {
                    cachedConfig ?: createDefaultConfig()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unlocked config read unavailable (${e.message}); " +
                    "returning the last in-memory snapshot")
                cachedConfig ?: createDefaultConfig()
            }
        }
    }

    /**
     * Best-effort recovery of a corrupt live config from the last-known-good
     * backups written by saveConfig(). Returns the recovered config (and
     * promotes it back to the live path + clears corruptionDetected) on
     * success, or null if there's no usable backup. Caller holds the monitor.
     *
     * SEQ-AWARE, DOUBLE-SOURCE recovery: considers BOTH the sticky .bak
     * (daemon-maintained, may be STALE across a daemon-down session) AND the
     * app-private .bak (app-maintained, current for app-side writes). It
     * restores whichever VALID candidate has the highest [SEQ_KEY], so a stale
     * sticky .bak can NEVER silently revert newer user changes that the
     * app-private copy still holds (the historical "settings reverted after
     * OTA" symptom). Ties / legacy seq=0 fall back to the sticky .bak.
     */
    private fun recoverFromBackup(configFile: File): JSONObject? {
        val bakFile = File(configFile.parentFile, configFile.name + ".bak")
        val stickyBak: JSONObject? = try {
            if (bakFile.exists() && bakFile.length() > 0L) JSONObject(bakFile.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Sticky .bak at ${bakFile.path} unusable: ${e.message}"); null
        }
        val appBak: JSONObject? = readAppPrivateBackup()

        // Pick the highest-seq valid candidate. Prefer the sticky .bak on a tie
        // (seq equal, incl. both legacy 0) so behaviour matches the historical
        // single-source path when seqs are uninformative.
        val recovered: JSONObject? = when {
            stickyBak != null && appBak != null ->
                if (seqOf(appBak) > seqOf(stickyBak)) appBak else stickyBak
            stickyBak != null -> stickyBak
            appBak != null -> appBak
            else -> null
        }
        if (recovered == null) return null
        val src = if (recovered === appBak) APP_PRIVATE_BAK_PATH else bakFile.path

        return try {
            // Promote the good bytes back to the live path so peer processes
            // stop reading corruption. Recovering from a CORRUPT live file, so
            // we deliberately overwrite with the chosen .bak content (no fresh
            // re-read — the on-disk bytes ARE the corruption we're fixing).
            // Persist under the lock when this UID can complete the atomic
            // sibling-file rename. A non-daemon that cannot create the sibling
            // leaves disk repair deferred rather than truncating the corrupt
            // destination in place.
            val repairResult =
                if (android.os.Process.myUid() == SHELL_DAEMON_UID) {
                    withConfigFileLockOrNull(
                        "Backup config promotion"
                    ) {
                        saveConfigInternal(recovered)
                    }
                } else {
                    // The app UID cannot create the atomic sibling in sticky
                    // storage. Queue the already-validated recovery image for
                    // daemon IPC after loadConfig releases the file lock.
                    pendingRootPromotion =
                        JSONObject(recovered.toString())
                    null
                }
            val repaired = repairResult?.committed ?: false
            val committed = repairResult?.committedConfig ?: recovered
            cachedConfig = committed
            corruptionDetected = false
            // Only trust the fast-path mtime gate if the DISK was actually
            // repaired; otherwise leave lastModified at 0 so the next load
            // re-reads (and re-recovers) rather than serving cache over still-
            // corrupt bytes.
            if (repaired) {
                pendingRootPromotion = null
                stampFreshness(configFile.lastModified())
                Log.w(TAG, "Recovered + repaired config from $src " +
                    "(seq=${seqOf(committed)}) after corruption")
            } else {
                stampFreshness(0)
                Log.w(TAG, "Recovered config in-memory from $src (seq=${seqOf(recovered)}); disk repair deferred")
            }
            committed
        } catch (e: Exception) {
            Log.w(TAG, "Recovery from $src failed: ${e.message}")
            null
        }
    }
    
    /**
     * Save entire config to file.
     */
    @JvmStatic
    @JvmOverloads
    fun saveConfig(config: JSONObject, force: Boolean = false): Boolean {
        return withConfigMutationLock("Whole-config save") {
            saveConfigLocked(config, force)
        }
    }

    /**
     * Lock-held implementation. Keeping sequence allocation, persistence,
     * backup publication, and listener notification inside one stable lock
     * prevents a direct whole-config caller from bypassing cross-process
     * serialization.
     */
    private fun saveConfigLocked(config: JSONObject, force: Boolean): Boolean {
        // Corruption guard: if the last load found a non-empty-but-unparseable
        // file on disk and we couldn't recover from .bak, `config` here was
        // built from createDefaultConfig() (loadConfig returned defaults, then
        // updateSection merged into them). Persisting it would clobber the
        // user's real — and still on-disk, still recoverable — settings with
        // factory defaults. Refuse, and let a later clean load (e.g. once the
        // daemon rewrites a valid file) clear the latch. This is the fix for
        // the v23.x→v24.1 "all settings lost after upgrade" report.
        //
        // EXCEPTION — force=true: a whole-config RESTORE (ConfigBackupService.
        // applyBundle) passes an already-validated, COMPLETE user config, not a
        // defaults-merge. The latch's rationale (don't overwrite recoverable
        // settings with defaults) does NOT apply — and blocking here defeats the
        // entire point of the restore feature, which exists precisely to recover
        // FROM config corruption (a corrupt on-disk file leaves the latch set,
        // and .bak recovery having failed is exactly when the user reaches for a
        // backup). So force writes past the latch and, on success, clears it —
        // the bytes we just wrote ARE the new valid on-disk truth.
        if (corruptionDetected && !force) {
            Log.e(TAG, "saveConfig blocked: corruption latch set; refusing to " +
                "overwrite live config with defaults until a clean load clears it")
            return false
        }
        if (corruptionDetected && force) {
            Log.w(TAG, "saveConfig(force): writing a validated whole-config restore " +
                "past the corruption latch (restore is authoritative user data, not defaults)")
        }
        config.put("lastModified", System.currentTimeMillis())
        // Bump the monotonic write sequence so recovery / load-time promotion can
        // ALWAYS identify the newest copy independent of (second-granular,
        // possibly clock-skewed) mtime. Take max(disk, mem)+1 so a write never
        // regresses the seq even if `config` was built from a stale snapshot.
        config.put(SEQ_KEY, nextConfigSeq(config))
        val writeResult = saveConfigInternal(config)
        if (writeResult.committed) {
            val committed = writeResult.committedConfig ?: config
            // A forced restore just wrote a complete, valid config over a
            // (previously) corrupt file — the on-disk truth is now clean, so
            // release the latch. Without this, subsequent normal saveConfig()
            // calls in the same process would still be blocked until a reload.
            if (force && corruptionDetected) {
                corruptionDetected = false
                Log.i(TAG, "saveConfig(force): restore succeeded — corruption latch cleared")
            }
            cachedConfig = committed
            // Track the file's actual mtime, NOT wall-clock — the cache
            // freshness check at loadConfig() compares fs mtime against
            // this value to detect cross-process writes. If we stored
            // System.currentTimeMillis() here, the saved mtime would
            // (almost always) be greater than the file's mtime, so the
            // fileModified <= lastModified check would never trip and
            // a cross-UID write would never invalidate the cache.
            stampFreshness(File(CONFIG_PATH).lastModified())
            // Mirror a last-known-good copy. loadConfig() restores from this
            // when the live file is found corrupt (including damage left by
            // legacy builds or external writers). Best-effort: a
            // failure here doesn't fail the save.
            writeBackupCopy(committed)
            // SECOND last-known-good in the app's OWN private dir. The app can
            // atomic-rename in its private dir, so IPC-confirmed writes refresh
            // this copy even though it cannot refresh the sticky sibling.
            // recoverFromBackup picks
            // whichever .bak has the higher configSeq, so neither source can
            // revert the other. Best-effort; only the app process can write it.
            writeAppPrivateBackup(committed)
            notifyListeners("all", committed)
        }
        return writeResult.committed
    }

    /**
     * Truncate/write [file] and make both its bytes and inode metadata durable
     * before returning. Permission changes happen before fsync so the daemon's
     * world-RW contract is covered by the same durability boundary.
     */
    private fun writeFileAndSync(
        file: File,
        payload: String,
        worldAccessible: Boolean
    ) {
        FileOutputStream(file, false).use { output ->
            output.write(payload.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            if (worldAccessible) {
                file.setReadable(true, false)
                file.setWritable(true, false)
            }
            output.fd.sync()
        }
    }

    /**
     * Persist a completed rename in its parent directory. Linux/ext4 supports
     * directory fsync; a few Android filesystems explicitly reject it. Only
     * those "unsupported" errno values are tolerated. Real I/O and permission
     * failures propagate so callers never report an unconfirmed durable rename.
     */
    private fun syncDirectoryAfterRename(directory: File?) {
        if (directory == null || !directory.isDirectory) {
            throw IOException("Rename parent directory unavailable")
        }

        var directoryFd: FileDescriptor? = null
        try {
            val opened = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
                0
            )
            directoryFd = opened
            Os.fsync(opened)
        } catch (e: ErrnoException) {
            if (isUnsupportedDirectorySync(e.errno)) {
                Log.w(TAG, "Directory fsync unsupported for ${directory.path}: ${e.message}")
                return
            }
            throw IOException("Directory fsync failed for ${directory.path}", e)
        } finally {
            directoryFd?.let {
                try {
                    Os.close(it)
                } catch (_: ErrnoException) {
                    // The durability boundary was the successful fsync above.
                }
            }
        }
    }

    private fun isUnsupportedDirectorySync(errno: Int): Boolean =
        errno == OsConstants.EINVAL ||
            errno == OsConstants.ENOSYS ||
            errno == OsConstants.ENOTSUP ||
            errno == OsConstants.EOPNOTSUPP ||
            errno == OsConstants.EISDIR

    /**
     * Retry transient directory-sync failures. Returning false means the rename
     * is committed in the running system but its crash durability is uncertain.
     */
    private fun syncDirectoryAfterRenameWithRetry(directory: File?): Boolean {
        var lastFailure: Exception? = null
        repeat(DIRECTORY_SYNC_ATTEMPTS) { attempt ->
            try {
                syncDirectoryAfterRename(directory)
                if (attempt > 0) {
                    Log.w(TAG, "Config directory sync recovered on attempt ${attempt + 1}")
                }
                return true
            } catch (e: Exception) {
                lastFailure = e
                Log.w(TAG, "Config directory sync attempt ${attempt + 1}/" +
                    "$DIRECTORY_SYNC_ATTEMPTS failed: ${e.message}")
            }
        }
        Log.e(TAG, "Config rename committed but directory sync remains uncertain: " +
            (lastFailure?.message ?: "unknown failure"))
        return false
    }

    /**
     * Re-read the destination after a committed rename whose directory fsync
     * failed. Prefer the actual parseable destination for cache/listener
     * publication. If the read itself is transiently unavailable, the expected
     * object is still the best representation: its fully-synced temp inode was
     * already renamed onto the destination.
     */
    private fun reconcileCommittedRename(
        configFile: File,
        expectedPayload: String,
        expectedConfig: JSONObject
    ): JSONObject {
        repeat(DIRECTORY_SYNC_ATTEMPTS) { attempt ->
            try {
                val actualPayload = configFile.readText()
                val actual = JSONObject(actualPayload)
                if (actualPayload != expectedPayload) {
                    Log.w(TAG, "Committed config differs from staged payload; " +
                        "publishing destination seq=${seqOf(actual)}")
                } else if (attempt > 0) {
                    Log.w(TAG, "Committed config reconciliation recovered on " +
                        "attempt ${attempt + 1}")
                }
                return actual
            } catch (e: Exception) {
                Log.w(TAG, "Committed config reconciliation attempt ${attempt + 1}/" +
                    "$DIRECTORY_SYNC_ATTEMPTS failed: ${e.message}")
            }
        }
        Log.e(TAG, "Committed config could not be re-read; publishing the " +
            "fully-synced payload that reached the rename commit point")
        return JSONObject(expectedConfig.toString())
    }

    /**
     * Mirror the current good config to a sibling .bak (best-effort). Written
     * after every successful saveConfig so loadConfig() can self-heal a corrupt
     * live file. Failures are swallowed — the .bak is a safety net, never a
     * gate on the primary save succeeding.
     */
    private fun writeBackupCopy(config: JSONObject) {
        try {
            val configFile = File(CONFIG_PATH)
            val bakFile = File(configFile.parentFile, configFile.name + ".bak")
            val bakTmp = File(configFile.parentFile, configFile.name + ".bak.tmp")
            writeFileAndSync(bakTmp, config.toString(2), worldAccessible = true)
            if (!bakTmp.renameTo(bakFile)) {
                // tmp-create/rename can fail for the app UID on sticky
                // /data/local/tmp. Do NOT fall back to a non-atomic direct
                // write of the .bak: that would TRUNCATE the existing
                // last-known-good copy, and a pm-install kill mid-write could
                // leave BOTH primary and .bak torn — defeating the self-heal
                // this backup exists for. A stale-but-VALID .bak is strictly
                // better than a freshly-truncated one. Skip the update; the
                // daemon (UID 2000, can atomic-rename) refreshes the .bak on
                // its next write.
                try { bakTmp.delete() } catch (_: Exception) {}
            } else {
                syncDirectoryAfterRename(bakFile.parentFile)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backup copy skipped: ${e.message}")
        }
    }

    /**
     * Read the monotonic write sequence from a config (0 if absent/legacy).
     * CLAMPED to non-negative: the config file is world-RW (0666), so a foreign
     * writer could inject a negative or garbage configSeq. A negative seq would
     * make an older .bak (positive seq) out-rank a newer live config in
     * recovery/promotion — the exact "revert to older snapshot" the seq exists
     * to prevent. Floor at 0 so a tampered value degrades to "legacy/unknown"
     * (a no-op tie) rather than a monotonicity break.
     */
    private fun seqOf(config: JSONObject?): Long =
        (config?.optLong(SEQ_KEY, 0L) ?: 0L).coerceAtLeast(0L)

    /**
     * Compute the next [SEQ_KEY] value: strictly greater than BOTH the value in
     * the object being written AND the value currently on disk, so a write built
     * from a stale in-memory snapshot can never regress the sequence (which would
     * let a later load/recover wrongly prefer the older copy). Read under the
     * caller's lock (saveConfig runs inside updateSection's withConfigFileLock,
     * and ConfigBackupService's runUnderConfigLock).
     *
     * SATURATING add: if a foreign-injected seq is at Long.MAX_VALUE, `+1` would
     * wrap to Long.MIN_VALUE (negative) and break ordering. Saturate at MAX_VALUE
     * instead — the sequence then stops advancing (a benign monotonic plateau)
     * rather than wrapping negative. seqOf already floors inputs at 0.
     */
    private fun nextConfigSeq(config: JSONObject): Long {
        val inMem = seqOf(config)
        val onDisk = try {
            val cf = File(CONFIG_PATH)
            if (cf.exists() && cf.length() > 0L) seqOf(JSONObject(cf.readText())) else 0L
        } catch (_: Exception) { 0L }
        val base = maxOf(inMem, onDisk)
        return if (base >= Long.MAX_VALUE - 1L) Long.MAX_VALUE else base + 1L
    }

    /**
     * Mirror the good config to an APP-PRIVATE .bak the app process can ALWAYS
     * refresh atomically (its own data dir, where it can create a sibling and
     * rename — unlike sticky /data/local/tmp). Confirmed IPC writes refresh it,
     * and recoverFromBackup picks the higher-seq source between this and the
     * sticky .bak.
     *
     * Daemon-skip: the daemon (UID 2000) cannot write into the app's 0700 dir
     * and doesn't need to — it keeps the sticky .bak current. App-UID only.
     * Best-effort; a failure never fails the save.
     */
    private fun writeAppPrivateBackup(config: JSONObject) {
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return
        val snapshot = try {
            JSONObject(config.toString())
        } catch (e: Exception) {
            Log.d(TAG, "App-private backup snapshot skipped: ${e.message}")
            return
        }

        // FileLock provides cross-process ordering; the monitor prevents this
        // JVM's concurrent callers from throwing OverlappingFileLockException.
        synchronized(appPrivateBackupMonitor) {
            var lockRaf: RandomAccessFile? = null
            var lockChannel: FileChannel? = null
            var backupLock: FileLock? = null
            var tmp: File? = null
            try {
                val dir = File(APP_PRIVATE_DIR)
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw IOException("Could not create app-private config directory")
                }

                lockRaf = RandomAccessFile(File(APP_PRIVATE_BAK_LOCK_PATH), "rw")
                lockChannel = lockRaf.channel
                backupLock = lockChannel.lock()

                val bak = File(APP_PRIVATE_BAK_PATH)
                val published = try {
                    if (bak.exists() && bak.length() > 0L) JSONObject(bak.readText()) else null
                } catch (_: Exception) {
                    null
                }
                if (published != null && seqOf(published) >= seqOf(snapshot)) {
                    // An out-of-order IPC acknowledgment must never regress the
                    // recovery copy. Equal sequence is already the same commit
                    // (or an ordering plateau), so preserving it is safer too.
                    return@synchronized
                }

                val staging = File(
                    APP_PRIVATE_BAK_PATH + ".tmp." +
                        android.os.Process.myPid() + "." +
                        appPrivateBackupTempSequence.incrementAndGet()
                )
                tmp = staging
                writeFileAndSync(staging, snapshot.toString(2), worldAccessible = false)
                if (!staging.renameTo(bak)) {
                    // App-private dir: rename should succeed (same fs, app-owned).
                    // If it somehow fails, drop the tmp rather than truncate the bak.
                    try { tmp.delete() } catch (_: Exception) {}
                } else {
                    tmp = null
                    syncDirectoryAfterRename(bak.parentFile)
                }
            } catch (e: Exception) {
                Log.d(TAG, "App-private backup skipped: ${e.message}")
            } finally {
                try { tmp?.delete() } catch (_: Exception) {}
                try { backupLock?.release() } catch (_: Exception) {}
                try { lockChannel?.close() } catch (_: Exception) {}
                try { lockRaf?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Read the app-private .bak if present and parseable, else null. Used as a
     * second recovery source (typically NEWER than the sticky .bak across a
     * daemon-down session). App-UID only — the daemon can't read the 0700 dir.
     */
    private fun readAppPrivateBackup(): JSONObject? {
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return null
        return try {
            val bak = File(APP_PRIVATE_BAK_PATH)
            if (!bak.exists() || bak.length() == 0L) null
            else JSONObject(bak.readText())
        } catch (_: Exception) { null }
    }

    /**
     * Promote a newer app-private backup through the shell daemon. This runs
     * only after the caller's read lock has been released: routing while the app
     * held the stable file lock would deadlock the daemon waiting for that same
     * lock, while a direct app-UID save cannot create a sticky-directory temp
     * sibling.
     */
    private fun promoteNewerAppPrivateBackupIfNeeded(
        loaded: JSONObject
    ): JSONObject {
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return loaded
        pendingRootPromotion?.let { recovered ->
            // Clear before IPC: a successful acknowledgment calls forceReload(),
            // whose load path would otherwise observe the same pending object
            // and recursively route it again.
            pendingRootPromotion = null
            if (requestRootPromotion(recovered)) {
                return cachedConfig ?: loaded
            }
            pendingRootPromotion = recovered
            return loaded
        }

        val appBackup = readAppPrivateBackup() ?: return loaded
        val live = try {
            val file = File(CONFIG_PATH)
            if (!file.exists() || file.length() <= 0L) return loaded
            JSONObject(readLiveConfigText(file))
        } catch (_: Exception) {
            // Live authority is unavailable. Never infer that an older-looking
            // read authorizes a destructive promotion.
            return loaded
        }
        if (seqOf(appBackup) <= seqOf(live)) return loaded

        Log.w(TAG, "App-private .bak (seq=${seqOf(appBackup)}) newer than live " +
            "(seq=${seqOf(live)}); requesting daemon promotion")
        return if (requestRootPromotion(appBackup)) {
            cachedConfig ?: loaded
        } else {
            loaded
        }
    }

    private fun requestRootPromotion(candidate: JSONObject): Boolean {
        val payload = JSONObject(candidate.toString())
            .put(ROOT_PROMOTION_MARKER, true)
        return routeWriteIfApp(
            "UPDATE_SECTION",
            ROOT_PROMOTION_SECTION,
            "data",
            payload
        ) == true
    }
    
    private fun saveConfigInternal(config: JSONObject): ConfigWriteResult {
        val configFile = File(CONFIG_PATH)
        configFile.parentFile?.mkdirs()
        val payload = config.toString(2)

        // Atomic write: write to a sibling .tmp file, then rename. The rename
        // is a single inode swap on the filesystem, so power loss either
        // leaves the old file intact or fully promotes the new one — never
        // a half-written corrupt config that would wipe user settings.
        //
        // When the app UID (10xxx) cannot create this sibling in sticky
        // /data/local/tmp, the write is deferred/rejected. It must never fall
        // back to opening CONFIG_PATH with truncate semantics: a process kill
        // during that write can destroy the only live config.
        // Per-PROCESS-unique tmp name (.tmp.<pid>): the rename onto CONFIG_PATH
        // is atomic, but the sibling tmp itself must not be shared — two
        // writers (e.g. two daemon JVMs both running migrateFromLegacy on first
        // boot) using a fixed "${name}.tmp" could interleave write/rename on the
        // SAME tmp inode and corrupt each other. A pid suffix gives each writer
        // its own staging file; the locked write paths already serialize, this
        // just hardens any residual unlocked caller.
        val tmpFile = File(configFile.parentFile, configFile.name + ".tmp." + android.os.Process.myPid())
        try {
            writeFileAndSync(tmpFile, payload, worldAccessible = true)
            if (tmpFile.renameTo(configFile)) {
                if (syncDirectoryAfterRenameWithRetry(configFile.parentFile)) {
                    Log.i(TAG, "Config saved to $CONFIG_PATH (atomic)")
                    return ConfigWriteResult(
                        ConfigWriteState.COMMITTED_DURABLE,
                        config
                    )
                }
                val reconciled = reconcileCommittedRename(
                    configFile,
                    payload,
                    config
                )
                Log.w(TAG, "Config saved to $CONFIG_PATH (committed; " +
                    "directory durability uncertain)")
                return ConfigWriteResult(
                    ConfigWriteState.COMMITTED_UNCERTAIN,
                    reconciled
                )
            }
            Log.e(TAG, "Atomic rename failed; live config left untouched")
        } catch (e: Exception) {
            Log.w(TAG, "Tmp-write path unavailable (${e.message}); " +
                "live config left untouched and mutation deferred")
        } finally {
            try { tmpFile.delete() } catch (_: Exception) {}
        }

        return ConfigWriteResult(ConfigWriteState.NOT_COMMITTED)
    }
    
    // ==================== SECTION GETTERS ====================
    
    /**
     * Get surveillance config section.
     */
    @JvmStatic
    fun getSurveillance(): JSONObject {
        return loadConfig().optJSONObject("surveillance") ?: JSONObject()
    }

    // ---- Deferred "navigate here" (section: deferred_nav) --------------------
    // A phone/OverDrive "Navigate here" that arrives while the car is off is stored
    // here and offered as a floating prompt on the next ACC-on. Latest overwrites.
    fun getDeferredNav(): JSONObject = loadConfig().optJSONObject("deferred_nav") ?: JSONObject()

    fun setPendingNav(name: String, lat: Double, lng: Double, receivedAt: Long): Boolean =
        updateValues(
            "deferred_nav",
            mapOf(
                "pending" to true,
                "name" to name,
                "lat" to lat,
                "lng" to lng,
                "receivedAt" to receivedAt,
            ),
        )

    fun clearPendingNav(): Boolean = updateValues("deferred_nav", mapOf("pending" to false))

    /** Max age (hours) a queued target is still offered on ACC-on. Default 4. */
    fun getDeferredNavExpiryHours(): Int = getDeferredNav().optInt("expiryHours", 4).coerceIn(1, 72)

    /** Saved floating-prompt position, or null when never dragged. */
    fun getNavPromptPos(): Pair<Int, Int>? {
        val d = getDeferredNav()
        val x = d.optInt("posX", Int.MIN_VALUE)
        val y = d.optInt("posY", Int.MIN_VALUE)
        return if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) null else x to y
    }

    fun setNavPromptPos(x: Int, y: Int): Boolean =
        updateValues("deferred_nav", mapOf("posX" to x, "posY" to y))

    /**
     * Get the surveillance schedule from config.
     * Returns a SurveillanceSchedule loaded from the surveillance section.
     */
    @JvmStatic
    fun getSurveillanceSchedule(): com.overdrive.app.surveillance.SurveillanceSchedule {
        val schedule = com.overdrive.app.surveillance.SurveillanceSchedule()
        schedule.loadFromJson(getSurveillance())
        return schedule
    }
    
    /**
     * Get recording config section.
     */
    @JvmStatic
    fun getRecording(): JSONObject {
        return loadConfig().optJSONObject("recording") ?: JSONObject()
    }
    
    /**
     * Get streaming config section.
     */
    @JvmStatic
    fun getStreaming(): JSONObject {
        return loadConfig().optJSONObject("streaming") ?: JSONObject()
    }

    /** Blind Spot overlay config section: {enabled, rearFov, sideFov, yaw, roll,
     *  pitch, feather}. mtime-gated loadConfig; callers needing cross-UID
     *  freshness (app reading a daemon/web write) should forceReload() first. */
    @JvmStatic
    fun getBlindSpot(): JSONObject {
        return loadConfig().optJSONObject("blindspot") ?: JSONObject()
    }

    /** True when the blind-spot feature is enabled. */
    @JvmStatic
    fun isBlindSpotEnabled(): Boolean = getBlindSpot().optBoolean("enabled", false)

    /** Global camera fps while the camera is kept warm ONLY for blind-spot AND
     *  the blind-spot view is HIDDEN (turn signal off). BS renders to a
     *  SurfaceControl layer with no encoder, so the shared camera HAL fps is its
     *  only cost/quality lever; keeping it low here burns almost no GPU/encode
     *  while keeping the rails warm for an instant reveal. Default 1 fps. */
    @JvmStatic
    fun getBlindSpotIdleFps(): Int =
        getBlindSpot().optInt("idleFps", 1).coerceIn(1, 15)

    /** Global camera fps while the camera is kept warm ONLY for blind-spot AND
     *  the blind-spot view is SHOWN (turn signal on). Ramps the shared camera up
     *  so the blind-spot view is smooth. Default 15 fps. */
    @JvmStatic
    fun getBlindSpotActiveFps(): Int =
        getBlindSpot().optInt("activeFps", 15).coerceIn(1, 30)

    /** Master gate for the parked-idle surveillance camera throttle. Default
     *  FALSE ⇒ every deployed unit is byte-identical until explicit opt-in.
     *
     *  When enabled (camera.surveillanceIdleThrottle = true): while ARMED sentry
     *  sits idle (no motion) the shared camera HAL drops to the idle fps, cutting
     *  the capture-rail + compose/encode load that otherwise pins ~half a
     *  little-core 24/7 while parked. Detection parity is preserved by design —
     *  see desiredCameraState()/reconcileCameraProfileLocked(): the idle HAL floor
     *  is clamped to the motion cadence and the AI readback is pinned to a
     *  wall-clock interval, so motion sampling is identical whether the HAL is
     *  idle or full-rate. Kept opt-in because it changes parked sentry behavior on
     *  a safety-relevant feature. */
    @JvmStatic
    fun isSurveillanceIdleThrottle(): Boolean =
        loadConfig().optJSONObject("camera")?.optBoolean("surveillanceIdleThrottle", false) ?: false

    /** Shared camera HAL fps while ARMED surveillance sits idle (no active
     *  motion). Ramps back to the surveillance fps on first-motion. Default 5. */
    @JvmStatic
    fun getSurveillanceIdleFps(): Int =
        loadConfig().optJSONObject("camera")?.optInt("surveillanceIdleFps", 5)?.coerceIn(1, 15) ?: 5

    /** Quiet-tier step-down (issue #174): after this many minutes of SUSTAINED
     *  no-motion while the idle throttle is active, drop the AI motion-readback
     *  cadence (and relax the idle-HAL parity floor) to getSurveillanceQuietTierFps()
     *  so a parked car overnight stops paying ~10 Hz PBO readbacks all night. The
     *  first motion event restores the full cadence via the existing motion-edge
     *  ramp. Only takes effect when surveillanceIdleThrottle is on. Default 5 min;
     *  0 disables the quiet tier (keeps the pre-#174 constant cadence). */
    @JvmStatic
    fun getSurveillanceQuietTierMinutes(): Int =
        loadConfig().optJSONObject("camera")?.optInt("surveillanceQuietTierMinutes", 5)?.coerceIn(0, 240) ?: 5

    /** AI motion-readback cadence (Hz) once the quiet tier is entered (issue #174).
     *  Must stay ≤ getSurveillanceIdleFps() so the HAL always delivers at least as
     *  fast as the AI lane samples (the lane can only DROP delivered frames, never
     *  manufacture them). Default 3. */
    @JvmStatic
    fun getSurveillanceQuietTierFps(): Int =
        loadConfig().optJSONObject("camera")?.optInt("surveillanceQuietTierFps", 3)?.coerceIn(1, 15) ?: 3

    /** Master gate for the OEM dashcam parked-idle encode throttle. Default FALSE. */
    @JvmStatic
    fun isOemIdleThrottleWhenParked(): Boolean =
        getOemDashcam().optBoolean("idleThrottleWhenParked", false)

    /** OEM dashcam effective encode fps while parked-idle keep-warm. The camera
     *  HAL rate is unchanged (>=15); only the encoder draw-stride is throttled.
     *  Default 5. */
    @JvmStatic
    fun getOemIdleFps(): Int =
        getOemDashcam().optInt("idleFps", 5).coerceIn(1, 15)

    /** Blind-spot display target: "head_unit" (default) or "cluster". */
    @JvmStatic
    fun getBlindSpotTarget(): String =
        getBlindSpot().optString("target", "head_unit")

    @JvmStatic
    fun isBlindSpotCluster(): Boolean = "cluster" == getBlindSpotTarget()

    /** Persist the blind-spot display target. Single-key merge — never clobbers
     *  the per-target geometry/geometryCluster siblings. */
    @JvmStatic
    fun setBlindSpotTarget(target: String): Boolean =
        updateValues("blindspot", mapOf(
            "target" to (if (target == "cluster") "cluster" else "head_unit")))

    /** Persist blindspot keys with a per-key merge, so the calibration siblings
     *  (debugView, the fov/yaw/roll tuning values, geometry/geometryCluster) are
     *  preserved. Mirrors [setCamViewValues]. */
    @JvmStatic
    fun setBlindSpotValues(values: Map<String, Any>): Boolean = updateValues("blindspot", values)

    // ── Blind-spot conditional-display gate (speed window + reverse) ────────────
    // Readers take the whole `blindspot` object once (the daemon gate evaluates on a
    // 250ms tick and must not pay three loadConfig calls per pass), so there are no
    // per-key getters here — just the shared bound normaliser + its range constant.

    /** Accepted upper limit for both speed bounds (km/h). Shared by the web UI's
     *  input max, the POST validator, and the daemon gate so the three can't drift. */
    const val BS_SPEED_MAX_KMH = 300

    /** Normalise a stored/incoming speed bound: anything outside 1..BS_SPEED_MAX_KMH
     *  (including negatives and the 0 sentinel) collapses to 0 = "no bound on this
     *  end". The single definition of what the gate will actually honour, so the API
     *  can't accept a value the daemon then ignores. */
    @JvmStatic
    fun clampBsSpeedBound(kmh: Int): Int =
        if (kmh <= 0 || kmh > BS_SPEED_MAX_KMH) 0 else kmh

    // ── Camera-view (on-demand native camera view, shares the BS lane) ──────────
    /** The camview section {enabled, mode, target, geometry, geometryCluster, autoHideSec}. */
    @JvmStatic
    fun getCamView(): JSONObject {
        return loadConfig().optJSONObject("camview") ?: JSONObject()
    }

    /** True when an on-demand camera view is currently requested/shown. */
    @JvmStatic
    fun isCamViewEnabled(): Boolean = getCamView().optBoolean("enabled", false)

    /** Requested camera view mode: 0=all-4 mosaic, 1=front, 2=right, 3=rear, 4=left,
     *  plus 7/8 = the blind-spot rear+side composite (left/right). Anything else falls
     *  back to 0 (mosaic). Views 5 (debug raw) and 6 (OEM dashcam) are deliberately not
     *  camera views. Must stay in step with StreamingApiHandler.isCamViewMode. */
    @JvmStatic
    fun getCamViewMode(): Int = getCamView().optInt("mode", 0)
        .let { if (it in 0..4 || it == 7 || it == 8) it else 0 }

    /** Camera-view display target: "head_unit" (default) or "cluster". */
    @JvmStatic
    fun getCamViewTarget(): String = getCamView().optString("target", "head_unit")

    @JvmStatic
    fun isCamViewCluster(): Boolean = "cluster" == getCamViewTarget()

    /** Auto-hide timeout in seconds (0 = stay until explicitly hidden). */
    @JvmStatic
    fun getCamViewAutoHideSec(): Int = getCamView().optInt("autoHideSec", 0).coerceIn(0, 600)

    /** Persist camview keys with a shallow single-key merge (never clobbers the
     *  per-target geometry/geometryCluster siblings). */
    @JvmStatic
    fun setCamViewValues(values: Map<String, Any>): Boolean = updateValues("camview", values)

    /** Physical-key mapping section: {enabled, allowAdvanced, bindings:[
     *  {keycode:int, pressType:"single|double|long", action:{kind, variables}}]}.
     *  Manual replay is stored per binding as
     *  {kind:"manualClip", beforeSeconds:0..60, afterSeconds:0..60}; the two
     *  values must total 1..60 seconds.
     *  Consumed by KeepAliveAccessibilityService.onKeyEvent → KeyMapDispatcher
     *  (app UID) which fires the mapped action through the daemon; callers needing
     *  cross-UID freshness (the a11y service in the app process reading a
     *  web/daemon write) should forceReload() first. mtime-gated loadConfig
     *  otherwise. */
    @JvmStatic
    fun getKeymap(): JSONObject {
        return loadConfig().optJSONObject("keymap") ?: JSONObject()
    }

    /** Master switch — key interception is a no-op (all keys pass through)
     *  unless this is true. Defaults false so a fresh install never swallows
     *  hardware buttons until the user opts in. */
    @JvmStatic
    fun isKeymapEnabled(): Boolean = getKeymap().optBoolean("enabled", false)

    /** Gate for the advanced escape hatch (raw-CAN / shell bindings). Curated
     *  actions run regardless; advanced payloads are refused at dispatch unless
     *  this is true. Defaults false. */
    @JvmStatic
    fun isKeymapAdvancedAllowed(): Boolean = getKeymap().optBoolean("allowAdvanced", false)

    /** Single-vs-double disambiguation window in ms (see KeyMapDispatcher). A hardware
     *  double-tap on a steering-wheel/dash button is slower than a touchscreen one, and
     *  varies by user and button feel, so this is user-tunable. Clamped to a sane
     *  250..1500ms band: below 250 a real double can't land in time; above 1500 a single
     *  tap on a key that also has a double mapped would feel unbearably laggy. Defaults
     *  450ms (a comfortable hardware double-tap measured from the first press). */
    @JvmStatic
    fun getKeymapDoubleTapWindowMs(): Long =
        getKeymap().optLong("doubleTapWindowMs", 450L).coerceIn(250L, 1500L)

    /** The automation config section: {allowShell}. Distinct from the automations
     *  LIST (which Automations.java persists in its own file) — this holds
     *  automation-wide settings toggles. */
    @JvmStatic
    fun getAutomation(): JSONObject = loadConfig().optJSONObject("automation") ?: JSONObject()

    /** Per-action driving-safety guards. Missing keys default ON in DrivingSafetyGuard. */
    @JvmStatic
    fun getDrivingSafety(): JSONObject =
        loadConfig().optJSONObject("drivingSafety") ?: JSONObject()

    /** Gate for the automation ShellAction — DISTINCT from [isKeymapAdvancedAllowed].
     *  Automations fire autonomously on vehicle events (not a deliberate button
     *  press), so arming shell there is a separate, stricter opt-in: enabling the
     *  key-mapping advanced hatch must NOT silently also allow unattended shell in
     *  automations. Lives in the automation section (its toggle is on the
     *  Automations page). Re-checked at fire time in ShellAction.trigger; callers
     *  in the daemon should forceReload() for cross-UID freshness. Defaults false. */
    @JvmStatic
    fun isAutomationShellAllowed(): Boolean = getAutomation().optBoolean("allowShell", false)

    /** Persist the automation-shell gate. Single-key merge on the automation
     *  section (updateValues), off the main looper. */
    @JvmStatic
    fun setAutomationShellAllowed(allow: Boolean): Boolean =
        updateValues("automation", mapOf("allowShell" to allow))

    /** Whether OverDrive's WiFi keep-alive must stand down. User/automation intent and
     *  the hotspot's temporary single-radio guard are independent: changing either one
     *  must never clear the other. Defaults FALSE and fails open on read errors. */
    @JvmStatic
    fun isWifiKeepAliveSuppressed(): Boolean = try {
        val config = loadConfig()
        val userOff =
            config.optJSONObject("radio")?.optBoolean("wifiUserOff", false) ?: false
        val hotspotOwnsSuppression =
            config.optJSONObject("hotspot")
                ?.optBoolean("suppressedByHotspot", false) ?: false
        userOff || hotspotOwnsSuppression
    } catch (t: Throwable) {
        false
    }

    /** Positive user-facing form of the persisted WiFi user/automation intent.
     *  Deliberately ignores the hotspot's temporary suppression marker. */
    @JvmStatic
    fun isWifiAutoEnableEnabled(): Boolean =
        try {
            !(loadConfig().optJSONObject("radio")
                ?.optBoolean("wifiUserOff", false) ?: false)
        } catch (t: Throwable) {
            true
        }

    @JvmStatic
    fun setWifiAutoEnableEnabled(enabled: Boolean): Boolean =
        setWifiKeepAliveSuppressed(!enabled)

    /** Persist user/automation WiFi-off intent only. The hotspot owns its separate
     *  suppressedByHotspot marker. Single-key merge on the "radio" section, off the
     *  main looper (updateValues routes an app-process write to the daemon). */
    @JvmStatic
    fun setWifiKeepAliveSuppressed(suppressed: Boolean): Boolean =
        updateValues("radio", mapOf("wifiUserOff" to suppressed))

    /** Whether the user has explicitly turned mobile data OFF via an automation /
     *  key-mapping radio action. The cellular keep-alive
     *  (AccSentryDaemon.ensureMobileDataEnabled, issue #209) checks this BEFORE
     *  re-running `svc data enable`, so a deliberate "data off" rule is not
     *  auto-re-enabled every parked tick. Defaults FALSE. Fail-open on read error
     *  (returns false → keep-alive resumes), mirroring the WiFi flag above. */
    @JvmStatic
    fun isDataKeepAliveSuppressed(): Boolean =
        try { (loadConfig().optJSONObject("radio")?.optBoolean("dataUserOff", false)) ?: false }
        catch (t: Throwable) { false }

    /** Persist the mobile-data-off suppression flag; set/cleared by the radio action
     *  exactly like the WiFi flag above. */
    @JvmStatic
    fun setDataKeepAliveSuppressed(suppressed: Boolean): Boolean =
        updateValues("radio", mapOf("dataUserOff" to suppressed))

    /** Whether the parked cellular keep-alive is enabled (Surveillance → General).
     *  On some models the data module sleeps a while after the car is switched off;
     *  when this is ON, AccSentryDaemon re-asserts the mobile-data master switch each
     *  parked tick so cellular stays reachable. Defaults FALSE — holding the bearer up
     *  costs battery and data on firmware that doesn't need it, so it is opt-in.
     *  Fail-CLOSED on read error (returns false → no keep-alive), the opposite of the
     *  suppression flag above: a config glitch must never start spending power. */
    @JvmStatic
    fun isMobileDataKeepAliveEnabled(): Boolean =
        try { (loadConfig().optJSONObject("surveillance")?.optBoolean("mobileDataKeepAlive", false)) ?: false }
        catch (t: Throwable) { false }

    /** The hotspot config section — the USER'S INTENT for the WiFi hotspot:
     *  {enabled, ssid, password, dataCapMb, dataUsedBytes, proxySystemWide,
     *   proxyForClients, autoStartBoot, keepAlive, suppressedByHotspot,
     *   warnAck, clientNames}. Live/observed state lives in the separate
     *  "hotspotState" section below so a snapshot publish can never clobber
     *  a user setting (and vice versa). */
    @JvmStatic
    fun getHotspot(): JSONObject = loadConfig().optJSONObject("hotspot") ?: JSONObject()

    /** Merge keys into the hotspot section. Single-key merges preserve siblings,
     *  so independent switches never overwrite each other. Off the main looper. */
    @JvmStatic
    fun updateHotspot(values: Map<String, Any>): Boolean = updateValues("hotspot", values)

    /** Whether the hotspot temporarily suppresses WiFi keep-alive. SoftAP and WiFi-STA
     *  share one radio, so this marker is combined with (but never overwrites) the
     *  user's independent WiFi auto-enable preference. */
    @JvmStatic
    fun didHotspotSuppressWifiKeepAlive(): Boolean =
        try { getHotspot().optBoolean("suppressedByHotspot", false) } catch (t: Throwable) { false }

    /** Route the head unit's OWN traffic through the local proxy (system-wide
     *  http proxy settings). Defaults FALSE. */
    @JvmStatic
    fun isHotspotProxySystemWide(): Boolean =
        try { getHotspot().optBoolean("proxySystemWide", false) } catch (t: Throwable) { false }

    /** Whether the local proxy inbound should be reachable from tethered clients
     *  (bind the AP-facing address instead of loopback). Read by the proxy daemon
     *  when it writes its inbound config. Defaults FALSE → loopback only, which
     *  is the historical behaviour. Fail-closed on any read error: a config
     *  glitch must never widen the listen address. */
    @JvmStatic
    fun isHotspotProxyForClients(): Boolean =
        try { getHotspot().optBoolean("proxyForClients", false) } catch (t: Throwable) { false }

    /** Whether tethered-client traffic should additionally be tunnelled (VLESS)
     *  rather than leaving the SIM directly. Requires proxyForClients, since the
     *  tunnel's encrypted stream egresses through the cellular-bound relay.
     *  Defaults FALSE and fails closed: a config glitch must not silently route a
     *  client's traffic through a remote server. */
    @JvmStatic
    fun isHotspotClientTunnel(): Boolean =
        try { getHotspot().optBoolean("clientTunnel", false) } catch (t: Throwable) { false }

    /** Observed hotspot state published by the app-process owner:
     *  {apState, startedAt, iface, baselineRx, baselineTx, clients, updatedAt,
     *   sessionBytes, lastError}. Read-only for every consumer. */
    @JvmStatic
    fun getHotspotState(): JSONObject = loadConfig().optJSONObject("hotspotState") ?: JSONObject()

    /** Publish an observed-state snapshot. Separate section from [getHotspot] so
     *  the throttled snapshot writer can never race a user settings write. */
    @JvmStatic
    fun updateHotspotState(values: Map<String, Any>): Boolean =
        updateValues("hotspotState", values)

    /** The dataUsage config section: {enabled}. Tracks per-day WiFi/mobile bytes
     *  consumed by Overdrive (app UID + UID-2000 daemons/tunnels) for the
     *  performance page's Data graph. */
    @JvmStatic
    fun getDataUsage(): JSONObject = loadConfig().optJSONObject("dataUsage") ?: JSONObject()

    /** Master switch for data-usage tracking. Defaults FALSE (opt-in): the
     *  sampler thread is only armed on the enable edge, so a disabled feature
     *  reads no /proc/net stats, writes no H2 rows, and schedules no wakeups —
     *  true zero-overhead-when-off, mirroring the keymap FileObserver contract.
     *  Daemon callers should forceReload() first for cross-UID freshness (the
     *  web toggle is written by the app UID, read by the UID-2000 sampler). */
    @JvmStatic
    fun isDataUsageEnabled(): Boolean = getDataUsage().optBoolean("enabled", false)

    /** Persist the data-usage master switch. Single-key merge on the dataUsage
     *  section (updateValues routes an app-process write to the daemon), off the
     *  main looper. */
    @JvmStatic
    fun setDataUsageEnabled(enabled: Boolean): Boolean =
        updateValues("dataUsage", mapOf("enabled" to enabled))

    /** The list of key bindings. Empty array when unset. Never null. */
    @JvmStatic
    fun getKeymapBindings(): org.json.JSONArray =
        getKeymap().optJSONArray("bindings") ?: org.json.JSONArray()

    /** Replace the whole keymap section (enabled/allowAdvanced/bindings). Callers
     *  MUST invoke this off the main looper — updateSection round-trips to the
     *  daemon over localhost. */
    @JvmStatic
    fun setKeymap(keymap: JSONObject): Boolean = updateSection("keymap", keymap)

    /**
     * User-defined quick-control buttons for the dashboard.
     *
     * Each entry is `{ id, label, icon?, action }`, where `action` is the SAME action payload
     * a key binding stores — so a button can do anything a physical key can (a catalog
     * entity, an allowlisted API call, an automation, an action group, or a sequence) and
     * fires through the one existing `/api/keymap/fire` executor. No second actuation path,
     * no second security surface.
     */
    @JvmStatic
    fun getQuickControls(): JSONObject =
        loadConfig().optJSONObject("quickControls") ?: JSONObject()

    /** The user's quick-control buttons. Empty array when unset. Never null. */
    @JvmStatic
    fun getQuickControlButtons(): org.json.JSONArray =
        getQuickControls().optJSONArray("buttons") ?: org.json.JSONArray()

    /** Replace the whole quickControls section. Callers MUST invoke this off the main
     *  looper — updateSection round-trips to the daemon over localhost. */
    @JvmStatic
    fun setQuickControls(quickControls: JSONObject): Boolean =
        updateSection("quickControls", quickControls)

    /**
     * Recording-side dewarp strength (Fitzgibbon division model).
     *
     * Range: 0..100. 0 = off (passthrough — shader stays bit-exact to the
     * legacy mosaic). 100 = maximum rectification.
     *
     * Single shared key used by BOTH the recording (ACC-on dashcam) and
     * surveillance (ACC-off) flows. The UI exposes a slider in each section
     * but they both read/write this key — flipping it in one place applies
     * to the other automatically.
     *
     * Defaults to 0 if absent. Clamped on read so a corrupt config value
     * cannot push the shader out of its safe range.
     */
    @JvmStatic
    fun getRectifyStrength(): Int {
        val raw = getRecording().optInt("rectifyStrength", 0)
        return raw.coerceIn(0, 100)
    }

    /** Set the shared recording/surveillance dewarp strength (0..100). */
    @JvmStatic
    fun setRectifyStrength(strength: Int): Boolean {
        return updateValues("recording", mapOf(
            "rectifyStrength" to strength.coerceIn(0, 100)
        ))
    }

    /**
     * Shared clip segment length in minutes for BOTH the ACC-on dashcam and
     * ACC-off / OEM surveillance flows. The UI exposes the control in each
     * section but both read/write this single key, so changing it in one
     * place applies to the other automatically (mirrors rectifyStrength).
     *
     * Clamped to [MIN_SEGMENT_DURATION_MINUTES..MAX_SEGMENT_DURATION_MINUTES]
     * on read so a corrupt config value can never disable segment rotation.
     */
    @JvmStatic
    fun getSegmentDurationMinutes(): Int {
        val raw = getRecording().optInt("segmentDurationMinutes",
            com.overdrive.app.util.Constants.SEGMENT_DURATION_MINUTES)
        return raw.coerceIn(
            com.overdrive.app.util.Constants.MIN_SEGMENT_DURATION_MINUTES,
            com.overdrive.app.util.Constants.MAX_SEGMENT_DURATION_MINUTES)
    }

    /** Set the shared clip segment length in minutes (clamped to MIN..MAX). */
    @JvmStatic
    fun setSegmentDurationMinutes(minutes: Int): Boolean {
        return updateValues("recording", mapOf(
            "segmentDurationMinutes" to minutes.coerceIn(
                com.overdrive.app.util.Constants.MIN_SEGMENT_DURATION_MINUTES,
                com.overdrive.app.util.Constants.MAX_SEGMENT_DURATION_MINUTES)
        ))
    }
    
    /**
     * Get telegram config section.
     */
    @JvmStatic
    fun getTelegram(): JSONObject {
        return loadConfig().optJSONObject("telegram") ?: JSONObject()
    }
    
    /**
     * Get proximity guard config section.
     */
    @JvmStatic
    fun getProximityGuard(): JSONObject {
        return loadConfig().optJSONObject("proximityGuard") ?: JSONObject()
    }
    
    /**
     * Get telemetry overlay config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getTelemetryOverlay(): JSONObject {
        return loadConfig().optJSONObject("telemetryOverlay") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    // ==================== SECTION SETTERS ====================
    
    /**
     * Update surveillance config section.
     */
    @JvmStatic
    fun setSurveillance(surveillance: JSONObject): Boolean {
        return updateSection("surveillance", surveillance)
    }
    
    /**
     * Update recording config section.
     */
    @JvmStatic
    fun setRecording(recording: JSONObject): Boolean {
        return updateSection("recording", recording)
    }
    
    /**
     * Update streaming config section.
     */
    @JvmStatic
    fun setStreaming(streaming: JSONObject): Boolean {
        return updateSection("streaming", streaming)
    }
    
    /**
     * Update telegram config section.
     */
    @JvmStatic
    fun setTelegram(telegram: JSONObject): Boolean {
        return updateSection("telegram", telegram)
    }
    
    /**
     * Update proximity guard config section.
     */
    @JvmStatic
    fun setProximityGuard(proximityGuard: JSONObject): Boolean {
        return updateSection("proximityGuard", proximityGuard)
    }
    
    /**
     * Update telemetry overlay config section.
     */
    @JvmStatic
    fun setTelemetryOverlay(telemetryOverlay: JSONObject): Boolean {
        return updateSection("telemetryOverlay", telemetryOverlay)
    }

    /**
     * Resolve whether the telemetry overlay should burn-in for a given
     * recording flow: "pano" (ACC-on trips), "surveillance" (ACC-off sentry),
     * or "oemDashcam". The legacy `enabled` key acts as the pano fallback so
     * pre-split installs keep their toggle. Surveillance and OEM dashcam
     * default to off — separate opt-ins (parked burn-in keeps the BYD poll
     * alive, and the OEM front sensor doesn't need a stamp by default).
     */
    @JvmStatic
    fun isTelemetryOverlayEnabledFor(flow: String): Boolean {
        val tel = getTelemetryOverlay()
        return when (flow) {
            "oemDashcam" -> tel.optBoolean("oemDashcamEnabled", false)
            "surveillance" -> tel.optBoolean("surveillanceEnabled", false)
            else /* "pano" */ -> {
                if (tel.has("panoEnabled")) tel.optBoolean("panoEnabled", false)
                else tel.optBoolean("enabled", false)
            }
        }
    }

    /**
     * Canonical config key under `telemetryOverlay.fields` for a flow. Unknown
     * flows map to the pano/ACC-on list.
     */
    private fun fieldsKeyForFlow(flow: String): String = when (flow) {
        "oemDashcam" -> "oemDashcam"
        "surveillance" -> "surveillance"
        else -> "accOn"
    }

    /**
     * The per-flow field selection as a JSON array of stable field keys, or
     * {@code null} if the flow has no explicit selection. A null return MUST be
     * treated by the caller as "use the legacy default" (TelemetryFields does
     * this) — do NOT substitute an empty list, which means "user deselected
     * everything". Each flow's list is independent and never shared.
     */
    @JvmStatic
    fun getTelemetryOverlayFields(flow: String): org.json.JSONArray? {
        val tel = getTelemetryOverlay()
        val fields = tel.optJSONObject("fields") ?: return null
        return fields.optJSONArray(fieldsKeyForFlow(flow))
    }

    /**
     * Persist the per-flow field selection (array of stable field keys). Merges
     * into the nested `fields` object so the other flows' lists are preserved —
     * updateSection only shallow-merges the top-level `telemetryOverlay`, so we
     * read-modify-write the `fields` sub-object here.
     */
    @JvmStatic
    fun setTelemetryOverlayFields(flow: String, fieldKeys: org.json.JSONArray): Boolean {
        val tel = getTelemetryOverlay()
        val fields = tel.optJSONObject("fields") ?: JSONObject()
        fields.put(fieldsKeyForFlow(flow), fieldKeys)
        val delta = JSONObject()
        delta.put("fields", fields)
        return setTelemetryOverlay(delta)
    }

    // ==================== OEM DASHCAM ====================

    /**
     * Get oemDashcam feature-gate section (separate from camera.* which is HAL
     * config). Read fields: disableNativeDvr, bitrateBudget,
     * priorityWhenContended, segmentRotateOffsetMs.
     */
    @JvmStatic
    fun getOemDashcam(): JSONObject {
        return loadConfig().optJSONObject("oemDashcam") ?: JSONObject().apply {
            put("disableNativeDvr", false)
            put("bitrateBudget", 10_000_000)
            put("priorityWhenContended", "pano")
            put("segmentRotateOffsetMs", 30_000)
            // Two independent modes — one per page:
            //   recordingMode      governs OEM behaviour during pano DASHCAM
            //                      recording (cam_*.mp4 flow). Off | Continuous
            //                      | Smart. Smart = follow whatever the pano
            //                      Recording Mode is doing (Continuous /
            //                      Drive Mode / Proximity Guard).
            //   surveillanceMode   governs OEM behaviour during pano
            //                      SURVEILLANCE (event_*.mp4 flow). Off |
            //                      Continuous | Smart. Smart = follow pano
            //                      surveillance motion detection.
            put("recordingMode", "off")
            put("surveillanceMode", "off")
        }
    }

    @JvmStatic
    fun setOemDashcam(oemDashcam: JSONObject): Boolean {
        return updateSection("oemDashcam", oemDashcam)
    }

    /**
     * OS-level package policy the user opted into. These are NOT feature gates —
     * each key records a `pm` state we must re-assert on daemon boot because a
     * firmware OTA re-scans the system partition and undoes it. Read fields:
     * disableTrafficMonitor. Owner: TrafficMonitorPolicy.
     */
    @JvmStatic
    fun getSystemApps(): JSONObject {
        return loadConfig().optJSONObject("systemApps") ?: JSONObject().apply {
            put("disableTrafficMonitor", false)
        }
    }

    /**
     * True iff either OEM Dashcam mode is non-Off. Pipeline lifecycle is
     * OR-gated. Streaming alone does NOT activate recording — that's gated
     * separately by the daemon-side stream router, mirroring how pano
     * keeps its recording and stream encoders independent.
     */
    @JvmStatic
    fun isAnyOemDashcamTriggerEnabled(): Boolean {
        val oem = getOemDashcam()
        // Once either new key is present, it is the authoritative answer.
        // Legacy `enabled` / `surveillance.enabled` are read-only mirrors
        // post-migration and must NEVER override an explicit Off pick.
        if (oem.has("recordingMode") || oem.has("surveillanceMode")) {
            val rec = oem.optString("recordingMode", "off").lowercase()
            val surv = oem.optString("surveillanceMode", "off").lowercase()
            return rec != "off" || surv != "off"
        }
        // Pre-migration legacy schemas only.
        if (oem.optBoolean("enabled", false)) return true
        val legacySurv = oem.optJSONObject("surveillance")
        if (legacySurv != null && legacySurv.optBoolean("enabled", false)) return true
        return false
    }

    @JvmStatic
    fun getOemRecordingMode(): String {
        val oem = getOemDashcam()
        if (oem.has("recordingMode")) return oem.optString("recordingMode", "off").lowercase()
        // Legacy: enabled=true → smart (matched pano mode). enabled=false → off.
        return if (oem.optBoolean("enabled", false)) "smart" else "off"
    }

    @JvmStatic
    fun getOemSurveillanceMode(): String {
        val oem = getOemDashcam()
        if (oem.has("surveillanceMode")) return oem.optString("surveillanceMode", "off").lowercase()
        // Legacy: surveillance.enabled=true → smart (follow pano motion).
        val legacy = oem.optJSONObject("surveillance")
        return if (legacy != null && legacy.optBoolean("enabled", false)) "smart" else "off"
    }

    /**
     * Migrate legacy {@code enabled / surveillance.enabled / accOffMode} →
     * {@code recordingMode / surveillanceMode}. Idempotent — once one of the
     * new keys is present we treat the migration as done.
     */
    @JvmStatic
    fun migrateOemDashcamModes(): Boolean {
        val root = loadConfig()
        val oem = root.optJSONObject("oemDashcam") ?: return false
        if (oem.has("recordingMode") || oem.has("surveillanceMode")) return false
        val delta = JSONObject()
        val legacyEnabled = oem.optBoolean("enabled", false)
        val legacyMode = oem.optString("accOffMode", "off")
        val legacySurv = oem.optJSONObject("surveillance")
        val survOn = legacySurv?.optBoolean("enabled", false) == true
        // Pre-migration semantics: enabled=true recorded during the ACC ON
        // (driving) phase regardless of accOffMode; accOffMode then governed
        // what happened at ACC OFF (off=tear down, smart=event-trigger,
        // continuous=keep recording). Surveillance-side independently captures
        // the parked-window intent from accOffMode + surveillance.enabled.
        //
        // FIX (dvr_ clips with OEM recording "disabled"): map recording-side to
        // "smart" — NOT "continuous". Two reasons: (1) "continuous" silently
        // records dvr_*.mp4 the ENTIRE ACC-ON drive, which surprised users who
        // only ever had the OEM feed enabled for VIEWING; "smart" mirrors the
        // pano dashcam (records only while the user's main recording is active),
        // the least-surprising faithful mapping. (2) It now AGREES with
        // getOemRecordingMode()'s own pre-migration fallback (legacy enabled=true
        // -> "smart", :1060). The old "continuous" mapping made the persisted
        // migrated value disagree with the on-the-fly getter for the same
        // legacy config — a latent split-brain. On a vehicle whose OEM camera id
        // is unset the recording-mode picker is hidden entirely (recording.js
        // gates it on oemDashcamCameraId>=0), so a "continuous" migration was
        // also UN-resettable from the UI: the user saw no toggle yet got dvr_
        // for every drive. "smart" + the camera-id guard in the resolver close
        // that hole.
        if (legacyEnabled) {
            delta.put("recordingMode", "smart")
            // accOffMode=continuous → user wanted parked-window recording too.
            // accOffMode=smart      → user wanted parked-window event clips.
            // accOffMode=off/unset  → user wanted clean ACC OFF teardown,
            //                          unless surveillance.enabled overrides.
            val survFromAccOff = when (legacyMode) {
                "continuous" -> "continuous"
                "smart" -> "smart"
                else -> if (survOn) "smart" else "off"
            }
            delta.put("surveillanceMode", survFromAccOff)
        } else {
            delta.put("recordingMode", "off")
            delta.put("surveillanceMode", if (survOn) "smart" else "off")
        }
        // R8-A #19: copy pano's codec/quality/fps into the oemDashcam
        // section so applyRecordingConfigFromUcm doesn't keep falling
        // back to pano's keys. The fallback was the pre-migration design
        // but now contradicts the doc comment that says OEM "deliberately
        // does NOT read pano's recording.* keys". Migrate once at upgrade
        // time; subsequent picks land directly into oemDashcam via
        // QualitySettingsApiHandler.
        val rec = root.optJSONObject("recording")
        if (rec != null) {
            if (!oem.has("codec") && rec.has("codec")) {
                delta.put("codec", rec.optString("codec"))
            }
            if (!oem.has("recordingQuality") && rec.has("recordingQuality")) {
                delta.put("recordingQuality", rec.optString("recordingQuality"))
            }
            if (!oem.has("fps") && rec.has("fps")) {
                delta.put("fps", rec.optInt("fps"))
            }
        }
        // Null out legacy keys so isAnyOemDashcamTriggerEnabled (and any
        // other reader that hasn't been switched to the new accessors)
        // can't resurrect a stale enabled=true from the pre-migration
        // schema. The nested surveillance.enabled stays as a one-way
        // mirror of surveillanceMode for readers that still consult it
        // (SurveillanceEngineGpu) — but we explicitly write its new
        // value so it can't disagree.
        delta.put("enabled", JSONObject.NULL)
        delta.put("accOffMode", JSONObject.NULL)
        // Mirror the resolved surveillanceMode (post-migration) into the
        // legacy nested boolean so readers that still consult it (e.g.
        // SurveillanceEngineGpu) can't disagree with the new accessor.
        val resolvedSurvMode = delta.optString("surveillanceMode", "off")
        // Clone existing surveillance object (if any) and only mutate `enabled`,
        // matching the POST handler's per-key merge semantics. Without the clone,
        // any future sub-key under `oem.surveillance` would be silently dropped
        // during migration.
        val existingSurv = oem.optJSONObject("surveillance")
        val sOut = if (existingSurv != null) JSONObject(existingSurv.toString()) else JSONObject()
        sOut.put("enabled", "off" != resolvedSurvMode)
        delta.put("surveillance", sOut)
        return updateSection("oemDashcam", delta)
    }

    /**
     * Resolve the effective OEM Dashcam AVMCamera id.
     *
     * Symmetric to pano's default: pano defaults to id=1 (see
     * {@code PanoramicCameraGpu.PHYSICAL_CAMERA_ID = 1}), so OEM defaults
     * to id=0. The two stay opposite by construction. Three rules in order:
     *
     *  1. **Manual override is honored verbatim.** When the user picked a
     *     specific id in the camera-mapping dialog (
     *     {@code oemDashcamManualOverride=true}), this returns that id —
     *     including -1 if they chose "Off". Manual is the authoritative answer.
     *  2. **Auto-infer from pano** when {@code oemDashcamManualOverride=false}:
     *     - pano=0 → OEM=1 (Tang-style, e.g. user manually set pano to 0)
     *     - pano=1 → OEM=0 (Seal/Han, the typical case)
     *  3. **Default to 0** otherwise (pano unprobed, or pano is some other
     *     id we don't have an XOR rule for). Symmetric to pano's id=1
     *     default — out-of-the-box install on Seal/Han works without
     *     forcing the user through the camera-mapping dialog. Tang users
     *     who manually set pano to 0 get OEM=1 via rule 2; if they ALSO
     *     don't set pano manually (pano stays at default 1, which is
     *     wrong for Tang anyway), the user has to fix the pano side first
     *     — no different from the pre-OEM situation.
     *
     * Safety net: even if this returns 0 on a vehicle where id=0 happens to
     * be the pano sensor, {@code OemDashcamPipeline.validateHalDimsOrReject}
     * checks BmmCameraInfo's declared dims and refuses to open anything with
     * an aspect ≥ 2.0 (panoramic strip). The applyLifecycle catch then rolls
     * UCM back to {@code enabled=false} with a {@code lastStartError} the UI
     * surfaces.
     *
     * Caller must ensure the camera section is fresh (forceReload from app UID).
     */
    @JvmStatic
    fun resolveOemDashcamId(): Int {
        val camera = loadConfig().optJSONObject("camera") ?: return -1
        val mode = camera.optString("cameraMode", "")
        if (mode.contains("dilink5", ignoreCase = true) || com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            val oemEnabled = getOemDashcam().optBoolean("enabled", false)
            if (!oemEnabled) {
                return -1
            }
        }
        if (camera.optBoolean("oemDashcamManualOverride", false)) {
            val id = camera.optInt("oemDashcamCameraId", -1)
            val oemEnabled = getOemDashcam().optBoolean("enabled", false)
            return if (oemEnabled && id >= 0) id else -1
        }
        return -1
    }
    
    /**
     * Get trip analytics config section.
     * Defaults to enabled=true (matching TripConfig.DEFAULT_ENABLED and the
     * applyDefaults seed) when the section is absent — the old `false` here
     * disagreed with every other default and would silently disable trips for
     * any future caller that trusted it.
     */
    @JvmStatic
    fun getTripAnalytics(): JSONObject {
        return loadConfig().optJSONObject("tripAnalytics") ?: JSONObject().apply {
            put("enabled", true)
        }
    }
    
    /**
     * Update trip analytics config section.
     */
    @JvmStatic
    fun setTripAnalytics(tripAnalytics: JSONObject): Boolean {
        return updateSection("tripAnalytics", tripAnalytics)
    }

    /**
     * Resolve the active update channel ("alpha" | "braveheart").
     *
     * Source of truth is the runtime-selectable "updates" section. When the
     * section (or its channel field) is absent — every install that pre-dates
     * this setting, including all current users mid-migration — we fall back
     * to [com.overdrive.app.BuildConfig.UPDATE_CHANNEL]. That build-time seed
     * is "alpha", so an un-toggled install resolves to the archive channel,
     * matching the historical single-tag behavior with ZERO config write.
     *
     * Callers that may run in the daemon process (UID 2000) while the web
     * toggle was written by the app (UID 10xxx) — or vice-versa — MUST
     * [forceReload] before reading, since the in-memory config is per-UID.
     * AppUpdater.resolveChannel() does exactly that.
     */
    @JvmStatic
    fun getUpdateChannel(): String {
        val ch = loadConfig().optJSONObject("updates")?.optString("channel", "")
        return if (!ch.isNullOrEmpty()) ch else com.overdrive.app.BuildConfig.UPDATE_CHANNEL
    }

    /**
     * Persist the user-selected update channel.
     *
     * Full-JSON rewrite via [updateSection] (which auto-routes app-UID writes
     * to the daemon over IPC for atomicity) — callers MUST invoke this OFF the
     * main looper (it can block on the IPC round-trip).
     */
    @JvmStatic
    fun setUpdateChannel(channel: String): Boolean {
        return updateSection("updates", JSONObject().put("channel", channel))
    }

    /**
     * Get status-overlay (floating pill) visibility section.
     * Each segment defaults to visible=true so installs that pre-date this
     * setting see no behavior change.
     */
    @JvmStatic
    fun getStatusOverlay(): JSONObject {
        return loadConfig().optJSONObject("statusOverlay") ?: JSONObject().apply {
            put("cameraVisible", true)
            put("tripVisible", true)
        }
    }

    /**
     * Update status-overlay (floating pill) visibility section.
     */
    @JvmStatic
    fun setStatusOverlay(statusOverlay: JSONObject): Boolean {
        return updateSection("statusOverlay", statusOverlay)
    }

    /** Remote voice/message settings shared by web, daemon, and app process. */
    @JvmStatic
    fun getRemoteCommunication(): JSONObject {
        return loadConfig().optJSONObject("remoteCommunication") ?: JSONObject().apply {
            put("voiceEnabled", true)
            put("outputLevel", 70)
            put("outputLevelOverrideEnabled", false)
            put("audioChannel", "media")
            put("listenerEnabled", false)
            put("messagesEnabled", true)
            put("emergencyDisabled", false)
        }
    }

    @JvmStatic
    fun setRemoteCommunication(remoteCommunication: JSONObject): Boolean {
        return updateSection("remoteCommunication", remoteCommunication)
    }
    
    /**
     * Get BYD Cloud config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getBydCloud(): JSONObject {
        return loadConfig().optJSONObject("bydCloud") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    /**
     * Update BYD Cloud config section.
     */
    @JvmStatic
    fun setBydCloud(bydCloud: JSONObject): Boolean {
        return updateSection("bydCloud", bydCloud)
    }

    /**
     * Get Cloudflared config section.
     */
    @JvmStatic
    fun getCloudflared(): JSONObject {
        return loadConfig().optJSONObject("cloudflared") ?: JSONObject().apply {
            put("isPaid", false)
            put("token", "")
        }
    }

    /**
     * Update Cloudflared config section.
     */
    @JvmStatic
    fun setCloudflared(cloudflared: JSONObject): Boolean {
        return updateSection("cloudflared", cloudflared)
    }

    /**
     * Get vehicle appearance config section (selected 3D model + body color +
     * drive-side layout). `driveSide` is "lhd" or "rhd" and decides which
     * physical front door each BYD HAL door-area code maps to in
     * notifications. Default "rhd" because the field-tested L↔R swap in
     * DoorEventNotifier was calibrated against RHD Sealion/Atto/Seal trims.
     */
    @JvmStatic
    fun readVehicleNominalKwhStrict(): Double {
        val config = try {
            withConfigFileLock {
                val file = File(CONFIG_PATH)
                if (!file.exists()) {
                    null
                } else {
                    if (file.length() <= 0L) {
                        throw ConfigReadUnavailableException(
                            "Config file is temporarily empty"
                        )
                    }
                    JSONObject(readLiveConfigText(file))
                }
            }
        } catch (e: ConfigLockUnavailableException) {
            throw IllegalStateException(
                "Authoritative config lock is unavailable", e
            )
        }
        if (config == null) return 0.0

        val vehicle = config.optJSONObject("vehicle") ?: return 0.0
        if (!vehicle.has("nominalKwh") || vehicle.isNull("nominalKwh")) {
            return 0.0
        }
        val nominal = try {
            vehicle.getDouble("nominalKwh")
        } catch (e: Exception) {
            throw IllegalStateException(
                "vehicle.nominalKwh is not numeric", e
            )
        }
        if (nominal.isNaN() || nominal.isInfinite() || nominal < 0.0) {
            throw IllegalStateException(
                "vehicle.nominalKwh is invalid: $nominal"
            )
        }
        return nominal
    }

    @JvmStatic
    fun getVehicle(): JSONObject {
        val stored = loadConfig().optJSONObject("vehicle")
        if (stored != null) {
            // Backfill driveSide on configs written before this field existed
            // so call sites can read it unconditionally without a default.
            if (!stored.has("driveSide")) stored.put("driveSide", "rhd")
            if (!stored.has("modelSource")) {
                stored.put(
                    "modelSource",
                    VehicleModelSelection.normalizeSource(
                        null, stored.optString("modelId", "")
                    )
                )
            }
            return stored
        }
        val defaultModel = if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) "sealion7" else "seal"
        return JSONObject().apply {
            put("modelId", defaultModel)
            put("modelSource", VehicleModelSelection.SOURCE_UNSET)
            put("color", "#E8E8EC")
            put("driveSide", "rhd")
        }
    }

    // ==================== TYRE PRESSURE THRESHOLDS ====================
    //
    // User-configurable over/under pressure limits, per axle. Before this
    // section the limits were hardcoded in THREE places that did not even
    // agree with each other (BydDataCollector kPa constants for
    // notifications, vehicle-control.js PSI literals for the corner
    // callouts, and the launcher widget's bar band), so a user running
    // non-stock tyre placard pressures got wrong alerts with no way to fix it.
    //
    // Canonical unit is kPa (integer) — the unit the BYD TPMS HAL reports, so
    // no rounding drift is introduced on the comparison path. The UI converts
    // for display.
    //
    // `low` is the under-pressure WARN floor and `high` the over-pressure WARN
    // ceiling; `criticalLow` is the shared deflated/CRITICAL threshold.
    const val TYRE_LOW_DEFAULT_KPA = 234        // ~34 PSI
    const val TYRE_HIGH_DEFAULT_KPA = 310       // ~45 PSI
    const val TYRE_CRITICAL_LOW_DEFAULT_KPA = 152 // ~22 PSI (deflated)
    // Clamp bounds. Wide enough for anything from a low-pressure spare to a
    // commercial-load rear axle, tight enough that a fat-fingered or unit-
    // confused entry (e.g. "35" meaning PSI) can't disable alerting entirely.
    const val TYRE_KPA_MIN = 80
    const val TYRE_KPA_MAX = 600

    /**
     * Tyre pressure alarm thresholds in kPa.
     *
     * Schema (all ints, kPa):
     *   { "frontLow": 234, "frontHigh": 310,
     *     "rearLow": 234,  "rearHigh": 310,
     *     "criticalLow": 152 }
     *
     * Missing keys are backfilled with the shipped defaults so every call site
     * can read unconditionally. Values are NOT clamped here — see
     * [getTyreThresholds] for the clamped, invariant-checked accessor that
     * consumers should use.
     */
    @JvmStatic
    fun getTyres(): JSONObject {
        // Build a FRESH object rather than back-filling the one on the config.
        // loadConfig() hands back the live cachedConfig, and optJSONObject
        // returns the nested reference inside it — so put()ing defaults there
        // mutates process-wide shared state. That is reached concurrently by the
        // TPMS poll loop, the HTTP worker pool, and the launcher/vehicle-control
        // endpoints, and org.json.JSONObject is HashMap-backed and not
        // thread-safe (torn map / ConcurrentModificationException on a racing
        // reader). It is also the NORMAL case, not an edge one: a POST persists
        // only the keys it changed, so the stored section is routinely sparse.
        val stored = loadConfig().optJSONObject("tyres")
        val out = JSONObject()
        out.put("frontLow", optIntOr(stored, "frontLow", TYRE_LOW_DEFAULT_KPA))
        out.put("frontHigh", optIntOr(stored, "frontHigh", TYRE_HIGH_DEFAULT_KPA))
        out.put("rearLow", optIntOr(stored, "rearLow", TYRE_LOW_DEFAULT_KPA))
        out.put("rearHigh", optIntOr(stored, "rearHigh", TYRE_HIGH_DEFAULT_KPA))
        out.put("criticalLow", optIntOr(stored, "criticalLow", TYRE_CRITICAL_LOW_DEFAULT_KPA))
        return out
    }

    /** Read-only int lookup that tolerates a null section. */
    private fun optIntOr(section: JSONObject?, key: String, default: Int): Int =
        if (section == null) default else section.optInt(key, default)

    /**
     * Clamped, self-consistent tyre thresholds. Use this — not [getTyres] — on
     * any comparison path.
     *
     * Guarantees, so a hand-edited or partially-written config can never
     * produce an un-alertable or permanently-alerting vehicle:
     *  - every value inside [TYRE_KPA_MIN]..[TYRE_KPA_MAX]
     *  - per axle, `high` > `low` (a crossed pair falls back to the defaults
     *    for that axle rather than silently inverting the comparison)
     *  - `criticalLow` <= the lower of the two axle lows (a criticalLow above
     *    a low would make the WARN band unreachable)
     */
    @JvmStatic
    fun getTyreThresholds(): JSONObject {
        val t = getTyres()
        fun clamp(key: String, default: Int): Int =
            t.optInt(key, default).coerceIn(TYRE_KPA_MIN, TYRE_KPA_MAX)

        var frontLow = clamp("frontLow", TYRE_LOW_DEFAULT_KPA)
        var frontHigh = clamp("frontHigh", TYRE_HIGH_DEFAULT_KPA)
        var rearLow = clamp("rearLow", TYRE_LOW_DEFAULT_KPA)
        var rearHigh = clamp("rearHigh", TYRE_HIGH_DEFAULT_KPA)
        if (frontHigh <= frontLow) {
            frontLow = TYRE_LOW_DEFAULT_KPA
            frontHigh = TYRE_HIGH_DEFAULT_KPA
        }
        if (rearHigh <= rearLow) {
            rearLow = TYRE_LOW_DEFAULT_KPA
            rearHigh = TYRE_HIGH_DEFAULT_KPA
        }
        val criticalLow = clamp("criticalLow", TYRE_CRITICAL_LOW_DEFAULT_KPA)
            .coerceAtMost(minOf(frontLow, rearLow))

        return JSONObject().apply {
            put("frontLow", frontLow)
            put("frontHigh", frontHigh)
            put("rearLow", rearLow)
            put("rearHigh", rearHigh)
            put("criticalLow", criticalLow)
        }
    }

    /** Update the tyre threshold section (merges; siblings preserved). */
    @JvmStatic
    fun setTyres(tyres: JSONObject): Boolean {
        return updateSection("tyres", tyres)
    }

    // Tyre pressure DISPLAY unit — presentation only. Stored values and every
    // comparison path stay kPa (see the threshold docs above); this pref just
    // decides how UI layers and notification text render a reading.
    // "psi" is the shipped default because that is what the vehicle-control
    // callouts and the dashboard glance historically displayed.
    const val TYRE_PRESSURE_UNIT_DEFAULT = "psi"

    /**
     * The user's tyre-pressure display unit: "kpa", "psi" or "bar".
     * Stored under tyres.pressureUnit; anything unrecognised (absent key,
     * hand-edited config, older build) falls back to the default rather
     * than leaking an invalid token to formatters.
     */
    @JvmStatic
    fun getTyrePressureUnit(): String {
        val stored = loadConfig().optJSONObject("tyres")
            ?.optString("pressureUnit", "") ?: ""
        return when (stored.lowercase()) {
            "kpa", "psi", "bar" -> stored.lowercase()
            else -> TYRE_PRESSURE_UNIT_DEFAULT
        }
    }

    /**
     * Physical vehicle model selected by the user or a future verified
     * detector. Returns null when modelId is only the renderer fallback.
     */
    @JvmStatic
    fun getSelectedVehicleModelId(): String? {
        val vehicle = getVehicle()
        return VehicleModelSelection.resolvedModelId(
            vehicle.optString("modelId", ""),
            vehicle.optString("modelSource", "")
        )
    }

    /**
     * Critical model-lineage read. Null means the durable config genuinely has
     * no resolved selection; lock, byte-read, shape, and parse failures throw.
     */
    @JvmStatic
    fun getSelectedVehicleModelIdStrict(): String? {
        val root = readDurableConfigStrict()
        if (!root.has("vehicle") || root.isNull("vehicle")) return null
        val rawVehicle = root.opt("vehicle")
        if (rawVehicle !is JSONObject) {
            throw IllegalStateException("Durable vehicle config is not an object")
        }
        if (!rawVehicle.has("modelId") || rawVehicle.isNull("modelId")) return null
        val rawModelId = rawVehicle.opt("modelId")
        if (rawModelId !is String) {
            throw IllegalStateException("vehicle.modelId is not text")
        }
        val rawSource = if (!rawVehicle.has("modelSource")
                || rawVehicle.isNull("modelSource")) {
            ""
        } else {
            val source = rawVehicle.opt("modelSource")
            if (source !is String) {
                throw IllegalStateException("vehicle.modelSource is not text")
            }
            source
        }
        return VehicleModelSelection.resolvedModelId(rawModelId, rawSource)
    }

    /**
     * Update vehicle appearance config section.
     */
    @JvmStatic
    fun setVehicle(vehicle: JSONObject): Boolean {
        return updateSection("vehicle", vehicle)
    }

    /**
     * Web-shell appearance preference (theme picker shipped in the WebView
     * pages). Stored separately from the Android-shell theme so a
     * Telegram-bot user accessing the tunnel can pick their own preference
     * without touching the Android side. Default: "dark".
     *
     * Schema:
     *   { "theme": "dark" | "light" | "auto",
     *     "locale": "en" | "de" | … | "auto" }
     *
     * `locale` is stored here (not in LocaleManager) so the web-side
     * language picker doesn't cross-pollinate the Android app's locale.
     * Survives tunnel-URL changes (each new zrok session is a fresh
     * origin, so localStorage alone is not enough). Default: "auto"
     * (the runtime falls back to navigator.language).
     */
    @JvmStatic
    fun getAppearance(): JSONObject {
        return loadConfig().optJSONObject("appearance") ?: JSONObject().apply {
            put("theme", "dark")
            put("locale", "auto")
        }
    }

    @JvmStatic
    fun setAppearance(appearance: JSONObject): Boolean {
        return updateSection("appearance", appearance)
    }

    /**
     * Geocoding (place-name tagging) section. Per-flow split so dashcam
     * and surveillance recordings can be tagged independently.
     *
     * Schema:
     *   recording: { enabled (bool, default false),
     *                allowOnline (bool, default false) }
     *   surveillance: { enabled (bool, default false),
     *                   allowOnline (bool, default false) }
     *   advanced: { customNominatimBase (string, default ""),
     *               nominatimCooldownUntilMs (long, default 0) }
     *
     * Why per-flow: a user driving a road-trip wants dashcam clips tagged
     * with the cities they passed through, but the same user parking at
     * home overnight may NOT want sentry clips tagged with their home
     * address (especially if shared via Telegram pushes). Per-flow toggles
     * give that distinction; a shared "advanced" sub-section keeps
     * power-user knobs (custom URL + cooldown state) singletons.
     *
     * The resolver picks {@code recording.allowOnline} for normal/proximity
     * recordings and {@code surveillance.allowOnline} for sentry events.
     * Cache + rate limiter are process-shared (one rate budget, one cache).
     */
    @JvmStatic
    fun getGeocoding(): JSONObject {
        return loadConfig().optJSONObject("geocoding") ?: JSONObject().apply {
            put("recording", JSONObject().apply {
                put("enabled", false)
                put("allowOnline", false)
            })
            put("surveillance", JSONObject().apply {
                put("enabled", false)
                put("allowOnline", false)
            })
            put("advanced", JSONObject().apply {
                put("customNominatimBase", "")
                put("nominatimCooldownUntilMs", 0L)
            })
        }
    }

    @JvmStatic
    fun setGeocoding(geocoding: JSONObject): Boolean {
        return updateSection("geocoding", geocoding)
    }

    /**
     * Convenience: query whether a given flow ("recording" or "surveillance")
     * is enabled. Falls back to {@code false} on any read failure so the
     * recorder's hot path is fail-closed.
     */
    @JvmStatic
    fun isGeocodingEnabledForFlow(flow: String): Boolean {
        try {
            val geo = loadConfig().optJSONObject("geocoding") ?: return false
            val sec = geo.optJSONObject(flow) ?: return false
            return sec.optBoolean("enabled", false)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * Companion to [isGeocodingEnabledForFlow] for the online tier gate.
     */
    @JvmStatic
    fun isGeocodingOnlineAllowedForFlow(flow: String): Boolean {
        try {
            val geo = loadConfig().optJSONObject("geocoding") ?: return false
            val sec = geo.optJSONObject(flow) ?: return false
            return sec.optBoolean("allowOnline", false)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * Native-shell preferences. Today this carries `locale` for the Android
     * UI's language picker — kept separate from `appearance.locale` (which
     * is the WebView-only locale) so a tunnel-side picker doesn't change
     * the in-car native shell's language and vice versa.
     *
     * Schema: {
     *   "locale": "<bcp47>" | "auto",
     *   "screenshotPrivacyMode": boolean
     * }
     *
     * The legacy file at /data/local/tmp/.overdrive/locale was unreliable
     * because the app UID can't `mkdir` under /data/local/tmp/, so writes
     * from the picker silently failed and the language reverted on next
     * cold start.
     */
    @JvmStatic
    fun getNativeShell(): JSONObject {
        return loadConfig().optJSONObject("nativeShell") ?: JSONObject()
    }

    @JvmStatic
    fun setNativeShell(nativeShell: JSONObject): Boolean {
        return updateSection("nativeShell", nativeShell)
    }

    /**
     * Whether screenshot privacy masking is active in both the native shell
     * and daemon-served web UI. This lives in the unified native-shell section
     * so the app and shell daemon see one persisted value across processes.
     */
    @JvmStatic
    fun isScreenshotPrivacyModeEnabled(): Boolean {
        return getNativeShell().optBoolean("screenshotPrivacyMode", false)
    }

    @JvmStatic
    fun setScreenshotPrivacyModeEnabled(enabled: Boolean): Boolean {
        return setNativeShell(JSONObject().put("screenshotPrivacyMode", enabled))
    }


    /**
     * If we're NOT the shell daemon, forward this write to the daemon over the
     * localhost IPC socket so it lands via the daemon's atomic tmp+rename path
     * instead of requiring an app process write in sticky storage.
     *
     * Returns:
     *   - true/false  — the daemon applied (or rejected) the write; this is the
     *                   authoritative result and the caller should NOT also
     *                   write locally. On true we forceReload() so the next
     *                   app-side read re-parses the daemon-written bytes.
     *   - null        — routing did not apply (we ARE the daemon, or the daemon
     *                   is unreachable). The caller may try a local write, but
     *                   it is allowed to run only after acquiring the stable
     *                   .lock inode; otherwise it returns false for retry.
     *
     * The socket I/O runs OUTSIDE the updateSection/updateValues monitor (the
     * caller invokes this before entering synchronized(this)) so a slow or
     * stalled localhost round-trip never blocks app-process readers, all of
     * which funnel through loadConfig()'s synchronized(this).
     *
     * `command` is "UPDATE_SECTION" or "UPDATE_VALUES"; `payloadKey` is "data"
     * or "values" respectively. UPDATE_SECTION may carry the private
     * [REPLACE_SECTION_MARKER], which the daemon applies as a whole-section
     * replacement under its normal lock. Callers MUST already be off the UI looper
     * (updateSection/updateValues are documented off-looper-only), so the
     * blocking socket call inherits that contract.
     */
    private fun routeWriteIfApp(
        command: String,
        section: String,
        payloadKey: String,
        payload: JSONObject
    ): Boolean? {
        // Shell daemon (UID 2000) writes locally — it can atomic-rename.
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return null

        var socket: Socket? = null
        var requestMayHaveBeenSent = false
        return try {
            socket = Socket()
            socket.connect(
                java.net.InetSocketAddress(InetAddress.getByName(DAEMON_IPC_HOST), DAEMON_IPC_PORT),
                IPC_CONNECT_TIMEOUT_MS
            )
            socket.soTimeout = IPC_READ_TIMEOUT_MS
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val req = JSONObject()
                .put("command", command)
                .put("section", section)
                .put(payloadKey, payload)
            // From this point onward, any transport failure is ambiguous: the
            // daemon may commit even if this process never receives its ack.
            requestMayHaveBeenSent = true
            writer.println(req.toString())
            if (writer.checkError()) {
                throw IOException("IPC request write failed")
            }
            val line = reader.readLine()
                ?: return reconcileAmbiguousIpcWrite(
                    command,
                    section,
                    payload,
                    "connection closed before acknowledgment"
                )
            val ok = JSONObject(line).optBoolean("success", false)
            if (ok) {
                // The daemon rewrote the file atomically in its own process.
                // Drop our stale cache so the next read re-parses its bytes
                // (honors the cross-UID forceReload-before-read invariant).
                val fresh = forceReload()
                // Keep the APP-PRIVATE .bak current with this daemon-applied
                // write. CRITICAL: this is the COMMON path (daemon up), and it
                // used to leave the app-private copy frozen at the last
                // daemon-DOWN write / migration seed — so the "double-source"
                // recovery was single-source in practice. Refresh it from the
                // bytes the daemon just wrote (which carry the daemon-bumped
                // configSeq) so the app-private .bak tracks live+sticky and can
                // genuinely back-stop a torn/stale sticky .bak during an OTA.
                // writeAppPrivateBackup is app-UID-only (daemon-skip) and atomic
                // in the app's own dir; best-effort, never fails the write.
                writeAppPrivateBackup(fresh)
            } else {
                Log.w(TAG, "IPC $command for '$section' returned success=false")
            }
            ok
        } catch (e: Exception) {
            if (requestMayHaveBeenSent) {
                reconcileAmbiguousIpcWrite(
                    command,
                    section,
                    payload,
                    e.message ?: e.javaClass.simpleName
                )
            } else {
                // Connect/setup failed before any request bytes could be sent.
                Log.w(TAG, "IPC $command unreachable (${e.message}); " +
                    "trying stable-lock local fallback")
                null
            }
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private data class MutationStamp(
        val origin: String,
        val sequence: Long
    )

    /**
     * Preserve an IPC-forwarded stamp, or allocate one for a new local request.
     * The same stamp follows an ambiguous request into its local fallback.
     */
    private fun ensureMutationStamp(payload: JSONObject): JSONObject {
        val existing = mutationStamp(payload)
        if (existing != null) return payload
        payload.remove(MUTATION_ORIGIN_MARKER)
        payload.remove(MUTATION_SEQUENCE_MARKER)
        val next = processMutationSequence.updateAndGet {
            if (it >= Long.MAX_VALUE - 1L) Long.MAX_VALUE else it + 1L
        }
        payload.put(MUTATION_ORIGIN_MARKER, processMutationOrigin)
        payload.put(MUTATION_SEQUENCE_MARKER, next)
        return payload
    }

    private fun mutationStamp(payload: JSONObject): MutationStamp? {
        val origin = payload.optString(MUTATION_ORIGIN_MARKER, "")
        val sequence = payload.optLong(MUTATION_SEQUENCE_MARKER, 0L)
        return if (origin.isNotEmpty() && sequence > 0L) {
            MutationStamp(origin, sequence)
        } else {
            null
        }
    }

    private fun stripMutationStamp(payload: JSONObject): MutationStamp? {
        val stamp = mutationStamp(payload)
        payload.remove(MUTATION_ORIGIN_MARKER)
        payload.remove(MUTATION_SEQUENCE_MARKER)
        return stamp
    }

    private fun appliedMutationSequence(
        config: JSONObject,
        section: String,
        key: String,
        stamp: MutationStamp
    ): Long {
        val sectionClock = config.optJSONObject(MUTATION_CLOCKS_KEY)
            ?.optJSONObject(stamp.origin)
            ?.optJSONObject(section)
            ?: return 0L
        val replacementSequence =
            sectionClock.optLong("*", 0L).coerceAtLeast(0L)
        if (key != "*") {
            return maxOf(
                replacementSequence,
                sectionClock.optLong(key, 0L).coerceAtLeast(0L)
            )
        }

        // A whole-section replacement dominates every older key mutation, and
        // any newer key mutation dominates an older delayed replacement.
        var newest = replacementSequence
        val keys = sectionClock.keys()
        while (keys.hasNext()) {
            newest = maxOf(
                newest,
                sectionClock.optLong(keys.next(), 0L).coerceAtLeast(0L)
            )
        }
        return newest
    }

    private fun recordMutationApplied(
        config: JSONObject,
        section: String,
        keys: List<String>,
        stamp: MutationStamp
    ) {
        val clocks = config.optJSONObject(MUTATION_CLOCKS_KEY) ?: JSONObject()
        val originClock = clocks.optJSONObject(stamp.origin) ?: JSONObject()
        val sectionClock = originClock.optJSONObject(section) ?: JSONObject()
        for (key in keys) {
            sectionClock.put(key, stamp.sequence)
        }
        originClock.put(section, sectionClock)
        clocks.put(stamp.origin, originClock)
        config.put(MUTATION_CLOCKS_KEY, clocks)
    }

    /**
     * Resolve every post-send transport failure against durable state before a
     * caller is allowed to attempt the idempotent local fallback. This covers
     * EOF, read timeouts, connection resets, malformed responses, and request
     * write failures where PrintWriter cannot prove that zero bytes were sent.
     */
    private fun reconcileAmbiguousIpcWrite(
        command: String,
        section: String,
        payload: JSONObject,
        failure: String
    ): Boolean? {
        repeat(IPC_RECONCILE_ATTEMPTS) { attempt ->
            val present = try {
                // If the daemon is still committing, its lock wins and this
                // read waits for the rename. If this reader wins first, release
                // and retry so the queued daemon request gets a turn.
                withConfigFileLock {
                    deltaPresentOnDisk(section, payload)
                }
            } catch (_: ConfigLockUnavailableException) {
                deltaPresentOnDisk(section, payload)
            }
            if (present) {
                Log.w(TAG, "IPC $command acknowledgment ambiguous ($failure), but " +
                    "the delta is on disk; treating as applied")
                val fresh = forceReload()
                writeAppPrivateBackup(fresh)
                return true
            }
            if (attempt + 1 < IPC_RECONCILE_ATTEMPTS) {
                try {
                    Thread.sleep(IPC_RECONCILE_DELAY_MS * (attempt + 1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        Log.w(TAG, "IPC $command acknowledgment ambiguous ($failure) and " +
            "the delta remained absent after reconciliation; trying " +
            "stable-lock local fallback")
        return null
    }

    /**
     * Best-effort check: are ALL keys of [payload] already present with the
     * expected values inside [section] of the on-disk config? Used by
     * [routeWriteIfApp] when the daemon ack was lost — if the daemon already
     * applied the write atomically we can skip a redundant local write. Reads
     * fresh from disk (the daemon write, if any, just
     * landed). Conservative: any mismatch / read failure returns false so the
     * caller falls back to the local write rather than dropping a real change.
     */
    private fun deltaPresentOnDisk(section: String, payload: JSONObject): Boolean {
        return try {
            val cf = File(CONFIG_PATH)
            if (!cf.exists() || cf.length() == 0L) return false
            val onDisk = JSONObject(cf.readText())
            val stamp = mutationStamp(payload)
            if (section == ROOT_PROMOTION_SECTION
                    && payload.optBoolean(ROOT_PROMOTION_MARKER, false)) {
                return rootPromotionPresentOnDisk(onDisk, payload)
            }
            val sec = onDisk.optJSONObject(section) ?: return false
            if (payload.optBoolean(REPLACE_SECTION_MARKER, false)) {
                if (stamp != null
                        && appliedMutationSequence(
                            onDisk, section, "*", stamp
                        ) >= stamp.sequence) {
                    return true
                }
                val expected = JSONObject(payload.toString())
                expected.remove(REPLACE_SECTION_MARKER)
                expected.remove(MUTATION_ORIGIN_MARKER)
                expected.remove(MUTATION_SEQUENCE_MARKER)
                if (sec.length() != expected.length()) return false
                val expectedKeys = expected.keys()
                while (expectedKeys.hasNext()) {
                    val key = expectedKeys.next()
                    if (!sec.has(key) || sec.get(key).toString() != expected.get(key).toString()) {
                        return false
                    }
                }
                return true
            }
            val keys = payload.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == MUTATION_ORIGIN_MARKER
                        || k == MUTATION_SEQUENCE_MARKER) continue
                if (stamp != null
                        && appliedMutationSequence(
                            onDisk, section, k, stamp
                    ) >= stamp.sequence) {
                    continue
                }
                if (!sec.has(k)) return false
                // Compare by string form — JSONObject lacks deep value equals,
                // and the wire payload round-trips through JSON anyway.
                if (sec.get(k).toString() != payload.get(k).toString()) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun rootPromotionPresentOnDisk(
        onDisk: JSONObject,
        payload: JSONObject
    ): Boolean {
        val expected = JSONObject(payload.toString())
        expected.remove(ROOT_PROMOTION_MARKER)
        // The daemon bumps configSeq while applying a promotion. A still newer
        // sequence means either that promotion committed or a later valid peer
        // write superseded it; in both cases replaying the root backup would
        // regress durable state.
        if (seqOf(onDisk) > seqOf(expected)) return true

        var expectedCount = 0
        val expectedKeys = expected.keys()
        while (expectedKeys.hasNext()) {
            val key = expectedKeys.next()
            if (key == SEQ_KEY || key == "lastModified") continue
            expectedCount++
            if (!onDisk.has(key)
                    || onDisk.get(key).toString()
                        != expected.get(key).toString()) {
                return false
            }
        }
        var actualCount = 0
        val actualKeys = onDisk.keys()
        while (actualKeys.hasNext()) {
            val key = actualKeys.next()
            if (key != SEQ_KEY && key != "lastModified") actualCount++
        }
        return actualCount == expectedCount
    }

    /**
     * Update a specific section of the config.
     */
    @JvmStatic
    fun updateSection(section: String, data: JSONObject): Boolean {
        // Detach nested JSON values from the caller before either IPC or local
        // publication. The local path may retain this object in cachedConfig
        // after a successful save.
        val routedPayload = JSONObject(data.toString())
        if (section == ROOT_PROMOTION_SECTION) {
            routeWriteIfApp("UPDATE_SECTION", section, "data", routedPayload)
                ?.let { return it }
            return applyRootPromotion(routedPayload)
        }
        ensureMutationStamp(routedPayload)
        // App-process writes reroute to the daemon for atomic durability; a
        // non-null result is authoritative (no local write). See routeWriteIfApp.
        routeWriteIfApp("UPDATE_SECTION", section, "data", routedPayload)?.let { return it }
        val replace = routedPayload.optBoolean(REPLACE_SECTION_MARKER, false)
        routedPayload.remove(REPLACE_SECTION_MARKER)
        val mutationStamp = stripMutationStamp(routedPayload)
        val payload = routedPayload
        // Cross-process lock + fresh re-read so a peer daemon JVM's just-
        // committed section isn't dropped by a stale-snapshot merge.
        // INVARIANT (audit Jun 2026): config-change listeners run INSIDE this OS
        // file lock (here + saveConfig's notifyListeners("all")), so they MUST be
        // non-blocking — a listener that does synchronous I/O would stall every
        // peer daemon's config write (they block acquiring the FileLock). Current
        // listeners comply (RoadSenseController hops to a short thread;
        // GpuSurveillancePipeline.rectifyConfigListener is a fast in-memory
        // setter). Keep new listeners non-blocking or dispatch them off-thread.
        return withConfigMutationLock("Section '$section' update") {
            val config = loadIsolatedConfigForMutation()
            // A replacement is used only by sections whose payload is a
            // complete authoritative snapshot. All other writes retain the
            // established per-key merge behavior.
            val existing = if (replace) JSONObject() else config.optJSONObject(section) ?: JSONObject()
            val appliedKeys = ArrayList<String>()
            if (replace && mutationStamp != null
                    && appliedMutationSequence(
                        config, section, "*", mutationStamp
                    ) >= mutationStamp.sequence) {
                return@withConfigMutationLock true
            }
            val keys = payload.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!replace && mutationStamp != null
                        && appliedMutationSequence(
                            config, section, key, mutationStamp
                        ) >= mutationStamp.sequence) {
                    continue
                }
                existing.put(key, payload.get(key))
                appliedKeys.add(key)
            }
            if (!replace && appliedKeys.isEmpty()) {
                return@withConfigMutationLock true
            }
            config.put(section, existing)
            if (mutationStamp != null) {
                recordMutationApplied(
                    config,
                    section,
                    if (replace) listOf("*") else appliedKeys,
                    mutationStamp
                )
            }
            val success = saveConfig(config)
            if (success) {
                notifyListeners(section, existing)
            }
            success
        }
    }

    private fun applyRootPromotion(payload: JSONObject): Boolean {
        if (!payload.optBoolean(ROOT_PROMOTION_MARKER, false)) return false
        val candidate = JSONObject(payload.toString())
        candidate.remove(ROOT_PROMOTION_MARKER)
        return withConfigMutationLock("App-private root promotion") {
            val current = loadIsolatedConfigForMutation()
            if (seqOf(current) >= seqOf(candidate)) {
                // A peer caught up while the request was in flight. Its state is
                // at least as new, so the backup must not overwrite it.
                true
            } else {
                applyDefaults(candidate)
                saveConfig(candidate)
            }
        }
    }

    /**
     * Atomically replace one config section. The existing daemon IPC route is
     * deliberately reused so app-process callers retain atomic persistence.
     * The caller's JSONObject is copied and never mutated.
     */
    @JvmStatic
    fun replaceSection(section: String, data: JSONObject): Boolean {
        val replacement = JSONObject(data.toString())
        replacement.put(REPLACE_SECTION_MARKER, true)
        return updateSection(section, replacement)
    }

    /**
     * Update individual values within a section.
     */
    @JvmStatic
    fun updateValues(section: String, values: Map<String, Any>): Boolean {
        // Reroute to the daemon when not shell UID. Serialize the values map
        // into a JSONObject for the wire; the daemon applies it via updateValues.
        val valuesJson = JSONObject()
        values.forEach { (key, value) -> valuesJson.put(key, value) }
        val payload = ensureMutationStamp(JSONObject(valuesJson.toString()))
        routeWriteIfApp("UPDATE_VALUES", section, "values", payload)?.let { return it }
        val mutationStamp = stripMutationStamp(payload)
        return withConfigMutationLock("Section '$section' values update") {
            val config = loadIsolatedConfigForMutation()
            val sectionObj = config.optJSONObject(section) ?: JSONObject()
            val appliedKeys = ArrayList<String>()

            val keys = payload.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (mutationStamp != null
                        && appliedMutationSequence(
                            config, section, key, mutationStamp
                        ) >= mutationStamp.sequence) {
                    continue
                }
                sectionObj.put(key, payload.get(key))
                appliedKeys.add(key)
            }
            if (appliedKeys.isEmpty()) {
                return@withConfigMutationLock true
            }

            config.put(section, sectionObj)
            if (mutationStamp != null) {
                recordMutationApplied(
                    config, section, appliedKeys, mutationStamp)
            }
            val success = saveConfig(config)
            if (success) {
                notifyListeners(section, sectionObj)
            }
            success
        }
    }
    
    // ==================== CONVENIENCE METHODS ====================
    
    /**
     * Get a specific surveillance value.
     */
    @JvmStatic
    fun getSurveillanceValue(key: String, default: Any): Any {
        return getSurveillance().opt(key) ?: default
    }
    
    /**
     * Get a specific recording value.
     */
    @JvmStatic
    fun getRecordingValue(key: String, default: Any): Any {
        return getRecording().opt(key) ?: default
    }
    
    /**
     * Get a specific proximity guard value.
     */
    @JvmStatic
    fun getProximityGuardValue(key: String, default: Any): Any {
        return getProximityGuard().opt(key) ?: default
    }
    
    /**
     * Check if surveillance is enabled in config (user preference for ACC OFF auto-start).
     */
    @JvmStatic
    fun isSurveillanceEnabled(): Boolean {
        return getSurveillance().optBoolean("surveillanceEnabled", false)
    }
    
    /**
     * Set surveillance enabled state in config.
     */
    @JvmStatic
    fun setSurveillanceEnabled(enabled: Boolean): Boolean {
        return updateValues("surveillance", mapOf("surveillanceEnabled" to enabled))
    }

    /**
     * Surveillance arm mode: "lock" (arm on door-lock, disarm on unlock, with a
     * 60s fallback when lock state is unreadable) or "power" (arm immediately on
     * ACC-off, disarm on ACC-on). Any unrecognized value falls back to "lock".
     * Read by CameraDaemon's ACC-off dispatch and door-lock gate.
     */
    @JvmStatic
    fun getSurveillanceArmMode(): String {
        val mode = getSurveillance().optString("armMode", "power")
        return if (mode == "lock") "lock" else "power"
    }

    /**
     * True iff the user chose the "onOnly" operating mode — i.e. ALL behaviour that
     * runs after the vehicle is turned OFF must be disabled (keep-awake, post-OFF
     * surveillance, revival alarms) so the head unit can sleep. Read by every post-OFF
     * choke point across the daemons and the app process.
     *
     * Reads the SAME mtime-gated [loadConfig] cache that the other surveillance
     * accessors (isSurveillanceEnabled / getSurveillanceArmMode) and the daemon-side
     * isKeepUsbPowerOnAccOff() use, so a Settings toggle applies on the next config
     * re-read / ACC cycle without a daemon restart.
     *
     * FAIL-OPEN to false (full "onAndOff" behaviour) on any error, absence, or a
     * non-string value — a transient read glitch must NEVER silently disable
     * keep-awake or surveillance for an on-and-off user.
     */
    @JvmStatic
    fun isVehicleOnOnlyMode(): Boolean =
        try { getSurveillance().optString("operatingMode", "onAndOff") == "onOnly" }
        catch (t: Throwable) { false }

    // ==================== LISTENERS ====================
    
    @JvmStatic
    fun addListener(listener: ConfigChangeListener) {
        listeners.add(listener)
    }
    
    @JvmStatic
    fun removeListener(listener: ConfigChangeListener) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(section: String, config: JSONObject) {
        listeners.forEach { listener ->
            try {
                listener.onConfigChanged(section, config)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: ${e.message}")
            }
        }
    }
    
    // ==================== UTILITY ====================
    
    private fun createDefaultConfig(): JSONObject {
        val config = JSONObject()
        config.put("surveillance", JSONObject())
        config.put("recording", JSONObject())
        config.put("streaming", JSONObject())
        config.put("telegram", JSONObject())
        config.put("camera", JSONObject())
        config.put("proximityGuard", JSONObject())
        config.put("telemetryOverlay", JSONObject())
        config.put("tripAnalytics", JSONObject())
        config.put("oemDashcam", JSONObject())
        config.put("bydCloud", JSONObject())
        config.put("genAi", JSONObject())
        config.put("geocoding", JSONObject())
        config.put("version", 1)
        config.put("lastModified", System.currentTimeMillis())
        applyDefaults(config)
        return config
    }
    
    /**
     * Force reload from disk (bypasses cache).
     */
    @JvmStatic
    fun forceReload(): JSONObject {
        synchronized(this) {
            cachedConfig = null
            stampFreshness(0)
            return loadConfig()
        }
    }

    // Sibling advisory-lock file for CROSS-PROCESS serialization of the config
    // read-modify-write. synchronized(this) only excludes threads within ONE
    // JVM; multiple UID-2000 daemon JVMs (CameraDaemon, AccSentryDaemon, the
    // cluster projector, etc.) each run their own UnifiedConfigManager and
    // would otherwise interleave loadConfig→merge→atomic-rename, dropping a
    // peer's just-committed section (stale-snapshot lost update). An OS flock
    // held across the whole critical section makes those writers mutually
    // exclude. World-RW so any daemon UID can acquire it.
    private const val LOCK_PATH = "$CONFIG_PATH.lock"

    /**
     * Every writer must lock this one stable inode. The live config inode is
     * replaceable by the daemon's atomic rename and can therefore never be a
     * lock fallback: an app locking the old live inode would not exclude a
     * daemon locking [LOCK_PATH].
     */
    private fun lockTargetFor(): File = File(LOCK_PATH)

    private class ConfigLockUnavailableException(
        message: String,
        cause: Throwable? = null
    ) : IllegalStateException(message, cause)

    /**
     * Run [body] while holding BOTH the in-JVM monitor AND an exclusive OS
     * advisory lock on [LOCK_PATH], so the enclosed read-modify-write is
     * atomic across processes. The body never runs unless the OS lock was
     * acquired. A daemon may create a missing lock file; an app process must
     * defer until the daemon provisions it because the app cannot safely choose
     * any substitute inode.
     */
    // RE-ENTRANCY guard: java.nio FileLock is per-JVM for the whole file, so a
    // second channel.lock() from the same JVM while one is held throws
    // OverlappingFileLockException. loadConfig()'s internal self-heal persists
    // can run while updateSection already holds the lock (updateSection →
    // loadConfigFresh → loadConfig → migrate → saveConfigInternal), so nesting
    // is real. This flag makes withConfigFileLock re-entrant per thread: the
    // outermost holder owns the OS lock; inner calls just run body().
    private val holdingFileLock = ThreadLocal.withInitial { false }

    private fun <T> withConfigFileLock(body: () -> T): T {
        synchronized(this) {
            if (holdingFileLock.get() == true) {
                // Already held by this thread higher in the stack — the OS lock
                // is in force; just run.
                return body()
            }

            val lockFile = lockTargetFor()
            if (android.os.Process.myUid() != SHELL_DAEMON_UID && !lockFile.isFile) {
                throw ConfigLockUnavailableException(
                    "Stable config lock $LOCK_PATH is absent; " +
                        "local mutation must retry after daemon initialization"
                )
            }

            var raf: RandomAccessFile? = null
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                try {
                    raf = RandomAccessFile(lockFile, "rw")
                    channel = raf.channel
                    // Blocking exclusive lock — contention is rare (a few daemons,
                    // sub-ms writes) and a brief block is preferable to a dropped
                    // write. lock() releases on channel/raf close in finally.
                    lock = channel.lock()
                    try {
                        lockFile.setReadable(true, false)
                        lockFile.setWritable(true, false)
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    throw ConfigLockUnavailableException(
                        "Stable config lock $LOCK_PATH could not be acquired: " +
                            (e.message ?: e.javaClass.simpleName),
                        e
                    )
                }

                holdingFileLock.set(true)
                try {
                    return body()
                } finally {
                    holdingFileLock.set(false)
                }
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                try { channel?.close() } catch (_: Exception) {}
                try { raf?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Boolean mutation entry points report a deferred write as false. This
     * preserves their retry contract without ever executing a mutation under
     * only the in-process monitor.
     */
    private fun withConfigMutationLock(
        operation: String,
        body: () -> Boolean
    ): Boolean {
        return try {
            withConfigFileLock(body)
        } catch (e: ConfigLockUnavailableException) {
            Log.w(TAG, "$operation deferred: ${e.message}")
            false
        }
    }

    /**
     * Load-time promotion and repair are optional writes. If locking is not
     * currently possible, retain the valid read result and retry the repair on
     * a later load instead of turning lock unavailability into corruption.
     */
    private fun <T> withConfigFileLockOrNull(
        operation: String,
        body: () -> T
    ): T? {
        return try {
            withConfigFileLock(body)
        } catch (e: ConfigLockUnavailableException) {
            Log.w(TAG, "$operation deferred: ${e.message}")
            null
        }
    }

    /**
     * Persist a config mutated by loadConfig()'s self-heal/migration paths,
     * cross-process-safely. Gated to the daemon (UID 2000): only it can
     * reliably atomic-rename, and routing all real persists through the daemon
     * keeps a single locked writer. A non-daemon (app UID) caller skips the
     * write and keeps the migrated object in memory — the daemon re-persists it
     * on its next locked write, so nothing is lost on disk. Re-entrant: if the
     * caller already holds the file lock (the updateSection path) this nests
     * harmlessly.
     */
    private fun persistSelfHeal(config: JSONObject) {
        if (android.os.Process.myUid() != SHELL_DAEMON_UID) return
        withConfigFileLockOrNull("Config self-heal persistence") {
            saveConfigInternal(config)
        }
    }

    /**
     * Persist the geocoding-schema migration WITHOUT clobbering a peer's
     * concurrently-written section. Daemon-only. Acquires the cross-process
     * lock, RE-READS the current on-disk bytes (which may now carry a peer's
     * new section that wasn't in the snapshot the caller migrated), re-applies
     * the idempotent applyDefaults migration to THOSE bytes, and writes the
     * result. App UID skips (no atomic write available); the daemon migrates +
     * persists on its next load/write. Best-effort — failure just defers the
     * migration to a later load.
     */
    private fun persistMigrationUnderLock(): JSONObject? {
        if (android.os.Process.myUid() != SHELL_DAEMON_UID) return null
        return withConfigFileLockOrNull("Config schema migration") {
            try {
                val cf = File(CONFIG_PATH)
                if (cf.exists()) {
                    val fresh = JSONObject(cf.readText())
                    applyDefaults(fresh)        // idempotent; folds legacy geocoding keys
                    val writeResult = saveConfigInternal(fresh)
                    if (writeResult.committed) {
                        val committed = writeResult.committedConfig ?: fresh
                        cachedConfig = committed
                        stampFreshness(cf.lastModified())
                        Log.i(TAG, "Migrated legacy geocoding schema to nested form (locked re-read)")
                        committed
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Locked migration persist failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Force a fresh re-parse of the on-disk config, bypassing the cache + the
     * 1-second-mtime freshness gate in loadConfig. Used by updateSection/
     * updateValues BEFORE merging so a peer process's same-second write isn't
     * missed (the mtime gate is second-granular on ext4, so two writes in one
     * second look unchanged). Caller holds the monitor (+ ideally the file lock).
     */
    private fun loadConfigFresh(): JSONObject {
        cachedConfig = null
        stampFreshness(0)
        return loadConfig()
    }

    /**
     * Mutations operate on a deep JSON copy. A failed save can mutate this
     * working object freely without leaking uncommitted values through the
     * live cached snapshot returned by getters.
     */
    private fun loadIsolatedConfigForMutation(): JSONObject =
        JSONObject(loadConfigFresh().toString())
    
    /**
     * Get the config file path (for debugging).
     */
    @JvmStatic
    fun getConfigPath(): String = CONFIG_PATH
    
    /**
     * Check if config file exists.
     */
    @JvmStatic
    fun configExists(): Boolean = File(CONFIG_PATH).exists()
    
    /**
     * Get last modified timestamp.
     */
    @JvmStatic
    fun getLastModified(): Long {
        return File(CONFIG_PATH).let { if (it.exists()) it.lastModified() else 0L }
    }
}
