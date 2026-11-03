'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
const script = fs.readFileSync(path.join(root, 'app/src/main/assets/web/shared/communicate.js'), 'utf8');
let assertions = 0;
function check(actual, expected) { assert.equal(actual, expected); assertions++; }
function setup(translation) {
  let now = 0;
  let ticker;
  const events = {}, counters = {socket: 0, context: 0, tracks: 0, disconnect: 0, timer: 0, cabin: 0};
  const sent = [], states = [];
  const context = {window: null, document: {hidden: false, body: {},
    addEventListener(name, callback) { events[name] = callback; }},
    BYD: {i18n: {t: key => translation === 'translated' ? 'Translated:' + key : key}},
    CabinAudio: {setLocalPaused() {}, stop() { counters.cabin++; }},
    WebSocket: {OPEN: 1}, Date: {now: () => now}, console,
    setInterval(callback) { ticker = callback; return 17; },
    clearInterval(id) { check(id, 17); counters.timer++; },
    addEventListener(name, callback) { events[name] = callback; }};
  context.window = context;
  vm.runInNewContext(script, context, {filename: 'communicate.js', timeout: 1000});
  const page = context.CommunicatePage;
  page.setTalkState = (...state) => states.push(state);
  page.renderElapsed = () => {};
  page.setMeter = value => check(value, 0);
  page.startStatusPolling = () => {};
  page.stopStatusPolling = () => {};
  page.pressing = page.live = true;
  page.generation = 10;
  page.socket = {readyState: 1, send(message) { sent.push(JSON.parse(message)); }, close() { counters.socket++; }};
  page.audioContext = {close() { counters.context++; return Promise.resolve(); }};
  page.stream = {getTracks: () => [{stop() { counters.tracks++; }}, {stop() { counters.tracks++; }}]};
  for (const field of ['processorNode', 'sourceNode', 'silentGain']) {
    page[field] = {disconnect() { counters.disconnect++; }};
  }
  page.startedAt = page.lastAudioAt = 0;
  page.startTalkTicker();
  return {page, context, counters, events, sent, states,
    tick(time) { now = time; ticker(); }};
}
function cleaned(fixture) {
  const {page, counters} = fixture;
  check(page.live, false); check(page.pressing, false); check(page.generation, 11);
  check(page.socket, null); check(page.stream, null); check(page.audioContext, null);
  check(page.processorNode, null); check(page.sourceNode, null); check(page.silentGain, null);
  check(page.tickTimer, null);
  check(counters.socket, 1); check(counters.context, 1); check(counters.tracks, 2);
  check(counters.disconnect, 3); check(counters.timer, 1);
}
for (const translation of ['translated', 'fallback']) {
  for (const [time, key, fallback] of [[30000, 'communicate.limit_30s', '30 second limit reached'],
    [3000, 'communicate.mic_inactive', 'Microphone stream became inactive']]) {
    const fixture = setup(translation);
    fixture.tick(time);
    cleaned(fixture);
    check(fixture.sent[0].type, 'stop');
    check(fixture.sent[0].reason, translation === 'translated' ? 'Translated:' + key : fallback);
  }
}
for (const event of ['visibilitychange', 'pagehide', 'beforeunload']) {
  const fixture = setup('translated');
  fixture.page.bindLifecycle();
  fixture.context.document.hidden = event === 'visibilitychange';
  fixture.events[event]();
  cleaned(fixture);
  check(fixture.counters.cabin, 1);
}
const active = setup('translated');
active.tick(2999);
check(active.page.live, true); check(active.counters.socket, 0); check(active.counters.tracks, 0);
active.page.live = false;
active.tick(30000);
check(active.counters.socket, 0);
console.log(`PASS ${assertions} localized timer and lifecycle cleanup assertions`);
