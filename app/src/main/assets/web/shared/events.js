/**
 * BYD Champ - Events Module
 * Calendar-based recording browser with video playback, pagination & thumbnails
 */

window.BYD = window.BYD || {};

BYD.events = {
    currentDate: new Date(),
    selectedDate: null,
    currentFilter: 'all',
    recordings: [],
    totalCount: 0,
    datesWithRecordings: new Map(),
    currentPage: 1,
    pageSize: 12,
    totalPages: 1,
    
    // Multi-select state
    selectMode: false,
    selectedFiles: new Set(),

    // In-flight recording state. Populated only when the user is deep-linked
    // (?file=…) from a fresh notification AND the referenced file isn't yet
    // in the recordings list (it's still <name>.mp4.tmp on disk). The
    // recordings page renders a pinned placeholder card while this is set,
    // and polls /api/recordings/inflight/<file> every 2s to detect when the
    // post-record window finishes and the rename completes.
    inflightFilename: null,
    inflightPollHandle: null,

    // v3 actor/severity/proximity filter state (item 6). Empty values = no filter.
    actorFilter: { class: '', severity: '', proximity: '' },


    async init() {
        const urlParams = new URLSearchParams(window.location.search);
        const filterParam = urlParams.get('filter');
        if (filterParam && ['all', 'sentry', 'normal', 'proximity'].includes(filterParam)) {
            this.currentFilter = filterParam;
            document.querySelectorAll('.filter-tab').forEach(tab => {
                tab.classList.toggle('active', tab.dataset.filter === filterParam);
            });
        }
        const fileParam = urlParams.get('file');

        this.renderCalendar();
        this.updateRecordingsTitle();
        this.updateCalendarButton();
        await this.loadDatesWithRecordings();
        await this.loadStorageStats();
        await this.loadRecordings();

        // Deep-link from a notification.
        //   - If the recording is finalized (in this.recordings): open the player.
        //   - If it's still being written (.mp4.tmp on disk): show a pinned
        //     placeholder card and poll for finalization.
        //   - If it's neither finalized nor in flight (older notification, file
        //     was deleted, etc.): silently no-op so we don't yank the user
        //     around.
        if (fileParam) {
            const found = this.recordings.find(r => r.filename === fileParam);
            if (found) {
                this.playVideo(fileParam);
            } else {
                this.checkAndPollInflight(fileParam);
            }
        }
        
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeVideo();
                this.closeCalendar();
            }
        });
        
        document.getElementById('videoModal').addEventListener('click', (e) => {
            if (e.target.id === 'videoModal') this.closeVideo();
        });
        
        document.getElementById('calendarPopup').addEventListener('click', (e) => {
            if (e.target.id === 'calendarPopup') this.closeCalendar();
        });
        
        const calendarPopup = document.getElementById('calendarPopup');
        const videoModal = document.getElementById('videoModal');
        
        const observer = new MutationObserver(() => {
            const isOpen = calendarPopup.classList.contains('active') || videoModal.classList.contains('active');
            document.body.style.overflow = isOpen ? 'hidden' : '';
        });
        
        observer.observe(calendarPopup, { attributes: true });
        observer.observe(videoModal, { attributes: true });

        // Stop inflight polling when the page is unloaded or hidden — without
        // this, setInterval keeps firing in the background after the user
        // navigates away (BFCache visit, swipe-to-back PWA gesture, etc.).
        // visibilitychange covers tab-hidden too, since the platform may not
        // run setInterval reliably while hidden anyway.
        const stopOnExit = () => this.stopInflightPolling();
        window.addEventListener('pagehide', stopOnExit);
        window.addEventListener('beforeunload', stopOnExit);
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden') stopOnExit();
        });
    },

    /** Idempotent stop — safe to call multiple times. */
    stopInflightPolling() {
        if (this.inflightPollHandle) {
            clearInterval(this.inflightPollHandle);
            this.inflightPollHandle = null;
        }
    },
    
    toggleCalendar() {
        document.getElementById('calendarPopup').classList.toggle('active');
    },
    
    closeCalendar() {
        document.getElementById('calendarPopup').classList.remove('active');
    },
    
    updateCalendarButton() {
        const btn = document.getElementById('calendarToggle');
        const text = document.getElementById('calendarBtnText');
        
        if (this.selectedDate) {
            const date = new Date(this.selectedDate + 'T00:00:00');
            text.textContent = date.toLocaleDateString(BYD.i18n.getLang(), { month: 'short', day: 'numeric' });
            btn.classList.add('has-date');
        } else {
            text.textContent = BYD.i18n.t('events.select_date');
            btn.classList.remove('has-date');
        }
    },
    
    renderCalendar() {
        const grid = document.getElementById('calendarGrid');
        const title = document.getElementById('calendarTitle');
        const year = this.currentDate.getFullYear();
        const month = this.currentDate.getMonth();
        
        // i18n: Use Intl.DateTimeFormat for localized month/weekday names instead of hardcoded English arrays.
        var monthDate = new Date(year, month, 1);
        var monthName;
        try {
            monthName = new Intl.DateTimeFormat(BYD.i18n.getLang(), { month: 'long' }).format(monthDate);
        } catch (e) {
            monthName = monthDate.toLocaleDateString(BYD.i18n.getLang(), { month: 'long' });
        }
        title.textContent = monthName + ' ' + year;
        grid.innerHTML = '';

        var weekdayFmt;
        try {
            weekdayFmt = new Intl.DateTimeFormat(BYD.i18n.getLang(), { weekday: 'short' });
        } catch (e) { weekdayFmt = null; }
        // Use Sunday 2024-01-07 .. Saturday 2024-01-13 as a known week for label rendering
        for (var w = 0; w < 7; w++) {
            const dateForDay = new Date(2024, 0, 7 + w); // Sun..Sat
            const label = weekdayFmt ? weekdayFmt.format(dateForDay) : dateForDay.toLocaleDateString(BYD.i18n.getLang(), { weekday: 'short' });
            const el = document.createElement('div');
            el.className = 'calendar-weekday';
            el.textContent = label;
            grid.appendChild(el);
        }
        
        const firstDay = new Date(year, month, 1).getDay();
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        const daysInPrevMonth = new Date(year, month, 0).getDate();
        const todayStr = this.formatDateKey(new Date());
        
        for (let i = firstDay - 1; i >= 0; i--) {
            const day = daysInPrevMonth - i;
            this.addDayCell(grid, day, this.formatDateKey(new Date(year, month - 1, day)), true);
        }
        
        for (let day = 1; day <= daysInMonth; day++) {
            const dateKey = this.formatDateKey(new Date(year, month, day));
            this.addDayCell(grid, day, dateKey, false, dateKey === todayStr, this.selectedDate === dateKey);
        }
        
        const totalCells = grid.children.length;
        for (let day = 1; totalCells + day - 7 <= 42; day++) {
            this.addDayCell(grid, day, this.formatDateKey(new Date(year, month + 1, day)), true);
        }
    },
    
    addDayCell(grid, day, dateKey, isOtherMonth, isToday, isSelected) {
        const el = document.createElement('div');
        el.className = 'calendar-day';
        el.textContent = day;
        el.dataset.date = dateKey;
        
        if (isOtherMonth) el.classList.add('other-month');
        if (isToday) el.classList.add('today');
        if (isSelected) el.classList.add('selected');
        
        // Disable future dates
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const cellDate = new Date(dateKey + 'T00:00:00');
        const isFuture = cellDate > today;
        
        if (isFuture) {
            el.classList.add('disabled');
        } else {
            const dateInfo = this.datesWithRecordings.get(dateKey);
            if (dateInfo) {
                el.classList.add('has-recordings');
                if (dateInfo.hasSentry) el.classList.add('has-sentry');
            }
            el.addEventListener('click', () => this.selectDate(dateKey));
        }
        
        grid.appendChild(el);
    },
    
    formatDateKey(date) {
        return date.getFullYear() + '-' + 
               String(date.getMonth() + 1).padStart(2, '0') + '-' + 
               String(date.getDate()).padStart(2, '0');
    },
    
    prevMonth() {
        this.currentDate.setMonth(this.currentDate.getMonth() - 1);
        this.renderCalendar();
    },
    
    nextMonth() {
        this.currentDate.setMonth(this.currentDate.getMonth() + 1);
        this.renderCalendar();
    },
    
    selectDate(dateKey) {
        this.selectedDate = this.selectedDate === dateKey ? null : dateKey;
        document.querySelectorAll('.calendar-day').forEach(el => {
            el.classList.toggle('selected', el.dataset.date === this.selectedDate);
        });
        this.updateRecordingsTitle();
        this.updateCalendarButton();
        this.currentPage = 1;
        this.loadRecordings();
        this.closeCalendar();
    },
    
    setFilter(filter) {
        this.currentFilter = filter;
        document.querySelectorAll('.filter-tab').forEach(tab => {
            tab.classList.toggle('active', tab.dataset.filter === filter);
        });
        this.updateRecordingsTitle();
        this.currentPage = 1;
        this.loadRecordings();
    },

    /**
     * Set v3 actor / severity / proximity filter (item 6).
     * Empty value clears the row. Updates chip active states.
     */
    setActorFilter(kind, value) {
        if (!this.actorFilter) this.actorFilter = { class: '', severity: '', proximity: '' };
        this.actorFilter[kind] = value || '';
        const rowSel = '.filter-tabs[data-filter-row="' + kind + '"] .filter-chip';
        document.querySelectorAll(rowSel).forEach(chip => {
            chip.classList.toggle('active', (chip.dataset[kind] || '') === (value || ''));
        });
        this.currentPage = 1;
        this.loadRecordings();
    },
    
    updateRecordingsTitle() {
        const title = document.getElementById('recordingsTitle');
        let prefix = this.selectedDate
            ? new Date(this.selectedDate + 'T00:00:00').toLocaleDateString(BYD.i18n.getLang(), { month: 'short', day: 'numeric', year: 'numeric' })
            : BYD.i18n.t('events.all');

        var suffixKey;
        if (this.currentFilter === 'sentry') suffixKey = 'events.title_sentry';
        else if (this.currentFilter === 'proximity') suffixKey = 'events.title_proximity';
        else suffixKey = 'events.title_recordings';
        title.textContent = BYD.i18n.t(suffixKey, {prefix: prefix});
    },
    
    updatePagination() {
        const pagination = document.getElementById('pagination');
        const prevBtn = document.getElementById('prevPageBtn');
        const nextBtn = document.getElementById('nextPageBtn');
        const info = document.getElementById('paginationInfo');
        
        if (this.totalPages <= 1) {
            pagination.style.display = 'none';
            return;
        }
        
        pagination.style.display = 'flex';
        prevBtn.disabled = this.currentPage <= 1;
        nextBtn.disabled = this.currentPage >= this.totalPages;
        info.textContent = BYD.i18n.t('events.page_of', {page: this.currentPage, total: this.totalPages});
    },
    
    prevPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.loadRecordings();
            document.getElementById('recordingsList').scrollTop = 0;
        }
    },
    
    nextPage() {
        if (this.currentPage < this.totalPages) {
            this.currentPage++;
            this.loadRecordings();
            document.getElementById('recordingsList').scrollTop = 0;
        }
    },

    async loadDatesWithRecordings() {
        try {
            const res = await fetch('/api/recordings/dates');
            const data = await res.json();
            if (data.success && data.dates) {
                this.datesWithRecordings.clear();
                data.dates.forEach(d => {
                    this.datesWithRecordings.set(d.date, { count: d.count, hasSentry: d.hasSentry });
                });
                this.renderCalendar();
            }
        } catch (e) {
            console.error('Failed to load dates:', e);
        }
    },
    
    async loadStorageStats() {
        try {
            const res = await fetch('/api/recordings/stats');
            const data = await res.json();
            if (data.success) {
                document.getElementById('storageUsed').textContent = data.totalSizeFormatted;
                const usedPercent = data.totalSpace > 0 ? (data.totalSize / data.totalSpace) * 100 : 0;
                document.getElementById('storageFill').style.width = Math.min(usedPercent, 100) + '%';
                document.getElementById('normalCount').textContent = data.normalCount || 0;
                document.getElementById('sentryCount').textContent = data.sentryCount || 0;
                document.getElementById('proximityCount').textContent = data.proximityCount || 0;
            }
        } catch (e) {
            console.error('Failed to load storage stats:', e);
        }
    },
    
    /**
     * Single source of truth for filename → thumbnail DOM id. Both the inflight
     * placeholder and the recordings-list renderer use this so the
     * "highlight just-finalized card" lookup can't drift from the id used
     * when the card was rendered.
     */
    _thumbDomId(filename) {
        return 'thumb-' + filename.replace(/[^a-zA-Z0-9]/g, '_');
    },

    /**
     * Probe the backend for an in-flight .mp4.tmp matching the deep-linked
     * filename. If it's there, set state and start polling so the placeholder
     * card upgrades to a normal entry the moment the post-record window ends.
     */
    async checkAndPollInflight(filename) {
        try {
            const res = await fetch('/api/recordings/inflight/' + encodeURIComponent(filename));
            const data = await res.json();
            if (!data || !data.inflight) {
                // File isn't in flight — no placeholder, no polling. The
                // recording either finished and was paginated past the first
                // page, or it never existed.
                return;
            }
            this.inflightFilename = filename;
            this.renderRecordings();
            this.startInflightPolling();
        } catch (e) {
            console.warn('[events] inflight probe failed:', e);
        }
    },

    startInflightPolling() {
        this.stopInflightPolling();
        this.inflightPollHandle = setInterval(async () => {
            if (!this.inflightFilename) {
                this.stopInflightPolling();
                return;
            }
            try {
                const res = await fetch('/api/recordings/inflight/' +
                    encodeURIComponent(this.inflightFilename));
                const data = await res.json();
                if (!data.inflight) {
                    // Rename finished. Clear the placeholder, reload the list
                    // so the freshly-renamed file appears in its proper slot,
                    // and stop polling. We deliberately do NOT auto-open the
                    // video — the user came here from a notification a few
                    // seconds ago; surfacing the entry visibly is enough.
                    const finishedFile = this.inflightFilename;
                    this.inflightFilename = null;
                    this.stopInflightPolling();
                    await this.loadRecordings();
                    // If the file landed on this page, briefly highlight it.
                    const node = document.getElementById(this._thumbDomId(finishedFile));
                    const card = node ? node.closest('.recording-card') : null;
                    if (card) {
                        card.classList.add('recording-card-just-finalized');
                        setTimeout(() => {
                            card.classList.remove('recording-card-just-finalized');
                        }, 2000);
                    }
                }
            } catch (e) {
                // Network blip — keep polling, don't bail.
            }
        }, 2000);
    },

    /**
     * Build the pinned "Recording in progress" card markup. Reuses the
     * existing recording-card / recording-thumbnail / recording-info CSS so
     * it slots in visually with the rest of the list. Distinguished by the
     * .recording-card-inflight modifier (subtle pulse) and a Recording badge
     * sitting where the duration badge would normally be.
     *
     * Thumbnail loads from the same /thumb/ endpoint, which now serves a sync
     * frame from the .mp4.tmp file before post-record finalises (see
     * RecordingsApiHandler.findVideoFile, allowInFlightTmp).
     */
    renderInflightCard(filename) {
        const thumbId = this._thumbDomId(filename);
        const fname = filename.length > 28 ? filename.substring(0, 25) + '...' : filename;
        const thumbUrl = '/thumb/' + encodeURIComponent(filename);
        return '' +
            '<div class="recording-card recording-card-inflight" data-filename="' + filename + '">' +
                '<div class="recording-thumbnail" id="' + thumbId + '" data-thumb="' + thumbUrl + '">' +
                    '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg></div>' +
                    '<span class="inflight-badge"><span class="inflight-dot"></span>' + BYD.i18n.t('events.recording_badge') + '</span>' +
                '</div>' +
                '<div class="recording-info">' +
                    '<div class="recording-name"><span class="recording-badge live">' + BYD.i18n.t('events.live_badge') + '</span>' + fname + '</div>' +
                    '<div class="recording-meta"><span>' + BYD.i18n.t('events.available_in_seconds') + '</span></div>' +
                '</div>' +
            '</div>';
    },

    async loadRecordings() {
        const list = document.getElementById('recordingsList');
        list.innerHTML = '<div class="loading"><div class="spinner"></div></div>';

        try {
            let url = '/api/recordings';
            const params = [];
            if (this.currentFilter !== 'all') params.push('type=' + this.currentFilter);
            if (this.selectedDate) params.push('date=' + this.selectedDate);
            // v3 actor filters (item 6)
            if (this.actorFilter && this.actorFilter.class)     params.push('class=' + encodeURIComponent(this.actorFilter.class));
            if (this.actorFilter && this.actorFilter.severity)  params.push('severity=' + encodeURIComponent(this.actorFilter.severity));
            if (this.actorFilter && this.actorFilter.proximity) params.push('proximity=' + encodeURIComponent(this.actorFilter.proximity));
            params.push('page=' + this.currentPage);
            params.push('pageSize=' + this.pageSize);
            url += '?' + params.join('&');
            
            const res = await fetch(url);
            const data = await res.json();
            
            if (data.success) {
                this.recordings = data.recordings || [];
                this.totalPages = data.totalPages || 1;
                this.totalCount = data.totalCount || this.recordings.length;
                this.renderRecordings();
                this.updatePagination();
                document.getElementById('recordingsCount').textContent =
                    BYD.i18n.plural('events.video_count', this.totalCount);
            }
        } catch (e) {
            console.error('Failed to load recordings:', e);
            list.innerHTML = '<div class="empty-state"><svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg><div class="empty-title">' + BYD.i18n.t('events.empty_failed_title') + '</div><div class="empty-text">' + BYD.i18n.t('events.empty_failed_text') + '</div></div>';
        }
    },
    
    renderRecordings() {
        const list = document.getElementById('recordingsList');

        // Inflight placeholder always renders FIRST so it's visible even when
        // the rest of the page is empty (deep-link arrived faster than any
        // pagination of older recordings finished).
        const inflightHtml = this.inflightFilename
            ? this.renderInflightCard(this.inflightFilename)
            : '';

        if (this.recordings.length === 0) {
            if (inflightHtml) {
                list.innerHTML = inflightHtml;
            } else {
                list.innerHTML = '<div class="empty-state"><svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><div class="empty-title">' + BYD.i18n.t('events.empty_none_title') + '</div><div class="empty-text">' + BYD.i18n.t('events.empty_none_text') + '</div></div>';
            }
            return;
        }

        list.innerHTML = inflightHtml + this.recordings.map(rec => {
            const thumbId = this._thumbDomId(rec.filename);
            const badge = rec.type === 'sentry' ? BYD.i18n.t('events.badge_sentry') : rec.type === 'proximity' ? BYD.i18n.t('events.badge_proximity') : BYD.i18n.t('events.badge_normal');
            const fname = rec.filename.length > 28 ? rec.filename.substring(0, 25) + '...' : rec.filename;
            const isSelected = this.selectedFiles.has(rec.filename);
            
            // Checkbox for select mode
            const checkbox = this.selectMode 
                ? '<label class="select-checkbox-wrap" onclick="event.stopPropagation()"><input type="checkbox" class="select-checkbox" ' + (isSelected ? 'checked' : '') + ' onchange="BYD.events.toggleFileSelection(\'' + rec.filename + '\', event)"></label>'
                : '';
            
            // Card click handler depends on mode
            const cardClick = this.selectMode 
                ? 'BYD.events.toggleFileSelection(\'' + rec.filename + '\')'
                : 'BYD.events.playVideo(\'' + rec.filename + '\')';
            
            // v3 enrichment (item 6): use hero JPEG when present, sev badge, actor summary
            const sev = (rec.peakSeverity || '').toUpperCase();
            const sevClass = sev === 'CRITICAL' ? 'sev-critical' : sev === 'ALERT' ? 'sev-alert' : '';
            const sevLabel = sev === 'CRITICAL' ? BYD.i18n.t('events.severity_critical')
                : sev === 'ALERT' ? BYD.i18n.t('events.severity_alert')
                : sev === 'NOTICE' ? BYD.i18n.t('events.severity_notice')
                : sev;
            const sevBadge = sev ? '<span class="recording-badge sev-' + sev.toLowerCase() + '">' + sevLabel + '</span>' : '';
            const thumbUrl = rec.heroThumbnailUrl || rec.thumbnailUrl || '';
            const personCount  = rec.personCount  || rec.personSpans  || 0;
            const vehicleCount = rec.vehicleCount || rec.vehicleSpans || 0;
            const bikeCount    = rec.bikeCount    || rec.bikeSpans    || 0;
            const animalCount  = rec.animalCount  || 0;
            const proxLabel = (function(p) {
                switch ((p||'').toUpperCase()) {
                    case 'VERY_CLOSE': return BYD.i18n.t('events.prox_very_close');
                    case 'CLOSE': return BYD.i18n.t('events.prox_close');
                    case 'MID': return BYD.i18n.t('events.prox_mid');
                    case 'FAR': return BYD.i18n.t('events.prox_far');
                    default: return '';
                }
            })(rec.peakProximity);
            let actorPills = '';
            if (personCount > 0)  actorPills += '<span class="pill">👤 ' + personCount + '</span>';
            if (vehicleCount > 0) actorPills += '<span class="pill">🚗 ' + vehicleCount + '</span>';
            if (bikeCount > 0)    actorPills += '<span class="pill">🚲 ' + bikeCount + '</span>';
            if (animalCount > 0)  actorPills += '<span class="pill">🐾 ' + animalCount + '</span>';
            if (proxLabel)        actorPills += '<span class="pill prox-' + (rec.peakProximity || 'UNKNOWN') + '">' + proxLabel + '</span>';
            const actorRow = actorPills ? '<div class="actor-summary">' + actorPills + '</div>' : '';

            return '<div class="recording-card' + (isSelected ? ' selected' : '') + (sevClass ? ' ' + sevClass : '') + '" data-filename="' + rec.filename + '" onclick="' + cardClick + '">' +
                checkbox +
                '<div class="recording-thumbnail" id="' + thumbId + '" data-thumb="' + thumbUrl + '">' +
                '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg></div>' +
                '<div class="play-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>' +
                (rec.duration ? '<span class="duration-badge">' + rec.duration + '</span>' : '') +
                '</div>' +
                '<div class="recording-info">' +
                '<div class="recording-name"><span class="recording-badge ' + rec.type + '">' + badge + '</span>' + sevBadge + fname + '</div>' +
                '<div class="recording-meta"><span>' + rec.dateFormatted + '</span><span>' + rec.timeFormatted + '</span><span>' + rec.sizeFormatted + '</span></div>' +
                actorRow +
                '</div>' +
                (this.selectMode ? '' : 
                '<div class="recording-actions">' +
                '<button class="action-btn" onclick="event.stopPropagation(); BYD.events.downloadVideo(\'' + rec.filename + '\')" title="' + BYD.i18n.t('common.download') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>' +
                '<button class="action-btn delete" onclick="event.stopPropagation(); BYD.events.deleteRecording(\'' + rec.filename + '\')" title="' + BYD.i18n.t('common.delete') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>' +
                '</div>') +
                '</div>';
        }).join('');
        
        // Load thumbnails async after render
        this.loadThumbnailsAsync();
    },
    
    // Load thumbnails asynchronously without blocking
    loadThumbnailsAsync() {
        document.querySelectorAll('.recording-thumbnail[data-thumb]').forEach(container => {
            const thumbUrl = container.dataset.thumb;
            if (!thumbUrl) return;
            
            this.loadSingleThumbnail(container, thumbUrl, 0);
        });
    },
    
    // Load single thumbnail with retry on 202
    loadSingleThumbnail(container, url, retryCount) {
        if (retryCount > 8) {
            // After max retries, show "no preview" state
            const placeholder = container.querySelector('.thumb-placeholder');
            if (placeholder) {
                placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
            }
            return;
        }
        
        fetch(url).then(res => {
            if (res.status === 200) {
                return res.blob();
            } else if (res.status === 202) {
                // Generating, retry with exponential backoff
                const delay = Math.min(1000 * Math.pow(1.5, retryCount), 5000);
                setTimeout(() => this.loadSingleThumbnail(container, url, retryCount + 1), delay);
                return null;
            }
            return null;
        }).then(blob => {
            if (blob && blob.size > 0) {
                const imgUrl = URL.createObjectURL(blob);
                const placeholder = container.querySelector('.thumb-placeholder');
                if (placeholder) {
                    const img = document.createElement('img');
                    img.src = imgUrl;
                    img.alt = BYD.i18n.t('events.thumbnail_alt');
                    img.onload = () => placeholder.remove();
                    img.onerror = () => {
                        URL.revokeObjectURL(imgUrl);
                        placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
                    };
                    container.insertBefore(img, container.firstChild);
                }
            }
        }).catch(() => {
            // Network error - show fallback
            const placeholder = container.querySelector('.thumb-placeholder');
            if (placeholder) {
                placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
            }
        });
    },
    
    onThumbError(el) {
        const container = el.parentElement;
        container.innerHTML = '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span></div><div class="play-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>';
    },
    
    playVideo(filename) {
        const rec = this.recordings.find(r => r.filename === filename);
        if (!rec) return;
        
        document.getElementById('videoTitle').textContent = rec.filename;
        document.getElementById('videoDate').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg> ' + rec.dateFormatted;
        document.getElementById('videoTime').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> ' + rec.timeFormatted;
        document.getElementById('videoSize').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> ' + rec.sizeFormatted;
        
        const downloadBtn = document.getElementById('downloadBtn');
        downloadBtn.href = rec.videoUrl;
        downloadBtn.download = rec.filename;
        
        const player = document.getElementById('videoPlayer');
        player.setAttribute('playsinline', '');
        player.setAttribute('webkit-playsinline', '');
        player.src = rec.videoUrl;
        document.getElementById('videoModal').classList.add('active');
        player.load();
        player.oncanplay = function() { player.play().catch(function() {}); player.oncanplay = null; };
        
        // SOTA: Load event timeline for this recording
        this.loadTimeline(rec.filename, player);
    },
    
    closeVideo() {
        const player = document.getElementById('videoPlayer');
        player.pause();
        player.src = '';
        document.getElementById('videoModal').classList.remove('active');
        
        // SOTA: Clean up timeline
        this.destroyTimeline();
    },
    
    downloadVideo(filename) {
        const rec = this.recordings.find(r => r.filename === filename);
        if (!rec) return;
        const a = document.createElement('a');
        a.href = rec.videoUrl;
        a.download = rec.filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    },
    
    async deleteRecording(filename) {
        if (!confirm(BYD.i18n.t('events.confirm_delete_one', {filename: filename}))) return;

        // If the modal player is currently showing this recording, pause it
        // and detach the source BEFORE the DELETE goes through. Otherwise
        // the in-flight Range request races with the file disappearing and
        // the user sees an "error: source not supported" toast.
        try {
            const player = document.getElementById('videoPlayer');
            if (player && player.src && player.src.endsWith('/video/' + filename)) {
                this.closeVideo();
            }
        } catch (_) {}

        try {
            const res = await fetch('/api/recordings/' + filename, { method: 'DELETE' });
            const data = await res.json();
            
            if (data.success) {
                await this.loadDatesWithRecordings();
                await this.loadStorageStats();
                await this.loadRecordings();
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(BYD.i18n.t('events.toast_deleted'), 'success');
                }
            } else {
                alert(BYD.i18n.t('events.alert_delete_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
            }
        } catch (e) {
            console.error('Delete failed:', e);
            alert(BYD.i18n.t('events.alert_delete_failed_generic'));
        }
    },
    
    // ========================================================================
    // Multi-Select & Batch Delete
    // ========================================================================
    
    toggleSelectMode() {
        this.selectMode = !this.selectMode;
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    exitSelectMode() {
        this.selectMode = false;
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    toggleFileSelection(filename, event) {
        if (event) event.stopPropagation();
        
        if (this.selectedFiles.has(filename)) {
            this.selectedFiles.delete(filename);
        } else {
            this.selectedFiles.add(filename);
        }
        this.updateSelectUI();
        this.updateCardSelection(filename);
    },
    
    selectAll() {
        this.recordings.forEach(rec => this.selectedFiles.add(rec.filename));
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    deselectAll() {
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    updateCardSelection(filename) {
        const card = document.querySelector('[data-filename="' + filename + '"]');
        if (card) {
            card.classList.toggle('selected', this.selectedFiles.has(filename));
            const checkbox = card.querySelector('.select-checkbox');
            if (checkbox) checkbox.checked = this.selectedFiles.has(filename);
        }
    },
    
    updateSelectUI() {
        const toolbar = document.getElementById('selectToolbar');
        const selectBtn = document.getElementById('selectModeBtn');
        const count = document.getElementById('selectedCount');
        
        if (this.selectMode) {
            if (toolbar) toolbar.style.display = 'flex';
            if (selectBtn) selectBtn.classList.add('active');
            if (count) count.textContent = BYD.i18n.t('events.n_selected', {n: this.selectedFiles.size});
        } else {
            if (toolbar) toolbar.style.display = 'none';
            if (selectBtn) selectBtn.classList.remove('active');
        }
    },
    
    async batchDelete() {
        const count = this.selectedFiles.size;
        if (count === 0) return;
        
        if (!confirm(BYD.i18n.plural('events.confirm_delete_n', count))) return;
        
        const filenames = Array.from(this.selectedFiles);
        
        try {
            const res = await fetch('/api/recordings/batch-delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filenames: filenames })
            });
            const data = await res.json();
            
            if (data.success) {
                const msg = data.failed > 0
                    ? BYD.i18n.t('events.batch_deleted_with_failures', {deleted: data.deleted, failed: data.failed})
                    : BYD.i18n.t('events.batch_deleted', {deleted: data.deleted});
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(msg, data.failed > 0 ? 'warning' : 'success');
                }
                this.exitSelectMode();
                await this.loadDatesWithRecordings();
                await this.loadStorageStats();
                await this.loadRecordings();
            } else {
                alert(BYD.i18n.t('events.batch_delete_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
            }
        } catch (e) {
            console.error('Batch delete failed:', e);
            alert(BYD.i18n.t('events.batch_delete_failed_generic'));
        }
    },
    
    // ========================================================================
    // SOTA: Event Timeline Scrubber
    // ========================================================================
    
    _timelineRaf: null,
    _timelineEvents: null,
    
    /**
     * Load event timeline data and render markers.
     * Backward compatible: if no JSON sidecar exists, timeline stays hidden.
     */
    async loadTimeline(filename, videoEl) {
        const scrubber = document.getElementById('timelineScrubber');
        const legend = document.getElementById('timelineLegend');
        const track = document.getElementById('timelineTrack');
        const playhead = document.getElementById('timelinePlayhead');
        
        // Guard: if timeline HTML elements don't exist (old cached page), skip silently
        if (!scrubber || !track || !playhead) return;
        
        // Reset
        track.innerHTML = '';
        playhead.style.left = '0px';
        scrubber.style.display = 'none';
        if (legend) legend.style.display = 'none';
        this._timelineEvents = null;
        
        try {
            const res = await fetch('/api/events/' + filename);
            const data = await res.json();
            
            if (!data.events || data.events.length === 0) {
                // No events — backward compatible, just hide the scrubber
                return;
            }
            
            this._timelineEvents = data;
            
            // Wait for video metadata to get duration
            const renderMarkers = () => {
                const duration = videoEl.duration;
                if (!duration || duration <= 0) return;
                
                const durationMs = data.durationMs > 0 ? data.durationMs : duration * 1000;
                
                track.innerHTML = '';
                
                for (const ev of data.events) {
                    const marker = document.createElement('div');
                    marker.className = 'timeline-marker type-' + ev.type;
                    
                    const leftPct = (ev.start / durationMs) * 100;
                    const widthPct = Math.max(((ev.end - ev.start) / durationMs) * 100, 0.5);
                    
                    marker.style.left = leftPct + '%';
                    marker.style.width = widthPct + '%';
                    track.appendChild(marker);
                }
                
                scrubber.style.display = 'block';
                if (legend) legend.style.display = 'flex';
            };
            
            if (videoEl.readyState >= 1) {
                renderMarkers();
            } else {
                videoEl.addEventListener('loadedmetadata', renderMarkers, { once: true });
            }
            
            // Click-to-seek on the timeline
            scrubber.onclick = (e) => {
                const rect = scrubber.getBoundingClientRect();
                const pct = (e.clientX - rect.left) / rect.width;
                if (videoEl.duration) {
                    videoEl.currentTime = pct * videoEl.duration;
                }
            };
            
            // Playhead tracking at 10 FPS (matches surveillance engine rate)
            const updatePlayhead = () => {
                if (!videoEl.paused && videoEl.duration > 0) {
                    const pct = (videoEl.currentTime / videoEl.duration) * 100;
                    playhead.style.left = pct + '%';
                }
                this._timelineRaf = requestAnimationFrame(updatePlayhead);
            };
            this._timelineRaf = requestAnimationFrame(updatePlayhead);
            
        } catch (e) {
            // Fetch failed — backward compatible, just hide
            console.debug('No timeline data for', filename);
        }
    },
    
    /**
     * Clean up timeline resources.
     */
    destroyTimeline() {
        if (this._timelineRaf) {
            cancelAnimationFrame(this._timelineRaf);
            this._timelineRaf = null;
        }
        this._timelineEvents = null;
        
        const scrubber = document.getElementById('timelineScrubber');
        const legend = document.getElementById('timelineLegend');
        if (scrubber) scrubber.style.display = 'none';
        if (legend) legend.style.display = 'none';
    }
};