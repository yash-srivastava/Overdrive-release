<p align="center">
  <img src="https://overdrive-5lc.pages.dev/app-icon.webp" width="120" alt="OverDrive Logo">
</p>

<h1 align="center">OverDrive</h1>
<p align="center">Advanced Sentry Mode for BYD Vehicles</p>
<p align="center">
  <a href="https://github.com/yash-srivastava/Overdrive-release/releases/tag/alpha">Download Alpha</a> •
  <a href="https://overdrive-5lc.pages.dev/">Website</a> •
  <a href="https://discord.gg/PZutk9fg4h">Discord</a> •
  <a href="#features">Features</a> •
  <a href="#quick-start-use-pre-built-apk">Setup Guide</a>
</p>

Free, open-source dashcam and sentry mode app built specifically for BYD vehicles with DiLink v3. All data stays on your device — no cloud, no accounts, no subscriptions.

---

<p align="center">
  <a href="https://player.cloudinary.com/embed/?cloud_name=dhwuuoz67&public_id=Demo_nqf0ky">
    <img src="https://github.com/user-attachments/assets/d5faeb2a-96dd-4737-86f4-2e87af52ec4c" alt="Click to Watch OverDrive Demo" width="100%">
  </a>
</p>

---


## Quick Start (Use Pre-built APK)

