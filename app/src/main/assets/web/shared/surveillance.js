/**
 * BYD Champ - Surveillance Settings Module
 * SOTA: Uses unified config for cross-UID access (app UI + web UI sync)
 * SOTA: Storage limits with auto-cleanup (100MB - 100GB internal/SD card)
 * SOTA: Storage type selection (internal vs SD card)
 */

window.BYD = window.BYD || {};

BYD.surveillance = {
    config: {
        enabled: false,
        distance: 3,
        sensitivity: 3,
        minObjectSize: 0.08,
        flashImmunity: 2,
        detectPerson: true,
        detectCar: true,
        detectBike: true,
        detectAnimal: false,
        preRecordSeconds: 5,
        postRecordSeconds: 10,
        // ACC-off SURVEILLANCE quality tier — INDEPENDENT of the ACC-on
        // dashcam recordingQuality. This page's picker drives the parked
        // sentry bitrate only. Persisted to recording.surveillanceQuality;
        // the server live-applies it to a running parked recording with no
        // encoder reinit. Seeded from the ACC-on tier server-side so it starts
        // matching the old shared value until the user changes it.
        surveillanceQuality: 'STANDARD',
        // Surveillance camera fps — independent of the ACC-on targetFps.
        // Persisted to camera.surveillanceTargetFps.
        surveillanceCameraFps: 15,
        // Codec is SHARED across both axes (a device-compat choice, not a
        // per-mode quality knob), so it stays on the recordingCodec key.
        recordingCodec: 'H264',
        // Server-supplied for UI (filled by load):
        recordingQualityOptions: {},
        activeRecordingEstimate: null,
        surveillanceLimitMb: 500,
        surveillanceStorageType: 'INTERNAL',
        // V2 Motion Detection
        environmentPreset: 'outdoor',
        sensitivityLevel: 3,
        detectionZone: 'normal',
        loiteringTime: 3,
        approachTrigger: 2,
        shadowFilter: 2,
        cameraFront: true,
        cameraRight: true,
        cameraLeft: true,
        cameraRear: true,
        motionHeatmap: false,
        filterDebugLog: false,
        discardEmptyBrightMotionEvents: false,
        discardEmptyMotionAtNight: false,
        motionSalienceEnabled: false,
        // Per-quadrant sensitivity / zone overrides. Keys: Q0=front, Q1=right,
        // Q2=rear, Q3=left. Absent key = inherit global. The "Side-cam Boost"
        // UI writes to Q1 + Q3.
        quadrantOverrides: {},
        // Telegram-specific: send a "Recording in progress…" text ping at
        // recording start. OFF by default so users with both PWA + Telegram
        // see one notification per event (PWA has tag-replace; Telegram does
        // not). Telegram-only users may turn this on for low-latency awareness.
        telegramSendStartPing: false,
        // Per-tier Telegram filter — mirrors the push tier toggles. Defaults
        // match: NOTICE off (background noise), ALERT + CRITICAL on.
        telegramNotices: false,
        telegramAlerts: true,
        telegramCritical: true,
        // ACC-OFF mode: 'smart' (motion + AI events, default) | 'continuous'
        // (plain rolling 4-cam recording, no motion, no AI). Backward-compat:
        // installs that pre-date this key see the smart default.
        accOffMode: 'smart',
        // Arm mode: 'lock' (arm on door-lock, disarm on unlock, 60s fallback when
        // lock state is unreadable, default) | 'power' (arm immediately on
        // power-off, disarm on power-on). Both honor safe locations + schedules.
        armMode: 'lock',
        // Operating mode: 'onAndOff' (default, full behaviour — keep-awake + post-OFF
        // surveillance run after the car is off) | 'onOnly' (let the head unit sleep
        // once parked; only-while-driving features run). Backward-compat: installs that
        // pre-date this key see the onAndOff default. Read by every post-OFF daemon gate.
        operatingMode: 'onAndOff',
        // Keep ONLY the USB/data rail powered after ACC OFF (e.g. to charge a phone
        // while parked). Default true (unchanged out-of-box behaviour); turning it
        // off lets just that rail sleep on the next ACC-OFF cycle to save the 12V
        // battery. Does NOT affect the cameras — parked surveillance is unaffected.
        keepUsbPowerOnAccOff: true,
        // Parked cellular keep-alive. Default false = opt-in; only needed on models
        // whose data module sleeps after ACC OFF. Hydrated from /api/surveillance/config
        // and persisted immediately on toggle (no Apply).
        mobileDataKeepAlive: false,
        // Low-power-while-parked master toggle (mirrors camera.surveillanceIdleThrottle
        // + oemDashcam.idleThrottleWhenParked, which are driven together). Default
        // false = today's behaviour (full idle frame rate). Hydrated from
        // /api/settings/unified on load; persisted immediately on toggle.
        lowPowerWhileParked: false,
        // OEM dashcam surveillance mode mirror — 'off' | 'continuous' | 'smart'.
        // Hydrated by loadOemDashcam() from /api/oem-dashcam/config and
        // pushed back to the same endpoint on Apply (oem-tab branch in
        // applySettings()). Lives on the surveillance config object so the
        // dirty diff has a stable key to compare.
        oemSurveillanceMode: 'off',
        // Lens-dewarp strength (Fitzgibbon division model). 0..100, 0=off.
        // Shared with the recording page through UnifiedConfigManager
        // recording.rectifyStrength. Default 0 here so the dirty-diff
        // baseline is a real number even before loadConfig() fires.
        rectifyStrength: 0,
        // Clip segment length in minutes (2/5/10). SAME shared key as the
        // recording page (recording.segmentDurationMinutes) — one control
        // governs both axes, so changing it here changes it there too.
        segmentDurationMinutes: 2,
        // HV-battery SoC cutoff (%) for parked surveillance. Saved to
        // power.lowSocCutoffPercent (NOT the surveillance section) — that's the
        // key SocCutoffMonitor reads to self-shut-down at/below this level.
        // 0 = Off. Immediate-save on drag end (like screenDeterrentDuration),
        // so it stays OUT of the Apply-button tab field map. Default 10 here so
        // the dirty-diff baseline is a real number before loadConfig() lands.
        lowSocCutoffPercent: 10
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
        // Dynamic per-volume ceilings (live StatFs from server)
        maxLimitMb: 100000,
        maxLimitMbSdCard: 100000,
        maxLimitMbUsb: 100000,
        // Combined-limit advisory (one entry per targeted volume). See
        // shared/storage-budget.js — rendered, never enforced.
        storageBudget: []
    },
    cdrInfo: null,
    cdrConfig: {
        enabled: false,
        reservedSpaceMb: 2000,
        protectedHours: 24,
        minFilesKeep: 10
    },
    savedConfig: null,
    hasUnsavedChanges: false,
    lastConfigTimestamp: 0,  // Track config file timestamp for sync

    distanceMap: {
        1: { size: 0.25 },
        2: { size: 0.18 },
        3: { size: 0.12 },
        4: { size: 0.08 },
        5: { size: 0.05 }
    },
    distanceLabel(v) { return BYD.i18n.t('surveillance.distance.' + v + '_label'); },
    distanceHint(v) { return BYD.i18n.t('surveillance.distance.' + v + '_hint'); },

    // Sensitivity controls motion detection thresholds (requiredBlocks + densityThreshold)
    // Block size is LOCKED at 32 - only density and required count vary
    sensitivityMap: {
        1: { required: 4, density: 48 },
        2: { required: 3, density: 40 },
        3: { required: 2, density: 32 },
        4: { required: 2, density: 16 },
        5: { required: 1, density: 12 }
    },
    sensitivityLabel(v) { return BYD.i18n.t('surveillance.sens.' + v + '_label'); },
    sensitivityHint(v) { return BYD.i18n.t('surveillance.sens.' + v + '_hint'); },

    flashImmunityMap: { 0: 'SENSITIVE', 1: 'NORMAL', 2: 'STRICT', 3: 'MAX' },

    // V2 sensitivity level labels — looked up via BYD.i18n
    v2SensLabel(v) { return BYD.i18n.t('surveillance.v2_sens_label.' + v); },
    v2SensHint(v) { return BYD.i18n.t('surveillance.v2_sens_hint.' + v); },

    // V2 environment presets: { sensitivityLevel, detectionZone, loiteringTime, shadowFilter }
    v2Presets: {
        outdoor:  { sensitivityLevel: 3, detectionZone: 'normal', loiteringTime: 3, shadowFilter: 2 },
        garage:   { sensitivityLevel: 4, detectionZone: 'close',  loiteringTime: 2, shadowFilter: 1 },
        street:   { sensitivityLevel: 3, detectionZone: 'normal', loiteringTime: 5, shadowFilter: 3 }
    },

    // Live-armed status badge — separate from this.config.enabled (the
    // persisted feature toggle set in updateUI()). "Enabled as a setting"
    // and "actually armed right now" are different: the toggle can be on
    // while the daemon is still booting, ACC is on, or the doors are
    // unlocked, and the badge used to just echo the toggle, so it read "ON"
    // from the moment the app launched even though nothing was protecting
    // the car yet. Reads /api/surveillance/status directly instead.
    async refreshLiveStatusBadge() {
        const badge = document.getElementById('survStatusBadge');
        if (!badge) return;
        try {
            const res = await BYD.api.getSurveillanceStatus();
            const armed = !!(res && res.success && res.status
                && res.status.enabled && res.status.initialized !== false);
            badge.textContent = armed ? BYD.i18n.t('surveillance.badge_on') : BYD.i18n.t('surveillance.badge_off');
            badge.className = 'status-badge ' + (armed ? 'active' : 'inactive');
        } catch (e) {
            // Leave the last-known badge state on a transient fetch failure
            // rather than flipping it to an unverified guess.
        }
    },

    async init() {
        // loadConfig() must land FIRST and alone — every loader below reads or
        // writes this.config, and updateUI() renders from it.
        //
        // The rest are idempotent reads of independent endpoints, so they run
        // in PARALLEL. They used to be seven sequential `await`s, which made
        // first paint wait on 11 serial round-trips (loadConfig is itself a
        // 3-deep chain and loadOemDashcam a 2-deep one). On a head unit where
        // the storage endpoints take a few hundred ms each against a large
        // library, that serialization — not any single slow call — is what made
        // this page take seconds to become usable, with Apply disabled, the OEM
        // picker dimmed and the Detection tab inert the whole time.
        // Same fix recording.js already carries (see its init()): ~11 RTTs → ~3.
        await this.loadConfig();
        await Promise.all([
            this.loadStorageStats(),
            this.loadCameraFps(),
            this.loadGeocoding(),
            // Sentry's own composition layout (independent of the dashcam layout).
            this.loadSurveillanceLayout(),
            // Dedicated OEM Dashcam tab — load the mode picker, telemetry
            // toggle, status badge, and native DVR control on init so the
            // user can land directly on the OEM tab and find populated state.
            this.loadOemDashcam(),
            this.loadOemNativeDvr(),
            // ACC-off surveillance telemetry overlay (own master + field selection).
            this.loadSurvTelemetryOverlay(),
        ]);
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
        this.startClock();

        // Live-armed badge, separate from the config-driven toggle above.
        // Refreshed on init and every 10s so it doesn't go stale while the
        // page sits open across a daemon restart/arm/disarm.
        this.refreshLiveStatusBadge();
        setInterval(() => this.refreshLiveStatusBadge(), 10000);

        // Telegram pairing state — drives the tier filter availability UI.
        // Re-checked when reloadConfig() fires (visibility change, periodic
        // refresh) so a freshly-paired bot un-greys the toggles live.
        this.refreshTelegramAvailability();
        
        // Load BYD Cloud status
        if (window.BydCloud) {
            BydCloud.loadStatus();
        }
        
        // Show CDR cleanup card if SD card is selected on load
        this.updateCdrCleanupVisibility();
        
        // Auto-start heatmap if enabled in config and video display area exists
        if (this.config.motionHeatmap) {
            this.startHeatmap();
        }
        
        // Reload config when page becomes visible (user switches back to tab)
        document.addEventListener('visibilitychange', () => {
            // reloadConfig self-gates on hasUnsavedChanges (and falls back to the
            // banner-only refresh when dirty), so no second gate here — returning to a
            // dirty tab should still pick up a peer page's limit change.
            if (document.visibilityState === 'visible') {
                this.reloadConfig();
            }
            // Stop heatmap polling when page is hidden
            if (document.visibilityState === 'hidden' && this._heatmapInterval) {
                this.stopHeatmap();
                this._heatmapWasRunning = true;
            }
            // Restart heatmap when page becomes visible again
            if (document.visibilityState === 'visible' && this._heatmapWasRunning) {
                this._heatmapWasRunning = false;
                if (this.config.motionHeatmap) {
                    this.startHeatmap();
                }
            }
        });

        // Re-evaluate Apply enabled-state + unsaved markers whenever the
        // user swaps bottom tabs — markChanged() reads the active tab id
        // each call, so the visible button reflects only the visible tab.
        // Also re-hydrate the OEM card on tab activation so the picker
        // never lingers in its hard-coded {aria-busy="true"} dim state if
        // init's first loadOemDashcam was superseded mid-flight (token
        // race) or hit a transient daemon-not-ready window. Mirrors what
        // visibilitychange already does — same idempotent reload.
        var self = this;
        document.addEventListener('ot-tabs:active-changed', function (ev) {
            self.markChanged();
            try {
                if (ev && ev.detail && ev.detail.id === 'oem') {
                    self.loadOemDashcam();
                    self.loadOemNativeDvr();
                }
            } catch (_) {}
        });
    },
    
    async reloadConfig() {
        // Don't reload if user has unsaved changes
        if (this.hasUnsavedChanges) {
            // ...but keep the overcommit advisory current: it depends on the OTHER
            // categories' limits, so a dirty page would otherwise show a stale verdict
            // indefinitely. Writes no control, so it's safe with edits in flight.
            this.refreshBudgetOnly();
            return;
        }

        try {
            const resp = await fetch('/api/surveillance/config');
            const data = await resp.json();
            if (data.success && data.config) {
                // Check if config actually changed (via timestamp)
                const newTimestamp = data.config.lastModified || 0;
                if (newTimestamp > this.lastConfigTimestamp) {
                    // Load storage settings first (its own dirty-guard protects
                    // the limit/type fields). Done BEFORE any mutation of
                    // this.config so the mid-flight guard below can bail without
                    // leaving config half-replaced.
                    await this.loadStorageSettings();

                    // Re-check hasUnsavedChanges: reloadConfig() is async with
                    // awaits above. The entry guard only covers entry — if the
                    // user started editing (markChanged) while we were suspended
                    // at an await, we must NOT wholesale-replace this.config with
                    // server data, advance the timestamp, re-baseline savedConfig,
                    // or call updateUI() — doing so would strand config/UI/
                    // savedConfig out of sync and silently discard the pending
                    // edit. Return WITHOUT advancing lastConfigTimestamp so the
                    // next reload re-attempts once the edit is saved. Mirrors the
                    // mid-flight guard in recording.js reloadConfig.
                    if (this.hasUnsavedChanges) return;

                    // Apply server config atomically (no await between here and
                    // updateUI, so the three stay consistent).
                    this.config = { ...this.config, ...data.config };
                    // Use server-provided distance/sensitivity if available
                    if (!data.config.distance) {
                        this.config.distance = this.sizeToDistance(this.config.minObjectSize || 0.08);
                    }
                    if (!data.config.sensitivity) {
                        this.config.sensitivity = 3;  // Default
                    }
                    this.lastConfigTimestamp = newTimestamp;

                    this.savedConfig = JSON.parse(JSON.stringify(this.config));
                    this.updateUI();
                }
            }
        } catch (e) {
            console.warn('Failed to reload config:', e);
        }
        // Re-probe Telegram pairing on every reload — newly-paired bots
        // should un-grey the tier toggles without a page refresh.
        this.refreshTelegramAvailability();
        // Re-hydrate the OEM Dashcam tab. The user may have picked a
        // camera id via the Android camera-mapping dialog while this
        // page was hidden; reloadConfig fires on visibilitychange so
        // the cards re-evaluate without a page reload.
        this.loadOemDashcam();
        this.loadOemNativeDvr();
        // Re-read sentry's layout so a change made elsewhere (or on another
        // device) reflects without a full page reload.
        this.loadSurveillanceLayout();
        // Lens-dewarp slider lives in unified-config recording.* (shared
        // with the dashcam page). /api/surveillance/config doesn't surface
        // it, so re-read it here so a change made on the recording page
        // reflects without a full reload.
        try {
            const uResp = await fetch('/api/settings/unified');
            const uData = await uResp.json();
            // Guard on !hasUnsavedChanges for the same reason as the clip-duration
            // block below: this runs after awaits, so a mid-flight edit to the
            // rectify slider would otherwise be overwritten and savedConfig
            // re-baselined, erasing the dirty diff and graying out Apply.
            if (!this.hasUnsavedChanges &&
                uData.success && uData.config && uData.config.recording &&
                typeof uData.config.recording.rectifyStrength === 'number') {
                var rsRefresh = uData.config.recording.rectifyStrength;
                if (rsRefresh < 0) rsRefresh = 0;
                if (rsRefresh > 100) rsRefresh = 100;
                if (rsRefresh !== this.config.rectifyStrength) {
                    this.config.rectifyStrength = rsRefresh;
                    if (this.savedConfig) this.savedConfig.rectifyStrength = rsRefresh;
                    var slider = document.getElementById('survRectifySlider');
                    var label  = document.getElementById('survRectifyValue');
                    if (slider) slider.value = rsRefresh;
                    if (label) {
                        var offTxt = (BYD.i18n && typeof BYD.i18n.t === 'function')
                            ? BYD.i18n.t('recording.rectify_off') : null;
                        if (!offTxt || offTxt === 'recording.rectify_off') offTxt = 'Off';
                        label.textContent = (rsRefresh === 0) ? offTxt : (rsRefresh + '%');
                    }
                }
            }
            // Shared clip duration — re-sync if the recording page changed it.
            // SIBLING of the rectify block above (NOT nested): segmentDuration
            // must re-sync even when rectifyStrength is absent from the unified
            // config (e.g. a config that only has the segmentDuration default).
            // Mirrors recording.js reloadConfig, where these are sibling ifs.
            // Guard on !hasUnsavedChanges: this runs after the awaits above, so
            // the user may have clicked a clip-duration button mid-flight —
            // without the re-check the resume would overwrite their pending pick
            // AND re-baseline savedConfig, erasing the dirty diff / graying out
            // Apply.
            if (!this.hasUnsavedChanges &&
                uData.success && uData.config && uData.config.recording &&
                typeof uData.config.recording.segmentDurationMinutes === 'number') {
                var sdRefresh = uData.config.recording.segmentDurationMinutes;
                if (sdRefresh !== 2 && sdRefresh !== 5 && sdRefresh !== 10) sdRefresh = 2;
                if (sdRefresh !== this.config.segmentDurationMinutes) {
                    this.config.segmentDurationMinutes = sdRefresh;
                    if (this.savedConfig) this.savedConfig.segmentDurationMinutes = sdRefresh;
                    document.querySelectorAll('#survClipDurationBtns .btn-toggle').forEach(function (btn) {
                        btn.classList.toggle('active', btn.dataset.value === String(sdRefresh));
                    });
                }
            }
            // ACC-off surveillance tier — SIBLING re-sync so a change made on
            // another device / tab reflects on visibility refresh (the surv page
            // now owns recording.surveillanceQuality; /api/surveillance/config
            // does NOT surface it, so re-read it from unified config here — same
            // pattern as the rectify + clip-duration blocks above). Falls back to
            // the ACC-on recordingQuality when the surveillance key is absent, so
            // a pre-split config shows the seeded value. Guarded on
            // !hasUnsavedChanges to protect a mid-flight tier pick.
            if (!this.hasUnsavedChanges &&
                uData.success && uData.config && uData.config.recording) {
                var sqRefresh = uData.config.recording.surveillanceQuality
                    || uData.config.recording.recordingQuality;
                if (sqRefresh && sqRefresh !== this.config.surveillanceQuality) {
                    this.config.surveillanceQuality = sqRefresh;
                    if (this.savedConfig) this.savedConfig.surveillanceQuality = sqRefresh;
                    document.querySelectorAll('#survQualityBtns .btn-toggle').forEach(function (btn) {
                        btn.classList.toggle('active', btn.dataset.value === sqRefresh);
                    });
                    this.renderActiveEstimate();
                }
            }
            // ACC-off surveillance fps — SIBLING re-sync (camera.surveillanceTargetFps),
            // falling back to camera.targetFps when unset. Same guard/pattern.
            if (!this.hasUnsavedChanges &&
                uData.success && uData.config && uData.config.camera) {
                var sfRefresh = (typeof uData.config.camera.surveillanceTargetFps === 'number')
                    ? uData.config.camera.surveillanceTargetFps
                    : uData.config.camera.targetFps;
                if (typeof sfRefresh === 'number' && sfRefresh !== this.config.surveillanceCameraFps) {
                    this.config.surveillanceCameraFps = sfRefresh;
                    if (this.savedConfig) this.savedConfig.surveillanceCameraFps = sfRefresh;
                    document.querySelectorAll('#survFpsBtns .btn-toggle').forEach(function (btn) {
                        btn.classList.toggle('active', btn.dataset.value === String(sfRefresh));
                    });
                }
            }
        } catch (_) {}
    },

    async loadStorageSettings() {
        try {
            const resp = await fetch('/api/settings/storage');
            const data = await resp.json();
            if (data.success) {
                // Dirty-guard the user-editable picker fields — see the matching
                // guard in recording.js loadStorageSettings. A reload landing just
                // after Apply (hasUnsavedChanges already cleared) would otherwise
                // overwrite the just-saved slider value with a stale server read
                // and snap the slider back. Display-only storageInfo/ceilings below
                // always refresh.
                if (!this.hasUnsavedChanges) {
                    this.config.surveillanceLimitMb = data.surveillanceLimitMb || 500;
                    this.config.surveillanceStorageType = data.surveillanceStorageType || 'INTERNAL';
                }
                // Active = where surveillance ACTUALLY writes right now. Differs
                // from the configured type when the chosen external volume isn't
                // mounted (the SD card is bridged behind the USB power rail, so
                // cutting USB power unmounts it) or is full — both fall back to
                // internal. Captured so updateStorageTypeUI can warn the user
                // their clips are NOT landing on the SD/USB they picked.
                this.storageInfo.surveillanceStorageTypeActive =
                    data.surveillanceStorageTypeActive || this.config.surveillanceStorageType;

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

                // Dynamic ceilings
                this.storageInfo.maxLimitMb       = data.maxLimitMb       || 100000;
                this.storageInfo.maxLimitMbSdCard = data.maxLimitMbSdCard || 100000;
                this.storageInfo.maxLimitMbUsb    = data.maxLimitMbUsb    || 100000;
                this.storageInfo.surveillancePath = data.surveillancePath || '';
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
            // Recordings index is down — zeroed counters are not
            // authoritative, so show "--" instead of claiming zero events.
            // Flag it so the 10s poller backs off rather than issuing a
            // permanent 6-req/min 503 stream at the daemon.
            if (data.indexUnavailable) {
                this._indexDown = true;
                var sdIds = ['survStorageUsed', 'survStorageLimit'];
                for (var si = 0; si < sdIds.length; si++) {
                    var sel = document.getElementById(sdIds[si]);
                    if (sel) sel.textContent = '--';
                }
                var sFill = document.getElementById('survStorageFill');
                if (sFill) sFill.style.width = '0%';
                // Same as recording.js: today's event count is only written in
                // the success branch and would otherwise keep its "0 →"
                // default, asserting no events today.
                var sToday = document.getElementById('eventsToday');
                if (sToday) sToday.textContent = '--';
                return;
            }
            this._indexDown = false;
            if (data.success) {
                const usedEl = document.getElementById('survStorageUsed');
                const limitEl = document.getElementById('survStorageLimit');
                const fillEl = document.getElementById('survStorageFill');

                // Prefer the structured byType block; fall back to legacy
                // flat fields so a stale daemon still renders correctly.
                const counts = data.byType ? {
                    sentry: data.byType.sentry || {}
                } : {
                    sentry: {
                        count: data.sentryCount,
                        bytes: data.sentrySize,
                        bytesFormatted: data.sentrySizeFormatted,
                        todayCount: data.sentryTodayCount
                    }
                };

                const sentryBytesFormatted = counts.sentry.bytesFormatted || data.sentrySizeFormatted;
                if (usedEl) usedEl.textContent = BYD.i18n.t('surveillance.size_used', {size: sentryBytesFormatted});

                const limitMb = this.config.surveillanceLimitMb || 500;
                if (limitEl) limitEl.textContent = BYD.i18n.t('surveillance.size_limit_mb', {mb: limitMb});

                // Calculate percentage
                const usedBytes = counts.sentry.bytes || 0;
                const limitBytes = limitMb * 1024 * 1024;
                const percent = Math.min(100, Math.round(usedBytes * 100 / limitBytes));
                if (fillEl) fillEl.style.width = percent + '%';

                // Update Events Today count
                const eventsTodayEl = document.getElementById('eventsToday');
                if (eventsTodayEl) {
                    const todayCount = counts.sentry.todayCount || 0;
                    eventsTodayEl.textContent = todayCount + ' →';
                }

                // Daemon's recording index is still building — surface a
                // progress line on the storage card so the user doesn't see
                // a stale 0 MB while H2 catches up. Self-refresh until
                // indexState disappears from the payload.
                // Single in-flight timer + exponential backoff (2s → 4s →
                // 8s → 10s cap). Page revisits / segment switches stack
                // independent polling chains otherwise — each scheduled
                // setTimeout fires its own loadStorageStats which schedules
                // another one, multiplying daemon HTTP load.
                if (this._warmingPollTimer) {
                    clearTimeout(this._warmingPollTimer);
                    this._warmingPollTimer = null;
                }
                if (data.indexState && data.indexState.warming) {
                    const done = data.indexState.done || 0;
                    const total = data.indexState.total || 0;
                    const pct = total > 0 ? Math.round(done * 100 / total) : 0;
                    const tmpl = BYD.i18n.t('surveillance.storage_indexing', {done: done, total: total, pct: pct});
                    const text = (tmpl && tmpl !== 'surveillance.storage_indexing')
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
    
    async loadCameraFps() {
        try {
            const resp = await fetch('/api/settings/quality');
            const data = await resp.json();
            if (data.success) {
                // Surveillance-specific fps + tier (fall back to the shared
                // ACC-on value the server sends when the surveillance key is
                // unset, so a fresh install shows the seeded default).
                this.config.surveillanceCameraFps =
                    data.surveillanceCameraFps || data.cameraFps || 15;
                this.config.cameraFpsActual = data.cameraFpsActual || null;
                this.config.cameraFpsClampNote = data.cameraFpsClampNote || null;
                // Pull tier + render data so the picker can show live
                // size estimates without waiting for the next reload.
                if (data.surveillanceQuality) {
                    this.config.surveillanceQuality = data.surveillanceQuality;
                } else if (data.recordingQuality) {
                    this.config.surveillanceQuality = data.recordingQuality;
                }
                this.config.recordingQualityOptions = data.recordingQualityOptions || {};
                this.config.activeRecordingEstimate = data.activeRecordingEstimate || null;
            }
        } catch (e) {
            console.warn('Failed to load camera FPS:', e);
            this.config.surveillanceCameraFps = 15;
        }
    },

    setFps(fps) {
        this.config.surveillanceCameraFps = parseInt(fps, 10);
        document.querySelectorAll('#survFpsBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(fps)));
        // FPS shifts the per-tier qualityEquivalent labels — re-render so
        // the user sees the change before pressing Apply. Persistence is
        // routed through applySettings() (recording-tab branch below).
        this.renderActiveEstimate();
        this.markChanged();
    },

    /** Pull a fresh /api/settings/quality and update the local tier table +
     *  active-estimate display. Called after a save that affects per-tier
     *  metadata (codec or fps). */
    async refetchQualityOptions() {
        try {
            const r = await fetch('/api/settings/quality');
            if (!r.ok) return;
            const data = await r.json();
            if (data && data.recordingQualityOptions) {
                this.config.recordingQualityOptions = data.recordingQualityOptions;
                this.renderActiveEstimate();
            }
        } catch (e) { /* best-effort */ }
    },
    
    effectiveMaxLimitMb() {
        switch (this.config.surveillanceStorageType) {
            case 'SD_CARD': return this.storageInfo.maxLimitMbSdCard;
            case 'USB':     return this.storageInfo.maxLimitMbUsb;
            default:        return this.storageInfo.maxLimitMb;
        }
    },

    updateStorageLimitUI() {
        const slider = document.getElementById('survLimitSlider');
        const value = document.getElementById('survLimitValue');

        const maxLimit = this.effectiveMaxLimitMb();

        if (slider) {
            slider.max = maxLimit;
            slider.value = Math.min(this.config.surveillanceLimitMb, maxLimit);
        }
        if (value) {
            const mb = this.config.surveillanceLimitMb;
            value.textContent = mb >= 1000 ? (mb / 1000) + ' GB' : mb + ' MB';
        }

        const minLabel = document.getElementById('survLimitMin');
        const maxLabel = document.getElementById('survLimitMax');
        if (minLabel) minLabel.textContent = BYD.i18n.t('surveillance.limit_min_default');
        if (maxLabel) maxLabel.textContent = maxLimit >= 1000 ? (maxLimit / 1000) + ' GB' : maxLimit + ' MB';

        this.updateBudgetBanner();
    },

    /**
     * Render the combined-limit advisory. Passes the CURRENT (possibly unsaved)
     * slider value and storage-type pick so the warning tracks the controls live
     * rather than only after Apply.
     */
    updateBudgetBanner() {
        if (!BYD.storageBudget) return;
        BYD.storageBudget.render('survBudgetBanner', this.storageInfo.storageBudget,
            'surveillance', this.config.surveillanceLimitMb, this.config.surveillanceStorageType);
    },

    /**
     * Re-read ONLY the storage budget and re-render the advisory — for the dirty-page
     * case, where the full reloadConfig is (correctly) suppressed to protect unsaved
     * edits. Writes no control, so it is safe with edits in flight.
     */
    async refreshBudgetOnly() {
        if (!BYD.storageBudget) return;
        try {
            const resp = await fetch('/api/settings/storage');
            const data = await resp.json();
            if (!data || !data.success || !data.storageBudget) return;
            this.storageInfo.storageBudget = data.storageBudget;
            this.updateBudgetBanner();
        } catch (e) { /* advisory only — keep the last render */ }
    },
    
    updateStorageTypeUI() {
        document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.surveillanceStorageType));

        // SD/USB both ride the USB power rail; when "Keep USB powered" is OFF they're
        // unpowered while parked, so disable both regardless of physical presence.
        const usbOff = (this.config.keepUsbPowerOnAccOff === false);
        const usbOffTitle = BYD.i18n && BYD.i18n.t
            ? (BYD.i18n.t('surveillance.storage_blocked_usb_off') || 'Turn "Keep USB powered" on to use SD/USB while parked')
            : 'Turn "Keep USB powered" on to use SD/USB while parked';

        const sdCardBtn = document.getElementById('btnSurvSdCard');
        if (sdCardBtn) {
            sdCardBtn.disabled = !this.storageInfo.sdCardAvailable || usbOff;
            sdCardBtn.title = usbOff ? usbOffTitle
                : (this.storageInfo.sdCardAvailable ? '' : BYD.i18n.t('recording.sd_card_unavailable'));
        }
        const usbBtn = document.getElementById('btnSurvUsb');
        if (usbBtn) {
            usbBtn.disabled = !this.storageInfo.usbAvailable || usbOff;
            usbBtn.title = usbOff ? usbOffTitle
                : (this.storageInfo.usbAvailable ? '' : BYD.i18n.t('recording.usb_unavailable'));
        }

        // SD card status block
        const sdStatusEl = document.getElementById('survSdCardStatus');
        if (sdStatusEl) {
            sdStatusEl.style.display = 'block';
            const dotEl = document.getElementById('survSdStatusDot');
            const textEl = document.getElementById('survSdStatusText');
            const spaceEl = document.getElementById('survSdSpaceInfo');
            if (this.storageInfo.sdCardAvailable) {
                if (dotEl) dotEl.className = 'sd-status-dot online';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_available');
                if (spaceEl) {
                    spaceEl.style.display = 'block';
                    const sdFreeEl = document.getElementById('survSdFree');
                    const sdTotalEl = document.getElementById('survSdTotal');
                    if (sdFreeEl) sdFreeEl.textContent = BYD.i18n.t('recording.size_free', {size: this.formatSize(this.storageInfo.sdCardFreeSpace)});
                    if (sdTotalEl) sdTotalEl.textContent = BYD.i18n.t('recording.size_total', {size: this.formatSize(this.storageInfo.sdCardTotalSpace)});
                }
            } else {
                if (dotEl) dotEl.className = 'sd-status-dot offline';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_not_detected');
                if (spaceEl) spaceEl.style.display = 'none';
            }
        }

        // USB status block
        const usbStatusEl = document.getElementById('survUsbStatus');
        if (usbStatusEl) {
            usbStatusEl.style.display = 'block';
            const dotEl = document.getElementById('survUsbStatusDot');
            const textEl = document.getElementById('survUsbStatusText');
            const spaceEl = document.getElementById('survUsbSpaceInfo');
            if (this.storageInfo.usbAvailable) {
                if (dotEl) dotEl.className = 'sd-status-dot online';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.usb_available');
                if (spaceEl) {
                    spaceEl.style.display = 'block';
                    const usbFreeEl = document.getElementById('survUsbFree');
                    const usbTotalEl = document.getElementById('survUsbTotal');
                    if (usbFreeEl) usbFreeEl.textContent = BYD.i18n.t('recording.size_free', {size: this.formatSize(this.storageInfo.usbFreeSpace)});
                    if (usbTotalEl) usbTotalEl.textContent = BYD.i18n.t('recording.size_total', {size: this.formatSize(this.storageInfo.usbTotalSpace)});
                }
            } else {
                if (dotEl) dotEl.className = 'sd-status-dot offline';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.usb_not_detected');
                if (spaceEl) spaceEl.style.display = 'none';
            }
        }

        const pathEl = document.getElementById('survStoragePath');
        if (pathEl && this.storageInfo.surveillancePath) {
            const shortPath = this.storageInfo.surveillancePath.replace('/storage/emulated/0/', '');
            pathEl.textContent = BYD.i18n.t('surveillance.events_saved_to', {path: shortPath});
        }

        // Fallback warning. When the configured external volume isn't the one
        // we're actually writing to (unmounted SD/USB — typically because USB
        // power is off and the SD rides that rail — or a full external volume),
        // surface it so the user knows their clips are silently landing on
        // internal instead of the SD/USB they selected. Per-clip badges in the
        // recordings library reinforce this at the file level.
        const fallbackEl = document.getElementById('survStorageFallbackWarning');
        if (fallbackEl) {
            const configured = this.config.surveillanceStorageType || 'INTERNAL';
            const active = this.storageInfo.surveillanceStorageTypeActive || configured;
            if (configured !== 'INTERNAL' && active === 'INTERNAL') {
                const volName = configured === 'SD_CARD'
                    ? (BYD.i18n.t('events.storage_sd_card') || 'SD card')
                    : (BYD.i18n.t('events.storage_usb') || 'USB');
                fallbackEl.textContent = BYD.i18n.t('surveillance.storage_fallback_warning', {volume: volName})
                    || (volName + ' unavailable — saving to internal storage');
                fallbackEl.style.display = 'block';
            } else {
                fallbackEl.style.display = 'none';
            }
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
        // SD/USB both ride the USB power rail, which is unpowered while parked when
        // "Keep USB powered" is OFF. Block the selection and tell the user to turn
        // USB power back on first, so we never persist a storage target that silently
        // can't be written to during a parked session.
        if ((type === 'SD_CARD' || type === 'USB') && this.config.keepUsbPowerOnAccOff === false) {
            const t = (k, fb) => (BYD.i18n && BYD.i18n.t ? (BYD.i18n.t(k) || fb) : fb);
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(t('surveillance.storage_blocked_usb_off',
                    'Turn "Keep USB powered" on to use SD/USB while parked'), 'warning');
            }
            return;
        }

        this.config.surveillanceStorageType = type;
        document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === type));

        // Re-clamp limit to the new volume's effective max so we don't
        // ship a value larger than the destination volume can hold.
        const newMax = this.effectiveMaxLimitMb();
        if (this.config.surveillanceLimitMb > newMax) {
            this.config.surveillanceLimitMb = newMax;
        }
        this.updateStorageLimitUI();
        this.updateCdrCleanupVisibility();
        this.markChanged();
        var _su = document.getElementById('storageUnsaved'); if (_su) _su.classList.add('show');
    },
    
    // ==================== CDR Cleanup ====================
    
    async loadCdrConfig() {
        try {
            const resp = await fetch('/api/storage/external');
            const data = await resp.json();
            if (data.success) {
                this.cdrConfig.enabled = data.cleanupEnabled || false;
                this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb || 2000;
                this.cdrConfig.protectedHours = data.protectedHours || 24;
                this.cdrConfig.minFilesKeep = data.minFilesKeep || 10;
                
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
            }
        } catch (e) {
            console.warn('Failed to load CDR config:', e);
        }
    },
    
    updateCdrCleanupVisibility() {
        const card = document.getElementById('cdrCleanupCard');
        if (card) {
            const showCard = this.config.surveillanceStorageType === 'SD_CARD' && this.storageInfo.sdCardAvailable;
            card.style.display = showCard ? 'block' : 'none';
            
            if (showCard) {
                this.loadCdrConfig();
            }
        }
    },
    
    updateCdrUI() {
        // Update toggle
        const toggle = document.getElementById('cdrCleanupEnabled');
        if (toggle) toggle.checked = this.cdrConfig.enabled;
        
        // Update badge
        const badge = document.getElementById('cdrCleanupBadge');
        if (badge) {
            badge.textContent = this.cdrConfig.enabled ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
            badge.className = 'status-badge ' + (this.cdrConfig.enabled ? 'active' : 'inactive');
        }
        
        // Update sliders
        const reservedSlider = document.getElementById('cdrReservedSlider');
        const reservedValue = document.getElementById('cdrReservedValue');
        if (reservedSlider) reservedSlider.value = this.cdrConfig.reservedSpaceMb;
        if (reservedValue) reservedValue.textContent = this.cdrConfig.reservedSpaceMb >= 1000 ? 
            (this.cdrConfig.reservedSpaceMb / 1000) + ' GB' : this.cdrConfig.reservedSpaceMb + ' MB';
        
        const protectedSlider = document.getElementById('cdrProtectedSlider');
        const protectedValue = document.getElementById('cdrProtectedValue');
        if (protectedSlider) protectedSlider.value = this.cdrConfig.protectedHours;
        if (protectedValue) protectedValue.textContent = this.cdrConfig.protectedHours + 'h';
        
        const minKeepSlider = document.getElementById('cdrMinKeepSlider');
        const minKeepValue = document.getElementById('cdrMinKeepValue');
        if (minKeepSlider) minKeepSlider.value = this.cdrConfig.minFilesKeep;
        if (minKeepValue) minKeepValue.textContent = this.cdrConfig.minFilesKeep;
        
        // Update info
        if (this.cdrInfo) {
            const pathEl = document.getElementById('cdrPath');
            if (pathEl) pathEl.textContent = this.cdrInfo.cdrPath || BYD.i18n.t('surveillance.not_found');

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
        const enabled = document.getElementById('cdrCleanupEnabled').checked;
        try {
            await fetch('/api/storage/external/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            });
            this.cdrConfig.enabled = enabled;
            this.updateCdrUI();
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(enabled ? BYD.i18n.t('recording.cdr_enabled') : BYD.i18n.t('recording.cdr_disabled'), 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_toggle_failed'), 'error');
        }
    },
    
    updateCdrReserved(value) {
        this.cdrConfig.reservedSpaceMb = parseInt(value);
        const el = document.getElementById('cdrReservedValue');
        if (el) el.textContent = value >= 1000 ? (value / 1000) + ' GB' : value + ' MB';
        this.saveCdrConfig();
    },
    
    updateCdrProtected(value) {
        this.cdrConfig.protectedHours = parseInt(value);
        const el = document.getElementById('cdrProtectedValue');
        if (el) el.textContent = value + 'h';
        this.saveCdrConfig();
    },
    
    updateCdrMinKeep(value) {
        this.cdrConfig.minFilesKeep = parseInt(value);
        const el = document.getElementById('cdrMinKeepValue');
        if (el) el.textContent = value;
        this.saveCdrConfig();
    },
    
    async saveCdrConfig() {
        try {
            await fetch('/api/storage/external/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    reservedSpaceMb: this.cdrConfig.reservedSpaceMb,
                    protectedHours: this.cdrConfig.protectedHours,
                    minFilesKeep: this.cdrConfig.minFilesKeep
                })
            });
        } catch (e) {
            console.warn('Failed to save CDR config:', e);
        }
    },
    
    async triggerCdrCleanup() {
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
        }
    },
    
    updateSurvLimit(value) {
        this.config.surveillanceLimitMb = parseInt(value);
        const v = parseInt(value);
        document.getElementById('survLimitValue').textContent = v >= 1000 ? (v / 1000) + ' GB' : v + ' MB';
        // Live advisory while dragging — the point at which the user crosses the
        // volume's capacity is exactly when they need to know.
        this.updateBudgetBanner();
        this.markChanged();
        var _su = document.getElementById('storageUnsaved'); if (_su) _su.classList.add('show');
    },

    startClock() {
        const update = () => {
            const el = document.getElementById('currentTime');
            if (el) {
                el.textContent = new Date().toLocaleTimeString(BYD.i18n.getLang(), {
                    hour: '2-digit',
                    minute: '2-digit',
                    hour12: false
                });
            }
        };
        update();
        setInterval(update, 1000);
        
        // Config refresh (every 10s) to catch external changes (Telegram, IPC)
        setInterval(() => {
            // Unconditional: reloadConfig self-gates on hasUnsavedChanges and, when
            // dirty, refreshes only the overcommit banner (which writes no control).
            // Gating here as well would leave a dirty page's banner stale forever.
            this.reloadConfig();
            // Back off to every 3rd tick (10s → 30s) while the recordings
            // index is down, same rationale as recording.js.
            this._statsSkip = (this._statsSkip || 0) + 1;
            if (!this._indexDown || this._statsSkip % 3 === 0) {
                this.loadStorageStats();
            }

            // Refresh CDR info if SD card is selected
            if (this.config.surveillanceStorageType === 'SD_CARD' && this.storageInfo.sdCardAvailable) {
                this.loadCdrConfig();
            }
        }, 10000);
    },

    async loadConfig() {
        try {
            const resp = await fetch('/api/surveillance/config');
            const data = await resp.json();
            if (data.success && data.config) {
                this.config = { ...this.config, ...data.config };
                // Use server-provided distance/sensitivity if available, otherwise calculate from minObjectSize
                if (!data.config.distance) {
                    this.config.distance = this.sizeToDistance(this.config.minObjectSize || 0.08);
                }
                if (!data.config.sensitivity) {
                    this.config.sensitivity = 3;  // Default
                }
                this.lastConfigTimestamp = data.config.lastModified || Date.now();
            }
        } catch (e) {
            console.warn('Failed to load config:', e);
        }

        // Storage settings run CONCURRENTLY with the unified read below rather
        // than serially after it. Both must come after the config spread above
        // (which wholesale-replaces this.config and would clobber their writes),
        // but they touch disjoint keys — unified writes rectifyStrength /
        // segmentDurationMinutes / lowPowerWhileParked, storage writes
        // surveillanceLimitMb / surveillanceStorageType / storageInfo.* — so
        // there is no ordering dependency between them. This takes
        // /api/settings/storage (the expensive one) off the tail of the serial
        // chain, where it was gating everything downstream of loadConfig().
        const storageSettingsPromise = this.loadStorageSettings();

        // Pull rectifyStrength from the unified-config recording section.
        // The surveillance API doesn't surface it (it lives under recording.*
        // since both flows share one slider value), so we read it directly
        // and also use the camera section to gate visibility on dilink4.
        try {
            const uResp = await fetch('/api/settings/unified');
            const uData = await uResp.json();
            if (uData.success && uData.config) {
                if (uData.config.recording &&
                    typeof uData.config.recording.rectifyStrength === 'number') {
                    var rs = uData.config.recording.rectifyStrength;
                    if (rs < 0) rs = 0; if (rs > 100) rs = 100;
                    this.config.rectifyStrength = rs;
                } else {
                    this.config.rectifyStrength = 0;
                }
                // Shared clip duration (same key the recording page writes).
                if (uData.config.recording &&
                    typeof uData.config.recording.segmentDurationMinutes === 'number') {
                    var sd = uData.config.recording.segmentDurationMinutes;
                    if (sd !== 2 && sd !== 5 && sd !== 10) sd = 2;
                    this.config.segmentDurationMinutes = sd;
                } else {
                    this.config.segmentDurationMinutes = 2;
                }
                try {
                    var mode = uData.config.camera && uData.config.camera.cameraMode;
                    var card = document.getElementById('survRectifyCard');
                    if (card) card.style.display = (mode === 'dilink4') ? 'none' : '';
                } catch (_) {}
                // Low-power-while-parked: read the pano flag (the OEM flag is kept in
                // lockstep by toggleLowPowerMode). Absent → false (today's default).
                this.config.lowPowerWhileParked =
                    !!(uData.config.camera && uData.config.camera.surveillanceIdleThrottle === true);
            }
        } catch (e) {
            console.warn('Failed to load rectifyStrength: ' + (e && e.message));
        }

        // Join the storage read kicked off above. loadStorageSettings() swallows
        // its own errors, so this can't reject and reach callers.
        await storageSettingsPromise;
    },

    sizeToDistance(size) {
        if (size >= 0.22) return 1;
        if (size >= 0.15) return 2;
        if (size >= 0.10) return 3;
        if (size >= 0.06) return 4;
        return 5;
    },

    /**
     * Per-tab dirty diff. The Apply button is enabled only when the
     * CURRENTLY VISIBLE tab has uncommitted edits, and the per-card
     * "● unsaved" markers each reflect their own tab's dirty state.
     * Switching tabs re-runs the diff so the bar reflects what the user
     * is looking at, not the page-wide aggregate.
     */
    _tabDirty: function () {
        if (!this.savedConfig) return {};
        var dirty = {};
        var map = this._tabFieldMap || {};
        var tabIds = Object.keys(map);
        for (var t = 0; t < tabIds.length; t++) {
            var tabId = tabIds[t];
            var fields = map[tabId];
            var d = false;
            for (var i = 0; i < fields.length; i++) {
                var k = fields[i];
                if (k === '_storage_endpoint') continue;
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
        // Aggregate flag — used by reloadConfig() / visibility refresh to
        // decide whether to skip a server pull. True if ANY tab is dirty.
        this.hasUnsavedChanges = false;
        for (var k in dirtyByTab) {
            if (dirtyByTab[k]) { this.hasUnsavedChanges = true; break; }
        }
        this._dirtyByTab = dirtyByTab;

        // Apply button reflects the active tab only.
        var btn = document.getElementById('btnApply');
        if (btn) {
            var activeTab = this._activeTabId();
            var activeIsDirty = !!dirtyByTab[activeTab];
            btn.disabled = !activeIsDirty;
            btn.classList.toggle('has-changes', activeIsDirty);
        }

        // Each "● unsaved" marker maps 1:1 to a tab — show only when its
        // own tab is dirty so cards on a clean tab don't flash markers
        // because of edits on another tab.
        var markerTabMap = {
            'detectionUnsaved': 'detection',
            'recordingUnsaved': 'recording',
            'storageUnsaved':   'storage'
        };
        for (var elId in markerTabMap) {
            var el = document.getElementById(elId);
            if (el) el.classList.toggle('show', !!dirtyByTab[markerTabMap[elId]]);
        }
    },

    updateUI() {
        // Null-guard every top-level DOM read. updateUI is reused on
        // notifications.html (via initTelegramOnly) which only mounts the
        // Telegram subset of these controls — without the guards, the very
        // first missing element threw and aborted the function before it
        // reached the v2Telegram* checkboxes, leaving the toggles blank
        // after a page reload even when the persisted value was true.
        const survEnabled = document.getElementById('survEnabled');
        if (survEnabled) survEnabled.checked = this.config.enabled;

        // Badge is owned by refreshLiveStatusBadge() below, not here — this
        // only ran off this.config.enabled (the persisted feature toggle),
        // so the badge said "ON" the instant the app launched even though
        // nothing was armed yet (daemon still booting, ACC on, unlocked).
        // The toggle being on and the daemon actually being armed are two
        // different things; the badge needs to answer the second one.

        const preRecSlider = document.getElementById('preRecSlider');
        if (preRecSlider) preRecSlider.value = this.config.preRecordSeconds;
        const preRecValue = document.getElementById('preRecValue');
        if (preRecValue) preRecValue.textContent = this.config.preRecordSeconds + 's';
        const preLabel = document.getElementById('preLabel');
        if (preLabel) preLabel.textContent = BYD.i18n.t('surveillance.before_seconds', {n: this.config.preRecordSeconds});
        const timelinePre = document.getElementById('timelinePre');
        if (timelinePre) timelinePre.style.flex = this.config.preRecordSeconds / 10;

        const postRecSlider = document.getElementById('postRecSlider');
        if (postRecSlider) postRecSlider.value = this.config.postRecordSeconds;
        const postRecValue = document.getElementById('postRecValue');
        if (postRecValue) postRecValue.textContent = this.config.postRecordSeconds + 's';
        const postLabel = document.getElementById('postLabel');
        if (postLabel) postLabel.textContent = BYD.i18n.t('surveillance.after_seconds', {n: this.config.postRecordSeconds});
        const timelinePost = document.getElementById('timelinePost');
        if (timelinePost) timelinePost.style.flex = this.config.postRecordSeconds / 20;

        // Surveillance tier picker — bound to the ACC-off surveillanceQuality
        // key (independent of the dashcam page's recordingQuality picker).
        document.querySelectorAll('#survQualityBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.surveillanceQuality));
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingCodec));

        // Surveillance camera FPS buttons — bound to surveillanceCameraFps.
        document.querySelectorAll('#survFpsBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(this.config.surveillanceCameraFps || 15)));

        // Clip duration buttons (shared across recording + surveillance).
        document.querySelectorAll('#survClipDurationBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(this.config.segmentDurationMinutes || 2)));

        // Lens-dewarp slider (shared across recording + surveillance).
        var rectifySlider = document.getElementById('survRectifySlider');
        var rectifyLabel  = document.getElementById('survRectifyValue');
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

        // Active estimate (per-tier subtitles removed — info is summarised below).
        this.renderActiveEstimate();

        this.updateStorageLimitUI();

        // V2 Motion Detection UI
        this.updateV2UI();

        // Deterrent Action UI
        this.updateDeterrentUI();

        // Reset Apply button state after UI update (no unsaved changes after load)
        this.hasUnsavedChanges = false;
        const btnApply = document.getElementById('btnApply');
        if (btnApply) btnApply.disabled = true;
        var _du = document.getElementById('detectionUnsaved'); if (_du) _du.classList.remove('show');
        var _ru = document.getElementById('recordingUnsaved'); if (_ru) _ru.classList.remove('show');
        var _su2 = document.getElementById('storageUnsaved'); if (_su2) _su2.classList.remove('show');
    },

    updateCheckboxStyles() {
        ['detectPerson', 'detectCar', 'detectBike', 'detectAnimal'].forEach(id => {
            const cb = document.getElementById(id);
            if (cb && cb.parentElement) {
                cb.parentElement.classList.toggle('active', cb.checked);
            }
        });
    },

    async toggleSurveillance() {
        const enabled = document.getElementById('survEnabled').checked;
        try {
            const res = await fetch(enabled ? '/api/surveillance/enable' : '/api/surveillance/disable', { method: 'POST' });
            // The endpoint answers {"success":false,"error":...} when the config
            // write fails — previously the response was discarded, so a failed
            // persist showed a green "Surveillance enabled" toast and the
            // checkbox stayed on while nothing would arm on the next park.
            const body = await res.json().catch(() => null);
            if (body && body.success === false) {
                document.getElementById('survEnabled').checked = !enabled;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(body.error || BYD.i18n.t('surveillance.toggle_failed'), 'error');
                }
                return;
            }
            this.config.enabled = enabled;
            this.savedConfig.enabled = enabled;
            this.updateUI();
            if (BYD.utils && BYD.utils.toast) {
                // deferred=true → the vehicle is on, so the preference is stored
                // but nothing is armed yet. Say that instead of a bare "enabled".
                const msg = (body && body.deferred)
                    ? BYD.i18n.t(enabled ? 'surveillance.enabled_deferred' : 'surveillance.disabled_deferred')
                    : BYD.i18n.t(enabled ? 'surveillance.enabled' : 'surveillance.disabled');
                BYD.utils.toast(msg, 'success');
            }
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('surveillance.toggle_failed'), 'error');
        }
    },

    // Removed: updateDistance / updateSensitivity / updateFlashImmunity — the v1
    // sliders they drove no longer exist on any page (superseded by the v2
    // sensitivity slider and the detection-zone buttons) and nothing called them.
    // Their unguarded getElementById(...).textContent would have thrown.

    updateDetection() {
        this.config.detectPerson = document.getElementById('detectPerson').checked;
        this.config.detectCar = document.getElementById('detectCar').checked;
        this.config.detectBike = document.getElementById('detectBike').checked;
        var _da = document.getElementById('detectAnimal');
        if (_da) this.config.detectAnimal = _da.checked;
        this.updateCheckboxStyles();
        this.markChanged();
    },

    updatePreRec(value) {
        this.config.preRecordSeconds = parseInt(value);
        document.getElementById('preRecValue').textContent = value + 's';
        document.getElementById('preLabel').textContent = BYD.i18n.t('surveillance.before_seconds', {n: value});
        document.getElementById('timelinePre').style.flex = value / 10;
        this.markChanged();
    },

    updatePostRec(value) {
        this.config.postRecordSeconds = parseInt(value);
        document.getElementById('postRecValue').textContent = value + 's';
        document.getElementById('postLabel').textContent = BYD.i18n.t('surveillance.after_seconds', {n: value});
        document.getElementById('timelinePost').style.flex = value / 20;
        this.markChanged();
    },

    /**
     * Lens-dewarp slider (0..100). Mirrors the slider on the recording page
     * — same UnifiedConfigManager key (recording.rectifyStrength), same
     * effect on the active recorder. Editing here also affects ACC-on
     * dashcam recordings (and vice versa).
     *
     * Live debounced POST so the recorder picks up the change as the user
     * drags; Apply persists it durably alongside other recording-tab fields.
     */
    updateRectifyStrength(value) {
        var v = parseInt(value);
        if (isNaN(v)) v = 0;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        this.config.rectifyStrength = v;
        var label = document.getElementById('survRectifyValue');
        if (label) {
            var offTxt = (BYD.i18n && typeof BYD.i18n.t === 'function')
                ? BYD.i18n.t('recording.rectify_off') : null;
            if (!offTxt || offTxt === 'recording.rectify_off') offTxt = 'Off';
            label.textContent = (v === 0) ? offTxt : (v + '%');
        }
        this.markChanged();
        if (this._rectifyDebounce) clearTimeout(this._rectifyDebounce);
        var self = this;
        this._rectifyDebounce = setTimeout(function () {
            try {
                fetch('/api/settings/unified', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        section: 'recording',
                        data: { rectifyStrength: v }
                    })
                });
            } catch (_) { /* live preview is best-effort */ }
        }, 200);
    },

    /** Clip segment length (minutes: 2/5/10). SAME shared key as the recording
     *  page — editing here also changes the dashcam axis. Persisted on Apply
     *  via the quality POST (which validates + live-applies to both axes);
     *  takes effect on the next segment rotation. */
    setClipDuration(minutes) {
        var m = parseInt(minutes, 10);
        if (m !== 2 && m !== 5 && m !== 10) m = 2;
        this.config.segmentDurationMinutes = m;
        document.querySelectorAll('#survClipDurationBtns .btn-toggle').forEach(function (btn) {
            btn.classList.toggle('active', btn.dataset.value === String(m));
        });
        this.markChanged();
    },

    setRecordingQuality(tier) {
        this.config.surveillanceQuality = tier;
        document.querySelectorAll('#survQualityBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === tier));
        this.renderActiveEstimate();
        this.markChanged();
    },

    setCodec(codec) {
        this.config.recordingCodec = codec;
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === codec));
        this.renderActiveEstimate();
        this.markChanged();
    },

    /** Look up the per-tier estimate computed by the server (codec+fps already
     *  factored in). Returns null if not loaded yet. */
    estimateForTier(tier) {
        const opts = this.config.recordingQualityOptions || {};
        return opts[tier] || null;
    },

    formatEstimate(est) {
        if (!est) return '—';
        const parts = [BYD.i18n.t('recording.unit_mbps', {n: (est.bitrateMbps != null ? est.bitrateMbps : '—')})];
        if (est.mbPer2Min != null) parts.push(BYD.i18n.t('recording.unit_mb_per_2min', {n: est.mbPer2Min}));
        if (est.qualityEquivalent) parts.push(est.qualityEquivalent);
        return parts.join(' · ');
    },

    renderActiveEstimate() {
        const el = document.getElementById('survActiveEstimate');
        if (el) {
            // Compute locally from the user's *current* selection, not the
            // server's stale activeRecordingEstimate. Show "saved → pending"
            // diff when the user has changed the tier without applying yet.
            const currentTier = this.config.surveillanceQuality;
            const savedTier = this.savedConfig ? this.savedConfig.surveillanceQuality : currentTier;
            const currentEst = this.estimateForTier(currentTier);
            const savedEst = this.estimateForTier(savedTier);
            if (currentTier === savedTier || !savedEst) {
                el.textContent = this.formatEstimate(currentEst);
            } else {
                el.textContent = this.formatEstimate(savedEst)
                    + BYD.i18n.t('recording.estimate_diff_arrow')
                    + this.formatEstimate(currentEst);
            }
        }
        // Measured-FPS row mirrors recording.html: shown when the HAL is
        // emitting at a different rate than what the user requested.
        const row = document.getElementById('survFpsClampRow');
        const fpsEl = document.getElementById('survFpsActual');
        if (row && fpsEl) {
            const actual = this.config.cameraFpsActual;
            if (actual == null) {
                row.style.display = 'none';
            } else {
                row.style.display = '';
                fpsEl.textContent = this.config.cameraFpsClampNote
                    ? this.config.cameraFpsClampNote
                    : (actual + ' fps');
            }
        }
    },

    // ==================== V2 Motion Detection ====================

    // V2 hint texts — looked up via BYD.i18n
    v2EnvPresetHint(preset) { return BYD.i18n.t('surveillance.env_hint.' + preset); },
    v2DetectionZoneHint(zone) { return BYD.i18n.t('surveillance.zone_hint.' + zone); },

    /**
     * ACC-OFF mode toggle. Persists immediately (no Apply step) because the
     * choice flips a different recording subsystem and waiting until the
     * user re-finds the Apply button on a different tab is awkward. Mirrors
     * the immediate-save pattern used for Telegram tier toggles.
     *
     * Side effect: dim/disable the detection / side-cam / ROI cards when
     * Continuous is selected — those settings are inert in continuous mode
     * (no motion analysis runs), so showing them as live is misleading.
     */
    setAccOffMode(mode) {
        if (mode !== 'smart' && mode !== 'continuous') return;
        const prev = this.config.accOffMode || 'smart';
        if (prev === mode) return;

        this.config.accOffMode = mode;
        // Reflect button state immediately so the click feels responsive
        // before the network round-trip resolves.
        document.querySelectorAll('#accOffModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === mode));
        this.applyAccOffModeUI();

        const self = this;
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ accOffMode: mode })
        }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
          .then(() => {
              if (self.savedConfig) self.savedConfig.accOffMode = mode;
              self.markChanged();
              if (BYD.utils && BYD.utils.toast) {
                  const k = mode === 'continuous'
                      ? 'surveillance.accoff_saved_continuous'
                      : 'surveillance.accoff_saved_smart';
                  const fallback = mode === 'continuous'
                      ? 'Continuous recording enabled'
                      : 'Smart event recording enabled';
                  const localized = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(k) : null;
                  BYD.utils.toast(localized || fallback, 'success');
              }
          })
          .catch(() => {
              // Revert in-memory + UI on failure so the displayed state
              // matches the server.
              self.config.accOffMode = prev;
              document.querySelectorAll('#accOffModeBtns .btn-toggle').forEach(btn =>
                  btn.classList.toggle('active', btn.dataset.value === prev));
              self.applyAccOffModeUI();
              if (BYD.utils && BYD.utils.toast) {
                  const localized = BYD.i18n && BYD.i18n.t
                      ? BYD.i18n.t('surveillance.accoff_save_failed') : null;
                  BYD.utils.toast(localized || 'Could not save mode', 'error');
              }
          });
    },

    setArmMode(mode) {
        if (mode !== 'lock' && mode !== 'power') return;
        const prev = this.config.armMode || 'lock';
        if (prev === mode) return;

        this.config.armMode = mode;
        // Reflect button state immediately so the click feels responsive
        // before the network round-trip resolves.
        document.querySelectorAll('#armModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === mode));
        this.applyArmModeUI();

        const self = this;
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ armMode: mode })
        }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
          .then(() => {
              if (self.savedConfig) self.savedConfig.armMode = mode;
              self.markChanged();
              if (BYD.utils && BYD.utils.toast) {
                  const k = mode === 'power'
                      ? 'surveillance.arm_saved_power'
                      : 'surveillance.arm_saved_lock';
                  const fallback = mode === 'power'
                      ? 'Arms on power-off'
                      : 'Arms on door lock';
                  const localized = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(k) : null;
                  BYD.utils.toast(localized || fallback, 'success');
              }
          })
          .catch(() => {
              // Revert in-memory + UI on failure so the displayed state
              // matches the server.
              self.config.armMode = prev;
              document.querySelectorAll('#armModeBtns .btn-toggle').forEach(btn =>
                  btn.classList.toggle('active', btn.dataset.value === prev));
              self.applyArmModeUI();
              if (BYD.utils && BYD.utils.toast) {
                  const localized = BYD.i18n && BYD.i18n.t
                      ? BYD.i18n.t('surveillance.arm_save_failed') : null;
                  BYD.utils.toast(localized || 'Could not save arm mode', 'error');
              }
          });
    },

    /**
     * Update the arm-mode hint paragraph based on the current armMode.
     * Called from setArmMode() and the load path (updateV2UI). Cosmetic only.
     */
    applyArmModeUI() {
        const mode = this.config.armMode || 'lock';
        const hintKey = mode === 'power'
            ? 'surveillance.arm_hint_power'
            : 'surveillance.arm_hint_lock';
        const hintEl = document.getElementById('armModeHint');
        if (hintEl) {
            hintEl.setAttribute('data-i18n', hintKey);
            hintEl.textContent = (BYD.i18n && BYD.i18n.t)
                ? (BYD.i18n.t(hintKey) || hintEl.textContent)
                : hintEl.textContent;
        }
    },

    /**
     * Operating mode: 'onAndOff' (full behaviour) | 'onOnly' (let the head unit sleep
     * after the car is off). Optimistic set → POST /api/surveillance/config → revert +
     * toast on failure. Mirrors setArmMode(). Takes effect on the next ACC cycle (the
     * daemons re-read the mtime-gated config); no live restart, matching arm mode.
     */
    setOperatingMode(mode) {
        if (mode !== 'onAndOff' && mode !== 'onOnly') return;
        const prev = this.config.operatingMode || 'onAndOff';
        if (prev === mode) return;

        this.config.operatingMode = mode;
        document.querySelectorAll('#operatingModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === mode));
        this.applyOperatingModeUI();

        const self = this;
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ operatingMode: mode })
        }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
          .then(() => {
              if (self.savedConfig) self.savedConfig.operatingMode = mode;
              self.markChanged();
              if (BYD.utils && BYD.utils.toast) {
                  const k = mode === 'onOnly'
                      ? 'surveillance.operating_mode_saved_on_only'
                      : 'surveillance.operating_mode_saved_on_off';
                  const fallback = mode === 'onOnly'
                      ? 'Sleeps when the car is off'
                      : 'Keeps working while parked';
                  const localized = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(k) : null;
                  BYD.utils.toast(localized || fallback, 'success');
              }
          })
          .catch(() => {
              self.config.operatingMode = prev;
              document.querySelectorAll('#operatingModeBtns .btn-toggle').forEach(btn =>
                  btn.classList.toggle('active', btn.dataset.value === prev));
              self.applyOperatingModeUI();
              if (BYD.utils && BYD.utils.toast) {
                  const localized = BYD.i18n && BYD.i18n.t
                      ? BYD.i18n.t('surveillance.operating_mode_save_failed') : null;
                  BYD.utils.toast(localized || 'Could not save operating mode', 'error');
              }
          });
    },

    /**
     * Update the operating-mode hint paragraph based on the current operatingMode.
     * Called from setOperatingMode() and the load path (updateV2UI). Cosmetic only.
     */
    applyOperatingModeUI() {
        const mode = this.config.operatingMode || 'onAndOff';
        const inert = (mode === 'onOnly');
        const hintKey = inert
            ? 'surveillance.operating_mode_hint_on_only'
            : 'surveillance.operating_mode_hint_on_off';
        const hintEl = document.getElementById('operatingModeHint');
        if (hintEl) {
            hintEl.setAttribute('data-i18n', hintKey);
            hintEl.textContent = (BYD.i18n && BYD.i18n.t)
                ? (BYD.i18n.t(hintKey) || hintEl.textContent)
                : hintEl.textContent;
        }

        // Inert-state reflection: in "On Only" mode Overdrive fully shuts down when
        // parked, so every post-vehicle-OFF surveillance control is meaningless. Dim +
        // disable them (and their hints) so the user isn't tuning knobs that can't apply,
        // and show one explanatory note. Mirrors applyAccOffModeUI's dim idiom
        // (opacity + pointer-events, Chrome-58/ES5-safe). We DO NOT dim the operating-mode
        // row itself (or the user couldn't switch back). Restored (inert=false) on onAndOff.
        const self = this;
        const dimRow = function (controlId) {
            const el = document.getElementById(controlId);
            if (!el) return;
            const row = el.closest ? el.closest('.setting-row') : null;
            if (row) {
                row.style.opacity = inert ? '0.45' : '';
                row.style.pointerEvents = inert ? 'none' : '';
            }
        };
        const dimHint = function (hintId) {
            const h = document.getElementById(hintId);
            if (h) { h.style.opacity = inert ? '0.45' : ''; }
        };
        // Post-OFF surveillance controls (all inert when onOnly).
        ['survEnabled', 'survKeepUsbPower', 'survMobileDataKeepAlive', 'survLowPowerMode', 'lowSocCutoffSlider']
            .forEach(dimRow);
        dimHint('lowSocCutoffHint');
        // Arm-mode + ACC-off-mode are btn-groups (no single input id) — dim by their
        // container's row + hint, and disable the buttons so it's not merely visual.
        ['armModeBtns', 'accOffModeBtns'].forEach(function (grpId) {
            const grp = document.getElementById(grpId);
            if (grp) {
                const row = grp.closest ? grp.closest('.setting-row') : null;
                if (row) {
                    row.style.opacity = inert ? '0.45' : '';
                    row.style.pointerEvents = inert ? 'none' : '';
                }
                const btns = grp.querySelectorAll('.btn-toggle');
                for (let i = 0; i < btns.length; i++) btns[i].disabled = inert;
            }
        });
        dimHint('armModeHint');
        dimHint('accOffModeHint');
        // Disable the underlying inputs too (defensive — not touch-only).
        ['survEnabled', 'survKeepUsbPower', 'survMobileDataKeepAlive', 'survLowPowerMode', 'lowSocCutoffSlider']
            .forEach(function (id) { const el = document.getElementById(id); if (el) el.disabled = inert; });
        // Explanatory note (shown only when inert).
        const note = document.getElementById('postOffDisabledNotice');
        if (note) note.style.display = inert ? '' : 'none';
    },

    /**
     * Update the hint paragraph + dim/restore detection-related cards based
     * on the current accOffMode. Called from setAccOffMode() and from the
     * load path (updateV2UI). No save side-effect — purely cosmetic.
     */
    applyAccOffModeUI() {
        const mode = this.config.accOffMode || 'smart';
        const hintKey = mode === 'continuous'
            ? 'surveillance.accoff_hint_continuous'
            : 'surveillance.accoff_hint_smart';
        const hintEl = document.getElementById('accOffModeHint');
        if (hintEl) {
            hintEl.setAttribute('data-i18n', hintKey);
            hintEl.textContent = (BYD.i18n && BYD.i18n.t)
                ? (BYD.i18n.t(hintKey) || hintEl.textContent)
                : hintEl.textContent;
        }
        // Dim the cards whose contents don't apply in continuous mode:
        // motion/AI detection cards (sensitivity, env preset, side-cam
        // boost, ROI) all live on data-tab="detection". We deliberately
        // don't dim the Recording tab — codec / bitrate / fps still apply
        // for continuous output. Safe Locations also stays live (the
        // gating still works) and we skip the mode-toggle card itself.
        const dim = (mode === 'continuous');
        document.querySelectorAll('.card[data-tab="detection"]').forEach(card => {
            if (card.querySelector('#safeLocEnabled')) return;
            if (card.querySelector('#accOffModeBtns')) return;
            card.style.opacity = dim ? '0.45' : '';
            card.style.pointerEvents = dim ? 'none' : '';
        });
    },

    /**
     * Toggle "Keep USB powered while parked".
     *
     * On this platform the SD-card slot shares the USB power domain, so turning USB
     * power OFF lets the head unit sleep on the next ACC-OFF cycle and the SD card
     * loses power while parked. Surveillance therefore CANNOT use the SD card when
     * USB power is off — it must record to Internal storage.
     *
     * Flow when turning OFF while surveillance storage is SD/USB: show a SOTA confirm
     * explaining the consequence; on confirm, switch surveillance storage to Internal
     * and persist BOTH changes together; on cancel, revert the toggle. Turning ON, or
     * turning OFF when already on Internal, persists directly (no prompt).
     */
    toggleKeepUsbPower() {
        const el = document.getElementById('survKeepUsbPower');
        if (!el) return;
        const on = el.checked;
        const self = this;
        const t = (k, fb) => (BYD.i18n && BYD.i18n.t ? (BYD.i18n.t(k) || fb) : fb);

        // Storage that depends on the shared USB/SD power rail. INTERNAL is safe.
        const storage = this.config.surveillanceStorageType || 'INTERNAL';
        const storageNeedsUsbPower = (storage === 'SD_CARD' || storage === 'USB');

        // Turning OFF while surveillance records to SD/USB → confirm + auto-switch.
        if (!on && storageNeedsUsbPower) {
            // The confirmation dialog is the user's consent for the silent storage
            // switch to Internal. If it's unavailable (core.js failed to load /
            // BYD.utils corrupted), fail safe: do NOT auto-confirm — revert the
            // toggle to ON and warn, so storage is never switched without consent.
            if (!BYD.utils || !BYD.utils.confirmDialog) {
                el.checked = true;
                self.config.keepUsbPowerOnAccOff = true;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(t('surveillance.usb_off_confirm_unavailable',
                        'Confirmation unavailable — cannot turn off USB power safely'), 'error');
                }
                return;
            }

            const proceed = BYD.utils.confirmDialog({
                title: t('surveillance.usb_off_confirm_title', 'Turn off USB power?'),
                body: t('surveillance.usb_off_confirm_body',
                    'The SD card shares the USB power rail, so it will be unpowered while parked. '
                    + 'Surveillance will be switched to Internal storage so recording keeps working. '
                    + 'You can switch back to the SD card after turning USB power on again.'),
                confirmLabel: t('surveillance.usb_off_confirm_ok', 'Turn off & use Internal'),
                cancelLabel: t('common.cancel', 'Cancel'),
                danger: false
            });

            Promise.resolve(proceed).then(function (ok) {
                if (!ok) {
                    // Reverted — restore toggle to ON, no persist. Guard el in case
                    // it was removed from the DOM while the dialog was open (mirrors
                    // the defensive check in _persistKeepUsbPower's catch).
                    if (el) el.checked = true;
                    self.config.keepUsbPowerOnAccOff = true;
                    return;
                }
                // Switch surveillance storage to Internal in the UI/config, then
                // persist USB-off + storage together. Set the flag on config first so
                // the notice/button-state refresh inside persist reflects USB-off.
                self.config.keepUsbPowerOnAccOff = false;
                const prevStorageType = self._applyInternalStorageForUsbOff();
                self._persistKeepUsbPower(false, el, /*alsoStorage*/ true, prevStorageType);
            }).catch(function () {
                // Dialog threw / rejected — leave USB power ON and keep the toggle in
                // sync so el.checked (OFF) doesn't diverge from config. Guard el in
                // case it was removed from the DOM while the dialog was open.
                if (el) el.checked = true;
                self.config.keepUsbPowerOnAccOff = true;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(t('surveillance.keep_usb_save_failed', 'Could not save setting'), 'error');
                }
            });
            return;
        }

        // Direct persist (ON, or OFF-while-Internal — no consequence to warn about).
        this.config.keepUsbPowerOnAccOff = on;
        this._persistKeepUsbPower(on, el, /*alsoStorage*/ false);
    },

    /**
     * Force surveillance storage to Internal (used when USB power is turned off).
     * Mirrors the relevant side effects of setStorageType('INTERNAL') without its
     * availability guards, and refreshes the dependent UI.
     */
    _applyInternalStorageForUsbOff() {
        const prevStorageType = this.config.surveillanceStorageType || 'INTERNAL';
        this.config.surveillanceStorageType = 'INTERNAL';
        document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(function (btn) {
            btn.classList.toggle('active', btn.dataset.value === 'INTERNAL');
        });
        const newMax = this.effectiveMaxLimitMb();
        if (this.config.surveillanceLimitMb > newMax) this.config.surveillanceLimitMb = newMax;
        this.updateStorageLimitUI();
        this.updateCdrCleanupVisibility();
        // Flag the storage tab dirty via the standard diff so the Apply button and
        // per-tab "unsaved" markers stay consistent (mirrors setStorageType()).
        this.markChanged();
        // Return the prior storage type so the caller can revert if persist fails.
        return prevStorageType;
    },

    /**
     * Persist the USB-power flag (and optionally the storage switch) to the daemon.
     * Optimistic UI with revert-on-failure. Updates the inline notice + toast.
     *
     * The USB-power flag lives in /api/surveillance/config; the surveillance storage
     * type lives in /api/settings/storage (handled by QualitySettingsApiHandler), so
     * the storage switch is a SEPARATE request chained after the flag persists.
     */
    _persistKeepUsbPower(on, el, alsoStorage, prevStorageType) {
        const self = this;
        const t = (k, fb) => (BYD.i18n && BYD.i18n.t ? (BYD.i18n.t(k) || fb) : fb);

        const persistStorage = function () {
            if (!alsoStorage) return Promise.resolve();
            return fetch('/api/settings/storage', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ surveillanceStorageType: 'INTERNAL' })
            }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
              .then(function (data) {
                  // The daemon answers HTTP 200 with {success:false} when the
                  // requested storage type change is rejected (target volume
                  // unavailable). Treat that as a failure so the caller's catch
                  // reverts the optimistic config instead of diverging on reload.
                  if (data && data.success === false) {
                      return Promise.reject(new Error(data.error || 'storage change rejected'));
                  }
                  return data;
              });
        };

        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ keepUsbPowerOnAccOff: on })
        }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
          .then(persistStorage)
          .then(() => {
              if (self.savedConfig) {
                  self.savedConfig.keepUsbPowerOnAccOff = on;
                  if (alsoStorage) self.savedConfig.surveillanceStorageType = 'INTERNAL';
              }
              self.markChanged();
              self.updateUsbPowerStorageNotice();
              if (BYD.utils && BYD.utils.toast) {
                  if (alsoStorage) {
                      BYD.utils.toast(t('surveillance.usb_off_switched_internal',
                          'USB power off — surveillance now records to Internal storage'), 'success');
                  } else {
                      const msg = on
                          ? t('surveillance.keep_usb_saved_on', 'USB will stay powered while parked')
                          : t('surveillance.keep_usb_saved_off', 'USB will sleep while parked (next ACC-OFF)');
                      BYD.utils.toast(msg, 'success');
                  }
              }
              // Refresh storage stats from the daemon so the UI doesn't show stale
              // free/total/availability after the storage type was switched server-side.
              if (alsoStorage && typeof self.loadStorageSettings === 'function') {
                  return Promise.resolve(self.loadStorageSettings())
                      .then(function () { self.updateUsbPowerStorageNotice(); })
                      .catch(function () {});
              }
          })
          .catch(() => {
              // Revert toggle + config on failure.
              self.config.keepUsbPowerOnAccOff = !on;
              if (el) el.checked = !on;
              // Also revert the storage type if we switched it to INTERNAL before
              // persisting, so storage doesn't stay changed while the USB flag rolls
              // back (state desync). Re-sync dependent UI + dirty markers.
              if (alsoStorage && self.config && prevStorageType) {
                  self.config.surveillanceStorageType = prevStorageType;
                  document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(function (btn) {
                      btn.classList.toggle('active', btn.dataset.value === prevStorageType);
                  });
                  if (typeof self.updateStorageLimitUI === 'function') self.updateStorageLimitUI();
                  if (typeof self.updateCdrCleanupVisibility === 'function') self.updateCdrCleanupVisibility();
              }
              // Recalculate the dirty-diff after reverting config so the Apply button
              // and per-tab "unsaved" markers reflect the rolled-back state. Must run
              // on every revert path (not just alsoStorage), otherwise the direct
              // OFF-while-Internal / ON path leaves the button state stale.
              self.markChanged();
              self.updateUsbPowerStorageNotice();
              if (BYD.utils && BYD.utils.toast) {
                  BYD.utils.toast(t('surveillance.keep_usb_save_failed', 'Could not save setting'), 'error');
              }
          });
    },

    /**
     * Low-power-while-parked master toggle. Drives BOTH pipelines together:
     * camera.surveillanceIdleThrottle (pano surveillance camera) and
     * oemDashcam.idleThrottleWhenParked (OEM forward dashcam). Persists immediately
     * (no Apply button) to /api/settings/unified, optimistic with revert-on-failure.
     * Both flags default OFF, so turning this on is the single opt-in that activates
     * the parked idle frame-rate throttle across the whole recording stack.
     */
    toggleLowPowerMode() {
        const el = document.getElementById('survLowPowerMode');
        if (!el) return;
        const on = el.checked;
        const self = this;
        const t = (k, fb) => (BYD.i18n && BYD.i18n.t ? (BYD.i18n.t(k) || fb) : fb);

        this.config.lowPowerWhileParked = on;

        // Persist the two section flags in parallel. Both must land for the feature
        // to behave coherently; if either fails we revert the toggle + config.
        const postSection = (section, data) => fetch('/api/settings/unified', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ section: section, data: data })
        }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)));

        Promise.all([
            postSection('camera', { surveillanceIdleThrottle: on }),
            postSection('oemDashcam', { idleThrottleWhenParked: on })
        ]).then(function () {
            if (BYD.utils && BYD.utils.toast) {
                const msg = on
                    ? t('surveillance.low_power_saved_on', 'Low-power mode on — camera idles at a low frame rate while parked')
                    : t('surveillance.low_power_saved_off', 'Low-power mode off — camera stays at full frame rate while parked');
                BYD.utils.toast(msg, 'success');
            }
        }).catch(function () {
            // Revert on failure so the toggle never diverges from persisted state.
            self.config.lowPowerWhileParked = !on;
            if (el) el.checked = !on;
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(t('surveillance.low_power_save_failed', 'Could not save low-power mode'), 'error');
            }
        });
    },

    /**
     * Parked cellular keep-alive toggle. Default OFF: on some models the mobile-data
     * module sleeps a while after ACC OFF, dropping cellular while WiFi survives; when
     * ON the daemon re-asserts the data master switch each parked tick. Persists
     * immediately (no Apply) to /api/surveillance/config, optimistic with
     * revert-on-failure. Pure persist — the daemon snapshots it when the next parked
     * session starts, so an in-flight session is unaffected.
     */
    toggleMobileDataKeepAlive() {
        const el = document.getElementById('survMobileDataKeepAlive');
        if (!el) return;
        const on = el.checked;
        const self = this;
        const t = (k, fb) => (BYD.i18n && BYD.i18n.t ? (BYD.i18n.t(k) || fb) : fb);

        this.config.mobileDataKeepAlive = on;

        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ mobileDataKeepAlive: on })
        }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
          .then(function () {
              if (self.savedConfig) self.savedConfig.mobileDataKeepAlive = on;
              if (BYD.utils && BYD.utils.toast) {
                  const msg = on
                      ? t('surveillance.mobile_data_keepalive_saved_on', 'Mobile data will stay awake while parked (next ACC-OFF)')
                      : t('surveillance.mobile_data_keepalive_saved_off', 'Mobile data will be left alone while parked');
                  BYD.utils.toast(msg, 'success');
              }
          }).catch(function () {
              // Revert so the toggle never diverges from persisted state.
              self.config.mobileDataKeepAlive = !on;
              if (el) el.checked = !on;
              if (BYD.utils && BYD.utils.toast) {
                  BYD.utils.toast(t('surveillance.mobile_data_keepalive_save_failed',
                      'Could not save mobile-data setting'), 'error');
              }
          });
    },

    /**
     * Show/hide the inline "recording to Internal because USB power is off" warning.
     * Visible only when keepUsbPowerOnAccOff is explicitly false. Safe to call any
     * time (load, toggle, storage change).
     */
    updateUsbPowerStorageNotice() {
        const note = document.getElementById('survUsbPowerStorageNotice');
        if (note) {
            note.style.display = (this.config.keepUsbPowerOnAccOff === false) ? 'flex' : 'none';
        }
        // Keep the SD/USB storage buttons' disabled state in sync with the toggle.
        try { this.updateStorageTypeUI(); } catch (e) {}
    },

    setEnvironmentPreset(preset) {
        this.config.environmentPreset = preset;
        document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === preset));
        // Hide Custom button when a real preset is selected
        const customBtn = document.getElementById('envPresetCustom');
        if (customBtn) customBtn.classList.remove('active');

        // Update environment preset hint
        var _eh = document.getElementById('envPresetHint');
        if (_eh) {
            _eh.setAttribute('data-i18n', 'surveillance.env_hint.' + preset);
            _eh.textContent = this.v2EnvPresetHint(preset) || '';
        }

        // Apply preset values to other V2 controls (UI only, not sent yet)
        const p = this.v2Presets[preset];
        if (p) {
            this.config.sensitivityLevel = p.sensitivityLevel;
            this.config.detectionZone = p.detectionZone;
            this.config.loiteringTime = p.loiteringTime;

            // Update sensitivity slider + label + hint
            const sensSlider = document.getElementById('v2SensitivitySlider');
            if (sensSlider) sensSlider.value = p.sensitivityLevel;
            const sensValue = document.getElementById('v2SensitivityValue');
            if (sensValue) {
                sensValue.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + p.sensitivityLevel);
                sensValue.textContent = this.v2SensLabel(p.sensitivityLevel) || p.sensitivityLevel;
            }
            var _sh = document.getElementById('v2SensitivityHint');
            if (_sh) {
                _sh.setAttribute('data-i18n', 'surveillance.v2_sens_hint.' + p.sensitivityLevel);
                _sh.textContent = this.v2SensHint(p.sensitivityLevel) || '';
            }

            // Update detection zone buttons + hint
            document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
                btn.classList.toggle('active', btn.dataset.value === p.detectionZone));
            var _dh = document.getElementById('detectionZoneHint');
            if (_dh) {
                _dh.setAttribute('data-i18n', 'surveillance.zone_hint.' + p.detectionZone);
                _dh.textContent = this.v2DetectionZoneHint(p.detectionZone) || '';
            }

            // Update loitering slider + label
            const loiterSlider = document.getElementById('loiteringTimeSlider');
            if (loiterSlider) loiterSlider.value = p.loiteringTime;
            const loiterValue = document.getElementById('loiteringTimeValue');
            if (loiterValue) loiterValue.textContent = p.loiteringTime + 's';
            
            // Update shadow filter select
            if (p.shadowFilter !== undefined) {
                this.config.shadowFilter = p.shadowFilter;
                const shadowSelect = document.getElementById('shadowFilterSelect');
                if (shadowSelect) shadowSelect.value = p.shadowFilter;
            }
        }
        this.markChanged();
    },

    updateV2Sensitivity(value) {
        this.config.sensitivityLevel = parseInt(value);
        const label = document.getElementById('v2SensitivityValue');
        if (label) {
            label.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + value);
            label.textContent = this.v2SensLabel(value) || value;
        }
        var _sh = document.getElementById('v2SensitivityHint');
        if (_sh) {
            _sh.setAttribute('data-i18n', 'surveillance.v2_sens_hint.' + value);
            _sh.textContent = this.v2SensHint(value) || '';
        }
        this._deselectPresetIfCustom();
        this.markChanged();
    },

    setDetectionZone(zone) {
        this.config.detectionZone = zone;
        document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === zone));
        var _dh = document.getElementById('detectionZoneHint');
        if (_dh) {
            _dh.setAttribute('data-i18n', 'surveillance.zone_hint.' + zone);
            _dh.textContent = this.v2DetectionZoneHint(zone) || '';
        }
        this._deselectPresetIfCustom();
        this.markChanged();
    },

    updateLoiteringTime(value) {
        this.config.loiteringTime = parseInt(value);
        const label = document.getElementById('loiteringTimeValue');
        if (label) label.textContent = value + 's';
        this._deselectPresetIfCustom();
        this.markChanged();
    },

    updateApproachTrigger(value) {
        const n = parseInt(value);
        this.config.approachTrigger = n;
        const label = document.getElementById('approachTriggerValue');
        if (label) label.textContent = (n === 0) ? BYD.i18n.t('surveillance.approach_off') || 'Off' : n + 's';
        // Independent of the environment presets — does not deselect one.
        this.markChanged();
    },

    updateShadowFilter(value) {
        this.config.shadowFilter = parseInt(value);
        const hint = document.getElementById('shadowFilterHint');
        if (hint) {
            hint.setAttribute('data-i18n', 'surveillance.shadow_hint.' + this.config.shadowFilter);
            hint.textContent = BYD.i18n.t('surveillance.shadow_hint.' + this.config.shadowFilter) || '';
        }
        this.markChanged();
    },

    updateMotionSalience() {
        var el = document.getElementById('v2MotionSalience');
        this.config.motionSalienceEnabled = (el && el.checked) || false;
        this.markChanged();
    },

    updateDiscardEmptyMotion() {
        var el = document.getElementById('v2DiscardEmptyMotion');
        this.config.discardEmptyBrightMotionEvents = (el && el.checked) || false;
        // The night sub-toggle only makes sense while the primary discard is on;
        // disable + visually gray it out when the parent is off.
        this._syncNightDiscardEnabled();
        this.markChanged();
    },

    updateDiscardEmptyMotionAtNight() {
        var el = document.getElementById('v2DiscardEmptyMotionNight');
        this.config.discardEmptyMotionAtNight = (el && el.checked) || false;
        this.markChanged();
    },

    _syncNightDiscardEnabled() {
        var parentOn = !!this.config.discardEmptyBrightMotionEvents;
        var row = document.getElementById('discardEmptyMotionNightRow');
        var nightEl = document.getElementById('v2DiscardEmptyMotionNight');
        if (nightEl) nightEl.disabled = !parentOn;
        if (row) row.style.opacity = parentOn ? '1' : '0.45';
    },
    
    _deselectPresetIfCustom() {
        // Check if current values match ANY preset
        let matchedPreset = null;
        for (const [name, p] of Object.entries(this.v2Presets)) {
            if (this.config.sensitivityLevel == p.sensitivityLevel &&
                this.config.detectionZone === p.detectionZone &&
                this.config.loiteringTime == p.loiteringTime) {
                matchedPreset = name;
                break;
            }
        }
        
        const customBtn = document.getElementById('envPresetCustom');
        if (matchedPreset) {
            // Values match a known preset — highlight it
            this.config.environmentPreset = matchedPreset;
            document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
                btn.classList.toggle('active', btn.dataset.value === matchedPreset));
            if (customBtn) customBtn.classList.remove('active');
            var _eh = document.getElementById('envPresetHint');
            if (_eh) {
                _eh.setAttribute('data-i18n', 'surveillance.env_hint.' + matchedPreset);
                _eh.textContent = this.v2EnvPresetHint(matchedPreset) || '';
            }
        } else {
            // No preset matches — show Custom
            document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
                btn.classList.remove('active'));
            if (customBtn) customBtn.classList.add('active');
            var _eh = document.getElementById('envPresetHint');
            if (_eh) {
                _eh.setAttribute('data-i18n', 'surveillance.custom_config');
                _eh.textContent = BYD.i18n.t('surveillance.custom_config');
            }
        }
    },

    updateV2Cameras() {
        this.config.cameraFront = document.getElementById('v2CameraFront').checked;
        this.config.cameraRight = document.getElementById('v2CameraRight').checked;
        this.config.cameraLeft = document.getElementById('v2CameraLeft').checked;
        this.config.cameraRear = document.getElementById('v2CameraRear').checked;
        this.markChanged();
    },

    /**
     * Side-cam Boost: writes overrides into quadrantOverrides Q1 (right) and
     * Q3 (left). Disabling the boost removes both keys so the side cams
     * inherit the global sensitivity / zone again.
     */
    _writeSideCamOverrides(sens, zone) {
        const ov = Object.assign({}, this.config.quadrantOverrides || {});
        if (sens == null && zone == null) {
            delete ov.Q1;
            delete ov.Q3;
        } else {
            const perQ = {};
            if (sens != null) perQ.sensitivityLevel = parseInt(sens);
            if (zone != null) perQ.detectionZone = zone;
            ov.Q1 = perQ;
            ov.Q3 = Object.assign({}, perQ);
        }
        this.config.quadrantOverrides = ov;
    },

    updateSideCamBoost() {
        const on = document.getElementById('sideCamBoostEnabled').checked;
        const ctl = document.getElementById('sideCamBoostControls');
        if (ctl) ctl.style.display = on ? '' : 'none';
        if (on) {
            const sens = parseInt(document.getElementById('sideCamSensSlider').value);
            const activeZoneBtn = document.querySelector('#sideCamZoneBtns .btn-toggle.active');
            const zone = activeZoneBtn ? activeZoneBtn.dataset.value : 'extended';
            this._writeSideCamOverrides(sens, zone);
        } else {
            this._writeSideCamOverrides(null, null);
        }
        this.markChanged();
    },

    updateSideCamSens(value) {
        const sens = parseInt(value);
        const valEl = document.getElementById('sideCamSensValue');
        if (valEl) {
            valEl.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + sens);
            valEl.textContent = this.v2SensLabel(sens) || sens;
        }
        const activeZoneBtn = document.querySelector('#sideCamZoneBtns .btn-toggle.active');
        const zone = activeZoneBtn ? activeZoneBtn.dataset.value : 'extended';
        this._writeSideCamOverrides(sens, zone);
        this.markChanged();
    },

    setSideCamZone(zone) {
        document.querySelectorAll('#sideCamZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === zone));
        const sens = parseInt(document.getElementById('sideCamSensSlider').value);
        this._writeSideCamOverrides(sens, zone);
        this.markChanged();
    },

    updateTelegramStartPing() {
        const el = document.getElementById('v2TelegramSendStartPing');
        if (!el) return;
        this.config.telegramSendStartPing = el.checked;
        this._persistTelegramFields(['telegramSendStartPing']);
    },

    /**
     * Per-tier Telegram filter handler. Wires the three new toggles
     * (Notice / Alert / Critical) into config and persists immediately
     * — the Telegram block lives on notifications.html, which has no
     * Apply button, so we can't rely on the surveillance.html partial-save
     * path here.
     */
    updateTelegramTiers() {
        const n = document.getElementById('v2TelegramNotices');
        const a = document.getElementById('v2TelegramAlerts');
        const c = document.getElementById('v2TelegramCritical');
        if (n) this.config.telegramNotices = n.checked;
        if (a) this.config.telegramAlerts = a.checked;
        if (c) this.config.telegramCritical = c.checked;
        this._persistTelegramFields(['telegramNotices', 'telegramAlerts', 'telegramCritical']);
    },

    /**
     * Immediate save for the v2Telegram* toggles on the Notifications →
     * Telegram tab. Mirrors TelegramPrefs.save() over on
     * /api/telegram/preferences: edit lands in this.config, POST it
     * straight to /api/surveillance/config, and on success sync
     * savedConfig so the dirty diff (and any sibling Apply button on
     * surveillance.html) doesn't think this tab is still pending. On
     * failure, revert the in-memory + checkbox state so the UI doesn't
     * pretend the change was persisted.
     */
    _persistTelegramFields(fields) {
        const prev = {};
        const body = {};
        for (let i = 0; i < fields.length; i++) {
            const k = fields[i];
            prev[k] = this.savedConfig ? this.savedConfig[k] : undefined;
            body[k] = this.config[k];
        }
        const self = this;
        // Tier toggles share IDs with the surveillance.html version. Map the
        // config keys we just sent to their checkbox elements so we can
        // re-paint after the network round-trip — Chrome 58 WebView can
        // visually drop the click flip when the synchronous AndroidBridge
        // POST resolves in the same microtask as the change event, leaving
        // the slider stuck on the old position even though the underlying
        // checkbox.checked is correct. Re-asserting .checked from the
        // authoritative this.config value forces a paint pass.
        const fieldToToggleId = {
            telegramSendStartPing: 'v2TelegramSendStartPing',
            telegramNotices:       'v2TelegramNotices',
            telegramAlerts:        'v2TelegramAlerts',
            telegramCritical:      'v2TelegramCritical'
        };
        function repaintToggles() {
            for (let i = 0; i < fields.length; i++) {
                const k = fields[i];
                const id = fieldToToggleId[k];
                if (!id) continue;
                const el = document.getElementById(id);
                if (!el) continue;
                const want = !!self.config[k];
                // Toggle .checked twice — assigning the same value is a
                // no-op on most engines and won't cause a re-paint, so
                // flip-then-restore around a forced reflow on the slider
                // sibling.
                const slider = el.parentNode && el.parentNode.querySelector('.toggle-slider');
                el.checked = !want;
                if (slider) { void slider.offsetHeight; }
                el.checked = want;
            }
        }
        function safeToast(key, fallback, kind) {
            if (!(BYD.utils && BYD.utils.toast)) return;
            const localized = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key) : null;
            BYD.utils.toast(localized || fallback, kind);
        }
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
          .then(() => {
              if (self.savedConfig) {
                  for (let i = 0; i < fields.length; i++) {
                      self.savedConfig[fields[i]] = self.config[fields[i]];
                  }
              }
              self.markChanged();
              repaintToggles();
              safeToast('telegram.prefs_saved', 'Preferences saved', 'success');
          })
          .catch(() => {
              for (let i = 0; i < fields.length; i++) {
                  const k = fields[i];
                  if (prev[k] !== undefined) self.config[k] = prev[k];
              }
              repaintToggles();
              safeToast('telegram.prefs_save_failed', 'Could not save preferences', 'error');
          });
    },

    /**
     * Reflect the Telegram-bot pairing state in the tier filter UI. The
     * tier toggles + the two-stage start-ping are functionally inert when
     * the bot isn\'t paired (engine still calls TelegramNotifier, daemon
     * just answers "Owner not set" and drops the message), so we visually
     * disable them and show an inline warning so the user understands
     * why their toggles don\'t produce messages. Re-runs on every config
     * refresh (every 10s) so freshly-paired bots un-grey live without a
     * page reload.
     */
    async refreshTelegramAvailability() {
        let paired = false;
        try {
            const r = await fetch('/api/settings/telegram-status');
            if (r.ok) {
                const j = await r.json();
                paired = !!(j && j.enabled);
            }
        } catch (e) {
            // Network blip / endpoint missing on older builds — treat as
            // "unknown" and leave toggles enabled so we don\'t block users
            // on a transient failure.
            paired = true;
        }
        const group = document.getElementById('v2TelegramTierGroup');
        const warning = document.getElementById('v2TelegramTierWarning');
        const startPing = document.getElementById('v2TelegramSendStartPing');
        const tierIds = ['v2TelegramNotices', 'v2TelegramAlerts', 'v2TelegramCritical'];

        const setDisabled = (el, dis) => {
            if (!el) return;
            el.disabled = dis;
            const row = el.closest('.setting-row');
            if (row) {
                row.style.opacity = dis ? '0.55' : '';
                row.style.pointerEvents = dis ? 'none' : '';
            }
        };
        setDisabled(startPing, !paired);
        for (const id of tierIds) setDisabled(document.getElementById(id), !paired);
        if (warning) warning.style.display = paired ? 'none' : 'block';
        if (group) group.dataset.telegramPaired = paired ? '1' : '0';
    },

    updateV2Dev() {
        var heatmapEl = document.getElementById('v2MotionHeatmap');
        var filterEl = document.getElementById('v2FilterDebugLog');
        const heatmapOn = (heatmapEl && heatmapEl.checked) || false;
        const wasOn = this.config.motionHeatmap;
        this.config.motionHeatmap = heatmapOn;
        this.config.filterDebugLog = (filterEl && filterEl.checked) || false;

        // Start/stop heatmap when toggle changes
        if (heatmapOn && !wasOn) {
            this.startHeatmap();
        } else if (!heatmapOn && wasOn) {
            this.stopHeatmap();
        }

        this.markChanged();
    },

    // ==================== Motion Heatmap Overlay ====================

    _heatmapInterval: null,
    _heatmapCanvas: null,
    _heatmapWasRunning: false,

    /**
     * Start the motion heatmap overlay.
     * Creates a canvas over the live stream container and polls at 3 FPS.
     */
    startHeatmap() {
        if (this._heatmapInterval) return; // Already running

        const container = document.getElementById('videoDisplayArea');
        if (!container) {
            console.log('[Heatmap] No video display area found — skipping');
            return;
        }

        // Create canvas overlay if not exists
        if (!this._heatmapCanvas) {
            const canvas = document.createElement('canvas');
            canvas.id = 'heatmapOverlay';
            canvas.width = 1280;
            canvas.height = 960;
            canvas.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:15;';
            container.style.position = 'relative';
            container.appendChild(canvas);
            this._heatmapCanvas = canvas;
        }

        this._heatmapCanvas.style.display = 'block';

        // Poll at 3 FPS (333ms)
        this._heatmapInterval = setInterval(() => this._pollHeatmap(), 333);
        console.log('[Heatmap] Started polling at 3 FPS');
    },

    /**
     * Stop the motion heatmap overlay and clean up.
     */
    stopHeatmap() {
        if (this._heatmapInterval) {
            clearInterval(this._heatmapInterval);
            this._heatmapInterval = null;
        }

        if (this._heatmapCanvas) {
            this._heatmapCanvas.remove();
            this._heatmapCanvas = null;
        }

        console.log('[Heatmap] Stopped');
    },

    /**
     * Fetch heatmap data and draw on canvas.
     */
    async _pollHeatmap() {
        if (!this._heatmapCanvas) return;

        try {
            const resp = await fetch('/api/surveillance/heatmap');
            const data = await resp.json();
            if (data && data.quadrants) {
                // Override viewMode with client-side stream selection if available.
                // BYD.stream tracks which camera the user selected — use that
                // so the heatmap matches what's visible on screen.
                if (typeof BYD !== 'undefined' && BYD.stream && BYD.stream.currentViewMode >= 0) {
                    data.viewMode = BYD.stream.currentViewMode;
                }
                this._drawHeatmap(this._heatmapCanvas, data);
            }
        } catch (e) {
            // Surveillance not running or API error — clear the canvas
            const ctx = this._heatmapCanvas.getContext('2d');
            if (ctx) ctx.clearRect(0, 0, this._heatmapCanvas.width, this._heatmapCanvas.height);
        }
    },

    /**
     * Draw the heatmap overlay on the canvas.
     * 
     * In mosaic mode (viewMode=0), draws a 2x2 grid matching the recorder's
     * mosaic (Q0=front, Q1=right, Q2=rear, Q3=left):
     *   [0: FRONT]  [1: RIGHT]
     *   [2: REAR ]  [3: LEFT ]
     *
     * In single-camera mode (viewMode=1-4), draws only the active quadrant
     * filling the full canvas. viewMode mapping: 1=Front, 2=Right, 3=Rear, 4=Left.
     *
     * Each quadrant has gridCols x gridRows blocks with confidence values.
     * Blocks are color-coded: green (low) → yellow (medium) → red (high).
     */
    _drawHeatmap(canvas, data) {
        var ctx = canvas.getContext('2d');
        if (!ctx) return;

        var cw = canvas.width;
        var ch = canvas.height;
        ctx.clearRect(0, 0, cw, ch);

        var gridCols = data.gridCols || 10;
        var gridRows = data.gridRows || 7;

        // viewMode: 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, 5=Raw, 6=OEM Dashcam
        var viewMode = data.viewMode || 0;

        // Views 5 (raw debug) and 6 (OEM Dashcam) have no AVM
        // surveillance/heatmap pipeline behind them. Skip the overlay
        // entirely so we don't paint stale AVM quadrant data on top of
        // the raw / DVR stream — the canvas was already cleared above.
        if (viewMode === 5 || viewMode === 6) return;

        // Map viewMode to quadrant ID. Quadrant order is Q0=front, Q1=right,
        // Q2=rear, Q3=left (MotionPipelineV2.QUADRANT_NAMES) and the stream's
        // single-view modes are 1=front, 2=right, 3=rear, 4=left (the uViewMode
        // branches in GpuStreamScaler's fragment shader), so this is the identity
        // shift. It previously mapped 3→3 and 4→2, painting the LEFT camera's
        // blocks while the user was watching the REAR stream and vice versa.
        var viewModeToQuadrant = { 1: 0, 2: 1, 3: 2, 4: 3 };
        var singleQuadrant = (viewMode > 0) ? viewModeToQuadrant[viewMode] : -1;
        
        // In single-camera mode, the heatmap fills the full canvas
        // In mosaic mode, each quadrant takes a quarter
        var isSingle = singleQuadrant >= 0;
        var quadW = isSingle ? cw : cw / 2;
        var quadH = isSingle ? ch : ch / 2;
        var blockW = quadW / gridCols;
        var blockH = quadH / gridRows;

        // Mosaic layout, matching the recorder's output and the GL shader's
        // uViewMode==0 rearrange: Front=TL, Right=TR, Rear=BL, Left=BR.
        // Q2 is REAR (bottom-LEFT) and Q3 is LEFT (bottom-RIGHT) — these two were
        // transposed in both the positions and the labels, which is what made
        // left-camera motion appear as rear-camera motion on the debug heatmap.
        var quadPositions = [
            [0, 0],  // 0: front — top-left
            [1, 0],  // 1: right — top-right
            [0, 1],  // 2: rear  — bottom-left
            [1, 1]   // 3: left  — bottom-right
        ];
        var quadLabels = [
            BYD.i18n.t('surveillance.camera_front'),
            BYD.i18n.t('surveillance.camera_right'),
            BYD.i18n.t('surveillance.camera_rear'),
            BYD.i18n.t('surveillance.camera_left')
        ];
        var threatLabels = [
            '',
            BYD.i18n.t('surveillance.heatmap_threat_low'),
            BYD.i18n.t('surveillance.heatmap_threat_medium'),
            BYD.i18n.t('surveillance.heatmap_threat_high'),
            BYD.i18n.t('surveillance.heatmap_threat_critical')
        ];

        for (var qi = 0; qi < data.quadrants.length; qi++) {
            var q = data.quadrants[qi];
            
            // In single-camera mode, only draw the active quadrant
            if (isSingle && q.id !== singleQuadrant) continue;
            
            var pos = quadPositions[q.id];
            if (!pos) continue;

            // In single mode, always draw at (0,0) filling full canvas
            var qx = isSingle ? 0 : pos[0] * quadW;
            var qy = isSingle ? 0 : pos[1] * quadH;

            // Quadrant border (thin white line) — only in mosaic mode
            if (!isSingle) {
                ctx.strokeStyle = 'rgba(255, 255, 255, 0.15)';
                ctx.lineWidth = 1;
                ctx.strokeRect(qx, qy, quadW, quadH);
            }

            // Camera label (top-left of each quadrant)
            ctx.font = 'bold 14px Inter, sans-serif';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';

            // Disabled quadrant
            if (!q.enabled) {
                ctx.fillStyle = 'rgba(128, 128, 128, 0.25)';
                ctx.fillRect(qx, qy, quadW, quadH);
                ctx.fillStyle = 'rgba(255, 255, 255, 0.5)';
                ctx.fillText(BYD.i18n.t('surveillance.heatmap_camera_off', {camera: quadLabels[q.id]}), qx + 8, qy + 6);
                continue;
            }

            // Suppressed quadrant (brightness shift detected)
            if (q.suppressed) {
                ctx.fillStyle = 'rgba(59, 130, 246, 0.20)';
                ctx.fillRect(qx, qy, quadW, quadH);
                ctx.fillStyle = 'rgba(147, 197, 253, 0.8)';
                ctx.fillText(BYD.i18n.t('surveillance.heatmap_camera_light_shift', {camera: quadLabels[q.id]}), qx + 8, qy + 6);
                continue;
            }

            // Draw confidence blocks with smooth gradient
            var activeCount = 0;
            if (q.confidence && q.confidence.length > 0) {
                for (var i = 0; i < q.confidence.length; i++) {
                    var c = q.confidence[i];
                    if (c <= 0) continue;

                    activeCount++;
                    var col = i % gridCols;
                    var row = Math.floor(i / gridCols);
                    var bx = qx + col * blockW;
                    var by = qy + row * blockH;

                    // Smooth color gradient: green → yellow → red
                    var r, g, b, a;
                    if (c < 0.40) {
                        var t = c / 0.40;
                        r = Math.round(34 + (234 - 34) * t);
                        g = Math.round(197 + (179 - 197) * t);
                        b = Math.round(94 + (8 - 94) * t);
                        a = 0.25 + 0.15 * t;
                    } else {
                        var t = (c - 0.40) / 0.60;
                        r = Math.round(234 + (239 - 234) * t);
                        g = Math.round(179 - 179 * t);
                        b = Math.round(8 + (68 - 8) * t);
                        a = 0.40 + 0.25 * t;
                    }
                    ctx.fillStyle = 'rgba(' + r + ',' + g + ',' + b + ',' + a.toFixed(2) + ')';
                    ctx.fillRect(bx + 1, by + 1, blockW - 2, blockH - 2);
                }
            }

            // Camera label with active block count
            var threat = q.threatLevel || 0;
            var labelColor = threat >= 3 ? 'rgba(239, 68, 68, 0.9)' :
                             threat >= 2 ? 'rgba(234, 179, 8, 0.9)' :
                             activeCount > 0 ? 'rgba(34, 197, 94, 0.9)' :
                             'rgba(255, 255, 255, 0.5)';
            ctx.fillStyle = labelColor;
            var label = quadLabels[q.id];
            if (activeCount > 0) {
                label += '  ' + BYD.i18n.t('surveillance.heatmap_blocks', {n: activeCount});
                if (threat > 0 && threat < threatLabels.length) {
                    label += ' · ' + threatLabels[threat];
                }
            }
            var labelWidth = ctx.measureText(label).width + 12;
            ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
            ctx.fillRect(qx + 4, qy + 3, labelWidth, 20);
            ctx.fillStyle = labelColor;
            ctx.fillText(label, qx + 8, qy + 6);
        }

        // Legend (bottom-center)
        var legendY = ch - 24;
        var legendX = cw / 2 - 120;
        ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
        ctx.fillRect(legendX - 8, legendY - 4, 256, 22);
        ctx.font = '11px Inter, sans-serif';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        // Green
        ctx.fillStyle = 'rgba(34, 197, 94, 0.7)';
        ctx.fillRect(legendX, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_low'), legendX + 16, legendY + 6);
        // Yellow
        ctx.fillStyle = 'rgba(234, 179, 8, 0.7)';
        ctx.fillRect(legendX + 55, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_medium'), legendX + 71, legendY + 6);
        // Red
        ctx.fillStyle = 'rgba(239, 68, 68, 0.7)';
        ctx.fillRect(legendX + 130, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_high'), legendX + 146, legendY + 6);
        // Blue
        ctx.fillStyle = 'rgba(59, 130, 246, 0.7)';
        ctx.fillRect(legendX + 190, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_suppressed'), legendX + 206, legendY + 6);
    },

    updateV2UI() {
        // ACC-OFF mode — reflect the loaded value on the toggle group and
        // refresh the dim state of dependent cards. Default to 'smart' if
        // the server omitted the field (older daemon build).
        const accOffMode = this.config.accOffMode || 'smart';
        document.querySelectorAll('#accOffModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === accOffMode));
        this.applyAccOffModeUI();

        // Arm mode — reflect the loaded value on the toggle group and refresh
        // its hint. Default to 'lock' if the server omitted the field (older
        // daemon build).
        const armMode = this.config.armMode || 'lock';
        document.querySelectorAll('#armModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === armMode));
        this.applyArmModeUI();

        // Operating mode — reflect the loaded value on the toggle group and refresh
        // its hint. Default to 'onAndOff' if the server omitted the field (older daemon
        // build) so the switch shows the real out-of-box default.
        const operatingMode = this.config.operatingMode || 'onAndOff';
        document.querySelectorAll('#operatingModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === operatingMode));
        this.applyOperatingModeUI();

        // Keep USB powered while parked — default true when the server omits the
        // field (older daemon build) so the switch shows the real out-of-box default.
        const keepUsb = document.getElementById('survKeepUsbPower');
        if (keepUsb) keepUsb.checked = (this.config.keepUsbPowerOnAccOff !== false);
        // Reflect the "recording to Internal because USB power is off" notice.
        this.updateUsbPowerStorageNotice();

        // Low-power-while-parked toggle. Default OFF when the field is absent
        // (older config / opt-in feature) so the switch shows the real default.
        const lowPower = document.getElementById('survLowPowerMode');
        if (lowPower) lowPower.checked = (this.config.lowPowerWhileParked === true);

        // Parked cellular keep-alive. Default OFF when absent (opt-in feature).
        const mobileData = document.getElementById('survMobileDataKeepAlive');
        if (mobileData) mobileData.checked = (this.config.mobileDataKeepAlive === true);

        // Low-battery (HV SoC) cutoff slider. 0 renders as "Off".
        const socCutoff = document.getElementById('lowSocCutoffSlider');
        let socVal = parseInt(this.config.lowSocCutoffPercent, 10);
        if (!isFinite(socVal)) socVal = 10;
        if (socVal < 0) socVal = 0; if (socVal > 30) socVal = 30;
        if (socCutoff) socCutoff.value = socVal;
        const socCutoffLabel = document.getElementById('lowSocCutoffValue');
        if (socCutoffLabel) socCutoffLabel.textContent = (socVal === 0)
            ? (BYD.i18n.t('surveillance.low_soc_cutoff_off') || 'Off')
            : socVal + '%';

        // Environment preset — check if current values match the saved preset
        // If user customized sliders after selecting a preset, don't highlight any preset
        const savedPreset = this.config.environmentPreset;
        const presetValues = this.v2Presets[savedPreset];
        let presetMatches = false;
        if (presetValues) {
            presetMatches = (
                this.config.sensitivityLevel == presetValues.sensitivityLevel &&
                this.config.detectionZone === presetValues.detectionZone &&
                this.config.loiteringTime == presetValues.loiteringTime
            );
        }
        
        document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', presetMatches && btn.dataset.value === savedPreset));
        const customBtn = document.getElementById('envPresetCustom');
        if (!presetMatches) {
            if (customBtn) customBtn.classList.add('active');
        } else {
            if (customBtn) customBtn.classList.remove('active');
        }
        var _eh = document.getElementById('envPresetHint');
        if (_eh) {
            if (presetMatches) {
                _eh.setAttribute('data-i18n', 'surveillance.env_hint.' + savedPreset);
                _eh.textContent = this.v2EnvPresetHint(savedPreset) || '';
            } else {
                _eh.setAttribute('data-i18n', 'surveillance.custom_config');
                _eh.textContent = BYD.i18n.t('surveillance.custom_config');
            }
        }

        // Sensitivity
        const sensSlider = document.getElementById('v2SensitivitySlider');
        if (sensSlider) sensSlider.value = this.config.sensitivityLevel;
        const sensValue = document.getElementById('v2SensitivityValue');
        if (sensValue) {
            sensValue.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + this.config.sensitivityLevel);
            sensValue.textContent = this.v2SensLabel(this.config.sensitivityLevel) || this.config.sensitivityLevel;
        }
        var _sh = document.getElementById('v2SensitivityHint');
        if (_sh) {
            _sh.setAttribute('data-i18n', 'surveillance.v2_sens_hint.' + this.config.sensitivityLevel);
            _sh.textContent = this.v2SensHint(this.config.sensitivityLevel) || '';
        }

        // Detection zone
        document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.detectionZone));
        var _dh = document.getElementById('detectionZoneHint');
        if (_dh) {
            _dh.setAttribute('data-i18n', 'surveillance.zone_hint.' + this.config.detectionZone);
            _dh.textContent = this.v2DetectionZoneHint(this.config.detectionZone) || '';
        }

        // Loitering time
        const loiterSlider = document.getElementById('loiteringTimeSlider');
        if (loiterSlider) loiterSlider.value = this.config.loiteringTime;
        const loiterValue = document.getElementById('loiteringTimeValue');
        if (loiterValue) loiterValue.textContent = this.config.loiteringTime + 's';

        // Approach trigger (0 = Off)
        if (this.config.approachTrigger !== undefined) {
            const apSlider = document.getElementById('approachTriggerSlider');
            if (apSlider) apSlider.value = this.config.approachTrigger;
            const apValue = document.getElementById('approachTriggerValue');
            if (apValue) apValue.textContent = (this.config.approachTrigger === 0)
                ? (BYD.i18n.t('surveillance.approach_off') || 'Off')
                : this.config.approachTrigger + 's';
        }

        // Shadow filter
        const shadowSelect = document.getElementById('shadowFilterSelect');
        if (shadowSelect && this.config.shadowFilter !== undefined) {
            shadowSelect.value = this.config.shadowFilter;
        }
        const shadowHint = document.getElementById('shadowFilterHint');
        if (shadowHint && this.config.shadowFilter !== undefined) {
            shadowHint.setAttribute('data-i18n', 'surveillance.shadow_hint.' + this.config.shadowFilter);
            shadowHint.textContent = BYD.i18n.t('surveillance.shadow_hint.' + this.config.shadowFilter) || '';
        }

        // Camera toggles
        const cf = document.getElementById('v2CameraFront');
        if (cf) cf.checked = this.config.cameraFront;
        const cr = document.getElementById('v2CameraRight');
        if (cr) cr.checked = this.config.cameraRight;
        const cl = document.getElementById('v2CameraLeft');
        if (cl) cl.checked = this.config.cameraLeft;
        const cb = document.getElementById('v2CameraRear');
        if (cb) cb.checked = this.config.cameraRear;

        // Side-cam Boost — derived from quadrantOverrides[Q1] / [Q3]. We
        // treat "boost enabled" as: both side quadrants share an override.
        // If the user customized just one side via API, fall back to that
        // quadrant's value to stay consistent.
        const ov = this.config.quadrantOverrides || {};
        const q1 = ov.Q1 || {};
        const q3 = ov.Q3 || {};
        const boostOn = !!(q1.sensitivityLevel || q3.sensitivityLevel || q1.detectionZone || q3.detectionZone);
        const boostBox = document.getElementById('sideCamBoostEnabled');
        const boostCtl = document.getElementById('sideCamBoostControls');
        if (boostBox) boostBox.checked = boostOn;
        if (boostCtl) boostCtl.style.display = boostOn ? '' : 'none';
        const sideSens = (q1.sensitivityLevel || q3.sensitivityLevel || 4);
        const sideZone = (q1.detectionZone || q3.detectionZone || 'extended');
        const sSlider = document.getElementById('sideCamSensSlider');
        if (sSlider) sSlider.value = sideSens;
        const sValue = document.getElementById('sideCamSensValue');
        if (sValue) {
            sValue.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + sideSens);
            sValue.textContent = this.v2SensLabel(sideSens) || sideSens;
        }
        document.querySelectorAll('#sideCamZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === sideZone));

        // Developer toggles
        const hm = document.getElementById('v2MotionHeatmap');
        if (hm) hm.checked = this.config.motionHeatmap;
        const fd = document.getElementById('v2FilterDebugLog');
        if (fd) fd.checked = this.config.filterDebugLog;

        // Paint a checkbox + force the adjacent .toggle-slider to re-evaluate
        // its `:checked + .toggle-slider` style. Plain `el.checked = true`
        // sets the property correctly but Chrome 58 WebView may not
        // invalidate the sibling slider's style until the element is
        // interacted with — slider stays in OFF position even though the
        // underlying checkbox is checked. Flip-reflow-restore forces the
        // style recompute. Harmless on every other engine.
        const setToggle = (el, want) => {
            if (!el) return;
            const slider = el.parentNode && el.parentNode.querySelector('.toggle-slider');
            el.checked = !want;
            if (slider) { void slider.offsetHeight; }
            el.checked = !!want;
        };

        // Discard non-actor recordings (detection tab) — user-facing toggle,
        // so use setToggle for the Chrome-58 slider repaint (the developer
        // toggles above can stay plain).
        setToggle(document.getElementById('v2MotionSalience'),
                  !!this.config.motionSalienceEnabled);
        setToggle(document.getElementById('v2DiscardEmptyMotion'),
                  !!this.config.discardEmptyBrightMotionEvents);
        setToggle(document.getElementById('v2DiscardEmptyMotionNight'),
                  !!this.config.discardEmptyMotionAtNight);
        this._syncNightDiscardEnabled();

        // Telegram start-ping opt-in
        setToggle(document.getElementById('v2TelegramSendStartPing'),
                  !!this.config.telegramSendStartPing);

        // Per-tier Telegram filter — null-coalesce to the documented defaults
        // so configs saved before these fields existed render correctly
        // (notices off, alerts on, critical on).
        setToggle(document.getElementById('v2TelegramNotices'),
                  this.config.telegramNotices === true);
        setToggle(document.getElementById('v2TelegramAlerts'),
                  this.config.telegramAlerts !== false);
        setToggle(document.getElementById('v2TelegramCritical'),
                  this.config.telegramCritical !== false);
        
        // Object detection checkboxes
        const dp = document.getElementById('detectPerson');
        if (dp) dp.checked = this.config.detectPerson;
        const dc = document.getElementById('detectCar');
        if (dc) dc.checked = this.config.detectCar;
        const db = document.getElementById('detectBike');
        if (db) db.checked = this.config.detectBike;
        const da = document.getElementById('detectAnimal');
        if (da) da.checked = this.config.detectAnimal;
        this.updateCheckboxStyles();
    },

    /**
     * Per-tab field map — which `this.config` keys belong to which tab.
     * Apply uses this to build a PARTIAL body that only writes fields the
     * user could have edited on the active tab. Avoids clobbering settings
     * the user hasn't seen on this page-load.
     *
     * The keys here MUST match exactly what SurveillanceApiHandler reads on
     * POST (configJson.has(...) calls). Audited 2026-05-20 against the
     * Java handler. The "_storage_endpoint" sentinel signals a separate POST
     * to /api/settings/storage instead of the surveillance config endpoint.
     *
     * Side-cam boost (sideCamBoost*) is intentionally absent — those
     * controls write into `quadrantOverrides` rather than dedicated keys,
     * so the partial body only needs to ship `quadrantOverrides`.
     *
     * Safe Locations and ROI editor have their OWN endpoints
     * (/api/surveillance/safe-locations, save() on roi-schedule.js posts
     * directly with its own partial body) and are NOT routed through this
     * Apply flow. Listing them here would be a no-op since `this.config`
     * doesn't carry those keys.
     */
    _tabFieldMap: {
        general: [
            'enabled',
            'scheduleEnabled', 'scheduleRules',
            'deterrentAction', 'deterrentCooldownSeconds'
            // Screen deterrent fields (screenDeterrentEnabled / Duration /
            // Message / image) are intentionally OUT of the tab map: they
            // save immediately on change via updateScreenDeterrent + the
            // image upload endpoint, so they never participate in the
            // Apply-button dirty diff. Listing them here would cause Apply
            // to enable the moment the user toggles, even though the value
            // was already persisted.
        ],
        detection: [
            'environmentPreset',
            'sensitivityLevel', 'detectionZone',
            'loiteringTime', 'approachTrigger', 'shadowFilter',
            'cameraFront', 'cameraRight', 'cameraLeft', 'cameraRear',
            'quadrantOverrides',
            'detectPerson', 'detectCar', 'detectBike', 'detectAnimal',
            'aiConfidence', 'minObjectSize',
            'flashImmunity',
            // Folded in from the (removed) Advanced tab — these motion-
            // detection diagnostics belong with detection.
            'motionHeatmap', 'filterDebugLog',
            'discardEmptyBrightMotionEvents', 'discardEmptyMotionAtNight',
            'motionSalienceEnabled'
        ],
        recording: [
            // Backend reads these EXACT names — preRecordSeconds (no "ing").
            // This page's quality/fps pickers drive the ACC-off SURVEILLANCE
            // knobs (surveillanceQuality + surveillanceCameraFps), INDEPENDENT
            // of the dashcam page's ACC-on recordingQuality/cameraFps. The
            // applySettings() recording branch POSTs them to
            // /api/settings/quality, which persists + live-applies to a running
            // parked recording with no encoder reinit.
            // recordingCodec is SHARED (device-compat, not a per-mode knob) and
            // stays on the shared key. rectifyStrength + segmentDurationMinutes
            // are likewise shared with the dashcam page (one slider each).
            'preRecordSeconds', 'postRecordSeconds',
            'surveillanceQuality', 'recordingCodec', 'surveillanceCameraFps',
            'rectifyStrength',
            // Shared clip duration — same recording.segmentDurationMinutes key
            // the dashcam page writes; saving here also changes that axis.
            'segmentDurationMinutes'
        ],
        oem: [
            // OEM tab is a sentinel — applySettings() branches off to
            // /api/oem-dashcam/config when the active tab is `oem`. The
            // dirty diff still has to compare _something_, so we list the
            // mirrored config field that setOemSurveillanceMode() updates
            // on `this.config`.
            'oemSurveillanceMode'
        ],
        storage: [
            // Sentinel — branches the Apply flow to /api/settings/storage.
            // The diff loop in _tabDirty() skips this sentinel, so it doesn't
            // participate in the per-tab dirty check; the two fields below
            // are what actually drive Apply enablement on the storage tab.
            '_storage_endpoint',
            'surveillanceLimitMb', 'surveillanceStorageType'
        ]
    },

    /**
     * Look up the tab the user is currently viewing. The bottom-tabs script
     * (shared/app-tabs.js) persists the active tab to localStorage; we read
     * that so a partial save targets the visible tab even after the user
     * switches across tabs without reloading.
     */
    _activeTabId: function () {
        try {
            var path = window.location.pathname || '';
            var idx = path.lastIndexOf('/');
            var page = idx >= 0 ? path.substring(idx + 1) : (path || 'index');
            var stored = window.localStorage.getItem('ot-active-tab-' + page);
            if (stored) return stored;
        } catch (e) {}
        // Fall back to the visible bottom-tab pill.
        var visible = document.querySelector('.bottom-tab.is-active');
        if (visible) return visible.getAttribute('data-tab-target') || 'general';
        return 'general';
    },

    async applySettings() {
        const btn = document.getElementById('btnApply');
        const origText = btn.innerHTML;
        btn.innerHTML = BYD.i18n.t('surveillance.saving');
        btn.disabled = true;

        try {
            const activeTab = this._activeTabId();
            const fields = this._tabFieldMap[activeTab] || [];
            const isStorageTab = fields.indexOf('_storage_endpoint') !== -1;

            let storageData = {};

            if (isStorageTab) {
                // Storage tab — only write storage fields via /api/settings/storage,
                // mirroring the legacy carve-out. Don't touch /api/surveillance/config
                // so detection sensitivity / schedule etc. stay untouched.
                const storageResp = await fetch('/api/settings/storage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        surveillanceLimitMb: this.config.surveillanceLimitMb,
                        surveillanceStorageType: this.config.surveillanceStorageType
                    })
                });
                if (!storageResp.ok) throw new Error('Storage save failed: ' + storageResp.status);
                storageData = await storageResp.json();
                // HTTP 200 with {success:false} = the requested storage-TYPE
                // change was rejected (target volume unavailable). The server
                // handles the type FIRST and returns before the limit, so
                // nothing was committed — revert both optimistic fields to the
                // baseline, re-render, and throw so the catch shows an error
                // instead of baking the rejected value into savedConfig.
                if (storageData && storageData.success === false) {
                    if (this.savedConfig) {
                        this.config.surveillanceStorageType = this.savedConfig.surveillanceStorageType;
                        this.config.surveillanceLimitMb = this.savedConfig.surveillanceLimitMb;
                    }
                    this.updateStorageLimitUI();
                    this.updateStorageTypeUI();
                    throw new Error(storageData.error || 'storage change rejected');
                }
                // Re-sync config to the daemon's committed (possibly clamped)
                // value so the savedConfig snapshot below baselines off the true
                // persisted number — see the matching re-sync in recording.js.
                if (typeof storageData.surveillanceLimitMb === 'number') {
                    this.config.surveillanceLimitMb = storageData.surveillanceLimitMb;
                }
                if (storageData.surveillanceStorageType) {
                    this.config.surveillanceStorageType = storageData.surveillanceStorageType;
                }
                // CDR cleanup card on this tab self-saves — toggleCdrCleanup()
                // and the slider onchange handlers POST to
                // /api/storage/external/config directly on each change. So
                // pressing Apply on the Storage tab only needs to write the
                // surveillance storage limit + storage-type pair above; the
                // CDR retention sliders are already persisted live.
            } else if (activeTab === 'oem') {
                // OEM tab — write the chosen OEM surveillance mode through
                // the dedicated endpoint. recording.html owns the OEM
                // telemetry burn-in toggle; this page only flips the mode.
                let oemErr = null;
                try {
                    const oemResp = await fetch('/api/oem-dashcam/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ surveillanceMode: this.config.oemSurveillanceMode || 'off' })
                    });
                    if (!oemResp.ok) throw new Error('OEM save failed: ' + oemResp.status);
                    const oemData = await oemResp.json();
                    if (oemData && oemData.success === false) {
                        throw new Error(oemData.error || 'OEM save rejected');
                    }
                } catch (e) {
                    oemErr = e;
                }
                // Always re-hydrate from the server — success or failure.
                // On success this paints the new state via the normal poll;
                // on failure it re-syncs the radio + savedConfig back to
                // the server's actual mode so the UI doesn't lie about a
                // value the daemon refused to accept.
                if (!oemErr && (this.config.oemSurveillanceMode || 'off') !== 'off') {
                    this._pollOemDashcamUntilSettled();
                } else {
                    try { await this.loadOemDashcam(); } catch (_) {}
                }
                if (oemErr) throw oemErr;
            } else if (activeTab === 'recording') {
                // Recording tab — quality/codec/fps belong to the shared
                // /api/settings/quality endpoint; pre/post-record buffers
                // remain on the surveillance config writer. Sequential
                // POSTs with per-endpoint savedConfig commit so a partial
                // failure (e.g. /api/settings/quality 500 while
                // /api/surveillance/config would have succeeded) leaves
                // the dirty diff accurate: the half that DID land migrates
                // into savedConfig; the half that failed stays dirty so
                // the Apply button correctly reflects "still has unsaved
                // changes". Without this, parallel posts could promote
                // savedConfig optimistically and leave divergent state
                // with no recovery short of a full reload.
                const qualityKeys = ['surveillanceQuality', 'recordingCodec', 'surveillanceCameraFps',
                    'segmentDurationMinutes'];
                const survKeys = ['preRecordSeconds', 'postRecordSeconds'];
                const committed = {};
                let firstError = null;

                try {
                    const qResp = await fetch('/api/settings/quality', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            // ACC-off surveillance knobs — server persists to
                            // recording.surveillanceQuality / camera.surveillanceTargetFps
                            // and live-applies to a running parked recording.
                            surveillanceQuality: this.config.surveillanceQuality,
                            surveillanceCameraFps: this.config.surveillanceCameraFps,
                            // Shared with the dashcam page (device-compat + clip length).
                            recordingCodec: this.config.recordingCodec,
                            segmentDurationMinutes: this.config.segmentDurationMinutes
                        })
                    });
                    if (!qResp.ok) throw new Error('Quality save failed: ' + qResp.status);
                    qualityKeys.forEach(k => { committed[k] = true; });
                } catch (e) {
                    firstError = e;
                }
                try {
                    const sResp = await fetch('/api/surveillance/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            preRecordSeconds: this.config.preRecordSeconds,
                            postRecordSeconds: this.config.postRecordSeconds
                        })
                    });
                    if (!sResp.ok) throw new Error('Config save failed: ' + sResp.status);
                    survKeys.forEach(k => { committed[k] = true; });
                } catch (e) {
                    if (firstError == null) firstError = e;
                }
                // rectifyStrength lives in unified-config recording.* (shared
                // with the dashcam page slider). Persist via /api/settings/unified
                // so the live listener fires and any reload of the recording
                // page reads the same value.
                try {
                    var rs = (typeof this.config.rectifyStrength === 'number')
                        ? this.config.rectifyStrength : 0;
                    if (rs < 0) rs = 0; if (rs > 100) rs = 100;
                    const uResp = await fetch('/api/settings/unified', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            section: 'recording',
                            data: { rectifyStrength: rs }
                        })
                    });
                    if (!uResp.ok) throw new Error('Rectify save failed: ' + uResp.status);
                    committed['rectifyStrength'] = true;
                } catch (e) {
                    if (firstError == null) firstError = e;
                }

                // Promote only the keys whose POST succeeded. Stamp the
                // saved baseline directly so subsequent _tabDirty() runs
                // see the failed-half still dirty. The blanket
                // `savedConfig = JSON.parse(JSON.stringify(config))` below
                // would clobber the dirty signal — skip it for this branch
                // and short-circuit by re-throwing if we had any failure.
                if (!this.savedConfig) this.savedConfig = {};
                Object.keys(committed).forEach(k => {
                    this.savedConfig[k] = this.config[k];
                });
                if (qualityKeys.some(k => committed[k])) this.refetchQualityOptions();
                if (firstError) throw firstError;
                // Mark this branch as having handled savedConfig so the
                // generic "savedConfig = clone(config)" below doesn't
                // overwrite our partial commit.
                this._skipSavedConfigSnapshot = true;
            } else {
                // Build a partial body containing only the active tab's DIRTY
                // fields (config differs from the savedConfig baseline). The
                // daemon's surveillance config endpoint is a partial-merge
                // writer, so omitted keys retain their prior values. Sending
                // only dirty keys (not the whole tab) prevents a stale client
                // — or a silent mis-tap on an unrelated control — from riding
                // along with an Apply and clobbering flags the user never
                // touched (on-car incident: an Apply carried all four detect*
                // classes false alongside a preset change, disabling AI for
                // 14 minutes). Fall back to sending the field when there is no
                // baseline to diff against (savedConfig missing the key).
                const partial = {};
                for (let i = 0; i < fields.length; i++) {
                    const key = fields[i];
                    if (this.config[key] === undefined) continue;
                    const baseline = this.savedConfig ? this.savedConfig[key] : undefined;
                    if (baseline !== undefined &&
                        JSON.stringify(this.config[key]) === JSON.stringify(baseline)) {
                        continue; // unchanged — let the daemon keep its value
                    }
                    partial[key] = this.config[key];
                }
                let configResp = await fetch('/api/surveillance/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(partial)
                });
                if (!configResp.ok) throw new Error('Config save failed: ' + configResp.status);
                let configData = null;
                try { configData = await configResp.json(); } catch (_) {}
                // Daemon guard: a save whose RESULT would disable every object
                // class is rejected with code=all_classes_off (HTTP 200,
                // success:false). Ask the user to confirm via the themed
                // dialog; on confirm, retry the same body with the explicit
                // override flag. Fail SAFE when the modal helper is missing
                // (core.js not loaded): do NOT auto-confirm — surface the
                // rejection, mirroring the keep-USB consent pattern.
                if (configData && configData.success === false &&
                        configData.code === 'all_classes_off') {
                    const t = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t.bind(BYD.i18n) : null;
                    let confirmed = false;
                    if (BYD.utils && BYD.utils.confirmDialog) {
                        confirmed = await BYD.utils.confirmDialog({
                            title: (t && t('surveillance.confirm_disable_all_classes_title'))
                                || 'Disable all object classes?',
                            body: (t && t('surveillance.confirm_disable_all_classes'))
                                || 'AI detection turns off entirely: events only trigger on raw motion and are rate-limited. Real events may be missed.',
                            confirmLabel: (t && t('surveillance.confirm_disable_all_classes_ok'))
                                || 'Disable anyway',
                            cancelLabel: (t && t('common.cancel')) || 'Cancel',
                            danger: true
                        });
                    }
                    if (!confirmed) {
                        throw new Error(configData.error || 'all object classes would be disabled');
                    }
                    partial.confirmDisableAllClasses = true;
                    configResp = await fetch('/api/surveillance/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(partial)
                    });
                    if (!configResp.ok) throw new Error('Config save failed: ' + configResp.status);
                    try { configData = await configResp.json(); } catch (_) {}
                }
                if (configData && configData.success === false) {
                    throw new Error(configData.error || 'config save rejected');
                }
            }

            // Recording-tab branch handles per-key savedConfig promotion
            // already; skip the blanket snapshot so a partial failure
            // doesn't get overwritten with a clean copy.
            if (!this._skipSavedConfigSnapshot) {
                this.savedConfig = JSON.parse(JSON.stringify(this.config));
            }
            this._skipSavedConfigSnapshot = false;
            this.hasUnsavedChanges = false;
            this.markChanged();
            // Re-render so the "saved → pending" arrow disappears now that
            // savedConfig caught up to config.
            this.renderActiveEstimate();

            // Refresh storage stats after save (cleanup may have run)
            setTimeout(() => this.loadStorageStats(), 1000);

            let msg = BYD.i18n.t('surveillance.settings_applied');
            if (storageData.cleanup && storageData.cleanup.surveillanceToDelete) {
                msg = BYD.i18n.t('surveillance.settings_applied_deleting', {files: storageData.cleanup.surveillanceFilesEstimate, size: storageData.cleanup.surveillanceToDelete});
            }

            btn.innerHTML = BYD.i18n.t('surveillance.saved_check');
            setTimeout(() => { btn.innerHTML = origText; }, 1500);

            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');
        } catch (e) {
            console.error('applySettings error:', e);
            btn.innerHTML = origText;
            btn.disabled = false;
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('surveillance.save_failed', {error: e.message || BYD.i18n.t('errors.generic')}), 'error');
        }
    },

    /**
     * Lightweight init for pages that only mount the Telegram delivery
     * controls (notifications.html). Loads the surveillance config, paints
     * the Telegram checkboxes via updateUI(), and refreshes the bot-paired
     * availability state. Every DOM read inside updateUI() is null-guarded
     * so missing controls (sensitivity slider, env preset, ROI editor, …)
     * are skipped without warnings.
     */
    async initTelegramOnly() {
        await this.loadConfig();
        // Snapshot savedConfig so _persistTelegramFields' dirty-tracking +
        // failure-revert path has something to compare against. Without
        // this snapshot, prev[k] is undefined for every field on the
        // notifications page, which makes the failure path unable to
        // revert to a known-good value (it skips the revert entirely
        // because of `if (prev[k] !== undefined)`). Mirrors what the
        // full init() path on surveillance.html does.
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
        this.refreshTelegramAvailability();
        // Re-poll the pairing state when the user comes back to this tab —
        // a fresh bot pair done in the Telegram daemon page should un-grey
        // the toggles without a manual reload.
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') {
                this.refreshTelegramAvailability();
            }
        });
    },

    /**
     * Lightweight init for pages that only need the heatmap overlay (e.g., live view).
     * Loads config and starts heatmap if enabled, without touching surveillance UI elements.
     */
    async initHeatmapOnly() {
        await this.loadConfig();

        // Auto-start heatmap if enabled in config and video display area exists
        if (this.config.motionHeatmap) {
            this.startHeatmap();
        }

        // Handle page visibility for heatmap
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden' && this._heatmapInterval) {
                this.stopHeatmap();
                this._heatmapWasRunning = true;
            }
            if (document.visibilityState === 'visible' && this._heatmapWasRunning) {
                this._heatmapWasRunning = false;
                if (this.config.motionHeatmap) {
                    this.startHeatmap();
                }
            }
        });

        // Periodically check if heatmap config changed (e.g., toggled from surveillance page)
        setInterval(async () => {
            try {
                const resp = await fetch('/api/surveillance/config');
                const data = await resp.json();
                if (data.success && data.config) {
                    const newHeatmap = data.config.motionHeatmap || false;
                    if (newHeatmap && !this._heatmapInterval) {
                        this.config.motionHeatmap = true;
                        this.startHeatmap();
                    } else if (!newHeatmap && this._heatmapInterval) {
                        this.config.motionHeatmap = false;
                        this.stopHeatmap();
                    }
                }
            } catch (e) { /* ignore */ }
        }, 5000);
    },

    /**
     * Update surveillance-specific UI from status (called by core.js)
     */
    updateFromStatus(status) {
        const survState = document.getElementById('survState');
        if (survState) {
            if (status.safeZoneSuppressed || status.inSafeZone) {
                survState.textContent = status.safeZoneName
                    ? BYD.i18n.t('surveillance.state_safe_zone_named', {name: status.safeZoneName})
                    : BYD.i18n.t('surveillance.state_safe_zone');
                survState.style.color = 'var(--brand-secondary)';
            } else {
                survState.textContent = status.gpuSurveillance
                    ? BYD.i18n.t('surveillance.state_active')
                    : BYD.i18n.t('common.idle');
                survState.style.color = '';
            }
        }
        
        // Don't touch the enabled toggle from status — gpuSurveillance is runtime state,
        // not the user's preference. Surveillance can be enabled (preference=true) but not
        // active (gpuSurveillance=false) when ACC is ON. The toggle reflects the preference,
        // which is loaded from the config API, not from status.
    },

    // ── Deterrent Action ────────────────────────────────────────────────

    updateDeterrent(value) {
        this.config.deterrentAction = value;
        // Save immediately (deterrent is independent of the Apply button)
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ deterrentAction: value })
        }).then(() => {
            this.updateDeterrentUI();
        }).catch(e => console.warn('Failed to save deterrent:', e));
    },

    updateDeterrentUI() {
        const action = this.config.deterrentAction || 'silent';
        const select = document.getElementById('deterrentAction');
        if (select) select.value = action;

        const badge = document.getElementById('deterrentBadge');
        if (badge) {
            const labels = {
                silent: BYD.i18n.t('surveillance.deterrent_silent_badge'),
                flash_lights: BYD.i18n.t('surveillance.deterrent_flash_badge'),
                find_car: BYD.i18n.t('surveillance.deterrent_horn_badge')
            };
            badge.textContent = labels[action] || BYD.i18n.t('surveillance.deterrent_silent_badge');
            badge.className = 'status-badge ' + (action === 'silent' ? 'inactive' : 'active');
        }

        // Show warning if cloud action selected but not configured
        const warning = document.getElementById('deterrentWarning');
        if (warning) {
            const needsCloud = action !== 'silent';
            const configured = this.config.bydCloudEnabled;
            warning.style.display = (needsCloud && !configured) ? 'block' : 'none';
        }

        this.updateScreenDeterrentUI();
    },

    // ── Screen Deterrent ───────────────────────────────────────────────────

    /**
     * Save a screen-deterrent field. Toggling enabled or duration applies
     * immediately (no Apply button), matching the cloud deterrent's UX.
     * 'message' is debounced via queueScreenDeterrentMessage to avoid one
     * POST per keystroke.
     */
    updateScreenDeterrent: function(field, value) {
        var body = {};
        var configKey = '';
        var previousValue;
        if (field === 'enabled') {
            configKey = 'screenDeterrentEnabled';
            previousValue = this.config[configKey];
            this.config.screenDeterrentEnabled = !!value;
            body.screenDeterrentEnabled = !!value;
        } else if (field === 'duration') {
            var v = parseInt(value, 10);
            if (!isFinite(v) || v < 3) v = 3;
            if (v > 30) v = 30;
            configKey = 'screenDeterrentDurationSeconds';
            previousValue = this.config[configKey];
            this.config.screenDeterrentDurationSeconds = v;
            body.screenDeterrentDurationSeconds = v;
            var label = document.getElementById('screenDeterrentDurationValue');
            if (label) label.textContent = v + 's';
        } else if (field === 'message') {
            configKey = 'screenDeterrentMessage';
            previousValue = this.config[configKey];
            this.config.screenDeterrentMessage =
                String(value || '').substring(0, 120);
            body.screenDeterrentMessage = this.config.screenDeterrentMessage;
        } else {
            return;
        }

        var self = this;
        var fieldSequences = this._screenDeterrentFieldSequences
            || (this._screenDeterrentFieldSequences = {});
        var sequence = (fieldSequences[configKey] || 0) + 1;
        fieldSequences[configKey] = sequence;
        var previous = this._screenDeterrentSaveChain || Promise.resolve();
        var request = previous.catch(function() {}).then(function() {
            return fetch('/api/surveillance/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
        }).then(function(resp) {
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            return resp.json();
        }).then(function(data) {
            if (!data || !data.success) {
                throw new Error((data && data.error) || 'Save failed');
            }
            return data;
        });
        this._screenDeterrentSaveChain = request;

        request.then(function() {
            if (self.savedConfig) {
                self.savedConfig[configKey] = body[configKey];
            }
            if (sequence !== self._screenDeterrentFieldSequences[configKey]) return;
            self.updateScreenDeterrentUI();
            if (self.markChanged) self.markChanged();
        }).catch(function(e) {
            console.warn('Failed to save screen deterrent:', e);
            if (sequence !== self._screenDeterrentFieldSequences[configKey]) return;
            self.config[configKey] = self.savedConfig
                    && Object.prototype.hasOwnProperty.call(
                        self.savedConfig, configKey)
                ? self.savedConfig[configKey] : previousValue;
            self.updateScreenDeterrentUI();
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(
                    (e && e.message) || 'Could not save screen deterrent settings',
                    'error');
            }
        });
    },

    /**
     * Update only the slider label as the user drags. The actual save is
     * triggered by onchange (drag end) so we don't fire one POST per
     * pointer-move event — a one-second drag would otherwise invalidate UCM
     * cache 30 times across all daemon processes.
     */
    previewScreenDeterrentDuration: function(value) {
        var v = parseInt(value, 10);
        if (!isFinite(v)) return;
        var label = document.getElementById('screenDeterrentDurationValue');
        if (label) label.textContent = v + 's';
    },

    /**
     * Low-battery (HV SoC) cutoff — label-only preview while dragging. The real
     * save fires on `onchange` (drag end) via updateLowSocCutoff so a drag
     * doesn't POST per pointer-move. 0 renders as "Off".
     */
    previewLowSocCutoff: function(value) {
        var v = parseInt(value, 10);
        if (!isFinite(v)) return;
        var label = document.getElementById('lowSocCutoffValue');
        if (label) label.textContent = (v === 0)
            ? (BYD.i18n.t('surveillance.low_soc_cutoff_off') || 'Off')
            : v + '%';
    },

    /**
     * Persist the low-battery cutoff. Saves to power.lowSocCutoffPercent (the
     * key SocCutoffMonitor reads), NOT the surveillance section — the handler
     * routes it. Immediate-save + savedConfig mirror so it stays clean against
     * the Apply-button dirty diff, mirroring updateScreenDeterrent('duration').
     */
    updateLowSocCutoff: function(value) {
        var v = parseInt(value, 10);
        if (!isFinite(v) || v < 0) v = 0;
        if (v > 30) v = 30;
        this.config.lowSocCutoffPercent = v;
        if (this.savedConfig) this.savedConfig.lowSocCutoffPercent = v;
        var label = document.getElementById('lowSocCutoffValue');
        if (label) label.textContent = (v === 0)
            ? (BYD.i18n.t('surveillance.low_soc_cutoff_off') || 'Off')
            : v + '%';
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ lowSocCutoffPercent: v })
        }).then(function() {
            if (BYD.surveillance.markChanged) BYD.surveillance.markChanged();
        }).catch(function(e) {
            console.warn('Failed to save low-SoC cutoff:', e);
        });
    },

    queueScreenDeterrentMessage: function(value) {
        // Debounce text input — fire after 600ms idle.
        if (this._screenDeterrentMsgTimer) {
            clearTimeout(this._screenDeterrentMsgTimer);
        }
        var self = this;
        this._screenDeterrentMsgTimer = setTimeout(function() {
            self.updateScreenDeterrent('message', value);
        }, 600);
    },

    /**
     * Upload-fail UX. We use the toast system for transient errors (network,
     * server, parse) so the user can keep interacting with the page; the
     * blocking native alert() leaked the loopback origin into a system
     * dialog and broke the dark Material surface.
     */
    _uploadToastError: function(reasonKey, reasonFallback) {
        if (!(BYD.utils && BYD.utils.toast)) return;
        var t = BYD.i18n && BYD.i18n.t ? BYD.i18n.t.bind(BYD.i18n) : null;
        var headline = (t && t('surveillance.screen_deterrent_upload_failed')) || 'Upload failed';
        var detail = (t && reasonKey && t(reasonKey)) || reasonFallback || '';
        BYD.utils.toast(detail ? headline + ' — ' + detail : headline, 'error', 4500);
    },

    DETERRENT_MAX_IMAGE_BYTES: 8 * 1024 * 1024,
    DETERRENT_MAX_VIDEO_BYTES: 10 * 1024 * 1024,

    _isDeterrentVideo: function(file) {
        if (!file) return false;
        return (file.type || '').indexOf('video/') === 0
            || /\.mp4$/i.test(file.name || '');
    },

    uploadScreenDeterrentImage: function(file) {
        // Always reset the <input> value so the same file can be re-selected
        // later. Without this, picking the same image twice silently no-ops
        // because the change event doesn't fire on identical values.
        try {
            var fileInput = document.getElementById('screenDeterrentFile');
            if (fileInput) fileInput.value = '';
        } catch (_) {}

        if (!file) {
            console.warn('[deterrent] upload: no file');
            return;
        }
        console.log('[deterrent] upload start:', file.name, file.size, file.type);

        var isVideo = this._isDeterrentVideo(file);
        var maxBytes = isVideo
            ? this.DETERRENT_MAX_VIDEO_BYTES : this.DETERRENT_MAX_IMAGE_BYTES;
        if (file.size > maxBytes) {
            this._uploadToastError(
                isVideo
                    ? 'surveillance.screen_deterrent_video_too_large'
                    : 'surveillance.screen_deterrent_too_large',
                isVideo ? 'Video too large (max 10 MB)' : 'Image too large (max 8 MB)');
            return;
        }
        if (file.size === 0) {
            this._uploadToastError('surveillance.screen_deterrent_empty_file',
                                   'The selected file is empty');
            return;
        }

        var self = this;
        var reader = new FileReader();

        reader.onerror = function(e) {
            console.error('[deterrent] FileReader error:', e);
            self._uploadToastError('surveillance.screen_deterrent_read_failed',
                                   'Could not read the file');
        };

        reader.onload = function(e) {
            // result is "data:image/<type>;base64,...."
            var dataUrl = e.target.result;
            if (!dataUrl) {
                console.error('[deterrent] FileReader returned empty result');
                self._uploadToastError('surveillance.screen_deterrent_read_failed',
                                       'Could not read the file');
                return;
            }
            console.log('[deterrent] read OK, posting...', dataUrl.length, 'chars');

            // Use fetch(), NOT XMLHttpRequest. Reason: inside the in-app
            // WebView, the WebViewClient.shouldInterceptRequest path
            // (WebViewFragment.kt) only fires HTTP GETs against the daemon —
            // Android's WebResourceRequest API doesn't expose the request
            // body to the intercept callback, so an XHR POST gets silently
            // converted to a GET, the daemon returns the existing image
            // bytes (or 404), and the upload code parses them as a failed
            // JSON response → "Upload failed" toast.
            //
            // fetch() POSTs go through the INJECT_JS patch in
            // WebViewFragment.kt which routes them via
            // AndroidBridge.httpRequest — that bridge writes the body to
            // outputStream cleanly and returns the daemon's JSON.
            // External-tunnel browsers don't have AndroidBridge; their
            // fetch() goes over the network unmodified, also fine.
            fetch('/api/surveillance/screen-deterrent/image', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filename: file.name, dataBase64: dataUrl })
            }).then(function (resp) {
                console.log('[deterrent] upload response status:', resp.status);
                if (!resp.ok) {
                    throw new Error('HTTP ' + resp.status);
                }
                return resp.json();
            }).then(function (data) {
                if (data && data.success) {
                    console.log('[deterrent] upload success:', data.path);
                    self.config.screenDeterrentImagePath = data.path;
                    self.config.screenDeterrentHasImage = true;
                    self.config.screenDeterrentAssetType = data.assetType
                        || (/\.mp4$/i.test(data.path || '') ? 'video' : 'image');
                    self.updateScreenDeterrentUI();
                    if (BYD.utils && BYD.utils.toast) {
                        var key = self.config.screenDeterrentAssetType === 'video'
                            ? 'surveillance.screen_deterrent_video_upload_ok'
                            : 'surveillance.screen_deterrent_upload_ok';
                        var tt = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key) : null;
                        BYD.utils.toast(tt || 'Asset uploaded', 'success');
                    }
                } else {
                    self._uploadToastError(null, (data && data.error) || '');
                }
            }).catch(function (err) {
                console.error('[deterrent] upload failed:', err && err.message);
                var msg = err && err.message ? err.message : '';
                if (msg.indexOf('HTTP ') === 0) {
                    self._uploadToastError(null, 'Server returned ' + msg);
                } else {
                    self._uploadToastError('surveillance.screen_deterrent_network_error',
                                           msg || 'Network error');
                }
            });
        };

        try {
            reader.readAsDataURL(file);
        } catch (err) {
            console.error('[deterrent] readAsDataURL threw:', err);
            this._uploadToastError(null, err && err.message);
        }
    },

    clearScreenDeterrentImage: async function() {
        // Destructive action — themed confirm dialog matches the rest of
        // the page (white system-modal popups looked out of place against
        // the dark Material surface and leaked the loopback origin into
        // the title bar).
        var t = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t.bind(BYD.i18n) : null;
        if (BYD.utils && BYD.utils.confirmDialog) {
            var ok = await BYD.utils.confirmDialog({
                title: (t && t('surveillance.screen_deterrent_remove_title')) || 'Remove asset?',
                body: (t && t('surveillance.screen_deterrent_remove_body'))
                    || 'The deterrent will fall back to the default red screen.',
                confirmLabel: (t && t('surveillance.screen_deterrent_remove')) || 'Remove asset',
                cancelLabel: (t && t('common.cancel')) || 'Cancel',
                danger: true
            });
            if (!ok) return;
        } else if (typeof confirm === 'function') {
            // Pre-modal-helper fallback (older bundles / very early init).
            var legacy = (t && t('surveillance.screen_deterrent_remove_confirm'))
                || 'Remove the uploaded asset? This cannot be undone.';
            if (!confirm(legacy)) return;
        }
        var self = this;
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ clearScreenDeterrentImage: true })
        }).then(function(resp) {
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            return resp.json();
        }).then(function(data) {
            if (!data || !data.success) {
                throw new Error((data && data.error) || 'Clear failed');
            }
            self.config.screenDeterrentImagePath = '';
            self.config.screenDeterrentHasImage = false;
            self.config.screenDeterrentAssetType = '';
            self.updateScreenDeterrentUI();
        }).catch(function(e) {
            console.warn('Failed to clear screen deterrent image:', e);
            self._uploadToastError(null,
                e && e.message ? e.message : 'Could not remove the asset');
        });
    },

    updateScreenDeterrentUI: function() {
        var enabled = !!this.config.screenDeterrentEnabled;
        var dur = parseInt(this.config.screenDeterrentDurationSeconds, 10);
        if (!isFinite(dur)) dur = 8;
        var msg = this.config.screenDeterrentMessage || '';
        var hasImage = !!this.config.screenDeterrentHasImage;
        var imagePath = this.config.screenDeterrentImagePath || '';
        var assetType = this.config.screenDeterrentAssetType
            || (/\.mp4$/i.test(imagePath) ? 'video' : (hasImage ? 'image' : ''));
        var isVideo = assetType === 'video';

        var cb = document.getElementById('screenDeterrentEnabled');
        if (cb) cb.checked = enabled;

        var slider = document.getElementById('screenDeterrentDurationSlider');
        if (slider) slider.value = dur;
        var label = document.getElementById('screenDeterrentDurationValue');
        if (label) label.textContent = dur + 's';

        var msgInput = document.getElementById('screenDeterrentMessage');
        if (msgInput && msgInput.value !== msg) msgInput.value = msg;
        // Mutex: when a custom image is set, the message field is ignored at
        // render time. Visually disable it AND show an explicit override
        // banner inside the "Custom content" group so the user can't miss it.
        if (msgInput) {
            msgInput.disabled = hasImage;
            msgInput.style.opacity = hasImage ? '0.5' : '';
        }
        var overrideBanner = document.getElementById('screenDeterrentOverrideBanner');
        if (overrideBanner) {
            overrideBanner.style.display = hasImage ? '' : 'none';
        }

        var badge = document.getElementById('screenDeterrentBadge');
        if (badge) {
            badge.textContent = enabled
                ? BYD.i18n.t('surveillance.screen_deterrent_on_badge')
                : BYD.i18n.t('surveillance.screen_deterrent_off_badge');
            badge.className = 'status-badge ' + (enabled ? 'active' : 'inactive');
        }

        var clearBtn = document.getElementById('screenDeterrentClearBtn');
        if (clearBtn) clearBtn.style.display = hasImage ? '' : 'none';

        // When an asset is already uploaded, the Upload button becomes
        // "Replace image" so the user understands picking a new file
        // overwrites the current asset (the latest upload is the only one
        // we keep — server cleans up siblings on every upload).
        var uploadLabel = document.getElementById('screenDeterrentUploadLabel');
        if (uploadLabel && BYD.i18n && BYD.i18n.t) {
            uploadLabel.textContent = hasImage
                ? BYD.i18n.t('surveillance.screen_deterrent_replace')
                : BYD.i18n.t('surveillance.screen_deterrent_upload');
        }

        var previewLabel = document.getElementById('screenDeterrentPreviewLabel');
        if (previewLabel && BYD.i18n && BYD.i18n.t) {
            previewLabel.textContent = isVideo
                ? BYD.i18n.t('surveillance.screen_deterrent_preview_label_video')
                : BYD.i18n.t('surveillance.screen_deterrent_preview_label');
        }

        var preview = document.getElementById('screenDeterrentPreview');
        var previewImg = document.getElementById('screenDeterrentPreviewImg');
        var previewVideo = document.getElementById('screenDeterrentPreviewVideo');
        var previewRequestId = (this._deterrentPreviewRequestId || 0) + 1;
        this._deterrentPreviewRequestId = previewRequestId;
        if (preview && previewImg) {
            previewImg.onload = null;
            previewImg.onerror = null;
            if (hasImage && imagePath) {
                // Load preview via fetch → Blob → URL.createObjectURL.
                //
                // Why not <img src="/api/...">: the BYD head-unit WebView
                // (Chrome 58) silently fails to render an <img> whose URL
                // routes through shouldInterceptRequest with a streamed
                // binary response — same firmware quirk that breaks
                // autoplay video without a Range hop.
                //
                // Why not XHR + arraybuffer + base64 data URL: btoa on a
                // 200KB+ string from a String.fromCharCode loop is O(n²)
                // and the resulting data: URL crosses Chrome 58's
                // attribute-length budget in some builds, leaving <img>
                // with a "broken image" glyph.
                //
                // fetch().then(res => res.blob()) → createObjectURL is the
                // pattern events.js uses for recording thumbnails on the
                // same WebView and is known good. revoke the previous URL
                // before assigning a new one to avoid leaking blob handles
                // across reloads.
                var self = this;
                previewImg.removeAttribute('src');
                previewImg.style.display = 'none';
                if (previewVideo) {
                    try { previewVideo.pause(); } catch (_) {}
                    previewVideo.onloadedmetadata = null;
                    previewVideo.onerror = null;
                    previewVideo.onvolumechange = null;
                    previewVideo.removeAttribute('src');
                    try { previewVideo.load(); } catch (_) {}
                    previewVideo.style.display = 'none';
                }
                if (this._lastDeterrentBlobUrl) {
                    try { URL.revokeObjectURL(this._lastDeterrentBlobUrl); } catch (_) {}
                    this._lastDeterrentBlobUrl = null;
                }
                fetch('/api/surveillance/screen-deterrent/image?t=' + Date.now(), {
                    cache: 'no-store',
                    credentials: 'same-origin'
                }).then(function (res) {
                    if (!res.ok) throw new Error('HTTP ' + res.status);
                    return res.blob();
                }).then(function (blob) {
                    if (!blob || blob.size === 0) throw new Error('empty blob');
                    if (previewRequestId !== self._deterrentPreviewRequestId) return;
                    var url = URL.createObjectURL(blob);
                    self._lastDeterrentBlobUrl = url;
                    if (isVideo && previewVideo) {
                        previewVideo.muted = true;
                        previewVideo.defaultMuted = true;
                        previewVideo.onvolumechange = function () {
                            if (!previewVideo.muted) previewVideo.muted = true;
                        };
                        previewVideo.style.display = '';
                        previewVideo.onloadedmetadata = function () {
                            if (previewRequestId !== self._deterrentPreviewRequestId) return;
                            preview.style.display = '';
                            var play = previewVideo.play();
                            if (play && play.catch) play.catch(function () {});
                        };
                        previewVideo.onerror = function () {
                            if (previewRequestId !== self._deterrentPreviewRequestId) return;
                            console.warn('[deterrent] preview <video> failed to decode blob');
                            preview.style.display = 'none';
                        };
                        previewVideo.src = url;
                        previewVideo.load();
                        return;
                    }
                    previewImg.style.display = '';
                    previewImg.onload = function () {
                        if (previewRequestId !== self._deterrentPreviewRequestId) return;
                        preview.style.display = '';
                    };
                    previewImg.onerror = function () {
                        if (previewRequestId !== self._deterrentPreviewRequestId) return;
                        console.warn('[deterrent] preview <img> failed to decode blob');
                        preview.style.display = 'none';
                    };
                    previewImg.src = url;
                }).catch(function (err) {
                    if (previewRequestId !== self._deterrentPreviewRequestId) return;
                    console.warn('[deterrent] preview load failed:', err && err.message);
                    preview.style.display = 'none';
                });
            } else {
                if (this._lastDeterrentBlobUrl) {
                    try { URL.revokeObjectURL(this._lastDeterrentBlobUrl); } catch (_) {}
                    this._lastDeterrentBlobUrl = null;
                }
                preview.style.display = 'none';
                previewImg.removeAttribute('src');
                previewImg.style.display = '';
                if (previewVideo) {
                    try { previewVideo.pause(); } catch (_) {}
                    previewVideo.onloadedmetadata = null;
                    previewVideo.onerror = null;
                    previewVideo.onvolumechange = null;
                    previewVideo.removeAttribute('src');
                    try { previewVideo.load(); } catch (_) {}
                    previewVideo.style.display = 'none';
                }
            }
        }
    },

    downloadAndSetTheme: function(url, filename, btn) {
        var origText = btn.innerHTML;
        btn.disabled = true;
        btn.innerHTML = '<span data-i18n="surveillance.downloading">Downloading...</span>';

        var self = this;
        fetch(url)
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.blob();
            })
            .then(function(blob) {
                var file = new File([blob], filename, { type: blob.type || 'image/gif' });
                self.uploadScreenDeterrentImage(file);
                btn.innerHTML = '<span data-i18n="surveillance.applied">Applied!</span>';
                setTimeout(function() {
                    btn.innerHTML = origText;
                    btn.disabled = false;
                }, 2000);
            })
            .catch(function(err) {
                console.error('[deterrent] theme download failed:', err);
                btn.innerHTML = '<span data-i18n="surveillance.failed">Failed</span>';
                setTimeout(function() {
                    btn.innerHTML = origText;
                    btn.disabled = false;
                }, 2000);
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to download theme', 'error');
            });
    },

    testScreenDeterrent: function() {
        fetch('/api/surveillance/screen-deterrent/test', { method: 'POST' })
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function(data) {
                if (!BYD.utils || !BYD.utils.toast) return;
                if (data && data.success) {
                    BYD.utils.toast('Deterrent triggered', 'success');
                } else {
                    BYD.utils.toast(
                        (data && data.error) || 'Failed to trigger deterrent', 'error');
                }
            })
            .catch(function(err) {
                console.error('[deterrent] test failed:', err);
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to trigger deterrent', 'error');
            });
    },

    // ==================== Geocoding (Place Tagging) ====================
    //
    // Surveillance side of the per-flow geocoding split. Toggles the
    // "surveillance" sub-section only — dashcam tagging lives on
    // recording.html. Custom Nominatim URL is shared between both pages
    // (advanced sub-section); whichever page writes last wins.

    async loadGeocoding() {
        try {
            const resp = await fetch('/api/settings/geocoding');
            const data = await resp.json();
            if (!data.success) return;
            const surCfg = data.surveillance || {};
            const advCfg = data.advanced || {};
            const swEnabled = document.getElementById('survGeocodingEnabled');
            const swOnline = document.getElementById('survGeocodingOnline');
            const inputUrl = document.getElementById('survGeocodingCustomUrl');
            if (swEnabled) swEnabled.checked = !!surCfg.enabled;
            if (swOnline) {
                swOnline.checked = !!surCfg.allowOnline;
                swOnline.disabled = !surCfg.enabled;
            }
            if (inputUrl) {
                inputUrl.value = advCfg.customNominatimBase || '';
                inputUrl.disabled = !surCfg.enabled;
            }
        } catch (e) {
            console.warn('Failed to load surveillance geocoding state:', e);
        }
    },

    async _postGeocoding(delta) {
        try {
            const resp = await fetch('/api/settings/geocoding', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(delta)
            });
            const data = await resp.json();
            return data && data.success ? data : null;
        } catch (e) {
            console.warn('Geocoding POST failed:', e);
            return null;
        }
    },

    async toggleGeocodingEnabled() {
        const sw = document.getElementById('survGeocodingEnabled');
        const swOnline = document.getElementById('survGeocodingOnline');
        const inputUrl = document.getElementById('survGeocodingCustomUrl');
        if (!sw) return;
        const enabled = sw.checked;
        const result = await this._postGeocoding({ surveillance: { enabled } });
        if (result) {
            const sur = result.surveillance || {};
            sw.checked = !!sur.enabled;
            if (swOnline) swOnline.disabled = !sur.enabled;
            if (inputUrl) inputUrl.disabled = !sur.enabled;
            if (BYD.utils && BYD.utils.toast) {
                const key = sur.enabled
                    ? 'surveillance.geocoding_enabled_toast'
                    : 'surveillance.geocoding_disabled_toast';
                BYD.utils.toast(BYD.i18n.t(key), 'success');
            }
        } else {
            sw.checked = !enabled;
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('surveillance.geocoding_update_failed'), 'error');
            }
        }
    },

    async toggleGeocodingOnline() {
        const sw = document.getElementById('survGeocodingOnline');
        if (!sw) return;
        const allowOnline = sw.checked;
        const result = await this._postGeocoding({ surveillance: { allowOnline } });
        if (result && result.surveillance) {
            sw.checked = !!result.surveillance.allowOnline;
        } else {
            sw.checked = !allowOnline;
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('surveillance.geocoding_update_failed'), 'error');
            }
        }
    },

    async saveGeocodingCustomUrl() {
        const input = document.getElementById('survGeocodingCustomUrl');
        if (!input) return;
        const url = (input.value || '').trim();
        const result = await this._postGeocoding({
            advanced: { customNominatimBase: url }
        });
        if (result && result.advanced) {
            input.value = result.advanced.customNominatimBase || '';
        }
    },

    // ==================== OEM Dashcam tab ====================
    //
    // Surveillance.html has a dedicated "OEM Dashcam" tab with the
    // surveillanceMode picker (Off | Continuous | Smart), status badge,
    // and native DVR control. The burn-in telemetry toggle lives only
    // on recording.html (it stamps speed/GPS/timestamp into dvr_*.mp4
    // for active dashcam recording, not parked surveillance clips).
    // Both pages use the same i18n keys under "oem_dashcam.*" — the
    // only difference is which mode field is written (recordingMode
    // vs surveillanceMode) and which one drives Apply on each page.

    async loadOemDashcam() {
        // Token-based dedup — see recording.js mirror.
        const myToken = (this._oemLoadToken || 0) + 1;
        this._oemLoadToken = myToken;
        const cardForBusy = document.getElementById('oemDashcamMainCard');
        const selectorForBusy = cardForBusy ? cardForBusy.querySelector('.mode-selector') : null;
        if (selectorForBusy) {
            selectorForBusy.setAttribute('aria-busy', 'true');
            selectorForBusy.setAttribute('data-hydrating', 'true');
        }
        // Track id-unset early-return so the finally doesn't clobber
        // the badge's hidden state — see recording.js mirror.
        let runIsIdUnset = false;
        try {
            const qres = await fetch('/api/settings/quality');
            const qdata = await qres.json();
            if (this._oemLoadToken !== myToken) return;   // superseded
            const oem = qdata && qdata.oemDashcam;
            const card = document.getElementById('oemDashcamMainCard');
            if (!card) return;
            const idUnset = !oem
                || typeof oem.oemDashcamCameraId !== 'number'
                || oem.oemDashcamCameraId < 0;
            // Cached on `this` so the parallel _renderOemNativeDvrCard()
            // race-in (loadOemNativeDvr's fetch resolves AFTER this run
            // hides the card) and the loadOemDashcam catch path can
            // both observe the latest id-unset state.
            this._oemIdUnset = idUnset;
            const idUnsetRow = document.getElementById('oemDashcamIdUnsetRow');
            if (idUnsetRow) idUnsetRow.style.display = idUnset ? '' : 'none';
            const modeRow = document.getElementById('oemSurvModeRow');
            const modeSelector = card.querySelector('.mode-selector');
            const statusRow = document.getElementById('oemPipelineStatusRow');
            [modeRow, modeSelector, statusRow].forEach(el => {
                if (el) el.style.display = idUnset ? 'none' : '';
            });
            // Hide the native DVR card when there's no OEM id configured
            // (disable-factory-DVR is meaningless without an OEM pipeline
            // to take over).
            const oemNativeDvrCard = document.getElementById('oemNativeDvrCard');
            if (oemNativeDvrCard && idUnset) oemNativeDvrCard.style.display = 'none';
            const idUnsetBadge = document.getElementById('oemPipelineBadge');
            if (idUnsetBadge) idUnsetBadge.style.display = idUnset ? 'none' : '';
            if (idUnset) { runIsIdUnset = true; return; }

            // Telemetry burn-in lives only on recording.html now, so we
            // pull just the OEM config here. Single fetch keeps first-paint
            // latency lower than the prior parallel pair.
            let sdata = {};
            try {
                const r = await fetch('/api/oem-dashcam/config');
                sdata = await r.json();
            } catch (_) {}
            if (this._oemLoadToken !== myToken) return;   // superseded
            let mode = (sdata && sdata.surveillanceMode) ? String(sdata.surveillanceMode).toLowerCase() : 'off';
            if (mode !== 'off' && mode !== 'continuous' && mode !== 'smart') mode = 'off';
            // Mirror onto this.config so the dirty diff has a stable key
            // to compare against. The savedConfig snapshot is updated too
            // so a fresh load doesn't immediately mark the OEM tab dirty.
            this.config.oemSurveillanceMode = mode;
            if (this.savedConfig) this.savedConfig.oemSurveillanceMode = mode;
            document.querySelectorAll('input[name="oemSurveillanceMode"]').forEach(function (r) {
                r.checked = (r.value === mode);
                const c = r.closest('.mode-option').querySelector('.mode-card');
                if (c) c.classList.toggle('mode-card-active', r.checked);
            });
            // Hydrated — drop the dim/busy state.
            const selector = card.querySelector('.mode-selector');
            if (selector) {
                selector.removeAttribute('aria-busy');
                selector.removeAttribute('data-hydrating');
            }

            const statusEl = document.getElementById('oemPipelineStatus');
            const statusHint = document.getElementById('oemPipelineStatusHint');
            const badge = document.getElementById('oemPipelineBadge');
            const off = mode === 'off';
            const running = !!(sdata && sdata.pipelineRunning);
            const recording = !!(sdata && sdata.recording);
            // Off mode masks the error state — see recording.js mirror.
            // Two distinct error sources: lastStartError (pipeline failed
            // to come up) and lastWriteError (disk writer aborted mid-clip,
            // typically SD unmount). Either one paints the badge red so
            // the user doesn't see "Recording" while the muxer is dead.
            const startErr = !!(sdata && sdata.lastStartError && !running && !recording && !off);
            const writeErr = !!(sdata && sdata.lastWriteError && !off);
            const errored = startErr || writeErr;
            const errorMsg = (sdata && sdata.lastWriteError) || (sdata && sdata.lastStartError) || '';
            if (badge) {
                // Mutually-exclusive class state — see recording.js mirror.
                const isActive = !off && (running || recording);
                const isInactive = !errored && !isActive;
                badge.classList.toggle('errored', errored);
                badge.classList.toggle('active', isActive);
                badge.classList.toggle('inactive', isInactive);
                // Mirror data-i18n to the live key — see recording.js
                // mirror. Without this, a language switch resets the
                // badge to whatever static "status_idle" was on the
                // markup at page-load, regardless of true state.
                const badgeKey = errored ? 'oem_dashcam.status_error'
                    : off ? 'oem_dashcam.status_idle'
                    : recording ? 'oem_dashcam.status_recording'
                    : running ? 'oem_dashcam.status_armed'
                    : 'oem_dashcam.status_starting';
                badge.setAttribute('data-i18n', badgeKey);
                badge.textContent = BYD.i18n.t(badgeKey);
                // Reveal post-hydration. Use opacity (not visibility) so
                // the badge stays in the a11y tree before hydration.
                badge.style.opacity = '';
            }
            if (statusEl) {
                if (errored) {
                    // Concatenated string — strip data-i18n so a
                    // language switch doesn't blow away the appended
                    // dynamic suffix. See recording.js mirror.
                    const prefixKey = writeErr
                        ? 'oem_dashcam.write_error_prefix'
                        : 'oem_dashcam.start_error_prefix';
                    statusEl.removeAttribute('data-i18n');
                    statusEl.textContent = BYD.i18n.t(prefixKey) + errorMsg;
                    if (errorMsg !== this._lastShownOemError) {
                        this._lastShownOemError = errorMsg;
                        if (BYD.utils && BYD.utils.toast) BYD.utils.toast(statusEl.textContent, 'error');
                    }
                } else {
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
                        ? 'oem_dashcam.status_hint_continuous_surveillance'
                        : 'oem_dashcam.status_hint_smart_surveillance');
                statusHint.setAttribute('data-i18n', hintKey);
                statusHint.textContent = BYD.i18n.t(hintKey);
            }
            // See recording.js mirror — mark hydrated so transient
            // catch-path clobbers can't regress the badge to Idle.
            this._oemEverHydrated = true;
        } catch (e) {
            console.warn('Failed to load OEM surveillance mode:', e);
            if (this._oemLoadToken === myToken && !this._oemEverHydrated) {
                const b = document.getElementById('oemPipelineBadge');
                if (b) {
                    b.classList.remove('errored', 'active');
                    b.classList.add('inactive');
                    // BYD.i18n is guaranteed loaded by init order.
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
            // Only the winner clears badge state — see recording.js mirror.
            if (this._oemLoadToken === myToken) {
                const _b = document.getElementById('oemPipelineBadge');
                if (_b) {
                    _b.style.opacity = '';
                    // Don't resurrect the badge when this run was an
                    // id-unset early-return, OR when the cached
                    // id-unset gate fired in any prior run that the
                    // current call didn't reach (fetch failed before
                    // updating runIsIdUnset). See recording.js mirror.
                    if (!runIsIdUnset && this._oemIdUnset !== true) _b.style.display = '';
                }
            }
        }
    },

    _pollOemDashcamUntilSettled() {
        const token = (this._oemPollToken || 0) + 1;
        this._oemPollToken = token;
        const delays = [500, 1000, 2000, 3000, 4000, 5000, 5000, 5000];
        let prevSerialized = null;
        const tick = async (i) => {
            if (this._oemPollToken !== token) return;
            if (i >= delays.length) {
                // Hard ceiling — see recording.js mirror.
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
                // Post-fetch token check — supersedes prevents a stale
                // tick's terminal branch overwriting fresh state.
                if (this._oemPollToken !== token) return;
                if (!d || !d.success) {
                    if (d && d.starting === false) {
                        // Permanent rejection — see recording.js mirror.
                        await this.loadOemDashcam();
                        return;
                    }
                    return tick(i + 1);
                }
                if (d.pipelineRunning || d.lastStartError) {
                    await this.loadOemDashcam();
                    return;
                }
                // Refresh on snapshot change — see recording.js mirror.
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

    setOemSurveillanceMode(mode) {
        if (mode !== 'off' && mode !== 'continuous' && mode !== 'smart') return;
        // Repaint highlights immediately so the click feels instant; the
        // actual /api/oem-dashcam/config POST waits for Apply.
        this.config.oemSurveillanceMode = mode;
        document.querySelectorAll('input[name="oemSurveillanceMode"]').forEach(function (r) {
            r.checked = (r.value === mode);
            const c = r.closest('.mode-option').querySelector('.mode-card');
            if (c) c.classList.toggle('mode-card-active', r.value === mode);
        });
        this.markChanged();
    },

    // ==================== Native DVR (com.byd.cdr) ====================

    _renderOemNativeDvrCard(state) {
        const card = document.getElementById('oemNativeDvrCard');
        const label = document.getElementById('oemNativeDvrStatusLabel');
        const btn = document.getElementById('oemNativeDvrToggleBtn');
        if (!card || !label || !btn) return;
        if (state === 'not_installed') {
            card.style.display = 'none';
            return;
        }
        // If the OEM run has already hidden the card because the camera
        // id isn't configured, don't resurrect it from a parallel
        // /api/oem-dashcam/native-dvr/status load that came back later.
        if (this._oemIdUnset !== true) {
            card.style.display = '';
        }
        if (state === 'disabled') {
            label.setAttribute('data-i18n', 'oem_dashcam.native_dvr_status_disabled');
            label.textContent = BYD.i18n.t('oem_dashcam.native_dvr_status_disabled');
            btn.setAttribute('data-i18n', 'oem_dashcam.native_dvr_enable');
            btn.textContent = BYD.i18n.t('oem_dashcam.native_dvr_enable');
            btn.classList.remove('btn-danger');
            btn.classList.add('btn-primary');
            btn.dataset.action = 'enable';
        } else {
            label.setAttribute('data-i18n', 'oem_dashcam.native_dvr_status_enabled');
            label.textContent = BYD.i18n.t('oem_dashcam.native_dvr_status_enabled');
            btn.setAttribute('data-i18n', 'oem_dashcam.native_dvr_disable');
            btn.textContent = BYD.i18n.t('oem_dashcam.native_dvr_disable');
            btn.classList.remove('btn-primary');
            btn.classList.add('btn-danger');
            btn.dataset.action = 'disable';
        }
    },

    async loadOemNativeDvr() {
        try {
            const resp = await fetch('/api/oem-dashcam/native-dvr/status');
            const data = await resp.json();
            if (data && data.success) {
                this._renderOemNativeDvrCard(data.state || 'enabled');
            }
        } catch (e) {
            console.warn('Failed to load native DVR state:', e);
        }
    },

    async toggleOemNativeDvr() {
        const btn = document.getElementById('oemNativeDvrToggleBtn');
        if (!btn) return;
        const action = btn.dataset.action === 'enable' ? 'enable' : 'disable';
        const url = '/api/oem-dashcam/native-dvr/' + action;
        btn.disabled = true;
        try {
            const resp = await fetch(url, { method: 'POST' });
            const data = await resp.json();
            const newState = (data && data.state) || 'enabled';
            this._renderOemNativeDvrCard(newState);
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

    // ==================== Sentry Recording Layout ====================
    //
    // Sentry's OWN composition layout, independent of the dashcam recording
    // layout on the recording page. Standard = 2x2 360 mosaic (default).
    // Dashcam = forward road view on top with the 360 left/rear/right cameras
    // along the bottom. Saves immediately (not via the Apply button), mirroring
    // the dashcam layout card in recording.js. Hits
    // /api/settings/surveillance-layout and reuses the recording.layout_* i18n
    // strings (already translated in every language).

    async loadSurveillanceLayout() {
        try {
            const resp = await fetch('/api/settings/surveillance-layout');
            const data = await resp.json();
            if (data.success) {
                this._applySurveillanceLayoutButtons(data.layout || 'standard');

                const wsToggle = document.getElementById('survUseWindshield');
                if (wsToggle) {
                    wsToggle.checked = data.dashcamUseWindshield || false;
                    wsToggle.disabled = !data.windshieldAvailable;
                }

                const infoLine = document.getElementById('survWindshieldCameraInfo');
                if (infoLine) {
                    if (!data.windshieldAvailable) {
                        infoLine.textContent = BYD.i18n.t('recording.layout_windshield_unavailable');
                        infoLine.style.display = 'block';
                    } else {
                        infoLine.style.display = 'none';
                    }
                }

                this._updateSurveillanceWindshieldVisibility(data.layout || 'standard');
            }
        } catch (e) {
            console.warn('Failed to load surveillance layout:', e);
        }
    },

    _updateSurveillanceWindshieldVisibility(layout) {
        const subSetting = document.getElementById('survWindshieldRow');
        if (subSetting) {
            subSetting.style.display = layout === 'dashcam' ? 'flex' : 'none';
        }
    },

    _applySurveillanceLayoutButtons(layout) {
        const group = document.getElementById('survLayoutBtns');
        if (!group) return;
        group.querySelectorAll('.btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === layout));
    },

    async setSurveillanceLayout(layout) {
        this._applySurveillanceLayoutButtons(layout);
        this._updateSurveillanceWindshieldVisibility(layout);
        await this._saveSurveillanceLayout();
    },

    async toggleSurveillanceWindshield() {
        await this._saveSurveillanceLayout();
    },

    async _saveSurveillanceLayout() {
        const group = document.getElementById('survLayoutBtns');
        const active = group ? group.querySelector('.btn-toggle.active') : null;
        const layout = active ? active.dataset.value : 'standard';

        const wsToggle = document.getElementById('survUseWindshield');
        const dashcamUseWindshield = wsToggle ? wsToggle.checked : false;

        try {
            const resp = await fetch('/api/settings/surveillance-layout', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ layout, dashcamUseWindshield })
            });
            const data = await resp.json();
            if (data.success) {
                this._applySurveillanceLayoutButtons(data.layout);
                this._updateSurveillanceWindshieldVisibility(data.layout);

                if (wsToggle) {
                    wsToggle.checked = data.dashcamUseWindshield;
                    wsToggle.disabled = !data.windshieldAvailable;
                }

                if (BYD.utils && BYD.utils.toast) {
                    const key = data.layout === 'dashcam' ? 'recording.layout_dashcam_toast' : 'recording.layout_standard_toast';
                    BYD.utils.toast(BYD.i18n.t(key), 'success');
                }
            } else if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('recording.layout_update_failed'), 'error');
            }
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.layout_update_failed'), 'error');
        }
    },

    // ==================== Surveillance Telemetry Overlay ====================
    // ACC-off flow. Own master toggle + field selection, never shared with the
    // ACC-on recording overlay. Self-contained here because recording.js isn't
    // loaded on this page.

    _telemetryCatalog: null,
    _telemetryFields: { accOn: [], surveillance: [], oemDashcam: [] },

    async loadSurvTelemetryOverlay() {
        try {
            const resp = await fetch('/api/settings/telemetry-overlay');
            const data = await resp.json();
            if (!data.success) return;
            const toggle = document.getElementById('survTelemetryOverlayEnabled');
            if (toggle) toggle.checked = !!data.surveillanceEnabled;
            this._telemetryCatalog = data.fieldCatalog || null;
            if (data.fields) this._telemetryFields = data.fields;
            this.renderSurvTelemetryFields();
            this.updateSurvTelemetryFieldsVisibility(!!(toggle && toggle.checked));
        } catch (e) {
            console.warn('Failed to load surveillance telemetry overlay:', e);
        }
    },

    survTelemetryFieldLabel(key) {
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

    renderSurvTelemetryFields() {
        const grid = document.getElementById('telemetryFieldsSurveillanceGrid');
        if (!grid || !this._telemetryCatalog) return;
        const selected = new Set(this._telemetryFields.surveillance || []);
        grid.innerHTML = '';
        this._telemetryCatalog.forEach(f => {
            const label = document.createElement('label');
            label.className = 'checkbox-item' + (selected.has(f.key) ? ' active' : '');
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.id = 'telField_surveillance_' + f.key;
            cb.checked = selected.has(f.key);
            cb.addEventListener('change', () => {
                label.classList.toggle('active', cb.checked);
                this.toggleSurvTelemetryField();
            });
            const span = document.createElement('span');
            span.textContent = this.survTelemetryFieldLabel(f.key);
            label.appendChild(cb);
            label.appendChild(span);
            grid.appendChild(label);
        });
    },

    updateSurvTelemetryFieldsVisibility(visible) {
        const wrap = document.getElementById('telemetryFieldsSurveillance');
        if (wrap) wrap.style.display = visible ? '' : 'none';
    },

    async toggleSurvTelemetryField() {
        const grid = document.getElementById('telemetryFieldsSurveillanceGrid');
        const keys = [];
        if (grid) grid.querySelectorAll('input[type=checkbox]').forEach(cb => {
            if (cb.checked) keys.push(cb.id.replace('telField_surveillance_', ''));
        });
        this._telemetryFields.surveillance = keys;
        try {
            const resp = await fetch('/api/settings/telemetry-overlay', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ fields: { surveillance: keys } })
            });
            const data = await resp.json();
            if (data.success && data.fields) this._telemetryFields = data.fields;
        } catch (e) {
            console.warn('Failed to update surveillance telemetry fields:', e);
        }
    },

    async toggleSurvTelemetryOverlay() {
        const toggle = document.getElementById('survTelemetryOverlayEnabled');
        if (!toggle) return;
        const enabled = toggle.checked;
        try {
            const resp = await fetch('/api/settings/telemetry-overlay', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ surveillanceEnabled: enabled })
            });
            const data = await resp.json();
            if (data.success) {
                toggle.checked = !!data.surveillanceEnabled;
                this.updateSurvTelemetryFieldsVisibility(!!data.surveillanceEnabled);
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(data.surveillanceEnabled
                        ? BYD.i18n.t('recording.telemetry_overlay_enabled')
                        : BYD.i18n.t('recording.telemetry_overlay_disabled'), 'success');
                }
            } else {
                toggle.checked = !enabled;
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.overlay_update_failed'), 'error');
            }
        } catch (e) {
            toggle.checked = !enabled;
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.overlay_update_failed'), 'error');
        }
    }
};

