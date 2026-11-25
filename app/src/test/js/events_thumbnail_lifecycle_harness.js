'use strict';

// Run: node app/src/test/js/events_thumbnail_lifecycle_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/events.js'), 'utf8');

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture(abortSupported) {
    let serial = 0;
    const requests = [], timers = new Map(), liveUrls = new Set(), revoked = [];
    const list = {
        cards: [],
        contains(card) { return this.cards.includes(card); },
        querySelectorAll() { return this.cards; },
        set innerHTML(html) { this.cards = []; }
    };
    const BYD = { i18n: { t: key => key } };
    const context = {
        BYD, window: { BYD }, console,
        document: {
            getElementById: id => id === 'recordingsList' ? list : null,
            createElement: () => ({ remove() { this.removed = true; } })
        },
        AbortController: abortSupported ? AbortController : undefined,
        fetch(url, options) {
            const d = deferred();
            requests.push({ url, options, ...d });
            return d.promise;
        },
        setTimeout(fn, delay) { const id = ++serial; timers.set(id, { fn, delay }); return id; },
        clearTimeout(id) { timers.delete(id); },
        URL: {
            createObjectURL() { const url = 'blob:' + ++serial; liveUrls.add(url); return url; },
            revokeObjectURL(url) { assert(liveUrls.delete(url), 'revoke exactly once'); revoked.push(url); }
        }
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    function card(url) {
        const placeholder = { innerHTML: '', remove() { this.removed = true; } };
        return {
            dataset: { thumb: url }, placeholder,
            querySelector() { return placeholder.removed ? null : placeholder; },
            insertBefore(img) { this.img = img; }
        };
    }
    function render(cards) {
        BYD.events._replaceRecordingList(list, 'new list');
        list.cards = cards;
        BYD.events.loadThumbnailsAsync();
    }
    function tick() {
        const [id, timer] = timers.entries().next().value;
        timers.delete(id);
        timer.fn();
        return timer.delay;
    }
    const ok = () => ({ status: 200, blob: async () => ({ size: 1234 }) });
    return { ev: BYD.events, list, card, render, requests, timers, liveUrls, revoked, tick, ok };
}

async function main() {
    // Replacing a list cancels pending retry timers and aborts supported fetches.
    for (const support of [true, false]) {
        const f = fixture(support);
        const a = f.card('/thumb/a'), b = f.card('/thumb/b'), c = f.card('/thumb/c');
        f.render([a, b, c]);
        f.ev.loadThumbnailsAsync();
        assert.strictEqual(f.requests.length, 3, 'duplicate load does not start another batch');
        f.requests[0].resolve({ status: 202 });
        f.requests[1].resolve(f.ok());
        await flush();
        assert.strictEqual(f.timers.size, 1);
        assert.strictEqual(f.liveUrls.size, 1);
        b.img.onload();
        assert(b.placeholder.removed);
        assert.strictEqual(f.liveUrls.size, 1, 'current image URL stays usable');
        const lateTimer = Array.from(f.timers.values())[0].fn;
        f.render([f.card('/thumb/new')]);
        assert.strictEqual(f.timers.size, 0);
        assert.strictEqual(f.liveUrls.size, 0);
        if (support) assert(f.requests[2].options.signal.aborted);
        else assert.strictEqual(f.requests[2].options, undefined);
        lateTimer(); // A callback already dequeued before cancellation is harmless.
        let parsed = false;
        f.requests[2].resolve({ status: 200, blob() { parsed = true; return Promise.resolve({ size: 1 }); } });
        await flush();
        assert.strictEqual(parsed, false, 'stale response not converted to blob');
        assert.strictEqual(f.requests.length, 4);
        assert(!c.img);
        b.img.onerror(); // A queued event cannot revoke again or mutate an old card.
        assert.strictEqual(f.revoked.length, 1);
        assert(!b.img.removed);
    }
    // A late 202 must not schedule a retry, and a current retry may still succeed.
    {
        const f = fixture(false), a = f.card('/thumb/a'), b = f.card('/thumb/b');
        f.render([a]);
        f.render([b]);
        f.requests[0].resolve({ status: 202 });
        await flush();
        assert.strictEqual(f.timers.size, 0);
        f.requests[1].resolve({ status: 202 });
        await flush();
        assert.strictEqual(f.tick(), 1000);
        f.requests[2].resolve(f.ok());
        await flush();
        b.img.onload();
        assert(b.placeholder.removed);
        assert.strictEqual(f.liveUrls.size, 1);
        assert.strictEqual(f.timers.size, 0);
    }
    // Replacement while body conversion is pending must not create a URL.
    {
        const f = fixture(false), a = f.card('/thumb/a'), body = deferred();
        f.render([a]);
        f.requests[0].resolve({ status: 200, blob: () => body.promise });
        await flush();
        f.render([]);
        body.resolve({ size: 100 });
        await flush();
        assert(!a.img);
        assert.strictEqual(f.liveUrls.size, 0);
    }
    // Active retries keep the original nine attempts and capped backoff.
    {
        const f = fixture(true), a = f.card('/thumb/a');
        f.render([a]);
        for (let i = 0; i < 9; i++) {
            f.requests[i].resolve({ status: 202 });
            await flush();
            assert.strictEqual(f.tick(), Math.min(1000 * Math.pow(1.5, i), 5000));
        }
        assert.strictEqual(f.requests.length, 9);
        assert(a.placeholder.innerHTML.includes('events.no_preview'));
        assert.strictEqual(f.timers.size, 0);
    }
    // Image and network failures retain fallback and release only their own URL.
    {
        const f = fixture(true), a = f.card('/thumb/a'), b = f.card('/thumb/b');
        f.render([a, b]);
        f.requests[0].resolve(f.ok());
        f.requests[1].reject(new Error('network error'));
        await flush();
        assert(b.placeholder.innerHTML.includes('events.no_preview'));
        a.img.onerror();
        assert(a.img.removed);
        assert(a.placeholder.innerHTML.includes('events.no_preview'));
        assert.strictEqual(f.liveUrls.size, 0);
        f.render([]);
        assert.strictEqual(f.revoked.length, 1);
    }
    // A long session must not accumulate successful blob URLs across pages.
    {
        const f = fixture(false);
        for (let page = 0; page < 100; page++) {
            const cards = Array.from({ length: 12 }, (_, i) => f.card('/thumb/' + page + '/' + i));
            f.render(cards);
            f.requests.slice(-12).forEach(r => r.resolve(f.ok()));
            await flush();
            cards.forEach(c => c.img.onload());
            assert.strictEqual(f.liveUrls.size, 12);
        }
        f.ev._replaceRecordingList(f.list, 'spinner');
        assert.strictEqual(f.liveUrls.size, 0);
        assert.strictEqual(f.revoked.length, 1200);
    }
    // All list writes must pass through the cleanup boundary.
    assert.strictEqual((source.match(/list\.innerHTML\s*=/g) || []).length, 1);
    console.log('events_thumbnail_lifecycle_harness: all scenarios passed (1200 URL lifecycle checks)');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
