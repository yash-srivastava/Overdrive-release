'use strict';

// Run with: node app/src/test/js/parking_date_formatter_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const src = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/parking.js'), 'utf8');
const options = { weekday: 'long', month: 'short', day: 'numeric' };
const now = Date.UTC(2026, 2, 29, 12);
const day = 86400000;

function element() {
    const attrs = {};
    return {
        style: {}, children: [], classList: { add() {}, remove() {} },
        setAttribute(k, v) { attrs[k] = v; }, getAttribute(k) { return attrs[k]; },
        appendChild(child) { this.children.push(child); },
        set innerHTML(v) { this.html = v; this.children = []; },
        get innerHTML() { return this.html; }
    };
}

function setup(mode) {
    const nodes = {}, requests = [], counts = { formatters: 0, fallback: 0 };
    let locale = 'en-US';
    class Clock extends Date {
        static now() { return now; }
        toLocaleDateString(...args) { counts.fallback++; return super.toLocaleDateString(...args); }
    }
    const BYD = {
        i18n: { getLang: () => locale, t: key => key },
        clock: { time: () => '12:00' },
        units: { hourSuffix: () => 'h', minuteSuffix: () => 'm' }
    };
    const sandbox = {
        Date: Clock, BYD, window: { BYD },
        Intl: mode === 'missing' ? undefined : { DateTimeFormat: function (...args) {
            counts.formatters++;
            if (mode === 'throw') throw new Error('unsupported Intl');
            return new Intl.DateTimeFormat(...args);
        } },
        document: { getElementById: id => nodes[id] || (nodes[id] = element()),
            querySelectorAll: () => [], createElement: element },
        fetch() { return new Promise(resolve => requests.push(rows => resolve({
            ok: true, json: () => Promise.resolve({ success: true, sessions: rows, total: rows.length })
        }))); }
    };
    vm.runInNewContext(src, sandbox, { filename: 'parking.js' });
    return { page: sandbox.window.Parking, nodes, requests, counts, locale: value => { locale = value; } };
}
function rows(times) {
    return times.map((time, i) => ({ sessionId: 's' + i, startedMs: time, endedMs: now, safeZone: 'test' }));
}
function headings(h) {
    return h.nodes.pkSessions.children.filter(el => el.className === 'day-header').map(el => el.textContent);
}
function expected(times, locale) {
    const output = [];
    for (const time of times) {
        const label = new Date(time).toLocaleDateString(locale, options);
        if (label !== output[output.length - 1]) output.push(label);
    }
    return output;
}
async function load(h, times, append) {
    if (append) h.page.loadMore(); else h.page.quickFilter(30);
    assert.strictEqual(h.requests.length, 1);
    h.requests.shift()(rows(times));
    await new Promise(resolve => setImmediate(resolve));
}

(async function () {
    const times = Array.from({ length: 24 }, (_, i) => now - Math.floor(i / 4) * day);
    const next = [times[23], times[23] - day, times[23] - day];
    const h = setup('normal');
    await load(h, times, false);
    assert.deepStrictEqual(headings(h), expected(times, 'en-US'));
    assert.deepStrictEqual(h.counts, { formatters: 1, fallback: 0 });
    await load(h, next, true);
    assert.deepStrictEqual(headings(h), expected(times.concat(next), 'en-US'));
    assert.deepStrictEqual(h.counts, { formatters: 2, fallback: 0 });
    for (const locale of ['de-DE', 'ar-EG', 'ja-JP', 'th-TH']) {
        h.locale(locale);
        await load(h, times, false);
        assert.deepStrictEqual(headings(h), expected(times, locale));
    }
    assert.deepStrictEqual(h.counts, { formatters: 6, fallback: 0 });
    const invalid = [now, 'invalid', now - day];
    await load(h, invalid, false);
    assert.deepStrictEqual(headings(h), expected(invalid, 'th-TH'));
    assert.deepStrictEqual(h.counts, { formatters: 7, fallback: 1 });
    await load(h, [], false);
    await load(h, [now], false);
    assert.deepStrictEqual(headings(h), expected([now], 'th-TH'));
    assert.deepStrictEqual(h.counts, { formatters: 7, fallback: 2 });
    for (const mode of ['missing', 'throw']) {
        const fallback = setup(mode);
        await load(fallback, times, false);
        assert.deepStrictEqual(headings(fallback), expected(times, 'en-US'));
        assert.strictEqual(fallback.counts.fallback, 24);
        assert.strictEqual(fallback.counts.formatters, mode === 'throw' ? 1 : 0);
    }
    console.log('parking_date_formatter_harness: pagination, locales, invalid dates, empty/single rows and Intl fallbacks passed');
})().catch(err => { console.error(err); process.exitCode = 1; });
