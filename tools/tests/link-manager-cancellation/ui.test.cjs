// Execute the unchanged production asset through its public UI entrypoints with dummy DOM/fetch.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../../app/src/main/assets/web/shared/safekeep.js'), 'utf8');

class Element {
    constructor() {
        this.children = [];
        this.style = {};
        this.value = '';
        this.classList = { toggle() {} };
        this.parts = new Map();
    }
    set innerHTML(value) { this.html = value; this.children = []; }
    get innerHTML() { return this.html || ''; }
    appendChild(child) { this.children.push(child); child.parentNode = this; }
    removeChild(child) { this.children = this.children.filter(item => item !== child); child.parentNode = null; }
    setAttribute() {}
    addEventListener() {}
    querySelectorAll() { return []; }
    querySelector(selector) {
        if (selector === 'input[name="skProvider"]:checked') return { value: this.provider || 'gdrive' };
        if (!this.parts.has(selector)) this.parts.set(selector, new Element());
        return this.parts.get(selector);
    }
}

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

async function flush() { for (let i = 0; i < 20; i++) await Promise.resolve(); }

function fixture(provider = 'gdrive') {
    const body = new Element();
    const elements = new Map();
    const requests = [], warnings = [], timers = new Map();
    const linkStatus = { state: 'pending' };
    const statusResponses = [];
    let nextTimer = 0;
    const start = deferred(), cancel = deferred(), finish = deferred();
    const document = {
        body, hidden: false, createElement() { const element = new Element(); element.provider = provider; return element; },
        addEventListener() {}, removeEventListener() {},
        querySelector() { return null; }, querySelectorAll() { return []; },
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, new Element());
            const element = elements.get(id);
            if (id === 'skAppId') element.value = 'DUMMY_CLIENT_ID';
            if (id === 'skPasteCode') element.value = 'DUMMY_CODE';
            return element;
        }
    };
    const reply = (value, status = 200) => ({ ok: status < 400, status, text: () => Promise.resolve(JSON.stringify(value)) });
    const context = {
        document, navigator: {}, location: { hash: '' }, console,
        BYD: { core: { toast(message, type) { warnings.push({ message, type }); } } },
        setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
        clearTimeout(id) { timers.delete(id); },
        fetch(url, options) {
            requests.push({ url, options });
            if (url === '/api/safekeep/config') return Promise.resolve(reply({ config: { destinations: [] }, oauthApps: { gdrive: true } }));
            if (url === '/api/safekeep/status') return Promise.resolve(reply({ enabled: true }));
            if (url === '/api/safekeep/link/start') return start.promise;
            if (url === '/api/safekeep/link/cancel') return cancel.promise;
            if (url === '/api/safekeep/link/finish') return finish.promise;
            if (url.indexOf('/api/safekeep/link/status?') === 0) {
                return statusResponses.length ? statusResponses.shift().promise : Promise.resolve(reply(linkStatus));
            }
            if (url === '/api/safekeep/dest/test') return Promise.resolve(reply({ success: true, result: {} }));
            throw new Error('Unexpected dummy request: ' + url);
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(source, context, { filename: 'safekeep.js', timeout: 1000 });
    const modal = () => body.children.at(-1).children[0];
    const button = (index) => modal().querySelector('.soh-modal-actions').children[index];
    async function openSignIn() {
        context.SK.init();
        await flush();
        context.SK.openAddStorage();
        button(1).onclick(); // Public provider picker -> OAuth introduction.
        button(1).onclick(); // Public introduction -> actual startLink closure.
        await flush();
        assert.equal(body.children.length, 1);
    }
    return { body, requests, warnings, start, cancel, finish, reply, button, openSignIn, timers, linkStatus, statusResponses };
}

async function fireTimers(f) {
    for (const [id, callback] of [...f.timers.entries()]) {
        f.timers.delete(id);
        callback();
    }
    await flush();
}

async function pasteWithHeldStatus() {
    const f = fixture('dropbox');
    await f.openSignIn();
    f.start.resolve(f.reply({ success: true, linkId: 'DUMMY_LINK', state: 'pending', mode: 'paste' }));
    await flush();
    f.button(1).onclick(); // Leave the actual finish request in flight.
    f.button(0).onclick();
    f.cancel.resolve(f.reply({ success: false, error: 'link_commit_started' }, 409));
    await flush();
    const status = deferred();
    f.statusResponses.push(status);
    await fireTimers(f);
    assert.equal(f.requests.filter(request => request.url.indexOf('/api/safekeep/link/status?') === 0).length, 1);
    return { f, status };
}

function destinationChecks(f) { return f.requests.filter(request => request.url === '/api/safekeep/dest/test').length; }

async function closeWithoutAnotherCancellation(f) {
    assert.equal(f.button(0).textContent, 'Close');
    f.button(0).onclick();
    await flush();
    assert.equal(f.body.children.length, 0, 'terminal sign-in can close without another cancellation');
    assert.equal(f.requests.filter(request => request.url === '/api/safekeep/link/cancel').length, 1);
}

async function run() {
    {
        const f = fixture();
        await f.openSignIn();
        f.start.resolve(f.reply({ success: true, linkId: 'DUMMY_LINK', state: 'pending', mode: 'device' }));
        await flush();
        f.button(0).onclick();
        assert.equal(f.body.children.length, 1, 'modal stays visible before cancellation is confirmed');
        f.cancel.resolve(f.reply({ success: true }));
        await flush();
        assert.equal(f.body.children.length, 0, 'confirmed cancellation closes modal');
        assert.equal(f.warnings.length, 0);
        console.log('UI PASS confirmed cancellation waits then closes');
    }
    {
        const f = fixture();
        await f.openSignIn();
        f.start.resolve(f.reply({ success: true, linkId: 'DUMMY_LINK', state: 'pending', mode: 'device' }));
        await flush();
        f.button(0).onclick();
        f.cancel.resolve(f.reply({ success: false, error: 'link_commit_started' }, 409));
        await flush();
        assert.equal(f.body.children.length, 1, 'too-late cancellation retains the modal');
        assert.equal(f.warnings.length, 1);
        assert.match(f.warnings[0].message, /already being saved/);
        assert.equal(f.warnings[0].type, 'warning');
        assert.ok(f.timers.size > 0, 'status polling remains scheduled');
        console.log('UI PASS commit conflict warns and retains status view');
    }
    {
        const f = fixture();
        await f.openSignIn();
        f.button(0).onclick(); // Close while start is still in flight, before an id exists.
        assert.equal(f.body.children.length, 0);
        f.start.resolve(f.reply({ success: true, linkId: 'DUMMY_LINK', state: 'pending', mode: 'device' }));
        await flush();
        assert.equal(f.requests.filter(r => r.url === '/api/safekeep/link/cancel').length, 1);
        f.cancel.resolve(f.reply({ success: false, error: 'link_commit_started' }, 409));
        await flush();
        assert.equal(f.warnings.length, 1, 'late-start caller inspects cancellation conflict');
        assert.match(f.warnings[0].message, /already being saved/);
        assert.ok(f.requests.filter(r => r.url === '/api/safekeep/config').length >= 2, 'storage view refresh requested');
        console.log('UI PASS late-start cancellation conflict is not ignored');
    }
    {
        const f = fixture();
        await f.openSignIn();
        f.start.resolve(f.reply({ success: true, linkId: 'DUMMY_LINK', state: 'pending', mode: 'device' }));
        await flush();
        f.button(0).onclick();
        f.cancel.reject(new Error('dummy offline result'));
        await flush();
        assert.equal(f.body.children.length, 1);
        assert.equal(f.warnings.length, 1);
        assert.match(f.warnings[0].message, /Could not confirm cancellation/);
        console.log('UI PASS transport failure does not imply cancellation');
    }
    {
        const f = fixture('dropbox');
        await f.openSignIn();
        f.start.resolve(f.reply({ success: true, linkId: 'DUMMY_LINK', state: 'pending', mode: 'paste' }));
        await flush();
        f.button(1).onclick();
        f.button(0).onclick();
        f.cancel.resolve(f.reply({ success: true }));
        await flush();
        assert.equal(f.body.children.length, 0);
        f.finish.resolve(f.reply({ success: false, state: 'expired' }));
        await flush();
        assert.equal(f.warnings.length, 0, 'cancelled paste response cannot revive a closed modal or report an error');
        console.log('UI PASS cancelled paste ignores its late finish response');
    }
    {
        const f = fixture('dropbox');
        await f.openSignIn();
        f.start.resolve(f.reply({ success: true, linkId: 'DUMMY_LINK', state: 'pending', mode: 'paste' }));
        await flush();
        f.button(1).onclick();
        f.finish.reject(new Error('dummy lost finish response'));
        await flush();
        const scheduledBeforeCancel = f.timers.size;
        f.button(0).onclick();
        f.cancel.resolve(f.reply({ success: false, error: 'link_commit_started' }, 409));
        await flush();
        assert.equal(f.body.children.length, 1);
        assert.equal(f.timers.size, scheduledBeforeCancel, 'conflict does not duplicate an existing status timer');
        assert.ok(f.warnings.some(warning => /already being saved/.test(warning.message)));
        f.linkStatus.state = 'done';
        f.linkStatus.destId = 'DUMMY_DESTINATION';
        for (const [id, callback] of [...f.timers.entries()]) {
            f.timers.delete(id);
            callback();
        }
        await flush();
        assert.equal(f.button(0).textContent, 'Close', 'status polling recovers the completed paste');
        f.button(0).onclick();
        assert.equal(f.body.children.length, 0, 'recovered paste modal can be dismissed');
        assert.equal(f.requests.filter(request => request.url === '/api/safekeep/link/cancel').length, 1);
        console.log('UI PASS lost paste response recovers through conflict status polling and closes');
    }
    {
        const { f, status } = await pasteWithHeldStatus();
        f.finish.resolve(f.reply({ success: true, destId: 'DUMMY_DESTINATION' }));
        await flush();
        assert.equal(destinationChecks(f), 1);
        status.resolve(f.reply({ state: 'pending' }));
        await flush();
        await closeWithoutAnotherCancellation(f);
        assert.equal(destinationChecks(f), 1, 'old pending response cannot repeat completion');
        console.log('UI PASS old pending status cannot overwrite a successful finish');
    }
    for (const lateFinish of ['success', 'http-error', 'transport-error']) {
        const { f, status } = await pasteWithHeldStatus();
        status.resolve(f.reply({ state: 'done', destId: 'DUMMY_DESTINATION' }));
        await flush();
        assert.equal(destinationChecks(f), 1);
        const warnings = f.warnings.length;
        if (lateFinish === 'success') f.finish.resolve(f.reply({ success: true, destId: 'DUMMY_DESTINATION' }));
        else if (lateFinish === 'http-error') f.finish.resolve(f.reply({ success: false, error: 'DUMMY_LATE_ERROR' }, 400));
        else f.finish.reject(new Error('dummy late transport failure'));
        await flush();
        assert.equal(destinationChecks(f), 1, 'late finish cannot repeat linked destination work');
        assert.equal(f.warnings.length, warnings, 'late finish cannot report an error after completion');
        await closeWithoutAnotherCancellation(f);
        console.log('UI PASS terminal done ignores late finish ' + lateFinish);
    }
    {
        const { f, status } = await pasteWithHeldStatus();
        status.resolve(f.reply({ state: 'error', error: 'DUMMY_TERMINAL_ERROR' }));
        await flush();
        f.finish.resolve(f.reply({ success: true, destId: 'DUMMY_DESTINATION' }));
        await flush();
        assert.equal(destinationChecks(f), 0, 'late finish cannot replace terminal error with success');
        await closeWithoutAnotherCancellation(f);
        console.log('UI PASS terminal error ignores late finish success');
    }
}

run().catch(error => { console.error(error); process.exitCode = 1; });
