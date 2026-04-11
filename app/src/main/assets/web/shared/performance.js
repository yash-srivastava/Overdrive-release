/**
 * BYD Champ - Performance Monitor Module
 * SOTA real-time performance visualization with smooth canvas charts
 * Features: Interactive crosshair tooltips, smooth animations, value tracking
 * 
 * ON-DEMAND ARCHITECTURE:
 * - Connects to backend when page loads, disconnects when leaving
 * - Sends heartbeats every 5 seconds to maintain connection
 * - Backend only polls CPU/GPU/Memory when clients are connected
 */

window.BYD = window.BYD || {};

BYD.performance = {
    // Configuration
    HISTORY_SIZE: 60,
    UPDATE_INTERVAL: 1000,
    SOC_UPDATE_INTERVAL: 60000, // SOC updates every minute
    HEARTBEAT_INTERVAL: 5000,   // Heartbeat every 5 seconds
    
    // State
    pollInterval: null,
    socPollInterval: null,
    heartbeatInterval: null,
    clientId: null,              // Assigned by server on connect
    isConnected: false,
    charts: {},
    history: {
        cpuSystem: [],
        cpuApp: [],
        memSystem: [],
        memApp: [],
        gpu: []
    },
    
    // SOC State
    socTimeRange: 72, // Default 3 days (72 hours)
    socData: {
        history: [],
        stats: {},
        sessions: []
    },
    
    // Interactive tooltip state
    tooltip: {
        visible: false,
        x: 0,
        y: 0,
        chartId: null,
        dataIndex: -1
    },
    
    // Colors
    colors: {
        system: '#00D4AA',
        app: '#0EA5E9',
        gpu: '#a855f7',
        soc: '#22c55e',
        charging: '#0EA5E9',
        grid: 'rgba(255, 255, 255, 0.06)',
        text: 'rgba(255, 255, 255, 0.4)',
        crosshair: 'rgba(255, 255, 255, 0.3)',
        tooltipBg: 'rgba(20, 20, 30, 0.95)',
        tooltipBorder: 'rgba(255, 255, 255, 0.1)'
    },
    
    async init() {
        console.log('[Performance] Initializing...');
        
        // Initialize history arrays
        for (let i = 0; i < this.HISTORY_SIZE; i++) {
            this.history.cpuSystem.push(0);
            this.history.cpuApp.push(0);
            this.history.memSystem.push(0);
            this.history.memApp.push(0);
            this.history.gpu.push(0);
        }
        
        // Initialize charts
        this.initCharts();
        
        // SOTA: Connect to backend (starts monitoring if first client)
        await this.connect();
        
        // Start polling for real-time metrics
        this.startPolling();
        
        // Fetch initial SOC data
        await this.fetchSocHistory();
        
        // Fetch initial battery health data
        await this.fetchBatteryHealth();
        
        // Start SOC polling (less frequent)
        this.socPollInterval = setInterval(() => this.fetchSocHistory(), this.SOC_UPDATE_INTERVAL);
        
        // Battery health polling (every 2 minutes — same as SOC)
        this.batteryHealthPollInterval = setInterval(() => this.fetchBatteryHealth(), this.SOC_UPDATE_INTERVAL);
        
        // Handle resize
        window.addEventListener('resize', () => this.resizeCharts());
        
        // SOTA: Handle page visibility and unload for clean disconnect
        this.setupLifecycleHandlers();
        
        console.log('[Performance] Initialized');
    },
    
    /**
     * SOTA: Setup page lifecycle handlers for clean connect/disconnect
     */
    setupLifecycleHandlers() {
        // Handle page unload (close tab, navigate away)
        window.addEventListener('beforeunload', () => {
            this.disconnect();
        });
        
        // Handle visibility change (tab switch, minimize)
        document.addEventListener('visibilitychange', () => {
            if (document.hidden) {
                // Page hidden - disconnect to save resources
                console.log('[Performance] Page hidden - disconnecting');
                this.disconnect();
            } else {
                // Page visible again - reconnect
                console.log('[Performance] Page visible - reconnecting');
                this.connect();
            }
        });
        
        // Handle page hide (mobile browsers)
        window.addEventListener('pagehide', () => {
            this.disconnect();
        });
    },
    
    /**
     * SOTA: Connect to backend - registers client and starts monitoring
     */
    async connect() {
        if (this.isConnected) return;
        
        try {
            const res = await fetch('/api/performance/connect', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ clientId: this.clientId })
            });
            
            if (res.ok) {
                const data = await res.json();
                this.clientId = data.clientId;
                this.isConnected = true;
                
                // Start heartbeat
                this.startHeartbeat();
                
                console.log('[Performance] Connected as:', this.clientId, 
                    '(active clients:', data.activeClients + ')');
            }
        } catch (e) {
            console.error('[Performance] Connect failed:', e);
        }
    },
    
    /**
     * SOTA: Disconnect from backend - unregisters client
     */
    async disconnect() {
        if (!this.isConnected || !this.clientId) return;
        
        // Stop heartbeat
        this.stopHeartbeat();
        
        try {
            // Use sendBeacon for reliable delivery during page unload
            const data = JSON.stringify({ clientId: this.clientId });
            if (navigator.sendBeacon) {
                navigator.sendBeacon('/api/performance/disconnect', data);
            } else {
                // Fallback to fetch (may not complete during unload)
                fetch('/api/performance/disconnect', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: data,
                    keepalive: true
                });
            }
            console.log('[Performance] Disconnected:', this.clientId);
        } catch (e) {
            console.error('[Performance] Disconnect failed:', e);
        }
        
        this.isConnected = false;
    },
    
    /**
     * SOTA: Start heartbeat to keep connection alive
     */
    startHeartbeat() {
        if (this.heartbeatInterval) return;
        
        this.heartbeatInterval = setInterval(async () => {
            if (!this.clientId) return;
            
            try {
                await fetch('/api/performance/heartbeat', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ clientId: this.clientId })
                });
            } catch (e) {
                console.warn('[Performance] Heartbeat failed:', e);
            }
        }, this.HEARTBEAT_INTERVAL);
    },
    
    /**
     * SOTA: Stop heartbeat
     */
    stopHeartbeat() {
        if (this.heartbeatInterval) {
            clearInterval(this.heartbeatInterval);
            this.heartbeatInterval = null;
        }
    },
    
    initCharts() {
        this.charts.cpu = this.createChart('cpuChart', 'cpu');
        this.charts.mem = this.createChart('memChart', 'mem');
        this.charts.gpu = this.createChart('gpuChart', 'gpu');
        this.charts.soc = this.createChart('socChart', 'soc');
        this.charts.voltage = this.createChart('voltageChart', 'voltage');
        this.charts.thermal = this.createChart('thermalChart', 'thermal');

        // Setup IntersectionObserver for charts that may be below the fold on mobile
        this._setupVisibilityObserver();
    },

    /**
     * Track canvases that need re-rendering when they become visible.
     * On mobile, charts below the fold have 0x0 dimensions at render time.
     */
    _pendingVisibilityRenders: {},

    _setupVisibilityObserver() {
        if (!('IntersectionObserver' in window)) return;

        this._visibilityObserver = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    const canvasId = entry.target.id;
                    const chartType = this._pendingVisibilityRenders[canvasId];
                    if (chartType) {
                        delete this._pendingVisibilityRenders[canvasId];
                        // Small delay to ensure layout is complete
                        requestAnimationFrame(() => this.renderChartByType(chartType));
                    }
                }
            });
        }, { threshold: 0.1 });
    },

    _scheduleVisibilityRender(canvasId, chartType) {
        if (this._pendingVisibilityRenders[canvasId]) return; // already scheduled
        this._pendingVisibilityRenders[canvasId] = chartType;

        const canvas = document.getElementById(canvasId);
        if (canvas && this._visibilityObserver) {
            this._visibilityObserver.observe(canvas);
        }
    },
    
    createChart(canvasId, chartType) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return null;
        
        const ctx = canvas.getContext('2d');
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        
        canvas.width = rect.width * dpr;
        canvas.height = rect.height * dpr;
        ctx.scale(dpr, dpr);
        
        // Setup interactive events (only once per canvas)
        if (!canvas._interactionSetup) {
            this.setupChartInteraction(canvas, chartType);
            canvas._interactionSetup = true;
        }
        
        return { canvas, ctx, width: rect.width, height: rect.height, type: chartType };
    },
    
    /**
     * SOTA: Setup mouse/touch interaction for crosshair tooltips
     */
    setupChartInteraction(canvas, chartType) {
        const self = this;
        
        // Mouse move handler
        canvas.addEventListener('mousemove', (e) => {
            const rect = canvas.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            self.handleChartHover(chartType, x, y, rect.width, rect.height);
        });
        
        // Mouse leave handler
        canvas.addEventListener('mouseleave', () => {
            self.tooltip.visible = false;
            self.tooltip.chartId = null;
            self.renderChartByType(chartType);
        });
        
        // Touch support - need touchstart to initiate tracking
        canvas.addEventListener('touchstart', (e) => {
            e.preventDefault();
            const rect = canvas.getBoundingClientRect();
            const touch = e.touches[0];
            const x = touch.clientX - rect.left;
            const y = touch.clientY - rect.top;
            self.handleChartHover(chartType, x, y, rect.width, rect.height);
        }, { passive: false });
        
        canvas.addEventListener('touchmove', (e) => {
            e.preventDefault();
            const rect = canvas.getBoundingClientRect();
            const touch = e.touches[0];
            const x = touch.clientX - rect.left;
            const y = touch.clientY - rect.top;
            self.handleChartHover(chartType, x, y, rect.width, rect.height);
        }, { passive: false });
        
        canvas.addEventListener('touchend', () => {
            // Keep tooltip visible for a moment on touch end, then hide
            setTimeout(() => {
                self.tooltip.visible = false;
                self.tooltip.chartId = null;
                self.renderChartByType(chartType);
            }, 1500); // Keep visible for 1.5s after touch ends
        });
        
        canvas.addEventListener('touchcancel', () => {
            self.tooltip.visible = false;
            self.tooltip.chartId = null;
            self.renderChartByType(chartType);
        });
    },
    
    /**
     * Handle hover on chart - calculate data index and show tooltip
     */
    handleChartHover(chartType, mouseX, mouseY, width, height) {
        const padding = { top: 10, right: 10, bottom: 25, left: 40 };
        const chartWidth = width - padding.left - padding.right;
        
        // Check if mouse is within chart area
        if (mouseX < padding.left || mouseX > width - padding.right) {
            this.tooltip.visible = false;
            this.renderChartByType(chartType);
            return;
        }
        
        // Calculate data index based on mouse position
        const relativeX = mouseX - padding.left;
        let dataIndex;
        
        if (chartType === 'soc') {
            // SOC chart uses time-based positioning
            const history = this.socData.history;
            if (!history || history.length < 2) return;
            
            const timeStart = history[0].t;
            const timeEnd = history[history.length - 1].t;
            const timeRange = timeEnd - timeStart;
            const targetTime = timeStart + (relativeX / chartWidth) * timeRange;
            
            // Find closest data point
            dataIndex = this.findClosestTimeIndex(history, targetTime);
        } else if (chartType === 'voltage') {
            const d = this.batteryHealthData;
            if (!d || !d.voltageHistory || d.voltageHistory.length < 2) return;
            const history = d.voltageHistory;
            const timeStart = history[0].t;
            const timeEnd = history[history.length - 1].t;
            const targetTime = timeStart + (relativeX / chartWidth) * (timeEnd - timeStart);
            dataIndex = this.findClosestTimeIndex(history, targetTime);
        } else if (chartType === 'thermal') {
            const d = this.batteryHealthData;
            if (!d || !d.thermalHistory || d.thermalHistory.length < 2) return;
            const history = d.thermalHistory;
            const timeStart = history[0].t;
            const timeEnd = history[history.length - 1].t;
            const targetTime = timeStart + (relativeX / chartWidth) * (timeEnd - timeStart);
            dataIndex = this.findClosestTimeIndex(history, targetTime);
        } else {
            // Real-time charts use index-based positioning
            const data = this.getChartData(chartType);
            if (!data || data.length === 0) return;
            
            dataIndex = Math.round((relativeX / chartWidth) * (data[0].length - 1));
            dataIndex = Math.max(0, Math.min(data[0].length - 1, dataIndex));
        }
        
        this.tooltip.visible = true;
        this.tooltip.chartId = chartType;
        this.tooltip.x = mouseX;
        this.tooltip.y = mouseY;
        this.tooltip.dataIndex = dataIndex;
        
        this.renderChartByType(chartType);
    },
    
    /**
     * Find closest index in time-series data
     */
    findClosestTimeIndex(history, targetTime) {
        let closest = 0;
        let minDiff = Math.abs(history[0].t - targetTime);
        
        for (let i = 1; i < history.length; i++) {
            const diff = Math.abs(history[i].t - targetTime);
            if (diff < minDiff) {
                minDiff = diff;
                closest = i;
            }
        }
        return closest;
    },
    
    /**
     * Get chart data arrays by type
     */
    getChartData(chartType) {
        switch (chartType) {
            case 'cpu':
                return [this.history.cpuSystem, this.history.cpuApp];
            case 'mem':
                return [this.history.memSystem, this.history.memApp];
            case 'gpu':
                return [this.history.gpu];
            default:
                return null;
        }
    },
    
    /**
     * Render specific chart by type
     */
    renderChartByType(chartType) {
        switch (chartType) {
            case 'cpu':
                this.renderChart(this.charts.cpu, [
                    { data: this.history.cpuSystem, color: this.colors.system, label: 'System' },
                    { data: this.history.cpuApp, color: this.colors.app, label: 'App' }
                ]);
                break;
            case 'mem':
                this.renderChart(this.charts.mem, [
                    { data: this.history.memSystem, color: this.colors.system, label: 'System' },
                    { data: this.history.memApp, color: this.colors.app, label: 'App' }
                ]);
                break;
            case 'gpu':
                this.renderChart(this.charts.gpu, [
                    { data: this.history.gpu, color: this.colors.gpu, label: 'GPU' }
                ]);
                break;
            case 'soc':
                this.renderSocChart();
                break;
            case 'voltage':
                this.renderVoltageChart();
                break;
            case 'thermal':
                this.renderThermalChart();
                break;
        }
    },
    
    resizeCharts() {
        Object.keys(this.charts).forEach(key => {
            const canvasId = key === 'cpu' ? 'cpuChart' : key === 'mem' ? 'memChart' : key === 'gpu' ? 'gpuChart' : key === 'soc' ? 'socChart' : key === 'voltage' ? 'voltageChart' : key === 'thermal' ? 'thermalChart' : key + 'Chart';
            const canvas = document.getElementById(canvasId);
            if (canvas) {
                const rect = canvas.getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0) {
                    this.charts[key] = this.createChart(canvasId, key);
                }
            }
        });
        this.renderAllCharts();
        this.renderSocChart();
        this.renderVoltageChart();
        this.renderThermalChart();
    },
    
    startPolling() {
        this.fetchData();
        this.pollInterval = setInterval(() => this.fetchData(), this.UPDATE_INTERVAL);
    },
    
    stopPolling() {
        if (this.pollInterval) {
            clearInterval(this.pollInterval);
            this.pollInterval = null;
        }
        if (this.socPollInterval) {
            clearInterval(this.socPollInterval);
            this.socPollInterval = null;
        }
        if (this.batteryHealthPollInterval) {
            clearInterval(this.batteryHealthPollInterval);
            this.batteryHealthPollInterval = null;
        }
        // SOTA: Disconnect from backend when stopping
        this.disconnect();
    },
    
    async fetchData() {
        try {
            // Try WebView bridge first
            if (typeof PerformanceBridge !== 'undefined') {
                const dataStr = PerformanceBridge.getPerformanceData();
                const data = JSON.parse(dataStr);
                this.updateUI(data);
                return;
            }
            
            // Fallback to HTTP API
            const res = await fetch('/api/performance');
            if (res.ok) {
                const data = await res.json();
                this.updateUI(data);
            }
        } catch (e) {
            console.error('[Performance] Fetch error:', e);
            // Update monitoring status
            this.setMonitoringStatus(false);
        }
    },
    
    updateUI(data) {
        if (!data || data.error) {
            this.setMonitoringStatus(false);
            return;
        }
        
        this.setMonitoringStatus(true);
        
        // Update CPU metrics
        if (data.cpu) {
            this.updateMetric('cpuValue', data.cpu.system, '');
            this.updateMetric('cpuAppValue', data.cpu.app, '');
            this.updateBar('cpuBar', data.cpu.app);  // Show app CPU on bar
            this.updateMetric('cpuFreq', data.cpu.freqMhz, ' MHz');
            this.updateMetric('cpuTemp', data.cpu.tempC, '°C');
            this.setCardStatus('cpuCard', data.cpu.app);  // Status based on app CPU
            
            // Update history
            this.pushHistory('cpuSystem', data.cpu.system);
            this.pushHistory('cpuApp', data.cpu.app);
        }
        
        // Update Memory metrics
        if (data.memory) {
            this.updateMetric('memValue', data.memory.usagePercent, '');
            this.updateMetric('memUsed', Math.round(data.memory.usedMb), '');
            this.updateMetric('memTotal', Math.round(data.memory.totalMb), '');
            this.updateBar('memBar', data.memory.usagePercent);
            
            // App memory breakdown - pass raw numbers, let updateMetric handle formatting
            const appTotal = data.memory.appTotalMb;
            const appNative = data.memory.appNativeMb;
            const appJava = data.memory.appJavaMb;
            this.updateMetric('appMemTotal', appTotal != null ? appTotal : '--', ' MB');
            this.updateMetric('appMemNative', appNative != null ? appNative : '--', ' MB');
            this.updateMetric('appMemJava', appJava != null ? appJava : '--', ' MB');
            this.setCardStatus('memCard', data.memory.usagePercent);
            
            // Update history
            this.pushHistory('memSystem', data.memory.usagePercent);
            // App memory as percentage of total system memory for meaningful comparison
            const appMemMb = appTotal || 0;
            const totalMemMb = data.memory.totalMb || 1;
            const appMemPercent = Math.min(100, (appMemMb / totalMemMb) * 100);
            this.pushHistory('memApp', appMemPercent);
        }
        
        // Update GPU metrics
        if (data.gpu) {
            this.updateMetric('gpuValue', data.gpu.usage || 0, '');
            this.updateBar('gpuBar', data.gpu.usage || 0);
            this.updateMetric('gpuFreq', (data.gpu.freqMhz ? data.gpu.freqMhz.toFixed(0) : '--'), ' MHz');
            this.updateMetric('gpuTemp', data.gpu.tempC || '--', '°C');
            
            // SOTA: Frequency-aware GPU health highlighting
            // High usage at low freq = efficient (governor doing its job)
            // High usage at high freq = needs optimization
            this.updateGpuHealth(data.gpu.usage || 0, data.gpu.freqMhz || 0);
            
            // Update history
            this.pushHistory('gpu', data.gpu.usage || 0);
        }
        
        // Update App metrics
        if (data.app) {
            this.updateMetric('threadCount', data.app.threads, '');
            this.updateMetric('openFds', data.app.openFds, '');
            this.updateMetric('gcCount', data.app.gcCount, '');
        }
        
        // Render charts
        this.renderAllCharts();
    },
    
    updateMetric(id, value, suffix) {
        const el = document.getElementById(id);
        if (el) {
            if (typeof value === 'number' && !isNaN(value)) {
                // Format number: show 1 decimal place, remove trailing .0
                el.textContent = value.toFixed(1).replace(/\.0$/, '') + (suffix || '');
            } else if (value === '--' || value == null || value === '') {
                // Placeholder value
                el.textContent = '--' + (suffix || '');
            } else if (typeof value === 'string') {
                // Avoid double suffix (e.g., "50%%" when value already contains %)
                const cleanValue = value.replace(/%+$/, '').replace(/°C+$/, '').replace(/ MHz+$/, '').replace(/ MB+$/, '');
                el.textContent = cleanValue + (suffix || '');
            } else {
                el.textContent = '--' + (suffix || '');
            }
        }
    },
    
    updateBar(id, percent) {
        const el = document.getElementById(id);
        if (el) {
            el.style.width = Math.min(100, Math.max(0, percent)) + '%';
            el.classList.remove('warning', 'danger');
            if (percent > 90) el.classList.add('danger');
            else if (percent > 70) el.classList.add('warning');
        }
    },
    
    setCardStatus(id, percent) {
        const el = document.getElementById(id);
        if (el) {
            el.classList.remove('warning', 'danger');
            if (percent > 90) el.classList.add('danger');
            else if (percent > 70) el.classList.add('warning');
        }
    },
    
    setMonitoringStatus(active) {
        const dot = document.getElementById('monitoringDot');
        const text = document.getElementById('monitoringText');
        if (dot) dot.classList.toggle('inactive', !active);
        if (text) text.textContent = active ? 'Monitoring' : 'Offline';
    },
    
    /**
     * SOTA: Frequency-aware GPU health highlighting with oscillation detection
     * 
     * In mobile SoCs, utilization % is actually a FREQUENCY RATIO:
     * - Usage = Current Freq / Max Freq (e.g., 320/650 = 49%)
     * 
     * The "sawtooth" oscillation pattern (49% → 92% → 49%) is NORMAL:
     * 1. Workload arrives (camera frame)
     * 2. GPU boosts to high freq to process quickly
     * 3. GPU drops back to idle freq ("race to idle")
     * This is called Dynamic Frequency Scaling and is battery-efficient.
     * 
     * When to worry:
     * - Locked at high usage (92%+) for extended periods = overloaded
     * - Oscillating is healthy, locked high is not
     */
    
    // Track recent GPU values for oscillation detection
    gpuRecentValues: [],
    GPU_OSCILLATION_WINDOW: 10, // Check last 10 samples
    
    updateGpuHealth(usage, freqMhz) {
        const card = document.getElementById('gpuCard');
        const badge = document.getElementById('gpuStatusBadge');
        const hint = document.getElementById('gpuHealthHint');
        const hintText = document.getElementById('gpuHintText');
        const subtitle = document.getElementById('gpuSubtitle');
        
        if (!card || !badge || !hint) return;
        
        // Track recent values for oscillation detection
        this.gpuRecentValues.push(usage);
        if (this.gpuRecentValues.length > this.GPU_OSCILLATION_WINDOW) {
            this.gpuRecentValues.shift();
        }
        
        // Detect oscillation pattern (variance in recent values)
        const isOscillating = this.detectGpuOscillation();
        const isLockedHigh = this.detectLockedHigh();
        
        // Reset classes
        card.classList.remove('warning', 'danger');
        badge.classList.remove('efficient', 'optimal', 'heavy', 'critical');
        hint.classList.remove('efficient', 'optimal', 'heavy', 'critical');
        
        // Frequency thresholds (MHz)
        const LOW_FREQ = 350;
        const HIGH_FREQ = 500;
        
        // Usage thresholds (%)
        const HIGH_USAGE = 70;
        const CRITICAL_USAGE = 90;
        
        let status = 'idle';
        let badgeText = '';
        let hintMessage = '';
        let showHint = false;
        
        if (isLockedHigh) {
            // CRITICAL: GPU stuck at high usage - actual problem
            status = 'critical';
            badgeText = 'Overloaded';
            card.classList.add('danger');
            subtitle.textContent = 'GPU locked at high load';
            hintMessage = `⚠️ GPU stuck at ${usage.toFixed(0)}% — Not oscillating. System may be overloaded or thermal throttling.`;
            showHint = true;
        } else if (isOscillating && usage >= HIGH_USAGE) {
            // Oscillating with high peaks - this is HEALTHY
            status = 'efficient';
            badgeText = 'Healthy';
            subtitle.textContent = 'Dynamic frequency scaling';
            hintMessage = `✓ Oscillation is normal! GPU boosts to ${usage.toFixed(0)}% for frames, then idles. This "race to idle" pattern saves battery.`;
            showHint = true;
        } else if (usage < 20 && freqMhz < LOW_FREQ) {
            // Idle - GPU barely working
            status = 'optimal';
            badgeText = 'Idle';
            subtitle.textContent = 'GPU idle, minimal power draw';
            showHint = false;
        } else if (usage >= HIGH_USAGE && freqMhz < LOW_FREQ) {
            // High usage at low frequency = EFFICIENT
            status = 'efficient';
            badgeText = 'Efficient';
            subtitle.textContent = 'Power-efficient operation';
            hintMessage = `${usage.toFixed(0)}% at ${freqMhz.toFixed(0)} MHz — Governor optimizing power.`;
            showHint = true;
        } else if (usage >= CRITICAL_USAGE && freqMhz >= HIGH_FREQ && !isOscillating) {
            // Critical only if NOT oscillating
            status = 'critical';
            badgeText = 'Heavy';
            card.classList.add('danger');
            subtitle.textContent = 'Sustained high load';
            hintMessage = `${usage.toFixed(0)}% at ${freqMhz.toFixed(0)} MHz — Monitor for heat and battery drain.`;
            showHint = true;
        } else if (usage >= HIGH_USAGE && freqMhz >= HIGH_FREQ) {
            // High usage at high freq but oscillating = normal burst
            status = 'optimal';
            badgeText = 'Active';
            subtitle.textContent = 'Processing burst';
            showHint = false;
        } else if (freqMhz >= HIGH_FREQ) {
            // High freq but moderate usage - ramping up
            status = 'optimal';
            badgeText = 'Active';
            subtitle.textContent = 'GPU active';
            showHint = false;
        } else {
            // Normal operation
            status = 'optimal';
            badgeText = '';
            subtitle.textContent = 'Graphics Processing';
            showHint = false;
        }
        
        // Update badge
        if (badgeText) {
            badge.textContent = badgeText;
            badge.classList.add(status);
            badge.style.display = 'inline-block';
        } else {
            badge.style.display = 'none';
        }
        
        // Update hint
        if (showHint && hintMessage) {
            hint.classList.add(status);
            hintText.textContent = hintMessage;
            hint.style.display = 'flex';
        } else {
            hint.style.display = 'none';
        }
    },
    
    /**
     * Detect if GPU is oscillating (healthy sawtooth pattern)
     * Returns true if there's significant variance in recent values
     */
    detectGpuOscillation() {
        if (this.gpuRecentValues.length < 5) return false;
        
        const values = this.gpuRecentValues;
        const min = Math.min(...values);
        const max = Math.max(...values);
        const range = max - min;
        
        // Oscillation = range of at least 20% between min and max
        return range >= 20;
    },
    
    /**
     * Detect if GPU is locked at high usage (unhealthy)
     * Returns true if all recent values are above 85%
     */
    detectLockedHigh() {
        if (this.gpuRecentValues.length < 8) return false;
        
        // Check if ALL recent values are high (no dips)
        const allHigh = this.gpuRecentValues.every(v => v >= 85);
        const avgUsage = this.gpuRecentValues.reduce((a, b) => a + b, 0) / this.gpuRecentValues.length;
        
        return allHigh && avgUsage >= 88;
    },
    
    pushHistory(key, value) {
        if (this.history[key]) {
            this.history[key].push(value || 0);
            if (this.history[key].length > this.HISTORY_SIZE) {
                this.history[key].shift();
            }
        }
    },
    
    renderAllCharts() {
        // Only render charts that aren't currently being hovered
        // This prevents tooltip from being cleared during data updates
        if (this.tooltip.chartId !== 'cpu') {
            this.renderChart(this.charts.cpu, [
                { data: this.history.cpuSystem, color: this.colors.system, label: 'System' },
                { data: this.history.cpuApp, color: this.colors.app, label: 'App' }
            ]);
        } else {
            // Re-render with tooltip
            this.renderChartByType('cpu');
        }
        
        if (this.tooltip.chartId !== 'mem') {
            this.renderChart(this.charts.mem, [
                { data: this.history.memSystem, color: this.colors.system, label: 'System' },
                { data: this.history.memApp, color: this.colors.app, label: 'App' }
            ]);
        } else {
            this.renderChartByType('mem');
        }
        
        if (this.tooltip.chartId !== 'gpu') {
            this.renderChart(this.charts.gpu, [
                { data: this.history.gpu, color: this.colors.gpu, label: 'GPU' }
            ]);
        } else {
            this.renderChartByType('gpu');
        }
    },

    
    renderChart(chart, series) {
        if (!chart || !chart.ctx) return;
        
        const { ctx, width, height, type } = chart;
        const padding = { top: 10, right: 10, bottom: 25, left: 40 };
        const chartWidth = width - padding.left - padding.right;
        const chartHeight = height - padding.top - padding.bottom;
        
        // Clear canvas
        ctx.clearRect(0, 0, width, height);
        
        // Draw grid
        ctx.strokeStyle = this.colors.grid;
        ctx.lineWidth = 1;
        
        // Horizontal grid lines (0%, 25%, 50%, 75%, 100%)
        for (let i = 0; i <= 4; i++) {
            const y = padding.top + (chartHeight * i / 4);
            ctx.beginPath();
            ctx.moveTo(padding.left, y);
            ctx.lineTo(width - padding.right, y);
            ctx.stroke();
            
            // Y-axis labels
            ctx.fillStyle = this.colors.text;
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText((100 - i * 25) + '%', padding.left - 8, y + 3);
        }
        
        // X-axis labels (time)
        ctx.textAlign = 'center';
        ctx.fillText('60s', padding.left, height - 5);
        ctx.fillText('30s', padding.left + chartWidth / 2, height - 5);
        ctx.fillText('now', width - padding.right, height - 5);
        
        // Draw each series
        series.forEach(s => {
            this.drawLine(ctx, s.data, s.color, padding, chartWidth, chartHeight);
        });
        
        // SOTA: Draw interactive crosshair and tooltip if hovering this chart
        if (this.tooltip.visible && this.tooltip.chartId === type) {
            this.drawCrosshairAndTooltip(ctx, series, padding, chartWidth, chartHeight, width, height);
        }
    },
    
    /**
     * SOTA: Draw crosshair line and tooltip with values at hover position
     */
    drawCrosshairAndTooltip(ctx, series, padding, chartWidth, chartHeight, width, height) {
        const dataIndex = this.tooltip.dataIndex;
        if (dataIndex < 0 || !series[0] || !series[0].data || dataIndex >= series[0].data.length) return;
        
        const points = series[0].data.length;
        const stepX = chartWidth / (points - 1);
        const x = padding.left + dataIndex * stepX;
        
        // Draw vertical crosshair line
        ctx.beginPath();
        ctx.strokeStyle = this.colors.crosshair;
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 4]);
        ctx.moveTo(x, padding.top);
        ctx.lineTo(x, padding.top + chartHeight);
        ctx.stroke();
        ctx.setLineDash([]);
        
        // Draw data points on the crosshair
        const tooltipData = [];
        series.forEach(s => {
            if (s.data && s.data[dataIndex] !== undefined) {
                const value = s.data[dataIndex];
                const y = padding.top + chartHeight - (value / 100 * chartHeight);
                
                // Draw highlighted point
                ctx.beginPath();
                ctx.arc(x, y, 6, 0, Math.PI * 2);
                ctx.fillStyle = s.color;
                ctx.fill();
                
                // White ring
                ctx.beginPath();
                ctx.arc(x, y, 8, 0, Math.PI * 2);
                ctx.strokeStyle = '#fff';
                ctx.lineWidth = 2;
                ctx.stroke();
                
                tooltipData.push({ label: s.label, value: value.toFixed(1), color: s.color });
            }
        });
        
        // Calculate time ago
        const secondsAgo = (series[0].data.length - 1 - dataIndex);
        const timeLabel = secondsAgo === 0 ? 'now' : secondsAgo + 's ago';
        
        // Draw tooltip box
        this.drawTooltipBox(ctx, x, padding.top + 20, tooltipData, timeLabel, width, padding);
    },
    
    /**
     * Draw tooltip box with values
     */
    drawTooltipBox(ctx, x, y, data, timeLabel, canvasWidth, padding) {
        const boxPadding = 10;
        const lineHeight = 18;
        const boxWidth = 110;
        const boxHeight = boxPadding * 2 + lineHeight * (data.length + 1);
        
        // Position tooltip to avoid overflow
        let tooltipX = x + 15;
        if (tooltipX + boxWidth > canvasWidth - padding.right) {
            tooltipX = x - boxWidth - 15;
        }
        
        // Draw tooltip background
        ctx.fillStyle = this.colors.tooltipBg;
        ctx.strokeStyle = this.colors.tooltipBorder;
        ctx.lineWidth = 1;
        
        // Rounded rectangle
        const radius = 6;
        ctx.beginPath();
        ctx.moveTo(tooltipX + radius, y);
        ctx.lineTo(tooltipX + boxWidth - radius, y);
        ctx.quadraticCurveTo(tooltipX + boxWidth, y, tooltipX + boxWidth, y + radius);
        ctx.lineTo(tooltipX + boxWidth, y + boxHeight - radius);
        ctx.quadraticCurveTo(tooltipX + boxWidth, y + boxHeight, tooltipX + boxWidth - radius, y + boxHeight);
        ctx.lineTo(tooltipX + radius, y + boxHeight);
        ctx.quadraticCurveTo(tooltipX, y + boxHeight, tooltipX, y + boxHeight - radius);
        ctx.lineTo(tooltipX, y + radius);
        ctx.quadraticCurveTo(tooltipX, y, tooltipX + radius, y);
        ctx.closePath();
        ctx.fill();
        ctx.stroke();
        
        // Draw time label
        ctx.fillStyle = this.colors.text;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'left';
        ctx.fillText(timeLabel, tooltipX + boxPadding, y + boxPadding + 10);
        
        // Draw data values
        data.forEach((item, i) => {
            const itemY = y + boxPadding + lineHeight * (i + 1) + 10;
            
            // Color dot
            ctx.beginPath();
            ctx.arc(tooltipX + boxPadding + 4, itemY - 4, 4, 0, Math.PI * 2);
            ctx.fillStyle = item.color;
            ctx.fill();
            
            // Label and value
            ctx.fillStyle = '#fff';
            ctx.font = '11px Inter, sans-serif';
            ctx.fillText(item.label + ':', tooltipX + boxPadding + 14, itemY);
            
            ctx.font = 'bold 11px JetBrains Mono, monospace';
            ctx.textAlign = 'right';
            ctx.fillText(item.value + '%', tooltipX + boxWidth - boxPadding, itemY);
            ctx.textAlign = 'left';
        });
    },
    
    drawLine(ctx, data, color, padding, chartWidth, chartHeight) {
        if (!data || data.length === 0) return;
        
        const points = data.length;
        const stepX = chartWidth / (points - 1);
        
        // Draw filled area
        ctx.beginPath();
        ctx.moveTo(padding.left, padding.top + chartHeight);
        
        for (let i = 0; i < points; i++) {
            const x = padding.left + i * stepX;
            const y = padding.top + chartHeight - (data[i] / 100 * chartHeight);
            
            if (i === 0) {
                ctx.lineTo(x, y);
            } else {
                // Smooth curve using quadratic bezier
                const prevX = padding.left + (i - 1) * stepX;
                const prevY = padding.top + chartHeight - (data[i - 1] / 100 * chartHeight);
                const cpX = (prevX + x) / 2;
                ctx.quadraticCurveTo(prevX, prevY, cpX, (prevY + y) / 2);
                if (i === points - 1) {
                    ctx.lineTo(x, y);
                }
            }
        }
        
        ctx.lineTo(padding.left + chartWidth, padding.top + chartHeight);
        ctx.closePath();
        
        // Gradient fill
        const gradient = ctx.createLinearGradient(0, padding.top, 0, padding.top + chartHeight);
        gradient.addColorStop(0, color + '40');
        gradient.addColorStop(1, color + '05');
        ctx.fillStyle = gradient;
        ctx.fill();
        
        // Draw line
        ctx.beginPath();
        for (let i = 0; i < points; i++) {
            const x = padding.left + i * stepX;
            const y = padding.top + chartHeight - (data[i] / 100 * chartHeight);
            
            if (i === 0) {
                ctx.moveTo(x, y);
            } else {
                const prevX = padding.left + (i - 1) * stepX;
                const prevY = padding.top + chartHeight - (data[i - 1] / 100 * chartHeight);
                const cpX = (prevX + x) / 2;
                ctx.quadraticCurveTo(prevX, prevY, cpX, (prevY + y) / 2);
                if (i === points - 1) {
                    ctx.lineTo(x, y);
                }
            }
        }
        
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.stroke();
        
        // Draw current value dot
        const lastX = padding.left + chartWidth;
        const lastY = padding.top + chartHeight - (data[data.length - 1] / 100 * chartHeight);
        
        ctx.beginPath();
        ctx.arc(lastX, lastY, 4, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.fill();
        
        // Glow effect
        ctx.beginPath();
        ctx.arc(lastX, lastY, 6, 0, Math.PI * 2);
        ctx.fillStyle = color + '40';
        ctx.fill();
    },
    
    // ==================== SOC CHART METHODS ====================
    
    /**
     * Set SOC time range and refresh data
     */
    setSocTimeRange(hours) {
        this.socTimeRange = hours;
        
        // Update button states
        document.querySelectorAll('.time-btn').forEach(btn => {
            btn.classList.toggle('active', parseInt(btn.dataset.hours) === hours);
        });
        
        // Fetch new data
        this.fetchSocHistory();
    },
    
    /**
     * Fetch SOC history from API
     */
    async fetchSocHistory() {
        try {
            const url = `/api/performance/soc/full?hours=${this.socTimeRange}&points=300`;
            const res = await fetch(url);
            
            if (res.ok) {
                const data = await res.json();
                this.socData = {
                    history: data.history || [],
                    stats: data.stats || {},
                    sessions: data.chargingSessions || [],
                    hasLiveData: data.hasLiveData || false
                };
                
                this.updateSocStats();
                this.renderSocChart();
            } else {
                console.error('[Performance] SOC fetch failed:', res.status);
            }
        } catch (e) {
            console.error('[Performance] SOC fetch error:', e);
        }
    },
    
    /**
     * Update SOC statistics display
     */
    updateSocStats() {
        const stats = this.socData.stats;
        
        // Current SOC - try from stats first, then from live data
        let current = stats.currentSoc;
        if (current == null && this.socData.history && this.socData.history.length > 0) {
            current = this.socData.history[this.socData.history.length - 1].soc;
        }
        this.updateElement('socCurrent', current != null ? current.toFixed(0) + '%' : '--%');
        
        // Current kWh - from latest history point
        let currentKwh = null;
        let currentRange = null;
        let isCharging = false;
        if (this.socData.history && this.socData.history.length > 0) {
            const latest = this.socData.history[this.socData.history.length - 1];
            if (latest.kwh && latest.kwh > 0) {
                currentKwh = latest.kwh;
            }
            if (latest.range && latest.range > 0) {
                currentRange = latest.range;
            }
            isCharging = latest.charging;
        }
        this.updateElement('socKwh', currentKwh != null ? currentKwh.toFixed(1) + ' kWh' : '-- kWh');
        
        // Min/Max/Avg
        this.updateElement('socMin', stats.minSoc != null ? stats.minSoc.toFixed(0) + '%' : '--%');
        this.updateElement('socMax', stats.maxSoc != null ? stats.maxSoc.toFixed(0) + '%' : '--%');
        this.updateElement('socAvg', stats.avgSoc != null ? stats.avgSoc.toFixed(0) + '%' : '--%');
        
        // Charging sessions
        this.updateElement('chargingSessions', stats.chargingSessions != null ? stats.chargingSessions : '--');
        
        // Update EV Battery Card
        this.updateElement('evSocValue', current != null ? current.toFixed(0) : '--');
        this.updateElement('evKwhValue', currentKwh != null ? currentKwh.toFixed(1) : '--');
        this.updateElement('evRangeValue', currentRange != null ? currentRange.toFixed(0) + ' km' : '-- km');
        this.updateElement('evChargingStatus', isCharging ? '⚡ Charging' : 'Idle');
        
        // Update EV SOC bar
        const evSocBar = document.getElementById('evSocBar');
        if (evSocBar && current != null) {
            evSocBar.style.width = Math.min(100, Math.max(0, current)) + '%';
        }
        
        // Update EV Battery card status color
        const evCard = document.getElementById('evBatteryCard');
        if (evCard && current != null) {
            evCard.classList.remove('warning', 'danger');
            if (current < 15) evCard.classList.add('danger');
            else if (current < 30) evCard.classList.add('warning');
        }
        
        // SOH from latest history point (fallback when battery health API hasn't loaded yet)
        if (this.socData.history && this.socData.history.length > 0) {
            const latest = this.socData.history[this.socData.history.length - 1];
            if (latest.soh && latest.soh > 0) {
                const sohEl = document.getElementById('evSohValue');
                if (sohEl && sohEl.textContent === '--%') {
                    sohEl.textContent = latest.soh.toFixed(1) + '%';
                }
                // Also update battery health card if not yet populated
                const sohValEl = document.getElementById('sohValue');
                if (sohValEl && sohValEl.textContent === '--%') {
                    sohValEl.textContent = latest.soh.toFixed(1) + '%';
                }
                const badge = document.getElementById('sohBadge');
                if (badge && badge.style.display === 'none') {
                    badge.style.display = '';
                    badge.textContent = 'SOH ' + latest.soh.toFixed(0) + '%';
                    if (latest.soh >= 90) badge.className = 'gpu-status-badge efficient';
                    else if (latest.soh >= 80) badge.className = 'gpu-status-badge optimal';
                    else if (latest.soh >= 70) badge.className = 'gpu-status-badge heavy';
                    else badge.className = 'gpu-status-badge critical';
                }
            }
        }
    },
    
    updateElement(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    },
    
    /**
     * Render SOC chart with charging indicators
     */
    renderSocChart() {
        const chart = this.charts.soc;
        if (!chart || !chart.ctx) return;
        
        const { ctx, width, height } = chart;
        const padding = { top: 15, right: 15, bottom: 30, left: 45 };
        const chartWidth = width - padding.left - padding.right;
        const chartHeight = height - padding.top - padding.bottom;
        
        const history = this.socData.history;
        
        // Clear canvas
        ctx.clearRect(0, 0, width, height);
        
        if (!history || history.length === 0) {
            // Draw empty state
            ctx.fillStyle = this.colors.text;
            ctx.font = '14px Inter, sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText('No SOC data available', width / 2, height / 2 - 10);
            ctx.font = '12px Inter, sans-serif';
            ctx.fillText('SOC history will appear once data is collected', width / 2, height / 2 + 15);
            return;
        }
        
        // If only one point, show current value prominently
        if (history.length === 1) {
            const soc = history[0].soc;
            ctx.fillStyle = this.colors.soc;
            ctx.font = 'bold 48px JetBrains Mono, monospace';
            ctx.textAlign = 'center';
            ctx.fillText(soc.toFixed(0) + '%', width / 2, height / 2);
            ctx.fillStyle = this.colors.text;
            ctx.font = '12px Inter, sans-serif';
            ctx.fillText('Current Battery Level', width / 2, height / 2 + 30);
            ctx.fillText('History will build over time', width / 2, height / 2 + 50);
            return;
        }
        
        // Find min/max for Y axis (SOC is 0-100%)
        const minSoc = 0;
        const maxSoc = 100;
        
        // Time range
        const timeStart = history[0].t;
        const timeEnd = history[history.length - 1].t;
        const timeRange = timeEnd - timeStart;
        
        // Draw grid
        ctx.strokeStyle = this.colors.grid;
        ctx.lineWidth = 1;
        
        // Horizontal grid lines (0%, 25%, 50%, 75%, 100%)
        for (let i = 0; i <= 4; i++) {
            const y = padding.top + (chartHeight * i / 4);
            ctx.beginPath();
            ctx.moveTo(padding.left, y);
            ctx.lineTo(width - padding.right, y);
            ctx.stroke();
            
            // Y-axis labels
            ctx.fillStyle = this.colors.text;
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText((100 - i * 25) + '%', padding.left - 8, y + 3);
        }
        
        // X-axis time labels
        ctx.textAlign = 'center';
        const timeLabels = this.getTimeLabels(timeStart, timeEnd, this.socTimeRange);
        timeLabels.forEach(label => {
            const x = padding.left + ((label.time - timeStart) / timeRange) * chartWidth;
            ctx.fillText(label.text, x, height - 8);
        });
        
        // Draw charging regions first (background)
        this.drawChargingRegions(ctx, history, padding, chartWidth, chartHeight, timeStart, timeRange);
        
        // Draw SOC line
        this.drawSocLine(ctx, history, padding, chartWidth, chartHeight, timeStart, timeRange, minSoc, maxSoc, width);
    },
    
    /**
     * Draw charging regions as highlighted background areas
     */
    drawChargingRegions(ctx, history, padding, chartWidth, chartHeight, timeStart, timeRange) {
        let inCharging = false;
        let chargingStartX = 0;
        
        history.forEach((point, i) => {
            const x = padding.left + ((point.t - timeStart) / timeRange) * chartWidth;
            
            if (point.charging && !inCharging) {
                // Charging started
                inCharging = true;
                chargingStartX = x;
            } else if (!point.charging && inCharging) {
                // Charging ended - draw region
                inCharging = false;
                ctx.fillStyle = 'rgba(14, 165, 233, 0.1)';
                ctx.fillRect(chargingStartX, padding.top, x - chargingStartX, chartHeight);
            }
        });
        
        // Handle case where still charging at end
        if (inCharging) {
            const endX = padding.left + chartWidth;
            ctx.fillStyle = 'rgba(14, 165, 233, 0.1)';
            ctx.fillRect(chargingStartX, padding.top, endX - chargingStartX, chartHeight);
        }
    },
    
    /**
     * Draw SOC line with gradient fill
     */
    drawSocLine(ctx, history, padding, chartWidth, chartHeight, timeStart, timeRange, minSoc, maxSoc, width) {
        if (history.length < 2) return;
        
        const socRange = maxSoc - minSoc;
        
        // Build path
        ctx.beginPath();
        ctx.moveTo(padding.left, padding.top + chartHeight);
        
        history.forEach((point, i) => {
            const x = padding.left + ((point.t - timeStart) / timeRange) * chartWidth;
            const y = padding.top + chartHeight - ((point.soc - minSoc) / socRange * chartHeight);
            
            if (i === 0) {
                ctx.lineTo(x, y);
            } else {
                // Smooth curve
                const prevPoint = history[i - 1];
                const prevX = padding.left + ((prevPoint.t - timeStart) / timeRange) * chartWidth;
                const prevY = padding.top + chartHeight - ((prevPoint.soc - minSoc) / socRange * chartHeight);
                const cpX = (prevX + x) / 2;
                ctx.quadraticCurveTo(prevX, prevY, cpX, (prevY + y) / 2);
                
                if (i === history.length - 1) {
                    ctx.lineTo(x, y);
                }
            }
        });
        
        // Close path for fill
        const lastPoint = history[history.length - 1];
        const lastX = padding.left + ((lastPoint.t - timeStart) / timeRange) * chartWidth;
        ctx.lineTo(lastX, padding.top + chartHeight);
        ctx.closePath();
        
        // Gradient fill
        const gradient = ctx.createLinearGradient(0, padding.top, 0, padding.top + chartHeight);
        gradient.addColorStop(0, this.colors.soc + '40');
        gradient.addColorStop(1, this.colors.soc + '05');
        ctx.fillStyle = gradient;
        ctx.fill();
        
        // Draw line
        ctx.beginPath();
        history.forEach((point, i) => {
            const x = padding.left + ((point.t - timeStart) / timeRange) * chartWidth;
            const y = padding.top + chartHeight - ((point.soc - minSoc) / socRange * chartHeight);
            
            if (i === 0) {
                ctx.moveTo(x, y);
            } else {
                const prevPoint = history[i - 1];
                const prevX = padding.left + ((prevPoint.t - timeStart) / timeRange) * chartWidth;
                const prevY = padding.top + chartHeight - ((prevPoint.soc - minSoc) / socRange * chartHeight);
                const cpX = (prevX + x) / 2;
                ctx.quadraticCurveTo(prevX, prevY, cpX, (prevY + y) / 2);
                
                if (i === history.length - 1) {
                    ctx.lineTo(x, y);
                }
            }
        });
        
        ctx.strokeStyle = this.colors.soc;
        ctx.lineWidth = 2;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.stroke();
        
        // Draw current value dot
        const lastY = padding.top + chartHeight - ((lastPoint.soc - minSoc) / socRange * chartHeight);
        
        ctx.beginPath();
        ctx.arc(lastX, lastY, 5, 0, Math.PI * 2);
        ctx.fillStyle = this.colors.soc;
        ctx.fill();
        
        // Glow
        ctx.beginPath();
        ctx.arc(lastX, lastY, 8, 0, Math.PI * 2);
        ctx.fillStyle = this.colors.soc + '40';
        ctx.fill();
        
        // SOTA: Current value label with smart positioning to stay in bounds
        const labelText = lastPoint.soc.toFixed(0) + '%';
        ctx.font = 'bold 11px JetBrains Mono, monospace';
        const textWidth = ctx.measureText(labelText).width;
        
        // Calculate label position - keep within chart bounds
        let labelX = lastX + 12;
        let labelY = lastY + 4;
        
        // If label would overflow right edge, position to the left
        if (labelX + textWidth > width - padding.right) {
            labelX = lastX - textWidth - 12;
        }
        
        // If label would overflow top, position below the point
        if (labelY - 10 < padding.top) {
            labelY = lastY + 20;
        }
        
        // If label would overflow bottom, position above the point
        if (labelY > padding.top + chartHeight - 5) {
            labelY = lastY - 10;
        }
        
        // Draw label background for better readability
        const bgPadding = 4;
        ctx.fillStyle = this.colors.tooltipBg;
        ctx.fillRect(labelX - bgPadding, labelY - 12, textWidth + bgPadding * 2, 16);
        
        ctx.fillStyle = '#fff';
        ctx.textAlign = 'left';
        ctx.fillText(labelText, labelX, labelY);
        
        // SOTA: Draw interactive crosshair and tooltip if hovering SOC chart
        if (this.tooltip.visible && this.tooltip.chartId === 'soc') {
            this.drawSocCrosshairAndTooltip(ctx, history, padding, chartWidth, chartHeight, timeStart, timeRange, minSoc, socRange, width);
        }
    },
    
    /**
     * SOTA: Draw crosshair and tooltip for SOC chart
     */
    drawSocCrosshairAndTooltip(ctx, history, padding, chartWidth, chartHeight, timeStart, timeRange, minSoc, socRange, width) {
        const dataIndex = this.tooltip.dataIndex;
        if (dataIndex < 0 || dataIndex >= history.length) return;
        
        const point = history[dataIndex];
        const x = padding.left + ((point.t - timeStart) / timeRange) * chartWidth;
        const y = padding.top + chartHeight - ((point.soc - minSoc) / socRange * chartHeight);
        
        // Draw vertical crosshair line
        ctx.beginPath();
        ctx.strokeStyle = this.colors.crosshair;
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 4]);
        ctx.moveTo(x, padding.top);
        ctx.lineTo(x, padding.top + chartHeight);
        ctx.stroke();
        ctx.setLineDash([]);
        
        // Draw highlighted point
        ctx.beginPath();
        ctx.arc(x, y, 7, 0, Math.PI * 2);
        ctx.fillStyle = this.colors.soc;
        ctx.fill();
        
        // White ring
        ctx.beginPath();
        ctx.arc(x, y, 9, 0, Math.PI * 2);
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 2;
        ctx.stroke();
        
        // Format time
        const date = new Date(point.t);
        const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const dateStr = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
        
        // Draw tooltip
        this.drawSocTooltipBox(ctx, x, y, point, timeStr, dateStr, width, padding, chartWidth);
    },
    
    /**
     * Draw SOC tooltip box with detailed info
     */
    drawSocTooltipBox(ctx, x, y, point, timeStr, dateStr, canvasWidth, padding, chartWidth) {
        const boxPadding = 10;
        const lineHeight = 16;
        const boxWidth = 130;
        // Add extra line for kWh if available
        const hasKwh = point.kwh && point.kwh > 0;
        const boxHeight = boxPadding * 2 + lineHeight * (hasKwh ? 5 : 4);
        
        // Position tooltip to avoid overflow
        let tooltipX = x + 15;
        let tooltipY = Math.max(padding.top + 10, y - boxHeight / 2);
        
        // Keep within horizontal bounds
        if (tooltipX + boxWidth > canvasWidth - padding.right) {
            tooltipX = x - boxWidth - 15;
        }
        
        // Keep within vertical bounds
        if (tooltipY + boxHeight > padding.top + (canvasWidth * 0.6)) {
            tooltipY = padding.top + 10;
        }
        
        // Draw tooltip background
        ctx.fillStyle = this.colors.tooltipBg;
        ctx.strokeStyle = this.colors.tooltipBorder;
        ctx.lineWidth = 1;
        
        // Rounded rectangle
        const radius = 6;
        ctx.beginPath();
        ctx.moveTo(tooltipX + radius, tooltipY);
        ctx.lineTo(tooltipX + boxWidth - radius, tooltipY);
        ctx.quadraticCurveTo(tooltipX + boxWidth, tooltipY, tooltipX + boxWidth, tooltipY + radius);
        ctx.lineTo(tooltipX + boxWidth, tooltipY + boxHeight - radius);
        ctx.quadraticCurveTo(tooltipX + boxWidth, tooltipY + boxHeight, tooltipX + boxWidth - radius, tooltipY + boxHeight);
        ctx.lineTo(tooltipX + radius, tooltipY + boxHeight);
        ctx.quadraticCurveTo(tooltipX, tooltipY + boxHeight, tooltipX, tooltipY + boxHeight - radius);
        ctx.lineTo(tooltipX, tooltipY + radius);
        ctx.quadraticCurveTo(tooltipX, tooltipY, tooltipX + radius, tooltipY);
        ctx.closePath();
        ctx.fill();
        ctx.stroke();
        
        // Draw date/time
        ctx.fillStyle = this.colors.text;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'left';
        ctx.fillText(dateStr + ' ' + timeStr, tooltipX + boxPadding, tooltipY + boxPadding + 10);
        
        // SOC value
        ctx.fillStyle = this.colors.soc;
        ctx.font = 'bold 16px JetBrains Mono, monospace';
        ctx.fillText(point.soc.toFixed(1) + '%', tooltipX + boxPadding, tooltipY + boxPadding + 30);
        
        // kWh remaining (if available)
        let yOffset = 48;
        if (hasKwh) {
            ctx.fillStyle = '#fbbf24'; // Amber color for energy
            ctx.font = '11px Inter, sans-serif';
            ctx.fillText('⚡ ' + point.kwh.toFixed(1) + ' kWh', tooltipX + boxPadding, tooltipY + boxPadding + yOffset);
            yOffset += 16;
        }
        
        // Charging status
        ctx.fillStyle = point.charging ? this.colors.charging : this.colors.text;
        ctx.font = '11px Inter, sans-serif';
        ctx.fillText(point.charging ? '⚡ Charging' : '○ Not charging', tooltipX + boxPadding, tooltipY + boxPadding + yOffset);
        yOffset += 16;
        
        // Range if available
        if (point.range && point.range > 0) {
            ctx.fillStyle = '#fff';
            ctx.fillText('Range: ' + point.range.toFixed(0) + ' km', tooltipX + boxPadding, tooltipY + boxPadding + yOffset);
        }
    },
    
    /**
     * Generate time labels for X axis
     */
    getTimeLabels(startTime, endTime, hoursRange) {
        const labels = [];
        const range = endTime - startTime;
        
        // Determine label interval based on range
        let labelCount = 5;
        if (hoursRange <= 24) labelCount = 6;
        else if (hoursRange <= 72) labelCount = 4;
        else labelCount = 7;
        
        for (let i = 0; i <= labelCount; i++) {
            const time = startTime + (range * i / labelCount);
            const date = new Date(time);
            
            let text;
            if (hoursRange <= 24) {
                // Show hours
                text = date.getHours().toString().padStart(2, '0') + ':00';
            } else {
                // Show day/month
                text = (date.getMonth() + 1) + '/' + date.getDate();
            }
            
            labels.push({ time, text });
        }
        
        return labels;
    },

    // ==================== BATTERY HEALTH ====================

    batteryTimeRange: 72,
    healthTimeRange: 72,
    batteryHealthData: null,

    setBatteryTimeRange(hours) {
        this.batteryTimeRange = hours;
        this.fetchBatteryHealth();
    },

    setHealthTimeRange(hours) {
        this.healthTimeRange = hours;
        this.fetchBatteryHealth();
    },

    async fetchBatteryHealth() {
        try {
            const hours = Math.max(this.batteryTimeRange, this.healthTimeRange);
            const res = await fetch(`/api/performance/battery?hours=${hours}&points=300`);
            if (!res.ok) return;
            
            this.batteryHealthData = await res.json();
            this.updateBatteryHealthUI();
            this.renderVoltageChart();
            this.renderThermalChart();
        } catch (e) {
            console.error('[Performance] Battery health fetch error:', e);
        }
    },

    updateBatteryHealthUI() {
        const d = this.batteryHealthData;
        if (!d) return;

        const c = d.current || {};
        const vs = d.voltageStats || {};

        // 12V stats
        this.updateElement('volt12vCurrent', c.voltage12v ? c.voltage12v.toFixed(2) + 'V' : '--V');
        this.updateElement('volt12vMin', vs.min ? vs.min.toFixed(2) + 'V' : '--V');
        this.updateElement('volt12vMax', vs.max ? vs.max.toFixed(2) + 'V' : '--V');
        this.updateElement('volt12vAvg', vs.avg ? vs.avg.toFixed(2) + 'V' : '--V');

        // SOH
        const soh = c.soh;
        this.updateElement('sohValue', soh != null ? soh.toFixed(1) + '%' : '--%');
        this.updateElement('evSohValue', soh != null ? soh.toFixed(1) + '%' : '--%');
        this.updateElement('estCapacity', c.estimatedCapacityKwh ? c.estimatedCapacityKwh.toFixed(1) + ' kWh' : '-- kWh');

        // SOH badge
        const badge = document.getElementById('sohBadge');
        if (badge && soh != null) {
            badge.style.display = '';
            badge.textContent = 'SOH ' + soh.toFixed(0) + '%';
            if (soh >= 90) { badge.className = 'gpu-status-badge efficient'; }
            else if (soh >= 80) { badge.className = 'gpu-status-badge optimal'; }
            else if (soh >= 70) { badge.className = 'gpu-status-badge heavy'; }
            else { badge.className = 'gpu-status-badge critical'; }
        }

        // Thermal
        this.updateElement('tempHighVal', c.tempHigh != null ? c.tempHigh.toFixed(1) + '°C' : '--°C');
        this.updateElement('tempLowVal', c.tempLow != null ? c.tempLow.toFixed(1) + '°C' : '--°C');
        this.updateElement('tempDeltaVal', c.tempDelta != null ? c.tempDelta.toFixed(1) + '°C' : '--°C');
        
        const status = c.thermalStatus || '--';
        const statusEl = document.getElementById('thermalStatus');
        if (statusEl) {
            statusEl.textContent = status;
            statusEl.style.color = status === 'CRITICAL' ? '#ef4444' : status === 'WARNING' ? '#fbbf24' : status === 'NORMAL' ? '#22c55e' : 'var(--text-muted)';
        }
    },

    renderVoltageChart() {
        const d = this.batteryHealthData;
        if (!d || !d.voltageHistory || d.voltageHistory.length < 2) return;

        const canvas = document.getElementById('voltageChart');
        if (!canvas) return;

        // Skip rendering if canvas is not visible (mobile: below fold)
        const rect = canvas.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) {
            // Schedule re-render when visible
            this._scheduleVisibilityRender('voltageChart', 'voltage');
            return;
        }

        // Re-create chart to get correct dimensions
        this.charts.voltage = this.createChart('voltageChart', 'voltage');
        if (!this.charts.voltage) return;

        const ctx = this.charts.voltage.ctx;
        const W = this.charts.voltage.width;
        const H = this.charts.voltage.height;

        ctx.clearRect(0, 0, W, H);

        const history = d.voltageHistory;
        const padding = { top: 20, right: 20, bottom: 30, left: 50 };
        const cW = W - padding.left - padding.right;
        const cH = H - padding.top - padding.bottom;

        const timeStart = history[0].t;
        const timeEnd = history[history.length - 1].t;
        const timeRange = timeEnd - timeStart || 1;

        let minV = Infinity, maxV = -Infinity;
        history.forEach(p => { if (p.voltage < minV) minV = p.voltage; if (p.voltage > maxV) maxV = p.voltage; });
        minV = Math.floor(minV * 2) / 2 - 0.5;
        maxV = Math.ceil(maxV * 2) / 2 + 0.5;
        if (minV > 10.5) minV = 10.5;
        const vRange = maxV - minV || 1;

        // Grid
        ctx.strokeStyle = 'rgba(255,255,255,0.06)';
        ctx.lineWidth = 1;
        for (let v = Math.ceil(minV); v <= maxV; v += 0.5) {
            const y = padding.top + cH - ((v - minV) / vRange) * cH;
            ctx.beginPath(); ctx.moveTo(padding.left, y); ctx.lineTo(W - padding.right, y); ctx.stroke();
            ctx.fillStyle = 'rgba(255,255,255,0.3)';
            ctx.font = '11px Inter';
            ctx.textAlign = 'right';
            ctx.fillText(v.toFixed(1) + 'V', padding.left - 6, y + 4);
        }

        // Warning threshold
        const warnY = padding.top + cH - ((11.5 - minV) / vRange) * cH;
        ctx.strokeStyle = 'rgba(239, 68, 68, 0.4)';
        ctx.setLineDash([4, 4]);
        ctx.beginPath(); ctx.moveTo(padding.left, warnY); ctx.lineTo(W - padding.right, warnY); ctx.stroke();
        ctx.setLineDash([]);

        // Charging regions
        this.drawChargingHighlight(ctx, history, padding, cW, cH, timeStart, timeRange);

        // Voltage line
        ctx.strokeStyle = '#fbbf24';
        ctx.lineWidth = 2;
        ctx.beginPath();
        history.forEach((p, i) => {
            const x = padding.left + ((p.t - timeStart) / timeRange) * cW;
            const y = padding.top + cH - ((p.voltage - minV) / vRange) * cH;
            i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
        });
        ctx.stroke();

        // Time labels
        const labels = this.getTimeLabels(timeStart, timeEnd, this.batteryTimeRange);
        ctx.fillStyle = 'rgba(255,255,255,0.4)';
        ctx.font = '11px Inter';
        ctx.textAlign = 'center';
        labels.forEach(l => {
            const x = padding.left + ((l.time - timeStart) / timeRange) * cW;
            ctx.fillText(l.text, x, H - 6);
        });

        // Crosshair tooltip (same pattern as SOC chart)
        if (this.tooltip.visible && this.tooltip.chartId === 'voltage') {
            this.drawVoltageCrosshair(ctx, history, padding, cW, cH, timeStart, timeRange, minV, vRange, W);
        }
    },

    renderThermalChart() {
        const d = this.batteryHealthData;
        if (!d || !d.thermalHistory || d.thermalHistory.length < 2) return;

        const canvas = document.getElementById('thermalChart');
        if (!canvas) return;

        // Skip rendering if canvas is not visible (mobile: below fold)
        const rect = canvas.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) {
            this._scheduleVisibilityRender('thermalChart', 'thermal');
            return;
        }

        // Re-create chart to get correct dimensions
        this.charts.thermal = this.createChart('thermalChart', 'thermal');
        if (!this.charts.thermal) return;

        const ctx = this.charts.thermal.ctx;
        const W = this.charts.thermal.width;
        const H = this.charts.thermal.height;

        ctx.clearRect(0, 0, W, H);

        const history = d.thermalHistory;
        const padding = { top: 20, right: 20, bottom: 30, left: 50 };
        const cW = W - padding.left - padding.right;
        const cH = H - padding.top - padding.bottom;

        const timeStart = history[0].t;
        const timeEnd = history[history.length - 1].t;
        const timeRange = timeEnd - timeStart || 1;

        // Auto-scale Y
        let minT = Infinity, maxT = -Infinity;
        history.forEach(p => {
            [p.high, p.low, p.avg].forEach(v => {
                if (v != null) { if (v < minT) minT = v; if (v > maxT) maxT = v; }
            });
        });
        minT = Math.floor(minT / 5) * 5 - 5;
        maxT = Math.ceil(maxT / 5) * 5 + 5;
        const tRange = maxT - minT || 1;

        // Grid
        ctx.strokeStyle = 'rgba(255,255,255,0.06)';
        ctx.lineWidth = 1;
        for (let t = Math.ceil(minT / 5) * 5; t <= maxT; t += 5) {
            const y = padding.top + cH - ((t - minT) / tRange) * cH;
            ctx.beginPath(); ctx.moveTo(padding.left, y); ctx.lineTo(W - padding.right, y); ctx.stroke();
            ctx.fillStyle = 'rgba(255,255,255,0.3)';
            ctx.font = '11px Inter';
            ctx.textAlign = 'right';
            ctx.fillText(t + '°C', padding.left - 6, y + 4);
        }

        // Charging regions (background highlight)
        this.drawChargingHighlight(ctx, history, padding, cW, cH, timeStart, timeRange);

        // Draw lines: high (red), low (green), avg (blue)
        const series = [
            { key: 'high', color: '#ef4444' },
            { key: 'low', color: '#22c55e' },
            { key: 'avg', color: '#3b82f6' }
        ];

        series.forEach(s => {
            ctx.strokeStyle = s.color;
            ctx.lineWidth = 2;
            ctx.beginPath();
            let started = false;
            history.forEach(p => {
                if (p[s.key] == null) return;
                const x = padding.left + ((p.t - timeStart) / timeRange) * cW;
                const y = padding.top + cH - ((p[s.key] - minT) / tRange) * cH;
                if (!started) { ctx.moveTo(x, y); started = true; } else { ctx.lineTo(x, y); }
            });
            ctx.stroke();
        });

        // Time labels
        const labels = this.getTimeLabels(timeStart, timeEnd, this.healthTimeRange);
        ctx.fillStyle = 'rgba(255,255,255,0.4)';
        ctx.font = '11px Inter';
        ctx.textAlign = 'center';
        labels.forEach(l => {
            const x = padding.left + ((l.time - timeStart) / timeRange) * cW;
            ctx.fillText(l.text, x, H - 6);
        });

        // Crosshair tooltip (same pattern as SOC chart)
        if (this.tooltip.visible && this.tooltip.chartId === 'thermal') {
            this.drawThermalCrosshair(ctx, history, padding, cW, cH, timeStart, timeRange, minT, tRange, W);
        }
    },

    /**
     * Draw crosshair and tooltip for voltage chart (matches SOC chart style).
     */
    drawVoltageCrosshair(ctx, history, padding, cW, cH, timeStart, timeRange, minV, vRange, width) {
        const idx = this.tooltip.dataIndex;
        if (idx < 0 || idx >= history.length) return;
        const point = history[idx];
        const x = padding.left + ((point.t - timeStart) / timeRange) * cW;
        const y = padding.top + cH - ((point.voltage - minV) / vRange) * cH;

        // Vertical crosshair
        ctx.beginPath();
        ctx.strokeStyle = this.colors.crosshair;
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 4]);
        ctx.moveTo(x, padding.top);
        ctx.lineTo(x, padding.top + cH);
        ctx.stroke();
        ctx.setLineDash([]);

        // Highlighted dot
        ctx.beginPath();
        ctx.arc(x, y, 7, 0, Math.PI * 2);
        ctx.fillStyle = '#fbbf24';
        ctx.fill();
        ctx.beginPath();
        ctx.arc(x, y, 9, 0, Math.PI * 2);
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 2;
        ctx.stroke();

        // Tooltip box
        const date = new Date(point.t);
        const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const dateStr = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
        const boxPadding = 10;
        const lineHeight = 16;
        const boxWidth = 130;
        const lines = 3 + (point.charging ? 1 : 0);
        const boxHeight = boxPadding * 2 + lineHeight * lines;

        let tooltipX = x + 15;
        if (tooltipX + boxWidth > width - padding.right) tooltipX = x - boxWidth - 15;
        let tooltipY = Math.max(padding.top + 10, y - boxHeight / 2);

        ctx.fillStyle = this.colors.tooltipBg;
        ctx.strokeStyle = this.colors.tooltipBorder;
        ctx.lineWidth = 1;
        this._drawRoundRect(ctx, tooltipX, tooltipY, boxWidth, boxHeight, 6);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = this.colors.text;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'left';
        ctx.fillText(dateStr + ' ' + timeStr, tooltipX + boxPadding, tooltipY + boxPadding + 10);

        ctx.fillStyle = '#fbbf24';
        ctx.font = 'bold 16px JetBrains Mono, monospace';
        ctx.fillText(point.voltage.toFixed(2) + 'V', tooltipX + boxPadding, tooltipY + boxPadding + 30);

        let yOff = 48;
        if (point.charging) {
            ctx.fillStyle = this.colors.charging || '#0ea5e9';
            ctx.font = '11px Inter, sans-serif';
            ctx.fillText('⚡ Charging', tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += 16;
        }
        ctx.fillStyle = point.voltage < 11.5 ? '#ef4444' : '#22c55e';
        ctx.font = '11px Inter, sans-serif';
        ctx.fillText(point.voltage < 11.5 ? '⚠ Low voltage' : '● Normal', tooltipX + boxPadding, tooltipY + boxPadding + yOff);
    },

    /**
     * Draw crosshair and tooltip for thermal chart (matches SOC chart style).
     */
    drawThermalCrosshair(ctx, history, padding, cW, cH, timeStart, timeRange, minT, tRange, width) {
        const idx = this.tooltip.dataIndex;
        if (idx < 0 || idx >= history.length) return;
        const point = history[idx];
        const x = padding.left + ((point.t - timeStart) / timeRange) * cW;

        // Vertical crosshair
        ctx.beginPath();
        ctx.strokeStyle = this.colors.crosshair;
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 4]);
        ctx.moveTo(x, padding.top);
        ctx.lineTo(x, padding.top + cH);
        ctx.stroke();
        ctx.setLineDash([]);

        // Dots on each line
        const series = [
            { key: 'high', color: '#ef4444' },
            { key: 'low', color: '#22c55e' },
            { key: 'avg', color: '#3b82f6' }
        ];
        series.forEach(s => {
            if (point[s.key] == null) return;
            const y = padding.top + cH - ((point[s.key] - minT) / tRange) * cH;
            ctx.beginPath();
            ctx.arc(x, y, 5, 0, Math.PI * 2);
            ctx.fillStyle = s.color;
            ctx.fill();
            ctx.beginPath();
            ctx.arc(x, y, 7, 0, Math.PI * 2);
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 2;
            ctx.stroke();
        });

        // Tooltip box
        const date = new Date(point.t);
        const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const dateStr = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
        const boxPadding = 10;
        const lineHeight = 16;
        const boxWidth = 140;
        let lineCount = 1; // header
        if (point.high != null) lineCount++;
        if (point.low != null) lineCount++;
        if (point.avg != null) lineCount++;
        if (point.charging) lineCount++;
        const boxHeight = boxPadding * 2 + lineHeight * lineCount + 4;

        let tooltipX = x + 15;
        if (tooltipX + boxWidth > width - padding.right) tooltipX = x - boxWidth - 15;
        let tooltipY = padding.top + 10;

        ctx.fillStyle = this.colors.tooltipBg;
        ctx.strokeStyle = this.colors.tooltipBorder;
        ctx.lineWidth = 1;
        this._drawRoundRect(ctx, tooltipX, tooltipY, boxWidth, boxHeight, 6);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = this.colors.text;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'left';
        ctx.fillText(dateStr + ' ' + timeStr, tooltipX + boxPadding, tooltipY + boxPadding + 10);

        let yOff = 28;
        if (point.high != null) {
            ctx.fillStyle = '#ef4444';
            ctx.font = '12px JetBrains Mono, monospace';
            ctx.fillText('Hi: ' + point.high.toFixed(1) + '°C', tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += lineHeight;
        }
        if (point.low != null) {
            ctx.fillStyle = '#22c55e';
            ctx.font = '12px JetBrains Mono, monospace';
            ctx.fillText('Lo: ' + point.low.toFixed(1) + '°C', tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += lineHeight;
        }
        if (point.avg != null) {
            ctx.fillStyle = '#3b82f6';
            ctx.font = '12px JetBrains Mono, monospace';
            ctx.fillText('Avg: ' + point.avg.toFixed(1) + '°C', tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += lineHeight;
        }
        if (point.charging) {
            ctx.fillStyle = this.colors.charging || '#0ea5e9';
            ctx.font = '11px Inter, sans-serif';
            ctx.fillText('⚡ Charging', tooltipX + boxPadding, tooltipY + boxPadding + yOff);
        }
    },

    /**
     * Draw a rounded rectangle path (compatible with older WebViews that lack ctx.roundRect).
     */
    _drawRoundRect(ctx, x, y, w, h, r) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.lineTo(x + w - r, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + r);
        ctx.lineTo(x + w, y + h - r);
        ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
        ctx.lineTo(x + r, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - r);
        ctx.lineTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.closePath();
    },

    /**
     * Draw charging regions as highlighted background areas on any chart.
     * Expects history points to have a `charging` boolean field.
     */
    drawChargingHighlight(ctx, history, padding, chartWidth, chartHeight, timeStart, timeRange) {
        let inCharging = false;
        let startX = 0;

        history.forEach(p => {
            const x = padding.left + ((p.t - timeStart) / timeRange) * chartWidth;
            if (p.charging && !inCharging) {
                inCharging = true;
                startX = x;
            } else if (!p.charging && inCharging) {
                inCharging = false;
                ctx.fillStyle = 'rgba(14, 165, 233, 0.1)';
                ctx.fillRect(startX, padding.top, x - startX, chartHeight);
            }
        });

        if (inCharging) {
            ctx.fillStyle = 'rgba(14, 165, 233, 0.1)';
            ctx.fillRect(startX, padding.top, padding.left + chartWidth - startX, chartHeight);
        }
    }
};
