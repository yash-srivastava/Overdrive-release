/*
 * Shared driver-cluster projection settings, used by any page that can drive the
 * cluster (Automations "Move app to display", Key mapping, and — canonically — the
 * blind-spot + map tabs on the RoadSense page).
 *
 * There is ONE cluster-size source of truth: blindspot.clusterSizeProfile in the
 * unified config. The OEM projection (ClusterProjectionController) is the single reader,
 * shared by blind-spot, the nav map, and the new move-app-to-cluster cast — so a value
 * set on any page applies everywhere. This helper just renders that one selector and
 * persists it, so the automation + key-mapping pages don't each re-implement the fetch.
 *
 * Option values are the OEM size opcodes (31=10.25" / 30=12.3" / 29=8.8"), matching the
 * road-sense.html dropdown. i18n reuses the existing road_sense.* keys (already
 * translated in all locales) so this adds no new strings.
 *
 * ES5 / Chrome-58 safe (the in-app WebView): no arrow functions, no fetch-body via XHR
 * (we use fetch(), which this WebView handles for POST bodies).
 */
window.ClusterProjection = (function () {
  var VALID = [29, 30, 31];
  // Per select id: the car's value arrived, read counters, a reconnect handler is registered or deferred.
  var loaded = {}, loadSeq = {}, loadOk = {}, bound = {}, deferred = {};

  // Render the shared selector into the container with id `containerId`. `selectId` is
  // the <select> element id (unique per page). Safe to call before or after i18n applies
  // — the labels carry data-i18n and are (re)translated by the page's i18n pass.
  function renderInto(containerId, selectId) {
    var host = document.getElementById(containerId);
    if (!host) return;
    var row = document.createElement('div');
    row.className = 'setting-row';
    row.style.borderBottom = 'none';
    row.innerHTML =
      // Reuse the road_sense.* label + description keys (present in every locale) so
      // this shared selector needs no new translations.
      '<div class="setting-info">' +
        '<div class="setting-name" data-i18n="road_sense.map_cluster_layout">Cluster size</div>' +
        '<div class="setting-desc" data-i18n="road_sense.map_cluster_layout_desc">Match this to your instrument cluster. Shared with the blind-spot cluster view. If the projection looks stretched or cropped, try another option.</div>' +
      '</div>' +
      '<div class="setting-control">' +
        '<select id="' + selectId + '" class="select-control">' +
          '<option value="31" data-i18n="road_sense.bs_layout_1025">10.25&quot; cluster</option>' +
          '<option value="30" data-i18n="road_sense.bs_layout_123">12.3&quot; cluster</option>' +
          '<option value="29" data-i18n="road_sense.bs_layout_88">8.8&quot; cluster</option>' +
        '</select>' +
      '</div>';
    host.appendChild(row);
    var sel = document.getElementById(selectId);
    if (sel) {
      sel.addEventListener('change', function () { save(sel.value, sel); });
    }
    load(selectId);
    // After an outage, read again only if the first read never landed, and not under an open picker.
    if (!bound[selectId] && window.BYD && BYD.offline && typeof BYD.offline.onReconnect === 'function') {
      bound[selectId] = true;
      BYD.offline.onReconnect(function reread() {
        // Hidden: on the next return to the page, once.
        if (document.hidden) {
          if (deferred[selectId]) return;
          deferred[selectId] = true;
          document.addEventListener('visibilitychange', function shown() {
            if (document.hidden) return;
            document.removeEventListener('visibilitychange', shown);
            deferred[selectId] = false;
            reread();
          });
          return;
        }
        if (!loaded[selectId] && document.activeElement !== document.getElementById(selectId)) load(selectId);
      });
    }
    // Re-apply translations to the freshly-injected nodes if the page's i18n is ready.
    try {
      if (window.BYD && BYD.i18n && BYD.i18n.hydrate) BYD.i18n.hydrate(row);
    } catch (e) { /* i18n hydrates on its own catalog-ready pass otherwise */ }
  }

  // Read the current shared size from unified config → blindspot.clusterSizeProfile.
  function load(selectId) {
    var seq = loadSeq[selectId] = (loadSeq[selectId] || 0) + 1;
    fetch('/api/settings/unified', { cache: 'no-store' }).then(function (r) {
      return r.json();
    }).then(function (data) {
      // Not the car's value (an error reply has no config), or a newer read already painted: ignored.
      if (!data || data.success === false || !data.config || seq < (loadOk[selectId] || 0)) return;
      loadOk[selectId] = seq;
      // Same shape road-sense.js reads: data.config.blindspot.clusterSizeProfile.
      var bs = data && data.config && data.config.blindspot;
      var n = bs ? parseInt(bs.clusterSizeProfile, 10) : NaN;
      if (VALID.indexOf(n) === -1) n = 31; // default 10.25" (matches daemon seed default)
      var sel = document.getElementById(selectId);
      if (sel) sel.value = String(n);
      loaded[selectId] = true;
    }).catch(function () { /* leave the default selected */ });
  }

  // Persist to the SHARED key. The daemon relayouts a live cluster projection on this
  // key, so a change takes effect immediately. Saved on change (no staged Apply).
  function save(v, sel) {
    var n = parseInt(v, 10);
    if (VALID.indexOf(n) === -1) return;
    if (sel && sel.id && !loaded[sel.id]) {
      // The picker still shows the default, not the car's value: put it back and save nothing.
      sel.value = '31';
      notLoaded();
      return;
    }
    fetch('/api/settings/unified', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ section: 'blindspot', data: { clusterSizeProfile: n } })
    }).then(function (r) {
      return r.json();
    }).then(function (data) {
      var ok = data && data.success;
      // Reuse the road_sense.* toast keys (present in every locale; this selector is
      // the same setting the RoadSense page saves) so no new strings are needed.
      toast(ok ? 'road_sense.saved' : 'road_sense.save_failed', ok ? 'success' : 'error');
    }).catch(function () {
      toast('road_sense.save_failed', 'error');
    });
  }

  function toast(key, kind) {
    try {
      if (window.BYD && BYD.utils && BYD.utils.toast) {
        BYD.utils.toast(BYD.i18n.t(key), kind);
      }
    } catch (e) { /* toast is best-effort */ }
  }

  function notLoaded() {
    var key = 'road_sense.cluster_size_not_loaded', text = null;
    try { text = BYD.i18n.t(key); } catch (e) { /* no catalog yet */ }
    if (!text || text === key) text = 'Could not read the saved cluster size — reload the page before changing it.';
    try {
      if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(text, 'error');
    } catch (e) { /* toast is best-effort */ }
  }

  return { renderInto: renderInto };
})();
