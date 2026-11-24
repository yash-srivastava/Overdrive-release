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
        classList: { add: name => classes.add(name), contains: name => classes.has(name) },
        set innerHTML(value) { this.children.length = 0; },
        appendChild(child) { this.children.push(child); },
        addEventListener() {}
    };
}

const grid = element();
const title = element();
const BYD = { i18n: { getLang: () => 'en-US' } };
const sandbox = {
    BYD,
    window: { BYD },
    document: {
        getElementById: id => id === 'calendarGrid' ? grid : id === 'calendarTitle' ? title : null,
        createElement: element
    }
};
vm.createContext(sandbox);
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
console.log('events_calendar_navigation_harness: 8 scenarios passed');
