(function () {
    'use strict';

    const ServiceModePage = {
        apps: [],
        selectedPackages: new Set(),
        dateInitializedFromServer: false,

        t(key, fallback) {
            const value = window.BYD && BYD.i18n && BYD.i18n.t
                ? BYD.i18n.t(key) : null;
            return value && value !== key ? value : fallback;
        },

        async request(path, options) {
            const response = await fetch(path, Object.assign({
                cache: 'no-store',
                credentials: 'same-origin'
            }, options || {}));
            let data = {};
            try { data = await response.json(); } catch (_) {}
            if (!response.ok) {
                throw new Error((data && data.error) || ('HTTP ' + response.status));
            }
            return data;
        },

        async loadApps() {
            const container = document.getElementById('smApps');
            if (container) {
                this.selectedPackages = new Set(Array.from(
                    container.querySelectorAll('input[data-package]:checked')
                ).map(input => input.getAttribute('data-package')));
                container.innerHTML = '';
                const loading = document.createElement('div');
                loading.className = 'sm-empty';
                loading.textContent = this.t('service_mode.loading_apps', 'Loading installed apps…');
                container.appendChild(loading);
            }
            try {
                const data = await this.request('/api/service-mode/apps');
                if (!data.success) throw new Error(data.error || 'Could not load installed apps.');
                this.apps = Array.isArray(data.apps) ? data.apps : [];
                this.renderApps();
            } catch (error) {
                if (container) {
                    container.innerHTML = '';
                    const empty = document.createElement('div');
                    empty.className = 'sm-empty';
                    empty.textContent = error.message || this.t('service_mode.apps_failed', 'Could not load installed apps.');
                    container.appendChild(empty);
                }
                this.toast(error.message || this.t('service_mode.apps_failed', 'Could not load installed apps.'), 'error');
            }
        },

        renderApps() {
            const container = document.getElementById('smApps');
            if (!container) return;
            const selected = this.selectedPackages || new Set();

            container.innerHTML = '';
            if (!this.apps.length) {
                const empty = document.createElement('div');
                empty.className = 'sm-empty';
                empty.textContent = this.t(
                    'service_mode.no_apps',
                    'No user-installed launchable apps were found.'
                );
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
                checkbox.checked = selected.has(app.package);

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

        localDateValue(date) {
            const year = date.getFullYear();
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const day = String(date.getDate()).padStart(2, '0');
            return year + '-' + month + '-' + day;
        },

        selectedEndDate(value) {
            const date = String(value || '');
            if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
                throw new Error(this.t('service_mode.choose_end_date', 'Choose a valid end date.'));
            }
            return date;
        },

        addOneDay(dateValue) {
            const parts = String(dateValue || '').split('-').map(Number);
            if (parts.length !== 3 || parts.some(part => !Number.isFinite(part))) return '';
            const date = new Date(parts[0], parts[1] - 1, parts[2], 12, 0, 0, 0);
            date.setDate(date.getDate() + 1);
            return this.localDateValue(date);
        },

        formatDateTime(timestamp, timeZone) {
            if (!timestamp) return '';
            try {
                return new Date(timestamp).toLocaleString(
                    (window.BYD && BYD.i18n && BYD.i18n.getLang)
                        ? BYD.i18n.getLang() : undefined,
                    {
                        year: 'numeric', month: 'short', day: 'numeric',
                        hour: 'numeric', minute: '2-digit',
                        timeZone: timeZone || undefined
                    }
                );
            } catch (_) {
                return new Date(timestamp).toLocaleString();
            }
        },

        renderStatus(status) {
            const dot = document.getElementById('smStatusDot');
            const title = document.getElementById('smStatusTitle');
            const meta = document.getElementById('smStatusMeta');
            const details = document.getElementById('smActiveDetails');
            const endActions = document.getElementById('smEndActions');
            const setup = document.getElementById('smSetup');
            const errorBox = document.getElementById('smRestoreError');
            const endButton = document.getElementById('smEndButton');

            const active = !!status.active;
            const restoring = !!status.restoring;
            dot.className = 'sm-status-dot' + (active ? ' active' : (restoring ? ' restoring' : ''));
            details.innerHTML = '';
            errorBox.hidden = !status.lastError;
            errorBox.textContent = status.lastError || '';

            if (active) {
                title.textContent = this.t('service_mode.status_active', 'Service Mode is active');
                meta.textContent = this.t('service_mode.status_until', 'Automatic restoration: {date}')
                    .replace('{date}', this.formatDateTime(status.endsAtEpochMs, status.timeZoneId));
                const rows = [];
                if (status.hideOverdrive) {
                    rows.push(this.t('service_mode.detail_overdrive', 'OverDrive launcher entry is hidden'));
                }
                (status.hiddenPackages || []).forEach(pkg => rows.push(
                    this.t('service_mode.detail_app', 'Hidden app: {package}').replace('{package}', pkg)
                ));
                const featureLabels = {
                    automations: this.t('service_mode.feature_automations', 'Automations'),
                    keymap: this.t('service_mode.feature_keymap', 'Key Mapping')
                };
                (status.disabledFeatures || []).forEach(feature => rows.push(
                    this.t('service_mode.detail_feature', 'Paused feature: {feature}')
                        .replace('{feature}', featureLabels[feature] || feature)
                ));
                rows.forEach(value => {
                    const li = document.createElement('li');
                    li.textContent = value;
                    details.appendChild(li);
                });
                details.hidden = rows.length === 0;
                endActions.hidden = false;
                endButton.disabled = false;
                setup.hidden = true;
            } else if (restoring) {
                title.textContent = this.t('service_mode.status_restoring', 'Restoring apps and features…');
                meta.textContent = this.t(
                    'service_mode.status_restoring_desc',
                    'The daemon is retrying any package restoration that has not completed.'
                );
                details.hidden = true;
                endActions.hidden = true;
                setup.hidden = true;
            } else {
                title.textContent = this.t('service_mode.status_inactive', 'Service Mode is off');
                meta.textContent = this.t(
                    'service_mode.status_inactive_desc',
                    'Your apps and OverDrive are available as usual.'
                );
                details.hidden = true;
                endActions.hidden = true;
                setup.hidden = false;
            }
        },

        async loadStatus() {
            try {
                const status = await this.request('/api/service-mode/status');
                if (!status.success) throw new Error(status.error || 'Could not load Service Mode status.');
                const endDate = document.getElementById('smEndDate');
                if (status.serverLocalDate) {
                    endDate.min = status.serverLocalDate;
                    if (!this.dateInitializedFromServer) {
                        endDate.value = this.addOneDay(status.serverLocalDate);
                        this.dateInitializedFromServer = true;
                    } else if (!endDate.value || endDate.value < status.serverLocalDate) {
                        endDate.value = this.addOneDay(status.serverLocalDate);
                    }
                }
                this.renderStatus(status);
            } catch (error) {
                this.toast(error.message || 'Could not load Service Mode status.', 'error');
            }
        },

        async startNow() {
            const button = document.getElementById('smStartButton');
            if (button.disabled) return;
            let endDate;
            try {
                endDate = this.selectedEndDate(
                    document.getElementById('smEndDate').value
                );
            } catch (error) {
                this.toast(error.message, 'error');
                return;
            }

            const hiddenPackages = Array.from(
                document.querySelectorAll('#smApps input[data-package]:checked')
            ).map(input => input.getAttribute('data-package'));
            const disabledFeatures = Array.from(
                document.querySelectorAll('.smFeature:checked')
            ).map(input => input.value);

            button.disabled = true;
            try {
                const result = await this.request('/api/service-mode/start', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        endDate: endDate,
                        hideOverdrive: document.getElementById('smHideOverdrive').checked,
                        hiddenPackages: hiddenPackages,
                        disabledFeatures: disabledFeatures
                    })
                });
                if (!result.success) {
                    this.toast(result.error || this.t('service_mode.start_failed', 'Could not start Service Mode.'), 'error');
                    if (typeof result.active === 'undefined') {
                        await this.loadStatus();
                        return;
                    }
                } else {
                    this.toast(this.t('service_mode.started', 'Service Mode started.'), 'success');
                }
                this.renderStatus(result);
            } catch (error) {
                this.toast(error.message || this.t('service_mode.start_failed', 'Could not start Service Mode.'), 'error');
                await this.loadStatus();
            } finally {
                button.disabled = false;
            }
        },

        async endNow() {
            const button = document.getElementById('smEndButton');
            if (button.disabled) return;
            button.disabled = true;
            try {
                const result = await this.request('/api/service-mode/end', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: '{}'
                });
                if (!result.success) {
                    this.toast(result.error || this.t('service_mode.end_failed', 'Could not restore everything yet.'), 'error');
                } else {
                    this.toast(this.t('service_mode.ended', 'Service Mode ended. Apps and features have been restored.'), 'success');
                }
                this.renderStatus(result);
            } catch (error) {
                this.toast(error.message || this.t('service_mode.end_failed', 'Could not restore everything yet.'), 'error');
                await this.loadStatus();
            } finally {
                button.disabled = false;
            }
        },

        toast(message, type) {
            if (window.BYD && BYD.core && BYD.core.toast) {
                BYD.core.toast(message, type || 'success');
            } else {
                console.log('[ServiceMode] ' + message);
            }
        },

        init() {
            const endDate = document.getElementById('smEndDate');
            const tomorrow = new Date();
            tomorrow.setDate(tomorrow.getDate() + 1);
            endDate.min = this.localDateValue(new Date());
            endDate.value = this.localDateValue(tomorrow);

            document.getElementById('smStartButton').addEventListener('click', () => this.startNow());
            document.getElementById('smEndButton').addEventListener('click', () => this.endNow());
            document.getElementById('smReloadApps').addEventListener('click', () => this.loadApps());

            this.loadApps();
            this.loadStatus();
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden) this.loadStatus();
            });
            window.setInterval(() => {
                if (!document.hidden) this.loadStatus();
            }, 30000);
        }
    };

    window.ServiceModePage = ServiceModePage;
})();
