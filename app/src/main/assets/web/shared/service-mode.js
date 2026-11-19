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
        lastLocalizedMessage: null,

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

        t(key, fallback) {
            const fullKey = 'service_mode.' + key;
            const translated = window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function'
                ? BYD.i18n.t(fullKey) : null;
            return typeof translated === 'string' && translated && translated !== fullKey
                ? translated : fallback;
        },

        message(text, isError, key, fallback) {
            this.lastLocalizedMessage = key ? { key: key, fallback: fallback, isError: !!isError } : null;
            const node = this.byId('smMessage');
            node.textContent = text || '';
            node.style.color = isError ? 'var(--error, #b3261e)' : 'var(--on-surface-variant)';
        },

        onLocaleChange() {
            // These nodes are rendered with textContent rather than data-i18n.
            // Keep the last HTTP snapshot so a catalog load or a language switch
            // can translate immediately without requiring another request.
            if (this.lastStatus) this.renderStatus(this.lastStatus);
            if (this.appsLoaded) this.renderApps();
            else if (!this.byId('smSetup').hidden) {
                this.byId('smApps').textContent = this.t('loading_apps', 'Loading installed apps…');
            }
            if (this.lastLocalizedMessage) {
                const msg = this.lastLocalizedMessage;
                this.message(this.t(msg.key, msg.fallback), msg.isError,
                    msg.key, msg.fallback);
            }
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
                return new Date(timestamp).toLocaleString(undefined, {
                    year: 'numeric', month: 'short', day: 'numeric',
                    hour: 'numeric', minute: '2-digit',
                    timeZone: timeZone || undefined
                });
            } catch (_) {
                return new Date(timestamp).toLocaleString();
            }
        },

        async loadApps() {
            const container = this.byId('smApps');
            if (container) {
                container.querySelectorAll('input[data-package]:checked').forEach(input => {
                    this.selectedPackages.add(input.getAttribute('data-package'));
                });
                container.textContent = this.t('loading_apps', 'Loading installed apps…');
            }
            try {
                const data = await this.request('/api/service-mode/apps');
                this.apps = Array.isArray(data.apps) ? data.apps : [];
                this.appsLoaded = true;
                this.renderApps();
            } catch (error) {
                container.textContent = error.message || 'Could not load installed apps.';
                this.message(error.message || this.t('apps_failed', 'Could not load installed apps.'), true);
            }
        },

        renderApps() {
            const container = this.byId('smApps');
            container.textContent = '';
            if (!this.apps.length) {
                const empty = document.createElement('div');
                empty.className = 'sm-empty';
                empty.textContent = this.t('no_apps', 'No eligible user-installed launchable apps were found.');
                container.appendChild(empty);
                return;
            }
            this.apps.forEach(app => {
                if (!app || !app.package) return;
                const row = document.createElement('label');
                row.className = 'sm-app-row';

                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.setAttribute('data-package', app.package);
                checkbox.checked = this.selectedPackages.has(app.package);
                checkbox.addEventListener('change', () => {
                    if (checkbox.checked) this.selectedPackages.add(app.package);
                    else this.selectedPackages.delete(app.package);
                });

                const copy = document.createElement('span');
                copy.className = 'sm-app-copy';
                const label = document.createElement('span');
                label.className = 'sm-app-label';
                label.textContent = app.label || app.package;
                const pkg = document.createElement('span');
                pkg.className = 'sm-app-package';
                pkg.textContent = app.package;
                copy.appendChild(label);
                copy.appendChild(pkg);
                row.appendChild(checkbox);
                row.appendChild(copy);
                container.appendChild(row);
            });
        },

        renderStatus(status) {
            this.lastStatus = status;
            this.byId('smRecoveryHint').hidden = !(
                status.enabled && (status.hiddenPackages || []).length);
            const dot = this.byId('smStatusDot');
            const title = this.byId('smStatusTitle');
            const meta = this.byId('smStatusMeta');
            const details = this.byId('smActiveDetails');
            const endActions = this.byId('smEndActions');
            const setup = this.byId('smSetup');
            const errorBox = this.byId('smRestoreError');
            const endButton = this.byId('smEndButton');

            const active = !!status.active;
            const restoring = !!status.restoring;
            dot.className = 'sm-dot' + (active ? ' active' : (restoring ? ' restoring' : ''));
            details.textContent = '';
            errorBox.hidden = !status.lastError;
            errorBox.textContent = status.lastError || '';

            if (active) {
                title.textContent = this.t('status_active', 'Service Mode is active');
                meta.textContent = this.t('status_until', 'Automatic restoration: {date}').replace('{date}',
                    this.formatDateTime(status.endsAtEpochMs, status.timeZoneId));
                const rows = [];
                (status.hiddenPackages || []).forEach(pkg => rows.push(this.t('hidden_app', 'Hidden app: {package}').replace('{package}', pkg)));
                const labels = { automations: this.t('feature_automations', 'Automations'), keymap: this.t('feature_keymap', 'Key Mapping') };
                (status.disabledFeatures || []).forEach(feature => {
                    rows.push(this.t('paused_feature', 'Paused feature: {feature}').replace('{feature}', labels[feature] || feature));
                });
                rows.forEach(value => {
                    const item = document.createElement('li');
                    item.textContent = value;
                    details.appendChild(item);
                });
                details.hidden = rows.length === 0;
                endActions.hidden = false;
                endButton.disabled = this.busy;
                setup.hidden = true;
            } else if (restoring) {
                title.textContent = this.t('status_restoring', 'Restoring apps and features…');
                meta.textContent = this.t('status_restoring_desc', 'The daemon is retrying any item that has not yet been restored.');
                details.hidden = true;
                endActions.hidden = true;
                setup.hidden = true;
            } else {
                title.textContent = this.t('status_inactive', 'Service Mode is off');
                meta.textContent = this.t('status_inactive_desc', 'Your apps and OverDrive are available as usual.');
                details.hidden = true;
                endActions.hidden = true;
                setup.hidden = false;
            }
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
                    if (!this.busy) this.loadStatus().catch(error => {
                        this.message(error.message || 'Could not refresh Service Mode status.', true);
                    });
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
                this.message(this.t('choose_end_date', 'Choose a valid end date.'),
                    true, 'choose_end_date', 'Choose a valid end date.');
                return;
            }

            const disabledFeatures = [];
            if (this.byId('smPauseAutomations').checked) disabledFeatures.push('automations');
            if (this.byId('smPauseKeymap').checked) disabledFeatures.push('keymap');

            const payload = {
                endDate: endDate,
                hiddenPackages: Array.from(this.selectedPackages),
                disabledFeatures: disabledFeatures
            };

            this.setBusy(true);
            this.message(this.t('starting', 'Starting Service Mode…'),
                false, 'starting', 'Starting Service Mode…');
            try {
                const result = await this.request('/api/service-mode/start', {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                if (!result.success) throw new Error(result.error || 'Could not start Service Mode.');
                this.message(this.t('started', 'Service Mode started.'),
                    false, 'started', 'Service Mode started.');
                await this.loadStatus();
            } catch (error) {
                this.message(error.message || this.t('start_failed', 'Could not start Service Mode.'), true);
                try { await this.loadStatus(); } catch (_) {}
            } finally {
                this.setBusy(false);
            }
        },

        async end() {
            this.setBusy(true);
            this.message(this.t('status_restoring', 'Restoring apps and features…'),
                false, 'status_restoring', 'Restoring apps and features…');
            try {
                const result = await this.request('/api/service-mode/end', {
                    method: 'POST',
                    body: '{}'
                });
                if (!result.success) throw new Error(result.error || 'Restoration is still pending.');
                this.message(this.t('ended', 'Service Mode ended. Saved app states have been restored.'),
                    false, 'ended', 'Service Mode ended. Saved app states have been restored.');
                await this.loadStatus();
            } catch (error) {
                this.message(error.message || this.t('end_failed', 'Could not restore everything yet.'), true);
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
                this.message(error.message || this.t('start_failed', 'Could not initialize Service Mode.'), true);
            }
            // loadStatus owns the one polling timer; never lose its handle.
        }
    };

    document.addEventListener('DOMContentLoaded', () => page.init());
})();
