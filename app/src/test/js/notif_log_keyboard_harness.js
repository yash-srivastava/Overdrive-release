'use strict';

// Run with: node app/src/test/js/notif_log_keyboard_harness.js
// Exercise the real card handlers with a minimal DOM stub; native button
// activation is left to the browser and is not simulated here.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

function element() {
    return {
        handlers: {},
        setAttribute() {},
        addEventListener(type, handler) { this.handlers[type] = handler; }
    };
}

const sandbox = {
    window: {},
    document: {
        createElement() {
            const card = element();
            const button = element();
            card.querySelector = selector => selector === '[data-del]' ? button : null;
            return card;
        }
    }
};
const source = path.resolve(__dirname, '../../main/assets/web/shared/notif-log.js');
vm.runInNewContext(fs.readFileSync(source, 'utf8'), sandbox, { filename: source });
const log = sandbox.window.NOTIFLOG;
const notification = { id: 42, title: 'Recording', url: '/events.html?file=clip.mp4' };
let opened = 0;
let deleted = 0;
log._open = n => { assert.strictEqual(n, notification); opened++; };
log.deleteOne = id => { assert.strictEqual(id, notification.id); deleted++; };
const card = log._buildCard(notification);
const button = card.querySelector('[data-del]');

function keydown(target, key, keyCode) {
    const event = {
        target, currentTarget: card, key, keyCode, defaultPrevented: false,
        preventDefault() { this.defaultPrevented = true; }
    };
    card.handlers.keydown(event);
    return event.defaultPrevented;
}

for (const [key, keyCode] of [['Enter', 13], [' ', 32], [undefined, 13], [undefined, 32]]) {
    assert.strictEqual(keydown(button, key, keyCode), false, 'nested button retains native activation');
    assert.strictEqual(opened, 0, 'nested button must not open the card');
}
for (const [key, keyCode] of [['Enter', 13], [' ', 32], [undefined, 13], [undefined, 32]]) {
    const previous = opened;
    assert.strictEqual(keydown(card, key, keyCode), true, 'card activation prevents default');
    assert.strictEqual(opened, previous + 1, 'focused card opens once');
}
assert.strictEqual(keydown(card, 'Tab', 9), false, 'unrelated key retains default');
assert.strictEqual(opened, 4);

let stopped = false;
button.handlers.click({ stopPropagation() { stopped = true; } });
assert.strictEqual(stopped, true, 'delete click does not bubble to the card');
assert.strictEqual(deleted, 1);
assert.strictEqual(opened, 4);
card.handlers.click();
assert.strictEqual(opened, 5, 'card click still opens the notification');
console.log('notif_log_keyboard_harness: all checks passed');
