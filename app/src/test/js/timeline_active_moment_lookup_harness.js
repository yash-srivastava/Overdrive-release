/* Run with node app/src/test/js/timeline_active_moment_lookup_harness.js. */
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const context = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/timeline-view.js'), 'utf8'), context);
const { ActiveMomentLookup, buildMoments, normalizeWindow } = context.BYD.tlview._internals;
const lookup = new ActiveMomentLookup();

// Original activeMomentId loop, including the playing-ID preference, strict end
// boundary, and first-in-source tie rule. Store.merged deduplicates recording IDs.
function original(moments, t, preferredId) {
    let best = null;
    for (const m of moments) {
        const end = m.end != null ? m.end : m.start + 1000;
        if (t < m.start - 250 || t >= end + 250) continue;
        if (m.id === preferredId) return m.id;
        if (!best || m.start > best.start) best = m;
    }
    return best ? best.id : null;
}
function byId(moments) {
    const out = Object.create(null);
    for (const m of moments) out[m.id] = m;
    return out;
}
let comparisons = 0;
function compare(moments, ids, t, preferredId) {
    assert.strictEqual(lookup.find(moments, ids, t, preferredId), original(moments, t, preferredId));
    comparisons++;
}
const base = 1760000000000;
// Event order differs from start order, includes equal starts and a long older clip.
const segments = normalizeWindow({ segments: [
    { id: 'long', type: 'replay', start: base, end: base + 10000 },
    { id: 'a', type: 'sentry', start: base + 1000, end: base + 2000 },
    { id: 'b', type: 'sentry', start: base + 1000, end: base + 3000 },
    { id: 'unknown', type: 'proximity', start: String(base + 4000) },
    { id: 'late', type: 'sentry', start: base + 12000, end: base + 12100 }
] }).segments;
const sample = buildMoments(segments, [{ rec: 'a', kind: 'person', t0: base + 1900 }]);
const padded = sample.concat(Array.from({ length: 300 }, (_, i) => ({
    id: 'later' + i, start: base + 100000 + i * 3000, end: null
})));
for (const moments of [sample, padded]) {
    const ids = byId(moments);
    for (const m of sample) {
        const end = m.end != null ? m.end : m.start + 1000;
        for (const t of [m.start - 251, m.start - 250, m.start - 249, m.start,
            end + 249, end + 250, end + 251, NaN]) {
            for (const id of [null, 'missing', ...sample.map(x => x.id)]) compare(moments, ids, t, id);
        }
    }
    assert.strictEqual(lookup.find(moments, ids, base + 1500, null), 'a');
    assert.strictEqual(lookup.find(moments, ids, base + 1500, 'long'), 'long');
    assert.strictEqual(lookup.find(moments, ids, base + 7000, null), 'long');
}

let seed = 0xa12bc345;
function rand(n) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return (seed >>> 8) % n;
}
for (let run = 0; run < 200; run++) {
    const moments = Array.from({ length: rand(600) }, (_, i) => {
        const start = base + rand(100) * 500;
        return Object.freeze({ id: String(i), start,
            end: rand(8) === 0 ? null : start + (rand(80) - 2) * 250 });
    });
    Object.freeze(moments);
    const ids = byId(moments);
    for (let i = 0; i < 100; i++) {
        const t = base + (rand(180) - 10) * 500 + rand(3) - 1;
        compare(moments, ids, t, rand(2) ? String(rand(180)) : null);
    }
}
// A new source of equal length invalidates the index. Label-only edits do not.
const old = Array.from({ length: 300 }, (_, i) => ({ id: i === 0 ? 'old' : 'old' + i, start: base + i * 3000, end: base + i * 3000 + 1000 }));
assert.strictEqual(lookup.find(old, byId(old), base, null), 'old');
const order = lookup.order;
assert(order, 'large history builds an index');
old[0].label = 'Translated';
assert.strictEqual(lookup.find(old, byId(old), base, null), 'old');
assert.strictEqual(lookup.order, order);
const replacement = Array.from({ length: 300 }, (_, i) => ({ id: i === 0 ? 'new' : 'new' + i, start: base + 5000 + i * 3000, end: null }));
assert.strictEqual(lookup.find(replacement, byId(replacement), base, null), null);
assert.strictEqual(lookup.find(replacement, byId(replacement), base + 5000, null), 'new');
assert.notStrictEqual(lookup.order, order);
assert.strictEqual(lookup.find(old, byId(old), base, 'old'), 'old');
assert.strictEqual(lookup.order, null, 'preferred hit after replacement releases old index without rebuilding');
assert.strictEqual(lookup.find([], {}, base, null), null);

// On a large disjoint history the warm path reads only logarithmically many
// timestamps, even when there is no active clip. A preferred hit uses two reads.
let reads = 0;
const large = Array.from({ length: 10000 }, (_, i) => {
    const start = base + ((i * 7919) % 10000) * 3000;
    return { id: String(i), get start() { reads++; return start; },
        get end() { reads++; return start + 1000; } };
});
const ids = byId(large);
lookup.find(large, ids, base, null);
reads = 0;
for (let i = 0; i < 480; i++) lookup.find(large, ids, base + i * 71257, null);
const warmReads = reads;
assert(warmReads <= 480 * 18, 'warm disjoint queries should not scan the history');
reads = 0;
for (let i = 0; i < 480; i++) original(large, base + i * 71257, null);
const oldReads = reads;
reads = 0;
assert.strictEqual(lookup.find(large, ids, base, '0'), '0');
assert.strictEqual(reads, 2, 'preferred clip should not search the index');
console.log(`${comparisons} reference comparisons passed; 480 warm queries: ${oldReads} -> ${warmReads} timestamp reads`);
