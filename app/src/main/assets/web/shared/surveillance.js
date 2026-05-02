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
        preRecordSeconds: 5,
        postRecordSeconds: 10,
        recordingBitrate: 'MEDIUM',
        recordingCodec: 'H264',
        surveillanceLimitMb: 500,
        surveillanceStorageType: 'INTERNAL',
        // V2 Motion Detection
        environmentPreset: 'outdoor',
        sensitivityLevel: 3,
        detectionZone: 'normal',
        loiteringTime: 3,
        shadowFilter: 2,
        cameraFront: true,
        cameraRight: true,
        cameraLeft: true,
        cameraRear: true,
        motionHeatmap: false,
        filterDebugLog: false
    },
    storageInfo: {
        sdCardAvailable: false,
        sdCardPath: null,
        sdCardFreeSpace: 0,
        sdCardTotalSpace: 0,
        maxLimitMb: 100000,
        maxLimitMbSdCard: 100000
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
        1: { size: 0.25, label: '~3m (near)', hint: 'Near — only detects large/close objects (cars, groups)' },
        2: { size: 0.18, label: '~5m', hint: 'Close — good for parking lots, detects people nearby' },
        3: { size: 0.12, label: '~8m', hint: 'Balanced — detects people up to ~8m away' },
        4: { size: 0.08, label: '~10m', hint: 'Far — catches distant movement, may include passersby' },
        5: { size: 0.05, label: '~15m (far)', hint: 'Very Far — maximum range, use in quiet areas' }
    },

    // Sensitivity controls motion detection thresholds (requiredBlocks + densityThreshold)
    // Block size is LOCKED at 32 - only density and required count vary
    sensitivityMap: {
        1: { label: 'Strict', required: 4, density: 48, hint: 'Strict — large objects only (car/group), ignores single walkers' },
        2: { label: 'Conservative', required: 3, density: 40, hint: 'Conservative — solid objects, good for windy conditions' },
        3: { label: 'Default', required: 2, density: 32, hint: 'Balanced — triggers on walking people, ignores bugs/leaves' },
        4: { label: 'Sensitive', required: 2, density: 16, hint: 'Sensitive — catches motion immediately on block entry' },
        5: { label: 'Aggressive', required: 1, density: 12, hint: 'Aggressive — triggers on any motion, use indoors/garages only' }
    },

    flashImmunityMap: { 0: 'SENSITIVE', 1: 'NORMAL', 2: 'STRICT', 3: 'MAX' },

    // V2 sensitivity level labels
    v2SensitivityLabels: {
        1: '1 — Low',
        2: '2',
        3: '3 — Default',
        4: '4',
        5: '5 — Max'
    },

    // V2 environment presets: { sensitivityLevel, detectionZone, loiteringTime, shadowFilter }
    v2Presets: {
        outdoor:  { sensitivityLevel: 3, detectionZone: 'normal', loiteringTime: 3, shadowFilter: 2 },
        garage:   { sensitivityLevel: 4, detectionZone: 'close',  loiteringTime: 2, shadowFilter: 1 },
        street:   { sensitivityLevel: 3, detectionZone: 'normal', loiteringTime: 5, shadowFilter: 3 }
    },

    async init() {
        await this.loadConfig();
        await this.loadStorageStats();
        await this.loadCameraFps();
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
        this.startClock();
        
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
            if (document.visibilityState === 'visible' && !this.hasUnsavedChanges) {
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
    },
    
    async reloadConfig() {
        // Don't reload if user has unsaved changes
        if (this.hasUnsavedChanges) return;
        
        try {
            const resp = await fetch('/api/surveillance/config');
            const data = await resp.json();
            if (data.success && data.config) {
                // Check if config actually changed (via timestamp)
                const newTimestamp = data.config.lastModified || 0;
                if (newTimestamp > this.lastConfigTimestamp) {
                    this.config = { ...this.config, ...data.config };
                    // Use server-provided distance/sensitivity if available
                    if (!data.config.distance) {
                        this.config.distance = this.sizeToDistance(this.config.minObjectSize || 0.08);
                    }
                    if (!data.config.sensitivity) {
                        this.config.sensitivity = 3;  // Default
                    }
                    this.lastConfigTimestamp = newTimestamp;
                    
                    // Load storage settings BEFORE setting savedConfig
                    // so both config and savedConfig include the same storage values
                    await this.loadStorageSettings();
                    
                    this.savedConfig = JSON.parse(JSON.stringify(this.config));
                    this.updateUI();
                }
            }
        } catch (e) {
            console.warn('Failed to reload config:', e);
        }
    },
    
    async loadStorageSettings() {
        try {
            const resp = await fetch('/api/settings/storage');
            const data = await resp.json();
            if (data.success) {
                this.config.surveillanceLimitMb = data.surveillanceLimitMb || 500;
                this.config.surveillanceStorageType = data.surveillanceStorageType || 'INTERNAL';
                
                // Update storage info
                this.storageInfo.sdCardAvailable = data.sdCardAvailable || false;
                this.storageInfo.sdCardPath = data.sdCardPath || null;
                this.storageInfo.sdCardFreeSpace = data.sdCardFreeSpace || 0;
                this.storageInfo.sdCardTotalSpace = data.sdCardTotalSpace || 0;
                this.storageInfo.maxLimitMb = data.maxLimitMb || 100000;
                this.storageInfo.maxLimitMbSdCard = data.maxLimitMbSdCard || 100000;
                this.storageInfo.surveillancePath = data.surveillancePath || '';
                
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
            if (data.success) {
                const usedEl = document.getElementById('survStorageUsed');
                const limitEl = document.getElementById('survStorageLimit');
                const fillEl = document.getElementById('survStorageFill');
                
                if (usedEl) usedEl.textContent = data.sentrySizeFormatted + ' used';
                
                const limitMb = this.config.surveillanceLimitMb || 500;
                if (limitEl) limitEl.textContent = limitMb + ' MB limit';
                
                // Calculate percentage
                const usedBytes = data.sentrySize || 0;
                const limitBytes = limitMb * 1024 * 1024;
                const percent = Math.min(100, Math.round(usedBytes * 100 / limitBytes));
                if (fillEl) fillEl.style.width = percent + '%';
                
                // Update Events Today count
                const eventsTodayEl = document.getElementById('eventsToday');
                if (eventsTodayEl) {
                    const todayCount = data.sentryTodayCount || 0;
                    eventsTodayEl.textContent = todayCount + ' →';
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
                this.config.cameraFps = data.cameraFps || 15;
            }
        } catch (e) {
            console.warn('Failed to load camera FPS:', e);
            this.config.cameraFps = 15;
        }
    },
    
    async setFps(fps) {
        this.config.cameraFps = fps;
        document.querySelectorAll('#survFpsBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === String(fps)));
        
        // Save immediately via quality API (shared with recording page)
        try {
            await fetch('/api/settings/quality', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ cameraFps: fps })
            });
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast('Camera FPS set to ' + fps + ' — takes effect on next ACC OFF', 'success');
            }
        } catch (e) {
            console.error('Failed to save FPS:', e);
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to save FPS', 'error');
        }
    },
    
    updateStorageLimitUI() {
        const slider = document.getElementById('survLimitSlider');
        const value = document.getElementById('survLimitValue');
        
        // Update slider max based on storage type
        const maxLimit = this.config.surveillanceStorageType === 'SD_CARD' 
            ? this.storageInfo.maxLimitMbSdCard 
            : this.storageInfo.maxLimitMb;
        
        if (slider) {
            slider.max = maxLimit;
            slider.value = Math.min(this.config.surveillanceLimitMb, maxLimit);
        }
        if (value) {
            const mb = this.config.surveillanceLimitMb;
            value.textContent = mb >= 1000 ? (mb / 1000) + ' GB' : mb + ' MB';
        }
        
        // Update range labels
        const minLabel = document.getElementById('survLimitMin');
        const maxLabel = document.getElementById('survLimitMax');
        if (minLabel) minLabel.textContent = '100 MB';
        if (maxLabel) maxLabel.textContent = maxLimit >= 1000 ? (maxLimit / 1000) + ' GB' : maxLimit + ' MB';
    },
    
    updateStorageTypeUI() {
        // Update storage type buttons
        document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.surveillanceStorageType));
        
        // Update SD card button state
        const sdCardBtn = document.getElementById('btnSurvSdCard');
        if (sdCardBtn) {
            sdCardBtn.disabled = !this.storageInfo.sdCardAvailable;
            if (!this.storageInfo.sdCardAvailable) {
                sdCardBtn.title = 'SD Card not available';
            } else {
                sdCardBtn.title = '';
            }
        }
        
        // Show/hide SD card status
        const statusEl = document.getElementById('survSdCardStatus');
        if (statusEl) {
            statusEl.style.display = 'block';
            
            const dotEl = document.getElementById('survSdStatusDot');
            const textEl = document.getElementById('survSdStatusText');
            const spaceEl = document.getElementById('survSdSpaceInfo');
            
            if (this.storageInfo.sdCardAvailable) {
                if (dotEl) dotEl.className = 'sd-status-dot online';
                if (textEl) textEl.textContent = 'SD Card: Available';
                if (spaceEl) {
                    spaceEl.style.display = 'block';
                    document.getElementById('survSdFree').textContent = this.formatSize(this.storageInfo.sdCardFreeSpace) + ' free';
                    document.getElementById('survSdTotal').textContent = this.formatSize(this.storageInfo.sdCardTotalSpace) + ' total';
                }
            } else {
                if (dotEl) dotEl.className = 'sd-status-dot offline';
                if (textEl) textEl.textContent = 'SD Card: Not detected';
                if (spaceEl) spaceEl.style.display = 'none';
            }
        }
        
        // Update storage path display
        const pathEl = document.getElementById('survStoragePath');
        if (pathEl && this.storageInfo.surveillancePath) {
            const shortPath = this.storageInfo.surveillancePath.replace('/storage/emulated/0/', '');
            pathEl.textContent = 'Events saved to ' + shortPath;
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
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('SD Card not available', 'error');
            return;
        }
        
        this.config.surveillanceStorageType = type;
        document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === type));
        
        // Update slider max when storage type changes
        this.updateStorageLimitUI();
        
        // Show/hide CDR cleanup card
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
                    cdrDeletable: data.cdrDeletableFormatted,
                    totalFreed: data.totalBytesFreedFormatted,
                    totalDeleted: data.totalFilesDeleted
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
            badge.textContent = this.cdrConfig.enabled ? 'ON' : 'OFF';
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
            if (pathEl) pathEl.textContent = this.cdrInfo.cdrPath || 'Not found';
            
            const usageEl = document.getElementById('cdrUsage');
            if (usageEl) usageEl.textContent = this.cdrInfo.cdrUsage || '--';
            
            const countEl = document.getElementById('cdrFileCount');
            if (countEl) countEl.textContent = this.cdrInfo.cdrFileCount || '0';
            
            const deletableEl = document.getElementById('cdrDeletable');
            if (deletableEl) deletableEl.textContent = this.cdrInfo.cdrDeletable || '--';
            
            const freedEl = document.getElementById('cdrTotalFreed');
            if (freedEl) freedEl.textContent = this.cdrInfo.totalFreed || '0 B';
            
            const deletedEl = document.getElementById('cdrTotalDeleted');
            if (deletedEl) deletedEl.textContent = this.cdrInfo.totalDeleted || '0';
        }
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
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(enabled ? 'CDR cleanup enabled' : 'CDR cleanup disabled', 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to toggle CDR cleanup', 'error');
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
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Cleaning up dashcam files...', 'info');
            
            const resp = await fetch('/api/storage/external/cleanup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            
            if (data.success) {
                const msg = data.filesDeleted > 0 
                    ? `Freed ${data.freedFormatted} (${data.filesDeleted} files)`
                    : 'No files needed cleanup';
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');
                
                // Refresh CDR info
                this.loadCdrConfig();
            } else {
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(data.error || 'Cleanup failed', 'error');
            }
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to trigger cleanup', 'error');
        }
    },
    
    updateSurvLimit(value) {
        this.config.surveillanceLimitMb = parseInt(value);
        const v = parseInt(value);
        document.getElementById('survLimitValue').textContent = v >= 1000 ? (v / 1000) + ' GB' : v + ' MB';
        this.markChanged();
        var _su = document.getElementById('storageUnsaved'); if (_su) _su.classList.add('show');
    },

    startClock() {
        const update = () => {
            const el = document.getElementById('currentTime');
            if (el) {
                el.textContent = new Date().toLocaleTimeString('en-US', {
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
            if (!this.hasUnsavedChanges) {
                this.reloadConfig();
            }
            this.loadStorageStats();
            
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
        
        // Load storage settings
        await this.loadStorageSettings();
    },

    sizeToDistance(size) {
        if (size >= 0.22) return 1;
        if (size >= 0.15) return 2;
        if (size >= 0.10) return 3;
        if (size >= 0.06) return 4;
        return 5;
    },

    markChanged() {
        // Only compare user-editable fields (ignore server-only fields like lastModified, densityThreshold, etc.)
        const editableKeys = [
            'enabled', 'distance', 'sensitivity', 'flashImmunity',
            'detectPerson', 'detectCar', 'detectBike',
            'preRecordSeconds', 'postRecordSeconds',
            'recordingBitrate', 'recordingCodec',
            'surveillanceLimitMb', 'surveillanceStorageType',
            'environmentPreset', 'sensitivityLevel', 'detectionZone', 'loiteringTime',
            'shadowFilter',
            'cameraFront', 'cameraRight', 'cameraLeft', 'cameraRear',
            'motionHeatmap', 'filterDebugLog'
        ];
        const pick = (obj) => {
            const r = {};
            editableKeys.forEach(k => { if (k in obj) r[k] = obj[k]; });
            return JSON.stringify(r);
        };
        this.hasUnsavedChanges = pick(this.config) !== pick(this.savedConfig);
        const btn = document.getElementById('btnApply');
        if (btn) {
            btn.disabled = !this.hasUnsavedChanges;
            if (this.hasUnsavedChanges) {
                btn.classList.add('has-changes');
            } else {
                btn.classList.remove('has-changes');
            }
        }
        document.getElementById('detectionUnsaved').classList.toggle('show', this.hasUnsavedChanges);
        document.getElementById('recordingUnsaved').classList.toggle('show', this.hasUnsavedChanges);
        var _su = document.getElementById('storageUnsaved'); if (_su) _su.classList.toggle('show', this.hasUnsavedChanges);
    },

    updateUI() {
        document.getElementById('survEnabled').checked = this.config.enabled;
        
        const badge = document.getElementById('survStatusBadge');
        badge.textContent = this.config.enabled ? '● ON' : '○ OFF';
        badge.className = 'status-badge ' + (this.config.enabled ? 'active' : 'inactive');
        
        document.getElementById('preRecSlider').value = this.config.preRecordSeconds;
        document.getElementById('preRecValue').textContent = this.config.preRecordSeconds + 's';
        document.getElementById('preLabel').textContent = this.config.preRecordSeconds + 's before';
        document.getElementById('timelinePre').style.flex = this.config.preRecordSeconds / 10;
        
        document.getElementById('postRecSlider').value = this.config.postRecordSeconds;
        document.getElementById('postRecValue').textContent = this.config.postRecordSeconds + 's';
        document.getElementById('postLabel').textContent = this.config.postRecordSeconds + 's after';
        document.getElementById('timelinePost').style.flex = this.config.postRecordSeconds / 20;
        
        document.querySelectorAll('#bitrateBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingBitrate));
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingCodec));
        
        // Camera FPS buttons
        document.querySelectorAll('#survFpsBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === String(this.config.cameraFps || 15)));
        
        this.updateStorageLimitUI();
        this.updateFileSizeEstimate();
        
        // V2 Motion Detection UI
        this.updateV2UI();
        
        // Deterrent Action UI
        this.updateDeterrentUI();
        
        // Reset Apply button state after UI update (no unsaved changes after load)
        this.hasUnsavedChanges = false;
        document.getElementById('btnApply').disabled = true;
        var _du = document.getElementById('detectionUnsaved'); if (_du) _du.classList.remove('show');
        document.getElementById('recordingUnsaved').classList.remove('show');
        var _su2 = document.getElementById('storageUnsaved'); if (_su2) _su2.classList.remove('show');
    },

    updateCheckboxStyles() {
        ['detectPerson', 'detectCar', 'detectBike'].forEach(id => {
            const cb = document.getElementById(id);
            cb.parentElement.classList.toggle('active', cb.checked);
        });
    },

    async toggleSurveillance() {
        const enabled = document.getElementById('survEnabled').checked;
        try {
            await fetch(enabled ? '/api/surveillance/enable' : '/api/surveillance/disable', { method: 'POST' });
            this.config.enabled = enabled;
            this.savedConfig.enabled = enabled;
            this.updateUI();
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(enabled ? 'Surveillance enabled' : 'Surveillance disabled', 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to toggle surveillance', 'error');
        }
    },

    updateDistance(value) {
        this.config.distance = parseInt(value);
        this.config.minObjectSize = (this.distanceMap[value] || {}).size || 0.08;
        document.getElementById('distanceValue').textContent = (this.distanceMap[value] || {}).label || '~8m';
        document.getElementById('distanceHint').textContent = (this.distanceMap[value] || {}).hint || '';
        this.markChanged();
    },

    updateSensitivity(value) {
        this.config.sensitivity = parseInt(value);
        document.getElementById('sensitivityValue').textContent = (this.sensitivityMap[value] || {}).label || 'Default';
        document.getElementById('sensitivityHint').textContent = (this.sensitivityMap[value] || {}).hint || '';
        this.markChanged();
    },

    updateFlashImmunity(value) {
        this.config.flashImmunity = parseInt(value);
        document.getElementById('flashImmunityValue').textContent = this.flashImmunityMap[value] || 'MEDIUM';
        this.markChanged();
    },

    updateDetection() {
        this.config.detectPerson = document.getElementById('detectPerson').checked;
        this.config.detectCar = document.getElementById('detectCar').checked;
        this.config.detectBike = document.getElementById('detectBike').checked;
        this.updateCheckboxStyles();
        this.markChanged();
    },

    updatePreRec(value) {
        this.config.preRecordSeconds = parseInt(value);
        document.getElementById('preRecValue').textContent = value + 's';
        document.getElementById('preLabel').textContent = value + 's before';
        document.getElementById('timelinePre').style.flex = value / 10;
        this.markChanged();
    },

    updatePostRec(value) {
        this.config.postRecordSeconds = parseInt(value);
        document.getElementById('postRecValue').textContent = value + 's';
        document.getElementById('postLabel').textContent = value + 's after';
        document.getElementById('timelinePost').style.flex = value / 20;
        this.markChanged();
    },

    setBitrate(bitrate) {
        this.config.recordingBitrate = bitrate;
        document.querySelectorAll('#bitrateBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === bitrate));
        this.updateFileSizeEstimate();
        this.markChanged();
    },

    setCodec(codec) {
        this.config.recordingCodec = codec;
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === codec));
        this.updateFileSizeEstimate();
        this.markChanged();
    },

    updateFileSizeEstimate() {
        const bitrateMap = { 'LOW': 2, 'MEDIUM': 3, 'HIGH': 6 };
        const sizeMB = (bitrateMap[this.config.recordingBitrate] || 3) * 120 / 8 * 
                       (this.config.recordingCodec === 'H265' ? 0.5 : 1.0);
        const estEl = document.getElementById('estFileSize');
        if (estEl) {
            estEl.textContent = `~${Math.round(sizeMB * 0.85)}-${Math.round(sizeMB * 1.15)} MB` + 
                               (this.config.recordingCodec === 'H265' ? ' (H.265)' : '');
        }
    },

    // ==================== V2 Motion Detection ====================

    // V2 environment preset hint texts
    v2EnvPresetHints: {
        outdoor: 'Standard filtering for open parking lots and driveways. Handles sun, clouds, and headlights.',
        garage: 'Aggressive light filtering for indoor parking. Ignores fluorescent flicker and reflections.',
        street: 'Tuned for busy areas with foot traffic. Longer loitering time to ignore passersby.'
    },

    // V2 sensitivity hint texts
    v2SensitivityHints: {
        1: 'Only detects large, close movement like someone touching the car.',
        2: 'Detects solid objects nearby. Good for windy conditions.',
        3: 'Balanced — detects a person approaching within about 3 meters.',
        4: 'Sensitive — catches motion quickly, good for people at distance.',
        5: 'Maximum — detects any movement in the camera view. May increase false alerts.'
    },

    // V2 detection zone hint texts
    v2DetectionZoneHints: {
        close: 'Only triggers when someone is right next to the car (~1.5m).',
        normal: 'Standard detection range (~3m around the car).',
        extended: 'Detects activity further out (~5m+). May pick up more passing traffic.'
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
        if (_eh) _eh.textContent = this.v2EnvPresetHints[preset] || '';

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
            if (sensValue) sensValue.textContent = this.v2SensitivityLabels[p.sensitivityLevel] || p.sensitivityLevel;
            var _sh = document.getElementById('v2SensitivityHint');
            if (_sh) _sh.textContent = this.v2SensitivityHints[p.sensitivityLevel] || '';

            // Update detection zone buttons + hint
            document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
                btn.classList.toggle('active', btn.dataset.value === p.detectionZone));
            var _dh = document.getElementById('detectionZoneHint');
            if (_dh) _dh.textContent = this.v2DetectionZoneHints[p.detectionZone] || '';

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
        if (label) label.textContent = this.v2SensitivityLabels[value] || value;
        var _sh = document.getElementById('v2SensitivityHint');
        if (_sh) _sh.textContent = this.v2SensitivityHints[value] || '';
        this._deselectPresetIfCustom();
        this.markChanged();
    },

    setDetectionZone(zone) {
        this.config.detectionZone = zone;
        document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === zone));
        var _dh = document.getElementById('detectionZoneHint');
        if (_dh) _dh.textContent = this.v2DetectionZoneHints[zone] || '';
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
    
    updateShadowFilter(value) {
        this.config.shadowFilter = parseInt(value);
        const hints = {
            0: 'Shadow filtering disabled. May cause false recordings from tree shadows and cloud movement.',
            1: 'Light filtering — catches obvious shadows. Good for garages with fluorescent lights.',
            2: 'Balanced filtering — catches most tree shadows while preserving real motion detection.',
            3: 'Aggressive filtering — maximum shadow rejection. Use when parked under trees with heavy wind.'
        };
        const hint = document.getElementById('shadowFilterHint');
        if (hint) hint.textContent = hints[this.config.shadowFilter] || '';
        this.markChanged();
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
            if (_eh) _eh.textContent = this.v2EnvPresetHints[matchedPreset] || '';
        } else {
            // No preset matches — show Custom
            document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
                btn.classList.remove('active'));
            if (customBtn) customBtn.classList.add('active');
            var _eh = document.getElementById('envPresetHint');
            if (_eh) _eh.textContent = 'Custom configuration — select a preset to reset to recommended values.';
        }
    },

    updateV2Cameras() {
        this.config.cameraFront = document.getElementById('v2CameraFront').checked;
        this.config.cameraRight = document.getElementById('v2CameraRight').checked;
        this.config.cameraLeft = document.getElementById('v2CameraLeft').checked;
        this.config.cameraRear = document.getElementById('v2CameraRear').checked;
        this.markChanged();
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
     * In mosaic mode (viewMode=0), draws a 2x2 grid:
     *   [0: FRONT]  [1: RIGHT]
     *   [2: LEFT ]  [3: REAR ]
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

        // viewMode: 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left
        var viewMode = data.viewMode || 0;
        
        // Map viewMode to quadrant ID: 1→0(front), 2→1(right), 3→3(rear), 4→2(left)
        var viewModeToQuadrant = { 1: 0, 2: 1, 3: 3, 4: 2 };
        var singleQuadrant = (viewMode > 0) ? viewModeToQuadrant[viewMode] : -1;
        
        // In single-camera mode, the heatmap fills the full canvas
        // In mosaic mode, each quadrant takes a quarter
        var isSingle = singleQuadrant >= 0;
        var quadW = isSingle ? cw : cw / 2;
        var quadH = isSingle ? ch : ch / 2;
        var blockW = quadW / gridCols;
        var blockH = quadH / gridRows;

        var quadPositions = [
            [0, 0],  // 0: front  — top-left
            [1, 0],  // 1: right  — top-right
            [1, 1],  // 2: left   — bottom-right
            [0, 1]   // 3: rear   — bottom-left
        ];
        var quadLabels = ['FRONT', 'RIGHT', 'LEFT', 'REAR'];
        var threatLabels = ['', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

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
                ctx.fillText(quadLabels[q.id] + ' — OFF', qx + 8, qy + 6);
                continue;
            }

            // Suppressed quadrant (brightness shift detected)
            if (q.suppressed) {
                ctx.fillStyle = 'rgba(59, 130, 246, 0.20)';
                ctx.fillRect(qx, qy, quadW, quadH);
                ctx.fillStyle = 'rgba(147, 197, 253, 0.8)';
                ctx.fillText(quadLabels[q.id] + ' — LIGHT SHIFT', qx + 8, qy + 6);
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
                label += '  ' + activeCount + ' blocks';
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
        ctx.fillText('Low', legendX + 16, legendY + 6);
        // Yellow
        ctx.fillStyle = 'rgba(234, 179, 8, 0.7)';
        ctx.fillRect(legendX + 55, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText('Medium', legendX + 71, legendY + 6);
        // Red
        ctx.fillStyle = 'rgba(239, 68, 68, 0.7)';
        ctx.fillRect(legendX + 130, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText('High', legendX + 146, legendY + 6);
        // Blue
        ctx.fillStyle = 'rgba(59, 130, 246, 0.7)';
        ctx.fillRect(legendX + 190, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText('Suppressed', legendX + 206, legendY + 6);
    },

    updateV2UI() {
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
                _eh.textContent = this.v2EnvPresetHints[savedPreset] || '';
            } else {
                _eh.textContent = 'Custom configuration — select a preset to reset to recommended values.';
            }
        }

        // Sensitivity
        const sensSlider = document.getElementById('v2SensitivitySlider');
        if (sensSlider) sensSlider.value = this.config.sensitivityLevel;
        const sensValue = document.getElementById('v2SensitivityValue');
        if (sensValue) sensValue.textContent = this.v2SensitivityLabels[this.config.sensitivityLevel] || this.config.sensitivityLevel;
        var _sh = document.getElementById('v2SensitivityHint');
        if (_sh) _sh.textContent = this.v2SensitivityHints[this.config.sensitivityLevel] || '';

        // Detection zone
        document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.detectionZone));
        var _dh = document.getElementById('detectionZoneHint');
        if (_dh) _dh.textContent = this.v2DetectionZoneHints[this.config.detectionZone] || '';

        // Loitering time
        const loiterSlider = document.getElementById('loiteringTimeSlider');
        if (loiterSlider) loiterSlider.value = this.config.loiteringTime;
        const loiterValue = document.getElementById('loiteringTimeValue');
        if (loiterValue) loiterValue.textContent = this.config.loiteringTime + 's';

        // Shadow filter
        const shadowSelect = document.getElementById('shadowFilterSelect');
        if (shadowSelect && this.config.shadowFilter !== undefined) {
            shadowSelect.value = this.config.shadowFilter;
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

        // Developer toggles
        const hm = document.getElementById('v2MotionHeatmap');
        if (hm) hm.checked = this.config.motionHeatmap;
        const fd = document.getElementById('v2FilterDebugLog');
        if (fd) fd.checked = this.config.filterDebugLog;
        
        // Object detection checkboxes
        const dp = document.getElementById('detectPerson');
        if (dp) dp.checked = this.config.detectPerson;
        const dc = document.getElementById('detectCar');
        if (dc) dc.checked = this.config.detectCar;
        const db = document.getElementById('detectBike');
        if (db) db.checked = this.config.detectBike;
        this.updateCheckboxStyles();
    },

    async applySettings() {
        const btn = document.getElementById('btnApply');
        const origText = btn.innerHTML;
        btn.innerHTML = '⏳ Saving...';
        btn.disabled = true;
        
        try {
            // Save surveillance config
            const configResp = await fetch('/api/surveillance/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(this.config)
            });
            
            if (!configResp.ok) {
                throw new Error('Config save failed: ' + configResp.status);
            }
            
            // SOTA: Also save storage limit and type settings via storage API
            const storageResp = await fetch('/api/settings/storage', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    surveillanceLimitMb: this.config.surveillanceLimitMb,
                    surveillanceStorageType: this.config.surveillanceStorageType
                })
            });
            const storageData = await storageResp.json();
            
            this.savedConfig = JSON.parse(JSON.stringify(this.config));
            this.hasUnsavedChanges = false;
            this.markChanged();
            
            // Refresh storage stats after save (cleanup may have run)
            setTimeout(() => this.loadStorageStats(), 1000);
            
            let msg = 'Settings applied';
            
            // Show cleanup info if files will be deleted
            if (storageData.cleanup && storageData.cleanup.surveillanceToDelete) {
                msg = `Settings applied. Deleting ~${storageData.cleanup.surveillanceFilesEstimate} old events (${storageData.cleanup.surveillanceToDelete})`;
            }
            
            btn.innerHTML = '✓ Saved';
            setTimeout(() => { btn.innerHTML = origText; }, 1500);
            
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');
        } catch (e) {
            console.error('applySettings error:', e);
            btn.innerHTML = origText;
            btn.disabled = false;
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to save: ' + (e.message || 'unknown error'), 'error');
        }
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
                survState.textContent = '🏠 Safe Zone' + (status.safeZoneName ? ' (' + status.safeZoneName + ')' : '');
                survState.style.color = 'var(--brand-secondary)';
            } else {
                survState.textContent = status.gpuSurveillance ? 'Active' : 'Idle';
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
            const labels = { silent: 'SILENT', flash_lights: 'FLASH', find_car: 'HORN' };
            badge.textContent = labels[action] || 'SILENT';
            badge.className = 'status-badge ' + (action === 'silent' ? 'inactive' : 'active');
        }

        // Show warning if cloud action selected but not configured
        const warning = document.getElementById('deterrentWarning');
        if (warning) {
            const needsCloud = action !== 'silent';
            const configured = this.config.bydCloudEnabled;
            warning.style.display = (needsCloud && !configured) ? 'block' : 'none';
        }
    }
};

