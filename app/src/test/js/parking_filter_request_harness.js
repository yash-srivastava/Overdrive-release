'use strict';

// Run with: node app/src/test/js/parking_filter_request_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const src = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/parking.js'), 'utf8');
const now = Date.UTC(2026, 0, 15, 12);
const day = 86400000;

function element() {
    const attributes = {};
    const classes = new Set();
    return {
        style: {}, children: [], html: '',
        classList: { add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c) },
        setAttribute(k, v) { attributes[k] = v; },
        getAttribute(k) { return attributes[k]; },
        appendChild(child) { this.children.push(child); },
        set innerHTML(html) { this.html = html; this.children = []; },
        get innerHTML() { return this.html; }
    };
}

function setup() {
    const nodes = {};
    const tabs = [element(), element(), element()];
    const requests = [];
    const BYD = {
        clock: { time: () => '12:00' },
        units: { hourSuffix: () => 'h', minuteSuffix: () => 'm' }
    };
    class Clock extends Date { static now() { return now; } }
    const sandbox = {
        Date: Clock, BYD, window: { BYD },
        document: {
            getElementById: id => nodes[id] || (nodes[id] = element()),
            querySelectorAll: () => tabs,
            createElement: element
        },
        fetch(url) {
            return new Promise((resolve, reject) => requests.push({
                url: new URL(url, 'https://example.invalid'),
                reply(sessions) { resolve({ json: () => Promise.resolve({ sessions, total: sessions.length }) }); },
                reject
            }));
        }
    };
    vm.runInNewContext(src, sandbox, { filename: 'parking.js' });
    return { page: sandbox.window.Parking, nodes, tabs, requests };
}

function session(name) {
    return { sessionId: name, safeZone: name, startedMs: now - 3600000, endedMs: now };
}
function cards(h) {
    return h.nodes.pkSessions.children.filter(el => el.className === 'park-card').map(el => el.innerHTML);
}
function expectOnly(h, name) {
    const rows = cards(h);
    assert.strictEqual(rows.length, 1);
    assert.ok(rows[0].includes(name), 'expected session ' + name);
}
function flush() { return new Promise(resolve => setImmediate(resolve)); }
function selectTwo(h) {
    h.page.quickFilter(30, h.tabs[0]);
    h.page.quickFilter(7, h.tabs[1]);
    assert.strictEqual(h.requests.length, 2, 'new range must request page one even while old range loads');
    assert.strictEqual(h.requests[1].url.searchParams.get('from'), String(now - 7 * day));
    assert.strictEqual(h.requests[1].url.searchParams.get('offset'), '0');
    assert.ok(h.tabs[1].classList.contains('active'));
}

(async function () {
    // Older success after the new range is rendered must not overwrite it.
    let h = setup();
    selectTwo(h);
    h.requests[1].reply([session('new-range')]); await flush();
    h.requests[0].reply([session('old-range')]); await flush();
    expectOnly(h, 'new-range');

    // Older completion while the replacement is still loading must not release
    // its single-flight guard or replace its skeleton.
    h = setup(); selectTwo(h);
    h.requests[0].reply([session('old-range')]); await flush();
    assert.ok(h.nodes.pkSessions.innerHTML.includes('skeleton'));
    h.page.loadMore();
    h.page.quickFilter(7, h.tabs[1]);
    assert.strictEqual(h.requests.length, 2, 'same-range and append requests stay coalesced');
    h.requests[1].reply([session('new-range')]); await flush();
    expectOnly(h, 'new-range');

    // An obsolete network failure must not replace the current list with an empty state.
    h = setup(); selectTwo(h);
    h.requests[1].reply([session('new-range')]); await flush();
    h.requests[0].reject(new Error('offline')); await flush();
    expectOnly(h, 'new-range');
    assert.strictEqual(h.nodes.pkEmptyState.style.display, 'none');

    // Changing range during Load more resets the offset and drops the old page.
    h = setup();
    h.page.quickFilter(30, h.tabs[0]);
    h.requests[0].reply(Array.from({ length: 24 }, (_, i) => session('initial-' + i))); await flush();
    h.page.loadMore(); h.page.loadMore();
    assert.strictEqual(h.requests.length, 2);
    assert.strictEqual(h.requests[1].url.searchParams.get('offset'), '24');
    h.page.quickFilter(0, h.tabs[2]);
    assert.strictEqual(h.requests.length, 3);
    assert.strictEqual(h.requests[2].url.searchParams.get('offset'), '0');
    assert.strictEqual(h.requests[2].url.searchParams.has('from'), false);
    h.requests[2].reply([session('all-range')]); await flush();
    h.requests[1].reply([session('obsolete-page')]); await flush();
    expectOnly(h, 'all-range');
    console.log('parking_filter_request_harness: 4 scenarios passed');
})().catch(err => { console.error(err); process.exitCode = 1; });
