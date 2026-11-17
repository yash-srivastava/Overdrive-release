/* Widgets settings page: the glassWidgets UCM section (master opt-in, where to show, which
   widgets and their order, placement and look). The widgets themselves draw natively on screen. */
const WidgetsSettings = {
    SECTION: 'glassWidgets',
    DEFAULTS: {
        enabled: false,
        mode: 'home',
        widgets: ['battery', 'charging', 'trip', 'power'],
        position: 'bottom-left',
        offsetX: 24,
        offsetY: 24,
        scale: 1,
        theme: 'auto',
        tint: 0.82,
        reduceMotion: false,
        motors: 'auto',
        chargingOnlyWhenActive: true,
        showBlindSpot: true,
        floatingShortcuts: false,
        terrain: true,
    },
    CATALOG: [
        { id: 'battery', key: 'widgets.w_battery', name: 'Battery & range', desc: 'widgets.w_battery_desc', descFb: "Glass battery cell with the charge and the range left: OverDrive's calibrated range once it has learned your trips. Plug-in hybrids get a second cell for fuel." },
        { id: 'charging', key: 'widgets.w_charging', name: 'Charging', desc: 'widgets.w_charging_desc', descFb: 'Time to full, the charge limit and light flowing in.' },
        { id: 'trip', key: 'widgets.w_trip', name: 'Live trip', desc: 'widgets.w_trip_desc', descFb: "This trip's distance, time and efficiency, with average and top speed, the energy used and the battery from start to now." },
        { id: 'drive', key: 'widgets.w_drive', name: 'Live drive', desc: 'widgets.w_drive_desc', descFb: "The last five minutes of power, regen and speed as a glowing ribbon, with this trip's distance, time, efficiency and top speed." },
        { id: 'dynamics', key: 'widgets.w_dynamics', name: 'Drive dynamics', desc: 'widgets.w_dynamics_desc', descFb: 'Speed inside a ring that swings with the power drawn and the regen, the motor rpm, the accelerator and brake pedals and the g-force, live while you drive.' },
        { id: 'roadsense', key: 'widgets.w_roadsense', name: 'RoadSense', desc: 'widgets.w_roadsense_desc', descFb: 'Warning signs for speed breakers, potholes and rough road ahead.' },
        { id: 'power', key: 'widgets.w_power', name: 'Vehicle power & tyres', desc: 'widgets.w_power_desc', descFb: 'Power flowing to the motors and back on regen; the wheels show tyre pressure.' },
        { id: 'controls', key: 'widgets.w_controls', name: 'Vehicle controls', desc: 'widgets.w_controls_desc', descFb: 'Lock, climate, boot, lights and all four windows. Works in Park only.' },
        { id: 'shortcuts', key: 'widgets.w_shortcuts', name: 'Automation shortcuts', desc: 'widgets.w_shortcuts_desc', descFb: 'Three keys that each run an automation with one tap. Hold a key to choose or remove its automation.' },
        { id: 'seats', key: 'widgets.w_seats', name: 'Seat positions', desc: 'widgets.w_seats_desc', descFb: 'Your saved seat positions. Tap one to move the seat; works in Park only.' },
    ],

    config: null,
    _hydrated: false,
    _writeChain: Promise.resolve(),
    _saveTimer: 0,
    _inFlight: 0,
    _syncAfterSave: false,
    // bumped by every edit, so a reload that started before one never overwrites it
    _edits: 0,
    // the keys edited since the last load: a save writes only these, so it can't put back a list
    // or size the head unit's arrange mode saved meanwhile
    _dirty: null,
    // the list edits behind a dirty `widgets`, replayed onto the list saved by then (arrange mode can add
    // and take off widgets while this page is open)
    _listOps: null,
    _arrangeWatch: 0,
    // bumped whenever a watch starts or stops, so a poll that was already out can't act for a newer one
    _arrangeSeq: 0,

    _t(key, fallback) {
        const t = (window.BYD && BYD.i18n) ? BYD.i18n.t(key) : key;
        return (t && t !== key) ? t : fallback;
    },
    _toast(key, fallback, type) {
        if (BYD.utils && BYD.utils.toast) BYD.utils.toast(this._t(key, fallback), type);
    },

    async init() {
        this.renderUnit();
        this._dirty = new Set();
        this._listOps = [];
        this.config = Object.assign({}, this.DEFAULTS);
        // Saving writes the whole section, so the page never unlocks on defaults: a failed load would wipe it.
        let ok = await this.loadConfig();
        for (let i = 0; !ok && i < 3; i++) {
            await new Promise((resolve) => setTimeout(resolve, 1500));
            ok = await this.loadConfig();
        }
        if (!ok) {
            this._toast('widgets.load_failed', "Couldn't load widget settings. Reopen the page to try again.", 'error');
            return;
        }
        this.render();
        this._hydrated = true;
        if (BYD.utils && BYD.utils.unlockSettingsHydration) BYD.utils.unlockSettingsHydration();
        if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            BYD.i18n.onChange(() => { this.renderBadge(); this.renderList(); });
        }
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') this.renderUnit();
            // a reload while an edit is queued or saving would drop the edit or re-show stale values
            if (document.visibilityState !== 'visible' || this._saveTimer || this._inFlight) return;
            const seq = this._edits;
            const fresh = () => seq === this._edits && !this._saveTimer && !this._inFlight;
            this.loadConfig(fresh).then((loaded) => { if (loaded) this.render(); });
        });
    },

    // [stillWanted], if given, is asked just before the loaded values replace the page's
    async loadConfig(stillWanted) {
        try {
            const resp = await fetch('/api/settings/unified');
            const data = await resp.json();
            if (!data || !data.success || !data.config) return false;
            const saved = data.config[this.SECTION] || {};
            const c = Object.assign({}, this.DEFAULTS);
            for (const k in this.DEFAULTS) if (saved[k] !== undefined && saved[k] !== null) c[k] = saved[k];
            if (!Array.isArray(c.widgets)) c.widgets = this.DEFAULTS.widgets.slice();
            c.widgets = c.widgets.filter((id) => this.CATALOG.some((w) => w.id === id));
            if (stillWanted && !stillWanted()) return false;
            this.config = c;
            this._dirty.clear();
            this._listOps = [];
            this.arranged = this._hasLayout(saved);
            return true;
        } catch (e) {
            console.warn('widgets: load failed', e);
            return false;
        }
    },

    render() {
        const c = this.config;
        document.getElementById('gwEnabled').checked = !!c.enabled;
        this.renderBadge();
        document.querySelectorAll('input[name=gwMode]').forEach((r) => { r.checked = r.value === c.mode; });
        document.getElementById('gwPosition').value = c.position;
        document.getElementById('gwTheme').value = c.theme;
        document.getElementById('gwMotors').value = c.motors;
        document.getElementById('gwReduce').checked = !!c.reduceMotion;
        document.getElementById('gwChargingAuto').checked = !!c.chargingOnlyWhenActive;
        document.getElementById('gwBlindSpot').checked = !!c.showBlindSpot;
        document.getElementById('gwFloating').checked = !!c.floatingShortcuts;
        document.getElementById('gwTerrain').checked = !!c.terrain;
        this._setSlider('gwOffsetX', c.offsetX, c.offsetX + ' dp');
        this._setSlider('gwOffsetY', c.offsetY, c.offsetY + ' dp');
        this._setSlider('gwScale', Math.round(c.scale * 100), Math.round(c.scale * 100) + '%');
        this._setSlider('gwTint', Math.round(c.tint * 100), Math.round(c.tint * 100) + '%');
        this.renderArranged();
        this.renderList();
    },

    // the on-screen arrangement is the overlay's own; the page only shows whether there is one
    _hasLayout(saved) {
        return !!(saved.layout && typeof saved.layout === 'object' && Object.keys(saved.layout).length);
    },

    renderArranged() {
        document.getElementById('gwArrangedNote').style.display = this.arranged ? '' : 'none';
        document.getElementById('gwResetArrange').style.display = this.arranged ? '' : 'none';
    },

    // The widgets show tyre pressure in the app-wide unit picked under Notifications; this only displays it.
    async renderUnit() {
        const el = document.getElementById('gwUnitNow');
        try {
            const resp = await fetch('/api/tyres/limits');
            const data = await resp.json();
            const names = { kpa: 'kPa', psi: 'psi', bar: 'bar' };
            el.textContent = (data && names[data.pressureUnit]) || '';
        } catch (e) {
            el.textContent = '';
        }
    },

    // Arrange mode runs on the head unit's own screen, over this page.
    async arrange() {
        if (!this._hydrated) return;
        if (!this.config.enabled) { this._toast('widgets.arrange_off', 'Turn the widgets on first', 'error'); return; }
        // the head unit arranges what's saved: save the page as it is now, after anything already on its way
        // (an edit, so a reload that read the server before it can't put older values back)
        this._edits++;
        clearTimeout(this._saveTimer);
        this._saveTimer = 0;
        let needSync = this._syncAfterSave;
        this._syncAfterSave = false;
        if (!(await this.save())) {
            if (needSync) this._syncAfterSave = true;
            this._toast('widgets.save_failed', 'Save failed', 'error');
            return;
        }
        // a save that was already out may have failed meanwhile and handed its sync on; one queued since
        // keeps its own, to send after it saves
        if (!this._saveTimer) { needSync = needSync || this._syncAfterSave; this._syncAfterSave = false; }
        // a switch save that failed while this waited has rolled the page back to off
        if (!this.config.enabled) { this._toast('widgets.arrange_off', 'Turn the widgets on first', 'error'); return; }
        if (needSync) {
            try { await fetch('/api/widgets/sync', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }); } catch (e) { /* arrange starts the overlay anyway */ }
        }
        try {
            const resp = await fetch('/api/widgets/arrange', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
            const data = await resp.json();
            if (resp.ok && data && data.success) { this._watchArranged(); return; }
            if (resp.ok && data && data.enabled === false) this._toast('widgets.arrange_off', 'Turn the widgets on first', 'error');
            else this._toast('widgets.arrange_failed', "Couldn't open arrange mode", 'error');
        } catch (e) {
            this._toast('widgets.arrange_failed', "Couldn't open arrange mode", 'error');
        }
    },

    // The overlay saves the arrangement when Done is tapped on the head unit; follow it for a few minutes.
    _watchArranged() {
        this._stopWatch();
        const seq = ++this._arrangeSeq;
        // the first answer is the baseline: the page's own idea may be stale (arranged by a long press)
        let start = null;
        let left = 60;
        const id = setInterval(async () => {
            if (seq !== this._arrangeSeq || --left < 0) { clearInterval(id); return; }
            // an edit made while this read was out is newer than what it brings back
            const edits = this._edits;
            try {
                const resp = await fetch('/api/settings/unified');
                const data = await resp.json();
                if (seq !== this._arrangeSeq) { clearInterval(id); return; }
                if (!data || !data.success || !data.config) return;
                const saved = data.config[this.SECTION] || {};
                // arrange mode can add and take off widgets: show its list once saved, unless one is being edited here
                if (Array.isArray(saved.widgets) && edits === this._edits && !this._dirty.has('widgets') && !this._saveTimer && !this._inFlight) {
                    const list = saved.widgets.filter((id) => this.CATALOG.some((w) => w.id === id));
                    if (JSON.stringify(list) !== JSON.stringify(this.config.widgets)) { this.config.widgets = list; this.renderList(); }
                }
                const now = this._hasLayout(saved);
                if (now !== this.arranged) { this.arranged = now; this.renderArranged(); }
                if (start === null) start = now;
                else if (now !== start) this._stopWatch();
            } catch (e) { /* keep watching */ }
        }, 3000);
        this._arrangeWatch = id;
    },

    _stopWatch() {
        clearInterval(this._arrangeWatch);
        this._arrangeWatch = 0;
        this._arrangeSeq++;
    },

    // Back to the row or column the placement settings describe.
    async resetArrangement() {
        if (!this._hydrated) return;
        // an edit in flight: a reload that read the server before it lands can't bring the note back
        this._edits++;
        this._inFlight++;
        // an Arrange tapped just before this, whose watch starts while this is out, keeps that watch
        const seq = this._arrangeSeq;
        try {
            // in the same chain as the saves, so Arrange waits for it and its outcome
            const run = () => this._post({ layout: {} });
            const p = this._writeChain.then(run, run);
            this._writeChain = p.catch(() => false);
            if (!(await p)) throw new Error('save');
            if (this._arrangeSeq === seq) this._stopWatch();
            this.arranged = false;
            this.renderArranged();
            this._toast('widgets.arrange_reset_done', 'Widgets back in their row', 'success');
        } catch (e) {
            this._toast('widgets.save_failed', 'Save failed', 'error');
        } finally {
            this._inFlight--;
        }
    },

    renderBadge() {
        const on = !!this.config.enabled;
        const badge = document.getElementById('gwStatusBadge');
        badge.textContent = this._t(on ? 'status.on' : 'status.off', on ? 'ON' : 'OFF');
        badge.classList.toggle('active', on);
        badge.classList.toggle('inactive', !on);
    },

    _setSlider(id, v, label) {
        document.getElementById(id).value = v;
        document.getElementById(id + 'Value').textContent = label;
    },

    // Selected widgets first, in the user's order, then the rest in catalog order.
    renderList() {
        const list = document.getElementById('gwList');
        const sel = this.config.widgets;
        const ordered = sel.map((id) => this.CATALOG.find((w) => w.id === id)).filter(Boolean)
            .concat(this.CATALOG.filter((w) => sel.indexOf(w.id) < 0));
        list.innerHTML = '';
        ordered.forEach((w) => {
            const on = sel.indexOf(w.id) >= 0;
            const idx = sel.indexOf(w.id);
            const row = document.createElement('div');
            row.className = 'gw-item';
            row.innerHTML =
                '<label class="toggle-switch"><input type="checkbox"' + (on ? ' checked' : '') + '><span class="toggle-slider"></span></label>' +
                '<div class="setting-info"><div class="setting-name"></div><div class="setting-desc"></div></div>' +
                '<div class="gw-order"><button type="button" aria-label="Move up">&#8593;</button><button type="button" aria-label="Move down">&#8595;</button></div>';
            row.querySelector('.setting-name').textContent = this._t(w.key, w.name);
            row.querySelector('.setting-desc').textContent = this._t(w.desc, w.descFb);
            row.querySelector('input').addEventListener('change', (e) => this.toggleWidget(w.id, e.target.checked));
            const [up, down] = row.querySelectorAll('.gw-order button');
            up.disabled = !on || idx === 0;
            down.disabled = !on || idx === sel.length - 1;
            up.addEventListener('click', () => this.move(w.id, -1));
            down.addEventListener('click', () => this.move(w.id, 1));
            list.appendChild(row);
        });
    },

    toggleWidget(id, on) {
        if (!this._hydrated) return;
        const sel = this.config.widgets.filter((x) => x !== id);
        if (on) sel.push(id);
        this.config.widgets = sel;
        this._dirty.add('widgets');
        this._listOps.push({ op: on ? 'add' : 'remove', id: id });
        this.renderList();
        // the daemon polls the blind-spot radar only while the power widget is listed
        this.queueSave(true);
    },

    move(id, dir) {
        if (!this._hydrated) return;
        const sel = this.config.widgets.slice();
        const i = sel.indexOf(id), j = i + dir;
        if (i < 0 || j < 0 || j >= sel.length) return;
        sel[i] = sel[j];
        sel[j] = id;
        this.config.widgets = sel;
        this._dirty.add('widgets');
        this._listOps.push({ op: 'move', id: id, dir: dir });
        this.renderList();
        this.queueSave();
    },

    set(key, value) {
        if (!this._hydrated) return;
        this.config[key] = value;
        this._dirty.add(key);
        this.queueSave(key === 'showBlindSpot');
    },

    slide(key, value) {
        if (!this._hydrated) return;
        this.config[key] = value;
        this._dirty.add(key);
        const labels = { offsetX: value + ' dp', offsetY: value + ' dp', scale: Math.round(value * 100) + '%', tint: Math.round(value * 100) + '%' };
        const ids = { offsetX: 'gwOffsetXValue', offsetY: 'gwOffsetYValue', scale: 'gwScaleValue', tint: 'gwTintValue' };
        document.getElementById(ids[key]).textContent = labels[key];
        this.queueSave();
    },

    async setEnabled(on) {
        if (!this._hydrated) return;
        const box = document.getElementById('gwEnabled');
        this._edits++;
        const prev = this.config.enabled;
        this.config.enabled = on;
        this._dirty.add('enabled');
        clearTimeout(this._saveTimer);
        this._saveTimer = 0;
        // one switch save at a time, so a failed one rolls back to what the server really has
        box.disabled = true;
        const ok = await this.save();
        box.disabled = false;
        if (!ok) {
            this.config.enabled = prev;
            box.checked = prev;
            this._dirty.add('enabled');
            // it may have landed after all (a late answer), a save queued behind it carries the new value,
            // and an edit that rode along wasn't saved: write the page as it now is after them
            this.queueSave(true);
            this._toast('widgets.save_failed', 'Save failed', 'error');
            return;
        }
        this.render();
        // The daemon starts or stops the overlay to match; it re-reads the saved flag itself.
        try { await fetch('/api/widgets/sync', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }); } catch (e) { /* the overlay also polls */ }
        this._toast(on ? 'widgets.enabled_toast' : 'widgets.disabled_toast', on ? 'Widgets on' : 'Widgets off', 'success');
    },

    queueSave(sync) {
        this._edits++;
        if (sync) this._syncAfterSave = true;
        clearTimeout(this._saveTimer);
        this._saveTimer = setTimeout(() => this._runQueuedSave(), 350);
    },

    _runQueuedSave() {
        this._saveTimer = 0;
        const needSync = this._syncAfterSave;
        this._syncAfterSave = false;
        return this.save().then((ok) => {
            // the next save that lands still owes the overlay its sync
            if (!ok) { if (needSync) this._syncAfterSave = true; this._toast('widgets.save_failed', 'Save failed', 'error'); return false; }
            if (needSync && this.config.enabled) {
                fetch('/api/widgets/sync', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }).catch(() => { /* next toggle syncs */ });
            }
            return true;
        });
    },

    // One write of the section; true when core stored it. A write that never answers gives up after
    // 15 s, so it can't hold the switch, Arrange or later saves for ever (no timeout in an old WebView).
    async _post(data) {
        const ctl = typeof AbortController === 'function' ? new AbortController() : null;
        let timer = 0;
        // the race gives up even where the request itself can't be cancelled
        const late = new Promise((resolve, reject) => { timer = setTimeout(() => { if (ctl) ctl.abort(); reject(new Error('timeout')); }, 15000); });
        try {
            const resp = await Promise.race([fetch('/api/settings/unified', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ section: this.SECTION, data: data }),
                signal: ctl ? ctl.signal : undefined,
            }), late]);
            const json = await Promise.race([resp.json(), late]);
            return !!(resp.ok && json && json.success !== false);
        } catch (e) {
            console.warn('widgets: save failed', e);
            return false;
        } finally {
            clearTimeout(timer);
        }
    },

    // The page's list edits replayed onto the list saved now; null when it can't be read.
    async _rebaseList(ops) {
        try {
            const resp = await fetch('/api/settings/unified');
            const data = await resp.json();
            if (!data || !data.success || !data.config) return null;
            const saved = data.config[this.SECTION] || {};
            const list = (Array.isArray(saved.widgets) ? saved.widgets : this.DEFAULTS.widgets)
                .filter((id) => this.CATALOG.some((w) => w.id === id));
            ops.forEach((o) => {
                const i = list.indexOf(o.id);
                if (o.op === 'add') { if (i < 0) list.push(o.id); }
                else if (o.op === 'remove') { if (i >= 0) list.splice(i, 1); }
                else {
                    const j = i + o.dir;
                    if (i >= 0 && j >= 0 && j < list.length) { list[i] = list[j]; list[j] = o.id; }
                }
            });
            return list;
        } catch (e) {
            return null;
        }
    },

    // Writes are chained so a slower earlier save can never land after a newer one. Each takes the keys
    // edited by the time it runs, so one queued behind a failed save carries that save's keys again;
    // core keeps the keys that aren't sent.
    save() {
        const run = async () => {
            const keys = Array.from(this._dirty);
            this._dirty.clear();
            const ops = this._listOps;
            this._listOps = [];
            if (!keys.length) return true;
            const data = {};
            keys.forEach((k) => { data[k] = JSON.parse(JSON.stringify(this.config[k])); });
            const rebased = keys.indexOf('widgets') >= 0 && ops.length ? await this._rebaseList(ops) : null;
            if (rebased) data.widgets = rebased;
            const ok = await this._post(data);
            if (!ok) {
                keys.forEach((k) => this._dirty.add(k));
                this._listOps = ops.concat(this._listOps);
            } else if (rebased && !this._dirty.has('widgets') && JSON.stringify(rebased) !== JSON.stringify(this.config.widgets)) {
                // the list now holds what arrange mode added too
                this.config.widgets = rebased;
                this.renderList();
            }
            return ok;
        };
        this._inFlight++;
        const p = this._writeChain.then(run, run);
        this._writeChain = p.catch(() => false);
        p.then(() => { this._inFlight--; }, () => { this._inFlight--; });
        return p;
    },
};