// Alias for backward compatibility
window.SurvSettings = BYD.surveillance;

// ── BYD Cloud Account Setup ─────────────────────────────────────────────

window.BydCloud = {
    isConfigured: false,

    async loadStatus() {
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

        if (status.verified) {
            this.isConfigured = true;
            if (badge) { badge.textContent = '\u25cf CONNECTED'; badge.className = 'status-badge active'; }
            if (info) {
                info.style.display = 'block';
                document.getElementById('bydVinDisplay').textContent = status.vin || '\u2014';
                document.getElementById('bydAccountDisplay').textContent = status.username || '\u2014';
            }
            if (clearSection) clearSection.style.display = 'block';
            if (testBtn) { testBtn.disabled = false; testBtn.style.color = 'var(--text-primary)'; testBtn.style.borderColor = 'var(--brand-primary)'; }
            if (emailInput) emailInput.value = status.username || '';
            var regionSelect = document.getElementById('bydRegion');
            if (regionSelect && status.region) regionSelect.value = status.region;
            if (pwdInput) pwdInput.placeholder = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022  (leave blank to keep)';
            if (pinInput) pinInput.placeholder = '\u2022\u2022\u2022\u2022\u2022\u2022';
            if (pwdHint) pwdHint.textContent = 'Leave blank to keep current password';
            if (pinHint) pinHint.textContent = 'Leave blank to keep current PIN';
            if (saveBtn) saveBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> Update Credentials';
        } else {
            this.isConfigured = status.configured || false;
            if (status.configured && !status.verified) {
                if (badge) { badge.textContent = 'SAVED'; badge.className = 'status-badge inactive'; }
            } else {
                if (badge) { badge.textContent = 'NOT SET'; badge.className = 'status-badge inactive'; }
            }
            if (info) info.style.display = 'none';
            if (clearSection) clearSection.style.display = 'none';
            if (testBtn) { testBtn.disabled = true; testBtn.style.color = 'var(--text-muted)'; testBtn.style.borderColor = 'var(--border-default)'; }
            if (pwdInput) pwdInput.placeholder = 'BYD app password';
            if (pinInput) pinInput.placeholder = '123456';
            if (pwdHint) pwdHint.textContent = 'Your BYD app login password';
            if (pinHint) pinHint.textContent = 'The 4-6 digit PIN you set in the official BYD app';
            if (saveBtn) saveBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> Save Credentials';
        }

        BYD.surveillance.config.bydCloudEnabled = status.verified || false;
        BYD.surveillance.updateDeterrentUI();
    },

    async saveCredentials() {
        const email = document.getElementById('bydEmail').value.trim();
        const password = document.getElementById('bydPassword').value.trim();
        const pin = document.getElementById('bydPin').value.trim();
        const region = document.getElementById('bydRegion').value;
        const saveBtn = document.getElementById('bydSaveBtn');

        if (!email) { this.showStatus('Email is required', 'error'); return; }
        if (!this.isConfigured && (!password || !pin)) {
            this.showStatus('Password and control PIN are required for first setup', 'error');
            return;
        }

        saveBtn.disabled = true;
        saveBtn.textContent = 'Saving...';
        this.showStatus('Saving credentials and testing login...', 'info');

        try {
            const body = { username: email, region: region };
            if (password) body.password = password;
            if (pin) body.controlPin = pin;

            const controller = new AbortController();
            const timeoutId = setTimeout(function() { controller.abort(); }, 30000);

            const resp = await fetch('/api/bydcloud/setup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
                signal: controller.signal
            });
            clearTimeout(timeoutId);
            const data = await resp.json();

            if (data.success) {
                this.showStatus('\u2713 Saved and verified! VIN: ' + data.vin, 'success');
                document.getElementById('bydPassword').value = '';
                document.getElementById('bydPin').value = '';
            } else {
                this.showStatus('\u2717 ' + (data.error || 'Save failed'), 'error');
            }
        } catch (e) {
            if (e.name === 'AbortError') {
                this.showStatus('Request is taking long... checking status in a moment', 'info');
                // The server might still be processing — wait and check
                await new Promise(function(r) { setTimeout(r, 5000); });
            } else {
                this.showStatus('\u2717 Network error: ' + e.message, 'error');
            }
        } finally {
            saveBtn.disabled = false;
            await this.loadStatus();
        }
    },

    async testConnection() {
        const testBtn = document.getElementById('bydTestBtn');
        testBtn.disabled = true;
        testBtn.textContent = 'Testing...';
        this.showStatus('Logging in and flashing lights...', 'info');

        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(function() { controller.abort(); }, 30000);

            const resp = await fetch('/api/bydcloud/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: 'flash_lights' }),
                signal: controller.signal
            });
            clearTimeout(timeoutId);
            const data = await resp.json();
            if (data.success) {
                this.showStatus('\u2713 Flash command sent \u2014 check your car!', 'success');
            } else {
                this.showStatus('\u2717 ' + (data.error || 'Test failed'), 'error');
            }
        } catch (e) {
            if (e.name === 'AbortError') {
                this.showStatus('Command may have been sent \u2014 check your car', 'info');
            } else {
                this.showStatus('\u2717 ' + e.message, 'error');
            }
        } finally {
            testBtn.disabled = false;
            testBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg> Test Connection';
        }
    },

    async clearCredentials() {
        if (!confirm('Clear BYD Cloud credentials?\n\nDeterrent actions (flash lights, horn) will stop working until you set up again.')) return;

        try {
            await fetch('/api/bydcloud/clear', { method: 'POST' });
            this.showStatus('Credentials cleared', 'info');
            document.getElementById('bydEmail').value = '';
            document.getElementById('bydPassword').value = '';
            document.getElementById('bydPin').value = '';
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
    }
};
