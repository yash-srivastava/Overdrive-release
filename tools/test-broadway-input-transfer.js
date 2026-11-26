// Node.js 18+: node tools/test-broadway-input-transfer.js
// Exercise actual Player and socket handlers with real ArrayBuffer transfer semantics.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const shared = path.join(__dirname, '../app/src/main/assets/web/shared');

async function main() {
    const elements = new Map();
    const location = { href: 'https://example.test/local/live-view.html',
        protocol: 'https:', host: 'example.test' };
    const workers = [];
    class Worker {
        constructor() { this.packets = []; workers.push(this); }
        addEventListener() {}
        postMessage(message, transfers = []) {
            const original = message.buf;
            const received = structuredClone(message, { transfer: transfers });
            if (original) this.packets.push({ original, received });
        }
    }
    class WebSocket {
        static OPEN = 1;
        static CONNECTING = 0;
        constructor() { this.readyState = WebSocket.OPEN; }
    }
    class WorkerUrl extends URL {
        static createObjectURL() { return 'blob:decoder-test'; }
    }
    const context = vm.createContext({ Worker, WebSocket, URL: WorkerUrl, Blob,
        Uint8Array, location, navigator: { userAgent: 'test', maxTouchPoints: 0 },
        console: { log() {}, error: (...args) => { throw new Error(args.join(' ')); } },
        Decoder: { nowValue: () => 0 },
        fetch: async () => ({ text: async () => '"avc.wasm"' }),
        document: {
            createElement: () => ({ style: {} }),
            getElementById: id => {
                if (!elements.has(id)) elements.set(id, { style: {}, appendChild() {},
                    classList: { add() {}, remove() {} } });
                return elements.get(id);
            }
        },
        BYD: { i18n: { t: key => key } }
    });
    context.window = context;
    for (const name of ['Player.js', 'stream.js', 'pip.js']) {
        vm.runInContext(fs.readFileSync(path.join(shared, name), 'utf8'), context, { filename: name });
    }
    let totalBytes = 0;
    for (const module of [context.BYD.stream, context.BYD.pip]) {
        assert.equal(await module.initBroadway(), true);
        if (module === context.BYD.stream) module.connectWebSocket();
        assert.equal(module.ws.binaryType, 'arraybuffer');
        const worker = workers.at(-1);
        for (let i = 0; i < 120; i++) {
            const data = Uint8Array.from({ length: 1 + (i * 7919) % 65536 }, (_, j) => (i + j) & 255);
            const expected = data.slice();
            const buffer = data.buffer;
            module.ws.onmessage({ data: buffer });
            const packet = worker.packets.at(-1);
            assert.equal(packet.original, buffer, 'worker must receive original socket buffer');
            assert.equal(buffer.byteLength, 0, 'socket buffer should be detached');
            assert.equal(packet.received.offset, 0);
            assert.equal(packet.received.length, expected.length);
            assert.deepEqual(new Uint8Array(packet.received.buf), expected);
            totalBytes += expected.length;
        }
        assert.equal(worker.packets.length, 120);
        if (module === context.BYD.stream) assert.equal(module.frameCount, 120);
        // MSE still receives the original, undetached packet without posting to the worker.
        module.decoderMode = 'jmuxer';
        let fed;
        module.jmuxer = { feed: value => { fed = value.video; } };
        const mse = Uint8Array.of(0, 0, 1, 0x65, 42);
        module.ws.onmessage({ data: mse.buffer });
        assert.equal(fed.buffer, mse.buffer);
        assert.deepEqual(fed, mse);
        assert.equal(mse.byteLength, 5);
        assert.equal(worker.packets.length, 120);
    }
    // Generic Player users still get the default copy path for potentially shared input.
    const defaultPlayer = new context.Player({ useWorker: true, webgl: true, render: false });
    const source = Uint8Array.of(9, 8, 7, 6, 5);
    defaultPlayer.decode(source.subarray(1, 4));
    const copied = workers.at(-1).packets[0];
    assert.notEqual(copied.original, source.buffer);
    assert.equal(source.byteLength, 5);
    assert.deepEqual(new Uint8Array(copied.received.buf), Uint8Array.of(8, 7, 6));
    console.log(`PASS: 240 transferred packets (${totalBytes} bytes), MSE and default copy path`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
