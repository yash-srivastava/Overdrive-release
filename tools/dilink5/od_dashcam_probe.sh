#!/system/bin/sh
# OverDrive — DiLink 5 forward-dashcam probe (Step 0, no app changes).
#
# Finds which QCarCam input is the forward dashcam, and whether the shipped
# fast_cam_capture can stream it next to the four surround cameras.
#   1. Read-only discovery: props, camera configs, factory DVR state.
#   2. After you type YES: stops OverDrive's camera daemon cleanly, opens each
#      candidate input for a few seconds, then restarts the daemon.
# Saves 2 still frames from every input that streams (may show the cabin).
#
# Run parked (P), ignition ON, OverDrive app CLOSED, no reverse/360 view on
# screen. Takes ~5-8 min. Other camera views may flicker while it runs.
#
#   adb push od_dashcam_probe.sh /sdcard/Download/
#   adb shell sh /sdcard/Download/od_dashcam_probe.sh
# Detached (survives adb drops):
#   adb shell "setsid nohup sh /sdcard/Download/od_dashcam_probe.sh -y \
#     > /sdcard/Download/od_probe_console.txt 2>&1 < /dev/null &"
#
# Options: -y (skip the prompt)   --discover-only (no daemon stop, no camera opens)
# Env:     IDS="4 5 6"  AVM=0,1,2,3  GAP=15  NO_RECORD=1

YES=0
DISCOVER_ONLY=0
for arg in "$@"; do
    case "$arg" in
        -y|--yes) YES=1 ;;
        --discover-only) DISCOVER_ONLY=1 ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) echo "unknown option: $arg"; exit 2 ;;
    esac
done

FRAME=4992000   # fast_cam_capture hard-codes 1920x1300 UYVY buffers for every input
PORT=19876      # CameraDaemon TcpCommandServer
GAP=${GAP:-15}  # the daemon's own cross-process QCarCam reacquire cooldown
LEGACY=/data/local/tmp
ALT=/data/user_de/0/com.android.shell/overdrive
TS=$(date +%Y%m%d_%H%M%S)

CONSOLE=""
LOCK=""
ROOT=""
OUT=""
BIN=""
GUARD=""
WE_STOPPED=0
STOP_CONFIRMED=0
RESTORED=0
STUCK=0
LAST_OPENED=0
ABORTED=0
CHILD=""
CDR_PID_BEFORE=""
DVR_BASE=0
STREAMING=""

# ---------- helpers ----------

say() {
    printf '%s\n' "$*"
    [ -n "$CONSOLE" ] && printf '%s\n' "$*" >> "$CONSOLE"
}
die() { say "ERROR: $*"; exit 1; }
# toybox ps ARGS is argv with argv[0]'s directory stripped. Match argv fields exactly:
# a wrapper shell whose command text merely mentions a name must not count.
ps_args() { ps -A -o PID,ARGS 2>/dev/null; }
daemon_running() { ps_args | awk '$1 ~ /^[0-9]+$/ && $2 == "byd_cam_daemon" { f = 1 } END { exit !f }'; }
watchdog_running() {
    ps_args | awk -v p="$ROOT/start_cam_daemon.sh" '$1 ~ /^[0-9]+$/ && $2 ~ /(^|\/)(mk)?sh$/ && $3 == p { f = 1 } END { exit !f }'
}
capture_running() { ps_args | awk '$1 ~ /^[0-9]+$/ && $2 ~ /(^|\/)(fast_cam_capture|qcarcam_test)$/ { f = 1 } END { exit !f }'; }
alive() {
    if [ -r "/proc/$1/stat" ]; then
        st=$(awk '{print $3}' "/proc/$1/stat" 2>/dev/null)
        [ -n "$st" ] && [ "$st" != Z ]
    else
        kill -0 "$1" 2>/dev/null
    fi
}
is_num() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; return 0; }
valid_ids() { echo "$1" | grep -q -E '^[0-9]{1,3}(,[0-9]{1,3}){3,4}$'; }
cfg_get() {
    grep -o -E "\"$1\" *: *\"[^\"]*\"" "$ROOT/overdrive_config.json" 2>/dev/null \
        | head -n 1 | sed 's/.*: *"\(.*\)"$/\1/'
}

# ---------- factory DVR evidence ----------

