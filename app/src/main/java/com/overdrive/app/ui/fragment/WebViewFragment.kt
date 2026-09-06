package com.overdrive.app.ui.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.overdrive.app.R
import com.overdrive.app.daemon.CameraDaemon

/**
 * WebView fragment that loads pages from the daemon's HTTP server.
 *
 * Problem: sing-box sets a system HTTP proxy (127.0.0.1:8119) which WebView
 * honors. Requests to 127.0.0.1:8080 get routed through the proxy and fail.
 *
 * Solution: Temporarily clear the system proxy before loading, restore after.
 * Also inject auth JWT cookie so API calls pass AuthMiddleware.
 */
class WebViewFragment : Fragment() {

    companion object {
        const val ARG_PAGE_PATH = "page_path"
        private const val KEY_SAVED_URL = "saved_url"

        // ── Key-mapping capture bridge ────────────────────────────────────
        // The Key Mapping "press a button to capture it" box lives in the
        // WebView, but hardware buttons hit the NATIVE AccessibilityService
        // (onKeyEvent) — they never become WebView DOM keydown events. So the
        // dispatcher, while the page is in capture mode, forwards the keycode to
        // the live WebView here; onCapturedKey(...) pushes it into the page via
        // evaluateJavascript (window.KM.onNativeKey). captureArmed lets the
        // dispatcher cheaply know whether to forward+consume (during capture) vs.
        // run the normal mapping. Set by the page through
        // AndroidBridge.setKeyCapture(bool).
        @Volatile private var liveWebView: WebView? = null
        @JvmStatic @Volatile var captureArmed: Boolean = false
            private set

        /** Called by the a11y dispatcher (app process) when a hardware key
         *  arrives during capture. Pushes the keycode into the page on the UI
         *  thread. No-op if no page is live. Best-effort. */
        @JvmStatic
        fun onCapturedKey(keyCode: Int) {
            val wv = liveWebView ?: return
            wv.post {
                try {
                    wv.evaluateJavascript(
                        "window.KM && window.KM.onNativeKey && window.KM.onNativeKey(" + keyCode + ");",
                        null)
                } catch (_: Throwable) { /* page navigated away */ }
            }
        }

        /** Page toggles capture mode via AndroidBridge.setKeyCapture(). */
        @JvmStatic fun setCaptureArmed(armed: Boolean) { captureArmed = armed }

        // CDN strategy short-circuit. The fetch loop tries HTTP-proxy →
        // SOCKS-proxy → direct in order. On the head unit's mobile data
        // path each failing attempt eats up to (connect + read) ms, and
        // a single page (live-view.html) easily fires 30+ tile / font /
        // jmuxer requests in parallel through this same loop — multiply
        // a 3-strategy serial cascade by 30 and the page parser stalls
        // for tens of seconds. Once we know which strategy is currently
        // working, every CDN request takes that one first; the cascade
        // is reserved for the periodic re-probe.
        //
        // Values: 0 = unknown / re-probe, 1 = HTTP proxy, 2 = SOCKS proxy,
        // 3 = direct.
        @Volatile
        private var lastWorkingCdnStrategy: Int = 0
        @Volatile
        private var lastCdnStrategyTimestamp: Long = 0L
        // Re-probe roughly every minute so a recovered proxy / re-routed
        // network gets a chance, without thrashing on every tile.
        private const val CDN_STRATEGY_TTL_MS: Long = 60_000L

        // Per-attempt budgets. The previous values (8s connect / 15s read
        // for proxies, 3s connect / 15s read direct) added up to a
        // worst-case ~70s per resource on a parser-blocking <script> tag
        // on bad mobile data. 3.5s + 4s caps each attempt at ~7.5s, and
        // with the sticky strategy above the *typical* cost is one
        // attempt per resource.
        private const val CDN_CONNECT_TIMEOUT_MS = 3500
        private const val CDN_READ_TIMEOUT_MS = 4000
        
        /**
         * JavaScript injected after each page load.
         *
         *   1. Tags <html data-app-shell="1"> so the page CSS can opt into
         *      app-shell-only tweaks via that attribute selector — cleaner
         *      than scattering Android-specific overrides across pages.
         *   2. Hides the in-page sidebar / mobile header / fullscreen
         *      button — the Android shell already provides a navigation rail
         *      and top app bar, so the page-internal navigation is redundant
         *      and creates the overlap the user reports on Live View.
         *   3. Repositions the Map ↔ Cameras mini-preview toggle to the
         *      bottom-right (default top-left collides with the camera-top-bar
         *      pill in landscape windowed mode).
         *   4. Patches window.fetch() to route POST/PUT/DELETE through
         *      AndroidBridge.httpRequest() so writes bypass sing-box proxy.
         *      GET requests go through the normal WebView path so polling
         *      doesn't block the JS thread. theme.js applies the same patch
         *      in <head> so Live View auto-start does not race this inject.
         */
        private const val INJECT_JS = """
(function() {
    document.documentElement.setAttribute('data-app-shell', '1');

    var css = [
        // === Global: hide page-internal navigation. The Android shell already
        //     provides the nav rail + top app bar, so the in-page sidebar,
        //     mobile header, page-header title, and floating mini-preview tab
        //     switcher are all redundant and visually noisy. ===
        '.sidebar, .sidebar-overlay, .mobile-header { display: none !important; }',
        // The activity's MaterialToolbar already shows the page title; hiding
        // the in-page <header class="page-header"> kills the duplicate title.
        '.page-header { display: none !important; }',
        // Sidebar is hidden in the embedded WebView, so collapse the
        // CSS variable that anchored sticky elements (.bottom-tabs,
        // .footer-bar) to the right of where the sidebar used to live.
        // Without this the bottom-tab bar leaves a 260px gap on the left
        // and only fills half the viewport in landscape.
        ':root { --sidebar-width: 0px !important; }',
        '.main-content { margin-left: 0 !important; padding-top: 0 !important; }',
        '.bottom-tabs { left: 0 !important; right: 0 !important; }',
        '.pip-container, .pip-toggle-btn, #pipToggleBtn, #pipContainer { display: none !important; }',
        '.toast-container { z-index: 20000 !important; bottom: 70px !important; }',
        '.page-body { padding-bottom: 80px !important; }',
        '.footer-bar { bottom: 0 !important; left: 0 !important; right: 0 !important;',
        '              padding: 12px 16px !important; padding-bottom: 12px !important;',
        '              z-index: 10000 !important; }',

        // === Strip the persistent yellow focus ring Chrome leaves on
        //     buttons after a tap. The active tab is already conveyed by
        //     the .is-active pill background; the trailing focus outline
        //     reads as a stuck "stale highlight" and the user reports it
        //     as a continuous yellow box that lingers until they tap
        //     somewhere else. CSS alone cannot fully suppress the WebView
        //     focus rect on Chrome 58 — we ALSO blur the button on click
        //     below in JS. ===
        '.bottom-tab, .bottom-tab:focus, .bottom-tab:focus-visible,',
        '.bottom-tab:active { outline: none !important; box-shadow: none !important;',
        '                     background-image: none !important; }',
        'button:focus, button:focus-visible { outline: none !important; }',
        '* { -webkit-tap-highlight-color: transparent !important; }',

        // === Generic icon-then-text spacing inside the WebView shell.
        //     Across pages (recording.html / surveillance.html / mqtt.html
        //     / about.html / abrp.html / telegram.html ...) icon+title rows
        //     use `display:flex; gap:` to space the leading SVG from its
        //     label. Chrome 58 on the BYD head-unit honors `gap` only
        //     intermittently — on some firmware builds the icon and the
        //     text end up touching. Belt-and-braces fix: bump gap AND add
        //     a margin-right shim on every direct SVG child of an
        //     icon-bearing row so the spacing always shows. Scoped to
        //     [data-app-shell="1"] so the standalone web tunnel keeps its
        //     authored spacing. ===
        '[data-app-shell="1"] .card-title { gap: 12px !important; }',
        '[data-app-shell="1"] .card-title > svg { margin-right: 6px !important; }',
        '[data-app-shell="1"] .info-box-note > .info-icon,',
        '[data-app-shell="1"] .info-box-warning > .info-icon { margin-right: 12px !important; }',
        '[data-app-shell="1"] .tier-card { gap: 16px !important; }',
        '[data-app-shell="1"] .tier-card > .tier-icon { margin-right: 8px !important; }',
        '[data-app-shell="1"] .credit-row { gap: 16px !important; }',
        '[data-app-shell="1"] .credit-row > .credit-avatar { margin-right: 4px !important; }',
        // Settings rows that pair a leading SVG with a label.
        '[data-app-shell="1"] .setting-row > svg:first-child,',
        '[data-app-shell="1"] .setting-info > svg:first-child { margin-right: 12px !important; }',

        // === Recording-mode chip sizing inside the embedded WebView only.
        //     The 44dp chip + 22px glyph the standalone web shell uses reads
        //     too heavy in the in-app WebView — the head-unit window is
        //     narrower than the desktop / tunnel viewport, so the icon
        //     dominates the row. Shrink the chip to 32dp and the SVG to
        //     16px ONLY when the page is hosted by the Android shell
        //     (data-app-shell="1") so the standalone tunnel stays untouched. ===
        '[data-app-shell="1"] .mode-icon {',
        '   flex: 0 0 32px !important; width: 32px !important; height: 32px !important;',
        '   border-radius: 9px !important; }',
        '[data-app-shell="1"] .mode-icon svg {',
        '   width: 16px !important; height: 16px !important; }',
        // gap: bumped to 18px so the icon chip doesn't crowd the title.
        // Chrome 58 supports flex `gap` here (the WebView constraint that
        // forced margin-based shims only applies inside the 3D vehicle-
        // control overlay; PWA pages can use modern flex gap).
        '[data-app-shell="1"] .mode-card {',
        '   gap: 18px !important; padding: 12px !important;',
        '   padding-right: 44px !important; min-height: 0 !important; }',
        // Belt-and-braces margin shim — if any earlier rule clobbers gap,
        // the chip still gets a visible breathing space before the body.
        '[data-app-shell="1"] .mode-icon { margin-right: 6px !important; }',
        '[data-app-shell="1"] .mode-name { font-size: 13px !important; }',
        '[data-app-shell="1"] .mode-desc { font-size: 11px !important; }',

        // === Live View (live-view.html) tweaks ===
        // The mini-preview is the only Map ↔ Cameras toggle on this page,
        // so we MUST keep it visible. Match the web-tunnel placement
        // (top-left) — earlier injection moved it bottom-right because the
        // page-internal mobile-header pushed content down, but we hide that
        // header above so the original top: 80px / left: 24px works fine
        // and the in-app WebView matches what users see on the tunnel.
        '[data-app-shell="1"] .mini-preview { top: 80px !important; left: 24px !important;',
        '   right: auto !important; bottom: auto !important;',
        '   width: 64px !important; height: 64px !important; z-index: 60 !important; }',
        '[data-app-shell="1"] .mini-preview-content svg { width: 22px !important; height: 22px !important; }',
        '[data-app-shell="1"] .mini-preview-label { font-size: 9px !important; padding: 3px 0 !important; }',
        // Hide the in-page fullscreen button — the WebView already fills
        // the destination and the button's request would be denied here.
        '[data-app-shell="1"] .top-bar-btn { display: none !important; }',
        // Pull the absolute-positioned camera top bar in by a hair so the
        // connection-status pill and quality dropdown breathe at narrow
        // landscape widths (head-unit windowed mode, ~600-900px wide).
        '[data-app-shell="1"] .camera-top-bar { padding: 12px 14px !important; gap: 8px; }',
        '[data-app-shell="1"] .camera-top-bar .top-bar-left,',
        '[data-app-shell="1"] .camera-top-bar .top-bar-right { min-width: 0; flex-wrap: nowrap; }',
        '[data-app-shell="1"] .quality-select-sota { min-width: 96px; max-width: 140px; }',
        // Map overlay buttons (My Location / Directions) — keep them clear
        // of the top-bar pill. The default top: 16px lands underneath the
        // pill on narrow viewports.
        '[data-app-shell="1"] #panelMap .map-overlay-actions { top: 14px !important; right: 14px !important; gap: 10px !important; }',
        '[data-app-shell="1"] .btn-map-float { width: 44px !important; height: 44px !important; }',
        '[data-app-shell="1"] .btn-map-float svg { width: 20px !important; height: 20px !important; }',
        // Camera hotspot labels — keep a generous translated-label clamp,
        // but preserve the page's paint-independent pill size and opposing
        // left/right anchors. The former negative-offset rules set both
        // horizontal edges after the page moved to right:/left: anchoring,
        // squeezing LEFT/RIGHT down to an ellipsis on the head unit.
        '[data-app-shell="1"] .cam-hotspot .hotspot-label {',
        '   max-width: 74px; white-space: nowrap; overflow: hidden;',
        '   text-overflow: ellipsis; padding: 4px 6px; font-size: 11px; }',
        '[data-app-shell="1"] .cam-hotspot[data-cam="4"] .hotspot-label {',
        '   left: auto !important; right: calc(100% + 4px) !important; }',
        '[data-app-shell="1"] .cam-hotspot[data-cam="2"] .hotspot-label {',
        '   right: auto !important; left: calc(100% + 4px) !important; }',

        // === Page-specific carry-overs (kept from previous behaviour) ===
        '#safeLocMap { z-index: 1 !important; position: relative !important; overflow: hidden !important; }',
        '#safeLocMap .leaflet-pane { z-index: 1 !important; }',
        '#safeLocMap .leaflet-control-container { z-index: 10 !important; }',
        '#roiCanvasContainer { position: relative !important; width: 100% !important;',
        '                      height: 200px !important; padding-bottom: 0 !important;',
        '                      overflow: hidden !important; z-index: 0 !important; }',
        '#roiCanvas { position: absolute !important; top: 0 !important; left: 0 !important;',
        '             width: 100% !important; height: 100% !important;',
        '             max-width: 100% !important; max-height: 200px !important; }'
    ].join(' ');

    var s = document.createElement('style');
    s.textContent = css;
    document.head.appendChild(s);

    // === Blur focused .bottom-tab on click + after each tab swap.
    //     Chrome 58 keeps the focus ring drawn until the user taps
    //     somewhere outside the button; calling blur() drops the focus
    //     so the visual highlight clears immediately. The .is-active
    //     pill remains, so the active tab is still obvious. ===
    function patchBottomTabsBlur() {
        var bar = document.querySelector('.bottom-tabs');
        if (!bar) return false;
        bar.addEventListener('click', function (ev) {
            var btn = ev.target;
            while (btn && btn !== bar && !(btn.classList && btn.classList.contains('bottom-tab'))) {
                btn = btn.parentNode;
            }
            if (btn && btn !== bar && typeof btn.blur === 'function') {
                setTimeout(function () { try { btn.blur(); } catch (e) {} }, 0);
            }
        }, true);
        return true;
    }
    if (!patchBottomTabsBlur()) {
        // Tab bar is built by app-tabs.js after DOMContentLoaded; observe.
        var tabsObserver = new MutationObserver(function () {
            if (patchBottomTabsBlur()) tabsObserver.disconnect();
        });
        tabsObserver.observe(document.body, { childList: true, subtree: true });
    }

    // Replace fetch() to bypass sing-box proxy for localhost
    if (window.AndroidBridge && !window._fetchPatched) {
        window._fetchPatched = true;
        var _orig = window.fetch;
        window.fetch = function(input, init) {
            init = init || {};
            var url = (typeof input === 'string') ? input : (input.url || '');
            var isLocal = url.startsWith('/') || url.indexOf('127.0.0.1') !== -1 || url.indexOf('localhost') !== -1;
            
            // Only intercept API calls — let media (video/thumb/snapshot/h264) go through normally
            // Also let /status go through normal async path — it polls every 3s and would block the JS thread
            var isApi = url.indexOf('/api/') !== -1 || url.indexOf('/auth/') !== -1;
            if (!isLocal || !isApi) return _orig.call(window, input, init);
            
            var method = (init.method || 'GET').toUpperCase();
            
            // Only use synchronous AndroidBridge for POST/PUT/DELETE (writes).
            // GET requests go through normal async WebView path (shouldInterceptRequest handles proxy bypass).
            // This prevents the synchronous bridge from blocking the JS thread during polling/config loads.
            if (method === 'GET') return _orig.call(window, input, init);
            var body = init.body || '';
            var headers = {};
            if (init.headers) {
                if (init.headers instanceof Headers) {
                    init.headers.forEach(function(v, k) { headers[k] = v; });
                } else if (typeof init.headers === 'object') {
                    headers = init.headers;
                }
            }
            if (!headers['Content-Type'] && !headers['content-type'] && body) {
                headers['Content-Type'] = 'application/json';
            }
            
            var fullUrl = url.startsWith('/') ? 'http://127.0.0.1:8080' + url : url;
            
            return new Promise(function(resolve) {
                try {
                    var raw = AndroidBridge.httpRequest(fullUrl, method, body, JSON.stringify(headers));
                    var status = 200;
                    try {
                        var parsed = JSON.parse(raw);
                        if (parsed._status) { status = parsed._status; delete parsed._status; raw = JSON.stringify(parsed); }
                    } catch(e) {}
                    resolve(new Response(raw, { status: status, headers: { 'Content-Type': 'application/json' } }));
                } catch(e) {
                    console.error('AndroidBridge error:', e);
                    resolve(new Response('{"error":"bridge_error"}', { status: 500 }));
                }
            });
        };
        console.log('[OverDrive] fetch() patched to bypass proxy');
    }
})();
"""
    }

