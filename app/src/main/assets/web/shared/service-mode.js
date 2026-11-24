(function () {
    'use strict';

    const page = {
        apps: [],
        selectedPackages: new Set(),
        dateInitialized: false,
        busy: false,
        statusTimer: null,
        lastStatus: null,
        appsLoaded: false,

        async request(path, options) {
            const response = await fetch(path, Object.assign({
                cache: 'no-store',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' }
            }, options || {}));
            let data = {};
            try { data = await response.json(); } catch (_) {}
            if (!response.ok || data.success === false) {
                throw new Error(data.error || ('HTTP ' + response.status));
            }
            return data;
        },

        byId(id) { return document.getElementById(id); },

        // Isolated value, so RTL text can't reorder dates, packages or paths.
        fill(node, template, token, value, className) {
            const parts = String(template).split(token);
            const isolated = document.createElement('bdi');
            if (className) isolated.className = className;
            isolated.textContent = value;
            node.textContent = parts[0];
            node.appendChild(isolated);
            node.appendChild(document.createTextNode(parts.slice(1).join(token)));
        },

        tk(fullKey, fallback) {
            const translated = window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function'
                ? BYD.i18n.t(fullKey) : null;
            return typeof translated === 'string' && translated && translated !== fullKey
                ? translated : fallback;
        },

        t(key, fallback) { return this.tk('service_mode.' + key, fallback); },

        notify(text, type) {
            if (window.BYD && BYD.core && typeof BYD.core.toast === 'function') {
                BYD.core.toast(text, type || 'info');
            }
        },

        onLocaleChange() {
            if (this.lastStatus) this.renderStatus(this.lastStatus);
            if (this.appsLoaded) this.renderApps();
            else this.renderAppsNote(this.t('loading_apps', 'Loading installed apps…'));
        },

        addOneDay(dateValue) {
            const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(dateValue || ''));
            if (!match) return '';
            const date = new Date(Date.UTC(
                Number(match[1]), Number(match[2]) - 1, Number(match[3])));
            date.setUTCDate(date.getUTCDate() + 1);
            return date.toISOString().slice(0, 10);
        },

        formatDateTime(timestamp, timeZone) {
            if (!timestamp) return '';
            try {
                return new Date(timestamp).toLocaleString(document.documentElement.lang || undefined, {
                    year: 'numeric', month: 'short', day: 'numeric',
                    hour: 'numeric', minute: '2-digit',
                    timeZone: timeZone || undefined
                });
            } catch (_) {
                return new Date(timestamp).toLocaleString();
            }
        },

        // One setting row: name, optional isolated detail line, optional control.
        row(name, detail, control) {
            const row = document.createElement('div');
            row.className = 'setting-row';
            const info = document.createElement('div');
            info.className = 'setting-info';
            const title = document.createElement('div');
            title.className = 'setting-name';
            title.textContent = name;
            info.appendChild(title);
            if (detail) {
                const desc = document.createElement('div');
                desc.className = 'setting-desc';
                const isolated = document.createElement('bdi');
                isolated.className = 'sm-path';
                isolated.textContent = detail;
                desc.appendChild(isolated);
                info.appendChild(desc);
            }
            row.appendChild(info);
            if (control) {
                const holder = document.createElement('div');
                holder.className = 'setting-control';
                holder.appendChild(control);
                row.appendChild(holder);
            }
            return row;
        },

        renderAppsNote(text) {
            const container = this.byId('smApps');
            container.textContent = '';
            const note = document.createElement('div');
            note.className = 'setting-desc sm-note';
            note.textContent = text;
            container.appendChild(note);
        },

        async loadApps() {
            const container = this.byId('smApps');
            container.querySelectorAll('input[data-package]:checked').forEach(input => {
                this.selectedPackages.add(input.getAttribute('data-package'));
            });
            this.renderAppsNote(this.t('loading_apps', 'Loading installed apps…'));
            try {
                const data = await this.request('/api/service-mode/apps');
                this.apps = Array.isArray(data.apps) ? data.apps : [];
                this.appsLoaded = true;
                // A selection that is no longer listed can't be started.
                const listed = new Set(this.apps.map(app => app && app.package));
                Array.from(this.selectedPackages).forEach(pkg => {
                    if (!listed.has(pkg)) this.selectedPackages.delete(pkg);
                });
                this.renderApps();
            } catch (error) {
                const text = error.message || this.t('apps_failed', 'Could not load installed apps.');
                this.renderAppsNote(text);
                this.notify(text, 'error');
            }
        },

        renderApps() {
            const container = this.byId('smApps');
            container.textContent = '';
            if (!this.apps.length) {
                this.renderAppsNote(this.t('no_apps', 'No eligible user-installed launchable apps were found.'));
                return;
            }
            this.apps.forEach(app => {
                if (!app || !app.package) return;
                const toggle = document.createElement('label');
                toggle.className = 'toggle-switch';
                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.setAttribute('data-package', app.package);
                checkbox.setAttribute('aria-label', app.label || app.package);
                checkbox.checked = this.selectedPackages.has(app.package);
                checkbox.addEventListener('change', () => {
                    if (checkbox.checked) this.selectedPackages.add(app.package);
                    else this.selectedPackages.delete(app.package);
                });
                const slider = document.createElement('span');
                slider.className = 'toggle-slider';
                toggle.appendChild(checkbox);
                toggle.appendChild(slider);
                container.appendChild(this.row(app.label || app.package, app.package, toggle));
            });
        },

        renderStatus(status) {
            this.lastStatus = status;
            const active = !!status.active;
            const restoring = !!status.restoring;
            const packages = status.hiddenPackages || [];
            const features = status.disabledFeatures || [];

            this.byId('smHero').className = 'r-hero sm-hero' + (active || restoring ? '' : ' is-off');
            const badge = this.byId('smStatusBadge');
            badge.className = 'status-badge r-hero-ctl ' + (active ? 'active'
                : (restoring && status.lastError ? 'errored' : 'inactive'));
            badge.textContent = active ? this.tk('status.on', 'ON')
                : (restoring ? this.t('badge_restoring', 'Restoring') : this.tk('status.off', 'OFF'));

            const title = this.byId('smStatusTitle');
            const meta = this.byId('smStatusMeta');
            if (active) {
                title.textContent = this.t('status_active', 'Service Mode is active');
                this.fill(meta, this.t('status_until', 'Automatic restoration: {date}'), '{date}',
                    this.formatDateTime(status.endsAtEpochMs, status.timeZoneId));
            } else if (restoring) {
                title.textContent = this.t('status_restoring', 'Restoring apps and features…');
                meta.textContent = this.t('status_restoring_desc', 'The daemon is retrying any item that has not yet been restored.');
            } else {
                title.textContent = this.t('status_inactive', 'Service Mode is off');
                meta.textContent = this.t('hero_desc', 'Temporarily hide selected third-party apps during a dealer visit. OverDrive remains available.');
            }

            const labels = {
                automations: this.t('feature_automations', 'Automations'),
                keymap: this.t('feature_keymap', 'Key Mapping'),
                proximity: this.t('feature_proximity', 'Proximity'),
                roadsense: this.t('feature_roadsense', 'RoadSense'),
                widgets: this.t('feature_widgets', 'Widgets'),
                seat_positions: this.t('feature_seat_positions', 'Seat Positions')
            };
            this.byId('smStats').hidden = !active;
            this.byId('smStatApps').textContent = String(packages.length);
            this.byId('smStatFeatures').textContent = features.length
                ? features.map(feature => labels[feature] || feature).join(', ')
                : this.tk('common.none', 'None');

            this.byId('smActiveCard').hidden = !(active || restoring);
            const details = this.byId('smActiveDetails');
            details.textContent = '';
            packages.forEach(pkg => details.appendChild(this.row(pkg)));

            const errorBox = this.byId('smRestoreError');
            errorBox.hidden = !status.lastError;
            errorBox.querySelector('.info-note').textContent = status.lastError || '';

            const hint = this.byId('smRecoveryHint');
            hint.hidden = !(status.enabled && packages.length);
            this.fill(hint.querySelector('.info-note'), this.t('manual_recovery',
                'If OverDrive is uninstalled before restoration, use a trusted ADB shell to run {command}. Do not execute the editable Downloads copy.'),
                '{command}', 'sh ' + (status.rescueScriptPath || '/data/local/tmp/overdrive-service-mode-rescue.sh'), 'sm-path');

            this.byId('smEndActions').hidden = !active;
            this.byId('smEndButton').disabled = this.busy;
            const setupVisible = !active && !restoring;
            this.byId('smSetup').hidden = !setupVisible;
            this.byId('smStartBar').hidden = !setupVisible;
        },

        async loadStatus() {
            const status = await this.request('/api/service-mode/status');
            const date = this.byId('smEndDate');
            if (status.serverLocalDate) {
                date.min = status.serverLocalDate;
                if (!this.dateInitialized) {
                    date.value = this.addOneDay(status.serverLocalDate);
                    this.dateInitialized = true;
                } else if (!date.value || date.value < status.serverLocalDate) {
                    date.value = this.addOneDay(status.serverLocalDate);
                }
            }
            this.renderStatus(status);
            this.syncStatusTimer(status);
        },

        syncStatusTimer(status) {
            const shouldPoll = !!(status && (status.active || status.restoring));
            if (shouldPoll && !this.statusTimer) {
                this.statusTimer = window.setInterval(() => {
                    if (!this.busy) this.loadStatus().catch(() => {});
                }, 15000);
            } else if (!shouldPoll && this.statusTimer) {
                window.clearInterval(this.statusTimer);
                this.statusTimer = null;
            }
        },

        setBusy(value) {
            this.busy = value;
            this.byId('smStartButton').disabled = value;
            this.byId('smEndButton').disabled = value;
            this.byId('smRefreshApps').disabled = value;
        },

        async start() {
            const endDate = this.byId('smEndDate').value;
            if (!/^\d{4}-\d{2}-\d{2}$/.test(endDate)) {
                this.notify(this.t('choose_end_date', 'Choose a valid end date.'), 'error');
                return;
            }

            const disabledFeatures = [];
            if (this.byId('smPauseAutomations').checked) disabledFeatures.push('automations');
            if (this.byId('smPauseKeymap').checked) disabledFeatures.push('keymap');
            if (this.byId('smPauseProximity').checked) disabledFeatures.push('proximity');
            if (this.byId('smPauseRoadSense').checked) disabledFeatures.push('roadsense');
            if (this.byId('smPauseWidgets').checked) disabledFeatures.push('widgets');
            if (this.byId('smPauseSeatPositions').checked) disabledFeatures.push('seat_positions');

            const payload = {
                endDate: endDate,
                hiddenPackages: Array.from(this.selectedPackages),
                disabledFeatures: disabledFeatures
            };

            this.setBusy(true);
            this.notify(this.t('starting', 'Starting Service Mode…'), 'info');
            try {
                const result = await this.request('/api/service-mode/start', {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                if (!result.success) throw new Error(result.error || 'Could not start Service Mode.');
                this.notify(this.t('started', 'Service Mode started.'), 'success');
                await this.loadStatus();
            } catch (error) {
                this.notify(error.message || this.t('start_failed', 'Could not start Service Mode.'), 'error');
                try { await this.loadStatus(); } catch (_) {}
            } finally {
                this.setBusy(false);
            }
        },

        async end() {
            this.setBusy(true);
            this.notify(this.t('status_restoring', 'Restoring apps and features…'), 'info');
            try {
                const result = await this.request('/api/service-mode/end', {
                    method: 'POST',
                    body: '{}'
                });
                if (!result.success) throw new Error(result.error || 'Restoration is still pending.');
                this.notify(this.t('ended', 'Service Mode ended. Apps and features have been restored.'), 'success');
                await this.loadStatus();
            } catch (error) {
                this.notify(error.message || this.t('end_failed', 'Could not restore everything yet.'), 'error');
                try { await this.loadStatus(); } catch (_) {}
            } finally {
                this.setBusy(false);
            }
        },

        async init() {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
                BYD.i18n.onChange(() => this.onLocaleChange());
            }
            this.byId('smStartButton').addEventListener('click', () => this.start());
            this.byId('smEndButton').addEventListener('click', () => this.end());
            this.byId('smRefreshApps').addEventListener('click', () => this.loadApps());
            try {
                await Promise.all([this.loadStatus(), this.loadApps()]);
            } catch (error) {
                this.notify(error.message || this.t('start_failed', 'Could not start Service Mode.'), 'error');
            }
            // loadStatus owns the one polling timer; never lose its handle.
        }
    };

    document.addEventListener('DOMContentLoaded', () => page.init());
})();