Download the latest APK from [GitHub Releases](https://github.com/yash-srivastava/Overdrive-release/releases/tag/alpha) and install it directly on your BYD head unit.

### 1. Prerequisites
- Ensure **Wireless ADB** is enabled on your device before launching the app.

### 2. Initial Configuration
1. **Authorize ADB:** On first launch, accept the ADB authentication prompt on your device screen.
2. **Background Persistence:** In Settings, ensure the **"Disable Autostart"** toggle is **unchecked**. This is critical for reliable background operation.

> ⚠️ **CRITICAL: Hard Reboot Required**
> After the first installation and initial run, you must hard reboot the device:
> Press and hold the **Volume Down** button for 5 seconds. Wait for the system to fully restart.
> This step is necessary to finalize the installation.

### 3. Network & Tunnel Setup

**Option A: Dedicated Wi-Fi Hotspot (Recommended)**
- Keep the Sing-box Proxy disabled.
- Directly enable your preferred tunnel (Zrok or Cloudflared).

**Option B: Public / BYD SIM**
- Toggle the **"Public"** switch at the top right of the dashboard.
- Go to Daemon View and verify the Sing-box Proxy Daemon is running.
- Once verified, enable your preferred tunnel (Zrok or Cloudflared).

### Telegram Notifications Setup
1. Message [@BotFather](https://t.me/BotFather) on Telegram → `/newbot` → follow prompts → get your bot token
2. Message [@userinfobot](https://t.me/userinfobot) → `/start` → copy your Chat ID
3. In OverDrive: Settings → Notifications → enter bot token & chat ID

---

## Why OverDrive?

| Feature | OverDrive | Other Apps |
|---|---|---|
| CPU Usage | **<28%** | 70–90% |
| Proximity Recording | ✅ Market First | ❌ |
| Real-time Performance Monitor | ✅ Built-in | ❌ |
| ISP Blocklist Bypass | ✅ Via BYD SIM | ❌ Requires WiFi Hotspot |
| Remote Access | 3 methods (LAN, Cloudflared, Zrok) | Usually 1 (if any) |
| ADB Shell Runner | ✅ | ❌ |
| Telegram Notifications | ✅ Free | Paid or None |
| Data Privacy | 100% On-Device | Often Cloud-Required |
| Price | **Free Forever** | $5–50/month |

## Features

- **Optimized Recording Pipeline** — <28% CPU, ~150MB memory, <3s boot time
- **Proximity Recording (Market First)** — Uses BYD's 8 parking radar sensors to trigger recording only when objects approach. Configurable trigger levels, pre-event buffer, and 500ms debouncing.
- **Advanced Sentry Mode** — 24/7 surveillance with motion detection and AI object recognition
- **Real-time Performance Monitor** — CPU, GPU, memory usage, and battery voltage dashboard
- **ISP Blocklist Bypass** — Browse via BYD's built-in SIM card without a dedicated hotspot
- **ADB Shell Runner** — Built-in terminal for running commands, checking processes, and viewing logs
- **Telegram Notifications** — Instant alerts for motion detection, recording events, and low battery
- **Recording Library** — Calendar view for browsing and managing recordings

## Remote Access

Three options for viewing your car's cameras remotely:

### Local Network (LAN)
Access at `http://<car-ip>:8080` when on the same WiFi. Zero setup, fastest streaming.

### Cloudflare Tunnel
Access from anywhere via `https://<random>.trycloudflare.com`. No port forwarding, HTTPS by default. Video streaming can be slow due to Cloudflare limitations.

### Zrok Tunnel (Recommended)
Free, open-source tunneling with no bandwidth limits at `https://<your-share>.share.zrok.io`. Best for video streaming.

**Quick Zrok setup:**
1. Sign up at [zrok.io](https://zrok.io)
2. Get your invite token from email
3. Enter token in OverDrive settings
4. Done — tunnel URL is auto-generated

**Advanced self-hosted setup:**
1. Follow the [zrok setup guide](https://netfoundry.io/docs/zrok/category/self-hosting)
2. Copy the enable token from the create account command
3. Enter the token and zrok controller endpoint in OverDrive settings
    If wanting access outside network, ensure the zrok controller, ziti controller and ziti router are port forwarded
    The zrok frontend can have access restricted using caddy
4. Done — tunnel URL is auto-generated

## Tech Specs

| Category | Detail |
|---|---|
| Resolution | Up to 2560×1920 |
| Codec | H.264 / H.265 (HEVC) |
| Bitrate | 2–12 Mbps (configurable) |
| FPS | 15–30 fps |
| CPU Usage | <28% (optimized) |
| Memory | ~150MB |
| Streaming Latency | <100ms |
| Boot Time | <3 seconds |
| AI Detection | Hardware accelerated, real-time (vehicles, people, objects) |
| Tested On | BYD Seal (Global) |
| Platform | DiLink v3 |
| Android | 10+ (API 29+) |
| Architecture | arm64-v8a |

> Should work on all BYD vehicles with DiLink v3 and panoramic camera system.

## Building from Source

```bash
git clone https://github.com/yash-srivastava/Overdrive-release.git
```

Set up signing by exporting these environment variables before building:

```bash
export KEYSTORE_FILE=/path/to/your/release.jks
export KEYSTORE_PASSWORD=your_password
export KEY_PASSWORD=your_key_password
export KEY_ALIAS=your_alias
```

Then build with Gradle:

```bash
./gradlew assembleRelease
```

## VLESS Proxy Setup (Optional)

The ISP blocklist bypass feature uses a VLESS Reality proxy. The app ships with placeholder credentials — you need to supply your own.

1. Edit `app/src/main/cpp/secrets/secrets.json` and fill in your VLESS server details:
   ```json
   "proxy": {
     "PROXY_SERVER_IP": "your.server.ip",
     "PROXY_SERVER_PORT": "443",
     "PROXY_UUID": "your-uuid-here",
     "PROXY_SHORT_ID": "your-short-id",
     "PROXY_PUBLIC_KEY": "your-public-key",
     "PROXY_SNI": "google.com"
   }
   ```

2. Encrypt each value using the helper script:
   ```bash
   pip install pycryptodome
   python3 generate_safe_enc.py "your.server.ip"
   ```

3. Replace the corresponding `Safe.s("...")` values in `app/src/main/java/com/overdrive/app/daemon/GlobalProxyDaemon.java` (lines 71–79).

4. Rebuild the app.

If you don't need the proxy feature, you can skip this — the app works fine without it.

## Zrok Token Setup (Optional)

If you want to use Zrok tunneling for remote access, you need your own Zrok invite token:

1. Sign up at [zrok.io](https://zrok.io) and get your invite token from email
2. Enter the token in the app: Daemons → Zrok settings

## Privacy

- 100% local storage — all recordings saved on device
- No account required
- No cloud upload — remote viewing is direct via tunnels
- Open source — audit the code yourself

## Community

- [Discord Server](https://discord.gg/PZutk9fg4h)
- [Report Issues](https://github.com/yash-srivastava/Overdrive-release/issues)

## Acknowledgments

- **Native Bangcle Crypto Engine** — Full Java port of BYD's proprietary white-box AES encryption, based on the reverse engineering work by [Niek/BYD-re](https://github.com/Niek/BYD-re) and [jkaberg/pyBYD](https://github.com/jkaberg/pyBYD). Zero new dependencies — uses the existing OkHttp stack and Java crypto libraries.

## Changelog

### v11 — May 2026: BYD Cloud Deterrent, Sentry Mode Alarm & Pipeline Fixes

**✨ New Features**
- **BYD Cloud Deterrent** — When surveillance detects a confirmed threat, OverDrive can now automatically flash the car's headlights or honk the horn via BYD's cloud API. Three modes available: Silent (record only), Flash Lights, and Horn + Lights. Recurring triggers every 15 seconds while motion continues
- **BYD Cloud Account Setup** — One-time setup in Surveillance Settings to connect your BYD app account. Supports all 14 overseas server regions (EU, India, Australia, Singapore, Brazil, Japan, Korea, Saudi Arabia, Turkey, Mexico, Indonesia, Vietnam, Norway, Uzbekistan). Credentials are stored locally on the device and never sent to any third-party server — all communication goes directly to BYD's official API
- **Native Bangcle Crypto Engine** — Full Java port of BYD's proprietary white-box AES encryption, based on the reverse engineering work by [Niek/BYD-re](https://github.com/Niek/BYD-re) and [jkaberg/pyBYD](https://github.com/jkaberg/pyBYD). Zero new dependencies — uses the existing OkHttp stack and Java crypto libraries. No Python runtime, no JavaScript bridge, no bloat
- **Test Connection Button** — Verify your BYD Cloud setup works by flashing the car's lights directly from the settings page

**⚡ Optimizations & Fixes**
- **Camera/Recording Pipeline Optimizations** — Reduced memory allocations and improved frame throughput in the GPU surveillance and recording pipelines
- **SOH & Charging Info Fixes** — Fixed State of Health and charging data not displaying correctly on some BYD models

---

### v10 — April 2026: Surveillance Overhaul, Camera Re-Config & MQTT SSL

**✨ New Features**
- **Camera Re-Configuration** — New setup flow to identify and assign the correct camera and video feeds for different BYD vehicles. Helps resolve mismatched or swapped camera inputs across trims and model years
- **Status Pill Overlay** — Persistent floating indicator showing real-time recording and trip status. Automatically hides when ACC is off to save resources, reappears when you start the car
- **MQTT SSL/TLS Support** — Secure connections to MQTT brokers now work properly. Home Assistant, Mosquitto with TLS, and other SSL-enabled brokers are fully supported
- **Surveillance Detection Overhaul** — Major rework of the motion detection pipeline:
  - Select any combination of cameras to trigger motion events
  - Improved detection algorithm with significantly fewer false positives
  - New filter settings for sensitivity, cooldown, and minimum motion area
  - Preset configurations (Parking, Outdoor, etc.) for quick setup

**⚡ Optimizations & Fixes**
- **BYD Camera "No Signal" Fix** — Resolved the native camera signal loss issue that could occur when OverDrive is running alongside the BYD dashcam
- **CPU Performance** — Reduced CPU cycles across the recording and surveillance pipeline, yielding roughly 10–15% lower CPU usage compared to the last release
- **Event Deletion** — Fixed a bug where automated event deletion was not properly removing files from storage
- **SOH & Energy Display** — Corrected State of Health estimation calculations, fixed kWh consumption showing incorrect values on trip details, and charging power now displays correctly

---

### v9 — April 2026: MQTT Telemetry, PHEV Support & Camera Reliability

**✨ New Features**
- **MQTT Telemetry** — Connect to up to 5 MQTT brokers to publish vehicle telemetry with configurable intervals, QoS, and proxy support. Full web UI with live status and telemetry preview, accessible from sidebar and Android drawer
- **PHEV & Sealion 6 DM-i Support** — Plug-in hybrids now show correct remaining kWh, charging power, and battery health
- **Terrain-Aware Driving Scores** — Driving DNA adjusts scoring thresholds based on GPS altitude (flat, hilly, climb, descent). Elevation visible on trip cards
- **Trip Consumption Display** — Average consumption (kWh/100km) in trip summaries and detail view, with %/100km fallback for PHEVs
- **Battery Health & SOH** — Battery health tracking with voltage history, cell temperatures, SOH estimation, and ABRP battery temperature uploads
- **Zrok Token Reset** — Zrok reserved tunnel token can now be reset directly from the UI
- **BYD Camera Arbitration** — OverDrive registers with the BYD camera service so the native dashcam no longer loses video signal

**🐛 Bug Fixes**
- Fixed "no video signal" on the native BYD AVM camera when OverDrive is running
- Fixed double-recording and streaming issues across drive mode switches and camera interruptions
- Fixed trips being lost on ACC OFF and improved trip distance accuracy with GPS fallback
- Fixed SOC reading wrong source, charging power showing 0 kW, and SOH estimation accuracy
- Fixed driving score penalties for one-pedal driving and smoothness jitter
- Fixed performance chart time filters affecting the wrong chart
- Fixed MP4 corruption on surveillance stop and video playback of deleted recordings
- Fixed surveillance toggle and sentry state management across reboots and mode changes
- Improved daemon stability — watchdog retries on transient crashes, fixed Telegram and Zrok launch issues

---

### v8 — April 2026: BYD Yuan Pro Support, Network Awareness & Sentry Reliability

**⚡ Network Display**
- Added a network status indicator on the left nav panel across all pages
- Displays WiFi SSID, IP address, or Mobile Data connectivity status
- Icon dynamically switches between WiFi, cellular, and disconnected states

**🚗 BYD Yuan Pro Support**
- Added full support for BYD Yuan Pro — sentry mode, surveillance, live streaming, ABRP telemetry, and all vehicle data features work out of the box

**🎥 Sentry**
- Fixed ACC status getting stuck on "ON" after turning off the car via BYD app
- Resolved a gap in power level detection where the ON → ACC transition during BYD app shutdown was not triggering sentry mode re-entry

**📹 Events & Recordings**
- Fixed events page showing deleted or inaccessible ghost recordings from unmounted SD card paths
- Videos that no longer exist on disk are now properly filtered out instead of showing as unplayable entries
- Eliminated duplicate entries when the same recording exists across SD card and internal storage

**🐛 Bug Fixes**
- 🔋 **ACC State Reliability:** Hardened the ACC state notification path so CameraDaemon always receives the correct state, even when surveillance is disabled or suppressed by safe zones
- 💾 **Storage Integrity:** Calendar date highlights and storage statistics now accurately reflect only readable, valid files on disk

---

### v7 — April 2026
- 🔓 Open sourced the project
- 🧹 Removed hardcoded credentials (keystore, VPS, VLESS, Zrok token)
- 🔧 Signing config now uses environment variables
- 🔐 VLESS proxy credentials replaced with placeholders
- 🛠️ Added `generate_safe_enc.py` helper for encrypting your own secrets
- 📄 Added comprehensive README with setup guide
- 📝 Added .gitignore for clean repo hygiene

### v1.0.0 — January 2026
- 🚀 Optimized pipeline: <28% CPU usage
- 🎯 Market first: Proximity recording using BYD radar sensors
- 📊 Real-time performance monitor
- 🛡️ Advanced Sentry Mode with motion detection
- 🤖 AI-powered object detection
- ☁️ 3 remote access options (LAN, Cloudflared, Zrok)
- 📱 Telegram bot notifications
- 🔧 ADB shell console
- 🌐 ISP blocklist bypass via BYD SIM
- 📹 H.265 (HEVC) codec support
- 📚 Recording library with calendar view

## License

Open source under MIT License. Your data stays on your device.
