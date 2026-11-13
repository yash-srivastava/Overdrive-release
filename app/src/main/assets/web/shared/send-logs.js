/**
 * Diagnostics — per-daemon log send (braveheart only).
 *
 * On the About page, queries /api/logs/available. If the backend reports log
 * upload is configured (braveheart build with a Cloudflare Worker URL baked
 * in), reveals the #diagSection and renders one row per daemon. Tapping a row
 * POSTs /api/logs/upload?daemon=<key>, which uploads the (redacted) log to the
 * Worker and returns a short code shown for the user to share.
 *
 * Each daemon row includes the component it owns and examples of when to send it.
 * ES5-only (Chrome 58 head unit): var + string concat + fetch(). POST uses
 * fetch (the WebView eats XHR write bodies). No flex `gap`.
 */
(function () {
    'use strict';

    function $(id) { return document.getElementById(id); }
    function t(key, fb) {
        if (window.BYD && BYD.i18n && BYD.i18n.t) {
            var v = BYD.i18n.t(key);
            if (v && v !== key) return v;
        }
        return fb;
    }
    function toast(msg, type) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, type || 'info');
        else if (window.BYD && BYD.toast) BYD.toast(msg, type || 'info');
    }

    var STYLE_INJECTED = false;
    function injectStyles() {
        if (STYLE_INJECTED) return;
        STYLE_INJECTED = true;
        var css =
            '.diag-row{display:flex;align-items:center;padding:13px 14px;border-bottom:1px solid var(--border-subtle);cursor:pointer;color:var(--text-primary);}' +
            '.diag-row:last-child{border-bottom:0;}' +
            '.diag-row:hover{background:var(--bg-hover);}' +
            '.diag-row:focus-visible{outline:2px solid var(--brand-primary);outline-offset:-2px;}' +
            '.diag-copy{flex:1;min-width:0;}' +
            '.diag-row .diag-name{display:block;font-size:15px;font-weight:600;}' +
            '.diag-row .diag-component{display:block;margin-top:4px;font-size:12px;line-height:1.45;color:var(--text-secondary);}' +
            '.diag-row .diag-when{display:block;margin-top:3px;font-size:12px;line-height:1.45;color:var(--text-secondary);opacity:0.85;}' +
            '.diag-row .diag-act{flex-shrink:0;margin-left:12px;font-size:13px;color:var(--text-secondary);white-space:nowrap;}' +
            '.diag-row.busy{opacity:0.6;pointer-events:none;}' +
            '.diag-code{font-family:ui-monospace,monospace;font-size:18px;font-weight:700;letter-spacing:1px;color:var(--brand-primary);}';
        var s = document.createElement('style');
        s.textContent = css;
        document.head.appendChild(s);
    }

    function prettyName(key) {
        // Fallback label for an older server that does not return descriptions.
        var names = {
            camera: 'Camera', accsentry: 'ACC Sentry', sentry: 'Sentry',
            telegram: 'Telegram Bot', cloudflared: 'Cloudflared Tunnel', zrok: 'Zrok Tunnel',
            tailscale: 'Tailscale', wireguard: 'WireGuard', singbox: 'Sing-box Proxy'
        };
        return names[key] || key;
    }

    function descriptionFor(key, descriptions) {
        var info = descriptions && descriptions[key] ? descriptions[key] : {};
        return {
            name: info.name || prettyName(key),
            component: info.component || t('logs.component_unavailable', 'Component details are unavailable in this app version.'),
            sendWhen: info.sendWhen || t('logs.send_when_unavailable', 'Send this log when the problem involves this service.')
        };
    }

    function uploadDaemon(rowEl, key) {
        if (rowEl.className.indexOf('busy') >= 0) return;
        rowEl.className = 'diag-row busy';
        var act = rowEl.querySelector('.diag-act');
        if (act) act.textContent = t('logs.uploading', 'Uploading…');

        fetch('/api/logs/upload?daemon=' + encodeURIComponent(key), { method: 'POST' })
            .then(function (r) { return r.text().then(function (txt) { return { ok: r.ok, status: r.status, body: txt }; }); })
            .then(function (resp) {
                rowEl.className = 'diag-row';
                var res = null;
                try { res = resp.body ? JSON.parse(resp.body) : null; } catch (e) {}
                if (!resp.ok || !res || res.error || !res.code) {
                    if (act) act.textContent = t('logs.send', 'Send');
                    toast(t('logs.upload_failed', 'Upload failed') + ': ' + ((res && res.error) || ('HTTP ' + resp.status)), 'error');
                    return;
                }
                if (act) act.textContent = '✓';
                showCode(prettyName(key), res.code);
            })
            .catch(function (e) {
                rowEl.className = 'diag-row';
                if (act) act.textContent = t('logs.send', 'Send');
                toast(t('logs.upload_failed', 'Upload failed') + ': ' + ((e && e.message) || t('errors.network', 'Network error')), 'error');
            });
    }

    function showCode(name, code) {
        // Reuse the update modal styling shell if present; otherwise a minimal
        // inline modal. Keep it ES5 + no external deps.
        var bg = document.createElement('div');
        bg.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.72);z-index:9000;display:flex;align-items:center;justify-content:center;padding:16px;';
        var card = document.createElement('div');
        card.style.cssText = 'background:#0e1218;color:#e8eef5;padding:24px;max-width:420px;width:100%;border-radius:14px;border:1px solid #232a35;text-align:center;';
        var h = document.createElement('h2');
        h.style.cssText = 'margin:0 0 10px;font-size:17px;color:#fff;';
        h.textContent = t('logs.uploaded_title', 'Log uploaded');
        var p = document.createElement('p');
        p.style.cssText = 'font-size:13px;color:#9aa6b3;margin:0 0 16px;line-height:1.5;';
        p.textContent = t('logs.uploaded_body', 'Share this code with the maintainer:');
        var codeEl = document.createElement('div');
        codeEl.className = 'diag-code';
        codeEl.style.cssText = 'margin:0 0 14px;';
        codeEl.textContent = code;

        // Where to report — the community links the user shares the code on.
        var whereEl = document.createElement('div');
        whereEl.style.cssText = 'font-size:12px;color:#9aa6b3;margin:0 0 8px;line-height:1.5;';
        whereEl.textContent = t('logs.uploaded_where', 'Post it on Discord, GitHub Issues or WhatsApp:');
        var linkRow = document.createElement('div');
        linkRow.style.cssText = 'margin:0 0 18px;';
        var rdefs = [
            { label: t('update.report_discord', 'Discord'), url: t('update.report_discord_url', 'https://discord.gg/PZutk9fg4h') },
            { label: t('update.report_github', 'GitHub Issues'), url: t('update.report_github_url', 'https://github.com/yash-srivastava/Overdrive-release/issues') },
            { label: t('update.report_whatsapp', 'WhatsApp'), url: t('update.report_whatsapp_url', 'https://chat.whatsapp.com/HChmriCWgr9KwAtE6OEkiM') }
        ];
        for (var ri = 0; ri < rdefs.length; ri++) {
            var ra = document.createElement('a');
            ra.href = rdefs[ri].url;
            ra.target = '_blank';
            ra.rel = 'noopener noreferrer';
            ra.textContent = rdefs[ri].label;
            ra.style.cssText = 'display:inline-block;font-size:12px;font-weight:600;color:#3b82f6;text-decoration:none;padding:6px 12px;border:1px solid #2a3340;border-radius:8px;' + (ri > 0 ? 'margin-left:8px;' : '');
            linkRow.appendChild(ra);
        }

        var btnRow = document.createElement('div');
        btnRow.style.cssText = 'display:flex;justify-content:center;';
        var copyBtn = document.createElement('button');
        copyBtn.style.cssText = 'padding:10px 18px;border-radius:8px;border:0;background:#3b82f6;color:#fff;font-weight:600;font-size:13px;cursor:pointer;margin-right:8px;font-family:inherit;';
        copyBtn.textContent = t('logs.copy_code', 'Copy code');
        copyBtn.onclick = function () {
            function done() { toast(t('common.copied', 'Copied'), 'success'); }
            // navigator.clipboard is absent over plain HTTP (LAN / in-car WebView).
            function fallback() {
                var ta = document.createElement('textarea');
                ta.value = code;
                ta.setAttribute('readonly', '');
                ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;';
                card.appendChild(ta);
                ta.select();
                ta.setSelectionRange(0, code.length);
                var ok = false;
                try { ok = document.execCommand('copy'); } catch (e) {}
                card.removeChild(ta);
                if (ok) { done(); return; }
                // Last resort: select the code so the user can long-press copy it.
                var range = document.createRange();
                range.selectNodeContents(codeEl);
                var sel = window.getSelection();
                sel.removeAllRanges();
                sel.addRange(range);
            }
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(code).then(done, fallback);
            } else {
                fallback();
            }
        };
        var closeBtn = document.createElement('button');
        closeBtn.style.cssText = 'padding:10px 18px;border-radius:8px;border:0;background:transparent;color:#cdd6e0;font-weight:600;font-size:13px;cursor:pointer;font-family:inherit;';
        closeBtn.textContent = t('common.close', 'Close');
        closeBtn.onclick = function () { document.body.removeChild(bg); };
        btnRow.appendChild(copyBtn);
        btnRow.appendChild(closeBtn);
        card.appendChild(h); card.appendChild(p); card.appendChild(codeEl);
        card.appendChild(whereEl); card.appendChild(linkRow); card.appendChild(btnRow);
        bg.appendChild(card);
        bg.addEventListener('click', function (e) { if (e.target === bg) document.body.removeChild(bg); });
        document.body.appendChild(bg);
    }

    function render(daemons, descriptions) {
        var list = $('diagDaemonList');
        var section = $('diagSection');
        if (!list || !section) return;
        list.innerHTML = '';
        for (var i = 0; i < daemons.length; i++) {
            (function (key) {
                var info = descriptionFor(key, descriptions);
                var row = document.createElement('div');
                row.className = 'diag-row';
                row.setAttribute('role', 'button');
                row.setAttribute('tabindex', '0');
                row.setAttribute('aria-label', info.name + '. ' + info.component + ' ' + info.sendWhen);

                var copy = document.createElement('div');
                copy.className = 'diag-copy';

                var name = document.createElement('span');
                name.className = 'diag-name';
                name.textContent = info.name;

                var component = document.createElement('span');
                component.className = 'diag-component';
                component.textContent = info.component;

                var when = document.createElement('span');
                when.className = 'diag-when';
                when.textContent = t('logs.when_to_send', 'Send this log when: ') + info.sendWhen;

                var act = document.createElement('span');
                act.className = 'diag-act';
                act.textContent = t('logs.send', 'Send');

                copy.appendChild(name);
                copy.appendChild(component);
                copy.appendChild(when);
                row.appendChild(copy);
                row.appendChild(act);
                row.addEventListener('click', function () { uploadDaemon(row, key); });
                row.addEventListener('keydown', function (e) {
                    if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        uploadDaemon(row, key);
                    }
                });
                list.appendChild(row);
            })(daemons[i]);
        }
        section.style.display = '';
    }

    function init() {
        fetch('/api/logs/available').then(function (r) { return r.json(); }).then(function (res) {
            if (!res || !res.available || !res.daemons || !res.daemons.length) return; // hidden on non-braveheart
            injectStyles();
            render(res.daemons, res.descriptions || {});
        }).catch(function () { /* endpoint missing on old daemon — stay hidden */ });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        setTimeout(init, 0);
    }
})();
