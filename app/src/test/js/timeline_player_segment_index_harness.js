// Run with Node.js. Compare indexed helpers with their original linear path.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const sourcePath = path.join(__dirname, '../../main/assets/web/shared/timeline-player.js');
function load(file) {
    const sandbox = {};
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), sandbox);
    return sandbox.BYD.tlplayer._internals;
}
const api = load(sourcePath);
// Optional original source for direct compatibility checks; otherwise the
// unindexed helper calls are the linear reference implementation.
const ref = process.argv[2] ? load(process.argv[2]) : api;
const equal = (a, b) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b));
let seed = 0x7f123456;
function random(n) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed % n;
}
function compare(segs, blocked, w, b, ends) {
    equal(api.sourcesAt(segs, w, blocked, ends), ref.sourcesAt(segs, w, blocked));
    const actual = api.landing(w, segs, blocked, ends), expected = ref.landing(w, segs, blocked);
    equal(actual, expected);
    assert.strictEqual(actual.seg, expected.seg);
    assert.strictEqual(actual.blockedSeg, expected.blockedSeg);
    equal(api.nextPlayableStart(segs, w, blocked, ends), ref.nextPlayableStart(segs, w, blocked));
    equal(api.hasFootage(segs, w, b, blocked, ends), ref.hasFootage(segs, w, b, blocked));
    equal(api.frameTarget(null, null, segs, w, ends), ref.frameTarget(null, null, segs, w));
}
for (let trial = 0; trial < 500; trial++) {
    const segs = [], blocked = new Map();
    for (let i = 0, n = random(200); i < n; i++) {
        const start = random(100) * 1000 - 10000;
        const end = [null, undefined, NaN, Infinity, start, start - 1, start + random(50000) + 1][random(7)];
        const s = {id: 'clip' + i, fp: 'fp', start, end, available: random(4) !== 0,
            type: random(2) ? 'normal' : 'oemDashcam'};
        segs.push(s);
        if (random(5) === 0) blocked.set(s.id + '|' + s.fp, true);
    }
    segs.sort((a,b) => a.start - b.start);
    const ends = api.buildSegmentEnds(segs);
    for (let q = 0; q < 40; q++) {
        const s = segs[random(segs.length || 1)];
        const w = q % 4 === 0 && s ? s.start : q % 4 === 1 && s && Number.isFinite(s.end)
            ? s.end - (q % 2 ? api.SEAM_MS : 0) : random(160000) - 20000;
        compare(segs, blocked, w, w + random(20000), ends);
        // Blocking can change independently of the segment snapshot.
        if (s) {
            const k = s.id + '|' + s.fp;
            if (blocked.has(k)) blocked.delete(k); else blocked.set(k, true);
            s.available = !s.available;
        }
    }
}
// A nested clip can keep a prefix alive beyond thousands of expired clips.
const dense = Array.from({length: 4096}, (_,i) => ({id: 'c'+i, fp:'f', start:i*1000, end:i*1000+800, type:'normal'}));
dense[0].end = 3000000;
dense[1].layout = 'single';
const denseEnds = api.buildSegmentEnds(dense);
for (const w of [-1,0,800,1000,2999999,3000000,4095800,Infinity,-Infinity,NaN]) {
    compare(dense, new Map([['c0|f', true]]), w, w+1000, denseEnds);
}
// Exercise actual data replacement and source publication, not only pure helpers.
const player = Object.create(api.Player.prototype);
const events = [];
Object.assign(player, {blocked: new Map(), curItem:null, gapHold:null, tb:null,
    _kick(){}, _updateState(){}, _currentWall(){return 5000;}, _emit(name,payload){events.push([name,payload]);}});
player._setData({segments:[{id:'old',fp:'f',start:0,end:10000,type:'normal'}]});
assert.strictEqual(player.segmentEnds[0], 10000);
equal(events[0][1].families, ['mosaic']);
player._setData({segments:[{id:'new',fp:'f',start:4000,end:6000,type:'oemDashcam'}]});
assert.strictEqual(player.segmentEnds[0], 6000);
equal(events[1][1].families, ['oem']);
player.blocked.set('new|f',true);
player._emitSources(true);
equal(events[2][1].families, []);
console.log('Passed 20,000 randomized five-helper comparisons, nested/boundary cases, and player data refresh/blocking.');
