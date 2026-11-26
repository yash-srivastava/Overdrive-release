// Canvas timeline strip for the web Recordings Timeline (BYD.tlstrip).
// Browser-only (the car WebView intercepts events.html): ES2017, no build step.
(function () {
    'use strict';

    const MIN = 60000;
    const SPAN_MIN = 2 * MIN;
    const SPAN_MAX = 24 * 60 * MIN;
    const STEPS = [1, 2, 5, 10, 15, 30, 60, 120, 180, 240];
    const MINOR = { 1: 0.5, 2: 0.5, 5: 1, 10: 2, 15: 5, 30: 5, 60: 15, 120: 30, 180: 60, 240: 60, 360: 60 };
    const LABEL_GAP_PX = 110;
    const DECEL = 0.998;
    const RUBBER_K = 0.55;
    const SLOP_PX = 6;
    const SNAP_PX = 24;
    const MIN_RUN_PX = 8;
    const CLUSTER_PX = 26;
    const CURRENT_PX = 9;
    const VEL_WINDOW_MS = 80;
    const FLICK_PX_S = 150;
    const MAX_FLICK_PX_S = 9000;
    const OMEGA_STD = 2 * Math.PI / 0.35;
    const OMEGA_SLOW = 2 * Math.PI / 1.6;
    const CONTIG_MS = 1500;
    const FADE_MS = 150;
    const CLUSTER_ANIM_MS = 160;
    const HOLD_MS = 2500;
    const LIVE_EXTRAPOLATE_MS = 600000;
    const KIND_NAMES = ['person', 'vehicle', 'bike', 'animal', 'motion', 'deterrent', 'road', 'replay', 'proximity'];
    const K_ROAD = 6, K_REPLAY = 7, K_PROX = 8;

    function pickStep(pxPerMin) {
        for (let i = 0; i < STEPS.length; i++) if (STEPS[i] * pxPerMin >= LABEL_GAP_PX) return STEPS[i];
        return 360;
    }

    function rubberband(x, d, k) {
        if (k == null) k = RUBBER_K;
        if (!(d > 0)) return 0;
        return (x * d * k) / (d + k * Math.abs(x));
    }

    function project(v, rate) {
        if (rate == null) rate = DECEL;
        return (v / 1000) * rate / (1 - rate);
    }

    // Endpoint velocity (units/s) over the samples within windowMs of the newest one.
    // A finger that paused longer than windowMs before release has no velocity.
    function velocityFrom(ts, xs, n, head, cap, releaseT, windowMs) {
        if (n < 2) return 0;
        const last = (head - 1 + cap) % cap;
        const tLast = ts[last];
        if (releaseT - tLast > windowMs) return 0;
        let first = last;
        for (let i = 1; i < n; i++) {
            const j = (last - i + cap) % cap;
            if (tLast - ts[j] > windowMs) {
                if (first === last && tLast - ts[j] <= 2 * windowMs) first = j;
                break;
            }
            first = j;
        }
        const dt = tLast - ts[first];
        if (!(dt >= 4)) return 0;
        return (xs[last] - xs[first]) / dt * 1000;
    }

    // Exact damped-spring step (x in any unit, v in unit/s, dt in s); zeta >= 1 is critical.
    function springStep(st, dt) {
        const w = st.omega, z = st.zeta, e0 = st.x - st.target, v0 = st.v;
        if (z >= 1) {
            const ex = Math.exp(-w * dt), b = v0 + w * e0;
            st.x = st.target + (e0 + b * dt) * ex;
            st.v = (v0 - w * b * dt) * ex;
        } else {
            const wd = w * Math.sqrt(1 - z * z), ex = Math.exp(-z * w * dt);
            const cos = Math.cos(wd * dt), sin = Math.sin(wd * dt), b = (v0 + z * w * e0) / wd;
            st.x = st.target + ex * (e0 * cos + b * sin);
            st.v = ex * (v0 * cos - (e0 * wd + z * w * b) * sin);
        }
    }

    function lowerBound(arr, n, x) {
        let lo = 0, hi = n;
        while (lo < hi) { const m = (lo + hi) >> 1; if (arr[m] < x) lo = m + 1; else hi = m; }
        return lo;
    }

    function nearestIndex(arr, n, x) {
        if (n <= 0) return -1;
        const i = lowerBound(arr, n, x);
        if (i >= n) return n - 1;
        if (i > 0 && x - arr[i - 1] <= arr[i] - x) return i - 1;
        return i;
    }

    // First pair index (pairs packed s,e,s,e; sorted, disjoint) whose end is after x.
    function firstPairEndingAfter(p, nPairs, x) {
        let lo = 0, hi = nPairs;
        while (lo < hi) { const m = (lo + hi) >> 1; if (p[2 * m + 1] <= x) lo = m + 1; else hi = m; }
        return lo;
    }

    // Release plan in center-time units: c (ms), v (ms/s), ppm (px/ms).
    // Lands on the projection (or a snap point within SNAP_PX of it); stiffness gives zero
    // initial acceleration so the motion continues at the finger's speed without launching.
    function planFling(c, v, ppm, lo, hi, snapT, snapN, out) {
        let target = c + project(v * ppm) / ppm;
        out.snap = -1;
        out.zeta = 1;
        const i = nearestIndex(snapT, snapN, target);
        if (i >= 0 && Math.abs(snapT[i] - target) * ppm <= SNAP_PX) { target = snapT[i]; out.snap = i; }
        const inside = c >= lo && c <= hi;
        if (target < lo) { target = lo; out.snap = -1; if (inside) out.zeta = 0.8; }
        else if (target > hi) { target = hi; out.snap = -1; if (inside) out.zeta = 0.8; }
        const d = target - c;
        let w = OMEGA_STD;
        if (d !== 0 && d * v > 0) w = Math.min(OMEGA_STD, Math.max(OMEGA_SLOW, 2 * out.zeta * Math.abs(v / d)));
        out.target = target;
        out.omega = w;
        return out;
    }

    // Groups sorted times whose distance to the running centroid is < thrMs.
    function clusterMarks(times, alerts, n, thrMs, outT, outN, outFirst, outAlert) {
        let k = -1;
        for (let i = 0; i < n; i++) {
            const t = times[i];
            if (k >= 0 && t - outT[k] < thrMs) {
                const cnt = outN[k] + 1;
                outT[k] += (t - outT[k]) / cnt;
                outN[k] = cnt;
                if (alerts[i]) outAlert[k] = 1;
            } else {
                k++;
                outT[k] = t; outN[k] = 1; outFirst[k] = i; outAlert[k] = alerts[i] ? 1 : 0;
            }
        }
        return k + 1;
    }

    function mergeIntervals(list, tol) {
        list = list.filter(function (p) { return p[1] > p[0]; });
        list.sort(function (a, b) { return a[0] - b[0]; });
        const out = [];
        for (let i = 0; i < list.length; i++) {
            const s = list[i][0], e = list[i][1];
            const last = out[out.length - 1];
            if (last && s <= last[1] + tol) { if (e > last[1]) last[1] = e; }
            else out.push([s, e]);
        }
        return out;
    }

    function subtractIntervals(a, b) {
        const out = [];
        let j = 0;
        for (let i = 0; i < a.length; i++) {
            let s = a[i][0];
            const e = a[i][1];
            while (j < b.length && b[j][1] <= s) j++;
            for (let k = j; k < b.length && b[k][0] < e; k++) {
                if (b[k][0] > s) out.push([s, b[k][0]]);
                if (b[k][1] > s) s = b[k][1];
                if (s >= e) break;
            }
            if (s < e) out.push([s, e]);
        }
        return out;
    }

    const REAPED_KEY = { user: 'deleted', retention: 'overwritten', free_space: 'overwritten', relief: 'overwritten', removed: 'removed' };
    const REASON_CODES = ['user_off', 'acc_on', 'owner_returned', 'schedule_end', 'safe_zone', 'lease_revoked',
        'pipeline_down', 'soc_cutoff', 'lv_low', 'daemon_stop', 'storage_unavailable', 'storage_full'];
    const SENTRY_CODES = ['surveillance_off', 'vehicle_on_only', 'suppressed_safe_zone', 'suppressed_schedule',
        'pipeline_down', 'lock_wait'];
    // An unlisted reason falls back by journal kind, as on the head unit.
    const KIND_REASON = { daemon: 'daemon_stop', storage: 'storage_unavailable', surveillance: 'user_off' };
    // A tier explains a gap only when its entries cover at least this share of it (head unit rule).
    const MIN_SHARE = 0.5;

    function num(v, dflt) { return typeof v === 'number' && isFinite(v) ? v : dflt; }

    // Gap explanation key per gap: reaped > journal states > session sentry != armed > trip > null.
    function gapReasons(gaps, d, now) {
        const n = gaps.length;
        const res = new Array(n).fill(null);
        if (!n) return res;
        const ends = new Float64Array(n);
        for (let g = 0; g < n; g++) ends[g] = gaps[g][1];
        const cats = [d.reaped, d.states, d.sessions, d.trips];
        const bestOv = [new Float64Array(n), new Float64Array(n), new Float64Array(n), new Float64Array(n)];
        const sumOv = [new Float64Array(n), new Float64Array(n), new Float64Array(n), new Float64Array(n)];
        const bestIt = [new Array(n), new Array(n), new Array(n), new Array(n)];
        for (let ci = 0; ci < 4; ci++) {
            const list = Array.isArray(cats[ci]) ? cats[ci] : [];
            for (let i = 0; i < list.length; i++) {
                const it = list[i];
                if (!it) continue;
                if (ci === 2 && (it.sentry === 'armed' || SENTRY_CODES.indexOf(it.sentry) < 0)) continue;
                const s = num(it.start, NaN), e = num(it.end, now);
                if (!(e > s)) continue;
                for (let g = lowerBound(ends, n, s + 1e-9); g < n && gaps[g][0] < e; g++) {
                    const ov = Math.min(e, gaps[g][1]) - Math.max(s, gaps[g][0]);
                    if (ov > 0) sumOv[ci][g] += ov;
                    if (ov > bestOv[ci][g]) { bestOv[ci][g] = ov; bestIt[ci][g] = it; }
                }
            }
        }
        for (let g = 0; g < n; g++) {
            const need = (gaps[g][1] - gaps[g][0]) * MIN_SHARE;
            const wins = function (ci) { return bestOv[ci][g] > 0 && sumOv[ci][g] >= need; };
            if (wins(0)) res[g] = 'tlview.reason.' + (REAPED_KEY[bestIt[0][g].reason] || 'removed');
            else if (wins(1)) {
                const r = bestIt[1][g].reason;
                res[g] = 'tlview.reason.' + (REASON_CODES.indexOf(r) >= 0 ? r : KIND_REASON[bestIt[1][g].kind] || 'unknown');
            } else if (wins(2)) res[g] = 'tlview.sentry.' + bestIt[2][g].sentry;
            else if (wins(3)) res[g] = 'tlview.reason.driving';
        }
        return res;
    }

    function parseColor(str) {
        if (typeof str !== 'string') return null;
        const s = str.trim();
        let m = /^#([0-9a-f]{3,8})$/i.exec(s);
        if (m) {
            let h = m[1];
            if (h.length === 3 || h.length === 4) h = h.replace(/./g, '$&$&');
            if (h.length !== 6 && h.length !== 8) return null;
            const v = parseInt(h, 16);
            return h.length === 6
                ? [(v >> 16) & 255, (v >> 8) & 255, v & 255, 1]
                : [(v >>> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, (v & 255) / 255];
        }
        m = /^rgba?\(([^)]+)\)$/i.exec(s);
        if (!m) return null;
        const p = m[1].split(/[\s,/]+/).filter(Boolean).map(function (x) {
            return x.slice(-1) === '%' ? parseFloat(x) * 2.55 : parseFloat(x);
        });
        if (p.length < 3 || p.some(function (x) { return !isFinite(x); })) return null;
        let a = p.length > 3 ? p[3] : 1;
        if (/%\s*\)?$/.test(m[1].split(/[\s,/]+/).filter(Boolean)[3] || '')) a = p[3] / 255;
        return [p[0], p[1], p[2], a];
    }

    function rgba(c, a) {
        return 'rgba(' + Math.round(c[0]) + ',' + Math.round(c[1]) + ',' + Math.round(c[2]) + ',' + (+(a * c[3]).toFixed(3)) + ')';
    }

    function luminance(c) {
        const f = function (v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
        return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
    }

    const EN = {
        'tlview.reason.user_off': 'sentry off', 'tlview.reason.acc_on': 'car on',
        'tlview.reason.owner_returned': 'owner returned', 'tlview.reason.schedule_end': 'outside sentry schedule',
        'tlview.reason.safe_zone': 'safe zone', 'tlview.reason.lease_revoked': 'sentry restarted',
        'tlview.reason.pipeline_down': 'cameras unavailable', 'tlview.reason.soc_cutoff': 'battery saver',
        'tlview.reason.lv_low': '12 V battery low', 'tlview.reason.daemon_stop': 'recorder not running',
        'tlview.reason.storage_unavailable': 'storage unavailable', 'tlview.reason.storage_full': 'storage full',
        'tlview.reason.driving': 'driving, not recorded', 'tlview.reason.deleted': 'deleted',
        'tlview.reason.overwritten': 'overwritten by loop recording', 'tlview.reason.removed': 'removed',
        'tlview.reason.unknown': 'no footage',
        'tlview.sentry.surveillance_off': 'sentry off', 'tlview.sentry.vehicle_on_only': 'sentry set to car-on only',
        'tlview.sentry.suppressed_safe_zone': 'safe zone', 'tlview.sentry.suppressed_schedule': 'outside sentry schedule',
        'tlview.sentry.pipeline_down': 'cameras unavailable', 'tlview.sentry.lock_wait': 'waiting for the car to lock',
        'tlview.gapNoFootage': 'No footage', 'tlview.gapFmt': '{duration} · {reason}',
        'tlview.durHm': '{h} h {m} min', 'tlview.durM': '{m} min', 'tlview.durS': '{s} s',
        'tlview.kindPerson': 'Person', 'tlview.kindVehicle': 'Vehicle', 'tlview.kindBike': 'Bike',
        'tlview.kindAnimal': 'Animal', 'tlview.kindMotion': 'Motion', 'tlview.kindDeterrent': 'Deterrent',
        'tlview.kindEvent': 'Event', 'tlview.closePass': 'Close pass', 'tlview.savedReplay': 'Saved replay',
        'tlview.drive': 'Drive', 'tlview.roadPothole': 'Pothole', 'tlview.roadBreaker': 'Speed breaker',
        'tlview.roadRough': 'Rough road', 'tlview.live': 'Live', 'tlview.tabTimeline': 'Timeline',
        'tlview.ariaStrip': 'Timeline at {time}', 'tlview.ariaStripNearest': 'Timeline at {time}, nearest moment {moment}'
    };
    const LABEL_KEYS = ['tlview.kindPerson', 'tlview.kindVehicle', 'tlview.kindBike', 'tlview.kindAnimal',
        'tlview.kindMotion', 'tlview.kindDeterrent', 'tlview.kindEvent', 'tlview.savedReplay', 'tlview.closePass'];
    const ROAD_KEY = { pothole: 'tlview.roadPothole', breaker: 'tlview.roadBreaker', rough: 'tlview.roadRough' };

    function interpolate(str, vars) {
        if (!vars) return str;
        return String(str).replace(/\{(\w+)\}/g, function (m, k) { return vars[k] != null ? vars[k] : m; });
    }

    // Icons on a 24-unit grid: filled paths, optional cut-outs, round-capped strokes.
    function makeIcons() {
        if (typeof Path2D !== 'function') return null;
        const P = function (d) { return new Path2D(d); };
        const circ = function (p, cx, cy, r) { p.moveTo(cx + r, cy); p.arc(cx, cy, r, 0, 2 * Math.PI); return p; };
        const person = circ(P('M4.8 21c0-4.4 3.2-7.6 7.2-7.6s7.2 3.2 7.2 7.6z'), 12, 6.5, 3.6);
        const vehicle = P('M3.5 16.5v-3.6l2-5.2A2.2 2.2 0 0 1 7.6 6.3h8.8a2.2 2.2 0 0 1 2.1 1.4l2 5.2v3.6a1 1 0 0 1-1 1h-1.2a2.3 2.3 0 0 1-4.6 0H10.3a2.3 2.3 0 0 1-4.6 0H4.5a1 1 0 0 1-1-1zM6.6 11.6h10.8l-1.3-3.3H7.9z');
        const bike = circ(circ(P('M5.8 16l4.2-7.5h5.2l3 7.5M10 8.5l3.4 7.5M13.6 5.5h2.6'), 5.8, 16, 3.6), 18.2, 16, 3.6);
        const animal = P('M12.3 11.2c3.2 0 5.9 3.8 5.9 6.3 0 1.9-1.7 2.8-3.2 2.3-1.1-.3-1.8-.7-2.7-.7s-1.6.4-2.7.7c-1.5.5-3.2-.4-3.2-2.3 0-2.5 2.7-6.3 5.9-6.3z');
        circ(animal, 6, 10, 2.3); circ(animal, 10, 6, 2.3); circ(animal, 14.6, 6, 2.3); circ(animal, 18.6, 10, 2.3);
        return [
            { f: person },
            { f: vehicle },
            { s: bike, w: 2.4 },
            { f: animal },
            { s: P('M2.5 12h3.8l2.4-6 4.6 12 2.4-6h5.8'), w: 2.6 },
            { f: P('M7 17.5v-5a5 5 0 0 1 10 0v5zM5 18.8h14v2.4H5z'), s: P('M12 2.4v2.6M4.3 5.8l1.8 1.8M19.7 5.8l-1.8 1.8'), w: 2.2 },
            { f: P('M12 3l10 18H2z'), cut: P('M11 9.5h2v5.5h-2zM11 16.6h2v2h-2z') },
            { f: P('M12 2.5l7 9.5-7 9.5-7-9.5z') },
            { s: P('M3.5 17a12 12 0 0 1 17 0M6.8 13.6a7.4 7.4 0 0 1 10.4 0M10 10.3a2.9 2.9 0 0 1 4 0'), w: 2.4 }
        ];
    }

    function packPairs(list) {
        const out = new Float64Array(list.length * 2);
        for (let i = 0; i < list.length; i++) { out[2 * i] = list[i][0]; out[2 * i + 1] = list[i][1]; }
        return out;
    }

    // Every available segment belongs to exactly one merged footage run. Carry
    // its driving flag through the merge instead of rescanning all drive clips
    // for each run (quadratic on fragmented recording days).
    function mergeFootageRuns(available) {
        available.sort(function (a, b) { return a[0] - b[0]; });
        const runs = [], drive = [];
        for (let i = 0; i < available.length; i++) {
            const [s, e, driving] = available[i];
            if (!(e > s)) continue;
            const last = runs[runs.length - 1];
            if (last && s <= last[1] + CONTIG_MS) {
                if (e > last[1]) last[1] = e;
                if (driving) drive[drive.length - 1] = 1;
            } else {
                runs.push([s, e]);
                drive.push(driving ? 1 : 0);
            }
        }
        return { runs: runs, runDrive: new Uint8Array(drive) };
    }

    function segEnd(sg) {
        const e = num(sg.end, NaN);
        return e > sg.start ? e : sg.start + 1000;
    }

    function arr(v) { return Array.isArray(v) ? v : []; }

    // Normalise footage, seams and marker geometry independently of the clock.
    function buildStaticModel(d) {
        const segs = arr(d.segments).filter(function (sg) { return sg && num(sg.start, NaN) === sg.start; });
        const av = [], un = [];
        for (let i = 0; i < segs.length; i++) {
            const sg = segs[i], end = segEnd(sg);
            if (sg.available === false) un.push([sg.start, end]);
            else av.push([sg.start, end, sg.type === 'normal' || sg.type === 'oemDashcam']);
        }
        const { runs, runDrive } = mergeFootageRuns(av);
        const unav = subtractIntervals(mergeIntervals(un, CONTIG_MS), runs);
        const seamList = [];
        ['normal', 'oemDashcam', 'sentry'].forEach(function (type) {
            const list = segs.filter(function (sg) { return sg.type === type && sg.available !== false; })
                .sort(function (a, b) { return a.start - b.start; });
            for (let i = 1; i < list.length; i++) {
                const dt = list[i].start - segEnd(list[i - 1]);
                if (dt >= -CONTIG_MS && dt <= CONTIG_MS) seamList.push(list[i].start);
            }
        });
        seamList.sort(function (a, b) { return a - b; });
        const seams = new Float64Array(seamList.length);
        let nSeams = 0;
        for (let i = 0; i < seamList.length; i++) if (!nSeams || seamList[i] - seams[nSeams - 1] > 500) seams[nSeams++] = seamList[i];

        const mk = [];
        const recWithMarks = new Set();
        arr(d.marks).forEach(function (m) {
            if (!m || num(m.t0, NaN) !== m.t0) return;
            let k = KIND_NAMES.indexOf(m.kind);
            if (k < 0 || k > 5) k = 4;
            mk.push([m.t0, k, m.alert ? 1 : 0, m.rec == null ? null : m.rec, LABEL_KEYS[k]]);
            if (m.rec != null) recWithMarks.add(m.rec);
        });
        arr(d.road).forEach(function (r) {
            if (r && num(r.t, NaN) === r.t) mk.push([r.t, K_ROAD, 0, null, ROAD_KEY[r.kind] || 'tlview.kindEvent']);
        });
        segs.forEach(function (sg) {
            if (sg.type === 'replay') {
                mk.push([num(sg.end, NaN) > sg.start ? sg.end : sg.start, K_REPLAY, 0, sg.id, LABEL_KEYS[K_REPLAY]]);
            } else if (sg.type === 'proximity') {
                mk.push([sg.start, K_PROX, 0, sg.id, LABEL_KEYS[K_PROX]]);
            } else if (sg.type === 'sentry' && !recWithMarks.has(sg.id)) {
                const cls = arr(sg.classes);
                let k = 4;
                for (let i = 0; i < 4; i++) if (cls.indexOf(KIND_NAMES[i]) >= 0 || (i === 1 && cls.indexOf('car') >= 0)) { k = i; break; }
                const sev = sg.peakSeverity === 'ALERT' || sg.peakSeverity === 'CRITICAL';
                mk.push([sg.start, k, sev ? 1 : 0, sg.id, LABEL_KEYS[k]]);
            }
        });
        mk.sort(function (a, b) { return a[0] - b[0]; });
        const nM = mk.length;
        const mT = new Float64Array(nM), mK = new Uint8Array(nM), mA = new Uint8Array(nM);
        const mRec = new Array(nM), mLab = new Array(nM);
        for (let i = 0; i < nM; i++) { mT[i] = mk[i][0]; mK[i] = mk[i][1]; mA[i] = mk[i][2]; mRec[i] = mk[i][3]; mLab[i] = mk[i][4]; }

        const moments = arr(d.moments).filter(function (m) { return m && num(m.t, NaN) === m.t; })
            .slice().sort(function (a, b) { return a.t - b.t; });
        const momT = new Float64Array(moments.length);
        for (let i = 0; i < moments.length; i++) momT[i] = moments[i].t;

        return {
            source: d.reuseStatic === true ? [d.segments, d.marks, d.road, d.moments] : null,
            geometry: {
                runs: packPairs(runs), nRuns: runs.length, runDrive: runDrive,
                unav: packPairs(unav), nUnav: unav.length,
                seams: seams, nSeams: nSeams,
                mT: mT, mK: mK, mA: mA, mRec: mRec, mLab: mLab, nM: nM,
                moments: moments, momT: momT, nMom: moments.length
            }
        };
    }

    // Opt-in callers promise to replace the four source arrays when their contents change.
    // Other callers (including the gap card) retain the original rebuild-on-every-call behaviour.
    function buildModel(d, previousStatic) {
        d = d || {};
        const dataNow = num(d.now, Date.now());
        const source = previousStatic && previousStatic.source;
        const fixed = d.reuseStatic === true && source && source[0] === d.segments &&
            source[1] === d.marks && source[2] === d.road && source[3] === d.moments
            ? previousStatic : buildStaticModel(d);
        const lo = num(d.minMs, -Infinity);
        const maxMs = num(d.maxMs, Infinity);
        const hiData = Math.max(lo, Math.min(maxMs, dataNow));
        const liveSince = arr(d.live).map(function (l) { return l ? num(l.since, NaN) : NaN; })
            .filter(function (v) { return v === v && v <= dataNow; }).sort(function (a, b) { return a - b; });
        const live = mergeIntervals(liveSince.map(function (s) { return [s, dataNow]; }), 0);
        const reaped = mergeIntervals(arr(d.reaped).filter(Boolean).map(function (r) {
            return [num(r.start, NaN), num(r.end, NaN)];
        }), 0);
        let armedOpen = false;
        const armedRaw = [];
        arr(d.sessions).forEach(function (s) {
            if (!s || s.sentry !== 'armed' || !(num(s.start, NaN) <= dataNow)) return;
            const e = num(s.end, NaN);
            if (!(e === e)) armedOpen = true;
            armedRaw.push([s.start, e === e ? e : dataNow]);
        });
        const armed = subtractIntervals(mergeIntervals(armedRaw, 0), reaped);
        if (armedOpen && !(armed.length && armed[armed.length - 1][1] >= dataNow)) armedOpen = false;
        // Rebuild only clock-dependent coverage, using the existing packed static intervals.
        const coverInput = [];
        for (const pairs of [fixed.geometry.runs, fixed.geometry.unav]) {
            for (let i = 0; i < pairs.length; i += 2) coverInput.push([pairs[i], pairs[i + 1]]);
        }
        const cover = mergeIntervals(coverInput.concat(live, armed), CONTIG_MS);
        let gaps = [];
        const gLo = isFinite(lo) ? lo : (cover.length ? cover[0][0] : hiData);
        if (hiData > gLo) {
            gaps = subtractIntervals([[gLo, hiData]], cover).filter(function (g) { return g[1] - g[0] >= CONTIG_MS; });
        }
        const gapOpen = gaps.length > 0 && gaps[gaps.length - 1][1] >= dataNow && hiData === dataNow;
        const gapKey = gapReasons(gaps, d, dataNow);

        return Object.assign({
            staticData: fixed,
            lo: lo, maxMs: maxMs, dataNow: dataNow, hiData: hiData, liveDay: maxMs >= dataNow - 1000,
            armed: packPairs(armed), nArmed: armed.length, armedOpen: armedOpen,
            gaps: packPairs(gaps), nGaps: gaps.length, gapKey: gapKey, gapOpen: gapOpen,
            live: packPairs(live), nLive: live.length
        }, fixed.geometry);
    }

    function create(hostEl, opts) {
        if (!hostEl || typeof hostEl.appendChild !== 'function') throw new TypeError('tlstrip: host element required');
        opts = opts || {};
        const vertical = opts.orientation === 'vertical';
        const SIG = vertical ? -1 : 1;
        const reduced = opts.reducedMotion != null ? !!opts.reducedMotion
            : !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
        const handlers = Object.create(null);
        let destroyed = false;

        const strCache = new Map();
        function tr(key, vars) {
            let s = null;
            if (typeof opts.t === 'function') { try { s = opts.t(key, vars); } catch (e) { s = null; } }
            if (typeof s !== 'string' || !s || s === key) s = interpolate(EN[key] != null ? EN[key] : key, vars);
            return s;
        }
        function label(key) {
            let s = strCache.get(key);
            if (s === undefined) { s = tr(key); strCache.set(key, s); }
            return s;
        }

        // hourCycle h23 instead of hour12:false: some engines render midnight as "24:xx" with hour12.
        let tz = typeof opts.tz === 'string' && opts.tz ? opts.tz : undefined;
        let fmtHM = null, fmtHMS = null, fmtParts = null;
        const hmCache = new Map(), offCache = new Map();
        let hmsKey = NaN, hmsStr = '';
        (function makeFormatters() {
            const base = { hourCycle: 'h23', hour: '2-digit', minute: '2-digit' };
            try { fmtHM = new Intl.DateTimeFormat(undefined, Object.assign({ timeZone: tz }, base)); }
            catch (e) { tz = undefined; fmtHM = new Intl.DateTimeFormat(undefined, base); }
            fmtHMS = new Intl.DateTimeFormat(undefined, Object.assign({ timeZone: tz, second: '2-digit' }, base));
            fmtParts = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric',
                day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' });
        })();
        function tzOffset(ms) {
            const key = Math.floor(ms / 900000);
            let off = offCache.get(key);
            if (off === undefined) {
                off = 0;
                try {
                    const p = fmtParts.formatToParts(key * 900000);
                    let y = 1970, mo = 1, d = 1, h = 0, mi = 0, s = 0;
                    for (let i = 0; i < p.length; i++) {
                        const v = +p[i].value;
                        switch (p[i].type) {
                            case 'year': y = v; break; case 'month': mo = v; break; case 'day': d = v; break;
                            case 'hour': h = v % 24; break; case 'minute': mi = v; break; case 'second': s = v; break;
                        }
                    }
                    off = Date.UTC(y, mo - 1, d, h, mi, s) - key * 900000;
                } catch (e) { off = 0; }
                if (offCache.size > 2048) offCache.clear();
                offCache.set(key, off);
            }
            return off;
        }
        // First 15-min bucket start in (a, b] whose offset differs from a's (a DST change), else NaN.
        function tzSplit(a, b) {
            const oa = tzOffset(a);
            let lo = Math.floor(a / 900000), hi = Math.floor(b / 900000);
            if (hi <= lo || tzOffset(hi * 900000) === oa) return NaN;
            while (hi - lo > 1) { const m = Math.floor((lo + hi) / 2); if (tzOffset(m * 900000) === oa) lo = m; else hi = m; }
            return hi * 900000;
        }
        function hm(ms) {
            const key = Math.floor(ms / MIN);
            let s = hmCache.get(key);
            if (s === undefined) {
                s = fmtHM.format(key * MIN);
                if (hmCache.size > 4096) hmCache.clear();
                hmCache.set(key, s);
            }
            return s;
        }
        function hms(ms) {
            const key = Math.floor(ms / 1000);
            if (key !== hmsKey) { hmsKey = key; hmsStr = fmtHMS.format(key * 1000); }
            return hmsStr;
        }
        // Same rounding as the gap card and the head unit: whole seconds from 1 s, minutes by 30 s.
        function fmtDur(ms) {
            ms = Math.max(0, ms);
            const secs = Math.floor((ms + 500) / 1000);
            if (secs < 60) return tr('tlview.durS', { s: Math.max(1, secs) });
            const mins = Math.floor((ms + 30000) / 60000);
            if (mins < 60) return tr('tlview.durM', { m: mins });
            return tr('tlview.durHm', { h: Math.floor(mins / 60), m: mins % 60 });
        }

        // A horizontal strip leaves vertical pans to the page (it scrolls when the layout can't fit); a vertical one scrubs with them.
        const touch = vertical ? 'none' : 'pan-y';
        const canvas = document.createElement('canvas');
        canvas.className = 'tlv-strip-canvas';
        canvas.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;display:block;touch-action:' + touch + ';' +
            'user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent;outline-offset:-2px';
        canvas.tabIndex = 0;
        canvas.setAttribute('role', 'slider');
        canvas.setAttribute('aria-orientation', vertical ? 'vertical' : 'horizontal');
        canvas.setAttribute('aria-label', tr('tlview.tabTimeline'));
        const prevTouch = hostEl.style.touchAction, prevPos = hostEl.style.position;
        let posPatched = false;
        try {
            if (getComputedStyle(hostEl).position === 'static') { hostEl.style.position = 'relative'; posPatched = true; }
        } catch (e) { /* detached host */ }
        hostEl.style.touchAction = touch;
        hostEl.insertBefore(canvas, hostEl.firstChild);
        const ctx = canvas.getContext('2d');
        const icons = makeIcons();

        let W = 0, H = 0, dpr = 1, axisLen = 1, P0 = 0;
        // Horizontal rows (designed for 88 px; compressed proportionally in shorter hosts).
        let L_PI = 4, L_PD = 1, L_LB = 20, L_MN = 24, L_MY = 40, L_TY = 54, L_TH = 30, L_H0 = 26, L_H1 = 88;
        let span = vertical ? 2 * 3600000 : 6 * 3600000;
        let ppm = 1 / span;
        let c = Date.now(), rc = c;

        // Dark palette is fixed; the light theme derives from design-token CSS variables on the host.
        const C = {};
        const FONTS = ['', '', '', '', '', '', '', ''];
        const F_RULER = 0, F_PILL = 1, F_PILL_DRAG = 2, F_GAP = 3, F_DRIVE = 4, F_COUNT = 5, F_VPILL = 6, F_VPILL_DRAG = 7;
        const twCache = [new Map(), new Map(), new Map(), new Map(), new Map(), new Map(), new Map(), new Map()];
        let hatchPat = null, livePat = null, fadeL = null, fadeR = null, patDpr = 0;
        function textW(fi, s) {
            const m = twCache[fi];
            let w = m.get(s);
            if (w === undefined) {
                ctx.font = FONTS[fi];
                w = ctx.measureText(s).width || 0;
                if (m.size > 512) m.clear();
                m.set(s, w);
            }
            return w;
        }
        function nearestBg(el) {
            for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
                const col = parseColor(getComputedStyle(n).backgroundColor);
                if (col && col[3] > 0.5) return col;
            }
            return null;
        }
        function makeStripes(base, stripe) {
            const tile = document.createElement('canvas');
            const n = Math.max(2, Math.round(7 * dpr));
            tile.width = n; tile.height = n;
            const t = tile.getContext('2d');
            if (!t) return base;
            t.fillStyle = base; t.fillRect(0, 0, n, n);
            t.fillStyle = stripe; t.fillRect(0, 0, 2.4 * n / 7, n);
            const pat = ctx.createPattern(tile, 'repeat');
            if (!pat) return base;
            if (typeof pat.setTransform === 'function' && typeof DOMMatrix === 'function') {
                try { pat.setTransform(new DOMMatrix().rotateSelf(45).scaleSelf(7 / n)); } catch (e) { /* vertical stripes */ }
            }
            return pat;
        }
        function refreshPalette() {
            let cs = null;
            try { cs = getComputedStyle(hostEl); } catch (e) { cs = null; }
            const v = function (name, dflt) { return (cs && parseColor(cs.getPropertyValue(name))) || parseColor(dflt); };
            const bg = (cs && parseColor(cs.getPropertyValue('--tlv-strip-bg'))) || nearestBg(hostEl) || v('--surface-container-low', '#151A18');
            const dark = luminance(bg) < 0.35;
            const onSurf = v('--on-surface', '#DEE4E0'), surf = v('--surface', '#0E1311');
            C.bg = rgba(bg, 1); C.bgClear = rgba(bg, 0);
            C.primary = rgba(v('--primary', '#5DDBB6'), 1); C.onPrimary = rgba(v('--on-primary', '#003828'), 1);
            if (dark) {
                Object.assign(C, {
                    footage: 'rgba(226,232,229,0.92)', armed: 'rgba(222,228,224,0.085)', hatchBase: '#111614',
                    hatchStripe: 'rgba(255,255,255,0.075)', ruler: '#7F8B87', minor: '#3A4440', grid: 'rgba(255,255,255,0.05)',
                    gapText: '#A5B0AC', driveText: '#1C2321', seam: 'rgba(14,19,17,0.35)', markFill: 'rgba(222,228,224,0.10)',
                    markIcon: '#E6ECE9', alert: '#FFB870', alertFill: 'rgba(255,184,112,0.16)', alertIcon: '#FFC98F',
                    live: '#FF5449', liveBase: 'rgba(255,84,73,0.20)', liveStripe: 'rgba(255,84,73,0.45)',
                    head: '#FFFFFF', headHalo: 'rgba(0,0,0,0.5)', unav: 'rgba(226,232,229,0.55)', dot: 'rgba(14,19,17,0.75)'
                });
            } else {
                const warn = v('--status-warning', '#A6601C'), danger = v('--status-danger', '#BA1A1A');
                Object.assign(C, {
                    footage: rgba(onSurf, 0.86), armed: rgba(onSurf, 0.07), hatchBase: rgba(onSurf, 0.04),
                    hatchStripe: rgba(onSurf, 0.11), ruler: rgba(v('--outline', '#6F7975'), 1),
                    minor: rgba(v('--outline-variant', '#BFC9C3'), 1), grid: rgba(onSurf, 0.06),
                    gapText: rgba(v('--on-surface-variant', '#3F4944'), 1), driveText: rgba(surf, 1), seam: rgba(surf, 0.45),
                    markFill: rgba(onSurf, 0.08), markIcon: rgba(onSurf, 0.85), alert: rgba(warn, 1),
                    alertFill: rgba(warn, 0.14), alertIcon: rgba(warn, 1), live: rgba(danger, 1),
                    liveBase: rgba(danger, 0.14), liveStripe: rgba(danger, 0.4), head: rgba(onSurf, 1),
                    headHalo: 'rgba(255,255,255,0.75)', unav: rgba(onSurf, 0.5), dot: rgba(surf, 0.85)
                });
            }
            const fam = (cs && cs.fontFamily) || 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
            FONTS[F_RULER] = '400 11px ' + fam; FONTS[F_PILL] = '700 12.5px ' + fam; FONTS[F_PILL_DRAG] = '700 14px ' + fam;
            FONTS[F_GAP] = '400 11.5px ' + fam; FONTS[F_DRIVE] = '600 11.5px ' + fam; FONTS[F_COUNT] = '700 11.5px ' + fam;
            FONTS[F_VPILL] = '700 10.5px ' + fam; FONTS[F_VPILL_DRAG] = '700 11.5px ' + fam;
            for (let i = 0; i < twCache.length; i++) twCache[i].clear();
            buildPatterns();
        }
        function buildPatterns() {
            if (!ctx) return;
            patDpr = dpr;
            hatchPat = makeStripes(C.hatchBase, C.hatchStripe);
            livePat = makeStripes(C.liveBase, C.liveStripe);
            fadeL = ctx.createLinearGradient(0, 0, 40, 0);
            fadeL.addColorStop(0, C.bg); fadeL.addColorStop(1, C.bgClear);
            fadeR = ctx.createLinearGradient(0, 0, -40, 0);
            fadeR.addColorStop(0, C.bg); fadeR.addColorStop(1, C.bgClear);
        }

        let model = buildModel(null);
        let dataPerf = performance.now();
        let fKinds = null, fAlerts = false;
        let gapFull = [], gapShort = [];
        let vT = new Float64Array(0), vA = new Uint8Array(0), vK = new Uint8Array(0), vI = new Int32Array(0), nV = 0;
        let clT, clN, clF, clA, clB, pT, pN, pF, pA, pB;
        let nCl = 0, nPrev = 0, clPpm = 0, clFresh = true, clAnimUntil = 0;
        let snT = new Float64Array(0), snS = new Int32Array(0), nSn = 0;

        function allocClusters(n) {
            n = Math.max(1, n);
            clT = new Float64Array(n); clN = new Int32Array(n); clF = new Int32Array(n); clA = new Uint8Array(n); clB = new Float64Array(n);
            pT = new Float64Array(n); pN = new Int32Array(n); pF = new Int32Array(n); pA = new Uint8Array(n); pB = new Float64Array(n);
            nCl = 0; nPrev = 0; clPpm = 0;
        }
        allocClusters(1);

        function applyFilter() {
            const n = model.nM;
            vT = new Float64Array(n); vA = new Uint8Array(n); vK = new Uint8Array(n); vI = new Int32Array(n); nV = 0;
            for (let i = 0; i < n; i++) {
                const k = model.mK[i];
                if (fKinds && fKinds.indexOf(KIND_NAMES[k]) < 0) continue;
                if (fAlerts && !model.mA[i]) continue;
                vT[nV] = model.mT[i]; vA[nV] = model.mA[i]; vK[nV] = k; vI[nV] = i; nV++;
            }
            allocClusters(nV);
            clFresh = true;
            const nm = model.nMom;
            nSn = nV + nm;
            snT = new Float64Array(nSn); snS = new Int32Array(nSn);
            let a = 0, b = 0;
            for (let k = 0; k < nSn; k++) {
                if (b >= nm || (a < nV && vT[a] <= model.momT[b])) { snT[k] = vT[a]; snS[k] = a; a++; }
                else { snT[k] = model.momT[b]; snS[k] = -(b + 1); b++; }
            }
        }

        // Re-clusters only when the zoom changed; identical clusters keep their birth time.
        function recluster(now) {
            if (clPpm === ppm) return;
            let t;
            t = pT; pT = clT; clT = t; t = pN; pN = clN; clN = t; t = pF; pF = clF; clF = t;
            t = pA; pA = clA; clA = t; t = pB; pB = clB; clB = t;
            nPrev = nCl;
            nCl = clusterMarks(vT, vA, nV, CLUSTER_PX / ppm, clT, clN, clF, clA);
            let j = 0;
            for (let i = 0; i < nCl; i++) {
                let born = -1e12;
                if (!clFresh) {
                    born = now;
                    while (j < nPrev && pF[j] < clF[i]) j++;
                    if (j < nPrev && pF[j] === clF[i] && pN[j] === clN[i]) born = pB[j];
                    if (born + CLUSTER_ANIM_MS > clAnimUntil) clAnimUntil = born + CLUSTER_ANIM_MS;
                }
                clB[i] = born;
            }
            clPpm = ppm;
            clFresh = false;
        }
        function snapLabel(k) {
            if (k < 0) {
                const m = model.moments[-k - 1];
                return m && m.label != null ? String(m.label) : null;
            }
            return label(model.mLab[vI[k]]);
        }

        const HATCH_PERIOD = 7 * Math.SQRT2;
        const DASH = [4, 3], NO_DASH = [];
        const RAIL_X = 56, RAIL_W = 14;
        const countStrs = [];
        let curKey = -1, curLabel = null;
        let pillGrow = 0, pillW = vertical ? 56 : 84, pillTarget = pillW, pillOverride = null;
        let flingLabel = null, flingT = NaN;
        let ptTime = '', ptLabel = null, ptStr = '';
        let fadeCv = null, fadeT0 = -1;
        let pressed = false, dragging = false, pinching = false, cAnim = 0, sAnim = false;

        function posOf(t) { return P0 + SIG * (t - c) * ppm; }
        function timeAt(pos) { return c + SIG * (pos - P0) / ppm; }
        function nowMs() { return model.dataNow + Math.max(0, Math.min(performance.now() - dataPerf, LIVE_EXTRAPOLATE_MS)); }
        // A window that reaches the data's now is live: its bound and red dot follow the running clock.
        function hiB() { return Math.max(model.lo, model.liveDay ? nowMs() : Math.min(model.maxMs, nowMs())); }
        function mod(a, m) { const r = a % m; return r < 0 ? r + m : r; }
        function countStr(n) { let s = countStrs[n]; if (s === undefined) { s = String(n); if (n < 10000) countStrs[n] = s; } return s; }
        function pillText(ts, lab) {
            if (ts !== ptTime || lab !== ptLabel) { ptTime = ts; ptLabel = lab; ptStr = lab ? ts + '  ·  ' + lab : ts; }
            return ptStr;
        }

        function rr(x, y, w, h, r) {
            if (r > w / 2) r = w / 2;
            if (r > h / 2) r = h / 2;
            if (!(r > 0)) r = 0;
            ctx.beginPath();
            ctx.moveTo(x + r, y);
            ctx.arcTo(x + w, y, x + w, y + h, r);
            ctx.arcTo(x + w, y + h, x, y + h, r);
            ctx.arcTo(x, y + h, x, y, r);
            ctx.arcTo(x, y, x + w, y, r);
            ctx.closePath();
        }

        function drawIcon(k, cx, cy, size, color) {
            if (!icons) { ctx.fillStyle = color; ctx.beginPath(); ctx.arc(cx, cy, size * 0.22, 0, 2 * Math.PI); ctx.fill(); return; }
            const ic = icons[k] || icons[4];
            ctx.save();
            ctx.translate(cx - size / 2, cy - size / 2);
            ctx.scale(size / 24, size / 24);
            if (ic.f) { ctx.fillStyle = color; ctx.fill(ic.f); }
            if (ic.cut) { ctx.fillStyle = C.bg; ctx.fill(ic.cut); }
            if (ic.s) { ctx.strokeStyle = color; ctx.lineWidth = ic.w; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.stroke(ic.s); }
            ctx.restore();
        }

        // Paints packed pairs visible in [t0, t1] along the axis; q0/qLen is the cross-axis band.
        function drawPairs(p, n, openEnd, t0, t1, stroke, sx, sy, minLen, radius, q0, qLen) {
            if (!n) return;
            let i = firstPairEndingAfter(p, n, t0);
            if (i >= n && openEnd > p[2 * n - 1]) i = n - 1;
            const lim = (vertical ? H : W) + 24;
            for (; i < n; i++) {
                const s = p[2 * i];
                if (s > t1) break;
                let e = p[2 * i + 1];
                if (i === n - 1 && openEnd > e) e = openEnd;
                if (e < t0) continue;
                let a = posOf(vertical ? e : s), b = posOf(vertical ? s : e);
                if (a < -24) a = -24;
                if (b > lim) b = lim;
                let len = b - a;
                if (len < minLen) { a -= (minLen - len) / 2; len = minLen; }
                if (!(len > 0)) continue;
                const rad = len > 12 ? radius : Math.min(radius, 1);
                if (vertical) rr(q0 - sx, a - sy, qLen, len, rad); else rr(a - sx, q0 - sy, len, qLen, rad);
                if (stroke) ctx.stroke(); else ctx.fill();
            }
        }

        function render(tNow) {
            if (!ctx || W < 1 || H < 1) return;
            if (patDpr !== dpr) buildPatterns();
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            ctx.globalAlpha = 1;
            ctx.clearRect(0, 0, W, H);
            recluster(tNow);
            const now = nowMs();
            if (vertical) drawVertical(tNow, now); else drawHorizontal(tNow, now);
            if (fadeT0 >= 0 && fadeCv) {
                const q = (tNow - fadeT0) / FADE_MS;
                if (q >= 1) fadeT0 = -1;
                else {
                    ctx.setTransform(1, 0, 0, 1, 0, 0);
                    ctx.globalAlpha = 1 - q;
                    ctx.drawImage(fadeCv, 0, 0);
                    ctx.globalAlpha = 1;
                }
            }
        }
        function fadeStart() {
            if (!ctx || canvas.width < 2) return;
            if (!fadeCv || fadeCv.width !== canvas.width || fadeCv.height !== canvas.height) {
                fadeCv = document.createElement('canvas');
                fadeCv.width = canvas.width; fadeCv.height = canvas.height;
            }
            const f = fadeCv.getContext('2d');
            if (!f) return;
            f.clearRect(0, 0, fadeCv.width, fadeCv.height);
            f.drawImage(canvas, 0, 0);
            fadeT0 = performance.now();
        }
        function interactingVisual() { return pressed || dragging || pinching || cAnim === 2 || cAnim === 3; }

        function drawHorizontal(tNow, now) {
            const TY = L_TY, TH = L_TH, MY = L_MY;
            const t0 = c - P0 / ppm, t1 = c + (W - P0) / ppm, pad = 40 / ppm;
            const step = pickStep(ppm * MIN), stepMs = step * MIN, minorMs = MINOR[step] * MIN;
            const sA = t0 - pad, sB = t1 + pad, cut = tzSplit(sA, sB);
            ctx.font = FONTS[F_RULER]; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
            for (let seg = 0; seg < (cut === cut ? 2 : 1); seg++) {
                const a = seg ? cut : sA, b = seg || !(cut === cut) ? sB : cut - 1, off = tzOffset(a);
                for (let k = Math.ceil((a + off) / minorMs), k1 = Math.floor((b + off) / minorMs); k <= k1; k++) {
                    const local = k * minorMs, t = local - off, x = posOf(t);
                    if (local % stepMs === 0) {
                        ctx.fillStyle = C.grid; ctx.fillRect(x - 0.5, TY - 4, 1, TH + 8);
                        const s = hm(t);
                        if (Math.abs(x - P0) > Math.max(58, pillW / 2 + textW(F_RULER, s) / 2 + 1)) {
                            ctx.fillStyle = C.ruler; ctx.fillText(s, x, L_LB);
                        }
                    } else {
                        ctx.fillStyle = C.minor; ctx.fillRect(x - 0.5, L_MN, 1, 3);
                    }
                }
            }
            ctx.fillStyle = C.armed;
            drawPairs(model.armed, model.nArmed, model.armedOpen ? now : NaN, t0, t1, false, 0, 0, 2, 6, TY, TH);
            const sh = mod(-c * ppm, HATCH_PERIOD);
            ctx.save(); ctx.translate(sh, 0); ctx.fillStyle = hatchPat;
            drawPairs(model.gaps, model.nGaps, model.gapOpen ? now : NaN, t0, t1, false, sh, 0, 2, 6, TY, TH);
            ctx.restore();
            if (model.nGaps) {
                ctx.font = FONTS[F_GAP]; ctx.textAlign = 'center'; ctx.fillStyle = C.gapText;
                const g = model.gaps, n = model.nGaps;
                let i = firstPairEndingAfter(g, n, t0);
                if (i >= n && model.gapOpen) i = n - 1;
                for (; i < n; i++) {
                    const s = g[2 * i];
                    if (s > t1) break;
                    let e = g[2 * i + 1];
                    if (i === n - 1 && model.gapOpen && now > e) e = now;
                    const a = Math.max(0, posOf(s)), w = Math.min(W, posOf(e)) - a;
                    if (w <= 90) continue;
                    let txt = gapFull[i];
                    if (textW(F_GAP, txt) + 28 > w) { txt = gapShort[i]; if (textW(F_GAP, txt) + 28 > w) continue; }
                    ctx.fillText(txt, a + w / 2, MY + 4);
                }
            }
            ctx.fillStyle = C.footage;
            // Short clips (most events) stay chunky enough to see and hit; releases near them snap to the moment.
            drawPairs(model.runs, model.nRuns, NaN, t0, t1, false, 0, 0, MIN_RUN_PX, 6, TY, TH);
            if (model.nUnav) {
                ctx.strokeStyle = C.unav; ctx.lineWidth = 1.2; ctx.setLineDash(DASH);
                drawPairs(model.unav, model.nUnav, NaN, t0, t1, true, -0.6, -0.6, MIN_RUN_PX, 6, TY, TH - 1.2);
                ctx.setLineDash(NO_DASH);
            }
            if (ppm * 120000 >= 14 && model.nSeams) {
                ctx.fillStyle = C.seam;
                for (let i = lowerBound(model.seams, model.nSeams, t0); i < model.nSeams && model.seams[i] <= t1; i++) {
                    ctx.fillRect(posOf(model.seams[i]) - 0.6, TY + 5, 1.2, TH - 10);
                }
            }
            if (model.nRuns) {
                const dl = label('tlview.drive');
                ctx.font = FONTS[F_DRIVE]; ctx.textAlign = 'left'; ctx.fillStyle = C.driveText;
                const dw = textW(F_DRIVE, dl), r = model.runs, n = model.nRuns;
                for (let i = firstPairEndingAfter(r, n, t0); i < n && r[2 * i] <= t1; i++) {
                    if (!model.runDrive[i]) continue;
                    const a = Math.max(0, posOf(r[2 * i])), w = Math.min(W, posOf(r[2 * i + 1])) - a;
                    if (w > 64 && dw + 16 <= w) ctx.fillText(dl, a + 10, TY + TH / 2 + 4);
                }
            }
            if (model.nLive) {
                ctx.save(); ctx.translate(sh, 0); ctx.fillStyle = livePat;
                drawPairs(model.live, model.nLive, now, t0, t1, false, sh, 0, 4, 6, TY, TH);
                ctx.restore();
            }
            if (model.liveDay) {
                const x = posOf(now);
                if (x > -6 && x < W + 6) { ctx.fillStyle = C.live; ctx.beginPath(); ctx.arc(x, TY + TH / 2, 4.5, 0, 2 * Math.PI); ctx.fill(); }
            }
            const act = interactingVisual();
            curKey = -1; curLabel = null;
            if (nCl) {
                let best = CURRENT_PX;
                const m0 = t0 - 30 / ppm, m1 = t1 + 30 / ppm;
                ctx.textAlign = 'center';
                for (let i = lowerBound(clT, nCl, m0); i < nCl && clT[i] <= m1; i++) {
                    const x = posOf(clT[i]), dx = Math.abs(x - P0), cur = dx < CURRENT_PX;
                    if (cur && dx <= best) { best = dx; curKey = clF[i]; }
                    let alpha = 1, sc = 1;
                    const age = tNow - clB[i];
                    if (age < CLUSTER_ANIM_MS) {
                        const q = 1 - age / CLUSTER_ANIM_MS, e = 1 - q * q * q;
                        alpha = e;
                        if (!reduced) sc = 0.6 + 0.4 * e;
                    }
                    const al = clA[i] === 1;
                    ctx.globalAlpha = alpha;
                    ctx.beginPath(); ctx.arc(x, MY, (cur && act ? 13 : 11) * sc, 0, 2 * Math.PI);
                    ctx.fillStyle = al ? C.alertFill : C.markFill; ctx.fill();
                    if (cur || al) { ctx.lineWidth = cur ? 2 : 1.4; ctx.strokeStyle = cur ? C.primary : C.alert; ctx.stroke(); }
                    const col = al ? C.alertIcon : C.markIcon;
                    if (clN[i] === 1) drawIcon(vK[clF[i]], x, MY, 14 * sc, col);
                    else { ctx.font = FONTS[F_COUNT]; ctx.fillStyle = col; ctx.fillText(countStr(clN[i]), x, MY + 4); }
                }
                ctx.globalAlpha = 1;
                if (curKey >= 0) curLabel = snapLabel(curKey);
            }
            ctx.fillStyle = fadeL; ctx.fillRect(0, 0, 40, H);
            ctx.save(); ctx.translate(W, 0); ctx.fillStyle = fadeR; ctx.fillRect(-40, 0, 40, H); ctx.restore();
            const top = L_H0, bot = L_H1;
            ctx.fillStyle = C.headHalo; ctx.fillRect(P0 - 2.5, top, 5, bot - top);
            ctx.fillStyle = C.head; ctx.fillRect(P0 - 1, top, 2, bot - top);
            const lab = act ? (curLabel || (flingLabel && Math.abs(posOf(flingT) - P0) < CURRENT_PX ? flingLabel : null)) : pillOverride;
            const big = pillGrow > 0.5, fi = big ? F_PILL_DRAG : F_PILL;
            const txt = pillText(hms(c), lab), tw = textW(fi, txt);
            pillTarget = Math.min(W - 8, lab ? tw + 24 : (big ? Math.max(104, tw + 28) : Math.max(84, tw + 20)));
            const ph = 22 + 4 * pillGrow, py = L_PI + (L_PD - L_PI) * pillGrow;
            rr(P0 - pillW / 2, py, pillW, ph, ph / 2); ctx.fillStyle = C.primary; ctx.fill();
            ctx.font = FONTS[fi]; ctx.textAlign = 'center'; ctx.fillStyle = C.onPrimary;
            ctx.fillText(txt, P0, py + ph / 2 + (big ? 5 : 4.5));
        }

        function drawVertical(tNow, now) {
            const tTop = c + P0 / ppm, tBot = c - (H - P0) / ppm, pad = 24 / ppm;
            const stepMs = pickStep(ppm * MIN) * MIN, sA = tBot - pad, sB = tTop + pad, cut = tzSplit(sA, sB);
            ctx.font = FONTS[F_RULER]; ctx.textAlign = 'right'; ctx.textBaseline = 'alphabetic'; ctx.fillStyle = C.ruler;
            for (let seg = 0; seg < (cut === cut ? 2 : 1); seg++) {
                const a = seg ? cut : sA, b = seg || !(cut === cut) ? sB : cut - 1, off = tzOffset(a);
                for (let k = Math.ceil((a + off) / stepMs), k1 = Math.floor((b + off) / stepMs); k <= k1; k++) {
                    const t = k * stepMs - off, y = posOf(t);
                    if (Math.abs(y - P0) >= 18) ctx.fillText(hm(t), 44, y + 4);
                }
            }
            ctx.fillStyle = C.armed;
            drawPairs(model.armed, model.nArmed, model.armedOpen ? now : NaN, tBot, tTop, false, 0, 0, 0, 4, RAIL_X, RAIL_W);
            const sh = mod(c * ppm, HATCH_PERIOD);
            ctx.save(); ctx.translate(0, sh); ctx.fillStyle = hatchPat;
            drawPairs(model.gaps, model.nGaps, model.gapOpen ? now : NaN, tBot, tTop, false, 0, sh, 2, 1, RAIL_X, RAIL_W);
            ctx.restore();
            ctx.fillStyle = C.footage;
            drawPairs(model.runs, model.nRuns, NaN, tBot, tTop, false, 0, 0, MIN_RUN_PX, 4, RAIL_X, RAIL_W);
            if (model.nUnav) {
                ctx.strokeStyle = C.unav; ctx.lineWidth = 1.2; ctx.setLineDash(DASH);
                drawPairs(model.unav, model.nUnav, NaN, tBot, tTop, true, -0.6, -0.6, MIN_RUN_PX, 4, RAIL_X, RAIL_W - 1.2);
                ctx.setLineDash(NO_DASH);
            }
            if (model.nLive) {
                ctx.save(); ctx.translate(0, sh); ctx.fillStyle = livePat;
                drawPairs(model.live, model.nLive, now, tBot, tTop, false, 0, sh, 4, 4, RAIL_X, RAIL_W);
                ctx.restore();
            }
            if (model.liveDay) {
                const y = posOf(now);
                if (y > -6 && y < H + 6) { ctx.fillStyle = C.live; ctx.beginPath(); ctx.arc(RAIL_X + RAIL_W / 2, y, 4.5, 0, 2 * Math.PI); ctx.fill(); }
            }
            curKey = -1; curLabel = null;
            let best = CURRENT_PX;
            for (let i = lowerBound(vT, nV, tBot - pad); i < nV && vT[i] <= tTop + pad; i++) {
                const y = posOf(vT[i]), dy = Math.abs(y - P0), cur = dy < CURRENT_PX;
                if (cur && dy <= best) { best = dy; curKey = i; }
                ctx.beginPath(); ctx.arc(RAIL_X + RAIL_W / 2, y, cur ? 4.6 : 3.4, 0, 2 * Math.PI);
                ctx.fillStyle = cur ? C.primary : vA[i] ? C.alert : C.dot; ctx.fill();
            }
            if (curKey >= 0) curLabel = snapLabel(curKey);
            ctx.fillStyle = C.headHalo; ctx.fillRect(0, P0 - 2.25, W, 4.5);
            ctx.fillStyle = C.head; ctx.fillRect(0, P0 - 0.9, W, 1.8);
            const big = pillGrow > 0.5, ph = 22 + 4 * pillGrow;
            pillTarget = 56 + 4 * pillGrow;
            rr(0, P0 - ph / 2, pillW, ph, ph / 2); ctx.fillStyle = C.primary; ctx.fill();
            ctx.font = FONTS[big ? F_VPILL_DRAG : F_VPILL]; ctx.textAlign = 'center'; ctx.fillStyle = C.onPrimary;
            ctx.fillText(hms(c), pillW / 2, P0 + (big ? 4.2 : 3.8));
        }

        let raf = 0, lastTs = 0, paletteReady = false;
        let scrubbing = false, lastScrubEmit = NaN;
        let wheelActive = false, wheelTimer = 0, keyActive = false, keyTimer = 0;
        let gMin = Infinity, gMax = -Infinity;
        let holdT = NaN, holdUntil = 0;
        let pbWall = NaN, pbPlaying = false, pbSpeed = 1, pbAt = 0, corr = 0, corrAt = 0;
        const cs = { x: 0, v: 0, target: 0, zeta: 1, omega: OMEGA_STD };
        const ls = { x: 0, v: 0, target: 0, zeta: 1, omega: OMEGA_STD };
        let sFocalPos = NaN, sFocalT = NaN, sUser = false, sTarget = 0;
        let vpC = NaN, vpSpan = NaN, vpW = -1, vpH = -1;
        let snapPend = false, snapPendT = NaN, snapPendLabel = null;
        let lastCurKey = -2, rangeDirty = false, lastRangeFrom = NaN, lastRangeTo = NaN;
        let ariaAt = -1e9, ariaText = '', ariaTimer = 0, liveTimer = 0;
        let anchorS = 0, anchorC = 0, lastS = 0;
        const plan = { target: 0, zeta: 1, omega: OMEGA_STD, snap: -1 };
        const vp = { from: 0, to: 0, pxPerMs: 0 };

        function emit(evt, arg) {
            const l = handlers[evt];
            if (!l || destroyed) return;
            for (let i = 0; i < l.length; i++) {
                try { l[i](arg); } catch (err) { setTimeout(function () { throw err; }, 0); }
            }
        }
        function schedule() { if (!raf && !destroyed) raf = requestAnimationFrame(frame); }
        function invalidate() { schedule(); }
        // Overscroll past the gesture bounds is shown through the rubber band, never clamped hard.
        function present(raw) {
            const lo = Math.min(model.lo, gMin), hi = Math.max(hiB(), gMax);
            if (raw < lo) return lo - rubberband((lo - raw) * ppm, axisLen) / ppm;
            if (raw > hi) return hi + rubberband((raw - hi) * ppm, axisLen) / ppm;
            return raw;
        }
        function beginScrub() {
            if (scrubbing) return;
            scrubbing = true;
            lastScrubEmit = NaN;
            emit('scrubstart');
        }
        // The strip holds the released time until the player reports it (or HOLD_MS passes).
        function holdAt(t) {
            holdT = t; holdUntil = performance.now() + HOLD_MS;
            pbWall = t; pbAt = performance.now(); pbPlaying = false; corr = 0;
        }
        function endScrub() {
            gMin = Infinity; gMax = -Infinity;
            if (!scrubbing) return;
            scrubbing = false;
            holdAt(c);
            rangeDirty = true;
            emit('scrubend', Math.round(c));
        }
        function userActive() { return pressed || dragging || pinching || wheelActive || keyActive || cAnim !== 0; }
        function followAllowed(tNow) { return !userActive() && pbWall === pbWall && !(holdUntil > tNow); }
        function followCenter(tNow) {
            let v = pbWall;
            if (pbPlaying) v += Math.min(tNow - pbAt, 1500) * pbSpeed;
            if (corr !== 0) {
                const k = Math.exp(-(tNow - corrAt) / 120);
                if (k < 0.01) corr = 0; else v += corr * k;
            }
            return v;
        }
        function clampSpan(s) { return s > SPAN_MAX ? SPAN_MAX : s < SPAN_MIN ? SPAN_MIN : s; }
        function setSpanRaw(ns, focalPos, focalT) {
            span = clampSpan(ns);
            ppm = axisLen / span;
            if (focalPos === focalPos) c = rc = focalT - SIG * (focalPos - P0) / ppm;
        }
        function reanchor() { anchorS = lastS; anchorC = rc; }
        function stopAnimations() {
            if (cAnim) { cAnim = 0; rc = c; cs.v = 0; }
            dropSpanAnim();
            snapPend = false; flingLabel = null; fadeT0 = -1;
        }
        // Ends the scrub session once nothing owns it any more (finger, wheel, keys, motion, user zoom).
        function endOwnerless() {
            if (scrubbing && !pressed && !pinching && !wheelActive && !keyActive && !cAnim && !(sAnim && sUser)) endScrub();
        }
        function dropSpanAnim() {
            const user = sAnim && sUser;
            sAnim = false; sUser = false;
            if (user) emit('span', span);
        }
        function finishCenterAnim() {
            const kind = cAnim;
            cAnim = 0;
            if (kind === 2 || kind === 3) {
                if (snapPend) { snapPend = false; emit('snapped', snapPendLabel); }
                flingLabel = null;
            }
            endOwnerless();
        }
        function emitViewport() {
            vpC = c; vpSpan = span; vpW = W; vpH = H;
            vp.from = timeAt(vertical ? H : 0);
            vp.to = timeAt(vertical ? 0 : W);
            vp.pxPerMs = ppm;
            emit('viewport', vp);
        }
        function emitRange() {
            const from = timeAt(vertical ? H : 0), to = timeAt(vertical ? 0 : W);
            if (Math.abs(from - lastRangeFrom) * ppm < 1 && Math.abs(to - lastRangeTo) * ppm < 1) return;
            lastRangeFrom = from; lastRangeTo = to;
            emit('range', { from: Math.round(from), to: Math.round(to) });
        }
        function setNumericAttr(name, value) {
            const text = String(value);
            if (canvas.getAttribute(name) !== text) canvas.setAttribute(name, text);
        }
        function setValueAttrs() {
            const lo = model.lo;
            if (isFinite(lo)) {
                setNumericAttr('aria-valuemin', 0);
                setNumericAttr('aria-valuemax', Math.max(0, Math.round((hiB() - lo) / 1000)));
                setNumericAttr('aria-valuenow', Math.max(0, Math.round((c - lo) / 1000)));
            } else setNumericAttr('aria-valuenow', Math.round(c / 1000));
        }
        const ariaTick = function () { ariaTimer = 0; updateAria(performance.now()); };
        function updateAria(tNow) {
            if (tNow - ariaAt < 1000) {
                if (!ariaTimer && !destroyed) ariaTimer = setTimeout(ariaTick, 1000 - (tNow - ariaAt));
                return;
            }
            ariaAt = tNow;
            const time = hms(c), i = nearestIndex(model.momT, model.nMom, c);
            const m = i >= 0 && Math.abs(model.momT[i] - c) <= 300000 ? model.moments[i] : null;
            const txt = m && m.label != null
                ? tr('tlview.ariaStripNearest', { time: time, moment: String(m.label) })
                : tr('tlview.ariaStrip', { time: time });
            if (txt !== ariaText) { ariaText = txt; canvas.setAttribute('aria-valuetext', txt); }
            // The bounds and relative position can change without changing the spoken time.
            setValueAttrs();
        }
        const liveTick = function () { liveTimer = 0; invalidate(); };
        function scheduleLiveTick() {
            if (liveTimer || destroyed) return;
            const now = nowMs();
            if (!model.liveDay || now - model.dataNow >= LIVE_EXTRAPOLATE_MS) return;
            const p = posOf(now);
            if (p < -10 || p > (vertical ? H : W) + 10) return;
            liveTimer = setTimeout(liveTick, Math.max(50, Math.min(1000, 1 / ppm)));
        }

        function frame() {
            raf = 0;
            if (destroyed) return;
            const tNow = performance.now();
            let dt = lastTs ? (tNow - lastTs) / 1000 : 0.016;
            lastTs = tNow;
            if (dt > 0.064) dt = 0.064; else if (!(dt > 0)) dt = 0;
            let moving = false, followed = false;
            if (sAnim) {
                springStep(ls, dt);
                const done = Math.abs(ls.x - ls.target) < 2e-4 && Math.abs(ls.v) < 2e-3;
                setSpanRaw(done ? sTarget : Math.exp(ls.x), sFocalPos, sFocalT);
                if (dragging) reanchor();
                if (done) {
                    sAnim = false;
                    if (sUser) { sUser = false; emit('span', span); endOwnerless(); }
                } else moving = true;
            }
            if (cAnim) {
                springStep(cs, dt);
                if (Math.abs(cs.x - cs.target) * ppm < 0.35 && Math.abs(cs.v) * ppm < 6) {
                    cs.x = cs.target; cs.v = 0; rc = cs.x; c = present(rc);
                    finishCenterAnim();
                } else { rc = cs.x; c = present(rc); moving = true; }
            } else if (followAllowed(tNow)) {
                const fc = followCenter(tNow);
                if (fc === fc) { c = rc = fc; followed = true; }
                if (pbPlaying || corr !== 0) moving = true;
            }
            const gT = interactingVisual() ? 1 : 0;
            if (reduced) { pillGrow = gT; pillW = pillTarget; }
            else {
                const k = 1 - Math.exp(-dt / 0.06);
                pillGrow += (gT - pillGrow) * k;
                if (Math.abs(gT - pillGrow) < 0.01) pillGrow = gT;
                pillW += (pillTarget - pillW) * k;
                if (Math.abs(pillTarget - pillW) < 0.5) pillW = pillTarget;
            }
            if (c !== vpC || span !== vpSpan || W !== vpW || H !== vpH) {
                if (!followed) rangeDirty = true;
                emitViewport();
            }
            render(tNow);
            if (scrubbing) {
                const r = Math.round(c);
                if (r !== lastScrubEmit) { lastScrubEmit = r; emit('scrub', r); }
            }
            if (snapPend && Math.abs(c - snapPendT) * ppm < 2) { snapPend = false; emit('snapped', snapPendLabel); }
            if (dragging || pinching) {
                if (lastCurKey === -2) lastCurKey = curKey;
                else if (curKey !== lastCurKey) { lastCurKey = curKey; emit('snapped', curKey >= 0 ? curLabel : null); }
            } else lastCurKey = -2;
            updateAria(tNow);
            if (moving || pillGrow !== gT || pillW !== pillTarget || clAnimUntil > tNow || fadeT0 >= 0) schedule();
            else {
                if (rangeDirty && !userActive()) { rangeDirty = false; emitRange(); }
                scheduleLiveTick();
            }
        }

        function setData(d) {
            if (destroyed) return;
            const previousStatic = model.staticData;
            model = buildModel(d, previousStatic);
            dataPerf = performance.now();
            strCache.clear();
            for (let i = 0; i < twCache.length; i++) twCache[i].clear();
            gapFull = new Array(model.nGaps); gapShort = new Array(model.nGaps);
            for (let i = 0; i < model.nGaps; i++) {
                const reason = tr(model.gapKey[i] || 'tlview.gapNoFootage');
                gapShort[i] = reason;
                gapFull[i] = tr('tlview.gapFmt', { duration: fmtDur(model.gaps[2 * i + 1] - model.gaps[2 * i]), reason: reason });
            }
            if (model.staticData !== previousStatic) applyFilter();
            lastCurKey = -2;
            // A fling whose landing left the new bounds re-plans from its current velocity (edge bounce).
            if (cAnim === 2 || cAnim === 3) {
                const lo = Math.min(model.lo, gMin), hi = Math.max(hiB(), gMax);
                const t = cs.target < lo ? lo : cs.target > hi ? hi : cs.target;
                if (t !== cs.target) {
                    const d = t - cs.x;
                    cs.target = t; cs.zeta = 0.8;
                    cs.omega = d * cs.v > 0 ? Math.min(OMEGA_STD, Math.max(OMEGA_SLOW, 1.6 * Math.abs(cs.v / d))) : OMEGA_STD;
                    snapPend = false; flingLabel = null;
                }
            }
            ariaAt = -1e9;
            invalidate();
        }

        function setPlayback(wallMs, playing, speed) {
            if (destroyed || typeof wallMs !== 'number' || !isFinite(wallMs)) return;
            const tNow = performance.now();
            const sp = speed > 0 ? +speed : 1;
            if (holdUntil > tNow) {
                if (Math.abs(wallMs - holdT) > Math.max(1500 * sp, 30 / ppm)) return;
                holdUntil = 0;
            }
            const follow = followAllowed(tNow);
            const before = follow ? followCenter(tNow) : c;
            pbWall = wallMs; pbPlaying = !!playing; pbSpeed = sp; pbAt = tNow;
            // Only playing reports are smoothed (extrapolation jitter); paused reports are exact positions.
            const d = before - wallMs;
            corr = pbPlaying && !userActive() && Math.abs(d) * ppm < 60 && Math.abs(d) < 2000 * sp ? d : 0;
            corrAt = tNow;
            invalidate();
        }

        function glideTo(ms) {
            if (destroyed || typeof ms !== 'number' || !isFinite(ms)) return;
            if (pressed || dragging || pinching || wheelActive || cAnim === 2 || cAnim === 3) return;
            holdAt(ms);
            if (reduced || Math.abs(ms - c) * ppm > 3 * axisLen) {
                fadeStart(); cAnim = 0; c = rc = ms; rangeDirty = true; invalidate();
                return;
            }
            cs.x = c; if (cAnim !== 1) cs.v = 0;
            cs.target = ms; cs.zeta = 1; cs.omega = OMEGA_STD;
            cAnim = 1;
            invalidate();
        }

        function animateSpan(target, focalPos, focalT, user) {
            target = clampSpan(target);
            if (reduced) {
                fadeStart(); dropSpanAnim(); setSpanRaw(target, focalPos, focalT);
                if (dragging) reanchor();
                if (user) emit('span', span);
                endOwnerless();
                rangeDirty = true; invalidate();
                return;
            }
            const replacedUser = sAnim && sUser && !user;
            ls.x = Math.log(span); if (!sAnim) ls.v = 0;
            ls.target = Math.log(target); ls.zeta = 1; ls.omega = OMEGA_STD;
            sAnim = true; sFocalPos = focalPos; sFocalT = focalT; sUser = !!user; sTarget = target;
            if (replacedUser) endOwnerless();
            invalidate();
        }
        function setSpan(ms, animate) {
            if (destroyed || !(ms > 0) || pinching) return;
            const target = clampSpan(ms);
            if (!animate || target === span) {
                dropSpanAnim(); setSpanRaw(target, NaN, NaN);
                if (dragging) reanchor();
                endOwnerless();
                rangeDirty = true; invalidate();
                return;
            }
            animateSpan(target, NaN, NaN, false);
        }
        function setFilter(f) {
            if (destroyed) return;
            f = f || {};
            fKinds = Array.isArray(f.kinds) ? f.kinds.map(String) : null;
            fAlerts = !!f.alertsOnly;
            applyFilter();
            lastCurKey = -2;
            invalidate();
        }
        function setPillLabel(text) {
            pillOverride = text == null || text === '' ? null : String(text);
            invalidate();
        }

        const pointers = new Map();
        let primary = -1, downS = 0, downQ = 0, downOnCanvas = false, tapCancelled = false;
        let pinchD0 = 1, pinchSpan0 = span, pinchFocalT = 0;
        let gsSpan0 = span, gsPos = 0, gsT = 0, gestureActive = false;
        const SN = 32, sT = new Float64Array(SN), sX = new Float64Array(SN);
        let sHead = 0, sCount = 0, rect = null;
        const tmpP = { x: 0, y: 0 };

        function pushSample(t, s) { sT[sHead] = t; sX[sHead] = s; sHead = (sHead + 1) % SN; if (sCount < SN) sCount++; }
        function toLocal(e, out) {
            if (!rect) rect = canvas.getBoundingClientRect();
            const kx = rect.width > 0 ? W / rect.width : 1, ky = rect.height > 0 ? H / rect.height : 1;
            out.x = (e.clientX - rect.left) * kx;
            out.y = (e.clientY - rect.top) * ky;
            return out;
        }
        function axisOf(p) { return vertical ? p.y : p.x; }
        function crossOf(p) { return vertical ? p.x : p.y; }
        function evTime(e) { return e.timeStamp > 0 ? e.timeStamp : performance.now(); }
        function gestureBounds() { if (gMin === Infinity) { gMin = c; gMax = c; } }
        // Inverse rubber band: the raw center that presents at pc, so a grab mid-bounce does not jump.
        function unrubber(pc) {
            const lo = Math.min(model.lo, gMin), hi = Math.max(hiB(), gMax);
            if (pc < lo) { const y = Math.min((lo - pc) * ppm, axisLen * 0.95); return lo - y * axisLen / (RUBBER_K * (axisLen - y)) / ppm; }
            if (pc > hi) { const y = Math.min((pc - hi) * ppm, axisLen * 0.95); return hi + y * axisLen / (RUBBER_K * (axisLen - y)) / ppm; }
            return pc;
        }
        function clampToGesture() {
            const lo = Math.min(model.lo, gMin), hi = Math.max(hiB(), gMax);
            if (c < lo) c = rc = lo; else if (c > hi) c = rc = hi;
        }

        function onPointerDown(e) {
            if (destroyed || (e.pointerType === 'mouse' && e.button !== 0)) return;
            const tg = e.target;
            if (tg !== canvas && tg && tg.closest && tg.closest('button,a,input,select,textarea,[data-tlv-nodrag]')) return;
            if (pointers.size >= 2 || pointers.has(e.pointerId)) return;
            rect = canvas.getBoundingClientRect();
            const p = toLocal(e, { x: 0, y: 0 });
            pointers.set(e.pointerId, p);
            if (tg === canvas) { try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* pointer already gone */ } }
            if (wheelActive) { clearTimeout(wheelTimer); wheelTimer = 0; wheelActive = false; gestureActive = false; }
            if (keyActive) { clearTimeout(keyTimer); keyTimer = 0; keyActive = false; }
            if (pointers.size === 1) {
                stopAnimations();
                gestureBounds();
                rc = unrubber(c);
                primary = e.pointerId; pressed = true; dragging = false; tapCancelled = false;
                downS = lastS = axisOf(p); downQ = crossOf(p); downOnCanvas = tg === canvas;
                sCount = 0; sHead = 0;
                pushSample(evTime(e), downS);
            } else startPinch();
            invalidate();
        }
        function startPinch() {
            const it = pointers.values(), a = it.next().value, b = it.next().value;
            if (!a || !b) return;
            pinching = true; dragging = false; pressed = true; tapCancelled = true;
            pinchD0 = Math.max(24, Math.abs(axisOf(a) - axisOf(b)));
            pinchSpan0 = span;
            pinchFocalT = timeAt((axisOf(a) + axisOf(b)) / 2);
            cAnim = 0; rc = c;
            pointers.forEach(function (q, id) { try { canvas.setPointerCapture(id); } catch (err) { /* not capturable */ } });
            beginScrub();
        }
        function updatePinch() {
            const it = pointers.values(), a = it.next().value, b = it.next().value;
            if (!a || !b) return;
            const before = span;
            setSpanRaw(pinchSpan0 * pinchD0 / Math.max(24, Math.abs(axisOf(a) - axisOf(b))), (axisOf(a) + axisOf(b)) / 2, pinchFocalT);
            clampToGesture();
            if (span !== before) emit('span', span);
        }
        function onPointerMove(e) {
            const p = pointers.get(e.pointerId);
            if (!p || destroyed) return;
            toLocal(e, p);
            if (pinching) { updatePinch(); invalidate(); return; }
            if (e.pointerId !== primary) return;
            const s = axisOf(p);
            lastS = s;
            if (pressed && !dragging) {
                if (Math.abs(s - downS) > SLOP_PX) {
                    dragging = true;
                    anchorS = s; anchorC = rc;
                    if (!downOnCanvas) { try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* pointer already gone */ } }
                    beginScrub();
                } else if (Math.abs(crossOf(p) - downQ) > 2 * SLOP_PX) tapCancelled = true;
            }
            if (dragging) {
                rc = anchorC - SIG * (s - anchorS) / ppm;
                c = present(rc);
                pushSample(evTime(e), s);
                invalidate();
            }
        }
        function onPointerUp(e) {
            const p = pointers.get(e.pointerId);
            if (!p || destroyed) return;
            const up = e.type === 'pointerup';
            if (up) toLocal(e, p);
            pointers.delete(e.pointerId);
            if (pinching) {
                if (pointers.size === 1) {
                    pinching = false;
                    const it = pointers.entries().next().value;
                    primary = it[0]; dragging = true; pressed = true;
                    lastS = anchorS = axisOf(it[1]); anchorC = rc;
                    sCount = 0; sHead = 0;
                    pushSample(evTime(e), anchorS);
                } else release(0);
                invalidate();
                return;
            }
            if (e.pointerId !== primary) return;
            primary = -1;
            if (dragging) {
                pushSample(evTime(e), axisOf(p));
                release(up ? velocityFrom(sT, sX, sCount, sHead, SN, evTime(e), VEL_WINDOW_MS) : 0);
            } else {
                // A tap that stopped a bounce past the bounds still springs back before the session ends.
                release(0);
                if (up && !tapCancelled && Math.abs(axisOf(p) - downS) <= SLOP_PX) handleTap(p, e.target);
            }
            invalidate();
        }
        // Flick: project, snap, spring with the finger's velocity; slow release: settle or rubber-band back.
        function release(vFinger) {
            pressed = false; dragging = false; pinching = false;
            const lo = Math.min(model.lo, gMin), hi = Math.max(hiB(), gMax);
            const v = Math.max(-MAX_FLICK_PX_S, Math.min(MAX_FLICK_PX_S, vFinger));
            const vC = -SIG * v / ppm;
            const outside = c < lo || c > hi;
            if (!outside && Math.abs(v) >= FLICK_PX_S) {
                planFling(c, vC, ppm, lo, hi, snT, nSn, plan);
                const lab = plan.snap >= 0 ? snapLabel(snS[plan.snap]) : null;
                if (reduced) {
                    fadeStart(); c = rc = plan.target;
                    if (plan.snap >= 0) emit('snapped', lab);
                    endScrub();
                    return;
                }
                cs.x = rc = c; cs.v = vC; cs.target = plan.target; cs.zeta = plan.zeta; cs.omega = plan.omega;
                cAnim = 2;
                snapPend = plan.snap >= 0; snapPendT = plan.target; snapPendLabel = lab;
                flingLabel = lab; flingT = plan.target;
                return;
            }
            if (outside) {
                const target = c < lo ? lo : hi;
                if (reduced) { fadeStart(); c = rc = target; endScrub(); return; }
                cs.x = rc = c; cs.v = 0; cs.target = target; cs.zeta = 1; cs.omega = OMEGA_STD;
                cAnim = 3;
                return;
            }
            endScrub();
        }
        function recOf(k) { const r = model.mRec[vI[k]]; return r == null ? null : r; }
        function inLive(t, now) {
            const p = model.live;
            for (let i = 0; i < model.nLive; i++) if (t >= p[2 * i] && t <= Math.max(p[2 * i + 1], now)) return true;
            return false;
        }
        function tapMark(t, rec) { glideTo(t); emit('tapmark', { t: t, rec: rec }); }
        // Footage under a tap at axis position q: a short clip (drawn MIN_RUN_PX wide) plays from its start.
        function runTarget(q) {
            const r = model.runs, n = model.nRuns, t = timeAt(q), tol = (MIN_RUN_PX / 2 + 4) / ppm;
            for (let i = firstPairEndingAfter(r, n, t - tol); i < n && r[2 * i] <= t + tol; i++) {
                const s0 = r[2 * i], e0 = r[2 * i + 1], a = posOf(s0), b = posOf(e0), len = Math.abs(b - a);
                if (Math.abs(q - (a + b) / 2) > Math.max(len, MIN_RUN_PX) / 2 + 4) continue;
                return len < MIN_RUN_PX ? s0 : Math.min(Math.max(t, s0), e0 - 500);
            }
            return NaN;
        }
        function handleTap(p, target) {
            const now = nowMs();
            if (vertical) {
                if (target !== canvas || p.x > RAIL_X + RAIL_W + 20) return;
                const i = nearestIndex(vT, nV, timeAt(p.y));
                const dm = i >= 0 ? Math.abs(posOf(vT[i]) - p.y) : Infinity;
                const dl = model.liveDay ? Math.abs(posOf(now) - p.y) : Infinity;
                if (dl <= 20 && dl <= dm) emit('taplive');
                else if (dm <= 24) tapMark(vT[i], recOf(i));
                else if (p.x >= RAIL_X - 12 && p.x <= RAIL_X + RAIL_W + 12) { const rt = runTarget(p.y); if (rt === rt) tapMark(rt, null); }
                return;
            }
            const TY = L_TY, MY = L_MY;
            if (p.y >= TY - 6 && p.y <= TY + L_TH + 6 &&
                ((model.liveDay && Math.abs(posOf(now) - p.x) <= 22) || inLive(timeAt(p.x), now))) {
                emit('taplive');
                return;
            }
            if (p.y >= TY - 2 && p.y <= TY + L_TH + 6) {
                const rt = runTarget(p.x);
                if (rt === rt) { tapMark(rt, null); return; }
            }
            if (p.y > MY + 22 || !nCl) return;
            const i = nearestIndex(clT, nCl, timeAt(p.x));
            if (i < 0 || Math.abs(posOf(clT[i]) - p.x) > 22) return;
            if (clN[i] === 1) tapMark(vT[clF[i]], recOf(clF[i]));
            else zoomIntoCluster(i);
        }
        function zoomIntoCluster(i) {
            const f = clF[i], n = clN[i];
            let gap = Infinity;
            for (let k = f + 1; k < f + n; k++) { const g = vT[k] - vT[k - 1]; if (g > 0 && g < gap) gap = g; }
            const want = gap === Infinity ? SPAN_MIN : axisLen * gap / 40;
            const target = Math.max(SPAN_MIN, span / 8, Math.min(span / 2, want));
            if (!(target < span)) return;
            gestureBounds();
            beginScrub();
            animateSpan(target, posOf(clT[i]), clT[i], true);
        }

        const endWheel = function () { wheelTimer = 0; wheelActive = false; gestureActive = false; endScrub(); invalidate(); };
        function wheelSession() {
            stopAnimations();
            if (!wheelActive) { wheelActive = true; gestureBounds(); }
            beginScrub();
            clearTimeout(wheelTimer);
            wheelTimer = 0;
        }
        function onWheel(e) {
            if (destroyed || pointers.size) return;
            e.preventDefault();
            const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? axisLen : 1;
            const dx = e.deltaX * unit, dy = e.deltaY * unit;
            if ((!dx && !dy) || (e.ctrlKey && gestureActive)) return;
            wheelSession();
            if (e.ctrlKey) {
                rect = canvas.getBoundingClientRect();
                const pos = axisOf(toLocal(e, tmpP)), ft = timeAt(pos), before = span;
                setSpanRaw(span * Math.exp(Math.max(-1, Math.min(1, dy * 0.01))), pos, ft);
                if (span !== before) emit('span', span);
            } else {
                const d = vertical ? (Math.abs(dy) >= Math.abs(dx) ? dy : dx) : (Math.abs(dx) > Math.abs(dy) ? dx : dy);
                c = rc = c + SIG * d / ppm;
            }
            clampToGesture();
            wheelTimer = setTimeout(endWheel, 180);
            invalidate();
        }
        function onGestureStart(e) {
            if (destroyed || pointers.size) return;
            e.preventDefault();
            wheelSession();
            gestureActive = true;
            rect = canvas.getBoundingClientRect();
            gsSpan0 = span; gsPos = axisOf(toLocal(e, tmpP)); gsT = timeAt(gsPos);
        }
        function onGestureChange(e) {
            if (destroyed || pointers.size || !gestureActive) return;
            e.preventDefault();
            const before = span;
            setSpanRaw(gsSpan0 / (e.scale > 0 ? e.scale : 1), gsPos, gsT);
            clampToGesture();
            if (span !== before) emit('span', span);
            invalidate();
        }
        function onGestureEnd(e) {
            if (destroyed || !gestureActive) return;
            e.preventDefault();
            clearTimeout(wheelTimer);
            wheelTimer = setTimeout(endWheel, 60);
        }

        const endKeys = function () { keyTimer = 0; keyActive = false; endScrub(); invalidate(); };
        function keyJump(t) {
            if (pressed || pinching || !(t === t)) return;
            stopAnimations();
            c = rc = Math.max(model.lo, Math.min(hiB(), t));
            keyActive = true;
            beginScrub();
            clearTimeout(keyTimer);
            keyTimer = setTimeout(endKeys, 250);
            invalidate();
        }
        function stepMoment(fwd) {
            if (pressed || pinching) return;
            let arrT = model.momT, n = model.nMom, useMarks = false;
            if (!n) { arrT = vT; n = nV; useMarks = true; }
            if (!n) return;
            const i = fwd ? lowerBound(arrT, n, c + 500) : lowerBound(arrT, n, c - 500) - 1;
            if (i < 0 || i >= n) return;
            if (keyActive) { clearTimeout(keyTimer); keyTimer = 0; keyActive = false; }
            stopAnimations();
            const t = arrT[i];
            c = rc = t;
            endOwnerless();
            holdAt(t);
            rangeDirty = true;
            const m = useMarks ? null : model.moments[i];
            emit('tapmark', { t: t, rec: useMarks ? recOf(i) : (m.rec == null ? null : m.rec) });
            invalidate();
        }
        function keyZoom(f) {
            if (pinching) return;
            const before = span;
            dropSpanAnim();
            setSpanRaw(span * f, NaN, NaN);
            if (dragging) reanchor();
            if (span !== before) { emit('span', span); rangeDirty = true; }
            endOwnerless();
            invalidate();
        }
        function onKeyDown(e) {
            if (destroyed || e.altKey || e.metaKey || e.ctrlKey) return;
            const k = e.key;
            if (k === 'ArrowLeft' || k === 'ArrowRight') keyJump(c + (e.shiftKey ? 60000 : 10000) * (k === 'ArrowRight' ? 1 : -1));
            else if (k === 'ArrowUp' || k === 'ArrowDown') stepMoment(vertical ? k === 'ArrowUp' : k === 'ArrowDown');
            else if (k === 'Home') { if (isFinite(model.lo)) keyJump(model.lo); }
            else if (k === 'End') keyJump(hiB());
            else if (k === '+' || k === '=' || k === 'Add') keyZoom(0.5);
            else if (k === '-' || k === '_' || k === 'Subtract') keyZoom(2);
            else return;
            e.preventDefault();
            e.stopPropagation();
        }
        const onContextMenu = function (e) { e.preventDefault(); };

        function resize() {
            if (destroyed) return;
            W = Math.max(0, hostEl.clientWidth || 0);
            H = Math.max(0, hostEl.clientHeight || 0);
            const nd = Math.max(1, Math.min(4, window.devicePixelRatio || 1));
            const dprChanged = nd !== dpr;
            dpr = nd;
            const bw = Math.max(1, Math.round(W * dpr)), bh = Math.max(1, Math.round(H * dpr));
            if (canvas.width !== bw) canvas.width = bw;
            if (canvas.height !== bh) canvas.height = bh;
            axisLen = Math.max(1, vertical ? H : W);
            P0 = vertical ? Math.round(H * 0.4) : W / 2;
            const k = H < 88 ? Math.max(0.6, H / 88) : 1, oy = H > 88 ? Math.floor((H - 88) / 2) : 0;
            L_PI = oy + 4 * k; L_PD = oy + k; L_LB = oy + 20 * k; L_MN = oy + 24 * k; L_MY = oy + 40 * k;
            L_TY = oy + 54 * k; L_TH = Math.max(18, Math.round(30 * k)); L_H0 = oy + 26 * k; L_H1 = Math.min(H, L_TY + L_TH + 4);
            ppm = axisLen / span;
            rect = null;
            if (dragging) reanchor();
            if (pinching) startPinch();
            if (dprChanged || !paletteReady) { refreshPalette(); paletteReady = true; }
            if (dprChanged || !dprMq) watchDpr();
            render(performance.now());
            emitViewport();
            schedule();
        }
        let ro = null, mo = null, dprMq = null;
        const onDprChange = function () { resize(); };
        function watchDpr() {
            if (dprMq) {
                if (dprMq.removeEventListener) dprMq.removeEventListener('change', onDprChange);
                else if (dprMq.removeListener) dprMq.removeListener(onDprChange);
                dprMq = null;
            }
            if (!window.matchMedia) return;
            dprMq = window.matchMedia('(resolution: ' + (window.devicePixelRatio || 1) + 'dppx)');
            if (dprMq.addEventListener) dprMq.addEventListener('change', onDprChange);
            else if (dprMq.addListener) dprMq.addListener(onDprChange);
        }
        const onWinResize = function () { resize(); };
        const onTheme = function () { refreshPalette(); invalidate(); };
        if (typeof ResizeObserver === 'function') { ro = new ResizeObserver(onWinResize); ro.observe(hostEl); }
        else window.addEventListener('resize', onWinResize);
        if (typeof MutationObserver === 'function') {
            mo = new MutationObserver(onTheme);
            mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
        }
        const wheelOpts = { passive: false };
        hostEl.addEventListener('pointerdown', onPointerDown);
        hostEl.addEventListener('pointermove', onPointerMove);
        hostEl.addEventListener('pointerup', onPointerUp);
        hostEl.addEventListener('pointercancel', onPointerUp);
        hostEl.addEventListener('wheel', onWheel, wheelOpts);
        hostEl.addEventListener('gesturestart', onGestureStart);
        hostEl.addEventListener('gesturechange', onGestureChange);
        hostEl.addEventListener('gestureend', onGestureEnd);
        canvas.addEventListener('lostpointercapture', onPointerUp);
        canvas.addEventListener('keydown', onKeyDown);
        canvas.addEventListener('contextmenu', onContextMenu);
        window.addEventListener('pointerup', onPointerUp);
        window.addEventListener('pointercancel', onPointerUp);

        function destroy() {
            if (destroyed) return;
            destroyed = true;
            if (raf) cancelAnimationFrame(raf);
            raf = 0;
            clearTimeout(wheelTimer); clearTimeout(keyTimer); clearTimeout(ariaTimer); clearTimeout(liveTimer);
            hostEl.removeEventListener('pointerdown', onPointerDown);
            hostEl.removeEventListener('pointermove', onPointerMove);
            hostEl.removeEventListener('pointerup', onPointerUp);
            hostEl.removeEventListener('pointercancel', onPointerUp);
            hostEl.removeEventListener('wheel', onWheel, wheelOpts);
            hostEl.removeEventListener('gesturestart', onGestureStart);
            hostEl.removeEventListener('gesturechange', onGestureChange);
            hostEl.removeEventListener('gestureend', onGestureEnd);
            canvas.removeEventListener('lostpointercapture', onPointerUp);
            canvas.removeEventListener('keydown', onKeyDown);
            canvas.removeEventListener('contextmenu', onContextMenu);
            window.removeEventListener('pointerup', onPointerUp);
            window.removeEventListener('pointercancel', onPointerUp);
            if (ro) ro.disconnect(); else window.removeEventListener('resize', onWinResize);
            if (mo) mo.disconnect();
            if (dprMq) {
                if (dprMq.removeEventListener) dprMq.removeEventListener('change', onDprChange);
                else if (dprMq.removeListener) dprMq.removeListener(onDprChange);
                dprMq = null;
            }
            pointers.clear();
            if (canvas.parentNode) canvas.parentNode.removeChild(canvas);
            hostEl.style.touchAction = prevTouch;
            if (posPatched) hostEl.style.position = prevPos;
            for (const k in handlers) delete handlers[k];
        }

        // The 'viewport' payload object is reused every frame; copy fields if retained.
        const api = {
            setData: setData, setPlayback: setPlayback, glideTo: glideTo, setSpan: setSpan,
            setFilter: setFilter, setPillLabel: setPillLabel,
            timeToPos: function (ms) { return posOf(ms); },
            posToTime: function (px) { return timeAt(px); },
            resize: resize, destroy: destroy,
            on: function (evt, fn) {
                if (typeof fn === 'function' && !destroyed) handlers[evt] = (handlers[evt] || []).concat([fn]);
                return api;
            },
            off: function (evt, fn) {
                const l = handlers[evt];
                if (l) {
                    const n = fn ? l.filter(function (f) { return f !== fn; }) : [];
                    if (n.length) handlers[evt] = n; else delete handlers[evt];
                }
                return api;
            },
            get centerMs() { return c; },
            get spanMs() { return span; }
        };
        resize();
        return api;
    }

    window.BYD = window.BYD || {};
    window.BYD.tlstrip = {
        create: create,
        _internals: {
            pickStep: pickStep, rubberband: rubberband, project: project, velocityFrom: velocityFrom,
            springStep: springStep, planFling: planFling, clusterMarks: clusterMarks, nearestIndex: nearestIndex,
            lowerBound: lowerBound, mergeIntervals: mergeIntervals, subtractIntervals: subtractIntervals,
            gapReasons: gapReasons, buildModel: buildModel, parseColor: parseColor,
            constants: { SPAN_MIN: SPAN_MIN, SPAN_MAX: SPAN_MAX, SNAP_PX: SNAP_PX, CLUSTER_PX: CLUSTER_PX, SLOP_PX: SLOP_PX,
                FLICK_PX_S: FLICK_PX_S, OMEGA_STD: OMEGA_STD, OMEGA_SLOW: OMEGA_SLOW, DECEL: DECEL, RUBBER_K: RUBBER_K }
        }
    };
})();
