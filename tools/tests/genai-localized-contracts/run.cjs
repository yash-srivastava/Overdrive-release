'use strict';
// Loads the complete shipped script. All UI/network collaborators are local fakes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
const script = fs.readFileSync(path.join(root, 'app/src/main/assets/web/shared/genai.js'), 'utf8');
function element(tag = 'div') {
  return {tag, children: [], textContent: '', style: {}, listeners: {},
    appendChild(child) { this.children.push(child); return child; },
    insertBefore(child) { this.children.unshift(child); },
    addEventListener(type, fn) { this.listeners[type] = fn; },
    setAttribute() {}, get firstChild() { return this.children[0]; }};
}
function text(node) { return [node.textContent, ...node.children.map(text)].join(' '); }
async function load(locale) {
  const calls = [];
  const catalog = language => fs.readFileSync(path.join(root,
    `app/src/main/assets/web/i18n/${language}.json`), 'utf8');
  const input = {value: 'Inspect automation', style: {}};
  const context = {window: null, document: {createElement: element,
    getElementById(id) { return id === 'genAiInput' ? input : null; },
    readyState: 'loading', querySelectorAll: () => [], addEventListener() {},
    documentElement: {setAttribute() {}}},
    BYD: {}, AndroidBridge: {getAppLocale: () => locale, getI18nCatalog: catalog},
    localStorage: {getItem: () => null, setItem() {}}, navigator: {language: locale},
    console, Promise};
  context.window = context;
  const core = fs.readFileSync(path.join(root, 'app/src/main/assets/web/shared/core.js'), 'utf8');
  const start = core.indexOf('BYD.i18n = (function () {');
  const end = core.indexOf('\n})();', start) + '\n})();'.length;
  assert.ok(start >= 0 && end > start, 'complete i18n module must be present');
  vm.runInNewContext(core.slice(start, end), context, {filename: 'core-i18n.js', timeout: 1000});
  await context.BYD.i18n.init();
  const translate = context.BYD.i18n.t;
  vm.runInNewContext(script, context, {filename: 'genai.js', timeout: 1000});
  const ai = context.GenAI;
  ai.renderRichText = (node, value) => { node.textContent = value; };
  ai.renderMessages = () => {};
  ai.renderStatus = () => {};
  ai.newRequestId = () => 'dummy-request';
  ai.toast = () => {};
  ai.openChatStream = (...args) => calls.push(args);
  return {ai, translate, calls, i18n: context.BYD.i18n};
}
let assertions = 0;
function check(actual, expected) { assert.equal(actual, expected); assertions++; }
async function main() {
for (const locale of ['en', 'fr', 'he']) {
  const {ai, translate, calls, i18n} = await load(locale);
  for (const key of ['draft_saved_manual', 'confirm_run', 'share_once', 'voice_request',
    'no_community', 'community_found_one', 'community_found']) {
    assert.notEqual(translate('genai.' + key), 'genai.' + key, `${locale}: missing ${key}`);
    assertions++;
  }
  check(translate('genai.__missing_test_key'), 'genai.__missing_test_key');
  const draft = ai.automationDraftCard({saved: true, automation: {name: 'Dummy'}});
  check(text(draft).includes(translate('genai.draft_saved_manual')), true);
  const action = ai.actionProposalCard({type: 'automation', automationName: 'Dummy'});
  check(text(action).includes(translate('genai.confirm_run')), true);
  const context = ai.contextRequestCard({mode: 'automation_diagnostics', query: 'Dummy query', resolved: true});
  check(text(context).includes(translate('genai.share_once')), true);
  check(text(context).includes(translate('genai.voice_request', {query: 'Dummy query'})), true);
  for (const count of [0, 1, 2]) {
    const host = element();
    ai.communityCards = () => element();
    ai.appendMessage(host, {role: 'assistant', mode: 'community_search',
      communityResults: Array.from({length: count}, () => ({}))}, false);
    const expected = translate(count === 0 ? 'genai.no_community' : count === 1
      ? 'genai.community_found_one' : 'genai.community_found', {count});
    check(text(host).includes(expected), true);
  }
  ai.status = {enabled: true, configured: true};
  ai.selectedMode = 'automation_diagnostics';
  ai.send();
  check(calls.length, 1);
  check(calls[0][1], 'automation_diagnostics');
  check(calls[0][0][0].content, 'Inspect automation');
  check(ai.messages[ai.messages.length - 1].pending, true);
  await i18n.setLang(locale === 'he' ? 'en' : 'he', {persist: false});
  const changed = ai.automationDraftCard({saved: true, automation: {name: 'Dummy'}});
  check(text(changed).includes(translate('genai.draft_saved_manual')), true);
  check(i18n.getLang(), locale === 'he' ? 'en' : 'he');
}
console.log(`PASS ${assertions} GenAI localized rendering and mode-routing assertions`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
