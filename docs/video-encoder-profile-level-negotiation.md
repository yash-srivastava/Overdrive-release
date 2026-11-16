# Video encoder profile and level negotiation

`HardwareEventRecorderGpu` creates the platform encoder before applying codec
profile/level hints. Hints are selected from that concrete component's
`CodecCapabilities.profileLevels`, rather than assuming every BYD encoder
supports the same fixed level.

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

The supplied DI3 (`trinket`, Android 10) vendor XML declares the hardware
`OMX.qcom.video.encoder.hevc` and `.hevc.cq` components. Its hardware encoder
entry advertises up to 4096x2160, 100 Mbps and VBR/CBR modes, with performance
points including 4096x2160@24 and 3840x2160@30. The performance XML reports
measured 3840x2160 HEVC encoding around 11–16 fps and 1920x1080 around 33–49
fps. Those XML declarations do not include a Qualcomm HEVC profile/level
pair, so they prove the encoder exists but do not establish its runtime
`CodecProfileLevel` values. The Google software HEVC encoder declares Main
Profile / Main Tier Level 5.1 but is limited to 512x512; it is not evidence
for the Qualcomm hardware encoder. DI5 codec evidence is still required
before claiming cross-generation compatibility.

When the encoder reports `INFO_OUTPUT_FORMAT_CHANGED`, OverDrive logs the
negotiated output format. For AVC it also reads `profile_idc` and `level_idc`
from the SPS in `csd-0`. These are the actual stream values and are more
reliable than the requested `MediaFormat` hints. HEVC output format metadata
is logged when the platform exposes it; this change does not parse HEVC SPS.

A 2560x1920 AVC frame contains 19,200 macroblocks (ceil(width/16) ×
ceil(height/16)), exceeding AVC Level 3.1's 3,600 macroblocks per picture.
For the reported 2560x1920, 26 fps, 14.88 Mbps case, Level 5.0 is the lowest
AVC Baseline level satisfying the picture, sample-rate and bitrate limits.
The equivalent HEVC Main-tier selection is Level 5.0. Actual level selection
still depends on the selected encoder advertising that profile/level.

This fixes an internally inconsistent fixed-level request. It does not
establish or fix the cause of a Windows D3D11VA decoder-device initialization
failure; that remains dependent on the Windows GPU, driver, adapter and
playback stack.
