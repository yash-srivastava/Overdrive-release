'use strict';

// Run with: node app/src/test/js/trips_day_grouping_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const src = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/trips.js'), 'utf8');
const options = { weekday: 'long', month: 'short', day: 'numeric' };

function reference(rows, locale) {
    const groups = {};
    rows.forEach(t => {
        const day = new Date(t.startTime || t.start_time).toLocaleDateString(locale, options);
        if (!groups[day]) groups[day] = [];
        groups[day].push(t);
    });
    return groups;
}

function element() {
    return {
        children: [], dataset: {}, style: {},
        appendChild(child) { this.children.push(child); },
        set innerHTML(value) { this.children = []; }
    };
}

function setup(mode) {
    const counts = { formatters: 0, fallback: 0 };
    let locale = 'en-US';
    class Clock extends Date {
        toLocaleDateString(...args) { counts.fallback++; return super.toLocaleDateString(...args); }
    }
    const nodes = { tripList: element(), tripPrevBtn: element(), tripNextBtn: element(), loadMoreBtn: element() };
    const sandbox = {
        Date: Clock,
        Intl: mode === 'missing' ? undefined : { DateTimeFormat: function (...args) {
            counts.formatters++;
            if (mode === 'throw') throw new Error('unsupported Intl');
            return new Intl.DateTimeFormat(...args);
        } },
        BYD: { i18n: { getLang: () => locale } },
        document: { getElementById: id => nodes[id] || null, createElement: element },
        window: {}
    };
    vm.runInNewContext(src + '\n;this.page = TRIPS;', sandbox, { filename: 'trips.js' });
    const page = sandbox.page;
    page.createTripCard = t => ({ id: t.id, dataset: {} });
    page.updatePeriodSummary = page.updateCostHero = page.decorateParkingGaps = () => {};
    return { page, nodes, counts, setLocale: value => { locale = value; } };
}

function check(h, rows, locale) {
    h.setLocale(locale);
    h.page.trips = rows;
    h.page._pagerTripId = null;
    const groups = reference(rows, locale);
    const expected = Object.keys(groups).flatMap(day => [day, ...groups[day].map(t => t.id)]);
    h.page.renderTripList(rows);
    assert.deepStrictEqual(h.nodes.tripList.children.map(el => el.className === 'day-header' ? el.textContent : el.id), expected);
    const ordered = Object.values(groups).flat().map(t => t.id);
    assert.deepStrictEqual(Array.from(h.page._displayedTrips(), t => t.id), ordered);
    if (ordered.length) {
        h.page._pagerTripId = ordered[0];
        h.page._paintTripPager();
        assert.strictEqual(h.nodes.tripPrevBtn.disabled, true);
        assert.strictEqual(h.nodes.tripNextBtn.disabled, ordered.length === 1);
        h.page._pagerTripId = ordered[ordered.length - 1];
        h.page._paintTripPager();
        assert.strictEqual(h.nodes.tripPrevBtn.disabled, ordered.length === 1);
        assert.strictEqual(h.nodes.tripNextBtn.disabled, true);
    }
}

const times = [Date.UTC(2026, 2, 29, 0, 5), Date.UTC(2025, 2, 30, 0, 5),
    Date.UTC(2026, 2, 29, 23, 55), 'invalid', Date.UTC(2026, 2, 28, 23, 55),
    '2026-03-29T01:30:00+01:00', Date.UTC(2026, 9, 25, 1, 5)];
const rows = times.map((t, i) => i % 2 ? { id: i, start_time: t } : { id: i, startTime: t });
const h = setup('normal');
for (const locale of ['en-US', 'de-DE', 'ar-EG', 'he-IL', 'ja-JP', 'th-TH']) {
    check(h, rows, locale);
    check(h, rows.slice().reverse(), locale);
    check(h, [], locale);
    check(h, rows.slice(0, 1), locale);
}
const stress = Array.from({ length: 1000 }, (_, i) => ({ id: i, startTime: Date.UTC(2026, 0, 1) + i * 3600000 }));
h.setLocale('en-US');
h.counts.formatters = h.counts.fallback = 0;
const grouped = h.page._groupTripsByDay(stress);
assert.deepStrictEqual(JSON.stringify(grouped), JSON.stringify(reference(stress, 'en-US')));
assert.deepStrictEqual(h.counts, { formatters: 1, fallback: 0 });
for (const mode of ['missing', 'throw']) {
    const fallback = setup(mode);
    check(fallback, rows, 'de-DE');
    assert.strictEqual(fallback.counts.fallback, rows.length * 4);
    assert.strictEqual(fallback.counts.formatters, mode === 'throw' ? 4 : 0);
}
console.log('trips_day_grouping_harness: locale, ordering, pager, fallback and 1000-row formatter reuse passed');
