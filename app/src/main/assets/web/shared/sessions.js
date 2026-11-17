/*
 * Signed-in browsers page: GET /api/auth/sessions, POST .../revoke {id}, POST .../revoke-others.
 * ES5 only, like the other settings pages.
 */
(function () {
    'use strict';
    var BYD = window.BYD = window.BYD || {};

    function t(key, fallback, vars) {
        var v = null;
        try { v = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key, vars) : null; } catch (e) { v = null; }
        if (!v || v === key) v = fallback;
        if (vars) Object.keys(vars).forEach(function (n) { v = v.split('{' + n + '}').join(vars[n]); });
        return v;
    }

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text !== undefined) e.textContent = text;
        return e;
    }

    function when(sec) {
        if (!sec) return '—';
        try {
            return new Date(sec * 1000).toLocaleString(undefined, {
                day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
            });
        } catch (e) {
            return new Date(sec * 1000).toLocaleString();
        }
    }

    // Same wording as the server's AuthSessions.labelFor, for this browser before it is listed.
    function labelFor(ua) {
        ua = ua || '';
        var browser = /Edg\//.test(ua) ? 'Edge'
            : /OPR\//.test(ua) ? 'Opera'
            : /SamsungBrowser\//.test(ua) ? 'Samsung Internet'
            : /Firefox\/|FxiOS\//.test(ua) ? 'Firefox'
            : /CriOS\/|Chrome\//.test(ua) ? 'Chrome'
            : /Safari\//.test(ua) ? 'Safari' : '';
        var os = /iPhone/.test(ua) ? 'iPhone'
            : /iPad/.test(ua) ? 'iPad'
            : /Android/.test(ua) ? 'Android'
            : /Windows/.test(ua) ? 'Windows'
            : /Mac OS X/.test(ua) ? 'Mac'
            : /CrOS/.test(ua) ? 'ChromeOS'
            : /Linux/.test(ua) ? 'Linux' : '';
        return browser && os ? browser + ' · ' + os : (browser || os);
    }

    function call(path, method, payload) {
        var options = { method: method, cache: 'no-store', headers: {} };
        if (payload) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(payload);
        }
        return BYDAuth.fetch(path, options).then(function (r) {
            return r.json().then(function (data) {
                if (!r.ok || !data || data.success !== true) throw new Error('HTTP ' + r.status);
                return data;
            });
        });
    }

    function toast(message, kind) {
        if (BYD.utils && BYD.utils.toast) BYD.utils.toast(message, kind);
    }

    function datesLine(s) {
        return t('sessions.last_seen', 'Last used {date}', { date: when(s.lastSeenAt) }) + ' · '
            + t('sessions.created', 'Signed in {date}', { date: when(s.createdAt) });
    }

    var others = [];

    function renderCurrent(current) {
        var name = document.getElementById('currentName');
        var desc = document.getElementById('currentDesc');
        var action = document.getElementById('currentAction');
        action.hidden = false;
        action.disabled = false;
        if (current) {
            name.textContent = current.label || labelFor(navigator.userAgent) || t('sessions.unknown_device', 'Browser');
            desc.textContent = t('sessions.created', 'Signed in {date}', { date: when(current.createdAt) }) + ' · '
                + t('sessions.expires', 'Expires {date}', { date: when(current.expiresAt) });
            action.textContent = t('sessions.sign_out', 'Sign out');
            action.onclick = function () { signOut(current, action); };
        } else {
            name.textContent = labelFor(navigator.userAgent) || t('sessions.unknown_device', 'Browser');
            desc.textContent = t('sessions.untracked_desc',
                "Signed in before this update, so it can't be managed here yet. Sign in again to add it.");
            action.textContent = t('sessions.sign_in_again', 'Sign in again');
            action.onclick = function () { BYDAuth.logout(); };
        }
    }

    function renderOthers() {
        var list = document.getElementById('sessionList');
        list.textContent = '';
        document.getElementById('othersCount').textContent = String(others.length);
        document.getElementById('emptyNote').style.display = others.length > 0 ? 'none' : '';
        document.getElementById('signOutOthersRow').style.display = others.length === 0 ? 'none' : '';
        document.getElementById('signOutOthersBtn').disabled = false;
        others.forEach(function (s) {
            var row = el('div', 'setting-row');
            var info = el('div', 'setting-info');
            info.appendChild(el('div', 'setting-name', s.label || t('sessions.unknown_device', 'Browser')));
            info.appendChild(el('div', 'setting-desc', datesLine(s)));
            row.appendChild(info);
            var control = el('div', 'setting-control');
            var button = el('button', 'btn btn-secondary', t('sessions.sign_out', 'Sign out'));
            button.type = 'button';
            button.addEventListener('click', function () { signOut(s, button); });
            control.appendChild(button);
            row.appendChild(control);
            list.appendChild(row);
        });
    }

    function render(data) {
        document.getElementById('loadError').style.display = 'none';
        var rows = data.sessions || [];
        var current = null;
        others = [];
        rows.forEach(function (s) { if (s.current) current = s; else others.push(s); });
        others.sort(function (a, b) { return (b.lastSeenAt || 0) - (a.lastSeenAt || 0); });
        renderCurrent(current);
        renderOthers();
    }

    function refresh() {
        return call('/api/auth/sessions', 'GET').then(render).catch(function () {
            document.getElementById('loadError').style.display = '';
        });
    }

    function signOut(session, button) {
        if (!window.confirm(t('sessions.confirm_sign_out', 'Sign out this browser?'))) return;
        button.disabled = true;
        call('/api/auth/sessions/revoke', 'POST', { id: session.id }).then(function (data) {
            if (data.current) {
                BYDAuth.clearToken();
                window.location.href = '/login.html';
                return;
            }
            toast(t('sessions.signed_out', 'Signed out'), 'success');
            refresh();
        }).catch(function () {
            button.disabled = false;
            toast(t('sessions.action_failed', "Couldn't sign out. Try again."), 'error');
        });
    }

    function signOutOthers() {
        if (others.length === 0) return;
        if (!window.confirm(t('sessions.confirm_sign_out_others', 'Sign out every other browser?'))) return;
        var button = document.getElementById('signOutOthersBtn');
        button.disabled = true;
        call('/api/auth/sessions/revoke-others', 'POST', {}).then(function () {
            toast(t('sessions.signed_out', 'Signed out'), 'success');
            refresh();
        }).catch(function () {
            button.disabled = false;
            toast(t('sessions.action_failed', "Couldn't sign out. Try again."), 'error');
        });
    }

    BYD.sessions = { refresh: refresh, signOutOthers: signOutOthers };
})();
