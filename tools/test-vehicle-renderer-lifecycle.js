'use strict';

const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const decoders = [];
const loads = [];
const frames = new Map();
const timers = new Map();
let nextId = 1;
const context = {
    console, document: { hidden: false, removeEventListener: function () {} },
    requestAnimationFrame: function (fn) { const id = nextId++; frames.set(id, fn); return id; },
    cancelAnimationFrame: function (id) { frames.delete(id); },
    setTimeout: function (fn) { const id = nextId++; timers.set(id, fn); return id; },
    clearTimeout: function (id) { timers.delete(id); }
};
context.window = {
    removeEventListener: function () {},
    THREE: {
        GLTFLoader: function () {
            this.setDRACOLoader = function () {};
            this.load = function (url, success, progress, failure) { loads.push({ success, failure }); };
        },
        DRACOLoader: function () {
            this.disposals = 0;
            this.setDecoderPath = this.setDecoderConfig = function () {};
            this.dispose = function () { this.disposals++; };
            decoders.push(this);
        },
        Box3: function () {
            this.setFromObject = function () { return this; };
            this.getSize = function () { return { x: 1, y: 1, z: 1 }; };
        },
        Vector3: function () {}
    }
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('app/src/main/assets/web/shared/ev-card-3d.js', 'utf8'), context);

function fixture() {
    const value = Object.create(context.window.OverdriveEvCard3D.prototype);
    Object.assign(value, {
        _disposed: false, _loadGen: 1, _dracoLoaders: [], _renderFrame: null,
        _sizePoll: null, _afterRenderCbs: [], renderer: null, carModel: null,
        bodyPaintMeshes: [], view: 'side', _autoFit: function () {},
        setColor: function () {}, pendingColor: '#C8102E', _observer: null,
        scene: { additions: 0, add: function () { this.additions++; }, remove: function () {} }
    });
    return value;
}

function model() {
    const freed = { geometry: 0, material: 0, texture: 0 };
    const mesh = {
        isMesh: true, geometry: { dispose: function () { freed.geometry++; } },
        material: {
            color: { r: .5, g: .5, b: .5 }, metalness: 0,
            map: { dispose: function () { freed.texture++; } },
            dispose: function () { freed.material++; }
        }
    };
    return {
        freed, scene: { rotation: {}, traverse: function (fn) { fn(mesh); } }
    };
}

let value = fixture();
value.onceAfterRender(function () { throw new Error('Disposed readiness callback ran'); });
value._sizePoll = context.setTimeout(function () {});
value._loadGlb('seal.glb', 1, null);
const decoder = decoders[0];
const late = model();
value.dispose();
assert.strictEqual(decoder.disposals, 1, 'disposal terminates an owned pending decoder');
assert.strictEqual(value._afterRenderCbs.length, 0);
assert.strictEqual(frames.size, 0, 'disposal cancels the pending render frame');
assert.strictEqual(timers.size, 0, 'disposal cancels the fallback size poll');
assert.strictEqual(value._loadGen, 2, 'disposal invalidates in-flight model loads');
loads[0].success({ scene: late.scene });
assert.strictEqual(value.carModel, null, 'late GLB must not repopulate a disposed scene');
assert.deepStrictEqual(late.freed, { geometry: 1, material: 1, texture: 1 });
value.dispose();
assert.strictEqual(decoder.disposals, 1, 'decoder cleanup is idempotent');

value = fixture();
value._loadGlb('old-model.glb', 1, null);
value._loadGen = 2;
const obsolete = model();
loads[1].success({ scene: obsolete.scene });
assert.deepStrictEqual(obsolete.freed, { geometry: 1, material: 1, texture: 1 });
assert.strictEqual(decoders[1].disposals, 1, 'replaced-model completion releases its decoder');
assert.strictEqual(value.scene.additions, 0);

value = fixture();
value._loadGlb('unavailable.glb', 1, null);
loads[2].failure(new Error('fixture load failure'));
assert.strictEqual(decoders[2].disposals, 1, 'load errors release owned decoder resources');
assert.strictEqual(value._dracoLoaders.length, 0);

value = fixture();
value._loadGlb('seal.glb', 1, null);
const loaded = model();
loads[3].success({ scene: loaded.scene });
assert.strictEqual(value.carModel, loaded.scene);
assert.strictEqual(value.scene.additions, 1);
assert.strictEqual(decoders[3].disposals, 1, 'successful loads also release their decoder');
value.dispose();
assert.deepStrictEqual(loaded.freed, { geometry: 1, material: 1, texture: 1 });
assert.strictEqual(decoders[3].disposals, 1);

console.log('Vehicle renderer lifecycle: canceled, replaced, failed and completed GLB loads passed');
