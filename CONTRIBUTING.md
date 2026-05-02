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
# Clone your fork
git clone https://github.com/yash-srivastava/Overdrive-release.git
cd overdrive

# Build the project
./gradlew assembleDebug

# Run tests
./gradlew test
```

### Running on a Device

The app is designed for BYD vehicles with specific hardware APIs. For general development and UI work, the Android emulator works fine. Hardware-specific features (camera, vehicle data, surveillance) require a compatible device.

## Project Structure

```
app/src/main/
├── java/com/overdrive/app/
│   ├── abrp/              # ABRP telemetry integration
│   ├── byd/               # BYD vehicle API integration
│   ├── camera/            # Camera management (AVM, panoramic)
│   ├── daemon/            # Background services & Telegram bot
│   ├── logging/           # Logging configuration
│   ├── monitor/           # Vehicle, battery & network monitoring
│   ├── mqtt/              # MQTT messaging
│   ├── recording/         # Recording mode management
│   ├── server/            # HTTP/TCP/IPC server & API handlers
│   ├── storage/           # Storage management
│   ├── streaming/         # GPU-accelerated streaming
│   ├── surveillance/      # Surveillance engine & motion detection
│   ├── telemetry/         # Telemetry overlay rendering
│   ├── trips/             # Trip detection, scoring & analytics
│   ├── ui/                # Activities, fragments & UI controllers
│   └── updater/           # In-app update system
├── cpp/surveillance/       # Native C++ (motion pipeline, texture tracking)
├── assets/web/             # Web UI (HTML/JS for local dashboards)
└── res/                    # Android resources (layouts, drawables)
```

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
