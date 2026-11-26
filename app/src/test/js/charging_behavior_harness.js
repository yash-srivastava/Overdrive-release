'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const chargingSource = fs.readFileSync(
    path.resolve(__dirname,
        '../../main/assets/web/shared/charging.js'),
    'utf8');

function fakeElement(id) {
    const classes = new Set();
    return {
        id: id,
        value: '',
        checked: false,
        disabled: false,
        textContent: '',
        innerHTML: '',
        style: {},
        dataset: {},
        children: [],
        classList: {
            add: function (name) { classes.add(name); },
            remove: function (name) { classes.delete(name); },
            contains: function (name) { return classes.has(name); }
        },
        addEventListener: function () {},
        setAttribute: function () {},
        querySelector: function () { return null; },
        appendChild: function (child) { this.children.push(child); },
        getContext: function () { return null; },
        getBoundingClientRect: function () {
            return { left: 0, top: 0, width: 320, height: 180 };
        }
    };
}

function createEnvironment() {
    const elements = new Map();
    const requests = [];
    const document = {
        visibilityState: 'visible',
        documentElement: fakeElement('documentElement'),
        getElementById: function (id) {
            if (!elements.has(id)) elements.set(id, fakeElement(id));
            return elements.get(id);
        },
        querySelectorAll: function () { return []; },
        addEventListener: function () {},
        createElement: function (tag) { return fakeElement(tag); }
    };
    const window = {
        BYD: null,
        devicePixelRatio: 1,
        confirm: function () { return true; },
        open: function () {}
    };
    const context = {
        console: console,
        document: document,
        window: window,
        fetch: function (url, options) {
            let resolve;
            const promise = new Promise(function (done) {
                resolve = done;
            });
            requests.push({
                url: url,
                options: options || {},
                resolve: resolve
            });
            return promise;
        },
        getComputedStyle: function () {
            return { getPropertyValue: function () { return ''; } };
        },
        MutationObserver: function () {
            this.observe = function () {};
        },
        setTimeout: setTimeout,
        clearTimeout: clearTimeout,
        Promise: Promise,
        Map: Map,
        Set: Set
    };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.resolve(__dirname,
        '../../main/assets/web/shared/range-calendar.js'), 'utf8'), context,
        { filename: 'range-calendar.js' });
    vm.runInContext(
        chargingSource + '\nthis.CHARGING = CHARGING;',
        context,
        { filename: 'charging.js' });
    return {
        charging: context.CHARGING,
        elements: elements,
        document: document,
        requests: requests
    };
}

function respond(request, body, ok, status) {
    request.resolve({
        ok: ok !== false,
        status: status || (ok === false ? 500 : 200),
        json: function () { return Promise.resolve(body); }
    });
}

async function flush() {
    await new Promise(function (resolve) { setImmediate(resolve); });
    await new Promise(function (resolve) { setImmediate(resolve); });
}

function plain(value) {
    return JSON.parse(JSON.stringify(value));
}

const tests = [];
function test(name, body) {
    tests.push({ name: name, body: body });
}

test('detail responses cannot replace a newer visible session', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging.sessions = [];
    charging._renderDetailCharts = function (samples) {
        this.renderedSamples = samples;
    };
    charging._fillDetailHeader = function (session, id) {
        if (String(this.currentSessionId) !== String(id)) return;
        this._detailSessionId = id;
        this._detailInProgress = session.inProgress === true;
        this.visibleSession = session.id;
    };

    charging.showDetail(1);
    charging.showDetail(2);
    assert.strictEqual(env.requests.length, 4);

    respond(env.requests[2],
        { success: true, session: { id: 2, inProgress: false } });
    respond(env.requests[3],
        { success: true, samples: [{ t: 2 }] });
    await flush();
    respond(env.requests[0],
        { success: true, session: { id: 1, inProgress: false } });
    respond(env.requests[1],
        { success: true, samples: [{ t: 1 }] });
    await flush();

    assert.strictEqual(charging.visibleSession, 2);
    assert.strictEqual(charging.renderedSamples[0].t, 2);
    let deleted = null;
    charging.deleteSession = function (id) { deleted = id; };
    charging.deleteCurrent();
    assert.strictEqual(deleted, 2);
});

