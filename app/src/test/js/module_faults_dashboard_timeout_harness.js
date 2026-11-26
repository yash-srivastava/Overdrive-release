'use strict';

// Run with: node app/src/test/js/module_faults_dashboard_timeout_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
    '../../main/assets/web/shared/module-faults-dashboard.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
const warning = { success: true, modules: [{ name: 'Synthetic module', status: 'warning' }] };
const clear = { success: true, modules: [] };
function response(payload, ok = true) {
    return { ok, json: () => Promise.resolve(payload) };
}
function environment(withAbort) {
    const requests = [], timers = new Map();
    let next = 0, aborts = 0;
    const nodes = {
        dashModuleFaults: { hidden: true },
        dashModuleFaultsCount: {}, dashModuleFaultsSummary: {},
        dashModuleFaultsList: { items: [], get firstChild() { return this.items[0]; },
            removeChild() { this.items.shift(); }, appendChild(item) { this.items.push(item); } }
    };
    const window = {
        fetch(url, options) {
            assert.strictEqual(url, '/api/diagnostics/module-faults');
            assert.strictEqual(options.method, 'GET');
            assert.strictEqual(Boolean(options.signal), withAbort);
            const request = deferred();
            requests.push(request);
            return request.promise;
        },
        setTimeout(fn, delay) { assert.strictEqual(delay, 15000); timers.set(++next, fn); return next; },
        clearTimeout(id) { timers.delete(id); },
        AbortController: withAbort ? class {
            constructor() { this.signal = {}; }
            abort() { aborts++; }
        } : undefined
    };
    const document = { getElementById: id => nodes[id], createElement: () => ({}) };
    vm.runInNewContext(source, { window, document });
    const dashboard = window.BYD.moduleFaultsDashboard;
    dashboard.render(warning);
    return { dashboard, requests, timers, nodes, aborts: () => aborts,
        expire() {
            assert.strictEqual(timers.size, 1, 'pending request must have a deadline');
            const [id, fn] = timers.entries().next().value;
            timers.delete(id);
            fn();
        } };
}

async function stalled(withAbort, bodyStalls) {
    const e = environment(withAbort);
    const first = e.dashboard.refresh();
    assert.strictEqual(e.dashboard.refresh(), first, 'pending refreshes must share one request');
    await flush();
    assert.strictEqual(e.requests.length, 1);
    const body = deferred();
    if (bodyStalls) {
        e.requests[0].resolve({ ok: true, json: () => body.promise });
        await flush();
    }
    e.expire();
    await first;
    assert.strictEqual(e.dashboard.request, null);
    assert.strictEqual(e.nodes.dashModuleFaults.hidden, false, 'timeout must preserve warning');
    assert.strictEqual(e.aborts(), withAbort ? 1 : 0);
    const retry = e.dashboard.refresh();
    await flush();
    assert.strictEqual(e.requests.length, 2);
    e.requests[1].resolve(response(warning));
    await retry;
    assert.strictEqual(e.timers.size, 0);
    // An old response that contains no warnings must not hide the newer warning.
    if (bodyStalls) body.resolve(clear);
    else e.requests[0].resolve(response(clear));
    await flush();
    assert.strictEqual(e.nodes.dashModuleFaults.hidden, false);
    const latest = e.dashboard.refresh();
    await flush();
    e.requests[2].resolve(response(clear));
    await latest;
    assert.strictEqual(e.nodes.dashModuleFaults.hidden, true, 'current successful response still renders');
    assert.strictEqual(e.timers.size, 0);
}

async function failure(kind) {
    const e = environment(false);
    const request = e.dashboard.refresh();
    await flush();
    if (kind === 'network') e.requests[0].reject(new Error('offline'));
    else if (kind === 'json') e.requests[0].resolve({ ok: true, json: () => Promise.reject(new Error('invalid')) });
    else if (kind === 'http') e.requests[0].resolve(response(clear, false));
    else e.requests[0].resolve(response({ success: false }));
    await request;
    assert.strictEqual(e.nodes.dashModuleFaults.hidden, false);
    assert.strictEqual(e.dashboard.request, null);
    assert.strictEqual(e.timers.size, 0);
}

(async function () {
    for (const withAbort of [false, true]) {
        for (const bodyStalls of [false, true]) await stalled(withAbort, bodyStalls);
    }
    for (const kind of ['network', 'json', 'http', 'payload']) await failure(kind);
    console.log('module_faults_dashboard_timeout_harness: 8 scenarios passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
