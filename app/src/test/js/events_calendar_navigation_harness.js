'use strict';

// Run with: node app/src/test/js/events_calendar_navigation_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

function element() {
    const classes = new Set();
    return {
        children: [],
        dataset: {},
        handlers: {},
        classList: {
            add: name => classes.add(name),
            contains: name => classes.has(name),
            toggle: (name, force) => force ? classes.add(name) : classes.delete(name)
        },
        set innerHTML(value) { this.children.length = 0; },
        appendChild(child) { this.children.push(child); },
        addEventListener(type, handler) { this.handlers[type] = handler; }
    };
}

const grid = element();
// Model a DOM collection without an iterator while retaining array helpers for assertions.
grid.children[Symbol.iterator] = undefined;
const title = element();
const BYD = { i18n: { getLang: () => 'en-US' } };
// Freeze today so the adjacent-month and disabled-day cases are deterministic.
class CalendarDate extends Date {
    constructor(...args) { super(...(args.length ? args : [2024, 2, 1, 12])); }
}
const sandbox = {
    Date: CalendarDate,
    BYD,
    window: { BYD },
    document: {
        getElementById: id => id === 'calendarGrid' ? grid : id === 'calendarTitle' ? title : null,
        createElement: element,
        querySelectorAll: () => grid.children.filter(cell => cell.className === 'calendar-day')
    }
};
vm.createContext(sandbox);
const helper = path.resolve(__dirname, '../../main/assets/web/shared/range-calendar.js');
vm.runInContext(fs.readFileSync(helper, 'utf8'), sandbox, { filename: helper });
const file = path.resolve(__dirname, '../../main/assets/web/shared/events.js');
vm.runInContext(fs.readFileSync(file, 'utf8'), sandbox, { filename: file });
const events = BYD.events;

// Months use JavaScript's zero-based numbering.
const cases = [
    ['non-leap March 31 back', 2023, 2, 31, 'prevMonth', 2023, 1, 28],
    ['leap March 31 back', 2024, 2, 31, 'prevMonth', 2024, 1, 29],
    ['January 31 forward', 2023, 0, 31, 'nextMonth', 2023, 1, 28],
    ['May 31 back', 2024, 4, 31, 'prevMonth', 2024, 3, 30],
    ['August 31 forward', 2024, 7, 31, 'nextMonth', 2024, 8, 30],
    ['December to January', 2023, 11, 31, 'nextMonth', 2024, 0, 31],
    ['January to December', 2024, 0, 31, 'prevMonth', 2023, 11, 31],
    ['ordinary mid-month', 2024, 5, 15, 'nextMonth', 2024, 6, 31]
];

for (const [label, y, m, d, method, targetY, targetM, days] of cases) {
    events.currentDate = new Date(y, m, d, 12);
    events.selectedDate = '2023-01-15';
    events.currentPage = 3;
    events[method]();
    assert.strictEqual(events.currentDate.getFullYear(), targetY, label + ': year');
    assert.strictEqual(events.currentDate.getMonth(), targetM, label + ': month');
    assert.strictEqual(events.currentDate.getDate(), 1, label + ': anchor');
    const expectedTitle = new Intl.DateTimeFormat('en-US', { month: 'long' })
        .format(new Date(targetY, targetM, 1)) + ' ' + targetY;
    assert.strictEqual(title.textContent, expectedTitle, label + ': heading');
    assert.strictEqual(grid.children.length, 49, label + ': seven headings and 42 days');
    const current = grid.children.filter(cell => cell.className === 'calendar-day' &&
        !cell.classList.contains('other-month'));
    assert.strictEqual(current.length, days, label + ': current-month dates');
    assert.strictEqual(current[0].dataset.date,
        events.formatDateKey(new Date(targetY, targetM, 1)), label + ': first day');
    assert.strictEqual(current[days - 1].dataset.date,
        events.formatDateKey(new Date(targetY, targetM, days)), label + ': last day');
    assert.strictEqual(events.selectedDate, '2023-01-15', label + ': selected filter unchanged');
    assert.strictEqual(events.currentPage, 3, label + ': pagination unchanged');

    // Reversing direction must return to the original month, without rollover drift.
    events[method === 'prevMonth' ? 'nextMonth' : 'prevMonth']();
    assert.strictEqual(events.currentDate.getFullYear(), y, label + ': reverse year');
    assert.strictEqual(events.currentDate.getMonth(), m, label + ': reverse month');
}
// February 2024 has January and March padding, including the frozen today.
events.currentDate = new Date(2024, 1, 1);
events.selectedDate = '2024-01-28';
events.datesWithRecordings.set('2024-01-28', { count: 1, hasSentry: true });
events.datesWithRecordings.set('2024-02-29', { count: 1, hasSentry: false });
events.datesWithRecordings.set('2024-03-02', { count: 1, hasSentry: true });
events.renderCalendar();
const cell = key => grid.children.find(el => el.dataset.date === key);
assert.ok(cell('2024-01-28').classList.contains('other-month'));
assert.ok(!cell('2024-01-28').classList.contains('selected'));
assert.ok(cell('2024-01-28').classList.contains('has-recordings'));
assert.ok(cell('2024-01-28').classList.contains('has-sentry'));
assert.ok(!cell('2024-03-01').classList.contains('today'));
assert.ok(cell('2024-02-29').classList.contains('has-recordings'));
assert.ok(!cell('2024-02-29').classList.contains('has-sentry'));
assert.ok(cell('2024-03-02').classList.contains('disabled'));
assert.ok(!cell('2024-03-02').classList.contains('has-recordings'));
assert.ok(!cell('2024-03-02').classList.contains('has-sentry'));
assert.ok(!cell('2024-03-02').handlers.click);
assert.ok(cell('2024-03-01').handlers.click);

// A click still toggles the single-date filter, resets pagination and refreshes the list.
const calls = [];
for (const method of ['updateRecordingsTitle', 'updateCalendarButton', 'loadRecordings', 'closeCalendar']) {
    events[method] = () => calls.push(method);
}
events.currentPage = 3;
cell('2024-02-29').handlers.click();
assert.strictEqual(events.selectedDate, '2024-02-29');
assert.strictEqual(events.currentPage, 1);
assert.ok(cell('2024-02-29').classList.contains('selected'));
assert.deepStrictEqual(calls, ['updateRecordingsTitle', 'updateCalendarButton', 'loadRecordings', 'closeCalendar']);
events.renderCalendar();
assert.ok(cell('2024-02-29').classList.contains('selected'));
assert.ok(!grid.children.some(el => el.classList.contains('in-range')));
cell('2024-02-29').handlers.click();
assert.strictEqual(events.selectedDate, null);
assert.ok(!cell('2024-02-29').classList.contains('selected'));
cell('2024-01-28').handlers.click();
assert.strictEqual(events.selectedDate, '2024-01-28');
events.renderCalendar();
assert.ok(!cell('2024-01-28').classList.contains('selected'));

events.nextMonth();
assert.ok(cell('2024-03-01').classList.contains('today'));
assert.ok(!cell('2024-03-01').classList.contains('disabled'));
console.log('events_calendar_navigation_harness: ALL CHECKS PASSED');
