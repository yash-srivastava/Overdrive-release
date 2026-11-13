/* Read-only partial vehicle-warning overview. No vehicle commands are sent. */
const ModuleFaultsPage = {
    snapshot: null,
    busy: false,

    t(key, fallback) {
        try {
            const fullKey = 'moduleFaults.' + key;
            const value = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(fullKey) : null;
            return value && value !== fullKey ? value : fallback;
        } catch (_) { return fallback; }
    },

    init() {
        this.refresh();
        if (!this._localeListener && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            this._localeListener = true;
            BYD.i18n.onChange(() => this.render());
        }
    },

    async refresh() {
        if (this.busy) return;
        this.busy = true;
        const button = document.getElementById('mfRefresh');
        const error = document.getElementById('mfError');
        if (button) button.disabled = true;
        if (error) { error.style.display = 'none'; error.textContent = ''; }
        try {
            const response = await fetch('/api/diagnostics/module-faults', {
                method: 'GET',
                cache: 'no-store',
                headers: { 'Accept': 'application/json' }
            });
            const payload = await response.json();
            if (!response.ok || !payload || payload.success !== true) {
                throw new Error((payload && payload.error) || ('HTTP ' + response.status));
            }
            this.snapshot = payload;
            this.render();
        } catch (e) {
            if (error) {
                error.textContent = this.t('load_error', 'Could not load vehicle diagnostic indicators.')
                    + (e && e.message ? ' ' + e.message : '');
                error.style.display = 'block';
            }
        } finally {
            this.busy = false;
            if (button) button.disabled = false;
        }
    },

    render() {
        const data = this.snapshot;
        if (!data) return;
        const modules = Array.isArray(data.modules) ? data.modules : [];
        const summary = data.summary || {};
        const summaryItems = [
            ['warningModules', 'warning_modules'],
            ['clearModules', 'clear_modules'],
            ['partialModules', 'partial_modules'],
            ['unavailableModules', 'unavailable_modules'],
            ['notScannedModules', 'not_scanned_modules']
        ];
        const summaryNode = document.getElementById('mfSummary');
        if (summaryNode) {
            summaryNode.innerHTML = summaryItems.map(([field, label]) =>
                '<div class="mf-summary-item"><div class="mf-summary-value">'
                + Number(summary[field] || 0)
                + '</div><div class="mf-summary-label">'
                + this.escape(this.t(label, label))
                + '</div></div>'
            ).join('');
        }

        const grid = document.getElementById('mfModules');
        if (grid) {
            grid.innerHTML = modules.map(module => this.renderModule(module)).join('');
            if (!modules.length) {
                grid.innerHTML = '<div class="mf-empty">'
                    + this.escape(this.t('empty', 'No readings are available yet. Make sure the vehicle is awake and try refreshing.'))
                    + '</div>';
            }
        }
        const updated = document.getElementById('mfUpdated');
        if (updated) {
            const stamp = Number(data.vehicleDataTimestamp || data.timestamp || 0);
            updated.textContent = stamp > 0 ? new Date(stamp).toLocaleString() : '—';
        }
        const exportButton = document.getElementById('mfCopy');
        if (exportButton) exportButton.disabled = false;
    },

    renderModule(module) {
        const status = String(module.status || 'unavailable');
        const statusKey = {
            warning: 'status_warning', clear: 'status_clear', partial: 'status_partial',
            unavailable: 'status_unavailable', not_scanned: 'status_not_scanned'
        }[status] || 'status_unavailable';
        const statusText = this.t(statusKey, status.replace('_', ' '));
        const readings = Array.isArray(module.readings) ? module.readings : [];
        const readingsHtml = readings.map(reading => {
            const readingStatus = String(reading.status || 'unavailable');
            const readingKey = {
                warning: 'status_warning', clear: 'status_clear', partial: 'status_partial',
                unavailable: 'status_unavailable'
            }[readingStatus] || 'status_unavailable';
            const metrics = [];
            if (Number.isFinite(Number(reading.pressureKpa))) {
                metrics.push(this.escape(String(reading.pressureKpa)) + ' kPa');
            }
            if (Number.isFinite(Number(reading.voltage))) {
                metrics.push(this.escape(String(reading.voltage)) + ' ' + this.escape(reading.voltageUnit || 'V'));
            }
            const metricHtml = metrics.length ? '<small>' + metrics.join(' · ') + '</small>' : '';
            return '<div class="mf-reading"><div class="mf-reading-head"><strong>'
                + this.escape(reading.label || reading.id || 'Reading')
                + '</strong><span class="mf-status ' + this.escape(readingStatus) + '">'
                + this.escape(this.t(readingKey, readingStatus))
                + '</span></div><p>' + this.escape(reading.summary || '')
                + '</p>' + metricHtml + '</div>';
        }).join('');
        return '<article class="mf-card ' + this.escape(status) + '">'
            + '<div class="mf-card-head"><h2>' + this.escape(module.name || module.id || 'Module')
            + '</h2><span class="mf-status ' + this.escape(status) + '">'
            + this.escape(statusText) + '</span></div>'
            + '<p class="mf-summary-text">' + this.escape(module.summary || '') + '</p>'
            + (readingsHtml ? '<div class="mf-reading-list">' + readingsHtml + '</div>' : '')
            + '<div class="mf-source">' + this.escape(module.source || '') + '</div>'
            + '</article>';
    },

    async copyReport() {
        if (!this.snapshot) return;
        const report = JSON.stringify(this.snapshot, null, 2);
        let copied = false;
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                await navigator.clipboard.writeText(report);
                copied = true;
            }
        } catch (_) { /* fall back to the WebView-compatible selection copy */ }
        if (!copied) {
            const field = document.createElement('textarea');
            field.value = report;
            field.setAttribute('readonly', '');
            field.style.position = 'fixed';
            field.style.opacity = '0';
            document.body.appendChild(field);
            field.select();
            try { copied = document.execCommand('copy'); } catch (_) { copied = false; }
            field.remove();
        }
        try {
            BYD.core.toast(
                copied ? this.t('copied', 'Diagnostic report copied to clipboard.')
                    : this.t('copy_failed', 'Could not copy the report.'),
                copied ? 'success' : 'error'
            );
        } catch (_) {}
    },

    escape(value) {
        return String(value == null ? '' : value).replace(/[&<>"']/g, character => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[character]));
    }
};
