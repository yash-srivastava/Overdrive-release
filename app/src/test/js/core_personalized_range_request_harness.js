'use strict';

// Run with: node app/src/test/js/core_personalized_range_request_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
    '../../main/assets/web/shared/core.js'), 'utf8');
// Load the actual core object, without unrelated page initialization.
const start = source.indexOf('BYD.core = {');
const end = source.indexOf('\n};', start);
assert(start >= 0 && end > start);
const coreSource = source.slice(start, end + 3);
const flush = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

function environment(withAbort) {
    let now = 100000, nextTimer = 0, aborts = 0;
    const requests = [], timers = new Map(), nodes = {};
    const context = {
        BYD: { units: { dist: n => n + ' km' } },
        document: { getElementById(id) {
            return nodes[id] || (nodes[id] = { style: {} });
        } },
        Date: { now: () => now },
        setTimeout(fn, delay) { timers.set(++nextTimer, { fn, at: now + delay }); return nextTimer; },
        clearTimeout(id) { timers.delete(id); },
        fetch(url, options) {
            assert.strictEqual(url, '/api/trips/range');
            const request = deferred();
            requests.push({ ...request, options });
            return request.promise;
        },
        AbortController: withAbort ? class {
            constructor() { this.signal = {}; }
            abort() { aborts++; }
        } : undefined
    };
    vm.runInNewContext(coreSource, context);
    const core = context.BYD.core;
    core._lastRange = { elecRangeKm: 100, fuelRangeKm: 300, totalRangeKm: 400 };
    return { core, requests, nodes, timers, aborts: () => aborts,
        advance(ms) {
            now += ms;
            for (const [id, timer] of timers) {
                if (timer.at <= now) { timers.delete(id); timer.fn(); }
            }
        } };
}

function reply(km) {
    return { json: async () => ({ success: true, range: { predictedRangeKm: km } }) };
}

async function coalescesAndCaches() {
    const e = environment(true);
    const first = e.core.updatePersonalizedRange();
    e.advance(5000);
    e.core._lastRange.fuelRangeKm = 290;
    const overlapping = e.core.updatePersonalizedRange();
    assert.strictEqual(e.requests.length, 1, 'status ticks must not duplicate pending range requests');
    await overlapping;
    assert.strictEqual(e.nodes.fuelPersonalizedRange.textContent, '290 km');
    const body = deferred();
    e.requests[0].resolve({ json: () => body.promise });
    await flush();
    await e.core.updatePersonalizedRange();
    assert.strictEqual(e.requests.length, 1, 'body parsing is still in flight');
    body.resolve({ success: true, range: { predictedRangeKm: 123 } });
    await first;
    assert.strictEqual(e.core._personalizedRangeKm, 123);
    assert.strictEqual(e.timers.size, 0);
    e.advance(54999);
    await e.core.updatePersonalizedRange();
    assert.strictEqual(e.requests.length, 1, 'retain the 60-second cache');
    e.advance(1);
    const second = e.core.updatePersonalizedRange();
    assert.strictEqual(e.requests.length, 2);
    e.requests[1].resolve(reply(130));
    await second;
    assert.strictEqual(e.core._personalizedRangeKm, 130);
}

async function timeout(withAbort, stallBody) {
    const e = environment(withAbort);
    e.core._personalizedRangeKm = 120;
    const first = e.core.updatePersonalizedRange();
    const body = deferred();
    if (stallBody) { e.requests[0].resolve({ json: () => body.promise }); await flush(); }
    e.advance(15000);
    await first;
    assert.strictEqual(e.core._personalizedRangeKm, 120, 'retain last-known range on timeout');
    assert.strictEqual(e.aborts(), withAbort ? 1 : 0);
    assert.strictEqual(e.timers.size, 0);
    await e.core.updatePersonalizedRange();
    assert.strictEqual(e.requests.length, 1, 'retain the existing failure backoff');
    e.advance(45000);
    const retry = e.core.updatePersonalizedRange();
    assert.strictEqual(e.requests.length, 2, 'timeout must release the guard');
    e.requests[1].resolve(reply(140));
    await retry;
    if (stallBody) body.resolve({ success: true, range: { predictedRangeKm: 1 } });
    else e.requests[0].resolve(reply(1));
    await flush();
    assert.strictEqual(e.core._personalizedRangeKm, 140, 'late response must not overwrite retry');
}

async function failure(jsonFailure) {
    const e = environment(true);
    const first = e.core.updatePersonalizedRange();
    if (jsonFailure) e.requests[0].resolve({ json: () => Promise.reject(new Error('invalid JSON')) });
    else e.requests[0].reject(new Error('offline'));
    await first;
    assert.strictEqual(e.timers.size, 0);
    e.advance(60000);
    const retry = e.core.updatePersonalizedRange();
    assert.strictEqual(e.requests.length, 2);
    e.requests[1].resolve(reply(145));
    await retry;
    assert.strictEqual(e.core._personalizedRangeKm, 145);
}

(async () => {
    await coalescesAndCaches();
    for (const withAbort of [true, false]) {
        for (const stallBody of [true, false]) await timeout(withAbort, stallBody);
    }
    await failure(false);
    await failure(true);
    console.log('Personalized range: 7 scenarios passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
