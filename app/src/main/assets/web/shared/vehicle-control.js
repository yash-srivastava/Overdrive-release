/**
 * Vehicle Control — VFX Engine
 * Three.js car with GSAP energy-based animations
 * State sync with BYD vehicle APIs
 *
 * Compatibility: Chrome 58+ (BYD DiLink Android 7.1 WebView)
 * - No ES modules, no import maps, no optional chaining, no nullish coalescing
 * - Uses UMD globals: THREE, THREE.GLTFLoader, THREE.OrbitControls, gsap
 *
 * LITE MODE: when vehicle-control.html boots with window.VC_LITE (persisted
 * preference 'vc.viewMode' != 'immersive'), none of the vendor globals exist.
 * init() takes a controls-only path — no scene, no model, no render loop —
 * and every control still works (they're plain DOM + fetch).
 */

// ---- Lite-mode THREE stub ----
// All VFX entry points already no-op without a loaded car model, but several
// click handlers construct `new THREE.Color(...)` inline as ARGUMENTS —
// evaluated before the callee's guard can bail. This inert Color stub absorbs
// those constructions in lite mode; nothing else from THREE is reachable
// (initThreeJS / loadModel / animate are skipped entirely). Scoped strictly
// to VC_LITE so immersive keeps real failure detection ("3D engine failed to
// load") when the vendor bundle is genuinely missing.
if (window.VC_LITE && typeof THREE === 'undefined') {
    window.THREE = {
        _vcLiteStub: true,
        Color: function () {
            this.convertSRGBToLinear = function () { return this; };
            this.copy = function () { return this; };
        }
    };
}

