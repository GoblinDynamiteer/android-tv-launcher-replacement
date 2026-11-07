#!/usr/bin/env bash
# Build the launcher on GitHub Actions and run it on the TV.
#
#   ./tv.sh build        push the current branch, wait for CI, download the APK
#   ./tv.sh install      install the downloaded APK on the TV
#   ./tv.sh start        open the launcher on the TV
#   ./tv.sh deploy       build + install + start
#   ./tv.sh shot [--no-open] [file]
#                        screenshot the TV and open it (default build/shots/<time>.png)
#   ./tv.sh log          follow logcat for the launcher
#   ./tv.sh home on      make the launcher the default home (disables Google's)
#   ./tv.sh home off     re-enable Google's launcher and give Home back to it
#   ./tv.sh home         show which app the Home button opens
#   ./tv.sh hue-key [file]
#                        copy the Hue bridge key (default ~/.hue_key) into the launcher
#   ./tv.sh wake-mac "<input name>" <mac>
#                        wake that input's device over the network (Wake-on-LAN) when it's
#                        picked while asleep; no arguments lists the configured ones
#
# Only committed changes are built. Env: ADB_SERIAL (default 192.168.50.126:5555),
# REMOTE (default github), VIEWER (screenshot viewer, default eog).
set -euo pipefail

SERIAL="${ADB_SERIAL:-192.168.50.126:5555}"
REMOTE="${REMOTE:-github}"
VIEWER="${VIEWER:-eog}" # xdg-open would pick Chrome for PNGs here
PKG=se.kampe.tvlauncher
ACTIVITY=.MainActivity
ROOT="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$ROOT/build/ci"
STOCK_HOME=com.google.android.tvlauncher/.MainActivity
WORKFLOW=build.yml
ARTIFACT=app-debug

die() { echo "$*" >&2; exit 1; }

connect() {
    adb connect "$SERIAL" >/dev/null 2>&1 || true
    local state
    state="$(adb -s "$SERIAL" get-state 2>&1 || true)"
    [[ "$state" == device ]] || die "TV $SERIAL not reachable over adb: $state"
}

build() {
    cd "$ROOT"
    [[ -z "$(git status --porcelain)" ]] ||
        echo "Note: uncommitted changes are not part of the CI build." >&2

    local branch sha run_id=""
    branch="$(git branch --show-current)"
    sha="$(git rev-parse HEAD)"
    if [[ "$(git rev-parse '@{u}' 2>/dev/null || true)" != "$sha" ]]; then
        git push -u "$REMOTE" "$branch"
    fi

    echo "Waiting for the CI run of ${sha:0:7} ..."
    for _ in $(seq 30); do
        run_id="$(gh run list --workflow "$WORKFLOW" --commit "$sha" --limit 1 \
            --json databaseId -q '.[0].databaseId // empty')"
        [[ -n "$run_id" ]] && break
        sleep 4
    done
    [[ -n "$run_id" ]] || die "No CI run found for $sha."

    gh run watch "$run_id" --exit-status --interval 5 >/dev/null ||
        die "CI run failed: gh run view $run_id --log-failed"
    rm -rf "$APK_DIR"
    gh run download "$run_id" --name "$ARTIFACT" --dir "$APK_DIR"
    echo "APK: $(ls "$APK_DIR"/*.apk)"
}

install() {
    connect
    local apk
    apk="$(ls "$APK_DIR"/*.apk 2>/dev/null | head -1)"
    [[ -n "$apk" ]] || die "No APK in $APK_DIR; run ./tv.sh build first."
    adb -s "$SERIAL" install -r "$apk"
}

start() {
    connect
    adb -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY"
}

shot() {
    local open=1
    if [[ "${1:-}" == --no-open ]]; then
        open=0
        shift
    fi
    connect
    local file="${1:-$ROOT/build/shots/$(date +%Y%m%d-%H%M%S).png}"
    mkdir -p "$(dirname "$file")"
    adb -s "$SERIAL" exec-out screencap -p >"$file"
    echo "$file"
    if ((open)); then
        setsid "$VIEWER" "$file" >/dev/null 2>&1 &
    fi
}

