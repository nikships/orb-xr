#!/bin/bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Display/bundle name shown in Finder, Dock, and /Applications.
APP_DISPLAY_NAME="Orb XR"
# Actual running process name (CFBundleExecutable) — unrelated to the display
# name, needed to `pkill` the right process regardless of the .app's folder name.
APP_PROCESS_NAME="GhosttyXRCompanion"
APP_BUNDLE_ID="dev.ghosttyxr.companion"
APP_SOURCE="$PROJECT_DIR/$APP_DISPLAY_NAME.app"
APP_DESTINATION="/Applications/$APP_DISPLAY_NAME.app"
# Pre-rename install path from earlier releases; remove so it doesn't linger
# as a duplicate copy alongside the new name.
LEGACY_DESTINATIONS=(
    "/Applications/GhosttyXRCompanion.app"
    "/Applications/Agent Cockpit.app"
)

if [ ! -d "$APP_SOURCE" ]; then
    echo "ERROR: $APP_SOURCE not found. Run ./build-release.sh first." >&2
    exit 1
fi

osascript -e "tell application \"$APP_DISPLAY_NAME\" to quit" 2>/dev/null || true
pkill -x "$APP_PROCESS_NAME" 2>/dev/null || true
# Do NOT `tccutil reset` here: it wipes Accessibility/Screen Recording grants on
# every reinstall. Same bundle ID + same Developer ID signature keeps TCC grants
# even though the .app's folder name changed.
rm -rf "$APP_DESTINATION" "${LEGACY_DESTINATIONS[@]}"
cp -R "$APP_SOURCE" "$APP_DESTINATION"
open "$APP_DESTINATION"
