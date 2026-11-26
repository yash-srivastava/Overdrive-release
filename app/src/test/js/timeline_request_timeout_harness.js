/* Run with node app/src/test/js/timeline_request_timeout_harness.js. */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(process.argv[2] || path.join(__dirname,
    '../../main/assets/web/shared/timeline-view.js'), 'utf8');
const day = '2026-01-15';
const payload = { segments: [{ id: 'clip', start: 100, end: 200 }] };

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
function response(status, json, etag) {
    return { status, ok: status >= 200 && status < 300, json,
        headers: { get: () => etag || null } };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture(abortSupported) {
    const requests = [], timers = new Map(), changes = [];
    let nextTimer = 0;
    const context = {
        location: { href: 'https://example.test/events.html', pathname: '/events.html', search: '' },
        AbortController: abortSupported ? AbortController : undefined,
        fetch(url, opts) {
            const d = deferred();
            requests.push({ ...d, url, opts });
            return d.promise;
        },
        setTimeout(fn, ms) { const id = ++nextTimer; timers.set(id, { fn, ms }); return id; },
        clearTimeout(id) { timers.delete(id); }
    };
    context.window = context;
    vm.runInNewContext(source, context);
    const store = new context.BYD.tlview._internals.Store((...args) => changes.push(args));
    function expire() {
        assert.strictEqual(timers.size, 1, 'request must retain its timeout until settled');
        const [id, timer] = timers.entries().next().value;
        assert.strictEqual(timer.ms, 15000);
        timers.delete(id);
        timer.fn();
    }
    return { context, store, requests, timers, changes, expire };
}

async function stalledBody(abortSupported) {
    const f = fixture(abortSupported);
    const pending = f.store.load(day);
    const body = deferred();
    f.requests[0].resolve(response(200, () => body.promise, 'old'));
    await flush();
    assert.strictEqual(f.store.load(day), pending, 'concurrent reads share the pending operation');
    f.expire();
    await flush();
    const entry = f.store.days.get(day);
    assert.strictEqual(entry.promise, null, 'timeout must release the in-flight entry');
    assert.strictEqual(entry.status, 'error');
    assert.strictEqual(entry.err.offline, true);
    assert.strictEqual(f.timers.size, 0);
    if (abortSupported) assert.strictEqual(f.requests[0].opts.signal.aborted, true);
    assert.strictEqual(await pending, entry);
    const retry = f.store.load(day, true);
    assert.strictEqual(f.requests.length, 2);
    f.requests[1].resolve(response(200, () => Promise.resolve(payload), 'new'));
    await retry;
    body.resolve({ segments: [] });
    await flush();
    assert.strictEqual(entry.data.segments.length, 1, 'late body must not replace retry data');
    assert.strictEqual(entry.etag, 'new');
    assert.strictEqual(f.timers.size, 0);
    assert.strictEqual(f.changes.length, 2);
}

async function stalledHeaders() {
    const f = fixture(false);
    const pending = f.store.load(day);
    f.expire();
    await flush();
    assert.strictEqual(f.store.days.get(day).promise, null);
    await pending;
    f.requests[0].resolve(response(401, () => Promise.resolve({})));
    await flush();
    assert.strictEqual(f.context.location.href, 'https://example.test/events.html', 'late 401 must not redirect');
    assert.strictEqual(f.changes.length, 1);
}

async function normalAndConditional() {
    const f = fixture(true);
    const first = f.store.load(day);
    f.requests[0].resolve(response(200, () => Promise.resolve(payload), 'tag'));
    const entry = await first;
    assert.strictEqual(entry.status, 'ok');
    assert.strictEqual(entry.etag, 'tag');
    assert.strictEqual(f.timers.size, 0);
    const second = f.store.load(day, true);
    assert.strictEqual(f.requests[1].opts.headers['If-None-Match'], 'tag');
    f.requests[1].resolve(response(304, () => { throw Error('304 has no body'); }));
    assert.strictEqual(await second, entry);
    assert.strictEqual(entry.data.segments.length, 1);
    assert.strictEqual(f.timers.size, 0);
    assert.strictEqual(f.requests[1].opts.signal.aborted, false);
}

async function failure(kind) {
    const f = fixture(true);
    const pending = f.store.load(day);
    if (kind === 'network') f.requests[0].reject(Error('network'));
    else if (kind === 'http') f.requests[0].resolve(response(503, () => Promise.resolve({})));
    else f.requests[0].resolve(response(200, () => Promise.reject(Error('bad JSON'))));
    const entry = await pending;
    assert.strictEqual(entry.status, 'error');
    assert.strictEqual(entry.promise, null);
    assert.strictEqual(f.timers.size, 0);
}

(async () => {
    await stalledBody(true);
    await stalledBody(false);
    await stalledHeaders();
    await normalAndConditional();
    for (const kind of ['network', 'http', 'json']) await failure(kind);
    console.log('Timeline request timeout: 7 scenarios passed');
})().catch(err => { console.error(err); process.exitCode = 1; });
