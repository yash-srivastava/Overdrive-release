/* Community tab of the widget builder: browse, live previews drawn from each widget file, rate, report, add to the
   car, edit a copy, and share or manage your own. Talks only to the car's /api/community/widgets routes; other
   people's words are always set as text, never as HTML. ES2017 only (no ?. ?? flat fromEntries replaceAll). */
(function () {
  'use strict';
  const ODW = window.ODW;
  const WB = window.WidgetBuilder;
  if (!ODW || !WB || !WB.util) return;
  const U = WB.util;
  const tr = U.tr, esc = U.esc, el = U.el, btn = U.btn;
  const CATS = ['battery', 'charging', 'driving', 'trip', 'tyres', 'road', 'other'];
  const SORTS = ['updated', 'created', 'downloads', 'rating'];
  const PAGE = 12;
  const AUTHOR_KEY = 'odw.community.author';
  const MAX_AUTHOR = 40, MAX_DESC = 500;
  const C = { host: null, mounted: false, items: [], next: null, loading: false, error: '', q: '', sort: 'updated', cat: '', seq: 0, timer: 0, grid: null, more: null, status: null, theme: null };
  const base = '/api/community/widgets/';
  const enc = encodeURIComponent;

  function cmError(r, key) {
    if (r.status === 0) return tr('err_offline');
    if (r.status === 429) return tr('cm_err_slow');
    if (r.error) return tr('err_car_said', { msg: r.error });
    if (r.status === 401 || r.status === 403) return tr('cm_err_denied');
    return tr(key);
  }

  function normalize(it) {
    if (!it || typeof it !== 'object' || typeof it.id !== 'string') return null;
    let doc = it.widget;
    if (typeof doc === 'string') { try { doc = JSON.parse(doc); } catch (e) { doc = null; } }
    if (!doc || typeof doc !== 'object' || !Array.isArray(doc.layers)) doc = null;
    const n = v => (typeof v === 'number' && isFinite(v) ? v : 0);
    return {
      id: it.id, widget: doc, mine: it.mine === true,
      name: String(it.name || (doc && doc.name) || tr('untitled')).slice(0, 80),
      authorName: String(it.authorName || '').slice(0, 60),
      description: String(it.description || '').slice(0, MAX_DESC),
      category: CATS.indexOf(it.category) >= 0 ? it.category : 'other',
      ratingAvg: n(it.ratingAvg), ratingCount: n(it.ratingCount), downloads: n(it.downloads),
      createdMs: n(it.createdMs), updatedMs: n(it.updatedMs),
    };
  }

  function relTime(ms) {
    if (!ms) return '';
    const d = Math.max(0, Date.now() - ms), min = 60000, hr = 60 * min, day = 24 * hr;
    if (d < 2 * min) return tr('time_recent');
    if (d < hr) return tr('time_minutes', { n: Math.floor(d / min) });
    if (d < day) return tr('time_hours', { n: Math.floor(d / hr) });
    if (d < 30 * day) return tr('time_days', { n: Math.floor(d / day) });
    try { return new Date(ms).toLocaleDateString(); } catch (e) { return ''; }
  }

  function starsText(avg) {
    const full = Math.max(0, Math.min(5, Math.round(avg)));
    return '★★★★★'.slice(0, full) + '☆☆☆☆☆'.slice(0, 5 - full);
  }
  function rating(item, host) {
    const s = el('span', 'wb-cm-stars', host);
    if (!item.ratingCount) { s.textContent = tr('cm_no_ratings'); return s; }
    const st = el('b', '', s); st.textContent = starsText(item.ratingAvg); st.setAttribute('aria-hidden', 'true');
    el('span', '', s).textContent = ' ' + item.ratingAvg.toFixed(1) + ' (' + item.ratingCount + ')';
    s.setAttribute('aria-label', tr('cm_rating_aria', { avg: item.ratingAvg.toFixed(1), n: item.ratingCount }));
    return s;
  }

  // ── list ──────────────────────────────────────────────────────────────────
  function mount(host) {
    if (C.mounted) { paintSoon(); return; }
    C.mounted = true;
    C.host = host;
    host.innerHTML = '';
    const head = el('div', 'wb-section-head wb-cm-head', host);
    el('h2', 'wb-section-title wb-cm-title', head, esc(tr('cm_title')));
    shareButton(head);
    el('p', 'wb-cm-intro', host, esc(tr('cm_intro')));
    const bar = el('div', 'wb-cm-bar', host);
    const search = el('input', 'text-input wb-cm-search', bar);
    search.type = 'search'; search.maxLength = 80; search.placeholder = tr('cm_search');
    search.setAttribute('aria-label', tr('cm_search')); search.setAttribute('dir', 'auto');
    search.addEventListener('input', () => {
      clearTimeout(C.timer);
      C.timer = setTimeout(() => { const q = search.value.trim(); if (q !== C.q) { C.q = q; load(true); } }, 350);
    });
    const sort = el('select', 'wb-select wb-cm-sort', bar);
    sort.setAttribute('aria-label', tr('cm_sort'));
    sort.innerHTML = SORTS.map(v => '<option value="' + v + '">' + esc(tr('cm_sort_' + v)) + '</option>').join('');
    sort.addEventListener('change', () => { C.sort = sort.value; load(true); });
    const cats = el('div', 'checkbox-row wb-chips wb-cm-cats', host);
    [''].concat(CATS).forEach(c => {
      const b = btn('checkbox-item wb-chip' + (c === C.cat ? ' active' : ''), cats, esc(tr(c ? 'cat_' + c : 'cat_all')), () => {
        C.cat = c;
        Array.prototype.forEach.call(cats.children, x => { x.classList.toggle('active', x === b); x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
        load(true);
      });
      b.setAttribute('aria-pressed', c === C.cat ? 'true' : 'false');
    });
    C.status = el('div', 'wb-cm-status', host);
    C.status.setAttribute('role', 'status');
    C.grid = el('div', 'wb-cm-grid', host);
    C.more = btn('btn btn-secondary wb-cm-more', host, esc(tr('cm_more')), () => load(false));
    C.more.hidden = true;
    load(true);
  }

  function load(reset) {
    if (!C.mounted) return;
    const seq = ++C.seq;
    if (reset) { C.items = []; C.next = null; C.grid.innerHTML = ''; }
    C.loading = true;
    C.error = '';
    renderStatus();
    const qs = ['sort=' + enc(C.sort), 'pageSize=' + PAGE];
    if (C.q) qs.push('q=' + enc(C.q));
    if (C.cat) qs.push('category=' + enc(C.cat));
    const cursor = reset ? null : C.next;
    if (cursor) qs.push('cursor=' + enc(cursor));
    U.api('GET', base + 'list?' + qs.join('&')).then(r => {
      if (seq !== C.seq) return;
      C.loading = false;
      if (!r.ok || !Array.isArray(r.data.items)) { C.error = cmError(r, 'cm_load_failed'); renderStatus(); return; }
      const items = r.data.items.map(normalize).filter(Boolean).filter(it => !C.items.some(x => x.id === it.id));
      C.items = C.items.concat(items);
      // The daemon can filter every row from a page yet return a valid next cursor.
      // Keep pagination available even if normalization/deduplication added no cards.
      const nc = r.data.nextCursor == null ? '' : String(r.data.nextCursor);
      C.next = nc && nc !== cursor ? nc : null;
      items.forEach(it => C.grid.appendChild(card(it)));
      paintSoon();
      renderStatus();
    });
  }

  function renderStatus() {
    if (!C.status) return;
    C.status.innerHTML = '';
    if (C.loading) el('div', 'wb-empty', C.status, esc(tr('loading')));
    else if (C.error) {
      const e = el('div', 'wb-empty', C.status);
      e.textContent = C.error + ' ';
      btn('wb-link', e, esc(tr('retry')), () => load(!C.next));
    } else if (!C.items.length && !C.next && (C.q || C.cat)) el('div', 'wb-empty', C.status, esc(tr('cm_none_match')));
    else if (!C.items.length && !C.next) {
      const box = el('div', 'empty-state', el('div', 'card-body', el('div', 'card wb-cm-empty', C.status)));
      el('p', 'empty-text', box, esc(tr('cm_none_yet')));
      shareButton(box);
    }
    C.more.hidden = !C.next || C.loading;
  }

  function card(item) {
    const c = el('article', 'card wb-cm-card');
    c.setAttribute('data-id', item.id);
    const cv = el('canvas', 'wb-cm-preview', c);
    cv.setAttribute('role', 'img');
    cv.setAttribute('aria-label', tr('cm_preview_of', { name: item.name }));
    cv._doc = item.widget;
    const body = el('div', 'wb-cm-body', c);
    const name = el('h3', 'wb-cm-name', body); name.textContent = item.name; name.setAttribute('dir', 'auto');
    const by = el('div', 'wb-cm-by', body); by.textContent = tr('cm_by', { name: item.authorName || '—' }); by.setAttribute('dir', 'auto');
    const meta = el('div', 'wb-cm-meta', body);
    rating(item, meta);
    el('span', '', meta).textContent = tr('cm_added_n', { n: item.downloads });
    el('span', 'status-badge inactive wb-badge-plain wb-cm-cat', meta).textContent = tr('cat_' + item.category);
    if (item.mine) el('span', 'status-badge active wb-badge-plain wb-cm-mine', meta).textContent = tr('cm_mine');
    if (item.description) { const d = el('p', 'wb-cm-desc', body); d.textContent = item.description; d.setAttribute('dir', 'auto'); }
    const acts = el('div', 'wb-cm-acts', c);
    const add = btn('btn btn-secondary wb-cm-add', acts, esc(tr('cm_add')), () => addToCar(item, add));
    if (!item.widget) add.disabled = true;
    const copy = btn('btn btn-secondary wb-cm-copy', acts, esc(tr('cm_copy')), () => editCopy(item));
    if (!item.widget) copy.disabled = true;
    btn('btn btn-secondary wb-cm-details', acts, esc(tr('cm_details')), () => details(item));
    return c;
  }

  // previews draw once each, after layout; a theme change or a hidden tab repaints them later
  let paintRaf = 0;
  function paintSoon() {
    if (paintRaf) return;
    paintRaf = requestAnimationFrame(() => {
      paintRaf = 0;
      if (!C.grid) return;
      const theme = WB.theme(), units = WB.units();
      const look = theme + '/' + units.distance + '/' + units.tyre;
      Array.prototype.forEach.call(C.grid.querySelectorAll('canvas.wb-cm-preview'), cv => {
        if (cv._painted === look) return;
        if (!cv._doc) { cv._painted = look; return; }
        if (ODW.renderStill(cv, cv._doc, { theme, units })) cv._painted = look;
      });
    });
  }
  function repaint() { if (C.mounted) paintSoon(); }

  function refreshItem(id) {
    return U.api('GET', base + 'item/' + enc(id)).then(r => {
      const it = r.ok ? normalize(r.data.item) : null;
      if (!it) return null;
      const i = C.items.findIndex(x => x.id === id);
      if (i >= 0) C.items[i] = it;
      const old = cardOf(id);
      if (old && old.parentNode) { old.parentNode.replaceChild(card(it), old); paintSoon(); }
      return it;
    });
  }
  // ids are compared as strings, never built into selectors
  const cardOf = id => (C.grid ? Array.prototype.find.call(C.grid.children, c => c.getAttribute('data-id') === id) : null) || null;

  function dropItem(id) {
    C.items = C.items.filter(x => x.id !== id);
    const old = cardOf(id);
    if (old && old.parentNode) old.parentNode.removeChild(old);
    renderStatus();
  }

  // ── actions ───────────────────────────────────────────────────────────────
  function addToCar(item, button) {
    if (button) button.disabled = true;
    U.api('POST', base + 'import/' + enc(item.id), {}).then(r => {
      if (button) button.disabled = false;
      if (!r.ok) { U.toast(U.errorText(r, 'cm_add_failed'), 'error'); return; }
      const localId = typeof r.data.localId === 'string' ? r.data.localId : null;
      refreshItem(item.id);
      WB.refreshMine().then(() => {
        if (!localId) { U.toast(tr('cm_added_mine', { name: item.name }), 'success'); return; }
        U.confirmDlg({ title: tr('cm_added_title'), body: tr('cm_added_body', { name: item.name }), confirmLabel: tr('show_on_car'), cancelLabel: tr('not_now') }).then(ok => {
          if (!ok) return;
          U.api('POST', '/api/widgets/custom/' + enc(localId) + '/show', { shown: true }).then(sr => {
            if (sr.ok) { U.toast(tr('shown_on', { name: item.name }), 'success'); WB.refreshMine(); }
            else U.toast(U.errorText(sr, 'show_failed'), 'error');
          });
        });
      });
    });
  }

  function editCopy(item) { if (item.widget) WB.openCopy(item.widget, item.name); }

  function rate(item, stars) {
    return U.api('POST', base + 'rate/' + enc(item.id), { stars }).then(r => {
      if (!r.ok) { U.toast(cmError(r, 'cm_rate_failed'), 'error'); return null; }
      U.toast(tr('cm_rated', { n: stars }), 'success');
      return refreshItem(item.id);
    });
  }

  function report(item) {
    U.promptDlg({ title: tr('cm_report_title'), body: tr('cm_report_body'), placeholder: tr('cm_report_placeholder'), confirmLabel: tr('cm_report'), cancelLabel: tr('cancel') }).then(reason => {
      if (reason == null) return;
      U.api('POST', base + 'report/' + enc(item.id), { reason: String(reason).trim().slice(0, 300) }).then(r => {
        if (r.ok) U.toast(tr('cm_reported'), 'success');
        else U.toast(cmError(r, 'cm_report_failed'), 'error');
      });
    });
  }

  function localFor(item) {
    return WB.mine().find(w => w.communityId === item.id && w.source !== 'community') || null;
  }

  function details(item) {
    U.modal((cardEl, close) => {
      cardEl.classList.add('wb-cm-detail');
      const h = el('h3', 'soh-modal-title', cardEl); h.textContent = item.name; h.setAttribute('dir', 'auto');
      const by = el('div', 'wb-cm-by', cardEl); by.textContent = tr('cm_by', { name: item.authorName || '—' }); by.setAttribute('dir', 'auto');
      const cv = el('canvas', 'wb-cm-big', cardEl);
      cv.setAttribute('role', 'img');
      cv.setAttribute('aria-label', tr('cm_preview_of', { name: item.name }));
      if (item.widget) requestAnimationFrame(() => ODW.renderStill(cv, item.widget, { theme: WB.theme(), units: WB.units(), pad: 8 }));
      if (item.description) { const d = el('p', 'wb-cm-desc wb-cm-desc-full', cardEl); d.textContent = item.description; d.setAttribute('dir', 'auto'); }
      const meta = el('div', 'wb-cm-meta', cardEl);
      rating(item, meta);
      el('span', '', meta).textContent = tr('cm_added_n', { n: item.downloads });
      el('span', 'status-badge inactive wb-badge-plain wb-cm-cat', meta).textContent = tr('cat_' + item.category);
      if (item.updatedMs) el('span', '', meta).textContent = tr('cm_updated', { when: relTime(item.updatedMs) });
      if (item.widget) {
        let names = [];
        try { names = new ODW.Widget(item.widget).signals(); } catch (e) { names = []; }
        if (names.length) {
          const rd = el('div', 'wb-note wb-cm-reads', cardEl);
          rd.textContent = tr('cm_reads', { list: names.map(k => WB.signalLabel(k)).join(', ') });
        }
      }
      const rateRow = el('div', 'wb-cm-rate', cardEl);
      el('span', 'wb-cm-rate-label', rateRow).textContent = tr('cm_rate');
      const starBox = el('span', 'wb-cm-rate-stars', rateRow);
      for (let n = 1; n <= 5; n++) {
        const b = btn('wb-star', starBox, n <= Math.round(item.ratingAvg) ? '★' : '☆', () => {
          rate(item, n).then(it => { if (it) { item = it; Array.prototype.forEach.call(starBox.children, (x, i) => { x.textContent = i < n ? '★' : '☆'; }); } });
        });
        b.setAttribute('aria-label', tr('cm_rate_n', { n }));
      }
      const acts = el('div', 'soh-modal-actions wb-cm-detail-acts', cardEl);
      btn('btn btn-secondary', acts, esc(tr('cm_report')), () => report(item));
      if (item.mine) {
        btn('btn btn-secondary', acts, esc(tr('cm_edit_details')), () => { close(); editDetails(item); });
        const local = localFor(item);
        if (local) btn('btn btn-secondary', acts, esc(tr('cm_push')), () => { close(); pushLatest(item, local); });
        btn('btn btn-danger', acts, esc(tr('cm_remove')), () => { close(); removeItem(item); });
      }
      btn('btn btn-secondary', acts, esc(tr('close')), close).setAttribute('data-autofocus', '');
      if (item.widget) {
        btn('btn btn-secondary', acts, esc(tr('cm_copy')), () => { close(); editCopy(item); });
        btn('btn btn-primary', acts, esc(tr('cm_add')), () => { close(); addToCar(item); });
      }
    });
  }

  // the car publishes the copy it has saved, so unsaved edits are saved first
  function pushLatest(item, local) {
    WB.ensureSaved(local.id).then(ok => { if (ok) sendLatest(item, local); });
  }
  function sendLatest(item, local) {
    U.api('PUT', base + 'item/' + enc(item.id), { localId: local.id }).then(r => {
      if (!r.ok) { U.toast(cmError(r, 'cm_update_failed'), 'error'); return; }
      U.toast(tr('cm_pushed', { name: item.name }), 'success');
      refreshItem(item.id);
    });
  }

  function removeItem(item) {
    U.confirmDlg({ title: tr('cm_remove_title'), body: tr('cm_remove_body', { name: item.name }), confirmLabel: tr('cm_remove'), cancelLabel: tr('cancel'), danger: true }).then(ok => {
      if (!ok) return;
      U.api('DELETE', base + 'item/' + enc(item.id)).then(r => {
        if (!r.ok && r.status !== 404) { U.toast(cmError(r, 'cm_remove_failed'), 'error'); return; }
        dropItem(item.id);
        U.toast(tr('cm_removed', { name: item.name }), 'success');
        WB.refreshMine();
      });
    });
  }

  // ── sharing ───────────────────────────────────────────────────────────────
  function shareButton(host) {
    return btn('btn btn-secondary wb-cm-share', host, U.icon('upload') + '<span>' + esc(tr('cm_share_btn')) + '</span>', () => openSharePicker());
  }

  /** Picks one of your saved widgets to share; with none saved, points to Design. */
  function openSharePicker() {
    WB.refreshMine().then(() => {
      if (WB.mineState() === 'error') { U.toast(tr('mine_error'), 'error'); return; }
      const list = WB.mine().filter(w => w.source !== 'community');
      if (!list.length) {
        U.modal((cardEl, close) => {
          cardEl.classList.add('wb-cm-picker-none');
          el('h3', 'soh-modal-title', cardEl).textContent = tr('cm_pick_none_title');
          el('p', 'wb-note', cardEl).textContent = tr('cm_pick_none_body');
          const acts = el('div', 'soh-modal-actions', cardEl);
          btn('btn btn-secondary', acts, esc(tr('cancel')), close).setAttribute('data-autofocus', '');
          btn('btn btn-primary', acts, esc(tr('mine_design')), () => { close(); WB.setMode('design'); });
        }, 'wb-modal-form');
        return;
      }
      U.modal((cardEl, close) => {
        cardEl.classList.add('wb-cm-picker');
        el('h3', 'soh-modal-title', cardEl).textContent = tr('cm_pick_title');
        el('p', 'wb-note', cardEl).textContent = tr('cm_pick_body');
        const grid = el('div', 'wb-pick-list', cardEl);
        list.forEach(w => {
          const name = (w.doc && w.doc.name) || tr('untitled');
          const b = btn('wb-pick-item', grid, '', () => { close(); WB.share(w); });
          const cv = el('canvas', 'wb-pick-preview', b);
          cv.setAttribute('aria-hidden', 'true');
          const tx = el('span', 'wb-tx', b);
          el('b', '', tx).textContent = name;
          el('small', '', tx).textContent = w.communityId ? tr('share_update') : tr('cm_publish');
          requestAnimationFrame(() => ODW.renderStill(cv, w.doc, { theme: WB.theme(), units: WB.units(), pad: 6 }));
        });
        const acts = el('div', 'soh-modal-actions', cardEl);
        btn('btn btn-secondary', acts, esc(tr('cancel')), close).setAttribute('data-autofocus', '');
      }, 'wb-modal-form');
    });
  }

  /** The likeliest category, by the signals the file reads. */
  function guessCategory(doc) {
    let names = [];
    try { names = new ODW.Widget(doc).names(); } catch (e) { names = []; }
    const score = { road: 0, tyres: 0, charging: 0, trip: 0, driving: 0, battery: 0 };
    const RULES = [
      ['road', /^hazard_/], ['tyres', /^tyre_/], ['charging', /^(is_charging|is_dcfc|charge_|charging_)/], ['trip', /^trip_/],
      ['driving', /^(speed|power|accel_pct|brake_pct|motor_|altitude_m|grade_pct|driving|reversing)$/], ['battery', /^(soc|ev_range_km|calibrated_range_km|fuel_pct|is_phev)$/],
    ];
    names.forEach(n => RULES.forEach(rl => { if (rl[1].test(n)) score[rl[0]]++; }));
    (doc && Array.isArray(doc.layers) ? doc.layers : []).forEach(l => { if (l && l.type === 'road') score.road += 3; });
    let best = 'other', top = 0;
    RULES.forEach(rl => { if (score[rl[0]] > top) { top = score[rl[0]]; best = rl[0]; } });
    return best;
  }

  function field(form, label, kind, max, value) {
    const row = el('label', 'wb-form-row', form);
    el('span', 'wb-form-label', row).textContent = label;
    const input = el(kind === 'textarea' ? 'textarea' : 'input', 'text-input wb-txt', row);
    if (kind !== 'textarea') input.type = 'text';
    else input.rows = 3;
    input.maxLength = max;
    input.value = value || '';
    input.setAttribute('dir', 'auto');
    return input;
  }
  function categoryField(form, value) {
    const row = el('label', 'wb-form-row', form);
    el('span', 'wb-form-label', row).textContent = tr('cm_field_category');
    const sel = el('select', 'wb-select wb-pick', row);
    sel.innerHTML = CATS.map(c => '<option value="' + c + '">' + esc(tr('cat_' + c)) + '</option>').join('');
    sel.value = CATS.indexOf(value) >= 0 ? value : 'other';
    return sel;
  }
  function rememberedAuthor(input) {
    const saved = U.load(AUTHOR_KEY);
    if (typeof saved === 'string' && saved) { input.value = saved; return; }
    // the name already used for sharing automations, if any
    U.api('GET', '/api/community/settings').then(r => {
      if (r.ok && typeof r.data.authorName === 'string' && r.data.authorName && !input.value) input.value = r.data.authorName.slice(0, MAX_AUTHOR);
    });
  }

  /** Shares a saved widget, or updates its shared copy: the car reads the file from its own store. */
  function openPublish(entry) {
    if (!entry || !entry.doc) return;
    const update = !!entry.communityId && entry.source !== 'community';
    if (!update) { publishForm(entry, false, null); return; }
    U.api('GET', base + 'item/' + enc(entry.communityId)).then(r => publishForm(entry, true, r.ok ? normalize(r.data.item) : null));
  }

  function publishForm(entry, update, shared) {
    const prefill = shared
      ? { name: shared.name, about: shared.description, category: shared.category }
      : { name: entry.doc.name || '', about: entry.doc.about || '', category: guessCategory(entry.doc) };
    U.modal((cardEl, close) => {
      cardEl.classList.add('wb-cm-publish');
      el('h3', 'soh-modal-title', cardEl).textContent = tr(update ? 'cm_publish_update_title' : 'cm_publish_title');
      el('p', 'wb-note', cardEl).textContent = tr('cm_publish_note');
      const form = el('div', 'wb-form', cardEl);
      const author = field(form, tr('cm_field_author'), 'text', MAX_AUTHOR, '');
      rememberedAuthor(author);
      const name = field(form, tr('cm_field_name'), 'text', ODW.MAX_NAME, prefill.name);
      const desc = field(form, tr('cm_field_desc'), 'textarea', MAX_DESC, prefill.about);
      const cat = categoryField(form, prefill.category);
      const acts = el('div', 'soh-modal-actions', cardEl);
      btn('btn btn-secondary', acts, esc(tr('cancel')), close);
      const go = btn('btn btn-primary', acts, esc(tr(update ? 'cm_publish_update' : 'cm_publish')), () => {
        const a = author.value.trim(), n = name.value.trim(), d = desc.value.trim();
        if (!a) { U.toast(tr('cm_err_author'), 'error'); author.focus(); return; }
        if (!n) { U.toast(tr('cm_err_name'), 'error'); name.focus(); return; }
        if ([a, n, d].some(ODW.hasLink) || ODW.docProblems(entry.doc).some(q => q.code === 'link' || q.why === 'link')) { U.toast(tr('cap_link'), 'error'); return; }
        go.disabled = true;
        U.api('POST', base + 'publish', { localId: entry.id, authorName: a, name: n, description: d, category: cat.value }).then(r => {
          go.disabled = false;
          if (!r.ok) { U.toast(cmError(r, 'cm_publish_failed'), 'error'); return; }
          U.store(AUTHOR_KEY, a);
          close();
          U.toast(tr(r.data.updated ? 'cm_published_update' : 'cm_published', { name: n }), 'success');
          WB.refreshMine();
          if (C.mounted) load(true);
        });
      });
    }, 'wb-modal-form');
  }

  /** Edits the shared copy's name, description and category (yours only; the car checks). */
  function editDetails(item) {
    U.modal((cardEl, close) => {
      el('h3', 'soh-modal-title', cardEl).textContent = tr('cm_edit_details');
      const form = el('div', 'wb-form', cardEl);
      const name = field(form, tr('cm_field_name'), 'text', ODW.MAX_NAME, item.name);
      const desc = field(form, tr('cm_field_desc'), 'textarea', MAX_DESC, item.description);
      const cat = categoryField(form, item.category);
      const acts = el('div', 'soh-modal-actions', cardEl);
      btn('btn btn-secondary', acts, esc(tr('cancel')), close);
      const go = btn('btn btn-primary', acts, esc(tr('save_changes')), () => {
        const n = name.value.trim(), d = desc.value.trim();
        if (!n) { U.toast(tr('cm_err_name'), 'error'); name.focus(); return; }
        if ([n, d].some(ODW.hasLink)) { U.toast(tr('cap_link'), 'error'); return; }
        go.disabled = true;
        U.api('PUT', base + 'item/' + enc(item.id), { name: n, description: d, category: cat.value }).then(r => {
          go.disabled = false;
          if (!r.ok) { U.toast(cmError(r, 'cm_update_failed'), 'error'); return; }
          close();
          U.toast(tr('cm_updated_ok'), 'success');
          refreshItem(item.id);
        });
      });
    }, 'wb-modal-form');
  }

  window.WidgetCommunity = { mount, repaint, openPublish, openSharePicker, guessCategory, dropItem, refresh: () => load(true) };
})();