test('summary responses are period and generation guarded', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    const applied = [];
    charging._applySummary = function (summary, period) {
        applied.push({ summary: summary, period: period });
    };

    charging.currentDays = 30;
    charging.loadSummary();
    charging.currentDays = 7;
    charging.loadSummary();
    respond(env.requests[1],
        { success: true, summary: { marker: 'new' } });
    await flush();
    respond(env.requests[0],
        { success: true, summary: { marker: 'old' } });
    await flush();

    assert.deepStrictEqual(plain(applied), [{
        summary: { marker: 'new' },
        period: 'days=7'
    }]);
});

test('soc responses are hours and generation guarded', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    const applied = [];
    charging._applySoc = function (soc, hours) {
        applied.push({ soc: soc, hours: hours });
    };

    charging.socHours = 720;
    charging.loadSoc();
    charging.socHours = 24;
    charging.loadSoc();
    respond(env.requests[1],
        { success: true, soc: [{ marker: 'new' }] });
    await flush();
    respond(env.requests[0],
        { success: true, soc: [{ marker: 'old' }] });
    await flush();

    assert.deepStrictEqual(plain(applied), [{
        soc: [{ marker: 'new' }],
        hours: 24
    }]);
});

test('session refresh and load-more cannot cross periods', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    const applied = [];
    charging._applySessions = function (sessions, offset) {
        applied.push({
            marker: sessions[0] ? sessions[0].marker : '',
            offset: offset
        });
        this.currentOffset = offset;
        this.sessions = offset === 0
            ? sessions : this.sessions.concat(sessions);
    };

    charging.currentDays = 30;
    charging.loadSessions(0);
    charging.currentDays = 7;
    charging.loadSessions(0);
    respond(env.requests[1],
        { success: true, sessions: [{ marker: 'seven' }] });
    await flush();

    charging.loadMore();
    const oldLoadMore = env.requests[2];
    charging.currentDays = 30;
    charging.loadSessions(0);
    const newRefresh = env.requests[3];
    respond(newRefresh,
        { success: true, sessions: [{ marker: 'thirty' }] });
    await flush();
    respond(oldLoadMore,
        { success: true, sessions: [{ marker: 'stale-more' }] });
    respond(env.requests[0],
        { success: true, sessions: [{ marker: 'stale-first' }] });
    await flush();

    assert.deepStrictEqual(plain(applied), [
        { marker: 'seven', offset: 0 },
        { marker: 'thirty', offset: 0 }
    ]);
});

test('config refresh preserves edits and save sends only dirty fields', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    env.document.getElementById('chargingEnabled').checked = true;
    env.document.getElementById('currencySelect').value = '$';
    charging._configBaseline = charging._readConfigForm();
    charging._renderTariffFallbackNote = function () {};
    charging._applyConfig({
        enabled: false,
        electricityRate: 1,
        dcRate: 0,
        currency: '$'
    });

    env.document.getElementById('chargingEnabled').checked = true;
    charging.showApplyNeeded();
    charging._applyConfig({
        enabled: false,
        electricityRate: 2,
        dcRate: 0,
        currency: '$'
    });
    assert.strictEqual(
        env.document.getElementById('chargingEnabled').checked, true);
    assert.strictEqual(
        env.document.getElementById('rateInput').value, 2);
    assert.deepStrictEqual(
        plain(charging._dirtyConfigBody()),
        { enabled: true });

    charging.saveSettings();
    assert.deepStrictEqual(
        JSON.parse(env.requests[0].options.body),
        { enabled: true });
    respond(env.requests[0], { success: true });
    await flush();
    assert.strictEqual(charging._configDirty.enabled, undefined);
});

