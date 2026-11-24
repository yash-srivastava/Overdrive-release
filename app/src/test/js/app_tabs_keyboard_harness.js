// Run with Node.js from any directory. Exercises the real shared controller
// with a minimal DOM model; native focus traversal still needs a browser check.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/app-tabs.js'), 'utf8');

function mount(hash, saved, direction) {
    const changes = [];
    const windowEvents = {};
    let focus;
    let scrolls = 0;
    let buttons = [];
    let stored = saved;
    function element() {
        const attrs = {};
        const classes = new Set();
        const handlers = {};
        return {
            style: {}, scrollWidth: 0, clientWidth: 0, tabIndex: 0,
            setAttribute(k, v) {
                attrs[k] = String(v);
                if (k === 'tabindex') this.tabIndex = Number(v);
            },
            getAttribute(k) { return attrs[k] === undefined ? null : attrs[k]; },
            removeAttribute(k) { delete attrs[k]; },
            classList: {
                add(k) { classes.add(k); }, remove(k) { classes.delete(k); },
                contains(k) { return classes.has(k); }
            },
            addEventListener(k, fn) { handlers[k] = fn; },
            click() { if (handlers.click) handlers.click({ currentTarget: this }); },
            focus() { focus = this; },
            keydown(key, mods) {
                const ev = Object.assign({ key, currentTarget: this, defaultPrevented: false,
                    preventDefault() { this.defaultPrevented = true; } }, mods);
                if (handlers.keydown) handlers.keydown(ev);
                return ev.defaultPrevented;
            },
            appendChild(child) { child.parentNode = this; },
            querySelector() { return null; },
            querySelectorAll() { return []; }
        };
    }
    const ids = ['general', 'schedule', 'history'];
    const panels = ids.map(id => {
        const el = element(); el.setAttribute('data-tab', id); return el;
    });
    const main = element();
    main.querySelectorAll = selector => selector === '[data-tab]' ? panels : [];
    const body = element();
    const nav = element();
    const slot = element();
    const apply = element();
    const footer = element();
    footer.querySelector = selector => selector === '#btnApply' ? apply : null;
    Object.defineProperty(nav, 'innerHTML', {
        set(html) {
            buttons = Array.from(html.matchAll(/<button\b([^>]*)>/g), match => {
                const btn = element();
                for (const attr of match[1].matchAll(/([\w-]+)="([^"]*)"/g)) {
                    btn.setAttribute(attr[1], attr[2]);
                    if (attr[1] === 'class') attr[2].split(' ').forEach(c => btn.classList.add(c));
                }
                btn.parentNode = nav;
                return btn;
            });
        }
    });
    nav.querySelectorAll = () => buttons;
    nav.querySelector = () => buttons.find(b => b.classList.contains('is-active'));
    const document = {
        readyState: 'complete', body, documentElement: element(),
        createElement(tag) { return tag === 'nav' ? nav : slot; },
        querySelector(sel) {
            return sel === '.main-content' ? main : sel === '.footer-bar' ? footer : null;
        },
        createEvent() { return { initCustomEvent(name, bubbles, cancelable, detail) { this.detail = detail; } }; },
        dispatchEvent(ev) { changes.push(ev.detail.id); }
    };
    const window = {
        OT_TABS: ids.map(id => ({ id, readOnly: id === 'schedule' })),
        location: { pathname: '/example.html', hash },
        localStorage: { getItem() { return stored; }, setItem(k, v) { stored = v; } },
        getComputedStyle() { return { direction }; },
        addEventListener(name, fn) { windowEvents[name] = fn; },
        scrollTo() { scrolls++; }
    };
    vm.runInNewContext(source, { document, window });
    function selected(index) {
        buttons.forEach((btn, i) => {
            const active = i === index;
            assert.strictEqual(btn.tabIndex, active ? 0 : -1, 'tabIndex: ' + ids[i]);
            assert.strictEqual(btn.getAttribute('aria-selected'), String(active));
            assert.strictEqual(btn.classList.contains('is-active'), active);
            assert.strictEqual(panels[i].getAttribute('hidden') === null, active);
        });
        assert.strictEqual(slot.classList.contains('is-hidden'), index === 1);
    }
    function press(from, key, to) {
        const count = changes.length;
        const priorScrolls = scrolls;
        buttons[from].focus();
        assert(buttons[from].keydown(key), 'Handled key: ' + key);
        selected(to);
        assert.strictEqual(focus, buttons[to]);
        assert.strictEqual(changes.length, count + (from === to ? 0 : 1));
        assert.strictEqual(scrolls, priorScrolls + (from === to ? 0 : 1));
        if (from !== to) {
            assert.strictEqual(changes[count], ids[to]);
            assert.strictEqual(stored, ids[to]);
        }
    }
    return { selected, press, buttons, changes, deepLink(id) {
        window.location.hash = '#' + id; windowEvents.hashchange();
    }, setDirection(value) { direction = value; } };
}

const ui = mount('#schedule', 'history', 'ltr');
ui.selected(1); // Hash wins over the saved History tab.
ui.press(1, 'ArrowRight', 2);
ui.press(2, 'ArrowRight', 0);
ui.press(0, 'ArrowLeft', 2);
ui.press(2, 'Home', 0);
ui.press(0, 'End', 2);
ui.press(2, 'End', 2); // No duplicate change event.
ui.setDirection('rtl');
ui.press(2, 'ArrowRight', 1);
ui.press(1, 'ArrowLeft', 2);
ui.press(2, 'ArrowLeft', 0);
ui.press(0, 'Home', 0);
ui.setDirection('ltr');
for (const key of ['Tab', 'Enter', ' ', 'ArrowDown', 'ArrowRight']) {
    assert.strictEqual(ui.buttons[0].keydown(key, { ctrlKey: key === 'ArrowRight' }), false);
    ui.selected(0);
}
ui.buttons[1].click();
ui.selected(1);
ui.deepLink('history');
ui.selected(2);
assert.strictEqual(ui.changes[ui.changes.length - 1], 'history');
mount('', 'history', 'ltr').selected(2);
mount('', null, 'ltr').selected(0);
console.log('PASS: tab focus, LTR/RTL navigation, selection, persistence, events and native key handling');
