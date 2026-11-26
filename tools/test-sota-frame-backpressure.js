// Run with node tools/test-sota-frame-backpressure.js (no browser required).
const assert = require('assert');
const paints = new Map();
let nextId = 0;
global.window = {
    requestAnimationFrame(callback) { const id = nextId++; paints.set(id, callback); return id; },
    cancelAnimationFrame(id) { paints.delete(id); }
};
global.EncodedVideoChunk = class {
    constructor(init) { this.type = init.type; }
};
const Player = require('../app/src/main/assets/web/shared/SotaPlayer.js');
const drawn = [], notified = [];
const canvas = {
    width: 0, height: 0,
    getContext() { return { drawImage(frame) { drawn.push(frame.id); }, clearRect() {} }; }
};
const player = new Player(canvas);
player.running = true;
player.onFrame = count => notified.push(count);
function frame(id) {
    return {
        id, displayWidth: 1280, displayHeight: 720, closed: 0,
        close() { assert.strictEqual(++this.closed, 1); }
    };
}
function tick() {
    assert.strictEqual(paints.size, 1);
    const [id, callback] = paints.entries().next().value;
    paints.delete(id);
    callback();
}

// Burst of decoded frames: exactly one draw and no leaked VideoFrames.
const burst = Array.from({ length: 10 }, (_, i) => frame(i));
burst.forEach(f => player.handleFrame(f));
assert.strictEqual(paints.size, 1);
assert.deepStrictEqual(burst.map(f => f.closed), [1,1,1,1,1,1,1,1,1,0]);
assert.strictEqual(drawn.length, 0);
tick();
assert.deepStrictEqual(drawn, [9]);
assert.deepStrictEqual(notified, [1]);
assert(burst.every(f => f.closed === 1));
assert.strictEqual(canvas.width, 1280);
assert.strictEqual(canvas.height, 720);

// Queue overflow resets once, drops dependent pictures until the next IDR.
const submitted = [], configured = [];
let resets = 0;
const decoder = {
    decodeQueueSize: 0,
    decode(chunk) { submitted.push(chunk.type); this.decodeQueueSize++; },
    reset() { resets++; this.decodeQueueSize = 0; },
    configure(config) { configured.push(config.codec); },
    close() {}
};
player.decoder = decoder;
player.codecString = 'avc1.640028';
const sps = Uint8Array.of(0,0,0,1,0x67,0x64,0,0x28);
const pps = Uint8Array.of(0,0,0,1,0x68,1);
const idr = Uint8Array.of(0,0,0,1,0x65,1);
const delta = Uint8Array.of(0,0,0,1,0x41,1);
player.processNAL(sps);
player.processNAL(pps);
player.processNAL(idr);
for (let i = 1; i < Player.MAX_DECODE_QUEUE; i++) player.processNAL(delta);
assert.strictEqual(submitted.length, Player.MAX_DECODE_QUEUE);
const stale = frame(10);
player.handleFrame(stale);
player.processNAL(delta);
assert.strictEqual(stale.closed, 1);
assert.strictEqual(paints.size, 0);
assert.strictEqual(resets, 1);
assert.deepStrictEqual(configured, ['avc1.640028']);
assert.strictEqual(player.hasReceivedKeyframe, false);
player.processNAL(delta);
assert.strictEqual(submitted.length, Player.MAX_DECODE_QUEUE);
player.processNAL(idr);
player.processNAL(delta);
assert.deepStrictEqual(submitted.slice(-2), ['key', 'delta']);
// An IDR arriving at the limit can immediately restart decoding.
decoder.decodeQueueSize = Player.MAX_DECODE_QUEUE;
player.processNAL(idr);
assert.strictEqual(resets, 2);
assert.strictEqual(submitted[submitted.length - 1], 'key');

// Suspend and explicit stop release a pending frame and cancel its paint.
const suspended = frame(11);
player.handleFrame(suspended);
player._suspendForHidden();
assert.strictEqual(suspended.closed, 1);
assert.strictEqual(paints.size, 0);
player.running = true;
const stopped = frame(12);
player.handleFrame(stopped);
player.stop();
assert.strictEqual(stopped.closed, 1);
assert.strictEqual(paints.size, 0);
const late = frame(13);
player.handleFrame(late);
assert.strictEqual(late.closed, 1);
assert.deepStrictEqual(drawn, [9]);
console.log('Frame coalescing, queue recovery and lifecycle checks passed.');
