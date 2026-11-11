/*
 * Audio Library controller (Automations page).
 *
 * Upload / list / delete / preview the sounds the "Play Audio" automation + key-
 * mapping action can play. Mirrors the Screen Deterrent image upload:
 *   - FileReader.readAsDataURL → base64
 *   - POST { filename, dataBase64 } to /api/audio/library via fetch() (NOT XHR — the
 *     in-app WebView drops XHR POST bodies; see the deterrent upload note).
 *   - list via GET /api/audio/library, delete via DELETE ?name=, preview via
 *     POST /api/audio/library/play.
 *
 * Renders into #audioLibList and #audioLibFile on the Automations page.
 */
window.AudioLibrary = (function () {
    'use strict';

    var MAX_BYTES = 48 * 1024 * 1024;
    var refreshGeneration = 0;

    function $(id) { return document.getElementById(id); }
    function tr(k, d) { return (window.BYD && BYD.i18n && BYD.i18n.t) ? (BYD.i18n.t(k) || d) : d; }
    function toast(msg, kind) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, kind || 'info'); }

    function refresh() {
        var list = $('audioLibList');
        if (!list) return;
        var generation = ++refreshGeneration;
        fetch('/api/audio/library', { cache: 'no-store' })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (j) {
                if (generation !== refreshGeneration) return;
                if (!j || !j.success || !Array.isArray(j.sounds)) throw new Error('Invalid audio library response');
                render(j.sounds);
            })
            .catch(function () {
                if (generation === refreshGeneration) {
                    toast(tr('automation.audio_list_failed', 'Could not load sounds'), 'error');
                }
            });
    }

    function render(sounds) {
        var list = $('audioLibList');
        if (!list) return;
        list.innerHTML = '';
        if (!sounds.length) {
            var empty = document.createElement('div');
            empty.className = 'setting-desc';
            empty.style.padding = '8px 0';
            empty.textContent = tr('automation.audio_library_empty', 'No sounds uploaded yet.');
            list.appendChild(empty);
            return;
        }
        for (var i = 0; i < sounds.length; i++) {
            (function (s) {
                var row = document.createElement('div');
                row.className = 'setting-row';
                row.style.borderBottom = 'none';

                var info = document.createElement('div');
                info.className = 'setting-info';
                var name = document.createElement('div');
                name.className = 'setting-name';
                name.textContent = s.name;
                var size = document.createElement('div');
                size.className = 'setting-desc';
                size.textContent = formatSize(s.size);
                info.appendChild(name);
                info.appendChild(size);
                row.appendChild(info);

                var ctl = document.createElement('div');
                ctl.className = 'setting-control';
                ctl.style.display = 'flex';
                ctl.style.gap = '8px';

                var play = document.createElement('button');
                play.className = 'btn btn-secondary';
                play.textContent = tr('automation.audio_preview', 'Preview');
                play.onclick = function () { preview(s.name); };
                ctl.appendChild(play);

                var del = document.createElement('button');
                del.className = 'btn btn-danger-ghost';
                del.textContent = tr('automation.audio_delete', 'Delete');
                del.onclick = function () { remove(s.name); };
                ctl.appendChild(del);

                row.appendChild(ctl);
                list.appendChild(row);
            })(sounds[i]);
        }
    }

    function formatSize(bytes) {
        if (!bytes && bytes !== 0) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    function upload(file) {
        // Reset the input so re-picking the same file fires change again.
        try { var fi = $('audioLibFile'); if (fi) fi.value = ''; } catch (e) {}
        if (!file) return;
        if (file.size > MAX_BYTES) { toast(tr('automation.audio_too_large', 'File too large (max 48 MB)'), 'error'); return; }
        if (file.size === 0) { toast(tr('automation.audio_empty', 'The selected file is empty'), 'error'); return; }

        var reader = new FileReader();
        // Report the READ failure distinctly from an upload failure. In the in-app
        // WebView the file arrives as a content:// Uri, and if the WebView is not
        // permitted to resolve it (WebSettings.allowContentAccess) the read fails
        // here and NO POST is ever sent — which used to look identical to "nothing
        // happened", with no trace in the daemon log because the request never
        // reached it. Name the failure and include the error so it is diagnosable
        // from the UI alone.
        reader.onerror = function () {
            var msg = (reader.error && (reader.error.name || reader.error.message)) || 'unknown';
            console.error('[audio] FileReader failed:', msg, 'file=', file && file.name);
            toast(tr('automation.audio_read_failed', 'Could not read the file') + ' (' + msg + ')', 'error');
        };
        reader.onabort = function () {
            console.error('[audio] FileReader aborted for', file && file.name);
            toast(tr('automation.audio_read_failed', 'Could not read the file'), 'error');
        };
        reader.onload = function (e) {
            var dataUrl = e.target.result;
            if (!dataUrl) { toast(tr('automation.audio_read_failed', 'Could not read the file'), 'error'); return; }
            // fetch(), NOT XHR — the in-app WebView drops XHR POST bodies.
            fetch('/api/audio/library', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filename: file.name, dataBase64: dataUrl })
            }).then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (data) {
                if (data && data.success) {
                    // Tell the user when this REPLACED an existing sound. The
                    // server sanitizes filenames (disallowed chars collapse to
                    // '_'), so two different files can land on the same name and
                    // silently swap what every automation bound to it will play.
                    if (data.replaced) {
                        toast(tr('automation.audio_upload_replaced',
                            'Replaced existing sound "' + data.name + '"'), 'success');
                    } else {
                        toast(tr('automation.audio_upload_ok', 'Sound uploaded'), 'success');
                    }
                    refresh();
                } else {
                    toast((data && data.error) || tr('automation.audio_upload_failed', 'Upload failed'), 'error');
                }
            }).catch(function (err) {
                toast((err && err.message) || tr('automation.audio_upload_failed', 'Upload failed'), 'error');
            });
        };
        try { reader.readAsDataURL(file); }
        catch (err) { toast(tr('automation.audio_read_failed', 'Could not read the file'), 'error'); }
    }

    function remove(name) {
        var doDelete = function () {
            fetch('/api/audio/library?name=' + encodeURIComponent(name), { method: 'DELETE' })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    if (data && data.success) { toast(tr('automation.audio_deleted', 'Sound deleted'), 'success'); refresh(); }
                    else { toast((data && data.error) || tr('automation.audio_delete_failed', 'Delete failed'), 'error'); }
                })
                .catch(function () { toast(tr('automation.audio_delete_failed', 'Delete failed'), 'error'); });
        };
        if (window.BYD && BYD.utils && BYD.utils.confirmDialog) {
            BYD.utils.confirmDialog({
                title: tr('automation.audio_delete', 'Delete'),
                body: tr('automation.audio_delete_confirm', 'Delete this sound? Automations that use it will stop playing it.'),
                confirmLabel: tr('automation.audio_delete', 'Delete'),
                cancelLabel: tr('common.cancel', 'Cancel'),
                danger: true
            }).then(function (ok) { if (ok) doDelete(); });
        } else if (window.confirm(tr('automation.audio_delete_confirm', 'Delete this sound?'))) {
            doDelete();
        }
    }

    function preview(name) {
        fetch('/api/audio/library/play', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name, channel: 'media' })
        }).then(function (r) { return r.json(); })
          .then(function (data) {
              if (!(data && data.success)) toast((data && data.error) || tr('automation.audio_preview_failed', 'Could not play'), 'error');
          })
          .catch(function () { toast(tr('automation.audio_preview_failed', 'Could not play'), 'error'); });
    }

    // Auto-refresh the list on load once the DOM is ready.
    function init() { refresh(); }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return { refresh: refresh, upload: upload, remove: remove, preview: preview };
}());
