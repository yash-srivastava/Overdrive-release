/**
 * BYD Champ - Shared Utilities
 * Extensible for future features (radar, bodywork, sentry, etc.)
 */

const BYD = {
    // ==================== CONFIGURATION ====================
    config: {
        apiBase: '',  // Same origin
        refreshInterval: 5000,
        streamRefreshInterval: 100,
        toastDuration: 3000
    },

    // ==================== STATE ====================
    state: {
        cameras: {},
        battery: { voltage: 0, level: 'UNKNOWN' },
        acc: 'UNKNOWN',
        deviceId: 'unknown',
        sentry: { armed: false },
        radar: {},
        fullscreenCamera: null
    },

    // ==================== API CALLS ====================
    api: {
        async get(endpoint) {
            try {
                const res = await fetch(BYD.config.apiBase + endpoint);
                return await res.json();
            } catch (e) {
                console.error('API GET error:', endpoint, e);
                return null;
            }
        },

        async post(endpoint, data = {}) {
            try {
                const res = await fetch(BYD.config.apiBase + endpoint, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                return await res.json();
            } catch (e) {
                console.error('API POST error:', endpoint, e);
                return null;
            }
        },

        // Camera controls
        async startRecording(camId) {
            return this.get(`/api/start/${camId}`);
        },

        async startViewing(camId) {
            return this.get(`/api/view/${camId}`);
        },

        async stopCamera(camId) {
            return this.get(`/api/stop/${camId}`);
        },

        async stopAll() {
            return this.get('/api/stopall');
        },

        async getStatus() {
            return this.get('/status');
        },

        // Surveillance API
        async getSurveillanceConfig() {
            return this.get('/api/surveillance/config');
        },

        async getSurveillanceStatus() {
            return this.get('/api/surveillance/status');
        },

        async setSurveillanceConfig(config) {
            return this.post('/api/surveillance/config', config);
        },

        async enableSurveillance() {
            return this.post('/api/surveillance/enable');
        },

        async disableSurveillance() {
            return this.post('/api/surveillance/disable');
        },

        // Future: Get radar data
        async getRadar() {
            return this.get('/api/radar');
        }
    },

    // ==================== UI HELPERS ====================
    ui: {
        // Update battery display
        updateBattery(voltage, level) {
            const el = document.getElementById('batteryValue');
            if (!el) return;

            let color, icon;
            if (voltage < 11.5) {
                color = 'critical';
                icon = '⚠️';
            } else if (voltage < 12.0) {
                color = 'low';
                icon = '🔋';
            } else {
                color = 'ok';
                icon = '🔋';
            }

            el.className = 'value ' + color;
            el.textContent = `${icon} ${voltage.toFixed(1)}V (${level})`;
        },

        // Update camera card state
        updateCameraCard(camId, isRecording, isViewing) {
            const card = document.getElementById(`card${camId}`);
            const status = document.getElementById(`st${camId}`);
            const viewBtn = document.getElementById(`view${camId}`);
            const recBtn = document.getElementById(`rec${camId}`);
            const box = document.getElementById(`box${camId}`);

            if (!card) return;

            const isActive = isRecording || isViewing;

            // Update card class
            card.className = 'cam-card' + 
                (isRecording ? ' recording' : '') + 
                (isViewing ? ' viewing' : '');

            // Update status badge
            if (status) {
                status.className = 'cam-status ' + (isRecording ? 'rec' : (isViewing ? 'view' : 'off'));
                status.textContent = isRecording ? '● REC' : (isViewing ? '● VIEW' : '○ OFF');
            }

            // Update buttons
            if (viewBtn) {
                viewBtn.className = 'btn btn-view' + (isViewing ? ' on' : '');
                viewBtn.textContent = isViewing ? '⏹ Stop' : '👁 View';
                viewBtn.disabled = isRecording;
            }

            if (recBtn) {
                recBtn.className = 'btn btn-rec' + (isRecording ? ' on' : '');
                recBtn.textContent = isRecording ? '⏹ Stop' : '⏺ Record';
                recBtn.disabled = isViewing;
            }

            // Update video box
            if (box) {
                if (isActive && !box.querySelector('img')) {
                    box.innerHTML = `<img src="/stream/${camId}" id="img${camId}" alt="Camera ${camId}"/>`;
                } else if (!isActive && box.querySelector('img')) {
                    box.innerHTML = '<div class="placeholder">Tap View or Record</div>';
                }
            }
        },

        // Toggle fullscreen for camera
        toggleFullscreen(camId) {
            const box = document.getElementById(`box${camId}`);
            const closeBtn = document.getElementById('closeFs');

            if (BYD.state.fullscreenCamera === camId) {
                // Exit fullscreen
                box.classList.remove('fullscreen');
                if (closeBtn) closeBtn.style.display = 'none';
                BYD.state.fullscreenCamera = null;
            } else {
                // Exit previous fullscreen
                if (BYD.state.fullscreenCamera) {
                    const prevBox = document.getElementById(`box${BYD.state.fullscreenCamera}`);
                    if (prevBox) prevBox.classList.remove('fullscreen');
                }
                // Enter fullscreen
                box.classList.add('fullscreen');
                if (closeBtn) closeBtn.style.display = 'block';
                BYD.state.fullscreenCamera = camId;
            }
        },

        exitFullscreen() {
            if (BYD.state.fullscreenCamera) {
                const box = document.getElementById(`box${BYD.state.fullscreenCamera}`);
                const closeBtn = document.getElementById('closeFs');
                if (box) box.classList.remove('fullscreen');
                if (closeBtn) closeBtn.style.display = 'none';
                BYD.state.fullscreenCamera = null;
            }
        },

        // Show toast notification
        toast(message, type = 'info') {
            let container = document.getElementById('toastContainer');
            if (!container) {
                container = document.createElement('div');
                container.id = 'toastContainer';
                container.className = 'toast-container';
                document.body.appendChild(container);
            }

            const toast = document.createElement('div');
            toast.className = `toast ${type}`;
            toast.textContent = message;
            container.appendChild(toast);

            setTimeout(() => {
                toast.style.opacity = '0';
                setTimeout(() => toast.remove(), 300);
            }, BYD.config.toastDuration);
        },

        // Set loading state on button
        setButtonLoading(btn, loading) {
            if (loading) {
                btn.disabled = true;
                btn.dataset.originalText = btn.textContent;
                btn.textContent = '...';
            } else {
                btn.disabled = false;
                btn.textContent = btn.dataset.originalText || btn.textContent;
            }
        }
    },

    // ==================== CAMERA CONTROLS ====================
    camera: {
        async toggleView(camId, event) {
            if (event) event.stopPropagation();
            
            const btn = document.getElementById(`view${camId}`);
            const isOn = btn && btn.classList.contains('on');
            
            if (btn) BYD.ui.setButtonLoading(btn, true);
            
            if (isOn) {
                await BYD.api.stopCamera(camId);
            } else {
                await BYD.api.startViewing(camId);
            }
            
            setTimeout(() => BYD.refresh(), 800);
        },

        async toggleRecord(camId, event) {
            if (event) event.stopPropagation();
            
            const btn = document.getElementById(`rec${camId}`);
            const isOn = btn && btn.classList.contains('on');
            
            if (btn) BYD.ui.setButtonLoading(btn, true);
            
            if (isOn) {
                await BYD.api.stopCamera(camId);
            } else {
                await BYD.api.startRecording(camId);
            }
            
            setTimeout(() => BYD.refresh(), 800);
        },

        async stopAll() {
            await BYD.api.stopAll();
            setTimeout(() => BYD.refresh(), 500);
        }
    },

    // ==================== SENTRY MODE ====================
    sentry: {
        config: {
            enabled: false,
            sensitivity: 'MEDIUM',
            noiseThreshold: 0.01,
            lightThreshold: 0.40,
            aiEnabled: true,
            aiConfidence: 0.60,
            detectPerson: true,
            detectCar: true,
            preEventBufferSeconds: 5,
            postEventBufferSeconds: 10
        },

        status: {
            enabled: false,
            active: false,
            recording: false,
            bufferFillPercent: 0,
            yoloLoaded: false
        },

        // Load config and status from server
        async load() {
            const [configRes, statusRes] = await Promise.all([
                BYD.api.getSurveillanceConfig(),
                BYD.api.getSurveillanceStatus()
            ]);

            if (configRes && configRes.success) {
                this.config = { ...this.config, ...configRes.config };
                this.updateUI();
            }

            if (statusRes && statusRes.success) {
                this.status = { ...this.status, ...statusRes.status };
                this.updateStatusUI();
            }
        },

        // Update UI from config
        updateUI() {
            const c = this.config;

            // Enable toggle
            const enabledEl = document.getElementById('sentryEnabled');
            if (enabledEl) enabledEl.checked = c.enabled;

            // Sensitivity buttons
            document.querySelectorAll('.btn-toggle[data-value]').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.value === c.sensitivity);
            });

            // Sliders
            this.setSlider('noise', Math.round(c.noiseThreshold * 100));
            this.setSlider('light', Math.round(c.lightThreshold * 100));
            this.setSlider('aiConf', Math.round(c.aiConfidence * 100));
            this.setSlider('preBuffer', c.preEventBufferSeconds);
            this.setSlider('postBuffer', c.postEventBufferSeconds);

            // AI settings
            const aiEnabledEl = document.getElementById('aiEnabled');
            if (aiEnabledEl) aiEnabledEl.checked = c.aiEnabled;

            const detectPersonEl = document.getElementById('detectPerson');
            if (detectPersonEl) detectPersonEl.checked = c.detectPerson;

            const detectCarEl = document.getElementById('detectCar');
            if (detectCarEl) detectCarEl.checked = c.detectCar;
        },

        // Update status display
        updateStatusUI() {
            const s = this.status;

            const statusText = document.getElementById('sentryStatusText');
            const statusDiv = document.getElementById('sentryStatus');
            
            if (statusText && statusDiv) {
                let text, className;
                if (s.recording) {
                    text = '🔴 Recording';
                    className = 'recording';
                } else if (s.active) {
                    text = '✅ Active (monitoring)';
                    className = 'active';
                } else if (s.enabled) {
                    text = '⏸️ Enabled (waiting for ACC OFF)';
                    className = 'enabled';
                } else {
                    text = 'Disabled';
                    className = 'disabled';
                }
                statusText.textContent = text;
                statusDiv.className = 'sentry-status ' + className;
            }

            const bufferEl = document.getElementById('bufferFill');
            if (bufferEl) bufferEl.textContent = Math.round(s.bufferFillPercent * 100) + '%';

            const yoloEl = document.getElementById('yoloStatus');
            if (yoloEl) yoloEl.textContent = s.yoloLoaded ? '✅ Loaded' : '❌ Not Loaded';
        },

        setSlider(name, value) {
            const slider = document.getElementById(name + 'Slider');
            const valueEl = document.getElementById(name + 'Value');
            
            if (slider) slider.value = value;
            if (valueEl) {
                if (name === 'preBuffer' || name === 'postBuffer') {
                    valueEl.textContent = value + 's';
                } else {
                    valueEl.textContent = value + '%';
                }
            }
        },

        updateSliderValue(name, value) {
            const valueEl = document.getElementById(name + 'Value');
            if (valueEl) {
                if (name === 'preBuffer' || name === 'postBuffer') {
                    valueEl.textContent = value + 's';
                } else {
                    valueEl.textContent = value + '%';
                }
            }
        },

        setSensitivity(level) {
            this.config.sensitivity = level;
            document.querySelectorAll('.btn-toggle[data-value]').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.value === level);
            });
        },

        // Toggle enabled state
        async toggleEnabled() {
            const enabledEl = document.getElementById('sentryEnabled');
            const enabled = enabledEl ? enabledEl.checked : false;

            const res = enabled 
                ? await BYD.api.enableSurveillance()
                : await BYD.api.disableSurveillance();

            if (res && res.success) {
                this.config.enabled = enabled;
                BYD.ui.toast(enabled ? 'Surveillance enabled' : 'Surveillance disabled', 'success');
                this.load(); // Refresh status
            } else {
                BYD.ui.toast('Failed to update surveillance', 'error');
                if (enabledEl) enabledEl.checked = !enabled; // Revert
            }
        },

        // Collect config from UI
        collectConfig() {
            var _el;
            return {
                sensitivity: this.config.sensitivity,
                noiseThreshold: parseInt(((_el = document.getElementById('noiseSlider')) ? _el.value : 1) || 1) / 100,
                lightThreshold: parseInt(((_el = document.getElementById('lightSlider')) ? _el.value : 40) || 40) / 100,
                aiEnabled: ((_el = document.getElementById('aiEnabled')) ? _el.checked : true),
                aiConfidence: parseInt(((_el = document.getElementById('aiConfSlider')) ? _el.value : 60) || 60) / 100,
                detectPerson: ((_el = document.getElementById('detectPerson')) ? _el.checked : true),
                detectCar: ((_el = document.getElementById('detectCar')) ? _el.checked : true),
                preEventBufferSeconds: parseInt(((_el = document.getElementById('preBufferSlider')) ? _el.value : 5) || 5),
                postEventBufferSeconds: parseInt(((_el = document.getElementById('postBufferSlider')) ? _el.value : 10) || 10)
            };
        },

        // Save config to server
        async saveConfig() {
            const config = this.collectConfig();
            const res = await BYD.api.setSurveillanceConfig(config);

            if (res && res.success) {
                this.config = { ...this.config, ...config };
                BYD.ui.toast('Settings saved', 'success');
            } else {
                BYD.ui.toast('Failed to save settings', 'error');
            }
        },

        // Update config (called on checkbox change)
        updateConfig() {
            // Just update local state, save button will persist
        }
    },

    // ==================== SETTINGS ====================
    settings: {
        recordingQuality: 'NORMAL',
        streamingQuality: 'LQ',

        qualityLabels: {
            recording: {
                LOW: 'LOW (10fps, 2Mbps)',
                REDUCED: 'REDUCED (15fps, 2.5Mbps)',
                NORMAL: 'NORMAL (25fps, 4Mbps)'
            },
            streaming: {
                ULTRA_LOW: 'ULTRA_LOW (5fps, 400kbps)',
                LQ: 'LQ (10fps, 800kbps)',
                HQ: 'HQ (15fps, 2Mbps)'
            }
        },

        async load() {
            const res = await BYD.api.get('/api/settings/quality');
            if (res && res.success) {
                this.recordingQuality = res.recordingQuality || 'NORMAL';
                this.streamingQuality = res.streamingQuality || 'LQ';
                this.updateUI();
            }
        },

        updateUI() {
            // Update recording quality buttons
            document.querySelectorAll('[data-rec-quality]').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.recQuality === this.recordingQuality);
            });

            // Update streaming quality buttons
            document.querySelectorAll('[data-stream-quality]').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.streamQuality === this.streamingQuality);
            });

            // Update current settings display
            const recEl = document.getElementById('currentRecQuality');
            const streamEl = document.getElementById('currentStreamQuality');
            if (recEl) recEl.textContent = this.qualityLabels.recording[this.recordingQuality];
            if (streamEl) streamEl.textContent = this.qualityLabels.streaming[this.streamingQuality];
        },

        setRecordingQuality(quality) {
            this.recordingQuality = quality;
            document.querySelectorAll('[data-rec-quality]').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.recQuality === quality);
            });
            const recEl = document.getElementById('currentRecQuality');
            if (recEl) recEl.textContent = this.qualityLabels.recording[quality];
        },

        setStreamingQuality(quality) {
            this.streamingQuality = quality;
            document.querySelectorAll('[data-stream-quality]').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.streamQuality === quality);
            });
            const streamEl = document.getElementById('currentStreamQuality');
            if (streamEl) streamEl.textContent = this.qualityLabels.streaming[quality];
        },

        async save() {
            const res = await BYD.api.post('/api/settings/quality', {
                recordingQuality: this.recordingQuality,
                streamingQuality: this.streamingQuality
            });

            if (res && res.success) {
                BYD.ui.toast('Quality settings saved', 'success');
            } else {
                BYD.ui.toast('Failed to save settings', 'error');
            }
        }
    },

    // ==================== REFRESH ====================
    async refresh() {
        const data = await BYD.api.getStatus();
        if (!data) return;

        const recording = data.recording || [];
        const viewing = data.viewing || [];
        const battery = data.battery || {};
        const recordingStatus = data.recordingStatus || {};

        // Update battery — same 3-way cascade as core.js's status poller:
        //  1. Live car_service reading (recordingStatus.voltage12v) — added for
        //     platforms (e.g. DiLink 5 / Sealion 7) where battery.voltage is dead.
        //  2. The ORIGINAL battery.voltage path, fully intact — the fallback for
        //     any platform where that already works fine and the new one doesn't.
        //  3. Neither valid this poll: skip the update entirely and leave whatever
        //     updateBattery last painted, rather than flickering to a false 0.0V.
        BYD.state.battery = battery;
        const carSvcVoltage = Number(recordingStatus.voltage12v);
        const carSvcFresh = isFinite(carSvcVoltage) && carSvcVoltage > 0;
        const stockVoltage = Number(battery.voltage);
        const stockFresh = battery.available !== false
            && battery.isStale !== true
            && isFinite(stockVoltage)
            && stockVoltage > 0;
        if (carSvcFresh) {
            BYD.ui.updateBattery(carSvcVoltage, battery.level || 'UNKNOWN');
        } else if (stockFresh) {
            BYD.ui.updateBattery(stockVoltage, battery.level || 'UNKNOWN');
        }

        // Update device ID if available
        if (data.deviceId) {
            BYD.state.deviceId = data.deviceId;
            const deviceEl = document.getElementById('deviceId');
            if (deviceEl) deviceEl.textContent = data.deviceId;
        }

        // Update ACC status if available
        if (data.acc !== undefined) {
            BYD.state.acc = data.acc;
            const accEl = document.getElementById('accValue');
            if (accEl) {
                accEl.className = 'value ' + (data.acc ? 'on' : 'off');
                accEl.textContent = data.acc ? 'ON' : 'OFF';
            }
        }

        // Update all camera cards
        for (let i = 1; i <= 4; i++) {
            const isRec = recording.includes(i);
            const isView = viewing.includes(i);
            BYD.ui.updateCameraCard(i, isRec, isView);
        }
    },

    // ==================== INITIALIZATION ====================
    init() {
        // Initial refresh
        this.refresh();

        // Auto-refresh (skip if fullscreen to avoid stream interruption)
        setInterval(() => {
            if (!BYD.state.fullscreenCamera) {
                this.refresh();
            }
        }, this.config.refreshInterval);

        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.ui.exitFullscreen();
            }
        });

        console.log('BYD Champ initialized');
    }
};

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BYD;
}
