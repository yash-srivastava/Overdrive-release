/* Factory DVR WebUI: only lists/plays/exports files returned by the read-only API. ES5-compatible. */
(function () {
    'use strict';
    var list, status, player, panel, playing, refresh;
    var busy = false;
    function t(key, fallback, vars) {
        var value = BYD.i18n.t('factory_dvr.' + key, vars);
        return value === 'factory_dvr.' + key ? fallback : value;
    }
    function setStatus(message) { status.textContent = message; }
    function bytes(n) { return (n / (1024 * 1024)).toFixed(1) + ' MB'; }
    function url(id, download) {
        return '/api/factory-dvr/file?id=' + encodeURIComponent(id) + (download ? '&download=1' : '');
    }
    function stopPlayer() {
        player.pause();
        player.removeAttribute('src');
        player.load();
        panel.style.display = 'none';
    }
    function play(clip) {
        stopPlayer();
        playing.textContent = clip.relativePath;
        panel.style.display = 'block';
        player.src = url(clip.id, false);
        var started = player.play();
        if (started && typeof started.catch === 'function') started.catch(function () {
            setStatus(t('play_failed', 'Playback failed or the format is unsupported.'));
        });
        panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    function addClip(clip) {
        var row = document.createElement('article');
        row.className = 'factory-clip';
        var path = document.createElement('span');
        path.className = 'factory-path';
        path.textContent = clip.relativePath;
        row.appendChild(path);
        var meta = document.createElement('p');
        meta.className = 'factory-meta';
        meta.textContent = new Date(clip.modified).toLocaleString() + ' · ' + bytes(clip.bytes);
        row.appendChild(meta);
        var actions = document.createElement('div');
        actions.className = 'factory-clip-actions';
        var button = document.createElement('button');
        button.className = 'btn btn-secondary';
        button.type = 'button';
        button.textContent = t('play', 'Play');
        button.addEventListener('click', function () { play(clip); });
        actions.appendChild(button);
        var download = document.createElement('a');
        download.className = 'btn btn-secondary';
        download.href = url(clip.id, true);
        download.download = clip.name;
        download.textContent = t('save_copy', 'Save a copy');
        actions.appendChild(download);
        row.appendChild(actions);
        list.appendChild(row);
    }
    function load() {
        if (busy) return;
        busy = true;
        refresh.disabled = true;
        stopPlayer();
        list.textContent = '';
        setStatus(t('scanning', 'Scanning the microSD card…'));
        BYDAuth.fetch('/api/factory-dvr', { cache: 'no-store' })
            .then(function (r) {
                if (!r.ok) throw new Error(String(r.status));
                return r.json();
            }).then(function (data) {
                if (!data.found) {
                    setStatus(t('missing', 'No readable BYD factory DVR folder was found.'));
                } else if (!data.videos || !data.videos.length) {
                    setStatus(t('empty', 'No readable factory videos were found.'));
                } else {
                    setStatus(data.videos.length + ' ' + t('videos', 'factory videos') +
                        (data.limited ? ' · ' + t('limited', 'List limited to the newest recordings') : ''));
                    data.videos.forEach(addClip);
                }
            }).catch(function (error) {
                if (error.message !== 'Unauthorized') {
                    setStatus(t('unavailable', 'Unable to read factory recordings. Check the microSD card and login.'));
                }
            }).then(function () {
                busy = false;
                refresh.disabled = false;
            });
    }
    window.BYD = window.BYD || {};
    BYD.factoryDvr = {
        init: function () {
            list = document.getElementById('factoryList');
            status = document.getElementById('factoryStatus');
            player = document.getElementById('factoryPlayer');
            panel = document.getElementById('factoryPlayerPanel');
            playing = document.getElementById('factoryPlaying');
            refresh = document.getElementById('factoryRefresh');
            refresh.addEventListener('click', load);
            player.addEventListener('error', function () {
                setStatus(t('play_failed', 'Playback failed or the format is unsupported.'));
            });
            load();
        }
    };
}());