// Alias for backward compatibility
window.SurvSettings = BYD.surveillance;

// ── BYD Cloud Account Setup ─────────────────────────────────────────────

window.BydCloud = {
    isConfigured: false,
    // Region key → existing surveillance.byd_region_* i18n key. The "no" entry
    // is BYD's own region key for Middle East / Africa (legacy from when the
    // region first launched on the -no.byd.auto host); it is NOT Norway, which
    // maps to the "eu" node. The english fallback labels here are only used
    // before the i18n catalog has loaded.
    regionLabels: {
        eu:   { i18n: 'surveillance.byd_region_eu',   en: 'Europe' },
        sg:   { i18n: 'surveillance.byd_region_sg',   en: 'Singapore / APAC' },
        au:   { i18n: 'surveillance.byd_region_au',   en: 'Australia' },
        br:   { i18n: 'surveillance.byd_region_br',   en: 'Brazil' },
        jp:   { i18n: 'surveillance.byd_region_jp',   en: 'Japan' },
        uz:   { i18n: 'surveillance.byd_region_uz',   en: 'Uzbekistan' },
        no:   { i18n: 'surveillance.byd_region_mena', en: 'Middle East / Africa' },
        mx:   { i18n: 'surveillance.byd_region_mx',   en: 'Mexico / Latin America' },
        id:   { i18n: 'surveillance.byd_region_id',   en: 'Indonesia' },
        tr:   { i18n: 'surveillance.byd_region_tr',   en: 'Turkey' },
        kr:   { i18n: 'surveillance.byd_region_kr',   en: 'Korea' },
        in:   { i18n: 'surveillance.byd_region_in',   en: 'India' },
        vn:   { i18n: 'surveillance.byd_region_vn',   en: 'Vietnam' },
        sa:   { i18n: 'surveillance.byd_region_sa',   en: 'Saudi Arabia' },
        om:   { i18n: 'surveillance.byd_region_om',   en: 'Oman' },
        kz:   { i18n: 'surveillance.byd_region_kz',   en: 'Kazakhstan' },
        cn:   { i18n: 'surveillance.byd_region_cn',   en: 'China (mainland)' }
    },
    defaultCountriesByRegion: {
        eu: 'GB', sg: 'SG', au: 'AU', br: 'BR', jp: 'JP', uz: 'UZ',
        no: 'AE', mx: 'MX', id: 'ID', tr: 'TR', kr: 'KR', in: 'IN',
        vn: 'VN', sa: 'SA', om: 'OM', kz: 'KZ', cn: 'CN'
    },
    countries: [
        { name: 'Albania', code: 'AL', language: 'en', region: 'eu' },
        { name: 'Argentina', code: 'AR', language: 'es', region: 'mx' },
        { name: 'Australia', code: 'AU', language: 'en', region: 'au' },
        { name: 'Austria', code: 'AT', language: 'de', region: 'eu' },
        { name: 'Bahrain', code: 'BH', language: 'ar', region: 'no' },
        { name: 'Bangladesh', code: 'BD', language: 'en', region: 'sg' },
        { name: 'Belgium', code: 'BE', language: 'en', region: 'eu' },
        { name: 'Bhutan', code: 'BT', language: 'en', region: 'sg' },
        { name: 'Bolivia', code: 'BO', language: 'es', region: 'mx' },
        { name: 'Bosnia and Herzegovina', code: 'BA', language: 'en', region: 'eu' },
        { name: 'Brazil', code: 'BR', language: 'pt', region: 'br' },
        { name: 'Brunei', code: 'BN', language: 'en', region: 'sg' },
        { name: 'Bulgaria', code: 'BG', language: 'en', region: 'eu' },
        { name: 'Cambodia', code: 'KH', language: 'en', region: 'sg' },
        { name: 'Chile', code: 'CL', language: 'es', region: 'mx' },
        { name: 'China', code: 'CN', language: 'zh', region: 'cn' },
        { name: 'Colombia', code: 'CO', language: 'es', region: 'mx' },
        { name: 'Costa Rica', code: 'CR', language: 'es', region: 'mx' },
        { name: 'Croatia', code: 'HR', language: 'en', region: 'eu' },
        { name: 'Cyprus', code: 'CY', language: 'en', region: 'eu' },
        { name: 'Czech Republic', code: 'CZ', language: 'en', region: 'eu' },
        { name: 'Denmark', code: 'DK', language: 'en', region: 'eu' },
        { name: 'Dominican Republic', code: 'DO', language: 'es', region: 'mx' },
        { name: 'Ecuador', code: 'EC', language: 'es', region: 'mx' },
        { name: 'Egypt', code: 'EG', language: 'ar', region: 'no' },
        { name: 'El Salvador', code: 'SV', language: 'es', region: 'mx' },
        { name: 'Estonia', code: 'EE', language: 'en', region: 'eu' },
        { name: 'Finland', code: 'FI', language: 'en', region: 'eu' },
        { name: 'France', code: 'FR', language: 'fr', region: 'eu' },
        { name: 'French Polynesia', code: 'PF', language: 'fr', region: 'sg' },
        { name: 'Germany', code: 'DE', language: 'de', region: 'eu' },
        { name: 'Greece', code: 'GR', language: 'en', region: 'eu' },
        { name: 'Guatemala', code: 'GT', language: 'es', region: 'mx' },
        { name: 'Hong Kong', code: 'HK', language: 'zh_TW', region: 'sg' },
        { name: 'Honduras', code: 'HN', language: 'es', region: 'mx' },
        { name: 'Hungary', code: 'HU', language: 'en', region: 'eu' },
        { name: 'Iceland', code: 'IS', language: 'en', region: 'eu' },
        { name: 'India', code: 'IN', language: 'en', region: 'in' },
        { name: 'Indonesia', code: 'ID', language: 'id', region: 'id' },
        { name: 'Ireland', code: 'IE', language: 'en', region: 'eu' },
        { name: 'Israel', code: 'IL', language: 'he', region: 'eu' },
        { name: 'Italy', code: 'IT', language: 'it', region: 'eu' },
        { name: 'Japan', code: 'JP', language: 'ja', region: 'jp' },
        { name: 'Jordan', code: 'JO', language: 'ar', region: 'no' },
        { name: 'Kazakhstan', code: 'KZ', language: 'ru', region: 'kz' },
        { name: 'Kosovo', code: 'XK', language: 'en', region: 'eu' },
        { name: 'Kuwait', code: 'KW', language: 'ar', region: 'no' },
        { name: 'Laos', code: 'LA', language: 'en', region: 'sg' },
        { name: 'Latvia', code: 'LV', language: 'en', region: 'eu' },
        { name: 'Liechtenstein', code: 'LI', language: 'de', region: 'eu' },
        { name: 'Lithuania', code: 'LT', language: 'en', region: 'eu' },
        { name: 'Luxembourg', code: 'LU', language: 'fr', region: 'eu' },
        { name: 'Macao', code: 'MO', language: 'zh_TW', region: 'sg' },
        { name: 'Malaysia', code: 'MY', language: 'en', region: 'sg' },
        { name: 'Maldives', code: 'MV', language: 'en', region: 'sg' },
        { name: 'Malta', code: 'MT', language: 'en', region: 'eu' },
        { name: 'Mauritius', code: 'MU', language: 'en', region: 'no' },
        { name: 'Mexico', code: 'MX', language: 'es', region: 'mx' },
        { name: 'Moldova', code: 'MD', language: 'ru', region: 'eu' },
        { name: 'Monaco', code: 'MC', language: 'fr', region: 'eu' },
        { name: 'Mongolia', code: 'MN', language: 'en', region: 'sg' },
        { name: 'Montenegro', code: 'ME', language: 'en', region: 'eu' },
        { name: 'Morocco', code: 'MA', language: 'ar', region: 'no' },
        { name: 'Myanmar', code: 'MM', language: 'en', region: 'sg' },
        { name: 'Nepal', code: 'NP', language: 'en', region: 'sg' },
        { name: 'Netherlands', code: 'NL', language: 'nl', region: 'eu' },
        { name: 'New Caledonia', code: 'NC', language: 'fr', region: 'sg' },
        { name: 'New Zealand', code: 'NZ', language: 'en', region: 'au' },
        { name: 'Nicaragua', code: 'NI', language: 'es', region: 'mx' },
        { name: 'North Macedonia', code: 'MK', language: 'en', region: 'eu' },
        { name: 'Norway', code: 'NO', language: 'en', region: 'eu' },
        { name: 'Oman', code: 'OM', language: 'ar', region: 'om' },
        { name: 'Pakistan', code: 'PK', language: 'en', region: 'sg' },
        { name: 'Panama', code: 'PA', language: 'es', region: 'mx' },
        { name: 'Paraguay', code: 'PY', language: 'es', region: 'mx' },
        { name: 'Peru', code: 'PE', language: 'es', region: 'mx' },
        { name: 'Philippines', code: 'PH', language: 'en', region: 'sg' },
        { name: 'Poland', code: 'PL', language: 'en', region: 'eu' },
        { name: 'Portugal', code: 'PT', language: 'pt', region: 'eu' },
        { name: 'Qatar', code: 'QA', language: 'ar', region: 'no' },
        { name: 'Reunion Island', code: 'RE', language: 'fr', region: 'no' },
        { name: 'Romania', code: 'RO', language: 'en', region: 'eu' },
        { name: 'Saudi Arabia', code: 'SA', language: 'ar', region: 'sa' },
        { name: 'Serbia', code: 'RS', language: 'en', region: 'eu' },
        { name: 'Singapore', code: 'SG', language: 'en', region: 'sg' },
        { name: 'Slovakia', code: 'SK', language: 'en', region: 'eu' },
        { name: 'Slovenia', code: 'SI', language: 'en', region: 'eu' },
        { name: 'South Africa', code: 'ZA', language: 'en', region: 'no' },
        { name: 'South Korea', code: 'KR', language: 'ko', region: 'kr' },
        { name: 'Spain', code: 'ES', language: 'es', region: 'eu' },
        { name: 'Sri Lanka', code: 'LK', language: 'en', region: 'sg' },
        { name: 'Sweden', code: 'SE', language: 'en', region: 'eu' },
        { name: 'Switzerland', code: 'CH', language: 'de', region: 'eu' },
        { name: 'Thailand', code: 'TH', language: 'th', region: 'sg' },
        { name: 'Turkey', code: 'TR', language: 'tr', region: 'tr' },
        { name: 'Ukraine', code: 'UA', language: 'ru', region: 'eu' },
        { name: 'United Arab Emirates', code: 'AE', language: 'ar', region: 'no' },
        { name: 'United Kingdom', code: 'GB', language: 'en', region: 'eu' },
        { name: 'Uruguay', code: 'UY', language: 'es', region: 'mx' },
        { name: 'Uzbekistan', code: 'UZ', language: 'ru', region: 'uz' },
        { name: 'Vatican City', code: 'VA', language: 'it', region: 'eu' },
        { name: 'Vietnam', code: 'VN', language: 'vi', region: 'vn' }
    ],

    normalizeRegion(region) {
        if (region === 'kr-ali') return 'kr';
        return this.regionLabels[region] ? region : 'eu';
    },

    ensureCountryOptions() {
        const select = document.getElementById('bydCountryCode');
        if (!select || select.dataset.loaded === '1') return;
        select.innerHTML = '';
        this.countryByCode = {};
        for (var i = 0; i < this.countries.length; i++) {
            var country = this.countries[i];
            this.countryByCode[country.code] = country;
            var opt = document.createElement('option');
            opt.value = country.code;
            opt.textContent = country.name + ' (' + country.code + ')';
            select.appendChild(opt);
        }
        select.dataset.loaded = '1';
    },

    getCountryMeta(countryCode) {
        this.ensureCountryOptions();
        var code = (countryCode || '').trim().toUpperCase();
        return (this.countryByCode && this.countryByCode[code]) || null;
    },

    countryForRegion(region) {
        var normalized = this.normalizeRegion(region);
        return this.defaultCountriesByRegion[normalized] || 'GB';
    },

    setRegionDisplay(region, countryCode) {
        var normalized = this.normalizeRegion(region);
        const regionSelect = document.getElementById('bydRegion');
        const summary = document.getElementById('bydRegionDerived');
        if (regionSelect) regionSelect.value = normalized;
        if (summary) {
            var entry = this.regionLabels[normalized];
            var label;
            if (entry && BYD && BYD.i18n) {
                label = BYD.i18n.t(entry.i18n) || entry.en;
            } else {
                label = (entry && entry.en) || normalized.toUpperCase();
            }
            var ctry = countryCode || 'GB';
            if (BYD && BYD.i18n) {
                summary.textContent = BYD.i18n.t('byd_cloud.region_derived', {
                    country: ctry, region: label
                });
            } else {
                summary.textContent = 'Country code ' + ctry + ' uses the ' + label + ' BYD server.';
            }
        }
    },

    applyCountrySelection(countryCode, region) {
        // Bail early on pages that don't render the BYD account UI (e.g. the
        // surveillance/status pages call loadStatus to fetch the configured
        // state but don't include the country/region selects).
        if (!document.getElementById('bydCountryCode')
                && !document.getElementById('bydRegion')
                && !document.getElementById('bydRegionDerived')) {
            return;
        }
        this.ensureCountryOptions();
        var meta = this.getCountryMeta(countryCode);
        if (!meta) {
            var fallbackCode = this.countryForRegion(region || 'eu');
            meta = this.getCountryMeta(fallbackCode) || this.getCountryMeta('GB');
        }
        const select = document.getElementById('bydCountryCode');
        if (select && meta) select.value = meta.code;
        this.setRegionDisplay(meta ? meta.region : this.normalizeRegion(region), meta ? meta.code : countryCode);
    },

    async loadStatus() {
        this.ensureCountryOptions();
        this.applyCountrySelection('GB', 'eu');
        try {
            const resp = await fetch('/api/bydcloud/status');
            const data = await resp.json();
            if (data.success && data.status) {
                this.isConfigured = data.status.configured;
                this.updateStatusUI(data.status);
            }
        } catch (e) {
            console.warn('Failed to load BYD Cloud status:', e);
        }
    },

    onCountryChange(countryCode) {
        var meta = this.getCountryMeta(countryCode);
        this.setRegionDisplay(meta ? meta.region : 'eu', meta ? meta.code : countryCode);
    },

    onRegionChange(region) {
        this.setRegionDisplay(region, null);
    },

    updateStatusUI(status) {
        const badge = document.getElementById('bydCloudBadge');
        const info = document.getElementById('bydCloudInfo');
        const clearSection = document.getElementById('bydClearSection');
        const testBtn = document.getElementById('bydTestBtn');
        const saveBtn = document.getElementById('bydSaveBtn');
        const emailInput = document.getElementById('bydEmail');
        const pwdHint = document.getElementById('bydPasswordHint');
        const pinHint = document.getElementById('bydPinHint');
        const pwdInput = document.getElementById('bydPassword');
        const pinInput = document.getElementById('bydPin');
        this.applyCountrySelection(status.countryCode, status.region);

        if (status.verified) {
            this.isConfigured = true;
            if (badge) { badge.textContent = BYD.i18n.t('surveillance.byd_badge_connected'); badge.className = 'status-badge active'; }
            if (info) {
                info.style.display = 'block';
                document.getElementById('bydVinDisplay').textContent = status.vin || '\u2014';
                document.getElementById('bydAccountDisplay').textContent = status.username || '\u2014';
            }
            if (clearSection) clearSection.style.display = 'block';
            if (testBtn) { testBtn.disabled = false; testBtn.style.color = 'var(--text-primary)'; testBtn.style.borderColor = 'var(--brand-primary)'; }
            if (emailInput) emailInput.value = status.username || '';
            if (pwdInput) pwdInput.placeholder = BYD.i18n.t('surveillance.byd_password_placeholder_keep');
            if (pinInput) pinInput.placeholder = BYD.i18n.t('surveillance.byd_pin_placeholder_keep');
            if (pwdHint) pwdHint.textContent = BYD.i18n.t('surveillance.byd_pwd_keep');
            if (pinHint) pinHint.textContent = BYD.i18n.t('surveillance.byd_pin_keep');
            if (saveBtn) saveBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> ' + BYD.i18n.t('surveillance.update_credentials');
        } else {
            this.isConfigured = status.configured || false;
            if (status.configured && !status.verified) {
                if (badge) { badge.textContent = BYD.i18n.t('surveillance.byd_badge_saved'); badge.className = 'status-badge inactive'; }
            } else {
                if (badge) { badge.textContent = BYD.i18n.t('surveillance.byd_badge_not_set'); badge.className = 'status-badge inactive'; }
            }
            if (info) info.style.display = 'none';
            if (clearSection) clearSection.style.display = 'none';
            if (testBtn) { testBtn.disabled = true; testBtn.style.color = 'var(--text-muted)'; testBtn.style.borderColor = 'var(--border-default)'; }
            if (pwdInput) pwdInput.placeholder = BYD.i18n.t('surveillance.byd_pwd_placeholder');
            if (pinInput) pinInput.placeholder = '123456';
            if (pwdHint) pwdHint.textContent = BYD.i18n.t('surveillance.byd_pwd_hint');
            if (pinHint) pinHint.textContent = BYD.i18n.t('surveillance.byd_pin_hint');
            if (saveBtn) saveBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> ' + BYD.i18n.t('surveillance.save_credentials');
        }

        BYD.surveillance.config.bydCloudEnabled = status.verified || false;
        BYD.surveillance.updateDeterrentUI();

        // Cloud push status
        var pushSection = document.getElementById('bydCloudPushSection');
        var mergeSection = document.getElementById('bydCloudMergeSection');
        if (status.verified && status.cloudPush) {
            var cp = status.cloudPush;
            if (pushSection) pushSection.style.display = 'block';
            if (mergeSection) mergeSection.style.display = 'block';

            var pushBadge = document.getElementById('bydPushBadge');
            var pushAge = document.getElementById('bydPushAge');
            var pushLock = document.getElementById('bydPushLock');
            var pushSoc = document.getElementById('bydPushSoc');
            var pushCharging = document.getElementById('bydPushCharging');

            if (pushBadge) {
                if (cp.connected && cp.lastMessageAge >= 0 && cp.lastMessageAge < 120) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_live');
                    pushBadge.className = 'status-badge active';
                } else if (cp.connected && cp.lastMessageAge >= 0 && cp.lastMessageAge < 600) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_ok');
                    pushBadge.className = 'status-badge active';
                } else if (cp.connected && cp.lastMessageAge >= 600) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_stale');
                    pushBadge.className = 'status-badge inactive';
                } else if (cp.connected) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_waiting');
                    pushBadge.className = 'status-badge inactive';
                } else {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_offline');
                    pushBadge.className = 'status-badge inactive';
                }
            }

            if (pushAge) {
                if (cp.lastMessageAge >= 0) {
                    var age = cp.lastMessageAge;
                    pushAge.textContent = age < 60 ? BYD.i18n.t('surveillance.byd_age_seconds', {n: age}) : BYD.i18n.t('surveillance.byd_age_minutes', {n: Math.floor(age / 60)});
                } else {
                    pushAge.textContent = cp.connected ? BYD.i18n.t('surveillance.byd_waiting_data') : '';
                }
            }

            if (pushLock) {
                if (cp.lockState && cp.lockState !== 'unknown') {
                    var lockIcon = cp.lockState === 'locked' ? '\uD83D\uDD12' : '\uD83D\uDD13';
                    pushLock.textContent = lockIcon + ' ' + cp.lockState;
                } else if (cp.connected && cp.lastMessageAge < 0) {
                    pushLock.textContent = BYD.i18n.t('surveillance.byd_waiting_tbox');
                } else {
                    pushLock.textContent = '';
                }
            }
            if (pushSoc && cp.socPercent != null) {
                pushSoc.textContent = '\uD83D\uDD0B ' + cp.socPercent + '%';
            } else if (pushSoc) {
                pushSoc.textContent = '';
            }
            if (pushCharging) {
                if (cp.chargingState && cp.chargingState !== 'unknown') {
                    var chgLabel = { 'not_charging': BYD.i18n.t('surveillance.byd_charge_not_charging'), 'charging': BYD.i18n.t('surveillance.byd_charge_charging') };
                    pushCharging.textContent = '\u26A1 ' + (chgLabel[cp.chargingState] || cp.chargingState);
                } else {
                    pushCharging.textContent = '';
                }
            }

            // Merge toggle
            var mergeToggle = document.getElementById('bydCloudMergeToggle');
            if (mergeToggle) mergeToggle.checked = cp.cloudDataMerge || false;
        } else {
            if (pushSection) pushSection.style.display = 'none';
            if (mergeSection) mergeSection.style.display = 'none';
        }
    },

    async saveCredentials() {
        const email = document.getElementById('bydEmail').value.trim();
        const password = document.getElementById('bydPassword').value.trim();
        const pin = document.getElementById('bydPin').value.trim();
        const countrySelect = document.getElementById('bydCountryCode');
        const countryMeta = this.getCountryMeta(countrySelect ? countrySelect.value : 'GB') || this.getCountryMeta('GB');
        const region = countryMeta ? countryMeta.region : 'eu';
        const saveBtn = document.getElementById('bydSaveBtn');
        // Preserve the icon + label so the finally block can restore it cleanly
        // after the request resolves; assigning .textContent strips the SVG.
        const saveBtnHtml = saveBtn ? saveBtn.innerHTML : null;

        if (!email) { this.showStatus(BYD.i18n.t('surveillance.byd_email_required'), 'error'); return; }
        if (!this.isConfigured && (!password || !pin)) {
            this.showStatus(BYD.i18n.t('surveillance.byd_pwd_pin_required'), 'error');
            return;
        }

        saveBtn.disabled = true;
        saveBtn.textContent = BYD.i18n.t('surveillance.byd_saving');
        this.showStatus(BYD.i18n.t('surveillance.byd_save_progress'), 'info');

        try {
            const body = {
                username: email,
                region: region,
                countryCode: countryMeta ? countryMeta.code : 'GB',
                language: countryMeta ? countryMeta.language : 'en'
            };
            if (password) body.password = password;
            if (pin) body.controlPin = pin;

            const controller = new AbortController();
            const timeoutId = setTimeout(function() { controller.abort(); }, 60000);

            const resp = await fetch('/api/bydcloud/setup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
                signal: controller.signal
            });
            clearTimeout(timeoutId);
            const data = await resp.json();

            if (data.success) {
                this.showStatus(BYD.i18n.t('surveillance.byd_save_verified', {vin: data.vin}), 'success');
                document.getElementById('bydPassword').value = '';
                document.getElementById('bydPin').value = '';
            } else {
                this.showStatus('\u2717 ' + (data.error || BYD.i18n.t('surveillance.byd_save_failed')), 'error');
            }
        } catch (e) {
            if (e.name === 'AbortError') {
                this.showStatus(BYD.i18n.t('surveillance.byd_request_long'), 'info');
                // The server might still be processing — wait and check
                await new Promise(function(r) { setTimeout(r, 5000); });
            } else {
                this.showStatus('\u2717 ' + BYD.i18n.t('vehicle.network_error_msg', {message: e.message}), 'error');
            }
        } finally {
            saveBtn.disabled = false;
            if (saveBtnHtml != null) saveBtn.innerHTML = saveBtnHtml;
            await this.loadStatus();
        }
    },

    async testConnection() {
        const testBtn = document.getElementById('bydTestBtn');
        testBtn.disabled = true;
        testBtn.textContent = BYD.i18n.t('surveillance.byd_test_testing');
        this.showStatus(BYD.i18n.t('surveillance.byd_test_progress'), 'info');

        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(function() { controller.abort(); }, 60000);

            const resp = await fetch('/api/bydcloud/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: 'flash_lights' }),
                signal: controller.signal
            });
            clearTimeout(timeoutId);
            const data = await resp.json();
            if (data.success) {
                this.showStatus(BYD.i18n.t('surveillance.byd_test_sent'), 'success');
            } else {
                this.showStatus('\u2717 ' + (data.error || BYD.i18n.t('surveillance.byd_test_failed')), 'error');
            }
        } catch (e) {
            if (e.name === 'AbortError') {
                this.showStatus(BYD.i18n.t('surveillance.byd_test_check_car'), 'info');
            } else {
                this.showStatus('\u2717 ' + e.message, 'error');
            }
        } finally {
            testBtn.disabled = false;
            testBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg> ' + BYD.i18n.t('surveillance.test_connection');
        }
    },

    async clearCredentials() {
        var t = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t.bind(BYD.i18n) : null;
        if (BYD.utils && BYD.utils.confirmDialog) {
            var ok = await BYD.utils.confirmDialog({
                title: (t && t('surveillance.byd_clear_creds_title')) || 'Clear BYD Cloud credentials?',
                body: (t && t('surveillance.byd_clear_creds_body'))
                    || 'Deterrent actions (flash lights, horn) will stop working until you set up again.',
                confirmLabel: (t && t('common.clear')) || 'Clear',
                cancelLabel: (t && t('common.cancel')) || 'Cancel',
                danger: true
            });
            if (!ok) return;
        } else if (typeof confirm === 'function') {
            if (!confirm(BYD.i18n.t('surveillance.confirm_clear_byd_creds'))) return;
        }

        try {
            await fetch('/api/bydcloud/clear', { method: 'POST' });
            this.showStatus(BYD.i18n.t('surveillance.byd_creds_cleared'), 'info');
            document.getElementById('bydEmail').value = '';
            document.getElementById('bydPassword').value = '';
            document.getElementById('bydPin').value = '';
            this.applyCountrySelection('GB', 'eu');
            const deterrentSelect = document.getElementById('deterrentAction');
            if (deterrentSelect && deterrentSelect.value !== 'silent') {
                deterrentSelect.value = 'silent';
                BYD.surveillance.updateDeterrent('silent');
            }
            await this.loadStatus();
        } catch (e) {
            this.showStatus('\u2717 ' + e.message, 'error');
        }
    },

    showStatus(message, type) {
        const div = document.getElementById('bydCloudStatus');
        if (!div) return;
        div.style.display = 'block';
        div.textContent = message;
        const colors = {
            success: { bg: 'rgba(34,197,94,0.1)', border: '#22c55e', color: '#16a34a' },
            error:   { bg: 'rgba(239,68,68,0.1)', border: '#ef4444', color: '#dc2626' },
            info:    { bg: 'rgba(59,130,246,0.1)', border: '#3b82f6', color: '#2563eb' }
        };
        const c = colors[type] || colors.info;
        div.style.background = c.bg;
        div.style.borderLeft = '3px solid ' + c.border;
        div.style.color = c.color;
        if (type !== 'error') {
            setTimeout(function() { if (div.textContent === message) div.style.display = 'none'; }, 8000);
        }
    },

    togglePasswordVisibility(inputId, btn) {
        const input = document.getElementById(inputId);
        if (!input) return;
        if (input.type === 'password') {
            input.type = 'text';
            btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';
        } else {
            input.type = 'password';
            btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
        }
    },

    async toggleCloudDataMerge(enabled) {
        try {
            await fetch('/api/bydcloud/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ cloudDataMerge: enabled })
            });
        } catch (e) {
            console.warn('Failed to update cloud data merge:', e);
        }
    }
};
