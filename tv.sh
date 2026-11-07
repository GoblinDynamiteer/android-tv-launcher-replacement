#!/usr/bin/env bash
# Build the launcher on GitHub Actions and run it on the TV.
#
#   ./tv.sh build        push the current branch, wait for CI, download the APK
#   ./tv.sh install      install the downloaded APK on the TV
#   ./tv.sh start        open the launcher on the TV
#   ./tv.sh deploy       build + install + start
#   ./tv.sh shot [file]  save a screenshot of the TV (default build/shot.png)
#   ./tv.sh log          follow logcat for the launcher
#
# Only committed changes are built. Env: ADB_SERIAL (default 192.168.50.126:5555),
# REMOTE (default github).
set -euo pipefail

SERIAL="${ADB_SERIAL:-192.168.50.126:5555}"
REMOTE="${REMOTE:-github}"
PKG=se.kampe.tvlauncher
ACTIVITY=.MainActivity
ROOT="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$ROOT/build/ci"
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
    connect
    local file="${1:-$ROOT/build/shot.png}"
    mkdir -p "$(dirname "$file")"
    adb -s "$SERIAL" exec-out screencap -p >"$file"
    echo "$file"
}

log() {
    connect
    local pid
    pid="$(adb -s "$SERIAL" shell pidof "$PKG" | tr -d '\r')"
    [[ -n "$pid" ]] || die "$PKG is not running."
    adb -s "$SERIAL" logcat --pid="$pid"
}

case "${1:-}" in
    build) build ;;
    install) install ;;
    start) start ;;
    deploy) build && install && start ;;
    shot) shot "${2:-}" ;;
    log) log ;;
    *) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
