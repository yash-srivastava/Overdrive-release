'use strict';

// Run with: node app/src/test/js/backup_preview_order_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/backup-flow.js'), 'utf8');

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

function fixture() {
    const nodes = new Map(), readers = [], requests = [], toasts = [];
    function element() {
        return {
            handlers: {}, style: {}, disabled: false,
            addEventListener(type, fn) { this.handlers[type] = fn; },
            appendChild(child) {
                if (child.id) nodes.set(child.id, child);
                for (const match of (child.innerHTML || '').matchAll(/\bid="([^"]+)"/g)) {
                    nodes.set(match[1], element());
                }
            },
            remove() {
                nodes.delete(this.id);
                for (const match of (this.innerHTML || '').matchAll(/\bid="([^"]+)"/g)) {
                    nodes.delete(match[1]);
                }
            }
        };
    }
    nodes.set('backupMount', element());
    const BYD = { utils: { toast: (message, kind) => toasts.push({ message, kind }) } };
    const document = {
        readyState: 'loading', head: element(), body: element(),
        createElement: element,
        getElementById: id => nodes.get(id) || null,
        addEventListener(type, fn) { assert.strictEqual(type, 'DOMContentLoaded'); fn(); }
    };
    vm.runInNewContext(source, {
        window: { BYD }, BYD, document, console,
        FileReader: function () {
            readers.push(this);
            this.readAsText = file => { this.file = file; };
        },
        fetch(url, options) {
            const pending = deferred();
            requests.push({ url, options, ...pending });
            return pending.promise;
        }
    });
    return {
        nodes, requests, readers, toasts,
        choose(file) {
            const input = nodes.get('bkFile');
            input.files = file ? [file] : [];
            input.handlers.change({ target: input });
            assert.strictEqual(input.value, '');
        },
        read(index, text) {
            readers[index].result = text;
            readers[index].onload();
        },
        confirm() { nodes.get('bkConfirm').handlers.click(); },
        cancel() { nodes.get('bkCancel').handlers.click(); },
        modal() { return nodes.get('bkModalBg'); }
    };
}

const A = '{"fixture":"A"}', B = '{"fixture":"B"}';
const file = { size: 20 };
const flush = () => new Promise(resolve => setImmediate(resolve));
function valid(request, label) {
    request.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ valid: true, warnings: [label] })) });
}
function start(f, text, index) { f.choose(file); f.read(index, text); }

async function main() {
    // Latest preview owns both the displayed warning and the confirmed payload.
    let f = fixture();
    start(f, A, 0); start(f, B, 1);
    valid(f.requests[1], 'warning B'); await flush();
    valid(f.requests[0], 'warning A'); await flush();
    assert.ok(f.modal().innerHTML.includes('warning B'));
    assert.ok(!f.modal().innerHTML.includes('warning A'));
    f.confirm();
    assert.strictEqual(f.requests[2].url, '/api/backup/import?confirm=true');
    assert.strictEqual(f.requests[2].options.body, B);

    // Older file reads and read failures must not start a preview or show an error.
    f = fixture(); f.choose(file); f.choose(file); f.choose(file);
    f.read(0, A); f.readers[1].onerror(); f.read(2, B);
    assert.strictEqual(f.requests.length, 1);
    assert.strictEqual(f.requests[0].options.body, B);
    assert.strictEqual(f.toasts.length, 0);

    // A response whose body is delayed is also stale after another selection.
    f = fixture(); start(f, A, 0);
    const body = deferred(); f.requests[0].resolve({ ok: true, text: () => body.promise });
    await flush(); start(f, B, 1);
    body.resolve('{"valid":true}'); await flush();
    assert.ok(!f.modal());
    valid(f.requests[1], 'warning B'); await flush();
    assert.ok(f.modal());

    // Obsolete failures are silent, but the current failure still appears.
    f = fixture(); start(f, A, 0); start(f, B, 1);
    f.requests[0].reject(new Error('old')); await flush();
    assert.strictEqual(f.toasts.length, 0);
    f.requests[1].reject(new Error('current')); await flush();
    assert.strictEqual(f.toasts.length, 1);
    assert.ok(f.toasts[0].message.includes('current'));

    // Invalid newer selections supersede pending previews, including size rejection.
    for (const oversized of [false, true]) {
        f = fixture(); start(f, A, 0);
        f.choose(oversized ? { size: 33 * 1024 * 1024 } : file);
        if (!oversized) f.read(1, 'not json');
        valid(f.requests[0], 'warning A'); await flush();
        assert.ok(!f.modal());
        assert.strictEqual(f.toasts.length, 1);
        assert.strictEqual(f.requests.length, 1);
    }

    // Cancelling the picker is not a selection; cancelling the modal must not restore.
    f = fixture(); start(f, A, 0); f.choose(null);
    valid(f.requests[0], 'warning A'); await flush();
    assert.ok(f.modal()); f.cancel();
    assert.ok(!f.modal()); assert.strictEqual(f.requests.length, 1);

    // A new selection replaces an existing unconfirmed preview, not an active restore.
    f = fixture(); start(f, A, 0); valid(f.requests[0], 'warning A'); await flush();
    f.choose(file); assert.ok(!f.modal()); f.read(1, B);
    valid(f.requests[1], 'warning B'); await flush(); f.confirm();
    f.choose(file); assert.strictEqual(f.readers.length, 2);
    assert.strictEqual(f.requests.length, 3);
    assert.strictEqual(f.requests[2].options.body, B);
    console.log('backup_preview_order_harness: 8 scenarios passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
