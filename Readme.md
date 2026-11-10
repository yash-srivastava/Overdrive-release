<p align="center">
  <img src="app/src/main/assets/web/shared/app-icon-ios.webp" width="120" alt="OverDrive Logo">
</p>

<h1 align="center">OverDrive</h1>
<p align="center">Advanced Sentry Mode for BYD Vehicles</p>
<p align="center">
  <a href="https://github.com/yash-srivastava/Overdrive-release/releases/tag/alpha">Download Alpha</a> •
  <a href="https://www.overdrive.qd.je">Website</a> •
  <a href="https://discord.gg/PZutk9fg4h">Discord</a> •
  <a href="#features">Features</a> •
  <a href="#quick-start-use-pre-built-apk">Setup Guide</a> •
  <a href="#home-assistant-integration">Home Assistant</a> •
  <a href="#translations">Translate</a>
</p>
<p align="center">
  <a href="https://crowdin.com/project/overdrive">
    <img src="https://badges.crowdin.net/overdrive/localized.svg" alt="Crowdin localization status">
  </a>
</p>

Free, open-source dashcam and sentry mode app built specifically for BYD vehicles with DiLink v3. All data stays on your device — no cloud, no accounts, no subscriptions.

---

<p align="center">
  <a href="https://www.youtube.com/watch?v=MSgcZPENKfs">
    <img src=".github/assets/overdrive-demo.gif" alt="Click to Watch OverDrive Demo" width="100%">
  </a>
  <br>
  <sub>▶ Click to watch the full video on YouTube</sub>
</p>

---


### DiLink 5: optional ADB recovery permission setup

In **Settings → Daemons** on DiLink 5, enable **Prepare automatic ADB recovery**
while wireless ADB is already running and authorized. The app will periodically
attempt to grant itself `WRITE_SECURE_SETTINGS` using that authorized connection.
The setting is off by default and persists across app restarts. This complements
the existing DiLink 5 settings re-enable and OEM fallback mechanisms; it does not
replace their port watcher, change their retry policy, or enable recovery on DiLink 3/4.

The existing DiLink 5 recovery needs the permission before ADB disappears.
Without it, or if the firmware does not restart the TCP listener, recovery may
require manual intervention. A stopped app process cannot perform recovery.
Wireless debugging may expose the head unit to other devices on reachable networks;
use it only where that risk is acceptable.

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

Enable your preferred tunnel (Zrok, Cloudflared or Tailscale).

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
| Remote Access | 4 methods (LAN, Cloudflared, Zrok, Tailscale), plus WireGuard for private services | Usually 1 (if any) |
| ADB Shell Runner | ✅ | ❌ |
| Telegram Notifications | ✅ Free | Paid or None |
| Data Privacy | 100% On-Device | Often Cloud-Required |
| Price | **Free Forever** | $5–50/month |

## Features

- **Optimized Recording Pipeline** — <28% CPU, ~150MB memory, <3s boot time
- **Proximity Recording (Market First)** — Uses BYD's 8 parking radar sensors to trigger recording only when objects approach. Configurable trigger levels, pre-event buffer, and 500ms debouncing.
- **Advanced Sentry Mode** — 24/7 surveillance with motion detection and AI object recognition
- **Real-time Performance Monitor** — CPU, GPU, memory usage, and battery voltage dashboard
- **Tyre telemetry** — Dashboard shows each wheel's pressure in the selected unit and temperature in °C when available.
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

**Paid version (named tunnel on your own domain):**
Instead of a random `trycloudflare.com` URL, you can run a named tunnel on a domain managed in your Cloudflare account, e.g. `https://car.example.com`.

