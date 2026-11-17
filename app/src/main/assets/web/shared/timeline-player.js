// BYD.tlplayer: wall-clock playback of the recordings timeline over /hls/timeline.m3u8 (browser only).
(function (root) {
    'use strict';

    const BYD = root.BYD = root.BYD || {};

    const HLS_VERSION = '1.7.4';
    const WIN_BACK_MS = 10 * 60000;
    const WIN_AHEAD_MS = 60 * 60000;
    const WIN_EDGE_MS = 5 * 60000;
    const API_MAX_MS = 8 * 86400000;
    const SEAM_MS = 1500;
    const QUIET_PAD_MS = 10000;
    const QUIET_HOLD_MS = 2000;
    const QUIET_LOOKAHEAD_MS = 12000;
    const QUIET_REWIND_MAX_MS = 20000;
    const FRAME_DEBOUNCE_MS = 120;
    const FRAME_CACHE_MAX = 60;
    const FRAME_W_MIN = 160;
    const FRAME_W_MAX = 1280;
    const FRAME_W_STEP = 160;
    const AT_MAX_MS = 6 * 3600000;
    const POS_INTERVAL_MS = 33;
    const SOURCES_INTERVAL_MS = 1000;
    const END_EPS_S = 0.12;
    const STALL_END_S = 0.6;
    // Fast speeds play every frame; keyframe skim only after the stream underruns (2 within UNDERRUN_SPAN_MS, or one this long).
    const UNDERRUN_SPAN_MS = 30000;
    const UNDERRUN_LONG_MS = 2500;
    const UNDERRUN_MIN_MS = 350;
    const FLOOR_MAX_MS = 3000;
    const PROBE_AHEAD_S = 60;
    const PROBE_CONCURRENCY = 2;
    const OVERLAY_DWELL_MS = 3000;
    const HOLD_FADE_MS = 180;
    const HOLD_MAX_MS = 6000;
    const RETRY_MS = [1000, 2000, 4000];
    const RELOAD_LIMIT = 3;
    const RELOAD_SPAN_MS = 60000;
    const LENS_REFRESH_MS = 10000;
    // Same rule as the list and the head unit: this much continuous 1x/2x playback marks a clip watched.
    const WATCHED_AFTER_MS = 3000;
    const WATCHED_RETRY_MS = 30000;
    const WATCH_GAP_MS = 500;
    const MOSAIC = 'mosaic';
    const OEM = 'oem';
    const QUIET_TYPES = { normal: true, oemDashcam: true };
    const MOMENT_TYPES = { sentry: true, proximity: true, replay: true };
    const SPEEDS = [1, 2, 4, 8];
    const GENERIC_CODECS = {
        avc1: 'avc1.42E01E', avc3: 'avc3.42E01E', hvc1: 'hvc1.1.6.L93.B0', hev1: 'hev1.1.6.L93.B0',
        av01: 'av01.0.04M.08', vp09: 'vp09.00.10.08'
    };

    const SCRIPT_SRC = (typeof document !== 'undefined' && document.currentScript && document.currentScript.src) || '';
    const VENDOR_URL = (function () {
        const v = SCRIPT_SRC.replace(/timeline-player\.js(?:[?#].*)?$/, 'vendor/hls.light.min.js');
        return (v && v !== SCRIPT_SRC ? v : '../shared/vendor/hls.light.min.js') + '?v=' + HLS_VERSION;
    })();

    // ------------------------------------------------------------------ pure helpers

    function isNum(x) { return typeof x === 'number' && isFinite(x); }
    function clamp(x, a, b) { return x < a ? a : (x > b ? b : x); }
    function perfNow() { return root.performance && root.performance.now ? root.performance.now() : Date.now(); }
    function safeDecode(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }
    function interpolate(str, vars) {
        if (!vars) return str;
        return String(str).replace(/\{(\w+)\}/g, function (m, k) { return vars[k] != null ? String(vars[k]) : m; });
    }

    const ISO_RE = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:[.,](\d+))?(Z|[+-]\d{2}(?::?\d{2})?)?$/i;

    // Explicit offset parse so every engine agrees; offset-less strings fall back to Date.parse like hls.js.
    function parseIsoMs(s) {
        const str = String(s == null ? '' : s).trim();
        const m = ISO_RE.exec(str);
        if (!m || !m[8]) {
            const v = Date.parse(str);
            return isFinite(v) ? v : null;
        }
        let ms = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
        if (m[7]) ms += Math.floor(Number('0.' + m[7]) * 1000 + 1e-6);
        const z = m[8].toUpperCase();
        if (z !== 'Z') {
            const digits = z.slice(1).replace(':', '');
            const off = Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2, 4) || 0);
            ms -= (z.charAt(0) === '-' ? -1 : 1) * off * 60000;
        }
        return ms;
    }

    const ID_RE = /\/hls\/id\/([^\/?#]+)\/([^\/?#]+)\//;
    const SKIM_RE = /\/k(\d+)s\d+\.m4s(?:[?#]|$)/;

    function idFromUrl(url) {
        const m = ID_RE.exec(String(url || ''));
        return m ? { id: safeDecode(m[1]), fp: safeDecode(m[2]) } : null;
    }

    function skimOfUrl(url) {
        const m = SKIM_RE.exec(String(url || ''));
        return m ? Number(m[1]) : 0;
    }

    function parsePlaylist(text) {
        const lines = String(text == null ? '' : text).replace(/^\uFEFF/, '').split(/\r?\n/);
        const frags = [];
        let dur = null, pdt = null, cc = 0, start = 0, map = null, gap = false;
        let valid = false, endList = false;
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            if (!line) continue;
            if (line.charAt(0) === '#') {
                if (line === '#EXTM3U') valid = true;
                else if (line.indexOf('#EXTINF:') === 0) dur = parseFloat(line.slice(8));
                else if (line.indexOf('#EXT-X-PROGRAM-DATE-TIME:') === 0) pdt = parseIsoMs(line.slice(25));
                else if (line === '#EXT-X-DISCONTINUITY') cc++;
                else if (line.indexOf('#EXT-X-MAP:') === 0) {
                    const mm = /URI="([^"]*)"/.exec(line);
                    map = mm ? mm[1] : null;
                } else if (line === '#EXT-X-ENDLIST') endList = true;
                else if (line === '#EXT-X-GAP') gap = true;
                continue;
            }
            const d = isNum(dur) && dur > 0 ? dur : 0;
            let p = pdt;
            if (p === null && frags.length) {
                // RFC 8216: without a discontinuity the date runs on from the previous segment.
                const prev = frags[frags.length - 1];
                if (prev.cc === cc && prev.pdt !== null) p = prev.pdt + prev.duration * 1000 * (skimOfUrl(prev.url) || 1);
            }
            frags.push({ start: start, duration: d, pdt: p, url: line, cc: cc, map: map, gap: gap });
            start += d;
            dur = null; pdt = null; gap = false;
        }
        return { valid: valid, frags: frags, endList: endList };
    }

    // frags: [{start, duration, pdt, url, cc, map}] in media order (hls.js level details or parsePlaylist).
    // baseWall dates media time 0 when the playlist carries no PDT (native: video.getStartDate()).
    function buildTable(frags, startSN, baseWall) {
        const n = frags.length;
        const tb = {
            n: n, startSN: startSN | 0, items: [], hasPdt: frags.some(function (f) { return isNum(f.pdt); }),
            ms: new Float64Array(n), me: new Float64Array(n), ws: new Float64Array(n), we: new Float64Array(n),
            weMax: new Float64Array(n), itemOf: new Int32Array(n)
        };
        let item = null;
        for (let i = 0; i < n; i++) {
            const f = frags[i];
            const ref = idFromUrl(f.url) || { id: '', fp: '' };
            const cc = f.cc | 0;
            const dur = f.duration > 0 ? f.duration : 0;
            tb.ms[i] = f.start;
            tb.me[i] = f.start + dur;
            if (!item || item.id !== ref.id || item.fp !== ref.fp || item.cc !== cc) {
                item = {
                    index: tb.items.length, id: ref.id, fp: ref.fp, key: ref.id + '|' + ref.fp, cc: cc,
                    i0: i, i1: i, skim: skimOfUrl(f.url), init: f.map || null, seg: null
                };
                tb.items.push(item);
            }
            item.i1 = i;
            tb.itemOf[i] = item.index;
            const rate = item.skim || 1;
            let ws = isNum(f.pdt) ? f.pdt : null;
            if (ws === null) {
                ws = i ? tb.ws[i - 1] + (tb.me[i - 1] - tb.ms[i - 1]) * 1000 * (tb.items[tb.itemOf[i - 1]].skim || 1)
                    : (isNum(baseWall) ? baseWall + f.start * 1000 : 0);
            }
            tb.ws[i] = ws;
            tb.we[i] = ws + dur * 1000 * rate;
        }
        for (let i = 0; i < n; i++) {
            const it = tb.items[tb.itemOf[i]];
            // Inside one clip the next GOP's date is the exact end, also for skim segments.
            if (i < it.i1 && tb.ws[i + 1] > tb.ws[i]) tb.we[i] = tb.ws[i + 1];
            tb.weMax[i] = i ? Math.max(tb.weMax[i - 1], tb.we[i]) : tb.we[i];
        }
        for (let k = 0; k < tb.items.length; k++) {
            const it = tb.items[k];
            it.ms = tb.ms[it.i0];
            it.me = tb.me[it.i1];
            it.ws = tb.ws[it.i0];
            it.we = tb.we[it.i1];
        }
        return tb;
    }

    function fragIndexAtMedia(tb, t) {
        if (!tb || !tb.n) return -1;
        if (!(t >= tb.ms[0])) return 0;
        let lo = 0, hi = tb.n - 1;
        while (lo < hi) {
            const mid = (lo + hi + 1) >> 1;
            if (tb.ms[mid] <= t) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    function itemAtMedia(tb, t) {
        const i = fragIndexAtMedia(tb, t);
        return i < 0 ? null : tb.items[tb.itemOf[i]];
    }

    function mediaToWall(tb, t) {
        const i = fragIndexAtMedia(tb, t);
        if (i < 0) return null;
        const span = tb.me[i] - tb.ms[i];
        const x = clamp(t - tb.ms[i], 0, span);
        return span > 0 ? tb.ws[i] + x * (tb.we[i] - tb.ws[i]) / span : tb.ws[i];
    }

    // Inside a fragment -> exact media time; in a gap -> start of the next fragment; past the end -> past.
    function wallToMedia(tb, w) {
        const n = tb ? tb.n : 0;
        if (!n || !isNum(w)) return null;
        let lo = 0, hi = n;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if (tb.weMax[mid] > w) hi = mid; else lo = mid + 1;
        }
        if (lo >= n) return { media: tb.me[n - 1], i: n - 1, inside: false, gap: false, past: true };
        const i = lo;
        if (w >= tb.ws[i]) {
            const wspan = tb.we[i] - tb.ws[i], mspan = tb.me[i] - tb.ms[i];
            const media = wspan > 0 ? tb.ms[i] + (w - tb.ws[i]) * mspan / wspan : tb.ms[i];
            return { media: Math.min(media, tb.me[i]), i: i, inside: true, gap: false, past: false };
        }
        return { media: tb.ms[i], i: i, inside: false, gap: true, past: false };
    }

    // OEM single-camera footage is one family whatever list type the index gives it (dvr_p_ is "sentry").
    function familyOf(s) { return s && (s.type === 'oemDashcam' || s.layout === 'single') ? OEM : MOSAIC; }

    function isPlayable(s) {
        return !!s && s.available !== false && isNum(s.start) && isNum(s.end) && s.end > s.start;
    }

    function hasKey(set, s) { return !!set && !!s && set.has(s.id + '|' + s.fp); }

    function sourcesAt(segs, w, blocked) {
        const fam = {};
        for (let i = 0; i < (segs ? segs.length : 0); i++) {
            const s = segs[i];
            if (s.start > w) break;
            if (isPlayable(s) && s.end > w && !hasKey(blocked, s)) fam[familyOf(s)] = true;
        }
        return [MOSAIC, OEM].filter(function (f) { return fam[f]; });
    }

    function mergeIntervals(iv) {
        const list = iv.filter(function (x) { return isNum(x[0]) && isNum(x[1]) && x[1] >= x[0]; })
            .sort(function (a, b) { return a[0] - b[0]; });
        const out = [];
        for (let i = 0; i < list.length; i++) {
            const last = out[out.length - 1];
            if (last && list[i][0] <= last[1]) last[1] = Math.max(last[1], list[i][1]);
            else out.push([list[i][0], list[i][1]]);
        }
        return out;
    }

    // Marks, road events and moment clips padded by ±10 s: playback slows to the chosen speed inside these.
    function busyIntervals(data) {
        const iv = [];
        const marks = (data && data.marks) || [];
        for (let i = 0; i < marks.length; i++) {
            const a = Number(marks[i].t0), b = Number(marks[i].t1);
            if (isNum(a)) iv.push([a - QUIET_PAD_MS, Math.max(a, isNum(b) ? b : a) + QUIET_PAD_MS]);
        }
        const road = (data && data.road) || [];
        for (let i = 0; i < road.length; i++) {
            const t = Number(road[i].t);
            if (isNum(t)) iv.push([t - QUIET_PAD_MS, t + QUIET_PAD_MS]);
        }
        const segs = (data && data.segments) || [];
        for (let i = 0; i < segs.length; i++) {
            const s = segs[i];
            if (!MOMENT_TYPES[s.type] || !isNum(s.start)) continue;
            iv.push([s.start - QUIET_PAD_MS, (isNum(s.end) ? s.end : s.start + 1000) + QUIET_PAD_MS]);
        }
        return mergeIntervals(iv);
    }

    function firstEndingAfter(iv, a) {
        let lo = 0, hi = iv.length;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if (iv[mid][1] < a) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    function inIntervals(iv, a, b) {
        const k = firstEndingAfter(iv || [], a);
        return !!iv && k < iv.length && iv[k][0] <= b;
    }

    function newQuietState() { return { mode: 'normal', since: null }; }

    // One skip-quiet decision. Leaving quiet is immediate (with look-ahead for skim latency); entering
    // needs QUIET_HOLD_MS of quiet wall time so the expensive skim reload never flaps.
    function skipQuietStep(st, inp) {
        const wall = inp.wall;
        const clear = !!QUIET_TYPES[inp.type] && !inIntervals(inp.busy, wall, wall + QUIET_LOOKAHEAD_MS);
        if (st.mode === 'quiet') {
            if (clear) return { mode: 'quiet', switched: false, seekWall: null };
            st.mode = 'normal';
            st.since = null;
            const k = firstEndingAfter(inp.busy || [], wall);
            const bs = inp.busy && k < inp.busy.length ? inp.busy[k][0] : null;
            const back = bs !== null && bs < wall ? Math.max(bs, wall - QUIET_REWIND_MAX_MS) : wall;
            return { mode: 'normal', switched: true, seekWall: back };
        }
        if (!clear) {
            st.since = null;
            return { mode: 'normal', switched: false, seekWall: null };
        }
        if (st.since === null || wall < st.since) st.since = wall;
        if (wall - st.since >= QUIET_HOLD_MS) {
            st.mode = 'quiet';
            st.since = null;
            return { mode: 'quiet', switched: true, seekWall: wall };
        }
        return { mode: 'normal', switched: false, seekWall: null };
    }

    // Playback window: [t - 10 min, t + 60 min] clipped to the loaded window.
    function playbackWindow(w, outer) {
        const lo = outer ? outer.from : -Infinity, hi = outer ? outer.to : Infinity;
        const c = clamp(w, lo, hi);
        return { from: Math.max(lo, Math.floor(c - WIN_BACK_MS)), to: Math.min(hi, Math.ceil(c + WIN_AHEAD_MS)) };
    }

    // 0 = keep; -1/1 = within 5 min of an extendable edge; 2 = outside the window.
    function slideDir(w, win, outer) {
        if (!win) return 2;
        const canBack = !outer || win.from > outer.from;
        const canFwd = !outer || win.to < outer.to;
        if (w < win.from) return canBack ? 2 : 0;
        if (w >= win.to) return canFwd ? 2 : 0;
        if (canBack && w - win.from < WIN_EDGE_MS) return -1;
        if (canFwd && win.to - w < WIN_EDGE_MS) return 1;
        return 0;
    }

    // Where playback lands for wall w: inside playable footage -> w, else the next playable start.
    function landing(w, segs, blocked) {
        let hit = null, blockedSeg = null, next = null;
        for (let i = 0; i < (segs ? segs.length : 0); i++) {
            const s = segs[i];
            if (!isPlayable(s)) continue;
            if (s.start > w) {
                if (!hasKey(blocked, s)) { next = s; break; }
                continue;
            }
            if (s.end > w) {
                if (hasKey(blocked, s)) { if (!blockedSeg) blockedSeg = s; } else if (!hit) hit = s;
            }
        }
        if (hit) return { wall: w, seg: hit, blockedSeg: null };
        if (next) return { wall: next.start, seg: next, blockedSeg: blockedSeg };
        return { wall: w, seg: null, blockedSeg: blockedSeg };
    }

    function nextPlayableStart(segs, after, blocked) {
        let best = null;
        for (let i = 0; i < (segs ? segs.length : 0); i++) {
            const s = segs[i];
            if (best !== null && s.start >= best) break;
            if (!isPlayable(s) || s.end <= after + SEAM_MS || hasKey(blocked, s)) continue;
            const at = Math.max(s.start, after);
            if (best === null || at < best) best = at;
        }
        return best;
    }

    function hasFootage(segs, a, b, blocked) {
        for (let i = 0; i < (segs ? segs.length : 0); i++) {
            const s = segs[i];
            if (s.start >= b) break;
            if (isPlayable(s) && s.end > a && !hasKey(blocked, s)) return true;
        }
        return false;
    }

    function timelineUrl(win, prefer, skim) {
        return '/hls/timeline.m3u8?from=' + Math.floor(win.from) + '&to=' + Math.ceil(win.to)
            + (prefer ? '&prefer=' + encodeURIComponent(prefer) : '')
            + (skim ? '&skim=' + skim : '');
    }

    function apiWindow(outer) {
        return outer.to - outer.from > API_MAX_MS ? { from: outer.to - API_MAX_MS, to: outer.to } : outer;
    }

    function apiUrl(win) {
        return '/api/timeline?from=' + Math.floor(win.from) + '&to=' + Math.ceil(win.to) + '&include=marks,road';
    }

    function frameWidth(cssW, dpr) {
        const px = Math.ceil((Number(cssW) || 0) * (Number(dpr) || 1) / FRAME_W_STEP) * FRAME_W_STEP;
        return clamp(px, FRAME_W_MIN, FRAME_W_MAX);
    }

    // ls only varies the URL: the daemon renders at its current strength, the browser cache must not outlive it.
    function frameUrl(id, fp, at, w, ls) {
        return '/api/timeline/frame?id=' + encodeURIComponent(id) + '&fp=' + encodeURIComponent(fp)
            + '&at=' + clamp(Math.round(at), 0, AT_MAX_MS) + '&w=' + w + (isNum(ls) ? '&ls=' + ls : '');
    }

    // Scrub frame request for wall w: one key per GOP inside the playlist, 1 s buckets elsewhere.
    function frameTarget(tb, segById, segs, w) {
        if (tb && tb.n && w >= tb.ws[0] - SEAM_MS && w <= tb.we[tb.n - 1] + SEAM_MS) {
            const r = wallToMedia(tb, w);
            const it = tb.items[tb.itemOf[r.i]];
            if (it && it.id) {
                const seg = segById ? segById.get(it.id) : null;
                const base = seg && isNum(seg.start) ? seg.start : it.ws;
                // The GOP's keyframe is the frame at-or-before its middle even if the bases differ slightly.
                const mid = (tb.ws[r.i] + tb.we[r.i]) / 2;
                return { id: it.id, fp: it.fp, at: clamp(Math.round(mid - base), 0, AT_MAX_MS), key: it.key + '|g' + Math.round(tb.ws[r.i]) };
            }
        }
        const land = landing(w, segs, null);
        const s = land.seg;
        if (!s || !s.id) return null;
        const x = Math.max(0, land.wall - s.start);
        const q = Math.min(Math.floor(x / 1000) * 1000 + 500, Math.max(0, s.end - s.start));
        return { id: s.id, fp: s.fp, at: clamp(q, 0, AT_MAX_MS), key: s.id + '|' + s.fp + '|q' + q };
    }

    function Lru(max, onEvict) {
        this.max = max;
        this.map = new Map();
        this.onEvict = onEvict;
    }
    Lru.prototype.get = function (k) {
        const v = this.map.get(k);
        if (v !== undefined) { this.map.delete(k); this.map.set(k, v); }
        return v;
    };
    Lru.prototype.set = function (k, v) {
        if (this.map.has(k)) this.map.delete(k);
        this.map.set(k, v);
        while (this.map.size > this.max) {
            const k0 = this.map.keys().next().value;
            const v0 = this.map.get(k0);
            this.map.delete(k0);
            this.onEvict(v0, k0);
        }
    };
    Lru.prototype.clear = function () {
        const self = this;
        this.map.forEach(function (v, k) { self.onEvict(v, k); });
        this.map.clear();
    };

    function fourcc(u8, p) {
        return p + 4 <= u8.length ? String.fromCharCode(u8[p], u8[p + 1], u8[p + 2], u8[p + 3]) : '';
    }

    // Track set and video sample entry of an init segment: {sig: 'av'|'v'|'a'|'', video: fourcc|null}.
    function initInfo(buf) {
        const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
        const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
        const tracks = [];
        function walk(start, end, trak) {
            let p = start;
            while (p + 8 <= end) {
                let size = dv.getUint32(p);
                const type = fourcc(u8, p + 4);
                let hdr = 8;
                if (size === 1) {
                    if (p + 16 > end) return;
                    size = dv.getUint32(p + 8) * 4294967296 + dv.getUint32(p + 12);
                    hdr = 16;
                } else if (size === 0) {
                    size = end - p;
                }
                if (size < hdr || p + size > end) return;
                if (type === 'trak') {
                    const t = { handler: '', entry: '' };
                    tracks.push(t);
                    walk(p + hdr, p + size, t);
                } else if (type === 'moov' || type === 'mdia' || type === 'minf' || type === 'stbl') {
                    walk(p + hdr, p + size, trak);
                } else if (trak && type === 'hdlr') {
                    trak.handler = fourcc(u8, p + hdr + 8);
                } else if (trak && type === 'stsd') {
                    trak.entry = fourcc(u8, p + hdr + 12);
                }
                p += size;
            }
        }
        walk(0, u8.length, null);
        let video = null, audio = false;
        for (let i = 0; i < tracks.length; i++) {
            if (tracks[i].handler === 'vide' && !video) video = tracks[i].entry || null;
            else if (tracks[i].handler === 'soun') audio = true;
        }
        return { sig: (audio && video ? 'av' : (video ? 'v' : (audio ? 'a' : ''))), video: video };
    }

    function sigOfTracks(tracks) {
        if (!tracks) return '';
        if (tracks.audiovideo) return 'av';
        const v = !!tracks.video, a = !!tracks.audio;
        return a && v ? 'av' : (v ? 'v' : (a ? 'a' : ''));
    }

    function mediaSourceCtor() { return root.MediaSource || root.ManagedMediaSource || root.WebKitMediaSource || null; }

    function mseSupports(container, codec) {
        const MS = mediaSourceCtor();
        if (!MS || typeof MS.isTypeSupported !== 'function' || !codec) return true;
        try { return MS.isTypeSupported((container || 'video/mp4') + ';codecs="' + codec + '"'); } catch (e) { return true; }
    }

    function fourccSupported(fc) {
        const c = GENERIC_CODECS[fc];
        return c ? mseSupports('video/mp4', c) : true;
    }

    function authToken() {
        try {
            /* global BYDAuth */
            return typeof BYDAuth !== 'undefined' && BYDAuth && BYDAuth.getToken ? BYDAuth.getToken() : null;
        } catch (e) {
            return null;
        }
    }

    function sameOrigin(url) {
        try { return new URL(url, root.location.href).origin === root.location.origin; } catch (e) { return false; }
    }

    function absUrl(url) {
        try { return new URL(url, root.location.href).href; } catch (e) { return url; }
    }

    // Only same-origin paths from the server become hrefs.
    function safeHref(u) {
        return typeof u === 'string' && /^\/(?!\/)/.test(u) ? u : null;
    }

    let hlsPromise = null;
    function ensureHls() {
        if (root.Hls) return Promise.resolve(root.Hls);
        if (hlsPromise) return hlsPromise;
        hlsPromise = new Promise(function (resolve) {
            const s = document.createElement('script');
            s.src = VENDOR_URL;
            s.async = true;
            s.onload = function () { resolve(root.Hls || null); };
            s.onerror = function () {
                hlsPromise = null;
                if (s.parentNode) s.parentNode.removeChild(s);
                resolve(null);
            };
            (document.head || document.documentElement).appendChild(s);
        });
        return hlsPromise;
    }

    const CSS = [
        '.tlv-player{position:relative;width:100%;height:100%;overflow:hidden;background:#000}',
        '.tlv-player>.tlv-video,.tlv-player>.tlv-lens,.tlv-player>.tlv-hold,.tlv-player>.tlv-scrub-frame{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:contain;display:block;margin:0;padding:0;border:0}',
        '.tlv-player>.tlv-lens,.tlv-player>.tlv-hold,.tlv-player>.tlv-scrub-frame{pointer-events:none}',
        '.tlv-player>.tlv-hold{transition:opacity ' + HOLD_FADE_MS + 'ms cubic-bezier(.23,1,.32,1)}',
        '.tlv-player [hidden]{display:none!important}',
        '.tlv-unsupported{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;padding:24px;box-sizing:border-box;text-align:center;background:rgba(14,19,17,.9);color:#DEE4E0;font:500 15px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}',
        '.tlv-unsupported-msg{margin:0;max-width:32em}',
        '.tlv-unsupported-dl{display:inline-flex;align-items:center;justify-content:center;min-height:48px;min-width:48px;padding:0 24px;box-sizing:border-box;border-radius:24px;background:#242927;color:#DEE4E0;font-weight:600;text-decoration:none;transition:transform 100ms ease-out}',
        '.tlv-unsupported-dl:active{transform:scale(.97)}',
        '.tlv-unsupported-dl:focus-visible{outline:2px solid #DEE4E0;outline-offset:2px}',
        '@media (prefers-reduced-motion:reduce){.tlv-player>.tlv-hold{transition-duration:150ms}}'
    ].join('\n');

    // Inserted first in <head> so any page stylesheet overrides these defaults.
    function injectStyle() {
        if (document.getElementById('tlv-player-style')) return;
        const st = document.createElement('style');
        st.id = 'tlv-player-style';
        st.textContent = CSS;
        const head = document.head || document.documentElement;
        head.insertBefore(st, head.firstChild);
    }

    // ------------------------------------------------------------------ player

    function Player(container, opts) {
        if (!container || typeof container.appendChild !== 'function') throw new Error('tlplayer: container element required');
        this.o = opts || {};
        this.container = container;
        this.listeners = {};
        this.dead = false;
        this.gen = 0;
        this.srcGen = 0;
        this.outer = null;
        this.win = null;
        this.prefer = null;
        this.userSpeed = 1;
        this.mode = 0;
        this.slowAt = 0;
        this.underruns = [];
        this.stallAt = 0;
        this.runAt = 0;
        this.skipQuiet = false;
        this.quiet = newQuietState();
        this.continueNext = true;
        this.userMuted = false;
        this.forcedMute = false;
        this.intent = 'pause';
        this.state = null;
        this.errorCode = null;
        this.ended = false;
        this.waiting = false;
        this.engineKind = null;
        this.hls = null;
        this.hlsLoading = false;
        this.srcSig = null;
        this.tb = null;
        this.tbDirty = false;
        this.tbBuiltAt = 0;
        this.loadingSource = false;
        this.startMedia = null;
        this.pendingWall = null;
        this.virtualWall = null;
        this.lastWall = null;
        this.floor = null;
        this.curItem = null;
        this.curItemKey = null;
        this.itemEmitKey = null;
        this.boundaryKey = null;
        this.gapHold = null;
        this.overlaySeg = null;
        this.parkItem = null;
        this.pendingOverlay = null;
        this.nativeStart = null;
        this.nativeFrags = null;
        this.resume = null;
        this.dwell = 0;
        this.blockedRun = 0;
        this.blindSlide = 0;
        this.data = null;
        this.dataReady = false;
        this.dataFailed = false;
        this.segs = [];
        this.segById = new Map();
        this.busy = [];
        this.blocked = new Map();
        this.sigs = new Map();
        this.probes = new Map();
        this.probeQueue = [];
        this.probeActive = 0;
        this.sidecars = new Map();
        this.strength = null;
        this.strengthAt = 0;
        this.dewarp = null;
        this.lensFailed = false;
        this.lensReplaced = false;
        this.lensKey = null;
        this.lensItem = null;
        this.holding = false;
        this.holdTimer = 0;
        this.holdSafety = 0;
        this.frameFail = new Set();
        this.scrub = null;
        this.scrubRelease = false;
        this.frameWant = null;
        this.frameReq = null;
        this.frameTimer = 0;
        this.frameSeq = 0;
        this.frameShownSeq = -1;
        this.shownUrl = null;
        this.revokeLater = [];
        this.netTries = 0;
        this.mediaTries = 0;
        this.mediaErrAt = 0;
        this.fenceTries = 0;
        this.reloads = [];
        this.retryTimer = 0;
        this.kickTimer = 0;
        this.raf = 0;
        this.vfc = 0;
        this.posAt = 0;
        this.posWall = null;
        this.posPlaying = null;
        this.sourcesAt = 0;
        this.sourcesKey = null;
        this.ctl = { data: null, playlist: null };
        const self = this;
        this.frames = new Lru(FRAME_CACHE_MAX, function (url) { self._revoke(url); });
        this.watch = null;
        this.watchedPosted = new Set();
        this.watchedRetried = new Set();
        this._buildDom();
        this._bindVideo();
        this._setState('idle');
    }

    Player.prototype._buildDom = function () {
        injectStyle();
        const doc = document;
        const rootEl = doc.createElement('div');
        rootEl.className = 'tlv-player';
        const v = doc.createElement('video');
        v.className = 'tlv-video';
        v.playsInline = true;
        v.setAttribute('playsinline', '');
        v.setAttribute('webkit-playsinline', '');
        v.preload = 'auto';
        v.muted = false;
        const lens = doc.createElement('canvas');
        lens.className = 'tlv-lens';
        lens.hidden = true;
        lens.setAttribute('aria-hidden', 'true');
        const hold = doc.createElement('canvas');
        hold.className = 'tlv-hold';
        hold.hidden = true;
        hold.setAttribute('aria-hidden', 'true');
        const img = doc.createElement('img');
        img.className = 'tlv-scrub-frame';
        img.alt = '';
        img.hidden = true;
        img.draggable = false;
        img.decoding = 'async';
        const ov = doc.createElement('div');
        ov.className = 'tlv-unsupported';
        ov.hidden = true;
        ov.setAttribute('role', 'status');
        ov.setAttribute('aria-live', 'polite');
        const msg = doc.createElement('p');
        msg.className = 'tlv-unsupported-msg';
        const dl = doc.createElement('a');
        dl.className = 'tlv-unsupported-dl';
        ov.appendChild(msg);
        ov.appendChild(dl);
        rootEl.appendChild(v);
        rootEl.appendChild(lens);
        rootEl.appendChild(hold);
        rootEl.appendChild(img);
        rootEl.appendChild(ov);
        this.container.appendChild(rootEl);
        this.el = rootEl;
        this.video = v;
        this.lensCanvas = lens;
        this.holdCanvas = hold;
        this.img = img;
        this.ov = ov;
        this.ovMsg = msg;
        this.ovDl = dl;
    };

    Player.prototype._bindVideo = function () {
        const v = this.video;
        const self = this;
        const on = function (name, fn) { v.addEventListener(name, fn); };
        this._vh = {
            playing: function () { self._stallEnded(); if (!self.runAt) self.runAt = perfNow(); self.waiting = false; self.ended = false; self._loopStart(); self._updateState(); self._tick(); },
            play: function () { self._loopStart(); },
            pause: function () { self.stallAt = 0; self.runAt = 0; self.waiting = false; self._updateState(); self._emitPosition(true); },
            waiting: function () { self._stallStarted(); self.waiting = true; self._updateState(); self._tick(); },
            seeking: function () { self.stallAt = 0; self.runAt = 0; self.floor = null; self._loopStart(); self._updateState(); },
            seeked: function () { self.waiting = false; self._updateState(); self._onSeeked(); },
            timeupdate: function () { self._tick(); },
            ended: function () { self._onEnded(); },
            error: function () { self._onVideoError(); },
            loadedmetadata: function () { self._onMetadata(); },
            loadeddata: function () { if (typeof v.requestVideoFrameCallback !== 'function') self._onSeeked(); },
            canplay: function () { if (self.waiting) { self._stallEnded(); self.waiting = false; self._updateState(); } },
            ratechange: function () { self._applyRate(); }
        };
        Object.keys(this._vh).forEach(function (k) { on(k, self._vh[k]); });
        this._onVis = function () { if (!document.hidden) { self._loopStart(); self._tick(); self._emitPosition(true); } };
        document.addEventListener('visibilitychange', this._onVis);
        this._vfcStart();
    };

    // ---------------------------------------------------------------- public API

    Player.prototype.on = function (evt, fn) {
        if (typeof fn !== 'function') return function () {};
        (this.listeners[evt] = this.listeners[evt] || []).push(fn);
        const self = this;
        return function () { self.off(evt, fn); };
    };

    Player.prototype.off = function (evt, fn) {
        const list = this.listeners[evt];
        if (!list) return;
        const i = list.indexOf(fn);
        if (i >= 0) list.splice(i, 1);
    };

    Player.prototype.load = function (win, prefer) {
        if (this.dead) return;
        const from = Math.floor(Number(win && win.from)), to = Math.ceil(Number(win && win.to));
        // Keep the last picture up while another day loads instead of going black.
        this._holdFrame();
        this.gen++;
        this._abortFetches();
        this._closeSource();
        this._clearHolds();
        this._clearScrub(true);
        this.outer = null;
        this.win = null;
        this.tb = null;
        this.pendingWall = null;
        this.pendingUser = null;
        this.virtualWall = null;
        this.lastWall = null;
        this.ended = false;
        this.errorCode = null;
        this.blindSlide = 0;
        this.curItem = null;
        this.curItemKey = null;
        this.itemEmitKey = null;
        this.sourcesKey = null;
        this.data = null;
        this.dataReady = false;
        this.dataFailed = false;
        this.segs = [];
        this.segById = new Map();
        this.busy = [];
        this.reloads = [];
        this.netTries = 0;
        this.mediaTries = 0;
        this.quiet = newQuietState();
        const m = this._skimFor(this._effectiveSpeed());
        if (this.mode !== m) { this.mode = m; this._applyRate(); }
        if (!(isNum(from) && isNum(to) && to > from)) {
            this._fail(null, 'range');
            return;
        }
        this.outer = { from: from, to: to };
        this.prefer = prefer || null;
        this._setState('buffering');
        this._fetchData(this.gen);
        const g = this.gen;
        const self = this;
        // Runs after the caller's synchronous seekWall()/play() so the first window centres on its target.
        clearTimeout(this.kickTimer);
        this.kickTimer = setTimeout(function () { if (g === self.gen) self._kick(); }, 0);
    };

    Player.prototype.play = function () {
        if (this.dead) return;
        this.intent = 'play';
        if (this.errorCode) { this._recoverFromError(); return; }
        if (this.overlaySeg) { this._resume(); return; }
        if (this.gapHold) {
            const g = this.gapHold;
            if (!g.item && !isNum(g.wall) && isNum(g.from)) {
                const nx = this.dataReady ? nextPlayableStart(this.segs, g.from, this.blocked) : null;
                if (nx === null || !this.outer || nx >= this.outer.to) {
                    // Nothing recorded after the hold in this day: stay put, and say so.
                    this.intent = 'pause';
                    this._updateState();
                    this._emit('state', this.state);
                    this._emit('gap', { from: g.from, to: null, user: true, play: true });
                    return;
                }
                g.wall = nx;
            }
            this.gapHold = null;
            this.virtualWall = null;
            if (g.item) this._boundary(g.item, true);
            else if (isNum(g.wall)) this.seekWall(g.wall, true);
            this._updateState();
            return;
        }
        if (this.scrub) { this.scrub.wasPlaying = true; return; }
        if (this.ended) { this._updateState(); return; }
        if (!this.outer) return;
        if (!this.win) { this._kick(); return; }
        if (this.tb && !this.loadingSource) this._playMedia();
        this._updateState();
    };

    Player.prototype.pause = function () {
        if (this.dead) return;
        this.intent = 'pause';
        this.carry = false;
        clearTimeout(this.dwell);
        this.dwell = 0;
        if (this.scrub) this.scrub.wasPlaying = false;
        this.video.pause();
        this._updateState();
    };

    Player.prototype.seekWall = function (ms, keepIntent, carry) {
        ms = Number(ms);
        if (this.dead || !isNum(ms) || !this.outer) return;
        // A move made while playing carries on past unrecorded time; a first open or a paused move holds there.
        this.carry = carry != null ? !!carry : this.intent === 'play' && (this.state === 'playing' || this.state === 'buffering');
        if (!keepIntent) this._clearHolds();
        if (this.scrub) { this._clearScrub(false); this.scrubRelease = !this.img.hidden; }
        this.errorCode = null;
        this.ended = false;
        this.boundaryKey = null;
        this.floor = null;
        this.quiet.since = null;
        ms = clamp(ms, this.outer.from, this.outer.to);
        // A user seek that must wait for a table still holds in a gap once the table lands.
        this.pendingUser = keepIntent ? null : { wall: ms };
        if (!this.win) {
            this.pendingWall = ms;
            this._kick();
            return;
        }
        if (slideDir(ms, this.win, this.outer) === 2) { this._openWindow(ms); return; }
        if (!this.loadingSource && !this.tb) {
            // Nothing loaded and nothing loading: open a window here rather than wait for one.
            this._openWindow(ms);
            return;
        }
        if (this.loadingSource || !this.tb) {
            this.pendingWall = ms;
            this._emitPosition(true);
            return;
        }
        if (this.engineKind === 'native' && !this.video.getAttribute('src')) { this._openSource(ms); return; }
        this.pendingUser = null;
        this._seekInTable(ms, !keepIntent);
    };

    Player.prototype.beginScrub = function () {
        if (this.dead || this.scrub) return;
        this.scrub = { wasPlaying: this.intent === 'play' || (this.video && !this.video.paused) };
        clearTimeout(this.dwell);
        this.dwell = 0;
        this.scrubRelease = false;
        this.video.pause();
        this._updateState();
    };

    Player.prototype.scrubWall = function (ms) {
        ms = Number(ms);
        if (this.dead || !isNum(ms) || !this.outer) return;
        if (!this.scrub) this.beginScrub();
        ms = clamp(ms, this.outer.from, this.outer.to);
        this.scrub.wall = ms;
        const tgt = frameTarget(this.tb, this.segById, this.segs, ms);
        if (!tgt) return;
        tgt.w = frameWidth(this.el.clientWidth, root.devicePixelRatio || 1);
        tgt.key += '|w' + tgt.w + (isNum(this.strength) ? '|s' + this.strength : '');
        tgt.seq = ++this.frameSeq;
        const hit = this.frames.get(tgt.key);
        if (hit) {
            this.frameWant = null;
            this._showFrame(hit, tgt.seq);
            return;
        }
        if (this.frameFail.has(tgt.key)) return;
        this.frameWant = tgt;
        if (!this.frameTimer) {
            const self = this;
            this.frameTimer = setTimeout(function () { self.frameTimer = 0; self._pumpFrame(); }, FRAME_DEBOUNCE_MS);
        }
    };

    Player.prototype.endScrub = function (ms) {
        if (this.dead) return;
        const s = this.scrub;
        if (!s) { this.seekWall(ms); return; }
        const target = isNum(Number(ms)) ? Number(ms) : s.wall;
        this._clearScrub(false);
        this.intent = s.wasPlaying ? 'play' : 'pause';
        this.scrubRelease = !this.img.hidden;
        this.seekWall(target, false, s.wasPlaying);
        if (this.intent === 'play' && this.tb && !this.loadingSource && !this.overlaySeg && !this.gapHold) this._playMedia();
        this._updateState();
    };

    /** The view is hidden: pause and stop fetching segments; play() resumes loading. */
    Player.prototype.suspend = function () {
        if (this.dead) return;
        this.pause();
        if (this.hls && this.hlsLoading) {
            this.suspended = true;
            this._stopHls();
        }
    };

    Player.prototype.setSpeed = function (s) {
        s = Number(s);
        this.userSpeed = SPEEDS.indexOf(s) >= 0 ? s : 1;
        this.slowAt = 0;
        this._applySpeed(null);
    };

    Player.prototype.setSkipQuiet = function (on) {
        this.skipQuiet = !!on;
        this.quiet = newQuietState();
        this.slowAt = 0;
        this._applySpeed(null);
    };

    Player.prototype.setContinue = function (on) {
        this.continueNext = on !== false;
    };

    Player.prototype.setPrefer = function (type) {
        const p = type || null;
        if (p === this.prefer) return;
        this.prefer = p;
        this.sourcesKey = null;
        if (this.win && !this.dead) this._openSource(this._currentWall());
    };

    Player.prototype.setMuted = function (m) {
        this.userMuted = !!m;
        if (!this.userMuted) this.forcedMute = false;
        this._applyMute();
    };

    Player.prototype.destroy = function () {
        if (this.dead) return;
        this.dead = true;
        this.gen++;
        this.srcGen++;
        clearTimeout(this.kickTimer);
        clearTimeout(this.retryTimer);
        clearTimeout(this.dwell);
        clearTimeout(this.frameTimer);
        clearTimeout(this.holdTimer);
        clearTimeout(this.holdSafety);
        if (this.raf) cancelAnimationFrame(this.raf);
        this.raf = 0;
        const v = this.video;
        if (this.vfc && v.cancelVideoFrameCallback) v.cancelVideoFrameCallback(this.vfc);
        this.vfc = 0;
        this._abortFetches();
        if (this.frameReq) this.frameReq.ctl.abort();
        this.frameReq = null;
        this._closeSource();
        if (this.dewarp) { try { this.dewarp.detach(); } catch (e) { /* gone */ } }
        this.dewarp = null;
        const self = this;
        Object.keys(this._vh).forEach(function (k) { v.removeEventListener(k, self._vh[k]); });
        document.removeEventListener('visibilitychange', this._onVis);
        this.shownUrl = null;
        this.frames.clear();
        this._flushRevokes();
        if (this.el && this.el.parentNode) this.el.parentNode.removeChild(this.el);
        this.listeners = {};
    };

    // ---------------------------------------------------------------- events / state

    Player.prototype._emit = function (evt, payload) {
        const list = this.listeners[evt];
        if (!list || !list.length) return;
        const copy = list.slice();
        for (let i = 0; i < copy.length; i++) {
            try { copy[i](payload); } catch (e) { if (root.console) console.error('[tlplayer] listener', evt, e); }
        }
    };

    Player.prototype._setState = function (s) {
        if (s === this.state) return;
        this.state = s;
        this._emit('state', s);
    };

    Player.prototype._updateState = function () {
        if (this.dead) return;
        const v = this.video;
        let s;
        if (this.errorCode) s = 'error';
        else if (this.scrub) s = 'scrubbing';
        else if (this.ended) s = 'ended';
        else if (!this.outer) s = 'idle';
        else if (this.overlaySeg || this.gapHold) s = 'paused';
        else if (!this.win) s = this.dataReady && this.pendingWall === null ? 'idle' : 'buffering';
        else if (this.loadingSource || v.seeking || (this.intent === 'play' && (this.waiting || v.readyState < 3))) s = 'buffering';
        else if (!v.paused) s = 'playing';
        else s = 'paused';
        this._setState(s);
    };

    Player.prototype._t = function (key, en, vars) {
        const fn = this.o.t || (BYD.i18n && BYD.i18n.t);
        const tryKey = function (k) {
            if (typeof fn !== 'function') return null;
            try {
                const s = fn(k, vars);
                return typeof s === 'string' && s && s !== k && s.indexOf('tlview.') !== 0 ? s : null;
            } catch (e) {
                return null;
            }
        };
        return tryKey('tlview.' + key) || tryKey(key) || interpolate(en, vars);
    };

    // [status]: the HTTP status behind an 'auth' failure (401 = session expired, 403 = refused).
    Player.prototype._fail = function (seg, code, status) {
        this.errorCode = code;
        this.loadingSource = false;
        this.intent = 'pause';
        try { this.video.pause(); } catch (e) { /* detached */ }
        this._releaseHold();
        this._updateState();
        const msg = code === 'network' || code === 'auth' ? this._t('offline', "Can't reach the recorder")
            : this._t('clipError', "Couldn't play this clip");
        this._emit('error', { segment: seg || (this.curItem ? this._segOf(this.curItem) : null), message: msg, code: code, status: status || null });
    };

    Player.prototype._recoverFromError = function () {
        const w = this._currentWall();
        this.errorCode = null;
        this.netTries = 0;
        this.mediaTries = 0;
        this.reloads = [];
        if (!this.outer) return;
        if (isNum(w)) this._openWindow(w); else this._kick();
    };

    // ---------------------------------------------------------------- data (/api/timeline)

    Player.prototype._fetchData = function (g, fresh) {
        const self = this, win = apiWindow(this.outer);
        const failed = function (err) {
            if (g !== self.gen || self.dead) return;
            if (err && err.status === 401) { self._fail(null, 'auth', 401); return; }
            self.dataFailed = true;
            self._kick();
        };
        // opts.data(win): a host already holding this window (the view's day store) hands its copy over instead of a second fetch.
        let shared = null;
        if (typeof this.o.data === 'function') {
            try { shared = this.o.data({ from: win.from, to: win.to, fresh: !!fresh }); } catch (e) { shared = null; }
        }
        if (shared && typeof shared.then === 'function') {
            shared.then(function (json) { if (g === self.gen && !self.dead) self._setData(json); }, failed);
            return;
        }
        const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
        this.ctl.data = ctl;
        fetch(apiUrl(win), { signal: ctl ? ctl.signal : undefined, cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) {
                if (r.ok) return r.json();
                const err = new Error('http ' + r.status);
                err.status = r.status;
                throw err;
            })
            .then(function (json) { if (g === self.gen && !self.dead) self._setData(json); })
            .catch(failed);
    };

    Player.prototype._setData = function (json) {
        const segs = (json && Array.isArray(json.segments) ? json.segments : []).filter(function (s) { return s && s.id && isNum(s.start); });
        segs.sort(function (a, b) { return a.start - b.start; });
        this.data = json;
        this.segs = segs;
        this.segById = new Map(segs.map(function (s) { return [s.id, s]; }));
        this.busy = busyIntervals({ marks: json && json.marks, road: json && json.road, segments: segs });
        this.dataReady = true;
        const h = this.gapHold;
        if (h && !h.item && !isNum(h.wall) && isNum(h.from) && this.outer) {
            // The hold came before the clip list: now the next footage is known.
            const nx = nextPlayableStart(segs, h.from, this.blocked);
            if (nx !== null && nx < this.outer.to) {
                h.wall = nx;
                this._emit('gap', { from: h.from, to: nx, user: true });
            }
        }
        if (this.tb) this._annotate(this.tb);
        if (this.curItem) this._emitItem(this.curItem, true);
        this._emitSources(true);
        this._kick();
        this._updateState();
    };

    Player.prototype._annotate = function (tb) {
        for (let k = 0; k < tb.items.length; k++) {
            const it = tb.items[k];
            const s = this.segById.get(it.id);
            it.seg = s && s.fp === it.fp ? s : (s || null);
            it.type = it.seg ? it.seg.type : null;
        }
    };

    Player.prototype._segOf = function (it) {
        return it.seg || { id: it.id, fp: it.fp, videoUrl: '/video/id/' + encodeURIComponent(it.id), eventUrl: '/api/events/id/' + encodeURIComponent(it.id) };
    };

    // ---------------------------------------------------------------- windows / sources

    Player.prototype._kick = function () {
        if (this.dead || !this.outer || this.win) return;
        let target = this.pendingWall;
        if (target === null) {
            if (!this.dataReady && !this.dataFailed) return;
            target = this.dataReady ? nextPlayableStart(this.segs, this.outer.from - SEAM_MS - 1, this.blocked) : this.outer.from;
            if (target === null || target >= this.outer.to) { this._setEmpty(); return; }
        }
        this._openWindow(target);
    };

    Player.prototype._setEmpty = function () {
        this._closeSource();
        this.win = null;
        this.tb = null;
        this.ended = false;
        this._releaseHold();
        this._emitSources(true);
        this._setState('idle');
    };

    Player.prototype._openWindow = function (wall) {
        if (this.dead || !this.outer) return;
        const land = this.dataReady ? landing(wall, this.segs, this.blocked) : { wall: wall, blockedSeg: null };
        this.pendingOverlay = land.blockedSeg && land.blockedSeg.start <= wall ? { seg: land.blockedSeg, wall: wall } : null;
        this.win = playbackWindow(land.wall, this.outer);
        this._openSource(land.wall);
    };

    Player.prototype._currentWall = function () {
        if (this.pendingWall !== null) return this.pendingWall;
        if (this.virtualWall !== null) return this.virtualWall;
        if (this.tb && !this.loadingSource) {
            const w = mediaToWall(this.tb, this._mediaNow());
            if (isNum(w)) return w;
        }
        return this.lastWall !== null ? this.lastWall : (this.win ? this.win.from : (this.outer ? this.outer.from : null));
    };

    Player.prototype._openSource = function (startWall) {
        if (this.dead || !this.win) return;
        const sg = ++this.srcGen;
        if (this.win && (startWall < this.win.from || startWall >= this.win.to)) this.win = playbackWindow(startWall, this.outer);
        this._holdFrame();
        this._closeSource();
        this.loadingSource = true;
        this.tb = null;
        this.srcSig = null;
        this.startMedia = null;
        this.boundaryKey = null;
        this.floor = null;
        this.pendingWall = startWall;
        this.waiting = false;
        this.runAt = 0;
        this.stallAt = 0;
        this._updateState();
        this._emitPosition(true);
        const self = this;
        this._engine().then(function (kind) {
            if (sg !== self.srcGen || self.dead) return;
            // The engine decides whether a fast speed can play full frames (first open only learns it here).
            const m = self._skimFor(self._effectiveSpeed());
            if (m !== self.mode) { self.mode = m; self._applyRate(); }
            if (sg !== self.srcGen || self.dead) return;
            const url = timelineUrl(self.win, self.prefer, self.mode);
            if (kind === 'hls') self._openHls(url, sg);
            else if (kind === 'native') self._openNative(url, sg);
            else self._fail(null, 'unsupported');
        });
    };

    Player.prototype._engine = function () {
        if (this.engineKind) return Promise.resolve(this.engineKind);
        const v = this.video;
        const nativeOk = typeof v.canPlayType === 'function' && v.canPlayType('application/vnd.apple.mpegurl') !== '';
        const self = this;
        if (!mediaSourceCtor()) {
            this.engineKind = nativeOk ? 'native' : null;
            return Promise.resolve(this.engineKind);
        }
        return ensureHls().then(function (H) {
            let ok = false;
            try { ok = !!(H && H.isSupported()); } catch (e) { ok = false; }
            self.engineKind = ok ? 'hls' : (nativeOk ? 'native' : null);
            return self.engineKind;
        });
    };

    Player.prototype._closeSource = function () {
        clearTimeout(this.retryTimer);
        this.retryTimer = 0;
        if (this.ctl.playlist) { try { this.ctl.playlist.abort(); } catch (e) { /* done */ } }
        this.ctl.playlist = null;
        const h = this.hls;
        this.hls = null;
        this.hlsLoading = false;
        if (h) { try { h.destroy(); } catch (e) { /* torn down */ } }
        const v = this.video;
        if (this.engineKind === 'native' && v.getAttribute('src')) {
            v.removeAttribute('src');
            try { v.load(); } catch (e) { /* idle */ }
        }
        this.loadingSource = false;
    };

    Player.prototype._hlsConfig = function () {
        const policy = function (ttfb, load, retries) {
            return {
                default: {
                    maxTimeToFirstByteMs: ttfb, maxLoadTimeMs: load,
                    timeoutRetry: { maxNumRetry: 1, retryDelayMs: 0, maxRetryDelayMs: 0 },
                    errorRetry: { maxNumRetry: retries, retryDelayMs: 1000, maxRetryDelayMs: 4000 }
                }
            };
        };
        return {
            maxBufferLength: 30,
            maxMaxBufferLength: 60,
            backBufferLength: 30,
            enableWorker: true,
            lowLatencyMode: false,
            autoStartLoad: false,
            startFragPrefetch: false,
            capLevelToPlayerSize: false,
            manifestLoadPolicy: policy(20000, 30000, 1),
            playlistLoadPolicy: policy(20000, 30000, 1),
            fragLoadPolicy: policy(10000, 30000, 2),
            xhrSetup: function (xhr, url) {
                const tok = authToken();
                if (!tok || !sameOrigin(url)) return;
                if (xhr.readyState === 0) xhr.open('GET', url, true);
                xhr.setRequestHeader('Authorization', 'Bearer ' + tok);
            }
        };
    };

    Player.prototype._openHls = function (url, sg) {
        const H = root.Hls;
        let hls;
        try {
            hls = new H(this._hlsConfig());
        } catch (e) {
            this._fail(null, 'unsupported');
            return;
        }
        this.hls = hls;
        const E = H.Events;
        const self = this;
        const live = function () { return sg === self.srcGen && !self.dead && self.hls === hls; };
        hls.on(E.LEVEL_LOADED, function (ev, d) {
            if (!live()) return;
            const details = d && d.details;
            Promise.resolve().then(function () { if (live()) self._onHlsLevel(details); });
        });
        hls.on(E.LEVEL_PTS_UPDATED, function () { if (live()) self.tbDirty = true; });
        hls.on(E.FRAG_LOADING, function (ev, d) { if (live()) self._guard(d && d.frag); });
        hls.on(E.FRAG_PARSING_INIT_SEGMENT, function (ev, d) { if (live()) self._onInitParsed(d && d.frag, d && d.tracks); });
        hls.on(E.FRAG_BUFFERED, function () { if (live()) { self.netTries = 0; } });
        hls.on(E.ERROR, function (ev, d) { if (live()) self._onHlsError(d || {}); });
        hls.attachMedia(this.video);
        hls.loadSource(absUrl(url));
    };

    Player.prototype._hlsFrags = function (details) {
        const list = (details && details.fragments) || [];
        return list.map(function (f) {
            return {
                start: f.start, duration: f.duration, pdt: f.programDateTime, url: f.url, cc: f.cc,
                map: f.initSegment ? f.initSegment.url : null
            };
        });
    };

    Player.prototype._onHlsLevel = function (details) {
        if (this.tb && !this.loadingSource) return;
        const tb = buildTable(this._hlsFrags(details), details ? details.startSN : 0);
        this._onTable(tb);
    };

    Player.prototype._rebuildTable = function () {
        this.tbDirty = false;
        const h = this.hls;
        const lvl = h && h.levels && h.levels[0];
        if (!lvl || !lvl.details || !this.tb) return;
        const now = perfNow();
        if (now - this.tbBuiltAt < 250) { this.tbDirty = true; return; }
        this.tbBuiltAt = now;
        const tb = buildTable(this._hlsFrags(lvl.details), lvl.details.startSN);
        if (tb.items.length !== this.tb.items.length) return;
        this._annotate(tb);
        const cur = this.curItem;
        this.tb = tb;
        if (cur) { this.curItem = tb.items[cur.index]; }
    };

    Player.prototype._openNative = function (url, sg) {
        const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
        this.ctl.playlist = ctl;
        const self = this;
        fetch(url, { signal: ctl ? ctl.signal : undefined, cache: 'no-store', credentials: 'same-origin' })
            .then(function (r) {
                if (!r.ok) {
                    const err = new Error('http ' + r.status);
                    err.status = r.status;
                    throw err;
                }
                return r.text();
            })
            .then(function (text) {
                if (sg !== self.srcGen || self.dead) return;
                const pl = parsePlaylist(text);
                const tb = buildTable(pl.frags, 0);
                self.nativeFrags = pl.frags;
                self.nativeStart = null;
                self._onTable(tb);
                if (sg !== self.srcGen || !self.tb) return;
                self.video.src = absUrl(url);
            })
            .catch(function (err) {
                if (sg !== self.srcGen || self.dead || (err && err.name === 'AbortError')) return;
                self._onPlaylistError(err && err.status ? err.status : 0);
            });
    };

    Player.prototype._onMetadata = function () {
        this._applyRate();
        const v = this.video;
        if (this.engineKind === 'native' && this.tb && !this.tb.hasPdt && this.nativeFrags && typeof v.getStartDate === 'function') {
            const sd = v.getStartDate();
            const base = sd && typeof sd.getTime === 'function' ? sd.getTime() : NaN;
            if (isNum(base)) {
                const tb = buildTable(this.nativeFrags, 0, base);
                this._annotate(tb);
                this.tb = tb;
            }
        }
        if (this.engineKind === 'native' && this.nativeStart !== null && isNum(this.nativeStart)) {
            const t = this.nativeStart;
            this.nativeStart = null;
            try { this.video.currentTime = t; } catch (e) { /* not seekable yet */ }
        }
        if (this.intent === 'play' && !this.overlaySeg && !this.gapHold && !this.scrub) this._playMedia();
    };

    // The playlist's table is ready: apply the pending target and start loading there.
    Player.prototype._onTable = function (tb) {
        this.loadingSource = false;
        this.tbBuiltAt = perfNow();
        if (!tb.n) {
            this.tb = null;
            this._endOfWindow(this.win.to, true);
            return;
        }
        this._annotate(tb);
        this.tb = tb;
        const w = this.pendingWall !== null ? this.pendingWall : tb.ws[0];
        this.pendingWall = null;
        const user = this.pendingUser;
        this.pendingUser = null;
        if (user && !this.pendingOverlay && this._holdDeferred(tb, user.wall)) return;
        const r = wallToMedia(tb, w);
        if (r.past) {
            this._endOfWindow(tb.we[tb.n - 1], false);
            return;
        }
        const it = tb.items[tb.itemOf[r.i]];
        const ov = this.pendingOverlay;
        this.pendingOverlay = null;
        if (ov) {
            this._enterBlocked(this._segItem(ov.seg), ov.wall, r.gap ? it.ws : w);
            return;
        }
        if (this._isBlocked(it)) {
            this._enterBlocked(it, w, null);
            return;
        }
        const sig = this.sigs.get(this._sigKey(it));
        this.srcSig = this.mode ? 'v' : (sig || null);
        this._seekMedia(r.media);
        this.lastWall = r.gap ? it.ws : w;
        this._setCurrentItem(it);
        this._probeAhead(it);
        if (this.intent === 'play') this._playMedia();
        this._updateState();
        this._emitPosition(true);
    };

    // Until the element reaches a fresh source's start position its currentTime is meaningless (startMedia).
    Player.prototype._seekMedia = function (t) {
        const v = this.video;
        this.floor = null;
        if (this.engineKind === 'hls' && this.hls) {
            if (!this.hlsLoading || (this.startMedia !== null && v.readyState < 1)) {
                this.hlsLoading = true;
                this.hls.startLoad(t);
                if (v.readyState >= 1) v.currentTime = t; else this.startMedia = t;
            } else {
                v.currentTime = t;
            }
            return;
        }
        if (v.readyState >= 1) {
            try { v.currentTime = t; } catch (e) { this.nativeStart = t; this.startMedia = t; }
        } else {
            this.nativeStart = t;
            this.startMedia = t;
        }
    };

    Player.prototype._mediaNow = function () {
        return this.startMedia !== null ? this.startMedia : this.video.currentTime;
    };

    Player.prototype._startSettled = function () {
        if (this.startMedia === null) return true;
        const v = this.video;
        if (v.readyState >= 1 && !v.seeking && Math.abs(v.currentTime - this.startMedia) < 0.75) {
            this.startMedia = null;
            return true;
        }
        return false;
    };

    // The landing may have moved the window to the next footage; the user's own wall decides the hold.
    Player.prototype._holdDeferred = function (tb, uw) {
        const ur = wallToMedia(tb, uw);
        if (!ur) return false;
        let next = null;
        if (ur.past) {
            if (!(uw > tb.we[tb.n - 1])) return false;
            next = this.dataReady ? nextPlayableStart(this.segs, Math.max(uw, tb.we[tb.n - 1]), this.blocked) : null;
        } else if (ur.gap) {
            if (tb.items[tb.itemOf[ur.i]].ws - uw < SEAM_MS) return false;
            if (this.dataReady) {
                const land = landing(uw, this.segs, this.blocked);
                if (land.blockedSeg && land.blockedSeg.start <= uw) return false;
            }
            next = tb.items[tb.itemOf[ur.i]].ws;
        } else {
            return false;
        }
        this._holdInGap(uw, next !== null && next < this.outer.to ? next : null);
        return true;
    };

    // User seeks into unrecorded time keep the playhead there; play() continues from the next footage.
    Player.prototype._holdInGap = function (ms, next) {
        const playing = this.intent === 'play' && !!this.carry;
        this.carry = false;
        // While playing, a move there carries on from the next footage; the view says what was skipped.
        if (playing && isNum(next)) {
            this._emit('gap', { from: ms, to: next, user: true, skip: true });
            this.seekWall(next, true);
            return;
        }
        this.video.pause();
        this.intent = 'pause';
        // Next footage unknown (none, or data still loading): the hold keeps its own wall for play().
        this.gapHold = isNum(next) ? { wall: next } : { wall: null, from: ms };
        this.virtualWall = ms;
        this.lastWall = ms;
        // Nothing after it in this window: the view looks on later days when this was playing.
        this._emit('gap', { from: ms, to: isNum(next) ? next : null, user: true, play: playing || undefined });
        this._updateState();
        this._emitPosition(true);
    };

    Player.prototype._seekInTable = function (ms, user) {
        const tb = this.tb;
        const r = wallToMedia(tb, ms);
        if (!r) return;
        if (r.past) {
            const next = this.dataReady ? nextPlayableStart(this.segs, Math.max(ms, tb.we[tb.n - 1]), this.blocked) : null;
            if (user && ms > tb.we[tb.n - 1]) {
                this._holdInGap(ms, next !== null && next < this.outer.to ? next : null);
                return;
            }
            if (next !== null && next < this.outer.to) {
                this._openWindow(next);
                return;
            }
            this._seekMedia(Math.max(tb.ms[tb.n - 1], tb.me[tb.n - 1] - 0.05));
            this.ended = true;
            this.video.pause();
            this.lastWall = tb.we[tb.n - 1];
            this._updateState();
            this._emitPosition(true);
            return;
        }
        const it = tb.items[tb.itemOf[r.i]];
        if (r.gap && this.dataReady) {
            const land = landing(ms, this.segs, this.blocked);
            if (land.blockedSeg && land.blockedSeg.start <= ms) {
                this._enterBlocked(this._segItem(land.blockedSeg), ms, it.ws);
                return;
            }
        }
        // A hole under a seam is drawn as solid footage: land on the next frame, no hold.
        if (r.gap && user && it.ws - ms >= SEAM_MS) {
            this._holdInGap(ms, it.ws);
            return;
        }
        if (this._isBlocked(it)) {
            this._enterBlocked(it, ms, null);
            return;
        }
        if (this._isBarrier(it)) {
            this._openSource(r.gap ? it.ws : ms);
            return;
        }
        const target = r.gap ? it.ws : ms;
        if (slideDir(target, this.win, this.outer) !== 0 && this._footageBeyond(target)) {
            this._openWindow(target);
            return;
        }
        this._seekMedia(r.media);
        this.lastWall = target;
        this._setCurrentItem(it);
        this._probeAhead(it);
        if (this.intent === 'play') this._playMedia();
        this._updateState();
        this._emitPosition(true);
    };

    // Slide only when the new window would reach footage the current one lacks.
    Player.prototype._footageBeyond = function (w) {
        if (!this.dataReady) return true;
        const d = slideDir(w, this.win, this.outer);
        const nw = playbackWindow(w, this.outer);
        if (d === -1 || (d === 2 && w < this.win.from)) return hasFootage(this.segs, nw.from, this.win.from, this.blocked);
        if (d === 1 || d === 2) return hasFootage(this.segs, this.win.to, nw.to, this.blocked);
        return false;
    };

    Player.prototype._segItem = function (s) {
        return { id: s.id, fp: s.fp, key: s.id + '|' + s.fp, seg: s, type: s.type, index: -1, ws: s.start, we: s.end };
    };

    // No playable media after lastWall in this playlist. An empty playlist passes win.to, so every
    // retry starts past the previous window and the walk always ends at the loaded window's edge.
    Player.prototype._endOfWindow = function (lastWall, emptyPlaylist) {
        const u = this.pendingUser;
        if (u && emptyPlaylist) {
            // A user seek into a window with no footage at all holds where the user put it. The empty
            // window is dropped, so the next seek or Play opens a fresh one instead of waiting on it.
            this.pendingUser = null;
            const nx = this.dataReady ? nextPlayableStart(this.segs, u.wall, this.blocked) : null;
            this._closeSource();
            this.win = null;
            this.tb = null;
            this._holdInGap(u.wall, nx !== null && nx < this.outer.to ? nx : null);
            return;
        }
        const next = this.dataReady ? nextPlayableStart(this.segs, lastWall, this.blocked) : null;
        if (next !== null && next < this.outer.to) {
            if (!emptyPlaylist && !this.continueNext && next - lastWall > SEAM_MS) {
                this.video.pause();
                this.gapHold = { wall: next };
                this.lastWall = lastWall;
                this._emit('gap', { from: lastWall, to: next });
                this._updateState();
                this._emitPosition(true);
                return;
            }
            if (!emptyPlaylist && this.continueNext && next - lastWall > SEAM_MS) this._emit('gap', { from: lastWall, to: next, cont: true });
            this._openWindow(emptyPlaylist ? Math.max(next, lastWall) : next);
            return;
        }
        if (!this.dataReady && this.win && this.win.to < this.outer.to && this.blindSlide < 24) {
            this.blindSlide++;
            this._openWindow(Math.min(this.outer.to - 1, this.win.to + WIN_BACK_MS));
            return;
        }
        if (emptyPlaylist && !this.tb) {
            this._setEmpty();
            return;
        }
        this.ended = true;
        this.video.pause();
        this._updateState();
        this._emitPosition(true);
    };

    Player.prototype._allowReload = function () {
        const now = Date.now();
        this.reloads = this.reloads.filter(function (t) { return now - t < RELOAD_SPAN_MS; });
        if (this.reloads.length >= RELOAD_LIMIT) return false;
        this.reloads.push(now);
        return true;
    };

    // ---------------------------------------------------------------- unsupported / barriers

    Player.prototype._sigKey = function (it) { return it.key + (this.mode ? '|k' : '|m'); };

    Player.prototype._isBlocked = function (it) { return !!it && this.blocked.has(it.key); };

    // hls.js can't move one MediaSource between muxed audio+video and video-only SourceBuffers.
    Player.prototype._isBarrier = function (it) {
        if (!it || this.engineKind !== 'hls' || this.mode || !this.srcSig) return false;
        const sig = this.sigs.get(this._sigKey(it));
        return !!sig && sig !== this.srcSig;
    };

    Player.prototype._block = function (key, reason) {
        if (this.blocked.has(key)) return;
        this.blocked.set(key, reason);
        const self = this;
        const sg = this.srcGen;
        setTimeout(function () { if (sg === self.srcGen && !self.dead) self._onBlockedKey(key); }, 0);
    };

    // A clip found unplayable while the playhead sits on it (often before the start seek lands).
    Player.prototype._onBlockedKey = function (key) {
        if (this.overlaySeg || this.gapHold || this.scrub || !this.tb || this.loadingSource) return;
        const pending = this.startMedia !== null;
        const t = pending ? this.startMedia : this.video.currentTime;
        const it = itemAtMedia(this.tb, t);
        if (!it || it.key !== key) return;
        this._enterBlocked(it, pending && this.lastWall !== null ? this.lastWall : mediaToWall(this.tb, t), null);
    };

    Player.prototype._stopHls = function () {
        if (this.hls && this.hlsLoading) {
            this.hlsLoading = false;
            try { this.hls.stopLoad(); } catch (e) { /* stopped */ }
        }
    };

    Player.prototype._guard = function (frag) {
        const ref = frag && idFromUrl(frag.url);
        if (!ref) return;
        const key = ref.id + '|' + ref.fp;
        if (this.blocked.has(key)) { this._stopHls(); return; }
        if (!this.mode && this.srcSig) {
            const sig = this.sigs.get(key + '|m');
            if (sig && sig !== this.srcSig) this._stopHls();
        }
    };

    Player.prototype._onInitParsed = function (frag, tracks) {
        const ref = frag && idFromUrl(frag.url);
        if (!ref || !tracks) return;
        const key = ref.id + '|' + ref.fp;
        const vt = tracks.audiovideo || tracks.video;
        if (vt && vt.codec && !mseSupports(vt.container, vt.codec)) {
            this._block(key, 'codec');
            this._stopHls();
            return;
        }
        if (this.mode) return;
        const sig = sigOfTracks(tracks);
        if (sig) this.sigs.set(key + '|m', sig);
        if (!this.srcSig) this.srcSig = sig;
        else if (sig && sig !== this.srcSig) this._stopHls();
    };

    Player.prototype._probeAhead = function (it) {
        if (this.engineKind !== 'hls' || !this.tb || !it) return;
        const tb = this.tb;
        const hevcOk = fourccSupported('hvc1');
        for (let k = Math.max(0, it.index); k < tb.items.length; k++) {
            const x = tb.items[k];
            if (x.ms - it.ms > PROBE_AHEAD_S) break;
            if (this.mode && hevcOk) break;
            this._probe(x);
        }
    };

    Player.prototype._probe = function (it) {
        if (!it.init || !it.id) return;
        const pk = this._sigKey(it);
        if (this.probes.has(pk) || this.sigs.has(pk) || this.blocked.has(it.key)) return;
        if (this.probeActive >= PROBE_CONCURRENCY) {
            if (this.probeQueue.indexOf(it) < 0) this.probeQueue.push(it);
            return;
        }
        this.probeActive++;
        this.probes.set(pk, true);
        const self = this;
        const mode = this.mode;
        fetch(it.init, { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.arrayBuffer() : null; })
            .then(function (buf) {
                if (!buf || self.dead) return;
                const info = initInfo(buf);
                if (info.video && !fourccSupported(info.video)) {
                    self._block(it.key, 'codec');
                    return;
                }
                if (!mode && info.sig) self.sigs.set(it.key + '|m', info.sig);
            })
            .catch(function () { self.probes.delete(pk); })
            .then(function () {
                self.probeActive--;
                const nx = self.probeQueue.shift();
                if (nx && !self.dead) self._probe(nx);
            });
    };

    // ---------------------------------------------------------------- tick / boundaries

    Player.prototype._loopStart = function () {
        if (this.raf || this.dead || typeof requestAnimationFrame !== 'function') return;
        const self = this;
        const step = function () {
            self.raf = 0;
            if (self.dead) return;
            self._tick();
            const v = self.video;
            if (!v.paused || v.seeking || self.loadingSource || self.holding) self.raf = requestAnimationFrame(step);
        };
        this.raf = requestAnimationFrame(step);
    };

    Player.prototype._vfcStart = function () {
        const v = this.video;
        if (typeof v.requestVideoFrameCallback !== 'function' || this.dead) return;
        const self = this;
        this.vfc = v.requestVideoFrameCallback(function (now, md) {
            self.vfc = 0;
            if (self.dead) return;
            self._onFrame(md && isNum(md.mediaTime) ? md.mediaTime : v.currentTime);
            self._vfcStart();
        });
    };

    // A frame is being presented: switch item-bound state (lens) on exactly this frame.
    Player.prototype._onFrame = function (mediaTime) {
        if (this.mediaTries && perfNow() - this.mediaErrAt > 10000) this.mediaTries = 0;
        if (this.tb && !this.loadingSource && !this.overlaySeg && this._startSettled()) {
            const it = itemAtMedia(this.tb, mediaTime);
            if (it) {
                this._setCurrentItem(it);
                if (!this._isBlocked(it)) { this.blockedRun = 0; this.fenceTries = 0; }
            }
        }
        if (this.holding && !this.loadingSource && !this.video.seeking) this._releaseHold();
        if (this.scrubRelease && !this.video.seeking) this._hideScrubFrame();
    };

    Player.prototype._onSeeked = function () {
        const self = this;
        if (typeof this.video.requestVideoFrameCallback !== 'function' && typeof requestAnimationFrame === 'function') {
            // Without frame callbacks, two animation frames after 'seeked' the new frame is on screen.
            requestAnimationFrame(function () {
                requestAnimationFrame(function () {
                    if (self.dead) return;
                    if (self.holding) self._releaseHold();
                    if (self.scrubRelease) self._hideScrubFrame();
                });
            });
        }
        this._tick();
        this._emitPosition(true);
    };

    Player.prototype._tick = function () {
        if (this.dead) return;
        if (this.tbDirty) this._rebuildTable();
        const tb = this.tb;
        const v = this.video;
        if (!tb || this.loadingSource || this.scrub) { this._emitPosition(false); return; }
        if (this.overlaySeg || this.gapHold || this.errorCode) { this._emitPosition(false); return; }
        if (!this._startSettled()) { this._emitPosition(false); return; }
        const t = v.currentTime;
        const it = itemAtMedia(tb, t);
        if (!it) return;
        if (typeof v.requestVideoFrameCallback !== 'function' || v.paused || this.curItem === null) this._setCurrentItem(it);
        if (this._isBlocked(it) && !v.seeking) {
            this._enterBlocked(it, mediaToWall(tb, t), null);
            return;
        }
        if (this._isBarrier(it) && !v.seeking) {
            this._openSource(mediaToWall(tb, t));
            return;
        }
        const wall = this._wallAt(t);
        this.lastWall = wall;
        this._trackWatched(it, !v.paused && !v.seeking);
        if (!v.paused && !v.seeking) {
            const bk = this.srcGen + ':' + it.index;
            const rate = Math.max(1, v.playbackRate || 1);
            if (this.boundaryKey !== bk && (it.me - t <= END_EPS_S * rate || (this.waiting && it.me - t <= STALL_END_S))) {
                this.boundaryKey = bk;
                if (this._boundary(it, false)) return;
            }
            const dir = slideDir(wall, this.win, this.outer);
            if (dir !== 0 && this._footageBeyond(wall)) {
                this._openWindow(wall);
                return;
            }
            if (this.skipQuiet && it.type) {
                const r = skipQuietStep(this.quiet, { wall: wall, type: it.type, busy: this.busy });
                if (r.switched) {
                    this._applySpeed(r.seekWall);
                    return;
                }
            }
        }
        if (!v.paused && this.lensItem && Date.now() - this.strengthAt > LENS_REFRESH_MS) {
            const sc = this.sidecars.get(this.lensItem.key);
            if (sc && sc.lens && sc.lens.raw === true) this._loadSidecar(this.lensItem);
        }
        this._emitSources(false);
        this._emitPosition(false);
    };

    // Any tick missed (pause, hold, scrub, hidden tab) restarts the count.
    Player.prototype._trackWatched = function (it, playing) {
        const seg = it && it.seg;
        const rate = this.video.playbackRate || 1;
        const hidden = typeof document !== 'undefined' && document.visibilityState === 'hidden';
        if (!playing || hidden || this.state !== 'playing' || this.mode || rate > 2 || !seg || seg.watched || !seg.id) {
            this.watch = null;
            return;
        }
        const now = perfNow(), key = seg.id + '|' + seg.fp;
        const w = this.watch;
        if (!w || w.key !== key || now - w.last > WATCH_GAP_MS) {
            this.watch = { key: key, since: now, last: now };
            return;
        }
        w.last = now;
        if (now - w.since < WATCHED_AFTER_MS || this.watchedPosted.has(key)) return;
        this.watchedPosted.add(key);
        const self = this;
        fetch('/api/recordings/id/' + encodeURIComponent(seg.id) + '/watched', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sizeBytes: seg.sizeBytes, mp4Mtime: seg.mp4Mtime })
        }).then(function (r) {
            if (r.ok) { seg.watched = true; return; }
            // A 4xx is retried once later (the server answers 404 for a brief database error too), then left.
            const once = r.status >= 400 && r.status < 500;
            if (!once || !self.watchedRetried.has(key)) {
                if (once) self.watchedRetried.add(key);
                setTimeout(function () { self.watchedPosted.delete(key); }, WATCHED_RETRY_MS);
            }
        }, function () { setTimeout(function () { self.watchedPosted.delete(key); }, WATCHED_RETRY_MS); });
        this.watch = null;
    };

    Player.prototype._wallAt = function (t) {
        let w = mediaToWall(this.tb, t);
        const v = this.video;
        const moving = !v.paused && !v.seeking;
        // Seam overlaps of up to a GOP would step the clock back; hold it until the media catches up.
        if (moving && this.floor !== null && w < this.floor && this.floor - w < FLOOR_MAX_MS) w = this.floor;
        this.floor = moving ? w : null;
        return w;
    };

    // Playhead at the end of `it`; returns true when it took over playback.
    Player.prototype._boundary = function (it, resumed) {
        const tb = this.tb;
        const next = tb && it.index >= 0 ? tb.items[it.index + 1] : null;
        if (!next) {
            if (resumed) return false;
            this._endOfWindow(it.we, false);
            return true;
        }
        if (!resumed && !this.continueNext && next.ws - it.we > SEAM_MS) {
            this.video.pause();
            this.gapHold = { item: it };
            this.lastWall = it.we;
            this._emit('gap', { from: it.we, to: next.ws });
            this._updateState();
            this._emitPosition(true);
            return true;
        }
        // Crossing a gap by itself: the view may know footage there that this table predates.
        if (!resumed && this.continueNext && next.ws - it.we > SEAM_MS) this._emit('gap', { from: it.we, to: next.ws, cont: true });
        if (this._isBlocked(next)) {
            this._enterBlocked(next, next.ws, null);
            return true;
        }
        if (this._isBarrier(next)) {
            this._openSource(next.ws);
            return true;
        }
        if (resumed) {
            this._playMedia();
            return true;
        }
        return false;
    };

    Player.prototype._onEnded = function () {
        const tb = this.tb;
        if (!tb || !tb.n || this.loadingSource || this.overlaySeg || this.gapHold || this.ended) return;
        const last = tb.items[tb.items.length - 1];
        const bk = this.srcGen + ':' + last.index;
        if (this.boundaryKey === bk) return;
        this.boundaryKey = bk;
        this._boundary(last, false);
    };

    // Overlay for an unplayable clip; playback resumes at `resumeWall` (or the next usable item).
    Player.prototype._enterBlocked = function (it, wall, resumeWall) {
        this.video.pause();
        this.gapHold = null;
        const seg = this._segOf(it);
        const reason = this.blocked.get(it.key) || 'codec';
        this._showOverlay(seg, reason);
        this.virtualWall = isNum(wall) ? wall : it.ws;
        this.lastWall = this.virtualWall;
        this.parkItem = it;
        this.curItem = it.index >= 0 ? it : this.curItem;
        this._lensOff();
        this._emitItem(it, false, seg);
        const msg = reason === 'codec' ? this._t('cantPlayHere', "This clip can't play in this browser") : this._t('clipError', "Couldn't play this clip");
        this._emit('error', { segment: seg, message: msg, code: reason === 'codec' ? 'unsupported' : 'media' });
        const target = isNum(resumeWall) ? resumeWall : this._resumeTarget(it);
        this.resume = { wall: target };
        const tr = target !== null && this.tb ? wallToMedia(this.tb, target) : null;
        const ti = tr && !tr.past ? this.tb.items[this.tb.itemOf[tr.i]] : null;
        // Preload the resume point behind the overlay when this playlist can play it.
        if (ti && !this._isBlocked(ti) && !this._isBarrier(ti) && (this.engineKind !== 'native' || this.video.getAttribute('src'))) this._seekMedia(tr.media);
        this.blockedRun++;
        clearTimeout(this.dwell);
        this.dwell = 0;
        if (this.continueNext && this.intent === 'play' && target !== null && this.blockedRun < 2) {
            const self = this;
            this.dwell = setTimeout(function () { self.dwell = 0; self._resume(); }, OVERLAY_DWELL_MS);
        }
        this._releaseHold();
        this._updateState();
        this._emitPosition(true);
    };

    Player.prototype._resumeTarget = function (it) {
        const tb = this.tb;
        if (tb && it.index >= 0) {
            for (let k = it.index + 1; k < tb.items.length; k++) {
                if (!this._isBlocked(tb.items[k])) return tb.items[k].ws;
            }
        }
        const after = isNum(it.we) ? it.we : (tb && tb.n ? tb.we[tb.n - 1] : null);
        if (after === null || !this.dataReady) return null;
        const next = nextPlayableStart(this.segs, after, this.blocked);
        return next !== null && next < this.outer.to ? next : null;
    };

    Player.prototype._resume = function () {
        clearTimeout(this.dwell);
        this.dwell = 0;
        const r = this.resume;
        this._hideOverlay();
        this.resume = null;
        this.virtualWall = null;
        if (r && r.wall !== null && isNum(r.wall)) {
            this.intent = 'play';
            this.seekWall(r.wall, true);
        } else {
            this.ended = true;
            this._updateState();
        }
    };

    Player.prototype._showOverlay = function (seg, reason) {
        this.overlaySeg = seg;
        this.ovMsg.textContent = reason === 'codec'
            ? this._t('cantPlayHere', "This clip can't play in this browser")
            : this._t('clipError', "Couldn't play this clip");
        const href = safeHref(seg && seg.videoUrl);
        if (href) {
            this.ovDl.href = href;
            this.ovDl.setAttribute('download', '');
            this.ovDl.textContent = this._t('download', 'Download');
            this.ovDl.hidden = false;
        } else {
            this.ovDl.removeAttribute('href');
            this.ovDl.hidden = true;
        }
        this.ov.hidden = false;
    };

    Player.prototype._hideOverlay = function () {
        this.overlaySeg = null;
        this.parkItem = null;
        this.ov.hidden = true;
    };

    Player.prototype._clearHolds = function () {
        clearTimeout(this.dwell);
        this.dwell = 0;
        this.gapHold = null;
        this.resume = null;
        this.virtualWall = null;
        if (this.overlaySeg) this._hideOverlay();
    };

    // ---------------------------------------------------------------- items, lens, sources, position

    Player.prototype._setCurrentItem = function (it) {
        const key = it ? this.srcGen + ':' + it.index + ':' + it.key : null;
        if (key === this.curItemKey) { this.curItem = it; return; }
        this.curItem = it;
        this.curItemKey = key;
        this._applyLens(it);
        this._emitItem(it, false);
        this._probeAhead(it);
        const nx = it && this.tb ? this.tb.items[it.index + 1] : null;
        if (nx) this._loadSidecar(nx);
        this._emitSources(true);
    };

    Player.prototype._emitItem = function (it, force, segOverride) {
        if (!it) return;
        const seg = segOverride || this._segOf(it);
        const spec = this._specSync(it);
        const key = seg.id + '|' + seg.fp + '|' + (spec ? spec.k1 + ',' + spec.aspect : '-') + '|' + (it.seg ? 1 : 0);
        if (!force && key === this.itemEmitKey) return;
        this.itemEmitKey = key;
        this._emit('item', { segment: seg, spec: spec || null });
    };

    // undefined = sidecar or strength still loading.
    Player.prototype._specSync = function (it) {
        const LD = BYD.LensDewarp;
        if (!LD || !it || !it.id) return null;
        const sc = this.sidecars.get(it.key);
        if (sc === undefined || (sc && typeof sc.then === 'function') || this.strength === null) return undefined;
        if (!sc) return null;
        try { return LD.specFromSidecar(sc, this.strength); } catch (e) { return null; }
    };

    Player.prototype._loadSidecar = function (it) {
        const LD = BYD.LensDewarp;
        if (!LD || !it || !it.id) return Promise.resolve(null);
        const self = this;
        const now = Date.now();
        if (this.strength === null || now - this.strengthAt > LENS_REFRESH_MS) {
            this.strengthAt = now;
            Promise.resolve(LD.fetchRectifyStrength()).then(function (s) {
                const v = Number(s);
                const changed = v !== self.strength;
                self.strength = isFinite(v) ? v : 0;
                if (changed && !self.dead && self.lensItem) self._applyLens(self.lensItem, true);
            }, function () { self.strength = 0; });
        }
        const cached = this.sidecars.get(it.key);
        if (cached !== undefined) return Promise.resolve(cached);
        const url = (it.seg && safeHref(it.seg.eventUrl)) || ('/api/events/id/' + encodeURIComponent(it.id));
        const p = fetch(url, { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .catch(function () { return null; })
            .then(function (json) {
                self.sidecars.set(it.key, json && typeof json === 'object' ? json : null);
                if (self.sidecars.size > 200) self.sidecars.delete(self.sidecars.keys().next().value);
                return self.sidecars.get(it.key);
            });
        this.sidecars.set(it.key, p);
        return p;
    };

    Player.prototype._applyLens = function (it, refresh) {
        this.lensItem = it;
        if (!it || this._isBlocked(it)) { this._lensOff(); return; }
        const spec = this._specSync(it);
        if (spec) this._lensOn(spec); else this._lensOff();
        if (spec === undefined || refresh) {
            const self = this;
            this._loadSidecar(it).then(function () {
                if (self.dead || self.lensItem !== it) return;
                const s2 = self._specSync(it);
                if (s2 === undefined) return;
                if (s2) self._lensOn(s2); else self._lensOff();
                self._emitItem(it, false);
            });
        }
    };

    Player.prototype._lensOn = function (spec) {
        const LD = BYD.LensDewarp;
        if (this.lensFailed || !LD) { this._lensOff(); return; }
        if (!this.dewarp) {
            let d;
            try { d = new LD(this.lensCanvas); } catch (e) { this.lensFailed = true; this._lensOff(); return; }
            const self = this;
            d.onFail = function (reason) {
                if (self.dewarp !== d) return;
                self.dewarp = null;
                self.lensKey = null;
                self.lensCanvas.hidden = true;
                // A lost context stays lost on its canvas: one fresh canvas, then plain video.
                if (reason === 'contextlost' && !self.lensReplaced && self.lensCanvas.parentNode) {
                    self.lensReplaced = true;
                    const c = self.lensCanvas.cloneNode(false);
                    c.hidden = true;
                    self.lensCanvas.parentNode.replaceChild(c, self.lensCanvas);
                    self.lensCanvas = c;
                } else {
                    self.lensFailed = true;
                }
            };
            this.dewarp = d;
        }
        const key = spec.k1 + ',' + spec.k2 + ',' + spec.aspect;
        if (key !== this.lensKey) {
            let ok = false;
            try { ok = this.dewarp.attach(this.video, { k1: spec.k1, k2: spec.k2, aspect: spec.aspect }); } catch (e) { ok = false; }
            if (!ok) { this._lensOff(); return; }
            this.lensKey = key;
        }
        this.lensCanvas.hidden = false;
    };

    Player.prototype._lensOff = function () {
        if (this.dewarp && this.lensKey) { try { this.dewarp.detach(); } catch (e) { /* gone */ } }
        this.lensKey = null;
        if (this.lensCanvas) this.lensCanvas.hidden = true;
    };

    Player.prototype._emitSources = function (force) {
        const now = perfNow();
        if (!force && now - this.sourcesAt < SOURCES_INTERVAL_MS) return;
        this.sourcesAt = now;
        const w = this._currentWall();
        const families = this.dataReady && isNum(w) ? sourcesAt(this.segs, w, this.blocked) : [];
        const it = this.curItem;
        const active = it && it.type ? familyOf(it.seg || { type: it.type }) : null;
        const key = families.join(',') + '|' + active + '|' + this.prefer;
        if (key === this.sourcesKey) return;
        this.sourcesKey = key;
        this._emit('sources', { families: families, active: active, prefer: this.prefer });
    };

    Player.prototype._emitPosition = function (force) {
        if (this.scrub) return;
        const now = perfNow();
        if (!force && now - this.posAt < POS_INTERVAL_MS) return;
        let wall = this.virtualWall !== null ? this.virtualWall : (this.pendingWall !== null ? this.pendingWall : this.lastWall);
        if (wall === null && this.tb && !this.loadingSource) wall = mediaToWall(this.tb, this.video.currentTime);
        if (!isNum(wall)) return;
        const v = this.video;
        const playing = !v.paused && !this.loadingSource && !this.waiting && this.virtualWall === null;
        if (!force && wall === this.posWall && playing === this.posPlaying) return;
        this.posAt = now;
        this.posWall = wall;
        this.posPlaying = playing;
        this._emit('position', { wall: wall, playing: playing });
    };

    // ---------------------------------------------------------------- speed / mute

    Player.prototype._effectiveSpeed = function () {
        // Skip quiet driving sets the speed itself, as on the head unit: 8x quiet drives, 1x everything else.
        if (this.skipQuiet) return this.quiet.mode === 'quiet' ? 8 : 1;
        return this.userSpeed;
    };

    // Keyframe skim only where full frames can't keep up: Safari's own HLS, or after this stream underran at that speed.
    Player.prototype._skimFor = function (eff) {
        return eff >= 4 && (this.engineKind === 'native' || (this.slowAt > 0 && eff >= this.slowAt)) ? eff : 0;
    };

    Player.prototype._applySpeed = function (atWall) {
        if (!this.skipQuiet && this.quiet.mode === 'quiet') this.quiet = newQuietState();
        const eff = this._effectiveSpeed();
        const mode = this._skimFor(eff);
        this.underruns = [];
        this.stallAt = 0;
        if (mode === this.mode) {
            this._applyRate();
            if (isNum(atWall) && this.tb && Math.abs(atWall - this._currentWall()) > 500) this.seekWall(atWall, true);
            return;
        }
        const w = isNum(atWall) ? atWall : this._currentWall();
        this.mode = mode;
        this._applyRate();
        if (this.win && isNum(w) && !this.dead) this._openSource(w);
    };

    Player.prototype._applyRate = function () {
        const v = this.video;
        const rate = this.mode ? 1 : this._effectiveSpeed();
        try {
            if (v.defaultPlaybackRate !== rate) v.defaultPlaybackRate = rate;
            if (v.playbackRate !== rate) v.playbackRate = rate;
        } catch (e) {
            // This browser refuses the rate: skim instead.
            if (!this.mode && rate >= 4 && this.slowAt !== rate) { this.slowAt = rate; this._applySpeed(null); return; }
        }
        this._bufferFor(rate);
        this._applyMute();
    };

    // The forward buffer covers the same wall time at any speed (media seconds grow with the rate).
    Player.prototype._bufferFor = function (rate) {
        const c = this.hls && this.hls.config;
        if (!c) return;
        const k = rate >= 4 ? rate / 2 : 1;
        c.maxBufferLength = 30 * k;
        c.maxMaxBufferLength = 60 * k;
    };

    Player.prototype._applyMute = function () {
        const m = this.userMuted || this.forcedMute || this.mode >= 4 || this._effectiveSpeed() >= 4;
        if (this.video.muted !== m) this.video.muted = m;
    };

    // An underrun mid-playback at a full-frame fast speed (not the start after a seek or source switch).
    Player.prototype._fastFull = function () {
        return !this.mode && this.intent === 'play' && this._effectiveSpeed() >= 4 && !this.video.seeking &&
            !(typeof document !== 'undefined' && document.visibilityState === 'hidden');
    };

    Player.prototype._stallStarted = function () {
        if (this.stallAt || !this.runAt || perfNow() - this.runAt < 1500 || !this._fastFull()) return;
        this.stallAt = perfNow();
        const self = this, at = this.stallAt;
        clearTimeout(this.stallTimer);
        this.stallTimer = setTimeout(function () { if (self.stallAt === at && self.waiting && self._fastFull()) self._tooSlow(); }, UNDERRUN_LONG_MS);
    };

    Player.prototype._stallEnded = function () {
        const at = this.stallAt;
        this.stallAt = 0;
        clearTimeout(this.stallTimer);
        if (!at || !this._fastFull()) return;
        const now = perfNow();
        if (now - at < UNDERRUN_MIN_MS) return;
        this.underruns = this.underruns.filter(function (t) { return now - t < UNDERRUN_SPAN_MS; });
        this.underruns.push(now);
        if (this.underruns.length >= 2) this._tooSlow();
    };

    Player.prototype._tooSlow = function () {
        const eff = this._effectiveSpeed();
        if (this.mode || eff < 4 || this.dead) return;
        this.slowAt = eff;
        this._emit('speedmode', { speed: eff, skim: true });
        this._applySpeed(this._currentWall());
    };

    Player.prototype._playMedia = function () {
        const v = this.video;
        // Loading stops while the view is hidden (suspend); playing picks it up where the element is.
        if (this.suspended) {
            this.suspended = false;
            if (this.engineKind === 'hls' && this.hls && !this.hlsLoading && this.tb) {
                this.hlsLoading = true;
                try { this.hls.startLoad(this.startMedia !== null ? this.startMedia : v.currentTime); } catch (e) { /* reopened on the next error */ }
            }
        }
        this._applyRate();
        this._applyMute();
        let p;
        try { p = v.play(); } catch (e) { p = null; }
        const self = this;
        if (p && typeof p.catch === 'function') {
            p.catch(function (err) {
                if (self.dead || !err) return;
                // Autoplay without a gesture: retry muted and tell the view.
                if (err.name === 'NotAllowedError' && !v.muted) {
                    self.forcedMute = true;
                    self._applyMute();
                    self._emit('muted', { muted: true, forced: true });
                    const p2 = v.play();
                    if (p2 && p2.catch) p2.catch(function () { self.intent = 'pause'; self._updateState(); });
                } else if (err.name === 'NotAllowedError') {
                    self.intent = 'pause';
                    self._updateState();
                }
            });
        }
        this._loopStart();
    };

    // ---------------------------------------------------------------- errors

    Player.prototype._onHlsError = function (d) {
        const H = root.Hls;
        if (!H) return;
        const D = H.ErrorDetails, T = H.ErrorTypes;
        const frag = d.frag;
        const ref = frag ? idFromUrl(frag.url) : null;
        const key = ref ? ref.id + '|' + ref.fp : null;
        const code = d.response && isNum(d.response.code) ? d.response.code : (d.networkDetails && isNum(d.networkDetails.status) ? d.networkDetails.status : 0);
        const codecErr = d.details === D.BUFFER_ADD_CODEC_ERROR || d.details === D.BUFFER_INCOMPATIBLE_CODECS_ERROR
            || d.details === D.MANIFEST_INCOMPATIBLE_CODECS_ERROR;
        if (codecErr) {
            const it = (key && this._itemByKey(key)) || this._loadingItem();
            if (it) this._block(it.key, 'codec');
            this._stopHls();
            this._afterBlock(it);
            return;
        }
        if (d.details === D.MANIFEST_LOAD_ERROR || d.details === D.MANIFEST_LOAD_TIMEOUT || d.details === D.LEVEL_LOAD_ERROR
            || d.details === D.LEVEL_EMPTY_ERROR || d.details === D.MANIFEST_PARSING_ERROR) {
            if (!d.fatal && d.details !== D.LEVEL_EMPTY_ERROR) return;
            this._onPlaylistError(d.details === D.LEVEL_EMPTY_ERROR ? 404 : code);
            return;
        }
        if (key && (this.blocked.has(key) || (this.srcSig && this.sigs.get(key + '|m') && this.sigs.get(key + '|m') !== this.srcSig))) {
            // Fallout of a fenced clip; hls.js has already reset/stopped by now, so keep the current item going.
            if (d.fatal) this._resumeBeforeFence();
            return;
        }
        if (!d.fatal) return;
        if (d.type === T.NETWORK_ERROR) {
            if (code === 401 || code === 403) { this._fail(null, 'auth', code); return; }
            if (code === 404 || code === 409 || code === 410) {
                if (this._allowReload()) { this._reopenFresh(); return; }
                this._fail(key ? this._segOfKey(key) : null, 'gone');
                return;
            }
            this._retry('net', key);
            return;
        }
        if (d.type === T.MEDIA_ERROR) {
            if (d.details === D.FRAG_PARSING_ERROR && key) {
                this._block(key, 'media');
                this._stopHls();
                this._afterBlock(this._itemByKey(key));
                return;
            }
            this._retry('media', key, d.details === D.MEDIA_SOURCE_REQUIRES_RESET);
            return;
        }
        this._retry('media', key, false);
    };

    Player.prototype._itemByKey = function (key) {
        const tb = this.tb;
        if (!tb) return null;
        const cur = this.curItem;
        let best = null;
        for (let k = 0; k < tb.items.length; k++) {
            if (tb.items[k].key !== key) continue;
            if (!best || (cur && Math.abs(tb.items[k].index - cur.index) < Math.abs(best.index - cur.index))) best = tb.items[k];
        }
        return best;
    };

    Player.prototype._segOfKey = function (key) {
        const it = this._itemByKey(key);
        return it ? this._segOf(it) : null;
    };

    // The item whose init hls.js was handling when an error had no fragment attached.
    Player.prototype._loadingItem = function () {
        const tb = this.tb;
        if (!tb) return null;
        const v = this.video;
        const now = this._mediaNow();
        const it = itemAtMedia(tb, now);
        if (!it) return null;
        if (this.startMedia !== null) return it;
        const buf = v.buffered;
        let end = -1;
        for (let i = 0; buf && i < buf.length; i++) if (buf.start(i) <= v.currentTime + 0.5 && buf.end(i) > end) end = buf.end(i);
        if (end >= 0 && end >= it.me - 0.25) return tb.items[it.index + 1] || it;
        return it;
    };

    Player.prototype._afterBlock = function (it) {
        if (this.overlaySeg) {
            // The preloaded resume point failed too: retarget silently, the overlay stays on its clip.
            if (this.parkItem && this.resume) this.resume.wall = this._resumeTarget(this.parkItem);
            return;
        }
        if (!it) { this._retry('media', null, false); return; }
        const cur = this.tb ? itemAtMedia(this.tb, this._mediaNow()) : null;
        if (!cur || cur.index >= it.index) {
            this._enterBlocked(it, this._isStartOf(it) ? it.ws : this._currentWall(), null);
            return;
        }
        // Prefetch hit the clip: the current item keeps playing and the boundary skips it.
        this._resumeBeforeFence();
    };

    // Restart loading at the playhead after a fenced clip's error; the guard stops again at the fence.
    Player.prototype._resumeBeforeFence = function () {
        const tb = this.tb, h = this.hls;
        if (!tb || !h) return;
        const t = this._mediaNow();
        const cur = itemAtMedia(tb, t);
        if (cur && this._isBlocked(cur)) { this._enterBlocked(cur, mediaToWall(tb, t), null); return; }
        if (cur && this._isBarrier(cur)) { this._openSource(mediaToWall(tb, t)); return; }
        if (++this.fenceTries > RETRY_MS.length) { this._retry('media', cur ? cur.key : null, false); return; }
        try {
            if (this.video.error) h.recoverMediaError(); else h.startLoad(t);
            this.hlsLoading = true;
        } catch (e) {
            this._openSource(mediaToWall(tb, t));
        }
    };

    Player.prototype._isStartOf = function (it) {
        return !(this._mediaNow() > it.ms + 0.25);
    };

    // Backoff 1/2/4 s. hls.js already rebuilt the MediaSource when `reset` is set, so loading just restarts.
    Player.prototype._retry = function (kind, key, reset) {
        const n = kind === 'net' ? this.netTries++ : this.mediaTries++;
        if (kind !== 'net') this.mediaErrAt = perfNow();
        if (n >= RETRY_MS.length) {
            this._fail(key ? this._segOfKey(key) : null, kind === 'net' ? 'network' : 'media');
            return;
        }
        const self = this;
        const sg = this.srcGen;
        clearTimeout(this.retryTimer);
        this.retryTimer = setTimeout(function () {
            if (sg !== self.srcGen || self.dead || !self.hls) return;
            const t = self._mediaNow();
            if (kind === 'net' || (reset && n === 0)) {
                self.hlsLoading = true;
                try { self.hls.startLoad(t); } catch (e) { self._reopenFresh(); }
            } else if (n < RETRY_MS.length - 1) {
                try { self.hls.recoverMediaError(); self.hlsLoading = true; } catch (e) { self._reopenFresh(); }
            } else {
                self._reopenFresh();
            }
        }, RETRY_MS[n]);
    };

    Player.prototype._reopenFresh = function () {
        const w = this._currentWall();
        this.sourcesKey = null;
        if (this.outer) {
            const g = this.gen;
            this._abortData();
            this.dataReady = false;
            this._fetchData(g, true);
        }
        if (isNum(w)) this._openSource(w);
    };

    Player.prototype._abortData = function () {
        if (this.ctl.data) { try { this.ctl.data.abort(); } catch (e) { /* done */ } }
        this.ctl.data = null;
    };

    Player.prototype._abortFetches = function () {
        this._abortData();
        if (this.ctl.playlist) { try { this.ctl.playlist.abort(); } catch (e) { /* done */ } }
        this.ctl.playlist = null;
    };

    Player.prototype._onPlaylistError = function (status) {
        if (status === 401 || status === 403) { this._fail(null, 'auth', status); return; }
        if (status === 404) {
            const w = this.pendingWall !== null ? this.pendingWall : this.outer.from;
            this.loadingSource = false;
            this.tb = null;
            this._closeSource();
            this._endOfWindow(this.win ? this.win.to : w, true);
            return;
        }
        if (status === 400) { this._fail(null, 'media'); return; }
        const n = this.netTries++;
        if (n >= RETRY_MS.length) { this._fail(null, 'network'); return; }
        const self = this;
        const sg = this.srcGen;
        const w = this.pendingWall;
        clearTimeout(this.retryTimer);
        this.retryTimer = setTimeout(function () {
            if (sg !== self.srcGen || self.dead) return;
            self._openSource(isNum(w) ? w : self._currentWall());
        }, RETRY_MS[n]);
    };

    Player.prototype._onVideoError = function () {
        if (this.engineKind !== 'native' || this.dead) return;
        const err = this.video.error;
        const code = err ? err.code : 0;
        const tb = this.tb;
        if (!tb) return;
        const it = itemAtMedia(tb, this.nativeStart !== null && isNum(this.nativeStart) ? this.nativeStart : this.video.currentTime);
        if (code === 4 || code === 3) {
            if (it) this._block(it.key, code === 4 ? 'codec' : 'media');
            const target = it ? this._resumeTarget(it) : null;
            const w = this._currentWall();
            this._closeSource();
            this.tb = tb;
            if (it) this._enterBlocked(it, w, target);
            return;
        }
        if (this._allowReload()) this._openSource(this._currentWall());
        else this._fail(it ? this._segOf(it) : null, 'network');
    };

    // ---------------------------------------------------------------- hold frame

    Player.prototype._holdFrame = function () {
        const v = this.video;
        if (!this.lensCanvas.hidden) return;
        if (!(v.readyState >= 2) || !v.videoWidth) return;
        const c = this.holdCanvas;
        const w = Math.min(v.videoWidth, 1280);
        const h = Math.max(1, Math.round(v.videoHeight * w / v.videoWidth));
        try {
            if (c.width !== w) c.width = w;
            if (c.height !== h) c.height = h;
            c.getContext('2d').drawImage(v, 0, 0, w, h);
        } catch (e) {
            return;
        }
        clearTimeout(this.holdTimer);
        clearTimeout(this.holdSafety);
        c.style.opacity = '1';
        c.hidden = false;
        this.holding = true;
        const self = this;
        // A frozen picture must not outlive a slow reload.
        this.holdSafety = setTimeout(function () { self._releaseHold(); }, HOLD_MAX_MS);
    };

    Player.prototype._releaseHold = function () {
        if (!this.holding) return;
        this.holding = false;
        clearTimeout(this.holdSafety);
        const c = this.holdCanvas;
        c.style.opacity = '0';
        clearTimeout(this.holdTimer);
        this.holdTimer = setTimeout(function () { c.hidden = true; }, HOLD_FADE_MS + 40);
    };

    // ---------------------------------------------------------------- scrub frames

    Player.prototype._pumpFrame = function () {
        const want = this.frameWant;
        if (!want || !this.scrub || this.dead) return;
        const hit = this.frames.get(want.key);
        if (hit) { this.frameWant = null; this._showFrame(hit, want.seq); return; }
        const busy = this.frameReq;
        if (busy) {
            if (busy.key === want.key) return;
            // Same clip: let it land (live feedback), then fetch the latest. Other clip: last wins.
            if (busy.id === want.id && !busy.stale) return;
            busy.ctl.abort();
        }
        this.frameWant = null;
        const ctl = typeof AbortController !== 'undefined' ? new AbortController() : { abort: function () {}, signal: undefined };
        const req = { key: want.key, id: want.id, seq: want.seq, ctl: ctl, stale: false };
        this.frameReq = req;
        const self = this;
        const g = this.gen;
        const url = frameUrl(want.id, want.fp, want.at, want.w, this.strength);
        const attempt = function (left) {
            return fetch(url, { signal: ctl.signal, credentials: 'same-origin' }).then(function (r) {
                if (r.status === 503 && left > 0) {
                    return new Promise(function (res) { setTimeout(res, 1000); }).then(function () {
                        return req === self.frameReq ? attempt(left - 1) : null;
                    });
                }
                if (!r.ok && r.status !== 503) self.frameFail.add(req.key);
                return r.ok ? r.blob() : null;
            });
        };
        const stuck = setTimeout(function () { req.stale = true; }, 1500);
        attempt(2).then(function (blob) {
            if (!blob) return;
            const u = URL.createObjectURL(blob);
            if (self.dead || g !== self.gen) { URL.revokeObjectURL(u); return; }
            self.frames.set(req.key, u);
            if (self.scrub || self.scrubRelease) self._showFrame(u, req.seq);
        }).catch(function () { /* aborted or offline: keep the previous frame */ }).then(function () {
            clearTimeout(stuck);
            if (self.frameReq === req) self.frameReq = null;
            if (self.frameWant && self.scrub && !self.dead && !self.frameTimer) self._pumpFrame();
        });
    };

    Player.prototype._showFrame = function (url, seq) {
        if (seq < this.frameShownSeq) return;
        this.frameShownSeq = seq;
        const img = this.img;
        const self = this;
        const swap = function () {
            if (self.dead || self.frameShownSeq !== seq) return;
            const prev = self.shownUrl;
            img.src = url;
            img.hidden = false;
            self.shownUrl = url;
            if (prev && prev !== url) self._flushRevokes();
        };
        const pre = new Image();
        pre.src = url;
        if (typeof pre.decode === 'function') pre.decode().then(swap, swap); else swap();
    };

    Player.prototype._hideScrubFrame = function () {
        this.scrubRelease = false;
        this.img.hidden = true;
    };

    Player.prototype._revoke = function (url) {
        if (url === this.shownUrl) { this.revokeLater.push(url); return; }
        try { URL.revokeObjectURL(url); } catch (e) { /* gone */ }
    };

    Player.prototype._flushRevokes = function () {
        const keep = this.shownUrl;
        this.revokeLater = this.revokeLater.filter(function (u) {
            if (u === keep) return true;
            try { URL.revokeObjectURL(u); } catch (e) { /* gone */ }
            return false;
        });
    };

    Player.prototype._clearScrub = function (hideImg) {
        this.scrub = null;
        this.frameWant = null;
        clearTimeout(this.frameTimer);
        this.frameTimer = 0;
        if (this.frameReq) { this.frameReq.ctl.abort(); this.frameReq = null; }
        if (hideImg) this._hideScrubFrame();
    };

    BYD.tlplayer = {
        create: function (containerEl, opts) { return new Player(containerEl, opts); },
        _internals: {
            HLS_VERSION: HLS_VERSION, VENDOR_URL: VENDOR_URL,
            WINDOW: {
                BACK_MS: WIN_BACK_MS, AHEAD_MS: WIN_AHEAD_MS, EDGE_MS: WIN_EDGE_MS,
                rule: 'HLS window = [t - 10 min, t + 60 min] inside load({from,to}); reload it around the playhead and seek'
                    + ' to the same wall when the playhead is within 5 min of an edge with footage beyond, or a seek lands outside'
            },
            SEAM_MS: SEAM_MS, QUIET: { PAD_MS: QUIET_PAD_MS, HOLD_MS: QUIET_HOLD_MS, LOOKAHEAD_MS: QUIET_LOOKAHEAD_MS },
            parseIsoMs: parseIsoMs, parsePlaylist: parsePlaylist, buildTable: buildTable, idFromUrl: idFromUrl,
            skimOfUrl: skimOfUrl, fragIndexAtMedia: fragIndexAtMedia, itemAtMedia: itemAtMedia,
            mediaToWall: mediaToWall, wallToMedia: wallToMedia, familyOf: familyOf, isPlayable: isPlayable,
            sourcesAt: sourcesAt, mergeIntervals: mergeIntervals, busyIntervals: busyIntervals, inIntervals: inIntervals,
            newQuietState: newQuietState, skipQuietStep: skipQuietStep, playbackWindow: playbackWindow, slideDir: slideDir,
            landing: landing, nextPlayableStart: nextPlayableStart, hasFootage: hasFootage, timelineUrl: timelineUrl,
            apiUrl: apiUrl, apiWindow: apiWindow, frameUrl: frameUrl, frameWidth: frameWidth, frameTarget: frameTarget,
            Lru: Lru, initInfo: initInfo, sigOfTracks: sigOfTracks, Player: Player
        }
    };
})(typeof window !== 'undefined' ? window : globalThis);
