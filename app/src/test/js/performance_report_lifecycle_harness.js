// Run with: node app/src/test/js/performance_report_lifecycle_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/performance.js'), 'utf8');

function fixture(tab, hidden = false, hash = '') {
    const calls = [];
    const timers = new Map();
    const timeouts = [];
    const docEvents = {}, winEvents = {};
    let nextId = 1, reconnect, languageChange;
    let active = tab;
    const document = {
        hidden,
        addEventListener: (name, fn) => { docEvents[name] = fn; },
        querySelector: selector => {
            if (selector === '.bottom-tabs .bottom-tab.is-active[data-tab-target]') {
                return { getAttribute: () => active };
            }
            if (selector === '.bottom-tab[data-tab-target="battery"]') {
                return { click: () => select('battery') };
            }
            return null;
        }
    };
    function select(id) {
        active = id;
        docEvents['ot-tabs:active-changed']({ detail: { id } });
    }
    const pendingSoh = [];
    const BYD = {
        offline: { onReconnect: fn => { reconnect = fn; } },
        i18n: { onChange: fn => { languageChange = fn; } }
    };
    const window = { BYD, location: { hash },
        addEventListener: (name, fn) => { winEvents[name] = fn; } };
    vm.runInNewContext(source, {
        BYD, window, document, console,
        setInterval: (fn, ms) => {
            assert.strictEqual(ms, 60000);
            const id = nextId++;
            timers.set(id, fn);
            return id;
        },
        clearInterval: id => timers.delete(id),
        setTimeout: fn => timeouts.push(fn),
        requestAnimationFrame: fn => fn(),
        fetch: url => {
            assert.strictEqual(url, '/api/performance/soh');
            calls.push('soh');
            return new Promise(resolve => pendingSoh.push(() => resolve({
                json: async () => ({ success: true })
            })));
        }
    });
    const perf = BYD.performance;
    ['_refreshPalette', '_setupThemeObserver', 'initCharts', 'applyDataUsageState',
        'resizeCharts', 'stopRealtimePolling', 'stopTopPolling', 'disconnect',
        'setMonitoringStatus', 'startRealtimeForGeneration', 'startTopPolling',
        'updateSohDetailCard'].forEach(name => { perf[name] = () => {}; });
    perf.fetchSocHistory = () => calls.push('soc');
    perf.fetchBatteryHealth = () => calls.push('battery');
    perf.fetchDataUsage = () => calls.push('data');
    perf._scrollToSohFixBanner = () => calls.push('scroll');
    const tick = () => [...timers.values()].forEach(fn => fn());
    const flushInit = () => timeouts.splice(0).forEach(fn => fn());
    function expect(expected, count) {
        assert.deepStrictEqual(calls.splice(0), expected);
        assert.strictEqual(timers.size, count);
    }
    return { perf, document, window, docEvents, winEvents, select, tick, flushInit,
        expect, reconnect: () => reconnect(), languageChange: () => languageChange(),
        resolveSoh: async () => {
            assert(pendingSoh.length);
            pendingSoh.shift()();
            // Resolve fetch, response.json and the async continuation.
            for (let i = 0; i < 5; i++) await Promise.resolve();
        } };
}

async function lateBatteryHealthDoesNotStartHiddenHvRequest() {
    let finish;
    const BYD = {};
    const sandbox = { BYD, window: { BYD }, document: { hidden: false }, console,
        fetch: () => new Promise(resolve => { finish = resolve; }) };
    vm.runInNewContext(source, sandbox);
    const perf = BYD.performance;
    perf._activeTab = 'battery';
    perf.batteryHealthPollInterval = 1;
    let hv = 0;
    perf.updateBatteryHealthUI = perf.renderVoltageChart = perf.renderThermalChart = () => {};
    perf.fetchHvCells = () => { hv++; };
    const first = perf.fetchBatteryHealth();
    perf._activeTab = 'data';
    finish({ ok: true, json: async () => ({}) });
    await first;
    assert.strictEqual(hv, 0);
    perf._activeTab = 'battery';
    const second = perf.fetchBatteryHealth();
    finish({ ok: true, json: async () => ({}) });
    await second;
    assert.strictEqual(hv, 1);
}

async function main() {
    // Initial view, repeated sync, timer ticks, switches, visibility and reconnect.
    const f = fixture('system');
    await f.perf.init(); f.flushInit(); f.expect([], 0);
    f.select('live'); f.tick(); f.expect([], 0);
    f.select('reset'); f.tick(); f.expect([], 0);
    f.select('battery'); f.expect(['soc', 'battery', 'soh'], 3);
    f.perf.syncViewLifecycle(); f.expect([], 3);
    f.tick(); f.expect(['soc', 'battery', 'soh'], 3);
    f.languageChange(); f.expect(['soh'], 3);
    f.select('data'); f.expect(['data'], 1);
    f.tick(); f.languageChange(); f.expect(['data'], 1);
    f.document.hidden = true; f.docEvents.visibilitychange(); f.tick(); f.expect([], 0);
    f.reconnect(); f.expect([], 0);
    f.document.hidden = false; f.docEvents.visibilitychange(); f.expect(['data'], 1);
    f.reconnect(); f.expect(['data'], 1);
    f.select('battery'); f.expect(['soc', 'battery', 'soh'], 3);
    f.winEvents.pagehide(); f.tick(); f.expect([], 0);
    f.winEvents.pageshow(); f.expect(['soc', 'battery', 'soh'], 3);
    f.winEvents.beforeunload(); f.expect([], 0);

    const data = fixture('data');
    await data.perf.init(); data.flushInit(); data.expect(['data'], 1);
    const battery = fixture('battery');
    await battery.perf.init(); battery.flushInit(); battery.expect(['soc', 'battery', 'soh'], 3);
    const hidden = fixture('battery', true);
    await hidden.perf.init(); hidden.flushInit(); hidden.expect([], 0);
    hidden.document.hidden = false; hidden.docEvents.visibilitychange();
    hidden.expect(['soc', 'battery', 'soh'], 3);

    // Notification opens Battery even if a different tab was saved.
    const link = fixture('data', false, '#soh-fix');
    await link.perf.init(); link.flushInit();
    link.expect(['soc', 'battery', 'soh'], 3);
    await link.resolveSoh(); link.expect(['scroll'], 3);
    link.window.location.hash = '#battery'; link.winEvents.hashchange();
    link.window.location.hash = '#soh-fix'; link.winEvents.hashchange();
    link.expect(['soh'], 3);
    await link.resolveSoh(); link.expect(['scroll'], 3);

    // If the user leaves the target before the response, do not scroll later.
    const cancelled = fixture('battery', false, '#soh-fix');
    await cancelled.perf.init(); cancelled.flushInit();
    cancelled.expect(['soc', 'battery', 'soh'], 3);
    cancelled.select('data'); cancelled.expect(['data'], 1);
    await cancelled.resolveSoh(); cancelled.expect([], 1);
    cancelled.select('battery'); cancelled.expect(['soc', 'battery', 'soh'], 3);
    await cancelled.resolveSoh(); cancelled.expect([], 3);
    await lateBatteryHealthDoesNotStartHiddenHvRequest();
    console.log('Performance report lifecycle: all assertions passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
