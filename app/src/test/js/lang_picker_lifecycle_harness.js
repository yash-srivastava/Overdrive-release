'use strict';

// Run with: node app/src/test/js/lang_picker_lifecycle_harness.js
// Load the actual picker with a small DOM stub and deterministic timers.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const src = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/lang-picker.js'), 'utf8');

function setup(touch) {
    let now = 0;
    let nextId = 1;
    const timers = new Map();
    function advance(ms) {
        const end = now + ms;
        for (;;) {
            const next = Array.from(timers.entries())
                .filter(entry => entry[1].at <= end)
                .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
            if (!next) break;
            timers.delete(next[0]);
            now = next[1].at;
            next[1].run();
        }
        now = end;
    }

    function node() {
        const handlers = {};
        const classes = new Set();
        return {
            parentNode: null,
            classList: { add: name => classes.add(name), remove: name => classes.delete(name),
                contains: name => classes.has(name) },
            setAttribute() {},
            addEventListener(type, fn) { handlers[type] = fn; },
            fire(type, event) { handlers[type].call(this, event); }
        };
    }

    const sheet = node();
    const chip = node();
    const close = node();
    const search = node();
    let focusCount = 0;
    search.focus = () => { focusCount++; };
    const rows = ['de', 'fr', 'auto'].map(code => Object.assign(node(), {
        getAttribute(name) {
            if (name === 'data-action') return code === 'auto' ? 'auto' : null;
            if (name === 'data-lang') return code === 'auto' ? null : code;
            return null;
        }
    }));
    const list = { querySelectorAll: () => rows };
    const requests = [];
    sheet.querySelector = selector => ({
        '.lang-sheet-close': close,
        '.lang-sheet-title': node(),
        '.lang-sheet-search input': search,
        '.lang-sheet-list': list
    })[selector];
    const body = {
        appendChild(el) { el.parentNode = this; },
        removeChild(el) { assert.strictEqual(el.parentNode, this); el.parentNode = null; }
    };
    const footer = { insertBefore(el) { assert.strictEqual(el, chip); } };
    const document = Object.assign(node(), {
        readyState: 'complete', body,
        getElementById: () => null,
        querySelector: () => footer,
        createElement(tag) {
            if (tag === 'button') return chip;
            // The first div is the sheet, later divs are esc() scratch nodes.
            if (!this.sheetCreated) { this.sheetCreated = true; return sheet; }
            return { get innerHTML() { return String(this.textContent); } };
        }
    });
    const BYD = { i18n: {
        supported: () => ['en', 'de', 'fr', 'it', 'es', 'nl', 'ja', 'ko', 'tr', 'ar', 'he'],
        getLang: () => 'en', getDisplayName: code => code,
        t: key => key, onChange() {},
        setLang(lang) {
            return new Promise(resolve => requests.push({ lang, resolve }));
        }
    } };
    const window = { BYD };
    if (touch) window.ontouchstart = null;
    vm.runInNewContext(src, {
        window, document, BYD, console,
        navigator: { language: 'en-US' }, localStorage: { removeItem() {} },
        setTimeout(run, ms) { const id = nextId++; timers.set(id, { at: now + ms, run }); return id; },
        clearTimeout(id) { timers.delete(id); }
    });
    return {
        open: () => chip.fire('click'), close: () => close.fire('click'),
        escape: () => document.fire('keydown', { key: 'Escape' }),
        backdrop: () => sheet.fire('click', { target: sheet }),
        pick: code => rows[['de', 'fr', 'auto'].indexOf(code)].fire('click'),
        requests,
        advance, attached: () => sheet.parentNode === body,
        visible: () => sheet.classList.contains('open'), focused: () => focusCount
    };
}

// An old close must not detach the sheet after reopening.
{
    const p = setup(false);
    p.open(); p.advance(50); p.close(); p.advance(100); p.open();
    p.advance(180); // Original removal deadline.
    assert.ok(p.attached() && p.visible(), 'reopened sheet must survive previous close timer');
    assert.strictEqual(p.focused(), 0, 'previous opening must not focus the new sheet early');
    p.advance(70);
    assert.strictEqual(p.focused(), 1);
    p.close(); p.advance(279); assert.ok(p.attached());
    p.advance(1); assert.ok(!p.attached());
}

// Close before autofocus; ordinary exit animation still finishes.
{
    const p = setup(false);
    p.open(); p.advance(20); p.escape(); p.advance(250);
    assert.strictEqual(p.focused(), 0, 'closed sheet must not steal focus');
    assert.ok(p.attached() && !p.visible());
    p.advance(30); assert.ok(!p.attached());
    p.open(); p.advance(250); assert.strictEqual(p.focused(), 1);
}

// Repeated close/reopen/close must use the latest removal deadline.
{
    const p = setup(false);
    p.open(); p.close(); p.advance(100); p.open(); p.backdrop();
    p.advance(180); assert.ok(p.attached());
    p.advance(100); assert.ok(!p.attached());
    assert.strictEqual(p.focused(), 0);
}

// Touch clients never schedule desktop autofocus.
{
    const p = setup(true);
    p.open(); p.advance(500); assert.strictEqual(p.focused(), 0);
    p.backdrop(); p.advance(280); assert.ok(!p.attached());
}
async function selectionScenarios() {
    // Both explicit and automatic choices may finish after a close/reopen.
    for (const code of ['de', 'auto']) {
        const p = setup(false);
        p.open(); p.pick(code);
        assert.strictEqual(p.requests[0].lang, code === 'auto' ? 'en-US' : code);
        p.escape(); p.advance(100); p.open();
        p.requests[0].resolve();
        await Promise.resolve();
        p.advance(280);
        assert.ok(p.attached() && p.visible(), 'old selection must not close a reopened sheet');
        p.pick('fr');
        p.requests[1].resolve();
        await Promise.resolve();
        assert.ok(!p.visible(), 'current selection should still close the sheet');
        p.advance(280);
        assert.ok(!p.attached());
    }

    // Superseding a selection in the same sheet invalidates the earlier completion.
    {
        const p = setup(false);
        p.open(); p.pick('de'); p.pick('fr');
        p.requests[0].resolve();
        await Promise.resolve();
        assert.ok(p.visible(), 'earlier choice must not close while latest choice loads');
        p.requests[1].resolve();
        await Promise.resolve();
        assert.ok(!p.visible());
    }

    // An exiting sheet is still attached, but should not start another selection.
    {
        const p = setup(false);
        p.open(); p.close(); p.pick('de');
        assert.strictEqual(p.requests.length, 0);
        p.advance(280);
        assert.ok(!p.attached());
    }
}

selectionScenarios().then(() => {
    console.log('lang_picker_lifecycle_harness: 8 scenarios passed');
}).catch(err => {
    console.error(err);
    process.exitCode = 1;
});
