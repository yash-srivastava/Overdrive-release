/* Shared Events video player markup. Loaded by events.html and parking.html. */
(function () {
    'use strict';
    if (document.getElementById('videoModal')) return;
    var markup = `    <!-- Video Modal -->
    <div class="video-modal" id="videoModal">
        <div class="video-container">
            <div class="video-header">
                <span class="video-title" id="videoTitle" data-i18n="events.video_title">Recording</span>
                <button class="video-close" onclick="BYD.events.closeVideo()" data-i18n-attr="title:common.close" title="Close">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"/>
                        <line x1="6" y1="6" x2="18" y2="18"/>
                    </svg>
                </button>
            </div>
            <div class="video-player" id="videoPlayerWrap">
                <!-- IMPORTANT: NO \`controls\` attribute on <video>. The native
                     control bar is painted as part of the video element, so a
                     transform: scale(2) (used for quadrant zoom) drags the
                     timestamp + native scrubber off-screen. We render our own
                     transport chrome below this surface (see .video-transport)
                     which lives outside the zooming subtree. -->
                <video id="videoPlayer" autoplay muted playsinline webkit-playsinline preload="auto"></video>
                <!-- Quadrant selector. The four AVM cameras are baked into a
                     single MP4 as a 2x2 mosaic (Front=TL, Right=TR, Rear=BL,
                     Left=BR by GpuMosaicRecorder convention). Tapping a
                     quadrant button applies a CSS transform that zooms 2x to
                     just that camera; tapping the active button or the All
                     button restores the full mosaic view. -->
                <div class="quadrant-bar" id="quadrantBar" role="group" data-i18n-attr="aria-label:events.quadrant_group" aria-label="Camera selector">
                    <button type="button" data-quadrant="all" class="active" data-i18n-attr="title:events.quadrant_all;aria-label:events.quadrant_all" title="Show all cameras" aria-label="Show all cameras">
                        <svg viewBox="0 0 24 24"><g class="q2x2"><rect class="qcell lit" x="3" y="3" width="8" height="8"/><rect class="qcell lit" x="13" y="3" width="8" height="8"/><rect class="qcell lit" x="3" y="13" width="8" height="8"/><rect class="qcell lit" x="13" y="13" width="8" height="8"/></g><g class="qdash"><rect class="qcell lit" x="3" y="3" width="18" height="9"/><rect class="qcell lit" x="3" y="14" width="5" height="7"/><rect class="qcell lit" x="9.5" y="14" width="5" height="7"/><rect class="qcell lit" x="16" y="14" width="5" height="7"/></g></svg>
                    </button>
                    <button type="button" data-quadrant="front" data-i18n-attr="title:events.quadrant_front;aria-label:events.quadrant_front" title="Show front camera" aria-label="Show front camera">
                        <svg viewBox="0 0 24 24"><g class="q2x2"><rect class="qcell lit" x="3" y="3" width="8" height="8"/><rect class="qcell" x="13" y="3" width="8" height="8"/><rect class="qcell" x="3" y="13" width="8" height="8"/><rect class="qcell" x="13" y="13" width="8" height="8"/></g><g class="qdash"><rect class="qcell lit" x="3" y="3" width="18" height="9"/><rect class="qcell" x="3" y="14" width="5" height="7"/><rect class="qcell" x="9.5" y="14" width="5" height="7"/><rect class="qcell" x="16" y="14" width="5" height="7"/></g></svg>
                    </button>
                    <button type="button" data-quadrant="right" data-i18n-attr="title:events.quadrant_right;aria-label:events.quadrant_right" title="Show right camera" aria-label="Show right camera">
                        <svg viewBox="0 0 24 24"><g class="q2x2"><rect class="qcell" x="3" y="3" width="8" height="8"/><rect class="qcell lit" x="13" y="3" width="8" height="8"/><rect class="qcell" x="3" y="13" width="8" height="8"/><rect class="qcell" x="13" y="13" width="8" height="8"/></g><g class="qdash"><rect class="qcell" x="3" y="3" width="18" height="9"/><rect class="qcell" x="3" y="14" width="5" height="7"/><rect class="qcell" x="9.5" y="14" width="5" height="7"/><rect class="qcell lit" x="16" y="14" width="5" height="7"/></g></svg>
                    </button>
                    <button type="button" data-quadrant="rear" data-i18n-attr="title:events.quadrant_rear;aria-label:events.quadrant_rear" title="Show rear camera" aria-label="Show rear camera">
                        <svg viewBox="0 0 24 24"><g class="q2x2"><rect class="qcell" x="3" y="3" width="8" height="8"/><rect class="qcell" x="13" y="3" width="8" height="8"/><rect class="qcell lit" x="3" y="13" width="8" height="8"/><rect class="qcell" x="13" y="13" width="8" height="8"/></g><g class="qdash"><rect class="qcell" x="3" y="3" width="18" height="9"/><rect class="qcell" x="3" y="14" width="5" height="7"/><rect class="qcell lit" x="9.5" y="14" width="5" height="7"/><rect class="qcell" x="16" y="14" width="5" height="7"/></g></svg>
                    </button>
                    <button type="button" data-quadrant="left" data-i18n-attr="title:events.quadrant_left;aria-label:events.quadrant_left" title="Show left camera" aria-label="Show left camera">
                        <svg viewBox="0 0 24 24"><g class="q2x2"><rect class="qcell" x="3" y="3" width="8" height="8"/><rect class="qcell" x="13" y="3" width="8" height="8"/><rect class="qcell" x="3" y="13" width="8" height="8"/><rect class="qcell lit" x="13" y="13" width="8" height="8"/></g><g class="qdash"><rect class="qcell" x="3" y="3" width="18" height="9"/><rect class="qcell lit" x="3" y="14" width="5" height="7"/><rect class="qcell" x="9.5" y="14" width="5" height="7"/><rect class="qcell" x="16" y="14" width="5" height="7"/></g></svg>
                    </button>
                </div>
            </div>
            <!-- ============================================================
                 Custom transport — lives OUTSIDE .video-player so quadrant
                 zoom never moves the scrubber, time text, or play/pause.
                 ============================================================ -->
            <div class="video-transport">
                <!-- Always-visible scrubber. Markers are populated only when
                     a JSON sidecar exists; the bar itself stays so users have
                     a seek surface for plain recordings too. -->
                <div class="timeline-scrubber" id="timelineScrubber" role="slider" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0" aria-label="Seek">
                    <div class="timeline-track" id="timelineTrack">
                        <div class="timeline-progress" id="timelineProgress"></div>
                    </div>
                    <div class="timeline-playhead smooth" id="timelinePlayhead"></div>
                </div>
                <!-- Event-type legend — hidden until the timeline has marker
                     data. Plain recordings show the scrubber alone. -->
                <div class="timeline-legend" id="timelineLegend" style="display:none;">
                    <span class="legend-item"><span class="legend-dot legend-motion"></span><span data-i18n="events.legend_motion">Motion</span></span>
                    <span class="legend-item"><span class="legend-dot legend-person"></span><span data-i18n="events.actor_person">Person</span></span>
                    <span class="legend-item"><span class="legend-dot legend-car"></span><span data-i18n="events.actor_vehicle">Vehicle</span></span>
                    <span class="legend-item"><span class="legend-dot legend-bike"></span><span data-i18n="events.actor_bike">Bike</span></span>
                    <span class="legend-item"><span class="legend-dot legend-animal"></span><span data-i18n="events.actor_animal">Animal</span></span>
                </div>
                <div class="video-controls">
                    <button type="button" class="video-ctrl-btn" id="videoPlayPauseBtn" data-i18n-attr="title:events.player_play_pause;aria-label:events.player_play_pause" title="Play/Pause" aria-label="Play/Pause">
                        <!-- Icon swapped between play/pause in events.js -->
                        <svg id="videoPlayPauseIcon" viewBox="0 0 24 24" fill="currentColor"><polygon points="6,4 20,12 6,20"/></svg>
                    </button>
                    <span class="video-time" id="videoTimeDisplay">
                        <span id="videoCurTime">0:00</span><span class="sep">/</span><span class="dur" id="videoDurTime">0:00</span>
                    </span>
                    <span class="spacer"></span>
                    <button type="button" class="video-ctrl-btn" id="videoMuteBtn" data-i18n-attr="title:events.player_mute;aria-label:events.player_mute" title="Mute" aria-label="Mute">
                        <svg id="videoMuteIcon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <!-- Initial state — muted (the player starts muted for autoplay). -->
                            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
                            <line x1="23" y1="9" x2="17" y2="15"/>
                            <line x1="17" y1="9" x2="23" y2="15"/>
                        </svg>
                    </button>
                    <!-- Fullscreen toggle. We fullscreen the .video-container
                         (NOT the <video>) so our quadrant zoom + custom
                         transport survive. Native <video> fullscreen would
                         drop us into the browser's player chrome and lose
                         the zoom transform. -->
                    <button type="button" class="video-ctrl-btn" id="videoFullscreenBtn" data-i18n-attr="title:events.player_fullscreen;aria-label:events.player_fullscreen" title="Fullscreen" aria-label="Fullscreen">
                        <svg id="videoFullscreenIcon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <!-- Initial state — enter fullscreen (corner brackets). -->
                            <path d="M4 9V4h5"/>
                            <path d="M20 9V4h-5"/>
                            <path d="M4 15v5h5"/>
                            <path d="M20 15v5h-5"/>
                        </svg>
                    </button>
                </div>
            </div>
            <div class="video-details">
                <div class="video-meta">
                    <span id="videoDate">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
                            <line x1="16" y1="2" x2="16" y2="6"/>
                            <line x1="8" y1="2" x2="8" y2="6"/>
                            <line x1="3" y1="10" x2="21" y2="10"/>
                        </svg>
                        --
                    </span>
                    <span id="videoTime">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <circle cx="12" cy="12" r="10"/>
                            <polyline points="12 6 12 12 16 14"/>
                        </svg>
                        --
                    </span>
                    <span id="videoSize">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                            <polyline points="7 10 12 15 17 10"/>
                            <line x1="12" y1="15" x2="12" y2="3"/>
                        </svg>
                        --
                    </span>
                </div>
                <a class="download-btn" id="downloadBtn" href="#" download>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                        <polyline points="7 10 12 15 17 10"/>
                        <line x1="12" y1="15" x2="12" y2="3"/>
                    </svg>
                    <span data-i18n="common.download">Download</span>
                </a>
            </div>
        </div>
    </div>

`;
    var script = document.currentScript;
    if (script && script.parentNode) {
        script.insertAdjacentHTML('beforebegin', markup);
    } else if (document.body) {
        document.body.insertAdjacentHTML('beforeend', markup);
    }
})();
