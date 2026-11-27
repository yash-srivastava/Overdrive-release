'use strict';

// Run with: node app/src/test/js/events_watch_history_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/events.js'), 'utf8');

function setup() {
    const requests = [];
    const timers = new Map();
    let nextTimer = 0;
    const listeners = {};
    const list = { innerHTML: 'existing cards' };
    const card = { dataset: { recordingKey: 'clip' }, classList: { add() {} } };
    const BYD = { i18n: { plural: (key, count) => String(count), t: key => key },
        core: { toast() {} } };
    const document = {
        hidden: false,
        addEventListener(type, callback) { listeners[type] = callback; },
        getElementById: id => id === 'recordingsList' ? list : {},
        querySelectorAll: () => [card]
    };
    const sandbox = {
        BYD, document, window: { BYD },
        console: { error() {}, warn() {} },
        setTimeout(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); },
        fetch(url, options) {
            return new Promise((resolve, reject) => requests.push({ url, options, resolve, reject }));
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);
    const events = BYD.events;
    const clip = { id: 'clip', size: 100, mp4Mtime: 200, watched: false };
    events.recordings = [clip];
    events._initDone = true;
    events._listHidden = () => false;
    events.loadStorageStats = events.loadDatesWithRecordings = events.loadPlaceChips = () => {};
    events.updatePagination = events._refreshRecordingNav = () => {};
    let renders = 0;
    events.renderRecordings = () => { renders++; };
    return { events, document, list, clip, requests, timers, listeners, renders: () => renders };
}

function reply(request, recordings) {
    request.resolve({ ok: true, json: async () => ({ success: true, recordings }) });
}

function playback(duration) {
    const env = setup();
    const handlers = {};
    const player = {
        duration, currentTime: 0, paused: false, seeking: false, ended: false, readyState: 4,
        played: { length: 0, start: () => 0, end: () => 0 },
        addEventListener(type, callback) { handlers[type] = callback; }
    };
    const fire = type => handlers[type]();
    const finish = (start = 0, end = duration) => {
        player.currentTime = duration;
        player.played = { length: 1, start: () => start, end: () => end };
        player.paused = player.ended = true;
        fire('pause'); // Native pause is queued before ended at normal completion.
        fire('ended');
    };
    env.events._watchActiveKey = env.clip.id;
    env.events._bindWatchedTracking(player);
    return { ...env, player, fire, finish };
}

