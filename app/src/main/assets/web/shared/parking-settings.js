/**
 * Parking Intelligence settings card (surveillance.html → General tab).
 * Reads /api/parking/status, writes single keys via POST /api/parking/config.
 * The daemon's ParkingController reconciles live on each write.
 */
(function () {
    'use strict';

    function $(id) { return document.getElementById(id); }
    function t(key) {
        var v = (window.BYD && BYD.i18n) ? BYD.i18n.t(key) : null;
        return (v == null || v === key) ? key : v;
    }
    function toast(msg, type) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, type); }

    function paint(cfg, status) {
        if (!cfg) return;
        setChecked('parkingEnabled', !!cfg.enabled);
        setChecked('parkingSnapshots', cfg.snapshots !== false);
        setChecked('parkingNeighbours', cfg.neighbours !== false);
        setChecked('parkingSignage', cfg.signage !== false);
        var rd = $('parkingRetentionDays'); if (rd) rd.value = cfg.retentionDays || 90;
        var cap = $('parkingStorageCapMb'); if (cap) cap.value = cfg.storageCapMb || 300;
        var et = $('parkingEndTrigger'); if (et) et.value = cfg.endTrigger || 'return';
        var sub = $('parkingSubSettings'); if (sub) sub.style.opacity = cfg.enabled ? '1' : '.55';
        var badge = $('parkingBadge');
        if (badge) {
            badge.textContent = cfg.enabled ? t('status.on') : t('status.off');
            badge.className = 'status-badge ' + (cfg.enabled ? 'active' : 'inactive');
        }
        var note = $('parkingModelsNote');
        if (note && status) {
            note.textContent = status.signageModels ? t('parking.settings_models_present') : t('parking.settings_models_missing');
            note.style.display = '';
            // Same treatment as the RoadSense hints: the row it explains sits above it.
            note.style.marginTop = '8px';
        }
    }

    // ---- Address lookup (geocoding.parking flow) ----
    // The daemon inherits the sentry/dashcam choice until the user sets this
    // card's toggles; the API reports that as `inherited: true`.
    var geo = { enabled: false, allowOnline: false, inherited: true };
    // Resolves true once `geo` holds the server's state; saves merge onto it.
    var geoLoad = Promise.resolve(false);

    function paintGeocoding(p) {
        if (!p) return;
        geo = { enabled: !!p.enabled, allowOnline: !!p.allowOnline, inherited: !!p.inherited };
        setChecked('parkingGeocodingEnabled', geo.enabled);
        setChecked('parkingGeocodingOnline', geo.allowOnline);
        var online = $('parkingGeocodingOnline'); if (online) online.disabled = !geo.enabled;
        var hint = $('parkingGeocodingNote');
        if (hint) {
            hint.textContent = geo.inherited ? t('parking.settings_geocoding_inherited') : '';
            hint.style.display = geo.inherited ? '' : 'none';
            hint.style.marginTop = '8px';
        }
    }

    function loadGeocoding() {
        if (!$('parkingGeocodingEnabled')) return Promise.resolve(false);
        geoLoad = fetch('/api/settings/geocoding', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (!d || !d.parking) return false;
                paintGeocoding(d.parking);
                return true;
            })
            .catch(function () { return false; /* card keeps its defaults */ });
        return geoLoad;
    }

    // Flip-reflow-restore so the Chrome 58 WebView repaints the slider
    // (same trick notifications.html uses for its Telegram toggles).
    function setChecked(id, want) {
        var el = $(id); if (!el) return;
        var slider = el.parentNode && el.parentNode.querySelector('.toggle-slider');
        el.checked = !want;
        if (slider) { void slider.offsetHeight; }
        el.checked = want;
    }

    var reconnectBound = false, reconnectDeferred = false;
    function onReconnect() {
        // Hidden: on the next return to the page, once.
        if (document.hidden) {
            if (reconnectDeferred) return;
            reconnectDeferred = true;
            document.addEventListener('visibilitychange', function shown() {
                if (document.hidden) return;
                document.removeEventListener('visibilitychange', shown);
                reconnectDeferred = false;
                onReconnect();
            });
            return;
        }
        // A number being typed keeps its value; its change event saves it.
        var focused = document.activeElement;
        if (focused && /^parking/.test(focused.id || '')) return;
        ParkingSettings.init();
    }

    var ParkingSettings = {
        /** Optional page hook: called with the saved config after every successful write. */
        onSaved: null,
        paint: paint,
        /** Pages that fetch /api/parking/status themselves call this instead of init(). */
        loadGeocoding: loadGeocoding,
        init: function () {
            if (!$('parkingEnabled')) return Promise.resolve();
            if (!reconnectBound && window.BYD && BYD.offline && typeof BYD.offline.onReconnect === 'function') {
                reconnectBound = true;
                BYD.offline.onReconnect(onReconnect);
            }
            loadGeocoding();
            return fetch('/api/parking/status', { cache: 'no-store' })
                .then(function (r) { return r.json(); })
                .then(function (st) { paint(st.config, st); })
                .catch(function () { /* card keeps its defaults */ });
        },
        /**
         * Address lookup toggles. Both fields are always sent so the first
         * explicit save carries the inherited allowOnline value over instead of
         * silently resetting it to off.
         */
        saveGeocoding: function (key, value) {
            return geoLoad.then(function (ok) { return ok || loadGeocoding(); }).then(function (ok) {
                if (!ok) {
                    toast(t('parking.enable_failed'), 'error');
                    setChecked('parkingGeocodingEnabled', geo.enabled);
                    setChecked('parkingGeocodingOnline', geo.allowOnline);
                    return;
                }
                var next = { enabled: geo.enabled, allowOnline: geo.allowOnline };
                next[key] = !!value;
                if (!next.enabled) next.allowOnline = false;
                return fetch('/api/settings/geocoding', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ parking: next })
                }).then(function (r) { return r.json(); }).then(function (d) {
                    if (d && d.success && d.parking) {
                        paintGeocoding(d.parking);
                        toast(t('parking.settings_saved'), 'success');
                    } else {
                        toast(t('parking.enable_failed'), 'error');
                        loadGeocoding();
                    }
                }).catch(function () {
                    toast(t('parking.enable_failed'), 'error');
                    loadGeocoding();
                });
            });
        },
        save: function (key, value) {
            var body = {}; body[key] = value;
            return fetch('/api/parking/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }).then(function (r) { return r.json(); }).then(function (d) {
                if (d && d.success) {
                    paint(d.config, null);
                    toast(t('parking.settings_saved'), 'success');
                    if (typeof ParkingSettings.onSaved === 'function') {
                        try { ParkingSettings.onSaved(d.config); } catch (e) { /* page hook must not break saves */ }
                    }
                } else {
                    toast(t('parking.enable_failed'), 'error');
                    ParkingSettings.init();
                }
            }).catch(function () {
                toast(t('parking.enable_failed'), 'error');
                ParkingSettings.init();
            });
        }
    };
    window.ParkingSettings = ParkingSettings;
})();
