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
    const list = { innerHTML: 'existing cards' };
    const card = { dataset: { recordingKey: 'clip' }, classList: { add() {} } };
    const BYD = { i18n: { plural: (key, count) => String(count) } };
    const document = {
        hidden: false,
        getElementById: id => id === 'recordingsList' ? list : {},
        querySelectorAll: () => [card]
    };
    const sandbox = {
        BYD, document, window: { BYD },
        console: { error() {}, warn() {} },
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
    return { events, document, list, clip, requests, renders: () => renders };
}

function reply(request, recordings) {
    request.resolve({ ok: true, json: async () => ({ success: true, recordings }) });
}

async function run() {
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