test('rejected config save keeps values dirty and retryable', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    env.document.getElementById('currencySelect').value = '$';
    charging._configBaseline = charging._readConfigForm();
    charging._renderTariffFallbackNote = function () {};
    charging._applyConfig({
        enabled: false,
        electricityRate: 1,
        dcRate: 0,
        currency: '$'
    });
    env.document.getElementById('rateInput').value = '4.5';
    charging.showApplyNeeded();

    charging.saveSettings();
    respond(env.requests[0],
        { success: false, error: 'invalid' }, false, 400);
    await flush();

    assert.strictEqual(
        env.document.getElementById('rateInput').value, '4.5');
    assert.strictEqual(charging._configDirty.electricityRate, true);
    assert.strictEqual(
        env.document.getElementById('chargingApplyBtn').disabled, false);
    assert.strictEqual(env.requests.length, 1);
});

test('older config response cannot overwrite a newer one', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    env.document.getElementById('currencySelect').value = '$';
    charging._configBaseline = charging._readConfigForm();
    charging._renderTariffFallbackNote = function () {};

    charging.loadConfig();
    charging.loadConfig();
    respond(env.requests[1], {
        success: true,
        config: {
            enabled: true,
            electricityRate: 9,
            dcRate: 0,
            currency: '$'
        }
    });
    await flush();
    respond(env.requests[0], {
        success: true,
        config: {
            enabled: false,
            electricityRate: 1,
            dcRate: 0,
            currency: '$'
        }
    });
    await flush();

    assert.strictEqual(charging.electricityRate, 9);
    assert.strictEqual(
        env.document.getElementById('chargingEnabled').checked, true);
});

test('unlisted durable currency is not invented as a dirty edit', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging.currency = 'EUR';
    env.document.getElementById('currencySelect').value = '';
    charging._configBaseline = {
        enabled: false,
        electricityRate: 0,
        dcRate: 0,
        currency: 'EUR'
    };

    charging._refreshConfigDirty();

    assert.strictEqual(charging._configDirty.currency, undefined);
    assert.strictEqual(charging._readConfigForm().currency, 'EUR');
});

test('failed bootstrap live section retries one coherent overview and preserves rows', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging.sessions = [{ id: 99 }];
    charging._applyConfig = function () {};
    charging._applySummary = function () {};
    charging._applySoc = function () {};
    charging._applyTariffs = function () {};
    env.document.getElementById('sessionListSkeleton').style.display = '';

    charging.bootstrap();
    assert.ok(env.requests[0].url.indexOf('days=7') >= 0);
    assert.ok(env.requests[0].url.indexOf('hours=168') >= 0);
    respond(env.requests[0], {
        success: true,
        bootstrap: {
            config: { config: {} },
            summary: { summary: {} },
            soc: { soc: [] },
            sessions: { error: 'database unavailable' },
            tariffs: { tariffs: [] }
        }
    });
    await flush();

    assert.strictEqual(
        env.document.getElementById('sessionListSkeleton').style.display,
        'none');
    assert.strictEqual(env.requests.length, 2);
    assert.ok(env.requests[1].url.indexOf(
        '/api/charging/overview?days=7') === 0);
    assert.strictEqual(charging.sessions[0].id, 99);
    respond(env.requests[1],
        { success: false, error: 'still unavailable' }, false, 500);
    await flush();
    assert.strictEqual(charging.sessions[0].id, 99);
});