    private var webView: WebView? = null
    private var loadingOverlay: View? = null
    private var errorOverlay: View? = null
    private var btnRetry: MaterialButton? = null
    private var currentUrl: String? = null
    private var pageLoadFailed = false
    // True between onPageStarted and onPageFinished / onReceivedError. The
    // auth-cookie retry consults this so it doesn't fire a reload() during
    // an in-flight load — overlapping loadUrl + reload on Chrome 58 WebView
    // is the source of the "spinner forever" hang.
    private var loadInProgress = false
    // Only allow the auth retry to trigger one reload across the fragment's
    // lifetime. After the first auth-driven reload, any further auth state
    // changes are picked up by the caller (Settings → Re-login → manual
    // retry) rather than by silently reloading whatever page the user is
    // currently looking at.
    private var authReloadFired = false

    // Pending callback from onShowFileChooser — must be invoked exactly once,
    // with either the selected URIs or null on cancel.
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    // Single-flight guard for openEventByFilename: a deep-link tap that resolves
    // slowly (the scan is a sync HTTP round-trip to a warming daemon) invites a
    // second tap; without this each tap spawns a thread that navigates, pushing
    // two player fragments or firing navigate() after the destination already
    // moved. Set true on entry, cleared once navigation resolves. @Volatile:
    // set on the worker's UI post, read on the UI thread in shouldOverride.
    @Volatile
    private var deepLinkInFlight = false

