'use strict';

// Run from repository root: node app/src/test/js/lens_dewarp_lifecycle_harness.js
// A deterministic media + WebGL mock; no decoder/GPU or browser fidelity claims.
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const rafs = new Map();
let nextId = 1;
let ticks = 0;
const context = {
    window: {},
    requestAnimationFrame(fn) { const id = nextId++; rafs.set(id, fn); return id; },
    cancelAnimationFrame(id) { rafs.delete(id); }
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('app/src/main/assets/web/shared/lens-dewarp.js', 'utf8'), context);
const LensDewarp = context.window.BYD.LensDewarp;
function refresh() {
    const pending = Array.from(rafs.entries());
    pending.forEach(([id, fn]) => {
        if (rafs.delete(id)) { ticks++; fn(); }
    });
}
function video(modern, cancellable = true) {
    const listeners = new Map(), callbacks = new Map();
    const v = {
        paused: true, ended: false, seeking: false, readyState: 2,
        videoWidth: 1920, videoHeight: 1080, webkitDecodedFrameCount: 1, currentTime: 0,
        listeners, callbacks,
        addEventListener(name, fn) {
            if (!listeners.has(name)) listeners.set(name, new Set());
            listeners.get(name).add(fn);
        },
        removeEventListener(name, fn) { listeners.get(name).delete(fn); },
        emit(name) { (listeners.get(name) || []).forEach(fn => fn({type: name})); },
        frame() {
            this.webkitDecodedFrameCount++;
            this.currentTime += 1 / 30;
            Array.from(callbacks.entries()).forEach(([id, fn]) => { callbacks.delete(id); fn(); });
        }
    };
    if (modern) {
        v.requestVideoFrameCallback = fn => { const id = nextId++; callbacks.set(id, fn); return id; };
        if (cancellable) v.cancelVideoFrameCallback = id => callbacks.delete(id);
    }
    return v;
}
function fixture() {
    const gl = { draws: 0, uploads: 0, error: 0, failUpload: null, NO_ERROR: 0 };
    ['shaderSource', 'compileShader', 'attachShader', 'linkProgram', 'bindBuffer',
        'bufferData', 'bindTexture', 'texParameteri', 'pixelStorei', 'viewport',
        'useProgram', 'activeTexture', 'uniform1i', 'uniform1f', 'uniform2f',
        'enableVertexAttribArray', 'vertexAttribPointer'].forEach(name => { gl[name] = () => {}; });
    ['createShader', 'createProgram', 'createBuffer', 'createTexture', 'getAttribLocation',
        'getUniformLocation'].forEach(name => { gl[name] = () => 1; });
    gl.getShaderParameter = gl.getProgramParameter = () => true;
    gl.getError = () => gl.error;
    gl.texImage2D = (...args) => {
        if (args.length !== 6) return; // detach's 1x1 release is not a video upload
        gl.uploads++;
        if (gl.failUpload) throw gl.failUpload;
    };
    gl.drawArrays = () => { gl.draws++; };
    const canvasEvents = {};
    const canvas = { width: 1, height: 1, clientWidth: 320, clientHeight: 180,
        getContext: () => gl, addEventListener: (name, fn) => { canvasEvents[name] = fn; } };
    const renderer = new LensDewarp(canvas);
    return {renderer, gl, canvas, canvasEvents};
}
function attach(f, v) {
    assert.strictEqual(f.renderer.attach(v, {k1: .1, k2: .03, aspect: .56, strip: .01}), true);
    assert.strictEqual(rafs.size, 1);
}
function noListeners(v) {
    v.listeners.forEach(set => assert.strictEqual(set.size, 0));
}

// Modern: paused idle and 30 fps playback on a 60 Hz display.
let f = fixture(), v = video(true);
attach(f, v);
refresh();
assert.strictEqual(f.gl.draws, 1);
const pausedTicks = ticks;
for (let i = 0; i < 3600; i++) refresh();
assert.strictEqual(ticks, pausedTicks, 'one idle minute schedules no RAF work');
v.paused = false;
v.emit('play'); v.emit('playing'); refresh();
const playTicks = ticks, playDraws = f.gl.draws;
for (let i = 0; i < 600; i++) {
    if (i % 2 === 0) v.frame();
    refresh();
}
assert.strictEqual(ticks - playTicks, 300);
assert.strictEqual(f.gl.draws - playDraws, 300);
assert.strictEqual(v.callbacks.size, 1);
v.paused = true; v.emit('pause'); refresh();
assert.strictEqual(rafs.size, 0);
let draws = f.gl.draws;
v.seeking = true; v.emit('seeking');
v.currentTime = 10; v.seeking = false; v.emit('seeked');
f.renderer.updateSlider(70); f.renderer.updateSlider(80);
assert.strictEqual(rafs.size, 1, 'seek and slider coalesce');
refresh(); assert.strictEqual(f.gl.draws, draws + 1);
v.videoWidth = 1280; v.videoHeight = 720; v.emit('resize'); refresh();
assert.strictEqual(f.canvas.width, 1280); assert.strictEqual(f.canvas.height, 720);
v.frame(); refresh(); assert.strictEqual(rafs.size, 0, 'late paused frame wakes once');
f.renderer.detach();
assert.strictEqual(v.callbacks.size, 0); noListeners(v);
assert.strictEqual(f.canvas.width, 1); assert.strictEqual(rafs.size, 0);