test('failed current live pair clears live fields and later success recovers', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging._renderSummaryCharts = function () {};
    charging._renderSessionCards = function () {};
    charging.summaryCache = {
        periodEnergyKwh: 12.5,
        periodCost: 4.5,
        periodSessions: 2,
        live: {
            charging: true,
            powerKw: 3.2,
            sessionKwh: 1.4,
            timeToFullMin: 40
        }
    };
    charging._summaryPeriodKey = 'days=7';
    charging.sessions = [{
        id: 1,
        inProgress: true,
        chargingNow: true,
        livePowerKw: 3.2,
        timeToFullMin: 40
    }, {
        id: 2,
        inProgress: false,
        energyAdded: 8.0
    }];

    const failed = charging._loadCurrentLivePair();
    respond(env.requests[0],
        { success: false, error: 'overview unavailable' }, false, 500);
    assert.strictEqual(await failed, false);
    assert.strictEqual(charging.summaryCache.live.charging, false);
    assert.strictEqual(charging.sessions[0].chargingNow, false);

    assert.strictEqual(charging.summaryCache.periodEnergyKwh, 12.5);
    assert.strictEqual(charging.summaryCache.periodCost, 4.5);
    assert.strictEqual(charging.summaryCache.live.charging, false);
    assert.strictEqual(charging.summaryCache.live.powerKw, 0);
    assert.strictEqual(charging.summaryCache.live.sessionKwh, null);
    assert.strictEqual(
        charging.summaryCache.live.sessionEnergyEstimated,
        false);
    assert.strictEqual(
        charging.summaryCache.live.sessionEnergyIncomplete,
        false);
    assert.strictEqual(charging.sessions.length, 2);
    assert.strictEqual(charging.sessions[0].chargingNow, false);
    assert.strictEqual(charging.sessions[0].livePowerKw, null);
    assert.strictEqual(charging.sessions[0].timeToFullMin, null);
    assert.strictEqual(charging.sessions[1].energyAdded, 8.0);

    const recovered = charging._loadCurrentLivePair();
    respond(env.requests[1], {
        success: true,
        summary: {
            periodEnergyKwh: 12.5,
            periodCost: 4.5,
            periodSessions: 2,
            live: {
                charging: true,
                powerKw: 3.0,
                sessionKwh: 1.6,
                timeToFullMin: 30
            }
        },
        sessions: [{
            id: 1,
            inProgress: true,
            chargingNow: true,
            livePowerKw: 3.0,
            timeToFullMin: 30
        }, {
            id: 2,
            inProgress: false,
            energyAdded: 8.0
        }]
    });
    assert.strictEqual(await recovered, true);
    assert.strictEqual(charging.summaryCache.live.charging, true);
    assert.strictEqual(charging.summaryCache.live.powerKw, 3.0);
    assert.strictEqual(charging.sessions[0].chargingNow, true);
    assert.strictEqual(charging.sessions[0].livePowerKw, 3.0);
});

test('stale failed live pair cannot clear a newer successful pair', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging._renderSummaryCharts = function () {};
    charging._renderSessionCards = function () {};

    const oldPair = charging._loadCurrentLivePair();
    const newPair = charging._loadCurrentLivePair();
    respond(env.requests[1], {
        success: true,
        summary: {
            periodEnergyKwh: 5,
            live: { charging: true, powerKw: 2.9 }
        },
        sessions: [{
            id: 7,
            inProgress: true,
            chargingNow: true
        }]
    });
    assert.strictEqual(await newPair, true);

    respond(env.requests[0],
        { success: false, error: 'old overview failure' }, false, 500);
    assert.strictEqual(await oldPair, null);

    assert.strictEqual(charging.summaryCache.live.charging, true);
    assert.strictEqual(charging.sessions[0].id, 7);
    assert.strictEqual(charging.sessions[0].chargingNow, true);
});

test('periodic refresh is single-flight', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging._applySummary = function () {};
    charging._applySessions = function () {};

    const generation = charging._liveRefreshGeneration;
    const first = charging._runPeriodicRefresh(generation);
    const second = charging._runPeriodicRefresh(generation);
    assert.strictEqual(first, second);
    assert.strictEqual(env.requests.length, 1);

    respond(env.requests[0], {
        success: true,
        summary: { live: { charging: false } },
        sessions: []
    });
    await flush();
    charging._stopVisibleRefresh();
});