    // Picker launcher — registered in onCreate so it survives config changes.
    //
    // We use ACTION_GET_CONTENT (via ActivityResultContracts.GetContent) instead
    // of OpenMultipleDocuments. Reasons specific to the BYD head unit (Android
    // 7.1):
    //   - OpenMultipleDocuments fires ACTION_OPEN_DOCUMENT, which routes to the
    //     Storage Access Framework picker (the "Documents" tree). On the head
    //     unit that UI is either missing or shows an empty Recents tree — the
    //     user reported it as a "weird gallery I can't pick anything in".
    //   - GetContent fires ACTION_GET_CONTENT, which Android 7.1 routes to the
    //     system gallery / Files / any installed image picker. That's what
    //     normal "Upload photo" buttons in apps use, and it's the picker
    //     users on this device already know.
    //   - PickVisualMedia (the modern Photo Picker) requires Android 13+ and
    //     isn't an option on this device.
    //
    // Single-file only — the deterrent flow only ever wants one image, and
    // GetContent is single-select. The web <input> doesn't carry `multiple`
    // either, so there's no UX regression.
    private lateinit var fileChooserLauncher: ActivityResultLauncher<String>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>
    private var pendingAudioPermissionRequest: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            val result = if (uri == null) null else arrayOf(uri)
            pendingFileCallback?.onReceiveValue(result)
            pendingFileCallback = null
        }
        audioPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val request = pendingAudioPermissionRequest
            pendingAudioPermissionRequest = null
            if (request == null) return@registerForActivityResult
            try {
                if (granted && isTrustedAssistantAudioRequest(request)) {
                    request.grant(arrayOf(
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    ))
                } else {
                    request.deny()
                }
            } catch (_: Throwable) {
                try { request.deny() } catch (_: Throwable) {}
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_webview, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webView)
        // Publish the live WebView so the key-mapping capture bridge can push
        // native hardware keycodes into whatever page is showing.
        liveWebView = webView
        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        errorOverlay = view.findViewById(R.id.errorOverlay)
        btnRetry = view.findViewById(R.id.btnRetry)
        btnRetry?.setOnClickListener { retryLoad() }

        setupWebView()
        injectAuthCookie()

        currentUrl = savedInstanceState?.getString(KEY_SAVED_URL)
            ?: ("http://127.0.0.1:${CameraDaemon.HTTP_PORT}" +
                (arguments?.getString(ARG_PAGE_PATH)
                    ?: arguments?.getString("page_path")
                    ?: "/surveillance"))

        loadPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // allowFileAccess stays FALSE — the page never needs file:// and
            // leaving it off keeps the local-file attack surface closed.
            settings.allowFileAccess = false
            // allowContentAccess MUST be true, and this is not optional for
            // uploads to work at all.
            //
            // onShowFileChooser hands the WebView a content:// Uri from
            // ACTION_GET_CONTENT. When the page then does
            // `FileReader.readAsDataURL(input.files[0])`, the WebView itself has to
            // resolve that content:// Uri through the ContentResolver to produce
            // the bytes. With allowContentAccess=false that read is refused, so
            // readAsDataURL never yields data and the upload silently never sends
            // its POST — which is exactly the observed symptom: the picker opens,
            // a file is chosen, and the daemon log shows only GET /api/audio/library
            // with no POST ever arriving. Affects BOTH upload paths that use this
            // idiom (audio-library.js and the screen-deterrent image in
            // surveillance.js).
            //
            // This does NOT widen the page's reach: content:// access is limited to
            // providers that have granted this app a URI permission — i.e. exactly
            // the file the user just picked in the system picker.
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Respect server Cache-Control headers. The daemon serves HTML with no-store and
            // shared static assets (CSS/JS/fonts/icons, ~360KB total) with
            // "max-age=3600, must-revalidate" plus an ETag, so HTML is always fresh and assets
            // are not re-downloaded on every page load.
            //
            // Note the ETag does NOT help here: the proxy path below strips If-None-Match, so
            // this client can never get a 304 and each expiry costs the full body. max-age is
            // the only lever in-app, which is why it is an hour rather than minutes.
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Resolve background color from the active theme so the WebView's
            // outer chrome flips with light/dark mode.
            setBackgroundColor(resolveThemeBackground())
            // WebView is already hardware-accelerated by default; promoting to
            // LAYER_TYPE_HARDWARE allocates a persistent full-window GPU texture
            // and adds a render-into-layer + composite pass on every invalidation
            // (~10-30×/sec during live video). Removed: LAYER_TYPE_NONE is the
            // correct default for continuously-animating content.
            setLayerType(View.LAYER_TYPE_NONE, null)

            webViewClient = object : WebViewClient() {
                
                /**
                 * SOTA FIX: Intercept VIDEO requests with aggressive header handling.
                 * Bypasses sing-box proxy for all localhost requests.
                 */
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null

                    // FILTER: Only intercept our local server and external map/CDN resources
                    val isLocalServer = url.contains("127.0.0.1:${CameraDaemon.HTTP_PORT}") ||
                        url.contains("localhost:${CameraDaemon.HTTP_PORT}")
                    
                    // Bypass proxy for map tiles and CDN resources (sing-box proxy blocks these)
                    val isMapTile = url.contains("tile.openstreetmap.de") ||
                        url.contains("unpkg.com") ||
                        url.contains("cdn.jsdelivr.net") ||
                        url.contains("fonts.googleapis.com") ||
                        url.contains("fonts.gstatic.com")
                    
                    if (!isLocalServer && !isMapTile) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    
                    // For map tiles/CDN: route through sing-box proxy (HTTP or SOCKS).
                    // The BYD head unit has no direct internet — all external requests
                    // must go through the sing-box mixed proxy on port 8119.
                    //
                    // Strategy ordering, in priority:
                    //   1. The strategy that worked on the LAST CDN fetch (sticky,
                    //      re-probed every CDN_STRATEGY_TTL_MS). On a single page
                    //      load with 30+ external resources, this collapses what
                    //      used to be a 3-attempt serial cascade per resource into
                    //      a single attempt, which is the difference between the
                    //      page parser stalling for tens of seconds and rendering
                    //      promptly when sing-box is up but slow / mobile data is
                    //      flaky.
                    //   2. The remaining strategies in HTTP → SOCKS → direct order
                    //      (skipping the sticky one we already tried).
                    //
                    // On all-fail we return a real 504 instead of falling through
                    // to super.shouldInterceptRequest. Letting WebView retry the
                    // resource via the system network stack just routes it back
                    // through the same sing-box proxy (or no proxy at all on
                    // mobile-data-only) we already exhausted, and parser-blocking
                    // <script> tags hang on that retry instead of failing fast.
                    // 504 makes the resource fail instantly so the page can
                    // continue without it.
                    if (isMapTile) {
                        val proxyAvailable = com.overdrive.app.mqtt.ProxyHelper.isProxyAvailable()

                        val httpProxy: java.net.Proxy by lazy {
                            java.net.Proxy(java.net.Proxy.Type.HTTP,
                                java.net.InetSocketAddress("127.0.0.1", 8119))
                        }
                        val socksProxy: java.net.Proxy by lazy {
                            java.net.Proxy(java.net.Proxy.Type.SOCKS,
                                java.net.InetSocketAddress("127.0.0.1", 8119))
                        }

                        fun strategyForId(id: Int): java.net.Proxy? = when (id) {
                            1 -> if (proxyAvailable) httpProxy else null
                            2 -> if (proxyAvailable) socksProxy else null
                            3 -> java.net.Proxy.NO_PROXY
                            else -> null
                        }

                        // Build the attempt order. Sticky strategy first (if
                        // still within TTL), then everything else.
                        val attemptOrder = mutableListOf<Int>()
                        val now = System.currentTimeMillis()
                        val sticky = lastWorkingCdnStrategy
                        if (sticky != 0 &&
                            now - lastCdnStrategyTimestamp < CDN_STRATEGY_TTL_MS) {
                            if (strategyForId(sticky) != null) attemptOrder.add(sticky)
                        }
                        // Default cascade: HTTP, SOCKS, direct.
                        for (id in intArrayOf(1, 2, 3)) {
                            if (id !in attemptOrder && strategyForId(id) != null) {
                                attemptOrder.add(id)
                            }
                        }

                        for (id in attemptOrder) {
                            val proxy = strategyForId(id) ?: continue
                            var connection: java.net.HttpURLConnection? = null
                            try {
                                connection = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
                                connection.connectTimeout = CDN_CONNECT_TIMEOUT_MS
                                connection.readTimeout = CDN_READ_TIMEOUT_MS
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0 Safari/537.36")
                                request?.requestHeaders?.forEach { (key, value) ->
                                    if (key != "User-Agent") connection.setRequestProperty(key, value)
                                }
                                connection.connect()

                                if (connection.responseCode !in 200..399) {
                                    connection.disconnect()
                                    connection = null
                                    continue
                                }

                                val stream = connection.inputStream
                                val rawContentType = connection.contentType ?: "application/octet-stream"
                                val mime = rawContentType.split(";").first().trim()
                                val encoding = if (rawContentType.contains("charset=")) {
                                    rawContentType.substringAfter("charset=").trim()
                                } else null

                                val response = WebResourceResponse(mime, encoding, stream)
                                response.setStatusCodeAndReasonPhrase(connection.responseCode, connection.responseMessage ?: "OK")

                                val headers = mutableMapOf<String, String>()
                                connection.headerFields?.forEach { (k, v) ->
                                    if (k == null || v.isEmpty()) return@forEach
                                    val lower = k.lowercase()
                                    // Same filter as the localhost branch:
                                    // Content-Encoding/Length and hop-by-hop
                                    // headers describe the upstream
                                    // connection, not the WebView one. CDNs
                                    // commonly gzip; without this filter
                                    // WebView re-decodes an already-decoded
                                    // stream and the resource fails to
                                    // render.
                                    if (lower == "content-encoding" ||
                                        lower == "content-length" ||
                                        lower == "transfer-encoding" ||
                                        lower == "connection" ||
                                        lower == "keep-alive" ||
                                        lower == "proxy-authenticate" ||
                                        lower == "trailer" ||
                                        lower == "te" ||
                                        lower == "upgrade") return@forEach
                                    headers[k] = v.last()
                                }
                                headers["Access-Control-Allow-Origin"] = "*"
                                response.responseHeaders = headers

                                // Promote this strategy. Subsequent CDN
                                // requests on the same page will hit it
                                // first and skip the cascade.
                                lastWorkingCdnStrategy = id
                                lastCdnStrategyTimestamp = now

                                android.util.Log.d("WebViewProxy", "CDN OK via ${proxy.type()} (id=$id): $url")
                                return response
                            } catch (e: Exception) {
                                android.util.Log.w("WebViewProxy", "CDN ${proxy.type()} (id=$id) failed for $url: ${e.message}")
                                try { connection?.disconnect() } catch (_: Exception) {}
                                // Try next strategy
                            }
                        }

                        // Exhausted — demote sticky so the next request
                        // re-probes from scratch instead of retrying the
                        // same dead path.
                        if (sticky != 0) lastWorkingCdnStrategy = 0

                        // Fail FAST with a synthetic 504 instead of letting
                        // WebView fall back to the system network stack.
                        // Falling through here is what stalls a parser-
                        // blocking <script src="https://cdn.jsdelivr.net/...">
                        // for tens of seconds on bad mobile data: WebView
                        // re-runs the same proxy path we just exhausted.
                        // 504 surfaces a real failure to the parser so the
                        // page continues rendering without that resource.
                        android.util.Log.e("WebViewProxy", "All CDN strategies failed for: $url — returning 504")
                        return synthesize504("CDN unreachable")
                    }

                    // LOGGING: Check if we are seeing the video request
                    if (url.endsWith(".mp4")) {
                        android.util.Log.d("WebViewProxy", "Intercepting Video: $url")
                    }

                    try {
                        // 1. Force Direct Connection (Bypass sing-box)
                        val connection = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 30000
                        // Don't auto-follow 3xx — AuthMiddleware emits a 302
                        // → /login.html for unauthenticated page requests, and
                        // we need WebView to see that redirect so the address
                        // bar / history reflects the login page. With auto-
                        // follow, WebView sees a 200 from /login.html under
                        // the *original* URL, breaking back navigation.
                        connection.instanceFollowRedirects = false

                        // Preserve the verb. HttpURLConnection defaults to GET,
                        // and WebResourceRequest does not expose a POST body, so
                        // mutating calls with a body still go through
                        // AndroidBridge. Empty-body POSTs (live-view stream
                        // enable, quality) must keep POST — otherwise they 404
                        // against POST-only daemon routes.
                        val method = request.method?.uppercase() ?: "GET"
                        connection.requestMethod = method
                        if (method == "POST" || method == "PUT"
                            || method == "PATCH" || method == "DELETE") {
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(0)
                        }

                        // 2. Forward Range Header (VITAL for video)
                        // Chrome sends "Range: bytes=0-" to start playback
                        val range = request.requestHeaders["Range"]
                        if (range != null) {
                            connection.setRequestProperty("Range", range)
                            android.util.Log.d("WebViewProxy", "Request Range: $range")
                        }

                        // Forward remaining request headers, but strip
                        // conditional-GET headers (If-None-Match /
                        // If-Modified-Since). Reason: shouldInterceptRequest's
                        // synthetic responses don't merge with WebView's HTTP
                        // cache the way real network responses do, so a 304
                        // from the daemon returns to WebView as "200 OK with
                        // empty body" and breaks the video element. By
                        // forcing the daemon to always 200, we let
                        // Cache-Control: max-age + immutable on the daemon's
                        // response satisfy repeat playback from cache without
                        // revalidation. ETag is still useful for external
                        // tunnel clients that bypass this intercept.
                        request.requestHeaders?.forEach { (key, value) ->
                            if (key.equals("Range", ignoreCase = true)) return@forEach
                            if (key.equals("If-None-Match", ignoreCase = true)) return@forEach
                            if (key.equals("If-Modified-Since", ignoreCase = true)) return@forEach
                            connection.setRequestProperty(key, value)
                        }

                        // Inject Auth
                        val jwt = getAuthJwt()
                        if (jwt != null) {
                            connection.setRequestProperty("Cookie", "byd_session=$jwt")
                        }

                        if (connection.doOutput) {
                            connection.outputStream.use { /* empty body */ }
                        }

                        connection.connect()

                        // 3. Handle Response
                        val stream = if (connection.responseCode in 200..399) {
                            connection.inputStream
                        } else {
                            // 4xx/5xx — prefer the server's error body, but if
                            // there is none, surface a synthetic 503 instead of
                            // returning null. Returning null falls back to the
                            // system proxy (sing-box) for localhost, which
                            // hangs without firing onReceivedError.
                            connection.errorStream
                                ?: return synthesize503(
                                    "HTTP ${connection.responseCode} (no error body)"
                                )
                        }
                        val length = connection.contentLength

                        // SOTA FIX: Determine correct MIME type
                        // 1. Force video/mp4 for .mp4 files (Chrome 74 fails on application/octet-stream)
                        // 2. For other files, parse Content-Type header properly (strip charset)
                        // 3. Fallback to application/octet-stream (not video/mp4!) for unknown types
                        val rawContentType = connection.contentType ?: "application/octet-stream"
                        var mime = rawContentType.split(";").first().trim()
                        // Encoding ONLY for text MIME types. WebResourceResponse
                        // treats this as a charset hint; passing "utf-8" for a
                        // binary image/video stream makes some Android 7.x
                        // WebViews try to decode the bytes as UTF-8 text and
                        // corrupt the data (the deterrent preview <img> not
                        // rendering on this firmware was caused by this).
                        val isTextMime = mime.startsWith("text/")
                            || mime == "application/json"
                            || mime == "application/javascript"
                            || mime == "application/xml"
                            || mime == "image/svg+xml"
                        val encoding = when {
                            rawContentType.contains("charset=") ->
                                rawContentType.substringAfter("charset=").trim()
                            isTextMime -> "utf-8"
                            else -> null
                        }
                        if (url.endsWith(".mp4")) mime = "video/mp4"

                        val response = WebResourceResponse(mime, encoding, stream)

                        // 4. Pass Status Code (206 vs 200)
                        response.setStatusCodeAndReasonPhrase(
                            connection.responseCode,
                            connection.responseMessage ?: "OK"
                        )

                        // 5. Force Response Headers
                        val headers = mutableMapOf<String, String>()
                        // Copy server headers, but drop:
                        //   - Content-Encoding: HttpURLConnection transparently
                        //     gunzips, so the stream we hand to WebView is
                        //     already decoded — leaving the header would tell
                        //     WebView to decode again and fail.
                        //   - Content-Length: keyed off the server's
                        //     (possibly compressed) length; we recompute
                        //     below from connection.contentLength which
                        //     reflects the post-decompression stream when
                        //     the server didn't gzip, and the manual
                        //     override is safer than a stale value.
                        //   - Hop-by-hop headers: Transfer-Encoding /
                        //     Connection / Keep-Alive / Proxy-Authenticate /
                        //     Trailer / TE / Upgrade. These describe the
                        //     server↔intercept connection, not the
                        //     intercept↔WebView one.
                        connection.headerFields?.forEach { (k, v) ->
                            if (k == null || v.isEmpty()) return@forEach
                            val lower = k.lowercase()
                            if (lower == "content-encoding" ||
                                lower == "content-length" ||
                                lower == "transfer-encoding" ||
                                lower == "connection" ||
                                lower == "keep-alive" ||
                                lower == "proxy-authenticate" ||
                                lower == "trailer" ||
                                lower == "te" ||
                                lower == "upgrade") return@forEach
                            headers[k] = v.last()
                        }
                        // MANUAL OVERRIDES: Ensure these exist even if server forgot them
                        headers["Access-Control-Allow-Origin"] = "*"
                        headers["Accept-Ranges"] = "bytes"  // Tells player "You can seek"
                        if (length > 0) {
                            headers["Content-Length"] = length.toString()
                        }
                        response.responseHeaders = headers

                        if (url.endsWith(".mp4")) {
                            android.util.Log.d("WebViewProxy", "Serving Video: ${connection.responseCode} len=$length mime=$mime")
                            // Log all response headers for debugging
                            headers.forEach { (k, v) ->
                                android.util.Log.d("WebViewProxy", "  Header: $k = $v")
                            }
                        }

                        return response
                    } catch (e: Exception) {
                        android.util.Log.e("WebViewProxy", "Failed: $url - ${e.message}")
                        // CRITICAL: never return null for a 127.0.0.1 URL.
                        // Returning null tells WebView "fetch this yourself" —
                        // and WebView's default path goes through the system
                        // HTTP proxy (sing-box on :8119), which has no route
                        // back to localhost. The request hangs without ever
                        // firing onReceivedError, so the loading overlay
                        // stays up forever. Synthesize a 503 instead so the
                        // WebView treats it as a real error and fires
                        // onReceivedError → showError() → user sees the retry
                        // button.
                        return synthesize503(e.message ?: "connection failed")
                    }
                }

                /**
                 * Build a 503 WebResourceResponse to return when a localhost
                 * fetch fails. Surfacing the failure to WebView lets
                 * onReceivedError fire and the user reach the retry overlay,
                 * instead of hanging on the system proxy fallback.
                 */
                private fun synthesize503(reason: String): WebResourceResponse {
                    val body = "{\"error\":\"daemon_unreachable\",\"reason\":${
                        org.json.JSONObject.quote(reason)
                    }}"
                    val stream = java.io.ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                    val resp = WebResourceResponse("application/json", "utf-8", stream)
                    resp.setStatusCodeAndReasonPhrase(503, "Service Unavailable")
                    resp.responseHeaders = mapOf(
                        "Cache-Control" to "no-store",
                        "Connection" to "close"
                    )
                    return resp
                }

                /**
                 * Build a 504 WebResourceResponse for a CDN/external
                 * resource the head unit can't reach. Returning this
                 * (instead of letting WebView retry via the system
                 * network stack) makes parser-blocking <script>/<link>
                 * tags fail FAST on bad mobile data so the page can
                 * continue rendering. Body is empty so a <script> tag
                 * doesn't try to evaluate non-JS as JS.
                 */
                private fun synthesize504(reason: String): WebResourceResponse {
                    val stream = java.io.ByteArrayInputStream(ByteArray(0))
                    val resp = WebResourceResponse("text/plain", "utf-8", stream)
                    resp.setStatusCodeAndReasonPhrase(504, "Gateway Timeout")
                    resp.responseHeaders = mapOf(
                        "Cache-Control" to "no-store",
                        "Connection" to "close",
                        "X-Overdrive-Fail-Reason" to reason
                    )
                    return resp
                }
                
                override fun onPageStarted(
                    view: WebView?, url: String?, favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    // Clear the failure flag at the START of every navigation so a
                    // stale `pageLoadFailed=true` from a previous error can't
                    // suppress showContent() on the retry's onPageFinished. The
                    // flag stays cleared unless onReceivedError fires for *this*
                    // load.
                    pageLoadFailed = false
                    loadInProgress = true
                    showLoading()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    loadInProgress = false
                    if (!pageLoadFailed) {
                        showContent()
                        // Theme: tag <html data-theme="…"> BEFORE INJECT_JS so any
                        // CSS that depends on the variable values uses the right
                        // values on first paint.
                        view?.evaluateJavascript(buildThemeInjectJs(), null)
                        // Hide sidebar (app drawer handles navigation) and
                        // patch fetch() to bypass proxy for ALL localhost calls
                        view?.evaluateJavascript(INJECT_JS, null)
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        pageLoadFailed = true
                        loadInProgress = false
                        showError()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    
                    // Intercept events page links — use native RecordingLibraryFragment
                    // which has reliable video playback via VideoView
                    if (url.contains("/events.html") || url.endsWith("/events")) {
                        try {
                            // Extract filter / file params if present
                            // (e.g., events.html?filter=sentry&file=event_20260512_143022.mp4)
                            val uri = android.net.Uri.parse(url)
                            val filter = uri.getQueryParameter("filter")
                            val file = uri.getQueryParameter("file")
                            // A `file=` deep link (from a notification or the Log tab)
                            // means "open THIS clip", not "show the list". The native
                            // recordings fragment ignores the file arg, so resolve the
                            // filename to its path and jump straight to the player.
                            // Falls back to the recordings list if it can't be resolved
                            // (deleted, still recording, or the daemon is warming up).
                            if (!file.isNullOrBlank()) {
                                openEventByFilename(file, filter)
                            } else {
                                val bundle = android.os.Bundle().apply {
                                    if (filter != null) putString("filter", filter)
                                }
                                androidx.navigation.fragment.NavHostFragment.findNavController(this@WebViewFragment)
                                    .navigate(R.id.recordingsFragment, bundle)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("WebView", "Failed to navigate to events: ${e.message}")
                        }
                        return true
                    }
                    
                    if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
                        return false
                    }
                    try {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (_: Exception) {
                        // No browser/ACTION_VIEW handler on this head unit (common on
                        // locked-down automotive ROMs). Don't fail silently — copy the
                        // URL to the clipboard and tell the user, so a BYOK signup link
                        // is still reachable (paste it on a phone).
                        try {
                            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                            android.widget.Toast.makeText(requireContext(),
                                getString(R.string.url_copied_no_browser, url),
                                android.widget.Toast.LENGTH_LONG).show()
                        } catch (_: Exception) {}
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(
                    consoleMessage: android.webkit.ConsoleMessage?
                ): Boolean {
                    consoleMessage?.let {
                        val level = when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> android.util.Log.ERROR
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> android.util.Log.WARN
                            android.webkit.ConsoleMessage.MessageLevel.DEBUG -> android.util.Log.DEBUG
                            else -> android.util.Log.INFO
                        }
                        android.util.Log.println(
                            level,
                            "WebViewJS",
                            "${it.sourceId()?.substringAfterLast('/')}:${it.lineNumber()} — ${it.message()}"
                        )
                    }
                    return true
                }

                /**
                 * Bridge <input type="file"> clicks into the Android photo picker.
                 * Without this, the default WebChromeClient ignores the click and
                 * nothing happens (e.g. file upload buttons in the web UI).
                 */
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // Release any stale callback from a previous, unfinished request
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback

                    // GetContent takes a single MIME type. Collapse the <input
                    // accept="image/png,image/jpeg,..."> list to "image/*" since
                    // a wildcard is the safest pick on Android 7.1 head-unit
                    // gallery apps that don't recognise specific image subtypes.
                    // If the page asks for something other than images entirely,
                    // honor that prefix; otherwise default to images.
                    val accept = fileChooserParams?.acceptTypes
                        ?.firstOrNull { it.isNotBlank() }
                        ?.let { type ->
                            when {
                                type.startsWith("image/") -> "image/*"
                                type.startsWith("video/") -> "video/*"
                                type.startsWith("audio/") -> "audio/*"
                                type == "*/*" -> "*/*"
                                else -> type
                            }
                        } ?: "image/*"

                    return try {
                        fileChooserLauncher.launch(accept)
                        true
                    } catch (e: Exception) {
                        android.util.Log.e("WebView", "File chooser launch failed: ${e.message}")
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = null
                        false
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null
                            || !isTrustedAssistantAudioRequest(request)) {
                        request?.deny()
                        return
                    }
                    if (ContextCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        ))
                        return
                    }
                    pendingAudioPermissionRequest?.deny()
                    pendingAudioPermissionRequest = request
                    audioPermissionLauncher.launch(
                        Manifest.permission.RECORD_AUDIO
                    )
                }
            }

            // Native bridge for direct HTTP calls bypassing proxy
            addJavascriptInterface(ProxyBypassBridge(), "AndroidBridge")
        }
    }

    private fun isTrustedAssistantAudioRequest(
        request: PermissionRequest
    ): Boolean {
        val resources = request.resources ?: return false
        if (!resources.contains(
                PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            return false
        }
        val origin = request.origin ?: return false
        if (origin.host != "127.0.0.1"
                && origin.host != "localhost") {
            return false
        }
        val page = Uri.parse(webView?.url ?: currentUrl ?: return false)
        return (page.host == "127.0.0.1"
                || page.host == "localhost")
                && (page.path == "/assistant"
                || page.path == "/assistant.html")
    }

    /**
     * Resolve a recording filename (from a notification / Log-tab deep link) to
     * its on-disk path and open it directly in the native video player. The
     * scan does a synchronous HTTP fetch to the daemon, so it runs off the UI
     * thread; navigation is posted back to the main thread.
     *
     * Falls back to the recordings LIST (pre-selecting the type tab via
     * `filter`) when the clip can't be resolved — deleted, still being written
     * (`.mp4.tmp`), or the daemon index is still warming up. This preserves the
     * old behavior for those edge cases instead of dead-ending on a black
     * player.
     */
    private fun openEventByFilename(filename: String, filter: String?) {
        // Single-flight: ignore re-taps while a resolve is already running.
        if (deepLinkInFlight) return
        // Capture the activity ON THE UI THREAD (shouldOverrideUrlLoading runs
        // here) — never read the Fragment.activity field from the worker.
        val act = activity ?: return
        val appCtx = act.applicationContext
        deepLinkInFlight = true
        Thread({
            var match: com.overdrive.app.ui.model.RecordingFile? = null
            try {
                // One bounded re-scan: a freshly-fired notification can arrive
                // before the daemon has indexed the clip (scan returns empty
                // during warmup). Retry a couple of times before falling back,
                // mirroring how events.js polls inflight.
                var attempts = 0
                while (attempts < 3) {
                    val all = com.overdrive.app.ui.util.RecordingScanner.scanRecordings(appCtx)
                    match = all.firstOrNull { it.file.name == filename }
                    if (match != null || attempts == 2) break
                    attempts++
                    try { Thread.sleep(1200) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt(); break
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WebView", "openEventByFilename scan failed: ${e.message}")
            }
            val resolved = match
            act.runOnUiThread {
                try {
                    // Guard against the fragment being torn down while we scanned.
                    if (!isAdded || isDetached || view == null) return@runOnUiThread
                    val nav = androidx.navigation.fragment.NavHostFragment
                        .findNavController(this@WebViewFragment)
                    if (resolved != null) {
                        val bundle = android.os.Bundle().apply {
                            putString(
                                com.overdrive.app.ui.fragment.VideoPlayerFragment.ARG_VIDEO_PATH,
                                resolved.file.absolutePath
                            )
                            putString(
                                com.overdrive.app.ui.fragment.VideoPlayerFragment.ARG_VIDEO_TITLE,
                                resolved.file.name
                            )
                        }
                        nav.navigate(R.id.action_global_videoPlayer, bundle)
                    } else {
                        // Unresolved (deleted / still recording / daemon warming)
                        // — open the list, pre-selecting the type tab via `filter`.
                        val bundle = android.os.Bundle().apply {
                            if (filter != null) putString("filter", filter)
                        }
                        nav.navigate(R.id.recordingsFragment, bundle)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebView", "openEventByFilename navigate failed: ${e.message}")
                } finally {
                    deepLinkInFlight = false
                }
            }
        }, "OpenEventDeepLink").start()
    }

    /**
     * Native bridge that JavaScript calls for ALL HTTP requests.
     * Uses Proxy.NO_PROXY to bypass sing-box.
     * This is synchronous — called from JS via AndroidBridge.httpRequest().
     */
    inner class ProxyBypassBridge {

        /**
         * Expose the active app theme ("dark" / "light") so theme.js can
         * stamp <html data-theme="…"> at script-include time, BEFORE any
         * stylesheet evaluates. Without this the page paints once with the
         * default-dark palette and then re-paints when onPageFinished
         * injects the theme — visible flash on light-mode devices.
         *
         * Reads UI_MODE_NIGHT_MASK so it tracks the same source the rest of
         * the Android shell uses (PreferencesManager.setThemeMode →
         * AppCompatDelegate.setDefaultNightMode → activity Configuration).
         */
        /**
         * Expose the active app locale (e.g. "en", "de", "zh-CN") so theme.js's
         * sibling i18n bootstrap can stamp the right language at script-include
         * time inside the Android WebView. The web-side picker is suppressed
         * in-app — the app's Settings → Language panel is the source of truth.
         * External tunnel/browser users get their own localStorage-backed
         * picker via the lang-picker.js sheet.
         *
         * Reads LocaleManager.get() which is the same source the rest of the
         * server uses (it's what /status reports as `locale`). On any error
         * (e.g. very early boot before the file is written) returns "en" so
         * the page still loads.
         */
        @android.webkit.JavascriptInterface
        fun getAppLocale(): String {
            return try {
                com.overdrive.app.server.LocaleManager.get()
            } catch (e: Exception) {
                "en"
            }
        }

        /**
         * Arm/disarm the NATIVE blind-spot lane to match the just-saved
         * blindspot.enabled (or debugPreview) flag, without waiting for the next
         * activity onResume. The RoadSense web tab calls this after toggling so the
         * daemon reacts instantly. The visual is a daemon-owned SurfaceControl layer
         * (NO app-process overlay → NO SYSTEM_ALERT_WINDOW permission needed); this
         * just POSTs the daemon control surface via BlindSpotControl.
         */
        @android.webkit.JavascriptInterface
        fun syncBlindSpotOverlay(): String {
            try {
                val ctx = context ?: return "no_context"
                com.overdrive.app.roadsense.overlay.BlindSpotControl.sync(ctx)
                return "ok"
            } catch (e: Exception) {
                android.util.Log.w("WebViewFragment", "syncBlindSpotOverlay bridge failed: ${e.message}")
                return "error"
            }
        }

        /**
         * Start/stop the app-side RoadSense floating overlay to match the just-saved
         * roadSense.overlayVisible (and master enabled) flags, without waiting for the
         * next Activity onResume. The RoadSense web tab calls this after toggling the
         * "Show overlay" switch so the pill/card appears or disappears instantly.
         * Mirrors syncBlindSpotOverlay; uses overlayShouldShow() so hiding the overlay
         * never touches detection/audio/crowdsource (all daemon-side). forceReload so the
         * value we just wrote (app UID) is read back fresh. Posts to the UI thread —
         * start/stopService must run on the main looper, and bridge calls arrive on a
         * WebView worker thread.
         */
        @android.webkit.JavascriptInterface
        fun syncRoadSenseOverlay(): String {
            return try {
                val ctx = context ?: return "no_context"
                // Read here (WebView worker): snapshot(forceReload) hits disk.
                val shouldShow = com.overdrive.app.roadsense.config.RoadSenseConfig
                    .snapshot(forceReload = true).overlayShouldShow()
                ctx.mainExecutor.execute {
                    try {
                        com.overdrive.app.roadsense.overlay.RoadSenseOverlayService
                            .syncWithConfig(ctx, shouldShow)
                    } catch (e: Exception) {
                        android.util.Log.w("WebViewFragment", "syncRoadSenseOverlay apply failed: ${e.message}")
                    }
                }
                "ok"
            } catch (e: Exception) {
                android.util.Log.w("WebViewFragment", "syncRoadSenseOverlay bridge failed: ${e.message}")
                "error"
            }
        }

        /**
         * Launch the native RoadSense hazard map (GPU MapLibre Activity). Called
         * from the RoadSense web tab's "View Hazard Map" action. The map can't be
         * a WebView page — MapLibre GL needs WebGL2/modern Chrome the head-unit
         * WebView (Chrome 58) lacks — so it's a dedicated native Activity. Posted
         * to the UI thread because JS bridge calls arrive on a WebView worker
         * thread, and startActivity must run on the main looper.
         */
        @android.webkit.JavascriptInterface
        fun openHazardMap(): String {
            return try {
                val act = activity ?: return "no_context"
                act.runOnUiThread {
                    try {
                        act.startActivity(
                            android.content.Intent(act, com.overdrive.app.navmap.RoadSenseMapActivity::class.java)
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("WebViewFragment", "openHazardMap launch failed: ${e.message}")
                    }
                }
                "ok"
            } catch (e: Exception) {
                android.util.Log.w("WebViewFragment", "openHazardMap bridge failed: ${e.message}")
                "error"
            }
        }

        /**
         * Key Mapping capture toggle. The capture box calls this with true when
         * "Capture a key" is armed and false when it disarms. While armed, the
         * native AccessibilityService (KeyMapDispatcher) forwards the next
         * hardware keycode into the page (window.KM.onNativeKey) and consumes it,
         * instead of running the normal mapping — so a physical button that never
         * reaches the WebView DOM can still be captured.
         */
        @android.webkit.JavascriptInterface
        fun setKeyCapture(armed: Boolean) {
            setCaptureArmed(armed)
        }

        @android.webkit.JavascriptInterface
        fun getAppTheme(): String {
            // Source-of-truth ordering — try the strongest signal first:
            //   1. PreferencesManager (the user's explicit pick: AUTO / NO / YES)
            //      AUTO falls through to step 2.
            //   2. AppCompatDelegate.getDefaultNightMode() — the active runtime
            //      mode after setDefaultNightMode() is called. This is what
            //      every Material widget actually uses, and stays correct
            //      across activity recreates.
            //   3. Configuration.uiMode — last-resort fallback for very early
            //      paint moments where AppCompatDelegate hasn't propagated yet.
            //
            // The previous implementation only read step 3 which is the
            // SYSTEM uiMode and ignored AppCompat overrides — picking Light
            // in the app left this returning "dark" until the OS itself
            // flipped, which is exactly the bug the user reported.
            try {
                val pref = com.overdrive.app.ui.util.PreferencesManager.getThemeMode()
                when (pref) {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> return "light"
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> return "dark"
                }
            } catch (ignored: Exception) {
                // PreferencesManager not initialized (very early boot) —
                // fall through to the next signal.
            }
            try {
                val mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
                if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return "light"
                if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return "dark"
            } catch (ignored: Exception) { /* fall through */ }

            val ctx = context ?: return "dark"
            val isNight = (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (isNight) "dark" else "light"
        }

        @android.webkit.JavascriptInterface
        fun httpRequest(urlStr: String, method: String, body: String, headers: String): String {
            var conn: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL(urlStr)
                conn = url.openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                conn.requestMethod = method
                val isBydCloudApi = url.path.startsWith("/api/bydcloud")
                val isGenAiApi = url.path.startsWith("/api/genai/")
                val isStreamEnable = url.path == "/api/stream/enable"
                conn.connectTimeout = if (isBydCloudApi) 10000 else 8000
                conn.readTimeout = when {
                    isGenAiApi -> 130000
                    isBydCloudApi -> 60000
                    // GL encoder init while 4 cams are recording can exceed
                    // the default 10s; the in-app Live View then toasted
                    // enable_failed even though Tailscale (no bridge timeout)
                    // succeeded.
                    isStreamEnable -> 30000
                    else -> 10000
                }
                conn.instanceFollowRedirects = false

                // Parse and set headers
                if (headers.isNotEmpty()) {
                    try {
                        val hJson = org.json.JSONObject(headers)
                        val keys = hJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            conn.setRequestProperty(k, hJson.getString(k))
                        }
                    } catch (_: Exception) {}
                }

                // Always inject auth cookie
                val jwt = getAuthJwt()
                if (jwt != null) {
                    val existing = conn.getRequestProperty("Cookie") ?: ""
                    if (!existing.contains("byd_session")) {
                        val cookie = if (existing.isNotEmpty()) "$existing; byd_session=$jwt" else "byd_session=$jwt"
                        conn.setRequestProperty("Cookie", cookie)
                    }
                }

                // Empty-body POST/PUT/DELETE still needs doOutput, otherwise
                // some HttpURLConnection builds omit the verb or Content-Length
                // and the daemon 404s the route.
                val mutating = method == "POST" || method == "PUT"
                    || method == "PATCH" || method == "DELETE"
                if (mutating) {
                    conn.doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { out ->
                        if (bytes.isNotEmpty()) out.write(bytes)
                    }
                }

                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: return "{\"_status\":$code}")
                val responseBody = stream.bufferedReader(Charsets.UTF_8).readText()
                stream.close()

                // Wrap response with status code so JS can check it
                return if (responseBody.startsWith("{") || responseBody.startsWith("[")) {
                    // JSON response — inject status
                    if (responseBody.startsWith("{")) {
                        "{\"_status\":$code," + responseBody.substring(1)
                    } else {
                        "{\"_status\":$code,\"data\":$responseBody}"
                    }
                } else {
                    "{\"_status\":$code,\"body\":${org.json.JSONObject.quote(responseBody)}}"
                }
            } catch (e: Exception) {
                android.util.Log.e("WebViewBridge", "Request failed: $method $urlStr — ${e.message}")
                return "{\"_status\":0,\"error\":\"${e.message?.replace("\"", "'") ?: "unknown"}\"}"
            } finally {
                conn?.disconnect()
            }
        }

        /**
         * Long GenAI calls must not block Chrome 58's JavaScript thread. The
         * normal write bridge is synchronous, so the Assistant uses this
         * narrowly-scoped asynchronous variant and receives one quoted callback.
         */
        @android.webkit.JavascriptInterface
        fun httpRequestAsync(
            urlStr: String,
            method: String,
            body: String,
            headers: String,
            callbackId: String
        ) {
            if (!callbackId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
            val allowed = try {
                val url = java.net.URL(urlStr)
                (url.host == "127.0.0.1" || url.host == "localhost") &&
                    (url.port == -1 || url.port == CameraDaemon.HTTP_PORT) &&
                    url.path.startsWith("/api/genai/")
            } catch (_: Exception) {
                false
            }
            if (!allowed) {
                deliverAsyncHttpResponse(
                    callbackId,
                    "{\"_status\":403,\"error\":\"Request not allowed\"}"
                )
                return
            }
            Thread({
                deliverAsyncHttpResponse(
                    callbackId,
                    httpRequest(urlStr, method, body, headers)
                )
            }, "GenAiWebRequest").apply {
                isDaemon = true
                start()
            }
        }

        private fun deliverAsyncHttpResponse(callbackId: String, raw: String) {
            val script = "window.GenAI&&GenAI.onNativeResponse(" +
                org.json.JSONObject.quote(callbackId) + "," +
                org.json.JSONObject.quote(raw) + ");"
            webView?.post {
                try {
                    webView?.evaluateJavascript(script, null)
                } catch (_: Throwable) {
                    // Page was closed while the provider request was finishing.
                }
            }
        }
    }

    // ==================== AUTH ====================

    private var cachedJwt: String? = null
    private var jwtExpiry: Long = 0
    // Pin the WebView cookie's JWT to the AuthManager state version it was
    // minted from — the cookie travels through CookieManager which has its
    // own persistence, so a stale JWT here outlives the in-process cache.
    private var cachedJwtStateVersion: Long = -1

    private fun getAuthJwt(): String? {
        // Cache JWT for 5 minutes to avoid spamming auth state saves
        val now = System.currentTimeMillis()
        val curVersion = com.overdrive.app.auth.AuthManager.getStateVersion()
        if (cachedJwt != null && now < jwtExpiry && cachedJwtStateVersion == curVersion) return cachedJwt
        
        return try {
            // Try to initialize AuthManager if not already done
            if (com.overdrive.app.auth.AuthManager.getState() == null) {
                com.overdrive.app.auth.AuthManager.initialize()
            }
            
            var jwt = com.overdrive.app.auth.AuthManager.generateJwt()
            
            // If JWT generation failed, try reading auth state directly from daemon's file
            if (jwt == null) {
                android.util.Log.w("WebView", "JWT generation failed, retrying with fresh init...")
                com.overdrive.app.auth.AuthManager.initialize()
                jwt = com.overdrive.app.auth.AuthManager.generateJwt()
            }
            
            if (jwt != null) {
                cachedJwt = jwt
                jwtExpiry = now + 5 * 60 * 1000  // 5 min cache
                cachedJwtStateVersion = com.overdrive.app.auth.AuthManager.getStateVersion()
                android.util.Log.d("WebView", "JWT generated successfully")
            } else {
                android.util.Log.e("WebView", "JWT generation failed after retry")
            }
            jwt
        } catch (e: Exception) {
            android.util.Log.e("WebView", "JWT failed: ${e.message}")
            null
        }
    }

    private fun injectAuthCookie() {
        injectAuthCookie(attempt = 0)
    }

    private fun injectAuthCookie(attempt: Int) {
        try {
            val jwt = getAuthJwt()
            if (jwt != null) {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie("http://127.0.0.1:${CameraDaemon.HTTP_PORT}", "byd_session=$jwt; Path=/; Max-Age=31536000")
                cm.flush()
                android.util.Log.d("WebView", "Auth cookie set${if (attempt > 0) " (attempt ${attempt + 1})" else ""}")
                if (attempt > 0 && !authReloadFired) {
                    // Auth came online after the page already tried to load.
                    // Only fire the reload once, and only if no other load is
                    // currently in flight — overlapping loadUrl + reload on
                    // Chrome 58 WebView can swallow onPageFinished and leave
                    // the loading overlay stuck forever.
                    if (loadInProgress) {
                        // A load is happening right now (probably the initial
                        // one from loadPage()). Re-poll in a moment so we
                        // reload once it settles, instead of racing it.
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            injectAuthCookie(attempt)
                        }, 500)
                        return
                    }
                    authReloadFired = true
                    webView?.reload()
                }
            } else if (attempt < 10) {
                // Auth not ready yet — daemon may still be writing the
                // unified config. Poll up to ~10s. Each retry re-mints
                // the JWT, so we'll pick up the daemon's secret as soon
                // as it lands in the unified config.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    injectAuthCookie(attempt + 1)
                }, 1000)
            } else {
                android.util.Log.e("WebView", "Auth cookie retry exhausted after 10 attempts")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebView", "Cookie inject failed: ${e.message}")
        }
    }

    private fun loadPage() {
        pageLoadFailed = false
        showLoading()
        currentUrl?.let { webView?.loadUrl(it) }
    }

    private fun retryLoad() {
        injectAuthCookie()
        loadPage()
    }

    private fun showLoading() {
        loadingOverlay?.alpha = 1f
        loadingOverlay?.visibility = View.VISIBLE
        errorOverlay?.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    private fun showContent() {
        loadingOverlay?.animate()?.alpha(0f)?.setDuration(200)
            ?.withEndAction { loadingOverlay?.visibility = View.GONE }?.start()
        errorOverlay?.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    private fun showError() {
        loadingOverlay?.visibility = View.GONE
        errorOverlay?.visibility = View.VISIBLE
        webView?.visibility = View.INVISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUrl?.let { outState.putString(KEY_SAVED_URL, it) }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (pageLoadFailed) retryLoad()
        // Re-apply theme on resume so a user toggle while the page was
        // backgrounded is reflected without requiring a full reload.
        webView?.let { it.evaluateJavascript(buildThemeInjectJs(), null) }
        // Android WebView versions used by older BYD head units do not always
        // deliver a reliable visible-side Page Visibility event. Give pages an
        // explicit lifecycle signal as well; stream.js deduplicates this with
        // visibilitychange so only one decoder/socket is created.
        webView?.evaluateJavascript(
            "(function(){if(window.BYD&&BYD.stream&&BYD.stream.resumeAfterBackground){" +
                "BYD.stream.resumeAfterBackground(true);}})();",
            null
        )
    }

    /**
     * Resolve the active theme's `colorBackground` so the WebView's outer
     * chrome (visible during page loads or where the page itself is
     * transparent) matches whatever light/dark mode the activity is in.
     */
    private fun resolveThemeBackground(): Int {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(
                android.R.attr.colorBackground, tv, true
            )
        ) tv.data else android.graphics.Color.BLACK
    }

    /**
     * Build the JS snippet that tags <html data-theme="dark|light"> based on
     * the current activity night-mode configuration. Called both on every
     * page-finished and on resume so theme switches propagate without a reload.
     *
     * Reads PreferencesManager → AppCompatDelegate → resources.uiMode in that
     * order so an explicit Light/Dark pick wins over the OS-level uiMode
     * (which lags behind setDefaultNightMode for a frame or two and was the
     * source of the "Light app, dark WebView" report).
     */
    private fun buildThemeInjectJs(): String {
        val theme = resolveActiveTheme()
        return "document.documentElement.setAttribute('data-theme','$theme');"
    }

    private fun resolveActiveTheme(): String {
        try {
            val pref = com.overdrive.app.ui.util.PreferencesManager.getThemeMode()
            when (pref) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> return "light"
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> return "dark"
            }
        } catch (ignored: Exception) { /* fall through */ }
        try {
            val mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return "light"
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return "dark"
        } catch (ignored: Exception) { /* fall through */ }
        val isNight = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (isNight) "dark" else "light"
    }

    /**
     * Apply a theme to this WebView immediately. The Settings theme picker
     * calls this on every visible WebView fragment so the switch is instant
     * (no activity recreate, no page reload).
     */
    fun applyTheme(theme: String) {
        val safe = if (theme == "light") "light" else "dark"
        webView?.evaluateJavascript(
            "document.documentElement.setAttribute('data-theme','$safe');",
            null
        )
    }

    /**
     * Apply a locale to this WebView immediately. The Android language
     * picker calls this on every visible WebView fragment so the switch is
     * instant (the activity recreate that follows AppCompatDelegate.set
     * ApplicationLocales would normally re-load the page, but on some
     * head-unit configurations the recreate is skipped — e.g. when the
     * fragment is offscreen — and the stale WebView keeps showing the
     * previous language until the user manually navigates away and back).
     *
     * Calls BYD.i18n.setLang() in the loaded page, which refetches the
     * catalog and re-hydrates every [data-i18n] node. Safe to call before
     * the page has finished loading — the call is no-op'd until BYD is
     * present.
     */
    fun applyLocale(lang: String) {
        if (lang.isBlank()) return
        // Sanitise to a JS string literal — only the BCP-47 alphabet is
        // valid here (a-zA-Z0-9 plus "-"). Drop everything else.
        val safe = lang.replace(Regex("[^a-zA-Z0-9-]"), "")
        if (safe.isEmpty()) return
        webView?.evaluateJavascript(
            "if (window.BYD && BYD.i18n && BYD.i18n.setLang) { BYD.i18n.setLang('$safe'); }",
            null
        )
    }

    override fun onPause() {
        // Disarm key-capture when the keymap page leaves the foreground.
        // captureArmed is a process-wide static that used to be cleared only in
        // onDestroyView — arming capture then leaving the app (HOME / app-switch)
        // left the a11y service (KeyMapDispatcher) consuming and swallowing every
        // system-wide hardware key until the WebView resumed and the JS self-heal
        // ran. Clearing it here bounds capture consumption to the foreground page.
        if (captureArmed) {
            captureArmed = false
            // Mirror the reset into the page so its capturing flag + capture-box
            // label reset too (best-effort — the page may already be gone). We
            // prefer an explicit external setter if the page exposes one; else we
            // fall back to toggling ONLY when the box is still armed so we never
            // accidentally RE-arm capture on a page that already disarmed itself.
            webView?.let { wv ->
                wv.post {
                    try {
                        wv.evaluateJavascript(
                            "(function(){try{" +
                                "if(window.KM&&window.KM.setCaptureExternally){window.KM.setCaptureExternally(false);return;}" +
                                "var b=document.getElementById('kmCaptureBox');" +
                                "if(b&&b.classList&&b.classList.contains('armed')&&window.KM&&window.KM.toggleCapture){window.KM.toggleCapture();}" +
                                "}catch(e){}})();",
                            null)
                    } catch (_: Throwable) { /* page navigated away */ }
                }
            }
        }
        // Native pause counterpart to the onResume resume signal: these head
        // units don't always deliver the Page Visibility event, and webView
        // .onPause() stops neither JS timers nor the socket.
        webView?.evaluateJavascript(
            "(function(){if(window.BYD&&BYD.stream&&BYD.stream.pauseForBackground){" +
                "BYD.stream.pauseForBackground();}})();",
            null
        )
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        // If a file picker is pending, release its callback so the WebView
        // doesn't hang onto a dangling handle.
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingAudioPermissionRequest?.deny()
        pendingAudioPermissionRequest = null

        webView?.let { wv ->
            // Stop any media playback and clear content before destroying
            wv.loadUrl("about:blank")
            wv.stopLoading()
            // Remove from parent to prevent leaked window
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        // Drop the capture-bridge ref if it points at this (now-destroyed) view.
        if (liveWebView === webView) liveWebView = null
        webView = null
        captureArmed = false
        super.onDestroyView()
    }
}
