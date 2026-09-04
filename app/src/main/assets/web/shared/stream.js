/**
 * BYD Champ - WebSocket H.264 Stream Module
 * 
 * Features:
 * - SOTA WebCodecs decoder (hardware GPU, sub-100ms latency)
 * - Fallback: JMuxer (MSE/GPU) > Broadway (CPU)
 * - Page Visibility API: Pause streaming when backgrounded
 * - Latency Killer: Auto-jump to live if buffer builds up
 * - Camera selector UI before streaming starts
 * 
 * Decoder Priority:
 * 1. WebCodecs (iOS 16.4+, Chrome, Edge) - Native hardware, instant latency
 * 2. JMuxer (Android/PC with MSE) - GPU via Media Source Extensions
 * 3. Broadway (Fallback) - CPU/WebGL software decoding
 */

window.BYD = window.BYD || {};

BYD.stream = {
    // State
    sotaPlayer: null,      // WebCodecs player (SOTA)
    jmuxer: null,          // JMuxer fallback
    broadwayPlayer: null,  // Broadway fallback
    ws: null,
    isFullscreen: false,
    currentViewMode: -1, // -1 = not selected
    // Index aligns with /api/stream/view/{n}: 0=Mosaic, 1=Front, 2=Right,
    // 3=Rear, 4=Left, 5=Raw (legacy debug pano strip), 6=OEM Dashcam.
    // _viewModeName(mode) resolves to the localized label via i18n
    // (stream.view_name_<n>) at toast/label-set time so a language
    // switch doesn't strand a stale English string in the UI. The
    // English literals live only in en.json.
    _viewModeName(mode) {
        return BYD.i18n.t('stream.view_name_' + mode);
    },
    reconnectTimer: null,
    frameCount: 0,
    lastFrameTime: 0,
    // True only after the current connection/view has produced a frame.
    // Socket-open alone is not enough: the BYD WebView reuses an existing
    // WebSocket when switching cameras, so no second onopen event fires.
    _frameStatusLive: false,
    _pendingConnectedToast: null,
    decoderMode: null,     // 'webcodecs', 'jmuxer', or 'broadway'
    latencyCheckInterval: null,
    streamStarted: false,
    selectedQuality: 'MEDIUM',
    activeFps: 10,  // Updated from /api/stream/quality response; default matches MEDIUM preset
    // Last view mode the backend accepted; currentViewMode is set optimistically.
    confirmedViewMode: -1,
    // Bumped by stopStream. An async start/view-switch compares its captured
    // value and abandons its remaining steps if it has been superseded.
    _startGeneration: 0,
    // Every camera selection invalidates outstanding view-route responses and
    // readiness polls. An older DVR completion must not override a newer pick.
    _viewSelectionToken: 0,
    _viewPollToken: 0,
    _viewMutationTail: Promise.resolve(),
    _viewRouteClientId: null,
    // A stream paused because the app was backgrounded should resume the
    // same camera. Manual stops intentionally clear this state.
    _backgroundResumeMode: null,
    _backgroundResumeTimer: null,
    _backgroundResumeInFlight: false,
    _backgroundResumeRerequested: false,
    
    // WebSocket connects to /ws on same port as HTTP server (optimized endpoint)
    // Old raw stream port 8887 is deprecated - bypasses SOTA optimizations
    
    // Detect iOS
    isIOS: /iPad|iPhone|iPod/.test(navigator.userAgent) || 
           (navigator.maxTouchPoints > 1 && /Mac/.test(navigator.userAgent)),
    
    // Check WebCodecs support
    hasWebCodecs: typeof VideoDecoder !== 'undefined',
    
    /**
     * Poll until the backend returns a terminal response (success=true, or
     * success=false without starting=true). DVR uses the read-only status
     * endpoint after its initial selection; AVM modes retain the legacy route
     * retry because their cold-start completion is not backend-owned.
     * Cadence is geometric so the head-unit doesn't burn 12 sequential RTTs
     * on a slow cellular link. Returns the final response object.
     */
    async _pollViewUntilSettled(mode, initialResponse, selectionToken) {
        // Token-based cancellation: if a second view-set is fired while
        // an earlier poll is still running, the older poll observes the
        // token mismatch and bails so the UI mutation race lands on the
        // newer click. Mirrors recording.js _oemPollToken.
        const token = (this._viewPollToken || 0) + 1;
        this._viewPollToken = token;
        const delays = [500, 1000, 2000, 3000, 4000];
        let data = initialResponse;
        let toastShown = false;
        const cancelled = () => this._viewPollToken !== token
            || (selectionToken !== undefined
                && this._viewSelectionToken !== selectionToken);
        // Cancellation returns a {cancelled:true} sentinel, NOT the
        // last fetched data. Callers that don't recognise the sentinel
        // would otherwise read a stale {success:false,starting:true}
        // and fire a false error toast / clear UI for a click that's
        // no longer the user's intent.
        for (let i = 0; i < delays.length; i++) {
            if (cancelled()) return { cancelled: true };
            await new Promise(r => setTimeout(r, delays[i]));
            if (cancelled()) return { cancelled: true };
            try {
                // DVR warm-up is completed by the daemon lifecycle worker.
                // Polling the mutating route endpoint used to re-bind the OEM
                // source every pass; on single-client HAL variants that could
                // make the feed alternate between AVM and DVR. Observe mode 6
                // through the read-only status endpoint after the first click.
                data = mode === 6
                    ? await this._requestViewStatus(mode, selectionToken)
                    : await this._requestViewRoute(mode, selectionToken);
            } catch (e) { continue; }
            if (data && data.cancelled) return data;
            if (cancelled()) return { cancelled: true };
            if (data && data.success === true) break;
            // Terminal failure (no `starting` flag) — bail immediately so the
            // user sees the real error toast instead of waiting out the full
            // 10.5s geometric backoff. `oem_unsupported` from a sticky
            // lastStartError is the canonical case.
            if (data && data.success === false && !data.starting) break;
            if (!toastShown && data && data.success === false && data.starting) {
                // Backend-provided errorCode names the gate that's still
                // warming up; older backends don't emit it on the starting
                // path, hence the mode-based fallback.
                const key = (data.errorCode && typeof data.errorCode === 'string')
                    ? 'stream.' + data.errorCode
                    : (mode === 6 ? 'stream.oem_starting' : 'stream.pano_starting');
                const message = BYD.i18n.t(key);
                if (typeof this.showStartupStatus === 'function') {
                    this.showStartupStatus(message);
                } else if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(message, 'info');
                }
                toastShown = true;
            }
        }
        // Augment the returned data so the caller can distinguish
        // "polling exhausted while still in starting state" from
        // "backend rejected the view mode". Without this flag the
        // caller would toast "view unavailable" on a slow-pipeline
        // timeout, which is inaccurate (the mode IS available, the
        // backend just didn't settle within the geometric window).
        if (data && data.success !== true && data.starting === true) {
            data = Object.assign({}, data, { exhausted: true });
        }
        return data;
    },

    /**
     * Serialize view mutations from this page. Fetch cancellation cannot undo
     * a request the backend has already received; the next selection therefore
     * waits for an earlier route to settle, then applies its own route last.
     */
    _enqueueViewMutation(selectionToken, mutation) {
        const run = async () => {
            if (selectionToken !== undefined
                    && this._viewSelectionToken !== selectionToken) {
                return { cancelled: true };
            }
            return mutation();
        };
        const queued = this._viewMutationTail.then(run, run);
        // Keep the tail usable after a rejected network request while
        // returning the original result to the initiating caller.
        this._viewMutationTail = queued.catch(() => {});
        return queued;
    },

    _requestViewRoute(mode, selectionToken) {
        return this._enqueueViewMutation(selectionToken, async () => {
            const res = await fetch(this._viewRouteUrl(mode, selectionToken));
            if (!res.ok) throw new Error('view route failed: ' + res.status);
            const data = await res.json();
            if (selectionToken !== undefined
                    && this._viewSelectionToken !== selectionToken) {
                return { cancelled: true };
            }
            return data;
        });
    },

    _requestViewStatus(mode, selectionToken) {
        return fetch(this._viewStatusUrl(mode, selectionToken)).then(async (res) => {
            if (!res.ok) throw new Error('view status failed: ' + res.status);
            const data = await res.json();
            if (selectionToken !== undefined
                    && this._viewSelectionToken !== selectionToken) {
                return { cancelled: true };
            }
            return data;
        });
    },

    _viewRouteUrl(mode, selectionToken) {
        if (typeof selectionToken !== 'number') {
            return '/api/stream/view/' + mode;
        }
        if (!this._viewRouteClientId) {
            this._viewRouteClientId = 'live' + Date.now().toString(36)
                + Math.random().toString(36).substring(2);
        }
        return '/api/stream/view/' + mode
            + '?client=' + encodeURIComponent(this._viewRouteClientId)
            + '&selection=' + selectionToken;
    },

    _viewStatusUrl(mode, selectionToken) {
        let url = '/api/stream/view-status/' + mode;
        if (typeof selectionToken !== 'number') return url;
        if (!this._viewRouteClientId) {
            this._viewRouteClientId = 'live' + Date.now().toString(36)
                + Math.random().toString(36).substring(2);
        }
        return url
            + '?client=' + encodeURIComponent(this._viewRouteClientId)
            + '&selection=' + selectionToken;
    },

    _requestStreamDisable(selectionToken) {
        return this._enqueueViewMutation(selectionToken, async () => {
            const res = await fetch('/api/stream/disable', { method: 'POST' });
            if (!res.ok) throw new Error('stream disable failed: ' + res.status);
            return res.json();
        });
    },

    /**
     * Set quality before streaming starts
     */
    setQualityUpfront(quality) {
        this.selectedQuality = quality;
        console.log('[Stream] Quality preset:', quality);
    },
    
    /**
     * Select camera and start streaming
     */
    async selectCamera(mode) {
        this.currentViewMode = mode;
        
        // Highlight selected camera on the car diagram
        this.updateCarDiagram(mode);
        
        // Start streaming
        await this.startStream();
    },
    
    /**
     * Highlight car section on hover
     */
    hoverCam(section) {
        const sectionMap = {
            'front': 'hlFront',
            'rear': 'hlRear', 
            'left': 'hlLeft',
            'right': 'hlRight'
        };
        
        // Reset all highlights
        Object.values(sectionMap).forEach(id => {
            const el = document.getElementById(id);
            if (el) {
                el.style.fill = 'transparent';
                el.style.stroke = 'transparent';
            }
        });
        
        // Apply highlight to hovered section
        if (section && sectionMap[section]) {
            const el = document.getElementById(sectionMap[section]);
            if (el) {
                el.style.fill = 'rgba(0, 212, 170, 0.15)';
                el.style.stroke = 'rgba(0, 212, 170, 0.5)';
            }
        }
        
        // Also highlight the label
        document.querySelectorAll('.cam-label').forEach(label => {
            if (section && label.classList.contains(section)) {
                label.classList.add('hover');
            } else {
                label.classList.remove('hover');
            }
        });
    },
    
    /**
     * Update car diagram selection
     */
    updateCarDiagram(mode) {
        // Remove all active states
        document.querySelectorAll('.cam-indicator').forEach(el => el.classList.remove('active'));
        document.querySelectorAll('.cam-label').forEach(el => el.classList.remove('active'));
        document.querySelectorAll('.cam-area').forEach(el => el.classList.remove('active'));
        
        // Add active state to selected
        const camMap = { 0: 'all', 1: 'front', 2: 'right', 3: 'rear', 4: 'left' };
        const camName = camMap[mode];
        
        if (mode === 0) {
            // All cameras - highlight all
            document.querySelectorAll('.cam-indicator').forEach(el => el.classList.add('active'));
        } else {
            const indicator = document.querySelector(`.cam-indicator[data-cam="${mode}"]`);
            if (indicator) indicator.classList.add('active');
        }
        
        const label = document.querySelector(`.cam-label.${camName}`);
        if (label) label.classList.add('active');
    },
    
    /**
     * Enable streaming on server
     */
    async enableStreaming() {
        try {
            const res = await fetch('/api/stream/enable', { method: 'POST' });
            const data = await res.json();
            return data.success;
        } catch (e) {
            console.error('[Stream] Enable error:', e);
            return false;
        }
    },

    /**
     * Record actual frame delivery and converge every live-view status surface.
     * This is intentionally idempotent so legacy WebSocket onmessage can call it
     * for every packet without repainting the status DOM at camera frame rate.
     */
    noteFrameReceived(count) {
        this.frameCount = Number.isFinite(count) ? count : this.frameCount + 1;
        this.lastFrameTime = Date.now();
        if (!this._frameStatusLive) {
            this._frameStatusLive = true;
            this.updateStreamStatus(BYD.i18n.t('stream.live'), true);
            if (this._pendingConnectedToast && BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t(this._pendingConnectedToast), 'success');
            }
            this._pendingConnectedToast = null;
        }
    },

    /**
     * Reset frame-backed live state for a fresh connection or camera switch.
     */
    markStreamConnecting() {
        this._frameStatusLive = false;
        this.updateStreamStatus(BYD.i18n.t('stream.connecting'), false);
    },
    
    /**
     * Initialize decoder based on device capabilities
     * Priority: WebCodecs > JMuxer > Broadway
     */
    async initDecoder() {
        // Try WebCodecs first (SOTA - best performance)
        if (this.hasWebCodecs && typeof SotaPlayer !== 'undefined') {
            const success = await this.initWebCodecs();
            if (success) return true;
            console.log('[Stream] WebCodecs failed, trying fallback...');
        }
        
        // Fallback: JMuxer for non-iOS with MSE support. await because initJMuxer
        // can hand back initBroadway()'s promise when it defers; an unawaited
        // promise is truthy and would mask a Broadway failure as success.
        if (!this.isIOS && window.MediaSource) {
            const success = await this.initJMuxer();
            if (success) return true;
        }

        // Final fallback: Broadway (CPU decoding)
        return await this.initBroadway();
    },
    
    /**
     * Initialize WebCodecs (SOTA - Hardware GPU decoding)
     */
    async initWebCodecs() {
        console.log('[Stream] Initializing WebCodecs (SOTA Hardware)');
        
        try {
            // Get or create canvas for WebCodecs
            let canvas = document.getElementById('sota_canvas');
            if (!canvas) {
                canvas = document.createElement('canvas');
                canvas.id = 'sota_canvas';
                canvas.width = 1280;
                canvas.height = 960;
                canvas.style.cssText = 'width:100%;height:100%;object-fit:contain;display:none;';
                
                const container = document.getElementById('sw_container') || 
                                  document.getElementById('videoDisplayArea');
                if (container) {
                    container.appendChild(canvas);
                }
            }
            
            // SOTA FIX: Connect to optimized /ws endpoint on same port as HTTP server
            // NOT the raw stream port 8887 which bypasses SOTA optimizations.
            // Append JWT as ?token= so tunnels work (cookies stripped by SameSite;
            // browser WebSocket API can't set Authorization header).
            const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
            let wsUrl = `${protocol}//${location.host}/ws`;
            if (typeof BYDAuth !== 'undefined') {
                const wsToken = BYDAuth.getToken();
                if (wsToken) wsUrl += `?token=${encodeURIComponent(wsToken)}`;
            }

            console.log('[Stream] WebCodecs connecting to optimized endpoint:', wsUrl);

            // Create SOTA player
            this.sotaPlayer = new SotaPlayer(canvas, wsUrl);
            
            // Set up callbacks
            this.sotaPlayer.onConnected = () => {
                console.log('[Stream] WebCodecs connected');
                // Wait for a decoded frame before claiming the camera is live.
                this._pendingConnectedToast = 'stream.connected_webcodecs';
                this.markStreamConnecting();
            };

            this.sotaPlayer.onDisconnected = (code) => {
                console.log('[Stream] WebCodecs disconnected:', code);
                this._pendingConnectedToast = null;
                this.markStreamConnecting();
            };
            
            this.sotaPlayer.onFrame = (count) => {
                this.noteFrameReceived(count);
            };
            
            this.sotaPlayer.onError = (e) => {
                console.error('[Stream] WebCodecs error:', e);
                // WebCodecs can't decode this stream on this device — hand over to
                // JMuxer/Broadway instead of sitting on a black canvas.
                this.fallbackFromWebCodecs();
            };
            
            this.decoderMode = 'webcodecs';
            this.updateDecoderBadge('SOTA');
            
            // Show canvas, hide video element
            canvas.style.display = 'block';
            const video = document.getElementById('hw_player');
            if (video) video.style.display = 'none';
            
            console.log('[Stream] WebCodecs initialized successfully');
            return true;
            
        } catch (e) {
            console.error('[Stream] WebCodecs init failed:', e);
            return false;
        }
    },
    
    /**
     * Initialize JMuxer for Android/PC (GPU decoding via MSE)
     */
    initJMuxer() {
        console.log('[Stream] Initializing JMuxer (GPU)');
        this.decoderMode = 'jmuxer';
        
        const video = document.getElementById('hw_player');
        if (!video) {
            console.error('[Stream] Video element not found');
            return false;
        }
        
        if (this.jmuxer) return true;

        if (!window.MediaSource) {
            console.warn('[Stream] MSE not supported, falling back to Broadway');
            return this.initBroadway();
        }

        // JMuxer ships from a CDN (live-view.html loads jsdelivr), so it is simply
        // absent whenever the car/phone has no internet — the common case for a
        // parked vehicle. Without this check `new JMuxer` throws ReferenceError
        // and takes the whole decoder cascade down instead of reaching Broadway,
        // which is bundled locally.
        if (typeof JMuxer === 'undefined') {
            console.warn('[Stream] JMuxer unavailable (CDN blocked/offline), using Broadway');
            return this.initBroadway();
        }

        try {
            this.jmuxer = new JMuxer({
                node: 'hw_player',
                mode: 'video',
                flushingTime: 0,
                fps: this.activeFps || 10,
                debug: false,
                onReady: () => {
                    console.log('[Stream] JMuxer ready (fps=' + (this.activeFps || 10) + ')');
                    video.style.display = 'block';
                    video.play().catch(e => console.log('[Stream] Autoplay blocked:', e.message));
                },
                onError: (e) => console.error('[Stream] JMuxer error:', e)
            });
            
            this.updateDecoderBadge('GPU');
            this.startLatencyMonitor(video);
            
            return true;
        } catch (e) {
            console.error('[Stream] JMuxer init failed:', e);
            return this.initBroadway();
        }
    },

    /**
     * Initialize Broadway for iOS (CPU/Software decoding)
     */
    async initBroadway() {
        console.log('[Stream] Initializing Broadway (CPU)');
        this.decoderMode = 'broadway';
        
        try {
            if (typeof Player === 'undefined') {
                console.error('[Stream] Broadway Player not loaded');
                return false;
            }
            
            // 1. Construct Absolute URL for the WASM file
            // Assumes current page is in /local/ and assets are in /shared/
            const baseUrl = new URL('../shared/', window.location.href).href;
            const wasmUrl = baseUrl + 'avc.wasm';
            
            // 2. Fetch Decoder.js source code
            const decoderUrl = baseUrl + "Decoder.js";
            const response = await fetch(decoderUrl);
            let decoderCode = await response.text();
            
            // 3. CRITICAL FIX: Replace relative "avc.wasm" with Absolute URL
            // This fixes: "Failed to parse URL from avc.wasm" in Blob Workers
            decoderCode = decoderCode.replace(/["']avc\.wasm["']/g, `"${wasmUrl}"`);
            
            // 4. Inject locateFile (Standard Emscripten hook) as a backup
            const locateFileInjection = `
                var Module = Module || {};
                Module['locateFile'] = function(path) {
                    if (path.endsWith('.wasm')) {
                        return '${wasmUrl}';
                    }
                    return path;
                };
            `;
            decoderCode = locateFileInjection + decoderCode;
            
            // 5. Create Worker from modified source
            const blob = new Blob([decoderCode], { type: 'application/javascript' });
            const workerUrl = URL.createObjectURL(blob);
            
            this.broadwayPlayer = new Player({
                useWorker: true,
                workerFile: workerUrl,
                webgl: true,
                size: { width: 1280, height: 960 }
            });
            
            // 6. Setup Canvas
            const container = document.getElementById('sw_container');
            if (container) {
                container.innerHTML = '';
                container.appendChild(this.broadwayPlayer.canvas);
                container.style.display = 'block';
                this.broadwayPlayer.canvas.style.width = '100%';
                this.broadwayPlayer.canvas.style.height = '100%';
                this.broadwayPlayer.canvas.style.objectFit = 'contain';
            }
            
            this.updateDecoderBadge('CPU');
            return true;
            
        } catch (e) {
            console.error('[Stream] Broadway init failed:', e);
            return false;
        }
    },
    
    /**
     * Latency monitor - jump to live if buffer exceeds threshold
     */
    startLatencyMonitor(video) {
        if (this.latencyCheckInterval) clearInterval(this.latencyCheckInterval);
        
        this.latencyCheckInterval = setInterval(() => {
            if (video && !video.paused && video.buffered.length > 0) {
                const end = video.buffered.end(0);
                const latency = end - video.currentTime;
                if (latency > 1.5) {
                    console.log('[Stream] High latency - jumping to live');
                    video.currentTime = end - 0.1;
                }
            }
        }, 2000);
    },
    
    /**
     * Update decoder badge
     */
    updateDecoderBadge(mode) {
        const badge = document.getElementById('decoderBadge');
        if (badge) {
            badge.textContent = mode;
            badge.style.display = 'inline-flex';
        }
    },
    
    /**
     * WebCodecs reported an unrecoverable decode failure — retire it and retry
     * with the MSE/CPU decoders. Runs at most once per page so a fallback that
     * also fails can't ping-pong. Without this the player stayed "connected"
     * forever on a black canvas, which is what made the camera look broken on
     * some phones while working in the car's WebView (which never takes the
     * WebCodecs path at all — Chrome 58 has no VideoDecoder).
     */
    async fallbackFromWebCodecs() {
        if (this._webCodecsRetired) return;
        this._webCodecsRetired = true;
        console.warn('[Stream] Retiring WebCodecs, switching to fallback decoder');

        if (this.sotaPlayer) {
            try { this.sotaPlayer.stop(); } catch (e) {}
            this.sotaPlayer = null;
        }
        const canvas = document.getElementById('sota_canvas');
        if (canvas) { canvas.style.display = 'none'; canvas.classList.remove('active'); }

        this.decoderMode = null;
        this.hasWebCodecs = false;   // keeps initDecoder from re-picking WebCodecs
        this.markStreamConnecting();

        try {
            // await regardless: initJMuxer returns a bare boolean normally, but
            // returns initBroadway()'s PROMISE when it defers (no MSE / JMuxer
            // missing). A promise is always truthy, so testing it unawaited would
            // report success even when Broadway failed to load.
            const ready = await ((!this.isIOS && window.MediaSource)
                ? this.initJMuxer()
                : this.initBroadway());
            if (!ready) {
                console.error('[Stream] Fallback decoder init failed');
                return;
            }
            this.updateDecoderBadge(this.decoderMode === 'jmuxer' ? 'JMuxer' : 'Broadway');
            if (this.decoderMode === 'jmuxer') {
                const v = document.getElementById('hw_player');
                if (v) { v.style.display = 'block'; v.classList.add('active'); }
            } else {
                const c = document.getElementById('sw_container');
                if (c) c.classList.add('active');
            }
            this.connectWebSocket();
        } catch (e) {
            console.error('[Stream] Fallback switch failed:', e);
        }
    },

    /**
     * Connect to WebSocket stream
     */
    connectWebSocket() {
        // For WebCodecs mode, the SotaPlayer handles its own WebSocket
        if (this.decoderMode === 'webcodecs' && this.sotaPlayer) {
            this.sotaPlayer.start();
            return;
        }
        
        // CONNECTING too: overwriting this.ws mid-handshake orphans that socket
        // beyond stopStream's reach and both then feed the same jmuxer.
        if (this.ws &&
            (this.ws.readyState === WebSocket.OPEN ||
             this.ws.readyState === WebSocket.CONNECTING)) return;
        
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        
        // SOTA FIX: Always use optimized /ws endpoint on same port as HTTP server.
        // Append JWT as ?token= for tunnel compatibility (see WebCodecs path above).
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        let wsUrl = `${protocol}//${location.host}/ws`;
        if (typeof BYDAuth !== 'undefined') {
            const wsToken = BYDAuth.getToken();
            if (wsToken) wsUrl += `?token=${encodeURIComponent(wsToken)}`;
        }

        console.log('[Stream] Connecting:', wsUrl);

        try {
            this.ws = new WebSocket(wsUrl);
            this.ws.binaryType = 'arraybuffer';
            
            this.ws.onopen = () => {
                console.log('[Stream] Connected');
                this.frameCount = 0;
                // A connected socket can still have no camera frames. The
                // first onmessage below promotes the UI to Live.
                this._pendingConnectedToast = 'stream.connected';
                this.markStreamConnecting();
            };
            
            this.ws.onmessage = (event) => {
                if (!event.data) return;
                const data = new Uint8Array(event.data);
                
                if (this.decoderMode === 'broadway' && this.broadwayPlayer) {
                    this.broadwayPlayer.decode(data);
                } else if (this.decoderMode === 'jmuxer' && this.jmuxer) {
                    this.jmuxer.feed({ video: data });
                }
                
                this.noteFrameReceived();
            };
            
            this.ws.onclose = (event) => {
                console.log('[Stream] Closed:', event.code);
                this.ws = null;
                this._pendingConnectedToast = null;
                this.markStreamConnecting();
                
                if (!this.reconnectTimer && this.streamStarted) {
                    this.reconnectTimer = setTimeout(() => {
                        this.reconnectTimer = null;
                        this.connectWebSocket();
                    }, 2000);
                }
            };
            
            this.ws.onerror = (error) => {
                console.error('[Stream] Error:', error);
            };
            
        } catch (e) {
            console.error('[Stream] Connection failed:', e);
        }
    },
    
    /**
     * Update stream status
     */
    updateStreamStatus(text, isLive) {
        const statusEl = document.getElementById('streamStatus');
        const badge = document.getElementById('connectionBadge');
        const dot = document.getElementById('streamDot');
        
        if (statusEl) statusEl.textContent = text;
        
        if (badge) {
            if (isLive) {
                badge.classList.add('live');
                badge.classList.remove('connecting');
            } else {
                badge.classList.remove('live');
                badge.classList.add('connecting');
            }
        }
        
        if (dot) {
            if (isLive) {
                dot.classList.add('live');
                dot.classList.remove('connecting');
            } else {
                dot.classList.remove('live');
                dot.classList.add('connecting');
            }
        }
    },

    /**
     * Start streaming
     */
    async startStream() {
        this.streamStarted = true;

        // Optimistic UI mutations — Connecting overlay shows during the
        // multi-second view-mode poll. Both abandon paths (cancellation
        // by a newer poll, and validated-rejection) MUST restore the
        // prior state via abandonAndRestore() — pre-fix the cancellation
        // path returned without undoing these, leaving the user with a
        // permanent "Connecting..." spinner.
        const placeholder = document.getElementById('placeholder');
        const overlay = document.getElementById('streamOverlay');
        if (placeholder) placeholder.style.display = 'none';
        if (overlay) overlay.style.display = 'flex';

        // Show connecting state immediately
        this.markStreamConnecting();

        // Update view label
        const viewLabel = document.getElementById('viewLabel');
        if (viewLabel) viewLabel.textContent = this._viewModeName(this.currentViewMode) || BYD.i18n.t('stream.camera');

        // Update mini selector
        this.updateCarSelector(this.currentViewMode);

        // Sync quality selector with preset
        const qualitySelect = document.getElementById('qualitySelect');
        if (qualitySelect) qualitySelect.value = this.selectedQuality;

        const abandonAndRestore = () => {
            this.streamStarted = false;
            this.currentViewMode = -1;
            if (viewLabel) viewLabel.textContent = '';
            if (placeholder) placeholder.style.display = 'flex';
            if (overlay) overlay.style.display = 'none';
        };

        // Validate view mode FIRST — before spinning up the server-side
        // streaming encoder. Pre-fix this happened AFTER enableStreaming,
        // so a backend rejection of the view-mode (e.g. view 6 with no
        // OEM stream-routing hook) left the encoder running with no
        // consumer until the next start or a manual disable. Mirrors
        // the live-view.html flow which already validates first.
        if (this.currentViewMode >= 0) {
            try {
                let data = await this._requestViewRoute(this.currentViewMode);
                if (data && data.success === false && data.starting) {
                    data = await this._pollViewUntilSettled(this.currentViewMode, data);
                }
                // Cancelled by a newer poll — restore the placeholder so
                // the Connecting overlay doesn't strand on cancellation.
                if (data && data.cancelled) {
                    abandonAndRestore();
                    return;
                }
                if (data && data.success === false) {
                    if (BYD.utils && BYD.utils.toast) {
                        // exhausted=true means the geometric poll ran
                        // out of attempts while the backend was still
                        // in the starting=true state. That's a startup
                        // timeout, not a view-mode rejection.
                        const fallback = data.exhausted
                            ? BYD.i18n.t('stream.start_timeout')
                            : BYD.i18n.t('stream.view_unavailable');
                        BYD.utils.toast(data.error || fallback, 'error');
                    }
                    abandonAndRestore();
                    return;
                }
            } catch (e) {}
        }

        // Enable streaming on server (only after view-mode is validated)
        const enabled = await this.enableStreaming();
        if (!enabled) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('stream.enable_failed'), 'error');
            return;
        }

        // Apply preset quality and capture the active fps for JMuxer timing
        try {
            const qRes = await fetch('/api/stream/quality/' + this.selectedQuality, { method: 'POST' });
            const qData = await qRes.json();
            if (qData.fps) this.activeFps = qData.fps;
        } catch (e) {}
        
        await new Promise(r => setTimeout(r, 300));
        
        const decoderReady = await this.initDecoder();
        if (!decoderReady) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('stream.decoder_failed'), 'error');
            return;
        }
        
        this.connectWebSocket();
    },
    
    /**
     * Stop streaming and show camera selector
     */
    stopAndShowSelector() {
        this.stopStream();
        
        // Show placeholder, hide overlay
        const placeholder = document.getElementById('placeholder');
        const overlay = document.getElementById('streamOverlay');
        if (placeholder) placeholder.style.display = 'flex';
        if (overlay) overlay.style.display = 'none';
        
        // Reset selection
        this.currentViewMode = -1;
        document.querySelectorAll('.cam-indicator').forEach(el => el.classList.remove('active'));
        document.querySelectorAll('.cam-label').forEach(el => el.classList.remove('active'));
    },
    
    /**
     * Stop streaming
     */
    stopStream(options) {
        // Invalidate any start/view-switch still sitting in an await.
        this._startGeneration++;
        this._viewSelectionToken = (this._viewSelectionToken || 0) + 1;
        this._viewPollToken = (this._viewPollToken || 0) + 1;
        const preserveBackgroundResume = options && options.preserveBackgroundResume === true;
        if (!preserveBackgroundResume) {
            this._backgroundResumeMode = null;
            // Don't leave a mode pauseForBackground could silently restart.
            this.confirmedViewMode = -1;
            if (this._backgroundResumeTimer) {
                clearTimeout(this._backgroundResumeTimer);
                this._backgroundResumeTimer = null;
            }
        }

        this.streamStarted = false;
        this._frameStatusLive = false;
        this._pendingConnectedToast = null;
        
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        
        if (this.latencyCheckInterval) {
            clearInterval(this.latencyCheckInterval);
            this.latencyCheckInterval = null;
        }
        
        // Stop WebCodecs player
        if (this.sotaPlayer) {
            this.sotaPlayer.stop();
            this.sotaPlayer = null;
        }
        
        if (this.ws) {
            this.ws.onclose = null;
            this.ws.close();
            this.ws = null;
        }
        
        if (this.jmuxer) {
            try { this.jmuxer.destroy(); } catch (e) {}
            this.jmuxer = null;
        }
        
        if (this.broadwayPlayer) {
            this.broadwayPlayer = null;
        }
        
        // Hide video elements by dropping the .active class, NOT via an inline
        // display:none — inline style outranks the non-important `video.active`
        // rule, so a later restart could never make the legacy player visible.
        const hwPlayer = document.getElementById('hw_player');
        const swContainer = document.getElementById('sw_container');
        const sotaCanvas = document.getElementById('sota_canvas');
        [hwPlayer, swContainer, sotaCanvas].forEach(function (el) {
            if (!el) return;
            el.classList.remove('active');
            el.style.removeProperty('display');
        });
        
        this.decoderMode = null;
        console.log('[Stream] Stopped');
    },

    /**
     * Pause an active stream while preserving enough intent to restore the
     * same camera when Android brings the WebView back to the foreground.
     */
    pauseForBackground() {
        if (!this.streamStarted || this.currentViewMode < 0) return;
        // Prefer the confirmed mode; currentViewMode may be an unconfirmed switch.
        const mode = (typeof this.confirmedViewMode === 'number' &&
                      this.confirmedViewMode >= 0)
            ? this.confirmedViewMode
            : this.currentViewMode;
        console.log('[Stream] Backgrounded - pausing camera:', mode);
        this.stopStream({ preserveBackgroundResume: true });
        this._backgroundResumeMode = mode;

        // Do not leave a stale "Live" badge over the blank decoder while the
        // WebView is being restored.
        this.updateStreamStatus(BYD.i18n.t('stream.connecting'), false);
    },

    /**
     * Resume a lifecycle-paused stream. Android onResume and the Page
     * Visibility API can both signal the foreground transition, so the short
     * timer and in-flight guard ensure only one decoder/socket is created.
     */
    resumeAfterBackground(forceForeground) {
        const foregroundIsAuthoritative = forceForeground === true;
        if (foregroundIsAuthoritative && this._backgroundResumeTimer) {
            clearTimeout(this._backgroundResumeTimer);
            this._backgroundResumeTimer = null;
        }
        if (this._backgroundResumeInFlight) {
            // Another cycle happened mid-resume; the finally block re-drives it,
            // otherwise this request would be dropped with nothing to retry it.
            this._backgroundResumeRerequested = true;
            return;
        }
        if (this._backgroundResumeMode === null ||
            this._backgroundResumeMode < 0 ||
            this.streamStarted ||
            this._backgroundResumeTimer) {
            return;
        }

        this._backgroundResumeTimer = setTimeout(async () => {
            this._backgroundResumeTimer = null;
            // Re-check hidden even when onResume signalled: the activity can be
            // paused again inside the 250ms window and JS timers keep running,
            // which would turn a camera on off-screen.
            if (document.hidden ||
                this._backgroundResumeMode === null ||
                this.streamStarted ||
                this._backgroundResumeInFlight) {
                return;
            }

            const mode = this._backgroundResumeMode;
            this._backgroundResumeMode = null;
            this._backgroundResumeInFlight = true;
            this._backgroundResumeRerequested = false;
            console.log('[Stream] Foregrounded - resuming camera:', mode);
            // There are no fetch timeouts in this codebase, so a request that
            // never settles would wedge the in-flight flag for the page's life.
            const watchdog = setTimeout(() => {
                if (this._backgroundResumeInFlight) {
                    console.warn('[Stream] Resume watchdog fired - releasing');
                    this._backgroundResumeInFlight = false;
                    if (this._backgroundResumeMode === null) this._backgroundResumeMode = mode;
                }
            }, 20000);
            try {
                await this.selectCamera(mode);
                // live-view.html's selectCamera override reports most failures by
                // returning, not throwing, so keep the intent for the next resume.
                if (!this.streamStarted && !document.hidden) {
                    console.warn('[Stream] Resume did not start a stream - retaining intent');
                    this._backgroundResumeMode = mode;
                    this.updateConnectionStatus('disconnected');
                }
            } catch (e) {
                console.error('[Stream] Resume failed:', e);
                if (!document.hidden && !this.streamStarted) {
                    this._backgroundResumeMode = mode;
                    this.updateConnectionStatus('disconnected');
                }
            } finally {
                clearTimeout(watchdog);
                this._backgroundResumeInFlight = false;
                if (this._backgroundResumeRerequested) {
                    this._backgroundResumeRerequested = false;
                    if (!document.hidden && !this.streamStarted &&
                        this._backgroundResumeMode !== null) {
                        this.resumeAfterBackground(true);
                    }
                }
            }
        }, 250);
    },
    
    /**
     * Update mini car selector
     */
    updateCarSelector(mode) {
        const btns = ['vm0', 'vm1', 'vm2', 'vm3', 'vm4'];
        btns.forEach((id, i) => {
            const btn = document.getElementById(id);
            if (btn) {
                btn.classList.toggle('active', i === mode);
                btn.classList.remove('loading');
            }
        });
    },
    
    /**
     * Set view mode (while streaming)
     */
    async setViewMode(mode) {
        const btn = document.getElementById('vm' + mode);
        if (btn) btn.classList.add('loading');

        try {
            let data = await this._requestViewRoute(mode);
            if (data && data.success === false && data.starting) {
                data = await this._pollViewUntilSettled(mode, data);
            }
            // Cancelled by a newer click — drop the loading spinner
            // and exit without mutating UI label / current view.
            if (data && data.cancelled) {
                if (btn) btn.classList.remove('loading');
                return;
            }

            if (data.success) {
                this.currentViewMode = mode;
                this.updateCarSelector(mode);
                const viewLabel = document.getElementById('viewLabel');
                const label = this._viewModeName(mode) || BYD.i18n.t('stream.camera');
                if (viewLabel) viewLabel.textContent = label;
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(label, 'info');
            } else {
                if (BYD.utils && BYD.utils.toast) {
                    // Prefer i18n key from errorCode; fall back to the
                    // exhausted-vs-rejection-aware localized fallback so
                    // a startup timeout doesn't get mis-toasted as
                    // "view mode unavailable".
                    let msg;
                    if (data.errorCode) {
                        msg = BYD.i18n.t('stream.' + data.errorCode);
                    } else if (data.error) {
                        msg = data.error;
                    } else {
                        msg = data.exhausted
                            ? BYD.i18n.t('stream.start_timeout')
                            : BYD.i18n.t('stream.view_unavailable');
                    }
                    BYD.utils.toast(msg, 'error');
                }
                if (btn) btn.classList.remove('loading');
            }
        } catch (e) {
            console.error('[Stream] Set view mode error:', e);
            if (btn) btn.classList.remove('loading');
        }
    },
    
    /**
     * Set streaming quality (saves to backend)
     */
    async setQuality(quality) {
        this.selectedQuality = quality;
        try {
            const res = await fetch('/api/stream/quality/' + quality, { method: 'POST' });
            const data = await res.json();
            if (data.success) {
                const displayName = data.displayName || quality;
                if (BYD.utils && BYD.utils.toast) {
                    // {name}-templated so locales that need a different
                    // separator/word-order can encode it inline rather
                    // than relying on a trailing space getting dropped.
                    const tmpl = BYD.i18n.t('stream.quality_set');
                    BYD.utils.toast(tmpl.replace('{name}', displayName), 'info');
                }
                // Server restarts encoder with new resolution — the existing WebSocket
                // connection will receive new SPS/PPS and the decoder handles it automatically
            }
        } catch (e) {
            console.error('[Stream] Set quality error:', e);
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('stream.quality_failed'), 'error');
        }
    },
    
    /**
     * Toggle fullscreen
     */
    toggleFullscreen() {
        const container = document.getElementById('streamContainer');
        if (!container) return;
        
        this.isFullscreen = !this.isFullscreen;
        container.classList.toggle('fullscreen', this.isFullscreen);
        
        if (this.isFullscreen && container.requestFullscreen) {
            container.requestFullscreen().catch(() => {});
        } else if (!this.isFullscreen && document.fullscreenElement) {
            document.exitFullscreen();
        }
    },
    
    /**
     * Load saved quality from backend and update selector
     */
    async loadSavedQuality() {
        try {
            const res = await fetch('/api/stream/quality');
            const data = await res.json();
            if (data.success && data.current) {
                this.selectedQuality = data.current;
                const selector = document.getElementById('qualitySelector');
                if (selector) {
                    selector.value = data.current;
                    // Assigning .value fires no event, so mirrored menus
                    // need an explicit sync.
                    if (BYD.qualityMenu) BYD.qualityMenu.sync();
                    console.log('[Stream] Loaded saved quality:', data.current);
                }
            }
        } catch (e) {
            console.error('[Stream] Failed to load saved quality:', e);
        }
    },
    
    /**
     * Initialize stream module
     */
    init() {
        console.log('[Stream] Init - iOS:', this.isIOS, '- WebCodecs:', this.hasWebCodecs);
        
        // Load saved quality from backend
        this.loadSavedQuality();
        
        // Status polling is handled by core.js - no duplicate polling here
        
        // Pause expensive decoding in the background and restore the same
        // selection when the page becomes visible again.
        document.addEventListener("visibilitychange", () => {
            if (document.hidden) {
                this.pauseForBackground();
            } else {
                this.resumeAfterBackground();
            }
        });
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && this.isFullscreen) {
                this.toggleFullscreen();
            }
        });
        
        // Fullscreen change handler
        document.addEventListener('fullscreenchange', () => {
            if (!document.fullscreenElement && this.isFullscreen) {
                this.isFullscreen = false;
                const container = document.getElementById('streamContainer');
                if (container) container.classList.remove('fullscreen');
            }
        });
        
        console.log('[Stream] Module initialized');
    }
};