test('older refresh cannot resurrect charging after a newer stopped response', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    const applied = [];
    charging._applySummary = function (summary) {
        applied.push(summary.live.charging);
    };
    charging._applySessions = function () {};

    const oldGeneration = charging._liveRefreshGeneration;
    charging._runPeriodicRefresh(oldGeneration);
    charging._stopVisibleRefresh();
    const newGeneration = charging._liveRefreshGeneration;
    charging._runPeriodicRefresh(newGeneration);

    respond(env.requests[1], {
        success: true,
        summary: { live: { charging: false } },
        sessions: []
    });
    await flush();
    respond(env.requests[0], {
        success: true,
        summary: { live: { charging: true } },
        sessions: [{ id: 1, inProgress: true }]
    });
    await flush();

    assert.deepStrictEqual(applied, [false]);
    charging._stopVisibleRefresh();
});

test('bootstrap tariffs cannot overwrite a newer direct tariff response', async function () {
    const env = createEnvironment();
    const charging = env.charging;
    const applied = [];
    charging._applyConfig = function () {};
    charging._applySummary = function () {};
    charging._applySoc = function () {};
    charging._applySessions = function () {};
    charging._applyTariffs = function (payload) {
        applied.push(payload.tariffs[0].id);
        this.tariffs = payload.tariffs;
    };

    const bootstrap = charging.bootstrap();
    const direct = charging.loadTariffs();
    assert.strictEqual(env.requests.length, 2);

    respond(env.requests[1], {
        success: true,
        tariffs: [{ id: 'new' }],
        meta: {}
    });
    assert.strictEqual(await direct, true);

    respond(env.requests[0], {
        success: true,
        bootstrap: {
            config: { config: {} },
            summary: { summary: { live: { charging: false } } },
            soc: { soc: [] },
            sessions: { sessions: [] },
            tariffs: { tariffs: [{ id: 'old' }] }
        }
    });
    await bootstrap;

    assert.deepStrictEqual(applied, ['new']);
    assert.strictEqual(charging.tariffs[0].id, 'new');
});

test('stopped in-progress detail never displays stale time to full', function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging.currentSessionId = 7;
    const row = {
        id: 7,
        inProgress: true,
        chargingNow: false,
        startTime: 1000,
        timeToFullMin: 40
    };

    charging._fillDetailHeader(row, 7);
    assert.strictEqual(
        env.document.getElementById('detailTimeToFull').textContent,
        '--');

    row.chargingNow = true;
    charging._fillDetailHeader(row, 7);
    assert.strictEqual(
        env.document.getElementById('detailTimeToFull').textContent,
        '40 min');
});

test('successful stopped overview refreshes an already-open detail header', function () {
    const env = createEnvironment();
    const charging = env.charging;
    charging.currentSessionId = 7;
    charging.summaryCache = null;
    charging._renderSessionCards = function () {};
    const row = {
        id: 7,
        inProgress: true,
        chargingNow: true,
        startTime: 1000,
        timeToFullMin: 40
    };

    charging._applySessions([row], 0);
    assert.ok(
        env.document.getElementById('detailSubtitle').textContent
            .indexOf('Charging now') >= 0);
    assert.strictEqual(
        env.document.getElementById('detailTimeToFull').textContent,
        '40 min');

    row.chargingNow = false;
    row.timeToFullMin = null;
    charging._applySessions([row], 0);
    assert.strictEqual(
        env.document.getElementById('detailSubtitle').textContent
            .indexOf('Charging now'),
        -1);
    assert.strictEqual(
        env.document.getElementById('detailTimeToFull').textContent,
        '--');
});

test('durable pending repricing is warned and both views refresh', function () {
    const env = createEnvironment();
    const charging = env.charging;
    const toasts = [];
    let refreshes = 0;
    charging._toast = function (message, type) {
        toasts.push({ message: message, type: type });
    };
    charging.loadTariffs = function () { refreshes++; };
    charging._loadCurrentLivePair = function () { refreshes++; };

    charging._afterTariffChange({
        success: true,
        repriced: null,
        repricingStatus: 'pending',
        repricingConfirmed: false,
        repricingDurable: true
    }, null, true);

    assert.strictEqual(toasts.length, 1);
    assert.strictEqual(toasts[0].type, 'warning');
    assert.ok(toasts[0].message.indexOf('automatically') >= 0);
    assert.strictEqual(refreshes, 2);
});

