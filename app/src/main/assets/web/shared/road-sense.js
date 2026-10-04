/**
 * Overdrive — RoadSense Settings Module
 *
 * Mirrors recording.js / surveillance.js:
 *   - loadConfig() reads the current state from the daemon (GET
 *     /api/settings/unified -> config.roadSense).
 *   - each control persists immediately on change via fetch() POST
 *     /api/settings/unified { section: "roadSense", data: { ... } }.
 *     (XHR POST bodies are dropped in the in-app WebView — always fetch().)
 *
 * Config keys (the `roadSense` UCM section; RoadSenseConfig.kt reads these
 * exact names):
 *   enabled, warnEnabled, warnMode ("visual"|"audio"|"both"),
 *   warnAudioChannel ("navigation"|"media"|"voice"|"alarm"), warnAudioVolume (10..100),
 *   warnLeadSeconds (default 4, 2..8), warnConfidenceThreshold (0..1, default 0),
 *   warnSeverityMinor / warnSeverityModerate / warnSeveritySevere,
 *   calibrationMode, crowdUpload, crowdDownload, syncWorkerUrl.
 *
 * The two delete actions hit live daemon endpoints (RoadSenseApiHandler, routed
 * from HttpServer):
 *   POST /api/roadsense/delete-local  — wipe on-device calibrations/detections
 *   POST /api/roadsense/delete-cloud  — wipe this device's uploaded rows
 */

window.BYD = window.BYD || {};