// Old WebView: frame counter still polled while playing or seeking, then sleeps.
f = fixture(); v = video(false);
attach(f, v); refresh(); assert.strictEqual(rafs.size, 0);
v.paused = false; v.emit('play'); refresh();
assert.strictEqual(rafs.size, 1);
draws = f.gl.draws;
for (let i = 0; i < 120; i++) { if (i % 2 === 0) v.frame(); refresh(); }
assert.strictEqual(f.gl.draws - draws, 60);
v.paused = true; v.emit('pause'); refresh(); assert.strictEqual(rafs.size, 0);
v.seeking = true; v.readyState = 1; v.emit('seeking'); refresh();
assert.strictEqual(rafs.size, 1);
v.currentTime = 12; v.readyState = 2; v.seeking = false; v.emit('seeked');
refresh(); assert.strictEqual(rafs.size, 0);
assert.strictEqual(f.renderer.lastTime, 12);
v.readyState = 0; v.videoWidth = 0; v.emit('emptied'); refresh();
assert.strictEqual(rafs.size, 0);
v.readyState = 2; v.videoWidth = 640; v.videoHeight = 480; v.emit('loadeddata'); refresh();
assert.strictEqual(f.canvas.width, 640); assert.strictEqual(rafs.size, 0);
f.renderer.detach(); noListeners(v);

// Legacy clock-only fallback and ended state.
f = fixture(); v = video(false); delete v.webkitDecodedFrameCount;
attach(f, v); refresh(); v.paused = false; v.emit('play'); refresh();
draws = f.gl.draws; v.currentTime = 1; refresh(); assert.strictEqual(f.gl.draws, draws + 1);
v.ended = true; v.emit('ended'); refresh(); assert.strictEqual(rafs.size, 0);
f.renderer.detach();

// Attach before metadata; transient upload failure retries even while paused.
f = fixture(); v = video(true); v.readyState = 0; v.videoWidth = 0;
attach(f, v); refresh(); assert.strictEqual(f.gl.draws, 0); assert.strictEqual(rafs.size, 0);
v.readyState = 2; v.videoWidth = 1920; v.emit('loadeddata');
f.gl.failUpload = new Error('not ready'); refresh(); assert.strictEqual(rafs.size, 1);
f.gl.failUpload = null; refresh(); assert.strictEqual(f.gl.draws, 1); assert.strictEqual(rafs.size, 0);

// Reattach to the same element and then a different one; no duplicate listeners.
attach(f, v); refresh(); v.listeners.forEach(set => assert.strictEqual(set.size, 1));
assert.strictEqual(v.callbacks.size, 1);
const old = v; v = video(true, false); attach(f, v); noListeners(old);
assert.strictEqual(old.callbacks.size, 0); refresh();
const stale = Array.from(v.callbacks.values())[0];
attach(f, v); refresh(); const pending = v.callbacks.size;
stale(); assert.strictEqual(v.callbacks.size, pending); assert.strictEqual(rafs.size, 0);
f.renderer.detach(); noListeners(v); stale(); assert.strictEqual(rafs.size, 0);

// Existing failure path must stop all scheduling and notify the host once.
for (const reason of ['tainted', 'upload', 'contextlost']) {
    f = fixture(); v = video(true); const failures = [];
    f.renderer.onFail = r => failures.push(r);
    attach(f, v);
    if (reason === 'tainted') f.gl.failUpload = {name: 'SecurityError'};
    if (reason === 'upload') f.gl.error = 1280;
    if (reason === 'contextlost') f.canvasEvents.webglcontextlost();
    refresh(); assert.deepStrictEqual(failures, [reason]);
    assert.strictEqual(rafs.size, 0); assert.strictEqual(v.callbacks.size, 0); noListeners(v);
    assert.strictEqual(f.renderer.attach(v, {}), false);
}
console.log('PASS: dewarp idle, modern cadence (300/600), legacy polling, seeks, readiness, retry, reattach and failures');
