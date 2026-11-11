/**
 * OverDrive — Notification Log module (Notifications ▸ Log tab).
 *
 * A chronological, filterable, paginated history of every push notification the
 * daemon has emitted (surveillance, proximity, tyre, door, charging, SOH…).
 * Backed by /api/notifications/log (H2 NotificationStore). Tapping an event
 * notification jumps to the recording; other categories open their settings
 * page. Per-card delete + multi-select bulk delete.
 *
 * Consistency: mirrors charging.js / events.js idioms —
 *   - The date filter is the charging-page calendar (quick pills + custom
 *     From→To month-grid popup), ported verbatim and renamed nl* / NOTIFLOG.
 *   - Pagination is the events-page Prev / Next "Page X of Y" bar.
 *   - Cards, tokens, toasts, and confirm dialogs use the shared design system.
 *
 * WebView compatibility (head-unit is Chrome 58 on some pages):
 *   - No optional chaining (?.) / nullish (??), no Array.flat(), no roundRect.
 *   - POST/DELETE go through fetch() (the in-app WebView drops XHR write bodies).
 *   - Authenticated calls use BYDAuth.fetch (same as the rest of this page).
 */

var NOTIFLOG = {
    // ---- State ----
    currentPage: 1,
    totalPages: 1,
    pageSize: 20,
    total: 0,
    notifications: [],
    _loadSeq: 0,          // monotonic; drops out-of-order fetch responses
    _menuKind: null,      // which filter chip's sheet is open ('date'|'cat'|'sev')
    categories: [],          // registry entries: {id,label,group,severity,...}
    loaded: false,

    // Filters
    currentDays: 0,          // 0 = all time (default — a log wants everything)
    _rangeFrom: null,        // custom range (epoch-ms); null = use currentDays
    _rangeTo: null,
    group: '',               // category-prefix filter ('' = all), e.g. 'vehicle.charging'
    severity: '',            // '' = all | 'info' | 'warn' | 'critical'

    // Selection (bulk delete)
    selectMode: false,
    selected: {},            // id -> true

    // Calendar range-picker state (ported from charging.js).
    _calMonth: null,
    _draftFrom: null,     // draft range start key (YYYY-MM-DD) while the date sheet is open
    _draftTo: null,       // draft range end key; applied to _rangeFrom/_rangeTo only on Apply

    // ==================== LIFECYCLE ====================

    // Called lazily the first time the Log tab becomes active (see the
    // ot-tabs:active-changed hook wired in notifications.html). Deferring the
    // first fetch keeps the page's first paint on the Push/Telegram tab cheap.
    ensureLoaded: function () {
        if (this.loaded) return;
        this.loaded = true;
        this._syncChips();
        this.reload();
    },

    reload: function () {
        this.currentPage = 1;
        this.load();
    },

    load: function () {
        var self = this;
        var seq = ++this._loadSeq;   // tag this request; ignore stale responses
        var requestedPage = this.currentPage;
        this._showSkeleton();
        var url = '/api/notifications/log?' + this._query();
        BYDAuth.fetch(url).then(function (r) { return r.json(); })
            .then(function (d) {
                if (seq !== self._loadSeq) return;   // a newer load superseded us
                self._hideSkeleton();
                if (!d || d.success !== true) { self._renderEmpty(); return; }
                if (d.categories) self.categories = d.categories;
                self.notifications = d.notifications || [];
                self.total = d.total || 0;
                self.totalPages = d.totalPages || 1;
                self.currentPage = d.page || 1;
                // The requested page can become invalid after a deletion.
                // Compare the original request, not the server-reported page:
                // the server may already have clamped d.page, or returned an
                // empty result for an out-of-range request.
                if (requestedPage > self.totalPages && self.totalPages >= 1) {
                    self.currentPage = self.totalPages;
                    self.load();
                    return;
                }
                self._render();
            })
            .catch(function () {
                if (seq !== self._loadSeq) return;
                self._hideSkeleton();
                self._renderEmpty();
                self._toast(self._t('notiflog.load_failed', 'Could not load notifications'), 'error');
            });
    },

    // days / from / to + page + pageSize + optional group / severity.
    _query: function () {
        var q;
        if (this._rangeFrom != null) {
            q = 'from=' + this._rangeFrom;
            if (this._rangeTo != null) q += '&to=' + this._rangeTo;
        } else {
            q = 'days=' + (this.currentDays || 0);
        }
        q += '&page=' + this.currentPage + '&pageSize=' + this.pageSize;
        if (this.group) q += '&group=' + encodeURIComponent(this.group);
        if (this.severity) q += '&severity=' + encodeURIComponent(this.severity);
        return q;
    },

    // ==================== RENDER ====================

    _render: function () {
        var list = document.getElementById('nlList');
        if (!list) return;
        if (!this.notifications.length) { this._renderEmpty(); this._updatePagination(); this._updateBulkBar(); return; }

        var empty = document.getElementById('nlEmpty');
        if (empty) empty.style.display = 'none';

        list.innerHTML = '';
        for (var i = 0; i < this.notifications.length; i++) {
            list.appendChild(this._buildCard(this.notifications[i]));
        }
        this._updatePagination();
        this._updateBulkBar();
        this._updateCount();
    },

    _updateCount: function () {
        var el = document.getElementById('nlCount');
        if (!el) return;
        el.style.display = '';
        if (!this.total) {
            // Header count doubles as a compact empty label so the header never
            // collapses. The big empty-state block (#nlEmpty) still shows below.
            el.textContent = this._t('notiflog.empty', 'No notifications yet');
            return;
        }
        var tmpl = this.total === 1
            ? this._t('notiflog.count_one', '{n} notification')
            : this._t('notiflog.count_other', '{n} notifications');
        el.textContent = tmpl.replace('{n}', this.total);
    },

    _renderEmpty: function () {
        var list = document.getElementById('nlList');
        if (list) list.innerHTML = '';
        this.notifications = [];
        this.total = 0;
        this.totalPages = 1;
        var empty = document.getElementById('nlEmpty');
        if (empty) empty.style.display = '';
        this._updatePagination();
        this._updateBulkBar();
        this._updateCount();
    },

    _buildCard: function (n) {
        var self = this;
        var sev = (n.severity || 'info').toLowerCase();
        var card = document.createElement('div');
        card.className = 'nl-card sev-' + sev;
        card.setAttribute('data-id', n.id);

        var meta = this._categoryMeta(n.category);
        var isEvent = this._isEventNotification(n);
        // Clickable affordance: in select mode EVERY card toggles selection; out
        // of select mode only event/url cards navigate.
        if (this.selectMode || isEvent || n.url) card.className += ' nl-clickable';

        // ---- Selection checkbox (bulk mode) ----
        var checkCol = '';
        if (this.selectMode) {
            var checked = this.selected[n.id] ? ' checked' : '';
            checkCol =
                '<label class="nl-check" onclick="event.stopPropagation();">' +
                    '<input type="checkbox"' + checked + ' data-check="' + this._esc(n.id) + '">' +
                    '<span class="nl-check-box"></span>' +
                '</label>';
        }

        // ---- Severity dot + icon ----
        var icon = this._categoryIcon(meta ? meta.group : '');

        // ---- Title / body / meta ----
        var sevPill = '<span class="nl-sev-pill sev-' + sev + '">' + this._sevLabel(sev) + '</span>';
        var groupPill = meta ? '<span class="nl-group-pill">' + this._esc(this._groupLabel(meta.group)) + '</span>' : '';

        var place = '';
        if (n.data && n.data.place) place = '<span class="nl-place">' + this._esc(n.data.place) + '</span>';

        var chips = this._actorChips(n);

        var jumpHint = '';
        if (isEvent) {
            jumpHint = '<span class="nl-jump" aria-hidden="true">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg>' +
                '</span>';
        }

        var thumb = '';
        if (n.data && n.data.snapshot && this._safeUrl(n.data.snapshot)) {
            // Snapshot is a signed thumbnail URL (surveillance/proximity). Only
            // same-origin-relative or https URLs are allowed (no data:/javascript:
            // /remote-tracking). Lazy; hide on error so a dead link leaves no box.
            thumb = '<div class="nl-thumb"><img loading="lazy" src="' + this._esc(n.data.snapshot) +
                '" alt="' + this._t('notiflog.snapshot_alt', 'Event snapshot') +
                '" onerror="this.parentNode.style.display=\'none\'"></div>';
        }

        card.innerHTML =
            checkCol +
            '<div class="nl-icon sev-' + sev + '">' + icon + '</div>' +
            thumb +
            '<div class="nl-body">' +
                '<div class="nl-line1">' +
                    '<span class="nl-title">' + this._esc(n.title || '') + '</span>' +
                    jumpHint +
                '</div>' +
                (n.body ? '<div class="nl-text">' + this._esc(n.body) + '</div>' : '') +
                '<div class="nl-meta">' +
                    sevPill + groupPill + place + chips +
                    '<span class="nl-time">' + this._esc(this._fmtTime(n.ts)) + '</span>' +
                '</div>' +
            '</div>' +
            (this.selectMode ? '' :
                '<button class="nl-del" title="' + this._t('notiflog.delete', 'Delete') + '" data-del="' + this._esc(n.id) + '">' +
                    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
                '</button>');

        // ---- Wire interactions ----
        if (this.selectMode) {
            var cb = card.querySelector('input[type="checkbox"]');
            if (cb) cb.addEventListener('change', function () { self._toggleSelect(n.id, this.checked); });
            // In select mode the whole card toggles selection.
            card.addEventListener('click', function () {
                var box = card.querySelector('input[type="checkbox"]');
                var nowChecked = !self.selected[n.id];
                if (box) box.checked = nowChecked;
                self._toggleSelect(n.id, nowChecked);
            });
        } else {
            var del = card.querySelector('[data-del]');
            if (del) del.addEventListener('click', function (e) {
                e.stopPropagation();
                self.deleteOne(n.id);
            });
            if (card.className.indexOf('nl-clickable') >= 0) {
                card.setAttribute('role', 'button');
                card.setAttribute('tabindex', '0');
                card.setAttribute('aria-label', (n.title || '') + ' — ' + self._fmtTime(n.ts));
                card.addEventListener('click', function () { self._open(n); });
                card.addEventListener('keydown', function (e) {
                    if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13 || e.keyCode === 32) {
                        e.preventDefault();
                        self._open(n);
                    }
                });
            }
        }
        return card;
    },

    // ==================== NAVIGATION (jump to event / settings) ====================

    // Event notifications (surveillance/proximity) deep-link to a specific
    // recording. In the head-unit WebView, navigating to /events.html?file=…
    // is intercepted natively and routed straight to the video player (see
    // WebViewFragment.openEventByFilename). In an external browser, events.js
    // reads ?file= and opens the player. Either way, use the stored url.
    _open: function (n) {
        if (!n || !n.url) return;
        // Only navigate to same-origin, root-anchored app paths (e.g.
        // /events.html?…). Reject scheme-bearing (javascript:/data:/https:) or
        // protocol-relative (//host) URLs so a malformed/hostile stored url
        // can't drive an open-redirect or script execution.
        if (!this._safeUrl(n.url)) return;
        window.location.href = n.url;
    },

    // A URL is "safe" for navigation/img-src if it is a root-relative app path
    // ("/...") that is NOT protocol-relative ("//host"). Rejects everything
    // with a scheme.
    _safeUrl: function (u) {
        if (!u) return false;
        u = String(u);
        return u.charAt(0) === '/' && u.charAt(1) !== '/';
    },

    _isEventNotification: function (n) {
        if (!n) return false;
        var hasFile = n.data && (n.data.filename || (n.url && n.url.indexOf('file=') >= 0));
        var cat = (n.category || '');
        var isSurv = cat.indexOf('surveillance') === 0;
        return !!(hasFile || (isSurv && n.url));
    },

    // ==================== FILTERS (dropdown-chip model) ====================

    // Option tables for the three filter dimensions. `label` uses _t() at build
    // time (i18n key + English fallback). `value` is what the query sends.
    _dateOptions: function () {
        return [
            { value: 0,  key: 'notiflog.filter_all', fallback: 'All time' },
            { value: 7,  key: 'notiflog.filter_7d',  fallback: '7 days' },
            { value: 30, key: 'notiflog.filter_30d', fallback: '30 days' },
            { value: 'custom', key: 'notiflog.filter_custom', fallback: 'Custom range' }
        ];
    },
    _catOptions: function () {
        return [
            { value: '',                 key: 'notiflog.cat_all',          fallback: 'All' },
            { value: 'surveillance',     key: 'notiflog.cat_surveillance', fallback: 'Surveillance' },
            { value: 'vehicle.charging', key: 'notiflog.cat_charging',     fallback: 'Charging' },
            { value: 'vehicle.security', key: 'notiflog.cat_security',     fallback: 'Security' },
            { value: 'vehicle.health',   key: 'notiflog.cat_vehicle',      fallback: 'Vehicle' }
        ];
    },
    _sevOptions: function () {
        return [
            { value: '',         key: 'notiflog.sev_all',      fallback: 'All',      dot: '' },
            { value: 'critical', key: 'notiflog.sev_critical', fallback: 'Critical', dot: 'sev-critical' },
            { value: 'warn',     key: 'notiflog.sev_warn',     fallback: 'Alert',    dot: 'sev-warn' },
            { value: 'info',     key: 'notiflog.sev_info',     fallback: 'Info',     dot: 'sev-info' }
        ];
    },

    // Chip entry point. Date has its own self-contained sheet; cat/sev share the
    // simple option menu.
    openFilterMenu: function (kind) {
        if (kind === 'date') { this.openDateMenu(); return; }
        var self = this;
        this._menuKind = kind;
        var list = document.getElementById('nlMenuList');
        var title = document.getElementById('nlMenuTitle');
        var pop = document.getElementById('nlMenuPopup');
        if (!list || !pop) return;
        list.innerHTML = '';

        var opts, titleTxt, current;
        if (kind === 'cat') {
            opts = this._catOptions();
            titleTxt = this._t('notiflog.filter_category', 'Category');
            current = this.group;
        } else {
            opts = this._sevOptions();
            titleTxt = this._t('notiflog.filter_severity', 'Severity');
            current = this.severity;
        }
        if (title) title.textContent = titleTxt;

        for (var i = 0; i < opts.length; i++) {
            (function (opt) {
                var item = document.createElement('button');
                item.className = 'nl-menu-item' + (opt.value === current ? ' selected' : '');
                var dot = (kind === 'sev')
                    ? '<span class="nl-menu-dot ' + (opt.dot || '') + '"></span>'
                    : '';
                item.innerHTML = dot +
                    '<span>' + self._esc(self._t(opt.key, opt.fallback)) + '</span>' +
                    '<svg class="nl-menu-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>';
                item.addEventListener('click', function () { self._pickFilter(kind, opt.value); });
                list.appendChild(item);
            })(opts[i]);
        }

        // Backdrop tap-to-close, lazy-bound once.
        if (!pop._nlBound) {
            pop._nlBound = true;
            pop.addEventListener('click', function (e) { if (e.target === pop) self.closeFilterMenu(); });
        }
        pop.classList.add('active');
        this._setChipOpen(kind, true);
    },

    closeFilterMenu: function () {
        var pop = document.getElementById('nlMenuPopup');
        if (pop) pop.classList.remove('active');
        this._setChipOpen(this._menuKind, false);
        this._menuKind = null;
    },

    _pickFilter: function (kind, value) {
        // Date uses its own sheet (openDateMenu); this handles cat/sev only.
        if (kind === 'cat') this.group = value || '';
        else this.severity = value || '';
        this.closeFilterMenu();
        this._syncChips();
        this.reload();
    },

    resetFilters: function () {
        this.currentDays = 0;
        this._rangeFrom = null;
        this._rangeTo = null;
        this.group = '';
        this.severity = '';
        this._syncChips();
        this.reload();
    },

    // Reflect active selections in the three chips: label = current choice,
    // .is-active accent when a chip is non-default. Show the reset chip when
    // any filter is off its default.
    _syncChips: function () {
        // Date
        var dLabel, dActive;
        if (this._rangeFrom != null) {
            dLabel = this._rangeLabel(); dActive = true;
        } else {
            var dOpt = this._findOpt(this._dateOptions(), this.currentDays);
            dLabel = dOpt ? this._t(dOpt.key, dOpt.fallback) : this._t('notiflog.filter_all', 'All time');
            dActive = this.currentDays !== 0;
        }
        this._setChip('nlDateChip', 'nlDateChipLabel', dLabel, dActive);
        // Category
        var cOpt = this._findOpt(this._catOptions(), this.group || '');
        var cDefault = !this.group;
        this._setChip('nlCatChip', 'nlCatChipLabel',
            cDefault ? this._t('notiflog.filter_category', 'Category') : this._t(cOpt.key, cOpt.fallback),
            !cDefault);
        // Severity
        var sOpt = this._findOpt(this._sevOptions(), this.severity || '');
        var sDefault = !this.severity;
        this._setChip('nlSevChip', 'nlSevChipLabel',
            sDefault ? this._t('notiflog.filter_severity', 'Severity') : this._t(sOpt.key, sOpt.fallback),
            !sDefault);
        // Reset chip visibility
        var reset = document.getElementById('nlResetChip');
        if (reset) reset.style.display = (dActive || !cDefault || !sDefault) ? '' : 'none';
    },

    _findOpt: function (opts, value) {
        for (var i = 0; i < opts.length; i++) if (opts[i].value === value) return opts[i];
        return null;
    },

    _rangeLabel: function () {
        var lang = (window.BYD && BYD.i18n && BYD.i18n.getLang) ? BYD.i18n.getLang() : undefined;
        function fmt(ms) {
            try { return new Date(ms).toLocaleDateString(lang, { month: 'short', day: 'numeric' }); }
            catch (e) { return ''; }
        }
        if (this._rangeFrom != null && this._rangeTo != null) return fmt(this._rangeFrom) + ' – ' + fmt(this._rangeTo);
        if (this._rangeFrom != null) return fmt(this._rangeFrom) + ' –';
        return this._t('notiflog.filter_custom', 'Custom range');
    },

    _setChip: function (chipId, labelId, label, active) {
        var lbl = document.getElementById(labelId);
        if (lbl) lbl.textContent = label;
        var chip = document.getElementById(chipId);
        if (chip) { if (active) chip.classList.add('is-active'); else chip.classList.remove('is-active'); }
    },

    _setChipOpen: function (kind, open) {
        var id = kind === 'date' ? 'nlDateChip' : kind === 'cat' ? 'nlCatChip' : kind === 'sev' ? 'nlSevChip' : null;
        if (!id) return;
        var chip = document.getElementById(id);
        if (chip) { if (open) chip.classList.add('is-open'); else chip.classList.remove('is-open'); }
    },

    // ---- Date sheet: presets + inline tap-tap range calendar ----------------
    // Fully self-contained: the sheet + calendar live in static markup and are
    // never relocated or innerHTML-wiped, so it reopens reliably every time.

    openDateMenu: function () {
        var pop = document.getElementById('nlDatePopup');
        if (!pop) return;
        var self = this;
        // Seed the draft range from the applied range (or a sensible default) so
        // reopening shows what's active. Draft keys are separate from applied so
        // Cancel/backdrop doesn't mutate the live filter.
        this._draftFrom = (this._rangeFrom != null) ? this._dateKey(new Date(this._rangeFrom)) : null;
        this._draftTo = (this._rangeTo != null) ? this._dateKey(new Date(this._rangeTo)) : null;  // first tap sets start, second sets end
        var seed = this._draftFrom || this._dateKey(new Date());
        this._calMonth = new Date(seed + 'T00:00:00');
        this._calMonth.setDate(1);
        this._renderCalendar();
        this._syncDatePresets();
        this._updateDateSummary();
        if (!pop._nlBound) {
            pop._nlBound = true;
            pop.addEventListener('click', function (e) { if (e.target === pop) self.closeDateMenu(); });
        }
        pop.classList.add('active');
        this._setChipOpen('date', true);
    },

    closeDateMenu: function () {
        var pop = document.getElementById('nlDatePopup');
        if (pop) pop.classList.remove('active');
        this._setChipOpen('date', false);
        // Clear the draft so it never lingers between opens; the next open
        // re-seeds it from the applied range.
        this._draftFrom = null;
        this._draftTo = null;
    },

    // Preset tap = immediate apply + close (no calendar interaction needed).
    pickDatePreset: function (days) {
        this.currentDays = days;
        this._rangeFrom = null;
        this._rangeTo = null;
        this.closeDateMenu();
        this._syncChips();
        this.reload();
    },

    _syncDatePresets: function () {
        var btns = document.querySelectorAll('#nlDatePresets .nl-date-preset');
        // A preset highlights only when NO custom range is active AND no draft
        // range is in progress; -1 = custom (no preset highlighted).
        var activeDays = (this._rangeFrom == null && !this._draftFrom) ? (this.currentDays || 0) : -1;
        for (var i = 0; i < btns.length; i++) {
            var d = parseInt(btns[i].getAttribute('data-days'), 10);
            if (d === activeDays) btns[i].classList.add('active');
            else btns[i].classList.remove('active');
        }
    },

    calPrevMonth: function () { this._calMonth.setMonth(this._calMonth.getMonth() - 1); this._renderCalendar(); },
    calNextMonth: function () { this._calMonth.setMonth(this._calMonth.getMonth() + 1); this._renderCalendar(); },

    _renderCalendar: function () {
        var grid = document.getElementById('nlCalendarGrid');
        var title = document.getElementById('nlCalendarTitle');
        if (!grid || !this._calMonth) return;
        var lang = (window.BYD && BYD.i18n && BYD.i18n.getLang) ? BYD.i18n.getLang() : undefined;
        var year = this._calMonth.getFullYear(), month = this._calMonth.getMonth();
        var monthDate = new Date(year, month, 1);
        try { title.textContent = new Intl.DateTimeFormat(lang, { month: 'long' }).format(monthDate) + ' ' + year; }
        catch (e) { title.textContent = monthDate.toLocaleDateString(lang, { month: 'long' }) + ' ' + year; }
        grid.innerHTML = '';

        var wkFmt; try { wkFmt = new Intl.DateTimeFormat(lang, { weekday: 'short' }); } catch (e) { wkFmt = null; }
        for (var w = 0; w < 7; w++) {
            var dd = new Date(2024, 0, 7 + w);
            var el = document.createElement('div');
            el.className = 'calendar-weekday';
            el.textContent = wkFmt ? wkFmt.format(dd) : dd.toLocaleDateString(lang, { weekday: 'short' });
            grid.appendChild(el);
        }
        var firstDay = new Date(year, month, 1).getDay();
        var daysInMonth = new Date(year, month + 1, 0).getDate();
        var daysInPrev = new Date(year, month, 0).getDate();
        var todayKey = this._dateKey(new Date());
        for (var i = firstDay - 1; i >= 0; i--) this._calDayCell(grid, daysInPrev - i, this._dateKey(new Date(year, month - 1, daysInPrev - i)), true, todayKey);
        for (var day = 1; day <= daysInMonth; day++) this._calDayCell(grid, day, this._dateKey(new Date(year, month, day)), false, todayKey);
        // Pad the trailing week(s) to a fixed 6-row grid (42 day cells) so every
        // month renders a clean rectangle. Snapshot the count FIRST — appending
        // in the loop condition would move the target as we add cells.
        var emitted = firstDay + daysInMonth;            // leading + current day cells
        var trailing = 42 - emitted;                      // fill to 6 rows
        for (var d2 = 1; d2 <= trailing; d2++) this._calDayCell(grid, d2, this._dateKey(new Date(year, month + 1, d2)), true, todayKey);
    },

    _calDayCell: function (grid, day, dateKey, otherMonth, todayKey) {
        var self = this;
        var el = document.createElement('div');
        el.className = 'calendar-day';
        el.textContent = day;
        el.setAttribute('data-date', dateKey);
        if (otherMonth) el.classList.add('other-month');
        if (dateKey === todayKey) el.classList.add('today');
        if (dateKey === this._draftFrom || dateKey === this._draftTo) el.classList.add('selected');
        else if (this._draftFrom && this._draftTo && dateKey > this._draftFrom && dateKey < this._draftTo) el.classList.add('in-range');
        var today = new Date(); today.setHours(0, 0, 0, 0);
        if (new Date(dateKey + 'T00:00:00') > today) el.classList.add('disabled');
        else el.addEventListener('click', function () { self._calPick(dateKey); });
        grid.appendChild(el);
    },

    // Tap-tap range selection: 1st tap = start (clears end), 2nd tap = end
    // (auto-orders). A 3rd tap starts a fresh range. Stays in the sheet — no
    // nested popup, no close on pick.
    _calPick: function (dateKey) {
        if (this._draftFrom == null || this._draftTo != null) {
            // Start a new range.
            this._draftFrom = dateKey;
            this._draftTo = null;
        } else {
            // Complete the range; order it.
            if (dateKey < this._draftFrom) { this._draftTo = this._draftFrom; this._draftFrom = dateKey; }
            else { this._draftTo = dateKey; }
        }
        this._renderCalendar();
        this._syncDatePresets();
        this._updateDateSummary();
    },

    _updateDateSummary: function () {
        var el = document.getElementById('nlDateSummary');
        var apply = document.getElementById('nlDateApply');
        var lang = (window.BYD && BYD.i18n && BYD.i18n.getLang) ? BYD.i18n.getLang() : undefined;
        var self = this;
        function fmt(key) {
            try { return new Date(key + 'T00:00:00').toLocaleDateString(lang, { month: 'short', day: 'numeric', year: 'numeric' }); }
            catch (e) { return key; }
        }
        var txt, ready;
        if (this._draftFrom && this._draftTo) { txt = fmt(this._draftFrom) + '  –  ' + fmt(this._draftTo); ready = true; }
        else if (this._draftFrom) { txt = fmt(this._draftFrom) + '  –  …'; ready = true; }
        else { txt = this._t('notiflog.range_pick', 'Pick a start or end date'); ready = false; }
        if (el) el.textContent = txt;
        if (apply) apply.disabled = !ready;
    },

    // Apply the draft range as the live filter (To defaults to From when only
    // one endpoint chosen = single-day range).
    applyCustomRange: function () {
        var fromKey = this._draftFrom;
        var toKey = this._draftTo || this._draftFrom;
        if (!fromKey) {
            this._toast(this._t('notiflog.range_pick', 'Pick a start or end date'), 'error');
            return;
        }
        this._rangeFrom = this._keyToMs(fromKey, false);
        this._rangeTo = this._keyToMs(toKey, true);
        this.currentDays = 0;
        this.closeDateMenu();
        this._syncChips();
        this.reload();
    },

    _dateKey: function (d) {
        var m = d.getMonth() + 1, day = d.getDate();
        return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
    },
    _keyToMs: function (key, endOfDay) {
        var p = key.split('-');
        if (p.length !== 3) return null;
        var y = parseInt(p[0], 10), mo = parseInt(p[1], 10) - 1, da = parseInt(p[2], 10);
        if (isNaN(y) || isNaN(mo) || isNaN(da)) return null;
        return (endOfDay ? new Date(y, mo, da, 23, 59, 59, 999) : new Date(y, mo, da, 0, 0, 0, 0)).getTime();
    },

    // ==================== PAGINATION (events-style Prev / Next) ====================

    _updatePagination: function () {
        var bar = document.getElementById('nlPagination');
        var prev = document.getElementById('nlPrevBtn');
        var next = document.getElementById('nlNextBtn');
        var info = document.getElementById('nlPageInfo');
        if (!bar) return;
        if (this.totalPages <= 1) { bar.style.display = 'none'; return; }
        bar.style.display = 'flex';
        if (prev) prev.disabled = this.currentPage <= 1;
        if (next) next.disabled = this.currentPage >= this.totalPages;
        if (info) {
            var tmpl = this._t('notiflog.page_of', 'Page {page} of {total}');
            info.textContent = tmpl.replace('{page}', this.currentPage).replace('{total}', this.totalPages);
        }
    },

    prevPage: function () {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.load();
            this._scrollTop();
        }
    },
    nextPage: function () {
        if (this.currentPage < this.totalPages) {
            this.currentPage++;
            this.load();
            this._scrollTop();
        }
    },
    _scrollTop: function () {
        var list = document.getElementById('nlList');
        if (list) list.scrollTop = 0;
        if (window.scrollTo) window.scrollTo(0, 0);
    },

    // ==================== DELETE ====================

    deleteOne: function (id) {
        var self = this;
        BYD.utils.confirmDialog({
            title: this._t('notiflog.delete_title', 'Delete notification?'),
            body: this._t('notiflog.delete_body', 'This removes it from the log. It cannot be undone.'),
            confirmLabel: this._t('notiflog.delete', 'Delete'),
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            // POST fallback path — the in-app WebView can drop DELETE bodies/methods.
            BYDAuth.fetch('/api/notifications/log/' + id + '/delete', { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function (d) {
                    if (d && d.success) {
                        self._toast(self._t('notiflog.deleted', 'Notification deleted'));
                        self._removeLocal([id]);
                    } else {
                        self._toast(self._t('notiflog.delete_failed', 'Could not delete'), 'error');
                    }
                })
                .catch(function () { self._toast(self._t('notiflog.delete_failed', 'Could not delete'), 'error'); });
        });
    },

    // ---- Bulk selection ----
    toggleSelectMode: function () {
        this.selectMode = !this.selectMode;
        this.selected = {};
        this._render();
        this._updateSelectToggle();
    },

    _toggleSelect: function (id, on) {
        if (on) this.selected[id] = true;
        else delete this.selected[id];
        this._updateBulkBar();
    },

    selectAllOnPage: function () {
        for (var i = 0; i < this.notifications.length; i++) this.selected[this.notifications[i].id] = true;
        this._render();
    },
    clearSelection: function () {
        this.selected = {};
        this._render();
    },

    _selectedIds: function () {
        var ids = [];
        for (var k in this.selected) { if (this.selected.hasOwnProperty(k)) ids.push(parseInt(k, 10)); }
        return ids;
    },

    deleteSelected: function () {
        var self = this;
        var ids = this._selectedIds();
        if (!ids.length) return;
        var tmpl = this._t('notiflog.bulk_confirm_body', 'Delete {n} selected notifications? This cannot be undone.');
        BYD.utils.confirmDialog({
            title: this._t('notiflog.bulk_confirm_title', 'Delete selected?'),
            body: tmpl.replace('{n}', ids.length),
            confirmLabel: this._t('notiflog.delete', 'Delete'),
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            BYDAuth.fetch('/api/notifications/log/bulk-delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ids: ids })
            }).then(function (r) { return r.json(); })
                .then(function (d) {
                    if (d && d.success) {
                        self._toast(self._t('notiflog.bulk_deleted', '{n} deleted').replace('{n}', d.removed != null ? d.removed : ids.length));
                        self.selected = {};
                        self._removeLocal(ids);
                    } else {
                        self._toast(self._t('notiflog.delete_failed', 'Could not delete'), 'error');
                    }
                })
                .catch(function () { self._toast(self._t('notiflog.delete_failed', 'Could not delete'), 'error'); });
        });
    },

    clearAll: function () {
        var self = this;
        BYD.utils.confirmDialog({
            title: this._t('notiflog.clear_title', 'Clear all notifications?'),
            body: this._t('notiflog.clear_body', 'This permanently deletes the entire notification log.'),
            confirmLabel: this._t('notiflog.clear_all', 'Clear all'),
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            // POST fallback for the WebView (mirrors charging history clear).
            BYDAuth.fetch('/api/notifications/log/clear', { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function (d) {
                    if (d && d.success) {
                        self._toast(self._t('notiflog.cleared', 'Notification log cleared'));
                        self.selected = {};
                        self.selectMode = false;
                        self._updateSelectToggle();
                        self.reload();
                    } else {
                        self._toast(self._t('notiflog.delete_failed', 'Could not delete'), 'error');
                    }
                })
                .catch(function () { self._toast(self._t('notiflog.delete_failed', 'Could not delete'), 'error'); });
        });
    },

    // Remove rows locally for an instant update, then refresh the page so
    // totals / pagination stay honest (and a now-empty page falls back a page).
    _removeLocal: function (ids) {
        var drop = {};
        for (var i = 0; i < ids.length; i++) drop[ids[i]] = true;
        var kept = [];
        for (var j = 0; j < this.notifications.length; j++) {
            if (!drop[this.notifications[j].id]) kept.push(this.notifications[j]);
        }
        var removedCount = this.notifications.length - kept.length;
        this.notifications = kept;
        for (var k = 0; k < ids.length; k++) delete this.selected[ids[k]];
        // Keep the count pill honest until the reload reconciles it.
        this.total = Math.max(0, this.total - removedCount);
        // If the page emptied out and we're past page 1, step back a page.
        if (this.notifications.length === 0 && this.currentPage > 1) this.currentPage--;
        // Repaint immediately so the deleted card(s) vanish at once, then reload
        // to reconcile total/pagination with the server.
        this._render();
        this.load();
    },

    _updateSelectToggle: function () {
        var btn = document.getElementById('nlSelectToggle');
        if (btn) {
            btn.classList.toggle('active', this.selectMode);
            var lbl = btn.querySelector('.nl-tool-label');
            if (lbl) lbl.textContent = this.selectMode
                ? this._t('notiflog.done', 'Done')
                : this._t('notiflog.select', 'Select');
        }
        var single = document.getElementById('nlClearAllBtn');
        if (single) single.style.display = this.selectMode ? 'none' : '';
    },

    _updateBulkBar: function () {
        var bar = document.getElementById('nlBulkBar');
        if (!bar) return;
        if (!this.selectMode) { bar.style.display = 'none'; return; }
        bar.style.display = 'flex';
        var count = this._selectedIds().length;
        var del = document.getElementById('nlBulkDeleteBtn');
        if (del) del.disabled = count === 0;
        var cnt = document.getElementById('nlBulkCount');
        if (cnt) {
            cnt.textContent = this._t('notiflog.n_selected', '{n} selected').replace('{n}', count);
        }
    },

    // ==================== HELPERS ====================

    _categoryMeta: function (catId) {
        if (!catId || !this.categories) return null;
        for (var i = 0; i < this.categories.length; i++) {
            if (this.categories[i] && this.categories[i].id === catId) return this.categories[i];
        }
        return null;
    },

    _groupLabel: function (group) {
        // Registry groups are already human ("Surveillance"/"Vehicle"/...).
        return group || this._t('notiflog.group_other', 'Other');
    },

    _sevLabel: function (sev) {
        if (sev === 'critical') return this._t('notiflog.sev_critical', 'Critical');
        if (sev === 'warn') return this._t('notiflog.sev_warn', 'Alert');
        return this._t('notiflog.sev_info', 'Info');
    },

    // Group -> inline SVG icon (stroke uses currentColor; severity color set on
    // the .nl-icon wrapper).
    _categoryIcon: function (group) {
        switch (group) {
            case 'Surveillance':
                return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>';
            case 'Charging':
                return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 L4 14 h7 l-1 8 9-12 h-7 z"/></svg>';
            case 'Security':
                return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>';
            case 'Vehicle':
                return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 16.5a2.5 2.5 0 0 1-5 0m10-3.5H5l1.5-6h11L19 13Zm-2 0v3m-10-3v3"/><circle cx="7" cy="16.5" r="2"/><circle cx="17" cy="16.5" r="2"/></svg>';
            default:
                return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>';
        }
    },

    // Actor / detail chips from the stored data blob (surveillance/proximity).
    _actorChips: function (n) {
        if (!n || !n.data) return '';
        var self = this;
        var d = n.data, out = '';
        function chip(label) { return '<span class="nl-chip">' + label + '</span>'; }
        // Counts are daemon integers today; coerce to int + escape as defense-
        // in-depth so a malformed value can never inject markup.
        function num(v) { var i = parseInt(v, 10); return isNaN(i) ? 0 : i; }
        if (d.personCount) out += chip(num(d.personCount) + ' ' + self._esc(this._t('notiflog.person', 'person')));
        if (d.vehicleCount) out += chip(num(d.vehicleCount) + ' ' + self._esc(this._t('notiflog.vehicle', 'vehicle')));
        if (d.bikeCount) out += chip(num(d.bikeCount) + ' ' + self._esc(this._t('notiflog.bike', 'bike')));
        if (d.animalCount) out += chip(num(d.animalCount) + ' ' + self._esc(this._t('notiflog.animal', 'animal')));
        if (d.closestProximity) {
            var p = String(d.closestProximity).toUpperCase();
            var cls = (p === 'VERY_CLOSE') ? 'prox-veryclose' : (p === 'CLOSE') ? 'prox-close' : 'prox-far';
            out += '<span class="nl-chip ' + cls + '">' + this._esc(this._proxLabel(p)) + '</span>';
        }
        return out;
    },

    _proxLabel: function (p) {
        if (p === 'VERY_CLOSE') return this._t('notiflog.prox_very_close', 'Very close');
        if (p === 'CLOSE') return this._t('notiflog.prox_close', 'Close');
        if (p === 'MID') return this._t('notiflog.prox_mid', 'Mid');
        return this._t('notiflog.prox_far', 'Far');
    },

    _fmtTime: function (ts) {
        if (!ts) return '';
        var lang = (window.BYD && BYD.i18n && BYD.i18n.getLang) ? BYD.i18n.getLang() : undefined;
        var d = new Date(ts);
        var now = new Date();
        var sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
        try {
            if (sameDay) {
                return d.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });
            }
            return d.toLocaleDateString(lang, { month: 'short', day: 'numeric' }) + ' · ' +
                   d.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });
        } catch (e) {
            return d.toLocaleString();
        }
    },

    _showSkeleton: function () {
        var sk = document.getElementById('nlSkeleton');
        if (sk) sk.style.display = '';
        var list = document.getElementById('nlList');
        if (list && !this.notifications.length) list.innerHTML = '';
    },
    _hideSkeleton: function () {
        var sk = document.getElementById('nlSkeleton');
        if (sk) sk.style.display = 'none';
    },

    _esc: function (str) {
        if (str == null) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    },
    _t: function (key, fallback) {
        if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
            var v = BYD.i18n.t(key);
            if (v && v !== key) return v;
        }
        return fallback;
    },
    _toast: function (msg, type) {
        if (window.BYD && BYD.utils && typeof BYD.utils.toast === 'function') BYD.utils.toast(msg, type || 'success');
    }
};

if (typeof window !== 'undefined') window.NOTIFLOG = NOTIFLOG;
