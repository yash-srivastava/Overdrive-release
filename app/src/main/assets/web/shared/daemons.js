/*
 * Helpers for the Daemons status page.
 * Reads GET /api/daemons (read-only; see DaemonsApiHandler). ES5 only: the head-unit
 * browser floor is Chrome 58.
 */
(function () {
    'use strict';
    var BYD = window.BYD = window.BYD || {};

    function t(key, fallback, vars) {
        var v = null;
        try { v = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key, vars) : null; } catch (e) { v = null; }
        if (v && v !== key) return v;
        if (vars) Object.keys(vars).forEach(function (n) { fallback = fallback.split('{' + n + '}').join(vars[n]); });
        return fallback;
    }

    function load() {
        return fetch('/api/daemons', { credentials: 'same-origin', cache: 'no-store' }).then(function (r) {
            if (r.status === 401) { window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname); throw new Error('Unauthorized'); }
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        });
    }

    /** running | user_stopped | starting | stopped | unknown */
    function stateOf(d) {
        if (d.running === true) return 'running';
        if (d.running === null || d.running === undefined) return 'unknown';
        if (d.userStopped) return 'user_stopped';
        return d.starting ? 'starting' : 'stopped';
    }

    var WG_STATES = {
        connected: ['daemons.tunnel_wg_connected', 'Connected'],
        connecting: ['daemons.tunnel_wg_connecting', 'Connecting…'],
        reloading: ['daemons.tunnel_wg_connecting', 'Connecting…'],
        error: ['daemons.tunnel_wg_error', 'Connection error']
    };

    /** Status line under a tunnel; '' when the URL says it all. */
    function tunnelNote(tun, urlsHidden) {
        if (!tun.running) return t('daemons.tunnel_reconnecting', 'Reconnecting…');
        if (tun.type === 'wireguard') {
            var s = WG_STATES[tun.state];
            return s ? t(s[0], s[1]) : '';
        }
        if (tun.url) return '';
        return urlsHidden
            ? t('daemons.tunnel_url_hidden', 'Address hidden while public streaming is on')
            : t('daemons.tunnel_waiting_url', 'Waiting for the public address…');
    }

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text !== undefined) e.textContent = text;
        return e;
    }

    BYD.daemons = { t: t, load: load, stateOf: stateOf, tunnelNote: tunnelNote, el: el };
})();
