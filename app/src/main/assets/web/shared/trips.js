/**
 * OverDrive - Trip Analytics Module v2
 * Modern trip list, interactive timeline slider, route map with marker,
 * radar hover tooltips, score descriptions, speed distribution details.
 */

const TRIPS = {
    // State
    currentOffset: 0,
    currentDays: 7,
    pageSize: 20,
    trips: [],
    currentTripId: null,
    leafletMap: null,
    routeLayer: null,
    sliderMarker: null,
    telemetryCache: null,
    radarScoresCache: null,
    rangeCache: null,
    pendingStorageType: null,
    pendingStorageLimit: null,
    electricityRate: 0,
    currency: '$',

    colors: {
        brand: '#00D4AA',
        brandRgba: 'rgba(0, 212, 170, 0.25)',
        accent: '#0EA5E9',
        danger: '#EF4444',
        warning: '#F59E0B',
        text: 'rgba(255, 255, 255, 0.5)',
        grid: 'rgba(255, 255, 255, 0.08)',
        speedGreen: '#22C55E',
        speedYellow: '#F59E0B',
        speedRed: '#EF4444',
    },

    // Criteria metadata for tooltips and descriptions
    criteriaInfo: {
        anticipation: {
            label: 'Anticipation',
            icon: '🔮',
            desc: 'How well you read traffic ahead and lift off the accelerator early before braking.',
            tip: 'Coast more before red lights. Lift off the pedal 3–5 seconds earlier than usual.'
        },
        smoothness: {
            label: 'Smoothness',
            icon: '🌊',
            desc: 'How gradually you apply and release the accelerator and brake pedals.',
            tip: 'Pretend there\'s a glass of water on the dashboard. Smooth transitions keep it steady.'
        },
        speedDiscipline: {
            label: 'Speed Discipline',
            icon: '🎯',
            desc: 'How consistently you maintain appropriate speeds without frequent speeding.',
            tip: 'Use cruise control on highways. Staying within 5 km/h of the limit saves energy.'
        },
        efficiency: {
            label: 'Efficiency',
            icon: '⚡',
            desc: 'Your energy consumption per km compared to the optimal baseline for your vehicle, route terrain, and conditions.',
            tip: 'Pre-condition the cabin while plugged in. Scores adjust for terrain — hills won\'t penalize you unfairly.'
        },
        consistency: {
            label: 'Consistency',
            icon: '📐',
            desc: 'How uniform your driving style is throughout the trip. Erratic behavior lowers this.',
            tip: 'Pick a driving style and stick with it. Consistent habits build muscle memory.'
        }
    },

    // ==================== INIT ====================

    async init() {
        console.log('[Trips] Initializing v2...');
        await this.loadConfig();
        await this.loadStorageSettings();
        await this.loadCdrInfo();
        await this.loadDna();
        await this.loadSummary(7);
        await this.loadRange();
        await this.loadTrips(this.currentDays, 0);
        console.log('[Trips] Initialized');
    },

    // ==================== CONFIG ====================

    async loadConfig() {
        try {
            const resp = await fetch('/api/trips/config');
            const data = await resp.json();
            if (data.success && data.config) {
                const el = document.getElementById('tripsEnabled');
                if (el) el.checked = data.config.enabled || false;
                // Load electricity rate
                this.electricityRate = data.config.electricityRate || 0;
                this.currency = data.config.currency || '$';
                const rateInput = document.getElementById('rateInput');
                const currSelect = document.getElementById('currencySelect');
                if (rateInput && this.electricityRate > 0) rateInput.value = this.electricityRate;
                if (currSelect) currSelect.value = this.currency;
                // Update currency icons
                this.updateCurrencyIcons();
            }
        } catch (e) { console.warn('[Trips] Config load failed:', e); }
    },

    async toggleEnabled() {
        const checked = document.getElementById('tripsEnabled').checked;
        try {
            await fetch('/api/trips/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: checked })
            });
        } catch (e) { console.warn('[Trips] Toggle failed:', e); }
    },

    async saveCostConfig() {
        const rateInput = document.getElementById('rateInput');
        const currSelect = document.getElementById('currencySelect');
        const rate = rateInput ? parseFloat(rateInput.value) || 0 : 0;
        const currency = currSelect ? currSelect.value : '$';
        this.electricityRate = rate;
        this.currency = currency;
        try {
            await fetch('/api/trips/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ electricityRate: rate, currency: currency })
            });
            this.updateCurrencyIcons();
            this.updatePeriodSummary();
            this.updateCostHero();
        } catch (e) { console.warn('[Trips] Save cost config failed:', e); }
    },

    updateCurrencyIcons() {
        // Cost circle icons show the user's selected currency symbol
        const c = this.currency || '$';
        const icon1 = document.getElementById('costCircleIcon');
        const icon2 = document.getElementById('costCircleIconActive');
        if (icon1) icon1.textContent = c;
        if (icon2) icon2.textContent = c;
    },

    // ==================== STORAGE ====================

    async loadStorageSettings() {
        try {
            const resp = await fetch('/api/trips/storage');
            const data = await resp.json();
            if (data.success && data.storage) {
                const s = data.storage;
                const intBtn = document.getElementById('storageInternal');
                const sdBtn = document.getElementById('storageSdCard');
                if (intBtn && sdBtn) {
                    intBtn.classList.toggle('active', s.storageType === 'INTERNAL');
                    sdBtn.classList.toggle('active', s.storageType === 'SD_CARD');
                }
                const slider = document.getElementById('storageLimitSlider');
                if (slider) slider.value = s.limitMb || 500;
                this.updateLimitLabel(s.limitMb || 500);
                this.renderStorageUsage(s.usedMb || 0, s.limitMb || 500, s.tripsCount || 0, s.usedUnit || 'MB');

                // Storage path
                const pathEl = document.getElementById('tripStoragePath');
                if (pathEl && s.storagePath) {
                    pathEl.textContent = s.storagePath;
                } else if (pathEl) {
                    pathEl.textContent = s.storageType === 'SD_CARD' ? 'SD Card / Overdrive / trips' : 'Internal / Overdrive / trips';
                }

                // SD card status
                const sdStatus = document.getElementById('tripSdCardStatus');
                const sdDot = document.getElementById('tripSdStatusDot');
                const sdText = document.getElementById('tripSdStatusText');
                const sdSpaceInfo = document.getElementById('tripSdSpaceInfo');
                const sdFree = document.getElementById('tripSdFree');
                const sdTotal = document.getElementById('tripSdTotal');

                if (s.storageType === 'SD_CARD' && sdStatus) {
                    sdStatus.style.display = 'block';
                    if (s.sdCardAvailable) {
                        if (sdDot) { sdDot.classList.add('online'); sdDot.classList.remove('offline'); }
                        if (sdText) sdText.textContent = 'SD Card: Available';
                    } else {
                        if (sdDot) { sdDot.classList.add('offline'); sdDot.classList.remove('online'); }
                        if (sdText) sdText.textContent = 'SD Card: Not detected';
                    }
                } else if (sdStatus) {
                    sdStatus.style.display = 'none';
                }

                // Show/hide dashcam cleanup card based on storage type
                const cdrCard = document.getElementById('tripCdrCleanupCard');
                if (cdrCard) cdrCard.style.display = s.storageType === 'SD_CARD' ? 'block' : 'none';
                // Load CDR info if SD card
                if (s.storageType === 'SD_CARD') this.loadCdrInfo();

                this.pendingStorageType = null;
                this.pendingStorageLimit = null;
                this.resetApplyButton();
            }
        } catch (e) { console.warn('[Trips] Storage load failed:', e); }
    },

    setStorageType(type) {
        this.pendingStorageType = type;
        const intBtn = document.getElementById('storageInternal');
        const sdBtn = document.getElementById('storageSdCard');
        if (intBtn && sdBtn) {
            intBtn.classList.toggle('active', type === 'INTERNAL');
            sdBtn.classList.toggle('active', type === 'SD_CARD');
        }
        // Show/hide dashcam cleanup card
        const cdrCard = document.getElementById('tripCdrCleanupCard');
        if (cdrCard) cdrCard.style.display = type === 'SD_CARD' ? 'block' : 'none';
        this.showApplyNeeded();
    },

    setStorageLimit(limitMb) {
        this.pendingStorageLimit = parseInt(limitMb);
        this.updateLimitLabel(limitMb);
        this.showApplyNeeded();
    },

    updateLimitLabel(val) {
        const el = document.getElementById('storageLimitValue');
        const desc = document.getElementById('storageLimitDesc');
        const v = parseInt(val);
        const label = v >= 1000 ? (v / 1000) + ' GB' : v + ' MB';
        if (el) el.textContent = label;
        if (desc) desc.textContent = label;
    },

    showApplyNeeded() {
        const btn = document.getElementById('storageApplyBtn');
        if (btn) { btn.disabled = false; btn.textContent = 'Apply Changes'; }
    },

    resetApplyButton() {
        const btn = document.getElementById('storageApplyBtn');
        if (btn) { btn.disabled = true; btn.textContent = 'Apply Changes'; }
    },

    async applyStorageSettings() {
        const btn = document.getElementById('storageApplyBtn');
        const body = {};
        if (this.pendingStorageType !== null) body.storageType = this.pendingStorageType;
        if (this.pendingStorageLimit !== null) body.storageLimitMb = this.pendingStorageLimit;

        if (btn) { btn.disabled = true; btn.textContent = 'Applying...'; }

        try {
            // Save storage settings
            if (Object.keys(body).length > 0) {
                await fetch('/api/trips/storage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
            }

            // Save cost config
            await this.saveCostConfig();

            this.pendingStorageType = null;
            this.pendingStorageLimit = null;
            if (btn) {
                btn.textContent = '\u2713 Applied';
                setTimeout(() => { btn.textContent = 'Apply Changes'; btn.disabled = true; }, 2000);
            }
            await this.loadStorageSettings();
        } catch (e) {
            console.warn('[Trips] Apply storage failed:', e);
            if (btn) { btn.disabled = false; btn.textContent = 'Apply Changes'; }
        }
    },

    // ==================== CDR CLEANUP ====================

    async toggleCdrCleanup() {
        const el = document.getElementById('tripCdrEnabled');
        const enabled = el ? el.checked : false;
        try {
            await fetch('/api/storage/external/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: enabled })
            });
            const badge = document.getElementById('tripCdrBadge');
            if (badge) {
                badge.textContent = enabled ? 'ON' : 'OFF';
                badge.className = 'status-badge ' + (enabled ? 'active' : 'inactive');
            }
        } catch (e) { console.warn('[Trips] CDR toggle failed:', e); }
    },

    updateCdrReserved(val) {
        const el = document.getElementById('tripCdrReservedValue');
        if (el) el.textContent = (val / 1000).toFixed(1) + ' GB';
    },

    updateCdrProtected(val) {
        const el = document.getElementById('tripCdrProtectedValue');
        if (el) el.textContent = val + 'h';
    },

    updateCdrMinKeep(val) {
        const el = document.getElementById('tripCdrMinKeepValue');
        if (el) el.textContent = val;
    },

    async triggerCdrCleanup() {
        if (!confirm('Delete old BYD dashcam files to free space?')) return;
        try {
            const resp = await fetch('/api/storage/external/cleanup', { method: 'POST' });
            const data = await resp.json();
            if (data.success) {
                this.setEl('tripCdrTotalFreed', data.freedFormatted || '--');
                this.setEl('tripCdrTotalDeleted', (data.deletedCount || 0) + ' files');
                this.loadCdrInfo();
            }
        } catch (e) { console.warn('[Trips] CDR cleanup failed:', e); }
    },

    async loadCdrInfo() {
        try {
            const resp = await fetch('/api/storage/external');
            const data = await resp.json();
            if (!data.success) return;

            // SD card space — show the status section
            const sdStatus = document.getElementById('tripSdCardStatus');
            const sdDot = document.getElementById('tripSdStatusDot');
            const sdText = document.getElementById('tripSdStatusText');
            const sdSpaceInfo = document.getElementById('tripSdSpaceInfo');

            if (sdStatus) sdStatus.style.display = 'block';
            if (data.sdCardAvailable) {
                if (sdDot) { sdDot.classList.add('online'); sdDot.classList.remove('offline'); }
                if (sdText) sdText.textContent = 'SD Card: Available';
                if (sdSpaceInfo && data.sdCardFree !== undefined) {
                    sdSpaceInfo.style.display = 'block';
                    this.setEl('tripSdFree', data.sdCardFreeFormatted || ((data.sdCardFree / (1024 * 1024 * 1024)).toFixed(1) + ' GB') + ' free');
                    this.setEl('tripSdTotal', data.sdCardTotalFormatted || ((data.sdCardTotal / (1024 * 1024 * 1024)).toFixed(1) + ' GB') + ' total');
                }
            } else {
                if (sdDot) { sdDot.classList.add('offline'); sdDot.classList.remove('online'); }
                if (sdText) sdText.textContent = 'SD Card: Not detected';
            }

            // CDR info
            this.setEl('tripCdrPath', data.cdrPath || '--');
            this.setEl('tripCdrUsage', data.cdrUsageFormatted || '--');
            this.setEl('tripCdrFileCount', data.cdrFileCount || '--');
            this.setEl('tripCdrDeletable', data.cdrDeletableFormatted || '--');
            this.setEl('tripCdrTotalFreed', data.totalBytesFreedFormatted || '--');
            this.setEl('tripCdrTotalDeleted', data.totalFilesDeleted || '--');

            // Config
            const cdrEnabled = document.getElementById('tripCdrEnabled');
            const cdrBadge = document.getElementById('tripCdrBadge');
            if (cdrEnabled) cdrEnabled.checked = data.cleanupEnabled || false;
            if (cdrBadge) {
                cdrBadge.textContent = data.cleanupEnabled ? 'ON' : 'OFF';
                cdrBadge.className = 'status-badge ' + (data.cleanupEnabled ? 'active' : 'inactive');
            }
            if (data.reservedSpaceMb) {
                const rs = document.getElementById('tripCdrReservedSlider');
                if (rs) rs.value = data.reservedSpaceMb;
                this.setEl('tripCdrReservedValue', (data.reservedSpaceMb / 1000).toFixed(1) + ' GB');
            }
            if (data.protectedHours !== undefined) {
                const ps = document.getElementById('tripCdrProtectedSlider');
                if (ps) ps.value = data.protectedHours;
                this.setEl('tripCdrProtectedValue', data.protectedHours + 'h');
            }
            if (data.minFilesKeep !== undefined) {
                const ms = document.getElementById('tripCdrMinKeepSlider');
                if (ms) ms.value = data.minFilesKeep;
                this.setEl('tripCdrMinKeepValue', data.minFilesKeep);
            }
        } catch (e) { /* CDR info not critical */ }
    },

    // Calendar state
    calendarMonth: null,
    calendarYear: null,
    selectedDate: null,

    filterByDays(days) {
        document.querySelectorAll('.filter-tab').forEach(btn => {
            btn.classList.toggle('active', parseInt(btn.dataset.days) === days);
        });
        this.currentDays = days;
        this.currentOffset = 0;
        this.trips = [];
        this.selectedDate = null;
        const toggle = document.getElementById('calendarToggle');
        const btnText = document.getElementById('calendarBtnText');
        if (toggle) toggle.classList.remove('has-date');
        if (btnText) btnText.textContent = 'Select Date';
        this.loadTrips(days, 0);
        this.loadSummary(days);
    },

    quickFilter(days, btn) {
        document.querySelectorAll('.filter-tab').forEach(b => b.classList.remove('active'));
        if (btn) btn.classList.add('active');
        this.filterByDays(days);
    },

    filterByDateRange() {
        // Not used anymore — kept for compat
    },

    // Calendar popup
    toggleCalendar() {
        const popup = document.getElementById('calendarPopup');
        if (popup.classList.contains('active')) {
            this.closeCalendar();
        } else {
            const now = new Date();
            this.calendarMonth = now.getMonth();
            this.calendarYear = now.getFullYear();
            this.renderCalendar();
            popup.classList.add('active');
        }
    },

    closeCalendar() {
        document.getElementById('calendarPopup').classList.remove('active');
    },

    prevMonth() {
        this.calendarMonth--;
        if (this.calendarMonth < 0) { this.calendarMonth = 11; this.calendarYear--; }
        this.renderCalendar();
    },

    nextMonth() {
        this.calendarMonth++;
        if (this.calendarMonth > 11) { this.calendarMonth = 0; this.calendarYear++; }
        this.renderCalendar();
    },

    renderCalendar() {
        const grid = document.getElementById('calendarGrid');
        const title = document.getElementById('calendarTitle');
        if (!grid) return;

        const months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
        title.textContent = months[this.calendarMonth] + ' ' + this.calendarYear;

        grid.innerHTML = '';
        const weekdays = ['Su','Mo','Tu','We','Th','Fr','Sa'];
        weekdays.forEach(d => {
            const el = document.createElement('div');
            el.className = 'calendar-weekday';
            el.textContent = d;
            grid.appendChild(el);
        });

        const firstDay = new Date(this.calendarYear, this.calendarMonth, 1).getDay();
        const daysInMonth = new Date(this.calendarYear, this.calendarMonth + 1, 0).getDate();
        const today = new Date();

        // Build set of days that have trips
        const tripDays = new Set();
        this.trips.forEach(t => {
            const d = new Date(t.startTime || t.start_time);
            if (d.getMonth() === this.calendarMonth && d.getFullYear() === this.calendarYear) {
                tripDays.add(d.getDate());
            }
        });

        // Previous month padding
        const prevDays = new Date(this.calendarYear, this.calendarMonth, 0).getDate();
        for (let i = firstDay - 1; i >= 0; i--) {
            const el = document.createElement('div');
            el.className = 'calendar-day other-month';
            el.textContent = prevDays - i;
            grid.appendChild(el);
        }

        // Current month days
        for (let d = 1; d <= daysInMonth; d++) {
            const el = document.createElement('div');
            el.className = 'calendar-day';
            el.textContent = d;

            const dateObj = new Date(this.calendarYear, this.calendarMonth, d);
            if (dateObj > today) el.classList.add('other-month');
            if (d === today.getDate() && this.calendarMonth === today.getMonth() && this.calendarYear === today.getFullYear()) {
                el.classList.add('today');
            }
            if (this.selectedDate && d === this.selectedDate.getDate() && this.calendarMonth === this.selectedDate.getMonth() && this.calendarYear === this.selectedDate.getFullYear()) {
                el.classList.add('selected');
            }
            if (tripDays.has(d)) {
                el.classList.add('has-trips');
            }

            el.onclick = () => this.selectCalendarDate(d);
            grid.appendChild(el);
        }

        // Also fetch trip dates for this month if we don't have them cached
        this.loadCalendarDots();
    },

    async loadCalendarDots() {
        try {
            const startOfMonth = new Date(this.calendarYear, this.calendarMonth, 1);
            const endOfMonth = new Date(this.calendarYear, this.calendarMonth + 1, 0);
            const days = Math.ceil((endOfMonth - startOfMonth) / 86400000) + 1;
            const resp = await fetch('/api/trips?days=' + days + '&limit=200');
            const data = await resp.json();
            if (data.success && data.trips) {
                const tripDays = new Set();
                data.trips.forEach(t => {
                    const d = new Date(t.startTime || t.start_time);
                    if (d.getMonth() === this.calendarMonth && d.getFullYear() === this.calendarYear) {
                        tripDays.add(d.getDate());
                    }
                });
                // Update dots on existing calendar days
                document.querySelectorAll('#calendarGrid .calendar-day:not(.other-month)').forEach(el => {
                    const day = parseInt(el.textContent);
                    if (tripDays.has(day)) el.classList.add('has-trips');
                });
            }
        } catch (e) { /* silent */ }
    },

    selectCalendarDate(day) {
        this.selectedDate = new Date(this.calendarYear, this.calendarMonth, day);
        const toggle = document.getElementById('calendarToggle');
        const btnText = document.getElementById('calendarBtnText');
        const dateStr = this.selectedDate.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });

        if (toggle) toggle.classList.add('has-date');
        if (btnText) btnText.innerHTML = dateStr + ' <span class="clear-date-btn" onclick="event.stopPropagation(); TRIPS.clearDate()">✕</span>';

        document.querySelectorAll('.filter-tab').forEach(b => b.classList.remove('active'));
        this.closeCalendar();

        // Clear current trips and load for the selected date
        this.trips = [];
        this.currentOffset = 0;
        this.renderTripList([]);
        document.getElementById('tripEmptyState').style.display = 'none';

        // Calculate days from selected date to now
        const now = new Date();
        const diffDays = Math.ceil((now - this.selectedDate) / 86400000) + 1;
        this.currentDays = diffDays;
        this.loadTripsForDate(this.selectedDate);
        this.loadSummary(1);
    },

    async loadTripsForDate(date) {
        try {
            // Load enough days to include the selected date, then filter client-side
            const now = new Date();
            const diffDays = Math.ceil((now - date) / 86400000) + 1;
            const resp = await fetch('/api/trips?days=' + diffDays + '&limit=200');
            const data = await resp.json();

            const skel = document.getElementById('tripListSkeleton');
            if (skel) skel.style.display = 'none';

            if (data.success && data.trips) {
                // Filter to only trips on the selected date
                const selectedDay = date.toDateString();
                const filtered = data.trips.filter(t => {
                    const tripDate = new Date(t.startTime || t.start_time);
                    return tripDate.toDateString() === selectedDay;
                });

                if (filtered.length > 0) {
                    this.trips = filtered;
                    this.renderTripList(filtered);
                    document.getElementById('tripEmptyState').style.display = 'none';
                    document.getElementById('loadMoreBtn').style.display = 'none';
                } else {
                    this.trips = [];
                    this.renderTripList([]);
                    document.getElementById('tripEmptyState').style.display = 'flex';
                    document.getElementById('loadMoreBtn').style.display = 'none';
                }
            } else {
                document.getElementById('tripEmptyState').style.display = 'flex';
            }
        } catch (e) {
            console.warn('[Trips] Load trips for date failed:', e);
            document.getElementById('tripEmptyState').style.display = 'flex';
        }
    },

    clearDate() {
        this.selectedDate = null;
        const toggle = document.getElementById('calendarToggle');
        const btnText = document.getElementById('calendarBtnText');
        if (toggle) toggle.classList.remove('has-date');
        if (btnText) btnText.textContent = 'Select Date';
        this.quickFilter(7, document.querySelector('.filter-tab[data-days="7"]'));
    },

    renderStorageUsage(usedMb, limitMb, count, unit) {
        const fill = document.getElementById('storageUsageFill');
        const usedText = document.getElementById('storageUsedText');
        const countText = document.getElementById('storageTripsCount');
        const u = unit || 'MB';

        if (fill) {
            const pct = u === 'KB' ? ((usedMb / 1024) / limitMb * 100) : (usedMb / limitMb * 100);
            fill.style.width = Math.min(100, Math.max(pct, count > 0 ? 0.5 : 0)) + '%';
        }

        if (usedText) {
            if (u === 'KB') {
                usedText.textContent = usedMb + ' KB / ' + limitMb + ' MB';
            } else if (usedMb === 0 && count > 0) {
                usedText.textContent = '< 1 MB / ' + limitMb + ' MB';
            } else {
                usedText.textContent = usedMb.toFixed(1) + ' MB / ' + limitMb + ' MB';
            }
        }

        if (countText) countText.textContent = count + (count === 1 ? ' trip stored' : ' trips stored');
    },

    // ==================== TRIP LIST ====================

    async loadTrips(days, offset) {
        try {
            const resp = await fetch('/api/trips?days=' + days + '&limit=' + this.pageSize);
            const data = await resp.json();
            const skel = document.getElementById('tripListSkeleton');
            if (skel) skel.style.display = 'none';

            if (data.success && data.trips && data.trips.length > 0) {
                if (offset === 0) this.trips = [];
                this.trips = this.trips.concat(data.trips);
                this.currentOffset = this.trips.length;
                this.renderTripList(this.trips);
                const btn = document.getElementById('loadMoreBtn');
                if (btn) btn.style.display = data.trips.length >= this.pageSize ? 'block' : 'none';
                document.getElementById('tripEmptyState').style.display = 'none';
            } else if (offset === 0) {
                // No trips for this period — clear the list and show empty state.
                this.trips = [];
                this.renderTripList([]);
                document.getElementById('tripEmptyState').style.display = 'flex';
                document.getElementById('loadMoreBtn').style.display = 'none';
            }
        } catch (e) {
            console.warn('[Trips] Load trips failed:', e);
            const skel = document.getElementById('tripListSkeleton');
            if (skel) skel.style.display = 'none';
        }
    },

    loadMore() {
        this.currentDays += 30;
        this.loadTrips(this.currentDays, this.currentOffset);
    },

    renderTripList(trips) {
        const container = document.getElementById('tripList');
        if (!container) return;
        const skel = document.getElementById('tripListSkeleton');
        container.innerHTML = '';
        if (skel) container.appendChild(skel);

        const groups = {};
        trips.forEach(t => {
            const day = new Date(t.startTime || t.start_time).toLocaleDateString('en-US', {
                weekday: 'long', month: 'short', day: 'numeric'
            });
            if (!groups[day]) groups[day] = [];
            groups[day].push(t);
        });

        Object.keys(groups).forEach(day => {
            const header = document.createElement('div');
            header.className = 'day-header';
            header.textContent = day;
            container.appendChild(header);
            groups[day].forEach(trip => container.appendChild(this.createTripCard(trip)));
        });

        // Update period summary and cost from loaded trips
        this.updatePeriodSummary();
        this.updateCostHero();
    },

    createTripCard(trip) {
        const card = document.createElement('div');
        card.className = 'trip-card';
        card.onclick = () => this.showDetail(trip.id);

        const startTime = new Date(trip.startTime || trip.start_time);
        const timeStr = startTime.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
        const dist = (trip.distanceKm || trip.distance_km || 0).toFixed(1);
        const dur = this.formatDuration(trip.durationSeconds || trip.duration_seconds || 0);
        const avgScore = this.getAvgScore(trip);
        const scoreClass = avgScore >= 70 ? '' : avgScore >= 40 ? 'mid' : 'low';
        const eff = (trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0).toFixed(2);
        const avgSpd = Math.round(trip.avgSpeedKmh || trip.avg_speed_kmh || 0);
        const socStart = (trip.socStart || trip.soc_start || 0).toFixed(1);
        const socEnd = (trip.socEnd || trip.soc_end || 0).toFixed(1);
        const tripId = trip.id;
        const energyUsed = trip.energyUsedKwh || trip.energy_used_kwh || 0;
        const tripCost = trip.tripCost || trip.trip_cost || 0;
        // Build cost string: prefer stored cost, then compute from energy, then from SoC
        let costStr = '';
        const cur = trip.currency || this.currency || '$';
        if (tripCost > 0) {
            costStr = cur + tripCost.toFixed(1);
        } else if (energyUsed > 0 && this.electricityRate > 0) {
            costStr = cur + (energyUsed * this.electricityRate).toFixed(1);
        } else if (this.electricityRate > 0) {
            // Fallback: estimate from SoC delta for old trips without kWh data
            const socStart = trip.socStart || trip.soc_start || 0;
            const socEnd = trip.socEnd || trip.soc_end || 0;
            if (socStart > socEnd && socStart > 0) {
                const socDelta = socStart - socEnd;
                // Derive nominal from kwhStart if available, else use 82.56 kWh default
                const kwhStart = trip.kwhStart || trip.kwh_start || 0;
                const nominal = (kwhStart > 0 && socStart > 5) ? kwhStart / (socStart / 100) : 82.56;
                const estEnergy = (socDelta / 100) * nominal;
                costStr = '~' + cur + (estEnergy * this.electricityRate).toFixed(1);
            }
        }

        const elevGain = trip.elevationGainM || trip.elevation_gain_m || 0;
        const gradProfile = trip.gradientProfile || trip.gradient_profile || '';
        const gradIcons = { FLAT: '🛣️', HILLY: '⛰️', MOUNTAIN_CLIMB: '🏔️', MOUNTAIN_DESCENT: '⬇️' };
        const elevStr = elevGain > 0 ? (gradIcons[gradProfile] || '') + ' +' + Math.round(elevGain) + 'm' : '';

        card.innerHTML =
            '<div class="trip-card-top">' +
                '<span class="trip-time" style="font-size: 18px;">' + timeStr + '</span>' +
            '</div>' +
            '<div class="trip-score-badge ' + scoreClass + '">' + avgScore + '</div>' +
            '<div class="trip-capsules">' +
                '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/><circle cx="12" cy="12" r="10"/></svg> ' + dist + ' km</span>' +
                '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> ' + dur + '</span>' +
                '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg> ' + (energyUsed > 0 ? energyUsed.toFixed(1) + ' kWh' : eff + ' %/km') + '</span>' +
                '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="6" y="7" width="12" height="10" rx="1"/><path d="M18 10h2a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-2"/></svg> ' + socStart + '→' + socEnd + '%</span>' +
                (elevStr ? '<span class="trip-capsule" style="color:#0EA5E9;">' + elevStr + '</span>' : '') +
                (costStr ? '<span class="trip-capsule" style="color:var(--warning);"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg> ' + costStr + '</span>' : '') +
            '</div>' +
            '<button class="trip-delete-btn" onclick="event.stopPropagation(); TRIPS.deleteTrip(\'' + tripId + '\')" title="Delete trip">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
            '</button>';

        return card;
    },

    // ==================== DNA & SUMMARY ====================

    async loadDna() {
        try {
            const resp = await fetch('/api/trips/dna?days=30');
            const data = await resp.json();
            if (data.success && data.dna) {
                this.radarScoresCache = data.dna;
                const canvas = document.getElementById('radarChart');
                if (canvas) this.renderRadar(canvas, data.dna);
                if (data.dna.overall !== undefined) this.renderScoreCircle(data.dna.overall);
            }
        } catch (e) { console.warn('[Trips] DNA load failed:', e); }
    },

    async loadSummary(days) {
        const d = days || 7;
        try {
            const resp = await fetch('/api/trips/summary?days=' + d);
            const data = await resp.json();
            if (data.success && data.summary && data.summary.length > 0) {
                const s = data.summary[0];
                this.setEl('summaryTrips', s.tripCount || s.trip_count || 0);
                this.setEl('summaryDistance', (s.totalDistanceKm || s.total_distance_km || 0).toFixed(1));
                this.setEl('summaryTime', ((s.totalDurationSeconds || s.total_duration_seconds || 0) / 3600).toFixed(1));
                // Compute overall from 5 sub-scores (matching backend integer division)
                const sA = s.avgAnticipation || s.avg_anticipation || 0;
                const sS = s.avgSmoothness || s.avg_smoothness || 0;
                const sSD = s.avgSpeedDiscipline || s.avg_speed_discipline || 0;
                const sE = s.avgEfficiencyScore || s.avg_efficiency_score || 0;
                const sC = s.avgConsistency || s.avg_consistency || 0;
                this.setEl('summaryEfficiency', Math.floor((sA + sS + sSD + sE + sC) / 5));
            }
        } catch (e) { console.warn('[Trips] Summary load failed:', e); }
    },

    // ==================== CLIENT-SIDE SUMMARY ====================

    updatePeriodSummary() {
        const trips = this.trips;
        if (!trips || trips.length === 0) return;

        let totalDist = 0, totalDur = 0, totalEnergy = 0, totalCost = 0;
        let scoreSum = 0;
        let totalSocDelta = 0;
        trips.forEach(t => {
            totalDist += t.distanceKm || t.distance_km || 0;
            totalDur += t.durationSeconds || t.duration_seconds || 0;
            let energy = t.energyUsedKwh || t.energy_used_kwh || 0;
            // Fallback: estimate energy from SoC delta for trips without kWh data
            if (energy <= 0) {
                const ss = t.socStart || t.soc_start || 0;
                const se = t.socEnd || t.soc_end || 0;
                if (ss > se && ss > 0) {
                    const kws = t.kwhStart || t.kwh_start || 0;
                    const nom = (kws > 0 && ss > 5) ? kws / (ss / 100) : 82.56;
                    energy = ((ss - se) / 100) * nom;
                }
            }
            totalEnergy += energy;
            totalCost += t.tripCost || t.trip_cost || 0;
            scoreSum += this.getAvgScore(t);
            const socStart = t.socStart || t.soc_start || 0;
            const socEnd = t.socEnd || t.soc_end || 0;
            if (socStart > socEnd) totalSocDelta += (socStart - socEnd);
        });

        this.setEl('summaryTrips', trips.length);
        this.setEl('summaryDistance', totalDist.toFixed(1));
        this.setEl('summaryTime', (totalDur / 3600).toFixed(1));
        this.setEl('summaryEfficiency', trips.length > 0 ? Math.floor(scoreSum / trips.length) : '--');
        this.setEl('summaryEnergy', totalEnergy > 0 ? totalEnergy.toFixed(1) : '--');

        // Average consumption: kWh/100km (works for both BEV and PHEV)
        // Prefer direct kWh measurement, fall back to SOC-based estimate
        if (totalDist > 0.5) {
            if (totalEnergy > 0) {
                const kwhPer100km = (totalEnergy / totalDist) * 100;
                this.setEl('summaryConsumption', kwhPer100km.toFixed(1));
            } else if (totalSocDelta > 0) {
                const socPer100km = (totalSocDelta / totalDist) * 100;
                this.setEl('summaryConsumption', socPer100km.toFixed(1) + '%');
            } else {
                this.setEl('summaryConsumption', '--');
            }
        } else {
            this.setEl('summaryConsumption', '--');
        }

        if (totalCost > 0) {
            this.setEl('summaryCost', (this.currency || '$') + totalCost.toFixed(1));
        } else if (totalEnergy > 0 && this.electricityRate > 0) {
            const computed = totalEnergy * this.electricityRate;
            this.setEl('summaryCost', (this.currency || '$') + computed.toFixed(1));
        } else {
            this.setEl('summaryCost', '--');
        }
    },

    updateCostHero() {
        const noRate = document.getElementById('costNoRate');
        const dataDiv = document.getElementById('costHeroData');
        if (!noRate || !dataDiv) return;

        // Draw empty circle for no-rate state
        this.renderCircleGauge('costCircleCanvas', 0, 'rgba(245,158,11,0.2)');
        this.updateCurrencyIcons();

        if (this.electricityRate <= 0) {
            noRate.style.display = 'block';
            dataDiv.style.display = 'none';
            return;
        }

        const trips = this.trips;
        let totalEnergy = 0, totalDist = 0;
        if (trips && trips.length > 0) {
            trips.forEach(t => {
                let energy = t.energyUsedKwh || t.energy_used_kwh || 0;
                // Fallback: estimate from SoC delta for trips without kWh data
                if (energy <= 0) {
                    const ss = t.socStart || t.soc_start || 0;
                    const se = t.socEnd || t.soc_end || 0;
                    if (ss > se && ss > 0) {
                        const kws = t.kwhStart || t.kwh_start || 0;
                        const nom = (kws > 0 && ss > 5) ? kws / (ss / 100) : 82.56;
                        energy = ((ss - se) / 100) * nom;
                    }
                }
                totalEnergy += energy;
                totalDist += t.distanceKm || t.distance_km || 0;
            });
        }

        if (totalDist > 0 && totalEnergy > 0) {
            const kwhPerKm = totalEnergy / totalDist;
            const costPerKm = kwhPerKm * this.electricityRate;
            noRate.style.display = 'none';
            dataDiv.style.display = 'block';
            this.setEl('costPerKmValue', this.currency + costPerKm.toFixed(2));
            this.setEl('costPerKmUnit', '/km');
            // Formula capsule below circle (single source of truth — no duplicate text)
            const formulaCapsule = document.getElementById('costFormulaCapsule');
            if (formulaCapsule) {
                formulaCapsule.textContent = kwhPerKm.toFixed(3) + ' kWh/km × ' + this.currency + this.electricityRate + '/kWh';
                formulaCapsule.style.display = '';
            }
            const infoEl = document.getElementById('costPerKwhInfo');
            if (infoEl) infoEl.style.display = 'none';
            // Gauge: lower cost = better. Map 0-5 ₹/km to 100-0%
            const pct = Math.max(0, Math.min(100, (1 - costPerKm / 5) * 100));
            this.renderCircleGauge('costCircleCanvasActive', pct, '#F59E0B');
        } else {
            noRate.style.display = 'none';
            dataDiv.style.display = 'block';
            this.setEl('costPerKmValue', '--');
            this.setEl('costPerKwhInfo', 'Drive more trips to calculate');
            const infoEl = document.getElementById('costPerKwhInfo');
            if (infoEl) infoEl.style.display = '';
            const formulaCapsule = document.getElementById('costFormulaCapsule');
            if (formulaCapsule) formulaCapsule.style.display = 'none';
            this.renderCircleGauge('costCircleCanvasActive', 0, 'rgba(245,158,11,0.2)');
        }
    },

    /**
     * Generic circle gauge renderer — used by range and cost cards.
     */
    renderCircleGauge(canvasId, percent, color) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return;
        const dpr = window.devicePixelRatio || 1;
        const size = 140;
        canvas.width = size * dpr;
        canvas.height = size * dpr;
        canvas.style.width = size + 'px';
        canvas.style.height = size + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const cx = size / 2, cy = size / 2, radius = 58, lineWidth = 8;

        // Background ring
        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        ctx.strokeStyle = 'rgba(255,255,255,0.06)';
        ctx.lineWidth = lineWidth;
        ctx.stroke();

        if (percent > 0) {
            const startAngle = -Math.PI / 2;
            const endAngle = startAngle + (percent / 100) * Math.PI * 2;

            // Glow
            ctx.beginPath();
            ctx.arc(cx, cy, radius, startAngle, endAngle);
            ctx.strokeStyle = color.replace(')', ',0.3)').replace('rgb', 'rgba');
            ctx.lineWidth = lineWidth + 6;
            ctx.lineCap = 'round';
            ctx.stroke();

            // Main arc
            ctx.beginPath();
            ctx.arc(cx, cy, radius, startAngle, endAngle);
            ctx.strokeStyle = color;
            ctx.lineWidth = lineWidth;
            ctx.lineCap = 'round';
            ctx.stroke();
        }
    },

    async loadRange() {
        try {
            const resp = await fetch('/api/trips/range');
            const data = await resp.json();
            const content = document.getElementById('rangeHeroContent');
            if (!content) return;

            if (data.success && data.range && data.range !== null) {
                const r = data.range;
                this.rangeCache = r;
                const predicted = Math.round(r.predictedRangeKm || r.predicted_range_km || 0);
                const lower = Math.round(r.lowerBoundKm || r.lower_bound_km || 0);
                const upper = Math.round(r.upperBoundKm || r.upper_bound_km || 0);
                const builtIn = Math.round(r.builtInRangeKm || r.built_in_range_km || 0);

                content.innerHTML = '';

                // Update circle value
                this.setEl('rangeCircleValue', predicted);
                const rangePct = Math.min(100, (predicted / 500) * 100);
                this.renderCircleGauge('rangeCircleCanvas', rangePct, '#0EA5E9');

                // Delta capsule below circle — shows personalized vs built-in
                const capsule = document.getElementById('rangeDeltaCapsule');
                if (capsule && builtIn > 0) {
                    const delta = predicted - builtIn;
                    const deltaSign = delta >= 0 ? '+' : '';
                    capsule.innerHTML = predicted + ' vs ' + builtIn + ' km <span style="opacity:0.7;margin-left:2px;">(' + deltaSign + delta + ')</span>';
                    capsule.className = 'range-delta-capsule ' + (delta >= 0 ? 'better' : 'worse');
                    capsule.style.display = '';
                } else if (capsule) {
                    capsule.textContent = lower + ' – ' + upper + ' km range';
                    capsule.className = 'range-delta-capsule neutral';
                    capsule.style.display = '';
                }

                // Build hover tooltip matching score-hero-tooltip design
                const tooltip = document.getElementById('rangeHeroTooltip');
                if (tooltip) {
                    tooltip.style.display = '';
                    let tt = '<div class="range-tooltip-title">Range Details</div>';

                    tt += '<div class="range-tooltip-row"><span class="range-tooltip-label">Confidence</span><span class="range-tooltip-value">' + lower + ' – ' + upper + ' km</span></div>';

                    // Conditions pills
                    const bucketKey = r.bucketKey || r.bucket_key || '';
                    const samples = r.sampleCount || r.sample_count || 0;
                    if (bucketKey) {
                        const parts = bucketKey.split('_');
                        const speedLabels = { city: 'City', suburban: 'Suburban', highway: 'Highway' };
                        const tempLabels = { cold: 'Cold', mild: 'Mild', hot: 'Hot' };
                        const styleLabels = { low: 'Calm', mid: 'Balanced', high: 'Spirited' };
                        const speedColors = { city: 'rgba(99,102,241,0.15);color:#6366F1', suburban: 'rgba(0,212,170,0.15);color:var(--brand-primary)', highway: 'rgba(245,158,11,0.15);color:var(--warning)' };
                        const tempColors = { cold: 'rgba(14,165,233,0.15);color:#0EA5E9', mild: 'rgba(34,197,94,0.15);color:#22C55E', hot: 'rgba(239,68,68,0.15);color:var(--danger)' };
                        const styleColors = { low: 'rgba(34,197,94,0.15);color:#22C55E', mid: 'rgba(245,158,11,0.15);color:var(--warning)', high: 'rgba(239,68,68,0.15);color:var(--danger)' };

                        tt += '<div class="range-tooltip-conditions">';
                        tt += '<div class="range-tooltip-conditions-label">Current conditions</div>';
                        tt += '<div class="range-tooltip-pills">';
                        tt += '<span class="range-tooltip-pill" style="background:' + (speedColors[parts[0]] || speedColors.suburban) + ';">' + (speedLabels[parts[0]] || parts[0]) + '</span>';
                        tt += '<span class="range-tooltip-pill" style="background:' + (tempColors[parts[1]] || tempColors.mild) + ';">' + (tempLabels[parts[1]] || parts[1]) + '</span>';
                        tt += '<span class="range-tooltip-pill" style="background:' + (styleColors[parts[2]] || styleColors.mid) + ';">' + (styleLabels[parts[2]] || parts[2]) + '</span>';
                        tt += '</div>';
                        tt += '<div class="range-tooltip-samples">Based on ' + samples + ' trips in similar conditions</div>';
                        tt += '</div>';
                    }

                    tooltip.innerHTML = tt;
                }
            } else {
                content.innerHTML = '<div class="range-hero-no-data"><div>Drive more trips to unlock personalized range</div></div>';
                this.renderCircleGauge('rangeCircleCanvas', 0, 'rgba(14,165,233,0.2)');
                const capsule = document.getElementById('rangeDeltaCapsule');
                if (capsule) capsule.style.display = 'none';
            }
        } catch (e) {
            console.warn('[Trips] Range load failed:', e);
            this.renderCircleGauge('rangeCircleCanvas', 0, 'rgba(14,165,233,0.2)');
        }
    },

    // ==================== TRIP DETAIL ====================

    async showDetail(tripId) {
        this.currentTripId = tripId;
        document.getElementById('tripListView').classList.add('hidden');
        document.getElementById('tripDetail').classList.add('active');
        window.scrollTo(0, 0);

        try {
            const tripResp = await fetch('/api/trips/' + tripId);
            const tripData = await tripResp.json();
            if (!tripData.success || !tripData.trip) return;
            const trip = tripData.trip;

            const start = new Date(trip.startTime || trip.start_time);
            this.setEl('detailTitle', start.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' }));
            this.setEl('detailSubtitle', start.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) +
                ' – ' + new Date(trip.endTime || trip.end_time).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }));
            this.setEl('detailDistance', (trip.distanceKm || trip.distance_km || 0).toFixed(1));
            this.setEl('detailDuration', this.formatDuration(trip.durationSeconds || trip.duration_seconds || 0));
            this.setEl('detailSocDelta', ((trip.socStart || trip.soc_start || 0) - (trip.socEnd || trip.soc_end || 0)).toFixed(1) + '%');
            // Show energy kWh or efficiency
            const detailEnergy = trip.energyUsedKwh || trip.energy_used_kwh || 0;
            this.setEl('detailEfficiency', detailEnergy > 0 ? detailEnergy.toFixed(1) + ' kWh' : (trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0).toFixed(2));
            // Average consumption: kWh/100km or %/100km
            const tripDist = trip.distanceKm || trip.distance_km || 0;
            if (tripDist > 0.1 && detailEnergy > 0) {
                this.setEl('detailConsumption', ((detailEnergy / tripDist) * 100).toFixed(1));
            } else if (tripDist > 0.1) {
                const socDelta = (trip.socStart || trip.soc_start || 0) - (trip.socEnd || trip.soc_end || 0);
                if (socDelta > 0) {
                    this.setEl('detailConsumption', ((socDelta / tripDist) * 100).toFixed(1) + '%');
                } else {
                    this.setEl('detailConsumption', '--');
                }
            } else {
                this.setEl('detailConsumption', '--');
            }
            this.setEl('detailAvgSpeed', Math.round(trip.avgSpeedKmh || trip.avg_speed_kmh || 0));
            this.setEl('detailMaxSpeed', trip.maxSpeedKmh || trip.max_speed_kmh || 0);
            this.setEl('detailSocStart', (trip.socStart || trip.soc_start || 0).toFixed(1) + '%');
            this.setEl('detailTemp', (trip.extTempC || trip.ext_temp_c || '--') + (trip.extTempC || trip.ext_temp_c ? '°C' : ''));
            // Elevation data
            const elevGain = trip.elevationGainM || trip.elevation_gain_m || 0;
            const elevLoss = trip.elevationLossM || trip.elevation_loss_m || 0;
            this.setEl('detailElevGain', elevGain > 0 ? '+' + Math.round(elevGain) + 'm' : '--');
            this.setEl('detailElevLoss', elevLoss > 0 ? '-' + Math.round(elevLoss) + 'm' : '--');
            // Gradient profile pill
            const gradProfile = trip.gradientProfile || trip.gradient_profile || '';
            const gradEl = document.getElementById('detailGradientPill');
            if (gradEl && gradProfile) {
                const gradLabels = { FLAT: '🛣️ Flat', HILLY: '⛰️ Hilly', MOUNTAIN_CLIMB: '🏔️ Climb', MOUNTAIN_DESCENT: '⬇️ Descent' };
                const gradColors = { FLAT: 'rgba(34,197,94,0.15);color:#22C55E', HILLY: 'rgba(245,158,11,0.15);color:#F59E0B', MOUNTAIN_CLIMB: 'rgba(239,68,68,0.15);color:#EF4444', MOUNTAIN_DESCENT: 'rgba(14,165,233,0.15);color:#0EA5E9' };
                gradEl.innerHTML = gradLabels[gradProfile] || gradProfile;
                gradEl.style.cssText = 'display:inline-flex;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:600;background:' + (gradColors[gradProfile] || gradColors.FLAT);
            } else if (gradEl) {
                gradEl.style.display = 'none';
            }
            // Trip cost
            const detailCost = trip.tripCost || trip.trip_cost || 0;
            const detailCurrency = trip.currency || this.currency || '$';
            this.setEl('detailCost', detailCost > 0 ? detailCurrency + detailCost.toFixed(1) : '--');

            this.renderScoreBar('scoreAnticipation', 'scoreAnticipationVal', trip.anticipationScore || trip.anticipation_score || 0);
            this.renderScoreBar('scoreSmoothness', 'scoreSmoothnessVal', trip.smoothnessScore || trip.smoothness_score || 0);
            this.renderScoreBar('scoreSpeedDisc', 'scoreSpeedDiscVal', trip.speedDisciplineScore || trip.speed_discipline_score || 0);
            this.renderScoreBar('scoreEfficiency', 'scoreEfficiencyVal', trip.efficiencyScore || trip.efficiency_score || 0);
            this.renderScoreBar('scoreConsistency', 'scoreConsistencyVal', trip.consistencyScore || trip.consistency_score || 0);

            this.renderMicroMoments(trip.microMomentsJson || trip.micro_moments_json);
            this.loadRouteComparison(trip);

            // Fetch telemetry (may be unavailable for older trips)
            console.log('[Trips] Trip telemetry path:', trip.telemetryFilePath || trip.telemetry_file_path || 'NONE');
            if (trip.telemetryFilePath || trip.telemetry_file_path) {
                try {
                    console.log('[Trips] Fetching telemetry for trip ' + tripId);
                    const telResp = await fetch('/api/trips/' + tripId + '/telemetry');
                    console.log('[Trips] Telemetry response status:', telResp.status);
                    if (telResp.ok) {
                        const telData = await telResp.json();
                        console.log('[Trips] Telemetry data: success=' + telData.success + ' samples=' + (telData.telemetry ? telData.telemetry.length : 0));
                        if (telData.success && telData.telemetry && telData.telemetry.length > 0) {
                const samples = telData.telemetry;
                this.telemetryCache = samples;
                this.currentTripData = trip;

                // Setup timeline slider
                this.setupTimelineSlider(samples);

                const ribbonCanvas = document.getElementById('timelineChart');
                if (ribbonCanvas) this.renderTimeline(ribbonCanvas, samples);
                const histCanvas = document.getElementById('speedHistogram');
                if (histCanvas) this.renderSpeedHistogram(histCanvas, samples);
                const mapContainer = document.getElementById('tripMap');
                console.log('[Trips] Map container:', mapContainer ? (mapContainer.offsetWidth + 'x' + mapContainer.offsetHeight) : 'NOT FOUND');
                console.log('[Trips] Leaflet available:', typeof L !== 'undefined');
                if (mapContainer) {
                    // Delay map render to ensure container is visible and has dimensions.
                    // renderRouteMap has its own retry logic for Leaflet loading and
                    // container layout, so we just need a small initial delay.
                    setTimeout(() => {
                        console.log('[Trips] Calling renderRouteMap with ' + samples.length + ' samples');
                        this.renderRouteMap(mapContainer, samples);
                    }, 150);
                }
                        }
                    }
                } catch (e) {
                    console.error('[Trips] Telemetry/map error:', e.message || e);
                }
            }
        } catch (e) { console.warn('[Trips] Detail load failed:', e); }
    },

    hideDetail() {
        this.currentTripId = null;
        this.telemetryCache = null;
        this.currentTripData = null;
        this.sliderMarker = null;
        document.getElementById('tripDetail').classList.remove('active');
        document.getElementById('tripListView').classList.remove('hidden');
        document.getElementById('timelineSliderCard').style.display = 'none';
        if (this.leafletMap) { this.leafletMap.remove(); this.leafletMap = null; }
    },

    // ==================== TIMELINE SLIDER ====================

    setupTimelineSlider(samples) {
        const card = document.getElementById('timelineSliderCard');
        if (!card || samples.length < 2) return;
        card.style.display = 'block';

        const slider = document.getElementById('timelineSlider');
        slider.max = samples.length - 1;
        slider.value = 0;

        const tStart = samples[0].t;
        const tEnd = samples[samples.length - 1].t;
        const durMin = Math.round((tEnd - tStart) / 60000);
        this.setEl('sliderStartTime', '0:00');
        this.setEl('sliderEndTime', durMin + ' min');

        // Hover scrub — moving mouse over slider area scrubs the position
        const self = this;
        const wrap = slider.parentElement;
        if (wrap) {
            wrap.onmousemove = function(e) {
                const rect = wrap.getBoundingClientRect();
                const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                const idx = Math.round(pct * (samples.length - 1));
                slider.value = idx;
                self.updateSliderDisplay(idx);
            };
        }

        this.updateSliderDisplay(0);
    },

    onSliderInput(val) {
        const idx = parseInt(val);
        this.updateSliderDisplay(idx);
    },

    updateSliderDisplay(idx) {
        const samples = this.telemetryCache;
        if (!samples || idx >= samples.length) return;
        const s = samples[idx];
        const tStart = samples[0].t;
        const elapsed = (s.t - tStart) / 1000;
        const min = Math.floor(elapsed / 60);
        const sec = Math.floor(elapsed % 60);

        // 1. Sync Text HUD
        this.setEl('sliderSpeed', s.s || 0);
        this.setEl('sliderAccel', s.a || 0);
        this.setEl('sliderBrake', s.b || 0);
        this.setEl('sliderCurrentTime', min + ':' + (sec < 10 ? '0' : '') + sec);

        // SoC interpolated
        const tripData = this.currentTripData;
        if (tripData) {
            const socS = parseFloat(tripData.socStart || tripData.soc_start || 0);
            const socE = parseFloat(tripData.socEnd || tripData.soc_end || 0);
            const totalSamples = samples.length - 1;
            const socAtIdx = totalSamples > 0 ? socS + (socE - socS) * (idx / totalSamples) : socS;
            this.setEl('sliderSoc', socAtIdx.toFixed(1));
        }

        // 2. Sync Map Marker with heading rotation
        if (this.leafletMap && this.sliderMarker && s.la && s.lo) {
            this.sliderMarker.setLatLng([s.la, s.lo]);
            // Compute heading using a lookback window for stability
            var telSamples = this.telemetryCache || [];
            var heading = null;
            // Look back up to 10 samples to find a point with enough separation
            for (var hi = 1; hi <= Math.min(10, idx); hi++) {
                var prev = telSamples[idx - hi];
                if (prev && prev.la && prev.lo && (Math.abs(prev.la - s.la) > 0.00003 || Math.abs(prev.lo - s.lo) > 0.00003)) {
                    var dLon = (s.lo - prev.lo) * Math.PI / 180;
                    var lat1 = prev.la * Math.PI / 180;
                    var lat2 = s.la * Math.PI / 180;
                    var y = Math.sin(dLon) * Math.cos(lat2);
                    var x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
                    heading = Math.atan2(y, x) * 180 / Math.PI;
                    break;
                }
            }
            if (heading !== null) {
                var wrapper = this.sliderMarker.getElement();
                if (wrapper) {
                    var img = wrapper.querySelector('.car-icon-wrapper');
                    if (img) img.style.transform = 'rotate(' + heading + 'deg)';
                }
            }
        }

        // 3. Sync Timeline chart scrubber
        const tlCanvas = document.getElementById('timelineChart');
        if (tlCanvas && samples.length > 1) {
            this.renderTimeline(tlCanvas, samples, idx);
        }
    },

    // ==================== SCORE DETAIL TOGGLE ====================

    toggleScoreDetail(row) {
        row.classList.toggle('expanded');
    },

    // ==================== ROUTE COMPARISON ====================

    async loadRouteComparison(trip) {
        const card = document.getElementById('routeComparisonCard');
        const content = document.getElementById('routeComparisonContent');
        if (!card || !content) return;

        const startLat = trip.startLat || trip.start_lat || 0;
        if (startLat === 0) { card.style.display = 'none'; return; }

        try {
            const tripId = trip.id;
            const resp = await fetch('/api/trips/' + tripId + '/similar');
            const data = await resp.json();
            if (!data.success || data.count === 0) { card.style.display = 'none'; return; }

            card.style.display = 'block';
            const stats = data.stats;
            const similar = data.similar || [];
            const tripEnergy = trip.energyUsedKwh || trip.energy_used_kwh || 0;
            const tripCost = trip.tripCost || trip.trip_cost || 0;
            const avgCost = stats.avgCost || 0;
            const currency = trip.currency || '₹';
            const tripDur = trip.durationSeconds || trip.duration_seconds || 0;
            const avgDur = stats.avgDurationSeconds || 0;
            const tripDist = trip.distanceKm || trip.distance_km || 0;

            // Compute avg energy from similar trips
            var sumEnergy = 0, energyCount = 0;
            similar.forEach(function(t) {
                var e = t.energyUsedKwh || t.energy_used_kwh || 0;
                if (e > 0) { sumEnergy += e; energyCount++; }
            });
            var avgEnergy = energyCount > 0 ? sumEnergy / energyCount : 0;

            // Route rank
            var rank = 1;
            var currentEff = trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0;
            similar.forEach(function(t) {
                if ((t.efficiencySocPerKm || t.efficiency_soc_per_km || 999) < currentEff) rank++;
            });
            var totalOnRoute = data.count + 1;

            let html = '';

            // Summary banner
            var energyDelta = tripEnergy - avgEnergy;
            var energyPct = avgEnergy > 0 ? Math.abs(energyDelta / avgEnergy * 100).toFixed(0) : 0;
            var isBetter = energyDelta < 0;

            if (avgEnergy > 0 && tripEnergy > 0) {
                if (isBetter) {
                    html += '<div style="padding:12px 14px;background:rgba(34,197,94,0.08);border:1px solid rgba(34,197,94,0.2);border-radius:12px;margin-bottom:12px;">';
                    html += '<div style="font-size:14px;font-weight:600;color:#22C55E;">🎉 Used ' + energyPct + '% less energy than usual</div>';
                    html += '<div style="font-size:12px;color:var(--text-secondary);margin-top:4px;">#' + rank + ' of ' + totalOnRoute + ' trips on this route</div>';
                    html += '</div>';
                } else {
                    html += '<div style="padding:12px 14px;background:rgba(245,158,11,0.08);border:1px solid rgba(245,158,11,0.2);border-radius:12px;margin-bottom:12px;">';
                    html += '<div style="font-size:14px;font-weight:600;color:var(--warning);">📊 Used ' + energyPct + '% more energy than usual</div>';
                    html += '<div style="font-size:12px;color:var(--text-secondary);margin-top:4px;">#' + rank + ' of ' + totalOnRoute + ' trips on this route</div>';
                    html += '</div>';
                }
            }

            // Modern comparison cards — this trip vs route avg
            html += '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px;">';
            // This trip
            html += '<div style="padding:12px;background:var(--bg-elevated);border-radius:10px;border:1px solid var(--border-subtle);">';
            html += '<div style="font-size:10px;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;">This trip</div>';
            if (tripEnergy > 0) html += '<div style="font-size:13px;color:var(--text-primary);margin-bottom:4px;">⚡ ' + tripEnergy.toFixed(1) + ' kWh</div>';
            html += '<div style="font-size:13px;color:var(--text-primary);margin-bottom:4px;">⏱ ' + Math.round(tripDur/60) + ' min</div>';
            if (tripCost > 0) html += '<div style="font-size:13px;color:var(--text-primary);">💰 ' + currency + tripCost.toFixed(1) + '</div>';
            html += '</div>';
            // Route avg
            html += '<div style="padding:12px;background:var(--bg-elevated);border-radius:10px;border:1px solid var(--border-subtle);">';
            html += '<div style="font-size:10px;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;">Route average</div>';
            if (avgEnergy > 0) html += '<div style="font-size:13px;color:var(--text-secondary);margin-bottom:4px;">⚡ ' + avgEnergy.toFixed(1) + ' kWh</div>';
            html += '<div style="font-size:13px;color:var(--text-secondary);margin-bottom:4px;">⏱ ' + Math.round(avgDur/60) + ' min</div>';
            if (avgCost > 0) html += '<div style="font-size:13px;color:var(--text-secondary);">💰 ' + currency + avgCost.toFixed(1) + '</div>';
            html += '</div>';
            html += '</div>';

            // Sparkline
            if (similar.length >= 2) {
                html += '<div style="font-size:11px;color:var(--text-muted);margin-bottom:4px;">Energy trend (oldest → newest)</div>';
                html += '<canvas id="routeSparkline" class="sparkline-container" style="width:100%;height:40px;"></canvas>';
            }

            // Compare on Map button
            if (stats.bestTripId > 0) {
                html += '<button onclick="TRIPS.showRouteMapComparison(' + tripId + ',' + stats.bestTripId + ',' + (stats.worstTripId > 0 ? stats.worstTripId : -1) + ')" style="width:100%;padding:10px;margin:8px 0;background:var(--bg-elevated);border:1px solid var(--border-subtle);border-radius:8px;color:var(--brand-primary);font-size:13px;font-weight:600;cursor:pointer;">🗺️ Compare on Map</button>';
            }

            // Recent trips — clickable links
            html += '<div style="font-size:11px;color:var(--text-muted);margin:8px 0 6px;text-transform:uppercase;letter-spacing:0.5px;">Trips on this route (' + data.count + ')</div>';
            similar.slice(0, 5).forEach(function(t) {
                var date = new Date(t.startTime || t.start_time).toLocaleDateString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
                var energy = (t.energyUsedKwh || t.energy_used_kwh || 0);
                var cost = t.tripCost || t.trip_cost || 0;
                var isBest = t.id === stats.bestTripId;
                html += '<div class="route-comparison-item" style="cursor:pointer;" onclick="TRIPS.showDetail(' + t.id + ')">';
                html += '<span class="route-comparison-date">' + date + (isBest ? ' 🏆' : '') + '</span>';
                html += '<span class="route-comparison-eff">' + (energy > 0 ? energy.toFixed(1) + ' kWh' : '--') + (cost > 0 ? ' · ' + currency + cost.toFixed(0) : '') + '</span>';
                html += '<span style="color:var(--brand-primary);font-size:12px;">→</span>';
                html += '</div>';
            });

            content.innerHTML = html;

            // Draw sparkline (energy-based)
            if (similar.length >= 2) {
                var energyPoints = similar.slice().reverse().map(function(t) { return t.energyUsedKwh || t.energy_used_kwh || 0; });
                energyPoints.push(tripEnergy);
                setTimeout(function() { TRIPS.drawRouteSparkline(energyPoints); }, 50);
            }
        } catch (e) {
            console.warn('[Trips] Route comparison error:', e);
            card.style.display = 'none';
        }
    },

    drawRouteSparkline(points) {
        const canvas = document.getElementById('routeSparkline');
        if (!canvas || points.length < 2) return;
        const ctx = canvas.getContext('2d');
        const dpr = window.devicePixelRatio || 1;
        canvas.width = canvas.offsetWidth * dpr;
        canvas.height = 40 * dpr;
        ctx.scale(dpr, dpr);
        const w = canvas.offsetWidth, h = 40;

        const min = Math.min.apply(null, points) * 0.9;
        const max = Math.max.apply(null, points) * 1.1;
        const range = max - min || 1;

        ctx.beginPath();
        ctx.strokeStyle = 'rgba(99,102,241,0.6)';
        ctx.lineWidth = 2;
        points.forEach(function(v, i) {
            var x = (i / (points.length - 1)) * (w - 8) + 4;
            var y = h - 4 - ((v - min) / range) * (h - 8);
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        ctx.stroke();

        // Highlight current trip (last point)
        var lastVal = points[points.length - 1];
        var lastX = w - 4;
        var lastY = h - 4 - ((lastVal - min) / range) * (h - 8);
        ctx.beginPath();
        ctx.arc(lastX, lastY, 4, 0, Math.PI * 2);
        ctx.fillStyle = 'var(--brand-primary)';
        ctx.fill();
    },

    routeCompareMapInstance: null,

    async showRouteMapComparison(currentId, bestId, worstId) {
        const overlay = document.getElementById('routeMapOverlay');
        const mapDiv = document.getElementById('routeCompareMap');
        const legend = document.getElementById('routeMapLegend');
        if (!overlay || !mapDiv) return;

        // Destroy previous map instance
        if (this.routeCompareMapInstance) {
            this.routeCompareMapInstance.remove();
            this.routeCompareMapInstance = null;
        }

        overlay.style.display = 'block';
        mapDiv.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--text-muted);">Loading routes...</div>';

        try {
            // Fetch GPS traces in parallel
            const fetches = [fetch('/api/trips/' + currentId + '/gps')];
            if (bestId > 0) fetches.push(fetch('/api/trips/' + bestId + '/gps'));
            if (worstId > 0 && worstId !== bestId) fetches.push(fetch('/api/trips/' + worstId + '/gps'));

            const responses = await Promise.all(fetches);
            const data = await Promise.all(responses.map(function(r) { return r.json(); }));

            const currentGps = data[0].success ? data[0].gps : [];
            const bestGps = data[1] && data[1].success ? data[1].gps : [];
            const worstGps = data[2] && data[2].success ? data[2].gps : [];

            if (currentGps.length === 0) {
                mapDiv.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);">No GPS data available</div>';
                return;
            }

            // Create Leaflet map
            if (typeof L === 'undefined') {
                mapDiv.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);">Map library not available</div>';
                return;
            }
            mapDiv.innerHTML = '';
            const map = L.map(mapDiv, { zoomControl: false, attributionControl: false });
            this.routeCompareMapInstance = map;
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);

            const bounds = L.latLngBounds();

            // Draw worst first (bottom layer)
            if (worstGps.length > 0) {
                var worstLine = L.polyline(worstGps, { color: '#EF4444', weight: 5, opacity: 0.5, dashArray: '10,8' }).addTo(map);
                bounds.extend(worstLine.getBounds());
            }
            // Best
            if (bestGps.length > 0) {
                var bestLine = L.polyline(bestGps, { color: '#22C55E', weight: 5, opacity: 0.7 }).addTo(map);
                bounds.extend(bestLine.getBounds());
            }
            // Current on top
            var currentLine = L.polyline(currentGps, { color: '#6366F1', weight: 5, opacity: 1.0 }).addTo(map);
            bounds.extend(currentLine.getBounds());

            // Start marker (same as trip detail)
            var startPoint = currentGps[0];
            var startIcon = L.divIcon({
                className: '',
                html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#22C55E;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="16" height="16"><polygon points="5,3 19,12 5,21"/></svg></div>',
                iconSize: [32, 32],
                iconAnchor: [16, 16]
            });
            L.marker(startPoint, { icon: startIcon }).bindTooltip('Start', { permanent: false, direction: 'top' }).addTo(map);

            // End marker (same as trip detail)
            var endPoint = currentGps[currentGps.length - 1];
            var endIcon = L.divIcon({
                className: '',
                html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#EF4444;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="14" height="14"><rect x="6" y="6" width="12" height="12" rx="2"/></svg></div>',
                iconSize: [32, 32],
                iconAnchor: [16, 16]
            });
            L.marker(endPoint, { icon: endIcon }).bindTooltip('End', { permanent: false, direction: 'top' }).addTo(map);

            map.fitBounds(bounds, { padding: [20, 20] });

            // Legend
            legend.innerHTML = '<span style="display:flex;align-items:center;gap:4px;"><span style="width:20px;height:4px;background:#6366F1;border-radius:2px;"></span>Current</span>' +
                '<span style="display:flex;align-items:center;gap:4px;"><span style="width:20px;height:4px;background:#22C55E;border-radius:2px;"></span>Best</span>' +
                (worstGps.length > 0 ? '<span style="display:flex;align-items:center;gap:4px;"><span style="width:20px;height:4px;background:#EF4444;border-radius:2px;"></span>Worst</span>' : '');

        } catch (e) {
            console.warn('[Trips] Route map error:', e);
            mapDiv.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);">Failed to load routes</div>';
        }
    },

    // ==================== TRIP BREAKDOWN ====================

    renderScoreBar(fillId, valId, score) {
        const fill = document.getElementById(fillId);
        const val = document.getElementById(valId);
        if (fill) {
            fill.style.width = score + '%';
            fill.classList.remove('low', 'mid');
            if (score < 40) fill.classList.add('low');
            else if (score < 70) fill.classList.add('mid');
        }
        if (val) val.textContent = score;
    },

    renderMicroMoments(json) {
        const list = document.getElementById('microMomentsList');
        if (!list) return;
        list.innerHTML = '';

        let moments = null;
        if (typeof json === 'string' && json) {
            try { moments = JSON.parse(json); } catch (e) { /* ignore */ }
        } else if (typeof json === 'object') {
            moments = json;
        }

        if (!moments) {
            list.innerHTML = '<div style="color:var(--text-muted);font-size:13px;padding:12px 0;">No micro-moment data available</div>';
            return;
        }

        const items = [];
        const tooltips = {
            'Launch Events': 'Number of hard accelerations from standstill (0→30+ km/h). Fewer = more efficient starts.',
            'Coast-Brake Events': 'Times you went directly from coasting to braking. More events = less anticipation of stops.',
            'Avg Coast Gap': 'Average time between lifting the accelerator and pressing the brake. Longer gaps = better anticipation.',
            'Pedal Smoothness (σ)': 'Standard deviation of pedal input changes. Lower σ = smoother, more consistent pedal work.'
        };
        if (moments.launchProfiles) items.push({ icon: '🚀', label: 'Launch Events', value: moments.launchProfiles.length });
        if (moments.coastBrakeEvents) {
            items.push({ icon: '🛑', label: 'Coast-Brake Events', value: moments.coastBrakeEvents.length });
            if (moments.coastBrakeEvents.length > 0) {
                const avgGap = moments.coastBrakeEvents.reduce((s, e) => s + (e.coastGapMs || e.coast_gap_ms || 0), 0) / moments.coastBrakeEvents.length / 1000;
                items.push({ icon: '⏳', label: 'Avg Coast Gap', value: avgGap.toFixed(1) + 's' });
            }
        }
        if (moments.pedalSmoothnessWindows) {
            const avgSmooth = moments.pedalSmoothnessWindows.length > 0
                ? moments.pedalSmoothnessWindows.reduce((s, w) => s + (w.stdDev || w.std_dev || 0), 0) / moments.pedalSmoothnessWindows.length
                : 0;
            items.push({ icon: '📊', label: 'Pedal Smoothness (σ)', value: avgSmooth.toFixed(1) });
        }

        items.forEach(item => {
            const el = document.createElement('div');
            el.className = 'moment-item';
            el.style.position = 'relative';
            const tip = tooltips[item.label] || '';
            el.innerHTML = '<span class="moment-icon">' + item.icon + '</span>' +
                '<div style="flex:1;"><span>' + item.label + '</span>' +
                (tip ? '<div style="font-size:10px;color:var(--text-muted);margin-top:2px;line-height:1.3;">' + tip + '</div>' : '') +
                '</div>' +
                '<span class="moment-value">' + item.value + '</span>';
            list.appendChild(el);
        });
    },

    // ==================== SCORE CIRCLE ====================

    renderScoreCircle(score) {
        const canvas = document.getElementById('scoreCircleCanvas');
        if (!canvas) return;

        const dpr = window.devicePixelRatio || 1;
        const size = 140;
        canvas.width = size * dpr;
        canvas.height = size * dpr;
        canvas.style.width = size + 'px';
        canvas.style.height = size + 'px';

        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const cx = size / 2, cy = size / 2, radius = 58, lineWidth = 8;

        let color, glowColor;
        if (score >= 80) { color = '#22C55E'; glowColor = 'rgba(34,197,94,0.3)'; }
        else if (score >= 60) { color = '#00D4AA'; glowColor = 'rgba(0,212,170,0.3)'; }
        else if (score >= 40) { color = '#F59E0B'; glowColor = 'rgba(245,158,11,0.3)'; }
        else { color = '#EF4444'; glowColor = 'rgba(239,68,68,0.3)'; }

        // Background ring
        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        ctx.strokeStyle = 'rgba(255,255,255,0.06)';
        ctx.lineWidth = lineWidth;
        ctx.stroke();

        const startAngle = -Math.PI / 2;
        const endAngle = startAngle + (score / 100) * Math.PI * 2;

        // Glow
        ctx.beginPath();
        ctx.arc(cx, cy, radius, startAngle, endAngle);
        ctx.strokeStyle = glowColor;
        ctx.lineWidth = lineWidth + 8;
        ctx.lineCap = 'round';
        ctx.stroke();

        // Main arc
        ctx.beginPath();
        ctx.arc(cx, cy, radius, startAngle, endAngle);
        ctx.strokeStyle = color;
        ctx.lineWidth = lineWidth;
        ctx.lineCap = 'round';
        ctx.stroke();

        // Update text
        const numberEl = document.getElementById('scoreHeroNumber');
        const starEl = document.getElementById('scoreStar');
        const labelEl = document.getElementById('scoreHeroLabel');
        if (numberEl) numberEl.textContent = score;
        if (starEl) starEl.style.color = color;

        let label, cls;
        if (score >= 80) { label = 'Excellent Driver'; cls = 'excellent'; }
        else if (score >= 60) { label = 'Good Driver'; cls = 'good'; }
        else if (score >= 40) { label = 'Average Driver'; cls = 'average'; }
        else { label = 'Needs Improvement'; cls = 'poor'; }

        if (labelEl) {
            labelEl.textContent = label;
            labelEl.className = 'score-hero-label ' + cls;
        }

        // Dynamic card background based on score tier
        const card = document.getElementById('scoreHeroCard');
        if (card) {
            if (score >= 80) {
                card.style.background = 'linear-gradient(135deg, rgba(34,197,94,0.1) 0%, rgba(34,197,94,0.04) 100%)';
                card.style.borderColor = 'rgba(34,197,94,0.2)';
            } else if (score >= 60) {
                card.style.background = 'linear-gradient(135deg, rgba(0,212,170,0.08) 0%, rgba(14,165,233,0.06) 100%)';
                card.style.borderColor = 'rgba(0,212,170,0.15)';
            } else if (score >= 40) {
                card.style.background = 'linear-gradient(135deg, rgba(245,158,11,0.1) 0%, rgba(245,158,11,0.04) 100%)';
                card.style.borderColor = 'rgba(245,158,11,0.2)';
            } else {
                card.style.background = 'linear-gradient(135deg, rgba(239,68,68,0.1) 0%, rgba(239,68,68,0.04) 100%)';
                card.style.borderColor = 'rgba(239,68,68,0.2)';
            }
        }

        // Update tooltip content based on score and DNA breakdown
        const tooltipTitle = document.getElementById('scoreTooltipTitle');
        const tooltipDesc = document.getElementById('scoreTooltipDesc');
        const tooltipTip = document.getElementById('scoreTooltipTip');
        const tierPills = document.querySelectorAll('.score-tier-pill');

        if (tooltipTitle) tooltipTitle.textContent = label + ' — ' + score + '/100';

        // Highlight active tier
        tierPills.forEach(pill => {
            pill.classList.remove('active');
            if (score >= 80 && pill.classList.contains('t-excellent')) pill.classList.add('active');
            else if (score >= 60 && score < 80 && pill.classList.contains('t-good')) pill.classList.add('active');
            else if (score >= 40 && score < 60 && pill.classList.contains('t-average')) pill.classList.add('active');
            else if (score < 40 && pill.classList.contains('t-poor')) pill.classList.add('active');
        });

        // Dynamic description based on score tier
        if (tooltipDesc) {
            if (score >= 80) tooltipDesc.textContent = 'Outstanding driving habits. You maximize regen, maintain smooth pedal inputs, and keep consistent speeds.';
            else if (score >= 60) tooltipDesc.textContent = 'Solid driving with room to grow. Occasional hard braking or speed fluctuations bring the score down.';
            else if (score >= 40) tooltipDesc.textContent = 'Moderate efficiency. Frequent speed changes or inconsistent pedal use are costing you range.';
            else tooltipDesc.textContent = 'Aggressive patterns detected. Heavy acceleration and late braking are significantly reducing your range.';
        }

        // Dynamic tip based on actual weakest DNA score
        if (tooltipTip && this.radarScoresCache) {
            const dna = this.radarScoresCache;
            const scores = [
                { key: 'anticipation', val: dna.anticipation || dna.anticipation_score || 0, tip: 'Lift off the accelerator 3–5 seconds earlier before stops. Coast into red lights.' },
                { key: 'smoothness', val: dna.smoothness || dna.smoothness_score || 0, tip: 'Apply pedals gradually — imagine balancing a glass of water on the dashboard.' },
                { key: 'speedDiscipline', val: dna.speedDiscipline || dna.speed_discipline || 0, tip: 'Use cruise control and stay within 5 km/h of speed limits on highways.' },
                { key: 'efficiency', val: dna.efficiency || dna.efficiency_score || 0, tip: 'Pre-condition the cabin while plugged in. Avoid full-throttle launches.' },
                { key: 'consistency', val: dna.consistency || dna.consistency_score || 0, tip: 'Pick one driving style and maintain it. Erratic switching wastes energy.' }
            ];
            const weakest = scores.reduce((min, s) => s.val < min.val ? s : min, scores[0]);
            const info = this.criteriaInfo[weakest.key];
            const weakLabel = info ? info.label : weakest.key;
            tooltipTip.textContent = '💡 Weakest area: ' + weakLabel + ' (' + weakest.val + '/100). ' + weakest.tip;
        } else if (tooltipTip) {
            tooltipTip.textContent = '💡 Drive more trips to get personalized improvement suggestions.';
        }
    },

    // ==================== RADAR CHART with hover ====================

    renderRadar(canvas, scores) {
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        // Old WebView (Chrome <88) doesn't support CSS aspect-ratio, so the
        // container may have zero height. Fall back to width for a square canvas.
        const w = rect.width || 300;
        const h = rect.height > 0 ? rect.height : w;
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        // Also set explicit CSS size so the canvas is visible
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const cx = w / 2, cy = h / 2;
        const radius = Math.min(cx, cy) * 0.55;

        const axes = [
            { label: 'Anticipation', key: 'anticipation' },
            { label: 'Smoothness', key: 'smoothness' },
            { label: 'Speed Disc.', key: 'speedDiscipline' },
            { label: 'Efficiency', key: 'efficiency' },
            { label: 'Consistency', key: 'consistency' }
        ];
        const n = axes.length;
        const angleStep = (Math.PI * 2) / n;
        const startAngle = -Math.PI / 2;

        ctx.clearRect(0, 0, w, h);

        // Grid rings
        for (let ring = 1; ring <= 5; ring++) {
            const r = (ring / 5) * radius;
            ctx.beginPath();
            for (let i = 0; i <= n; i++) {
                const angle = startAngle + i * angleStep;
                const x = cx + Math.cos(angle) * r;
                const y = cy + Math.sin(angle) * r;
                if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            }
            ctx.closePath();
            ctx.strokeStyle = this.colors.grid;
            ctx.lineWidth = 1;
            ctx.stroke();
        }

        // Axis lines
        for (let i = 0; i < n; i++) {
            const angle = startAngle + i * angleStep;
            ctx.beginPath();
            ctx.moveTo(cx, cy);
            ctx.lineTo(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius);
            ctx.strokeStyle = this.colors.grid;
            ctx.lineWidth = 1;
            ctx.stroke();
        }

        // Score polygon
        const values = axes.map(a => (scores[a.key] || scores[a.key.replace(/([A-Z])/g, '_$1').toLowerCase()] || 0) / 100);
        ctx.beginPath();
        values.forEach((v, i) => {
            const angle = startAngle + i * angleStep;
            const x = cx + Math.cos(angle) * v * radius;
            const y = cy + Math.sin(angle) * v * radius;
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        ctx.closePath();
        ctx.fillStyle = this.colors.brandRgba;
        ctx.fill();
        ctx.strokeStyle = this.colors.brand;
        ctx.lineWidth = 2;
        ctx.stroke();

        // Dots
        values.forEach((v, i) => {
            const angle = startAngle + i * angleStep;
            const x = cx + Math.cos(angle) * v * radius;
            const y = cy + Math.sin(angle) * v * radius;
            ctx.beginPath();
            ctx.arc(x, y, 5, 0, Math.PI * 2);
            ctx.fillStyle = this.colors.brand;
            ctx.fill();
            ctx.strokeStyle = '#0F0F12';
            ctx.lineWidth = 2;
            ctx.stroke();
        });

        // Labels
        ctx.font = '13px Inter, sans-serif';
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        axes.forEach((a, i) => {
            const angle = startAngle + i * angleStep;
            const labelR = radius + 30;
            const x = cx + Math.cos(angle) * labelR;
            const y = cy + Math.sin(angle) * labelR;

            ctx.textAlign = Math.abs(Math.cos(angle)) > 0.3 ? (Math.cos(angle) > 0 ? 'left' : 'right') : 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(a.label, x, y);
        });

        // Store axis positions for hover
        this._radarAxes = axes.map((a, i) => {
            const angle = startAngle + i * angleStep;
            const v = values[i];
            return {
                key: a.key,
                score: Math.round(v * 100),
                dotX: cx + Math.cos(angle) * v * radius,
                dotY: cy + Math.sin(angle) * v * radius,
                labelX: cx + Math.cos(angle) * radius,
                labelY: cy + Math.sin(angle) * radius
            };
        });

        // Setup hover
        this.setupRadarHover(canvas);
    },

    setupRadarHover(canvas) {
        const self = this;
        const tooltip = document.getElementById('radarTooltip');
        const wrap = canvas.parentElement;
        if (!tooltip || !wrap) return;

        canvas.onmousemove = function(e) {
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            const my = e.clientY - rect.top;

            if (!self._radarAxes) return;

            let closest = null;
            let minDist = 40; // pixel threshold
            self._radarAxes.forEach(ax => {
                const dx = mx - ax.dotX;
                const dy = my - ax.dotY;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < minDist) { minDist = dist; closest = ax; }
            });

            if (closest) {
                const info = self.criteriaInfo[closest.key];
                if (!info) return;
                document.getElementById('radarTooltipTitle').textContent = info.icon + ' ' + info.label;
                document.getElementById('radarTooltipScore').textContent = closest.score + '/100';
                document.getElementById('radarTooltipDesc').textContent = info.desc;
                document.getElementById('radarTooltipTip').textContent = '💡 ' + info.tip;

                // Position tooltip — always below and centered on dot
                const wrapRect = wrap.getBoundingClientRect();
                let tx = closest.dotX - 100; // center the 200px tooltip
                let ty = closest.dotY + 15;  // below the dot
                // Keep within bounds
                if (tx < 5) tx = 5;
                if (tx + 210 > wrapRect.width) tx = wrapRect.width - 215;
                if (ty + 100 > wrapRect.height) ty = closest.dotY - 115; // flip above

                tooltip.style.left = tx + 'px';
                tooltip.style.top = ty + 'px';
                tooltip.classList.add('visible');
            } else {
                tooltip.classList.remove('visible');
            }
        };

        canvas.onmouseleave = function() {
            tooltip.classList.remove('visible');
        };
    },

    // ==================== TIMELINE CHART ====================

    renderTimeline(canvas, telemetry, highlightIdx) {
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        // Fallback for zero-height containers on old WebView without aspect-ratio
        const w = rect.width || 300;
        const h = rect.height > 0 ? rect.height : 160;
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const pad = { top: 10, right: 40, bottom: 25, left: 50 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;

        ctx.clearRect(0, 0, w, h);
        if (!telemetry || telemetry.length < 2) return;

        const tStart = telemetry[0].t;
        const tEnd = telemetry[telemetry.length - 1].t;
        const tRange = tEnd - tStart || 1;
        const maxSpeed = Math.max(10, ...telemetry.map(s => s.s || 0));

        // Grid lines
        ctx.strokeStyle = this.colors.grid;
        ctx.lineWidth = 1;
        for (let i = 0; i <= 4; i++) {
            const y = pad.top + (ch * i / 4);
            ctx.beginPath();
            ctx.moveTo(pad.left, y);
            ctx.lineTo(w - pad.right, y);
            ctx.stroke();
            ctx.fillStyle = this.colors.text;
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText(Math.round(maxSpeed * (1 - i / 4)), pad.left - 6, y + 3);
        }

        // Brake area (red, from bottom)
        ctx.beginPath();
        ctx.moveTo(pad.left, pad.top + ch);
        telemetry.forEach((s, i) => {
            const x = pad.left + ((s.t - tStart) / tRange) * cw;
            const y = pad.top + ch - ((s.b || 0) / 100) * ch;
            ctx.lineTo(x, y);
        });
        ctx.lineTo(pad.left + cw, pad.top + ch);
        ctx.closePath();
        ctx.fillStyle = 'rgba(239, 68, 68, 0.2)';
        ctx.fill();

        // Accel pedal area (blue, from bottom)
        ctx.beginPath();
        ctx.moveTo(pad.left, pad.top + ch);
        telemetry.forEach((s, i) => {
            const x = pad.left + ((s.t - tStart) / tRange) * cw;
            const y = pad.top + ch - ((s.a || 0) / 100) * ch;
            ctx.lineTo(x, y);
        });
        ctx.lineTo(pad.left + cw, pad.top + ch);
        ctx.closePath();
        ctx.fillStyle = 'rgba(14, 165, 233, 0.15)';
        ctx.fill();

        // Speed line
        ctx.beginPath();
        telemetry.forEach((s, i) => {
            const x = pad.left + ((s.t - tStart) / tRange) * cw;
            const y = pad.top + ch - ((s.s || 0) / maxSpeed) * ch;
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        ctx.strokeStyle = this.colors.brand;
        ctx.lineWidth = 1.5;
        ctx.stroke();

        // X-axis labels
        ctx.fillStyle = this.colors.text;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'center';
        const durMin = tRange / 60000;
        ctx.fillText('0 min', pad.left, h - 5);
        ctx.fillText(Math.round(durMin / 2) + ' min', pad.left + cw / 2, h - 5);
        ctx.fillText(Math.round(durMin) + ' min', w - pad.right, h - 5);

        // SoC% interpolated line (right Y-axis, zoomed to actual range)
        const tripData = this.currentTripData;
        const socStart = tripData ? parseFloat(tripData.socStart || tripData.soc_start || 0) : 0;
        const socEnd = tripData ? parseFloat(tripData.socEnd || tripData.soc_end || 0) : 0;
        const hasSoc = socStart > 0 || socEnd > 0;

        if (hasSoc) {
            // Use a zoomed Y range: pad 5% above and below the actual SoC range
            const socMin = Math.max(0, Math.min(socStart, socEnd) - 5);
            const socMax = Math.min(100, Math.max(socStart, socEnd) + 5);
            const socRange = socMax - socMin || 1;

            // Right Y-axis labels
            ctx.fillStyle = 'rgba(245,158,11,0.7)';
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText(socStart.toFixed(1) + '%', w - 2, pad.top + ch - ((socStart - socMin) / socRange) * ch + 3);
            ctx.fillText(socEnd.toFixed(1) + '%', w - 2, pad.top + ch - ((socEnd - socMin) / socRange) * ch + 3);

            // SoC line
            ctx.beginPath();
            telemetry.forEach((s, i) => {
                const x = pad.left + ((s.t - tStart) / tRange) * cw;
                const progress = (s.t - tStart) / tRange;
                const soc = socStart + (socEnd - socStart) * progress;
                const y = pad.top + ch - ((soc - socMin) / socRange) * ch;
                if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            });
            ctx.strokeStyle = 'rgba(245,158,11,0.6)';
            ctx.lineWidth = 1.5;
            ctx.setLineDash([6, 4]);
            ctx.stroke();
            ctx.setLineDash([]);
        }

        // Compute pedal stats on first render
        if (highlightIdx === undefined) {
            let accelCount = 0, brakeCount = 0, coastCount = 0;
            telemetry.forEach(s => {
                if ((s.b || 0) > 0) brakeCount++;
                else if ((s.a || 0) > 0) accelCount++;
                else coastCount++;
            });
            const total = accelCount + brakeCount + coastCount;
            if (total > 0) {
                this.setEl('tlAccelPct', Math.round((accelCount / total) * 100) + '%');
                this.setEl('tlCoastPct', Math.round((coastCount / total) * 100) + '%');
                this.setEl('tlBrakePct', Math.round((brakeCount / total) * 100) + '%');
            }
        }

        // Highlight scrubber
        if (highlightIdx !== undefined && highlightIdx < telemetry.length) {
            const s = telemetry[highlightIdx];
            const x = pad.left + ((s.t - tStart) / tRange) * cw;

            ctx.beginPath();
            ctx.moveTo(x, pad.top);
            ctx.lineTo(x, pad.top + ch);
            ctx.strokeStyle = 'rgba(0,212,170,0.6)';
            ctx.lineWidth = 1.5;
            ctx.setLineDash([4, 4]);
            ctx.stroke();
            ctx.setLineDash([]);

            const sy = pad.top + ch - ((s.s || 0) / maxSpeed) * ch;
            ctx.beginPath();
            ctx.arc(x, sy, 5, 0, Math.PI * 2);
            ctx.fillStyle = this.colors.brand;
            ctx.fill();
            ctx.strokeStyle = '#0F0F12';
            ctx.lineWidth = 2;
            ctx.stroke();

            // Tooltip with SoC
            const tw = 140;
            const tx = x + 12 + tw > pad.left + cw ? x - tw - 12 : x + 12;
            const ty = Math.max(pad.top, sy - 70);
            ctx.fillStyle = 'rgba(15,15,20,0.92)';
            ctx.beginPath();
            ctx.roundRect(tx, ty, tw, 70, 6);
            ctx.fill();
            ctx.strokeStyle = 'rgba(0,212,170,0.3)';
            ctx.lineWidth = 1;
            ctx.stroke();

            ctx.fillStyle = '#fff';
            ctx.font = '11px Inter, sans-serif';
            ctx.textAlign = 'left';
            ctx.fillText('Speed: ' + (s.s || 0) + ' km/h', tx + 8, ty + 15);
            ctx.fillText('Accel: ' + (s.a || 0) + '%', tx + 8, ty + 30);
            ctx.fillText('Brake: ' + (s.b || 0) + '%', tx + 8, ty + 45);
            // SoC interpolated
            if (hasSoc) {
                const progress = (s.t - tStart) / tRange;
                const socAtPoint = socStart + (socEnd - socStart) * progress;
                ctx.fillStyle = 'rgba(245,158,11,0.8)';
                ctx.fillText('SoC: ' + socAtPoint.toFixed(1) + '%', tx + 8, ty + 60);
            }
        } else {
            this.setupChartHover(canvas, telemetry, tStart, tRange, cw, pad);
        }
    },

    setupChartHover(canvas, telemetry, tStart, tRange, cw, pad) {
        const self = this;

        // Desktop: mousemove
        canvas.style.pointerEvents = 'auto';
        canvas.onmousemove = function(e) {
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            if (mx < pad.left || mx > pad.left + cw) return;
            const relX = mx - pad.left;
            const targetT = tStart + (relX / cw) * tRange;
            let closest = 0, minDiff = Infinity;
            for (let i = 0; i < telemetry.length; i++) {
                const diff = Math.abs(telemetry[i].t - targetT);
                if (diff < minDiff) { minDiff = diff; closest = i; }
            }
            self.renderTimeline(canvas, telemetry, closest);
            const slider = document.getElementById('timelineSlider');
            if (slider) { slider.value = closest; self.updateSliderDisplay(closest); }
        };
        canvas.onmouseleave = function() {
            self.renderTimeline(canvas, telemetry);
        };

        // Mobile: touch scrub (horizontal drag on chart moves scrubber)
        canvas.ontouchstart = function(e) {
            if (e.touches.length === 1) {
                canvas._touchStartX = e.touches[0].clientX;
                canvas._touchStartY = e.touches[0].clientY;
                canvas._isScrubbing = false;
            }
        };
        canvas.ontouchmove = function(e) {
            if (e.touches.length !== 1) return;
            const dx = Math.abs(e.touches[0].clientX - canvas._touchStartX);
            const dy = Math.abs(e.touches[0].clientY - canvas._touchStartY);
            // If horizontal movement > vertical, it's a scrub — prevent scroll
            if (dx > dy && dx > 10) {
                canvas._isScrubbing = true;
                e.preventDefault();
                const rect = canvas.getBoundingClientRect();
                const mx = e.touches[0].clientX - rect.left;
                if (mx < pad.left || mx > pad.left + cw) return;
                const relX = mx - pad.left;
                const targetT = tStart + (relX / cw) * tRange;
                let closest = 0, minDiff = Infinity;
                for (let i = 0; i < telemetry.length; i++) {
                    const diff = Math.abs(telemetry[i].t - targetT);
                    if (diff < minDiff) { minDiff = diff; closest = i; }
                }
                self.renderTimeline(canvas, telemetry, closest);
                const slider = document.getElementById('timelineSlider');
                if (slider) { slider.value = closest; self.updateSliderDisplay(closest); }
            }
        };
        canvas.ontouchend = function() {
            if (canvas._isScrubbing) {
                self.renderTimeline(canvas, telemetry);
            }
            canvas._isScrubbing = false;
        };
    },

    // ==================== SPEED HISTOGRAM ====================

    renderSpeedHistogram(canvas, telemetry) {
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        const w = rect.width || 300;
        const h = rect.height > 0 ? rect.height : 160;
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const pad = { top: 10, right: 10, bottom: 25, left: 80 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;

        ctx.clearRect(0, 0, w, h);
        if (!telemetry || telemetry.length === 0) return;

        const bucketSize = 10;
        const buckets = {};
        const labels = [];
        for (let i = 0; i <= 130; i += bucketSize) {
            const label = i + '-' + (i + bucketSize);
            labels.push(label);
            buckets[label] = 0;
        }
        labels.push('140+');
        buckets['140+'] = 0;

        let totalSamples = 0;
        telemetry.forEach(s => {
            const speed = s.s || 0;
            totalSamples++;
            if (speed >= 140) { buckets['140+']++; return; }
            const idx = Math.floor(speed / bucketSize) * bucketSize;
            const label = idx + '-' + (idx + bucketSize);
            if (buckets[label] !== undefined) buckets[label]++;
        });

        const filteredLabels = labels.filter(l => buckets[l] > 0);
        if (filteredLabels.length === 0) return;

        const maxCount = Math.max(...filteredLabels.map(l => buckets[l]));
        const barH = Math.min(22, ch / filteredLabels.length - 4);

        filteredLabels.forEach((label, i) => {
            const count = buckets[label];
            const barW = (count / maxCount) * cw;
            const y = pad.top + i * (barH + 4);
            const pct = totalSamples > 0 ? Math.round(count / totalSamples * 100) : 0;

            const speedVal = parseInt(label);
            let color = this.colors.speedGreen;
            if (speedVal < 40) color = this.colors.speedYellow;
            else if (speedVal > 80) color = this.colors.speedRed;

            // Bar with rounded ends
            ctx.fillStyle = color;
            ctx.globalAlpha = 0.75;
            ctx.beginPath();
            ctx.roundRect(pad.left, y, Math.max(barW, 4), barH, 3);
            ctx.fill();
            ctx.globalAlpha = 1;

            // Label
            ctx.fillStyle = this.colors.text;
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText(label + ' km/h', pad.left - 6, y + barH / 2 + 3);

            // Percentage
            ctx.fillStyle = 'rgba(255,255,255,0.7)';
            ctx.textAlign = 'left';
            ctx.fillText(pct + '%', pad.left + barW + 6, y + barH / 2 + 3);
        });

        // Summary stats — use trip-level values for consistency with card
        const tripData = this.currentTripData;
        if (tripData || telemetry.length > 0) {
            const avg = tripData ? Math.round(tripData.avgSpeedKmh || tripData.avg_speed_kmh || 0) : 0;
            const max = tripData ? Math.round(tripData.maxSpeedKmh || tripData.max_speed_kmh || 0) : 0;
            const allSpeeds = telemetry.map(s => s.s || 0);
            const movingSamples = allSpeeds.filter(s => s > 0);
            const lowPct = movingSamples.length > 0 ? Math.round(movingSamples.filter(s => s < 40).length / movingSamples.length * 100) : 0;
            const highPct = movingSamples.length > 0 ? Math.round(movingSamples.filter(s => s > 80).length / movingSamples.length * 100) : 0;

            const summaryEl = document.getElementById('speedHistSummary');
            if (summaryEl) {
                summaryEl.innerHTML =
                    '<span class="speed-hist-stat">Avg: <span class="shval">' + avg + ' km/h</span></span>' +
                    '<span class="speed-hist-stat">Max: <span class="shval">' + max + ' km/h</span></span>' +
                    '<span class="speed-hist-stat">Low speed: <span class="shval">' + lowPct + '%</span></span>' +
                    '<span class="speed-hist-stat">High speed: <span class="shval">' + highPct + '%</span></span>';
            }
        }
    },

    // ==================== ROUTE MAP ====================

    renderRouteMap(container, telemetry) {
        console.log('[Trips] renderRouteMap called, telemetry=' + (telemetry ? telemetry.length : 'null'));
        if (this.leafletMap) { this.leafletMap.remove(); this.leafletMap = null; }
        if (!telemetry || telemetry.length < 2) {
            console.warn('[Trips] renderRouteMap: not enough telemetry');
            return;
        }

        // Guard: Leaflet may not be loaded (CDN fetch can fail on old WebView)
        if (typeof L === 'undefined') {
            console.warn('[Trips] Leaflet not loaded, retrying in 1s... (attempt ' + (this._mapRetries || 0) + ')');
            if (!this._mapRetries) this._mapRetries = 0;
            if (this._mapRetries < 5) {
                this._mapRetries++;
                setTimeout(() => this.renderRouteMap(container, telemetry), 1000);
            } else {
                console.error('[Trips] Leaflet never loaded after 5 retries');
            }
            return;
        }
        this._mapRetries = 0;

        // Guard: container must have real dimensions (old WebView is slow to layout
        // after display:none → display:block transition)
        var rect = container.getBoundingClientRect();
        console.log('[Trips] Map container rect:', rect.width + 'x' + rect.height);
        if (rect.width < 10 || rect.height < 10) {
            console.warn('[Trips] Map container has no dimensions, retrying... (attempt ' + (this._layoutRetries || 0) + ')');
            if (!this._layoutRetries) this._layoutRetries = 0;
            if (this._layoutRetries < 10) {
                this._layoutRetries++;
                setTimeout(() => this.renderRouteMap(container, telemetry), 300);
            } else {
                console.error('[Trips] Map container never got dimensions after 10 retries');
            }
            return;
        }
        this._layoutRetries = 0;

        const points = telemetry.filter(s => s.la && s.lo && s.la !== 0 && s.lo !== 0);
        console.log('[Trips] GPS points with coordinates:', points.length);
        if (points.length < 2) {
            console.warn('[Trips] renderRouteMap: not enough GPS points with coordinates');
            return;
        }

        // Compute bounds FIRST
        let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
        for (let i = 0; i < points.length; i++) {
            if (points[i].la < minLat) minLat = points[i].la;
            if (points[i].la > maxLat) maxLat = points[i].la;
            if (points[i].lo < minLon) minLon = points[i].lo;
            if (points[i].lo > maxLon) maxLon = points[i].lo;
        }

        // Create map and SET VIEW before adding any layers
        this.leafletMap = L.map(container, {
            zoomControl: true,
            attributionControl: false,
            scrollWheelZoom: false,
            dragging: true,
            tap: true,
            touchZoom: true,
            bounceAtZoomLimits: false
        });

        if (isFinite(minLat) && isFinite(maxLat) && isFinite(minLon) && isFinite(maxLon) &&
            maxLat > minLat && maxLon > minLon) {
            this.leafletMap.fitBounds([[minLat, minLon], [maxLat, maxLon]], { padding: [30, 30] });
        } else {
            this.leafletMap.setView([points[0].la, points[0].lo], 14);
        }

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19
        }).addTo(this.leafletMap);

        console.log('[Trips] Map created successfully, tiles added, bounds set');

        // Now add route polyline as a SINGLE polyline with color array
        // Build coordinate array for a single polyline (more reliable than many small segments)
        const coords = points.map(p => [p.la, p.lo]);
        L.polyline(coords, {
            color: this.colors.brand,
            weight: 4,
            opacity: 0.85
        }).addTo(this.leafletMap);

        // Start marker
        var startIcon = L.divIcon({
            className: '',
            html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#22C55E;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="16" height="16"><polygon points="5,3 19,12 5,21"/></svg></div>',
            iconSize: [32, 32],
            iconAnchor: [16, 16]
        });
        L.marker([points[0].la, points[0].lo], { icon: startIcon }).addTo(this.leafletMap).bindPopup('<b>Start</b>');

        // End marker
        var endIcon = L.divIcon({
            className: '',
            html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#EF4444;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="14" height="14"><rect x="6" y="6" width="12" height="12" rx="2"/></svg></div>',
            iconSize: [32, 32],
            iconAnchor: [16, 16]
        });
        L.marker([points[points.length - 1].la, points[points.length - 1].lo], { icon: endIcon }).addTo(this.leafletMap).bindPopup('<b>End</b>');

        // Slider marker — car icon (same as live view map)
        const carIcon = L.divIcon({
            className: 'car-map-marker',
            html: '<div class="car-icon-wrapper"><img src="../shared/car-icon-map.webp" class="car-icon-img" alt="Car"></div>',
            iconSize: [24, 50],
            iconAnchor: [12, 25]
        });
        this.sliderMarker = L.marker([points[0].la, points[0].lo], { icon: carIcon }).addTo(this.leafletMap);

        // Set initial heading from first GPS points with meaningful distance
        if (points.length >= 2) {
            // Find the first pair of points with enough separation for a reliable heading
            var initHeading = null;
            for (var hi = 1; hi < Math.min(points.length, 20); hi++) {
                var dLat = points[hi].la - points[0].la;
                var dLonRaw = points[hi].lo - points[0].lo;
                // Rough distance check — need at least ~10m separation
                if (Math.abs(dLat) > 0.00005 || Math.abs(dLonRaw) > 0.00005) {
                    var dLon = dLonRaw * Math.PI / 180;
                    var lat1 = points[0].la * Math.PI / 180;
                    var lat2 = points[hi].la * Math.PI / 180;
                    var y = Math.sin(dLon) * Math.cos(lat2);
                    var x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
                    initHeading = Math.atan2(y, x) * 180 / Math.PI;
                    break;
                }
            }
            // Fallback to first two points if no good pair found
            if (initHeading === null && points[1]) {
                var dLon = (points[1].lo - points[0].lo) * Math.PI / 180;
                var lat1 = points[0].la * Math.PI / 180;
                var lat2 = points[1].la * Math.PI / 180;
                var y = Math.sin(dLon) * Math.cos(lat2);
                var x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
                initHeading = Math.atan2(y, x) * 180 / Math.PI;
            }
            if (initHeading !== null) {
                setTimeout(function() {
                    var el = document.querySelector('.car-icon-wrapper');
                    if (el) el.style.transform = 'rotate(' + initHeading + 'deg)';
                }, 100);
            }
        }

        // Force Leaflet to recalculate container size (old WebView may report
        // stale dimensions right after display:none → block transition)
        var mapRef = this.leafletMap;
        setTimeout(function() { if (mapRef) mapRef.invalidateSize(); }, 200);
        setTimeout(function() { if (mapRef) mapRef.invalidateSize(); }, 800);

        // Click/tap on map to jump to nearest point
        const self = this;
        this.leafletMap.on('click', function(e) {
            const clickLat = e.latlng.lat;
            const clickLon = e.latlng.lng;
            let closestIdx = 0, minDist = Infinity;
            const allTel = self.telemetryCache || [];
            for (let i = 0; i < allTel.length; i++) {
                if (!allTel[i].la || !allTel[i].lo) continue;
                const dLat = allTel[i].la - clickLat;
                const dLon = allTel[i].lo - clickLon;
                const dist = dLat * dLat + dLon * dLon;
                if (dist < minDist) { minDist = dist; closestIdx = i; }
            }
            const slider = document.getElementById('timelineSlider');
            if (slider) { slider.value = closestIdx; self.updateSliderDisplay(closestIdx); }
        });
    },

    // ==================== DELETE ====================

    async deleteTrip(tripId) {
        if (!confirm('Delete this trip? This cannot be undone.')) return;
        try {
            const resp = await fetch('/api/trips/' + tripId, { method: 'DELETE' });
            const data = await resp.json();
            if (data.success) {
                const id = Number(tripId);
                this.trips = this.trips.filter(t => t.id !== id);
                this.renderTripList(this.trips);
                if (this.currentTripId == tripId) this.hideDetail();
            }
        } catch (e) { console.warn('[Trips] Delete failed:', e); }
    },

    deleteCurrentTrip() {
        if (this.currentTripId) this.deleteTrip(this.currentTripId);
    },

    // ==================== HELPERS ====================

    setEl(id, val) {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
    },

    getAvgScore(trip) {
        const a = trip.anticipationScore || trip.anticipation_score || 0;
        const s = trip.smoothnessScore || trip.smoothness_score || 0;
        const sd = trip.speedDisciplineScore || trip.speed_discipline_score || 0;
        const e = trip.efficiencyScore || trip.efficiency_score || 0;
        const c = trip.consistencyScore || trip.consistency_score || 0;
        return Math.floor((a + s + sd + e + c) / 5);
    },

    formatDuration(seconds) {
        if (!seconds || seconds <= 0) return '--';
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        if (h > 0) return h + 'h ' + m + 'm';
        return m + ' min';
    }
};
