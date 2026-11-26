// Run: node app/src/test/js/cabin_audio_worklet_ring_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let Processor;
const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/cabin-audio-worklet.js'), 'utf8');
vm.runInNewContext(source, {
    AudioWorkletProcessor: class { constructor() { this.port = {}; } },
    ArrayBuffer, Int16Array, Float32Array,
    registerProcessor: (name, ctor) => {
        assert.strictEqual(name, 'cabin-audio-playback');
        Processor = ctor;
    }
});
const actual = new Processor();
// Reference FIFO models the original per-sample semantics without ring indices.
let expected = [];
let primed = false;
let seed = 0x51a77;
function random(n) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed % n;
}
function push(n) {
    const pcm = new Int16Array(n);
    for (let i = 0; i < n; i++) pcm[i] = random(65536) - 32768;
    actual.port.onmessage({data: pcm.buffer});
    expected = expected.concat(Array.from(pcm, x => x / 32768)).slice(-48000);
    checkState();
}
function checkState() {
    assert.strictEqual(actual.size, expected.length);
    assert.strictEqual(actual.primed, primed);
    assert.strictEqual(actual.writeIndex, (actual.readIndex + actual.size) % 48000);
}
function render(n, channels = 1) {
    const output = Array.from({length: channels}, () => new Float32Array(n).fill(42));
    assert.strictEqual(actual.process([], [output]), true);
    if (channels) {
        if (!primed && expected.length >= 3840) primed = true;
        const samples = new Float32Array(n);
        for (let i = 0; i < n; i++) {
            if (primed && expected.length) samples[i] = expected.shift();
            else if (!expected.length) primed = false;
        }
        for (const channel of output) assert.deepStrictEqual(channel, samples);
    }
    checkState();
}
function reset() {
    actual.port.onmessage({data: {type: 'reset'}});
    expected = [];
    primed = false;
    checkState();
}

// Priming, exact drain, refill below threshold, underrun and re-prime.
render(128, 2);
push(3839); render(128); push(1); render(3840);
assert.strictEqual(actual.primed, true);
push(3); render(3); render(1); assert.strictEqual(actual.primed, false);
push(3839); render(128); push(1); render(4000, 2);
// Wrap, overflow, a packet larger than capacity, empty packets and outputs.
push(47000); render(46000); push(5000); render(512, 2);
push(70000); render(48000); render(0); render(128, 0); render(128);
reset(); push(0); render(128);
for (let i = 0; i < 2500; i++) {
    const op = random(100);
    if (op < 42) push(random(4097));
    else if (op < 94) render(random(1025), 1 + random(2));
    else if (op < 98) reset();
    else push(48000 + random(2048));
}
// Compare everything still buffered, including data at both sides of a wrap.
push(3840); render(48001, 2);
console.log('Cabin audio ring: 2500 randomized operations and edge cases passed');
