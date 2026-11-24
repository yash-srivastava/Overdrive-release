/*
 * Overdrive — Automations UI controller.
 *
 * ES5 / Chrome 58 floor (BYD DiLink head-unit WebView, Android 7.1). Assets
 * ship raw — there is NO transpile/build step — so this file must parse and
 * run on Chrome 58. In particular:
 *   - No optional chaining (?.) / nullish coalescing (??) — both are Chrome 80+.
 *     A single one anywhere aborts the ENTIRE script at parse time, which is
 *     what left the tab blank and every onclick handler dead.
 *   - No Array.flat / Object.fromEntries / String.replaceAll (Chrome 90+),
 *     no class fields / private #fields, no numeric separators, no
 *     optional-catch-binding (`catch {}` — needs `catch (e) {}`).
 *   - const/let, arrow functions, template literals, for...of, Array.from,
 *     Promise and fetch ARE fine on Chrome 58.
 * Mutating network calls (POST/PUT/DELETE) MUST use fetch() — XHR request
 * bodies are silently dropped on this WebView.
 */

window.BYD = window.BYD || {};

// Display order of category <optgroup>s in the action/trigger/condition pickers.
// Mirrors AutomationCategories.ORDER (Java). Kept in sync by hand — it's a fixed,
// rarely-changing list, and an out-of-sync key just sorts to the end (never hides an
// item). 'other' is always forced last regardless of this list.
BYD.AUTOMATION_CATEGORY_ORDER = [
    'vehicle', 'climate', 'windows_body', 'lighting', 'adas_safety', 'drive',
    'media', 'displays', 'sensors', 'surveillance', 'advanced', 'system', 'flow', 'other',
];

// Fallback control-flow (If / Loop) nesting cap, used only until the schema loads. The
// AUTHORITATIVE value comes from the engine via schema (actions section .maxActionDepth,
// read by maxActionDepth() below), so the editor's cap can never drift from the parser's
// Automation.MAX_ACTION_DEPTH. The editor stops offering If/Loop at the cap so a user can't
// build a tree that would be rejected on save. Depth 1 = a top-level control-flow body.
const MAX_ACTION_DEPTH_FALLBACK = 8;

const triggerIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z"/></svg>';
const runSpinIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true"><path class="run-spin" d="M21 12a9 9 0 1 1-6.22-8.56"/></svg>';
const runDoneIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path class="run-check" d="M20 6 9 17l-5-5"/></svg>';
const runFailIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12"/></svg>';
const copyIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>';
const editIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
const deleteIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
const infoIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>';
// Reorder arrows for multi-row sections (move a step up / down in the chain).
const moveUpIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m18 15-6-6-6 6"/></svg>';
const moveDownIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>';

// Block-builder look (presentational only): one stroke icon per step kind, and the hue key
// a block / step paints with (data-section / data-step-cat → var(--auto-<key>) in CSS).
const AUTO_ICON_PATHS = {
    'when': '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
    'if': '<path d="M6 3v12"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/>',
    'wait': '<path d="M5 22h14M5 2h14M17 22v-4.17a2 2 0 0 0-.59-1.41L12 12l-4.41 4.41A2 2 0 0 0 7 17.83V22M7 2v4.17a2 2 0 0 0 .59 1.41L12 12l4.41-4.41A2 2 0 0 0 17 6.17V2"/>',
    'then': '<path d="M7 4v16l13-8z"/>',
    'else': '<path d="M15 10l5 5-5 5M4 4v7a4 4 0 0 0 4 4h12"/>',
    'loop': '<path d="m17 2 4 4-4 4M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4 4-4M21 13v1a4 4 0 0 1-4 4H3"/>',
    'pause': '<path d="M6 4h4v16H6zM14 4h4v16h-4z"/>',
    'var': '<path d="M8 21s-4-3-4-9 4-9 4-9M16 3s4 3 4 9-4 9-4 9M15 9l-6 6M9 9l6 6"/>',
    'group': '<path d="m12 2 10 5-10 5L2 7zM2 17l10 5 10-5M2 12l10 5 10-5"/>',
    'vehicle': '<path d="M5 17H3v-5l2.2-5.1A2 2 0 0 1 7 5.7h10a2 2 0 0 1 1.8 1.2L21 12v5h-2M3 12h18M9.5 17h5"/><circle cx="7.5" cy="17" r="2"/><circle cx="16.5" cy="17" r="2"/>',
    'climate': '<path d="M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z"/>',
    'body': '<path d="M3 21h18M6 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16M14 12h.01"/>',
    'light': '<path d="M9 18h6M10 22h4M15.09 14c.18-.98.65-1.74 1.41-2.5A4.65 4.65 0 0 0 18 8 6 6 0 0 0 6 8c0 1 .23 2.23 1.5 3.5A4.61 4.61 0 0 1 8.91 14"/>',
    'safety': '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10zM12 8v4M12 16h.01"/>',
    'drive': '<path d="m12 14 4-4M3.34 19a10 10 0 1 1 17.32 0"/>',
    'media': '<path d="M9 18V5l12-2v13M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM21 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"/>',
    'display': '<path d="M2 3h20v14H2zM8 21h8M12 17v4"/>',
    'system': '<path d="M2 4h20v16H2zM2 8h20M6 6h.01M9 6h.01"/>',
    'surv': '<path d="M23 7l-7 5 7 5V7zM1 5h15v14H1z"/>',
    'adv': '<path d="M21 4h-7M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3M14 2v4M8 10v4M16 18v4"/>',
    'sensor': '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
    'other': '<circle cx="12" cy="12" r="10"/><path d="M12 12h.01"/>',
    'bell': '<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0"/>'
};
const AUTO_CATEGORY_HUES = {
    'vehicle': 'vehicle', 'climate': 'climate', 'windows_body': 'body', 'lighting': 'light',
    'adas_safety': 'safety', 'drive': 'drive', 'media': 'media', 'displays': 'display',
    'system': 'system', 'surveillance': 'surv', 'advanced': 'adv', 'sensors': 'sensor', 'other': 'other'
};
const AUTO_FLOW_HUES = {
    'if': 'if', 'loop': 'loop', 'pause': 'wait', 'waitUntil': 'wait', 'waitUntilState': 'wait',
    'setVariable': 'var', 'incrementVariable': 'var', 'computeVariable': 'var', 'captureVariable': 'var',
    'variable': 'var', 'actionGroup': 'group', 'automationControl': 'group'
};
const AUTO_SECTION_HUES = { 'triggers': 'when', 'conditions': 'if', 'delay': 'wait', 'actions': 'then', 'elseActions': 'else' };
const AUTO_BRANCH_HUES = { 'automation.then_actions': 'then', 'automation.else_actions': 'else', 'automation.loop_body': 'loop' };
const AUTO_BELL_IDS = { 'notification': 'bell', 'showToast': 'bell', 'showDialog': 'bell' };

function autoPick(map, key) {
    return (key != null && Object.prototype.hasOwnProperty.call(map, key)) ? map[key] : null;
}
// Hue key for a schema option: flow actions by id, everything else by category.
function autoStepHue(option) {
    if (!option) return 'other';
    if (option.category === 'flow') return autoPick(AUTO_FLOW_HUES, option.id) || 'other';
    return autoPick(AUTO_CATEGORY_HUES, option.category) || 'other';
}
function autoStepIconKey(option, hue) {
    if (option && option.id === 'pause') return 'pause';
    return (option && autoPick(AUTO_BELL_IDS, option.id)) || hue;
}
function autoSectionHue(sectionId) {
    return autoPick(AUTO_SECTION_HUES, sectionId) || 'other';
}
function autoIconSvg(key) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">'
        + (autoPick(AUTO_ICON_PATHS, key) || AUTO_ICON_PATHS.other) + '</svg>';
}

// Automation ids and the schema section ids are user / server supplied. They
// are safe to interpolate into text, but NOT into class names (a leading
// digit, space, or punctuation throws a DOMException from classList.add) and
// NOT into URLs without percent-encoding. Sanitize to a token for class use;
// encodeURIComponent for URL path segments.
function sanitizeToken(value) {
    return String(value == null ? '' : value).replace(/[^a-zA-Z0-9_-]/g, '_');
}

// Escape user-provided text before it goes into innerHTML. The automation NAME is
// free-text and the card summary is built as an HTML string (buildAutomationText →
// innerHTML), so an unescaped name could inject markup. ES5-safe (no String.raw etc).
function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ── List sort ──────────────────────────────────────────────────────────────
// Persisted sort order for the local automation list (automations.html). Pure
// client-side reordering of what /api/automations/list returned — 'default'
// preserves the daemon's map order byte-for-byte, so pre-feature behaviour is
// the default. The choice lives in localStorage (per-browser, like the active
// bottom-tab record) so it survives reloads without a backend field.
const AUTOMATION_SORT_KEY = 'automations.listSort';
const AUTOMATION_SORT_MODES = ['default', 'name_az', 'name_za', 'recent', 'runs', 'enabled', 'disabled'];

