#!/usr/bin/env bash
# Remove ads, recommendations and unused apps from the Philips Android TV.
#
#   ./debloat.sh status   read-only: show the state of every package below
#   ./debloat.sh apply    back up + uninstall USER_APPS, disable SYSTEM_APPS
#   ./debloat.sh undo     re-enable SYSTEM_APPS, reinstall USER_APPS from backup
#
# Nothing on the system partition is touched:
# - USER_APPS are ordinary installed apps. Their APKs are pulled to
#   backup/apks/<pkg>/ first, and the uninstall is skipped if the backup fails.
#   Undo reinstalls them from there (app data and logins are not restored).
# - SYSTEM_APPS are disabled with `pm disable-user --user 0`; undo is `pm enable`.
#
# Device: $ADB_SERIAL, default 192.168.50.126:5555.
set -euo pipefail

SERIAL="${ADB_SERIAL:-192.168.50.126:5555}"
BACKUP_DIR="$(cd "$(dirname "$0")" && pwd)/backup/apks"

# Apps someone installed (not system apps): back up, then uninstall.
USER_APPS=(
    com.justwatch.justwatch        # JustWatch
    com.spotify.tv.android         # Spotify
    com.discovery.dplay            # discovery+
    com.wbd.stream                 # Max
    com.hbo.hbonow                 # HBO (old app)
    se.tv4.tv4playtab              # TV4 Play
    com.disney.disneyplus          # Disney+
    org.droidtv.nettvapp20004410   # Philips App Gallery web app
    org.droidtv.nettvapp4546       # Philips App Gallery web app
    org.droidtv.nettvapp4932       # Philips App Gallery web app
)

# Preinstalled system apps: disable for user 0.
SYSTEM_APPS=(
    # Ads / recommendation rows
    com.google.android.tvrecommendations
    org.droidtv.recommendation
    org.droidtv.nettvrecommender
    # Philips clutter
    org.droidtv.demome                 # Demo Me
    org.droidtv.amazonalexa            # Alexa
    org.droidtv.nettv_launcherapp      # App Gallery
    org.droidtv.nettvapps
    org.droidtv.nettv.market
    # Bundled apps not used
    com.netflix.ninja                  # Netflix
    com.amazon.amazonvideo.livingroom  # Prime Video
    com.google.android.videos          # Google TV / Play Movies
    com.google.android.youtube.tvmusic # YouTube Music
    com.google.android.play.games      # Play Games
    com.phorus.playfi.tv               # DTS Play-Fi
    io.mimi.tvapp                      # Mimi hearing test
    com.google.android.katniss         # Voice search / Assistant
)

adb_sh() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }

connect() {
    adb connect "$SERIAL" >/dev/null 2>&1 || true
    local state
    state="$(adb -s "$SERIAL" get-state 2>&1 || true)"
    if [[ "$state" != device ]]; then
        echo "TV $SERIAL not reachable over adb: $state" >&2
        exit 1
    fi
}

# Prints: missing | enabled | disabled
pkg_state() {
    local pkg=$1
    if ! adb_sh pm path "$pkg" | grep -q '^package:'; then
        echo missing
    elif adb_sh pm list packages -d | grep -qx "package:$pkg"; then
        echo disabled
    else
        echo enabled
    fi
}

status() {
    echo "== User apps (uninstall)"
    for pkg in "${USER_APPS[@]}"; do
        local backup=no
        [[ -n "$(ls "$BACKUP_DIR/$pkg/"*.apk 2>/dev/null)" ]] && backup=yes
        printf '  %-9s backup=%-3s %s\n' "$(pkg_state "$pkg")" "$backup" "$pkg"
    done
    echo "== System apps (disable)"
    for pkg in "${SYSTEM_APPS[@]}"; do
        printf '  %-9s %s\n' "$(pkg_state "$pkg")" "$pkg"
    done
}

backup_apks() {
    local pkg=$1 dir="$BACKUP_DIR/$1"
    mkdir -p "$dir"
    local paths=() p
    mapfile -t paths < <(adb_sh pm path "$pkg" | sed -n 's/^package://p')
    [[ ${#paths[@]} -gt 0 ]] || return 1
    for p in "${paths[@]}"; do
        adb -s "$SERIAL" pull "$p" "$dir/$(basename "$p")" >/dev/null || return 1
        [[ -s "$dir/$(basename "$p")" ]] || return 1
    done
}

apply() {
    echo "== Uninstalling user apps (APK backup in $BACKUP_DIR)"
    for pkg in "${USER_APPS[@]}"; do
        if [[ "$(pkg_state "$pkg")" == missing ]]; then
            echo "  skip      $pkg (not installed)"
            continue
        fi
        if ! backup_apks "$pkg"; then
            echo "  FAILED    $pkg (backup failed, not uninstalled)"
            continue
        fi
        if adb -s "$SERIAL" uninstall "$pkg" >/dev/null; then
            echo "  removed   $pkg"
        else
            echo "  FAILED    $pkg (uninstall failed)"
        fi
    done
    echo "== Disabling system apps"
    for pkg in "${SYSTEM_APPS[@]}"; do
        case "$(pkg_state "$pkg")" in
            missing)  echo "  skip      $pkg (not installed)" ;;
            disabled) echo "  skip      $pkg (already disabled)" ;;
            *)
                if adb_sh pm disable-user --user 0 "$pkg" | grep -q 'disabled-user'; then
                    echo "  disabled  $pkg"
                else
                    echo "  FAILED    $pkg"
                fi
                ;;
        esac
    done
}

undo() {
    echo "== Re-enabling system apps"
    for pkg in "${SYSTEM_APPS[@]}"; do
        if [[ "$(pkg_state "$pkg")" == disabled ]]; then
            adb_sh pm enable "$pkg" >/dev/null && echo "  enabled   $pkg"
        fi
    done
    echo "== Reinstalling user apps from backup"
    for pkg in "${USER_APPS[@]}"; do
        [[ "$(pkg_state "$pkg")" == missing ]] || continue
        local apks=()
        mapfile -t apks < <(ls "$BACKUP_DIR/$pkg/"*.apk 2>/dev/null)
        if [[ ${#apks[@]} -eq 0 ]]; then
            echo "  FAILED    $pkg (no backup; reinstall from Play Store)"
        elif [[ ${#apks[@]} -eq 1 ]]; then
            adb -s "$SERIAL" install "${apks[0]}" >/dev/null && echo "  installed $pkg"
        else
            adb -s "$SERIAL" install-multiple "${apks[@]}" >/dev/null && echo "  installed $pkg"
        fi
    done
}

case "${1:-}" in
    status) connect; status ;;
    apply)  connect; apply ;;
    undo)   connect; undo ;;
    *) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