1. In the [Cloudflare Zero Trust dashboard](https://one.dash.cloudflare.com/), go to **Networks → Tunnels & Mesh → Create a tunnel** and choose **Cloudflared**
2. Copy the tunnel token.
3. Under **Public Hostname**, add your hostname (e.g. `car.example.com`) with service `http://127.0.0.1:8080`
4. In OverDrive: Daemons → Cloudflared settings → enable **Cloudflare Paid Version** → paste the token → save
5. Start the Cloudflared daemon. The URL shown is your configured hostname

### Zrok Tunnel (Recommended)
Free, open-source tunneling with no bandwidth limits at `https://<your-share>.share.zrok.io`. Best for video streaming.

**Quick Zrok setup:**
1. Sign up at [zrok.io](https://zrok.io)
2. Get your invite token from email
3. Enter token in OverDrive settings
4. Done — tunnel URL is auto-generated

### Tailscale Tunnel
Free, with no bandwidth limits. Connect from any device connected to tailscale.

**Quick Tailscale setup:**
1. Sign up at [tailscale.com](https://tailscale.com/)
2. Open tailscale settings in Overdrive
3. Generate a login URL and login
4. Optionally, disable key expiry in tailscale if you would not like to log in every 6 months

#### Tailscale proxy
With tailscale enabled, the tailscale proxy can be enabled from the tailscale settings.
This allows accessing an MQTT server through tailscale without port forwarding.
This can be accessed via the tailscale IP or a subnet that has been advertised on tailscale.

### WireGuard Tunnel
For home networks that already run a WireGuard server (OPNsense, pfSense, Fritz!Box, MikroTik, wg-easy, …). The car dials out to your server, so nothing needs to be reachable on the car side. It is meant for reaching your own services, such as the MQTT broker of Home Assistant. By default the tunnel is outbound-only and the dashboard is not published.

**Setup:**
1. On your router, add a WireGuard peer for the car and export its client config (the `.conf` file or QR code the router offers).
2. In OverDrive: Daemons → WireGuard Tunnel → settings. Paste the config, import the `.conf` from a USB stick (a picture of the QR code works too), or tap **Set up from phone** and open the shown link on your phone to upload the file there.
3. Save. The tunnel starts by itself; the daemon row shows the endpoint once the handshake is done.

Only addresses in `AllowedIPs` go through the tunnel. Everything else, including public MQTT brokers, keeps its normal route (direct, or via sing-box when it runs). With `AllowedIPs = 0.0.0.0/0` all proxied traffic uses the tunnel. If the config sets `DNS =`, host names are asked there first while the tunnel is up, so a broker address like `homeassistant.lan` works.

- `PersistentKeepalive` defaults to 25 seconds when the config leaves it out. Without it, the mobile carrier's NAT drops the mapping and commands from Home Assistant stop reaching the car.
- Saving a new config while the tunnel runs applies it within a few seconds.
- If the Tailscale proxy runs as well, MQTT goes through Tailscale.
- **Dashboard reachable over WireGuard** (settings dialog, off by default): serves the dashboard on the car's tunnel address, for example `http://10.8.0.2:8080`, so a device on your home network can open it through the WireGuard server like it would over Tailscale. Requests that arrive this way are treated as remote and must log in; only on-device callers skip the login. Switching it restarts the tunnel. Every peer that can reach the car's tunnel IP can reach the login page, so limit the car's address to trusted peers in the firewall rules of your WireGuard server (for example allow only your admin devices to reach it) and keep a strong dashboard password.
- The config, including the private key, is readable only by OverDrive's daemon user and is not part of config backups.

## Home Assistant Integration

OverDrive can publish your vehicle's live telemetry to [Home Assistant](https://www.home-assistant.io/) over MQTT **and** let Home Assistant send commands back to the car (climate, windows, seats, charge limit, and more). Entities appear in Home Assistant automatically — **no YAML editing required**.

> **Everything is local.** Commands are sent straight to the car's own software (the BYD SDK on the head unit). They **never** go through the BYD cloud. Your data and controls stay between your car and your Home Assistant.

### What you get

- **Sensors (read-only):** battery %, range, speed, location (as a device tracker on your HA map), tyre pressures, cabin/battery temperature, charging state, doors/windows status, and more.
- **Controls (optional, off by default):** climate on/off + temperature + fan, windows, tailgate, sunroof/sunshade, seat heating/ventilation, daytime running lights, charge limit, child lock, wireless phone charger, and assorted car settings.

### Before you start — what you need

1. A running **MQTT broker**. If you use the [Mosquitto add-on](https://github.com/home-assistant/addons/tree/master/mosquitto) in Home Assistant, you already have one.
2. The **MQTT integration** enabled in Home Assistant (Settings → Devices & Services → Add Integration → MQTT).
3. Your broker's **address and port** (default port is `1883`, or `8883` for TLS), and a **username/password** if your broker requires one.
4. Your car and Home Assistant able to reach each other on the network. If they're not on the same LAN, enable the **Tailscale proxy** (see [Tailscale proxy](#tailscale-proxy) above) or the [WireGuard Tunnel](#wireguard-tunnel) so the car can reach your broker without port forwarding.

### Step 1 — Add the connection in OverDrive

Open OverDrive → **MQTT** (sidebar or app drawer) → **Add Connection**, then fill in:

| Field | What to enter |
|---|---|
| **Connection Name** | Anything, e.g. `Home Assistant` |
| **Broker URL** | Your broker's host, e.g. `192.168.1.10`. For TLS or WebSocket, add a protocol prefix — see [Broker URL formats](#broker-url-formats) |
| **Port** | `1883` (plain) or `8883` (TLS). Ignored if the Broker URL already contains a port |
| **Username / Password** | Only if your broker requires login (leave blank otherwise) |
| **Topic** | Leave the default `overdrive/vehicle/telemetry` unless you have a reason to change it |

> **Self-signed / Mosquitto TLS certificate?** Enable the **trust self-signed certificates** option on the connection so OverDrive accepts your broker's cert.

#### Broker URL formats

The protocol comes from the prefix on the **Broker URL**. Without a prefix, OverDrive always connects with plain, unencrypted MQTT (`tcp://`), even on port `8883`.

| Protocol | Prefix | Typical port |
|---|---|---|
| Plain MQTT | none, or `tcp://` | `1883` |
| MQTT over TLS | `ssl://` | `8883` |
| MQTT over WebSocket | `ws://` | depends on broker |
| MQTT over secure WebSocket | `wss://` | `443` or `8884` |

Only these four prefixes work. `mqtt://`, `mqtts://`, `http://` and `https://` aren't recognised.

How the Broker URL and **Port** field combine:

| Broker URL | Port field | Connects to |
|---|---|---|
| `192.168.1.10` | `1883` | `tcp://192.168.1.10:1883` |
| `ssl://broker.example.com` | `8883` | `ssl://broker.example.com:8883` |
| `ssl://broker.example.com:8883` | anything | `ssl://broker.example.com:8883` (Port field ignored) |
| `wss://broker.example.com:8884/mqtt` | anything | `wss://broker.example.com:8884/mqtt` (Port field ignored) |

Rules:

- **To use a TLS port, add `ssl://` or `wss://`.** A bare host with port `8883` connects as `tcp://host:8883`, sending plain MQTT to a TLS port. The broker drops it, and the connection fails with `reason=32109` / `EOFException`.
- **A port in a bare host doesn't work.** `broker.example.com:8883` becomes `tcp://broker.example.com:8883:1883`. Put the port in the Port field, or add a prefix and keep the port in the URL.
- **A WebSocket path needs the port in the URL.** Write `wss://host:8884/mqtt`. Without the port, `wss://host/mqtt` gets the Port field appended after the path (`wss://host/mqtt:8884`), which is invalid.
- **A trailing slash is removed**, so `ssl://broker.example.com/` works the same as `ssl://broker.example.com`.

Examples:

| Broker | Broker URL | Port |
|---|---|---|
| Home Assistant Mosquitto, plain | `192.168.1.10` | `1883` |
| Home Assistant Mosquitto, TLS (turn on *trust self-signed certificates*) | `ssl://192.168.1.10` | `8883` |
| HiveMQ Cloud, TLS | `ssl://<cluster-id>.s1.eu.hivemq.cloud:8883` | (ignored) |
| HiveMQ Cloud, secure WebSocket | `wss://<cluster-id>.s1.eu.hivemq.cloud:8884/mqtt` | (ignored) |

For HiveMQ Cloud, use the credentials from the cluster's **Access Management** page, not your HiveMQ console login, and leave *trust self-signed certificates* off.

### Step 2 — Turn on Home Assistant discovery

1. Switch on **Home Assistant discovery**.
2. Leave **Discovery prefix** as `homeassistant` (this matches Home Assistant's default — only change it if you customised yours).
3. Save. Within a few seconds a new **OverDrive** device appears in Home Assistant under Settings → Devices & Services → MQTT, with all the sensors already populated.

That's it for read-only telemetry. ✅

### Step 3 — Enable controls (optional)

By default OverDrive only *publishes* data — it ignores any command. To let Home Assistant actually control the car:

1. On the same MQTT connection, turn on **Allow vehicle control** (this option only appears once Home Assistant discovery is enabled).
2. Save. The controllable entities (climate, windows, seats, charge limit, …) now show up on the OverDrive device in Home Assistant, and you can operate them from any dashboard, automation, or voice assistant.

> ⚠️ **Safety:** "Allow vehicle control" is **off by default** and is a real control path to your car. Some actions move physical parts (windows, tailgate, sunroof). Only enable it on a broker you trust, behind a username/password, and ideally on a private network or your Tailscale tailnet.

### Using the controls

Once discovery + control are on, the entities are standard Home Assistant entities — drag them onto a dashboard, call them from automations, or ask your voice assistant. No topic juggling needed.

#### Advanced: sending commands manually

Under the hood every control listens on `<topic>/<key>/set` (where `<topic>` is the connection's Topic from Step 1, default `overdrive/vehicle/telemetry`). Handy for testing with `mosquitto_pub` or for templated automations:

```bash
# Climate: turn on, set 21 °C, fan speed 3
mosquitto_pub -t 'overdrive/vehicle/telemetry/climate/mode/set'        -m 'auto'
mosquitto_pub -t 'overdrive/vehicle/telemetry/climate/temperature/set' -m '21'
mosquitto_pub -t 'overdrive/vehicle/telemetry/climate/fan_mode/set'    -m '3'

# Vent the cabin / open windows
mosquitto_pub -t 'overdrive/vehicle/telemetry/windows_all/set' -m 'OPEN'   # or CLOSE / STOP

# Charge limit: enable and cap at 80 %
mosquitto_pub -t 'overdrive/vehicle/telemetry/charge_cap_enabled/set' -m '1'
mosquitto_pub -t 'overdrive/vehicle/telemetry/charge_cap_percent/set' -m '80'

# Daytime running lights on
mosquitto_pub -t 'overdrive/vehicle/telemetry/drl/set' -m 'on'
```

Full list of controllable entities and their accepted payloads:

| Control | Key | Payloads |
|---|---|---|
| Climate mode | `climate/mode` | `off`, `auto` |
| Climate temperature | `climate/temperature` | `17`–`33` (°C) |
| Climate fan | `climate/fan_mode` | `0`–`7` |
| Windows | `windows_all` | `OPEN`, `CLOSE`, `STOP` |
| Tailgate | `tailgate` | `OPEN`, `CLOSE`, `STOP` |
| Sunroof | `sunroof` | `OPEN`, `CLOSE`, `STOP` |
| Sunshade | `sunshade` | `OPEN`, `CLOSE`, `STOP` |
| Driver / passenger seat heating | `seat_heat_driver`, `seat_heat_passenger` | `off`, `low`, `medium`, `high` |
| Driver / passenger seat ventilation | `seat_vent_driver`, `seat_vent_passenger` | `off`, `low`, `medium`, `high` |
| Recall driver seat memory | `seat_memory_driver` | `PRESS` |
| Daytime running lights | `drl` | `on` / `off` (or `1` / `0`) |
| Speed-limit warning | `adas_slw` | `on` / `off` |
| Charge limit on/off | `charge_cap_enabled` | `1` / `0` |
| Charge limit % | `charge_cap_percent` | `50`–`100` (step 5) |
| Child lock | `child_lock` | `1` / `0` |
| Phone wireless charger | `wireless_charging` | `1` / `0` |
| Car settings | `setting_<name>` | depends on setting (on/off, a number, or a fixed list) |

### Troubleshooting

- **No OverDrive device in Home Assistant?** Check the MQTT connection shows **Connected** in OverDrive, confirm the **Discovery prefix** matches HA's (default `homeassistant`), and make sure the HA MQTT integration points at the *same* broker.
- **Sensors show but controls are missing?** "Allow vehicle control" isn't enabled — see Step 3 (it only appears after discovery is on).
- **A command does nothing?** The car must be awake/accessible to the head-unit SDK for that action. OverDrive optimistically updates the entity, then the next telemetry refresh reconciles the true state.
- **Broker on a different network?** Enable the [Tailscale proxy](#tailscale-proxy) or the [WireGuard Tunnel](#wireguard-tunnel) so the car can reach it without port forwarding.
- **`Connect failed (reason=32109)` with `EOFException`?** The broker closed the connection before answering. This usually means plain MQTT is going to a TLS port. Add `ssl://` (or `wss://`) to the Broker URL — see [Broker URL formats](#broker-url-formats).

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

### Closed component: `libod.so`

The blind-spot lens-projection coefficients ship as a prebuilt, closed-source
`libod.so` (`app/src/main/jniLibs/arm64-v8a/`); its source (`cpp/od/`) is not
part of this repository. At runtime the library authorizes against the app's
signing certificate, so a **release** build signed with a different key will
render the blind-spot camera card black.

**Debug builds bypass this check** — `./gradlew assembleDebug` works normally
from a clean clone, including for contributors, and the native build configures
fine without the `od` source. Only release forks signed with a non-matching key
are affected.

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
3. If building from source, also replace `YOUR_ZROK_TOKEN` in `app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java` with your token (this is only used for the Telegram bot's `/tunnel zrok` command)

## Privacy

- 100% local storage — all recordings saved on device
- No account required
- No cloud upload — remote viewing is direct via tunnels
- Open source — audit the code yourself

## Translations

OverDrive's UI is translated through [Crowdin](https://crowdin.com/project/overdrive). The app currently ships in **16 languages** (English, German, Spanish, French, Hindi, Italian, Japanese, Korean, Dutch, Norwegian Bokmål, Brazilian Portuguese, Russian, Thai, Turkish, Vietnamese, Simplified Chinese, Traditional Chinese), with another ~14 languages open for community translation (Polish, Czech, Romanian, Catalan, Swedish, Finnish, Danish, Greek, Hungarian, Hebrew, Arabic, Serbian, Ukrainian, Afrikaans).

### How to help

1. Sign up at [crowdin.com](https://crowdin.com) (free).
2. Visit the [OverDrive project](https://crowdin.com/project/overdrive) and click **Join**.
3. Pick a language and translate strings directly in Crowdin's web editor — **no GitHub or coding required**.

New strings get auto-pre-translated by AI; translators just need to review and correct rather than start from scratch. Brand names (BYD, OverDrive, Tailscale, etc.) are protected — Crowdin's glossary marks them "do not translate".

When you save translations they enter as suggestions. Once enough community translators agree, or a proofreader approves, they land in the next release via an automated pull request.

### Want proofreader access?

If you've contributed substantial translations for a language, open an issue or DM the maintainer for **Proofreader** role — that lets you approve translations directly without waiting for review.

## Community

- [Discord Server](https://discord.gg/PZutk9fg4h)
- [Report Issues](https://github.com/yash-srivastava/Overdrive-release/issues)
- [Translate on Crowdin](https://crowdin.com/project/overdrive)

## Acknowledgments

- **Native Bangcle Crypto Engine** — Full Java port of BYD's proprietary white-box AES encryption, based on the reverse engineering work by [Niek/BYD-re](https://github.com/Niek/BYD-re) and [jkaberg/pyBYD](https://github.com/jkaberg/pyBYD). Zero new dependencies — uses the existing OkHttp stack and Java crypto libraries.
- **3D BYD Vehicle Models** — Vehicle Control page uses base models from [ddiaz-design's BYD collection on Sketchfab](https://sketchfab.com/ddiaz-design/collections/byd-base-models-5bf92ab5f2be4ff6be5c3ac49f7099f3).
- **BYD Dashcast** — Overdrive projection to instrument cluster uses some vetted techinques of [BYD-Dashcast](https://github.com/Kiroha/byd-dashcast), a fantastic open-source project for application projection.

## Changelog

Condensed highlights below. Full release notes for every version live in [RELEASE_NOTES.md](RELEASE_NOTES.md).

### v35.0 — July 2026: Blind-Spot Camera Fixes & Pick-Your-Overlay

**✨ New Features**
- **Blind-spot side/rear views** — Correct single-camera feed with per-side rotation, per-side screen position, and a fisheye-correction slider
- **Choose your overlay fields** — The burned-in overlay is now a checklist: keep the usual speed, gear, pedals, seatbelts, turn signals and timestamp, or add **battery %, 12V voltage, low/high beam, and GPS location**. Pick per recording type — trips/manual, surveillance, and OEM dashcam each have their own list
- **Overlay on surveillance too** — Sentry event clips can now carry the overlay (off by default), with its own separate field selection

**⚡ Optimizations & Fixes**
- **Blind-spot reliability** — Single-camera passthrough (sliders only affect the merged view), stripped release logs, and arming that a disable can't undo
- **HUD On/Off** *(speculative — please confirm on your car)* — The HUD on/off action now toggles the dedicated head-up-display power switch instead of just dropping brightness to zero, so "off" actually turns the HUD off
- **Bluetooth connect/disconnect triggers** *(speculative)* — Bluetooth connection state is now watched from the app and relayed to automations, so "when Bluetooth connects / disconnects" rules should fire
- **Bluetooth device-name condition** *(speculative)* — The connected phone's name is now populated alongside the connection state, so a "only while &lt;my phone&gt; is connected" condition can match
- **Play Video** *(speculative)* — Reworked how the video player is launched (and how the video button/key-mapping fires) to fix uploaded MP4s not starting on screen

---

### v34.0 — July 2026: Projection, Smarter Automations & Reliability Fixes

**✨ New Features**
- **Choose when Overdrive runs** — Pick "On & Off" (keeps watching while parked, the default) or "On Only" (fully shuts down when you park and starts back up when you switch the car on — no overnight battery use)
- **Low-power mode while parked** — Optional setting that drops the cameras to a low idle frame rate when nothing's happening and instantly ramps back to full quality on motion
- **Projection** — Cast an app to the driver cluster and see a live, draggable, resizable mirror of it on the main screen — tap and swipe the mirror to control it
- **On-screen messages** — New "Show Toast" and "Show Dialog" actions put a message on the screen from any automation or button, floating over whatever's on screen without taking over the car
- **Compare against a variable or another signal** — If / Else, Loop, and Wait-Until can now compare a value against one of your variables or against another live signal
- **Set a variable from the car's live state** — A "Set Variable" action can capture the current value of a signal into a variable to use later
- **Air recirculation control** — Switch the air intake between recirculate and fresh air from an automation or a mapped button

**⚡ Optimizations & Fixes**
- **Play Video works now** — Uploaded MP4 videos wouldn't start (from either an automation or a button); they now play correctly on screen
- **Seatbelt automations fire** — The driver/passenger belt state was read the wrong way and never updated. Fixed, with a de-glitch so a never-fastened passenger seat reads correctly
- **Cabin-temperature automations fire** — "Cabin temp" was reading the climate dial (your setpoint) instead of the measured cabin temperature. It now reads the real sensor
- **Double-tap bindings no longer trigger the single action** — More forgiving detection window, plus a new **Double-tap speed** slider on the Key Mapping page
- **Fold/unfold mirrors reliability** — Aligned the mirror-fold command with the reference behaviour so it reports success correctly
- **Ambient-light colour picker redesign** — Tap-to-pick swatch grid matching the Vehicle Control page, instead of a fiddly slider

---

### v33.1 — July 2026: Cast to the Cluster, More Automation Signals & Reliability Fixes

**✨ New Features**
- **Cast any app to the driver cluster** — A "Move app to display" automation/button can now send an app to the instrument cluster behind the wheel
- **Save reusable action groups from the app** — Build a named set of actions once, then run it from any automation or button, with a proper Groups tab
- **Lock and regen as triggers & conditions** — React to the car locking/unlocking, and to the energy-recuperation level (Standard / High / Max)
- **Block a button's single-click** — Optional per-binding setting so a double-press action isn't preceded by the button's normal single-click

**⚡ Optimizations & Fixes**
- **More triggers & conditions fire reliably** — Gear (incl. Park), seatbelt, drive/EV mode, hazards, high/low beam, auto-lights, incline and parking-radar were reading a stale value; they now update live
- **If / Else automations save correctly** — The "Otherwise" branch was being dropped on save; both branches now persist
- **Action groups save reliably** — Fixed a wiring bug that made every group save fail
- **Automation editor polish** — Consistent field widths, tidier action-group cards, cleaner condition value picker
- **Lower parked power use** — Fast pedal/steering polling now stops when the car is off

---

### v33.0 — July 2026: Programmable Automations, Full ADAS Control & More Ways to React

**✨ New Features**
- **Automations that can think** — Loops, an inline If / Else branch, number variables, and reusable action groups
- **Smarter conditions** — Nested AND / OR groups, and compare one live value against another (cabin vs outside temp)
- **One automation controls another** — enable/disable/toggle a rule from another. Name them, see how often each runs, and fire any from a mapped button
- **Full ADAS control** — blind-spot, traffic-sign, cross-traffic alert & brake, traffic-light, door-open & rear-collision warning, speed-limit, lane-keeping, forward-collision, hazards. Emergency Braking is re-arm-only, never off
- **More to control** — WiFi / Bluetooth / data, AC auto & fan-only, heated wheel, welcome & reading lights, ambient music, headlight level, media, volume, brightness, speak-aloud and app navigation
- **More to react to** — drive & EV/HEV mode, sunrise/sunset, date, rain-soon, an incoming call, and what the **parked camera** sees (person, vehicle or animal)
- **Home Assistant, both ways** — publish an MQTT message from a rule, and trigger a rule from an incoming one
- **Edit shared automations** — update a community automation you published in place, keeping its ratings and downloads

**⚡ Optimizations & Fixes**
- **Sounds and videos play now** — the Play Audio / Video actions were silent on many cars
- **Driver seat heating no longer also turns on the passenger seat**
- **Double-press-only buttons work again** — a single tap now passes through to the car's own function. **"Close all windows"** from a button works too
- **Door open/close automations work while parked** — no longer need the car on
- **Stability Control & wireless charging toggles fixed**; key mapping recovers faster after a dropped button-capture service

---

### v32.0 — July 2026: Camera Views, Your Own Sounds & Smarter Automations

**✨ New Features**
- **Show any camera on screen — on demand** — Front, rear, left, right or all-four, on the main infotainment screen or the instrument cluster, at the size and corner you pick. Fire it from an automation ("show rear on reverse") or a mapped button, with optional auto-hide
- **Play your own sounds and videos** — Upload MP3/WAV/MP4 to the new Audio Library, then play from an automation or button
- **Many more things your car can react to** — New triggers for accelerator/brake pressure, steering angle, indicators, the emergency alarm, tyre pressure/leak warnings, and your phone connecting over Bluetooth by name
- **Rules that know where you are** — A location trigger fires when you enter or leave a mapped Safe-Location zone. Zones now go as small as 15 m (was 50)
- **More you can control** — Cluster and head-up-display brightness, volume per channel, and brake pedal feel (Comfort/Sport)
- **Pause and wait in a routine** — Pause for a set time, or wait until a value is reached ("wait until stopped, then close windows")
- **Toggle with one button** — Mapped buttons can flip a setting on, then off on the next press

**⚡ Optimizations & Fixes**
- **Edit a key mapping in place** — Edit an existing binding instead of deleting and re-adding
- **Steering & pedal rules fire reliably** — Fixed the steering reading sticking at start-up, and stopped a momentary "unavailable" reading from firing a rule by mistake
- **Turn-signal rules don't flicker** while you wait to turn
- **EV / HEV switching hardened** — a guard ensures the powertrain only ever selects a valid mode
- **Fully translated** across all shipped languages

---

### v31.0 — July 2026: Community Automations, Ambient Automation, App Launcher & Live Deterrents

**✨ New Features**
- **Community Automations — browse, share and rate** — A new Community tab to discover automations other drivers built and add them with one tap (switched off, so you can review them first). Publish your own, rate the ones you like, and sort or filter by rating, adds or recency
- **Ambient light automation — and more ways to trigger** — Set the interior ambient light colour as an automation action, or straight from the Vehicle Control page. New triggers react to current speed, the time of day, and the day of the week — plus a copy button to duplicate an existing automation
- **Open any app — from an automation or a button** — Launch any installed app automatically when an automation fires, or bind it to a steering-wheel/dashboard button with Key Mapping
- **Horn and Flash on the live camera view** — Dedicated Horn and Flash buttons right on the live camera feed to deter anyone near your car
- **Separate MQTT heartbeat for parked, charging and normal** — Independent heartbeat sliders let you slow telemetry publishing right down when the car is parked or charging

---

### v30.2 — July 2026: Custom Key Mapping

**✨ New Features**
- **Key Mapping — make the car's buttons do what you want** — Rebind your steering-wheel and dashboard buttons. Capture a button (or type its code), pick single, double or long press, and choose what it does — lock or unlock, open the windows, tailgate, sunroof or sunshade, toggle climate, seat heating or cooling, daytime lights, flash the lights or find your car. Find it under **Vehicle → Key Mapping**
- **One button, a whole routine** — Chain several actions into a single press. One button can close the windows, lock the doors and switch to ECO — in order, every time
- **Switch drive and energy modes anywhere** — Change drive mode (ECO / Sport / Normal / Snow), powertrain (EV / HEV), energy recuperation and steering feel — as button mappings, automation actions, and from Home Assistant / MQTT
- **Run scripts and open apps automatically** — Bind a shell command to a button or fire one from an automation. Off by default behind a separate permission, with a clear warning before you turn them on

**⚡ Optimizations & Fixes**
- **Instant settings** — Key-mapping changes now take effect the moment you save
- **Fully translated** — Every new screen and label is available in all supported languages

---

### v30.0 — July 2026: Automation Support

**✨ New Features**
- **Automations — your car reacts on its own** — Build your own "when this, do that" rules. Pick a trigger from a vehicle event (power state, gear, battery level, windows and more), add conditions, and choose what happens — open or close the sunroof, sunshade or windows, switch lights or seat heating and cooling on, set the speed-limit warning, or send yourself a notification
- **Animals show up on the event timeline** — When the camera spots an animal, it now appears on the recording's timeline and subtitles alongside people and vehicles

**⚡ Optimizations & Fixes**
- **Smoother MQTT** — Optimizations so your car's data flows more reliably to Home Assistant and other tools
- **Better Brazilian Portuguese** — Cleaned up the Brazilian Portuguese (PT-BR) translations

---

### v29.0 — July 2026: Tune Each Camera, Catch Every Alert

**✨ New Features**
- **Separate quality for driving and surveillance** — Recording frame rate and video quality can now be set independently for the two modes
- **A history of every notification** — Notifications now have their own Log tab. Filter by date, category or severity, and tap an event alert to jump straight to its recording

**⚡ Optimizations & Fixes**
- **Correct charging power for plug-in hybrids** — Fixed the charging power reading on PHEVs
- **Turn-by-turn banner alignment** — Sorted out the alignment of the turn-by-turn navigation banner
- **Sharper surveillance detection** — Improved detection accuracy so the camera flags what matters and misses less
- **Live MQTT power draw** — Fixed MQTT power draw so it now updates live
- **Time to full charge no longer sticks** — Fixed the time-to-full-charge figure getting stuck on the dashboard

---

### v28.0 — June 2026: Charging History & Longer Clips

**✨ New Features**
- **Charging history and a dashboard card** — Overdrive now logs your charging sessions and shows a card with time to full charge and more
- **Longer event clips** — Recordings were fixed at 2 minutes; you can now choose 5 or 10 minutes too
- **Save places straight from the map** — Press and hold anywhere on the map to add that spot as a favourite

**⚡ Optimizations & Fixes**
- **More accurate trip distance** — Trips now use the car's own distance reading, falling back to GPS only when it isn't available
- **Fixed Driving DNA consistency** — Corrected the consistency score calculation
- **Fixed turn-by-turn on the cluster** — The navigation banner now shows correctly on the instrument cluster projection
- **Map settings without RoadSense** — You can open map settings without enabling RoadSense first
- **Correct update tags and version info**
- **Improved Surveillance recording and hero thumbnail generation accuracy**

---

### v27.0 — June 2026: Easier Recordings, Settings You Can Carry Over

**✨ New Features**
- **Find your clips faster, wherever they're saved** — Your recordings now show as simple chips so you can see at a glance where things are stored, and a filter lets you look through clips across all your storage in one place
- **Back up your settings and trips, your way** — Save all your Overdrive settings to one file and load them back whenever you need. Your trip info is there too, accessible from the car, the web app, or Telegram
- **Dial in how sensitive RoadSense is** — A new slider lets you turn speed breaker and pothole detection up or down to match your roads
- **Bring your trips back if your storage gets wiped** — Added a Trip Restore button so you can recover past trips instead of losing them

**⚡ Optimizations & Fixes**
- **Surveillance stays on with USB power off** — Fixed an issue where surveillance would quietly stop after a while when the USB power toggle was off
- **Telegram notifications work properly again**
- **Cleaner trip numbers for plug-in hybrids** — Two decimal places, and for PHEVs the start and end battery percentage
- **Correct battery health and energy for plug-in hybrids**
- **The map tells you why a search didn't work** — Clear reason instead of just "couldn't find a route"
- **Your location is up to date when you come back to the map**
- **Smoother position marker on the driver cluster** — The dot now glides along instead of hopping every second, following your car's own speed and braking
- **Sharper map on the driver cluster** — Now fills the whole cluster screen and reads clearly

---

### v26.0 — June 2026: Choose Your Updates — Alpha or Braveheart

**✨ New Features**
- **Two update channels — pick how new you like it** — Choose how updates reach your car from **Settings → About → Update channel**. Switch any time
  - **Alpha** — the stable release, recommended for everyday driving. Every version is kept here, so you can install any one you like
  - **Braveheart** — the latest and greatest, the moment it's ready. New features first, with the occasional rough edge
- **Send us a log when something goes wrong** — The app bundles up a diagnostic log (with personal info automatically removed) and gives you a short code to share on Discord, GitHub, or WhatsApp
- **Light or dark map, your call** — Switch the map between light and dark, or leave it on **Auto** to follow your app theme
- **Save your Home, Work, and favourite places** — Pin the places you drive to most and reach them in one tap
- **Pick up your trip where you left off** — Your destination and stops are still set when you come back to the map

**⚡ Optimizations & Fixes**
- **Lower CPU and GPU usage with Proximity Guard and Blind Spot on** — Cameras run at a low frame rate when nothing is happening, switching to full quality the moment there's an event
- **Live view runs at full quality only while open**
- **Cameras start only when the mode needs them**
- **Recording storage limit is now properly enforced** — Older recordings clear correctly once the storage limit is reached, including dashcam drive clips
- **Smooth location tracking on the map**
- **Re-routing keeps your stops**
- **Smoother, more reliable updating** — Installs and channel switches are dependable, and settings carry over cleanly
- **More accurate battery health on plug-in hybrids**
- **Fuel and total range now shown for plug-in hybrids**

---

### v25.0 — June 2026: Turn-by-Turn Navigation with RoadSense Hazards

**✨ New Features**
- **Turn-by-turn navigation, right on your dash** — Search for a place, pick your route, and follow clear guidance as you drive. You can send the map to your driver cluster so the road ahead stays in your line of sight
- **See road hazards along your route** — The map plots the speed breakers and potholes RoadSense knows about — yours and ones shared by other drivers. Tap any hazard to confirm or remove it
- **Chinese BYD cloud accounts now supported**

**⚡ Optimizations & Fixes**
- **Recording starts right away** — Fixed an uncommon case where recording could take a couple of minutes to kick in
- **Smoother, snappier screen** — Menus and animations on the head unit feel noticeably more fluid

---

### v24.0 — June 2026: Blind Spot View, Recording Layouts, Redesigned Dashboard

**✨ New Features**
- **Blind Spot — see alongside the car when you signal** — Flip on a turn signal and a floating window pops up with an intelligently blended view of that side of the car, merging the side and rear cameras into one wide, natural picture. Size it, pin it to any corner, and fine-tune it from **RoadSense → Blind Spot**
- **Choose what your recordings show** — A new recording layout option captures the forward dashcam view together with the full 360° camera feed
- **Redesigned web app dashboard** — Fresh, cleaner look that's easier to read at a glance

**⚡ Optimizations & Fixes**
- **Sharper road hazard detection** — RoadSense is better at spotting speed breakers and potholes
- **Accurate battery health for plug-in hybrids**
- **Web app fits your phone properly** — Pages adapt to portrait or landscape with nothing cut off

---

### v23.0 — June 2026: RoadSense, App Lock, Recording Modes from the Overlay

**✨ New Features**
- **RoadSense — get a heads-up before the bumps** — Your car learns the speed breakers and potholes on the roads you drive and warns you as you approach. Everything stays on your device by default; optionally share hazards with other drivers
- **App Lock with a PIN** — Set a PIN and the app asks for it before opening. Cameras, surveillance, and recordings keep running in the background as normal
- **Switch recording modes right from the overlay** — Change recording mode and start recording with a single tap

**⚡ Optimizations & Fixes**
- **More resilient recording** — No longer stops by itself in certain rare timing situations
- **Smoother telemetry overlay** — The speed/gear/GPS stamp burned into recordings is lighter on the system
- **Recordings now move in the right direction** — **Next** takes you to the newer recording and **Previous** to the older one
- **No more false charging alerts** — Fixed a bug that sent charging notifications every couple of hours

---

### v22.0 — June 2026: Fisheye Correction, Per-Camera Zoom, Location Search

**✨ New Features**
- **Fisheye correction for the cameras** — Straightens out the curve on the wide-angle cameras. Set independently for **Vehicle On** (driving) and **Vehicle Off** (parked) recordings
- **Zoom into a single camera** — Tap any camera to zoom in while the others stay where they are. Zoom level is remembered per camera
- **Search recordings by place** — Type a city, area, or landmark and the list filters as you type

**⚡ Optimizations & Fixes**
- **OEM Dashcam streaming fix** — Fixed the live stream not starting or freezing shortly after opening
- **Faster Recordings and Trips pages** — Both open noticeably quicker, especially on slower head units

---

### v21.0 — June 2026: OEM Dashcam Recording & Streaming

**✨ New Features**
- **OEM Dashcam recording — vehicle on** — The factory forward camera writes its own `dvr_*.mp4` clips alongside the panoramic dashcam. Pick **Continuous** or **Smart** from **Settings → Recording → Dashcam**
- **OEM Dashcam recording — vehicle off** — The same two modes apply to parked surveillance, with identical Safe Locations / Schedule / per-camera filters
- **OEM Dashcam streaming** — Stream the forward camera in real time over the web app, with streaming quality controlled separately from recording quality

**⚡ Optimizations & Fixes**
- **Place filter chips on landscape Recordings page** — The landscape head unit now gets the same chip-based filtering as portrait
- **Apply button drives all OEM and quality changes** — Codec, FPS, recording quality, and OEM mode pickers now wait for the Apply pill instead of saving on selection

---

### v20.0 — May 2026: In-Cabin Audio, Continuous Surveillance Recording

> **Important** — after installing this update, do a hard reboot by holding the Volume button for 5 seconds.

**✨ New Features**
- **In-cabin audio recording on every camera clip** — The cabin microphone is captured alongside video on Continuous, Drive, and Proximity Guard recordings. Off by default; toggle from **Settings → Recording → Cabin Audio**
- **Continuous Surveillance recording** — Keep recording the whole time the car is parked, not just when motion fires, with oldest segments rolling off automatically

**⚡ Optimizations & Fixes**
- **Zrok tunnel reachability fix** — Resolved a case where the tunnel could go silently unreachable after a network handover
- **Lower CPU + memory across ACC ON/OFF transitions**
- **Accurate SOH and kWh for PHEVs**
- **SD card detection edge cases** — Fixed mounts where the card was visible to the OS but the daemon reported "no card"
- **BYD Cloud account: country picker replaces region picker** — Korea now uses the right `-kr` host, and the Middle East / Africa node is exposed for the first time. **EU users — re-save your BYD Cloud settings**
- **"Bad magic: expected BGTB" sign-in error fixed**

---

### v19.0 — May 2026: Stutter-Free AI Recording, Richer MQTT, Dashboard Upgrades

**✨ New Features**
- **New optimized AI detection pipeline — zero stutters in recordings** — The encoder pipeline is now fully decoupled from AI inference, so freeze-and-skip frames are gone
- **MQTT payload now carries 60+ fields** — Automatically filtered by validity so consumers never see stale or sentinel values
- **Open / Close Trunk button on the Dashboard**
- **Mini map view on the Dashboard**
- **Draggable floating camera / location button** — Position is remembered between sessions

---

### v18.0 — May 2026: Screen Deterrent, Web Dashboard & USB Storage

**✨ New Features**
- **Screen Deterrent without cloud setup** — A visual deterrent that runs entirely on-device, no BYD Cloud account required. Set your own message, image, or animated GIF
- **Dashboard page in the web app** — Live position, lock / unlock / flash / find-car commands, and at-a-glance battery, range, 12V, ACC, network and lock-state metrics
- **USB Flash Drive as a storage option** — Auto-detects mounted volumes and falls back gracefully when the drive is unplugged

---

### v17.0 — May 2026: New FPS Controls, New Logo, Fullscreen Player

**✨ New Features**
- **Configurable recording frame rate (10 / 15 / 20 / 30 FPS)** — For both Normal and Surveillance recording, with dynamic quality scaling
- **Live streaming up to 30 FPS** — Streaming presets now reach the HAL ceiling (1280×960 @ 30 fps)
- **Brand-new OverDrive logo**
- **Fullscreen video player in Recordings**

**⚡ Optimizations & Fixes**
- **Better Thai (ไทย) translations**
- **THB added as a Trips currency**

---

### v15.0 — May 2026: A Whole New OverDrive

Android app + web companion rebuilt around how you actually use the car. Material 3.

**✨ New Features**
- **Hand-curated translations** — Every screen, button, alert, and the new theme picker / Telegram tier filter polished by hand
- **Real home screen** — Car at a glance, charging, last trip, surveillance, quick actions
- **Five sections, one tap each** — Dashboard · Recordings · Diagnostics · Integrations · Settings
- **Settings hub** — Recording, Surveillance, ABRP, MQTT, Telegram, Tailscale, Themes under one roof
- **Light · Dark · Auto themes** — "Auto" follows the head unit's day/night mode
- **Web companion redesign** — Themed Live View, redesigned Vehicle Control with a 3D viewport, and its own theme picker
- **Telegram severity filter** — Notice / Alert / Critical, with a clear "bot not paired" hint
- **Recordings, reimagined** — Grouped by date, count pills, share-on-tile, refined player

**⚡ Optimizations & Fixes**
- **First-load tunnel fix** — Shows last-known values and a "Stale / Disconnected" pill instead of going to "—"
- **Sharper surveillance thumbnails** — Static parked vehicles can no longer hijack a recording's hero thumbnail
- **Every event has a preview image**
- **"Check for updates" reliability** — Caps at 12s
- **Rotation-ready** — Layouts respond properly to portrait/landscape on the 15.6" display

---

### v14.2 — May 2026: Speak Your Language & More Vehicle Controls

**✨ New Features**
- **Multi-language support** — Every screen, button, alert, popup, push notification, and recording subtitle translated. Out of the box, OverDrive matches your car's language
- **More Vehicle Controls** — Seat Memory Positions (1 / 2 / 3), Daytime Running Lights (DRL), and Speed Limit Warning (SLW), alongside the existing lock, trunk, windows, AC, and seats

**⚡ Optimizations & Fixes**
- **Resolved install failures across several languages** — Character-encoding issues that broke the build on specific locales
- **Refreshed setup-wizard icons**
- **Map markers translated** — Tile labels stay in their local script
- **Sidebar layout robust to long translations**
- **Cleaner short-status words across locales**

---

### v14.0 — May 2026: Surveillance Revamp & Native Push

**✨ New Features**
- **Native Push Notifications** — Alerts straight from the car to your phone, no bot, no chat. Needs a Zrok reserved tunnel; open the URL on your phone, Add to Home Screen, hit **Enable**. Per-device, per-tier muting
- **Surveillance Overhaul** — Rebuilt around persistent **Actors** tracked across frames and cameras with class, peak severity, and proximity
  - **Three-tier severity** (Notice / Alert / Critical) on separate push channels
  - **Hero thumbnail per recording** at the peak-threat moment
  - **Per-segment heroes** for long events
  - **Cleaner copy** — proper plurals
  - **Two-stage banners** — quick text at start, rich banner at close
  - **Static-aware gate** — parked cars cap at NOTICE, still persons stay CRITICAL
- **Recording Library Redesign** — 2-col grid of 16:10 tiles with severity badges + actor pills, week-strip calendar
- **Web Events Filtering** — Three chip rows (What / Severity / Distance)
- **Sunshade Controls** on the Vehicle Control page

**⚡ Optimizations & Fixes**
- **Fixed Duplicate Recordings**
- **Fixed Broken Recordings** — Reliable across rapid ACC/gear transitions and SD card hiccups
- **Fixed Charging** — Indicator updates during active charging; kW reads true value

---

### v13.0 — May 2026: Tailscale Tunnel, More Vehicle Models & Window Levels

**✨ New Features**
- **Tailscale Tunnel Support** — A new remote-access option alongside Zrok and Cloudflared
- **New Vehicle 3D Models** — BYD Seal, Seal U, Seal U DM-i, Dolphin, Atto 3, Han, Tang, M6, Seagull, and Destroyer 05
- **Partial Window Controls** — Open or close each window to a chosen level (¼, ½, ¾, full)

**⚡ Optimizations & Fixes**
- **Fixed ACC Off / ACC On Recording**
- **3D Surround View Polished**
- **Lock/Unlock Status Reliability** — Now updates correctly even when the car is asleep, using BYD Cloud as a fallback

---

### v12 — May 2026: Vehicle Control, ROI Scheduling & Cloud Sync

**✨ New Features**
- **Vehicle Control Page** — Interactive dashboard featuring a 3D BYD Seal model with customizable body color, real-time remote controls (lock/unlock, trunk, windows, AC, seat heating/cooling via BYD Cloud + local HAL), live state sync with animated indicators, and an experimental 3D surround view using fisheye camera projection (might not work or lag)
- **Surveillance ROI & Schedule Selection** — Define regions of interest and time-based schedules for surveillance activation, reducing unnecessary recordings and false triggers
- **MQTT Listener Updates from BYD Cloud** — Real-time push notifications from BYD Cloud via MQTT subscription, enabling state sync

**⚡ Optimizations & Fixes**
- **Fixed Wrong Camera Bug** — Resolved camera feed mismatch issue; added manual camera ID selection option for vehicles with non-standard configurations
- **Improved Surveillance Accuracy** — Reduced false positives through refined motion detection and AI gating logic
- **Improved SOH Calculation** — Enhanced State of Health estimation logic with option to reset SOH for recalibration after battery service or firmware updates
- **Fixed No Video Signal in OEM Dashcam** — Resolved issue where the OEM dashcam feed would show no signal under certain initialization conditions

---

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

OverDrive's own code is MIT-licensed. Bundled third-party components (cloudflared,
zrok, sing-box, tailscale, YOLO11n, and others) remain under their own licenses —
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
