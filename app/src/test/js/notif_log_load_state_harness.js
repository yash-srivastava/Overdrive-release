'use strict';

// Run with node app/src/test/js/notif_log_load_state_harness.js.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/notif-log.js'), 'utf8');

function fixture() {
    const elements = {};
    for (const id of ['nlList', 'nlEmpty', 'nlLoadError', 'nlSkeleton', 'nlCount', 'nlPagination']) {
        elements[id] = { style: { display: 'none' }, innerHTML: '', textContent: '', appendChild() {} };
    }
    const requests = [], toasts = [];
    const sandbox = {
        document: { getElementById: id => elements[id] || null },
        BYDAuth: { fetch: url => new Promise((resolve, reject) => requests.push({ url, resolve, reject })) },
        BYD: { utils: { toast: (message, kind) => toasts.push({ message, kind }) } }
    };
    sandbox.window = sandbox;
    vm.runInNewContext(source, sandbox);
    const log = sandbox.NOTIFLOG;
    log._buildCard = () => ({});
    return { log, elements, requests, toasts };
}

const settle = () => new Promise(resolve => setImmediate(resolve));
const response = (data, ok = true) => ({ ok, status: ok ? 200 : 503, json: () => Promise.resolve(data) });
const empty = { success: true, notifications: [], total: 0, totalPages: 1, page: 1 };
const rows = { success: true, notifications: [{ id: 42 }], total: 1, totalPages: 1, page: 1 };

function assertFailed(f) {
    assert.strictEqual(f.elements.nlSkeleton.style.display, 'none');
    assert.strictEqual(f.elements.nlEmpty.style.display, 'none', 'failure must not claim an empty log');
    assert.strictEqual(f.elements.nlLoadError.style.display, '', 'retryable error is visible');
    assert.strictEqual(f.elements.nlCount.style.visibility, 'hidden', 'failure must not claim a zero count');
    assert.strictEqual(f.elements.nlPagination.style.display, 'none');
    assert.deepStrictEqual(f.toasts, [{ message: 'Could not load notifications', kind: 'error' }]);
}

async function main() {
    for (const [name, finish] of [
        ['network rejection', req => req.reject(new Error('offline'))],
        ['HTTP failure', req => req.resolve(response(rows, false))],
        ['API failure', req => req.resolve(response({ success: false }))],
        ['missing payload', req => req.resolve(response(null))],
        ['missing notifications array', req => req.resolve(response({ success: true }))],
        ['invalid notifications array', req => req.resolve(response({ success: true, notifications: {} }))],
        ['invalid JSON', req => req.resolve({ ok: true, json: () => Promise.reject(new Error('bad JSON')) })]
    ]) {
        const f = fixture();
        f.log.load();
        finish(f.requests[0]);
        await settle();
        assertFailed(f);
        // A local re-render (e.g. selection toggle) must not revive the empty state.
        f.log._render();
        assertFailed(f);
        console.log('PASS ' + name);
    }

    const f = fixture();
    f.log.currentPage = 2;
    f.log.group = 'vehicle.charging';
    f.log.load();
    f.requests[0].reject(new Error('offline'));
    await settle();
    assertFailed(f);
    f.log.load(); // Retry button uses the same load method.
    assert.strictEqual(f.requests[1].url, f.requests[0].url, 'retry retains page and filters');
    assert.strictEqual(f.elements.nlLoadError.style.display, 'none');
    assert.strictEqual(f.elements.nlSkeleton.style.display, '');
    f.requests[1].resolve(response(rows));
    await settle();
    assert.strictEqual(f.log.notifications[0].id, 42);
    assert.strictEqual(f.elements.nlLoadError.style.display, 'none');
    assert.strictEqual(f.elements.nlEmpty.style.display, 'none');
    assert.strictEqual(f.elements.nlCount.textContent, '1 notification');
    assert.strictEqual(f.elements.nlCount.style.visibility, '');
    console.log('PASS retry recovers populated list and count');

    const g = fixture();
    g.log.load();
    g.requests[0].reject(new Error('offline'));
    await settle();
    g.log.load();
    g.requests[1].resolve(response(empty));
    await settle();
    assert.strictEqual(g.elements.nlLoadError.style.display, 'none');
    assert.strictEqual(g.elements.nlEmpty.style.display, '');
    assert.strictEqual(g.elements.nlCount.textContent, 'No notifications yet');
    console.log('PASS successful empty retry displays genuine empty state');

    const prior = fixture();
    prior.log.load();
    prior.requests[0].resolve(response(rows));
    await settle();
    prior.log.load();
    prior.requests[1].reject(new Error('offline'));
    await settle();
    assertFailed(prior);
    assert.strictEqual(prior.log.notifications.length, 0);
    console.log('PASS failed refresh does not claim the populated log is empty');

    const h = fixture();
    h.log.load();
    h.log.load();
    h.requests[1].resolve(response(rows));
    await settle();
    h.requests[0].reject(new Error('obsolete'));
    await settle();
    assert.strictEqual(h.log.notifications[0].id, 42);
    assert.strictEqual(h.elements.nlLoadError.style.display, 'none');
    assert.strictEqual(h.toasts.length, 0);
    console.log('PASS obsolete failure does not replace success');

    const i = fixture();
    i.log.load();
    i.log.load();
    i.requests[1].reject(new Error('offline'));
    await settle();
    i.requests[0].resolve(response(rows));
    await settle();
    assertFailed(i);
    console.log('PASS obsolete success does not dismiss current error');

    const markup = fs.readFileSync(path.join(__dirname,
        '../../main/assets/web/local/notifications.html'), 'utf8');
    assert.match(markup, /id="nlLoadError"[^>]*style="display:none;"/);
    assert.match(markup, /role="alert" data-i18n="notiflog.load_failed"/);
    assert.match(markup, /onclick="NOTIFLOG\.load\(\)" data-i18n="common.retry"/);
    console.log('PASS localized retry and alert markup contract');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
