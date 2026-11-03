# Contributing to OverDrive

Thanks for your interest in contributing to OverDrive! This guide will help you get started.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [How to Contribute](#how-to-contribute)
- [Pull Request Process](#pull-request-process)
- [Coding Standards](#coding-standards)
- [Reporting Bugs](#reporting-bugs)
- [Feature Requests](#feature-requests)
- [License](#license)

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment. Be kind, constructive, and professional in all interactions.

## Getting Started

1. Fork the repository
2. Clone your fork locally
3. Create a new branch for your work
4. Make your changes
5. Submit a pull request

## Development Setup

### Prerequisites

- **Android Studio** (latest stable recommended)
- **JDK 17** or higher
- **Android SDK** with API level matching the project's `targetSdk`
- **Android NDK** (required for native C++ components)
- **Gradle** (wrapper included in the project)

### Building the Project

```bash
# Clone your fork (replace <your-username>)
git clone https://github.com/<your-username>/Overdrive-release.git
cd Overdrive-release

# Build the project
./gradlew assembleDebug

# Run tests
./gradlew test
```

### Windows notes

Several unit tests read source and web files and assert on their text, and one web folder is a git symlink.
Git for Windows defaults break both, so clone with these settings:

```bash
git clone -c core.autocrlf=false -c core.symlinks=true https://github.com/<your-username>/Overdrive-release.git
```

- **Line endings:** with the default `core.autocrlf=true`, every file is checked out with CRLF line endings
  and many tests fail because they no longer find the text they look for.
- **Symlinks:** `app/src/main/assets/web/local/shared` is a symlink to `../shared`. Creating symlinks on
  Windows needs Developer Mode (or an elevated shell). Without it the link becomes a plain text file and
  the tests that read through it fail. The main-asset staging task preserves this source alias
  while packaging only `web/shared`; the daemon still accepts legacy `/local/shared/` URLs.
- **JDK:** the JDK bundled with Android Studio works for the build.

### Running on a Device

The app is designed for BYD vehicles with specific hardware APIs. For general development and UI work, the Android emulator works fine. Hardware-specific features (camera, vehicle data, surveillance) require a compatible device.

## How the App Is Structured

OverDrive runs as two cooperating processes. The camera, recording, streaming and the HTTP server on port 8080 run in a
background daemon started through `app_process`; the UI app is a separate process. They talk over loopback HTTP and a
shared configuration file, so code under `server/` and `daemon/` runs in the daemon, not in the UI process.

## Project Structure

```
app/src/main/
├── java/com/overdrive/app/
│   ├── abrp/              # ABRP telemetry integration
│   ├── ai/                # Object detection
│   ├── auth/              # Device token, JWT sessions & PIN lock
│   ├── automation/        # Automation rules, conditions & actions
│   ├── battery/           # Battery chemistry & charging-session quality
│   ├── bridge/            # Vehicle & performance data bridges
│   ├── byd/               # BYD vehicle API integration
│   ├── camera/            # Camera management (AVM, panoramic)
│   ├── charging/          # Charging sessions, tariffs & API
│   ├── client/            # Clients for the camera daemon
│   ├── communication/     # Remote voice & cabin audio communication
│   ├── community/         # Community automation catalogue
│   ├── config/            # App & daemon configuration, backup
│   ├── daemon/            # Background services & Telegram bot
│   ├── genai/             # AI assistant, insights & routines
│   ├── geo/               # Geocoding & location snapshots
│   ├── launcher/          # Daemon & app launchers (ADB, cluster cast)
│   ├── logging/           # Logging configuration
│   ├── monitor/           # Vehicle, battery & network monitoring
│   ├── mqtt/              # MQTT messaging
│   ├── navmap/            # Navigation map & cluster projection
│   ├── network/           # Hotspot & cellular relay
│   ├── notifications/     # Notification bus & sinks (push, Telegram, history)
│   ├── onboarding/        # First-run tours & setup coaching
│   ├── overlay/           # On-screen status & message overlays
│   ├── parking/           # Parking intelligence
│   ├── power/             # ACC-off & parked power handling
│   ├── proximity/         # Proximity (parking radar) recording trigger
│   ├── receiver/          # Boot & screen broadcast receivers
│   ├── recording/         # Recording mode management
│   ├── remote/            # Remote developer view
│   ├── roadsense/         # Road hazard detection
│   ├── server/            # HTTP/TCP/IPC server & API handlers
│   ├── services/          # Android services (keep-alive, auto-start, cabin audio)
│   ├── shell/             # Hidden-API bypass & shell permissions
│   ├── storage/           # Storage management
│   ├── streaming/         # GPU-accelerated streaming
│   ├── surveillance/      # Surveillance engine & motion detection
│   ├── telegram/          # Telegram bot interfaces & messages
│   ├── telemetry/         # Telemetry overlay rendering
│   ├── telenav/           # Telenav navigation integration
│   ├── trips/             # Trip detection, scoring & analytics
│   ├── ui/                # Activities, fragments & UI controllers
│   ├── updater/           # In-app update system
│   ├── util/              # Shared helpers (HTTP client, storage paths)
│   └── wireguard/         # WireGuard tunnel configuration
├── cpp/surveillance/       # Native C++ (motion pipeline, texture tracking)
├── assets/web/             # Web UI (HTML/JS for local dashboards)
└── res/                    # Android resources (layouts, drawables)
```

The list covers the main packages; a handful of small ones are omitted.

Other top-level locations:

- `app/src/test/java/com/overdrive/app/`: unit tests, laid out like the main packages. Most test pure static helpers;
  some "contract" tests read source or web files and assert on their text.
- `tools/`: scripts that build the bundled `libtailscale` and `libwireguard` libraries.
- `stubs-bydauto/`: stub classes of the BYD vehicle SDK so the project compiles without the real SDK.

### Companion launcher locale checks

This public Gradle build contains `:app` and `:dilink-probe`; a separate companion
launcher is not included. The app's Czech/Hebrew tests cover the resources and
runtime wiring owned by `:app`. Launcher-specific assertions are retained in
[`tools/tests/companion-launcher-locales`](tools/tests/companion-launcher-locales/README.md)
and require a real, clean, versioned companion checkout:

```bash
java tools/tests/companion-launcher-locales/LauncherLocaleContract.java /path/to/actual/launcher-module
```

This command fails if the companion or a required input is missing; it does not
skip the checks or create replacement sources. A release containing both apps
must record both source revisions, this contract result, and the companion's
own build/tests. A passing public app suite alone does not establish companion
locale acceptance. See the tool's README for inputs and ownership boundaries.

## How to Contribute

### Good First Issues

Look for issues labeled `good first issue` or `help wanted`. These are a great starting point for new contributors.

### Areas Where Help is Appreciated

- Bug fixes and stability improvements
- UI/UX improvements
- Documentation and code comments
- Test coverage
- Performance optimizations
- Translations / localization

## Pull Request Process

1. **Branch naming**: Use descriptive branch names like `fix/camera-crash`, `feature/trip-export`, or `docs/update-readme`.
2. **Keep PRs focused**: One feature or fix per PR. Smaller PRs are easier to review and merge.
3. **Write a clear description**: Explain what changed, why, and how to test it.
4. **Update documentation**: If your change affects behavior, update relevant docs.
5. **Ensure the build passes**: Run `./gradlew assembleDebug` and `./gradlew test` before submitting.
6. **Respond to feedback**: Be open to review comments and iterate on your changes.

### PR Template

When opening a pull request, please include:

```
## What does this PR do?
Brief description of the change.

## Why is this change needed?
Context and motivation.

## How to test
Steps to verify the change works correctly.

## Screenshots (if applicable)
```

## Coding Standards

### Java

- Follow standard Java conventions
- Use meaningful variable and method names
- Add Javadoc comments for public APIs
- Keep methods focused and reasonably sized

### Kotlin

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Prefer `val` over `var` where possible
- Use Kotlin idioms (scope functions, extension functions) where they improve readability

### C++ (Native)

- Follow the existing style in `app/src/main/cpp/`
- Use descriptive names for functions and variables
- Comment complex logic, especially GPU pipeline code

### General

- No hardcoded secrets or API keys — use configuration files or environment variables
- Handle errors gracefully with proper logging
- Keep log output meaningful (avoid excessive debug logging in production paths)
- Write thread-safe code, especially in daemon and surveillance components

## Reporting Bugs

When filing a bug report, please include:

- **Device model** and Android version
- **App version** or commit hash
- **Steps to reproduce** the issue
- **Expected behavior** vs. actual behavior
- **Logs** (logcat output if available)
- **Screenshots or screen recordings** if it's a UI issue

## Feature Requests

Feature requests are welcome. Please open an issue and include:

- A clear description of the feature
- The problem it solves or the use case it enables
- Any ideas on implementation approach (optional but helpful)

## License

By contributing, you agree that your contributions will be licensed under the same license as the project.

---

Thanks for helping make OverDrive better!