async function run() {
    // A fully played short clip saves exactly once, even with pause before ended.
    for (const duration of [0.4, 1.8, 2.5]) {
        const { events, clip, requests, timers, fire, finish } = playback(duration);
        fire('playing');
        finish();
        fire('ended');
        assert.strictEqual(timers.size, 0);
        assert.strictEqual(requests.length, 1);
        assert.strictEqual(requests[0].url, '/api/recordings/id/clip/watched');
        assert.deepStrictEqual(JSON.parse(requests[0].options.body), { sizeBytes: 100, mp4Mtime: 200 });
        assert.strictEqual(clip.watched, false, 'wait for daemon acknowledgement');
        requests[0].resolve({ ok: true, json: async () => ({ success: true }) });
        await Promise.all(Array.from(events._watchWrites));
        assert.strictEqual(clip.watched, true);
    }
    // Opening, pausing, seeking to the end, and hidden playback do not qualify.
    {
        const { requests, finish } = playback(1);
        finish();
        assert.strictEqual(requests.length, 0);
    }
    {
        const { player, requests, fire, finish } = playback(1);
        fire('playing');
        player.paused = true;
        fire('pause');
        assert.strictEqual(requests.length, 0);
        player.seeking = true;
        fire('seeking');
        player.currentTime = 0.95;
        player.seeking = player.paused = false;
        fire('seeked');
        finish(0.95);
        assert.strictEqual(requests.length, 0);
    }
    {
        const { document, requests, listeners, fire, finish } = playback(1);
        fire('playing');
        document.hidden = true;
        listeners.visibilitychange();
        document.hidden = false;
        finish();
        assert.strictEqual(requests.length, 0);
    }
    {
        const { requests, fire, finish } = playback(1);
        fire('playing');
        finish(0, 0.5);
        assert.strictEqual(requests.length, 0, 'incomplete played range');
    }
    // Longer clips retain the existing timer and do not use the short fallback.
    {
        const { requests, fire, finish } = playback(10);
        fire('playing');
        finish(9.8);
        assert.strictEqual(requests.length, 0);
    }
    {
        const { events, requests, timers, fire, finish } = playback(10);
        fire('playing');
        Array.from(timers.values())[0]();
        finish();
        assert.strictEqual(requests.length, 1);
        requests[0].resolve({ ok: true, json: async () => ({ success: true }) });
        await Promise.all(Array.from(events._watchWrites));
    }
    // Resume waits for the short clip's save before asking the daemon for an
    // unwatched recording. A failed save remains unwatched and can be retried.
    for (const success of [true, false]) {
        const { events, document, player, clip, requests, fire, finish } = playback(1);
        fire('playing');
        finish();
        events._recordingFilterParams = () => [];
        events._refreshPlaylistControls = () => {};
        document.getElementById = id => id === 'videoModal'
            ? { classList: { contains: () => false } }
            : id === 'videoPlayer' ? player : {};
        const resume = events.seekPlaylist(true);
        assert.strictEqual(requests.length, 1, 'resume waits for watched POST');
        requests[0].resolve({ ok: success, json: async () => ({ success }) });
        await Promise.all(Array.from(events._watchWrites));
        await new Promise(resolve => setImmediate(resolve));
        assert.strictEqual(clip.watched, success);
        assert.strictEqual(requests.length, 2);
        assert(requests[1].url.includes('resume=1'));
        requests[1].resolve({ ok: true, json: async () => ({ success: true, status: 'end' }) });
        await resume;
    }
    // A successful remote list must refresh after another client watched a clip.
    {
        const { events, list, clip, requests, renders } = setup();
        const refresh = events._refreshAfterReconnect();
        assert.strictEqual(requests.length, 1);
        assert.strictEqual(requests[0].options.cache, 'no-store');
        assert.strictEqual(list.innerHTML, 'existing cards', 'no spinner on a healthy list');
        reply(requests[0], [{ ...clip, watched: true }]);
        await refresh;
        assert.strictEqual(events.recordings[0].watched, true);
        assert.strictEqual(renders(), 1);
        const unchanged = events._refreshWatchHistory();
        reply(requests[1], [{ ...clip, watched: true }]);
        await unchanged;
        assert.strictEqual(renders(), 1, 'unchanged list should not reload thumbnails');
    }
    // Foreground refresh is deferred while hidden or in Timeline mode.
    {
        const { events, document, clip, requests } = setup();
        document.hidden = true;
        events._refreshWatchHistory();
        assert.strictEqual(requests.length, 0);
        document.hidden = false;
        events._listHidden = () => true;
        events._refreshWatchHistory();
        assert.strictEqual(requests.length, 0);
        events._listHidden = () => false;
        events._resumeHeldPolls();
        assert.strictEqual(requests.length, 1);
        reply(requests[0], [{ ...clip, watched: true }]);
    }
    // A list response created before an acknowledged POST must not revert it.
    {
        const { events, clip, requests, renders } = setup();
        const refresh = events._refreshWatchHistory();
        events._watchActiveKey = clip.id;
        const post = events._persistWatched(clip, clip.id);
        requests[1].resolve({ ok: true, json: async () => ({ success: true }) });
        await post;
        assert.strictEqual(clip.watched, true);
        reply(requests[0], [{ ...clip, watched: false }]);
        await new Promise(resolve => setImmediate(resolve));
        assert.strictEqual(events.recordings[0].watched, true);
        assert.strictEqual(renders(), 0, 'stale list was never painted');
        assert.strictEqual(requests.length, 3, 'request fresh daemon state');
        // A genuinely new file at that ID remains unwatched.
        reply(requests[2], [{ ...clip, size: 101, watched: false }]);
        await refresh;
        assert.strictEqual(events.recordings[0].watched, false);
    }
    // A failed background read keeps the list and can recover on reconnect.
    {
        const { events, list, clip, requests } = setup();
        const refresh = events._refreshWatchHistory();
        requests[0].reject(new Error('link down'));
        await refresh;
        assert.strictEqual(list.innerHTML, 'existing cards');
        assert.strictEqual(events._listFailed, true);
        events._refreshAfterReconnect();
        assert.strictEqual(requests.length, 2);
        reply(requests[1], [{ ...clip, watched: true }]);
    }
    console.log('events_watch_history_harness: all scenarios passed');
}

run().catch(error => { console.error(error); process.exitCode = 1; });
