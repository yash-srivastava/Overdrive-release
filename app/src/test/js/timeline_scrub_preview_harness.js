// Run with Node.js; exercise actual player methods without media or network I/O.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/timeline-player.js'), 'utf8');

function fixture(withDecode = true) {
    const pending = [];
    const sandbox = { clearTimeout, setTimeout };
    sandbox.Image = function () {
        if (withDecode) this.decode = () => new Promise((resolve, reject) => pending.push({ resolve, reject }));
    };
    vm.runInNewContext(source, sandbox);
    const player = Object.create(sandbox.BYD.tlplayer._internals.Player.prototype);
    Object.assign(player, {
        dead: false, scrub: null, scrubRelease: false, frameSeq: 0, frameShownSeq: -1,
        shownUrl: null, revokeLater: [], img: { hidden: true, src: '' },
        video: { paused: true, seeking: false, pause() {} }, intent: 'pause',
        frameTimer: 0, dwell: 0, _updateState() {}, seekWall() {}
    });
    return {
        player, pending,
        show(url) { player._showFrame(url, ++player.frameSeq); },
        async finish(index, fail = false) {
            if (fail) pending[index].reject(new Error('decode failed'));
            else pending[index].resolve();
            await Promise.resolve();
        }
    };
}

async function run() {
    // Existing preview can remain visible while seeking, but no later decode
    // may bring it back after the first video frame has been presented.
    {
        const f = fixture(), p = f.player;
        p.beginScrub();
        f.show('blob:first');
        await f.finish(0);
        f.show('blob:late');
        p.endScrub(1000);
        assert.strictEqual(p.scrubRelease, true);
        p._onFrame(1);
        assert.strictEqual(p.img.hidden, true);
        await f.finish(1);
        assert.strictEqual(p.img.hidden, true, 'late decode covered video after handoff');
        assert.strictEqual(p.img.src, 'blob:first');
    }
    // Releasing before the first preview appears must not reveal it later.
    {
        const f = fixture(), p = f.player;
        p.beginScrub(); f.show('blob:late');
        p.endScrub(1000);
        assert.strictEqual(p.scrubRelease, false);
        await f.finish(0, true);
        assert.strictEqual(p.img.hidden, true);
    }
    // A new day clears the preview even if a decode has not completed.
    {
        const f = fixture(), p = f.player;
        p.beginScrub(); f.show('blob:old-day');
        p._clearScrub(true);
        await f.finish(0);
        assert.strictEqual(p.img.hidden, true);
    }
    // A new gesture rejects old decode and fetch callbacks, even before
    // its first new preview request. Normal new previews still render.
    {
        const f = fixture(), p = f.player;
        p.beginScrub(); f.show('blob:old-decode');
        const oldFetchSeq = ++p.frameSeq;
        p.endScrub(1000); p.beginScrub();
        await f.finish(0);
        p._showFrame('blob:old-fetch', oldFetchSeq);
        assert.strictEqual(f.pending.length, 1);
        assert.strictEqual(p.img.hidden, true);
        f.show('blob:current'); await f.finish(1);
        assert.strictEqual(p.img.src, 'blob:current');
        assert.strictEqual(p.img.hidden, false);
    }
    // Keep the latest-frame rule, and allow updates during the intentional
    // release hold until a real video frame arrives.
    {
        const f = fixture(), p = f.player;
        p.beginScrub(); f.show('blob:older'); f.show('blob:newer');
        await f.finish(1); await f.finish(0);
        assert.strictEqual(p.img.src, 'blob:newer');
        f.show('blob:release'); p.endScrub(1000);
        await f.finish(2);
        assert.strictEqual(p.img.src, 'blob:release');
        assert.strictEqual(p.img.hidden, false);
        p._onFrame(1);
        assert.strictEqual(p.img.hidden, true);
    }
    // Older browsers have no Image.decode; retain synchronous display.
    {
        const f = fixture(false), p = f.player;
        p.beginScrub(); f.show('blob:sync');
        assert.strictEqual(p.img.hidden, false);
        assert.strictEqual(p.img.src, 'blob:sync');
        p.endScrub(1000); p._onFrame(1);
        assert.strictEqual(p.img.hidden, true);
    }
    console.log('6 timeline scrub preview scenarios passed');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
