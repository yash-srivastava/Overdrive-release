/* Dashboard summary for the existing read-only module-faults endpoint. */
(function (window) {
    'use strict';

    var POLL_INTERVAL_MS = 60000;
    var dashboard = {
        timer: null,
        request: null,
        initialized: false,

        t: function (key, fallback, params) {
            try {
                var fullKey = 'moduleFaults.' + key;
                var value = window.BYD && BYD.i18n && BYD.i18n.t
                    ? BYD.i18n.t(fullKey, params) : null;
                return value && value !== fullKey ? value : fallback;
            } catch (_) { return fallback; }
        },

        count: function (n) {
            var fallback = n === 1 ? '1 module warning' : n + ' module warnings';
            try {
                var key = 'moduleFaults.dashboard_warning_count';
                var value = window.BYD && BYD.i18n && BYD.i18n.plural ? BYD.i18n.plural(key, n, { count: n }) : null;
                return value && value !== key ? value : fallback;
            } catch (_) { return fallback; }
        },

        init: function () {
            if (this.initialized) return;
            this.initialized = true;
            this.refresh();
            this.timer = window.setInterval(function () {
                if (!document.hidden) dashboard.refresh();
            }, POLL_INTERVAL_MS);
            document.addEventListener('visibilitychange', function () {
                if (!document.hidden) dashboard.refresh();
            });
        },

        refresh: function () {
            if (this.request) return this.request;
            var self = this;
            this.request = window.fetch('/api/diagnostics/module-faults', {
                method: 'GET',
                cache: 'no-store',
                headers: { 'Accept': 'application/json' }
            }).then(function (response) {
                return response.json().then(function (payload) {
                    if (!response.ok || !payload || payload.success !== true) {
                        throw new Error('Module-fault diagnostics unavailable');
                    }
                    self.render(payload);
                });
            }).catch(function () {
                // A failed/stale diagnostics request must not imply that the
                // vehicle is fault-free. Keep the last known warning visible.
            }).then(function () {
                self.request = null;
            }, function () {
                self.request = null;
            });
            return this.request;
        },

        render: function (payload) {
            var card = document.getElementById('dashModuleFaults');
            var countNode = document.getElementById('dashModuleFaultsCount');
            var summaryNode = document.getElementById('dashModuleFaultsSummary');
            var listNode = document.getElementById('dashModuleFaultsList');
            if (!card || !countNode || !summaryNode || !listNode) return;

            var modules = Array.isArray(payload.modules) ? payload.modules : [];
            var warnings = modules.filter(function (module) {
                return module && module.status === 'warning';
            });

            // The API summary is informational only; derive visibility from
            // explicit per-module warning states so partial/unscanned modules
            // can never be presented as faults.
            if (!warnings.length) {
                card.hidden = true;
                return;
            }

            countNode.textContent = String(warnings.length);
            summaryNode.textContent = this.count(warnings.length);
            while (listNode.firstChild) listNode.removeChild(listNode.firstChild);
            warnings.forEach(function (module) {
                var item = document.createElement('li');
                item.textContent = String(module.name || module.id || 'Module');
                var readings = Array.isArray(module.readings) ? module.readings : [];
                var details = readings.filter(function (reading) {
                    return reading && reading.status === 'warning';
                }).map(function (reading) {
                    return reading.label || reading.id;
                }).filter(Boolean);
                if (details.length) item.textContent += ': ' + details.join(', ');
                listNode.appendChild(item);
            });
            card.hidden = false;
        }
    };

    window.BYD = window.BYD || {};
    window.BYD.moduleFaultsDashboard = dashboard;
}(window));
