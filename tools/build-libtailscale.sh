#!/usr/bin/env bash
# Reproducibly build the Android/arm64 Tailscale executable packaged as libtailscale.so.
#
# It is intentionally named .so so Android extracts it from jniLibs with executable
# permissions; the artifact is a static PIE containing both tailscale and tailscaled.
# The local patch keeps the existing loopback-TCP local API, adds an Android ACME
# resolver fallback, and stops userspace netstack from raw-forwarding tailnet
# connections into device loopback (only Serve-published ports stay reachable).
# Do not add ts_omit_acme: tailnet HTTPS depends on it.
set -euo pipefail

TS_VERSION="${1:-v1.96.4}"
GO_TOOLCHAIN="${GO_TOOLCHAIN:-go1.26.2}"
NDK_VERSION="${NDK_VERSION:-26.1.10909125}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libtailscale.so"
PATCH="$ROOT/tools/patches/tailscale-tcp-socket.patch"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TAGS=ts_include_cli
TAGS+=,ts_omit_aws,ts_omit_kube,ts_omit_cloud,ts_omit_synology
TAGS+=,ts_omit_bird,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved
TAGS+=,ts_omit_desktop_sessions,ts_omit_systray,ts_omit_colorable
TAGS+=,ts_omit_completion,ts_omit_completion_scripts,ts_omit_webbrowser
TAGS+=,ts_omit_qrcodes,ts_omit_clientupdate,ts_omit_drive,ts_omit_taildrop
TAGS+=,ts_omit_webclient,ts_omit_tap,ts_omit_tpm,ts_omit_capture
TAGS+=,ts_omit_debugportmapper,ts_omit_debugeventbus,ts_omit_doctor
TAGS+=,ts_omit_hujsonconf,ts_omit_identityfederation,ts_omit_oauthkey
TAGS+=,ts_omit_sdnotify

for command in git go upx grep; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "FATAL: required command not found: $command" >&2
        exit 1
    fi
done

# Go's Android PIE currently carries a section-string table that UPX 5.0.2 rejects
# as "bad e_shstrtab". The pinned NDK llvm-strip normalizes/removes those unused
# section headers before packing. Pinning it also makes the successful recipe
# independent of whichever host strip happens to be first on PATH.
LLVM_STRIP="${LLVM_STRIP:-}"
if [ -z "$LLVM_STRIP" ]; then
    SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [ -z "$SDK_ROOT" ]; then
        echo "FATAL: set ANDROID_SDK_ROOT/ANDROID_HOME or LLVM_STRIP" >&2
        exit 1
    fi
    LLVM_STRIP="$(find \
        "$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt" \
        -name llvm-strip -print 2>/dev/null | head -1)"
fi
if [ -z "$LLVM_STRIP" ] || [ ! -x "$LLVM_STRIP" ]; then
    echo "FATAL: NDK $NDK_VERSION llvm-strip not found; set LLVM_STRIP explicitly" >&2
    exit 1
fi

GO_VERSION_OUTPUT="$(env GOTOOLCHAIN="$GO_TOOLCHAIN" go version)"
case "$GO_VERSION_OUTPUT" in
    *" $GO_TOOLCHAIN "*) ;;
    *)
        echo "FATAL: expected $GO_TOOLCHAIN, got: $GO_VERSION_OUTPUT" >&2
        exit 1
        ;;
esac

UPX_MAJOR="$(upx --version 2>/dev/null | head -1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
if [ -z "$UPX_MAJOR" ] || [ "$UPX_MAJOR" -lt 5 ]; then
    echo "FATAL: need UPX >= 5 after llvm-strip normalization" >&2
    exit 1
fi

echo "==> cloning tailscale $TS_VERSION"
git clone --depth 1 --branch "$TS_VERSION" \
    https://github.com/tailscale/tailscale.git "$WORK/tailscale"

echo "==> applying Android loopback/ACME patch"
git -C "$WORK/tailscale" apply --check "$PATCH"
git -C "$WORK/tailscale" apply "$PATCH"

echo "==> testing patched upstream packages"
(
    cd "$WORK/tailscale"
    env GOTOOLCHAIN="$GO_TOOLCHAIN" go test \
        ./ipn/ipnauth ./ipn/ipnlocal ./safesocket ./cmd/tailscale/cli ./wgengine/netstack
)

echo "==> building android/arm64 with $GO_TOOLCHAIN"
(
    cd "$WORK/tailscale"
    env GOTOOLCHAIN="$GO_TOOLCHAIN" GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
        go build -tags "$TAGS" -trimpath -ldflags "-s -w" \
        -o "$WORK/libtailscale.so" ./cmd/tailscaled
)

echo "==> normalizing ELF with NDK $NDK_VERSION llvm-strip"
"$LLVM_STRIP" --strip-all "$WORK/libtailscale.so"

echo "==> compressing with $(upx --version | head -1)"
upx --best -q "$WORK/libtailscale.so"
upx -t -q "$WORK/libtailscale.so"

echo "==> verifying unpacked feature set"
upx -d -q -o "$WORK/check.bin" "$WORK/libtailscale.so"
if ! LC_ALL=C grep -qa "acme-v02.api.letsencrypt.org" "$WORK/check.bin"; then
    echo "FATAL: ACME client missing from built binary" >&2
    exit 1
fi
if ! LC_ALL=C grep -qa "tls-terminated-tcp" "$WORK/check.bin"; then
    echo "FATAL: TLS-terminated TCP Serve support missing from built binary" >&2
    exit 1
fi
if ! LC_ALL=C grep -qa "proxy-protocol" "$WORK/check.bin"; then
    echo "FATAL: PROXY protocol Serve support missing from built binary" >&2
    exit 1
fi

install -m 0644 "$WORK/libtailscale.so" "$OUT"
echo "==> wrote $OUT ($(wc -c < "$OUT") bytes)"
shasum -a 256 "$OUT"
