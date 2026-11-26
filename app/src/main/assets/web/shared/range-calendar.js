/** Shared local-date range calendar for Trips, Charging, Notification Log and Events. */
var ODRangeCalendar = (function () {
    function dateKey(d) {
        var m = d.getMonth() + 1, day = d.getDate();
        return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
    }

    function keyToMs(key, endOfDay) {
        var p = key.split('-');
        if (p.length !== 3) return null;
        var y = parseInt(p[0], 10), mo = parseInt(p[1], 10) - 1, da = parseInt(p[2], 10);
        if (isNaN(y) || isNaN(mo) || isNaN(da)) return null;
        return (endOfDay ? new Date(y, mo, da, 23, 59, 59, 999) : new Date(y, mo, da, 0, 0, 0, 0)).getTime();
    }

    function render(grid, title, selectedMonth, lang, fromKey, toKey, onPick, options) {
        var year = selectedMonth.getFullYear(), month = selectedMonth.getMonth();
        var monthDate = new Date(year, month, 1);
        try { title.textContent = new Intl.DateTimeFormat(lang, { month: 'long' }).format(monthDate) + ' ' + year; }
        catch (e) { title.textContent = monthDate.toLocaleDateString(lang, { month: 'long' }) + ' ' + year; }
        var active = document.activeElement;
        var focusedKey = active && active.parentNode === grid && active.dataset
            ? active.dataset.date : null;
        grid.innerHTML = '';

        var dayFmt;
        try { dayFmt = new Intl.DateTimeFormat(lang, { year: 'numeric', month: 'long', day: 'numeric' }); }
        catch (e) { dayFmt = new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'long', day: 'numeric' }); }

        var wkFmt; try { wkFmt = new Intl.DateTimeFormat(lang, { weekday: 'short' }); } catch (e) { wkFmt = null; }
        for (var w = 0; w < 7; w++) {
            var dd = new Date(2024, 0, 7 + w); // Sunday-first headers.
            var el = document.createElement('div');
            el.className = 'calendar-weekday';
            el.textContent = wkFmt ? wkFmt.format(dd) : dd.toLocaleDateString(lang, { weekday: 'short' });
            grid.appendChild(el);
        }

        var firstDay = monthDate.getDay();
        var daysInMonth = new Date(year, month + 1, 0).getDate();
        var daysInPrev = new Date(year, month, 0).getDate();
        var todayKey = dateKey(new Date());
        // Preserve the existing day labels and local-date construction for all six weeks.
        for (var i = firstDay - 1; i >= 0; i--) {
            appendDay(daysInPrev - i, dateKey(new Date(year, month - 1, daysInPrev - i)), true);
        }
        for (var day = 1; day <= daysInMonth; day++) {
            appendDay(day, dateKey(new Date(year, month, day)), false);
        }
        var trailingDays = 42 - firstDay - daysInMonth;
        for (var d2 = 1; d2 <= trailingDays; d2++) {
            appendDay(d2, dateKey(new Date(year, month + 1, d2)), true);
        }

        function appendDay(day, key, otherMonth) {
            var el = document.createElement('div');
            el.className = 'calendar-day';
            el.textContent = day;
            el.dataset.date = key;
            if (otherMonth) el.classList.add('other-month');
            // Events highlights only the displayed month; range pickers also highlight padding days.
            if (!otherMonth || !options || !options.currentMonthHighlightsOnly) {
                if (key === todayKey) el.classList.add('today');
                if (key === fromKey || key === toKey) el.classList.add('selected');
                else if (fromKey && toKey && key > fromKey && key < toKey) el.classList.add('in-range');
            }
            el.setAttribute('role', 'button');
            el.setAttribute('aria-label', dayFmt.format(new Date(key + 'T00:00:00')));
            el.setAttribute('aria-pressed', el.classList.contains('selected') ? 'true' : 'false');
            if (key > todayKey) {
                el.classList.add('disabled');
                el.setAttribute('aria-disabled', 'true');
            } else {
                el.tabIndex = 0;
                el.addEventListener('click', function () { onPick(key); });
                el.addEventListener('keydown', function (event) {
                    if (event.key === 'Enter' || event.key === ' ' || event.keyCode === 13 || event.keyCode === 32) {
                        event.preventDefault();
                        if (!event.repeat) onPick(key);
                    }
                });
            }
            grid.appendChild(el);
            // Notification range selection rebuilds the grid after each pick.
            if (key === focusedKey && key <= todayKey) el.focus();
        }
    }

    return { dateKey: dateKey, keyToMs: keyToMs, render: render };
})();