BYD.automations = {
    automations: {},
    schema: [],
    formData: {},
    editingId: null,
    sortMode: 'default',
    _schemaLoadGeneration: 0,
    _localeChangeBound: false,

    init() {
        // Restore the persisted list sort before the first render. An unknown
        // stored value (from a newer/older build) degrades to 'default'.
        try {
            const savedSort = localStorage.getItem(AUTOMATION_SORT_KEY);
            if (savedSort && AUTOMATION_SORT_MODES.indexOf(savedSort) >= 0) this.sortMode = savedSort;
        } catch (_) { /* storage unavailable → session default */ }
        const sortSel = document.getElementById('automationSortSelect');
        if (sortSel) sortSel.value = this.sortMode;

        this.loadAutomations();
        this.loadAutomationSchema();
        this.loadSettings();
        this.loadGroups();
        // Most of this page is hydrated from the web catalog, but the editor schema is
        // translated by the daemon and every field/select is then built imperatively.
        // Reload that schema for the new browser locale and rebuild every dynamic surface,
        // otherwise a switch such as pt-BR -> en leaves the old Portuguese labels behind.
        if (!this._localeChangeBound && window.BYD && BYD.i18n
                && typeof BYD.i18n.onChange === 'function') {
            this._localeChangeBound = true;
            BYD.i18n.onChange(() => this.refreshLocale());
        }
        this.loadPositionList();
        // Re-sync the Groups tab whenever the user switches TO it. app-tabs only
        // toggles element visibility; without this the list wouldn't refresh (a group
        // saved on another visit, or a stale editor pane left open) until a full reload.
        // Switching to Groups always returns to the LIST view (hideGroupForm) and
        // reloads it. Guarded so a failure never blocks the tab switch.
        document.addEventListener('ot-tabs:active-changed', (e) => {
            try {
                const id = e && e.detail ? e.detail.id : null;
                if (id === 'groups') { this.hideGroupForm(); this.loadGroups(); }
            } catch (_) {}
        });
        if (window.BYD && BYD.offline && typeof BYD.offline.onReconnect === 'function') {
            BYD.offline.onReconnect(() => this._refreshAfterReconnect());
        }
    },

    /** Back online: what failed to load, or is still on its way, loads now; loaded lists and an open form stay.
     *  While hidden: on the next return to the page, once. */
    _refreshAfterReconnect() {
        if (document.hidden) {
            if (this._reconnectDeferred) return;
            this._reconnectDeferred = true;
            const shown = () => {
                if (document.hidden) return;
                document.removeEventListener('visibilitychange', shown);
                this._reconnectDeferred = false;
                this._refreshAfterReconnect();
            };
            document.addEventListener('visibilitychange', shown);
            return;
        }
        // The page's lists didn't load from the car, so the sounds list (a sibling read) likely didn't either.
        const outage = !!(this._listFailed || this._listPending || this._groupsFailed || this._groupsPending);
        // Read again on the next form open: an outage fails open to "configured".
        this._cloudConfigured = null;
        if (this._appList && !this._appList.length) {
            this._appList = null;
            this._appListPromise = null;
        }
        if (this._positionListFailed) {
            this._positionList = null;
            this._positionListPromise = null;
            this.loadPositionList();
        }
        if (this._listFailed || this._listPending) this.loadAutomations();
        if (this._groupsFailed || this._groupsPending) this.loadGroups();
        if (!this.schemaSections().length) this.loadAutomationSchema();
        // Safety settings still loading or unavailable read again.
        const box = document.getElementById('safetySettingsStatus');
        const safety = document.getElementById('safetySettingsStatusText');
        const shownKey = box && !box.hidden && safety ? safety.getAttribute('data-i18n') : null;
        if (shownKey === 'automation.safety_unavailable' || shownKey === 'automation.safety_loading') this.loadSettings();
        if (outage && window.AudioLibrary && typeof AudioLibrary.refresh === 'function') AudioLibrary.refresh();
    },

    // Automation-wide settings. Kept separate from the per-automation CRUD above.
    async loadSettings() {
        // Overlapping reads: a success applies unless a newer success did, a failure only if nothing newer applied.
        const seq = this._settingsSeq = (this._settingsSeq || 0) + 1;
        const controls = document.getElementById('safetySettingsControls');
        const status = document.getElementById('safetySettingsStatus');
        const statusText = document.getElementById('safetySettingsStatusText');
        const statusIcon = document.getElementById('safetySettingsStatusIcon');
        if (controls) controls.hidden = true;
        if (status) {
            status.hidden = false;
            status.className = 'info-box-note';
            status.setAttribute('role', 'status');
        }
        if (statusIcon) statusIcon.hidden = true;
        if (statusText) {
            statusText.setAttribute('data-i18n', 'automation.safety_loading');
            statusText.textContent = BYD.i18n.t('automation.safety_loading')
                || 'Loading safety settings…';
        }
        let timeoutId = 0;
        try {
            const resp = await Promise.race([
                fetch('/api/automations/settings', { cache: 'no-store' }),
                new Promise((resolve, reject) => {
                    timeoutId = setTimeout(
                        () => reject(new Error('safety settings request timed out')), 10000);
                })
            ]);
            const data = await resp.json();
            if (!resp.ok || !data || data.success !== true
                    || !data.drivingSafety
                    || typeof data.drivingSafety !== 'object') {
                throw new Error('invalid safety settings response');
            }
            if (seq < (this._settingsOk || 0)) return;
            this._settingsOk = seq;
            if (seq > (this._settingsLast || 0)) this._settingsLast = seq;
            const el = document.getElementById('autoAllowShell');
            if (el) el.checked = !!data.allowShell;
            const safety = data.drivingSafety;
            const toggles = document.querySelectorAll('[data-safety-key]');
            for (let i = 0; i < toggles.length; i++) {
                const key = toggles[i].getAttribute('data-safety-key');
                if (typeof safety[key] !== 'boolean') {
                    throw new Error('missing safety setting: ' + key);
                }
            }
            for (let i = 0; i < toggles.length; i++) {
                const key = toggles[i].getAttribute('data-safety-key');
                toggles[i].checked = safety[key];
            }
            if (controls) controls.hidden = false;
            if (status) status.hidden = true;
        } catch (e) {
            if (seq < (this._settingsLast || 0)) return;
            this._settingsLast = seq;
            if (controls) controls.hidden = true;
            if (status) {
                status.hidden = false;
                status.className = 'info-box-warning';
                status.setAttribute('role', 'alert');
            }
            if (statusIcon) statusIcon.hidden = false;
            if (statusText) {
                statusText.setAttribute('data-i18n', 'automation.safety_unavailable');
                let message = 'Safety settings are unavailable. The current guard state could not be verified; reload this page to try again.';
                try {
                    if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
                        message = BYD.i18n.t('automation.safety_unavailable') || message;
                    }
                } catch (_) {}
                statusText.textContent = message;
            }
            console.warn('[Automations] Failed to load settings:', e);
        } finally {
            if (timeoutId) clearTimeout(timeoutId);
        }
    },

    async saveSettings() {
        const el = document.getElementById('autoAllowShell');
        const allow = !!(el && el.checked);
        try {
            const resp = await fetch('/api/automations/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ allowShell: allow })
            });
            const data = await resp.json();
            if (!data || !data.success) {
                if (el) el.checked = !allow; // revert the toggle on failure
                if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('automation.settings_save_failed'), 'error');
            }
        } catch (e) {
            if (el) el.checked = !allow;
            if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('automation.settings_save_failed'), 'error');
        }
    },

    async saveSafetySetting(el) {
        if (!el) return;
        const key = el.getAttribute('data-safety-key');
        const enabled = !!el.checked;
        if (!key) return;
        if (!enabled) {
            let message = 'Disable this safety guard? The action will be allowed while the car is moving or its state is unknown.';
            try {
                if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
                    message = BYD.i18n.t('automation.safety_disable_confirm') || message;
                }
            } catch (_) {}
            const confirmed = (window.BYD && BYD.utils && BYD.utils.confirmDialog)
                ? await BYD.utils.confirmDialog({
                    title: BYD.i18n.t('common.warning') || 'Warning',
                    body: message,
                    confirmLabel: BYD.i18n.t('common.disable') || 'Disable',
                    cancelLabel: BYD.i18n.t('common.cancel') || 'Cancel',
                    danger: true
                  })
                : window.confirm(message);
            if (!confirmed) {
                el.checked = true;
                return;
            }
        }

        const previous = !enabled;
        const drivingSafety = {};
        drivingSafety[key] = enabled;
        // One settings mutation at a time. Otherwise a failed request can re-read
        // authority while another toggle is still committing and repaint stale state.
        const toggles = document.querySelectorAll('[data-safety-key]');
        for (let i = 0; i < toggles.length; i++) toggles[i].disabled = true;
        try {
            const resp = await fetch('/api/automations/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ drivingSafety: drivingSafety })
            });
            const data = await resp.json();
            const safety = data && data.drivingSafety;
            if (!resp.ok || !data || data.success !== true
                    || !safety || typeof safety !== 'object') {
                throw new Error('safety setting was not persisted');
            }
            for (let i = 0; i < toggles.length; i++) {
                const toggleKey = toggles[i].getAttribute('data-safety-key');
                if (typeof safety[toggleKey] !== 'boolean') {
                    throw new Error('missing safety setting: ' + toggleKey);
                }
            }
            for (let i = 0; i < toggles.length; i++) {
                const toggleKey = toggles[i].getAttribute('data-safety-key');
                toggles[i].checked = safety[toggleKey];
            }
        } catch (e) {
            el.checked = previous;
            // The POST may have committed before its response was lost. Re-read the
            // authoritative values; if that also fails, loadSettings hides every toggle.
            await this.loadSettings();
            if (window.BYD && BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('automation.settings_save_failed'), 'error');
            }
        } finally {
            for (let i = 0; i < toggles.length; i++) toggles[i].disabled = false;
        }
    },

    // Export all automations: fetch the backup envelope from the daemon and trigger a
    // client-side download. No server file is written — the JSON is streamed straight
    // into a Blob the browser saves, so it works over the tunnel too.
    async exportAll() {
        try {
            const resp = await fetch('/api/automations/export', { cache: 'no-store' });
            if (!resp.ok) { this.toast(BYD.i18n.t('automation.backup_export_failed'), 'error'); return; }
            const text = await resp.text();
            const blob = new Blob([text], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            // Uptime-ish suffix (no Date dependency issues in this WebView): the daemon
            // already stamped exportedAt inside; the filename just needs to be unique-ish.
            a.download = 'overdrive-automations.json';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            this.toast(BYD.i18n.t('automation.backup_exported'), 'success');
        } catch (e) {
            this.toast(BYD.i18n.t('automation.backup_export_failed'), 'error');
        }
    },

    // Import a backup file (merge). Reads the chosen file client-side and POSTs it to
    // /api/automations/import; the daemon validates every automation before storing, so
    // a malformed file is rejected without touching the current set. Refreshes the list
    // and reports how many were imported.
    importFile(file) {
        if (!file) return;
        const input = document.getElementById('automationImportFile');
        const done = () => { if (input) input.value = ''; }; // re-select same file re-fires onchange
        // FileReader (not Blob.text()) — Blob.text() is Chrome 76+, this WebView is 58.
        const reader = new FileReader();
        reader.onerror = () => { this.toast(BYD.i18n.t('automation.backup_import_failed'), 'error'); done(); };
        reader.onload = () => {
            const text = String(reader.result || '');
            try {
                // Accept either the export envelope ({automations:{...}}) or a bare map;
                // the endpoint handles both. Validate JSON here for a clean early error.
                JSON.parse(text);
            } catch (parseErr) {
                this.toast(BYD.i18n.t('automation.backup_import_invalid'), 'error');
                done();
                return;
            }
            fetch('/api/automations/import', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: text
            })
            .then(resp => resp.json().catch(() => null))
            .then(data => {
                if (data && data.success) {
                    this.loadAutomations();
                    this.toast(BYD.i18n.t('automation.backup_imported').replace('{0}', String(data.imported)), 'success');
                } else {
                    this.toast((data && data.error) ? data.error : BYD.i18n.t('automation.backup_import_failed'), 'error');
                }
            })
            .catch(() => this.toast(BYD.i18n.t('automation.backup_import_failed'), 'error'))
            .then(done);
        };
        reader.readAsText(file);
    },

    async loadAutomations() {
        // Overlapping reads: a success applies unless a newer success did, a failure only if nothing newer
        // applied. Failed (an error reply carries success:false) and pending feed the reconnect handler.
        const seq = this._listSeq = (this._listSeq || 0) + 1;
        this._listPending = true;
        let data = null;
        try {
            const resp = await fetch('/api/automations/list');
            data = await resp.json();
        } catch (e) {
            console.warn('[Automations] Failed to load automations:', e);
        }
        const ok = !!data && data.success !== false;
        if (ok ? seq < (this._listOk || 0) : seq < (this._listLast || 0)) return;
        if (ok) this._listOk = seq;
        if (seq > (this._listLast || 0)) this._listLast = seq;
        if (seq === this._listSeq) this._listPending = false;
        this._listFailed = !ok;
        this.automations = ok ? data : {};
        this.render();
    },

    async loadAutomationSchema() {
        const generation = ++this._schemaLoadGeneration;
        const lang = (window.BYD && BYD.i18n && BYD.i18n.getLang)
            ? BYD.i18n.getLang() : 'en';
        try {
            const resp = await fetch(
                '/api/automations/schema?lang=' + encodeURIComponent(lang),
                { cache: 'no-store' });
            const data = await resp.json();
            if (generation !== this._schemaLoadGeneration) return;
            if (data) {
                this.schema = data;
            } else {
                this.schema = [];
            }
        } catch (e) {
            if (generation !== this._schemaLoadGeneration) return;
            console.warn('[Automations] Failed to load schema:', e);
            this.schema = [];
        }
        this.render();
        this.renderForm();
        // Group cards build their recipe from the schema; skip until the list itself has loaded.
        if (this._groupsLoaded) {
            try { this.renderGroupList(); } catch (_) {}
        }
    },

    async refreshLocale() {
        // Intl.Collator is locale-bound, so discard the old one before rebuilding sorted
        // action/condition lists and automation-name ordering.
        this._sortCollator = null;
        await this.loadAutomationSchema();
        this.renderGroupList();
        if (this.groupData) this.renderGroupForm();
        // These sibling controllers also render translated text imperatively.
        if (window.AudioLibrary && typeof AudioLibrary.refresh === 'function') {
            AudioLibrary.refresh();
        }
        if (window.CommunityAutomations
                && typeof CommunityAutomations.loadPage === 'function') {
            CommunityAutomations.loadPage(CommunityAutomations.currentPage || 1);
        }
    },

    // The schema is an ordered list of sections. Normalize whatever the
    // server returns (array, or an empty/failed object) to an array so the
    // rest of the code can iterate it consistently.
    schemaSections() {
        if (Array.isArray(this.schema)) return this.schema;
        return [];
    },

    // The full action catalog (the `options` of the "actions" schema section) — the same
    // list the automation action rows and the nested if/loop editors use. Shared with the
    // Action Group editor so a group can hold any action an automation can. [] if the
    // schema hasn't loaded yet.
    actionCatalog() {
        const sec = this.schemaSections().find(s => s.id === 'actions');
        return (sec && Array.isArray(sec.options)) ? sec.options : [];
    },

    // The conditions/signals catalog (the `options` of the "conditions" schema section) —
    // the live-signal list. Used as the Signal-mode source for the dynamic value editor on
    // the inline flow actions (If / Loop / Wait Until), so their comparison RHS can be a
    // ${signal:TYPE} just like a real condition. [] if the schema hasn't loaded yet.
    conditionCatalog() {
        const sec = this.schemaSections().find(s => s.id === 'conditions');
        return (sec && Array.isArray(sec.options)) ? sec.options : [];
    },

    // The engine's control-flow nesting cap, read from the schema (actions section) so the
    // editor's limit always matches Automation.MAX_ACTION_DEPTH. Falls back until loaded.
    maxActionDepth() {
        const sec = this.schemaSections().find(s => s.id === 'actions');
        const n = sec && sec.maxActionDepth;
        return (typeof n === 'number' && n > 0) ? n : MAX_ACTION_DEPTH_FALLBACK;
    },

    render() {
        const list = document.getElementById('automationList');
        const empty = document.getElementById('emptyState');
        const head = document.getElementById('listHead');
        const sortRow = document.getElementById('automationSortRow');
        if (!list || !empty) return;

        const entries = Object.entries(this.automations);
        if (entries.length === 0) {
            list.innerHTML = '';
            empty.style.display = 'block';
            if (head) head.style.display = 'none';
            if (sortRow) sortRow.style.display = 'none';
            return;
        }

        empty.style.display = 'none';
        if (head) head.style.display = '';
        // A single card has nothing to sort — keep the row out of the way until
        // it's useful, so the pre-feature layout is untouched for small sets.
        if (sortRow) sortRow.style.display = entries.length >= 2 ? '' : 'none';
        list.innerHTML = '';
        for (let [key, automation] of this.sortEntries(entries)) {
            list.append(this.createAutomationElement(key, automation));
        }
    },

    /** Change the list sort (select onchange), persist it, and re-render. */
    setSort(mode) {
        if (AUTOMATION_SORT_MODES.indexOf(mode) < 0) mode = 'default';
        this.sortMode = mode;
        try { localStorage.setItem(AUTOMATION_SORT_KEY, mode); } catch (_) { /* best-effort */ }
        this.render();
    },

    // Backward-compatible mode derivation. Manual-only is stored with disabled=true
    // so older builds fail safe; the extra marker lets this build distinguish it
    // from a fully disabled rule.
    getAutomationMode(automation) {
        if (automation && automation.manualOnly) return 'manual';
        return automation && automation.disabled ? 'disabled' : 'automatic';
    },

    applyModeToForm(mode) {
        if (mode === 'manual') {
            this.formData.disabled = true;
            this.formData.manualOnly = true;
        } else if (mode === 'disabled') {
            this.formData.disabled = true;
            delete this.formData.manualOnly;
        } else {
            this.formData.disabled = false;
            delete this.formData.manualOnly;
        }
    },

    // Locale-aware, numeric-smart name comparator ("Auto 2" < "Auto 10"), built
    // lazily and cached — Intl.Collator construction is not free and render()
    // runs on every save/toggle. Falls back to a plain compare if Intl is absent.
    _sortCollator: null,
    _collator() {
        if (!this._sortCollator) {
            try {
                const lang = document.documentElement.lang || undefined;
                this._sortCollator = new Intl.Collator(lang, { numeric: true, sensitivity: 'base' });
            } catch (_) {
                this._sortCollator = { compare: (a, b) => (a < b ? -1 : a > b ? 1 : 0) };
            }
        }
        return this._sortCollator;
    },

    /**
     * Order the [key, automation] entries per this.sortMode. In place; returns
     * the same array. 'default' is a no-op so the daemon's order round-trips
     * untouched. Every mode is a total order with deterministic tiebreaks, and
     * Array.prototype.sort is stable, so equal automations keep their stored
     * relative order — the list never shuffles between renders.
     *
     * Name semantics: the name is OPTIONAL metadata, so unnamed automations sink
     * below named ones on the name sorts (their card has no bold heading to scan
     * for) and keep their stored order among themselves.
     */
    sortEntries(entries) {
        const mode = this.sortMode;
        if (mode === 'default') return entries;

        const col = this._collator();
        const nameOf = (a) => (a && a.name ? String(a.name).trim() : '');
        // dir: 1 = A→Z, -1 = Z→A. Unnamed sort AFTER named in both directions.
        const byName = (ea, eb, dir) => {
            const an = nameOf(ea[1]), bn = nameOf(eb[1]);
            if (!an && !bn) return 0;
            if (!an) return 1;
            if (!bn) return -1;
            return dir * col.compare(an, bn);
        };
        const lastRun = (e) => e[1].lastTriggered || 0;
        const runs = (e) => e[1].triggerCount || 0;
        const modeRank = (e) => {
            const m = this.getAutomationMode(e[1]);
            return m === 'automatic' ? 0 : (m === 'manual' ? 1 : 2);
        };

        switch (mode) {
            case 'name_az':
                entries.sort((a, b) => byName(a, b, 1));
                break;
            case 'name_za':
                entries.sort((a, b) => byName(a, b, -1));
                break;
            case 'recent':
                // Most recently run first; never-run (0) sink to the bottom together.
                entries.sort((a, b) => (lastRun(b) - lastRun(a)) || byName(a, b, 1));
                break;
            case 'runs':
                entries.sort((a, b) => (runs(b) - runs(a)) || (lastRun(b) - lastRun(a)) || byName(a, b, 1));
                break;
            case 'enabled':
                entries.sort((a, b) => (modeRank(a) - modeRank(b)) || byName(a, b, 1));
                break;
            case 'disabled':
                entries.sort((a, b) => (modeRank(b) - modeRank(a)) || byName(a, b, 1));
                break;
        }
        return entries;
    },

    createAutomationElement(key, automation) {
        // Never interpolate the raw id into a class name — a key with a
        // leading digit / space / punctuation throws a DOMException from
        // classList.add. Sanitize to a token, and stash the real id on a
        // data-attribute for anything that needs it back.
        const token = sanitizeToken(key);
        const runMode = this.getAutomationMode(automation);
        const automationDiv = document.createElement('div');
        automationDiv.classList.add(
            token + '-card', 'card', runMode === 'automatic' ? 'enabled' : runMode);
        automationDiv.setAttribute('data-automation-id', key);

        const automationHeader = document.createElement('div');
        automationHeader.classList.add(token + '-header', 'header');
        automationDiv.append(automationHeader);

        const automationInfo = document.createElement('div');
        automationInfo.classList.add(token + '-info', 'info', 'recipe-info');
        automationInfo.innerHTML = this.buildAutomationText(automation, { recipe: true });
        automationHeader.append(automationInfo);

        const automationActions = document.createElement('div');
        automationActions.classList.add(token + '-actions', 'actions');
        automationHeader.append(automationActions);

        const triggerBtn = document.createElement('button');
        triggerBtn.classList.add(token + '-action', 'action', 'icon-btn', 'run-btn');
        triggerBtn.title = BYD.i18n.t('automation.trigger');
        triggerBtn.setAttribute('aria-label', BYD.i18n.t('automation.trigger'));
        triggerBtn.innerHTML = triggerIcon;
        triggerBtn.addEventListener('click', () => this.triggerAutomation(key, triggerBtn));
        automationActions.append(triggerBtn);

        const copyBtn = document.createElement('button');
        copyBtn.classList.add(token + '-action', 'action', 'icon-btn');
        copyBtn.title = BYD.i18n.t('automation.copy_automation');
        copyBtn.innerHTML = copyIcon;
        // Open the form pre-filled from this automation but with no editingId, so
        // Save creates a NEW automation (a duplicate) rather than overwriting.
        copyBtn.addEventListener('click', () => this.showForm(null, automation));
        automationActions.append(copyBtn);

        const editBtn = document.createElement('button');
        editBtn.classList.add(token + '-action', 'action', 'icon-btn');
        editBtn.title = BYD.i18n.t('automation.edit_automation');
        editBtn.innerHTML = editIcon;
        editBtn.addEventListener('click', () => this.showForm(key, automation));
        automationActions.append(editBtn);

        const deleteBtn = document.createElement('button');
        deleteBtn.classList.add(token + '-action', 'action', 'icon-btn', 'danger');
        deleteBtn.title = BYD.i18n.t('common.delete');
        deleteBtn.innerHTML = deleteIcon;
        deleteBtn.addEventListener('click', () => this.deleteAutomation(key));
        automationActions.append(deleteBtn);

        // Icon-only buttons: the tooltip text doubles as the accessible name.
        for (const b of [triggerBtn, copyBtn, editBtn, deleteBtn]) b.setAttribute('aria-label', b.title);

        const automationBody = document.createElement('div');
        automationBody.classList.add(token + '-body', 'body');
        automationDiv.append(automationBody);

        const statusText = document.createElement('div');
        statusText.classList.add(token + '-status-text', 'status-text');
        automationBody.append(statusText);

        const statusDot = document.createElement('span');
        statusDot.classList.add(
            token + '-status-dot', 'status-dot',
            runMode === 'automatic' ? 'connected' : (runMode === 'manual' ? 'manual' : 'off'));
        statusText.append(statusDot);

        const statusMessage = document.createElement('span');
        statusMessage.classList.add(token + '-status-message', 'status-message');
        statusMessage.textContent = BYD.i18n.t('automation.mode_' + runMode);
        statusText.append(statusMessage);

        const modeSelect = document.createElement('select');
        modeSelect.classList.add(token + '-mode-select', 'select-control', 'automation-mode-select');
        modeSelect.setAttribute('aria-label', BYD.i18n.t('automation.mode_label'));
        for (const mode of ['automatic', 'manual', 'disabled']) {
            const option = document.createElement('option');
            option.value = mode;
            option.textContent = BYD.i18n.t('automation.mode_' + mode);
            modeSelect.append(option);
        }
        modeSelect.value = runMode;
        modeSelect.addEventListener('change', () => this.setAutomationMode(key, modeSelect.value));
        automationBody.append(modeSelect);

        return automationDiv;
    },

    // `opts.recipe` (local list cards only): wrap the SAME text pieces in recipe markup —
    // block marker, joiners, one chip per step with its icon/hue — so the card's text is
    // unchanged. Without it (community cards) the output is the plain prose, as before.
    // `opts.sections` limits the output to those section ids (the action-group cards).
    buildAutomationText(automation, opts) {
        const sections = this.schemaSections();
        if (!sections.length) return BYD.i18n.t('automation.failed_to_load_schema');

        const recipe = !!(opts && opts.recipe);
        const onlySections = (opts && Array.isArray(opts.sections)) ? opts.sections : null;
        const chip = (text, hue, iconKey, extraClass) => '<span class="recipe-chip' + (extraClass ? ' ' + extraClass : '')
            + '" data-step-cat="' + hue + '">' + autoIconSvg(iconKey || hue) + '<span class="recipe-text">' + text + '</span></span>';
        const mark = (text, sectionId) => {
            if (!recipe) return text;
            const hue = autoSectionHue(sectionId);
            return '<span class="recipe-sec" data-step-cat="' + hue + '">' + autoIconSvg(hue)
                + '<span class="recipe-text">' + text + '</span></span>';
        };
        const joiner = (text) => (recipe ? '<span class="recipe-join">' + text + '</span>' : text);
        const step = (text, options, entry) => {
            if (!recipe || !text) return text;
            const option = (options || []).find(o => o && o.id === entry.type) || null;
            const hue = autoStepHue(option);
            return chip(text, hue, autoStepIconKey(option, hue));
        };
        const lineOpen = (sectionId) => (recipe ? '<span class="recipe-line" data-section="' + sanitizeToken(sectionId) + '">' : '');

        let result = '';
        try {
            // Friendly name (when set) as a bold heading above the trigger/condition
            // summary, plus an unobtrusive run-count / last-fired line. Both are
            // optional metadata — absent on older automations, so the card reads
            // exactly as before for them.
            if (automation.name) {
                result += '<strong class="automation-name">' + escapeHtml(automation.name) + '</strong><br>';
            }
            const runs = automation.triggerCount || 0;
            if (runs > 0) {
                let stats = BYD.i18n.t('automation.stats_runs').replace('{n}', runs);
                if (automation.lastTriggered) {
                    stats += ' · ' + BYD.i18n.t('automation.stats_last')
                        .replace('{when}', this.formatRelativeTime(automation.lastTriggered));
                }
                result += '<span class="automation-stats">' + stats + '</span><br>';
            }
            for (const section of sections) {
                if (onlySections && onlySections.indexOf(section.id) < 0) continue;
                if (section.options && section.options.length) {
                    const sectionData = (automation[section.id] != null) ? automation[section.id] : [];
                    // Skip an empty else section entirely so automations without an
                    // else branch read exactly as before.
                    if (section.id === 'elseActions' && sectionData.length === 0) continue;
                    result += lineOpen(section.id);
                    for (let i = 0; i < sectionData.length; i++) {
                        const entry = sectionData[i] || {};
                        if (i === 0) {
                            result += mark(section.label, section.id);
                        } else {
                            result += joiner(this.rowJoinerLabel(section,
                                section.logic && section.logic.field && automation[section.logic.field]));
                        }
                        result += ' ';
                        result += step(this.describeActionEntry(entry, section.options), section.options, entry);
                    }
                    // Nested condition GROUPS (conditions section) — render each group's
                    // AND/OR-joined leaves so a group-only automation isn't blank on the
                    // card. Joined to the flat rows above by the section's own AND/OR word.
                    if (section.groups && Array.isArray(automation.conditionGroups)) {
                        const secLogic = (automation[(section.logic && section.logic.field)] || (section.logic && section.logic.default) || 'AND');
                        for (let gi = 0; gi < automation.conditionGroups.length; gi++) {
                            const grp = automation.conditionGroups[gi] || {};
                            const gConds = Array.isArray(grp.conditions) ? grp.conditions : [];
                            if (!gConds.length) continue;
                            // Joiner before the group: section label if it's the very first
                            // term (no flat rows preceded it), else the section AND/OR word.
                            const noFlatRows = !(sectionData && sectionData.length);
                            result += (gi === 0 && noFlatRows)
                                ? mark(section.label, section.id)
                                : joiner(BYD.i18n.t('automation.logic_' + secLogic.toLowerCase()));
                            result += recipe
                                ? ' <span class="recipe-chip recipe-group" data-step-cat="if">' + autoIconSvg('if') + '<span class="recipe-text">('
                                : ' (';
                            const gLogic = (grp.logic || 'AND');
                            for (let ci = 0; ci < gConds.length; ci++) {
                                if (ci > 0) result += ' ' + BYD.i18n.t('automation.logic_' + gLogic.toLowerCase()) + ' ';
                                result += this.describeActionEntry(gConds[ci] || {}, section.options);
                            }
                            result += recipe ? ')</span></span>' : ')';
                        }
                    }
                } else {
                    result += lineOpen(section.id);
                    const plain = section.label + '=' + automation[section.id];
                    result += recipe ? chip(plain, autoSectionHue(section.id)) : plain;
                }
                if (recipe) result += '</span>';
                result += '<br>';
            }
        } catch (e) {
            return BYD.i18n.t('automation.parse_error');
        }
        return result;
    },

    // Render one action entry (label + variables + comparator/value) AND, for a
    // control-flow action, its nested child/else action lists — so the list-card
    // preview describes a loop body / if-then-else instead of omitting it. `options`
    // is the action catalog (shared by the entry and its nested actions). Recurses.
    describeActionEntry(entry, options) {
        entry = entry || {};
        const data = options.find(option => option.id === entry.type);
        if (!data) return '';
        let out = data.label + ' ';
        const dataVars = (data.variables && data.variables.length) ? data.variables : [];
        const entryVars = entry.variables || {};
        // "Run Action Group" reads best as "Run Action Group (Morning Routine)" — show the
        // group NAME alone, not the redundant "Action group=<name>" clause the generic
        // var loop would produce. The group id is the action's single variable; resolve it
        // to its friendly name (falls back to the raw id via automationValueToText).
        if (entry.type === 'actionGroup') {
            const gvar = dataVars.find(v => v.id === 'groupId') || dataVars[0];
            if (gvar) out += '(' + this.automationValueToText(gvar, entryVars[gvar.id]) + ') ';
            return out;
        }
        const parts = [];
        for (const variable of dataVars) {
            // A shared/older entry has no value for a variable added later: leave it out.
            if (!variable.signalAddress && entryVars[variable.id] === undefined) continue;
            const raw = variable.signalAddress
                ? this._lhsAddress(entryVars[variable.id], entryVars.name, data)
                : entryVars[variable.id];
            parts.push(variable.label + '=' + this.automationValueToText(variable, raw));
        }
        if (parts.length) out += '(' + parts.join(',') + ') ';
        // comparator/value live directly on the entry (not under .variables).
        if (data.comparator && data.value) {
            for (const variable of ['comparator', 'value']) {
                out += this.automationValueToText(data[variable], entry[variable]) + ' ';
            }
        }
        // Nested lists for loop/if. Render each as "then: a; b" / "else: c" so the
        // structure reads. Guard against a malformed non-array. Depth is naturally
        // bounded by the engine's parse cap, so this recursion is finite.
        const nest = (arr, labelKey) => {
            if (!Array.isArray(arr) || arr.length === 0) return '';
            const parts = arr.map(child => this.describeActionEntry(child, options).trim()).filter(Boolean);
            if (!parts.length) return '';
            return BYD.i18n.t(labelKey) + ': ' + parts.join('; ') + ' ';
        };
        if (Array.isArray(entry.childActions) || Array.isArray(entry.elseActions)) {
            out += '[ ';
            out += nest(entry.childActions, entry.type === 'if' ? 'automation.then_actions' : 'automation.loop_body');
            out += nest(entry.elseActions, 'automation.else_actions');
            out += '] ';
        }
        return out;
    },

    // Coarse "N minutes/hours/days ago" for the run-stats line. Falls back to the
    // trigger-count-only display when the timestamp is missing (older automations
    // that were fired before stats tracking existed have triggerCount but no ts).
    formatRelativeTime(ts) {
        if (!ts || ts <= 0) return BYD.i18n.t('automation.stats_never');
        const diffSec = Math.floor((Date.now() - ts) / 1000);
        if (diffSec < 60) return BYD.i18n.t('automation.stats_just_now');
        if (diffSec < 3600) return BYD.i18n.t('automation.stats_minutes_ago').replace('{n}', Math.floor(diffSec / 60));
        if (diffSec < 86400) return BYD.i18n.t('automation.stats_hours_ago').replace('{n}', Math.floor(diffSec / 3600));
        return BYD.i18n.t('automation.stats_days_ago').replace('{n}', Math.floor(diffSec / 86400));
    },

    renderForm() {
        const grid = document.getElementById('formGrid');
        if (!grid) return;

        // A tapped-open description tooltip is now persistent (was hover-only), but this
        // rebuild discards the icon that owns it — so dismiss it and drop the owner, or it
        // would linger with stale content pinned to a removed row.
        this.hideDescriptionTooltip();
        this._tipOwner = null;

        const sections = this.schemaSections();
        if (!sections.length) {
            grid.innerHTML = '';
            const msg = document.createElement('div');
            msg.classList.add('form-empty');
            msg.textContent = BYD.i18n.t('automation.failed_to_load_schema');
            grid.append(msg);
            return;
        }

        grid.innerHTML = '';
        // Execution mode is top-level metadata rather than part of the action/condition
        // schema. Manual-only keeps the configured On Change rule for a future switch
        // back to Automatic, but the daemon does not subscribe to or evaluate it.
        grid.append(this.createModeField());
        // Optional friendly name — a top-level field (not schema-driven) so a user can
        // label an automation ("Arm sentry at night") for the list and the
        // "Control Automation" picker. Absent/blank → the field is simply not emitted,
        // keeping older automations byte-identical.
        grid.append(this.createNameField());
        for (let section of sections) {
            grid.append(this.createSection(section));
        }
        // Cloud-dependency notice: lock/unlock/flash/find-car have NO local SDK path on
        // this platform — they need BYD Cloud. If the current automation uses one and
        // cloud isn't configured, surface an inline warning so the rule doesn't silently
        // no-op at fire time. Fetched lazily + cached; the banner re-evaluates on each
        // render (adding/removing a cloud action updates it).
        this._maybeRenderCloudWarning(grid);
        // Re-assert the Save gate: an add/delete changes which fields are .invalid but
        // fires no 'change', so deleting the only invalid row left Save stuck
        // .is-disabled — and that class is pointer-events:none, a dead button.
        this._syncSaveDisabled();
    },

    // Action ids with no local SDK fallback (cloud-only on this generation).
    _CLOUD_ONLY_ACTIONS: ['lock', 'unlock', 'flash', 'findCar'],

    // Show a warning at the top of the form when the automation uses a cloud-only action
    // but BYD Cloud isn't configured. Non-blocking: fetches cloud status once (cached on
    // this._cloudConfigured) and inserts the banner when it resolves.
    _maybeRenderCloudWarning(grid) {
        const usesCloud = this._formUsesCloudOnlyAction();
        if (!usesCloud) return;
        const show = () => {
            if (this._cloudConfigured) return; // configured → no warning
            if (grid.querySelector('.cloud-warning')) return; // already shown
            const warn = document.createElement('div');
            warn.classList.add('cloud-warning', 'field-help');
            warn.textContent = BYD.i18n.t('automation.cloud_required_warning');
            grid.insertBefore(warn, grid.firstChild);
        };
        if (this._cloudConfigured != null) { show(); return; }
        fetch('/api/vehicle/cloud-status', { cache: 'no-store' })
            .then(r => r.json())
            .then(j => { this._cloudConfigured = !!(j && j.configured); })
            .catch(() => { this._cloudConfigured = true; }) // fail-open: don't nag on a read glitch
            .then(() => { if (document.getElementById('formGrid') === grid) show(); });
    },

    // True if any action (incl. nested loop/if child lists) in the current form is a
    // cloud-only action. Walks the primary + else lists recursively.
    _formUsesCloudOnlyAction() {
        const cloud = this._CLOUD_ONLY_ACTIONS;
        const walk = (list) => {
            if (!Array.isArray(list)) return false;
            for (const a of list) {
                if (!a) continue;
                if (cloud.indexOf(a.type) >= 0) return true;
                if (walk(a.childActions) || walk(a.elseActions)) return true;
            }
            return false;
        };
        return walk(this.formData.actions) || walk(this.formData.elseActions);
    },

    // True if a single manualClip action's before+after seconds is a valid window:
    // each 0..60 AND the combined total 1..60. Mirrors the daemon's ManualClipWindow.create
    // cross-field rule, which per-field IntType validation (and thus the .invalid gate)
    // cannot express — so without this a "0s + 0s" or "40s + 40s" clip passes the form but
    // is rejected on save ("failed to save" with no field reason). Absent/blank fields
    // parse as 0 (matches the daemon reading a missing/edited value); an entry whose window
    // is invalid returns false here.
    _manualClipWindowValid(a) {
        const toInt = (v) => {
            if (v == null || v === '') return 0;
            const n = parseInt(v, 10);
            return isNaN(n) ? 0 : n;
        };
        const vars = a.variables || {};
        const before = toInt(vars.beforeSeconds);
        const after = toInt(vars.afterSeconds);
        if (before < 0 || before > 60 || after < 0 || after > 60) return false;
        const total = before + after;
        return total >= 1 && total <= 60;
    },

    // True if ANY manualClip action in `lists` (recursing children/else) has an invalid
    // window. Used to block Save + show a specific message, since the daemon rejects the
    // whole automation/group on such an action. Shared by the automation form and the
    // group editor (pass the group's actions).
    _hasInvalidManualClip(lists) {
        const walk = (list) => {
            if (!Array.isArray(list)) return false;
            for (const a of list) {
                if (!a) continue;
                if (a.type === 'manualClip' && !this._manualClipWindowValid(a)) return true;
                if (walk(a.childActions) || walk(a.elseActions)) return true;
            }
            return false;
        };
        for (const l of lists) { if (walk(l)) return true; }
        return false;
    },

    createModeField() {
        const section = document.createElement('div');
        section.classList.add('mode-section', 'section');

        const label = document.createElement('div');
        label.classList.add('row-label');
        label.textContent = BYD.i18n.t('automation.mode_label');
        section.append(label);

        const select = document.createElement('select');
        select.classList.add('select-control', 'automation-form-mode-select');
        for (const mode of ['automatic', 'manual', 'disabled']) {
            const option = document.createElement('option');
            option.value = mode;
            option.textContent = BYD.i18n.t('automation.mode_' + mode);
            select.append(option);
        }
        select.value = this.getAutomationMode(this.formData);
        select.addEventListener('change', () => this.applyModeToForm(select.value));
        section.append(select);
        return section;
    },

    // A single free-text "Name" input bound to formData.name. Trimmed on input; an
    // empty value deletes the key so the saved JSON stays clean (and identical to a
    // pre-name automation).
    createNameField() {
        const section = document.createElement('div');
        section.classList.add('name-section', 'section');

        const label = document.createElement('div');
        label.classList.add('row-label');
        label.textContent = BYD.i18n.t('automation.name_label');
        section.append(label);

        const input = document.createElement('input');
        input.type = 'text';
        input.classList.add('input', 'text', 'name-input');
        input.maxLength = 64;
        input.placeholder = BYD.i18n.t('automation.name_placeholder');
        input.value = (this.formData && this.formData.name) ? this.formData.name : '';
        input.addEventListener('input', () => {
            const v = input.value.trim();
            if (v) this.formData.name = v;
            else delete this.formData.name;
        });
        section.append(input);
        return section;
    },

    createSection(section) {
        const token = sanitizeToken(section.id);
        const sectionDiv = document.createElement('div');
        sectionDiv.classList.add(token + '-section', 'section');
        sectionDiv.setAttribute('data-section', section.id);

        if (section.options && section.options.length) {
            const required = (section.required != null) ? section.required : 0;
            if (!this.formData[section.id]) this.formData[section.id] = [];
            while (this.formData[section.id].length < required) this.formData[section.id].push({});
            sectionDiv.append(this.createSectionHead(section));
            // Optional AND/OR combining toggle for a section that advertises `logic`
            // (currently the conditions section). Stored on formData under the
            // descriptor's field name (e.g. "conditionLogic"); absent → server default.
            if (section.logic && section.logic.field) {
                sectionDiv.append(this.createLogicToggle(section.logic));
            }
            for (let i = 0; i < this.formData[section.id].length; i++) {
                if (i > 0) {
                    const join = this.createRowLabel({ id: section.id, label: this.rowJoinerLabel(section,
                        section.logic && section.logic.field && this.formData[section.logic.field]) });
                    join.classList.add('step-join');
                    sectionDiv.append(join);
                }
                const row = this.createRow(section, i);
                const rowCount = this.formData[section.id].length;
                const showReorder = rowCount > 1;   // only meaningful with 2+ rows
                const showDelete = rowCount > required;
                if (showReorder || showDelete) {
                    const actionsContainer = document.createElement('div');
                    actionsContainer.classList.add(token + '-delete-container', 'delete-container');

                    // Reorder arrows — move this step up/down so a chain runs in the
                    // order the user wants (actions run top-to-bottom). Swap adjacent
                    // entries in formData and re-render. First row has no "up", last no
                    // "down" (rendered disabled so the column stays aligned).
                    if (showReorder) {
                        const upBtn = document.createElement('button');
                        upBtn.classList.add(token + '-move', 'move-up', 'icon-btn');
                        upBtn.title = BYD.i18n.t('automation.move_up');
                        upBtn.innerHTML = moveUpIcon;
                        if (i === 0) {
                            upBtn.disabled = true;
                            upBtn.classList.add('unknown');
                        } else {
                            upBtn.addEventListener('click', () => {
                                const arr = this.formData[section.id];
                                const tmp = arr[i - 1]; arr[i - 1] = arr[i]; arr[i] = tmp;
                                this.renderForm();
                            });
                        }
                        actionsContainer.append(upBtn);

                        const downBtn = document.createElement('button');
                        downBtn.classList.add(token + '-move', 'move-down', 'icon-btn');
                        downBtn.title = BYD.i18n.t('automation.move_down');
                        downBtn.innerHTML = moveDownIcon;
                        if (i === rowCount - 1) {
                            downBtn.disabled = true;
                            downBtn.classList.add('unknown');
                        } else {
                            downBtn.addEventListener('click', () => {
                                const arr = this.formData[section.id];
                                const tmp = arr[i + 1]; arr[i + 1] = arr[i]; arr[i] = tmp;
                                this.renderForm();
                            });
                        }
                        actionsContainer.append(downBtn);
                    }

                    if (showDelete) {
                        const deleteBtn = document.createElement('button');
                        deleteBtn.classList.add(token + '-delete', 'delete', 'icon-btn', 'danger');
                        deleteBtn.title = BYD.i18n.t('automation.delete_row');
                        deleteBtn.innerHTML = deleteIcon;
                        deleteBtn.addEventListener('click', () => {
                            this.formData[section.id].splice(i, 1);
                            this.renderForm();
                        });
                        actionsContainer.append(deleteBtn);
                    }
                    row.append(actionsContainer);
                }
                sectionDiv.append(row);
            }
            if (this.formData[section.id].length === 0) {
                sectionDiv.append(this.createRowElement(section));
            }
            const addBtn = document.createElement('button');
            addBtn.classList.add('btn', 'btn-secondary', token + '-add-button', 'add-button');
            addBtn.innerHTML = BYD.i18n.t('automation.add_row') + ' +';
            addBtn.addEventListener('click', () => {
                this.formData[section.id].push({});
                this.renderForm();
            });
            sectionDiv.append(addBtn);

            // Nested condition GROUPS — only for a section that advertises `groups`
            // (the conditions section). Entirely separate from the flat rows above:
            // groups live in formData.conditionGroups and are combined with the flat
            // conditions under conditionLogic. This block is additive — when the user
            // adds no groups, formData.conditionGroups stays absent and the saved
            // automation is byte-identical to before.
            if (section.groups) {
                sectionDiv.append(this.createConditionGroups(section));
            }
        } else {
            sectionDiv.append(this.createSectionHead(section));
            const row = this.createRowElement(section);
            const initial = (this.formData[section.id] != null) ? this.formData[section.id] : section.min;
            row.append(this.createInput(section, initial, (element, value) => {
                this.formData[section.id] = value;
            }));
            sectionDiv.append(row);
        }
        return sectionDiv;
    },

    // Build the nested-condition-group editor. Renders formData.conditionGroups (an
    // array of { logic, conditions:[{type,comparator,value,variables}], groups:[] });
    // the UI edits ONE level of groups (each a set of condition rows + its own AND/OR),
    // which covers (A AND B) OR (C AND D). Uses the same option catalog + leaf inputs
    // as flat conditions, but is wholly independent of the flat-row path so it can't
    // regress it. `section` is the conditions schema section (carries options).
    createConditionGroups(section) {
        const wrap = document.createElement('div');
        wrap.classList.add('condition-groups');
        const groups = Array.isArray(this.formData.conditionGroups) ? this.formData.conditionGroups : null;
        // Only materialise the array once the user actually adds a group, so an
        // untouched automation never gains an empty conditionGroups key on save.
        const ensureGroups = () => {
            if (!Array.isArray(this.formData.conditionGroups)) this.formData.conditionGroups = [];
            return this.formData.conditionGroups;
        };

        if (groups && groups.length) {
            for (let gi = 0; gi < groups.length; gi++) {
                wrap.append(this.createOneConditionGroup(section, groups[gi], gi));
            }
        }
        const addGroup = document.createElement('button');
        addGroup.classList.add('btn', 'btn-secondary', 'add-group-button');
        addGroup.innerHTML = BYD.i18n.t('automation.add_group') + ' +';
        addGroup.addEventListener('click', () => {
            ensureGroups().push({ logic: 'AND', conditions: [{}], groups: [] });
            this.renderForm();
        });
        wrap.append(addGroup);
        return wrap;
    },

    // One group card: an AND/OR toggle + its condition rows + add/delete controls.
    createOneConditionGroup(section, group, gi) {
        const card = document.createElement('div');
        card.classList.add('condition-group', 'card');

        // Header: label + delete-group.
        const header = document.createElement('div');
        header.classList.add('condition-group-header', 'row');
        const title = document.createElement('span');
        title.classList.add('label');
        title.textContent = BYD.i18n.t('automation.condition_group') + ' ' + (gi + 1);
        header.append(title);
        const del = document.createElement('button');
        del.classList.add('delete', 'icon-btn', 'danger');
        del.title = BYD.i18n.t('automation.delete_group');
        del.innerHTML = deleteIcon;
        del.addEventListener('click', () => {
            this.formData.conditionGroups.splice(gi, 1);
            // Drop the array entirely if it's now empty, so save stays byte-clean.
            if (this.formData.conditionGroups.length === 0) delete this.formData.conditionGroups;
            this.renderForm();
        });
        header.append(del);
        card.append(header);

        // Per-group AND/OR toggle → writes group.logic.
        const logicSel = document.createElement('select');
        logicSel.classList.add('input', 'enum', 'logic-select');
        for (const opt of [['AND', 'automation.logic_and'], ['OR', 'automation.logic_or']]) {
            const o = document.createElement('option');
            o.value = opt[0];
            o.textContent = BYD.i18n.t(opt[1]);
            if ((group.logic || 'AND') === opt[0]) o.selected = true;
            logicSel.append(o);
        }
        logicSel.addEventListener('change', () => { group.logic = logicSel.value; });
        card.append(logicSel);
        card.append(this.createLogicSegments(logicSel));

        // Condition rows within the group. Reuses the leaf inputs (type/comparator/
        // value) via createInput, backed by group.conditions[ci].
        if (!Array.isArray(group.conditions)) group.conditions = [];
        // A term-less group is SILENTLY DROPPED on save (Automation.fromJson) — deleting a
        // group's last row saved "successfully" and the group was gone on reopen. Re-seed an
        // empty row (the flat sections' `required` rule) so that state is unreachable.
        if (!group.conditions.length
                && !(Array.isArray(group.groups) && group.groups.length)) {
            group.conditions.push({});
        }
        for (let ci = 0; ci < group.conditions.length; ci++) {
            card.append(this.createGroupConditionRow(section, group, ci));
        }
        const addCond = document.createElement('button');
        addCond.classList.add('btn', 'btn-secondary', 'add-button');
        addCond.innerHTML = BYD.i18n.t('automation.add_row') + ' +';
        addCond.addEventListener('click', () => {
            group.conditions.push({});
            this.renderForm();
        });
        card.append(addCond);
        return card;
    },

    // One condition row inside a group. Mirrors the flat createRow's type→comparator/
    // value flow but is backed by group.conditions[ci] (NOT formData[section.id]), so
    // the flat-row code path is untouched. options come from the conditions section.
    createGroupConditionRow(section, group, ci) {
        const options = section.options || [];
        const row = document.createElement('div');
        row.classList.add('condition-group-row', 'row');
        const inputs = document.createElement('div');
        inputs.classList.add('inputs');
        const stepIcon = this.createStepIcon();
        const stepTag = this.createStepTag();
        this.paintStep(row, stepIcon, stepTag, null, 'if');

        // (Re)build the comparator/value + enum sub-variable inputs for the CURRENT
        // entry object at group.conditions[ci] and the selected option descriptor.
        // Always reads the live array slot (not a stale capture), so a type change that
        // replaces the slot object still binds handlers to the new object.
        const rebuild = (selected) => {
            inputs.querySelectorAll('.variable-input').forEach(el => el.remove());
            inputs.classList.remove('has-cond-value');   // re-added below only if a value editor renders
            const e = group.conditions[ci];
            if (!e.variables) e.variables = {};
            // Enum sub-variables (e.g. area/type) the condition declares.
            const vars = (selected && selected.variables && selected.variables.length) ? selected.variables : [];
            for (const variable of vars) {
                const sel = this.createInput(variable, e.variables[variable.id], (element, value) => {
                    e.variables[variable.id] = value;
                });
                sel.classList.add('variable-input');
                inputs.append(sel);
            }
            // Condition leaves carry comparator + value, exactly like flat conditions.
            // Value uses the dynamic-capable editor (constant / ${var:…} / ${signal:…}).
            if (selected && selected.comparator && selected.value) {
                const compInp = this.createInput(selected.comparator, e.comparator, (element, value) => { e.comparator = value; });
                compInp.classList.add('variable-input', 'comparator');
                inputs.append(compInp);
                const valInp = this.createConditionValueInput(selected.value, e.value,
                    (value) => { e.value = value; }, options, e.type);
                valInp.classList.add('variable-input', 'value');
                // Top-align this row: the value editor is a 2-line stack (mode toggle above
                // the input), taller than the sibling type/comparator selects. Without this
                // the row's center-alignment floats the value box below the selects — the
                // reported "box moves down / misaligned" strip.
                inputs.classList.add('has-cond-value');
                inputs.append(valInp);
            }
        };

        const typeSel = this.createEnumInput(this.getTypeEnum('cg', options), group.conditions[ci].type,
            (element, value, selected) => {
                // Reset the slot on a real type change (mirrors flat createRow's reset).
                if (group.conditions[ci].type !== value) group.conditions[ci] = { type: value };
                this.paintStep(row, stepIcon, stepTag, selected, 'if');
                rebuild(selected);
            });
        inputs.append(typeSel);
        // Edit path: seed the trailing inputs for an already-chosen type.
        if (group.conditions[ci].type) {
            const sel = options.find(o => o.id === group.conditions[ci].type);
            if (sel) rebuild(sel);
        }
        inputs.prepend(stepTag);
        row.append(inputs);
        row.prepend(stepIcon);

        // Row delete only while the group has a spare row (flat sections' showDelete =
        // rowCount > required). Removing the last term is what the group's own delete
        // button in the header is for.
        if (group.conditions.length > 1) {
            const del = document.createElement('button');
            del.classList.add('delete', 'icon-btn', 'danger');
            del.title = BYD.i18n.t('automation.delete_row');
            del.innerHTML = deleteIcon;
            del.addEventListener('click', () => {
                group.conditions.splice(ci, 1);
                this.renderForm();
            });
            row.append(del);
        }
        return row;
    },

    // A nested action editor (loop body / if-then / if-else) backed by an arbitrary
    // array `arr` of {type,variables,...} entries. Independent of the flat action path
    // (createRow) so it can't regress it. `options` is the action catalog; `labelKey`
    // localizes the sub-list heading. Recurses: a nested loop/if renders its own
    // nested editor via createNestedActionRow → the same eventListener-free path.
    // `rerender` is the callback used to re-draw the surrounding form after an
    // add/delete. It defaults to renderForm() (the automation editor), but the Action
    // Group editor passes its own renderGroupForm() so the SAME nested-action widget
    // drives either form. Nested control-flow (if/loop) inside a group propagates it.
    // `depth` (default 1) = this list's control-flow nesting level, so the row picker can
    // hide If/Loop once the engine's MAX_ACTION_DEPTH cap is reached (see getTypeEnum).
    createNestedActions(options, arr, labelKey, rerender, depth) {
        const rr = rerender || (() => this.renderForm());
        const d = depth || 1;
        const box = document.createElement('div');
        box.classList.add('nested-actions', 'variable-input'); // variable-input so the row cleanup removes it on type change
        const branch = autoPick(AUTO_BRANCH_HUES, labelKey) || 'group';
        box.setAttribute('data-branch', branch);
        const heading = document.createElement('div');
        heading.classList.add('nested-actions-label', 'label');
        heading.textContent = BYD.i18n.t(labelKey);
        const branchIcon = document.createElement('span');
        branchIcon.classList.add('branch-icon');
        branchIcon.setAttribute('aria-hidden', 'true');
        branchIcon.innerHTML = autoIconSvg(branch);
        heading.insertBefore(branchIcon, heading.firstChild);
        box.append(heading);
        for (let i = 0; i < arr.length; i++) {
            box.append(this.createNestedActionRow(options, arr, i, rr, d));
        }
        const add = document.createElement('button');
        add.classList.add('btn', 'btn-secondary', 'add-button');
        add.innerHTML = BYD.i18n.t('automation.add_row') + ' +';
        add.addEventListener('click', () => {
            arr.push({});
            rr();
        });
        box.append(add);
        return box;
    },

    // One nested action row backed by arr[i]. Mirrors createRow's type→variables flow
    // but writes into arr[i] (not formData[section.id]). Reuses createInput for the
    // action's variables, and recurses for a nested control-flow action's own children.
    // `depth` = this row's control-flow nesting level (1 = a top-level If/Loop's body).
    createNestedActionRow(options, arr, i, rerender, depth) {
        const rr = rerender || (() => this.renderForm());
        const d = depth || 1;
        const row = document.createElement('div');
        row.classList.add('nested-action-row', 'row');
        const inputs = document.createElement('div');
        inputs.classList.add('inputs');
        const stepIcon = this.createStepIcon();
        const stepTag = this.createStepTag();
        this.paintStep(row, stepIcon, stepTag, null, 'other');

        const rebuild = (selected) => {
            inputs.querySelectorAll('.nested-var').forEach(el => el.remove());
            const e = arr[i];
            if (!e.variables) e.variables = {};
            const vars = (selected && selected.variables && selected.variables.length) ? selected.variables : [];
            for (const variable of vars) {
                let inp;
                if (variable.dynamic) {
                    // Dynamic RHS for a NESTED inline flow action (an If/Loop/Wait-Until
                    // inside a group or another flow action) — same Value/Variable/Signal
                    // editor as the top-level path, so nesting doesn't lose the capability.
                    // Free-TEXT RHS when the LHS compares as a string (a user variable, a zone
                    // name, an SSID); an enum dropdown when the LHS signal is enum-valued.
                    const nRhs = this.signalIsStringValued(e.variables.event)
                        ? this.asStringSchema(variable)
                        : this.dynamicValueSchema(variable, e.variables.event);
                    inp = this.createConditionValueInput(
                        nRhs, e.variables[variable.id],
                        (value) => { e.variables[variable.id] = value; },
                        this.conditionCatalog(), null);
                } else if (variable.id === 'state' && selected && this.hasSignalAddress(selected)) {
                    // Awaited state for a NESTED wait-until-signal — same vocabulary dropdown.
                    const nState = this.awaitedStateSchema(variable, e.variables.event);
                    inp = this.createInput(nState, e.variables[variable.id],
                        (element, value) => { e.variables[variable.id] = value; });
                } else if (variable.signalAddress) {
                    // LHS signal picker for a NESTED flow action — same catalog + attribute
                    // selectors as the top-level path, so nesting doesn't lose the capability.
                    const nExtra = selected && selected.nameField
                        ? [{ id: 'variable', label: BYD.i18n.t('automation.cond_mode_var') }] : [];
                    this._foldLhsVariableName(e.variables, variable.id, selected);
                    inp = this.createSignalAddressInput(
                        variable, e.variables[variable.id],
                        (value, quiet) => {
                            const prev = e.variables[variable.id];
                            e.variables[variable.id] = value;
                            if (quiet) return;
                            // Same guarded re-render as the top-level path: only a change into
                            // or out of "variable" alters which sibling fields apply.
                            // Same as the top-level path: sibling fields (awaited-state
                            // vocabulary, variable name/comparator) depend on the chosen signal.
                            if (prev !== value) rr();
                        },
                        this.conditionCatalog(), nExtra);
                } else if (this.isLhsEventVariable(variable, selected)) {
                    // LHS event picker with a Variable operand — re-render on change so the
                    // name field + eq/neq comparator swap appear (same as the top-level path).
                    // Re-render ONLY on a genuine change: createEnumInput fires this listener
                    // synchronously at build time (value === the stored event), and rr() there
                    // rebuilds this same picker → fires on build again → unbounded recursion
                    // that overflows the stack and freezes the editor. Guard with prev !== value.
                    inp = this.createInput(variable, e.variables[variable.id], (element, value) => {
                        const prev = e.variables[variable.id];
                        e.variables[variable.id] = value;
                        if (prev !== value) rr();
                    });
                } else if (variable.id === 'comparator'
                        && (this.signalEnumOptions(e.variables.event)
                            || this.signalIsStringValued(e.variables.event))) {
                    // String LHS → constrain the comparator to eq/neq. Same for an enum-valued
                    // signal LHS, which is also string-backed (see the top-level path).
                    inp = this.createInput(this.eqNeqComparator(variable), e.variables[variable.id], (element, value) => {
                        e.variables[variable.id] = value;
                    });
                } else {
                    inp = this.createInput(variable, e.variables[variable.id], (element, value) => {
                        e.variables[variable.id] = value;
                    });
                }
                inp.classList.add('nested-var');
                inputs.append(inp);
                // After the LHS event picker set to "variable", render the bespoke name field.
                if (this.isLhsEventVariable(variable, selected)
                        && e.variables[variable.id] === 'variable'
                        && selected.nameField) {
                    const nameInp = this.createInput(selected.nameField, e.variables.name, (element, value) => {
                        e.variables.name = value;
                    });
                    nameInp.classList.add('nested-var');
                    inputs.append(nameInp);
                }
            }
            // A nested control-flow action gets its OWN nested editors, one level deeper.
            // Passing d+1 lets the child picker hide If/Loop at the engine's depth cap so
            // you can't build a tree that would be rejected on save. Propagate rr so a
            // group-editor context keeps re-rendering the group form, not the automation.
            if (selected && selected.hasChildActions) {
                if (!Array.isArray(e.childActions)) e.childActions = [];
                const child = this.createNestedActions(options, e.childActions,
                    (e.type === 'if') ? 'automation.then_actions' : 'automation.loop_body', rr, d + 1);
                child.classList.add('nested-var');
                inputs.append(child);
                if (selected.hasElseActions) {
                    if (!Array.isArray(e.elseActions)) e.elseActions = [];
                    const elseBox = this.createNestedActions(options, e.elseActions, 'automation.else_actions', rr, d + 1);
                    elseBox.classList.add('nested-var');
                    inputs.append(elseBox);
                }
            }
        };

        const typeSel = this.createEnumInput(this.getTypeEnum('na', options, d >= this.maxActionDepth()), arr[i].type,
            (element, value, selected) => {
                if (arr[i].type !== value) arr[i] = { type: value };
                this.paintStep(row, stepIcon, stepTag, selected, 'other');
                rebuild(selected);
            });
        inputs.append(typeSel);
        if (arr[i].type) {
            const sel = options.find(o => o.id === arr[i].type);
            if (sel) rebuild(sel);
        }
        inputs.prepend(stepTag);
        row.append(inputs);
        row.prepend(stepIcon);

        // Reorder + delete controls, mirroring the top-level actions section (createSection).
        // Actions run top-to-bottom, so a group / if-branch / loop-body needs up/down to
        // order its steps. Arrows show only with 2+ rows; first has no "up", last no "down"
        // (rendered disabled so the column stays aligned). Swap adjacent entries in `arr`
        // and re-render via rr (renderGroupForm for a group, renderForm for an automation).
        const actionsContainer = document.createElement('div');
        actionsContainer.classList.add('delete-container');
        if (arr.length > 1) {
            const upBtn = document.createElement('button');
            upBtn.classList.add('move-up', 'icon-btn');
            upBtn.title = BYD.i18n.t('automation.move_up');
            upBtn.innerHTML = moveUpIcon;
            if (i === 0) { upBtn.disabled = true; upBtn.classList.add('unknown'); }
            else upBtn.addEventListener('click', () => {
                const tmp = arr[i - 1]; arr[i - 1] = arr[i]; arr[i] = tmp; rr();
            });
            actionsContainer.append(upBtn);

            const downBtn = document.createElement('button');
            downBtn.classList.add('move-down', 'icon-btn');
            downBtn.title = BYD.i18n.t('automation.move_down');
            downBtn.innerHTML = moveDownIcon;
            if (i === arr.length - 1) { downBtn.disabled = true; downBtn.classList.add('unknown'); }
            else downBtn.addEventListener('click', () => {
                const tmp = arr[i + 1]; arr[i + 1] = arr[i]; arr[i] = tmp; rr();
            });
            actionsContainer.append(downBtn);
        }

        const del = document.createElement('button');
        del.classList.add('delete', 'icon-btn', 'danger');
        del.title = BYD.i18n.t('automation.delete_row');
        del.innerHTML = deleteIcon;
        del.addEventListener('click', () => {
            arr.splice(i, 1);
            rr();
        });
        actionsContainer.append(del);
        row.append(actionsContainer);
        return row;
    },

    // Render the AND/OR combining selector for a section that advertises a `logic`
    // descriptor. The chosen value is written to this.formData[field] so it is
    // serialized with the rest of the automation on save. Defaults to the
    // descriptor's default (or the current formData value when editing).
    createLogicToggle(logic) {
        const field = logic.field;
        const options = logic.options || [];
        const current = (this.formData[field] != null) ? this.formData[field] : (logic.default || 'AND');
        // Seed formData so a save without touching the control still persists a value.
        this.formData[field] = current;

        const row = document.createElement('div');
        row.classList.add('logic-toggle', 'row');

        const selector = document.createElement('select');
        selector.classList.add('input', 'enum', 'logic-select');
        for (const opt of options) {
            const o = document.createElement('option');
            o.value = opt.value;
            o.textContent = this.optionLabel({ id: opt.value, label: opt.label });
            if (opt.value === current) o.selected = true;
            selector.appendChild(o);
        }
        selector.addEventListener('change', () => {
            this.formData[field] = selector.value;
            const label = this.rowJoinerLabel({ logic }, selector.value);
            if (row.parentElement) {
                Array.prototype.forEach.call(row.parentElement.children, el => {
                    if (el.classList.contains('step-join')) el.textContent = label;
                });
            }
        });
        row.append(selector);
        row.append(this.createLogicSegments(selector));
        return row;
    },

    // Word between two rows: a change in ANY trigger re-checks the automation, conditions follow
    // their AND/OR toggle, and every action runs.
    rowJoinerLabel(section, logicValue) {
        if (section.id === 'triggers') return BYD.i18n.t('automation.logic_or');
        if (section.logic && section.logic.field) {
            const lg = String(logicValue || section.logic.default || 'AND');
            return BYD.i18n.t('automation.logic_' + lg.toLowerCase());
        }
        return BYD.i18n.t('automation.extra_row');
    },

    // Condition VALUE editor with an optional dynamic-reference mode (expression engine).
    // Renders a small mode toggle (Value / Variable / Signal) above the value input:
    //   • Value   → the normal typed input (createInput) — the default, unchanged path.
    //   • Variable→ a text field; emits ${var:NAME} (compares against a Set-Variable).
    //   • Signal  → a dropdown of the other condition signals; emits ${signal:TYPE}
    //               (compares one live signal against another, e.g. cabinTemp>outsideTemp).
    // Backward-compatible: a stored plain value opens in Value mode; only a ${…} token
    // opens in a dynamic mode. `onChange(value)` receives either the constant or the
    // ${…} token string. `signalOptions` is the conditions catalog (for the Signal list);
    // when absent, only Value + Variable modes are offered.
    // True when `variable` is the LHS "event" picker of a flow action (If/Wait-Until/Loop)
    // that offers a "variable" operand — detected by the schema carrying a nameField AND the
    // enum listing a "variable" option. Used to hook a re-render + show the name field.
    isLhsEventVariable(variable, selected) {
        return !!(selected && selected.nameField
            && variable && variable.id === 'event'
            && Array.isArray(variable.options)
            && variable.options.some(o => o && o.id === 'variable'));
    },

    // A shallow copy of a comparator enum schema whose options are narrowed to eq/neq —
    // the only comparisons meaningful for a string-valued LHS (a user variable, or an
    // enum-valued signal like Daylight/gear).
    eqNeqComparator(comparatorSchema) {
        const copy = {};
        for (const k in comparatorSchema) if (comparatorSchema.hasOwnProperty(k)) copy[k] = comparatorSchema[k];
        copy.options = (comparatorSchema.options || []).filter(o => o && (o.id === 'eq' || o.id === 'neq'));
        // strictOptions: an out-of-list stored value here (e.g. a leftover gt/lt from before
        // the LHS was switched to a string variable) is CONTEXTUALLY excluded — gt/lt still
        // exist in the base comparator schema, they're just meaningless against a string and
        // would silently evaluate false. So it must NOT be preserved as "(unavailable on this
        // device)" (which is both a lie — it IS available — and a footgun: the rule would
        // save and never fire). Force the strict .invalid path so the user re-picks eq/neq.
        copy.strictOptions = true;
        return copy;
    },

    // A shallow copy of a dynamic value schema forced to STRING type — used for the RHS
    // constant editor when the LHS is a string-valued variable, so the user can type a word
    // ("Sport_Mode") instead of being restricted to the numeric picker. Keeps `dynamic` so
    // the Value/Variable/Signal toggle still layers on. maxLength mirrors a user variable.
    asStringSchema(valueSchema) {
        const copy = {};
        for (const k in valueSchema) if (valueSchema.hasOwnProperty(k)) copy[k] = valueSchema[k];
        copy.type = 'string';
        if (copy.maxLength == null) copy.maxLength = 64;
        return copy;
    },

    // The LEFT-hand signal picker for the flow actions (if/else, wait-until, wait-until-signal,
    // loop): the full conditions catalog plus a selector for each attribute the chosen signal
    // declares, so any of the ~58 conditions — including the attributed ones (a specific door,
    // seat, light, window) — can be tested, not just the handful each action used to hardcode.
    //
    // Stores a BARE address ("gear", "speed:units=kmph", "lights:area=lowBeam"), not a
    // ${signal:…} token: this field NAMES the operand rather than being compared as a value.
    // The server parses both forms (AutomationCondition.resolveSignalAddress), so a legacy
    // stored id keeps working and is shown as-is until the user picks something else.
    // ── Location-zone picker ──────────────────────────────────────────────────────────
    // The locationZone condition matches a ZONE NAME (a Safe Location), or the literal "none"
    // when the car is outside every zone — it is not a coordinate field, which is the part
    // users find confusing. So render a dropdown of the zones that actually exist plus "none",
    // and let a zone be created inline (name + lat/long + radius) so the user never has to
    // leave the editor to make one.
    //
    // Creating a zone posts to the SAME endpoint the Surveillance page uses, so the geofence
    // itself is still owned by one engine (haversine + 20m exit hysteresis + the 10-zone cap).
    // Nothing about how the condition is EVALUATED changes: the daemon keeps publishing a zone
    // name and the stored value is still that name, so existing automations are untouched.
    _zoneCache: null,

    loadZones() {
        if (this._zoneCache) return Promise.resolve(this._zoneCache);
        return fetch('/api/surveillance/safe-locations')
            .then(r => r.json())
            .then(d => {
                this._zoneCache = {
                    zones: (d && Array.isArray(d.zones)) ? d.zones : [],
                    hasGps: !!(d && d.hasGps),
                    lat: (d && d.lat != null) ? d.lat : null,
                    lng: (d && d.lng != null) ? d.lng : null
                };
                return this._zoneCache;
            })
            .catch(() => {
                // Offline/daemon-down: fall back to a free-text field rather than an empty
                // dropdown that could not express any zone.
                this._zoneCache = { zones: [], hasGps: false, lat: null, lng: null, failed: true };
                return this._zoneCache;
            });
    },

    // Is this the locationZone condition's value field?
    isLocationZoneField(selfType, schema) {
        return selfType === 'locationZone' && schema && schema.id === 'zone';
    },

    createLocationZoneInput(schema, currentValue, onChange) {
        const wrap = document.createElement('div');
        wrap.classList.add('cond-value-wrap', 'zone-picker');

        const sel = document.createElement('select');
        sel.classList.add('input', 'enum');
        const form = document.createElement('div');
        form.classList.add('zone-new-form');
        const hint = document.createElement('div');
        hint.classList.add('field-help');
        wrap.append(sel, form, hint);

        const NEW = '__new__';
        const rebuild = (data) => {
            sel.innerHTML = '';
            const add = (val, text) => {
                const o = document.createElement('option');
                o.value = val; o.textContent = text; sel.append(o);
                return o;
            };
            for (const z of data.zones) add(z.name, z.name);
            add('none', BYD.i18n.t('automation.zone_none'));
            // A stored name whose zone was since renamed/removed must NOT be silently blanked
            // just by opening the form — keep it selectable and flag it.
            const stored = (typeof currentValue === 'string') ? currentValue.trim() : '';
            if (stored && stored !== 'none' && !data.zones.some(z => z.name === stored)) {
                add(stored, stored + ' ' + BYD.i18n.t('automation.cond_signal_missing'));
            }
            add(NEW, BYD.i18n.t('automation.zone_new'));
            sel.value = stored || 'none';
            hint.textContent = BYD.i18n.t('automation.zone_hint');
        };

        // Free-text fallback when the zone list can't be fetched.
        const asText = () => {
            wrap.innerHTML = '';
            const inp = this.createInput(schema, currentValue, (el, v) => onChange(v));
            const h = document.createElement('div');
            h.classList.add('field-help');
            h.textContent = BYD.i18n.t('automation.zone_hint');
            wrap.append(inp, h);
        };

        this.loadZones().then((data) => {
            if (data.failed) { asText(); return; }
            rebuild(data);
            sel.addEventListener('change', () => {
                if (sel.value === NEW) { renderNewForm(data); return; }
                form.innerHTML = '';
                onChange(sel.value);
            });
        });

        const renderNewForm = (data) => {
            form.innerHTML = '';
            const mk = (labelKey, value, attrs) => {
                const l = document.createElement('label');
                l.classList.add('zone-field');
                const t = document.createElement('span');
                t.textContent = BYD.i18n.t(labelKey);
                const i = document.createElement('input');
                i.classList.add('input');
                for (const k in attrs) if (attrs.hasOwnProperty(k)) i.setAttribute(k, attrs[k]);
                if (value != null) i.value = value;
                l.append(t, i);
                form.append(l);
                return i;
            };
            const nameI = mk('automation.zone_name', '', { type: 'text', maxlength: '40' });
            const latI = mk('automation.zone_lat', data.lat != null ? data.lat : '',
                { type: 'number', step: 'any', placeholder: '12.971599' });
            const lngI = mk('automation.zone_lng', data.lng != null ? data.lng : '',
                { type: 'number', step: 'any', placeholder: '77.594566' });
            const radI = mk('automation.zone_radius', 100, { type: 'number', min: '15', max: '5000' });

            const err = document.createElement('div');
            err.classList.add('zone-error');
            const save = document.createElement('button');
            save.type = 'button';
            save.classList.add('btn');
            save.textContent = BYD.i18n.t('automation.zone_save');
            form.append(err, save);

            save.addEventListener('click', () => {
                const name = nameI.value.trim();
                const lat = parseFloat(latI.value);
                const lng = parseFloat(lngI.value);
                const radiusM = parseInt(radI.value, 10);
                // Validate client-side for a useful message; the daemon re-checks everything.
                if (!name) { err.textContent = BYD.i18n.t('automation.zone_err_name'); return; }
                if (!isFinite(lat) || !isFinite(lng) || (lat === 0 && lng === 0)
                        || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    err.textContent = BYD.i18n.t('automation.zone_err_coords'); return;
                }
                if (!isFinite(radiusM) || radiusM < 15 || radiusM > 5000) {
                    err.textContent = BYD.i18n.t('automation.zone_err_radius'); return;
                }
                err.textContent = '';
                save.disabled = true;
                fetch('/api/surveillance/safe-locations', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: name, lat: lat, lng: lng, radiusM: radiusM })
                }).then(r => r.json()).then((d) => {
                    save.disabled = false;
                    if (!d || !d.success || !d.zone) {
                        err.textContent = (d && d.error) || BYD.i18n.t('automation.zone_err_save');
                        return;
                    }
                    this._zoneCache = null;          // the list changed — refetch next time
                    data.zones = data.zones.concat([d.zone]);
                    form.innerHTML = '';
                    rebuild(data);
                    sel.value = d.zone.name;
                    onChange(d.zone.name);           // select the zone just created
                }).catch(() => {
                    save.disabled = false;
                    err.textContent = BYD.i18n.t('automation.zone_err_save');
                });
            });
        };

        return wrap;
    },

    // True when this action's schema declares a signal-address variable, i.e. it is one of the
    // flow actions whose LHS is a catalog signal. Used to scope the awaited-state dropdown to
    // those rows only, so an unrelated action that happens to have a "state" variable keeps
    // rendering exactly as before.
    hasSignalAddress(actionSchema) {
        const vars = (actionSchema && Array.isArray(actionSchema.variables)) ? actionSchema.variables : [];
        return vars.some(v => v && v.signalAddress);
    },

    // For a "wait until signal" row: the schema to render its awaited-STATE field with,
    // derived from whichever signal the sibling `event` address currently names.
    //
    // The engine compares the awaited state with `eq` against whatever the signal publishes,
    // and each signal has its own vocabulary (on/off, open/closed, occupied/empty, P/R/N/D…).
    // The catalog already ships every condition's own `value` schema, so when the chosen
    // signal is enum-valued we hand that back and the user gets a DROPDOWN of exactly the
    // valid words instead of a free-text box they have to guess at.
    //
    // Returns the original free-text schema when the signal is unknown, not enum-valued
    // (a number like windowOpenPercent), or not chosen yet — so the field always renders and
    // a stored value is never blocked. Purely presentational: the server still accepts any
    // bounded string, so an automation saved before this (or against a signal this trim
    // doesn't expose) keeps loading exactly as it did.
    awaitedStateSchema(stateSchema, addressValue) {
        const options = this.signalEnumOptions(addressValue);
        if (!options) return stateSchema;
        // Keep the field's own id/label (the row stores under "state"), take the options.
        return {
            id: stateSchema.id, label: stateSchema.label, type: 'enum', options: options
        };
    },

    // ── Live signal state (editor hints) ───────────────────────────────────────
    // "What does this signal read RIGHT NOW?" — the single biggest source of confusion when
    // building a rule was having to guess a signal's vocabulary (is gear "p" or "park"? is a
    // toggle "on" or "true"?) and whether it is even being published on this car. The daemon
    // exposes its live state map read-only at /api/automations/state, keyed by the same
    // ${signal:…} address the editor emits, so a hint can be shown next to every picker.
    //
    // Polled only while an editor is actually on screen (startStatePolling/stopStatePolling), so
    // a parked head unit with no form open does no work.
    _signalState: {},            // address -> current value
    _statePollTimer: null,
    _stateFetchInFlight: false,

    STATE_POLL_MS: 3000,

    async fetchSignalState() {
        // Guarded against overlap: a slow daemon must not queue up requests behind a 3s timer.
        if (this._stateFetchInFlight) return;
        this._stateFetchInFlight = true;
        try {
            const resp = await fetch('/api/automations/state', { cache: 'no-store' });
            const data = await resp.json();
            if (data && data.success && data.state && typeof data.state === 'object') {
                this._signalState = data.state;
                this.refreshSignalHints();
            }
        } catch (e) {
            // Daemon down / transport hiccup — keep the last known values rather than blanking
            // every hint, which would read as "no signals available".
        } finally {
            this._stateFetchInFlight = false;
        }
    },

    // True when at least one hint is actually on screen (not on a hidden tab) and the page is
    // foregrounded — the single condition under which polling is worth anything.
    signalHintsVisible() {
        if (document.hidden) return false;
        // ANY visible hint, not just the first: hints live in BOTH the automation form
        // (#formCard, data-tab="add") and the action-group editor (#groupEditor,
        // data-tab="groups"), whose nested action rows use the same signal picker. Testing
        // only querySelector('.signal-hint') would find the automation form's hint — hidden
        // while the user is on the Groups tab — and wrongly stop polling, freezing the group
        // editor's values. The list is tiny (a handful of rows), so this is cheap.
        const hints = document.querySelectorAll('.signal-hint');
        for (let i = 0; i < hints.length; i++) {
            if (hints[i].offsetParent !== null) return true;
        }
        return false;
    },

    // Begin polling live values. Idempotent, so every editor open / tab return can call it.
    //
    // Deliberately NOT gated on visibility here. The gate lives ONLY in the interval below, which
    // is what stops a parked head unit polling forever. Gating the START too was wrong and is why
    // no request was ever made: hints are built by renderForm()/renderGroupForm() while their tab
    // is still hidden, so the very first call always saw offsetParent === null and bailed — and on
    // a wide/desktop layout there is no `.bottom-tabs` element, so the tap listener that was meant
    // to re-arm it never fires either. Starting unconditionally costs one request; the interval
    // then decides whether to keep going.
    startStatePolling() {
        this.fetchSignalState();
        if (this._statePollTimer) return;
        this._statePollTimer = setInterval(() => {
            // Self-stopping, gated on VISIBILITY not mere existence. Bottom-tab switching
            // (app-tabs.js) only toggles the `hidden` ATTRIBUTE — the populated form stays in
            // the document — so a presence test (`querySelector('.signal-hint')`) kept matching
            // and this poll ran every 3s forever after the user left the editor via the tab bar
            // instead of Cancel/Save. `[data-tab][hidden]` is `display:none !important`, so
            // offsetParent is null for a hidden hint; that is the cheap Chrome-58-safe test.
            // Also stop while the page itself is backgrounded.
            //
            // Requires several CONSECUTIVE hidden ticks, not one: a hint can read hidden
            // transiently (mid-render, a layout the tab system doesn't manage, an ancestor being
            // rebuilt), and retiring on the first such tick is what made the poll die before it
            // ever fetched. A genuinely closed editor stays hidden, so it still retires promptly.
            if (this.signalHintsVisible()) {
                this._stateHiddenTicks = 0;
            } else {
                this._stateHiddenTicks = (this._stateHiddenTicks || 0) + 1;
                if (this._stateHiddenTicks >= 3) { this.stopStatePolling(); return; }
            }
            this.fetchSignalState();
        }, this.STATE_POLL_MS);
    },

    stopStatePolling() {
        if (this._statePollTimer) { clearInterval(this._statePollTimer); this._statePollTimer = null; }
        this._stateHiddenTicks = 0;
    },

    // The live value for a ${signal:…} token (or a bare address), or undefined when the signal
    // has never been observed / has expired. Accepts the same shapes signalEnumOptions does.
    // Pre-catalog flat ids, mapped to the ATTRIBUTED address the daemon actually keys on.
    // MUST mirror AutomationCondition.LEGACY_SIGNAL_IDS on the server. Note these map to a
    // full address, NOT to a bare type: the daemon publishes speed under "speed:units=kmph",
    // never "speed", so mapping speedKmph->"speed" (as the type-only LEGACY map used for
    // vocabulary lookups does) finds nothing and the hint wrongly reads "not reported yet"
    // while the car is moving.
    LEGACY_SIGNAL_ADDRESSES: {
        speedKmph: 'speed:units=kmph',
        speedMph:  'speed:units=mph',
        turnLeft:  'turnSignal:side=left',
        turnRight: 'turnSignal:side=right',
        lowBeam:   'lights:area=lowBeam',
        highBeam:  'lights:area=highBeam',
        hazard:    'lights:area=hazard',
        drl:       'lights:area=drl'
    },

    liveSignalValue(addressValue) {
        let sig = (typeof addressValue === 'string') ? addressValue.trim() : '';
        if (!sig) return undefined;
        if (sig.indexOf('${signal:') === 0 && sig.charAt(sig.length - 1) === '}') {
            sig = sig.substring(9, sig.length - 1).trim();
        }
        if (!sig) return undefined;
        // A saved automation may still address a signal by its pre-catalog flat id; translate
        // to the daemon's key before looking anything up.
        if (this.LEGACY_SIGNAL_ADDRESSES[sig]) sig = this.LEGACY_SIGNAL_ADDRESSES[sig];
        const st = this._signalState || {};
        if (Object.prototype.hasOwnProperty.call(st, sig)) return st[sig];
        // Attribute order can differ between what the user built and what the daemon keyed on
        // (the daemon sorts them). Retry with a sorted attribute list before giving up.
        const colon = sig.indexOf(':');
        if (colon < 0) return undefined;
        const type = sig.substring(0, colon);
        const attrs = sig.substring(colon + 1).split(',').map(s => s.trim()).filter(Boolean).sort();
        const norm = type + ':' + attrs.join(',');
        return Object.prototype.hasOwnProperty.call(st, norm) ? st[norm] : undefined;
    },

    // The human-readable vocabulary line for a signal: its allowed words (enum), or its numeric
    // range, plus the unit/MQTT cross-reference when the catalog carries one. Returns '' when
    // nothing useful is known, so callers can skip the row entirely.
    signalVocabularyText(addressValue) {
        const opts = this.signalEnumOptions(addressValue);
        if (opts && opts.length) {
            return BYD.i18n.t('automation.signal_allowed')
                + ' ' + opts.map(o => this.optionLabel(o)).join(' · ');
        }
        const spec = this.signalValueSpec(addressValue);
        if (spec && spec.type === 'int' && spec.min != null && spec.max != null) {
            return BYD.i18n.t('automation.signal_range')
                .replace('{0}', String(spec.min)).replace('{1}', String(spec.max));
        }
        if (spec && spec.type === 'string') return BYD.i18n.t('automation.signal_text_valued');
        return '';
    },

    // The catalog `value` schema for the signal an address names (enum options, int min/max, …).
    signalValueSpec(addressValue) {
        let sig = (typeof addressValue === 'string') ? addressValue.trim() : '';
        if (!sig) return null;
        if (sig.indexOf('${signal:') === 0 && sig.charAt(sig.length - 1) === '}') {
            sig = sig.substring(9, sig.length - 1).trim();
        }
        const colon = sig.indexOf(':');
        if (colon >= 0) sig = sig.substring(0, colon).trim();
        const LEGACY = {
            speedKmph: 'speed', speedMph: 'speed', turnLeft: 'turnSignal', turnRight: 'turnSignal',
            lowBeam: 'lights', highBeam: 'lights', hazard: 'lights', drl: 'lights'
        };
        if (LEGACY[sig]) sig = LEGACY[sig];
        const opt = this.conditionCatalog().find(o => o && o.id === sig);
        return (opt && opt.value) ? opt.value : null;
    },

    // Build (or refresh) the hint block under a signal picker: "now: p" + the vocabulary line.
    // `host` is the element to fill; `getAddress` returns the current token, so the same block
    // can be refreshed in place by the poll without re-rendering the form.
    buildSignalHint(host, getAddress) {
        if (!host) return;
        host.classList.add('signal-hint');
        host._odGetAddress = getAddress;      // claimed by refreshSignalHints() below
        this.paintSignalHint(host);
        // A hint existing IS the signal that an editor is open, so this is the one place that
        // needs to start the poll. It retires itself once no hint is VISIBLE on the page.
        this.startStatePolling();
        // Resume after the poll retired itself because the page was backgrounded or the user
        // was on another tab. Bound once; re-entering startStatePolling is idempotent, and it
        // no-ops immediately if nothing is visible.
        if (!this._stateVisBound) {
            this._stateVisBound = true;
            // A live language switch swaps the catalog and calls hydrate(), but these hints are
            // built IMPERATIVELY, so hydrate() cannot repair their text — and the repaint dedup
            // below would otherwise suppress the rebuild because the address+value never
            // changed, leaving the old language's "NOW"/"Allowed:" labels on screen. Drop every
            // dedup key and force a repaint instead.
            if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
                BYD.i18n.onChange(() => {
                    const hints = document.querySelectorAll('.signal-hint');
                    for (let i = 0; i < hints.length; i++) hints[i]._odLastKey = null;
                    this.refreshSignalHints();
                });
            }
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden) this.startStatePolling();
            });
            // Tab switches don't fire visibilitychange, so also resume on any tap in the
            // bottom tab bar. Cheap: startStatePolling exits at once when no hint is visible.
            document.addEventListener('click', (ev) => {
                if (ev.target && ev.target.closest && ev.target.closest('.bottom-tabs')) {
                    setTimeout(() => this.startStatePolling(), 0);
                }
            });
        }
    },

    paintSignalHint(host) {
        const getAddress = host && host._odGetAddress;
        if (typeof getAddress !== 'function') return;
        const address = getAddress();
        const live = this.liveSignalValue(address);
        // Skip the rebuild when nothing observable changed. refreshSignalHints() runs on every
        // 3s tick, and in steady state the value is identical — so without this the hint
        // destroyed and recreated its whole subtree (plus 2-4 catalog scans and i18n lookups)
        // forever, for no visual difference. NUL-joined so an address/value pair can't alias.
        const key = String(address) + ' '
                + (live == null ? 'undef' : String(live));
        if (host._odLastKey === key) return;
        host._odLastKey = key;

        host.innerHTML = '';
        if (!address) { host.style.display = 'none'; return; }
        host.style.display = '';
        // The hint JUST became visible (an unchosen signal renders display:none, so it had no
        // offsetParent and the visibility gate refused to start the poll). Selecting a signal
        // only repaints — nothing else re-arms — so without this the first signal picked inside
        // an already-open editor showed a frozen "not reported yet" forever. Idempotent and
        // self-gating: startStatePolling early-returns when a timer already exists, and the
        // dedup guard above means this line only runs on a real change, not every 3s tick.
        this.startStatePolling();

        const nowRow = document.createElement('div');
        nowRow.classList.add('signal-hint-now');
        const label = document.createElement('span');
        label.classList.add('signal-hint-label');
        label.textContent = BYD.i18n.t('automation.signal_now');
        const val = document.createElement('span');
        val.classList.add('signal-hint-value');
        if (live === undefined || live === null) {
            // Never observed on this car (or expired). Say so plainly — an empty hint would
            // read as "no value", and a fabricated default would be worse.
            val.classList.add('unknown');
            val.textContent = BYD.i18n.t('automation.signal_unknown');
        } else if (live === '') {
            // A real, SET-but-empty value (a variable assigned ""), which the daemon reports
            // distinctly from absent. Calling it "not reported yet" was a lie: `eq ""` does
            // match it at run time. Shown in the muted style but named for what it is.
            val.classList.add('unknown');
            val.textContent = BYD.i18n.t('automation.signal_empty');
        } else {
            val.textContent = this.formatLiveSignalValue(address, live);
        }
        nowRow.append(label, val);
        host.append(nowRow);

        const vocab = this.signalVocabularyText(address);
        if (vocab) {
            const vRow = document.createElement('div');
            vRow.classList.add('signal-hint-vocab');
            vRow.textContent = vocab;
            host.append(vRow);
        }
        // The MQTT twin key, when the signal has one — the same read-only cross-reference the
        // description tooltip carries, surfaced here for users wiring Home Assistant too.
        const spec = this.conditionCatalog().find(o => o && o.id === this.signalTypeOf(address));
        if (spec && spec.mqtt) {
            const mRow = document.createElement('div');
            mRow.classList.add('signal-hint-mqtt');
            mRow.textContent = BYD.i18n.t('automation.signal_mqtt').replace('{0}', spec.mqtt);
            host.append(mRow);
        }
    },

    // Bare signal type from any address shape.
    signalTypeOf(addressValue) {
        let sig = (typeof addressValue === 'string') ? addressValue.trim() : '';
        if (sig.indexOf('${signal:') === 0 && sig.charAt(sig.length - 1) === '}') {
            sig = sig.substring(9, sig.length - 1).trim();
        }
        const colon = sig.indexOf(':');
        return colon >= 0 ? sig.substring(0, colon).trim() : sig;
    },

    // Show an enum value using its FRIENDLY label rather than the raw stored word, so the hint
    // matches what the dropdowns say ("Park", not "p").
    formatLiveSignalValue(address, live) {
        const opts = this.signalEnumOptions(address);
        if (opts && opts.length) {
            const hit = opts.find(o => o && String(o.id) === String(live));
            if (hit) return this.optionLabel(hit) + ' (' + String(live) + ')';
        }
        return String(live);
    },

    // Repaint every hint currently on screen (called after each poll).
    refreshSignalHints() {
        const hosts = document.querySelectorAll('.signal-hint');
        for (let i = 0; i < hosts.length; i++) {
            if (hosts[i]._odGetAddress) this.paintSignalHint(hosts[i]);
        }
    },

    // The vocabulary of the signal a flow-action LHS address names: its catalog `value`
    // options when that signal is ENUM-valued, else null (numeric signal, unknown id,
    // nothing chosen yet, or a "variable" LHS). One source of truth for every
    // "give this field the signal's own words" decision below.
    signalEnumOptions(addressValue) {
        let sig = (typeof addressValue === 'string') ? addressValue.trim() : '';
        if (!sig) return null;
        if (sig.indexOf('${signal:') === 0 && sig.charAt(sig.length - 1) === '}') {
            sig = sig.substring(9, sig.length - 1).trim();
        }
        const colon = sig.indexOf(':');
        if (colon >= 0) sig = sig.substring(0, colon).trim();
        // Legacy flow-action ids named the attributed light/turn signals directly; map them to
        // the catalog id so an untouched saved automation also gets the dropdown.
        const LEGACY = {
            speedKmph: 'speed', speedMph: 'speed', turnLeft: 'turnSignal', turnRight: 'turnSignal',
            lowBeam: 'lights', highBeam: 'lights', hazard: 'lights', drl: 'lights'
        };
        if (LEGACY[sig]) sig = LEGACY[sig];
        const opt = this.conditionCatalog().find(o => o && o.id === sig);
        const val = opt && opt.value;
        if (!val || val.type !== 'enum' || !Array.isArray(val.options) || !val.options.length) {
            return null;
        }
        return val.options;
    },

    // True when the signal a flow-action LHS names is compared as a STRING but has no fixed
    // vocabulary to offer as a dropdown (its catalog value type is "string" — e.g. a location
    // zone name, an MQTT payload, a variable). Those need a free-TEXT constant box: the flow
    // actions declare their RHS as a DynamicIntType, so the editor rendered a number-only
    // input and there was no way to type a word — the reported "only accepts numbers". An
    // enum-valued signal is handled by the dropdown path instead, and a numeric signal keeps
    // its numeric picker, so this only widens the cases that were previously impossible.
    signalIsStringValued(addressValue) {
        let sig = (typeof addressValue === 'string') ? addressValue.trim() : '';
        if (!sig) return false;
        if (sig.indexOf('${signal:') === 0 && sig.charAt(sig.length - 1) === '}') {
            sig = sig.substring(9, sig.length - 1).trim();
        }
        const colon = sig.indexOf(':');
        if (colon >= 0) sig = sig.substring(0, colon).trim();
        if (sig === 'variable') return true;
        const opt = this.conditionCatalog().find(o => o && o.id === sig);
        return !!(opt && opt.value && opt.value.type === 'string');
    },

    // The same vocabulary lookup, for the COMPARE RHS of the inline flow actions
    // (If / Loop / Wait Until). Their `value` field is declared as a DynamicIntType, so it
    // rendered a bare NUMBER box even when the chosen LHS is enum-valued — "Daylight eq ?"
    // asked for a number, and no number can ever equal "day"/"night" (StringValue.compareValue
    // is a lexical eq/neq), so the rule silently never fired. When the addressed signal is
    // enum-valued we hand back an enum schema so the user picks from its real words.
    //
    // `dynamic` is preserved so the Value / Variable / Signal toggle still layers on top —
    // the dropdown replaces only the CONSTANT editor, leaving ${var:…}/${signal:…} intact.
    // Falls through to the original numeric schema for a numeric signal (speed, battery), an
    // unknown/unchosen signal, or a variable LHS, so every existing row renders as before.
    dynamicValueSchema(valueSchema, addressValue) {
        if (!valueSchema || valueSchema.type === 'string') return valueSchema;
        const options = this.signalEnumOptions(addressValue);
        if (!options) return valueSchema;
        return {
            id: valueSchema.id, label: valueSchema.label, type: 'enum',
            options: options, dynamic: valueSchema.dynamic,
            // strictOptions, for the same reason eqNeqComparator sets it: these options are
            // CONTEXTUALLY narrowed by the chosen LHS, not limited by the device. A stored value
            // outside them is either a leftover from switching the LHS (build "Daylight eq day",
            // then change the LHS to Gear → "day") or a pre-dropdown numeric constant
            // (sunPhase eq 0). Preserving it as "(unavailable on this device)" would be a lie —
            // the signal IS available — and would silently save a rule that can NEVER fire,
            // because the engine compares these with a lexical eq/neq (StringValue.compareValue).
            // Forcing .invalid makes the user re-pick a real word instead.
            strictOptions: true
        };
    },

    // If's legacy `name` field (AI/imported JSON) names the variable a bare "variable" LHS
    // reads; the engine resolves both forms to the same state key.
    _lhsAddress(address, name, action) {
        if (action && action.nameField && typeof address === 'string' && address.trim() === 'variable'
                && typeof name === 'string' && name.trim()) {
            return 'variable:name=' + name.trim();
        }
        return address;
    },

    // Move the legacy `name` into the address so the picker shows and edits it.
    _foldLhsVariableName(vars, key, action) {
        const folded = this._lhsAddress(vars[key], vars.name, action);
        if (folded !== vars[key]) {
            vars[key] = folded;
            delete vars.name;
        }
    },

    createSignalAddressInput(schema, currentValue, onChange, signalOptions, extraOptions) {
        const wrap = document.createElement('div');
        wrap.classList.add('cond-value-wrap');

        // Parse "TYPE" or "TYPE:k=v,k2=v2" (and tolerate a ${signal:…} token, which an
        // imported/hand-edited config may carry).
        const parse = (v) => {
            let s = (typeof v === 'string') ? v.trim() : '';
            if (!s) return { sig: '', attrs: {} };
            if (s.indexOf('${signal:') === 0 && s.charAt(s.length - 1) === '}') {
                s = s.substring(9, s.length - 1).trim();
            }
            const c = s.indexOf(':');
            if (c < 0) return { sig: s, attrs: {} };
            const sig = s.substring(0, c).trim();
            const rest = s.substring(c + 1);
            // A variable's name is free text and may contain a comma ("Shopping,List"), so take
            // it verbatim rather than splitting — matching the server's parser. Every other
            // signal's attributes are fixed tokens, so the comma split is safe for them.
            if (sig === 'variable' && rest.indexOf('name=') === 0) {
                return { sig: sig, attrs: { name: rest.substring(5).trim() } };
            }
            const attrs = {};
            for (const pair of rest.split(',')) {
                const eq = pair.indexOf('=');
                if (eq > 0) attrs[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim();
            }
            return { sig: sig, attrs: attrs };
        };
        const init = parse(currentValue);
        const attrState = Object.assign({}, init.attrs);

        // Free-text-keyed signals can't be addressed by type alone; an action that supports
        // them (the "variable" LHS) passes them in via extraOptions so they stay selectable.
        //
        // De-dupe by id, keeping the CATALOG entry over an extraOptions one of the same id.
        // "Variable" was offered TWICE in the flow-action LHS picker (If/Wait-Until/Loop) with
        // identical labels, and only one worked: the catalog entry declares the variable NAME
        // as an attribute (VariableCondition.toJson puts it in `variables`), so renderAttrs
        // gives it a name box and emit() produces the resolvable "variable:name=Foo". The
        // extraOptions copy has no attributes, so it emits a bare "variable" that names no
        // state — and the separate `nameField` box that was meant to back it never renders
        // (isLhsEventVariable requires an enum `options` list, which SignalAddressType does
        // not emit). Dropping the extra copy removes the dead duplicate, not the working one.
        const NON_ADDRESSABLE = { mqttTrigger: 1 };
        const pickable = (signalOptions || []).filter(o => o && o.id && !NON_ADDRESSABLE[o.id]);
        for (const eo of (extraOptions || [])) {
            if (eo && eo.id && !pickable.some(o => o.id === eo.id)) pickable.push(eo);
        }

        const sel = document.createElement('select');
        sel.classList.add('input', 'enum', 'cond-ref-signal');
        const ph = document.createElement('option');
        ph.value = ''; ph.textContent = BYD.i18n.t('automation.cond_signal_placeholder');
        ph.disabled = true; ph.selected = true; ph.hidden = true;
        sel.append(ph);
        this.appendSortedCatalogOptions(sel, pickable);
        // A stored id that isn't in the catalog (a legacy flat id like "speedKmph", or a
        // signal this trim doesn't expose) is preserved as its own option, so merely opening
        // the form can't silently blank a working automation.
        if (init.sig && !pickable.some(o => o.id === init.sig)) {
            const keep = document.createElement('option');
            keep.value = init.sig;
            keep.textContent = init.sig;
            sel.append(keep);
        }
        if (init.sig) sel.value = init.sig;
        wrap.append(sel);

        const attrBox = document.createElement('div');
        attrBox.classList.add('cond-signal-attrs');
        wrap.append(attrBox);

        // `quiet` seeds formData WITHOUT letting the caller re-render. The build-time seed must
        // be quiet: renderAttrs() above defaults a missing attribute, so seeding a bare stored
        // address ("speed") emits the completed one ("speed:units=kmph") — a real change that
        // would otherwise re-enter renderForm()/rr() in the middle of building this very row.
        // It converges after one pass, but re-rendering mid-build risks losing focus and is
        // needless churn; the value still lands in formData either way.
        let quiet = false;
        // Current address as this widget would emit it — shared by emit() and the live hint so
        // the two can never disagree about which instance is addressed.
        const currentAddress = () => {
            const sig = sel.value;
            if (!sig) return '';
            const opt = pickable.find(o => o.id === sig);
            const vars = (opt && Array.isArray(opt.variables)) ? opt.variables : [];
            const parts = [];
            for (const v of vars) {
                const val = attrState[v.id];
                if (val != null && val !== '') parts.push(v.id + '=' + val);
            }
            return sig + (parts.length ? ':' + parts.join(',') : '');
        };
        // Declared before renderAttrs() so the synchronous change listener its attribute inputs
        // fire at build can repaint it without hitting a temporal dead zone.
        const hint = document.createElement('div');
        const emit = () => {
            const sig = sel.value;
            if (!sig) { onChange('', quiet); return; }
            onChange(currentAddress(), quiet);
        };
        // A variable is addressed only by its name; a blank one resolves to no state.
        let nameInp = null;
        let nameMax = Infinity;
        const renderAttrs = () => {
            attrBox.innerHTML = '';
            nameInp = null;
            const opt = pickable.find(o => o.id === sel.value);
            const vars = (opt && Array.isArray(opt.variables)) ? opt.variables : [];
            for (const v of vars) {
                if (attrState[v.id] == null && Array.isArray(v.options) && v.options.length) {
                    attrState[v.id] = v.options[0].id;
                }
                const inp = this.createInput(v, attrState[v.id], (el, value) => {
                    attrState[v.id] = value;
                    emit();
                    // The attribute selects the INSTANCE, so the live value changes with it.
                    this.paintSignalHint(hint);
                    syncValid();
                });
                inp.classList.add('cond-signal-attr');
                if (sel.value === 'variable' && v.id === 'name') {
                    nameInp = inp;
                    nameMax = (v.maxLength != null) ? v.maxLength : Infinity;
                    // Live, so Save enables while typing instead of only after blur.
                    inp.addEventListener('input', () => syncValid());
                }
                attrBox.append(inp);
            }
        };
        // Mark an unchosen signal invalid, which disables Save. Every declared action
        // variable is REQUIRED on load (BaseAction.fromJson), so letting a fresh row save with
        // no signal would have the server reject the whole automation. The old enum picker got
        // this for free (createEnumInput flags a non-matching selection); this one must do it
        // explicitly.
        const syncValid = () => {
            sel.classList.toggle('invalid', !sel.value);
            if (nameInp) {
                const typed = String(nameInp.value || '');
                nameInp.classList.toggle('invalid', !typed.trim() || typed.length > nameMax);
            }
            if (this._syncSaveDisabled) this._syncSaveDisabled();
            if (this._syncGroupSaveDisabled) this._syncGroupSaveDisabled();
        };
        sel.addEventListener('change', () => {
            // A different signal has different attributes — drop the old ones so a stale
            // key (e.g. side=left) can't ride along onto a signal that doesn't declare it.
            for (const k in attrState) if (attrState.hasOwnProperty(k)) delete attrState[k];
            renderAttrs();
            emit();
            syncValid();
            this.paintSignalHint(hint);
        });
        renderAttrs();
        // Seed formData from the stored value at BUILD time (the enum picker did this via its
        // build-time changeEvent). Without it, re-saving an untouched automation would drop
        // this variable — and any attribute defaulted by renderAttrs above would be lost.
        if (init.sig) { quiet = true; emit(); quiet = false; }
        syncValid();
        // Live value + vocabulary for the chosen signal, under the picker. Same block the
        // condition-value editor uses, refreshed in place by the poll.
        wrap.append(hint);
        this.buildSignalHint(hint, currentAddress);
        return wrap;
    },

    createConditionValueInput(valueSchema, currentValue, onChange, signalOptions, selfType) {
        // The locationZone value is a Safe Location NAME, so give it the zone picker (dropdown
        // of real zones + "none" + inline create) instead of a bare text box. Returned before
        // the Value/Variable/Signal toggle is built: comparing a zone name against a live
        // signal or variable is not meaningful, and the toggle would only add confusion here.
        if (this.isLocationZoneField(selfType, valueSchema)) {
            return this.createLocationZoneInput(valueSchema, currentValue, onChange);
        }
        const wrap = document.createElement('div');
        wrap.classList.add('cond-value-wrap');

        // Parse an existing value to decide the initial mode.
        const parseRef = (v) => {
            if (typeof v !== 'string') return null;
            const s = v.trim();
            if (s.indexOf('${var:') === 0 && s.charAt(s.length - 1) === '}') {
                return { mode: 'var', name: s.substring(6, s.length - 1) };
            }
            if (s.indexOf('${signal:') === 0 && s.charAt(s.length - 1) === '}') {
                // signal:TYPE  or  signal:TYPE:k1=v1,k2=v2 — split the type from any
                // attribute list so an ATTRIBUTED signal (turnSignal side=left, speed
                // units=kmph, seatbelt area=…) round-trips. Attributes are parsed into a
                // {key:val} map the signal-mode editor re-seeds its attribute selectors from.
                const inner = s.substring(9, s.length - 1);
                const c = inner.indexOf(':');
                if (c < 0) return { mode: 'signal', sig: inner, attrs: {} };
                const attrs = {};
                for (const pair of inner.substring(c + 1).split(',')) {
                    const eq = pair.indexOf('=');
                    if (eq > 0) attrs[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim();
                }
                return { mode: 'signal', sig: inner.substring(0, c).trim(), attrs };
            }
            return null;
        };
        const initRef = parseRef(currentValue);
        const initialValue = currentValue;
        let mode = initRef ? initRef.mode : 'value';

        // Only free-text-keyed signals genuinely can't be addressed by a signal token
        // (variable/mqttTrigger carry an arbitrary name, not a fixed type+attrs). Real
        // ATTRIBUTED vehicle signals (turnSignal, speed, seatbelt, lights, occupant, window…)
        // ARE pickable now: the server resolves ${signal:TYPE:k=v} (see AutomationCondition),
        // and the signal-mode editor below renders a selector for each attribute the chosen
        // signal declares (its schema `variables`), emitting that attributed token. Excluding
        // them wholesale was what hid every vehicle signal from the picker.
        const NON_PICKABLE = { variable: 1, mqttTrigger: 1 };
        const signalPickable = (signalOptions || []).filter(o =>
            o && o.id && o.id !== selfType && !NON_PICKABLE[o.id]);

        // Mode toggle (segmented). Signal mode only when we have pickable (resolvable) signals.
        const modes = [['value', 'automation.cond_mode_value'], ['var', 'automation.cond_mode_var']];
        if (signalPickable.length) modes.push(['signal', 'automation.cond_mode_signal']);
        const toggle = document.createElement('div');
        toggle.classList.add('cond-mode-toggle');
        const body = document.createElement('div');
        body.classList.add('cond-value-body');

        const render = () => {
            body.innerHTML = '';
            if (mode === 'value') {
                // The normal typed editor. Seed it only when the current value is a real
                // constant (not a leftover ${…} token from another mode); after a mode switch,
                // fall back to the saved constant.
                const shown = currentValue !== undefined ? currentValue : initialValue;
                const seed = parseRef(shown) ? undefined : shown;
                // Store the restored constant first; editors that emit on build then override it.
                if (currentValue === undefined && seed !== undefined) onChange(seed);
                const input = this.createInput(valueSchema, seed, (el, value) => onChange(value));
                body.append(input);
            } else if (mode === 'var') {
                const input = document.createElement('input');
                input.type = 'text';
                input.classList.add('input', 'text', 'cond-ref-input');
                input.placeholder = BYD.i18n.t('automation.cond_var_placeholder');
                input.maxLength = 40;   // SetVariableAction.MAX_NAME: longer names never resolve
                input.value = (initRef && initRef.mode === 'var') ? initRef.name : '';
                const emit = () => {
                    const n = input.value.trim();
                    onChange(n ? '${var:' + n + '}' : '');
                    // Only a string field can hold ''; elsewhere a reference needs a name.
                    input.classList.toggle('invalid', !n && valueSchema.type !== 'string');
                    this._syncSaveDisabled();
                    this._syncGroupSaveDisabled();
                };
                input.addEventListener('input', emit);
                emit();
                body.append(input);
            } else { // signal
                const sel = document.createElement('select');
                sel.classList.add('input', 'enum', 'cond-ref-signal');
                const ph = document.createElement('option');
                ph.value = ''; ph.textContent = BYD.i18n.t('automation.cond_signal_placeholder');
                ph.disabled = true; ph.selected = true; ph.hidden = true;
                sel.append(ph);
                this.appendSortedCatalogOptions(sel, signalPickable);
                // A saved signal token (e.g. a hand-edited/imported attributed one) that
                // isn't in the pickable list: keep it as a "(missing)" option so opening the
                // form does NOT silently blank the stored value. Only a real user change
                // (the change listener) rewrites it.
                if (initRef && initRef.mode === 'signal') {
                    const present = signalPickable.some(o => o.id === initRef.sig);
                    if (!present) {
                        const miss = document.createElement('option');
                        miss.value = initRef.sig;
                        miss.textContent = initRef.sig + ' ' + BYD.i18n.t('automation.cond_signal_missing');
                        sel.append(miss);
                    }
                    sel.value = initRef.sig;
                    // Re-entering Signal mode shows the saved token, so store it again too.
                    onChange(initialValue);
                }
                // Nothing picked yet: block Save rather than keep the previous mode's value.
                const syncPicked = () => sel.classList.toggle('invalid', !sel.value);
                syncPicked();
                body.append(sel);

                // Per-signal ATTRIBUTE selectors. A signal whose schema declares `variables`
                // (turnSignal→side, speed→units, seatbelt→area/seat, …) needs those chosen so
                // the emitted token addresses the right instance: ${signal:TYPE:k=v,…}. The
                // attribute state is held here and re-seeded from a parsed token on edit.
                const attrState = (initRef && initRef.mode === 'signal') ? Object.assign({}, initRef.attrs) : {};
                const attrBox = document.createElement('div');
                attrBox.classList.add('cond-signal-attrs');
                body.append(attrBox);

                // Declared HERE, before renderAttrs() can run: the attribute inputs
                // createInput() builds fire their change listener SYNCHRONOUSLY at build (see the
                // note after renderAttrs below), and that listener repaints this hint — so a
                // `const hint` declared further down would be in its temporal dead zone on the
                // very first render. Populated by buildSignalHint() once `sel` exists.
                const hint = document.createElement('div');

                // Build the token from the current type + attribute selections and emit it.
                // Attribute keys are emitted in the signal's declared variable order so the
                // token is stable (byte-identical round-trip on re-save).
                const emitSignal = () => {
                    const sig = sel.value;
                    if (!sig) { onChange(''); return; }
                    const opt = signalPickable.find(o => o.id === sig);
                    const vars = (opt && Array.isArray(opt.variables)) ? opt.variables : [];
                    const parts = [];
                    for (const v of vars) {
                        const val = attrState[v.id];
                        if (val != null && val !== '') parts.push(v.id + '=' + val);
                    }
                    onChange('${signal:' + sig + (parts.length ? ':' + parts.join(',') : '') + '}');
                };

                // (Re)render the attribute selectors for the currently-selected signal. Each
                // is the same enum input the condition's own variables use, so it inherits the
                // localized option labels and styling.
                const renderAttrs = () => {
                    attrBox.innerHTML = '';
                    const opt = signalPickable.find(o => o.id === sel.value);
                    const vars = (opt && Array.isArray(opt.variables)) ? opt.variables : [];
                    for (const v of vars) {
                        // Default an unset attribute to the variable's first option so the
                        // token is never emitted with a missing key (which the server rejects).
                        if (attrState[v.id] == null && Array.isArray(v.options) && v.options.length) {
                            attrState[v.id] = v.options[0].id;
                        }
                        const inp = this.createInput(v, attrState[v.id], (el, value) => {
                            attrState[v.id] = value;
                            emitSignal();
                            // The attribute picks the INSTANCE (speed units, seatbelt seat,
                            // window area), so the live value changes with it.
                            this.paintSignalHint(hint);
                        });
                        inp.classList.add('cond-signal-attr');
                        attrBox.append(inp);
                    }
                };

                // Live-value + vocabulary hint for the chosen signal (element declared above the
                // attribute renderer). Reads the token straight back out of the widget state, so
                // the poll can repaint it without re-rendering the form.
                body.append(hint);
                this.buildSignalHint(hint, () => {
                    const sig = sel.value;
                    if (!sig) return '';
                    const opt = signalPickable.find(o => o.id === sig);
                    const vars = (opt && Array.isArray(opt.variables)) ? opt.variables : [];
                    const parts = [];
                    for (const v of vars) {
                        const val = attrState[v.id];
                        if (val != null && val !== '') parts.push(v.id + '=' + val);
                    }
                    return sig + (parts.length ? ':' + parts.join(',') : '');
                });

                sel.addEventListener('change', () => {
                    // New signal type → drop stale attributes from the previous type.
                    for (const k of Object.keys(attrState)) delete attrState[k];
                    renderAttrs();
                    emitSignal();
                    this.paintSignalHint(hint);
                    syncPicked();
                    this._syncSaveDisabled();
                    this._syncGroupSaveDisabled();
                });
                renderAttrs();
                this.paintSignalHint(hint);
                // Note: the attribute enum inputs createInput() builds fire their change
                // listener synchronously at build, so an already-attributed signal may
                // re-emit its token here on initial render — but that is harmless because
                // attrState was seeded from the parsed token FIRST (line above the signal
                // <select> handler), so any re-emit is byte-identical to the stored value. A
                // signal that isn't in signalPickable (a "(missing)" stored token) has no
                // opt.variables, so renderAttrs adds no inputs and nothing re-emits — the
                // stored token is preserved untouched.
            }
            this._syncSaveDisabled();
            this._syncGroupSaveDisabled();
        };

        for (const [m, key] of modes) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.classList.add('cond-mode-btn');
            btn.textContent = BYD.i18n.t(key);
            btn.classList.toggle('active', m === mode);
            btn.addEventListener('click', () => {
                if (mode === m) return;
                mode = m;
                // Switching mode clears the stored value so a stale token/constant from the
                // previous mode can't leak (emit below re-populates for the new mode).
                currentValue = undefined;
                toggle.querySelectorAll('.cond-mode-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                render();
            });
            toggle.append(btn);
        }
        wrap.append(toggle, body);
        render();
        return wrap;
    },

    createRow({ id, label, options }, index) {
        const token = sanitizeToken(id);
        const row = this.createRowElement({ id });
        const inputs = document.createElement('div');
        inputs.classList.add(token + '-inputs', 'inputs');

        const typeSelectorContainer = document.createElement('div');
        typeSelectorContainer.classList.add(token + '-type-selector', 'type-selector');
        const stepIcon = this.createStepIcon();
        const stepTag = this.createStepTag();
        const stepFallback = autoSectionHue(id);
        this.paintStep(row, stepIcon, stepTag, null, stepFallback);

        const eventListener = (element, value, selected) => {
            inputs.querySelectorAll('.variable-input').forEach(input => input.remove());
            inputs.querySelectorAll('.field-help').forEach(help => help.remove());
            inputs.querySelectorAll('.nested-actions').forEach(n => n.remove());
            typeSelectorContainer.querySelectorAll('.description-icon').forEach(description => description.remove());
            inputs.classList.remove('has-cond-value');   // re-added below only if a cond-value editor renders
            if (this.formData[id][index].type !== value) this.formData[id][index] = { type: value };
            this.paintStep(row, stepIcon, stepTag, selected, stepFallback);
            if (!this.formData[id][index].variables) this.formData[id][index].variables = {};
            if (selected.description && value !== 'operatingMode') {
                const icon = document.createElement('div');
                icon.classList.add(token + '-description-tooltip', 'description-icon');
                icon.innerHTML = infoIcon;
                const showAt = () => {
                    const currentPosition = icon.getBoundingClientRect();
                    this.showDescriptionTooltip(selected, currentPosition.left, currentPosition.bottom + window.scrollY);
                };
                icon.addEventListener('mouseover', showAt);
                icon.addEventListener('mouseout', () => this.hideDescriptionTooltip());
                // TAP support. The hover pair above is unreachable on the head unit and on
                // a phone — both are touch-only — so the description (and the MQTT
                // telemetry key it carries) could never be read there. Tap toggles it, and
                // the next tap anywhere dismisses it. stopPropagation keeps this tap from
                // immediately hitting the document dismiss handler below.
                icon.addEventListener('click', (ev) => {
                    ev.stopPropagation();
                    ev.preventDefault();
                    const tip = document.querySelector('.description-tooltip');
                    const open = tip && tip.classList.contains('visible') && this._tipOwner === icon;
                    if (open) { this.hideDescriptionTooltip(); this._tipOwner = null; return; }
                    showAt();
                    this._tipOwner = icon;
                    if (!this._tipDismissBound) {
                        // One document-level dismiss for every icon on the page.
                        document.addEventListener('click', () => {
                            this.hideDescriptionTooltip();
                            this._tipOwner = null;
                        });
                        this._tipDismissBound = true;
                    }
                });
                typeSelectorContainer.append(icon);
            }
            const selectedVars = (selected.variables && selected.variables.length) ? selected.variables : [];
            for (const variable of selectedVars) {
                let variableSelector;
                if (variable.dynamic) {
                    // A dynamic field (the RHS of an inline If/Loop/Wait-Until compare):
                    // render the same Value / Variable / Signal editor a condition uses, so
                    // the comparison can target a constant, a ${var:NAME}, or a ${signal:TYPE}.
                    // The signal list is the conditions catalog; selfType is left null (a flow
                    // action's LHS is picked in a sibling "event" field, not this row's type).
                    // When the LHS compares as a STRING (a user variable, a zone name, an SSID)
                    // the constant path is free text — a numeric-only picker couldn't hold
                    // "Sport_Mode", which is the reported "only accepts numbers". Otherwise,
                    // when the addressed LHS signal is ENUM-valued (Daylight, gear, wifiState…),
                    // swap the numeric constant editor for a dropdown of that signal's own
                    // words — a number could never eq "day" (see dynamicValueSchema).
                    const rhsSchema =
                        this.signalIsStringValued(this.formData[id][index].variables.event)
                        ? this.asStringSchema(variable)
                        : this.dynamicValueSchema(variable, this.formData[id][index].variables.event);
                    variableSelector = this.createConditionValueInput(
                        rhsSchema, this.formData[id][index].variables[variable.id],
                        (value) => { this.formData[id][index].variables[variable.id] = value; },
                        this.conditionCatalog(), null);
                    inputs.classList.add('has-cond-value');   // top-align (2-line editor)
                } else if (variable.id === 'state' && selected && this.hasSignalAddress(selected)) {
                    // "Wait until signal" awaited state: a DROPDOWN of the chosen signal's own
                    // vocabulary when it has one, else the plain free-text field.
                    const stateSchema = this.awaitedStateSchema(
                        variable, this.formData[id][index].variables.event);
                    variableSelector = this.createInput(
                        stateSchema, this.formData[id][index].variables[variable.id],
                        (element, value) => { this.formData[id][index].variables[variable.id] = value; });
                } else if (variable.signalAddress) {
                    // The LHS signal picker of a flow action: the whole conditions catalog
                    // plus per-attribute selectors. A "variable" operand stays available for
                    // actions that declare the bespoke name field (If), so the free-text
                    // variable LHS keeps working alongside the catalog.
                    const extra = selected && selected.nameField
                        ? [{ id: 'variable', label: BYD.i18n.t('automation.cond_mode_var') }] : [];
                    this._foldLhsVariableName(this.formData[id][index].variables, variable.id, selected);
                    variableSelector = this.createSignalAddressInput(
                        variable, this.formData[id][index].variables[variable.id],
                        (value, quiet) => {
                            const prev = this.formData[id][index].variables[variable.id];
                            this.formData[id][index].variables[variable.id] = value;
                            if (quiet) return;
                            // Selecting/leaving "variable" changes which sibling fields apply
                            // (name field, eq/neq comparator, string RHS) — re-render, guarded
                            // so an unchanged emit can't loop (see isLhsEventVariable).
                            // Re-render when the signal changes: sibling fields depend on it —
                            // the awaited-state dropdown's vocabulary, and (for "variable") the
                            // name field / eq-neq comparator / string RHS. Guarded on a real
                            // change so a build-time emit can't recurse.
                            if (prev !== value) this.renderForm();
                        },
                        this.conditionCatalog(), extra);
                    inputs.classList.add('has-cond-value');
                } else if (this.isLhsEventVariable(variable, selected)) {
                    // The LHS "event" picker of a flow action that supports a Variable operand
                    // (If/Wait-Until/Loop). Wrap it so choosing "variable" re-renders the row
                    // (to show the name field + swap the comparator/value to string mode).
                    variableSelector = this.createInput(variable, this.formData[id][index].variables[variable.id], (element, value) => {
                        // Re-render ONLY on a genuine value change. createEnumInput fires this
                        // listener SYNCHRONOUSLY at build time too (with value === the stored
                        // event); re-rendering then rebuilds THIS very picker, which fires on
                        // build again → unbounded recursion that overflows the stack and hard-
                        // freezes the editor ("nothing editable"). On a real change the name
                        // field + eq/neq comparator swap still appear on the rebuild.
                        const prev = this.formData[id][index].variables[variable.id];
                        this.formData[id][index].variables[variable.id] = value;
                        if (prev !== value) this.renderForm();
                    });
                } else if (variable.id === 'comparator'
                        && (this.signalEnumOptions(this.formData[id][index].variables.event)
                            || this.signalIsStringValued(this.formData[id][index].variables.event))) {
                    // String LHS → constrain the comparator to eq/neq (gt/lt are meaningless
                    // against a string and would silently evaluate false). An ENUM-valued
                    // signal LHS (Daylight, gear, wifiState…) is string-backed for exactly the
                    // same reason — StringValue only implements eq/neq, so gt/lt there returns
                    // null and the rule never fires, so a stale gt/lt must be re-picked rather
                    // than silently preserved (see eqNeqComparator).
                    variableSelector = this.createInput(this.eqNeqComparator(variable), this.formData[id][index].variables[variable.id], (element, value) => {
                        this.formData[id][index].variables[variable.id] = value;
                    });
                } else {
                    variableSelector = this.createInput(variable, this.formData[id][index].variables[variable.id], (element, value) => {
                        this.formData[id][index].variables[variable.id] = value;
                    });
                }
                variableSelector.classList.add(token + '-input', 'variable-input');
                inputs.append(variableSelector);
                // Right after the LHS event picker, when it's set to "variable", render the
                // bespoke free-text variable-name field (schema carries it as `nameField`).
                if (this.isLhsEventVariable(variable, selected)
                        && this.formData[id][index].variables[variable.id] === 'variable'
                        && selected.nameField) {
                    const nameInp = this.createInput(selected.nameField, this.formData[id][index].variables.name, (element, value) => {
                        this.formData[id][index].variables.name = value;
                    });
                    nameInp.classList.add(token + '-input', 'variable-input', 'lhs-var-name');
                    inputs.append(nameInp);
                }
            }
            if (selected.comparator && selected.value) {
                // Comparator: normal enum input. Value: the dynamic-capable editor so a
                // condition can compare against a constant, a ${var:…}, or another
                // ${signal:…}. `options` here is the conditions catalog (signal list).
                const compSel = this.createInput(selected.comparator, this.formData[id][index].comparator, (element, value) => {
                    this.formData[id][index].comparator = value;
                });
                compSel.classList.add(token + '-input', 'variable-input', 'comparator');
                inputs.append(compSel);
                const valSel = this.createConditionValueInput(selected.value, this.formData[id][index].value,
                    (value) => { this.formData[id][index].value = value; }, options, value);
                valSel.classList.add(token + '-input', 'variable-input', 'value');
                inputs.classList.add('has-cond-value');   // top-align (see createRow eventListener note)
                inputs.append(valSel);
            }
            // Persistent help for controls whose behavior needs more context than a tooltip.
            const helpKeys = (value === 'setVariable' || value === 'variable')
                ? [
                    'automation.variables_help_intro',
                    'automation.variables_help_unset',
                    'automation.variables_help_mutex'
                ]
                : value === 'operatingMode'
                    ? [
                        'automation.operating_mode_help_intro',
                        'automation.operating_mode_help_on_only',
                        'automation.operating_mode_help_on_and_off'
                    ]
                    : null;
            if (helpKeys) {
                const help = document.createElement('div');
                help.classList.add('field-help', 'variable-input');
                const body = document.createElement('div');
                for (const key of helpKeys) {
                    const line = document.createElement('p');
                    line.textContent = BYD.i18n.t(key);
                    body.append(line);
                }
                help.innerHTML = infoIcon;
                help.append(body);
                inputs.append(help);
            }
            // Control-flow actions (loop / if) carry nested action lists. Render a
            // nested action editor for the "then"/body list, and (if the schema says so)
            // an "else" list. Backed by childActions / elseActions on this entry.
            // options here is the SAME action catalog, so nested actions can be anything.
            if (selected.hasChildActions) {
                const entry = this.formData[id][index];
                if (!Array.isArray(entry.childActions)) entry.childActions = [];
                inputs.append(this.createNestedActions(options, entry.childActions,
                    (value === 'if') ? 'automation.then_actions' : 'automation.loop_body'));
                if (selected.hasElseActions) {
                    if (!Array.isArray(entry.elseActions)) entry.elseActions = [];
                    inputs.append(this.createNestedActions(options, entry.elseActions,
                        'automation.else_actions'));
                }
            }
        };

        const typeSelector = this.createEnumInput(this.getTypeEnum(id, options), this.formData[id][index].type, eventListener);
        typeSelectorContainer.prepend(typeSelector);

        inputs.prepend(typeSelectorContainer);
        inputs.prepend(stepTag);

        row.append(inputs);
        row.prepend(stepIcon);

        return row;
    },

    createRowElement({ id }) {
        const row = document.createElement('div');
        row.classList.add(sanitizeToken(id) + '-row', 'row');
        return row;
    },

    // Block-builder look (presentational only). A section block's header: tinted icon chip
    // + the section's own label / description (same elements and text as before).
    createSectionHead(section) {
        const head = document.createElement('div');
        head.classList.add('section-head');
        const chip = document.createElement('span');
        chip.classList.add('section-icon');
        chip.setAttribute('aria-hidden', 'true');
        chip.innerHTML = autoIconSvg(autoSectionHue(section.id));
        const text = document.createElement('div');
        text.classList.add('section-head-text');
        text.append(this.createRowLabel(section));
        if (section.description) {
            text.append(this.createRowDescription(section));
        }
        head.append(chip, text);
        return head;
    },

    createStepIcon() {
        const chip = document.createElement('span');
        chip.classList.add('step-icon');
        chip.setAttribute('aria-hidden', 'true');
        return chip;
    },

    createStepTag() {
        const tag = document.createElement('span');
        tag.classList.add('step-tag');
        return tag;
    },

    // Hue + icon + category tag of a step row from its selected option (null = nothing
    // picked yet → the caller's fallback hue). Re-run from the type select's handler.
    paintStep(row, chip, tag, option, fallback) {
        const hue = option ? autoStepHue(option) : (fallback || 'other');
        row.setAttribute('data-step-cat', hue);
        chip.innerHTML = autoIconSvg(option ? autoStepIconKey(option, hue) : hue);
        tag.textContent = (option && option.category) ? this.categoryLabel(option.category) : '';
    },

    // Segmented AND/OR front-end for an existing logic <select>. The select stays in the
    // DOM as the source of truth (value + its own change handler); a segment click sets
    // its value and fires the same 'change' a user pick would.
    createLogicSegments(selector) {
        const seg = document.createElement('div');
        seg.classList.add('btn-group', 'logic-seg');
        seg.setAttribute('role', 'group');
        const buttons = [];
        const sync = () => {
            for (const b of buttons) {
                const on = b.getAttribute('data-value') === selector.value;
                b.classList.toggle('active', on);
                b.setAttribute('aria-pressed', on ? 'true' : 'false');
            }
        };
        for (const opt of Array.prototype.slice.call(selector.options)) {
            if (opt.disabled || opt.hidden) continue;
            const b = document.createElement('button');
            b.type = 'button';
            b.classList.add('btn-toggle', 'logic-seg-btn');
            b.setAttribute('data-value', opt.value);
            b.textContent = opt.textContent;
            b.addEventListener('click', () => {
                if (selector.value === opt.value) return;
                selector.value = opt.value;
                selector.dispatchEvent(new Event('change', { bubbles: true }));
            });
            buttons.push(b);
            seg.append(b);
        }
        if (buttons.length < 2) {
            seg.style.display = 'none';   // nothing to segment — the native select stays the control
            return seg;
        }
        selector.addEventListener('change', sync);
        sync();
        selector.classList.add('logic-select-native');
        selector.setAttribute('tabindex', '-1');
        selector.setAttribute('aria-hidden', 'true');
        return seg;
    },

    createRowLabel({ id, label }) {
        const rowLabel = document.createElement('span');
        rowLabel.classList.add(sanitizeToken(id) + '-label', 'label');
        rowLabel.textContent = label;

        return rowLabel;
    },

    createRowDescription({ id, description }) {
        const rowDescription = document.createElement('span');
        rowDescription.classList.add(sanitizeToken(id) + '-description', 'description');
        rowDescription.textContent = description;

        return rowDescription;
    },

    showDescriptionTooltip({ description, mqtt }, x, y) {
        let tooltip = document.querySelector('.description-tooltip');
        if (!tooltip) {
            tooltip = document.createElement('div');
            tooltip.classList.add('description-tooltip');
            document.body.append(tooltip);
        }
        // textContent (not innerHTML) so a description stays inert, exactly as before.
        // This assignment also REPLACES all children, which is what clears the MQTT line
        // below — the tooltip node is reused across options, so without that reset a stale
        // key would stick to the next signal that has none. Keep it above that block.
        tooltip.textContent = description;
        // Signals that also publish over MQTT name their telemetry key here — a read-only
        // cross-reference for users wiring the same car through Home Assistant. Absent for
        // actions and for signals with no twin, in which case the tooltip is unchanged.
        if (mqtt) {
            const line = document.createElement('div');
            line.classList.add('description-mqtt');
            const caption = document.createElement('span');
            caption.textContent = BYD.i18n.t('automation.mqtt_topic') + ' ';
            const key = document.createElement('code');
            key.textContent = mqtt;
            line.append(caption, key);
            tooltip.append(line);
        }
        tooltip.style.left = x + 'px';
        tooltip.style.top = y + 'px';
        const position = tooltip.getBoundingClientRect();
        if (position.right > window.innerWidth) x = Math.max(0, window.innerWidth - tooltip.offsetWidth);
        if (position.left < 0) x = 0;
        if (position.bottom > window.innerHeight) y = Math.max(0, window.innerHeight + window.scrollY - tooltip.offsetHeight);
        if (position.top < 0) y = window.scrollY;
        tooltip.style.left = x + 'px';
        tooltip.style.top = y + 'px';
        tooltip.classList.toggle('visible', true);
    },

    hideDescriptionTooltip() {
        let tooltip = document.querySelector('.description-tooltip');
        if (tooltip) {
            tooltip.classList.toggle('visible', false);
        }
    },

    // `noControlFlow` (optional): drop options that carry nested child actions (If/Loop)
    // so a picker at the engine's max nesting depth can't offer a deeper control-flow row.
    getTypeEnum(id, options, noControlFlow) {
        return {
            type: 'enum',
            label: BYD.i18n.t('automation.select'),
            id,
            options: noControlFlow ? (options || []).filter(o => o && !o.hasChildActions) : options,
        };
    },

    // Localized <optgroup> heading for a category key. Falls back to a title-cased
    // key so an un-translated / newly-added category still reads sensibly.
    categoryLabel(cat) {
        const key = 'automation.category_' + cat;
        const t = BYD.i18n.t(key);
        if (t && t !== key) return t;
        return String(cat).replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    },

    // Append a catalog to an existing <select>, grouped by the same category
    // headings as the main automation pickers and alphabetized within each group.
    // Callers add their own placeholder before invoking this helper.
    appendSortedCatalogOptions(selector, options) {
        const list = (options || []).filter(o => o && o.id);
        if (!list.length) return;
        const makeOption = (option) => {
            const el = document.createElement('option');
            el.value = option.id;
            el.textContent = this.optionLabel(option);
            return el;
        };
        const compare = (a, b) => {
            const byLabel = this._collator().compare(
                this.optionLabel(a), this.optionLabel(b));
            return byLabel || String(a.id).localeCompare(String(b.id));
        };
        const grouped = list.some(o => o.category);
        if (!grouped) {
            for (const option of list.slice().sort(compare)) {
                selector.append(makeOption(option));
            }
            return;
        }

        const buckets = new Map();
        for (const option of list) {
            const cat = option.category || 'other';
            if (!buckets.has(cat)) buckets.set(cat, []);
            buckets.get(cat).push(option);
        }
        const order = (window.BYD && BYD.AUTOMATION_CATEGORY_ORDER) || [];
        const rank = (cat) => {
            if (cat === 'other') return Number.MAX_SAFE_INTEGER;
            const i = order.indexOf(cat);
            return i < 0 ? Number.MAX_SAFE_INTEGER - 1 : i;
        };
        const categories = Array.from(buckets.keys()).sort((a, b) => {
            const byRank = rank(a) - rank(b);
            return byRank || this._collator().compare(
                this.categoryLabel(a), this.categoryLabel(b));
        });
        for (const cat of categories) {
            const group = document.createElement('optgroup');
            group.label = this.categoryLabel(cat);
            for (const option of buckets.get(cat).slice().sort(compare)) {
                group.append(makeOption(option));
            }
            selector.append(group);
        }
    },

    // An option's display text, guarded against an UNRESOLVED server label. Labels are
    // translated daemon-side (Label.getLabel -> Messages.get) and Messages returns the KEY
    // itself on a miss, so a catalog that lacks the key put a raw "automation.camera_left" in
    // the dropdown. Retry against the web catalog (which carries most of the same keys) and
    // otherwise humanise the id, so the control is always readable even if a catalog is stale.
    optionLabel(option) {
        const label = option && option.label;
        if (typeof label !== 'string' || !label) return String((option && option.id) || '');
        if (!/^[a-z_]+(\.[A-Za-z0-9_]+)+$/.test(label)) return label;  // a real translation
        const t = BYD.i18n.t(label);
        if (t && t !== label) return t;
        const tail = label.substring(label.lastIndexOf('.') + 1);
        return tail.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    },

    createInput(data, defaultValue, eventListener) {
        switch (data.type) {
            case 'enum': return this.createEnumInput(data, defaultValue, eventListener);
            case 'string': return this.createStringInput(data, defaultValue, eventListener);
            case 'int': return this.createIntInput(data, defaultValue, eventListener);
            case 'colour': return this.createColourInput(data, defaultValue, eventListener);
            case 'time': return this.createTimeInput(data, defaultValue, eventListener);
            case 'app': return this.createAppInput(data, defaultValue, eventListener);
            case 'savedSeatPosition': return this.createSeatPositionInput(data, defaultValue, eventListener);
            case 'audio': return this.createAudioInput(data, defaultValue, eventListener);
            case 'actionGroup': return this.createActionGroupInput(data, defaultValue, eventListener);
            case 'automationRef': return this.createAutomationRefInput(data, defaultValue, eventListener);
            default: return this.createFallbackInput(data);
        }
    },

    // An unknown input type used to return `undefined`, and the caller then
    // did row.append(undefined) → TypeError, which aborted the whole form
    // render. Return a disabled, clearly-inert field instead so one unknown
    // type can't blank the entire form.
    createFallbackInput(data) {
        const input = document.createElement('input');
        input.classList.add('input', 'unknown');
        input.type = 'text';
        input.disabled = true;
        input.placeholder = (data && data.label != null) ? data.label : BYD.i18n.t('automation.unsupported_input');
        return input;
    },

    createEnumInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum');
        // The field name lives in a hidden placeholder <option>, so it disappears the
        // moment a value is chosen — a row then reads "Speed / Greater than / 60" with
        // no captions. Mirror it onto title + aria-label so the name is still
        // recoverable (long-press / hover) and is announced by assistive tech. Purely
        // additive: no DOM structure change, so the callers that add classes to this
        // node and the '#formGrid .invalid' Save gate are unaffected.
        if (data.label != null && data.label !== '') {
            selector.title = data.label;
            selector.setAttribute('aria-label', data.label);
        }
        const placeholder = document.createElement('option');
        placeholder.value = data.id;
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);
        const options = data.options || [];
        // Group under <optgroup> headings ONLY when the options carry a `category`
        // (the action / trigger / condition TYPE picker does; small on/off-style enum
        // variables do not, so those stay a flat list exactly as before). Category is a
        // cosmetic hint from the server — the stored value is still option.id, so this
        // never changes what a saved automation resolves to.
        const grouped = options.length && options.some(o => o && o.category);
        const mkOption = (option) => {
            const optionElement = document.createElement('option');
            optionElement.value = option.id;
            optionElement.textContent = this.optionLabel(option);
            return optionElement;
        };
        if (grouped) {
            selector.classList.add('categorized');
            // Bucket by category key, preserving first-seen option order within each.
            const buckets = new Map();
            for (const option of options) {
                const cat = (option && option.category) || 'other';
                if (!buckets.has(cat)) buckets.set(cat, []);
                buckets.get(cat).push(option);
            }
            // Server-declared display order; unknown categories sort after known ones
            // (stable), 'other' always last, so a new category never hides its items.
            const ORDER = (window.BYD && BYD.AUTOMATION_CATEGORY_ORDER) || [];
            const rank = (cat) => {
                if (cat === 'other') return Number.MAX_SAFE_INTEGER;
                const i = ORDER.indexOf(cat);
                return i < 0 ? Number.MAX_SAFE_INTEGER - 1 : i;
            };
            const cats = Array.from(buckets.keys()).sort((a, b) => rank(a) - rank(b));
            for (const cat of cats) {
                const group = document.createElement('optgroup');
                group.label = this.categoryLabel(cat);
                const items = buckets.get(cat).slice().sort((a, b) => {
                    const byLabel = this._collator().compare(
                        this.optionLabel(a), this.optionLabel(b));
                    return byLabel || String(a.id).localeCompare(String(b.id));
                });
                for (const option of items) group.append(mkOption(option));
                selector.append(group);
            }
        } else {
            for (const option of options) selector.append(mkOption(option));
        }
        if (defaultValue) selector.value = defaultValue;
        // A stored value the current schema doesn't offer (an action/trigger/condition
        // TYPE — or any enum value — from a newer build, a feature disabled on this
        // device, or a cross-version/community import). Without a matching <option> the
        // <select> can't select it: selectedIndex goes to -1, the row renders EMPTY, and
        // the .invalid class both styles it as an error AND — because the Save gate keys
        // off '#formGrid .invalid' (see _syncSaveDisabled / saveForm) — globally blocks
        // saving, so the whole automation becomes uneditable (can't even rename it).
        // Mirror the Signal picker (createConditionValueInput): keep the stored value as a
        // selected "(unavailable)" option so opening the form neither wipes it nor locks
        // Save. It is NOT marked invalid, so the rest of the form stays editable and the
        // step round-trips byte-identical on save. Only a real user change (picking a
        // known option, which fires the eventListener) rewrites it. An empty/placeholder
        // value (a fresh, unset row) is left to the normal invalid path so the user is
        // still forced to choose a type. `data.strictOptions` opts a picker OUT of this
        // preservation (see eqNeqComparator): where the options were CONTEXTUALLY narrowed
        // from a still-valid base set, an out-of-list value isn't "unavailable on this
        // device" — it's incompatible with the current context and must stay .invalid so the
        // user re-picks, exactly as before this change.
        const preserved = (!data.strictOptions
            && defaultValue != null && defaultValue !== ''
            && !options.some(o => o && o.id === defaultValue));
        if (preserved) {
            const miss = document.createElement('option');
            miss.value = defaultValue;
            miss.textContent = defaultValue + ' ' + BYD.i18n.t('automation.option_unavailable');
            selector.append(miss);
            selector.value = defaultValue;
        }
        // A strictOptions picker must NOT preserve an out-of-list value (see eqNeqComparator /
        // dynamicValueSchema), but it must still SHOW what is wrong: with no matching <option>
        // the <select> renders blank, so the user sees an empty red box, a dimmed Save and no
        // stated reason — for a row they never touched (a pre-dropdown numeric constant, or a
        // leftover from changing the LHS). Append the stale value as a clearly-flagged option
        // and select it, so the field reads e.g. "0 — no longer valid here". It is still marked
        // .invalid below (selector.value matches no real option), so Save stays blocked until a
        // real word is picked — the value is displayed, NOT accepted.
        const stale = (data.strictOptions
            && defaultValue != null && defaultValue !== ''
            && !options.some(o => o && o.id === defaultValue));
        if (stale) {
            const bad = document.createElement('option');
            bad.value = defaultValue;
            bad.textContent = defaultValue + ' ' + BYD.i18n.t('automation.option_invalid_here');
            selector.append(bad);
            selector.value = defaultValue;
        }
        // Add invalid class if the selected option is not within the options list
        const changeEvent = () => {
            const selected = options.find(option => option.id === selector.value);
            if (selected) {
                selector.classList.toggle('invalid', false);
                if (eventListener) eventListener(selector, selector.value, selected);
            } else if (preserved && selector.value === defaultValue) {
                // Preserved unavailable value — keep as-is, don't flag invalid (which would
                // lock Save) and don't fire the eventListener (there is no schema to render
                // its sub-fields from; formData already holds the original step intact).
                selector.classList.toggle('invalid', false);
            } else {
                selector.classList.toggle('invalid', true);
            }
        };
        selector.addEventListener('change', changeEvent);
        changeEvent();
        return selector;
    },

    createStringInput(data, defaultValue, eventListener) {
        const maxLength = (data.maxLength != null) ? data.maxLength : 2147483647;
        const input = document.createElement(maxLength <= 30 ? 'input' : 'textarea');
        input.classList.add('input', 'string');
        input.placeholder = data.label;
        input.maxLength = maxLength;
        // A saved number 0 (e.g. an If "variable eq 0" RHS) is a real value, not "empty".
        if (defaultValue != null && defaultValue !== false) input.value = defaultValue;
        // Add invalid class if the selected value is longer than maxLength
        const changeEvent = () => {
            if (input.value.length <= maxLength) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, input.value);
            } else {
                input.classList.toggle('invalid', true);
            }
        };
        input.addEventListener('change', changeEvent);
        changeEvent();
        // Optional cautionary note (e.g. the shell-command action). When present
        // we wrap the input + an amber warning box, matching the key-mapping
        // shell field. The wrapper still carries the input classes the caller
        // adds (variable-input etc.), and .value/.addEventListener are proxied so
        // the caller's eventListener + form serialization keep working unchanged.
        if (data.warning) {
            const wrap = document.createElement('div');
            wrap.classList.add('string-with-warning');
            wrap.append(input);
            const warn = document.createElement('div');
            warn.classList.add('field-warning');
            warn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg><span></span>';
            warn.querySelector('span').textContent = data.warning;
            wrap.append(warn);
            // Proxy the class list add the caller performs onto the actual input
            // so styling/serialization hooks still land on the field, not the div.
            // (createInput's caller does variableSelector.classList.add(...); the
            // wrapper tolerates that harmlessly, and the input keeps 'string'.)
            return wrap;
        }
        return input;
    },

    createIntInput(data, defaultValue, eventListener) {
        const input = document.createElement('input');
        input.classList.add('input', 'int');
        input.type = 'number';
        input.placeholder = data.label;
        const min = (data.min != null) ? data.min : 0;
        const max = (data.max != null) ? data.max : 2147483647;
        input.min = min;
        input.max = max;
        if (defaultValue != null && !isNaN(defaultValue)) input.value = defaultValue;
        // Add invalid class if the selected value is not within the min and max
        const changeEvent = () => {
            const value = parseInt(input.value, 10);
            if (!isNaN(value) && value >= min && value <= max) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, value);
            } else {
                input.classList.toggle('invalid', true);
            }
        };
        input.addEventListener('change', changeEvent);
        changeEvent();
        return input;
    },

    // Ambient-colour picker rendered as a SWATCH GRID matching the Vehicle Control page's
    // colour picker (round chips + active ring), replacing the old drag-a-gradient range
    // slider that was hard to aim on the fixed 31-entry BYD palette. The stored value is
    // still the 1-based palette INDEX the car expects (the ambient light takes an index,
    // not a free hex), so this is purely a nicer front-end over the same value — no action
    // or SDK change. Tap a chip to select; the active chip gets the ring. touchend is
    // handled too for WebView tap reliability (same as vehicle-control's swatches).
    createColourInput(data, defaultValue, eventListener) {
        const wrap = document.createElement('div');
        wrap.classList.add('input', 'colour-swatches');
        const codes = (data.colourCodes != null && data.colourCodes.length) ? data.colourCodes : ['#00D4AA'];
        const min = 1, max = codes.length;
        let current = (defaultValue != null && !isNaN(defaultValue)
            && defaultValue >= min && defaultValue <= max) ? parseInt(defaultValue, 10) : 1;

        const swatches = [];
        const select = (idx) => {
            current = idx;
            for (let k = 0; k < swatches.length; k++) {
                swatches[k].classList.toggle('active', (k + 1) === current);
            }
            if (eventListener) eventListener(wrap, current);
        };

        for (let i = 0; i < codes.length; i++) {
            (function (hex, oneBased) {
                const sw = document.createElement('div');
                sw.classList.add('colour-swatch');
                if (oneBased === current) sw.classList.add('active');
                sw.style.backgroundColor = hex;
                sw.title = hex;
                sw.addEventListener('click', function (e) { e.stopPropagation(); select(oneBased); });
                sw.addEventListener('touchend', function (e) { e.preventDefault(); e.stopPropagation(); select(oneBased); });
                swatches.push(sw);
                wrap.append(sw);
            })(codes[i], i + 1);
        }
        // Emit the initial value so the form has it even if the user never taps (parity
        // with the old slider, which fired changeEvent() once on build).
        if (eventListener) eventListener(wrap, current);
        return wrap;
    },

    createTimeInput(data, defaultValue, eventListener) {
        const input = document.createElement('input');
        input.classList.add('input', 'time');
        input.type = 'time';
        input.placeholder = data.label;
        // '' is not midnight: isNaN('') is false, so exclude it explicitly.
        if (defaultValue != null && defaultValue !== '' && !isNaN(defaultValue)) {
            input.value = this.timeToString(defaultValue);
        }
        // Add invalid class if the selected value is not a valid time
        const changeEvent = () => {
            const time = input.value;
            let value = null;
            if (time) {
                const split = time.split(':');
                value = parseInt(split[0], 10) * 60 + parseInt(split[1], 10);
            }
            if (value != null && !isNaN(value) && value >= 0 && value < 60 * 24) {
                input.classList.toggle('invalid', false);
                if (eventListener) eventListener(input, value);
            } else {
                input.classList.toggle('invalid', true);
            }
        };
        input.addEventListener('input', changeEvent);
        changeEvent();
        return input;
    },

    timeToString(value) {
        const hours = Math.floor(value / 60);
        const mins = value % 60;
        return String(hours).padStart(2, '0') + ':' + String(mins).padStart(2, '0');
    },

    automationValueToText(spec, rawVal) {
        const option = spec && spec.options && spec.options.find(o => o.id === rawVal);
        if (option && option.label != null) return this.optionLabel(option);
        if (spec && spec.type === 'time' && rawVal != null && !isNaN(rawVal)) return this.timeToString(rawVal);
        if (spec && spec.type === 'app' && rawVal != null) {
            // Prefer the friendly label if the app list is already cached; else the package name.
            const app = (this._appList || []).find(a => a.package === rawVal);
            return this._escVal((app && app.label) ? app.label : rawVal);
        }
        if (spec && spec.type === 'savedSeatPosition' && rawVal != null) {
            // Stored value is the position id; show the position's NAME. Distinguish "cache
            // not loaded yet" from "loaded and the id is gone" — collapsing the two would
            // label every position as missing for the moment before loadPositionList lands.
            const list = this._positionList;
            if (!list) return this._escVal(rawVal);
            const pos = list.find(p => p.id === rawVal);
            if (pos) return this._escVal(pos.name);
            return this._escVal(rawVal) + ' (' + this._escVal(BYD.i18n.t('automation.position_missing')) + ')';
        }
        if (spec && spec.type === 'actionGroup' && rawVal != null) {
            // The stored value is a group UUID; show its friendly NAME from the cached
            // group list (loadGroups fills this._groups on init + after any change, and
            // re-renders the automation list so the name fills in once the cache lands).
            // Fall back to the raw id when the group is missing/not-yet-loaded.
            const grp = (this._groups || []).find(g => g.id === rawVal);
            return this._escVal((grp && grp.name) ? grp.name : rawVal);
        }
        // rawVal is a user/community-supplied automation VALUE that buildAutomationText
        // interpolates into an innerHTML string. Escape it so a shared automation whose
        // variable value contains markup can't inject script when its rule prose renders
        // (community detail preview AND the local card, which renders imported automations
        // too). The schema option.label + computed time string above are trusted.
        return this._escVal(rawVal);
    },

    // Escape a dynamic value for safe innerHTML interpolation. Prefers BYD.core._esc;
    // falls back to a local textContent escape so a missing core can't leave it raw.
    _escVal(v) {
        if (v == null) return v;
        if (window.BYD && BYD.core && BYD.core._esc) return BYD.core._esc(String(v));
        const d = document.createElement('div');
        d.textContent = String(v);
        return d.innerHTML;
    },

    // Installed-app dropdown, populated live from GET /api/apps/list. The value
    // stored is the package name; the label shown is the app's display name.
    // Unlike enum, options are NOT in the schema (apps differ per device).
    createAppInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum', 'app');
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);

        const fill = (apps) => {
            // Drop any previously-added app options (keep the placeholder at index 0).
            while (selector.options.length > 1) selector.remove(1);
            for (const app of apps) {
                const opt = document.createElement('option');
                opt.value = app.package;
                opt.textContent = app.label || app.package;
                selector.append(opt);
            }
            // If the stored package isn't in the list (uninstalled since), add it so
            // the binding still shows what it points at rather than silently blanking.
            if (defaultValue && !apps.some(a => a.package === defaultValue)) {
                const opt = document.createElement('option');
                opt.value = defaultValue;
                opt.textContent = defaultValue;
                selector.append(opt);
            }
            if (defaultValue) selector.value = defaultValue;
            changeEvent();
            // The list resolves asynchronously — AFTER showForm() already ran
            // _syncSaveDisabled() while this field was still the placeholder-only
            // (invalid) state. Re-sync now that a valid default may have cleared
            // .invalid, or Save stays stuck disabled (pointer-events:none) until
            // the user touches another field.
            this._syncSaveDisabled();
        };

        const changeEvent = () => {
            const ok = !!selector.value;
            selector.classList.toggle('invalid', !ok);
            if (ok && eventListener) eventListener(selector, selector.value);
        };
        selector.addEventListener('change', changeEvent);

        // Async-load the app list once; reuse the cache on subsequent renders.
        this.loadAppList().then(fill).catch(() => { changeEvent(); this._syncSaveDisabled(); });
        changeEvent();
        return selector;
    },

    // Saved seat/mirror position dropdown, populated live from GET /api/positions. The
    // value stored is the position ID, never its name: a captured position's name is
    // rebuilt from the car on every capture, so a name-keyed binding would silently stop
    // matching after a rename in the car. Options are NOT in the schema (positions are
    // created and deleted at runtime, and captured ones differ per signed-in DiLink
    // profile). Mirrors createAppInput, with optgroups because the list mixes the user's
    // own positions with the car's slots for one or more profiles.
    createSeatPositionInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum', 'seat-position');
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);

        const fill = (positions) => {
            while (selector.options.length > 1) selector.remove(1);

            const add = (parent, p) => {
                const opt = document.createElement('option');
                opt.value = p.id;
                opt.textContent = p.name || p.id;
                parent.append(opt);
            };
            const group = (label) => {
                const g = document.createElement('optgroup');
                g.label = label;
                selector.append(g);
                return g;
            };

            const mine = positions.filter(p => p.source === 'user');
            if (mine.length) {
                const g = group(BYD.i18n.t('automation.positions_mine'));
                mine.forEach(p => add(g, p));
            }
            // Captured positions are per-profile, so group by profile — the same slot
            // number means different geometry for a different signed-in account.
            const profiles = [];
            positions.forEach(p => {
                if (p.source === 'captured' && profiles.indexOf(p.profile) === -1) profiles.push(p.profile);
            });
            profiles.forEach(prof => {
                const g = group(BYD.i18n.t('automation.positions_from_car') + ' — ' + prof);
                positions.filter(p => p.source === 'captured' && p.profile === prof).forEach(p => add(g, p));
            });

            // If the stored id isn't in the list (deleted since), add it so the binding
            // shows what it points at rather than silently blanking — same as apps.
            if (defaultValue && !positions.some(p => p.id === defaultValue)) {
                const opt = document.createElement('option');
                opt.value = defaultValue;
                opt.textContent = defaultValue + ' (' + BYD.i18n.t('automation.position_missing') + ')';
                selector.append(opt);
            }
            if (defaultValue) selector.value = defaultValue;
            changeEvent();
            // Same reason as createAppInput: the list resolves after showForm() already
            // ran _syncSaveDisabled() against the placeholder-only state.
            this._syncSaveDisabled();
        };

        const changeEvent = () => {
            const ok = !!selector.value;
            selector.classList.toggle('invalid', !ok);
            if (ok && eventListener) eventListener(selector, selector.value);
        };
        selector.addEventListener('change', changeEvent);

        this.loadPositionList().then(fill).catch(() => { changeEvent(); this._syncSaveDisabled(); });
        changeEvent();
        return selector;
    },

    // Uploaded-sound dropdown, populated live from GET /api/audio/library. The value
    // stored is the sound's filename; options are NOT in the schema (they differ per
    // device and change as the user uploads/deletes). Mirrors createAppInput.
    createAudioInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum', 'audio');
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);

        const fill = (sounds) => {
            while (selector.options.length > 1) selector.remove(1);
            for (const s of sounds) {
                const opt = document.createElement('option');
                opt.value = s.name;
                opt.textContent = s.name;
                selector.append(opt);
            }
            // If the stored sound was deleted since, keep showing it so the binding
            // reveals what it points at rather than silently blanking.
            if (defaultValue && !sounds.some(s => s.name === defaultValue)) {
                const opt = document.createElement('option');
                opt.value = defaultValue;
                opt.textContent = defaultValue + ' (missing)';
                selector.append(opt);
            }
            if (defaultValue) selector.value = defaultValue;
            changeEvent();
            this._syncSaveDisabled();
        };

        const changeEvent = () => {
            const ok = !!selector.value;
            selector.classList.toggle('invalid', !ok);
            if (ok && eventListener) eventListener(selector, selector.value);
        };
        selector.addEventListener('change', changeEvent);

        this.loadAudioList().then(fill).catch(() => { changeEvent(); this._syncSaveDisabled(); });
        changeEvent();
        return selector;
    },

    // Fetch (fresh each render — no cache, since the user may have just uploaded a
    // sound in the library panel) the audio library. Resolves to [{name,path,size}].
    loadAudioList() {
        return fetch('/api/audio/library', { cache: 'no-store' })
            .then(r => r.json())
            .then(j => (j && j.success && Array.isArray(j.sounds)) ? j.sounds : [])
            .catch(() => []);
    },

    // Live-populated action-group dropdown (mirrors createAudioInput): the stored value
    // is the group id; the label shows the group name. Fetched fresh each render since
    // the user may have just created a group. A stored id that no longer resolves is
    // still shown (marked missing) so the binding reveals what it points at.
    createActionGroupInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum', 'action-group');
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);

        const fill = (groups) => {
            while (selector.options.length > 1) selector.remove(1);
            for (const g of groups) {
                const opt = document.createElement('option');
                opt.value = g.id;
                opt.textContent = g.name;
                selector.append(opt);
            }
            if (defaultValue && !groups.some(g => g.id === defaultValue)) {
                const opt = document.createElement('option');
                opt.value = defaultValue;
                opt.textContent = defaultValue + ' (missing)';
                selector.append(opt);
            }
            if (defaultValue) selector.value = defaultValue;
            changeEvent();
            this._syncSaveDisabled();
        };
        const changeEvent = () => {
            const ok = !!selector.value;
            selector.classList.toggle('invalid', !ok);
            if (ok && eventListener) eventListener(selector, selector.value);
        };
        selector.addEventListener('change', changeEvent);
        this.loadActionGroups().then(fill).catch(() => { changeEvent(); this._syncSaveDisabled(); });
        changeEvent();
        return selector;
    },

    // Fetch the saved action groups. Resolves to [{id, name}]. Fresh each render.
    loadActionGroups() {
        return fetch('/api/action-groups/list', { cache: 'no-store' })
            .then(r => r.json())
            .then(j => Array.isArray(j) ? j : [])
            .catch(() => []);
    },

    // Live-populated OTHER-automation picker for the "Control Automation" action. Value
    // is the target automation's id; label is its name (or generated fallback). Excludes
    // the automation currently being edited so a rule can't target itself in the picker.
    createAutomationRefInput(data, defaultValue, eventListener) {
        const selector = document.createElement('select');
        selector.classList.add('input', 'enum', 'automation-ref');
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = data.label;
        placeholder.disabled = true;
        placeholder.selected = true;
        placeholder.hidden = true;
        selector.append(placeholder);

        const fill = (autos) => {
            while (selector.options.length > 1) selector.remove(1);
            for (const a of autos) {
                const opt = document.createElement('option');
                opt.value = a.id;
                opt.textContent = a.name;
                selector.append(opt);
            }
            if (defaultValue && !autos.some(a => a.id === defaultValue)) {
                const opt = document.createElement('option');
                opt.value = defaultValue;
                opt.textContent = defaultValue + ' (missing)';
                selector.append(opt);
            }
            if (defaultValue) selector.value = defaultValue;
            changeEvent();
            this._syncSaveDisabled();
        };
        const changeEvent = () => {
            const ok = !!selector.value;
            selector.classList.toggle('invalid', !ok);
            if (ok && eventListener) eventListener(selector, selector.value);
        };
        selector.addEventListener('change', changeEvent);
        this.loadAutomationRefs().then(fill).catch(() => { changeEvent(); this._syncSaveDisabled(); });
        changeEvent();
        return selector;
    },

    // Fetch the [{id,name}] picker list, excluding the automation being edited.
    loadAutomationRefs() {
        const self = this.editingId ? ('?self=' + encodeURIComponent(this.editingId)) : '';
        return fetch('/api/automations/picker' + self, { cache: 'no-store' })
            .then(r => r.json())
            .then(j => Array.isArray(j) ? j : [])
            .catch(() => []);
    },

    // Fetch + cache the installed-app list. Resolves to [{package, label}].
    loadAppList() {
        if (this._appList) return Promise.resolve(this._appList);
        if (this._appListPromise) return this._appListPromise;
        this._appListPromise = fetch('/api/apps/list')
            .then(r => r.json())
            .then(j => {
                this._appList = (j && j.success && Array.isArray(j.apps)) ? j.apps : [];
                return this._appList;
            })
            .catch(e => { this._appList = []; return this._appList; });
        return this._appListPromise;
    },

    // Fetch + cache the saved seat/mirror positions. Resolves to
    // [{id, name, source, profile, slot, axes}]. Loaded on init as well as on demand,
    // because the saved-automation cards resolve a stored position id to its name for
    // display — same reasoning as loadGroups.
    loadPositionList() {
        if (this._positionList) return Promise.resolve(this._positionList);
        if (this._positionListPromise) return this._positionListPromise;
        this._positionListPromise = fetch('/api/positions', { cache: 'no-store' })
            .then(r => r.json())
            .then(j => {
                this._positionList = (j && Array.isArray(j.positions)) ? j.positions : [];
                this._positionListFailed = !(j && Array.isArray(j.positions));
                // Re-render the automation list now the cache is fresh, or a card summary
                // would show the raw position id until the next full reload. loadPositions
                // and loadAutomations race on init. Cheap no-op when the list is empty.
                try {
                    if (this.automations && Object.keys(this.automations).length) this.render();
                } catch (_) {}
                return this._positionList;
            })
            .catch(() => { this._positionList = []; this._positionListFailed = true; return this._positionList; });
        return this._positionListPromise;
    },

    _switchTab(id) {
        if (typeof window.OT_setActiveTab === 'function') window.OT_setActiveTab(id);
    },

    _syncSaveDisabled() {
        // :has() is Chrome 105+ (the head-unit WebView is Chrome 58), so the
        // "disable Save while the form has an invalid field" affordance can't
        // live in CSS. Reflect it onto the button here instead — called after
        // every render and whenever a field's validity may have changed.
        const saveBtn = document.getElementById('saveBtn');
        if (!saveBtn) return;
        // Scope to #formGrid — the Action Group editor's #groupFormGrid ALSO carries the
        // .form-grid class, so an unscoped '.form-grid .invalid' would let a half-built
        // group (an untyped action row) wrongly disable the automation Save (and vice
        // versa). Each form validates only its own grid.
        const hasInvalid = document.querySelectorAll('#formGrid .invalid').length > 0;
        // Also block on a manualClip whose before+after window is out of the daemon's
        // 1..60s contract — a cross-field rule the per-field .invalid gate can't catch.
        const badClip = this._hasInvalidManualClip([this.formData.actions, this.formData.elseActions]);
        saveBtn.classList.toggle('is-disabled', hasInvalid || badClip);
    },

    showForm(id, automation) {
        this.editingId = id;
        // Deep-clone the source automation so editing the form does NOT mutate
        // the cached list item in place (which would survive until reload and
        // make Cancel a no-op). A fresh add starts from an empty object.
        this.formData = automation ? JSON.parse(JSON.stringify(automation)) : {};
        var titleEl = document.getElementById('formTitle');
        if (titleEl) {
            if (id) {
                titleEl.textContent = BYD.i18n.t('automation.edit_automation');
            } else {
                titleEl.textContent = BYD.i18n.t('automation.add_automation');
            }
        }
        this.renderForm();
        this._syncSaveDisabled();
        this._switchTab('add');
        // AFTER the tab switch: the hints were built by renderForm() while this tab was still
        // hidden, so the visibility gate in startStatePolling correctly refused to start then.
        // Now that the form is on screen, start it.
        this.startStatePolling();
    },

    hideForm() {
        // Cancel returns to the Automations list. editingId is reset so a
        // subsequent tap on the empty-state CTA opens a fresh form.
        this.showForm(null, {});
        this._switchTab('automations');
    },

    async saveForm() {
        // Scope to #formGrid so a hidden, half-built group in #groupFormGrid can't block
        // saving a valid automation (both grids share the .form-grid class).
        if (document.querySelectorAll('#formGrid .invalid').length) {
            this.toast(BYD.i18n.t('automation.invalid_form'), 'error');
        } else if (this._hasInvalidManualClip([this.formData.actions, this.formData.elseActions])) {
            // Specific message (not the generic "invalid form") so the user knows the clip
            // duration is the problem — the daemon rejects a total outside 1..60s.
            this.toast(BYD.i18n.t('automation.clip_window_invalid'), 'error');
        } else {
            try {
                const path = this.editingId
                    ? '/api/automations/automation/' + encodeURIComponent(this.editingId)
                    : '/api/automations/automation';
                const resp = await fetch(path, {
                    method: this.editingId ? 'PUT' : 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.formData)
                });
                if (resp.ok) {
                    // Success bodies are JSON; parse defensively.
                    let result = {};
                    try { result = await resp.json(); } catch (e) {}
                    if (result.success !== false) {
                        await this.loadAutomations();
                        this.hideForm();
                        return this.toast(BYD.i18n.t('toast.saved'), 'success');
                    }
                }
                // Rejected (e.g. 400): surface the daemon's actual reason instead of a
                // generic "save failed". The error endpoint replies text/plain OR a
                // {error} JSON; read as text and fall back to the generic string. Without
                // this, a rejected big/deep automation showed only "Failed to save" with
                // no hint of the cause (the reported "can't save my big automation").
                let msg = '';
                try {
                    const raw = await resp.text();
                    try { const j = JSON.parse(raw); msg = j.error || j.message || ''; }
                    catch (e) { msg = raw; }
                } catch (e) {}
                return this.toast(msg && msg.trim() ? msg.trim() : BYD.i18n.t('errors.save_failed'), 'error');
            } catch (e) {}
            this.toast(BYD.i18n.t('errors.save_failed'), 'error');
        }
    },

    async triggerAutomation(key, btn) {
        const card = btn && btn.closest ? btn.closest('.card') : null;
        if (btn) {
            if (btn.classList.contains('is-running')) return;
            this._setRunState(btn, card, 'running');
        }
        let ok = false;
        try {
            const resp = await fetch('/api/automations/test/' + encodeURIComponent(key), { method: 'POST' });
            const result = await resp.json();
            ok = !!result.success;
        } catch (e) {}
        if (btn) this._setRunState(btn, card, ok ? 'done' : 'failed');
        if (ok) return this.toast(BYD.i18n.t('automation.toast_triggered'), 'success');
        this.toast(BYD.i18n.t('errors.generic'), 'error');
    },

    // Run-now feedback on the button and its card: spinner + sweep while the
    // request is out, then a drawn check (or a shake) before the play icon returns.
    _setRunState(btn, card, state) {
        clearTimeout(btn._runTimer);
        btn.classList.remove('is-running', 'is-done', 'is-failed');
        if (card) card.classList.remove('is-running', 'is-done', 'is-failed');
        if (state === 'idle') {
            btn.innerHTML = triggerIcon;
            btn.disabled = false;
            btn.removeAttribute('aria-busy');
            return;
        }
        btn.innerHTML = state === 'running' ? runSpinIcon : (state === 'done' ? runDoneIcon : runFailIcon);
        btn.disabled = state === 'running';
        if (state === 'running') btn.setAttribute('aria-busy', 'true');
        else btn.removeAttribute('aria-busy');
        void btn.offsetWidth; // restart the keyframes when states repeat quickly
        btn.classList.add('is-' + state);
        if (card && state === 'done') {
            // Light the recipe up trigger → conditions → actions, in reading order.
            const steps = card.querySelectorAll('.recipe-sec, .recipe-chip');
            for (let i = 0; i < steps.length; i++) steps[i].style.setProperty('--run-i', Math.min(i, 12));
        }
        if (card) card.classList.add('is-' + state);
        if (state !== 'running') {
            btn._runTimer = setTimeout(() => this._setRunState(btn, card, 'idle'), 1800);
        }
    },

    async deleteAutomation(key) {
        // Themed confirm (BYD.utils.confirmDialog) instead of the native confirm() —
        // matches the app look/feel and avoids the browser dialog leaking the
        // loopback origin. Falls back to native confirm only if utils isn't loaded.
        const ok = (window.BYD && BYD.utils && BYD.utils.confirmDialog)
            ? await BYD.utils.confirmDialog({
                title: BYD.i18n.t('confirm.delete'),
                body: BYD.i18n.t('automation.confirm_delete_body'),
                confirmLabel: BYD.i18n.t('common.delete'),
                cancelLabel: BYD.i18n.t('common.cancel'),
                danger: true
              })
            : confirm(BYD.i18n.t('confirm.delete'));
        if (!ok) return;
        try {
            const resp = await fetch('/api/automations/automation/' + encodeURIComponent(key), { method: 'DELETE' });
            const result = await resp.json();
            if (result.success) {
                await this.loadAutomations();
                return this.toast(BYD.i18n.t('toast.deleted'), 'success');
            }
        } catch (e) {}
        this.toast(BYD.i18n.t('errors.delete_failed'), 'error');
    },

    async setAutomationMode(key, mode) {
        try {
            const resp = await fetch('/api/automations/mode/' + encodeURIComponent(key), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mode })
            });
            const result = await resp.json();
            if (result.success) {
                await this.loadAutomations();
                return this.toast(BYD.i18n.t('toast.saved'), 'success');
            }
        } catch (e) {}
        // The backend did not accept the toggle — re-render from cached state so the switch reverts to
        // the real backend value instead of staying stuck in the flipped position the user just clicked.
        this.render();
        this.toast(BYD.i18n.t('errors.save_failed'), 'error');
    },

    // Legacy internal helper retained for any older inline caller.
    disableAutomation(key, disabled) {
        return this.setAutomationMode(key, disabled ? 'disabled' : 'automatic');
    },

    // ── Action Groups ────────────────────────────────────────────────────────────
    // A reusable, named action sequence, run by the "Run action group" action / a keymap.
    // The daemon already had full CRUD (/api/action-groups); this is the missing editor.
    // groupData holds the group being edited: { id?, name, actions:[...] }. The action
    // list reuses the SAME createNestedActions widget the if/loop branches use, fed the
    // shared actionCatalog(), and re-rendered via renderGroupForm (not renderForm).

    groupData: null,      // the group currently open in the editor, or null
    _groups: [],          // cached [{id, name, actions}] for the list
    _groupsLoaded: false, // true once loadGroups() has filled _groups at least once

    // Load + render the saved groups into #groupList (called on init + after any change).
    async loadGroups() {
        // Same reply rule and failed/pending flags as loadAutomations.
        const seq = this._groupsSeq = (this._groupsSeq || 0) + 1;
        this._groupsPending = true;
        let map = null;
        try {
            const resp = await fetch('/api/action-groups', { cache: 'no-store' });
            map = await resp.json();
        } catch (e) { /* counted as a failed read below */ }
        const ok = !!map && map.success !== false;
        if (ok ? seq < (this._groupsOk || 0) : seq < (this._groupsLast || 0)) return;
        if (ok) this._groupsOk = seq;
        if (seq > (this._groupsLast || 0)) this._groupsLast = seq;
        if (seq === this._groupsSeq) this._groupsPending = false;
        this._groupsFailed = !ok;
        // Shape: { id: {name, actions:[...]} }. Flatten to an array for the list.
        this._groups = ok ? Object.keys(map).map(id => ({
            id, name: (map[id] && map[id].name) || id,
            actions: (map[id] && map[id].actions) || []
        })) : [];
        this._groupsLoaded = true;
        this.renderGroupList();
        // The saved-automation cards resolve an "Action Group" action's stored UUID to
        // its friendly name via this._groups (see automationValueToText). loadGroups()
        // and loadAutomations() race on init, and a group can be renamed on the Groups
        // tab — so re-render the automation list now that the cache is fresh, otherwise a
        // card summary would show the raw UUID until the next full reload. Guarded + only
        // when the list is already populated so this is a cheap no-op otherwise.
        try {
            if (this.automations && Object.keys(this.automations).length) this.render();
        } catch (_) {}
    },

    // Export all action groups. Same client-side-Blob download as exportAll() above (no
    // server file is written, so it works over the tunnel too); the envelope key is
    // `actionGroups`, distinct from the automation backup's `automations`, so the two
    // backups can never be imported into the wrong store.
    async exportGroups() {
        try {
            const resp = await fetch('/api/action-groups/export', { cache: 'no-store' });
            if (!resp.ok) { this.toast(BYD.i18n.t('automation.groups_backup_export_failed'), 'error'); return; }
            const text = await resp.text();
            const blob = new Blob([text], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'overdrive-action-groups.json';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            this.toast(BYD.i18n.t('automation.groups_backup_exported'), 'success');
        } catch (e) {
            this.toast(BYD.i18n.t('automation.groups_backup_export_failed'), 'error');
        }
    },

    // Import an action-group backup (merge). Mirrors importFile(): FileReader rather than
    // Blob.text() (Chrome 58 WebView), a local JSON.parse for a clean early error, and the
    // daemon validates every group's actions before storing so a bad file changes nothing.
    importGroupsFile(file) {
        if (!file) return;
        const input = document.getElementById('groupImportFile');
        const done = () => { if (input) input.value = ''; }; // re-select same file re-fires onchange
        const reader = new FileReader();
        reader.onerror = () => { this.toast(BYD.i18n.t('automation.groups_backup_import_failed'), 'error'); done(); };
        reader.onload = () => {
            const text = String(reader.result || '');
            try {
                JSON.parse(text);
            } catch (parseErr) {
                this.toast(BYD.i18n.t('automation.groups_backup_import_invalid'), 'error');
                done();
                return;
            }
            fetch('/api/action-groups/import', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: text
            })
            .then(resp => resp.json().catch(() => null))
            .then(data => {
                if (data && data.success) {
                    // Refreshes the group list AND re-renders the automation cards, so an
                    // "Action Group" action shows the imported group's name, not its UUID.
                    this.loadGroups();
                    this.toast(BYD.i18n.t('automation.groups_backup_imported')
                        .replace('{0}', String(data.imported)), 'success');
                } else {
                    this.toast((data && data.error) ? data.error : BYD.i18n.t('automation.groups_backup_import_failed'), 'error');
                }
            })
            .catch(() => this.toast(BYD.i18n.t('automation.groups_backup_import_failed'), 'error'))
            .then(done);
        };
        reader.readAsText(file);
    },

    renderGroupList() {
        const list = document.getElementById('groupList');
        const empty = document.getElementById('groupEmpty');
        if (!list) return;
        list.innerHTML = '';
        if (!this._groups.length) {
            if (empty) empty.style.display = '';
            return;
        }
        if (empty) empty.style.display = 'none';
        for (const g of this._groups) list.append(this.createGroupElement(g));
    },

    // Same card shape as an automation (recipe chips + icon actions), minus the mode row.
    createGroupElement(g) {
        const card = document.createElement('div');
        card.classList.add('card', 'group-card');
        card.setAttribute('data-group-id', g.id);

        const header = document.createElement('div');
        header.classList.add('header');
        card.append(header);

        const info = document.createElement('div');
        info.classList.add('info', 'recipe-info');
        const acts = Array.isArray(g.actions) ? g.actions : [];
        let html = '<strong class="automation-name">' + escapeHtml(g.name) + '</strong><br>'
            + '<span class="automation-stats">'
            + escapeHtml(BYD.i18n.t('automation.group_action_count').replace('{n}', acts.length))
            + '</span><br>';
        if (this.schemaSections().length) {
            html += this.buildAutomationText({ actions: acts }, { recipe: true, sections: ['actions'] });
        }
        info.innerHTML = html;
        header.append(info);

        const actions = document.createElement('div');
        actions.classList.add('actions');
        header.append(actions);
        const addButton = (title, icon, extra, onClick) => {
            const b = document.createElement('button');
            b.classList.add('action', 'icon-btn');
            if (extra) b.classList.add(extra);
            b.title = title;
            b.setAttribute('aria-label', title);
            b.innerHTML = icon;
            b.addEventListener('click', () => onClick(b));
            actions.append(b);
            return b;
        };
        addButton(BYD.i18n.t('automation.trigger'), triggerIcon, 'run-btn', (b) => this.runGroup(g.id, b));
        // No id, so Save creates a new group (mirrors the automation copy button).
        addButton(BYD.i18n.t('automation.copy_group'), copyIcon, null,
            () => this.showGroupForm({ name: g.name, actions: acts }));
        addButton(BYD.i18n.t('automation.edit_group'), editIcon, null, () => this.showGroupForm(g));
        addButton(BYD.i18n.t('common.delete'), deleteIcon, 'danger', () => this.deleteGroup(g.id, g.name));
        return card;
    },

    async runGroup(id, btn) {
        const card = btn && btn.closest ? btn.closest('.card') : null;
        if (btn) {
            if (btn.classList.contains('is-running')) return;
            this._setRunState(btn, card, 'running');
        }
        let ok = false;
        try {
            const resp = await fetch('/api/action-groups/' + encodeURIComponent(id) + '/run', { method: 'POST' });
            const result = await resp.json();
            ok = !!(result && result.success);
        } catch (e) {}
        if (btn) this._setRunState(btn, card, ok ? 'done' : 'failed');
        if (ok) return this.toast(BYD.i18n.t('automation.toast_group_ran'), 'success');
        this.toast(BYD.i18n.t('errors.generic'), 'error');
    },

    // Open the group editor for a new (g == null) or existing group. Deep-clones so
    // Cancel discards edits, mirroring showForm().
    showGroupForm(g) {
        this.groupData = g
            ? { id: g.id, name: g.name, actions: JSON.parse(JSON.stringify(g.actions || [])) }
            : { name: '', actions: [] };
        this.renderGroupForm();
        this._switchTab('groups');
        // Reveal the editor, hide the list section within the Groups tab.
        const ed = document.getElementById('groupEditor');
        const ls = document.getElementById('groupListSection');
        if (ed) ed.style.display = '';
        if (ls) ls.style.display = 'none';
        // AFTER the reveal, for the same reason showForm() does: renderGroupForm() built any
        // signal hints while this editor was still display:none, so the visibility gate
        // correctly refused to start the live-value poll then.
        this.startStatePolling();
    },

    hideGroupForm() {
        this.groupData = null;
        // Clear the grid so no leftover .invalid action rows linger in the (now hidden)
        // group editor — otherwise even the #formGrid-scoped checks aside, a stale group
        // DOM is dead weight and could confuse a future unscoped query.
        const grid = document.getElementById('groupFormGrid');
        if (grid) grid.innerHTML = '';
        const ed = document.getElementById('groupEditor');
        const ls = document.getElementById('groupListSection');
        if (ed) ed.style.display = 'none';
        if (ls) ls.style.display = '';
    },

    renderGroupForm() {
        const grid = document.getElementById('groupFormGrid');
        if (!grid || !this.groupData) return;
        grid.innerHTML = '';

        // Name field (bound to groupData.name).
        const nameRow = document.createElement('div');
        nameRow.classList.add('name-row', 'row');
        const nameLabel = document.createElement('span');
        nameLabel.classList.add('label');
        nameLabel.textContent = BYD.i18n.t('automation.group_name');
        const nameInput = document.createElement('input');
        nameInput.type = 'text';
        nameInput.classList.add('input', 'string');
        nameInput.placeholder = BYD.i18n.t('automation.group_name_placeholder');
        nameInput.value = this.groupData.name || '';
        nameInput.addEventListener('input', () => {
            this.groupData.name = nameInput.value.trim();
            this._syncGroupSaveDisabled();
        });
        nameRow.append(nameLabel, nameInput);
        grid.append(nameRow);

        // Action list — the SAME widget as if/loop branches, re-rendering THIS form.
        const catalog = this.actionCatalog();
        if (!Array.isArray(this.groupData.actions)) this.groupData.actions = [];
        grid.append(this.createNestedActions(catalog, this.groupData.actions,
            'automation.group_actions', () => this.renderGroupForm()));

        // Re-validate Save on ANY change inside the group grid. The action-type <select>
        // fires 'change' but only calls its own rebuild() (no re-render), and the global
        // document 'change' listener drives _syncSaveDisabled (the AUTOMATION button), not
        // this one — so without this grid-scoped listener the group Save button would stay
        // stuck disabled after picking an action type (the user could only un-stick it by
        // editing the name). Bound ONCE per grid node (renderGroupForm re-runs on every
        // add/delete but reuses the same #groupFormGrid element, so guard against stacking
        // duplicate listeners).
        if (!grid.dataset.syncBound) {
            grid.addEventListener('change', () => this._syncGroupSaveDisabled());
            grid.dataset.syncBound = '1';
        }

        this._syncGroupSaveDisabled();
    },

    // Disable Save until the group has a name AND at least one action with a chosen type
    // (an empty/typeless action is rejected by the daemon and would fail the save).
    _syncGroupSaveDisabled() {
        const btn = document.getElementById('groupSaveBtn');
        if (!btn || !this.groupData) return;
        const hasName = !!(this.groupData.name && this.groupData.name.trim());
        const acts = this.groupData.actions || [];
        const hasAction = acts.length > 0 && acts.every(a => a && a.type);
        const invalid = document.querySelectorAll('#groupFormGrid .invalid').length > 0;
        // Same cross-field manualClip window rule as the automation form (daemon 1..60s).
        const badClip = this._hasInvalidManualClip([acts]);
        btn.disabled = !(hasName && hasAction && !invalid && !badClip);
    },

    async saveGroup() {
        if (!this.groupData) return;
        if (!this.groupData.name || !this.groupData.name.trim()) {
            return this.toast(BYD.i18n.t('automation.group_needs_name'), 'error');
        }
        const acts = this.groupData.actions || [];
        if (!acts.length || !acts.every(a => a && a.type)) {
            return this.toast(BYD.i18n.t('automation.group_needs_action'), 'error');
        }
        if (this._hasInvalidManualClip([acts])) {
            return this.toast(BYD.i18n.t('automation.clip_window_invalid'), 'error');
        }
        // Declared outside the try so the catch/fallthrough toast below can still read it.
        let serverError = '';
        try {
            const id = this.groupData.id;
            const path = id ? '/api/action-groups/' + encodeURIComponent(id) : '/api/action-groups';
            const resp = await fetch(path, {
                method: id ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: this.groupData.name.trim(), actions: acts })
            });
            const result = await resp.json();
            if (result && result.success) {
                await this.loadGroups();
                this.hideGroupForm();
                return this.toast(BYD.i18n.t('toast.saved'), 'success');
            }
            // Surface the daemon's own reason when it sent one — a persistence failure explains
            // that the group is live NOW but would be lost on restart, which the generic
            // "save failed" hides (the import path already does this).
            if (result && result.error) serverError = result.error;
        } catch (e) {}
        this.toast(serverError || BYD.i18n.t('errors.save_failed'), 'error');
    },

    async deleteGroup(id, name) {
        const ok = (window.BYD && BYD.utils && BYD.utils.confirmDialog)
            ? await BYD.utils.confirmDialog({
                title: BYD.i18n.t('confirm.delete'),
                body: BYD.i18n.t('automation.confirm_delete_group_body').replace('{name}', name || ''),
                confirmLabel: BYD.i18n.t('common.delete'),
                cancelLabel: BYD.i18n.t('common.cancel'),
                danger: true
              })
            : confirm(BYD.i18n.t('confirm.delete'));
        if (!ok) return;
        let serverError = '';
        try {
            const resp = await fetch('/api/action-groups/' + encodeURIComponent(id), { method: 'DELETE' });
            const result = await resp.json();
            if (result && result.success) {
                await this.loadGroups();
                return this.toast(BYD.i18n.t('toast.deleted'), 'success');
            }
            if (result && result.error) serverError = result.error;
        } catch (e) {}
        this.toast(serverError || BYD.i18n.t('errors.delete_failed'), 'error');
    },

    toast(message, type) {
        if (BYD.utils && BYD.utils.toast) {
            BYD.utils.toast(message, type === 'error' ? 'error' : 'success');
        } else {
            console.log('[Automations] ' + type + ': ' + message);
        }
    }
};

window.AutomationSettings = BYD.automations;

// Keep the Save-disabled affordance in sync as the user edits fields. Field
// validity is toggled on 'change'; re-check after it bubbles to the grid.
document.addEventListener('change', function (e) {
    if (e.target && e.target.closest && e.target.closest('.form-grid')) {
        BYD.automations._syncSaveDisabled();
    }
});
