'use strict';
// Browser-free regression for Service Mode's dynamic translations and state/UI
// refresh on delayed catalog load and language switch. Run: node run.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../');
const web = path.join(root, 'app/src/main/assets/web');
const en = require(path.join(web, 'i18n/en.json'));
const he = require(path.join(web, 'i18n/he.json'));

class Element {
    constructor() {
        this._textContent = '';
        this.hidden = false;
        this.disabled = false;
        this.style = {};
        this.children = [];
        this.value = '';
        this.dataset = {};
    }
    get textContent() { return this._textContent; }
    set textContent(value) { this._textContent = value; this.children = []; }
    addEventListener() {}
    appendChild(element) { this.children.push(element); }
    setAttribute(name, value) { this.dataset[name] = value; }
    getAttribute(name) { return this.dataset[name]; }
    querySelectorAll() { return []; }
}
(async () => {
    const elements = {};
    const listeners = {};
    let locale = 'unloaded';
    const status = {
        success: true, enabled: true, active: true, restoring: false,
        endsAtEpochMs: Date.UTC(2026, 10, 25), timeZoneId: 'Asia/Jerusalem',
        serverLocalDate: '2026-11-19', hiddenPackages: ['com.example.music'],
        disabledFeatures: ['automations'], lastError: ''
    };
    const sandbox = {
        window: {
            BYD: null,
            setInterval() { return 1; },
            clearInterval() {}
        },
        document: {
            getElementById(id) { return elements[id] || (elements[id] = new Element()); },
            createElement() { return new Element(); },
            addEventListener(event, fn) { listeners[event] = fn; }
        },
        fetch: async (endpoint) => ({
            ok: true,
            json: async () => endpoint.endsWith('/status') ? status : {
                success: true, apps: [{ package: 'com.example.music', label: 'Music' }]
            }
        }),
        console,
        Set,
        Date
    };
    let onLocaleChange;
    sandbox.BYD = sandbox.window.BYD = {
        i18n: {
            t(key) {
                if (locale === 'unloaded') return key;
                return key.split('.').reduce((obj, segment) => obj && obj[segment],
                    locale === 'he' ? he : en) || key;
            },
            onChange(fn) { onLocaleChange = fn; }
        }
    };
    vm.runInNewContext(fs.readFileSync(path.join(web, 'shared/service-mode.js'), 'utf8'), sandbox);
    assert.equal(typeof listeners.DOMContentLoaded, 'function');
    listeners.DOMContentLoaded();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(elements.smStatusTitle.textContent, 'Service Mode is active');
    assert.equal(elements.smRecoveryHint.hidden, false);
    assert.equal(elements.smActiveDetails.children.length, 2);
    locale = 'he';
    onLocaleChange();
    assert.equal(elements.smStatusTitle.textContent, he.service_mode.status_active);
    assert.equal(elements.smActiveDetails.children.length, 2);
    assert.ok(elements.smActiveDetails.children[1].textContent.startsWith(
        he.service_mode.paused_feature.split('{feature}')[0]));
    assert.equal(elements.smApps.children.length, 1);
    locale = 'en';
    onLocaleChange();
    assert.equal(elements.smStatusTitle.textContent, en.service_mode.status_active);
    assert.equal(elements.smRecoveryHint.hidden, false);
    console.log('PASS service-mode dynamic localization + state restoration rendering');
})().catch(e => { console.error(e); process.exitCode = 1; });
