/* Recordings Timeline view (BYD.tlview): List|Timeline switch, layouts, day-chunked data, Moments and playback controls. */
(function (root) {
    'use strict';

    var BYD = root.BYD = root.BYD || {};
    var doc = root.document;

    var LS = {
        mode: 'byd.events.view', speed: 'byd.tlview.speed', skip: 'byd.tlview.skipQuiet',
        cont: 'byd.tlview.continue', prefer: 'byd.tlview.prefer', filter: 'byd.tlview.filter', tz: 'byd.tlview.tz'
    };
    var SEC = 1000, MIN = 60 * SEC, HOUR = 60 * MIN, DAY = 24 * HOUR;
    var ZOOMS = [['zoomDay', DAY], ['zoom6h', 6 * HOUR], ['zoom1h', HOUR], ['zoom10m', 10 * MIN]];
    var SPEEDS = [1, 2, 4, 8];
    var ROW_H = 56, ROW_GAP = 4, CACHE_DAYS = 7, REFRESH_MS = 30 * SEC, SCROLL_HOLD_MS = 5 * SEC, HIDE_MS = 3 * SEC;
    var FETCH_TIMEOUT_MS = 15 * SEC, DAYS_BACK = 60, THUMB_CACHE = 240, THUMB_PARALLEL = 4;
    // Past days re-read this often (and on return to the page); the player takes a day copy read this recently as is.
    var PAST_REFRESH_MS = 5 * MIN, SHARE_MS = 5 * SEC, RESUME_KEY = 'byd.tlview.resume';
    var MOMENT_TYPES = { sentry: true, proximity: true, replay: true };
    var FILTERS = {
        all: { kinds: null, alertsOnly: false },
        people: { kinds: ['person'], alertsOnly: false },
        vehicles: { kinds: ['vehicle', 'bike'], alertsOnly: false },
        alerts: { kinds: null, alertsOnly: true }
    };
    var FILTER_ORDER = ['all', 'people', 'vehicles', 'alerts'];
    var FILTER_KEY = { all: 'filterAll', people: 'filterPeople', vehicles: 'filterVehicles', alerts: 'filterAlerts' };
    var CLS_ALIAS = {
        person: 'person', pedestrian: 'person', people: 'person',
        vehicle: 'vehicle', car: 'vehicle', truck: 'vehicle', bus: 'vehicle', van: 'vehicle',
        bike: 'bike', bicycle: 'bike', motorcycle: 'bike', motorbike: 'bike',
        animal: 'animal', dog: 'animal', cat: 'animal'
    };
    var CLS_ORDER = ['person', 'vehicle', 'bike', 'animal'];
    var KIND_KEY = { person: 'kindPerson', vehicle: 'kindVehicle', bike: 'kindBike', animal: 'kindAnimal', motion: 'kindMotion', deterrent: 'kindDeterrent' };
    var CAM_KEY = { front: 'camFront', right: 'camRight', rear: 'camRear', left: 'camLeft' };
    var LAYOUTS = {
        standard: [{ cam: 'front', x: 0, y: 0, w: 0.5, h: 0.5 }, { cam: 'right', x: 0.5, y: 0, w: 0.5, h: 0.5 },
                   { cam: 'rear', x: 0, y: 0.5, w: 0.5, h: 0.5 }, { cam: 'left', x: 0.5, y: 0.5, w: 0.5, h: 0.5 }],
        dashcam: [{ cam: 'front', x: 0, y: 0, w: 1, h: 0.7 }, { cam: 'left', x: 0, y: 0.7, w: 1 / 3, h: 0.3 },
                  { cam: 'rear', x: 1 / 3, y: 0.7, w: 1 / 3, h: 0.3 }, { cam: 'right', x: 2 / 3, y: 0.7, w: 1 / 3, h: 0.3 }],
        single: [{ cam: 'front', x: 0, y: 0, w: 1, h: 1 }]
    };

    // English literals; every key mirrors i18n "tlview" and backs untranslated locales.
    var EN = {
        tabList: 'List', tabTimeline: 'Timeline',
        filterAll: 'All', filterPeople: 'People', filterVehicles: 'Vehicles', filterAlerts: 'Alerts',
        zoomDay: 'Day', zoom6h: '6h', zoom1h: '1h', zoom10m: '10m',
        moments: 'Moments', momentsCount: { one: '{count} moment', other: '{count} moments' },
        playing: 'Playing', alert: 'Alert', critical: 'Critical',
        kindPerson: 'Person', kindVehicle: 'Vehicle', kindBike: 'Bike', kindAnimal: 'Animal', kindMotion: 'Motion',
        kindDeterrent: 'Deterrent', kindAnd: '{a} & {b}', kindEvent: 'Event',
        closePass: 'Close pass', savedReplay: 'Saved replay', drive: 'Drive',
        roadPothole: 'Pothole', roadBreaker: 'Speed breaker', roadRough: 'Rough road',
        gapNoFootage: 'No footage', gapFmt: '{duration} · {reason}',
        reason: {
            user_off: 'sentry off', acc_on: 'car on', owner_returned: 'owner returned', schedule_end: 'outside sentry schedule',
            safe_zone: 'safe zone', lease_revoked: 'sentry restarted', pipeline_down: 'cameras unavailable', soc_cutoff: 'battery saver',
            lv_low: '12 V battery low', daemon_stop: 'recorder not running', storage_unavailable: 'storage unavailable',
            storage_full: 'storage full', driving: 'driving, not recorded', deleted: 'deleted',
            overwritten: 'overwritten by loop recording', removed: 'removed', unknown: 'no footage'
        },
        sentry: {
            surveillance_off: 'sentry off', vehicle_on_only: 'sentry set to car-on only', suppressed_safe_zone: 'safe zone',
            suppressed_schedule: 'outside sentry schedule', pipeline_down: 'cameras unavailable', lock_wait: 'waiting for the car to lock'
        },
        armedHint: 'Sentry armed · nothing happened',
        unavailableVolume: 'On {volume} (not inserted)', storageSd: 'SD card', storageUsb: 'USB drive', storageInternal: 'internal storage',
        live: 'Live',
        speedTitle: 'Playback speed', speedNote: '4× and 8× play without sound',
        skipQuiet: 'Skip quiet driving', skipQuietDesc: 'Drives play at 8×, slow to 1× for events',
        continueNext: 'Continue to next event', continueNextDesc: 'Skips the empty time between clips',
        sourceMosaic: '360°', sourceOem: 'Dashcam front', sourceSwitch: 'Switch camera source',
        nextEventIn: 'Next event in {duration}',
        emptyDay: 'No recordings on {day}', offline: "Can't reach the recorder", retry: 'Retry',
        clipError: "Couldn't play this clip", cantPlayHere: "This clip can't play in this browser", download: 'Download',
        clipGone: 'This clip is no longer available', gapDamaged: "Damaged clip · can't be played",
        gapIndexing: 'Still being indexed · try again shortly', recordingNow: 'Recording now · plays once saved', speedAuto: 'Auto',
        today: 'Today', yesterday: 'Yesterday',
        camFront: 'Front', camRight: 'Right', camRear: 'Rear', camLeft: 'Left', camNotInClip: "{camera} isn't in this clip",
        ariaStrip: 'Timeline at {time}', ariaStripNearest: 'Timeline at {time}, nearest moment {moment}',
        ariaPlay: 'Play', ariaPause: 'Pause', ariaPrev: 'Previous event', ariaNext: 'Next event',
        ariaSpeed: 'Playback speed {speed}', ariaDate: 'Choose date and time', ariaFilter: 'Filter', ariaListView: 'List view',
        fullscreen: 'Fullscreen', exitFullscreen: 'Exit fullscreen', share: 'Share segment', shareUnavailable: 'No clip to share',
        shareCopied: 'Download link copied', shareFailed: "Couldn't share this clip",
        shareRecording: "This clip is still recording. Share it once it's saved.",
        durHm: '{h} h {m} min', durM: '{m} min', durS: '{s} s',
        watchDrive: 'Watch this drive', watchSession: 'Watch this session', rewind: 'Rewind'
    };

    // ------------------------------------------------------------------ i18n / small utils

    function lookupEn(key) {
        var parts = String(key).split('.'), cur = EN;
        for (var i = 0; i < parts.length; i++) {
            if (cur == null || typeof cur !== 'object') return null;
            cur = cur[parts[i]];
        }
        return cur == null ? null : cur;
    }
    function interp(s, vars) {
        if (!vars || typeof s !== 'string') return s;
        return s.replace(/\{(\w+)\}/g, function (m, n) { return vars[n] != null ? String(vars[n]) : m; });
    }
    function tr(key, vars) {
        var full = 'tlview.' + key, v = null;
        try { if (BYD.i18n && BYD.i18n.t) v = BYD.i18n.t(full, vars); } catch (e) { v = null; }
        if (typeof v !== 'string' || v === full) {
            var en = lookupEn(key);
            if (en && typeof en === 'object') en = en.other;
            v = interp(typeof en === 'string' ? en : key, vars);
        }
        return v;
    }
    function trCount(key, n) {
        var full = 'tlview.' + key, v = null;
        try { if (BYD.i18n && BYD.i18n.plural) v = BYD.i18n.plural(full, n); } catch (e) { v = null; }
        if (typeof v !== 'string' || v === full) {
            var en = lookupEn(key) || {};
            v = interp(n === 1 ? en.one : en.other, { count: n });
        }
        return v;
    }
    function reasonText(code) { return tr('reason.' + (EN.reason[code] ? code : 'unknown')); }
    function lang() {
        try { if (BYD.i18n && BYD.i18n.getLang) return BYD.i18n.getLang() || 'en'; } catch (e) { /* fall through */ }
        return 'en';
    }
    function esc(s) {
        return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function pad2(n) { return (n < 10 ? '0' : '') + n; }
    /** Same-origin absolute path only ("/x", never "//host"). */
    function localPath(u) {
        // URL parsing drops tabs and newlines, so "/\t/host" would become protocol-relative: resolve and compare origins.
        if (typeof u !== 'string' || u.charAt(0) !== '/' || /[\u0000-\u0020\u007f\\]/.test(u)) return false;
        try { return new URL(u, root.location.href).origin === root.location.origin; } catch (e) { return u.charAt(1) !== '/'; }
    }
    function num(x) { var n = Number(x); return x != null && x !== '' && isFinite(n) ? n : null; }
    function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }
    function lsGet(k) { try { return root.localStorage ? root.localStorage.getItem(k) : null; } catch (e) { return null; } }
    function lsSet(k, v) { try { if (root.localStorage) root.localStorage.setItem(k, String(v)); } catch (e) { /* private mode */ } }

    // ------------------------------------------------------------------ car time zone

    var partsFmtCache = {};
    function partsFormatter(tz) {
        if (Object.prototype.hasOwnProperty.call(partsFmtCache, tz)) return partsFmtCache[tz];
        var f = null;
        try {
            f = new Intl.DateTimeFormat('en-US', {
                timeZone: tz, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit', weekday: 'short'
            });
            if (typeof f.formatToParts !== 'function') f = null; else f.formatToParts(new Date(0));
        } catch (e) { f = null; }
        partsFmtCache[tz] = f;
        return f;
    }
    var WEEKDAY = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };

    /** Wall-clock math in the car's zone; falls back to a fixed offset when Intl lacks the zone. */
    function Zone(tz, offsetMin) {
        this.tz = tz ? String(tz) : null;
        this.f = this.tz ? partsFormatter(this.tz) : null;
        var off = num(offsetMin);
        this.off = off != null ? off : -new Date().getTimezoneOffset();
    }
    Zone.prototype.parts = function (ms) {
        if (this.f) {
            var p = this.f.formatToParts(new Date(ms)), o = {};
            for (var i = 0; i < p.length; i++) o[p[i].type] = p[i].value;
            var h = +o.hour;
            return { y: +o.year, mo: +o.month, d: +o.day, h: h === 24 ? 0 : h, mi: +o.minute, s: +o.second, wd: WEEKDAY[o.weekday] };
        }
        var d = new Date(ms + this.off * MIN);
        return { y: d.getUTCFullYear(), mo: d.getUTCMonth() + 1, d: d.getUTCDate(), h: d.getUTCHours(),
                 mi: d.getUTCMinutes(), s: d.getUTCSeconds(), wd: d.getUTCDay() };
    };
    Zone.prototype.offsetAt = function (ms) {
        if (!this.f) return this.off;
        var base = Math.floor(ms / SEC) * SEC, p = this.parts(base);
        return Math.round((Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - base) / MIN);
    };
    Zone.prototype.toEpoch = function (y, mo, d, h, mi, s) {
        var guess = Date.UTC(y, mo - 1, d, h || 0, mi || 0, s || 0);
        var o1 = this.offsetAt(guess), t = guess - o1 * MIN, o2 = this.offsetAt(t);
        if (o2 !== o1) t = guess - o2 * MIN;
        return t;
    };
    Zone.prototype.dayKey = function (ms) {
        var p = this.parts(ms);
        return p.y + '-' + pad2(p.mo) + '-' + pad2(p.d);
    };
    Zone.prototype.dayRange = function (key) {
        var k = parseKey(key), n = parseKey(addDaysKey(key, 1));
        return { from: this.toEpoch(k.y, k.mo, k.d, 0, 0, 0), to: this.toEpoch(n.y, n.mo, n.d, 0, 0, 0) };
    };
    /** Day keys overlapping [from, to). */
    Zone.prototype.daysIn = function (from, to) {
        var out = [], key = this.dayKey(from);
        for (var guard = 0; guard < 64; guard++) {
            out.push(key);
            if (this.dayRange(key).to >= to) break;
            key = addDaysKey(key, 1);
        }
        return out;
    };
    function parseKey(key) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(key || ''));
        return m ? { y: +m[1], mo: +m[2], d: +m[3] } : null;
    }
    function addDaysKey(key, n) {
        var k = parseKey(key), d = new Date(Date.UTC(k.y, k.mo - 1, k.d + n));
        return d.getUTCFullYear() + '-' + pad2(d.getUTCMonth() + 1) + '-' + pad2(d.getUTCDate());
    }
    function browserTz() {
        try { return Intl.DateTimeFormat().resolvedOptions().timeZone || null; } catch (e) { return null; }
    }

    // ------------------------------------------------------------------ recording names

    var FILE_RE = /^(cam\d*|event|proximity|replay|dvr_p|dvr)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?:_\d+)?\.mp4$/i;
    var FILE_TYPE = { event: 'sentry', proximity: 'proximity', replay: 'replay', dvr: 'oemDashcam', dvr_p: 'oemDashcam' };
    /** Writer stamp (car-local yyyyMMdd_HHmmss) and clip type from a recording file name. */
    function parseFilename(name) {
        var base = String(name || '').split(/[\\/]/).pop();
        var m = FILE_RE.exec(base);
        if (!m) return null;
        var pre = m[1].toLowerCase();
        var f = { type: /^cam/.test(pre) ? 'normal' : FILE_TYPE[pre], y: +m[2], mo: +m[3], d: +m[4], h: +m[5], mi: +m[6], s: +m[7] };
        if (f.mo < 1 || f.mo > 12 || f.d < 1 || f.d > 31 || f.h > 23 || f.mi > 59 || f.s > 59) return null;
        return f;
    }
    /** Segment the stamp belongs to: replay stamps mark the end, sentry/proximity stamps sit after a pre-roll. */
    function matchFileSegment(type, wall, segments) {
        var best = null, bestScore = Infinity;
        for (var i = 0; i < segments.length; i++) {
            var s = segments[i];
            var end = s.end != null ? s.end : s.start + SEC;
            if (wall < s.start - 3 * SEC || wall > end + 3 * SEC) continue;
            var d = type === 'replay' ? Math.abs(end - wall)
                : (type === 'normal' || type === 'oemDashcam') ? Math.abs(s.start - wall) : Math.abs(wall - s.start);
            var score = d + (s.type === type ? 0 : 1e9);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    // ------------------------------------------------------------------ moments

    function segClasses(s, marks) {
        var counts = {}, seen = {}, out = [];
        function add(raw, n) {
            var c = CLS_ALIAS[String(raw || '').toLowerCase()];
            if (!c) return;
            counts[c] = (counts[c] || 0) + (n || 0);
            if (!seen[c]) { seen[c] = true; out.push(c); }
        }
        var cs = s.counts || {};
        for (var k in cs) if (Object.prototype.hasOwnProperty.call(cs, k) && num(cs[k]) > 0) add(k, num(cs[k]));
        var cl = Array.isArray(s.classes) ? s.classes : [];
        for (var i = 0; i < cl.length; i++) add(cl[i], 0);
        for (var j = 0; j < marks.length; j++) add(marks[j].kind, 0);
        out.sort(function (a, b) { return (counts[b] || 0) - (counts[a] || 0) || CLS_ORDER.indexOf(a) - CLS_ORDER.indexOf(b); });
        return out;
    }
    function makeMoment(s, marks) {
        var first = null, motion = false;
        for (var i = 0; i < marks.length; i++) {
            var k = marks[i].kind, t0 = num(marks[i].t0);
            if (k === 'motion') motion = true;
            if (k !== 'deterrent' && t0 != null && (first == null || t0 < first)) first = t0;
        }
        var end = num(s.end);
        var t = s.start;
        if (s.type === 'replay') t = end != null ? end : s.start;
        else if (first != null && first >= s.start && (end == null || first <= end)) t = first;
        var sev = String(s.peakSeverity || '').toUpperCase();
        return {
            id: String(s.id), seg: s, t: t, start: s.start, end: end, marks: marks, motion: motion,
            classes: segClasses(s, marks), level: sev === 'CRITICAL' ? 'critical' : (sev === 'ALERT' ? 'alert' : null)
        };
    }
    /** One moment per sentry/proximity/replay clip, newest first. */
    function buildMoments(segments, marks) {
        var byRec = {}, out = [];
        for (var i = 0; i < marks.length; i++) {
            var mk = marks[i];
            if (mk && mk.rec != null) (byRec[mk.rec] || (byRec[mk.rec] = [])).push(mk);
        }
        for (var j = 0; j < segments.length; j++) {
            var s = segments[j];
            if (s && MOMENT_TYPES[s.type] && num(s.start) != null) out.push(makeMoment(s, byRec[s.id] || []));
        }
        out.sort(function (a, b) { return (b.t - a.t) || (a.id < b.id ? -1 : 1); });
        return out;
    }
    function momentKind(m) {
        if (m.seg.type === 'proximity') return tr('closePass');
        if (m.seg.type === 'replay') return tr('savedReplay');
        if (m.classes.length >= 2) {
            var b = tr(KIND_KEY[m.classes[1]]);
            if (/^en/i.test(lang())) b = b.toLowerCase();
            return tr('kindAnd', { a: tr(KIND_KEY[m.classes[0]]), b: b });
        }
        if (m.classes.length === 1) return tr(KIND_KEY[m.classes[0]]);
        return tr(m.motion ? 'kindMotion' : 'kindEvent');
    }
    function momentPasses(m, filterKey) {
        var f = FILTERS[filterKey] || FILTERS.all;
        if (f.alertsOnly && !m.level) return false;
        if (!f.kinds) return true;
        for (var i = 0; i < f.kinds.length; i++) if (m.classes.indexOf(f.kinds[i]) >= 0) return true;
        return false;
    }
    function firstCamera(m) {
        var c = String(m.seg.cameras || '').toLowerCase().split(/[\s,;|]+/);
        for (var i = 0; i < c.length; i++) if (CAM_KEY[c[i]]) return c[i];
        for (var j = 0; j < m.marks.length; j++) if (CAM_KEY[m.marks[j].cam]) return m.marks[j].cam;
        return null;
    }
    function momentMeta(m) {
        var parts = [], cam = firstCamera(m);
        if (cam) parts.push(tr(CAM_KEY[cam]));
        if (m.seg.place) parts.push(String(m.seg.place));
        if (m.end != null && m.end > m.start) parts.push(fmtClipDur(m.end - m.start));
        return parts.join(' · ');
    }

    /** Collision push for rows placed at their time: centred on pos, then shifted down past the previous row. */
    function pushRows(items, rowH, gap, minTop) {
        var sorted = items.slice().sort(function (a, b) { return a.pos - b.pos; });
        var out = [], prevBottom = -Infinity;
        for (var i = 0; i < sorted.length; i++) {
            var top = sorted[i].pos - rowH / 2;
            if (minTop != null && top < minTop) top = minTop;
            if (top < prevBottom + gap) top = prevBottom + gap;
            out.push({ key: sorted[i].key, pos: sorted[i].pos, top: top });
            prevBottom = top + rowH;
        }
        return out;
    }
    // Same rounding as the head unit (GapReasons.durationText): whole seconds from 1 s, minutes by 30 s.
    function fmtSpan(ms) {
        ms = Math.max(0, ms);
        var s = Math.floor((ms + 500) / SEC);
        if (s < 60) return tr('durS', { s: Math.max(1, s) });
        var m = Math.floor((ms + 30 * SEC) / MIN);
        if (m < 60) return tr('durM', { m: m });
        return tr('durHm', { h: Math.floor(m / 60), m: m % 60 });
    }
    function fmtClipDur(ms) {
        var s = Math.max(0, Math.round(ms / SEC)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
        return (h ? h + ':' + pad2(m) : String(m)) + ':' + pad2(s % 60);
    }

    // ------------------------------------------------------------------ data store (day chunks)

    function normalizeWindow(j) {
        function arr(x) { return Array.isArray(x) ? x : []; }
        var segs = [];
        arr(j.segments).forEach(function (s) {
            if (!s || s.id == null || num(s.start) == null) return;
            s.start = num(s.start);
            s.end = num(s.end);
            segs.push(s);
        });
        return {
            segments: segs, marks: arr(j.marks), sessions: arr(j.sessions), trips: arr(j.trips), states: arr(j.states),
            reaped: arr(j.reaped), road: arr(j.road), live: arr(j.live), truncated: !!j.truncated, warming: !!j.warming
        };
    }
    var UNCHANGED = {};
    function authHeaders() {
        try { return typeof BYDAuth !== 'undefined' && BYDAuth && BYDAuth.getAuthHeaders ? BYDAuth.getAuthHeaders() || {} : {}; } catch (e) { return {}; }
    }
    /** [cond] carries the ETag both ways: sent as If-None-Match, a 304 resolves UNCHANGED. A 401 goes to login here. */
    function fetchJson(url, cond) {
        var ctl = typeof AbortController === 'function' ? new AbortController() : null;
        var timer, timeoutError;
        // Fetch resolves at the headers, not at the end of the JSON body. Bound
        // the whole operation, even on older WebViews without AbortController.
        var timeout = new Promise(function (resolve, reject) {
            timer = setTimeout(function () {
                timeoutError = new Error('Timeline request timed out');
                timeoutError.offline = true;
                reject(timeoutError);
                if (ctl) ctl.abort();
            }, FETCH_TIMEOUT_MS);
        });
        var headers = authHeaders();
        if (cond && cond.etag) headers['If-None-Match'] = cond.etag;
        var opts = { cache: 'no-store', headers: headers };
        if (ctl) opts.signal = ctl.signal;
        var req;
        try { req = root.fetch(url, opts); } catch (e) { req = Promise.reject(e); }
        var body = Promise.resolve(req).then(function (r) {
            // A late response on a browser without abort support must not
            // redirect to login or process an expired request's headers.
            if (timeoutError) throw timeoutError;
            if (r && r.status === 304 && cond && cond.etag) return UNCHANGED;
            if (!r || !r.ok) {
                var err = new Error('http ' + (r ? r.status : 0));
                err.status = r ? r.status : 0;
                err.offline = !r || r.status >= 500;
                if (err.status === 401) authExpired();
                throw err;
            }
            if (cond) cond.etag = (r.headers && r.headers.get('ETag')) || null;
            return r.json();
        }, function (e) {
            var err = new Error('offline');
            err.offline = true;
            err.cause = e;
            throw err;
        });
        return Promise.race([body, timeout]).then(function (value) {
            clearTimeout(timer);
            return value;
        }, function (err) {
            clearTimeout(timer);
            throw err;
        });
    }

    function Store(onChange) {
        this.days = new Map();
        this.onChange = onChange;
        this.skew = 0;
        this.offline = false;
        this.zoneFromServer = false;
        var cached = null;
        try { cached = JSON.parse(lsGet(LS.tz) || 'null'); } catch (e) { cached = null; }
        this.zoneCached = !!(cached && cached.tz);
        this.zone = this.zoneCached ? new Zone(cached.tz, cached.off) : new Zone(browserTz(), null);
    }
    Store.prototype.now = function () { return Date.now() + this.skew; };
    /** Adopts the daemon zone; true when it differs from the one in use (the Zone object only changes then). */
    Store.prototype.noteServer = function (j) {
        if (!j) return false;
        var now = num(j.now);
        if (now != null) this.skew = now - Date.now();
        if (typeof j.tz !== 'string' || !j.tz) return false;
        var off = num(j.tzOffsetMin), changed = this.zone.tz !== j.tz, first = !this.zoneFromServer;
        if (changed) this.zone = new Zone(j.tz, off);
        else if (off != null) this.zone.off = off;
        this.zoneFromServer = true;
        if (changed || first) lsSet(LS.tz, JSON.stringify({ tz: j.tz, off: this.zone.off }));
        return changed;
    };
    Store.prototype.touch = function (key) {
        var c = this.days.get(key);
        if (c) { this.days.delete(key); this.days.set(key, c); }
        return c;
    };
    Store.prototype.evict = function (keep) {
        var self = this;
        if (this.days.size <= CACHE_DAYS) return;
        var keys = [];
        this.days.forEach(function (c, k) { keys.push(k); });
        for (var i = 0; i < keys.length && self.days.size > CACHE_DAYS; i++) {
            var c = self.days.get(keys[i]);
            if ((keep && keep.indexOf(keys[i]) >= 0) || (c && c.promise)) continue;
            self.days.delete(keys[i]);
        }
    };
    Store.prototype.load = function (key, force, keep) {
        var self = this, c = this.touch(key);
        if (c && c.promise) return c.promise;
        if (c && c.status === 'ok' && !force) return Promise.resolve(c);
        // Failed days retry only on the refresh tick / Retry, or after a pause, never per UI event.
        if (c && c.status === 'error' && !force && Date.now() - (c.errAt || 0) < 10 * SEC) return Promise.resolve(c);
        var zone = this.zone, range = zone.dayRange(key);
        if (!c) { c = { key: key, status: 'loading', data: null, at: 0 }; this.days.set(key, c); }
        c.from = range.from;
        c.to = range.to;
        if (!c.data) c.status = 'loading';
        // A re-read revalidates the copy held: 304 is "unchanged", handled as a re-read that returned the same day.
        var url = '/api/timeline?from=' + range.from + '&to=' + range.to, cond = { etag: c.data && c.etagUrl === url ? c.etag : null };
        c.promise = fetchJson(url, cond).then(function (j) {
            c.promise = null;
            if (zone !== self.zone) return c;
            if (j === UNCHANGED) {
                c.status = 'ok';
                c.at = Date.now();
                self.offline = false;
                self.onChange(key, null, false, true);
                return c;
            }
            c.etag = cond.etag;
            c.etagUrl = url;
            if (self.noteServer(j)) { self.reset(); self.onChange(null, null, true); return c; }
            var data = normalizeWindow(j), same = false;
            if (c.data) {
                // Compared on re-reads only; a first load is always news.
                var prev = c.sig || JSON.stringify(c.data), sig = JSON.stringify(data);
                same = prev === sig;
                c.sig = sig;
            }
            c.data = data;
            c.status = 'ok';
            c.at = Date.now();
            self.offline = false;
            self.onChange(key, null, false, same);
            return c;
        }, function (err) {
            c.promise = null;
            if (zone !== self.zone) return c;
            if (!c.data) c.status = 'error';
            c.err = err;
            c.errAt = Date.now();
            self.offline = true;
            self.onChange(key, err, false);
            return c;
        });
        this.evict(keep);
        return c.promise;
    };
    Store.prototype.reset = function () { this.days = new Map(); };
    Store.prototype.merged = function () {
        var segById = {}, segs = [], marks = [], sessions = {}, trips = {}, states = [], reaped = [], road = [];
        var seen = {}, live = [], liveTo = -Infinity, liveAt = -1, truncated = false;
        function once(prefix, list, keyOf, out) {
            for (var i = 0; i < list.length; i++) {
                var x = list[i];
                if (!x) continue;
                var k = prefix + keyOf(x);
                if (seen[k]) continue;
                seen[k] = true;
                out.push(x);
            }
        }
        var list = [];
        this.days.forEach(function (c) { list.push(c); });
        // Freshest first: a duplicate (e.g. a state seen open by an older read) takes the newest copy.
        list.sort(function (a, b) { return (b.at || 0) - (a.at || 0); });
        list.forEach(function (c) {
            if (!c.data) return;
            var d = c.data;
            for (var i = 0; i < d.segments.length; i++) {
                var s = d.segments[i], prev = segById[s.id];
                if (!prev) { segById[s.id] = s; segs.push(s); } else if (c.at > (prev._at || 0)) { segs[segs.indexOf(prev)] = s; segById[s.id] = s; }
                s._at = c.at;
            }
            once('m', d.marks, function (m) { return m.rec + '|' + m.t0 + '|' + m.kind + '|' + m.cam; }, marks);
            once('st', d.states, function (s) { return s.start + '|' + s.kind + '|' + s.state; }, states);
            once('r', d.reaped, function (r) { return r.start + '|' + r.type + '|' + r.reason; }, reaped);
            once('rd', d.road, function (r) { return r.t + '|' + r.kind; }, road);
            d.sessions.forEach(function (s) { if (s && s.id != null && !sessions[s.id]) sessions[s.id] = s; });
            d.trips.forEach(function (t) { if (t && t.id != null && !trips[t.id]) trips[t.id] = t; });
            // Only the newest day's window lists the recordings still open; an older day read later lists none.
            if (c.to > liveTo || (c.to === liveTo && c.at > liveAt)) { liveTo = c.to; liveAt = c.at; live = d.live; }
            truncated = truncated || d.truncated;
        });
        function byStart(a, b) { return (a.start || 0) - (b.start || 0); }
        function vals(o) { var out = []; for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) out.push(o[k]); return out; }
        segs.sort(byStart);
        marks.sort(function (a, b) { return (a.t0 || 0) - (b.t0 || 0); });
        states.sort(byStart);
        reaped.sort(byStart);
        road.sort(function (a, b) { return (a.t || 0) - (b.t || 0); });
        return {
            segments: segs, marks: marks, sessions: vals(sessions).sort(byStart), trips: vals(trips).sort(byStart),
            states: states, reaped: reaped, road: road, live: live, truncated: truncated
        };
    };

    // ------------------------------------------------------------------ view state

    var V = {
        mode: 'list', applied: false, built: false, active: false, started: false, zoneReady: false,
        zone: null, layout: null, strip: null, stripHost: null, player: null,
        playhead: Date.now(), playing: false, speed: 1, skip: false, cont: true, prefer: null, filter: 'all',
        spans: { land: 6 * HOUR, port: 2 * HOUR }, range: null, minMs: Date.now() - DAYS_BACK * DAY,
        scrubbing: false, wasPlaying: false, touched: false, deep: null, pendingFile: null, pendingDefault: false, pendingDay: null,
        playerWin: null, item: null, itemId: null, spec: null, families: [], activeFamily: null,
        data: null, dataRev: 0, moments: [], momentsById: {}, activeId: null, rowEls: {}, listSig: '', titleSig: '',
        camLayout: null, objSig: '', menu: null, menuAnchor: null, hideTimer: null, uiTimer: null, uiAt: 0, rowsRaf: 0,
        userScrollAt: 0, refreshTimer: null, liveKey: null, daysInfo: null, daysAt: 0, hintTo: null, bannerTimer: null,
        rowDrag: null, suppressClickUntil: 0, fullscreen: false
    };
    var els = {};
    var store = null;
    var thumbs = { cache: new Map(), queue: [], active: 0, io: null };

    var ICON = {
        prev: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="5" width="2.6" height="14" rx="1"/><path d="M19.5 5.2v13.6L9 12z"/></svg>',
        next: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="16.4" y="5" width="2.6" height="14" rx="1"/><path d="M4.5 5.2v13.6L15 12z"/></svg>',
        play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 4.8v14.4L19.4 12z"/></svg>',
        pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6.5" y="5" width="4" height="14" rx="1.2"/><rect x="13.5" y="5" width="4" height="14" rx="1.2"/></svg>',
        cal: '<svg class="tlv-stroke" viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="5" width="17" height="15.5" rx="3"/><path d="M3.5 10h17M8 3v4M16 3v4"/></svg>',
        caret: '<svg class="tlv-caret" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 10l5 5 5-5z"/></svg>',
        filter: '<svg class="tlv-stroke" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M7 12h10M10 17h4"/></svg>',
        list: '<svg class="tlv-stroke" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 7h11M9 12h11M9 17h11"/><path d="M4.5 7h.01M4.5 12h.01M4.5 17h.01" stroke-width="3"/></svg>',
        swap: '<svg class="tlv-stroke" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7h11l-3-3M17 17H6l3 3"/></svg>',
        fullscreen: '<svg class="tlv-stroke tlv-fsico" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>',
        fullscreenExit: '<svg class="tlv-stroke tlv-fsico" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/></svg>',
        share: '<svg class="tlv-stroke" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/><polyline points="16 6 12 2 8 6"/><line x1="12" y1="2" x2="12" y2="15"/></svg>',
        eq: '<span class="tlv-eq" aria-hidden="true"><i></i><i></i><i></i></span>'
    };
    var KIND_ICON = {
        person: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="6.5" r="3.6"/><path d="M4.8 21c0-4.4 3.2-7.6 7.2-7.6s7.2 3.2 7.2 7.6z"/></svg>',
        vehicle: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3.5 16.5v-3.6l2-5.2A2.2 2.2 0 0 1 7.6 6.3h8.8a2.2 2.2 0 0 1 2.1 1.4l2 5.2v3.6a1 1 0 0 1-1 1h-1.2a2.3 2.3 0 0 1-4.6 0H10.3a2.3 2.3 0 0 1-4.6 0H4.5a1 1 0 0 1-1-1zM6.6 11.6h10.8l-1.3-3.3H7.9z"/></svg>',
        bike: '<svg class="tlv-stroke" viewBox="0 0 24 24" aria-hidden="true"><circle cx="5.8" cy="16" r="3.6"/><circle cx="18.2" cy="16" r="3.6"/><path d="M5.8 16l4.2-7.5h5.2l3 7.5M10 8.5l3.4 7.5M13.6 5.5h2.6"/></svg>',
        animal: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="6" cy="10" r="2.3"/><circle cx="10" cy="6" r="2.3"/><circle cx="14.6" cy="6" r="2.3"/><circle cx="18.6" cy="10" r="2.3"/><path d="M12.3 11.2c3.2 0 5.9 3.8 5.9 6.3 0 1.9-1.7 2.8-3.2 2.3-1.1-.3-1.8-.7-2.7-.7s-1.6.4-2.7.7c-1.5.5-3.2-.4-3.2-2.3 0-2.5 2.7-6.3 5.9-6.3z"/></svg>'
    };

    function P() { return V.player; }
    function call(o, m) {
        if (!o || typeof o[m] !== 'function') return undefined;
        try { return o[m].apply(o, Array.prototype.slice.call(arguments, 2)); } catch (e) {
            if (root.console) root.console.error('[tlview] ' + m, e);
            return undefined;
        }
    }
    function nowMs() { return store ? store.now() : Date.now(); }
    function clock24() { try { return BYD.clock && BYD.clock.is24h ? BYD.clock.is24h() : null; } catch (e) { return null; } }
    function reducedMotion() { try { return !!(root.matchMedia && root.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (e) { return false; } }
    function isPortrait() { try { return !!root.matchMedia('(max-width: 700px) and (orientation: portrait)').matches; } catch (e) { return false; } }
    function isShort() { try { return !!root.matchMedia('(orientation: landscape) and (max-height: 500px)').matches; } catch (e) { return false; } }
    var vhUnit = null;
    /** Dynamic viewport units follow a browser's collapsing toolbars (svh left a blank band under a collapsed bar). Pixels
     *  where there are none: Android WebView and installed apps resolve the units stale after rotation (see events.html). */
    function viewportUnit() {
        if (vhUnit === null) {
            vhUnit = '';
            try {
                var app = /; wv\)/.test(root.navigator.userAgent || '') || doc.documentElement.getAttribute('data-android-embed') === '1' ||
                    !!(root.matchMedia && root.matchMedia('(display-mode: standalone), (display-mode: fullscreen)').matches) || root.navigator.standalone === true;
                if (!app && root.CSS && root.CSS.supports) vhUnit = root.CSS.supports('height', '100dvh') ? 'dvh' : root.CSS.supports('height', '100svh') ? 'svh' : '';
            } catch (e) { vhUnit = ''; }
        }
        return vhUnit;
    }

    var timeFmtCache = {};
    function fmtTime(ms, withSec) {
        var z = V.zone, h24 = clock24(), p;
        if (z && z.f && h24 !== true) {
            var key = lang() + '|' + z.tz + '|' + (withSec ? 1 : 0) + '|' + h24;
            var f = timeFmtCache[key];
            if (f === undefined) {
                try {
                    var o = { timeZone: z.tz, hour: '2-digit', minute: '2-digit' };
                    if (withSec) o.second = '2-digit';
                    if (h24 === false) o.hour12 = true;
                    f = new Intl.DateTimeFormat(lang(), o);
                } catch (e) { f = null; }
                timeFmtCache[key] = f;
            }
            if (f) return f.format(new Date(ms)).replace(/^24(?=\D)/, '00');
        }
        p = (z || new Zone(browserTz(), null)).parts(ms);
        return pad2(p.h) + ':' + pad2(p.mi) + (withSec ? ':' + pad2(p.s) : '');
    }
    function dayLabel(key) {
        var today = V.zone.dayKey(nowMs()), k = parseKey(key), s;
        var opts = { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' };
        if (k.y !== parseKey(today).y) opts.year = 'numeric';
        try { s = new Intl.DateTimeFormat(lang(), opts).format(new Date(Date.UTC(k.y, k.mo - 1, k.d, 12))); } catch (e) { s = key; }
        var rel = key === today ? tr('today') : (key === addDaysKey(today, -1) ? tr('yesterday') : null);
        return { rel: rel, date: s };
    }
    function weekdayShort(key) {
        var k = parseKey(key);
        try { return new Intl.DateTimeFormat(lang(), { weekday: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(k.y, k.mo - 1, k.d, 12))); } catch (e) { return ''; }
    }

    // ------------------------------------------------------------------ mode switch

    function readMode() { return lsGet(LS.mode) === 'timeline' ? 'timeline' : 'list'; }
    function cleanUrl(keys) {
        try {
            var u = new URL(root.location.href), changed = false;
            keys.forEach(function (k) { if (u.searchParams.has(k)) { u.searchParams.delete(k); changed = true; } });
            if (changed) root.history.replaceState(root.history.state, '', u.toString());
        } catch (e) { /* old engines: leave the URL */ }
    }
    /** Session expired (401): to login (auth.js's flow), returning to this view at the playhead. Once. */
    function authExpired() {
        if (V.authGone) return;
        V.authGone = true;
        var back = root.location.pathname + root.location.search;
        try {
            if (V.mode === 'timeline') {
                var u = new URL(root.location.href);
                u.searchParams.delete('file');
                u.searchParams.delete('end');
                u.searchParams.set('view', 'timeline');
                if (V.started && V.zoneReady) u.searchParams.set('t', String(Math.round(V.playhead)));
                back = u.pathname + u.search;
            }
        } catch (e) { /* old engines: the page as it is */ }
        stashResume();
        root.location.href = '/login.html?redirect=' + encodeURIComponent(back);
    }
    // Other 401 redirects (the shell's /status poll usually sees expiry first) keep the bare URL: the playhead rides in the tab.
    function stashResume() {
        try {
            if (V.active && V.started && V.zoneReady) root.sessionStorage.setItem(RESUME_KEY, JSON.stringify({ t: Math.round(V.playhead), at: Date.now() }));
            else root.sessionStorage.removeItem(RESUME_KEY);
        } catch (e) { /* storage blocked */ }
    }
    /** The stashed playhead when this load is the return from login (one-shot: read once, then dropped). */
    function takeResume() {
        var s = null, ref = null, nav = null;
        try { s = JSON.parse(root.sessionStorage.getItem(RESUME_KEY) || 'null'); root.sessionStorage.removeItem(RESUME_KEY); } catch (e) { s = null; }
        try { ref = doc.referrer ? new URL(doc.referrer) : null; } catch (e) { ref = null; }
        // A reload or Back keeps the login referrer too: only the navigation login made counts.
        try { nav = root.performance.getEntriesByType('navigation')[0].type; } catch (e) { nav = null; }
        var fromLogin = !!ref && ref.origin === root.location.origin && /^\/login(\.html)?$/.test(ref.pathname) && (nav == null || nav === 'navigate');
        return fromLogin && s && num(s.t) != null && Date.now() - (num(s.at) || 0) < HOUR ? num(s.t) : null;
    }
    function setMode(mode, persist) {
        mode = mode === 'timeline' ? 'timeline' : 'list';
        if (persist !== false) lsSet(LS.mode, mode);
        if (mode === V.mode && V.applied) return;
        V.mode = mode;
        V.applied = true;
        paintToggle();
        if (mode === 'timeline') enter(); else leave();
    }
    function paintToggle() {
        var btns = doc.querySelectorAll('[data-tlv-mode]');
        for (var i = 0; i < btns.length; i++) {
            var on = btns[i].getAttribute('data-tlv-mode') === V.mode;
            btns[i].setAttribute('aria-pressed', on ? 'true' : 'false');
            btns[i].classList.toggle('tlv-on', on);
        }
    }
    function enter() {
        doc.documentElement.classList.add('tlv-on');
        if (!els.root) return;
        els.root.hidden = false;
        V.active = true;
        if (!V.built) build();
        applyLayout(true);
        if (!V.started) start();
        else {
            call(V.strip, 'resize');
            startTimers();
            tick(true);
        }
    }
    function leave() {
        doc.documentElement.classList.remove('tlv-on', 'tlv-port-on', 'tlv-short-on');
        V.active = false;
        if (els.root) els.root.hidden = true;
        closeMenus();
        stopTimers();
        if (V.player) call(V.player, P() && P().suspend ? 'suspend' : 'pause');
        // Leaving cancels a pending "plays once saved" (as hiding the page does).
        V.waitTail = null;
        setPlaying(false);
        cleanUrl(['view', 't', 'end']);
    }

    // ------------------------------------------------------------------ DOM

    function build() {
        V.built = true;
        els.root.innerHTML =
            '<div class="tlv-appbar">' +
                '<button type="button" class="tlv-abdate" data-tlv="date" aria-haspopup="dialog"><span class="tlv-abday"></span><span class="tlv-absub"></span>' + ICON.caret + '</button>' +
                '<button type="button" class="tlv-ib" data-tlv="filtermenu" aria-haspopup="dialog">' + ICON.filter + '<i class="tlv-fdot" hidden></i></button>' +
                '<button type="button" class="tlv-ib" data-tlv="tolist">' + ICON.list + '</button>' +
            '</div>' +
            '<div class="tlv-stage">' +
                '<div class="tlv-player">' +
                    '<div class="tlv-video"></div>' +
                    '<div class="tlv-cams" aria-hidden="true"></div>' +
                    '<div class="tlv-obj" hidden></div>' +
                    '<button type="button" class="tlv-src" data-tlv="source" hidden></button>' +
                    '<div class="tlv-state" hidden></div>' +
                    '<div class="tlv-banner" role="status" hidden></div>' +
                    '<div class="tlv-gapcard" role="status" hidden></div>' +
                    '<button type="button" class="tlv-nexthint" data-tlv="nexthint" hidden></button>' +
                    '<div class="tlv-ctl">' +
                        '<button type="button" class="tlv-b2" data-tlv="prev">' + ICON.prev + '</button>' +
                        '<button type="button" class="tlv-b2 tlv-pp" data-tlv="play">' + ICON.play + '</button>' +
                        '<button type="button" class="tlv-b2" data-tlv="next">' + ICON.next + '</button>' +
                        '<button type="button" class="tlv-b2" data-tlv="fullscreen">' + ICON.fullscreen + '</button>' +
                        '<button type="button" class="tlv-b2" data-tlv="share">' + ICON.share + '</button>' +
                        '<button type="button" class="tlv-spd" data-tlv="speed" aria-haspopup="dialog" aria-expanded="false">1×</button>' +
                    '</div>' +
                    '<div class="tlv-spdpop tlv-menu" role="dialog" tabindex="-1" hidden></div>' +
                '</div>' +
                '<div class="tlv-panel">' +
                    '<div class="tlv-tb"><div class="tlv-chips" role="group"></div><div class="tlv-sp"></div><div class="tlv-zoom" role="group"></div></div>' +
                    '<div class="tlv-striphost"></div>' +
                '</div>' +
                '<section class="tlv-list">' +
                    '<div class="tlv-lh"><h2 class="tlv-lt"></h2><span class="tlv-lc" aria-hidden="true"></span></div>' +
                    '<div class="tlv-rows" role="list"></div>' +
                '</section>' +
                '<div class="tlv-vfeed"><div class="tlv-vhost"></div><div class="tlv-vrows" role="list"></div></div>' +
            '</div>';
        function q(sel) { return els.root.querySelector(sel); }
        els.abDay = q('.tlv-abday'); els.abSub = q('.tlv-absub'); els.abDate = q('.tlv-abdate'); els.fdot = q('.tlv-fdot');
        els.filterBtn = q('[data-tlv="filtermenu"]'); els.listBtn = q('[data-tlv="tolist"]');
        els.stage = q('.tlv-stage'); els.player = q('.tlv-player'); els.video = q('.tlv-video'); els.cams = q('.tlv-cams');
        els.obj = q('.tlv-obj'); els.src = q('.tlv-src'); els.state = q('.tlv-state'); els.banner = q('.tlv-banner');
        els.gapCard = q('.tlv-gapcard');
        els.hint = q('.tlv-nexthint'); els.ctl = q('.tlv-ctl'); els.prev = q('[data-tlv="prev"]'); els.play = q('[data-tlv="play"]');
        els.next = q('[data-tlv="next"]'); els.fs = q('[data-tlv="fullscreen"]'); els.share = q('[data-tlv="share"]');
        els.spd = q('[data-tlv="speed"]'); els.spdpop = q('.tlv-spdpop');
        doc.addEventListener('fullscreenchange', onFsChange);
        doc.addEventListener('webkitfullscreenchange', onFsChange);
        doc.addEventListener('fullscreenerror', onFsError);
        doc.addEventListener('webkitfullscreenerror', onFsError);
        els.panel = q('.tlv-panel'); els.chips = q('.tlv-chips'); els.zoom = q('.tlv-zoom'); els.striphost = q('.tlv-striphost');
        els.list = q('.tlv-list'); els.lt = q('.tlv-lt'); els.lc = q('.tlv-lc'); els.rows = q('.tlv-rows');
        els.vfeed = q('.tlv-vfeed'); els.vhost = q('.tlv-vhost'); els.vrows = q('.tlv-vrows');

        els.scrim = mk('div', 'tlv-scrim');
        els.scrim.setAttribute('data-tlv', 'scrim');
        els.sheet = mk('div', 'tlv-sheet tlv-menu');
        els.datepop = mk('div', 'tlv-pop tlv-datepop tlv-menu');
        els.filterpop = mk('div', 'tlv-pop tlv-filterpop tlv-menu');
        [els.sheet, els.datepop, els.filterpop].forEach(function (m) { m.setAttribute('role', 'dialog'); m.tabIndex = -1; });
        [els.scrim, els.sheet, els.datepop, els.filterpop].forEach(function (m) {
            m.hidden = true;
            doc.body.appendChild(m);
            m.addEventListener('click', onClick);
        });
        els.datepop.addEventListener('change', onDateInput);

        els.root.addEventListener('click', onClick);
        els.player.addEventListener('pointermove', function (e) { if (e.pointerType !== 'touch') showControls(); }, { passive: true });
        els.player.addEventListener('pointerdown', function () { showControls(); }, { passive: true });
        els.ctl.addEventListener('focusin', function () { showControls(); });
        els.video.addEventListener('loadedmetadata', layoutOverlays, true);
        els.video.addEventListener('resize', layoutOverlays, true);
        ['wheel', 'touchmove', 'pointerdown', 'keydown'].forEach(function (ev) {
            els.rows.addEventListener(ev, function () { V.userScrollAt = Date.now(); }, { passive: true });
        });
        wireRowDrag();
        renderTexts();
    }
    function mk(tag, cls) { var e = doc.createElement(tag); e.className = cls; return e; }

    /** Date pill + Live link appended to the Recordings toolbar; shown only in Timeline mode. */
    function buildBarEnd() {
        var bar = doc.querySelector('.events-top-bar');
        if (!bar || els.barEnd) return;
        var end = mk('div', 'tlv-barend');
        end.innerHTML = '<button type="button" class="tlv-datepill" data-tlv="date" aria-haspopup="dialog">' + ICON.cal +
            '<span class="tlv-dptext"></span>' + ICON.caret + '</button>' +
            '<a class="tlv-live" href="live-view.html"><span class="tlv-dot" aria-hidden="true"></span><span class="tlv-livet"></span></a>';
        bar.appendChild(end);
        end.addEventListener('click', onClick);
        els.barEnd = end;
        els.datePill = end.querySelector('.tlv-datepill');
        els.dpText = end.querySelector('.tlv-dptext');
        els.liveText = end.querySelector('.tlv-livet');
        els.liveText.textContent = tr('live');
    }

    function renderTexts() {
        if (els.liveText) els.liveText.textContent = tr('live');
        if (els.datePill) els.datePill.setAttribute('aria-label', tr('ariaDate'));
        if (!V.built) return;
        els.abDate.setAttribute('aria-label', tr('ariaDate'));
        els.filterBtn.setAttribute('aria-label', tr('ariaFilter'));
        els.listBtn.setAttribute('aria-label', tr('ariaListView'));
        els.prev.setAttribute('aria-label', tr('ariaPrev'));
        els.next.setAttribute('aria-label', tr('ariaNext'));
        if (els.fs) {
            els.fs.setAttribute('aria-label', V.fullscreen ? tr('exitFullscreen') : tr('fullscreen'));
            els.fs.setAttribute('aria-pressed', V.fullscreen ? 'true' : 'false');
        }
        if (els.share) els.share.setAttribute('aria-label', tr('share'));
        els.lt.textContent = tr('moments');
        els.rows.setAttribute('aria-label', tr('moments'));
        els.vrows.setAttribute('aria-label', tr('moments'));
        els.chips.setAttribute('aria-label', tr('ariaFilter'));
        renderChips();
        renderZoom();
        renderSpeed();
        setPlaying(V.playing);
        V.titleSig = '';
        V.listSig = '';
        V.camSig = '';
        if (V.zoneReady) layoutOverlays();
    }
    function renderChips() {
        els.chips.innerHTML = FILTER_ORDER.map(function (k) {
            var on = k === V.filter;
            return '<button type="button" class="tlv-chip' + (on ? ' tlv-on' : '') + '" data-tlv="filter" data-v="' + k + '" aria-pressed="' + on + '">' + esc(tr(FILTER_KEY[k])) + '</button>';
        }).join('');
        els.fdot.hidden = V.filter === 'all';
    }
    function renderZoom() {
        if (!els.zoom) return;
        var span = V.spans[V.layout || 'land'];
        els.zoom.innerHTML = ZOOMS.map(function (z) {
            var on = Math.abs(span - z[1]) <= z[1] * 0.02;
            return '<button type="button" class="tlv-zb' + (on ? ' tlv-on' : '') + '" data-tlv="zoom" data-v="' + z[1] + '" aria-pressed="' + on + '">' + esc(tr(z[0])) + '</button>';
        }).join('');
    }
    function renderSpeed() {
        if (!els.spd) return;
        els.spd.textContent = V.skip ? tr('speedAuto') : V.speed + '×';
        els.spd.classList.toggle('tlv-on', V.speed !== 1 || V.skip);
        els.spd.setAttribute('aria-label', tr('ariaSpeed', { speed: V.speed + '×' }));
        [els.spdpop, els.sheet].forEach(function (m) {
            if (!m || m.hidden) return;
            var b = m.querySelectorAll('[data-tlv="setspeed"]');
            for (var i = 0; i < b.length; i++) {
                var on = +b[i].getAttribute('data-v') === V.speed;
                b[i].classList.toggle('tlv-on', on);
                b[i].setAttribute('aria-checked', on ? 'true' : 'false');
            }
            var t = m.querySelectorAll('[data-tlv="toggle"]');
            for (var j = 0; j < t.length; j++) t[j].setAttribute('aria-checked', (t[j].getAttribute('data-v') === 'skip' ? V.skip : V.cont) ? 'true' : 'false');
        });
    }
    function speedMenuHtml(sheet) {
        var h = (sheet ? '<div class="tlv-grab" aria-hidden="true"></div>' : '') +
            '<h4 class="tlv-mh">' + esc(tr('speedTitle')) + '</h4>' +
            '<div class="tlv-spdseg" role="radiogroup" aria-label="' + esc(tr('speedTitle')) + '">';
        SPEEDS.forEach(function (s) {
            var on = s === V.speed;
            h += '<button type="button" role="radio" data-tlv="setspeed" data-v="' + s + '" aria-checked="' + on + '" class="' + (on ? 'tlv-on' : '') + '">' + s + '×</button>';
        });
        return h + '</div><div class="tlv-spdnote">' + esc(tr('speedNote')) + '</div>' +
            toggleHtml('skip', tr('skipQuiet'), tr('skipQuietDesc'), V.skip) +
            toggleHtml('cont', tr('continueNext'), tr('continueNextDesc'), V.cont);
    }
    function toggleHtml(id, title, desc, on) {
        return '<button type="button" class="tlv-tgl" role="switch" data-tlv="toggle" data-v="' + id + '" aria-checked="' + !!on + '">' +
            '<span class="tlv-tx"><b>' + esc(title) + '</b><small>' + esc(desc) + '</small></span><span class="tlv-sw" aria-hidden="true"></span></button>';
    }

    // ------------------------------------------------------------------ layout

    function applyLayout(force) {
        if (!V.active || !V.built) return;
        var lay = isPortrait() ? 'port' : 'land';
        // A phone on its side: the compact landscape (events.html folds the page chrome on html.tlv-short-on).
        V.short = lay === 'land' && isShort();
        els.root.classList.toggle('tlv-short', V.short);
        doc.documentElement.classList.toggle('tlv-short-on', V.short);
        if (lay !== V.layout || force) {
            var prev = V.layout;
            V.layout = lay;
            els.root.classList.toggle('tlv-port', lay === 'port');
            els.root.classList.toggle('tlv-land', lay !== 'port');
            doc.documentElement.classList.toggle('tlv-port-on', lay === 'port');
            if (prev && prev !== lay) {
                closeMenus();
                if (V.strip) createStrip();
                V.listSig = '';
                clearVRows();
            }
            renderZoom();
        }
        sizeLayout();
    }
    // Full-bleed: both margins from the parent's measured edges, so page padding, safe areas and RTL never leave a gap.
    function bleed(el) {
        var vw = doc.documentElement.clientWidth || root.innerWidth || 390;
        if (!el || !el.parentNode) return vw;
        var pr = el.parentNode.getBoundingClientRect();
        el.style.width = vw + 'px';
        el.style.maxWidth = 'none';
        el.style.marginLeft = (-Math.round(pr.left)) + 'px';
        el.style.marginRight = (-Math.round(vw - pr.right)) + 'px';
        return vw;
    }
    function unbleed(el) {
        if (!el) return;
        el.style.width = '';
        el.style.maxWidth = '';
        el.style.marginLeft = '';
        el.style.marginRight = '';
    }
    function sizeLayout() {
        if (!V.active) return;
        var vh = root.innerHeight || 800;
        var nav = doc.querySelector('.app-bottom-nav'), navH = 0;
        if (nav) { var nr = nav.getBoundingClientRect(); if (nr.height && root.getComputedStyle(nav).display !== 'none') navH = nr.height; }
        var top = els.root.getBoundingClientRect().top + (root.pageYOffset || 0);
        var off = top + navH + (V.short ? 8 : 16), avail;
        if (V.layout === 'port') {
            els.root.classList.remove('tlv-narrow');
            avail = rootHeight(off, 360, !navH);
            var w = bleed(els.player);
            bleed(els.vfeed);
            els.player.style.height = Math.round(Math.min(w * 3 / 4, avail * 0.5)) + 'px';
        } else {
            unbleed(els.player);
            unbleed(els.vfeed);
            // Narrow or portrait-shaped windows stack player, strip and Moments instead of side by side (never when short).
            var stageW = els.stage.clientWidth || 800, narrow = !V.short && (stageW < 640 || vh > (root.innerWidth || 0) * 1.1);
            els.root.classList.toggle('tlv-narrow', narrow);
            if (narrow) {
                avail = rootHeight(off, 560, !navH);
                var nh = Math.round(Math.min(stageW * 3 / 4, Math.max(200, avail * 0.5)));
                els.player.style.width = Math.round(nh * 4 / 3) + 'px';
                els.player.style.height = nh + 'px';
            } else {
                // Short: the player takes what the compact panel leaves, so the page fits the screen.
                avail = rootHeight(off, V.short ? 232 : 420, !navH);
                var panelH = els.panel.offsetHeight || 136;
                var ph = Math.max(V.short ? 120 : 220, avail - panelH - (V.short ? 8 : 12));
                var pw = Math.min(Math.round(stageW * 0.58), Math.round(ph * 4 / 3));
                els.player.style.width = pw + 'px';
                els.player.style.height = Math.round(pw * 3 / 4) + 'px';
            }
        }
        call(V.strip, 'resize');
        layoutOverlays();
        if (V.menu === 'speed' && !els.spdpop.hidden) positionSpeedPop();
        scheduleRows();
    }
    /** Root height: the viewport less [off] (page above, bottom bar, margin), at least [floor]; dvh where it holds, else innerHeight. */
    function rootHeight(off, floor, inset) {
        var st = els.root.style, u = viewportUnit();
        st.minHeight = floor + 'px';
        if (u) st.height = 'calc(100' + u + ' - ' + Math.round(off) + 'px' + (inset ? ' - env(safe-area-inset-bottom, 0px))' : ')');
        else st.height = Math.max(floor, Math.round((root.innerHeight || 800) - off)) + 'px';
        return els.root.clientHeight || floor;
    }

    // ------------------------------------------------------------------ start / timers

    function start() {
        V.started = true;
        var done = false;
        var go = function () {
            if (done || !store) return;
            done = true;
            V.zoneReady = true;
            V.zone = store.zone;
            initTarget();
            createStrip();
            createPlayer();
            reloadPlayer(V.playhead, !!(V.deep && V.deep.t != null) && !V.pendingFile);
            refreshDays(true);
            ensureData();
            if (V.active) startTimers();
            scheduleUi(true);
        };
        if (store.zoneCached) { go(); return; }
        var to = setTimeout(go, 2500);
        var z = store.zone, now = Date.now();
        fetchJson('/api/timeline/days?from=' + (now - 2 * DAY) + '&to=' + (now + DAY)).then(function (j) {
            store.noteServer(j);
            if (z !== store.zone && done) onZoneChanged();
            clearTimeout(to);
            go();
        }, function () { clearTimeout(to); store.offline = true; go(); });
    }
    function initTarget() {
        var now = nowMs(), d = V.deep;
        if (d && d.t != null) {
            V.playhead = clamp(d.t, now - 400 * DAY, now);
            if (d.end != null && d.end > d.t) V.spans.land = V.spans.port = clamp((d.end - d.t) * 2.2, 10 * MIN, DAY);
        } else if (d && d.file) {
            V.playhead = clamp(V.zone.toEpoch(d.file.y, d.file.mo, d.file.d, d.file.h, d.file.mi, d.file.s), now - 400 * DAY, now);
            V.pendingFile = d.file;
            V.spans.land = HOUR;
            V.spans.port = HOUR / 2;
        } else {
            V.playhead = now - MIN;
            V.pendingDefault = true;
        }
        V.minMs = Math.min(V.minMs, V.playhead - HOUR);
    }
    function startTimers() {
        stopTimers();
        V.refreshTimer = setInterval(tick, REFRESH_MS);
    }
    function stopTimers() {
        if (V.refreshTimer) clearInterval(V.refreshTimer);
        V.refreshTimer = null;
    }
    /** Live-day refresh; the previous day reloads once more when midnight passes. */
    function tick(all) {
        if (!V.active || !V.zoneReady || (doc.visibilityState && doc.visibilityState === 'hidden')) return;
        if (V.waitTail != null && Date.now() - (V.waitTailAt || 0) > 20 * MIN) { V.waitTail = null; setPlaying(false); }
        var today = V.zone.dayKey(nowMs()), keys = visibleKeys(), t = Date.now();
        // Past days change rarely (a card pulled or re-inserted, the index still warming): re-read every 5 min (aged from the
        // last attempt, so offline doesn't retry them every tick) and on return.
        if (keys.indexOf(today) >= 0) store.load(today, true, keys);
        if (V.liveKey && V.liveKey !== today) store.load(V.liveKey, true, keys);
        V.liveKey = today;
        keys.forEach(function (k) {
            var c = store.days.get(k), due = !c || t - Math.max(c.at || 0, c.errAt || 0) >= PAST_REFRESH_MS;
            if (k <= today && (all === true || due || c.status === 'error' || (c.data && c.data.warming))) store.load(k, true, keys);
        });
        // The day list too (which days have footage), at most every 5 min.
        refreshDays(false);
        pushStripData();
        scheduleUi(true);
    }
    /** Days under the strip; a range that no longer contains the playhead (after a jump) is ignored. */
    function visibleKeys() {
        var span = V.spans[V.layout || 'land'], r = V.range;
        if (!r || V.playhead < r.from - span || V.playhead > r.to + span) r = { from: V.playhead - span / 2, to: V.playhead + span / 2 };
        return V.zone.daysIn(r.from, Math.max(r.to, r.from + 1));
    }
    function ensureData() {
        if (!V.zoneReady) return;
        var keys = visibleKeys(), today = V.zone.dayKey(nowMs());
        var want = [addDaysKey(keys[0], -1)].concat(keys, [addDaysKey(keys[keys.length - 1], 1)]);
        // Playing on past the neighbour must reach the next day with footage, not a later day still cached.
        var nf = nextFootageKey(addDaysKey(keys[keys.length - 1], 1), today);
        if (nf && want.indexOf(nf) < 0) want.push(nf);
        want.forEach(function (k) {
            if (k > today) return;
            // Back on a today read a while ago: its live band and newest clips are a refresh old.
            // Aged from the last attempt, failed ones too: offline, a re-read per strip move would storm.
            var c = store.days.get(k), old = k === today && c && c.status === 'ok' && Date.now() - Math.max(c.at || 0, c.errAt || 0) > REFRESH_MS;
            store.load(k, !!old, want);
        });
    }
    /** First day from [k0] up to [last] the day list says has footage. */
    function nextFootageKey(k0, last) {
        if (!V.daysInfo) return null;
        var best = null;
        for (var k in V.daysInfo) {
            if (!Object.prototype.hasOwnProperty.call(V.daysInfo, k)) continue;
            var d = V.daysInfo[k];
            if (k >= k0 && k <= last && num(d.footageMs) > 0 && (best == null || k < best)) best = k;
        }
        return best;
    }
    function refreshDays(force) {
        if (!V.zoneReady || (!force && V.daysTried && Date.now() - V.daysTried < 5 * MIN)) return Promise.resolve(V.daysInfo);
        V.daysTried = Date.now();
        var z = V.zone, today = z.dayKey(nowMs());
        var from = z.dayRange(addDaysKey(today, -(DAYS_BACK - 1))).from, to = z.dayRange(today).to;
        var url = '/api/timeline/days?from=' + from + '&to=' + to, cond = { etag: V.daysInfo && V.daysUrl === url ? V.daysEtag : null };
        return fetchJson(url, cond).then(function (j) {
            if (j === UNCHANGED) { V.daysAt = Date.now(); return V.daysInfo; }
            V.daysUrl = url;
            V.daysEtag = cond.etag;
            if (store.noteServer(j)) { store.reset(); onZoneChanged(); }
            var map = {}, keys = [];
            (Array.isArray(j.days) ? j.days : []).forEach(function (d) {
                if (!d || !parseKey(d.day)) return;
                map[d.day] = d;
                if (num(d.footageMs) > 0 || num(d.events) > 0) keys.push(d.day);
            });
            keys.sort();
            V.daysInfo = map;
            V.daysAt = Date.now();
            ensureData();
            V.minMs = keys.length ? Math.min(V.zone.dayRange(keys[0]).from, V.playhead - HOUR) : Math.min(V.minMs, from);
            if (V.menu === 'date') paintDays();
            pushStripData();
            return map;
        }, function () { return V.daysInfo; });
    }
    function onZoneChanged() {
        V.zone = store.zone;
        // A file= link not yet resolved names a car-local time: re-read it in the car's zone.
        var f = V.pendingFile;
        if (f && !V.touched) V.playhead = clamp(V.zone.toEpoch(f.y, f.mo, f.d, f.h, f.mi, f.s), nowMs() - 400 * DAY, nowMs());
        timeFmtCache = {};
        V.titleSig = '';
        V.listSig = '';
        V.playerWin = null;
        if (V.strip) createStrip();
        ensureData();
        scheduleUi(true);
    }
    function retry() {
        store.offline = false;
        visibleKeys().forEach(function (k) { store.load(k, true); });
        refreshDays(true);
        updateStates();
    }

    // ------------------------------------------------------------------ data -> UI

    function onStoreChange(key, err, zoneChanged, same) {
        if (zoneChanged) { onZoneChanged(); return; }
        if (err) {
            if (key === V.pendingDay) V.pendingDay = null;
            scheduleUi(true);
            return;
        }
        // First, so a gap card re-read below that seeks reloads a player that lacks the new clip.
        if (V.playerWin && key === V.playerWin.key) checkStale();
        // A re-read that returned the same day changes nothing on screen (no list rebuild, no scroll jump).
        if (!same || !V.data) {
            V.dataRev++;
            V.data = store.merged();
            V.moments = buildMoments(V.data.segments, V.data.marks);
            V.momentsById = {};
            V.moments.forEach(function (m) {
                m.day = V.zone.dayKey(m.t);
                m.label = momentKind(m);
                V.momentsById[m.id] = m;
            });
            pushStripData();
        }
        // After the staleness check, so a follow lands in a player that knows the new clip.
        if (!same) followTail();
        if (V.pendingFile) resolveFile();
        if (V.pendingDefault) resolveDefault(key);
        if (V.pendingDay && key === V.pendingDay) { var d = V.pendingDay; V.pendingDay = null; seekTo(bestTargetInDay(d), {}); }
        scheduleRows();
        scheduleUi(true);
    }
    function pushStripData() {
        // A gap card raised before its day's clips arrived (or whose cause has gone) is re-read from the real data.
        if (V.gapArgs && V.data && els.gapCard && !els.gapCard.hidden) {
            var ga = V.gapArgs, sv = V.gapSliverId != null ? segById(V.gapSliverId) : null;
            // The sliver the card named is analysed now: the tap was on it (within its touch slop), so land on it.
            if (sv && sv.available !== false && sv.end != null && sv.end > sv.start && (ga.at < sv.start || ga.at >= sv.end)) {
                // While Play waits there it plays from its start; otherwise the playhead lands on it.
                var waiting = V.waitTail != null;
                hideHint();
                seekTo(sv.start, waiting ? { play: true } : {});
            }
            else if (footageAt(ga.at)) { hideHint(); if (V.waitTail == null) seekTo(ga.at, {}); }
            else showGapCard(ga.at, ga.next);
        }
        if (!V.strip || !V.data) return;
        var d = V.data, now = nowMs();
        call(V.strip, 'setData', {
            segments: d.segments, marks: d.marks, sessions: d.sessions, trips: d.trips, states: d.states, reaped: d.reaped,
            road: d.road, live: d.live, now: now, minMs: Math.min(V.minMs, now - HOUR), maxMs: now,
            moments: V.moments.map(function (m) { return { t: m.t, rec: m.id, label: m.label }; })
        });
    }
    function resolveFile() {
        var f = V.pendingFile, key = V.zone.dayKey(V.playhead), c = store.days.get(key);
        if (!c || c.status === 'loading') return;
        V.pendingFile = null;
        var wall = V.zone.toEpoch(f.y, f.mo, f.d, f.h, f.mi, f.s);
        var seg = c.data ? matchFileSegment(f.type, wall, c.data.segments) : null;
        if (!V.touched) seekTo(seg ? seg.start : wall, { play: true });
    }
    function resolveDefault(key) {
        var today = V.zone.dayKey(nowMs());
        if (key !== today) return;
        V.pendingDefault = false;
        if (!V.touched) seekTo(bestTargetInDay(today), {});
    }
    function bestTargetInDay(key) {
        var r = V.zone.dayRange(key), now = nowMs(), i, best = null;
        for (i = 0; i < V.moments.length; i++) {
            var m = V.moments[i];
            if (m.day === key && momentPasses(m, V.filter)) return m.start;
        }
        var segs = V.data ? V.data.segments : [];
        for (i = 0; i < segs.length; i++) {
            var s = segs[i];
            if (s.available !== false && s.end != null && s.start < r.to && s.end > r.from && (!best || s.end > best.end)) best = s;
        }
        if (best) return Math.max(best.start, Math.min(best.end, r.to) - 30 * SEC);
        return key === V.zone.dayKey(now) ? now - MIN : Math.min(r.from + 12 * HOUR, now);
    }
    /** The window's playable clips (id -> start|end): what the player's own clip table was built from. */
    function playableSig(segs, from, to) {
        var m = {};
        segs.forEach(function (s) {
            if (s && s.available !== false && s.end != null && s.end > s.start && s.start < to && s.end > from) m[s.id] = s.start + '|' + s.end;
        });
        return m;
    }
    function checkStale() {
        var w = V.playerWin, c = store.days.get(w.key);
        if (!c || !c.data) return;
        var cur = playableSig(c.data.segments, w.from, w.to), lo = Infinity, hi = -Infinity, k;
        if (!w.segs) { w.segs = cur; return; }
        // Any clip added, changed or gone (a new clip, one analysed mid-day, a card re-inserted): where it starts.
        for (k in cur) if (Object.prototype.hasOwnProperty.call(cur, k) && w.segs[k] !== cur[k]) { var ab = cur[k].split('|'), a = num(ab[0]); lo = Math.min(lo, a); hi = Math.max(hi, num(ab[1])); }
        for (k in w.segs) if (Object.prototype.hasOwnProperty.call(w.segs, k) && !Object.prototype.hasOwnProperty.call(cur, k)) { var bb = w.segs[k].split('|'), b = num(bb[0]); lo = Math.min(lo, b); hi = Math.max(hi, num(bb[1])); }
        if (lo === Infinity) return;
        w.stale = true;
        w.staleFrom = Math.min(w.staleFrom != null ? w.staleFrom : Infinity, lo);
        w.staleTo = Math.max(w.staleTo != null ? w.staleTo : -Infinity, hi);
        w.segs = cur;
    }

    // ------------------------------------------------------------------ strip

    // The strip asks for full 'tlview.*' keys and falls back to its own English on null.
    function stripText(key, vars) {
        try {
            var v = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key, vars) : null;
            return typeof v === 'string' && v !== key ? v : null;
        } catch (e) { return null; }
    }
    function createStrip() {
        destroyStrip();
        if (!BYD.tlstrip || typeof BYD.tlstrip.create !== 'function' || !V.zoneReady) return;
        var vertical = V.layout === 'port', host = vertical ? els.vhost : els.striphost, s = null;
        try {
            s = BYD.tlstrip.create(host, {
                orientation: vertical ? 'vertical' : 'horizontal', tz: V.zone.f ? V.zone.tz : undefined,
                reducedMotion: reducedMotion(), t: stripText
            });
        } catch (e) { if (root.console) root.console.error('[tlview] strip', e); s = null; }
        if (!s) return;
        V.strip = s;
        V.stripHost = host;
        var on = function (ev, fn) { call(s, 'on', ev, function (x) { if (V.strip === s) fn(x); }); };
        on('scrubstart', function () { beginScrub(); });
        on('scrub', function (ms) {
            if (num(ms) == null) return;
            V.playhead = ms;
            // The player only knows its own day: its frames would be the wrong day's. Release reloads it.
            if (scrubbable(ms)) call(P(), 'scrubWall', ms);
            scheduleRows();
            scheduleUi();
        });
        on('scrubend', function (ms) { endScrub(num(ms) != null ? ms : V.playhead); });
        on('tapmark', function (m) { if (m && num(m.t) != null) { V.touched = true; seekTo(m.t, { glide: true }); } });
        on('taplive', function () { root.location.href = 'live-view.html'; });
        on('span', function (ms) { if (num(ms) > 0) { V.spans[V.layout] = ms; renderZoom(); } });
        on('range', function (r) { if (r && num(r.from) != null) { V.range = { from: r.from, to: r.to }; ensureData(); } });
        on('viewport', function (v) { if (v && num(v.from) != null) { V.range = { from: v.from, to: v.to }; scheduleRows(); } });
        call(s, 'setSpan', V.spans[V.layout], false);
        call(s, 'setFilter', FILTERS[V.filter]);
        pushStripData();
        call(s, 'setPlayback', V.playhead, playingNow(), V.speed);
        var span = V.spans[V.layout];
        V.range = { from: V.playhead - span / 2, to: V.playhead + span / 2 };
    }
    function destroyStrip() {
        if (!V.strip) return;
        var s = V.strip;
        V.strip = null;
        call(s, 'destroy');
        if (V.stripHost) V.stripHost.innerHTML = '';
        V.stripHost = null;
    }
    function beginScrub() {
        V.scrubbing = true;
        V.nextAt = null;
        // A drag started while waiting resumes on release (the button showed Pause); the player can't know.
        V.wasWaiting = V.waitTail != null;
        V.waitTail = null;
        V.touched = true;
        V.pendingDay = null;
        V.pendingDefault = false;
        V.wasPlaying = V.playing;
        els.root.classList.add('tlv-drag');
        hideHint();
        call(P(), 'beginScrub');
    }
    function endScrub(ms) {
        V.scrubbing = false;
        els.root.classList.remove('tlv-drag');
        V.playhead = ms;
        if (needsReload(ms)) reloadPlayer(ms, V.wasPlaying);
        else {
            call(P(), 'endScrub', ms);
            if (V.wasWaiting && V.active) call(P(), 'play');
        }
        ensureData();
        scheduleUi(true);
    }

    // ------------------------------------------------------------------ player

    function createPlayer() {
        if (V.player || !BYD.tlplayer || typeof BYD.tlplayer.create !== 'function') {
            if (!V.player) showBanner(tr('clipError'));
            return;
        }
        var p = null;
        try { p = BYD.tlplayer.create(els.video, { tz: V.zone.f ? V.zone.tz : undefined, muted: false, data: playerData }); } catch (e) { p = null; }
        if (!p) { showBanner(tr('clipError')); return; }
        V.player = p;
        call(p, 'on', 'position', onPosition);
        call(p, 'on', 'state', onState);
        call(p, 'on', 'item', onItem);
        call(p, 'on', 'sources', onSources);
        call(p, 'on', 'gap', onGap);
        call(p, 'on', 'error', onError);
        call(p, 'setSpeed', V.speed);
        call(p, 'setSkipQuiet', V.skip);
        call(p, 'setContinue', V.cont);
        call(p, 'setPrefer', V.prefer);
        call(p, 'setMuted', false);
    }
    /** The player's window is a store day: it gets the store's copy (re-read unless a few seconds old), never a second fetch. */
    function playerData(win) {
        if (!store || !V.zoneReady || !win) return null;
        var key = V.zone.dayKey(win.from), r = V.zone.dayRange(key), c = store.days.get(key);
        // A 25 h DST day plays a 24 h window: the player reads that one itself.
        if (r.from !== win.from || r.to !== win.to) return null;
        var fresh = !win.fresh && c && c.data && !c.promise && Date.now() - (c.at || 0) <= SHARE_MS;
        return (fresh ? Promise.resolve(c) : store.load(key, true, visibleKeys().concat([key]))).then(function (x) {
            if (x && x.data) return x.data;
            throw new Error('no data');
        });
    }
    /** HLS windows are car-local days (max 24 h); a seek outside the window reloads it. */
    function reloadPlayer(t, play, auto) {
        var p = P();
        if (!p || !V.zoneReady) return;
        var key = V.zone.dayKey(t), r = V.zone.dayRange(key), from = r.from, to = r.to;
        if (to - from > DAY) { from = clamp(t - DAY / 2, r.from, r.to - DAY); to = from + DAY; }
        V.playerWin = { key: key, from: from, to: to, at: nowMs(), segs: null, stale: false, staleFrom: null, staleTo: null };
        checkStale();
        call(p, 'load', { from: from, to: to }, V.prefer);
        // auto: playback carrying on (not a user seek), so a gap there is crossed, not held.
        call(p, 'seekWall', t, !!auto);
        if (play && V.active) call(p, 'play');
    }
    /** The player can preview t from its own table (stale only past the first change). */
    function scrubbable(t) {
        var w = V.playerWin;
        if (!w || t < w.from || t >= w.to) return false;
        return !(w.stale && w.staleFrom != null && t >= w.staleFrom - 2 * SEC);
    }
    function needsReload(t) {
        var w = V.playerWin;
        if (!w) return true;
        if (t < w.from || t >= w.to) return true;
        // The player's table lacks a change at or before t; seeks earlier play its own table (playing on into the
        // change is caught by onEnded and the gap-crossing check).
        return !!(w.stale && w.staleFrom != null && t >= w.staleFrom - 2 * SEC);
    }
    function seekTo(t, o) {
        o = o || {};
        if (num(t) == null || !V.zoneReady) return;
        if (V.waitTail != null) o = { play: true, glide: o.glide };
        V.waitTail = null;
        V.nextAt = null;
        // Before the player call: a seek into a gap shows its card synchronously.
        hideHint();
        t = Math.min(t, nowMs());
        if (t < V.minMs) V.minMs = t - HOUR;
        V.playhead = t;
        if (P()) {
            if (needsReload(t)) reloadPlayer(t, o.play || V.playing);
            else {
                call(P(), 'seekWall', t);
                if (o.play && V.active) call(P(), 'play');
            }
        }
        if (V.strip) {
            if (o.glide && !reducedMotion()) call(V.strip, 'glideTo', t);
            else call(V.strip, 'setPlayback', t, playingNow(), V.speed);
        }
        ensureData();
        scheduleUi(true);
    }
    function onPosition(x) {
        var wall = x ? num(x.wall) : null;
        if (wall == null || V.scrubbing || V.rowDrag) return;
        V.playhead = wall;
        // Waiting for a clip to be saved shows Pause although the player itself is stopped.
        if (x.playing != null && !!x.playing !== V.playing && !(V.waitTail != null && !x.playing)) setPlaying(!!x.playing);
        call(V.strip, 'setPlayback', wall, playingNow(), V.speed);
        if (V.playing && V.hintTo != null && wall >= V.hintTo - SEC) hideHint();
        scheduleRows();
        scheduleUi();
    }
    function onState(x) {
        var s = String(typeof x === 'string' ? x : (x && (x.state || x.name)) || '').toLowerCase();
        els.player.classList.toggle('tlv-buffering', s === 'buffering');
        if (s === 'playing') setPlaying(true);
        else if (s === 'paused' || s === 'ended' || s === 'idle' || s === 'error') setPlaying(V.waitTail != null);
        if (s === 'ended') onEnded();
    }
    function onEnded() {
        if (!V.active || !V.data) return;
        // A clip saved meanwhile after the playhead that this player's window lacks: on to it (straight on only when contiguous).
        if (V.playerWin && V.playerWin.stale && V.playerWin.staleTo != null && V.playerWin.staleTo >= V.playhead - 2 * SEC) {
            if (V.cont) playNextAfter(V.playhead);
            else reloadPlayer(V.playhead, true);
            return;
        }
        // Where the player ran out (its last position report can trail the end by a tick).
        var pl = P(), ph = pl && num(pl.lastWall) != null ? Math.max(V.playhead, pl.lastWall) : V.playhead;
        V.playhead = ph;
        var segs = V.data.segments, next = null;
        for (var i = 0; i < segs.length; i++) {
            var s = segs[i];
            if (s.available !== false && s.end != null && s.end > ph + SEC) { next = s; break; }
        }
        // Ran out at the newest saved clip while one is still recording: resume when it lands.
        if (!next) {
            if (V.data.live && V.data.live.length) armWait(ph);
            return;
        }
        // With Continue off only footage that carries straight on (a clip across midnight, as across a seam).
        if (!V.cont && next.start - ph > 1500) return;
        // A clip running past the window's end (midnight) continues where the window stopped.
        var at = Math.max(next.start, ph);
        // Spanning the window's end: continue from the next window, not the old one's last frame.
        if (V.playerWin && next.end > V.playerWin.to) at = Math.max(at, V.playerWin.to);
        if (V.playerWin && at >= V.playerWin.to - SEC) seekTo(at, { play: true, glide: true });
    }
    /** After the newest clip ended mid-recording: play on from it once the saved clip appears. */
    function followTail() {
        var t = V.waitTail;
        // V.playing is true while waiting (the button shows Pause); only real playback elsewhere ends the wait.
        if (t == null || !V.data) return;
        var pl = P();
        if (pl && pl.state === 'playing') { V.waitTail = null; return; }
        // A wait longer than any clip takes to save: no surprise start later.
        if (Date.now() - (V.waitTailAt || 0) > 20 * MIN) { V.waitTail = null; setPlaying(false); return; }
        var segs = V.data.segments;
        for (var i = 0; i < segs.length; i++) {
            var s = segs[i];
            if (s.available !== false && s.end != null && s.end > t + SEC) {
                V.waitTail = null;
                // With Continue off only a clip that carries straight on (as the player plays across seams).
                if (V.cont || s.start - t <= 1500) { hideHint(); playNextAfter(t); }
                else setPlaying(false);
                return;
            }
        }
        // The recording stopped and nothing after t is still being saved or analysed: stop waiting (the card stays).
        if (!(V.data.live && V.data.live.length)) {
            for (var j = 0; j < segs.length; j++) {
                var u = segs[j];
                // Any clip still being analysed may be the one saved over t (it can start before t).
                if (u.available !== false && u.indexing && (u.end == null || !(u.end > u.start))) return;
            }
            V.waitTail = null;
            setPlaying(false);
        }
    }
    function onItem(x) {
        V.item = x && x.segment ? x.segment : null;
        V.itemId = V.item && V.item.id != null ? String(V.item.id) : null;
        V.spec = x ? x.spec || null : null;
        layoutOverlays();
        markActive(true);
        updateObj();
    }
    function onSources(x) {
        V.families = x && Array.isArray(x.families) ? x.families : [];
        V.activeFamily = x ? x.active || null : null;
        renderSource();
    }
    function onGap(x) {
        var to = x ? num(x.to) : null, from = x ? num(x.from) : null;
        if (x && x.user && x.skip) { flashGapCard(from != null ? from : V.playhead); return; }
        if (x && x.user) {
            showGapCard(from != null ? from : V.playhead, to);
            if (x.play) playNextAfter(from != null ? from : V.playhead);
            return;
        }
        if (to == null) return;
        if (x && x.cont) {
            // Crossing a gap by itself on a table that predates footage the view now has there: play that instead.
            // Deferred: the player is still inside its own boundary handling.
            if (from != null && V.playerWin && V.playerWin.stale && footageBetween(from, to)) {
                var win = V.playerWin;
                // Not if the user paused or moved meanwhile (the player's own intent, not the icon, says so).
                setTimeout(function () { var pp = P(); if (V.active && pp && pp.intent === 'play' && V.playerWin === win) reloadPlayer(from, true, true); }, 0);
            }
            return;
        }
        if (V.cont) { if (V.strip && !reducedMotion()) call(V.strip, 'glideTo', to); return; }
        V.hintTo = to;
        els.hint.innerHTML = esc(tr('nextEventIn', { duration: fmtSpan(to - (from != null ? from : V.playhead)) })) + ' <span aria-hidden="true">▸</span>';
        els.hint.hidden = false;
    }
    /** Play pressed in a gap the player can't see past (its day ends): next footage on any loaded day. */
    function playNextAfter(t) {
        // Re-entry (the player answering this very seek with another hold) would loop: once per press.
        if (V.inNext) return;
        V.inNext = true;
        try {
            var segs = (V.data && V.data.segments) || [];
            for (var i = 0; i < segs.length; i++) {
                var s = segs[i];
                if (s.available !== false && s.end != null && s.end > t + SEC) {
                    // A fresh load: the view may know this clip before the player's own day data does.
                    var at = Math.max(s.start, t);
                    // The player held there again (its data lacks the clip): once, not a reload loop.
                    if (V.nextAt === at && Date.now() - V.nextAtTs < 5 * SEC) { setPlaying(false); return; }
                    V.nextAt = at;
                    V.nextAtTs = Date.now();
                    hideHint();
                    V.playhead = at;
                    reloadPlayer(at, true);
                    if (V.strip) call(V.strip, 'glideTo', at);
                    setPlaying(true);
                    return;
                }
            }
            // Still recording here: play it once it's saved.
            if (V.data && V.data.live && V.data.live.length) armWait(t);
            else setPlaying(false);
        } finally {
            V.inNext = false;
        }
    }
    /** Waits for the next saved clip after t: says so, and the button shows Pause (it cancels the wait). */
    function armWait(t, keepAt) {
        if (!(keepAt && V.waitTail != null)) V.waitTailAt = Date.now();
        V.waitTail = t;
        var lv = (V.data && V.data.live) || [], ls = Infinity;
        for (var i = 0; i < lv.length; i++) { var sn = lv[i] ? num(lv[i].since) : null; if (sn != null && sn >= t && sn < ls) ls = sn; }
        showGapCard(ls - t <= 1500 ? ls : t, null);
        setPlaying(true);
    }
    function hideHint() {
        V.hintTo = null;
        V.gapArgs = null;
        V.gapSliverId = null;
        if (els.hint) els.hint.hidden = true;
        if (els.gapCard) els.gapCard.hidden = true;
        if (els.player) els.player.classList.remove('tlv-ingap');
    }
    // A user seek into unrecorded time: say why, keep the playhead, offer the next footage.
    function showGapCard(at, next) {
        if (!els.gapCard) return;
        V.gapArgs = { at: at, next: next };
        var text = gapCardText(at);
        els.gapCard.textContent = text;
        els.gapCard.hidden = false;
        if (els.player) els.player.classList.add('tlv-ingap');
        if (next != null && next > at) {
            V.hintTo = next;
            els.hint.innerHTML = esc(tr('nextEventIn', { duration: fmtSpan(next - at) })) + ' <span aria-hidden="true">▸</span>';
            els.hint.hidden = false;
        } else {
            V.hintTo = null;
            els.hint.hidden = true;
        }
    }
    /** A move while playing skipped unrecorded time: say why for a moment while the next footage plays. */
    function flashGapCard(at) {
        if (!els.gapCard) return;
        hideHint();
        els.gapCard.textContent = gapCardText(at);
        els.gapCard.hidden = false;
        clearTimeout(V.flashTimer);
        V.flashTimer = setTimeout(function () { if (V.gapArgs == null && els.gapCard) els.gapCard.hidden = true; }, 2500);
    }
    /** A playable clip runs somewhere inside (a, b). */
    function footageBetween(a, b) {
        var segs = (V.data && V.data.segments) || [];
        for (var i = 0; i < segs.length; i++) {
            var s = segs[i];
            if (s && s.available !== false && s.end != null && s.end > s.start && s.start < b && s.end > a + SEC) return true;
        }
        return false;
    }
    function segById(id) {
        var segs = (V.data && V.data.segments) || [];
        for (var i = 0; i < segs.length; i++) if (segs[i] && String(segs[i].id) === String(id)) return segs[i];
        return null;
    }
    /** A playable clip covers t (the card's cause, e.g. indexing or a missing card, has gone). */
    function footageAt(t) {
        var segs = (V.data && V.data.segments) || [];
        for (var i = 0; i < segs.length; i++) {
            var s = segs[i];
            if (s && s.available !== false && s.end != null && s.end > s.start && t >= s.start && t < s.end) return true;
        }
        return false;
    }
    /** Half the time a sliver is drawn wide at the strip's zoom (a few px of bar plus slop). */
    function sliverSlop() {
        var a = call(V.strip, 'timeToPos', V.playhead), b = call(V.strip, 'timeToPos', V.playhead + MIN);
        var pxPerMs = num(a) != null && num(b) != null ? Math.abs(b - a) / MIN : 0;
        return pxPerMs > 0 ? Math.max(2 * SEC, 6 / pxPerMs) : 2 * SEC;
    }
    function inPairs(p, n, t) {
        for (var i = 0; i < n; i++) if (t >= p[2 * i] && t < p[2 * i + 1]) return i;
        return -1;
    }
    /** What the strip draws under the playhead, in words: its own gap, reason and coverage (as on the head unit). */
    function gapCardText(at) {
        var d = V.data || {}, segs = d.segments || [], now = nowMs(), i, slop = sliverSlop();
        function unknownLen(x) { return x.end == null || !(x.end > x.start); }
        // A sliver named before stays named while it is unanalysed (a zoom change narrows the touch slop).
        var prevSl = V.gapSliverId != null ? segById(V.gapSliverId) : null;
        V.gapSliverId = prevSl && prevSl.available !== false && unknownLen(prevSl) ? prevSl.id : null;
        var I = BYD.tlstrip && BYD.tlstrip._internals, m = null;
        try {
            m = I && I.buildModel ? I.buildModel({
                segments: segs, marks: d.marks || [], sessions: d.sessions || [], trips: d.trips || [], states: d.states || [],
                reaped: d.reaped || [], road: d.road || [], live: d.live || [], now: now, minMs: Math.min(V.minMs, now - HOUR), maxMs: now
            }) : null;
        } catch (e) { m = null; }
        // The red live band, whatever a neighbouring sliver's touch slop says at this zoom.
        if (m && inPairs(m.live, m.nLive, at) >= 0) return tr('recordingNow');
        for (i = 0; i < segs.length; i++) {
            var x = segs[i];
            if (x && x.available !== false && num(x.start) != null && unknownLen(x) && at >= x.start - slop && at < x.start + slop) {
                V.gapSliverId = x.id;
                return tr(x.indexing ? 'gapIndexing' : 'gapDamaged');
            }
        }
        if (!m) return tr('gapNoFootage');
        if (inPairs(m.unav, m.nUnav, at) >= 0) {
            var off = null;
            for (i = 0; i < segs.length && !off; i++) {
                var o = segs[i];
                if (o && o.available === false && at >= o.start - SEC && at < Math.max(o.end || 0, o.start + SEC) + SEC) off = o;
            }
            var vol = tr(off && off.storage === 'USB' ? 'storageUsb' : off && off.storage === 'INTERNAL' ? 'storageInternal' : 'storageSd');
            return tr('unavailableVolume', { volume: vol });
        }
        if (inPairs(m.armed, m.nArmed, at) >= 0) return tr('armedHint');
        var g = inPairs(m.gaps, m.nGaps, at);
        if (g < 0) return tr('gapNoFootage');
        // An unanalysed clip's footage runs on past its sliver, up to the gap's end.
        for (i = 0; i < segs.length; i++) {
            var y = segs[i];
            if (y && y.available !== false && y.indexing && unknownLen(y) && Math.abs(m.gaps[2 * g] - (y.start + SEC)) <= 1500) return tr('gapIndexing');
        }
        var key = m.gapKey[g], reason = key ? (stripText(key) || tr(key.replace(/^tlview\./, ''))) : null;
        return tr('gapNoFootage') + ' · ' + fmtSpan(m.gaps[2 * g + 1] - m.gaps[2 * g]) + (reason ? ' · ' + reason : '');
    }
    function onError(x) {
        // Auth is never "this clip": 401 is an expired session (to login), 403 a refusal like any unreachable recorder.
        if (x && x.code === 'auth') {
            if (x.status === 403) showBanner(tr('offline'));
            else authExpired();
            return;
        }
        var msg = String(x && x.message || ''), seg = x && x.segment;
        var unsupported = (x && x.code === 'unsupported') || /hevc|hvc1|hev1|h\.?265|codec|unsupported|not supported/i.test(msg);
        if (x && x.code === 'gone') {
            // The strip still draws the vanished clip: re-read its day.
            var t = seg && num(seg.start) != null ? num(seg.start) : V.playhead;
            if (V.zoneReady) store.load(V.zone.dayKey(t), true, visibleKeys());
            showBanner(tr('clipGone'));
            return;
        }
        if (unsupported && seg && localPath(seg.videoUrl)) {
            showBanner(tr('cantPlayHere'), seg.videoUrl);
        } else {
            showBanner(unsupported ? tr('cantPlayHere') : tr('clipError'));
        }
        // The newest clip can't play while one is recording: follow on to the next saved clip (never this one).
        var se = seg ? num(seg.end) : null;
        var pl = P(), wantPlay = V.playing || !!(pl && pl.intent === 'play');
        if (x && x.code === 'media' && se != null && wantPlay && V.data && V.data.live && V.data.live.length && !footageBetween(se, Infinity)) {
            armWait(se, true);
        }
    }
    function showBanner(text, downloadUrl) {
        if (!els.banner) return;
        els.banner.innerHTML = '<span>' + esc(text) + '</span>' +
            (downloadUrl ? ' <a class="tlv-dl" href="' + esc(downloadUrl) + '" download>' + esc(tr('download')) + '</a>' : '');
        els.banner.hidden = false;
        clearTimeout(V.bannerTimer);
        V.bannerTimer = setTimeout(function () { els.banner.hidden = true; }, downloadUrl ? 9000 : 5000);
    }
    /** Media actually running: waiting for a clip to be saved shows Pause but the playhead stands still. */
    function playingNow() { return V.playing && V.waitTail == null; }
    function setPlaying(on) {
        V.playing = !!on;
        if (!els.play) return;
        els.play.innerHTML = V.playing ? ICON.pause : ICON.play;
        els.play.setAttribute('aria-label', tr(V.playing ? 'ariaPause' : 'ariaPlay'));
        if (V.playing) armHide(); else showControls();
    }
    function togglePlay() {
        var p = P();
        if (!p) return;
        // A press is a user gesture: sound that autoplay rules forced off may come back now.
        call(p, 'setMuted', false);
        if (V.playing) { V.waitTail = null; V.wasWaiting = false; V.wasPlaying = false; call(p, 'pause'); setPlaying(false); return; }
        V.nextAt = null;
        if (V.waitTail != null) {
            // The newest clip is still being saved: keep waiting for it, and say so.
            armWait(V.waitTail);
            return;
        }
        // Held in a gap the player sees no end to (its day ends there): go to the next footage on any day.
        if (V.gapArgs && V.gapArgs.next == null && els.gapCard && !els.gapCard.hidden) { playNextAfter(V.playhead); return; }
        // Ran out (end of its window or of all footage): play() alone would change nothing.
        if (p.state === 'ended') { playNextAfter(V.playhead); return; }
        // Optimistic first: a state the player emits while handling play() must win.
        setPlaying(true);
        var w = V.playerWin, inGap = !footageAt(V.playhead);
        // Play in a gap while this table predates a change after the playhead: its "next" may skip new footage.
        var staleAhead = !!(w && w.stale && w.staleTo != null && w.staleTo >= V.playhead - 2 * SEC);
        if (inGap && (staleAhead || needsReload(V.playhead))) playNextAfter(V.playhead);
        else if (needsReload(V.playhead)) reloadPlayer(V.playhead, true);
        else call(p, 'play');
    }
    function setSpeed(s) {
        if (SPEEDS.indexOf(s) < 0) return;
        V.speed = s;
        lsSet(LS.speed, s);
        call(P(), 'setSpeed', s);
        call(V.strip, 'setPlayback', V.playhead, playingNow(), s);
        renderSpeed();
    }
    function toggleOpt(id) {
        if (id === 'skip') { V.skip = !V.skip; lsSet(LS.skip, V.skip ? 1 : 0); call(P(), 'setSkipQuiet', V.skip); }
        else if (id === 'cont') { V.cont = !V.cont; lsSet(LS.cont, V.cont ? 1 : 0); call(P(), 'setContinue', V.cont); if (V.cont) hideHint(); }
        renderSpeed();
    }
    function isOem(f) { return /oem|dash/i.test(String(f || '')); }
    function renderSource() {
        var fams = V.families || [];
        els.src.hidden = fams.length < 2;
        if (fams.length < 2) return;
        var label = tr(isOem(V.activeFamily || fams[0]) ? 'sourceOem' : 'sourceMosaic');
        els.src.innerHTML = ICON.swap + '<span>' + esc(label) + '</span>';
        els.src.setAttribute('aria-label', tr('sourceSwitch') + ': ' + label);
    }
    function switchSource() {
        var next = isOem(V.activeFamily || (V.families || [])[0]) ? null : 'oemDashcam';
        V.prefer = next;
        lsSet(LS.prefer, next || '');
        call(P(), 'setPrefer', next);
    }

    // ------------------------------------------------------------------ overlays on the video

    function contentRect() {
        var box = els.video, W = box.clientWidth, H = box.clientHeight;
        var v = box.querySelector('video'), c = v ? null : box.querySelector('canvas');
        var vw = v ? v.videoWidth : (c ? c.width : 0), vh = v ? v.videoHeight : (c ? c.height : 0);
        if (!vw || !vh || !W || !H) return { x: 0, y: 0, w: W, h: H };
        var s = Math.min(W / vw, H / vh);
        return { x: (W - vw * s) / 2, y: (H - vh * s) / 2, w: vw * s, h: vh * s };
    }
    function clipLayout() {
        var sp = V.spec, seg = V.item;
        if (sp && (sp.single || sp.layout === 'single')) return 'single';
        if (sp && typeof sp.layout === 'string' && LAYOUTS[sp.layout.toLowerCase()]) return sp.layout.toLowerCase();
        if (!seg) return 'standard';
        if (seg.type === 'oemDashcam' || seg.layout === 'single') return 'single';
        return seg.layout === 'dashcam' ? 'dashcam' : 'standard';
    }
    function layoutOverlays() {
        if (!V.built) return;
        var r = contentRect(), lay = clipLayout(), sig = lay + (V.item ? '+' : '-');
        els.cams.style.left = r.x + 'px';
        els.cams.style.top = r.y + 'px';
        els.cams.style.width = r.w + 'px';
        els.cams.style.height = r.h + 'px';
        if (sig !== V.camSig) {
            V.camSig = sig;
            V.camLayout = lay;
            els.cams.innerHTML = V.item ? LAYOUTS[lay].map(function (t) {
                return '<span class="tlv-cam" style="left:' + (t.x * 100) + '%;top:' + (t.y * 100) + '%">' + esc(tr(CAM_KEY[t.cam])) + '</span>';
            }).join('') : '';
            V.objSig = '';
        }
        els.cams.hidden = !V.item;
        if (!els.obj.hidden) placeObj(V.objCam);
    }
    function updateObj() {
        var m = V.activeId ? V.momentsById[V.activeId] : null, t = V.playhead, mk = null;
        if (!m || m.seg.type === 'replay') { els.obj.hidden = true; V.objSig = ''; return; }
        for (var i = 0; i < m.marks.length; i++) {
            var x = m.marks[i], t0 = num(x.t0), t1 = num(x.t1);
            if (!KIND_KEY[x.kind] || x.kind === 'motion' || x.kind === 'deterrent' || t0 == null) continue;
            if (t >= t0 - 500 && t <= (t1 != null ? t1 : t0) + 500 && (!mk || (num(x.conf) || 0) > (num(mk.conf) || 0))) mk = x;
        }
        var kind = mk ? CLS_ALIAS[mk.kind] : m.classes[0];
        if (!kind) { els.obj.hidden = true; V.objSig = ''; return; }
        var conf = num(mk ? mk.conf : m.seg.peakConfidence), cam = mk && CAM_KEY[mk.cam] ? mk.cam : firstCamera(m);
        var pct = conf == null ? null : Math.round(conf <= 1 ? conf * 100 : conf);
        var sig = kind + '|' + pct + '|' + cam + '|' + V.camLayout;
        if (sig === V.objSig && !els.obj.hidden) return;
        V.objSig = sig;
        V.objCam = cam;
        els.obj.innerHTML = (KIND_ICON[kind] || '') + '<span>' + esc(tr(KIND_KEY[kind]) + (pct != null ? ' · ' + pct + '%' : '')) + '</span>';
        els.obj.hidden = false;
        placeObj(cam);
    }
    /** Chip sits at the bottom of its camera's tile, on the outer side for bottom tiles to clear the controls. */
    function placeObj(cam) {
        var tiles = LAYOUTS[V.camLayout || 'standard'], tile = tiles[0];
        for (var i = 0; i < tiles.length; i++) if (tiles[i].cam === cam) tile = tiles[i];
        var r = contentRect(), W = els.video.clientWidth, H = els.video.clientHeight;
        var bottomEdge = tile.y + tile.h >= 0.99, rightSide = bottomEdge && tile.x >= 0.5 && tile.w < 1;
        var bottomPx = H - (r.y + (tile.y + tile.h) * r.h) + 10;
        els.obj.style.bottom = bottomPx + 'px';
        els.obj.style.top = 'auto';
        if (rightSide) { els.obj.style.left = 'auto'; els.obj.style.right = (W - (r.x + (tile.x + tile.w) * r.w) + 10) + 'px'; }
        else { els.obj.style.right = 'auto'; els.obj.style.left = (r.x + tile.x * r.w + 10) + 'px'; }
    }
    function showControls() {
        if (!els.ctl) return;
        els.ctl.classList.remove('tlv-hide');
        armHide();
    }
    function armHide() {
        clearTimeout(V.hideTimer);
        // Waiting keeps the controls up: its Pause button is how the wait is cancelled.
        if (!playingNow() || V.menu) return;
        V.hideTimer = setTimeout(function () {
            if (playingNow() && !V.menu && !keyboardFocusInControls()) els.ctl.classList.add('tlv-hide');
        }, HIDE_MS);
    }
    /** A mouse click leaves focus on Play; only keyboard focus keeps the pill up. */
    function keyboardFocusInControls() {
        try { return !!els.ctl.querySelector(':focus-visible'); } catch (e) { return false; }
    }
    function updateStates() {
        if (!V.built || !V.zoneReady) return;
        var key = V.zone.dayKey(V.playhead), c = store.days.get(key), html = '', cls = '';
        if ((!c || !c.data) && (store.offline || (c && c.status === 'error'))) {
            cls = 'tlv-offline';
            html = '<p>' + esc(tr('offline')) + '</p><button type="button" class="tlv-btn" data-tlv="retry">' + esc(tr('retry')) + '</button>';
        } else if (!c || !c.data) {
            cls = 'tlv-loading';
            html = '<span class="tlv-spin" aria-hidden="true"></span>';
        } else if (!c.data.segments.length && !(key === V.zone.dayKey(nowMs()) && c.data.live.length)) {
            cls = 'tlv-empty';
            var l = dayLabel(key);
            html = '<p>' + esc(tr('emptyDay', { day: l.rel || l.date })) + '</p>';
        } else if (store.offline) {
            cls = 'tlv-mini';
            html = '<p>' + esc(tr('offline')) + '</p><button type="button" class="tlv-btn" data-tlv="retry">' + esc(tr('retry')) + '</button>';
        }
        var sig = cls + '|' + html;
        if (sig !== V.stateSig) {
            V.stateSig = sig;
            els.state.className = 'tlv-state ' + cls;
            els.state.innerHTML = html;
            els.state.hidden = !cls;
        }
    }

    // ------------------------------------------------------------------ Moments list

    function scheduleUi(now) {
        if (now) {
            clearTimeout(V.uiTimer);
            V.uiTimer = null;
            V.uiAt = Date.now();
            updateUi();
            return;
        }
        if (V.uiTimer) return;
        V.uiTimer = setTimeout(function () { V.uiTimer = null; V.uiAt = Date.now(); updateUi(); }, Math.max(0, 250 - (Date.now() - V.uiAt)));
    }
    function updateUi() {
        if (!V.active || !V.zoneReady || !V.built) return;
        updateDayTitle();
        renderList();
        markActive(false);
        updateObj();
        updatePrevNext();
        updateStates();
        if (V.zone.dayKey(V.playhead) !== V.lastEnsureKey) { V.lastEnsureKey = V.zone.dayKey(V.playhead); ensureData(); }
    }
    function updateDayTitle() {
        var key = V.zone.dayKey(V.playhead), l = dayLabel(key), sig = key + '|' + l.rel + '|' + l.date;
        if (sig === V.titleSig) return;
        V.titleSig = sig;
        if (els.dpText) els.dpText.innerHTML = l.rel ? '<b>' + esc(l.rel) + '</b> · ' + esc(l.date) : '<b>' + esc(l.date) + '</b>';
        els.abDay.textContent = l.rel || l.date;
        els.abSub.textContent = l.rel ? l.date : '';
    }
    function listMoments() {
        var key = V.zone.dayKey(V.playhead);
        return V.moments.filter(function (m) { return m.day === key && momentPasses(m, V.filter); });
    }
    function renderList() {
        if (V.layout !== 'land') return;
        var key = V.zone.dayKey(V.playhead), sig = key + '|' + V.filter + '|' + V.dataRev + '|' + lang();
        if (sig === V.listSig) return;
        V.listSig = sig;
        var items = listMoments();
        els.lc.textContent = items.length ? String(items.length) : '';
        els.rows.setAttribute('aria-label', tr('moments') + ', ' + trCount('momentsCount', items.length));
        V.rowEls = {};
        V.activeId = undefined;
        if (!items.length) { els.rows.innerHTML = ''; return; }
        els.rows.innerHTML = items.map(function (m) { return '<div role="listitem" class="tlv-li">' + rowHtml(m) + '</div>'; }).join('');
        var btns = els.rows.querySelectorAll('.tlv-row');
        for (var i = 0; i < btns.length; i++) V.rowEls[btns[i].getAttribute('data-id')] = btns[i];
        observeThumbs(els.rows);
        markActive(true);
    }
    function rowHtml(m) {
        return '<button type="button" class="tlv-row" data-tlv="moment" data-id="' + esc(m.id) + '">' +
            '<span class="tlv-th" data-thumb="' + esc(m.id) + '"></span>' +
            '<span class="tlv-mt"><span class="tlv-t1"><span class="tlv-tm">' + esc(fmtTime(m.t)) + '</span><span class="tlv-k">' + esc(m.label) + '</span></span>' +
            '<span class="tlv-t2">' + esc(momentMeta(m)) + '</span></span>' +
            '<span class="tlv-st"></span></button>';
    }
    function setStatus(row, m, on) {
        var st = row.querySelector('.tlv-st');
        row.classList.toggle('tlv-on', on);
        if (on) row.setAttribute('aria-current', 'true'); else row.removeAttribute('aria-current');
        st.className = 'tlv-st' + (on ? ' tlv-now' : (m.level ? ' tlv-' + m.level : ''));
        st.innerHTML = on ? ICON.eq + esc(tr('playing'))
            : (m.level ? '<i aria-hidden="true"></i>' + esc(tr(m.level === 'critical' ? 'critical' : 'alert')) : '');
    }
    function activeMomentId() {
        var t = V.playhead, best = null;
        for (var i = 0; i < V.moments.length; i++) {
            var m = V.moments[i], end = m.end != null ? m.end : m.start + SEC;
            if (t < m.start - 250 || t >= end + 250) continue;
            if (m.id === V.itemId) return m.id;
            if (!best || m.start > best.start) best = m;
        }
        return best ? best.id : null;
    }
    function markActive(force) {
        var id = activeMomentId();
        if (id === V.activeId && !force) return;
        V.activeId = id;
        var k, m, maps = [V.rowEls, V.vRowEls || {}];
        for (var j = 0; j < maps.length; j++) {
            for (k in maps[j]) {
                if (!Object.prototype.hasOwnProperty.call(maps[j], k) || !(m = V.momentsById[k])) continue;
                var row = maps[j][k].row || maps[j][k];
                var on = k === id;
                if (on !== row.classList.contains('tlv-on') || force || !row._st) { setStatus(row, m, on); row._st = true; }
            }
        }
        if (id && V.layout === 'land' && V.rowEls[id]) autoScroll(V.rowEls[id]);
    }
    function autoScroll(row) {
        if (Date.now() - V.userScrollAt < SCROLL_HOLD_MS || V.scrubbing) return;
        var box = els.rows, li = row.parentNode, top = li.offsetTop, h = li.offsetHeight;
        if (top >= box.scrollTop + 8 && top + h <= box.scrollTop + box.clientHeight - 8) return;
        var target = Math.max(0, top - (box.clientHeight - h) / 2);
        if (typeof box.scrollTo === 'function') box.scrollTo({ top: target, behavior: reducedMotion() ? 'auto' : 'smooth' });
        else box.scrollTop = target;
    }
    function openMoment(id) {
        var m = V.momentsById[id];
        if (!m) return;
        V.touched = true;
        V.userScrollAt = 0;
        seekTo(m.start, { glide: true, play: true });
    }
    function sortedNav() {
        return V.moments.filter(function (m) { return momentPasses(m, V.filter); }).sort(function (a, b) { return a.start - b.start; });
    }
    function findMoment(dir) {
        var list = sortedNav(), t = V.playhead, i;
        if (dir > 0) { for (i = 0; i < list.length; i++) if (list[i].start > t + SEC) return list[i]; return null; }
        for (i = list.length - 1; i >= 0; i--) if (list[i].start < t - 2 * SEC) return list[i];
        return null;
    }
    function jumpMoment(dir, animate) {
        var m = findMoment(dir);
        if (!m) return;
        V.touched = true;
        seekTo(m.start, { glide: animate, play: V.playing });
    }
    function updatePrevNext() {
        els.prev.disabled = !findMoment(-1);
        els.next.disabled = !findMoment(1);
    }
    function setFilter(k) {
        if (!FILTERS[k]) return;
        V.filter = k;
        lsSet(LS.filter, k);
        call(V.strip, 'setFilter', FILTERS[k]);
        renderChips();
        if (V.menu === 'filter') paintFilterPop();
        V.listSig = '';
        scheduleRows();
        scheduleUi(true);
    }
    function setZoom(ms) {
        if (!(ms > 0)) return;
        V.spans[V.layout] = ms;
        call(V.strip, 'setSpan', ms, !reducedMotion());
        renderZoom();
    }

    // ------------------------------------------------------------------ phone vertical feed

    function scheduleRows() {
        if (V.rowsRaf || V.layout !== 'port') return;
        var raf = root.requestAnimationFrame || function (f) { return setTimeout(f, 16); };
        V.rowsRaf = raf(function () { V.rowsRaf = 0; positionRows(); });
    }
    function clearVRows() {
        if (els.vrows) els.vrows.innerHTML = '';
        V.vRowEls = {};
    }
    function positionRows() {
        if (V.layout !== 'port' || !V.active || !V.strip || typeof V.strip.timeToPos !== 'function') return;
        var h = els.vfeed.clientHeight, items = [], list = V.moments, i;
        for (i = 0; i < list.length; i++) {
            if (!momentPasses(list[i], V.filter)) continue;
            var pos = call(V.strip, 'timeToPos', list[i].t);
            if (num(pos) == null || pos < -ROW_H * 8 || pos > h + ROW_H) continue;
            items.push({ key: list[i].id, pos: pos });
        }
        var placed = pushRows(items, ROW_H, ROW_GAP, null), keep = {}, pool = V.vRowEls || (V.vRowEls = {});
        for (i = 0; i < placed.length; i++) {
            var p = placed[i];
            if (p.top > h || p.top + ROW_H < 0) continue;
            keep[p.key] = true;
            var e = pool[p.key];
            if (!e) {
                var m = V.momentsById[p.key];
                var li = mk('div', 'tlv-vli');
                li.setAttribute('role', 'listitem');
                li.innerHTML = rowHtml(m);
                e = pool[p.key] = { li: li, row: li.firstChild };
                els.vrows.appendChild(li);
                setStatus(e.row, m, p.key === V.activeId);
                e.row._st = true;
                observeThumbs(li);
            }
            e.li.style.transform = 'translateY(' + Math.round(p.top) + 'px)';
        }
        for (var k in pool) {
            if (Object.prototype.hasOwnProperty.call(pool, k) && !keep[k]) {
                if (pool[k].li.parentNode) pool[k].li.parentNode.removeChild(pool[k].li);
                delete pool[k];
            }
        }
    }
    /** Drags that start on a row still move the strip under the fixed playhead. */
    function wireRowDrag() {
        els.vrows.addEventListener('pointerdown', function (e) {
            var row = e.target.closest ? e.target.closest('.tlv-row') : null;
            if (!row || !V.strip || (e.button != null && e.button > 0)) return;
            V.rowDrag = { id: e.pointerId, y0: e.clientY, t0: V.playhead, t: null, moved: false, row: row };
        });
        els.vrows.addEventListener('pointermove', function (e) {
            var d = V.rowDrag;
            if (!d || d.id !== e.pointerId) return;
            var dy = e.clientY - d.y0;
            if (!d.moved) {
                if (Math.abs(dy) < 8) return;
                d.moved = true;
                try { d.row.setPointerCapture(e.pointerId); } catch (err) { /* capture is best-effort */ }
                beginScrub();
            }
            var a = call(V.strip, 'timeToPos', d.t0), b = call(V.strip, 'timeToPos', d.t0 + MIN), k = (b - a) / MIN;
            if (!isFinite(k) || !k) return;
            var t = clamp(d.t0 - dy / k, V.minMs, nowMs());
            d.t = t;
            V.playhead = t;
            call(V.strip, 'setPlayback', t, false, V.speed);
            if (scrubbable(t)) call(P(), 'scrubWall', t);
            scheduleRows();
            scheduleUi();
        });
        var end = function (e) {
            var d = V.rowDrag;
            if (!d || d.id !== e.pointerId) return;
            V.rowDrag = null;
            if (!d.moved) return;
            V.suppressClickUntil = Date.now() + 400;
            endScrub(d.t != null ? d.t : d.t0);
        };
        els.vrows.addEventListener('pointerup', end);
        els.vrows.addEventListener('pointercancel', end);
    }

    // ------------------------------------------------------------------ thumbnails (fetched for the auth header)

    function observeThumbs(container) {
        var nodes = container.querySelectorAll('[data-thumb]');
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            if (n._tlvThumb) continue;
            n._tlvThumb = true;
            var hit = thumbs.cache.get(n.getAttribute('data-thumb'));
            if (hit && hit.url) { setThumb(n, hit.url); continue; }
            if (typeof IntersectionObserver === 'function') {
                if (!thumbs.io) {
                    thumbs.io = new IntersectionObserver(function (entries) {
                        entries.forEach(function (en) {
                            if (!en.isIntersecting) return;
                            thumbs.io.unobserve(en.target);
                            queueThumb(en.target);
                        });
                    }, { rootMargin: '200px' });
                }
                thumbs.io.observe(n);
            } else queueThumb(n);
        }
    }
    function queueThumb(n) { thumbs.queue.push(n); pumpThumbs(); }
    function pumpThumbs() {
        while (thumbs.active < THUMB_PARALLEL && thumbs.queue.length) {
            var n = thumbs.queue.shift(), id = n.getAttribute('data-thumb'), m = V.momentsById[id];
            if (!n.isConnected || !m) continue;
            var hit = thumbs.cache.get(id);
            if (hit && hit.url) { setThumb(n, hit.url); continue; }
            if (hit && hit.promise) { hit.promise.then(applyTo(n)); continue; }
            if (hit && Date.now() - (hit.failedAt || 0) < MIN) continue;
            var url = m.seg.thumbUrl;
            if (!localPath(url)) continue;
            thumbs.active++;
            var entry = { url: null, promise: null };
            thumbs.cache.set(id, entry);
            entry.promise = loadThumb(url, 0).then(settleThumb(id, entry));
            entry.promise.then(applyTo(n));
        }
    }
    function settleThumb(id, entry) {
        return function (u) {
            entry.url = u;
            entry.promise = null;
            if (!u) entry.failedAt = Date.now();
            thumbs.active--;
            trimThumbs();
            pumpThumbs();
            return u;
        };
    }
    function applyTo(n) { return function (u) { if (u && n.isConnected) setThumb(n, u); }; }
    function loadThumb(url, tries) {
        return root.fetch(url).then(function (r) {
            if (r.status === 401) { authExpired(); return null; }
            if (r.status === 202 && tries < 6) {
                return new Promise(function (res) { setTimeout(res, Math.min(1000 * Math.pow(1.5, tries), 5000)); })
                    .then(function () { return loadThumb(url, tries + 1); });
            }
            return r.ok ? r.blob().then(function (b) { return b && b.size ? URL.createObjectURL(b) : null; }) : null;
        }).catch(function () { return null; });
    }
    function setThumb(n, u) {
        if (n.firstChild && n.firstChild.getAttribute && n.firstChild.getAttribute('src') === u) return;
        var img = doc.createElement('img');
        img.alt = '';
        img.decoding = 'async';
        img.src = u;
        n.innerHTML = '';
        n.appendChild(img);
    }
    function trimThumbs() {
        if (thumbs.cache.size <= THUMB_CACHE) return;
        var drop = [], extra = thumbs.cache.size - THUMB_CACHE;
        thumbs.cache.forEach(function (e, k) { if (drop.length < extra && !e.promise) drop.push(k); });
        drop.forEach(function (k) {
            var e = thumbs.cache.get(k);
            if (e && e.url) { try { URL.revokeObjectURL(e.url); } catch (err) { /* already revoked */ } }
            thumbs.cache.delete(k);
        });
    }

    // ------------------------------------------------------------------ menus

    function closeMenus() {
        var was = !!V.menu;
        [els.spdpop, els.sheet, els.scrim, els.datepop, els.filterpop].forEach(function (m) { if (m) m.hidden = true; });
        if (els.spd) els.spd.setAttribute('aria-expanded', 'false');
        var anchor = V.menuAnchor;
        V.menu = null;
        V.menuAnchor = null;
        if (was && anchor && doc.activeElement && (doc.activeElement === doc.body || isInMenu(doc.activeElement))) {
            try { anchor.focus(); } catch (e) { /* detached */ }
        }
        armHide();
        return was;
    }
    function isInMenu(el) {
        return [els.spdpop, els.sheet, els.datepop, els.filterpop].some(function (m) { return m && m.contains(el); });
    }
    function openMenu(name, box, anchor) {
        V.menu = name;
        V.menuAnchor = anchor || null;
        box.hidden = false;
        clearTimeout(V.hideTimer);
        try { box.focus({ preventScroll: true }); } catch (e) { /* old engines */ }
    }
    function toggleSpeedMenu() {
        if (V.menu === 'speed') { closeMenus(); return; }
        closeMenus();
        var sheet = V.layout === 'port' || els.player.clientHeight < 330;
        var box = sheet ? els.sheet : els.spdpop;
        box.innerHTML = speedMenuHtml(sheet);
        box.setAttribute('aria-label', tr('speedTitle'));
        if (sheet) els.scrim.hidden = false;
        openMenu('speed', box, els.spd);
        if (!sheet) positionSpeedPop();
        els.spd.setAttribute('aria-expanded', 'true');
        els.ctl.classList.remove('tlv-hide');
    }
    function positionSpeedPop() {
        var pr = els.player.getBoundingClientRect(), br = els.spd.getBoundingClientRect(), w = els.spdpop.offsetWidth || 300;
        var cx = br.left - pr.left + br.width / 2, left = clamp(cx - w / 2, 12, Math.max(12, pr.width - w - 12));
        els.spdpop.style.left = left + 'px';
        els.spdpop.style.bottom = Math.round(pr.bottom - br.top + 12) + 'px';
        els.spdpop.style.setProperty('--tlv-tip', Math.round(cx - left) + 'px');
    }
    function placePop(box, anchor) {
        var r = anchor.getBoundingClientRect(), vw = root.innerWidth || doc.documentElement.clientWidth, w = box.offsetWidth;
        var left = r.left + r.width / 2 > vw / 2 ? r.right - w : r.left;
        box.style.left = Math.round(clamp(left, 8, Math.max(8, vw - w - 8))) + 'px';
        box.style.top = Math.round(r.bottom + 8) + 'px';
    }
    function toggleDatePop(anchor) {
        if (V.menu === 'date') { closeMenus(); return; }
        closeMenus();
        if (!V.zoneReady) return;
        var key = V.zone.dayKey(V.playhead), today = V.zone.dayKey(nowMs()), p = V.zone.parts(V.playhead);
        var minKey = V.zone.dayKey(Math.min(V.minMs, nowMs() - (DAYS_BACK - 1) * DAY));
        els.datepop.setAttribute('aria-label', tr('ariaDate'));
        els.datepop.innerHTML = '<div class="tlv-days" role="group"></div>' +
            '<div class="tlv-dtrow">' +
                '<input type="date" class="tlv-in" data-tlv-in="date" value="' + key + '" min="' + minKey + '" max="' + today + '" aria-label="' + esc(tr('ariaDate')) + '">' +
                '<input type="time" class="tlv-in" data-tlv-in="time" step="1" value="' + pad2(p.h) + ':' + pad2(p.mi) + ':' + pad2(p.s) + '" aria-label="' + esc(tr('ariaDate')) + '">' +
            '</div>' +
            (V.layout === 'port' ? '<a class="tlv-live tlv-livepop" href="live-view.html"><span class="tlv-dot" aria-hidden="true"></span><span class="tlv-livet">' + esc(tr('live')) + '</span></a>' : '');
        paintDays();
        openMenu('date', els.datepop, anchor);
        placePop(els.datepop, anchor);
        refreshDays(false);
    }
    function paintDays() {
        var box = els.datepop && els.datepop.querySelector('.tlv-days');
        if (!box) return;
        var key = V.zone.dayKey(V.playhead), today = V.zone.dayKey(nowMs()), h = '';
        for (var i = 6; i >= 0; i--) {
            var k = addDaysKey(today, -i), info = V.daysInfo ? V.daysInfo[k] : null;
            var has = !!(info && (num(info.footageMs) > 0 || num(info.events) > 0));
            var l = dayLabel(k), on = k === key;
            h += '<button type="button" class="tlv-day' + (on ? ' tlv-on' : '') + (V.daysInfo && !has ? ' tlv-none' : '') + '" data-tlv="day" data-v="' + k + '" aria-pressed="' + on + '" aria-label="' + esc(l.rel ? l.rel + ', ' + l.date : l.date) + '">' +
                '<span class="tlv-dw">' + esc(weekdayShort(k)) + '</span><span class="tlv-dn">' + parseKey(k).d + '</span>' +
                '<i class="tlv-ddot' + (info && num(info.alerts) > 0 ? ' tlv-alert' : '') + '"' + (has ? '' : ' hidden') + '></i></button>';
        }
        box.innerHTML = h;
    }
    function onDateInput() {
        var di = els.datepop.querySelector('[data-tlv-in="date"]'), ti = els.datepop.querySelector('[data-tlv-in="time"]');
        var k = parseKey(di && di.value);
        if (!k) return;
        var hm = /^(\d{1,2}):(\d{2})(?::(\d{2}))?/.exec(ti && ti.value || '');
        if (!hm) { gotoDay(di.value); return; }
        var t = V.zone.toEpoch(k.y, k.mo, k.d, +hm[1], +hm[2], +(hm[3] || 0));
        V.minMs = Math.min(V.minMs, V.zone.dayRange(di.value).from);
        V.touched = true;
        seekTo(Math.min(t, nowMs()), {});
        paintDays();
    }
    function gotoDay(key) {
        if (!parseKey(key)) return;
        var r = V.zone.dayRange(key);
        V.minMs = Math.min(V.minMs, r.from);
        V.touched = true;
        var c = store.days.get(key);
        if (c && c.data) { seekTo(bestTargetInDay(key), {}); return; }
        V.pendingDay = key;
        seekTo(Math.min(r.from + 12 * HOUR, nowMs()), {});
        store.load(key, false);
    }
    function toggleFilterPop(anchor) {
        if (V.menu === 'filter') { closeMenus(); return; }
        closeMenus();
        els.filterpop.setAttribute('aria-label', tr('ariaFilter'));
        paintFilterPop();
        openMenu('filter', els.filterpop, anchor);
        placePop(els.filterpop, anchor);
    }
    function paintFilterPop() {
        els.filterpop.innerHTML = FILTER_ORDER.map(function (k) {
            var on = k === V.filter;
            return '<button type="button" class="tlv-fopt' + (on ? ' tlv-on' : '') + '" data-tlv="filter" data-v="' + k + '" aria-pressed="' + on + '">' + esc(tr(FILTER_KEY[k])) + '</button>';
        }).join('');
    }

    // ------------------------------------------------------------------ fullscreen + share

    function fsTarget() { return els.player; }

    function isFsActive() {
        var el = doc.fullscreenElement || doc.webkitFullscreenElement;
        return !!el || !!(fsTarget() && fsTarget().classList.contains('is-fullscreen'));
    }

    function refreshFsButton() {
        if (!els.fs) return;
        var on = isFsActive();
        V.fullscreen = on;
        els.fs.innerHTML = on ? ICON.fullscreenExit : ICON.fullscreen;
        els.fs.setAttribute('aria-label', tr(on ? 'exitFullscreen' : 'fullscreen'));
        els.fs.setAttribute('aria-pressed', on ? 'true' : 'false');
        if (els.root) els.root.classList.toggle('tlv-fs-on', on);
        doc.documentElement.classList.toggle('tlv-fs-on', on);
    }

    function onFsChange() { refreshFsButton(); }

    var fsPending = 0;

    function fsFallback() {
        var box = fsTarget();
        if (!box) return;
        box.classList.add('is-fullscreen');
        refreshFsButton();
    }

    function onFsError() {
        if (fsPending) { clearTimeout(fsPending); fsPending = 0; }
        fsFallback();
    }

    function exitFullscreen() {
        var box = fsTarget();
        if (!box) return;
        if (fsPending) { clearTimeout(fsPending); fsPending = 0; }
        if (box.classList.contains('is-fullscreen')) {
            box.classList.remove('is-fullscreen');
        } else if (doc.fullscreenElement && doc.exitFullscreen) {
            var p = doc.exitFullscreen();
            if (p && p.catch) p.catch(function () {});
        } else if (doc.webkitFullscreenElement && doc.webkitExitFullscreen) {
            doc.webkitExitFullscreen();
        }
        refreshFsButton();
    }

    function toggleFullscreen() {
        var box = fsTarget();
        if (!box) return;
        if (isFsActive()) { exitFullscreen(); return; }
        try {
            var p = box.requestFullscreen ? box.requestFullscreen()
                : box.webkitRequestFullscreen ? box.webkitRequestFullscreen() : null;
            if (p && p.catch) p.catch(onFsError);
            else if (!box.requestFullscreen && !box.webkitRequestFullscreen) { fsFallback(); return; }
        } catch (e) { fsFallback(); return; }
        // A refused request can also fail silently: fall back if nothing happened.
        fsPending = setTimeout(function () {
            fsPending = 0;
            if (!isFsActive()) fsFallback();
        }, 600);
    }

    function segFilename(seg) {
        if (!seg || !seg.id) return 'recording.mp4';
        return String(seg.id).replace(/[^a-f0-9]/gi, '') + '.mp4';
    }

    function downloadCurrentSegment() {
        var seg = V.item;
        var url = seg && seg.videoUrl;
        if (!url) return;
        var a = doc.createElement('a');
        a.href = url;
        a.download = segFilename(seg);
        doc.body.appendChild(a);
        a.click();
        doc.body.removeChild(a);
    }

    function shareCurrentSegment() {
        var seg = V.item;
        if (!seg || seg.available === false || !seg.videoUrl) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(tr('shareUnavailable'), 'warning');
            return;
        }
        if (seg.end == null) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(tr('shareRecording'), 'warning');
            return;
        }
        var url = seg.videoUrl;
        var name = segFilename(seg);
        var nav = root.navigator;
        var canShareFiles = false;
        try {
            canShareFiles = !!(nav && nav.share && nav.canShare && root.File
                && nav.canShare({ files: [new File([''], name, { type: 'video/mp4' })] }));
        } catch (e) { canShareFiles = false; }
        var shareFiles = function (file) {
            if (!nav.canShare({ files: [file] })) return false;
            return nav.share({ files: [file], title: name }).then(function () { return true; }).catch(function () { return false; });
        };
        (canShareFiles ? fetch(url) : Promise.resolve(null)).then(function (resp) {
            if (!resp) return false;
            if (!resp.ok) throw new Error('fetch');
            return resp.blob().then(function (blob) {
                var file = new File([blob], name, { type: blob.type || 'video/mp4' });
                return shareFiles(file);
            });
        }).then(function (shared) {
            if (shared) return;
            if (root.navigator && root.navigator.share) {
                var link = ((root.location && root.location.origin) || '') + url;
                return root.navigator.share({ title: name, url: link }).catch(function () { return false; });
            }
            return false;
        }).then(function (shared) {
            if (shared) return;
            var link = ((root.location && root.location.origin) || '') + url;
            if (root.navigator && root.navigator.clipboard && root.navigator.clipboard.writeText) {
                return root.navigator.clipboard.writeText(link).then(function () {
                    if (BYD.utils && BYD.utils.toast) BYD.utils.toast(tr('shareCopied'), 'success');
                }).catch(function () { downloadCurrentSegment(); });
            }
            downloadCurrentSegment();
        }).catch(function () {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(tr('shareFailed'), 'error');
            downloadCurrentSegment();
        });
    }

    // ------------------------------------------------------------------ input

    function onClick(e) {
        var el = e.target && e.target.closest ? e.target.closest('[data-tlv]') : null;
        if (!el || el.disabled) return;
        var a = el.getAttribute('data-tlv'), v = el.getAttribute('data-v');
        switch (a) {
            case 'play': V.touched = true; togglePlay(); break;
            case 'prev': jumpMoment(-1, true); break;
            case 'next': jumpMoment(1, true); break;
            case 'fullscreen': toggleFullscreen(); break;
            case 'share': shareCurrentSegment(); break;
            case 'speed': toggleSpeedMenu(); break;
            case 'setspeed': setSpeed(+v); break;
            case 'toggle': toggleOpt(v); break;
            case 'source': switchSource(); break;
            case 'nexthint': if (V.hintTo != null) seekTo(V.hintTo, { play: true, glide: true }); break;
            case 'retry': retry(); break;
            case 'moment': if (Date.now() >= V.suppressClickUntil) openMoment(el.getAttribute('data-id')); break;
            case 'filter': setFilter(v); if (els.filterpop.contains(el)) closeMenus(); break;
            case 'zoom': setZoom(+v); break;
            case 'date': toggleDatePop(el); break;
            case 'day': closeMenus(); gotoDay(v); break;
            case 'filtermenu': toggleFilterPop(el); break;
            case 'tolist': setMode('list'); break;
            case 'scrim': closeMenus(); break;
        }
        if (a !== 'scrim') showControls();
    }
    function onDocPointer(e) {
        if (!V.menu) return;
        var t = e.target;
        if (isInMenu(t) || (V.menuAnchor && V.menuAnchor.contains(t)) || t === els.scrim) return;
        closeMenus();
    }
    function onKey(e) {
        if (!V.active || e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
        var modal = doc.getElementById('videoModal');
        if (modal && modal.classList.contains('active')) return;
        var t = e.target, tag = (t && t.tagName) || '';
        if (e.key === 'Escape') {
            if (isFsActive()) { exitFullscreen(); e.preventDefault(); return; }
            if (closeMenus()) e.preventDefault();
            return;
        }
        if (/^(input|textarea|select)$/i.test(tag) || (t && t.isContentEditable)) return;
        if (V.menu && isInMenu(t)) return;
        var onButton = /^(button|a)$/i.test(tag);
        switch (e.key) {
            case 'ArrowLeft': case 'ArrowRight':
                V.touched = true;
                seekTo(V.playhead + (e.key === 'ArrowRight' ? 1 : -1) * (e.shiftKey ? MIN : 10 * SEC), {});
                break;
            case 'ArrowUp': jumpMoment(-1, false); break;
            case 'ArrowDown': jumpMoment(1, false); break;
            case ' ': case 'Spacebar':
                if (onButton) return;
                V.touched = true;
                togglePlay();
                break;
            case '1': case '2': case '4': case '8': setSpeed(+e.key); break;
            case 'f': case 'F':
                if (onButton) return;
                toggleFullscreen();
                break;
            default: return;
        }
        e.preventDefault();
        showControls();
    }

    // ------------------------------------------------------------------ boot

    function readPrefs() {
        var s = +lsGet(LS.speed);
        V.speed = SPEEDS.indexOf(s) >= 0 ? s : 1;
        V.skip = lsGet(LS.skip) === '1';
        V.cont = lsGet(LS.cont) !== '0';
        V.prefer = lsGet(LS.prefer) || null;
        var f = lsGet(LS.filter);
        V.filter = FILTERS[f] ? f : 'all';
    }
    /** Runs at parse time, before events.js reads the URL, so a remembered Timeline owns ?file=. */
    function boot() {
        store = new Store(onStoreChange);
        readPrefs();
        var params;
        try { params = new URLSearchParams(root.location.search); } catch (e) { params = null; }
        var view = params && params.get('view'), mode = readMode(), back = takeResume();
        if (view === 'timeline' || (back != null && mode === 'timeline')) {
            var t = back != null ? back : num(params.get('t')), end = back != null ? null : num(params.get('end'));
            V.deep = t != null ? { t: t, end: end } : null;
            mode = 'timeline';
        } else if (mode === 'timeline' && params && params.get('file')) {
            var f = parseFilename(params.get('file'));
            if (f) { V.deep = { file: f }; cleanUrl(['file']); }
        }
        els.root = doc.getElementById('tlvRoot');
        if (!els.root) return;
        var btns = doc.querySelectorAll('[data-tlv-mode]');
        for (var i = 0; i < btns.length; i++) {
            btns[i].addEventListener('click', function (ev) { setMode(ev.currentTarget.getAttribute('data-tlv-mode')); });
        }
        if (mode === 'timeline') doc.documentElement.classList.add('tlv-on');
        V.mode = mode;
        paintToggle();
        var ready = function () {
            buildBarEnd();
            var go = function () {
                if (V.applied) return;
                V.mode = null;
                setMode(mode, false);
            };
            var i18n = BYD.i18n && BYD.i18n.init ? BYD.i18n.init() : null;
            var fallback = setTimeout(go, 2500);
            if (i18n && i18n.then) i18n.then(function () { clearTimeout(fallback); go(); }, function () { clearTimeout(fallback); go(); });
            else { clearTimeout(fallback); go(); }
            if (BYD.i18n && BYD.i18n.onChange) BYD.i18n.onChange(onLangChange);
        };
        if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', ready); else ready();
        doc.addEventListener('keydown', onKey);
        doc.addEventListener('pointerdown', onDocPointer, true);
        var resizeRaf = 0;
        root.addEventListener('resize', function () {
            if (!V.active || resizeRaf) return;
            resizeRaf = (root.requestAnimationFrame || setTimeout)(function () {
                resizeRaf = 0;
                if (V.menu && V.menu !== 'speed') closeMenus();
                applyLayout(false);
            });
        });
        root.addEventListener('pagehide', stashResume);
        doc.addEventListener('visibilitychange', function () {
            if (doc.visibilityState === 'visible') tick(true);
            // Leaving cancels a pending "plays once saved", as leaving the app does on the head unit.
            else if (doc.visibilityState === 'hidden' && V.waitTail != null) { V.waitTail = null; setPlaying(false); }
        });
        root.addEventListener('byd:clock-changed', function () { timeFmtCache = {}; V.listSig = ''; clearVRows(); scheduleRows(); scheduleUi(true); });
    }
    function onLangChange() {
        timeFmtCache = {};
        renderTexts();
        V.moments.forEach(function (m) { m.label = momentKind(m); });
        pushStripData();
        clearVRows();
        scheduleRows();
        if (V.menu) closeMenus();
        scheduleUi(true);
    }

    BYD.tlview = {
        setMode: function (m) { setMode(m, true); },
        getMode: function () { return V.mode; },
        _internals: {
            EN: EN, tr: tr, trCount: trCount, reasonText: reasonText, esc: esc, Zone: Zone, parseKey: parseKey,
            addDaysKey: addDaysKey, parseFilename: parseFilename, matchFileSegment: matchFileSegment,
            buildMoments: buildMoments, momentKind: momentKind, momentPasses: momentPasses, momentMeta: momentMeta,
            segClasses: segClasses, pushRows: pushRows, fmtSpan: fmtSpan, fmtClipDur: fmtClipDur, FILTERS: FILTERS,
            Store: Store, normalizeWindow: normalizeWindow
        }
    };

    if (doc && doc.documentElement && doc.getElementById) boot();
})(typeof window !== 'undefined' ? window : this);
