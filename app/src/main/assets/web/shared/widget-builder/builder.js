/* Widget builder (browser only): templates, layers, a Simple/Advanced inspector that writes widget-file formulas,
   simulated car data, and saving to the car, which draws saved files natively. Never runs in the car app.
   ES2017 only (no ?. ?? flat fromEntries replaceAll roundRect). */
(function () {
  'use strict';
  const ODW = window.ODW;
  if (!ODW) return;
  const Widget = ODW.Widget, TYPES = ODW.TYPES, TOKENS = ODW.TOKENS, color = ODW.color, num = ODW.num;
  const SIZES = ODW.SIZES, MAX_LAYERS = ODW.MAX_LAYERS;

  // ── small helpers ─────────────────────────────────────────────────────────
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.prototype.slice.call((r || document).querySelectorAll(s));
  function el(tag, cls, parent, html) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    if (parent) parent.appendChild(e);
    return e;
  }
  function btn(cls, parent, html, onClick) {
    const b = el('button', cls, parent, html);
    b.type = 'button';
    if (onClick) b.addEventListener('click', onClick);
    return b;
  }
  const clone = o => JSON.parse(JSON.stringify(o));
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ESC[c]);
  const nz = v => (typeof v === 'number' && isFinite(v) ? v : 0);
  const clamp01 = v => Math.min(1, Math.max(0, v));
  const P = 'widgetBuilder.';
  function t18(full, vars) {
    let v = null;
    try { v = window.BYD && BYD.i18n && BYD.i18n.t ? BYD.i18n.t(full, vars) : null; } catch (e) { v = null; }
    return typeof v === 'string' ? v : null;
  }
  function tr(key, vars) { const v = t18(P + key, vars); return v == null ? P + key : String(v); }
  function trOr(key, fallback, vars) { const v = t18(P + key, vars); return v == null || v === P + key ? fallback : String(v); }
  /** Translation as HTML: the text is escaped, then {holes} take the caller's ready-made HTML. */
  function trHtml(key, vars) {
    const raw = t18(P + key);
    return esc(raw == null ? P + key : raw).replace(/\{(\w+)\}/g, (m, k) => (vars && vars[k] != null ? String(vars[k]) : m));
  }
  const code = s => '<code dir="ltr">' + esc(s) + '</code>';
  // stroke icons on the app's 24 grid
  const ICONS = Object.assign(Object.create(null), {
    widget: '<rect x="3" y="5" width="18" height="14" rx="3"/>',
    ring: '<path d="M4.9 19.1a10 10 0 1 1 14.2 0"/><path d="m12 12 4-4"/>',
    bar: '<rect x="3" y="9" width="18" height="6" rx="3"/><path d="M6.5 12h7"/>',
    text: '<path d="M4 7V5h16v2"/><path d="M12 5v14"/><path d="M9 19h6"/>',
    reading: '<path d="M4 9h16M4 15h16M10 3 8 21M16 3l-2 18"/>',
    spark: '<polyline points="3 17 9 11 13 15 21 7"/>',
    capsule: '<rect x="7" y="3" width="10" height="18" rx="3"/><path d="M10 14h4M10 10h4"/>',
    pulse: '<circle cx="12" cy="12" r="3"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    shape: '<rect x="3" y="3" width="8" height="8" rx="1"/><circle cx="16.5" cy="16.5" r="4.5"/>',
    car: '<path d="M5 17H3v-5l2.2-5.1A2 2 0 0 1 7 5.7h10a2 2 0 0 1 1.8 1.2L21 12v5h-2M3 12h18M9.5 17h5"/><circle cx="7.5" cy="17" r="2"/><circle cx="16.5" cy="17" r="2"/>',
    pack: '<rect x="2" y="7" width="18" height="10" rx="2"/><path d="M22 11v2M6 10v4M10 10v4M14 10v4"/>',
    tyres: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/>',
    road: '<path d="M4 21 9 3M20 21 15 3M12 6v2M12 11v2M12 16v2"/>',
    unknown: '<circle cx="12" cy="12" r="9"/><path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3"/><path d="M12 17h.01"/>',
    eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
    eyeOff: '<path d="M9.9 4.2A9 9 0 0 1 12 4c6.5 0 10 8 10 8a17 17 0 0 1-2.2 3.2M6.6 6.6A17 17 0 0 0 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6"/><path d="m2 2 20 20"/><path d="M14.1 14.1a3 3 0 1 1-4.2-4.2"/>',
    up: '<path d="M12 19V5"/><path d="m5 12 7-7 7 7"/>',
    down: '<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',
    copy: '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
    trash: '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
    upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>',
    pencil: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  });
  const icon = k => '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (ICONS[k] || ICONS.unknown) + '</svg>';
  const typeIcon = type => icon(TYPES[type] ? type : 'unknown');
  // writes a text node in place, so per-second updates don't wake the page shell's DOM observer
  function setText(node, s) {
    const f = node.firstChild;
    if (f && f.nodeType === 3 && !f.nextSibling) { if (f.nodeValue !== s) f.nodeValue = s; } else node.textContent = s;
  }
  function setHtml(node, html) { if (node._h !== html) { node._h = html; node.innerHTML = html; } }
  function store(key, val) { try { if (val == null) localStorage.removeItem(key); else localStorage.setItem(key, JSON.stringify(val)); } catch (e) { /* private mode */ } }
  function load(key) { try { const s = localStorage.getItem(key); return s ? JSON.parse(s) : null; } catch (e) { return null; } }

  function api(method, path, body) {
    const opts = { method, cache: 'no-store', headers: {} };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    let p;
    try {
      p = typeof BYDAuth !== 'undefined' && BYDAuth && typeof BYDAuth.fetch === 'function' ? BYDAuth.fetch(path, opts) : fetch(path, opts);
    } catch (e) { p = Promise.reject(e); }
    return Promise.resolve(p).then(r => r.text().then(txt => {
      let data = null;
      try { data = txt ? JSON.parse(txt) : null; } catch (e) { data = null; }
      const ok = r.ok && !!data && data.success !== false;
      return { ok, status: r.status, data: data || {}, error: (data && typeof data.error === 'string' && data.error) || '' };
    })).catch(() => ({ ok: false, status: 0, data: {}, error: '' }));
  }
  function fetchJson(url) { return fetch(url, { cache: 'no-cache' }).then(r => (r.ok ? r.json() : null)).catch(() => null); }
  function withTimeout(p, ms) { return Promise.race([p, new Promise(res => setTimeout(() => res(null), ms))]); }

  function toast(msg, type) { try { BYD.core.toast(msg, type || 'info', 4500); } catch (e) { /* no toast host */ } }
  const hasUtils = name => !!(window.BYD && BYD.utils && typeof BYD.utils[name] === 'function');
  const confirmDlg = o => (hasUtils('confirmDialog') ? BYD.utils.confirmDialog(o) : Promise.resolve(window.confirm(o.body || o.title || '')));
  const alertDlg = o => (hasUtils('alertDialog') ? BYD.utils.alertDialog(o) : Promise.resolve(window.alert(o.body || o.title || '')));
  const promptDlg = o => (hasUtils('promptDialog') ? BYD.utils.promptDialog(o) : Promise.resolve(window.prompt(o.body || o.title || '', o.value || '')));

  /** A dialog on the app's modal surface; build(card, close) fills it. Esc and the backdrop close it. */
  function modal(build, cls, onClose) {
    const back = el('div', 'modal-backdrop wb-modal-back', document.body);
    back.style.display = 'flex';
    const card = el('div', 'modal-card wb-modal ' + (cls || ''), back);
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');
    const prev = document.activeElement;
    let done = false;
    const onKey = e => { if (e.key === 'Escape') { e.preventDefault(); close(); } };
    function close() {
      if (done) return;
      done = true;
      document.removeEventListener('keydown', onKey, true);
      if (back.parentNode) back.parentNode.removeChild(back);
      try { if (prev && prev.focus) prev.focus(); } catch (e) { /* gone */ }
      if (onClose) onClose();
    }
    back.addEventListener('click', e => { if (e.target === back) close(); });
    document.addEventListener('keydown', onKey, true);
    build(card, close);
    const first = card.querySelector('[data-autofocus]') || card.querySelector('input, textarea, select, button');
    try { if (first) first.focus(); } catch (e) { /* not focusable */ }
    return close;
  }

  const inCarApp = () => typeof window.AndroidBridge !== 'undefined' || document.documentElement.getAttribute('data-android-embed') === '1';

  // ── car data: the v1 catalog (the car's copy wins), simulated readings ────
  // Fallback only, a copy of CustomSignals' catalog: GET /api/widgets/signals is the source of truth.
  const sigE = (key, group, label, unit, min, max, step, def, live) => ({ key, group, unit, type: 'number', min, max, step, live: !!live, labelKey: P + 'sig.' + key, label, def });
  const flagE = (key, group, label, def) => ({ key, group, unit: '', type: 'bool', min: 0, max: 1, step: 1, live: false, labelKey: P + 'sig.' + key, label, def });
  const choiceE = (key, group, label, def, options) => ({ key, group, unit: '', type: 'enum', min: options[0][0], max: options[options.length - 1][0], step: 1, live: false, labelKey: P + 'sig.' + key, label, def, options });
  const BUNDLED_SIGNALS = [
    sigE('soc', 'Battery', 'Battery level', '%', 0, 100, 1, 72),
    sigE('ev_range_km', 'Battery', "Range (car's figure)", 'km', 0, 1000, 1, 318),
    sigE('calibrated_range_km', 'Battery', 'Calibrated range', 'km', 0, 1500, 1, 305),
    sigE('fuel_pct', 'Battery', 'Fuel level (plug-in hybrid)', '%', 0, 100, 1, null),
    flagE('is_phev', 'Battery', 'Plug-in hybrid', false),
    flagE('is_charging', 'Charging', 'Charging', false),
    sigE('charge_power', 'Charging', 'Charging power', 'kW', 0, 350, 0.1, null),
    sigE('charge_limit', 'Charging', 'Charge limit', '%', 0, 100, 1, 80),
    sigE('charging_eta_minutes', 'Charging', 'Charging time left', 'min', 0, 1440, 1, null),
    flagE('is_dcfc', 'Charging', 'DC fast charging', null),
    flagE('driving', 'Driving', 'Driving', false),
    flagE('reversing', 'Driving', 'Reversing', false),
    sigE('speed', 'Driving', 'Speed', 'km/h', 0, 250, 1, 0, true),
    sigE('power', 'Driving', 'Power (negative is regen)', 'kW', -150, 300, 0.1, 0, true),
    sigE('accel_pct', 'Driving', 'Accelerator pedal', '%', 0, 100, 1, 0, true),
    sigE('brake_pct', 'Driving', 'Brake pedal', '%', 0, 100, 1, 0, true),
    sigE('motor_front_rpm', 'Driving', 'Front motor speed', 'rpm', -16000, 16000, 10, 0, true),
    sigE('motor_rear_rpm', 'Driving', 'Rear motor speed', 'rpm', -16000, 16000, 10, 0, true),
    sigE('trip_km', 'Trip', 'Trip distance', 'km', 0, 1000, 0.1, 23.4),
    sigE('trip_minutes', 'Trip', 'Trip time', 'min', 0, 1440, 1, 31),
    sigE('trip_wh_per_km', 'Trip', 'Trip consumption', 'Wh/km', 0, 400, 1, 152),
    sigE('trip_kwh', 'Trip', 'Trip energy', 'kWh', 0, 150, 0.1, 3.6),
    sigE('trip_top_kmh', 'Trip', 'Trip top speed', 'km/h', 0, 250, 1, 96),
    sigE('tyre_fl', 'Tyres', 'Front-left tyre', 'bar', 0, 4, 0.05, 2.48),
    sigE('tyre_fr', 'Tyres', 'Front-right tyre', 'bar', 0, 4, 0.05, 2.5),
    sigE('tyre_rl', 'Tyres', 'Rear-left tyre', 'bar', 0, 4, 0.05, 2.46),
    sigE('tyre_rr', 'Tyres', 'Rear-right tyre', 'bar', 0, 4, 0.05, 2.5),
    sigE('tyre_low_bar', 'Tyres', 'Low tyre warning', 'bar', 0, 4, 0.05, 2.34),
    sigE('tyre_high_bar', 'Tyres', 'High tyre warning', 'bar', 0, 4, 0.05, 3.1),
    flagE('hazard_ahead', 'RoadSense', 'Hazard ahead', false),
    choiceE('hazard_type', 'RoadSense', 'Hazard type', 0, [[0, 'Speed breaker'], [1, 'Pothole'], [2, 'Road hazard'], [3, 'Rough road']]),
    sigE('hazard_m', 'RoadSense', 'Distance to hazard', 'm', 0, 1000, 5, 320),
    sigE('hazard_sev', 'RoadSense', 'Hazard severity', '', 1, 3, 1, 2),
    sigE('hazard_count', 'RoadSense', 'Hazards in a row', '', 1, 4, 1, 1),
    sigE('altitude_m', 'RoadSense', 'Altitude', 'm', -500, 6000, 1, 214),
    sigE('grade_pct', 'RoadSense', 'Road grade', '%', -30, 30, 0.5, 0),
    flagE('locked', 'Body', 'Locked', true),
  ];
  const GROUP_ORDER = ['Battery', 'Charging', 'Driving', 'Trip', 'Tyres', 'RoadSense', 'Body'];

  const SIM_DEFAULTS = {
    soc: 72, ev_range_km: 318, calibrated_range_km: 305, fuel_pct: null, is_phev: false, is_charging: false, charge_power: null,
    charge_limit: 80, charging_eta_minutes: null, is_dcfc: false, driving: false, reversing: false, speed: 0, power: 0,
    accel_pct: 0, brake_pct: 0, motor_front_rpm: 0, motor_rear_rpm: 0, trip_km: 23.4, trip_minutes: 31, trip_wh_per_km: 152,
    trip_kwh: 3.6, trip_top_kmh: 96, tyre_fl: 2.48, tyre_fr: 2.5, tyre_rl: 2.46, tyre_rr: 2.5, tyre_low_bar: 2.34,
    tyre_high_bar: 3.1, locked: true, altitude_m: 214, grade_pct: 0, hazard_ahead: false, hazard_type: 0, hazard_m: 320,
    hazard_sev: 2, hazard_count: 1,
  };
  const STILL = { driving: false, reversing: false, speed: 0, power: 0, accel_pct: 0, brake_pct: 0, motor_front_rpm: 0, motor_rear_rpm: 0 };
  const NOT_CHARGING = { is_charging: false, is_dcfc: false, charge_power: null, charging_eta_minutes: null };
  const SCEN = {
    parked: { set: Object.assign({ hazard_ahead: false }, STILL, NOT_CHARGING) },
    town: { drive: 'town', set: Object.assign({ driving: true, reversing: false, hazard_ahead: false }, NOT_CHARGING) },
    highway: { drive: 'highway', set: Object.assign({ driving: true, reversing: false, hazard_ahead: false }, NOT_CHARGING) },
    ac: { charge: 'ac', set: Object.assign({}, STILL, { is_charging: true, is_dcfc: false, charge_power: 7.2, soc: 46, ev_range_km: 202, calibrated_range_km: 194, charging_eta_minutes: 142, hazard_ahead: false }) },
    dc: { charge: 'dc', set: Object.assign({}, STILL, { is_charging: true, is_dcfc: true, charge_power: 88, soc: 34, ev_range_km: 150, calibrated_range_km: 143, charging_eta_minutes: 26, hazard_ahead: false }) },
    low: { set: Object.assign({}, STILL, NOT_CHARGING, { soc: 14, ev_range_km: 58, calibrated_range_km: 55, hazard_ahead: false }) },
    hazard: { drive: 'town', hazards: true, set: Object.assign({ driving: true, reversing: false, hazard_ahead: true, hazard_type: 0, hazard_m: 320, hazard_sev: 2, hazard_count: 1 }, NOT_CHARGING) },
  };
  const SCEN_ORDER = ['parked', 'town', 'highway', 'ac', 'dc', 'low', 'hazard'];
  const HAZARDS = [[0, 2, 1], [1, 3, 1], [3, 1, 1], [0, 2, 3]];

  // ── editor tables ─────────────────────────────────────────────────────────
  const PALETTE = ['glowAccent', 'glowGood', 'glowWarn', 'glowDanger', 'regen', 'fuel', 'accent', 'good', 'warn', 'danger', 'text', 'text2', 'text3', 'etch', 'white'];
  // [min, max ('W'/'H' = the glass size), step]
  const GEO = {
    x: [0, 'W', 0.5], y: [0, 'H', 0.5], w: [4, 'W', 1], h: [2, 'H', 1], cx: [0, 'W', 0.5], cy: [0, 'H', 0.5],
    r: [4, 120, 1], width: [1, 40, 0.5], start: [-360, 360, 1], sweep: [10, 360, 1], size: [9, 80, 1],
    glow: [0, 1, 0.05], window: [10, 300, 5], fill: [0, 0.6, 0.02], radius: [0, 40, 0.5],
    strokeWidth: [0, 12, 0.5], period: [0.6, 6, 0.1], scale: [0.4, 3, 0.02],
  };
  // fixed-value ranges for bindable numbers that aren't 0 → 1
  const FIXED = {
    rotate: [-180, 180, 1], power: [-100, 250, 1], soc: [0, 100, 1], fl: [1.2, 3.6, 0.05], fr: [1.2, 3.6, 0.05], rl: [1.2, 3.6, 0.05], rr: [1.2, 3.6, 0.05],
    hazard: [0, 3, 1], distance: [0, 800, 5], severity: [1, 3, 1], count: [1, 4, 1],
  };
  const HINTED = new Set(['value', 'level', 'limit', 'text', 'unit', 'track', 'rotate', 'visible', 'opacity', 'power', 'soc', 'phase', 'fl', 'fr', 'rl', 'rr', 'ahead', 'hazard', 'distance', 'severity', 'count']);

  const ID = '[a-z_][a-z0-9_]*';
  const CONDITIONS = [
    [null, 'cond_always'],
    ['=is_charging', 'cond_charging'],
    ['=driving', 'cond_driving'],
    ['=!driving', 'cond_parked'],
    ['=soc <= 20', 'cond_low_battery'],
    ['=hazard_ahead', 'cond_hazard'],
    ['=!hazard_ahead', 'cond_clear'],
    ['=min(tyre_fl, tyre_fr, tyre_rl, tyre_rr) < tyre_low_bar', 'cond_tyre_low'],
    ['=night', 'cond_night'],
    ['=!night', 'cond_day'],
  ];
  const BATTERY_RAMP = '=ramp(soc, 0, glowDanger, 10, glowDanger, 18, glowWarn, 26, glowWarn, 34, glowAccent)';
  const readFraction = raw => { const m = typeof raw === 'string' && new RegExp('^=(' + ID + ')\\s*/\\s*([\\d.]+)$').exec(raw); return m ? { sig: m[1], full: +m[2] } : null; };
  const readSignal = raw => { const m = typeof raw === 'string' && new RegExp('^=(' + ID + ')$').exec(raw); return m ? m[1] : null; };
  const UNIT_FNS = ['dist', 'near', 'height', 'consumption', 'tyre'];
  const readReading = raw => {
    const m = typeof raw === 'string' && new RegExp('^\\{(?:(' + UNIT_FNS.join('|') + ')\\((' + ID + ')\\)|(' + ID + '))(?::(\\d))?\\}$').exec(raw);
    return m ? { fn: m[1] || null, sig: m[2] || m[3], dec: m[4] ? +m[4] : 0 } : null;
  };
  const INSERT_FNS = ['fmt', 'round', 'min', 'max', 'clamp', 'ramp', 'known', 'ifknown', 'dist', 'near', 'height', 'consumption', 'tyre'];
  function readSteps(raw) {
    if (typeof raw !== 'string') return null;
    let m = new RegExp('^=ramp\\((' + ID + '), 0, glowDanger, 10, glowDanger, 18, glowWarn, 26, glowWarn, 34, glowAccent\\)$').exec(raw);
    if (m) return { sig: m[1], red: 10, amber: 26, standard: true };
    m = new RegExp('^=(' + ID + ') <= (-?[\\d.]+) \\? glowDanger : \\1 <= (-?[\\d.]+) \\? glowWarn : glowAccent$').exec(raw);
    return m ? { sig: m[1], red: +m[2], amber: +m[3] } : null;
  }
  const isFormula = v => typeof v === 'string' && v.charAt(0) === '=';
  const normF = s => (typeof s === 'string' ? s.replace(/\s+/g, '').replace(/"/g, "'") : s);
  // other spellings of the automatic formulas that count as automatic
  const AUTO_ALT = {
    phase: ["=is_charging?'charging':driving||reversing?'driving':'parked'", "=is_charging?'charging':(driving||reversing)?'driving':'parked'"],
  };
  const isAuto = (key, raw, auto) => raw !== undefined && (normF(raw) === normF(auto) || (AUTO_ALT[key] || []).indexOf(normF(raw)) >= 0);

  const LIBRARY = [
    ['lib_car', [
      ['car', 'lib_car_whole', () => carLayer({})],
      ['car', 'lib_car_nopack', () => carLayer({ parts: 'body,motors,tyres,pressures,flow' })],
      ['pack', 'lib_car_pack', () => carLayer({ parts: 'pack', scale: 2.2, name: tr('lib_car_pack') })],
      ['tyres', 'lib_car_tyres', () => carLayer({ parts: 'tyres,pressures', name: tr('lib_car_tyres') })],
    ]],
    ['lib_road', [['road', 'lib_road_sign', () => placed(Object.assign({ type: 'road', name: tr('type.road') }, clone(TYPES.road.defaults)))]]],
    ['lib_gauges', [
      ['ring', 'lib_ring', () => Object.assign({ type: 'ring', name: tr('type.ring') }, clone(TYPES.ring.defaults))],
      ['bar', 'lib_bar', () => Object.assign({ type: 'bar', name: tr('type.bar') }, clone(TYPES.bar.defaults))],
      ['capsule', 'lib_cell', () => Object.assign({ type: 'capsule', name: tr('type.capsule') }, clone(TYPES.capsule.defaults))],
    ]],
    ['lib_text', [
      ['reading', 'lib_reading', () => ({ type: 'text', name: tr('lib_reading'), x: 26, y: 100, size: 34, weight: 600, text: '{soc}', unit: '%', color: 'text', motion: 'number' })],
      ['text', 'lib_words', () => ({ type: 'text', name: tr('lib_words'), x: 26, y: 60, size: 15, weight: 500, text: tr('lib_words_default'), color: 'text2' })],
    ]],
    ['lib_charts', [['spark', 'lib_trace', () => Object.assign({ type: 'spark', name: tr('lib_trace') }, clone(TYPES.spark.defaults))]]],
    ['lib_effects', [['pulse', 'lib_glow', () => Object.assign({ type: 'pulse', name: tr('lib_glow') }, clone(TYPES.pulse.defaults))]]],
    ['lib_shapes', [['shape', 'lib_shape', () => Object.assign({ type: 'shape', name: tr('type.shape') }, clone(TYPES.shape.defaults))]]],
  ];

  const ALL_PARTS = ODW.PARTS.names.join(',');
  const PART_PRESETS = [['parts_whole', ALL_PARTS, 1], ['parts_nopack', 'body,motors,tyres,pressures,flow', 1], ['parts_pack', 'pack', 2.2]];
  // object inputs come connected; each is a choice, and a formula only on request
  const OBJECT_FEEDS = Object.assign(Object.create(null), {
    car: [
      { id: 'power', keys: ['power'], pick: true, show: (v) => (v == null ? '–' : Math.round(v) + ' kW') },
      { id: 'soc', keys: ['soc'], pick: true, show: (v) => (v == null ? '–' : Math.round(v) + ' %') },
      { id: 'phase', keys: ['phase'], fixed: ['parked', 'driving', 'charging'], show: (v) => (v == null ? '–' : trOr('phase_' + v, String(v))) },
      { id: 'tyres', keys: ['fl', 'fr', 'rl', 'rr'], show: (v, L) => ['fl', 'fr', 'rl', 'rr'].map(k => (num(L.vals[k]) == null ? '–' : ODW.tyreText(num(L.vals[k]), tyreUnit()))).join(' · ') + ' ' + tyreUnit() },
    ],
    road: [
      { id: 'hazards', keys: ['ahead', 'hazard', 'distance', 'severity', 'count'],
        show: (v, L) => (ODW.truthy(L.vals.ahead) ? tr('feed_hazard_now', { what: hazardName(num(L.vals.hazard)), d: sampleIn('hazard_m', num(L.vals.distance) || 0, 0) }) : tr('feed_hazard_none')) },
    ],
  });
  const autoFor = (type, key) => (type === 'car' ? ODW.CAR_AUTO[key] : ODW.ROAD_AUTO[key]);
  const hazardName = k => optLabel('hazard_type', k === 0 || k === 1 || k === 3 ? k : 2);

  // ── state ─────────────────────────────────────────────────────────────────
  const S = {
    simple: true, doc: null, docId: null, baseJson: null, savedJson: null,
    sel: -1, theme: 'night', zoom: 1, scenario: 'parked', tab: 'props', panel: 'layers', mode: 'design', narrow: false,
    data: Object.create(null), unknown: new Set(), stale: false, reduceMotion: false, hist: [], hidden: new WeakSet(),
    catalog: [], templates: [], tpl: null, mine: [], mineState: 'loading', limits: { maxWidgets: 32, maxBytes: 16384 },
    saving: false, ready: false, units: { distance: 'km', tyre: 'psi' }, carUnits: { distance: 'km', tyre: 'psi' }, pendingId: null,
  };
  // keyed by names from widget files: no prototype, so "constructor" or "__proto__" is just unknown
  let SIG = Object.create(null);
  let root = null;
  let W = null, shell = null, canvas = null, g = null, selCanvas = null, sg = null;
  let boundJson = '', dirty = true;
  const sim = { t: 0, prev: 0, hz: 0 };
  let LIVE = [];
  const DRAFT_KEY = 'odw.builder.draft.v1', PREFS_KEY = 'odw.builder.prefs.v1';

  function setCatalog(list) {
    const ok = Array.isArray(list) ? list.filter(s => s && typeof s.key === 'string' && /^[a-z_][a-z0-9_]*$/.test(s.key) && !(s.key in Object.prototype)) : [];
    S.catalog = ok.length ? ok : BUNDLED_SIGNALS;
    SIG = Object.create(null);
    S.catalog.forEach(s => { SIG[s.key] = s; });
    S.catalog.forEach(s => {
      if (Object.prototype.hasOwnProperty.call(S.data, s.key)) return;
      if (Object.prototype.hasOwnProperty.call(SIM_DEFAULTS, s.key)) S.data[s.key] = SIM_DEFAULTS[s.key];
      else if (s.def !== undefined) S.data[s.key] = s.def;
      else S.data[s.key] = sigType(s) === 'bool' ? false : nz(s.min);
    });
  }
  function sigType(s) { return s && (s.type === 'bool' || s.type === 'enum') ? s.type : 'number'; }
  const numeric = k => !!SIG[k] && sigType(SIG[k]) === 'number';
  function sigLabel(k) {
    const s = SIG[k];
    if (!s) return k;
    const full = typeof s.labelKey === 'string' && s.labelKey ? s.labelKey : P + 'sig.' + k;
    const v = t18(full);
    return v && v !== full ? String(v) : (s.label || k);
  }
  const sigUnit = k => (SIG[k] && SIG[k].unit) || '';
  const sigOptions = k => (SIG[k] && Array.isArray(SIG[k].options) ? SIG[k].options.filter(o => Array.isArray(o) && typeof o[0] === 'number') : []);
  function optLabel(k, v) {
    const o = sigOptions(k).find(x => x[0] === v);
    return trOr('opt.' + k + '_' + v, o && o[1] ? String(o[1]) : String(v));
  }
  /** Car signals the doc reads: catalog names (driving among them) and names nobody publishes. */
  /** How the car's units show a reading: its converting function, unit word and usual decimals; null when it has none. */
  function unitFor(key) {
    const u = sigUnit(key);
    if (u === 'bar') return { fn: 'tyre', unit: '{tyre_unit}', dec: 0 };
    if (u === 'km') return { fn: 'dist', unit: '{dist_unit}', dec: 1 };
    if (u === 'km/h') return { fn: 'dist', unit: '{speed_unit}', dec: 0 };
    if (u === 'Wh/km') return { fn: 'consumption', unit: '{consumption_unit}', dec: 1 };
    if (u === 'm') return /altitude|height/.test(key) ? { fn: 'height', unit: '{height_unit}', dec: 0 } : { fn: 'near', unit: '{near_unit}', dec: 0 };
    return null;
  }
  function setReading(src, key, dec) {
    const uf = unitFor(key);
    const d = uf && uf.fn === 'tyre' ? 0 : dec;
    src.text = '{' + (uf ? uf.fn + '(' + key + ')' : key) + (d ? ':' + d : '') + '}';
    const unit = uf ? uf.unit : sigUnit(key);
    if (unit) src.unit = unit; else delete src.unit;
  }
  // a sample reading as this widget would show it in the simulated units
  function sampleIn(key, v, d) {
    const uf = unitFor(key);
    let x = v, unit = sigUnit(key);
    if (uf && W) {
      x = num(ODW.evaluate(ODW.parse(uf.fn + '(' + v + ')'), W.lookup));
      unit = String(W.lookup(uf.unit.slice(1, -1)) || unit);
    }
    return (x == null ? '–' : x.toFixed(d)) + (unit ? ' ' + unit : '');
  }
  const tyreUnit = () => String((W && W.lookup('tyre_unit')) || 'psi');
  const objectCount = () => (S.doc ? S.doc.layers.filter(l => l && TYPES[l.type] && TYPES[l.type].object).length : 0);
  function usedSignals() {
    return W ? W.names().filter(k => SIG[k] || (!ODW.BUILTINS.has(k) && TOKENS.night[k] === undefined)) : [];
  }
  const groupLabel = gname => trOr('group.' + gname, gname);
  function groupedSignals(filter) {
    const groups = Object.create(null), order = [];
    S.catalog.forEach(s => {
      if (filter && !filter(s.key)) return;
      const gname = s.group || 'Other';
      if (!groups[gname]) { groups[gname] = []; order.push(gname); }
      groups[gname].push(s);
    });
    const rank = gname => { const i = GROUP_ORDER.indexOf(gname); return i < 0 ? 99 : i; };
    order.sort((a, b) => rank(a) - rank(b));
    return order.map(gname => [gname, groups[gname]]);
  }
  const typeLabel = type => trOr('type.' + type, TYPES[type] ? TYPES[type].label : String(type));

  // ── simulated car ──────────────────────────────────────────────────────────
  function driveStep(kind) {
    sim.t += 1;
    const t = sim.t, d = S.data;
    const dip = t % 90 < 14 ? Math.pow(Math.sin(Math.PI * (t % 90) / 14), 2) : 0;
    let kmh = kind === 'highway' ? 108 + 9 * Math.sin(t / 31) + 4 * Math.sin(t / 7) : 46 + 26 * Math.sin(t / 23) + 12 * Math.sin(t / 7) - 44 * dip;
    kmh = Math.max(0, Math.min(135, kmh));
    const acc = sim.t === 1 ? 0 : kmh - sim.prev;
    sim.prev = kmh;
    let kw = 0.0011 * kmh * kmh + 0.12 * kmh + acc * (acc > 0 ? 9.5 : 7.0);
    if (acc < -0.8) kw = acc * 6.2;
    kw = Math.max(-70, Math.min(165, kw));
    d.speed = Math.round(kmh);
    d.power = Math.round(kw);
    d.accel_pct = acc > 0 ? Math.min(100, Math.round(18 + acc * 9)) : 0;
    d.brake_pct = acc < -0.8 ? Math.min(100, Math.round(-acc * 8)) : 0;
    d.motor_front_rpm = d.motor_rear_rpm = Math.round(kmh * 92);
    d.trip_km = +(nz(d.trip_km) + kmh / 3600).toFixed(2);
    d.trip_kwh = +(nz(d.trip_kwh) + kw / 3600).toFixed(3);
    d.trip_minutes = +(nz(d.trip_minutes) + 1 / 60).toFixed(2);
    d.trip_top_kmh = Math.max(nz(d.trip_top_kmh), Math.round(kmh));
    if (d.trip_km > 0.1) d.trip_wh_per_km = Math.round(d.trip_kwh * 1000 / d.trip_km);
    d.soc = Math.max(0, +(nz(d.soc) - kw / 3600 / 60 * 100).toFixed(2));
    d.ev_range_km = Math.round(d.soc * 4.4);
    d.calibrated_range_km = Math.round(d.soc * 4.2);
    d.grade_pct = +(3 * Math.sin(t / 37)).toFixed(1);
    d.altitude_m = Math.round(nz(d.altitude_m) + kmh / 3.6 * d.grade_pct / 100);
  }

  // hazards close in at the car's speed; past one, the next comes into view
  function hazardStep() {
    const d = S.data;
    d.hazard_ahead = true;
    d.hazard_m = Math.round(nz(d.hazard_m) - Math.max(9, nz(d.speed) / 3.6) * 2.5);
    if (d.hazard_m < 15) {
      sim.hz = (sim.hz + 1) % HAZARDS.length;
      const h = HAZARDS[sim.hz];
      Object.assign(d, { hazard_type: h[0], hazard_sev: h[1], hazard_count: h[2], hazard_m: 340 });
    }
  }

  function chargeStep(kind) {
    const d = S.data;
    if (!d.is_charging) return;
    const limit = nz(d.charge_limit) || 100;
    const kw = kind === 'dc' ? Math.max(18, 92 - Math.max(0, nz(d.soc) - 50) * 2.4) : 7.2;
    d.charge_power = +kw.toFixed(1);
    d.soc = Math.min(limit, +(nz(d.soc) + kw / 3600 / 60 * 100 * 8).toFixed(2));
    d.ev_range_km = Math.round(d.soc * 4.4);
    d.calibrated_range_km = Math.round(d.soc * 4.2);
    d.charging_eta_minutes = Math.max(0, Math.round((limit - d.soc) / 100 * 60 / kw * 60));
    if (d.soc >= limit) Object.assign(d, NOT_CHARGING);
  }

  // ── live readings: what the car feeds a custom widget now; the simulator stands in without them ──
  const LIVE_MS = 2000;
  const live = { ok: false, on: false, values: Object.create(null), fails: 0, timer: 0, busy: false };
  function fetchLive() {
    if (live.busy) return Promise.resolve(false);
    live.busy = true;
    return api('GET', '/api/widgets/values').then(r => {
      live.busy = false;
      const v = r.ok && r.data && r.data.values;
      // a failed read keeps the last values; three in a row read as no fresh data
      if (!v || typeof v !== 'object' || Array.isArray(v)) { live.fails++; if (live.on) liveChanged(); return false; }
      const next = Object.create(null);
      Object.keys(v).forEach(k => {
        if (!/^[a-z_][a-z0-9_]*$/.test(k) || k in Object.prototype) return;
        const x = v[k];
        next[k] = (typeof x === 'number' && isFinite(x)) || typeof x === 'boolean' ? x : null;
      });
      live.values = next;
      live.fails = 0;
      live.ok = true;
      if (live.on) liveChanged();
      return true;
    });
  }
  // polls only while the Design tab is on screen
  function startLive() {
    if (!live.on || !S.ready || S.mode !== 'design' || document.hidden) return;
    fetchLive();
    if (live.timer) return;
    live.timer = setInterval(() => {
      if (!live.on || S.mode !== 'design' || document.hidden) { clearInterval(live.timer); live.timer = 0; return; }
      fetchLive();
    }, LIVE_MS);
  }
  // a change in freshness rebinds even when the values stay the same
  function liveChanged() { feed(!(W && W.env.stale !== staleNow())); updateSimValues(); updateResults(); updateMeter(); }
  function goLive(on) {
    if (on && !live.ok) return;
    if (!on && live.on) Object.keys(live.values).forEach(k => { if (SIG[k] && live.values[k] != null) S.data[k] = live.values[k]; });
    live.on = on;
    if (on) { S.units = Object.assign({}, S.carUnits); S.hist = []; renderUnits(); rebuild(); renderInspector(); }
    else if (live.timer) { clearInterval(live.timer); live.timer = 0; }
    renderSim();
    updateMeter();
    startLive();
  }
  /** A live reading as the car words it, in its units: {v, u}, or null when the car doesn't report it. */
  function liveText(k, v) {
    const s = SIG[k];
    if (v == null || !s) return null;
    const type = sigType(s);
    if (type === 'bool' || typeof v === 'boolean') return { v: tr(v ? 'yes' : 'no'), u: '' };
    if (type === 'enum' && sigOptions(k).length) return { v: optLabel(k, v), u: '' };
    const x = num(v), uf = unitFor(k);
    if (x == null) return null;
    if (!uf || !W) return { v: fmtSim(s, x), u: s.unit || '' };
    const at = id => (id === 'v' ? x : W.lookup(id));
    const unit = String(W.lookup(uf.unit.slice(1, -1)) || '');
    if (uf.fn === 'tyre') return { v: ODW.tyreText(x, String(W.lookup('tyre_unit'))), u: unit };
    const y = num(ODW.evaluate(ODW.parse(uf.fn + '(v)'), at));
    return y == null ? null : { v: fmtSim(s, y), u: unit };
  }

  function effective() {
    const out = {};
    if (live.on) { Object.keys(live.values).forEach(k => { out[k] = live.values[k]; }); return out; }
    Object.keys(S.data).forEach(k => { out[k] = S.unknown.has(k) ? null : S.data[k]; });
    return out;
  }
  const drivingNow = () => { const d = live.on ? live.values : S.data; return !!(d.driving || d.reversing); };
  const staleNow = () => (live.on ? live.fails >= 3 : S.stale);

  function pushHistory(t) {
    S.hist.push({ t, data: effective() });
    if (S.hist.length > 320) S.hist.shift();
  }

  function setScenario(id) {
    if (live.on) goLive(false);
    S.scenario = id;
    const sc = SCEN[id];
    if (sc) Object.assign(S.data, sc.set);
    if (sc && sc.drive) {
      // two minutes of earlier driving so the traces aren't empty
      const end = ODW.now();
      S.hist = S.hist.filter(h => h.t < end - 121000);
      for (let i = 120; i >= 1; i--) { driveStep(sc.drive); pushHistory(end - i * 1000); }
      rebuild();
    }
    feed(true);
    renderSim();
  }

  function tick() {
    if (!S.ready || document.hidden || S.mode !== 'design') return;
    const sc = live.on ? null : SCEN[S.scenario];
    if (sc && sc.drive) driveStep(sc.drive);
    if (sc && sc.charge) chargeStep(sc.charge);
    if (sc && sc.hazards) hazardStep();
    pushHistory(ODW.now());
    feed(true);
    updateSimValues();
    updateResults();
    updateMeter();
  }

  /** One reading to the widget, like the overlay's poll: unchanged data is a no-op. */
  function feed(animate) {
    if (!W) return;
    W.env.driving = drivingNow();
    W.env.stale = staleNow();
    W.env.reduceMotion = S.reduceMotion;
    const eff = effective();
    const j = JSON.stringify(eff);
    if (j === boundJson && animate) return;
    boundJson = j;
    W.bind(eff, animate);
    if (shell) shell.classList.toggle('is-stale', staleNow());
    dirty = true;
    kick();
  }

  // ── widget on the stage ───────────────────────────────────────────────────
  function rebuild() {
    const dpr = window.devicePixelRatio || 1;
    W = new Widget(S.doc, { theme: S.theme, px: dpr * 1.5 });
    W.layers.forEach(L => { L.editorHidden = S.hidden.has(L.src); });
    W.env.driving = drivingNow(); W.env.stale = staleNow(); W.env.reduceMotion = S.reduceMotion; W.env.units = S.units;
    W.seed(S.hist);
    boundJson = '';
    feed(false);
    mountShell();
    dirty = true;
    kick();
  }

  function mountShell() {
    const slot = $('#wbSlot');
    const want = W.w + 'x' + W.h;
    if (!shell || shell.getAttribute('data-size') !== want || !slot.contains(shell)) {
      slot.innerHTML = '';
      shell = el('div', 'wb-gw', slot);
      shell.setAttribute('data-size', want);
      shell.style.width = W.w + 'px';
      shell.style.height = W.h + 'px';
      el('div', 'wb-gw-shadow', shell);
      const clip = el('div', 'wb-gw-clip', shell);
      el('div', 'wb-gw-body', clip);
      canvas = el('canvas', 'wb-gw-object', clip);
      el('div', 'wb-gw-front', clip);
      const fog = el('div', 'wb-gw-fog', clip);
      el('div', 'wb-gw-fog-label', fog, '◷ ' + esc(tr('stale_label')));
      el('div', 'wb-gw-sheen', clip);
      selCanvas = el('canvas', 'wb-sel', shell);
      selCanvas.setAttribute('aria-label', tr('preview_aria'));
      el('div', 'wb-badge', shell);
      const dpr = window.devicePixelRatio || 1, k = 1.5;
      canvas.width = Math.round(W.w * dpr * k); canvas.height = Math.round(W.h * dpr * k);
      selCanvas.width = Math.round((W.w + 48) * dpr * k); selCanvas.height = Math.round((W.h + 48) * dpr * k);
      g = canvas.getContext('2d'); sg = selCanvas.getContext('2d');
      g.setTransform(dpr * k, 0, 0, dpr * k, 0, 0);
      sg.setTransform(dpr * k, 0, 0, dpr * k, 24 * dpr * k, 24 * dpr * k);
      wireDrag();
    }
    shell.classList.toggle('is-stale', staleNow());
    setText($('.wb-badge', shell), tr('badge', { name: S.doc.name || tr('untitled'), size: sizeLabel(S.doc.size), w: W.w, h: W.h }));
    fitPreview();
  }
  const sizeLabel = s => trOr('size_' + s, String(s || 'small'));

  // the widget keeps its real proportions and shrinks to the stage's width
  function fitPreview() {
    const slot = $('#wbSlot'), stage = $('#wbStage');
    if (!slot || !stage || !W) return;
    const availW = stage.clientWidth - (S.narrow ? 28 : 60);
    // below: the dock and its gap; above: the clock row and the name badge, wide layout only
    const availH = stage.clientHeight - (S.narrow ? 48 : 82) - (S.narrow ? 8 : 80);
    const zoom = S.narrow ? 1 : S.zoom;
    const fit = Math.min(zoom, availW / (W.w + 30), availH / W.h);
    const k = availW > 0 && availH > 0 ? Math.max(0.25, fit) : 1;
    slot.style.transform = Math.abs(k - 1) < 0.001 ? '' : 'scale(' + k.toFixed(3) + ')';
  }

  // ── frame loop: draws only while something changed or moves, like GlassTicker ─
  let rafId = 0, lastTs = 0, lastDraw = 0, draws = 0, rate = 0, rateAt = 0;
  function stageVisible() { return S.ready && S.mode === 'design' && !document.hidden; }
  function kick() { if (!rafId && stageVisible()) { lastTs = 0; rafId = requestAnimationFrame(frame); } }
  function frame(ts) {
    rafId = 0;
    if (!W || !g || !stageVisible()) return;
    const dt = lastTs ? Math.min(0.05, (ts - lastTs) / 1000) : 1 / 60;
    lastTs = ts;
    const busy = W.step(dt);
    const minMs = W.minFrameMs;
    if (dirty || (busy && (minMs === 0 || ts - lastDraw >= minMs))) {
      g.clearRect(0, 0, W.w, W.h);
      W.draw(g, ts);
      drawSelection();
      lastDraw = ts; dirty = false; draws++;
    }
    if (busy || dirty) rafId = requestAnimationFrame(frame);
  }

  let meterEls = null;
  function updateMeter() {
    const host = $('#wbMeter');
    if (!host || !W) return;
    if (!meterEls) {
      host.innerHTML = '';
      const pill = cls => { const p = el('span', 'status-badge inactive wb-badge-plain wb-pill ' + cls, host); el('i', '', p); const s = el('span', '', p); s.textContent = ''; return { p, s }; };
      meterEls = { a: pill('wb-pill-rate'), b: pill('wb-pill-drive'), c: pill('wb-pill-data') };
    }
    const t = ODW.now();
    if (rateAt) rate = Math.round(draws * 1000 / Math.max(1, t - rateAt));
    draws = 0; rateAt = t;
    let state;
    if (W.springsMoving) state = tr('meter_gliding');
    else if (W.loopRunning()) state = tr('meter_loop');
    else if (W.hasActiveLoop()) state = tr('meter_paused', { why: tr(staleNow() ? 'why_stale' : S.reduceMotion ? 'why_motion' : 'why_driving') });
    else state = tr('meter_idle');
    const busy = W.springsMoving || W.loopRunning();
    meterEls.a.p.classList.toggle('live', busy);
    setText(meterEls.a.s, tr('meter_rate', { n: rate }) + ' · ' + state);
    meterEls.b.p.classList.toggle('warn', drivingNow());
    setText(meterEls.b.s, tr(drivingNow() ? 'pill_driving' : 'pill_parked'));
    meterEls.c.p.classList.add('live');
    setText(meterEls.c.s, tr(live.on ? 'pill_live' : 'pill_data'));
  }

  function drawSelection() {
    if (!sg) return;
    sg.clearRect(-24, -24, W.w + 48, W.h + 48);
    if (S.sel < 0) return;
    const L = W.layers[S.sel];
    if (!L || L.error) return;
    const b = W.bounds(L, g);
    const x = b[0], y = b[1], w = b[2], h = b[3];
    sg.save();
    sg.setLineDash([4, 3]); sg.lineWidth = 1.2; sg.strokeStyle = 'rgba(93,219,182,.95)';
    sg.strokeRect(x - 2, y - 2, w + 4, h + 4);
    sg.setLineDash([]); sg.fillStyle = '#5DDBB6';
    [[x - 2, y - 2], [x + w + 2, y - 2], [x - 2, y + h + 2], [x + w + 2, y + h + 2]].forEach(p => sg.fillRect(p[0] - 2.5, p[1] - 2.5, 5, 5));
    sg.restore();
  }

  function wireDrag() {
    let drag = null;
    selCanvas.style.touchAction = 'none';
    selCanvas.addEventListener('pointerdown', e => {
      const r = selCanvas.getBoundingClientRect();
      const k = r.width / (W.w + 48);
      const px = (e.clientX - r.left) / k - 24, py = (e.clientY - r.top) / k - 24;
      let hit = -1;
      for (let i = W.layers.length - 1; i >= 0; i--) {
        const L = W.layers[i];
        if (L.error || L.editorHidden || !W.visible(L)) continue;
        const b = W.bounds(L, g);
        if (px >= b[0] - 3 && px <= b[0] + b[2] + 3 && py >= b[1] - 3 && py <= b[1] + b[3] + 3) { hit = i; break; }
      }
      select(hit);
      if (hit < 0) return;
      const src = S.doc.layers[hit];
      const keys = TYPES[src.type] && TYPES[src.type].props.cx ? ['cx', 'cy'] : ['x', 'y'];
      drag = { k, keys, sx: e.clientX, sy: e.clientY, ox: nz(src[keys[0]]), oy: nz(src[keys[1]]), src, moved: false };
      try { selCanvas.setPointerCapture(e.pointerId); } catch (err) { /* no capture */ }
    });
    selCanvas.addEventListener('pointermove', e => {
      if (!drag) return;
      const nx = Math.round((drag.ox + (e.clientX - drag.sx) / drag.k) * 2) / 2;
      const ny = Math.round((drag.oy + (e.clientY - drag.sy) / drag.k) * 2) / 2;
      if (nx === drag.src[drag.keys[0]] && ny === drag.src[drag.keys[1]]) return;
      drag.src[drag.keys[0]] = nx;
      drag.src[drag.keys[1]] = ny;
      drag.moved = true;
      rebuild();
      syncGeoInputs();
    });
    const end = () => { if (drag) { const moved = drag.moved; drag = null; if (moved) { renderFile(); renderChecks(); saveDraft(); renderStatus(); } } };
    selCanvas.addEventListener('pointerup', end);
    selCanvas.addEventListener('pointercancel', end);
  }

  // ── left: templates, layers, saved widgets ────────────────────────────────
  function blankDoc() {
    return {
      format: 1, name: tr('blank_name'), size: 'small', about: '',
      layers: [
        { type: 'text', name: tr('blank_layer_title'), x: 26, y: 62, size: 15, weight: 500, text: tr('blank_title'), color: 'text2' },
        { type: 'text', name: tr('blank_layer_value'), x: 26, y: 110, size: 38, weight: 600, text: '{soc}', unit: '%', color: 'text', motion: 'number' },
      ],
    };
  }
  function templateList() { return S.templates.concat([{ id: 'blank', name: tr('blank_name'), doc: blankDoc(), blank: true }]); }

  function renderTemplates() {
    const host = $('#wbTpls');
    if (!host) return;
    host.innerHTML = '';
    const list = templateList();
    list.forEach(tp => {
      const b = btn('wb-tpl' + (tp.id === S.tpl ? ' on' : ''), host, '', () => loadTemplate(tp.id));
      const cv = el('canvas', '', b);
      cv.setAttribute('aria-hidden', 'true');
      el('b', '', b, esc(trOr('tpl.' + tp.id, tp.name || tp.doc.name || '')));
      const n = Array.isArray(tp.doc.layers) ? tp.doc.layers.length : 0;
      el('small', '', b, esc(tr('tpl_meta', { size: sizeLabel(tp.doc.size), n })));
      b.title = trOr('tpl.' + tp.id + '_about', tp.doc.about || '');
      requestAnimationFrame(() => ODW.renderStill(cv, tp.doc, { theme: S.theme, units: S.units }));
    });
    if (!S.templates.length && S.ready) el('div', 'wb-empty wb-span', host, esc(tr('tpl_none')));
  }

  function guardDiscard() {
    if (!S.doc || JSON.stringify(S.doc) === S.baseJson) return Promise.resolve(true);
    return confirmDlg({ title: tr('discard_title'), body: tr('discard_body'), confirmLabel: tr('discard_ok'), cancelLabel: tr('cancel'), danger: true });
  }

  function loadTemplate(id) {
    const tp = templateList().find(x => x.id === id);
    if (!tp) return;
    guardDiscard().then(ok => {
      if (!ok) return;
      openDoc(clone(tp.doc), { id: null, saved: null, tpl: id });
    });
  }

  /** Puts a doc in the editor. opts: {id, saved (json the car holds), tpl} */
  function openDoc(doc, opts) {
    opts = opts || {};
    S.doc = ODW.cleanDoc(doc);
    if (!Array.isArray(S.doc.layers)) S.doc.layers = [];
    if (!SIZES[S.doc.size]) S.doc.size = 'small';
    S.docId = opts.id || null;
    S.pendingId = opts.pendingId || null;
    S.tpl = opts.tpl || null;
    S.baseJson = JSON.stringify(S.doc);
    S.savedJson = opts.saved || null;
    S.hidden = new WeakSet();
    const pick = S.doc.layers.findIndex(l => l && typeof (l.value || l.level) === 'string');
    S.sel = pick;
    shell = null;
    rebuild();
    renderAll();
    saveDraft();
  }

  function renderLayers() {
    const host = $('#wbLayers');
    if (!host) return;
    host.innerHTML = '';
    const wr = btn('wb-lrow wb-lrow-widget' + (S.sel === -1 ? ' on' : ''), host, '', () => select(-1));
    wr.innerHTML = '<span class="wb-ic">' + icon('widget') + '</span><span class="wb-nm">' + esc(S.doc.name || tr('untitled')) + ' <small>· ' + esc(tr('widget_word')) + '</small></span>';
    for (let i = S.doc.layers.length - 1; i >= 0; i--) {
      const src = S.doc.layers[i], L = W.layers[i];
      const row = el('div', 'wb-lrow' + (S.sel === i ? ' on' : '') + (S.hidden.has(src) ? ' wb-hiddenl' : ''), host);
      row.setAttribute('role', 'button');
      row.tabIndex = 0;
      const bound = L && Object.keys(L.props).some(k => L.props[k].bound);
      const errs = L && (L.error || Object.keys(L.errors).length);
      const actBtn = (a, key, ic, cls) => '<button type="button" class="btn btn-icon' + (cls || '') + '" data-a="' + a + '" title="' + esc(tr(key)) + '" aria-label="' + esc(tr(key)) + '">' + icon(ic) + '</button>';
      row.innerHTML = '<span class="wb-ic">' + typeIcon(src.type) + '</span><span class="wb-nm">' + esc(src.name || typeLabel(src.type)) + '</span><span class="wb-tags">' +
        (errs ? '<span class="wb-tag err" title="' + esc(tr('tag_error')) + '">!</span>' : '') +
        (L && L.loop ? '<span class="wb-tag loop" title="' + esc(tr('tag_loop')) + '">⟳</span>' : '') +
        (bound ? '<span class="wb-tag" title="' + esc(tr('tag_bound')) + '">ƒ</span>' : '') + '</span>' +
        '<span class="wb-act">' + actBtn('eye', S.hidden.has(src) ? 'act_show' : 'act_hide', S.hidden.has(src) ? 'eyeOff' : 'eye') + actBtn('up', 'act_up', 'up') +
        actBtn('down', 'act_down', 'down') + actBtn('dup', 'act_dup', 'copy') + actBtn('del', 'act_del', 'trash', ' btn-danger-ghost') + '</span>';
      const act = a => {
        if (a === 'eye') { if (S.hidden.has(src)) S.hidden.delete(src); else S.hidden.add(src); rebuild(); renderLayers(); return; }
        if (a === 'del') { S.doc.layers.splice(i, 1); S.sel = -1; }
        if (a === 'dup') {
          if (S.doc.layers.length >= MAX_LAYERS) { toast(tr('max_layers', { n: MAX_LAYERS }), 'warning'); return; }
          if (TYPES[src.type] && TYPES[src.type].object && objectCount() >= ODW.MAX_OBJECTS) { toast(tr('lib_objects_full', { n: ODW.MAX_OBJECTS }), 'warning'); return; }
          const c = clone(src);
          S.doc.layers.splice(i + 1, 0, c);
          S.sel = i + 1;
        }
        if (a === 'up' && i < S.doc.layers.length - 1) { const tmp = S.doc.layers[i]; S.doc.layers[i] = S.doc.layers[i + 1]; S.doc.layers[i + 1] = tmp; if (S.sel === i) S.sel = i + 1; }
        if (a === 'down' && i > 0) { const tmp = S.doc.layers[i]; S.doc.layers[i] = S.doc.layers[i - 1]; S.doc.layers[i - 1] = tmp; if (S.sel === i) S.sel = i - 1; }
        changed();
        renderInspector();
      };
      row.addEventListener('click', e => {
        const t = e.target && e.target.closest ? e.target.closest('[data-a]') : e.target;
        const a = t && t.getAttribute ? t.getAttribute('data-a') : null;
        if (!a) { select(i); return; }
        e.stopPropagation();
        act(a);
      });
      row.addEventListener('keydown', e => { if ((e.key === 'Enter' || e.key === ' ') && e.target === row) { e.preventDefault(); select(i); } });
    }
    const list = $('#wbAddList');
    list.innerHTML = '';
    const objectsFull = objectCount() >= ODW.MAX_OBJECTS;
    LIBRARY.forEach(grp => {
      const isObj = grp[0] === 'lib_car' || grp[0] === 'lib_road';
      el('h5', 'wb-section-title', list, esc(tr(grp[0])));
      if (isObj && objectsFull && grp[0] === 'lib_car') el('div', 'wb-menu-note', list, esc(tr('lib_objects_full', { n: ODW.MAX_OBJECTS })));
      grp[1].forEach(item => {
        const b = btn('', list, '<span class="wb-ic">' + icon(item[0]) + '</span><span class="wb-tx"><b>' + esc(tr(item[1])) + '</b><small>' + esc(tr(item[1] + '_desc')) + '</small></span>', () => {
          closeAddMenu();
          if (isObj && objectCount() >= ODW.MAX_OBJECTS) { toast(tr('lib_objects_full', { n: ODW.MAX_OBJECTS }), 'warning'); return; }
          if (S.doc.layers.length >= MAX_LAYERS) { toast(tr('max_layers', { n: MAX_LAYERS }), 'warning'); return; }
          S.doc.layers.push(item[2]());
          S.sel = S.doc.layers.length - 1;
          changed();
          renderInspector();
          if (S.narrow) showPanel('edit');
        });
        b.setAttribute('role', 'menuitem');
        if (isObj && objectsFull) b.disabled = true;
      });
    });
  }

  // an object starts where it fits on this glass
  function carLayer(extra) {
    const l = Object.assign({ type: 'car', name: tr('type.car') }, clone(TYPES.car.defaults), extra);
    return placed(l);
  }
  function placed(l) {
    const tmp = new Widget({ size: S.doc.size, layers: [l] });
    const L = tmp.layers[0];
    if (!L || L.error) return l;
    const b = tmp.bounds(L, g || document.createElement('canvas').getContext('2d'));
    if (b[0] + b[2] > tmp.w) l.x = Math.max(0, Math.round(tmp.w - b[2] - 12));
    if (b[1] + b[3] > tmp.h) l.y = Math.max(0, Math.round((tmp.h - b[3]) / 2));
    if (l.parts === 'pack') { l.x = Math.max(0, Math.round((tmp.w - b[2]) / 2)); }
    return l;
  }

  function closeAddMenu() {
    const m = $('#wbAddMenu'), list = $('#wbAddList'), b = $('#wbAddBtn');
    if (m) m.classList.remove('open');
    if (list) list.classList.remove('open');
    if (b) b.setAttribute('aria-expanded', 'false');
  }
  // from the button's leading edge into the content, flipped up or capped (with its own scroll) to stay on screen
  function placeAddMenu() {
    const b = $('#wbAddBtn'), list = $('#wbAddList');
    if (!b || !list) return;
    const r = b.getBoundingClientRect(), pad = 12, gap = 6;
    const vw = document.documentElement.clientWidth || window.innerWidth || 1024, vh = window.innerHeight || 768;
    const w = Math.min(300, vw - 2 * pad);
    const rtl = document.documentElement.getAttribute('dir') === 'rtl';
    list.style.width = w + 'px';
    list.style.left = Math.max(pad, Math.min(rtl ? r.right - w : r.left, vw - w - pad)) + 'px';
    list.style.maxHeight = 'none';
    const want = list.scrollHeight, below = vh - r.bottom - gap - pad, above = r.top - gap - pad;
    const down = below >= want || below >= above;
    list.style.top = down ? (r.bottom + gap) + 'px' : 'auto';
    list.style.bottom = down ? 'auto' : (vh - r.top + gap) + 'px';
    list.style.maxHeight = Math.max(120, Math.min(want, down ? below : above)) + 'px';
  }

  function renderMine() {
    const host = $('#wbMine'), count = $('#wbMineCount');
    if (!host) return;
    host.innerHTML = '';
    if (count) { setText(count, S.mineState === 'ok' ? tr('mine_count', { n: S.mine.length, max: S.limits.maxWidgets }) : ''); count.hidden = S.mineState !== 'ok'; }
    if (S.mineState === 'loading' && !S.mine.length) { el('div', 'wb-empty', host, esc(tr('loading'))); return; }
    if (S.mineState === 'error' && !S.mine.length) {
      const e = el('div', 'wb-empty', host, esc(tr('mine_error')) + ' ');
      btn('wb-link', e, esc(tr('retry')), () => loadMine());
      return;
    }
    if (!S.mine.length) {
      const box = el('div', 'empty-state', el('div', 'card-body', el('div', 'card wb-mine-empty', host)));
      box.innerHTML = '<svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + ICONS.widget + '</svg>';
      el('p', 'empty-text', box, esc(tr('mine_empty')));
      btn('btn btn-secondary', box, icon('plus') + '<span>' + esc(tr('mine_design')) + '</span>', () => setMode('design'));
      return;
    }
    const list = S.mine.slice().sort((x, y) => nz(y.updatedMs) - nz(x.updatedMs));
    list.forEach(w => {
      const name = (w.doc && w.doc.name) || tr('untitled');
      const shared = !!w.communityId && w.source !== 'community';
      const size = typeof w.doc.size === 'string' && SIZES[w.doc.size] ? w.doc.size : 'small';
      const item = el('div', 'card wb-item' + (w.shown ? ' is-shown' : '') + (w.id === S.docId ? ' on' : ''), host);
      item.setAttribute('data-id', w.id);
      const row = el('div', 'wb-item-row', item);
      const cv = el('canvas', 'wb-item-preview', row);
      cv.setAttribute('role', 'img');
      cv.setAttribute('aria-label', tr('cm_preview_of', { name }));
      cv._doc = w.doc;
      const info = el('div', 'wb-item-info', row);
      el('div', 'wb-item-name', info, esc(name)).setAttribute('dir', 'auto');
      el('div', 'wb-item-meta', info, esc(tr('mine_meta', { size: sizeLabel(size), w: SIZES[size][0], h: SIZES[size][1] })));
      const badges = el('div', 'wb-item-badges', info);
      if (w.shown) el('span', 'status-badge active', badges, esc(tr('show_on_car_short')));
      if (shared) el('span', 'status-badge wb-badge-tonal', badges, esc(tr('mine_shared')));
      if (w.source === 'community') el('span', 'status-badge inactive', badges, esc(tr('mine_from_community')));
      if (w.id === S.docId) el('span', 'status-badge inactive', badges, esc(tr('mine_editing')));
      const acts = el('div', 'wb-item-acts', row);
      const act = (cls, ic, label, fn) => { const x = btn('btn btn-icon ' + cls, acts, icon(ic), fn); x.title = label; x.setAttribute('aria-label', label); return x; };
      if (w.source !== 'community') act('wb-item-share', 'upload', tr(shared ? 'share_update_named' : 'share_named', { name }), () => share(w));
      act('wb-item-edit', 'pencil', tr('edit_named', { name }), () => openMine(w.id));
      act('wb-item-dup', 'copy', tr('dup_named', { name }), () => openCopy(w.doc, name));
      act('btn-danger-ghost wb-btn-icon', 'trash', tr('delete_named', { name }), () => removeMine(w));
      const foot = el('div', 'wb-item-foot', item);
      el('span', 'wb-item-state', foot, '<i></i>' + esc(tr(w.shown ? 'mine_on_screen' : 'mine_off_screen')));
      const sw = el('label', 'toggle-switch wb-switch', el('div', 'wb-showsw', foot));
      sw.title = tr('show_on_car');
      const cb = el('input', '', sw); cb.type = 'checkbox'; cb.checked = !!w.shown;
      cb.setAttribute('aria-label', tr('show_on_car_named', { name }));
      el('span', 'toggle-slider', sw);
      cb.addEventListener('change', () => setShown(w, cb.checked, cb));
    });
    paintMine();
  }

  // previews draw once laid out; a hidden tab, a new look or new units paints them again
  let mineRaf = 0;
  function paintMine() {
    if (mineRaf) return;
    mineRaf = requestAnimationFrame(() => {
      mineRaf = 0;
      const look = S.theme + '/' + S.units.distance + '/' + S.units.tyre;
      $$('#wbMine canvas.wb-item-preview').forEach(cv => {
        if (cv._painted === look || !cv._doc) return;
        if (ODW.renderStill(cv, cv._doc, { theme: S.theme, units: S.units })) cv._painted = look;
      });
    });
  }

  function loadMine() {
    if (!S.mine.length) { S.mineState = 'loading'; renderMine(); }
    return api('GET', '/api/widgets/custom').then(r => {
      if (r.ok && Array.isArray(r.data.widgets)) {
        S.mine = r.data.widgets.filter(w => w && typeof w.id === 'string' && w.doc && typeof w.doc === 'object');
        const lim = r.data.limits || {};
        S.limits = { maxWidgets: num(lim.maxWidgets) || 32, maxBytes: num(lim.maxBytes) || ODW.MAX_BYTES };
        S.mineState = 'ok';
      } else S.mineState = 'error';
      renderMine();
      renderStatus();
      return S.mineState === 'ok';
    });
  }

  function openMine(id) {
    const w = S.mine.find(x => x.id === id);
    if (!w) return;
    if (id === S.docId && JSON.stringify(S.doc) === S.baseJson) { setMode('design'); if (S.narrow) showPanel('edit'); return; }
    guardDiscard().then(ok => {
      if (!ok) return;
      const doc = clone(w.doc);
      openDoc(doc, { id: w.id, saved: JSON.stringify(ODW.cleanDoc(doc)) });
      setMode('design');
      if (S.narrow) showPanel('edit');
    });
  }

  function setShown(w, shown, cb) {
    cb.disabled = true;
    api('POST', '/api/widgets/custom/' + encodeURIComponent(w.id) + '/show', { shown }).then(r => {
      cb.disabled = false;
      if (r.ok) {
        w.shown = shown;
        toast(tr(shown ? 'shown_on' : 'shown_off', { name: (w.doc && w.doc.name) || tr('untitled') }), 'success');
        renderMine();
        renderStatus();
      } else {
        cb.checked = !shown;
        toast(errorText(r, 'show_failed'), 'error');
      }
    });
  }

  function askDelete(w, name) {
    if (!(w.communityId && w.source !== 'community')) {
      return confirmDlg({ title: tr('delete_title'), body: tr('delete_body', { name }), confirmLabel: tr('delete'), cancelLabel: tr('cancel'), danger: true })
        .then(ok => ({ ok, community: false }));
    }
    return new Promise(resolve => {
      let answer = { ok: false, community: false };
      modal((card, close) => {
        el('h3', 'soh-modal-title', card).textContent = tr('delete_title');
        el('p', 'wb-note', card).textContent = tr('delete_shared_body', { name });
        const row = el('div', 'setting-row setting-row--inline wb-modal-toggle', card);
        el('div', 'setting-name', el('div', 'setting-info', row)).textContent = tr('delete_also_community');
        const cb = toggle(el('div', 'setting-control', row), false, tr('delete_also_community'));
        const acts = el('div', 'soh-modal-actions', card);
        btn('btn btn-secondary', acts, esc(tr('cancel')), close).setAttribute('data-autofocus', '');
        btn('btn btn-danger', acts, esc(tr('delete')), () => { answer = { ok: true, community: cb.checked }; close(); });
      }, 'wb-modal-form', () => resolve(answer));
    });
  }

  function removeMine(w) {
    const name = (w.doc && w.doc.name) || tr('untitled');
    askDelete(w, name).then(ans => {
      if (!ans.ok) return;
      // the community copy goes first, so a failure there leaves everything as it was
      const first = ans.community
        ? api('DELETE', '/api/community/widgets/item/' + encodeURIComponent(w.communityId)).then(r => {
          if (r.ok || r.status === 404) return true;
          toast(errorText(r, 'cm_remove_failed'), 'error');
          return false;
        })
        : Promise.resolve(true);
      first.then(go => {
        if (!go) return;
        if (ans.community && window.WidgetCommunity && WidgetCommunity.dropItem) WidgetCommunity.dropItem(w.communityId);
        removeLocal(w, name);
      });
    });
  }

  function removeLocal(w, name) {
    api('DELETE', '/api/widgets/custom/' + encodeURIComponent(w.id)).then(r => {
      if (!r.ok && r.status !== 404) { toast(errorText(r, 'delete_failed'), 'error'); return; }
      S.mine = S.mine.filter(x => x.id !== w.id);
      if (S.docId === w.id) {
        S.docId = null; S.savedJson = null; S.pendingId = null;
        toast(tr('deleted_still_open', { name }), 'info');
        saveDraft();
      } else toast(tr('deleted', { name }), 'success');
      renderMine();
      renderStatus();
    });
  }

  /** True once the car holds [id]'s latest edits: saves first (after asking) when [id] is open with unsaved changes. */
  function ensureSaved(id) {
    if (!(id && id === S.docId && JSON.stringify(ODW.cleanDoc(S.doc)) !== S.savedJson)) return Promise.resolve(true);
    return confirmDlg({ title: tr('share_save_title'), body: tr('share_save_body'), confirmLabel: tr('save'), cancelLabel: tr('cancel') }).then(ok => (ok ? save() : false));
  }

  // the open widget: a new one is saved first (after asking), a saved one shares the copy the car holds
  function shareOpen() {
    if (!S.doc || S.saving) return;
    const go = () => share(S.mine.find(x => x.id === S.docId) || { id: S.docId, doc: S.doc });
    if (S.docId) { go(); return; }
    confirmDlg({ title: tr('share_save_title'), body: tr('share_save_body'), confirmLabel: tr('save'), cancelLabel: tr('cancel') })
      .then(ok => (ok ? save() : false)).then(ok => { if (ok && S.docId) go(); });
  }

  /** Opens a widget as a new, unsaved copy in Design. */
  function openCopy(doc, name) {
    return guardDiscard().then(ok => {
      if (!ok) return false;
      const copy = ODW.cleanDoc(doc);
      copy.name = tr('copy_name', { name: String(name || copy.name || tr('untitled')) }).slice(0, ODW.MAX_NAME);
      openDoc(copy, { id: null, saved: null });
      setMode('design');
      if (S.narrow) showPanel('layers');
      toast(tr('copy_opened'), 'info');
      return true;
    });
  }

  function share(w) {
    ensureSaved(w.id).then(ok => {
      if (ok && window.WidgetCommunity && WidgetCommunity.openPublish) WidgetCommunity.openPublish(S.mine.find(x => x.id === w.id) || w);
    });
  }

  function select(i) {
    S.sel = i;
    renderLayers();
    renderInspector();
    dirty = true;
    kick();
    if (S.narrow && i >= 0) showPanel('edit');
  }

  // ── right: inspector ──────────────────────────────────────────────────────
  function group(host, title) { const gr = el('div', 'wb-group', host); el('h4', 'wb-section-title', gr, esc(title)); return gr; }
  // one property as a stacked settings row: its name and hint, then whatever the caller adds
  function prop(host, label, hint) {
    const p = el('div', 'setting-stack wb-prop', host);
    const info = el('div', 'setting-info', p);
    el('div', 'setting-name', info, esc(label));
    if (hint) el('div', 'setting-desc', info, esc(hint));
    return p;
  }
  function seg(host, options, current, onPick, cls) {
    const s = el('div', 'filter-tabs wb-seg' + (cls ? ' ' + cls : ''), host);
    s.setAttribute('role', 'group');
    options.forEach(o => {
      const on = String(current) === String(o[0]);
      const b = btn('filter-tab' + (on ? ' active' : ''), s, esc(o[1]), () => onPick(o[0]));
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    return s;
  }
  function slider(host, label, min, max, step, value, onValue, geoKey) {
    const row = el('div', 'slider-control', host);
    const range = el('input', 'slider', row); range.type = 'range'; range.min = min; range.max = max; range.step = step;
    const box = el('input', 'text-input wb-num', row); box.type = 'number'; box.step = step; box.setAttribute('inputmode', 'decimal');
    range.setAttribute('aria-label', label); box.setAttribute('aria-label', label);
    if (geoKey) { range.setAttribute('data-geo', geoKey); box.setAttribute('data-geo', geoKey); }
    range.value = value.range; box.value = value.box;
    range.addEventListener('input', () => { box.value = range.value; onValue(range.value); });
    box.addEventListener('input', () => { range.value = box.value; onValue(box.value); });
    return row;
  }
  function toggle(host, checked, label) {
    const sw = el('label', 'toggle-switch', host);
    const cb = el('input', '', sw); cb.type = 'checkbox'; cb.checked = !!checked;
    if (label) cb.setAttribute('aria-label', label);
    el('span', 'toggle-slider', sw);
    return cb;
  }
  // Checks name a property the way its editor labels it
  const LABEL_KEY = Object.assign(Object.create(null), { motion: 'h_motion', parts: 'parts', drive: 'motors', region: 'sign_style', side: 'traffic_side', weight: 'style.weight', align: 'style.align', shape: 'style.shape', d: 'style.path' });
  const propLabel = key => (LABEL_KEY[key] ? tr(LABEL_KEY[key]) : Object.prototype.hasOwnProperty.call(GEO, key) ? trOr('geo.' + key, key) : trOr('prop.' + key, key));
  const propHint = key => (HINTED.has(key) ? trOr('prop.' + key + '_hint', '') : '');

  function renderInspector() {
    const host = $('#wbPaneProps');
    if (!host || !S.doc) return;
    LIVE = [];
    host.innerHTML = '';
    const bar = el('div', 'wb-modebar', host);
    seg(bar, [[true, tr('mode_simple')], [false, tr('mode_advanced')]], S.simple, v => { S.simple = v; savePrefs(); renderInspector(); });
    el('span', 'wb-modehint', bar, esc(tr(S.simple ? 'mode_simple_hint' : 'mode_advanced_hint')));
    if (S.sel < 0 || !S.doc.layers[S.sel]) { widgetSettings(host); return; }
    const src = S.doc.layers[S.sel], def = TYPES[src.type];
    if (!def) { el('div', 'wb-note', host, esc(tr('layer_unknown', { type: String(src.type) }))); return; }
    if (S.simple) { simpleInspector(host, src, def); return; }
    layerHead(host, src, def);

    const keys = Object.keys(def.props);
    const bindable = keys.filter(k => ['num', 'col', 'txt', 'bool'].indexOf(def.props[k]) >= 0);
    const geo = keys.filter(k => def.props[k] === 'geo');
    const style = keys.filter(k => ['weight', 'align', 'shape', 'path'].indexOf(def.props[k]) >= 0 || String(def.props[k]).indexOf('enum:') === 0);

    if (def.object) {
      const g0 = group(host, tr('h_what_it_is'));
      el('div', 'wb-note', g0, esc(tr('object_intro_' + src.type)));
      if (src.type === 'car') partsEditor(g0, src);
      objectInputs(host, src, true);
      const gw = group(host, tr('h_when_to_show'));
      propEditor(gw, src, 'visible', 'bool');
      propEditor(gw, src, 'opacity', 'num');
    } else {
      const g1 = group(host, tr('h_what_it_shows'));
      if (src.type === 'pulse') el('div', 'wb-note', g1, esc(tr('pulse_note_adv')));
      bindable.forEach(k => propEditor(g1, src, k, def.props[k]));
      propEditor(g1, src, 'visible', 'bool');
      propEditor(g1, src, 'opacity', 'num');
    }
    if (geo.length) {
      const g2 = group(host, tr('h_layout'));
      const grid = el('div', 'wb-grid2', g2);
      geo.forEach(k => geoEditor(grid, src, k));
      if (src.type === 'text') el('div', 'wb-note', g2, esc(tr('text_y_note', { w: W.w, h: W.h })));
    }
    if (style.length) {
      const g3 = group(host, tr('h_style'));
      style.forEach(k => styleEditor(g3, src, k, def.props[k]));
    }
    if (def.props.motion) {
      const g4 = group(host, tr('h_motion'));
      seg(el('div', 'setting-stack wb-prop', g4), [['none', tr('motion_none')], ['number', tr('motion_number')], ['liquid', tr('motion_liquid')]], src.motion || 'none', v => { src.motion = v; changed(); renderInspector(); });
      el('div', 'wb-note', g4, esc(tr('motion_note')));
    }
  }

  function layerHead(host, src, def) {
    const head = el('div', 'wb-insp-head', host);
    el('span', 'wb-ic', head, typeIcon(src.type));
    const input = el('input', 'text-input', head);
    input.value = src.name || typeLabel(src.type);
    input.maxLength = 60;
    input.setAttribute('dir', 'auto');
    input.setAttribute('aria-label', tr('layer_name'));
    input.addEventListener('input', () => { src.name = input.value; lightChange(); });
    el('small', '', head, esc(typeLabel(src.type)));
  }

  function simpleInspector(host, src, def) {
    layerHead(host, src, def);
    const g1 = group(host, tr('h_what_it_shows'));
    let colourSig = 'soc';
    if (src.type === 'ring' || src.type === 'bar') colourSig = simpleLevel(g1, src, 'value');
    else if (src.type === 'capsule') colourSig = simpleLevel(g1, src, 'level');
    else if (src.type === 'text') colourSig = simpleText(g1, src);
    else if (src.type === 'spark') colourSig = simpleTrace(g1, src);
    else if (src.type === 'pulse') el('div', 'wb-note', g1, esc(tr('pulse_note')));
    else if (src.type === 'car') simpleCar(g1, src);
    else if (src.type === 'road') simpleRoad(g1, src);
    else el('div', 'wb-note', g1, esc(tr('shape_note')));
    if (!def.object) simpleColour(group(host, tr('h_colour')), src, src.type === 'shape' ? 'fill' : 'color', colourSig || 'soc');
    simpleShow(group(host, tr('h_when_to_show')), src);
    if (def.props.motion && !def.object) {
      const gm = group(host, tr('h_movement'));
      seg(gm, [['none', tr('move_jump')], ['number', tr('move_glide')], ['liquid', tr('move_settle')]], src.motion || 'none', v => { src.motion = v; changed(); renderInspector(); });
      el('div', 'wb-note', gm, esc(tr('move_note')));
    }
    const g5 = group(host, tr('h_place'));
    el('div', 'wb-note', g5, esc(tr('drag_note')));
    if (src.type === 'text') {
      seg(g5, [[15, tr('text_small')], [24, tr('text_medium')], [34, tr('text_large')], [46, tr('text_huge')]], nz(src.size) || 24, v => { src.size = v; changed(); renderInspector(); });
    } else {
      const grid = el('div', 'wb-grid2', g5);
      Object.keys(def.props).filter(k => def.props[k] === 'geo' && ['scale', 'r', 'w', 'h', 'width'].indexOf(k) >= 0).forEach(k => geoEditor(grid, src, k));
    }
    el('div', 'wb-note', host, trHtml('need_more', { advanced: '<b>' + esc(tr('mode_advanced')) + '</b>' }));
  }

  function customNote(host, label) {
    const n = el('div', 'wb-note', host, esc(tr('custom_formula', { what: label })) + ' ');
    btn('wb-link', n, esc(tr('edit_in_advanced')), () => { S.simple = false; savePrefs(); renderInspector(); });
  }

  function signalPicker(cur, filter) {
    const s = document.createElement('select');
    s.className = 'wb-select wb-pick';
    s.innerHTML = groupedSignals(filter).map(gr => '<optgroup label="' + esc(groupLabel(gr[0])) + '">' + gr[1].map(x =>
      '<option value="' + esc(x.key) + '"' + (x.key === cur ? ' selected' : '') + '>' + esc(sigLabel(x.key)) + (x.unit ? ' (' + esc(x.unit) + ')' : '') + '</option>').join('') + '</optgroup>').join('');
    return s;
  }

  function simpleLevel(host, src, key) {
    const cur = readFraction(src[key]);
    if ((!cur && isFormula(src[key])) || (cur && !numeric(cur.sig))) { customNote(host, tr('what_it_shows')); return null; }
    const sig = cur ? cur.sig : 'soc';
    const p = prop(host, tr('shows'));
    const sel = signalPicker(sig, numeric); p.appendChild(sel);
    sel.setAttribute('aria-label', tr('shows'));
    const f = prop(host, tr('full_at'), tr('full_at_hint'));
    const row = el('div', 'wb-row', f);
    const box = el('input', 'text-input wb-num', row); box.type = 'number'; box.setAttribute('inputmode', 'decimal');
    box.value = cur ? cur.full : (SIG[sig] ? SIG[sig].max : 100);
    box.setAttribute('aria-label', tr('full_at'));
    const unit = el('span', 'wb-hint', row, esc(sigUnit(sig)));
    const res = el('div', 'wb-res', host);
    const show = () => {
      const v = num(effective()[sel.value]);
      setHtml(res, v == null ? esc(tr('now_no_reading_empty')) : trHtml('now_full', { pct: '<b>' + Math.round(clamp01(v / (+box.value || 1)) * 100) + '%</b>' }));
    };
    LIVE.push(show);
    const write = () => { src[key] = '=' + sel.value + ' / ' + (+box.value || 1); changed(); show(); };
    sel.addEventListener('change', () => { box.value = SIG[sel.value] ? SIG[sel.value].max : 100; setText(unit, sigUnit(sel.value)); write(); renderInspector(); });
    box.addEventListener('input', write);
    show();
    return sel.value;
  }

  function simpleText(host, src) {
    const reading = readReading(src.text);
    const words = typeof src.text === 'string' && !isFormula(src.text) && src.text.indexOf('{') < 0;
    if ((!reading && !words) || (reading && !numeric(reading.sig))) { customNote(host, tr('this_text')); return null; }
    seg(host, [['words', tr('text_words')], ['reading', tr('text_reading')]], reading ? 'reading' : 'words', v => {
      if (v === 'words' && reading) { src.text = sigLabel(reading.sig); delete src.unit; }
      if (v === 'reading' && !reading) setReading(src, 'soc', 0);
      changed(); renderInspector();
    });
    if (!reading) {
      const p = el('div', 'setting-stack wb-prop', host);
      const input = el('input', 'text-input wb-txt', p); input.value = src.text; input.maxLength = 120; input.setAttribute('dir', 'auto');
      input.setAttribute('aria-label', tr('text_words'));
      input.addEventListener('input', () => { src.text = input.value; changed(); });
      return null;
    }
    const p = prop(host, tr('reading'));
    const sel = signalPicker(reading.sig, numeric); p.appendChild(sel);
    sel.setAttribute('aria-label', tr('reading'));
    const uf = unitFor(reading.sig);
    if (uf && uf.fn === 'tyre') el('div', 'wb-note', host, esc(tr('tyre_unit_note')));
    else {
      const fmt = prop(host, tr('shown_as'));
      const now = num(effective()[reading.sig]);
      const sample = now == null ? 72.4 : now;
      seg(fmt, [[0, sampleIn(reading.sig, sample, 0)], [1, sampleIn(reading.sig, sample, 1)]], reading.dec, d => { setReading(src, reading.sig, d); changed(); renderInspector(); });
    }
    el('div', 'wb-note', host, esc(tr('reading_note')));
    sel.addEventListener('change', () => {
      const u2 = unitFor(sel.value);
      setReading(src, sel.value, u2 ? u2.dec : reading.dec);
      changed(); renderInspector();
    });
    return reading.sig;
  }

  function simpleTrace(host, src) {
    const sig = readSignal(src.value);
    if ((!sig && isFormula(src.value)) || (sig && !numeric(sig))) { customNote(host, tr('this_trace')); return null; }
    const p = prop(host, tr('trace_reading'));
    const sel = signalPicker(sig || 'speed', numeric); p.appendChild(sel);
    sel.setAttribute('aria-label', tr('trace_reading'));
    sel.addEventListener('change', () => { src.value = '=' + sel.value; changed(); });
    const w = prop(host, tr('trace_window'));
    seg(w, [[60, tr('min_1')], [120, tr('min_2')], [300, tr('min_5')]], nz(src.window) || 60, n => { src.window = n; changed(); renderInspector(); });
    return sel.value;
  }

  function simpleColour(host, src, key, sig) {
    const raw = src[key];
    const steps = readSteps(raw);
    if (isFormula(raw) && !steps) { customNote(host, tr('the_colour')); return; }
    const mode = steps ? 'steps' : 'fixed';
    seg(host, [['fixed', tr('colour_one')], ['steps', tr('colour_steps')]], mode, v => {
      if (v === 'fixed' && steps) src[key] = 'glowAccent';
      if (v === 'steps' && !steps) {
        const s = SIG[sig];
        src[key] = sig === 'soc' ? BATTERY_RAMP
          : '=' + sig + ' <= ' + (s ? Math.round(nz(s.min) + (nz(s.max) - nz(s.min)) * 0.1) : 10) + ' ? glowDanger : ' + sig + ' <= ' + (s ? Math.round(nz(s.min) + (nz(s.max) - nz(s.min)) * 0.3) : 30) + ' ? glowWarn : glowAccent';
      }
      changed(); renderInspector();
    });
    if (mode === 'fixed') {
      const row = el('div', 'wb-colors wb-mt', host);
      PALETTE.forEach(name => {
        const b = btn(raw === name ? 'on' : '', row, '', () => { src[key] = name; changed(); renderInspector(); });
        b.style.background = color.css(TOKENS[S.theme][name]); b.title = name; b.setAttribute('aria-label', name);
        b.setAttribute('aria-pressed', raw === name ? 'true' : 'false');
      });
      el('div', 'wb-note', host, esc(tr('theme_colours_note')));
      return;
    }
    const u = sigUnit(steps.sig);
    const p = el('div', 'setting-stack wb-prop', host);
    const row = el('div', 'wb-row wb-wrap', p);
    el('span', 'wb-hint wb-red', row, esc(tr('steps_red')));
    const red = el('input', 'text-input wb-num', row); red.type = 'number'; red.value = steps.red; red.setAttribute('aria-label', tr('steps_red'));
    el('span', 'wb-hint', row, esc(u));
    el('span', 'wb-hint wb-amber', row, esc(tr('steps_amber')));
    const amber = el('input', 'text-input wb-num', row); amber.type = 'number'; amber.value = steps.amber; amber.setAttribute('aria-label', tr('steps_amber'));
    el('span', 'wb-hint', row, esc(u));
    const write = () => { src[key] = '=' + steps.sig + ' <= ' + (+red.value || 0) + ' ? glowDanger : ' + steps.sig + ' <= ' + (+amber.value || 0) + ' ? glowWarn : glowAccent'; changed(); };
    red.addEventListener('input', write); amber.addEventListener('input', write);
    el('div', 'wb-note', host, esc(tr(steps.standard ? 'steps_standard_note' : 'steps_note')));
  }

  function simpleShow(host, src) {
    const cur = src.visible == null ? null : src.visible;
    const known = CONDITIONS.find(c => c[0] === cur);
    const sel = el('select', 'wb-select wb-pick', el('div', 'setting-stack wb-prop', host));
    sel.setAttribute('aria-label', tr('h_when_to_show'));
    sel.innerHTML = CONDITIONS.map((c, i) => '<option value="' + i + '"' + (known && known[0] === c[0] ? ' selected' : '') + '>' + esc(tr(c[1])) + '</option>').join('') +
      (known ? '' : '<option selected disabled>' + esc(tr('cond_custom')) + '</option>');
    sel.addEventListener('change', () => { const v = CONDITIONS[+sel.value][0]; if (v == null) delete src.visible; else src.visible = v; changed(); });
  }

  function simpleCar(host, src) {
    partsEditor(host, src);
    const m = prop(host, tr('motors'));
    seg(m, [['awd', tr('enum.awd')], ['fwd', tr('enum.fwd')], ['rwd', tr('enum.rwd')]], src.drive || 'awd', v => { src.drive = v; changed(); renderInspector(); });
    objectInputs(host, src, false);
  }

  function partsEditor(host, src) {
    const cur = ODW.PARTS.of(src);
    const p = prop(host, tr('parts'), tr('parts_hint'));
    const chips = el('div', 'checkbox-row wb-chips wb-parts', p);
    ODW.PARTS.names.forEach(name => {
      const on = cur.has(name);
      const b = btn('checkbox-item wb-chip' + (on ? ' active' : ''), chips, esc(tr('part.' + name)), () => {
        const next = new Set(cur);
        if (next.has(name)) next.delete(name); else next.add(name);
        if (!next.size) { toast(tr('parts_keep_one'), 'warning'); return; }
        const wasPack = cur.size === 1 && cur.has('pack'), isPack = next.size === 1 && next.has('pack');
        src.parts = ODW.PARTS.names.filter(n => next.has(n)).join(',');
        if (isPack && !wasPack) src.scale = 2.2;
        if (wasPack && !isPack) src.scale = 1;
        changed(); renderInspector();
      });
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    const pre = el('div', 'wb-row wb-wrap', p);
    PART_PRESETS.forEach(ps => {
      btn('btn btn-secondary', pre, esc(tr(ps[0])), () => { src.parts = ps[1]; src.scale = ps[2]; changed(); renderInspector(); });
    });
    el('div', 'wb-note', p, esc(tr('parts_note')));
  }

  function simpleRoad(host, src) {
    const p = prop(host, tr('sign_style'));
    seg(p, [['eu', tr('enum.eu')], ['us', tr('enum.us')], ['cn', tr('enum.cn')]], src.region || 'eu', v => { src.region = v; changed(); renderInspector(); });
    const s = prop(host, tr('drives_on'));
    seg(s, [['left', tr('enum.left')], ['right', tr('enum.right')]], src.side || 'left', v => { src.side = v; changed(); renderInspector(); });
    objectInputs(host, src, false);
  }

  const formulaOpen = new Set();
  function feedState(src, f) {
    const vals = f.keys.map(k => src[k]);
    if (vals.every(v => v === undefined)) return 'off';
    if (f.keys.every((k, i) => isAuto(k, vals[i], autoFor(src.type, k)))) return 'auto';
    if (f.keys.length === 1) {
      const sig = readSignal(vals[0]);
      if (f.pick && sig && numeric(sig)) return 'sig:' + sig;
      if (f.fixed && f.fixed.indexOf(vals[0]) >= 0) return 'fixed:' + vals[0];
    }
    return 'custom';
  }

  function objectInputs(host, src, advanced) {
    const gr = group(host, tr(src.type === 'road' ? 'connected_road' : 'connected_car'));
    (OBJECT_FEEDS[src.type] || []).forEach(f => {
      const state = feedState(src, f);
      const row = el('div', 'setting-stack wb-prop wb-feed', gr);
      const nm = el('div', 'setting-name', el('div', 'setting-info', row));
      el('span', '', nm, esc(tr('feed_' + f.id)));
      const nowEl = el('span', 'wb-hint wb-now', nm, '');
      const showNow = () => {
        const L = W.layers[S.sel];
        if (!L || L.src !== src) return;
        const v0 = L.vals[f.keys[0]];
        setText(nowEl, tr('now_value', { v: f.show(num(v0) == null ? v0 : num(v0), L) }));
      };
      LIVE.push(showNow);
      showNow();
      const sel = el('select', 'wb-select wb-pick', row);
      sel.setAttribute('aria-label', tr('feed_' + f.id));
      let html = '<option value="auto">' + esc(tr('feed_auto', { what: tr('feed_' + f.id + '_auto') })) + '</option>';
      if (f.pick) {
        html += '<optgroup label="' + esc(tr('feed_other')) + '">' + S.catalog.filter(x => numeric(x.key)).map(x =>
          '<option value="sig:' + esc(x.key) + '">' + esc(sigLabel(x.key)) + (x.unit ? ' (' + esc(x.unit) + ')' : '') + '</option>').join('') + '</optgroup>';
      }
      if (f.fixed) html += f.fixed.map(v => '<option value="fixed:' + v + '">' + esc(tr('always_' + v)) + '</option>').join('');
      html += '<option value="off">' + esc(tr('feed_off')) + '</option>';
      if (state === 'custom') html += '<option value="custom">' + esc(tr('feed_custom')) + '</option>';
      sel.innerHTML = html;
      sel.value = state;
      sel.addEventListener('change', () => {
        const v = sel.value;
        if (v === 'auto') f.keys.forEach(k => { src[k] = autoFor(src.type, k); });
        else if (v === 'off') f.keys.forEach(k => { delete src[k]; });
        else if (v.indexOf('sig:') === 0) src[f.keys[0]] = '=' + v.slice(4);
        else if (v.indexOf('fixed:') === 0) src[f.keys[0]] = v.slice(6);
        changed(); renderInspector();
      });
      if (state === 'off') el('div', 'wb-note', row, esc(tr('feed_off_note')));
      if (state === 'custom') {
        const L = W.layers[S.sel];
        const problem = f.keys.map(k => formulaProblem(src, L, k)).find(Boolean);
        const n = el('div', 'wb-note', row, problem ? '<span class="wb-warnt">' + trHtml('feed_broken', { why: problem }) + '</span> ' : esc(tr('feed_custom_note')) + ' ');
        btn('wb-link', n, esc(tr('feed_reset')), () => { f.keys.forEach(k => { src[k] = autoFor(src.type, k); }); changed(); renderInspector(); });
      }
      if (advanced) {
        const id = S.sel + ':' + f.id;
        btn('wb-link wb-formula-link', el('div', 'wb-note', row), esc(tr(formulaOpen.has(id) ? 'formula_hide' : 'formula_write')), () => {
          if (formulaOpen.has(id)) formulaOpen.delete(id); else formulaOpen.add(id);
          renderInspector();
        });
        if (formulaOpen.has(id)) f.keys.forEach(k => propEditor(row, src, k, TYPES[src.type].props[k]));
      }
    });
  }

  // plain-language reason a formula can't work, as HTML, or null
  function formulaProblem(src, L, key) {
    if (L && L.errors[key]) return friendlyError(L.errors[key]);
    const raw = src[key];
    if (!isFormula(raw)) return null;
    const ids = new Set();
    try { ODW.collect(ODW.parse(raw.slice(1)), ids); } catch (e) { return friendlyError(e); }
    const bad = Array.from(ids).filter(id => !SIG[id] && TOKENS.night[id] === undefined && !ODW.BUILTINS.has(id));
    return bad.length ? friendlyUnknown(bad[0]) : null;
  }

  function widgetSettings(host) {
    const doc = S.doc;
    const head = el('div', 'wb-insp-head', host);
    el('span', 'wb-ic wb-ic-widget', head, icon('widget'));
    const input = el('input', 'text-input', head);
    input.value = doc.name || ''; input.maxLength = ODW.MAX_NAME; input.setAttribute('dir', 'auto');
    input.placeholder = tr('untitled'); input.setAttribute('aria-label', tr('widget_name'));
    input.addEventListener('input', () => { doc.name = input.value; lightChange(); if (shell) setText($('.wb-badge', shell), tr('badge', { name: doc.name || tr('untitled'), size: sizeLabel(doc.size), w: W.w, h: W.h })); });
    el('small', '', head, esc(tr('widget_word')));
    const ga = group(host, tr('h_about'));
    const about = el('textarea', 'text-input wb-txt wb-about', ga);
    about.value = doc.about || ''; about.maxLength = ODW.MAX_ABOUT; about.rows = 2; about.setAttribute('dir', 'auto');
    about.placeholder = tr('about_placeholder'); about.setAttribute('aria-label', tr('h_about'));
    about.addEventListener('input', () => { if (about.value) doc.about = about.value; else delete doc.about; lightChange(); });
    const g1 = group(host, tr('h_size'));
    seg(g1, Object.keys(SIZES).map(v => [v, sizeLabel(v) + ' ' + SIZES[v][0] + '×' + SIZES[v][1]]), doc.size, v => { doc.size = v; shell = null; changed(); renderInspector(); });
    el('div', 'wb-note', g1, esc(tr('size_note')));
    const g3 = group(host, tr('h_reads'));
    const sigs = usedSignals();
    el('div', 'wb-note', g3, sigs.length ? sigs.map(k => code(k) + ' ' + (SIG[k] ? esc(sigLabel(k)) : '<span class="wb-warnt">' + esc(tr('unknown_word')) + '</span>')).join('<br>') : esc(tr('reads_none')));
  }

  function propEditor(host, src, key, kind) {
    const label = propLabel(key), hint = propHint(key);
    const raw = src[key];
    const live = isFormula(raw);
    const p = el('div', 'setting-stack wb-prop', host);
    p.setAttribute('data-key', key);
    const info = el('div', 'setting-info', p);
    const nm = el('div', 'setting-name', info);
    el('span', '', nm, esc(label));
    const mode = el('div', 'filter-tabs wb-mode', nm);
    if (hint) el('div', 'setting-desc', info, kind === 'txt' && key === 'text' ? trHtml('prop.text_hint', { example: code('{soc}') }) : esc(hint));
    mode.setAttribute('role', 'group');
    const bFixed = btn('filter-tab' + (live ? '' : ' active'), mode, esc(tr(kind === 'txt' ? 'mode_words' : 'mode_fixed')), () => {
      if (!live) return;
      const v = freeze(key, kind);
      if (v === undefined) delete src[key]; else src[key] = v;
      changed(); renderInspector();
    });
    const bLive = btn('filter-tab' + (live ? ' active' : ''), mode, esc(tr('mode_live')), () => { if (!live) { src[key] = '=' + suggest(src, key, kind); changed(); renderInspector(); } });
    bFixed.setAttribute('aria-pressed', live ? 'false' : 'true');
    bLive.setAttribute('aria-pressed', live ? 'true' : 'false');

    if (live) {
      const ta = el('textarea', 'text-input wb-expr', p);
      ta.spellcheck = false;
      ta.setAttribute('dir', 'ltr');
      ta.setAttribute('autocapitalize', 'off');
      ta.setAttribute('autocomplete', 'off');
      ta.setAttribute('aria-label', label);
      ta.value = raw.slice(1);
      ta.rows = Math.min(4, Math.max(1, Math.ceil(ta.value.length / 44)));
      ta.maxLength = ODW.MAX_EXPR;
      const res = el('div', 'wb-res', p);
      // its own row: the result line above refreshes every reading, and rebuilding around an open select closes it
      const ins = signalSelect();
      el('div', 'wb-ins-row', p).appendChild(ins);
      const onInput = () => { src[key] = '=' + ta.value; changed(); showResult(res, src, key, kind); };
      ta.addEventListener('input', onInput);
      // a lone reading is swapped for the new one; otherwise it goes in at the cursor, never glued to a neighbour
      ins.addEventListener('change', () => {
        const v = ins.value, text = ta.value;
        if (!v) return;
        const at = ta.selectionStart == null ? text.length : ta.selectionStart;
        const end = ta.selectionEnd == null ? at : ta.selectionEnd;
        // a function wraps a lone reading or the selection; otherwise its caret lands inside the brackets
        const fn = /\(\)$/.test(v) ? v.slice(0, -2) : null;
        const lone = /^\s*[a-z_][a-z0-9_]*\s*$/i.test(text);
        let caret;
        if (fn && text.trim() && (lone || at !== end)) {
          const a = lone ? 0 : at, b = lone ? text.length : end;
          const before = text.slice(0, a), inner = text.slice(a, b).trim(), after = text.slice(b);
          ta.value = before + fn + '(' + inner + ')' + after;
          caret = (before + fn + '(' + inner + ')').length;
        } else if (!text.trim() || (!fn && lone) || (at === 0 && end === text.length)) { ta.value = v; caret = fn ? v.length - 1 : v.length; }
        else {
          const before = text.slice(0, at), after = text.slice(end);
          const padL = /[a-z0-9_)'"]$/i.test(before) ? ' ' : '', padR = /^[a-z0-9_('"]/i.test(after) ? ' ' : '';
          ta.value = before + padL + v + padR + after;
          caret = (before + padL + v).length - (fn ? 1 : 0);
        }
        ins.value = '';
        onInput();
        try { ta.focus(); ta.setSelectionRange(caret, caret); } catch (e) { /* not focusable */ }
      });
      res.setAttribute('data-key', key);
      res._kind = kind;
      showResult(res, src, key, kind);
      return;
    }
    if (kind === 'num') {
      const fx = FIXED[key] || [0, 1, 0.01];
      const v = raw == null ? '' : raw;
      slider(p, label, fx[0], fx[1], fx[2], { range: v === '' ? (key === 'opacity' ? 1 : 0) : v, box: v }, x => { if (x === '' || x == null) delete src[key]; else src[key] = +x; changed(); });
    } else if (kind === 'col') {
      const row = el('div', 'wb-colors', p);
      const allowNone = ['track', 'fill', 'stroke'].indexOf(key) >= 0;
      const cur = raw == null ? null : raw;
      if (allowNone) {
        const on = cur === 'none' || (cur == null && key !== 'track');
        const b = btn('wb-none' + (on ? ' on' : ''), row, '', () => { src[key] = 'none'; changed(); renderInspector(); });
        b.title = tr('colour_none'); b.setAttribute('aria-label', tr('colour_none')); b.setAttribute('aria-pressed', on ? 'true' : 'false');
      }
      PALETTE.forEach(name => {
        const b = btn(cur === name ? 'on' : '', row, '', () => { src[key] = name; changed(); renderInspector(); });
        b.style.background = color.css(TOKENS[S.theme][name]); b.title = name; b.setAttribute('aria-label', name);
        b.setAttribute('aria-pressed', cur === name ? 'true' : 'false');
      });
      el('div', 'wb-note', p, cur && PALETTE.indexOf(cur) < 0 && cur !== 'none' ? trHtml('colour_custom', { c: code(cur) }) : esc(tr('theme_colours_note')));
    } else if (kind === 'txt') {
      const input = el('input', 'text-input wb-txt', p); input.value = raw == null ? '' : raw; input.setAttribute('dir', 'auto'); input.maxLength = 200;
      input.setAttribute('aria-label', label);
      const res = el('div', 'wb-res', p);
      input.addEventListener('input', () => { if (input.value === '' && key === 'unit') delete src[key]; else src[key] = input.value; changed(); showResult(res, src, key, kind); });
      res.setAttribute('data-key', key);
      res._kind = kind;
      showResult(res, src, key, kind);
    } else if (kind === 'bool') {
      const lab = el('div', 'wb-row wb-bool', p);
      const cb = toggle(lab, raw === undefined ? true : !!raw, label);
      el('span', '', lab, esc(tr(raw === undefined ? 'always_shown' : 'fixed_word')));
      cb.addEventListener('change', () => { if (cb.checked) delete src[key]; else src[key] = false; changed(); renderInspector(); });
    }
  }

  function suggest(src, key, kind) {
    const raw = src[key];
    if (kind === 'num') return key === 'rotate' ? 'lerp(-120, 120, clamp(speed / 160, 0, 1))' : key === 'opacity' ? 'parked ? 1 : 0.6' : (raw != null ? String(raw) : 'soc / 100');
    if (kind === 'col') return 'ramp(soc, 0, glowDanger, 18, glowWarn, 34, glowAccent)';
    if (kind === 'bool') return 'is_charging';
    return raw && String(raw).indexOf('{') < 0 ? "'" + String(raw).replace(/'/g, '') + "'" : "fmt(soc) + '%'";
  }

  function freeze(key, kind) {
    const L = W.layers[S.sel];
    const v = L ? L.vals[key] : null;
    if (kind === 'num') { const n = num(v); return n == null ? undefined : +n.toFixed(4); }
    if (kind === 'bool') return v ? undefined : false;
    if (kind === 'col') return v && typeof v === 'object' ? color.hex(v.col) : (typeof v === 'string' ? v : 'text');
    return L ? W.str(L, key) : '';
  }

  function signalSelect() {
    const s = document.createElement('select');
    s.className = 'wb-select wb-ins';
    s.setAttribute('aria-label', tr('insert_reading'));
    s.innerHTML = '<option value="">' + esc(tr('insert_reading')) + '</option>' + groupedSignals().map(gr =>
      '<optgroup label="' + esc(groupLabel(gr[0])) + '">' + gr[1].map(x => '<option value="' + esc(x.key) + '">' + esc(sigLabel(x.key)) + (x.unit ? ' (' + esc(x.unit) + ')' : '') + ' · ' + esc(x.key) + '</option>').join('') + '</optgroup>').join('') +
      '<optgroup label="' + esc(tr('ins_units')) + '">' + ODW.UNIT_WORDS.map(w => '<option value="' + w + '">' + esc(w + ' · ' + tr('unitw_' + w)) + '</option>').join('') + '</optgroup>' +
      '<optgroup label="' + esc(tr('ins_functions')) + '">' + INSERT_FNS.map(f => '<option value="' + f + '()">' + esc(f + '(…) · ' + tr('fn_' + f)) + '</option>').join('') + '</optgroup>';
    return s;
  }

  function showResult(res, src, key, kind) {
    const L = W.layers[S.sel];
    const err = L && L.errors[key];
    const box = res.previousElementSibling;
    const markBad = bad => { if (box && (box.tagName === 'TEXTAREA' || box.tagName === 'INPUT')) box.classList.toggle('bad', bad); };
    if (err) { res.className = 'wb-res err'; setHtml(res, '✕ ' + friendlyError(err)); markBad(true); return; }
    res.className = 'wb-res';
    markBad(false);
    const v = L ? (kind === 'txt' ? W.str(L, key) : L.vals[key]) : null;
    let out;
    if (kind === 'col') {
      out = v && typeof v === 'object' ? '<span class="wb-sw" style="background:' + color.css(v.col) + '"></span> <b dir="ltr">' + color.hex(v.col) + '</b>'
        : typeof v === 'string' ? '<b dir="ltr">' + esc(v) + '</b>' : '<b>' + esc(tr('res_col_none')) + '</b>';
    } else if (kind === 'txt') out = '<b dir="auto">“' + esc(v) + '”</b>';
    else if (kind === 'bool') out = '<b>' + esc(tr(v == null ? 'res_bool_none' : ODW.truthy(v) ? 'res_shown' : 'res_hidden')) + '</b>';
    else out = v == null ? '<b>' + esc(tr('res_num_none')) + '</b>' : '<b dir="ltr">' + (typeof v === 'number' ? +v.toFixed(3) : esc(String(v))) + '</b>';
    const idents = new Set();
    try {
      const raw = src[key];
      if (isFormula(raw)) ODW.collect(ODW.parse(raw.slice(1)), idents);
      else if (typeof raw === 'string' && raw.indexOf('{') >= 0) ODW.template(raw).forEach(x => { if (typeof x !== 'string') ODW.collect(x.node, idents); });
    } catch (e) { /* shown above */ }
    const reads = Array.from(idents).filter(id => SIG[id] || (TOKENS.night[id] === undefined && !ODW.BUILTINS.has(id)));
    const bad = reads.filter(id => !SIG[id]);
    const known = reads.filter(id => SIG[id]);
    setHtml(res, trHtml('right_now', { value: out }) + (known.length ? ' · ' + esc(tr('from_signals', { list: known.map(id => sigLabel(id)).join(', ') })) : '') +
      (bad.length ? ' <span class="wb-warnt">· ' + friendlyUnknown(bad[0]) + '</span>' : ''));
  }

  // a name the car doesn't publish: two readings run together, a typo, or simply unknown (HTML)
  function friendlyUnknown(id) {
    const keys = Object.keys(SIG).sort((a, b) => b.length - a.length);
    const parts = [];
    let rest = id;
    while (rest) { const k = keys.find(x => rest.indexOf(x) === 0); if (!k) break; parts.push(k); rest = rest.slice(k.length); }
    if (!rest && parts.length > 1) return trHtml('unknown_joined', { a: esc(sigLabel(parts[0])), b: esc(sigLabel(parts[1])), example: code(parts[0] + ' / 100') });
    let best = null, bestD = 4;
    keys.forEach(k => { const d = editDistance(id, k); if (d < bestD) { bestD = d; best = k; } });
    return best ? trHtml('unknown_typo', { id: esc(id), label: esc(sigLabel(best)), key: code(best) }) : trHtml('unknown_plain', { id: esc(id) });
  }
  function editDistance(a, b) {
    if (Math.abs(a.length - b.length) > 3) return 99;
    const d = [];
    for (let i = 0; i <= a.length; i++) { d.push([i]); for (let j = 1; j <= b.length; j++) d[i].push(i === 0 ? j : 0); }
    for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    return d[a.length][b.length];
  }
  // parser errors in plain language (HTML)
  function friendlyError(e) {
    if (!e) return '';
    const c = e.code, a = e.arg;
    if (c === 'unexpected' && /^[a-z0-9_.]+$/i.test(String(a))) return trHtml('err_missing_op', { tok: code(a) });
    if (c === 'unexpected') return trHtml('err_unexpected', { tok: code(a) });
    if (c === 'end') return esc(tr('err_end'));
    if (c === 'expect' && a === ')') return esc(tr('err_bracket'));
    if (c === 'expect' && a === ':') return esc(tr('err_colon'));
    if (c === 'expect') return trHtml('err_expect', { tok: code(a) });
    if (c === 'fn') return trHtml('err_fn', { name: code(a) });
    if (c === 'arity' && Array.isArray(a)) return a[1] === a[2] ? trHtml('err_arity', { fn: code(a[0] + '()'), n: a[1] }) : trHtml('err_arity_range', { fn: code(a[0] + '()'), min: a[1], max: a[2] });
    if (c === 'unclosed') return esc(tr('err_quote'));
    if (c === 'colour') return trHtml('err_colour', { c: code(a) });
    if (c === 'number') return esc(tr('err_number'));
    if (c === 'char') return trHtml('err_char', { c: code(a) });
    if (c === 'long') return esc(tr('err_long', { n: a }));
    if (c === 'brace') return esc(tr('err_brace'));
    if (c === 'deep') return esc(tr('err_deep'));
    return esc(e.message || String(e));
  }

  function geoEditor(host, src, key) {
    const spec = GEO[key];
    if (!spec) return;
    const max = spec[1] === 'W' ? W.w : spec[1] === 'H' ? W.h : spec[1];
    const label = trOr('geo.' + key, key);
    const p = prop(host, label);
    const v = src[key] == null ? '' : src[key];
    slider(p, label, spec[0], max, spec[2], { range: v, box: v }, x => { if (x === '' || !isFinite(+x)) return; src[key] = +x; changed(); }, key);
  }
  function syncGeoInputs() {
    const src = S.doc.layers[S.sel];
    if (!src) return;
    $$('#wbPaneProps [data-geo]').forEach(i => { const v = src[i.getAttribute('data-geo')]; i.value = v == null ? '' : v; });
  }

  function styleEditor(host, src, key, kind) {
    if (kind === 'path') {
      const p = prop(host, tr('style.path'), tr('style.path_hint'));
      const ta = el('textarea', 'text-input wb-expr', p); ta.value = src.d || ''; ta.rows = 2; ta.setAttribute('dir', 'ltr'); ta.spellcheck = false; ta.maxLength = 2000;
      ta.setAttribute('aria-label', tr('style.path'));
      ta.addEventListener('input', () => { src.d = ta.value; changed(); });
      return;
    }
    const opts = kind.indexOf('enum:') === 0 ? kind.slice(5).split(',').map(v => [v, trOr('enum.' + v, v)])
      : kind === 'weight' ? [[500, tr('style.w500')], [600, tr('style.w600')], [700, tr('style.w700')]]
      : kind === 'align' ? [['left', tr('style.left')], ['center', tr('style.center')], ['right', tr('style.right')]]
      : [['rect', tr('style.rect')], ['circle', tr('style.circle')], ['path', tr('style.path')]];
    const lab = key === 'drive' ? tr('motors') : key === 'region' ? tr('sign_style') : key === 'side' ? tr('drives_on')
      : kind === 'weight' ? tr('style.weight') : kind === 'align' ? tr('style.align') : tr('style.shape');
    const p = prop(host, lab);
    const cur = src[key] == null ? opts[0][0] : src[key];
    seg(p, opts, cur, v => {
      src[key] = v;
      if (key === 'shape' && v === 'path' && !src.d) src.d = 'M0 20 L20 0 L40 20 Z';
      changed(); renderInspector();
    });
  }

  /** Something that changes the drawing: rebuild and refresh every view of the file. */
  function changed() {
    rebuild();
    renderLayers();
    renderFile();
    renderChecks();
    renderSim();
    renderStatus();
    saveDraft();
  }
  /** Names only: no redraw needed. */
  function lightChange() {
    renderLayers();
    renderFile();
    renderChecks();
    renderStatus();
    saveDraft();
  }

  function updateResults() {
    if (S.tab !== 'props' || !S.doc) return;
    LIVE.forEach(f => f());
    const src = S.doc.layers[S.sel];
    if (!src) return;
    $$('#wbPaneProps .wb-res[data-key]').forEach(res => showResult(res, src, res.getAttribute('data-key'), res._kind || 'num'));
  }

  // ── simulated data panel ──────────────────────────────────────────────────
  // the simulator's units, which start as the car's own
  function renderUnits() {
    const host = $('#wbUnits');
    if (!host) return;
    host.innerHTML = '';
    el('span', 'wb-units-label', host, esc(tr('units_title')));
    seg(host, [['km', tr('unit_km')], ['mi', tr('unit_mi')]], S.units.distance, v => setUnits({ distance: v })).setAttribute('aria-label', tr('units_distance'));
    seg(host, [['bar', tr('unit_bar')], ['psi', tr('unit_psi')], ['kpa', tr('unit_kpa')]], S.units.tyre, v => setUnits({ tyre: v })).setAttribute('aria-label', tr('units_tyres'));
  }
  function setUnits(u) {
    S.units = Object.assign({}, S.units, u);
    renderUnits();
    if (S.doc) { rebuild(); renderInspector(); }
    renderTemplates();
    paintMine();
    if (window.WidgetCommunity && WidgetCommunity.repaint) WidgetCommunity.repaint();
  }
  function readUnits(u) {
    const tyre = u && typeof u.tyre === 'string' ? u.tyre.toLowerCase() : '';
    return { distance: u && u.distance === 'mi' ? 'mi' : 'km', tyre: ['bar', 'psi', 'kpa'].indexOf(tyre) >= 0 ? tyre : 'psi' };
  }

  function renderSimChips() {
    const chips = $('#wbScen');
    if (!chips) return;
    chips.innerHTML = '';
    SCEN_ORDER.forEach(id => {
      const on = !live.on && S.scenario === id;
      const b = btn('checkbox-item wb-chip' + (on ? ' active' : ''), chips, esc(tr('scen_' + id)), () => setScenario(id));
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
  }

  function renderSim() {
    renderSimChips();
    const badge = $('#wbSimState');
    if (badge) { setText(badge, tr(live.on ? 'live_badge' : 'sim_badge')); badge.classList.toggle('active', live.on); badge.classList.toggle('inactive', !live.on); }
    const bar = $('#wbLive'), simOnly = $('#wbSimOnly');
    if (bar) { if (live.ok && !live.on) bar.removeAttribute('hidden'); else bar.setAttribute('hidden', ''); }
    if (simOnly) { if (live.on) simOnly.setAttribute('hidden', ''); else simOnly.removeAttribute('hidden'); }
    const host = $('#wbSigs');
    if (!host || !W) return;
    host.innerHTML = '';
    const used = usedSignals();
    const keys = used.filter(k => SIG[k]);
    if (!keys.length) keys.push('soc');
    keys.forEach(k => {
      const s = SIG[k];
      if (!s) return;
      if (live.on) {
        // the car's reading, read-only
        const row = el('div', 'setting-row wb-sig wb-sig-live', host);
        row.setAttribute('data-key', k);
        const info = el('div', 'setting-info', row);
        el('div', 'setting-name wb-nm', info, '<b>' + esc(sigLabel(k)) + '</b>');
        el('div', 'setting-desc', info, '<code dir="ltr">' + esc(k) + '</code>');
        const v = el('span', 'slider-value wb-v', el('div', 'setting-control wb-sigctl', row));
        el('span', 'wb-vv', v, '');
        el('small', 'wb-vu', v, '');
        return;
      }
      const row = el('div', 'setting-row wb-sig' + (S.unknown.has(k) ? ' isunk' : ''), host);
      row.setAttribute('data-key', k);
      const info = el('div', 'setting-info', row);
      el('div', 'setting-name wb-nm', info, '<b>' + esc(sigLabel(k)) + '</b>' + (s.live ? '<span class="wb-fast" title="' + esc(tr('sim_live_title')) + '">' + esc(tr('sim_live')) + '</span>' : ''));
      el('div', 'setting-desc', info, '<code dir="ltr">' + esc(k) + '</code>');
      const ctl = el('div', 'setting-control wb-sigctl', row);
      const type = sigType(s);
      const value = host2 => { const v = el('span', 'slider-value wb-v', host2); el('span', 'wb-vv', v, ''); el('small', 'wb-vu', v, ''); };
      if (type === 'bool') {
        const sw = el('label', 'toggle-switch wb-switch wb-ctl', ctl);
        const input = el('input', '', sw); input.type = 'checkbox'; input.checked = !!S.data[k];
        input.setAttribute('aria-label', sigLabel(k));
        el('span', 'toggle-slider', sw);
        input.addEventListener('change', () => { S.data[k] = input.checked; manual(); });
        value(ctl);
      } else if (type === 'enum' && sigOptions(k).length) {
        const input = el('select', 'wb-select wb-ctl', ctl);
        input.setAttribute('aria-label', sigLabel(k));
        input.innerHTML = sigOptions(k).map(o => '<option value="' + esc(o[0]) + '"' + (S.data[k] === o[0] ? ' selected' : '') + '>' + esc(optLabel(k, o[0])) + '</option>').join('');
        input.addEventListener('change', () => { S.data[k] = +input.value; manual(); });
        value(ctl);
      } else {
        const sc = el('div', 'slider-control wb-ctl', ctl);
        const input = el('input', 'slider', sc); input.type = 'range';
        const mn = nz(s.min), mx = s.max == null ? mn + 100 : nz(s.max);
        input.min = mn; input.max = mx; input.step = s.step || 1;
        input.value = S.data[k] == null ? mn : S.data[k];
        input.setAttribute('aria-label', sigLabel(k));
        input.addEventListener('input', () => { S.data[k] = +input.value; manual(); });
        value(sc);
      }
      const unk = el('label', 'checkbox-item wb-unk' + (S.unknown.has(k) ? ' active' : ''), ctl); unk.title = tr('sim_unknown_title');
      const cb = el('input', '', unk); cb.type = 'checkbox'; cb.checked = S.unknown.has(k);
      cb.setAttribute('aria-label', tr('sim_unknown_named', { name: sigLabel(k) }));
      el('span', '', unk, esc(tr('sim_unknown')));
      cb.addEventListener('change', () => {
        if (cb.checked) S.unknown.add(k); else S.unknown.delete(k);
        row.classList.toggle('isunk', cb.checked);
        unk.classList.toggle('active', cb.checked);
        feed(true); updateSimValues(); updateResults();
      });
    });
    const note = $('#wbSimNote');
    if (note) note.textContent = live.on ? tr('live_note') : tr('sim_note') + (SCEN[S.scenario] && SCEN[S.scenario].charge ? ' ' + tr('sim_note_charge') : '');
    updateSimValues();
  }

  function manual() {
    if (SCEN[S.scenario] && (SCEN[S.scenario].drive || SCEN[S.scenario].charge || SCEN[S.scenario].hazards)) { S.scenario = 'manual'; renderSimChips(); }
    feed(true);
    updateSimValues();
    updateResults();
  }

  function fmtSim(s, v) {
    const step = nz(s.step) || 1;
    return String(+(+v).toFixed(step < 1 ? (step < 0.1 ? 2 : 1) : 0));
  }

  function updateSimValues() {
    $$('#wbSigs .wb-sig').forEach(row => {
      const k = row.getAttribute('data-key'), s = SIG[k], v = S.data[k];
      const vv = $('.wb-vv', row), vu = $('.wb-vu', row);
      if (!s || !vv) return;
      if (live.on) {
        const t = liveText(k, live.values[k]);
        row.classList.toggle('isnone', !t);
        setText(vv, t ? t.v : tr('live_none'));
        setText(vu, t ? t.u : '');
        return;
      }
      if (S.unknown.has(k) || v == null) { setText(vv, '–'); setText(vu, tr('sim_no_reading')); return; }
      const type = sigType(s);
      if (type === 'bool') { setText(vv, tr(v ? 'yes' : 'no')); setText(vu, ''); }
      else if (type === 'enum' && sigOptions(k).length) { setText(vv, optLabel(k, v)); setText(vu, ''); }
      else { setText(vv, fmtSim(s, v)); setText(vu, s.unit || ''); }
      const input = row.querySelector('input[type=range]');
      if (input && document.activeElement !== input) input.value = v;
      const sel2 = row.querySelector('select');
      if (sel2 && document.activeElement !== sel2) sel2.value = String(v);
      const cb = row.querySelector('.wb-switch input');
      if (cb) cb.checked = !!v;
    });
  }

  // ── widget file + checks ──────────────────────────────────────────────────
  function renderFile() {
    const host = $('#wbPaneFile');
    if (!host || !S.doc) return;
    host.innerHTML = '';
    const doc = ODW.cleanDoc(S.doc);
    const json = JSON.stringify(doc, null, 2);
    const head = el('div', 'wb-filehead', host);
    el('span', '', head, esc(tr('file_intro')));
    const acts = el('div', 'wb-fileacts', host);
    btn('btn btn-secondary', acts, esc(tr('file_copy')), () => copyText(json));
    btn('btn btn-secondary', acts, esc(tr('file_download')), () => download(json, doc.name));
    btn('btn btn-secondary', acts, esc(tr('file_open')), () => openFile());
    const kb = ODW.carBytes(doc) / 1024;
    el('span', 'wb-hint wb-filesize', acts, esc(tr('file_size', { kb: kb.toFixed(1), max: Math.round(S.limits.maxBytes / 1024) })));
    const pre = el('pre', 'wb-file', host);
    pre.setAttribute('dir', 'ltr');
    pre.innerHTML = esc(json)
      .replace(/(&quot;[^&]*?&quot;)(\s*:)/g, '<span class="k">$1</span>$2')
      .replace(/(:\s*)(&quot;=.*?&quot;)(,?)$/gm, '$1<span class="e">$2</span>$3')
      .replace(/(:\s*)(&quot;(?!=).*?&quot;)(,?)$/gm, '$1<span class="s">$2</span>$3')
      .replace(/(:\s*)(-?\d+(\.\d+)?)(,?)$/gm, '$1<span class="n">$2</span>$4');
  }

  function copyText(text) {
    const done = () => toast(tr('file_copied'), 'success');
    if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, () => legacyCopy(text, done)); return; }
    legacyCopy(text, done);
  }
  function legacyCopy(text, done) {
    const ta = el('textarea', 'wb-offscreen', document.body);
    ta.value = text;
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    if (ok) done(); else toast(tr('file_copy_failed'), 'error');
  }
  function download(text, name) {
    const slug = String(name || 'widget').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'widget';
    const blob = new Blob([text], { type: 'application/json' });
    const a = el('a', '', document.body);
    a.href = URL.createObjectURL(blob);
    a.download = slug + '.json';
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); if (a.parentNode) a.parentNode.removeChild(a); }, 1000);
  }
  function openFile() {
    const input = el('input', 'wb-offscreen', document.body);
    input.type = 'file';
    input.accept = '.json,application/json';
    input.addEventListener('change', () => {
      const f = input.files && input.files[0];
      if (input.parentNode) input.parentNode.removeChild(input);
      if (!f) return;
      if (f.size > 64 * 1024) { toast(tr('file_too_big'), 'error'); return; }
      const rd = new FileReader();
      rd.onload = () => {
        let doc = null;
        try { doc = JSON.parse(String(rd.result)); } catch (e) { doc = null; }
        if (!doc || typeof doc !== 'object' || !Array.isArray(doc.layers)) { toast(tr('file_bad'), 'error'); return; }
        guardDiscard().then(ok => { if (ok) { openDoc(doc, { id: null, saved: null }); toast(tr('file_opened'), 'success'); } });
      };
      rd.readAsText(f);
    });
    input.click();
  }

  function layerName(i) {
    const l = S.doc.layers[i];
    return l && typeof l === 'object' && typeof l.name === 'string' && l.name.trim() ? l.name.trim() : tr('layer_n', { n: i + 1 });
  }

  /** Everything the car would refuse on save, as HTML lines. Kinds it doesn't know are skipped, not refused. */
  function blockers() {
    const out = [];
    // points from the layer to its field in reading order, so it flips under RTL
    const arrow = document.documentElement.getAttribute('dir') === 'rtl' ? ' ← ' : ' → ';
    W.layers.forEach((L, i) => {
      if (L.error) return;
      Object.keys(L.errors).forEach(k => out.push(esc(layerName(i)) + arrow + esc(propLabel(k)) + ': ' + friendlyError(L.errors[k])));
    });
    ODW.docProblems(ODW.cleanDoc(S.doc)).forEach(p => {
      if (p.code === 'prop') {
        const what = p.key === 'name' ? tr('layer_name') : propLabel(p.key);
        out.push(esc(layerName(p.layer)) + arrow + esc(what) + ': ' + esc(tr('why_' + p.why, { n: p.why === 'text_long' ? ODW.MAX_TEXT : ODW.MAX_NAME })));
      } else out.push(esc(tr('cap_' + p.code, { n: p.n == null ? (p.layer == null ? '' : p.layer + 1) : p.n })));
    });
    return out;
  }

  function renderChecks() {
    const host = $('#wbPaneChecks');
    if (!host || !W) return;
    host.innerHTML = '';
    const list = el('div', 'wb-checks', host);
    const add = (kind, title, body) => el('div', 'wb-check ' + kind, list, '<i>' + { ok: '✓', info: 'i', warn: '!', bad: '✕' }[kind] + '</i><div><b>' + title + '</b><span>' + body + '</span></div>');
    let issues = 0;
    const errs = blockers();
    if (errs.length) { issues += errs.length; add('bad', esc(tr('chk_fix')), errs.join('<br>')); }
    const skipped = W.layers.filter(L => L.error && L.src && typeof L.src === 'object');
    if (skipped.length) { issues += skipped.length; add('warn', esc(tr('chk_skipped')), esc(tr('chk_skipped_body', { list: skipped.map(L => '“' + String(L.src.type) + '”').join(', ') }))); }
    add('ok', esc(tr('chk_runs')), esc(tr('chk_runs_body')));
    const loops = W.layers.filter(L => L.loop && !L.error);
    if (loops.length) add('info', esc(tr('chk_loop')), esc(tr('chk_loop_body', { names: loops.map(L => '“' + (L.src.name || typeLabel(L.type)) + '”').join(', ') })));
    else add('ok', esc(tr('chk_idle')), esc(tr('chk_idle_body')));
    const sigs = usedSignals(), unknown = sigs.filter(k => !SIG[k]);
    if (unknown.length) { issues += unknown.length; add('warn', esc(tr('chk_unknown')), unknown.map(friendlyUnknown).join('<br>')); }
    const known = sigs.filter(k => SIG[k]);
    if (known.length) add('ok', esc(tr('chk_reads', { n: known.length })), known.map(k => code(k)).join(', ') + ' ' + esc(tr('chk_reads_body')));
    const live = known.filter(k => SIG[k].live);
    if (live.length) add('info', esc(tr('chk_live')), live.map(k => code(k)).join(', ') + ' ' + esc(tr('chk_live_body')));
    add('ok', esc(tr('chk_safe')), esc(tr('chk_safe_body')));
    const n = S.doc.layers.length;
    add(n > MAX_LAYERS ? 'bad' : 'ok', esc(tr('chk_layers', { n, max: MAX_LAYERS })), esc(tr('chk_layers_body')));
    const badge = $('#wbCheckN');
    if (badge) { setText(badge, issues ? String(issues) : '✓'); badge.classList.toggle('ok', !issues); badge.classList.toggle('active', !issues); }
  }

  // ── saving ────────────────────────────────────────────────────────────────
  function newId() {
    const a = new Uint8Array(6);
    try { crypto.getRandomValues(a); } catch (e) { for (let i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256); }
    let s = '';
    for (let i = 0; i < a.length; i++) s += (a[i] % 36).toString(36);
    return 'w-' + Date.now().toString(36) + '-' + s;
  }

  function errorText(r, fallbackKey, vars) {
    if (r.status === 0) return tr('err_offline');
    // the car's own words when it saved but couldn't finish ("save again")
    if (r.status === 503 && r.error) return r.error;
    if (r.status === 413) return tr('err_too_big', { n: Math.round(S.limits.maxBytes / 1024), max: S.limits.maxWidgets });
    if (r.status === 409) return r.error ? tr('err_car_said', { msg: r.error }) : tr('err_full', { n: S.limits.maxWidgets });
    if (r.status === 401 || r.status === 403) return tr('err_auth');
    if (r.error) return tr('err_car_said', { msg: r.error });
    return tr(fallbackKey, vars);
  }

  function save() {
    if (S.saving || !W) return Promise.resolve(false);
    const errs = blockers();
    if (errs.length) {
      toast(tr('save_fix_first'), 'error');
      renderChecks();
      showTab('checks');
      if (S.narrow) showPanel('edit');
      return Promise.resolve(false);
    }
    if (typeof S.doc.name !== 'string' || !S.doc.name.trim()) S.doc.name = tr('untitled').slice(0, ODW.MAX_NAME);
    const doc = ODW.cleanDoc(S.doc);
    const id = S.docId || S.pendingId || (S.pendingId = newId());
    const isNew = !S.mine.some(x => x.id === id);
    if (isNew && S.mineState === 'ok' && S.mine.length >= S.limits.maxWidgets) {
      alertDlg({ title: tr('full_title'), body: tr('err_full', { n: S.limits.maxWidgets }) });
      return Promise.resolve(false);
    }
    saveDraft();
    S.saving = true;
    renderStatus();
    return api('PUT', '/api/widgets/custom/' + encodeURIComponent(id), { doc }).then(r => {
      S.saving = false;
      if (!r.ok) {
        renderStatus();
        toast(errorText(r, 'save_failed'), 'error');
        // the car may have kept the file anyway
        if (r.status === 0 || r.status >= 500) loadMine();
        return false;
      }
      S.pendingId = null;
      const canonical = r.data.doc && typeof r.data.doc === 'object' ? r.data.doc : doc;
      const wasNew = !S.docId;
      S.docId = r.data.id || id;
      const keepSel = S.sel;
      S.doc = ODW.cleanDoc(canonical);
      S.sel = keepSel < S.doc.layers.length ? keepSel : -1;
      S.hidden = new WeakSet();
      S.baseJson = JSON.stringify(S.doc);
      S.savedJson = JSON.stringify(ODW.cleanDoc(S.doc));
      rebuild();
      renderAll();
      saveDraft();
      if (shell) { shell.classList.remove('is-entering'); void shell.offsetWidth; shell.classList.add('is-entering'); }
      return loadMine().then(() => {
        const entry = S.mine.find(x => x.id === S.docId);
        if (entry && entry.shown) { toast(tr('saved_shown', { name: S.doc.name || tr('untitled') }), 'success'); return true; }
        if (wasNew && entry) {
          return confirmDlg({ title: tr('saved_title'), body: tr('saved_ask_show', { name: S.doc.name || tr('untitled') }), confirmLabel: tr('show_on_car'), cancelLabel: tr('not_now') }).then(ok => {
            if (!ok) return true;
            return api('POST', '/api/widgets/custom/' + encodeURIComponent(entry.id) + '/show', { shown: true }).then(sr => {
              if (sr.ok) { entry.shown = true; toast(tr('shown_on', { name: S.doc.name || tr('untitled') }), 'success'); renderMine(); renderStatus(); }
              else toast(errorText(sr, 'show_failed'), 'error');
              return true;
            });
          });
        }
        toast(tr('saved', { name: S.doc.name || tr('untitled') }), 'success');
        return true;
      });
    });
  }

  function renderStatus() {
    const st = $('#wbDocState');
    let key = 'status_new';
    if (S.saving) key = 'status_saving';
    else if (S.docId) {
      const clean = JSON.stringify(ODW.cleanDoc(S.doc)) === S.savedJson;
      const entry = S.mine.find(x => x.id === S.docId);
      key = !clean ? 'status_unsaved' : entry && entry.shown ? 'status_saved_shown' : 'status_saved';
    }
    if (st) { setText(st, tr(key)); st.setAttribute('data-state', key); }
    $$('#wbSave, #wbShare').forEach(b => { b.disabled = S.saving || !S.ready; });
  }

  function saveDraft() {
    if (!S.doc) return;
    clearTimeout(saveDraft.h);
    saveDraft.h = setTimeout(() => store(DRAFT_KEY, { doc: S.doc, docId: S.docId, pendingId: S.pendingId, baseJson: S.baseJson, savedJson: S.savedJson, tpl: S.tpl }), 400);
  }
  function savePrefs() { store(PREFS_KEY, { simple: S.simple, theme: S.theme }); }

  // ── layout and chrome ─────────────────────────────────────────────────────
  function showTab(v) {
    S.tab = v;
    $$('#wbTabs button').forEach(b => { const on = b.getAttribute('data-v') === v; b.classList.toggle('active', on); b.setAttribute('aria-selected', on ? 'true' : 'false'); });
    $$('#wbRight .wb-pane').forEach(p => p.classList.toggle('on', p.id === 'wbPane' + v.charAt(0).toUpperCase() + v.slice(1)));
    if (v === 'props') updateResults();
  }

  function showPanel(p) {
    S.panel = p === 'edit' || p === 'data' ? p : 'layers';
    root.setAttribute('data-panel', S.panel);
    $$('#wbMtabs button').forEach(b => { const on = b.getAttribute('data-p') === S.panel; b.classList.toggle('active', on); b.setAttribute('aria-selected', on ? 'true' : 'false'); });
  }

  // Design and Community are the page's section tabs (app-tabs.js); without them the sections switch here
  function setMode(m, fromTabs) {
    m = m === 'community' || m === 'mine' ? m : 'design';
    const tab = document.querySelector('.bottom-tab[data-tab-target="' + m + '"]');
    if (!fromTabs && tab && !tab.classList.contains('is-active')) { tab.click(); return; }
    const changedMode = S.mode !== m;
    S.mode = m;
    root.setAttribute('data-mode', m);
    if (!tab) {
      [['#wbDesign', 'design'], ['#wbMineTab', 'mine'], ['#wbCommunity', 'community']].forEach(x => { const n = $(x[0]); if (n) { if (x[1] === m) n.removeAttribute('hidden'); else n.setAttribute('hidden', ''); } });
    }
    if (m === 'community') {
      const host = $('#wbCommunity');
      if (window.WidgetCommunity && host) WidgetCommunity.mount(host);
    } else if (m === 'mine') {
      if (S.ready) loadMine();
      paintMine();
    } else if (changedMode) {
      fitPreview();
      dirty = true;
      kick();
      renderTemplates();
      startLive();
    }
  }

  function applyNarrow(n) {
    if (S.narrow === n) return;
    S.narrow = n;
    root.classList.toggle('wb-narrow', n);
    document.body.classList.toggle('wb-is-narrow', n);
    fitPreview();
  }

  function observeLayout() {
    const measure = () => { applyNarrow(root.clientWidth < 1040); fitPreview(); };
    if (typeof ResizeObserver !== 'undefined') {
      let raf = 0;
      new ResizeObserver(() => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; measure(); }); }).observe(root);
    } else window.addEventListener('resize', measure);
    measure();
  }

  function setTheme(theme) {
    S.theme = theme === 'day' ? 'day' : 'night';
    savePrefs();
    $$('#wbTheme button').forEach(x => { const on = x.getAttribute('data-v') === S.theme; x.classList.toggle('active', on); x.setAttribute('aria-pressed', on ? 'true' : 'false'); });
    const stage = $('#wbStage');
    if (stage) stage.setAttribute('data-look', S.theme);
    document.body.setAttribute('data-wb-look', S.theme);
    if (W) W.setTheme(S.theme);
    dirty = true;
    kick();
    renderTemplates();
    renderInspector();
    paintMine();
    if (window.WidgetCommunity && WidgetCommunity.repaint) WidgetCommunity.repaint();
  }

  function renderStageText() {
    const d = new Date();
    const lang = (window.BYD && BYD.i18n && BYD.i18n.getLang && BYD.i18n.getLang()) || undefined;
    try {
      setText($('#wbClock'), d.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit', hour12: false }));
      setText($('#wbDate'), d.toLocaleDateString(lang, { weekday: 'long', day: 'numeric', month: 'long' }));
    } catch (e) { /* old Intl */ }
  }

  function renderAll() {
    renderTemplates();
    renderLayers();
    renderInspector();
    renderFile();
    renderChecks();
    renderSim();
    renderMine();
    renderStatus();
    renderUnits();
    meterEls = null;
    updateMeter();
  }

  function wire() {
    $$('#wbTheme button').forEach(b => b.addEventListener('click', () => setTheme(b.getAttribute('data-v'))));
    $$('#wbZoom button').forEach(b => b.addEventListener('click', () => {
      S.zoom = +b.getAttribute('data-v') || 1;
      $$('#wbZoom button').forEach(x => { x.classList.toggle('active', x === b); x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
      fitPreview();
    }));
    $$('#wbTabs button').forEach(b => b.addEventListener('click', () => showTab(b.getAttribute('data-v'))));
    $$('#wbMtabs button').forEach(b => b.addEventListener('click', () => showPanel(b.getAttribute('data-p'))));
    document.addEventListener('ot-tabs:active-changed', e => { const id = e && e.detail && e.detail.id; if (id === 'design' || id === 'mine' || id === 'community') setMode(id, true); });
    $$('#wbSave').forEach(b => b.addEventListener('click', () => save()));
    $$('#wbShare').forEach(b => b.addEventListener('click', () => shareOpen()));
    $$('#wbMineNew').forEach(b => b.addEventListener('click', () => setMode('design')));
    // the Add menu floats on the page body, above the app sidebar
    const addList = $('#wbAddList');
    if (addList.parentNode !== document.body) document.body.appendChild(addList);
    addList.addEventListener('click', e => e.stopPropagation());
    const addBtn = $('#wbAddBtn');
    addBtn.addEventListener('click', e => {
      e.stopPropagation();
      const open = !addList.classList.contains('open');
      if (!open) { closeAddMenu(); return; }
      $('#wbAddMenu').classList.add('open');
      addList.classList.add('open');
      addBtn.setAttribute('aria-expanded', 'true');
      placeAddMenu();
    });
    document.addEventListener('click', e => { if (addList.classList.contains('open') && !addList.contains(e.target)) closeAddMenu(); });
    window.addEventListener('resize', closeAddMenu);
    window.addEventListener('scroll', () => { if (addList.classList.contains('open')) closeAddMenu(); }, { passive: true });
    $$('#wbBackLive').forEach(b => b.addEventListener('click', () => goLive(true)));
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') closeAddMenu();
      if (S.mode !== 'design' || S.sel < 0 || !S.doc || !/^Arrow/.test(e.key)) return;
      const tag = (e.target && e.target.tagName) || '';
      if (/^(INPUT|TEXTAREA|SELECT)$/.test(tag) || (e.target && e.target.isContentEditable) || document.querySelector('.modal-backdrop')) return;
      const src = S.doc.layers[S.sel];
      if (!src || !TYPES[src.type]) return;
      const keys = TYPES[src.type].props.cx ? ['cx', 'cy'] : ['x', 'y'];
      const step = e.shiftKey ? 10 : 1;
      const dx = e.key === 'ArrowLeft' ? -step : e.key === 'ArrowRight' ? step : 0;
      const dy = e.key === 'ArrowUp' ? -step : e.key === 'ArrowDown' ? step : 0;
      e.preventDefault();
      src[keys[0]] = nz(src[keys[0]]) + dx;
      src[keys[1]] = nz(src[keys[1]]) + dy;
      rebuild(); syncGeoInputs(); renderFile(); saveDraft(); renderStatus();
    });
    $('#wbStale').addEventListener('change', e => { S.stale = e.target.checked; feed(false); dirty = true; kick(); updateMeter(); });
    $('#wbMotion').addEventListener('change', e => { S.reduceMotion = e.target.checked; feed(false); dirty = true; kick(); updateMeter(); });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { dirty = true; kick(); startLive(); } });
    window.addEventListener('resize', () => fitPreview());
    if (window.BYD && BYD.i18n && BYD.i18n.onChange) BYD.i18n.onChange(() => { if (S.ready) { if (shell) shell = null; rebuild(); renderAll(); renderStageText(); } });
  }

  function showCarNotice() {
    root.innerHTML = '';
    const box = el('div', 'empty-state', el('div', 'card-body', el('div', 'card wb-carnote', root)));
    el('h2', 'empty-title', box, esc(tr('car_notice_title')));
    el('p', 'empty-text', box, esc(tr('car_notice_body')));
  }

  function bootDoc(q) {
    const draft = load(DRAFT_KEY);
    let opened = false;
    if (draft && draft.doc && Array.isArray(draft.doc.layers)) {
      // a draft that can't open is dropped, or it would break every reload
      try {
        openDoc(draft.doc, { id: typeof draft.docId === 'string' ? draft.docId : null, pendingId: typeof draft.pendingId === 'string' && /^[a-z0-9-]{8,40}$/.test(draft.pendingId) ? draft.pendingId : null, saved: typeof draft.savedJson === 'string' ? draft.savedJson : null, tpl: typeof draft.tpl === 'string' ? draft.tpl : null });
        if (typeof draft.baseJson === 'string') S.baseJson = draft.baseJson;
        opened = true;
      } catch (e) { store(DRAFT_KEY, null); toast(tr('draft_broken'), 'warning'); }
    }
    const tplParam = q.get('tpl');
    const tp = tplParam ? templateList().find(x => x.id === tplParam) : null;
    if (tp) {
      dropParam('tpl');
      if (!opened) { openDoc(clone(tp.doc), { tpl: tp.id }); return; }
      guardDiscard().then(ok => { if (ok) openDoc(clone(tp.doc), { id: null, saved: null, tpl: tp.id }); renderStatus(); });
    }
    if (opened) { renderStatus(); return; }
    const first = S.templates.find(x => x.id === 'charge-ring') || S.templates[0];
    if (first) openDoc(clone(first.doc), { tpl: first.id });
    else openDoc(blankDoc(), { tpl: 'blank' });
  }

  function init() {
    root = $('#wb');
    if (!root || root.getAttribute('data-ready')) return;
    root.setAttribute('data-ready', '1');
    if (inCarApp()) { showCarNotice(); return; }
    const prefs = load(PREFS_KEY) || {};
    S.simple = prefs.simple !== false;
    S.theme = prefs.theme === 'day' || prefs.theme === 'night' ? prefs.theme : (document.documentElement.getAttribute('data-theme') === 'light' ? 'day' : 'night');
    const q = new URLSearchParams(location.search);
    setCatalog(BUNDLED_SIGNALS);
    wire();
    observeLayout();
    setTheme(S.theme);
    renderStageText();
    renderMine();
    const catalog = withTimeout(api('GET', '/api/widgets/signals'), 8000).then(r => (r && r.ok ? r.data : null));
    const tpls = fetchJson('/shared/widget-builder/templates/index.json').then(list => {
      if (!Array.isArray(list)) return [];
      return Promise.all(list.filter(x => x && typeof x.file === 'string' && /^[a-z0-9-]+\.json$/.test(x.file)).map(x =>
        fetchJson('/shared/widget-builder/templates/' + x.file).then(doc => (doc && Array.isArray(doc.layers)
          ? { id: x.file.replace(/\.json$/, ''), name: x.name || doc.name, doc: ODW.cleanDoc(doc) } : null)))).then(a => a.filter(Boolean));
    }).catch(() => []);
    Promise.all([catalog, tpls]).then(res => {
      const cat = res[0];
      if (cat && Array.isArray(cat.signals) && cat.signals.length) setCatalog(cat.signals);
      S.units = readUnits(cat && cat.units);
      S.carUnits = Object.assign({}, S.units);
      S.templates = res[1] || [];
      const end = ODW.now();
      for (let i = 120; i >= 1; i--) pushHistory(end - i * 1000);
      S.ready = true;
      root.classList.add('is-ready');
      bootDoc(q);
      const qm = q.get('mode'), tab = document.querySelector('.bottom-tab.is-active');
      setMode(qm === 'community' || qm === 'mine' ? qm : tab ? tab.getAttribute('data-tab-target') : 'design');
      setInterval(tick, 1000);
      renderStatus();
      // the car's own readings when it can share them; older car apps keep the simulator
      fetchLive().then(ok => { if (ok) goLive(true); });
    });
    loadMine().then(ok => { if (ok && q.get('edit')) waitReady(() => openMine(q.get('edit'))); });
  }
  function dropParam(name) {
    try { const u = new URL(location.href); u.searchParams.delete(name); history.replaceState(history.state, '', u.pathname + u.search + u.hash); } catch (e) { /* the link stays as it was */ }
  }
  function waitReady(fn) { if (S.ready) fn(); else setTimeout(() => waitReady(fn), 100); }

  // the community tab borrows these
  window.WidgetBuilder = {
    init,
    util: { tr, trOr, trHtml, esc, el, btn, api, toast, confirmDlg, alertDlg, promptDlg, modal, setText, code, store, load, sigLabel, errorText, icon },
    theme: () => S.theme,
    units: () => S.units,
    mine: () => S.mine,
    mineState: () => S.mineState,
    share: w => share(w),
    setMode: m => setMode(m),
    limits: () => S.limits,
    refreshMine: () => loadMine(),
    ensureSaved,
    signalLabel: k => sigLabel(k),
    openCopy,
  };
})();
