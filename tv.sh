#!/usr/bin/env bash
# Build the launcher and run it on the TV.
#
#   ./tv.sh build        build the debug APK locally (JDK 17 + Android SDK on this PC)
#   ./tv.sh ci-build     push the current branch, wait for CI, download its APK instead
#   ./tv.sh install      install the last built APK on the TV
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
#   ./tv.sh wallpaper [list]
#                        list the wallpapers per input/app, and the space left on the TV
#   ./tv.sh wallpaper add "<name>" <image>...
#                        add images (jpg/png/webp) for an input or dock app, by the name the
#                        launcher shows (or the app's package); one is picked at random and
#                        faded in while it has focus
#   ./tv.sh wallpaper remove "<name>" [<image>]
#                        remove that name's wallpapers, or only the one with that file name
#
# `build` uses the working tree as it is; `ci-build` builds committed changes only.
# Env: ADB_SERIAL (default 192.168.50.126:5555), JAVA_HOME (default the newest
# ~/.jdks/jdk-17*), ANDROID_HOME (default ~/Android/Sdk), REMOTE (default github),
# VIEWER (screenshot viewer, default eog).
set -euo pipefail

SERIAL="${ADB_SERIAL:-192.168.50.126:5555}"
REMOTE="${REMOTE:-github}"
VIEWER="${VIEWER:-eog}" # xdg-open would pick Chrome for PNGs here
PKG=se.kampe.tvlauncher
ACTIVITY=.MainActivity
ROOT="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$ROOT/build/apk"
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
    # Same key as CI, so the APK can update the installed app (adb install -r).
    [[ -f app/debug.keystore ]] || die "app/debug.keystore is missing (the shared debug key)."
    if [[ -z "${JAVA_HOME:-}" ]]; then
        JAVA_HOME="$(ls -d "$HOME"/.jdks/jdk-17* 2>/dev/null | sort -V | tail -1)"
        [[ -n "$JAVA_HOME" ]] || die "No JDK 17: set JAVA_HOME."
        export JAVA_HOME
    fi
    local sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
    [[ -f local.properties ]] || echo "sdk.dir=$sdk" >local.properties

    ./gradlew --console=plain -q assembleDebug
    rm -rf "$APK_DIR"
    mkdir -p "$APK_DIR"
    cp app/build/outputs/apk/debug/app-debug.apk "$APK_DIR/"
    echo "APK: $APK_DIR/app-debug.apk"
}

ci_build() {
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

WALL_DIR=files/wallpapers

wall_index() {
    adb -s "$SERIAL" shell "run-as $PKG cat $WALL_DIR/index 2>/dev/null" | tr -d '\r' || true
}

# Writes the index (stdin), then deletes image files no line refers to any more.
wall_write_index() {
    adb -s "$SERIAL" shell "run-as $PKG sh -c 'mkdir -p $WALL_DIR && cat > $WALL_DIR/index'"
    local keep f
    keep="$(wall_index | cut -f2 | sort -u)"
    for f in $(adb -s "$SERIAL" shell "run-as $PKG ls $WALL_DIR" | tr -d '\r'); do
        [[ "$f" == index ]] && continue
        grep -qxF "$f" <<<"$keep" || adb -s "$SERIAL" shell "run-as $PKG rm $WALL_DIR/$f"
    done
}

# How much the wallpapers take, and what's left on the TV's app storage (/data).
wall_space() {
    local used free
    used="$(adb -s "$SERIAL" shell "run-as $PKG du -sk $WALL_DIR 2>/dev/null" | awk '{print $1}')"
    free="$(adb -s "$SERIAL" shell df -k /data | awk 'NR == 2 {print $4}')"
    awk -v u="${used:-0}" -v f="${free:-0}" \
        'BEGIN { printf "\nWallpapers use %.1f MB; %.1f GB free on the TV.\n", u / 1024, f / 1048576 }'
}

wallpaper() {
    connect
    adb -s "$SERIAL" shell pm path "$PKG" >/dev/null 2>&1 ||
        die "$PKG is not installed; run ./tv.sh install first."
    local cmd="${1:-list}"
    shift || true
    case "$cmd" in
        list)
            local index
            index="$(wall_index | grep -v '^$' || true)"
            if [[ -z "$index" ]]; then
                echo "(none)"
                wall_space
                return
            fi
            sort -t$'\t' -k1,1 -s <<<"$index" | awk -F'\t' 'NF >= 2 {
                if ($1 != last) print $1
                last = $1
                print "    " ($3 != "" ? $3 : $2) "  (" $2 ")"
            }'
            wall_space
            ;;
        add)
            local name="${1:-}"
            shift || true
            [[ -n "$name" && $# -gt 0 ]] || die 'Usage: ./tv.sh wallpaper add "<name>" <image>...'
            local index img ext stored size
            index="$(wall_index)"
            for img in "$@"; do
                [[ -f "$img" ]] || die "No such file: $img"
                ext="${img##*.}"
                ext="${ext,,}"
                [[ "$ext" =~ ^(jpg|jpeg|png|webp)$ ]] || die "Not a jpg/png/webp image: $img"
                stored="$(sha1sum "$img" | cut -c1-12).$ext"
                adb -s "$SERIAL" exec-in "run-as $PKG sh -c 'mkdir -p $WALL_DIR && cat > $WALL_DIR/$stored'" <"$img"
                # exec-in can return before the TV has written the last bytes.
                for _ in $(seq 20); do
                    size="$(adb -s "$SERIAL" shell "run-as $PKG wc -c $WALL_DIR/$stored" | awk '{print $1}')"
                    [[ "$size" == "$(stat -c %s "$img")" ]] && break
                    sleep 0.5
                done
                [[ "$size" == "$(stat -c %s "$img")" ]] || die "Copying $img failed ($size bytes arrived)."
                index="$(printf '%s\n' "$index" | awk -F'\t' -v n="$name" -v f="$stored" \
                    'NF && !(tolower($1) == tolower(n) && $2 == f)')"
                index+=$'\n'"$name"$'\t'"$stored"$'\t'"$(basename "$img")"
                echo "Added $(basename "$img") for \"$name\" ($((size / 1024)) KB)"
            done
            printf '%s\n' "$index" | grep -v '^$' | wall_write_index
            ;;
        remove)
            local name="${1:-}" only="${2:-}"
            [[ -n "$name" ]] || die 'Usage: ./tv.sh wallpaper remove "<name>" [<image>]'
            [[ -z "$only" ]] || only="$(basename "$only")"
            local index kept
            index="$(wall_index | grep -v '^$' || true)"
            kept="$(printf '%s\n' "$index" | awk -F'\t' -v n="$name" -v o="$only" \
                'NF && !(tolower($1) == tolower(n) && (o == "" || $2 == o || $3 == o))')"
            [[ "$kept" != "$index" ]] || die "No wallpaper matches \"$name\"${only:+ $only}."
            printf '%s\n' "$kept" | grep -v '^$' | wall_write_index || true
            echo "Removed. Now:"
            wallpaper list
            ;;
        *) die 'Usage: ./tv.sh wallpaper [list | add "<name>" <image>... | remove "<name>" [<image>]]' ;;
    esac
}

case "${1:-}" in
    build) build ;;
    ci-build) ci_build ;;
    install) install ;;
    start) start ;;
    deploy) build && install && start ;;
    shot) shift; shot "$@" ;;
    log) log ;;
    home) home "${2:-}" ;;
    hue-key) hue_key "${2:-}" ;;
    wake-mac) shift; wake_mac "$@" ;;
    wallpaper) shift; wallpaper "$@" ;;
    *) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 1 ;;
esac
