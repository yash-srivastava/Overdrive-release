/**
 * BYD Champ - Recording Settings Module
 * SOTA: Uses unified config for cross-UID access (app UI + web UI sync)
 * SOTA: Storage limits with auto-cleanup (100MB - 100GB internal/SD card)
 * SOTA: Storage type selection (internal vs SD card)
 */

window.BYD = window.BYD || {};

BYD.recording = {
    config: {
        // Single recording quality tier — ECONOMY/STANDARD/HIGH/PREMIUM/MAX.
        // Replaces the legacy parallel recordingBitrate + recordingQuality
        // strings. Server resets to STANDARD on first load post-migration.
        recordingQuality: 'STANDARD',
        // streamingQuality is owned by the camera controller dropdown in
        // index.html — recording settings page no longer renders it.
        recordingCodec: 'H264',
        cameraFps: 15,
        // Server-supplied for UI dynamic rendering (filled by loadConfig):
        cameraFpsActual: null,
        cameraFpsClampNote: null,
        recordingQualityOptions: {},
        activeRecordingEstimate: null,
        nativeResolution: null,
        recordingsLimitMb: 500,
        recordingsStorageType: 'INTERNAL',
        recordingMode: 'NONE',
        proximityGuard: {
            triggerLevel: 'RED',
            preRecordSeconds: 5,
            postRecordSeconds: 10,
            blindSpotTrigger: false
        },
        // OEM dashcam recording mode mirror — 'off' | 'continuous' | 'smart'.
        // Hydrated by loadOemDashcam() and committed via the oem-tab branch
        // in saveSettings() so the picker waits for Apply, not the click.
        oemRecordingMode: 'off',
        // Lens-dewarp strength (Fitzgibbon division model). 0..100, 0=off.
        // Shared with surveillance page via UnifiedConfigManager
        // recording.rectifyStrength. Default 0 here so the dirty-diff
        // baseline is a real number even before loadConfig() fires.
        rectifyStrength: 0,
        // Clip segment length in minutes (2/5/10). Shared with the
        // surveillance page via recording.segmentDurationMinutes — one control
        // governs both ACC-on dashcam and ACC-off surveillance rotation.
        segmentDurationMinutes: 2
    },
    storageInfo: {
        sdCardAvailable: false,
        sdCardPath: null,
        sdCardFreeSpace: 0,
        sdCardTotalSpace: 0,
        usbAvailable: false,
        usbPath: null,
        usbFreeSpace: 0,
        usbTotalSpace: 0,
        // Dynamic per-volume ceilings; server pulls these from live StatFs.
        maxLimitMb: 100000,
        maxLimitMbSdCard: 100000,
        maxLimitMbUsb: 100000,
        // Combined-limit advisory (one entry per targeted volume). See
        // shared/storage-budget.js — rendered, never enforced.
        storageBudget: []
    },
    cdrInfo: null,
    savedConfig: null,
    hasUnsavedChanges: false,
    lastConfigTimestamp: 0,  // Track config file timestamp for sync
    _hydrated: false,
    _writeQueue: Promise.resolve(),
    _cdrReady: false,
    _cdrWritesPending: 0,
    _cdrWriteVersion: 0,
    _cdrTogglePending: false,
    _cdrCleanupPending: false,
    _cdrSaveTimer: null,
    _cdrDirty: {},
    _layoutWritePending: false,
    _layoutWriteVersion: 0,
    _recordingLayout: 'standard',
    _dashcamUseWindshield: false,
    _windshieldAvailable: false,
    _telemetryWriteVersion: 0,
    _audioWritesPending: 0,
    _audioWriteVersion: 0,
    _geocodingWritesPending: 0,
    _geocodingWriteVersion: 0,

    _enqueueWrite(task) {
        const run = () => task();
        const next = this._writeQueue.then(run, run);
        this._writeQueue = next.catch(() => {});
        return next;
    },

    async _postJson(url, body) {
        return this._enqueueWrite(async () => {
            try {
                const resp = await fetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await resp.json();
                if (!resp.ok || !data || data.success !== true) {
                    throw new Error(data && data.error ? data.error : 'Request rejected');
                }
                return data;
            } catch (e) {
                console.warn('Recording settings POST failed (' + url + '):', e);
                return null;
            }
        });
    },

    _postTelemetry(body) {
        this._telemetryWriteVersion++;
        return this._postJson('/api/settings/telemetry-overlay', body);
    },

    async init() {
        // loadConfig already chains into loadStorageSettings (line 245);
        // calling it again here was a redundant /api/settings/storage
        // round-trip on every page load. The remaining six loaders are
        // idempotent reads that don't touch this.config, so they run in
        // parallel — first-paint goes from ~8 sequential RTTs to ~2.
        await this.loadConfig();
        await Promise.all([
            this.loadStorageStats(),
            this.loadTelemetryOverlay(),
            this.loadAudioRecording(),
            this.loadGeocoding(),
            this.loadNativeDvr(),
            this.loadOemDashcam(),
            this.loadRecordingLayout(),
        ]);
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
        this._hydrated = true;
        if (BYD.utils && BYD.utils.unlockSettingsHydration) {
            BYD.utils.unlockSettingsHydration();
        }
        // The hero line is composed from option labels; rebuild it once they are re-translated.
        if (BYD.i18n && BYD.i18n.onChange) BYD.i18n.onChange(() => this.renderHeroSummary());

        // CDR status can recursively scan a slow or unhealthy SD card. Keep
        // its controls gated by _cdrReady instead of holding the whole page.
        if (this.config.recordingsStorageType === 'SD_CARD') {
            this.updateCdrCleanupVisibility();
        }
        
        // Status polling is handled by core.js - no need to duplicate
        
        // Reload config when page becomes visible (user switches back to tab)
        document.addEventListener('visibilitychange', () => {
            // reloadConfig self-gates on hasUnsavedChanges (and falls back to the
            // banner-only refresh when dirty), so no second gate here — returning to a
            // dirty tab should still pick up a peer page's limit change.
            if (document.visibilityState === 'visible') {
                this.reloadConfig();
            }
        });
        
        // SOTA: More frequent config refresh (every 10s) to catch app UI changes quickly
        setInterval(() => {
            // Unconditional: reloadConfig self-gates on hasUnsavedChanges and, when
            // dirty, refreshes only the overcommit banner (which writes no control).
            // Gating here as well would leave a dirty page's banner stale forever.
            this.reloadConfig();
            // Skip 2 of every 3 ticks while the recordings index is down
            // (10s → 30s effective) so a permanently-down index doesn't get
            // polled 6×/min for as long as the page stays open.
            this._statsSkip = (this._statsSkip || 0) + 1;
            if (!this._indexDown || this._statsSkip % 3 === 0) {
                this.loadStorageStats();  // Always refresh storage stats
            }

            // Refresh CDR info if visible
            if (this.config.recordingsStorageType === 'SD_CARD') {
                this.loadCdrConfig();
            }
        }, 10000);

        // Re-evaluate Apply enabled-state when the bottom tab changes —
        // markChanged() reads the active tab id each call so the button
        // reflects only the visible tab's dirty state. Mirroring
        // surveillance.js: also re-hydrate the OEM card on tab activation
        // so the picker never lingers in its hard-coded {aria-busy="true"}
        // dim state if init's first loadOemDashcam was superseded
        // mid-flight (token race) or hit a transient daemon-not-ready.
        var self = this;
        document.addEventListener('ot-tabs:active-changed', function (ev) {
            self.markChanged();
            try {
                if (ev && ev.detail && ev.detail.id === 'oem') {
                    self.loadOemDashcam();
                    self.loadNativeDvr();
                }
            } catch (_) {}
        });
    },
    
    async reloadConfig() {
        // Only reload if no unsaved changes
        if (this.hasUnsavedChanges) {
            // ...but the overcommit advisory still must track PEER pages: it's a
            // function of the other categories' limits, so a dirty page would
            // otherwise keep asserting an overcommit the user already fixed elsewhere
            // (or stay silent about a new one) for as long as it stays dirty. Safe to
            // run while dirty because it writes no control — only the banner.
            this.refreshBudgetOnly();
            return;
        }

        try {
            const resp = await fetch('/api/settings/quality');
            const data = await resp.json();
            if (data.success) {
                // Check if config actually changed (via timestamp)
                const newTimestamp = data.lastModified || 0;
                // Mid-flight guard: this block writes config + re-baselines
                // savedConfig + updateUI() after the await above. A quality/
                // fps/clip edit started during that await would otherwise be
                // clobbered by the server value and its dirty diff erased
                // (graying out Apply). Bail without advancing lastConfigTimestamp
                // so the next reload re-attempts once the edit is saved. Mirrors
                // the guards in the other re-sync blocks and surveillance.js.
                if (newTimestamp > this.lastConfigTimestamp && !this.hasUnsavedChanges) {
                    this.config.recordingQuality = data.recordingQuality || 'STANDARD';
                    this.config.recordingCodec = data.recordingCodec || 'H264';
                    this.config.cameraFps = data.cameraFps || 15;
                    this.config.cameraFpsActual = data.cameraFpsActual || null;
                    this.config.cameraFpsClampNote = data.cameraFpsClampNote || null;
                    this.config.recordingQualityOptions = data.recordingQualityOptions || {};
                    this.config.qualityOptionsByFps = data.qualityOptionsByFps || null;
                    this.config.activeRecordingEstimate = data.activeRecordingEstimate || null;
                    this.config.nativeResolution = data.nativeResolution || null;
                    if (typeof data.segmentDurationMinutes === 'number') {
                        this.config.segmentDurationMinutes = data.segmentDurationMinutes;
                    }
                    this.savedConfig = JSON.parse(JSON.stringify(this.config));
                    this.lastConfigTimestamp = newTimestamp;
                    this.updateUI();
                    console.log('Recording config reloaded (timestamp:', newTimestamp, ')');
                }
            }
        } catch (e) {
            console.warn('Failed to reload config:', e);
        }
        
        // Reload recording mode. Collected first and applied once below (unified wins), so the
        // live value never sits in config on its own while the unified read is awaited.
        let reloadedMode = null;
        try {
            const modeResp = await fetch('/api/recording/mode');
            const modeData = await modeResp.json();
            if (modeData.status === 'ok') {
                reloadedMode = modeData.mode || 'NONE';
            }
        } catch (e) {}
        
        // Reload proximity guard config and recording mode from unified config
        try {
            const proxResp = await fetch('/api/settings/unified');
            const proxData = await proxResp.json();
            if (proxData.success && proxData.config) {
                // Load recording mode from unified config if available (overrides /api/recording/mode)
                if (proxData.config.recording && proxData.config.recording.mode) {
                    reloadedMode = proxData.config.recording.mode;
                }
                // Lens-dewarp slider (shared with surveillance page).
                // The initial loadConfig() reads this; the periodic reload
                // MUST re-read it too — otherwise a change made on the
                // surveillance page won't reflect here until a full
                // navigation / reload of the recording page.
                //
                // Guard on !hasUnsavedChanges (same as the clip-duration block
                // below and surveillance.js's rectify re-sync): this runs after
                // the /api/settings/unified await, so a mid-flight drag of the
                // rectify slider would otherwise be clobbered by the stale server
                // value — leaving config out of sync with the DOM and the dirty
                // diff, silently discarding the pending edit.
                if (!this.hasUnsavedChanges &&
                    proxData.config.recording &&
                    typeof proxData.config.recording.rectifyStrength === 'number') {
                    var rsRefresh = proxData.config.recording.rectifyStrength;
                    if (rsRefresh < 0) rsRefresh = 0;
                    if (rsRefresh > 100) rsRefresh = 100;
                    this.config.rectifyStrength = rsRefresh;
                    if (this.savedConfig) this.savedConfig.rectifyStrength = rsRefresh;
                }
                // Shared clip duration (same key feeds both axes). The quality
                // endpoint above is timestamp-gated and may miss a same-second
                // change made on the surveillance page, so re-sync by value
                // here — mirrors surveillance.js's reloadConfig.
                //
                // Guard on !hasUnsavedChanges: reloadConfig() may have been
                // entered while clean (passing the line-134 guard) and then
                // suspended at an await while the user clicked a Clip Duration
                // button. Without this re-check, the in-flight resume would
                // revert the user's pending pick to the stale server value and
                // gray out Apply. The entry guard only covers entry; this
                // covers the mid-flight race.
                if (!this.hasUnsavedChanges &&
                    proxData.config.recording &&
                    typeof proxData.config.recording.segmentDurationMinutes === 'number') {
                    var sdRefresh = proxData.config.recording.segmentDurationMinutes;
                    if (sdRefresh !== 2 && sdRefresh !== 5 && sdRefresh !== 10) sdRefresh = 2;
                    if (sdRefresh !== this.config.segmentDurationMinutes) {
                        this.config.segmentDurationMinutes = sdRefresh;
                        if (this.savedConfig) this.savedConfig.segmentDurationMinutes = sdRefresh;
                        document.querySelectorAll('#clipDurationBtns .btn-toggle').forEach(function (btn) {
                            btn.classList.toggle('active', btn.dataset.value === String(sdRefresh));
                        });
                    }
                }
                // Hide rectify card on dilink4 — same gate as initial load.
                try {
                    var modeRefresh = proxData.config.camera && proxData.config.camera.cameraMode;
                    var cardRefresh = document.getElementById('rectifyCard');
                    if (cardRefresh) cardRefresh.style.display = (modeRefresh === 'dilink4') ? 'none' : '';
                } catch (_) {}

                // Merge proximity guard with defaults. Skipped while dirty, like the blocks
                // above, so an edit made during the awaits is not reverted to the server value.
                if (!this.hasUnsavedChanges && proxData.config.proximityGuard) {
                    const serverConfig = proxData.config.proximityGuard;
                    this.config.proximityGuard = {
                        triggerLevel: serverConfig.triggerLevel || this.config.proximityGuard.triggerLevel || 'RED',
                        preRecordSeconds: serverConfig.preRecordSeconds || this.config.proximityGuard.preRecordSeconds || 5,
                        postRecordSeconds: serverConfig.postRecordSeconds || this.config.proximityGuard.postRecordSeconds || 10,
                        blindSpotTrigger: serverConfig.blindSpotTrigger === true
                    };
                    if (this.savedConfig) {
                        this.savedConfig.proximityGuard = JSON.parse(JSON.stringify(this.config.proximityGuard));
                    }
                }
            }
        } catch (e) {}
        if (reloadedMode && !this.hasUnsavedChanges) {
            this.config.recordingMode = reloadedMode;
            if (this.savedConfig) this.savedConfig.recordingMode = reloadedMode;
        }
        
        // Also reload storage settings
        await this.loadStorageSettings();
        
        // Reload telemetry overlay state
        await this.loadTelemetryOverlay();
        await this.loadAudioRecording();
        // Re-hydrate the OEM Dashcam card on every reload so:
        // (a) a camera-mapping dialog change visible on next visibilitychange,
        // (b) the pipeline status row updates as applyLifecycle settles,
        // (c) toggling between tabs picks up daemon-side state drift.
        // Surveillance.js's reloadConfig already does this for its OEM row;
        // recording.js was missing the symmetric call.
        await this.loadOemDashcam();
        await this.loadRecordingLayout();

        // Update UI with all reloaded settings.
        //
        // Re-check hasUnsavedChanges: reloadConfig() is async with several
        // awaited fetches after the line-134 entry guard. If the user edited
        // any field (e.g. clicked a Clip Duration button) while this call was
        // suspended at an await, re-baselining savedConfig here and calling
        // updateUI() (which hard-disables Apply) would silently discard that
        // pending edit. Skip the re-baseline when there are unsaved changes so
        // the user's in-flight edit and the enabled Apply button survive.
        if (this.hasUnsavedChanges) return;
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
    },

    async loadConfig() {
        try {
            const resp = await fetch('/api/settings/quality');
            const data = await resp.json();
            if (data.success) {
                // New unified tier (ECONOMY/STANDARD/HIGH/PREMIUM/MAX).
                // STANDARD is the post-migration default; legacy
                // LOW/REDUCED/NORMAL silently map upstream.
                this.config.recordingQuality = data.recordingQuality || 'STANDARD';
                this.config.recordingCodec = data.recordingCodec || 'H264';
                this.config.cameraFps = data.cameraFps || 15;
                this.config.cameraFpsActual = data.cameraFpsActual || null;
                this.config.cameraFpsClampNote = data.cameraFpsClampNote || null;
                this.config.recordingQualityOptions = data.recordingQualityOptions || {};
                this.config.qualityOptionsByFps = data.qualityOptionsByFps || null;
                this.config.activeRecordingEstimate = data.activeRecordingEstimate || null;
                this.config.nativeResolution = data.nativeResolution || null;
                if (typeof data.segmentDurationMinutes === 'number') {
                    this.config.segmentDurationMinutes = data.segmentDurationMinutes;
                }
                this.lastConfigTimestamp = data.lastModified || Date.now();
            }
        } catch (e) {}

        // Kick the storage read NOW and join it at the end, so the expensive
        // /api/settings/storage overlaps the two cheap reads below instead of
        // running after them. It writes only recordingsLimitMb /
        // recordingsStorageType / storageInfo.*, which nothing below touches.
        //
        // NOTE the mode reads below stay SERIAL with respect to each other on
        // purpose: /api/recording/mode and /api/settings/unified both write
        // config.recordingMode, and unified is meant to win. Parallelising that
        // pair would make the winner depend on response order.
        const storageSettingsPromise = this.loadStorageSettings();

        // Load recording mode
        try {
            const modeResp = await fetch('/api/recording/mode');
            const modeData = await modeResp.json();
            if (modeData.status === 'ok') {
                this.config.recordingMode = modeData.mode || 'NONE';
            }
        } catch (e) {}
        
        // Load proximity guard config and recording mode from unified config
        try {
            const proxResp = await fetch('/api/settings/unified');
            const proxData = await proxResp.json();
            console.log('Unified config response:', proxData);
            if (proxData.success && proxData.config) {
                // Load recording mode from unified config if available
                if (proxData.config.recording && proxData.config.recording.mode) {
                    this.config.recordingMode = proxData.config.recording.mode;
                    console.log('Loaded recording mode from unified:', this.config.recordingMode);
                }
                // Lens-dewarp strength (shared by recording + surveillance).
                // 0 default keeps the prior behaviour (raw HAL output).
                if (proxData.config.recording &&
                    typeof proxData.config.recording.rectifyStrength === 'number') {
                    var rs = proxData.config.recording.rectifyStrength;
                    if (rs < 0) rs = 0; if (rs > 100) rs = 100;
                    this.config.rectifyStrength = rs;
                } else {
                    this.config.rectifyStrength = 0;
                }
                // Hide the slider entirely on dilink4 cars: the legacy-mode
                // shader branch isn't on their hot path, so the slider would
                // be a no-op control. Mode is mirrored from `camera.cameraMode`.
                try {
                    var mode = proxData.config.camera && proxData.config.camera.cameraMode;
                    var card = document.getElementById('rectifyCard');
                    if (card) card.style.display = (mode === 'dilink4') ? 'none' : '';
                } catch (_) {}

                // Merge proximity guard with defaults to handle missing fields
                if (proxData.config.proximityGuard) {
                    const serverConfig = proxData.config.proximityGuard;
                    this.config.proximityGuard = {
                        triggerLevel: serverConfig.triggerLevel || this.config.proximityGuard.triggerLevel || 'RED',
                        preRecordSeconds: serverConfig.preRecordSeconds || this.config.proximityGuard.preRecordSeconds || 5,
                        postRecordSeconds: serverConfig.postRecordSeconds || this.config.proximityGuard.postRecordSeconds || 10,
                        blindSpotTrigger: serverConfig.blindSpotTrigger === true
                    };
                    console.log('Loaded proximity guard config:', this.config.proximityGuard);
                }
            }
        } catch (e) {
            console.warn('Failed to load unified config:', e);
        }

        // Join the storage read kicked off above. loadStorageSettings() swallows
        // its own errors, so this can't reject and reach callers.
        await storageSettingsPromise;
    },
    
    async loadStorageSettings() {
        try {
            const resp = await fetch('/api/settings/storage');
            const data = await resp.json();
            if (data.success) {
                // Dirty-guard the user-editable picker fields. loadStorageSettings
                // is called from reloadConfig() (periodic 10s + visibility), and
                // without this guard a reload that lands right after Apply (which
                // clears hasUnsavedChanges) would overwrite the just-saved slider
                // value with a stale server read and snap the slider back. The
                // storageInfo/ceiling fields below are display-only and always
                // refresh. Mirrors the clip-duration mid-flight guard above.
                if (!this.hasUnsavedChanges) {
                    this.config.recordingsLimitMb = data.recordingsLimitMb || 500;
                    this.config.recordingsStorageType = data.recordingsStorageType || 'INTERNAL';
                }
                // Active (resolved) type may differ from configured when the
                // chosen external volume is unavailable/full and recordings
                // fell back to internal. Used to surface the fallback banner.
                this.config.recordingsStorageTypeActive =
                    data.recordingsStorageTypeActive || this.config.recordingsStorageType;

                // SD card info
                this.storageInfo.sdCardAvailable = data.sdCardAvailable || false;
                this.storageInfo.sdCardPath = data.sdCardPath || null;
                this.storageInfo.sdCardFreeSpace = data.sdCardFreeSpace || 0;
                this.storageInfo.sdCardTotalSpace = data.sdCardTotalSpace || 0;

                // USB info
                this.storageInfo.usbAvailable = data.usbAvailable || false;
                this.storageInfo.usbPath = data.usbPath || null;
                this.storageInfo.usbFreeSpace = data.usbFreeSpace || 0;
                this.storageInfo.usbTotalSpace = data.usbTotalSpace || 0;

                // Dynamic per-volume ceilings (live StatFs from server) — now the
                // FULL usable volume per category (no /N division).
                this.storageInfo.maxLimitMb       = data.maxLimitMb       || 100000;
                this.storageInfo.maxLimitMbSdCard = data.maxLimitMbSdCard || 100000;
                this.storageInfo.maxLimitMbUsb    = data.maxLimitMbUsb    || 100000;
                // Effective enforced limit = configured clamped to the active
                // volume's capacity. Differs from recordingsLimitMb only during a
                // fallback to internal; drives the honest banner copy below.
                this.storageInfo.recordingsEffectiveLimitMb = data.recordingsEffectiveLimitMb || 0;
                this.storageInfo.recordingsPath = data.recordingsPath || '';
                this.storageInfo.storageBudget = data.storageBudget || [];

                this.updateStorageLimitUI();
                this.updateStorageTypeUI();
            }
        } catch (e) {
            console.warn('Failed to load storage settings:', e);
        }
    },
    
    async loadStorageStats() {
        try {
            const resp = await fetch('/api/recordings/stats');
            const data = await resp.json();
            // Recordings index is down — the zeroed counters in this payload
            // are not authoritative, so show "--" rather than asserting the
            // library is empty. Also mark the outage so the 10s poller backs
            // off instead of hammering the daemon 6×/min for the page's whole
            // lifetime (each 503 takes the index monitor server-side).
            if (data.indexUnavailable) {
                this._indexDown = true;
                var rdIds = ['storageUsed', 'storageLimit'];
                for (var ri = 0; ri < rdIds.length; ri++) {
                    var rel = document.getElementById(rdIds[ri]);
                    if (rel) rel.textContent = '--';
                }
                var rFill = document.getElementById('storageFill');
                if (rFill) rFill.style.width = '0%';
                // Today's clip count is written only in the success branch, so
                // without this it keeps its hardcoded "0 →" default and asserts
                // that nothing was recorded today.
                var rToday = document.getElementById('recToday');
                if (rToday) rToday.textContent = '--';
                return;
            }
            this._indexDown = false;
            if (data.success) {
                const usedEl = document.getElementById('storageUsed');
                const limitEl = document.getElementById('storageLimit');
                const fillEl = document.getElementById('storageFill');

                // Prefer the structured byType block; fall back to legacy
                // flat fields so a stale daemon still renders correctly.
                // Instant replays are typed 'replay' since index schema v3
                // but still live in the recordings dirs and count against
                // recordingsLimitMb (StorageManager reaps cam_* + replay_*
                // together), so they must stay inside this card's usage
                // math. Old daemons have no replay fields → 0.
                const counts = data.byType ? {
                    normal: data.byType.normal || {},
                    proximity: data.byType.proximity || {},
                    replay: data.byType.replay || {}
                } : {
                    normal: {
                        count: data.normalCount,
                        bytes: data.normalSize,
                        bytesFormatted: data.normalSizeFormatted,
                        todayCount: data.normalTodayCount
                    },
                    proximity: {
                        count: data.proximityCount,
                        bytes: data.proximitySize,
                        bytesFormatted: data.proximitySizeFormatted,
                        todayCount: data.proximityTodayCount
                    },
                    replay: {
                        count: data.replayCount || 0,
                        bytes: data.replaySize || 0,
                        todayCount: data.replayTodayCount || 0
                    }
                };

                // Used = everything the recordings quota actually holds
                // (cam_*/dvr_* + replay_* + proximity_*), matching the server-side
                // reaper. Proximity shares the recordings cap, so leaving it out made
                // the bar read "in cap" while dashcam clips were being deleted.
                const usedBytes = (counts.normal.bytes || 0) + (counts.replay.bytes || 0)
                    + (counts.proximity.bytes || 0);
                if (usedEl) usedEl.textContent = BYD.i18n.t('recording.storage_used', {size: this.formatSize(usedBytes)});

                const limitMb = this.config.recordingsLimitMb || 500;
                if (limitEl) limitEl.textContent = BYD.i18n.t('recording.storage_limit_mb', {mb: limitMb});

                // Calculate percentage
                const limitBytes = limitMb * 1024 * 1024;
                const percent = Math.min(100, Math.round(usedBytes * 100 / limitBytes));
                if (fillEl) fillEl.style.width = percent + '%';

                // Update Recordings Today count
                const recTodayEl = document.getElementById('recToday');
                if (recTodayEl) {
                    // Include normal + proximity + replay recordings for today
                    const todayCount = (counts.normal.todayCount || 0)
                        + (counts.proximity.todayCount || 0)
                        + (counts.replay.todayCount || 0);
                    recTodayEl.textContent = todayCount + ' →';
                }

                // Daemon's recording index is still building — repurpose the
                // storage-card "used" line to surface progress so the user
                // doesn't see a stale 0 MB while H2 catches up. Self-refresh
                // until indexState disappears from the payload.
                // Single in-flight timer + exponential backoff (2s → 4s →
                // 8s → 10s cap). Page revisits / segment switches stack
                // independent polling chains otherwise.
                if (this._warmingPollTimer) {
                    clearTimeout(this._warmingPollTimer);
                    this._warmingPollTimer = null;
                }
                if (data.indexState && data.indexState.warming) {
                    const done = data.indexState.done || 0;
                    const total = data.indexState.total || 0;
                    const pct = total > 0 ? Math.round(done * 100 / total) : 0;
                    const tmpl = BYD.i18n.t('recording.storage_indexing', {done: done, total: total, pct: pct});
                    const text = (tmpl && tmpl !== 'recording.storage_indexing')
                        ? tmpl
                        : 'Indexing — ' + done + ' / ' + total + ' (' + pct + '%)';
                    if (usedEl) usedEl.textContent = text;
                    var self = this;
                    var attempt = Math.min(this._warmingPollAttempt || 0, 8);
                    var delay = Math.min(2000 * Math.pow(2, attempt), 10000);
                    this._warmingPollAttempt = (this._warmingPollAttempt || 0) + 1;
                    this._warmingPollTimer = setTimeout(function () {
                        self._warmingPollTimer = null;
                        if (self.loadStorageStats) self.loadStorageStats();
                    }, delay);
                } else {
                    this._warmingPollAttempt = 0;
                }
            }
        } catch (e) {
            console.warn('Failed to load storage stats:', e);
        }
    },
    
    /**
     * Resolve the slider's effective max based on the selected storage
     * type. Pulls the live per-volume ceiling from storageInfo so card
     * swaps update the slider after the next loadStorageSettings.
     */
    effectiveMaxLimitMb() {
        switch (this.config.recordingsStorageType) {
            case 'SD_CARD': return this.storageInfo.maxLimitMbSdCard;
            case 'USB':     return this.storageInfo.maxLimitMbUsb;
            default:        return this.storageInfo.maxLimitMb;
        }
    },

    updateStorageLimitUI() {
        const slider = document.getElementById('recLimitSlider');
        const value = document.getElementById('recLimitValue');

        const maxLimit = this.effectiveMaxLimitMb();

        if (slider) {
            slider.max = maxLimit;
            slider.value = Math.min(this.config.recordingsLimitMb, maxLimit);
        }
        if (value) {
            const mb = this.config.recordingsLimitMb;
            value.textContent = mb >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (mb / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: mb});
        }

        const minLabel = document.getElementById('recLimitMin');
        const maxLabel = document.getElementById('recLimitMax');
        if (minLabel) minLabel.textContent = BYD.i18n.t('recording.unit_mb', {n: 100});
        if (maxLabel) maxLabel.textContent = maxLimit >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (maxLimit / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: maxLimit});

        this.updateBudgetBanner();
    },

    /**
     * Render the combined-limit advisory. Passes the CURRENT (possibly unsaved)
     * slider value as pending so the warning appears while dragging, not only
     * after Apply. Guarded on the shared module being present so a cached page
     * without the new script tag doesn't throw.
     */
    updateBudgetBanner() {
        if (!BYD.storageBudget) return;
        BYD.storageBudget.render('recBudgetBanner', this.storageInfo.storageBudget,
            'recordings', this.config.recordingsLimitMb, this.config.recordingsStorageType);
    },

    /**
     * Re-read ONLY the storage budget and re-render the advisory — for the dirty-page
     * case, where the full reloadConfig is (correctly) suppressed to protect unsaved
     * edits. Writes no control, so it is safe with edits in flight; the pending slider
     * value in this.config is what the banner renders against either way.
     */
    async refreshBudgetOnly() {
        if (!BYD.storageBudget || !document.getElementById('recBudgetBanner')) return;
        try {
            const resp = await fetch('/api/settings/storage');
            const data = await resp.json();
            if (!data || !data.success || !data.storageBudget) return;
            this.storageInfo.storageBudget = data.storageBudget;
            this.updateBudgetBanner();
        } catch (e) { /* advisory only — keep the last render */ }
    },
    
    updateStorageTypeUI() {
        // Update storage type buttons
        document.querySelectorAll('#recStorageTypeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingsStorageType));

        // Storage fallback banner: configured an external volume but it's
        // unavailable/full so recordings are actually landing on internal.
        // The active!=configured divergence is computed server-side
        // (recordingsStorageTypeActive); only render when the user picked an
        // external target and it fell back to INTERNAL.
        var fallbackBanner = document.getElementById('recStorageFallbackBanner');
        if (fallbackBanner) {
            var configured = this.config.recordingsStorageType;
            var active = this.config.recordingsStorageTypeActive || configured;
            var fellBack = (configured === 'SD_CARD' || configured === 'USB')
                && active === 'INTERNAL';
            fallbackBanner.style.display = fellBack ? 'flex' : 'none';
            // Honest enforcement copy: while on the internal fallback the configured
            // limit is clamped to what internal can hold. When that clamp is active
            // (effective < configured), say so, so the user understands why fewer
            // clips are retained than their (external-sized) limit implies.
            var textEl = document.getElementById('recStorageFallbackText');
            if (textEl && fellBack) {
                var eff = this.storageInfo.recordingsEffectiveLimitMb || 0;
                var cfg = this.config.recordingsLimitMb || 0;
                if (eff > 0 && cfg > 0 && eff < cfg) {
                    textEl.textContent = BYD.i18n.t('recording.storage_fallback_enforcing', {mb: eff});
                } else {
                    textEl.textContent = BYD.i18n.t('recording.storage_fallback_internal');
                }
            }
        }

        // SD card button state
        const sdCardBtn = document.getElementById('btnRecSdCard');
        if (sdCardBtn) {
            sdCardBtn.disabled = !this.storageInfo.sdCardAvailable;
            sdCardBtn.title = this.storageInfo.sdCardAvailable ? '' : BYD.i18n.t('recording.sd_card_unavailable');
        }

        // USB button state
        const usbBtn = document.getElementById('btnRecUsb');
        if (usbBtn) {
            usbBtn.disabled = !this.storageInfo.usbAvailable;
            usbBtn.title = this.storageInfo.usbAvailable ? '' : BYD.i18n.t('recording.usb_unavailable');
        }

        // SD card status block
        const sdStatusEl = document.getElementById('recSdCardStatus');
        if (sdStatusEl) {
            sdStatusEl.style.display = 'block';
            const dotEl = document.getElementById('recSdStatusDot');
            const textEl = document.getElementById('recSdStatusText');
            const spaceEl = document.getElementById('recSdSpaceInfo');
            if (this.storageInfo.sdCardAvailable) {
                if (dotEl) dotEl.className = 'sd-status-dot online';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_available');
                if (spaceEl) {
                    spaceEl.style.display = 'block';
                    document.getElementById('recSdFree').textContent = BYD.i18n.t('recording.size_free', {size: this.formatSize(this.storageInfo.sdCardFreeSpace)});
                    document.getElementById('recSdTotal').textContent = BYD.i18n.t('recording.size_total', {size: this.formatSize(this.storageInfo.sdCardTotalSpace)});
                }
            } else {
                if (dotEl) dotEl.className = 'sd-status-dot offline';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_not_detected');
                if (spaceEl) spaceEl.style.display = 'none';
            }
        }

        // USB status block
        const usbStatusEl = document.getElementById('recUsbStatus');
        if (usbStatusEl) {
            usbStatusEl.style.display = 'block';
            const dotEl = document.getElementById('recUsbStatusDot');
            const textEl = document.getElementById('recUsbStatusText');
            const spaceEl = document.getElementById('recUsbSpaceInfo');
            if (this.storageInfo.usbAvailable) {
                if (dotEl) dotEl.className = 'sd-status-dot online';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.usb_available');
                if (spaceEl) {
                    spaceEl.style.display = 'block';
                    document.getElementById('recUsbFree').textContent = BYD.i18n.t('recording.size_free', {size: this.formatSize(this.storageInfo.usbFreeSpace)});
                    document.getElementById('recUsbTotal').textContent = BYD.i18n.t('recording.size_total', {size: this.formatSize(this.storageInfo.usbTotalSpace)});
                }
            } else {
                if (dotEl) dotEl.className = 'sd-status-dot offline';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.usb_not_detected');
                if (spaceEl) spaceEl.style.display = 'none';
            }
        }

        // Storage path display
        const pathEl = document.getElementById('recStoragePath');
        if (pathEl && this.storageInfo.recordingsPath) {
            const shortPath = this.storageInfo.recordingsPath.replace('/storage/emulated/0/', '');
            pathEl.textContent = BYD.i18n.t('recording.saved_to', {path: shortPath});
        }
    },
    
    formatSize(bytes) {
        if (bytes >= 1000000000) return (bytes / 1000000000).toFixed(1) + ' GB';
        if (bytes >= 1000000) return (bytes / 1000000).toFixed(1) + ' MB';
        if (bytes >= 1000) return (bytes / 1000).toFixed(1) + ' KB';
        return bytes + ' B';
    },
    
    setStorageType(type) {
        if (type === 'SD_CARD' && !this.storageInfo.sdCardAvailable) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.sd_card_unavailable'), 'error');
            return;
        }
        if (type === 'USB' && !this.storageInfo.usbAvailable) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.usb_unavailable'), 'error');
            return;
        }

        this.config.recordingsStorageType = type;
        document.querySelectorAll('#recStorageTypeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === type));

        // Re-clamp slider value to the new volume's effective max so we don't
        // ship a 80GB value to the server when the user just switched to a
        // 32GB USB stick.
        const newMax = this.effectiveMaxLimitMb();
        if (this.config.recordingsLimitMb > newMax) {
            this.config.recordingsLimitMb = newMax;
        }
        this.updateStorageLimitUI();
        this.updateCdrCleanupVisibility();
        this.markChanged();
    },
    
    // ==================== CDR Cleanup ====================
    
    cdrConfig: {
        enabled: false,
        reservedSpaceMb: 2000,
        protectedHours: 24,
        minFilesKeep: 10
    },
    
    async loadCdrConfig() {
        // The cleanup card moved to storage.html; skip the slow CDR scan when it is not on this page.
        if (!document.getElementById('cdrCleanupCard')) return false;
        if (this._cdrWritesPending > 0) return false;
        const writeVersion = this._cdrWriteVersion;
        try {
            const resp = await fetch('/api/storage/external');
            const data = await resp.json();
            if (data.success
                    && this._cdrWritesPending === 0
                    && writeVersion === this._cdrWriteVersion) {
                this._cdrReady = true;
                this.cdrConfig.enabled = !!data.cleanupEnabled;
                if (typeof data.reservedSpaceMb === 'number') {
                    this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb;
                }
                if (typeof data.protectedHours === 'number') {
                    this.cdrConfig.protectedHours = data.protectedHours;
                }
                if (typeof data.minFilesKeep === 'number') {
                    this.cdrConfig.minFilesKeep = data.minFilesKeep;
                }
                
                // Store CDR info
                this.cdrInfo = {
                    cdrPath: data.cdrPath,
                    cdrUsage: data.cdrUsageFormatted,
                    cdrFileCount: data.cdrFileCount,
                    cdrProtected: data.cdrProtectedFormatted,
                    cdrDeletable: data.cdrDeletableFormatted,
                    totalFreed: data.totalBytesFreedFormatted,
                    totalDeleted: data.totalFilesDeleted,
                    monitoringActive: !!data.monitoringActive,
                    lastCleanupTime: data.lastCleanupTime || 0,
                    recommendAutoCleanup: !!data.recommendAutoCleanup
                };

                this.updateCdrUI();
                return true;
            }
        } catch (e) {
            console.warn('Failed to load CDR config:', e);
        }
        return false;
    },
    
    updateCdrCleanupVisibility() {
        const card = document.getElementById('cdrCleanupCard');
        if (card) {
            const wasVisible = card.style.display !== 'none';
            const showCard = this.config.recordingsStorageType === 'SD_CARD' && this.storageInfo.sdCardAvailable;
            card.style.display = showCard ? 'block' : 'none';
            
            if (showCard) {
                if (!wasVisible) this._cdrReady = false;
                this.updateCdrUI();
                return this.loadCdrConfig();
            }
        }
        return Promise.resolve(false);
    },
    
    updateCdrUI() {
        // Update toggle
        const toggle = document.getElementById('cdrCleanupEnabled');
        if (toggle) {
            if (!this._cdrTogglePending) toggle.checked = this.cdrConfig.enabled;
            toggle.disabled = !this._cdrReady
                || this._cdrTogglePending || this._cdrCleanupPending
                || this._cdrWritesPending > 0 || !!this._cdrSaveTimer
                || Object.keys(this._cdrDirty).length > 0;
        }
        const cleanupButton = document.getElementById('cdrCleanupNow');
        if (cleanupButton) {
            cleanupButton.disabled = !this._cdrReady || !this.cdrConfig.enabled
                || this._cdrCleanupPending || this._cdrWritesPending > 0
                || !!this._cdrSaveTimer || Object.keys(this._cdrDirty).length > 0;
        }
        
        // Update badge
        const badge = document.getElementById('cdrCleanupBadge');
        if (badge) {
            badge.textContent = this.cdrConfig.enabled ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
            badge.className = 'status-badge ' + (this.cdrConfig.enabled ? 'active' : 'inactive');
        }
        
        // Update sliders
        const reservedSlider = document.getElementById('cdrReservedSlider');
        const reservedValue = document.getElementById('cdrReservedValue');
        if (reservedSlider) {
            reservedSlider.value = this.cdrConfig.reservedSpaceMb;
            reservedSlider.disabled = !this._cdrReady || this._cdrCleanupPending
                || this._cdrTogglePending || this._cdrWritesPending > 0;
        }
        if (reservedValue) reservedValue.textContent = this.cdrConfig.reservedSpaceMb >= 1000
            ? BYD.i18n.t('recording.unit_gb', {n: (this.cdrConfig.reservedSpaceMb / 1000)})
            : BYD.i18n.t('recording.unit_mb', {n: this.cdrConfig.reservedSpaceMb});

        const protectedSlider = document.getElementById('cdrProtectedSlider');
        const protectedValue = document.getElementById('cdrProtectedValue');
        if (protectedSlider) {
            protectedSlider.value = this.cdrConfig.protectedHours;
            protectedSlider.disabled = !this._cdrReady || this._cdrCleanupPending
                || this._cdrTogglePending || this._cdrWritesPending > 0;
        }
        if (protectedValue) protectedValue.textContent = BYD.i18n.t('recording.unit_hours', {n: this.cdrConfig.protectedHours});
        
        const minKeepSlider = document.getElementById('cdrMinKeepSlider');
        const minKeepValue = document.getElementById('cdrMinKeepValue');
        if (minKeepSlider) {
            minKeepSlider.value = this.cdrConfig.minFilesKeep;
            minKeepSlider.disabled = !this._cdrReady || this._cdrCleanupPending
                || this._cdrTogglePending || this._cdrWritesPending > 0;
        }
        if (minKeepValue) minKeepValue.textContent = this.cdrConfig.minFilesKeep;
        
        // Update info
        if (this.cdrInfo) {
            const pathEl = document.getElementById('cdrPath');
            if (pathEl) pathEl.textContent = this.cdrInfo.cdrPath || BYD.i18n.t('recording.not_found');

            const usageEl = document.getElementById('cdrUsage');
            if (usageEl) usageEl.textContent = this.cdrInfo.cdrUsage || '--';

            const countEl = document.getElementById('cdrFileCount');
            if (countEl) countEl.textContent = this.cdrInfo.cdrFileCount || '0';

            const protEl = document.getElementById('cdrProtected');
            if (protEl) protEl.textContent = this.cdrInfo.cdrProtected || '--';

            const deletableEl = document.getElementById('cdrDeletable');
            if (deletableEl) deletableEl.textContent = this.cdrInfo.cdrDeletable || '--';

            const monEl = document.getElementById('cdrMonitoring');
            if (monEl) {
                if (!this.cdrConfig.enabled) {
                    monEl.textContent = BYD.i18n.t('common.disabled');
                    monEl.style.color = '';
                } else if (this.cdrInfo.monitoringActive) {
                    monEl.textContent = BYD.i18n.t('common.running');
                    monEl.style.color = '#22c55e';
                } else {
                    monEl.textContent = BYD.i18n.t('common.idle');
                    monEl.style.color = '#94a3b8';
                }
            }

            const lastEl = document.getElementById('cdrLastCleanup');
            if (lastEl) lastEl.textContent = this._formatRelativeTime(this.cdrInfo.lastCleanupTime);

            const banner = document.getElementById('cdrRecommendBanner');
            if (banner) banner.style.display = this.cdrInfo.recommendAutoCleanup ? 'block' : 'none';

            const freedEl = document.getElementById('cdrTotalFreed');
            if (freedEl) freedEl.textContent = this.cdrInfo.totalFreed || '0 B';

            const deletedEl = document.getElementById('cdrTotalDeleted');
            if (deletedEl) deletedEl.textContent = this.cdrInfo.totalDeleted || '0';
        }
    },

    _formatRelativeTime(ts) {
        if (!ts || ts <= 0) return BYD.i18n.t('recording.never');
        const diffSec = Math.floor((Date.now() - ts) / 1000);
        if (diffSec < 0) return BYD.i18n.t('recording.just_now');
        if (diffSec < 60) return BYD.i18n.t('recording.seconds_ago', {n: diffSec});
        if (diffSec < 3600) return BYD.i18n.t('recording.minutes_ago', {n: Math.floor(diffSec / 60)});
        if (diffSec < 86400) return BYD.i18n.t('recording.hours_ago', {n: Math.floor(diffSec / 3600)});
        return BYD.i18n.t('recording.days_ago', {n: Math.floor(diffSec / 86400)});
    },
    
    async toggleCdrCleanup() {
        const toggle = document.getElementById('cdrCleanupEnabled');
        if (!toggle || !this._hydrated || !this._cdrReady
                || this._cdrTogglePending || this._cdrCleanupPending
                || this._cdrWritesPending > 0 || this._cdrSaveTimer
                || Object.keys(this._cdrDirty).length > 0) return;
        const enabled = toggle.checked;
        const previous = this.cdrConfig.enabled;
        let saved = false;
        this._cdrTogglePending = true;
        this._cdrWriteVersion++;
        this._cdrWritesPending++;
        this.updateCdrUI();
        try {
            const data = await this._postJson('/api/storage/external/config', { enabled });
            if (!data) {
                this.cdrConfig.enabled = previous;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('recording.cdr_toggle_failed'), 'error');
                }
                return;
            }
            saved = true;
            this.cdrConfig.enabled = data.cleanupEnabled == null
                ? enabled : !!data.cleanupEnabled;
            if (typeof data.reservedSpaceMb === 'number') {
                this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb;
            }
            if (typeof data.protectedHours === 'number') {
                this.cdrConfig.protectedHours = data.protectedHours;
            }
            if (typeof data.minFilesKeep === 'number') {
                this.cdrConfig.minFilesKeep = data.minFilesKeep;
            }
            this.updateCdrUI();
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(
                    this.cdrConfig.enabled
                        ? BYD.i18n.t('recording.cdr_enabled')
                        : BYD.i18n.t('recording.cdr_disabled'),
                    'success'
                );
            }
        } finally {
            this._cdrWritesPending--;
            this._cdrTogglePending = false;
            if (!saved) this._cdrReady = false;
            this.updateCdrUI();
            if (!this._cdrReady && this._cdrWritesPending === 0 && !this._cdrSaveTimer) {
                this.loadCdrConfig();
            }
        }
    },
    
    updateCdrReserved(value) {
        if (!this._hydrated || !this._cdrReady || this._cdrCleanupPending
                || this._cdrTogglePending || this._cdrWritesPending > 0) return;
        this.cdrConfig.reservedSpaceMb = parseInt(value);
        this._cdrDirty.reservedSpaceMb = this.cdrConfig.reservedSpaceMb;
        this._cdrWriteVersion++;
        const el = document.getElementById('cdrReservedValue');
        const v = parseInt(value);
        if (el) el.textContent = v >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (v / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: v});
        this.saveCdrConfig();
    },

    updateCdrProtected(value) {
        if (!this._hydrated || !this._cdrReady || this._cdrCleanupPending
                || this._cdrTogglePending || this._cdrWritesPending > 0) return;
        this.cdrConfig.protectedHours = parseInt(value);
        this._cdrDirty.protectedHours = this.cdrConfig.protectedHours;
        this._cdrWriteVersion++;
        const el = document.getElementById('cdrProtectedValue');
        if (el) el.textContent = BYD.i18n.t('recording.unit_hours', {n: parseInt(value)});
        this.saveCdrConfig();
    },
    
    updateCdrMinKeep(value) {
        if (!this._hydrated || !this._cdrReady || this._cdrCleanupPending
                || this._cdrTogglePending || this._cdrWritesPending > 0) return;
        this.cdrConfig.minFilesKeep = parseInt(value);
        this._cdrDirty.minFilesKeep = this.cdrConfig.minFilesKeep;
        this._cdrWriteVersion++;
        const el = document.getElementById('cdrMinKeepValue');
        if (el) el.textContent = value;
        this.saveCdrConfig();
    },
    
    saveCdrConfig() {
        if (this._cdrSaveTimer) clearTimeout(this._cdrSaveTimer);
        this._cdrSaveTimer = setTimeout(() => {
            this._cdrSaveTimer = null;
            this._flushCdrConfig();
        }, 200);
        this.updateCdrUI();
    },

    async _flushCdrConfig() {
        if (!this._hydrated || !this._cdrReady) return;
        const body = this._cdrDirty;
        this._cdrDirty = {};
        if (Object.keys(body).length === 0) return;
        const writeVersion = this._cdrWriteVersion;
        this._cdrWritesPending++;
        this.updateCdrUI();
        let saved = false;
        try {
            const data = await this._postJson('/api/storage/external/config', body);
            if (!data) return;
            saved = true;
            if (writeVersion !== this._cdrWriteVersion) return;
            if (data.cleanupEnabled != null) {
                this.cdrConfig.enabled = !!data.cleanupEnabled;
            }
            this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb;
            this.cdrConfig.protectedHours = data.protectedHours;
            this.cdrConfig.minFilesKeep = data.minFilesKeep;
            this.updateCdrUI();
        } finally {
            this._cdrWritesPending--;
            if (!saved) this._cdrReady = false;
            this.updateCdrUI();
            if (!this._cdrReady && this._cdrWritesPending === 0 && !this._cdrSaveTimer) {
                this.loadCdrConfig();
            }
        }
    },
    
    async triggerCdrCleanup() {
        const button = document.getElementById('cdrCleanupNow');
        if (!this._hydrated || !this._cdrReady || !this.cdrConfig.enabled
                || this._cdrCleanupPending || this._cdrWritesPending > 0
                || this._cdrSaveTimer || Object.keys(this._cdrDirty).length > 0
                || (button && button.disabled)) return;
        this._cdrCleanupPending = true;
        this.updateCdrUI();
        try {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_cleaning'), 'info');

            const resp = await fetch('/api/storage/external/cleanup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();

            if (data.success) {
                const msg = data.filesDeleted > 0
                    ? BYD.i18n.t('recording.cdr_freed', {size: data.freedFormatted, files: data.filesDeleted})
                    : BYD.i18n.t('recording.cdr_no_cleanup');
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');

                // Refresh CDR info
                this.loadCdrConfig();
            } else {
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(data.error || BYD.i18n.t('recording.cdr_cleanup_failed'), 'error');
            }
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_trigger_failed'), 'error');
        } finally {
            this._cdrCleanupPending = false;
            this.updateCdrUI();
        }
    },
    
    updateRecLimit(value) {
        this.config.recordingsLimitMb = parseInt(value);
        const v = parseInt(value);
        const limitLabel = document.getElementById('recLimitValue');
        if (limitLabel) limitLabel.textContent = v >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (v / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: v});
        // Live advisory while dragging — the point at which the user crosses the
        // volume's capacity is exactly when they need to know.
        this.updateBudgetBanner();
        this.markChanged();
    },

    /**
     * Lens-dewarp slider (0..100). Updates the displayed label and marks
     * the quality tab dirty — Apply persists via /api/settings/unified
     * which fires UnifiedConfigManager listeners; the active recorder picks
     * up the new value within one frame, no segment rotation needed.
     *
     * Also lightly debounced live-pushes to the daemon so users get
     * immediate visual confirmation without having to hit Apply for every
     * slider tweak. Apply is still required to persist across daemon
     * restart (the live POST already calls updateSection so this is
     * actually idempotent — keeping markChanged for the Apply UX).
     */
    updateRectifyStrength(value) {
        var v = parseInt(value);
        if (isNaN(v)) v = 0;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        this.config.rectifyStrength = v;
        var label = document.getElementById('rectifyValue');
        if (label) {
            // i18n.t can return the key itself if the catalog hasn't loaded
            // yet; fall back to a literal "Off" so the label never reads as
            // a raw key like "recording.rectify_off" or empty string.
            var offTxt = (BYD.i18n && typeof BYD.i18n.t === 'function')
                ? BYD.i18n.t('recording.rectify_off') : null;
            if (!offTxt || offTxt === 'recording.rectify_off') offTxt = 'Off';
            label.textContent = (v === 0) ? offTxt : (v + '%');
        }
        this.markChanged();
        // Debounced live POST so the recorder applies the new value as the
        // user drags. Avoids hammering the daemon on every input event.
        if (this._rectifyDebounce) clearTimeout(this._rectifyDebounce);
        var self = this;
        this._rectifyDebounce = setTimeout(function () {
            self._postJson('/api/settings/unified', {
                section: 'recording',
                data: { rectifyStrength: v }
            });
        }, 200);
    },

    /**
     * Per-tab dirty diff. Recording page tabs:
     *   capture  — recordingMode, proximityGuard
     *   quality  — recordingQuality, recordingCodec, cameraFps
     *   oem      — oemRecordingMode
     *   status   — read-only
     * Storage moved to storage.html: its fields still load here but are not
     * edited, so they must not mark this page dirty.
     * (recordingBitrate field removed; tier-based recordingQuality replaces it.
     *  streamingQuality moved to the camera controller dropdown in index.html.)
     */
    _recTabFieldMap: {
        capture: ['recordingMode', 'proximityGuard'],
        quality: ['recordingQuality', 'recordingCodec', 'cameraFps', 'rectifyStrength', 'segmentDurationMinutes'],
        oem:     ['oemRecordingMode']
    },

    _tabDirty: function () {
        if (!this.savedConfig) return {};
        var dirty = {};
        var map = this._recTabFieldMap;
        for (var tabId in map) {
            var fields = map[tabId];
            var d = false;
            for (var i = 0; i < fields.length; i++) {
                var k = fields[i];
                if (JSON.stringify(this.config[k]) !== JSON.stringify(this.savedConfig[k])) {
                    d = true; break;
                }
            }
            dirty[tabId] = d;
        }
        return dirty;
    },

    markChanged() {
        var dirtyByTab = this._tabDirty();
        this.hasUnsavedChanges = false;
        for (var k in dirtyByTab) {
            if (dirtyByTab[k]) { this.hasUnsavedChanges = true; break; }
        }
        this._dirtyByTab = dirtyByTab;

        var btn = document.getElementById('btnApply');
        if (btn) {
            var activeTab = this._activeTabId();
            var activeIsDirty = !!dirtyByTab[activeTab];
            btn.disabled = !activeIsDirty;
            btn.classList.toggle('has-changes', activeIsDirty);
        }
        this.renderHeroSummary();
    },

    // Hero sub-line: the applied mode, tier, codec and clip length, using the option labels shown on the page.
    renderHeroSummary() {
        const sub = document.getElementById('recHeroSub');
        if (!sub || !this.savedConfig) return;
        const c = this.savedConfig;
        const label = (selector) => {
            try {
                const el = document.querySelector(selector);
                return el ? el.textContent.replace(/\s+/g, ' ').trim() : '';
            } catch (e) { return ''; }
        };
        const parts = c.recordingMode === 'NONE'
            ? [BYD.i18n.t('recording.select_mode_desc')]
            : [
                label('input[name="recordingMode"][value="' + c.recordingMode + '"] + .mode-card .mode-name'),
                label('#recQualityBtns .btn-toggle[data-value="' + c.recordingQuality + '"]'),
                label('#codecBtns .btn-toggle[data-value="' + c.recordingCodec + '"]'),
                label('#clipDurationBtns .btn-toggle[data-value="' + c.segmentDurationMinutes + '"]')
            ];
        sub.textContent = parts.filter(Boolean).join(' · ');
    },

    updateUI() {
        // Single recording quality tier (replaces parallel recordingQuality + recordingBitrate).
        document.querySelectorAll('#recQualityBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingQuality));
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingCodec));
        document.querySelectorAll('#fpsBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(this.config.cameraFps)));
        document.querySelectorAll('#clipDurationBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(this.config.segmentDurationMinutes)));

        // Tier metadata (Mbps, GB/hr, qualityEquivalent) comes from the
        // recordingQualityOptions block in /api/quality. UI re-renders the
        // per-tier subtitle whenever updateUI runs so the labels reflect
        // current codec + fps choices live.
        this.renderActiveEstimate();
        this.renderFpsActual();
        
        // Update recording mode radio buttons
        const modeRadio = document.querySelector(`input[name="recordingMode"][value="${this.config.recordingMode}"]`);
        if (modeRadio) modeRadio.checked = true;
        
        // Show/hide proximity settings
        this.updateProximitySettingsVisibility();
        
        // Update proximity guard settings
        const triggerLevel = document.getElementById('triggerLevel');
        if (triggerLevel) triggerLevel.value = this.config.proximityGuard.triggerLevel || 'RED';
        this.syncTriggerLevelBtns();
        const bsdTrigger = document.getElementById('bsdTriggerToggle');
        if (bsdTrigger) bsdTrigger.checked = this.config.proximityGuard.blindSpotTrigger === true;
        
        const preSlider = document.getElementById('preRecordSlider');
        const preValue = document.getElementById('preRecordValue');
        if (preSlider && preValue) {
            preSlider.value = this.config.proximityGuard.preRecordSeconds || 5;
            preValue.textContent = BYD.i18n.t('recording.unit_seconds', {n: preSlider.value});
            document.getElementById('timelinePre').textContent = BYD.i18n.t('recording.unit_seconds', {n: preSlider.value});
        }

        const postSlider = document.getElementById('postRecordSlider');
        const postValue = document.getElementById('postRecordValue');
        if (postSlider && postValue) {
            postSlider.value = this.config.proximityGuard.postRecordSeconds || 10;
            postValue.textContent = BYD.i18n.t('recording.unit_seconds', {n: postSlider.value});
            document.getElementById('timelinePost').textContent = BYD.i18n.t('recording.unit_seconds', {n: postSlider.value});
        }

        // Lens-dewarp slider (shared with surveillance flow).
        var rectifySlider = document.getElementById('rectifySlider');
        var rectifyLabel = document.getElementById('rectifyValue');
        if (rectifySlider) {
            var rs = (typeof this.config.rectifyStrength === 'number')
                ? this.config.rectifyStrength : 0;
            rectifySlider.value = rs;
            if (rectifyLabel) {
                var offTxt = (BYD.i18n && typeof BYD.i18n.t === 'function')
                    ? BYD.i18n.t('recording.rectify_off') : null;
                if (!offTxt || offTxt === 'recording.rectify_off') offTxt = 'Off';
                rectifyLabel.textContent = (rs === 0) ? offTxt : (rs + '%');
            }
        }

        this.updateStorageLimitUI();
        this.updateStorageTypeUI();
        // File size estimate is now rendered by renderActiveEstimate() (called
        // earlier in updateUI). The legacy updateFileSizeEstimate() that
        // computed sizes locally from a hardcoded bitrate map was removed —
        // size + qualityEquivalent now come from the server via the
        // recordingQualityOptions / activeRecordingEstimate API fields.

        // Show CDR cleanup card if SD card is selected
        this.updateCdrCleanupVisibility();
        
        // Reset Apply button state after UI update (no unsaved changes after load)
        this.hasUnsavedChanges = false;
        const btn = document.getElementById('btnApply');
        if (btn) {
            btn.disabled = true;
        }
        this.renderHeroSummary();
    },
    
    onModeChange(mode) {
        this.config.recordingMode = mode;
        this.updateProximitySettingsVisibility();
        this.markChanged();
    },
    
    updateProximitySettingsVisibility() {
        const card = document.getElementById('proximitySettingsCard');
        if (card) {
            card.style.display = this.config.recordingMode === 'PROXIMITY_GUARD' ? 'block' : 'none';
        }
    },
    
    updatePreRecord(value) {
        this.config.proximityGuard.preRecordSeconds = parseInt(value);
        document.getElementById('preRecordValue').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        document.getElementById('timelinePre').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        this.markChanged();
    },

    updatePostRecord(value) {
        this.config.proximityGuard.postRecordSeconds = parseInt(value);
        document.getElementById('postRecordValue').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        document.getElementById('timelinePost').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        this.markChanged();
    },
    
    setBlindSpotTrigger(enabled) {
        this.config.proximityGuard.blindSpotTrigger = enabled === true;
        this.markChanged();
    },

    setTriggerLevel(value) {
        const sel = document.getElementById('triggerLevel');
        if (!sel || sel.value === value) return;
        sel.value = value;
        this.syncTriggerLevelBtns();
        this.markDirty();
    },

    syncTriggerLevelBtns() {
        const sel = document.getElementById('triggerLevel');
        const value = sel ? sel.value : 'RED';
        document.querySelectorAll('#triggerLevelBtns [data-value]').forEach((btn) => {
            const on = btn.getAttribute('data-value') === value;
            btn.classList.toggle('active', on);
            btn.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
    },

    markDirty() {
        // Update triggerLevel from select when called
        const triggerLevel = document.getElementById('triggerLevel');
        if (triggerLevel) {
            this.config.proximityGuard.triggerLevel = triggerLevel.value;
        }
        this.markChanged();
    },

    setRecordingQuality(tier) {
        this.config.recordingQuality = tier;
        document.querySelectorAll('#recQualityBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === tier));
        this.renderActiveEstimate();
        this.markChanged();
    },

    setCodec(codec) {
        this.config.recordingCodec = codec;
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === codec));
        // Codec changes the bitrate→quality math; refresh tier subtitles +
        // active estimate so the UI labels track the new codec.
        this.renderActiveEstimate();
        this.markChanged();
    },

    setFps(fps) {
        this.config.cameraFps = parseInt(fps, 10);
        document.querySelectorAll('#fpsBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(fps)));
        // FPS shifts the qualityEquivalent labels (bits-per-pixel-frame).
        // Re-render the per-tier subtitle locally so the user sees the
        // shift before they hit Apply (server then re-derives on next GET).
        this.renderActiveEstimate();
        this.markChanged();
    },

    /** Clip segment length (minutes: 2/5/10). Shared key — applies to both
     *  recording axes. Persisted on Apply via the quality POST; takes effect
     *  on the next segment rotation (no encoder reinit). */
    setClipDuration(minutes) {
        var m = parseInt(minutes, 10);
        if (m !== 2 && m !== 5 && m !== 10) m = 2;
        this.config.segmentDurationMinutes = m;
        document.querySelectorAll('#clipDurationBtns .btn-toggle').forEach(function (btn) {
            btn.classList.toggle('active', btn.dataset.value === String(m));
        });
        this.markChanged();
    },

    /** Pick the matching tier entry from the server-supplied options table.
     *  All math (bitrate, MB/2min, qualityEquivalent for the active codec+fps)
     *  is precomputed there; we just look it up. Returns null if the tier
     *  isn't loaded yet. */
    estimateForTier(tier, fps) {
        const byFps = this.config.qualityOptionsByFps;
        const opts = (byFps && byFps[String(fps)]) || this.config.recordingQualityOptions || {};
        return opts[tier] || null;
    },

    /** Pull a fresh /api/settings/quality and update the local tier table.
     *  Called after a save that affects per-tier metadata (codec or fps). */
    async refetchQualityOptions() {
        try {
            const r = await fetch('/api/settings/quality');
            if (!r.ok) return;
            const data = await r.json();
            if (data && data.recordingQualityOptions) {
                this.config.recordingQualityOptions = data.recordingQualityOptions;
                this.config.qualityOptionsByFps = data.qualityOptionsByFps || null;
                this.renderActiveEstimate();
            }
        } catch (e) { /* best-effort */ }
    },

    /** Format a tier+codec into "2 Mbps · ~28.6 MB / 2 min · ~720p". */
    formatEstimate(est) {
        if (!est) return '—';
        const parts = [BYD.i18n.t('recording.unit_mbps', {n: (est.bitrateMbps != null ? est.bitrateMbps : '—')})];
        if (est.mbPer2Min != null) parts.push(BYD.i18n.t('recording.unit_mb_per_2min', {n: est.mbPer2Min}));
        if (est.qualityEquivalent) parts.push(est.qualityEquivalent);
        return parts.join(' · ');
    },

    renderActiveEstimate() {
        const el = document.getElementById('activeEstimate');
        if (!el) return;

        // Compute the *currently-selected* estimate locally — server's
        // activeRecordingEstimate is stale until next save. The tier options
        // table is keyed by tier name and already accounts for codec+fps.
        const currentTier = this.config.recordingQuality;
        const savedTier = this.savedConfig ? this.savedConfig.recordingQuality : currentTier;
        const currentFps = this.config.cameraFps;
        const savedFps = this.savedConfig ? this.savedConfig.cameraFps : currentFps;
        const currentEst = this.estimateForTier(currentTier, currentFps);
        const savedEst = this.estimateForTier(savedTier, savedFps);

        // If neither tier nor fps changed since save, show one line.
        // Otherwise show "saved → pending" so the user sees what changes.
        if ((currentTier === savedTier && currentFps === savedFps) || !savedEst) {
            el.textContent = this.formatEstimate(currentEst);
        } else {
            el.textContent = this.formatEstimate(savedEst)
                + BYD.i18n.t('recording.estimate_diff_arrow')
                + this.formatEstimate(currentEst);
        }

        const native = document.getElementById('nativeResolution');
        if (native && this.config.nativeResolution) {
            native.textContent = this.config.nativeResolution;
        }
    },

    renderFpsActual() {
        const row = document.getElementById('fpsClampRow');
        const el  = document.getElementById('fpsActual');
        if (!row || !el) return;
        const actual = this.config.cameraFpsActual;
        if (actual == null) { row.style.display = 'none'; return; }
        row.style.display = '';
        if (this.config.cameraFpsClampNote) {
            el.textContent = this.config.cameraFpsClampNote;
        } else {
            el.textContent = actual + ' fps';
        }
    },

    updateRetention(value) {
        // Deprecated - retention days no longer used
        console.log('Retention days setting deprecated');
    },

    /**
     * Look up the active bottom-tab id (status / capture / quality / oem).
     * Mirrors the helper on SurvSettings — kept inline here to avoid a hard
     * cross-module dependency between recording.js and surveillance.js.
     */
    _activeTabId: function () {
        try {
            var path = window.location.pathname || '';
            var idx = path.lastIndexOf('/');
            var page = idx >= 0 ? path.substring(idx + 1) : (path || 'index');
            var stored = window.localStorage.getItem('ot-active-tab-' + page);
            // A tab dropped from the manifest (Storage) can linger here; app-tabs.js then shows the first tab.
            var tabs = window.OT_TABS || [];
            for (var i = 0; stored && i < tabs.length; i++) {
                if (tabs[i] && tabs[i].id === stored) return stored;
            }
        } catch (e) {}
        var visible = document.querySelector('.bottom-tab.is-active');
        if (visible) return visible.getAttribute('data-tab-target') || 'capture';
        return 'capture';
    },

    saveSettings() {
        return this._enqueueWrite(() => this._saveSettingsNow());
    },

    async _saveSettingsNow() {
        const btn = document.getElementById('btnApply');
        const origHtml = btn ? btn.innerHTML : null;
        const savedBefore = this.savedConfig
            ? JSON.parse(JSON.stringify(this.savedConfig)) : null;
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="r-btn-spinner" aria-hidden="true"></span>' + (BYD.i18n.t('common.saving') || 'Saving…');
        }
        try {
            const activeTab = this._activeTabId();
            // Per-tab dispatch — each branch only writes endpoints whose data
            // could have been edited on the visible tab. Saves on Quality
            // tab no longer overwrite Storage/Proximity-Guard prefs the user
            // may have changed on another device while this tab was open.
            let storageData = {};
            let qualityRejectedFields = [];
            const prevFps = this.savedConfig ? this.savedConfig.cameraFps : 15;

            if (activeTab === 'quality') {
                // Single recording quality tier replaces the legacy parallel
                // recordingBitrate (LOW/MEDIUM/HIGH) + recordingQuality
                // (LOW/REDUCED/NORMAL) keys. Server still accepts the old
                // recordingBitrate key for backward compat, but we no longer
                // send it.
                // streamingQuality is owned by the camera controller (index.html)
                // — do not include it here so we don't overwrite a setting
                // the user changed on the live view.
                const qResp = await fetch('/api/settings/quality', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        recordingQuality: this.config.recordingQuality,
                        recordingCodec: this.config.recordingCodec,
                        cameraFps: this.config.cameraFps,
                        segmentDurationMinutes: this.config.segmentDurationMinutes
                    })
                });
                const qData = await qResp.json();
                if (!qResp.ok || !qData || qData.success !== true) {
                    throw new Error((qData && qData.error) || ('quality ' + qResp.status));
                }
                // Surface field-level rejections in the final toast (instead
                // of firing a separate warn toast that collides with the
                // success toast at the end of saveSettings).
                if (qData.rejected && qData.rejected.length) {
                    qualityRejectedFields = qData.rejected.map(function (r) { return r.field; });
                }
                // Mirror codec + tier into the unified store so other pages
                // that read from there see the new values. Note: legacy
                // `bitrate` key is no longer written; the single `quality`
                // tier (ECONOMY..MAX) is the source of truth.
                // rectifyStrength rides the same POST so Apply persists the
                // slider value alongside codec/tier — debounced live POSTs
                // already pushed to the daemon, this is the durable write.
                var rectifyToSave = (typeof this.config.rectifyStrength === 'number')
                    ? this.config.rectifyStrength : 0;
                if (rectifyToSave < 0) rectifyToSave = 0;
                if (rectifyToSave > 100) rectifyToSave = 100;
                const unifiedData = { rectifyStrength: rectifyToSave };
                if (qualityRejectedFields.indexOf('recordingCodec') === -1) {
                    unifiedData.codec = this.config.recordingCodec;
                }
                if (qualityRejectedFields.indexOf('recordingQuality') === -1) {
                    unifiedData.quality = this.config.recordingQuality;
                    unifiedData.recordingQuality = this.config.recordingQuality;
                }
                const uResp = await fetch('/api/settings/unified', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        section: 'recording',
                        data: unifiedData
                    })
                });
                const uData = await uResp.json();
                if (!uResp.ok || !uData || uData.success !== true) {
                    throw new Error((uData && uData.error) || ('unified ' + uResp.status));
                }
            } else if (activeTab === 'capture') {
                const mResp = await fetch('/api/recording/mode', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ mode: this.config.recordingMode })
                });
                const mData = await mResp.json();
                if (!mResp.ok || !mData || mData.status !== 'ok') {
                    throw new Error((mData && mData.message) || ('mode ' + mResp.status));
                }
                const modeResp = await fetch('/api/settings/unified', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        section: 'recording',
                        data: { mode: this.config.recordingMode }
                    })
                });
                const modeData = await modeResp.json();
                if (!modeResp.ok || !modeData || modeData.success !== true) {
                    throw new Error((modeData && modeData.error) || ('mode mirror ' + modeResp.status));
                }
                const proxResp = await fetch('/api/settings/unified', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        section: 'proximityGuard',
                        data: this.config.proximityGuard
                    })
                });
                const proxData = await proxResp.json();
                if (!proxResp.ok || !proxData || proxData.success !== true) {
                    throw new Error((proxData && proxData.error) || ('proximity guard ' + proxResp.status));
                }
            } else if (activeTab === 'storage') {
                const storageResp = await fetch('/api/settings/storage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        recordingsLimitMb: this.config.recordingsLimitMb,
                        recordingsStorageType: this.config.recordingsStorageType
                    })
                });
                storageData = await storageResp.json();
                // The daemon answers HTTP 200 with {success:false} when a
                // requested storage-TYPE change is rejected (target volume
                // unavailable). The server handles the type change FIRST and
                // returns before touching the limit, so on rejection neither
                // field was committed — revert both optimistic fields to the
                // last-applied baseline, re-render, and throw so the catch shows
                // an error toast instead of a false "applied" + baking the
                // rejected value into savedConfig.
                if (!storageResp.ok || !storageData || storageData.success !== true) {
                    if (this.savedConfig) {
                        this.config.recordingsStorageType = this.savedConfig.recordingsStorageType;
                        this.config.recordingsLimitMb = this.savedConfig.recordingsLimitMb;
                    }
                    this.updateStorageLimitUI();
                    this.updateStorageTypeUI();
                    throw new Error((storageData && storageData.error)
                        || ('storage ' + storageResp.status));
                }
                // Re-sync config to the value the daemon actually committed
                // (it clamps to the active volume ceiling). This closes the
                // stale-read window: the savedConfig snapshot below — and any
                // reload that fires after — now baseline off the true persisted
                // value instead of the optimistic pre-save number.
                if (typeof storageData.recordingsLimitMb === 'number') {
                    this.config.recordingsLimitMb = storageData.recordingsLimitMb;
                }
                if (storageData.recordingsStorageType) {
                    this.config.recordingsStorageType = storageData.recordingsStorageType;
                }
            } else if (activeTab === 'oem') {
                const oemMode = this.config.oemRecordingMode || 'off';
                let oemErr = null;
                try {
                    const oemResp = await fetch('/api/oem-dashcam/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ recordingMode: oemMode })
                    });
                    const oemData = await oemResp.json();
                    if (!oemResp.ok || !oemData || oemData.success !== true) {
                        throw new Error((oemData && oemData.error)
                            || ('oem ' + oemResp.status));
                    }
                } catch (e) {
                    oemErr = e;
                }
                // Always re-hydrate so the radio reflects the daemon's
                // actual mode (whether the POST succeeded or not). Without
                // this, a rejected mode change leaves the picker showing a
                // value the server refused to accept.
                // A rejected pick is dropped, so no later load can keep showing it.
                if (oemErr && this.savedConfig) this.config.oemRecordingMode = this.savedConfig.oemRecordingMode;
                if (!oemErr && oemMode !== 'off') this._pollOemDashcamUntilSettled();
                else { try { await this.loadOemDashcam(true); } catch (_) {} }
                if (oemErr) throw oemErr;
            } else {
                // Status tab is read-only (declared readOnly: true in the
                // OT_TABS manifest), so app-tabs.js hides the Apply button
                // there. Defensive: nothing to save → no-op.
            }

            // Only this tab was posted, so other tabs' edits keep their baseline and stay pending.
            const baselineBefore = this.savedConfig;
            this.savedConfig = JSON.parse(JSON.stringify(this.config));
            if (baselineBefore) {
                Object.keys(this._recTabFieldMap).forEach(tabId => {
                    if (tabId === activeTab) return;
                    this._recTabFieldMap[tabId].forEach(field => {
                        if (!Object.prototype.hasOwnProperty.call(baselineBefore, field)) return;
                        const v = baselineBefore[field];
                        this.savedConfig[field] = (v === undefined) ? v : JSON.parse(JSON.stringify(v));
                    });
                });
            }
            if (activeTab === 'quality' && savedBefore) {
                qualityRejectedFields.forEach(field => {
                    if (Object.prototype.hasOwnProperty.call(savedBefore, field)) {
                        this.savedConfig[field] = savedBefore[field];
                    }
                });
            }
            this.hasUnsavedChanges = false;
            // Update timestamp to prevent immediate reload overwriting our changes
            this.lastConfigTimestamp = Date.now();
            this.markChanged();
            // savedConfig caught up to config — re-render so the "saved →
            // pending" arrow disappears and the new value is the new baseline.
            this.renderActiveEstimate();
            // If quality tab changes touched codec or fps, the per-tier
            // qualityEquivalent shifts. Refetch the options table so the
            // subtitle text matches the new active codec/fps.
            if (activeTab === 'quality') {
                this.refetchQualityOptions();
            }

            // Refresh storage stats after save (cleanup may have run)
            setTimeout(() => this.loadStorageStats(), 1000);

            // Toast policy: a single toast at the end of save. Severity is
            // derived from the most-pessimistic outcome — if the server kept
            // ANY field at its prior value (rejected[]), we show 'warn'. If
            // ALL submitted recording knobs were rejected we don't claim
            // "applied". Otherwise success path is unchanged.
            let msg;
            let severity = 'success';
            if (activeTab === 'quality' && qualityRejectedFields.length) {
                const fields = qualityRejectedFields.join(', ');
                const submittedQualityFieldCount = 4; // recordingQuality, recordingCodec, cameraFps, segmentDurationMinutes
                if (qualityRejectedFields.length >= submittedQualityFieldCount) {
                    msg = BYD.i18n.t('recording.values_rejected_all', {fields: fields}) || ('No changes applied — values rejected: ' + fields);
                    severity = 'error';
                } else {
                    msg = BYD.i18n.t('recording.kept_old_values', {applied: BYD.i18n.t('recording.settings_applied'), fields: fields})
                        || (BYD.i18n.t('recording.settings_applied') + ' — but kept old values for: ' + fields);
                    severity = 'warn';
                }
            } else {
                msg = BYD.i18n.t('recording.settings_applied');
                if (activeTab === 'quality' && this.config.recordingCodec === 'H265') {
                    msg += ' - ' + BYD.i18n.t('recording.h265_next_recording');
                }
                if (activeTab === 'quality' && this.config.cameraFps !== prevFps) {
                    msg += ' - ' + BYD.i18n.t('recording.fps_next_acc_on');
                }
                if (storageData.cleanup && storageData.cleanup.recordingsToDelete) {
                    msg = BYD.i18n.t('recording.settings_applied_deleting', {files: storageData.cleanup.recordingsFilesEstimate, size: storageData.cleanup.recordingsToDelete});
                }
            }

            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, severity);
        } catch (e) {
            console.error('recording.saveSettings error:', e);
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.save_settings_failed'), 'error');
        } finally {
            if (btn) {
                btn.innerHTML = origHtml;
                // markChanged() reapplies disabled-state based on dirty flag.
                this.markChanged();
            }
        }
    },

    // ==================== Telemetry Overlay ====================

    // Cached field catalog (list of selectable keys) + last-known fields per
    // flow, so a checklist edit can POST the whole flow array back.
    _telemetryCatalog: null,
    _telemetryFields: { accOn: [], surveillance: [], oemDashcam: [] },
    // Field edits auto-POST, so hasUnsavedChanges never trips and the 10s
    // reloadConfig poller can re-render a grid between a click and its POST,
    // visually reverting the checkbox. Skip re-rendering while one is in flight.
    _telemetryFieldPostInFlight: 0,

    async loadTelemetryOverlay() {
        const writeVersion = this._telemetryWriteVersion;
        try {
            const resp = await fetch('/api/settings/telemetry-overlay');
            const data = await resp.json();
            if (data.success && writeVersion === this._telemetryWriteVersion) {
                const toggle = document.getElementById('telemetryOverlayEnabled');
                if (toggle) toggle.checked = data.enabled || false;
                this._telemetryCatalog = data.fieldCatalog || null;
                if (data.fields) this._telemetryFields = data.fields;
                // Render the ACC-on flow checklist and reflect enabled state.
                this.renderTelemetryFields('accOn', 'telemetryFieldsAccOnGrid');
                this.updateTelemetryFieldsVisibility('accOn', !!(toggle && toggle.checked));
                // Re-render the OEM grid too: it shares this catalog and may have
                // rendered empty if loadOemDashcam won the init race.
                const oemCb = document.getElementById('oemTelemetryOverlay');
                if (oemCb) {
                    this.renderTelemetryFields('oemDashcam', 'telemetryFieldsOemDashcamGrid');
                    this.updateTelemetryFieldsVisibility('oemDashcam', !!oemCb.checked);
                }
            }
        } catch (e) {
            console.warn('Failed to load telemetry overlay state:', e);
        }
    },

    // Human-readable label for a field key. Uses i18n when present, falls back
    // to a built-in English map so the checklist is never blank.
    telemetryFieldLabel(key) {
        const fallback = {
            speed: 'Speed', gear: 'Gear', accelPedal: 'Accelerator',
            brakePedal: 'Brake', seatbeltDriver: 'Driver seatbelt',
            seatbeltPassenger: 'Passenger seatbelt', turnSignals: 'Turn signals',
            timestamp: 'Date & time', batteryPercent: 'Battery %',
            voltage12v: '12V voltage', lowBeam: 'Low beam',
            highBeam: 'High beam', location: 'GPS location',
            vin: 'VIN'
        };
        const i18nKey = 'recording.telemetry_field_' + key;
        const t = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(i18nKey) : null;
        return (t && t !== i18nKey) ? t : (fallback[key] || key);
    },

    // Build the chip grid for a flow from the cached catalog + selection.
    // Reuses the .checkbox-item chip look (hidden input + .active state).
    renderTelemetryFields(flow, gridId) {
        const grid = document.getElementById(gridId);
        if (!grid || !this._telemetryCatalog) return;
        // Don't repaint over an edit whose POST hasn't landed yet.
        if (this._telemetryFieldPostInFlight > 0) return;
        const selected = new Set(this._telemetryFields[flow] || []);
        grid.innerHTML = '';
        this._telemetryCatalog.forEach(f => {
            const id = 'telField_' + flow + '_' + f.key;
            const label = document.createElement('label');
            label.className = 'checkbox-item' + (selected.has(f.key) ? ' active' : '');
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.id = id;
            cb.checked = selected.has(f.key);
            cb.addEventListener('change', () => {
                label.classList.toggle('active', cb.checked);
                this.toggleTelemetryField(flow, f.key, cb.checked, gridId);
            });
            const span = document.createElement('span');
            span.textContent = this.telemetryFieldLabel(f.key);
            label.appendChild(cb);
            label.appendChild(span);
            grid.appendChild(label);
        });
    },

    updateTelemetryFieldsVisibility(flow, visible) {
        const wrap = document.getElementById('telemetryFields'
            + flow.charAt(0).toUpperCase() + flow.slice(1));
        if (wrap) wrap.style.display = visible ? '' : 'none';
    },

    // Collect the currently-checked keys for a flow from the DOM grid.
    _collectFlowFields(flow, gridId) {
        const grid = document.getElementById(gridId);
        const keys = [];
        if (grid) {
            grid.querySelectorAll('input[type=checkbox]').forEach(cb => {
                if (cb.checked) keys.push(cb.id.replace('telField_' + flow + '_', ''));
            });
        }
        return keys;
    },

    async toggleTelemetryField(flow, key, checked, gridId) {
        if (!this._hydrated) return;
        const field = document.getElementById('telField_' + flow + '_' + key);
        if (field) field.disabled = true;
        const keys = this._collectFlowFields(flow, gridId);
        this._telemetryFields[flow] = keys;
        const body = { fields: {} };
        body.fields[flow] = keys;
        this._telemetryFieldPostInFlight++;
        let failed = false;
        try {
            const data = await this._postTelemetry(body);
            failed = !data;
            if (data && data.fields) this._telemetryFields = data.fields;
        } finally {
            this._telemetryFieldPostInFlight--;
            if (field) field.disabled = false;
            if (this._telemetryFieldPostInFlight === 0) {
                if (failed) {
                    await this.loadTelemetryOverlay();
                } else {
                    this.renderTelemetryFields('accOn', 'telemetryFieldsAccOnGrid');
                    this.renderTelemetryFields('oemDashcam', 'telemetryFieldsOemDashcamGrid');
                }
            }
        }
    },

    async toggleTelemetryOverlay() {
        const toggle = document.getElementById('telemetryOverlayEnabled');
        if (!toggle || !this._hydrated || toggle.disabled) return;
        const enabled = toggle.checked;
        toggle.disabled = true;
        try {
            const data = await this._postTelemetry({ enabled });
            if (data) {
                toggle.checked = data.enabled;
                this.updateTelemetryFieldsVisibility('accOn', !!data.enabled);
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(data.enabled ? BYD.i18n.t('recording.telemetry_overlay_enabled') : BYD.i18n.t('recording.telemetry_overlay_disabled'), 'success');
                }
            } else {
                toggle.checked = !enabled;
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.overlay_update_failed'), 'error');
            }
        } catch (e) {
            toggle.checked = !enabled;
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.overlay_update_failed'), 'error');
        } finally {
            toggle.disabled = false;
        }
    },

    // ==================== Audio Recording ====================
    //
    // Toggles cabin-mic capture in the app process. Capture only fires
    // while the daemon is in an ACC-on recording mode; surveillance
    // recordings are never audio-muxed regardless of this toggle.

    async loadAudioRecording() {
        if (this._audioWritesPending > 0) return;
        const writeVersion = this._audioWriteVersion;
        try {
            const resp = await fetch('/api/settings/audio-recording');
            const data = await resp.json();
            if (data.success
                    && this._audioWritesPending === 0
                    && writeVersion === this._audioWriteVersion) {
                const toggle = document.getElementById('audioRecordingEnabled');
                if (toggle) toggle.checked = data.enabled || false;
            }
        } catch (e) {
            console.warn('Failed to load audio-recording state:', e);
        }
    },

    async toggleAudioRecording() {
        const toggle = document.getElementById('audioRecordingEnabled');
        if (!toggle || !this._hydrated || toggle.disabled) return;
        const enabled = toggle.checked;
        this._audioWriteVersion++;
        this._audioWritesPending++;
        toggle.disabled = true;
        try {
            const data = await this._postJson('/api/settings/audio-recording', { enabled });
            if (data) {
                toggle.checked = data.enabled;
                if (BYD.utils && BYD.utils.toast) {
                    const key = data.enabled ? 'recording.audio_enabled_toast' : 'recording.audio_disabled_toast';
                    BYD.utils.toast(BYD.i18n.t(key), 'success');
                }
            } else {
                toggle.checked = !enabled;
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.audio_update_failed'), 'error');
            }
        } catch (e) {
            toggle.checked = !enabled;
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.audio_update_failed'), 'error');
        } finally {
            this._audioWritesPending--;
            toggle.disabled = false;
        }
    },

    // ==================== Geocoding (Place Tagging) ====================
    //
    // Per-flow split — this page controls the "recording" flow (dashcam +
    // proximity guard). The surveillance.html page exposes the matching
    // surveillance flow toggles. Both pages share the advanced custom
    // Nominatim URL field; whichever page writes last wins.
    //
    // Toggling at any time is safe — the recorder reads the config on
    // every captureStartLocationSnapshot() call, so a flip mid-recording
    // takes effect at the next rotation/start.

    async loadGeocoding() {
        if (this._geocodingWritesPending > 0) return false;
        const writeVersion = this._geocodingWriteVersion;
        try {
            const resp = await fetch('/api/settings/geocoding');
            const data = await resp.json();
            if (!data.success
                    || this._geocodingWritesPending > 0
                    || writeVersion !== this._geocodingWriteVersion) return false;
            const recCfg = data.recording || {};
            const advCfg = data.advanced || {};
            const swEnabled = document.getElementById('geocodingEnabled');
            const swOnline = document.getElementById('geocodingOnline');
            const inputUrl = document.getElementById('geocodingCustomUrl');
            if (swEnabled) swEnabled.checked = !!recCfg.enabled;
            if (swOnline) {
                swOnline.checked = !!recCfg.allowOnline;
                swOnline.disabled = !recCfg.enabled;
            }
            if (inputUrl) {
                inputUrl.value = advCfg.customNominatimBase || '';
                inputUrl.disabled = !recCfg.enabled;
            }
            return true;
        } catch (e) {
            console.warn('Failed to load geocoding state:', e);
            return false;
        }
    },

    async _postGeocoding(delta) {
        this._geocodingWriteVersion++;
        this._geocodingWritesPending++;
        try {
            return await this._postJson('/api/settings/geocoding', delta);
        } finally {
            this._geocodingWritesPending--;
        }
    },

    async _reloadGeocodingWhenIdle() {
        let queue;
        do {
            queue = this._writeQueue;
            await queue;
            await Promise.resolve();
        } while (queue !== this._writeQueue || this._geocodingWritesPending > 0);
        return this.loadGeocoding();
    },

    async toggleGeocodingEnabled() {
        const sw = document.getElementById('geocodingEnabled');
        const swOnline = document.getElementById('geocodingOnline');
        const inputUrl = document.getElementById('geocodingCustomUrl');
        if (!sw || !this._hydrated || sw.disabled) return;
        const enabled = sw.checked;
        sw.disabled = true;
        if (swOnline) swOnline.disabled = true;
        if (inputUrl) inputUrl.disabled = true;
        try {
            const result = await this._postGeocoding({ recording: { enabled } });
            if (result) {
                // Echoed authoritative state — UI mirrors what the daemon wrote.
                const rec = result.recording || {};
                sw.checked = !!rec.enabled;
                if (BYD.utils && BYD.utils.toast) {
                    const key = rec.enabled
                        ? 'recording.geocoding_enabled_toast'
                        : 'recording.geocoding_disabled_toast';
                    BYD.utils.toast(BYD.i18n.t(key), 'success');
                }
            } else {
                sw.checked = !enabled;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('recording.geocoding_update_failed'), 'error');
                }
            }
        } finally {
            sw.disabled = false;
            if (swOnline) swOnline.disabled = !sw.checked;
            if (inputUrl) inputUrl.disabled = !sw.checked;
        }
    },

    async toggleGeocodingOnline() {
        const sw = document.getElementById('geocodingOnline');
        if (!sw || !this._hydrated || sw.disabled) return;
        const allowOnline = sw.checked;
        sw.disabled = true;
        try {
            const result = await this._postGeocoding({ recording: { allowOnline } });
            if (result && result.recording) {
                sw.checked = !!result.recording.allowOnline;
            } else {
                sw.checked = !allowOnline;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('recording.geocoding_update_failed'), 'error');
                }
            }
        } finally {
            sw.disabled = !document.getElementById('geocodingEnabled').checked;
        }
    },

    async saveGeocodingCustomUrl() {
        const input = document.getElementById('geocodingCustomUrl');
        if (!input || !this._hydrated || input.disabled) return;
        const url = (input.value || '').trim();
        input.disabled = true;
        try {
            const result = await this._postGeocoding({
                advanced: { customNominatimBase: url }
            });
            if (result && result.advanced) {
                input.value = result.advanced.customNominatimBase || '';
            } else {
                await this._reloadGeocodingWhenIdle();
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('recording.geocoding_update_failed'), 'error');
                }
            }
        } finally {
            input.disabled = !document.getElementById('geocodingEnabled').checked;
        }
    },

    // ==================== Native DVR (com.byd.cdr) ====================
    //
    // Toggles the factory dashcam package. When disabled, our OEM Dashcam
    // pipeline owns the AVMCamera without contention. The card is hidden
    // entirely when the package isn't installed (state === 'not_installed')
    // — distinguishes "no DVR sensor on this vehicle" from "DVR present but
    // currently active".

    _renderNativeDvrCard(state) {
        const card = document.getElementById('nativeDvrCard');
        const label = document.getElementById('nativeDvrStatusLabel');
        const btn = document.getElementById('nativeDvrToggleBtn');
        if (!card || !label || !btn) return;
        if (state === 'not_installed') {
            card.style.display = 'none';
            return;
        }
        // If the OEM run has already gated the card hidden because the
        // camera id isn't configured, don't resurrect it from a parallel
        // /api/oem-dashcam/native-dvr/status load that came back after
        // loadOemDashcam.
        if (this._oemIdUnset !== true) {
            card.style.display = '';
        }
        if (state === 'disabled') {
            label.setAttribute('data-i18n', 'oem_dashcam.native_dvr_status_disabled');
            label.textContent = (BYD.i18n && BYD.i18n.t)
                ? BYD.i18n.t('oem_dashcam.native_dvr_status_disabled')
                : 'Status: Disabled by OverDrive';
            btn.setAttribute('data-i18n', 'oem_dashcam.native_dvr_enable');
            btn.textContent = (BYD.i18n && BYD.i18n.t)
                ? BYD.i18n.t('oem_dashcam.native_dvr_enable')
                : 'Re-enable native DVR';
            // Re-enable is a benign action — keep the primary visual treatment.
            btn.classList.remove('btn-danger');
            btn.classList.add('btn-primary');
            btn.dataset.action = 'enable';
        } else {
            // 'enabled' (default)
            label.setAttribute('data-i18n', 'oem_dashcam.native_dvr_status_enabled');
            label.textContent = (BYD.i18n && BYD.i18n.t)
                ? BYD.i18n.t('oem_dashcam.native_dvr_status_enabled')
                : 'Status: Enabled';
            btn.setAttribute('data-i18n', 'oem_dashcam.native_dvr_disable');
            btn.textContent = (BYD.i18n && BYD.i18n.t)
                ? BYD.i18n.t('oem_dashcam.native_dvr_disable')
                : 'Disable native DVR';
            btn.classList.remove('btn-primary');
            btn.classList.add('btn-danger');
            btn.dataset.action = 'disable';
        }
    },

    async loadNativeDvr() {
        try {
            const resp = await fetch('/api/oem-dashcam/native-dvr/status');
            const data = await resp.json();
            if (data && data.success) {
                this._renderNativeDvrCard(data.state || 'enabled');
            }
        } catch (e) {
            console.warn('Failed to load native DVR state:', e);
        }
    },

    async toggleNativeDvr() {
        const btn = document.getElementById('nativeDvrToggleBtn');
        if (!btn) return;
        const action = btn.dataset.action === 'enable' ? 'enable' : 'disable';
        const url = '/api/oem-dashcam/native-dvr/' + action;
        // Disable the button mid-shell to prevent double-click double-pm.
        btn.disabled = true;
        try {
            const resp = await fetch(url, { method: 'POST' });
            const data = await resp.json();
            const newState = (data && data.state) || 'enabled';
            this._renderNativeDvrCard(newState);
            if (BYD.utils && BYD.utils.toast) {
                if (data && data.success) {
                    const key = action === 'disable'
                        ? 'oem_dashcam.native_dvr_disable_toast'
                        : 'oem_dashcam.native_dvr_enable_toast';
                    BYD.utils.toast(BYD.i18n.t(key), 'success');
                } else {
                    BYD.utils.toast(
                        (data && data.error) || BYD.i18n.t('common.error'),
                        'error');
                }
            }
        } catch (e) {
            console.warn('Native DVR toggle failed:', e);
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('common.error'), 'error');
            }
        } finally {
            btn.disabled = false;
        }
    },

    /**
     * OEM Dashcam settings card. Hidden until /api/settings/quality reports
     * an `oemDashcam.oemDashcamCameraId >= 0` (i.e. the user has picked a
     * camera id in the camera-mapping dialog). When visible, the user can
     * toggle the feature and set ACC-off behaviour. Settings persist to the
     * `oemDashcam` UCM section; the daemon-side pipeline lifecycle reads
     * them at boot/start.
     *
     * Until Phase B (daemon-side pipeline lifecycle) lands, the toggle
     * persists config and shows a "pipeline not yet available" hint so the
     * user knows the recorder isn't actually running yet.
     */
    async loadOemDashcam(forceServerMode) {
        // Token-based dedup so concurrent loads (visibilitychange +
        // 10s reload + post-toggle poll's terminal refresh) can't
        // race: only the most-recently-started load mutates DOM.
        const myToken = (this._oemLoadToken || 0) + 1;
        this._oemLoadToken = myToken;
        const cardForBusy = document.getElementById('oemDashcamCard');
        const selectorForBusy = cardForBusy ? cardForBusy.querySelector('.mode-selector') : null;
        if (selectorForBusy) {
            selectorForBusy.setAttribute('aria-busy', 'true');
            selectorForBusy.setAttribute('data-hydrating', 'true');
        }
        // Captured by the finally so the badge clobber path can
        // distinguish "this run early-returned because the camera id
        // is unset" (badge MUST stay hidden) from "this run completed
        // a normal hydration" (badge re-armed and visible).
        let runIsIdUnset = false;
        try {
            // First fetch is the gate (oemDashcamCameraId < 0 ⇒ early
            // return). Once we know the card is rendered, fire the two
            // independent fetches (config + telemetry overlay) in parallel
            // — they have no read-order dependency.
            const qres = await fetch('/api/settings/quality');
            const qdata = await qres.json();
            if (this._oemLoadToken !== myToken) return;   // superseded
            const oem = qdata && qdata.oemDashcam;
            const card = document.getElementById('oemDashcamCard');
            if (!card) return;
            const idUnset = !oem
                || typeof oem.oemDashcamCameraId !== 'number'
                || oem.oemDashcamCameraId < 0;
            // Cached on `this` so the parallel _renderNativeDvrCard()
            // race-in (loadNativeDvr's fetch resolves AFTER this run
            // hides the card) and the loadOemDashcam catch path can
            // both observe the latest id-unset state.
            this._oemIdUnset = idUnset;
            const idUnsetRow = document.getElementById('oemDashcamIdUnsetRow');
            if (idUnsetRow) idUnsetRow.style.display = idUnset ? '' : 'none';
            const modeRow = document.getElementById('oemRecordingModeRow');
            const modeSelector = card.querySelector('.mode-selector');
            const telRow = document.getElementById('oemTelemetryOverlayRow');
            const statusRow = document.getElementById('oemPipelineStatusRow');
            [modeRow, modeSelector, telRow, statusRow].forEach(el => {
                if (el) el.style.display = idUnset ? 'none' : '';
            });
            // Native DVR card hides when there's no OEM camera id — the
            // factory dashcam disable is meaningless without an OEM
            // pipeline to take its place.
            const nativeDvrCard = document.getElementById('nativeDvrCard');
            if (nativeDvrCard && idUnset) nativeDvrCard.style.display = 'none';
            // Status badge is meaningless when no camera id is configured —
            // hide it so the card-header doesn't show a stale "Idle" green
            // pill next to "Camera ID not configured".
            const idUnsetBadge = document.getElementById('oemPipelineBadge');
            if (idUnsetBadge) idUnsetBadge.style.display = idUnset ? 'none' : '';
            if (idUnset) { runIsIdUnset = true; return; }

            const telemetryWriteVersion = this._telemetryWriteVersion;
            const [sdata, tdata] = await Promise.all([
                fetch('/api/oem-dashcam/config').then(r => r.json()).catch(() => ({})),
                fetch('/api/settings/telemetry-overlay').then(r => r.json()).catch(() => ({})),
            ]);
            if (this._oemLoadToken !== myToken) return;   // superseded
            // Validate the server-reported mode against the known set;
            // fall back to 'off' so a typo / older daemon never leaves
            // the radio group with NO selection (which is the visual
            // "default not selected" the user reported).
            let mode = (sdata && sdata.recordingMode) ? String(sdata.recordingMode).toLowerCase() : 'off';
            if (mode !== 'off' && mode !== 'continuous' && mode !== 'smart') mode = 'off';
            // Mirror onto this.config so the dirty diff has a stable key
            // to compare against. The savedConfig snapshot is updated too
            // so a fresh load doesn't immediately mark the OEM tab dirty.
            // An unsaved pick survives: this load re-runs on every reload and OEM-tab activation.
            const oemPickPending = !forceServerMode && !!this.savedConfig
                && this.config.oemRecordingMode !== this.savedConfig.oemRecordingMode;
            if (oemPickPending) {
                this.savedConfig.oemRecordingMode = mode;
                mode = this.config.oemRecordingMode;
                this.markChanged();
            } else {
                this.config.oemRecordingMode = mode;
                if (this.savedConfig) this.savedConfig.oemRecordingMode = mode;
            }
            // Clear any prior checked state so we don't end up with two
            // radios pretending to be selected (paranoia — radio groups
            // already enforce one-of, but explicit reset survives DOM
            // clones / replaceChild churn).
            document.querySelectorAll('input[name="oemRecordingMode"]').forEach(function (r) {
                r.checked = (r.value === mode);
                const card = r.closest('.mode-option').querySelector('.mode-card');
                if (card) card.classList.toggle('mode-card-active', r.checked);
            });
            // Hydrated — drop the dim/busy state.
            const selector = card.querySelector('.mode-selector');
            if (selector) {
                selector.removeAttribute('aria-busy');
                selector.removeAttribute('data-hydrating');
            }

            const telCb = document.getElementById('oemTelemetryOverlay');
            if (telemetryWriteVersion === this._telemetryWriteVersion) {
                if (telCb) telCb.checked = !!(tdata && tdata.oemDashcamEnabled);
                // OEM field checklist mirrors the ACC-on one but its own flow.
                // Seed the shared cache from this response: both loaders run in one
                // Promise.all, so loadTelemetryOverlay may not have populated it yet
                // and the grid would render empty with nothing to retry it.
                if (tdata && tdata.fieldCatalog) this._telemetryCatalog = tdata.fieldCatalog;
                if (tdata && tdata.fields) this._telemetryFields = tdata.fields;
                this.renderTelemetryFields('oemDashcam', 'telemetryFieldsOemDashcamGrid');
                this.updateTelemetryFieldsVisibility('oemDashcam', !!(telCb && telCb.checked));
            }

            // Surface pipeline + recording state. Status badge + status row
            // both reflect the same source so the user sees one consistent
            // truth.
            const statusEl = document.getElementById('oemPipelineStatus');
            const statusHint = document.getElementById('oemPipelineStatusHint');
            const badge = document.getElementById('oemPipelineBadge');
            const off = mode === 'off';
            const running = !!(sdata && sdata.pipelineRunning);
            const recording = !!(sdata && sdata.recording);
            // Off mode masks the error state — when the user has
            // explicitly disabled the pipeline, a previous lastStartError
            // is stale and shouldn't paint the badge red. Errored is
            // also mutually exclusive with active/inactive below. Two
            // distinct sources: lastStartError (failed to come up) and
            // lastWriteError (disk writer aborted mid-clip — SD unmount /
            // full volume). Either one paints the badge red so the user
            // doesn't see "Recording" while the muxer is dead.
            const startErr = !!(sdata && sdata.lastStartError && !running && !recording && !off);
            const writeErr = !!(sdata && sdata.lastWriteError && !off);
            const errored = startErr || writeErr;
            const errorMsg = (sdata && sdata.lastWriteError) || (sdata && sdata.lastStartError) || '';
            if (badge) {
                // Compute exactly one of {errored, active, inactive} so
                // the toggles can never end up with two classes set
                // (which would let CSS specificity pick the wrong color).
                const isActive = !off && (running || recording);
                const isInactive = !errored && !isActive;
                badge.classList.toggle('errored', errored);
                badge.classList.toggle('active', isActive);
                badge.classList.toggle('inactive', isInactive);
                // Track the localized key on data-i18n so a later
                // language switch (which re-runs i18n.applyTranslations
                // over every [data-i18n] node) finds the CURRENT key
                // instead of the stale "status_idle" sitting in the
                // markup. Without this, switching languages mid-session
                // resets the badge to "Idle" regardless of true state.
                const badgeKey = errored ? 'oem_dashcam.status_error'
                    : off ? 'oem_dashcam.status_idle'
                    : recording ? 'oem_dashcam.status_recording'
                    : running ? 'oem_dashcam.status_armed'
                    : 'oem_dashcam.status_starting';
                badge.setAttribute('data-i18n', badgeKey);
                badge.textContent = BYD.i18n.t(badgeKey);
                // Reveal post-hydration so the localized text appears
                // without flashing the English "Idle" inline default.
                // Use opacity (not visibility) so the badge stays in
                // the a11y tree even before hydration.
                badge.style.opacity = '';
            }
            if (statusEl) {
                if (errored) {
                    // Concatenated string — strip data-i18n so a language
                    // switch's mass re-translate doesn't overwrite the
                    // appended dynamic error suffix.
                    const prefixKey = writeErr
                        ? 'oem_dashcam.write_error_prefix'
                        : 'oem_dashcam.start_error_prefix';
                    statusEl.removeAttribute('data-i18n');
                    statusEl.textContent = BYD.i18n.t(prefixKey) + errorMsg;
                    // Toast once per distinct error string. visibilitychange
                    // + 10s reload would otherwise re-fire the same error
                    // toast every poll, drowning legitimate notifications.
                    if (errorMsg !== this._lastShownOemError) {
                        this._lastShownOemError = errorMsg;
                        if (BYD.utils && BYD.utils.toast) BYD.utils.toast(statusEl.textContent, 'error');
                    }
                } else {
                    // Any non-error terminal state — clear the dedup
                    // marker so a future error re-toasts once.
                    this._lastShownOemError = null;
                    const statusKey = off ? 'oem_dashcam.status_idle'
                        : recording ? 'oem_dashcam.status_recording'
                        : running ? 'oem_dashcam.status_armed'
                        : 'oem_dashcam.status_starting';
                    statusEl.setAttribute('data-i18n', statusKey);
                    statusEl.textContent = BYD.i18n.t(statusKey);
                }
            }
            if (statusHint) {
                const hintKey = off ? 'oem_dashcam.status_hint_off'
                    : (mode === 'continuous'
                        ? 'oem_dashcam.status_hint_continuous_recording'
                        : 'oem_dashcam.status_hint_smart_recording');
                statusHint.setAttribute('data-i18n', hintKey);
                statusHint.textContent = BYD.i18n.t(hintKey);
            }
            // Mark that this run completed a real hydration. The catch
            // path below uses this to decide whether to clobber the
            // badge to "Idle" — if we've ever hydrated, a transient
            // /api/settings/quality blip should NOT regress the visible
            // state, since the real status is more useful than a hard
            // reset to idle.
            this._oemEverHydrated = true;
        } catch (e) {
            console.warn('Failed to load OEM dashcam settings:', e);
            // Only reset badge if WE are still the most-recent load
            // AND we've never successfully hydrated. Once we have a
            // real status painted, leave it alone — a transient fetch
            // blip shouldn't visually downgrade to "Idle".
            if (this._oemLoadToken === myToken && !this._oemEverHydrated) {
                const b = document.getElementById('oemPipelineBadge');
                if (b) {
                    b.classList.remove('errored', 'active');
                    b.classList.add('inactive');
                    // BYD.i18n is guaranteed loaded by init order — the
                    // shell hydrates i18n before mounting recording.js.
                    b.textContent = BYD.i18n.t('oem_dashcam.status_idle');
                }
            }
        } finally {
            // The dim-clear is unconditional — even a superseded run (token
            // bumped by a concurrent reload) must lift the busy state on
            // its way out. Without this, a network blip during init's call
            // followed by an immediately-superseding visibilitychange call
            // could leave aria-busy="true" sticky if the second call also
            // fails. Costs nothing to clear it twice; costs a permanently
            // un-clickable picker to clear it never.
            if (selectorForBusy) {
                selectorForBusy.removeAttribute('aria-busy');
                selectorForBusy.removeAttribute('data-hydrating');
            }
            // Only the winner reveals the badge.
            // Older loads that lost the token race must not reveal a badge
            // whose text the newer load hasn't yet written. Also clear
            // inline display:'none' from any prior id-unset run so a
            // successful re-load actually shows the badge.
            if (this._oemLoadToken === myToken) {
                const _b = document.getElementById('oemPipelineBadge');
                if (_b) {
                    _b.style.opacity = '';
                    // Only re-reveal the badge when the run completed a
                    // real hydration AND the cached id-unset gate
                    // hasn't flipped on us. runIsIdUnset is local-per
                    // call so a fetch failure mid-run leaves it false;
                    // this._oemIdUnset survives the catch and prevents
                    // a finally-block reveal from undoing a prior
                    // id-unset hide.
                    if (!runIsIdUnset && this._oemIdUnset !== true) _b.style.display = '';
                }
            }
        }
    },

    /**
     * Poll /api/oem-dashcam/config until pipelineRunning flips true or
     * lastStartError is recorded. Refreshes the recording.html OEM card on
     * every transition. Backs off geometrically and gives up after a hard
     * ceiling so a wedged daemon doesn't loop forever.
     *
     * Cancellable: a second call (e.g. user toggles off then on again)
     * cancels the in-flight poll via the _oemPollToken epoch.
     */
    _pollOemDashcamUntilSettled() {
        const token = (this._oemPollToken || 0) + 1;
        this._oemPollToken = token;
        // 0.5s, 1s, 2s, 3s, 4s, 5s, 5s, 5s — total ~25s ceiling. Most
        // starts settle by 6-9s; the long tail covers slow HALs.
        const delays = [500, 1000, 2000, 3000, 4000, 5000, 5000, 5000];
        let prevSerialized = null;
        const tick = async (i) => {
            if (this._oemPollToken !== token) return;       // cancelled
            if (i >= delays.length) {
                // Hard ceiling reached without pipelineRunning or
                // lastStartError. Re-hydrate the UI so the badge can't
                // sit on a stale "Starting…" forever, and surface the
                // timeout to the user with a toast.
                try { await this.loadOemDashcam(); } catch (_) {}
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('oem_dashcam.start_timeout'), 'warn');
                }
                return;
            }
            await new Promise(r => setTimeout(r, delays[i]));
            if (this._oemPollToken !== token) return;
            try {
                const r = await fetch('/api/oem-dashcam/config');
                const d = await r.json();
                // Post-fetch token check — without this a superseded
                // poll's terminal tick whose fetch resolves AFTER the
                // user toggled off would still call loadOemDashcam and
                // overwrite the new state.
                if (this._oemPollToken !== token) return;
                if (!d || !d.success) {
                    if (d && d.starting === false) {
                        // Permanent rejection from the daemon — stop
                        // polling and refresh the card so the user
                        // sees the terminal state immediately.
                        await this.loadOemDashcam();
                        return;
                    }
                    return tick(i + 1);
                }
                if (d.pipelineRunning || d.lastStartError) {
                    await this.loadOemDashcam();
                    return;
                }
                // Mid-startup state changes (e.g. encoder→muxer→writer)
                // each emit a distinct config payload. Refresh the
                // badge whenever the snapshot changes so the user sees
                // progress, not a stuck "Starting…" pill.
                const serialized = JSON.stringify(d);
                if (serialized !== prevSerialized) {
                    prevSerialized = serialized;
                    try { await this.loadOemDashcam(); } catch (_) {}
                    if (this._oemPollToken !== token) return;
                }
                return tick(i + 1);
            } catch (e) {
                return tick(i + 1);
            }
        };
        tick(0);
    },

    async toggleOemTelemetryOverlay() {
        const cb = document.getElementById('oemTelemetryOverlay');
        if (!cb || !this._hydrated || cb.disabled) return;
        const enabled = !!cb.checked;
        cb.disabled = true;
        try {
            // Write only the OEM-specific key. Pano's panoEnabled stays at
            // whatever the user set elsewhere — that's the entire point of
            // the per-pipeline split.
            const data = await this._postTelemetry({
                oemDashcamEnabled: enabled
            });
            if (!data) {
                cb.checked = !enabled;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('common.error'), 'error');
                }
            } else {
                const applied = data.oemDashcamEnabled == null
                    ? enabled : !!data.oemDashcamEnabled;
                cb.checked = applied;
                this.updateTelemetryFieldsVisibility('oemDashcam', applied);
            }
        } catch (e) {
            cb.checked = !enabled;
            console.warn('OEM telemetry overlay toggle failed:', e);
            // Surface the failure to the user — a silent revert of the
            // checkbox state would feel like the click was ignored.
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('common.error'), 'error');
            }
        } finally {
            cb.disabled = false;
        }
    },

    setOemRecordingMode(mode) {
        if (mode !== 'off' && mode !== 'continuous' && mode !== 'smart') return;
        this.config.oemRecordingMode = mode;
        document.querySelectorAll('input[name="oemRecordingMode"]').forEach(function (r) {
            r.checked = (r.value === mode);
            const card = r.closest('.mode-option').querySelector('.mode-card');
            if (card) card.classList.toggle('mode-card-active', r.value === mode);
        });
        this.markChanged();
    },

    // ==================== Recording Layout ====================
    //
    // Standard = 2x2 360 mosaic (default). Dashcam = forward road view on
    // top with the 360 left/rear/right cameras tiled along the bottom.
    // Recordings only; telemetry overlay is preserved in both layouts.

    async loadRecordingLayout() {
        if (this._layoutWritePending) return;
        const writeVersion = this._layoutWriteVersion;
        try {
            const resp = await fetch('/api/settings/recording-layout');
            const data = await resp.json();
            if (data.success
                    && !this._layoutWritePending
                    && writeVersion === this._layoutWriteVersion) {
                this._recordingLayout = data.layout || 'standard';
                this._dashcamUseWindshield = !!data.dashcamUseWindshield;
                this._windshieldAvailable = !!data.windshieldAvailable;
                this._applyRecordingLayoutButtons(this._recordingLayout);

                const wsToggle = document.getElementById('dashcamUseWindshield');
                if (wsToggle) {
                    wsToggle.checked = this._dashcamUseWindshield;
                    wsToggle.disabled = !this._windshieldAvailable;
                }

                const infoLine = document.getElementById('windshieldCameraInfo');
                if (infoLine) {
                    if (!this._windshieldAvailable) {
                        infoLine.textContent = BYD.i18n.t('recording.layout_windshield_unavailable');
                        infoLine.style.display = 'block';
                    } else {
                        infoLine.style.display = 'none';
                    }
                }

                this._updateWindshieldToggleVisibility(this._recordingLayout);
            }
        } catch (e) {
            console.warn('Failed to load recording layout:', e);
        }
    },

    _updateWindshieldToggleVisibility(layout) {
        const subSetting = document.getElementById('dashcamWindshieldRow');
        if (subSetting) {
            subSetting.style.display = layout === 'dashcam' ? 'flex' : 'none';
        }
    },

    _applyRecordingLayoutButtons(layout) {
        const group = document.getElementById('recLayoutBtns');
        if (!group) return;
        group.querySelectorAll('.btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === layout));
    },

    async setRecordingLayout(layout) {
        if (!this._hydrated || this._layoutWritePending) return;
        this._applyRecordingLayoutButtons(layout);
        this._updateWindshieldToggleVisibility(layout);
        await this._saveRecordingLayout();
    },

    async toggleDashcamWindshield() {
        if (!this._hydrated || this._layoutWritePending) return;
        await this._saveRecordingLayout();
    },

    async _saveRecordingLayout() {
        const group = document.getElementById('recLayoutBtns');
        const active = group ? group.querySelector('.btn-toggle.active') : null;
        const layout = active ? active.dataset.value : 'standard';

        const wsToggle = document.getElementById('dashcamUseWindshield');
        const dashcamUseWindshield = wsToggle ? wsToggle.checked : false;

        this._layoutWriteVersion++;
        this._layoutWritePending = true;
        if (group) {
            group.querySelectorAll('.btn-toggle').forEach(btn => { btn.disabled = true; });
        }
        if (wsToggle) wsToggle.disabled = true;
        try {
            const data = await this._postJson('/api/settings/recording-layout', {
                layout,
                dashcamUseWindshield
            });
            if (data) {
                this._recordingLayout = data.layout || 'standard';
                this._dashcamUseWindshield = !!data.dashcamUseWindshield;
                this._windshieldAvailable = !!data.windshieldAvailable;
                this._applyRecordingLayoutButtons(this._recordingLayout);
                this._updateWindshieldToggleVisibility(this._recordingLayout);

                if (wsToggle) {
                    wsToggle.checked = this._dashcamUseWindshield;
                }

                if (BYD.utils && BYD.utils.toast) {
                    const key = this._recordingLayout === 'dashcam'
                        ? 'recording.layout_dashcam_toast'
                        : 'recording.layout_standard_toast';
                    BYD.utils.toast(BYD.i18n.t(key), 'success');
                }
            } else {
                this._applyRecordingLayoutButtons(this._recordingLayout);
                this._updateWindshieldToggleVisibility(this._recordingLayout);
                if (wsToggle) wsToggle.checked = this._dashcamUseWindshield;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('recording.layout_update_failed'), 'error');
                }
            }
        } finally {
            this._layoutWritePending = false;
            if (group) {
                group.querySelectorAll('.btn-toggle').forEach(btn => { btn.disabled = false; });
            }
            if (wsToggle) wsToggle.disabled = !this._windshieldAvailable;
        }
    },

    /**
     * Drive the "Recording Status" card (badge + Current State row) from the
     * live daemon /status payload. core.js calls this on every status poll.
     *
     * Before this hook the card was hard-bound to the static "IDLE"/"Idle"
     * markup in recording.html and never updated by any JS, so it read
     * "not recording" permanently even while a clip was being written. We
     * read the same recordingStatus.{isRecording,modeActive,pipelineRunning}
     * fields the native status pill consumes — isRecording is the raw
     * recorder boolean (momentarily false during the deferred-record window
     * at cold start / ACC-on), and modeActive stays true across it, so we
     * treat (isRecording || (modeActive && pipelineRunning)) as "recording"
     * for the continuous-style modes. PROXIMITY_GUARD's not-recording state
     * is the normal armed/idle state, so it shows "Armed" rather than a
     * false "not recording".
     */
    updateFromStatus(status) {
        var badge = document.getElementById('recStatusBadge');
        var state = document.getElementById('recState');
        if (!badge && !state) return;

        var rec = status && status.recordingStatus;
        // No recordingStatus in the payload (old daemon / daemon not yet up):
        // leave the card on its last-known text rather than blanking it.
        if (!rec) return;

        var mode = rec.configuredMode || 'NONE';
        var isRecording = !!rec.isRecording;
        // Default the compound fields to isRecording so an older daemon that
        // only emits isRecording behaves exactly as the bare flag.
        var modeActive = (typeof rec.modeActive === 'boolean') ? rec.modeActive : isRecording;
        var pipelineRunning = (typeof rec.pipelineRunning === 'boolean') ? rec.pipelineRunning : isRecording;
        // wedged: modeActive is true but the encoder is structurally stuck —
        // nothing is being written. Defaults false (healthy / older daemon).
        var wedged = (rec.wedged === true);
        var gear = rec.gear || 'P';
        var accOn = (rec.accOn === true);
        var isProximity = (mode === 'PROXIMITY_GUARD');
        var off = (mode === 'NONE' || mode === 'UNKNOWN');

        // Mirror the native status pill (StatusOverlayService.updateUI):
        // the deferred-record window (modeActive && pipelineRunning while
        // isRecording is momentarily false) counts as live, EXCEPT when the
        // daemon reports the activation is wedged — then it must fall through
        // to the fault state instead of a false "recording".
        var deferredActive = !isProximity && modeActive && pipelineRunning && !wedged;
        // FIX (false-GREEN): gate the bare isRecording term on !wedged too. The
        // daemon now reports wedged=true when a muxer is open but no video
        // sample has reached disk for >8s (SD unmount / ENOSPC / write
        // failures); without this the card would show "Recording" over a dead
        // writer. Mirrors StatusOverlayService.updateUI's recordingLive.
        var recordingLive = (isRecording && !wedged) || deferredActive;

        // shouldBeRecording mirrors StatusOverlayService.shouldRecordingBeActive:
        // CONTINUOUS records whenever ACC is on; DRIVE_MODE records in a driving
        // gear (D/R/S/M/N, not P). Used only to paint the RED fault state when a
        // continuous-style mode should be writing but isn't (wedged / stuck).
        var driving = (gear === 'D' || gear === 'R' || gear === 'S' || gear === 'M' || gear === 'N');
        var shouldBeRecording = accOn && (mode === 'CONTINUOUS'
            || (mode === 'DRIVE_MODE' && driving));

        var cls, badgeKey, stateKey;
        if (off) {
            cls = 'inactive'; badgeKey = 'recording.status_idle'; stateKey = 'common.idle';
        } else if (recordingLive) {
            cls = 'active'; badgeKey = 'recording.status_recording'; stateKey = 'recording.state_recording';
        } else if (isProximity) {
            // Armed, waiting for a radar trigger — not a fault.
            cls = 'inactive'; badgeKey = 'recording.status_armed'; stateKey = 'recording.state_armed';
        } else if (shouldBeRecording) {
            // Continuous/drive mode SHOULD be writing but isn't (wedged encoder
            // or stuck activation). Paint the red fault state — mirrors the
            // native pill's status_danger branch so the secondary web card
            // doesn't silently downgrade a real fault to a benign IDLE.
            cls = 'errored'; badgeKey = 'recording.status_problem'; stateKey = 'recording.state_problem';
        } else {
            // Configured continuous/drive mode but standby (parked / ACC off).
            cls = 'inactive'; badgeKey = 'recording.status_idle'; stateKey = 'common.idle';
        }

        // Summary tile: shared off look when no mode is set or on a fault (the fault also turns its chip red).
        var hero = document.getElementById('recHero');
        if (hero) hero.classList.toggle('is-off', off || cls === 'errored');

        if (badge) {
            badge.classList.remove('active', 'inactive', 'errored');
            badge.classList.add(cls);
            // Re-point the data-i18n binding to the live key (rather than
            // stripping it) so a runtime language switch — which re-translates
            // [data-i18n] nodes via hydrate(document) — repaints this badge
            // instantly in the new language instead of freezing it until the
            // next status poll. All keys above are static catalog keys.
            badge.setAttribute('data-i18n', badgeKey);
            badge.textContent = BYD.i18n.t(badgeKey);
        }
        if (state) {
            state.setAttribute('data-i18n', stateKey);
            state.textContent = BYD.i18n.t(stateKey);
        }
    }
};

// Alias for backward compatibility
window.RecSettings = BYD.recording;
