/**
 * BYD Champ - Recording Settings Module
 * SOTA: Uses unified config for cross-UID access (app UI + web UI sync)
 * SOTA: Storage limits with auto-cleanup (100MB - 100GB internal/SD card)
 * SOTA: Storage type selection (internal vs SD card)
 */

window.BYD = window.BYD || {};

BYD.recording = {
    config: {
        recordingQuality: 'NORMAL',
        streamingQuality: 'LQ',
        recordingBitrate: 'MEDIUM',
        recordingCodec: 'H264',
        cameraFps: 15,
        recordingsLimitMb: 500,
        recordingsStorageType: 'INTERNAL',
        recordingMode: 'NONE',
        proximityGuard: {
            triggerLevel: 'RED',
            preRecordSeconds: 5,
            postRecordSeconds: 10
        }
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
    savedConfig: null,
    hasUnsavedChanges: false,
    lastConfigTimestamp: 0,  // Track config file timestamp for sync

    async init() {
        await this.loadConfig();
        await this.loadStorageStats();
        await this.loadTelemetryOverlay();
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
        
        // Load CDR cleanup config if SD card is selected
        if (this.config.recordingsStorageType === 'SD_CARD') {
            this.updateCdrCleanupVisibility();
        }
        
        // Status polling is handled by core.js - no need to duplicate
        
        // Reload config when page becomes visible (user switches back to tab)
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && !this.hasUnsavedChanges) {
                this.reloadConfig();
            }
        });
        
        // SOTA: More frequent config refresh (every 10s) to catch app UI changes quickly
        setInterval(() => {
            if (!this.hasUnsavedChanges) {
                this.reloadConfig();
            }
            this.loadStorageStats();  // Always refresh storage stats
            
            // Refresh CDR info if visible
            if (this.config.recordingsStorageType === 'SD_CARD') {
                this.loadCdrConfig();
            }
        }, 10000);
    },
    
    async reloadConfig() {
        // Only reload if no unsaved changes
        if (this.hasUnsavedChanges) return;
        
        try {
            const resp = await fetch('/api/settings/quality');
            const data = await resp.json();
            if (data.success) {
                // Check if config actually changed (via timestamp)
                const newTimestamp = data.lastModified || 0;
                if (newTimestamp > this.lastConfigTimestamp) {
                    this.config.recordingQuality = data.recordingQuality || 'NORMAL';
                    this.config.streamingQuality = data.streamingQuality || 'LQ';
                    this.config.recordingBitrate = data.recordingBitrate || 'MEDIUM';
                    this.config.recordingCodec = data.recordingCodec || 'H264';
                    this.config.cameraFps = data.cameraFps || 15;
                    this.savedConfig = JSON.parse(JSON.stringify(this.config));
                    this.lastConfigTimestamp = newTimestamp;
                    this.updateUI();
                    console.log('Recording config reloaded (timestamp:', newTimestamp, ')');
                }
            }
        } catch (e) {
            console.warn('Failed to reload config:', e);
        }
        
        // Reload recording mode
        try {
            const modeResp = await fetch('/api/recording/mode');
            const modeData = await modeResp.json();
            if (modeData.status === 'ok') {
                this.config.recordingMode = modeData.mode || 'NONE';
            }
        } catch (e) {}
        
        // Reload proximity guard config and recording mode from unified config
        try {
            const proxResp = await fetch('/api/settings/unified');
            const proxData = await proxResp.json();
            if (proxData.success && proxData.config) {
                // Load recording mode from unified config if available (overrides /api/recording/mode)
                if (proxData.config.recording && proxData.config.recording.mode) {
                    this.config.recordingMode = proxData.config.recording.mode;
                }
                
                // Merge proximity guard with defaults
                if (proxData.config.proximityGuard) {
                    const serverConfig = proxData.config.proximityGuard;
                    this.config.proximityGuard = {
                        triggerLevel: serverConfig.triggerLevel || this.config.proximityGuard.triggerLevel || 'RED',
                        preRecordSeconds: serverConfig.preRecordSeconds || this.config.proximityGuard.preRecordSeconds || 5,
                        postRecordSeconds: serverConfig.postRecordSeconds || this.config.proximityGuard.postRecordSeconds || 10
                    };
                }
            }
        } catch (e) {}
        
        // Also reload storage settings
        await this.loadStorageSettings();
        
        // Reload telemetry overlay state
        await this.loadTelemetryOverlay();
        
        // Update UI with all reloaded settings
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
    },

    async loadConfig() {
        try {
            const resp = await fetch('/api/settings/quality');
            const data = await resp.json();
            if (data.success) {
                this.config.recordingQuality = data.recordingQuality || 'NORMAL';
                this.config.streamingQuality = data.streamingQuality || 'LQ';
                this.config.recordingBitrate = data.recordingBitrate || 'MEDIUM';
                this.config.recordingCodec = data.recordingCodec || 'H264';
                this.config.cameraFps = data.cameraFps || 15;
                this.lastConfigTimestamp = data.lastModified || Date.now();
            }
        } catch (e) {}
        
        // Load recording mode
        try {
            const modeResp = await fetch('/api/recording/mode');
            const modeData = await modeResp.json();
            if (modeData.status === 'ok') {
                this.config.recordingMode = modeData.mode || 'NONE';
            }
        } catch (e) {}
        
        // Load proximity guard config and recording mode from unified config
        try {
            const proxResp = await fetch('/api/settings/unified');
            const proxData = await proxResp.json();
            console.log('Unified config response:', proxData);
            if (proxData.success && proxData.config) {
                // Load recording mode from unified config if available
                if (proxData.config.recording && proxData.config.recording.mode) {
                    this.config.recordingMode = proxData.config.recording.mode;
                    console.log('Loaded recording mode from unified:', this.config.recordingMode);
                }
                
                // Merge proximity guard with defaults to handle missing fields
                if (proxData.config.proximityGuard) {
                    const serverConfig = proxData.config.proximityGuard;
                    this.config.proximityGuard = {
                        triggerLevel: serverConfig.triggerLevel || this.config.proximityGuard.triggerLevel || 'RED',
                        preRecordSeconds: serverConfig.preRecordSeconds || this.config.proximityGuard.preRecordSeconds || 5,
                        postRecordSeconds: serverConfig.postRecordSeconds || this.config.proximityGuard.postRecordSeconds || 10
                    };
                    console.log('Loaded proximity guard config:', this.config.proximityGuard);
                }
            }
        } catch (e) {
            console.warn('Failed to load unified config:', e);
        }
        
        // Load storage settings
        await this.loadStorageSettings();
    },
    
    async loadStorageSettings() {
        try {
            const resp = await fetch('/api/settings/storage');
            const data = await resp.json();
            if (data.success) {
                this.config.recordingsLimitMb = data.recordingsLimitMb || 500;
                this.config.recordingsStorageType = data.recordingsStorageType || 'INTERNAL';
                
                // Update storage info
                this.storageInfo.sdCardAvailable = data.sdCardAvailable || false;
                this.storageInfo.sdCardPath = data.sdCardPath || null;
                this.storageInfo.sdCardFreeSpace = data.sdCardFreeSpace || 0;
                this.storageInfo.sdCardTotalSpace = data.sdCardTotalSpace || 0;
                this.storageInfo.maxLimitMb = data.maxLimitMb || 100000;
                this.storageInfo.maxLimitMbSdCard = data.maxLimitMbSdCard || 100000;
                this.storageInfo.recordingsPath = data.recordingsPath || '';
                
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
                const usedEl = document.getElementById('storageUsed');
                const limitEl = document.getElementById('storageLimit');
                const fillEl = document.getElementById('storageFill');
                
                if (usedEl) usedEl.textContent = BYD.i18n.t('recording.storage_used', {size: data.normalSizeFormatted});

                const limitMb = this.config.recordingsLimitMb || 500;
                if (limitEl) limitEl.textContent = BYD.i18n.t('recording.storage_limit_mb', {mb: limitMb});
                
                // Calculate percentage
                const usedBytes = data.normalSize || 0;
                const limitBytes = limitMb * 1024 * 1024;
                const percent = Math.min(100, Math.round(usedBytes * 100 / limitBytes));
                if (fillEl) fillEl.style.width = percent + '%';
                
                // Update Recordings Today count
                const recTodayEl = document.getElementById('recToday');
                if (recTodayEl) {
                    // Include normal + proximity recordings for today
                    const todayCount = (data.normalTodayCount || 0) + (data.proximityTodayCount || 0);
                    recTodayEl.textContent = todayCount + ' →';
                }
            }
        } catch (e) {
            console.warn('Failed to load storage stats:', e);
        }
    },
    
    updateStorageLimitUI() {
        const slider = document.getElementById('recLimitSlider');
        const value = document.getElementById('recLimitValue');
        
        // Update slider max based on storage type
        const maxLimit = this.config.recordingsStorageType === 'SD_CARD' 
            ? this.storageInfo.maxLimitMbSdCard 
            : this.storageInfo.maxLimitMb;
        
        if (slider) {
            slider.max = maxLimit;
            slider.value = Math.min(this.config.recordingsLimitMb, maxLimit);
        }
        if (value) {
            const mb = this.config.recordingsLimitMb;
            value.textContent = mb >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (mb / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: mb});
        }

        // Update range labels
        const minLabel = document.getElementById('recLimitMin');
        const maxLabel = document.getElementById('recLimitMax');
        if (minLabel) minLabel.textContent = BYD.i18n.t('recording.unit_mb', {n: 100});
        if (maxLabel) maxLabel.textContent = maxLimit >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (maxLimit / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: maxLimit});
    },
    
    updateStorageTypeUI() {
        // Update storage type buttons
        document.querySelectorAll('#recStorageTypeBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingsStorageType));
        
        // Update SD card button state
        const sdCardBtn = document.getElementById('btnRecSdCard');
        if (sdCardBtn) {
            sdCardBtn.disabled = !this.storageInfo.sdCardAvailable;
            if (!this.storageInfo.sdCardAvailable) {
                sdCardBtn.title = BYD.i18n.t('recording.sd_card_unavailable');
            } else {
                sdCardBtn.title = '';
            }
        }
        
        // Show/hide SD card status
        const statusEl = document.getElementById('recSdCardStatus');
        if (statusEl) {
            statusEl.style.display = 'block';
            
            const dotEl = document.getElementById('recSdStatusDot');
            const textEl = document.getElementById('recSdStatusText');
            const spaceEl = document.getElementById('recSdSpaceInfo');
            
            if (this.storageInfo.sdCardAvailable) {
                if (dotEl) dotEl.className = 'sd-status-dot online';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_available');
                if (spaceEl) {
                    spaceEl.style.display = 'block';
                    document.getElementById('recSdFree').textContent = BYD.i18n.t('recording.size_free', {size: this.formatSize(this.storageInfo.sdCardFreeSpace)});
                    document.getElementById('recSdTotal').textContent = BYD.i18n.t('recording.size_total', {size: this.formatSize(this.storageInfo.sdCardTotalSpace)});
                }
            } else {
                if (dotEl) dotEl.className = 'sd-status-dot offline';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_not_detected');
                if (spaceEl) spaceEl.style.display = 'none';
            }
        }
        
        // Update storage path display
        const pathEl = document.getElementById('recStoragePath');
        if (pathEl && this.storageInfo.recordingsPath) {
            const shortPath = this.storageInfo.recordingsPath.replace('/storage/emulated/0/', '');
            pathEl.textContent = BYD.i18n.t('recording.saved_to', {path: shortPath});
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
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.sd_card_unavailable'), 'error');
            return;
        }
        
        this.config.recordingsStorageType = type;
        document.querySelectorAll('#recStorageTypeBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === type));
        
        // Update slider max when storage type changes
        this.updateStorageLimitUI();
        
        // Show/hide CDR cleanup card
        this.updateCdrCleanupVisibility();
        
        this.markChanged();
    },
    
    // ==================== CDR Cleanup ====================
    
    cdrConfig: {
        enabled: false,
        reservedSpaceMb: 2000,
        protectedHours: 24,
        minFilesKeep: 10
    },
    
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
                    cdrProtected: data.cdrProtectedFormatted,
                    cdrDeletable: data.cdrDeletableFormatted,
                    totalFreed: data.totalBytesFreedFormatted,
                    totalDeleted: data.totalFilesDeleted,
                    monitoringActive: !!data.monitoringActive,
                    lastCleanupTime: data.lastCleanupTime || 0,
                    recommendAutoCleanup: !!data.recommendAutoCleanup
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
            const showCard = this.config.recordingsStorageType === 'SD_CARD' && this.storageInfo.sdCardAvailable;
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
            badge.textContent = this.cdrConfig.enabled ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
            badge.className = 'status-badge ' + (this.cdrConfig.enabled ? 'active' : 'inactive');
        }
        
        // Update sliders
        const reservedSlider = document.getElementById('cdrReservedSlider');
        const reservedValue = document.getElementById('cdrReservedValue');
        if (reservedSlider) reservedSlider.value = this.cdrConfig.reservedSpaceMb;
        if (reservedValue) reservedValue.textContent = this.cdrConfig.reservedSpaceMb >= 1000
            ? BYD.i18n.t('recording.unit_gb', {n: (this.cdrConfig.reservedSpaceMb / 1000)})
            : BYD.i18n.t('recording.unit_mb', {n: this.cdrConfig.reservedSpaceMb});

        const protectedSlider = document.getElementById('cdrProtectedSlider');
        const protectedValue = document.getElementById('cdrProtectedValue');
        if (protectedSlider) protectedSlider.value = this.cdrConfig.protectedHours;
        if (protectedValue) protectedValue.textContent = BYD.i18n.t('recording.unit_hours', {n: this.cdrConfig.protectedHours});
        
        const minKeepSlider = document.getElementById('cdrMinKeepSlider');
        const minKeepValue = document.getElementById('cdrMinKeepValue');
        if (minKeepSlider) minKeepSlider.value = this.cdrConfig.minFilesKeep;
        if (minKeepValue) minKeepValue.textContent = this.cdrConfig.minFilesKeep;
        
        // Update info
        if (this.cdrInfo) {
            const pathEl = document.getElementById('cdrPath');
            if (pathEl) pathEl.textContent = this.cdrInfo.cdrPath || BYD.i18n.t('recording.not_found');

            const usageEl = document.getElementById('cdrUsage');
            if (usageEl) usageEl.textContent = this.cdrInfo.cdrUsage || '--';

            const countEl = document.getElementById('cdrFileCount');
            if (countEl) countEl.textContent = this.cdrInfo.cdrFileCount || '0';

            const protEl = document.getElementById('cdrProtected');
            if (protEl) protEl.textContent = this.cdrInfo.cdrProtected || '--';

            const deletableEl = document.getElementById('cdrDeletable');
            if (deletableEl) deletableEl.textContent = this.cdrInfo.cdrDeletable || '--';

            const monEl = document.getElementById('cdrMonitoring');
            if (monEl) {
                if (!this.cdrConfig.enabled) {
                    monEl.textContent = BYD.i18n.t('common.disabled');
                    monEl.style.color = '';
                } else if (this.cdrInfo.monitoringActive) {
                    monEl.textContent = BYD.i18n.t('common.running');
                    monEl.style.color = '#22c55e';
                } else {
                    monEl.textContent = BYD.i18n.t('common.idle');
                    monEl.style.color = '#94a3b8';
                }
            }

            const lastEl = document.getElementById('cdrLastCleanup');
            if (lastEl) lastEl.textContent = this._formatRelativeTime(this.cdrInfo.lastCleanupTime);

            const banner = document.getElementById('cdrRecommendBanner');
            if (banner) banner.style.display = this.cdrInfo.recommendAutoCleanup ? 'block' : 'none';

            const freedEl = document.getElementById('cdrTotalFreed');
            if (freedEl) freedEl.textContent = this.cdrInfo.totalFreed || '0 B';

            const deletedEl = document.getElementById('cdrTotalDeleted');
            if (deletedEl) deletedEl.textContent = this.cdrInfo.totalDeleted || '0';
        }
    },

    _formatRelativeTime(ts) {
        if (!ts || ts <= 0) return BYD.i18n.t('recording.never');
        const diffSec = Math.floor((Date.now() - ts) / 1000);
        if (diffSec < 0) return BYD.i18n.t('recording.just_now');
        if (diffSec < 60) return BYD.i18n.t('recording.seconds_ago', {n: diffSec});
        if (diffSec < 3600) return BYD.i18n.t('recording.minutes_ago', {n: Math.floor(diffSec / 60)});
        if (diffSec < 86400) return BYD.i18n.t('recording.hours_ago', {n: Math.floor(diffSec / 3600)});
        return BYD.i18n.t('recording.days_ago', {n: Math.floor(diffSec / 86400)});
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
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(enabled ? BYD.i18n.t('recording.cdr_enabled') : BYD.i18n.t('recording.cdr_disabled'), 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_toggle_failed'), 'error');
        }
    },
    
    updateCdrReserved(value) {
        this.cdrConfig.reservedSpaceMb = parseInt(value);
        const el = document.getElementById('cdrReservedValue');
        const v = parseInt(value);
        if (el) el.textContent = v >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (v / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: v});
        this.saveCdrConfig();
    },

    updateCdrProtected(value) {
        this.cdrConfig.protectedHours = parseInt(value);
        const el = document.getElementById('cdrProtectedValue');
        if (el) el.textContent = BYD.i18n.t('recording.unit_hours', {n: parseInt(value)});
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
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_cleaning'), 'info');

            const resp = await fetch('/api/storage/external/cleanup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();

            if (data.success) {
                const msg = data.filesDeleted > 0
                    ? BYD.i18n.t('recording.cdr_freed', {size: data.freedFormatted, files: data.filesDeleted})
                    : BYD.i18n.t('recording.cdr_no_cleanup');
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');

                // Refresh CDR info
                this.loadCdrConfig();
            } else {
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(data.error || BYD.i18n.t('recording.cdr_cleanup_failed'), 'error');
            }
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_trigger_failed'), 'error');
        }
    },
    
    updateRecLimit(value) {
        this.config.recordingsLimitMb = parseInt(value);
        const v = parseInt(value);
        document.getElementById('recLimitValue').textContent = v >= 1000 ? BYD.i18n.t('recording.unit_gb', {n: (v / 1000)}) : BYD.i18n.t('recording.unit_mb', {n: v});
        this.markChanged();
    },

    markChanged() {
        this.hasUnsavedChanges = JSON.stringify(this.config) !== JSON.stringify(this.savedConfig);
        const btn = document.getElementById('btnApply');
        if (btn) {
            btn.disabled = !this.hasUnsavedChanges;
        }
    },

    updateUI() {
        document.querySelectorAll('#recQualityBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingQuality));
        document.querySelectorAll('#streamQualityBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.streamingQuality));
        document.querySelectorAll('#bitrateBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingBitrate));
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingCodec));
        document.querySelectorAll('#fpsBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === String(this.config.cameraFps)));
        
        // Update recording mode radio buttons
        const modeRadio = document.querySelector(`input[name="recordingMode"][value="${this.config.recordingMode}"]`);
        if (modeRadio) modeRadio.checked = true;
        
        // Show/hide proximity settings
        this.updateProximitySettingsVisibility();
        
        // Update proximity guard settings
        const triggerLevel = document.getElementById('triggerLevel');
        if (triggerLevel) triggerLevel.value = this.config.proximityGuard.triggerLevel || 'RED';
        
        const preSlider = document.getElementById('preRecordSlider');
        const preValue = document.getElementById('preRecordValue');
        if (preSlider && preValue) {
            preSlider.value = this.config.proximityGuard.preRecordSeconds || 5;
            preValue.textContent = BYD.i18n.t('recording.unit_seconds', {n: preSlider.value});
            document.getElementById('timelinePre').textContent = BYD.i18n.t('recording.unit_seconds', {n: preSlider.value});
        }

        const postSlider = document.getElementById('postRecordSlider');
        const postValue = document.getElementById('postRecordValue');
        if (postSlider && postValue) {
            postSlider.value = this.config.proximityGuard.postRecordSeconds || 10;
            postValue.textContent = BYD.i18n.t('recording.unit_seconds', {n: postSlider.value});
            document.getElementById('timelinePost').textContent = BYD.i18n.t('recording.unit_seconds', {n: postSlider.value});
        }
        
        this.updateStorageLimitUI();
        this.updateStorageTypeUI();
        this.updateFileSizeEstimate();
        
        // Show CDR cleanup card if SD card is selected
        this.updateCdrCleanupVisibility();
        
        // Reset Apply button state after UI update (no unsaved changes after load)
        this.hasUnsavedChanges = false;
        const btn = document.getElementById('btnApply');
        if (btn) {
            btn.disabled = true;
        }
    },
    
    onModeChange(mode) {
        this.config.recordingMode = mode;
        this.updateProximitySettingsVisibility();
        this.markChanged();
    },
    
    updateProximitySettingsVisibility() {
        const card = document.getElementById('proximitySettingsCard');
        if (card) {
            card.style.display = this.config.recordingMode === 'PROXIMITY_GUARD' ? 'block' : 'none';
        }
    },
    
    updatePreRecord(value) {
        this.config.proximityGuard.preRecordSeconds = parseInt(value);
        document.getElementById('preRecordValue').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        document.getElementById('timelinePre').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        this.markChanged();
    },

    updatePostRecord(value) {
        this.config.proximityGuard.postRecordSeconds = parseInt(value);
        document.getElementById('postRecordValue').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        document.getElementById('timelinePost').textContent = BYD.i18n.t('recording.unit_seconds', {n: value});
        this.markChanged();
    },
    
    markDirty() {
        // Update triggerLevel from select when called
        const triggerLevel = document.getElementById('triggerLevel');
        if (triggerLevel) {
            this.config.proximityGuard.triggerLevel = triggerLevel.value;
        }
        this.markChanged();
    },

    setRecQuality(quality) {
        this.config.recordingQuality = quality;
        document.querySelectorAll('#recQualityBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === quality));
        this.markChanged();
    },

    setStreamQuality(quality) {
        this.config.streamingQuality = quality;
        document.querySelectorAll('#streamQualityBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === quality));
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

    setFps(fps) {
        this.config.cameraFps = fps;
        document.querySelectorAll('#fpsBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === String(fps)));
        this.markChanged();
    },

    updateFileSizeEstimate() {
        const bitrateMap = { 'LOW': 2, 'MEDIUM': 3, 'HIGH': 6 };
        const sizeMB = (bitrateMap[this.config.recordingBitrate] || 3) * 120 / 8 *
                       (this.config.recordingCodec === 'H265' ? 0.5 : 1.0);
        const estEl = document.getElementById('estFileSize');
        if (estEl) {
            const minMb = Math.round(sizeMB * 0.85);
            const maxMb = Math.round(sizeMB * 1.15);
            const key = this.config.recordingCodec === 'H265' ? 'recording.est_file_size_h265' : 'recording.est_file_size_range';
            estEl.textContent = BYD.i18n.t(key, {min: minMb, max: maxMb});
        }
    },

    updateRetention(value) {
        // Deprecated - retention days no longer used
        console.log('Retention days setting deprecated');
    },

    async saveSettings() {
        try {
            // Save quality settings
            const resp = await fetch('/api/settings/quality', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    recordingQuality: this.config.recordingQuality,
                    streamingQuality: this.config.streamingQuality,
                    recordingBitrate: this.config.recordingBitrate,
                    recordingCodec: this.config.recordingCodec,
                    cameraFps: this.config.cameraFps
                })
            });
            const data = await resp.json();
            
            // Save recording mode
            await fetch('/api/recording/mode', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mode: this.config.recordingMode })
            });
            
            // Save recording section to unified config (includes mode)
            await fetch('/api/settings/unified', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    section: 'recording',
                    data: {
                        bitrate: this.config.recordingBitrate,
                        codec: this.config.recordingCodec,
                        quality: this.config.recordingQuality,
                        mode: this.config.recordingMode
                    }
                })
            });
            
            // Save proximity guard settings
            await fetch('/api/settings/unified', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    section: 'proximityGuard',
                    data: this.config.proximityGuard
                })
            });
            
            // Save storage limit and type settings
            const storageResp = await fetch('/api/settings/storage', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    recordingsLimitMb: this.config.recordingsLimitMb,
                    recordingsStorageType: this.config.recordingsStorageType
                })
            });
            const storageData = await storageResp.json();
            
            this.savedConfig = JSON.parse(JSON.stringify(this.config));
            this.hasUnsavedChanges = false;
            // Update timestamp to prevent immediate reload overwriting our changes
            this.lastConfigTimestamp = Date.now();
            this.markChanged();
            
            // Refresh storage stats after save (cleanup may have run)
            setTimeout(() => this.loadStorageStats(), 1000);
            
            let msg = BYD.i18n.t('recording.settings_applied');
            if (this.config.recordingCodec === 'H265') msg += ' - ' + BYD.i18n.t('recording.h265_next_recording');
            if (this.config.cameraFps !== (this.savedConfig ? this.savedConfig.cameraFps : 15)) {
                msg += ' - ' + BYD.i18n.t('recording.fps_next_acc_on');
            }

            // Show cleanup info if files will be deleted
            if (storageData.cleanup && storageData.cleanup.recordingsToDelete) {
                msg = BYD.i18n.t('recording.settings_applied_deleting', {files: storageData.cleanup.recordingsFilesEstimate, size: storageData.cleanup.recordingsToDelete});
            }

            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.save_settings_failed'), 'error');
        }
    },

    // ==================== Telemetry Overlay ====================

    async loadTelemetryOverlay() {
        try {
            const resp = await fetch('/api/settings/telemetry-overlay');
            const data = await resp.json();
            if (data.success) {
                const toggle = document.getElementById('telemetryOverlayEnabled');
                if (toggle) toggle.checked = data.enabled || false;
            }
        } catch (e) {
            console.warn('Failed to load telemetry overlay state:', e);
        }
    },

    async toggleTelemetryOverlay() {
        const toggle = document.getElementById('telemetryOverlayEnabled');
        if (!toggle) return;
        const enabled = toggle.checked;
        try {
            const resp = await fetch('/api/settings/telemetry-overlay', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            });
            const data = await resp.json();
            if (data.success) {
                toggle.checked = data.enabled;
                if (BYD.utils && BYD.utils.toast) {
                    BYD.utils.toast(data.enabled ? BYD.i18n.t('recording.telemetry_overlay_enabled') : BYD.i18n.t('recording.telemetry_overlay_disabled'), 'success');
                }
            } else {
                toggle.checked = !enabled;
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.overlay_update_failed'), 'error');
            }
        } catch (e) {
            toggle.checked = !enabled;
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.overlay_update_failed'), 'error');
        }
    }
};

// Alias for backward compatibility
window.RecSettings = BYD.recording;
