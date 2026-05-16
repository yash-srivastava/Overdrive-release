/**
 * Vehicle Control — VFX Engine
 * Three.js car with GSAP energy-based animations
 * State sync with BYD vehicle APIs
 *
 * Compatibility: Chrome 58+ (BYD DiLink Android 7.1 WebView)
 * - No ES modules, no import maps, no optional chaining, no nullish coalescing
 * - Uses UMD globals: THREE, THREE.GLTFLoader, THREE.OrbitControls, gsap
 */

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
        trunkOpen: false,
        doors: { lf: 1, rf: 1, lr: 1, rr: 1, trunk: -1, hood: -1 },
        windows: { lf: 0, rf: 0, lr: 0, rr: 0, sunroof: 0, sunshade: 0 },
        lights: { dayTimeLight: false },
        adas: { speedLimitWarning: false },
        soc: 0,
        rangeKm: 0,
        cloudConfigured: false,
        acOn: false,
        acTemp: 22,
        acFan: 3,
        seatHeat: { 1: 0, 2: 0 },  // 0=off, 1=low, 2=high
        seatCool: { 1: 0, 2: 0 }
    },

    pollInterval: null,
    _toastTimer: null,
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
        // Default: Aurora White (converted to linear so it matches the rest
        // of the colour pipeline; see applyColor() for the rationale).
        this.baseColor = new THREE.Color(0xE8E8EC).convertSRGBToLinear();
        this.initThreeJS();
        this.initColorPicker();
        this.bindControls();
        this.startStateSync();
        this.checkCloudStatus();
        this.requestCloudLockRefresh();
        this.startCloudLockSync();
        this.animate();
        this.init3dButton();
        this.initCloudModal();

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
    },

    initThreeJS: function() {
        var self = this;

        this.scene = new THREE.Scene();

        // Pull camera further back on narrow screens so the car renders
        // smaller — leaves headroom on the canvas for the four tyre
        // callouts (and future engine/coolant/oil overlays) without them
        // colliding with the rendered body. Wider FOV on mobile too, so
        // the same body fits in less screen height.
        var isMobile = window.innerWidth < 768;
        var fov = isMobile ? 50 : 50;
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
        this.camera.position.set(isMobile ? 5.0 : 4, isMobile ? 3.0 : 2.5, isMobile ? 6.5 : 5);

        this.renderer = new THREE.WebGLRenderer({
            canvas: canvasEl,
            antialias: true,
            alpha: true
        });
        this.renderer.setSize(renderW, renderH, false);
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 3));
        this.renderer.setClearColor(0x0F0F12, 1);
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
    },

    addLighting: function() {
        // Environment lighting for PBR materials
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
        this.bodyPaintMeshes = [];
    },

    _loadModelFromPath: function(modelPath, gen) {
        var self = this;
        var loader = new THREE.GLTFLoader();

        // Draco decoder — the GLB uses Draco mesh compression.
        // Local path: assets/web/shared/vendor/draco/ (extracted to /data/local/tmp/web/shared/vendor/draco/).
        // We force the JS decoder (no WASM) for Chrome 58 compatibility and to avoid the
        // wasm MIME quirks on some BYD WebViews.
        var dracoLoader = new THREE.DRACOLoader();
        dracoLoader.setDecoderPath('../shared/vendor/draco/');
        dracoLoader.setDecoderConfig({ type: 'js' });
        loader.setDRACOLoader(dracoLoader);

        loader.load(
            modelPath,
            function(gltf) {
                if (gen !== self._loadGen) return; // a newer load has superseded this one
                self._modelLoadComplete = true;
                if (self._modelLoadTimeout) { clearTimeout(self._modelLoadTimeout); self._modelLoadTimeout = null; }
                self.carModel = gltf.scene;

                self.carModel.traverse(function(node) {
                    if (node.isMesh) {
                        // Identify body paint panels vs glass/chrome/rubber/interior
                        // Body paint: opaque, non-transparent, typically the largest colored surfaces
                        var mat = node.material;
                        var isBodyPaint = false;

                        if (mat && !mat.transparent && mat.opacity > 0.9) {
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

                var box = new THREE.Box3().setFromObject(self.carModel);
                var center = box.getCenter(new THREE.Vector3());
                self.carModel.position.sub(center);
                self.carModel.position.y += 0.1;

                // Slight bump on the Android WebView (BYD head unit) since
                // its effective canvas is smaller than mobile browsers.
                // Kept conservative (1.10) so the four tyre callouts and
                // the planned engine/coolant/oil overlays have room around
                // the rendered car without overlapping the body.
                if (window.AndroidBridge) {
                    self.carModel.scale.multiplyScalar(1.10);
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
        // Match the FOV used at init() — we don't shrink the FOV on mobile
        // anymore (the car was rendering too big and clipping into the
        // tyre callouts). 50° is a comfortable garage-floor look at all
        // screen widths.
        this.camera.fov = 50;
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

    animate: function() {
        var self = this;
        requestAnimationFrame(function() { self.animate(); });
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
            if (onProgress) onProgress(BYD.i18n.t('vehicle.downloading_model', {name: entry.name, pct: 0}));

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
                        onProgress(BYD.i18n.t('vehicle.downloading_model', {name: entry.name, pct: pct}));
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
            opt.textContent = m.name;
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
        if (panelId === 'panelWindows') panel.classList.add('vc-panel-tall');
        else panel.classList.remove('vc-panel-tall');
        this._activePanel = panelId;
    },

    /** Update tab dot indicators based on vehicle state */
    updateTabIndicators: function() {
        var tabs = document.querySelectorAll('.vc-tab');
        if (!tabs.length) return;

        // Security tab — has-active if locked (null = unknown, don't show)
        var secTab = tabs[0];
        if (secTab) {
            if (this.vehicleState.locked === true) secTab.classList.add('has-active');
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

        // Lock
        this.bindBtn('btnLock', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnLock', true);
            self.triggerLockVFX();
            self.apiPost('/api/vehicle/lock').then(function(result) {
                self.setPending('btnLock', false);
                if (result.success && result.commandSuccess) {
                    self.toast(BYD.i18n.t('vehicle.car_locked'), 'success');
                } else {
                    self.toast(result.error || BYD.i18n.t('vehicle.lock_failed'), 'error');
                }
            });
        });

        // Unlock
        this.bindBtn('btnUnlock', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnUnlock', true);
            self.triggerUnlockVFX();
            self.apiPost('/api/vehicle/unlock').then(function(result) {
                self.setPending('btnUnlock', false);
                if (result.success && result.commandSuccess) {
                    self.toast(BYD.i18n.t('vehicle.car_unlocked'), 'success');
                } else {
                    self.toast(result.error || BYD.i18n.t('vehicle.unlock_failed'), 'error');
                }
            });
        });

        // Trunk open — shows progress: Unlocking → Opening
        this.bindBtn('btnTrunkOpen', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnTrunkOpen', true);
            self.toast(BYD.i18n.t('vehicle.unlocking_car'), 'info');
            self.triggerUnlockVFX();
            self.apiPost('/api/vehicle/trunk', { action: 'open' }).then(function(result) {
                self.setPending('btnTrunkOpen', false);
                if (result.success) {
                    self.triggerTrunkVFX(true);
                    self.toast(BYD.i18n.t('vehicle.trunk_opening'), 'success');
                } else {
                    self.toast(result.error || BYD.i18n.t('vehicle.trunk_failed'), 'error');
                }
            });
        });

        // Trunk close — closes trunk via local HAL + locks car
        this.bindBtn('btnTrunkClose', function() {
            self.setPending('btnTrunkClose', true);
            self.toast(BYD.i18n.t('vehicle.closing_trunk'), 'info');
            self.triggerLockVFX();
            self.apiPost('/api/vehicle/trunk', { action: 'close' }).then(function(result) {
                self.setPending('btnTrunkClose', false);
                if (result.success) {
                    self.triggerTrunkVFX(false);
                    self.toast(BYD.i18n.t('vehicle.trunk_closing'), 'success');
                } else {
                    self.toast(result.error || BYD.i18n.t('vehicle.trunk_close_failed'), 'error');
                }
            });
        });

        // Flash lights
        this.bindBtn('btnFlash', function() {
            if (!self.requireCloud()) return;
            self.setPending('btnFlash', true);
            self.triggerFlashVFX();
            self.apiPost('/api/vehicle/flash').then(function(result) {
                self.setPending('btnFlash', false);
                if (result.success) self.toast(BYD.i18n.t('vehicle.lights_flashed'), 'info');
                else self.toast(result.error || BYD.i18n.t('vehicle.flash_failed'), 'error');
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
                            // VFX: only show direction if we know the current
                            // position; otherwise skip the animation (target
                            // alone doesn't tell us which way it'll move).
                            if (typeof current === 'number' && current >= 0) {
                                if (Math.abs(current - target) > 5) {
                                    self.triggerWindowVFX(area, target > current);
                                }
                            }
                            // Visually mark the chosen preset; live position
                            // tracking will reconcile this on the next state poll.
                            self.markWindowPreset(area, target);
                            self.apiPost('/api/vehicle/window',
                                { area: areaNum, targetPercent: target });
                        });
                    })(presets[pi]);
                }
            })(rows[ri]);
        }

        // All windows — only fully-open / fully-closed makes sense for "all"
        // (per-window % requires per-window polling, no SDK batch primitive).
        // Loop over the 4 side windows only — `area:0` does not control sunroof/sunshade.
        this.bindBtn('btnWinAllOpen', function() {
            for (var j = 0; j < 4; j++) self.triggerWindowVFX(areas[j], true);
            self.apiPost('/api/vehicle/window', { area: 0, command: 1 });
            self.toast(BYD.i18n.t('vehicle.windows_all_opening'), 'info');
        });
        this.bindBtn('btnWinAllClose', function() {
            for (var j = 0; j < 4; j++) self.triggerWindowVFX(areas[j], false);
            self.apiPost('/api/vehicle/window', { area: 0, command: 2 });
            self.toast(BYD.i18n.t('vehicle.windows_all_closing'), 'info');
        });

        // === LIGHTS CONTROLS ===
        this.bindBtn('btnDRL', function() {
            var enable = !(self.vehicleState.lights && self.vehicleState.lights.dayTimeLight);
            self.triggerSonarVFX(0, 0.6, 2, new THREE.Color(enable ? 0xFF6B35 : 0x1A1A1E));
            self.apiPost('/api/vehicle/lights', { target: 'dayTimeLight', enable: enable }).then(function(result) {
                if (result.success && result.commandSuccess) {
                    self.vehicleState.lights.dayTimeLight = result.enable;
                    self.updateLightsUI();
                    self.toast(BYD.i18n.t(result.enable ? 'vehicle.drl_enabled' : 'vehicle.drl_disabled'), 'info');
                } else {
                    self.toast(result.error || BYD.i18n.t('vehicle.drl_failed'), 'error');
                }
            });
        });

        // === ADAS CONTROLS ===
        this.bindBtn('btnSLW', function() {
            var enable = !(self.vehicleState.adas && self.vehicleState.adas.speedLimitWarning);
            self.apiPost('/api/vehicle/adas', { target: 'speedLimitWarning', enable: enable }).then(function(result) {
                if (result.success && result.commandSuccess) {
                    self.vehicleState.adas.speedLimitWarning = result.enable;
                    self.updateAdasUI();
                    self.toast(BYD.i18n.t(result.enable ? 'vehicle.slw_enabled' : 'vehicle.slw_disabled'), 'info');
                } else {
                    self.toast(result.error || BYD.i18n.t('vehicle.slw_failed'), 'error');
                }
            });
        });

        // === CLIMATE CONTROLS ===
        this.bindBtn('btnAcOn', function() {
            // Blue burst from cabin center
            self.triggerSonarVFX(0, 0.6, 0.2, new THREE.Color(0x38BDF8));
            self.triggerSonarVFX(0, 0.6, -0.2, new THREE.Color(0x38BDF8));
            self.flashBodyColor(new THREE.Color(0x38BDF8), 0.1, 2, null);
            self.apiPost('/api/vehicle/climate', { action: 'power_on' }).then(function(r) {
                if (r.success && r.commandSuccess !== false) { self.vehicleState.acOn = true; self.updateClimateUI(); self.toast(BYD.i18n.t('vehicle.ac_on'), 'success'); }
                else { self.toast(r.error || BYD.i18n.t('vehicle.ac_command_failed'), 'error'); }
            });
        });
        this.bindBtn('btnAcOff', function() {
            self.flashBodyColor(new THREE.Color(0x71717A), 0.15, 1, null);
            self.apiPost('/api/vehicle/climate', { action: 'power_off' }).then(function(r) {
                if (r.success && r.commandSuccess !== false) { self.vehicleState.acOn = false; self.updateClimateUI(); self.toast(BYD.i18n.t('vehicle.ac_off'), 'info'); }
                else { self.toast(r.error || BYD.i18n.t('vehicle.ac_command_failed'), 'error'); }
            });
        });
        this.bindBtn('btnTempUp', function() {
            var t = Math.min(33, self.vehicleState.acTemp + 1);
            self.vehicleState.acTemp = t;
            self.updateClimateUI();
            // Warm pulse for temp up
            self.triggerSonarVFX(0, 0.6, 0, new THREE.Color(t > 25 ? 0xFF6B35 : 0x38BDF8));
            self.apiPost('/api/vehicle/climate', { action: 'set_temp', zone: 1, temp: t });
        });
        this.bindBtn('btnTempDown', function() {
            var t = Math.max(17, self.vehicleState.acTemp - 1);
            self.vehicleState.acTemp = t;
            self.updateClimateUI();
            // Cool pulse for temp down
            self.triggerSonarVFX(0, 0.6, 0, new THREE.Color(t < 20 ? 0x38BDF8 : 0x00D4AA));
            self.apiPost('/api/vehicle/climate', { action: 'set_temp', zone: 1, temp: t });
        });
        this.bindBtn('btnFanUp', function() {
            var f = Math.min(7, self.vehicleState.acFan + 1);
            self.vehicleState.acFan = f;
            self.updateClimateUI();
            // Multiple sonar rings for higher fan — more rings = more wind
            for (var fi = 0; fi < Math.min(f, 3); fi++) {
                (function(delay) {
                    setTimeout(function() { self.triggerSonarVFX(0, 0.5, 0.3 - delay * 0.3, new THREE.Color(0x00D4AA)); }, delay * 80);
                })(fi);
            }
            self.apiPost('/api/vehicle/climate', { action: 'set_fan', fan: f });
        });
        this.bindBtn('btnFanDown', function() {
            var f = Math.max(1, self.vehicleState.acFan - 1);
            self.vehicleState.acFan = f;
            self.updateClimateUI();
            self.triggerSonarVFX(0, 0.5, 0, new THREE.Color(0x52525B));
            self.apiPost('/api/vehicle/climate', { action: 'set_fan', fan: f });
        });

        // Seat heating — cycles 0→1→2→0
        var seatPositions = {
            1: { x: 0.5, y: 0.4, z: 0.2 },   // driver
            2: { x: -0.5, y: 0.4, z: 0.2 }    // passenger
        };
        for (var si = 1; si <= 2; si++) {
            (function(pos) {
                self.bindBtn('btnSeatHeat' + pos, function() {
                    var cur = self.vehicleState.seatHeat[pos] || 0;
                    var next = (cur + 1) % 3;
                    self.vehicleState.seatHeat[pos] = next;
                    self.vehicleState.seatCool[pos] = 0;
                    self.updateSeatUI(pos);
                    self.updateSeatGlows();
                    // Heat VFX — warm sonar at seat, intensity scales with level
                    var sp = seatPositions[pos];
                    if (next > 0) {
                        var heatColor = next === 2 ? 0xFF4500 : 0xFF8C00;
                        self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(heatColor));
                        if (next === 2) {
                            setTimeout(function() { self.triggerSonarVFX(sp.x, sp.y + 0.2, sp.z, new THREE.Color(0xFF4500)); }, 120);
                        }
                        self.toast(BYD.i18n.t('vehicle.seat_heat_level', {level: next === 1 ? BYD.i18n.t('vehicle.level_low') : BYD.i18n.t('vehicle.level_high')}), 'success');
                    } else {
                        self.toast(BYD.i18n.t('vehicle.seat_heat_off'), 'info');
                    }
                    self.apiPost('/api/vehicle/seat', { action: 'heating', position: pos, level: next });
                });
                self.bindBtn('btnSeatCool' + pos, function() {
                    var cur = self.vehicleState.seatCool[pos] || 0;
                    var next = (cur + 1) % 3;
                    self.vehicleState.seatCool[pos] = next;
                    self.vehicleState.seatHeat[pos] = 0;
                    self.updateSeatUI(pos);
                    self.updateSeatGlows();
                    // Cool VFX — blue sonar at seat
                    var sp = seatPositions[pos];
                    if (next > 0) {
                        var coolColor = next === 2 ? 0x00BFFF : 0x87CEEB;
                        self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(coolColor));
                        if (next === 2) {
                            setTimeout(function() { self.triggerSonarVFX(sp.x, sp.y + 0.2, sp.z, new THREE.Color(0x00BFFF)); }, 120);
                        }
                        self.toast(BYD.i18n.t('vehicle.seat_cool_level', {level: next === 1 ? BYD.i18n.t('vehicle.level_low') : BYD.i18n.t('vehicle.level_high')}), 'success');
                    } else {
                        self.toast(BYD.i18n.t('vehicle.seat_cool_off'), 'info');
                    }
                    self.apiPost('/api/vehicle/seat', { action: 'ventilation', position: pos, level: next });
                });
            })(si);
        }

        // Seat memory positions — driver-side recall (BYD SDK supports up to 2 stored positions).
        for (var smi = 1; smi <= 2; smi++) {
            (function(pos) {
                self.bindBtn('btnSeatMemory' + pos, function() {
                    // Blue sonar at driver seat — recall is driver-only.
                    var sp = seatPositions[1];
                    self.triggerSonarVFX(sp.x, sp.y, sp.z, new THREE.Color(0x00BFFF));
                    self.toast(BYD.i18n.t('vehicle.seat_memory_position', {pos: pos}), 'success');
                    self.apiPost('/api/vehicle/seat', { action: 'position', position: pos });
                });
            })(smi);
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

    // ==================== STATE SYNC ====================

    startStateSync: function() {
        var self = this;
        this.fetchState();
        this.pollInterval = setInterval(function() { self.fetchState(); }, 3000);
    },

    fetchState: function() {
        var self = this;
        fetch('/api/vehicle/state').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            if (!data.success) return;

            var wasLocked = self.vehicleState.locked;

            // Doors (lock status: 1=locked, 2=unlocked)
            if (data.doors) {
                var d = data.doors;
                self.vehicleState.doors = {
                    lf: d.lf || -1, rf: d.rf || -1,
                    lr: d.lr || -1, rr: d.rr || -1,
                    trunk: d.trunk || -1, hood: d.hood || -1
                };
                var overall = (d.overall !== undefined && d.overall !== null) ? d.overall : -1;
                if (overall === 1) {
                    self.vehicleState.locked = true;
                } else if (overall === 2) {
                    self.vehicleState.locked = false;
                } else {
                    // Unknown from CAN bus — keep last known state if we had one
                    // Only set to null if we never received a valid state
                    if (wasLocked === null) self.vehicleState.locked = null;
                    // else keep wasLocked (persist last known)
                }
            }

            // Windows
            if (data.windows) {
                var w = data.windows;
                self.vehicleState.windows = {
                    lf: w.lf >= 0 ? w.lf : 0,
                    rf: w.rf >= 0 ? w.rf : 0,
                    lr: w.lr >= 0 ? w.lr : 0,
                    rr: w.rr >= 0 ? w.rr : 0,
                    sunroof: w.sunroof >= 0 ? w.sunroof : 0,
                    sunshade: w.sunshade >= 0 ? w.sunshade : 0
                };
            }

            // Battery
            if (data.battery) {
                self.vehicleState.soc = data.battery.soc || 0;
                self.vehicleState.rangeKm = data.battery.rangeKm || data.battery.bodyworkRangeKm || 0;
            }

            // Climate
            if (data.climate) {
                if (data.climate.acOn !== undefined) self.vehicleState.acOn = data.climate.acOn;
                if (data.climate.fanLevel !== undefined && data.climate.fanLevel >= 1 && data.climate.fanLevel <= 7) {
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

            // Update UI
            self.updateHUD();
            self.updateWindowBars();
            self.updateDoorIndicators();
            self.updateTrunkIndicator();
            self.updateWindowGlows();
            self.updateClimateUI();
            self.updateSeatGlows();
            self.updateTabIndicators();
            self.updateLightsUI();
            self.updateAdasUI();

        }).catch(function(e) {
            console.warn('[VC] State fetch error:', e);
        });
    },

    checkCloudStatus: function() {
        var self = this;
        fetch('/api/vehicle/cloud-status').then(function(resp) {
            return resp.json();
        }).then(function(data) {
            self.vehicleState.cloudConfigured = data.configured && data.verified;
            self.updateCloudIndicator();
        }).catch(function(e) {
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

            // Prefer cloud lock state when CAN bus didn't give us a valid one.
            // CAN bus sets self.vehicleState.locked = true/false; null = no
            // valid reading yet. We only override null — if CAN said locked
            // or unlocked, trust it (it's a few hundred ms fresh vs MQTT's
            // potentially-minutes-old snapshot).
            var canIsAuthoritative = self.vehicleState.locked === true || self.vehicleState.locked === false;
            if (!canIsAuthoritative) {
                if (s.lockState === 'locked') {
                    self.vehicleState.locked = true;
                    self.updateHUD();
                    self.updateDoorIndicators();
                    self.updateTabIndicators();
                } else if (s.lockState === 'unlocked') {
                    self.vehicleState.locked = false;
                    self.updateHUD();
                    self.updateDoorIndicators();
                    self.updateTabIndicators();
                }
            }

            // If the response is stale and CAN didn't give us a value,
            // schedule one follow-up to pick up the result of the server's
            // background REST refresh. Skipped if this is itself a follow-up
            // call (avoids loops on persistently stale data).
            var isStale = s.lockState === 'unknown'
                    || s.lastMessageAge === -1
                    || (typeof s.lastMessageAge === 'number' && s.lastMessageAge > self.STALE_RESPONSE_AGE_S);
            if (!_isFollowup && isStale && !canIsAuthoritative) {
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

    updateHUD: function() {
        var socEl = document.getElementById('socValue');
        if (socEl) socEl.textContent = Math.round(this.vehicleState.soc) + '%';

        var socFill = document.getElementById('socFill');
        if (socFill) socFill.style.width = Math.min(100, Math.max(0, this.vehicleState.soc)) + '%';

        var rangeEl = document.getElementById('rangeValue');
        if (rangeEl) rangeEl.textContent = Math.round(this.vehicleState.rangeKm) + ' km';

        this.updateLockUI(this.vehicleState.locked);
    },

    updateLockUI: function(locked) {
        var lockBtn = document.getElementById('btnLock');
        var unlockBtn = document.getElementById('btnUnlock');
        var lockStatus = document.getElementById('lockStatus');

        // locked can be true, false, or null (unknown)
        if (lockBtn) { if (locked === true) lockBtn.classList.add('on'); else lockBtn.classList.remove('on'); }
        if (unlockBtn) { if (locked === false) unlockBtn.classList.add('on'); else unlockBtn.classList.remove('on'); }
        if (lockStatus) {
            lockStatus.textContent = locked === true ? BYD.i18n.t('vehicle.locked') : (locked === false ? BYD.i18n.t('vehicle.unlocked') : BYD.i18n.t('common.unknown'));
            var dot = lockStatus.previousElementSibling;
            if (dot) {
                dot.className = 'dot ' + (locked === true ? 'green' : (locked === false ? 'amber' : 'grey'));
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

    /** Update persistent glow for trunk open state */
    updateTrunkIndicator: function() {
        // doorLockStatus[4] = trunk: 1=locked/closed, 2=unlocked/open
        var trunkVal = -1;
        if (this.vehicleState.doors && this.vehicleState.doors.trunk !== undefined) {
            trunkVal = this.vehicleState.doors.trunk;
        }
        if (trunkVal === 2) {
            this.setStateGlow('trunk', { x: 0, y: 0.8, z: -2.2 }, 0x00D4AA); // green glow
        } else {
            this.removeStateGlow('trunk');
        }
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
        if (this.vehicleState.cloudConfigured) {
            if (dot) dot.className = 'dot green';
            if (textEl) textEl.textContent = BYD.i18n.t('vehicle.cloud_connected');
        } else {
            if (dot) dot.className = 'dot red';
            if (textEl) textEl.textContent = BYD.i18n.t('vehicle.cloud_not_configured');
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

    updateSeatUI: function(pos) {
        var heatBtn = document.getElementById('btnSeatHeat' + pos);
        var coolBtn = document.getElementById('btnSeatCool' + pos);
        var heatLvl = this.vehicleState.seatHeat[pos] || 0;
        var coolLvl = this.vehicleState.seatCool[pos] || 0;

        if (heatBtn) { if (heatLvl > 0) heatBtn.classList.add('on'); else heatBtn.classList.remove('on'); }
        if (coolBtn) { if (coolLvl > 0) coolBtn.classList.add('on'); else coolBtn.classList.remove('on'); }
    },

    updateLightsUI: function() {
        var btnDRL = document.getElementById('btnDRL');
        var on = !!(this.vehicleState.lights && this.vehicleState.lights.dayTimeLight);
        if (btnDRL) { if (on) btnDRL.classList.add('on'); else btnDRL.classList.remove('on'); }
    },

    updateAdasUI: function() {
        var btnSLW = document.getElementById('btnSLW');
        var on = !!(this.vehicleState.adas && this.vehicleState.adas.speedLimitWarning);
        if (btnSLW) { if (on) btnSLW.classList.add('on'); else btnSLW.classList.remove('on'); }
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
            var heatLvl = this.vehicleState.seatHeat[pos] || 0;
            var coolLvl = this.vehicleState.seatCool[pos] || 0;
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
     * Surround geometry: cylinder wall + ground disc — the "old" production
     * layout the user confirmed reads cleanly (rear cam looked correct here).
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
            '    // Sum confidence-weighted samples from all 4 cams. This gives',
            '    // a soft Voronoi-style blend in the cam overlap regions so',
            '    // seams disappear without a hard partition.',
            '    vec4 s0 = sampleGroundFromCam(0, ground);',
            '    vec4 s1 = sampleGroundFromCam(1, ground);',
            '    vec4 s2 = sampleGroundFromCam(2, ground);',
            '    vec4 s3 = sampleGroundFromCam(3, ground);',
            '    float wsum = s0.a + s1.a + s2.a + s3.a;',
            '    if (wsum < 1e-3) return vec4(0.0);',
            '    vec3 rgb = (s0.rgb * s0.a + s1.rgb * s1.a +',
            '                s2.rgb * s2.a + s3.rgb * s3.a) / wsum;',
            '    return vec4(rgb, clamp(wsum, 0.0, 1.0));',
            '}'
        ].join('\n');

        // Shared uniforms for both passes (wall + disc). Each pass gets its
        // own array copy via .slice() — Three.js compiles the uniform-array
        // slot independently per material, and we recreate both materials
        // together on every start3dView, so this stays in sync.
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
                uCamPosZ:       { value: this._3dCamPosZ.slice() }
            };
        }.bind(this);

        // ── Cylindrical wall ────────────────────────────────────────────
        var wallGeo = new THREE.CylinderGeometry(
            WALL_RADIUS, WALL_RADIUS, WALL_HEIGHT, 96, 1, true);
        wallGeo.translate(0, WALL_BOTTOM + WALL_HEIGHT / 2, 0);

        var wallMat = new THREE.ShaderMaterial({
            uniforms: sharedUniforms(),
            vertexShader: [
                'varying vec3 vWorldPos;',
                'varying float vYNorm;',
                'void main() {',
                '    vec4 wp = modelMatrix * vec4(position, 1.0);',
                '    vWorldPos = wp.xyz;',
                '    vYNorm = clamp((position.y - (' + WALL_BOTTOM.toFixed(2) + ')) / ' + WALL_HEIGHT.toFixed(2) + ', 0.0, 1.0);',
                '    gl_Position = projectionMatrix * viewMatrix * wp;',
                '}'
            ].join('\n'),
            fragmentShader: [
                'precision mediump float;',
                SHARED_GLSL,
                'varying vec3 vWorldPos;',
                'varying float vYNorm;',
                'void main() {',
                '    float bearing = atan(vWorldPos.x, -vWorldPos.z);',
                '    vec4 cam = sampleSurround(bearing, vYNorm);',
                '',
                '    vec3 horizon = vec3(0.04, 0.10, 0.11);',
                '    vec3 zenith  = vec3(0.01, 0.02, 0.03);',
                '    vec3 sky = mix(horizon, zenith, smoothstep(0.75, 1.0, vYNorm));',
                '    vec3 baseBg = mix(vec3(0.04, 0.04, 0.05), sky,',
                '                      smoothstep(0.0, 0.4, vYNorm));',
                '',
                '    // Cropped/out-of-frame fade — blend toward a *darkened*',
                '    // version of the cam itself so the crop band reads as a',
                '    // soft dimming of the image, not a black plate.',
                '    vec3 dimmedCam = cam.rgb * 0.35;',
                '    vec3 fadeColor = mix(baseBg, dimmedCam, 0.6);',
                '    vec3 rgb = composeSurround(cam.rgb, cam.a, fadeColor);',
                '',
                '    // Upper bowl dissolves to sky regardless of crop.',
                '    float skyFade = smoothstep(0.65, 0.95, vYNorm);',
                '    rgb = mix(rgb, sky, skyFade);',
                '',
                '    float horizonGlow = smoothstep(0.55, 0.62, vYNorm) *',
                '                        smoothstep(0.72, 0.62, vYNorm);',
                '    rgb += vec3(0.0, 0.06, 0.05) * horizonGlow * 0.25;',
                '',
                '    float groundFade = smoothstep(0.05, 0.0, vYNorm);',
                '    rgb = mix(rgb, vec3(0.04, 0.04, 0.05), groundFade * 0.6);',
                '',
                '    gl_FragColor = vec4(rgb, 1.0);',
                '}'
            ].join('\n'),
            side: THREE.BackSide,
            depthWrite: false
        });
        var wall = new THREE.Mesh(wallGeo, wallMat);
        wall.renderOrder = -2;
        this.scene.add(wall);
        this._skySphere = wall;

        // ── Ground disc ─────────────────────────────────────────────────
        // True SOTA AVM uses a flat near-field + curved far-field. The wall
        // shader above only paints the horizon and sky (the cropBottom values
        // were just bumped to hide the bodywork band of each fisheye), and
        // this disc fills everything from under the bumpers out to the bowl
        // radius via geometric IPM (sampleGround in SHARED_GLSL).
        //
        // The fragment shader receives world-space (x, z) directly — no UV
        // assumption, no quadrant remap on the wall side — so each ground
        // pixel pulls from whichever cam(s) actually saw that point. Multiple
        // cams contribute via confidence-weighted blend so the cardinal seams
        // (front/right corner etc.) dissolve smoothly.
        var DISC_SIZE = WALL_RADIUS * 2.0;
        var discGeo = new THREE.PlaneGeometry(DISC_SIZE, DISC_SIZE, 1, 1);

        var discMat = new THREE.ShaderMaterial({
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
                'void main() {',
                '    // Ground point in world XZ. The plane is rotated -90° on X',
                '    // below, so position.xy in object space maps to (x, z) in world.',
                '    vec2 ground = vWorldPos.xz;',
                '    float radius = length(ground);',
                '',
                '    // Cut a body-shaped hole under the car so we never try to',
                '    // invent pixels the cams cant physically see — the body',
                '    // occludes the ground inside its own footprint, and IPM',
                '    // would otherwise paint stretched bodywork onto the disc.',
                '    // Half-extents: ~2.35m fore/aft, ~0.95m lateral (BYD Seal).',
                '    float bodyX = abs(ground.x) / 0.95;',
                '    float bodyZ = abs(ground.y) / 2.35;',
                '    float bodyR = max(bodyX, bodyZ);',  // chebyshev / rounded box
                '    float carHole = smoothstep(1.00, 1.25, bodyR);',
                '',
                '    // Fade the disc out as it approaches the wall radius so the',
                '    // disc/wall seam is a soft cross-fade rather than a hard ring.',
                '    float edgeFade = 1.0 - smoothstep(' +
                    (WALL_RADIUS * 0.85).toFixed(2) + ', ' +
                    (WALL_RADIUS * 0.98).toFixed(2) + ', radius);',
                '',
                '    vec4 g = sampleGround(ground);',
                '    float alpha = g.a * carHole * edgeFade;',
                '',
                '    if (alpha < 0.01) discard;',
                '',
                '    // Subtle vignette toward the disc edge so far-field IPM',
                '    // distortion (which gets stretchy near the horizon) reads',
                '    // as atmospheric haze instead of broken geometry.',
                '    float haze = smoothstep(' +
                    (WALL_RADIUS * 0.55).toFixed(2) + ', ' +
                    (WALL_RADIUS * 0.95).toFixed(2) + ', radius);',
                '    vec3 hazeColor = vec3(0.06, 0.09, 0.10);',
                '    vec3 rgb = mix(g.rgb, hazeColor, haze * 0.35);',
                '',
                '    gl_FragColor = vec4(rgb, alpha);',
                '}'
            ].join('\n'),
            transparent: true,
            depthWrite: false,
            side: THREE.DoubleSide
        });

        var disc = new THREE.Mesh(discGeo, discMat);
        disc.rotation.x = -Math.PI / 2;
        disc.position.y = WALL_BOTTOM + 0.001;  // a hair above the wall floor
        disc.renderOrder = -1;                   // after wall (-2), before car (0)
        this.scene.add(disc);
        this._surroundDisc = disc;
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
    },

    // Reusable scratch vectors so the per-frame projection allocates nothing.
    _tyreScratchVec: null,

    // Layout is now pure CSS (see vehicle-control.css — .vc-tyre-callout
     // pins itself to the appropriate corner of .vc-tyre-overlay, which
     // covers the visible viewport). The per-frame call from animate()
     // becomes a no-op so we never touch DOM layout properties on the
     // BYD WebView's hot path.
    _updateTyreCalloutPositions: function() { /* no-op — CSS handles it */ },

    /** Map raw BYD enums + raw PSI to a 3-tier visual-state scale:
     *    'alert'  → red     leak (airLeakState>=1) or PSI < 22 (deflated)
     *    'warn'   → orange  pressureState UNDER/OVER, or PSI < 34, or PSI > 45
     *    'normal' → green   34-45 PSI, no leak, signal OK
     *    'muted'  → grey    no signal / no data
     *  SDK enums are checked first so we still flag alert/warn when the
     *  TPMS itself has detected a problem even if the raw PSI looks fine.
     */
    _tyreStateToken: function(corner) {
        if (!corner || corner.available === false) return 'muted';
        if (corner.signalState === 1) return 'muted';
        if (corner.airLeakState && corner.airLeakState >= 1) return 'alert';
        if (corner.pressureState && corner.pressureState >= 1) return 'warn';
        if (typeof corner.psi === 'number') {
            if (corner.psi < 22) return 'alert';
            if (corner.psi < 34 || corner.psi > 45) return 'warn';
        }
        return 'normal';
    },

    _tyreStateLabel: function(corner) {
        if (!corner || corner.available === false) return BYD.i18n.t('vehicle.tyre_no_data');
        if (corner.signalState === 1) return BYD.i18n.t('vehicle.tyre_no_signal');
        if (corner.airLeakState === 2) return BYD.i18n.t('vehicle.tyre_fast_leak');
        if (corner.airLeakState === 1) return BYD.i18n.t('vehicle.tyre_slow_leak');
        if (corner.pressureState === 1) return BYD.i18n.t('vehicle.tyre_low');
        if (corner.pressureState === 2) return BYD.i18n.t('vehicle.tyre_high');
        return BYD.i18n.t('vehicle.tyre_ok');
    },

    updateTyreCallouts: function(tyres) {
        if (!tyres) return;
        var corners = ['fl', 'fr', 'rl', 'rr'];
        for (var i = 0; i < corners.length; i++) {
            var key = corners[i];
            var data = tyres[key] || { available: false };
            var box = document.getElementById('tyre' + key.toUpperCase());
            if (!box) continue;

            var state = this._tyreStateToken(data);
            var label = this._tyreStateLabel(data);
            box.setAttribute('data-state', state);

            var psiEl  = box.querySelector('.vc-tyre-psi-val');
            var kpaEl  = box.querySelector('.vc-tyre-kpa');
            var tempEl = box.querySelector('.vc-tyre-temp-val');
            var tempBox = box.querySelector('.vc-tyre-temp');
            var stateEl = box.querySelector('.vc-tyre-state');

            if (data.available && typeof data.psi === 'number') {
                // Server returns PSI to one decimal place. Display kPa next
                // to it so a user with metric calibration in mind can still
                // cross-check.
                if (psiEl)  psiEl.textContent  = data.psi.toFixed(1);
                if (kpaEl)  kpaEl.textContent  = (data.kPa || 0) + ' kPa';
            } else {
                if (psiEl)  psiEl.textContent  = '--';
                if (kpaEl)  kpaEl.textContent  = '-- kPa';
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
        el.textContent = message;
        el.className = 'vc-toast show ' + (type || 'info');
        clearTimeout(this._toastTimer);
        var toastEl = el;
        this._toastTimer = setTimeout(function() {
            toastEl.classList.remove('show');
        }, 2500);
    }
};

// Boot when DOM is ready
document.addEventListener('DOMContentLoaded', function() { VC.init(); });
