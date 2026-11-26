// Run with: node app/src/test/js/community_list_request_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
    '../../main/assets/web/shared/community-automations.js'), 'utf8');

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

const flush = () => new Promise(resolve => setImmediate(resolve));
const payload = (id, page, total, totalPages) => ({ items: [{ id }], page, total, totalPages });

function fixture() {
    const requests = [], states = [], renders = [], scrolls = [], details = [], toasts = [];
    const listeners = {};
    const anchor = {};
    const BYD = { utils: { scrollToTop: node => scrolls.push(node) } };
    const sandbox = {
        BYD, window: { BYD },
        document: {
            getElementById: id => id === 'communityControls' ? anchor : null,
            addEventListener: (name, fn) => { listeners[name] = fn; }
        },
        fetch: url => {
            const response = deferred();
            requests.push({ url, response });
            return response.promise;
        }
    };
    vm.runInNewContext(source, sandbox, { filename: 'community-automations.js' });
    const catalog = BYD.communityAutomations;
    catalog._renderState = (kind, msg) => states.push([kind, msg]);
    catalog.renderList = items => {
        renders.push(items);
        catalog._maybeScrollTop();
    };
    catalog._showDetailModal = data => details.push(data);
    catalog._toast = (msg, kind) => toasts.push([msg, kind]);
    function initTabs() {
        // Bind the real tab listener without unrelated startup requests.
        for (const method of ['renderControls', '_loadPublishedIds', 'loadPage',
            '_hookLocalRender', '_scheduleAugment']) catalog[method] = () => {};
        catalog.init();
    }
    function switchTab(id) { listeners['ot-tabs:active-changed']({ detail: { id } }); }
    function reply(index, data) {
        requests[index].response.resolve({ json: () => Promise.resolve(data) });
    }
    return { catalog, requests, states, renders, scrolls, reply, anchor,
        details, toasts, initTabs, switchTab };
}

async function newestFilterWins() {
    const { catalog, requests, renders, reply, scrolls } = fixture();
    catalog.loadPage(3, true);
    catalog.category = 'comfort';
    catalog.q = 'seat';
    catalog.loadPage(1);
    assert.match(requests[0].url, /page=3/);
    assert.match(requests[1].url, /category=comfort&q=seat/);
    const newest = payload('new', 1, 2, 1);
    reply(1, newest);
    await flush();
    reply(0, payload('old', 3, 100, 9));
    await flush();
    assert.deepStrictEqual(renders, [newest.items]);
    assert.deepStrictEqual([catalog.currentPage, catalog.total, catalog.totalPages], [1, 2, 1]);
    assert.strictEqual(catalog._loading, false);
    assert.deepStrictEqual(scrolls, []);
}

async function olderResponseWhileNewPagePending() {
    const { catalog, renders, reply, scrolls, anchor } = fixture();
    catalog.loadPage(1);
    catalog.loadPage(2, true);
    reply(0, payload('old', 1, 30, 3));
    await flush();
    assert.strictEqual(catalog._loading, true);
    assert.strictEqual(catalog.currentPage, 2);
    assert.strictEqual(catalog._scrollAfterRender, true);
    assert.deepStrictEqual(renders, []);
    assert.deepStrictEqual(scrolls, []);
    const newest = payload('new', 2, 30, 3);
    reply(1, newest);
    await flush();
    assert.deepStrictEqual(renders, [newest.items]);
    assert.deepStrictEqual(scrolls, [anchor]);
    assert.strictEqual(catalog._scrollAfterRender, false);
    assert.strictEqual(catalog._loading, false);
}

async function lateBodyOnSamePage() {
    const { catalog, requests, renders, reply } = fixture();
    const body = deferred();
    catalog.loadPage(1);
    requests[0].response.resolve({ json: () => body.promise });
    await flush();
    catalog.loadPage(1);
    const newest = payload('refreshed', 1, 1, 1);
    reply(1, newest);
    await flush();
    body.resolve(payload('stale', 1, 1, 1));
    await flush();
    assert.deepStrictEqual(renders, [newest.items]);
}

