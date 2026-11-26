// Run with node app/src/test/js/timeline_strip_static_reuse_harness.js.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const window = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/timeline-strip.js'), 'utf8'), { window });
const { buildModel } = window.BYD.tlstrip._internals;

const staticFields = ['runs', 'runDrive', 'unav', 'seams', 'mT', 'mK', 'mA', 'mRec', 'mLab', 'moments', 'momT'];
function fields(model) {
    const { staticData, ...rest } = model;
    return rest;
}
function check(payload, previous, expectReuse) {
    const next = buildModel(payload, previous && previous.staticData);
    const fresh = buildModel({ ...payload, reuseStatic: false });
    assert.deepStrictEqual(fields(next), fields(fresh), 'reused model equals full rebuild');
    if (previous) {
        assert.strictEqual(next.staticData === previous.staticData, expectReuse);
        staticFields.forEach(key => assert.strictEqual(next[key] === previous[key], expectReuse, key));
    }
    return next;
}

const data = {
    reuseStatic: true, minMs: 0, maxMs: 100000, now: 30000,
    segments: [null, { id: 'normal', start: 0, end: 5000, type: 'normal' },
        { id: 'normal2', start: 5000, end: 7000, type: 'normal' },
        { id: 'sentry', start: 12000, end: 17000, type: 'sentry', classes: ['person'] },
        { id: 'unavailable', start: 15000, end: 20000, available: false },
        { id: 'replay', start: 21000, end: 23000, type: 'replay' },
        { id: 'prox', start: 25000, type: 'proximity' }, { start: NaN }],
    marks: [{ t0: 1500, rec: 'normal', kind: 'vehicle', alert: true }, { t0: NaN }],
    road: [{ t: 18000, kind: 'pothole' }],
    moments: [{ t: 12000, rec: 'sentry', label: 'Person' }, { t: 1500, rec: 'normal', label: 'Vehicle' }],
    live: [{ since: 27000 }], sessions: [{ start: 35000, sentry: 'armed' }],
    reaped: [{ start: 8000, end: 11000, reason: 'user' }],
    states: [{ start: 7000, end: 12000, reason: 'user_off' }], trips: []
};
let model = check(data, null, false);
const firstLive = model.live;
model = check({ ...data, now: 45000 }, model, true);
assert.deepStrictEqual(Array.from(firstLive), [27000, 30000]);
assert.deepStrictEqual(Array.from(model.live), [27000, 45000]);
assert.deepStrictEqual(Array.from(model.armed), [35000, 45000]);
model = check({ ...data, now: 50000, minMs: 10000, maxMs: 40000, live: [], sessions: [],
    states: [], reaped: [], trips: [{ start: 7000, end: 50000 }] }, model, true);
assert.strictEqual(model.hiData, 40000);
assert.strictEqual(model.nLive, 0);
assert.strictEqual(model.nArmed, 0);

// Any replacement of static input invalidates the snapshot, including translated labels.
const translated = { ...data, moments: data.moments.map(m => ({ ...m, label: 'Translated ' + m.label })) };
model = check(translated, model, false);
assert.strictEqual(model.moments[0].label, 'Translated Vehicle');
model = check(translated, model, true);
for (const key of ['segments', 'marks', 'road', 'moments']) {
    const replacement = { ...translated, [key]: translated[key].slice() };
    model = check(replacement, model, false);
    model = check(translated, model, false);
}
// A non-opt-in caller can mutate its source arrays in place and still receives fresh geometry.
const mutable = { ...data, reuseStatic: false, marks: [] };
model = check(mutable, model, false);
const oldCount = model.nM;
mutable.marks.push({ t0: 500, kind: 'person' });
model = check(mutable, model, false);
assert.strictEqual(model.nM, oldCount + 1);
model = check({ ...mutable, reuseStatic: true }, model, false);
model = check({ ...mutable, reuseStatic: false }, model, false);

// Large static marker set: the packed marker arrays keep their identity across clock ticks.
const large = { ...data, marks: Array.from({ length: 10000 }, (_, i) => ({ t0: i * 1000, kind: 'person' })) };
model = check(large, null, false);
for (let i = 1; i <= 20; i++) model = check({ ...large, now: large.now + i * 30000 }, model, true);
console.log('timeline strip static geometry reuse: OK');
