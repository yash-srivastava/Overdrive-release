// Playback-time lens dewarp for raw clips (CPU-engine recording). Mirrors
// native PlaybackDewarp (app/src/main/java/com/overdrive/app/ui/view/
// PlaybackDewarp.kt): the recorder's per-tile divide-model dewarp on a 2x2 grid.
// A clip's sidecar `lens.raw=true` opts in; the active `recording.rectifyStrength`
// slider drives k1/k2 at playback. No transcoding, no network per frame.
// Runs in the in-car Chrome 58 WebView too: no `?.`, `??` or class syntax.
(function() {
    'use strict';

    const VERT = [
        'attribute vec2 aPos;',
        'attribute vec2 aUV;',
        'varying vec2 vUV;',
        'void main() { vUV = aUV; gl_Position = vec4(aPos, 0.0, 1.0); }'
    ].join('\n');

    const FRAG = [
        '#ifdef GL_FRAGMENT_PRECISION_HIGH',
        'precision highp float;',
        '#else',
        'precision mediump float;',
        '#endif',
        'uniform sampler2D uTex;',
        'uniform float uK1, uK2, uAspect, uStrip;',
        'uniform vec2 uHalfTexel;',
        'varying vec2 vUV;',
        'vec2 rectify(vec2 t) {',
        '    vec2 nxy = t * 2.0 - 1.0;',
        '    vec2 nxyA = vec2(nxy.x, nxy.y * uAspect);',
        '    float r2 = dot(nxyA, nxyA);',
        '    float invD = 1.0 / (1.0 + uK1 * r2 + uK2 * r2 * r2);',
        '    float a2 = uAspect * uAspect;',
        '    float zoom = 1.0 + uK1 * a2 + uK2 * a2 * a2;',
        '    vec2 srcA = (nxyA * invD) * zoom;',
        '    return clamp(vec2(srcA.x, srcA.y / uAspect) * 0.5 + 0.5, vec2(0.0), vec2(1.0));',
        '}',
        'void main() {',
        '    // Picture space, y down: texture row 0 is the top of the <video> frame.',
        '    vec2 p = vec2(vUV.x, 1.0 - vUV.y);',
        '    // The telemetry strip at the top is shown as recorded, never sampled for the picture.',
        '    if (p.y < uStrip) { gl_FragColor = texture2D(uTex, p); return; }',
        '    vec2 tile = step(vec2(0.5), p) * 0.5;',
        '    vec2 src = clamp(tile + rectify((p - tile) * 2.0) * 0.5, tile + uHalfTexel, tile + 0.5 - uHalfTexel);',
        '    src.y = max(src.y, uStrip + uHalfTexel.y);',
        '    gl_FragColor = texture2D(uTex, src);',
        '}'
    ].join('\n');

    // Media events wake an otherwise idle renderer, including paused seeks and
    // source/size changes. Playing older WebViews still need decoded-frame polling.
    const WAKE_EVENTS = ['loadedmetadata', 'loadeddata', 'canplay', 'play', 'playing',
        'pause', 'seeking', 'seeked', 'ended', 'resize', 'emptied'];
    const STRENGTH_TTL_MS = 10000;
    let strengthCache = null;   // { at, promise }

    function compile(gl, type, src) {
        const sh = gl.createShader(type);
        gl.shaderSource(sh, src);
        gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
            const log = gl.getShaderInfoLog(sh);
            gl.deleteShader(sh);
            throw new Error('lens shader compile: ' + log);
        }
        return sh;
    }

    function frameCount(v) {
        if (typeof v.webkitDecodedFrameCount === 'number') return v.webkitDecodedFrameCount;
        if (typeof v.getVideoPlaybackQuality === 'function') {
            const q = v.getVideoPlaybackQuality();
            if (q && typeof q.totalVideoFrames === 'number') return q.totalVideoFrames;
        }
        return -1;
    }

    function LensDewarp(canvas) {
        this.canvas = canvas;
        this.gl = canvas.getContext('webgl', { preserveDrawingBuffer: false, antialias: false })
            || canvas.getContext('experimental-webgl');
        if (!this.gl || (this.gl.isContextLost && this.gl.isContextLost())) {
            throw new Error('WebGL unavailable');
        }
        const gl = this.gl;

        const vs = compile(gl, gl.VERTEX_SHADER, VERT);
        const fs = compile(gl, gl.FRAGMENT_SHADER, FRAG);
        const prog = gl.createProgram();
        gl.attachShader(prog, vs);
        gl.attachShader(prog, fs);
        gl.linkProgram(prog);
        if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
            throw new Error('lens link: ' + gl.getProgramInfoLog(prog));
        }
        this.prog = prog;
        this.aPos = gl.getAttribLocation(prog, 'aPos');
        this.aUV = gl.getAttribLocation(prog, 'aUV');
        this.uTex = gl.getUniformLocation(prog, 'uTex');
        this.uK1 = gl.getUniformLocation(prog, 'uK1');
        this.uK2 = gl.getUniformLocation(prog, 'uK2');
        this.uAspect = gl.getUniformLocation(prog, 'uAspect');
        this.uStrip = gl.getUniformLocation(prog, 'uStrip');
        this.uHalfTexel = gl.getUniformLocation(prog, 'uHalfTexel');

        this.vbo = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
            -1, -1, 0, 0,
            1, -1, 1, 0,
            -1, 1, 0, 1,
            1, 1, 1, 1,
        ]), gl.STATIC_DRAW);

        this.tex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, this.tex);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);

        this.video = null;
        this.spec = null;
        this.raf = 0;
        this.vfc = 0;
        this.running = false;
        this.failed = false;
        // Called once with a reason when this instance can no longer draw; the
        // host then shows the plain <video> (and replaces the canvas on 'contextlost').
        this.onFail = null;
        // Redraw only for a new video frame, a seek, or a spec/size change.
        this.dirty = true;
        this.lastFrames = -1;
        this.lastTime = -1;
        this.checkUpload = true;
        this._boundTick = this._tick.bind(this);
        this._attachment = 0;
        this._boundFrame = null;
        this._boundMediaWake = this._onMediaWake.bind(this);
        const self = this;
        canvas.addEventListener('webglcontextlost', function() { self._fail('contextlost'); }, false);
    }

    LensDewarp.prototype.attach = function(videoEl, spec) {
        if (this.failed) return false;
        this._stopWatching();
        this.video = videoEl;
        this.spec = spec;
        this.dirty = true;
        this.checkUpload = true;
        this.lastFrames = -1;
        this.lastTime = -1;
        this._syncSize();
        this.running = true;
        const attachment = this._attachment;
        const self = this;
        // A callback from a replaced attachment must not restart its old loop,
        // even on implementations without cancelVideoFrameCallback.
        this._boundFrame = function() {
            if (self._attachment === attachment) self._onVideoFrame();
        };
        WAKE_EVENTS.forEach(function(event) {
            videoEl.addEventListener(event, self._boundMediaWake);
        });
        this._requestFrameCallback();
        this._scheduleTick();
        return true;
    };

    LensDewarp.prototype.updateSlider = function(strength) {
        if (!this.spec) return;
        const t = Math.max(0, Math.min(100, Number(strength) || 0)) / 100;
        this.spec.k1 = 0.30 * t;
        this.spec.k2 = 0.10 * t;
        this.dirty = true;
        this._scheduleTick();
    };

    LensDewarp.prototype._stopWatching = function() {
        this.running = false;
        ++this._attachment;
        this._cancelFrameCallback();
        const v = this.video, wake = this._boundMediaWake;
        if (v) WAKE_EVENTS.forEach(function(event) { v.removeEventListener(event, wake); });
        if (this.raf) cancelAnimationFrame(this.raf);
        this.raf = 0;
        this._boundFrame = null;
    };

    LensDewarp.prototype.detach = function() {
        this._stopWatching();
        this.video = null;
        this.spec = null;
        // Give back the frame-sized texture and drawing buffer while no clip is open.
        const gl = this.gl;
        if (!this.failed && gl && !(gl.isContextLost && gl.isContextLost())) {
            try {
                gl.bindTexture(gl.TEXTURE_2D, this.tex);
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
            } catch (e) { /* best effort */ }
        }
        if (this.canvas) {
            this.canvas.width = 1;
            this.canvas.height = 1;
        }
    };

    LensDewarp.prototype._fail = function(reason) {
        if (this.failed) return;
        this.failed = true;
        this.detach();
        const cb = this.onFail;
        if (typeof cb === 'function') {
            try { cb(reason); } catch (e) { /* host callback */ }
        }
    };

    LensDewarp.prototype._requestFrameCallback = function() {
        const v = this.video;
        if (v && typeof v.requestVideoFrameCallback === 'function') {
            this.vfc = v.requestVideoFrameCallback(this._boundFrame);
        }
    };

    LensDewarp.prototype._cancelFrameCallback = function() {
        const v = this.video;
        if (this.vfc && v && typeof v.cancelVideoFrameCallback === 'function') {
            v.cancelVideoFrameCallback(this.vfc);
        }
        this.vfc = 0;
    };

    LensDewarp.prototype._onVideoFrame = function() {
        this.vfc = 0;
        if (!this.running) return;
        this.dirty = true;
        this._scheduleTick();
        this._requestFrameCallback();
    };

    LensDewarp.prototype._scheduleTick = function() {
        if (this.running && !this.raf) this.raf = requestAnimationFrame(this._boundTick);
    };

    LensDewarp.prototype._onMediaWake = function(event) {
        if (!this.running) return;
        // Play/playing only restart polling; all other events may change the
        // image even without a decoded-frame notification (notably paused seeks).
        if (event.type !== 'play' && event.type !== 'playing') this.dirty = true;
        this._scheduleTick();
    };

    LensDewarp.prototype._syncSize = function() {
        const v = this.video, c = this.canvas;
        if (!v || !c) return;
        const vw = v.videoWidth || c.clientWidth;
        const vh = v.videoHeight || c.clientHeight;
        if (c.width !== vw) { c.width = vw; this.dirty = true; this.checkUpload = true; }
        if (c.height !== vh) { c.height = vh; this.dirty = true; this.checkUpload = true; }
    };

    // Without requestVideoFrameCallback: a decoded-frame counter (or the clock) tells new frames apart.
    LensDewarp.prototype._newFrameWithoutCallback = function(v) {
        const frames = frameCount(v);
        const time = v.currentTime;
        const changed = frames >= 0
            ? (frames !== this.lastFrames || (v.paused && time !== this.lastTime))
            : time !== this.lastTime;
        this.lastFrames = frames;
        this.lastTime = time;
        return changed;
    };

    LensDewarp.prototype._tick = function() {
        this.raf = 0;
        if (!this.running) return;
        const v = this.video, spec = this.spec, gl = this.gl;
        if (!v || !spec) return;
        const hasFrameCallback = typeof v.requestVideoFrameCallback === 'function';
        // Modern engines wake on actual video frames. Older ones poll only
        // during playback/seeking, never indefinitely on a settled pause.
        if (!hasFrameCallback && ((!v.paused && !v.ended) || v.seeking)) this._scheduleTick();
        if (v.readyState < 2 || v.videoWidth === 0) return; // media events wake us when ready

        this._syncSize();
        if (hasFrameCallback) {
            if (!this.dirty) return;
        } else if (!this._newFrameWithoutCallback(v) && !this.dirty) {
            return;
        }
        this.dirty = false;
        gl.viewport(0, 0, this.canvas.width, this.canvas.height);
        gl.useProgram(this.prog);

        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, this.tex);
        try {
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, v);
        } catch (e) {
            if (e && e.name === 'SecurityError') {
                this._fail('tainted');
            } else {
                this.dirty = true;   // decode not ready; retry next frame
                this._scheduleTick();
            }
            return;
        }
        if (this.checkUpload) {
            this.checkUpload = false;
            if (gl.getError() !== gl.NO_ERROR) {
                this._fail('upload');
                return;
            }
        }
        gl.uniform1i(this.uTex, 0);
        gl.uniform1f(this.uK1, spec.k1);
        gl.uniform1f(this.uK2, spec.k2);
        gl.uniform1f(this.uAspect, spec.aspect);
        gl.uniform1f(this.uStrip, spec.strip > 0 && spec.strip < 0.5 ? spec.strip : 0);
        gl.uniform2f(this.uHalfTexel, 0.5 / v.videoWidth, 0.5 / v.videoHeight);

        gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
        gl.enableVertexAttribArray(this.aPos);
        gl.vertexAttribPointer(this.aPos, 2, gl.FLOAT, false, 16, 0);
        gl.enableVertexAttribArray(this.aUV);
        gl.vertexAttribPointer(this.aUV, 2, gl.FLOAT, false, 16, 8);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };

    LensDewarp.specFromSidecar = function(sidecar, strength) {
        const lens = sidecar && sidecar.lens;
        if (!lens || lens.raw !== true || lens.model !== 'divide') return null;
        const t = Math.max(0, Math.min(100, Number(strength) || 0)) / 100;
        if (t === 0) return null;
        const aspect = Number(lens.aspect);
        if (!(aspect >= 0.25 && aspect <= 1.0)) return null;
        // `strip`: rows of the opaque telemetry strip at the top, as a fraction of the frame.
        const rows = Number(lens.strip);
        const frameH = lens.frame ? Number(lens.frame.height) : 0;
        const strip = rows > 0 && frameH > 0 && rows * 2 < frameH ? rows / frameH : 0;
        return { k1: 0.30 * t, k2: 0.10 * t, aspect: aspect, strip: strip };
    };

    // GET /api/settings/unified answers {success, config: {recording: {...}}}.
    LensDewarp.fetchRectifyStrength = function() {
        const now = Date.now();
        if (strengthCache && now - strengthCache.at < STRENGTH_TTL_MS) return strengthCache.promise;
        const promise = fetch('/api/settings/unified', { cache: 'no-store' })
            .then(function(res) { return res.ok ? res.json() : null; })
            .then(function(json) {
                const cfg = json && (json.config || json);
                const rec = cfg && (cfg.recording || (cfg.sections && cfg.sections.recording));
                const v = rec ? Number(rec.rectifyStrength) : NaN;
                return Number.isFinite(v) ? v : 0;
            })
            .catch(function() { return 0; });
        strengthCache = { at: now, promise: promise };
        return promise;
    };

    window.BYD = window.BYD || {};
    window.BYD.LensDewarp = LensDewarp;
})();
