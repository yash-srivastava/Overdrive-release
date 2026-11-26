/* Run with node app/src/test/js/timeline_share_fallback_harness.js. */
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(process.argv[2] || path.join(__dirname,
    '../../main/assets/web/shared/timeline-view.js'), 'utf8');
const boot = 'if (doc && doc.documentElement && doc.getElementById) boot();';
assert(source.includes(boot), 'test hook must match the controller');
// Access the real share handler and selection without starting the player or network.
const instrumented = source.replace(boot, 'root.testShare = { share: shareCurrentSegment, state: V };');
const clip = { id: 'aa11', videoUrl: '/video/aa11', end: 200 };
const other = { id: 'bb22', videoUrl: '/video/bb22', end: 300 };
const flush = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
function fixture(navigator = {}) {
    const downloads = [], requests = [], toasts = [];
    const context = {
        navigator, location: { origin: 'https://example.test' },
        BYD: { utils: { toast(message, kind) { toasts.push({ message, kind }); } } },
        File: function (parts, name, options) { this.name = name; this.type = options.type; },
        fetch(url) {
            const d = deferred();
            requests.push({ url, ...d });
            return d.promise;
        },
        document: {
            body: { appendChild() {}, removeChild() {} },
            createElement(tag) {
                assert.strictEqual(tag, 'a');
                return { click() { downloads.push({ url: this.href, name: this.download }); } };
            }
        }
    };
    context.window = context;
    vm.runInNewContext(instrumented, context);
    const api = context.testShare;
    api.state.item = { ...clip };
    return { downloads, requests, toasts, api, share() { api.share(); },
        select(item) { api.state.item = item; } };
}
const expected = [{ url: clip.videoUrl, name: 'aa11.mp4' }];
const link = 'https://example.test' + clip.videoUrl;
const fileResponse = () => ({ ok: true, blob: () => Promise.resolve({ type: 'video/mp4' }) });
const scenarios = {
    async 'fetch failure after selection changes'() {
        const f = fixture({ canShare: () => true, share: () => Promise.resolve() });
        f.share();
        assert.strictEqual(f.requests[0].url, clip.videoUrl);
        f.select(other);
        f.requests[0].reject(new Error('offline'));
        await flush();
        assert.deepStrictEqual(f.downloads, expected);
        assert.strictEqual(f.toasts[0].kind, 'error');
    },
    async 'clipboard failure after selection disappears'() {
        const d = deferred();
        const f = fixture({ clipboard: { writeText(url) { assert.strictEqual(url, link); return d.promise; } } });
        f.share(); await flush();
        f.select(null);
        d.reject(new Error('clipboard denied'));
        await flush();
        assert.deepStrictEqual(f.downloads, expected);
    },
    async 'link failure without clipboard after selection changes'() {
        const d = deferred();
        const f = fixture({ share(options) { assert.strictEqual(options.url, link); return d.promise; } });
        f.share(); await flush();
        f.select(other);
        d.reject(new Error('share unavailable'));
        await flush();
        assert.deepStrictEqual(f.downloads, expected);
    },
    async 'successful file share stops fallback'() {
        const shares = [], copied = [];
        const f = fixture({ canShare: () => true,
            share(options) { shares.push(options); return Promise.resolve(); },
            clipboard: { writeText(url) { copied.push(url); return Promise.resolve(); } } });
        f.share(); f.select(other);
        f.requests[0].resolve(fileResponse());
        await flush();
        assert.strictEqual(shares.length, 1);
        assert.strictEqual(shares[0].files[0].name, 'aa11.mp4');
        assert.deepStrictEqual(copied, []);
        assert.deepStrictEqual(f.downloads, []);
    },
    async 'failed file share falls back to successful link share'() {
        const shares = [];
        const f = fixture({ canShare: () => true,
            share(options) {
                shares.push(options);
                return options.files ? Promise.reject(new Error('file share unavailable')) : Promise.resolve();
            } });
        f.share(); f.select(other);
        f.requests[0].resolve(fileResponse());
        await flush();
        assert.strictEqual(shares.length, 2);
        assert.strictEqual(shares[1].url, link);
        assert.deepStrictEqual(f.downloads, []);
    },
    async 'successful link share stops fallback'() {
        const f = fixture({ share(options) { assert.strictEqual(options.url, link); return Promise.resolve(); },
            clipboard: { writeText() { throw new Error('unexpected copy'); } } });
        f.share(); await flush();
        assert.deepStrictEqual(f.downloads, []);
        assert.deepStrictEqual(f.toasts, []);
    },
    async 'successful clipboard copy uses original link'() {
        const copied = [];
        const f = fixture({ clipboard: { writeText(url) { copied.push(url); return Promise.resolve(); } } });
        f.share(); f.select(other); await flush();
        assert.deepStrictEqual(copied, [link]);
        assert.deepStrictEqual(f.downloads, []);
        assert.strictEqual(f.toasts[0].kind, 'success');
    },
    async 'direct download retains captured URL and filename'() {
        const f = fixture();
        f.share();
        f.api.state.item.id = other.id;
        f.api.state.item.videoUrl = other.videoUrl;
        await flush();
        assert.deepStrictEqual(f.downloads, expected);
    },
    async 'unavailable and unfinished clips do not share'() {
        const f = fixture();
        f.select({ ...clip, available: false }); f.share();
        f.select({ ...clip, end: null }); f.share();
        await flush();
        assert.deepStrictEqual(f.downloads, []);
        assert.deepStrictEqual(f.requests, []);
        assert.deepStrictEqual(f.toasts.map(t => t.kind), ['warning', 'warning']);
    }
};
(async function () {
    let failed = 0;
    for (const [name, run] of Object.entries(scenarios)) {
        try { await run(); console.log('PASS ' + name); }
        catch (error) { failed++; console.error('FAIL ' + name, error); }
    }
    console.log(`${Object.keys(scenarios).length - failed}/${Object.keys(scenarios).length} scenarios passed`);
    if (failed) process.exitCode = 1;
})();