test('undurable repricing partial commit is rendered as an error', function () {
    const env = createEnvironment();
    const charging = env.charging;
    const toasts = [];
    charging._toast = function (message, type) {
        toasts.push({ message: message, type: type });
    };
    charging.loadTariffs = function () {};
    charging._loadCurrentLivePair = function () {};

    charging._afterTariffChange({
        success: false,
        tariffSaved: true,
        repriced: null,
        repricingStatus: 'failed',
        error: 'Tariff was saved, but history repricing could not be queued'
    });

    assert.strictEqual(toasts[toasts.length - 1].type, 'error');
    assert.ok(toasts[toasts.length - 1].message.indexOf(
        'could not be queued') >= 0);
});

test('open historical row is not rendered as charging now when live verdict is false', function () {
    const env = createEnvironment();
    const charging = env.charging;
    const grid = env.document.getElementById('sessionList');
    charging.sessions = [{
        id: 1,
        inProgress: true,
        chargingNow: false,
        startTime: 1000,
        energyAdded: 1.2,
        peakPower: 3.1
    }];

    charging._renderSessionCards();
    assert.strictEqual(grid.children.length, 1);
    assert.ok(grid.children[0].innerHTML.indexOf('Charging now') < 0);
    assert.ok(grid.children[0].innerHTML.indexOf('session-delete-btn') < 0);

    grid.children = [];
    charging.sessions[0].chargingNow = true;
    charging._renderSessionCards();
    assert.ok(grid.children[0].innerHTML.indexOf('Charging now') >= 0);
});

test('active session power matches live dashboard power while history keeps peak', function () {
    const env = createEnvironment();
    const charging = env.charging;
    const grid = env.document.getElementById('sessionList');
    charging.sessions = [{
        id: 1,
        inProgress: true,
        chargingNow: true,
        startTime: 1000,
        livePowerKw: 6.3,
        peakPower: 0,
        isEstimated: true,
        energyAdded: 1.2,
        energyEstimated: true
    }];

    charging._renderSessionCards();
    assert.ok(grid.children[0].innerHTML.indexOf('≈6.3 kW') >= 0);
    assert.ok(grid.children[0].innerHTML.indexOf('Power &amp; energy estimated') >= 0);

    grid.children = [];
    charging.sessions[0].energyEstimated = false;
    charging.sessions[0].energyIncomplete = false;
    charging.sessions[0].energySource = 'metered_counter';
    charging._renderSessionCards();
    assert.ok(grid.children[0].innerHTML.indexOf('Power estimated') >= 0);

    charging.currentSessionId = 1;
    charging._fillDetailHeader({
        id: 1,
        inProgress: true,
        startTime: 1000,
        energyAdded: 1.2,
        energyEstimated: true
    }, 1);
    assert.strictEqual(
        env.document.getElementById('detailAvgPowerLabel').textContent,
        'Current power');
    assert.strictEqual(
        env.document.getElementById('detailAvgPower').textContent,
        '≈6.3 kW');
    assert.strictEqual(
        env.document.getElementById('detailPeakPower').textContent,
        'Not measured');
    assert.strictEqual(
        env.document.getElementById('detailEstimateDisclosure').style.display,
        '');

    grid.children = [];
    charging.sessions[0].inProgress = false;
    charging.sessions[0].chargingNow = false;
    charging.sessions[0].isEstimated = false;
    charging.sessions[0].energyEstimated = false;
    charging.sessions[0].energySource = 'metered_counter';
    charging.sessions[0].peakPower = 6.6;
    charging._renderSessionCards();
    assert.ok(grid.children[0].innerHTML.indexOf('6.6 kW') >= 0);
    assert.ok(grid.children[0].innerHTML.indexOf('Estimated') < 0);
});

