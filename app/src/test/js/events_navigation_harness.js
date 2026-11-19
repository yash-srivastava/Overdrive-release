'use strict';

// Optional regression harness (not part of the browser bundle).
// Run with: node app/src/test/js/events_navigation_harness.js
// Exercises the real Events navigation methods with a changing, mocked index.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const src = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/events.js'), 'utf8');
const id = n => n.toString(16).padStart(32, '0');
const rec = (n, ts, rank = 0) => ({ id: id(n), filename: 'clip' + n + '.mp4',
    timestamp: ts, rootRank: rank, videoUrl: '/video/id/' + id(n) });

function fixture() {
    const nav = { hidden: true, parentNode: { classList: { toggle() {} } } };
    const modal = { classList: { contains: name => name === 'active' && modal.active } };
    modal.active = true;
    const prev = { disabled: false };
    const next = { disabled: false };
    const elements = {
        videoNav: nav, videoPrevBtn: prev, videoNextBtn: next,
        videoModal: modal, recordingsList: {}
    };
    const sandbox = {
        window: { BYD: {} }, console,
        document: { getElementById: name => elements[name] || null },
        fetch: null
    };
    sandbox.BYD = sandbox.window.BYD;
    vm.createContext(sandbox);
    vm.runInContext(src, sandbox, { filename: 'events.js' });
    const events = sandbox.BYD.events;
    const start = rec(3, 300);
    events.recordings = [rec(1, 500), rec(2, 400), start];
    events.totalCount = 6;
    events.currentPage = 1;
    events.totalPages = 2;
    events.pageSize = 3;
    events._activeRecording = start;
    events._activeRecordingKey = start.id;
    // Avoid media playback in this isolated navigation harness; record
    // exactly which recording the actual navigateRecording() selected.
    const opened = [];
    events._openAdjacentRecording = (key, recording) => {
        assert.strictEqual(key, recording.id);
        opened.push(recording.id);
        events._activeRecording = recording;
        events._activeRecordingKey = key;
        events._recordingNavSerial++;
        events._recordingNavBoundary = { '-1': false, '1': false };
    };
    return { events, sandbox, modal, nav, prev, next, opened };
}

const compare = (a, b) => b.timestamp - a.timestamp
    || a.rootRank - b.rootRank || a.id.localeCompare(b.id);

(async () => {
    const f = fixture();
    // The UI snapshot remains [A B C] / [D E F]. New X appears at the
    // front while C is playing: page-based Prev would eventually skip C.
    let library = [rec(7, 600), rec(1, 500), rec(2, 400), rec(3, 300),
        rec(4, 200), rec(5, 100), rec(6, 0)];
    const requests = [];
    f.sandbox.fetch = async url => {
        requests.push(url);
        const query = new URL('https://test.invalid' + url).searchParams;
        const from = query.get('fromId');
        const step = Number(query.get('step'));
        const sorted = library.slice().sort(compare);
        const index = sorted.findIndex(recording => recording.id === from);
        const recording = sorted[index + step] || null;
        return { ok: true, json: async () => ({ success: true, recording }) };
    };

    await f.events.navigateRecording(-1); // C -> B (not the newest X)
    await f.events.navigateRecording(-1); // B -> A
    await f.events.navigateRecording(-1); // A -> X (new since the list loaded)
    assert.deepStrictEqual(f.opened, [id(2), id(1), id(7)]);
    assert.strictEqual(f.events.recordings.length, 3, 'do not corrupt list page');
    await f.events.navigateRecording(1); // X -> A
    assert.strictEqual(f.opened[3], id(1));
    assert.ok(requests.every(url => url.startsWith('/api/recordings/adjacent?')));
    assert.ok(requests.every(url => url.includes('fromId=')));
    assert.strictEqual(f.nav.hidden, false, 'nav remains visible for off-page clips');

    // Full filter context reaches the adjacent API as on the list request.
    f.events.actorFilter = { class: 'vehicle', place: 'Marina Bay+' };
    f.events.storageFilter = 'SD_CARD';
    f.events.selectedDate = '2026-11-19';
    f.events.placeContainsQuery = 'Schöne';
    f.events.parkingSessionId = 'park_12';
    await f.events.navigateRecording(1);
    const query = new URL('https://test.invalid' + requests[requests.length - 1]).searchParams;
    assert.strictEqual(query.get('place'), 'Marina Bay+');
    assert.strictEqual(query.get('placeContains'), 'Schöne');
    assert.strictEqual(query.get('storage'), 'SD_CARD');
    assert.strictEqual(query.get('parkingSessionId'), 'park_12');
    assert.strictEqual(query.get('class'), 'vehicle');
    assert.strictEqual(query.get('date'), '2026-11-19');

    // At the end, do not reopen the same clip or move to an unrelated page.
    const end = library[library.length - 1];
    f.events._activeRecording = end;
    f.events._activeRecordingKey = end.id;
    f.events._recordingNavBoundary = { '-1': false, '1': false };
    await f.events.navigateRecording(1);
    assert.strictEqual(f.next.disabled, true);
    assert.strictEqual(f.events._activeRecordingKey, end.id);

    // Old response after player closes must not reopen a clip.
    let deliver;
    f.sandbox.fetch = () => new Promise(resolve => { deliver = resolve; });
    const openedBefore = f.opened.length;
    const pending = f.events.navigateRecording(-1);
    f.modal.active = false;
    f.events._recordingNavSerial++;
    deliver({ ok: true, json: async () => ({ success: true, recording: library[0] }) });
    await pending;
    assert.strictEqual(f.opened.length, openedBefore);

    // Same timestamp and root-rank uses ID as the final stable sort key.
    library = [rec(3, 100), rec(2, 100), rec(1, 100)];
    assert.deepStrictEqual(library.sort(compare).map(r => r.id), [id(1), id(2), id(3)]);
    console.log('events_navigation_harness: ALL CHECKS PASSED');
})().catch(error => { console.error(error); process.exitCode = 1; });
