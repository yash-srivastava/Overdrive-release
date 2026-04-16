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
        surveillanceStorageType: 'INTERNAL'
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

    async init() {
        await this.loadConfig();
        await this.loadStorageStats();
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
        this.startClock();
        
        // Show CDR cleanup card if SD card is selected on load
        this.updateCdrCleanupVisibility();
        
        // Reload config when page becomes visible (user switches back to tab)
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && !this.hasUnsavedChanges) {
                this.reloadConfig();
            }
        });
    },
    
    async reloadConfig() {
        // Only reload if no unsaved changes
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
                    this.savedConfig = JSON.parse(JSON.stringify(this.config));
                    this.lastConfigTimestamp = newTimestamp;
                    this.updateUI();
                    console.log('Config reloaded (timestamp:', newTimestamp, ')');
                }
            }
        } catch (e) {
            console.warn('Failed to reload config:', e);
        }
        
        // Also reload storage settings
        await this.loadStorageSettings();
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
        
        // SOTA: More frequent config refresh (every 10s) to catch app UI changes quickly
        setInterval(() => {
            if (!this.hasUnsavedChanges) {
                this.reloadConfig();
            }
            this.loadStorageStats();  // Always refresh storage stats
            
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
        this.hasUnsavedChanges = JSON.stringify(this.config) !== JSON.stringify(this.savedConfig);
        document.getElementById('btnApply').disabled = !this.hasUnsavedChanges;
        document.getElementById('detectionUnsaved').classList.toggle('show', this.hasUnsavedChanges);
        document.getElementById('recordingUnsaved').classList.toggle('show', this.hasUnsavedChanges);
        var _su = document.getElementById('storageUnsaved'); if (_su) _su.classList.toggle('show', this.hasUnsavedChanges);
    },

    updateUI() {
        document.getElementById('survEnabled').checked = this.config.enabled;
        
        const badge = document.getElementById('survStatusBadge');
        badge.textContent = this.config.enabled ? '● ON' : '○ OFF';
        badge.className = 'status-badge ' + (this.config.enabled ? 'active' : 'inactive');
        
        // Distance slider with hint
        document.getElementById('distanceSlider').value = this.config.distance;
        document.getElementById('distanceValue').textContent = (this.distanceMap[this.config.distance] || {}).label || '~8m';
        document.getElementById('distanceHint').textContent = (this.distanceMap[this.config.distance] || {}).hint || '';
        
        // Sensitivity slider with hint
        document.getElementById('sensitivitySlider').value = this.config.sensitivity;
        document.getElementById('sensitivityValue').textContent = (this.sensitivityMap[this.config.sensitivity] || {}).label || 'Default';
        document.getElementById('sensitivityHint').textContent = (this.sensitivityMap[this.config.sensitivity] || {}).hint || '';
        
        document.getElementById('flashImmunitySlider').value = this.config.flashImmunity;
        document.getElementById('flashImmunityValue').textContent = this.flashImmunityMap[this.config.flashImmunity] || 'MEDIUM';
        
        document.getElementById('detectPerson').checked = this.config.detectPerson;
        document.getElementById('detectCar').checked = this.config.detectCar;
        document.getElementById('detectBike').checked = this.config.detectBike;
        this.updateCheckboxStyles();
        
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
        
        this.updateStorageLimitUI();
        this.updateFileSizeEstimate();
        
        // Reset Apply button state after UI update (no unsaved changes after load)
        this.hasUnsavedChanges = false;
        document.getElementById('btnApply').disabled = true;
        document.getElementById('detectionUnsaved').classList.remove('show');
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

    async applySettings() {
        try {
            // Save surveillance config
            await fetch('/api/surveillance/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(this.config)
            });
            
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
            
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast('Failed to save settings', 'error');
        }
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
        
        // Reload config if surveillance state changed and no unsaved changes
        const newEnabled = status.gpuSurveillance || false;
        if (newEnabled !== this.config.enabled && !this.hasUnsavedChanges) {
            this.reloadConfig();
        }
    }
};

// Alias for backward compatibility
window.SurvSettings = BYD.surveillance;
