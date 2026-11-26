'use strict';

// Run with: node app/src/test/js/date_picker_calendar_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

function fakeElement() {
    const classes = new Set();
    return {
        children: [],
        dataset: {},
        handlers: {},
        classList: {
            add: name => classes.add(name),
            contains: name => classes.has(name)
        },
        set innerHTML(value) { this.children.length = 0; },
        appendChild(child) { this.children.push(child); },
        addEventListener(type, handler) { this.handlers[type] = handler; }
    };
}

function loadCalendar(file, symbol, gridId, titleId) {
    const grid = fakeElement();
    const title = fakeElement();
    const BYD = { i18n: { getLang: () => 'en' } };
    const sandbox = {
        BYD,
        window: { BYD },
        document: {
            getElementById: id => id === gridId ? grid : id === titleId ? title : null,
            createElement: fakeElement,
            addEventListener() {}
        },
        console
    };
    const source = fs.readFileSync(path.resolve(__dirname,
        '../../main/assets/web/shared', file), 'utf8');
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(path.resolve(__dirname,
        '../../main/assets/web/shared/range-calendar.js'), 'utf8'), sandbox,
        { filename: 'range-calendar.js' });
    vm.runInContext(source + '\n;this.calendar = ' + symbol + ';', sandbox, { filename: file });
    return { calendar: sandbox.calendar, grid };
}

const trips = loadCalendar('trips.js', 'TRIPS', 'calendarGrid', 'calendarTitle');
// Trip dots require a separate API request and are unrelated to grid layout.
trips.calendar.loadCalendarDots = () => {};
const charging = loadCalendar('charging.js', 'CHARGING', 'chargeCalendarGrid', 'chargeCalendarTitle');
const notifications = loadCalendar('notif-log.js', 'NOTIFLOG', 'nlCalendarGrid', 'nlCalendarTitle');

for (const [name, environment, render] of [
    ['Trips', trips, 'renderCalendar'],
    ['Charging', charging, '_renderCalendar'],
    ['Notifications', notifications, '_renderCalendar']
]) {
    const { calendar, grid } = environment;
    for (const [year, month, first, last, currentDays] of [
        [2015, 1, '2015-02-01', '2015-03-14', 28], // Sunday-start non-leap February
        [2024, 1, '2024-01-28', '2024-03-09', 29], // Leap February
        [2021, 4, '2021-04-25', '2021-06-05', 31], // Saturday-start 31-day month
        [2024, 11, '2024-12-01', '2025-01-11', 31] // Year boundary
    ]) {
        const label = `${name} ${year}-${month + 1}`;
        calendar._calMonth = new Date(year, month, 1);
        calendar[render]();
        assert.strictEqual(grid.children.length, 49, label + ': 7 headings and 42 dates');
        assert.ok(grid.children.slice(0, 7).every(cell => cell.className === 'calendar-weekday'));
        const dates = grid.children.slice(7);
        assert.ok(dates.every(cell => cell.className === 'calendar-day'));
        assert.strictEqual(dates[0].dataset.date, first, label + ': first date');
        assert.strictEqual(dates[41].dataset.date, last, label + ': last date');
        assert.strictEqual(dates.filter(cell => !cell.classList.contains('other-month')).length,
            currentDays, label + ': current-month days');
        const start = new Date(first + 'T00:00:00');
        dates.forEach((cell, index) => {
            const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + index);
            assert.strictEqual(cell.dataset.date, calendar._dateKey(date), label + ': consecutive dates');
        });
    }

    // All three adapters render their own range state and deliver the picked key.
    calendar._calMonth = new Date(2024, 1, 1);
    const draft = name === 'Notifications';
    calendar[draft ? '_draftFrom' : '_calFromKey'] = '2024-02-10';
    calendar[draft ? '_draftTo' : '_calToKey'] = '2024-02-12';
    let picked = null;
    calendar._calPick = key => { picked = key; };
    calendar[render]();
    const cell = key => grid.children.find(el => el.dataset.date === key);
    assert.ok(cell('2024-02-10').classList.contains('selected'), name);
    assert.ok(cell('2024-02-11').classList.contains('in-range'), name);
    assert.ok(cell('2024-02-12').classList.contains('selected'), name);
    cell('2024-02-11').handlers.click();
    assert.strictEqual(picked, '2024-02-11', name);
    // Existing range callers still highlight adjacent-month endpoints and interior dates.
    calendar[draft ? '_draftFrom' : '_calFromKey'] = '2024-01-28';
    calendar[draft ? '_draftTo' : '_calToKey'] = '2024-02-02';
    calendar[render]();
    assert.ok(cell('2024-01-28').classList.contains('other-month'), name);
    assert.ok(cell('2024-01-28').classList.contains('selected'), name);
    assert.ok(cell('2024-01-29').classList.contains('in-range'), name);
    assert.ok(cell('2024-02-02').classList.contains('selected'), name);
    assert.strictEqual(calendar._keyToMs('2024-02-29', false), new Date(2024, 1, 29).getTime());
    assert.strictEqual(calendar._keyToMs('2024-02-29', true), new Date(2024, 1, 29, 23, 59, 59, 999).getTime());
    assert.strictEqual(calendar._keyToMs('invalid', false), null);
    calendar._calMonth = new Date(new Date().getFullYear() + 2, 0, 1);
    calendar[render]();
    assert.ok(grid.children.slice(7).every(el => el.classList.contains('disabled') && !el.handlers.click), name);
}
console.log('date_picker_calendar_harness: ALL CHECKS PASSED');
