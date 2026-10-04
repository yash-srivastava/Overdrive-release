/**
 * Parking Intelligence page — list + drill-in detail, in the trips.html idiom:
 * a "parked now" hero (summary-card), quick range filters (filter-tabs), a
 * grid of square session cards grouped by day-header, and a detail view with
 * detail-header / detail-summary / summary-cards.
 *
 * Data: /api/parking/* (ParkingApiHandler). Images are fetched through
 * window.fetch (auth.js injects the JWT) and shown via object URLs, exactly
 * how events.js loads thumbnails.
 *
 * Routes (hash): ''  → list;  '#/session/<id>' → detail.
 * Old-WebView note (Chrome 58): no optional chaining, no Array.flat.
 */
(function () {
    'use strict';

    var PAGE = 24;
    var state = {
        status: null, days: 30, offset: 0, total: 0, loading: false,
        sessions: [], blobs: [], listRequest: 0
    };

    // ---------------------------------------------------------------- i18n / utils

    function t(key, vars) {
        var v = (window.BYD && BYD.i18n) ? BYD.i18n.t(key, vars) : null;
        return (v == null || v === key) ? fallback(key, vars) : v;
    }
    var FALLBACK = {
        'parking.now_parked': 'Parked now', 'parking.parked_for': 'Parked for {d}', 'parking.stat_parked_for': 'Parked for',
        'parking.no_sessions_title': 'No parking sessions yet', 'parking.no_sessions': 'The first one is created the next time the car is switched off.',
        'parking.no_sessions_range': 'No parking sessions in this range.', 'parking.disabled_title': 'Parking Intelligence is off',
        'parking.disabled_body': 'When on, every switch-off opens a parking session: where the car is, a four-camera still after you walk away, which vehicles arrived or left next to you while sentry watched, and a "Back at car" summary when you return.',
        'parking.unknown_place': 'Location unavailable', 'parking.events_n': '{n} events', 'parking.neighbours_n': '{n} neighbours',
        'parking.gps_fresh': 'GPS fresh', 'parking.gps_recent': 'GPS recent', 'parking.gps_stale': 'GPS stale · garage?', 'parking.gps_unknown': 'No GPS',
        'parking.sentry_armed': 'Sentry armed', 'parking.sentry_lock_wait': 'Waiting for lock', 'parking.sentry_pipeline_down': 'Camera off',
        'parking.sentry_suppressed_safe_zone': 'Safe zone', 'parking.sentry_suppressed_schedule': 'Outside schedule',
        'parking.sentry_surveillance_off': 'Surveillance off', 'parking.sentry_vehicle_on_only': 'Vehicle-on only', 'parking.sentry_unknown': 'Sentry unknown',
        'parking.back': 'All sessions', 'parking.started': 'Parked', 'parking.ended': 'Returned', 'parking.duration': 'Duration',
        'parking.trigger': 'Return signal', 'parking.stills': 'Stills', 'parking.arrived': 'Arrived', 'parking.returned': 'Returned',
        'parking.no_stills': 'No stills for this session', 'parking.signage': 'Garage signage',
        'parking.signage_pending': 'Not read yet — reads about two minutes after the next drive starts, or while charging.',
        'parking.signage_unavailable': 'Signage reading needs the OCR models (models/signage_*.tflite).',
        'parking.signage_skipped': 'No frames were available to read.', 'parking.signage_none': 'No level / zone / bay text found.',
        'parking.signage_confidence': 'confidence {p}%', 'parking.read_again': 'Read again', 'parking.neighbours': 'Neighbours',
        'parking.signage_state_pending': 'Pending', 'parking.signage_state_done': 'Not found', 'parking.signage_state_skipped': 'No frames',
        'parking.signage_state_unavailable': 'No models', 'status.on': 'ON', 'status.off': 'OFF',
        'parking.no_neighbours': 'No neighbouring vehicles were tracked. Sentry must be armed for this.',
        'parking.side_front': 'Front', 'parking.side_right': 'Right', 'parking.side_rear': 'Rear', 'parking.side_left': 'Left',
        'parking.status_present_on_arrival': 'Already there', 'parking.status_arrived': 'Arrived', 'parking.status_departed': 'Left',
        'parking.status_still_there': 'Still there', 'parking.status_left_unknown': 'Left (time unknown)', 'parking.status_passed': 'Passed close',
        'parking.arrived_at': 'Arrived {t}', 'parking.left_at': 'Left {t}', 'parking.last_seen': 'Last seen {t}', 'parking.clip': 'clip',
        'parking.events': 'Sentry events', 'parking.no_events': 'No sentry events during this session.', 'parking.open_events': 'Recordings',
        'parking.delete': 'Delete session', 'parking.delete_confirm': 'Delete this session, its stills and frames?', 'parking.deleted': 'Session deleted',
        'parking.delete_failed': 'Could not delete (open session?)', 'parking.enabled_toast': 'Parking Intelligence turned on',
        'parking.enable_failed': 'Could not save setting', 'parking.requeued': 'Signage read queued', 'parking.requeue_failed': 'Not running — enable Parking Intelligence first',
        'parking.trigger_door_open': 'door opened', 'parking.trigger_unlock': 'unlocked', 'parking.trigger_acc_on': 'car switched on',
        'parking.trigger_drive_away': 'drove away',
        'parking.trigger_recovered': 'recovered after restart', 'parking.trigger_superseded': 'superseded', 'parking.unconfirmed': 'unconfirmed',
        'parking.map': 'Map', 'parking.kind_close_pass': 'Close pass', 'parking.vehicle': 'Vehicle', 'parking.bike': 'Bike', 'parking.person': 'Person', 'parking.animal': 'Animal',
        'parking.status_paused': 'On · not running', 'parking.load_more': 'Load more sessions', 'parking.filter_all': 'All', 'parking.filter_days_90': '90 Days',
        'parking.enable': 'Turn on Parking Intelligence', 'parking.gps_label': 'GPS', 'parking.sentry_label': 'Sentry',
        'parking.energy_used': 'Energy used', 'parking.soc_charged': 'Charged while parked',
        'trip.filter.days_7': '7 Days', 'trip.filter.days_30': '30 Days'
    };
    function fallback(key, vars) {
        var s = FALLBACK[key] || key;
        if (vars) s = s.replace(/\{(\w+)\}/g, function (m, n) { return vars[n] != null ? vars[n] : m; });
        return s;
    }
    function esc(s) {
        return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function $(id) { return document.getElementById(id); }
    function toast(msg, type) { if (window.BYD && BYD.core && BYD.core.toast) BYD.core.toast(msg, type || 'info'); }
    function lang() { return (window.BYD && BYD.i18n && BYD.i18n.getLang) ? BYD.i18n.getLang() : undefined; }

    function fmtDur(ms) {
        var mins = Math.max(0, Math.floor(ms / 60000));
        var h = Math.floor(mins / 60), m = mins % 60;
        if (h >= 48) return Math.floor(h / 24) + 'd ' + (h % 24) + BYD.units.hourSuffix(true);
        var s = BYD.units.minuteSuffix(true, true);
        if (h > 0) return h + BYD.units.hourSuffix(true) + ' ' + (m < 10 ? '0' : '') + m + s;
        return m + s;
    }
    function fmtTime(ms) { return ms ? new Date(ms).toLocaleTimeString(lang(), { hour: '2-digit', minute: '2-digit' }) : ''; }
    function fmtDay(ms) { return new Date(ms).toLocaleDateString(lang(), { weekday: 'long', month: 'short', day: 'numeric' }); }
    function fmtDate(ms) { return new Date(ms).toLocaleDateString(lang(), { weekday: 'long', month: 'long', day: 'numeric' }); }

    function placeOf(s) {
        if (s.safeZone) return s.safeZone;
        if (s.place && (s.place.short || s.place.displayName)) return s.place.short || s.place.displayName;
        // Same fallback as the "Parked" notification: a fix without a resolved
        // address (geocoding off, or no internet in the garage) is still a
        // location — never call it unavailable while a map link exists.
        if (s.gps && s.gps.lat != null && s.gps.lng != null) return s.gps.lat.toFixed(5) + ', ' + s.gps.lng.toFixed(5);
        return null;
    }
    function levelOf(s) { return (s.signage && s.signage.found && s.signage.label) ? s.signage.label : null; }
    function gpsQuality(s) { return (s.gps && s.gps.quality) || 'UNKNOWN'; }
    function mapsUrl(s) {
        return (s.gps && s.gps.lat != null && s.gps.lng != null)
            ? 'https://maps.google.com/?q=' + s.gps.lat.toFixed(6) + ',' + s.gps.lng.toFixed(6) : null;
    }
    function triggerLabel(tr) { return tr ? t('parking.trigger_' + tr) : ''; }

    // SVG glyphs (lucide, 24-box) used by the capsules — same shapes the trips page uses.
    var ICON = {
        clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>',
        pin: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 21s-6-5.5-6-10a6 6 0 0 1 12 0c0 4.5-6 10-6 10z"/><circle cx="12" cy="11" r="2"/></svg>',
        car: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="8" rx="2"/><path d="M5 11l2-5h10l2 5"/><circle cx="7.5" cy="19" r="1.5"/><circle cx="16.5" cy="19" r="1.5"/></svg>',
        shield: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>',
        satellite: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="2"/><path d="M8.46 15.54A5 5 0 0 1 7 12a5 5 0 0 1 1.46-3.54"/><path d="M15.54 8.46A5 5 0 0 1 17 12a5 5 0 0 1-1.46 3.54"/><path d="M5.64 18.36A9 9 0 0 1 3 12a9 9 0 0 1 2.64-6.36"/><path d="M18.36 5.64A9 9 0 0 1 21 12a9 9 0 0 1-2.64 6.36"/></svg>',
        level: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="3"/><path d="M9 17V7h4a3 3 0 0 1 0 6H9"/></svg>',
        video: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg>',
        camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>',
        battery: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="16" height="10" rx="2" ry="2"/><line x1="22" y1="11" x2="22" y2="13"/></svg>',
        back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>',
        trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>',
        map: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6"/><line x1="8" y1="2" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="22"/></svg>'
    };
    function capsule(icon, text, cls) {
        return '<span class="park-capsule' + (cls ? ' ' + cls : '') + '">' + ICON[icon] + esc(text) + '</span>';
    }
    function gpsCapsule(s) {
        var q = gpsQuality(s);
        return capsule('satellite', t('parking.gps_' + q.toLowerCase()), q === 'STALE' ? 'warn' : (q === 'UNKNOWN' ? 'muted' : ''));
    }
    function sentryCapsule(s) {
        var st = s.sentryState || 'unknown';
        return capsule('shield', t('parking.sentry_' + st), st === 'armed' ? '' : (st === 'lock_wait' ? 'muted' : 'warn'));
    }
    // Energy (session.energy from ParkingSession.toJson / the live enrichment):
    //   kwh        signed net change, BMS pair first, else the SoC estimate
    //   socDelta   signed SoC change when both SoC bookends exist
    //   source     'bms' | 'soc'   (a '≈' marks the estimate)
    //   measurable above the source's noise floor — a flat reading is hidden,
    //              never shown as "0": the polled SoC gauge is whole-percent.
    function hasEnergy(s) {
        return !!(s.energy && s.energy.measurable && (s.energy.kwh != null || s.energy.socDelta != null));
    }
    function fmtEnergy(s) {
        if (!hasEnergy(s)) return null;
        var e = s.energy, parts = [];
        if (e.kwh != null) {
            // "≈" leads for the SoC-derived estimate: "≈−0.66 kWh", never "−≈0.66".
            parts.push((e.source === 'soc' ? '≈' : '') + (e.kwh > 0 ? '+' : '−') + Math.abs(e.kwh).toFixed(2) + ' kWh');
        }
        // SoC only when the gauge itself moved (whole-percent on most trims): a
        // real BMS draw with a flat SoC must not print "−0.0% SoC" beside it.
        if (e.socDelta != null && Math.abs(e.socDelta) >= 0.5) {
            parts.push((e.socDelta > 0 ? '+' : '−') + Math.abs(e.socDelta).toFixed(1) + '% SoC');
        }
        return parts.join(' · ');
    }
    function energyLabel(s) {
        return t(s.energy && s.energy.charged ? 'parking.soc_charged' : 'parking.energy_used');
    }
    function energyCapsule(s) {
        return hasEnergy(s) ? capsule('battery', fmtEnergy(s), s.energy.charged ? 'good' : '') : '';
    }
    function tag(text, cls) { return '<span class="pk-tag' + (cls ? ' ' + cls : '') + '">' + esc(text) + '</span>'; }

    // ---------------------------------------------------------------- images

    function revokeAll() {
        state.blobs.forEach(function (u) { try { URL.revokeObjectURL(u); } catch (e) { /* ignore */ } });
        state.blobs = [];
    }
    function loadImg(container, url) {
        if (!container || !url) return;
        fetch(url).then(function (r) { return r.ok ? r.blob() : null; }).then(function (blob) {
            if (!blob || !blob.size) return;
            var objUrl = URL.createObjectURL(blob);
            state.blobs.push(objUrl);
            var img = document.createElement('img');
            img.src = objUrl;
            img.alt = '';
            // Keep overlay children (the "Arrived · 08:13" pill); only the
            // placeholder text goes.
            var ph = container.querySelector('.pk-ph');
            if (ph) ph.parentNode.removeChild(ph);
            container.insertBefore(img, container.firstChild);
            container.setAttribute('data-full', objUrl);
            container.style.display = '';
        }).catch(function () { /* placeholder stays */ });
    }
    function hydrateImages(root) {
        var nodes = root.querySelectorAll('[data-img]');
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            loadImg(el, el.getAttribute('data-img'));
            el.removeAttribute('data-img');
        }
        var boxes = root.querySelectorAll('[data-lightbox]');
        for (var j = 0; j < boxes.length; j++) {
            boxes[j].addEventListener('click', function (ev) {
                ev.stopPropagation();
                var full = ev.currentTarget.getAttribute('data-full');
                if (!full) return;
                $('pkLightboxImg').src = full;
                $('pkLightbox').classList.add('open');
            });
        }
    }

    // ---------------------------------------------------------------- header / hero

    function renderHeader() {
        var st = state.status;
        var badge = $('pkHeaderBadge'), text = $('pkHeaderStatus');
        if (!st || !badge) return;
        text.textContent = !st.enabled ? t('status.off') : (st.running ? t('status.on') : t('parking.status_paused'));
        badge.style.color = st.enabled && st.running ? 'var(--brand-primary)' : (st.enabled ? 'var(--warning)' : '');
    }

    function renderHero(cur) {
        var hero = $('pkHero');
        if (!hero) return;
        if (!cur) { hero.style.display = 'none'; return; }
        hero.style.display = '';
        var place = placeOf(cur) || t('parking.unknown_place'), level = levelOf(cur);
        $('pkHeroPlace').innerHTML = esc(place) + (level ? ' <span class="pk-level">· ' + esc(level) + '</span>' : '');
        $('pkHeroSub').textContent = fmtDay(cur.startedMs) + ' · ' + fmtTime(cur.startedMs)
            + ' · ' + t('parking.gps_' + gpsQuality(cur).toLowerCase()) + ' · ' + t('parking.sentry_' + (cur.sentryState || 'unknown'))
            + (hasEnergy(cur) ? ' · ' + fmtEnergy(cur) : '');
        $('pkHeroTag').textContent = 'LIVE';
        $('pkHeroDuration').textContent = fmtDur(Date.now() - cur.startedMs);
        $('pkHeroEvents').textContent = String(cur.eventCount || 0);
        $('pkHeroNeighbours').textContent = String(cur.neighbourCount || 0);
        var thumb = $('pkHeroThumb');
        thumb.style.display = 'none';
        thumb.innerHTML = '';
        if (cur.snapshots && cur.snapshots.arrivedOk) loadImg(thumb, '/parking/asset/' + encodeURIComponent(cur.sessionId) + '/arrived_mosaic.jpg');
        hero.onclick = function () { location.hash = '#/session/' + cur.sessionId; };
        hero.style.cursor = 'pointer';
    }

    // ---------------------------------------------------------------- list

    function sessionCard(s, isCurrent) {
        var card = document.createElement('div');
        card.className = 'park-card' + (isCurrent ? ' current' : '');
        card.onclick = function () { location.hash = '#/session/' + s.sessionId; };
        var now = Date.now();
        var place = placeOf(s) || t('parking.unknown_place'), level = levelOf(s);
        var dur = fmtDur((s.endedMs || now) - s.startedMs);
        var events = s.eventCount || 0;
        var badgeCls = events === 0 ? 'none' : (events >= 5 ? 'high' : (events >= 2 ? 'mid' : ''));
        var badge = '<div class="park-badge ' + badgeCls + '" title="' + esc(t('parking.events_n', { n: events })) + '">'
            + (events === 0 ? ICON.shield : events) + '</div>';
        var capsules = capsule('clock', dur)
            + energyCapsule(s)
            + (level ? capsule('level', level) : '')
            + capsule('car', t('parking.neighbours_n', { n: s.neighbourCount || 0 }), (s.neighbourCount || 0) === 0 ? 'muted' : '')
            + gpsCapsule(s)
            + sentryCapsule(s);
        card.innerHTML =
            '<div class="park-card-top">' +
                '<div style="display:flex;align-items:center;flex-wrap:wrap;padding-right:48px;">' +
                    '<span class="park-time">' + esc(fmtTime(s.startedMs)) + '</span>' +
                    (isCurrent ? '<span class="park-live-tag">LIVE</span>' : '') +
                '</div>' +
            '</div>' +
            '<div class="park-place" title="' + esc(place) + '">' + esc(place) + '</div>' +
            badge +
            '<div class="park-capsules">' + capsules + '</div>';
        return card;
    }

    function renderList(sessions, append) {
        var box = $('pkSessions');
        if (!append) box.innerHTML = '';
        var current = state.status && state.status.current ? state.status.current : null;
        var lastDay = append ? box.getAttribute('data-last-day') : null;
        sessions.forEach(function (s) {
            var day = fmtDay(s.startedMs);
            if (day !== lastDay) {
                var h = document.createElement('div');
                h.className = 'day-header';
                h.textContent = day;
                box.appendChild(h);
                lastDay = day;
            }
            var isCur = !!(current && current.sessionId === s.sessionId);
            // The status copy of the open session carries the live energy
            // enrichment the plain list row lacks — render the card from it.
            box.appendChild(sessionCard(isCur ? current : s, isCur));
        });
        box.setAttribute('data-last-day', lastDay || '');
    }

    function showEmpty(kind) {
        var empty = $('pkEmptyState'), enableBtn = $('pkEnableFromEmpty');
        var disabled = state.status && !state.status.enabled;
        $('pkEmptyTitle').textContent = disabled ? t('parking.disabled_title') : t('parking.no_sessions_title');
        $('pkEmptyText').textContent = disabled ? t('parking.disabled_body')
            : (kind === 'range' ? t('parking.no_sessions_range') : t('parking.no_sessions'));
        enableBtn.style.display = disabled ? '' : 'none';
        empty.style.display = 'flex';
    }

    function rangeFrom() {
        return state.days > 0 ? Date.now() - state.days * 86400000 : 0;
    }

    function loadSessions(append) {
        if (state.loading) return;
        state.loading = true;
        var req = ++state.listRequest;
        var box = $('pkSessions'), empty = $('pkEmptyState'), more = $('pkMoreBtn');
        if (!append) {
            state.offset = 0;
            state.sessions = [];
            box.innerHTML = '<div class="skeleton skeleton-card"></div><div class="skeleton skeleton-card"></div><div class="skeleton skeleton-card"></div>';
            empty.style.display = 'none';
            more.style.display = 'none';
        }
        var url = '/api/parking/sessions?limit=' + PAGE + '&offset=' + state.offset;
        var from = rangeFrom();
        if (from > 0) url += '&from=' + from;
        fetch(url, { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (req !== state.listRequest) return;      // a newer filter owns the list
                state.loading = false;
                var list = (d && d.sessions) || [];
                state.total = d.total || 0;
                state.sessions = state.sessions.concat(list);
                renderList(list, append);
                state.offset += list.length;
                if (!append && list.length === 0) {
                    box.innerHTML = '';
                    showEmpty(from > 0 && state.total > 0 ? 'range' : 'none');
                } else {
                    empty.style.display = 'none';
                }
                // The range filter narrows client-visible rows; keep paging while the
                // last page was full (the server total counts ALL sessions).
                more.style.display = (list.length === PAGE) ? 'block' : 'none';
            })
            .catch(function () {
                if (req !== state.listRequest) return;
                state.loading = false;
                box.innerHTML = '';
                showEmpty('none');
            });
    }

    function showList() {
        $('pkListView').classList.remove('hidden');
        var detail = $('pkDetailView');
        detail.classList.remove('active');
        detail.innerHTML = '';
        revokeAll();
        fetch('/api/parking/status', { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (st) {
            state.status = st;
            renderHeader();
            renderHero(st.current || null);
            if (window.ParkingSettings) ParkingSettings.paint(st.config, st);
            loadSessions(false);
        }).catch(function () {
            renderHero(null);
            loadSessions(false);
        });
    }

    // ---------------------------------------------------------------- detail

    function showDetail(id) {
        $('pkListView').classList.add('hidden');
        var view = $('pkDetailView');
        view.classList.add('active');
        view.innerHTML = '<div class="skeleton skeleton-card" style="height:120px;"></div>';
        revokeAll();
        try { window.scrollTo(0, 0); } catch (e) { /* ignore */ }
        fetch('/api/parking/sessions/' + encodeURIComponent(id), { cache: 'no-store' })
            .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
            .then(function (d) { renderDetail(d); })
            .catch(function () { view.innerHTML = detailHeader(null) + '<div class="empty-state"><div class="empty-title">' + esc(t('parking.no_sessions_title')) + '</div></div>'; hydrateImages(view); });
        if (!state.status) {
            fetch('/api/parking/status', { cache: 'no-store' }).then(function (r) { return r.json(); })
                .then(function (st) { state.status = st; renderHeader(); }).catch(function () { /* ignore */ });
        }
    }

    function detailHeader(s) {
        var actions = '';
        if (s) {
            var maps = mapsUrl(s);
            if (maps) actions += '<a class="detail-back-btn" target="_blank" rel="noopener" href="' + esc(maps) + '">' + ICON.map + esc(t('parking.map')) + '</a>';
            actions += '<a class="detail-back-btn" href="events.html?filter=sentry&parkingSessionId=' + encodeURIComponent(s.sessionId) + '">' + ICON.video + esc(t('parking.open_events')) + '</a>';
        }
        return '<div class="detail-header">' +
            '<a class="detail-back-btn" href="#/">' + ICON.back + esc(t('parking.back')) + '</a>' +
            '<div class="detail-actions">' + actions + '</div>' +
        '</div>';
    }

    function stat(value, label, small) {
        return '<div class="detail-stat"><div class="detail-stat-value' + (small ? ' small' : '') + '">' + esc(value) + '</div><div class="detail-stat-label">' + esc(label) + '</div></div>';
    }

    function renderDetail(d) {
        var s = d.session, view = $('pkDetailView');
        var now = Date.now();
        var place = placeOf(s) || t('parking.unknown_place'), level = levelOf(s);
        var html = detailHeader(s) +
            '<div class="detail-summary">' +
                '<div class="detail-title">' + esc(place) + (level ? ' <span class="pk-level">· ' + esc(level) + '</span>' : '') + '</div>' +
                '<div class="detail-subtitle">' + esc(fmtDate(s.startedMs)) + ' · ' + esc(fmtTime(s.startedMs)) +
                    (s.endedMs ? ' – ' + esc(fmtTime(s.endedMs)) : ' · ' + esc(t('parking.now_parked'))) + '</div>' +
                '<div class="detail-summary-grid">' +
                    stat(fmtDur((s.endedMs || now) - s.startedMs), t('parking.duration')) +
                    (hasEnergy(s) ? stat(fmtEnergy(s), energyLabel(s), true) : '') +
                    stat(String(s.eventCount || 0), t('parking.events')) +
                    stat(String((d.neighbours || []).length), t('parking.neighbours')) +
                    stat(t('parking.gps_' + gpsQuality(s).toLowerCase()), t('parking.gps_label'), true) +
                    stat(t('parking.sentry_' + (s.sentryState || 'unknown')), t('parking.sentry_label'), true) +
                    (s.endTrigger ? stat(triggerLabel(s.endTrigger), t('parking.trigger'), true) : '') +
                '</div>' +
            '</div>' +
            stillsCard(s, d.assets || {}) +
            signageCard(s) +
            neighboursCard(s, d.neighbours || []) +
            eventsCard(d.events || []) +
            (!s.open ? '<button class="detail-delete-btn" id="pkDeleteBtn">' + ICON.trash + esc(t('parking.delete')) + '</button>' : '');
        view.innerHTML = html;
        hydrateImages(view);
        var del = $('pkDeleteBtn');
        if (del) del.addEventListener('click', function () { deleteSession(s.sessionId); });
        var rr = $('pkReadAgainBtn');
        if (rr) rr.addEventListener('click', function () { requeueSignage(s.sessionId); });
    }

    function cardShell(title, icon, body, right) {
        return '<div class="summary-card"><div class="pk-card-title"><div class="card-title">' + ICON[icon] + '<span>' + esc(title) + '</span></div>' +
            (right || '') + '</div>' + body + '</div>';
    }

    function stillsCard(s, assets) {
        var groups = ['arrived', 'returned'].filter(function (p) { return assets[p]; });
        var body;
        if (!groups.length) {
            body = '<div class="pk-muted">' + esc(t('parking.no_stills')) + '</div>';
        } else {
            body = '<div class="pk-stills">' + groups.map(function (p) {
                var a = assets[p];
                var main = a.mosaic || a.front || a.right || a.rear || a.left;
                var when = p === 'arrived' ? (s.snapshots && s.snapshots.arrivedMs) : (s.snapshots && s.snapshots.returnedMs);
                var tiles = ['front', 'right', 'rear', 'left'].filter(function (k) { return a[k]; }).map(function (k) {
                    return '<div class="pk-tile" data-lightbox data-img="' + esc(a[k]) + '" title="' + esc(t('parking.side_' + k)) + '"></div>';
                }).join('');
                return '<div><div class="pk-still" data-lightbox data-img="' + esc(main) + '">' +
                        '<span class="pk-still-label">' + esc(t('parking.' + p)) + (when ? ' · ' + esc(fmtTime(when)) : '') + '</span></div>' +
                        (tiles ? '<div class="pk-tiles">' + tiles + '</div>' : '') + '</div>';
            }).join('') + '</div>';
        }
        return cardShell(t('parking.stills'), 'camera', body);
    }

    function signageCard(s) {
        var st = s.signageState || 'pending';
        var found = st === 'done' && s.signage && s.signage.found;
        var body;
        if (found) {
            var ev = (s.signage.evidence || []).slice(0, 4).map(function (e) { return '“' + esc(e.text) + '”'; }).join(', ');
            body = '<div class="pk-signage-value">' + esc(s.signage.label) + '</div>' +
                   '<div class="pk-muted">' + esc(t('parking.signage_confidence', { p: Math.round((s.signage.confidence || 0) * 100) })) + '</div>' +
                   (ev ? '<div class="pk-signage-evidence">' + ev + '</div>' : '');
        } else {
            body = '<div class="pk-muted">' + esc(t('parking.signage_' + (st === 'done' ? 'none' : st))) + '</div>';
        }
        var canRead = state.status && state.status.running && state.status.signageModels;
        if (canRead) body += '<button class="load-more-btn" id="pkReadAgainBtn" style="margin-top:12px;">' + esc(t('parking.read_again')) + '</button>';
        var right = tag(found ? s.signage.label : t('parking.signage_state_' + st), found ? 'good' : '');
        return cardShell(t('parking.signage'), 'level', body, right);
    }

    function classLabel(g) {
        switch ((g || '').toUpperCase()) {
            case 'VEHICLE': return t('parking.vehicle');
            case 'BIKE': return t('parking.bike');
            case 'PERSON': return t('parking.person');
            case 'ANIMAL': return t('parking.animal');
            default: return g || '';
        }
    }
    function clipLink(name) {
        return name ? ' · <a href="events.html?file=' + encodeURIComponent(name) + '">' + esc(t('parking.clip')) + '</a>' : '';
    }
    function neighbourRow(n, sessionId) {
        var status = (n.status || '').toLowerCase();
        var cls = (status === 'departed' || status === 'left_unknown') ? 'warn' : (status === 'arrived' ? 'good' : '');
        var times = [];
        if (n.arrivedMs) times.push(esc(t('parking.arrived_at', { t: fmtTime(n.arrivedMs) })) + clipLink(n.arrivalEvent));
        if (n.departedMs) times.push(esc(t('parking.left_at', { t: fmtTime(n.departedMs) })) + clipLink(n.departureEvent));
        if (!n.arrivedMs && !n.departedMs && n.lastSeenMs) times.push(esc(t('parking.last_seen', { t: fmtTime(n.lastSeenMs) })) + clipLink(n.arrivalEvent));
        var frames = (n.frames || []).map(function (f) {
            return '<div class="pk-frame" data-lightbox data-img="/parking/asset/' + encodeURIComponent(sessionId) + '/' + encodeURIComponent(f.name) + '" title="' + esc(f.side || '') + '"></div>';
        }).join('');
        var kind = n.kind === 'CLOSE_PASS' ? t('parking.kind_close_pass') : classLabel(n.classGroup);
        return '<div class="pk-neigh">' +
            '<div class="pk-neigh-head"><span class="pk-neigh-kind">' + esc(kind) + (n.confirmed === false ? ' ' + tag(t('parking.unconfirmed')) : '') + '</span>' +
            tag(t('parking.status_' + status), cls) + '</div>' +
            (times.length ? '<div class="pk-neigh-times">' + times.join('<br>') + '</div>' : '') +
            (frames ? '<div class="pk-frames">' + frames + '</div>' : '') +
        '</div>';
    }
    function neighboursCard(s, list) {
        var body;
        if (!list.length) {
            body = '<div class="pk-muted">' + esc(t('parking.no_neighbours')) + '</div>';
        } else {
            body = '<div class="pk-lanes">' + ['front', 'right', 'rear', 'left'].map(function (side, idx) {
                var rows = list.filter(function (n) { return n.side === idx; });
                if (!rows.length) return '';
                rows.sort(function (a, b) { return (a.firstSeenMs || 0) - (b.firstSeenMs || 0); });
                return '<div class="pk-lane"><div class="pk-lane-title"><span>' + esc(t('parking.side_' + side)) + '</span><span class="n">' + rows.length + '</span></div>' +
                    rows.map(function (n) { return neighbourRow(n, s.sessionId); }).join('') + '</div>';
            }).join('') + '</div>';
        }
        return cardShell(t('parking.neighbours'), 'car', body, list.length ? tag(String(list.length), 'good') : '');
    }

    function eventsCard(events) {
        var body;
        if (!events.length) {
            body = '<div class="pk-muted">' + esc(t('parking.no_events')) + '</div>';
        } else {
            body = events.map(function (r) {
                var thumb = r.heroThumbnailUrl || r.thumbnailUrl || '';
                var sev = r.peakSeverity ? tag(r.peakSeverity, r.peakSeverity === 'CRITICAL' ? 'bad' : 'warn') : '';
                var counts = [];
                if (r.personCount) counts.push('👤 ' + r.personCount);
                if (r.vehicleCount) counts.push('🚗 ' + r.vehicleCount);
                if (r.bikeCount) counts.push('🚲 ' + r.bikeCount);
                if (r.animalCount) counts.push('🐾 ' + r.animalCount);
                var cams = r.cameras ? String(r.cameras).split(',').map(function (c) { return t('parking.side_' + c); }).join(' · ') : '';
                return '<a class="pk-event" href="events.html?file=' + encodeURIComponent(r.filename || '') + '">' +
                    '<div class="pk-ev-thumb"' + (thumb ? ' data-img="' + esc(thumb) + '"' : '') + '></div>' +
                    '<div class="pk-ev-main"><div class="pk-ev-title">' + esc(fmtTime(r.timestamp)) + sev + '</div>' +
                    '<div class="pk-ev-sub">' + esc(counts.join('  ')) + (cams ? (counts.length ? ' · ' : '') + esc(cams) : '') + '</div></div></a>';
            }).join('');
        }
        return cardShell(t('parking.events'), 'video', body, events.length ? tag(String(events.length), 'good') : '');
    }

    // ---------------------------------------------------------------- actions

    function deleteSession(id) {
        if (!confirm(t('parking.delete_confirm'))) return;
        fetch('/api/parking/sessions/' + encodeURIComponent(id), { method: 'DELETE' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (d && d.success) { toast(t('parking.deleted'), 'success'); location.hash = '#/'; }
                else toast(t('parking.delete_failed'), 'error');
            }).catch(function () { toast(t('parking.delete_failed'), 'error'); });
    }
    function requeueSignage(id) {
        fetch('/api/parking/sessions/' + encodeURIComponent(id) + '/signage', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (d) { toast(d && d.success ? t('parking.requeued') : t('parking.requeue_failed'), d && d.success ? 'success' : 'error'); })
            .catch(function () { toast(t('parking.requeue_failed'), 'error'); });
    }
    function selectTab(id) {
        var btn = document.querySelector('.bottom-tabs [data-tab-target="' + id + '"]');
        if (btn) btn.click();
    }

    // ---------------------------------------------------------------- routing

    function route() {
        var m = /^#\/session\/([A-Za-z0-9_\-]+)/.exec(location.hash || '');
        if (m) showDetail(m[1]); else showList();
    }

    window.Parking = {
        init: function () {
            window.addEventListener('hashchange', route);
            // Switching bottom tabs while in the detail drill-in returns to the list.
            document.addEventListener('ot-tabs:active-changed', function () {
                if ($('pkDetailView').classList.contains('active')) location.hash = '#/';
            });
            if (window.ParkingSettings) {
                ParkingSettings.onSaved = function (cfg) {
                    if (!state.status) state.status = {};
                    state.status.enabled = !!cfg.enabled;
                    state.status.config = cfg;
                    renderHeader();
                    // `running` flips asynchronously in the daemon; re-read shortly.
                    setTimeout(function () {
                        fetch('/api/parking/status', { cache: 'no-store' }).then(function (r) { return r.json(); })
                            .then(function (st) { state.status = st; renderHeader(); if ($('pkEmptyState').style.display !== 'none') showEmpty('none'); })
                            .catch(function () { /* ignore */ });
                    }, 600);
                };
            }
            route();
            // Keep the hero fresh on a page left open (duration ticking, live
            // SoC while charging). List view only: the detail view re-fetches
            // on navigation, and re-rendering it would churn its blob images.
            setInterval(function () {
                if (document.hidden) return;
                if (/^#\/session\//.test(location.hash || '')) return;
                fetch('/api/parking/status', { cache: 'no-store' })
                    .then(function (r) { return r.json(); })
                    .then(function (st) { state.status = st; renderHeader(); renderHero(st.current || null); })
                    .catch(function () { /* server sleeps while parked (onOnly) — keep the last render */ });
            }, 45000);
        },
        quickFilter: function (days, btn) {
            state.days = days;
            var tabs = document.querySelectorAll('.filter-tab');
            for (var i = 0; i < tabs.length; i++) tabs[i].classList.remove('active');
            if (btn) btn.classList.add('active');
            loadSessions(false);
        },
        loadMore: function () { loadSessions(true); },
        enableFromEmptyState: function () {
            if (window.ParkingSettings) {
                ParkingSettings.save('enabled', true).then(function () { selectTab('settings'); showEmpty('none'); });
            }
        },
        closeLightbox: function () { $('pkLightbox').classList.remove('open'); }
    };
})();