log() {
    connect
    local pid
    pid="$(adb -s "$SERIAL" shell pidof "$PKG" | tr -d '\r')"
    [[ -n "$pid" ]] || die "$PKG is not running."
    adb -s "$SERIAL" logcat --pid="$pid"
}

current_home() {
    adb -s "$SERIAL" shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1
}

# Google's launcher declares its HOME filter with priority 2, which beats any
# preferred home app, so `home on` has to disable it (`home off` re-enables it).
# FallbackHome (com.android.tv.settings) stays as the safety net.
home() {
    connect
    local stock_pkg="${STOCK_HOME%%/*}"
    case "${1:-}" in
        on)
            adb -s "$SERIAL" shell pm path "$PKG" >/dev/null 2>&1 ||
                die "$PKG is not installed; run ./tv.sh install first."
            adb -s "$SERIAL" shell pm disable-user --user 0 "$stock_pkg"
            adb -s "$SERIAL" shell cmd package set-home-activity --user 0 "$PKG/$ACTIVITY"
            echo "Undo: ./tv.sh home off  (or: adb shell pm enable $stock_pkg)"
            ;;
        off)
            adb -s "$SERIAL" shell pm enable "$stock_pkg"
            adb -s "$SERIAL" shell cmd package set-home-activity --user 0 "$STOCK_HOME"
            ;;
        "") ;;
        *) die "Usage: ./tv.sh home [on|off]" ;;
    esac
    echo "Home opens: $(current_home)"
}

# The key never goes into the repo or a command line: it's piped into the launcher's
# private files over adb (run-as works because the CI build is a debug build).
hue_key() {
    connect
    local file="${1:-$HOME/.hue_key}"
    [[ -s "$file" ]] || die "No Hue key in $file."
    adb -s "$SERIAL" shell pm path "$PKG" >/dev/null 2>&1 ||
        die "$PKG is not installed; run ./tv.sh install first."
    tr -d '[:space:]' <"$file" |
        adb -s "$SERIAL" shell "run-as $PKG sh -c 'mkdir -p files && cat > files/hue_key'"
    local size
    size="$(adb -s "$SERIAL" shell "run-as $PKG wc -c files/hue_key" | awk '{print $1}')"
    [[ "$size" -gt 0 ]] || die "Copying the key failed."
    echo "Hue key copied to $PKG ($size bytes). Remove: adb shell run-as $PKG rm files/hue_key"
}

# Wake-on-LAN MAC addresses per input name (as shown in the input bar), kept in the
# launcher's files/wake_macs as "<name>\t<mac>" lines.
wake_mac() {
    connect
    adb -s "$SERIAL" shell pm path "$PKG" >/dev/null 2>&1 ||
        die "$PKG is not installed; run ./tv.sh install first."
    local current
    current="$(adb -s "$SERIAL" shell "run-as $PKG cat files/wake_macs 2>/dev/null" | tr -d '\r' || true)"
    if (($# == 0)); then
        printf '%s\n' "${current:-(none)}"
        return
    fi
    local name="$1" mac="${2:-}"
    [[ "$mac" =~ ^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$ ]] ||
        die 'Usage: ./tv.sh wake-mac "<input name>" aa:bb:cc:dd:ee:ff'
    { printf '%s\n' "$current" | awk -F'\t' -v n="$name" 'NF && tolower($1) != tolower(n)'
      printf '%s\t%s\n' "$name" "$mac"; } |
        adb -s "$SERIAL" shell "run-as $PKG sh -c 'mkdir -p files && cat > files/wake_macs'"
    adb -s "$SERIAL" shell "run-as $PKG cat files/wake_macs" | tr -d '\r'
}

case "${1:-}" in
    build) build ;;
    install) install ;;
    start) start ;;
    deploy) build && install && start ;;
    shot) shift; shot "$@" ;;
    log) log ;;
    home) home "${2:-}" ;;
    hue-key) hue_key "${2:-}" ;;
    wake-mac) shift; wake_mac "$@" ;;
    *) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 1 ;;
esac
