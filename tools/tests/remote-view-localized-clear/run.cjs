'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
const script = fs.readFileSync(path.join(root, 'app/src/main/assets/web/shared/remote-dev-view.js'), 'utf8');
let assertions = 0;
function check(actual, expected) { assert.equal(actual, expected); assertions++; }
async function scenario(translated) {
  const nodes = {}, events = {}, sockets = [], loaders = [], revoked = [], requests = [];
  let cleared = 0, drawn = 0, nextUrl = 0;
  const classList = {add() {}, remove() {}, contains: () => false};
  const node = id => nodes[id] ||= {style: {}, classList, textContent: '', width: 960, height: 540,
    listeners: {}, addEventListener(name, callback) { this.listeners[name] = callback; },
    setAttribute() {}, focus() {}, blur() {}, getContext: () => ({
      clearRect() { cleared++; }, drawImage() { drawn++; }})};
  class Socket {
    constructor(url, protocols) { this.url = url; this.protocols = protocols; this.closes = 0; sockets.push(this); }
    close(code, reason) { check(code, 1000); this.closes++; }
  }
  class FrameImage {
    constructor() { this.naturalWidth = 960; this.naturalHeight = 540; loaders.push(this); }
    set src(value) { this.url = value; }
  }
  const context = {window: null, document: {hidden: false,
    getElementById: node, body: {classList},
    addEventListener(name, callback) { events[name] = callback; }},
    BYDAuth: {requireAuth: () => true, getToken: () => 'dummy-jwt',
      fetch(url, options) { requests.push({url, options}); return Promise.resolve({ok: true,
        json: () => Promise.resolve({success: true, session: 'dummy-capability', activityReady: true})}); }},
    BYD: {i18n: {t: key => translated ? 'Translated:' + key : key, init() {}}},
    WebSocket: Socket, Image: FrameImage, Blob,
    URL: {createObjectURL() { return 'blob:dummy-' + (++nextUrl); }, revokeObjectURL(url) { revoked.push(url); }},
    location: {protocol: 'https:', host: 'dummy.invalid'}, Promise, Date, console,
    setTimeout() { return 42; }, clearTimeout() {},
    addEventListener(name, callback) { events[name] = callback; }};
  context.window = context;
  vm.runInNewContext(script, context, {filename: 'remote-dev-view.js', timeout: 1000});
  check(sockets.length, 0); // Authentication alone does not start a session.
  nodes.startButton.listeners.click();
  for (let i = 0; i < 8; i++) await Promise.resolve();
  check(requests.length, 1); check(requests[0].url, '/api/dev-view/session');
  check(requests[0].options.method, 'POST');
  check(sockets.length, 1);
  check(sockets[0].protocols[1], 'dummy-capability');
  check(sockets[0].url.includes('dummy-capability'), false);
  const sendFrame = socket => socket.onmessage({data: new Blob(['dummy-frame'])});
  sendFrame(sockets[0]); loaders[0].onload();
  check(drawn, 1); check(nodes.remoteFrame.style.display, 'block');
  sockets[0].onmessage({data: JSON.stringify({type: 'frame', success: false, pixelCopyResult: 'LOCKED'})});
  check(nodes.remoteFrame.style.display, 'none');
  check(nodes.framePlaceholder.style.display, 'block');
  check(nodes.framePlaceholder.textContent, translated ? 'Translated:remote_dev.unlock_resume'
    : 'Unlock the physical Overdrive UI to resume.');
  check(revoked.includes('blob:dummy-1'), true);
  sendFrame(sockets[0]); // Decode pending when the tab is hidden.
  const beforeHide = drawn;
  const clearBeforeHide = cleared;
  context.document.hidden = true;
  events.visibilitychange();
  check(sockets[0].closes, 1); check(sockets[0].onmessage, null);
  check(cleared, clearBeforeHide + 1);
  check(nodes.framePlaceholder.textContent, translated ? 'Translated:remote_dev.resume_tab'
    : 'Return to this tab to resume the live view.');
  loaders[1].onload();
  check(drawn, beforeHide); check(revoked.includes('blob:dummy-2'), true);
  check(nodes.remoteFrame.style.display, 'none');
  context.document.hidden = false;
  events.visibilitychange();
  check(sockets.length, 2); check(sockets[1].protocols[1], 'dummy-capability');
}
(async () => {
  await scenario(false); await scenario(true);
  console.log(`PASS ${assertions} localized frame clearing and stale-decode assertions`);
})().catch(error => { console.error(error); process.exitCode = 1; });
