(function () {
    'use strict';

    const page = {
        apps: [],
        selectedPackages: new Set(),
        dateInitialized: false,
        busy: false,

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

        message(text, isError) {
            const node = this.byId('smMessage');
            node.textContent = text || '';
            node.style.color = isError ? 'var(--error, #b3261e)' : 'var(--on-surface-variant)';
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
                container.textContent = 'Loading installed apps…';
            }
            try {
                const data = await this.request('/api/service-mode/apps');
                this.apps = Array.isArray(data.apps) ? data.apps : [];
                this.renderApps();
            } catch (error) {
                container.textContent = error.message || 'Could not load installed apps.';
                this.message(error.message || 'Could not load installed apps.', true);
            }
        },

        renderApps() {
            const container = this.byId('smApps');
            container.textContent = '';
            if (!this.apps.length) {
                const empty = document.createElement('div');
                empty.className = 'sm-empty';
                empty.textContent = 'No eligible user-installed launchable apps were found.';
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
                title.textContent = 'Service Mode is active';
                meta.textContent = 'Automatic restoration: ' +
                    this.formatDateTime(status.endsAtEpochMs, status.timeZoneId);
                const rows = [];
                if (status.hideOverdrive) rows.push('OverDrive launcher entry is hidden');
                (status.hiddenPackages || []).forEach(pkg => rows.push('Hidden app: ' + pkg));
                const labels = { automations: 'Automations', keymap: 'Key Mapping' };
                (status.disabledFeatures || []).forEach(feature => {
                    rows.push('Paused feature: ' + (labels[feature] || feature));
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
                title.textContent = 'Restoring apps and features…';
                meta.textContent = 'The daemon is retrying any item that has not yet been restored.';
                details.hidden = true;
                endActions.hidden = true;
                setup.hidden = true;
            } else {
                title.textContent = 'Service Mode is off';
                meta.textContent = 'Your apps and OverDrive are available as usual.';
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
                this.message('Choose a valid end date.', true);
                return;
            }

            const disabledFeatures = [];
            if (this.byId('smPauseAutomations').checked) disabledFeatures.push('automations');
            if (this.byId('smPauseKeymap').checked) disabledFeatures.push('keymap');

            const payload = {
                endDate: endDate,
                hideOverdrive: this.byId('smHideOverdrive').checked,
                hiddenPackages: Array.from(this.selectedPackages),
                disabledFeatures: disabledFeatures
            };

            this.setBusy(true);
            this.message('Starting Service Mode…');
            try {
                const result = await this.request('/api/service-mode/start', {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                if (!result.success) throw new Error(result.error || 'Could not start Service Mode.');
                this.message('Service Mode started.');
                await this.loadStatus();
            } catch (error) {
                this.message(error.message || 'Could not start Service Mode.', true);
                try { await this.loadStatus(); } catch (_) {}
            } finally {
                this.setBusy(false);
            }
        },

        async end() {
            this.setBusy(true);
            this.message('Restoring apps and features…');
            try {
                const result = await this.request('/api/service-mode/end', {
                    method: 'POST',
                    body: '{}'
                });
                if (!result.success) throw new Error(result.error || 'Restoration is still pending.');
                this.message('Service Mode ended. Saved app states have been restored.');
                await this.loadStatus();
            } catch (error) {
                this.message(error.message || 'Could not restore everything yet.', true);
                try { await this.loadStatus(); } catch (_) {}
            } finally {
                this.setBusy(false);
            }
        },

        async init() {
            this.byId('smStartButton').addEventListener('click', () => this.start());
            this.byId('smEndButton').addEventListener('click', () => this.end());
            this.byId('smRefreshApps').addEventListener('click', () => this.loadApps());
            try {
                await Promise.all([this.loadStatus(), this.loadApps()]);
            } catch (error) {
                this.message(error.message || 'Could not initialize Service Mode.', true);
            }
            window.setInterval(() => {
                if (!this.busy) this.loadStatus().catch(error => {
                    this.message(error.message || 'Could not refresh Service Mode status.', true);
                });
            }, 15000);
        }
    };

    document.addEventListener('DOMContentLoaded', () => page.init());
})();
