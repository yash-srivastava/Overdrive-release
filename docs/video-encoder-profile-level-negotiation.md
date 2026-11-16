# Video encoder profile and level negotiation

`HardwareEventRecorderGpu` creates the platform encoder before applying codec
profile/level hints. Hints are selected from that concrete component's
`CodecCapabilities.profileLevels`, rather than assuming every BYD encoder
supports the same fixed level.

## Hardware AVC (H.264) and HEVC (H.265)

For AVC Baseline, `AvcLevelSelector` selects the lowest advertised level that
covers the configured macroblocks per picture, macroblocks per second and
bitrate. Android's AVC level constants are bit flags, so selection uses the
H.264 Annex A limits and never compares the integer constants as ordinals.

For HEVC Main, `HevcLevelSelector` applies the corresponding Main-tier luma
picture, luma sample-rate and bitrate limits. Advertised levels are treated as
maximum supported levels, not exact-only matches: a component advertising
Main-tier Level 5.1 can also satisfy a stream that only requires Level 4.
OverDrive selects the lowest level required by the stream, provided it does not
exceed the advertised maximum. Profile and level are applied together; if no
sufficient maximum is advertised, OverDrive omits BOTH hints so a vendor codec
is never configured with a profile but no matching level. `VideoCapabilities`
size/rate and bitrate ranges are also logged as diagnostics; vendor capability
reports can be incomplete, so they are not treated as a substitute for the
configure result.

## Supplied DI3 codec evidence

The supplied DI3 is identified as Qualcomm `trinket`, Android 10. Its
`/vendor/etc/media_codecs.xml` and `media_codecs_vendor.xml` declare these
hardware video encoders:

- **AVC/H.264:** `OMX.qcom.video.encoder.avc` (`video/avc`)
- **HEVC/H.265:** `OMX.qcom.video.encoder.hevc` (`video/hevc`)
- **HEVC constant-quality variant:** `OMX.qcom.video.encoder.hevc.cq`
- **VP8:** `OMX.qcom.video.encoder.vp8` (`video/x-vnd.on2.vp8`)

The AVC and regular HEVC hardware encoders both declare a 96x96 minimum and
4096x2160 maximum size, 2x2 alignment, 16x16 block size, up to 979,200
blocks/second, 1–100,000,000 bit/s, frame-rate range 1–240, and up to 16
concurrent instances. These are XML-advertised codec limits, not a promise that
every combination of maximum size, frame rate and bitrate can run together.

### AVC/H.264 details

The Qualcomm AVC encoder advertises these performance points:

- 4096x2160 at 24 fps
- 3840x2160 at 30 fps
- 1920x1080 at 120 fps
- 1280x720 at 240 fps

The performance XML reports measured AVC encoding rates of 303–380 fps at
320x240, 180–194 fps at 720x480, 44–61 fps at 1280x720, and 30–40 fps at
1920x1080. The resource-manager dump independently records active
`OMX.qcom.video.encoder.avc` clients, confirming that the Qualcomm AVC encoder
was actually used by the running system at dump time.

### HEVC/H.265 details

The regular Qualcomm HEVC encoder advertises VBR and CBR modes, up to
4096x2160, 100 Mbps, and performance points including 4096x2160@24 and
3840x2160@30. The performance XML reports measured HEVC encoding around
11–16 fps at 3840x2160 and 33–49 fps at 1920x1080.

The `.hevc.cq` component is a separate constant-quality encoder: its XML
advertises CQ mode and a fixed 512x512 size. It must not be treated as
interchangeable with the regular VBR/CBR HEVC encoder.

### Profile/level evidence limits

The vendor XML does not declare explicit Qualcomm AVC or HEVC profile/level
pairs. Therefore the XML proves that both Qualcomm hardware encoders exist and
documents their advertised size/rate/bitrate/performance limits, but it does
not establish their runtime `CodecProfileLevel` values. The implementation
queries `CodecCapabilities.profileLevels` from the concrete encoder selected
for the recording. The Google software HEVC encoder's Main Profile / Main
Tier Level 5.1 declaration is limited to 512x512 and is not evidence for the
Qualcomm hardware HEVC encoder. Likewise, Google software AVC declarations
must not be mistaken for Qualcomm hardware AVC capabilities.

A 2560x1920 AVC frame contains 19,200 macroblocks (ceil(width/16) ×
ceil(height/16)), exceeding AVC Level 3.1's 3,600 macroblocks per picture.
For the reported 2560x1920, 26 fps, 14.88 Mbps case, Level 5.0 is the lowest
AVC Baseline level satisfying the picture, sample-rate and bitrate limits.
The equivalent HEVC Main-tier selection is Level 5.0. Actual level selection
still depends on the selected encoder advertising that profile/level.

## Runtime diagnostics and validation

When the encoder reports `INFO_OUTPUT_FORMAT_CHANGED`, OverDrive logs the
negotiated output format. For AVC it also reads `profile_idc` and `level_idc`
from the SPS in `csd-0`. These are the actual stream values and are more
reliable than the requested `MediaFormat` hints. HEVC output format metadata
is logged when the platform exposes it; this change does not parse HEVC SPS.

The supplied evidence is DI3 only. No DI5 codec evidence or DI5 vehicle
validation is available, so this change makes no DI5 or cross-generation
compatibility claim. XML declarations and static selector tests do not replace
a live `MediaCodec.configure()` and recording test on the target head unit.

This fixes internally inconsistent fixed-level requests. It does not
establish or fix the cause of a Windows D3D11VA decoder-device initialization
failure; that remains dependent on the Windows GPU, driver, adapter and
playback stack.