async function failure(kind, stale) {
    const { catalog, requests, states, renders, reply } = fixture();
    catalog.loadPage(1);
    if (stale) catalog.loadPage(2);
    if (kind === 'network') requests[0].response.reject(new Error('offline'));
    if (kind === 'json') requests[0].response.resolve({ json: () => Promise.reject(new Error('bad JSON')) });
    if (kind === 'payload') reply(0, { error: 'Unavailable' });
    await flush();
    if (stale) {
        assert.deepStrictEqual(states.map(s => s[0]), ['loading', 'loading']);
        assert.strictEqual(catalog._loading, true);
        const newest = payload('new', 2, 30, 3);
        reply(1, newest);
        await flush();
        assert.deepStrictEqual(renders, [newest.items]);
    } else {
        assert.deepStrictEqual(states.map(s => s[0]), ['loading', 'error']);
        assert.deepStrictEqual(renders, []);
    }
    assert.strictEqual(catalog._loading, false);
}

// Only the most recent detail request may open a dialog.
async function detailOrdering(newestFirst) {
    const { catalog, requests, details, toasts, reply } = fixture();
    catalog.openDetail('old');
    catalog.openDetail('new');
    assert.match(requests[1].url, /automation\/new$/);
    const latest = { id: 'new' };
    if (newestFirst) {
        reply(1, { automation: latest });
        await flush();
        reply(0, { automation: { id: 'old' } });
    } else {
        reply(0, { automation: { id: 'old' } });
        await flush();
        assert.deepStrictEqual(details, []);
        reply(1, { automation: latest });
    }
    await flush();
    assert.deepStrictEqual(details, [latest]);
    assert.deepStrictEqual(toasts, []);
}

async function detailFailure(stale, kind) {
    const { catalog, requests, details, toasts, reply } = fixture();
    catalog.openDetail('old');
    if (stale) catalog.openDetail('new');
    if (kind === 'network') requests[0].response.reject(new Error('offline'));
    else if (kind === 'json') requests[0].response.resolve({ json: () => Promise.reject(new Error('bad JSON')) });
    else reply(0, { error: 'Unavailable' });
    await flush();
    assert.deepStrictEqual(details, []);
    assert.deepStrictEqual(toasts, stale ? [] : [['community.load_failed', 'error']]);
    if (stale) {
        const latest = { id: 'new' };
        reply(1, { automation: latest });
        await flush();
        assert.deepStrictEqual(details, [latest]);
    }
}

async function detailAfterTabChange(fail) {
    const { catalog, requests, details, toasts, reply, initTabs, switchTab } = fixture();
    initTabs();
    catalog.openDetail('old');
    // The HTTP headers can arrive before the user changes tabs, with the body still pending.
    const body = deferred();
    requests[0].response.resolve({ json: () => body.promise });
    await flush();
    switchTab('general');
    switchTab('community');
    if (fail) body.reject(new Error('bad JSON'));
    else body.resolve({ automation: { id: 'old' } });
    await flush();
    assert.deepStrictEqual(details, []);
    assert.deepStrictEqual(toasts, []);
    catalog.openDetail('new');
    const latest = { id: 'new' };
    reply(1, { automation: latest });
    await flush();
    assert.deepStrictEqual(details, [latest]);
}

(async () => {
    await newestFilterWins();
    await olderResponseWhileNewPagePending();
    await lateBodyOnSamePage();
    for (const kind of ['network', 'json', 'payload']) {
        await failure(kind, true);
        await failure(kind, false);
    }
    await detailOrdering(true);
    await detailOrdering(false);
    for (const kind of ['network', 'json', 'payload']) {
        await detailFailure(true, kind);
        await detailFailure(false, kind);
    }
    await detailAfterTabChange(false);
    await detailAfterTabChange(true);
    console.log('Community catalog request ordering: 19 scenarios passed (9 list, 10 detail)');
})().catch(error => { console.error(error); process.exitCode = 1; });
