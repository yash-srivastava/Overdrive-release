// Playback-time "Dashcam view": one WebGL pass composes the cam_* grid clip (leader) and the OEM
// windshield clip (follower) into the recorder's dashcam layout: windshield on top (cropped, never
// dewarped), left | rear | right grid tiles below (rectifyTile as lens-dewarp.js), telemetry strip
// as recorded. Runs in the in-car Chrome 58 WebView too: no `?.`, `??` or class syntax.
(function () {
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
        'uniform sampler2D uGrid;',
        'uniform sampler2D uDvr;',
        'uniform float uK1, uK2, uAspect, uStrip, uSplit, uCrop;',
        'uniform vec2 uHalfTexel, uCell0, uCell1, uCell2;',
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
        '    vec2 p = vec2(vUV.x, 1.0 - vUV.y);',
        '    if (p.y < uStrip) { gl_FragColor = texture2D(uGrid, p); return; }',
        '    if (p.y < uSplit) {',
        '        gl_FragColor = texture2D(uDvr, vec2(p.x, uCrop + (p.y / uSplit) * (1.0 - 2.0 * uCrop)));',
        '        return;',
        '    }',
        '    float by = (p.y - uSplit) / (1.0 - uSplit);',
        '    float bx = p.x * 3.0;',
        '    float cell = clamp(floor(bx), 0.0, 2.0);',
        '    vec2 o = cell < 0.5 ? uCell0 : (cell < 1.5 ? uCell1 : uCell2);',
        '    vec2 src = clamp(o + rectify(vec2(bx - cell, by)) * 0.5, o + uHalfTexel, o + 0.5 - uHalfTexel);',
        '    if (uStrip > 0.0 && o.y < 0.25) src.y = max(src.y, uStrip + uHalfTexel.y);',
        '    gl_FragColor = texture2D(uGrid, src);',
        '}'
    ].join('\n');

    const WAKE_EVENTS = ['loadedmetadata', 'loadeddata', 'canplay', 'play', 'playing',
        'pause', 'seeking', 'seeked', 'ended', 'resize', 'emptied'];
    const LEADER = 0, FOLLOWER = 1;

    function compile(gl, type, src) {
        const sh = gl.createShader(type);
        gl.shaderSource(sh, src);
        gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
            const log = gl.getShaderInfoLog(sh);
            gl.deleteShader(sh);
            throw new Error('dashcam shader compile: ' + log);
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

    function hasVfc(v) { return !!v && typeof v.requestVideoFrameCallback === 'function'; }

    // GpuMosaicRecorder.windshieldCropY: crop a srcAspect picture into the top band of a W:H frame.
    function cropFor(frameAspect, split, srcAspect) {
        const band = frameAspect / split;
        if (!(srcAspect > 0) || !(band > srcAspect)) return 0;
        const c = (1 - srcAspect / band) * 0.5;
        return c < 0 ? 0 : (c > 0.45 ? 0.45 : c);
    }

    // sizeEl: the visible box the canvas fills (the canvas itself may be hidden while it warms up).
    function DashcamView(canvas, sizeEl) {
        this.canvas = canvas;
        this.sizeEl = sizeEl || canvas;
        this.gl = canvas.getContext('webgl', { preserveDrawingBuffer: false, antialias: false })
            || canvas.getContext('experimental-webgl');
        if (!this.gl || (this.gl.isContextLost && this.gl.isContextLost())) throw new Error('WebGL unavailable');
        const gl = this.gl;
        const vs = compile(gl, gl.VERTEX_SHADER, VERT);
        const fs = compile(gl, gl.FRAGMENT_SHADER, FRAG);
        const prog = gl.createProgram();
        gl.attachShader(prog, vs);
        gl.attachShader(prog, fs);
        gl.linkProgram(prog);
        if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error('dashcam link: ' + gl.getProgramInfoLog(prog));
        this.prog = prog;
        this.shaders = [vs, fs];
        this.aPos = gl.getAttribLocation(prog, 'aPos');
        this.aUV = gl.getAttribLocation(prog, 'aUV');
        const u = {};
        ['uGrid', 'uDvr', 'uK1', 'uK2', 'uAspect', 'uStrip', 'uSplit', 'uCrop', 'uHalfTexel', 'uCell0', 'uCell1', 'uCell2']
            .forEach(function (n) { u[n] = gl.getUniformLocation(prog, n); });
        this.u = u;
        gl.useProgram(prog);
        gl.uniform1i(u.uGrid, 0);
        gl.uniform1i(u.uDvr, 1);

        this.vbo = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1]), gl.STATIC_DRAW);
        this.tex = [this._texture(), this._texture()];
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);

        this.videos = [null, null];
        this.vfc = [0, 0];
        this.newFrame = [false, false];
        this.has = [false, false];
        this.checkUpload = [true, true];
        this.lastFrames = [-1, -1];
        this.lastTime = [-1, -1];
        this.fw = 0;
        this.fh = 0;
        this.crop = 0;
        // Follower uploads so far: the host shows the canvas only after a frame uploaded post-seek.
        this.followerUploads = 0;
        this.geo = null;
        this.k1 = 0;
        this.k2 = 0;
        this.running = false;
        this.failed = false;
        this.released = false;
        this.dirty = true;
        this.sizeDirty = true;
        this.raf = 0;
        this._attachment = 0;
        this.onFail = null;   // (reason) once; the host falls back to single-source playback
        this.onFrame = null;  // (which 0 = leader | 1 = follower, mediaTime) per presented frame
        this.onDraw = null;   // () after each composed draw
        const self = this;
        this._boundTick = this._tick.bind(this);
        this._boundWake = function (e) { self._onWake(e); };
        this._boundResize = function () { self.sizeDirty = true; self._schedule(); };
        this._boundFrame = [null, null];
        canvas.addEventListener('webglcontextlost', function () { if (!self.released) self._fail('contextlost'); }, false);
        this.ro = null;
        if (typeof ResizeObserver === 'function') {
            try { this.ro = new ResizeObserver(this._boundResize); this.ro.observe(this.sizeEl); } catch (e) { this.ro = null; }
        }
        if (!this.ro && typeof window !== 'undefined') {
            window.addEventListener('resize', this._boundResize);
            window.addEventListener('orientationchange', this._boundResize);
        }
    }

    DashcamView.cropFor = cropFor;

    DashcamView.prototype._texture = function () {
        const gl = this.gl, t = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        return t;
    };

    // geo: {W, H, aspect, raw, strip (fraction), split, cells: [[x, y] x3]} from the clip's sidecar grid block.
    DashcamView.prototype.setGeometry = function (geo) {
        if (geo === this.geo) return;
        this.geo = geo;
        this.sizeDirty = true;
        this.dirty = true;
        this.fw = 0;   // recompute the crop for this frame aspect
        this._schedule();
    };

    // strength 0..100 (recording.rectifyStrength); raw=false clips sample identity.
    DashcamView.prototype.setStrength = function (strength) {
        const t = Math.max(0, Math.min(100, Number(strength) || 0)) / 100;
        const k1 = 0.30 * t, k2 = 0.10 * t;
        if (k1 === this.k1 && k2 === this.k2) return;
        this.k1 = k1;
        this.k2 = k2;
        this.dirty = true;
        this._schedule();
    };

    DashcamView.prototype.start = function (leader, follower) {
        if (this.failed || this.released) return false;
        if (this.running && this.videos[LEADER] === leader && this.videos[FOLLOWER] === follower) return true;
        this.stop();
        this.videos[LEADER] = leader;
        this.videos[FOLLOWER] = follower;
        this.running = true;
        this.dirty = true;
        const attachment = ++this._attachment;
        const self = this;
        for (let i = 0; i < 2; i++) {
            const which = i, v = this.videos[i];
            this.newFrame[i] = true;
            this.lastFrames[i] = -1;
            this.lastTime[i] = -1;
            this._boundFrame[i] = function (now, md) {
                if (self._attachment === attachment) self._onVideoFrame(which, md);
            };
            WAKE_EVENTS.forEach(function (ev) { v.addEventListener(ev, self._boundWake); });
            this._requestFrame(i);
        }
        this._schedule();
        return true;
    };

    DashcamView.prototype.stop = function () {
        this.running = false;
        ++this._attachment;
        if (this.raf) cancelAnimationFrame(this.raf);
        this.raf = 0;
        for (let i = 0; i < 2; i++) {
            const v = this.videos[i];
            if (!v) continue;
            if (this.vfc[i] && typeof v.cancelVideoFrameCallback === 'function') v.cancelVideoFrameCallback(this.vfc[i]);
            this.vfc[i] = 0;
            const wake = this._boundWake;
            WAKE_EVENTS.forEach(function (ev) { v.removeEventListener(ev, wake); });
            this.videos[i] = null;
            this._boundFrame[i] = null;
        }
    };

    // Frees textures, program and the context itself.
    DashcamView.prototype.release = function () {
        if (this.released) return;
        this.stop();
        this.released = true;
        if (this.ro) { try { this.ro.disconnect(); } catch (e) { /* gone */ } }
        this.ro = null;
        if (typeof window !== 'undefined') {
            window.removeEventListener('resize', this._boundResize);
            window.removeEventListener('orientationchange', this._boundResize);
        }
        const gl = this.gl;
        if (gl && !(gl.isContextLost && gl.isContextLost())) {
            try {
                gl.deleteTexture(this.tex[0]);
                gl.deleteTexture(this.tex[1]);
                gl.deleteBuffer(this.vbo);
                gl.deleteProgram(this.prog);
                gl.deleteShader(this.shaders[0]);
                gl.deleteShader(this.shaders[1]);
                const ext = gl.getExtension('WEBGL_lose_context');
                if (ext) ext.loseContext();
            } catch (e) { /* best effort */ }
        }
        this.canvas.width = 1;
        this.canvas.height = 1;
    };

    DashcamView.prototype._fail = function (reason) {
        if (this.failed) return;
        this.failed = true;
        this.stop();
        const cb = this.onFail;
        if (typeof cb === 'function') {
            try { cb(reason); } catch (e) { /* host callback */ }
        }
    };

    DashcamView.prototype._requestFrame = function (i) {
        const v = this.videos[i];
        if (hasVfc(v) && this._boundFrame[i]) this.vfc[i] = v.requestVideoFrameCallback(this._boundFrame[i]);
    };

    DashcamView.prototype._onVideoFrame = function (i, md) {
        this.vfc[i] = 0;
        if (!this.running) return;
        this.newFrame[i] = true;
        this._schedule();
        const v = this.videos[i];
        const cb = this.onFrame;
        if (typeof cb === 'function') cb(i, md && typeof md.mediaTime === 'number' ? md.mediaTime : v.currentTime);
        // The host may have stopped us from its callback.
        if (this.running && this.videos[i] === v) this._requestFrame(i);
    };

    DashcamView.prototype._onWake = function (e) {
        if (!this.running) return;
        if (e.type !== 'play' && e.type !== 'playing') {
            const t = e.target || e.currentTarget;
            if (t === this.videos[FOLLOWER]) this.newFrame[FOLLOWER] = true;
            else if (t === this.videos[LEADER]) this.newFrame[LEADER] = true;
            else { this.newFrame[LEADER] = true; this.newFrame[FOLLOWER] = true; }
        }
        this._schedule();
    };

    DashcamView.prototype._schedule = function () {
        if (this.running && !this.raf) this.raf = requestAnimationFrame(this._boundTick);
    };

    DashcamView.prototype._polled = function (i) {
        const v = this.videos[i];
        const frames = frameCount(v), time = v.currentTime;
        const changed = frames >= 0 ? (frames !== this.lastFrames[i] || (v.paused && time !== this.lastTime[i])) : time !== this.lastTime[i];
        this.lastFrames[i] = frames;
        this.lastTime[i] = time;
        return changed;
    };

    DashcamView.prototype._resize = function () {
        this.sizeDirty = false;
        const g = this.geo, c = this.canvas, box = this.sizeEl;
        if (!g) return;
        const dpr = (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
        const cw = box.clientWidth * dpr, ch = box.clientHeight * dpr;
        let w = g.W, h = g.H;
        if (cw > 0 && ch > 0) {
            const s = Math.min(cw / g.W, ch / g.H, 1);
            w = Math.max(2, Math.round(g.W * s));
            h = Math.max(2, Math.round(g.H * s));
        }
        if (c.width !== w) { c.width = w; this.dirty = true; }
        if (c.height !== h) { c.height = h; this.dirty = true; }
    };

    // Uploads a video only when it has a new frame; false = stop this tick.
    DashcamView.prototype._upload = function (i) {
        const v = this.videos[i], gl = this.gl;
        if (!this.newFrame[i] || v.readyState < 2 || !v.videoWidth) return true;
        gl.activeTexture(i === LEADER ? gl.TEXTURE0 : gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, this.tex[i]);
        try {
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, v);
        } catch (e) {
            if (e && e.name === 'SecurityError') { this._fail('tainted'); return false; }
            this._schedule();   // decode not ready; retry next frame
            return true;
        }
        if (this.checkUpload[i]) {
            this.checkUpload[i] = false;
            if (gl.getError() !== gl.NO_ERROR) { this._fail('upload'); return false; }
        }
        this.newFrame[i] = false;
        this.has[i] = true;
        this.dirty = true;
        if (i === FOLLOWER) {
            this.followerUploads++;
            if (v.videoWidth !== this.fw || v.videoHeight !== this.fh) {
                this.fw = v.videoWidth;
                this.fh = v.videoHeight;
                const g = this.geo;
                this.crop = g ? cropFor(g.W / g.H, g.split, this.fw / this.fh) : 0;
            }
        }
        return true;
    };

    DashcamView.prototype._tick = function () {
        this.raf = 0;
        if (!this.running) return;
        const L = this.videos[LEADER], F = this.videos[FOLLOWER], gl = this.gl, g = this.geo;
        if (!L || !F || !g) return;
        const vL = hasVfc(L), vF = hasVfc(F);
        // Without frame callbacks, poll only while something plays or seeks.
        if ((!vL && ((!L.paused && !L.ended) || L.seeking)) || (!vF && ((!F.paused && !F.ended) || F.seeking))) this._schedule();
        if (!vL && this._polled(LEADER)) this.newFrame[LEADER] = true;
        if (!vF && this._polled(FOLLOWER)) {
            this.newFrame[FOLLOWER] = true;
            const cb = this.onFrame;
            if (typeof cb === 'function') cb(FOLLOWER, F.currentTime);
            if (!this.running) return;
        }
        if (this.sizeDirty) this._resize();
        if (!this._upload(LEADER) || !this._upload(FOLLOWER)) return;
        if (!this.dirty || !this.has[LEADER] || !this.has[FOLLOWER]) return;
        this.dirty = false;
        const u = this.u;
        gl.viewport(0, 0, this.canvas.width, this.canvas.height);
        gl.useProgram(this.prog);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, this.tex[LEADER]);
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, this.tex[FOLLOWER]);
        gl.uniform1f(u.uK1, g.raw ? this.k1 : 0);
        gl.uniform1f(u.uK2, g.raw ? this.k2 : 0);
        gl.uniform1f(u.uAspect, g.aspect);
        gl.uniform1f(u.uStrip, g.strip);
        gl.uniform1f(u.uSplit, g.split);
        gl.uniform1f(u.uCrop, this.crop);
        gl.uniform2f(u.uHalfTexel, 0.5 / (L.videoWidth || g.W), 0.5 / (L.videoHeight || g.H));
        gl.uniform2f(u.uCell0, g.cells[0][0], g.cells[0][1]);
        gl.uniform2f(u.uCell1, g.cells[1][0], g.cells[1][1]);
        gl.uniform2f(u.uCell2, g.cells[2][0], g.cells[2][1]);
        gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
        gl.enableVertexAttribArray(this.aPos);
        gl.vertexAttribPointer(this.aPos, 2, gl.FLOAT, false, 16, 0);
        gl.enableVertexAttribArray(this.aUV);
        gl.vertexAttribPointer(this.aUV, 2, gl.FLOAT, false, 16, 8);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        const cb = this.onDraw;
        if (typeof cb === 'function') cb();
    };

    window.BYD = window.BYD || {};
    window.BYD.DashcamView = DashcamView;
})();
