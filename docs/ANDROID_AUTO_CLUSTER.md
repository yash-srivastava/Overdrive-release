# Android Auto cluster video bridge

## Purpose

This feature receives the independent Android Auto `CLUSTER` video stream and
renders it on the BYD instrument cluster. It does not mirror or move the main
Android Auto touchscreen picture.

The factory BYD Android Auto receiver is not modified. The feature is optional
and requires a separate Android Auto receiver that can advertise a second
`CLUSTER` video sink and push its H.264 stream over RTP/UDP.

## Initial compatibility target

The first profile targets the 2024 BYD Dolphin with DiLink 3.0 and its
1920x720 driver-cluster panel. The receiver is asked to send a 1920x1080 H.264
cluster stream; OverDrive crops the unused lower margin to the 1920x720 panel.

DiLink 5 is explicitly rejected by this first implementation. Other BYD
instrument-cluster sizes and firmware families need their own verified
profiles before they are enabled.

## Receiver requirements

The receiver must support all of the following:

1. Android Auto second-display service discovery.
2. A second sink advertised as `DISPLAY_TYPE_CLUSTER` (not merely a copy of
   the main display and not an ordinary auxiliary display).
3. H.264 baseline video on the second video channel.
4. RTP/UDP push to a typed IPv4 destination.

Configure the receiver with:

- Second display: enabled
- Display role: `CLUSTER`
- Second-screen output: `RTP_PUSH`
- Destination: `127.0.0.1`
- UDP port: `5004`
- Video profile: `1920x1080` for the initial Dolphin profile

The experimental second-screen receiver work that motivated this bridge is
available in the Open Headunit project as draft pull request
https://github.com/andreknieriem/open-headunit/pull/1097. That work is not a
released, BYD-validated dependency. Build and test a compatible receiver
separately; OverDrive does not bundle its APK or copy its source.

A compatible receiver must connect to the phone using the normal Android Auto
pairing flow. OverDrive's **Open receiver** button launches the optional receiver
app when application ID `com.andrerinas.headunitrevived` is installed; it does
not install or configure the receiver for you. The phone's Android Auto app
decides what content to send to a
`CLUSTER` sink. Waze may send a full navigation map, a reduced cluster view,
a turn card, or no cluster video depending on the phone app/version and its
cluster-display support. OverDrive cannot force Waze to provide a map if the
phone does not send one.

## Using the bridge

1. Install and configure a compatible receiver.
2. In OverDrive, open **RoadSense → Map → Android Auto on instrument cluster**.
3. Enable **Receive Android Auto cluster video**. The bridge listens on
   `127.0.0.1:5004`; the cluster remains on its native gauges while it waits.
4. Connect the phone to the compatible receiver and start Waze navigation.
5. When a complete H.264 keyframe arrives, OverDrive opens the cluster
   projection and displays the independent cluster stream.
6. Disable the toggle to stop receiving and restore the native gauges.

The main Android Auto picture remains on the receiver's primary infotainment
display. The factory receiver remains installed and unchanged; users can
return to it by selecting the factory Android Auto connection path again.

## Safety and lifecycle

- The listener binds only to IPv4 loopback (`127.0.0.1`), not the vehicle LAN.
- The bridge is receive-only and does not send CAN, UDS, ECU, or vehicle-control
  commands.
- The cluster projection is not opened until a complete H.264 IDR access unit
  with SPS/PPS is received.
- If the video stream stops for five seconds, OverDrive stops the bridge,
  releases its decoder/layer, and releases its sustained cluster-projection
  hold so the native gauges return.
- Cluster app casting and the RoadSense map projection are mutually exclusive
  with this bridge. A competing start is refused until the user explicitly
  stops the active cluster owner.
- Any controller force-close or daemon shutdown retires the video layer before
  the fission display is closed.
- The stream is not recorded or uploaded.

## Known limitations

- This is an experimental receiver bridge, not a replacement Android Auto
  implementation inside OverDrive.
- It requires a compatible external receiver build with the second `CLUSTER`
  stream and RTP push enabled. The factory BYD receiver does not currently
  expose that stream to OverDrive.
- The initial decoder profile is fixed at 1920x1080 input and 1920x720 output
  for the 2024 Dolphin cluster.
- DiLink 5 and other cluster geometries are not enabled by this first profile.
- Waze's actual cluster content is selected by the phone-side Android Auto
  implementation. A receiver can request a cluster stream but cannot guarantee
  that Waze sends a full map.
- The receiver-side second-screen implementation is experimental and has not
  been validated on a BYD head unit. Vehicle testing is required before treating
  this as a supported release feature.

## Troubleshooting

- **Waiting for receiver:** confirm that the receiver is configured for a
  separate `CLUSTER` sink and sends RTP to `127.0.0.1:5004`.
- **No video:** check the receiver's log for second-sink negotiation, video
  focus and RTP packet counters. Confirm that Waze is actively navigating.
- **Gauges return after five seconds:** the RTP stream stopped or did not reach
  the loopback listener. Restart the bridge after reconnecting the receiver.
- **Projection unavailable:** stop RoadSense map projection or app casting,
  then try again. If the cluster is in recovery/restore, wait for it to settle.
- **Unsupported transport:** this initial implementation does not support
  DiLink 5.

## Verification status

The patch can be checked and applied against the repository source snapshot.
No Android build, full unit-test suite, receiver build, or in-vehicle validation
is claimed by this change. The first physical validation target is the 2024
BYD Dolphin with DiLink 3.0.
