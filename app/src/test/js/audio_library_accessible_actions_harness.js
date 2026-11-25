// Run with node app/src/test/js/audio_library_accessible_actions_harness.js.
// Exercise the real controller with an in-memory DOM and synthetic responses.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/audio-library.js'), 'utf8');

class Element {
    constructor(tag) {
        this.tagName = tag;
        this.children = [];
        this.style = {};
        this.attributes = {};
        this.textContent = '';
    }
    appendChild(child) { this.children.push(child); }
    set innerHTML(value) {
        assert.strictEqual(value, '');
        this.children = [];
    }
    setAttribute(key, value) { this.attributes[key] = String(value); }
    getAttribute(key) { return this.attributes[key] || null; }
}

function fixture(translations) {
    const list = new Element('div');
    const requests = [];
    const confirmations = [];
    let sounds = [];
    const context = {
        document: {
            readyState: 'loading',
            addEventListener() {},
            getElementById(id) { return id === 'audioLibList' ? list : null; },
            createElement(tag) { return new Element(tag); }
        },
        BYD: {
            i18n: { t: key => translations[key] },
            utils: {
                toast(message) { throw new Error(message); },
                confirmDialog(options) {
                    confirmations.push(options);
                    return Promise.resolve(false);
                }
            }
        },
        fetch(url, options) {
            requests.push({ url, options });
            const data = url === '/api/audio/library'
                ? { success: true, sounds } : { success: true };
            return Promise.resolve({ ok: true, json: () => Promise.resolve(data) });
        }
    };
    context.window = context;
    vm.runInNewContext(source, context);
    return {
        list, requests, confirmations,
        async refresh(names) {
            sounds = names.map(name => ({ name, size: 1024 }));
            context.AudioLibrary.refresh();
            await new Promise(resolve => setImmediate(resolve));
        }
    };
}

function checkRow(row, filename, previewLabel, deleteLabel) {
    assert.strictEqual(row.children[0].children[0].textContent, filename);
    const [play, del] = row.children[1].children;
    assert.strictEqual(play.tagName, 'button');
    assert.strictEqual(del.tagName, 'button');
    assert.strictEqual(play.textContent, previewLabel);
    assert.strictEqual(del.textContent, deleteLabel);
    assert.strictEqual(play.getAttribute('aria-label'), previewLabel + ': ' + filename);
    assert.strictEqual(del.getAttribute('aria-label'), deleteLabel + ': ' + filename);
    return { play, del };
}

async function main() {
    const f = fixture({});
    await f.refresh(['doorbell.mp3', 'rain.wav']);
    checkRow(f.list.children[0], 'doorbell.mp3', 'Preview', 'Delete');
    const { play, del } = checkRow(f.list.children[1], 'rain.wav', 'Preview', 'Delete');
    play.onclick();
    del.onclick();
    await new Promise(resolve => setImmediate(resolve));
    assert.strictEqual(f.requests.length, 2, 'cancelled deletion must not send a request');
    assert.strictEqual(f.requests[1].url, '/api/audio/library/play');
    assert.deepStrictEqual(JSON.parse(f.requests[1].options.body), { name: 'rain.wav', channel: 'media' });
    assert.strictEqual(f.confirmations.length, 1);
    console.log('PASS distinct action names and unchanged handlers');

    const translated = fixture({
        'automation.audio_preview': 'Anhoeren',
        'automation.audio_delete': 'Loeschen'
    });
    await translated.refresh(['sound & "sample".mp3']);
    checkRow(translated.list.children[0], 'sound & "sample".mp3', 'Anhoeren', 'Loeschen');
    console.log('PASS translated action labels and literal filename text');

    await f.refresh(['new.wav']);
    assert.strictEqual(f.list.children.length, 1);
    checkRow(f.list.children[0], 'new.wav', 'Preview', 'Delete');
    await f.refresh([]);
    assert.strictEqual(f.list.children.length, 1);
    assert.strictEqual(f.list.children[0].textContent, 'No sounds uploaded yet.');
    console.log('PASS refreshed filenames and empty library');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
