'use strict';

// Run with: node app/src/test/js/core_modal_accessibility_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
    '../../main/assets/web/shared/core.js'), 'utf8');
// Exercise the actual modal helpers without booting the rest of the dashboard.
const start = source.indexOf('BYD.utils._modalEscBound = false;');
const end = source.indexOf('// Auto-load the language picker', start);
assert(start >= 0 && end > start);

function fixture() {
    const listeners = {};
    const document = { addEventListener(name, fn) { listeners[name] = fn; } };
    function element(tag) {
        const attrs = {}, handlers = {};
        return {
            tag, children: [], style: {}, textContent: '',
            appendChild(child) { this.children.push(child); child.parent = this; },
            setAttribute(key, value) { attrs[key] = String(value); },
            getAttribute(key) { return attrs[key] || null; },
            addEventListener(name, fn) { handlers[name] = fn; },
            click() { handlers.click({ target: this }); },
            focus() { document.activeElement = this; },
            remove() { this.parent.children.splice(this.parent.children.indexOf(this), 1); }
        };
    }
    document.body = element('body');
    document.createElement = element;
    document.activeElement = document.body;
    const BYD = { utils: {}, i18n: { t: key => ({
        'common.notice': 'Hinweis', 'common.confirm': 'Bestaetigen',
        'common.ok': 'OK', 'common.cancel': 'Abbrechen'
    }[key]) } };
    vm.runInNewContext(source.slice(start, end), { BYD, document });
    return { utils: BYD.utils, document, cards: () => document.body.children.map(el => el.children[0]),
        escape() { listeners.keydown({ key: 'Escape', preventDefault() {} }); } };
}

function checkDialog(card, title, body) {
    assert.strictEqual(card.getAttribute('role'), 'dialog');
    assert.strictEqual(card.getAttribute('aria-modal'), 'true');
    const heading = card.children.find(el => el.tag === 'h3');
    assert.strictEqual(heading.textContent, title);
    assert(heading.id, 'visible title needs an ID');
    assert.strictEqual(card.getAttribute('aria-labelledby'), heading.id);
    const paragraph = card.children.find(el => el.tag === 'p');
    if (body) {
        assert.strictEqual(paragraph.textContent, body);
        assert(paragraph.id, 'visible description needs an ID');
        assert.strictEqual(card.getAttribute('aria-describedby'), paragraph.id);
    } else {
        assert.strictEqual(paragraph, undefined);
        assert.strictEqual(card.getAttribute('aria-describedby'), null);
    }
}

async function main() {
    const f = fixture();
    const alert = f.utils.alertDialog({ title: 'Saved', body: 'Settings <saved> & ready.' });
    checkDialog(f.cards()[0], 'Saved', 'Settings <saved> & ready.');
    f.cards()[0].children.slice(-1)[0].children[0].click();
    await alert;
    assert.strictEqual(f.cards().length, 0);
    console.log('PASS alert name, description and dismissal');

    const titleOnly = f.utils.alertDialog({});
    checkDialog(f.cards()[0], 'Hinweis');
    f.escape();
    await titleOnly;
    console.log('PASS localized default title without dangling description');

    const first = f.utils.confirmDialog({ title: 'Confirm', body: 'First message' });
    const second = f.utils.confirmDialog({ title: 'Confirm', body: 'Second message', danger: true });
    const [a, b] = f.cards();
    checkDialog(a, 'Confirm', 'First message');
    checkDialog(b, 'Confirm', 'Second message');
    assert.strictEqual(new Set([a, b].flatMap(card => [card.getAttribute('aria-labelledby'),
        card.getAttribute('aria-describedby')])).size, 4);
    f.escape();
    assert.strictEqual(await second, false);
    checkDialog(f.cards()[0], 'Confirm', 'First message');
    a.children.slice(-1)[0].children[1].click();
    assert.strictEqual(await first, true);
    assert.strictEqual(f.cards().length, 0);
    console.log('PASS stacked dialogs have independent references and results');

    const prompt = f.utils.promptDialog({ body: 'Enter a name', label: 'Name', value: 'Example' });
    const card = f.cards()[0];
    checkDialog(card, 'Hinweis', 'Enter a name');
    const input = card.children.find(el => el.tag === 'input');
    assert.strictEqual(input.getAttribute('aria-label'), 'Name');
    assert.strictEqual(f.document.activeElement, input);
    card.children.slice(-1)[0].children[1].click();
    assert.strictEqual(await prompt, 'Example');
    assert.strictEqual(f.document.activeElement, f.document.body);
    console.log('PASS prompt input label, value and focus restoration');

    const reopened = f.utils.confirmDialog({ body: 'Again' });
    checkDialog(f.cards()[0], 'Bestaetigen', 'Again');
    assert.notStrictEqual(f.cards()[0].getAttribute('aria-labelledby'), a.getAttribute('aria-labelledby'));
    f.document.body.children[0].click();
    assert.strictEqual(await reopened, false);
    assert.strictEqual(f.cards().length, 0);
    console.log('PASS reopened dialog gets fresh references and backdrop cancellation');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
