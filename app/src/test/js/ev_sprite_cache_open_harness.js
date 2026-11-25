'use strict';

// Run with: node app/src/test/js/ev_sprite_cache_open_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/ev-card-sprite-cache.js'), 'utf8');

function fixture() {
    const requests = [];
    let opens = 0;
    let throwNext = false;
    const sandbox = {
        window: {}, Blob: function () {}, Image: function () {},
        URL: { createObjectURL() {} },
        indexedDB: { open() {
            opens++;
            if (throwNext) { throwNext = false; throw new Error('open failed'); }
            const req = {};
            requests.push(req);
            return req;
        } }
    };
    vm.runInNewContext(source, sandbox);
    const cache = sandbox.window.OverdriveEvSpriteCache;
    const canvas = { clientWidth: 280, clientHeight: 200 };
    const entry = { blob: {}, w: 280, h: 200 };
    const db = {
        closes: 0,
        close() { this.closes++; },
        transaction() {
            return { objectStore() { return { get() {
                const read = { result: entry };
                Promise.resolve().then(() => read.onsuccess());
                return read;
            } }; } };
        }
    };
    return {
        requests, db, entry,
        get opens() { return opens; },
        failNext() { throwNext = true; },
        get() { return cache.get('model', '#ffffff', 'side', canvas); },
        succeed(req, connection = db) { req.result = connection; req.onsuccess(); },
        fail(req) { req.error = new Error('open error'); req.onerror(); }
    };
}

async function run() {
    // Synchronous rejection must not poison future cache reads.
    const a = fixture();
    a.failNext();
    assert.strictEqual(await a.get(), null);
    const retry = a.get();
    assert.strictEqual(a.opens, 2, 'synchronous failure must be retryable');
    a.succeed(a.requests[0]);
    assert.strictEqual(await retry, a.entry);

    // Concurrent callers share one pending open and one successful connection.
    const b = fixture();
    const first = b.get();
    const second = b.get();
    assert.strictEqual(b.opens, 1);
    b.succeed(b.requests[0]);
    assert.strictEqual(await first, b.entry);
    assert.strictEqual(await second, b.entry);
    assert.strictEqual(await b.get(), b.entry);
    assert.strictEqual(b.opens, 1);

    // Asynchronous open errors remain retryable.
    const c = fixture();
    const failed = c.get();
    c.fail(c.requests[0]);
    assert.strictEqual(await failed, null);
    const next = c.get();
    c.succeed(c.requests[1]);
    assert.strictEqual(await next, c.entry);

    // A blocked open falls back, but may succeed later. Close its abandoned
    // connection without discarding the current successful open.
    const d = fixture();
    const blocked = d.get();
    d.requests[0].onblocked();
    assert.strictEqual(await blocked, null);
    const recovery = d.get();
    const abandoned = { closes: 0, close() { this.closes++; } };
    d.succeed(d.requests[0], abandoned);
    assert.strictEqual(abandoned.closes, 1);
    d.succeed(d.requests[1]);
    assert.strictEqual(await recovery, d.entry);
    assert.strictEqual(await d.get(), d.entry);
    assert.strictEqual(d.opens, 2);
    assert.strictEqual(d.db.closes, 0);

    // An error from a previously blocked request must not clear a newer open.
    const e = fixture();
    const obsolete = e.get();
    e.requests[0].onblocked();
    assert.strictEqual(await obsolete, null);
    const current = e.get();
    e.fail(e.requests[0]);
    const shared = e.get();
    assert.strictEqual(e.opens, 2);
    e.succeed(e.requests[1]);
    assert.strictEqual(await current, e.entry);
    assert.strictEqual(await shared, e.entry);
    console.log('ev_sprite_cache_open_harness: 5 scenarios passed');
}

run().catch(err => { console.error(err); process.exitCode = 1; });