var VC = {
    // Three.js core (initialized in init())
    scene: null,
    camera: null,
    renderer: null,
    controls: null,
    carModel: null,

    // Materials (initialized in initThreeJS())
    baseColor: null,
    bodyPaintMeshes: [],

    // State
    vehicleState: {
        locked: null,
        lockScope: 'unknown',
        lockSource: null,
        trunkOpen: false,
        doors: { lf: 1, rf: 1, lr: 1, rr: 1, trunk: -1, hood: -1 },
        windows: { lf: 0, rf: 0, lr: 0, rr: 0, sunroof: 0, sunshade: 0 },
        lights: { dayTimeLight: false, ambientColour: 1, ambientOptions: [] },
        adas: { speedLimitWarning: false },
        setting: { childPresenceDetection: false },
        soc: 0,
        rangeKm: 0,
        cloudConfigured: false,
        cloudState: 'checking',
        acOn: false,
        acTemp: 22,
        acFan: 3,
        seatHeat: [0, 0],  // [driver, passenger]; 0=off, 1=low, 2=high
        seatCool: [0, 0],
        // null = not read yet. The wheel heater is on/off only (no low/high), and
        // an unread state must not render as "off" — see updateSteeringHeatUI.
        steeringHeat: null,
        // Cloud-only readback; null until a fresh snapshot reports it.
        batteryHeat: null,
        acChargeCurrentLimit: {
            state: null,
            supported: null,
            available: false,
            checked: false
        }
    },

    pollInterval: null,
    cloudStatusInterval: null,
    cloudLockInterval: null,
    acChargeCurrentInterval: null,
    _toastTimer: null,
    // Command and fetch generations keep an older asynchronous result from
    // overwriting a newer action or a user edit.
    _windowCommandRevisions: {},
    _climatePowerRevision: 0,
    _climateTempRevision: 0,
    _climateFanRevision: 0,
    _climatePending: { power: 0, temp: 0, fan: 0 },
    _seatCommandRevision: 0,
    _seatPending: 0,
    _steeringHeatRevision: 0,
    _steeringHeatPending: 0,
    _batteryHeatRevision: 0,
    _batteryHeatPending: 0,
    _climateScheduleRevision: 0,
    _climateScheduleFetchRevision: 0,
    _climateScheduleDirty: false,
    _climateSchedulePending: null,
    _chargeCapRevision: 0,
    _chargeCapFetchRevision: 0,
    _chargeCapPendingRevision: 0,
    _acChargeCurrentRevision: 0,
    _acChargeCurrentFetchRevision: 0,
    _acChargeCurrentPendingRevision: 0,
    _chargingScheduleRevision: 0,
    _chargingScheduleFetchRevision: 0,
    _scheduleDirty: false,
    _smartChargePending: null,
    stateGlows: {},  // persistent glow lights keyed by position name
    _3dViewActive: false,
    _skySphere: null,
    _videoTexture: null,

    // Color presets — realistic car paint colors.
    // Hex values are sRGB. applyColor() converts to linear before assigning
    // to the material so the renderer's sRGB output encoding lands the final
    // pixel back at this hex on screen — without that step a saturated red
    // like #C8102E renders pinkish.
    colorPresets: [
        { name: 'Aurora White', hex: '#E8E8EC' },
        { name: 'Cosmos Black', hex: '#1A1A1E' },
        { name: 'Atlantic Blue', hex: '#1E3A5F' },
        { name: 'Deepsea Green', hex: '#1B4D3E' },
        { name: 'Cherry Red',   hex: '#C8102E' },
        { name: 'Storm Grey',   hex: '#5C5C66' }
    ],

    // Active 3D model id — populated from manifest in loadSavedModel().
    // The default 'seal' is bundled in the APK; everything else is downloaded on demand
    // and persisted on the device, so re-selecting a model after first download is instant.
    activeModelId: 'seal',
    manifest: null,
    _downloadPollTimer: null,
    // Monotonic generation tag. Bumped on every loadModel() so async callbacks from
    // an earlier load (network fetch, GLTF parse, retry) can detect they're stale
    // and no-op. Without this, switching models rapidly can let an old model's
    // loader.load() callback overwrite the newer one.
    _loadGen: 0,

    // ==================== INITIALIZATION ====================

    init: function() {
        var self = this;
        // Lite = controls-only boot. Set FIRST so every later branch (and
        // any handler that fires early) can consult it.
        this.liteMode = !!window.VC_LITE;
        // Model ids are global, but their badges can be market-specific
        // (for example, Seagull is sold as Dolphin Mini in Brazil). Refresh
        // the already-built picker when the user changes the web locale.
        if (BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            BYD.i18n.onChange(function() {
                self.refreshModelPickerNames();
                // These labels are live state, not static translated copy.
                // Re-render after hydration so the i18n pass can never reset
                // them to the HTML's initial "Checking" / "Unknown" text.
                self.updateHUD();
                self.updateCloudIndicator();
                self.updateCloudControlAvailability();
            });
        }
        if (this.liteMode) {
            this.initLiteMode();
        } else {
            // Default: Aurora White (converted to linear so it matches the rest
            // of the colour pipeline; see applyColor() for the rationale).
            this.baseColor = new THREE.Color(0xE8E8EC).convertSRGBToLinear();
            this.initThreeJS();
            this.initColorPicker();
        }
        this.bindControls();
        this.startStateSync();
        this.startAcChargeCurrentSync();
        this.startCloudStatusSync();
        this.requestCloudLockRefresh();
        this.startCloudLockSync();
        if (!this.liteMode) {
            this.animate();
            this.init3dButton();
        }
        this.initCloudModal();
        this.initVisibilitySync();
        this.initViewModeToggle();
        // Lite stops here: no appearance manifest, no persisted-model fetch,
        // no GLB download — the entire block below belongs to the 3D scene.
        if (this.liteMode) return;

        // Vehicle appearance (model + color) is stored unified server-side so AVN
        // and phone-over-tunnel access show the same car. Fetch manifest + persisted
        // selection in parallel, then apply both before kicking off the GLB load —
        // this avoids a flash of "Aurora White Seal" before the saved choice arrives.
        var manifestDone = false, selectedDone = false;
        var manifest = null, selected = null;

        function applyWhenReady() {
            if (!manifestDone || !selectedDone) return;
            self.manifest = manifest || {
                version: 0,
                'default': 'seal',
                models: [{ id: 'seal', name: 'BYD Seal', file: 'seal.glb', bundled: true }]
            };
            self.initModelPicker();
            // Apply persisted color BEFORE loading the model so the model's body-paint
            // traversal picks up the saved baseColor on first paint, no recolor flash.
            if (selected && selected.color) {
                self.applyColor(selected.color, true);
            }
            // Server has already validated modelId against the manifest, so trust the response.
            var chosenId = (selected && selected.modelId) || self.manifest['default'] || 'seal';
            var sel = document.getElementById('modelPicker');
            if (sel) sel.value = chosenId;
            self.loadModel(chosenId);
        }

        this.ModelStore.loadManifest(function(m) {
            manifest = m; manifestDone = true; applyWhenReady();
        });
        this._fetchSelected(function(s) {
            selected = s; selectedDone = true; applyWhenReady();
        });

        // Background revalidate against the GitHub release. If the remote manifest
        // version > what we just rendered, swap it in and re-render the dropdown.
        // We deliberately do NOT kick off a model reload here — the user's current
        // selection is still valid; if they want the new model they'll pick it.
        this._kickManifestRefresh();
    },

    // ==================== LITE MODE ====================

    /**
     * Controls-only viewport chrome. The html.vc-mode-lite CSS already
     * swaps the canvas for the static hero before first paint; this hides
     * the engine-owned affordances (loading overlay, paint/model pickers,
     * 3D surround toggle) that have no meaning without a scene. Inline
     * styles as belt-and-braces alongside the CSS rules.
     */
    initLiteMode: function() {
        var loading = document.getElementById('vcLoading');
        if (loading) loading.style.display = 'none';
        var hide = ['colorPicker', 'modelPicker', 'btn3dView'];
        for (var i = 0; i < hide.length; i++) {
            var el = document.getElementById(hide[i]);
            if (el) el.style.display = 'none';
        }
    },

    /**
     * Lite ↔ immersive switcher. Persists to localStorage 'vc.viewMode'
     * (read by the vehicle-control.html bootstrap on every load) then does
     * a full reload — the honest switch: the vendor bundle is loaded (or
     * dropped) and the page boots cleanly in the target mode, with no
     * half-torn GL context or injected-script ordering races. Null-safe:
     * without the button (older cached HTML) nothing binds and both modes
     * keep working.
     */
    initViewModeToggle: function() {
        var self = this;
        var btn = document.getElementById('btnViewMode');
        if (!btn) return;
        var label = document.getElementById('btnViewModeLabel');
        if (label) label.textContent = this.liteMode ? 'Immersive' : 'Lite';
        btn.title = this.liteMode
            ? 'Switch to immersive 3D view'
            : 'Switch to lightweight view';
        btn.addEventListener('click', function() {
            try {
                localStorage.setItem(
                    'vc.viewMode', self.liteMode ? 'immersive' : 'lite');
            } catch (e) {}
            location.reload();
        });
    },

    _kickManifestRefresh: function() {
        var self = this;
        this.ModelStore.refreshManifest(
            function onChanged(newManifest) {
                self.manifest = newManifest;
                self.initModelPicker();
                // initModelPicker rebuilds <option>s and resets selection to the
                // first entry; restore the dropdown's active value.
                var sel = document.getElementById('modelPicker');
                if (sel && self.activeModelId) sel.value = self.activeModelId;
            },
            function onResult(stale) {
                self._setStale(stale);
            }
        );
    },

    /**
     * Toggle the stale indicator on the model dropdown. Stale means the most recent
     * remote-manifest refresh failed (network down, GitHub unreachable, malformed
     * response). The bundled or previously-cached manifest is still working — this
     * only signals that newly-released models may not be visible yet.
     */
    _setStale: function(stale) {
        var sel = document.getElementById('modelPicker');
        if (!sel) return;
        if (stale) sel.classList.add('stale');
        else sel.classList.remove('stale');
    },

    _fetchSelected: function(cb) {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', '/api/models/selected', true);
        xhr.timeout = 5000;
        xhr.onload = function() {
            if (xhr.status >= 200 && xhr.status < 300) {
                try { cb(JSON.parse(xhr.responseText)); return; } catch(e) {}
            }
            cb(null);
        };
        xhr.onerror = function() { cb(null); };
        xhr.ontimeout = function() { cb(null); };
        xhr.send();
    },

    _saveSelected: function(patch) {
        // Fire-and-forget: a failed save just means next reload reverts. We don't
        // block the UI on the round-trip so the user feels the click immediately.
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/models/selected', true);
            xhr.setRequestHeader('Content-Type', 'application/json');
            xhr.send(JSON.stringify(patch));
        } catch(e) {}
        // Sidebar EV card mirrors the same /api/models/selected. Tell it
        // to re-fetch so the silhouette + paint colour update instantly,
        // without waiting for a page navigation.
        try {
            if (window.OverdriveAppShell && typeof window.OverdriveAppShell.refreshVehicle === 'function') {
                window.OverdriveAppShell.refreshVehicle();
            }
        } catch(e) {}
    },

    initThreeJS: function() {
        var self = this;

        this.scene = new THREE.Scene();

        // Compact screens keep a wider lens so the car leaves room for
        // overlays. Wide in-car displays use a calmer showroom perspective.
        var isMobile = window.innerWidth < 768;
        var isCompact = isMobile && !window.AndroidBridge;
        // A slightly narrower showroom lens on wide in-car displays makes
        // three-quarter views easier to read and reduces near-side distortion.
        var fov = isCompact ? 50 : 45;
        // Size the renderer to the CANVAS box, not the full window — the
        // sidebar (260px on desktop) eats the left edge, and rendering at
        // window-width pushes the car's visual centre off to the left of
        // the visible area. Reading the canvas's CSS box keeps the car
        // centred in whatever screen space is actually visible.
        var canvasEl = document.getElementById('vehicleCanvas');
        var canvasRect = canvasEl.getBoundingClientRect();
        var renderW = canvasRect.width  || window.innerWidth;
        var renderH = canvasRect.height || window.innerHeight;
        this.camera = new THREE.PerspectiveCamera(
            fov, renderW / renderH, 0.1, 1000
        );
        if (window.AndroidBridge) {
            this.camera.position.set(4.6, 2.8, 6.0);
        } else {
            this.camera.position.set(isCompact ? 5.0 : 4, isCompact ? 3.0 : 2.5, isCompact ? 6.5 : 5);
        }

        this.renderer = new THREE.WebGLRenderer({
            canvas: canvasEl,
            antialias: true,
            alpha: true,
            powerPreference: 'high-performance'
        });
        // The AVN stretches the native WebView to the physical display after
        // page layout. Rendering at its reported devicePixelRatio wastes GPU
        // fill-rate without adding visible detail; phones retain high DPI.
        this.renderer.setPixelRatio(window.AndroidBridge
            ? 1
            : Math.min(window.devicePixelRatio, 2));
        this.renderer.setSize(renderW, renderH, false);
        if (window.AndroidBridge && window.console) {
            console.log('[VC perf] canvas css=' + renderW + 'x' + renderH
                + ' buffer=' + canvasEl.width + 'x' + canvasEl.height
                + ' dpr=' + window.devicePixelRatio
                + ' cameraAspect=' + this.camera.aspect.toFixed(4));
        }
        // Read the clear colour from the active theme so the 3D viewport
        // matches the surrounding chrome under both light and dark themes.
        // Was previously hardcoded #0F0F12 which left the car silhouette
        // sitting in a black box on a light-themed page.
        this.renderer.setClearColor(this._readCanvasClearColor(), 1);
        this.renderer.outputEncoding = THREE.sRGBEncoding;
        this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
        this.renderer.toneMappingExposure = 1.2;

        this.controls = new THREE.OrbitControls(this.camera, this.renderer.domElement);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.08;
        this.controls.minDistance = 3;
        this.controls.maxDistance = 12;
        // Lock vertical rotation — keep camera above the car, no going underneath
        this.controls.minPolarAngle = Math.PI * 0.2;  // ~36° from top (don't go fully overhead)
        this.controls.maxPolarAngle = Math.PI * 0.48;  // ~86° (just above horizon, never below car)
        this.controls.enablePan = false;  // No panning — car stays centered
        this.controls.autoRotate = true;
        this.controls.autoRotateSpeed = 0.3;

        this.controls.addEventListener('start', function() {
            self.controls.autoRotate = false;
        });

        // Scene lighting — enhance the model's own materials
        this.addLighting();
        this.addGroundGrid();

        window.addEventListener('resize', function() { self.onResize(); });
        this._watchCanvasSize();

        // React to theme changes so the renderer's clear colour stays in
        // sync with the rest of the UI. The Android shell sets data-theme
        // on every page-load and again from the live theme picker; we
        // observe the attribute so the WebGL canvas flips without a reload.
        this._themeObserver = new MutationObserver(function () {
            if (!self.renderer) return;
            self.renderer.setClearColor(self._readCanvasClearColor(), 1);
        });
        this._themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-theme']
        });
    },

    /**
     * Resolve the canvas clear colour from the active --vc-canvas-bg token,
     * which itself maps to --bg-base in the shared palette. Returns a 0xRRGGBB
     * integer compatible with THREE.WebGLRenderer.setClearColor. Falls back
     * to the legacy dark color if the token can't be resolved (e.g. CSS not
     * yet applied on first frame).
     */
    _readCanvasClearColor: function () {
        try {
            var s = getComputedStyle(document.documentElement);
            var raw = (s.getPropertyValue('--vc-canvas-bg') || '').trim();
            if (!raw) raw = (s.getPropertyValue('--bg-base') || '').trim();
            // Hex literal — strip leading # and parse the 6 hex digits.
            var m = raw.match(/^#([0-9a-fA-F]{6})$/);
            if (m) return parseInt(m[1], 16);
            // Three-digit hex (#abc → #aabbcc).
            m = raw.match(/^#([0-9a-fA-F]{3})$/);
            if (m) {
                var t = m[1];
                return parseInt(t[0] + t[0] + t[1] + t[1] + t[2] + t[2], 16);
            }
            // rgb() / rgba() — pull the three channels.
            m = raw.match(/^rgba?\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i);
            if (m) {
                return ((+m[1]) << 16) | ((+m[2]) << 8) | (+m[3]);
            }
        } catch (e) { /* fall through */ }
        return 0x0F0F12;
    },

    /**
     * The native shell hides the web sidebar after onPageFinished, after this
     * page has already initialised Three.js. That expands the canvas without a
     * window resize, so the old backing buffer was stretched across the new
     * box. Observe the real content box and keep renderer + camera in sync.
     * A short poll covers WebViews older than ResizeObserver.
     */
    _watchCanvasSize: function() {
        var self = this;
        var canvas = this.renderer && this.renderer.domElement;
        if (!canvas) return;

        var sync = function() {
            if (!self.renderer || !self.camera) return;
            var rect = canvas.getBoundingClientRect();
            var w = Math.round(rect.width);
            var h = Math.round(rect.height);
            if (w < 1 || h < 1) return;
            if (w !== self._canvasCssW || h !== self._canvasCssH) {
                self._canvasCssW = w;
                self._canvasCssH = h;
                self.onResize();
                if (window.AndroidBridge && window.console) {
                    console.log('[VC perf] resized css=' + w + 'x' + h
                        + ' buffer=' + canvas.width + 'x' + canvas.height
                        + ' cameraAspect=' + self.camera.aspect.toFixed(4));
                }
            }
        };

        this._canvasCssW = Math.round(canvas.getBoundingClientRect().width);
        this._canvasCssH = Math.round(canvas.getBoundingClientRect().height);

        if (window.ResizeObserver) {
            this._canvasResizeObserver = new ResizeObserver(sync);
            this._canvasResizeObserver.observe(canvas);
        }

        var checksLeft = 30;
        var poll = function() {
            sync();
            checksLeft--;
            if (checksLeft > 0) setTimeout(poll, 100);
        };
        setTimeout(poll, 0);
    },

    addLighting: function() {
        // Environment lighting for PBR materials. Refs stashed on `this` so the
        // surround-bowl path can dim them — a fully-lit showroom car parked on
        // top of live AVM footage reads as fake; biased toward bowl ambient it
        // reads as "in the scene". See _setLightsForBowl.
        var ambient = new THREE.HemisphereLight(0x88aacc, 0x222244, 1.0);
        this.scene.add(ambient);

        // Key light — strong top-front
        var keyLight = new THREE.DirectionalLight(0xffffff, 1.2);
        keyLight.position.set(5, 8, 5);
        this.scene.add(keyLight);

        // Fill light
        var fillLight = new THREE.DirectionalLight(0x8899bb, 0.6);
        fillLight.position.set(-5, 4, -3);
        this.scene.add(fillLight);

        // Rim light from below — cyberpunk floor glow in selected color
        var rimLight = new THREE.PointLight(0x00E5FF, 0.6, 15);
        rimLight.position.set(0, -1.5, 0);
        this.scene.add(rimLight);
        this.rimLight = rimLight;

        // Back accent
        var backLight = new THREE.DirectionalLight(0x6644aa, 0.3);
        backLight.position.set(0, 3, -6);
        this.scene.add(backLight);

        // Save originals so the bowl path can scale them down and restore on
        // exit. For the HemisphereLight we also stash the original colours
        // since the AVM-derived sky-tint sampler tints them while bowl is up.
        this._sceneLights = [
            { light: ambient,   base: 1.0,
              origColor: ambient.color.clone(),
              origGroundColor: ambient.groundColor.clone() },
            { light: keyLight,  base: 1.2 },
            { light: fillLight, base: 0.6 },
            { light: rimLight,  base: 0.6 },
            { light: backLight, base: 0.3 }
        ];
    },

    /**
     * Bias scene lighting for surround-bowl mode. When the bowl is up, we want
     * the car to read as parked INSIDE the live AVM scene, not as a render
     * dropped on top of it — so the showroom-grade ambient/key/fill drops to
     * ~30%, killing the "studio model on a video" mismatch. Restores originals
     * on exit. Idempotent.
     */
    _setLightsForBowl: function(active) {
        if (!this._sceneLights) return;
        var scale = active ? 0.32 : 1.0;
        for (var i = 0; i < this._sceneLights.length; i++) {
            var entry = this._sceneLights[i];
            entry.light.intensity = entry.base * scale;
            // On exit, restore HemisphereLight's original sky/ground colours
            // — the AVM-derived sampler tints them while bowl is up.
            if (!active && entry.origColor && entry.light.isHemisphereLight) {
                entry.light.color.copy(entry.origColor);
                if (entry.origGroundColor) {
                    entry.light.groundColor.copy(entry.origGroundColor);
                }
            }
        }
    },

    addGroundGrid: function() {
        var gridHelper = new THREE.GridHelper(20, 40, 0x1a1a2e, 0x1a1a2e);
        gridHelper.position.y = -0.01;
        gridHelper.material.opacity = 0.15;
        gridHelper.material.transparent = true;
        this.scene.add(gridHelper);
        this._groundGrid = gridHelper;
    },

    loadModel: function(modelId) {
        var self = this;
        modelId = modelId || this.activeModelId || 'seal';
        this.activeModelId = modelId;
        this._modelLoadStartedAt = Date.now();
        var gen = ++this._loadGen;

        // Sanity check — if Three.js failed to load (e.g. local extraction failed),
        // bail with a clear message instead of throwing in the loader constructor.
        if (typeof THREE === 'undefined' || !THREE.GLTFLoader) {
            this._showModelError('3D engine failed to load. Tap Retry to reload.');
            return;
        }

        // Show the loading overlay even on a hot model swap — the spinner doubles as
        // the download progress indicator for non-bundled models.
        var loadingEl = document.getElementById('vcLoading');
        if (loadingEl) loadingEl.classList.remove('hidden');
        var vpLoading = document.querySelector('.vc-viewport');
        if (vpLoading) vpLoading.setAttribute('data-model-loading', 'true');
        var spinner = document.querySelector('.vc-loading-spinner');
        if (spinner) spinner.style.display = '';
        var retryBtn = document.getElementById('vcLoadingRetry');
        if (retryBtn) retryBtn.style.display = 'none';
        var textEl = document.querySelector('.vc-loading-text');
        if (textEl) textEl.textContent = BYD.i18n.t('vehicle.loading_model');

        // If 3D surround view is active, exit it cleanly before swapping. The bowl
        // shader, video stream, contact shadow, and orbit constraints are all tied
        // to the *current* car's pose; leaving them up while we swap would mismatch
        // the new model's bounding box and leave us with the old car's saved camera.
        // Pass skipFlyOut=true so we don't waste a 0.7s cinematic animation on a
        // car that's about to be replaced.
        if (this._3dViewActive) {
            this.stop3dView(true);
        }

        // Drop the previous model & its meshes so we don't leak materials when swapping.
        // bodyPaintMeshes is rebuilt below as the new model is traversed.
        this._disposeCarModel();

        // Track whether load completed; arm a hard timeout so the spinner can never spin forever.
        // BYD AVN networks can stall mid-download with no error event; without this the UI hangs.
        this._modelLoadComplete = false;
        if (this._modelLoadTimeout) clearTimeout(this._modelLoadTimeout);
        this._modelLoadTimeout = setTimeout(function() {
            if (!self._modelLoadComplete) {
                self._showModelError('Model load timed out. Tap Retry.');
            }
        }, 60000);  // 60s — generous enough to cover a ~2MB download on slow head-unit LTE

        // Resolve the model path: bundled (seal) loads instantly; everything else is
        // downloaded server-side and then served from the persistent cache through the
        // same URL (HttpServer.serveStaticFile() falls back to /data/local/tmp/overdrive/models/).
        this.ModelStore.ensureLoaded(modelId, this.manifest, function(modelPath, err) {
            if (gen !== self._loadGen) return; // user picked a different model meanwhile
            if (err) {
                self._modelLoadComplete = true;
                if (self._modelLoadTimeout) { clearTimeout(self._modelLoadTimeout); self._modelLoadTimeout = null; }
                self._showModelError(err);
                return;
            }
            self._loadModelFromPath(modelPath, gen);
        }, function(progressMsg) {
            if (gen !== self._loadGen) return;
            var t = document.querySelector('.vc-loading-text');
            if (t) t.textContent = progressMsg;
        });
    },

    _disposeCarModel: function() {
        if (!this.carModel) return;
        var self = this;

        // Tear down satellite state that's *attached to* the car before removing
        // the car itself. Order matters: these helpers reference this.carModel and
        // would no-op (or warn from GSAP) if we cleared the reference first.
        this.stopAcSonar();
        if (this.stateGlows) {
            var keys = Object.keys(this.stateGlows);
            for (var k = 0; k < keys.length; k++) this.removeStateGlow(keys[k]);
        }

        this.carModel.traverse(function(node) {
            if (node.isMesh) {
                if (node.geometry) node.geometry.dispose();
                if (node.material) {
                    if (Array.isArray(node.material)) {
                        for (var i = 0; i < node.material.length; i++) node.material[i].dispose();
                    } else {
                        node.material.dispose();
                    }
                }
            }
        });
        if (this.scene) this.scene.remove(this.carModel);
        this.carModel = null;
        this._modelSourceScale = null;
        this.bodyPaintMeshes = [];
    },

    /**
     * Prepare any manifest model for the shared Vehicle stage.
     *
     * `displayScale` is optional per-asset calibration metadata. The renderer
     * does not special-case vehicle ids: a source mesh with a distorted axis
     * can declare [x,y,z], while every other model falls back to [1,1,1].
     * A final uniform fit keeps different vehicles similarly readable.
     */
    _fitLoadedCarModel: function() {
        if (!this.carModel || typeof THREE === 'undefined') return;

        var entry = this.ModelStore.findEntry(this.manifest, this.activeModelId);
        var correction = [1, 1, 1];
        if (entry && entry.displayScale && entry.displayScale.length === 3) {
            for (var i = 0; i < 3; i++) {
                var value = parseFloat(entry.displayScale[i]);
                if (isFinite(value) && value > 0) correction[i] = value;
            }
        }

        var source = this._modelSourceScale || new THREE.Vector3(1, 1, 1);
        this.carModel.scale.set(
            source.x * correction[0],
            source.y * correction[1],
            source.z * correction[2]
        );
        this.carModel.position.set(0, 0, 0);

        var box = new THREE.Box3().setFromObject(this.carModel);
        var size = box.getSize(new THREE.Vector3());
        var longestFootprint = Math.max(size.x, size.z);
        var rect = this.renderer && this.renderer.domElement
            ? this.renderer.domElement.getBoundingClientRect()
            : { width: window.innerWidth };
        var compactViewport = rect.width < 768 && !window.AndroidBridge;
        var targetLength = compactViewport ? 4.65 : 5.25;

        if (longestFootprint > 0.0001) {
            this.carModel.scale.multiplyScalar(targetLength / longestFootprint);
        }

        // Centre only after applying both correction and fit. Scaling an
        // already-centred group can reintroduce an offset when its source
        // origin is asymmetric.
        this.carModel.position.set(0, 0, 0);
        box.setFromObject(this.carModel);
        var center = box.getCenter(new THREE.Vector3());
        this.carModel.position.sub(center);
        this.carModel.position.y += 0.1;
    },

    _loadModelFromPath: function(modelPath, gen) {
        var self = this;
        var loader = new THREE.GLTFLoader();

        // Draco decoder — the GLB uses Draco mesh compression.
        // Local path: assets/web/shared/vendor/draco/ (extracted to /data/local/tmp/web/shared/vendor/draco/).
        // WebAssembly is materially faster on the head unit and the local
        // server serves .wasm as application/wasm. Keep JS as a fallback for
        // WebViews without WebAssembly.
        var dracoLoader = new THREE.DRACOLoader();
        dracoLoader.setDecoderPath('../shared/vendor/draco/');
        dracoLoader.setDecoderConfig({
            type: typeof WebAssembly === 'object' ? 'wasm' : 'js'
        });
        loader.setDRACOLoader(dracoLoader);

        loader.load(
            modelPath,
            function(gltf) {
                if (gen !== self._loadGen) return; // a newer load has superseded this one
                self._modelLoadComplete = true;
                if (self._modelLoadTimeout) { clearTimeout(self._modelLoadTimeout); self._modelLoadTimeout = null; }
                self.carModel = gltf.scene;

                var modelEntry = self.ModelStore.findEntry(self.manifest, self.activeModelId);
                var paintMeshHint = modelEntry && typeof modelEntry.paintMeshHint === 'string'
                    ? modelEntry.paintMeshHint.toLowerCase() : '';

                self.carModel.traverse(function(node) {
                    if (node.isMesh) {
                        // Identify body paint panels vs glass/chrome/rubber/interior
                        // Body paint: opaque, non-transparent, typically the largest colored surfaces
                        var mat = node.material;
                        var isBodyPaint = false;

                        if (paintMeshHint) {
                            var paintName = ((node.name || '') + ' '
                                + (mat && mat.name ? mat.name : '')).toLowerCase();
                            isBodyPaint = !!(mat && mat.color
                                && paintName.indexOf(paintMeshHint) >= 0);
                        } else if (mat && !mat.transparent && mat.opacity > 0.9) {
                            // Check if it's NOT glass (glass is usually transparent or has low opacity)
                            // Check if it's NOT black rubber/tyre (very dark, roughness ~1)
                            // Check if it's NOT chrome (metalness ~1, very light color)
                            var col = mat.color;
                            if (col) {
                                var brightness = col.r * 0.299 + col.g * 0.587 + col.b * 0.114;
                                var isVeryDark = brightness < 0.08;  // black rubber, tyres
                                var isVeryBright = brightness > 0.85; // chrome, lights
                                var isGlass = mat.transparent || (mat.opacity < 0.95);
                                var metalness = mat.metalness !== undefined ? mat.metalness : 0;

                                // Body paint: mid-range brightness, not chrome-level metalness
                                if (!isVeryDark && !isVeryBright && !isGlass && metalness < 0.95) {
                                    isBodyPaint = true;
                                }
                            }
                        }

                        if (isBodyPaint) {
                            // Store original color for reference
                            node.userData.originalColor = mat.color.clone();
                            node.userData.isBodyPaint = true;
                            // Apply the user's chosen color
                            mat.color.set(self.baseColor);
                            mat.needsUpdate = true;
                            self.bodyPaintMeshes.push(node);
                        }

                        // Keep the model's original material for everything else
                        if (mat && mat.isMeshStandardMaterial) {
                            mat.envMapIntensity = 1.0;
                            mat.needsUpdate = true;
                        }
                    }
                });

                self._modelSourceScale = self.carModel.scale.clone();
                self._fitLoadedCarModel();
                if (window.AndroidBridge && window.console) {
                    console.log('[VC perf] model=' + self.activeModelId
                        + ' readyMs=' + (Date.now() - self._modelLoadStartedAt));
                }

                self.scene.add(self.carModel);

                var loadingEl = document.getElementById('vcLoading');
                if (loadingEl) loadingEl.classList.add('hidden');
                var vpReady = document.querySelector('.vc-viewport');
                if (vpReady) vpReady.removeAttribute('data-model-loading');
                // Cache the bounding box once — the wheel-anchor positions are
                // derived from it and the box is stable after model placement.
                if (self._cacheCarBounds) self._cacheCarBounds();
                self.triggerIdlePulse();
            },
            function(progress) {
                if (gen !== self._loadGen) return;
                if (progress.total > 0) {
                    var pct = Math.round((progress.loaded / progress.total) * 100);
                    var textEl = document.querySelector('.vc-loading-text');
                    if (textEl) textEl.textContent = BYD.i18n.t('vehicle.loading_model_pct', {pct: pct});
                }
            },
            function(error) {
                if (gen !== self._loadGen) return;
                console.error('Model load error:', error);
                self._modelLoadComplete = true;  // Don't fire timeout error after this.
                if (self._modelLoadTimeout) { clearTimeout(self._modelLoadTimeout); self._modelLoadTimeout = null; }
                self._showModelError(BYD.i18n.t('vehicle.model_load_failed'));
            }
        );
    },

    /**
     * Surface a user-actionable error in the loading overlay with a Retry button.
     * Idempotent: safe to call from timeout, error callback, or precondition guards.
     */
    _showModelError: function(msg) {
        var loadingEl = document.getElementById('vcLoading');
        if (loadingEl) loadingEl.classList.remove('hidden');
        var textEl = document.querySelector('.vc-loading-text');
        if (textEl) {
            textEl.textContent = msg || BYD.i18n.t('vehicle.model_load_failed_short');
            textEl.style.textAlign = 'center';
            textEl.style.lineHeight = '1.6';
        }
        var spinner = document.querySelector('.vc-loading-spinner');
        if (spinner) spinner.style.display = 'none';
        var retryBtn = document.getElementById('vcLoadingRetry');
        if (retryBtn) {
            retryBtn.style.display = 'inline-block';
            // Re-bind defensively (avoid stacking listeners across retries)
            var self = this;
            retryBtn.onclick = function() {
                retryBtn.style.display = 'none';
                if (spinner) spinner.style.display = '';
                if (textEl) textEl.textContent = BYD.i18n.t('vehicle.loading_model');
                self.loadModel();
            };
        }
    },

    onResize: function() {
        // Match the responsive lens used at initialisation.
        this.camera.fov = window.innerWidth < 768 && !window.AndroidBridge ? 50 : 45;
        // Re-measure the canvas's CSS box, not the window — the sidebar
        // takes 260px on desktop. Without this, the car visually shifts
        // off-centre toward the right edge of the visible area.
        var rect = this.renderer.domElement.getBoundingClientRect();
        var w = rect.width  || window.innerWidth;
        var h = rect.height || window.innerHeight;
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(w, h, false);
        // Invalidate the cached tyre-layout dimensions so the next
        // _updateTyreCalloutPositions call re-flows the boxes for the
        // new viewport size.
        this._tyreLastW = 0; this._tyreLastH = 0;
    },

    animate: function(frameTime) {
        var self = this;
        requestAnimationFrame(function(nextFrameTime) { self.animate(nextFrameTime); });
        // 30fps is smooth for this fixed automotive display. Together with
        // the 1x backing buffer it removes most continuous AVN GPU load;
        // standalone phone/browser clients retain their native refresh rate.
        if (window.AndroidBridge) {
            var now = typeof frameTime === 'number'
                ? frameTime
                : (window.performance && performance.now ? performance.now() : Date.now());
            if (this._lastRenderFrame
                    && now - this._lastRenderFrame < 32) return;
            this._lastRenderFrame = now;
        }
        if (this.controls) this.controls.update();
        // Update canvas texture each frame when 3D view is active
        if (this._3dViewActive && this._videoTexture) {
            this._videoTexture.needsUpdate = true;
        }
        if (this.renderer && this.scene && this.camera) {
            this.renderer.render(this.scene, this.camera);
        }
        // Reposition tyre callouts after the camera/controls have settled
        // for this frame. Cheap (4 vector projections + 4 line endpoints).
        // Skipped automatically while 3D surround is active or the user has
        // toggled the layer off.
        this._updateTyreCalloutPositions();
    },

    // ==================== VFX ANIMATIONS ====================

    /** Flash all body paint meshes to a color and back. Caller passes the
     *  flash colour in sRGB (`new THREE.Color(0xRRGGBB)`); we convert to
     *  linear here so the displayed flash matches the intended hex. The
     *  saved origColors are already in linear space (they came from the
     *  material) so they restore directly without extra conversion. */
    flashBodyColor: function(flashColor, duration, repeats, callback) {
        var self = this;
        if (this.bodyPaintMeshes.length === 0) return;

        var linearFlash = flashColor.clone().convertSRGBToLinear();

        // Store current colors (already linear)
        var origColors = [];
        for (var i = 0; i < this.bodyPaintMeshes.length; i++) {
            origColors.push(this.bodyPaintMeshes[i].material.color.clone());
        }

        // Flash each body mesh
        for (var j = 0; j < this.bodyPaintMeshes.length; j++) {
            gsap.to(this.bodyPaintMeshes[j].material.color, {
                r: linearFlash.r, g: linearFlash.g, b: linearFlash.b,
                duration: duration || 0.15,
                yoyo: true,
                repeat: repeats || 1,
                ease: 'power2.out',
                onComplete: (function(idx) {
                    return function() {
                        // Restore original color
                        self.bodyPaintMeshes[idx].material.color.copy(origColors[idx]);
                        self.bodyPaintMeshes[idx].material.needsUpdate = true;
                        if (idx === self.bodyPaintMeshes.length - 1 && callback) callback();
                    };
                })(j)
            });
        }
    },

    triggerIdlePulse: function() {
        // No-op — car looks good static with clean materials
    },

    triggerUnlockVFX: function() {
        var self = this;
        if (!this.carModel) return;
        var white = new THREE.Color(0xFFFFFF);

        this.flashBodyColor(white, 0.12, 3, null);

        // Scale bounce
        gsap.to(this.carModel.scale, {
            x: 1.02, y: 1.02, z: 1.02,
            duration: 0.2,
            yoyo: true,
            repeat: 1,
            ease: 'power2.out'
        });
    },

    triggerLockVFX: function() {
        var self = this;
        if (!this.carModel) return;
        var red = new THREE.Color(0xFF0055);

        this.flashBodyColor(red, 0.12, 1, null);

        gsap.to(this.carModel.scale, {
            x: 0.98, y: 0.98, z: 0.98,
            duration: 0.15,
            yoyo: true,
            repeat: 1,
            ease: 'power2.out'
        });
    },

    triggerSonarVFX: function(x, y, z, color) {
        var self = this;
        if (!this.carModel) return;
        var ringColor = color || this.baseColor;

        var ringGeo = new THREE.RingGeometry(0.1, 0.15, 32);
        var ringMat = new THREE.MeshBasicMaterial({
            color: ringColor,
            side: THREE.DoubleSide,
            transparent: true,
            opacity: 1.0
        });
        var sonarRing = new THREE.Mesh(ringGeo, ringMat);
        sonarRing.position.set(x, y, z);
        sonarRing.rotation.x = Math.PI / 2;
        this.carModel.add(sonarRing);

        gsap.to(sonarRing.scale, {
            x: 6, y: 6, z: 6,
            duration: 1.2,
            ease: 'power2.out'
        });
        gsap.to(ringMat, {
            opacity: 0,
            duration: 1.2,
            ease: 'power2.out',
            onComplete: function() {
                if (self.carModel) self.carModel.remove(sonarRing);
                ringGeo.dispose();
                ringMat.dispose();
            }
        });
    },

    triggerTrunkVFX: function(opening) {
        var self = this;
        var color = opening ? this.baseColor : new THREE.Color(0xFF0055);
        this.triggerSonarVFX(0, 0.8, -2.2, color);
        if (opening) {
            setTimeout(function() { self.triggerSonarVFX(0, 0.8, -2.2, color); }, 200);
        }
    },

    triggerDoorVFX: function(door, opening) {
        var positions = {
            lf: { x: 1.0, y: 0.6, z: 0.5 },
            rf: { x: -1.0, y: 0.6, z: 0.5 },
            lr: { x: 1.0, y: 0.6, z: -0.5 },
            rr: { x: -1.0, y: 0.6, z: -0.5 }
        };
        var pos = positions[door];
        if (!pos) return;
        var color = opening ? this.baseColor : new THREE.Color(0x22C55E);
        this.triggerSonarVFX(pos.x, pos.y, pos.z, color);
    },

    triggerWindowVFX: function(area, opening) {
        var positions = {
            lf: { x: 1.0, y: 0.9, z: 0.5 },
            rf: { x: -1.0, y: 0.9, z: 0.5 },
            lr: { x: 1.0, y: 0.9, z: -0.5 },
            rr: { x: -1.0, y: 0.9, z: -0.5 },
            sunroof: { x: 0, y: 1.4, z: -0.5 },
            sunshade: { x: 0, y: 1.4, z: -0.5 }
        };
        var pos = positions[area];
        if (!pos) return;
        var color = opening ? new THREE.Color(0x38BDF8) : this.baseColor;
        this.triggerSonarVFX(pos.x, pos.y, pos.z, color);
    },

    triggerFlashVFX: function() {
        if (!this.carModel) return;
        var white = new THREE.Color(0xFFFFFF);
        this.flashBodyColor(white, 0.08, 5, null);
    },

    /** Start continuous AC sonar wave effect — semi-circular ring sweeps front to back */
    startAcSonar: function() {
        if (this._acSonarInterval) return; // already running
        var self = this;
        this._acSonarMeshes = [];

        function spawnAcRing() {
            if (!self.carModel) return;
            var ringGeo = new THREE.RingGeometry(0.1, 0.15, 32);
            var ringMat = new THREE.MeshBasicMaterial({
                color: 0x38BDF8,
                side: THREE.DoubleSide,
                transparent: true,
                opacity: 0.8
            });
            var ring = new THREE.Mesh(ringGeo, ringMat);
            ring.position.set(0, 0.5, 1.5);
            ring.rotation.x = Math.PI / 2;
            self.carModel.add(ring);
            self._acSonarMeshes.push(ring);

            // Move from z=1.5 to z=-2.0 over 1.5s while fading out
            gsap.to(ring.position, {
                z: -2.0,
                duration: 1.5,
                ease: 'linear'
            });
            gsap.to(ringMat, {
                opacity: 0,
                duration: 1.5,
                ease: 'linear',
                onComplete: function() {
                    if (self.carModel) self.carModel.remove(ring);
                    ringGeo.dispose();
                    ringMat.dispose();
                    var idx = self._acSonarMeshes.indexOf(ring);
                    if (idx !== -1) self._acSonarMeshes.splice(idx, 1);
                }
            });
        }

        spawnAcRing();
        this._acSonarInterval = setInterval(function() {
            spawnAcRing();
        }, 2000);
    },

    /** Stop continuous AC sonar effect */
    stopAcSonar: function() {
        if (this._acSonarInterval) {
            clearInterval(this._acSonarInterval);
            this._acSonarInterval = null;
        }
        if (this._acSonarMeshes && this.carModel) {
            for (var i = 0; i < this._acSonarMeshes.length; i++) {
                var mesh = this._acSonarMeshes[i];
                gsap.killTweensOf(mesh.position);
                gsap.killTweensOf(mesh.material);
                this.carModel.remove(mesh);
                mesh.geometry.dispose();
                mesh.material.dispose();
            }
        }
        this._acSonarMeshes = [];
    },

    // ==================== COLOR PICKER ====================

    initColorPicker: function() {
        var self = this;
        var container = document.getElementById('colorPicker');
        if (!container) return;

        for (var i = 0; i < this.colorPresets.length; i++) {
            (function(preset, idx) {
                var swatch = document.createElement('div');
                swatch.className = 'vc-swatch' + (idx === 0 ? ' active' : '');
                swatch.style.backgroundColor = preset.hex;
                swatch.title = preset.name;
                swatch.setAttribute('data-hex', preset.hex);
                swatch.addEventListener('click', function(e) {
                    e.stopPropagation();
                    self.setColor(preset.hex, swatch);
                });
                // Also handle touchend for WebView reliability
                swatch.addEventListener('touchend', function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    self.setColor(preset.hex, swatch);
                });
                container.appendChild(swatch);
            })(this.colorPresets[i], i);
        }

        // Custom color — use a text hex input fallback for WebView compatibility
        // (input type="color" doesn't work on Android 7.1 WebView / Chrome 58)
        var custom = document.createElement('div');
        custom.className = 'vc-swatch-custom';
        custom.title = BYD.i18n.t('vehicle.color_custom');
        custom.style.position = 'relative';
        
        // Try native color picker first, fall back gracefully
        var input = document.createElement('input');
        input.type = 'color';
        input.value = '#E8E8EC';
        input.addEventListener('input', function(e) {
            self.setColor(e.target.value, null);
            custom.style.backgroundColor = e.target.value;
        });
        input.addEventListener('change', function(e) {
            self.setColor(e.target.value, null);
            custom.style.backgroundColor = e.target.value;
        });
        custom.appendChild(input);
        container.appendChild(custom);
    },

    /**
     * Apply a body paint color in-memory. Does NOT persist — used both by user
     * clicks (via setColor) and by the initial load (with the persisted value
     * fetched from /api/models/selected, before the GLB has parsed).
     *
     * Sync swatch highlight too: when called from init() the swatches may not
     * exist yet, so we no-op gracefully — initColorPicker will reflect the
     * baseColor next time setColor runs anyway.
     */
    applyColor: function(hex, syncSwatch) {
        // Convert sRGB hex → linear color space. The renderer is configured
        // with `outputEncoding = sRGBEncoding`, which means materials store
        // colors in linear space and the output is gamma-encoded on the way
        // to the screen. Without this conversion the picked hex gets treated
        // as already-linear and ends up rendered too bright + saturation-
        // shifted (e.g. #C8102E reads as pinkish-magenta instead of red).
        var linearColor = new THREE.Color(hex).convertSRGBToLinear();
        this.baseColor.copy(linearColor);

        for (var i = 0; i < this.bodyPaintMeshes.length; i++) {
            var mesh = this.bodyPaintMeshes[i];
            if (mesh.material && mesh.material.color) {
                mesh.material.color.copy(linearColor);
                mesh.material.needsUpdate = true;
            }
        }
        // Skip the rim light recolor while the surround bowl is up. The rim
        // light sits at y=-1.5 (under the car) inside the cylinder; tinting
        // it to the user's body-paint hex bleeds onto the bowl wall and makes
        // the camera footage look washed out / tinted whatever colour they
        // just picked. The rim light is decorative for the exterior orbit
        // pose only — no need to track body colour while we're inside the bowl.
        if (this.rimLight && !this._3dViewActive) this.rimLight.color.copy(linearColor);

        if (syncSwatch) {
            var swatches = document.querySelectorAll('.vc-swatch');
            for (var j = 0; j < swatches.length; j++) {
                swatches[j].classList.remove('active');
                var dataHex = swatches[j].getAttribute('data-hex');
                if (dataHex && dataHex.toLowerCase() === hex.toLowerCase()) {
                    swatches[j].classList.add('active');
                }
            }
        }
    },

    setColor: function(hex, activeSwatch) {
        this.applyColor(hex, false);

        var swatches = document.querySelectorAll('.vc-swatch');
        for (var i = 0; i < swatches.length; i++) swatches[i].classList.remove('active');
        if (activeSwatch) activeSwatch.classList.add('active');

        this._saveSelected({ color: hex });
    },

    // ==================== MODEL PICKER ====================

    /**
     * Tiny client for the /api/models/* endpoints. Resolves a model id to a URL the
     * GLTFLoader can consume; if the model isn't on disk yet, kicks off a server-side
     * download and polls for progress, surfacing a percentage to the loading overlay.
     *
     * Bundled models (manifest.bundled === true) skip the server roundtrip entirely.
     * Once a model has been downloaded once it lives in /data/local/tmp/overdrive/models/
     * and the server transparently serves it under the same shared/models/<file>.glb URL.
     */
    ModelStore: {
        /**
         * Fetch the effective manifest from the server. The server returns whichever
         * is newer between the APK-bundled copy and a previously-cached remote copy,
         * so this is fast (local file read) and offline-safe.
         *
         * Pair with refreshManifest() below to also revalidate against the remote
         * release in the background.
         */
        loadManifest: function(cb) {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/api/models/manifest', true);
            xhr.timeout = 8000;
            xhr.onload = function() {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try { cb(JSON.parse(xhr.responseText)); return; } catch(e) {}
                }
                cb(null);
            };
            xhr.onerror = function() { cb(null); };
            xhr.ontimeout = function() { cb(null); };
            xhr.send();
        },

        /**
         * Background revalidation against the remote manifest. The server fetches
         * GitHub (with If-None-Match for cheap 304 short-circuit), replaces its
         * cache if the remote version is newer, and returns
         *   {updated, stale, version, manifest?}.
         *
         * Two callbacks:
         *   onChanged(newManifest) — fires only when the manifest actually changed.
         *   onResult(stale)        — fires on every completion so callers can
         *                            update a stale indicator. stale === true means
         *                            the network/server attempt failed entirely.
         *                            Either callback may be null.
         */
        refreshManifest: function(onChanged, onResult) {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/models/manifest/refresh', true);
            xhr.timeout = 15000;  // generous: a 302 + Azure blob read on slow LTE
            xhr.onload = function() {
                if (xhr.status < 200 || xhr.status >= 300) {
                    if (onResult) onResult(true); // treat non-2xx as stale
                    return;
                }
                try {
                    var resp = JSON.parse(xhr.responseText);
                    var stale = !!(resp && resp.stale);
                    if (resp && resp.updated && resp.manifest && onChanged) {
                        onChanged(resp.manifest);
                    }
                    if (onResult) onResult(stale);
                } catch(e) {
                    if (onResult) onResult(true);
                }
            };
            // Local server is on loopback so onerror/ontimeout almost never fires —
            // a network failure manifests as `stale:true` in the response body.
            // But cover the timeout path in case the server itself stalls.
            xhr.onerror = function() { if (onResult) onResult(true); };
            xhr.ontimeout = function() { if (onResult) onResult(true); };
            xhr.send();
        },

        findEntry: function(manifest, id) {
            if (!manifest || !manifest.models) return null;
            for (var i = 0; i < manifest.models.length; i++) {
                if (manifest.models[i].id === id) return manifest.models[i];
            }
            return null;
        },

        modelUrl: function(file) {
            // The server falls back to the persistent download cache for any
            // shared/models/*.glb miss, so this URL works for bundled and downloaded alike.
            return '../shared/models/' + file;
        },

        /**
         * Resolve `id` to a URL ready for THREE.GLTFLoader. Three outcomes:
         *   1. Bundled or already-cached → onDone(url) immediately.
         *   2. Needs download → POSTs /api/models/download, polls /status, then onDone(url).
         *   3. Failure → onDone(null, errorMessage).
         * onProgress(text) gets called with friendly status strings during the download.
         */
        ensureLoaded: function(id, manifest, onDone, onProgress) {
            var self = this;
            var entry = this.findEntry(manifest, id);
            if (!entry) {
                onDone(null, 'Unknown model: ' + id);
                return;
            }
            var url = this.modelUrl(entry.file);

            // Skip the API roundtrip for the bundled default — it's always on disk.
            if (entry.bundled) {
                onDone(url);
                return;
            }

            // Quick existence check via /list (covers the "user re-selected a previously
            // downloaded model" case, which should feel instant).
            this._getList(function(list) {
                var listEntry = self._findInList(list, id);
                if (listEntry && listEntry.downloaded) {
                    onDone(url);
                    return;
                }
                self._download(id, entry, url, onDone, onProgress);
            }, function() {
                // Couldn't reach /list — assume not downloaded and try anyway.
                self._download(id, entry, url, onDone, onProgress);
            });
        },

        _getList: function(onOk, onErr) {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/api/models/list', true);
            xhr.timeout = 5000;
            xhr.onload = function() {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try { onOk(JSON.parse(xhr.responseText)); return; } catch(e) {}
                }
                onErr();
            };
            xhr.onerror = function() { onErr(); };
            xhr.ontimeout = function() { onErr(); };
            xhr.send();
        },

        _findInList: function(list, id) {
            if (!list || !list.models) return null;
            for (var i = 0; i < list.models.length; i++) {
                if (list.models[i].id === id) return list.models[i];
            }
            return null;
        },

        _download: function(id, entry, url, onDone, onProgress) {
            var self = this;
            if (onProgress) onProgress(BYD.i18n.t('vehicle.downloading_model', {
                name: BYD.i18n.modelName(entry.id, entry.name), pct: 0
            }));

            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/models/download?id=' + encodeURIComponent(id), true);
            xhr.onload = function() {
                if (xhr.status >= 200 && xhr.status < 300) {
                    self._poll(id, entry, url, onDone, onProgress);
                } else {
                    onDone(null, BYD.i18n.t('vehicle.download_request_failed', {status: xhr.status}));
                }
            };
            xhr.onerror = function() { onDone(null, BYD.i18n.t('vehicle.network_starting_download')); };
            xhr.send();
        },

        _poll: function(id, entry, url, onDone, onProgress) {
            var self = this;
            var attempts = 0;
            // 60s of poll budget at 250ms intervals — covers a ~2MB GLB on slow LTE.
            var maxAttempts = 240;

            function tick() {
                attempts++;
                if (attempts > maxAttempts) {
                    onDone(null, BYD.i18n.t('vehicle.download_timed_out'));
                    return;
                }
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '/api/models/status?id=' + encodeURIComponent(id), true);
                xhr.timeout = 4000;
                xhr.onload = function() {
                    if (xhr.status < 200 || xhr.status >= 300) {
                        setTimeout(tick, 500);
                        return;
                    }
                    var s;
                    try { s = JSON.parse(xhr.responseText); }
                    catch(e) { setTimeout(tick, 500); return; }

                    if (s.state === 'done' || s.downloaded === true) {
                        onDone(url);
                        return;
                    }
                    if (s.state === 'error') {
                        onDone(null, s.error || BYD.i18n.t('vehicle.download_failed'));
                        return;
                    }
                    if (onProgress) {
                        var pct = typeof s.percent === 'number' ? s.percent : 0;
                        onProgress(BYD.i18n.t('vehicle.downloading_model', {
                            name: BYD.i18n.modelName(entry.id, entry.name), pct: pct
                        }));
                    }
                    setTimeout(tick, 250);
                };
                xhr.onerror = function() { setTimeout(tick, 500); };
                xhr.ontimeout = function() { setTimeout(tick, 500); };
                xhr.send();
            }
            tick();
        }
    },

    initModelPicker: function() {
        var self = this;
        var sel = document.getElementById('modelPicker');
        if (!sel || !this.manifest || !this.manifest.models) return;

        // Wipe placeholder options and rebuild from the manifest so adding a model in
        // a future release just means bumping the manifest, not editing this file.
        sel.innerHTML = '';
        for (var i = 0; i < this.manifest.models.length; i++) {
            var m = this.manifest.models[i];
            var opt = document.createElement('option');
            opt.value = m.id;
            opt.textContent = BYD.i18n.modelName(m.id, m.name);
            sel.appendChild(opt);
        }

        sel.addEventListener('change', function() {
            self.setModel(sel.value);
        });

        // Tap-to-retry when stale. mousedown fires before the dropdown opens, so
        // by the time the user is choosing, we've already kicked off a refresh —
        // if it lands fast (304 path is ~200ms), the new model may even appear in
        // the open dropdown. Throttle to once per second to avoid spamming on
        // rapid taps.
        sel.addEventListener('mousedown', function() {
            if (!sel.classList.contains('stale')) return;
            var now = Date.now();
            if (self._lastStaleRetryMs && now - self._lastStaleRetryMs < 1000) return;
            self._lastStaleRetryMs = now;
            self._kickManifestRefresh();
        });
    },

    /** Re-label the existing options without rebuilding listeners/selection. */
    refreshModelPickerNames: function() {
        var sel = document.getElementById('modelPicker');
        if (!sel || !this.manifest || !this.manifest.models) return;
        for (var i = 0; i < this.manifest.models.length; i++) {
            var m = this.manifest.models[i];
            if (sel.options[i]) {
                sel.options[i].textContent = BYD.i18n.modelName(m.id, m.name);
            }
        }
    },

    setModel: function(id) {
        if (!id || id === this.activeModelId) return;
        this._saveSelected({ modelId: id });
        this.loadModel(id);
    },

    // ==================== PANEL TOGGLE (Tabbed Controls) ====================

    _activePanel: null,

    togglePanel: function(panelId, tabEl) {
        var panel = document.getElementById('vcPanel');
        var allPanels = panel.querySelectorAll('.vc-panel-row');
        var allTabs = document.querySelectorAll('.vc-tab');
        var target = document.getElementById(panelId);

        // If tapping the already-active tab, collapse
        if (this._activePanel === panelId) {
            panel.classList.remove('open');
            panel.classList.remove('vc-panel-tall');
            this._activePanel = null;
            for (var i = 0; i < allTabs.length; i++) allTabs[i].classList.remove('active');
            for (var j = 0; j < allPanels.length; j++) allPanels[j].style.display = 'none';
            return;
        }

        // Hide all panels, show target
        for (var k = 0; k < allPanels.length; k++) allPanels[k].style.display = 'none';
        if (target) target.style.display = 'flex';

        // Update tab active state
        for (var m = 0; m < allTabs.length; m++) allTabs[m].classList.remove('active');
        if (tabEl) tabEl.classList.add('active');

        // Open the panel container — Windows needs extra vertical space for
        // the per-window preset rows.
        panel.classList.add('open');
        // Tall panels: Windows (4×5 preset grid), Charging (schedule + cap stacked),
        // Climate (controls + remote preconditioning row).
        if (panelId === 'panelWindows' || panelId === 'panelCharging'
                || panelId === 'panelClimate') {
            panel.classList.add('vc-panel-tall');
        } else panel.classList.remove('vc-panel-tall');
        this._activePanel = panelId;
        if (panelId === 'panelCharging') {
            this.fetchChargingSchedule();
            this.fetchChargeCap();
            this.fetchAcChargeCurrentLimit();
        }
        if (panelId === 'panelClimate') {
            this.fetchClimateSchedule();
        }
        if (panelId === 'panelSound') {
            this.fetchEngineSoundState();
        }
    },

    /**
     * Query exterior-speaker availability + engine-sound simulator state and
     * reflect it in the Sound panel. Hides the engine-sound row when the
     * vehicle doesn't support the simulator, and shows an "unavailable" hint
     * when the 'auto' service is unreachable (car asleep / non-BYD build).
     */
    fetchEngineSoundState: function() {
        var self = this;
        fetch('/api/audio/engine-sound').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            var hint = document.getElementById('avasUnavailableHint');
            var engineRow = document.getElementById('engineSoundRow');
            if (!data || data.avasAvailable === false) {
                if (hint) hint.style.display = '';
                if (engineRow) engineRow.style.display = 'none';
                return;
            }
            if (hint) hint.style.display = 'none';
            if (engineRow) engineRow.style.display = data.supported ? '' : 'none';
            self.vehicleState.engineSoundOn = data.on === true;
            if (typeof data.preset === 'number' && data.preset >= 1) {
                self.vehicleState.enginePreset = data.preset;
            }
            self.updateEngineSoundUI();
        }).catch(function() {
            var hint = document.getElementById('avasUnavailableHint');
            if (hint) hint.style.display = '';
        });
    },

    updateEngineSoundUI: function() {
        var preset = document.getElementById('enginePreset');
        if (preset) preset.textContent = String(this.vehicleState.enginePreset || 1);
        var toggle = document.getElementById('btnEngineToggle');
        if (toggle) {
            if (this.vehicleState.engineSoundOn) toggle.classList.add('active');
            else toggle.classList.remove('active');
        }
    },

    /** Update tab dot indicators based on vehicle state */
    updateTabIndicators: function() {
        var tabs = document.querySelectorAll('.vc-tab');
        if (!tabs.length) return;

        // Security tab — has-active if locked (null = unknown, don't show)
        var secTab = tabs[0];
        if (secTab) {
            if (this.vehicleState.locked === true
                    && this.vehicleState.lockScope === 'vehicle') {
                secTab.classList.add('has-active');
            }
            else secTab.classList.remove('has-active');
        }

        // Trunk tab — has-active if trunk open
        var trunkTab = tabs[1];
        if (trunkTab) {
            if (this.vehicleState.trunkOpen === true) trunkTab.classList.add('has-active');
            else trunkTab.classList.remove('has-active');
        }

        // Climate tab — has-active if AC on (only if explicitly true, not undefined/null)
        var climateTab = tabs[2];
        if (climateTab) {
            if (this.vehicleState.acOn === true) climateTab.classList.add('has-active');
            else climateTab.classList.remove('has-active');
        }
    },

    // ==================== CONTROL BINDINGS ====================

    bindControls: function() {
        var self = this;

        // Lock — routed (cloud-first; SDK has no door-lock primitive so cloud-only effectively).
        this.bindBtn('btnLock', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnLock', true);
            self.triggerLockVFX();
            self.apiPost('/api/vehicle/lock').then(function(result) {
                self.setPending('btnLock', false);
                self.toastFromResult(result, BYD.i18n.t('vehicle.car_locked'), BYD.i18n.t('vehicle.lock_failed'));
            });
        });

        // Unlock — routed (cloud-first).
        this.bindBtn('btnUnlock', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnUnlock', true);
            self.triggerUnlockVFX();
            self.apiPost('/api/vehicle/unlock').then(function(result) {
                self.setPending('btnUnlock', false);
                self.toastFromResult(result, BYD.i18n.t('vehicle.car_unlocked'), BYD.i18n.t('vehicle.unlock_failed'));
            });
        });

        // Trunk open — use the local motor when the vehicle is awake; the
        // router only requests cloud if it actually needs the remote fallback.
        this.bindBtn('btnTrunkOpen', function() {
            self.setPending('btnTrunkOpen', true);
            self.apiPost('/api/vehicle/trunk', { action: 'open' }).then(function(result) {
                self.setPending('btnTrunkOpen', false);
                if (result.success) self.triggerTrunkVFX(true);
                self.toastFromResult(result, BYD.i18n.t('vehicle.trunk_opening'), BYD.i18n.t('vehicle.trunk_failed'));
            });
        });

        // Trunk close — SDK tailgate motor.
        this.bindBtn('btnTrunkClose', function() {
            self.setPending('btnTrunkClose', true);
            self.toast(BYD.i18n.t('vehicle.closing_trunk'), 'info');
            self.triggerLockVFX();
            self.apiPost('/api/vehicle/trunk', { action: 'close' }).then(function(result) {
                self.setPending('btnTrunkClose', false);
                if (result.success) self.triggerTrunkVFX(false);
                self.toastFromResult(result, BYD.i18n.t('vehicle.trunk_closing'), BYD.i18n.t('vehicle.trunk_close_failed'));
            });
        });

        // Flash lights — routed (cloud-only on this gen). Toast reads server-resolved message.
        this.bindBtn('btnFlash', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnFlash', true);
            self.triggerFlashVFX();
            self.apiPost('/api/vehicle/flash').then(function(result) {
                self.setPending('btnFlash', false);
                self.toastFromResult(result, BYD.i18n.t('vehicle.lights_flashed'), BYD.i18n.t('vehicle.flash_failed'));
            });
        });

        // Find car — routed cloud-first (cloud-only on this gen). Vehicle wakes,
        // then horn + lights pulse.
        this.bindBtn('btnFindCar', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnFindCar', true);
            self.triggerFlashVFX();
            self.apiPost('/api/vehicle/find-car').then(function(result) {
                self.setPending('btnFindCar', false);
                self.toastFromResult(result, BYD.i18n.t('vehicle_control.find_car_label'), null);
            });
        });

        // Battery preconditioning heat — cloud-only in both directions. The state
        // now comes from the cloud snapshot via /api/vehicle/state, so the tile
        // reflects reality across reloads; previously it was write-only, and after
        // a reload every tap re-sent "on" with no way to switch it off.
        this.bindBtn('btnBatteryHeat', function() {
            if (!self.requireCloud()) return;
            var current = !!(self.vehicleState && self.vehicleState.batteryHeat);
            var next = !current;
            var revision = ++self._batteryHeatRevision;
            self._batteryHeatPending = revision;
            self.setPending('btnBatteryHeat', true);
            self.apiPost('/api/vehicle/battery-heat', { enabled: next }).then(function(result) {
                if (revision !== self._batteryHeatRevision) return;
                self._batteryHeatPending = 0;
                self.setPending('btnBatteryHeat', false);
                if (result.success) {
                    if (!self.vehicleState) self.vehicleState = {};
                    self.vehicleState.batteryHeat = next;
                    self.updateBatteryHeatUI();
                }
                self.toastFromResult(result, BYD.i18n.t('vehicle_control.battery_heat_label'), null);
            }).catch(function(e) {
                if (revision !== self._batteryHeatRevision) return;
                self._batteryHeatPending = 0;
                self.setPending('btnBatteryHeat', false);
                self.toastFromResult({ success: false, error: e.message }, null,
                    BYD.i18n.t('vehicle_control.battery_heat_label'));
            });
        });

        // Per-window preset levels — backend runs closed-loop to drive the
        // window to the target % and auto-stops. UI just sends the target.
        var areas = ['lf', 'rf', 'lr', 'rr', 'sunroof', 'sunshade'];
        var rows = document.querySelectorAll('#panelWindows .vc-window-row[data-area]');
        for (var ri = 0; ri < rows.length; ri++) {
            (function(row) {
                var area = row.getAttribute('data-area');
                var areaNum = parseInt(row.getAttribute('data-area-num'), 10);
                var presets = row.querySelectorAll('.vc-preset');
                for (var pi = 0; pi < presets.length; pi++) {
                    (function(btn) {
                        btn.addEventListener('click', function() {
                            var target = parseInt(btn.getAttribute('data-preset'), 10);
                            var current = self.vehicleState.windows[area];
                            var revision = (self._windowCommandRevisions[area] || 0) + 1;
                            self._windowCommandRevisions[area] = revision;
                            self.apiPost('/api/vehicle/window',
                                { area: areaNum, targetPercent: target }).then(function(result) {
                                if (self._windowCommandRevisions[area] !== revision) return;
                                if (result && result.success) {
                                    // Only animate and select a target once the server accepted it.
                                    // A later live position update remains authoritative.
                                    if (typeof current === 'number' && current >= 0
                                            && Math.abs(current - target) > 5) {
                                        self.triggerWindowVFX(area, target > current);
                                    }
                                    self.markWindowPreset(area, target);
                                } else {
                                    self.updateWindowBars();
                                    self.fetchState();
                                }
                                self.toastFromResult(result, null, null);
                            });
                        });
                    })(presets[pi]);
                }
            })(rows[ri]);
        }

        // All windows — only fully-open / fully-closed makes sense for "all"
        // (per-window % requires per-window polling, no SDK batch primitive).
        // Loop over the 4 side windows only — `area:0` does not control sunroof/sunshade.
        this.bindBtn('btnWinAllOpen', function() {
            self.apiPost('/api/vehicle/window', { area: 0, command: 1 }).then(function(result) {
                if (result.success) {
                    for (var j = 0; j < 4; j++) self.triggerWindowVFX(areas[j], true);
                }
                self.toastFromResult(result, BYD.i18n.t('vehicle.windows_all_opening'),
                    BYD.i18n.t('vehicle.windows_all_opening'));
            });
        });
        // OPENWINDOW has only a ventilation-crack semantic in the BYD cloud.
        // It is deliberately separate from the all-open control above, and is
        // cloud-only — the neighbouring 0/100 presets have local SDK paths, this
        // one does not, so it explains itself instead of failing opaquely.
        this.bindBtn('btnWinAllVent', function() {
            if (!self.requireCloud()) return;
            self.apiPost('/api/vehicle/window', { action: 'vent' }).then(function(result) {
                if (result.success) {
                    for (var j = 0; j < 4; j++) self.triggerWindowVFX(areas[j], true);
                }
                self.toastFromResult(result,
                    BYD.i18n.t('vehicle.windows_venting'),
                    BYD.i18n.t('vehicle.windows_vent_failed'));
            });
        });
        // Routed SDK_FIRST on the server, with CLOSEWINDOW only as a fallback.
        this.bindBtn('btnWinAllClose', function() {
            self.apiPost('/api/vehicle/window', { area: 0, command: 2 }).then(function(result) {
                if (result.success) {
                    for (var j = 0; j < 4; j++) self.triggerWindowVFX(areas[j], false);
                }
                self.toastFromResult(result, BYD.i18n.t('vehicle.windows_all_closing'), BYD.i18n.t('vehicle.windows_all_closing'));
            });
        });

        // === LIGHTS CONTROLS ===
        this.bindBtn('btnDRL', function() {
            var enable = !(self.vehicleState.lights && self.vehicleState.lights.dayTimeLight);
            self.triggerSonarVFX(0, 0.6, 2, new THREE.Color(enable ? 0xFF6B35 : 0x1A1A1E));
            self.apiPost('/api/vehicle/lights', { target: 'dayTimeLight', enable: enable }).then(function(result) {
                if (result.success) {
                    self.vehicleState.lights.dayTimeLight = enable;
                    self.updateLightsUI();
                }
                self.toastFromResult(result,
                    BYD.i18n.t(enable ? 'vehicle.drl_enabled' : 'vehicle.drl_disabled'),
                    BYD.i18n.t('vehicle.drl_failed'));
            });
        });
        var ambientSlider = document.getElementById('ambientColourSlider');
        if (ambientSlider) {
            var ambientDebounce = null;
            ambientSlider.addEventListener('input', function() {
                var v = parseInt(ambientSlider.value, 10);
                self.vehicleState.lights.ambientColour = v;
                self.updateLightsUI();
                // Debounce the write so dragging the slider doesn't fire a POST per pixel.
                if (ambientDebounce) clearTimeout(ambientDebounce);
                ambientDebounce = setTimeout(function() {
                    self.apiPost('/api/vehicle/lights', { target: 'ambientColour', value: v }).then(function(result) {
                        if (result.success) {
                            self.updateLightsUI();
                        }
                        self.toastFromResult(result,
                            BYD.i18n.t('vehicle.ambient_set'),
                            BYD.i18n.t('vehicle.ambient_failed'));
                    });
                }, 350);
            });
        }

        // === ADAS CONTROLS ===
        this.bindBtn('btnSLW', function() {
            var enable = !(self.vehicleState.adas && self.vehicleState.adas.speedLimitWarning);
            self.apiPost('/api/vehicle/adas', { target: 'speedLimitWarning', enable: enable }).then(function(result) {
                if (result.success) {
                    self.vehicleState.adas.speedLimitWarning = enable;
                    self.updateAdasUI();
                }
                self.toastFromResult(result,
                    BYD.i18n.t(enable ? 'vehicle.slw_enabled' : 'vehicle.slw_disabled'),
                    BYD.i18n.t('vehicle.slw_failed'));
            });
        });
        this.bindBtn('btnCPD', function() {
            var enable = !(self.vehicleState.setting && self.vehicleState.setting.childPresenceDetection);
            self.apiPost('/api/vehicle/setting', { target: 'childPresenceDetection', value: enable ? 1 : 2 }).then(function(result) {
                if (result.success) {
                    self.vehicleState.setting.childPresenceDetection = enable;
                    self.updateAdasUI();
                }
                self.toastFromResult(result,
                    BYD.i18n.t(enable ? 'vehicle.cpd_enabled' : 'vehicle.cpd_disabled'),
                    BYD.i18n.t('vehicle.cpd_failed'));
            });
        });

        // === CHARGING SCHEDULE ===
        // pyBYD-shaped: { startChargeTime, endChargeTime, chargeWay, enabled }.
        // chargeWay is "s" one-shot, "e" every day, or "0,1,2,3,4" weekday list (Mon=0).
        // endChargeTime accepts "HH:MM" or sentinel "full" (charge until full within window).
        if (!this.vehicleState.chargingSchedule) {
            this.vehicleState.chargingSchedule = {
                enabled: null,
                startChargeTime: '22:00',
                endChargeTime: '06:00',
                chargeWay: 'e',
                untilFull: false,
                days: [],
                smartJourneyDto: null,
                supported: null
            };
        }
        // Master switch — wraps changeChargeStatue.
        this.bindBtn('btnSmartChargeToggle', function() {
            if (!self.requireCloud()) return;
            if (!self.beginSmartChargeRequest('toggle', 'btnSmartChargeToggle')) return;
            var cur = !!(self.vehicleState.chargingSchedule && self.vehicleState.chargingSchedule.enabled);
            var enable = !cur;
            self.apiPost('/api/vehicle/charging-schedule', { enabled: enable }).then(function(result) {
                if (self.finishSmartChargeRequest('toggle')) {
                    if (result.success) {
                        self.vehicleState.chargingSchedule.enabled = enable;
                        self.updateChargingUI();
                    }
                    self.toastFromResult(result, null, null);
                }
            }).catch(function(e) {
                if (self.finishSmartChargeRequest('toggle')) {
                    self.toastFromResult({ success: false, error: e.message }, null, null);
                }
            });
        });
        // Immediate charge start is terminally confirmed by the cloud. There
        // is intentionally no paired "stop now" button: BYD can acknowledge
        // status=0 without actually stopping an active charge session.
        this.bindBtn('btnStartCharging', function() {
            if (!self.requireCloud()) return;
            if (!self.beginSmartChargeRequest('start', 'btnStartCharging')) return;
            self.apiPost('/api/vehicle/start-charging', {}).then(function(result) {
                if (self.finishSmartChargeRequest('start')) {
                    self.toastFromResult(result, null, null);
                    if (result.success) self.fetchChargingSchedule();
                }
            }).catch(function(e) {
                if (self.finishSmartChargeRequest('start')) {
                    self.toastFromResult({ success: false, error: e.message }, null, null);
                }
            });
        });
        // Repeat segmented control — three exclusive modes.
        //   once   → chargeWay "s" (one-shot)
        //   daily  → chargeWay "e" (every day)
        //   custom → chargeWay = comma-separated weekday list
        var segs = document.querySelectorAll('#repeatSegmented .vc-seg');
        for (var i = 0; i < segs.length; i++) {
            (function(seg) {
                seg.addEventListener('click', function() {
                    if (self._smartChargePending) return;
                    var mode = seg.getAttribute('data-mode');
                    var s = self.vehicleState.chargingSchedule;
                    if (mode === 'once') { s.chargeWay = 's'; s.days = []; }
                    else if (mode === 'daily') { s.chargeWay = 'e'; s.days = [0,1,2,3,4,5,6]; }
                    else if (mode === 'custom') {
                        // Default custom selection to weekdays if nothing was set yet.
                        if (!s.days || s.days.length === 0 || s.days.length === 7) {
                            s.days = [0, 1, 2, 3, 4];
                        }
                        s.chargeWay = self._computeCustomChargeWay(s.days);
                    }
                    self.markScheduleDirty();
                    self.updateChargingUI();
                });
            })(segs[i]);
        }
        // Day chips — only meaningful when custom mode is active.
        var dayChips = document.getElementById('chargeDayChips');
        if (dayChips) {
            var chips = dayChips.querySelectorAll('.vc-day');
            for (var j = 0; j < chips.length; j++) {
                (function(chip) {
                    chip.addEventListener('click', function() {
                        if (self._smartChargePending) return;
                        var day = parseInt(chip.getAttribute('data-day'), 10);
                        var s = self.vehicleState.chargingSchedule;
                        if (!s.days) s.days = [];
                        var idx = s.days.indexOf(day);
                        if (idx >= 0) s.days.splice(idx, 1);
                        else s.days.push(day);
                        s.days.sort(function(a, b) { return a - b; });
                        s.chargeWay = self._computeCustomChargeWay(s.days);
                        self.markScheduleDirty();
                        self.updateChargingUI();
                    });
                })(chips[j]);
            }
        }
        // "Until full" preset → endChargeTime sentinel.
        this.bindBtn('btnChargeUntilFull', function() {
            if (self._smartChargePending) return;
            var s = self.vehicleState.chargingSchedule;
            s.untilFull = !s.untilFull;
            self.markScheduleDirty();
            self.updateChargingUI();
        });
        // Time inputs — write directly to local state; no API call until Save.
        var startInput = document.getElementById('chargeStartTime');
        if (startInput) {
            startInput.addEventListener('change', function() {
                if (self._smartChargePending) return;
                self.vehicleState.chargingSchedule.startChargeTime = startInput.value || '22:00';
                self.markScheduleDirty();
            });
        }
        var endInput = document.getElementById('chargeEndTime');
        if (endInput) {
            endInput.addEventListener('change', function() {
                if (self._smartChargePending) return;
                self.vehicleState.chargingSchedule.endChargeTime = endInput.value || '06:00';
                // Editing the time clears the "until full" sentinel.
                if (self.vehicleState.chargingSchedule.untilFull) {
                    self.vehicleState.chargingSchedule.untilFull = false;
                    self.updateChargingUI();
                }
                self.markScheduleDirty();
            });
        }
        // Save — writes saveOrUpdate. Schedule save carries its own status,
        // so the master toggle isn't a precondition.
        this.bindBtn('btnChargeScheduleSave', function() {
            if (!self.requireCloud()) return;
            if (self._smartChargePending) return;
            var s = self.vehicleState.chargingSchedule;
            var way = s.chargeWay || 'e';
            // Custom mode with no days picked is a no-op — refuse to send.
            if (self._scheduleMode(way) === 'custom') {
                if (!s.days || s.days.length === 0) {
                    self.toastFromResult({ success: false, error: BYD.i18n.t('vehicle_control.pick_a_day') }, null, null);
                    return;
                }
                way = self._computeCustomChargeWay(s.days);
            }
            var payload = {
                startChargeTime: s.startChargeTime || '22:00',
                endChargeTime: s.untilFull ? 'full' : (s.endChargeTime || '06:00'),
                chargeWay: way,
                enabled: s.enabled !== false
            };
            if (!self.beginSmartChargeRequest('save', 'btnChargeScheduleSave')) return;
            self.apiPost('/api/vehicle/charging-schedule', payload).then(function(result) {
                if (self.finishSmartChargeRequest('save')) {
                    if (result.success) {
                        s.enabled = payload.enabled;
                        self._scheduleDirty = false;
                        self.updateChargingUI();
                    }
                    self.toastFromResult(result,
                        BYD.i18n.t('vehicle_control.schedule_saved'),
                        BYD.i18n.t('vehicle_control.schedule_save_failed'));
                }
            }).catch(function(e) {
                if (self.finishSmartChargeRequest('save')) {
                    self.toastFromResult({ success: false, error: e.message },
                        null, BYD.i18n.t('vehicle_control.schedule_save_failed'));
                }
            });
        });
        this.fetchChargingSchedule();

        // === BEV CHARGE CAP (SDK_ONLY) ===
        // setChargeStopCapacityState (50..100%) + setChargeStopSwitchState.
        // The collector probes write/read-back on first call; if the framework
        // doesn't honor the value (the documented Seal HAL behavior) the GET
        // returns supported=false and we hide the section.
        if (!this.vehicleState.chargeCap) {
            this.vehicleState.chargeCap = { percent: null, enabled: null, supported: null };
        }
        this.bindBtn('btnChargeCapToggle', function() {
            var s = self.vehicleState.chargeCap;
            var enable = !s.enabled;
            var revision = ++self._chargeCapRevision;
            self._chargeCapPendingRevision = revision;
            self.apiPost('/api/vehicle/charge-cap', { enabled: enable }).then(function(result) {
                if (revision !== self._chargeCapRevision) return;
                self._chargeCapPendingRevision = 0;
                if (result && result.success) {
                    s.enabled = typeof result.enabled === 'boolean' ? result.enabled : null;
                    if (typeof result.supported === 'boolean') s.supported = result.supported;
                    if (typeof result.minimumPercent === 'number') s.minimumPercent = result.minimumPercent;
                    if (typeof result.maximumPercent === 'number') s.maximumPercent = result.maximumPercent;
                    if (typeof result.controlKind === 'string') s.controlKind = result.controlKind;
                    self.updateChargeCapUI();
                } else {
                    // The toggle has no optimistic state, but a failed response can
                    // invalidate capability/readback state. Reconcile it exactly as
                    // a rejected request does, while this revision still owns the UI.
                    self.updateChargeCapUI();
                    self.fetchChargeCap();
                }
                self.toastFromResult(result, null, null);
            }).catch(function(e) {
                if (revision !== self._chargeCapRevision) return;
                self._chargeCapPendingRevision = 0;
                self.updateChargeCapUI();
                self.fetchChargeCap();
                self.toastFromResult({ success: false, error: e.message }, null, null);
            });
        });
        var capSlider = document.getElementById('chargeCapSlider');
        if (capSlider) {
            var capDebounce = null;
            var capRevision = 0;
            capSlider.addEventListener('input', function() {
                var v = parseInt(capSlider.value, 10);
                var revision = ++capRevision;
                var stateRevision = ++self._chargeCapRevision;
                self._chargeCapPendingRevision = stateRevision;
                var readout = document.getElementById('chargeCapReadout');
                if (readout) readout.textContent = v + '%';
                if (capDebounce) clearTimeout(capDebounce);
                capDebounce = setTimeout(function() {
                    self.apiPost('/api/vehicle/charge-cap', { percent: v }).then(function(result) {
                        // A newer drag supersedes this request's visual result.
                        // The later request is responsible for publishing the
                        // currently selected verified charge-stop limit.
                        if (revision !== capRevision || stateRevision !== self._chargeCapRevision) return;
                        self._chargeCapPendingRevision = 0;
                        if (result.success) {
                            // Only reflect a charge-stop value confirmed by the
                            // server's direct register readback.
                            if (typeof result.percent === 'number'
                                    && result.percent >= 50 && result.percent <= 100) {
                                self.vehicleState.chargeCap.percent = result.percent;
                            }
                            if (typeof result.supported === 'boolean') {
                                self.vehicleState.chargeCap.supported = result.supported;
                            }
                            if (typeof result.minimumPercent === 'number') {
                                self.vehicleState.chargeCap.minimumPercent = result.minimumPercent;
                            }
                            if (typeof result.maximumPercent === 'number') {
                                self.vehicleState.chargeCap.maximumPercent = result.maximumPercent;
                            }
                            if (typeof result.controlKind === 'string') {
                                self.vehicleState.chargeCap.controlKind = result.controlKind;
                            }
                            self.updateChargeCapUI();
                        } else {
                            // The state remains the last direct-readback value;
                            // restore it before an optional refresh that may fail.
                            self.updateChargeCapUI();
                            self.fetchChargeCap();
                        }
                        self.toastFromResult(result, null, null);
                    }).catch(function(e) {
                        if (revision !== capRevision || stateRevision !== self._chargeCapRevision) return;
                        self._chargeCapPendingRevision = 0;
                        console.debug('[VC] charge-cap POST threw', e);
                        // Keep a rejected request from leaving its drag preview
                        // in the DOM when no response object is available.
                        self.updateChargeCapUI();
                    });
                }, 350);
            });
        }
        this.fetchChargeCap();

        // === OEM AC CHARGING CURRENT LIMIT (SDK_ONLY) ===
        // Five discrete states exposed by BYDAutoSettingDevice:
        // 1=6 A, 2=8 A, 3=10 A, 4=16 A, 5=maximum.
        var currentSegments = document.querySelectorAll('#acChargeCurrentSegmented .vc-seg');
        for (var currentIndex = 0; currentIndex < currentSegments.length; currentIndex++) {
            (function(segment) {
                segment.addEventListener('click', function() {
                    var nextState = parseInt(segment.getAttribute('data-state'), 10);
                    var currentLimit = self.vehicleState.acChargeCurrentLimit || {};
                    if (nextState < 1 || nextState > 5
                            || currentLimit.supported !== true
                            || currentLimit.available !== true
                            || self._acChargeCurrentPendingRevision) return;
                    var revision = ++self._acChargeCurrentRevision;
                    self._acChargeCurrentPendingRevision = revision;
                    self.updateAcChargeCurrentUI();
                    self.apiPost('/api/vehicle/ac-charge-current-limit',
                        { state: nextState }).then(function(result) {
                        if (revision !== self._acChargeCurrentRevision) return;
                        self._acChargeCurrentPendingRevision = 0;
                        if (result && typeof result.state === 'number'
                                && result.state >= 1 && result.state <= 5) {
                            self.vehicleState.acChargeCurrentLimit.state = result.state;
                            self.vehicleState.acChargeCurrentLimit.supported =
                                result.supported !== false;
                            self.vehicleState.acChargeCurrentLimit.available =
                                result.available !== false;
                        } else if (result && result.supported === false) {
                            self.vehicleState.acChargeCurrentLimit.supported = false;
                            self.vehicleState.acChargeCurrentLimit.available = true;
                        } else if (result && result.available === false) {
                            self.vehicleState.acChargeCurrentLimit.available = false;
                        }
                        self.vehicleState.acChargeCurrentLimit.checked = true;
                        self.updateAcChargeCurrentUI();
                        self.toastFromResult(result, null, null);
                    }).catch(function(e) {
                        if (revision !== self._acChargeCurrentRevision) return;
                        self._acChargeCurrentPendingRevision = 0;
                        self.updateAcChargeCurrentUI();
                        self.fetchAcChargeCurrentLimit();
                        self.toastFromResult({ success: false, error: e.message }, null, null);
                    });
                });
            })(currentSegments[currentIndex]);
        }
        this.updateAcChargeCurrentUI();

        // === CLIMATE CONTROLS ===
        function submitClimateValue(field, next, request) {
            var property = field === 'temp' ? 'acTemp' : 'acFan';
            var previous = self.vehicleState[property];
            var revision = self.beginClimateMutation(field);
            self.vehicleState[property] = next;
            self.updateClimateUI();
            self.apiPost('/api/vehicle/climate', request).then(function(result) {
                if (!self.finishClimateMutation(field, revision)) return;
                if (!result || !result.success) {
                    self.vehicleState[property] = previous;
                    self.updateClimateUI();
                    self.fetchState();
                    self.toastFromResult(result || { success: false },
                        null, BYD.i18n.t('vehicle.ac_command_failed'));
                }
            }).catch(function(e) {
                if (!self.finishClimateMutation(field, revision)) return;
                self.vehicleState[property] = previous;
                self.updateClimateUI();
                self.fetchState();
                self.toastFromResult({ success: false, error: e.message },
                    null, BYD.i18n.t('vehicle.ac_command_failed'));
            });
        }

        function submitClimatePower(next, request) {
            var previous = self.vehicleState.acOn;
            var revision = self.beginClimateMutation('power');
            self.apiPost('/api/vehicle/climate', request).then(function(result) {
                if (!self.finishClimateMutation('power', revision)) return;
                self.vehicleState.acOn = result && result.success ? next : previous;
                self.updateClimateUI();
                if (!result || !result.success) self.fetchState();
                self.toastFromResult(result || { success: false },
                    next ? BYD.i18n.t('vehicle.ac_on') : BYD.i18n.t('vehicle.ac_off'),
                    BYD.i18n.t('vehicle.ac_command_failed'));
            }).catch(function(e) {
                if (!self.finishClimateMutation('power', revision)) return;
                self.vehicleState.acOn = previous;
                self.updateClimateUI();
                self.fetchState();
                self.toastFromResult({ success: false, error: e.message },
                    null, BYD.i18n.t('vehicle.ac_command_failed'));
            });
        }

        this.bindBtn('btnAcOn', function() {
            self.triggerSonarVFX(0, 0.6, 0.2, new THREE.Color(0x38BDF8));
            self.triggerSonarVFX(0, 0.6, -0.2, new THREE.Color(0x38BDF8));
            self.flashBodyColor(new THREE.Color(0x38BDF8), 0.1, 2, null);
            // remoteDurationMinutes only bites when the car is asleep and the router
            // falls through to OPENAIR — a timed remote session. It shares BYD's
            // timeSpan wire field with the booking below, so one control sets both
            // rather than the page hardcoding the 20-minute default.
            submitClimatePower(true, {
                action: 'power_on',
                temp: self.vehicleState.acTemp,
                remoteDurationMinutes: self._remoteSessionMinutes()
            });
        });
        this.bindBtn('btnAcOff', function() {
            self.flashBodyColor(new THREE.Color(0x71717A), 0.15, 1, null);
            submitClimatePower(false, { action: 'power_off' });
        });
        this.bindBtn('btnTempUp', function() {
            var t = Math.min(33, self.vehicleState.acTemp + 1);
            // Warm pulse for temp up
            self.triggerSonarVFX(0, 0.6, 0, new THREE.Color(t > 25 ? 0xFF6B35 : 0x38BDF8));
            submitClimateValue('temp', t, { action: 'set_temp', zone: 1, temp: t });
        });
        this.bindBtn('btnTempDown', function() {
            var t = Math.max(17, self.vehicleState.acTemp - 1);
            // Cool pulse for temp down
            self.triggerSonarVFX(0, 0.6, 0, new THREE.Color(t < 20 ? 0x38BDF8 : 0x00D4AA));
            submitClimateValue('temp', t, { action: 'set_temp', zone: 1, temp: t });
        });
        this.bindBtn('btnFanUp', function() {
            var f = Math.min(7, self.vehicleState.acFan + 1);
            // Multiple sonar rings for higher fan — more rings = more wind
            for (var fi = 0; fi < Math.min(f, 3); fi++) {
                (function(delay) {
                    setTimeout(function() { self.triggerSonarVFX(0, 0.5, 0.3 - delay * 0.3, new THREE.Color(0x00D4AA)); }, delay * 80);
                })(fi);
            }
            submitClimateValue('fan', f, { action: 'set_fan', fan: f });
        });
        this.bindBtn('btnFanDown', function() {
            var f = Math.max(1, self.vehicleState.acFan - 1);
            self.triggerSonarVFX(0, 0.5, 0, new THREE.Color(0x52525B));
            submitClimateValue('fan', f, { action: 'set_fan', fan: f });
        });

        // === REMOTE PRECONDITIONING SCHEDULE (cloud BOOKINGAIR) ===
        // One booking: time-of-day + whole-degree temp + one of five OEM session
        // lengths. Editing an existing booking sends "update" with its id; Save
        // with no known id sends "create". Cloud-only — no SDK equivalent exists.
        if (!this.vehicleState.climateSchedule) {
            this.vehicleState.climateSchedule = {
                bookingId: null,
                time: '07:30',
                temp: 22,
                durationMinutes: 20,
                supported: null,
                // Cloud accepted a booking but reported no id for it — see the note
                // rendering in updateClimateScheduleUI.
                savedUnconfirmed: false,
                reportedTime: null,
                reportedTemp: null,
                reportedDuration: null
            };
        }
        var bookingTimeInput = document.getElementById('climateBookingTime');
        if (bookingTimeInput) {
            bookingTimeInput.addEventListener('change', function() {
                if (self._climateSchedulePending) return;
                self.vehicleState.climateSchedule.time = bookingTimeInput.value || '07:30';
                self._climateScheduleDirty = true;
            });
        }
        var durationSegs = document.querySelectorAll('#climateDurationSegmented .vc-seg');
        for (var di = 0; di < durationSegs.length; di++) {
            (function(seg) {
                seg.addEventListener('click', function() {
                    if (self._climateSchedulePending) return;
                    var minutes = parseInt(seg.getAttribute('data-duration'), 10);
                    if (isNaN(minutes)) return;
                    self.vehicleState.climateSchedule.durationMinutes = minutes;
                    self._climateScheduleDirty = true;
                    self.updateClimateScheduleUI();
                });
            })(durationSegs[di]);
        }
        // BOOKINGAIR accepts whole degrees 15..31 — a narrower band than the local
        // dial's 17..33, so clamp to the cloud's domain rather than the dial's.
        this.bindBtn('btnBookingTempUp', function() {
            if (self._climateSchedulePending) return;
            var s = self.vehicleState.climateSchedule;
            s.temp = Math.min(31, (s.temp || 22) + 1);
            self._climateScheduleDirty = true;
            self.updateClimateScheduleUI();
        });
        this.bindBtn('btnBookingTempDown', function() {
            if (self._climateSchedulePending) return;
            var s = self.vehicleState.climateSchedule;
            s.temp = Math.max(15, (s.temp || 22) - 1);
            self._climateScheduleDirty = true;
            self.updateClimateScheduleUI();
        });
        this.bindBtn('btnClimateScheduleSave', function() {
            if (!self.requireCloud()) return;
            if (self._climateSchedulePending) return;
            var s = self.vehicleState.climateSchedule;
            var epoch = self._nextOccurrenceEpochSeconds(s.time);
            if (epoch === null) {
                self.toastFromResult({ success: false,
                    error: BYD.i18n.t('vehicle.precondition_bad_time') }, null, null);
                return;
            }
            var payload = {
                action: s.bookingId ? 'update' : 'create',
                bookingTime: epoch,
                temp: s.temp || 22,
                durationMinutes: s.durationMinutes || 20
            };
            var wasUpdate = !!s.bookingId;
            if (wasUpdate) payload.bookingId = s.bookingId;
            if (!self.beginClimateScheduleRequest('save', 'btnClimateScheduleSave')) return;
            self.apiPost('/api/vehicle/climate-schedule', payload).then(function(result) {
                if (!self.finishClimateScheduleRequest('save')) return;
                if (result && result.success) {
                    // A create is not echoed an id (BYD's response body isn't surfaced),
                    // so the id is learned from the follow-up list read below. When the
                    // server DOES echo one it is decimal TEXT — 64-bit booking ids exceed
                    // JS's exact-integer range, so never coerce to Number.
                    if (typeof result.bookingId === 'string' && result.bookingId) {
                        s.bookingId = result.bookingId;
                    }
                    // Cleared again by the list read below if it returns a real id.
                    s.savedUnconfirmed = !s.bookingId;
                    self._climateScheduleDirty = false;
                    self.updateClimateScheduleUI();
                    self.fetchClimateSchedule();
                } else if (wasUpdate) {
                    // An update against an id the cloud no longer honours would fail
                    // identically forever, and Clear would fail too — the editor would be
                    // wedged for the session. Drop the id so the next Save creates afresh,
                    // and re-read to recover the real one if the booking does still exist.
                    s.bookingId = null;
                    s.reportedTime = null;
                    s.reportedTemp = null;
                    s.reportedDuration = null;
                    // The user's pending edit is preserved, but the dirty flag must not
                    // keep blocking the reconciling read.
                    self._climateScheduleDirty = false;
                    self.updateClimateScheduleUI();
                    self.fetchClimateSchedule();
                }
                self.toastFromResult(result || { success: false },
                    BYD.i18n.t('vehicle.precondition_saved'),
                    BYD.i18n.t('vehicle.precondition_save_failed'));
            }).catch(function(e) {
                if (!self.finishClimateScheduleRequest('save')) return;
                self.toastFromResult({ success: false, error: e.message },
                    null, BYD.i18n.t('vehicle.precondition_save_failed'));
            });
        });
        this.bindBtn('btnClimateScheduleClear', function() {
            if (!self.requireCloud()) return;
            if (self._climateSchedulePending) return;
            var s = self.vehicleState.climateSchedule;
            // Delete is only meaningful against a known id. The button is hidden
            // without one, so this is a guard, not a reachable UI state.
            if (!s.bookingId) return;
            if (!self.beginClimateScheduleRequest('clear', 'btnClimateScheduleClear')) return;
            self.apiPost('/api/vehicle/climate-schedule',
                { action: 'delete', bookingId: s.bookingId }).then(function(result) {
                if (!self.finishClimateScheduleRequest('clear')) return;
                // Either the delete succeeded, or the id is one the cloud will not act
                // on — in both cases keeping it would only wedge every later Save and
                // Clear against a booking we cannot address. Drop it and re-read; a
                // booking that genuinely survives comes back with its real id.
                s.bookingId = null;
                s.reportedTime = null;
                s.reportedTemp = null;
                s.reportedDuration = null;
                s.savedUnconfirmed = false;
                self._climateScheduleDirty = false;
                self.updateClimateScheduleUI();
                self.fetchClimateSchedule();
                self.toastFromResult(result || { success: false },
                    BYD.i18n.t('vehicle.precondition_cleared'),
                    BYD.i18n.t('vehicle.precondition_clear_failed'));
            }).catch(function(e) {
                if (!self.finishClimateScheduleRequest('clear')) return;
                self.toastFromResult({ success: false, error: e.message },
                    null, BYD.i18n.t('vehicle.precondition_clear_failed'));
            });
        });
        this.updateClimateScheduleUI();

        // === EXTERIOR SPEAKER (AVAS) CONTROLS ===
        // Tone tiles carry data-avas-pattern; POST the index to /api/audio/avas-tone.
        var toneBtns = document.querySelectorAll('#panelSound [data-avas-pattern]');
        for (var ti = 0; ti < toneBtns.length; ti++) {
            (function(btn) {
                var pattern = parseInt(btn.getAttribute('data-avas-pattern'), 10);
                self.bindBtn(btn.id, function() {
                    self.triggerSonarVFX(0, 0.6, 0, new THREE.Color(0xFBBF24));
                    self.apiPost('/api/audio/avas-tone', { pattern: pattern }).then(function(r) {
                        self.toastFromResult(r, BYD.i18n.t('vehicle.avas_playing'), BYD.i18n.t('vehicle.avas_failed'));
                    });
                });
            })(toneBtns[ti]);
        }
        this.bindBtn('btnAvasStop', function() {
            self.apiPost('/api/audio/avas-tone', { stop: true }).then(function(r) {
                self.toastFromResult(r, BYD.i18n.t('vehicle.avas_stopped'), BYD.i18n.t('vehicle.avas_failed'));
            });
        });
        this.bindBtn('btnEngineToggle', function() {
            var next = !self.vehicleState.engineSoundOn;
            self.apiPost('/api/audio/engine-sound', { on: next, preset: self.vehicleState.enginePreset || 1 }).then(function(r) {
                if (r.success) { self.vehicleState.engineSoundOn = next; self.updateEngineSoundUI(); }
                self.toastFromResult(r, BYD.i18n.t('vehicle.engine_sound'), BYD.i18n.t('vehicle.avas_failed'));
            });
        });
        this.bindBtn('btnEngineNext', function() {
            var p = (self.vehicleState.enginePreset || 1) + 1;
            self.apiPost('/api/audio/engine-sound', { preset: p }).then(function(r) {
                if (r.success && typeof r.preset === 'number' && r.preset >= 1) {
                    self.vehicleState.enginePreset = r.preset;
                    self.updateEngineSoundUI();
                }
            });
        });
        this.bindBtn('btnEnginePrev', function() {
            var p = Math.max(1, (self.vehicleState.enginePreset || 1) - 1);
            self.apiPost('/api/audio/engine-sound', { preset: p }).then(function(r) {
                if (r.success && typeof r.preset === 'number' && r.preset >= 1) {
                    self.vehicleState.enginePreset = r.preset;
                    self.updateEngineSoundUI();
                }
            });
        });

        // Seat heating — cycles 0→1→2→0
        var seatPositions = {
            1: { x: 0.5, y: 0.4, z: 0.2 },   // driver
            2: { x: -0.5, y: 0.4, z: 0.2 }    // passenger
        };
        function seatLevelFeedbackMessage(kind, level) {
            var levelLabel = level === 1
                ? (BYD.i18n.t('vehicle.level_low') || 'Low')
                : (BYD.i18n.t('vehicle.level_high') || 'High');
            if (kind === 'heating') {
                if (level > 0) {
                    return BYD.i18n.t('vehicle.seat_heat_level', {
                        level: levelLabel
                    });
                }
                return BYD.i18n.t('vehicle.seat_heat_off');
            }
            if (level > 0) {
                return BYD.i18n.t('vehicle.seat_cool_level', {
                    level: levelLabel
                });
            }
            return BYD.i18n.t('vehicle.seat_cool_off');
        }

        function showSeatLevelVfx(pos, kind, level) {
            var sp = seatPositions[pos];
            if (!sp || level <= 0) return;
            if (kind === 'heating') {
                var heatColor = level === 2 ? 0xFF4500 : 0xFF8C00;
                self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(heatColor));
                if (level === 2) {
                    setTimeout(function() {
                        self.triggerSonarVFX(sp.x, sp.y + 0.2, sp.z, new THREE.Color(0xFF4500));
                    }, 120);
                }
            } else {
                var coolColor = level === 2 ? 0x00BFFF : 0x87CEEB;
                self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(coolColor));
                if (level === 2) {
                    setTimeout(function() {
                        self.triggerSonarVFX(sp.x, sp.y + 0.2, sp.z, new THREE.Color(0x00BFFF));
                    }, 120);
                }
            }
        }

        function submitSeatLevel(pos, kind, level, previous) {
            var revision = ++self._seatCommandRevision;
            self._seatPending = revision;
            self.apiPost('/api/vehicle/seat', {
                action: kind, position: pos, level: level,
                driverHeat: self.vehicleState.seatHeat[0] || 0,
                driverVent: self.vehicleState.seatCool[0] || 0,
                passengerHeat: self.vehicleState.seatHeat[1] || 0,
                passengerVent: self.vehicleState.seatCool[1] || 0
            }).then(function(result) {
                if (revision !== self._seatCommandRevision) return;
                self._seatPending = 0;
                if (result && result.success) {
                    var feedbackMessage = seatLevelFeedbackMessage(kind, level);
                    self.toastFromResult({
                        success: true,
                        path: result.path,
                        latencyMs: result.latencyMs,
                        outcome: result.outcome,
                        message: feedbackMessage || result.message
                    }, feedbackMessage, BYD.i18n.t('vehicle.ac_command_failed'));
                    // Acknowledgement is independent of the optional 3D effect:
                    // even if the scene is unavailable, the command result is visible.
                    showSeatLevelVfx(pos, kind, level);
                    return;
                }
                self.vehicleState.seatHeat = previous.heat;
                self.vehicleState.seatCool = previous.cool;
                self.updateSeatUI();
                self.updateSeatGlows();
                self.fetchState();
                self.toastFromResult(result || { success: false }, null,
                    BYD.i18n.t('vehicle.ac_command_failed'));
            }).catch(function(e) {
                if (revision !== self._seatCommandRevision) return;
                self._seatPending = 0;
                self.vehicleState.seatHeat = previous.heat;
                self.vehicleState.seatCool = previous.cool;
                self.updateSeatUI();
                self.updateSeatGlows();
                self.fetchState();
                self.toastFromResult({ success: false, error: e.message }, null,
                    BYD.i18n.t('vehicle.ac_command_failed'));
            });
        }

        for (var si = 1; si <= 2; si++) {
            (function(pos) {
                self.bindBtn('btnSeatHeat' + pos, function() {
                    var previous = {
                        heat: self.vehicleState.seatHeat.slice(0),
                        cool: self.vehicleState.seatCool.slice(0)
                    };
                    var cur = self.vehicleState.seatHeat[pos - 1] || 0;
                    var next = (cur + 1) % 3;
                    self.vehicleState.seatHeat[pos - 1] = next;
                    self.vehicleState.seatCool[pos - 1] = 0;
                    self.updateSeatUI();
                    self.updateSeatGlows();
                    submitSeatLevel(pos, 'heating', next, previous);
                });
                self.bindBtn('btnSeatCool' + pos, function() {
                    var previous = {
                        heat: self.vehicleState.seatHeat.slice(0),
                        cool: self.vehicleState.seatCool.slice(0)
                    };
                    var cur = self.vehicleState.seatCool[pos - 1] || 0;
                    var next = (cur + 1) % 3;
                    self.vehicleState.seatCool[pos - 1] = next;
                    self.vehicleState.seatHeat[pos - 1] = 0;
                    self.updateSeatUI();
                    self.updateSeatGlows();
                    submitSeatLevel(pos, 'ventilation', next, previous);
                });
            })(si);
        }

        // Steering-wheel heater — a plain on/off surface (no low/high), routed
        // SDK-first with a composite cloud fallback. `steeringHeat` is null until
        // a reading arrives, so the first tap targets ON rather than assuming OFF.
        this.bindBtn('btnSteeringHeat', function() {
            var previous = self.vehicleState.steeringHeat;
            var next = !previous;
            var revision = ++self._steeringHeatRevision;
            // The pending flag — not the revision alone — is what protects the
            // in-flight window. The routed write can take up to the cloud budget,
            // and the 3s poll would otherwise read the not-yet-updated server
            // state and revert the tile while the command is still succeeding.
            self._steeringHeatPending = revision;
            self.vehicleState.steeringHeat = next;
            self.updateSteeringHeatUI();
            self.apiPost('/api/vehicle/climate',
                { action: next ? 'steering_heat_on' : 'steering_heat_off' }).then(function(result) {
                if (revision !== self._steeringHeatRevision) return;
                self._steeringHeatPending = 0;
                if (!result || !result.success) {
                    self.vehicleState.steeringHeat = previous;
                    self.updateSteeringHeatUI();
                    self.fetchState();
                }
                self.toastFromResult(result || { success: false },
                    BYD.i18n.t(next ? 'vehicle.steering_heat_on' : 'vehicle.steering_heat_off'),
                    BYD.i18n.t('vehicle.ac_command_failed'));
            }).catch(function(e) {
                if (revision !== self._steeringHeatRevision) return;
                self._steeringHeatPending = 0;
                self.vehicleState.steeringHeat = previous;
                self.updateSteeringHeatUI();
                self.fetchState();
                self.toastFromResult({ success: false, error: e.message }, null,
                    BYD.i18n.t('vehicle.ac_command_failed'));
            });
        });

        // Seat memory positions — driver-side (BYD SDK supports up to 2 stored slots).
        // TAP = recall the stored slot (move seat to it); LONG-PRESS = save the
        // seat's current position into that slot (mirrors the physical door memory
        // buttons). Two distinct SDK feature ids back these — see BydDataCollector
        // setSeatMemoryPosition (WAKE/recall) vs setSeatMemorySave (SET/store).
        for (var smi = 1; smi <= 2; smi++) {
            (function(pos) {
                self.bindTileTapHold('btnSeatMemory' + pos,
                    function() {
                        // TAP → recall. Blue sonar at the driver seat.
                        var sp = seatPositions[1];
                        self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(0x00BFFF));
                        self.toast(BYD.i18n.t('vehicle.seat_memory_position', {pos: pos}), 'success');
                        self.apiPost('/api/vehicle/seat', { action: 'position', position: pos });
                    },
                    function() {
                        // LONG-PRESS → save current position. Green sonar to signal
                        // "stored" (distinct from the blue recall pulse).
                        var sp = seatPositions[1];
                        self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(0x00D4AA));
                        self.toast(BYD.i18n.t('vehicle.seat_memory_saved', {pos: pos}), 'success');
                        self.apiPost('/api/vehicle/seat', { action: 'save', position: pos });
                    });
            })(smi);
        }
    },

    /**
     * Bind a tile that distinguishes a short TAP from a LONG-PRESS (tap-and-hold).
     * Used by the seat-memory tiles: tap recalls, hold saves. SOTA-grade for the
     * Android 7.1 head-unit WebView:
     *   - Uses pointer events when available, falling back to touch+mouse; a single
     *     shared guard (bound flag) prevents double-binding across event families.
     *   - A hold timer (holdMs, default 650) fires onHold and marks the gesture
     *     "consumed" so the trailing tap/click does NOT also fire onTap.
     *   - Any move beyond a small slop, or leaving the tile, CANCELS the pending
     *     hold (so a scroll/drag never saves by accident).
     *   - A `.arming` class drives a fill animation during the hold for feedback;
     *     `.armed` flashes once on save. Both are cleared on release.
     *   - A 500ms post-fire dedupe (same as bindBtn) absorbs the synthetic click
     *     the platform emits after touchend.
     */
    bindTileTapHold: function(id, onTap, onHold, holdMs) {
        var el = document.getElementById(id);
        if (!el) return;
        holdMs = holdMs || 650;
        var self = this;
        var timer = null;
        var startX = 0, startY = 0;
        var held = false;          // hold already fired for this gesture
        var active = false;        // a press is in progress
        var lastFire = 0;          // shared tap/hold dedupe window
        var SLOP = 12;             // px of movement that cancels the hold

        function fire(fn) {
            var now = Date.now();
            if (now - lastFire < 500) return;
            lastFire = now;
            try { fn.call(el); }
            catch (err) { console.error('[VC] tap/hold handler error for #' + id + ':', err); }
        }
        function clearArm() {
            el.classList.remove('arming');
            if (timer) { clearTimeout(timer); timer = null; }
        }
        function begin(x, y) {
            // Idempotent against overlapping/secondary pointers (multi-touch): a
            // second begin() before end() would orphan the first hold timer, which
            // then fires a spurious onHold (save) after release. Ignore any press
            // that arrives while one is already active.
            if (active) return;
            active = true; held = false;
            startX = x; startY = y;
            // reflow so the fill animation restarts each press
            el.classList.remove('arming');
            void el.offsetWidth;
            el.style.setProperty('--vc-hold-ms', holdMs + 'ms');
            el.classList.add('arming');
            timer = setTimeout(function() {
                held = true;
                clearArm();
                el.classList.add('armed');
                setTimeout(function() { el.classList.remove('armed'); }, 400);
                fire(onHold);
            }, holdMs);
        }
        function move(x, y) {
            if (!active) return;
            if (Math.abs(x - startX) > SLOP || Math.abs(y - startY) > SLOP) {
                active = false; clearArm();
            }
        }
        function end(commitTap) {
            if (!active && !held) { clearArm(); return; }
            var wasHeld = held;
            active = false; held = false;
            clearArm();
            if (!wasHeld && commitTap) fire(onTap);
        }

        if (window.PointerEvent) {
            el.addEventListener('pointerdown', function(e) { begin(e.clientX, e.clientY); });
            el.addEventListener('pointermove', function(e) { move(e.clientX, e.clientY); });
            el.addEventListener('pointerup',    function(e) { end(true); });
            el.addEventListener('pointercancel',function(e) { active = false; held = false; clearArm(); });
            el.addEventListener('pointerleave', function(e) { if (active) { active = false; clearArm(); } });
        } else {
            el.addEventListener('touchstart', function(e) {
                var t = e.touches[0]; begin(t ? t.clientX : 0, t ? t.clientY : 0);
            }, { passive: true });
            el.addEventListener('touchmove', function(e) {
                var t = e.touches[0]; if (t) move(t.clientX, t.clientY);
            }, { passive: true });
            el.addEventListener('touchend', function(e) {
                e.preventDefault();  // suppress the synthetic click after touchend
                end(true);
            }, { passive: false });
            el.addEventListener('touchcancel', function() { active = false; held = false; clearArm(); });
            // Mouse fallback for desktop/PWA testing.
            el.addEventListener('mousedown', function(e) { begin(e.clientX, e.clientY); });
            el.addEventListener('mousemove', function(e) { move(e.clientX, e.clientY); });
            el.addEventListener('mouseup',   function() { end(true); });
            el.addEventListener('mouseleave',function() { if (active) { active = false; clearArm(); } });
        }
    },

    bindBtn: function(id, handler) {
        var el = document.getElementById(id);
        if (!el) return;
        // Android 7.1 WebView occasionally drops `click` after a touch sequence
        // (long-press cancellation, fast taps, gesture conflicts). Bind both and
        // de-duplicate via a 500ms guard so only one fire per real interaction.
        var lastFire = 0;
        function fire(e) {
            var now = Date.now();
            if (now - lastFire < 500) return;
            lastFire = now;
            try { handler.call(el, e); }
            catch (err) { console.error('[VC] handler error for #' + id + ':', err); }
        }
        el.addEventListener('click', fire);
        el.addEventListener('touchend', function(e) {
            // Suppress the synthetic click that follows touchend on Android,
            // and prevent double-fire from the dedupe window.
            e.preventDefault();
            fire(e);
        }, { passive: false });
    },

    setPending: function(id, pending) {
        var el = document.getElementById(id);
        if (!el) return;
        if (pending) {
            el.classList.add('pending');
        } else {
            el.classList.remove('pending');
        }
    },

    beginClimateMutation: function(field) {
        var revisionKey = field === 'power' ? '_climatePowerRevision'
            : field === 'temp' ? '_climateTempRevision'
            : '_climateFanRevision';
        var revision = (this[revisionKey] || 0) + 1;
        this[revisionKey] = revision;
        this._climatePending[field] = revision;
        return revision;
    },

    isClimateMutationCurrent: function(field, revision) {
        return this._climatePending[field] === revision;
    },

    finishClimateMutation: function(field, revision) {
        if (!this.isClimateMutationCurrent(field, revision)) return false;
        this._climatePending[field] = 0;
        return true;
    },

    markScheduleDirty: function() {
        this._scheduleDirty = true;
        this._chargingScheduleRevision++;
    },

    beginSmartChargeRequest: function(kind, buttonId) {
        if (this._smartChargePending) return false;
        this._smartChargePending = { kind: kind, buttonId: buttonId };
        this._chargingScheduleRevision++;
        this.setPending(buttonId, true);
        this.updateChargingUI();
        return true;
    },

    finishSmartChargeRequest: function(kind) {
        var pending = this._smartChargePending;
        if (!pending || pending.kind !== kind) return false;
        this._smartChargePending = null;
        this.setPending(pending.buttonId, false);
        this.updateChargingUI();
        return true;
    },

    beginClimateScheduleRequest: function(kind, buttonId) {
        if (this._climateSchedulePending) return false;
        this._climateSchedulePending = { kind: kind, buttonId: buttonId };
        this._climateScheduleRevision++;
        this.setPending(buttonId, true);
        this.updateClimateScheduleUI();
        return true;
    },

    finishClimateScheduleRequest: function(kind) {
        var pending = this._climateSchedulePending;
        if (!pending || pending.kind !== kind) return false;
        this._climateSchedulePending = null;
        this.setPending(pending.buttonId, false);
        this.updateClimateScheduleUI();
        return true;
    },

    // ==================== STATE SYNC ====================

    startStateSync: function() {
        var self = this;
        if (this.pollInterval) clearInterval(this.pollInterval);
        this.fetchState();
        this.pollInterval = setInterval(function() { self.fetchState(); }, 3000);
    },

    /**
     * Stop/restart the three pollers with page visibility. A hidden WebView kept
     * hitting /api/vehicle/state every 3s, and each of those spawns a server-side
     * refresh thread on the head unit.
     */
    initVisibilitySync: function() {
        var self = this;
        document.addEventListener('visibilitychange', function() {
            if (document.hidden) {
                self.stopSyncPollers();
            } else {
                self.startStateSync();
                self.startAcChargeCurrentSync();
                self.startCloudStatusSync();
                self.startCloudLockSync();
            }
        });
    },

    stopSyncPollers: function() {
        if (this.pollInterval) { clearInterval(this.pollInterval); this.pollInterval = null; }
        if (this.cloudStatusInterval) { clearInterval(this.cloudStatusInterval); this.cloudStatusInterval = null; }
        if (this.cloudLockInterval) { clearInterval(this.cloudLockInterval); this.cloudLockInterval = null; }
        if (this.acChargeCurrentInterval) {
            clearInterval(this.acChargeCurrentInterval);
            this.acChargeCurrentInterval = null;
        }
    },

    fetchState: function() {
        var self = this;
        var climatePowerRevision = this._climatePowerRevision;
        var climateFanRevision = this._climateFanRevision;
        var seatCommandRevision = this._seatCommandRevision;
        var steeringHeatRevision = this._steeringHeatRevision;
        var batteryHeatRevision = this._batteryHeatRevision;
        fetch('/api/vehicle/state').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            if (!data.success) return;

            var wasLocked = self.vehicleState.locked;
            var wasLockScope = self.vehicleState.lockScope;

            // Doors (lock status: 1=locked, 2=unlocked)
            if (data.doors) {
                var d = data.doors;
                self.vehicleState.doors = {
                    lf: d.lf || -1, rf: d.rf || -1,
                    lr: d.lr || -1, rr: d.rr || -1,
                    trunk: d.trunk || -1, hood: d.hood || -1
                };
                var overall = (d.overall !== undefined && d.overall !== null) ? d.overall : -1;
                var reportedScope = d.scope
                    || (d.source === 'ota' ? 'driver_door' : 'vehicle');
                if (overall === 1) {
                    self.vehicleState.locked = true;
                    self.vehicleState.lockScope = reportedScope;
                    self.vehicleState.lockSource = d.source || null;
                } else if (overall === 2) {
                    self.vehicleState.locked = false;
                    self.vehicleState.lockScope = reportedScope;
                    self.vehicleState.lockSource = d.source || null;
                } else {
                    // Unknown from all vehicle sources — keep the last known
                    // state if we had one.
                    // Only set to null if we never received a valid state
                    if (wasLocked === null) {
                        self.vehicleState.locked = null;
                        self.vehicleState.lockScope = 'unknown';
                        self.vehicleState.lockSource = null;
                    } else {
                        self.vehicleState.lockScope = wasLockScope;
                    }
                }
            }

            // Windows
            if (data.windows) {
                var w = data.windows;
                // Keep -1 ("no reading") distinct from 0 ("closed"). Coercing it to 0 asserted
                // "window closed" for a window we could not read — and it made the '--%' branch in
                // updateWindowBars unreachable, so an unreadable window rendered as fully shut.
                var winPct = function(v) {
                    return (typeof v === 'number' && v >= 0 && v <= 100) ? v : -1;
                };
                self.vehicleState.windows = {
                    lf: winPct(w.lf),
                    rf: winPct(w.rf),
                    lr: winPct(w.lr),
                    rr: winPct(w.rr),
                    sunroof: winPct(w.sunroof),
                    sunshade: winPct(w.sunshade)
                };
            }

            // Battery
            if (data.battery) {
                self.vehicleState.soc = data.battery.soc || 0;
                self.vehicleState.rangeKm = data.battery.rangeKm || data.battery.bodyworkRangeKm || 0;
            }

            // Climate
            if (data.climate) {
                // A remote OPENAIR session is valid while the local SDK reports
                // the sleeping vehicle as off. Prefer that explicit cloud-backed
                // signal, but retain local AC state when no remote session exists.
                if (data.climate.remoteClimateActive === true
                        && climatePowerRevision === self._climatePowerRevision
                        && !self._climatePending.power) {
                    self.vehicleState.acOn = true;
                } else if (climatePowerRevision === self._climatePowerRevision
                        && !self._climatePending.power
                        && data.climate.acOn !== undefined) {
                    self.vehicleState.acOn = data.climate.acOn;
                }
                if (climateFanRevision === self._climateFanRevision
                        && !self._climatePending.fan
                        && data.climate.fanLevel !== undefined
                        && data.climate.fanLevel >= 1 && data.climate.fanLevel <= 7) {
                    self.vehicleState.acFan = data.climate.fanLevel;
                }
                if (data.climate.insideTempC !== undefined && data.climate.insideTempC > 0) {
                    // Use inside temp as display reference (actual set temp not available from state)
                }
            }

            // Tyres — populate the corner callouts (also handles the
            // tyres.available === false case by setting every corner to
            // 'muted' / NO DATA).
            if (data.tyres) self.updateTyreCallouts(data.tyres);

            if (data.lights) self.vehicleState.lights = data.lights;
            if (data.adas) self.vehicleState.adas = data.adas;
            if (data.setting) self.vehicleState.setting = data.setting;

            if (seatCommandRevision === self._seatCommandRevision && !self._seatPending) {
                if (data.seats && data.seats.heat) self.vehicleState.seatHeat = data.seats.heat;
                if (data.seats && data.seats.cool) self.vehicleState.seatCool = data.seats.cool;
            }
            // Steering-wheel heat is omitted from the payload when neither the SDK
            // nor a fresh cloud snapshot could read it — leave the state null in
            // that case rather than rendering an unknown wheel as "off".
            if (steeringHeatRevision === self._steeringHeatRevision
                    && !self._steeringHeatPending
                    && data.seats && typeof data.seats.steeringHeat === 'boolean') {
                self.vehicleState.steeringHeat = data.seats.steeringHeat;
            }
            // Cloud-only, and omitted when no fresh snapshot reported it — so an
            // absent key leaves the previous reading rather than asserting "off".
            if (batteryHeatRevision === self._batteryHeatRevision
                    && !self._batteryHeatPending
                    && typeof data.batteryHeat === 'boolean') {
                self.vehicleState.batteryHeat = data.batteryHeat;
            }
            if (data.seats && data.seats.ventilatedSupported === false) {
                // Trim lacks ventilated seats — disable the cool buttons.
                // Cars without the hardware return hasFeature=0 from SDK and
                // 1001 from the BYD cloud, so neither path can succeed.
                var coolBtns = document.querySelectorAll('[id^="btnSeatCool"]');
                for (var ci = 0; ci < coolBtns.length; ci++) {
                    coolBtns[ci].setAttribute('disabled', 'true');
                    coolBtns[ci].classList.add('disabled');
                    coolBtns[ci].title = 'Ventilated seats not available on this trim';
                }
            }

            // Update UI
            self.updateHUD();
            self.updateWindowBars();
            self.updateDoorIndicators();
            self.updateTrunkIndicator();
            self.updateWindowGlows();
            self.updateClimateUI();
            self.updateSeatUI();
            self.updateSteeringHeatUI();
            self.updateBatteryHeatUI();
            self.updateSeatGlows();
            self.updateTabIndicators();
            self.updateLightsUI();
            self.updateAdasUI();

        }).catch(function(e) {
            console.warn('[VC] State fetch error:', e);
        });
    },

    startCloudStatusSync: function() {
        var self = this;
        this.updateCloudIndicator();
        this.updateCloudControlAvailability();
        this.checkCloudStatus();
        if (this.cloudStatusInterval) clearInterval(this.cloudStatusInterval);
        // Keep the badge and capability markers current if credentials are
        // connected or removed from Settings while this page remains open.
        this.cloudStatusInterval = setInterval(function() {
            self.checkCloudStatus();
        }, 30 * 1000);
    },

    checkCloudStatus: function() {
        var self = this;
        fetch('/api/vehicle/cloud-status').then(function(resp) {
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            return resp.json();
        }).then(function(data) {
            self.vehicleState.cloudConfigured = !!(data.configured && data.verified);
            self.vehicleState.cloudState = self.vehicleState.cloudConfigured
                ? 'connected'
                : 'not_configured';
            self.updateCloudIndicator();
            self.updateCloudControlAvailability();
            // Remote preconditioning is offered on the strength of the account alone
            // (see updateClimateScheduleUI), so re-render once that status is known —
            // otherwise the row waits on a booking-list read that may never land.
            self.updateClimateScheduleUI();
        }).catch(function(e) {
            // A failed probe means "status unknown", not "account gone". Clearing
            // cloudConfigured here would lock out every cloud control for 30s on
            // one dropped response, with valid credentials.
            self.vehicleState.cloudState = 'unavailable';
            self.updateCloudIndicator();
            self.updateCloudControlAvailability();
            console.warn('[VC] Cloud status error:', e);
        });
    },

    // Polls the cloud lock state. The server endpoint:
    //   - returns the cached MQTT-derived lock state immediately,
    //   - kicks off a one-shot REST refresh in the background if the cache
    //     is stale (rate-limited server-side, so this is cheap to call).
    // Used as a fallback for the lock-state UI: the CAN bus often returns
    // "unknown" while the car is sleeping; the cloud knows the answer.
    //
    // The server's background REST refresh typically completes in 1-3s but
    // its result lands in the next response, not this one. So when the
    // payload comes back stale (or missing) and CAN didn't give us a valid
    // value, we re-request after 3s to pick up the freshly-fetched data.
    // _isFollowup prevents the 3s re-request from itself spawning more.
    STALE_RESPONSE_AGE_S: 60,
    FOLLOWUP_DELAY_MS: 3000,

    requestCloudLockRefresh: function(_isFollowup) {
        var self = this;
        fetch('/api/vehicle/cloud-lock').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            if (!data || !data.success || !data.status) return;
            var s = data.status;

            // A full local vehicle reading is authoritative. The Atto's OTA
            // path exposes only the driver door, however, so a fresh full-car
            // cloud snapshot may replace that partial reading.
            var localIsAuthoritative =
                    (self.vehicleState.locked === true || self.vehicleState.locked === false)
                    && self.vehicleState.lockScope === 'vehicle';
            // Evaluate staleness BEFORE writing: an old snapshot must not replace a
            // fresh partial reading and get promoted to scope 'vehicle'.
            var isStale = s.lockState === 'unknown'
                    || s.lastMessageAge === -1
                    || (typeof s.lastMessageAge === 'number' && s.lastMessageAge > self.STALE_RESPONSE_AGE_S);
            var haveLocalReading =
                    self.vehicleState.locked === true || self.vehicleState.locked === false;
            if (!localIsAuthoritative && !(isStale && haveLocalReading)) {
                if (s.lockState === 'locked') {
                    self.vehicleState.locked = true;
                    self.vehicleState.lockScope = 'vehicle';
                    self.vehicleState.lockSource = 'cloud';
                    self.updateHUD();
                    self.updateDoorIndicators();
                    self.updateTabIndicators();
                } else if (s.lockState === 'unlocked') {
                    self.vehicleState.locked = false;
                    self.vehicleState.lockScope = 'vehicle';
                    self.vehicleState.lockSource = 'cloud';
                    self.updateHUD();
                    self.updateDoorIndicators();
                    self.updateTabIndicators();
                }
            }

            // If the response is stale and CAN didn't give us a value,
            // schedule one follow-up to pick up the result of the server's
            // background REST refresh. Skipped if this is itself a follow-up
            // call (avoids loops on persistently stale data).
            if (!_isFollowup && isStale && !localIsAuthoritative) {
                setTimeout(function() { self.requestCloudLockRefresh(true); }, self.FOLLOWUP_DELAY_MS);
            }
        }).catch(function(e) {
            console.warn('[VC] Cloud lock refresh error:', e);
        });
    },

    // Background poller for the cloud lock state. The cloud snapshot is the
    // authoritative source while the car is sleeping (CAN returns -1 in
    // that mode). 30s is plenty — MQTT pushes events the moment the car
    // moves, this is just a heartbeat for the cold-cache case.
    startCloudLockSync: function() {
        var self = this;
        if (this.cloudLockInterval) clearInterval(this.cloudLockInterval);
        this.cloudLockInterval = setInterval(function() {
            self.requestCloudLockRefresh();
        }, 30 * 1000);
    },

    // ==================== CLOUD MODAL ====================

    initCloudModal: function() {
        var self = this;
        var dismissBtn = document.getElementById('cloudModalDismiss');
        if (dismissBtn) {
            dismissBtn.addEventListener('click', function() { self.hideCloudModal(); });
        }
        var statusPill = document.getElementById('cloudStatus');
        if (statusPill) {
            statusPill.setAttribute('role', 'button');
            statusPill.setAttribute('tabindex', '0');
            var explainCloud = function() {
                if (!self.vehicleState.cloudConfigured) self.showCloudModal();
            };
            statusPill.addEventListener('click', explainCloud);
            statusPill.addEventListener('keydown', function(e) {
                if (e.key === 'Enter' || e.key === ' ') explainCloud();
            });
        }
        // Also dismiss on overlay click (outside the modal card)
        var overlay = document.getElementById('cloudModal');
        if (overlay) {
            overlay.addEventListener('click', function(e) {
                if (e.target === overlay) self.hideCloudModal();
            });
        }
    },

    /**
     * Guard for cloud-requiring actions.
     * Returns true if cloud is configured (action can proceed).
     * Returns false and shows modal if cloud is not configured.
     */
    requireCloud: function() {
        if (this.vehicleState.cloudConfigured) return true;
        this.showCloudModal();
        return false;
    },

    showCloudModal: function() {
        var overlay = document.getElementById('cloudModal');
        if (overlay) overlay.classList.add('visible');
    },

    hideCloudModal: function() {
        var overlay = document.getElementById('cloudModal');
        if (overlay) overlay.classList.remove('visible');
    },

    // ==================== UI UPDATES ====================

    translatedText: function(key, fallback) {
        var value = BYD.i18n && typeof BYD.i18n.t === 'function'
            ? BYD.i18n.t(key)
            : null;
        return value && value !== key ? value : fallback;
    },

    // SOC / range are owned by the app-shell sidebar card (core.js polls /status
    // and writes evPercentValue / evBatteryFill / evRange). The in-page HUD that
    // #socValue / #socFill / #rangeValue targeted never existed on this page, so
    // those writes were dead; only the lock UI is ours.
    updateHUD: function() {
        this.updateLockUI(this.vehicleState.locked, this.vehicleState.lockScope);
    },

    updateLockUI: function(locked, scope) {
        var lockBtn = document.getElementById('btnLock');
        var unlockBtn = document.getElementById('btnUnlock');
        var lockStatus = document.getElementById('lockStatus');
        var wholeVehicleKnown = scope === 'vehicle';

        // Do not present a driver-door-only reading as whole-car state.
        if (lockBtn) {
            if (locked === true && wholeVehicleKnown) lockBtn.classList.add('on');
            else lockBtn.classList.remove('on');
        }
        if (unlockBtn) {
            if (locked === false && wholeVehicleKnown) unlockBtn.classList.add('on');
            else unlockBtn.classList.remove('on');
        }
        if (lockStatus) {
            var label;
            if (locked !== true && locked !== false) {
                label = this.translatedText('common.unknown', 'Unknown');
            } else if (scope === 'driver_door') {
                label = locked
                    ? this.translatedText('vehicle.driver_door_locked', 'Driver door locked')
                    : this.translatedText('vehicle.driver_door_unlocked', 'Driver door unlocked');
            } else {
                label = locked
                    ? this.translatedText('vehicle.locked', 'Locked')
                    : this.translatedText('vehicle.unlocked', 'Unlocked');
            }
            lockStatus.textContent = label;
            var dot = lockStatus.previousElementSibling;
            if (dot) {
                // Colour tracks the lock state; the label carries the scope. Folding
                // partial scope onto amber made "Driver door locked" show the
                // unlocked colour and made the two driver-door states identical.
                var known = (locked === true || locked === false);
                var tone = !known ? 'grey' : (locked ? 'green' : 'amber');
                // 'partial' only decorates a KNOWN state — on grey it would just
                // inherit the pill's text colour and read as a fourth state.
                dot.className = 'dot compact-status-pill__dot ' + tone +
                    (known && !wholeVehicleKnown ? ' partial' : '');
            }
        }
    },

    updateWindowBars: function() {
        var areas = ['lf', 'rf', 'lr', 'rr', 'sunroof', 'sunshade'];
        for (var i = 0; i < areas.length; i++) {
            var area = areas[i];
            var fill = document.getElementById('winFill_' + area);
            var pct = document.getElementById('winPct_' + area);
            var label = document.getElementById('winLabel_' + area);
            var val = this.vehicleState.windows[area];
            var hasReading = (typeof val === 'number' && val >= 0);
            var display = hasReading ? val : 0;
            if (fill) fill.style.width = display + '%';
            if (pct) pct.textContent = display + '%';
            if (label) label.textContent = hasReading ? (val + '%') : '--%';
            // Reconcile the highlighted preset with the live position. Pick
            // the closest preset within the same ±5% tolerance the backend
            // uses to stop.
            if (hasReading) this.markWindowPresetFromActual(area, val);
        }
    },

    /** Visually mark one preset as the active target for a window. */
    markWindowPreset: function(area, target) {
        var row = document.querySelector('#panelWindows .vc-window-row[data-area="' + area + '"]');
        if (!row) return;
        var presets = row.querySelectorAll('.vc-preset');
        for (var i = 0; i < presets.length; i++) {
            var v = parseInt(presets[i].getAttribute('data-preset'), 10);
            if (v === target) presets[i].classList.add('active');
            else presets[i].classList.remove('active');
        }
    },

    /** Pick the closest preset to the live percentage and mark it active. */
    markWindowPresetFromActual: function(area, actual) {
        var presets = [0, 25, 50, 75, 100];
        var closest = presets[0];
        var bestDelta = Math.abs(actual - presets[0]);
        for (var i = 1; i < presets.length; i++) {
            var d = Math.abs(actual - presets[i]);
            if (d < bestDelta) { bestDelta = d; closest = presets[i]; }
        }
        // Only highlight if we're meaningfully near a preset (±10% of it)
        // — avoids confusingly lighting up "50" when window is at 35%.
        if (bestDelta <= 10) this.markWindowPreset(area, closest);
        else this.markWindowPreset(area, -1);
    },

    updateDoorIndicators: function() {
        var areas = ['lf', 'rf', 'lr', 'rr', 'sunroof', 'sunshade'];
        for (var i = 0; i < areas.length; i++) {
            var area = areas[i];
            var el = document.getElementById('doorState_' + area);
            if (!el) continue;
            var val = this.vehicleState.doors[area];
            if (val === 1) {
                el.textContent = '\uD83D\uDD12'; // locked
                el.title = BYD.i18n.t('vehicle.locked');
                this.removeStateGlow('door_' + area);
            } else if (val === 2) {
                el.textContent = '\uD83D\uDD13'; // unlocked
                el.title = BYD.i18n.t('vehicle.unlocked');
                this.setStateGlow('door_' + area, this.getDoorPosition(area), 0xF59E0B); // amber
            } else {
                el.textContent = '\u2014';
                el.title = BYD.i18n.t('common.unknown');
                this.removeStateGlow('door_' + area);
            }
        }
    },

    /** Clear the trunk glow until a verified tailgate-position source exists. */
    updateTrunkIndicator: function() {
        this.removeStateGlow('trunk');
    },

    /** Update persistent glow for open windows */
    updateWindowGlows: function() {
        var areas = ['lf', 'rf', 'lr', 'rr', 'sunroof', 'sunshade'];
        for (var i = 0; i < areas.length; i++) {
            var area = areas[i];
            var pct = this.vehicleState.windows[area] || 0;
            if (pct > 10) {
                this.setStateGlow('win_' + area, this.getWindowPosition(area), 0x38BDF8); // blue
            } else {
                this.removeStateGlow('win_' + area);
            }
        }
    },

    getDoorPosition: function(area) {
        var positions = {
            lf: { x: 1.0, y: 0.6, z: 0.5 },
            rf: { x: -1.0, y: 0.6, z: 0.5 },
            lr: { x: 1.0, y: 0.6, z: -0.5 },
            rr: { x: -1.0, y: 0.6, z: -0.5 }
        };
        return positions[area] || { x: 0, y: 0.5, z: 0 };
    },

    getWindowPosition: function(area) {
        var positions = {
            lf: { x: 1.0, y: 0.9, z: 0.5 },
            rf: { x: -1.0, y: 0.9, z: 0.5 },
            lr: { x: 1.0, y: 0.9, z: -0.5 },
            rr: { x: -1.0, y: 0.9, z: -0.5 }
        };
        return positions[area] || { x: 0, y: 0.9, z: 0 };
    },

    /** Add or update a persistent glow indicator on the car */
    setStateGlow: function(key, pos, colorHex) {
        if (!this.carModel) return;
        this.removeStateGlow(key);

        // Glowing ring — much more visible than a point light
        var ringGeo = new THREE.RingGeometry(0.08, 0.14, 24);
        var ringMat = new THREE.MeshBasicMaterial({
            color: colorHex, side: THREE.DoubleSide,
            transparent: true, opacity: 0.85
        });
        var ring = new THREE.Mesh(ringGeo, ringMat);
        ring.position.set(pos.x, pos.y, pos.z);
        ring.rotation.x = Math.PI / 2;
        this.carModel.add(ring);

        // Point light for ambient glow on nearby surfaces
        var light = new THREE.PointLight(colorHex, 0.6, 2.5);
        light.position.set(pos.x, pos.y, pos.z);
        this.carModel.add(light);

        // Pulse animation on the ring
        gsap.to(ringMat, {
            opacity: 0.3, duration: 1,
            yoyo: true, repeat: -1, ease: 'sine.inOut'
        });

        this.stateGlows[key] = { ring: ring, light: light, geo: ringGeo, mat: ringMat };
    },

    /** Remove a persistent glow */
    removeStateGlow: function(key) {
        var glow = this.stateGlows[key];
        if (!glow) return;
        gsap.killTweensOf(glow.mat);
        if (this.carModel) {
            this.carModel.remove(glow.ring);
            this.carModel.remove(glow.light);
        }
        glow.geo.dispose();
        glow.mat.dispose();
        delete this.stateGlows[key];
    },

    updateCloudIndicator: function() {
        var textEl = document.getElementById('cloudStatusText');
        var pillEl = document.getElementById('cloudStatus');
        if (!pillEl) return;
        var dot = pillEl.querySelector('.dot');
        var state = this.vehicleState.cloudState || 'checking';
        pillEl.setAttribute('data-cloud-state', state);
        if (state === 'connected') {
            if (dot) dot.className = 'dot compact-status-pill__dot green';
            if (textEl) {
                textEl.textContent = this.translatedText(
                    'vehicle.cloud_connected', 'BYD account connected');
            }
        } else if (state === 'not_configured') {
            if (dot) dot.className = 'dot compact-status-pill__dot grey';
            if (textEl) {
                textEl.textContent = this.translatedText(
                    'vehicle.cloud_not_configured', 'BYD account not connected');
            }
        } else if (state === 'unavailable') {
            if (dot) dot.className = 'dot compact-status-pill__dot red';
            if (textEl) {
                textEl.textContent = this.translatedText(
                    'vehicle.cloud_unavailable', 'Cloud status unavailable');
            }
        } else {
            if (dot) dot.className = 'dot compact-status-pill__dot amber';
            if (textEl) {
                textEl.textContent = this.translatedText(
                    'vehicle.checking', 'Checking...');
            }
        }
    },

    updateCloudControlAvailability: function() {
        var state = this.vehicleState.cloudState || 'checking';
        var controls = document.querySelectorAll('[data-requires-cloud="true"]');
        for (var i = 0; i < controls.length; i++) {
            controls[i].setAttribute('data-cloud-state', state);
        }
    },

    updateClimateUI: function() {
        var tempEl = document.getElementById('acTemp');
        if (tempEl) tempEl.textContent = this.vehicleState.acTemp + '\u00B0';

        var fanEl = document.getElementById('acFan');
        if (fanEl) fanEl.textContent = this.vehicleState.acFan;

        // AC On button highlights when AC is on; AC Off button highlights when AC
        // is off. Both stay visible \u2014 neither hides \u2014 so the user can always tap
        // the opposite state regardless of where the live state currently is.
        var btnOn = document.getElementById('btnAcOn');
        var btnOff = document.getElementById('btnAcOff');
        if (btnOn) { if (this.vehicleState.acOn) btnOn.classList.add('on'); else btnOn.classList.remove('on'); }
        if (btnOff) { if (!this.vehicleState.acOn) btnOff.classList.add('on'); else btnOff.classList.remove('on'); }

        if (this.vehicleState.acOn) {
            this.setStateGlow('ac', { x: 0, y: 0.5, z: 0.3 }, 0x38BDF8);
            this.startAcSonar();
        } else {
            this.removeStateGlow('ac');
            this.stopAcSonar();
        }
    },

    updateSeatUI: function() {
        for (var i = 0; i < 2; i++) {
            var heatBtn = document.getElementById('btnSeatHeat' + (i + 1));
            var coolBtn = document.getElementById('btnSeatCool' + (i + 1));
            var heatLvl = this.vehicleState.seatHeat[i] || 0;
            var coolLvl = this.vehicleState.seatCool[i] || 0;

            if (heatBtn) {
                heatBtn.classList.remove('on1', 'on2');
                if (heatLvl > 0) heatBtn.classList.add('on' + heatLvl);
            }
            if (coolBtn) {
                coolBtn.classList.remove('on1', 'on2');
                if (coolLvl > 0) coolBtn.classList.add('on' + coolLvl);
            }
        }
    },

    /**
     * Steering-wheel heater tile. Binary, so it reuses the heat tile's `on2`
     * highlight rather than inventing a level. A null state (nothing read yet)
     * renders unlit — the same "unknown" presentation as an off wheel, but the
     * click handler still targets ON, so the first tap is never a no-op.
     */
    updateSteeringHeatUI: function() {
        var btn = document.getElementById('btnSteeringHeat');
        if (!btn) return;
        btn.classList.remove('on1', 'on2');
        if (this.vehicleState.steeringHeat === true) btn.classList.add('on2');
    },

    /** Battery preconditioning tile. Unlit for both "off" and "not yet read". */
    updateBatteryHeatUI: function() {
        var btn = document.getElementById('btnBatteryHeat');
        if (!btn) return;
        if (this.vehicleState.batteryHeat === true) btn.classList.add('on');
        else btn.classList.remove('on');
    },

    updateLightsUI: function() {
        if (!this.vehicleState.lights) return;
        var btnDRL = document.getElementById('btnDRL');
        var on = !!(this.vehicleState.lights.dayTimeLight);
        if (btnDRL) { if (on) btnDRL.classList.add('on'); else btnDRL.classList.remove('on'); }

        var slider = document.getElementById('ambientColourSlider');
        if (slider) {
            var colour = this.vehicleState.lights.ambientColour;
            if (typeof colour === 'number') {
                slider.value = colour;
                var options = this.vehicleState.lights.ambientOptions;
                if (options && options.length) {
                    slider.disabled = false;
                    slider.style.background = 'linear-gradient(to right, ' + options.join(',') + ')';
                    if (colour >= 1 && colour <= options.length) {
                        slider.style.setProperty('--color', options[colour - 1]);
                    }
                } else {
                    slider.disabled = true;
                }
            }
        }
    },

    updateAdasUI: function() {
        var btnSLW = document.getElementById('btnSLW');
        var on = !!(this.vehicleState.adas && this.vehicleState.adas.speedLimitWarning);
        if (btnSLW) { if (on) btnSLW.classList.add('on'); else btnSLW.classList.remove('on'); }
        var btnCPD = document.getElementById('btnCPD');
        var cpdOn = !!(this.vehicleState.setting && this.vehicleState.setting.childPresenceDetection);
        if (btnCPD) { if (cpdOn) btnCPD.classList.add('on'); else btnCPD.classList.remove('on'); }
    },

    /** Custom-mode chargeWay: always emit CSV so server doesn't fall back to "e". */
    _computeCustomChargeWay: function(days) {
        if (!days || days.length === 0) return '';
        return days.slice().sort(function(a, b) { return a - b; }).join(',');
    },

    /** Parse wire chargeWay back into local day-array. "e" → all 7; "s" → []; CSV → ints. */
    _parseChargeWay: function(way) {
        if (!way || way === 'e') return [0, 1, 2, 3, 4, 5, 6];
        if (way === 's') return [];
        var parts = String(way).split(',');
        var out = [];
        for (var i = 0; i < parts.length; i++) {
            var n = parseInt(parts[i], 10);
            if (!isNaN(n) && n >= 0 && n <= 6) out.push(n);
        }
        return out;
    },

    /** Derive segmented mode from current chargeWay. */
    _scheduleMode: function(chargeWay) {
        if (chargeWay === 's') return 'once';
        if (!chargeWay || chargeWay === 'e') return 'daily';
        return 'custom';
    },

    updateChargingUI: function() {
        var s = this.vehicleState.chargingSchedule || {};
        var unsupported = s.supported === false;
        var pending = !!this._smartChargePending;
        var btn = document.getElementById('btnSmartChargeToggle');
        if (btn) {
            if (s.enabled === true) btn.classList.add('on'); else btn.classList.remove('on');
            btn.disabled = unsupported || pending;
        }
        var startNowBtn = document.getElementById('btnStartCharging');
        if (startNowBtn) startNowBtn.disabled = unsupported || pending;

        var startInput = document.getElementById('chargeStartTime');
        if (startInput) {
            startInput.value = s.startChargeTime || '';
            startInput.disabled = unsupported || pending;
        }

        var endInput = document.getElementById('chargeEndTime');
        if (endInput) {
            endInput.value = (s.endChargeTime && s.endChargeTime !== 'full')
                ? s.endChargeTime : '';
            endInput.disabled = unsupported || pending || !!s.untilFull;
        }

        var fullBtn = document.getElementById('btnChargeUntilFull');
        if (fullBtn) {
            if (s.untilFull) fullBtn.classList.add('on'); else fullBtn.classList.remove('on');
            fullBtn.disabled = unsupported || pending;
        }

        var mode = this._scheduleMode(s.chargeWay);
        var segs = document.querySelectorAll('#repeatSegmented .vc-seg');
        for (var k = 0; k < segs.length; k++) {
            if (segs[k].getAttribute('data-mode') === mode) segs[k].classList.add('on');
            else segs[k].classList.remove('on');
            segs[k].disabled = unsupported || pending;
        }

        var dayChips = document.getElementById('chargeDayChips');
        if (dayChips) {
            dayChips.style.display = (mode === 'custom') ? '' : 'none';
            var chips = dayChips.querySelectorAll('.vc-day');
            var selected;
            if (mode === 'custom') {
                selected = s.days && s.days.length > 0 ? s.days : this._parseChargeWay(s.chargeWay);
            } else if (mode === 'daily') {
                selected = [0, 1, 2, 3, 4, 5, 6];
            } else {
                selected = [];
            }
            for (var i = 0; i < chips.length; i++) {
                var d = parseInt(chips[i].getAttribute('data-day'), 10);
                if (selected.indexOf(d) >= 0) chips[i].classList.add('on');
                else chips[i].classList.remove('on');
                chips[i].disabled = unsupported || pending;
            }
        }
        var saveBtn = document.getElementById('btnChargeScheduleSave');
        if (saveBtn) saveBtn.disabled = unsupported || pending;
    },

    updateChargeCapUI: function() {
        var s = this.vehicleState.chargeCap || {};
        var section = document.getElementById('chargeCapSection');
        // Hide on trims where the probe confirmed no-op. null = not yet
        // probed → show optimistically so the user can trigger the probe.
        if (section) section.style.display = (s.supported === false) ? 'none' : '';

        var btn = document.getElementById('btnChargeCapToggle');
        if (btn) {
            if (s.enabled === true) btn.classList.add('on'); else btn.classList.remove('on');
            // The master switch must never operate before a capacity write has
            // verified that this trim honors the charge-stop backend.
            btn.disabled = s.supported !== true;
        }

        var slider = document.getElementById('chargeCapSlider');
        var readout = document.getElementById('chargeCapReadout');
        if (slider) {
            slider.min = (typeof s.minimumPercent === 'number') ? s.minimumPercent : 50;
            slider.max = (typeof s.maximumPercent === 'number') ? s.maximumPercent : 100;
            slider.disabled = s.supported === false;
            if (typeof s.percent === 'number') slider.value = s.percent;
            else slider.value = slider.min;
        }
        if (readout) {
            readout.textContent = (typeof s.percent === 'number') ? (s.percent + '%') : '--';
        }
    },

    fetchChargeCap: function() {
        var self = this;
        var fetchRevision = ++this._chargeCapFetchRevision;
        var stateRevision = this._chargeCapRevision;
        fetch('/api/vehicle/charge-cap').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            if (fetchRevision !== self._chargeCapFetchRevision
                    || stateRevision !== self._chargeCapRevision
                    || self._chargeCapPendingRevision) return;
            if (!data || !data.success) {
                console.debug('[VC] charge-cap GET failed', data);
                return;
            }
            if (!self.vehicleState.chargeCap) self.vehicleState.chargeCap = {};
            var s = self.vehicleState.chargeCap;
            // Only accept a verified charge-stop limit (50..100). A HAL sentinel that
            // slipped past the server (e.g. 65535) is ignored so the readout
            // shows '--' rather than "65535%".
            s.percent = (typeof data.percent === 'number'
                    && data.percent >= 50 && data.percent <= 100) ? data.percent : null;
            s.enabled = typeof data.enabled === 'boolean' ? data.enabled : null;
            if (typeof data.supported === 'boolean') s.supported = data.supported;
            else s.supported = null;
            s.minimumPercent = typeof data.minimumPercent === 'number' ? data.minimumPercent : null;
            s.maximumPercent = typeof data.maximumPercent === 'number' ? data.maximumPercent : null;
            s.controlKind = typeof data.controlKind === 'string' ? data.controlKind : null;
            self.updateChargeCapUI();
        }).catch(function(e) { console.debug('[VC] charge-cap GET threw', e); });
    },

    updateAcChargeCurrentUI: function() {
        var state = this.vehicleState.acChargeCurrentLimit || {};
        var section = document.getElementById('acChargeCurrentSection');
        if (section) section.setAttribute(
            'aria-disabled',
            state.supported === true && state.available === true ? 'false' : 'true');
        var pending = !!this._acChargeCurrentPendingRevision;
        var segments = document.querySelectorAll('#acChargeCurrentSegmented .vc-seg');
        for (var i = 0; i < segments.length; i++) {
            var value = parseInt(segments[i].getAttribute('data-state'), 10);
            if (value === state.state) segments[i].classList.add('on');
            else segments[i].classList.remove('on');
            segments[i].disabled = pending
                || state.supported !== true
                || state.available !== true;
        }
    },

    startAcChargeCurrentSync: function() {
        var self = this;
        if (this.acChargeCurrentInterval) {
            clearInterval(this.acChargeCurrentInterval);
        }
        this.fetchAcChargeCurrentLimit();
        this.acChargeCurrentInterval = setInterval(function() {
            self.fetchAcChargeCurrentLimit();
        }, 15 * 1000);
    },

    fetchAcChargeCurrentLimit: function() {
        var self = this;
        var fetchRevision = ++this._acChargeCurrentFetchRevision;
        var stateRevision = this._acChargeCurrentRevision;
        fetch('/api/vehicle/ac-charge-current-limit').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            if (fetchRevision !== self._acChargeCurrentFetchRevision
                    || stateRevision !== self._acChargeCurrentRevision
                    || self._acChargeCurrentPendingRevision) return;
            if (!data || !data.success) {
                console.debug('[VC] AC charge current GET failed', data);
                self.vehicleState.acChargeCurrentLimit.available = false;
                self.updateAcChargeCurrentUI();
                return;
            }
            var state = self.vehicleState.acChargeCurrentLimit;
            state.checked = true;
            if (typeof data.supported === 'boolean') {
                state.supported = data.supported;
            }
            state.available = data.available === true;
            state.state = typeof data.state === 'number'
                    && data.state >= 1 && data.state <= 5 ? data.state : null;
            self.updateAcChargeCurrentUI();
        }).catch(function(e) {
            console.debug('[VC] AC charge current GET threw', e);
            self.vehicleState.acChargeCurrentLimit.available = false;
            self.updateAcChargeCurrentUI();
        });
    },

    fetchChargingSchedule: function() {
        var self = this;
        var fetchRevision = ++this._chargingScheduleFetchRevision;
        var stateRevision = this._chargingScheduleRevision;
        fetch('/api/vehicle/charging-schedule').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            // A user edit or confirmed action that happened after this request
            // started owns the UI. Do not let this older cloud snapshot erase it.
            if (fetchRevision !== self._chargingScheduleFetchRevision
                    || stateRevision !== self._chargingScheduleRevision
                    || self._scheduleDirty
                    || self._smartChargePending) return;
            if (!data || !data.success) {
                console.debug('[VC] charging-schedule GET failed', data);
                return;
            }
            if (!self.vehicleState.chargingSchedule) self.vehicleState.chargingSchedule = {};
            var s = self.vehicleState.chargingSchedule;
            // Reset first: an authoritative cloud response with omitted DTOs
            // means no schedule, never "keep showing the old one".
            s.enabled = null;
            s.startChargeTime = null;
            s.endChargeTime = null;
            s.untilFull = false;
            s.chargeWay = null;
            s.days = [];
            s.smartJourneyDto = null;
            s.supported = data.supported !== false;
            if (data.supported === false) {
                console.debug('[VC] charging-schedule cloud not ready — reason:', data.reason || 'unsupported');
                self.updateChargingUI();
                return;
            }
            if (data.enabled === true || data.enabled === false) s.enabled = data.enabled;
            if (typeof data.startChargeTime === 'string' && data.startChargeTime) {
                s.startChargeTime = data.startChargeTime;
            }
            if (typeof data.endChargeTime === 'string' && data.endChargeTime) {
                if (data.endChargeTime === 'full') {
                    s.untilFull = true;
                } else {
                    s.untilFull = false;
                    s.endChargeTime = data.endChargeTime;
                }
            }
            if (typeof data.chargeWay === 'string' && data.chargeWay) {
                s.chargeWay = data.chargeWay;
                s.days = self._parseChargeWay(data.chargeWay);
            }
            if (data.smartJourneyDto && typeof data.smartJourneyDto === 'object') {
                s.smartJourneyDto = data.smartJourneyDto;
            }
            self.updateChargingUI();
        }).catch(function(e) { console.debug('[VC] charging-schedule GET threw', e); });
    },

    /**
     * Resolve an "HH:MM" wall-clock time to the next epoch-second occurrence.
     * BOOKINGAIR takes an absolute instant, and the server refuses anything less
     * than 30 s ahead, so a time that already passed today rolls to tomorrow.
     * Returns null for unparseable input.
     */
    _nextOccurrenceEpochSeconds: function(hhmm) {
        if (typeof hhmm !== 'string') return null;
        var parts = hhmm.split(':');
        if (parts.length < 2) return null;
        var hour = parseInt(parts[0], 10);
        var minute = parseInt(parts[1], 10);
        if (isNaN(hour) || isNaN(minute) || hour < 0 || hour > 23
                || minute < 0 || minute > 59) {
            return null;
        }
        var now = new Date();
        var target = new Date(now.getFullYear(), now.getMonth(), now.getDate(),
            hour, minute, 0, 0);
        // 60 s of headroom over the server's 30 s floor, so a booking saved for
        // "one minute from now" isn't rejected by the time the request lands.
        if (target.getTime() - now.getTime() < 60 * 1000) {
            // Roll over by incrementing the DATE, not by adding 86400000 ms: a fixed
            // day of milliseconds does not re-normalize the wall clock across a
            // DST transition, which would book an hour early or late.
            target = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1,
                hour, minute, 0, 0);
        }
        return Math.floor(target.getTime() / 1000);
    },

    /** Render an epoch-second booking instant as local "HH:MM". */
    _epochSecondsToLocalTime: function(epochSeconds) {
        var d = new Date(epochSeconds * 1000);
        var h = d.getHours();
        var m = d.getMinutes();
        return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m;
    },

    updateClimateScheduleUI: function() {
        var s = this.vehicleState.climateSchedule || {};
        var section = document.getElementById('climateScheduleSection');
        // Shown whenever the feature could work: an explicit supported=false from the
        // server hides it, and so does having no cloud account at all. A not-yet-probed
        // state (null) with cloud linked still shows — gating on a completed probe meant
        // the row never appeared when the booking-list read couldn't reach the car.
        if (section) {
            var canSchedule = s.supported !== false
                    && (s.supported === true || this.vehicleState.cloudConfigured);
            section.style.display = canSchedule ? '' : 'none';
        }
        var pending = !!this._climateSchedulePending;

        var timeInput = document.getElementById('climateBookingTime');
        if (timeInput) {
            timeInput.value = s.time || '07:30';
            timeInput.disabled = pending;
        }

        var tempReadout = document.getElementById('climateBookingTemp');
        if (tempReadout) {
            tempReadout.textContent = (typeof s.temp === 'number' ? s.temp : 22) + '°';
        }
        var tempUp = document.getElementById('btnBookingTempUp');
        var tempDown = document.getElementById('btnBookingTempDown');
        if (tempUp) tempUp.disabled = pending;
        if (tempDown) tempDown.disabled = pending;

        var minutes = s.durationMinutes || 20;
        var segs = document.querySelectorAll('#climateDurationSegmented .vc-seg');
        for (var i = 0; i < segs.length; i++) {
            var v = parseInt(segs[i].getAttribute('data-duration'), 10);
            if (v === minutes) segs[i].classList.add('on');
            else segs[i].classList.remove('on');
            segs[i].disabled = pending;
        }

        var saveBtn = document.getElementById('btnClimateScheduleSave');
        if (saveBtn) saveBtn.disabled = pending;
        // Clear only exists against a known booking id.
        var clearBtn = document.getElementById('btnClimateScheduleClear');
        if (clearBtn) {
            clearBtn.style.display = s.bookingId ? '' : 'none';
            clearBtn.disabled = pending;
        }

        var note = document.getElementById('climateScheduleNote');
        if (note) {
            if (s.bookingId && s.reportedTime) {
                note.style.display = '';
                note.textContent = BYD.i18n.t('vehicle.precondition_booked', {
                    time: s.reportedTime,
                    temp: (typeof s.reportedTemp === 'number' ? s.reportedTemp : s.temp),
                    minutes: s.reportedDuration || minutes
                });
            } else if (s.bookingId) {
                // A booking exists but the cloud didn't report its details in a
                // shape we recognise. Say only what is known.
                note.style.display = '';
                note.textContent = BYD.i18n.t('vehicle.precondition_booked_unknown');
            } else if (s.savedUnconfirmed) {
                // The cloud accepted a booking but its list read came back empty —
                // documented behaviour, not proof the booking is gone. Saying "no
                // schedule" here would be a lie, and Clear can't be offered without
                // an id, so warn that a repeat Save adds a second booking.
                note.style.display = '';
                note.textContent = BYD.i18n.t('vehicle.precondition_saved_unconfirmed');
            } else {
                note.style.display = 'none';
                note.textContent = '';
            }
        }
    },

    /**
     * Read the cloud's BOOKINGAIR list. An empty list is NOT proof of deletion —
     * the endpoint can omit a live booking — so an empty response never clears a
     * locally known booking id, it only leaves the editor as-is.
     */
    fetchClimateSchedule: function() {
        var self = this;
        var fetchRevision = ++this._climateScheduleFetchRevision;
        var stateRevision = this._climateScheduleRevision;
        fetch('/api/vehicle/climate-schedule').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            // A user edit or confirmed write that started after this request owns
            // the UI; an older cloud snapshot must not overwrite it.
            if (fetchRevision !== self._climateScheduleFetchRevision
                    || stateRevision !== self._climateScheduleRevision
                    || self._climateScheduleDirty
                    || self._climateSchedulePending) return;
            if (!data || !data.success) {
                console.debug('[VC] climate-schedule GET failed', data);
                return;
            }
            if (!self.vehicleState.climateSchedule) self.vehicleState.climateSchedule = {};
            var s = self.vehicleState.climateSchedule;
            s.supported = data.supported !== false;
            if (data.supported === false) {
                console.debug('[VC] climate-schedule cloud not ready — reason:',
                    data.reason || 'unsupported');
                self.updateClimateScheduleUI();
                return;
            }
            // The car was unreachable, so there is no list to reconcile against. Leave the
            // last known booking and its details exactly as they were — treating this as an
            // empty list would drop a live booking's ID.
            if (data.bookingsUnavailable === true) {
                console.debug('[VC] climate-schedule bookings unavailable —',
                    data.reason || 'unknown');
                self.updateClimateScheduleUI();
                return;
            }
            var entries = (data.bookings && data.bookings.listInfo) || null;
            var first = (entries && entries.length > 0) ? entries[0] : null;
            // The REPORTED details are cleared before re-reading: they describe what the
            // cloud last said, so a response that omits them must not leave the previous
            // values on screen asserting a booking time the cloud no longer reports. The
            // editor's own time/temp/duration are the user's working values, not cloud
            // claims, so those are left alone.
            s.reportedTime = null;
            s.reportedTemp = null;
            s.reportedDuration = null;
            // An empty list clears a known booking ID only when the server does NOT flag
            // the response as possibly-stale. BYD documents returning an empty object for
            // a live booking, so while that flag is set an empty list proves nothing and
            // the ID is kept.
            if (!first && s.bookingId && data.emptyBookingsMayBeStale !== true) {
                s.bookingId = null;
                s.savedUnconfirmed = false;
            }
            if (first) {
                // Only `listInfo[].bookingId` is a verified key on this response (the
                // server normalizes it to decimal text because 64-bit ids exceed JS's
                // exact-integer range). The remaining fields are read through a small
                // set of candidate spellings and simply stay unset when absent, so an
                // unexpected shape degrades to "booked, details not reported" instead
                // of showing an invented time or temperature.
                if (typeof first.bookingId === 'string' && first.bookingId) {
                    s.bookingId = first.bookingId;
                    // A real id supersedes the "accepted but unreported" state.
                    s.savedUnconfirmed = false;
                }
                // Epoch SECONDS only. BYD's own envelopes carry millisecond stamps, and
                // a ms value would decode to a plausible-looking wrong time and then
                // overwrite the user's pick — so bound it to a sane seconds window.
                var when = self._positiveInteger(
                    self._firstPresent(first, ['bookingTime', 'bookTime', 'appointmentTime']));
                if (when !== null && when > 1000000000 && when < 4000000000) {
                    s.reportedTime = self._epochSecondsToLocalTime(when);
                    s.time = s.reportedTime;
                }
                // Wire temperature is the raw HVAC scale (15C = 1), same as OPENAIR.
                // Only the primary spelling is verified to be raw; the alternates could
                // be plain Celsius, and the two domains overlap at 15/16/17. Accept the
                // raw decode only from the verified key so an alternate spelling can
                // never turn a 17C booking into 31C.
                var rawTemp = self._positiveInteger(first.mainSettingTemp);
                if (rawTemp !== null && rawTemp >= 1 && rawTemp <= 17) {
                    s.reportedTemp = rawTemp + 14;
                    s.temp = s.reportedTemp;
                }
                var span = self._positiveInteger(
                    self._firstPresent(first, ['timeSpan', 'timespan']));
                if (span !== null && span >= 1 && span <= 5) {
                    s.reportedDuration = span * 5 + 5;   // timeSpan 1..5 → 10..30 min
                    s.durationMinutes = s.reportedDuration;
                }
            }
            self.updateClimateScheduleUI();
        }).catch(function(e) { console.debug('[VC] climate-schedule GET threw', e); });
    },

    /**
     * Session length for a REMOTE AC start, in minutes. Shares the duration the
     * preconditioning row shows, since BYD carries both on the same timeSpan
     * field. Falls back to the OEM default when the value isn't one of the five
     * accepted lengths — the server refuses anything else outright.
     */
    _remoteSessionMinutes: function() {
        var s = this.vehicleState.climateSchedule;
        var m = s ? s.durationMinutes : null;
        if (m === 10 || m === 15 || m === 20 || m === 25 || m === 30) return m;
        return 20;
    },

    /** First key present on `obj` with a non-null value, else null. */
    _firstPresent: function(obj, keys) {
        for (var i = 0; i < keys.length; i++) {
            var v = obj[keys[i]];
            if (v !== undefined && v !== null && v !== '') return v;
        }
        return null;
    },

    /**
     * Accept a positive WHOLE number from a JSON number or its decimal text.
     * Integrality is required, not cosmetic: the server refuses a fractional temp
     * or a non-OEM duration outright, and the +/- steppers move by 1, so a
     * fractional seed would leave the editor permanently unable to save.
     */
    _positiveInteger: function(value) {
        var n;
        if (typeof value === 'number') {
            n = value;
        } else if (typeof value === 'string' && /^\d+$/.test(value)) {
            // Digits only — parseInt would truncate "8.5" to 8 and report a
            // temperature the cloud never sent.
            n = parseInt(value, 10);
        } else {
            return null;
        }
        if (isNaN(n) || n <= 0 || n !== Math.floor(n)) return null;
        return n;
    },

    updateSeatGlows: function() {
        var self = this;
        if (!this._seatSonarIntervals) this._seatSonarIntervals = {};
        if (!this._seatSonarMeshes) this._seatSonarMeshes = {};

        // Seat positions on the 3D model (approximate interior positions)
        var seatPositions = {
            1: { x: 0.5, y: 0.4, z: 0.2 },   // driver
            2: { x: -0.5, y: 0.4, z: 0.2 }    // passenger
        };

        for (var pos = 1; pos <= 2; pos++) {
            var heatLvl = this.vehicleState.seatHeat[pos - 1] || 0;
            var coolLvl = this.vehicleState.seatCool[pos - 1] || 0;
            var key = 'seat_' + pos;

            if (heatLvl > 0 || coolLvl > 0) {
                // Determine color
                var colorHex;
                if (heatLvl > 0) {
                    colorHex = heatLvl === 2 ? 0xFF4500 : 0xFF8C00;
                } else {
                    colorHex = coolLvl === 2 ? 0x00BFFF : 0x87CEEB;
                }

                // If already running with same color, skip
                if (this._seatSonarIntervals[key] && this._seatSonarIntervals[key].color === colorHex) continue;

                // Clear existing interval for this seat if any
                this._stopSeatSonar(key);

                var sp = seatPositions[pos];
                (function(seatKey, seatPos, seatColor) {
                    if (!self._seatSonarMeshes[seatKey]) self._seatSonarMeshes[seatKey] = [];

                    function spawnSeatRing() {
                        if (!self.carModel) return;
                        var ringGeo = new THREE.RingGeometry(0.08, 0.12, 24);
                        var ringMat = new THREE.MeshBasicMaterial({
                            color: seatColor,
                            side: THREE.DoubleSide,
                            transparent: true,
                            opacity: 0.8
                        });
                        var ring = new THREE.Mesh(ringGeo, ringMat);
                        ring.position.set(seatPos.x, seatPos.y, seatPos.z);
                        ring.rotation.x = Math.PI / 2;
                        self.carModel.add(ring);
                        self._seatSonarMeshes[seatKey].push(ring);

                        // Expand from scale 1 to 4 and fade out over 1 second
                        gsap.to(ring.scale, {
                            x: 4, y: 4, z: 4,
                            duration: 1,
                            ease: 'power2.out'
                        });
                        gsap.to(ringMat, {
                            opacity: 0,
                            duration: 1,
                            ease: 'power2.out',
                            onComplete: function() {
                                if (self.carModel) self.carModel.remove(ring);
                                ringGeo.dispose();
                                ringMat.dispose();
                                var meshes = self._seatSonarMeshes[seatKey];
                                if (meshes) {
                                    var idx = meshes.indexOf(ring);
                                    if (idx !== -1) meshes.splice(idx, 1);
                                }
                            }
                        });
                    }

                    spawnSeatRing();
                    var intervalId = setInterval(function() {
                        spawnSeatRing();
                    }, 1500);

                    self._seatSonarIntervals[seatKey] = { id: intervalId, color: seatColor };
                })(key, sp, colorHex);
            } else {
                // Seat is off — stop sonar
                this._stopSeatSonar(key);
            }
        }
    },

    /** Stop sonar for a specific seat and clean up meshes */
    _stopSeatSonar: function(key) {
        if (this._seatSonarIntervals && this._seatSonarIntervals[key]) {
            clearInterval(this._seatSonarIntervals[key].id);
            delete this._seatSonarIntervals[key];
        }
        if (this._seatSonarMeshes && this._seatSonarMeshes[key]) {
            var meshes = this._seatSonarMeshes[key];
            for (var i = 0; i < meshes.length; i++) {
                var mesh = meshes[i];
                gsap.killTweensOf(mesh.scale);
                gsap.killTweensOf(mesh.material);
                if (this.carModel) this.carModel.remove(mesh);
                mesh.geometry.dispose();
                mesh.material.dispose();
            }
            this._seatSonarMeshes[key] = [];
        }
        // Also remove the static glow
        this.removeStateGlow(key);
    },

    // ==================== 3D SURROUND VIEW ====================

    init3dButton: function() {
        var self = this;
        // Hide button if running inside app WebView (AndroidBridge is injected by WebViewFragment)
        if (window.AndroidBridge) {
            var btn = document.getElementById('btn3dView');
            if (btn) btn.style.display = 'none';
            return;
        }
        this.bindBtn('btn3dView', function() {
            if (self._3dViewActive) {
                self.stop3dView();
            } else {
                self.start3dView();
            }
        });
    },

    start3dView: function() {
        var self = this;
        this._3dViewActive = true;
        this._3dDecoderMode = null;  // 'webcodecs' or 'jmuxer'
        this._3dStreamConnected = false;
        var btn = document.getElementById('btn3dView');
        if (btn) btn.classList.add('on');
        // Hide tyre callouts in 3D surround mode — the leader-line projection
        // doesn't make sense once the bowl is the dominant shape on screen.
        var vp = document.querySelector('.vc-viewport');
        if (vp) vp.setAttribute('data-3d-on', 'true');

        // Timeout: if no stream data arrives within 8 seconds, show error and stop
        this._3dTimeout = setTimeout(function() {
            if (self._3dViewActive && !self._3dStreamConnected) {
                self.toast(BYD.i18n.t('vehicle.no_camera_stream'), 'error');
                self.stop3dView();
            }
        }, 8000);

        // Set stream to mosaic view mode (0) and high quality before connecting
        // This ensures we get the full 4-camera mosaic, same as the live view page
        Promise.all([
            fetch('/api/stream/view/0'),
            fetch('/api/stream/quality/HIGH', { method: 'POST' })
        ]).then(function() {
            self._start3dStream();
        }).catch(function() {
            // Even if quality/view set fails, try to connect anyway
            self._start3dStream();
        });
    },

    _start3dStream: function() {
        var self = this;

        try {
            // Use SotaPlayer (WebCodecs) — same decoder as the live view page
            var hasSotaPlayer = (typeof SotaPlayer !== 'undefined') && SotaPlayer.isSupported();

            if (hasSotaPlayer) {
                // SotaPlayer path — renders to canvas, use CanvasTexture for Three.js
                this._3dDecoderMode = 'webcodecs';
                this._3dCanvas = document.createElement('canvas');
                this._3dCanvas.width = 1280;
                this._3dCanvas.height = 960;
                this._3dCanvas.style.display = 'none';
                document.body.appendChild(this._3dCanvas);

                var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                var wsUrl = protocol + '//' + window.location.host + '/ws';
                // Append JWT as ?token= so tunnels work (cookies stripped by
                // SameSite; browser WS API can't set Authorization header).
                if (typeof BYDAuth !== 'undefined') {
                    var wsToken = BYDAuth.getToken();
                    if (wsToken) wsUrl += '?token=' + encodeURIComponent(wsToken);
                }

                this._sotaPlayer = new SotaPlayer(this._3dCanvas, wsUrl);
                this._sotaPlayer.onConnected = function() {
                    console.log('[VC] 3D WebCodecs stream connected');
                    self._3dStreamConnected = true;
                    if (self._3dTimeout) { clearTimeout(self._3dTimeout); self._3dTimeout = null; }
                    self.toast(BYD.i18n.t('vehicle.stream_3d_connected'), 'success');
                };
                this._sotaPlayer.onFrame = function() {
                    // Mark texture as needing update on each decoded frame
                    if (self._videoTexture) self._videoTexture.needsUpdate = true;
                };
                this._sotaPlayer.onDisconnected = function() {
                    console.log('[VC] 3D WebCodecs stream disconnected');
                };
                this._sotaPlayer.onError = function(e) {
                    console.error('[VC] 3D WebCodecs error:', e);
                };
                this._sotaPlayer.start();

                // Build the bowl mesh + contact shadow
                this._createSurroundBowl();
                this._createContactShadow();

                // Hide ground grid — it conflicts with the surround view
                if (this._groundGrid) this._groundGrid.visible = false;

                // Bias scene lighting so the car reads as part of the AVM
                // scene rather than a showroom render on top of it.
                this._setLightsForBowl(true);

                // Cinematic fly-in: GSAP-tween OrbitControls from the saved
                // exterior pose to a hero position close to the car. The bowl
                // wraps the user (R=8); polar stays just above horizon so we
                // never look up at the cap or down through the floor.
                if (this.controls) {
                    this._savedPolarMin = this.controls.minPolarAngle;
                    this._savedPolarMax = this.controls.maxPolarAngle;
                    this._savedMinDistance = this.controls.minDistance;
                    this._savedMaxDistance = this.controls.maxDistance;
                    this._savedDamping = this.controls.dampingFactor;
                    this._savedAutoRotate = this.controls.autoRotate;
                    this._savedAutoRotateSpeed = this.controls.autoRotateSpeed;
                    this._savedCamPos = this.camera.position.clone();
                    this._savedTarget = this.controls.target.clone();
                    this._savedFov = this.camera.fov;

                    // Tighter limits while in the bowl so the camera can never
                    // pierce the wall or look at the cap.
                    this.controls.minPolarAngle = Math.PI * 0.22;
                    this.controls.maxPolarAngle = Math.PI * 0.52;
                    this.controls.minDistance = 2.4;
                    this.controls.maxDistance = 6.8;
                    this.controls.dampingFactor = 0.05;  // smoother orbit
                    this.controls.autoRotate = true;     // slow drift
                    this.controls.autoRotateSpeed = 0.18;

                    this._flyToHero();
                }

            } else {
                console.error('[VC] No H.264 decoder available (need SotaPlayer + WebCodecs)');
                this.toast(BYD.i18n.t('vehicle.requires_webcodecs'), 'error');
                this._3dViewActive = false;
                var btn = document.getElementById('btn3dView');
                if (btn) btn.classList.remove('on');
                return;
            }
        } catch(e) {
            console.error('[VC] 3D view start error:', e);
            this.toast(BYD.i18n.t('vehicle.view_3d_failed', {message: e.message}), 'error');
        }

        this.toast(BYD.i18n.t('vehicle.surround_view_active'), 'info');
    },

    /** DevTools-only: dumps the mean luminance of each mosaic quadrant so you
     *  can tell at a glance whether the source feed itself is missing a cam.
     *  Run while 3D view is active: `VC.diag3dCams()` — quadrant order in the
     *  mosaic is BL=Front, BR=Right, TL=Rear, TR=Left (matches the bowl shader).
     *  A near-zero luminance for one quadrant means the AVM hardware isn't
     *  publishing that camera; a non-zero value means the bowl shader is
     *  ignoring it (rotation/swap defaults wrong for this BYD model). */
    diag3dCams: function() {
        if (!this._3dCanvas) {
            console.warn('[VC] diag3dCams: 3D view not active');
            return null;
        }
        var ctx = this._3dCanvas.getContext('2d');
        var W = this._3dCanvas.width, H = this._3dCanvas.height;
        var HW = W >> 1, HH = H >> 1;
        // Sample each quadrant via getImageData — slow path, fine for one-shot diagnosis.
        function meanLuma(x, y, w, h) {
            try {
                var data = ctx.getImageData(x, y, w, h).data;
                var sum = 0, n = data.length / 4;
                for (var i = 0; i < data.length; i += 4) {
                    sum += data[i] * 0.299 + data[i+1] * 0.587 + data[i+2] * 0.114;
                }
                return Math.round(sum / n);
            } catch (e) { return -1; }
        }
        var r = {
            // Canvas coords (top-down). Mosaic layout per GpuStreamScaler:
            rear:  meanLuma(0,  0,  HW, HH),  // canvas TL
            left:  meanLuma(HW, 0,  HW, HH),  // canvas TR
            front: meanLuma(0,  HH, HW, HH),  // canvas BL
            right: meanLuma(HW, HH, HW, HH)   // canvas BR
        };
        console.log('[VC] mosaic luma — front=' + r.front + ' right=' + r.right +
                    ' rear=' + r.rear + ' left=' + r.left +
                    '  (low values < 8 indicate a missing/dark cam)');
        return r;
    },

    /** Cinematic GSAP fly-in from the saved exterior pose to a hero pose
     *  inside the bowl. Tweens position + target + FOV in one burst so it
     *  reads as a single camera move. Cheap on the head-unit GPU — three
     *  scalar tweens, no continuous timer. */
    _flyToHero: function() {
        if (typeof gsap === 'undefined' || !this.controls) return;
        var ctrl = this.controls;
        var cam = this.camera;
        // Hero pose: front-quarter, just above horizon, ~3.6m back.
        // Lands slightly off-axis so the user can see the bowl curve and the
        // car silhouette in one frame.
        var hero = { x: 2.6, y: 1.7, z: 3.4 };
        var heroTarget = { x: 0, y: 0.4, z: 0 };
        var heroFov = (window.innerWidth < 768) ? 48 : 56;
        gsap.to(cam.position, {
            x: hero.x, y: hero.y, z: hero.z,
            duration: 1.1, ease: 'power3.inOut',
            onUpdate: function() { ctrl.update(); }
        });
        gsap.to(ctrl.target, {
            x: heroTarget.x, y: heroTarget.y, z: heroTarget.z,
            duration: 1.1, ease: 'power3.inOut',
            onUpdate: function() { ctrl.update(); }
        });
        gsap.to(cam, {
            fov: heroFov, duration: 1.1, ease: 'power3.inOut',
            onUpdate: function() { cam.updateProjectionMatrix(); }
        });
    },

    /** Soft contact-shadow plane under the car. Tiny dark blob; gives the
     *  model "weight" so it doesn't look like it's floating above the bowl. */
    _createContactShadow: function() {
        if (!this.scene) return;
        // Procedural radial-gradient texture — no asset round-trip.
        var size = 256;
        var c = document.createElement('canvas');
        c.width = c.height = size;
        var cg = c.getContext('2d');
        var grad = cg.createRadialGradient(size/2, size/2, size*0.08, size/2, size/2, size*0.5);
        grad.addColorStop(0, 'rgba(0, 0, 0, 0.55)');
        grad.addColorStop(0.55, 'rgba(0, 0, 0, 0.18)');
        grad.addColorStop(1, 'rgba(0, 0, 0, 0)');
        cg.fillStyle = grad;
        cg.fillRect(0, 0, size, size);
        var tex = new THREE.CanvasTexture(c);
        tex.minFilter = THREE.LinearFilter;
        tex.magFilter = THREE.LinearFilter;

        var mat = new THREE.MeshBasicMaterial({
            map: tex,
            transparent: true,
            depthWrite: false
        });
        // Roughly car-shaped footprint: a bit wider lateral than longitudinal.
        var geo = new THREE.PlaneGeometry(2.6, 4.2);
        var mesh = new THREE.Mesh(geo, mat);
        mesh.rotation.x = -Math.PI / 2;
        mesh.position.y = -0.39;
        mesh.renderOrder = -1;
        this.scene.add(mesh);
        this._contactShadow = mesh;
    },

    stop3dView: function(skipFlyOut) {
        this._3dViewActive = false;
        this._3dStreamConnected = false;
        var vpReveal = document.querySelector('.vc-viewport');
        if (vpReveal) vpReveal.removeAttribute('data-3d-on');

        // Kill any in-flight camera fly-in tweens from start3dView so they can't
        // overwrite values we set further down (especially on the skipFlyOut path
        // where we snap the camera back to its saved pose).
        if (typeof gsap !== 'undefined' && this.camera && this.controls) {
            gsap.killTweensOf(this.camera.position);
            gsap.killTweensOf(this.camera);
            gsap.killTweensOf(this.controls.target);
        }
        if (this._3dTimeout) { clearTimeout(this._3dTimeout); this._3dTimeout = null; }
        var btn = document.getElementById('btn3dView');
        if (btn) btn.classList.remove('on');

        // Stop SotaPlayer
        if (this._sotaPlayer) {
            this._sotaPlayer.stop();
            this._sotaPlayer = null;
        }

        // Remove canvas
        if (this._3dCanvas) {
            if (this._3dCanvas.parentNode) this._3dCanvas.parentNode.removeChild(this._3dCanvas);
            this._3dCanvas = null;
        }

        // Remove bowl mesh, legacy ground disc (no-op now), contact shadow.
        if (this._skySphere && this.scene) {
            this.scene.remove(this._skySphere);
            this._skySphere.geometry.dispose();
            this._skySphere.material.dispose();
            this._skySphere = null;
        }
        if (this._groundDisc && this.scene) {
            this.scene.remove(this._groundDisc);
            this._groundDisc.geometry.dispose();
            this._groundDisc.material.dispose();
            this._groundDisc = null;
        }
        if (this._surroundDisc && this.scene) {
            this.scene.remove(this._surroundDisc);
            this._surroundDisc.geometry.dispose();
            this._surroundDisc.material.dispose();
            this._surroundDisc = null;
        }
        if (this._contactShadow && this.scene) {
            this.scene.remove(this._contactShadow);
            this._contactShadow.geometry.dispose();
            if (this._contactShadow.material.map) this._contactShadow.material.map.dispose();
            this._contactShadow.material.dispose();
            this._contactShadow = null;
        }

        if (this._videoTexture) {
            this._videoTexture.dispose();
            this._videoTexture = null;
        }

        this._3dDecoderMode = null;

        // Restore ground grid
        if (this._groundGrid) this._groundGrid.visible = true;

        // Stop the AVM-derived sky-tint sampler before we restore lighting,
        // so the last sample's HemisphereLight overwrite doesn't fight the
        // restore by landing one tick later.
        this._stopSkyTintSampler();

        // Restore showroom lighting.
        this._setLightsForBowl(false);

        // Restore orbit constraints + cinematic fly-out back to the hero pose
        if (this.controls && this._savedPolarMin !== undefined) {
            this.controls.minPolarAngle = this._savedPolarMin;
            this.controls.maxPolarAngle = this._savedPolarMax;
            if (this._savedMinDistance !== undefined) {
                this.controls.minDistance = this._savedMinDistance;
            }
            if (this._savedMaxDistance !== undefined) {
                this.controls.maxDistance = this._savedMaxDistance;
            }
            if (this._savedDamping !== undefined) {
                this.controls.dampingFactor = this._savedDamping;
            }
            this.controls.autoRotate = (this._savedAutoRotate !== undefined)
                ? this._savedAutoRotate : true;
            if (this._savedAutoRotateSpeed !== undefined) {
                this.controls.autoRotateSpeed = this._savedAutoRotateSpeed;
            }

            if (!skipFlyOut && this._savedCamPos && this._savedTarget && typeof gsap !== 'undefined') {
                var ctrl = this.controls;
                var cam = this.camera;
                gsap.to(cam.position, {
                    x: this._savedCamPos.x, y: this._savedCamPos.y, z: this._savedCamPos.z,
                    duration: 0.7, ease: 'power3.inOut',
                    onUpdate: function() { ctrl.update(); }
                });
                gsap.to(ctrl.target, {
                    x: this._savedTarget.x, y: this._savedTarget.y, z: this._savedTarget.z,
                    duration: 0.7, ease: 'power3.inOut',
                    onUpdate: function() { ctrl.update(); }
                });
                if (this._savedFov !== undefined) {
                    var camRef = cam;
                    gsap.to(cam, {
                        fov: this._savedFov, duration: 0.7, ease: 'power3.inOut',
                        onUpdate: function() { camRef.updateProjectionMatrix(); }
                    });
                }
            } else if (skipFlyOut) {
                // Snap camera + target to saved values without animating. Used when
                // we're auto-exiting 3D for a model swap — the new model is about
                // to load and we don't want a stale fly-out to fight the new layout.
                if (this._savedCamPos) {
                    this.camera.position.set(this._savedCamPos.x, this._savedCamPos.y, this._savedCamPos.z);
                }
                if (this._savedTarget) {
                    this.controls.target.set(this._savedTarget.x, this._savedTarget.y, this._savedTarget.z);
                }
                if (this._savedFov !== undefined) {
                    this.camera.fov = this._savedFov;
                    this.camera.updateProjectionMatrix();
                }
                this.controls.update();
            }
        }

        // Restore stream quality to LOW (default for remote viewing)
        fetch('/api/stream/quality/LOW', { method: 'POST' }).catch(function() {});

        this.toast(BYD.i18n.t('vehicle.view_3d_off'), 'info');
    },

    /**
     * Surround geometry: a single PARAMETRIC BOWL mesh — flat near the car,
     * smoothly curving up to wall height at the perimeter. Replaces the older
     * cylinder-wall + flat-disc pair which had a hard crease at the seam that
     * read as "fake" no matter how good the shader blend was.
     *
     * Bowl profile (radial, axisymmetric):
     *   r ∈ [0, R_FLAT]      y = Y_FLOOR              (flat near-field)
     *   r ∈ [R_FLAT, R_WALL] y = Y_FLOOR + A·(r-R_FLAT)²   (C¹ ramp)
     * with A chosen so y(R_WALL) = WALL_TOP — gives a tangent-continuous floor
     * → wall transition, no visible crease.
     *
     * Every bowl pixel is painted by a single fragment shader that runs the
     * same geometric IPM the old disc used, but generalised to ARBITRARY 3D
     * world points (not just ground). That means the curved-up region pulls
     * its content from the cams' actual image rows above the horizon — sky,
     * garage roof, parking-lot ceiling — instead of the procedural horizon /
     * zenith gradient the old wall shader painted (which was just a fake).
     *
     * Mosaic layout (after THREE.CanvasTexture flipY=true):
     *   tex space    canvas    camera
     *   (0.0, 0.0)   BL        Front
     *   (0.5, 0.0)   BR        Right
     *   (0.0, 0.5)   TL        Rear
     *   (0.5, 0.5)   TR        Left
     *
     * Bearing: atan2(x, -z) → 0=front (-Z), +π/2=right (+X),
     *                          ±π=rear (+Z), -π/2=left (-X).
     *
     * Per-cam knobs (arrays of length 4 indexed by world-quadrant idx
     * 0=Front, 1=Right, 2=Rear, 3=Left). Rear (idx 2) is our reference —
     * it already looks clean. The other three are tuneable so we can match
     * its quality without affecting rear.
     *
     *   _3dCropBottom    [4]  hide bottom N% of each cam (car body / wheel)
     *   _3dCropTop       [4]  hide top N% of each cam (warped sky)
     *   _3dFishStrength  [4]  per-cam fisheye-undistort strength (0..1)
     *
     * Global knobs:
     *   _3dRotate        0..3   rotate camera assignment by 90° steps
     *   _3dSwapLR        bool   swap Left/Right cams
     *   _3dSwapFR        bool   swap Front/Rear cams
     *   _3dSideMirror    bool   horizontally flip side-camera images
     *   _3dRearMirror    bool   horizontally flip rear-camera image
     *   _3dFeather       0..0.5 seam blend half-width (fraction of quadrant)
     *
     * Apply changes via:  VC.stop3dView(); VC.start3dView();
     */
    _createSurroundBowl: function() {
        if (!this.scene) return;

        if (this._3dCanvas) {
            this._videoTexture = new THREE.CanvasTexture(this._3dCanvas);
            this._videoTexture.minFilter = THREE.LinearFilter;
            this._videoTexture.magFilter = THREE.LinearFilter;
            // Anisotropic filtering: sharper sampling at grazing angles where
            // the bowl curves away from the camera. Cheap on r147 + WebGL 1
            // (uses EXT_texture_filter_anisotropic when available, no-ops
            // gracefully on the BYD WebView when the extension is missing).
            if (this.renderer && this.renderer.capabilities &&
                typeof this.renderer.capabilities.getMaxAnisotropy === 'function') {
                var maxAniso = this.renderer.capabilities.getMaxAnisotropy() || 1;
                this._videoTexture.anisotropy = Math.min(8, maxAniso);
            }
        } else {
            console.error('[VC] No canvas available for surround view');
            return;
        }

        // Global mapping knobs.
        // Side cams are mounted under the wing mirrors and the sensor's
        // X-axis runs opposite the world's bearing axis — same situation
        // we already handle for the rear cam. Default to mirrored so the
        // side feeds match rear's correctness out of the box; the user
        // can flip back via VC._3dSideMirror = false if their specific
        // model is wired differently.
        if (this._3dSideMirror === undefined) this._3dSideMirror = true;
        if (this._3dRearMirror === undefined) this._3dRearMirror = false;
        if (this._3dRotate     === undefined) this._3dRotate = 0;
        if (this._3dSwapLR     === undefined) this._3dSwapLR = false;
        if (this._3dSwapFR     === undefined) this._3dSwapFR = false;
        if (this._3dFeather    === undefined) this._3dFeather = 0.30;

        // Per-cam tuning. Indices: 0=Front, 1=Right, 2=Rear, 3=Left.
        //
        // Rear (idx 2) is the reference: 0.15 / 0.08 / 0.6 produced a clean
        // image on the user's vehicle. The other three start from educated
        // defaults based on typical 4-cam AVM mount geometry — rear sits
        // high on the boot lid and looks ~level, but front sits low in the
        // grille and sees the hood (deeper bottom crop), and sides sit
        // under the wing mirrors and look DOWN at the road past the door
        // panel + wheel arch (much deeper bottom crop, wider lens →
        // stronger fish-eye undistort).
        //
        // Tweak per BYD model from DevTools without affecting rear:
        //   VC._3dCropBottom    = [front, right, rear, left]   // 0..0.5
        //   VC._3dCropTop       = [front, right, rear, left]   // 0..0.5
        //   VC._3dFishStrength  = [front, right, rear, left]   // 0..1
        // Apply with: VC.stop3dView(); VC.start3dView();
        // The wall now ONLY paints content above the horizon — the disc owns
        // ground content via geometric IPM (see _createGroundDisc below). To
        // keep car bodywork off the wall, cropBottom is pushed up well above
        // the visible body lip on each cam. Rear cam sits high on the boot lid
        // and sees less of the body than the wing-mirror sides do.
        if (!this._3dCropBottom || this._3dCropBottom.length !== 4) {
            //                       F     R     Rear  L
            this._3dCropBottom = [0.42, 0.52, 0.32, 0.52];
        }
        if (!this._3dCropTop || this._3dCropTop.length !== 4) {
            //                       F     R     Rear  L
            this._3dCropTop    = [0.10, 0.08, 0.10, 0.08];
        }
        if (!this._3dFishStrength || this._3dFishStrength.length !== 4) {
            //                       F     R     Rear  L
            this._3dFishStrength = [0.55, 0.70, 0.60, 0.70];
        }

        // ─── Ground-disc IPM parameters ─────────────────────────────────
        // Per-cam extrinsics + intrinsics for inverse perspective mapping
        // on the ground disc. Idx order (post-remap): 0=Front, 1=Right,
        // 2=Rear, 3=Left.
        //
        // Tweak from DevTools without rebuilding the wall:
        //   VC._3dCamHeight  = [F, R, Rear, L]   // metres above ground
        //   VC._3dCamTilt    = [F, R, Rear, L]   // pitch-down rad (~0.4-0.8)
        //   VC._3dCamYaw     = [F, R, Rear, L]   // yaw bias rad (±0.2 fixes per-side skew)
        //   VC._3dCamFov     = [F, R, Rear, L]   // half-FOV rad (~1.6 = 92°)
        //   VC._3dNearClip   = [F, R, Rear, L]   // metres — ground closer = under-car
        //   VC._3dFarClip    = [F, R, Rear, L]   // metres — fade to wall beyond this
        // Apply with: VC.stop3dView(); VC.start3dView();
        if (!this._3dCamHeight || this._3dCamHeight.length !== 4) {
            //                     F     R     Rear  L      (m above ground)
            this._3dCamHeight = [0.65, 0.95, 1.05, 0.95];
        }
        if (!this._3dCamTilt || this._3dCamTilt.length !== 4) {
            //                     F     R     Rear  L      (rad — pitch down)
            this._3dCamTilt   = [0.55, 0.75, 0.55, 0.75];
        }
        if (!this._3dCamYaw || this._3dCamYaw.length !== 4) {
            //                     F     R     Rear  L      (rad — yaw bias)
            this._3dCamYaw    = [0.00, 0.00, 0.00, 0.00];
        }
        if (!this._3dCamFov || this._3dCamFov.length !== 4) {
            // Effective pinhole half-FOV in radians. NOT the lens's optical
            // half-FOV (which is ~95° on these fisheyes — would blow up tan()
            // since tan approaches ±∞ at π/2). The fisheye re-curve below maps
            // this pinhole space back into actual lens space, so values in the
            // 60–80° (1.05–1.40 rad) range work best.
            //                     F     R     Rear  L      (rad — pinhole half-FOV)
            this._3dCamFov    = [1.20, 1.25, 1.20, 1.25];
        }
        if (!this._3dNearClip || this._3dNearClip.length !== 4) {
            // Distance from each CAM MOUNT (not from origin). The body-hole
            // already excludes pixels inside the car footprint; near-clip
            // here is the inner radius of useful IPM around each cam — far
            // enough out to avoid extreme down-look distortion under the
            // bumper, but not so far that the cam loses its near-field view.
            //                     F     R     Rear  L      (m — from cam)
            this._3dNearClip  = [0.40, 0.40, 0.40, 0.40];
        }
        if (!this._3dFarClip || this._3dFarClip.length !== 4) {
            // Distance from each CAM MOUNT to where the cams contribution
            // fades out for the wall seam. The disc edge is at world-radius
            // ~7.84m; from a cam mounted ±2.2m the far edge is up to ~10m.
            //                     F     R     Rear  L      (m — from cam)
            this._3dFarClip   = [10.5, 9.5,  10.5, 9.5];
        }
        // Cam mount positions in world XZ. The car sits at origin facing -Z, so
        // the front cam is at -Z, rear at +Z, side cams at ±X. Without these
        // offsets the IPM model places all cams at the origin and far-field
        // ground content lands in the wrong pixel — visible on near/mid-field
        // only since the offset error fraction shrinks with distance.
        if (!this._3dCamPosX || this._3dCamPosX.length !== 4) {
            //                     F     R     Rear  L      (m — world X)
            this._3dCamPosX   = [0.00,  0.95, 0.00, -0.95];
        }
        if (!this._3dCamPosZ || this._3dCamPosZ.length !== 4) {
            //                     F     R     Rear  L      (m — world Z; -Z = front)
            this._3dCamPosZ   = [-2.20, 0.00, 2.20, 0.00];
        }

        var WALL_RADIUS = 8.0;
        var WALL_HEIGHT = 5.0;
        var WALL_BOTTOM = -0.4;

        // GLSL fragment-shader fragment shared by wall + disc.  Defines
        // sampleSurround(bearing, vSample) and the helpers it needs.
        // Returns a vec4 where .a < 1.0 indicates the sample lies in the
        // cropped top/bottom of the cam (used to fade those areas out).
        var SHARED_GLSL = [
            'uniform sampler2D uTexture;',
            'uniform float uMirrorSides;',
            'uniform float uMirrorRear;',
            'uniform float uFeather;',
            'uniform int   uRotate;',
            'uniform float uSwapLR;',
            'uniform float uSwapFR;',
            // Per-cam tuning arrays (indexed 0=Front, 1=Right, 2=Rear, 3=Left
            // in WORLD space — sampleAt uses worldIdx, not the post-remap idx,
            // so the same physical camera always gets the same crop/fish even
            // when uRotate/uSwap* are non-default).
            'uniform float uCropBottom[4];',
            'uniform float uCropTop[4];',
            'uniform float uFishStrength[4];',
            '',
            'vec2 quadOrigin(int idx) {',
            '    if (idx == 0) return vec2(0.0, 0.0);',  // Front
            '    if (idx == 1) return vec2(0.5, 0.0);',  // Right
            '    if (idx == 2) return vec2(0.0, 0.5);',  // Rear
            '    return vec2(0.5, 0.5);',                // Left
            '}',
            '',
            'int remapIdx(int worldIdx) {',
            '    int idx = int(mod(float(worldIdx) + float(uRotate), 4.0));',
            '    if (uSwapLR > 0.5) {',
            '        if (idx == 1) idx = 3;',
            '        else if (idx == 3) idx = 1;',
            '    }',
            '    if (uSwapFR > 0.5) {',
            '        if (idx == 0) idx = 2;',
            '        else if (idx == 2) idx = 0;',
            '    }',
            '    return idx;',
            '}',
            '',
            '// Per-cam GLSL ES 1.00 array indexing: index must be a',
            '// constant-index expression on the BYD WebView (no dynamic',
            '// indices on uniform arrays). Branch instead of subscript.',
            'float pickFloat4(float a[4], int idx) {',
            '    if (idx == 0) return a[0];',
            '    if (idx == 1) return a[1];',
            '    if (idx == 2) return a[2];',
            '    return a[3];',
            '}',
            '',
            '// Generic radial fisheye undistortion. Treats the cam frame',
            '// as a normalised (-1,-1)..(+1,+1) plane, computes the polar',
            '// radius r, and remaps it through an atan-style curve so',
            '// straight world lines (lane markings) come out straighter.',
            '// fishStrength = 0 disables (returns input unchanged).',
            'vec2 undistort(vec2 xy, float fishStrength) {',
            '    float r = length(xy);',
            '    if (r < 1e-4 || fishStrength < 0.001) return xy;',
            '    // Approx fisheye half-FOV ~95° → tan(0.95) ≈ 1.40.',
            '    float k = 1.40;',
            '    // r_undist = tan(r * atan(k)) / k  — pulls peripheral',
            '    // pixels inward, straightening barrel curvature.',
            '    float rUndist = tan(r * atan(k)) / k;',
            '    float scale = mix(1.0, rUndist / r, fishStrength);',
            '    return xy * scale;',
            '}',
            '',
            '// Returns the sampled cam color in .rgb plus a "valid" weight',
            '// in .a — 1.0 fully visible, fading to 0 at the cropped edges so',
            '// the caller can smoothly blend to the bowl background colour.',
            '// Crucially, even inside the crop band we still SAMPLE THE TEXTURE',
            '// (clamped to the kept range) — so the cropped strip reads as',
            '// "dimmed continuation of the cam image" rather than a hard black',
            '// rectangle.',
            'vec4 sampleAt(int worldIdx, float centeredOffset, float vSample) {',
            '    int idx = remapIdx(worldIdx);',
            '    vec2 qo = quadOrigin(idx);',
            '    // Per-cam params — indexed by physical cam idx (post-remap),',
            '    // so per-cam tuning sticks to the physical sensor regardless',
            '    // of any future rotation/swap defaults.',
            '    float fishStrength = pickFloat4(uFishStrength, idx);',
            '    float cropBottom   = pickFloat4(uCropBottom,   idx);',
            '    float cropTop      = pickFloat4(uCropTop,      idx);',
            '    float c = centeredOffset;',
            '    if (idx == 2 && uMirrorRear  > 0.5) c = -c;',
            '    if ((idx == 1 || idx == 3) && uMirrorSides > 0.5) c = -c;',
            '',
            '    // Build a normalised (-1,-1)..(+1,+1) coord inside this',
            '    // cam frame so undistort() can operate on a circular',
            '    // domain. After undistortion convert back to (u,v) in',
            '    // [0,1] within the quadrant.',
            '    vec2 nxy = vec2(c, vSample * 2.0 - 1.0);',
            '    nxy = undistort(nxy, fishStrength);',
            '    float localU = 0.5 + 0.5 * nxy.x;',
            '    float localV = 0.5 + 0.5 * nxy.y;',
            '',
            '    // Crop band: skip cropBottom of the bottom (car body) and',
            '    // cropTop of the top (warped sky).  We CLAMP the V into the',
            '    // kept range when sampling so the texture continues visually',
            '    // into the cropped edge (no abrupt black band), but emit an',
            '    // alpha that fades over a soft band so the caller can blend',
            '    // smoothly to the bowl background.',
            '    float vMin = cropBottom;',
            '    float vMax = 1.0 - cropTop;',
            '    // Sampling V — clamp into the visible band so cropped pixels',
            '    // read from the nearest valid row of the cam image.',
            '    float vSamp = clamp(localV, vMin, vMax);',
            '',
            '    // Alpha — soft fade across an inset band inside the crop edge',
            '    // so the transition into bg is gradual.  fadePx defines the',
            '    // soft-edge thickness inside both the bottom and top crops.',
            '    float fadePx = 0.06;',
            '    float bottomFade = smoothstep(vMin - fadePx, vMin + fadePx, localV);',
            '    float topFade    = smoothstep(vMax + fadePx, vMax - fadePx, localV);',
            '    float vMask = bottomFade * topFade;',
            '',
            '    // Reject samples fully outside the frame after undistortion.',
            '    float xMask = step(0.0, localU) * step(localU, 1.0);',
            '    float yMask = step(-fadePx, localV) * step(localV, 1.0 + fadePx);',
            '    float mask = vMask * xMask * yMask;',
            '',
            '    vec2 uv = vec2(qo.x + clamp(localU, 0.0, 1.0) * 0.5,',
            '                   qo.y + vSamp * 0.5);',
            '    vec4 col = texture2D(uTexture, uv);',
            '    col.a = mask;',
            '    return col;',
            '}',
            '',
            'vec4 sampleSurround(float bearing, float vSample) {',
            '    // Shift bearing by +π/4 so quadrants are CENTRED on the cardinal',
            '    // directions: bearing 0 (= world front) lands in the middle of',
            '    // the Front quadrant, +π/2 in the middle of Right, etc.',
            '    float b = mod(bearing + 0.78540, 6.28318);',
            '    if (b < 0.0) b += 6.28318;',
            '    float virtIdx = b / 1.5708;',           // 0..4
            '    float idxFloor = floor(virtIdx);',
            '    float frac = virtIdx - idxFloor;',       // 0..1 across one quadrant
            '    float centered = frac * 2.0 - 1.0;',     // -1..+1 across assigned quadrant
            '    float feather = uFeather;',
            '',
            '    int idxA = int(mod(idxFloor, 4.0));',
            '    vec4 colA = sampleAt(idxA, centered, vSample);',
            '',
            '    if (feather > 0.001 && frac < feather) {',
            '        int idxB = int(mod(idxFloor + 3.0, 4.0));',
            '        float centeredB = 1.0 + frac;',
            '        vec4 colB = sampleAt(idxB, centeredB, vSample);',
            '        float w = smoothstep(0.0, feather, frac);',
            '        return mix(colB, colA, w);',
            '    } else if (feather > 0.001 && frac > 1.0 - feather) {',
            '        int idxB = int(mod(idxFloor + 1.0, 4.0));',
            '        float centeredB = -1.0 + (frac - 1.0);',
            '        vec4 colB = sampleAt(idxB, centeredB, vSample);',
            '        float w = smoothstep(1.0, 1.0 - feather, frac);',
            '        return mix(colB, colA, w);',
            '    }',
            '    return colA;',
            '}',
            '',
            '// Helper: compose the surround sample against a dark background',
            '// so cropped/out-of-frame pixels fade smoothly to the bowl colour',
            '// instead of showing whatever happens to be in the texture there.',
            'vec3 composeSurround(vec3 surround_rgb, float alpha, vec3 bg) {',
            '    return mix(bg, surround_rgb, alpha);',
            '}',
            '',
            '// ─── Inverse perspective mapping for the ground disc ──────────',
            '// Each cam is modelled as a pinhole at (0, h, d) looking outward',
            '// with pitch-down `tilt`, yaw bias `yawBias`, and half-FOV `hfov`.',
            '// Given a world-space ground point (gx, 0, gz), reproject it back',
            '// into the cams normalised image plane (-1..+1) and sample the',
            '// matching mosaic quadrant. Returns a vec4 where .a is the per-cam',
            '// confidence (0 = behind/clipped, 1 = squarely in frame).',
            'uniform float uCamHeight[4];',
            'uniform float uCamTilt[4];',
            'uniform float uCamYaw[4];',
            'uniform float uCamFov[4];',
            'uniform float uNearClip[4];',
            'uniform float uFarClip[4];',
            'uniform float uCamPosX[4];',
            'uniform float uCamPosZ[4];',
            'uniform float uYFloor;',
            'uniform float uBodyHalfX;',
            'uniform float uBodyHalfZ;',
            // Per-quadrant mean RGB of the AVM mosaic (4×1 downsample, ~1Hz).
            // Indexed by post-remap idx 0=Front,1=Right,2=Rear,3=Left.
            'uniform vec3 uSkyTint[4];',
            // Per-cam exposure-match gain. Computed JS-side from the same
            // 1Hz mosaic sample by targeting the MEDIAN of the 4 quadrant
            // lumas, so one over- or under-exposed cam doesnt pull the
            // others. Bounded [0.6, 1.6] to avoid over-correction. Applied
            // multiplicatively to col.rgb in sampleAt — the gain is keyed
            // by post-remap idx (the physical cam) so it follows the cam
            // through any rotate/swap.
            'uniform float uCamGain[4];',
            '',
            '// Forward direction (in world XZ) for each PHYSICAL cam after',
            '// the post-remap idx is known. Front=-Z, Right=+X, Rear=+Z, Left=-X.',
            'vec2 camForward(int idx) {',
            '    if (idx == 0) return vec2( 0.0, -1.0);',  // Front
            '    if (idx == 1) return vec2( 1.0,  0.0);',  // Right
            '    if (idx == 2) return vec2( 0.0,  1.0);',  // Rear
            '    return vec2(-1.0, 0.0);',                  // Left
            '}',
            '',
            'vec4 sampleGroundFromCam(int worldIdx, vec2 ground) {',
            '    // Per-cam params index by WORLD idx (not remapped) so they stay',
            '    // attached to the physical cam mount regardless of mosaic-layout',
            '    // swap/rotate knobs. remapIdx is only consulted by sampleAt to',
            '    // find the right quadrant in the texture atlas.',
            '    float h     = pickFloat4(uCamHeight, worldIdx);',
            '    float tilt  = pickFloat4(uCamTilt,   worldIdx);',
            '    float yawB  = pickFloat4(uCamYaw,    worldIdx);',
            '    float hfov  = pickFloat4(uCamFov,    worldIdx);',
            '    float near  = pickFloat4(uNearClip,  worldIdx);',
            '    float far   = pickFloat4(uFarClip,   worldIdx);',
            '',
            '    vec2 fwd = camForward(worldIdx);',
            '    // Apply yaw bias to the forward vector (rotates around Y in XZ).',
            '    float cy = cos(yawB), sy = sin(yawB);',
            '    vec2 fwdR = vec2(fwd.x * cy - fwd.y * sy, fwd.x * sy + fwd.y * cy);',
            '    // Right vector in XZ: rotate fwd -90° about Y in Three.js RH coords',
            '    // so that fwd=(0,-1) (world -Z = front) gives right=(+1,0) (world +X).',
            '    vec2 right = vec2(-fwdR.y, fwdR.x);',
            '',
            '    // Translate the world ground point into cam-LOCAL XZ. The cam',
            '    // sits at (camPosX, camPosZ) in world space, so a point P_world',
            '    // appears as (P - camPos) from the cams POV. Without this,',
            '    // near/mid-field IPM lands on the wrong pixel (the further the',
            '    // mount is from origin, the larger the error fraction).',
            '    float camX = pickFloat4(uCamPosX, worldIdx);',
            '    float camZ = pickFloat4(uCamPosZ, worldIdx);',
            '    vec2 local = ground - vec2(camX, camZ);',
            '',
            '    // Cam-space ground vector (forward = +Z_cam, right = +X_cam).',
            '    float zc = dot(local, fwdR);',
            '    float xc = dot(local, right);',
            '    float dist = length(local);',
            '',
            '    // Clip behind the cam, under the car, or past the disc edge.',
            '    if (zc <= 0.05) return vec4(0.0);',
            '    if (dist < near || dist > far) return vec4(0.0);',
            '',
            '    // World ray to the ground point in untilted cam frame: (xc, -h, zc).',
            '    // The cam is pitched DOWN by `tilt` (optical axis rotates from +Z',
            '    // toward -Y). To express the ray in the tilted cam frame we apply',
            '    // the INVERSE rotation, i.e. rotate +tilt about cam-X:',
            '    //   y_tilted =  y*cos + z*sin',
            '    //   z_tilted = -y*sin + z*cos',
            '    float ct = cos(tilt), st = sin(tilt);',
            '    float yr = -h * ct + zc * st;',
            '    float zr =  h * st + zc * ct;',
            '    if (zr <= 0.05) return vec4(0.0);',
            '',
            '    // Normalised image-plane coords. tan(hfov) sets the horizontal',
            '    // half-extent at unit depth — same convention as the wall',
            '    // shaders fisheye undistort.',
            '    float k = tan(hfov);',
            '    float u = (xc / zr) / k;',
            '    float v = (yr / zr) / k;',
            '',
            '    // (u, v) is now in PINHOLE-RECTIFIED sensor space — exactly what',
            '    // sampleAt expects, since sampleAt internally calls undistort()',
            '    // to map rectified coords back into the raw fisheye texture.',
            '    // Reject points past the rectified-image bounds; sampleAt will',
            '    // additionally fade at the per-cam crop edges.',
            '    float r = length(vec2(u, v));',
            '    if (r > 1.0) return vec4(0.0);',
            '',
            '    // sampleAt expects: c in [-1..+1] (horiz-centred) and vSample',
            '    // in [0..1] mapped 0=bottom-of-cam-frame .. 1=top. Image-plane',
            '    // y is +up; ground points project below the optical axis (v<0)',
            '    // so they must land in the lower half of the cam image (vSample',
            '    // < 0.5). Mapping: vSample = 0.5 + 0.5*v.',
            '    float vSample = 0.5 + 0.5 * v;',
            '    vec4 col = sampleAt(worldIdx, u, vSample);',
            '',
            '    // Confidence: high in the image centre, falling off toward the',
            '    // edge of the lens circle and toward the cams near/far clips.',
            '    float radial = 1.0 - smoothstep(0.55, 0.95, r);',
            '    float nearF  = smoothstep(near, near + 0.4, dist);',
            '    float farF   = 1.0 - smoothstep(far - 1.2, far, dist);',
            '    col.a *= radial * nearF * farF;',
            '    return col;',
            '}',
            '',
            'vec4 sampleGround(vec2 ground) {',
            '    // POWER-WEIGHTED blend across the 4 cams. Each cams contribution',
            '    // is weighted by alpha^P so the dominant cam in any given pixel',
            '    // visually wins rather than getting averaged to mush in overlap',
            '    // zones (which causes the ghost-doubling that reads as "this is',
            '    // not Tesla"). P=3 gives a near-Voronoi look — clean seams in',
            '    // overlap regions without the dead-zone risk of a hard bearing',
            '    // partition (zero-confidence cams still contribute zero, so a',
            '    // mis-classified point can never punch a black hole).',
            '    vec4 s0 = sampleGroundFromCam(0, ground);',
            '    vec4 s1 = sampleGroundFromCam(1, ground);',
            '    vec4 s2 = sampleGroundFromCam(2, ground);',
            '    vec4 s3 = sampleGroundFromCam(3, ground);',
            '    float w0 = s0.a * s0.a * s0.a;',
            '    float w1 = s1.a * s1.a * s1.a;',
            '    float w2 = s2.a * s2.a * s2.a;',
            '    float w3 = s3.a * s3.a * s3.a;',
            '    float wsum = w0 + w1 + w2 + w3;',
            '    if (wsum < 1e-5) return vec4(0.0);',
            '    vec3 rgb = (s0.rgb * w0 + s1.rgb * w1 +',
            '                s2.rgb * w2 + s3.rgb * w3) / wsum;',
            '    // Output alpha stays in linear-confidence units so the caller can',
            '    // still fade to bg correctly — the power weighting only reshapes',
            '    // the cross-cam color blend, not the visibility envelope.',
            '    float aSum = clamp(s0.a + s1.a + s2.a + s3.a, 0.0, 1.0);',
            '    return vec4(rgb, aSum);',
            '}',
            '',
            '// ─── 3D-generalised IPM ───────────────────────────────────────',
            '// sampleSpaceFromCam projects ANY world-space 3D point through',
            '// the cam pinhole+fisheye model — same math as sampleGroundFromCam',
            '// but with the y-coord generalised from the constant -h (ground)',
            '// to (wpos.y - (Y_FLOOR + h)). That lets the bowl curve up off',
            '// the floor and still pull content from the actual cam image rows',
            '// above the horizon (sky / garage roof / parking-lot ceiling)',
            '// rather than a procedural sky gradient.',
            '//',
            '// Y_FLOOR is the scene-y of the bowl floor; the cam IPM treats',
            '// the floor plane as ground=0, so the cams effective scene-y is',
            '// (Y_FLOOR + h). Inlined as a literal at JS build time.',
            'vec4 sampleSpaceFromCam(int worldIdx, vec3 wpos) {',
            '    float h     = pickFloat4(uCamHeight, worldIdx);',
            '    float tilt  = pickFloat4(uCamTilt,   worldIdx);',
            '    float yawB  = pickFloat4(uCamYaw,    worldIdx);',
            '    float hfov  = pickFloat4(uCamFov,    worldIdx);',
            '    float near  = pickFloat4(uNearClip,  worldIdx);',
            '    float far   = pickFloat4(uFarClip,   worldIdx);',
            '',
            '    vec2 fwd = camForward(worldIdx);',
            '    float cy = cos(yawB), sy = sin(yawB);',
            '    vec2 fwdR = vec2(fwd.x * cy - fwd.y * sy, fwd.x * sy + fwd.y * cy);',
            '    vec2 right = vec2(-fwdR.y, fwdR.x);',
            '',
            '    float camX = pickFloat4(uCamPosX, worldIdx);',
            '    float camZ = pickFloat4(uCamPosZ, worldIdx);',
            '    vec2 local = wpos.xz - vec2(camX, camZ);',
            '',
            '    float zc = dot(local, fwdR);',
            '    float xc = dot(local, right);',
            '    float yc = wpos.y - (uYFloor + h);',
            '    float dist = length(vec3(xc, yc, zc));',
            '',
            '    if (zc <= 0.05) return vec4(0.0);',
            '    // Far clip is generous on upper bowl since rim points sit at',
            '    // ~10m diagonal from a side cam — keep them visible.',
            '    if (dist < near || dist > far * 1.6) return vec4(0.0);',
            '',
            '    // Tilt rotation about the cam-X axis.',
            '    float ct = cos(tilt), st = sin(tilt);',
            '    float yr = yc * ct + zc * st;',
            '    float zr = -yc * st + zc * ct;',
            '    if (zr <= 0.05) return vec4(0.0);',
            '',
            '    float k = tan(hfov);',
            '    float u = (xc / zr) / k;',
            '    float v = (yr / zr) / k;',
            '',
            '    float r = length(vec2(u, v));',
            '    if (r > 1.0) return vec4(0.0);',
            '',
            '    float vSample = 0.5 + 0.5 * v;',
            '    vec4 col = sampleAt(worldIdx, u, vSample);',
            '',
            '    float radial = 1.0 - smoothstep(0.55, 0.95, r);',
            '    float nearF  = smoothstep(near, near + 0.4, dist);',
            '    float farF   = 1.0 - smoothstep(far * 1.6 - 1.2, far * 1.6, dist);',
            '    col.a *= radial * nearF * farF;',
            '    return col;',
            '}',
            '',
            'vec4 sampleSpace(vec3 wpos) {',
            '    // Same power-weighted blend as sampleGround — see the longer',
            '    // comment there for the rationale. Reusing the exponent so the',
            '    // floor↔curve crossfade in the bowl shader doesnt show a',
            '    // change-of-regime in cam selection at the seam.',
            '    vec4 s0 = sampleSpaceFromCam(0, wpos);',
            '    vec4 s1 = sampleSpaceFromCam(1, wpos);',
            '    vec4 s2 = sampleSpaceFromCam(2, wpos);',
            '    vec4 s3 = sampleSpaceFromCam(3, wpos);',
            '    float w0 = s0.a * s0.a * s0.a;',
            '    float w1 = s1.a * s1.a * s1.a;',
            '    float w2 = s2.a * s2.a * s2.a;',
            '    float w3 = s3.a * s3.a * s3.a;',
            '    float wsum = w0 + w1 + w2 + w3;',
            '    if (wsum < 1e-5) return vec4(0.0);',
            '    vec3 rgb = (s0.rgb * w0 + s1.rgb * w1 +',
            '                s2.rgb * w2 + s3.rgb * w3) / wsum;',
            '    float aSum = clamp(s0.a + s1.a + s2.a + s3.a, 0.0, 1.0);',
            '    return vec4(rgb, aSum);',
            '}'
        ].join('\n');

        // Body-hole half-extents: bbox-driven if the model has loaded, else
        // fall back to BYD Seal defaults so the bowl still works on first-run
        // race conditions before _cacheCarBounds has fired.
        var bodyHalfX = (typeof this._carHalfX === 'number' && this._carHalfX > 0)
            ? this._carHalfX : 0.95;
        var bodyHalfZ = (typeof this._carHalfZ === 'number' && this._carHalfZ > 0)
            ? this._carHalfZ : 2.35;

        // Shared uniforms for the bowl shader. The 3D-IPM helpers in
        // SHARED_GLSL key off uYFloor (the bowl's floor scene-y) and
        // uBodyHalfX/Z (the body hole footprint).
        var sharedUniforms = function() {
            return {
                uTexture:       { value: this._videoTexture },
                uMirrorSides:   { value: this._3dSideMirror ? 1.0 : 0.0 },
                uMirrorRear:    { value: this._3dRearMirror ? 1.0 : 0.0 },
                uFeather:       { value: this._3dFeather },
                uRotate:        { value: (this._3dRotate | 0) },
                uSwapLR:        { value: this._3dSwapLR ? 1.0 : 0.0 },
                uSwapFR:        { value: this._3dSwapFR ? 1.0 : 0.0 },
                uCropBottom:    { value: this._3dCropBottom.slice() },
                uCropTop:       { value: this._3dCropTop.slice() },
                uFishStrength:  { value: this._3dFishStrength.slice() },
                uCamHeight:     { value: this._3dCamHeight.slice() },
                uCamTilt:       { value: this._3dCamTilt.slice() },
                uCamYaw:        { value: this._3dCamYaw.slice() },
                uCamFov:        { value: this._3dCamFov.slice() },
                uNearClip:      { value: this._3dNearClip.slice() },
                uFarClip:       { value: this._3dFarClip.slice() },
                uCamPosX:       { value: this._3dCamPosX.slice() },
                uCamPosZ:       { value: this._3dCamPosZ.slice() },
                uYFloor:        { value: WALL_BOTTOM },
                uBodyHalfX:     { value: bodyHalfX },
                uBodyHalfZ:     { value: bodyHalfZ },
                // Per-quadrant mean RGB of the live AVM mosaic, sampled at
                // ~1Hz from a 4×1 downsample canvas. Drives the procedural
                // sky tint so the upper bowl warms / cools with the actual
                // scene (dusk → warm horizon, fluoro garage → cool tint).
                // Order matches mosaic quadrant idx:
                //   0=Front  1=Right  2=Rear  3=Left
                // Initial neutral grey so first frame doesn't pop.
                uSkyTint:       { value: [
                    new THREE.Vector3(0.5, 0.5, 0.5),
                    new THREE.Vector3(0.5, 0.5, 0.5),
                    new THREE.Vector3(0.5, 0.5, 0.5),
                    new THREE.Vector3(0.5, 0.5, 0.5)
                ] },
                // Per-cam exposure-match gain. Updated by the same 1Hz
                // sampler from the per-quadrant luma; targets the median
                // luma so one over/under-exposed cam doesnt pull the others.
                uCamGain:       { value: [1.0, 1.0, 1.0, 1.0] }
            };
        }.bind(this);

        // ── Parametric bowl ────────────────────────────────────────────
        // Single radial-fan mesh: flat near the car, smoothly curving up to
        // wall height at the perimeter. C¹-continuous (tangent-matched) at
        // the floor→curve transition so there's no visible crease, unlike
        // the previous cylinder+disc pair.
        //
        // Profile (axisymmetric, r = sqrt(x²+z²)):
        //   r ∈ [0, R_FLAT]      y = WALL_BOTTOM
        //   r ∈ [R_FLAT, R_WALL] y = WALL_BOTTOM + A·(r-R_FLAT)²
        // with A = WALL_HEIGHT / (R_WALL - R_FLAT)² so y(R_WALL) = WALL_TOP.
        // dy/dr at R_FLAT is 0 — the slope on the flat side and the curve
        // side both meet at zero, hiding the transition completely.
        var R_FLAT = WALL_RADIUS * 0.45;        // ~3.6m flat near-field
        var R_WALL = WALL_RADIUS;
        var BOWL_RISE_K = WALL_HEIGHT / ((R_WALL - R_FLAT) * (R_WALL - R_FLAT));
        var RADIAL_SEGS = 96;
        var RING_COUNT  = 48;  // dense enough that Gouraud → fragment interp is smooth

        var bowlGeo = (function() {
            var positions = [];
            var indices = [];
            // Vertex 0 = centre at (0, floor, 0). Then RING_COUNT rings of
            // RADIAL_SEGS verts each, evenly stepped in r so the curve stays
            // smooth at high zoom.
            positions.push(0, WALL_BOTTOM, 0);
            for (var ri = 1; ri <= RING_COUNT; ri++) {
                var t = ri / RING_COUNT;
                var r = t * R_WALL;
                var y;
                if (r <= R_FLAT) {
                    y = WALL_BOTTOM;
                } else {
                    var dr = r - R_FLAT;
                    y = WALL_BOTTOM + BOWL_RISE_K * dr * dr;
                }
                for (var si = 0; si < RADIAL_SEGS; si++) {
                    var theta = (si / RADIAL_SEGS) * Math.PI * 2;
                    positions.push(Math.cos(theta) * r, y, Math.sin(theta) * r);
                }
            }

            // Triangulate: centre fan → first ring; then ring-strip pairs.
            for (var s0 = 0; s0 < RADIAL_SEGS; s0++) {
                var s1 = (s0 + 1) % RADIAL_SEGS;
                indices.push(0, 1 + s0, 1 + s1);
            }
            for (var ring = 0; ring < RING_COUNT - 1; ring++) {
                var aBase = 1 + ring * RADIAL_SEGS;
                var bBase = 1 + (ring + 1) * RADIAL_SEGS;
                for (var k = 0; k < RADIAL_SEGS; k++) {
                    var k1 = (k + 1) % RADIAL_SEGS;
                    indices.push(aBase + k, bBase + k, bBase + k1);
                    indices.push(aBase + k, bBase + k1, aBase + k1);
                }
            }

            var g = new THREE.BufferGeometry();
            g.setAttribute('position',
                new THREE.BufferAttribute(new Float32Array(positions), 3));
            g.setIndex(indices);
            return g;
        })();

        var bowlMat = new THREE.ShaderMaterial({
            uniforms: sharedUniforms(),
            vertexShader: [
                'varying vec3 vWorldPos;',
                'void main() {',
                '    vec4 wp = modelMatrix * vec4(position, 1.0);',
                '    vWorldPos = wp.xyz;',
                '    gl_Position = projectionMatrix * viewMatrix * wp;',
                '}'
            ].join('\n'),
            fragmentShader: [
                'precision mediump float;',
                SHARED_GLSL,
                'varying vec3 vWorldPos;',
                '',
                '#define R_FLAT  ' + R_FLAT.toFixed(3),
                '#define R_WALL  ' + R_WALL.toFixed(3),
                '#define WALL_BOTTOM ' + WALL_BOTTOM.toFixed(3),
                '#define WALL_TOP    ' + (WALL_BOTTOM + WALL_HEIGHT).toFixed(3),
                '',
                'void main() {',
                '    float radius = length(vWorldPos.xz);',
                '    float yNorm = clamp(',
                '        (vWorldPos.y - WALL_BOTTOM) / (WALL_TOP - WALL_BOTTOM),',
                '        0.0, 1.0);',
                '',
                '    // Body-shaped hole on the flat near-field. Half-extents come',
                '    // from the loaded GLBs bounding box (uniform).',
                '    float bodyX = abs(vWorldPos.x) / max(uBodyHalfX, 0.10);',
                '    float bodyZ = abs(vWorldPos.z) / max(uBodyHalfZ, 0.10);',
                '    float bodyR = max(bodyX, bodyZ);',
                '    float carHole = smoothstep(1.00, 1.25, bodyR);',
                '',
                '    // THREE-WAY HYBRID SAMPLING.',
                '    //',
                '    //   yNorm < 0.02   (flat floor)              → sampleGround (BEV IPM)',
                '    //   0.02..0.30     (floor→wall transition)   → sampleSpace where reachable, sampleSurround fallback',
                '    //   0.30..0.62     (mid-upper wall)           → sampleSurround (bearing)',
                '    //   0.62..0.95     (above horizon)            → crossfade to stylized sky',
                '    //',
                '    // The transition band is the critical one — production',
                '    // AVMs (Mercedes, Hyundai) keep IPM authoritative as far',
                '    // up the lower wall as the cams physically reach. Points',
                '    // 1-2m off the floor at r≈5-7m DO sit inside the side+rear',
                '    // cam fisheye cones, so sampleSpace returns a meaningful',
                '    // sample there; only when ALL four cams fail (corners +',
                '    // upper wall) do we fall back to bearing. That keeps the',
                '    // floor↔wall seam photometrically consistent — both sides',
                '    // are projecting the same world point through cam intrinsics.',
                '    // Earlier attempt used sampleSpace on the WHOLE curve, which',
                '    // black-voided front+right because upper-wall points are',
                '    // outside all cam cones. The reach test fixes that.',
                '    float curveW = smoothstep(0.02, 0.30, yNorm);',
                '    float skyW   = smoothstep(0.62, 0.95, yNorm);',
                '    // ipmW: how much we trust IPM in the transition band.',
                '    // Linear from 1 at yNorm=0.02 to 0 at yNorm=0.30 — past',
                '    // 0.30 we are fully bearing.',
                '    float ipmW = clamp(1.0 - smoothstep(0.02, 0.30, yNorm), 0.0, 1.0);',
                '',
                '    vec4 g = vec4(0.0);',
                '    vec4 sp = vec4(0.0);',
                '    vec4 srf = vec4(0.0);',
                '    if (curveW < 0.999) {',
                '        g = sampleGround(vWorldPos.xz);',
                '    }',
                '    if (ipmW > 0.001) {',
                '        sp = sampleSpace(vWorldPos);',
                '    }',
                '    // Bearing fallback runs whenever we are in the curve and not',
                '    // fully replaced by sky. Used both as "wall content" past',
                '    // ipmW=0 and as the IPM-fallback when cams cant reach.',
                '    if (curveW > 0.001 && skyW < 0.999) {',
                '        float bearing = atan(vWorldPos.x, -vWorldPos.z);',
                '        srf = sampleSurround(bearing, yNorm);',
                '    }',
                '    // Within the transition band, blend sampleSpace with',
                '    // sampleSurround based on IPM reachability (sp.a). When',
                '    // some cam reached the point, IPM wins; when none did,',
                '    // bearing fills the gap with no black holes.',
                '    float reach = smoothstep(0.05, 0.40, sp.a);',
                '    vec4 trans = mix(srf, sp, reach * ipmW);',
                '    // Now compose the wall. Where ipmW>0 (transition band)',
                '    // use the IPM-with-fallback; past it, pure bearing.',
                '    vec4 w = mix(srf, trans, ipmW);',
                '    vec4 cam = mix(g, w, curveW);',
                '',
                '    // Stylized sky for the very top of the bowl — fisheye above-',
                '    // horizon pixels are blown-out / car-roof bleed, so we hard-',
                '    // replace them rather than show garbage. The horizon colour',
                '    // is biased by the AVM mean luminance per quadrant: dusk in',
                '    // front of the car warms the front horizon, fluoro garage',
                '    // tints all four quadrants cool, sun overhead pushes the',
                '    // zenith toward the cam mean rather than a fixed slate. Same',
                '    // bearing remap used by sampleSurround so the cardinal',
                '    // directions line up with their cam.',
                '    float bearingSky = atan(vWorldPos.x, -vWorldPos.z);',
                '    float bSky = mod(bearingSky + 0.78540, 6.28318);',
                '    if (bSky < 0.0) bSky += 6.28318;',
                '    float virtIdxSky = bSky / 1.5708;',
                '    int idxFloorSky = int(mod(floor(virtIdxSky), 4.0));',
                '    int idxNextSky  = int(mod(floor(virtIdxSky) + 1.0, 4.0));',
                '    // uSkyTint is fed by the JS sampler in WORLD idx order',
                '    // (0=Front, 1=Right, 2=Rear, 3=Left). Bearing also walks',
                '    // world idx, so DO NOT remapIdx here — the swap/rotate',
                '    // knobs only affect texture-quadrant lookup in sampleAt,',
                '    // not the world-idx-keyed sky tint. Earlier draft had a',
                '    // double-remap that desynced sky tint from cam content',
                '    // whenever swap/rotate were non-default.',
                '    vec3 tintA = uSkyTint[0]; vec3 tintB = uSkyTint[0];',
                '    if (idxFloorSky == 1) tintA = uSkyTint[1]; else if (idxFloorSky == 2) tintA = uSkyTint[2]; else if (idxFloorSky == 3) tintA = uSkyTint[3];',
                '    if (idxNextSky == 1) tintB = uSkyTint[1]; else if (idxNextSky == 2) tintB = uSkyTint[2]; else if (idxNextSky == 3) tintB = uSkyTint[3];',
                '    float fracSky = virtIdxSky - floor(virtIdxSky);',
                '    vec3 tintBearing = mix(tintA, tintB, smoothstep(0.0, 1.0, fracSky));',
                '    // Mean luminance of the bearing tint — used to drive the',
                '    // overall sky brightness so a dim garage sky stays dim.',
                '    float tintLuma = dot(tintBearing, vec3(0.299, 0.587, 0.114));',
                '    // Hue weight = how saturated the cam content is (chroma',
                '    // dist from grey). Pure-grey scenes shouldnt push hue at all.',
                '    float chroma = length(tintBearing - vec3(tintLuma));',
                '    float hueW   = clamp(chroma * 4.0, 0.0, 0.8);',
                '    // Designed sky stays the structural component; tint adds',
                '    // scene context with a soft cap so it can never read as',
                '    // unfiltered cam pixels (which would defeat the gradient).',
                '    vec3 horizonSkyBase = vec3(0.045, 0.055, 0.070);',
                '    vec3 zenithSkyBase  = vec3(0.018, 0.022, 0.030);',
                '    vec3 horizonSky = mix(horizonSkyBase,',
                '                          horizonSkyBase * (0.6 + tintLuma * 1.8) + tintBearing * 0.05,',
                '                          hueW);',
                '    vec3 zenithSky  = mix(zenithSkyBase,',
                '                          zenithSkyBase * (0.7 + tintLuma * 1.4) + tintBearing * 0.02,',
                '                          hueW);',
                '    vec3 sky = mix(horizonSky, zenithSky, smoothstep(0.62, 1.0, yNorm));',
                '',
                '    // Compose: cam.a controls fade-to-bg in cropped/out-of-frame',
                '    // regions. Background colour is a dim blend of cam + sky so',
                '    // the fade never reads as a hard black plate.',
                '    vec3 dimmedCam = cam.rgb * 0.35;',
                '    vec3 fadeColor = mix(sky, dimmedCam, 0.55);',
                '    vec3 rgb = composeSurround(cam.rgb, cam.a, fadeColor);',
                '',
                '    // Crossfade cam content → sky toward the rim.',
                '    rgb = mix(rgb, sky, skyW);',
                '',
                '    // Subtle horizon glow draws the eye to the cam content band.',
                '    float horizonGlow = smoothstep(0.55, 0.62, yNorm) *',
                '                        smoothstep(0.72, 0.62, yNorm);',
                '    rgb += vec3(0.0, 0.06, 0.05) * horizonGlow * 0.25;',
                '',
                '    // Atmospheric haze toward the rim.',
                '    float haze = smoothstep(0.55, 0.95, yNorm);',
                '    vec3 hazeColor = vec3(0.06, 0.09, 0.10);',
                '    rgb = mix(rgb, hazeColor, haze * 0.25);',
                '',
                '    float alpha = mix(1.0, carHole, smoothstep(0.0, 0.005, yNorm));',
                '    if (alpha < 0.01) discard;',
                '    gl_FragColor = vec4(rgb, alpha);',
                '}'
            ].join('\n'),
            transparent: true,
            depthWrite: false,
            side: THREE.DoubleSide
        });

        var bowl = new THREE.Mesh(bowlGeo, bowlMat);
        bowl.renderOrder = -2;
        this.scene.add(bowl);
        // Stash on both legacy slots so stop3dView's cleanup (which checks
        // _skySphere AND _surroundDisc separately) finds and disposes it
        // exactly once. We null _surroundDisc so the second branch is a no-op.
        this._skySphere = bowl;
        this._surroundDisc = null;

        // Kick the AVM-derived sky-tint sampler. Reads a 2×2 downsample of
        // the live mosaic at ~1Hz so the procedural sky and HemisphereLight
        // pick up the actual scene colour palette (warm dusk, cool fluoro,
        // sun-overhead etc.) without trying to use raw fisheye pixels.
        this._startSkyTintSampler(bowlMat);
    },

    /**
     * Sample the AVM mosaic into a 4-pixel offscreen canvas at ~1Hz, parse
     * each pixel as the mean RGB of its source quadrant, and feed those
     * four vec3s into `uSkyTint[4]` on the bowl material plus a tinted
     * HemisphereLight on the scene. Cheap (one drawImage scaling 1280×960 →
     * 2×2, one getImageData of 4 px) and runs only while the bowl is up.
     *
     * Quadrant idx in the canvas (post-CanvasTexture flipY):
     *   (0,0) BL → Front       (1,0) BR → Right
     *   (0,1) TL → Rear        (1,1) TR → Left
     * Output array order matches sampleAt's WORLD idx (0=Front,1=Right,
     * 2=Rear,3=Left), which is also what the shader expects in uSkyTint.
     */
    _startSkyTintSampler: function(bowlMat) {
        var self = this;
        if (this._skyTintInterval) clearInterval(this._skyTintInterval);
        if (!this._skyTintCanvas) {
            this._skyTintCanvas = document.createElement('canvas');
            this._skyTintCanvas.width = 2;
            this._skyTintCanvas.height = 2;
        }
        var dst = this._skyTintCanvas;
        var dctx = dst.getContext('2d');

        function tick() {
            var src = self._3dCanvas;
            if (!src || !bowlMat || !bowlMat.uniforms || !bowlMat.uniforms.uSkyTint) return;
            try {
                // Downsample the full mosaic to 2×2; the browser does a
                // box-filter mean for us so each output pixel is the mean
                // colour of its quadrant.
                dctx.drawImage(src, 0, 0, dst.width, dst.height);
                var data = dctx.getImageData(0, 0, 2, 2).data;
                // ImageData order: row-major, top-down (y=0 is TOP).
                // Indices: [0]=TL, [1]=TR, [2]=BL, [3]=BR (each is 4 bytes).
                // After flipY in CanvasTexture, the on-screen mapping is:
                //   canvas-TL → Rear, canvas-TR → Left,
                //   canvas-BL → Front, canvas-BR → Right.
                // Map to sampleAt WORLD idx (0=F,1=R,2=Rear,3=L):
                function pixVec(off) {
                    return new THREE.Vector3(
                        data[off]     / 255,
                        data[off + 1] / 255,
                        data[off + 2] / 255
                    );
                }
                var rear  = pixVec(0);   // TL
                var left  = pixVec(4);   // TR
                var front = pixVec(8);   // BL
                var right = pixVec(12);  // BR
                var arr = bowlMat.uniforms.uSkyTint.value;
                arr[0].copy(front);
                arr[1].copy(right);
                arr[2].copy(rear);
                arr[3].copy(left);

                // Per-cam exposure match. Compute each quadrants luma, take
                // the MEDIAN as the target, divide each cams luma by the
                // target to get a per-cam gain, then clamp to [0.6, 1.6] so
                // a single pitch-black cam can't blow the others out.
                // Indexed in the same world idx order as uSkyTint.
                function luma(v) { return v.x * 0.299 + v.y * 0.587 + v.z * 0.114; }
                var lumaArr = [luma(front), luma(right), luma(rear), luma(left)];
                // Median of 4: average of the two middle values after sort.
                var sorted = lumaArr.slice().sort(function(a, b) { return a - b; });
                var median = (sorted[1] + sorted[2]) * 0.5;
                if (median < 0.02) median = 0.02;  // floor to avoid div-by-zero
                var gainsOut = bowlMat.uniforms.uCamGain.value;
                for (var qi = 0; qi < 4; qi++) {
                    var camL = lumaArr[qi] < 0.01 ? 0.01 : lumaArr[qi];
                    var g = median / camL;
                    if (g < 0.6) g = 0.6;
                    else if (g > 1.6) g = 1.6;
                    // Ease toward target rather than snap, to avoid 1-Hz
                    // luma flicker from cars passing or auto-exposure jumps.
                    gainsOut[qi] = gainsOut[qi] * 0.5 + g * 0.5;
                }

                // Drive the showroom HemisphereLight from the same sample so
                // the car's PBR ambient picks up scene context — rear+left+
                // front+right top-half mean for skyColor, body-frame floor
                // luma for groundColor. Stays subtle (mixed against the
                // showroom defaults) so the car never reads as pure cam-tint.
                if (self._sceneLights && self._sceneLights[0]) {
                    var hemi = self._sceneLights[0].light;
                    if (hemi && hemi.isHemisphereLight) {
                        // 4-quadrant mean as the "sky" colour for IBL ambient.
                        var avg = new THREE.Vector3();
                        avg.copy(front).add(right).add(rear).add(left).multiplyScalar(0.25);
                        // Blend against original sky colour 0x88aacc so a
                        // dark bowl doesn't kill car contrast entirely.
                        var origSky = new THREE.Color(0x88aacc);
                        var blended = new THREE.Color(
                            origSky.r * 0.55 + avg.x * 0.45,
                            origSky.g * 0.55 + avg.y * 0.45,
                            origSky.b * 0.55 + avg.z * 0.45
                        );
                        hemi.color.copy(blended);
                        // Ground is dimmer; scale by 0.4 so under-car pickup
                        // stays plausibly shadowed.
                        var origGround = new THREE.Color(0x222244);
                        var dimAvg = new THREE.Color(avg.x * 0.4, avg.y * 0.4, avg.z * 0.4);
                        hemi.groundColor.copy(origGround.lerp(dimAvg, 0.6));
                    }
                }
            } catch (e) {
                // Cross-origin, canvas not ready, etc. — silent recovery on
                // next tick; never let a sampling glitch kill the bowl.
            }
        }

        // Prime once so the first second isn't neutral grey, then 1Hz.
        tick();
        this._skyTintInterval = setInterval(tick, 1000);
    },

    _stopSkyTintSampler: function() {
        if (this._skyTintInterval) {
            clearInterval(this._skyTintInterval);
            this._skyTintInterval = null;
        }
        // Keep the canvas allocated; cheap to reuse on next start3dView.
    },

    // ==================== DEFAULT-VIEW DATA OVERLAYS ====================
    //
    // The default exterior view doubles as a live status board. Each overlay
    // (tyres, engine, coolant, oil, …) follows the same pattern:
    //   1. Anchor: world-space Vector3 derived from the car's bounding box,
    //      cached once in _cacheCarBounds() so it survives orbit/zoom and
    //      is independent of the specific GLB.
    //   2. Container: a DOM element inside .vc-viewport that holds the
    //      callout boxes. Hide rules in vehicle-control.css key on
    //      .vc-viewport[data-3d-on="true"] so all default-view overlays
    //      auto-disappear in 3D Surround mode.
    //   3. Per-frame projection: project anchors → screen px → set box
    //      left/top + leader-line endpoints. Called from animate() once
    //      per frame after the renderer.render() has already drawn.
    //   4. Per-poll content update: a fetchState() handler reads its slice
    //      of the API payload and writes text + a state attr (normal /
    //      warn / alert / muted) onto each box. CSS does the colouring.
    //
    // To add a new overlay (e.g. coolant): add anchors in _cacheCarBounds,
    // add a DOM container next to vcTyreOverlay in the HTML, write
    // _updateXxxOverlayPositions / updateXxxOverlay methods, and call them
    // from animate() and fetchState() respectively.
    //
    // ---- Tyre callouts -----------------------------------------------------

    // Static-layout approach. We tried per-frame 3D wheel projection but
    // the alignment is unreliable across BYD models, camera angles, and
    // the AndroidBridge scale bump. Instead the callouts are pinned to
    // fixed screen slots — front pair above the car render area, rear
    // pair below — with short decorative leader lines pointing inward
    // toward the general wheel zone. This trades spatial fidelity for
    // SOTA-grade visual stability: nothing jitters as the camera orbits.
    _cacheCarBounds: function() {
        // Mark "ready to lay out" — actual positioning is screen-space,
        // not model-space, so we don't need to compute world anchors.
        this._tyreLayoutReady = true;

        // Capture the model's world-space half-extents on the ground plane
        // so the bowl shader can cut a body-shaped hole in the disc that
        // matches whichever GLB is loaded (Seal/Tang/Han/Atto have different
        // footprints; the previous hardcoded 0.95 × 2.35 was Seal-only and
        // bled bodywork onto the disc on every other model).
        if (!this.carModel || typeof THREE === 'undefined') return;
        try {
            var box = new THREE.Box3().setFromObject(this.carModel);
            var size = box.getSize(new THREE.Vector3());
            // Half-extents on world XZ. Add a small margin so the bowl-side
            // body hole is slightly larger than the silhouette, hiding any
            // last-pixel mismatch between the GLB outline and the actual car
            // footprint as captured by the cams.
            var marginX = 0.10;  // ~10cm
            var marginZ = 0.15;  // ~15cm — front/back overhang varies more
            this._carHalfX = (size.x * 0.5) + marginX;
            this._carHalfZ = (size.z * 0.5) + marginZ;
        } catch (e) {
            this._carHalfX = null;
            this._carHalfZ = null;
        }
    },

    // Reusable scratch vectors so the per-frame projection allocates nothing.
    _tyreScratchVec: null,

    // Layout is now pure CSS (see vehicle-control.css — .vc-tyre-callout
     // pins itself to the appropriate corner of .vc-tyre-overlay, which
     // covers the visible viewport). The per-frame call from animate()
     // becomes a no-op so we never touch DOM layout properties on the
     // BYD WebView's hot path.
    _updateTyreCalloutPositions: function() { /* no-op — CSS handles it */ },

    // User-configured kPa limits from /api/vehicle/state (tyres.limits), kept
    // in sync by updateTyreCallouts. Defaults mirror UnifiedConfigManager so
    // the colouring is correct on the first paint, before any response lands,
    // and if the server omits the block.
    _tyreLimits: { frontLow: 234, frontHigh: 310, rearLow: 234, rearHigh: 310, criticalLow: 152 },

    /** Map raw BYD enums + raw kPa to a 3-tier visual-state scale:
     *    'alert'  → red     leak (airLeakState>=1) or kPa <= criticalLow
     *    'warn'   → orange  pressureState UNDER/OVER, or kPa outside the
     *                       configured [low, high] band for that axle
     *    'normal' → teal    in-band reading with no SDK warning
     *    'muted'  → grey    no signal / no data
     *  SDK warnings are authoritative, while the numeric limits catch a
     *  genuinely low/high reading even when a vehicle reports state=normal.
     *
     *  Compares in kPa, not PSI: kPa is what the TPMS actually reports and what
     *  the user's limits are stored in, so the corner colour can never disagree
     *  with the notification thresholds because of rounding.
     *
     *  @param corner  the per-corner object from tyres[fl|fr|rl|rr]
     *  @param isFront true for the front axle (fl/fr), which has its own band
     */
    _tyreStateToken: function(corner, isFront) {
        if (!corner || corner.available === false) return 'muted';
        if (corner.signalState === 1) return 'muted';
        if (corner.airLeakState && corner.airLeakState >= 1) return 'alert';
        // The numeric net is evaluated BEFORE the firmware enum, and the worst
        // of the two wins — mirroring the server's `level = max(enum, kPa)` in
        // BydDataCollector.evaluatePressureCorner. Order matters: a genuinely
        // deflated tyre normally ALSO trips the firmware under-pressure flag, so
        // an enum-first early return painted the most serious case orange while
        // the server sent a CRITICAL alert for it.
        var lim = this._tyreLimits;
        var low = isFront ? lim.frontLow : lim.rearLow;
        var high = isFront ? lim.frontHigh : lim.rearHigh;
        if (typeof corner.kPa === 'number' && corner.kPa > 0) {
            if (corner.kPa <= lim.criticalLow) return 'alert';
            if (corner.kPa < low || corner.kPa > high) return 'warn';
        }
        // In-band (or no reading): the enum can still assert a problem we can't
        // see numerically, and it stays authoritative for that.
        if (typeof corner.pressureState === 'number'
                && corner.pressureState >= 1) return 'warn';
        return 'normal';
    },

    _tyreStateLabel: function(corner, isFront) {
        if (!corner || corner.available === false) return BYD.i18n.t('vehicle.tyre_no_data');
        if (corner.signalState === 1) return BYD.i18n.t('vehicle.tyre_no_signal');
        if (corner.airLeakState === 2) return BYD.i18n.t('vehicle.tyre_fast_leak');
        if (corner.airLeakState === 1) return BYD.i18n.t('vehicle.tyre_slow_leak');
        if (corner.pressureState === 1) return BYD.i18n.t('vehicle.tyre_high');
        if (corner.pressureState === 2) return BYD.i18n.t('vehicle.tyre_low');
        // Firmware reports normal but the reading is outside the user's band —
        // name the direction so the LOW/HIGH word matches the warn colour the
        // token function just assigned. Without this the callout said "OK" in
        // orange, which read as a UI bug.
        //
        // The low test must use the SAME boundary as _tyreStateToken: it treats
        // kPa <= criticalLow as 'alert', and criticalLow is allowed to equal an
        // axle low, so a strict `< low` here left a red corner captioned "OK" at
        // exactly that value.
        var lim = this._tyreLimits;
        var low = isFront ? lim.frontLow : lim.rearLow;
        var high = isFront ? lim.frontHigh : lim.rearHigh;
        if (typeof corner.kPa === 'number' && corner.kPa > 0) {
            if (corner.kPa < low || corner.kPa <= lim.criticalLow) {
                return BYD.i18n.t('vehicle.tyre_low');
            }
            if (corner.kPa > high) return BYD.i18n.t('vehicle.tyre_high');
        }
        return BYD.i18n.t('vehicle.tyre_ok');
    },

    updateTyreCallouts: function(tyres) {
        if (!tyres) return;
        // Adopt the server's limits when present; otherwise keep the previous
        // (or default) set rather than reverting mid-session.
        if (tyres.limits) {
            var L = tyres.limits, cur = this._tyreLimits;
            this._tyreLimits = {
                frontLow:    typeof L.frontLow    === 'number' ? L.frontLow    : cur.frontLow,
                frontHigh:   typeof L.frontHigh   === 'number' ? L.frontHigh   : cur.frontHigh,
                rearLow:     typeof L.rearLow     === 'number' ? L.rearLow     : cur.rearLow,
                rearHigh:    typeof L.rearHigh    === 'number' ? L.rearHigh    : cur.rearHigh,
                criticalLow: typeof L.criticalLow === 'number' ? L.criticalLow : cur.criticalLow
            };
        }
        var corners = ['fl', 'fr', 'rl', 'rr'];
        for (var i = 0; i < corners.length; i++) {
            var key = corners[i];
            var data = tyres[key] || { available: false };
            var box = document.getElementById('tyre' + key.toUpperCase());
            if (!box) continue;

            // corners[] is [fl, fr, rl, rr] — the first two are the front axle.
            var isFront = i < 2;
            var state = this._tyreStateToken(data, isFront);
            var label = this._tyreStateLabel(data, isFront);
            box.setAttribute('data-state', state);

            var psiEl  = box.querySelector('.vc-tyre-psi-val');
            var unitEl = box.querySelector('.vc-tyre-psi-unit');
            var kpaEl  = box.querySelector('.vc-tyre-kpa');
            var tempEl = box.querySelector('.vc-tyre-temp-val');
            var tempBox = box.querySelector('.vc-tyre-temp');
            var stateEl = box.querySelector('.vc-tyre-state');

            // Unit-aware rendering via BYD.units (fed from /status.pressureUnit).
            // Guarded so a cached pre-pressure core.js falls back to the
            // historical PSI-primary / kPa-secondary layout.
            var U = (window.BYD && BYD.units && BYD.units.pressureLabel) ? BYD.units : null;
            var mode = U ? U.pressureMode : 'psi';
            if (unitEl) unitEl.textContent = U ? U.pressureLabel() : 'PSI';

            if (data.available && typeof data.psi === 'number') {
                // Big value in the user's display unit; the sub-line keeps a
                // second unit as a cross-check — kPa normally, PSI when the
                // user's primary IS kPa. All threshold comparisons above
                // stay kPa, so colouring is unit-independent.
                if (psiEl) {
                    psiEl.textContent = U ? U.pressureVal(data.kPa || 0)
                                          : data.psi.toFixed(1);
                }
                if (kpaEl) {
                    kpaEl.textContent = (mode === 'kpa')
                        ? data.psi.toFixed(1) + ' PSI'
                        : (data.kPa || 0) + ' kPa';
                }
            } else {
                if (psiEl)  psiEl.textContent  = '--';
                if (kpaEl)  kpaEl.textContent  = (mode === 'kpa') ? '-- PSI' : '-- kPa';
            }
            if (typeof data.temperatureC === 'number') {
                if (tempEl)  tempEl.textContent  = data.temperatureC;
                if (tempBox) tempBox.style.display = '';
            } else {
                if (tempEl)  tempEl.textContent  = '--';
                if (tempBox) tempBox.style.display = 'none';
            }
            if (stateEl) stateEl.textContent = label;
        }
    },

    // ==================== API HELPERS ====================

    apiPost: function(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : '{}'
        }).then(function(resp) {
            return resp.json();
        }).catch(function(e) {
            return { success: false, error: BYD.i18n.t('vehicle.network_error_msg', {message: e.message}) };
        });
    },

    toast: function(message, type) {
        var el = document.getElementById('vcToast');
        if (!el) return;
        if (message == null || message === '') {
            var tr = BYD.i18n && BYD.i18n.t ? BYD.i18n.t.bind(BYD.i18n) : null;
            if (type === 'success') {
                message = (tr && tr('toast.fallback_success')) || 'Done';
            } else if (type === 'error') {
                message = (tr && tr('toast.fallback_error')) || 'Something went wrong';
            } else {
                message = (tr && tr('toast.fallback_info')) || 'Done';
            }
        }
        el.textContent = message;
        el.className = 'vc-toast show ' + (type || 'info');
        clearTimeout(this._toastTimer);
        var toastEl = el;
        this._toastTimer = setTimeout(function() {
            toastEl.classList.remove('show');
        }, 2500);
    },

    /**
     * Render a toast from a routed-API response { success, path, latencyMs, message, outcome }.
     * Prefers the server-resolved `message` so cloud-required / rate-limited /
     * cloud-failed prompts surface in the right locale automatically. Falls
     * back to caller-supplied success / error labels if the server didn't
     * return a message (e.g., legacy endpoint).
     *
     * Appends a localized path badge ("· via cloud", "· cloud → direct connection")
     * on success so the user always knows which transport actually fired.
     */
    toastFromResult: function(result, fallbackOk, fallbackErr) {
        if (!result) return;
        var msg = result.message || (result.success ? fallbackOk : (result.error || fallbackErr));
        if (!msg) msg = result.success ? (fallbackOk || '') : (fallbackErr || '');
        if (result.success) {
            var badge = this.pathLabel(result.path);
            if (badge) msg = msg + ' · ' + badge;
        }
        var type;
        if (result.success) type = 'success';
        else if (result.outcome === 'auth_required') type = 'info';
        else if (result.outcome === 'rate_limited') type = 'info';
        else type = 'error';
        this.toast(msg, type);
    },

    /**
     * Map the server's `path` field to a localized human badge. Unknown /
     * "none" paths return empty so callers can skip the separator.
     */
    pathLabel: function(path) {
        switch (path) {
            case 'cloud': return BYD.i18n.t('vehicle_control.path_cloud');
            case 'local': return BYD.i18n.t('vehicle_control.path_local');
            case 'cloud-then-local': return BYD.i18n.t('vehicle_control.path_cloud_then_local');
            case 'local-then-cloud': return BYD.i18n.t('vehicle_control.path_local_then_cloud');
            default: return '';
        }
    }
};

// Boot when DOM is ready. This script is injected dynamically by
// vehicle-control.html's mode-gated loader, and dynamically injected
// scripts do NOT block DOMContentLoaded — it may already have fired by
// the time we execute, in which case boot immediately. (Also correct for
// any legacy page that still includes this file with a static tag.)
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() { VC.init(); });
} else {
    VC.init();
}
