#!/bin/bash
#
# Notarize the already-signed "Orb XR.app", staple it, and upload the
# distributable zip to a GitHub release.
#
# Requirements:
#   - "Orb XR.app" present in this directory (run build-release.sh first).
#   - xcrun notarytool keychain profile (default name: "notarytool").
#       Configure once with:
#         xcrun notarytool store-credentials notarytool \
#           --apple-id <APPLE_ID> --team-id <TEAM_ID> \
#           --password <APP_SPECIFIC_PASSWORD>
#     Override the profile name via $NOTARY_PROFILE.
#   - gh CLI authenticated with push access to the repo (default: anand-92/ghostty-vibe-xr).
#       Override the release tag via $RELEASE_TAG; the release will be created if missing.
#
# Env vars:
#   NOTARY_PROFILE   keychain profile name for notarytool        (default: "notarytool")
#   RELEASE_TAG      GitHub release tag to upload assets to      (default: auto from app version)
#   SKIP_UPLOAD      set to 1 to skip the GitHub upload step
#

set -euo pipefail

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_APP_NAME="Orb XR"
APP_DIR="$PROJECT_DIR/$DIST_APP_NAME.app"
ZIP_BASENAME="OrbXR"
NOTARIZE_ZIP="$PROJECT_DIR/$ZIP_BASENAME-notarize.zip"
DIST_ZIP="$PROJECT_DIR/$ZIP_BASENAME-arm64.zip"
SHA_FILE="$DIST_ZIP.sha256"

NOTARY_PROFILE="${NOTARY_PROFILE:-notarytool}"

if [ ! -d "$APP_DIR" ]; then
    echo -e "${RED}ERROR: $APP_DIR not found. Run ./build-release.sh first.${NC}" >&2
    exit 1
fi

# Resolve version + tag
APP_VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP_DIR/Contents/Info.plist")
RELEASE_TAG="${RELEASE_TAG:-v$APP_VERSION}"
echo -e "${BLUE}App version:${NC} $APP_VERSION"
echo -e "${BLUE}Release tag:${NC} $RELEASE_TAG"

# Zip for notarization
echo -e "${BLUE}Creating notarization zip...${NC}"
rm -f "$NOTARIZE_ZIP"
ditto -c -k --sequesterRsrc --keepParent "$APP_DIR" "$NOTARIZE_ZIP"

# Submit + wait
echo -e "${BLUE}Submitting to Apple notary service (profile: $NOTARY_PROFILE)...${NC}"
SUBMIT_OUTPUT=$(xcrun notarytool submit "$NOTARIZE_ZIP" \
    --keychain-profile "$NOTARY_PROFILE" \
    --wait --output-format json)
echo "$SUBMIT_OUTPUT"

STATUS=$(echo "$SUBMIT_OUTPUT" | /usr/bin/python3 -c "import json,sys; print(json.load(sys.stdin)['status'])")
SUBMISSION_ID=$(echo "$SUBMIT_OUTPUT" | /usr/bin/python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")

if [ "$STATUS" != "Accepted" ]; then
    echo -e "${RED}Notarization status: $STATUS${NC}" >&2
    xcrun notarytool log "$SUBMISSION_ID" --keychain-profile "$NOTARY_PROFILE" || true
    exit 1
fi
echo -e "${GREEN}Notarization accepted (id: $SUBMISSION_ID)${NC}"

# Staple
echo -e "${BLUE}Stapling ticket to app bundle...${NC}"
xcrun stapler staple "$APP_DIR"
xcrun stapler validate "$APP_DIR"

# Verify Gatekeeper now accepts it
spctl -a -vvv -t install "$APP_DIR"

# Build distribution zip + checksum
echo -e "${BLUE}Creating distribution zip...${NC}"
rm -f "$DIST_ZIP" "$SHA_FILE"
ditto -c -k --sequesterRsrc --keepParent "$APP_DIR" "$DIST_ZIP"
shasum -a 256 "$DIST_ZIP" | tee "$SHA_FILE"

rm -f "$NOTARIZE_ZIP"

if [ "${SKIP_UPLOAD:-}" = "1" ]; then
    echo -e "${YELLOW}SKIP_UPLOAD=1 — leaving artifacts in place; not uploading to GitHub.${NC}"
    echo -e "${GREEN}Artifacts:${NC}"
    ls -lh "$DIST_ZIP" "$SHA_FILE"
    exit 0
fi

if ! command -v gh >/dev/null 2>&1; then
    echo -e "${RED}gh CLI not found; install it or rerun with SKIP_UPLOAD=1.${NC}" >&2
    exit 1
fi

# Make sure the release exists; create it if not
REPO_ROOT=$(cd "$PROJECT_DIR" && git rev-parse --show-toplevel)
cd "$REPO_ROOT"

if gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
    echo -e "${BLUE}Uploading to existing release ${RELEASE_TAG}...${NC}"
    gh release upload "$RELEASE_TAG" "$DIST_ZIP" "$SHA_FILE" --clobber
else
    echo -e "${BLUE}Creating release ${RELEASE_TAG}...${NC}"
    gh release create "$RELEASE_TAG" "$DIST_ZIP" "$SHA_FILE" \
        --title "Orb XR $RELEASE_TAG" \
        --notes "Orb XR (macOS) $APP_VERSION — signed with Developer ID and notarized by Apple."
fi

echo -e "${GREEN}Done.${NC}"
gh release view "$RELEASE_TAG" --web 2>/dev/null || true
