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
picture, luma sample-rate and bitrate limits. If the selected component does
not advertise a sufficient level, OverDrive omits the level hint and leaves
final negotiation to `MediaCodec.configure()` rather than requesting an
unsupported level. `VideoCapabilities` size/rate and bitrate ranges are also
logged as diagnostics; vendor capability reports can be incomplete, so they
are not treated as a substitute for the configure result.

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
