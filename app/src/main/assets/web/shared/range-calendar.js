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
        grid.innerHTML = '';

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
            var today = new Date(); today.setHours(0, 0, 0, 0);
            if (new Date(key + 'T00:00:00') > today) el.classList.add('disabled');
            else el.addEventListener('click', function () { onPick(key); });
            grid.appendChild(el);
        }
    }

    return { dateKey: dateKey, keyToMs: keyToMs, render: render };
})();
