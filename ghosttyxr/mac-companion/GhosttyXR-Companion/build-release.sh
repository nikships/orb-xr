#!/bin/bash
#
# Build a Release-config, Developer ID-signed, hardened-runtime "Orb XR.app"
# (Xcode target/executable/bundle ID remain GhosttyXRCompanion / dev.ghosttyxr.companion).
# Output: ./Orb XR.app (next to this script) ready to be notarized.
#
# Requirements:
#   - xcodegen (`brew install xcodegen`)
#   - Apple "Developer ID Application" identity in the login keychain.
#     Auto-detected; override with $CODESIGN_IDENTITY.
#   - $APP_VERSION (optional) — overrides CFBundleShortVersionString.
#                              Defaults to git tag or 0.1.0.
#

set -euo pipefail

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Xcode target/scheme/product name — unrelated to the shipped display name.
APP_NAME="GhosttyXRCompanion"
# Shipped .app folder name shown in Finder, Dock, and /Applications.
DIST_APP_NAME="Orb XR"
APP_DIR="$PROJECT_DIR/$DIST_APP_NAME.app"
BUILD_DIR="$PROJECT_DIR/build"
ENTITLEMENTS="$PROJECT_DIR/entitlements.plist"

cd "$PROJECT_DIR"

# Resolve signing identity
CODESIGN_IDENTITY="${CODESIGN_IDENTITY:-}"
if [ -z "$CODESIGN_IDENTITY" ]; then
    CODESIGN_IDENTITY=$(security find-identity -v -p codesigning | grep "Developer ID Application" | head -1 | sed 's/.*"\(.*\)"/\1/')
fi
if [ -z "$CODESIGN_IDENTITY" ]; then
    echo -e "${RED}ERROR: No 'Developer ID Application' identity found in keychain.${NC}" >&2
    echo "Install your Developer ID cert or set CODESIGN_IDENTITY." >&2
    exit 1
fi
echo -e "${BLUE}Using signing identity:${NC} $CODESIGN_IDENTITY"

# Resolve version
VERSION="${APP_VERSION:-}"
if [ -z "$VERSION" ]; then
    VERSION=$(git describe --tags --abbrev=0 2>/dev/null || echo "0.1.0")
    VERSION="${VERSION#v}"
fi
BUILD_NUMBER=$(git rev-list --count HEAD 2>/dev/null || echo "1")
echo -e "${BLUE}Version:${NC} ${VERSION} (build ${BUILD_NUMBER})"

# Regenerate Xcode project
echo -e "${BLUE}Running xcodegen...${NC}"
xcodegen generate

# Clean previous build
rm -rf "$BUILD_DIR" "$APP_DIR"

echo -e "${BLUE}Building Release config for arm64...${NC}"
xcodebuild \
    -project GhosttyXRCompanion.xcodeproj \
    -scheme GhosttyXRCompanion \
    -configuration Release \
    -derivedDataPath "$BUILD_DIR" \
    -arch arm64 \
    ONLY_ACTIVE_ARCH=NO \
    CODE_SIGN_STYLE=Manual \
    CODE_SIGN_IDENTITY="$CODESIGN_IDENTITY" \
    CODE_SIGNING_REQUIRED=YES \
    CODE_SIGNING_ALLOWED=YES \
    OTHER_CODE_SIGN_FLAGS="--timestamp --options runtime" \
    CODE_SIGN_ENTITLEMENTS="$ENTITLEMENTS" \
    MARKETING_VERSION="$VERSION" \
    CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
    build | xcbeautify 2>/dev/null || \
xcodebuild \
    -project GhosttyXRCompanion.xcodeproj \
    -scheme GhosttyXRCompanion \
    -configuration Release \
    -derivedDataPath "$BUILD_DIR" \
    -arch arm64 \
    ONLY_ACTIVE_ARCH=NO \
    CODE_SIGN_STYLE=Manual \
    CODE_SIGN_IDENTITY="$CODESIGN_IDENTITY" \
    CODE_SIGNING_REQUIRED=YES \
    CODE_SIGNING_ALLOWED=YES \
    OTHER_CODE_SIGN_FLAGS="--timestamp --options runtime" \
    CODE_SIGN_ENTITLEMENTS="$ENTITLEMENTS" \
    MARKETING_VERSION="$VERSION" \
    CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
    build

BUILT_APP="$BUILD_DIR/Build/Products/Release/$APP_NAME.app"
if [ ! -d "$BUILT_APP" ]; then
    echo -e "${RED}ERROR: Build product not found at $BUILT_APP${NC}" >&2
    exit 1
fi

BUILT_VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$BUILT_APP/Contents/Info.plist")
BUILT_NUMBER=$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "$BUILT_APP/Contents/Info.plist")
if [ "$BUILT_VERSION" != "$VERSION" ] || [ "$BUILT_NUMBER" != "$BUILD_NUMBER" ]; then
    echo -e "${RED}ERROR: Built app version ${BUILT_VERSION} (${BUILT_NUMBER}) does not match ${VERSION} (${BUILD_NUMBER}).${NC}" >&2
    exit 1
fi

cp -R "$BUILT_APP" "$APP_DIR"

# Strip extended attributes that block clean signing
xattr -cr "$APP_DIR"

# Re-sign top-level with explicit options to be safe across xcodebuild versions
echo -e "${BLUE}Re-signing top-level bundle with hardened runtime + timestamp...${NC}"
codesign --force --sign "$CODESIGN_IDENTITY" \
    --options runtime \
    --timestamp \
    --entitlements "$ENTITLEMENTS" \
    "$APP_DIR"

echo -e "${BLUE}Verifying signature...${NC}"
codesign --verify --deep --strict --verbose=2 "$APP_DIR"
echo -e "${GREEN}Signature OK${NC}"

# Gatekeeper assessment is expected to fail until notarization, but show the message.
spctl -a -vvv -t install "$APP_DIR" 2>&1 || \
    echo -e "${YELLOW}(Gatekeeper rejected — expected; run notarize-and-release.sh next.)${NC}"

echo -e "${GREEN}Built signed app:${NC} $APP_DIR"
echo
echo "Next: ./notarize-and-release.sh"
