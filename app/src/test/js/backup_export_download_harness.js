'use strict';

// Run with: node app/src/test/js/backup_export_download_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/backup-flow.js'), 'utf8');
const bundle = '{"manifest":{"appVersion":"1.2","deviceModel":"test"}}';

function fixture(options = {}) {
    const nodes = new Map(), toasts = [], timers = [], anchors = [], revoked = [], requests = [];
    const popup = { document: { title: '', body: { textContent: '' } } };
    let clicks = 0, opens = 0;
    function element(tag) {
        return {
            tag, handlers: {}, style: {}, disabled: false,
            addEventListener(type, fn) { this.handlers[type] = fn; },
            appendChild(child) {
                child.parentNode = this;
                if (child.tag === 'a') anchors.push(child);
                for (const match of (child.innerHTML || '').matchAll(/\bid="([^"]+)"/g)) {
                    nodes.set(match[1], element());
                }
            },
            removeChild(child) {
                assert.strictEqual(child.parentNode, this);
                child.parentNode = null;
            },
            click() {
                clicks++;
                if (options.clickFails) throw new Error('download unavailable');
            }
        };
    }
    nodes.set('backupMount', element());
    const BYD = { utils: { toast: (message, kind) => toasts.push({ message, kind }) } };
    const document = {
        readyState: 'loading', head: element(), body: element(),
        createElement: element, getElementById: id => nodes.get(id) || null,
        addEventListener(type, fn) { assert.strictEqual(type, 'DOMContentLoaded'); fn(); }
    };
    vm.runInNewContext(source, {
        window: { BYD, open() {
            opens++;
            if (options.popupThrows) throw new Error('popup unavailable');
            return options.popupBlocked ? null : popup;
        } }, BYD, document, console,
        Blob: function (parts) { assert.strictEqual(parts[0], bundle); },
        URL: {
            createObjectURL() {
                if (options.urlFails) throw new Error('object URL unavailable');
                return 'blob:fixture';
            },
            revokeObjectURL(url) { revoked.push(url); }
        },
        setTimeout(fn, ms) { timers.push({ fn, ms }); },
        fetch(url) {
            requests.push(url);
            return Promise.resolve({ ok: !options.httpFails, status: options.httpFails ? 503 : 200,
                text: () => Promise.resolve(bundle) });
        }
    });
    return {
        nodes, toasts, timers, anchors, revoked, requests, popup,
        get clicks() { return clicks; }, get opens() { return opens; },
        async export() {
            const button = nodes.get('bkExport');
            button.handlers.click();
            assert.strictEqual(button.disabled, true);
            await new Promise(resolve => setImmediate(resolve));
            assert.strictEqual(button.disabled, false);
        }
    };
}

async function main() {
    // Normal download keeps the URL alive until the existing delayed cleanup.
    let f = fixture(); await f.export();
    assert.deepStrictEqual(f.toasts, [{ message: 'Backup downloaded', kind: 'success' }]);
    assert.strictEqual(f.clicks, 1); assert.strictEqual(f.opens, 0);
    assert.ok(f.anchors[0].download.startsWith('overdrive-backup-test-1.2-'));
    assert.strictEqual(f.anchors[0].href, 'blob:fixture');
    assert.deepStrictEqual(f.revoked, []);
    assert.strictEqual(f.timers[0].ms, 1000); f.timers[0].fn();
    assert.strictEqual(f.anchors[0].parentNode, null);
    assert.deepStrictEqual(f.revoked, ['blob:fixture']);

    // A blocked fallback must report failure, never a successful download.
    f = fixture({ urlFails: true, popupBlocked: true }); await f.export();
    assert.deepStrictEqual(f.toasts, [{ message: 'Could not start the download on this browser.', kind: 'error' }]);
    assert.strictEqual(f.opens, 1); assert.strictEqual(f.clicks, 0);
    // Retry remains possible.
    await f.export(); assert.strictEqual(f.requests.length, 2);

    // A throwing fallback likewise reports only the failure.
    f = fixture({ urlFails: true, popupThrows: true }); await f.export();
    assert.deepStrictEqual(f.toasts, [{ message: 'Could not start the download on this browser.', kind: 'error' }]);

    // If clicking the anchor throws, remove it and revoke its URL immediately.
    f = fixture({ clickFails: true, popupBlocked: true }); await f.export();
    assert.strictEqual(f.anchors[0].parentNode, null);
    assert.deepStrictEqual(f.revoked, ['blob:fixture']);
    assert.strictEqual(f.timers.length, 0);
    assert.deepStrictEqual(f.toasts, [{ message: 'Could not start the download on this browser.', kind: 'error' }]);

    // A usable fallback displays the original text for manual saving, not a download success.
    f = fixture({ clickFails: true }); await f.export();
    assert.strictEqual(f.popup.document.body.textContent, bundle);
    assert.ok(f.popup.document.title.endsWith('.json'));
    assert.deepStrictEqual(f.toasts, []);
    assert.deepStrictEqual(f.revoked, ['blob:fixture']);

    // An export HTTP failure never attempts a browser download or popup.
    f = fixture({ httpFails: true }); await f.export();
    assert.deepStrictEqual(f.toasts, [{ message: 'Export failed: HTTP 503', kind: 'error' }]);
    assert.strictEqual(f.clicks, 0); assert.strictEqual(f.opens, 0);
    console.log('backup_export_download_harness: 6 scenarios passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