test('charger tier honors an explicit backend verdict before power fallback', function () {
    const charging = createEnvironment().charging;

    assert.strictEqual(charging._typeKind({ isDc: false, peakPower: 359.4 }), 'fast');
    assert.strictEqual(charging._typeKind({ isDc: true, peakPower: 15 }), 'dc');
    assert.strictEqual(charging._typeKind({ isDc: null, peakPower: 25 }), 'dc');
    assert.strictEqual(charging._typeKind({ isDc: null, peakPower: 7.2 }), 'fast');
    assert.strictEqual(charging._typeKind({ isDc: null, peakPower: 6.1 }), 'slow');
    assert.strictEqual(charging._typeKind({
        gunState: 1, isDc: null, peakPower: 100
    }), 'unk');
    assert.strictEqual(charging._typeKind({
        gunState: 5, isDc: true, peakPower: 100
    }), 'unk');
});

test('estimated or incomplete energy is never rendered as exact', function () {
    const env = createEnvironment();
    const charging = env.charging;

    assert.strictEqual(charging._energyIsApproximate({
        energySource: 'metered_counter'
    }), false);
    assert.strictEqual(charging._energyIsApproximate({
        energySource: 'integrated_rate'
    }), true);
    assert.strictEqual(charging._energyIsApproximate({
        sessionEnergyIncomplete: true,
        sessionEnergySource: 'metered_counter'
    }), true);
    assert.strictEqual(charging._energyIsApproximate({
        sessionKwh: 2.0
    }), true);

    charging.renderCircleGauge = function () {};
    charging._renderSummaryCharts = function () {};
    charging._energyBars = function () { return []; };
    charging.sessions = [];
    charging._applySummary({
        live: {
            charging: true,
            sessionKwh: 1.6,
            sessionEnergyEstimated: true
        }
    }, 'days=7', true);
    assert.strictEqual(
        env.document.getElementById('kwhHeroValue').textContent,
        '--');
    assert.strictEqual(
        env.document.getElementById('statsEstimateDisclosure').style.display,
        '');
    assert.strictEqual(
        env.document.getElementById('summaryEstimateDisclosure').style.display,
        '');

    charging._applySummary({
        periodEnergyKwh: 5.0,
        periodCost: 2.5,
        periodRangeGained: 30,
        periodEstimatedSessions: 1,
        lifetimeEnergyKwh: 20,
        lifetimeCost: 10,
        lifetimeEstimatedSessions: 1,
        live: { charging: false }
    }, 'days=7', true);
    assert.strictEqual(
        env.document.getElementById('kwhHeroValue').textContent,
        '--');
    assert.strictEqual(
        env.document.getElementById('summaryEnergy').textContent,
        '~5.0 kWh');
    assert.strictEqual(
        env.document.getElementById('summaryRangeGained').textContent,
        '~30 km');
    assert.strictEqual(
        env.document.getElementById('lifetimeEnergy').textContent,
        '~20 kWh');

    const barCharging = createEnvironment().charging;
    const bars = plain(barCharging._energyBars([{
        startTime: 1,
        energyAdded: 3.6,
        energySource: 'integrated_rate',
        isDc: null,
        inProgress: false
    }, {
        startTime: 2,
        energyAdded: 1.2,
        energySource: 'metered_counter',
        isDc: false,
        peakPower: 7.4,
        inProgress: false
    }]));
    assert.strictEqual(bars[0].approximate, false);
    assert.strictEqual(bars[1].approximate, true);
    assert.strictEqual(bars[0].chargerKind, 'fast');
    assert.strictEqual(bars[1].chargerKind, 'unk');
});

test('legacy SOC keeps the endpoint that was actually recorded', function () {
    const charging = createEnvironment().charging;

    assert.strictEqual(
        charging._socRangeText({ startSoc: 32, endSoc: null }),
        '32% → --');
    assert.strictEqual(
        charging._socRangeText({ startSoc: null, endSoc: 81 }),
        '-- → 81%');
    assert.strictEqual(
        charging._socRangeText({ startSoc: 32.4, endSoc: 81.4 }),
        '32% → 81%');
    assert.strictEqual(
        charging._socRangeText({ startSoc: -1, endSoc: 255 }),
        '--');
});

