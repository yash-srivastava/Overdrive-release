/*
 * Key Mapping settings controller.
 *
 * ES5-only (Chrome 58 / Android 7.1 head-unit floor) — no let/const/arrow/template
 * literals. Talks to the daemon's KeymapApiHandler:
 *   GET  /api/keymap/config  -> { enabled, allowAdvanced, bindings[], a11yEnabled }
 *   POST /api/keymap/config  -> persist { enabled, allowAdvanced, bindings[] }
 *
 * A binding is:
 *   { keycode:int, pressType:"single|double|long", enabled:true,
 *     label:"<human summary>", action:<fire-payload> }
 * where <fire-payload> is exactly what /api/keymap/fire consumes:
 *   { kind:"catalog", key, sub?, payload }   — curated vehicle action
 *   { kind:"vehicle", action }               — lock/unlock/flash/find_car
 *   { kind:"shell",   cmd }                  — advanced escape hatch
 *
 * The curated action list below MIRRORS the daemon's VehicleControlCatalog /
 * composite command set. It is the client-side menu only; the daemon re-resolves
 * and re-validates every fire, so a stale entry here just fails safe server-side.
 */
window.KM = (function () {
    'use strict';

    // Curated actions the user can bind. `payloads` (when present) becomes the
    // Value dropdown; the chosen option's `v` is sent as the fire payload.
    //   kind:"catalog" -> {kind:'catalog', key, sub, payload:v}
    //   kind:"vehicle" -> {kind:'vehicle', action:key}   (no payload)
    //   kind:"manualClip" -> {kind:'manualClip', beforeSeconds, afterSeconds}
    //   kind:"api"     -> {kind:'api', id, method, path, body}
    //       For features with no VehicleControlCatalog SDK entity (surveillance,
    //       recording). `api.path`/`api.body` support ${v} substitution from the
    //       chosen payload option. The daemon routes these through the same
    //       allowlisted automation bypass, so only the curated control surface
    //       is reachable. `id` is carried for the UI label only (daemon ignores it).
    var CURATED = [
        { id: 'lock',            i18n: 'keymap.act_lock',            kind: 'vehicle', key: 'lock' },
        { id: 'unlock',          i18n: 'keymap.act_unlock',          kind: 'vehicle', key: 'unlock' },
        { id: 'flash',           i18n: 'keymap.act_flash',           kind: 'vehicle', key: 'flash' },
        { id: 'find_car',        i18n: 'keymap.act_find_car',        kind: 'vehicle', key: 'find_car' },
        { id: 'windows_all',     i18n: 'keymap.act_windows',         kind: 'catalog', key: 'windows_all',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        // Per-door window position presets. A hardware button binds one fixed door,
        // so area is baked into the path and ${v} carries the preset percent
        // (0=closed/15=vent/50=half/100=full) through the closed-loop positioning
        // endpoint. api kind → allowlisted /api/vehicle/window.
        { id: 'window_lf',       i18n: 'keymap.act_window_lf',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":1,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'window_rf',       i18n: 'keymap.act_window_rf',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":2,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'window_lr',       i18n: 'keymap.act_window_lr',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":3,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'window_rr',       i18n: 'keymap.act_window_rr',       kind: 'api',
          method: 'POST', path: '/api/vehicle/window', body: '{"area":4,"targetPercent":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.win_closed' }, { v: '15', i18n: 'keymap.win_vent' }, { v: '50', i18n: 'keymap.win_half' }, { v: '100', i18n: 'keymap.win_full' } ] },
        { id: 'tailgate',        i18n: 'keymap.act_tailgate',        kind: 'catalog', key: 'tailgate',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'sunroof',         i18n: 'keymap.act_sunroof',         kind: 'catalog', key: 'sunroof',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'sunshade',        i18n: 'keymap.act_sunshade',        kind: 'catalog', key: 'sunshade',
          payloads: [ { v: 'OPEN', i18n: 'keymap.open' }, { v: 'CLOSE', i18n: 'keymap.close' }, { v: 'STOP', i18n: 'keymap.stop' } ] },
        { id: 'climate',         i18n: 'keymap.act_climate',         kind: 'catalog', key: 'climate', sub: 'mode',
          payloads: [ { v: 'auto', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        // AC fan speed — a button binds ONE fixed level (1-7). api kind → allowlisted
        // /api/vehicle/climate set_fan. ${v} carries the level.
        { id: 'ac_fan',          i18n: 'keymap.act_ac_fan',           kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"set_fan","fan":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.fan_1' }, { v: '2', i18n: 'keymap.fan_2' }, { v: '3', i18n: 'keymap.fan_3' }, { v: '4', i18n: 'keymap.fan_4' }, { v: '5', i18n: 'keymap.fan_5' }, { v: '6', i18n: 'keymap.fan_6' }, { v: '7', i18n: 'keymap.fan_7' } ] },
        // AC temperature — RELATIVE step, the natural fit for a hardware key: one press = one
        // degree warmer/cooler from wherever the dial is now. ${v} carries the signed delta.
        // (An absolute "set to 22" binding would need one key per temperature.)
        // No zone → both dials (the OEM's own default).
        { id: 'ac_temp_step',    i18n: 'keymap.act_ac_temp_step',     kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"step_temp","delta":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.temp_warmer' }, { v: '-1', i18n: 'keymap.temp_cooler' } ] },
        // Per-dial steps: zone 1 = driver, zone 2 = passenger. The daemon reads that dial,
        // steps it, and writes only that zone (see /api/vehicle/climate step_temp).
        { id: 'ac_temp_step_driver', i18n: 'keymap.act_ac_temp_step_driver', kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"step_temp","zone":1,"delta":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.temp_warmer' }, { v: '-1', i18n: 'keymap.temp_cooler' } ] },
        { id: 'ac_temp_step_passenger', i18n: 'keymap.act_ac_temp_step_passenger', kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"step_temp","zone":2,"delta":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.temp_warmer' }, { v: '-1', i18n: 'keymap.temp_cooler' } ] },
        // AC auto / fan-only / steering-wheel heater. ${v} carries the on|off suffix into
        // the /api/vehicle/climate action string (auto_on, fan_only_off, …).
        { id: 'ac_auto',         i18n: 'keymap.act_ac_auto',          kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"auto_${v}"}',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'fan_only',        i18n: 'keymap.act_fan_only',         kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"fan_only_${v}"}',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'steering_heat',   i18n: 'keymap.act_steering_heat',    kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"steering_heat_${v}"}',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'defrost_front',   i18n: 'keymap.act_defrost_front',    kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"defrost_front_${v}"}',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'defrost_rear',    i18n: 'keymap.act_defrost_rear',     kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"defrost_rear_${v}"}',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'recirculation',   i18n: 'keymap.act_recirculation',    kind: 'api',
          method: 'POST', path: '/api/vehicle/climate', body: '{"action":"recirculate_${v}"}',
          payloads: [ { v: 'on', i18n: 'keymap.recirculate' }, { v: 'off', i18n: 'keymap.fresh_air' } ] },
        { id: 'drl',             i18n: 'keymap.act_drl',             kind: 'catalog', key: 'drl',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'headlight_mode',  i18n: 'keymap.act_headlight_mode',  kind: 'catalog', key: 'headlight_mode',
          payloads: [ { v: 'off', i18n: 'keymap.off' }, { v: 'auto', i18n: 'keymap.headlight_auto' }, { v: 'parking', i18n: 'keymap.parking_lights' }, { v: 'low_beam', i18n: 'keymap.headlight_on_low_beam' } ] },
        // Welcome / reading / ambient-music lights on/off. ${v} carries true|false into
        // the /api/vehicle/lights enable field (target fixed per action).
        { id: 'welcome_light',   i18n: 'keymap.act_welcome_light',    kind: 'api',
          method: 'POST', path: '/api/vehicle/lights', body: '{"target":"welcomeLight","enable":${v}}',
          payloads: [ { v: 'true', i18n: 'keymap.on' }, { v: 'false', i18n: 'keymap.off' } ] },
        { id: 'reading_light',   i18n: 'keymap.act_reading_light',    kind: 'api',
          method: 'POST', path: '/api/vehicle/lights', body: '{"target":"readingLight","enable":${v}}',
          payloads: [ { v: 'true', i18n: 'keymap.on' }, { v: 'false', i18n: 'keymap.off' } ] },
        { id: 'ambient_music',   i18n: 'keymap.act_ambient_music',    kind: 'api',
          method: 'POST', path: '/api/vehicle/lights', body: '{"target":"ambientMusic","enable":${v}}',
          payloads: [ { v: 'true', i18n: 'keymap.on' }, { v: 'false', i18n: 'keymap.off' } ] },
        // Headlight beam level — a button binds ONE fixed level (1-11). ${v} carries it.
        { id: 'headlight_level', i18n: 'keymap.act_headlight_level',  kind: 'api',
          method: 'POST', path: '/api/vehicle/lights', body: '{"target":"headlightLevel","value":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.level_1' }, { v: '3', i18n: 'keymap.level_3' }, { v: '5', i18n: 'keymap.level_5' }, { v: '7', i18n: 'keymap.level_7' }, { v: '9', i18n: 'keymap.level_9' }, { v: '11', i18n: 'keymap.level_11' } ] },
        { id: 'hazard',          i18n: 'keymap.act_hazard',          kind: 'catalog', key: 'hazard',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'esp_control',     i18n: 'keymap.act_esp',             kind: 'catalog', key: 'esp_control',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'itac',            i18n: 'keymap.act_itac',            kind: 'catalog', key: 'itac',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        // Mirror fold/unfold. Set-only (no fold-state getter), so "toggle" is a blind
        // flip off the last-commanded value cached daemon-side — bind Toggle to one
        // button to alternate fold/unfold on each press.
        { id: 'mirror_fold',     i18n: 'keymap.act_mirror_fold',     kind: 'catalog', key: 'mirror_fold',
          payloads: [ { v: 'on', i18n: 'keymap.mirror_fold' }, { v: 'off', i18n: 'keymap.mirror_unfold' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // OEM native panorama application. These AUTO_VIDEO_BUTTON view choices do not use
        // OverDrive's camera overlay.
        { id: 'native_camera_view', i18n: 'keymap.act_native_camera_view', kind: 'catalog', key: 'native_camera_view',
          payloads: [ { v: 'front', i18n: 'keymap.camera_front' }, { v: 'front_wide', i18n: 'keymap.camera_front_wide' }, { v: 'rear', i18n: 'keymap.camera_rear' }, { v: 'rear_wide', i18n: 'keymap.camera_rear_wide' }, { v: 'left', i18n: 'keymap.camera_left' }, { v: 'right', i18n: 'keymap.camera_right' }, { v: 'left_right', i18n: 'keymap.camera_left_right' } ] },
        // Camera views on the native SurfaceControl lane (shares blind-spot pipeline;
        // BS has priority). A button picks WHICH camera; it shows centered on the
        // head-unit at a chosen SIZE (small/medium/large/full). ${v} = size % → the
        // preset's sizePct (corner fixed center for a button). api kind → allowlisted
        // /api/camview. Hide is a separate action.
        { id: 'camview_all',     i18n: 'keymap.act_camview_all',     kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=all&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_front',   i18n: 'keymap.act_camview_front',   kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=front&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_rear',    i18n: 'keymap.act_camview_rear',    kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=rear&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_left',    i18n: 'keymap.act_camview_left',    kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=left&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_right',   i18n: 'keymap.act_camview_right',   kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=right&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        // Composite rear+side views — the same render the blind-spot card uses, shown on
        // demand with no turn signal. Side is explicit per action. They follow the SHARED
        // blind-spot settings, so "Cameras shown" (rear+side / side / rear), fisheye and
        // rotation reshape these too.
        { id: 'camview_side_rear_left',  i18n: 'keymap.act_camview_side_rear_left',  kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=side_rear_left&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_side_rear_right', i18n: 'keymap.act_camview_side_rear_right', kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=side_rear_right&target=head_unit&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        // Resize the normal camera-view card currently selected for its saved display.
        // Unlike the camera choices above, this keeps the current feed and its corner;
        // it is also useful as a pre-set for the next camera view.
        { id: 'camview_resize', i18n: 'keymap.act_camview_resize', kind: 'api',
          method: 'POST', path: '/api/camview/geometry/size/${v}', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_hide',    i18n: 'keymap.act_camview_hide',    kind: 'api',
          method: 'POST', path: '/api/camview/hide', body: '' },
        // Same camera views projected onto the DRIVER CLUSTER (target=cluster). The daemon
        // opens the OEM cluster projection on demand and renders the view on its native
        // layer (identical path the blind-spot cluster view uses). No cluster "hide" is
        // needed — /api/camview/hide is target-agnostic (camview_hide above covers both).
        { id: 'camview_all_cluster',   i18n: 'keymap.act_camview_all_cluster',   kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=all&target=cluster&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_front_cluster', i18n: 'keymap.act_camview_front_cluster', kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=front&target=cluster&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_rear_cluster',  i18n: 'keymap.act_camview_rear_cluster',  kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=rear&target=cluster&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_left_cluster',  i18n: 'keymap.act_camview_left_cluster',  kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=left&target=cluster&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_right_cluster', i18n: 'keymap.act_camview_right_cluster', kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=right&target=cluster&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_side_rear_left_cluster',  i18n: 'keymap.act_camview_side_rear_left_cluster',  kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=side_rear_left&target=cluster&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        { id: 'camview_side_rear_right_cluster', i18n: 'keymap.act_camview_side_rear_right_cluster', kind: 'api',
          method: 'POST', path: '/api/camview/show?cam=side_rear_right&target=cluster&preset=${v}/center', body: '',
          payloads: [ { v: '25', i18n: 'keymap.size_small' }, { v: '45', i18n: 'keymap.size_medium' }, { v: '70', i18n: 'keymap.size_large' }, { v: '90', i18n: 'keymap.size_full' } ] },
        // ── Blind-spot card tweaks (allowlisted /api/bs/tweak; persists + applies live) ──
        // Fisheye correction for the SINGLE-camera side/rear views. Two actions: pick an
        // absolute level, or bind one button to nudge it up/down by a step each press
        // (the daemon steps off the stored value and clamps to 0-100).
        { id: 'bs_fisheye',      i18n: 'keymap.act_bs_fisheye',      kind: 'api',
          method: 'POST', path: '/api/bs/tweak?fisheye=${v}', body: '',
          payloads: [ { v: '0', i18n: 'keymap.off' }, { v: '25', i18n: 'keymap.level_25' }, { v: '50', i18n: 'keymap.level_50' }, { v: '75', i18n: 'keymap.level_75' }, { v: '100', i18n: 'keymap.level_100' } ] },
        { id: 'bs_fisheye_step', i18n: 'keymap.act_bs_fisheye_step', kind: 'api',
          method: 'POST', path: '/api/bs/tweak?fisheye=${v}', body: '',
          payloads: [ { v: 'up', i18n: 'keymap.bs_fisheye_more' }, { v: 'down', i18n: 'keymap.bs_fisheye_less' } ] },
        // Which camera(s) fill the card: merged rear+side, or one camera at full FOV.
        { id: 'bs_cameras',      i18n: 'keymap.act_bs_cameras',      kind: 'api',
          method: 'POST', path: '/api/bs/tweak?cameras=${v}', body: '',
          payloads: [ { v: 'both', i18n: 'keymap.bs_cameras_both' }, { v: 'side', i18n: 'keymap.bs_cameras_side' }, { v: 'rear', i18n: 'keymap.bs_cameras_rear' } ] },
        // Card rotation, applied to BOTH mirror-imaged sides (a button binds one angle;
        // per-side angles are set on the Road Sense page). Single-camera views only.
        { id: 'bs_rotation',     i18n: 'keymap.act_bs_rotation',     kind: 'api',
          method: 'POST', path: '/api/bs/tweak?rotation=${v}&side=both', body: '',
          payloads: [ { v: '0', i18n: 'keymap.bs_rotation_0' }, { v: '90', i18n: 'keymap.bs_rotation_90' }, { v: '180', i18n: 'keymap.bs_rotation_180' }, { v: '270', i18n: 'keymap.bs_rotation_270' }, { v: 'auto', i18n: 'keymap.bs_rotation_auto' } ] },
        // Feature master switch, and a dismiss that leaves the feature armed (the next
        // turn signal shows the card again).
        { id: 'bs_enable',       i18n: 'keymap.act_bs_enable',       kind: 'api',
          method: 'POST', path: '/api/bs/tweak?enabled=${v}', body: '',
          payloads: [ { v: 'true', i18n: 'keymap.on' }, { v: 'false', i18n: 'keymap.off' } ] },
        { id: 'bs_dismiss',      i18n: 'keymap.act_bs_dismiss',      kind: 'api',
          method: 'POST', path: '/api/bs/hide', body: '' },
        { id: 'seat_heat_driver',    i18n: 'keymap.act_seat_heat_driver',    kind: 'catalog', key: 'seat_heat_driver',
          payloads: [ { v: 'off', i18n: 'keymap.off' }, { v: 'low', i18n: 'keymap.low' }, { v: 'high', i18n: 'keymap.high' } ] },
        { id: 'seat_heat_passenger', i18n: 'keymap.act_seat_heat_passenger', kind: 'catalog', key: 'seat_heat_passenger',
          payloads: [ { v: 'off', i18n: 'keymap.off' }, { v: 'low', i18n: 'keymap.low' }, { v: 'high', i18n: 'keymap.high' } ] },
        { id: 'child_lock',      i18n: 'keymap.act_child_lock',      kind: 'catalog', key: 'child_lock',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'wireless_charging', i18n: 'keymap.act_wireless_charging', kind: 'catalog', key: 'wireless_charging',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'wireless_charging_left', i18n: 'keymap.act_wireless_charging_left', kind: 'catalog', key: 'wireless_charging_left',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'wireless_charging_right', i18n: 'keymap.act_wireless_charging_right', kind: 'catalog', key: 'wireless_charging_right',
          payloads: [ { v: '1', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        { id: 'drive_mode',      i18n: 'keymap.act_drive_mode',       kind: 'catalog', key: 'drive_mode',
          payloads: [ { v: 'normal', i18n: 'keymap.mode_normal' }, { v: 'eco', i18n: 'keymap.mode_eco' }, { v: 'sport', i18n: 'keymap.mode_sport' } ] },
        // Only the field-validated user-writable energy modes are offered here.
        { id: 'powertrain_mode', i18n: 'keymap.act_powertrain_mode',  kind: 'catalog', key: 'powertrain_mode',
          payloads: [ { v: 'ev', i18n: 'keymap.mode_ev' }, { v: 'hev', i18n: 'keymap.mode_hev' } ] },
        // "Engine mode" — one-tap switch to HEV. Does NOT hold the pack: on a DM-i this starts
        // the ICE and RECHARGES it. Use battery_hold below for a genuine hold.
        { id: 'hold_battery',    i18n: 'keymap.act_hold_battery',    kind: 'catalog', key: 'hold_battery',
          payloads: [ { v: 'on', i18n: 'keymap.on' } ] },
        // The REAL battery hold: writes setSOCTarget + setSocSaveSwitch together.
        // at_current = keep the charge I have (Highway), at_floor = let it deplete (City).
        { id: 'battery_hold',    i18n: 'keymap.act_battery_hold',    kind: 'catalog', key: 'battery_hold',
          payloads: [ { v: 'at_current', i18n: 'keymap.battery_hold_at_current' }, { v: 'at_floor', i18n: 'keymap.battery_hold_at_floor' }, { v: 'off', i18n: 'keymap.off' } ] },
        // regen / steering / brake feel: no telemetry readback exists, so "toggle"
        // CYCLES to the next option (daemon tracks the last-commanded value) — ideal
        // for a single button that flips between the two modes on each press.
        { id: 'regen_level',     i18n: 'keymap.act_regen',            kind: 'catalog', key: 'regen_level',
          payloads: [ { v: 'standard', i18n: 'keymap.regen_standard' }, { v: 'high', i18n: 'keymap.regen_high' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'steering_mode',   i18n: 'keymap.act_steering',         kind: 'catalog', key: 'steering_mode',
          payloads: [ { v: 'comfort', i18n: 'keymap.steering_comfort' }, { v: 'sport', i18n: 'keymap.steering_sport' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'brake_feel',      i18n: 'keymap.act_brake_feel',       kind: 'catalog', key: 'brake_feel',
          payloads: [ { v: 'comfort', i18n: 'keymap.brake_comfort' }, { v: 'sport', i18n: 'keymap.brake_sport' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // Speed-limit-warning + child-presence: real snapshot state → support toggle
        // (flip current) alongside explicit on/off. catalog kind resolves toggle daemon-side.
        { id: 'adas_slw',        i18n: 'keymap.act_slw',              kind: 'catalog', key: 'adas_slw',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        { id: 'adas_cpd',        i18n: 'keymap.act_cpd',              kind: 'catalog', key: 'adas_cpd',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // Lane assist — multi-mode (Off/LDW/LDP/LDW+LDP); "toggle" cycles via live
        // readback. catalog kind resolves against the "lane_assist" entity daemon-side.
        { id: 'lane_assist',     i18n: 'keymap.act_lane_assist',      kind: 'catalog', key: 'lane_assist',
          payloads: [ { v: '0', i18n: 'keymap.lane_off' }, { v: '1', i18n: 'keymap.lane_ldw' }, { v: '2', i18n: 'keymap.lane_ldp' }, { v: '3', i18n: 'keymap.lane_ldw_ldp' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // ── Expanded ADAS matrix. on/off (+toggle where the daemon has live readback).
        //    Feature ids/polarity unverified per trim — verify via GET /api/vehicle/adas. ──
        { id: 'adas_bsd',  i18n: 'keymap.act_bsd',  kind: 'catalog', key: 'adas_bsd',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_tsr',  i18n: 'keymap.act_tsr',  kind: 'catalog', key: 'adas_tsr',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_rcta', i18n: 'keymap.act_rcta', kind: 'catalog', key: 'adas_rcta',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_fcta', i18n: 'keymap.act_fcta', kind: 'catalog', key: 'adas_fcta',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_tla',  i18n: 'keymap.act_tla',  kind: 'catalog', key: 'adas_tla',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_dow',  i18n: 'keymap.act_dow',  kind: 'catalog', key: 'adas_dow',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_rcw',  i18n: 'keymap.act_rcw',  kind: 'catalog', key: 'adas_rcw',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_islc', i18n: 'keymap.act_islc', kind: 'catalog', key: 'adas_islc',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_elka', i18n: 'keymap.act_elka', kind: 'catalog', key: 'adas_elka',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_rctb', i18n: 'keymap.act_rctb', kind: 'catalog', key: 'adas_rctb',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_fctb', i18n: 'keymap.act_fctb', kind: 'catalog', key: 'adas_fctb',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'adas_fcw',  i18n: 'keymap.act_fcw',  kind: 'catalog', key: 'adas_fcw',
          payloads: [ { v: '0', i18n: 'keymap.off' }, { v: '1', i18n: 'keymap.fcw_low' }, { v: '2', i18n: 'keymap.fcw_medium' }, { v: '3', i18n: 'keymap.fcw_high' } ] },
        // AEB is ENABLE-ONLY (safety-critical): a button can only re-arm it, never disable.
        { id: 'adas_aeb',  i18n: 'keymap.act_aeb',  kind: 'catalog', key: 'adas_aeb',
          payloads: [ { v: 'on', i18n: 'keymap.on' } ] },
        // ── Display brightness (media handler; allowlisted). A button steps to a
        //    chosen level — pick the level when binding. ${v} = 0-100 percentage. ──
        { id: 'screen_brightness', i18n: 'keymap.act_screen_brightness', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"brightness","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.level_0' }, { v: '25', i18n: 'keymap.level_25' }, { v: '50', i18n: 'keymap.level_50' }, { v: '75', i18n: 'keymap.level_75' }, { v: '100', i18n: 'keymap.level_100' } ] },
        { id: 'cluster_brightness', i18n: 'keymap.act_cluster_brightness', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"cluster_brightness","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.level_0' }, { v: '25', i18n: 'keymap.level_25' }, { v: '50', i18n: 'keymap.level_50' }, { v: '75', i18n: 'keymap.level_75' }, { v: '100', i18n: 'keymap.level_100' } ] },
        { id: 'hud_brightness', i18n: 'keymap.act_hud_brightness', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"hud_brightness","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.level_0' }, { v: '25', i18n: 'keymap.level_25' }, { v: '50', i18n: 'keymap.level_50' }, { v: '75', i18n: 'keymap.level_75' }, { v: '100', i18n: 'keymap.level_100' } ] },
        // HUD on/off — no dedicated switch on this platform, so off=brightness 0 /
        // on=brightness 100 (value IS the brightness the daemon writes).
        { id: 'hud_power', i18n: 'keymap.act_hud_power', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"hud_power","value":${v}}',
          payloads: [ { v: '100', i18n: 'keymap.on' }, { v: '0', i18n: 'keymap.off' } ] },
        // Infotainment screen on/off — proven backlight path (PowerManager
        // turnBacklightOn/Off → BYDAutoSettingDevice → shell WAKEUP/SLEEP), NOT
        // goToSleep. Bind a button to Off to blank the panel; On restores it.
        { id: 'screen_power', i18n: 'keymap.act_screen_power', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"screen_power","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.off' }, { v: '1', i18n: 'keymap.on' } ] },
        // Physical centre-screen orientation through BYDAutoSettingDevice.setPadRotation.
        // Toggle cycles horizontal/vertical from the daemon's last-commanded cache.
        { id: 'infotainment_rotation', i18n: 'keymap.act_infotainment_rotation', kind: 'catalog', key: 'infotainment_rotation',
          payloads: [ { v: 'horizontal', i18n: 'keymap.orientation_horizontal' }, { v: 'vertical', i18n: 'keymap.orientation_vertical' }, { v: 'toggle', i18n: 'keymap.toggle' } ] },
        // Media volume on a fixed channel — a button binds ONE channel + one level.
        // Separate curated entries per channel keep the two-dropdown form to a single
        // value picker (the channel is baked into the body).
        // Volume is an ABSOLUTE step (0-40, the car's own volume scale), not a percent.
        // The daemon clamps to the channel's real stream max.
        { id: 'volume_media', i18n: 'keymap.act_volume_media', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"volume","channel":"media","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.vol_0' }, { v: '10', i18n: 'keymap.vol_10' }, { v: '20', i18n: 'keymap.vol_20' }, { v: '30', i18n: 'keymap.vol_30' }, { v: '40', i18n: 'keymap.vol_40' } ] },
        { id: 'volume_navigation', i18n: 'keymap.act_volume_navigation', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"volume","channel":"navigation","value":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.vol_0' }, { v: '10', i18n: 'keymap.vol_10' }, { v: '20', i18n: 'keymap.vol_20' }, { v: '30', i18n: 'keymap.vol_30' }, { v: '40', i18n: 'keymap.vol_40' } ] },
        // Volume up/down — one relative step on the media channel (ideal for a single
        // button that nudges volume each press). ${v} = direction (+1 up / -1 down).
        { id: 'volume_step', i18n: 'keymap.act_volume_step', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"volume_step","channel":"media","value":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.volume_up' }, { v: '-1', i18n: 'keymap.volume_down' } ] },
        // Media transport — controls whatever holds the media session (radio/BT/app).
        { id: 'media_control', i18n: 'keymap.act_media_control', kind: 'api',
          method: 'POST', path: '/api/vehicle/media', body: '{"target":"media_key","key":"${v}"}',
          payloads: [ { v: 'play_pause', i18n: 'keymap.media_play_pause' }, { v: 'next', i18n: 'keymap.media_next' }, { v: 'previous', i18n: 'keymap.media_previous' } ] },
        // Play audio: the payload dropdown is populated LIVE from the audio library
        // (dynamicPayloads:'audio' → GET /api/audio/library), so a button binds one of
        // the sounds the user uploaded on the Automations page. ${v} = the sound's
        // filename; the daemon resolves it to the library path. A button fires once
        // (loop off); for looping/screen use an automation. Stop is payloadless.
        { id: 'play_audio', i18n: 'keymap.act_play_audio', kind: 'api',
          method: 'POST', path: '/api/vehicle/play-audio',
          body: '{"name":"${v}","channel":"media","display":"speakers","loop":false}', dynamicPayloads: 'audio' },
        // Play a video with its picture on the head-unit screen (same library picker).
        { id: 'play_video', i18n: 'keymap.act_play_video', kind: 'api',
          method: 'POST', path: '/api/vehicle/play-audio',
          body: '{"name":"${v}","channel":"media","display":"screen","loop":false}', dynamicPayloads: 'audio' },
        { id: 'stop_audio', i18n: 'keymap.act_stop_audio', kind: 'api',
          method: 'POST', path: '/api/vehicle/stop-audio', body: '' },
        // System UI navigation — a button jumps Home / Back / Recents (daemon input keyevent).
        { id: 'ui_nav', i18n: 'keymap.act_ui_nav', kind: 'api',
          method: 'POST', path: '/api/vehicle/system', body: '{"target":"${v}"}',
          payloads: [ { v: 'home', i18n: 'keymap.nav_home' }, { v: 'back', i18n: 'keymap.nav_back' }, { v: 'recents', i18n: 'keymap.nav_recents' } ] },
        // Screenshot the chosen display (screencap; a11y screenshot is API30+, unavailable here).
        { id: 'screenshot', i18n: 'keymap.act_screenshot', kind: 'api',
          method: 'POST', path: '/api/vehicle/system', body: '{"target":"screenshot","display":${v}}',
          payloads: [ { v: '0', i18n: 'keymap.display_head_unit' }, { v: '1', i18n: 'keymap.display_cluster' } ] },
        // Parked-only and guarded against startup/reboot loops by the daemon.
        { id: 'ivi_reboot', i18n: 'keymap.act_ivi_reboot', kind: 'api',
          method: 'POST', path: '/api/system/ivi-reboot', body: '{"confirm":"REBOOT"}' },
        // Cast an installed app onto the DRIVER CLUSTER. The daemon opens the OEM
        // projection first (the fission display doesn't exist until then), resolves the
        // live display id, and holds the projection open. App picked from the live list
        // (dynamicPayloads:'apps' → /api/apps/list); the stored payload is the package.
        // Match the "Cluster size" in Settings to your cluster. Stop with the binding below.
        { id: 'cast_app_cluster', i18n: 'keymap.act_cast_app_cluster', kind: 'api',
          method: 'POST', path: '/api/vehicle/system',
          body: '{"target":"move_display","package":"${v}","display":1}', dynamicPayloads: 'apps' },
        // Stop casting to the cluster — releases the projection so the gauges return.
        { id: 'cluster_cast_stop', i18n: 'keymap.act_cluster_cast_stop', kind: 'api',
          method: 'POST', path: '/api/vehicle/system', body: '{"target":"cluster_cast_stop"}' },
        // Speak a fixed message aloud (TTS). A hardware button binds one preset phrase;
        // the payload dropdown offers a few common ones (free-text is an automation).
        { id: 'speak', i18n: 'keymap.act_speak', kind: 'api',
          method: 'POST', path: '/api/vehicle/speak', body: '{"text":"${v}","channel":"voice"}',
          payloads: [ { v: 'Hello', i18n: 'keymap.speak_hello' }, { v: 'Please fasten your seatbelt', i18n: 'keymap.speak_seatbelt' }, { v: 'Arriving soon', i18n: 'keymap.speak_arriving' } ] },
        // Show a brief on-screen toast. A hardware button binds one preset phrase (a
        // custom message/title/dialog is set up in an automation, which has the full form);
        // the toast floats over the current app without stealing focus.
        { id: 'show_toast', i18n: 'keymap.act_show_toast', kind: 'api',
          method: 'POST', path: '/api/vehicle/message',
          body: '{"kind":"toast","message":"${v}","duration":"short","position":"bottom","severity":"info"}',
          payloads: [ { v: 'Hello', i18n: 'keymap.speak_hello' }, { v: 'Please fasten your seatbelt', i18n: 'keymap.speak_seatbelt' }, { v: 'Arriving soon', i18n: 'keymap.speak_arriving' } ] },
        // ── API actions: features with no SDK/catalog entity (routed via the
        //    allowlisted automation bypass). path/body use ${v} for the payload. ──
        { id: 'surveillance',    i18n: 'keymap.act_surveillance',     kind: 'api',
          method: 'POST', path: '/api/surveillance/${v}', body: '',
          payloads: [ { v: 'enable', i18n: 'keymap.on' }, { v: 'disable', i18n: 'keymap.off' } ] },
        { id: 'recording',       i18n: 'keymap.act_recording',        kind: 'api',
          method: 'POST', path: '/api/recording/mode', body: '{"mode":"${v}"}',
          payloads: [ { v: 'NONE', i18n: 'keymap.rec_none' }, { v: 'CONTINUOUS', i18n: 'keymap.rec_continuous' },
                      { v: 'DRIVE_MODE', i18n: 'keymap.rec_drive' }, { v: 'PROXIMITY_GUARD', i18n: 'keymap.rec_proximity' } ] },
        // Seat memory: recall moves the driver seat to a stored slot; save stores
        // the current position into it. Two curated actions so a button can be
        // bound to either. Routed via /api/vehicle/seat (already automation-allowlisted).
        { id: 'seat_recall',     i18n: 'keymap.act_seat_recall',      kind: 'api',
          method: 'POST', path: '/api/vehicle/seat', body: '{"action":"position","position":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.seat_slot_1' }, { v: '2', i18n: 'keymap.seat_slot_2' } ] },
        { id: 'seat_save',       i18n: 'keymap.act_seat_save',        kind: 'api',
          method: 'POST', path: '/api/vehicle/seat', body: '{"action":"save","position":${v}}',
          payloads: [ { v: '1', i18n: 'keymap.seat_slot_1' }, { v: '2', i18n: 'keymap.seat_slot_2' } ] },
        // Manual-only recording action. This has a dedicated daemon action kind
        // instead of the automation-shared API path; the two window values are
        // edited by the clip controls below and persisted directly in the binding.
        { id: 'manual_clip',     i18n: 'keymap.act_manual_clip',       kind: 'manualClip' },
        // Run a saved automation on a keypress — the physical-key equivalent of the
        // automation's trigger. The target is chosen from a LIVE dropdown of the
        // user's automations (dynamicPayloads:'automation' → GET /api/automations/picker);
        // the stored binding is { kind:'automation', id, name }. The daemon enqueues it
        // on the shared AutomationQueue (conditions + run-stats still apply). Dedicated
        // 'automation' kind, NOT the API path — /api/automations is off the allowlist.
        { id: 'run_automation', i18n: 'keymap.act_run_automation', kind: 'automation',
          dynamicPayloads: 'automation' },
        // Run a reusable ACTION GROUP on a keypress. Same live-picker shape as
        // run_automation but sourced from /api/action-groups/list; the stored binding is
        // { kind:'actionGroup', id, name }. Unlike an automation a group has no conditions
        // and no run-stats, so it fires unconditionally — which is what a button should do.
        { id: 'run_action_group', i18n: 'keymap.act_run_action_group', kind: 'actionGroup',
          dynamicPayloads: 'actionGroup' },
        // Radio toggles (WiFi / Bluetooth / mobile-data). kind:'radio' → daemon runs
        // `svc <radio> enable|disable`; WiFi-off also sets the keep-alive suppression
        // flag so the watchdog doesn't auto-re-enable it. The `radio` id is fixed per
        // action; the on/off payload picks the state.
        { id: 'radio_wifi', i18n: 'keymap.act_radio_wifi', kind: 'radio', radio: 'wifi',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'radio_bluetooth', i18n: 'keymap.act_radio_bluetooth', kind: 'radio', radio: 'bluetooth',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] },
        { id: 'radio_data', i18n: 'keymap.act_radio_data', kind: 'radio', radio: 'data',
          payloads: [ { v: 'on', i18n: 'keymap.on' }, { v: 'off', i18n: 'keymap.off' } ] }
    ];

    // Live (async) payload sources for curated actions that carry
    // dynamicPayloads:'&lt;type&gt;'. Each entry supplies the fetch URL, the i18n keys for
    // the loading / empty / missing states, and an extract() mapping the response to a
    // flat [{value, text}] list for the payload dropdown. Keeps onCuratedChange generic
    // so a new live list is a data entry here, not another bespoke fetch branch.
    var DYNAMIC_PAYLOAD = {
        audio: {
            url: '/api/audio/library',
            loading: 'keymap.loading_audio',
            none: 'keymap.no_audio',
            missing: 'keymap.audio_missing',
            extract: function (j) {
                var out = [];
                var sounds = (j && j.success && j.sounds) ? j.sounds : [];
                for (var i = 0; i < sounds.length; i++) {
                    out.push({ value: sounds[i].name, text: sounds[i].name });
                }
                return out;
            }
        },
        automation: {
            url: '/api/automations/picker',
            loading: 'keymap.loading_automations',
            none: 'keymap.no_automations',
            missing: 'keymap.automation_missing',
            extract: function (j) {
                var out = [];
                var arr = Array.isArray(j) ? j : [];
                for (var i = 0; i < arr.length; i++) {
                    if (arr[i] && arr[i].id) out.push({ value: arr[i].id, text: arr[i].name || arr[i].id });
                }
                return out;
            }
        },
        // Reusable action groups. /api/action-groups/list already returns the [{id,name}]
        // picker shape, so this needs no new endpoint.
        actionGroup: {
            url: '/api/action-groups/list',
            loading: 'keymap.loading_action_groups',
            none: 'keymap.no_action_groups',
            missing: 'keymap.action_group_missing',
            extract: function (j) {
                var out = [];
                var arr = Array.isArray(j) ? j : [];
                for (var i = 0; i < arr.length; i++) {
                    if (arr[i] && arr[i].id) out.push({ value: arr[i].id, text: arr[i].name || arr[i].id });
                }
                return out;
            }
        },
        // Installed launchable apps, for the "Cast app to cluster" binding. Same source
        // as the openApp picker (/api/apps/list); the stored payload is the package name.
        apps: {
            url: '/api/apps/list',
            loading: 'keymap.loading_apps',
            none: 'keymap.no_apps',
            missing: 'keymap.app_missing',
            extract: function (j) {
                var out = [];
                var apps = (j && j.success && j.apps) ? j.apps : [];
                for (var i = 0; i < apps.length; i++) {
                    if (apps[i] && apps[i].package) {
                        out.push({ value: apps[i].package, text: apps[i].label || apps[i].package });
                    }
                }
                return out;
            }
        }
    };

    // Cosmetic <optgroup> grouping for the curated-action picker — mirrors the
    // Automations page categories. Maps each CURATED id to a category key; the value
    // stored in a binding is still the id (never the category), so this only reorders
    // how options are shown and never changes what a saved binding resolves to. An id
    // not listed here falls into 'other' (shown last), so a new action can't vanish.
    var KM_CATEGORY = {
        lock: 'vehicle', unlock: 'vehicle', flash: 'vehicle', find_car: 'vehicle',
        windows_all: 'windows_body', window_lf: 'windows_body', window_rf: 'windows_body',
        window_lr: 'windows_body', window_rr: 'windows_body', tailgate: 'windows_body',
        sunroof: 'windows_body', sunshade: 'windows_body', child_lock: 'windows_body',
        mirror_fold: 'windows_body', wireless_charging: 'windows_body',
        wireless_charging_left: 'windows_body', wireless_charging_right: 'windows_body',
        climate: 'climate', ac_fan: 'climate', ac_temp_step: 'climate',
        ac_temp_step_driver: 'climate', ac_temp_step_passenger: 'climate',
        ac_auto: 'climate', fan_only: 'climate', steering_heat: 'climate', recirculation: 'climate',
        defrost_front: 'climate', defrost_rear: 'climate',
        seat_heat_driver: 'climate', seat_heat_passenger: 'climate',
        drl: 'lighting', headlight_mode: 'lighting', hazard: 'lighting',
        welcome_light: 'lighting', reading_light: 'lighting',
        ambient_music: 'lighting', headlight_level: 'lighting',
        esp_control: 'adas_safety', itac: 'adas_safety', adas_slw: 'adas_safety',
        adas_cpd: 'adas_safety', lane_assist: 'adas_safety',
        adas_bsd: 'adas_safety', adas_tsr: 'adas_safety', adas_rcta: 'adas_safety',
        adas_fcta: 'adas_safety', adas_tla: 'adas_safety', adas_dow: 'adas_safety',
        adas_rcw: 'adas_safety', adas_islc: 'adas_safety', adas_elka: 'adas_safety',
        adas_rctb: 'adas_safety', adas_fctb: 'adas_safety', adas_fcw: 'adas_safety',
        adas_aeb: 'adas_safety',
        drive_mode: 'drive', powertrain_mode: 'drive', hold_battery: 'drive', regen_level: 'drive',
        steering_mode: 'drive', brake_feel: 'drive', battery_hold: 'drive',
        volume_media: 'media', volume_navigation: 'media', volume_step: 'media',
        media_control: 'media', play_audio: 'media', play_video: 'media', stop_audio: 'media', speak: 'media',
        screen_brightness: 'displays', cluster_brightness: 'displays',
        hud_brightness: 'displays', hud_power: 'displays', screen_power: 'displays',
        infotainment_rotation: 'displays',
        camview_all: 'surveillance', camview_front: 'surveillance', camview_rear: 'surveillance',
        camview_left: 'surveillance', camview_right: 'surveillance', camview_hide: 'surveillance',
        camview_all_cluster: 'surveillance', camview_front_cluster: 'surveillance', camview_rear_cluster: 'surveillance',
        camview_left_cluster: 'surveillance', camview_right_cluster: 'surveillance',
        camview_side_rear_left: 'surveillance', camview_side_rear_right: 'surveillance',
        camview_side_rear_left_cluster: 'surveillance', camview_side_rear_right_cluster: 'surveillance',
        surveillance: 'surveillance', recording: 'surveillance', manual_clip: 'surveillance',
        bs_fisheye: 'surveillance', bs_fisheye_step: 'surveillance', bs_cameras: 'surveillance',
        bs_rotation: 'surveillance', bs_enable: 'surveillance', bs_dismiss: 'surveillance',
        native_camera_view: 'surveillance',
        seat_recall: 'system', seat_save: 'system',
        ui_nav: 'system', screenshot: 'system', ivi_reboot: 'system', show_toast: 'system',
        cast_app_cluster: 'system', cluster_cast_stop: 'system',
        radio_wifi: 'system', radio_bluetooth: 'system', radio_data: 'system',
        run_automation: 'flow'
    };
    // Same display order as the Automations page (BYD.AUTOMATION_CATEGORY_ORDER); kept
    // inline so key-mapping.js works even if automations.js isn't loaded on this page.
    var KM_CATEGORY_ORDER = [
        'vehicle', 'climate', 'windows_body', 'lighting', 'adas_safety', 'drive',
        'media', 'displays', 'sensors', 'surveillance', 'advanced', 'system', 'flow', 'other'
    ];
    var HYBRID_ONLY_CURATED = {
        powertrain_mode: 1,
        hold_battery: 1,
        battery_hold: 1,
        charge_cap_enabled: 1,
        charge_cap_percent: 1
    };
    function kmCategoryLabel(cat) {
        var key = 'automation.category_' + cat;
        var t = tr(key);
        if (t && t !== key) return t;
        return String(cat).replace(/_/g, ' ').replace(/\b\w/g, function (c) { return c.toUpperCase(); });
    }

    // Known BYD steering-wheel / dash buttons (keycode → label), from the OEM
    // keycode map verified on DiLink firmware.
    //
    // CRITICAL firmware model: on this hardware a LONG PRESS is NOT repeatCount>0
    // of a base keycode — the firmware detects the hold itself and emits a
    // SEPARATE keycode (302 = "Next long", the long-press of 87 = "Next"; likewise
    // 303←88, 306←305, 312←304). So:
    //   • base keys (87/88/289/…) support single + double (timing between presses),
    //   • long-variant keys (302/303/306/312) must fire ON ARRIVAL — the keycode
    //     showing up IS the completed long-press. Binding them as pressType "long"
    //     (which waits for repeatCount>0) would NEVER fire, since they arrive as a
    //     plain repeatCount==0 DOWN. So `fixed: 'single'` = fire immediately; the
    //     label already tells the user it's the long-press button.
    // `pressTypes` limits the press-type selector to what the base keycode really
    // supports (no "long" on a base key, because long is a different keycode here).
    var KNOWN_BUTTONS = [
        { code: 87,  i18n: 'keymap.btn_next',      pressTypes: ['single', 'double'] },
        { code: 88,  i18n: 'keymap.btn_prev',      pressTypes: ['single', 'double'] },
        { code: 289, i18n: 'keymap.btn_mode',      pressTypes: ['single', 'double'] },
        { code: 291, i18n: 'keymap.btn_vol_up',    pressTypes: ['single'] },
        { code: 292, i18n: 'keymap.btn_vol_down',  pressTypes: ['single'] },
        { code: 293, i18n: 'keymap.btn_mute',      pressTypes: ['single', 'double'] },
        { code: 294, i18n: 'keymap.btn_surround',  pressTypes: ['single', 'double'] },
        { code: 304, i18n: 'keymap.btn_voice',     pressTypes: ['single', 'double'] },
        { code: 305, i18n: 'keymap.btn_rotate',    pressTypes: ['single', 'double'] },
        { code: 313, i18n: 'keymap.btn_phone',     pressTypes: ['single', 'double'] },
        { code: 317, i18n: 'keymap.btn_power',     pressTypes: ['single', 'double'] },
        { code: 302, i18n: 'keymap.btn_next_long',   fixed: 'single' },
        { code: 303, i18n: 'keymap.btn_prev_long',   fixed: 'single' },
        { code: 306, i18n: 'keymap.btn_rotate_long', fixed: 'single' },
        { code: 312, i18n: 'keymap.btn_voice_long',  fixed: 'single' }
    ];

    // Buttons whose OEM function the car firmware performs itself at a layer BELOW the
    // Android input pipeline (audio-policy mute / the phone-BT app or MCU). An
    // accessibility key filter can consume the app-level event but CANNOT veto that
    // parallel action, so the stock function may still fire alongside the mapped one.
    // We surface a warning for these; the double-press + block-single-click binding is
    // the best available suppression. 293 = Mute, 313 = Phone/Bluetooth.
    var NATIVE_SIDE_EFFECT_CODES = { 293: 1, 313: 1 };

    var state = {
        enabled: false,
        allowAdvanced: false,
        doubleTapWindowMs: 450,
        bindings: [],
        a11yEnabled: false,
        // The daemon's stronger "AMS has actually bound the service" signal. undefined until
        // a response carries it, so a11yReady() can fall back to the precondition on an
        // older daemon rather than reading a hardcoded false as "never ready".
        a11yBound: undefined,
        // undefined until the daemon has a reliable drivetrain verdict.
        fuelCapableHybrid: undefined,
        restartRequired: false
    };
    var capturing = false;
    var captured = null; // last captured keycode while arming

    var CLIP_MAX_BEFORE_SECONDS = 60;
    var CLIP_MAX_AFTER_SECONDS = 60;
    var CLIP_MAX_TOTAL_SECONDS = 60;
    var CLIP_DEFAULT_PRESET = '30:0';
    // Monotonic token for async payload-dropdown builds (dynamicPayloads). Bumped on
    // every onCuratedChange so a late fetch response for a since-changed action is
    // discarded instead of clobbering the current action's payload options.
    var payloadReqSeq = 0;
    var localeChangeBound = false;

    function $(id) { return document.getElementById(id); }
    function tr(key, vars) { return (window.BYD && BYD.i18n) ? (BYD.i18n.t(key, vars) || key) : key; }
    function toast(msg, kind) { if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, kind || 'info'); }
    function curatedById(id) {
        for (var i = 0; i < CURATED.length; i++) if (CURATED[i].id === id) return CURATED[i];
        return null;
    }

    // ───────────────────────── Load / paint ─────────────────────────

    // Guards for the a11y self-heal re-fetch below. a11yRecheckPending blocks
    // OVERLAPPING timers; a11yRechecksLeft BOUNDS the total number of sequential
    // rechecks so the page never polls forever. The daemon's self-heal lands
    // within ~5-11s (one or two 5.5s windows) on a provisioned unit, so a small
    // budget covers the legitimate "heal in flight" case. If the service still
    // isn't enabled after that (e.g. the Secure-settings write can't stick on a
    // permission-limited build, or the user hasn't enabled it), we STOP re-fetching
    // and leave the nudge card up — the user acts via "Open accessibility settings".
    // Without this cap, recheckA11y()->scheduleA11yRecheck() re-armed every 5.5s for
    // the life of the page, and each GET spawned a server-side settings-exec heal.
    //
    // The budget covers a COLD CAR START, not just a settings write. Opening this page
    // right after power-on races AMS binding the app-process service: the daemon's
    // ~4-5s heal is only the first step, and until the bind lands the key filter is
    // inert, so capture can't see a hardware button. 3 rechecks gave up after ~16.5s
    // and left the nudge up on a unit that was about to work — the "binding a key takes
    // too long after car on" report. 8 covers ~44s, past a cold boot, and still
    // terminates on a genuinely un-enabled service.
    var a11yRecheckPending = false;
    var a11yRechecksLeft = 0;
    var A11Y_RECHECK_BUDGET = 8;

    function load() {
        fetch('/api/keymap/config', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (s) {
                state.enabled = !!s.enabled;
                state.allowAdvanced = !!s.allowAdvanced;
                if (typeof s.doubleTapWindowMs === 'number') state.doubleTapWindowMs = s.doubleTapWindowMs;
                state.bindings = (s.bindings && s.bindings.length) ? s.bindings : [];
                state.a11yEnabled = !!s.a11yEnabled;
                if (typeof s.a11yBound === 'boolean') state.a11yBound = s.a11yBound;
                if (typeof s.fuelCapableHybrid === 'boolean') {
                    var wasDirty = formDirty;
                    state.fuelCapableHybrid = s.fuelCapableHybrid;
                    buildCuratedOptions();
                    formDirty = wasDirty;
                }
                state.restartRequired = !!s.restartRequired;
                paint();
                // Fresh foreground load — refill the recheck budget so the
                // heal-window poll runs, but stays bounded (see scheduleA11yRecheck).
                a11yRechecksLeft = A11Y_RECHECK_BUDGET;
                scheduleA11yRecheck();
            })
            .catch(function () { toast(tr('keymap.load_failed'), 'error'); });
        // Independent section, independent fetch: a quick-control list failure must not
        // stop the key bindings from painting (and vice-versa).
        loadQuickControls();
    }

    // Client-side half of the daemon's load-time self-heal
    // (KeymapApiHandler.handleGetConfig): when mapping is ON but the a11y service
    // is off, the GET makes the daemon kick an async enable that lands ~4-5s later,
    // but it reports a11yEnabled from a PRE-heal read — so a11yEnabled=true only
    // shows up on a SUBSEQUENT GET. Re-check once after the heal window so the
    // nudge auto-hides instead of lingering until a manual reload.
    //
    // CRITICAL: this MUST refresh ONLY the a11y status, never re-assign
    // state.bindings / state.enabled / state.allowAdvanced. This is a BACKGROUND
    // timer that can fire in the middle of the user adding bindings; if it called
    // the full load() (as it used to), it would clobber state.bindings with the
    // server snapshot — dropping any binding added since the timer was armed, or
    // one that a still-in-flight persist() hasn't committed yet. That was the
    // "adding a new binding deletes the older one" bug. So recheckA11y() reads the
    // config but adopts only a11yEnabled, leaving the user's working binding list
    // and toggles untouched.
    // Whether key filtering is actually live. a11yEnabled is only the OS BIND
    // PRECONDITION (component listed + master flag on); AMS binds the app-process service
    // asynchronously, and until that lands onKeyEvent never fires — so keys are dead and
    // capture sees nothing even though a11yEnabled reads true. The daemon reports the
    // stronger a11yBound for exactly this reason; treat "ready" as bound when the daemon
    // tells us, and fall back to the precondition on an older build that omits the field.
    function a11yReady() {
        if (typeof state.a11yBound === 'boolean') return state.a11yBound;
        return state.a11yEnabled;
    }

    function scheduleA11yRecheck() {
        if (state.enabled && !a11yReady() && !a11yRecheckPending && a11yRechecksLeft > 0) {
            a11yRecheckPending = true;
            a11yRechecksLeft--;
            setTimeout(function () { a11yRecheckPending = false; recheckA11y(); }, 5500);
        }
    }

    function recheckA11y() {
        fetch('/api/keymap/config', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (s) {
                // Adopt ONLY the a11y status — do NOT touch bindings/enabled/allowAdvanced.
                state.a11yEnabled = !!s.a11yEnabled;
                if (typeof s.a11yBound === 'boolean') state.a11yBound = s.a11yBound;
                // Refresh just the nudge card, not the whole list (paint() re-renders
                // from the untouched state.bindings, so it's safe, but keep it minimal).
                updateA11yCard();
                // If still not ready, arm one more recheck (self-terminates once it flips).
                scheduleA11yRecheck();
            })
            .catch(function () { /* transient — leave the nudge as-is */ });
    }

    // Show/hide the nudge from the honest readiness signal, and say WHICH state we're in:
    // "enabling…" while the OS precondition is met but the bind hasn't landed (the cold-boot
    // window, which resolves itself), versus the actionable nudge when the service is simply
    // not enabled. Without the distinction a user waiting out a normal boot is told to go
    // change system settings. Both elements are optional so an older page still works.
    function updateA11yCard() {
        var show = state.enabled && !a11yReady();
        // "Starting up" = the OS precondition is met but the bind hasn't landed. Nothing for the
        // user to do, so the amber "you must enable it" note AND the settings button are hidden;
        // the two notes are mutually exclusive. Showing both (the amber one had no id, so nothing
        // could hide it) gave contradictory advice during a normal car power-on.
        var starting = show && state.a11yEnabled;
        var card = $('kmA11yCard');
        if (card) card.style.display = show ? '' : 'none';
        var needed = $('kmA11yNeeded');
        if (needed) needed.style.display = (show && !starting) ? '' : 'none';
        var pending = $('kmA11yPending');
        if (pending) pending.style.display = starting ? '' : 'none';
        var openBtn = $('kmA11yOpenBtn');
        if (openBtn) openBtn.style.display = (show && !starting) ? '' : 'none';
    }

    function paint() {
        $('kmEnable').checked = state.enabled;
        $('kmAllowAdvanced').checked = state.allowAdvanced;
        // Double-tap window slider + live read-out (seconds, 1 decimal).
        var dtRange = $('kmDoubleTapWindow');
        if (dtRange) dtRange.value = state.doubleTapWindowMs;
        var dtVal = $('kmDoubleTapWindowVal');
        if (dtVal) dtVal.textContent = (state.doubleTapWindowMs / 1000).toFixed(1) + BYD.units.secondSuffix(true);

        // The "Advanced: shell" action only exists when the Advanced toggle is on.
        // Hiding the OPTION (not just gating on save) makes the relationship
        // obvious: flip the toggle → the shell choice appears/disappears. If it
        // was selected and Advanced is turned off, snap the picker back to the
        // safe curated kind so no stale shell form is left showing.
        var shellOpt = $('kmShellOption');
        if (shellOpt) {
            shellOpt.style.display = state.allowAdvanced ? '' : 'none';
            shellOpt.disabled = !state.allowAdvanced;
        }
        if (!state.allowAdvanced && $('kmActionKind').value === 'shell') {
            $('kmActionKind').value = 'curated';
        }
        onKindChange();

        paintStatusBadge();

        // Show the a11y nudge only while mapping is enabled but key filtering isn't live yet
        // (see a11yReady — bound, not merely the OS precondition).
        updateA11yCard();
        paintRestartWarning();

        renderList();
    }

    function paintStatusBadge() {
        var badge = $('kmStatusBadge');
        if (!badge) return;
        if (state.enabled) {
            badge.setAttribute('data-i18n', 'keymap.badge_on');
            badge.textContent = tr('keymap.badge_on');
            badge.className = 'status-badge active';
        } else {
            badge.setAttribute('data-i18n', 'keymap.badge_off');
            badge.textContent = tr('keymap.badge_off');
            badge.className = 'status-badge inactive';
        }
    }

    function paintRestartWarning() {
        var card = $('kmReplayRestartCard');
        if (card) card.style.display = state.restartRequired ? '' : 'none';
    }

    function renderList() {
        var list = $('kmList');
        var empty = $('kmEmpty');
        list.innerHTML = '';
        if (!state.bindings.length) {
            empty.style.display = '';
            return;
        }
        empty.style.display = 'none';
        for (var i = 0; i < state.bindings.length; i++) {
            list.appendChild(bindingRow(state.bindings[i], i));
        }
    }

    function bindingRow(b, index) {
        var row = document.createElement('div');
        row.className = 'km-binding';
        if (b.enabled === false) row.classList.add('km-disabled');

        var key = document.createElement('div');
        key.className = 'km-key';
        key.textContent = b.keycode;
        row.appendChild(key);

        var meta = document.createElement('div');
        meta.className = 'km-meta';
        var action = document.createElement('div');
        action.className = 'km-action';
        // `label` is a persisted display snapshot and may have been saved in another
        // locale. Rebuild known action summaries from their locale-neutral payload so
        // a live language switch cannot leave an old Portuguese label in the list.
        action.textContent = describeAction(b.action) || b.label || '';
        var sub = document.createElement('div');
        sub.className = 'km-sub';
        // For a double binding that blocks the native single, note it on the sub-line so
        // the list makes the behaviour visible (e.g. "Double press · blocks single-click").
        var subText = pressLabel(b.pressType);
        if (b.pressType === 'double' && b.blockNativeSingle === true) {
            subText += ' · ' + tr('keymap.block_native_single_tag');
        }
        if (b.enabled === false) subText += ' · ' + tr('keymap.disabled_tag');
        sub.textContent = subText;
        meta.appendChild(action);
        meta.appendChild(sub);
        row.appendChild(meta);

        var pill = document.createElement('span');
        pill.className = 'km-press-pill';
        pill.textContent = pressLabel(b.pressType);
        row.appendChild(pill);

        // Enable/disable toggle — lets the user temporarily disable a binding
        // without deleting it. The dispatcher already skips bindings with enabled:false.
        var toggle = document.createElement('label');
        toggle.className = 'toggle-switch toggle-switch-sm';
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = b.enabled !== false;
        cb.onchange = (function (idx) {
            return function () { toggleBinding(idx, this.checked); };
        })(index);
        var slider = document.createElement('span');
        slider.className = 'toggle-slider';
        toggle.appendChild(cb);
        toggle.appendChild(slider);
        row.appendChild(toggle);

        // Edit — pre-fill the Add form from this binding and switch to the Add tab.
        var edit = document.createElement('button');
        edit.className = 'btn btn-icon';
        edit.setAttribute('aria-label', tr('keymap.edit'));
        edit.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:16px;height:16px;"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
        edit.onclick = function () { editBinding(index); };
        row.appendChild(edit);

        var del = document.createElement('button');
        del.className = 'btn btn-icon btn-danger-ghost';
        del.setAttribute('aria-label', tr('keymap.delete'));
        del.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
        del.onclick = function () { removeBinding(index); };
        row.appendChild(del);
        return row;
    }

    function toggleBinding(index, enabled) {
        if (index < 0 || index >= state.bindings.length) return;
        // Capture the binding OBJECT, not the integer index: persist() is async, and
        // the user can delete/replace another binding (removeBinding's doDelete splices,
        // addBinding's dedup splices) while this POST is in flight — reindexing the
        // array. A splice moves references but never invalidates them, so rolling back
        // on the object survives a concurrent reshape; an index reach-back would flip
        // the wrong binding or write past the shrunken array. Mirrors the snapshot/
        // resync discipline addBinding + removeBinding already use.
        var b = state.bindings[index];
        b.enabled = enabled;
        persist(function (ok) {
            if (!ok) {
                b.enabled = !enabled;
                toast(tr('keymap.save_failed'), 'error');
            }
            renderList();
        });
    }

    function pressLabel(t) {
        if (t === 'double') return tr('keymap.press_double');
        if (t === 'long') return tr('keymap.press_long');
        return tr('keymap.press_single');
    }

    function describeClipWindow(beforeSeconds, afterSeconds) {
        if (beforeSeconds > 0 && afterSeconds > 0) {
            return tr('keymap.clip_summary_both', {
                before: beforeSeconds,
                after: afterSeconds
            });
        }
        if (beforeSeconds > 0) {
            return tr('keymap.clip_summary_before', { before: beforeSeconds });
        }
        return tr('keymap.clip_summary_after', { after: afterSeconds });
    }

    // Human summary of a fire-payload, used when a binding has no stored label.
    function describeAction(a) {
        if (!a) return '';
        if (a.kind === 'vehicle') return tr('keymap.act_' + a.action);
        if (a.kind === 'manualClip') {
            var clipBefore = parseInt(a.beforeSeconds, 10);
            var clipAfter = parseInt(a.afterSeconds, 10);
            if (isNaN(clipBefore)) clipBefore = 0;
            if (isNaN(clipAfter)) clipAfter = 0;
            return tr('keymap.act_manual_clip') + ' — '
                + describeClipWindow(clipBefore, clipAfter);
        }
        if (a.kind === 'catalog') {
            var c = curatedById(a.key);
            var name = c ? tr(c.i18n) : a.key;
            return a.payload ? (name + ' — ' + a.payload) : name;
        }
        if (a.kind === 'api') {
            var ca = curatedById(a.id);
            var nm = ca ? tr(ca.i18n) : (a.path || a.id || 'api');
            // Surface the chosen value so e.g. "Surveillance — enable" reads clearly.
            // Only show a value we can attribute to a real payload — NOT a blind
            // last-path-segment, which mislabels fixed-path actions (camview's
            // ?preset=60/center → "center", stop-audio → "stop-audio").
            var v = '';
            if (a.body && a.body.indexOf('"name"') >= 0) {
                // Play audio/video: the sound filename is the payload.
                var mn = a.body.match(/"name"\s*:\s*"([^"]*)"/);
                if (mn) v = mn[1];
            } else if (a.body && a.body.indexOf('"package"') >= 0) {
                // Cast app to cluster: the package is the payload. Show the friendly app
                // label from the cached picker list when we have it, else the package —
                // so two cluster-cast bindings for different apps are distinguishable.
                var mpk = a.body.match(/"package"\s*:\s*"([^"]*)"/);
                if (mpk) {
                    v = mpk[1];
                    if (appList) {
                        for (var ai = 0; ai < appList.length; ai++) {
                            if (appList[ai] && appList[ai]['package'] === mpk[1]) {
                                v = appList[ai].label || mpk[1];
                                break;
                            }
                        }
                    }
                }
            } else if (a.body && a.body.indexOf('"mode"') >= 0) {
                var m = a.body.match(/"mode"\s*:\s*"([^"]*)"/);
                if (m) v = m[1];
            } else if (a.body && a.body.indexOf('"position"') >= 0) {
                // Seat recall/save: the slot lives in the body as "position":N, and
                // the path is the constant /api/vehicle/seat — surface the slot so
                // "Recall seat position — Slot 1" distinguishes the two slots.
                var mp = a.body.match(/"position"\s*:\s*(\d+)/);
                if (mp) v = tr('keymap.seat_slot_' + mp[1]) || mp[1];
            } else if (ca && ca.path && ca.path.indexOf('${v}') >= 0 && a.path) {
                // The curated template puts the payload IN the path. It may sit in the
                // path proper (e.g. /api/surveillance/${v}) OR inside the query string
                // (e.g. /api/camview/show?...&preset=${v}/center for the camera-view
                // size). Diff over whichever segment holds ${v}: if the placeholder is
                // BEFORE any '?', compare path-only (ignoring the query); otherwise
                // compare the full string so a query-embedded ${v} is recoverable.
                var q = ca.path.indexOf('?');
                var vInQuery = q >= 0 && ca.path.indexOf('${v}') > q;
                var cleanTpl = vInQuery ? ca.path : ca.path.split('?')[0];
                var cleanPath = vInQuery ? a.path : a.path.split('?')[0];
                var tv = recoverV(cleanTpl, cleanPath);
                // Map a recovered camview size % back to its friendly label.
                if (tv != null && ca.payloads) {
                    for (var pi = 0; pi < ca.payloads.length; pi++) {
                        if (ca.payloads[pi].v === tv) { v = tr(ca.payloads[pi].i18n) || tv; break; }
                    }
                    if (!v) v = tv;
                } else if (tv != null) {
                    v = tv;
                }
            }
            return v ? (nm + ' — ' + v) : nm;
        }
        if (a.kind === 'automation') {
            // Prefer the saved name; fall back to the id so a nameless/older target
            // still reads. (The daemon always resolves by id, not this label.)
            return tr('keymap.act_run_automation') + ': ' + (a.name || a.id || '');
        }
        if (a.kind === 'actionGroup') {
            return tr('keymap.act_run_action_group') + ': ' + (a.name || a.id || '');
        }
        if (a.kind === 'radio') {
            var rc = matchCuratedByRadio(a.radio);
            var rlabel = rc ? tr(curatedById(rc).i18n) : (a.radio || 'radio');
            var rstate = tr(a.state === 'off' ? 'keymap.off' : 'keymap.on');
            return rlabel + ' — ' + rstate;
        }
        if (a.kind === 'openApp') {
            var appName = a.label || a['package'] || '';
            var suffix = a.split ? ' (' + tr('keymap.split_screen') + ')' : '';
            return tr('keymap.kind_open_app') + ': ' + appName + suffix;
        }
        if (a.kind === 'shell') return tr('keymap.kind_shell') + ': ' + (a.cmd || '');
        if (a.kind === 'sequence') {
            var steps = a.steps || [];
            var parts = [];
            for (var i = 0; i < steps.length; i++) parts.push(describeAction(steps[i]));
            return parts.join(' → ');
        }
        return a.kind || '';
    }

    // ───────────────────────── Config toggles ─────────────────────────

    function saveConfig() {
        state.enabled = $('kmEnable').checked;
        state.allowAdvanced = $('kmAllowAdvanced').checked;
        persist(function (ok) {
            if (ok) { paint(); } else { toast(tr('keymap.save_failed'), 'error'); }
        });
    }

    // Double-tap window slider: update the live read-out on every input tick (no
    // POST), and persist only on release (change) so dragging doesn't spam the
    // daemon. The daemon clamps to 250..1500ms; the slider min/max already match.
    function onDoubleTapWindowInput() {
        var r = $('kmDoubleTapWindow');
        if (!r) return;
        state.doubleTapWindowMs = parseInt(r.value, 10) || 450;
        var dtVal = $('kmDoubleTapWindowVal');
        if (dtVal) dtVal.textContent = (state.doubleTapWindowMs / 1000).toFixed(1) + BYD.units.secondSuffix(true);
    }
    function onDoubleTapWindowChange() {
        onDoubleTapWindowInput();
        persist(function (ok) {
            if (!ok) toast(tr('keymap.save_failed'), 'error');
        });
    }

    function persist(cb) {
        fetch('/api/keymap/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                enabled: state.enabled,
                allowAdvanced: state.allowAdvanced,
                doubleTapWindowMs: state.doubleTapWindowMs,
                bindings: state.bindings
            })
        }).then(function (r) { return r.json(); })
          .then(function (d) {
              // The save response echoes the live a11y state (the daemon may have
              // just auto-enabled the service on the enable edge), so adopt it and
              // let paint() hide the nag without waiting for a full reload.
              if (d && typeof d.a11yEnabled !== 'undefined') state.a11yEnabled = !!d.a11yEnabled;
              if (d && typeof d.a11yBound === 'boolean') state.a11yBound = d.a11yBound;
              if (d && d.success && typeof d.restartRequired !== 'undefined') {
                  state.restartRequired = !!d.restartRequired;
                  paintRestartWarning();
              }
              // An enable-edge save reports the precondition true but the bind not yet
              // landed (AMS binds asynchronously). Refresh the card from the honest signal
              // and arm the recheck so it clears itself once the service is really up,
              // instead of leaving a stale nudge until the next manual reload.
              updateA11yCard();
              a11yRechecksLeft = A11Y_RECHECK_BUDGET;
              scheduleA11yRecheck();
              if (cb) cb(!!(d && d.success));
          })
          .catch(function () { if (cb) cb(false); });
    }

    // ───────────────────────── Capture ─────────────────────────

    function toggleCapture() {
        capturing = !capturing;
        var box = $('kmCaptureBox');
        var label = $('kmCaptureBtnLabel');
        var hint = $('kmCapHint');
        if (capturing) {
            box.classList.add('armed');
            hint.setAttribute('data-i18n', 'keymap.capture_press');
            hint.textContent = tr('keymap.capture_press');
            label.setAttribute('data-i18n', 'keymap.capture_stop');
            label.textContent = tr('keymap.capture_stop');
        } else {
            box.classList.remove('armed');
            label.setAttribute('data-i18n', 'keymap.capture');
            label.textContent = tr('keymap.capture');
            if (!captured) {
                hint.setAttribute('data-i18n', 'keymap.capture_idle');
                hint.textContent = tr('keymap.capture_idle');
            }
        }
        // Tell the native side to route hardware keys to us while armed. Hardware
        // buttons hit the native AccessibilityService (onKeyEvent), NOT the WebView
        // DOM, so without this the capture box can only ever see keys that happen
        // to reach the DOM. When armed, the dispatcher forwards + consumes the key
        // and calls window.KM.onNativeKey(code) below.
        try {
            if (window.AndroidBridge && AndroidBridge.setKeyCapture) {
                AndroidBridge.setKeyCapture(capturing);
            }
        } catch (e) { /* not in the app WebView — DOM keydown fallback still works */ }
    }

    // Record a captured keycode (shared by the native bridge and the DOM
    // fallback). Reflects into the display + manual field and disarms.
    function recordCapturedKey(code) {
        if (!code) return;
        captured = code;
        $('kmCapKeycode').textContent = code;
        $('kmCapHint').setAttribute('data-i18n', 'keymap.captured');
        $('kmCapHint').textContent = tr('keymap.captured');
        $('kmManualKeycode').value = code;
        // If the captured code is a known long-variant (302/303/306/312), narrow
        // the press-type selector to fixed:'single' now — those codes fire on
        // arrival, so a bound "long" would be a dead binding.
        refreshPressTypeForManual();
        if (capturing) toggleCapture(); // disarm after one capture (also clears native capture)
    }

    // Called by the native AccessibilityService (via evaluateJavascript) when a
    // hardware key arrives during capture — the reliable path on the head unit.
    function onNativeKey(code) {
        if (!capturing) return;
        recordCapturedKey(code);
    }

    // In-page keydown fallback for phone/desktop PWA (and any key that DOES reach
    // the DOM). On the head unit the native bridge above is the real path.
    function onKeydown(e) {
        if (!capturing) return;
        var code = e.keyCode || e.which;
        if (!code) return;
        e.preventDefault();
        recordCapturedKey(code);
    }

    // ───────────────────────── Add / remove ─────────────────────────

    function onKindChange() {
        formDirty = true;
        var kind = $('kmActionKind').value;
        $('kmCuratedWrap').style.display = kind === 'curated' ? '' : 'none';
        $('kmShellWrap').style.display = kind === 'shell' ? '' : 'none';
        var appWrap = $('kmOpenAppWrap');
        if (appWrap) appWrap.style.display = kind === 'openApp' ? '' : 'none';
        if (kind === 'openApp') {
            var splitEl = $('kmOpenAppSplit');
            if (splitEl) splitEl.checked = false; // fresh default when switching into openApp
            buildAppOptions();
        }
    }

    // Rebuild the payload dropdown for the selected curated action. `preselect`
    // (optional) is a payload value to select once options are built — used by
    // editBinding() to restore a saved choice, including for dynamic (async) lists.
    function onCuratedChange(preselect) {
        formDirty = true;
        var c = curatedById($('kmCuratedAction').value);
        var row = $('kmPayloadRow');
        var sel = $('kmPayload');
        var clipWrap = $('kmClipWindowWrap');
        var isManualClip = !!(c && c.kind === 'manualClip');
        if (clipWrap) clipWrap.style.display = isManualClip ? '' : 'none';
        sel.innerHTML = '';
        if (isManualClip) {
            // Manual replay has two numeric dimensions instead of the regular
            // one-value payload dropdown. Invalidate any in-flight dynamic fetch
            // and drive the dedicated clip-window controls instead.
            payloadReqSeq++;
            row.style.display = 'none';
            applyClipPreset(false);
            return;
        }
        var dyn = (c && c.dynamicPayloads) ? DYNAMIC_PAYLOAD[c.dynamicPayloads] : null;
        if (dyn) {
            // Live-populate from an async source (audio library, or the saved-automation
            // list). Driven by the DYNAMIC_PAYLOAD descriptor so audio and automation
            // share one code path — only the URL / labels / extract() differ.
            row.style.display = '';
            var loading = document.createElement('option');
            loading.value = '';
            loading.textContent = tr(dyn.loading);
            loading.disabled = true; loading.selected = true;
            sel.appendChild(loading);
            // Stale-response guard: a fetch is async, but the user may switch the
            // curated action before it resolves. Stamp this request; on resolve, bail
            // if a newer onCuratedChange has run (token bumped) or the selected action
            // is no longer THIS dynamic one — otherwise a late response would clobber a
            // different action's (synchronously-built) payload dropdown.
            var myToken = ++payloadReqSeq;
            var myActionId = c.id;
            var myType = c.dynamicPayloads;
            var stillMine = function () {
                if (myToken !== payloadReqSeq) return false;
                var cur = curatedById($('kmCuratedAction').value);
                return !!(cur && cur.id === myActionId && cur.dynamicPayloads === myType);
            };
            fetch(dyn.url, { cache: 'no-store' })
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    if (!stillMine()) return;
                    var items = dyn.extract(j) || [];
                    sel.innerHTML = '';
                    if (!items.length) {
                        var none = document.createElement('option');
                        none.value = '';
                        none.textContent = tr(dyn.none);
                        none.disabled = true; none.selected = true;
                        sel.appendChild(none);
                        return;
                    }
                    for (var k = 0; k < items.length; k++) {
                        var so = document.createElement('option');
                        so.value = items[k].value;
                        so.textContent = items[k].text;
                        sel.appendChild(so);
                    }
                    // Restore a saved selection if it's still present; else keep the first.
                    if (preselect) {
                        var found = false;
                        for (var m = 0; m < sel.options.length; m++) {
                            if (sel.options[m].value === preselect) { found = true; break; }
                        }
                        if (!found) {
                            var miss = document.createElement('option');
                            miss.value = preselect;
                            miss.textContent = preselect + ' ' + tr(dyn.missing);
                            sel.appendChild(miss);
                        }
                        sel.value = preselect;
                    }
                })
                ['catch'](function () {
                    if (!stillMine()) return;
                    sel.innerHTML = '';
                    var err = document.createElement('option');
                    err.value = '';
                    err.textContent = tr(dyn.none);
                    err.disabled = true; err.selected = true;
                    sel.appendChild(err);
                });
            return;
        }
        // A synchronous rebuild also invalidates any in-flight dynamic fetch.
        payloadReqSeq++;
        if (c && c.payloads && c.payloads.length) {
            row.style.display = '';
            for (var i = 0; i < c.payloads.length; i++) {
                var o = document.createElement('option');
                o.value = c.payloads[i].v;
                o.textContent = tr(c.payloads[i].i18n);
                sel.appendChild(o);
            }
            if (preselect) sel.value = preselect;
        } else {
            row.style.display = 'none';
        }
    }

    function updateClipWindowLabels() {
        var before = parseInt($('kmClipBefore').value, 10);
        var after = parseInt($('kmClipAfter').value, 10);
        if (isNaN(before)) before = 0;
        if (isNaN(after)) after = 0;
        $('kmClipBeforeValue').textContent = tr('keymap.clip_seconds', { n: before });
        $('kmClipAfterValue').textContent = tr('keymap.clip_seconds', { n: after });
    }

    // Apply one of the curated windows. "Custom" keeps the current values and
    // reveals both sliders so the user can pick any whole-second split.
    function applyClipPreset(markDirty) {
        var preset = $('kmClipPreset');
        var customWrap = $('kmClipCustomWrap');
        if (!preset || !customWrap) return;
        var value = preset.value || CLIP_DEFAULT_PRESET;
        var custom = value === 'custom';
        customWrap.style.display = custom ? '' : 'none';
        if (!custom) {
            var parts = value.split(':');
            if (parts.length === 2) {
                $('kmClipBefore').value = parseInt(parts[0], 10);
                $('kmClipAfter').value = parseInt(parts[1], 10);
            }
        }
        updateClipWindowLabels();
        if (markDirty) formDirty = true;
    }

    function onClipPresetChange() {
        applyClipPreset(true);
    }

    function onClipWindowChange() {
        formDirty = true;
        // Slider edits always represent an intentional custom window, even if
        // the resulting pair happens to equal one of the convenience presets.
        var preset = $('kmClipPreset');
        if (preset) preset.value = 'custom';
        updateClipWindowLabels();
    }

    function resetClipWindow() {
        var preset = $('kmClipPreset');
        if (preset) preset.value = CLIP_DEFAULT_PRESET;
        var before = $('kmClipBefore');
        var after = $('kmClipAfter');
        if (before) before.value = 30;
        if (after) after.value = 0;
        applyClipPreset(false);
    }

    // Restore a saved manualClip window into the form when editing a binding.
    // Selects the matching preset when the pair is one of the presets, else
    // switches to Custom and reveals both sliders — the inverse of readClipWindow.
    function applyClipWindowToForm(beforeSeconds, afterSeconds) {
        var before = parseInt(beforeSeconds, 10);
        var after = parseInt(afterSeconds, 10);
        if (isNaN(before) || before < 0) before = 0;
        if (isNaN(after) || after < 0) after = 0;
        var beforeEl = $('kmClipBefore');
        var afterEl = $('kmClipAfter');
        if (beforeEl) beforeEl.value = before;
        if (afterEl) afterEl.value = after;
        var preset = $('kmClipPreset');
        if (preset) {
            var pair = before + ':' + after;
            var matched = false;
            for (var i = 0; i < preset.options.length; i++) {
                if (preset.options[i].value === pair) { preset.value = pair; matched = true; break; }
            }
            if (!matched) preset.value = 'custom';
        }
        applyClipPreset(false);
    }

    // Parse and validate the persisted manualClip contract. HTML range inputs
    // already constrain normal interaction, but this remains strict so a DOM edit
    // cannot save fractions, negatives, or a window longer than the daemon accepts.
    function readClipWindow() {
        var beforeRaw = $('kmClipBefore').value;
        var afterRaw = $('kmClipAfter').value;
        var wholeSeconds = /^\d+$/;
        var before = Number(beforeRaw);
        var after = Number(afterRaw);
        var valid = wholeSeconds.test(beforeRaw) && wholeSeconds.test(afterRaw)
            && isFinite(before) && isFinite(after)
            && Math.floor(before) === before && Math.floor(after) === after
            && before >= 0 && before <= CLIP_MAX_BEFORE_SECONDS
            && after >= 0 && after <= CLIP_MAX_AFTER_SECONDS
            && before + after >= 1 && before + after <= CLIP_MAX_TOTAL_SECONDS;
        if (!valid) {
            toast(tr('keymap.clip_window_invalid'), 'error');
            return null;
        }
        return { beforeSeconds: before, afterSeconds: after };
    }

    function buildCuratedOptions(payloadPreselect) {
        var sel = $('kmCuratedAction');
        var selected = sel.value;
        sel.innerHTML = '';
        // Bucket options by category. Confirmed BEVs omit controls that require an engine.
        var buckets = {};
        var i;
        for (i = 0; i < CURATED.length; i++) {
            if (state.fuelCapableHybrid === false
                    && HYBRID_ONLY_CURATED[CURATED[i].id]) continue;
            var cat = KM_CATEGORY[CURATED[i].id] || 'other';
            if (!buckets[cat]) buckets[cat] = [];
            buckets[cat].push(CURATED[i]);
        }
        // Emit <optgroup>s in the declared order; any un-ordered category (and 'other')
        // sorts last so a newly-added action is still reachable.
        var cats = Object.keys(buckets).sort(function (a, b) {
            var ra = a === 'other' ? 9998 : KM_CATEGORY_ORDER.indexOf(a);
            var rb = b === 'other' ? 9998 : KM_CATEGORY_ORDER.indexOf(b);
            if (ra < 0) ra = 9997; if (rb < 0) rb = 9997;
            return ra - rb;
        });
        for (i = 0; i < cats.length; i++) {
            var group = document.createElement('optgroup');
            group.label = kmCategoryLabel(cats[i]);
            var items = buckets[cats[i]].slice().sort(function (a, b) {
                var byLabel = String(tr(a.i18n)).localeCompare(
                    String(tr(b.i18n)), undefined, { sensitivity: 'base', numeric: true });
                return byLabel || String(a.id).localeCompare(String(b.id));
            });
            for (var j = 0; j < items.length; j++) {
                var o = document.createElement('option');
                o.value = items[j].id;
                o.textContent = tr(items[j].i18n);
                group.appendChild(o);
            }
            sel.appendChild(group);
        }
        if (selected && curatedById(selected)
                && !(state.fuelCapableHybrid === false && HYBRID_ONLY_CURATED[selected])) {
            sel.value = selected;
        }
        onCuratedChange(payloadPreselect);
    }

    // Cached installed-app list [{package,label}] for the openApp picker.
    var appList = null;

    // Populate the app picker from /api/apps/list (fetched once, then cached).
    // Shows a "loading" placeholder until the list arrives.
    function buildAppOptions() {
        var sel = $('kmOpenApp');
        if (!sel) return;
        var fill = function (apps) {
            var prev = sel.value;
            sel.innerHTML = '';
            var ph = document.createElement('option');
            ph.value = '';
            ph.textContent = tr('keymap.pick_app');
            ph.disabled = true; ph.selected = true;
            sel.appendChild(ph);
            var prevFound = false;
            for (var i = 0; i < apps.length; i++) {
                var o = document.createElement('option');
                o.value = apps[i]['package'];
                o.textContent = apps[i].label || apps[i]['package'];
                sel.appendChild(o);
                if (prev && apps[i]['package'] === prev) prevFound = true;
            }
            // If the previously-selected package (e.g. one injected by editBinding for
            // an app that's since been uninstalled, or not yet in the fetched list) is
            // absent, re-add it so the selection round-trips instead of silently
            // reverting to the placeholder (which would make the edit unsaveable).
            if (prev && !prevFound) {
                var keep = document.createElement('option');
                keep.value = prev;
                keep.textContent = prev;
                sel.appendChild(keep);
            }
            if (prev) sel.value = prev;
        };
        if (appList) { fill(appList); return; }
        // placeholder while loading
        sel.innerHTML = '';
        var loading = document.createElement('option');
        loading.value = '';
        loading.textContent = tr('keymap.loading_apps');
        loading.disabled = true; loading.selected = true;
        sel.appendChild(loading);
        fetch('/api/apps/list', { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (j) {
                appList = (j && j.success && j.apps) ? j.apps : [];
                fill(appList);
            })
            ['catch'](function () { appList = []; fill(appList); });
    }

    // Populate the "Button" dropdown from KNOWN_BUTTONS. First option is a
    // placeholder ("Choose a button…"); a final option is the custom/capture
    // escape hatch that reveals the capture box + manual field.
    function buildKnownButtonOptions() {
        var sel = $('kmKnownButton');
        if (!sel) return;
        sel.innerHTML = '';
        var ph = document.createElement('option');
        ph.value = '';
        ph.textContent = tr('keymap.pick_button');
        sel.appendChild(ph);
        for (var i = 0; i < KNOWN_BUTTONS.length; i++) {
            var b = KNOWN_BUTTONS[i];
            var o = document.createElement('option');
            o.value = String(b.code);
            // Show the code so power users can still see it: "Next  (87)"
            o.textContent = tr(b.i18n) + '  (' + b.code + ')';
            sel.appendChild(o);
        }
        var custom = document.createElement('option');
        custom.value = 'custom';
        custom.textContent = tr('keymap.btn_custom');
        sel.appendChild(custom);
    }

    function knownByCode(code) {
        for (var i = 0; i < KNOWN_BUTTONS.length; i++) {
            if (KNOWN_BUTTONS[i].code === code) return KNOWN_BUTTONS[i];
        }
        return null;
    }

    // When a known button is chosen: set the keycode, reveal/hide the capture
    // area, and tailor the press-type selector to what THIS button supports.
    // Show the "OEM function may still fire" note iff the chosen keycode is one the
    // firmware handles below the app (Mute / BT-Phone). Safe on a missing element.
    function syncNativeSideEffectWarn(code) {
        var w = $('kmNativeSideEffectWarn');
        if (!w) return;
        w.style.display = (code != null && NATIVE_SIDE_EFFECT_CODES[code]) ? '' : 'none';
    }

    function onKnownButtonChange() {
        var sel = $('kmKnownButton');
        var val = sel ? sel.value : '';
        var customWrap = $('kmCustomKeyWrap');
        // Leaving the custom/capture branch while still armed would hide the
        // capture wrap without disarming — the dispatcher would keep globally
        // consuming (and silently swallowing) the next hardware key, suppressing
        // its OEM action. toggleCapture() no-ops the display side effects here but
        // flips `capturing` false AND calls AndroidBridge.setKeyCapture(false),
        // clearing WebViewFragment.captureArmed. Re-entering custom from a
        // non-armed state is unaffected (arming still happens via the Capture btn).
        if (val !== 'custom' && capturing) toggleCapture();
        if (val === 'custom') {
            // Reveal the capture + manual-entry area (the secondary path). An
            // arbitrary captured/typed key gets the full press-type choice (we
            // can't know the firmware's gesture model for an unknown keycode) —
            // but if the entered/captured code turns out to be a KNOWN
            // long-variant (302/303/306/312), refreshPressTypeForManual() locks
            // it to fixed:'single' so the user can't create a never-firing
            // "long" binding (those codes arrive as a plain repeatCount==0 DOWN).
            if (customWrap) customWrap.style.display = '';
            captured = null;
            $('kmManualKeycode').value = '';
            refreshPressTypeForManual();
            syncNativeSideEffectWarn(null); // re-evaluated from the typed keycode below
            return;
        }
        if (customWrap) customWrap.style.display = 'none';
        if (!val) { captured = null; $('kmManualKeycode').value = ''; buildPressTypeOptions(null); syncNativeSideEffectWarn(null); return; }
        var code = parseInt(val, 10);
        captured = code;
        $('kmManualKeycode').value = code;
        buildPressTypeOptions(knownByCode(code));
        syncNativeSideEffectWarn(code);
    }

    // Rebuild the press-type <select> for the chosen button:
    //   • fixed button (long-variant keycode) → single locked option; the keycode
    //     arriving already IS the gesture, so it fires on arrival (pressType single),
    //     and we disable the selector so the user can't pick a never-firing "long".
    //   • base button with pressTypes → only those options (no "long", since long
    //     is a different keycode on this firmware).
    //   • null (custom/capture) → all three, free choice.
    function buildPressTypeOptions(btn) {
        var pt = $('kmPressType');
        if (!pt) return;
        var opts;
        var disabled = false;
        if (btn && btn.fixed) { opts = [btn.fixed]; disabled = true; }
        else if (btn && btn.pressTypes) { opts = btn.pressTypes; }
        else { opts = ['single', 'double', 'long']; }
        var labelKey = { single: 'keymap.press_single', double: 'keymap.press_double', long: 'keymap.press_long' };
        pt.innerHTML = '';
        for (var i = 0; i < opts.length; i++) {
            var o = document.createElement('option');
            o.value = opts[i];
            o.textContent = tr(labelKey[opts[i]] || opts[i]);
            pt.appendChild(o);
        }
        pt.value = opts[0];
        pt.disabled = disabled;
        syncBlockNativeRow();
    }

    // Show the "Block single-click action" toggle ONLY when the press type is "double"
    // (it is meaningless for single/long). Called on every press-type change — user-
    // driven (onPressTypeChange) and programmatic (buildPressTypeOptions / editBinding).
    // Clears the checkbox when hidden so a stale value can't be serialized for a
    // non-double binding. Defaults to CHECKED when switching to double so a newly-
    // created double binding suppresses the stock action (the intuitive expectation —
    // "I mapped this key, so it shouldn't also do the old thing"). Users who want the
    // stock single-tap to pass through can uncheck it.
    function syncBlockNativeRow() {
        var row = $('kmBlockNativeRow');
        var pt = $('kmPressType');
        if (!row || !pt) return;
        var isDouble = pt.value === 'double';
        row.style.display = isDouble ? '' : 'none';
        if (!isDouble) { var cb = $('kmBlockNativeSingle'); if (cb) cb.checked = false; }
        else {
            var cb = $('kmBlockNativeSingle');
            // Default ON for fresh bindings (editIndex === -1); when editing, the
            // saved value is restored by editBinding() AFTER this runs.
            if (cb && editIndex < 0) cb.checked = true;
        }
    }

    function onPressTypeChange() { syncBlockNativeRow(); }

    // Press-type selector for the manual/custom path. Reads the current manual
    // keycode and, if it is a KNOWN button (base or long-variant), restricts the
    // selector to exactly what that keycode supports — locking the four
    // long-variant keycodes (302/303/306/312) to fixed:'single' so the custom
    // path can't mint a dead "long" (or an unperformable double-of-a-hold)
    // binding. A genuinely-arbitrary keycode still gets all three types (the
    // escape hatch for platforms that DO auto-repeat). Called on entering the
    // custom branch, after capturing a key, and on every manual-keycode edit.
    function refreshPressTypeForManual() {
        var code = parseInt($('kmManualKeycode').value, 10);
        buildPressTypeOptions(code ? knownByCode(code) : null);
        // A manually-typed/captured Mute(293) or Phone-BT(313) code gets the same
        // "OEM function may still fire" note as picking it from the known list.
        syncNativeSideEffectWarn(code ? code : null);
    }

    // In-progress sequence steps (each a fire-action object). When non-empty,
    // "Add binding" wraps them as a {kind:'sequence'} action; a lone step is
    // stored as that single action (no needless sequence wrapper).
    var seqSteps = [];

    // Index of the binding currently being edited, or -1 when adding a fresh one.
    // Set by editBinding(); consumed by addBinding() to replace that exact slot
    // (so a keycode/pressType change during edit doesn't leave the original behind);
    // cleared by resetForm(). Kept as an index into state.bindings — the list is not
    // reordered between opening the editor and saving (persist only happens on save).
    var editIndex = -1;

    // Has the Add-form's action selection been touched since the last "Add step"
    // (or form reset)? Because buildActionFromForm() never returns null for the
    // default curated pick, "Add binding" cannot tell a genuinely-edited-but-not-
    // added selection from the leftover of the previous "Add step". This flag
    // lets addBinding() fold a pending selection ONLY when the user actually
    // edited it, so the documented "Add step … Add binding" chain doesn't
    // silently duplicate its last action (e.g. lock → flash → flash).
    var formDirty = false;

    // Build one fire-action object from the current Add-form selection, or null
    // (with a toast) if the form is incomplete. Shared by "Add binding" and
    // "Add step" so a step and a standalone binding are built identically.
    function buildActionFromForm() {
        var kind = $('kmActionKind').value;
        if (kind === 'curated') {
            var c = curatedById($('kmCuratedAction').value);
            if (!c) { toast(tr('keymap.need_action'), 'error'); return null; }
            if (c.kind === 'vehicle') return { kind: 'vehicle', action: c.key };
            if (c.kind === 'manualClip') {
                var clipWindow = readClipWindow();
                if (!clipWindow) return null;
                return {
                    kind: 'manualClip',
                    beforeSeconds: clipWindow.beforeSeconds,
                    afterSeconds: clipWindow.afterSeconds
                };
            }
            var payload = ($('kmPayload').value != null) ? $('kmPayload').value : '';
            if (c.kind === 'automation') {
                // The payload is the target automation's id; carry its display name
                // too so the list reads well and survives even if the automation is
                // later renamed/removed (daemon resolves by id at fire time).
                if (!payload) { toast(tr('keymap.need_automation'), 'error'); return null; }
                var selEl = $('kmPayload');
                var nm = (selEl && selEl.selectedIndex >= 0) ? selEl.options[selEl.selectedIndex].textContent : '';
                return { kind: 'automation', id: payload, name: nm };
            }
            if (c.kind === 'actionGroup') {
                // Same shape as the automation kind: payload is the group id, name is
                // carried for display only (the daemon resolves by id at fire time).
                if (!payload) { toast(tr('keymap.need_action_group'), 'error'); return null; }
                var grpEl = $('kmPayload');
                var grpNm = (grpEl && grpEl.selectedIndex >= 0) ? grpEl.options[grpEl.selectedIndex].textContent : '';
                return { kind: 'actionGroup', id: payload, name: grpNm };
            }
            if (c.kind === 'radio') {
                // radio id is fixed per curated action; payload is the on/off state.
                return { kind: 'radio', radio: c.radio, state: payload || 'on' };
            }
            if (c.kind === 'api') {
                // If this action's template needs a payload (${v} in path or body) but
                // none was chosen, refuse rather than baking an empty value — e.g. a
                // "Play Video" bound with no file picked would fire {"name":""} and the
                // daemon would reject it with "name or path is required" (silent no-play).
                var needsPayload = (c.path && c.path.indexOf('${v}') >= 0) ||
                                   (c.body && c.body.indexOf('${v}') >= 0);
                if (needsPayload && !payload) {
                    toast(tr(c.dynamicPayloads === 'audio' ? 'keymap.need_audio' : 'keymap.need_value'), 'error');
                    return null;
                }
                // Substitute the chosen payload into the path/body templates; the
                // daemon receives a concrete method/path/body (no ${v} at fire time).
                var sub = function (s) { return (s || '').split('${v}').join(payload); };
                return { kind: 'api', id: c.id, method: c.method || 'POST',
                         path: sub(c.path), body: sub(c.body) };
            }
            var a = { kind: 'catalog', key: c.key, payload: payload };
            if (c.sub) a.sub = c.sub;
            return a;
        }
        if (kind === 'openApp') {
            var sel = $('kmOpenApp');
            var pkg = sel ? sel.value : '';
            if (!pkg) { toast(tr('keymap.need_app'), 'error'); return null; }
            var label = '';
            if (sel && sel.selectedIndex >= 0) label = sel.options[sel.selectedIndex].textContent;
            var splitEl = $('kmOpenAppSplit');
            var split = !!(splitEl && splitEl.checked);
            var act = { kind: 'openApp', 'package': pkg, label: label };
            // Only carry the flag when set, so existing (non-split) bindings serialize unchanged.
            if (split) act.split = true;
            return act;
        }
        if (kind === 'shell') {
            if (!state.allowAdvanced) { toast(tr('keymap.advanced_disabled'), 'error'); return null; }
            var cmd = $('kmShellCmd').value.trim();
            if (!cmd) { toast(tr('keymap.need_cmd'), 'error'); return null; }
            return { kind: 'shell', cmd: cmd };
        }
        return null;
    }

    // Append the current form selection to the in-progress sequence and re-render
    // the step chips. Lets the user compose "close windows → lock → sport mode".
    // After a successful push we blank the action inputs so the just-added value
    // isn't silently re-appended by addBinding()'s pending-fold (WYSIWYG: what's
    // in the chips is what fires).
    function addStep() {
        var a = buildActionFromForm();
        if (!a) return;
        if (a.kind === 'manualClip') {
            toast(tr('keymap.clip_sequence_unsupported'), 'warning');
            return;
        }
        seqSteps.push(a);
        clearActionForm();
        renderSteps();
    }

    // ── Quick controls (dashboard buttons) ────────────────────────────────────
    // A quick-control button stores the SAME action payload a key binding does, so the
    // whole action builder above is reused verbatim: compose the action (single or
    // sequence) exactly as for a key, then save it as a button instead of to a keycode.
    // The daemon fires both through one executor, so a button can never do more than a key.

    var qcButtons = [];

    function loadQuickControls() {
        fetch('/api/quick-controls').then(function (r) { return r.json(); }).then(function (j) {
            qcButtons = (j && j.success && j.buttons) ? j.buttons : [];
            renderQuickControls();
        }).catch(function () { renderQuickControls(); });
    }

    function renderQuickControls() {
        var host = $('kmQcList');
        if (!host) return;
        host.textContent = '';
        var empty = $('kmQcEmpty');
        if (empty) empty.style.display = qcButtons.length ? 'none' : '';
        for (var i = 0; i < qcButtons.length; i++) {
            var b = qcButtons[i];
            if (!b) continue;
            var row = document.createElement('div');
            row.className = 'km-binding';
            var label = document.createElement('span');
            // textContent, never innerHTML — the label is user-authored free text.
            label.textContent = (b.label || b.id) + ' — ' + describeAction(b.action);
            row.appendChild(label);
            var del = document.createElement('button');
            del.className = 'btn btn-danger btn-sm';
            del.type = 'button';
            del.textContent = tr('keymap.qc_delete');
            // Address by stable id, never by render-time index: the list re-renders only
            // when the save POST echoes back, so a second click before that lands would
            // read a stale index and delete a DIFFERENT button.
            del.setAttribute('data-qc-id', b.id);
            del.addEventListener('click', function () {
                removeQuickControl(this.getAttribute('data-qc-id'));
            });
            row.appendChild(del);
            host.appendChild(row);
        }
    }

    // Save the composed action as a dashboard button. Uses the in-progress sequence when
    // one exists (same precedence addBinding uses), else the current single selection.
    function saveQuickControl() {
        var labelEl = $('kmQcLabel');
        var label = labelEl ? labelEl.value.trim() : '';
        if (!label) { toast(tr('keymap.qc_need_label'), 'error'); return; }
        var action;
        if (seqSteps.length) {
            // Fold the pending form selection ONLY if the user actually edited it —
            // the same formDirty gate addBinding uses, and for the same reason:
            // buildActionFromForm() never returns null, so after addStep() blanks the
            // form it still yields CURATED[0] ("lock"). Without this gate a button built
            // from "Add step" presses silently gained a trailing lock-the-car step.
            var steps = seqSteps.slice();
            if (formDirty) {
                var pending = buildActionFromForm();
                if (pending) steps.push(pending);
            }
            action = steps.length === 1 ? steps[0] : { kind: 'sequence', steps: steps };
        } else {
            // buildActionFromForm already toasts a specific reason when it refuses (e.g.
            // "choose an automation"), so don't stack a generic message on top of it.
            action = buildActionFromForm();
            if (!action) return;
        }
        if (!action) { toast(tr('keymap.qc_need_action'), 'error'); return; }
        if (action.kind === 'manualClip' || (action.kind === 'sequence' && hasManualClip(action.steps))) {
            // Same constraint the daemon enforces for sequences: a replay completes
            // asynchronously and must be a direct binding.
            toast(tr('keymap.clip_sequence_unsupported'), 'warning');
            return;
        }
        // id is generated, never user-supplied — it is what the fire endpoint resolves.
        var id = 'qc' + Date.now() + Math.floor(Math.random() * 1000);
        qcButtons.push({ id: id, label: label, action: action });
        persistQuickControls(function () {
            if (labelEl) labelEl.value = '';
            // BOTH, in this order. resetForm() ends any half-finished KEY binding session —
            // without it editIndex and the captured keycode stay armed, so the next "Save"
            // press would overwrite the binding being edited with this blanked form. It does
            // not touch the action selects, which clearActionForm() then resets.
            resetForm();
            clearActionForm();
        });
    }

    function hasManualClip(steps) {
        if (!steps || !steps.length) return false;
        for (var i = 0; i < steps.length; i++) {
            if (steps[i] && steps[i].kind === 'manualClip') return true;
        }
        return false;
    }

    function removeQuickControl(id) {
        if (!id) return;
        var index = -1;
        for (var i = 0; i < qcButtons.length; i++) {
            if (qcButtons[i] && qcButtons[i].id === id) { index = i; break; }
        }
        if (index < 0) return;
        function doDelete() {
            // Re-resolve by id: the confirm modal is async, so the array may have changed
            // (another delete landed, or the daemon echo re-rendered) since it opened.
            for (var j = 0; j < qcButtons.length; j++) {
                if (qcButtons[j] && qcButtons[j].id === id) {
                    qcButtons.splice(j, 1);
                    // Re-render at once so the row can't be clicked again during the POST.
                    renderQuickControls();
                    persistQuickControls();
                    return;
                }
            }
        }
        // Confirm first — deleting is destructive and one stray tap would otherwise remove a
        // button with no undo. Same themed modal (with native fallback) removeBinding uses.
        if (window.BYD && BYD.utils && BYD.utils.confirmDialog) {
            BYD.utils.confirmDialog({
                title: tr('keymap.qc_delete'),
                body: tr('keymap.qc_confirm_delete'),
                confirmLabel: tr('keymap.qc_delete'),
                cancelLabel: tr('common.cancel'),
                danger: true
            }).then(function (ok) { if (ok) doDelete(); });
        } else if (window.confirm(tr('keymap.qc_confirm_delete'))) {
            doDelete();
        }
    }

    // Wholesale replace, mirroring the keymap section: a removed button must actually
    // disappear rather than linger from a key-merge. Re-render from the daemon's echo so
    // the list always shows what was really persisted (labels are clamped server-side).
    function persistQuickControls(onOk) {
        fetch('/api/quick-controls', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ buttons: qcButtons })
        }).then(function (r) { return r.json(); }).then(function (j) {
            if (!j || !j.success) {
                toast((j && j.error) || tr('keymap.qc_save_failed'), 'error');
                loadQuickControls();
                return;
            }
            qcButtons = j.buttons || qcButtons;
            renderQuickControls();
            toast(tr('keymap.qc_saved'), 'success');
            if (onOk) onOk();
        }).catch(function () {
            toast(tr('keymap.qc_save_failed'), 'error');
            loadQuickControls();
        });
    }

    // Reset only the action inputs (not keycode/pressType, which describe the key
    // being bound — not the step) so the next step starts blank. Because this is
    // a programmatic reset, clear formDirty AFTER the handlers (which set it) run.
    function clearActionForm() {
        $('kmActionKind').value = 'curated';
        onKindChange();
        $('kmCuratedAction').selectedIndex = 0;
        onCuratedChange();
        $('kmShellCmd').value = '';
        resetClipWindow();
        formDirty = false;
    }

    function removeStep(i) {
        seqSteps.splice(i, 1);
        renderSteps();
    }

    // Swap step i with its neighbour (dir -1 = up, +1 = down) so a one-key routine runs
    // in the order the user wants. No-op at the ends.
    function moveStep(i, dir) {
        var j = i + dir;
        if (j < 0 || j >= seqSteps.length) return;
        var tmp = seqSteps[j]; seqSteps[j] = seqSteps[i]; seqSteps[i] = tmp;
        renderSteps();
    }

    function renderSteps() {
        var wrap = $('kmSeqSteps');
        if (!wrap) return;
        wrap.innerHTML = '';
        wrap.style.display = seqSteps.length ? '' : 'none';
        for (var i = 0; i < seqSteps.length; i++) {
            (function (idx) {
                var chip = document.createElement('div');
                chip.className = 'km-step';
                var span = document.createElement('span');
                span.textContent = (idx + 1) + '. ' + describeAction(seqSteps[idx]);
                chip.appendChild(span);
                // Reorder arrows (only meaningful with 2+ steps). Disabled at the ends
                // so the chip layout stays stable.
                if (seqSteps.length > 1) {
                    var up = document.createElement('button');
                    up.className = 'km-step-move';
                    up.setAttribute('aria-label', tr('keymap.move_up'));
                    up.innerHTML = '&#8593;'; // ↑
                    if (idx === 0) up.disabled = true;
                    else up.onclick = function () { moveStep(idx, -1); };
                    chip.appendChild(up);

                    var down = document.createElement('button');
                    down.className = 'km-step-move';
                    down.setAttribute('aria-label', tr('keymap.move_down'));
                    down.innerHTML = '&#8595;'; // ↓
                    if (idx === seqSteps.length - 1) down.disabled = true;
                    else down.onclick = function () { moveStep(idx, 1); };
                    chip.appendChild(down);
                }
                var x = document.createElement('button');
                x.className = 'km-step-x';
                x.setAttribute('aria-label', tr('keymap.delete'));
                x.innerHTML = '&times;';
                x.onclick = function () { removeStep(idx); };
                chip.appendChild(x);
                wrap.appendChild(chip);
            })(i);
        }
    }

    function addBinding() {
        var keycode = parseInt($('kmManualKeycode').value, 10);
        if (!keycode && captured) keycode = captured;
        if (!keycode || keycode <= 0) { toast(tr('keymap.need_keycode'), 'error'); return; }

        var pressType = $('kmPressType').value;
        var action = null;
        var label = '';

        if (seqSteps.length > 0) {
            // One key → N actions. Only fold an actually-edited, un-added form
            // selection in as the final step — addStep() blanks the form and
            // clears formDirty, so a user who clicked "Add step" for every action
            // doesn't get its last one silently duplicated (lock → flash → flash).
            // A user who edits the form but forgets "Add step" still gets it.
            var steps = seqSteps.slice();
            if (formDirty) {
                var pending = buildActionFromForm();
                if (pending && pending.kind === 'manualClip') {
                    toast(tr('keymap.clip_sequence_unsupported'), 'warning');
                    return;
                }
                if (pending) steps.push(pending);
            }
            if (steps.length === 1) {
                action = steps[0];
                label = describeAction(action);
            } else {
                action = { kind: 'sequence', steps: steps };
                label = tr('keymap.sequence_of', { n: steps.length }) || (steps.length + ' steps');
            }
        } else {
            action = buildActionFromForm();
            if (!action) return; // toast already shown
            label = describeAction(action);
        }

        // Snapshot the list first so a persist failure can roll back to exactly what
        // the server has — otherwise a phantom binding lingers in local state and a
        // later saveConfig()->persist() (from toggling Enable/Advanced) would
        // silently commit it. Mirrors removeBinding's load()-on-failure resync.
        var prev = state.bindings.slice();
        // Preserve the original enabled flag when editing so a Save doesn't silently
        // re-enable a binding the user had toggled off.
        var wasEnabled = (editIndex >= 0 && state.bindings[editIndex])
            ? (state.bindings[editIndex].enabled !== false) : true;
        var newBinding = { keycode: keycode, pressType: pressType, enabled: wasEnabled, label: label, action: action };
        // "Block native single-click" is only meaningful for a double-press binding
        // (the dispatcher reads it only in the double branch). Persist it only then, and
        // only when true, so single/long bindings stay byte-identical to before.
        if (pressType === 'double') {
            var bn = $('kmBlockNativeSingle');
            if (bn && bn.checked) newBinding.blockNativeSingle = true;
        }
        var replaced = false;

        if (editIndex >= 0 && editIndex < state.bindings.length) {
            // Editing an existing binding: replace that exact slot (so a keycode /
            // press-type change updates in place rather than adding a duplicate).
            // If the edit collided the keycode+pressType onto ANOTHER existing
            // binding, drop that other one first so we don't leave a dup the
            // dispatcher would double-fire.
            for (var j = state.bindings.length - 1; j >= 0; j--) {
                if (j !== editIndex && state.bindings[j].keycode === keycode
                        && state.bindings[j].pressType === pressType) {
                    state.bindings.splice(j, 1);
                    if (j < editIndex) editIndex--; // keep the target index valid
                }
            }
            state.bindings[editIndex] = newBinding;
            replaced = true;
        } else {
            // Adding: reject an exact keycode+pressType duplicate — the dispatcher's
            // "newest wins" would otherwise fire both. Replace instead.
            for (var i = 0; i < state.bindings.length; i++) {
                if (state.bindings[i].keycode === keycode && state.bindings[i].pressType === pressType) {
                    state.bindings[i] = newBinding;
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                state.bindings.push(newBinding);
            }
        }

        persist(function (ok) {
            if (!ok) { state.bindings = prev; toast(tr('keymap.save_failed'), 'error'); renderList(); return; }
            toast(replaced ? tr('keymap.updated') : tr('keymap.added'), 'success');
            resetForm();
            paint();
            if (window.OT_setActiveTab) window.OT_setActiveTab('bindings');
        });
    }

    // ───────────────────────── Edit ─────────────────────────

    // Load an existing binding into the Add form and switch to the Add tab. The
    // form then behaves exactly like adding, except addBinding() replaces the
    // original slot (editIndex) on save — so changing the keycode or press type
    // updates in place instead of leaving a duplicate behind.
    function editBinding(index) {
        var b = state.bindings[index];
        if (!b) return;
        // Start from a clean form, then populate. resetForm() clears editIndex, so
        // set it AFTER.
        resetForm();
        editIndex = index;

        // Keycode: prefer the Known-button dropdown when the code is known, else the
        // custom/manual path. Setting the dropdown drives onKnownButtonChange, which
        // fills the keycode + press-type options for us.
        var known = knownByCode(b.keycode);
        var knownSel = $('kmKnownButton');
        if (known && knownSel) {
            knownSel.value = String(b.keycode);
            onKnownButtonChange();
        } else if (knownSel) {
            knownSel.value = 'custom';
            onKnownButtonChange();       // reveals the custom wrap
            $('kmManualKeycode').value = b.keycode;
            refreshPressTypeForManual(); // narrow press types for the entered code
        }
        // Press type — set after the options are built above so the value sticks.
        var pt = $('kmPressType');
        if (pt && !pt.disabled) pt.value = b.pressType || 'single';
        // Reflect the saved block-native-single flag + show/hide its row for the
        // restored press type.
        syncBlockNativeRow();
        var bnEdit = $('kmBlockNativeSingle');
        if (bnEdit) bnEdit.checked = (b.pressType === 'double' && b.blockNativeSingle === true);

        // Action → form. A sequence loads its steps as chips (with the form left
        // blank so addBinding folds nothing extra); a single action loads into the
        // action inputs directly.
        if (b.action && b.action.kind === 'sequence') {
            seqSteps = (b.action.steps || []).slice();
            renderSteps();
        } else if (b.action) {
            loadActionIntoForm(b.action);
        }
        // The programmatic population above tripped the change handlers; the form
        // faithfully mirrors the saved binding, so treat it as pristine — an
        // unmodified Save re-persists the same action (not a duplicated step).
        formDirty = false;

        setFormMode(true);
        toast(tr('keymap.editing'), 'info');
        // Suppress the one tab-change we're about to cause, so the active-changed
        // listener doesn't immediately reset the edit session we just set up.
        suppressNextTabReset = true;
        if (window.OT_setActiveTab) window.OT_setActiveTab('add');
    }

    // True for exactly one 'ot-tabs:active-changed' — the switch editBinding() itself
    // triggers. Any OTHER switch to the Add tab (manual tab click, empty-state CTA)
    // means the user wants a FRESH binding, so we reset edit mode then. This closes
    // the "edit A → tab to Add → save overwrites A" data-loss trap.
    var suppressNextTabReset = false;

    function onTabChanged(id) {
        if (id !== 'add') return;
        if (suppressNextTabReset) { suppressNextTabReset = false; return; }
        // Entered the Add tab NOT via the edit pencil → start clean.
        if (editIndex >= 0) resetForm();
    }

    // Reflect add-vs-edit in the form's title + primary-button label so the user
    // knows a Save will overwrite the existing binding rather than add a new one.
    function setFormMode(editing) {
        var title = $('kmAddTitle');
        var btn = $('kmAddBtnLabel');
        var titleKey = editing ? 'keymap.edit_title' : 'keymap.add_title';
        var buttonKey = editing ? 'keymap.save_changes' : 'keymap.add_binding';
        if (title) {
            title.setAttribute('data-i18n', titleKey);
            title.textContent = tr(titleKey);
        }
        if (btn) {
            btn.setAttribute('data-i18n', buttonKey);
            btn.textContent = tr(buttonKey);
        }
    }

    // Populate the action inputs (kind + curated action + payload / shell / app)
    // from a single fire-action object — the inverse of buildActionFromForm(). Falls
    // back gracefully when a stored action doesn't match a curated entry (e.g. a
    // hand-crafted api action): the kind is still set so the user sees the closest
    // editable representation.
    function loadActionIntoForm(a) {
        var kindSel = $('kmActionKind');
        if (a.kind === 'shell') {
            if (kindSel) kindSel.value = 'shell';
            onKindChange();
            $('kmShellCmd').value = a.cmd || '';
            return;
        }
        if (a.kind === 'openApp') {
            if (kindSel) kindSel.value = 'openApp';
            onKindChange(); // triggers buildAppOptions()
            var appSel = $('kmOpenApp');
            if (appSel) {
                // The list may still be loading; set now and again once it arrives.
                var want = a['package'] || '';
                appSel.value = want;
                if (!appSel.value && want) {
                    // App list not populated yet — inject the value so it round-trips.
                    var o = document.createElement('option');
                    o.value = want; o.textContent = a.label || want;
                    appSel.appendChild(o);
                    appSel.value = want;
                }
            }
            var splitEl = $('kmOpenAppSplit');
            if (splitEl) splitEl.checked = !!a.split;
            return;
        }
        // Manual instant replay: a curated action with its own two-value clip
        // window instead of a payload dropdown. Select it, reveal the clip UI via
        // onCuratedChange, then restore the saved before/after window.
        if (a.kind === 'manualClip') {
            if (kindSel) kindSel.value = 'curated';
            onKindChange();
            var clipSel = $('kmCuratedAction');
            if (clipSel) clipSel.value = 'manual_clip';
            onCuratedChange();
            applyClipWindowToForm(a.beforeSeconds, a.afterSeconds);
            return;
        }
        // Run-automation: a curated action whose live payload is the target automation
        // id. Select it, reveal the (dynamic) payload dropdown via onCuratedChange, and
        // pass the saved id as the preselect so it's restored once the list resolves.
        if (a.kind === 'automation') {
            if (kindSel) kindSel.value = 'curated';
            onKindChange();
            var autoSel = $('kmCuratedAction');
            if (autoSel) autoSel.value = 'run_automation';
            onCuratedChange(a.id || undefined);
            return;
        }
        // Run-action-group: identical restore path, different curated id + live list.
        if (a.kind === 'actionGroup') {
            if (kindSel) kindSel.value = 'curated';
            onKindChange();
            var grpSel = $('kmCuratedAction');
            if (grpSel) grpSel.value = 'run_action_group';
            onCuratedChange(a.id || undefined);
            return;
        }
        // Radio toggle: match the curated entry by its fixed `radio` id, restore the
        // on/off state as the payload.
        if (a.kind === 'radio') {
            if (kindSel) kindSel.value = 'curated';
            onKindChange();
            var radioId = matchCuratedByRadio(a.radio);
            var radioSel = $('kmCuratedAction');
            if (radioSel && radioId) {
                radioSel.value = radioId;
                onCuratedChange(a.state || 'on');
            }
            return;
        }
        // catalog / vehicle / api all live under the "curated" kind, matched back to
        // a CURATED entry by key (catalog/vehicle) or id (api).
        if (kindSel) kindSel.value = 'curated';
        onKindChange();
        var curatedId = null;
        if (a.kind === 'vehicle') curatedId = matchCuratedByVehicle(a.action);
        else if (a.kind === 'catalog') curatedId = matchCuratedByCatalog(a.key, a.sub);
        else if (a.kind === 'api') curatedId = a.id || matchCuratedByApiPath(a.path, a.body);
        var curSel = $('kmCuratedAction');
        if (curSel && curatedId) {
            curSel.value = curatedId;
            // Reconstruct the saved payload and pass it as the preselect so
            // onCuratedChange restores it — this also works for DYNAMIC (async) payload
            // lists like the audio library, where a synchronous post-set would race the
            // fetch. For static lists it selects immediately.
            var payloadVal = reconstructPayload(a);
            onCuratedChange(payloadVal != null ? payloadVal : undefined);
        }
    }

    // Match a curated entry whose kind is 'vehicle' and key equals the action verb.
    function matchCuratedByVehicle(actionVerb) {
        for (var i = 0; i < CURATED.length; i++) {
            if (CURATED[i].kind === 'vehicle' && CURATED[i].key === actionVerb) return CURATED[i].id;
        }
        return null;
    }
    // Match a curated 'radio' entry by its fixed radio id (wifi/bluetooth/data).
    function matchCuratedByRadio(radio) {
        for (var i = 0; i < CURATED.length; i++) {
            if (CURATED[i].kind === 'radio' && CURATED[i].radio === radio) return CURATED[i].id;
        }
        return null;
    }
    // Match a curated 'catalog' entry by key (+ sub when the entry carries one).
    function matchCuratedByCatalog(key, sub) {
        for (var i = 0; i < CURATED.length; i++) {
            var c = CURATED[i];
            if (c.kind === 'catalog' && c.key === key && (c.sub || null) === (sub || null)) return c.id;
        }
        return null;
    }
    // Match a curated 'api' entry by comparing the path prefix (before any ${v}) and
    // the body's stable prefix. Used only when a stored api action lost its id.
    function matchCuratedByApiPath(path, body) {
        for (var i = 0; i < CURATED.length; i++) {
            var c = CURATED[i];
            if (c.kind !== 'api') continue;
            var cPathPrefix = (c.path || '').split('${v}')[0];
            if (path && cPathPrefix && path.indexOf(cPathPrefix) === 0) return c.id;
        }
        return null;
    }

    // Reconstruct the payload option value that produced this action, so the payload
    // dropdown can re-select it. For catalog it's the stored payload; for api we
    // recover ${v} by diffing the stored path/body against the curated template.
    function reconstructPayload(a) {
        if (a.kind === 'catalog') return a.payload != null ? a.payload : null;
        if (a.kind === 'api') {
            var c = curatedById(a.id) || null;
            if (!c) return null;
            // Recover ${v} from whichever template carried it.
            var fromPath = recoverV(c.path, a.path);
            if (fromPath != null) return fromPath;
            return recoverV(c.body, a.body);
        }
        return null;
    }

    // Given a curated template containing ${v} and the concrete stored string, return
    // the substring that ${v} expanded to, or null if the template has no ${v} or
    // doesn't match.
    function recoverV(template, concrete) {
        if (!template || concrete == null) return null;
        var idx = template.indexOf('${v}');
        if (idx < 0) return null;
        var before = template.substring(0, idx);
        var after = template.substring(idx + 4);
        // Concrete must start with the pre-${v} text; strip it.
        if (concrete.indexOf(before) !== 0) return null;
        var rest = concrete.substring(before.length);
        // …and end with the post-${v} text; strip that too (proper endsWith, ES5-safe).
        if (after) {
            if (rest.length < after.length) return null;
            if (rest.substring(rest.length - after.length) !== after) return null;
            rest = rest.substring(0, rest.length - after.length);
        }
        return rest;
    }

    function removeBinding(index) {
        // Do the actual delete once confirmed.
        function doDelete() {
            state.bindings.splice(index, 1);
            persist(function (ok) {
                if (ok) { renderList(); toast(tr('keymap.deleted'), 'success'); }
                else { toast(tr('keymap.save_failed'), 'error'); load(); }
            });
        }
        // Use the app's themed modal (matches every other page). Native
        // window.confirm looks foreign in the WebView; keep it only as a
        // fallback for very-early-init / older shells without BYD.utils.
        if (window.BYD && BYD.utils && BYD.utils.confirmDialog) {
            BYD.utils.confirmDialog({
                title: tr('keymap.delete'),
                body: tr('keymap.confirm_delete'),
                confirmLabel: tr('keymap.delete'),
                cancelLabel: tr('common.cancel'),
                danger: true
            }).then(function (ok) { if (ok) doDelete(); });
        } else if (window.confirm(tr('keymap.confirm_delete'))) {
            doDelete();
        }
    }

    function resetForm() {
        captured = null;
        editIndex = -1;
        $('kmManualKeycode').value = '';
        $('kmCapKeycode').textContent = '—';
        $('kmCapHint').setAttribute('data-i18n', 'keymap.capture_idle');
        $('kmCapHint').textContent = tr('keymap.capture_idle');
        $('kmShellCmd').value = '';
        var bn = $('kmBlockNativeSingle'); if (bn) bn.checked = false;
        syncBlockNativeRow();
        resetClipWindow();
        seqSteps = [];
        formDirty = false;
        renderSteps();
        setFormMode(false);
    }

    // Empty-state CTA — jump to the Add tab for a FRESH binding. Reset first so a
    // leftover edit session (editIndex) can't turn this "add" into an overwrite.
    function goAdd() {
        resetForm();
        if (window.OT_setActiveTab) window.OT_setActiveTab('add');
    }

    function openA11ySettings() {
        // Standard OS accessibility settings. In the head-unit WebView this may
        // be intercepted by the native shell to deep-link BYD's a11y screen.
        try { window.location.href = 'intent://settings/accessibility'; } catch (e) {}
        toast(tr('keymap.a11y_hint'), 'info');
    }

    // ───────────────────────── Init ─────────────────────────

    function refreshLocale() {
        // Keep every in-progress form value while rebuilding only the controls whose
        // labels were created imperatively and therefore cannot be fixed by hydrate().
        var wasDirty = formDirty;
        var payload = $('kmPayload') ? $('kmPayload').value : '';
        var known = $('kmKnownButton') ? $('kmKnownButton').value : '';
        var press = $('kmPressType') ? $('kmPressType').value : '';

        buildCuratedOptions(payload || undefined);
        buildKnownButtonOptions();
        if ($('kmKnownButton')) $('kmKnownButton').value = known;

        var pressSel = $('kmPressType');
        if (pressSel) {
            for (var i = 0; i < pressSel.options.length; i++) {
                pressSel.options[i].textContent = pressLabel(pressSel.options[i].value);
            }
            if (press) pressSel.value = press;
        }

        if ($('kmActionKind') && $('kmActionKind').value === 'openApp') buildAppOptions();
        paintStatusBadge();
        updateClipWindowLabels();
        renderList();
        renderQuickControls();
        renderSteps();
        setFormMode(editIndex >= 0);
        formDirty = wasDirty;
    }

    function init() {
        buildCuratedOptions();
        buildKnownButtonOptions();
        onKnownButtonChange(); // set initial custom-wrap visibility (placeholder → hidden)
        onKindChange();
        if (!localeChangeBound && window.BYD && BYD.i18n
                && typeof BYD.i18n.onChange === 'function') {
            localeChangeBound = true;
            BYD.i18n.onChange(refreshLocale);
        }
        document.addEventListener('keydown', onKeydown, true);
        // Reset a stale edit session when the user switches to the Add tab by any
        // means other than the edit pencil (see onTabChanged) — closes the edit trap.
        document.addEventListener('ot-tabs:active-changed', function (e) {
            try { onTabChanged(e && e.detail ? e.detail.id : null); } catch (_) {}
            // Re-arm the readiness poll on any tab switch. The budget is finite (~44s), so on a
            // boot slower than that the nudge would otherwise sit there until a manual reload
            // even though the service came up. Cheap: the guard below no-ops once ready, and
            // scheduleA11yRecheck already collapses repeats into one timer.
            try {
                if (state.enabled && !a11yReady()) {
                    a11yRechecksLeft = A11Y_RECHECK_BUDGET;
                    scheduleA11yRecheck();
                }
            } catch (_) {}
        }, false);
        // Same re-arm when the page returns to the foreground: the head unit does not reliably
        // deliver Page Visibility, so listen for both and let the guards dedupe.
        document.addEventListener('visibilitychange', function () {
            try {
                if (!document.hidden && state.enabled && !a11yReady()) {
                    a11yRechecksLeft = A11Y_RECHECK_BUDGET;
                    scheduleA11yRecheck();
                }
            } catch (_) {}
        }, false);
        // Mark the Add-form touched when the user edits the free-text / payload
        // inputs directly (the two <select>s already flag via their onchange
        // handlers). Lets addBinding() distinguish a real un-added selection from
        // the blanked leftover of "Add step". Wired once; harmless when disabled.
        wireDirty('kmPayload');
        wireDirty('kmShellCmd');
        wireDirty('kmManualKeycode');
        // Typing a keycode directly (custom path) should immediately narrow the
        // press-type selector: entering a known long-variant (302/303/306/312)
        // locks it to 'single', preventing a never-firing "long" binding, while
        // an unknown code keeps all three types. Complements the capture-path and
        // custom-branch calls to refreshPressTypeForManual().
        var manualEl = $('kmManualKeycode');
        if (manualEl) {
            manualEl.addEventListener('input', refreshPressTypeForManual, false);
            manualEl.addEventListener('change', refreshPressTypeForManual, false);
        }
        // buildCuratedOptions()/onKindChange() above set formDirty as a side
        // effect of programmatic setup; the form is pristine at rest, so clear it.
        formDirty = false;
        load();
    }

    // Flag the form dirty on any user edit of the given input.
    function wireDirty(id) {
        var el = $(id);
        if (!el) return;
        var mark = function () { formDirty = true; };
        el.addEventListener('input', mark, false);
        el.addEventListener('change', mark, false);
    }

    return {
        init: init,
        load: load,
        saveConfig: saveConfig,
        onDoubleTapWindowInput: onDoubleTapWindowInput,
        onDoubleTapWindowChange: onDoubleTapWindowChange,
        toggleCapture: toggleCapture,
        onNativeKey: onNativeKey,
        onKnownButtonChange: onKnownButtonChange,
        onKindChange: onKindChange,
        onPressTypeChange: onPressTypeChange,
        onCuratedChange: onCuratedChange,
        onClipPresetChange: onClipPresetChange,
        onClipWindowChange: onClipWindowChange,
        addStep: addStep,
        addBinding: addBinding,
        loadQuickControls: loadQuickControls,
        saveQuickControl: saveQuickControl,
        toggleBinding: toggleBinding,
        goAdd: goAdd,
        openA11ySettings: openA11ySettings
    };
}());
