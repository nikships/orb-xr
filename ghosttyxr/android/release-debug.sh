#!/bin/bash
#
# Build the headset app's debug APK and publish it to a GitHub release.
#
# The debug APK is debug-keystore-signed (see app/build.gradle.kts) so it can be
# sideloaded onto the XR AVD / a headset without a release key. This script is
# the manual counterpart to the Mac companion's notarize-and-release.sh.
#
# Requirements:
#   - JDK 21 (Gradle requirement). Auto-resolved via /usr/libexec/java_home on
#     macOS; override by exporting JAVA_HOME yourself.
#   - gh CLI authenticated with push access to the repo.
#
# Env vars:
#   RELEASE_TAG   GitHub release tag to publish to   (default: android-debug-<shortsha>)
#   SKIP_UPLOAD   set to 1 to build only, skip the GitHub upload step
#

set -euo pipefail

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ANDROID_DIR"

# Gradle needs JDK 21. Use the caller's JAVA_HOME only if it actually points at
# a usable JDK; otherwise (unset, or stale like a moved Android Studio path)
# resolve one ourselves.
if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo -e "${YELLOW}JAVA_HOME ($JAVA_HOME) is invalid; auto-resolving JDK 21.${NC}"
    JAVA_HOME=""
fi
if [ -z "${JAVA_HOME:-}" ]; then
    # 1) macOS java_home, 2) common Homebrew locations.
    if [ -x /usr/libexec/java_home ]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    fi
    for c in \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
        [ -n "$JAVA_HOME" ] && break
        [ -x "$c/bin/java" ] && JAVA_HOME="$c"
    done
    export JAVA_HOME
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo -e "${RED}ERROR: No JDK 21 found. Install it (e.g. 'brew install openjdk@21') or export JAVA_HOME.${NC}" >&2
    exit 1
fi
export JAVA_HOME
echo -e "${BLUE}JAVA_HOME:${NC} $JAVA_HOME"

# Resolve version + tag
VERSION_NAME=$(sed -n 's/.*versionName *= *"\(.*\)".*/\1/p' app/build.gradle.kts | head -n 1)
VERSION_NAME="${VERSION_NAME:-0.0.0}"
SHORT_SHA=$(git rev-parse --short HEAD)
BUILD_NUMBER=$(git rev-list --count HEAD 2>/dev/null || echo "1")
RELEASE_TAG="${RELEASE_TAG:-android-debug-$SHORT_SHA}"
echo -e "${BLUE}App version:${NC} $VERSION_NAME (build $BUILD_NUMBER, $SHORT_SHA)"
echo -e "${BLUE}Release tag:${NC} $RELEASE_TAG"

# Build the debug APK
echo -e "${BLUE}Building debug APK...${NC}"
./gradlew :app:assembleDebug --stacktrace

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo -e "${RED}ERROR: APK not found at $APK${NC}" >&2
    exit 1
fi

# Rename so the release asset carries the version + commit it was built from.
DIST_APK="$ANDROID_DIR/app/build/outputs/apk/debug/ghosttyxr-headset-$VERSION_NAME-debug-$SHORT_SHA.apk"
cp -f "$APK" "$DIST_APK"
SHA_FILE="$DIST_APK.sha256"
shasum -a 256 "$DIST_APK" | tee "$SHA_FILE"
echo -e "${GREEN}Built:${NC} $DIST_APK"

if [ "${SKIP_UPLOAD:-}" = "1" ]; then
    echo -e "${YELLOW}SKIP_UPLOAD=1 — leaving artifacts in place; not uploading to GitHub.${NC}"
    ls -lh "$DIST_APK" "$SHA_FILE"
    exit 0
fi

if ! command -v gh >/dev/null 2>&1; then
    echo -e "${RED}gh CLI not found; install it or rerun with SKIP_UPLOAD=1.${NC}" >&2
    exit 1
fi

# Publish from the repo root so gh resolves the right remote.
REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT"

if gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
    echo -e "${BLUE}Uploading to existing release ${RELEASE_TAG}...${NC}"
    gh release upload "$RELEASE_TAG" "$DIST_APK" "$SHA_FILE" --clobber
else
    echo -e "${BLUE}Creating release ${RELEASE_TAG}...${NC}"
    gh release create "$RELEASE_TAG" "$DIST_APK" "$SHA_FILE" \
        --title "Orb XR (debug) $VERSION_NAME" \
        --notes "Debug-signed headset APK built from $SHORT_SHA (build $BUILD_NUMBER). Sideload-only — not for Play Store distribution." \
        --prerelease
fi

echo -e "${GREEN}Done.${NC}"
gh release view "$RELEASE_TAG" --web 2>/dev/null || true
