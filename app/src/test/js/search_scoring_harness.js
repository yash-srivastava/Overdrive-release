'use strict';

// Run with: node app/src/test/js/search_scoring_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/search.js'), 'utf8');
let translations = Object.create(null);
let normalized = 0;
const window = { BYD: { i18n: { t: key => translations[key] || key } } };
vm.runInNewContext(source.replace('function norm(s) {', 'function norm(s) { countNormalization();')
    .replace('window.OverdriveSearch = {', 'window.scoring = { norm: norm, score: score, queryScorer: queryScorer, scoreEntry: scoreEntry, resolveLabel: resolveLabel, resolveCrumb: resolveCrumb };\n    window.OverdriveSearch = {'), {
    window, document: { readyState: 'loading', addEventListener() {} },
    countNormalization() { normalized++; }
});
const { norm, score, queryScorer, scoreEntry, resolveLabel, resolveCrumb } = window.scoring;

// Unpruned scoring rule from the original implementation.
function reference(q, e) {
    let best = score(q, resolveLabel(e));
    best = Math.max(best, score(q, resolveCrumb(e)) - 12);
    if (e.i18n) best = Math.max(best, score(q, e.i18n.split('.').pop().replace(/_/g, ' ')) - 18);
    best = Math.max(best, score(q, e.label) - 4);
    if (e.type !== 'page') best = Math.max(best, score(q, (e.pageLabel || '') + ' ' + (e.tabLabel || '')) - 14);
    return best <= 0 ? 0 : best + (e.type === 'page' ? 10 : e.type === 'tab' ? 5 : 0);
}

const terms = ['Battery', 'Battery charge', 'Charging', 'Đỗ xe', 'Bật', 'Énergie', 'énergie', 'a-b', 'a_b', 'a.b', 'a › b', 'constructor', '__proto__', 'toString', '', '安全', 'שלום'];
let seed = 93271;
function pick(a) { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return a[seed % a.length]; }
const entries = [];
for (let i = 0; i < 600; i++) {
    entries.push({ type: pick(['page', 'tab', 'setting']), href: 'page.html',
        label: pick(terms), i18n: 'settings.key_' + i, pageLabel: pick(terms), tabLabel: pick(terms),
        pageI18n: 'page.' + (i % 7), tabI18n: 'tab.' + (i % 13) });
}
let comparisons = 0;
for (let locale = 0; locale < 3; locale++) {
    translations = Object.create(null);
    if (locale) for (const e of entries) {
        translations[e.i18n] = pick(terms);
        translations[e.pageI18n] = pick(terms);
        translations[e.tabI18n] = pick(terms);
    }
    for (const raw of terms.concat(['bat', 'do xe', 'b', 'key', 'a b', 'zzzz', 'chrg', 'é', 'key 123'])) {
        const q = norm(raw);
        if (!q) continue;
        const match = queryScorer(q);
        const expected = [], actual = [];
        for (const e of entries) {
            const label = resolveLabel(e);
            const a = reference(q, e), b = scoreEntry(match, e, label);
            assert.strictEqual(b, a, `${raw}: ${JSON.stringify(e)}`);
            if (a > 0) expected.push({ e, s: a, n: label.length });
            if (b > 0) actual.push({ e, s: b, n: label.length });
            comparisons++;
        }
        const rank = (a, b) => b.s - a.s || a.n - b.n;
        assert.deepStrictEqual(actual.sort(rank).slice(0, 30), expected.sort(rank).slice(0, 30));
    }
}
// Repeated hits and misses, including object prototype names, normalize once per query.
normalized = 0;
const match = queryScorer('zzzz');
for (let i = 0; i < 100; i++) for (const hay of ['__proto__', 'constructor', 'zzzz', 'Battery']) {
    assert.strictEqual(match(hay), hay === 'zzzz' ? 100 : 0);
}
assert.strictEqual(normalized, 4);
assert.strictEqual(queryScorer('battery')('Battery'), 100);
assert.strictEqual(normalized, 5);
console.log(`search_scoring_harness: ${comparisons} score comparisons and top-30 rankings passed`);
