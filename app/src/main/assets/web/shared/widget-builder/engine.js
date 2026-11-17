/* Glass widget files in the browser: the expression language, springs and layers on Canvas 2D.
   CustomExpr / GlassCustomView draw the same files on the car; keep the two in step.
   ES2017 only (no ?. ?? flat fromEntries replaceAll roundRect). */
(function (G) {
  'use strict';

  // maps a file's strings index have no prototype, so "constructor" or "__proto__" find nothing
  const dict = o => Object.assign(Object.create(null), o);
  const SIZES = dict({ small: [272, 184], medium: [400, 184], wide: [520, 184] });
  const MAX_LAYERS = 24, MAX_EXPR = 400, MAX_NAME = 60, MAX_ABOUT = 500, MAX_BYTES = 16384;
  const FONT = "Barlow, Roboto, 'Segoe UI', system-ui, -apple-system, sans-serif";
  const clamp01 = v => Math.min(1, Math.max(0, v));
  const nv = (v, d) => (v == null ? d : v);
  const numOr = (v, d) => (typeof v === 'number' && isFinite(v) ? v : d);

  // ── colour: ARGB ints with GlassColor's maths ──────────────────────────────
  const A = c => (c >>> 24) & 255, R = c => (c >>> 16) & 255, Gn = c => (c >>> 8) & 255, B = c => c & 255;
  const argb = (a, r, g, b) => (((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255)) >>> 0;
  const color = {
    alpha: (c, k) => argb(Math.round(clamp01(k) * 255), R(c), Gn(c), B(c)),
    fade: (c, k) => color.alpha(c, (A(c) / 255) * k),
    clear: c => argb(0, R(c), Gn(c), B(c)),
    mix(a, b, t) {
      const k = clamp01(t), l = (x, y) => Math.round(x + (y - x) * k);
      return argb(l(A(a), A(b)), l(R(a), R(b)), l(Gn(a), Gn(b)), l(B(a), B(b)));
    },
    lighten: (c, t) => color.mix(c, color.alpha(0xFFFFFFFF, A(c) / 255), t),
    darken: (c, t) => color.mix(c, color.alpha(0xFF000000, A(c) / 255), t),
    mixHue(a, b, t) {
      const x = toHsl(a), y = toHsl(b);
      let dh = y[0] - x[0];
      if (dh > 180) dh -= 360;
      if (dh < -180) dh += 360;
      const k = clamp01(t);
      return fromHsl((x[0] + dh * k + 360) % 360, x[1] + (y[1] - x[1]) * k, x[2] + (y[2] - x[2]) * k);
    },
    rampHue(xs, cs, x) {
      if (x <= xs[0]) return cs[0];
      for (let i = 1; i < xs.length; i++) if (x <= xs[i]) return color.mixHue(cs[i - 1], cs[i], (x - xs[i - 1]) / Math.max(1e-6, xs[i] - xs[i - 1]));
      return cs[cs.length - 1];
    },
    css: c => 'rgba(' + R(c) + ',' + Gn(c) + ',' + B(c) + ',' + (+(A(c) / 255).toFixed(4)) + ')',
    parse(s) {
      const m = /^#([0-9a-f]{6}|[0-9a-f]{8})$/i.exec(s);
      if (!m) return null;
      return m[1].length === 6 ? (0xFF000000 | parseInt(m[1], 16)) >>> 0 : parseInt(m[1], 16) >>> 0;
    },
    hex: c => '#' + (c >>> 0).toString(16).toUpperCase().padStart(8, '0'),
    A,
  };
  function toHsl(c) {
    const r = R(c) / 255, g = Gn(c) / 255, b = B(c) / 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
    if (mx === mn) return [0, 0, l];
    const d = mx - mn, s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
    const h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    return [h * 60, s, l];
  }
  function fromHsl(h, s, l) {
    const a = s * Math.min(l, 1 - l);
    const f = n => { const k = (n + h / 30) % 12; return l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1))); };
    return argb(255, Math.round(f(0) * 255), Math.round(f(8) * 255), Math.round(f(4) * 255));
  }

  // GlassTheme's tokens; the ones a widget file may name
  const TOKENS = dict({
    night: dict({
      accent: 0xFF5DDBB6, good: 0xFF5BD382, warn: 0xFFFFB870, danger: 0xFFFFB4AB, glowAccent: 0xFF2FF0BD, glowGood: 0xFF3BE07A,
      glowWarn: 0xFFFFA23A, glowDanger: 0xFFFF5A4F, fuel: 0xFFF6C343, regen: 0xFF58B9FF, regenText: 0xFF8CCBFF,
      text: 0xFFE7F2ED, text2: 0xA8E7F2ED, text3: 0x66E7F2ED, etch: 0x38FFFFFF, white: 0xFFFFFFFF, black: 0xFF000000,
    }),
    day: dict({
      accent: 0xFF007A62, good: 0xFF1F7A3F, warn: 0xFFA6601C, danger: 0xFFBA1A1A, glowAccent: 0xFF00A884, glowGood: 0xFF1FA650,
      glowWarn: 0xFFE08A12, glowDanger: 0xFFE5342B, fuel: 0xFFCF9410, regen: 0xFF1C6FD6, regenText: 0xFF1759B3,
      text: 0xFF0B1A15, text2: 0xA80B1A15, text3: 0x6B0B1A15, etch: 0x3D0B281E, white: 0xFFFFFFFF, black: 0xFF000000,
    }),
  });
  const SHADOW = { night: [0xFF000000, 0.55], day: [0xFF12291F, 0.26] };
  const UNIT_WORDS = ['miles', 'dist_unit', 'speed_unit', 'near_unit', 'height_unit', 'consumption_unit', 'tyre_unit'];
  // currency: the trip's currency, else the charging session's; the car puts it in the data
  const BUILTINS = new Set(['night', 'driving', 'parked', 'pi', 'true', 'false', 'null', 'currency'].concat(UNIT_WORDS));
  // Home Assistant channels (automation triggers): mqtt('name') reads the data key "mqtt:name"
  const MQTT_CHANNEL = /^[A-Za-z0-9._-]{1,64}$/;
  const CONTROL_CHARS = /[\u0000-\u001F\u007F-\u009F]/g;
  const PAYLOAD_NUMBER = /^[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?$/;
  /** A channel's latest payload: a number when it reads as one, else its text (no control characters, trimmed, ≤64 units, never half a pair). */
  function mqttValue(raw) {
    if (typeof raw === 'number') return isFinite(raw) ? raw : null;
    if (typeof raw !== 'string') return null;
    const t = raw.replace(CONTROL_CHARS, '').trim();
    if (!t) return null;
    if (PAYLOAD_NUMBER.test(t)) { const x = Number(t); if (isFinite(x)) return x; }
    if (t.length <= 64) return t;
    const c = t.charCodeAt(63);
    return t.slice(0, c >= 0xD800 && c <= 0xDBFF ? 63 : 64);
  }
  const KM_PER_MI = 1.609344, YD_PER_M = 1.0936133, FT_PER_M = 3.2808399, MAX_NUM = 1e9;

  // ── expressions ────────────────────────────────────────────────────────────
  const ARITY = dict({
    min: [1, 8], max: [1, 8], clamp: [3, 3], lerp: [3, 3], abs: [1, 1], round: [1, 1], floor: [1, 1], ceil: [1, 1], sqrt: [1, 1],
    pow: [2, 2], sin: [1, 1], cos: [1, 1], known: [1, 1], ifknown: [2, 2], fmt: [1, 2], ramp: [3, 31], mix: [3, 3], alpha: [2, 2],
    dist: [1, 1], near: [1, 1], height: [1, 1], consumption: [1, 1], tyre: [1, 1], mqtt: [1, 1],
  });
  class ExprError extends Error {
    constructor(msg, at, code, arg) { super(msg); this.name = 'ExprError'; this.at = at; this.code = code; this.arg = arg; }
  }
  const isDigit = ch => ch >= '0' && ch <= '9';
  const isAlpha = ch => (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch === '_';
  const TWO = ['||', '&&', '==', '!=', '<=', '>='];

  function tokenize(src) {
    const out = [];
    let i = 0;
    while (i < src.length) {
      const ch = src[i];
      if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') { i++; continue; }
      if (isDigit(ch) || ch === '.') {
        let j = i;
        while (j < src.length && (isDigit(src[j]) || src[j] === '.')) j++;
        const v = Number(src.slice(i, j));
        if (!Number.isFinite(v) || src.slice(i, j) === '.') throw new ExprError('Bad number', i, 'number');
        out.push({ t: 'n', v, i }); i = j; continue;
      }
      if (isAlpha(ch)) {
        let j = i;
        while (j < src.length && (isAlpha(src[j]) || isDigit(src[j]))) j++;
        out.push({ t: 'id', v: src.slice(i, j), i }); i = j; continue;
      }
      if (ch === "'" || ch === '"') {
        const j = src.indexOf(ch, i + 1);
        if (j < 0) throw new ExprError('Unclosed text', i, 'unclosed');
        out.push({ t: 's', v: src.slice(i + 1, j), i }); i = j + 1; continue;
      }
      if (ch === '#') {
        let j = i + 1;
        while (j < src.length && (isAlpha(src[j]) || isDigit(src[j]))) j++;
        const c = color.parse(src.slice(i, j));
        if (c == null) throw new ExprError('Bad colour', i, 'colour', src.slice(i, j));
        out.push({ t: 'c', v: c, i }); i = j; continue;
      }
      const two = src.slice(i, i + 2);
      if (TWO.includes(two)) { out.push({ t: 'op', v: two, i }); i += 2; continue; }
      if ('?:+-*/%<>!(),'.includes(ch)) { out.push({ t: 'op', v: ch, i }); i++; continue; }
      throw new ExprError('Unexpected “' + ch + '”', i, 'char', ch);
    }
    out.push({ t: 'end', v: '', i: src.length });
    return out;
  }

  const MAX_DEPTH = 64;
  function parse(src) {
    if (src.length > MAX_EXPR) throw new ExprError('Expression is too long', 0, 'long', MAX_EXPR);
    const tk = tokenize(src);
    let p = 0, depth = 0;
    const isOp = v => tk[p].t === 'op' && tk[p].v === v;
    const expect = v => { if (!isOp(v)) throw new ExprError('Expected “' + v + '”', tk[p].i, 'expect', v); p++; };
    // the same nesting bound as CustomExpr, counted at the same places
    const deeper = () => { if (++depth > MAX_DEPTH) throw new ExprError('Expression is nested too deeply', tk[p].i, 'deep', MAX_DEPTH); };
    const binary = (sub, ops) => () => {
      let a = sub();
      while (tk[p].t === 'op' && ops.includes(tk[p].v)) { const op = tk[p++].v; a = { k: 'b', op, a, b: sub() }; }
      return a;
    };
    const unary = () => {
      if (!isOp('-') && !isOp('!')) return primary();
      const op = tk[p++].v;
      deeper();
      const a = unary();
      depth--;
      return { k: 'u', op, a };
    };
    const mul = binary(unary, ['*', '/', '%']), add = binary(mul, ['+', '-']), cmp = binary(add, ['<', '<=', '>', '>=']);
    const eq = binary(cmp, ['==', '!=']), and = binary(eq, ['&&']), or = binary(and, ['||']);
    function cond() {
      deeper();
      const c = or();
      let r = c;
      if (isOp('?')) {
        p++;
        const a = cond(); expect(':');
        r = { k: '?', c, a, b: cond() };
      }
      depth--;
      return r;
    }
    function primary() {
      const t = tk[p++];
      if (t.t === 'n') return { k: 'lit', v: t.v };
      if (t.t === 's') return { k: 'lit', v: t.v };
      if (t.t === 'c') return { k: 'lit', v: { col: t.v } };
      if (t.t === 'id') {
        if (isOp('(')) {
          p++;
          const ar = ARITY[t.v];
          if (!ar) throw new ExprError('Unknown function “' + t.v + '”', t.i, 'fn', t.v);
          const args = [];
          if (!isOp(')')) { args.push(cond()); while (isOp(',')) { p++; args.push(cond()); } }
          expect(')');
          if (t.v === 'mqtt' && !(args.length === 1 && args[0].k === 'lit' && typeof args[0].v === 'string' && MQTT_CHANNEL.test(args[0].v))) {
            throw new ExprError('mqtt() needs a channel name in quotes', t.i, 'mqtt');
          }
          if (args.length < ar[0] || args.length > ar[1]) {
            throw new ExprError(t.v + '() takes ' + (ar[0] === ar[1] ? ar[0] : ar[0] + '–' + ar[1]) + ' values', t.i, 'arity', [t.v, ar[0], ar[1]]);
          }
          return { k: 'f', fn: t.v, args };
        }
        if (t.v === 'true' || t.v === 'false') return { k: 'lit', v: t.v === 'true' };
        if (t.v === 'null') return { k: 'lit', v: null };
        return { k: 'id', v: t.v };
      }
      if (t.t === 'op' && t.v === '(') { const e = cond(); expect(')'); return e; }
      if (t.t === 'end') throw new ExprError('Unfinished expression', t.i, 'end');
      throw new ExprError('Unexpected “' + t.v + '”', t.i, 'unexpected', t.v);
    }
    const e = cond();
    if (tk[p].t !== 'end') throw new ExprError('Unexpected “' + tk[p].v + '”', tk[p].i, 'unexpected', tk[p].v);
    return e;
  }

  const isCol = v => v !== null && typeof v === 'object' && 'col' in v;
  const truthy = v => v == null ? false : typeof v === 'number' ? v !== 0 : typeof v === 'boolean' ? v : typeof v === 'string' ? v.length > 0 : true;
  const num = v => typeof v === 'number' ? (Number.isFinite(v) ? v : null) : typeof v === 'boolean' ? (v ? 1 : 0) : null;
  const POW10 = [1, 10, 100, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9];
  function fixed(x, d) {
    if (!isFinite(x)) return '–';
    const dd = Math.min(9, Math.max(0, d | 0));
    const scaled = Math.abs(x) * POW10[dd];
    if (scaled >= 9e15) return x.toFixed(dd);
    const r = String(Math.round(scaled)), sign = x < 0 ? '-' : '';
    if (!dd) return sign + r;
    const digits = r.length <= dd ? '0'.repeat(dd - r.length + 1) + r : r;
    return sign + digits.slice(0, digits.length - dd) + '.' + digits.slice(digits.length - dd);
  }
  const text = v => v == null ? '–' : typeof v === 'number' ? (isFinite(v) ? String(Math.round(v)) : '–') : isCol(v) ? color.hex(v.col) : String(v);
  function same(a, b) {
    const x = num(a), y = num(b);
    if (x != null && y != null) return x === y;
    if (isCol(a) && isCol(b)) return a.col === b.col;
    return typeof a === 'string' && typeof b === 'string' && a === b;
  }

  // a result that isn't finite or lies beyond ±1e9 is unknown, as CustomExpr.eval makes it
  const bound = v => (typeof v === 'number' && !(isFinite(v) && Math.abs(v) <= MAX_NUM) ? null : v);
  function evaluate(n, s) {
    switch (n.k) {
      case 'lit': return n.v;
      case 'id': return s(n.v);
      case 'u': { const a = evaluate(n.a, s); if (n.op === '!') return !truthy(a); const x = num(a); return x == null ? null : -x; }
      case '?': return truthy(evaluate(n.c, s)) ? evaluate(n.a, s) : evaluate(n.b, s);
      case 'b': {
        if (n.op === '&&') { const a = evaluate(n.a, s); return truthy(a) ? evaluate(n.b, s) : a; }
        if (n.op === '||') { const a = evaluate(n.a, s); return truthy(a) ? a : evaluate(n.b, s); }
        const a = evaluate(n.a, s), b = evaluate(n.b, s);
        if (a == null || b == null) return null;
        if (n.op === '==') return same(a, b);
        if (n.op === '!=') return !same(a, b);
        if (n.op === '+' && (typeof a === 'string' || typeof b === 'string')) return text(a) + text(b);
        const x = num(a), y = num(b);
        if (x == null || y == null) return null;
        switch (n.op) {
          case '+': return x + y; case '-': return x - y; case '*': return x * y;
          case '/': return y === 0 ? null : x / y; case '%': return y === 0 ? null : x % y;
          case '<': return x < y; case '<=': return x <= y; case '>': return x > y; case '>=': return x >= y;
        }
        return null;
      }
      case 'f': return call(n, s);
    }
    return null;
  }

  function call(n, s) {
    if (n.fn === 'mqtt') return mqttValue(s('mqtt:' + n.args[0].v));
    const v = n.args.map(a => evaluate(a, s));
    switch (n.fn) {
      case 'known': return v[0] != null;
      case 'ifknown': return v[0] != null ? v[0] : v[1];
      case 'fmt': {
        const x = num(v[0]);
        if (x == null) return '–';
        const d = v.length > 1 ? num(v[1]) : 0;
        return fixed(x, Math.min(3, Math.max(0, Math.trunc(d == null ? 0 : d))));
      }
      case 'ramp': return ramp(v);
      case 'mix': { const t = num(v[2]); return isCol(v[0]) && isCol(v[1]) && t != null ? { col: color.mix(v[0].col, v[1].col, t) } : null; }
      case 'alpha': { const t = num(v[1]); return isCol(v[0]) && t != null ? { col: color.fade(v[0].col, t) } : null; }
      case 'tyre': { const x = num(v[0]); return x == null || Math.abs(x) > MAX_NUM ? null : tyreText(x, s('tyre_unit')); }
    }
    const x = v.map(num);
    if (x.some(e => e == null)) return null;
    switch (n.fn) {
      case 'min': return Math.min.apply(null, x);
      case 'max': return Math.max.apply(null, x);
      case 'clamp': return Math.min(Math.max(x[0], x[1]), x[2]);
      case 'lerp': return x[0] + (x[1] - x[0]) * x[2];
      case 'abs': return Math.abs(x[0]);
      case 'round': return Math.round(x[0]);
      case 'floor': return Math.floor(x[0]);
      case 'ceil': return Math.ceil(x[0]);
      case 'sqrt': return x[0] < 0 ? null : Math.sqrt(x[0]);
      case 'pow': { const r = Math.pow(x[0], x[1]); return Number.isFinite(r) ? r : null; }
      case 'sin': return Math.sin(x[0]);
      case 'cos': return Math.cos(x[0]);
      // stored in km, km/h, m and Wh/km; shown in the car's units
      case 'dist': return truthy(s('miles')) ? x[0] / KM_PER_MI : x[0];
      case 'near': return truthy(s('miles')) ? x[0] * YD_PER_M : x[0];
      case 'height': return truthy(s('miles')) ? x[0] * FT_PER_M : x[0];
      case 'consumption': return truthy(s('miles')) ? x[0] * KM_PER_MI / 10 : x[0] / 10;
    }
    return null;
  }

  // a pressure in bar as the car words it in its tyre unit
  function tyreText(bar, unit) {
    const kpa = Math.round(bar * 100);
    if (unit === 'psi') return fixed(kpa * 0.1450377, 1);
    if (unit === 'kPa') return String(kpa);
    return fixed(kpa / 100, 1);
  }

  function ramp(v) {
    const x = num(v[0]);
    if (x == null) return null;
    const xs = [], cs = [];
    for (let i = 1; i + 1 < v.length; i += 2) { const xi = num(v[i]); if (xi == null) return null; xs.push(xi); cs.push(v[i + 1]); }
    if (!xs.length) return null;
    if (cs.every(isCol)) return { col: color.rampHue(xs, cs.map(c => c.col), x) };
    const ys = cs.map(num);
    if (ys.some(y => y == null)) return null;
    if (x <= xs[0]) return ys[0];
    for (let i = 1; i < xs.length; i++) if (x <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / Math.max(1e-6, xs[i] - xs[i - 1]);
    return ys[ys.length - 1];
  }

  function collect(n, out) {
    if (!n) return;
    if (n.k === 'id') out.add(n.v);
    if (n.a) collect(n.a, out);
    if (n.b) collect(n.b, out);
    if (n.c) collect(n.c, out);
    if (n.args) n.args.forEach(a => collect(a, out));
  }
  /** The Home Assistant channels a parsed expression reads. */
  function collectMqtt(n, out) {
    if (!n) return;
    if (n.k === 'f' && n.fn === 'mqtt' && n.args[0] && n.args[0].k === 'lit') out.add(String(n.args[0].v));
    // source order: a condition comes before its branches
    if (n.c) collectMqtt(n.c, out);
    if (n.a) collectMqtt(n.a, out);
    if (n.b) collectMqtt(n.b, out);
    if (n.args) n.args.forEach(a => collectMqtt(a, out));
  }

  // "Range {ev_range_km} km" or "{trip_km:1}": text with live holes
  function template(s) {
    const parts = [];
    let i = 0;
    while (i < s.length) {
      const o = s.indexOf('{', i);
      if (o < 0) { parts.push(s.slice(i)); break; }
      if (o > i) parts.push(s.slice(i, o));
      const c = s.indexOf('}', o);
      if (c < 0) throw new ExprError('Missing “}”', o, 'brace');
      let body = s.slice(o + 1, c).trim(), dec = null;
      const m = /:(\d)$/.exec(body);
      if (m) { dec = +m[1]; body = body.slice(0, -2).trim(); }
      parts.push({ node: parse(body), dec });
      i = c + 1;
    }
    return parts;
  }

  // ── layers ─────────────────────────────────────────────────────────────────
  // kinds: geo = fixed number (layout), num / col / txt / bool = may be bound to data with "=…"
  const CAR_AUTO = {
    power: '=is_charging ? charge_power : power', soc: '=soc',
    phase: "=is_charging ? 'charging' : driving ? 'driving' : 'parked'",
    fl: '=tyre_fl', fr: '=tyre_fr', rl: '=tyre_rl', rr: '=tyre_rr',
  };
  const ROAD_AUTO = { ahead: '=hazard_ahead', hazard: '=hazard_type', distance: '=hazard_m', severity: '=hazard_sev', count: '=hazard_count' };
  const TYPES = dict({
    ring: {
      label: 'Ring gauge', icon: '◔',
      props: { cx: 'geo', cy: 'geo', r: 'geo', width: 'geo', start: 'geo', sweep: 'geo', value: 'num', color: 'col', track: 'col', glow: 'geo', motion: 'motion' },
      defaults: { cx: 80, cy: 92, r: 54, width: 12, start: 135, sweep: 270, value: '=soc / 100', color: 'glowAccent', track: 'etch', glow: 1, motion: 'liquid' },
    },
    bar: {
      label: 'Bar', icon: '▬',
      props: { x: 'geo', y: 'geo', w: 'geo', h: 'geo', value: 'num', color: 'col', track: 'col', glow: 'geo', motion: 'motion' },
      defaults: { x: 26, y: 140, w: 220, h: 9, value: '=soc / 100', color: 'glowAccent', track: 'etch', glow: 0.8, motion: 'liquid' },
    },
    text: {
      label: 'Text', icon: 'T',
      props: { x: 'geo', y: 'geo', text: 'txt', unit: 'txt', size: 'geo', weight: 'weight', align: 'align', color: 'col', motion: 'motion' },
      defaults: { x: 26, y: 100, text: '{soc}', unit: '%', size: 32, weight: 600, align: 'left', color: 'text', motion: 'number' },
    },
    spark: {
      label: 'Sparkline', icon: '〰',
      props: { x: 'geo', y: 'geo', w: 'geo', h: 'geo', value: 'num', window: 'geo', color: 'col', fill: 'geo' },
      defaults: { x: 150, y: 40, w: 200, h: 70, value: '=speed', window: 60, color: 'glowAccent', fill: 0.2 },
    },
    capsule: {
      label: 'Glass cell', icon: '▯',
      props: { x: 'geo', y: 'geo', w: 'geo', h: 'geo', level: 'num', limit: 'num', color: 'col', motion: 'motion' },
      defaults: { x: 26, y: 15, w: 62, h: 154, level: '=soc / 100', color: 'glowAccent', motion: 'liquid' },
    },
    pulse: {
      label: 'Pulse glow', icon: '◌',
      props: { cx: 'geo', cy: 'geo', r: 'geo', period: 'geo', color: 'col' },
      defaults: { cx: 80, cy: 92, r: 80, period: 2.4, color: 'glowAccent', visible: '=is_charging' },
    },
    car: {
      label: 'Car & tyres', icon: '⛟', object: true,
      props: { parts: 'parts', x: 'geo', y: 'geo', scale: 'geo', power: 'num', soc: 'num', phase: 'txt', drive: 'enum:awd,fwd,rwd', fl: 'num', fr: 'num', rl: 'num', rr: 'num' },
      defaults: Object.assign({ parts: 'body,pack,motors,tyres,pressures,flow,port', x: 258, y: 6, scale: 1, drive: 'awd' }, CAR_AUTO),
    },
    road: {
      label: 'Road & hazard', icon: '⛛', object: true,
      props: { x: 'geo', y: 'geo', scale: 'geo', ahead: 'bool', hazard: 'num', distance: 'num', severity: 'num', count: 'num', region: 'enum:eu,us,cn', side: 'enum:left,right' },
      defaults: Object.assign({ x: 0, y: 21, scale: 1, region: 'eu', side: 'left' }, ROAD_AUTO),
    },
    shape: {
      label: 'Shape', icon: '◆',
      props: { shape: 'shape', x: 'geo', y: 'geo', w: 'geo', h: 'geo', radius: 'geo', d: 'path', fill: 'col', stroke: 'col', strokeWidth: 'geo', rotate: 'num', motion: 'motion' },
      defaults: { shape: 'rect', x: 26, y: 30, w: 60, h: 6, radius: 3, fill: 'accent', strokeWidth: 2, motion: 'none' },
    },
  });
  const COMMON = { visible: 'bool', opacity: 'num' };
  const BINDABLE = new Set(['num', 'col', 'txt', 'bool']);
  const SPRUNG = dict({ ring: ['value'], bar: ['value'], capsule: ['level'], shape: ['rotate'], car: ['power'], road: ['distance'] });
  const SPRINGS = dict({ number: [15, 1], liquid: [11, 0.7] });
  // a built-in object's inputs, kept to what the car accepts
  const OBJECT_LIMITS = dict({ car: { power: [-2000, 2000], soc: [0, 100], fl: [0, 10], fr: [0, 10], rl: [0, 10], rr: [0, 10] }, road: { distance: [0, 100000] } });
  function clampInputs(L) {
    const lim = OBJECT_LIMITS[L.type];
    if (!lim) return;
    Object.keys(lim).forEach(k => { const v = num(L.vals[k]); if (v != null) L.vals[k] = Math.min(lim[k][1], Math.max(lim[k][0], v)); });
  }

  class Spring {
    constructor(v, omega, zeta) { this.value = v; this.target = v; this.velocity = 0; this.moving = false; this.omega = omega; this.zeta = zeta; }
    set(t, instant) { this.target = t; if (instant) this.snap(t); else if (t !== this.value || this.velocity !== 0) this.moving = true; }
    snap(t) { if (t === undefined) t = this.target; this.target = t; this.value = t; this.velocity = 0; this.moving = false; }
    step(dt) {
      if (!this.moving) return false;
      const h = dt / 4;
      for (let i = 0; i < 4; i++) {
        const a = -this.omega * this.omega * (this.value - this.target) - 2 * this.zeta * this.omega * this.velocity;
        this.velocity += a * h; this.value += this.velocity * h;
      }
      const s = Math.max(1, Math.abs(this.target));
      if (Math.abs(this.value - this.target) < 1e-4 * s && Math.abs(this.velocity) < 1e-3 * s) this.snap();
      return this.moving;
    }
  }

  function compileProp(raw, kind, idents) {
    if (typeof raw === 'number' && isFinite(raw)) {
      if (kind === 'geo') return { bound: false, k: Math.round(Math.min(10000, Math.max(-10000, raw)) * 1000) / 1000, raw };
      if (BINDABLE.has(kind)) return { bound: false, k: Math.min(MAX_NUM, Math.max(-MAX_NUM, raw)), raw };
    }
    if (typeof raw === 'string' && BINDABLE.has(kind)) {
      if (raw.charAt(0) === '=') { const node = parse(raw.slice(1)); collect(node, idents); return { bound: true, node, raw }; }
      if (kind === 'txt' && raw.indexOf('{') >= 0) {
        const parts = template(raw);
        parts.forEach(x => { if (typeof x !== 'string') collect(x.node, idents); });
        return { bound: parts.some(x => typeof x !== 'string'), parts, raw };
      }
    }
    return { bound: false, k: raw, raw };
  }

  function compileLayer(src, index) {
    const type = src && typeof src === 'object' ? src.type : undefined;
    const def = TYPES[type];
    const L = { src, index, type, def, props: {}, errors: {}, vals: {}, springs: {}, idents: new Set(), mqtt: new Set(), seen: false };
    if (!def) { L.error = 'Unknown layer type “' + type + '”'; L.errorCode = 'type'; return L; }
    const kinds = Object.assign({}, def.props, COMMON);
    for (const key of Object.keys(kinds)) {
      const raw = src[key];
      if (raw === undefined || raw === null) continue;
      try { L.props[key] = compileProp(raw, kinds[key], L.idents); } catch (e) { L.errors[key] = e; continue; }
      const pr = L.props[key];
      if (pr.node) collectMqtt(pr.node, L.mqtt);
      if (pr.parts) pr.parts.forEach(x => { if (typeof x !== 'string') collectMqtt(x.node, L.mqtt); });
      if (!pr.bound && !pr.parts && typeof pr.k === 'string' && (isChoice(kinds[key]) || (type === 'car' && key === 'phase'))) pr.k = pr.k.trim().toLowerCase();
    }
    L.loop = type === 'pulse';
    L.live = L.loop || type === 'capsule' || type === 'spark' || !!def.object || Object.keys(L.props).some(k => L.props[k].bound);
    L.path = type === 'shape' && src.shape === 'path' && typeof src.d === 'string' ? safePath(src.d) : null;
    return L;
  }
  const isChoice = kind => kind === 'motion' || kind === 'align' || kind === 'shape' || String(kind).indexOf('enum:') === 0;
  function safePath(d) { try { return typeof Path2D !== 'undefined' ? new Path2D(d) : null; } catch (e) { return null; } }

  // ── the widget ─────────────────────────────────────────────────────────────
  class Widget {
    constructor(doc, opts) {
      opts = opts || {};
      this.doc = doc || {};
      this.theme = opts.theme === 'day' ? 'day' : 'night';
      this.px = opts.px || 1;
      this.env = { driving: false, reduceMotion: false, stale: false, units: { distance: 'km', tyre: 'psi' } };
      const sz = SIZES[typeof this.doc.size === 'string' ? this.doc.size.trim().toLowerCase() : 'small'] || SIZES.small;
      this.w = sz[0]; this.h = sz[1];
      const layers = Array.isArray(this.doc.layers) ? this.doc.layers : [];
      this.layers = layers.slice(0, MAX_LAYERS).map(compileLayer);
      this.data = {};
      this.bound = false;
      this.loopStart = now();
      this.springsMoving = false;
      this.lookup = id => {
        if (Object.prototype.hasOwnProperty.call(this.data, id)) { const v = this.data[id]; return v === undefined ? null : v; }
        if (id === 'night') return this.theme === 'night';
        if (id === 'driving') return !!this.env.driving;
        if (id === 'parked') return !this.env.driving;
        if (id === 'pi') return Math.PI;
        if (UNIT_WORDS.indexOf(id) >= 0) return unitWord(id, this.env.units);
        const t = TOKENS[this.theme][id];
        return t === undefined ? null : { col: t };
      };
    }

    get tokens() { return TOKENS[this.theme]; }

    /** Every name the file reads that isn't a colour or a built-in: the car signals it binds. */
    signals() {
      const out = new Set();
      for (const L of this.layers) for (const id of L.idents) if (!BUILTINS.has(id) && TOKENS.night[id] === undefined) out.add(id);
      return Array.from(out);
    }

    /** The Home Assistant channels the file reads. */
    mqttChannels() {
      const out = new Set();
      for (const L of this.layers) for (const ch of L.mqtt) out.add(ch);
      return Array.from(out);
    }

    /** Every identifier the file reads, builtins and colours included. */
    names() {
      const out = new Set();
      for (const L of this.layers) for (const id of L.idents) out.add(id);
      return Array.from(out);
    }

    setTheme(theme) { this.theme = theme === 'day' ? 'day' : 'night'; if (this.bound) this.bind(this.data, false); }

    /** New readings: every expression runs here, once, so drawing only reads results. */
    bind(data, animate) {
      this.data = Object.assign({}, data);
      const instant = !animate || this.env.reduceMotion;
      for (const L of this.layers) {
        if (L.error) continue;
        for (const key of Object.keys(L.props)) {
          const p = L.props[key];
          if (p.node) L.vals[key] = safeEval(p.node, this.lookup);
          else if (p.parts) L.vals[key] = p.parts.map(x => typeof x === 'string' ? x : { v: safeEval(x.node, this.lookup), dec: x.dec });
          else L.vals[key] = p.k;
        }
        if (L.def.object) clampInputs(L);
        const motion = L.def.object ? 'number' : L.vals.motion;
        if (SPRINGS[motion]) {
          for (const key of SPRUNG[L.type] || []) this.spring(L, key, num(L.vals[key]), motion, instant || !L.seen);
          if (L.type === 'text') {
            const t = L.vals.text;
            if (Array.isArray(t)) t.forEach((x, i) => { if (typeof x !== 'string') this.spring(L, 't' + i, num(x.v), motion, instant || !L.seen); });
            else this.spring(L, 't', num(t), motion, instant || !L.seen);
          }
        }
        if (L.type === 'spark') this.record(L, num(L.vals.value), now());
        L.seen = true;
      }
      this.bound = true;
    }

    spring(L, key, v, motion, instant) {
      if (v == null) return;
      const s = L.springs[key] || (L.springs[key] = new Spring(v, SPRINGS[motion][0], SPRINGS[motion][1]));
      s.set(v, instant);
    }

    /** Earlier readings for sparklines: [{t, data}] oldest first. */
    seed(samples) {
      for (const L of this.layers) {
        if (L.type !== 'spark' || L.error || !L.props.value) continue;
        L.hist = [];
        for (const smp of samples) {
          const keep = this.data;
          this.data = smp.data;
          const v = L.props.value.node ? num(safeEval(L.props.value.node, this.lookup)) : num(L.props.value.k);
          this.data = keep;
          this.record(L, v, smp.t);
        }
      }
    }

    record(L, v, t) {
      if (v == null) return;
      const hist = L.hist || (L.hist = []);
      // about one point a second, as the car keeps them
      if (hist.length && t - hist[hist.length - 1].t < 900) return;
      hist.push({ t, v });
      const win = Math.max(10, Math.min(300, nv(num(L.vals.window), 60))) * 1000;
      while (hist.length > 2 && hist[0].t < t - win - 1000) hist.shift();
    }

    loopsAllowed() { return this.allows('ambient'); }
    allows(kind) {
      if (this.env.stale) return false;
      if (kind === 'urgent') return true;
      if (kind === 'drive') return !this.env.reduceMotion;
      return !this.env.driving && !this.env.reduceMotion;
    }
    loopKind(L) {
      if (L.error || !this.visible(L)) return null;
      if (L.loop) return 'ambient';
      if (L.type === 'car') { const ph = L.vals.phase, kw = num(L.vals.power); return (ph === 'driving' && kw) || ph === 'charging' ? 'drive' : null; }
      if (L.type === 'road') return truthy(L.vals.ahead) && Math.round(nv(num(L.vals.severity), 1)) >= 3 ? 'urgent' : null;
      return null;
    }
    hasActiveLoop() { return this.layers.some(L => this.loopKind(L) != null); }
    loopRunning() { return this.layers.some(L => { const k = this.loopKind(L); return k != null && this.allows(k); }); }

    /** Advances springs; true while anything still needs frames (springs, or a loop the rules allow). */
    step(dt) {
      let moving = false;
      for (const L of this.layers) for (const k in L.springs) if (L.springs[k].step(dt)) moving = true;
      this.springsMoving = moving;
      return moving || this.loopRunning();
    }

    /** At most this many ms between frames: 30 fps while only a loop moves. */
    get minFrameMs() { return !this.springsMoving && this.loopRunning() ? 33 : 0; }

    visible(L) { return L.props.visible ? truthy(L.vals.visible) : true; }

    hasErrors() { return this.layers.some(L => L.error || Object.keys(L.errors).length > 0); }

    // file order is drawing order, a glass cell's glass included, as on the car
    draw(g, t) {
      for (const L of this.layers) this.drawLayer(g, L, t);
    }

    drawLayer(g, L, t) {
      if (L.error || L.editorHidden || !this.visible(L)) return;
      const op = L.props.opacity ? clamp01(nv(num(L.vals.opacity), 1)) : 1;
      if (op <= 0) return;
      g.save();
      g.globalAlpha = op;
      try { DRAW[L.type](g, this, L, t); } catch (e) { /* a bad value draws nothing, like the car */ }
      g.restore();
    }

    // value readers
    n(L, key, def) { const v = num(L.vals[key]); return v == null ? def : v; }
    sprung(L, key) { const v = num(L.vals[key]); if (v == null) return null; const s = L.springs[key]; return s ? s.value : v; }
    col(L, key, def) {
      if (!(key in L.props)) return def;
      const v = L.vals[key];
      if (isCol(v)) return v.col;
      if (typeof v === 'string') {
        if (v === 'none') return null;
        const t = this.tokens[v];
        if (t !== undefined) return t;
        const c = color.parse(v);
        return c == null ? def : c;
      }
      return def;
    }
    str(L, key) {
      const v = L.vals[key];
      if (v === undefined) return '';
      if (Array.isArray(v)) {
        return v.map((x, i) => {
          if (typeof x === 'string') return x;
          if (x.v == null) return '–';
          const s = L.springs['t' + i];
          const n = num(x.v);
          if (n == null) return text(x.v);
          return fixed(s ? s.value : n, nv(x.dec, 0));
        }).join('');
      }
      if (key === 'text' && L.springs.t && num(v) != null) return fixed(L.springs.t.value, 0);
      return v == null ? '–' : text(v);
    }

    /** Layer bounds in design units, for the editor's selection and dragging. */
    bounds(L, g) {
      const s = L.src;
      switch (L.type) {
        case 'ring': { const r = numOr(s.r, 54) + numOr(s.width, 12) / 2; return [numOr(s.cx, 80) - r, numOr(s.cy, 92) - r, 2 * r, 2 * r]; }
        case 'pulse': { const r = numOr(s.r, 80); return [numOr(s.cx, 80) - r, numOr(s.cy, 92) - r, 2 * r, 2 * r]; }
        case 'text': {
          const size = numOr(s.size, 24), x = numOr(s.x, 26), y = numOr(s.y, 100);
          g.font = numOr(s.weight, 600) + ' ' + size + 'px ' + FONT;
          const tw = g.measureText(this.str(L, 'text')).width;
          const u = this.str(L, 'unit');
          g.font = '500 ' + (size * 0.5) + 'px ' + FONT;
          const w = tw + (u ? size * 0.08 + g.measureText(u).width : 0);
          const x0 = s.align === 'center' ? x - w / 2 : s.align === 'right' ? x - w : x;
          return [x0, y - size * 0.8, w, size];
        }
        case 'car': { const k = objScale(s.scale), src = carRegion(L); return [numOr(s.x, 0), numOr(s.y, 0), src[2] * k, src[3] * k]; }
        case 'road': { const k = objScale(s.scale), src = roadRegion(L); return [numOr(s.x, 0), numOr(s.y, 0), src[2] * k, src[3] * k]; }
        default: return [numOr(s.x, 0), numOr(s.y, 0), numOr(s.w, 40), numOr(s.h, 40)];
      }
    }
  }

  function unitWord(id, units) {
    const miles = !!units && units.distance === 'mi';
    switch (id) {
      case 'miles': return miles;
      case 'dist_unit': return miles ? 'mi' : 'km';
      case 'speed_unit': return miles ? 'mph' : 'km/h';
      case 'near_unit': return miles ? 'yd' : 'm';
      case 'height_unit': return miles ? 'ft' : 'm';
      case 'consumption_unit': return miles ? 'kWh/100 mi' : 'kWh/100 km';
      case 'tyre_unit': return units && units.tyre === 'kpa' ? 'kPa' : units && units.tyre === 'bar' ? 'bar' : 'psi';
    }
    return null;
  }

  function safeEval(node, lookup) { try { return bound(evaluate(node, lookup)); } catch (e) { return null; } }
  function now() { return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now(); }

  // ── drawing (design units; matches GlassCustomView) ───────────────────────
  const GLOW_W = [10, 6, 3], GLOW_A = [0.07, 0.10, 0.16];
  const rad = d => d * Math.PI / 180;

  function arc(g, cx, cy, r, a0, sweep) { g.beginPath(); g.arc(cx, cy, Math.max(0, r), rad(a0), rad(a0 + sweep), false); g.stroke(); }
  function rrect(g, x, y, w, h, r) {
    r = Math.max(0, Math.min(r, w / 2, h / 2));
    g.beginPath();
    g.moveTo(x + r, y); g.lineTo(x + w - r, y); g.arcTo(x + w, y, x + w, y + r, r);
    g.lineTo(x + w, y + h - r); g.arcTo(x + w, y + h, x + w - r, y + h, r);
    g.lineTo(x + r, y + h); g.arcTo(x, y + h, x, y + h - r, r);
    g.lineTo(x, y + r); g.arcTo(x, y, x + r, y, r);
    g.closePath();
  }

  const DRAW = dict({
    ring(g, W, L) {
      const t = W.tokens, night = W.theme === 'night';
      const cx = W.n(L, 'cx', 80), cy = W.n(L, 'cy', 92), r = W.n(L, 'r', 54), w = Math.min(200, Math.max(0, W.n(L, 'width', 12)));
      const start = W.n(L, 'start', 135), sweep = W.n(L, 'sweep', 270);
      const v = clamp01(nv(W.sprung(L, 'value'), 0));
      g.lineCap = 'round';
      const track = W.col(L, 'track', t.etch);
      if (track != null) { g.lineWidth = w; g.strokeStyle = color.css(track); arc(g, cx, cy, r, start, sweep); }
      if (v <= 0.002) return;
      const c = W.col(L, 'color', t.glowAccent);
      if (c == null) return;
      const glow = Math.min(4, Math.max(0, W.n(L, 'glow', 1))) * (night ? 1 : 0.6);
      for (let j = 0; j < GLOW_W.length; j++) { g.lineWidth = w + GLOW_W[j]; g.strokeStyle = color.css(color.fade(c, GLOW_A[j] * glow)); arc(g, cx, cy, r, start, sweep * v); }
      g.lineWidth = w; g.strokeStyle = color.css(c); arc(g, cx, cy, r, start, sweep * v);
      g.lineWidth = Math.max(1, w * 0.28); g.strokeStyle = color.css(color.fade(color.lighten(c, 0.6), 0.55));
      arc(g, cx, cy, r - w * 0.18, start, sweep * v);
    },

    bar(g, W, L) {
      const t = W.tokens, night = W.theme === 'night';
      const x = W.n(L, 'x', 26), y = W.n(L, 'y', 140), w = W.n(L, 'w', 220), h = W.n(L, 'h', 9), rr = h / 2;
      const v = clamp01(nv(W.sprung(L, 'value'), 0));
      const track = W.col(L, 'track', t.etch);
      if (track != null) { g.fillStyle = color.css(track); rrect(g, x, y, w, h, rr); g.fill(); }
      if (v <= 0.002) return;
      const c = W.col(L, 'color', t.glowAccent);
      if (c == null) return;
      const fw = Math.max(h, w * v), glow = Math.min(4, Math.max(0, W.n(L, 'glow', 1))) * (night ? 1 : 0.6);
      for (let j = 0; j < GLOW_W.length; j++) {
        const e = GLOW_W[j] / 2;
        g.fillStyle = color.css(color.fade(c, GLOW_A[j] * glow)); rrect(g, x - e, y - e, fw + 2 * e, h + 2 * e, rr + e); g.fill();
      }
      g.fillStyle = color.css(c); rrect(g, x, y, fw, h, rr); g.fill();
      const hw = fw - rr * 1.2;
      if (hw > 0) { g.fillStyle = color.css(color.fade(color.lighten(c, 0.6), 0.5)); rrect(g, x + rr * 0.6, y + h * 0.18, hw, h * 0.24, h * 0.12); g.fill(); }
    },

    text(g, W, L) {
      const t = W.tokens;
      const x = W.n(L, 'x', 26), y = W.n(L, 'y', 100), size = Math.min(300, Math.max(1, W.n(L, 'size', 24)));
      const wt = num(L.vals.weight), weight = wt == null ? 600 : wt >= 700 ? 700 : wt >= 600 ? 600 : 500;
      const c = nv(W.col(L, 'color', t.text), t.text);
      const s = W.str(L, 'text'), u = W.str(L, 'unit');
      g.font = weight + ' ' + size + 'px ' + FONT;
      const tw = g.measureText(s).width;
      g.font = '500 ' + (size * 0.5) + 'px ' + FONT;
      const uw = u ? g.measureText(u).width : 0, gap = u ? size * 0.08 : 0;
      const total = tw + gap + uw, align = L.vals.align;
      const x0 = align === 'center' ? x - total / 2 : align === 'right' ? x - total : x;
      g.textBaseline = 'alphabetic'; g.textAlign = 'left';
      g.font = weight + ' ' + size + 'px ' + FONT; g.fillStyle = color.css(c); g.fillText(s, x0, y);
      if (u) { g.font = '500 ' + (size * 0.5) + 'px ' + FONT; g.fillStyle = color.css(color.fade(c, 0.5)); g.fillText(u, x0 + tw + gap, y); }
    },

    spark(g, W, L) {
      const t = W.tokens, night = W.theme === 'night';
      const hist = L.hist || [];
      if (hist.length < 2 || typeof Path2D === 'undefined') return;
      const x = W.n(L, 'x', 150), y = W.n(L, 'y', 40), w = W.n(L, 'w', 200), h = W.n(L, 'h', 70);
      const win = Math.max(10, Math.min(300, W.n(L, 'window', 60))) * 1000;
      const end = hist[hist.length - 1].t, from = end - win;
      const pts = hist.filter(p => p.t >= from - 1000);
      if (pts.length < 2) return;
      let lo = 0, hi = 0;
      for (const p of pts) { lo = Math.min(lo, p.v); hi = Math.max(hi, p.v); }
      if (hi - lo < 1e-3) hi = lo + 1;
      const px = p => x + Math.max(0, (p.t - from) / win) * w, py = v => y + h - (v - lo) / (hi - lo) * h;
      const zero = Math.min(y + h, Math.max(y, py(0)));
      const c = nv(W.col(L, 'color', t.glowAccent), t.glowAccent);
      const line = new Path2D();
      pts.forEach((p, i) => { if (i) line.lineTo(px(p), py(p.v)); else line.moveTo(px(p), py(p.v)); });
      const fill = clamp01(W.n(L, 'fill', 0.2));
      if (fill > 0) {
        const area = new Path2D(line);
        area.lineTo(px(pts[pts.length - 1]), zero); area.lineTo(px(pts[0]), zero); area.closePath();
        const gr = g.createLinearGradient(0, y, 0, y + h);
        gr.addColorStop(0, color.css(color.fade(c, fill))); gr.addColorStop(1, color.css(color.clear(c)));
        g.fillStyle = gr; g.fill(area);
      }
      g.lineJoin = 'round'; g.lineCap = 'round';
      g.strokeStyle = color.css(color.fade(c, night ? 0.16 : 0.1)); g.lineWidth = 6; g.stroke(line);
      g.strokeStyle = color.css(c); g.lineWidth = 2.2; g.stroke(line);
      if (lo < 0) { g.strokeStyle = color.css(color.fade(t.text, 0.18)); g.lineWidth = 1; g.beginPath(); g.moveTo(x, zero); g.lineTo(x + w, zero); g.stroke(); }
    },

    pulse(g, W, L, t) {
      const night = W.theme === 'night';
      const cx = W.n(L, 'cx', 80), cy = W.n(L, 'cy', 92), r = Math.max(0.1, W.n(L, 'r', 80));
      const c = W.col(L, 'color', W.tokens.glowAccent);
      if (c == null) return;
      const period = Math.max(0.6, W.n(L, 'period', 2.4));
      let a = 0.6, s = 1;
      if (W.loopsAllowed()) {
        const k = 0.5 + 0.5 * Math.sin((t - W.loopStart) / 1000 / period * 2 * Math.PI);
        a = 0.35 + 0.65 * k; s = 0.9 + 0.1 * k;
      }
      const gr = g.createRadialGradient(cx, cy, 0, cx, cy, r * s);
      gr.addColorStop(0, color.css(color.alpha(c, 0.5))); gr.addColorStop(0.5, color.css(color.alpha(c, 0.2))); gr.addColorStop(1, color.css(color.clear(c)));
      g.globalAlpha *= a * (night ? 1 : 0.7);
      g.fillStyle = gr; g.beginPath(); g.arc(cx, cy, r * s, 0, Math.PI * 2); g.fill();
    },

    shape(g, W, L) {
      const x = W.n(L, 'x', 0), y = W.n(L, 'y', 0), w = W.n(L, 'w', 40), h = W.n(L, 'h', 40);
      const rot = nv(W.sprung(L, 'rotate'), 0);
      const fill = W.col(L, 'fill', null), stroke = W.col(L, 'stroke', null), sw = Math.min(100, Math.max(0, W.n(L, 'strokeWidth', 2)));
      if (rot) { g.translate(x + w / 2, y + h / 2); g.rotate(rad(rot)); g.translate(-(x + w / 2), -(y + h / 2)); }
      const kind = L.vals.shape || 'rect';
      let p = null;
      if (kind === 'circle') { g.beginPath(); g.ellipse(x + w / 2, y + h / 2, Math.max(0, w / 2), Math.max(0, h / 2), 0, 0, Math.PI * 2); }
      else if (kind === 'path') { if (!L.path) return; g.translate(x, y); p = L.path; }
      else rrect(g, x, y, w, h, Math.max(0, W.n(L, 'radius', 0)));
      if (fill != null) { g.fillStyle = color.css(fill); if (p) g.fill(p); else g.fill(); }
      if (stroke != null && sw > 0) { g.strokeStyle = color.css(stroke); g.lineWidth = sw; if (p) g.stroke(p); else g.stroke(); }
    },

    capsule(g, W, L) { cellBack(g, W, L); cellLive(g, W, L); cellFront(g, W, L); },
    car(g, W, L, t) { objectIn(g, W, L, carRegion(L), o => drawCar(o, W, L, t)); },
    road(g, W, L, t) {
      // a clear road draws nothing; keeping right mirrors the scene, as GlassRoad does
      if (!truthy(L.vals.ahead)) return;
      objectIn(g, W, L, roadRegion(L), o => { if (L.vals.side === 'right') { o.translate(400, 0); o.scale(-1, 1); } drawRoad(o, W, L, t); });
    },
  });

  // ── glass cell: CellPainter, drawn in Canvas 2D ───────────────────────────
  const WHITE = 0xFFFFFFFF, BLACK = 0xFF000000, INK_DAY = 0xFF0B281E, INK_TEXT = 0xFF0B1A15;
  const BOLT = 'M1.3 -4.6 L-2.4 0.7 H0 L-1.2 4.6 L2.5 -0.8 H0.2 Z';
  const boltPath = typeof Path2D !== 'undefined' ? new Path2D(BOLT) : null;

  function cellGeom(W, L) {
    const x = W.n(L, 'x', 26), y = W.n(L, 'y', 15), w = Math.max(8, W.n(L, 'w', 62)), h = Math.max(40, W.n(L, 'h', 154));
    const rx = w / 2, cx = x + rx, ry = rx * 0.3, nubRx = rx * 0.34, nubRy = nubRx * 0.3;
    const yNub = y + nubRy, yCapTop = yNub + 6, yA = yCapTop + 12, yBot = y + h - ry, yB = yBot - 9, lrx = rx - 3;
    return { x, rx, cx, ry, nubRx, nubRy, yNub, yCapTop, yA, yBot, yB, lrx: Math.max(0.5, lrx), lry: ry * Math.max(0.5, lrx) / rx, levelY: f => yB - clamp01(f) * (yB - yA - 2) };
  }
  function lin(g, x0, y0, x1, y1, cols, stops) {
    const gr = g.createLinearGradient(x0, y0, x1, y1);
    cols.forEach((c, i) => gr.addColorStop(stops ? stops[i] : i / (cols.length - 1), color.css(c)));
    return gr;
  }
  function ovalFill(g, cx, cy, rx, ry, cols, stops, alpha) {
    g.save();
    g.translate(cx, cy); g.scale(Math.max(1e-3, rx), Math.max(1e-3, ry));
    const gr = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    cols.forEach((c, i) => gr.addColorStop(stops[i], color.css(c)));
    if (alpha != null) g.globalAlpha *= alpha;
    g.fillStyle = gr; g.beginPath(); g.arc(0, 0, 1, 0, Math.PI * 2); g.fill();
    g.restore();
  }
  function band(g, cx, rx, ry, y0, y1) {
    g.beginPath(); g.moveTo(cx - rx, y0); g.lineTo(cx - rx, y1);
    g.ellipse(cx, y1, rx, ry, 0, Math.PI, 0, true);
    g.lineTo(cx + rx, y0); g.closePath();
  }
  function frontArc(g, cx, rx, ry, y) { g.beginPath(); g.ellipse(cx, y, rx, ry, 0, 0, Math.PI, false); }

  function cellBack(g, W, L) {
    const night = W.theme === 'night', sh = SHADOW[W.theme][0], shA = SHADOW[W.theme][1], c = cellGeom(W, L);
    ovalFill(g, c.cx + 8, (c.yA + c.yBot) / 2 + 9, c.rx * 1.3, (c.yBot - c.yA) / 2 + 16,
      [color.alpha(sh, shA * 0.8), color.alpha(sh, shA * 0.36), color.clear(sh)], [0, 0.55, 1]);
    ovalFill(g, c.cx + 5, c.yBot + 3, c.rx * 1.18, c.ry * 1.25, [color.alpha(sh, Math.min(1, shA * 1.3)), color.clear(sh)], [0, 1]);
    g.fillStyle = lin(g, c.x, 0, c.x + c.rx * 2, 0, night
      ? [color.alpha(WHITE, 0.15), color.alpha(WHITE, 0.05), color.alpha(BLACK, 0.16), color.alpha(WHITE, 0.05), color.alpha(WHITE, 0.12)]
      : [color.alpha(INK_DAY, 0.14), color.alpha(INK_DAY, 0.05), color.alpha(WHITE, 0.4), color.alpha(INK_DAY, 0.05), color.alpha(INK_DAY, 0.15)],
      [0, night ? 0.14 : 0.16, 0.5, night ? 0.86 : 0.84, 1]);
    g.fillRect(c.x, c.yA, c.rx * 2, c.yB - c.yA);
  }

  function cellLive(g, W, L) {
    const night = W.theme === 'night', c = cellGeom(W, L);
    const col = nv(W.col(L, 'color', W.tokens.glowAccent), W.tokens.glowAccent);
    const level = clamp01(nv(W.sprung(L, 'level'), 0));
    const sy = c.levelY(level), hh = c.yB - sy;
    if (level > 0.001) {
      ovalFill(g, c.cx, sy + hh / 2, c.rx * 1.35 + 12, hh / 2 + 24,
        [color.alpha(col, night ? 0.36 : 0.2), color.alpha(col, night ? 0.13 : 0.07), color.clear(col)], [0, 0.45, 1], Math.min(1, level * 5));
    }
    if (level > 0.006) {
      g.save();
      g.beginPath(); g.rect(c.cx - c.lrx, sy, c.lrx * 2, c.yB - sy); g.clip();
      g.fillStyle = lin(g, c.cx - c.lrx, 0, c.cx + c.lrx, 0, night
        ? [color.darken(col, 0.5), color.darken(col, 0.18), color.lighten(col, 0.38), col, color.darken(col, 0.22), color.darken(col, 0.55)]
        : [color.darken(col, 0.34), color.darken(col, 0.1), color.lighten(col, 0.3), col, color.darken(col, 0.15), color.darken(col, 0.4)],
        [0, 0.12, 0.3, 0.44, 0.8, 1]);
      g.fillRect(c.cx - c.lrx, sy, c.lrx * 2, c.yB - sy);
      g.fillStyle = lin(g, 0, sy, 0, c.yB, [color.alpha(WHITE, 0.26), color.clear(WHITE), color.alpha(BLACK, night ? 0.3 : 0.14)], [0, 0.22, 1]);
      g.fillRect(c.cx - c.lrx, sy, c.lrx * 2, c.yB - sy);
      ovalFill(g, c.cx - c.lrx * 0.1, sy + 12, c.lrx * 1.1, 16, [color.alpha(color.lighten(col, 0.65), 0.5), color.clear(color.lighten(col, 0.65))], [0, 1]);
      g.restore();
      const ag = Math.abs((L.springs.level && L.springs.level.velocity) || 0);
      const ry = c.lry + Math.min(2.2, ag * 2);
      g.save();
      g.beginPath(); g.ellipse(c.cx, sy, c.lrx, ry, 0, 0, Math.PI * 2); g.clip();
      ovalFill(g, c.cx - c.lrx * 0.28, sy - ry * 0.24, c.lrx * 1.5, ry * 1.5,
        [color.lighten(col, night ? 0.62 : 0.5), color.lighten(col, night ? 0.28 : 0.2), col], [0, 0.55, 1], 245 / 255);
      g.restore();
      g.strokeStyle = color.css(color.alpha(color.lighten(col, 0.78), 0.85)); g.lineWidth = 1;
      g.beginPath(); g.ellipse(c.cx, sy, Math.max(0, c.lrx - 0.5), Math.max(0, ry - 0.3), 0, 0, Math.PI, false); g.stroke();
    }
    const limit = W.sprung(L, 'limit');
    if (limit != null && limit > 0.01 && limit < 0.99) {
      const yy = c.levelY(limit), rr = c.rx + 1.2, ry = c.ry * rr / c.rx;
      g.setLineDash([2, 2]); g.lineWidth = 0.8; g.strokeStyle = color.css(color.alpha(night ? WHITE : INK_TEXT, 0.35));
      g.beginPath(); g.ellipse(c.cx, yy, rr, ry, 0, Math.PI, 2 * Math.PI, false); g.stroke();
      g.setLineDash([]); g.lineWidth = 1.5; g.strokeStyle = color.css(color.alpha(night ? WHITE : INK_TEXT, night ? 0.92 : 0.8));
      g.beginPath(); g.ellipse(c.cx, yy, rr, ry, 0, 0, Math.PI, false); g.stroke();
    }
  }

  function cellFront(g, W, L) {
    const night = W.theme === 'night', c = cellGeom(W, L);
    const metal = night ? [0xFF1B2221, 0xFF3A4543, 0xFF8E9C98, 0xFF56625F, 0xFF29322F, 0xFF4B5654, 0xFF171D1C]
      : [0xFF8C9793, 0xFFC4CDC8, WHITE, 0xFFD6DDD9, 0xFFA8B2AE, 0xFFCCD4D0, 0xFF86908C];
    const stops = [0, 0.1, 0.26, 0.37, 0.64, 0.87, 1];
    const ink = night ? 0xFF050807 : 0xFF27322E;
    const face = (l, t, r, b) => lin(g, l, t, r, b, night ? [0xFFA4B1AD, 0xFF5A6663, 0xFF2B3331] : [WHITE, 0xFFDCE2DF, 0xFFAAB4B0], [0, 0.5, 1]);
    const left = c.x, right = c.x + c.rx * 2;
    const line = (x0, y0, x1, y1, w, col) => { g.lineWidth = w; g.strokeStyle = color.css(col); g.beginPath(); g.moveTo(x0, y0); g.lineTo(x1, y1); g.stroke(); };
    line(left + 0.7, c.yA, left + 0.7, c.yB, 1.2, color.alpha(WHITE, night ? 0.45 : 1));
    line(right - 0.7, c.yA, right - 0.7, c.yB, 1, color.alpha(night ? WHITE : INK_DAY, night ? 0.14 : 0.2));
    g.fillStyle = lin(g, 0, c.yA + 5, 0, c.yB - 1, [color.clear(WHITE), color.alpha(WHITE, 0.75), color.alpha(WHITE, 0.3), color.clear(WHITE)], [0, 0.1, 0.6, 1]);
    rrect(g, left + c.rx * 0.4, c.yA + 5, 3, c.yB - c.yA - 6, 1.5); g.fill();
    g.fillStyle = lin(g, left + c.rx * 0.2, 0, left + c.rx * 0.75, 0, [color.clear(WHITE), color.alpha(WHITE, 0.12), color.clear(WHITE)]);
    g.fillRect(left + c.rx * 0.2, c.yA + 3, c.rx * 0.55, c.yB - c.yA - 3);
    g.fillStyle = color.css(color.alpha(WHITE, night ? 0.16 : 0.5));
    rrect(g, left + c.rx * 1.62, c.yA + 8, 1.5, c.yB - c.yA - 14, 0.75); g.fill();

    g.fillStyle = lin(g, left, 0, right, 0, metal, stops); band(g, c.cx, c.rx, c.ry, c.yB, c.yBot); g.fill();
    g.lineWidth = 0.9; g.strokeStyle = color.css(color.alpha(WHITE, night ? 0.3 : 0.9)); frontArc(g, c.cx, c.rx, c.ry, c.yB); g.stroke();
    g.lineWidth = 0.8; g.strokeStyle = color.css(color.alpha(ink, 0.55)); frontArc(g, c.cx, c.rx, c.ry, c.yB + 1.2); g.stroke();

    g.fillStyle = lin(g, left, 0, right, 0, metal, stops); band(g, c.cx, c.rx, c.ry, c.yCapTop, c.yA); g.fill();
    g.lineWidth = 1.6; g.strokeStyle = color.css(color.alpha(ink, 0.8)); frontArc(g, c.cx, c.rx, c.ry, c.yA); g.stroke();
    g.fillStyle = face(c.cx - c.rx, c.yCapTop - c.ry, c.cx + c.rx, c.yCapTop + c.ry);
    g.beginPath(); g.ellipse(c.cx, c.yCapTop, c.rx, c.ry, 0, 0, Math.PI * 2); g.fill();
    g.lineWidth = 1; g.strokeStyle = color.css(color.alpha(night ? WHITE : INK_DAY, 0.14));
    g.beginPath(); g.ellipse(c.cx, c.yCapTop, c.rx * 0.8, c.ry * 0.8, 0, 0, Math.PI * 2); g.stroke();
    g.lineWidth = 0.9; g.strokeStyle = color.css(color.alpha(WHITE, night ? 0.45 : 1));
    g.beginPath(); g.ellipse(c.cx, c.yCapTop, Math.max(0, c.rx - 0.6), Math.max(0, c.ry - 0.3), 0, Math.PI, 2 * Math.PI, false); g.stroke();

    g.fillStyle = lin(g, c.cx - c.nubRx, 0, c.cx + c.nubRx, 0, metal, stops); band(g, c.cx, c.nubRx, c.nubRy, c.yNub, c.yCapTop); g.fill();
    g.fillStyle = face(c.cx - c.nubRx, c.yNub - c.nubRy, c.cx + c.nubRx, c.yNub + c.nubRy);
    g.beginPath(); g.ellipse(c.cx, c.yNub, c.nubRx, c.nubRy, 0, 0, Math.PI * 2); g.fill();

    const iy = c.yCapTop + c.ry + (c.yA - c.yCapTop) / 2;
    if (boltPath) {
      g.save(); g.translate(c.cx + 0.6, iy + 0.7); g.fillStyle = color.css(color.alpha(WHITE, night ? 0.18 : 0.8)); g.fill(boltPath); g.restore();
      g.save(); g.translate(c.cx, iy); g.fillStyle = color.css(color.alpha(ink, night ? 0.75 : 0.6)); g.fill(boltPath); g.restore();
    }
  }

  // ── built-in objects (car, road): the browser's stand-in for the native drawing ─
  // On the car these layers draw the Power & tyres and RoadSense widgets' own graphics; this approximates them
  // in the same design units so the editor reacts to data. Boxes [x, y, w, h] are the native objectBounds:
  // a layer clips to the union of its parts' boxes and its x, y and scale place that crop's top-left.
  const CAR_PART_NAMES = ['body', 'pack', 'motors', 'tyres', 'pressures', 'flow', 'port'];
  const PART_BOX = dict({
    body: [158, 45, 233, 94], pack: [235, 76, 82, 38], motors: [167, 51, 214, 76], tyres: [155, 6, 246, 172],
    pressures: [198, 23, 152, 138], flow: [191, 45, 166, 94], port: [267, 101, 14, 44],
  });
  const ROAD_LEFT = [0, 21, 357, 163], ROAD_RIGHT = [43, 21, 357, 163];
  // GlassPowerView's layout: centre line, front and rear axles, wheel rows, wheel size, pack
  const CY = 92, XF = 350, XR = 198, YT = 40, YB = 144, TL = 44, TWD = 22, BX0 = 236, BX1 = 312, BH = 30, PACK_CX = 274;
  const BODY_PATH = 'M180 47 H352 C378 47 386 67.25 386 92 C386 116.75 378 137 352 137 H180 C164 137 160 119 160 92 C160 65 164 47 180 47 Z' +
    ' M310 56.9 Q324 92 310 127.1 M228 59.6 Q218 92 228 124.4';
  const bodyPath = typeof Path2D !== 'undefined' ? new Path2D(BODY_PATH) : null;

  function partsOf(src) {
    const raw = src && src.parts != null ? String(src.parts) : (src && src.part === 'pack' ? 'pack' : CAR_PART_NAMES.join(','));
    const set = new Set(raw.split(',').map(x => x.trim()).filter(x => CAR_PART_NAMES.includes(x)));
    if (!set.size) CAR_PART_NAMES.forEach(x => set.add(x));
    return set;
  }
  function carRegion(L) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    partsOf(L.src).forEach(p => { const b = PART_BOX[p]; x0 = Math.min(x0, b[0]); y0 = Math.min(y0, b[1]); x1 = Math.max(x1, b[0] + b[2]); y1 = Math.max(y1, b[1] + b[3]); });
    return [x0, y0, x1 - x0, y1 - y0];
  }
  const roadRegion = L => (L.props.side && L.props.side.k === 'right' ? ROAD_RIGHT : ROAD_LEFT);
  const objScale = v => Math.min(8, Math.max(0.05, numOr(v, 1)));
  const PICTO = {
    0: 'M-23 9 H-14 C-8 9 -6 -9 0 -9 C6 -9 8 9 14 9 H23 V14 H-23 Z',
    1: 'M-23 7 H-11 L-8 15 H8 L11 7 H23 V12 H14 L11 20 H-11 L-14 12 H-23 Z M0 -8 A9 9 0 1 1 -0.01 -8 Z M0 -3 A3.4 3.4 0 1 0 0.01 -3 Z',
    3: 'M-23 9 C-20 9 -19 -5 -12.5 -5 C-6 -5 -6 9 0 9 C6 9 6 -5 12.5 -5 C19 -5 20 9 23 9 V14 H-23 Z',
    u: 'M-3.2 -14 H3.2 L2.2 6 H-2.2 Z M0 9.5 A3.4 3.4 0 1 1 -0.01 9.5 Z',
  };
  const pictoPath = {};
  const picto = k => pictoPath[k] || (pictoPath[k] = new Path2D(PICTO[k] || PICTO.u));

  /** Draws an object's crop offscreen at the layer's resolution, then places it like the native clip. */
  function objectIn(g, W, L, src, draw) {
    if (typeof document === 'undefined') return;
    const k = objScale(L.vals.scale), x = W.n(L, 'x', 0), y = W.n(L, 'y', 0);
    const px = Math.min(8, (W.px || 1) * k);
    const cw = Math.max(1, Math.ceil(src[2] * px)), ch = Math.max(1, Math.ceil(src[3] * px));
    const c = L.off || (L.off = document.createElement('canvas'));
    if (c.width !== cw || c.height !== ch) { c.width = cw; c.height = ch; }
    const o = c.getContext('2d');
    o.setTransform(1, 0, 0, 1, 0, 0);
    o.clearRect(0, 0, cw, ch);
    o.setTransform(px, 0, 0, px, -src[0] * px, -src[1] * px);
    o.save();
    draw(o);
    o.restore();
    g.drawImage(c, x, y, src[2] * k, src[3] * k);
  }

  function arrowHead(o, x, y, dx, dy, size) {
    const n = Math.hypot(dx, dy) || 1, ux = dx / n, uy = dy / n;
    o.beginPath();
    o.moveTo(x - ux * size - uy * size * 0.8, y - uy * size + ux * size * 0.8);
    o.lineTo(x, y);
    o.lineTo(x - ux * size + uy * size * 0.8, y - uy * size - ux * size * 0.8);
    o.stroke();
  }

  function tyre(o, W, cx, cy, p, lo, hi) {
    const t = W.tokens, night = W.theme === 'night', w = TL, h = TWD, x = cx - w / 2, y = cy - h / 2;
    const bad = p != null && (p < lo || p > hi);
    if (bad) {
      const gr = o.createRadialGradient(cx, cy, 2, cx, cy, w * 0.75);
      gr.addColorStop(0, color.css(color.alpha(t.glowWarn, 0.5))); gr.addColorStop(1, color.css(color.clear(t.glowWarn)));
      o.fillStyle = gr; o.fillRect(x - 20, y - 20, w + 40, h + 40);
    }
    const body = o.createLinearGradient(0, y, 0, y + h);
    body.addColorStop(0, night ? '#4A5250' : '#56605D'); body.addColorStop(0.45, night ? '#262C2B' : '#2E3634'); body.addColorStop(1, night ? '#0F1312' : '#161C1A');
    o.fillStyle = body; rrect(o, x, y, w, h, 9); o.fill();
    o.strokeStyle = color.css(color.alpha(0xFFFFFFFF, night ? 0.16 : 0.22)); o.lineWidth = 1;
    for (let i = 1; i < 9; i++) { const tx = x + 5 + i * (w - 10) / 9; o.beginPath(); o.moveTo(tx, y + 4); o.lineTo(tx - 1.5, y + h - 4); o.stroke(); }
    if (p == null) { o.setLineDash([3, 3]); o.strokeStyle = color.css(color.fade(t.text, 0.5)); rrect(o, x + 0.5, y + 0.5, w - 1, h - 1, 9); o.stroke(); o.setLineDash([]); }
    else if (bad) { o.strokeStyle = color.css(t.glowWarn); o.lineWidth = 1.6; rrect(o, x + 0.5, y + 0.5, w - 1, h - 1, 9); o.stroke(); }
  }

  // pressures in the car's tyre unit, as the built-in widget shows them
  function pressure(o, W, x, y, p, align, lo, hi) {
    const t = W.tokens;
    const bad = p != null && (p < lo || p > hi);
    const unit = unitWord('tyre_unit', W.env.units);
    const txt = p == null ? '–' : tyreText(p, unit);
    o.textBaseline = 'alphabetic';
    if (!bad) {
      o.font = '600 15px ' + FONT; o.fillStyle = color.css(t.text2); o.textAlign = align; o.fillText(txt, x, y); o.textAlign = 'left';
      return;
    }
    o.font = '600 22px ' + FONT; const nw = o.measureText(txt).width;
    o.font = '500 11px ' + FONT; const uw = o.measureText(unit).width;
    const w = nw + uw + 18, x0 = align === 'right' ? x - w + 6 : x - 4;
    o.fillStyle = color.css(color.alpha(W.theme === 'night' ? 0xFF0B1210 : 0xFFFFFFFF, 0.85));
    rrect(o, x0, y - 21, w, 27, 13.5); o.fill();
    o.strokeStyle = color.css(t.glowWarn); o.lineWidth = 1.5; rrect(o, x0, y - 21, w, 27, 13.5); o.stroke();
    o.font = '600 22px ' + FONT; o.fillStyle = color.css(t.warn); o.fillText(txt, x0 + 8, y);
    o.font = '500 11px ' + FONT; o.fillStyle = color.css(color.fade(t.warn, 0.8)); o.fillText(unit, x0 + 10 + nw, y);
  }

  function drawCar(o, W, L, t) {
    const tk = W.tokens, night = W.theme === 'night';
    const parts = partsOf(L.src), has = p => parts.has(p);
    const phase = L.vals.phase, raw = num(L.vals.power), kw = nv(W.sprung(L, 'power'), 0);
    const regen = phase === 'driving' && raw != null && raw < 0;
    const moving = phase === 'driving' && raw != null && raw !== 0;
    const charging = phase === 'charging';
    const drive = L.vals.drive || 'awd';
    const rearOn = moving && drive !== 'fwd', frontOn = moving && drive !== 'rwd';
    const flow = regen ? tk.regen : tk.glowAccent;
    const line = color.fade(tk.text, night ? 0.2 : 0.26);
    // the tyre band is the car's own; an unknown charge binds as 50, as on the car
    const band = k => { const v = num(W.data[k]); return v == null || Math.abs(v) > MAX_NUM ? null : Math.min(10, Math.max(0, v)); };
    const lo = nv(band('tyre_low_bar'), 2.34), hi = nv(band('tyre_high_bar'), 3.1);
    const soc = nv(num(L.vals.soc), 50);
    const top = YT + TWD / 2 + 3, bottom = YB - TWD / 2 - 3;

    o.lineCap = 'round'; o.lineJoin = 'round';
    o.lineWidth = 1.4; o.strokeStyle = color.css(line);
    if (has('body') && bodyPath) {
      o.stroke(bodyPath);
      o.beginPath(); o.moveTo(387, 86); o.lineTo(391, 92); o.lineTo(387, 98); o.stroke();
    }

    // axles and pack-to-motor links; arrows point where the energy goes
    const links = [[XR, rearOn, XR + 8, BX0], [XF, frontOn, XF - 8, BX1]];
    if (has('flow')) {
      for (const lk of links) {
        const ax = lk[0], on = lk[1], mx = lk[2], pxx = lk[3];
        o.lineWidth = on ? 2 : 1.4; o.strokeStyle = color.css(on ? color.fade(flow, 0.9) : line);
        o.beginPath(); o.moveTo(ax, top); o.lineTo(ax, bottom); o.stroke();
        if (has('pack')) { o.beginPath(); o.moveTo(pxx, CY); o.lineTo(mx, CY); o.stroke(); }
        if (on) {
          if (regen) { if (has('pack')) arrowHead(o, pxx - Math.sign(pxx - mx) * 2, CY, pxx - mx, 0, 5); arrowHead(o, ax, CY - 18, 0, 1, 5); arrowHead(o, ax, CY + 18, 0, -1, 5); }
          else { if (has('pack')) arrowHead(o, mx, CY, mx - pxx, 0, 5); arrowHead(o, ax, top + 2, 0, -1, 5); arrowHead(o, ax, bottom - 2, 0, 1, 5); }
        }
      }
    }
    // marching dots: faster with more power (a drive loop: runs parked or driving, not with reduce motion)
    if ((moving || charging) && W.allows('drive') && has('flow')) {
      const speed = 24 + Math.min(150, Math.abs(kw) * 0.9);
      const ph = ((t - W.loopStart) / 1000) * speed;
      o.fillStyle = color.css(color.lighten(flow, 0.4));
      const dot = (x, y) => { o.beginPath(); o.arc(x, y, 1.8, 0, Math.PI * 2); o.fill(); };
      const run = (x0, y0, x1, y1, len) => {
        const n = Math.max(1, Math.round(len / 14));
        for (let i = 0; i < n; i++) {
          let f = (((ph / len) + i / n) % 1 + 1) % 1;
          if (regen) f = 1 - f;
          dot(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f);
        }
      };
      if (charging && has('port')) run(PACK_CX, CY + BH / 2 + 19, PACK_CX, CY + BH / 2 + 1, 18);
      for (const lk of links) {
        if (!lk[1]) continue;
        if (has('pack')) run(lk[3], CY, lk[2], CY, Math.abs(lk[3] - lk[2]));
        run(lk[0], CY - 12, lk[0], top, CY - 12 - top);
        run(lk[0], CY + 12, lk[0], bottom, bottom - CY - 12);
      }
    }
    // motors
    if (has('motors')) {
      for (const m of [[XR, rearOn], [XF, frontOn]]) {
        const mx = m[0], on = m[1];
        const gr = o.createLinearGradient(mx - 8, 0, mx + 8, 0);
        gr.addColorStop(0, night ? '#1B2221' : '#8C9793'); gr.addColorStop(0.3, night ? '#8E9C98' : '#FFFFFF'); gr.addColorStop(0.7, night ? '#29322F' : '#A8B2AE'); gr.addColorStop(1, night ? '#171D1C' : '#86908C');
        o.fillStyle = gr; rrect(o, mx - 8, CY - 15.5, 16, 31, 6); o.fill();
        o.fillStyle = on ? color.css(flow) : (night ? '#0B100F' : '#25302C'); rrect(o, mx - 4, CY - 9.5, 8, 19, 3.5); o.fill();
        if (on) {
          const gl = o.createRadialGradient(mx, CY, 2, mx, CY, 26);
          gl.addColorStop(0, color.css(color.alpha(flow, 0.45))); gl.addColorStop(1, color.css(color.clear(flow)));
          o.fillStyle = gl; o.fillRect(mx - 26, CY - 26, 52, 52);
        }
      }
    }
    // pack: ten cells lit to the charge
    if (has('pack')) drawPack(o, tk, night, soc);
    if (has('port')) drawPort(o, tk, line, charging);
    // tyres (nose right, left side on top) and their pressures
    const p = k => num(L.vals[k]);
    if (has('tyres')) { tyre(o, W, XR, YT, p('rl'), lo, hi); tyre(o, W, XF, YT, p('fl'), lo, hi); tyre(o, W, XR, YB, p('rr'), lo, hi); tyre(o, W, XF, YB, p('fr'), lo, hi); }
    if (has('pressures')) {
      const ax = XR + TL / 2 + 8, bx = XF - TL / 2 - 8;
      pressure(o, W, ax, YT + 6, p('rl'), 'left', lo, hi); pressure(o, W, bx, YT + 6, p('fl'), 'right', lo, hi);
      pressure(o, W, ax, YB + 6, p('rr'), 'left', lo, hi); pressure(o, W, bx, YB + 6, p('fr'), 'right', lo, hi);
    }
  }

  function drawPack(o, t, night, soc) {
    const y0 = CY - BH / 2;
    const pg = o.createLinearGradient(0, y0, 0, y0 + BH);
    pg.addColorStop(0, night ? '#3A4543' : '#F4F7F5'); pg.addColorStop(1, night ? '#101615' : '#C9D2CE');
    o.fillStyle = pg; rrect(o, BX0, y0, BX1 - BX0, BH, 6); o.fill();
    o.strokeStyle = color.css(color.alpha(0xFFFFFFFF, night ? 0.22 : 0.9)); o.lineWidth = 1; rrect(o, BX0 + 0.5, y0 + 0.5, BX1 - BX0 - 1, BH - 1, 6); o.stroke();
    const lit = Math.max(0, Math.min(10, Math.round(soc / 10)));
    const cellC = color.rampHue([0, 10, 18, 26, 34], [t.glowDanger, t.glowDanger, t.glowWarn, t.glowWarn, t.glowAccent], soc);
    for (let i = 0; i < 10; i++) {
      o.fillStyle = i < lit ? color.css(cellC) : (night ? 'rgba(0,0,0,.45)' : 'rgba(11,40,30,.12)');
      rrect(o, BX0 + 5 + i * 6.8, y0 + 5, 5.2, BH - 10, 1.4); o.fill();
    }
  }

  function drawPort(o, t, line, charging) {
    const y = CY + BH / 2 + 21;
    o.strokeStyle = color.css(charging ? t.glowAccent : line); o.lineWidth = 1.4;
    rrect(o, PACK_CX - 4.5, y, 9, 9, 2.5); o.stroke();
    o.beginPath(); o.moveTo(PACK_CX - 2, y); o.lineTo(PACK_CX - 2, y - 3); o.moveTo(PACK_CX + 2, y); o.lineTo(PACK_CX + 2, y - 3); o.stroke();
    if (charging) { o.strokeStyle = color.css(t.glowAccent); o.lineWidth = 2; o.beginPath(); o.moveTo(PACK_CX, y - 4); o.lineTo(PACK_CX, CY + BH / 2 + 2); o.stroke(); arrowHead(o, PACK_CX, CY + BH / 2 + 2, 0, -1, 5); }
  }

  function drawRoad(o, W, L, t) {
    const tk = W.tokens, night = W.theme === 'night';
    if (!truthy(L.vals.ahead)) return;
    const d = Math.max(0, nv(W.sprung(L, 'distance'), 0));
    const sev = Math.min(3, Math.max(1, Math.round(nv(num(L.vals.severity), 1))));
    const kind = num(L.vals.hazard);
    const count = Math.min(4, Math.max(1, Math.round(nv(num(L.vals.count), 1))));
    const region = L.vals.region || 'eu';
    const k = clamp01(1 - d / 500);
    const ax = 190, ay = 46, bl = 88, br = 250, by = 184;
    const hot = sev >= 3 ? tk.glowDanger : tk.glowWarn;

    o.strokeStyle = color.css(color.fade(tk.text, 0.16)); o.lineWidth = 1;
    o.beginPath(); o.moveTo(60, 45); o.lineTo(276, 45); o.stroke();
    const ground = o.createLinearGradient(0, 45, 0, by);
    ground.addColorStop(0, night ? 'rgba(255,255,255,0)' : 'rgba(11,40,30,0)'); ground.addColorStop(1, night ? 'rgba(255,255,255,.05)' : 'rgba(11,40,30,.06)');
    o.fillStyle = ground; o.beginPath(); o.moveTo(60, 120); o.lineTo(ax, ay); o.lineTo(bl, by); o.lineTo(60, by); o.closePath(); o.fill();
    const asphalt = o.createLinearGradient(0, ay, 0, by);
    asphalt.addColorStop(0, night ? '#3A4240' : '#D2D9D5'); asphalt.addColorStop(1, night ? '#222827' : '#BAC4BF');
    o.fillStyle = asphalt; o.beginPath(); o.moveTo(ax, ay); o.lineTo(br, by); o.lineTo(bl, by); o.closePath(); o.fill();
    o.strokeStyle = color.css(color.fade(tk.text, night ? 0.5 : 0.45)); o.lineWidth = 1.4;
    o.beginPath(); o.moveTo(ax, ay); o.lineTo(bl, by); o.moveTo(ax, ay); o.lineTo(br, by); o.stroke();
    // centre dashes, longer as they near
    o.strokeStyle = color.css(color.fade(tk.text, night ? 0.55 : 0.5));
    for (const u of [0.1, 0.2, 0.33, 0.5, 0.72, 0.97]) {
      const u1 = Math.min(1, u + 0.035 + 0.06 * u);
      o.lineWidth = 0.8 + 2.4 * u;
      o.beginPath(); o.moveTo(ax - 19 * u, ay + (by - ay) * u); o.lineTo(ax - 19 * u1, ay + (by - ay) * u1); o.stroke();
    }
    // the hazard on the road, nearer and larger as the distance falls
    const uh = 0.2 + 0.6 * k, yh = ay + (by - ay) * uh, cxh = ax - 19 * uh, half = 81 * uh;
    if (kind === 1) { o.fillStyle = night ? '#050807' : '#1B2421'; o.beginPath(); o.ellipse(cxh - 4 * uh, yh - 5, 9 * uh + 3, 2.4 * uh + 1.4, 0, 0, Math.PI * 2); o.fill(); }
    else if (kind === 0) {
      o.lineWidth = 1.2 + 2 * uh; o.setLineDash([5 * uh + 2, 4 * uh + 2]);
      o.strokeStyle = color.css(tk.glowWarn); o.beginPath(); o.moveTo(cxh - half * 0.95, yh - 6); o.lineTo(cxh + half * 0.95, yh - 6); o.stroke();
      o.setLineDash([]);
    }
    o.strokeStyle = color.css(hot); o.lineWidth = 1.4 + 2.2 * uh;
    for (let i = 0; i < sev; i++) {
      const cy = yh + i * (4 + 7 * uh), w = half * 0.55;
      o.beginPath(); o.moveTo(cxh - w, cy + 4 + 3 * uh); o.lineTo(cxh, cy); o.lineTo(cxh + w, cy + 4 + 3 * uh); o.stroke();
    }
    // signs: a row of them for a cluster, the nearest first
    for (let i = count - 1; i >= 0; i--) {
      const kk = Math.max(0, k - i * 0.22);
      const sz = (24 + 19 * kk) * (1 - i * 0.12), sx = 151 - 36 * kk + i * 13, sy = 56 + 24 * kk - i * 11;
      if (i === 0) {
        const pulse = sev >= 3 && W.allows('urgent') ? 0.5 + 0.5 * Math.sin((t - W.loopStart) / 1000 * Math.PI * 2 / 1.1) : 0.5;
        const gr = o.createRadialGradient(sx, sy, 2, sx, sy, sz * 1.25);
        gr.addColorStop(0, color.css(color.alpha(hot, (night ? 0.42 : 0.3) + 0.25 * pulse))); gr.addColorStop(1, color.css(color.clear(hot)));
        o.fillStyle = gr; o.beginPath(); o.arc(sx, sy, sz * 1.25, 0, Math.PI * 2); o.fill();
      }
      o.strokeStyle = night ? '#8E9C98' : '#86908C'; o.lineWidth = 1.6 * (sz / 43);
      o.beginPath(); o.moveTo(sx, sy + sz * 0.36); o.lineTo(sx, sy + sz * 0.95); o.stroke();
      signFace(o, sx, sy, sz, region, kind);
    }
  }

  function signFace(o, cx, cy, sz, region, kind) {
    const r = sz / 2;
    o.save();
    o.lineJoin = 'round';
    if (region === 'us') {
      o.beginPath(); o.moveTo(cx, cy - r); o.lineTo(cx + r, cy); o.lineTo(cx, cy + r); o.lineTo(cx - r, cy); o.closePath();
      o.fillStyle = '#F6C343'; o.fill(); o.strokeStyle = '#111'; o.lineWidth = sz * 0.07; o.stroke();
    } else {
      const top = cy - r * 0.95, base = cy + r * 0.62;
      o.beginPath(); o.moveTo(cx, top); o.lineTo(cx + r, base); o.lineTo(cx - r, base); o.closePath();
      o.fillStyle = region === 'cn' ? '#F6C343' : '#FFFFFF'; o.fill();
      o.strokeStyle = region === 'cn' ? '#111' : '#D7262E'; o.lineWidth = sz * 0.12; o.stroke();
    }
    o.translate(cx, cy + sz * 0.06);
    o.scale(sz / 92, sz / 92);
    o.fillStyle = '#111';
    if (typeof Path2D !== 'undefined') o.fill(picto(kind == null ? 'u' : kind === 0 || kind === 1 || kind === 3 ? kind : 'u'), 'evenodd');
    o.restore();
  }

  // ── files: caps, cleaning and a still preview ─────────────────────────────
  /** A copy safe to store or share: no tap actions (v1 has none), format 1. */
  function cleanDoc(doc) {
    const out = JSON.parse(JSON.stringify(doc || {}));
    delete out.tap;
    if (Array.isArray(out.layers)) out.layers.forEach(l => { if (l && typeof l === 'object') delete l.tap; });
    out.format = 1;
    return out;
  }

  /** The file's size as the car measures it: org.json writes every "/" as "\/". */
  function carBytes(doc) {
    const json = JSON.stringify(cleanDoc(doc));
    return byteLength(json) + (json.match(/\//g) || []).length;
  }

  function byteLength(s) {
    if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(s).length;
    return unescape(encodeURIComponent(s)).length;
  }

  const MAX_TEXT = 400, MAX_PATH = 4000, MAX_OBJECTS = 4;
  const INVISIBLE = /[\u00AD\u200B-\u200F\u202A-\u202E\u2060-\u2064\uFEFF]/g;
  /** The link test the car and the community share: NFKC, invisible marks dropped, lower-cased. */
  function hasLink(str) {
    if (typeof str !== 'string' || !str) return false;
    let t = str;
    try { t = t.normalize('NFKC'); } catch (e) { /* no normalize(): test as written */ }
    t = t.replace(INVISIBLE, '').toLowerCase();
    return t.indexOf('://') >= 0 || t.indexOf('www.') >= 0 || t.indexOf('javascript:') >= 0;
  }
  function anyString(v, test) {
    if (typeof v === 'string') return test(v);
    if (Array.isArray(v)) return v.some(x => anyString(x, test));
    if (v && typeof v === 'object') return Object.keys(v).some(k => anyString(v[k], test));
    return false;
  }
  const CHOICES = dict({ motion: ['none', 'number', 'liquid'], align: ['left', 'center', 'right'], shape: ['rect', 'circle', 'path'] });
  const PHASES = ['driving', 'charging', 'parked'];
  const PATH_RE = /^[MmLlHhVvCcSsQqTtAaZz0-9.,+\-eE\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$/;
  const isNum = v => typeof v === 'number' && isFinite(v);
  const isExpr = v => typeof v === 'string' && v.charAt(0) === '=';
  // why one property fails CustomDoc's save check, or null; a null is no value, and expressions are checked by compiling
  function propProblem(kind, raw) {
    if (raw == null) return null;
    if (typeof raw === 'string' && hasLink(raw)) return 'link';
    if (kind === 'geo') return isNum(raw) ? null : 'number';
    if (kind === 'num') return typeof raw === 'boolean' || isNum(raw) || isExpr(raw) ? null : 'value';
    if (kind === 'bool') return typeof raw === 'boolean' || isNum(raw) || isExpr(raw) ? null : 'bool';
    if (kind === 'col') return typeof raw === 'string' && (isExpr(raw) || raw === 'none' || TOKENS.night[raw] !== undefined || color.parse(raw) != null) ? null : 'colour';
    if (kind === 'txt') {
      if (typeof raw === 'boolean' || isNum(raw) || isExpr(raw)) return null;
      if (typeof raw !== 'string') return 'text';
      return raw.length > MAX_TEXT ? 'text_long' : null;
    }
    if (kind === 'phase') return isExpr(raw) || (typeof raw === 'string' && PHASES.includes(raw.trim().toLowerCase())) ? null : 'phase';
    if (kind === 'weight') return isNum(raw) ? null : 'weight';
    if (kind === 'parts') return typeof raw === 'string' && raw.split(',').some(x => CAR_PART_NAMES.includes(x.trim())) ? null : 'parts';
    if (kind === 'path') return typeof raw === 'string' && raw.length <= MAX_PATH && PATH_RE.test(raw) ? null : 'path';
    const opts = CHOICES[kind] || (String(kind).indexOf('enum:') === 0 ? kind.slice(5).split(',') : null);
    if (opts) return typeof raw === 'string' && opts.includes(raw.trim().toLowerCase()) ? null : 'choice';
    return null;
  }

  /** What CustomDoc would refuse on save, as codes the page words for people: [{code, n, layer, key, why}]. */
  function docProblems(doc) {
    const out = [];
    if (!doc || typeof doc !== 'object') return [{ code: 'format' }];
    // a blank name is stored as "Untitled"
    if (doc.name != null && typeof doc.name !== 'string') out.push({ code: 'name_type' });
    const name = typeof doc.name === 'string' ? doc.name.trim() : '';
    if (name.length > MAX_NAME) out.push({ code: 'name', n: MAX_NAME });
    if (doc.size != null && (typeof doc.size !== 'string' || !SIZES[doc.size.trim().toLowerCase()])) out.push({ code: 'size' });
    if (doc.about != null && typeof doc.about !== 'string') out.push({ code: 'about_type' });
    if (typeof doc.about === 'string' && doc.about.trim().length > MAX_ABOUT) out.push({ code: 'about', n: MAX_ABOUT });
    if (hasLink(name) || hasLink(doc.about)) out.push({ code: 'link' });
    if (doc.layers != null && !Array.isArray(doc.layers)) return out.concat([{ code: 'format' }]);
    const layers = Array.isArray(doc.layers) ? doc.layers : [];
    if (layers.length > MAX_LAYERS) out.push({ code: 'layers', n: MAX_LAYERS });
    let known = 0, objects = 0;
    layers.forEach((l, i) => {
      if (!l || typeof l !== 'object' || Array.isArray(l)) { out.push({ code: 'layer_obj', layer: i }); return; }
      const def = typeof l.type === 'string' ? TYPES[l.type] : null;
      if (!def) return;
      known++;
      if (def.object) objects++;
      if (l.name != null) {
        if (typeof l.name !== 'string') out.push({ code: 'prop', layer: i, key: 'name', why: 'text' });
        else if (hasLink(l.name)) out.push({ code: 'prop', layer: i, key: 'name', why: 'link' });
        else if (l.name.trim().length > MAX_NAME) out.push({ code: 'prop', layer: i, key: 'name', why: 'layer_name' });
      }
      const kinds = Object.assign({}, def.props, COMMON);
      Object.keys(kinds).forEach(key => {
        if (!Object.prototype.hasOwnProperty.call(l, key)) return;
        const why = propProblem(l.type === 'car' && key === 'phase' ? 'phase' : kinds[key], l[key]);
        if (why) out.push({ code: 'prop', layer: i, key, why });
      });
    });
    if (!known) out.push({ code: 'empty' });
    if (objects > MAX_OBJECTS) out.push({ code: 'objects', n: MAX_OBJECTS });
    // a link anywhere else in the file, unknown keys included
    if (!out.some(q => q.code === 'link' || q.why === 'link') && anyString(cleanDoc(doc), hasLink)) out.push({ code: 'link' });
    if (carBytes(doc) > MAX_BYTES) out.push({ code: 'bytes', n: Math.round(MAX_BYTES / 1024) });
    return out;
  }

  // a believable moment for still previews: driving along, a pothole coming up
  const SAMPLE = {
    soc: 72, ev_range_km: 318, calibrated_range_km: 305, fuel_pct: null, is_phev: false, is_charging: false, charge_power: null,
    charge_limit: 80, charging_eta_minutes: null, is_dcfc: false, driving: true, reversing: false, speed: 64, power: 22,
    accel_pct: 18, brake_pct: 0, motor_front_rpm: 5400, motor_rear_rpm: 5400, trip_km: 23.4, trip_minutes: 31,
    trip_wh_per_km: 152, trip_kwh: 3.6, trip_top_kmh: 96, tyre_fl: 2.48, tyre_fr: 2.5, tyre_rl: 2.46, tyre_rr: 2.5,
    tyre_low_bar: 2.34, tyre_high_bar: 3.1, locked: false, altitude_m: 214, grade_pct: 1.5, hazard_ahead: true,
    hazard_type: 1, hazard_m: 180, hazard_sev: 2, hazard_count: 1,
  };
  function sampleHistory(base) {
    const out = [], end = now();
    for (let i = 120; i >= 1; i--) {
      const t = 120 - i, kmh = Math.max(0, 50 + 28 * Math.sin(t / 13) + 10 * Math.sin(t / 5));
      out.push({ t: end - i * 1000, data: Object.assign({}, base || SAMPLE, { speed: kmh, power: 0.0011 * kmh * kmh + 0.12 * kmh + 12 * Math.cos(t / 13) }) });
    }
    return out;
  }

  function shellPath(c, w, h, r) {
    c.beginPath(); c.moveTo(r, 0); c.arcTo(w, 0, w, h, r); c.arcTo(w, h, 0, h, r); c.arcTo(0, h, 0, 0, r); c.arcTo(0, 0, w, 0, r); c.closePath();
  }

  /** Draws a doc once, fitted into the canvas's laid-out box on a plain glass plate. Null when it has no size yet. */
  function renderStill(cv, doc, opts) {
    opts = opts || {};
    const r = cv.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return null;
    const dpr = window.devicePixelRatio || 1;
    const cw = Math.round(r.width * dpr), ch = Math.round(r.height * dpr);
    if (cv.width !== cw) cv.width = cw;
    if (cv.height !== ch) cv.height = ch;
    const c = cv.getContext('2d');
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.clearRect(0, 0, cw, ch);
    const night = opts.theme !== 'day';
    let w;
    try { w = new Widget(doc, { theme: night ? 'night' : 'day' }); } catch (e) { return null; }
    const pad = opts.pad == null ? 12 : opts.pad;
    const k = Math.min(r.width / (w.w + pad), r.height / (w.h + pad));
    w.px = dpr * k;
    const data = opts.data || SAMPLE;
    w.env.driving = !!(data.driving || data.reversing);
    w.env.reduceMotion = true;
    if (opts.units) w.env.units = opts.units;
    try {
      w.seed(opts.history || sampleHistory(data));
      w.bind(data, false);
    } catch (e) { return null; }
    c.setTransform(dpr * k, 0, 0, dpr * k, dpr * (r.width - w.w * k) / 2, dpr * (r.height - w.h * k) / 2);
    const gr = c.createLinearGradient(0, 0, w.w * 0.3, w.h);
    gr.addColorStop(0, night ? 'rgba(36,48,43,.95)' : 'rgba(255,255,255,.98)'); gr.addColorStop(1, night ? 'rgba(1,10,7,.95)' : 'rgba(233,240,235,.98)');
    c.fillStyle = gr; shellPath(c, w.w, w.h, 26); c.fill();
    c.save(); shellPath(c, w.w, w.h, 26); c.clip();
    try { w.draw(c, now()); } catch (e) { /* half a preview beats none */ }
    c.restore();
    c.strokeStyle = night ? 'rgba(255,255,255,.18)' : 'rgba(24,48,38,.18)'; c.lineWidth = 2; shellPath(c, w.w, w.h, 26); c.stroke();
    return w;
  }

  G.ODW = {
    SIZES, TYPES, COMMON, TOKENS, BUILTINS, CAR_AUTO, ROAD_AUTO, FONT,
    MAX_LAYERS, MAX_EXPR, MAX_NAME, MAX_ABOUT, MAX_BYTES, MAX_TEXT, MAX_OBJECTS, UNIT_WORDS, hasLink, tyreText, unitWord,
    color, parse, template, collect, evaluate: safeEval, truthy, num, text, Widget, ExprError,
    PARTS: { names: CAR_PART_NAMES, of: partsOf },
    cleanDoc, docProblems, byteLength, carBytes, renderStill, sampleHistory, SAMPLE, now,
    MQTT_CHANNEL, mqttValue,
  };
})(window);
