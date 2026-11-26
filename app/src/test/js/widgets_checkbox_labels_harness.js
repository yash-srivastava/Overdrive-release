'use strict';

// Run: node app/src/test/js/widgets_checkbox_labels_harness.js
// Exercise the real list renderer with DOM doubles and actual language catalogs.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const web = path.resolve(__dirname, '../../main/assets/web');
const source = fs.readFileSync(path.join(web, 'shared/widgets-settings.js'), 'utf8');

function element() {
    const attrs = {}, listeners = {};
    return {
        id: '', textContent: '', disabled: false, checked: false,
        setAttribute: (key, value) => { attrs[key] = String(value); },
        getAttribute: key => attrs[key] == null ? null : attrs[key],
        addEventListener: (key, fn) => { listeners[key] = fn; },
        dispatch: (key, event) => listeners[key](event)
    };
}
function row() {
    const name = element(), desc = element(), input = element();
    const up = element(), down = element();
    return {
        name, desc, input, up, down,
        set innerHTML(markup) {
            assert.ok(markup.includes('type="checkbox"'));
            input.checked = /<input type="checkbox" checked>/.test(markup);
        },
        querySelector(selector) {
            return { '.setting-name': name, '.setting-desc': desc, input }[selector];
        },
        querySelectorAll(selector) {
            assert.strictEqual(selector, '.gw-order button');
            return [up, down];
        }
    };
}
const list = {
    rows: [],
    set innerHTML(value) { assert.strictEqual(value, ''); this.rows = []; },
    appendChild(r) { this.rows.push(r); }
};
const document = {
    getElementById(id) { assert.strictEqual(id, 'gwList'); return list; },
    createElement(tag) { assert.strictEqual(tag, 'div'); return row(); }
};
const BYD = {};
const context = vm.createContext({ document, window: { BYD }, BYD, console });
vm.runInContext(source + '\nthis.settings = WidgetsSettings;', context, { filename: 'widgets-settings.js' });
const settings = context.settings;

function catalog(lang) {
    const data = JSON.parse(fs.readFileSync(path.join(web, 'i18n', lang + '.json'), 'utf8'));
    BYD.i18n = { t: key => key.split('.').reduce((o, k) => o && o[k], data) || key };
}
function checkList(selected) {
    settings.config = { widgets: selected };
    settings.renderList();
    const ordered = selected.concat(Array.from(settings.CATALOG, w => w.id).filter(id => !selected.includes(id)));
    assert.strictEqual(list.rows.length, ordered.length);
    const ids = new Set();
    list.rows.forEach((r, index) => {
        const w = settings.CATALOG.find(w => w.id === ordered[index]);
        assert.strictEqual(r.name.textContent, settings._t(w.key, w.name));
        assert.strictEqual(r.desc.textContent, settings._t(w.desc, w.descFb));
        assert.ok(r.input.getAttribute('aria-labelledby'), w.id + ': missing accessible name reference');
        assert.ok(r.input.getAttribute('aria-describedby'), w.id + ': missing accessible description reference');
        assert.strictEqual(r.input.getAttribute('aria-labelledby'), r.name.id);
        assert.strictEqual(r.input.getAttribute('aria-describedby'), r.desc.id);
        for (const id of [r.name.id, r.desc.id]) {
            assert.ok(id && !ids.has(id), 'unique nonempty ID: ' + id);
            ids.add(id);
        }
        const i = selected.indexOf(w.id);
        assert.strictEqual(r.input.checked, i >= 0);
        assert.strictEqual(r.up.disabled, i <= 0);
        assert.strictEqual(r.down.disabled, i < 0 || i === selected.length - 1);
    });
}

// Fallback rendering before translations load, with both selected and unselected items.
checkList(['trip', 'battery']);
assert.strictEqual(list.rows[0].name.textContent, 'Live trip');
console.log('PASS fallback names and unique associations');

// Rerender as the language changes. The associations stay stable and refer to the new text.
catalog('es');
checkList(['trip', 'battery']);
assert.strictEqual(list.rows[0].name.textContent, 'Viaje en curso');
const tripId = list.rows[0].name.id;
catalog('he');
checkList(['trip', 'battery']);
assert.strictEqual(list.rows[0].name.id, tripId);
assert.notStrictEqual(list.rows[0].name.textContent, 'Live trip');
checkList(['battery', 'trip']);
assert.notStrictEqual(list.rows[1].name.textContent, 'Live trip');
catalog('en');
checkList(['trip', 'battery']);
assert.strictEqual(list.rows[0].name.textContent, 'Live trip');
console.log('PASS localized names and descriptions after rerender');

// Check the existing event wiring without invoking saves or external actions.
const actions = [];
settings.toggleWidget = (id, on) => actions.push(['toggle', id, on]);
settings.move = (id, dir) => actions.push(['move', id, dir]);
list.rows[0].input.dispatch('change', { target: { checked: false } });
list.rows[0].down.dispatch('click');
list.rows[1].up.dispatch('click');
assert.deepStrictEqual(actions, [['toggle', 'trip', false], ['move', 'trip', 1], ['move', 'battery', -1]]);
checkList([]);
assert.ok(list.rows.every(r => !r.input.checked && r.up.disabled && r.down.disabled));
console.log('PASS existing handlers, order and empty selection');
