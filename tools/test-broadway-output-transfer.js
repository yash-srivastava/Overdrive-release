// Run with Node.js 18+ (no npm packages or external decoder required).
// Uses the bundled WASM decoder inside a simulated worker message host.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Six synthetic 64x48 baseline H.264 frames, generated with:
// ffmpeg -f lavfi -i testsrc2=size=64x48:rate=6 -frames:v 6 -c:v libx264
//   -profile:v baseline -pix_fmt yuv420p -preset ultrafast -tune zerolatency
//   -x264-params keyint=6:scenecut=0:aud=1 -f h264 sample.h264
// Encoder SEI removed; no recorded footage. Digests from the original decoder.
const fixture = Buffer.from(
    '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', 'base64');
const expected = {
    yuv: [
        'c61a2c2eb8b92c7997ec74470a0641673497bd86eaa4b6d2f713d4b712153c48',
        '94fed368abdc2cfc75e8bc492ee38488a4ee7dde74e1167e76673796d6dc9bbd',
        '7ed544c759f5b12078d25cd6fe9cf34b6f52ce16817050cb3978925d4e8ead17',
        '558cbdecf20d5a58ae9c97f3f05ebbce877729098464ba4fa78e5bbb90e8ca4d',
        '6bc6a6a12dd2cef21398643f536dc22f9d2f4720788449ac02d82299543cc5a6',
        '8a4a869aa69951bb5e5200d81489e7ba4e72b0d85b255b5555f5d2e43bf8cb94'
    ],
    rgb: [
        '693838887de6eb9d8e5ba329f6500aa119a276f2f146d1709000c7f3fc1cbec8',
        '36df089a9b3dd5fb76df6005678ece81b2bea4b4ec4c414ba058399b5a9e18df',
        '6e2df16c15fa6e0a54eac05509365a3008d3e3623e1f3c6fe14abc9217cfa57b',
        'e9ab744d3faf700f4e08035bfd129997f3aca6e13ef103b3bcb302ea19278ad3',
        '769eebe65899e994dc0ce3560981a02a6a92a858759f3348a80c72769b3654b2',
        '598e4f940cc981a1a33421fa51ce9fb9b172965e8a8f923d19f3318cd1dfe2c3'
    ]
};
const dir = process.argv[2] || path.join(__dirname, '../app/src/main/assets/web/shared');
const source = fs.readFileSync(path.join(dir, 'Decoder.js'), 'utf8');
const wasm = fs.readFileSync(path.join(dir, 'avc.wasm'));
const starts = Array.from(fixture.toString('latin1').matchAll(/\x00\x00(?:\x00)?\x01/g), m => m.index);
starts.push(fixture.length);

async function decode(rgb, reuseMemory) {
    let listener;
    let timer;
    const frames = [];
    const complete = new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error('Timed out waiting for decoded frames')), 10000);
        const ctx = {
            console, performance, setTimeout, clearTimeout,
            importScripts() {},
            async fetch(name) {
                assert.equal(name, 'avc.wasm');
                return new Response(wasm, {headers: {'Content-Type': 'application/wasm'}});
            },
            addEventListener(name, fn) {
                assert.equal(name, 'message');
                listener = fn;
            },
            postMessage(message, transfer) {
                if (!message.buf) return;
                try {
                    const length = rgb ? 64 * 48 * 4 : 64 * 48 * 3 / 2;
                    assert.equal(message.width, 64);
                    assert.equal(message.height, 48);
                    assert.equal(message.length, length);
                    assert.equal(message.buf.byteLength, length);
                    assert.equal(transfer.length, 1);
                    assert.equal(transfer[0], message.buf);
                    const received = structuredClone(message, {transfer});
                    assert.equal(message.buf.byteLength, 0);
                    assert.equal(received.buf.byteLength, length);
                    assert.ok(received.infos.length > 0);
                    assert.ok(Number.isInteger(received.infos[0].id));
                    assert.ok(Number.isFinite(received.infos[0].finishDecoding));
                    frames.push(received.buf);
                    if (frames.length === 6) resolve();
                } catch (err) {
                    reject(err);
                }
            }
        };
        ctx.self = ctx;
        vm.createContext(ctx);
        vm.runInContext(source, ctx, {filename: 'Decoder.js'});
        listener({data: {type: 'Broadway.js - Worker init', options: {rgb, reuseMemory}}});
        for (let i = 0; i < starts.length - 1; i++) {
            const buf = Uint8Array.from(fixture.subarray(starts[i], starts[i + 1])).buffer;
            listener({data: {buf, length: buf.byteLength, info: {id: i}}});
        }
    });
    try {
        await complete;
        // Check all retained buffers after decoding finishes, not just at delivery.
        assert.deepEqual(frames.map(buf => crypto.createHash('sha256')
            .update(new Uint8Array(buf)).digest('hex')), expected[rgb ? 'rgb' : 'yuv']);
        console.log(`${rgb ? 'RGB' : 'YUV'} reuseMemory=${reuseMemory}: six matching transferred frames`);
    } finally {
        clearTimeout(timer);
    }
}

(async () => {
    for (const rgb of [false, true]) {
        for (const reuseMemory of [false, true]) await decode(rgb, reuseMemory);
    }
})().catch(err => { console.error(err); process.exitCode = 1; });
