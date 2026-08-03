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

    // Data-usage state
    dataUsageRange: 30, // days
    dataUsageData: { days: [], enabled: false, available: true },
    
    // Interactive tooltip state
    tooltip: {
        visible: false,
        x: 0,
        y: 0,
        chartId: null,
        dataIndex: -1
    },
    
    // Colors — line/series accents stay constant; surface-dependent fields
    // (grid, text, crosshair, tooltip) get refreshed from CSS tokens by
    // _refreshPalette() so they flip with [data-theme="light"].
    colors: {
        system: '#00D4AA',
        app: '#0EA5E9',
        gpu: '#a855f7',
        soc: '#22c55e',
        charging: '#0EA5E9',
        grid: 'rgba(255, 255, 255, 0.06)',
        text: 'rgba(255, 255, 255, 0.4)',
        textStrong: '#FFFFFF',
        crosshair: 'rgba(255, 255, 255, 0.3)',
        tooltipBg: 'rgba(20, 20, 30, 0.95)',
        tooltipBorder: 'rgba(255, 255, 255, 0.1)',
        tooltipText: '#FFFFFF'
    },

    /**
     * Pull the theme-dependent chart colors out of CSS custom properties so
     * canvas labels react to light/dark mode (data-theme on <html>). Called
     * at init and whenever the theme attribute mutates.
     */
    _refreshPalette() {
        try {
            const s = getComputedStyle(document.documentElement);
            const pick = (name, fallback) => {
                const v = (s.getPropertyValue(name) || '').trim();
                return v || fallback;
            };
            this.colors.grid = pick('--chart-grid', this.colors.grid);
            this.colors.text = pick('--chart-text', this.colors.text);
            this.colors.textStrong = pick('--chart-text-strong', this.colors.textStrong);
            this.colors.crosshair = pick('--chart-crosshair', this.colors.crosshair);
            this.colors.tooltipBg = pick('--chart-tooltip-bg', this.colors.tooltipBg);
            this.colors.tooltipBorder = pick('--chart-tooltip-border', this.colors.tooltipBorder);
            this.colors.tooltipText = pick('--chart-tooltip-text', this.colors.tooltipText);
        } catch (e) { /* keep dark defaults */ }
    },

    _setupThemeObserver() {
        if (this._themeObserver) return;
        const self = this;
        this._themeObserver = new MutationObserver(() => {
            self._refreshPalette();
            try { self.renderAllCharts(); } catch (_) {}
            try { self.renderSocChart(); } catch (_) {}
            try { self.renderVoltageChart(); } catch (_) {}
            try { self.renderThermalChart(); } catch (_) {}
            try { self.renderDataUsageChart(); } catch (_) {}
        });
        this._themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-theme']
        });
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

        // Resolve theme-dependent canvas colors before first paint, then
        // watch for live theme flips from the Android shell.
        this._refreshPalette();
        this._setupThemeObserver();

        // Initialize charts
        this.initCharts();

        // Paint the data-usage card's DEFAULT (disabled) state immediately, before
        // the await chain below. The card's hint + content both start display:none
        // and only applyDataUsageState() reveals one — so if the first
        // fetchDataUsage() (line ~169) fails/hangs, or init aborts before it, the
        // card would otherwise sit permanently blank (no hint, no error). Painting
        // the default here guarantees the "Enable to track…" hint shows on load;
        // a later successful fetch upgrades it to the live state.
        try { this.applyDataUsageState(); } catch (e) {}

        // SOTA: Connect to backend (starts monitoring if first client)
        await this.connect();
        
        // Start polling for real-time metrics
        this.startPolling();
        
        // Fetch initial SOC data
        await this.fetchSocHistory();
        
        // Fetch initial battery health data
        await this.fetchBatteryHealth();
        
        // Fetch SOH detail status
        await this.fetchSohStatus();

        // Push-notification deep link: #soh-fix lands the user on the SOH
        // card with the frame-mismatch banner visible. Scroll into view +
        // briefly highlight so it's obvious which control answers the
        // notification's "needs review" prompt. Listen for hashchange too
        // — when a click hits an already-open tab, sw.js navigates the
        // existing window which only updates location.hash, no reload.
        var self = this;
        if (BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            BYD.i18n.onChange(function() { self.fetchSohStatus(); });
        }
        if (window.location.hash === '#soh-fix') {
            this._scrollToSohFixBanner();
        }
        window.addEventListener('hashchange', function () {
            if (window.location.hash === '#soh-fix') {
                self._scrollToSohFixBanner();
            }
        });

        // Fetch initial data-usage (also reflects the enabled/disabled state)
        await this.fetchDataUsage();

        // Start SOC polling (less frequent)
        this.socPollInterval = setInterval(() => this.fetchSocHistory(), this.SOC_UPDATE_INTERVAL);

        // Data-usage polling (every 2 minutes — matches the sampler cadence)
        this.dataUsagePollInterval = setInterval(() => this.fetchDataUsage(), this.SOC_UPDATE_INTERVAL);
        
        // Battery health polling (every 2 minutes — same as SOC)
        this.batteryHealthPollInterval = setInterval(() => this.fetchBatteryHealth(), this.SOC_UPDATE_INTERVAL);
        
        // SOH detail polling (every 2 minutes)
        this.sohPollInterval = setInterval(() => this.fetchSohStatus(), this.SOC_UPDATE_INTERVAL);

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
                // Refresh IMMEDIATELY, do not wait for the next scheduled tick. Browsers throttle
                // or freeze background timers, so the 60s SOC poll may not have fired for minutes:
                // returning to the tab showed data as old as the moment it was backgrounded, which
                // reads exactly like a stuck graph. connect() only restarts the heartbeat.
                this.fetchData();
                this.fetchSocHistory();
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
        this.charts.dataUsage = this.createChart('dataUsageChart', 'dataUsage');

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
        } else if (chartType === 'dataUsage') {
            // Discrete daily BARS (not a time-series line). The bar chart uses its
            // own padding (left:55) and slot geometry, so recompute against those
            // rather than the shared padding above. Map mouseX → day-bar index.
            var duDays = (this.dataUsageData && this.dataUsageData.days) || [];
            if (!duDays.length) { this.tooltip.visible = false; return; }
            var duPadLeft = 55, duPadRight = 15;
            var duChartW = width - duPadLeft - duPadRight;
            if (mouseX < duPadLeft || mouseX > width - duPadRight) {
                this.tooltip.visible = false; this.renderDataUsageChart(); return;
            }
            var duSlot = duChartW / duDays.length;
            dataIndex = Math.floor((mouseX - duPadLeft) / duSlot);
            dataIndex = Math.max(0, Math.min(duDays.length - 1, dataIndex));
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
                    { data: this.history.cpuSystem, color: this.colors.system, label: BYD.i18n.t('performance.legend_system') },
                    { data: this.history.cpuApp, color: this.colors.app, label: BYD.i18n.t('performance.legend_app') }
                ]);
                break;
            case 'mem':
                this.renderChart(this.charts.mem, [
                    { data: this.history.memSystem, color: this.colors.system, label: BYD.i18n.t('performance.legend_system') },
                    { data: this.history.memApp, color: this.colors.app, label: BYD.i18n.t('performance.legend_app') }
                ]);
                break;
            case 'gpu':
                this.renderChart(this.charts.gpu, [
                    { data: this.history.gpu, color: this.colors.gpu, label: BYD.i18n.t('performance.card_gpu') }
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
            case 'dataUsage':
                this.renderDataUsageChart();
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
        this.renderDataUsageChart();
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
        if (this.dataUsagePollInterval) {
            clearInterval(this.dataUsagePollInterval);
            this.dataUsagePollInterval = null;
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
            // The CPU card shows TWO representations so neither is ambiguous:
            //
            //   PRIMARY (headline + bar + graph): WHOLE-DEVICE % (0-100, 100% =
            //   the entire machine). Intuitive — app ≤ system always, the bar
            //   can't peg, and a viewer reads "how much of my head unit is
            //   busy". This is the big number.
            //
            //   SECONDARY (mono "top ·" line): TOP-STYLE per-core % (100% = one
            //   core, ceiling = cores × 100) so it cross-checks against a live
            //   `top`. The core count and the cores×100 ceiling are shown
            //   explicitly so "app 320%" can't confuse anyone.
            //
            // cores is auto-detected daemon-side; default 1 (no scaling) for
            // older daemons that don't send it, preserving prior behaviour.
            var cores = data.cpu.cores || 1;

            // Whole-device (primary). data.cpu.app arrives per-core, so ÷cores.
            var appWholeDevice = data.cpu.app / cores;
            var systemWholeDevice = data.cpu.system; // already 0-100

            // Top-style (secondary). data.cpu.system is whole-device, so ×cores.
            var systemPerCore = data.cpu.system * cores;

            // Primary headline + bar + status — all whole-device 0-100.
            this.updateMetric('cpuValue', systemWholeDevice, '');   // system, % of device
            this.updateMetric('cpuAppValue', appWholeDevice, '');   // app, % of device
            this.updateBar('cpuBar', appWholeDevice);
            // Show current / max so thermal throttling is self-evident: when the
            // current freq sits well below max under load (and temp is high) the
            // SoC is throttling — which drags the whole head-unit UI on this
            // shared SDM665, not just OverDrive. maxFreqMhz is the static hardware
            // ceiling; omitted (0) on kernels that don't expose it.
            if (data.cpu.maxFreqMhz) {
                this.updateMetric('cpuFreq', data.cpu.freqMhz + ' / ' + data.cpu.maxFreqMhz, ' MHz');
            } else {
                this.updateMetric('cpuFreq', data.cpu.freqMhz, ' MHz');
            }
            this.updateMetric('cpuTemp', data.cpu.tempC, '°C');
            this.setCardStatus('cpuCard', appWholeDevice);

            // Secondary top-style line — matches `top`, with explicit context.
            this.updateMetric('cpuTopApp', data.cpu.app, '');       // per-core, matches `top`
            this.updateMetric('cpuTopSys', systemPerCore, '');      // per-core
            var topEl = document.getElementById('cpuCores');
            if (topEl) topEl.textContent = cores;
            var maxEl = document.getElementById('cpuTopMax');
            if (maxEl) maxEl.textContent = cores * 100;

            // Chart shares a fixed 0-100 axis (drawLine maps value/100), so push
            // whole-device values — system line never clips, both series
            // directly comparable, app sits below system as it physically must.
            this.pushHistory('cpuSystem', systemWholeDevice);
            this.pushHistory('cpuApp', appWholeDevice);
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
            // Honesty marker (issue #173): when the value is a hardcoded
            // freq-ratio guess (no busy counter AND no readable max-freq, e.g.
            // BYD 2602 firmware → gpuclk/650 = a constant 92.3%), flag it via a
            // tooltip so a reader doesn't mistake the estimate for a real load.
            // The displayed number is unchanged.
            const gpuValueEl = document.getElementById('gpuValue');
            if (gpuValueEl) {
                if (data.gpu.usageSource === 'freq_ratio_hardcoded') {
                    gpuValueEl.title = 'Estimated from clock ratio (no GPU busy counter available on this firmware) — not a measured load.';
                    gpuValueEl.classList.add('estimated');
                } else {
                    gpuValueEl.removeAttribute('title');
                    gpuValueEl.classList.remove('estimated');
                }
            }
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
        if (text) text.textContent = active ? BYD.i18n.t('performance.monitoring_active') : BYD.i18n.t('performance.monitoring_offline');
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
            badgeText = BYD.i18n.t('performance.gpu_overloaded');
            card.classList.add('danger');
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_locked_high');
            hintMessage = BYD.i18n.t('performance.gpu_hint_overloaded', {pct: usage.toFixed(0)});
            showHint = true;
        } else if (isOscillating && usage >= HIGH_USAGE) {
            // Oscillating with high peaks - this is HEALTHY
            status = 'efficient';
            badgeText = BYD.i18n.t('performance.gpu_healthy');
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_dynamic_scaling');
            hintMessage = BYD.i18n.t('performance.gpu_hint_healthy', {pct: usage.toFixed(0)});
            showHint = true;
        } else if (usage < 20 && freqMhz < LOW_FREQ) {
            // Idle - GPU barely working
            status = 'optimal';
            badgeText = BYD.i18n.t('performance.gpu_idle');
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_idle');
            showHint = false;
        } else if (usage >= HIGH_USAGE && freqMhz < LOW_FREQ) {
            // High usage at low frequency = EFFICIENT
            status = 'efficient';
            badgeText = BYD.i18n.t('performance.gpu_efficient');
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_efficient');
            hintMessage = BYD.i18n.t('performance.gpu_hint_efficient', {pct: usage.toFixed(0), freq: freqMhz.toFixed(0)});
            showHint = true;
        } else if (usage >= CRITICAL_USAGE && freqMhz >= HIGH_FREQ && !isOscillating) {
            // Critical only if NOT oscillating
            status = 'critical';
            badgeText = BYD.i18n.t('performance.gpu_heavy');
            card.classList.add('danger');
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_sustained');
            hintMessage = BYD.i18n.t('performance.gpu_hint_heavy', {pct: usage.toFixed(0), freq: freqMhz.toFixed(0)});
            showHint = true;
        } else if (usage >= HIGH_USAGE && freqMhz >= HIGH_FREQ) {
            // High usage at high freq but oscillating = normal burst
            status = 'optimal';
            badgeText = BYD.i18n.t('performance.gpu_active');
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_burst');
            showHint = false;
        } else if (freqMhz >= HIGH_FREQ) {
            // High freq but moderate usage - ramping up
            status = 'optimal';
            badgeText = BYD.i18n.t('performance.gpu_active');
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_active');
            showHint = false;
        } else {
            // Normal operation
            status = 'optimal';
            badgeText = '';
            subtitle.textContent = BYD.i18n.t('performance.gpu_sub_processing');
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
                { data: this.history.cpuSystem, color: this.colors.system, label: BYD.i18n.t('performance.legend_system') },
                { data: this.history.cpuApp, color: this.colors.app, label: BYD.i18n.t('performance.legend_app') }
            ]);
        } else {
            // Re-render with tooltip
            this.renderChartByType('cpu');
        }

        if (this.tooltip.chartId !== 'mem') {
            this.renderChart(this.charts.mem, [
                { data: this.history.memSystem, color: this.colors.system, label: BYD.i18n.t('performance.legend_system') },
                { data: this.history.memApp, color: this.colors.app, label: BYD.i18n.t('performance.legend_app') }
            ]);
        } else {
            this.renderChartByType('mem');
        }

        if (this.tooltip.chartId !== 'gpu') {
            this.renderChart(this.charts.gpu, [
                { data: this.history.gpu, color: this.colors.gpu, label: BYD.i18n.t('performance.card_gpu') }
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
        ctx.fillText(BYD.i18n.t('performance.axis_60s'), padding.left, height - 5);
        ctx.fillText(BYD.i18n.t('performance.axis_30s'), padding.left + chartWidth / 2, height - 5);
        ctx.fillText(BYD.i18n.t('performance.time_now'), width - padding.right, height - 5);
        
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
                ctx.strokeStyle = this.colors.textStrong;
                ctx.lineWidth = 2;
                ctx.stroke();

                tooltipData.push({ label: s.label, value: value.toFixed(1), color: s.color });
            }
        });
        
        // Calculate time ago
        const secondsAgo = (series[0].data.length - 1 - dataIndex);
        const timeLabel = secondsAgo === 0 ? BYD.i18n.t('performance.time_now') : BYD.i18n.t('performance.time_seconds_ago', {n: secondsAgo});
        
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
            ctx.fillStyle = this.colors.tooltipText;
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
    
    // ==================== DATA USAGE METHODS ====================

    /** Human-readable bytes (base-1024): 1536 -> "1.5 KB". */
    formatBytes(bytes) {
        if (bytes == null || isNaN(bytes) || bytes <= 0) return '0 B';
        var units = ['B', 'KB', 'MB', 'GB', 'TB'];
        var i = Math.floor(Math.log(bytes) / Math.log(1024));
        if (i >= units.length) i = units.length - 1;
        var val = bytes / Math.pow(1024, i);
        return (val >= 100 || i === 0 ? val.toFixed(0) : val.toFixed(1)) + ' ' + units[i];
    },

    setDataUsageRange(days) {
        this.dataUsageRange = days;
        var sel = document.getElementById('dataUsageTimeSelector');
        if (sel) {
            sel.querySelectorAll('.time-btn').forEach(function (btn) {
                btn.classList.toggle('active', parseInt(btn.dataset.days) === days);
            });
        }
        this.fetchDataUsage();
    },

    /** Fetch usage + reflect the enabled/disabled state into the card UI.
     *  EVERY exit path calls applyDataUsageState() so the card can never sit
     *  blank: on a non-2xx status, a JSON-parse throw, or a rejected/stalled
     *  fetch we fall back to painting the current (default-or-last-known) state
     *  — the hint at minimum — instead of returning early and leaving both the
     *  hint and content divs at their HTML display:none. */
    async fetchDataUsage() {
        try {
            var res = await fetch('/api/performance/data-usage?days=' + this.dataUsageRange);
            if (!res.ok) { this.applyDataUsageState(); return; }
            var data = await res.json();
            this.dataUsageData = data || { days: [], enabled: false, available: true };
            this.applyDataUsageState();
            this.updateDataUsageStats();
            this.renderDataUsageChart();
        } catch (e) {
            console.error('[Performance] data-usage fetch error:', e);
            try { this.applyDataUsageState(); } catch (e2) {}
        }
    },

    /** Show the toggle position + swap hint/content based on enabled flag. */
    applyDataUsageState() {
        var enabled = !!this.dataUsageData.enabled;
        var cb = document.getElementById('dataUsageEnabled');
        if (cb) cb.checked = enabled;
        var hint = document.getElementById('dataUsageHint');
        var content = document.getElementById('dataUsageContent');
        if (hint) hint.style.display = enabled ? 'none' : 'block';
        if (content) content.style.display = enabled ? 'block' : 'none';

        // Note line: "collecting…" until the first day lands, or a kernel-
        // unsupported message if qtaguid is unreadable.
        var note = document.getElementById('dataUsageNote');
        if (note) {
            if (enabled && this.dataUsageData.available === false) {
                note.textContent = BYD.i18n.t('performance.data_usage_unavailable');
                note.style.display = 'block';
            } else if (enabled && (!this.dataUsageData.days || this.dataUsageData.days.length === 0)) {
                note.textContent = BYD.i18n.t('performance.data_usage_collecting');
                note.style.display = 'block';
            } else {
                note.style.display = 'none';
            }
        }
    },

    /** Toggle handler wired to the checkbox onchange. */
    async toggleDataUsage(enabled) {
        try {
            // Optimistic UI swap; fetchDataUsage below reconciles with the server.
            this.dataUsageData.enabled = enabled;
            this.applyDataUsageState();
            await fetch('/api/performance/data-usage/toggle', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: enabled })
            });
        } catch (e) {
            console.error('[Performance] data-usage toggle error:', e);
        } finally {
            // Re-pull the authoritative state (also refreshes chart when enabling).
            this.fetchDataUsage();
        }
    },

    updateDataUsageStats() {
        var d = this.dataUsageData || {};
        this.updateElement('duTotal', this.formatBytes(d.total));
        this.updateElement('duWifi', this.formatBytes(d.totalWifi));
        this.updateElement('duMobile', this.formatBytes(d.totalMobile));
        this.updateElement('duApp', this.formatBytes(d.totalApp));
        this.updateElement('duSystem', this.formatBytes(d.totalSystem));
    },

    /**
     * Stacked daily bar chart: WiFi (brand-secondary) stacked on Mobile
     * (chart-warning). One bar per day over the selected range; missing days
     * render as gaps (zero-height). Uses the same canvas/DPR setup as the SOC
     * chart via createChart.
     */
    renderDataUsageChart() {
        // The Data tab starts hidden (display:none), so the canvas is 0×0 when
        // initCharts() first runs — the chart object cached at boot has width=0
        // and drawing into it paints nothing (the "data comes but no chart" bug).
        // Mirror renderVoltageChart/renderThermalChart: if the canvas isn't laid
        // out yet, defer via the IntersectionObserver; once visible, RECREATE the
        // chart so it picks up the real dimensions before drawing.
        var canvas = document.getElementById('dataUsageChart');
        if (!canvas) return;
        var r = canvas.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) {
            this._scheduleVisibilityRender('dataUsageChart', 'dataUsage');
            return;
        }
        this.charts.dataUsage = this.createChart('dataUsageChart', 'dataUsage');
        var chart = this.charts.dataUsage;
        if (!chart || !chart.ctx) return;
        var ctx = chart.ctx, width = chart.width, height = chart.height;
        var padding = { top: 15, right: 15, bottom: 30, left: 55 };
        var chartWidth = width - padding.left - padding.right;
        var chartHeight = height - padding.top - padding.bottom;

        ctx.clearRect(0, 0, width, height);

        var days = (this.dataUsageData && this.dataUsageData.days) || [];
        if (!days.length) {
            ctx.fillStyle = this.colors.text;
            ctx.font = '13px Inter, sans-serif';
            ctx.textAlign = 'center';
            ctx.fillText(BYD.i18n.t('performance.data_usage_no_data'), width / 2, height / 2);
            return;
        }

        // Peak daily total for the Y scale (min 1 MB so tiny values still show).
        var maxTotal = 1024 * 1024;
        days.forEach(function (d) { if (d.total > maxTotal) maxTotal = d.total; });

        var wifiColor = (getComputedStyle(document.documentElement)
            .getPropertyValue('--brand-secondary') || '#0EA5E9').trim();
        var mobileColor = (getComputedStyle(document.documentElement)
            .getPropertyValue('--chart-warning') || '#f59e0b').trim();

        // Grid + Y labels (4 rows).
        ctx.strokeStyle = this.colors.grid;
        ctx.lineWidth = 1;
        ctx.textAlign = 'right';
        ctx.font = '10px Inter, sans-serif';
        for (var i = 0; i <= 4; i++) {
            var y = padding.top + (chartHeight * i / 4);
            ctx.beginPath();
            ctx.moveTo(padding.left, y);
            ctx.lineTo(width - padding.right, y);
            ctx.stroke();
            ctx.fillStyle = this.colors.text;
            ctx.fillText(this.formatBytes(maxTotal * (4 - i) / 4), padding.left - 8, y + 3);
        }

        // Bars.
        var n = days.length;
        var slot = chartWidth / n;
        var barW = Math.max(2, Math.min(slot * 0.7, 28));
        var self = this;
        days.forEach(function (d, idx) {
            var cx = padding.left + slot * idx + slot / 2;
            var x = cx - barW / 2;
            var wifiH = (d.wifi / maxTotal) * chartHeight;
            var mobileH = (d.mobile / maxTotal) * chartHeight;
            var otherH = (d.other / maxTotal) * chartHeight;
            var yBase = padding.top + chartHeight;
            // Mobile at the bottom, WiFi stacked above, other on top.
            ctx.fillStyle = mobileColor;
            ctx.fillRect(x, yBase - mobileH, barW, mobileH);
            ctx.fillStyle = wifiColor;
            ctx.fillRect(x, yBase - mobileH - wifiH, barW, wifiH);
            if (otherH > 0) {
                ctx.fillStyle = self.colors.text;
                ctx.fillRect(x, yBase - mobileH - wifiH - otherH, barW, otherH);
            }
        });

        // X labels: first, middle, last day (avoid clutter over 30 bars).
        ctx.fillStyle = this.colors.text;
        ctx.textAlign = 'center';
        ctx.font = '10px Inter, sans-serif';
        var idxs = n <= 3 ? days.map(function (_, i) { return i; }) : [0, Math.floor(n / 2), n - 1];
        idxs.forEach(function (idx) {
            var cx = padding.left + slot * idx + slot / 2;
            var label = (days[idx].date || '').slice(5); // MM-DD
            ctx.fillText(label, cx, height - 10);
        });

        // Hover tooltip: highlight the hovered day's bar slot + show a box with
        // that day's WiFi / Mobile / Total. Gated on this chart being hovered.
        if (this.tooltip && this.tooltip.visible && this.tooltip.chartId === 'dataUsage'
                && this.tooltip.dataIndex >= 0 && this.tooltip.dataIndex < n) {
            var hd = days[this.tooltip.dataIndex];
            var hcx = padding.left + slot * this.tooltip.dataIndex + slot / 2;
            // Faint highlight over the hovered slot.
            ctx.fillStyle = 'rgba(255,255,255,0.06)';
            ctx.fillRect(hcx - slot / 2, padding.top, slot, chartHeight);
            var lines = [
                (hd.date || ''),
                'Total: ' + this.formatBytes(hd.total),
                'WiFi: ' + this.formatBytes(hd.wifi),
                'Mobile: ' + this.formatBytes(hd.mobile)
            ];
            if (hd.other > 0) lines.push('Other: ' + this.formatBytes(hd.other));
            ctx.font = '11px Inter, sans-serif';
            var boxW = 0;
            lines.forEach(function (l) { boxW = Math.max(boxW, ctx.measureText(l).width); });
            boxW += 16;
            var lineH = 15, boxH = lines.length * lineH + 10;
            // Keep the box inside the canvas horizontally.
            var bx = Math.min(Math.max(hcx + 8, padding.left), width - padding.right - boxW);
            var by = padding.top + 6;
            ctx.fillStyle = (this.colors.tooltipBg || 'rgba(20,20,30,0.95)');
            ctx.strokeStyle = (this.colors.tooltipBorder || 'rgba(255,255,255,0.1)');
            ctx.lineWidth = 1;
            if (ctx.fillRect) { ctx.fillRect(bx, by, boxW, boxH); ctx.strokeRect(bx, by, boxW, boxH); }
            ctx.textAlign = 'left';
            ctx.fillStyle = (this.colors.tooltipText || '#FFFFFF');
            lines.forEach(function (l, i) {
                ctx.fillText(l, bx + 8, by + 16 + i * lineH);
            });
        }
    },

    // ==================== SOC CHART METHODS ====================

    /**
     * Set SOC time range and refresh data
     */
    setSocTimeRange(hours) {
        this.socTimeRange = hours;
        
        // Update button states — scoped to SOC chart only
        const selector = document.getElementById('socTimeSelector');
        if (selector) {
            selector.querySelectorAll('.time-btn').forEach(btn => {
                btn.classList.toggle('active', parseInt(btn.dataset.hours) === hours);
            });
        }
        
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
                this.socFetchFailures = 0;
            } else {
                console.error('[Performance] SOC fetch failed:', res.status);
                this.noteSocFetchFailure();
            }
        } catch (e) {
            console.error('[Performance] SOC fetch error:', e);
            this.noteSocFetchFailure();
        }
    },

    /**
     * A failed poll leaves the previous chart frame on screen, which is indistinguishable from a
     * frozen graph. Rather than blank a still-useful chart on one transport blip, mark the readouts
     * stale only after several consecutive failures, and let a success clear it.
     */
    socFetchFailures: 0,
    SOC_STALE_AFTER_FAILURES: 3,
    noteSocFetchFailure() {
        this.socFetchFailures = (this.socFetchFailures || 0) + 1;
        if (this.socFetchFailures === this.SOC_STALE_AFTER_FAILURES) {
            // Suffix the current readout rather than wiping it: the last known value is still the
            // best information available, it just should not look live.
            //
            // BOTH readouts, because the page carries two SOC layouts — the stats card writes
            // #socCurrent (updateSocStats) and the EV dashboard writes #evSocValue. Marking only
            // the first left the cue invisible on whichever layout was showing the other, which
            // defeats the point of the marker.
            ['socCurrent', 'evSocValue'].forEach(function (id) {
                const el = document.getElementById(id);
                if (el && el.textContent && el.textContent.indexOf('\u2022') === -1) {
                    el.textContent = el.textContent + ' \u2022 stale';
                }
            });
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
        this.updateElement('evRangeValue', currentRange != null ? BYD.units.dist(currentRange) : '-- ' + BYD.units.distLabel());
        this.updateElement('evChargingStatus', isCharging ? BYD.i18n.t('performance.status_charging') : BYD.i18n.t('performance.status_idle'));
        
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
                    badge.textContent = BYD.i18n.t('performance.soh_badge', {pct: latest.soh.toFixed(0)});
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
            ctx.fillText(BYD.i18n.t('performance.soc_no_data'), width / 2, height / 2 - 10);
            ctx.font = '12px Inter, sans-serif';
            ctx.fillText(BYD.i18n.t('performance.soc_will_appear'), width / 2, height / 2 + 15);
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
            ctx.fillText(BYD.i18n.t('performance.soc_current_label'), width / 2, height / 2 + 30);
            ctx.fillText(BYD.i18n.t('performance.soc_history_will_build'), width / 2, height / 2 + 50);
            return;
        }
        
        // Find min/max for Y axis (SOC is 0-100%)
        const minSoc = 0;
        const maxSoc = 100;
        
        // Time range
        const timeStart = history[0].t;
        const timeEnd = history[history.length - 1].t;
        // Defence-in-depth: every x is (t - timeStart) / timeRange, so a non-ascending series
        // (possible only if a clock correction slipped past the backend's upper time bound and
        // monotonicity guard) would divide by <= 0 and produce Infinity/NaN coordinates — a blank
        // or garbage line that reads as a frozen graph. Fall back to 1 so the chart degenerates to
        // a readable flat/left-anchored line instead of vanishing.
        const rawRange = timeEnd - timeStart;
        const timeRange = rawRange > 0 ? rawRange : 1;
        
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

        ctx.fillStyle = this.colors.tooltipText;
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
        ctx.strokeStyle = this.colors.textStrong;
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
        ctx.fillText(point.charging ? BYD.i18n.t('performance.status_charging') : BYD.i18n.t('performance.status_not_charging'), tooltipX + boxPadding, tooltipY + boxPadding + yOffset);
        yOffset += 16;

        // Range if available
        if (point.range && point.range > 0) {
            ctx.fillStyle = this.colors.tooltipText;
            ctx.fillText(BYD.i18n.t('performance.tooltip_range_km', {km: point.range.toFixed(0)}), tooltipX + boxPadding, tooltipY + boxPadding + yOffset);
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
        
        // Update button states — scoped to voltage chart only
        const selector = document.getElementById('voltageTimeSelector');
        if (selector) {
            selector.querySelectorAll('.time-btn').forEach(btn => {
                btn.classList.toggle('active', parseInt(btn.dataset.hours) === hours);
            });
        }
        
        this.fetchBatteryHealth();
    },

    setHealthTimeRange(hours) {
        this.healthTimeRange = hours;
        
        // Update button states — scoped to health chart only
        const selector = document.getElementById('healthTimeSelector');
        if (selector) {
            selector.querySelectorAll('.time-btn').forEach(btn => {
                btn.classList.toggle('active', parseInt(btn.dataset.hours) === hours);
            });
        }
        
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
            badge.textContent = BYD.i18n.t('performance.soh_badge', {pct: soh.toFixed(0)});
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

        // Filter data to this chart's own time range
        const cutoff = Date.now() - this.batteryTimeRange * 3600 * 1000;
        const history = d.voltageHistory.filter(p => p.t >= cutoff);
        if (history.length < 2) return;
        const padding = { top: 20, right: 20, bottom: 30, left: 50 };
        const cW = W - padding.left - padding.right;
        const cH = H - padding.top - padding.bottom;

        const timeStart = history[0].t;
        const timeEnd = history[history.length - 1].t;
        // `> 0 ? :` not `|| 1` — matches the SOC render and also guards a NEGATIVE range,
        // which a non-monotonic series would produce (|| 1 only catches exactly 0).
        const rawTimeRange = timeEnd - timeStart;
        const timeRange = rawTimeRange > 0 ? rawTimeRange : 1;

        let minV = Infinity, maxV = -Infinity;
        history.forEach(p => { if (p.voltage < minV) minV = p.voltage; if (p.voltage > maxV) maxV = p.voltage; });
        minV = Math.floor(minV * 2) / 2 - 0.5;
        maxV = Math.ceil(maxV * 2) / 2 + 0.5;
        if (minV > 10.5) minV = 10.5;
        const vRange = maxV - minV || 1;

        // Grid
        ctx.strokeStyle = this.colors.grid;
        ctx.lineWidth = 1;
        for (let v = Math.ceil(minV); v <= maxV; v += 0.5) {
            const y = padding.top + cH - ((v - minV) / vRange) * cH;
            ctx.beginPath(); ctx.moveTo(padding.left, y); ctx.lineTo(W - padding.right, y); ctx.stroke();
            ctx.fillStyle = this.colors.text;
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
        ctx.fillStyle = this.colors.text;
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

        // Filter data to this chart's own time range
        const cutoff = Date.now() - this.healthTimeRange * 3600 * 1000;
        const history = d.thermalHistory.filter(p => p.t >= cutoff);
        if (history.length < 2) return;
        const padding = { top: 20, right: 20, bottom: 30, left: 50 };
        const cW = W - padding.left - padding.right;
        const cH = H - padding.top - padding.bottom;

        const timeStart = history[0].t;
        const timeEnd = history[history.length - 1].t;
        // `> 0 ? :` not `|| 1` — matches the SOC render and also guards a NEGATIVE range,
        // which a non-monotonic series would produce (|| 1 only catches exactly 0).
        const rawTimeRange = timeEnd - timeStart;
        const timeRange = rawTimeRange > 0 ? rawTimeRange : 1;

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
        ctx.strokeStyle = this.colors.grid;
        ctx.lineWidth = 1;
        for (let t = Math.ceil(minT / 5) * 5; t <= maxT; t += 5) {
            const y = padding.top + cH - ((t - minT) / tRange) * cH;
            ctx.beginPath(); ctx.moveTo(padding.left, y); ctx.lineTo(W - padding.right, y); ctx.stroke();
            ctx.fillStyle = this.colors.text;
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
        ctx.fillStyle = this.colors.text;
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
        ctx.strokeStyle = this.colors.textStrong;
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
            ctx.fillText(BYD.i18n.t('performance.status_charging'), tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += 16;
        }
        ctx.fillStyle = point.voltage < 11.5 ? '#ef4444' : '#22c55e';
        ctx.font = '11px Inter, sans-serif';
        ctx.fillText(point.voltage < 11.5 ? BYD.i18n.t('performance.status_low_voltage') : BYD.i18n.t('performance.status_normal'), tooltipX + boxPadding, tooltipY + boxPadding + yOff);
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
            ctx.strokeStyle = this.colors.textStrong;
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
            ctx.fillText(BYD.i18n.t('performance.tooltip_temp_hi', {value: point.high.toFixed(1)}), tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += lineHeight;
        }
        if (point.low != null) {
            ctx.fillStyle = '#22c55e';
            ctx.font = '12px JetBrains Mono, monospace';
            ctx.fillText(BYD.i18n.t('performance.tooltip_temp_lo', {value: point.low.toFixed(1)}), tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += lineHeight;
        }
        if (point.avg != null) {
            ctx.fillStyle = '#3b82f6';
            ctx.font = '12px JetBrains Mono, monospace';
            ctx.fillText(BYD.i18n.t('performance.tooltip_temp_avg', {value: point.avg.toFixed(1)}), tooltipX + boxPadding, tooltipY + boxPadding + yOff);
            yOff += lineHeight;
        }
        if (point.charging) {
            ctx.fillStyle = this.colors.charging || '#0ea5e9';
            ctx.font = '11px Inter, sans-serif';
            ctx.fillText(BYD.i18n.t('performance.status_charging'), tooltipX + boxPadding, tooltipY + boxPadding + yOff);
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
    },

    // ==================== SOH Detail Card ====================

    async fetchSohStatus() {
        try {
            const resp = await fetch('/api/performance/soh');
            const data = await resp.json();
            if (data.success) {
                this.updateSohDetailCard(data);
            }
        } catch (e) {
            console.warn('[Performance] SOH fetch error:', e);
        }
    },

    updateSohDetailCard(data) {
        var setEl = function(id, val) { var el = document.getElementById(id); if (el) el.textContent = val; };

        // When no nominal is set, hide the SOH percentage entirely and prompt
        // the user to configure capacity. Without a nominal the formula has
        // nothing to divide by.
        var nominalSet = data.nominalCapacityKwh > 0;
        var displaySource = data.displaySource || (data.hasEstimate ? 'live' : 'unavailable');
        var displaySoh = (typeof data.displaySoh === 'number') ? data.displaySoh : -1;

        var percentEl = document.getElementById('sohDetailPercent');
        var fallbackEl = document.getElementById('sohFallbackCaption');

        var calSoh = (data.calibration && data.calibration.soh) ? data.calibration.soh : -1;
        var calTs = (data.calibration && data.calibration.timestampMs) ? data.calibration.timestampMs : 0;
        var calDateStr = '';
        if (calTs > 0) {
            var cd = new Date(calTs);
            calDateStr = cd.getFullYear() + '-' +
                ('0' + (cd.getMonth() + 1)).slice(-2) + '-' +
                ('0' + cd.getDate()).slice(-2);
        }

        // The direct OEM health index is already a complete percentage and does not
        // require a configured nominal pack size. Calculated sources still do.
        if (!nominalSet && displaySource !== 'oem') {
            if (percentEl) {
                percentEl.style.display = '';
                percentEl.textContent = BYD.i18n.t('soh.set_battery_capacity_prompt') || '—';
            }
            if (fallbackEl) { fallbackEl.hidden = true; fallbackEl.textContent = ''; }
        } else if (displaySoh > 0 && displaySource !== 'unavailable') {
            // Any priority-chain source with a real value renders the same
            // way: show the percent. Sources include 'oem' (direct vehicle index),
            // 'frame_anchor' (peak-charge), 'capacity_ah' (BMS coulomb),
            // 'live' (derived formula), 'calibration' (charge-session anchor).
            // Source-specific captions handled below — calibration shows the
            // verified date, every other source hides the fallback caption.
            if (percentEl) {
                percentEl.style.display = '';
                percentEl.textContent = displaySoh.toFixed(1) + '%';
            }
            if (fallbackEl) {
                if (displaySource === 'calibration') {
                    var capPct = (calSoh > 0 ? calSoh : displaySoh).toFixed(1);
                    fallbackEl.textContent = BYD.i18n.t('soh.fallback_caption', {pct: capPct, date: calDateStr});
                    fallbackEl.hidden = false;
                } else {
                    fallbackEl.hidden = true;
                    fallbackEl.textContent = '';
                }
            }
        } else {
            if (percentEl) {
                percentEl.style.display = 'none';
                percentEl.textContent = '';
            }
            if (fallbackEl) {
                fallbackEl.textContent = BYD.i18n.t('soh.unavailable_caption');
                fallbackEl.hidden = false;
            }
        }

        // Calibration anchor — separate "Last verified" subline. Hidden when
        // we're already showing the calibration value as the primary readout
        // (caption above already names the date), otherwise visible.
        var calEl = document.getElementById('sohCalibrationAnchor');
        if (calEl) {
            if (calSoh > 0 && displaySource !== 'calibration') {
                calEl.textContent = BYD.i18n.t('soh.last_verified', {pct: calSoh.toFixed(1), date: calDateStr});
                calEl.style.display = 'block';
            } else {
                calEl.style.display = 'none';
            }
        }

        // Battery-capacity row — summary + source caption
        var summaryEl = document.getElementById('sohCapacitySummary');
        var sourceEl = document.getElementById('sohCapacitySource');
        if (summaryEl) {
            if (nominalSet) {
                var pieces = [data.nominalCapacityKwh.toFixed(1) + ' ' + BYD.i18n.t('soh.kwh_unit')];
                if (data.modelId) pieces.push(this._formatModelId(data.modelId));
                summaryEl.textContent = pieces.join(' · ');
            } else {
                summaryEl.textContent = BYD.i18n.t('soh.tap_to_set');
            }
        }
        if (sourceEl) {
            sourceEl.textContent = data.nominalSource ? '(' + data.nominalSource + ')' : '';
        }

        // Metadata
        setEl('sohDetailNominal', nominalSet ? data.nominalCapacityKwh.toFixed(1) + ' kWh' : '--');

        if (data.lastUpdated && data.lastUpdated > 0) {
            var d = new Date(data.lastUpdated);
            setEl('sohDetailUpdated', d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'}));
        } else {
            setEl('sohDetailUpdated', '--');
        }

        // Hint
        var hint = document.getElementById('sohDetailHint');
        if (hint) {
            if (!nominalSet && displaySource !== 'oem') {
                hint.style.display = 'block';
                hint.textContent = BYD.i18n.t('soh.set_battery_capacity_prompt');
            } else if (!data.hasEstimate) {
                hint.style.display = 'block';
                hint.textContent = BYD.i18n.t('performance.soh_no_estimate');
            } else {
                hint.style.display = 'none';
            }
        }

        // Frame mismatch banner — same logic as native dialog: PHEV-only
        // (BEV daemon never populates frameAnchor.mismatch), gated on the
        // anchor having stabilized. Also serves as the landing target for
        // the vehicle.health.soh.frame_mismatch push notification.
        var fa = data.frameAnchor || {};
        var mismatch = !!fa.mismatch && (typeof fa.peakKwh === 'number') && fa.peakKwh > 0;
        var banner = document.getElementById('sohFrameMismatchBanner');
        var bodyEl = document.getElementById('sohFrameMismatchBody');
        var actionBtn = document.getElementById('sohFrameMismatchAction');
        if (banner) {
            if (mismatch && nominalSet) {
                banner.style.display = '';
                if (bodyEl) {
                    bodyEl.textContent = BYD.i18n.t('soh.frame_mismatch_body', {
                        peak: fa.peakKwh.toFixed(1),
                        nominal: data.nominalCapacityKwh.toFixed(1)
                    });
                }
                if (actionBtn && !actionBtn._sohWired) {
                    actionBtn._sohWired = true;
                    var self = this;
                    actionBtn.addEventListener('click', function () {
                        self.applyObservedNominal(fa.peakKwh, actionBtn);
                    });
                }
                // Refresh the captured peakKwh on every poll so the click
                // handler always sees the latest reading. Without this, a
                // banner shown on the first poll would stale the value
                // even after the daemon raised the peak.
                if (actionBtn) actionBtn._sohPeakKwh = fa.peakKwh;
            } else {
                banner.style.display = 'none';
            }
        }
    },

    /**
     * One-tap "Use observed value": POST the observed peak as the user nominal.
     * Mirrors the native MainActivity.applyObservedNominal flow so both
     * surfaces stay in lockstep.
     */
    applyObservedNominal: function(observedKwhArg, btn) {
        // Read live peak from the button (set every poll) so a banner that's
        // been visible for a few cycles uses the current value, not the
        // one captured at first render.
        var observedKwh = (btn && typeof btn._sohPeakKwh === 'number')
            ? btn._sohPeakKwh : observedKwhArg;
        if (!(observedKwh > 0)) return;
        var self = this;
        var originalLabel = btn ? btn.textContent : '';
        if (btn) {
            btn.disabled = true;
            btn.textContent = BYD.i18n.t('soh.use_observed_value_pending') || 'Saving…';
        }
        var rounded = Math.round(observedKwh * 10) / 10;
        fetch('/api/performance/soh/nominal', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ nominalKwh: rounded })
        }).then(function (resp) {
            return resp.json().catch(function () { return {}; }).then(function (j) {
                return { ok: resp.ok && j.success !== false, body: j };
            });
        }).then(function (r) {
            if (r.ok) {
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(
                        BYD.i18n.t('soh.observed_value_saved', { kwh: rounded.toFixed(1) }),
                        'success');
                }
                // Re-fetch so the banner hides and the headline updates without a reload.
                self.fetchSohStatus();
            } else {
                if (btn) {
                    btn.disabled = false;
                    btn.textContent = originalLabel;
                }
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(
                        BYD.i18n.t('soh.observed_value_failed',
                            { error: (r.body && r.body.error) || 'unknown' }),
                        'warning');
                }
            }
        }).catch(function (e) {
            if (btn) {
                btn.disabled = false;
                btn.textContent = originalLabel;
            }
            if (BYD.core && BYD.core.showToast) {
                BYD.core.showToast(
                    BYD.i18n.t('soh.observed_value_failed', { error: e.message || 'network' }),
                    'warning');
            }
        });
    },

    /**
     * Scroll the SOH detail card into view and pulse the frame-mismatch
     * banner so the deep-link click from the push notification is
     * immediately obvious. No-op if the SOH card isn't on the page or the
     * banner is hidden (mismatch already cleared between notification fire
     * and page open — race-tolerant).
     */
    _scrollToSohFixBanner: function() {
        var banner = document.getElementById('sohFrameMismatchBanner');
        var card = document.getElementById('sohDetailCard');
        // Wait one frame so the just-rendered banner has its computed style
        // before we scroll/animate. Without rAF the scroll target can be
        // off-screen if the SOH tab content was lazily expanded.
        requestAnimationFrame(function () {
            if (card && card.scrollIntoView) {
                try { card.scrollIntoView({ behavior: 'smooth', block: 'center' }); } catch (_) {
                    card.scrollIntoView();
                }
            }
            if (banner && banner.style.display !== 'none') {
                banner.style.transition = 'box-shadow 600ms ease-out';
                banner.style.boxShadow = '0 0 0 4px rgba(255,176,0,0.45)';
                setTimeout(function () { banner.style.boxShadow = ''; }, 1500);
            }
        });
    },

    _formatModelId: function(id) {
        // Best-effort: capitalize first letter. Manifest titles aren't cached
        // here; the modal has the full list when the user opens it.
        if (!id) return '';
        var fallback = id.charAt(0).toUpperCase() + id.slice(1);
        return BYD.i18n.modelName(id, fallback);
    },

    // ==================== SOH Capacity Modal ====================

    openSohCapacityModal: function() {
        var self = this;
        var backdrop = document.getElementById('sohCapacityModalBackdrop');
        if (!backdrop) return;

        // Pre-populate the inputs from the current status
        var input = document.getElementById('sohCapacityModalInput');
        var modelSel = document.getElementById('sohCapacityModalModel');
        if (input) input.value = '';
        if (modelSel) modelSel.innerHTML = '';

        // Fetch current state in parallel: nominal + model + manifest
        var nominalReq = new XMLHttpRequest();
        nominalReq.open('GET', '/api/performance/soh/nominal', true);
        nominalReq.onload = function() {
            try {
                var data = JSON.parse(nominalReq.responseText);
                if (input && typeof data.nominalKwh === 'number') {
                    input.value = data.nominalKwh.toFixed(1);
                }
            } catch (e) {}
        };
        nominalReq.send();

        var manifestReq = new XMLHttpRequest();
        manifestReq.open('GET', '/api/models/manifest', true);
        manifestReq.onload = function() {
            try {
                var manifest = JSON.parse(manifestReq.responseText);
                self._populateModelDropdown(manifest);
                // Sync current selected model. Setting `select.value`
                // programmatically does NOT dispatch a `change` event, so we
                // also need to populate the kWh input from the model's
                // nominal whenever the saved-nominal fetch came back empty
                // (fresh install, never customised). Without this fallback
                // the input renders blank even though Seal (or whichever
                // model) is selected, leaving the user with no default.
                var selReq = new XMLHttpRequest();
                selReq.open('GET', '/api/models/selected', true);
                selReq.onload = function() {
                    try {
                        var sel = JSON.parse(selReq.responseText);
                        var modelId = (sel && sel.modelId) ? sel.modelId : '';
                        // If no selected model came back, fall back to the
                        // first model in the manifest — every dropdown should
                        // surface a sensible default capacity on open.
                        if (!modelId && modelSel && modelSel.options.length) {
                            modelId = modelSel.options[0].value;
                        }
                        if (modelSel && modelId) modelSel.value = modelId;
                        if (input && (!input.value || input.value === '')) {
                            var kwh = self._modelNominalById[modelId];
                            if (typeof kwh === 'number' && kwh > 0) {
                                input.value = kwh.toFixed(1);
                            }
                        }
                    } catch (e) {}
                };
                selReq.send();
            } catch (e) {}
        };
        manifestReq.send();

        backdrop.style.display = 'flex';
    },

    _populateModelDropdown: function(manifest) {
        var modelSel = document.getElementById('sohCapacityModalModel');
        if (!modelSel) return;
        modelSel.innerHTML = '';
        var models = (manifest && manifest.models) ? manifest.models : [];
        // Cache so the change handler can look up nominalKwh by id without
        // re-parsing the manifest each time the user moves the dropdown.
        this._modelNominalById = {};
        for (var i = 0; i < models.length; i++) {
            var m = models[i];
            var opt = document.createElement('option');
            opt.value = m.id || '';
            // Manifest's canonical user-facing string is "name"; older
            // copies used "title". Fall back to id when neither is set.
            var canonicalName = m.name || m.title || m.id || '';
            opt.textContent = BYD.i18n.modelName(m.id, canonicalName);
            modelSel.appendChild(opt);
            // Gross nameplate for every drivetrain. PHEV remainKwh is corrected
            // to the gross frame at the HAL read boundary (the BYD HAL reports
            // PHEV energy at half scale), so the SOH formula is gross-framed and
            // the prefilled nominal must be gross too. (The old usableKwh prefer
            // was a rationalization of that half-scale artifact — removed.)
            if (m.id && typeof m.nominalKwh === 'number' && m.nominalKwh > 0) {
                this._modelNominalById[m.id] = m.nominalKwh;
            }
        }
        // Auto-fill the kWh input when the user changes model so the
        // displayed capacity reflects the new pack rather than the old
        // one. Mirrors the Android dialog's behavior.
        var self = this;
        modelSel.onchange = function() {
            var input = document.getElementById('sohCapacityModalInput');
            if (!input) return;
            var kwh = self._modelNominalById[modelSel.value];
            if (typeof kwh === 'number' && kwh > 0) {
                input.value = kwh.toFixed(1);
            }
        };
    },

    closeSohCapacityModal: function() {
        var backdrop = document.getElementById('sohCapacityModalBackdrop');
        if (backdrop) backdrop.style.display = 'none';
    },

    saveSohCapacity: function() {
        var self = this;
        var input = document.getElementById('sohCapacityModalInput');
        var modelSel = document.getElementById('sohCapacityModalModel');
        var kwh = input ? parseFloat(input.value) : NaN;
        // Floor is 8 (not 15) to match the backend's PHEV-aware range — the
        // smallest BYD Blade DM-i gross packs sit below 15 kWh (e.g. ~8.3-12.9).
        if (isNaN(kwh) || kwh < 8 || kwh > 120) {
            alert(BYD.i18n.t('soh.modal_capacity_label') + ': 8 - 120');
            return;
        }
        var modelId = modelSel ? modelSel.value : '';

        // Persist nominal first, then model. Each request is independent;
        // a failure on either leaves the other applied (intentional — the
        // user can retry a single field).
        var nomXhr = new XMLHttpRequest();
        nomXhr.open('POST', '/api/performance/soh/nominal', true);
        nomXhr.setRequestHeader('Content-Type', 'application/json');
        nomXhr.onload = function() {
            if (modelId) {
                var modelXhr = new XMLHttpRequest();
                modelXhr.open('POST', '/api/models/selected', true);
                modelXhr.setRequestHeader('Content-Type', 'application/json');
                modelXhr.onload = function() {
                    self.closeSohCapacityModal();
                    self.fetchSohStatus();
                };
                modelXhr.onerror = function() {
                    self.closeSohCapacityModal();
                    self.fetchSohStatus();
                };
                modelXhr.send(JSON.stringify({ modelId: modelId }));
            } else {
                self.closeSohCapacityModal();
                self.fetchSohStatus();
            }
        };
        nomXhr.onerror = function() {
            self.closeSohCapacityModal();
            self.fetchSohStatus();
        };
        nomXhr.send(JSON.stringify({ nominalKwh: kwh }));
    },

    resetSohCapacityToAuto: function() {
        var self = this;
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/performance/soh/nominal', true);
        xhr.setRequestHeader('Content-Type', 'application/json');
        xhr.onload = function() {
            self.closeSohCapacityModal();
            self.fetchSohStatus();
        };
        xhr.onerror = function() {
            self.closeSohCapacityModal();
        };
        xhr.send(JSON.stringify({ nominalKwh: null }));
    },

    async resetSoh() {
        if (!confirm(BYD.i18n.t('performance.confirm_reset_soh'))) {
            return;
        }

        try {
            const resp = await fetch('/api/performance/soh/reset', { method: 'POST' });
            const data = await resp.json();
            if (data.success) {
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(BYD.i18n.t('performance.soh_reset_toast'), 'success');
                } else {
                    alert(BYD.i18n.t('performance.soh_reset_alert'));
                }
                // Refresh the card
                this.fetchSohStatus();
            } else {
                alert(BYD.i18n.t('performance.reset_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
            }
        } catch (e) {
            alert(BYD.i18n.t('performance.reset_failed', {error: e.message}));
        }
    },

    toggleAllResetCategories(checked) {
        const boxes = document.querySelectorAll('#resetCategoriesList input[type=checkbox]');
        boxes.forEach(b => { b.checked = checked; });
    },

    async runReset() {
        const boxes = document.querySelectorAll('#resetCategoriesList input[type=checkbox]:checked');
        const categories = Array.from(boxes).map(b => b.dataset.cat);
        if (categories.length === 0) {
            alert(BYD.i18n.t('performance.select_one_category'));
            return;
        }

        const labelLookup = {
            trips: BYD.i18n.t('performance.cat_trips'),
            socHistory: BYD.i18n.t('performance.cat_soc_history'),
            soh: BYD.i18n.t('performance.cat_soh'),
            abrpToken: BYD.i18n.t('performance.cat_abrp_token'),
            bydCloud: BYD.i18n.t('performance.cat_byd_cloud'),
            mediaRecordings: BYD.i18n.t('performance.cat_recordings'),
            mediaSurveillance: BYD.i18n.t('performance.cat_sentry_events'),
            mediaProximity: BYD.i18n.t('performance.cat_proximity'),
            mediaTrips: BYD.i18n.t('performance.cat_trip_telemetry')
        };
        const list = categories.map(c => '• ' + (labelLookup[c] || c)).join('\n');
        const confirmMsg = BYD.i18n.t('performance.confirm_reset_categories', {list: list});
        if (!confirm(confirmMsg)) return;

        try {
            const resp = await fetch('/api/performance/reset', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ categories })
            });
            const data = await resp.json();
            if (!data.success) {
                alert(BYD.i18n.t('performance.reset_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
                return;
            }

            // Build a per-category summary from the response
            const lines = [];
            for (const cat of categories) {
                const r = (data.results || {})[cat] || {};
                const label = labelLookup[cat] || cat;
                if (r.success) {
                    let detail = '';
                    if (r.rowsDeleted !== undefined) detail = ' ' + BYD.i18n.t('performance.rows_paren', {n: r.rowsDeleted});
                    else if (r.filesDeleted !== undefined) detail = ' ' + BYD.i18n.t('performance.files_paren', {n: r.filesDeleted});
                    lines.push('✓ ' + label + detail);
                } else {
                    lines.push('✗ ' + label + ': ' + (r.error || BYD.i18n.t('performance.failed')));
                }
            }

            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('performance.reset_complete'), 'success');
            }
            alert(lines.join('\n'));

            // Clear checkboxes; refresh visible cards
            this.toggleAllResetCategories(false);
            try { this.fetchSohStatus(); } catch (_) {}
        } catch (e) {
            alert(BYD.i18n.t('performance.reset_failed', {error: e.message}));
        }
    }
};
