'use strict';

// Run with: node app/src/test/js/notif_log_time_format_harness.js
// Optional baseline source path runs the same label checks without formatter-count assertions.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const baseline = process.argv[2];
const source = baseline || path.resolve(__dirname, '../../main/assets/web/shared/notif-log.js');
const code = fs.readFileSync(source, 'utf8');
const today = new Date(2025, 5, 12, 12).getTime();
const earlier = new Date(2025, 5, 11, 23, 47).getTime();
let now = today;
let lang = 'en-US';
let setups = 0;
let legacyCalls = 0;
let failIntl = false;
let cards = [];
class FixedDate extends Date {
    constructor(...args) { super(...(args.length ? args : [now])); }
    toLocaleTimeString(...args) { legacyCalls++; return super.toLocaleTimeString(...args); }
    toLocaleDateString(...args) { legacyCalls++; return super.toLocaleDateString(...args); }
}
function DateTimeFormat(...args) {
    setups++;
    if (failIntl) throw new Error('Intl unavailable');
    return new Intl.DateTimeFormat(...args);
}
const list = {
    set innerHTML(value) { cards = []; },
    appendChild(card) { cards.push(card); }
};
const sandbox = {
    Date: FixedDate,
    Intl: { DateTimeFormat },
    BYD: { i18n: { getLang: () => lang } },
    document: {
        getElementById: id => id === 'nlList' ? list : null,
        createElement() {
            return { attrs: {}, setAttribute(k, v) { this.attrs[k] = v; },
                addEventListener() {}, querySelector() { return null; } };
        }
    }
};
sandbox.window = sandbox;
vm.runInNewContext(code, sandbox, { filename: source });
const log = sandbox.NOTIFLOG;
function reference(ts) {
    if (!ts) return '';
    const d = new Date(ts), n = new Date(now);
    const sameDay = d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
    try {
        const time = d.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });
        return sameDay ? time : d.toLocaleDateString(lang, { month: 'short', day: 'numeric' }) + ' · ' + time;
    } catch (e) { return d.toLocaleString(); }
}
function render(timestamps, expectedSetups, expectedLegacy, selectMode = false) {
    setups = legacyCalls = 0;
    log.selectMode = selectMode;
    log.notifications = timestamps.map((ts, i) => ({ id: i + 1, title: 'Notice ' + i, ts,
        url: i % 2 === 0 ? '/events.html?file=clip.mp4' : '' }));
    log._render();
    assert.strictEqual(cards.length, timestamps.length);
    cards.forEach((card, i) => {
        const label = reference(timestamps[i]);
        assert.ok(card.innerHTML.includes('<span class="nl-time">' + log._esc(label) + '</span>'));
        assert.strictEqual(card.attrs['aria-label'], !selectMode && i % 2 === 0 ? 'Notice ' + i + ' — ' + label : undefined);
    });
    if (!baseline) {
        assert.strictEqual(setups, expectedSetups, 'formatter constructions');
        assert.strictEqual(legacyCalls, expectedLegacy, 'legacy locale calls');
    }
}
const mixed = Array.from({ length: 20 }, (_, i) => (i % 2 ? today : earlier) - i * 60000);
for (lang of ['en-US', 'de-CH', 'vi-VN', 'ar-EG', 'ja-JP', undefined]) {
    render(mixed, 2, 0);
    render(mixed, 2, 0, true);
    render([today, today - 1000], 1, 0);
}
lang = 'en-US';
render([null, undefined, 0, ''], 0, 0);
render(['invalid', Infinity, today], 1, 4);
render([], 0, 0);
now = new Date(2025, 5, 13, 0, 1).getTime();
render([today, earlier], 2, 0);
failIntl = true;
render(mixed, 1, 40);
failIntl = false;
lang = 'invalid_locale';
render(mixed, 1, 20);
lang = 'en-US';
// A caller constructing a single card outside _render still works.
const direct = log._buildCard({ id: 1, title: 'Direct', ts: today, url: '/events.html' });
assert.strictEqual(direct.attrs['aria-label'], 'Direct — ' + reference(today));
console.log('notif_log_time_format_harness: all checks passed (' + (baseline ? 'baseline labels' : 'optimized') + ')');
