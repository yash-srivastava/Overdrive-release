// Run with node tools/test-sota-nal-views.js [path/to/original/SotaPlayer.js].
// WebCodecs is mocked; the chunk constructor copies only the supplied view.
const assert = require('assert');
const path = require('path');
global.window = {};
global.EncodedVideoChunk = class {
    constructor(init) {
        this.type = init.type;
        this.data = new Uint8Array(init.data);
    }
};
const Player = require('../app/src/main/assets/web/shared/SotaPlayer.js');
const Original = process.argv[2] ? require(path.resolve(process.argv[2])) : null;

function join(...parts) {
    const data = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let offset = 0;
    for (const part of parts) { data.set(part, offset); offset += part.length; }
    return data;
}
function nal(type, payload, short = false) {
    return join(short ? [0, 0, 1, type] : [0, 0, 0, 1, type], payload);
}
function makePlayer(Clazz) {
    const player = new Clazz({ getContext: () => ({}) });
    const output = [], configs = [];
    player.running = true;
    player.codecString = 'avc1.42C01F';
    player.decoder = {
        reset() { configs.push('reset'); },
        configure(config) { configs.push(config.codec); },
        decode(chunk) { output.push(chunk); }
    };
    return { player, output, configs };
}
const sps = nal(0x67, [0x64, 0, 0x28, 0x55]);
const pps = nal(0x68, [0x11, 0x22, 0x33], true);
const idr = nal(0x65, new Uint8Array(16384).fill(0x55));
const delta = nal(0x41, new Uint8Array(16384).fill(0x66), true);
const sei = nal(0x06, [0x12, 0x34, 0x56]);
const changedSps = nal(0x67, [0x4d, 0, 0x1f, 0x55], true);
const changedPps = nal(0x68, [0x44, 0x55, 0x66]);

// Nonzero byteOffset, mixed start codes, no start code, and empty input.
const packet = join([9, 8, 7], sps, pps, sei, idr);
const view = packet.subarray(3);
const test = makePlayer(Player);
const units = test.player.splitNALUnits(view);
assert.deepStrictEqual(units.map(u => [...u]), [sps, pps, sei, idr].map(u => [...u]));
assert(units.every(u => u.buffer === packet.buffer));
assert.strictEqual(units[0].byteOffset, 3);
assert.strictEqual(test.player.splitNALUnits(new Uint8Array(0)).length, 0);
const raw = Uint8Array.of(7, 8, 9);
assert.strictEqual(test.player.splitNALUnits(raw)[0], raw);

// Real processNAL and decodeChunk, with a copying chunk constructor. Ensure
// source reuse/mutation cannot alter a chunk or the retained codec parameters.
test.player.ingest(view);
assert.deepStrictEqual(test.configs, ['reset', 'avc1.640028']);
assert.strictEqual(test.output.length, 1);
assert.strictEqual(test.output[0].type, 'key');
assert.deepStrictEqual(test.output[0].data, join(sps, pps, idr));
assert.strictEqual(test.player.sps.buffer.byteLength, sps.length);
assert.strictEqual(test.player.pps.buffer.byteLength, pps.length);
assert.notStrictEqual(test.player.sps.buffer, packet.buffer);
assert.notStrictEqual(test.player.pps.buffer, packet.buffer);
const savedSps = test.player.sps, savedPps = test.player.pps;
packet.fill(0xff);
assert.deepStrictEqual(test.player.sps, sps);
assert.deepStrictEqual(test.player.pps, pps);
assert.deepStrictEqual(test.output[0].data, join(sps, pps, idr));
test.player.ingest(join(sps, pps, idr));
assert.strictEqual(test.player.sps, savedSps);
assert.strictEqual(test.player.pps, savedPps);
assert.strictEqual(test.configs.length, 2);
test.player.ingest(delta);
assert.strictEqual(test.output[2].type, 'delta');
assert.deepStrictEqual(test.output[2].data, delta);
test.player.ingest(join(changedSps, changedPps, delta));
assert.deepStrictEqual(test.configs, ['reset', 'avc1.640028', 'reset', 'avc1.4D001F']);
assert.strictEqual(test.output.length, 3); // wait for IDR after SPS change
test.player.ingest(idr);
assert.deepStrictEqual(test.output[3].data, join(changedSps, changedPps, idr));

// Count JS slice bytes, excluding WebCodecs' own required copy and the
// unchanged super-frame assembly. A minute at 30 messages/sec, 1 IDR/sec.
function workload(Clazz) {
    const { player } = makePlayer(Clazz);
    let bytes = 0, copies = 0, decoded = 0;
    player.decoder.decode = () => { decoded++; };
    const originalSlice = Uint8Array.prototype.slice;
    Uint8Array.prototype.slice = function (...args) {
        const result = originalSlice.apply(this, args);
        bytes += result.byteLength; copies++;
        return result;
    };
    try {
        for (let i = 0; i < 1800; i++) {
            player.ingest(i % 30 === 0 ? join(sps, pps, idr) : delta);
        }
    } finally { Uint8Array.prototype.slice = originalSlice; }
    assert.strictEqual(decoded, 1800);
    return { bytes, copies, decoded };
}
const after = workload(Player);
assert.deepStrictEqual(after, { bytes: sps.length + pps.length, copies: 2, decoded: 1800 });
console.log('Optimized minute:', after);

// Optional direct comparison to the original, including malformed/boundary
// messages. Preserve existing splitter semantics rather than change parsing.
if (Original) {
    const before = workload(Original);
    assert.strictEqual(before.bytes, 60 * (sps.length + pps.length + idr.length) + 1740 * delta.length);
    assert.strictEqual(before.copies, 1920);
    console.log('Original minute:', before);
    const a = makePlayer(Original), b = makePlayer(Player);
    let seed = 123456789;
    const next = () => (seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0);
    const pieces = [sps, pps, changedSps, changedPps, sei, idr.subarray(0, 64), delta.subarray(0, 64), Uint8Array.of(0, 0, 1, 0x65), raw, new Uint8Array(0)];
    for (let i = 0; i < 5000; i++) {
        const parts = [];
        const count = next() % 6;
        for (let j = 0; j < count; j++) parts.push(pieces[next() % pieces.length]);
        const msg = join([99, 98], ...parts).subarray(2);
        assert.deepStrictEqual(a.player.splitNALUnits(msg), b.player.splitNALUnits(msg));
        a.player.ingest(msg); b.player.ingest(msg);
        assert.deepStrictEqual(a.output.splice(0), b.output.splice(0));
        assert.deepStrictEqual(a.configs.splice(0), b.configs.splice(0));
        assert.deepStrictEqual(a.player.sps, b.player.sps);
        assert.deepStrictEqual(a.player.pps, b.player.pps);
        assert.strictEqual(a.player.hasReceivedKeyframe, b.player.hasReceivedKeyframe);
    }
    console.log('5,000 original/optimized packet comparisons passed.');
}
console.log('NAL view, codec ownership, configuration and allocation checks passed.');