test('recent SOC history fills live-card gaps without reviving stale SOC', function () {
    const charging = createEnvironment().charging;
    const now = Date.now();
    charging.socHistoryCache = [
        { t: now - 3 * 60 * 60 * 1000, soc: 42, range: 180, soh: 97 },
        { t: now - 10 * 60 * 1000, soc: 88, range: 260 }
    ];
    charging.summaryCache = {
        sohTrend: [{ soh: 96 }]
    };

    assert.deepStrictEqual(
        plain(charging._latestBatterySnapshot()),
        { soc: 88, range: 260, soh: 97 });

    charging.socHistoryCache = [
        { t: now - 3 * 60 * 60 * 1000, soc: 42, range: 180, soh: 97 }
    ];
    assert.deepStrictEqual(
        plain(charging._latestBatterySnapshot()),
        { soc: null, range: null, soh: 97 });
});

test('SOC arrival repaints the hero from the shared summary', function () {
    const env = createEnvironment();
    const charging = env.charging;
    let repaint = null;
    charging.summaryCache = { live: {} };
    charging._summaryPeriodKey = 'days=7';
    charging.renderSocOverTime = function () {};
    charging._applySummary = function (summary, period, force) {
        repaint = { summary: summary, period: period, force: force };
    };

    charging._applySoc([{ t: Date.now(), soc: 88, soh: 97 }], 168);

    assert.strictEqual(repaint.summary, charging.summaryCache);
    assert.strictEqual(repaint.period, 'days=7');
    assert.strictEqual(repaint.force, true);
});

test('average-power card never upgrades estimates to measured data', function () {
    const charging = createEnvironment().charging;
    charging.sessions = [{
        inProgress: false,
        energyAdded: 7,
        durationMinutes: 60,
        energySource: 'metered_counter'
    }, {
        inProgress: false,
        energyAdded: 4,
        durationMinutes: 60,
        energySource: 'integrated_rate'
    }];

    assert.deepStrictEqual(
        plain(charging._periodAveragePower({
            charging: true,
            powerKw: 5.5,
            isEstimated: true
        })),
        { value: 7, live: false });
    assert.deepStrictEqual(
        plain(charging._periodAveragePower({
            charging: true,
            powerKw: 6.2,
            isEstimated: false
        })),
        { value: 6.2, live: true });
});

test('completion card covers full waiting and active charging', function () {
    const charging = createEnvironment().charging;

    assert.strictEqual(
        charging._chargingCompletion({ full: true }).primary,
        'Complete');
    assert.strictEqual(
        charging._chargingCompletion({
            plugged: true,
            charging: false
        }).primary,
        'Waiting');
    assert.strictEqual(
        charging._chargingCompletion({
            charging: true,
            timeToFullMin: 65
        }).primary,
        '1h 5m');
    assert.strictEqual(
        charging._chargingCompletion({
            charging: false,
            timeToFullMin: 65
        }),
        null);
});

test('zero and negative charging power remain explicit graph gaps', function () {
    const charging = createEnvironment().charging;
    const points = plain(charging._powerCurvePoints([
        { t: 1, power: 6.1, soc: 40 },
        { t: 2, power: 0, soc: null },
        { t: 3, power: -2, soc: 41 },
        { t: 4, power: null, soc: null },
        { t: 5, power: 6.0, soc: 42 }
    ]));

    assert.deepStrictEqual(points, [
        { t: 1, power: 6.1, soc: 40 },
        { t: 2, power: null, soc: null },
        { t: 3, power: null, soc: 41 },
        { t: 4, power: null, soc: null },
        { t: 5, power: 6, soc: 42 }
    ]);
    assert.strictEqual(charging._powerCurveValueCount(points), 3);
});

(async function run() {
    let passed = 0;
    for (const entry of tests) {
        try {
            await entry.body();
            passed++;
        } catch (error) {
            console.error('FAIL: ' + entry.name);
            throw error;
        }
    }
    console.log('charging behavior harness: '
        + passed + '/' + tests.length + ' passed');
})().catch(function (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