dvr_snapshot() {
    for r in /storage/emulated/0 /storage/*; do
        case "$r" in /storage/self|/storage/emulated) continue ;; esac
        [ -d "$r" ] || continue
        for d in Recorder DVR CDR 行车记录仪 DashCam CarRecord Record Video Movies DCIM; do
            [ -d "$r/$d" ] && find "$r/$d" -maxdepth 4 -type f -mmin -15 2>/dev/null
        done
    done | grep -v -i overdrive | while IFS= read -r f; do
        stat -c '%s|%Y|%n' "$f" 2>/dev/null
    done | sort -t'|' -k3 > "$1"
}
# Files that are new, grew, or were touched between two snapshots.
dvr_growth() {
    awk -F'|' 'FILENAME == ARGV[1] { s[$3] = $1; t[$3] = $2; next }
        { if (!($3 in s) || $1 > s[$3] || $2 > t[$3]) n++ }
        END { print n + 0 }' "$1" "$2"
}

# ---------- discovery (read-only) ----------

discover() {
    say "== Discovery (read-only)"
    {
        echo "== props"
        for p in ro.product.model ro.product.name ro.product.device ro.build.product \
                 ro.build.fingerprint ro.build.version.release ro.build.version.sdk \
                 ro.vehicle.type ro.board.platform ro.hardware persist.overdrive.cams; do
            echo "$p=$(getprop "$p")"
        done
        echo "== camera-related props"
        getprop | grep -i -E 'cam|ais|dvr|cdr|avm|evs' | head -n 200
        echo "== libs"
        ls -l /vendor/lib64/libais_client.so /system/lib64/libais_test_util.so 2>&1
        echo "== processes"
        ps -A -o PID,USER,NAME,ARGS 2>/dev/null | grep -i -E 'ais|qcarcam|camera|cdr|dvr|evs|avm|fast_cam|byd_cam' | grep -v -F grep
        echo "== HALs"
        lshal 2>/dev/null | grep -i -E 'evs|camera|ais' | head -n 50
        echo "== packages"
        pm list packages 2>/dev/null | grep -i -E 'cdr|dvr|record|avm|camera|360'
        echo "== com.byd.cdr"
        dumpsys package com.byd.cdr 2>/dev/null | grep -E 'versionName|versionCode|enabled=|pkgFlags' | head -n 20
        echo "pid=$(pidof com.byd.cdr 2>/dev/null)"
        echo "== OverDrive"
        echo "root=$ROOT apk=$APK guard=${GUARD:-none}"
        echo "binary=$SRC_BIN sha256=$BIN_SHA"
        echo "cameraMode=$(cfg_get cameraMode) dilink5CameraMapping=$(cfg_get dilink5CameraMapping) modelId=$(cfg_get modelId)"
        echo "activeMode=$(cat "$ROOT/overdrive_active_vehicle_mode" 2>/dev/null)"
        ls -l "$ROOT/start_cam_daemon.sh" "$SENTINEL" "$ROOT/overdrive_parked_shutdown" 2>&1
        echo "surround=$AVM ($AVM_SRC) candidates=$CANDS"
    } > "$OUT/discovery.txt" 2>&1
    dumpsys media.camera 2>&1 | head -n 800 > "$OUT/dumpsys_media_camera.txt"
    for f in "$ROOT/cam_daemon.log" "$ROOT/byd_cam_daemon.log"; do
        [ -f "$f" ] && grep -i -E 'dilink|qcarcam|fast_?cam|camera|mapping|ais' "$f" 2>/dev/null \
            | tail -n 400 > "$OUT/$(basename "$f" .log)_camera_tail.txt"
    done
    # Camera/AIS config text files: input ids and names may be listed here.
    find /vendor/etc /odm/etc -maxdepth 4 -type f -size -1024k \
        \( -name '*.xml' -o -name '*.conf' -o -name '*.cfg' -o -name '*.json' -o -name '*.ini' -o -name '*.rc' -o -name '*.txt' \) \
        2>/dev/null | while IFS= read -r f; do
        case "$f" in
            *ais*|*qcarcam*|*camera*|*cam_*|*Camera*) ;;
            *) grep -q -i -E 'qcarcam|ais_server|input_id' "$f" 2>/dev/null || continue ;;
        esac
        mkdir -p "$OUT/vendor_cfg$(dirname "$f")" 2>/dev/null
        cp "$f" "$OUT/vendor_cfg$f" 2>/dev/null
    done
    CDR_PID_BEFORE=$(pidof com.byd.cdr 2>/dev/null)
    dvr_snapshot "$OUT/dvr_base_a.txt"
    sleep 12
    dvr_snapshot "$OUT/dvr_base_b.txt"
    DVR_BASE=$(dvr_growth "$OUT/dvr_base_a.txt" "$OUT/dvr_base_b.txt")
    say "   car: $(getprop ro.product.model) / $(getprop ro.build.product) / $(getprop ro.vehicle.type)"
    say "   surround IDs: $AVM ($AVM_SRC); candidates: $CANDS"
    say "   factory DVR: pid ${CDR_PID_BEFORE:-none}; recording files changing: $DVR_BASE"
}

# ---------- camera daemon stop / restore ----------

stop_daemon() {
    if ! daemon_running; then
        capture_running && die "a capture process is running without the daemon; start then stop the camera daemon from the app (or reboot), then re-run"
        say "Camera daemon is not running — leaving it as it is."
        return 0
    fi
    command -v nc >/dev/null 2>&1 || die "no 'nc' on this head unit; stop the camera daemon from the app, then re-run"
    cp "$ROOT/start_cam_daemon.sh" "$OUT/start_cam_daemon.sh.bak" 2>/dev/null \
        || say "WARN: no watchdog script found — start the camera daemon from the app afterwards."
    echo "disabled by od_dashcam_probe at $(date)" > "$SENTINEL" && chmod 666 "$SENTINEL" 2>/dev/null
    WE_STOPPED=1
    say "== Stopping the camera daemon (clean shutdown, ~20 s on DiLink 5)"
    resp=$(echo '{"cmd":"shutdown"}' | nc -q 2 -w 3 127.0.0.1 "$PORT" 2>&1)
    case "$resp" in
        *'"status":"ok"'*) ;;
        *)
            rm -f "$SENTINEL"
            WE_STOPPED=0
            die "shutdown not accepted (${resp:-no reply}); nothing was probed. Stop the camera daemon from the app, then re-run."
            ;;
    esac
    n=0
    while daemon_running && [ $n -lt 90 ]; do sleep 1; n=$((n + 1)); done
    if daemon_running; then
        STUCK=1
        die "daemon still shutting down after 90 s; nothing was probed. Start it from the app in a minute."
    fi
    m=0
    while capture_running && [ $m -lt 20 ]; do sleep 1; m=$((m + 1)); done
    if capture_running; then
        STUCK=1
        die "the daemon's capture child is still alive; nothing was probed. Start the camera daemon from the app (it cleans this up)."
    fi
    STOP_CONFIRMED=1
    LAST_OPENED=1
    say "   stopped after $((n + m)) s"
}

relaunch_watchdog() {
    rm -f "$ROOT/camera_daemon.lock" "$ROOT/cam_watchdog.pid"
    rm -rf "$ROOT/cam_watchdog.lock"
    cp "$OUT/start_cam_daemon.sh.bak" "$ROOT/start_cam_daemon.sh" && chmod 755 "$ROOT/start_cam_daemon.sh"
    (
        # Undo this script's signal ignores: dispositions survive exec, and the
        # daemon must start exactly as the app starts it.
        trap - INT TERM HUP PIPE
        [ "$ROOT" != "$LEGACY" ] && export OVERDRIVE_SCRATCH="$ROOT" TMPDIR="$ROOT"
        if command -v setsid >/dev/null 2>&1; then
            exec setsid nohup sh "$ROOT/start_cam_daemon.sh" > /dev/null 2>&1 < /dev/null
        fi
        exec nohup sh "$ROOT/start_cam_daemon.sh" > /dev/null 2>&1 < /dev/null
    ) &
    k=0
    while ! daemon_running && [ $k -lt 30 ]; do sleep 1; k=$((k + 1)); done
    if daemon_running; then
        say "Camera daemon restarted."
    else
        say "WARN: camera daemon did not come back in 30 s — start it from the OverDrive app."
    fi
}

restore_daemon() {
    trap '' INT TERM HUP
    if [ -n "$CHILD" ] && alive "$CHILD"; then
        for sig in INT TERM KILL; do
            kill -$sig "$CHILD" 2>/dev/null
            k=0
            while alive "$CHILD" && [ $k -lt 5 ]; do sleep 1; k=$((k + 1)); done
            alive "$CHILD" || break
        done
        LAST_OPENED=1
    fi
    if [ "$WE_STOPPED" = 1 ] && [ "$RESTORED" = 0 ]; then
        RESTORED=1
        if [ "$STUCK" = 1 ]; then
            say "Leaving the camera daemon stopped — start it from the OverDrive app."
        elif [ "$STOP_CONFIRMED" = 0 ]; then
            say "Camera daemon was still shutting down — start it from the OverDrive app in a minute."
        else
            # Cool down while the sentinel still holds off the app's health check,
            # then clear it and relaunch with no gap for a second launcher to race.
            [ "$LAST_OPENED" = 1 ] && sleep "$GAP"
            if daemon_running; then
                say "Camera daemon is already running again."
            else
                rm -f "$SENTINEL"
                if [ ! -f "$OUT/start_cam_daemon.sh.bak" ]; then
                    say "Start the camera daemon from the OverDrive app."
                elif watchdog_running; then
                    say "Camera watchdog already running."
                else
                    relaunch_watchdog
                fi
            fi
        fi
    fi
    [ -n "$LOCK" ] && rmdir "$LOCK" 2>/dev/null
}

# ---------- probe runs ----------

# run NAME SECONDS ARGS... : one fast_cam_capture session, bounded and logged.
run_fcc() {
    name=$1
    secs=$2
    shift 2
    [ "$ABORTED" = 1 ] && return 1
    if daemon_running || capture_running; then
        say "!! OverDrive's camera stack came back (was the app opened?) — stopping the probe."
        ABORTED=1
        return 1
    fi
    [ "$LAST_OPENED" = 1 ] && sleep "$GAP"
    out=$OUT/runs/$name.txt
    t0=$(date +'%m-%d %H:%M:%S.000')
    say "   [$name] $*"
    (
        export LD_LIBRARY_PATH="/vendor/lib64:/system/lib64:$ROOT"
        [ -n "$GUARD" ] && export LD_PRELOAD="$GUARD"
        [ "$ROOT" != "$LEGACY" ] && export OVERDRIVE_SCRATCH="$ROOT" TMPDIR="$ROOT"
        cd "$OUT" && exec "$BIN" "$@"
    ) > "$out" 2>&1 < /dev/null &
    pid=$!
    CHILD=$pid
    n=0
    limit=$((secs + 20))
    while alive "$pid" && [ $n -lt $limit ]; do sleep 1; n=$((n + 1)); done
    if alive "$pid"; then
        say "   ! still running after ${limit}s — asking it to stop"
        for sig in INT TERM; do
            kill -$sig "$pid" 2>/dev/null
            n=0
            while alive "$pid" && [ $n -lt 5 ]; do sleep 1; n=$((n + 1)); done
            alive "$pid" || break
        done
        if alive "$pid"; then
            say "   ! force-stopping it; if camera views stay black, switch the car off and on"
            kill -KILL "$pid" 2>/dev/null
        fi
    fi
    wait "$pid" 2>/dev/null
    echo "exit=$?" >> "$out"
    CHILD=""
    logcat -d -v threadtime -T "$t0" 2>/dev/null \
        | grep -v -E '(ais_get_frame|ais_client_process_cmd):[0-9]+ ERR .* 12$' \
        | grep -i -E 'ais|qcarcam|cdr|dvr|camera|avm|evs|fcc_probe' \
        | tail -n 400 > "$OUT/runs/$name.logcat.txt"
    if grep -q 'started OK' "$out"; then LAST_OPENED=1; else LAST_OPENED=0; fi
    return 0
}

# "frames fps" for a camera ID, from a run's final summary lines.
cam_stat() {
    awk -v id="$2" '$1 == "Camera" && $2 == id && $3 == ":" {
        f = $4; p = $6; gsub(/\(/, "", p); print f, p; exit }' "$1" 2>/dev/null
}
cam_fps() { cam_stat "$1" "$2" | awk '{ print ($2 == "" ? 0 : $2) } END { if (NR == 0) print 0 }'; }

classify() {
    f=$1
    id=$2
    [ -f "$f" ] || { echo "NOT_RUN"; return; }
    grep -q -E '^\[-\] Init failed' "$f" && { echo "INIT_FAILED"; return; }
    grep -q -E "Failed to open camera $id\$" "$f" && { echo "NOT_OPENED"; return; }
    rc=$(grep -E "Buffer config failed for cam $id:" "$f" | head -n 1 | awk '{print $NF}')
    [ -n "$rc" ] && { echo "BUFFER_REJECTED(rc=$rc)"; return; }
    rc=$(grep -E "Start failed for cam $id:" "$f" | head -n 1 | awk '{print $NF}')
    [ -n "$rc" ] && { echo "START_FAILED(rc=$rc)"; return; }
    if grep -q -E "Camera $id started OK" "$f"; then
        st=$(cam_stat "$f" "$id")
        fr=${st%% *}
        if is_num "$fr" && [ "$fr" -gt 0 ]; then
            echo "STREAMING($fr frames, ${st#* } fps)"
        else
            echo "STARTED_NO_FRAMES"
        fi
        return
    fi
    grep -q '^exit=12[67]' "$f" && { echo "BINARY_FAILED"; return; }
    echo "UNKNOWN"
}

record_dump() {
    id=$1
    [ "${NO_RECORD:-0}" = 1 ] && return 0
    avail=$(df -k "$OUT" 2>/dev/null | awk 'NR == 2 { print $4 }')
    if ! is_num "$avail" || [ "$avail" -lt 400000 ]; then
        say "   skip raw frames for $id: low or unknown free space"
        return 0
    fi
    raw=$OUT/dump_$id.tmp
    run_fcc "dump_$id" 1 --cam "$id" --record "$raw" --time 1 || return 1
    sz=$(stat -c %s "$raw" 2>/dev/null)
    is_num "$sz" || sz=0
    frames=$((sz / FRAME))
    if [ $frames -ge 1 ]; then
        keep=2
        [ $frames -lt 2 ] && keep=$frames
        dd if="$raw" of="$OUT/cam_${id}_1920x1300_uyvy.raw" bs=$FRAME skip=$((frames - keep)) count=$keep 2>/dev/null
    fi
    rm -f "$raw"
}

# ---------- summary + packaging ----------

summarize() {
    s=$OUT/summary.txt
    b=$OUT/runs/baseline.txt
    guard_used=no
    [ -n "$GUARD" ] && guard_used=yes
    {
        echo "OverDrive DiLink 5 dashcam probe  $TS"
        echo "car: $(getprop ro.product.model) / $(getprop ro.build.product) / vehicle.type=$(getprop ro.vehicle.type) / Android $(getprop ro.build.version.release)"
        echo "binary sha256: $BIN_SHA   log guard: $guard_used"
        echo "surround IDs: $AVM ($AVM_SRC)"
        echo "factory DVR at start: pid ${CDR_PID_BEFORE:-none}, recording files changing over 12 s: $DVR_BASE"
        if [ "$DISCOVER_ONLY" = 1 ]; then
            echo "discovery only: no cameras were opened"
        else
            if [ -f "$b" ]; then
                line="baseline (4 surround cams):"
                for a in $(echo "$AVM" | tr ',' ' '); do line="$line $a=$(cam_fps "$b" "$a")fps"; done
                echo "$line"
                grep -q -E '^\[-\] Init failed' "$b" && echo "  !! QCarCam refused the client (Init failed) — see runs/baseline.txt"
            fi
            echo
            echo "solo test per candidate (3 s):"
            for id in $CANDS; do
                echo "  $id: $(classify "$OUT/runs/solo_$id.txt" "$id")"
            done
            echo
            echo "with the 4 surround cams (8 s):"
            for id in $STREAMING; do
                f=$OUT/runs/avm_plus_$id.txt
                [ -f "$f" ] || { echo "  $id: not run"; continue; }
                pairs=""
                for a in $(echo "$AVM" | tr ',' ' '); do
                    pairs="$pairs $a=$(cam_fps "$f" "$a")/$(cam_fps "$b" "$a")"
                done
                echo "  $id: cam$id=$(cam_fps "$f" "$id")fps; surround fps now/baseline:$pairs"
                echo "      factory DVR files changing during run: $(cat "$OUT/dvr_$id.growth" 2>/dev/null) (baseline $DVR_BASE); cdr pid ${CDR_PID_BEFORE:-none} -> $(cat "$OUT/dvr_$id.pid" 2>/dev/null)"
                [ -f "$OUT/cam_${id}_1920x1300_uyvy.raw" ] && echo "      raw frames: cam_${id}_1920x1300_uyvy.raw"
            done
            [ -z "$STREAMING" ] && echo "  (no candidate streamed)"
            [ "$ABORTED" = 1 ] && echo "!! probe stopped early — see console.txt"
        fi
        echo
        echo "how to read this:"
        echo "  STREAMING        works with the shipped tool -> check its raw frames for the forward view"
        echo "  BUFFER_REJECTED  input exists but is not 1920x1300 UYVY -> needs a different capture tool"
        echo "  NOT_OPENED       no such input, or another client holds it"
        echo "  a GO needs: forward view in the frames, surround fps ~unchanged, factory DVR still writing"
        echo
        echo "view raw frames on a computer:"
        echo "  ffmpeg -f rawvideo -pixel_format uyvy422 -video_size 1920x1300 -i cam_N_1920x1300_uyvy.raw cam_N_%d.png"
    } > "$s"
    say ""
    cat "$s"
}

package() {
    rm -f "$BIN" "$OUT/start_cam_daemon.sh.bak"
    dest=/sdcard/Download
    mkdir -p "$dest" 2>/dev/null
    tgz=$dest/od_dashcam_probe_$TS.tar.gz
    if ! (cd "$(dirname "$OUT")" && tar -czf "$tgz" "$(basename "$OUT")") 2>/dev/null; then
        tgz=$dest/od_dashcam_probe_$TS.tar
        (cd "$(dirname "$OUT")" && tar -cf "$tgz" "$(basename "$OUT")") 2>/dev/null || tgz=$OUT
    fi
    say ""
    say "Results: $tgz"
    say "Pull with: adb pull $tgz"
}

# ---------- main ----------

trap '' PIPE
trap 'restore_daemon' EXIT
trap 'say "Interrupted — restoring the camera daemon."; exit 130' INT TERM HUP

uid=$(id -u 2>/dev/null)
# The daemon must come back as the shell user, exactly as the app launches it.
[ "$uid" = 2000 ] || die "run this from 'adb shell' as the shell user (uid is ${uid:-unknown}; after 'adb root' run 'adb unroot')"
[ -f /vendor/lib64/libais_client.so ] || die "no /vendor/lib64/libais_client.so — not a DiLink 5 head unit"

if [ -n "${OVERDRIVE_SCRATCH:-}" ] && [ -d "$OVERDRIVE_SCRATCH" ]; then
    ROOT=$OVERDRIVE_SCRATCH
fi
if [ -z "$ROOT" ]; then
    wd=$(ps_args | awk '$1 ~ /^[0-9]+$/ && $2 ~ /(^|\/)(mk)?sh$/ && $3 ~ /\/start_cam_daemon\.sh$/ { print $3; exit }')
    [ -n "$wd" ] && ROOT=${wd%/start_cam_daemon.sh}
fi
if [ -z "$ROOT" ]; then
    if [ -f "$ALT/.od_scratch_root" ]; then ROOT=$ALT; else ROOT=$LEGACY; fi
fi
SENTINEL=$ROOT/camera_daemon.disabled

mkdir "$ROOT/od_dashcam_probe.lock" 2>/dev/null || die "another probe is running (remove $ROOT/od_dashcam_probe.lock if not)"
LOCK=$ROOT/od_dashcam_probe.lock
OUT=$ROOT/od_dashcam_probe_$TS
mkdir -p "$OUT/runs" 2>/dev/null || die "cannot create $OUT"
CONSOLE=$OUT/console.txt

APK=$(pm path com.overdrive.app 2>/dev/null | grep '/base.apk$' | head -n 1 | sed 's/^package://')
if [ -n "$APK" ] && [ -f "$(dirname "$APK")/lib/arm64/libfast_cam_release_guard.so" ]; then
    # Production preloads this into the capture binary; with no client it only filters AIS log spam.
    GUARD=$(dirname "$APK")/lib/arm64/libfast_cam_release_guard.so
fi

SRC_BIN=""
for cand in "$ROOT/fast_cam_capture" "$LEGACY/fast_cam_capture" "$ALT/fast_cam_capture"; do
    [ -f "$cand" ] && { SRC_BIN=$cand; break; }
done
BIN=$OUT/fcc_probe
if [ -n "$SRC_BIN" ]; then
    cp "$SRC_BIN" "$BIN" 2>/dev/null
elif [ -n "$APK" ] && command -v unzip >/dev/null 2>&1; then
    unzip -p "$APK" assets/dilink5/fast_cam_capture > "$BIN" 2>/dev/null
    SRC_BIN="$APK!assets/dilink5/fast_cam_capture"
fi
[ -s "$BIN" ] || die "fast_cam_capture not found (enable DiLink 5 mode once so the daemon deploys it)"
chmod 755 "$BIN"
BIN_SHA=$(sha256sum "$BIN" 2>/dev/null | cut -d' ' -f1)

AVM_SRC="AVM env"
if [ -z "${AVM:-}" ]; then
    for f in "$ROOT/cam_daemon.log" "$ROOT/byd_cam_daemon.log"; do
        [ -f "$f" ] || continue
        m=$(grep -o -E 'camera mapping: [0-9]{1,3}(,[0-9]{1,3}){3,4}' "$f" 2>/dev/null | tail -n 1 | sed 's/.*: //')
        [ -n "$m" ] && AVM=$m
    done
    AVM_SRC="daemon log"
    if ! valid_ids "${AVM:-}"; then AVM=$(getprop persist.overdrive.cams); AVM_SRC="persist.overdrive.cams"; fi
    if ! valid_ids "$AVM"; then AVM=$(cfg_get dilink5CameraMapping); AVM_SRC="config"; fi
    if ! valid_ids "$AVM"; then
        ident=$(echo "$(cfg_get modelId) $(getprop ro.product.model) $(getprop ro.build.product)" | tr 'A-Z' 'a-z')
        case "$ident" in *shark*|*dmo*) AVM=8,9,5,4 ;; *) AVM=0,1,2,3 ;; esac
        AVM_SRC="model default"
    fi
fi
valid_ids "$AVM" || die "bad surround mapping '$AVM' (set AVM=0,1,2,3)"
FIFTH=$(echo "$AVM" | cut -s -d, -f5)
AVM=$(echo "$AVM" | cut -d, -f1-4)

if [ -n "${IDS:-}" ]; then
    CANDS=$IDS
else
    CANDS=$FIFTH
    i=0
    while [ $i -le 15 ]; do
        case ",$AVM,$FIFTH," in *",$i,"*) ;; *) CANDS="$CANDS $i" ;; esac
        i=$((i + 1))
    done
fi
CANDS=$(echo $CANDS)

discover

if [ "$DISCOVER_ONLY" != 1 ] && [ "$YES" != 1 ]; then
    say ""
    say "Next: stop OverDrive's camera daemon, open each candidate input for a few"
    say "seconds, then restart the daemon (~5-8 min). Car parked, ignition on, app closed."
    printf 'Type YES to continue: '
    read -r answer
    if [ "$answer" != YES ]; then
        say "Aborted — no cameras were opened."
        DISCOVER_ONLY=1
    fi
fi

if [ "$DISCOVER_ONLY" = 1 ]; then
    summarize
    package
    exit 0
fi

stop_daemon
say "== Baseline: surround cameras only"
run_fcc baseline 8 --cams "$AVM" --time 8
if grep -q -E '^\[-\] Init failed' "$OUT/runs/baseline.txt" 2>/dev/null; then
    say "!! QCarCam refused the capture client (Init failed) — see runs/baseline.txt"
    ABORTED=1
fi

say "== Solo test per candidate"
for id in $CANDS; do
    run_fcc "solo_$id" 3 --cam "$id" --time 3 || break
    result=$(classify "$OUT/runs/solo_$id.txt" "$id")
    say "      -> $result"
    case "$result" in STREAMING*) STREAMING="$STREAMING $id" ;; esac
done

for id in $STREAMING; do
    say "== Candidate $id next to the surround cameras"
    dvr_snapshot "$OUT/dvr_${id}_pre.txt"
    run_fcc "avm_plus_$id" 8 --cams "$AVM,$id" --time 8 || break
    dvr_snapshot "$OUT/dvr_${id}_post.txt"
    dvr_growth "$OUT/dvr_${id}_pre.txt" "$OUT/dvr_${id}_post.txt" > "$OUT/dvr_$id.growth"
    pidof com.byd.cdr > "$OUT/dvr_$id.pid" 2>/dev/null
    record_dump "$id" || break
done

say "== Restoring the camera daemon"
restore_daemon
summarize
package
