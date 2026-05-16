/**
 * BYD Champ - Floating Picture-in-Picture Camera
 * Draggable, resizable floating camera view for settings pages
 * Uses SotaPlayer (WebCodecs) with JMuxer/Broadway fallbacks
 */

window.BYD = window.BYD || {};

BYD.pip = {
    container: null,
    isDragging: false,
    isMinimized: false,
    isExpanded: false,
    isVisible: false,
    dragOffset: { x: 0, y: 0 },
    
    // Streaming state
    sotaPlayer: null,
    jmuxer: null,
    broadwayPlayer: null,
    decoderMode: null,
    currentViewMode: -1, // -1 = none selected (stream not started)
    streamStarted: false,
    
    isIOS: /iPad|iPhone|iPod/.test(navigator.userAgent) || 
           (navigator.maxTouchPoints > 1 && /Mac/.test(navigator.userAgent)),
    
    /**
     * Create and inject PiP container
     */
    create() {
        if (document.getElementById('pipContainer')) return;
        
        const html = `
        <div class="pip-container" id="pipContainer" style="display:none;">
            <div class="pip-header" id="pipHeader">
                <div class="pip-title">
                    <span class="status-dot" id="pipDot"></span>
                    <span id="pipStatus">${BYD.i18n.t('pip.select_camera')}</span>
                </div>
                <div class="pip-controls">
                    <button class="pip-btn" onclick="BYD.pip.toggleSize()" title="${BYD.i18n.t('pip.title_resize')}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M8 3H5a2 2 0 0 0-2 2v3"/>
                            <path d="M21 8V5a2 2 0 0 0-2-2h-3"/>
                            <path d="M3 16v3a2 2 0 0 0 2 2h3"/>
                            <path d="M16 21h3a2 2 0 0 0 2-2v-3"/>
                        </svg>
                    </button>
                    <button class="pip-btn" onclick="BYD.pip.minimize()" title="${BYD.i18n.t('pip.title_minimize')}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M5 12h14"/>
                        </svg>
                    </button>
                    <button class="pip-btn" onclick="BYD.pip.close()" title="${BYD.i18n.t('pip.title_close')}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M18 6 6 18"/><path d="m6 6 12 12"/>
                        </svg>
                    </button>
                </div>
            </div>
            <div class="pip-video" id="pipVideo">
                <video id="pip_hw_player" autoplay muted playsinline style="display:none;"></video>
                <div id="pip_sw_container" style="display:none;width:100%;height:100%;"></div>
                <canvas id="pip_sota_canvas" style="display:none;width:100%;height:100%;object-fit:contain;"></canvas>
                <div class="pip-placeholder" id="pipPlaceholder">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/>
                        <circle cx="12" cy="13" r="3"/>
                    </svg>
                    <span>${BYD.i18n.t('pip.select_a_camera')}</span>
                </div>
                <div class="decoder-badge" id="pipDecoderBadge"></div>
            </div>
            <div class="pip-footer">
                <button class="pip-cam-btn" id="pip_vm4" onclick="BYD.pip.setView(4)" title="${BYD.i18n.t('pip.title_left_camera')}">L</button>
                <button class="pip-cam-btn" id="pip_vm1" onclick="BYD.pip.setView(1)" title="${BYD.i18n.t('pip.title_front_camera')}">F</button>
                <button class="pip-cam-btn" id="pip_vm0" onclick="BYD.pip.setView(0)" title="${BYD.i18n.t('pip.title_all_cameras')}">ALL</button>
                <button class="pip-cam-btn" id="pip_vm3" onclick="BYD.pip.setView(3)" title="${BYD.i18n.t('pip.title_rear_camera')}">R</button>
                <button class="pip-cam-btn" id="pip_vm2" onclick="BYD.pip.setView(2)" title="${BYD.i18n.t('pip.title_right_camera')}">RT</button>
            </div>
        </div>`;
        
        document.body.insertAdjacentHTML('beforeend', html);
        this.container = document.getElementById('pipContainer');
        this.initDrag();
    },
    
    /**
     * Initialize drag functionality
     */
    initDrag() {
        const header = document.getElementById('pipHeader');
        if (!header) return;
        
        const startDrag = (clientX, clientY) => {
            this.isDragging = true;
            this.dragOffset.x = clientX - this.container.offsetLeft;
            this.dragOffset.y = clientY - this.container.offsetTop;
            this.container.style.transition = 'none';
        };
        
        const moveDrag = (clientX, clientY) => {
            if (!this.isDragging) return;
            const x = Math.max(0, Math.min(window.innerWidth - this.container.offsetWidth, clientX - this.dragOffset.x));
            const y = Math.max(0, Math.min(window.innerHeight - this.container.offsetHeight, clientY - this.dragOffset.y));
            this.container.style.left = x + 'px';
            this.container.style.top = y + 'px';
            this.container.style.right = 'auto';
            this.container.style.bottom = 'auto';
        };
        
        const endDrag = () => {
            this.isDragging = false;
            this.container.style.transition = '';
        };
        
        // Mouse events
        header.addEventListener('mousedown', (e) => {
            if (e.target.closest('.pip-btn')) return;
            startDrag(e.clientX, e.clientY);
        });
        document.addEventListener('mousemove', (e) => moveDrag(e.clientX, e.clientY));
        document.addEventListener('mouseup', endDrag);
        
        // Touch events
        header.addEventListener('touchstart', (e) => {
            if (e.target.closest('.pip-btn')) return;
            const touch = e.touches[0];
            startDrag(touch.clientX, touch.clientY);
        });
        document.addEventListener('touchmove', (e) => {
            if (!this.isDragging) return;
            const touch = e.touches[0];
            moveDrag(touch.clientX, touch.clientY);
        });
        document.addEventListener('touchend', endDrag);
    },
    
    /**
     * Toggle PiP visibility
     */
    toggle() {
        if (this.isVisible) {
            this.close();
        } else {
            this.show();
        }
    },
    
    /**
     * Show PiP (but don't start streaming - wait for camera selection)
     */
    show() {
        this.create();
        this.container.style.display = 'block';
        this.isVisible = true;
        
        // Reset state - don't auto-start stream
        this.currentViewMode = -1;
        this.streamStarted = false;
        document.querySelectorAll('.pip-cam-btn').forEach(b => b.classList.remove('active'));
        document.getElementById('pipStatus').textContent = BYD.i18n.t('pip.select_camera');
        document.getElementById('pipDot').classList.remove('live');
        this.showPlaceholder(BYD.i18n.t('pip.select_a_camera'));
    },

    /**
     * Hide PiP
     */
    hide() {
        if (this.container) {
            this.container.style.display = 'none';
        }
        this.isVisible = false;
    },
    
    /**
     * Close PiP and stop streaming
     */
    close() {
        this.stopStream();
        this.hide();
        this.currentViewMode = -1;
        this.streamStarted = false;
    },
    
    /**
     * Set camera view with toggle behavior
     */
    async setView(mode) {
        const viewNames = [BYD.i18n.t('pip.view_all'), BYD.i18n.t('pip.view_front'), BYD.i18n.t('pip.view_right'), BYD.i18n.t('pip.view_rear'), BYD.i18n.t('pip.view_left')];
        const btn = document.getElementById('pip_vm' + mode);
        
        // Toggle behavior: if same camera tapped again, stop the stream
        if (this.currentViewMode === mode && this.streamStarted) {
            console.log('[PiP] Toggling off camera:', mode);
            this.stopStream();
            this.currentViewMode = -1;
            this.streamStarted = false;
            
            // Remove active state from buttons
            document.querySelectorAll('.pip-cam-btn').forEach(b => b.classList.remove('active'));
            document.getElementById('pipStatus').textContent = BYD.i18n.t('pip.select_camera');
            document.getElementById('pipDot').classList.remove('live');
            this.showPlaceholder(BYD.i18n.t('pip.select_a_camera'));
            return;
        }
        
        // Update button states
        document.querySelectorAll('.pip-cam-btn').forEach(b => b.classList.remove('active', 'loading'));
        if (btn) btn.classList.add('loading');
        
        this.currentViewMode = mode;
        
        // If already streaming, just switch the view mode
        if (this.streamStarted) {
            try {
                await fetch('/api/stream/view/' + mode);
                if (btn) {
                    btn.classList.remove('loading');
                    btn.classList.add('active');
                }
                document.getElementById('pipStatus').textContent = viewNames[mode] || BYD.i18n.t('pip.default_camera');
            } catch (e) {
                if (btn) btn.classList.remove('loading');
            }
            return;
        }

        // Start streaming
        await this.startStream(mode);

        if (btn) {
            btn.classList.remove('loading');
            btn.classList.add('active');
        }
        document.getElementById('pipStatus').textContent = viewNames[mode] || BYD.i18n.t('pip.default_camera');
    },
    
    /**
     * Start streaming with selected view mode
     */
    async startStream(viewMode) {
        this.showPlaceholder(BYD.i18n.t('pip.connecting'));
        
        try {
            // 1. Set view mode first
            await fetch('/api/stream/view/' + viewMode);
            
            // 2. Enable streaming
            await fetch('/api/stream/enable', { method: 'POST' });
            
            // 3. Initialize decoder and connect
            await this.initDecoder();
            
            this.streamStarted = true;
        } catch (e) {
            console.error('[PiP] Failed to start stream:', e);
            this.showPlaceholder(BYD.i18n.t('pip.connection_failed'));
        }
    },
    
    /**
     * Initialize decoder - prefer WebCodecs (SotaPlayer), fallback to JMuxer/Broadway
     */
    async initDecoder() {
        // Try WebCodecs first (SotaPlayer)
        if ('VideoDecoder' in window && typeof SotaPlayer !== 'undefined') {
            return this.initSotaPlayer();
        }
        
        // Fallback based on platform
        if (this.isIOS) {
            return await this.initBroadway();
        } else {
            return this.initJMuxer();
        }
    },
    
    /**
     * Initialize SotaPlayer (WebCodecs) - Best quality
     */
    initSotaPlayer() {
        this.decoderMode = 'webcodecs';
        
        const canvas = document.getElementById('pip_sota_canvas');
        if (!canvas) return this.initJMuxer();
        
        // Hide other players, show canvas
        document.getElementById('pip_hw_player').style.display = 'none';
        document.getElementById('pip_sw_container').style.display = 'none';
        canvas.style.display = 'block';
        document.getElementById('pipPlaceholder').style.display = 'none';
        
        // Build WebSocket URL. Append JWT as ?token= so tunnels work — the
        // browser WebSocket API can't set headers, and SameSite policies
        // through reverse proxies routinely strip the byd_session cookie.
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        let url = `${protocol}//${window.location.host}/ws`;
        if (typeof BYDAuth !== 'undefined') {
            const wsToken = BYDAuth.getToken();
            if (wsToken) url += `?token=${encodeURIComponent(wsToken)}`;
        }

        // Create and start SotaPlayer
        if (this.sotaPlayer) {
            this.sotaPlayer.stop();
        }

        this.sotaPlayer = new SotaPlayer(canvas, url);
        this.sotaPlayer.onConnected = () => {
            document.getElementById('pipDot').classList.add('live');
        };
        this.sotaPlayer.onDisconnected = () => {
            document.getElementById('pipDot').classList.remove('live');
            // Reconnect if still visible and streaming
            if (this.isVisible && this.streamStarted) {
                setTimeout(() => {
                    if (this.isVisible && this.streamStarted) {
                        if (this.sotaPlayer) this.sotaPlayer.start();
                    }
                }, 2000);
            }
        };
        this.sotaPlayer.start();
        
        // Show decoder badge
        const badge = document.getElementById('pipDecoderBadge');
        if (badge) {
            badge.textContent = 'WebCodecs';
            badge.style.display = 'block';
        }
        
        return true;
    },
    
    /**
     * Initialize JMuxer (GPU) - Fallback for non-WebCodecs browsers
     */
    initJMuxer() {
        this.decoderMode = 'jmuxer';
        const video = document.getElementById('pip_hw_player');
        if (!video || !window.JMuxer || !window.MediaSource) {
            return this.initBroadway();
        }
        
        try {
            // Hide other players
            document.getElementById('pip_sota_canvas').style.display = 'none';
            document.getElementById('pip_sw_container').style.display = 'none';
            
            this.jmuxer = new JMuxer({
                node: 'pip_hw_player',
                mode: 'video',
                flushingTime: 0,
                fps: 15,
                debug: false,
                onReady: () => {
                    video.style.display = 'block';
                    document.getElementById('pipPlaceholder').style.display = 'none';
                    video.play().catch(() => {});
                }
            });
            
            // Connect WebSocket for JMuxer
            this.connectLegacyWebSocket();
            
            const badge = document.getElementById('pipDecoderBadge');
            if (badge) {
                badge.textContent = 'JMuxer';
                badge.style.display = 'block';
            }
            return true;
        } catch (e) {
            return this.initBroadway();
        }
    },
    
    /**
     * Initialize Broadway (CPU) - Fallback for iOS
     */
    async initBroadway() {
        this.decoderMode = 'broadway';
        
        if (typeof Player === 'undefined') {
            this.showPlaceholder(BYD.i18n.t('pip.decoder_not_loaded'));
            return false;
        }
        
        try {
            // Hide other players
            document.getElementById('pip_sota_canvas').style.display = 'none';
            document.getElementById('pip_hw_player').style.display = 'none';
            
            const baseUrl = new URL('../shared/', window.location.href).href;
            const wasmUrl = baseUrl + 'avc.wasm';
            const decoderUrl = baseUrl + 'Decoder.js';
            
            const response = await fetch(decoderUrl);
            let decoderCode = await response.text();
            decoderCode = decoderCode.replace(/["']avc\.wasm["']/g, `"${wasmUrl}"`);
            
            const locateFileInjection = `
                var Module = Module || {};
                Module['locateFile'] = function(path) {
                    if (path.endsWith('.wasm')) return '${wasmUrl}';
                    return path;
                };
            `;
            decoderCode = locateFileInjection + decoderCode;
            
            const blob = new Blob([decoderCode], { type: 'application/javascript' });
            const workerUrl = URL.createObjectURL(blob);
            
            this.broadwayPlayer = new Player({
                useWorker: true,
                workerFile: workerUrl,
                webgl: true,
                size: { width: 1280, height: 960 }
            });
            
            const container = document.getElementById('pip_sw_container');
            if (container) {
                container.innerHTML = '';
                container.appendChild(this.broadwayPlayer.canvas);
                container.style.display = 'block';
                this.broadwayPlayer.canvas.style.width = '100%';
                this.broadwayPlayer.canvas.style.height = '100%';
                this.broadwayPlayer.canvas.style.objectFit = 'contain';
            }
            
            document.getElementById('pipPlaceholder').style.display = 'none';
            
            // Connect WebSocket for Broadway
            this.connectLegacyWebSocket();
            
            const badge = document.getElementById('pipDecoderBadge');
            if (badge) {
                badge.textContent = 'Broadway';
                badge.style.display = 'block';
            }
            return true;
        } catch (e) {
            console.error('[PiP] Broadway init failed:', e);
            this.showPlaceholder(BYD.i18n.t('pip.decoder_failed'));
            return false;
        }
    },
    
    /**
     * Connect WebSocket for legacy decoders (JMuxer/Broadway)
     */
    connectLegacyWebSocket() {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) return;

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        let wsUrl = `${protocol}//${window.location.host}/ws`;
        if (typeof BYDAuth !== 'undefined') {
            const wsToken = BYDAuth.getToken();
            if (wsToken) wsUrl += `?token=${encodeURIComponent(wsToken)}`;
        }

        try {
            this.ws = new WebSocket(wsUrl);
            this.ws.binaryType = 'arraybuffer';
            
            this.ws.onopen = () => {
                document.getElementById('pipDot').classList.add('live');
            };
            
            this.ws.onmessage = (event) => {
                if (!event.data) return;
                const data = new Uint8Array(event.data);
                
                if (this.decoderMode === 'broadway' && this.broadwayPlayer) {
                    this.broadwayPlayer.decode(data);
                } else if (this.decoderMode === 'jmuxer' && this.jmuxer) {
                    this.jmuxer.feed({ video: data });
                }
            };
            
            this.ws.onclose = () => {
                this.ws = null;
                document.getElementById('pipDot').classList.remove('live');
                
                // Reconnect if still visible and streaming
                if (this.isVisible && this.streamStarted) {
                    setTimeout(() => this.connectLegacyWebSocket(), 2000);
                }
            };
        } catch (e) {
            this.showPlaceholder(BYD.i18n.t('pip.connection_failed'));
        }
    },

    /**
     * Stop streaming
     */
    stopStream() {
        // Stop SotaPlayer
        if (this.sotaPlayer) {
            this.sotaPlayer.stop();
            this.sotaPlayer = null;
        }
        
        // Stop legacy WebSocket
        if (this.ws) {
            this.ws.onclose = null;
            this.ws.close();
            this.ws = null;
        }
        
        // Stop JMuxer
        if (this.jmuxer) {
            try { this.jmuxer.destroy(); } catch (e) {}
            this.jmuxer = null;
        }
        
        // Stop Broadway
        if (this.broadwayPlayer) {
            this.broadwayPlayer = null;
        }
        
        this.decoderMode = null;
        this.streamStarted = false;
        
        // Hide decoder badge
        const badge = document.getElementById('pipDecoderBadge');
        if (badge) badge.style.display = 'none';
    },
    
    /**
     * Show placeholder
     */
    showPlaceholder(msg) {
        const el = document.getElementById('pipPlaceholder');
        if (el) {
            el.style.display = 'flex';
            el.querySelector('span').textContent = msg;
        }
        // Hide video elements
        const canvas = document.getElementById('pip_sota_canvas');
        const video = document.getElementById('pip_hw_player');
        const sw = document.getElementById('pip_sw_container');
        if (canvas) canvas.style.display = 'none';
        if (video) video.style.display = 'none';
        if (sw) sw.style.display = 'none';
    },
    
    /**
     * Toggle size
     */
    toggleSize() {
        if (this.isExpanded) {
            this.container.classList.remove('expanded');
            this.isExpanded = false;
        } else {
            this.container.classList.add('expanded');
            this.container.classList.remove('minimized');
            this.isExpanded = true;
            this.isMinimized = false;
        }
    },
    
    /**
     * Minimize
     */
    minimize() {
        this.container.classList.toggle('minimized');
        this.isMinimized = !this.isMinimized;
        if (this.isMinimized) {
            this.container.classList.remove('expanded');
            this.isExpanded = false;
        }
    },
    
    /**
     * Create floating toggle button
     */
    createToggleButton() {
        if (document.getElementById('pipToggleBtn')) return;
        
        const btn = document.createElement('button');
        btn.id = 'pipToggleBtn';
        btn.className = 'pip-toggle-btn';
        btn.title = BYD.i18n.t('pip.title_toggle');
        btn.innerHTML = `
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/>
                <circle cx="12" cy="13" r="3"/>
            </svg>
        `;
        btn.onclick = () => this.toggle();
        document.body.appendChild(btn);
    },
    
    /**
     * Initialize PiP module (call on settings pages)
     */
    init() {
        this.createToggleButton();
    }
};
