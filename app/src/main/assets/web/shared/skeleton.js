/**
 * BYD Champ - First-load placeholders
 *
 * A cover fills its host box; the host carries `is-sk` in the markup so its
 * value is hidden from the first paint, before this file has run.
 * Resolving is one-way. ES5 only - Chrome 58 WebView.
 */
(function () {
    'use strict';

    window.BYD = window.BYD || {};

    var FAILSAFE_MS = 8000;

    BYD.skeleton = {
        _resolved: {},

        isResolved: function (group) {
            return this._resolved[group] === true;
        },

        show: function (group) {
            if (this._resolved[group]) return;
            each('[data-sk-group="' + group + '"]', function (el) {
                el.style.display = '';
                setCoverHost(el, true);
            });
            each('[data-sk-real="' + group + '"]', function (el) { el.style.display = 'none'; });
        },

        resolve: function (group) {
            this._resolved[group] = true;
            each('[data-sk-group="' + group + '"]', function (el) {
                el.style.display = 'none';
                setCoverHost(el, false);
            });
            each('[data-sk-real="' + group + '"]', function (el) { el.style.display = ''; });
        },

        resolveAll: function () {
            var self = this;
            var seen = {};
            each('[data-sk-group]', function (el) {
                seen[el.getAttribute('data-sk-group')] = true;
            });
            each('[data-sk-real]', function (el) {
                seen[el.getAttribute('data-sk-real')] = true;
            });
            for (var group in seen) {
                if (seen.hasOwnProperty(group)) self.resolve(group);
            }
        }
    };

    function each(selector, fn) {
        var nodes;
        try {
            nodes = document.querySelectorAll(selector);
        } catch (e) {
            return;
        }
        for (var i = 0; i < nodes.length; i++) fn(nodes[i]);
    }

    function setCoverHost(el, on) {
        if (!el || !el.className || el.className.indexOf('sk-cover') === -1) return;
        var host = el.parentNode;
        if (!host || !host.classList) return;
        if (on) host.classList.add('is-sk');
        else host.classList.remove('is-sk');
    }

    // A failed or never-wired fetch must not leave a value hidden behind its placeholder.
    setTimeout(function () { BYD.skeleton.resolveAll(); }, FAILSAFE_MS);
})();
