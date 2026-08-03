/**
 * Overdrive — Web app shell.
 *
 * Each in-app web page used to copy-paste the same ~250-line <aside class="sidebar">
 * block. This script mounts it once at runtime so every page shares one source
 * of truth. The DOM produced here is identical (element IDs included) to what
 * the legacy markup produced — existing JS bindings (core.js, vehicle-control.js,
 * etc.) keep working without changes.
 *
 * Usage in HTML:
 *   <body>
 *     <div class="app-layout">
 *       <div id="app-shell-mount"></div>
 *       <main class="main-content">…page-specific content…</main>
 *     </div>
 *     <script src="../shared/app-shell.js"></script>
 *
 * If a page omits #app-shell-mount the shell appends itself to <body>.
 *
 * ES5-only (Chrome 58 / Android 7.1 head-unit floor). No const/let, no arrow
 * functions, no template strings, no async/await.
 *
 * Active link is detected from window.location.pathname's basename.
 */
(function () {
    'use strict';

    // The Android app supplies its own navigation rail and injects
    // AndroidBridge before the page starts loading. Its post-load CSS hides
    // this web sidebar, so rendering the sidebar's 3D vehicle there wastes a
    // full GLB parse and can race the visible aux canvas. Standalone/tunnel
    // pages have no bridge and keep the normal sidebar renderer.
    var embeddedInNativeApp =
        typeof window.AndroidBridge !== 'undefined';

    /*
     * Sidebar nav layout MIRRORS rail_menu.xml exactly:
     *   Dashboard → Live → Recordings → Vehicle → Trips → Integrations cluster →
     *   Diagnostics → Settings cluster → About.
     *
     * Recordings on the native rail opens the events list (the WebView's
     * recordings fragment shows events.html, not recording.html settings).
     * Recording / Surveillance / Notifications are SETTINGS pages — they
     * group under the "Settings" header to match how MainActivity.kt routes
     * them through SettingsFragment sub-destinations.
     *
     * Items with `divider: true` insert a hairline ABOVE that item.
     */
    var NAV_ITEMS = [
        // ===== Overview ===== — the "what's happening now" cluster.
        { divider: true, label: 'Overview', i18n: 'nav.overview_group' },
        { href: 'index.html',           i18n: 'nav.dashboard',       label: 'Dashboard',       svg: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>' },
        { href: 'live-view.html',       i18n: 'nav.live_view',       label: 'Live View',       svg: '<path d="M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/><path d="M2 12a9 9 0 0 0 8 8"/><circle cx="2" cy="12" r="2"/>' },
        { href: 'events.html',          i18n: 'nav.recordings',      label: 'Recordings',      svg: '<path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/>' },

        // ===== Vehicle ===== — control + trip history.
        { divider: true, label: 'Vehicle', i18n: 'nav.vehicle_group' },
        { href: 'vehicle-control.html', i18n: 'nav.vehicle_control', label: 'Vehicle Control', svg: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>' },
        { href: 'trips.html',           i18n: 'nav.trips',           label: 'Trips',           svg: '<path d="M3 17h2v-7l4 4 4-4 4 4 4-4v7h2"/><path d="M4 5h16"/>' },
        { href: 'charging.html',        i18n: 'nav.charging',        label: 'Charging',        svg: '<path d="M13 2 L4 14 h7 l-1 8 9-12 h-7 z"/>', svgExtra: 'stroke-linecap="round" stroke-linejoin="round"' },
        { href: 'automations.html',     i18n: 'nav.automations',     label: 'Automations',     svg: '<rect width="8" height="8" x="3" y="3" rx="2"/><path d="M7 11v4a2 2 0 0 0 2 2h4"/><rect width="8" height="8" x="13" y="13" rx="2"/>' },
        { href: 'key-mapping.html',     i18n: 'nav.key_mapping',     label: 'Key Mapping',     svg: '<rect width="20" height="14" x="2" y="5" rx="2"/><path d="M6 9h.01M10 9h.01M14 9h.01M18 9h.01M7 13h.01M17 13h.01M11 13h2"/>', svgExtra: 'stroke-linecap="round" stroke-linejoin="round"' },

        // ===== Integrations group ===== — mirrors the native Integrations
        // sub-page (Telegram, ABRP, MQTT, BYD Cloud).
        { divider: true, label: 'Integrations', i18n: 'nav.integrations_group' },
        { href: 'telegram.html',                          i18n: 'nav.telegram',       label: 'Telegram',       svg: '<line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>', svgExtra: 'stroke-linecap="round" stroke-linejoin="round"' },
        { href: 'abrp.html',                              i18n: 'nav.abrp',           label: 'ABRP',           svg: '<path d="M9 18l6-6-6-6"/><circle cx="18" cy="12" r="3"/><circle cx="6" cy="6" r="3"/><circle cx="6" cy="18" r="3"/>' },
        { href: 'mqtt.html',                              i18n: 'nav.mqtt',           label: 'MQTT',           svg: '<circle cx="12" cy="12" r="2"/><path d="M8.46 15.54A5 5 0 0 1 7 12a5 5 0 0 1 1.46-3.54"/><path d="M15.54 8.46A5 5 0 0 1 17 12a5 5 0 0 1-1.46 3.54"/><path d="M5.64 18.36A9 9 0 0 1 3 12a9 9 0 0 1 2.64-6.36"/><path d="M18.36 5.64A9 9 0 0 1 21 12a9 9 0 0 1-2.64 6.36"/>', svgExtra: 'stroke-linecap="round"' },
        { href: 'byd-cloud.html',                         i18n: 'nav.byd_cloud',      label: 'BYD Cloud',      svg: '<path d="M17.5 19a4.5 4.5 0 1 0-2.83-7.97A6 6 0 0 0 4 12.45a3 3 0 0 0 .5 5.95"/><path d="M17.5 19h-12"/>', svgExtra: 'stroke-linecap="round" stroke-linejoin="round"' },

        // ===== Diagnostics ===== — Performance is the only diagnostics-ish
        // page that exists on web; the native diagnostics fragment is native.
        { divider: true, label: 'Diagnostics', i18n: 'nav.diagnostics_group' },
        { href: 'performance.html',                       i18n: 'nav.performance',    label: 'Performance',    svg: '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>' },
        { href: 'remote-dev-view.html',                   label: 'Remote Dev View',  svg: '<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/><path d="m9 8 2 2-2 2M13 12h2"/>' },

        // ===== Settings ===== — surveillance + recording + notifications
        // are settings sub-destinations under SettingsFragment on native.
        { divider: true, label: 'Settings', i18n: 'nav.settings_group' },
        { href: 'surveillance.html',                      i18n: 'nav.surveillance',   label: 'Surveillance',   svg: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>' },
        { href: 'recording.html',                         i18n: 'nav.recording_settings', label: 'Recording Settings', svg: '<path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/>' },
        { href: 'road-sense.html',                        i18n: 'nav.road_sense',     label: 'RoadSense',      svg: '<path d="M3.2 20.2 L8.6 11 L15.4 11 L20.8 20.2 Z"/><path d="M12 12.4v1.3M12 15.7v1.6M12 18.7v1.1"/><path d="M12 3.2v2.4"/><path d="M12 7.6h.01"/>', svgExtra: 'stroke-linecap="round" stroke-linejoin="round"' },
        { href: 'notifications.html',                     i18n: 'nav.notifications',  label: 'Notifications',  svg: '<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/>' },

        // ===== About ===== — version, license, source, support links,
        // Check for Updates. Sits in its own group so the link isn't
        // dangling under "Settings".
        { divider: true, label: 'About', i18n: 'nav.about_group' },
        { href: 'about.html',                             i18n: 'nav.about',          label: 'About',          svg: '<path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>', svgExtra: 'stroke-linecap="round" stroke-linejoin="round"' }
    ];

    function activeBasename() {
        var path = window.location.pathname || '';
        var idx = path.lastIndexOf('/');
        var base = idx >= 0 ? path.substring(idx + 1) : path;
        if (!base) base = 'index.html';
        return base;
    }

    function buildNav() {
        var current = activeBasename();
        var html = '';
        for (var i = 0; i < NAV_ITEMS.length; i++) {
            var item = NAV_ITEMS[i];
            // Divider / group-header row. Renders as a hairline + optional
            // small caps label, mirroring the native rail's "About" hairline.
            // If the item only has `divider:true` (no label), render a bare
            // hairline.
            if (item.divider) {
                if (item.label) {
                    html += '<div class="nav-group-header">'
                        +    '<span class="nav-group-line"></span>'
                        +    '<span class="nav-group-label"' + (item.i18n ? ' data-i18n="' + item.i18n + '"' : '') + '>' + item.label + '</span>'
                        +  '</div>';
                } else {
                    html += '<div class="nav-divider" role="separator"></div>';
                }
                continue;
            }
            // Normal nav link.
            var isActive = item.href === current;
            var aria = isActive ? ' aria-current="page"' : '';
            var cls = 'nav-link' + (isActive ? ' active' : '');
            var extra = item.svgExtra || '';
            // aria-label doubles as the tooltip text in the collapsed-rail
            // state (see app-shell.css `.nav-link:hover::after`).
            html += '<a href="' + item.href + '" class="' + cls + '"' + aria
                +     ' aria-label="' + item.label + '">'
                +   '<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' + extra + '>'
                +     item.svg
                +   '</svg>'
                +   '<span' + (item.i18n ? ' data-i18n="' + item.i18n + '"' : '') + '>' + item.label + '</span>'
                +  '</a>';
        }
        return html;
    }

    function buildShellMarkup() {
        // Sidebar header — brand becomes a clickable anchor (routes to the
        // dashboard, the canonical "home"). The close-X stays for mobile.
        var header = ''
            + '<div class="sidebar-header">'
            +   '<a href="index.html" class="brand brand-link" aria-label="OverDrive — open Dashboard" data-i18n-attr="aria-label:nav.brand_home">'
            +     '<div class="brand-logo">'
            +       '<img src="../shared/app-icon-glyph-dark.webp" alt="OverDrive">'
            +       '<span class="brand-online-pulse" aria-hidden="true"></span>'
            +     '</div>'
            +     '<div class="brand-text">'
            +       '<span class="brand-name" data-i18n="app.name">OverDrive</span>'
            +       '<span class="brand-tagline" data-i18n="app.tagline">Surveillance System</span>'
            +       '<span class="brand-version" id="appVersion"></span>'
            +     '</div>'
            +   '</a>'
            +   '<button class="sidebar-close" type="button" aria-label="Close menu">'
            +     '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">'
            +       '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>'
            +     '</svg>'
            +   '</button>'
            + '</div>';

        var nav = '<nav class="sidebar-nav">' + buildNav() + '</nav>';

        // Footer = device status card + EV vehicle status card + fuel card (PHEV).
        // Element IDs (deviceId, accValue, batteryValue, survStatus, networkValue,
        // evCard, evPercentValue, evRange, evPower, evSohRow, evSohValue,
        // evPersonalizedRow, evPersonalizedRange, fuelCard, fuelPercentValue,
        // fuelBarFill) are read by core.js, vehicle-control.js, performance.js
        // — must match the legacy DOM. Battery+charging state moved off the
        // SVG bar onto the GLB body's emissive channel; core.js calls
        // OverdriveAppShell.setSoc / setCharging instead of writing rect widths.
        var footer = ''
            + '<div class="sidebar-footer">'
            +   '<div class="status-card">'
            +     '<div class="status-row">'
            +       '<span class="status-label" data-i18n="status.device"><span class="status-dot" id="connDot"></span>Device</span>'
            +       '<span class="status-value" id="deviceId">--</span>'
            +     '</div>'
            +     '<div class="status-row">'
            +       '<span class="status-label" data-i18n="status.acc">ACC</span>'
            +       '<span class="status-value" id="accValue">--</span>'
            +     '</div>'
            +     '<div class="status-row">'
            +       '<span class="status-label" data-i18n="status.battery_12v">'
            +         '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="6" y="7" width="12" height="10" rx="1"/><path d="M6 10H4a1 1 0 0 0-1 1v2a1 1 0 0 0 1 1h2"/><path d="M18 10h2a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-2"/></svg>12V'
            +       '</span>'
            +       '<span class="status-value" id="batteryValue">--</span>'
            +     '</div>'
            +     '<div class="status-row">'
            +       '<span class="status-label" data-i18n="status.sentry">Surveillance</span>'
            +       '<span class="status-value" id="survStatus" data-i18n="status.off">OFF</span>'
            +     '</div>'
            +     '<div class="status-row">'
            +       '<span class="status-label" data-i18n="status.network">'
            +         '<span id="networkIcon"><svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg></span>Net'
            +       '</span>'
            +       '<span class="status-value" id="networkValue">--</span>'
            +     '</div>'
            +   '</div>'
            +   buildEvCard()
            +   buildFuelCard()
            + '</div>';

        return header + nav + footer;
    }

    function buildEvCard() {
        // Vehicle status card.
        //
        //   #evCardCanvas         — three.js scene that renders the user's
        //                            selected GLB tinted in their colour.
        //                            Loaded lazily by ev-card-3d.js after
        //                            page-content paint.
        //   #svgChargeIcon        — bolt badge that pops in on .charging.
        //   .ev-battery-body-*    — translucent battery overlay painted
        //                            onto the lower body of the GLB; width
        //                            tracks SOC, charging triggers the
        //                            sweep loop.
        //
        // Preserved IDs (consumed by core.js):
        //   evCard, evPercentValue, evRange, evPower, evSohRow, evSohValue,
        //   evPersonalizedRow, evPersonalizedRange, evBatteryBar,
        //   evBatteryFill, fuelCard, fuelPercentValue, fuelBarFill.
        var s = '<div class="status-card ev-card" id="evCard">'
            +   '<div class="ev-header-row">'
            +     '<span class="ev-title" data-i18n="status.vehicle_status">Vehicle Status</span>'
            +     '<span class="ev-percent-text" id="evPercentValue">--%</span>'
            +   '</div>'
            +   '<div class="ev-svg-container">'
            +     '<canvas id="evCardCanvas" aria-hidden="true"></canvas>'
            +     '<svg viewBox="0 0 320 130" class="ev-car-svg" id="evCarSvg">'
            +       '<defs>'
            // Radial gradient gives the disc a polished-jewel look:
            // bright teal highlight in the upper-left quadrant fading
            // to a near-black anchor at the rim. The off-centre
            // <stop offset="0"> sits where the highlight lands; the
            // <radialGradient cx="35%" cy="30%"> places that focus.
            // No matrix transform needed — gradient inherits the
            // shape's bbox via the default gradientUnits.
            +         '<radialGradient id="evChargeBoltGrad" cx="35%" cy="30%" r="80%">'
            +           '<stop offset="0%"   stop-color="#1AF0C2"/>'
            +           '<stop offset="42%"  stop-color="#00B894"/>'
            +           '<stop offset="78%"  stop-color="#016B55"/>'
            +           '<stop offset="100%" stop-color="#021E18"/>'
            +         '</radialGradient>'
            // Subtle inner-glow filter so the bolt path reads with a
            // soft halo against the dark gradient instead of looking
            // pasted on. feGaussianBlur on the alpha + composite back
            // over source — supported on Chrome 58 (BYD WebView).
            +         '<filter id="evChargeBoltGlow" x="-30%" y="-30%" width="160%" height="160%">'
            +           '<feGaussianBlur in="SourceAlpha" stdDeviation="0.6"/>'
            +           '<feOffset dx="0" dy="0.5" result="offsetBlur"/>'
            +           '<feMerge>'
            +             '<feMergeNode in="offsetBlur"/>'
            +             '<feMergeNode in="SourceGraphic"/>'
            +           '</feMerge>'
            +         '</filter>'
            +       '</defs>'
            +       '<g id="svgChargeIcon" transform="translate(160, 5) scale(0)" style="transition: transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);">'
            // Outer disc — gradient fill + thin teal-tinted rim
            // instead of the flat white stroke. The rim catches the
            // ambient and gives the disc the "shiny bezel" feel.
            +         '<circle r="12" fill="url(#evChargeBoltGrad)" stroke="rgba(0,212,170,0.45)" stroke-width="1.2"/>'
            // Specular highlight — a small bright crescent in the
            // upper-left quadrant that sells the gloss. Painted as a
            // semi-transparent white ellipse, no animation.
            +         '<ellipse cx="-3.5" cy="-5" rx="5" ry="2.4" fill="rgba(255,255,255,0.35)"/>'
            // The bolt itself — white with the subtle glow filter.
            +         '<path d="M-2,-6 L-4,1 L0,1 L-2,7 L5,-1 L1,-1 Z" fill="#fff" filter="url(#evChargeBoltGlow)"/>'
            +       '</g>'
            +     '</svg>'
            +     '<div class="ev-battery-body-overlay" id="evBatteryBar">'
            +       '<div class="ev-battery-body-fill"   id="evBatteryFill"></div>'
            +       '<div class="ev-battery-body-charge" id="evBatteryCharge"></div>'
            +     '</div>'
            +   '</div>'
            +   '<div class="ev-info-row">'
            +     '<div class="ev-range"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v20M2 12h20"/></svg><span id="evRange">-- km</span></div>'
            +     '<div class="ev-power-status"><span class="ev-power-val" id="evPower">-- kW</span></div>'
            +   '</div>'
            +   '<div class="ev-soh-row" id="evSohRow" style="display:none;">'
            +     '<span class="ev-soh-label">SOH</span>'
            +     '<span class="ev-soh-val" id="evSohValue">--%</span>'
            +   '</div>'
            +   '<div class="ev-personalized-range" id="evPersonalizedRow" style="display:none;">'
            +     '<span class="ev-personalized-label" data-i18n="vehicle.personalized_label">Personalized</span>'
            +     '<span class="ev-personalized-val" id="evPersonalizedRange">-- km</span>'
            +   '</div>'
            +   '<div class="ev-personalized-range ev-personalized-combined" id="evCombinedRow" style="display:none;">'
            +     '<span class="ev-personalized-label" data-i18n="vehicle.combined_label">Combined</span>'
            +     '<span class="ev-personalized-val" id="evCombinedRange">-- km</span>'
            +   '</div>'
            + '</div>';
        return s;
    }

    function buildFuelCard() {
        return '<div class="status-card fuel-card" id="fuelCard" style="display:none;">'
            +   '<div class="fuel-header">'
            +     '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="width:18px;height:18px;color:var(--warning);">'
            +       '<path d="M3 22V6a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16"/>'
            +       '<path d="M3 22h10"/>'
            +       '<path d="M13 10h2a2 2 0 0 1 2 2v3a2 2 0 0 0 2 2h0a2 2 0 0 0 2-2V9.83a2 2 0 0 0-.59-1.42L18 6"/>'
            +       '<path d="M6 14v-3"/>'
            +     '</svg>'
            +     '<span class="fuel-title" data-i18n="vehicle.fuel">Fuel</span>'
            +     '<span class="fuel-percent" id="fuelPercentValue">--%</span>'
            +   '</div>'
            +   '<div class="fuel-bar-track">'
            +     '<div class="fuel-bar-fill" id="fuelBarFill" style="width:0%"></div>'
            +   '</div>'
            +   '<div class="ev-personalized-range fuel-personalized-row" id="fuelPersonalizedRow" style="display:none;">'
            +     '<span class="ev-personalized-label" data-i18n="vehicle.personalized_label">Personalized</span>'
            +     '<span class="ev-personalized-val" id="fuelPersonalizedRange">-- km</span>'
            +   '</div>'
            + '</div>';
    }

    function findMount() {
        var explicit = document.getElementById('app-shell-mount');
        if (explicit) return explicit;
        // Fall back: prepend a new <div> as the first child of .app-layout.
        var layout = document.querySelector('.app-layout');
        if (!layout) return null;
        var holder = document.createElement('div');
        holder.id = 'app-shell-mount';
        layout.insertBefore(holder, layout.firstChild);
        return holder;
    }

    function mount() {
        var holder = findMount();
        if (!holder) return;
        // Render shell into the mount point as <aside class="sidebar"> so all
        // existing CSS selectors (.sidebar, .sidebar-nav, .sidebar-footer …)
        // keep matching without changes.
        var aside = document.createElement('aside');
        aside.className = 'sidebar';
        aside.id = 'sidebar';
        aside.innerHTML = buildShellMarkup();
        // Replace the holder with the aside (lighter than nesting another wrapper).
        if (holder.parentNode) {
            holder.parentNode.replaceChild(aside, holder);
        }
        // Tag the body so shell-scoped CSS in app-shell.css ([data-app-shell="1"])
        // can tighten icon→label spacing across cards, info boxes, settings rows
        // etc. without touching per-page stylesheets.
        if (document.body) document.body.setAttribute('data-app-shell', '1');

        // The brand-logo <img> just got injected — re-run the theme.js icon
        // selector so it picks up the right asset for the active data-theme.
        // Without this, light-theme users see the hardcoded dark fallback.
        try { if (window.BYD && window.BYD.theme && window.BYD.theme.applyIcons) window.BYD.theme.applyIcons(); } catch (e) {}

        // Wire the close button. Pages already define toggleSidebar(); call it
        // if present, otherwise fall back to a generic show/hide.
        var closeBtn = aside.querySelector('.sidebar-close');
        if (closeBtn) {
            closeBtn.addEventListener('click', function () {
                if (typeof window.toggleSidebar === 'function') {
                    window.toggleSidebar();
                } else {
                    aside.classList.toggle('open');
                }
            });
        }

        // Wire per-group collapse on the rendered nav. Each .nav-group-header
        // becomes a tappable toggle that hides every .nav-link between it and
        // the next group-header / divider. State persists per-group.
        wireGroupCollapse(aside);

        // Reflect the user's vehicle pick (model + colour) on the EV card.
        // Each page mounts the same shell, so fetching once per page-load
        // keeps the sidebar in sync with whatever the user selected on
        // vehicle-control.html. The fetch is best-effort — failures fall
        // back to the default ceramic body.
        applyVehicleSelection();

        // Notify the page so it can run its post-shell initialization (i18n
        // re-application, status pollers, etc.). Listen for this event in
        // page-specific scripts that need the sidebar DOM ready.
        try {
            var ev = document.createEvent('Event');
            ev.initEvent('app-shell:ready', true, true);
            document.dispatchEvent(ev);
        } catch (e) { /* ignore — older browsers */ }

        // i18n.js calls applyI18n() at script-load time — but the shell DOM
        // didn't exist then. Re-run it now if the helper is available.
        if (window.BYD && window.BYD.i18n && typeof window.BYD.i18n.applyAll === 'function') {
            try { window.BYD.i18n.applyAll(); } catch (e) {}
        } else if (typeof window.applyI18n === 'function') {
            try { window.applyI18n(); } catch (e) {}
        }
    }

    // ============================================================
    // Vehicle selection → EV card visualization
    // ============================================================

    // Lazy-loaded EV-card 3D scene. Created on first setEvCardAppearance
    // call once the GLB renderer module has loaded. setModel/setColor on
    // the instance is safe even before vendor JS lands — pending values
    // are queued and applied when the renderer is ready.
    //
    // Sprite-cache fast path:
    //   On every page navigation the sidebar mounts the same #evCardCanvas.
    //   If a webp sprite for the user's (model, colour, view, dpr) is
    //   already in IndexedDB, paint it into the canvas immediately and
    //   skip the three.js + GLB load entirely. The 3D pipeline still
    //   runs once on first cold load (or after the user picks a colour
    //   we haven't seen before) — and at the end of that render we snap
    //   the canvas back into the cache for the next page visit.
    //
    //   Cache ownership: the cache key includes a SPRITE_VERSION constant
    //   in ev-card-sprite-cache.js, so a pipeline change (lighting rig,
    //   camera framing) invalidates everything in one go. Per-key
    //   invalidation also runs when the user changes their selection.
    var ev3dInstance = null;
    var ev3dLoadStarted = false;
    var spriteCacheLoadStarted = false;
    // True once the sidebar canvas has been painted from the sprite
    // cache. A canvas can hold a 2D context OR a WebGL context, never
    // both — so if a later setEvCardAppearance call has to fall back to
    // the live 3D pipeline (cache miss after a previous hit), we have
    // to swap in a fresh <canvas> element first.
    var sidebarPaintedFromSprite = false;
    // Per-canvas, per-key flag so we only snapshot the FIRST render
    // after a (model, colour) change — subsequent identical-key renders
    // (resize, visibility flip) shouldn't keep rewriting the same blob.
    var snapshotsTaken = {};

    // Replace #evCardCanvas with a fresh clone. Used when transitioning
    // from a 2D-painted sprite to the live WebGL pipeline within the
    // same page session — the existing 2D context permanently locks the
    // canvas out of WebGL, so we have to swap the element. References
    // held elsewhere (battery overlay, charge SVG) sit on adjacent DOM
    // and are unaffected.
    function swapSidebarCanvasForFresh() {
        var old = document.getElementById('evCardCanvas');
        if (!old || !old.parentNode) return null;
        var fresh = old.cloneNode(false);
        old.parentNode.replaceChild(fresh, old);
        sidebarPaintedFromSprite = false;
        return fresh;
    }

    function ensureSpriteCacheModule(cb) {
        if (typeof window.OverdriveEvSpriteCache === 'object') { cb(window.OverdriveEvSpriteCache); return; }
        if (spriteCacheLoadStarted) {
            // Poll briefly for the global to land.
            var tries = 0;
            var iv = setInterval(function () {
                tries++;
                if (typeof window.OverdriveEvSpriteCache === 'object') {
                    clearInterval(iv);
                    cb(window.OverdriveEvSpriteCache);
                } else if (tries > 60) {
                    clearInterval(iv);
                    cb(null);
                }
            }, 50);
            return;
        }
        spriteCacheLoadStarted = true;
        var s = document.createElement('script');
        s.src = '../shared/ev-card-sprite-cache.js';
        s.async = true;
        s.onload = function () { cb(window.OverdriveEvSpriteCache || null); };
        s.onerror = function () { cb(null); };
        document.head.appendChild(s);
    }

    // Try to paint a cached sprite into `canvasEl` for the given key
    // tuple. On success the 3D pipeline can be skipped for this canvas
    // until the user changes selection. Calls `cb(true|false)`.
    function tryPaintFromSpriteCache(canvasEl, modelId, color, view, cb) {
        if (!canvasEl || !modelId || !color) { cb(false); return; }
        ensureSpriteCacheModule(function (cache) {
            if (!cache) { cb(false); return; }
            cache.get(modelId, color, view, canvasEl).then(function (entry) {
                if (!entry || !entry.blob) { cb(false); return; }
                cache.paintInto(canvasEl, entry).then(cb);
            }, function () { cb(false); });
        });
    }

    // Schedule a one-shot post-render snapshot into the sprite cache.
    // The 3D instance fires `onceAfterRender` after the next paint that
    // includes both the GLB and the requested colour, so the captured
    // canvas contents reflect the correct (model, colour) tuple.
    function scheduleSpriteSnapshot(inst, canvasEl, modelId, color, view) {
        if (!inst || !canvasEl || !modelId || !color) return;
        ensureSpriteCacheModule(function (cache) {
            if (!cache) return;
            var key = cache.buildKey(modelId, color, view, canvasEl);
            if (snapshotsTaken[key]) return;
            snapshotsTaken[key] = true;
            inst.onceAfterRender(function () {
                cache.snapshot(canvasEl).then(function (blob) {
                    if (!blob) { delete snapshotsTaken[key]; return; }
                    cache.put(modelId, color, view, canvasEl, blob,
                              canvasEl.width, canvasEl.height);
                });
            });
        });
    }

    // Clear a previously-set snapshotsTaken flag so a later attempt
    // (different page in the same session, or a re-mount) can take a
    // fresh snapshot. Called when a pendingSidebarSnapshot is dropped
    // before its render callback ran.
    function clearSnapshotFlag(modelId, color, view, canvasEl) {
        if (!modelId || !color) return;
        ensureSpriteCacheModule(function (cache) {
            if (!cache) return;
            delete snapshotsTaken[cache.buildKey(modelId, color, view, canvasEl)];
        });
    }

    function ensureEv3d() {
        if (ev3dInstance) return ev3dInstance;
        var canvas = document.getElementById('evCardCanvas');
        if (!canvas) return null;
        if (ev3dLoadStarted) return null; // load is in flight
        ev3dLoadStarted = true;

        // Defer the script load until the page has had a chance to render
        // its primary content. requestIdleCallback is ideal; fall back to
        // a setTimeout for browsers without it (notably Safari ≤14).
        function instantiate() {
            if (ev3dInstance) return;
            if (typeof window.OverdriveEvCard3D !== 'function') return;
            // The native shell hides #sidebar after page load. The script may
            // still be needed by a visible aux canvas, but never bind WebGL
            // to the hidden sidebar canvas in that environment.
            if (embeddedInNativeApp) return;
            try {
                ev3dInstance = new window.OverdriveEvCard3D(canvas);
                // Replay the most recent (model, colour) pair. SOC +
                // charging state are painted on the DOM battery overlay
                // independently so they need no replay here.
                if (lastEv3dColor) ev3dInstance.setColor(lastEv3dColor);
                if (lastEv3dModel) ev3dInstance.setModel(lastEv3dModel);
                // If a setEvCardAppearance call hit the sprite-cache miss
                // path before the renderer module finished loading, we
                // owe a snapshot for that key. Replay it now.
                if (pendingSidebarSnapshot) {
                    var p = pendingSidebarSnapshot;
                    pendingSidebarSnapshot = null;
                    if (p.modelId && p.color) {
                        scheduleSpriteSnapshot(ev3dInstance, canvas,
                                               p.modelId, p.color, 'side');
                    }
                }
            } catch (e) {
                if (window.console) console.warn('[app-shell] ev-card-3d init failed:', e);
            }
        }

        function inject() {
            // Skip on pages that explicitly opt out (e.g. login.html could
            // set window.OverdriveDisableEvCard3D = true).
            if (window.OverdriveDisableEvCard3D) return;

            // If the global is already present (vehicle-control bundles
            // its own three.js + we got loaded second; or ev-map-sprite.js
            // got there first on a map page), instantiate immediately.
            if (typeof window.OverdriveEvCard3D === 'function') {
                instantiate();
                return;
            }

            // If a previous caller has already inserted the <script>,
            // don't insert a duplicate — just poll for the global to
            // appear once that load completes.
            if (document.querySelector('script[data-overdrive-ev-card-3d]')) {
                var pollTries = 0;
                var pollIv = setInterval(function () {
                    pollTries++;
                    if (typeof window.OverdriveEvCard3D === 'function') {
                        clearInterval(pollIv);
                        instantiate();
                    } else if (pollTries > 60) {
                        clearInterval(pollIv);
                    }
                }, 100);
                return;
            }

            var s = document.createElement('script');
            s.src = '../shared/ev-card-3d.js';
            s.async = true;
            s.setAttribute('data-overdrive-ev-card-3d', '1');
            s.onload = instantiate;
            s.onerror = function () {
                if (window.console) console.warn('[app-shell] ev-card-3d.js load failed');
            };
            document.head.appendChild(s);
        }
        if (typeof window.requestIdleCallback === 'function') {
            window.requestIdleCallback(inject, { timeout: 1500 });
        } else {
            setTimeout(inject, 200);
        }
        return null;
    }

    // Most-recent values applied to the 3D card. Used when the renderer
    // module finishes loading after one or more setEvCardAppearance calls
    // — replay these so the deferred mount is already correctly painted.
    var lastEv3dModel = null;
    var lastEv3dColor = null;
    // Holds the (modelId, colour) for which we owe the sidebar a
    // snapshot once the renderer module finishes loading. ensureEv3d()'s
    // instantiate() callback drains this. Hoisted up here (not down by
    // applyToAux) so the relationship to lastEv3dModel/lastEv3dColor is
    // visible at a glance.
    var pendingSidebarSnapshot = null;
    // Extra OverdriveEvCard3D instances mounted by other surfaces on the
    // same page (currently: Live View's top-down camera selector). These
    // share the same model/colour as the sidebar card and stay in sync
    // when the user changes their selection on vehicle-control.html.
    var auxEv3dInstances = [];
    // Aux canvases can mount as soon as app-shell:ready fires, while the
    // selected vehicle is still being fetched asynchronously. Treating
    // that short "unknown selection" window as a sprite-cache miss forces
    // every page visit through three.js + the full GLB, even when a cached
    // sprite exists. Hold those mounts until setEvCardAppearance resolves
    // either the saved selection or the normal fallback.
    var vehicleSelectionWaiters = [];
    var FALLBACK_MODEL_ID = 'seal';
    var FALLBACK_MODEL_COLOR = '#E8E8EC';

    function setEvCardAppearance(modelId, hexColor) {
        // Forward the user's selection to the 3D card. ev-card-3d.js
        // queues setModel/setColor calls if the renderer hasn't loaded
        // yet, so the deferred mount lands on the right (model, colour).
        var prevModel = lastEv3dModel;
        var prevColor = lastEv3dColor;
        lastEv3dModel = modelId;
        lastEv3dColor = hexColor;

        // Sidebar canvas — try the sprite-cache fast path first. Only
        // injects the 3D pipeline on a miss, so subsequent page loads
        // paint the EV card from a ~30KB webp instead of a 600KB
        // three.js + 1.6MB GLB cold parse.
        var sidebarCanvas = embeddedInNativeApp
            ? null
            : document.getElementById('evCardCanvas');
        if (sidebarCanvas && modelId && hexColor) {
            tryPaintFromSpriteCache(sidebarCanvas, modelId, hexColor, 'side', function (hit) {
                if (hit) {
                    sidebarPaintedFromSprite = true;
                    // Cache hit — the canvas is already painted. Don't
                    // touch the 3D instance unless one already exists
                    // (e.g. the user is on vehicle-control.html where
                    // the live three.js scene is wanted anyway).
                    if (ev3dInstance) {
                        if (modelId)  ev3dInstance.setModel(modelId);
                        if (hexColor) ev3dInstance.setColor(hexColor);
                    }
                    return;
                }
                // Miss — fall through to the 3D pipeline AND register a
                // snapshot for the next page that lands on this key.
                // If we've already painted the canvas in 2D from a
                // prior cache hit, the canvas is locked out of WebGL;
                // swap it for a fresh clone first.
                var canvasForLive = sidebarCanvas;
                if (sidebarPaintedFromSprite && !ev3dInstance) {
                    canvasForLive = swapSidebarCanvasForFresh() || sidebarCanvas;
                }
                var inst = ensureEv3d();
                if (inst) {
                    if (modelId)  inst.setModel(modelId);
                    if (hexColor) inst.setColor(hexColor);
                    scheduleSpriteSnapshot(inst, canvasForLive, modelId, hexColor, 'side');
                } else {
                    // Renderer module is in flight. Schedule the
                    // snapshot once it lands — ensureEv3d's instantiate
                    // callback drains pendingSidebarSnapshot.
                    //
                    // Two rapid misses before the renderer instantiates
                    // would overwrite an earlier pending snapshot here.
                    // That's fine semantically (last-write-wins matches
                    // the user's most recent selection), but if the
                    // earlier (model,colour) had already set its
                    // snapshotsTaken flag we need to clear it so a
                    // future page navigation can still snapshot it.
                    if (pendingSidebarSnapshot
                            && (pendingSidebarSnapshot.modelId !== modelId
                                || pendingSidebarSnapshot.color !== hexColor)) {
                        clearSnapshotFlag(pendingSidebarSnapshot.modelId,
                                          pendingSidebarSnapshot.color, 'side',
                                          sidebarCanvas);
                    }
                    pendingSidebarSnapshot = { modelId: modelId, color: hexColor };
                }
            });
        } else if (!embeddedInNativeApp) {
            // Fallback for callers that don't have the canvas or only
            // have one of (model, colour) — preserve the original
            // behaviour so login.html and friends still work.
            var inst = ensureEv3d();
            if (inst) {
                if (modelId)  inst.setModel(modelId);
                if (hexColor) inst.setColor(hexColor);
            }
        }

        // Aux canvases (Live View top-down selector, map markers).
        // Iterate backwards so we can splice disposed instances out as
        // we go without confusing the index. Trip-map remounts churn
        // these — without the splice the array would grow each time
        // the user opens a different trip and stale entries would
        // throw on setColor (carModel/scene set to null in dispose()).
        for (var i = auxEv3dInstances.length - 1; i >= 0; i--) {
            var aux = auxEv3dInstances[i];
            if (aux._disposed) {
                auxEv3dInstances.splice(i, 1);
                continue;
            }
            applyToAux(aux, modelId, hexColor);
        }

        // Drain deferred aux mounts only after the existing aux iteration.
        // A callback can add a sprite-only/live entry to auxEv3dInstances;
        // deferring the drain avoids applying the same selection twice in
        // this pass and issuing duplicate cache lookups.
        if (modelId && hexColor && vehicleSelectionWaiters.length) {
            var waiters = vehicleSelectionWaiters.slice();
            vehicleSelectionWaiters = [];
            for (var w = 0; w < waiters.length; w++) {
                try { waiters[w](); } catch (e) {
                    if (window.console) console.warn('[app-shell] deferred vehicle canvas mount failed:', e);
                }
            }
        }

        // If the user actually changed their selection (vehicle-control
        // calls refreshVehicle() after a save), surface a
        // vehicle-changed event so other surfaces (map markers, custom
        // pages) can react without re-polling the daemon. Includes the
        // previous values so listeners that maintain their own caches
        // can invalidate the right entries.
        //
        // Suppress the synthetic "null → first value" diff that fires
        // on the initial applyVehicleSelection — listeners only care
        // about user-driven changes, not the first hydration.
        var hadPrior = (prevModel !== null) || (prevColor !== null);
        if (hadPrior && (prevModel !== modelId || prevColor !== hexColor)) {
            try {
                var ev;
                if (typeof CustomEvent === 'function') {
                    ev = new CustomEvent('overdrive:vehicle-changed', {
                        detail: {
                            modelId: modelId,  color: hexColor,
                            prevModelId: prevModel, prevColor: prevColor
                        }
                    });
                } else {
                    ev = document.createEvent('CustomEvent');
                    ev.initCustomEvent('overdrive:vehicle-changed', true, true, {
                        modelId: modelId,  color: hexColor,
                        prevModelId: prevModel, prevColor: prevColor
                    });
                }
                document.dispatchEvent(ev);
            } catch (e) {}
        }
    }

    // Apply (model, colour) to an aux instance. Sprite-only auxes
    // (created when mountVehicleCanvas hit the cache) re-attempt the
    // cache for the new key inside their own setModel/setColor; live
    // 3D auxes update their scene and queue a snapshot for the new key
    // so the next page that mounts the same aux gets a fast paint.
    //
    // CRITICAL: must NOT call tryPaintFromSpriteCache on a canvas that's
    // already bound to a WebGL context (live aux) — getContext('2d')
    // would fail and the call would be a no-op anyway. The sprite-only
    // path is opt-in via aux._spriteOnly; live auxes route through the
    // 3D state and snapshot AFTER their next render.
    function applyToAux(aux, modelId, hexColor) {
        if (!aux || !modelId || !hexColor) {
            if (aux) {
                if (modelId)  aux.setModel(modelId);
                if (hexColor) aux.setColor(hexColor);
            }
            return;
        }
        // Preserve the aux's actual view ('top' | 'three-quarter' | 'side')
        // so the sprite-cache key here matches the one mountVehicleCanvas
        // filed the snapshot under. Collapsing to a 'top'|'side' binary
        // mis-keyed the dashboard hero's 'three-quarter' canvas as 'side',
        // so every colour change missed the cache and re-rendered WebGL +
        // orphaned a sprite under the wrong key.
        var view = (aux.view === 'top' || aux.view === 'three-quarter') ? aux.view : 'side';
        if (aux._spriteOnly) {
            // Sprite-only aux: setModel/setColor on the pseudo-instance
            // re-try the cache. On miss they upgrade to a live mount,
            // which in turn schedules its own snapshot.
            aux.setModel(modelId);
            aux.setColor(hexColor);
            return;
        }
        // Live 3D aux. Update the scene; snapshot the next render into
        // the cache so a future page can take the fast path.
        aux.setModel(modelId);
        aux.setColor(hexColor);
        if (aux.canvas) {
            scheduleSpriteSnapshot(aux, aux.canvas, modelId, hexColor, view);
        }
    }


    function applyVehicleSelection() {
        // No-op if the EV card isn't mounted (login.html, dev-only pages).
        if (!document.getElementById('evCardCanvas')) return;
        function applyFallbackAppearance() {
            setEvCardAppearance(FALLBACK_MODEL_ID, FALLBACK_MODEL_COLOR);
        }
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/api/models/selected', true);
            xhr.timeout = 4000;
            xhr.onload = function () {
                if (xhr.status < 200 || xhr.status >= 300) {
                    applyFallbackAppearance();
                    return;
                }
                var data;
                try { data = JSON.parse(xhr.responseText) || {}; }
                catch (e) {
                    applyFallbackAppearance();
                    return;
                }
                setEvCardAppearance(data.modelId || FALLBACK_MODEL_ID,
                    data.color || FALLBACK_MODEL_COLOR);
            };
            xhr.onerror = function () { applyFallbackAppearance(); };
            xhr.ontimeout = function () { applyFallbackAppearance(); };
            xhr.send();
        } catch (e) { applyFallbackAppearance(); }
    }

    // ============================================================
    // Sidebar group collapse
    // ============================================================
    //
    // Each .nav-group-header (Integrations / Diagnostics / Settings) becomes
    // a tappable summary that toggles every .nav-link between it and the
    // next group-header / nav-divider.
    //
    // Implementation: walk the .sidebar-nav once, for each group-header
    //  - inject a chevron at the trailing edge,
    //  - record the .nav-link siblings up to the next break,
    //  - on click, toggle a `data-collapsed` attribute on the group-header
    //    and apply [hidden] to its children. Persist to localStorage.
    //
    // The persistence key uses the group's i18n slug (e.g. "integrations_group")
    // so it survives label translation.
    function wireGroupCollapse(aside) {
        var nav = aside.querySelector('.sidebar-nav');
        if (!nav) return;
        // Cache the nav so external callers (update-flow.js after it injects
        // its sidebar entry) can re-run wiring on the same DOM.
        wireGroupCollapse._lastAside = aside;
        var children = nav.children;
        // Collect group definitions: { headerEl, slug, members: [linkEl,…] }.
        var groups = [];
        var currentGroup = null;
        for (var i = 0; i < children.length; i++) {
            var el = children[i];
            if (el.classList.contains('nav-group-header')) {
                if (currentGroup) groups.push(currentGroup);
                var labelEl = el.querySelector('.nav-group-label');
                var slug = labelEl && labelEl.getAttribute('data-i18n')
                    ? labelEl.getAttribute('data-i18n')
                    : (labelEl ? labelEl.textContent.trim().toLowerCase() : 'group-' + i);
                currentGroup = { headerEl: el, slug: slug, members: [] };
            } else if (el.classList.contains('nav-divider')) {
                // Bare hairline closes the current group.
                if (currentGroup) { groups.push(currentGroup); currentGroup = null; }
            } else if (currentGroup) {
                // Anything else (.nav-link) between headers belongs to the group.
                currentGroup.members.push(el);
            }
        }
        if (currentGroup) groups.push(currentGroup);

        function readState(slug) {
            try { return localStorage.getItem('overdrive.navGroup.' + slug) === '1'; }
            catch (e) { return false; }
        }
        function writeState(slug, collapsed) {
            try { localStorage.setItem('overdrive.navGroup.' + slug, collapsed ? '1' : '0'); }
            catch (e) { /* ignore */ }
        }

        for (var g = 0; g < groups.length; g++) {
            (function (group) {
                var header = group.headerEl;

                // Determine whether this group is currently collapsed — we
                // need to apply that state to ALL current members (including
                // freshly-injected ones), even if the header itself is
                // already wired from a prior call.
                var alreadyWired = header.hasAttribute('data-group-wired');
                var collapsed;
                if (alreadyWired) {
                    collapsed = header.getAttribute('data-collapsed') === 'true';
                } else {
                    var hasActive = false;
                    for (var k = 0; k < group.members.length; k++) {
                        if (group.members[k].classList.contains('active') ||
                            group.members[k].getAttribute('aria-current') === 'page') {
                            hasActive = true; break;
                        }
                    }
                    collapsed = hasActive ? false : readState(group.slug);
                }

                // Apply state to every tracked member — re-runs reach newly
                // injected links so they pick up the current collapsed state.
                if (collapsed) header.setAttribute('data-collapsed', 'true');
                else header.removeAttribute('data-collapsed');
                header.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
                for (var m = 0; m < group.members.length; m++) {
                    if (collapsed) group.members[m].setAttribute('hidden', '');
                    else group.members[m].removeAttribute('hidden');
                }

                // Skip the click/keydown bind on re-runs to avoid duplicate
                // handlers firing the toggle twice per click.
                if (alreadyWired) return;
                header.setAttribute('data-group-wired', '1');

                // Add chevron + interactive affordance.
                header.classList.add('nav-group-header-collapsible');
                header.setAttribute('role', 'button');
                header.setAttribute('tabindex', '0');
                if (!header.querySelector('.nav-group-chevron')) {
                    var chev = document.createElement('span');
                    chev.className = 'nav-group-chevron';
                    chev.setAttribute('aria-hidden', 'true');
                    chev.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
                    header.appendChild(chev);
                }

                function toggle() {
                    var nowCollapsed = header.getAttribute('data-collapsed') !== 'true';
                    if (nowCollapsed) header.setAttribute('data-collapsed', 'true');
                    else header.removeAttribute('data-collapsed');
                    header.setAttribute('aria-expanded', nowCollapsed ? 'false' : 'true');
                    // Walk siblings live each click — handles members injected
                    // after the initial wire (e.g. update-flow.js's link).
                    for (var s2 = header.nextElementSibling; s2; s2 = s2.nextElementSibling) {
                        if (s2.classList.contains('nav-group-header') ||
                            s2.classList.contains('nav-divider')) break;
                        if (nowCollapsed) s2.setAttribute('hidden', '');
                        else s2.removeAttribute('hidden');
                    }
                    writeState(group.slug, nowCollapsed);
                }
                header.addEventListener('click', toggle);
                header.addEventListener('keydown', function (ev) {
                    if (ev.key === 'Enter' || ev.key === ' ' || ev.keyCode === 13 || ev.keyCode === 32) {
                        ev.preventDefault();
                        toggle();
                    }
                });
            }(groups[g]));
        }
    }

    // Re-apply on demand (for pages that change the selection live, like
    // vehicle-control.html itself). Pages can call:
    //     window.OverdriveAppShell.refreshVehicle()
    // after they save a new model/colour to /api/models/selected.
    window.OverdriveAppShell = window.OverdriveAppShell || {};
    window.OverdriveAppShell.refreshVehicle = applyVehicleSelection;

    // Mount an additional OverdriveEvCard3D on a custom canvas (e.g. the
    // Live View camera selector). Reuses the sidebar's vendor download —
    // ev-card-3d.js is idempotent and adopts already-loaded THREE — and
    // tracks whatever (model, colour) the user has selected, replaying
    // the latest values once the renderer module is in place.
    window.OverdriveAppShell.mountVehicleCanvas = function (canvasEl, opts) {
        if (!canvasEl) return null;
        var view = 'side';
        if (opts && opts.view === 'top') view = 'top';
        else if (opts && opts.view === 'three-quarter') view = 'three-quarter';
        var readyNotified = false;

        // Surfaces may keep an inexpensive placeholder visible until this
        // callback fires. A cache hit and the first complete WebGL render
        // both count as ready; notify at most once for the initial mount.
        function notifyReady(source) {
            if (readyNotified) return;
            readyNotified = true;
            if (opts && typeof opts.onReady === 'function') {
                try { opts.onReady({ source: source }); } catch (e) {
                    if (window.console) console.warn('[app-shell] vehicle canvas onReady failed:', e);
                }
            }
        }

        // Sprite-cache fast path. If we have a webp for this
        // (model, colour, view), paint the canvas in 2D and stop.
        // CRITICAL: a single HTMLCanvasElement can hold either a 2D
        // context OR a WebGL context, never both — once getContext('2d')
        // succeeds, a subsequent getContext('webgl') returns null. So
        // for aux canvases we have to commit to one path. Sprite path
        // is the right default for non-interactive surfaces (map
        // markers, top-down hotspot art); it gives the same perf win
        // the sidebar already enjoys.
        function tryCache(cb) {
            if (!lastEv3dModel || !lastEv3dColor) { cb(false); return; }
            tryPaintFromSpriteCache(canvasEl, lastEv3dModel, lastEv3dColor, view, cb);
        }

        var instance = null;
        function instantiate() {
            if (instance) return instance;
            if (typeof window.OverdriveEvCard3D !== 'function') return null;
            try {
                instance = new window.OverdriveEvCard3D(canvasEl, opts || {});
                auxEv3dInstances.push(instance);
                if (lastEv3dColor) instance.setColor(lastEv3dColor);
                if (lastEv3dModel) instance.setModel(lastEv3dModel);
                if (lastEv3dModel && lastEv3dColor) {
                    instance.onceAfterRender(function () { notifyReady('render'); });
                    scheduleSpriteSnapshot(instance, canvasEl,
                                           lastEv3dModel, lastEv3dColor, view);
                }
            } catch (e) {
                if (window.console) console.warn('[app-shell] aux ev-card-3d init failed:', e);
            }
            return instance;
        }

        // Track this canvas in auxEv3dInstances even when we satisfy it
        // from the sprite cache, so a later setEvCardAppearance can
        // re-paint it on user selection change. We use a lightweight
        // pseudo-instance — not a real OverdriveEvCard3D — exposing the
        // minimum the iteration loop needs (canvas, view, _disposed,
        // setModel/setColor that re-attempt the cache).
        function makeSpriteOnlyAux() {
            var aux = {
                canvas: canvasEl,
                view: view,
                _disposed: false,
                _spriteOnly: true,
                setModel: function (m) {
                    if (!m || !lastEv3dColor) return;
                    tryPaintFromSpriteCache(canvasEl, m, lastEv3dColor, view, function (hit) {
                        if (!hit) upgradeToLive(aux);
                    });
                },
                setColor: function (c) {
                    if (!c || !lastEv3dModel) return;
                    tryPaintFromSpriteCache(canvasEl, lastEv3dModel, c, view, function (hit) {
                        if (!hit) upgradeToLive(aux);
                    });
                }
            };
            auxEv3dInstances.push(aux);
            return aux;
        }

        // Sprite-only auxes upgrade to a real WebGL instance the first
        // time the user selects a (model, colour) we don't have a
        // sprite for — at that point we can't paint anything from the
        // cache, so we have to render live. Splice the sprite-only
        // entry out before mounting (it shares the canvas, and the
        // WebGL context will own it from now on).
        function upgradeToLive(spriteAux) {
            spriteAux._disposed = true;
            // Reset the canvas: dropping its parent and reinserting
            // gives us a fresh element. Cheap if simply replaceChildren
            // — but we can't replace the canvas itself without breaking
            // existing references in the page (Leaflet marker, hotspot
            // overlay). Instead clear the 2D context and let three.js
            // attach. WebGL will refuse if a 2D context exists, so we
            // null out the canvas's getContext result by reassigning
            // width — this DOES NOT release the 2D context. The only
            // safe way is to swap the <canvas> for a fresh one.
            var fresh = canvasEl.cloneNode(false);
            if (canvasEl.parentNode) canvasEl.parentNode.replaceChild(fresh, canvasEl);
            canvasEl = fresh;
            instantiate();
        }

        // Wait for vehicle selection, then try the cache before loading
        // the renderer. This makes warm navigations a sprite-only path.
        function mountResolvedSelection() {
            tryCache(function (hit) {
                if (hit) {
                    makeSpriteOnlyAux();
                    notifyReady('cache');
                    return;
                }
                ensureEv3d();
                if (typeof window.OverdriveEvCard3D === 'function') {
                    instantiate();
                    return;
                }
                // Vendor still in flight — poll for the global the same way
                // ensureEv3d() does for its own canvas.
                var tries = 0;
                var iv = setInterval(function () {
                    tries++;
                    if (typeof window.OverdriveEvCard3D === 'function') {
                        clearInterval(iv);
                        instantiate();
                    } else if (tries > 80) {
                        clearInterval(iv);
                    }
                }, 100);
            });
        }

        if (lastEv3dModel && lastEv3dColor) {
            mountResolvedSelection();
        } else {
            vehicleSelectionWaiters.push(mountResolvedSelection);
        }

        return null;
    };

    // Battery strip driver. The bar lives at the base of the 3D body
    // (see CSS .ev-battery-body-overlay). Single solid colour by SOC
    // band — green when healthy, amber when low, red when critical, no
    // multi-colour ramp. data-soc-state on the wrapper picks the
    // gradient; SOC also sets the WIDTH of the visible portion.
    function socStateFor(pct) {
        if (pct <= 20) return 'critical';
        if (pct <= 50) return 'warning';
        return 'healthy';
    }
    window.OverdriveAppShell.setSoc = function (pct) {
        var fill = document.getElementById('evBatteryFill');
        var bar  = document.getElementById('evBatteryBar');
        var clamped = Math.max(0, Math.min(100, pct));
        if (fill) fill.style.width = clamped + '%';
        if (bar)  bar.setAttribute('data-soc-state', socStateFor(clamped));
    };
    // Map charging power (kW) to fill-up animation duration. Slow AC L1
    // crawls (5s/cycle) → fast DC charging visibly snaps (~0.8s/cycle).
    // The mapping is piecewise so the visual differentiation lines up
    // with the user's mental model: trickle, AC, DC, ultra-fast.
    function fillupDurationForKw(kw) {
        if (!kw || kw <= 0) return 2.4;          // unknown → default
        if (kw <= 3.6)   return 5.0;              // L1 trickle
        if (kw <= 11)    return 3.6;              // AC L2
        if (kw <= 22)    return 2.6;              // AC fast L2
        if (kw <= 50)    return 1.6;              // DC fast
        if (kw <= 150)   return 1.0;              // DC hyper
        return 0.8;                                // ultra-rapid (>150 kW)
    }
    window.OverdriveAppShell.setCharging = function (on, powerKw) {
        // The .charging class on the EV card root gates the CSS keyframes
        // (battery sweep + bolt icon scale-in). core.js already toggles
        // this class before calling us — forwarding here keeps the
        // contract intact for any direct callers. powerKw is optional;
        // when supplied, the fill-up cycle speed scales with charging
        // power (set as a CSS var on the card root so the keyframe
        // animation-duration follows live).
        var card = document.getElementById('evCard');
        if (!card) return;
        if (on) card.classList.add('charging');
        else    card.classList.remove('charging');
        var dur = fillupDurationForKw(powerKw);
        card.style.setProperty('--ev-charge-fillup-duration', dur + 's');
    };

    // Re-run group-collapse wiring after dynamic nav links land (e.g.
    // update-flow.js's "Check for Updates" link). The function is
    // idempotent — already-wired headers skip the click bind, and every
    // run re-applies the persisted collapsed state to current members.
    window.OverdriveAppShell.rewireNavGroups = function () {
        var aside = wireGroupCollapse._lastAside ||
                    document.getElementById('sidebar') ||
                    document.querySelector('.sidebar');
        if (aside) wireGroupCollapse(aside);
    };

    // Auto-load the collapsible-card script so every page that includes
    // app-shell.js gets the collapse behavior without needing a per-page
    // <script> tag. Idempotent: a second injection is a no-op via the
    // data-overdrive-collapse marker.
    function ensureCollapseScript() {
        if (document.querySelector('script[data-overdrive-collapse]')) return;
        var s = document.createElement('script');
        s.src = '../shared/app-collapse.js';
        s.setAttribute('data-overdrive-collapse', '1');
        s.async = false;
        // Place it next to app-shell.js so the relative path resolves the
        // same way as the page's other shared imports.
        var ref = document.querySelector('script[src*="app-shell.js"]');
        if (ref && ref.parentNode) ref.parentNode.insertBefore(s, ref.nextSibling);
        else document.head.appendChild(s);
    }
    ensureCollapseScript();

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', mount);
    } else {
        mount();
    }
}());
