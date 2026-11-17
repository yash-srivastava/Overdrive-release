/* Run with node tools/test-vehicle-canvas-lifecycle.js. No browser dependencies. */
'use strict';

const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync(
    'app/src/main/assets/web/shared/app-shell.js', 'utf8').replace(
    '    ensureCollapseScript();',
    `    window.__vehicleCanvasTest = {
        select: setEvCardAppearance,
        cacheLoading: function () { spriteCacheLoadStarted = true; },
        counts: function () {
            return { aux: auxEv3dInstances.length, waiters: vehicleSelectionWaiters.length,
                     snapshots: Object.keys(snapshotsTaken).length };
        }
    };
    ensureCollapseScript();`);

function deferred() {
    let resolve;
    const promise = new Promise(function (done) { resolve = done; });
    return { promise, resolve };
}

function environment(options) {
    options = options || {};
    const timers = new Map();
    const scripts = [];
    const instances = [];
    let timerId = 0;
    let cacheReads = 0;
    let cacheWrites = 0;
    let paints = 0;
    function element(tag) {
        const listeners = {};
        const attributes = {};
        const node = {
            tagName: tag.toUpperCase(), width: 640, height: 300,
            clientWidth: 640, clientHeight: 300, id: 'vehicle-test',
            setAttribute: function (name, value) { attributes[name] = value; },
            getAttribute: function (name) { return attributes[name]; },
            addEventListener: function (name, fn) { listeners[name] = fn; },
            removeEventListener: function (name, fn) {
                if (listeners[name] === fn) delete listeners[name];
            },
            getContext: function () {
                return { clearRect: function () {}, drawImage: function () { paints++; } };
            },
            cloneNode: function () { const clone = element(tag); clone.id = this.id; return clone; },
            fire: function (name) { if (listeners[name]) listeners[name](); },
            listener: function (name) { return listeners[name]; }
        };
        return node;
    }
    const canvas = element('canvas');
    canvas.parentNode = {
        replaceChild: function (fresh) { fresh.parentNode = this; this.current = fresh; },
        current: canvas
    };
    const document = {
        readyState: 'loading',
        addEventListener: function () {},
        dispatchEvent: function () {},
        getElementById: function () { return null; },
        createElement: element,
        querySelector: function (selector) {
            const marker = selector.match(/^script\[([a-z0-9-]+)\]$/);
            return marker ? scripts.find(function (item) {
                return item.getAttribute(marker[1]) !== undefined;
            }) || null : null;
        },
        head: { appendChild: function (node) { scripts.push(node); } }
    };
    function Renderer(node, settings) {
        this.canvas = node;
        this.view = settings.view;
        this.callbacks = [];
        this.setColor = function (color) { this.color = color; };
        this.setModel = function (model) { this.model = model; };
        this.onceAfterRender = function (callback) { this.callbacks.push(callback); };
        this.dispose = function () { this.disposals = (this.disposals || 0) + 1; this._disposed = true; };
        this.render = function () { const pending = this.callbacks.splice(0); pending.forEach(function (fn) { fn(); }); };
        instances.push(this);
    }
    const cache = {
        get: function () { cacheReads++; return options.get ? options.get() : Promise.resolve(null); },
        paintInto: function () { return options.paint ? options.paint() : Promise.resolve(true); },
        buildKey: function (model, color, view) { return model + color + view; },
        snapshot: function () { return options.snapshot ? options.snapshot() : Promise.resolve({}); },
        put: function () { cacheWrites++; return Promise.resolve(); }
    };
    const window = {
        AndroidBridge: {}, location: { pathname: '/remote-access.html' },
        console, OverdriveEvSpriteCache: options.noCacheModule ? undefined : cache
    };
    if (!options.noRenderer) window.OverdriveEvCard3D = Renderer;
    const context = {
        window, document, console,
        CustomEvent: function () {},
        setInterval: function (fn) { const id = ++timerId; timers.set(id, fn); return id; },
        clearInterval: function (id) { timers.delete(id); },
        setTimeout: function (fn) { const id = ++timerId; timers.set(id, fn); return id; },
        clearTimeout: function (id) { timers.delete(id); }
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    return {
        canvas, scripts, instances, window, timers, Renderer,
        api: window.OverdriveAppShell,
        select: window.__vehicleCanvasTest.select,
        counts: window.__vehicleCanvasTest.counts,
        cacheLoading: window.__vehicleCanvasTest.cacheLoading,
        reads: function () { return cacheReads; },
        writes: function () { return cacheWrites; },
        paints: function () { return paints; }
    };
}

async function settle() {
    for (let i = 0; i < 8; i++) await Promise.resolve();
}

async function run() {
    let env = environment();
    let handle = env.api.mountVehicleCanvas(env.canvas, {});
    assert.strictEqual(env.counts().waiters, 1);
    handle.dispose();
    assert.strictEqual(env.counts().waiters, 0);
    env.select('seal', '#123456');
    await settle();
    assert.strictEqual(env.instances.length, 0, 'disposed deferred selection must never mount');

    const lookup = deferred();
    env = environment({ get: function () { return lookup.promise; } });
    env.select('atto3', '#00AABB');
    let ready = 0;
    handle = env.api.mountVehicleCanvas(env.canvas, { onReady: function () { ready++; } });
    handle.dispose();
    lookup.resolve({ blob: {} });
    await settle();
    assert.strictEqual(env.instances.length, 0);
    assert.strictEqual(env.paints(), 0);
    assert.strictEqual(ready, 0, 'late cache result must not invoke readiness');

    env = environment({ noRenderer: true });
    env.select('seal', '#112233');
    handle = env.api.mountVehicleCanvas(env.canvas, {});
    await settle();
    const script = env.scripts.find(function (item) {
        return item.getAttribute('data-overdrive-ev-card-3d') === '1';
    });
    const lateLoad = script.listener('load');
    assert.strictEqual(env.timers.size, 1);
    handle.dispose();
    assert.strictEqual(env.timers.size, 0, 'dispose cancels the renderer vendor poll');
    env.window.OverdriveEvCard3D = env.Renderer;
    lateLoad();
    assert.strictEqual(env.instances.length, 0, 'late renderer load must not construct a scene');

    env = environment({ noCacheModule: true });
    env.cacheLoading();
    env.select('seal', '#FFFFFF');
    handle = env.api.mountVehicleCanvas(env.canvas, {});
    assert.strictEqual(env.timers.size, 1);
    handle.dispose();
    assert.strictEqual(env.timers.size, 0, 'dispose cancels an already-loading cache module poll');

    env = environment();
    env.select('shark', '#334455');
    handle = env.api.mountVehicleCanvas(env.canvas, { view: 'three-quarter' });
    await settle();
    assert.strictEqual(env.instances.length, 1);
    assert.strictEqual(env.instances[0].model, 'shark');
    assert.strictEqual(env.instances[0].color, '#334455');
    assert.strictEqual(env.counts().snapshots, 1);
    handle.dispose();
    handle.dispose();
    env.instances[0].render();
    await settle();
    assert.strictEqual(env.instances[0].disposals, 1, 'disposal is idempotent');
    assert.strictEqual(env.counts().aux, 0, 'live aux tracking is removed');
    assert.strictEqual(env.counts().snapshots, 0, 'pending snapshot ownership is released');
    assert.strictEqual(env.writes(), 0);

    env = environment({ get: function () { return Promise.resolve({ blob: {} }); } });
    env.select('seal', '#ABCDEF');
    handle = env.api.mountVehicleCanvas(env.canvas, {});
    await settle();
    assert.strictEqual(env.counts().aux, 1);
    const reads = env.reads();
    handle.dispose();
    env.select('atto3', '#654321');
    await settle();
    assert.strictEqual(env.counts().aux, 0, 'sprite aux tracking is removed');
    assert.strictEqual(env.reads(), reads, 'disposed sprite does not repaint on selection changes');

    const decode = deferred();
    env = environment({
        get: function () { return Promise.resolve({ blob: {} }); },
        paint: function () { return decode.promise; }
    });
    env.select('seal', '#778899');
    handle = env.api.mountVehicleCanvas(env.canvas, {});
    await settle();
    handle.dispose();
    decode.resolve(true);
    await settle();
    assert.strictEqual(env.paints(), 0, 'late sprite decode must not paint the disposed canvas');

    env = environment();
    env.select('seal', '#000000');
    let unavailable = 0;
    handle = env.api.mountVehicleCanvas(env.canvas, {
        cacheOnly: true, onUnavailable: function () { unavailable++; }
    });
    await settle();
    assert.strictEqual(unavailable, 1);
    assert.strictEqual(env.instances.length, 0, 'cache-only misses must not create WebGL');
    assert.strictEqual(env.timers.size, 0, 'cache-only misses must not poll for the renderer');
    assert.strictEqual(env.scripts.filter(function (item) {
        return item.getAttribute('data-overdrive-ev-card-3d') === '1';
    }).length, 0);
    handle.dispose();

    let first = true;
    env = environment({ get: function () {
        if (first) { first = false; return Promise.resolve({ blob: {} }); }
        return Promise.resolve(null);
    } });
    env.select('seal', '#CCCCCC');
    handle = env.api.mountVehicleCanvas(env.canvas, {});
    await settle();
    env.select('atto3', '#BBBBBB');
    await settle();
    assert.strictEqual(env.instances.length, 1, 'sprite cache miss upgrades to one live scene');
    assert.notStrictEqual(env.instances[0].canvas, env.canvas);
    assert.strictEqual(env.instances[0].canvas.id, env.canvas.id);
    handle.dispose();
    assert.strictEqual(env.counts().aux, 0);
    assert.strictEqual(env.instances[0].disposals, 1, 'handle owns the upgraded canvas scene');

    const snapshot = deferred();
    env = environment({ snapshot: function () { return snapshot.promise; } });
    env.select('seal', '#444444');
    handle = env.api.mountVehicleCanvas(env.canvas, {});
    await settle();
    env.instances[0].render();
    handle.dispose();
    snapshot.resolve({});
    await settle();
    assert.strictEqual(env.writes(), 0, 'late snapshot completion must not populate the cache');

    console.log('Vehicle canvas lifecycle: 10 cancellation, ownership and cache-only scenarios passed');
}

run().catch(function (error) { console.error(error); process.exitCode = 1; });