BYD.roadSense = {
    config: {
        enabled: false,
        warnEnabled: true,
        warnMode: 'both',
        // Chime audio channel: 'navigation' (car guidance stream) | 'media' | 'voice' | 'alarm'.
        warnAudioChannel: 'navigation',
        // Severe-alert ceiling; minor and moderate are scaled below it daemon-side.
        warnAudioVolume: 75,
        warnLeadSeconds: 4,
        // Stored 0..1 in the config; the slider works in whole percent (0..100).
        warnConfidenceThreshold: 0,
        // Detection sensitivity: a MULTIPLIER on the detector threshold (0.7..1.3,
        // default 1.0 = shipped tuning). Lower = more sensitive. The slider works in
        // "Less↔More" percent and is INVERTED vs this multiplier (see _detectPctToMult).
        // Distinct from warnConfidenceThreshold — this changes what gets DETECTED.
        detectionSensitivity: 1.0,
        warnSeverityMinor: true,
        warnSeverityModerate: true,
        warnSeveritySevere: true,
        calibrationMode: false,
        crowdUpload: false,
        crowdDownload: false,
        syncWorkerUrl: '',
        // Show the app-side floating pill/card on screen (default ON). Hiding it is
        // display-only — detection/audio/crowdsource keep running daemon-side.
        overlayVisible: true,
        // Blind Spot (separate UCM 'blindspot' section). enabled gates the
        // native indicator overlay; the 6 numerics are the stitch calibration.
        bsEnabled: false,
        // Camera merge mode: 'both' (rear+side stitch), 'side', or 'rear'.
        bsMergeMode: 'both',
        // On-screen card rotation. Either a fixed quarter turn (0/90/180/270) or
        // 'auto' (direction-of-travel: holds bsRotationBase moving forward, flips
        // 180° in reverse). Only applies to the single-camera modes (side/rear);
        // ignored for the merged 'both' view.
        bsRotation: 0,
        // Base quarter turn used as the forward orientation when bsRotation === 'auto'.
        bsRotationBase: 0,
        // PER-SIDE rotation: the left camera (view 7 / left turn) and right camera
        // (view 8 / right turn) are mirror-imaged, so each has its OWN rotation. These
        // seed from the global bsRotation/bsRotationBase on load when the per-side keys
        // aren't persisted yet (backward compatible).
        bsRotationLeft: 0,
        bsRotationRight: 0,
        bsRotationBaseLeft: 0,
        bsRotationBaseRight: 0,
        bsRearFov: 1.66,
        bsSideFov: 1.98,
        bsYaw: 1.23,
        bsRoll: 0.25,
        bsPitch: -0.275,
        bsFeather: 0.38,
        // Additional opaque stitch-tuning scalars; defaults = no change.
        bsProjExp: 1.0,
        bsRearRoll: 0.0,
        bsRearPitch: 0.0,
        // Fisheye / lens dewarp strength (0..100) for the SINGLE-CAMERA views
        // (side/rear). Separate from the recording pipeline's dewarp; 0 = off.
        // Ignored for the merged 'both' view.
        bsRectifyStrength: 0,
        // Conditional display: the card only appears while the vehicle speed is
        // inside [bsMinSpeedKmh, bsMaxSpeedKmh] and, optionally, not in reverse.
        // 0 means "no bound on that end", so 0/0 = any speed (the shipping
        // behaviour). Always km/h on the wire — the daemon gate compares in km/h;
        // the UI only converts for the mph hint. bsSuppressReverse defaults false.
        bsMinSpeedKmh: 0,
        bsMaxSpeedKmh: 0,
        bsSuppressReverse: false,
        // On-screen card size (% of panel width) + corner. Persisted as a preset
        // (not absolute px) so it stays correct across portrait/landscape rotation.
        // PER-TARGET: the head-unit set (bsSizePct/bsCorner) and the cluster set
        // (bsSizePctCluster/bsCornerCluster) are tracked separately because a card
        // sized for the 15.6" head-unit overflows the short 1920x720 cluster.
        bsSizePct: 40,
        bsCorner: 'tr',
        // PER-SIDE card corner: the left camera (view 7 / left turn) and right camera
        // (view 8 / right turn) can each sit at their own corner, so the driver can put
        // the left card on the left of the screen and the right card on the right. Seed
        // from bsCorner on load when the per-side keys aren't persisted (backward
        // compatible). Per-target, like bsCorner (head-unit vs cluster).
        bsCornerLeft: 'tr',
        bsCornerRight: 'tr',
        // Blind-spot display target: 'head_unit' (default) or 'cluster'.
        bsTarget: 'head_unit',
        bsSizePctCluster: 80,
        bsCornerCluster: 'tr',
        bsCornerLeftCluster: 'tr',
        bsCornerRightCluster: 'tr',
        // Cluster projection layout = OEM size profile (29=8.8", 30=12.3", 31=10.25").
        // 31 is the confirmed-correct default for this cluster.
        bsClusterLayout: 31,
        // Map → cluster projection. autoProjectCluster lives in the UCM `navMap`
        // section (read by the daemon on ACC-on); the live projecting state is NOT
        // a config value — it comes from GET /api/navmap/cluster/status.
        autoProjectCluster: false,
        // The Projection feature's own auto-cast-on-ACC-on preference (UCM
        // `projection` section, key autoStartOnAcc). Read-only here — its UI lives
        // in a native fragment. The driver cluster is a single surface, so this and
        // autoProjectCluster are mutually exclusive: turning the map auto-project ON
        // while this is set prompts to hand the cluster over (see toggleClusterAuto).
        projectionAutoStart: false
    },
    _hydrated: false,
    _writing: false,
    _writesPending: 0,
    _writeQueue: Promise.resolve(),
    _configWriteVersion: 0,
    _routingWriteVersion: 0,
    _routingWritesPending: 0,
    _clusterProjectVersion: 0,
    _clusterProjectPending: false,

    _enqueueWrite(task) {
        const run = () => task();
        const next = this._writeQueue.then(run, run);
        this._writeQueue = next.catch(() => {});
        return next;
    },

    async _saveSection(section, delta) {
        if (!this._hydrated) return null;
        this._configWriteVersion++;
        this._writesPending++;
        this._writing = true;
        try {
            return await this._enqueueWrite(async () => {
                try {
                    const resp = await fetch('/api/settings/unified', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ section: section, data: delta })
                    });
                    const data = await resp.json();
                    if (!resp.ok || !data) return null;
                    return data;
                } catch (e) {
                    console.warn(section + ': save failed:', e);
                    return null;
                }
            });
        } finally {
            this._writesPending--;
            this._writing = this._writesPending > 0;
        }
    },

    async init() {
        await this.loadConfig();
        this.updateUI(false);
        await Promise.all([this.loadRoutingStatus(), this.loadClusterStatus()]);
        this._hydrated = true;
        if (BYD.utils && BYD.utils.unlockSettingsHydration) {
            BYD.utils.unlockSettingsHydration();
        }

        // Re-read config when the user switches back to the tab (unless a
        // write is mid-flight). Cheap and keeps the page in sync with the
        // native settings UI / daemon-side changes.
        const self = this;

        // The speed hint / validation error is written imperatively via textContent, so
        // it has no data-i18n for hydrate() to re-translate on an in-page language
        // switch. Re-render it from the new catalog when the locale changes.
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
                BYD.i18n.onChange(function () { self._bsPaintSpeedHint(); });
            }
        } catch (e) { /* no i18n runtime — the hint keeps its English fallback */ }
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'hidden') {
                self._flushWarnVolumeOnExit();
                // CRITICAL: tearing the page away (tab switch / background) while
                // a debug preview is active would otherwise leave debugPreview=true
                // pinned in UCM — the native service keeps the HW decoder warm and
                // the global stream hijacked to view 7/8 indefinitely, surviving
                // ACC-off and app/daemon restart. Stop it on hide. visibilitychange
                // 'hidden' fires reliably on background where unload may not.
                if (self._bsPreviewActive) self.bsPreviewStop();
                return;
            }
            if (document.visibilityState === 'visible' && !self._writing) {
                self.reload();
            }
        });
        // pagehide/beforeunload cover hard navigation away from the page (the
        // visibilitychange('hidden') above covers background/tab-switch).
        window.addEventListener('pagehide', function () {
            self._flushWarnVolumeOnExit();
            if (self._bsPreviewActive) self.bsPreviewStop();
        });
        window.addEventListener('beforeunload', function () {
            self._flushWarnVolumeOnExit();
            if (self._bsPreviewActive) self.bsPreviewStop();
        });
    },

    async reload() {
        if (await this.loadConfig()) this.updateUI();
    },

    async loadConfig() {
        if (this._writesPending > 0) return false;
        const writeVersion = this._configWriteVersion;
        try {
            const resp = await fetch('/api/settings/unified');
            const data = await resp.json();
            if (this._writesPending > 0
                    || writeVersion !== this._configWriteVersion) return false;
            if (data && data.success && data.config && data.config.roadSense) {
                const rs = data.config.roadSense;
                const c = this.config;
                if (typeof rs.enabled === 'boolean') c.enabled = rs.enabled;
                if (typeof rs.warnEnabled === 'boolean') c.warnEnabled = rs.warnEnabled;
                if (rs.warnMode) c.warnMode = String(rs.warnMode).toLowerCase();
                // Unknown/absent value keeps the 'navigation' default, matching the
                // daemon's own clamp (RoadSenseAudioChannels.normalize).
                if (rs.warnAudioChannel) {
                    const ch = String(rs.warnAudioChannel).toLowerCase();
                    if (['navigation', 'media', 'voice', 'alarm'].indexOf(ch) >= 0) {
                        c.warnAudioChannel = ch;
                    }
                }
                if (typeof rs.warnAudioVolume === 'number') {
                    c.warnAudioVolume = this._clamp(Math.round(rs.warnAudioVolume), 10, 100);
                    if (!this._warnVolumeSavePromise && this._warnVolumePending == null) {
                        this._warnVolumeConfirmed = c.warnAudioVolume;
                    }
                }

                if (typeof rs.warnLeadSeconds === 'number') {
                    let v = Math.round(rs.warnLeadSeconds);
                    if (v < 2) v = 2; if (v > 8) v = 8;
                    c.warnLeadSeconds = v;
                }
                if (typeof rs.warnConfidenceThreshold === 'number') {
                    let t = rs.warnConfidenceThreshold;
                    if (t < 0) t = 0; if (t > 1) t = 1;
                    c.warnConfidenceThreshold = t;
                }
                if (typeof rs.detectionSensitivity === 'number') {
                    let m = rs.detectionSensitivity;
                    if (m < this.DETECT_MULT_MIN) m = this.DETECT_MULT_MIN;
                    if (m > this.DETECT_MULT_MAX) m = this.DETECT_MULT_MAX;
                    c.detectionSensitivity = m;
                }
                if (typeof rs.warnSeverityMinor === 'boolean') c.warnSeverityMinor = rs.warnSeverityMinor;
                if (typeof rs.warnSeverityModerate === 'boolean') c.warnSeverityModerate = rs.warnSeverityModerate;
                if (typeof rs.warnSeveritySevere === 'boolean') c.warnSeveritySevere = rs.warnSeveritySevere;
                if (typeof rs.calibrationMode === 'boolean') c.calibrationMode = rs.calibrationMode;
                if (typeof rs.crowdUpload === 'boolean') c.crowdUpload = rs.crowdUpload;
                if (typeof rs.crowdDownload === 'boolean') c.crowdDownload = rs.crowdDownload;
                if (typeof rs.syncWorkerUrl === 'string') c.syncWorkerUrl = rs.syncWorkerUrl;
                // Default ON when the key is absent (existing installs) — only flip to
                // hidden on an explicit stored false.
                if (typeof rs.overlayVisible === 'boolean') c.overlayVisible = rs.overlayVisible;
            }
            // Any successful RESPONSE means the persisted speed window is now known —
            // including one whose config has no `blindspot` section at all, which
            // legitimately means "both bounds absent = 0/0 = any". Set the flag here,
            // OUTSIDE the section guard: keying it to the section's presence would leave
            // it stuck false after one transient failure on such a config, permanently
            // disabling Apply (the flag is only ever cleared by a failed load).
            if (data && data.success && data.config) this._bsSpeedLoaded = true;
            // Blind Spot lives in its own top-level section.
            if (data && data.success && data.config && data.config.blindspot) {
                const bs = data.config.blindspot;
                const c = this.config;
                if (typeof bs.enabled === 'boolean') c.bsEnabled = bs.enabled;
                if (bs.mergeMode === 'both' || bs.mergeMode === 'side' || bs.mergeMode === 'rear') c.bsMergeMode = bs.mergeMode;
                if (bs.rotation === 0 || bs.rotation === 90 || bs.rotation === 180 || bs.rotation === 270 || bs.rotation === 'auto') c.bsRotation = bs.rotation;
                if (bs.rotationBase === 0 || bs.rotationBase === 90 || bs.rotationBase === 180 || bs.rotationBase === 270) c.bsRotationBase = bs.rotationBase;
                // PER-SIDE rotation (left = view 7 / left turn, right = view 8 / right
                // turn). Fall back to the legacy global rotation/rotationBase when a
                // per-side key is absent so an un-migrated config still populates the UI.
                // 40 and 310 are the free-angle presets: picking one applies a whole
                // framing (crop, mirror, zoom), not just an angle. 'auto' and the base
                // angles stay quarter-turn only — 'auto' flips 180 in reverse, which is
                // a quarter-turn idea that a preset framing has no meaning for.
                var _rotOk = function (v) { return v === 0 || v === 40 || v === 90 || v === 180 || v === 270 || v === 310 || v === 'auto'; };
                var _baseOk = function (v) { return v === 0 || v === 90 || v === 180 || v === 270; };
                c.bsRotationLeft  = _rotOk(bs.rotationLeft)  ? bs.rotationLeft  : c.bsRotation;
                c.bsRotationRight = _rotOk(bs.rotationRight) ? bs.rotationRight : c.bsRotation;
                c.bsRotationBaseLeft  = _baseOk(bs.rotationBaseLeft)  ? bs.rotationBaseLeft  : c.bsRotationBase;
                c.bsRotationBaseRight = _baseOk(bs.rotationBaseRight) ? bs.rotationBaseRight : c.bsRotationBase;
                if (typeof bs.rearFov === 'number') c.bsRearFov = this._clamp(bs.rearFov, 1.0, 2.2);
                if (typeof bs.sideFov === 'number') c.bsSideFov = this._clamp(bs.sideFov, 1.0, 2.2);
                if (typeof bs.yaw === 'number') c.bsYaw = this._clamp(bs.yaw, 0, 1.4);
                if (typeof bs.roll === 'number') c.bsRoll = this._clamp(bs.roll, -0.4, 0.4);
                if (typeof bs.pitch === 'number') c.bsPitch = this._clamp(bs.pitch, -0.4, 0.4);
                if (typeof bs.feather === 'number') c.bsFeather = this._clamp(bs.feather, 0, 1.0);
                if (typeof bs.projExp === 'number') c.bsProjExp = this._clamp(bs.projExp, 0.4, 1.6);
                if (typeof bs.rearRoll === 'number') c.bsRearRoll = this._clamp(bs.rearRoll, -0.4, 0.4);
                if (typeof bs.rearPitch === 'number') c.bsRearPitch = this._clamp(bs.rearPitch, -0.4, 0.4);
                if (typeof bs.rectifyStrength === 'number') c.bsRectifyStrength = this._clamp(bs.rectifyStrength, 0, 100);
                // Conditional display. Anything outside 1..BS_SPEED_MAX collapses to 0
                // ("any") — the same normalisation the daemon gate applies, so the UI
                // can never display a bound the daemon quietly ignores.
                c.bsMinSpeedKmh = this._bsNormSpeed(bs.minSpeedKmh);
                c.bsMaxSpeedKmh = this._bsNormSpeed(bs.maxSpeedKmh);
                // An INVERTED stored pair is unsatisfiable, so the daemon gate ignores
                // it entirely (shows at any speed). Paths that bypass the POST validator
                // can still produce one — a hand-edited config backup restore, chiefly —
                // so mirror the gate here rather than painting a window that isn't in
                // force. Displaying 0/0 tells the truth: the gate is disarmed.
                if (c.bsMinSpeedKmh > 0 && c.bsMaxSpeedKmh > 0
                        && c.bsMinSpeedKmh > c.bsMaxSpeedKmh) {
                    c.bsMinSpeedKmh = 0;
                    c.bsMaxSpeedKmh = 0;
                }
                // Mirror org.json optBoolean, which the daemon reads this with: it also
                // accepts the STRINGS "true"/"false" (case-insensitive). A typeof-only
                // check would render the toggle OFF while the gate was actually armed —
                // reachable via a hand-edited config or a restored backup.
                // NO trim(): org.json compares with equalsIgnoreCase and does NOT trim,
                // so " true" is FALSE to the daemon. Trimming here would show the toggle
                // ON for a disarmed gate — re-creating the very mismatch this closes.
                if (typeof bs.suppressInReverse === 'boolean') {
                    c.bsSuppressReverse = bs.suppressInReverse;
                } else if (typeof bs.suppressInReverse === 'string') {
                    var sir = bs.suppressInReverse.toLowerCase();
                    if (sir === 'true') c.bsSuppressReverse = true;
                    else if (sir === 'false') c.bsSuppressReverse = false;
                }
                // Display target ('head_unit' default | 'cluster').
                if (bs.target === 'cluster' || bs.target === 'head_unit') c.bsTarget = bs.target;
                // Cluster layout (size profile opcode 29/30/31).
                if (bs.clusterSizeProfile === 29 || bs.clusterSizeProfile === 30 || bs.clusterSizeProfile === 31) {
                    c.bsClusterLayout = bs.clusterSizeProfile;
                }
                // On-screen size/position preset (orientation-safe — daemon
                // recomputes px from the live panel). PER-TARGET: head-unit reads
                // geometry.{sizePct,corner}; cluster reads geometryCluster.{...}.
                var geo = bs.geometry || {};
                if (typeof geo.sizePct === 'number') c.bsSizePct = this._clamp(geo.sizePct, 15, 90);
                if (typeof geo.corner === 'string') c.bsCorner = geo.corner;
                var geoC = bs.geometryCluster || {};
                if (typeof geoC.sizePct === 'number') c.bsSizePctCluster = this._clamp(geoC.sizePct, 15, 90);
                if (typeof geoC.corner === 'string') c.bsCornerCluster = geoC.corner;
                // PER-SIDE corners (view 7 left / view 8 right). Fall back to the
                // per-target single corner when a per-side key is absent, so an
                // un-migrated config still populates both side controls sensibly.
                c.bsCornerLeft  = (typeof geo.cornerLeft  === 'string') ? geo.cornerLeft  : c.bsCorner;
                c.bsCornerRight = (typeof geo.cornerRight === 'string') ? geo.cornerRight : c.bsCorner;
                c.bsCornerLeftCluster  = (typeof geoC.cornerLeft  === 'string') ? geoC.cornerLeft  : c.bsCornerCluster;
                c.bsCornerRightCluster = (typeof geoC.cornerRight === 'string') ? geoC.cornerRight : c.bsCornerCluster;
            }
            // Map → cluster preference. autoProjectCluster lives in the navMap
            // section (the daemon reads it on ACC-on); default false when absent.
            if (data && data.success && data.config && data.config.navMap) {
                const nm = data.config.navMap;
                if (typeof nm.autoProjectCluster === 'boolean') this.config.autoProjectCluster = nm.autoProjectCluster;
            }
            // Projection feature's auto-cast-on-ACC-on preference (its own UCM
            // section, toggled from a native fragment). Read it so toggleClusterAuto
            // can detect the cluster conflict; default false when absent.
            if (data && data.success && data.config && data.config.projection) {
                const pj = data.config.projection;
                if (typeof pj.autoStartOnAcc === 'boolean') this.config.projectionAutoStart = pj.autoStartOnAcc;
            }
            return !!(data && data.success && data.config);
        } catch (e) {
            console.warn('RoadSense: failed to load config:', e);
            // The speed window is the only control written as an ATOMIC PAIR, so a
            // failed load is uniquely dangerous here: the inputs would paint the 0/0
            // defaults ("Any speed") while the daemon still holds e.g. maxSpeedKmh=200,
            // and applying an edit to one bound would POST both and silently erase the
            // other. Mark the pair unknown so bsApplySpeedWindow refuses to commit
            // until a successful load establishes the real values.
            this._bsSpeedLoaded = false;
            return false;
        }
    },

    _clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); },

    /**
     * Merge-write one or more keys into the roadSense UCM section. fetch()
     * (never XHR) so the WebView doesn't drop the POST body. Returns true on
     * a successful write so callers can revert the control on failure.
     */
    async _save(delta) {
        const data = await this._saveSection('roadSense', delta);
        return !!(data && data.success);
    },

    // ==================== UI sync ====================

    updateUI(loadLiveStatus) {
        if (loadLiveStatus == null) loadLiveStatus = true;
        const c = this.config;

        this._setChecked('rsEnabled', c.enabled);
        this._setBadge('rsStatusBadge', c.enabled);

        this._setChecked('rsCalibrationMode', c.calibrationMode);
        this._setChecked('rsOverlayVisible', c.overlayVisible);

        var inAppMap = (typeof window.AndroidBridge !== 'undefined'
            && typeof AndroidBridge.openHazardMap === 'function');

        // Hazard map = a NATIVE Activity launched only via the AndroidBridge, so
        // the "Open Map" card is meaningful ONLY in the in-app WebView. Show it in
        // app, HIDE it in a browser/tunnel (where it can't launch anything). The
        // routing + cluster cards below stay visible everywhere (pure daemon HTTP).
        var hazardMapCard = document.getElementById('rsHazardMapCard');
        if (hazardMapCard) {
            hazardMapCard.style.display = inAppMap ? '' : 'none';
        }

        // Routing (BYOK) config is pure daemon HTTP (/api/navmap/routing/*), which
        // works over a tunnel/browser too — so show it ALWAYS and load its status.
        // (Previously in-app-gated, which left it blank when developing locally.)
        var routingCard = document.getElementById('rsRoutingCard');
        if (routingCard) {
            routingCard.style.display = '';
            if (loadLiveStatus) this.loadRoutingStatus();
        }

        // Cluster projection drives an on-car native projection via daemon HTTP.
        // The toggle/status only DO something on-car, but showing the card off-car
        // is harmless + keeps the Map tab populated when developing. Status load is
        // best-effort (no-op if the daemon endpoint isn't reachable).
        var clusterCard = document.getElementById('rsClusterProjectCard');
        if (clusterCard) {
            clusterCard.style.display = '';
            this._setChecked('rsClusterAuto', c.autoProjectCluster);
            if (loadLiveStatus) this.loadClusterStatus();
        }

        this._setChecked('rsWarnEnabled', c.warnEnabled);
        this._setBadge('rsWarnBadge', c.enabled && c.warnEnabled);

        // Master gate: every other card only takes effect while RoadSense is on
        // (master_enable_desc). When it's off, dim + disable the dependent cards so
        // their toggles don't read as live next to an OFF badge — the contradiction
        // a first-run user hits (defaults show warnEnabled/severities ON, but the
        // Warnings badge is OFF because the master is off).
        this._applyMasterGate(c.enabled);

        // Warn mode button group.
        document.querySelectorAll('#rsWarnModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === c.warnMode));

        // Chime audio channel.
        const chanSel = document.getElementById('rsWarnChannel');
        if (chanSel) chanSel.value = c.warnAudioChannel;

        const volumeSlider = document.getElementById('rsWarnVolumeSlider');
        if (volumeSlider) volumeSlider.value = c.warnAudioVolume;
        this._setWarnVolumeLabel(c.warnAudioVolume);

        // Lead-time slider.
        const leadSlider = document.getElementById('rsWarnLeadSlider');
        if (leadSlider) leadSlider.value = c.warnLeadSeconds;
        this._setLeadLabel(c.warnLeadSeconds);

        // Confidence slider (config 0..1 -> percent 0..100).
        const confPct = Math.round(c.warnConfidenceThreshold * 100);
        const confSlider = document.getElementById('rsWarnConfSlider');
        if (confSlider) confSlider.value = confPct;
        this._setConfLabel(confPct);

        // Detection-sensitivity slider (config multiplier 0.7..1.3 -> "Less↔More" 0..100).
        const detectPct = this._detectMultToPct(c.detectionSensitivity);
        const detectSlider = document.getElementById('rsDetectSensSlider');
        if (detectSlider) detectSlider.value = detectPct;
        this._setDetectSensLabel(detectPct);

        // Per-severity chimes.
        this._setChecked('rsSeverityMinor', c.warnSeverityMinor);
        this._setChecked('rsSeverityModerate', c.warnSeverityModerate);
        this._setChecked('rsSeveritySevere', c.warnSeveritySevere);

        // Crowdsource.
        this._setChecked('rsCrowdUpload', c.crowdUpload);
        this._setChecked('rsCrowdDownload', c.crowdDownload);
        const urlInput = document.getElementById('rsSyncWorkerUrl');
        if (urlInput) urlInput.value = c.syncWorkerUrl || '';

        // Blind Spot.
        this._setChecked('bsEnabled', c.bsEnabled);
        this._setBadge('bsStatusBadge', c.bsEnabled);
        if (c.bsMergeMode !== 'both' && c.bsMergeMode !== 'side' && c.bsMergeMode !== 'rear') c.bsMergeMode = 'both';
        this._bsHighlightMergeMode(c.bsMergeMode);
        // PER-SIDE rotation: normalise + reflect left (view 7) and right (view 8)
        // independently. The mirror-imaged cameras each carry their own angle + auto base.
        var _normRot = function (v) { return (v === 0 || v === 90 || v === 180 || v === 270 || v === 'auto') ? v : 0; };
        var _normBase = function (v) { return (v === 0 || v === 90 || v === 180 || v === 270) ? v : 0; };
        c.bsRotationLeft = _normRot(c.bsRotationLeft);
        c.bsRotationRight = _normRot(c.bsRotationRight);
        c.bsRotationBaseLeft = _normBase(c.bsRotationBaseLeft);
        c.bsRotationBaseRight = _normBase(c.bsRotationBaseRight);
        this._bsHighlightRotation('left', c.bsRotationLeft);
        this._bsHighlightRotation('right', c.bsRotationRight);
        this._bsReflectAutoBaseRow('left', c.bsRotationLeft);
        this._bsReflectAutoBaseRow('right', c.bsRotationRight);
        this._bsHighlightAutoBase('left', c.bsRotationBaseLeft);
        this._bsHighlightAutoBase('right', c.bsRotationBaseRight);
        this._bsReflectRotationRow(c.bsMergeMode);
        // Fisheye slider value + row visibility (single-camera modes only).
        this._bsSetSlider('bsRectify', 'bsRectifyVal', c.bsRectifyStrength);
        this._bsReflectRectifyRow(c.bsMergeMode);
        // Conditional display: baseline the group as saved so Apply starts disabled
        // (same staged-edit model as the display/placement group below), then paint —
        // but PRESERVE a pending edit. updateUI also runs from reload() on
        // visibilitychange, and on a head unit the WebView is backgrounded constantly
        // (reverse camera, launcher, calls). Repainting unconditionally would silently
        // discard numbers the user had typed but not yet applied — including the
        // retry state left by a failed Apply. Keep the typed values and leave Apply
        // live; a fresh page load has no pending edit, so it paints normally.
        this._setChecked('bsSuppressReverse', c.bsSuppressReverse);
        var speedPending = this._bsSpeedPending();
        this._bsSpeedSaved = { min: c.bsMinSpeedKmh, max: c.bsMaxSpeedKmh };
        if (!speedPending) this._bsPaintSpeedWindow();
        this._bsMarkSpeedDirty();
        // Live preview is a NATIVE on-car window — only meaningful in the in-app
        // WebView. Hide the preview controls on a tunnel/browser (no AndroidBridge),
        // where tapping them would do nothing. Sliders + Apply still work remotely
        // (they tune/persist via HTTP), so only the preview row is gated.
        var inApp = (typeof window.AndroidBridge !== 'undefined');
        var pc = document.getElementById('bsPreviewControls');
        if (pc) pc.style.display = inApp ? '' : 'none';
        this._bsSetSlider('bsRearFov', 'bsRearFovVal', c.bsRearFov);
        this._bsSetSlider('bsSideFov', 'bsSideFovVal', c.bsSideFov);
        this._bsSetSlider('bsYaw', 'bsYawVal', c.bsYaw);
        this._bsSetSlider('bsRoll', 'bsRollVal', c.bsRoll);
        this._bsSetSlider('bsPitch', 'bsPitchVal', c.bsPitch);
        this._bsSetSlider('bsFeather', 'bsFeatherVal', c.bsFeather);
        this._bsSetSlider('bsProjExp', 'bsProjExpVal', c.bsProjExp);
        this._bsSetSlider('bsRearRoll', 'bsRearRollVal', c.bsRearRoll);
        this._bsSetSlider('bsRearPitch', 'bsRearPitchVal', c.bsRearPitch);
        // Reflect the ACTIVE target's saved size%/corner + layout dropdown, and
        // highlight the selected display target. Normalise first so a missing/empty
        // value still shows a definite selection (defaults head_unit).
        if (c.bsTarget !== 'cluster' && c.bsTarget !== 'head_unit') c.bsTarget = 'head_unit';
        this._bsHighlightTarget(c.bsTarget);
        this._bsReflectTargetControls(c.bsTarget);
        // Baseline the display/placement group as "saved" so Apply starts disabled
        // and only lights up on a real edit.
        this._bsDisplaySaved = this._bsSnapshotDisplay();
        this._bsDisplayDirty = false;
        this._bsMarkDirty();
    },

    /** Highlight the selected display target (M3 tonal selection, same pattern as
     *  the corner buttons). */
    _bsHighlightTarget(target) {
        var map = { head_unit: 'bsTargetHeadunit', cluster: 'bsTargetCluster' };
        for (var k in map) {
            var el = document.getElementById(map[k]);
            if (el) { if (k === target) el.classList.add('active'); else el.classList.remove('active'); }
        }
    },

    _bsSetSlider(sliderId, labelId, value) {
        const s = document.getElementById(sliderId);
        if (s) s.value = value;
        const l = document.getElementById(labelId);
        if (l) l.textContent = String(value);
    },

    _setChecked(id, on) {
        const el = document.getElementById(id);
        if (el) el.checked = !!on;
    },

    /**
     * Visually gate the dependent cards on the master `enabled` flag. The General
     * card holding the master switch stays fully live; every OTHER card (Warnings,
     * Crowdsource, Data) is dimmed + made non-interactive while the master is off,
     * so a first-run user doesn't see live-looking toggles next to an OFF badge.
     * Their checked STATE is preserved (so flipping master back on reveals the
     * saved selection) — we only block interaction, we don't change values.
     *
     * Blind Spot is a SEPARATE feature (its own `bsEnabled` flag + `blindspot`
     * UCM section, not RoadSense hazard detection), so its cards (data-tab=
     * "blindspot") are NOT gated by the RoadSense master — they can be enabled,
     * disabled, and tuned independently while RoadSense is off.
     *
     * The Map tab (data-tab="map": hazard map, routing BYOK key, cluster
     * projection) is likewise independent of hazard DETECTION — it is the
     * navigation map + routing + cluster surface, all driven by pure daemon
     * HTTP (/api/navmap/*) that works whether or not RoadSense is enabled. So
     * those cards are exempt too: the map/routing/cluster settings stay editable
     * with the RoadSense master off.
     */
    _applyMasterGate(masterOn) {
        document.querySelectorAll('.card').forEach(card => {
            // Leave the master switch's own card always interactive.
            if (card.querySelector('#rsEnabled')) return;
            // Blind Spot is independent of the RoadSense master gate.
            if (card.getAttribute('data-tab') === 'blindspot') return;
            // Map / routing / cluster are navigation surfaces, not hazard
            // detection — keep them editable while RoadSense is off.
            if (card.getAttribute('data-tab') === 'map') return;
            card.classList.toggle('rs-gated', !masterOn);
            // Block pointer + keyboard interaction on the controls when gated,
            // without touching their checked/value (so state survives a toggle).
            // `select` included: the chime-channel dropdown is the first <select> in a
            // GATED card (the other two live in the exempt map/blindspot tabs), so
            // without it that control stayed live inside a dimmed card.
            card.querySelectorAll('input, button, select, .btn-toggle').forEach(ctrl => {
                if (!masterOn) {
                    ctrl.setAttribute('disabled', 'disabled');
                    ctrl.setAttribute('aria-disabled', 'true');
                } else {
                    ctrl.removeAttribute('disabled');
                    ctrl.removeAttribute('aria-disabled');
                }
            });
        });
    },

    _setBadge(id, on) {
        const badge = document.getElementById(id);
        if (!badge) return;
        badge.textContent = on ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
        badge.className = 'status-badge ' + (on ? 'active' : 'inactive');
    },

    _setLeadLabel(seconds) {
        const el = document.getElementById('rsWarnLeadValue');
        if (!el) return;
        const tmpl = BYD.i18n.t('road_sense.unit_seconds', { n: seconds });
        el.textContent = (tmpl && tmpl !== 'road_sense.unit_seconds') ? tmpl : (seconds + BYD.units.secondSuffix(true));
    },

    _setConfLabel(pct) {
        const el = document.getElementById('rsWarnConfValue');
        if (el) el.textContent = pct + '%';
    },

    _setWarnVolumeLabel(pct) {
        const el = document.getElementById('rsWarnVolumeValue');
        if (el) el.textContent = pct + '%';
    },

    _setDetectSensLabel(pct) {
        const el = document.getElementById('rsDetectSensValue');
        if (el) el.textContent = pct + '%';
    },

    // ==================== Control handlers ====================

    async toggleEnabled() {
        const el = document.getElementById('rsEnabled');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ enabled: on });
        if (ok) {
            this.config.enabled = on;
            this._setBadge('rsStatusBadge', on);
            this._setBadge('rsWarnBadge', on && this.config.warnEnabled);
            // Live-update the dependent-card gate so toggling the master on/off
            // immediately enables/dims Warnings/Crowdsource/Data.
            this._applyMasterGate(on);
            // The floating overlay is a separate app-process service. Reconcile it
            // after either master transition, not only when the overlay preference
            // itself changes, so master OFF removes the pill immediately.
            if (typeof window.AndroidBridge !== 'undefined'
                    && typeof AndroidBridge.syncRoadSenseOverlay === 'function') {
                try { AndroidBridge.syncRoadSenseOverlay(); } catch (e) { /* best-effort */ }
            }
            this._toastSaved();
        } else {
            el.checked = !on;
            this._toastFailed();
        }
    },

    async toggleCalibrationMode() {
        const el = document.getElementById('rsCalibrationMode');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ calibrationMode: on });
        if (ok) { this.config.calibrationMode = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    /**
     * Show/hide the app-side floating RoadSense overlay. Persists roadSense.
     * overlayVisible (the daemon launch gate + the app onResume/keepalive gate both
     * read it). Display-only: detection, audio warnings, and crowdsource keep running
     * — so this never touches the master `enabled`. After persisting, nudge the native
     * side (AndroidBridge.syncRoadSenseOverlay) so the pill/card appears or disappears
     * immediately instead of on the next Activity onResume; no-op on a tunnel/browser
     * where there's no native overlay to drive.
     */
    async toggleOverlayVisible() {
        const el = document.getElementById('rsOverlayVisible');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ overlayVisible: on });
        if (ok) {
            this.config.overlayVisible = on;
            if (typeof window.AndroidBridge !== 'undefined'
                    && typeof AndroidBridge.syncRoadSenseOverlay === 'function') {
                try { AndroidBridge.syncRoadSenseOverlay(); } catch (e) { /* best-effort */ }
            }
            this._toastSaved();
        } else { el.checked = !on; this._toastFailed(); }
    },

    // Launch the native MapLibre hazard map via the AndroidBridge. In-app only
    // (the card is hidden otherwise), but guard defensively in case it's called
    // on a client without the bridge.
    openHazardMap() {
        if (typeof window.AndroidBridge !== 'undefined'
                && typeof AndroidBridge.openHazardMap === 'function') {
            try { AndroidBridge.openHazardMap(); }
            catch (e) { this._toastFailed(); }
        }
    },

    async toggleWarnEnabled() {
        const el = document.getElementById('rsWarnEnabled');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ warnEnabled: on });
        if (ok) {
            this.config.warnEnabled = on;
            this._setBadge('rsWarnBadge', this.config.enabled && on);
            this._toastSaved();
        } else { el.checked = !on; this._toastFailed(); }
    },

    /**
     * Audio channel the chime plays on. "navigation" puts it on the car's guidance
     * stream (STREAM_NAVI) so it ducks music; the daemon clamps anything unknown
     * back to navigation, so the four options here are the whole supported set.
     */
    async setWarnChannel(channel) {
        const allowed = ['navigation', 'media', 'voice', 'alarm'];
        if (allowed.indexOf(channel) < 0) return;
        const prev = this.config.warnAudioChannel;
        if (channel === prev) return;
        const el = document.getElementById('rsWarnChannel');
        const requestId = (this._warnChannelRequestId || 0) + 1;
        this._warnChannelRequestId = requestId;
        if (el) el.disabled = true;
        try {
            const ok = await this._save({ warnAudioChannel: channel });
            // A programmatic change can overlap this request even though the picker is
            // disabled. Only its newest completion may update the UI or live snapshot.
            if (requestId !== this._warnChannelRequestId) return;
            if (ok) {
                this.config.warnAudioChannel = channel;
                this._toastSaved();
            } else {
                // Revert the <select> to the still-persisted value.
                if (el) el.value = prev;
                this._toastFailed();
            }
        } finally {
            if (requestId === this._warnChannelRequestId && el) el.disabled = false;
        }
    },

    /**
     * Play the chime now via POST /api/roadsense/test-chime. The live cue only fires
     * while DRIVING on approach to a stored hazard, so this is the only way to hear it
     * (and check the channel's volume) without driving past a mapped bump. The daemon
     * uses the same playRawResource call the live cue does.
     *
     * The response means DISPATCHED, not "you heard it" — playback is async in the app
     * process — so the toast says "sent" rather than claiming playback was confirmed.
     */
    async testChime() {
        const btn = document.getElementById('rsTestChimeBtn');
        if (btn) btn.disabled = true;
        try {
            // Commit a just-dragged value before previewing it. A failed write restores
            // the last confirmed value, so the test can never imply that an unsaved
            // level will be used by live warnings.
            await this._flushWarnVolumeSave();
            const channelSelect = document.getElementById('rsWarnChannel');
            const channel = channelSelect ? channelSelect.value : this.config.warnAudioChannel;
            const volumeSlider = document.getElementById('rsWarnVolumeSlider');
            const volume = volumeSlider
                ? this._clamp(parseInt(volumeSlider.value, 10) || 75, 10, 100)
                : this.config.warnAudioVolume;
            const resp = await fetch('/api/roadsense/test-chime', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                // Send the channel the dropdown is CURRENTLY showing, so testing a channel
                // the user just picked doesn't depend on the save having landed first.
                body: JSON.stringify({
                    // Preview the severe-alert ceiling the slider controls; minor and
                    // moderate live warnings are intentionally scaled below this level.
                    severity: 'severe',
                    channel: channel,
                    volumePercent: volume
                })
            });
            const data = await resp.json();
            if (data && data.dispatched) {
                // "sent", NOT "playing": the daemon dispatches asynchronously and only ever
                // reports that the command was issued, never that a sound came out. Claiming
                // "playing" here would assert something no layer has confirmed.
                this._toast('road_sense.warn_test_playing',
                    'Chime sent at the selected RoadSense volume', 'success');
            } else {
                this._toast('road_sense.warn_test_failed', 'Could not send the chime', 'error');
            }
        } catch (e) {
            console.warn('RoadSense: test chime failed:', e);
            this._toast('road_sense.warn_test_failed', 'Could not send the chime', 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    },

    async setWarnMode(mode) {
        if (mode !== 'visual' && mode !== 'audio' && mode !== 'both') return;
        const prev = this.config.warnMode;
        // Optimistic UI — reflect immediately, revert if the write fails.
        document.querySelectorAll('#rsWarnModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === mode));
        const ok = await this._save({ warnMode: mode });
        if (ok) { this.config.warnMode = mode; this._toastSaved(); }
        else {
            document.querySelectorAll('#rsWarnModeBtns .btn-toggle').forEach(btn =>
                btn.classList.toggle('active', btn.dataset.value === prev));
            this._toastFailed();
        }
    },

    // Slider live-label is updated on every input; the durable write is
    // debounced so dragging doesn't hammer the daemon.
    updateWarnLead(value) {
        let v = parseInt(value, 10);
        if (isNaN(v)) v = 4;
        if (v < 2) v = 2; if (v > 8) v = 8;
        this.config.warnLeadSeconds = v;
        this._setLeadLabel(v);
        this._debounceSave('warnLeadSeconds', { warnLeadSeconds: v });
    },

    updateWarnConf(value) {
        let pct = parseInt(value, 10);
        if (isNaN(pct)) pct = 0;
        if (pct < 0) pct = 0; if (pct > 100) pct = 100;
        const t = pct / 100;
        this.config.warnConfidenceThreshold = t;
        this._setConfLabel(pct);
        this._debounceSave('warnConfidenceThreshold', { warnConfidenceThreshold: t });
    },

    updateWarnVolume(value) {
        let pct = parseInt(value, 10);
        if (isNaN(pct)) pct = 75;
        pct = this._clamp(pct, 10, 100);
        this.config.warnAudioVolume = pct;
        this._setWarnVolumeLabel(pct);
        this._warnVolumePending = pct;
        this._warnVolumePendingSequence = this._nextWarnVolumeSequence();
        this._warnVolumeExitFlushed = false;
        if (this._warnVolumeTimer) clearTimeout(this._warnVolumeTimer);
        const self = this;
        this._warnVolumeTimer = setTimeout(function () {
            self._warnVolumeTimer = null;
            self._flushWarnVolumeSave();
        }, 250);
    },

    _warnVolumeWriter() {
        if (this._warnVolumeWriterId) return this._warnVolumeWriterId;
        let id = '';
        try {
            const words = new Uint32Array(4);
            window.crypto.getRandomValues(words);
            for (let i = 0; i < words.length; i++) {
                id += ('00000000' + words[i].toString(16)).slice(-8);
            }
        } catch (e) {
            // Legacy WebViews without getRandomValues still get a page-unique token.
            for (let i = 0; i < 4; i++) {
                const word = ((Math.random() * 0x100000000) ^ Date.now() ^ (i * 2654435761)) >>> 0;
                id += ('00000000' + word.toString(16)).slice(-8);
            }
        }
        this._warnVolumeWriterId = id;
        return id;
    },

    _nextWarnVolumeSequence() {
        this._warnVolumeSequence = (this._warnVolumeSequence || 0) + 1;
        return this._warnVolumeSequence;
    },

    /** Range-input change fires when pointer/keyboard adjustment is committed. */
    commitWarnVolume(value) {
        this.updateWarnVolume(value);
        return this._flushWarnVolumeSave();
    },

    /**
     * Serialize volume writes, remember the last confirmed value, and restore it when
     * persistence fails. A newer drag that lands while a request is in flight is kept
     * pending and written immediately after the first request settles.
     */
    async _flushWarnVolumeSave() {
        if (this._warnVolumeTimer) {
            clearTimeout(this._warnVolumeTimer);
            this._warnVolumeTimer = null;
        }
        if (this._warnVolumeSavePromise) {
            await this._warnVolumeSavePromise;
            return this._flushWarnVolumeSave();
        }
        if (this._warnVolumePending == null) return true;

        const value = this._warnVolumePending;
        const sequence = this._warnVolumePendingSequence || this._nextWarnVolumeSequence();
        this._warnVolumePending = null;
        this._warnVolumePendingSequence = null;
        this._warnVolumeInFlightValue = value;
        this._warnVolumeInFlightSequence = sequence;
        const request = this._save({
            warnAudioVolume: value,
            warnAudioVolumeWriter: this._warnVolumeWriter(),
            warnAudioVolumeSequence: sequence
        });
        this._warnVolumeSavePromise = request;
        const ok = await request;
        this._warnVolumeSavePromise = null;
        this._warnVolumeInFlightValue = null;
        this._warnVolumeInFlightSequence = null;

        const hasNewerValue = this._warnVolumePending != null;
        if (ok) {
            this._warnVolumeConfirmed = value;
            if (!hasNewerValue) this._toastSaved();
        } else if (!hasNewerValue) {
            const confirmed = (typeof this._warnVolumeConfirmed === 'number')
                ? this._warnVolumeConfirmed : 75;
            this.config.warnAudioVolume = confirmed;
            const slider = document.getElementById('rsWarnVolumeSlider');
            if (slider) slider.value = confirmed;
            this._setWarnVolumeLabel(confirmed);
            this._toastFailed();
        }

        if (hasNewerValue) return this._flushWarnVolumeSave();
        return ok;
    },

    /**
     * Page teardown can cancel a normal fetch before the 250 ms debounce fires.
     * sendBeacon is supported by the legacy head-unit WebView and carries same-origin
     * cookies; keepalive fetch is a fallback for browsers that reject the beacon.
     */
    _flushWarnVolumeOnExit() {
        if (this._warnVolumeTimer) {
            clearTimeout(this._warnVolumeTimer);
            this._warnVolumeTimer = null;
        }
        const value = this._warnVolumePending != null
            ? this._warnVolumePending : this._warnVolumeInFlightValue;
        const sequence = this._warnVolumePending != null
            ? this._warnVolumePendingSequence : this._warnVolumeInFlightSequence;
        // visibilitychange, pagehide and beforeunload can all fire for one exit.
        // Send only the newest value once; otherwise the first handler clears pending
        // and a later handler can beacon the older in-flight value over it.
        if (value == null || sequence == null || this._warnVolumeExitFlushed) return;
        this._warnVolumeExitFlushed = true;
        // Keep the pending value intact so a page that was only backgrounded can finish
        // the normal serialized write after any older in-flight request settles.
        this._flushWarnVolumeSave();
        const body = JSON.stringify({
            section: 'roadSense',
            data: {
                warnAudioVolume: value,
                warnAudioVolumeWriter: this._warnVolumeWriter(),
                warnAudioVolumeSequence: sequence
            }
        });
        try {
            if (navigator.sendBeacon) {
                const payload = new Blob([body], { type: 'application/json' });
                if (navigator.sendBeacon('/api/settings/unified', payload)) return;
            }
        } catch (e) { /* fall through to keepalive fetch */ }
        try {
            fetch('/api/settings/unified', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: body,
                keepalive: true
            });
        } catch (e) { /* page is already leaving */ }
    },

    // Detection-sensitivity multiplier band — MUST mirror EventDetector's
    // MIN/MAX/DEFAULT_THRESHOLD_SCALE (the daemon clamps to the same band).
    DETECT_MULT_MIN: 0.7,
    DETECT_MULT_MAX: 1.3,
    DETECT_MULT_DEFAULT: 1.0,

    // The UI slider reads "Less (0%) ↔ More (100%)" sensitivity, but the stored
    // value is a threshold MULTIPLIER where LOWER = more sensitive — so the two are
    // INVERTED. 0% → MAX multiplier (least sensitive), 100% → MIN multiplier (most
    // sensitive), 50% → DEFAULT (1.0). Split the mapping around the default so the
    // midpoint is exactly 1.0 regardless of the (slightly asymmetric) band.
    _detectPctToMult(pct) {
        let p = parseInt(pct, 10);
        if (isNaN(p)) p = 50;
        if (p < 0) p = 0; if (p > 100) p = 100;
        // More sensitive (p>50) interpolates DEFAULT→MIN; less (p<50) DEFAULT→MAX.
        if (p >= 50) {
            const tMore = (p - 50) / 50;            // 0..1 toward "More"
            return this.DETECT_MULT_DEFAULT + tMore * (this.DETECT_MULT_MIN - this.DETECT_MULT_DEFAULT);
        }
        const tLess = (50 - p) / 50;                 // 0..1 toward "Less"
        return this.DETECT_MULT_DEFAULT + tLess * (this.DETECT_MULT_MAX - this.DETECT_MULT_DEFAULT);
    },

    _detectMultToPct(mult) {
        let m = parseFloat(mult);
        if (isNaN(m)) m = this.DETECT_MULT_DEFAULT;
        if (m < this.DETECT_MULT_MIN) m = this.DETECT_MULT_MIN;
        if (m > this.DETECT_MULT_MAX) m = this.DETECT_MULT_MAX;
        let pct;
        if (m <= this.DETECT_MULT_DEFAULT) {
            // DEFAULT..MIN maps to 50..100 ("More").
            const t = (this.DETECT_MULT_DEFAULT - m) / (this.DETECT_MULT_DEFAULT - this.DETECT_MULT_MIN);
            pct = 50 + t * 50;
        } else {
            // DEFAULT..MAX maps to 50..0 ("Less").
            const t = (m - this.DETECT_MULT_DEFAULT) / (this.DETECT_MULT_MAX - this.DETECT_MULT_DEFAULT);
            pct = 50 - t * 50;
        }
        return Math.round(pct / 5) * 5;          // snap to the slider's step=5
    },

    updateDetectSensitivity(value) {
        const mult = this._detectPctToMult(value);
        this.config.detectionSensitivity = mult;
        this._setDetectSensLabel(parseInt(value, 10));
        this._debounceSave('detectionSensitivity', { detectionSensitivity: mult });
    },

    _debounceSave(key, delta) {
        this._saveTimers = this._saveTimers || {};
        if (this._saveTimers[key]) clearTimeout(this._saveTimers[key]);
        const self = this;
        this._saveTimers[key] = setTimeout(function () {
            self._saveTimers[key] = null;
            self._save(delta).then(function (ok) {
                // Toast on BOTH outcomes so a slider gives the same "Saved"
                // confirmation as the toggles. Debounced (only fires ~250 ms after
                // the user stops dragging), so a continuous drag yields one toast on
                // settle, not one per input event. Shared by lead-time, confidence,
                // and detection-sensitivity; volume has confirmed-value rollback above.
                if (ok) self._toastSaved(); else self._toastFailed();
            });
        }, 250);
    },

    async toggleSeverity(level) {
        const map = {
            minor: { id: 'rsSeverityMinor', key: 'warnSeverityMinor' },
            moderate: { id: 'rsSeverityModerate', key: 'warnSeverityModerate' },
            severe: { id: 'rsSeveritySevere', key: 'warnSeveritySevere' }
        };
        const m = map[level];
        if (!m) return;
        const el = document.getElementById(m.id);
        if (!el) return;
        const on = el.checked;
        const delta = {}; delta[m.key] = on;
        const ok = await this._save(delta);
        if (ok) { this.config[m.key] = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    async toggleCrowdUpload() {
        const el = document.getElementById('rsCrowdUpload');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ crowdUpload: on });
        if (ok) { this.config.crowdUpload = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    async toggleCrowdDownload() {
        const el = document.getElementById('rsCrowdDownload');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ crowdDownload: on });
        if (ok) { this.config.crowdDownload = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    async saveWorkerUrl() {
        const input = document.getElementById('rsSyncWorkerUrl');
        if (!input) return;
        const url = (input.value || '').trim();
        const ok = await this._save({ syncWorkerUrl: url });
        if (ok) { this.config.syncWorkerUrl = url; this._toastSaved(); }
        else { this._toastFailed(); }
    },

    // ==================== Routing (BYOK) ====================
    //
    // The basemap + hazards are free; only turn-by-turn routing needs a personal
    // Valhalla key. The key is the SECRET — NavMapConfig stores it encrypted
    // on-device (same CredentialCipher scheme as BYD Cloud's password) and the
    // daemon NEVER returns it (status reports only `hasKey`). Backed by:
    //   GET  /api/navmap/routing/status  — { configured, enabled, endpoint, hasKey }
    //   POST /api/navmap/routing/setup   — { endpoint, apiKey }
    //   POST /api/navmap/routing/clear
    // All POSTs use fetch() (never XHR — the WebView drops XHR POST bodies).

    async _postRouting(url, body) {
        this._routingWriteVersion++;
        this._routingWritesPending++;
        try {
            return await this._enqueueWrite(async () => {
                try {
                    const resp = await fetch(url, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(body || {})
                    });
                    const data = await resp.json();
                    return resp.ok && data ? data : null;
                } catch (e) {
                    console.warn('RoadSense: routing write failed:', e);
                    return null;
                }
            });
        } finally {
            this._routingWritesPending--;
        }
    },

    async loadRoutingStatus() {
        if (this._routingWritesPending > 0) return;
        const writeVersion = this._routingWriteVersion;
        try {
            const resp = await fetch('/api/navmap/routing/status');
            const data = await resp.json();
            if (this._routingWritesPending > 0
                    || writeVersion !== this._routingWriteVersion) return;
            if (!data || !data.success) { this._setRoutingBadge(false); return; }
            const endpointInput = document.getElementById('rsRoutingEndpoint');
            if (endpointInput && !endpointInput.value) {
                endpointInput.value = data.endpoint || '';
            }
            // The key is write-only — never echoed back. When one is set, hint that
            // in the (empty) password placeholder instead of exposing the secret.
            const keyInput = document.getElementById('rsRoutingKey');
            if (keyInput) {
                keyInput.value = '';
                if (data.hasKey) {
                    const set = BYD.i18n.t('road_sense.routing_key_set_ph');
                    keyInput.placeholder = (set && set !== 'road_sense.routing_key_set_ph')
                        ? set : 'A key is saved — paste a new one to replace it';
                }
            }
            this._setRoutingBadge(!!data.hasKey);
        } catch (e) {
            console.warn('RoadSense: routing status failed:', e);
            if (writeVersion === this._routingWriteVersion
                    && this._routingWritesPending === 0) {
                this._setRoutingBadge(false);
            }
        }
    },

    /** Routing badge swaps between "configured" / "not configured" copy + tone. */
    // Open an external signup/docs URL in the DEVICE'S DEFAULT BROWSER.
    //  - In-app WebView (no real popup support): navigate the WebView to the URL;
    //    WebViewFragment.shouldOverrideUrlLoading intercepts the non-loopback URL
    //    and hands it to ACTION_VIEW → the default browser opens it (the WebView
    //    itself does not leave the settings page).
    //  - Tunnel/desktop browser: open a new tab (window.open _blank).
    openExternal(url) {
        try {
            var inApp = (typeof window.AndroidBridge !== 'undefined');
            if (inApp) {
                // The override fires on a top-level navigation to an external URL.
                window.location.href = url;
            } else {
                window.open(url, '_blank', 'noopener');
            }
        } catch (e) {
            try { window.open(url, '_blank'); } catch (_) {}
        }
    },

    // Stadia Maps free signup (the default Valhalla routing provider). Opens in the
    // default browser per openExternal().
    openRoutingSignup() {
        this.openExternal('https://client.stadiamaps.com/signup/');
    },

    _setRoutingBadge(hasKey) {
        const badge = document.getElementById('rsRoutingBadge');
        if (!badge) return;
        const key = hasKey ? 'road_sense.routing_status_set' : 'road_sense.routing_status_unset';
        const fallback = hasKey ? 'Routing key configured' : 'No routing key';
        const t = BYD.i18n.t(key);
        badge.textContent = (t && t !== key) ? t : fallback;
        badge.className = 'status-badge ' + (hasKey ? 'active' : 'inactive');
    },

    async saveRouting() {
        const endpointInput = document.getElementById('rsRoutingEndpoint');
        const keyInput = document.getElementById('rsRoutingKey');
        const endpoint = endpointInput ? (endpointInput.value || '').trim() : '';
        const apiKey = keyInput ? (keyInput.value || '').trim() : '';
        if (!apiKey) {
            this._toast('road_sense.routing_key_required', 'Enter a routing API key', 'error');
            return;
        }
        const btn = document.getElementById('rsRoutingSaveBtn');
        if (btn) btn.disabled = true;
        try {
            const data = await this._postRouting(
                '/api/navmap/routing/setup',
                { endpoint: endpoint, apiKey: apiKey }
            );
            if (data && data.success) {
                // Don't keep the secret in the DOM after a successful save.
                if (keyInput) keyInput.value = '';
                this._toastSaved();
                await this.loadRoutingStatus();
            } else {
                this._toastFailed();
            }
        } catch (e) {
            console.warn('RoadSense: routing save failed:', e);
            this._toastFailed();
        } finally {
            if (btn) btn.disabled = false;
        }
    },

    async clearRouting() {
        const btn = document.getElementById('rsRoutingClearBtn');
        if (btn) btn.disabled = true;
        try {
            const data = await this._postRouting('/api/navmap/routing/clear', {});
            if (data && data.success) {
                const keyInput = document.getElementById('rsRoutingKey');
                if (keyInput) {
                    keyInput.value = '';
                    const ph = BYD.i18n.t('road_sense.routing_key_ph');
                    keyInput.placeholder = (ph && ph !== 'road_sense.routing_key_ph')
                        ? ph : 'Paste your routing API key';
                }
                this._toast('road_sense.routing_cleared', 'Routing key cleared', 'success');
                await this.loadRoutingStatus();
            } else {
                this._toastFailed();
            }
        } catch (e) {
            console.warn('RoadSense: routing clear failed:', e);
            this._toastFailed();
        } finally {
            if (btn) btn.disabled = false;
        }
    },

    // ==================== Cluster projection ====================
    //
    // Projecting the map onto the driver cluster is a native on-car action — the
    // daemon's ClusterMapProjector holds the OEM projection surface. The live
    // projecting state is NOT a config value; it comes from the status endpoint.
    // The "auto-project on ACC-on" toggle IS a preference, persisted in the UCM
    // `navMap` section for the daemon to read on power-up.
    //   GET  /api/navmap/cluster/status → { success, projecting }
    //   POST /api/navmap/cluster/start  → { success, projecting }
    //   POST /api/navmap/cluster/stop   → { success, projecting }
    // All POSTs use fetch() (the in-app WebView drops XHR POST bodies).

    async loadClusterStatus() {
        // Reflect the SHARED cluster size profile (blindspot.clusterSizeProfile,
        // mirrored into config.bsClusterLayout on load) into the map-tab selector.
        var layoutSel = document.getElementById('rsClusterLayout');
        if (layoutSel) layoutSel.value = String(this.config.bsClusterLayout || 31);
        if (this._clusterProjectPending) return;
        const writeVersion = this._clusterProjectVersion;
        try {
            const resp = await fetch('/api/navmap/cluster/status');
            const data = await resp.json();
            if (this._clusterProjectPending
                    || writeVersion !== this._clusterProjectVersion) return;
            const projecting = !!(data && data.success && data.projecting);
            this._setChecked('rsClusterProject', projecting);
            this._setClusterBadge(projecting);
        } catch (e) {
            console.warn('RoadSense: cluster status failed:', e);
            if (writeVersion === this._clusterProjectVersion
                    && !this._clusterProjectPending) {
                this._setChecked('rsClusterProject', false);
                this._setClusterBadge(false);
            }
        }
    },

    // Set the SHARED cluster size profile from the Map tab. Writes
    // blindspot.clusterSizeProfile (the single key the OEM projection reads,
    // shared by map + blind-spot) so changing it here updates the blind-spot tab
    // too. The daemon dispatches relayoutCluster() on this key, so a live cluster
    // projection re-lays-out immediately. Saved immediately (no staged Apply here).
    async mapSetClusterLayout(v) {
        var n = parseInt(v, 10);
        if (!this._hydrated || (n !== 29 && n !== 30 && n !== 31)) return;
        var mapSel = document.getElementById('rsClusterLayout');
        var previous = this.config.bsClusterLayout;
        this.config.bsClusterLayout = n;
        // Keep the blind-spot tab's dropdown in sync if it's in the DOM.
        var bsSel = document.getElementById('bsClusterLayout');
        if (bsSel) bsSel.value = String(n);
        if (mapSel) mapSel.disabled = true;
        try {
            const ok = await this._bsSave({ clusterSizeProfile: n });
            if (ok) {
                this._toastSaved();
            } else {
                this.config.bsClusterLayout = previous;
                if (mapSel) mapSel.value = String(previous);
                if (bsSel) bsSel.value = String(previous);
                this._toastFailed();
            }
        } finally {
            if (mapSel) mapSel.disabled = false;
        }
    },

    /** Cluster badge swaps between "Projecting" / "Off" copy + tone. */
    _setClusterBadge(projecting) {
        const badge = document.getElementById('rsClusterBadge');
        if (!badge) return;
        const key = projecting ? 'road_sense.map_cluster_projecting' : 'road_sense.map_cluster_off';
        const fallback = projecting ? 'Projecting' : 'Off';
        const t = BYD.i18n.t(key);
        badge.textContent = (t && t !== key) ? t : fallback;
        badge.className = 'status-badge ' + (projecting ? 'active' : 'inactive');
    },

    async toggleClusterProject() {
        const el = document.getElementById('rsClusterProject');
        if (!el || !this._hydrated || el.disabled) return;
        const on = el.checked;
        this._clusterProjectVersion++;
        this._clusterProjectPending = true;
        el.disabled = true;
        try {
            const resp = await fetch(on ? '/api/navmap/cluster/start' : '/api/navmap/cluster/stop', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            if (data && data.success) {
                // Reflect the actual reported state (the endpoint returns it).
                const projecting = !!data.projecting;
                el.checked = projecting;
                this._setClusterBadge(projecting);
                this._toastSaved();
            } else {
                el.checked = !on;
                this._toastFailed();
            }
        } catch (e) {
            console.warn('RoadSense: cluster project toggle failed:', e);
            el.checked = !on;
            this._toastFailed();
        } finally {
            this._clusterProjectPending = false;
            el.disabled = false;
        }
    },

    async toggleClusterAuto() {
        const el = document.getElementById('rsClusterAuto');
        if (!el || !this._hydrated || el.disabled) return;
        const on = el.checked;
        el.disabled = true;
        try {
            // The driver cluster is a single surface — the map auto-project and the
            // Projection feature's auto-cast can't both own it on ACC-on. When turning
            // this ON while Projection auto-cast is set, confirm handing the cluster
            // over. On confirm we clear projection.autoStartOnAcc FIRST, then set our
            // own flag. Turning OFF is unconditional (frees the cluster, no sibling
            // write). No conflict / turning off → straight through, no dialog.
            const conflict = on && this.config.projectionAutoStart === true;
            if (conflict) {
                const t = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t.bind(BYD.i18n) : null;
                if (BYD.utils && BYD.utils.confirmDialog) {
                    const ok = await BYD.utils.confirmDialog({
                        title: (t && t('road_sense.map_cluster_auto_conflict_title')) || 'Projection is using the cluster',
                        body: (t && t('road_sense.map_cluster_auto_conflict_body')) || 'The Projection screen is set to auto-cast an app to the driver cluster on startup. The cluster can only show one thing at a time. Turn that off and auto-project the map instead?',
                        confirmLabel: (t && t('road_sense.map_cluster_auto_conflict_confirm')) || 'Use the map',
                        cancelLabel: (t && t('common.cancel')) || 'Cancel'
                    });
                    if (!ok) { el.checked = false; return; }
                }
            }
            // Free the cluster from Projection FIRST so the two auto-starts never
            // both fire — only needed when there was a conflict.
            if (conflict) {
                const pjData = await this._saveSection(
                    'projection', { autoStartOnAcc: false });
                if (!(pjData && pjData.success)) { el.checked = !on; this._toastFailed(); return; }
                this.config.projectionAutoStart = false;
            }
            const data = await this._saveSection(
                'navMap', { autoProjectCluster: on });
            if (data && data.success) {
                this.config.autoProjectCluster = on;
                this._toastSaved();
            }
            else { el.checked = !on; this._toastFailed(); }
        } catch (e) {
            console.warn('RoadSense: cluster auto toggle failed:', e);
            el.checked = !on;
            this._toastFailed();
        } finally {
            el.disabled = false;
        }
    },

    // ==================== Destructive actions ====================
    //
    // Two SEPARATE deletes with distinct confirms, both backed by live handlers
    // (RoadSenseApiHandler):
    //   POST /api/roadsense/delete-local  — wipe on-device calibrations + labels.
    //   POST /api/roadsense/delete-cloud  — wipe this device's uploaded rows
    //                                        (server scopes by the rotating
    //                                        roadSense.deviceId).

    async deleteLocal() {
        const msg = BYD.i18n.t('road_sense.confirm_delete_local');
        const prompt = (msg && msg !== 'road_sense.confirm_delete_local')
            ? msg
            : 'Delete all RoadSense calibrations stored on this device? This cannot be undone.';
        // Themed confirm (matches the rest of the app). The native confirm()
        // renders a white system popup that clashes with the dark Material
        // surface and leaks the loopback origin into the title bar; route
        // through BYD.utils.confirmDialog like surveillance.js does. Keep the
        // native confirm() as a fallback for very-early-init / older bundles.
        const t = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t.bind(BYD.i18n) : null;
        if (BYD.utils && BYD.utils.confirmDialog) {
            const ok = await BYD.utils.confirmDialog({
                title: (t && t('road_sense.delete_local_title')) || 'Delete local calibrations',
                body: prompt,
                confirmLabel: (t && t('road_sense.delete_local_btn')) || 'Delete local',
                cancelLabel: (t && t('common.cancel')) || 'Cancel',
                danger: true
            });
            if (!ok) return;
        } else if (typeof confirm === 'function') {
            if (!confirm(prompt)) return;
        }
        const btn = document.getElementById('rsDeleteLocalBtn');
        if (btn) btn.disabled = true;
        try {
            const resp = await fetch('/api/roadsense/delete-local', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            if (data && data.success) {
                this._toast('road_sense.delete_local_done', 'Local calibrations deleted', 'success');
            } else {
                this._toast('road_sense.delete_failed', 'Delete failed', 'error');
            }
        } catch (e) {
            console.warn('RoadSense: delete-local failed:', e);
            this._toast('road_sense.delete_failed', 'Delete failed', 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    },

    async deleteCloud() {
        const msg = BYD.i18n.t('road_sense.confirm_delete_cloud');
        const prompt = (msg && msg !== 'road_sense.confirm_delete_cloud')
            ? msg
            : 'Delete the RoadSense detections you uploaded from the shared cloud map? This cannot be undone.';
        // Themed confirm — see deleteLocal() for rationale.
        const t = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t.bind(BYD.i18n) : null;
        if (BYD.utils && BYD.utils.confirmDialog) {
            const ok = await BYD.utils.confirmDialog({
                title: (t && t('road_sense.delete_cloud_title')) || 'Delete cloud calibrations',
                body: prompt,
                confirmLabel: (t && t('road_sense.delete_cloud_btn')) || 'Delete cloud',
                cancelLabel: (t && t('common.cancel')) || 'Cancel',
                danger: true
            });
            if (!ok) return;
        } else if (typeof confirm === 'function') {
            if (!confirm(prompt)) return;
        }
        const btn = document.getElementById('rsDeleteCloudBtn');
        if (btn) btn.disabled = true;
        try {
            const resp = await fetch('/api/roadsense/delete-cloud', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            if (data && data.success) {
                this._toast('road_sense.delete_cloud_done', 'Cloud calibrations deleted', 'success');
            } else {
                this._toast('road_sense.delete_failed', 'Delete failed', 'error');
            }
        } catch (e) {
            console.warn('RoadSense: delete-cloud failed:', e);
            this._toast('road_sense.delete_failed', 'Delete failed', 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    },

    // ==================== Toast helpers ====================

    _toast(key, fallback, type) {
        if (!(BYD.utils && BYD.utils.toast)) return;
        const t = BYD.i18n.t(key);
        BYD.utils.toast((t && t !== key) ? t : fallback, type);
    },

    _toastSaved() {
        this._toast('road_sense.saved', 'Saved', 'success');
    },

    _toastFailed() {
        this._toast('road_sense.save_failed', 'Save failed', 'error');
    },

    // ==================== Blind Spot ====================

    /** Kick the native overlay service to react to the just-saved enabled/preview
     *  flag immediately. AndroidBridge is only present inside the in-app WebView;
     *  on a tunnel/browser there's no native overlay so this is a harmless no-op. */
    _bsSyncNative() {
        try {
            if (window.AndroidBridge && typeof AndroidBridge.syncBlindSpotOverlay === 'function') {
                var r = AndroidBridge.syncBlindSpotOverlay();
                // M4: the overlay needs "draw over other apps" permission; if it's
                // off the feature silently no-ops. The bridge opens the grant
                // screen and returns this marker — tell the user why.
                if (r === 'needs_overlay_permission') {
                    this._toast('road_sense.bs_needs_overlay', 'Allow "display over other apps" to show the blind-spot view', 'error');
                }
            }
        } catch (e) { /* no bridge (browser/tunnel) — service polls the flag anyway */ }
    },

    /** Persist only the blindspot section (NOT roadSense — separate top-level). */
    async _bsSave(delta) {
        const data = await this._saveSection('blindspot', delta);
        // Stash the server's reason so a caller can show it instead of the generic
        // "Save failed" — the daemon rejects e.g. an inverted speed window with a
        // localized explanation that would otherwise be dropped here.
        this._bsLastError = (data && !data.success && data.error)
            ? String(data.error) : null;
        return !!(data && data.success);
    },

    async bsToggleEnabled() {
        const on = document.getElementById('bsEnabled').checked;
        this.config.bsEnabled = on;
        this._setBadge('bsStatusBadge', on);
        // Also signal the native MainActivity to start/stop the overlay service.
        // The service polls blindspot.enabled itself, but a kick makes it instant.
        const ok = await this._bsSave({ enabled: on });
        if (ok) { this._bsSyncNative(); this._toastSaved(); }
        else { document.getElementById('bsEnabled').checked = !on; this.config.bsEnabled = !on; this._setBadge('bsStatusBadge', !on); this._toastFailed(); }
    },

    /** Select the camera merge mode: 'both' (rear+side stitch), 'side' (side
     *  camera only), or 'rear' (rear camera only). Persists immediately and takes
     *  effect live on the running view (daemon pushes it to the BS scaler). */
    async bsSetMergeMode(mode) {
        if (mode !== 'both' && mode !== 'side' && mode !== 'rear') return;
        var prev = this.config.bsMergeMode;
        if (mode === prev) return;
        this.config.bsMergeMode = mode;
        this._bsHighlightMergeMode(mode);
        this._bsReflectRotationRow(mode);
        // Same gate as the rotation rows; without this the fisheye row only
        // re-evaluated on a page reload after switching mode.
        this._bsReflectRectifyRow(mode);
        const ok = await this._bsSave({ mergeMode: mode });
        if (ok) { this._toastSaved(); }
        else {
            this.config.bsMergeMode = prev;
            this._bsHighlightMergeMode(prev);
            this._bsReflectRotationRow(prev);
            this._bsReflectRectifyRow(prev);
            this._toastFailed();
        }
    },

    /** Highlight the selected merge mode (M3 tonal selection, same pattern as the
     *  display-target / corner buttons). */
    _bsHighlightMergeMode(mode) {
        var map = { both: 'bsMergeBoth', side: 'bsMergeSide', rear: 'bsMergeRear' };
        for (var k in map) {
            var el = document.getElementById(map[k]);
            if (el) { if (k === mode) el.classList.add('active'); else el.classList.remove('active'); }
        }
    },

    /** Rotation only applies to the single-camera views — show the per-side rows for
     *  side/rear, hide them (the merged panorama can't rotate) for 'both'. PER-SIDE:
     *  there are two rotation rows (left camera = left turn / view 7; right camera =
     *  right turn / view 8) because the two cameras are mirror-imaged. */
    _bsReflectRotationRow(mode) {
        var single = (mode === 'side' || mode === 'rear');
        ['bsRotationLeftRow', 'bsRotationRightRow'].forEach(function (id) {
            var row = document.getElementById(id);
            if (row) row.style.display = single ? '' : 'none';
        });
        // Each auto-base row hangs off its side's rotation row; show it only when that
        // side is single-camera AND set to 'auto'.
        var baseL = document.getElementById('bsAutoBaseLeftRow');
        if (baseL) baseL.style.display = (single && this.config.bsRotationLeft === 'auto') ? '' : 'none';
        var baseR = document.getElementById('bsAutoBaseRightRow');
        if (baseR) baseR.style.display = (single && this.config.bsRotationRight === 'auto') ? '' : 'none';
    },

    /** Is this angle one of the free-angle presets, which bring their own framing? */
    _bsIsPresetAngle(v) { return v === 40 || v === 310; },

    /** Fisheye/lens-dewarp applies only to the single-camera views — show the row
     *  for side/rear, hide it for the merged 'both' view (libod handles that).
     *
     *  Also hidden when BOTH sides use a free-angle preset. Those framings were
     *  measured with no dewarp, so the daemon holds the setting back while one is
     *  active; leaving the control on screen would offer a slider that does nothing.
     *
     *  Gated on BOTH sides, not either: the angle is per-side but the dewarp is one
     *  global setting, so with a preset on one side and a quarter turn on the other it
     *  still does something and has to stay reachable. */
    _bsReflectRectifyRow(mode) {
        var single = (mode === 'side' || mode === 'rear');
        var bothPreset = this._bsIsPresetAngle(this.config.bsRotationLeft)
            && this._bsIsPresetAngle(this.config.bsRotationRight);
        var row = document.getElementById('bsRectifyRow');
        if (row) row.style.display = (single && !bothPreset) ? '' : 'none';
    },

    /** Live-preview the fisheye strength while dragging (label + running scaler),
     *  WITHOUT persisting on every input event. Mirrors the stitch sliders' bsTune. */
    bsRectifyInput(v) {
        var n = parseInt(v, 10);
        if (isNaN(n)) return;
        this.config.bsRectifyStrength = this._clamp(n, 0, 100);
        var l = document.getElementById('bsRectifyVal');
        if (l) l.textContent = String(this.config.bsRectifyStrength);
    },

    /** Commit the fisheye strength on release (change event). Persists to
     *  blindspot.rectifyStrength; the daemon pushes it live to the BS scaler. */
    async bsSetRectify(v) {
        var n = this._clamp(parseInt(v, 10) || 0, 0, 100);
        var prev = this.config.bsRectifyStrength;
        this.config.bsRectifyStrength = n;
        const ok = await this._bsSave({ rectifyStrength: n });
        if (ok) { this._toastSaved(); }
        else { this.config.bsRectifyStrength = prev; this._bsSetSlider('bsRectify', 'bsRectifyVal', prev); this._toastFailed(); }
    },

    // ── Conditional display: speed window + reverse suppression ────────────────
    // The speed pair is STAGED (typed freely, validated + committed on Apply) rather
    // than saved per keystroke: the two bounds constrain each other, so a half-typed
    // "6" on the way to "60" would momentarily be an inverted window — which the
    // daemon would reject and, worse, which reads as a real setting if persisted.
    // The reverse toggle is independent, so it saves immediately like the others.

    /** Upper limit for a speed bound; mirrors UnifiedConfigManager.BS_SPEED_MAX_KMH
     *  and the inputs' max attribute. */
    BS_SPEED_MAX: 300,

    /** Normalise a STORED bound to a whole 1..BS_SPEED_MAX, or 0 for "any". Matches
     *  UnifiedConfigManager.clampBsSpeedBound so the UI and the daemon gate agree on
     *  exactly which values are armed. Truncates toward zero rather than rounding,
     *  because the daemon reads the same key with optInt() — which truncates — so a
     *  hand-edited fractional 29.6 must display as the 29 the gate will use, not 30. */
    _bsNormSpeed(v) {
        var n = (typeof v === 'number') ? Math.trunc(v) : parseInt(v, 10);
        if (isNaN(n) || n <= 0 || n > this.BS_SPEED_MAX) return 0;
        return n;
    },

    /** True when the speed inputs hold an edit the user hasn't applied yet — i.e. they
     *  differ from the last-saved snapshot. Used to protect that edit from a repaint on
     *  the visibilitychange reload. Returns false before the first paint (no snapshot
     *  yet) and false for a blank field matching a stored 0, so a fresh load is never
     *  treated as pending. */
    _bsSpeedPending() {
        var s = this._bsSpeedSaved;
        if (!s) return false;
        var lo = document.getElementById('bsMinSpeed');
        var hi = document.getElementById('bsMaxSpeed');
        if (!lo || !hi) return false;
        var v = this._bsReadSpeedInputs();
        // NaN (mid-typing garbage) counts as pending: it is the user's in-progress text.
        if (isNaN(v.min) || isNaN(v.max)) return true;
        var norm = function (n) { return (n === null) ? 0 : n; };
        return norm(v.min) !== s.min || norm(v.max) !== s.max;
    },

    /** Paint both speed inputs from config + refresh the mph/any hint. */
    _bsPaintSpeedWindow() {
        var lo = document.getElementById('bsMinSpeed');
        var hi = document.getElementById('bsMaxSpeed');
        if (lo) lo.value = String(this.config.bsMinSpeedKmh);
        if (hi) hi.value = String(this.config.bsMaxSpeedKmh);
        this._bsPaintSpeedHint();
    },

    /** Read the two inputs as typed. Returns null for a genuinely EMPTY field ("any"),
     *  NaN for anything unusable, else the integer. */
    _bsReadSpeedInputs() {
        var read = function (id) {
            var el = document.getElementById(id);
            if (!el) return null;
            var raw = String(el.value).trim();
            if (raw === '') {
                // CAREFUL: for <input type=number> the value-sanitization algorithm
                // replaces any string that isn't a "valid floating-point number" with
                // "" — so "+60", "1e" and a lone "-" all read as blank while the box
                // still SHOWS the text. Treating that as "any" would silently apply 0
                // for something the user can see is not 0. Distinguish via validity:
                // a truly empty field is valid (no constraint here is required),
                // whereas sanitized-away text reports badInput.
                var bad = el.validity && el.validity.badInput;
                return bad ? NaN : null;
            }
            // "-0" is a valid number-input value and is numerically the 0 ("any")
            // sentinel, so accept it rather than reporting a range error that names no
            // cause. Any other sign/format still falls through to NaN.
            if (raw === '-0') return 0;
            return /^\d+$/.test(raw) ? parseInt(raw, 10) : NaN;
        };
        return { min: read('bsMinSpeed'), max: read('bsMaxSpeed') };
    },

    /** Echo the entered window in plain language (and in mph when the user's display
     *  unit is miles) so the km/h-only inputs aren't a trap for mph drivers. When the
     *  typed pair is INVALID this shows the reason instead: Apply is disabled in that
     *  state, so without it the row would silently describe a window the user didn't
     *  type (NaN coerced to "any") with no hint as to why nothing can be saved. */
    _bsPaintSpeedHint() {
        var el = document.getElementById('bsSpeedConv');
        if (!el) return;
        var v = this._bsReadSpeedInputs();
        var err = this._bsSpeedError(v);
        // is-error colours the shared hint slot so a validation message doesn't read as
        // just another muted description line. Cleared on every valid repaint below.
        // classList is guarded only because this helper is also driven from the
        // i18n onChange callback, which can fire before the card is in the DOM.
        if (err) {
            el.textContent = err;
            if (el.classList) el.classList.add('is-error');
            return;
        }
        if (el.classList) el.classList.remove('is-error');
        var lo = (v.min === null) ? 0 : v.min;
        var hi = (v.max === null) ? 0 : v.max;
        if (!lo && !hi) {
            el.textContent = this._t('road_sense.bs_speed_any', 'Any speed — the view always appears on the indicator.');
            return;
        }
        // BYD.units.mode starts at 'km' and is only set from /status.distanceUnit by
        // core.js's refreshStatus, which races this page's config fetch. Remember which
        // mode we painted with and re-paint once (below) if it later changes, so an mph
        // driver never keeps a km/h hint just because the config landed first.
        var mi = (window.BYD && BYD.units && BYD.units.mode === 'mi');
        this._bsHintMi = mi;
        this._bsArmUnitWatch();
        var fmt = function (kmh) {
            if (!kmh) return null;
            return mi ? (Math.round(kmh * 0.621371) + ' mph') : (kmh + ' km/h');
        };
        var a = fmt(lo), b = fmt(hi);
        if (a && b) el.textContent = this._t('road_sense.bs_speed_between', 'Shown between {a} and {b}.', { a: a, b: b });
        else if (a) el.textContent = this._t('road_sense.bs_speed_above', 'Shown at {a} and above.', { a: a });
        else el.textContent = this._t('road_sense.bs_speed_below', 'Shown up to {b}.', { b: b });
    },

    /** Watch for a late BYD.units.mode change (core.js's /status fetch resolving after
     *  ours) and re-paint the speed hint once it lands. Short, self-cancelling poll —
     *  the unit arrives on the first status refresh or not at all, so this is bounded
     *  rather than a permanent ticker. Idempotent: only one watch runs at a time. */
    _bsArmUnitWatch() {
        if (this._bsUnitWatch) return;
        var self = this;
        var tries = 0;
        this._bsUnitWatch = setInterval(function () {
            var mi = (window.BYD && BYD.units && BYD.units.mode === 'mi');
            var settled = (mi !== self._bsHintMi);
            if (settled || ++tries >= 20) {          // ~10s ceiling
                clearInterval(self._bsUnitWatch);
                self._bsUnitWatch = null;
                if (settled) self._bsPaintSpeedHint();
            }
        }, 500);
    },

    /** i18n with an English fallback + {name} interpolation applied to BOTH, so the
     *  hint stays correct before the catalog loads (t() returns null then). */
    _t(key, fallback, vars) {
        var s = null;
        try { s = (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key, vars) : null; } catch (e) { s = null; }
        if (!s || s === key) s = fallback;
        if (vars) {
            for (var n in vars) {
                if (vars.hasOwnProperty(n)) s = s.split('{' + n + '}').join(vars[n]);
            }
        }
        return s;
    },

    /** Live-refresh the hint + Apply state while typing. Nothing persists here. */
    bsSpeedInput() {
        this._bsPaintSpeedHint();
        this._bsMarkSpeedDirty();
    },

    /** Enable Apply only when the typed pair is BOTH valid and different from what's
     *  saved — so a partially-typed or inverted window can't be committed at all. */
    _bsMarkSpeedDirty() {
        var v = this._bsReadSpeedInputs();
        var s = this._bsSpeedSaved || { min: 0, max: 0 };
        var valid = this._bsSpeedError(v) === null;
        // A blank field means "any" (0) for the dirty compare, matching Apply.
        var lo = (v.min === null) ? 0 : v.min;
        var hi = (v.max === null) ? 0 : v.max;
        var dirty = valid && (lo !== s.min || hi !== s.max);
        var btn = document.getElementById('bsSpeedApplyBtn');
        if (btn) { btn.disabled = !dirty; btn.classList.toggle('has-changes', dirty); }
    },

    /** @return an error message, or null when the pair is committable. Mirrors the
     *  daemon's validateBsSpeedWindow (range + no inverted window). */
    _bsSpeedError(v) {
        var vals = [v.min, v.max];
        for (var i = 0; i < vals.length; i++) {
            var n = vals[i];
            if (n === null) continue;   // blank = "any"
            if (isNaN(n) || n < 0 || n > this.BS_SPEED_MAX) {
                return this._t('road_sense.bs_speed_err_range',
                    'Enter a whole number between 0 and {max} km/h (0 = any).',
                    { max: this.BS_SPEED_MAX });
            }
        }
        var lo = (v.min === null) ? 0 : v.min;
        var hi = (v.max === null) ? 0 : v.max;
        // 0 disarms an end, so only a both-armed pair can be inverted.
        if (lo > 0 && hi > 0 && lo > hi) {
            return this._t('road_sense.bs_speed_err_order',
                'The maximum must not be lower than the minimum.');
        }
        return null;
    },

    /** Commit the staged speed window in ONE save. Both keys always go together so
     *  the daemon validates the pair it will actually store (a single-key POST would
     *  be merged against the persisted sibling). */
    async bsApplySpeedWindow() {
        // Refuse to commit the atomic pair when the last load failed: the inputs would
        // be showing defaults, not the persisted window, so writing both keys could
        // erase a bound the user never saw. Tell them to reload rather than guess.
        if (this._bsSpeedLoaded === false) {
            var m = this._t('road_sense.bs_speed_not_loaded',
                'Could not read the saved speed range — reload the page before changing it.');
            if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(m, 'error');
            return;
        }
        var v = this._bsReadSpeedInputs();
        var err = this._bsSpeedError(v);
        if (err) {
            // Already localized by _bsSpeedError — pass it straight to the toast
            // rather than through _toast(key,...), which would re-look-up a key.
            if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(err, 'error');
            return;
        }
        var lo = (v.min === null) ? 0 : v.min;
        var hi = (v.max === null) ? 0 : v.max;
        var btn = document.getElementById('bsSpeedApplyBtn');
        if (btn) btn.disabled = true;
        var prevMin = this.config.bsMinSpeedKmh, prevMax = this.config.bsMaxSpeedKmh;
        this.config.bsMinSpeedKmh = lo;
        this.config.bsMaxSpeedKmh = hi;
        const ok = await this._bsSave({ minSpeedKmh: lo, maxSpeedKmh: hi });
        if (ok) {
            // Re-assert from the validated LOCALS, never from this.config: a reload()
            // can land during the await (a concurrent _bsSave's finally clears the
            // shared _writing flag, re-opening the visibilitychange reload gate) and
            // rewrite config back to the pre-save values. Painting from config then
            // showed 0/0 with Apply lit as "unsaved" while 60/120 was actually
            // persisted — and that Apply would silently revert the save the toast had
            // just confirmed. The locals are what the daemon accepted, so they are the
            // truth for both the inputs and the dirty baseline.
            this.config.bsMinSpeedKmh = lo;
            this.config.bsMaxSpeedKmh = hi;
            this._bsSpeedSaved = { min: lo, max: hi };
            this._bsPaintSpeedWindow();   // echo the normalised values back
            this._bsMarkSpeedDirty();
            this._toastSaved();
        } else {
            // Roll back the in-memory config but LEAVE THE INPUTS as typed: repainting
            // from config here would erase the user's numbers and (since they'd then
            // match the saved snapshot) grey out Apply, leaving no way to retry. Same
            // choice as bsApplyDisplay's failure path — stay dirty so Apply is live.
            this.config.bsMinSpeedKmh = prevMin;
            this.config.bsMaxSpeedKmh = prevMax;
            this._bsMarkSpeedDirty();
            if (btn) btn.disabled = false;
            // Prefer the daemon's own (localized) reason over "Save failed" — it names
            // the exact rejection, e.g. a cross-key inverted window the client couldn't
            // see because only one bound was in this delta.
            if (this._bsLastError && window.BYD && BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(this._bsLastError, 'error');
            } else {
                this._toastFailed();
            }
        }
    },

    /** Hide the card while reverse gear is engaged. Independent of the speed window,
     *  so it persists immediately (no Apply). */
    async bsToggleSuppressReverse() {
        var el = document.getElementById('bsSuppressReverse');
        if (!el) return;
        var on = el.checked;
        const ok = await this._bsSave({ suppressInReverse: on });
        if (ok) { this.config.bsSuppressReverse = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    /** Select a SIDE's on-screen card rotation: a quarter turn (0/90/180/270), one of
     *  the free-angle presets (40/310 — each also applies its own crop, mirror and
     *  zoom, so no other setting has to be touched), or 'auto' (direction-of-travel —
     *  the daemon holds that side's base angle moving forward and flips 180° in
     *  reverse gear). side is 'left' (view 7) or 'right' (view 8). Persists immediately
     *  and takes effect live on the running card. */
    async bsSetRotation(side, deg) {
        var isRight = (side === 'right');
        var d = (deg === 'auto') ? 'auto' : parseInt(deg, 10);
        if (d !== 0 && d !== 40 && d !== 90 && d !== 180 && d !== 270 && d !== 310 && d !== 'auto') return;
        var key = isRight ? 'bsRotationRight' : 'bsRotationLeft';
        var saveKey = isRight ? 'rotationRight' : 'rotationLeft';
        var prev = this.config[key];
        if (d === prev) return;
        this.config[key] = d;
        this._bsHighlightRotation(side, d);
        this._bsReflectAutoBaseRow(side, d);
        // Picking a preset can make the fisheye control inert, so re-evaluate its row
        // here as well — otherwise it would only update on a reload or a mode change.
        this._bsReflectRectifyRow(this.config.bsMergeMode);
        var payload = {}; payload[saveKey] = d;
        const ok = await this._bsSave(payload);
        if (ok) { this._toastSaved(); }
        else {
            this.config[key] = prev;
            this._bsHighlightRotation(side, prev);
            this._bsReflectAutoBaseRow(side, prev);
            this._bsReflectRectifyRow(this.config.bsMergeMode);
            this._toastFailed();
        }
    },

    /** Highlight the selected rotation button for one side (M3 tonal selection). */
    _bsHighlightRotation(side, deg) {
        var isRight = (side === 'right');
        var pfx = isRight ? 'bsRotR' : 'bsRotL';
        var map = { 0: pfx + '0', 40: pfx + '40', 90: pfx + '90', 180: pfx + '180',
                    270: pfx + '270', 310: pfx + '310', auto: pfx + 'Auto' };
        for (var k in map) {
            var el = document.getElementById(map[k]);
            if (el) {
                var sel = (k === 'auto') ? (deg === 'auto') : (parseInt(k, 10) === deg);
                if (sel) el.classList.add('active'); else el.classList.remove('active');
            }
        }
    },

    /** A side's forward-orientation base row is only meaningful when that side's
     *  rotation is 'auto'. */
    _bsReflectAutoBaseRow(side, rot) {
        var id = (side === 'right') ? 'bsAutoBaseRightRow' : 'bsAutoBaseLeftRow';
        var row = document.getElementById(id);
        if (row) row.style.display = (rot === 'auto') ? '' : 'none';
    },

    /** Select a side's forward-gear base orientation used by 'auto' (0/90/180/270).
     *  Reverse gear flips this 180° on-screen; persisted as rotationBaseLeft/Right. */
    async bsSetRotationBase(side, deg) {
        var isRight = (side === 'right');
        var d = parseInt(deg, 10);
        if (d !== 0 && d !== 90 && d !== 180 && d !== 270) return;
        var key = isRight ? 'bsRotationBaseRight' : 'bsRotationBaseLeft';
        var saveKey = isRight ? 'rotationBaseRight' : 'rotationBaseLeft';
        var prev = this.config[key];
        if (d === prev) return;
        this.config[key] = d;
        this._bsHighlightAutoBase(side, d);
        var payload = {}; payload[saveKey] = d;
        const ok = await this._bsSave(payload);
        if (ok) { this._toastSaved(); }
        else { this.config[key] = prev; this._bsHighlightAutoBase(side, prev); this._toastFailed(); }
    },

    /** Highlight the selected auto-base button for one side (M3 tonal selection). */
    _bsHighlightAutoBase(side, deg) {
        var isRight = (side === 'right');
        var pfx = isRight ? 'bsBaseR' : 'bsBaseL';
        var map = { 0: pfx + '0', 90: pfx + '90', 180: pfx + '180', 270: pfx + '270' };
        for (var k in map) {
            var el = document.getElementById(map[k]);
            if (el) { if (parseInt(k, 10) === deg) el.classList.add('active'); else el.classList.remove('active'); }
        }
    },

    /** Read the sliders into config + reflect labels. */
    _bsReadSliders() {
        const c = this.config;
        c.bsRearFov = parseFloat(document.getElementById('bsRearFov').value);
        c.bsSideFov = parseFloat(document.getElementById('bsSideFov').value);
        c.bsYaw = parseFloat(document.getElementById('bsYaw').value);
        c.bsRoll = parseFloat(document.getElementById('bsRoll').value);
        c.bsPitch = parseFloat(document.getElementById('bsPitch').value);
        c.bsFeather = parseFloat(document.getElementById('bsFeather').value);
        var pe = document.getElementById('bsProjExp');
        if (pe) c.bsProjExp = parseFloat(pe.value);
        var rr = document.getElementById('bsRearRoll');
        if (rr) c.bsRearRoll = parseFloat(rr.value);
        var rp = document.getElementById('bsRearPitch');
        if (rp) c.bsRearPitch = parseFloat(rp.value);
        document.getElementById('bsRearFovVal').textContent = String(c.bsRearFov);
        document.getElementById('bsSideFovVal').textContent = String(c.bsSideFov);
        document.getElementById('bsYawVal').textContent = String(c.bsYaw);
        document.getElementById('bsRollVal').textContent = String(c.bsRoll);
        document.getElementById('bsPitchVal').textContent = String(c.bsPitch);
        document.getElementById('bsFeatherVal').textContent = String(c.bsFeather);
        var pev = document.getElementById('bsProjExpVal');
        if (pev) pev.textContent = String(c.bsProjExp);
        var rrv = document.getElementById('bsRearRollVal');
        if (rrv) rrv.textContent = String(c.bsRearRoll);
        var rpv = document.getElementById('bsRearPitchVal');
        if (rpv) rpv.textContent = String(c.bsRearPitch);
    },

    /** Live-tune the in-car stitch via /api/stream/bs (in-memory, debounced).
     *  Order: hfov/sideHFov/yaw/roll/feather/projExp/vscale/pitch/rearRoll/rearPitch. */
    bsTune() {
        this._bsReadSliders();
        const c = this.config;
        if (this._bsTuneTimer) clearTimeout(this._bsTuneTimer);
        this._bsTuneTimer = setTimeout(function () {
            fetch('/api/stream/bs/' + c.bsRearFov + '/' + c.bsSideFov + '/' + c.bsYaw +
                  '/' + c.bsRoll + '/' + c.bsFeather + '/' + c.bsProjExp + '/1.0/' + c.bsPitch +
                  '/' + c.bsRearRoll + '/' + c.bsRearPitch,
                  { method: 'POST' });
        }, 120);
    },

    /** Select the display target: 'head_unit' (infotainment) or 'cluster' (driver
     *  gauge screen). Exactly one. Persists blindspot.target and re-reflects the
     *  per-target size/corner into the controls. */
    /** Reflect a target's stored size%/corner into the size+position controls, and
     *  show/hide + populate the cluster-layout dropdown (cluster target only). */
    _bsReflectTargetControls(t) {
        var cluster = (t === 'cluster');
        var pct = cluster ? this.config.bsSizePctCluster : this.config.bsSizePct;
        var cornerL = cluster ? this.config.bsCornerLeftCluster : this.config.bsCornerLeft;
        var cornerR = cluster ? this.config.bsCornerRightCluster : this.config.bsCornerRight;
        var szEl = document.getElementById('bsSize');
        var szVal = document.getElementById('bsSizeVal');
        if (szEl) szEl.value = String(pct);
        if (szVal) szVal.textContent = pct + '%';
        // PER-SIDE corner highlight (left = view 7, right = view 8).
        this._bsHighlightCorner('left', cornerL || 'tr');
        this._bsHighlightCorner('right', cornerR || 'tr');
        // Cluster layout dropdown: visible only for the cluster target.
        var row = document.getElementById('bsClusterLayoutRow');
        if (row) row.style.display = cluster ? '' : 'none';
        var sel = document.getElementById('bsClusterLayout');
        if (sel) sel.value = String(this.config.bsClusterLayout || 31);
    },

    // ── Display & placement group: STAGED edits + Apply (mirrors recording/
    //    surveillance). target / layout / size / corner only update the in-memory
    //    config + UI and mark the group dirty; nothing persists until bsApplyDisplay().
    //    This fixes the ordering bug where selecting 'cluster' saved immediately,
    //    before the layout dropdown was even visible. ──────────────────────────────

    /** STAGE the cluster projection layout (size profile 29/30/31). */
    bsSetClusterLayout(v) {
        var n = parseInt(v, 10);
        if (n !== 29 && n !== 30 && n !== 31) return;
        this.config.bsClusterLayout = n;
        this._bsMarkDirty();
    },

    /** STAGE the display target (head_unit | cluster) + reflect its controls. */
    bsSetTarget(target) {
        if (target !== 'head_unit' && target !== 'cluster') return;
        this.config.bsTarget = target;
        this._bsHighlightTarget(target);
        this._bsReflectTargetControls(target);
        this._bsMarkDirty();
    },

    /** STAGE the on-screen card size (% panel width) for the ACTIVE target. */
    bsSetSize(pct) {
        var p = parseInt(pct, 10);
        if (this.config.bsTarget === 'cluster') this.config.bsSizePctCluster = p;
        else this.config.bsSizePct = p;
        var el = document.getElementById('bsSizeVal');
        if (el) el.textContent = p + '%';
        this._bsMarkDirty();
    },

    /** STAGE a side's card corner (tl/tr/bl/br/center) for the ACTIVE target.
     *  side is 'left' (view 7 / left turn) or 'right' (view 8 / right turn). */
    bsSetCorner(side, corner) {
        var cluster = (this.config.bsTarget === 'cluster');
        var isRight = (side === 'right');
        var key = cluster
            ? (isRight ? 'bsCornerRightCluster' : 'bsCornerLeftCluster')
            : (isRight ? 'bsCornerRight' : 'bsCornerLeft');
        this.config[key] = corner;
        this._bsHighlightCorner(side, corner);
        this._bsMarkDirty();
    },

    /** Snapshot of the persisted display/placement values, for dirty-compare +
     *  revert. Taken on load and after a successful Apply. */
    _bsSnapshotDisplay() {
        var c = this.config;
        return {
            bsTarget: c.bsTarget, bsClusterLayout: c.bsClusterLayout,
            bsSizePct: c.bsSizePct,
            bsCornerLeft: c.bsCornerLeft, bsCornerRight: c.bsCornerRight,
            bsSizePctCluster: c.bsSizePctCluster,
            bsCornerLeftCluster: c.bsCornerLeftCluster, bsCornerRightCluster: c.bsCornerRightCluster
        };
    },

    /** Enable/disable the Apply button based on whether the display/placement group
     *  differs from the last-saved snapshot. */
    _bsMarkDirty() {
        var s = this._bsDisplaySaved || {};
        var c = this.config;
        var dirty = (c.bsTarget !== s.bsTarget) || (c.bsClusterLayout !== s.bsClusterLayout)
            || (c.bsSizePct !== s.bsSizePct)
            || (c.bsCornerLeft !== s.bsCornerLeft) || (c.bsCornerRight !== s.bsCornerRight)
            || (c.bsSizePctCluster !== s.bsSizePctCluster)
            || (c.bsCornerLeftCluster !== s.bsCornerLeftCluster) || (c.bsCornerRightCluster !== s.bsCornerRightCluster);
        this._bsDisplayDirty = dirty;
        var btn = document.getElementById('bsApplyBtn');
        if (btn) { btn.disabled = !dirty; btn.classList.toggle('has-changes', dirty); }
    },

    /** Commit the staged display/placement group in ONE save, then push to the
     *  daemon (target retarget + layout relayout happen daemon-side off the unified
     *  POST). Persists per-target geometry presets too. */
    async bsApplyDisplay() {
        if (!this._bsDisplayDirty) return;
        var c = this.config;
        var btn = document.getElementById('bsApplyBtn');
        if (btn) { btn.disabled = true; }
        // Build per-target geometry presets so the daemon recomputes px from the
        // live panel; include target + cluster layout in the same delta.
        // PER-SIDE corners persist as cornerLeft/cornerRight inside each geometry
        // object. Keep the legacy single `corner` in sync with the LEFT side so an
        // older daemon (or a fallback read) still gets a sensible value.
        var delta = {
            target: c.bsTarget,
            clusterSizeProfile: c.bsClusterLayout,
            geometry: {
                sizePct: c.bsSizePct,
                corner: c.bsCornerLeft,
                cornerLeft: c.bsCornerLeft,
                cornerRight: c.bsCornerRight
            },
            geometryCluster: {
                sizePct: c.bsSizePctCluster,
                corner: c.bsCornerLeftCluster,
                cornerLeft: c.bsCornerLeftCluster,
                cornerRight: c.bsCornerRightCluster
            }
        };
        var ok = await this._bsSave(delta);
        if (ok) {
            this._bsSyncNative();
            this._bsDisplaySaved = this._bsSnapshotDisplay();
            this._bsDisplayDirty = false;
            if (btn) btn.classList.remove('has-changes');
            this._toastSaved();
        } else {
            if (btn) { btn.disabled = false; }   // leave dirty so the user can retry
            this._toastFailed();
        }
    },

    /** Highlight the currently-selected corner button for one side (M3 tonal
     *  selection). side is 'left' (view 7) or 'right' (view 8); button ids are
     *  suffixed L/R (bsCornerLTl / bsCornerRTl / …). */
    _bsHighlightCorner(side, corner) {
        var sfx = (side === 'right') ? 'R' : 'L';
        var map = {
            tl: 'bsCorner' + sfx + 'Tl', tr: 'bsCorner' + sfx + 'Tr',
            bl: 'bsCorner' + sfx + 'Bl', br: 'bsCorner' + sfx + 'Br',
            center: 'bsCorner' + sfx + 'Center'
        };
        for (var k in map) {
            var el = document.getElementById(map[k]);
            if (el) { if (k === corner) el.classList.add('active'); else el.classList.remove('active'); }
        }
    },

    /** Start the live debug preview on the car screen: set debugPreview flag +
     *  debugView side, select the stream view so the overlay paints. */
    async bsPreview(mode) {
        // H2: the flag MUST persist before we hijack the global stream to 7/8.
        // If the UCM write fails (EACCES-from-app-UID is a documented risk), the
        // service never shows the overlay (tick takes the hide branch) and won't
        // restore the stream (streamWarmedView stays -1) — leaving the stream
        // stuck on a blind-spot view with no preview and no error. Bail first.
        const ok = await this._bsSave({ debugPreview: true, debugView: mode });
        if (!ok) { this._toastFailed(); return; }
        this._bsPreviewActive = true;
        // Drive the DEDICATED blind-spot pipeline (port 8889), not the shared
        // live-view stream. The overlay renders the BS lane, so the side must be
        // set on /api/bs/view; the old /api/stream/view/{7,8} switched a different
        // (shared) scaler the overlay never shows — so "switch to right" stayed
        // on whatever side the BS lane was last on (left).
        try { await fetch('/api/bs/view/' + mode, { method: 'POST' }); } catch (e) {}
        // Push current slider values immediately so the preview matches the UI.
        this.bsTune();
        this._bsSyncNative();
    },

    async bsPreviewStop() {
        this._bsPreviewActive = false;
        const ok = await this._bsSave({ debugPreview: false });
        if (!ok) this._toastFailed();   // L6: surface a stuck-preview write failure
        this._bsSyncNative();
    },

    async bsSave() {
        this._bsReadSliders();
        const c = this.config;
        const ok = await this._bsSave({
            rearFov: c.bsRearFov, sideFov: c.bsSideFov, yaw: c.bsYaw,
            roll: c.bsRoll, pitch: c.bsPitch, feather: c.bsFeather,
            projExp: c.bsProjExp, rearRoll: c.bsRearRoll, rearPitch: c.bsRearPitch
        });
        if (ok) this._toastSaved(); else this._toastFailed();
    },

    async bsResetDefaults() {
        const c = this.config;
        c.bsRearFov = 1.66; c.bsSideFov = 1.98; c.bsYaw = 1.23;
        c.bsRoll = 0.25; c.bsPitch = -0.275; c.bsFeather = 0.38;
        c.bsProjExp = 1.0; c.bsRearRoll = 0.0; c.bsRearPitch = 0.0;
        this._bsSetSlider('bsRearFov', 'bsRearFovVal', c.bsRearFov);
        this._bsSetSlider('bsSideFov', 'bsSideFovVal', c.bsSideFov);
        this._bsSetSlider('bsYaw', 'bsYawVal', c.bsYaw);
        this._bsSetSlider('bsRoll', 'bsRollVal', c.bsRoll);
        this._bsSetSlider('bsPitch', 'bsPitchVal', c.bsPitch);
        this._bsSetSlider('bsFeather', 'bsFeatherVal', c.bsFeather);
        this._bsSetSlider('bsProjExp', 'bsProjExpVal', c.bsProjExp);
        this._bsSetSlider('bsRearRoll', 'bsRearRollVal', c.bsRearRoll);
        this._bsSetSlider('bsRearPitch', 'bsRearPitchVal', c.bsRearPitch);
        this.bsTune();
        await this.bsSave();
    }
};

// Alias mirroring RecSettings / SurvSettings naming.
window.RoadSenseSettings = BYD.roadSense;
