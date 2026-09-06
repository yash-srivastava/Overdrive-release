/**
 * OverDrive - Charging Analytics Module
 *
 * Mirrors the Trips pattern (trips.js): a session-card list + a per-session
 * detail drill-in, plus a stats tab with hand-rolled Canvas2D charts.
 *
 * WebView compatibility (head-unit is Chrome 58 on some pages):
 *   - No optional chaining (?.) / nullish (??) — both are Chrome 80+.
 *   - No ctx.roundRect direct calls — _roundRectPath() polyfills it (Chrome 99+).
 *   - No Array.flat()/flatMap() (Chrome 69+).
 *   - POST/DELETE bodies go through fetch() (the in-app WebView drops XHR write
 *     bodies — see project memory "WebView XHR POST dropped").
 * All charts are pure Canvas2D, DPR-scaled, and re-painted on theme flip.
 */

var CHARGING = {
    // ---- State ----
    currentOffset: 0,
    currentDays: 7,
    // Custom date range (epoch-ms). When _rangeFrom != null the session list +
    // period summary query by range instead of currentDays. _rangeFrom/_rangeTo
    // null = use currentDays (0 = all time).
    _rangeFrom: null,
    _rangeTo: null,
    // Calendar range-picker state.
    _calTarget: 'from',       // which endpoint the open calendar edits
    _calMonth: null,          // Date pinned to the 1st of the displayed month
    _calFromKey: null,        // "YYYY-MM-DD" selected From, or null
    _calToKey: null,          // "YYYY-MM-DD" selected To, or null
    pageSize: 20,
    sessions: [],
    sortOrder: 'recent',
    currentSessionId: null,
    _detailSessionId: null,
    _detailGeneration: 0,
    _detailInProgress: false,
    _currentDetailSession: null,
    samplesCache: null,       // samples for the open detail session
    socHistoryCache: null,    // SoC-over-time series
    _socCacheHours: null,
    summaryCache: null,
    _summaryPeriodKey: null,
    _liveSession: null,       // the open in-progress session row, if any
    electricityRate: 0,
    currency: '$',
    dcRate: 0,
    // Location-aware tariffs. `matchedTariffId` is the one that would price a
    // charge started at the CURRENT position (server-computed, so the UI can't
    // disagree with what pricing will actually do); `defaultTariffId` is the
    // pinned fallback for charges that match no circle.
    tariffs: [],
    defaultTariffId: '',
    matchedTariffId: '',
    maxTariffs: 40,
    tariffGpsLat: null,
    tariffGpsLng: null,
    _editingTariff: null,
    // Default match radius for a new tariff, in metres. Mirrors
    // TariffProfile.DEFAULT_RADIUS_M — tight enough to mean "this charger"
    // rather than "this neighbourhood", while covering normal GPS scatter.
    TARIFF_DEFAULT_RADIUS_M: 50,
    fastSampleSec: 12,
    isPhev: false,
    nominalKwh: 0,
    _writing: false,          // true while a settings save is in-flight (gates revisit refresh)
    _configWriting: false,
    _configBaseline: null,
    _configDirty: {},
    _configGeneration: 0,
    _configSaveGeneration: 0,
    _bootstrapGeneration: 0,
    _summaryGeneration: 0,
    _socGeneration: 0,
    _sessionsGeneration: 0,
    _tariffsGeneration: 0,
    _sessionsRequestSerial: 0,
    _sessionsPeriodKey: null,
    _sessionsLoadMorePending: false,
    _liveRefreshTimer: null,
    _liveRefreshInFlight: null,
    _liveRefreshGeneration: 0,
    _liveLoadGeneration: 0,
    LIVE_REFRESH_INTERVAL_MS: 15000,
    _socGeom: null,           // cached SoC-chart geometry for hover hit-testing
    _socHoverIdx: null,       // active hovered sample index (null = no crosshair)
    // Per-session detail-chart hover (power / ramp / temp) keeps its state on
    // each canvas element (canvas._chgHoverSpec / _chgHoverIdx), so no shared
    // field here — multiple detail charts hover independently.
    socHours: 168,            // SoC chart window in hours (period selector; default 7d)
    // Canvas palette — dark defaults, replaced by _refreshPalette() reading the
    // --chart-* CSS variables on theme flip (same pattern as trips.js).
    colors: {
        brand: '#0EA5E9',
        brandRgba: 'rgba(14, 165, 233, 0.22)',
        accent: '#00D4AA',
        amber: '#F59E0B',
        danger: '#EF4444',
        good: '#22C55E',
        text: 'rgba(255, 255, 255, 0.7)',
        textMuted: 'rgba(255, 255, 255, 0.5)',
        textStrong: '#FFFFFF',
        grid: 'rgba(255, 255, 255, 0.08)',
        dotStroke: '#0F0F12',
        arcTrack: 'rgba(255, 255, 255, 0.06)'
    },

    // ==================== INIT / THEME ====================

    init: function () {
        this._refreshPalette();
        this._setupThemeObserver();
        // Establish a first-paint baseline before any asynchronous config
        // response. An edit made while bootstrap is pending is then recognized
        // as dirty and cannot be overwritten by that older response.
        this._configBaseline = this._readConfigForm();
        this._refreshConfigDirty();

        // Hide the detail view when the tab bar switches (mirrors trips).
        var self = this;
        document.addEventListener('ot-tabs:active-changed', function (ev) {
            self.hideDetail();
            // Canvas charts on a display:none tab render at a degenerate size
            // (offsetParent is null → _renderSummaryCharts skips them). They were
            // painted once on first load while the Stats tab was hidden, so the
            // SOH/cost/energy charts stayed blank until a reload landed ON Stats.
            // Repaint when Stats becomes active. Defer a tick so layout settles.
            var id = (ev && ev.detail) ? ev.detail.id : null;
            self._syncTabPresentation(id);
            if (id === 'stats') {
                setTimeout(function () {
                    if (self.summaryCache
                            && self._summaryPeriodKey === self._periodKey()) {
                        self._renderSummaryCharts(self.summaryCache);
                    }
                    if (self.socHistoryCache
                            && self._socCacheHours === self.socHours) {
                        var c = document.getElementById('socChart');
                        if (c) self.renderSocOverTime(c, self.socHistoryCache);
                    }
                }, 0);
            }
        });

        // Some old head-unit WebViews switch the shared tab without forwarding
        // the custom event above. Keep the visible page title in sync on click.
        document.addEventListener('click', function (ev) {
            var tab = ev.target;
            while (tab && tab !== document
                    && !(tab.classList
                    && tab.classList.contains('bottom-tab'))) {
                tab = tab.parentNode;
            }
            if (tab && tab !== document) {
                var tabId = tab.getAttribute('data-tab-target');
                if (tabId) {
                    setTimeout(function () {
                        self._syncTabPresentation(tabId);
                    }, 0);
                }
            }
        });

        // Close the calendar popup when its backdrop is tapped (mirrors events).
        var calPop = document.getElementById('chargeCalendarPopup');
        if (calPop) calPop.addEventListener('click', function (e) {
            if (e.target === calPop) self.closeCalendar();
        });

        // Re-sync when the user navigates back to this page. The native shell
        // (WebViewFragment) keeps the page ALIVE across tab switches — it does
        // NOT reload — so without this, a rate/currency saved on the Trips page
        // (shared value) or a toggle saved here never re-loads and the settings
        // show stale first-paint values. Mirrors core.js/road-sense.js. Guarded
        // by _writing so it can't clobber an in-flight save.
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'hidden') self._stopVisibleRefresh();
            else self._restartVisibleRefresh(true);
        });

        this._showSkeleton();
        setTimeout(function () { self._syncTabPresentation(); }, 0);
        this._restartVisibleRefresh(true);
    },

    _syncTabPresentation: function (activeId) {
        var id = activeId;
        if (!id) {
            var active = document.querySelector('.bottom-tab.is-active');
            if (active) id = active.getAttribute('data-tab-target');
        }
        var title = this._t('charge.title', 'Charging');
        if (id === 'stats') title = this._t('charge.tab_stats', 'Stats');
        else if (id === 'settings') {
            title = this._t('charge.tab_settings', 'Settings');
        }
        this._setText('chargingPageTitle', title);
        this._setText('chargingMobileTitle', title);
        if (window.AndroidBridge
                && typeof window.AndroidBridge.setPageTitle === 'function') {
            try {
                window.AndroidBridge.setPageTitle(title);
            } catch (e) { /* standalone web */ }
        }
    },

    _refreshPalette: function () {
        try {
            var s = getComputedStyle(document.documentElement);
            var pick = function (name, fallback) {
                var v = (s.getPropertyValue(name) || '').trim();
                return v || fallback;
            };
            this.colors.brand      = pick('--brand-primary',     this.colors.brand);
            this.colors.brandRgba  = this._rgba(this.colors.brand, 0.22);
            this.colors.text       = pick('--chart-text',        this.colors.text);
            this.colors.textStrong = pick('--chart-text-strong', this.colors.textStrong);
            this.colors.grid       = pick('--chart-grid',        this.colors.grid);
            this.colors.dotStroke  = pick('--bg-base',           this.colors.dotStroke);
            this.colors.arcTrack   = pick('--border-subtle',     this.colors.arcTrack);
            this.colors.textMuted  = this.colors.text;
        } catch (e) { /* keep dark defaults */ }
    },

    _setupThemeObserver: function () {
        if (this._themeObserver) return;
        var self = this;
        try {
            this._themeObserver = new MutationObserver(function () {
                self._refreshPalette();
                self._repaintAll();
            });
            this._themeObserver.observe(document.documentElement, {
                attributes: true,
                attributeFilter: ['data-theme']
            });
        } catch (e) { /* MutationObserver unsupported — skip live repaint */ }
    },

    _repaintAll: function () {
        var self = this;
        var tryPaint = function (fn) { try { fn(); } catch (e) {} };
        tryPaint(function () {
            if (self.summaryCache
                    && self._summaryPeriodKey === self._periodKey()) {
                var live = self.summaryCache.live || {};
                var soc = live.socPercent != null
                    && live.socPercent >= 0 ? live.socPercent : 0;
                self.renderCircleGauge(
                    'socCircleCanvas', soc, self.colors.brand);
            }
        });
        tryPaint(function () {
            if (self.socHistoryCache
                    && self._socCacheHours === self.socHours) {
                var c = document.getElementById('socChart');
                if (c) self.renderSocOverTime(c, self.socHistoryCache);
            }
        });
        tryPaint(function () {
            if (self.summaryCache
                    && self._summaryPeriodKey === self._periodKey()) {
                self._renderSummaryCharts(self.summaryCache);
            }
        });
        tryPaint(function () {
            if (self.currentSessionId && self.samplesCache) self._renderDetailCharts(self.samplesCache);
        });
    },

    // ==================== DATA LOADING ====================

    _fetchJson: function (url, options) {
        return fetch(url, options).then(function (response) {
            if (!response.ok) throw new Error(url + ' ' + response.status);
            return response.json();
        });
    },

    _payload: function (data, key, expectArray, requireSuccess) {
        if (!data || data.error || data.success === false) return null;
        if (requireSuccess && data.success !== true) return null;
        if (!Object.prototype.hasOwnProperty.call(data, key)) return null;
        var value = data[key];
        if (value == null || value.error || value.success === false) return null;
        if (expectArray) return Array.isArray(value) ? value : null;
        return (typeof value === 'object' && !Array.isArray(value)) ? value : null;
    },

    _periodKey: function () {
        return this._periodQuery();
    },

    _bootstrapUrl: function (periodKey, hours) {
        return '/api/charging/bootstrap?' + periodKey
            + '&hours=' + hours
            + '&points=300&limit=' + this.pageSize;
    },

    _pageIsVisible: function () {
        var state = document.visibilityState;
        return !state || state === 'visible';
    },

    _stopVisibleRefresh: function () {
        this._liveRefreshGeneration++;
        this._liveLoadGeneration++;
        if (this._liveRefreshTimer != null) {
            clearTimeout(this._liveRefreshTimer);
            this._liveRefreshTimer = null;
        }
        this._liveRefreshInFlight = null;
        // Invalidate every first-paint/live response that could otherwise land
        // after a hidden -> visible generation has published newer state.
        this._bootstrapGeneration++;
        this._configGeneration++;
        this._summaryGeneration++;
        this._socGeneration++;
        this._sessionsGeneration++;
        this._tariffsGeneration++;
        this._sessionsLoadMorePending = false;
    },

    _restartVisibleRefresh: function (useBootstrap) {
        this._stopVisibleRefresh();
        if (!this._pageIsVisible()) return Promise.resolve();
        var generation = this._liveRefreshGeneration;
        if (this._writing) {
            this._scheduleVisibleRefresh(generation, 1000);
            return Promise.resolve();
        }
        var request = useBootstrap
            ? this.bootstrap()
            : this._loadCurrentLivePair();
        return this._trackVisibleRefresh(request, generation);
    },

    _trackVisibleRefresh: function (request, generation) {
        var self = this;
        if (!request || typeof request.then !== 'function') {
            request = Promise.resolve();
        }
        this._liveRefreshInFlight = request;
        var settled = function () {
            if (generation !== self._liveRefreshGeneration
                    || self._liveRefreshInFlight !== request) return;
            self._liveRefreshInFlight = null;
            self._scheduleVisibleRefresh(
                generation, self.LIVE_REFRESH_INTERVAL_MS);
        };
        request.then(settled, settled);
        return request;
    },

    _scheduleVisibleRefresh: function (generation, delayMs) {
        var self = this;
        if (generation !== this._liveRefreshGeneration
                || !this._pageIsVisible()) return;
        if (this._liveRefreshTimer != null) {
            clearTimeout(this._liveRefreshTimer);
        }
        this._liveRefreshTimer = setTimeout(function () {
            self._liveRefreshTimer = null;
            self._runPeriodicRefresh(generation);
        }, delayMs);
    },

    _runPeriodicRefresh: function (generation) {
        if (generation !== this._liveRefreshGeneration
                || !this._pageIsVisible()) {
            return Promise.resolve();
        }
        if (this._liveRefreshInFlight) return this._liveRefreshInFlight;
        if (this._writing) {
            this._scheduleVisibleRefresh(generation, 1000);
            return Promise.resolve();
        }
        return this._trackVisibleRefresh(
            this._loadCurrentLivePair(),
            generation);
    },

    _loadCurrentLivePair: function () {
        var self = this;
        var generation = ++this._liveLoadGeneration;
        var periodKey = this._periodKey();
        var summaryGeneration = ++this._summaryGeneration;
        var sessionsGeneration = ++this._sessionsGeneration;
        this._sessionsPeriodKey = periodKey;
        this._sessionsLoadMorePending = false;
        var url = '/api/charging/overview?' + periodKey
            + '&limit=' + this.pageSize;
        return this._fetchJson(url).then(function (d) {
            if (generation !== self._liveLoadGeneration
                    || summaryGeneration !== self._summaryGeneration
                    || sessionsGeneration !== self._sessionsGeneration
                    || periodKey !== self._periodKey()
                    || periodKey !== self._sessionsPeriodKey) return null;
            var summary = self._payload(
                d, 'summary', false, true);
            var sessions = self._payload(
                d, 'sessions', true, true);
            if (summary === null || sessions === null) {
                throw new Error('invalid overview payload');
            }
            // Both payloads were parsed and generation-checked before either is
            // published, so one browser task installs one coherent live view.
            self._applySummary(summary, periodKey);
            self._applySessions(sessions, 0);
            return true;
        }).catch(function () {
            if (generation !== self._liveLoadGeneration
                    || summaryGeneration !== self._summaryGeneration
                    || sessionsGeneration !== self._sessionsGeneration
                    || periodKey !== self._periodKey()
                    || periodKey !== self._sessionsPeriodKey) return null;
            self._hideSkeleton();
            self._failCloseLivePresentation();
            return false;
        });
    },

    _failCloseLivePresentation: function () {
        this._liveSession = null;
        if (this.summaryCache) {
            this.summaryCache.live = {
                charging: false,
                plugged: false,
                full: false,
                fault: false,
                powerKw: 0,
                isEstimated: false,
                powerSource: 'none',
                powerObservedAtMs: 0,
                powerQuality: 'UNKNOWN',
                powerConfidence: 0,
                socPercent: null,
                sessionKwh: null,
                sessionEnergyIncomplete: false,
                sessionEnergyEstimated: false,
                sessionEnergySource: null,
                timeToFullMin: null
            };
            // Force only the rendering pass when the visible period changed while this request was
            // in flight. The cache key remains untouched, so old totals cannot masquerade as the new
            // period, but any live additions from that cache are removed immediately.
            this._applySummary(
                this.summaryCache, this._summaryPeriodKey, true);
        }

        var detailRow = null;
        for (var i = 0; i < this.sessions.length; i++) {
            var row = this.sessions[i];
            if (!row || row.inProgress !== true) continue;
            row.chargingNow = false;
            row.livePowerKw = null;
            row.timeToFullMin = null;
            row.isEstimated = false;
            if (this.currentSessionId != null
                    && String(row.id) === String(this.currentSessionId)) {
                detailRow = row;
            }
        }
        this._renderSessionCards();
        if (detailRow) {
            this._fillDetailHeader(detailRow, detailRow.id);
        }
    },

    bootstrap: function () {
        var self = this;
        var periodKey = this._periodKey();
        var hours = this.socHours || 168;
        var bootstrapGeneration = ++this._bootstrapGeneration;
        var liveLoadGeneration = ++this._liveLoadGeneration;
        var configGeneration = ++this._configGeneration;
        var summaryGeneration = ++this._summaryGeneration;
        var socGeneration = ++this._socGeneration;
        var sessionsGeneration = ++this._sessionsGeneration;
        var tariffsGeneration = ++this._tariffsGeneration;
        this._sessionsPeriodKey = periodKey;
        this._sessionsLoadMorePending = false;
        // Single composite call; fall back to the sequential loaders if the
        // bootstrap endpoint is unavailable (older daemon).
        return this._fetchJson(this._bootstrapUrl(periodKey, hours)).then(function (data) {
            if (bootstrapGeneration !== self._bootstrapGeneration
                    || liveLoadGeneration !== self._liveLoadGeneration) return;
            var b = (data && data.bootstrap) ? data.bootstrap : null;
            if (!b || data.success !== true || data.error) {
                throw new Error('no bootstrap payload');
            }
            // Each bootstrap section keeps its own named wrapper (the server
            // builds them from the same per-section handlers used by the
            // sequential loaders, stripping only success/_status). So unwrap the
            // same way the loaders do. Error sections are deliberately ignored
            // so a transient read failure cannot erase the last rendered state.
            var config = self._payload(b.config, 'config', false, false);
            var summary = self._payload(b.summary, 'summary', false, false);
            var soc = self._payload(b.soc, 'soc', true, false);
            var sessions = self._payload(b.sessions, 'sessions', true, false);
            var followups = [];
            if (configGeneration === self._configGeneration) {
                if (config !== null) self._applyConfig(config);
                else followups.push(self.loadConfig());
            }
            if (summaryGeneration === self._summaryGeneration
                    && periodKey === self._periodKey()) {
                if (summary !== null) {
                    self._applySummary(summary, periodKey);
                }
            }
            if (socGeneration === self._socGeneration
                    && hours === self.socHours) {
                if (soc !== null) self._applySoc(soc, hours);
                else followups.push(self.loadSoc());
            }
            if (sessionsGeneration === self._sessionsGeneration
                    && periodKey === self._periodKey()
                    && periodKey === self._sessionsPeriodKey) {
                if (sessions !== null) {
                    self._applySessions(sessions, 0);
                } else {
                    // A composite endpoint can succeed while this section
                    // fails. Remove the first-load skeleton immediately.
                    self._hideSkeleton();
                }
            }
            if (summary === null || sessions === null) {
                self._failCloseLivePresentation();
                // Re-read the pair together. Recovering only the failed half could combine a current
                // positive with the other section's older stopped/active state.
                followups.push(self._loadCurrentLivePair());
            }
            // Tariffs ship in the bootstrap on current daemons; an older one
            // omits the section, so fetch it separately rather than leaving the
            // list blank.
            if (tariffsGeneration === self._tariffsGeneration) {
                if (b.tariffs && !b.tariffs.error
                        && b.tariffs.success !== false
                        && Array.isArray(b.tariffs.tariffs)) {
                    self._applyTariffs(b.tariffs);
                } else {
                    followups.push(self.loadTariffs());
                }
            }
            return Promise.all(followups);
        }).catch(function () {
            if (bootstrapGeneration !== self._bootstrapGeneration
                    || liveLoadGeneration !== self._liveLoadGeneration) return;
            // Sequential fallback.
            return Promise.all([
                self.loadConfig(),
                self.loadSoc(),
                self._loadCurrentLivePair(),
                self.loadTariffs()
            ]);
        });
    },

    loadConfig: function () {
        var self = this;
        var generation = ++this._configGeneration;
        return this._fetchJson('/api/charging/config')
            .then(function (d) {
                if (generation !== self._configGeneration) return;
                var config = self._payload(d, 'config', false, true);
                if (config === null) throw new Error('invalid config payload');
                self._applyConfig(config);
            })
            .catch(function () {});
    },

    // Period query params: a custom from/to range when set, else days (0 = all
    // time, which the daemon treats as a 0-epoch lower bound).
    _periodQuery: function () {
        if (this._rangeFrom != null) {
            var q = 'from=' + this._rangeFrom;
            if (this._rangeTo != null) q += '&to=' + this._rangeTo;
            return q;
        }
        return 'days=' + (this.currentDays || 0);
    },

    loadSummary: function () {
        var self = this;
        var periodKey = this._periodKey();
        var generation = ++this._summaryGeneration;
        return this._fetchJson('/api/charging/summary?' + periodKey)
            .then(function (d) {
                if (generation !== self._summaryGeneration
                        || periodKey !== self._periodKey()) return null;
                var summary = self._payload(d, 'summary', false, true);
                if (summary === null) throw new Error('invalid summary payload');
                self._applySummary(summary, periodKey);
                return true;
            })
            .catch(function () {
                return generation === self._summaryGeneration
                        && periodKey === self._periodKey() ? false : null;
            });
    },

    loadSoc: function () {
        var self = this;
        var hours = this.socHours || 168;
        var generation = ++this._socGeneration;
        return this._fetchJson('/api/charging/soc?hours=' + hours + '&points=300')
            .then(function (d) {
                if (generation !== self._socGeneration
                        || hours !== self.socHours) return;
                var soc = self._payload(d, 'soc', true, true);
                if (soc === null) throw new Error('invalid SoC payload');
                self._applySoc(soc, hours);
            })
            .catch(function () {});
    },

    // Stats-tab SoC chart period selector (24h / 7d / 30d).
    socPeriod: function (hours, btn) {
        this.socHours = hours;
        var btns = document.querySelectorAll('#socPeriodTabs .filter-tab');
        for (var i = 0; i < btns.length; i++) btns[i].classList.remove('active');
        if (btn) btn.classList.add('active');
        this._socHoverIdx = null;
        this.loadSoc();
    },

    loadSessions: function (offset) {
        var self = this;
        var periodKey = this._periodKey();
        var generation;
        if (offset === 0) {
            generation = ++this._sessionsGeneration;
            this._sessionsPeriodKey = periodKey;
            this._sessionsLoadMorePending = false;
        } else {
            generation = this._sessionsGeneration;
            if (this._sessionsLoadMorePending
                    || periodKey !== this._sessionsPeriodKey
                    || offset !== this.currentOffset + this.pageSize) {
                return Promise.resolve(null);
            }
            this._sessionsLoadMorePending = true;
        }
        var requestSerial = ++this._sessionsRequestSerial;
        var url = '/api/charging?' + periodKey + '&limit=' + this.pageSize + '&offset=' + offset;
        return this._fetchJson(url)
            .then(function (d) {
                if (generation !== self._sessionsGeneration
                        || periodKey !== self._periodKey()
                        || periodKey !== self._sessionsPeriodKey) return null;
                if (offset > 0
                        && offset !== self.currentOffset + self.pageSize) return null;
                var sessions = self._payload(d, 'sessions', true, true);
                if (sessions === null) throw new Error('invalid sessions payload');
                self._applySessions(sessions, offset);
                return true;
            })
            .catch(function () {
                if (generation === self._sessionsGeneration
                        && periodKey === self._periodKey()
                        && offset === 0) {
                    self._hideSkeleton();
                    return false;
                }
                return null;
            })
            .then(function (result) {
                if (offset > 0
                        && generation === self._sessionsGeneration
                        && requestSerial === self._sessionsRequestSerial) {
                    self._sessionsLoadMorePending = false;
                }
                return result;
            });
    },

    // ==================== APPLY PAYLOADS ====================

    _readConfigForm: function () {
        return {
            enabled: this._getChecked('chargingEnabled'),
            electricityRate: this._getNum('rateInput'),
            dcRate: this._getNum('dcRateInput'),
            currency: this._getStr('currencySelect')
                || this.currency
                || '$'
        };
    },

    _setConfigControl: function (key, value) {
        if (key === 'enabled') {
            this._setVal('chargingEnabled', value, true);
        } else if (key === 'electricityRate') {
            this._setInput('rateInput', value > 0 ? value : '');
        } else if (key === 'dcRate') {
            this._setInput('dcRateInput', value > 0 ? value : '');
        } else if (key === 'currency') {
            this._setInput('currencySelect', value || '$');
        }
    },

    _configValueEqual: function (left, right) {
        if (typeof left === 'number' || typeof right === 'number') {
            return Number(left) === Number(right);
        }
        return left === right;
    },

    _refreshConfigDirty: function () {
        var current = this._readConfigForm();
        var baseline = this._configBaseline || current;
        var keys = ['enabled', 'electricityRate', 'dcRate', 'currency'];
        var dirty = {};
        var any = false;
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (!this._configValueEqual(current[key], baseline[key])) {
                dirty[key] = true;
                any = true;
            }
        }
        this._configDirty = dirty;
        if (!this._configWriting) {
            var btn = document.getElementById('chargingApplyBtn');
            if (btn) {
                btn.disabled = !any;
                btn.textContent = this._t(
                    'common.apply_changes', 'Apply Changes');
            }
        }
        return dirty;
    },

    _dirtyConfigBody: function () {
        this._refreshConfigDirty();
        var current = this._readConfigForm();
        var body = {};
        var keys = ['enabled', 'electricityRate', 'dcRate', 'currency'];
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (this._configDirty[key]) body[key] = current[key];
        }
        return body;
    },

    _applyConfig: function (cfg) {
        if (!cfg) return;
        this._refreshConfigDirty();
        var dirtyBeforeResponse = this._configDirty;
        var incoming = {};
        if (cfg.enabled !== undefined) incoming.enabled = !!cfg.enabled;
        if (cfg.electricityRate !== undefined) {
            this.electricityRate = Number(cfg.electricityRate) || 0;
            incoming.electricityRate = this.electricityRate;
        }
        if (cfg.currency !== undefined) {
            this.currency = cfg.currency || '$';
            incoming.currency = this.currency;
        }
        if (cfg.dcRate !== undefined) {
            this.dcRate = Number(cfg.dcRate) || 0;
            incoming.dcRate = this.dcRate;
        }
        if (cfg.fastSampleSec !== undefined) this.fastSampleSec = cfg.fastSampleSec || 12;
        if (cfg.isPhev !== undefined) this.isPhev = !!cfg.isPhev;
        if (cfg.nominalKwh) this.nominalKwh = cfg.nominalKwh;

        if (!this._configBaseline) {
            this._configBaseline = this._readConfigForm();
        }
        var keys = ['enabled', 'electricityRate', 'dcRate', 'currency'];
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (!Object.prototype.hasOwnProperty.call(incoming, key)) continue;
            this._configBaseline[key] = incoming[key];
            if (!dirtyBeforeResponse[key]) {
                this._setConfigControl(key, incoming[key]);
            }
        }
        this._refreshConfigDirty();

        // If summary arrived before config (sequential fallback can race),
        // re-render the hero so the cost/kWh fallback picks up the rate.
        if (this.summaryCache) {
            this._applySummary(
                this.summaryCache, this._summaryPeriodKey);
        }

        // The fallback note quotes the global rate + currency symbol, and tariff
        // rows fall back to this.currency for a profile with no currency of its
        // own — so both must be redrawn when either changes. Use the note-only
        // path when there are no tariffs, to avoid flashing #tariffEmpty during
        // bootstrap before the tariff payload has landed.
        if ((this.tariffs || []).length) this.renderTariffs();
        else this._renderTariffFallbackNote();
    },

    _applySummary: function (s, periodKey, forceRender) {
        if (!s) return;
        var effectivePeriodKey = periodKey
            || this._summaryPeriodKey
            || this._periodKey();
        if (effectivePeriodKey !== this._periodKey() && !forceRender) return;
        if (!forceRender) {
            this.summaryCache = s;
            this._summaryPeriodKey = effectivePeriodKey;
        }

        // Hero gauges.
        var live = s.live || {};
        var historical = this._latestBatterySnapshot();
        var liveSoc = Number(live.socPercent);
        var hasLiveSoc = live.socPercent !== undefined
            && live.socPercent !== null
            && isFinite(liveSoc)
            && liveSoc >= 0 && liveSoc <= 100;
        var soc = hasLiveSoc ? liveSoc : historical.soc;
        var hasSoc = soc !== null;
        this.renderCircleGauge('socCircleCanvas', soc, this.colors.brand);
        this._setText('socCircleValue', hasSoc
            ? Math.round(soc) + '%' : '--');
        var liveRange = Number(live.rangeKm);
        var hasLiveRange = live.rangeKm !== undefined
            && live.rangeKm !== null
            && isFinite(liveRange)
            && liveRange >= 0;
        var rangeKm = hasLiveRange ? liveRange : historical.range;
        this._setText('socRangeValue', rangeKm !== null
            ? this._dist(rangeKm) : '--');
        var liveSoh = Number(live.sohPercent);
        var hasLiveSoh = live.sohPercent !== undefined
            && live.sohPercent !== null
            && isFinite(liveSoh)
            && liveSoh > 0 && liveSoh <= 100;
        var sohPercent = hasLiveSoh ? liveSoh : historical.soh;
        this._setText('socSohValue', sohPercent !== null
            ? 'SOH ' + Math.round(sohPercent) + '%' : 'SOH --');

        var isCharging = live.charging === true;
        var livePowerEstimated = isCharging
            && live.isEstimated === true
            && live.powerKw != null
            && live.powerKw > 0;
        var liveKwh = (live.sessionKwh != null && live.sessionKwh > 0) ? live.sessionKwh : 0;
        var liveEnergyApproximate = isCharging && liveKwh > 0
            && this._energyIsApproximate(live);
        var periodEnergyApproximate =
            (s.periodEstimatedSessions || s.periodIncompleteSessions || 0) > 0
            || liveEnergyApproximate;
        var lifetimeEnergyApproximate =
            (s.lifetimeEstimatedSessions || s.lifetimeIncompleteSessions || 0) > 0;

        var completion = this._chargingCompletion(live);
        this._showCard('completionHeroCard', !!completion);
        this._setText('completionHeroValue',
            completion ? completion.primary : '--');
        this._setText('completionHeroSub',
            completion ? completion.secondary : '');

        // Prefer trustworthy live power; otherwise derive a duration-weighted
        // average from completed, non-estimated sessions in the visible period.
        var energy = s.periodEnergyKwh || 0;
        var periodPower = this._periodAveragePower(live);
        this._setText('kwhHeroLabel',
            this._t('charge.hero_avg_power', 'Average power'));
        this._setText('kwhHeroValue', periodPower.value > 0
            ? periodPower.value.toFixed(1) : '--');
        this._setText('kwhHeroUnit', 'kW');
        if (periodPower.live) {
            this._setText('kwhHeroSub',
                this._t('charge.power_live', 'Live measured power'));
        } else if (periodPower.value > 0) {
            this._setText('kwhHeroSub',
                this._t('charge.power_period',
                    'Across the selected period'));
        } else {
            this._setText('kwhHeroSub',
                this._t('charge.power_waiting',
                    'Available after a recorded charge'));
        }

        // Cost hero. A flat "cost per kWh" (just the configured rate) carried no
        // information. Show the running COST OF THIS SESSION while charging
        // (sessionKwh × rate), else this period's total cost, else the rate.
        var measured = (s.avgCostPerKwh !== undefined && s.avgCostPerKwh !== null) ? s.avgCostPerKwh : 0;
        var liveRate = this._liveRate();  // DC tariff when the open session is DC fast, else base
        if (isCharging && liveKwh > 0 && liveRate > 0) {
            this._setText('costHeroLabel', this._t('charge.hero_cost_session', 'Cost this session'));
            this._setText('costHeroValue', this._money(liveKwh * liveRate));
            this._setText('costHeroSub', this._t('charge.cost_estimated', 'estimated'));
        } else if (s.periodCost && s.periodCost > 0) {
            this._setText('costHeroLabel', this._t('charge.hero_cost_period', 'Cost this period'));
            this._setText('costHeroValue',
                (periodEnergyApproximate ? '~' : '')
                    + this._money(s.periodCost));
            this._setText('costHeroSub', measured > 0
                ? (periodEnergyApproximate ? '~' : '')
                    + this._money(measured)
                    + this._t('charge.per_kwh', '/kWh')
                : '');
        } else if (this.electricityRate > 0) {
            this._setText('costHeroLabel', this._t('charge.hero_cost', 'Cost per kWh'));
            this._setText('costHeroValue', this._money(this.electricityRate));
            this._setText('costHeroSub', this._t('charge.cost_configured', 'set rate'));
        } else {
            this._setText('costHeroLabel', this._t('charge.hero_cost', 'Cost per kWh'));
            this._setText('costHeroValue', '--');
            this._setText('costHeroSub', '');
        }

        // Period summary tiles.
        // Period tiles count COMPLETED sessions (from the daily rollup). The
        // open in-progress session isn't in that rollup yet, so add it live so
        // the tiles aren't "0 / -- / --" during your very first charge. No
        // double-count risk: it only lands in the rollup once it closes.
        var liveCost = (isCharging && liveKwh > 0 && liveRate > 0) ? liveKwh * liveRate : 0;
        var pSessions = (s.periodSessions || 0) + (isCharging ? 1 : 0);
        var pEnergy = energy + (isCharging ? liveKwh : 0);
        var pCost = (s.periodCost || 0) + liveCost;
        this._setText('summarySessions', pSessions > 0 ? pSessions : '--');
        this._setText('summaryEnergy', pEnergy > 0
            ? (periodEnergyApproximate ? '~' : '')
                + pEnergy.toFixed(1) + ' kWh'
            : '--');
        this._setText('summaryCost', pCost > 0
            ? (periodEnergyApproximate ? '~' : '')
                + this._money(pCost)
            : '--');
        // DC/AC: the live session's tier is known from its peak-power class.
        var liveDc = 0, liveAc = 0;
        if (isCharging && this._liveSession) {
            var k = this._typeKind(this._liveSession);
            if (k === 'dc') liveDc = 1; else if (k === 'fast' || k === 'slow') liveAc = 1;
        }
        this._setText('summaryDcAc', ((s.periodDcCount || 0) + liveDc) + ' / ' + ((s.periodAcCount || 0) + liveAc));
        // Range added: period rollup + the live session's gain so far.
        var liveRange = (isCharging && this._liveSession && this._liveSession.rangeGained > 0)
            ? this._liveSession.rangeGained : 0;
        var pRange = (s.periodRangeGained || 0) + liveRange;
        this._setText('summaryRangeGained', pRange > 0
            ? (periodEnergyApproximate ? '~' : '')
                + this._dist(pRange)
            : '--');
        this._showCard('statsEstimateDisclosure',
            periodEnergyApproximate
                || lifetimeEnergyApproximate
                || livePowerEstimated
                || liveCost > 0);
        this._showCard('summaryEstimateDisclosure',
            periodEnergyApproximate
                || livePowerEstimated
                || liveCost > 0);

        // Lifetime tiles.
        this._setText('lifetimeSessions', s.lifetimeSessions != null ? s.lifetimeSessions : '--');
        this._setText('lifetimeEnergy', (s.lifetimeEnergyKwh && s.lifetimeEnergyKwh > 0)
            ? (lifetimeEnergyApproximate ? '~' : '')
                + s.lifetimeEnergyKwh.toFixed(0) + ' kWh'
            : '--');
        this._setText('lifetimeCost', (s.lifetimeCost && s.lifetimeCost > 0)
            ? (lifetimeEnergyApproximate ? '~' : '')
                + this._money(s.lifetimeCost)
            : '--');

        // Show the session-derived cards ONLY when there's data to fill them —
        // empty chart frames are noise. The SoC chart + hero always render
        // (they have soc_history data even with zero charging sessions).
        var hasSessions = (s.lifetimeSessions || 0) > 0;
        var hasSohTrend = !!(s.sohTrend && s.sohTrend.length > 1);
        var hasCost = !!(s.daily && s.daily.length > 0 && s.periodCost > 0);
        var hasEfficiency = this._energyBars(this.sessions).length > 0;
        this._showCard('sohTrendCard', hasSohTrend);
        this._showCard('monthlyCostCard', hasCost);
        this._showCard('efficiencyCard', hasEfficiency);
        this._showCard('lifetimeCard', hasSessions);
        this._showCard('statsLowerGrid', hasEfficiency || hasSessions);
        this._showCard('statsTrendGrid', hasSohTrend || hasCost);

        // Stats-tab empty state: nothing session-derived to show yet. Tailor the
        // hint to whether recording is even enabled. NOTE: a charge in progress
        // counts as "have data" — otherwise the very first charge shows "No
        // charging data yet" even while the hero is live (no COMPLETED session
        // exists yet, so lifetimeSessions is still 0).
        var anyStatsCard = hasSessions || hasSohTrend || hasCost || hasEfficiency || isCharging;
        this._showCard('statsEmptyState', !anyStatsCard);
        if (!anyStatsCard) {
            var enabled = this._getChecked('chargingEnabled');
            this._setText('statsEmptyMsg', enabled
                ? this._t('charge.no_data_yet', 'No charging data yet')
                : this._t('charge.disabled_hint', 'Enable charging analytics in Settings to start recording'));
        }

        this._renderSummaryCharts(s);
    },

    _chargingCompletion: function (live) {
        if (!live || live.fault) return null;
        if (live.full) {
            return {
                primary: this._t(
                    'charge.completion_complete', 'Complete'),
                secondary: ''
            };
        }
        if (live.plugged && !live.charging) {
            return {
                primary: this._t(
                    'charge.completion_waiting', 'Waiting'),
                secondary: ''
            };
        }
        var mins = Number(live.timeToFullMin);
        if (!live.charging || !isFinite(mins) || mins <= 0) return null;
        mins = Math.max(1, Math.round(mins));
        var completedAt = Date.now() + mins * 60000;
        var clock = '';
        try {
            clock = new Date(completedAt).toLocaleTimeString(
                [], { hour: 'numeric', minute: '2-digit' });
        } catch (e) {
            var d = new Date(completedAt);
            clock = d.getHours() + ':'
                + (d.getMinutes() < 10 ? '0' : '')
                + d.getMinutes();
        }
        return {
            primary: this._fmtDuration(mins),
            secondary: this._t(
                'charge.completion_full_at', 'Full at') + ' ' + clock
        };
    },

    _periodAveragePower: function (live) {
        if (live && live.charging === true
                && live.isEstimated !== true
                && live.powerKw > 0.15) {
            return { value: live.powerKw, live: true };
        }

        var totalEnergy = 0;
        var totalHours = 0;
        var fallbackTotal = 0;
        var fallbackCount = 0;
        var rows = this.sessions || [];
        for (var i = 0; i < rows.length; i++) {
            var session = rows[i];
            if (!session || session.inProgress === true
                    || this._energyIsApproximate(session)) continue;
            if (session.energyAdded > 0
                    && session.durationMinutes > 0) {
                totalEnergy += session.energyAdded;
                totalHours += session.durationMinutes / 60;
            } else if (session.avgPower > 0) {
                fallbackTotal += session.avgPower;
                fallbackCount++;
            }
        }
        if (totalEnergy > 0 && totalHours > 0) {
            return { value: totalEnergy / totalHours, live: false };
        }
        if (fallbackCount > 0) {
            return {
                value: fallbackTotal / fallbackCount,
                live: false
            };
        }
        return { value: 0, live: false };
    },

    _showCard: function (id, show) {
        var el = document.getElementById(id);
        if (el) el.style.display = show ? '' : 'none';
    },

    _applySoc: function (soc, hours) {
        if (!soc) return;
        var effectiveHours = hours != null
            ? hours : this._socCacheHours;
        if (effectiveHours == null) effectiveHours = this.socHours;
        if (effectiveHours !== this.socHours) return;
        this.socHistoryCache = soc;
        this._socCacheHours = effectiveHours;
        var c = document.getElementById('socChart');
        if (c) this.renderSocOverTime(c, soc);
        // The live endpoint can be temporarily empty while the daemon reconnects.
        // Repaint the hero from the newest stored SOC/SOH point already on this page.
        if (this.summaryCache) {
            this._applySummary(
                this.summaryCache, this._summaryPeriodKey, true);
        }
    },

    _latestBatterySnapshot: function () {
        var rows = this.socHistoryCache || [];
        var result = { soc: null, range: null, soh: null };
        var socT = 0;
        var rangeT = 0;
        for (var i = rows.length - 1; i >= 0; i--) {
            var row = rows[i] || {};
            var rowT = isFinite(Number(row.t)) ? Number(row.t) : 0;
            var value;
            if (result.soc === null && row.soc != null) {
                value = Number(row.soc);
                if (isFinite(value) && value >= 0 && value <= 100) {
                    result.soc = value;
                    socT = rowT;
                }
            }
            if (result.range === null && row.range != null) {
                value = Number(row.range);
                if (isFinite(value) && value >= 0) {
                    result.range = value;
                    rangeT = rowT;
                }
            }
            if (result.soh === null && row.soh != null) {
                value = Number(row.soh);
                if (isFinite(value) && value > 0 && value <= 100) {
                    result.soh = value;
                }
            }
        }
        // SOC and range are current-state values; do not present an old parked
        // sample as live. SOH changes slowly, so the latest retained value is safe.
        var now = Date.now();
        if (socT < now - 2 * 60 * 60 * 1000
                || socT > now + 5 * 60 * 1000) result.soc = null;
        if (rangeT < now - 2 * 60 * 60 * 1000
                || rangeT > now + 5 * 60 * 1000) result.range = null;
        if (result.soh === null && this.summaryCache
                && Array.isArray(this.summaryCache.sohTrend)) {
            var trend = this.summaryCache.sohTrend;
            for (var j = trend.length - 1; j >= 0; j--) {
                value = trend[j] && Number(trend[j].soh);
                if (isFinite(value) && value > 0 && value <= 100) {
                    result.soh = value;
                    break;
                }
            }
        }
        return result;
    },

    _applySessions: function (sessions, offset) {
        this._hideSkeleton();
        if (offset === 0) this.sessions = sessions || [];
        else this.sessions = this.sessions.concat(sessions || []);
        this.currentOffset = offset;

        // Track the open in-progress session (if any) so the stats period tiles
        // can classify its DC/AC tier without re-querying.
        this._liveSession = null;
        for (var li = 0; li < this.sessions.length; li++) {
            if (this.sessions[li]
                    && this.sessions[li].inProgress === true
                    && this.sessions[li].chargingNow !== false) {
                this._liveSession = this.sessions[li];
                break;
            }
        }
        // If the session list arrived after the summary, re-apply the summary so
        // the live-augmented period tiles pick up _liveSession.
        if (this.summaryCache) {
            this._applySummary(
                this.summaryCache, this._summaryPeriodKey);
        }

        this._renderSessionCards();
        if (this.currentSessionId != null) {
            for (var di = 0; di < this.sessions.length; di++) {
                var detailRow = this.sessions[di];
                if (detailRow
                        && String(detailRow.id)
                            === String(this.currentSessionId)) {
                    this._fillDetailHeader(detailRow, detailRow.id);
                    break;
                }
            }
        }

        // "Load more" visible only when the last page was full.
        var more = document.getElementById('loadMoreBtn');
        if (more) more.style.display = (sessions && sessions.length >= this.pageSize) ? '' : 'none';

        var empty = document.getElementById('sessionEmptyState');
        if (empty) empty.style.display = (this.sessions.length === 0) ? '' : 'none';
    },

    // ==================== SESSION LIST ====================

    _renderSessionCards: function () {
        var grid = document.getElementById('sessionList');
        if (!grid) return;
        grid.innerHTML = '';
        var self = this;
        var displayedSessions = this.sessions.slice(0);
        displayedSessions.sort(function (a, b) {
            var at = a && a.startTime ? a.startTime : 0;
            var bt = b && b.startTime ? b.startTime : 0;
            return self.sortOrder === 'oldest' ? at - bt : bt - at;
        });
        for (var i = 0; i < displayedSessions.length; i++) {
            (function (s) {
                var card = document.createElement('article');
                card.className =
                    'session-card app-surface-card app-surface-card--interactive';
                card.setAttribute('role', 'button');
                card.setAttribute('tabindex', '0');

                var kind = self._typeKind(s);
                var typeLabel = self._typeLabel(s);
                var inProgress = s.inProgress === true;
                var chargingNow = inProgress && s.chargingNow !== false;
                var poisoned = s.powerDataQuality === 'poisoned';
                var calibration = s.calibration && s.calibration.qualified === true;
                // Active rows show the validated live rate; completed rows show a trusted peak.
                var chipKw = chargingNow
                        && s.livePowerKw != null && s.livePowerKw > 0
                    ? s.livePowerKw
                    : self._displayPeakKw(s);
                var powerEstimated = chargingNow
                    && s.isEstimated === true
                    && s.livePowerKw != null
                    && s.livePowerKw > 0;
                var peakStr = chipKw > 0
                    ? (powerEstimated ? '≈' : '') + chipKw.toFixed(1) + ' kW'
                    : '';
                var energyApproximate = self._energyIsApproximate(s);
                var estimateLabel = powerEstimated && energyApproximate
                    ? self._t('charge.power_energy_estimated',
                        'Power & energy estimated')
                    : powerEstimated
                        ? self._t('charge.power_estimated', 'Power estimated')
                        : self._t('charge.energy_estimated', 'Energy estimated');
                // Contradictory legacy power data is not presented as charging evidence.
                var energy = (!poisoned && s.energyAdded && s.energyAdded > 0)
                    ? (energyApproximate ? '~' : '+')
                        + s.energyAdded.toFixed(1) + ' kWh'
                    : '--';
                var socRange = self._socRangeText(s);
                var dur = (s.durationMinutes != null) ? self._fmtDuration(s.durationMinutes) : '';
                var costStr = (s.cost != null && s.cost > 0)
                    ? (energyApproximate ? '~' : '')
                        + self._money(s.cost)
                    : '';
                // Odometer at charge start (unit-aware), only when captured (>0).
                var odoStr = (s.startOdometerKm != null && s.startOdometerKm > 0) ? self._dist(s.startOdometerKm) : '';
                var locStr = self._locationLabel(s);   // place name, else coords, else ''

                var startTime = self._fmtDate(s.startTime);
                var rangeStr = (s.rangeGained != null && s.rangeGained > 0)
                    ? self._dist(s.rangeGained) : '--';
                var contextStr = locStr || costStr || odoStr || '--';
                var contextLabel = locStr
                    ? self._t('charge.detail_location', 'Location')
                    : (costStr
                        ? self._t('charge.detail_cost', 'Cost')
                        : self._t('charge.detail_odometer', 'Odometer'));
                var contextIcon =
                    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7a3 3 0 0 1 3-3h11v16H7a3 3 0 0 1-3-3V7z"/><path d="M4 8h14M14 12h7v5h-7a2.5 2.5 0 0 1 0-5z"/></svg>';
                if (locStr) {
                    contextIcon = self._pinIcon();
                } else if (!costStr && odoStr) {
                    contextIcon =
                        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 17a8 8 0 1 1 16 0"/><path d="m12 13 4-4"/><path d="M7 17h10"/></svg>';
                }
                var fillSoc = s.endSoc != null
                    ? Number(s.endSoc) : Number(s.startSoc);
                var socFillPct = isFinite(fillSoc)
                    && fillSoc >= 0 && fillSoc <= 100 ? fillSoc : 0;

                // Always show the power chip in the pill (peak for finished, live
                // for in-progress) — the user wants kW visible regardless of state.
                var powerChip = peakStr;
                card.setAttribute('aria-label', typeLabel + ', ' + energy);
                card.innerHTML =
                    '<div class="session-primary">' +
                        '<div class="session-kind-row">' +
                            '<span class="session-type session-type-' + kind + '">' +
                                self._typeIcon(kind) + '<span>' +
                                    self._esc(typeLabel) + '</span>' +
                            '</span>' +
                            (powerChip ? '<span class="session-power-chip">' +
                                self._esc(powerChip) + '</span>' : '') +
                        '</div>' +
                        '<div class="session-energy-row">' +
                            '<div class="session-energy">' +
                                self._esc(energy) + '</div>' +
                            (costStr ? '<div class="session-cost">' +
                                self._esc(costStr) + '</div>' : '') +
                            (chargingNow
                                ? '<span class="session-live"><span class="session-live-dot"></span>' +
                                    self._esc(self._t('charge.in_progress', 'Charging now')) + '</span>'
                                : '') +
                        '</div>' +
                        '<div class="session-soc-row">' +
                            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="6" width="16" height="12" rx="2"/><path d="M21 10v4"/></svg>' +
                            '<span>' + self._esc(socRange) + '</span>' +
                            '<span class="session-soc-track"><span class="session-soc-fill" style="width:' +
                                socFillPct + '%"></span></span>' +
                        '</div>' +
                        ((powerEstimated || energyApproximate)
                            ? '<span class="session-estimate-badge">' +
                                self._esc(estimateLabel) +
                              '</span>'
                            : '') +
                        (calibration ? '<div class="calibration-badge">LFP CALIBRATION CANDIDATE</div>' : '') +
                        (poisoned ? '<div class="quality-warning">' + self._qualityWarningContent(
                            self._t('charge.quality_warning_short', 'Contradictory power and charging energy hidden.')) + '</div>' : '') +
                    '</div>' +
                    '<div class="session-metric session-metric--start">' +
                        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/></svg>' +
                        '<div class="session-metric-copy">' +
                        '<div class="session-metric-value">' +
                            self._esc(startTime || '--') + '</div>' +
                        '<div class="session-metric-label">' +
                            self._esc(self._t('charge.start_time', 'Start time')) + '</div>' +
                    '</div></div>' +
                    '<div class="session-metric session-metric--duration">' +
                        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>' +
                        '<div class="session-metric-copy">' +
                        '<div class="session-metric-value">' + self._esc(dur || '--') + '</div>' +
                        '<div class="session-metric-label">' +
                            self._esc(self._t('charge.duration', 'Duration')) + '</div>' +
                    '</div></div>' +
                    '<div class="session-metric session-metric--range">' +
                        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 17a8 8 0 1 1 16 0"/><path d="m12 13 4-4"/><path d="M7 17h10"/></svg>' +
                        '<div class="session-metric-copy">' +
                        '<div class="session-metric-value">' + self._esc(rangeStr) + '</div>' +
                        '<div class="session-metric-label">' +
                            self._esc(self._t('charge.summary_range_gained', 'Range added')) + '</div>' +
                    '</div></div>' +
                    '<div class="session-metric session-metric--context">' +
                        contextIcon + '<div class="session-metric-copy">' +
                        '<div class="session-metric-value">' + self._esc(contextStr) + '</div>' +
                        '<div class="session-metric-label">' + self._esc(contextLabel) + '</div>' +
                    '</div></div>' +
                    '<div class="session-actions">' +
                        '<svg class="session-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">' +
                            '<path d="m9 18 6-6-6-6"></path>' +
                        '</svg>' +
                        (inProgress ? '' :
                            '<button class="session-delete-btn" title="' +
                                self._esc(self._t('charge.delete_session_title', 'Delete session')) + '">' +
                                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">' +
                                    '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/>' +
                                    '<path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
                            '</button>') +
                    '</div>' +
                    '';

                card.addEventListener('click', function () { self.showDetail(s.id); });
                card.addEventListener('keydown', function (e) {
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); self.showDetail(s.id); }
                });
                var delBtn = card.querySelector('.session-delete-btn');
                var qualityInfoBtn = card.querySelector('.quality-info-button');
                if (qualityInfoBtn) qualityInfoBtn.addEventListener('click', function (e) {
                    self.showQualityInfo(e);
                });
                if (delBtn) delBtn.addEventListener('click', function (e) {
                    e.stopPropagation();
                    self.deleteSession(s.id);
                });
                grid.appendChild(card);
            })(displayedSessions[i]);
        }
    },

    sortSessions: function (order) {
        this.sortOrder = order === 'oldest' ? 'oldest' : 'recent';
        this._renderSessionCards();
    },

    quickFilter: function (days, btn) {
        this.currentDays = days;          // 0 = all time
        this._rangeFrom = null;           // leaving custom-range mode
        this._rangeTo = null;
        var btns = document.querySelectorAll(
            '.charge-period-control .filter-tab[data-days]');
        for (var i = 0; i < btns.length; i++) {
            var buttonDays = parseInt(
                btns[i].getAttribute('data-days'), 10);
            if (buttonDays === days) btns[i].classList.add('active');
            else btns[i].classList.remove('active');
        }
        var row = document.getElementById('chargeRangeRow');
        if (row) row.classList.remove('open');
        this.currentOffset = 0;
        this._showSkeleton();
        this._loadCurrentLivePair();
    },

    // Reveal/hide the custom From → To range row (height+fade via the .open
    // class). Defaults to the last ~30 days the first time. Mirrors events.
    toggleCustomRange: function (btn) {
        var row = document.getElementById('chargeRangeRow');
        if (!row) return;
        if (row.classList.contains('open')) { row.classList.remove('open'); return; }
        row.classList.add('open');
        if (this._calFromKey == null) this._calFromKey = this._dateKey(new Date(Date.now() - 30 * 86400000));
        if (this._calToKey == null) this._calToKey = this._dateKey(new Date());
        this._updateRangeButtons();
        // Mark the Custom chip active.
        var btns = document.querySelectorAll(
            '.charge-period-control .filter-tab');
        for (var i = 0; i < btns.length; i++) btns[i].classList.remove('active');
        if (btn) btn.classList.add('active');
    },

    // Apply the picked From/To range (epoch-ms; From=start of day, To=end of day
    // inclusive). Either side may be unset → open-ended.
    applyCustomRange: function () {
        var fromMs = this._calFromKey ? this._keyToMs(this._calFromKey, false) : null;
        var toMs = this._calToKey ? this._keyToMs(this._calToKey, true) : null;
        if (fromMs == null && toMs == null) {
            this._toast(this._t('charge.range_pick', 'Pick a start or end date'), 'error');
            return;
        }
        if (fromMs != null && toMs != null && fromMs > toMs) {
            this._toast(this._t('charge.range_order', 'Start date must be before end date'), 'error');
            return;
        }
        this._rangeFrom = fromMs != null ? fromMs : 0;
        this._rangeTo = toMs;   // null = open-ended (daemon treats as no upper bound)
        this.currentOffset = 0;
        this._showSkeleton();
        this._loadCurrentLivePair();
    },

    // ---- Shared calendar (range picker) — ported from events.js ----------

    // Open the calendar to pick the 'from' or 'to' endpoint.
    openCalendar: function (which) {
        this._calTarget = which;   // 'from' | 'to'
        var seed = (which === 'to' ? this._calToKey : this._calFromKey);
        this._calMonth = seed ? new Date(seed + 'T00:00:00') : new Date();
        this._calMonth.setDate(1);
        this._renderCalendar();
        var pop = document.getElementById('chargeCalendarPopup');
        if (pop) pop.classList.add('active');
    },
    closeCalendar: function () {
        var pop = document.getElementById('chargeCalendarPopup');
        if (pop) pop.classList.remove('active');
    },
    calPrevMonth: function () { this._calMonth.setMonth(this._calMonth.getMonth() - 1); this._renderCalendar(); },
    calNextMonth: function () { this._calMonth.setMonth(this._calMonth.getMonth() + 1); this._renderCalendar(); },

    _renderCalendar: function () {
        var grid = document.getElementById('chargeCalendarGrid');
        var title = document.getElementById('chargeCalendarTitle');
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
        for (var d2 = 1; grid.children.length - 7 + d2 <= 42; d2++) this._calDayCell(grid, d2, this._dateKey(new Date(year, month + 1, d2)), true, todayKey);
    },

    _calDayCell: function (grid, day, dateKey, otherMonth, todayKey) {
        var self = this;
        var el = document.createElement('div');
        el.className = 'calendar-day';
        el.textContent = day;
        el.dataset.date = dateKey;
        if (otherMonth) el.classList.add('other-month');
        if (dateKey === todayKey) el.classList.add('today');
        if (dateKey === this._calFromKey || dateKey === this._calToKey) el.classList.add('selected');
        else if (this._calFromKey && this._calToKey && dateKey > this._calFromKey && dateKey < this._calToKey) el.classList.add('in-range');
        // Disable future dates.
        var today = new Date(); today.setHours(0, 0, 0, 0);
        if (new Date(dateKey + 'T00:00:00') > today) el.classList.add('disabled');
        else el.addEventListener('click', function () { self._calPick(dateKey); });
        grid.appendChild(el);
    },

    _calPick: function (dateKey) {
        if (this._calTarget === 'to') {
            this._calToKey = dateKey;
            // Keep order sane: if To precedes From, pull From back.
            if (this._calFromKey && this._calToKey < this._calFromKey) this._calFromKey = dateKey;
        } else {
            this._calFromKey = dateKey;
            if (this._calToKey && this._calFromKey > this._calToKey) this._calToKey = dateKey;
        }
        this._updateRangeButtons();
        this.closeCalendar();
    },

    _updateRangeButtons: function () {
        var lang = (window.BYD && BYD.i18n && BYD.i18n.getLang) ? BYD.i18n.getLang() : undefined;
        var fromTxt = document.getElementById('chargeFromText');
        var toTxt = document.getElementById('chargeToText');
        var fmt = function (key) {
            try { return new Date(key + 'T00:00:00').toLocaleDateString(lang, { month: 'short', day: 'numeric', year: 'numeric' }); }
            catch (e) { return key; }
        };
        // Show "From: <date>" / "To: <date>" so the field's role stays clear once
        // a date is chosen. Do NOT add the .has-date brand fill — two full-width
        // solid-green pills + a green Apply read as a "sea of green"; keep them as
        // outlined pills (the active selection shows via the calendar highlight).
        var fromLabel = this._t('charge.range_from', 'From');
        var toLabel = this._t('charge.range_to', 'To');
        if (fromTxt) fromTxt.textContent = this._calFromKey ? (fromLabel + ': ' + fmt(this._calFromKey)) : fromLabel;
        if (toTxt) toTxt.textContent = this._calToKey ? (toLabel + ': ' + fmt(this._calToKey)) : toLabel;
    },

    // "YYYY-MM-DD" local date key.
    _dateKey: function (d) {
        var m = d.getMonth() + 1, day = d.getDate();
        return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
    },
    // date key → epoch-ms at local 00:00 (or 23:59:59.999 when endOfDay).
    _keyToMs: function (key, endOfDay) {
        var p = key.split('-');
        if (p.length !== 3) return null;
        var y = parseInt(p[0], 10), mo = parseInt(p[1], 10) - 1, da = parseInt(p[2], 10);
        if (isNaN(y) || isNaN(mo) || isNaN(da)) return null;
        return (endOfDay ? new Date(y, mo, da, 23, 59, 59, 999) : new Date(y, mo, da, 0, 0, 0, 0)).getTime();
    },

    loadMore: function () {
        this.loadSessions(this.currentOffset + this.pageSize);
    },

    // ==================== DETAIL DRILL-IN ====================

    showDetail: function (id) {
        var self = this;
        var generation = ++this._detailGeneration;
        this.currentSessionId = id;
        this._detailSessionId = null;
        // Fail closed until the cached or fetched row proves this session is
        // complete. This also covers a detail opened before its list page loads.
        this._detailInProgress = true;
        var deleteBtn = document.getElementById('detailDeleteBtn');
        if (deleteBtn) {
            deleteBtn.disabled = true;
            deleteBtn.style.display = 'none';
            deleteBtn.setAttribute('aria-hidden', 'true');
        }
        // Clear any crosshair carried from a prior session's detail charts.
        this._clearDetailHoverState();
        this._currentDetailSession = null;
        this.samplesCache = [];
        this._renderDetailCharts([]);

        var list = document.getElementById('sessionListView');
        var detail = document.getElementById('chargingDetail');
        if (list) list.classList.add('hidden');
        if (detail) { detail.classList.remove('hidden'); detail.classList.add('active'); }
        if (document.body) {
            document.body.classList.add('charging-detail-open');
        }

        // Find the row we already have for the header; fetch full + samples.
        var row = null;
        for (var i = 0; i < this.sessions.length; i++) {
            if (this.sessions[i].id === id) { row = this.sessions[i]; break; }
        }
        if (row) this._fillDetailHeader(row, id);

        this._fetchJson('/api/charging/' + id)
            .then(function (d) {
                if (!self._isCurrentDetail(id, generation)) return;
                var session = self._payload(
                    d, 'session', false, true);
                if (session === null) {
                    throw new Error('invalid detail payload');
                }
                self._fillDetailHeader(session, id);
            })
            .catch(function () {});

        this._fetchJson('/api/charging/' + id + '/samples')
            .then(function (d) {
                if (!self._isCurrentDetail(id, generation)) return;
                var samples = self._payload(
                    d, 'samples', true, true);
                if (samples === null) {
                    throw new Error('invalid samples payload');
                }
                self.samplesCache = samples;
                self._renderDetailCharts(self.samplesCache);
            })
            .catch(function () {
                if (!self._isCurrentDetail(id, generation)) return;
                self.samplesCache = [];
                self._renderDetailCharts([]);
            });
    },

    _isCurrentDetail: function (id, generation) {
        return generation === this._detailGeneration
            && this.currentSessionId != null
            && String(this.currentSessionId) === String(id);
    },

    hideDetail: function () {
        var list = document.getElementById('sessionListView');
        var detail = document.getElementById('chargingDetail');
        if (detail) { detail.classList.add('hidden'); detail.classList.remove('active'); }
        if (list) list.classList.remove('hidden');
        if (document.body) {
            document.body.classList.remove('charging-detail-open');
        }
        this._detailGeneration++;
        this.currentSessionId = null;
        this._detailSessionId = null;
        this._detailInProgress = false;
        this._currentDetailSession = null;
        this.samplesCache = null;
        this._clearDetailHoverState();
    },

    // Reset the per-canvas hover index on the three detail charts so a crosshair
    // doesn't carry between sessions.
    _clearDetailHoverState: function () {
        var ids = ['detailPowerChart', 'detailTempChart'];
        for (var i = 0; i < ids.length; i++) {
            var c = document.getElementById(ids[i]);
            if (c) { c._chgHoverIdx = null; }
        }
    },

    _qualityWarningContent: function (message) {
        var label = this._t('charge.quality_info_open', 'Why was this charging data excluded?');
        return '<span class="quality-warning-text">' + this._esc(message) + '</span>' +
            '<button type="button" class="quality-info-button" aria-label="' + this._esc(label) +
                '" title="' + this._esc(label) + '">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">' +
                    '<circle cx="12" cy="12" r="9"></circle>' +
                    '<line x1="12" y1="11" x2="12" y2="16"></line>' +
                    '<circle cx="12" cy="8" r="0.7" fill="currentColor" stroke="none"></circle>' +
                '</svg>' +
            '</button>';
    },

    showQualityInfo: function (event) {
        if (event) {
            event.preventDefault();
            event.stopPropagation();
        }
        var title = this._t('charge.quality_info_title', 'Why was this data excluded?');
        var body = this._t('charge.quality_info_body',
            'OverDrive identified an explicit AC session whose stored peak power is at least 25 kW, which contradicts that connection type and matches a known legacy telemetry fault. The original session, samples, cost and totals remain unchanged. OverDrive only hides that session\'s charging energy, average power and peak power from the affected displays so the invalid readings are not presented as evidence.\n\nThe LFP calibration candidate badge is separate: it requires a completed 10% or lower to 99% or higher SOC span and an LFP battery declared by the configured physical vehicle model. It does not make the hidden power readings valid.');
        if (window.BYD && BYD.utils && typeof BYD.utils.alertDialog === 'function') {
            return BYD.utils.alertDialog({ title: title, body: body });
        }
        window.alert(title + '\n\n' + body);
    },

    _fillDetailHeader: function (s, sessionId) {
        if (this.currentSessionId == null
                || String(this.currentSessionId)
                !== String(sessionId)) return;
        this._detailSessionId = sessionId;
        this._currentDetailSession = s;
        var liveRow = s;
        for (var i = 0; i < this.sessions.length; i++) {
            if (this.sessions[i]
                    && String(this.sessions[i].id) === String(sessionId)) {
                liveRow = this.sessions[i];
                break;
            }
        }
        var inProgress = s.inProgress === true;
        var chargingNow = inProgress && liveRow.chargingNow !== false;
        var livePowerKw = chargingNow
                && liveRow.livePowerKw != null
                && liveRow.livePowerKw > 0
            ? liveRow.livePowerKw : 0;
        var powerEstimated = livePowerKw > 0
            && liveRow.isEstimated === true;
        var energyApproximate = this._energyIsApproximate(s);
        var notMeasured = this._t('charge.not_measured', 'Not measured');
        this._detailInProgress = inProgress;
        var deleteBtn = document.getElementById('detailDeleteBtn');
        if (deleteBtn) {
            deleteBtn.disabled = inProgress;
            deleteBtn.style.display = inProgress ? 'none' : '';
            deleteBtn.setAttribute('aria-hidden', inProgress ? 'true' : 'false');
        }
        var livePill = document.getElementById('detailLivePill');
        if (livePill) {
            livePill.style.display = chargingNow ? 'inline-flex' : 'none';
        }

        var duration = s.durationMinutes != null
            ? this._fmtDuration(s.durationMinutes) : '--';
        this._setText('detailDuration', duration);
        this._setText('detailChartDuration', duration);
        this._setText('detailTimeLabel', chargingNow
            ? this._t('charge.detail_time_so_far', 'Time so far')
            : this._t('charge.detail_duration', 'Duration'));
        this._setText('detailStartedAt',
            this._t('charge.detail_started', 'Started')
                + ' ' + this._fmtDate(s.startTime));

        var socText = this._socRangeText(s);
        this._setText('detailSocValue', socText);
        this._setText('detailChartSoc', socText);
        var socCaption = chargingNow
            ? this._t('charge.detail_start_now', 'Start → Now')
            : this._t('charge.detail_start_end', 'Start → End');
        this._setText('detailSocCaption', socCaption);
        this._setText('detailChartSocLabel', socCaption);
        var fillSoc = s.endSoc != null
            ? Number(s.endSoc) : Number(s.startSoc);
        var socFill = document.getElementById('detailSocFill');
        if (socFill) {
            socFill.style.width = isFinite(fillSoc)
                    && fillSoc >= 0 && fillSoc <= 100
                ? fillSoc + '%' : '0%';
        }

        this._setText('detailTitle', this._typeLabel(s) + ' · ' + this._fmtDate(s.startTime));
        var sub = [];
        if (chargingNow) sub.push(this._t('charge.in_progress', 'Charging now'));
        if (socText !== '--') sub.push(socText);
        if (s.durationMinutes != null) sub.push(this._fmtDuration(s.durationMinutes));
        this._setText('detailSubtitle', sub.join('  ·  '));

        var poisoned = s.powerDataQuality === 'poisoned';
        var calibration = s.calibration && s.calibration.qualified === true;
        var qualityWarning = document.getElementById('detailQualityWarning');
        if (qualityWarning) {
            var warningText = (calibration ? 'LFP calibration candidate. ' : '') +
                (poisoned ? 'Contradictory power and charging energy are hidden because this session contains invalid legacy power data.' : '');
            qualityWarning.innerHTML = poisoned
                ? this._qualityWarningContent(warningText)
                : '<span class="quality-warning-text">' + this._esc(warningText) + '</span>';
            qualityWarning.style.display = (calibration || poisoned) ? '' : 'none';
            var detailInfoBtn = qualityWarning.querySelector('.quality-info-button');
            var self = this;
            if (detailInfoBtn) detailInfoBtn.addEventListener('click', function (e) {
                self.showQualityInfo(e);
            });
        }
        // Same '~' convention as the list row: reconstructed/estimated energy is not exact.
        this._setText('detailEnergy', (!poisoned && s.energyAdded && s.energyAdded > 0)
            ? (energyApproximate ? '~' : '+')
                + s.energyAdded.toFixed(1) + ' kWh' : '--');
        this._setText('detailAvgPowerLabel', chargingNow
            ? this._t('charge.detail_current_power', 'Current power')
            : this._t('charge.detail_avg_power', 'Average power'));
        this._setText('detailAvgPower', chargingNow
            ? (livePowerKw > 0
                ? (powerEstimated ? '≈' : '') + livePowerKw.toFixed(1) + ' kW'
                : '--')
            : (!poisoned && s.avgPower != null && s.avgPower > 0
                ? s.avgPower.toFixed(1) + ' kW'
                : notMeasured));
        var displayedPeak = this._displayPeakKw(s);
        var peakText = displayedPeak > 0
            ? displayedPeak.toFixed(1) + ' kW' : notMeasured;
        this._setText('detailPeakPower', peakText);
        this._setText('detailChartPeak',
            peakText === notMeasured ? '--' : peakText);
        this._setText('detailRangeGained', (s.rangeGained != null && s.rangeGained > 0)
            ? (energyApproximate ? '~' : '') + this._dist(s.rangeGained)
            : '--');
        this._setText('detailOdometer', (s.startOdometerKm != null && s.startOdometerKm > 0) ? this._dist(s.startOdometerKm) : '--');
        this._setText('detailCost', (s.cost != null && s.cost >= 0)
            ? (energyApproximate ? '~' : '')
                + this._money(s.cost)
            : '--');
        this._setText('detailCostRate',
            s.cost != null && s.cost >= 0
                    && s.electricityRate != null
                    && s.electricityRate >= 0
                    && !s.tariffLabel
                ? '(' + this._money(s.electricityRate)
                    + this._t('charge.per_kwh', '/kWh') + ')'
                : '');
        this._showCard('detailEstimateDisclosure',
            powerEstimated || energyApproximate);
        this._setText('detailType', this._typeLabel(s));
        this._setText('detailTimeToFull', (chargingNow
                && liveRow.timeToFullMin != null
                && liveRow.timeToFullMin > 0)
            ? this._fmtDuration(liveRow.timeToFullMin) : '--');
        var temp = (s.tempAvg != null) ? s.tempAvg
                 : (s.tempHigh != null ? s.tempHigh : null);
        this._setText('detailTemp', (temp != null) ? Math.round(temp) + '°C' : '--');

        // Tariff provenance: name the tariff that priced this charge and the rate
        // it used, so a cost is always explainable. Sessions on the global rate
        // (including every session recorded before tariffs existed) hide the tile
        // rather than showing a bare rate with no source.
        var tariffStat = document.getElementById('detailTariffStat');
        if (tariffStat) {
            if (s.tariffLabel) {
                var rateTxt = (s.electricityRate != null && s.electricityRate > 0)
                    ? ' · ' + this._money(s.electricityRate) + '/kWh' : '';
                this._setText('detailTariff', s.tariffLabel + rateTxt);
                tariffStat.style.display = '';
            } else {
                tariffStat.style.display = 'none';
            }
        }

        // Location row: place name (or coords) + a "view on map" button when we
        // have coordinates. Hidden entirely when no location was captured.
        // Remember coords for openSessionMap (the button routes through the
        // native shouldOverrideUrlLoading Intent path, which has a clipboard
        // fallback on ROMs with no browser — same as the dashboard directions).
        this._detailLat = (s.lat != null) ? s.lat : null;
        this._detailLng = (s.lng != null) ? s.lng : null;
        var locRow = document.getElementById('detailLocRow');
        var locStr = this._locationLabel(s);
        if (locRow) {
            if (locStr) {
                locRow.style.display = '';
                this._setText('detailLocLabel', locStr);
                var mapBtn = document.getElementById('detailMapLink');
                if (mapBtn) mapBtn.style.display = (this._detailLat != null && this._detailLng != null) ? '' : 'none';
            } else {
                locRow.style.display = 'none';
            }
        }
        this._fillDetailSampleSummary(this.samplesCache || []);
    },

    _fillDetailSampleSummary: function (samples) {
        var session = this._currentDetailSession || {};
        var high = session.tempHigh != null
            ? Number(session.tempHigh) : null;
        var low = session.tempLow != null
            ? Number(session.tempLow) : null;
        var avg = session.tempAvg != null
            ? Number(session.tempAvg) : null;
        var avgTotal = 0;
        var avgCount = 0;
        samples = samples || [];
        for (var i = 0; i < samples.length; i++) {
            var sample = samples[i];
            if (!sample) continue;
            var sampleAvg = sample.temp != null
                ? Number(sample.temp) : null;
            var sampleHigh = sample.tempHigh != null
                ? Number(sample.tempHigh) : sampleAvg;
            var sampleLow = sample.tempLow != null
                ? Number(sample.tempLow) : sampleAvg;
            if (sampleHigh != null && isFinite(sampleHigh)
                    && (high == null || sampleHigh > high)) {
                high = sampleHigh;
            }
            if (sampleLow != null && isFinite(sampleLow)
                    && (low == null || sampleLow < low)) {
                low = sampleLow;
            }
            if (sampleAvg != null && isFinite(sampleAvg)) {
                avgTotal += sampleAvg;
                avgCount++;
            }
        }
        if (avgCount > 0) avg = avgTotal / avgCount;
        this._setText('detailTempHigh',
            high != null && isFinite(high)
                ? high.toFixed(1) + '°C' : '--');
        this._setText('detailTempLow',
            low != null && isFinite(low)
                ? low.toFixed(1) + '°C' : '--');
        this._setText('detailTempAvg',
            avg != null && isFinite(avg)
                ? avg.toFixed(1) + '°C' : '--');
    },

    // Open the session's location in the system maps app. Uses window.open so
    // the native WebView shouldOverrideUrlLoading handles it (ACTION_VIEW Intent
    // with a copy-to-clipboard fallback on locked-down ROMs) — NOT the daemon
    // proxy, and NOT a raw anchor that would silently fail with no browser.
    openSessionMap: function () {
        if (this._detailLat == null || this._detailLng == null) {
            this._toast(this._t('charge.no_location', 'No location for this session'), 'error');
            return;
        }
        var url = 'https://www.google.com/maps/search/?api=1&query=' + this._detailLat + ',' + this._detailLng;
        window.open(url, '_blank');
    },

    _renderDetailCharts: function (samples) {
        var power = document.getElementById('detailPowerChart');
        var temp = document.getElementById('detailTempChart');
        var powerSamples = this._powerCurvePoints(samples);
        var hasSamples = this._powerCurveValueCount(powerSamples) > 1;
        var hasTemperatureSamples = this._temperaturePoints(samples).length > 1;

        this._fillDetailSampleSummary(samples);

        var note = document.getElementById('detailNoSamples');
        if (note) note.style.display = hasSamples ? 'none' : '';
        var tempNote = document.getElementById('detailTempNoSamples');
        if (tempNote) tempNote.style.display = hasTemperatureSamples ? 'none' : '';

        if (hasSamples) {
            // Combined power+SoC curve; SoC ramp now lives on the right axis here
            // (the standalone "Charge curve" card was removed).
            if (power) this.renderPowerCurve(power, powerSamples);
        } else {
            this._clearCanvas('detailPowerChart');
        }
        if (hasTemperatureSamples) {
            if (temp) this.renderTempBand(temp, samples);
        } else {
            this._clearCanvas('detailTempChart');
        }
    },

    _powerCurvePoints: function (samples) {
        var pts = [];
        samples = samples || [];
        for (var i = 0; i < samples.length; i++) {
            var s = samples[i];
            if (s == null || typeof s.t !== 'number' || !isFinite(s.t)) continue;
            var hasPower = typeof s.power === 'number'
                    && isFinite(s.power) && s.power > 0;
            var hasSoc = typeof s.soc === 'number'
                    && isFinite(s.soc) && s.soc >= 0 && s.soc <= 100;
            // `power:null` is an explicit missing-rate sentinel from the API. Retain it even when
            // SoC is also unavailable, because dropping it would reconnect measured power segments
            // across an interval that was intentionally excluded from energy accounting.
            if (hasPower || hasSoc || s.power === null
                    || (typeof s.power === 'number' && isFinite(s.power) && s.power <= 0)) {
                pts.push({
                    t: s.t,
                    power: hasPower ? s.power : null,
                    soc: hasSoc ? s.soc : null
                });
            }
        }
        return pts;
    },

    _powerCurveValueCount: function (samples) {
        var count = 0;
        samples = samples || [];
        for (var i = 0; i < samples.length; i++) {
            var s = samples[i];
            if (s == null) continue;
            var hasPower = typeof s.power === 'number'
                    && isFinite(s.power) && s.power > 0;
            var hasSoc = typeof s.soc === 'number'
                    && isFinite(s.soc) && s.soc >= 0 && s.soc <= 100;
            if (hasPower || hasSoc) count++;
        }
        return count;
    },

    // ==================== SETTINGS ====================

    // Enable the Apply button when any setting changes (mirrors trips' dirty
    // flag so it doesn't sit always-active / misaligned).
    showApplyNeeded: function () {
        this._refreshConfigDirty();
    },
    resetApplyButton: function () {
        this._refreshConfigDirty();
    },

    saveSettings: function () {
        var self = this;
        var body = this._dirtyConfigBody();
        if (Object.keys(body).length === 0) {
            this._refreshConfigDirty();
            return;
        }
        var generation = ++this._configSaveGeneration;
        var btn = document.getElementById('chargingApplyBtn');
        if (btn) { btn.disabled = true; btn.textContent = self._t('charge.applying', 'Applying…'); }
        self._writing = true;  // block the visibilitychange refresh mid-save
        self._configWriting = true;
        fetch('/api/charging/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function (r) {
            return r.json().then(function (d) {
                if (!r.ok || !d || d.success !== true) {
                    throw new Error('config save rejected');
                }
                return d;
            });
        })
          .then(function (d) {
              if (generation !== self._configSaveGeneration) return;
              var keys = Object.keys(body);
              for (var i = 0; i < keys.length; i++) {
                  self._configBaseline[keys[i]] = body[keys[i]];
              }
              self._writing = false;
              self._configWriting = false;
              self._refreshConfigDirty();
              self._toast(self._t('charge.saved', 'Charging settings saved'));
              // Reconcile values merged by another client, while preserving
              // edits made locally after this request began.
              self.loadConfig();
              self._loadCurrentLivePair();
          })
          .catch(function () {
              if (generation !== self._configSaveGeneration) return;
              self._writing = false;
              self._configWriting = false;
              self._toast(self._t('charge.save_failed', 'Could not save charging settings'), 'error');
              // Keep the rejected values and original durable baseline. They
              // remain dirty and the user can correct/retry in place.
              self._refreshConfigDirty();
          });
    },

    editCost: async function () {
        var self = this;
        var sessionId = this.currentSessionId;
        var s = this._currentDetailSession;
        if (sessionId == null || !s
                || this._detailSessionId == null
                || String(this._detailSessionId) !== String(sessionId)) {
            return;
        }
        if (this._detailInProgress || s.endTime == null || s.endTime <= 0) {
            this._toast(this._t(
                'charge.cannot_edit_in_progress_cost',
                'Cannot edit cost of an in-progress session'), 'error');
            return;
        }

        var oldCost = s.cost != null && s.cost >= 0 ? s.cost : -1;
        var oldCostText = oldCost >= 0 ? oldCost.toFixed(2) : '';
        var message = this._t(
            'charge.edit_cost_prompt',
            'Enter the total cost of this charge. Leave empty to reset it.');
        var input = window.BYD && BYD.utils
                && typeof BYD.utils.promptDialog === 'function'
            ? await BYD.utils.promptDialog({
                title: this._t('charge.detail_cost', 'Cost'),
                body: message,
                label: this._t('charge.edit_cost', 'Edit cost'),
                value: oldCostText,
                placeholder: '0.00',
                inputType: 'number',
                inputMode: 'decimal'
            })
            : window.prompt(message, oldCostText);
        if (input === null) return;

        var trimmed = input.trim();
        var newCost = trimmed === '' ? -1 : Number(trimmed);
        if (!isFinite(newCost) || (newCost < 0 && newCost !== -1)) {
            this._toast(this._t(
                'charge.invalid_cost',
                'Please enter a valid non-negative number'), 'error');
            return;
        }
        if (newCost === oldCost) return;

        fetch('/api/charging/' + sessionId + '/cost', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ cost: newCost })
        }).then(function (response) {
            return response.json().then(function (body) {
                if (!response.ok || !body || body.success !== true) {
                    throw new Error('cost update rejected');
                }
                return body;
            });
        }).then(function () {
            var applyCost = function (row) {
                row.cost = newCost >= 0 ? newCost : null;
                row.electricityRate = newCost >= 0
                        && row.energyAdded != null && row.energyAdded > 0
                    ? newCost / row.energyAdded : null;
                row.tariffId = null;
                row.tariffLabel = null;
            };
            applyCost(s);
            for (var i = 0; i < self.sessions.length; i++) {
                if (self.sessions[i]
                        && String(self.sessions[i].id) === String(sessionId)) {
                    if (self.sessions[i] !== s) applyCost(self.sessions[i]);
                    break;
                }
            }
            self._renderSessionCards();
            if (self.currentSessionId != null
                    && String(self.currentSessionId) === String(sessionId)) {
                self._currentDetailSession = s;
                self._fillDetailHeader(s, sessionId);
            }
            self.loadSummary();
            self._toast(self._t('charge.cost_updated', 'Cost updated'));
        }).catch(function () {
            self._toast(self._t(
                'charge.cost_update_failed', 'Could not update cost'), 'error');
        });
    },

    deleteCurrent: function () {
        if (this.currentSessionId != null
                && this._detailSessionId != null
                && String(this.currentSessionId)
                === String(this._detailSessionId)
                && !this._detailInProgress) {
            this.deleteSession(this._detailSessionId);
        }
    },

    deleteSession: function (id) {
        var self = this;
        var msg = this._t('charge.delete_confirm', 'Delete this charging session?');
        if (!window.confirm(msg)) return;
        // POST fallback path — the in-app WebView can drop DELETE bodies/methods.
        fetch('/api/charging/' + id + '/delete', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d && d.success) {
                    self._toast(self._t('charge.deleted', 'Charging session deleted'));
                    // If we were viewing this session's detail, pop back to the list.
                    if (self.currentSessionId === id) self.hideDetail();
                    // Remove locally for an instant update, then refresh totals.
                    var kept = [];
                    for (var i = 0; i < self.sessions.length; i++) {
                        if (self.sessions[i].id !== id) kept.push(self.sessions[i]);
                    }
                    self.sessions = kept;
                    self._renderSessionCards();
                    var empty = document.getElementById('sessionEmptyState');
                    if (empty) empty.style.display = (self.sessions.length === 0) ? '' : 'none';
                    self._loadCurrentLivePair();
                } else {
                    self._toast(self._t('charge.delete_failed', 'Could not delete session'), 'error');
                }
            })
            .catch(function () { self._toast(self._t('charge.delete_failed', 'Could not delete session'), 'error'); });
    },

    clearHistory: function () {
        var self = this;
        var msg = this._t('charge.settings_clear_confirm', 'Delete all charging history? This cannot be undone.');
        if (!window.confirm(msg)) return;
        // POST variant (some WebViews drop DELETE) — the daemon accepts both.
        fetch('/api/charging/history/clear', { method: 'POST' })
            .then(function (r) {
                return r.json().then(function (body) {
                    if (!r.ok || !body || body.success !== true) throw new Error('clear failed');
                    return body;
                });
            })
            .then(function () {
                self._toast(self._t('charge.cleared', 'Charging history cleared'));
                self.currentOffset = 0;
                self._restartVisibleRefresh(true);
            })
            .catch(function () {
                self._toast(self._t('charge.clear_failed', 'Could not clear charging history'), 'error');
            });
    },

    // ==================== CHART RENDERERS (Canvas2D, DPR-scaled) ====================

    _setupCanvas: function (canvas, h) {
        var dpr = window.devicePixelRatio || 1;
        var w = canvas.clientWidth || canvas.parentNode.clientWidth || 320;
        var height = h || 180;
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(height * dpr);
        canvas.style.height = height + 'px';
        var ctx = canvas.getContext('2d');
        if (!ctx) return { ctx: null, w: w, h: height };
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, w, height);
        return { ctx: ctx, w: w, h: height };
    },

    _clearCanvas: function (id) {
        var c = document.getElementById(id);
        if (!c) return;
        var ctx = c.getContext('2d');
        if (ctx) ctx.clearRect(0, 0, c.width, c.height);
    },

    renderCircleGauge: function (canvasId, percent, color) {
        var canvas = document.getElementById(canvasId);
        if (!canvas) return;
        var dpr = window.devicePixelRatio || 1;
        var size = 120;
        canvas.width = size * dpr;
        canvas.height = size * dpr;
        canvas.style.width = size + 'px';
        canvas.style.height = size + 'px';
        var ctx = canvas.getContext('2d');
        if (!ctx) return;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

        var cx = size / 2, cy = size / 2, radius = 48, lineWidth = 8;
        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        ctx.strokeStyle = this.colors.arcTrack;
        ctx.lineWidth = lineWidth;
        ctx.stroke();

        if (percent > 0) {
            var startAngle = -Math.PI / 2;
            var endAngle = startAngle + (Math.min(percent, 100) / 100) * Math.PI * 2;
            ctx.beginPath();
            ctx.arc(cx, cy, radius, startAngle, endAngle);
            ctx.strokeStyle = color;
            ctx.lineWidth = lineWidth;
            ctx.lineCap = 'round';
            ctx.stroke();
        }
    },

    // SoC over time, with charging regions shaded. history: [{t,soc,charging}].
    renderSocOverTime: function (canvas, history) {
        if (!canvas || !history || !history.length) { return; }
        var dims = this._setupCanvas(canvas, 200);
        var ctx = dims.ctx, w = dims.w, h = dims.h;
        var pad = { l: 34, r: 12, t: 12, b: 22 };
        var plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;

        var pts = this._normalizePoints(history, 'soc');
        if (pts.list.length < 2) { this._clearCanvas(canvas.id); return; }
        var t0 = pts.tMin, tSpan = (pts.tMax - pts.tMin) || 1;
        var x = function (t) { return pad.l + ((t - t0) / tSpan) * plotW; };
        var y = function (v) { return pad.t + (1 - (v / 100)) * plotH; };

        this._drawGrid(ctx, pad, plotW, plotH, [0, 25, 50, 75, 100], function (v) { return v + '%'; }, 100, 0);

        // Charging-region bands.
        ctx.fillStyle = this.colors.brandRgba;
        var bandStart = null;
        for (var i = 0; i < pts.list.length; i++) {
            var charging = !!pts.list[i].charging;
            if (charging && bandStart === null) bandStart = pts.list[i].t;
            if ((!charging || i === pts.list.length - 1) && bandStart !== null) {
                var bx0 = x(bandStart), bx1 = x(pts.list[i].t);
                ctx.fillRect(bx0, pad.t, Math.max(bx1 - bx0, 1), plotH);
                bandStart = null;
            }
        }

        // Area fill under the SoC line.
        var grad = ctx.createLinearGradient(0, pad.t, 0, pad.t + plotH);
        grad.addColorStop(0, this._rgba(this.colors.brand, 0.28));
        grad.addColorStop(1, this._rgba(this.colors.brand, 0.02));
        ctx.beginPath();
        ctx.moveTo(x(pts.list[0].t), pad.t + plotH);
        for (var j = 0; j < pts.list.length; j++) ctx.lineTo(x(pts.list[j].t), y(pts.list[j].soc));
        ctx.lineTo(x(pts.list[pts.list.length - 1].t), pad.t + plotH);
        ctx.closePath();
        ctx.fillStyle = grad;
        ctx.fill();

        // SoC line.
        ctx.beginPath();
        for (var k = 0; k < pts.list.length; k++) {
            var px = x(pts.list[k].t), py = y(pts.list[k].soc);
            if (k === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
        }
        ctx.strokeStyle = this.colors.brand;
        ctx.lineWidth = 2;
        ctx.lineJoin = 'round';
        ctx.stroke();

        this._drawTimeLabels(ctx, pts, pad, plotW, plotH);

        // Cache geometry so the hover handler can map a pointer x back to the
        // nearest sample and redraw a crosshair + tooltip without recomputing.
        this._socGeom = {
            canvas: canvas, list: pts.list, t0: t0, tSpan: tSpan,
            pad: pad, plotW: plotW, plotH: plotH, w: w, h: h
        };
        this._setupSocHover(canvas);
        // Repaint any active crosshair (e.g. after a theme flip re-render).
        if (this._socHoverIdx != null) this._drawSocHover(this._socHoverIdx);
    },

    // Attach pointer handlers ONCE per canvas (idempotent via a flag) so a
    // re-render doesn't stack listeners. Mirrors performance.js interaction.
    _setupSocHover: function (canvas) {
        if (!canvas || canvas._socHoverBound) return;
        canvas._socHoverBound = true;
        var self = this;
        var onMove = function (clientX) {
            var g = self._socGeom;
            if (!g) return;
            var rect = g.canvas.getBoundingClientRect();
            var px = clientX - rect.left;
            // Find nearest sample by x.
            var best = -1, bestDx = 1e9;
            for (var i = 0; i < g.list.length; i++) {
                var sx = g.pad.l + ((g.list[i].t - g.t0) / g.tSpan) * g.plotW;
                var dx = Math.abs(sx - px);
                if (dx < bestDx) { bestDx = dx; best = i; }
            }
            if (best >= 0) { self._socHoverIdx = best; self._drawSocHover(best); }
        };
        canvas.addEventListener('mousemove', function (e) { onMove(e.clientX); });
        canvas.addEventListener('mouseleave', function () {
            self._socHoverIdx = null;
            if (self.socHistoryCache) self.renderSocOverTime(canvas, self.socHistoryCache);
        });
        canvas.addEventListener('touchstart', function (e) {
            if (e.touches && e.touches[0]) { e.preventDefault(); onMove(e.touches[0].clientX); }
        }, { passive: false });
        canvas.addEventListener('touchmove', function (e) {
            if (e.touches && e.touches[0]) { e.preventDefault(); onMove(e.touches[0].clientX); }
        }, { passive: false });
        canvas.addEventListener('touchend', function () {
            setTimeout(function () {
                self._socHoverIdx = null;
                if (self.socHistoryCache) self.renderSocOverTime(canvas, self.socHistoryCache);
            }, 1500);
        });
    },

    // Draw the crosshair + dot + tooltip for the sample at index `idx`. Repaints
    // the base chart first (cheap) so the overlay doesn't accumulate.
    _drawSocHover: function (idx) {
        var g = this._socGeom;
        if (!g || !g.list || idx == null || idx < 0 || idx >= g.list.length) return;
        // Repaint base, but guard against recursion (renderSocOverTime calls
        // _drawSocHover at the end — so temporarily clear the index).
        var saved = this._socHoverIdx;
        this._socHoverIdx = null;
        this.renderSocOverTime(g.canvas, this.socHistoryCache);
        this._socHoverIdx = saved;

        var ctx = g.canvas.getContext('2d');
        if (!ctx) return;
        var p = g.list[idx];
        var px = g.pad.l + ((p.t - g.t0) / g.tSpan) * g.plotW;
        var py = g.pad.t + (1 - (p.soc / 100)) * g.plotH;

        // Crosshair line.
        ctx.save();
        ctx.beginPath();
        ctx.moveTo(px, g.pad.t);
        ctx.lineTo(px, g.pad.t + g.plotH);
        ctx.strokeStyle = this._rgba(this.colors.text, 0.35);
        ctx.lineWidth = 1;
        if (this._supportsLineDash(ctx)) ctx.setLineDash([4, 4]);
        ctx.stroke();
        if (this._supportsLineDash(ctx)) ctx.setLineDash([]);

        // Point marker.
        ctx.beginPath();
        ctx.arc(px, py, 3.5, 0, Math.PI * 2);
        ctx.fillStyle = this.colors.brand;
        ctx.fill();
        ctx.lineWidth = 2;
        ctx.strokeStyle = this.colors.dotStroke;
        ctx.stroke();

        // Tooltip box: time + SoC% (+ "charging" marker when applicable).
        var socTxt = Math.round(p.soc) + '%';
        var timeTxt = this._fmtDate(p.t);
        ctx.font = '11px Inter, sans-serif';
        var tw = Math.max(ctx.measureText(socTxt).width, ctx.measureText(timeTxt).width) + 16;
        var th = 34;
        var bx = px + 10; if (bx + tw > g.w - 4) bx = px - tw - 10;
        if (bx < 4) bx = 4;
        var by = g.pad.t + 4;
        ctx.beginPath();
        this._roundRectPath(ctx, bx, by, tw, th, 6);
        ctx.fillStyle = this._rgba(this.colors.dotStroke, 0.95);
        ctx.fill();
        ctx.strokeStyle = this._rgba(this.colors.text, 0.2);
        ctx.lineWidth = 1;
        ctx.stroke();
        ctx.fillStyle = this.colors.textStrong;
        ctx.textAlign = 'left';
        ctx.textBaseline = 'top';
        ctx.font = '600 12px Inter, sans-serif';
        ctx.fillText(socTxt, bx + 8, by + 5);
        ctx.fillStyle = this.colors.textMuted;
        ctx.font = '10px Inter, sans-serif';
        ctx.fillText(timeTxt, bx + 8, by + 19);
        ctx.restore();
    },

    // Combined per-session chart: power kW (left axis, filled line) + SoC%
    // (right axis, dashed) on a shared time axis. Replaces the former separate
    // "Charge power" + "Charge curve" cards.
    renderPowerCurve: function (canvas, samples) {
        if (!canvas || !samples || samples.length < 2) return;
        var dims = this._setupCanvas(canvas, 210);
        var ctx = dims.ctx, w = dims.w, h = dims.h;
        var pad = { l: 38, r: 40, t: 18, b: 22 };
        var plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;

        // Validate all samples have .t defined before drawing (an undefined .t
        // produces NaN canvas coordinates that break the path).
        for (var v = 0; v < samples.length; v++) {
            if (samples[v] == null || samples[v].t == null) {
                this._clearCanvas(canvas.id);
                return;
            }
        }
        var t0 = samples[0].t, tSpan = (samples[samples.length - 1].t - t0) || 1;
        var maxP = 1;
        for (var i = 0; i < samples.length; i++) if (samples[i] && samples[i].power > maxP) maxP = samples[i].power;
        maxP = Math.ceil(maxP / 10) * 10;
        var x = function (t) { return pad.l + ((t - t0) / tSpan) * plotW; };
        var yP = function (v) { return pad.t + (1 - (v / maxP)) * plotH; };
        var yS = function (v) { return pad.t + (1 - (v / 100)) * plotH; };

        this._drawGrid(ctx, pad, plotW, plotH,
            [0, maxP * 0.25, maxP * 0.5, maxP * 0.75, maxP],
            function (v) { return Math.round(v); }, maxP, 0);

        // Power area + line. A null power row is an explicit unmeasured interval;
        // draw independent segments so the chart never invents a rate across it.
        var grad = ctx.createLinearGradient(0, pad.t, 0, pad.t + plotH);
        grad.addColorStop(0, this._rgba(this.colors.brand, 0.30));
        grad.addColorStop(1, this._rgba(this.colors.brand, 0.02));
        var segments = [], segment = [];
        for (var j = 0; j < samples.length; j++) {
            if (samples[j] != null && samples[j].power != null) {
                segment.push(samples[j]);
            } else if (segment.length) {
                segments.push(segment);
                segment = [];
            }
        }
        if (segment.length) segments.push(segment);
        for (var sg = 0; sg < segments.length; sg++) {
            var run = segments[sg];
            ctx.beginPath();
            ctx.moveTo(x(run[0].t), pad.t + plotH);
            for (var rp = 0; rp < run.length; rp++) {
                ctx.lineTo(x(run[rp].t), yP(run[rp].power));
            }
            ctx.lineTo(x(run[run.length - 1].t), pad.t + plotH);
            ctx.closePath();
            ctx.fillStyle = grad;
            ctx.fill();

            ctx.beginPath();
            for (var rl = 0; rl < run.length; rl++) {
                var px = x(run[rl].t), py = yP(run[rl].power);
                if (rl === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
            }
            ctx.strokeStyle = this.colors.brand;
            ctx.lineWidth = 2;
            ctx.stroke();
        }

        // SoC dashed line on the right axis.
        if (this._supportsLineDash(ctx)) ctx.setLineDash([6, 4]);
        ctx.beginPath();
        var drew = false;
        for (var m = 0; m < samples.length; m++) {
            if (samples[m].soc == null) continue;
            var sx = x(samples[m].t), sy = yS(samples[m].soc);
            if (!drew) { ctx.moveTo(sx, sy); drew = true; } else ctx.lineTo(sx, sy);
        }
        ctx.strokeStyle = this._rgba(this.colors.amber, 0.85);
        ctx.lineWidth = 1.6;
        ctx.stroke();
        if (this._supportsLineDash(ctx)) ctx.setLineDash([]);

        // Right axis = SoC% (0/50/100), to match the dashed SoC line. The left
        // axis (drawn by _drawGrid) is power kW. This is the combined "power +
        // charge curve" chart — both series on shared time, dual y-axes.
        ctx.fillStyle = this.colors.textMuted;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        var socTicks = [0, 50, 100];
        for (var st = 0; st < socTicks.length; st++) {
            ctx.fillText(socTicks[st] + '%', pad.l + plotW + 4, yS(socTicks[st]));
        }
        ctx.textAlign = 'left';

        // Legend: kW (brand) + SoC% (amber dashed).
        this._drawDualLegend(ctx, pad.l, pad.t - 2, plotW,
            this._t('charge.legend_power', 'kW'),
            this._t('charge.legend_soc', 'SoC %'));

        // Real wall-clock time axis (was relative "0m/Nm"). Shared across all
        // three detail charts so they line up on the same time scale.
        this._drawClockAxis(ctx, t0, samples[samples.length - 1].t, pad, plotW, plotH);

        // Register geometry for the shared detail-chart hover. The tooltip shows
        // power kW + SoC% + clock time at the nearest sample.
        var selfP = this;
        this._registerDetailHover(canvas, {
            samples: samples, t0: t0, tSpan: tSpan, pad: pad, plotW: plotW, plotH: plotH, w: w, h: h,
            render: function () {
                selfP.renderPowerCurve(canvas, selfP._powerCurvePoints(selfP.samplesCache));
            },
            dot: function (c, p, px) {
                if (p.power == null) return;
                var py = yP(p.power);
                c.beginPath(); c.arc(px, py, 3.5, 0, Math.PI * 2);
                c.fillStyle = selfP.colors.brand; c.fill();
                c.lineWidth = 2; c.strokeStyle = selfP.colors.dotStroke; c.stroke();
            },
            lines: function (p) {
                var L = [];
                if (p.power != null) L.push(p.power.toFixed(1) + ' kW');
                if (p.soc != null) L.push(Math.round(p.soc) + '%');
                L.push(selfP._fmtClock(p.t));
                return L;
            }
        });
    },

    // Normalize partial thermal samples. Some vehicle variants expose only the
    // average, high, or low channel; every valid channel must still produce a
    // finite line instead of leaving the chart with invalid min/max bounds.
    _temperaturePoints: function (samples) {
        var pts = [];
        samples = samples || [];
        for (var i = 0; i < samples.length; i++) {
            var s = samples[i];
            if (s == null || typeof s.t !== 'number' || !isFinite(s.t)) continue;
            var avg = (typeof s.temp === 'number' && isFinite(s.temp)) ? s.temp : null;
            var hi = (typeof s.tempHigh === 'number' && isFinite(s.tempHigh)) ? s.tempHigh : null;
            var lo = (typeof s.tempLow === 'number' && isFinite(s.tempLow)) ? s.tempLow : null;
            var center = avg;
            if (center == null && hi != null && lo != null) center = (hi + lo) / 2;
            if (center == null) center = hi != null ? hi : lo;
            if (center == null) continue;
            if (hi == null) hi = center;
            if (lo == null) lo = center;
            if (hi < lo) {
                var swap = hi;
                hi = lo;
                lo = swap;
            }
            if (center > hi) hi = center;
            if (center < lo) lo = center;
            pts.push({ t: s.t, avg: center, hi: hi, lo: lo });
        }
        return pts;
    },

    // Battery temperature: high–low band + average line. samples carry
    // temp (avg), tempHigh, tempLow — the pack reports a SPREAD of cell temps,
    // not one number, so we draw the band and label the avg.
    renderTempBand: function (canvas, samples) {
        if (!canvas) return;
        var pts = this._temperaturePoints(samples);
        if (pts.length < 2) { this._clearCanvas(canvas.id); return; }
        var dims = this._setupCanvas(canvas, 210);
        var ctx = dims.ctx, w = dims.w, h = dims.h;
        var pad = { l: 36, r: 12, t: 18, b: 22 };
        var plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;

        var minV = 1e9, maxV = -1e9;
        for (var j = 0; j < pts.length; j++) {
            var lo = (pts[j].lo != null) ? pts[j].lo : pts[j].avg;
            var hi = (pts[j].hi != null) ? pts[j].hi : pts[j].avg;
            if (lo != null && lo < minV) minV = lo;
            if (hi != null && hi > maxV) maxV = hi;
        }
        if (maxV - minV < 4) { minV -= 2; maxV += 2; }
        var t0 = pts[0].t, tSpan = (pts[pts.length - 1].t - t0) || 1;
        var x = function (t) { return pad.l + ((t - t0) / tSpan) * plotW; };
        var y = function (v) { return pad.t + (1 - (v - minV) / (maxV - minV)) * plotH; };

        this._drawGrid(ctx, pad, plotW, plotH,
            [minV, (minV + maxV) / 2, maxV],
            function (v) { return Math.round(v) + '°'; }, maxV, minV);

        // High–low band (filled) — only when we actually have a spread.
        var hasBand = false;
        for (var b = 0; b < pts.length; b++) { if (pts[b].hi != null && pts[b].lo != null && pts[b].hi !== pts[b].lo) { hasBand = true; break; } }
        if (hasBand) {
            ctx.beginPath();
            ctx.moveTo(x(pts[0].t), y(pts[0].hi));
            for (var u = 1; u < pts.length; u++) ctx.lineTo(x(pts[u].t), y(pts[u].hi));
            for (var d = pts.length - 1; d >= 0; d--) ctx.lineTo(x(pts[d].t), y(pts[d].lo));
            ctx.closePath();
            ctx.fillStyle = this._rgba(this.colors.amber, 0.16);
            ctx.fill();
        }

        // Average line.
        ctx.beginPath();
        var drewA = false;
        for (var k = 0; k < pts.length; k++) {
            if (pts[k].avg == null) continue;
            var px = x(pts[k].t), py = y(pts[k].avg);
            if (!drewA) { ctx.moveTo(px, py); drewA = true; } else ctx.lineTo(px, py);
        }
        ctx.strokeStyle = this.colors.amber;
        ctx.lineWidth = 2;
        ctx.stroke();

        this._drawClockAxis(ctx, t0, pts[pts.length - 1].t, pad, plotW, plotH);

        var selfT = this;
        this._registerDetailHover(canvas, {
            samples: pts, t0: t0, tSpan: tSpan, pad: pad, plotW: plotW, plotH: plotH, w: w, h: h,
            render: function () { selfT.renderTempBand(canvas, selfT.samplesCache); },
            dot: function (c, p, px) {
                if (p.avg == null) return;
                var py = y(p.avg);
                c.beginPath(); c.arc(px, py, 3.5, 0, Math.PI * 2);
                c.fillStyle = selfT.colors.amber; c.fill();
                c.lineWidth = 2; c.strokeStyle = selfT.colors.dotStroke; c.stroke();
            },
            lines: function (p) {
                var L = [];
                if (p.avg != null) L.push(Math.round(p.avg) + '° avg');
                if (p.hi != null && p.lo != null && p.hi !== p.lo) L.push(Math.round(p.lo) + '–' + Math.round(p.hi) + '°');
                L.push(selfT._fmtClock(p.t));
                return L;
            }
        });
    },

    // ---- Shared detail-chart hover --------------------------------------
    // One implementation drives the power / ramp / temp charts. `spec` carries
    // the chart's geometry + sample array plus three callbacks: render() to
    // repaint the base chart, dot(ctx,p,px) to mark the hovered point, and
    // lines(p) -> [strings] for the tooltip. State lives on the canvas element
    // so multiple detail charts hover independently.
    _registerDetailHover: function (canvas, spec) {
        if (!canvas) return;
        canvas._chgHoverSpec = spec;
        var self = this;
        if (canvas._chgHoverBound) {
            // Re-paint an active crosshair after a re-render (theme flip / poll).
            if (canvas._chgHoverIdx != null) this._drawDetailHover(canvas);
            return;
        }
        canvas._chgHoverBound = true;
        var onMove = function (clientX) {
            var g = canvas._chgHoverSpec;
            if (!g) return;
            var rect = canvas.getBoundingClientRect();
            var px = clientX - rect.left;
            var best = -1, bestDx = 1e9;
            for (var i = 0; i < g.samples.length; i++) {
                if (g.samples[i] == null || g.samples[i].t == null) continue;
                var sx = g.pad.l + ((g.samples[i].t - g.t0) / g.tSpan) * g.plotW;
                var dx = Math.abs(sx - px);
                if (dx < bestDx) { bestDx = dx; best = i; }
            }
            if (best >= 0) { canvas._chgHoverIdx = best; self._drawDetailHover(canvas); }
        };
        var clear = function () {
            canvas._chgHoverIdx = null;
            var g = canvas._chgHoverSpec;
            if (g && g.render) g.render();
        };
        canvas.addEventListener('mousemove', function (e) { onMove(e.clientX); });
        canvas.addEventListener('mouseleave', clear);
        canvas.addEventListener('touchstart', function (e) {
            if (e.touches && e.touches[0]) { e.preventDefault(); onMove(e.touches[0].clientX); }
        }, { passive: false });
        canvas.addEventListener('touchmove', function (e) {
            if (e.touches && e.touches[0]) { e.preventDefault(); onMove(e.touches[0].clientX); }
        }, { passive: false });
        canvas.addEventListener('touchend', function () { setTimeout(clear, 1500); });
        if (canvas._chgHoverIdx != null) this._drawDetailHover(canvas);
    },

    _drawDetailHover: function (canvas) {
        var g = canvas._chgHoverSpec;
        var idx = canvas._chgHoverIdx;
        if (!g || idx == null || idx < 0 || idx >= g.samples.length) return;
        var p = g.samples[idx];
        if (p == null || p.t == null) return;
        // Repaint base first (guard recursion: render() re-enters this via
        // _registerDetailHover, so clear the index across the repaint).
        var saved = canvas._chgHoverIdx;
        canvas._chgHoverIdx = null;
        if (g.render) g.render();
        canvas._chgHoverIdx = saved;
        // render() rebuilds the spec (new closures) — re-read it.
        g = canvas._chgHoverSpec;

        var ctx = canvas.getContext('2d');
        if (!ctx) return;
        var px = g.pad.l + ((p.t - g.t0) / g.tSpan) * g.plotW;

        ctx.save();
        ctx.beginPath();
        ctx.moveTo(px, g.pad.t);
        ctx.lineTo(px, g.pad.t + g.plotH);
        ctx.strokeStyle = this._rgba(this.colors.text, 0.35);
        ctx.lineWidth = 1;
        if (this._supportsLineDash(ctx)) ctx.setLineDash([4, 4]);
        ctx.stroke();
        if (this._supportsLineDash(ctx)) ctx.setLineDash([]);

        if (g.dot) { try { g.dot(ctx, p, px); } catch (e) {} }

        var lines = g.lines ? g.lines(p) : [];
        if (lines && lines.length) {
            ctx.font = '600 12px Inter, sans-serif';
            var tw = 0;
            for (var i = 0; i < lines.length; i++) tw = Math.max(tw, ctx.measureText(lines[i]).width);
            tw += 16;
            var th = 12 + lines.length * 15;
            var bx = px + 10; if (bx + tw > g.w - 4) bx = px - tw - 10;
            if (bx < 4) bx = 4;
            var by = g.pad.t + 4;
            ctx.beginPath();
            this._roundRectPath(ctx, bx, by, tw, th, 6);
            ctx.fillStyle = this._rgba(this.colors.dotStroke, 0.95);
            ctx.fill();
            ctx.strokeStyle = this._rgba(this.colors.text, 0.2);
            ctx.lineWidth = 1;
            ctx.stroke();
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';
            var ty = by + 6;
            for (var k = 0; k < lines.length; k++) {
                ctx.fillStyle = (k === 0) ? this.colors.textStrong : this.colors.textMuted;
                ctx.font = (k === 0) ? '600 12px Inter, sans-serif' : '10px Inter, sans-serif';
                ctx.fillText(lines[k], bx + 8, ty);
                ty += 15;
            }
        }
        ctx.restore();
    },

    // Bar hover: nearest bar by pointer x within the plot. `spec` =
    // { rects:[{bx,by,bw,bh,p}], w, h, pad, render(), lines(p) }. State on the
    // canvas element so it composes with the other charts.
    _registerBarHover: function (canvas, spec) {
        if (!canvas) return;
        canvas._barSpec = spec;
        var self = this;
        if (canvas._barBound) {
            if (canvas._barIdx != null) this._drawBarHover(canvas);
            return;
        }
        canvas._barBound = true;
        var onMove = function (clientX) {
            var g = canvas._barSpec;
            if (!g) return;
            var rect = canvas.getBoundingClientRect();
            var px = clientX - rect.left;
            var best = -1, bestDx = 1e9;
            for (var i = 0; i < g.rects.length; i++) {
                var cx = g.rects[i].bx + g.rects[i].bw / 2;
                var dx = Math.abs(cx - px);
                if (dx < bestDx) { bestDx = dx; best = i; }
            }
            if (best >= 0) { canvas._barIdx = best; self._drawBarHover(canvas); }
        };
        var clear = function () {
            canvas._barIdx = null;
            var g = canvas._barSpec;
            if (g && g.render) g.render();
        };
        canvas.addEventListener('mousemove', function (e) { onMove(e.clientX); });
        canvas.addEventListener('mouseleave', clear);
        canvas.addEventListener('touchstart', function (e) {
            if (e.touches && e.touches[0]) { e.preventDefault(); onMove(e.touches[0].clientX); }
        }, { passive: false });
        canvas.addEventListener('touchmove', function (e) {
            if (e.touches && e.touches[0]) { e.preventDefault(); onMove(e.touches[0].clientX); }
        }, { passive: false });
        canvas.addEventListener('touchend', function () { setTimeout(clear, 1500); });
        if (canvas._barIdx != null) this._drawBarHover(canvas);
    },

    _drawBarHover: function (canvas) {
        var g = canvas._barSpec;
        var idx = canvas._barIdx;
        if (!g || idx == null || idx < 0 || idx >= g.rects.length) return;
        var r = g.rects[idx];
        var saved = canvas._barIdx;
        canvas._barIdx = null;
        if (g.render) g.render();
        canvas._barIdx = saved;
        g = canvas._barSpec;
        r = g.rects[idx];

        var ctx = canvas.getContext('2d');
        if (!ctx) return;
        ctx.save();
        // Highlight the hovered bar with an outline.
        ctx.beginPath();
        this._roundRectPath(ctx, r.bx, r.by, r.bw, r.bh, 3);
        ctx.strokeStyle = this.colors.textStrong;
        ctx.lineWidth = 2;
        ctx.stroke();

        var lines = g.lines ? g.lines(r.p) : [];
        if (lines && lines.length) {
            ctx.font = '600 12px Inter, sans-serif';
            var tw = 0;
            for (var i = 0; i < lines.length; i++) tw = Math.max(tw, ctx.measureText(lines[i]).width);
            tw += 16;
            var th = 12 + lines.length * 15;
            var cx = r.bx + r.bw / 2;
            var bx = cx + 10; if (bx + tw > g.w - 4) bx = cx - tw - 10;
            if (bx < 4) bx = 4;
            var by = r.by - th - 6; if (by < g.pad.t) by = g.pad.t + 2;
            ctx.beginPath();
            this._roundRectPath(ctx, bx, by, tw, th, 6);
            ctx.fillStyle = this._rgba(this.colors.dotStroke, 0.95);
            ctx.fill();
            ctx.strokeStyle = this._rgba(this.colors.text, 0.2);
            ctx.lineWidth = 1;
            ctx.stroke();
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';
            var ty = by + 6;
            for (var k = 0; k < lines.length; k++) {
                ctx.fillStyle = (k === 0) ? this.colors.textStrong : this.colors.textMuted;
                ctx.font = (k === 0) ? '600 12px Inter, sans-serif' : '10px Inter, sans-serif';
                ctx.fillText(lines[k], bx + 8, ty);
                ty += 15;
            }
        }
        ctx.restore();
    },

    // Two-series legend drawn at the top-right of a chart's plot area: a solid
    // brand swatch + label, then a dashed amber swatch + label.
    _drawDualLegend: function (ctx, plotLeft, y, plotW, label1, label2) {
        ctx.save();
        ctx.font = '10px Inter, sans-serif';
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        var w2 = ctx.measureText(label2).width;
        var w1 = ctx.measureText(label1).width;
        var swatch = 14, gapTxt = 4, gapItem = 12;
        var totalW = swatch + gapTxt + w1 + gapItem + swatch + gapTxt + w2;
        var x = plotLeft + plotW - totalW;
        // Series 1: solid brand line.
        ctx.strokeStyle = this.colors.brand; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + swatch, y); ctx.stroke();
        ctx.fillStyle = this.colors.textMuted;
        ctx.fillText(label1, x + swatch + gapTxt, y);
        // Series 2: dashed amber line.
        var x2 = x + swatch + gapTxt + w1 + gapItem;
        ctx.strokeStyle = this._rgba(this.colors.amber, 0.85); ctx.lineWidth = 1.6;
        if (this._supportsLineDash(ctx)) ctx.setLineDash([6, 4]);
        ctx.beginPath(); ctx.moveTo(x2, y); ctx.lineTo(x2 + swatch, y); ctx.stroke();
        if (this._supportsLineDash(ctx)) ctx.setLineDash([]);
        ctx.fillStyle = this.colors.textMuted;
        ctx.fillText(label2, x2 + swatch + gapTxt, y);
        ctx.restore();
    },

    // Wall-clock time axis for the per-session detail charts (HH:MM at both
    // ends). Replaces the relative "0m / Nm" axis so the x is a real timestamp.
    _drawClockAxis: function (ctx, t0, tMax, pad, plotW, plotH) {
        ctx.fillStyle = this.colors.textMuted;
        ctx.font = '10px Inter, sans-serif';
        ctx.textBaseline = 'top';
        ctx.textAlign = 'left';
        ctx.fillText(this._fmtClock(t0), pad.l, pad.t + plotH + 6);
        ctx.textAlign = 'right';
        ctx.fillText(this._fmtClock(tMax), pad.l + plotW, pad.t + plotH + 6);
        // Mid label when the span is wide enough to be useful.
        if (plotW > 160) {
            ctx.textAlign = 'center';
            ctx.fillText(this._fmtClock(t0 + (tMax - t0) / 2), pad.l + plotW / 2, pad.t + plotH + 6);
        }
        ctx.textAlign = 'left';
    },

    // Date x-axis (MMM D at both ends + middle) for the day-scale stats charts.
    _drawDateAxis: function (ctx, t0, tMax, pad, plotW, plotH) {
        ctx.fillStyle = this.colors.textMuted;
        ctx.font = '10px Inter, sans-serif';
        ctx.textBaseline = 'top';
        var fmt = this._fmtDay ? this._fmtDay : function (ts) { return ''; };
        ctx.textAlign = 'left';
        ctx.fillText(fmt(t0), pad.l, pad.t + plotH + 6);
        ctx.textAlign = 'right';
        ctx.fillText(fmt(tMax), pad.l + plotW, pad.t + plotH + 6);
        if (plotW > 160 && tMax > t0) {
            ctx.textAlign = 'center';
            ctx.fillText(fmt(t0 + (tMax - t0) / 2), pad.l + plotW / 2, pad.t + plotH + 6);
        }
        ctx.textAlign = 'left';
    },

    // SOH degradation trend (line + dots). trend: [{day,soh}].
    renderSohTrend: function (canvas, trend) {
        if (!canvas || !trend || trend.length < 2) { if (canvas) this._clearCanvas(canvas.id); return; }
        var dims = this._setupCanvas(canvas, 170);
        var ctx = dims.ctx, w = dims.w, h = dims.h;
        var pad = { l: 38, r: 12, t: 12, b: 22 };
        var plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;

        var minV = 1e9, maxV = -1e9;
        for (var i = 0; i < trend.length; i++) { if (trend[i] != null && trend[i].soh < minV) minV = trend[i].soh; if (trend[i] != null && trend[i].soh > maxV) maxV = trend[i].soh; }
        minV = Math.floor(minV - 1); maxV = Math.ceil(maxV + 1);
        if (maxV > 100) maxV = 100;
        if (minV >= maxV) maxV = minV + 1;
        var t0 = trend[0].day, tSpan = (trend[trend.length - 1].day - t0) || 1;
        var x = function (t) { return pad.l + ((t - t0) / tSpan) * plotW; };
        var y = function (v) { return pad.t + (1 - (v - minV) / (maxV - minV)) * plotH; };

        this._drawGrid(ctx, pad, plotW, plotH, [minV, (minV + maxV) / 2, maxV], function (v) { return Math.round(v) + '%'; }, maxV, minV);

        ctx.beginPath();
        for (var k = 0; k < trend.length; k++) {
            if (trend[k] == null) continue;
            var px = x(trend[k].day), py = y(trend[k].soh);
            if (k === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
        }
        ctx.strokeStyle = this.colors.good;
        ctx.lineWidth = 2;
        ctx.stroke();
        for (var m = 0; m < trend.length; m++) {
            if (trend[m] == null) continue;
            ctx.beginPath();
            ctx.arc(x(trend[m].day), y(trend[m].soh), 2.5, 0, Math.PI * 2);
            ctx.fillStyle = this.colors.good;
            ctx.fill();
        }

        this._drawDateAxis(ctx, t0, trend[trend.length - 1].day, pad, plotW, plotH);

        // Hover: SOH% + date at the nearest day. Map the {day,soh} series into
        // the {t,...} shape the generic hover expects.
        var selfS = this;
        var pts = [];
        for (var z = 0; z < trend.length; z++) { if (trend[z] != null) pts.push({ t: trend[z].day, soh: trend[z].soh }); }
        this._registerDetailHover(canvas, {
            samples: pts, t0: t0, tSpan: tSpan, pad: pad, plotW: plotW, plotH: plotH, w: w, h: h,
            render: function () { selfS.renderSohTrend(canvas, selfS.summaryCache ? selfS.summaryCache.sohTrend : trend); },
            dot: function (c, p, px) {
                var py = y(p.soh);
                c.beginPath(); c.arc(px, py, 3.5, 0, Math.PI * 2);
                c.fillStyle = selfS.colors.good; c.fill();
                c.lineWidth = 2; c.strokeStyle = selfS.colors.dotStroke; c.stroke();
            },
            lines: function (p) { return [p.soh.toFixed(1) + '%', selfS._fmtDay(p.t)]; }
        });
    },

    // Per-day cost bars. daily: [{day,cost,energy}].
    renderCostBars: function (canvas, daily) {
        if (!canvas || !daily || !daily.length) { if (canvas) this._clearCanvas(canvas.id); return; }
        var dims = this._setupCanvas(canvas, 170);
        var ctx = dims.ctx, w = dims.w, h = dims.h;
        var pad = { l: 38, r: 12, t: 12, b: 22 };
        var plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;

        var maxC = 0;
        for (var i = 0; i < daily.length; i++) if (daily[i].cost > maxC) maxC = daily[i].cost;
        if (maxC <= 0) { this._clearCanvas(canvas.id); return; }
        maxC = maxC * 1.1;

        if (plotW <= 0) { this._clearCanvas(canvas.id); return; }

        this._drawGrid(ctx, pad, plotW, plotH, [0, maxC / 2, maxC], (function (self) {
            return function (v) { return self._money(v); };
        })(this), maxC, 0);

        var n = daily.length;
        var slot = plotW / n;
        var barW = Math.max(Math.min(slot * 0.6, 26), 3);
        var rects = [];
        for (var j = 0; j < n; j++) {
            var cost = daily[j].cost || 0;
            var barH = (cost / maxC) * plotH;
            var bx = pad.l + slot * j + (slot - barW) / 2;
            var by = pad.t + plotH - barH;
            ctx.beginPath();
            this._roundRectPath(ctx, bx, by, barW, Math.max(barH, 1), 3);
            ctx.fillStyle = this.colors.brand;
            ctx.fill();
            rects.push({ bx: bx, by: by, bw: barW, bh: Math.max(barH, 1), p: daily[j] });
        }

        // Date x-axis (first/last day) so bars are anchored in time.
        if (n > 0) this._drawDateAxis(ctx, daily[0].day, daily[n - 1].day, pad, plotW, plotH);

        // Bar hover → cost + energy + date.
        var selfC = this;
        this._registerBarHover(canvas, {
            rects: rects, w: w, h: h, pad: pad,
            render: function () { selfC.renderCostBars(canvas, selfC.summaryCache ? (selfC.summaryCache.daily || []) : daily); },
            lines: function (p) {
                var approximate = (p.estimated || p.incomplete || 0) > 0;
                var prefix = approximate ? '~' : '';
                var L = [prefix + selfC._money(p.cost || 0)];
                if (p.energy != null && p.energy > 0) {
                    L.push(prefix
                        + (Math.round(p.energy * 10) / 10)
                        + ' kWh');
                }
                L.push(selfC._fmtDay(p.day));
                return L;
            }
        });
    },

    // Per-session energy points from COMPLETED sessions (newest first in the
    // list → reversed to chronological). Shared by the card-visibility gate and
    // the bar renderer. Range-derived efficiency was dropped (range is now
    // energy×const, so a range-vs-energy scatter was a meaningless straight line).
    _energyBars: function (sessions) {
        var pts = [];
        if (!sessions) return pts;
        for (var i = 0; i < sessions.length; i++) {
            var s = sessions[i];
            if (!s || s.inProgress === true) continue;
            if (s.energyAdded > 0) {
                var chargerKind = this._typeKind(s);
                pts.push({
                    t: s.startTime,
                    kwh: s.energyAdded,
                    chargerKind: chargerKind,
                    cost: s.cost,
                    approximate: this._energyIsApproximate(s)
                });
            }
        }
        pts.reverse();   // chronological (sessions arrive newest-first)
        return pts;
    },

    // Per-session energy-added bars (one bar per completed charge). DC bars
    // brand-coloured, AC accent. Replaces the old range-vs-energy scatter.
    renderEfficiency: function (canvas, sessions) {
        if (!canvas) return;
        var pts = this._energyBars(sessions);
        if (pts.length < 1) { this._clearCanvas(canvas.id); return; }
        var dims = this._setupCanvas(canvas, 180);
        var ctx = dims.ctx, w = dims.w, h = dims.h;
        var pad = { l: 40, r: 12, t: 14, b: 24 };
        var plotW = w - pad.l - pad.r, plotH = h - pad.t - pad.b;
        if (plotW <= 0) { this._clearCanvas(canvas.id); return; }

        var maxK = 0;
        for (var i = 0; i < pts.length; i++) if (pts[i].kwh > maxK) maxK = pts[i].kwh;
        if (maxK <= 0) { this._clearCanvas(canvas.id); return; }
        maxK *= 1.1;

        var fmtNum = function (v) { return v >= 10 ? v.toFixed(0) : (Math.round(v * 10) / 10).toString(); };
        this._drawGrid(ctx, pad, plotW, plotH, [0, maxK / 2, maxK],
            function (v) { return fmtNum(v); }, maxK, 0);

        var n = pts.length;
        var slot = plotW / n;
        var barW = Math.max(Math.min(slot * 0.6, 28), 3);
        var rects = [];
        for (var j = 0; j < n; j++) {
            var barH = (pts[j].kwh / maxK) * plotH;
            var bx = pad.l + slot * j + (slot - barW) / 2;
            var by = pad.t + plotH - barH;
            ctx.beginPath();
            this._roundRectPath(ctx, bx, by, barW, Math.max(barH, 1), 3);
            ctx.fillStyle = pts[j].chargerKind === 'dc'
                ? this.colors.brand
                : pts[j].chargerKind === 'unk'
                    ? this.colors.textMuted
                    : this.colors.accent;
            ctx.fill();
            rects.push({ bx: bx, by: by, bw: barW, bh: Math.max(barH, 1), p: pts[j] });
        }

        // Y-axis title (kWh per session).
        ctx.save();
        ctx.fillStyle = this.colors.textMuted;
        ctx.font = '10px Inter, sans-serif';
        ctx.translate(10, pad.t + plotH / 2);
        ctx.rotate(-Math.PI / 2);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';
        ctx.fillText(this._t('charge.energy_bar_y', 'kWh per charge'), 0, 0);
        ctx.restore();

        // Bar hover → date + kWh (+ cost).
        var selfE = this;
        this._registerBarHover(canvas, {
            rects: rects, w: w, h: h, pad: pad,
            render: function () { selfE.renderEfficiency(canvas, selfE.sessions); },
            lines: function (p) {
                var prefix = p.approximate ? '~' : '';
                var L = [prefix + fmtNum(p.kwh) + ' kWh'];
                if (p.cost != null && p.cost > 0) {
                    L.push(prefix + selfE._money(p.cost));
                }
                L.push(selfE._fmtDay(p.t));
                return L;
            }
        });
    },

    _renderSummaryCharts: function (s) {
        // Only paint a chart whose card is actually visible — a display:none
        // canvas has offsetParent null (hidden from layout flow), so guard on
        // that rather than risk rendering at a degenerate size.
        var vis = function (c) { return c && c.offsetParent !== null; };
        var soh = document.getElementById('sohTrendChart');
        if (vis(soh)) this.renderSohTrend(soh, s.sohTrend || []);
        var cost = document.getElementById('monthlyCostChart');
        if (vis(cost)) this.renderCostBars(cost, s.daily || []);
        var eff = document.getElementById('efficiencyChart');
        if (vis(eff)) this.renderEfficiency(eff, this.sessions || []);
    },

    // ==================== CANVAS HELPERS ====================

    _roundRectPath: function (ctx, x, y, w, h, r) {
        if (typeof ctx.roundRect === 'function') { ctx.roundRect(x, y, w, h, r); return; }
        var rr = Math.min(r, w / 2, h / 2);
        if (rr < 0) rr = 0;
        ctx.moveTo(x + rr, y);
        ctx.lineTo(x + w - rr, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + rr);
        ctx.lineTo(x + w, y + h - rr);
        ctx.quadraticCurveTo(x + w, y + h, x + w - rr, y + h);
        ctx.lineTo(x + rr, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - rr);
        ctx.lineTo(x, y + rr);
        ctx.quadraticCurveTo(x, y, x + rr, y);
        ctx.closePath();
    },

    _supportsLineDash: function (ctx) {
        return typeof ctx.setLineDash === 'function';
    },

    _drawGrid: function (ctx, pad, plotW, plotH, ticks, fmt, maxV, minV) {
        ctx.strokeStyle = this.colors.grid;
        ctx.fillStyle = this.colors.textMuted;
        ctx.lineWidth = 1;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        var range = (maxV - minV) || 1;
        for (var i = 0; i < ticks.length; i++) {
            var v = ticks[i];
            var gy = pad.t + (1 - (v - minV) / range) * plotH;
            ctx.beginPath();
            ctx.moveTo(pad.l, gy);
            ctx.lineTo(pad.l + plotW, gy);
            ctx.stroke();
            ctx.fillText(fmt(v), pad.l - 4, gy);
        }
        ctx.textAlign = 'left';
    },

    _drawTimeLabels: function (ctx, pts, pad, plotW, plotH) {
        ctx.fillStyle = this.colors.textMuted;
        ctx.font = '10px Inter, sans-serif';
        ctx.textBaseline = 'top';
        ctx.textAlign = 'left';
        ctx.fillText(this._fmtClock(pts.tMin), pad.l, pad.t + plotH + 6);
        ctx.textAlign = 'right';
        ctx.fillText(this._fmtClock(pts.tMax), pad.l + plotW, pad.t + plotH + 6);
        ctx.textAlign = 'left';
    },

    _normalizePoints: function (history, key) {
        var list = [];
        var tMin = 1e18, tMax = -1e18;
        for (var i = 0; i < history.length; i++) {
            var row = history[i];
            if (!row) continue;
            var t = row.t != null ? row.t : (row.timestamp != null ? row.timestamp : i);
            var v = row[key];
            if (v == null && key === 'soc') v = row.socPercent;
            if (v == null) continue;
            list.push({ t: t, soc: v, charging: row.charging || row.is_charging || row.isCharging });
            if (t < tMin) tMin = t;
            if (t > tMax) tMax = t;
        }
        return { list: list, tMin: tMin, tMax: tMax };
    },

    // ==================== TARIFFS (location-aware rates) ====================

    loadTariffs: function () {
        var self = this;
        var generation = ++this._tariffsGeneration;
        return this._fetchJson('/api/charging/tariffs')
            .then(function (d) {
                if (generation !== self._tariffsGeneration) return null;
                if (!d || d.success !== true || d.error
                        || !Array.isArray(d.tariffs)) {
                    throw new Error('invalid tariffs payload');
                }
                self._applyTariffs(d);
                return true;
            })
            .catch(function () {
                return generation === self._tariffsGeneration
                    ? false : null;
            });
    },

    /**
     * Absorb a /api/charging/tariffs payload (or the matching bootstrap slice).
     * `meta` carries the default id, the live GPS fix and which tariff matches
     * it — that's what drives the "auto here" pill without a second request.
     */
    _applyTariffs: function (d) {
        if (!d) return;
        // A failed endpoint/bootstrap section yields {error:...} (or success:false),
        // which has no `tariffs` array. Treating that as an empty list wiped the
        // rendered rows AND closed an open editor on a transient failure. Keep the
        // last good state instead.
        if (d.error || d.success === false) return;
        if (!d.tariffs && !(d.meta && d.meta.tariffs)) return;
        var meta = d.meta || d;
        this.tariffs = d.tariffs || meta.tariffs || [];
        this.defaultTariffId = meta.defaultTariffId || '';
        this.matchedTariffId = meta.matchedTariffId || '';
        this.maxTariffs = meta.maxTariffs || 40;
        // Remember the fix so "add for this location" can show coordinates before
        // the POST, and so the editor can say when there's no fix to pin to.
        this.tariffGpsLat = (meta.lat != null) ? meta.lat : null;
        this.tariffGpsLng = (meta.lng != null) ? meta.lng : null;
        this.renderTariffs();
        // Keep the chip honest about the fix the save will actually use.
        var ed = document.getElementById('tariffEditor');
        if (ed && ed.style.display !== 'none') this._renderTariffLocChip();
    },

    renderTariffs: function () {
        var list = document.getElementById('tariffList');
        var empty = document.getElementById('tariffEmpty');
        if (!list) return;

        var rows = this.tariffs || [];
        // Drop the editor if the tariff it is bound to no longer exists (deleted
        // here, or removed by another client between refreshes). Otherwise Save
        // would PUT a dead id, 404, and look like a random failure.
        if (this._editingTariff) {
            var stillThere = false;
            for (var k = 0; k < rows.length; k++) {
                if (rows[k].id === this._editingTariff.id) { stillThere = true; break; }
            }
            if (!stillThere) this.closeTariffEditor();
        }
        list.innerHTML = '';
        if (rows.length === 0) {
            if (empty) empty.style.display = '';
            this._updateTariffAddBtn();
            this._renderTariffFallbackNote();
            return;
        }
        if (empty) empty.style.display = 'none';

        var self = this;
        for (var i = 0; i < rows.length; i++) {
            list.appendChild(self._tariffRow(rows[i]));
        }
        this._updateTariffAddBtn();
        this._renderTariffFallbackNote();
    },

    /**
     * State the fallback: what prices a charge that matches no tariff circle.
     * Only rendered when there IS a fallback to name — with no tariffs, no
     * default and no global rate there is nothing true to say, so we stay silent
     * rather than explaining a mechanism that isn't doing anything yet.
     */
    _renderTariffFallbackNote: function () {
        var host = document.getElementById('tariffFallbackNote');
        if (!host) return;
        var def = null;
        var rows = this.tariffs || [];
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].id === this.defaultTariffId) { def = rows[i]; break; }
        }
        // resolve() requires the default to actually price the gun, and rateFor()
        // falls back to acRate — so a default with no acRate cannot price a normal
        // AC charge. Don't promise it covers everything in that case.
        if (def && def.enabled !== false && def.acRate > 0) {
            var label = (def.label && def.label !== '')
                ? def.label : this._t('charge.tariff_unnamed', 'Unnamed tariff');
            host.textContent = this._t('charge.tariff_fallback_default',
                'Charges outside every tariff use "' + label + '".', { label: label });
            host.style.display = '';
            return;
        }
        if (this.electricityRate > 0) {
            var rate = this._money(this.electricityRate);
            host.textContent = this._t('charge.tariff_fallback_global',
                'Charges outside every tariff use ' + rate + '/kWh.', { rate: rate });
            host.style.display = '';
            return;
        }
        host.style.display = 'none';
    },

    /** Disable "add" at the cap so the user learns the limit before a failed POST. */
    _updateTariffAddBtn: function () {
        var btn = document.getElementById('tariffAddBtn');
        if (!btn) return;
        var atCap = (this.tariffs || []).length >= (this.maxTariffs || 40);
        btn.disabled = atCap;
        btn.title = atCap
            ? this._t('charge.tariff_limit', 'Tariff limit reached')
            : '';
    },

    _tariffRow: function (t) {
        var self = this;
        var row = document.createElement('div');
        row.className = 'tariff-row'
            + (t.id === this.matchedTariffId ? ' matched' : '')
            + (t.enabled === false ? ' disabled' : '');

        var cur = (t.currency && t.currency !== '') ? t.currency : (this.currency || '$');
        // Rate line: AC always, DC only when this place bills it separately.
        var rateBits = [];
        if (t.acRate > 0) {
            rateBits.push('<span class="mono">' + this._esc(cur) + t.acRate.toFixed(2) + '</span>'
                + ' ' + this._esc(this._t('charge.tariff_ac_short', 'AC')));
        }
        if (t.dcRate > 0) {
            rateBits.push('<span class="mono">' + this._esc(cur) + t.dcRate.toFixed(2) + '</span>'
                + ' ' + this._esc(this._t('charge.tariff_dc_short', 'DC')));
        }
        if (rateBits.length === 0) {
            rateBits.push(this._esc(this._t('charge.tariff_no_rate', 'No rate set')));
        }

        // Sub line: match radius + how often this tariff has actually priced a
        // charge (provenance beats a bare coordinate pair for recognising a place).
        var subBits = [Math.round(t.radiusM || 0) + ' m'];
        if (t.useCount > 0) {
            subBits.push(this._esc(this._t('charge.tariff_used_count',
                t.useCount + '×', { count: t.useCount })));
        }
        if (t.lat != null && t.lng != null) {
            subBits.push(t.lat.toFixed(3) + ', ' + t.lng.toFixed(3));
        }

        var label = (t.label && t.label !== '')
            ? t.label
            : this._t('charge.tariff_unnamed', 'Unnamed tariff');

        var pills = '';
        if (t.id === this.matchedTariffId) {
            pills += '<span class="tariff-pill here">' + this._esc(this._t('charge.tariff_pill_here', 'auto here')) + '</span>';
        }
        if (t.id === this.defaultTariffId) {
            pills += '<span class="tariff-pill default">' + this._esc(this._t('charge.tariff_pill_default', 'default')) + '</span>';
        }

        row.innerHTML =
            '<div class="tariff-info">' +
                '<div class="tariff-name-row">' +
                    '<span class="tariff-name">' + this._esc(label) + '</span>' + pills +
                '</div>' +
                '<div class="tariff-meta">' + rateBits.join(' · ') + '</div>' +
                '<div class="tariff-sub">' + subBits.join(' · ') + '</div>' +
            '</div>' +
            '<div class="tariff-actions">' +
                '<button class="tariff-icon-btn' + (t.id === this.defaultTariffId ? ' on' : '') + '" data-act="default" ' +
                    'title="' + this._esc(this._t('charge.tariff_set_default', 'Use when nothing matches')) + '">' +
                    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>' +
                '</button>' +
                '<button class="tariff-icon-btn" data-act="edit" ' +
                    'title="' + this._esc(this._t('common.edit', 'Edit')) + '">' +
                    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>' +
                '</button>' +
                '<button class="tariff-icon-btn danger" data-act="delete" ' +
                    'title="' + this._esc(this._t('common.delete', 'Delete')) + '">' +
                    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
                '</button>' +
            '</div>';

        // Listeners rather than inline onclick: the id is data, and building
        // handler strings from it invites a quoting bug.
        var btns = row.querySelectorAll('.tariff-icon-btn');
        for (var i = 0; i < btns.length; i++) {
            (function (btn) {
                btn.addEventListener('click', function (e) {
                    e.stopPropagation();
                    var act = btn.getAttribute('data-act');
                    if (act === 'edit') self.openTariffEditor(t);
                    else if (act === 'delete') self.deleteTariff(t);
                    else if (act === 'default') self.setDefaultTariff(t);
                });
            })(btns[i]);
        }
        return row;
    },

    /**
     * Open the add/edit panel. `t` null = create at the current position; an
     * object = edit that tariff (its stored location is kept as-is, so editing a
     * rate from the couch can't move the tariff to wherever the car is parked).
     */
    openTariffEditor: function (t) {
        var editor = document.getElementById('tariffEditor');
        if (!editor) return;
        this._editingTariff = t || null;
        // Creating pins to the CURRENT position, so refresh the snapshot rather than
        // showing whatever fix happened to exist at page load. Async: the chip below
        // renders from the cached value now and _applyTariffs redraws it on arrival.
        if (!t) this.loadTariffs();

        this._setInput('tariffLabelInput', t ? (t.label || '') : '');
        // On CREATE, seed the rates from the existing global settings. Those are
        // the rates the user already pays, so "save a tariff for here" is usually
        // just naming a place — pre-filling saves re-typing a number the app
        // already knows, and the fields stay editable. Left blank when no global
        // rate is configured (nothing to suggest).
        this._setInput('tariffAcRateInput',
            t ? (t.acRate > 0 ? t.acRate : '') : (this.electricityRate > 0 ? this.electricityRate : ''));
        this._setInput('tariffDcRateInput',
            t ? (t.dcRate > 0 ? t.dcRate : '') : (this.dcRate > 0 ? this.dcRate : ''));
        this._setInput('tariffRadiusInput', t ? (t.radiusM || this.TARIFF_DEFAULT_RADIUS_M) : this.TARIFF_DEFAULT_RADIUS_M);

        this._renderTariffLocChip();
        this._setTariffError('');
        editor.style.display = '';
        var labelInput = document.getElementById('tariffLabelInput');
        if (labelInput) { try { labelInput.focus(); } catch (e) {} }
    },

    /**
     * Paint the editor's location chip from the tariff being edited, else the live
     * fix. Factored out so the async loadTariffs() refresh can REPAINT it — the chip
     * previously showed the page-load snapshot while the save sent a newer fix, so
     * the tariff could be pinned somewhere the user never confirmed.
     */
    _renderTariffLocChip: function () {
        var locText = document.getElementById('tariffLocText');
        if (!locText) return;
        var t = this._editingTariff;
        if (t && t.lat != null && t.lng != null) {
            locText.textContent = t.lat.toFixed(5) + ', ' + t.lng.toFixed(5);
        } else if (this.tariffGpsLat != null && this.tariffGpsLng != null) {
            locText.textContent = this.tariffGpsLat.toFixed(5) + ', ' + this.tariffGpsLng.toFixed(5);
        } else {
            locText.textContent = this._t('charge.tariff_no_gps', 'Waiting for GPS…');
        }
    },

    closeTariffEditor: function () {
        var editor = document.getElementById('tariffEditor');
        if (editor) editor.style.display = 'none';
        this._editingTariff = null;
        this._setTariffError('');
    },

    _setTariffError: function (msg) {
        var el = document.getElementById('tariffError');
        if (!el) return;
        if (!msg) { el.style.display = 'none'; el.textContent = ''; return; }
        el.textContent = msg;
        el.style.display = '';
    },

    saveTariff: function () {
        var self = this;
        var editing = this._editingTariff;
        var label = this._getStr('tariffLabelInput').trim();
        var acRate = this._getNum('tariffAcRateInput');
        var dcRate = this._getNum('tariffDcRateInput');
        var radiusM = this._getNum('tariffRadiusInput') || this.TARIFF_DEFAULT_RADIUS_M;

        // Validate before POSTing so the user gets the reason inline. The server
        // re-checks all of this (never trust the client), but failing here avoids
        // a round-trip and keeps the message next to the field at fault.
        //
        // A tariff with no rate would match a charge and then price it at
        // nothing, which reads as a bug. Require at least one rate up front.
        if (acRate <= 0 && dcRate <= 0) {
            this._setTariffError(this._t('charge.tariff_err_no_rate', 'Enter an AC or DC rate'));
            return;
        }
        if (acRate < 0 || dcRate < 0 || acRate >= 100000 || dcRate >= 100000) {
            this._setTariffError(this._t('charge.tariff_err_rate_range',
                'Rates must be between 0 and 100000'));
            return;
        }
        // A radius below GPS scatter can never match; an oversized one would
        // swallow neighbouring sites on different tariffs.
        if (radiusM < 25 || radiusM > 2000) {
            this._setTariffError(this._t('charge.tariff_err_radius',
                'Match radius must be between 25 and 2000 m'));
            return;
        }
        if (label.length > 48) {
            this._setTariffError(this._t('charge.tariff_err_label', 'Label is too long'));
            return;
        }
        // Two tariffs with the same name are indistinguishable in the list and on
        // a session card, which defeats the point of labelling them.
        var dupe = (this.tariffs || []).some(function (t) {
            return t.label && label && t.label.toLowerCase() === label.toLowerCase()
                && (!editing || t.id !== editing.id);
        });
        if (dupe) {
            this._setTariffError(this._t('charge.tariff_err_dupe_label',
                'A tariff with that label already exists'));
            return;
        }
        // No client-side GPS gate. tariffGpsLat is a snapshot taken at page load;
        // gating on it permanently blocked "Add tariff" for anyone who opened the
        // page before the first fix, with no in-page way to refresh. The server
        // takes its OWN live fix at POST time and returns a 400 with a readable
        // reason when it genuinely has none — which saveTariff already renders
        // into #tariffError below.
        this._setTariffError('');

        var body = {
            label: label,
            acRate: acRate,
            dcRate: dcRate,
            radiusM: radiusM,
            currency: this.currency || '$'
        };
        if (editing) {
            body.id = editing.id;
        } else if (this.tariffGpsLat != null && this.tariffGpsLng != null) {
            // Pin to the fix the user was shown in the location chip. Otherwise the
            // server re-reads GPS at POST time and the tariff can land somewhere the
            // user never confirmed (they may have driven off since opening the form).
            body.lat = this.tariffGpsLat;
            body.lng = this.tariffGpsLng;
        }

        var btn = document.getElementById('tariffSaveBtn');
        if (btn) { btn.disabled = true; btn.textContent = this._t('common.saving', 'Saving…'); }
        this._writing = true;

        fetch('/api/charging/tariffs', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function (r) { return r.json(); })
          .then(function (d) {
              self._writing = false;
              if (btn) { btn.disabled = false; btn.textContent = self._t('common.save', 'Save'); }
              if (!d || (!d.success && !d.tariffSaved)) {
                  self._setTariffError((d && d.error) || self._t('charge.tariff_err_save', 'Could not save tariff'));
                  return;
              }
              self.closeTariffEditor();
              // Confirm the automatic behaviour explicitly on save — the whole
              // point of a tariff is that the user never touches it again, so
              // say so once with the actual radius rather than leaving them to
              // infer it.
              if (!editing) {
                  self._toast(self._t('charge.tariff_auto_hint',
                      'Saved. Charges within ' + radiusM + ' m of here will use this tariff automatically.',
                      { radius: radiusM }));
              }
              self._afterTariffChange(d, null, !editing);
          })
          .catch(function () {
              self._writing = false;
              if (btn) { btn.disabled = false; btn.textContent = self._t('common.save', 'Save'); }
              self._setTariffError(self._t('charge.tariff_err_save', 'Could not save tariff'));
          });
    },

    deleteTariff: function (t) {
        var self = this;
        var label = (t.label && t.label !== '') ? t.label : this._t('charge.tariff_unnamed', 'Unnamed tariff');
        var ask = this._t('charge.tariff_confirm_delete', 'Delete this tariff? Charges priced with it will fall back to your other rates.');
        var proceed = function (ok) {
            if (!ok) return;
            self._writing = true;
            fetch('/api/charging/tariffs/delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: t.id })
            }).then(function (r) { return r.json(); })
              .then(function (d) {
                  self._writing = false;
                  if (d && (d.success || d.tariffSaved)) {
                      self._afterTariffChange(d, label);
                  }
                  else self._toast(self._t('charge.tariff_err_delete', 'Could not delete tariff'), 'error');
              })
              .catch(function () {
                  self._writing = false;
                  self._toast(self._t('charge.tariff_err_delete', 'Could not delete tariff'), 'error');
              });
        };
        if (window.BYD && BYD.utils && typeof BYD.utils.confirmDialog === 'function') {
            BYD.utils.confirmDialog({
                title: this._t('common.delete', 'Delete'),
                body: ask,
                confirmLabel: this._t('common.delete', 'Delete'),
                cancelLabel: this._t('common.cancel', 'Cancel'),
                danger: true
            }).then(proceed);
        } else {
            proceed(window.confirm(ask));
        }
    },

    setDefaultTariff: function (t) {
        var self = this;
        // Tapping the star on the current default clears it — one control, both
        // directions, no separate "unset" affordance to discover.
        var next = (t.id === this.defaultTariffId) ? '' : t.id;
        this._writing = true;
        fetch('/api/charging/tariffs/default', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: next })
        }).then(function (r) { return r.json(); })
          .then(function (d) {
              self._writing = false;
              if (d && (d.success || d.tariffSaved)) {
                  self._afterTariffChange(d);
              }
              else self._toast(self._t('charge.tariff_err_save', 'Could not save tariff'), 'error');
          })
          .catch(function () {
              self._writing = false;
              self._toast(self._t('charge.tariff_err_save', 'Could not save tariff'), 'error');
          });
    },

    /**
     * Shared post-mutation refresh. A tariff change re-prices history server-side,
     * so the session list, the summary tiles and the cost hero all have to reload
     * — otherwise the page keeps showing costs at the old rate. `repriced` is
     * surfaced so the user sees that past charges were corrected, not just the
     * next one.
     */
    _afterTariffChange: function (d, deletedLabel, suppressSavedToast) {
        var n = (d && d.repriced) ? d.repriced : 0;
        if (n > 0) {
            // plural() picks one/other; it also returns the raw key on a miss, so
            // guard the same way _t does.
            var msg = null;
            if (window.BYD && BYD.i18n && typeof BYD.i18n.plural === 'function') {
                var pv = BYD.i18n.plural('charge.tariff_repriced', n, { count: n });
                if (pv && pv !== 'charge.tariff_repriced') msg = pv;
            }
            this._toast(msg || (n + (n === 1 ? ' past charge re-priced' : ' past charges re-priced')));
        } else if (deletedLabel) {
            this._toast(this._t('charge.tariff_deleted', 'Tariff deleted'));
        } else if (!suppressSavedToast) {
            // The create path already toasted the auto-apply hint; don't stack a
            // second, less informative "Tariff saved" on top of it.
            this._toast(this._t('charge.tariff_saved', 'Tariff saved'));
        }
        if (d && d.repricingStatus
                && d.repricingStatus !== 'complete') {
            var warning;
            var warningType = 'warning';
            if (d.repricingStatus === 'pending') {
                warning = this._t('charge.tariff_reprice_pending',
                    'Tariff saved. Past charges will be re-priced automatically when storage is ready.');
            } else if (d.repricingStatus === 'failed') {
                warning = (d && d.error) || this._t(
                    'charge.tariff_reprice_failed',
                    'Tariff saved, but past-charge repricing could not be queued.');
                warningType = 'error';
            } else {
                warning = this._t('charge.tariff_reprice_unconfirmed',
                    'Tariff saved, but past-charge repricing could not be confirmed.');
            }
            this._toast(warning, warningType);
        }
        this.loadTariffs();
        this._loadCurrentLivePair();
    },

    // ==================== FORMAT / DOM HELPERS ====================

    _money: function (v) {
        if (v == null) return '--';
        var sym = this.currency || '$';
        var abs = Math.abs(v);
        if (abs > 0 && abs < 1) {
            // Sub-$1 costs: a flat toFixed(2) rounds a small-but-nonzero charge
            // (e.g. a few-cent trickle top-up) to "$0.00" and it visibly vanishes.
            // Use extra precision here, trimming trailing zeros so a cost that's
            // merely small (not tiny) — $0.42 — still reads as a normal 2-decimal
            // amount instead of picking up noise digits.
            var fixed = v.toFixed(5).replace(/0+$/, '').replace(/\.$/, '.0');
            var dot = fixed.indexOf('.');
            if (dot < 0 || fixed.length - dot - 1 < 2) {
                fixed = v.toFixed(2);
            }
            return sym + fixed;
        }
        return sym + v.toFixed(2);
    },

    _dist: function (km) {
        if (window.BYD && BYD.units && typeof BYD.units.dist === 'function') return BYD.units.dist(km);
        return Math.round(km) + ' km';
    },

    _fmtDuration: function (minutes) {
        if (minutes == null) return '--';
        var m = Math.round(minutes);
        if (m < 60) return m + ' min';
        var h = Math.floor(m / 60);
        return h + 'h ' + (m % 60) + 'm';
    },

    _socRangeText: function (session) {
        session = session || {};
        var start = Number(session.startSoc);
        var end = Number(session.endSoc);
        var hasStart = session.startSoc != null
            && isFinite(start) && start >= 0 && start <= 100;
        var hasEnd = session.endSoc != null
            && isFinite(end) && end >= 0 && end <= 100;
        if (!hasStart && !hasEnd) return '--';
        return (hasStart ? Math.round(start) + '%' : '--')
            + ' → '
            + (hasEnd ? Math.round(end) + '%' : '--');
    },

    _fmtDate: function (ts) {
        if (!ts) return '';
        try {
            var d = new Date(ts);
            return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ' ' +
                   d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
        } catch (e) { return ''; }
    },

    _fmtClock: function (ts) {
        try {
            var d = new Date(ts);
            return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
        } catch (e) { return ''; }
    },

    _energyIsApproximate: function (energy) {
        if (!energy) return false;
        if (energy.energyIncomplete === true
                || energy.energyEstimated === true
                || energy.sessionEnergyIncomplete === true
                || energy.sessionEnergyEstimated === true) {
            return true;
        }
        var source = energy.energySource || energy.sessionEnergySource || '';
        if (source !== '') return source !== 'metered_counter';
        return (energy.energyAdded != null && energy.energyAdded > 0)
            || (energy.sessionKwh != null && energy.sessionKwh > 0);
    },

    // Date-only label (MMM D) for day-scale stats charts (SOH trend, cost bars).
    _fmtDay: function (ts) {
        if (!ts) return '';
        try {
            return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
        } catch (e) { return ''; }
    },

    // Classify a session into a SOTA charge tier: 'dc' (DC fast), 'fast'
    // (AC wallbox), 'slow' (AC trickle), or 'unk'. DC is normally authoritative
    // from the gun state; AC is split by peak power. The AC fast/slow cut is 7.2 kW
    // (NOT 7.0): a 7.4 kW single-phase wallbox under load reads ~7 kW and should be
    // "fast", but a 6-7 kW charge should stay "slow" — a hard 7.0 boundary made
    // a 6.x kW charge flicker to "fast" on a transient peak.
    AC_FAST_KW: 7.2,
    DC_KW: 25,
    // Minimum peak a real DC-fast session must reach. DC fast-charging is
    // fundamentally a high-power process (BYD DC ramps well past 25 kW); a session
    // whose measured peak never got near this is physically NOT DC fast, whatever
    // the gun-state flag says. Guards against a HAL gun-state misread (observed:
    // a PHEV AC charge at ~1.7 kW / ~7 kW peak recorded gun=3 → is_dc=1 → labelled
    // "DC fast"). We require peak ≥ 15 kW to honour a DC flag — above the observed
    // false-DC AC profiles while still allowing a short or heavily tapered DC charge
    // whose peak never reaches the power-only 25 kW threshold.
    DC_MIN_PEAK_KW: 15,
    _displayPeakKw: function (s) {
        if (!s) return 0;
        var peak = (s.peakPower != null && s.peakPower > 0) ? s.peakPower : 0;
        // This chip and the detail field are PEAK power, never live or average.
        // A DC-sized value contradicting an explicit AC verdict is poisoned
        // history, so its true peak is unknown and must be omitted rather than
        // relabeling another measurement as peak.
        if (s.powerDataQuality === 'poisoned') return 0;
        return peak;
    },

    _typeKind: function (s) {
        if (!s) return 'unk';
        var peak = (s.peakPower != null && s.peakPower > 0) ? s.peakPower : 0;
        // No-connection and V2L are not charging sessions. Keep legacy/corrupt rows from turning
        // into DC merely because they also contain a stale high power value.
        if (s.gunState === 1 || s.gunState === 5) return 'unk';
        var live = (s.livePowerKw != null && s.livePowerKw > 0) ? s.livePowerKw : 0;
        // A TRUE isDc flag is already peak-guarded: the backend's deriveIsDc only
        // returns 1 when the gun says DC *and* the peak cleared DC_MIN_PEAK_KW, and
        // it is the same call that selects dcRate. Re-testing the peak here was a
        // SECOND, independent application of that guard against a peak that can come
        // from a different source (the served peakPower is the max of CPS samples,
        // while pricing used the in-memory running max) — so a session in the
        // 15..25 kW band could be priced DC yet fall through to the power-only split
        // and render "AC fast". Trust the flag; the guard lives in one place.
        // An explicit AC verdict must also win over a corrupted peak sample.
        // Power-only DC inference is for UNKNOWN gun state, never a way to overrule gun==2.
        if (s.isDc === true) return 'dc';
        if (s.isDc === false) {
            // A peak at/above the DC boundary is physically incompatible with the
            // explicit AC gun verdict. It can be legacy poison from a bad counter
            // sample, so classify an open session from validated live power instead.
            // Completed sessions have no live value and safely fall back to AC slow.
            var acPower = s.powerDataQuality === 'poisoned' ? live : peak;
            return acPower >= this.AC_FAST_KW ? 'fast' : 'slow';
        }
        // Unknown gun state (isDc null: legacy/partial rows, AC_DC, or a
        // peak-downgraded misread) — bucket by power so they still classify sensibly.
        if (peak >= this.DC_KW) return 'dc';
        if (peak >= this.AC_FAST_KW) return 'fast';
        if (peak > 0) return 'slow';
        return 'unk';
    },

    // Effective per-kWh rate for the LIVE in-progress session.
    //
    // PREFER THE SERVER'S OWN NUMBER. chargingRowToJson stamps the open row's
    // `electricityRate` with the result of priceSession(deriveIsDc(...)) — the
    // SAME call that prices the row when it closes — which resolves the
    // LOCATION-TARIFF layer (TariffManager circles) before falling back to the
    // global DC/base rate. Re-deriving the rate here could only ever replicate
    // that fallback: a client-side mirror has no lat/lng, so a charge inside a
    // tariff circle showed the hero cost at the global rate while the card
    // beside it showed the tariff-priced cost, and the mismatch resolved only
    // when the session closed. Reading the served value removes the whole class
    // of drift, and keeps this in step with deriveIsDc automatically — including
    // its I5 rule that an unmeasured peak must NOT be read as "trust the gun".
    //
    // The local fallback below is retained ONLY for the pre-first-sample window,
    // where energyAdded is still 0 so the backend has not stamped a rate yet
    // (and the hero is gated on liveKwh > 0 anyway), and for older daemons.
    _liveRate: function () {
        var s = this._liveSession;
        if (s && s.electricityRate != null && s.electricityRate > 0) return s.electricityRate;
        if (this.dcRate > 0 && s && s.gunState === 3
                && s.peakPower != null && s.peakPower >= this.DC_MIN_PEAK_KW) {
            return this.dcRate;
        }
        return this.electricityRate;
    },

    _typeLabel: function (s) {
        var k = this._typeKind(s);
        if (k === 'dc')   return this._t('charge.type_dc', 'DC fast');
        if (k === 'fast') return this._t('charge.type_fast', 'AC fast');
        if (k === 'slow') return this._t('charge.type_slow', 'AC slow');
        return this._t('charge.type_unknown', 'Charge');
    },

    // SOTA per-tier glyph (inline SVG paths, stroke-based to match the app icons).
    _typeIcon: function (kind) {
        if (kind === 'dc') {
            // Double bolt = DC fast.
            return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 2 L3 13 h5 l-1 9 6-11 H8 z"/><path d="M19 2 l-4 7"/><path d="M21 11 l-3 5"/></svg>';
        }
        if (kind === 'fast') {
            // Single bolt = AC fast (wallbox).
            return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 L4 14 h7 l-1 8 9-12 h-7 z"/></svg>';
        }
        if (kind === 'slow') {
            // Plug = AC slow (trickle).
            return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 2v5M15 2v5"/><path d="M6 7h12v3a6 6 0 0 1-12 0z"/><path d="M12 16v6"/></svg>';
        }
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 L4 14 h7 l-1 8 9-12 h-7 z"/></svg>';
    },

    // Map-pin glyph for the location chip.
    _pinIcon: function () {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s-6-5.5-6-10a6 6 0 0 1 12 0c0 4.5-6 10-6 10z"/><circle cx="12" cy="11" r="2"/></svg>';
    },

    // Human location label for a session: place name if the geocoder resolved
    // one, else rounded coordinates, else '' (chip hidden).
    _locationLabel: function (s) {
        if (s.placeLabel) return s.placeLabel;
        if (s.lat != null && s.lng != null) {
            return s.lat.toFixed(3) + ', ' + s.lng.toFixed(3);
        }
        return '';
    },

    // Minimal HTML escape for interpolated text (place names can contain & < >).
    // Escapes for BOTH text nodes and quoted attribute values. Quotes matter:
    // tariff labels are user text and are interpolated into title="..." on the
    // row action buttons, so a label containing " would otherwise break out of
    // the attribute and inject markup.
    _esc: function (str) {
        if (str == null) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                          .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    },

    _rgba: function (color, alpha) {
        // Accepts #rrggbb or rgb()/rgba(); returns rgba with the given alpha.
        if (!color) return 'rgba(14,165,233,' + alpha + ')';
        if (color.charAt(0) === '#') {
            var hex = color.substring(1);
            if (hex.length === 3) hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
            var r = parseInt(hex.substring(0, 2), 16);
            var g = parseInt(hex.substring(2, 4), 16);
            var b = parseInt(hex.substring(4, 6), 16);
            if (isNaN(r) || isNaN(g) || isNaN(b)) return 'rgba(14,165,233,' + alpha + ')';
            return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
        }
        if (color.indexOf('rgba') === 0) return color.replace(/[\d.]+\s*\)$/, alpha + ')');
        if (color.indexOf('rgb') === 0) return color.replace('rgb', 'rgba').replace(')', ',' + alpha + ')');
        return color;
    },

    // BYD.i18n.t() has THREE returns: the translation, null while the catalog is
    // still loading, or THE RAW KEY when the key is missing from both the active
    // and the en catalog. A bare truthiness test treats that raw key as a hit, so
    // the caller's fallback is dead and the UI renders "charge.tariff_pill_here".
    // Reject the sentinel explicitly. `vars` forwards {placeholder} values.
    _t: function (key, fallback, vars) {
        if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
            var v = BYD.i18n.t(key, vars);
            if (v && v !== key) return v;
        }
        return fallback;
    },

    _toast: function (msg, type) {
        if (window.BYD && BYD.utils && typeof BYD.utils.toast === 'function') BYD.utils.toast(msg, type || 'success');
    },

    _showSkeleton: function () {
        var skel = document.getElementById('sessionListSkeleton');
        if (skel) skel.style.display = '';
    },
    _hideSkeleton: function () {
        var skel = document.getElementById('sessionListSkeleton');
        if (skel) skel.style.display = 'none';
    },

    _setText: function (id, value) {
        var el = document.getElementById(id);
        if (el) el.textContent = (value == null ? '--' : value);
    },
    _setInput: function (id, value) {
        var el = document.getElementById(id);
        if (el) el.value = (value == null ? '' : value);
    },
    _setVal: function (id, value, isCheckbox) {
        var el = document.getElementById(id);
        if (!el) return;
        if (isCheckbox) el.checked = !!value;
        else el.value = value;
    },
    _getChecked: function (id) {
        var el = document.getElementById(id);
        return el ? !!el.checked : false;
    },
    _getNum: function (id) {
        var el = document.getElementById(id);
        if (!el) return 0;
        var n = parseFloat(el.value);
        return isNaN(n) ? 0 : n;
    },
    _getStr: function (id) {
        var el = document.getElementById(id);
        return el ? (el.value || '') : '';
    }
};
// CHARGING.init() is called from charging.html after BYD.i18n.init() resolves
// (mirrors the trips.html boot order so the shared core/i18n are ready).
