/**
 * SOTA H.264 Player - In-Band Configuration Strategy
 * Glues SPS+PPS+IDR together as one "Super Chunk" for WebCodecs.
 */
(function(global) {
    class SotaPlayer {
        constructor(canvas, url) {
            if (typeof canvas === 'string') {
                this.canvas = document.getElementById(canvas);
            } else {
                this.canvas = canvas;
            }
            this.ctx = this.canvas.getContext('2d');
            this.url = url || null;
            this.ws = null;
            this.decoder = null;
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            this.running = false;
            this.frameCount = 0;
            this.onConnected = null;
            this.onDisconnected = null;
            // Optional notification for consumers that upload the canvas as a texture.
            this.onCanvasChanged = null;
            // Tracks the user's intent to view (vs. transient running state), so
            // we can suspend the stream when the tab is hidden and auto-resume
            // when it returns — without the user re-tapping the camera.
            this.userWantsStream = false;
            // Auto-reconnect backoff (capped) so a dead tunnel link doesn't
            // reconnect every 2 s forever, hammering cellular.
            this.reconnectAttempts = 0;
            this.pendingFrame = null;
            this.paintRequest = null;

            // Suspend the H.264 stream while the tab is backgrounded. Without
            // this, a live-view tab left open over the tunnel keeps pulling
            // video (and reconnect-looping) over cellular 24/7 — the single
            // largest data drain. Mirrors performance.js / road-sense.js which
            // already stop their feeds on hide.
            if (typeof document !== 'undefined' && document.addEventListener) {
                this._onVisibility = () => {
                    if (document.hidden) {
                        if (this.running) this._suspendForHidden();
                    } else if (this.userWantsStream && !this.running) {
                        this.reconnectAttempts = 0;
                        this.start();
                    }
                };
                this._onPageHide = () => { if (this.running) this._suspendForHidden(); };
                document.addEventListener('visibilitychange', this._onVisibility);
                window.addEventListener('pagehide', this._onPageHide);
            }
        }

        // Tear down the live socket/decoder but REMEMBER the user wanted it, so
        // the visibilitychange handler can resume on return. Distinct from
        // stop(), which is an explicit user stop and clears userWantsStream.
        _suspendForHidden() {
            this.running = false;
            this._clearFrameWatchdog();
            this._clearStallWatchdog();
            this._discardPendingFrame();
            if (this.ws) { try { this.ws.close(); } catch (e) {} this.ws = null; }
            if (this.decoder) { try { this.decoder.close(); } catch (e) {} this.decoder = null; }
            if (this.onDisconnected) this.onDisconnected();
        }

        static isSupported() {
            return "VideoDecoder" in window && "EncodedVideoChunk" in window;
        }

        toggle() {
            if (this.running) this.stop();
            else this.start();
            return this.running;
        }

        connect(url) {
            this.url = url;
            this.start();
        }

        start() {
            if (this.running) { this.stop(); return; }
            if (!SotaPlayer.isSupported()) { console.error("[SotaPlayer] WebCodecs not supported!"); return; }

            this.userWantsStream = true;
            this.running = true;
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            this.frameCount = 0;
            // Clear the fatal latch + error tally so an explicit restart gets a
            // genuine retry rather than inheriting the last attempt's verdict.
            this._failed = false;
            this.decodeErrors = 0;
            this.bytesReceived = 0;
            this.connectWebSocket();
        }

        connectWebSocket() {
            if (!this.url) return;
            
            // Reset decoder state for fresh start
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            
            this.ws = new WebSocket(this.url);
            this.ws.binaryType = "arraybuffer";
            const socket = this.ws;

            this.ws.onopen = () => {
                if (this.ws !== socket || !this.running) return;
                this._discardPendingFrame();
                this.reconnectAttempts = 0;  // healthy link — reset backoff
                // Never carry a decoder across connections. WebCodecs closes
                // the VideoDecoder BEFORE it invokes the error callback, so a
                // decoder that errored on the previous socket is already dead
                // and every decode() on it throws. Rebuild from scratch; the
                // first SPS of the new session reconfigures it.
                if (this.decoder) { try { this.decoder.close(); } catch (e) {} this.decoder = null; }
                this.initDecoder();
                this._armFrameWatchdog();
                this._armStallWatchdog();
                if (this.onConnected) this.onConnected();
            };
            this.ws.onmessage = (e) => {
                if (this.ws === socket) this.ingest(new Uint8Array(e.data));
            };
            this.ws.onclose = () => {
                if (this.ws !== socket) return;
                this._discardPendingFrame();
                if (this.decoder) { try { this.decoder.close(); } catch (e) {} this.decoder = null; }
                // Retire the watchdogs for this connection: a closed socket is a
                // link event, not a decode failure, and the next onopen re-arms
                // them. Leaving them armed could misfire during the reconnect gap.
                this._clearFrameWatchdog();
                this._clearStallWatchdog();
                if (this.onDisconnected) this.onDisconnected();
                // Auto-reconnect with capped exponential backoff (2s → 30s) so a
                // dead tunnel link doesn't reconnect every 2s forever over
                // cellular. Skip while the tab is hidden (visibility handler owns
                // resume). Reset on a successful onopen above.
                if (this.running && !(typeof document !== 'undefined' && document.hidden)) {
                    this.reconnectAttempts = (this.reconnectAttempts || 0) + 1;
                    var backoff = Math.min(30000, 2000 * Math.pow(2, this.reconnectAttempts - 1));
                    setTimeout(() => {
                        if (this.running && this.ws === socket && !(typeof document !== 'undefined' && document.hidden)) {
                            this.connectWebSocket();
                        }
                    }, backoff);
                }
            };
            // Do NOT zero `running` here: a WebSocket error is always followed
            // by a close event, and onclose owns the capped-backoff reconnect.
            // Clearing running on error would block that reconnect and leave a
            // stuck dead player (live-view's onDisconnected does not reconnect).
            this.ws.onerror = () => { /* close event follows; onclose owns reconnect */ };
        }

        /**
         * Create and configure a fresh VideoDecoder. `codec` is optional: a
         * rebuild after a decode error passes the codec string derived from
         * the SPS already in hand so the next IDR decodes without waiting for
         * another SPS.
         */
        initDecoder(codec) {
            if (this.decoder) return;
            const decoder = new VideoDecoder({
                output: (frame) => {
                    if (this.decoder === decoder) this.handleFrame(frame);
                    else frame.close();
                },
                error: (e) => { if (this.decoder === decoder) this.handleError(e); }
            });
            this.decoder = decoder;
            // The server REQUESTS Baseline/3.1, but KEY_PROFILE is only a hint —
            // plenty of encoders ignore it and emit Main/High. So this initial
            // config is provisional; processNAL reconfigures from the real SPS.
            this._configure(codec || "avc1.42C01F");
        }

        /**
         * Configure the decoder, retrying without hardware acceleration.
         * "prefer-hardware" is meant to be a preference, but on some mobile GPUs
         * (notably a range of Mali/Adreno Android builds) configure() throws or
         * the decoder errors immediately when no hardware path exists for the
         * profile — and software decode would have worked fine. Falling back
         * keeps those devices playing instead of showing a black canvas.
         */
        _configure(codec) {
            const attempts = [
                { codec: codec, hardwareAcceleration: "prefer-hardware", optimizeForLatency: true },
                { codec: codec, optimizeForLatency: true },
                { codec: "avc1.42C01F", optimizeForLatency: true }
            ];
            for (let i = 0; i < attempts.length; i++) {
                try {
                    this.decoder.configure(attempts[i]);
                    this.codecString = attempts[i].codec;
                    this.softwareFallback = i > 0;
                    return true;
                } catch (e) {
                    if (i === attempts.length - 1) {
                        // Nothing configured — surface it so the caller can switch
                        // decoders rather than sit on a permanently dead canvas.
                        this._fail('configure failed: ' + (e && e.message ? e.message : e));
                        return false;
                    }
                }
            }
            return false;
        }

        /**
         * Escalate if bytes arrive but no frame ever gets painted. Some mobile
         * decoders accept the configure() and every chunk, then emit nothing and
         * raise no error — indistinguishable from a working player except that
         * the canvas stays black. Nothing else in the pipeline can catch that.
         */
        _armFrameWatchdog() {
            this._clearFrameWatchdog();
            this._frameWatchdog = setTimeout(() => {
                this._frameWatchdog = null;
                if (!this.running || this._failed) return;
                if (this.frameCount > 0) return;          // decoding fine
                if (!this.bytesReceived) return;          // link problem, not decode
                this._fail('no frame decoded within 8s (' + this.bytesReceived + ' bytes in)');
            }, 8000);
        }

        _clearFrameWatchdog() {
            if (this._frameWatchdog) {
                clearTimeout(this._frameWatchdog);
                this._frameWatchdog = null;
            }
        }

        /**
         * Running-stream stall watchdog. The first-frame watchdog above only
         * covers the window before the first paint; once frames have flowed,
         * nothing else notices when they stop. That happens when the server
         * retires the stream encoder (quality change from another viewer,
         * idle shutdown) or when a tunnel link dies without a TCP reset: the
         * socket stays "open" and the player shows a frozen "Live" picture
         * for as long as the server keeps the socket (60 s).
         *
         * If frames have been painted on this player (frameCount > 0) and no
         * frame has arrived for STALL_TIMEOUT_MS while the socket is OPEN,
         * close the socket. onclose owns the capped-backoff reconnect, and
         * the fresh attach requests a new IDR from the server. Armed per
         * connection in onopen and cleared in onclose/stop/suspend so it can
         * never fire across the reconnect gap. Checked every 2 s, so a
         * healthy stream (frames every <=1 s at 1 fps) never trips it.
         */
        _armStallWatchdog() {
            this._clearStallWatchdog();
            // Treat the open itself as activity: on a reconnect frameCount is
            // still >0 from the previous socket, and the stale timestamp from
            // that socket must not count against this one.
            this._lastFrameAt = SotaPlayer._now();
            this._stallWatchdog = setInterval(() => {
                if (!this.running || this._failed || !this.ws) return;
                if (this.ws.readyState !== WebSocket.OPEN) return;
                if (this.frameCount === 0) return;       // first-frame watchdog owns this window
                const idleMs = SotaPlayer._now() - this._lastFrameAt;
                if (idleMs < SotaPlayer.STALL_TIMEOUT_MS) return;
                console.warn('[SotaPlayer] No frame for ' + Math.round(idleMs)
                    + 'ms on an open socket — reconnecting');
                // onclose follows and runs the capped-backoff reconnect.
                try { this.ws.close(); } catch (e) {}
            }, 2000);
        }

        _clearStallWatchdog() {
            if (this._stallWatchdog) {
                clearInterval(this._stallWatchdog);
                this._stallWatchdog = null;
            }
        }

        static _now() {
            return (typeof performance !== 'undefined' && performance.now)
                ? performance.now() : Date.now();
        }

        /**
         * Report an unrecoverable decoder failure exactly once. Without this the
         * player stays "connected" with a black canvas forever: onError was wired
         * up by callers but never invoked, so no fallback could ever trigger.
         */
        _fail(reason) {
            if (this._failed) return;
            this._failed = true;
            this._discardPendingFrame();
            console.error('[SotaPlayer] Fatal decode failure:', reason);
            if (this.onError) {
                try { this.onError(new Error(reason)); } catch (e) {}
            }
        }

        ingest(data) {
            if (!this.running || this._failed) return;
            this.bytesReceived = (this.bytesReceived || 0) + data.length;
            const nalUnits = this.splitNALUnits(data);
            for (const nal of nalUnits) {
                this.processNAL(nal);
            }
        }

        splitNALUnits(data) {
            // NALs are consumed synchronously by ingest(). Keep views into the
            // received message instead of copying the encoded payload here.
            // EncodedVideoChunk copies its BufferSource (no transfer requested).
            const units = [];
            let i = 0, lastStart = -1;

            while (i < data.length - 4) {
                if (data[i] === 0 && data[i+1] === 0) {
                    let startCodeLen = 0;
                    if (data[i+2] === 0 && data[i+3] === 1) startCodeLen = 4;
                    else if (data[i+2] === 1) startCodeLen = 3;

                    if (startCodeLen > 0) {
                        if (lastStart >= 0) units.push(data.subarray(lastStart, i));
                        lastStart = i;
                        i += startCodeLen;
                        continue;
                    }
                }
                i++;
            }

            if (lastStart >= 0) units.push(data.subarray(lastStart));
            else if (data.length > 0) units.push(data);
            return units;
        }

        processNAL(data) {
            let nalType = 0;
            if (data[0] === 0 && data[1] === 0 && data[2] === 0 && data[3] === 1) {
                nalType = data[4] & 0x1F;
            } else if (data[0] === 0 && data[1] === 0 && data[2] === 1) {
                nalType = data[3] & 0x1F;
            } else {
                return;
            }

            // Capture owned SPS/PPS copies — reconfigure decoder if SPS changes
            // (quality switch). Never retain a view of the whole message.
            if (nalType === 7) {
                if (this.sps && !this.arraysEqual(this.sps, data)) {
                    // SPS changed (quality/resolution change) — reset decoder state
                    this.sps = data.slice();
                    this.hasReceivedKeyframe = false;
                    if (this.decoder) {
                        try {
                            this._discardPendingFrame();
                            this.decoder.reset();
                            this._configure(this.extractCodecFromSPS(data));
                        } catch (e) {}
                    }
                    return;
                }
                // FIRST SPS: reconfigure to the stream's ACTUAL profile. initDecoder
                // guesses Baseline ("avc1.42C01F") because the encoder asks for it,
                // but MediaFormat.KEY_PROFILE is only a request — many devices emit
                // Main/High regardless. A decoder configured for Baseline that is
                // then fed High-profile chunks is exactly the case that fails on
                // some phones and not others: lenient desktop/WebView decoders
                // ignore the mismatch, stricter mobile ones reject every frame.
                if (!this.sps) {
                    this.sps = data.slice();
                    const realCodec = this.extractCodecFromSPS(data);
                    if (this.decoder && realCodec !== this.codecString) {
                        try {
                            this._discardPendingFrame();
                            this.decoder.reset();
                            this._configure(realCodec);
                            this.hasReceivedKeyframe = false;
                        } catch (e) {}
                    }
                    return;
                }
                // Equal SPS: retain the existing compact owned copy.
                return;
            }
            if (nalType === 8) {
                if (!this.pps || !this.arraysEqual(this.pps, data)) {
                    // Like SPS, PPS outlives this message. Do not pin a large
                    // WebSocket buffer (e.g. a bundled keyframe) via a view.
                    this.pps = data.slice();
                }
                return;
            }

            // Bound queued decode work. Once overloaded, discard the old GOP
            // and wait for a new IDR; never submit dependent frames after reset.
            if (nalType === 5 || nalType === 1) {
                if (this.decoder && this.decoder.decodeQueueSize >= SotaPlayer.MAX_DECODE_QUEUE) {
                    this.hasReceivedKeyframe = false;
                    this._discardPendingFrame();
                    try {
                        this.decoder.reset();
                        if (!this._configure(this.codecString)) return;
                    } catch (e) {
                        this.handleError(e);
                        return;
                    }
                }
                if (nalType === 5 && this.sps) {
                    this.hasReceivedKeyframe = this.decodeChunk(this.buildSuperFrame(data), true);
                } else if (nalType === 1 && this.hasReceivedKeyframe) {
                    this.decodeChunk(data, false);
                }
            }
        }

        buildSuperFrame(idrData) {
            const spsLen = this.sps ? this.sps.length : 0;
            const ppsLen = this.pps ? this.pps.length : 0;
            const superFrame = new Uint8Array(spsLen + ppsLen + idrData.length);
            let offset = 0;
            if (this.sps) { superFrame.set(this.sps, offset); offset += spsLen; }
            if (this.pps) { superFrame.set(this.pps, offset); offset += ppsLen; }
            superFrame.set(idrData, offset);
            return superFrame;
        }

        decodeChunk(data, isKey) {
            if (this._failed || !this.decoder) return false;
            try {
                const chunk = new EncodedVideoChunk({
                    type: isKey ? "key" : "delta",
                    timestamp: performance.now() * 1000,
                    data: data
                });
                this.decoder.decode(chunk);
                return true;
            } catch (e) {
                // decode() throws synchronously when the decoder is in a bad
                // state (unconfigured / closed). Silently swallowing every one of
                // those was the other half of the black-canvas symptom — count
                // them so handleError's ceiling can escalate to a fallback.
                this.handleError(e);
                return false;
            }
        }

        handleFrame(frame) {
            if (!this.running || this._failed) { frame.close(); return; }
            this._lastFrameAt = SotaPlayer._now();

            // Only one decoded frame may wait for the display. Bursts replace
            // the pending frame instead of repeatedly painting invisible work.
            if (this.pendingFrame) this.pendingFrame.close();
            this.pendingFrame = frame;
            if (this.paintRequest === null) {
                this.paintRequest = window.requestAnimationFrame(() => this._paintLatestFrame());
            }
        }

        _paintLatestFrame() {
            this.paintRequest = null;
            const frame = this.pendingFrame;
            this.pendingFrame = null;
            if (!frame) return;
            let canvasChanged = false;
            try {
                if (!this.running || this._failed) return;
                // Resizing clears the canvas even if the subsequent draw fails.
                canvasChanged = true;
                if (this.canvas.width !== frame.displayWidth || this.canvas.height !== frame.displayHeight) {
                    this.canvas.width = frame.displayWidth;
                    this.canvas.height = frame.displayHeight;
                }
                this.ctx.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height);
            } finally {
                frame.close();
                if (canvasChanged) this._notifyCanvasChanged();
            }
            if (this._frameWatchdog) this._clearFrameWatchdog();
            this.decodeErrors = 0;
            this.frameCount++;
            if (this.onFrame) this.onFrame(this.frameCount);
        }

        _notifyCanvasChanged() {
            if (!this.onCanvasChanged) return;
            try { this.onCanvasChanged(); }
            catch (e) { console.warn('[SotaPlayer] Canvas notification failed:', e); }
        }

        _discardPendingFrame() {
            if (this.paintRequest !== null) {
                window.cancelAnimationFrame(this.paintRequest);
                this.paintRequest = null;
            }
            if (this.pendingFrame) {
                this.pendingFrame.close();
                this.pendingFrame = null;
            }
        }

        handleError(e) {
            // Per the WebCodecs spec a decode error runs "Close VideoDecoder"
            // (state -> "closed") BEFORE the error callback is invoked, and
            // decode()/reset()/configure() on a closed decoder all throw
            // InvalidStateError. The old recovery called reset()+configure()
            // on exactly that closed instance, so nothing was ever rebuilt:
            // every later decode() threw, re-entered here, and the 6th error
            // retired WebCodecs entirely. Drop the faulted instance and build
            // a fresh one so the next IDR decodes.
            this._discardPendingFrame();
            const faulted = this.decoder;
            this.decoder = null;
            if (faulted) { try { faulted.close(); } catch (err) {} }
            if (!this.running || this._failed) return;

            // Bound the recovery attempts. A decoder that can't handle this
            // stream errors on EVERY chunk, and rebuilding forever kept the
            // canvas black with no signal to the caller — the reported "camera
            // doesn't work on my phone" symptom. After a few tries, give up
            // and report so a working decoder can take over.
            this.decodeErrors = (this.decodeErrors || 0) + 1;
            if (this.decodeErrors > 5) {
                this._fail('decoder errored ' + this.decodeErrors + 'x: '
                    + (e && e.message ? e.message : e));
                return;
            }
            // Wait for the next IDR before feeding the new decoder: a P-frame
            // would reference a picture it never saw. Reuse the SPS already in
            // hand so the rebuilt decoder is configured for the real profile.
            this.hasReceivedKeyframe = false;
            this.initDecoder(this.sps ? this.extractCodecFromSPS(this.sps) : "avc1.42C01F");
        }

        stop() {
            // Explicit user stop — clear intent so a later tab-show does NOT
            // auto-resume (only _suspendForHidden keeps userWantsStream set).
            this.userWantsStream = false;
            this.reconnectAttempts = 0;
            this.running = false;
            this._clearFrameWatchdog();
            this._clearStallWatchdog();
            const hadSocket = this.ws && this.ws.readyState !== WebSocket.CLOSED;
            if (this.ws) { this.ws.close(); this.ws = null; }
            this._discardPendingFrame();
            if (this.decoder) { try { this.decoder.close(); } catch(e) {} this.decoder = null; }
            this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
            this._notifyCanvasChanged();
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            if (hadSocket && this.onDisconnected) this.onDisconnected();
            // Remove the visibility listeners on explicit stop so instances that
            // are stopped+discarded don't accumulate orphan handlers. (Kept alive
            // through _suspendForHidden, which must still resume on show.)
            if (typeof document !== 'undefined' && document.removeEventListener) {
                if (this._onVisibility) document.removeEventListener('visibilitychange', this._onVisibility);
                if (this._onPageHide) window.removeEventListener('pagehide', this._onPageHide);
            }
        }

        isRunning() { return this.running; }

        arraysEqual(a, b) {
            if (a.length !== b.length) return false;
            for (let i = 0; i < a.length; i++) {
                if (a[i] !== b[i]) return false;
            }
            return true;
        }

        /**
         * SOTA: Extract avc1 codec string from SPS NAL unit.
         * Format: avc1.PPCCLL where PP=profile_idc, CC=constraint_flags, LL=level_idc
         * This ensures the WebCodecs decoder is configured to match the actual stream.
         */
        extractCodecFromSPS(spsData) {
            try {
                // Find the SPS byte after start code (00 00 00 01 67 or 00 00 01 67)
                let offset = 0;
                if (spsData[0] === 0 && spsData[1] === 0 && spsData[2] === 0 && spsData[3] === 1) {
                    offset = 5;  // Skip start code + NAL header
                } else if (spsData[0] === 0 && spsData[1] === 0 && spsData[2] === 1) {
                    offset = 4;  // Skip start code + NAL header
                } else {
                    offset = 1;  // Raw NAL, skip header
                }
                
                if (offset + 2 < spsData.length) {
                    const profileIdc = spsData[offset];
                    const constraintFlags = spsData[offset + 1];
                    const levelIdc = spsData[offset + 2];
                    const hex = (v) => v.toString(16).padStart(2, '0').toUpperCase();
                    return `avc1.${hex(profileIdc)}${hex(constraintFlags)}${hex(levelIdc)}`;
                }
            } catch (e) {}
            return "avc1.42C01F";  // Fallback: Baseline Profile Level 3.1
        }
    }

    // Assigned after the class body: static class fields are ES2022 and this
    // file must still PARSE on the in-car Chrome 58 WebView (it is loaded by
    // live-view.html there even though never instantiated).
    SotaPlayer.STALL_TIMEOUT_MS = 8000;
    SotaPlayer.MAX_DECODE_QUEUE = 12;

    if (typeof module !== 'undefined' && module.exports) module.exports = SotaPlayer;
    else global.SotaPlayer = SotaPlayer;
})(window);
