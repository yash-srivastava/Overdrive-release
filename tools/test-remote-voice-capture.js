// Run from the repository root: node tools/test-remote-voice-capture.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname,
    '../app/src/main/assets/web/shared/communicate-worklet.js'), 'utf8');

function processor(rate, target, size, messages) {
    let Processor;
    vm.runInNewContext(source, {
        sampleRate: rate, currentTime: 42,
        AudioWorkletProcessor: class {
            constructor() {
                this.port = { postMessage(message, transfer) {
                    assert.equal(transfer.length, 1);
                    assert.equal(transfer[0], message.pcm);
                    messages.push({ pcm: Array.from(new Int16Array(message.pcm)),
                        rms: message.rms, time: message.contextTime, type: message.type });
                } };
            }
        },
        registerProcessor(name, cls) {
            assert.equal(name, 'remote-voice-capture');
            Processor = cls;
        }
    });
    return new Processor({processorOptions: {targetSampleRate: target, blockSize: size}});
}

// Original general converter, independent of the production specialization.
function reference(block, rate, target) {
    const ratio = rate / Math.min(target, rate);
    const pcm = new Int16Array(Math.max(1, Math.floor(block.length / ratio)));
    let squareSum = 0, offset = 0;
    for (const value of block) squareSum += value * value;
    for (let i = 0; i < pcm.length; i++) {
        const next = Math.min(block.length, Math.floor((i + 1) * ratio));
        let sum = 0, count = 0;
        while (offset < next) { sum += block[offset++]; count++; }
        const sample = Math.max(-1, Math.min(1, count ? sum / count : 0));
        pcm[i] = sample < 0 ? sample * 32768 : sample * 32767;
    }
    return {pcm: Array.from(pcm), rms: Math.sqrt(squareSum / block.length), time: 42, type: 'pcm'};
}

let state = 0x5eac42;
function random() {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 0x100000000;
}
let compared = 0;
for (const [rate, target] of [[48000, 16000], [24000, 8000], [44100, 16000],
    [16000, 16000], [8000, 16000], [96000, 16000]]) {
    for (const size of [1, 2, 3, 4, 5, 6, 127, 128, 2048, 2049]) {
        const messages = [];
        const p = processor(rate, target, size, messages);
        assert.equal(p.process([]), true);
        for (let kind = 0; kind < 20; kind++) {
            const block = Float32Array.from({length: size}, (_, i) => {
                if (kind === 0) return 0;
                if (kind === 1) return i % 2 ? -1 : 1;
                if (kind === 2) return i === size - 1 ? 1 : 0;
                if (kind === 3) return i % 3 === 0 ? NaN : -0;
                if (kind === 4) return i % 2 ? Infinity : -Infinity;
                return random() * 3 - 1.5;
            });
            let offset = 0;
            while (offset < size) {
                const count = Math.min(size - offset, 1 + Math.floor(random() * 260));
                assert.equal(p.process([[block.subarray(offset, offset + count)]]), true);
                offset += count;
                assert.equal(messages.length, offset === size ? 1 : 0);
            }
            assert.deepEqual(messages.shift(), reference(block, rate, target));
            compared++;
        }
        // A single render quantum can complete multiple blocks.
        const combined = Float32Array.from({length: size * 3}, () => random() * 2 - 1);
        assert.equal(p.process([[combined]]), true);
        assert.equal(messages.length, 3);
        for (let i = 0; i < 3; i++) {
            assert.deepEqual(messages[i], reference(combined.subarray(i * size, (i + 1) * size), rate, target));
            compared++;
        }
    }
}
console.log(`${compared} PCM/RMS/message comparisons passed`);
