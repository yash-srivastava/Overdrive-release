'use strict';

// Run with: node app/src/test/js/sessions_refresh_order_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/sessions.js'), 'utf8');

function element() {
    return {
        children: [], style: {}, disabled: false, hidden: false,
        set textContent(value) { this.text = value; this.children = []; },
        get textContent() { return this.text || ''; },
        appendChild(child) { this.children.push(child); },
        addEventListener(type, callback) { this[type] = callback; }
    };
}

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

function environment() {
    const nodes = new Map();
    const requests = [];
    const document = {
        getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, element());
            return nodes.get(id);
        },
        createElement: element
    };
    document.getElementById('loadError').style.display = 'none';
    const context = {
        document, navigator: { userAgent: '' }, confirm: () => true,
        BYDAuth: { fetch(url, options) {
            const pending = deferred();
            requests.push({ url, options, ...pending });
            return pending.promise;
        } }
    };
    context.window = context;
    vm.runInNewContext(source, context);
    return { requests, page: context.BYD.sessions, node: document.getElementById };
}

const current = { id: 'current', current: true, label: 'Current browser' };
const other = { id: 'other', current: false, label: 'Other browser' };
function respond(request, sessions) {
    request.resolve({ ok: true, status: 200,
        json: () => Promise.resolve({ success: true, sessions }) });
}
function flush() { return new Promise(resolve => setImmediate(resolve)); }
function checkList(env, count) {
    assert.strictEqual(env.node('othersCount').textContent, String(count));
    assert.strictEqual(env.node('sessionList').children.length, count);
    assert.strictEqual(env.node('signOutOthersRow').style.display, count ? '' : 'none');
    assert.strictEqual(env.node('emptyNote').style.display, count ? 'none' : '');
    assert.strictEqual(env.node('currentName').textContent, current.label);
}

async function main() {
    // A pre-revocation snapshot must not repopulate the list after sign-out.
    {
        const env = environment();
        const initial = env.page.refresh();
        respond(env.requests[0], [current, other]);
        await initial;
        checkList(env, 1);
        const older = env.page.refresh();
        env.page.signOutOthers();
        assert.strictEqual(env.requests[2].url, '/api/auth/sessions/revoke-others');
        assert.strictEqual(env.requests[2].options.method, 'POST');
        env.requests[2].resolve({ ok: true, json: () => Promise.resolve({ success: true }) });
        await flush();
        assert.strictEqual(env.requests[3].url, '/api/auth/sessions');
        respond(env.requests[3], [current]);
        await flush();
        checkList(env, 0);
        respond(env.requests[1], [current, other]);
        await older;
        checkList(env, 0);
    }
    // A delayed JSON body is also superseded.
    {
        const env = environment();
        const older = env.page.refresh();
        const body = deferred();
        env.requests[0].resolve({ ok: true, json: () => body.promise });
        await flush();
        const newer = env.page.refresh();
        respond(env.requests[1], [current, other]);
        await newer;
        body.resolve({ success: true, sessions: [current] });
        await older;
        checkList(env, 1);
    }
    // Obsolete failures cannot show the error after a successful refresh.
    for (const fail of [r => r.reject(new Error('offline')),
        r => r.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) }),
        r => r.resolve({ ok: true, json: () => Promise.reject(new Error('bad JSON')) })]) {
        const env = environment();
        const older = env.page.refresh();
        const newer = env.page.refresh();
        respond(env.requests[1], [current]);
        await newer;
        fail(env.requests[0]);
        await older;
        checkList(env, 0);
        assert.strictEqual(env.node('loadError').style.display, 'none');
    }
    // A current error stays visible despite an obsolete success; a later success clears it.
    {
        const env = environment();
        const initial = env.page.refresh();
        respond(env.requests[0], [current, other]);
        await initial;
        const older = env.page.refresh();
        const newer = env.page.refresh();
        env.requests[2].reject(new Error('offline'));
        await newer;
        respond(env.requests[1], [current]);
        await older;
        checkList(env, 1);
        assert.strictEqual(env.node('loadError').style.display, '');
        const recovery = env.page.refresh();
        respond(env.requests[3], [current]);
        await recovery;
        checkList(env, 0);
        assert.strictEqual(env.node('loadError').style.display, 'none');
    }
    console.log('Passed 6 session-list refresh ordering scenarios');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
