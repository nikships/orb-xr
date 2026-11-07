# Orb XR (Mac)

Standalone SwiftUI Dock app for **Orb XR** —
pairs a Samsung Galaxy XR / Orb XR headset with this Mac. Display name is
**Orb XR**; binary/module remain `GhosttyXRCompanion` and bundle ID
`dev.ghosttyxr.companion`. Agent-oriented build/install rules:
[`../AGENTS.md`](../AGENTS.md).

## What it does

| Feature | Detail | Loopback port |
|---|---|---|
| **Pairing QR** | LAN IPv4 + ephemeral token + optional host `fp` → `ssh://user@ip:22?token=&fp=` | n/a (headset SSHes to macOS :22) |
| **Keyboard relay** | Host keystrokes → focused headset pane (armed / Symbiosis) | **45873** |
| **Window / desktop mirror** | SSH-auth control on **45874**; H.264/HEVC video on direct LAN TCP **45876**; Symbiosis pointer | **45874**, **45876** |
| **Terminal handoff** | Open local terminals on live `tmux -L gxr` sessions (attach-only) | **45875** |

Closing the main window does **not** quit — relay/mirror/handoff are app-lifetime
services. Dock click restores the window. Bundle ID: `dev.ghosttyxr.companion`.

## Pairing QR

```text
ssh://<NSUserName>@<wifi-ip>:22?token=<base64url-token>&fp=SHA256:<base64-host-pubkey-hash>
```

If no host key exists yet, `fp` is omitted (never fabricated). Token TTL ~30s
with auto-rotate. Remote Login is detected via `systemsetup` or TCP probe of
`127.0.0.1:22`; the app never auto-enables sshd.

## Requirements

- macOS 14+
- Xcode (full install for signed release; verified on Xcode 26.x)
- `xcodegen` (`brew install xcodegen`)

## Build

Project is generated from `project.yml`. `swift build` is not supported.

### Debug (ad-hoc)

```bash
cd ghosttyxr/mac-companion/GhosttyXR-Companion
xcodegen generate
xcodebuild -project GhosttyXRCompanion.xcodeproj -scheme GhosttyXRCompanion   -configuration Debug -derivedDataPath build   CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO build
open build/Build/Products/Debug/GhosttyXRCompanion.app
```

### Release (signed + notarized) — preferred for real testing

End users download `OrbXR-arm64.zip` (contains `Orb XR.app`) from
[GitHub Releases](https://github.com/nikships/orb-xr/releases).

```bash
cd ghosttyxr/mac-companion/GhosttyXR-Companion
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
./build-release.sh
NOTARY_PROFILE=gxr-notary SKIP_UPLOAD=1 ./notarize-and-release.sh
./install-local.sh   # replaces /Applications; preserves TCC grants
```

`install-local.sh` intentionally does **not** run `tccutil reset`.

Verify: `spctl -a -vvv -t install "/Applications/Orb XR.app"`

One-time notary credentials:

```bash
xcrun notarytool store-credentials notarytool   --apple-id <APPLE_ID> --team-id <TEAM_ID> --password <APP_SPECIFIC_PASSWORD>
```

### Unit tests

```bash
xcodegen generate
xcodebuild -project GhosttyXRCompanion.xcodeproj -scheme GhosttyXRCompanion   -configuration Debug -destination 'platform=macOS'   CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO test
```

## Layout

```text
Sources/GhosttyXRCompanion/
├── GhosttyXRCompanionApp.swift
├── PairingController.swift
├── Info.plist
├── Models/          PairingToken, ConnectionInfo, CompanionOnboarding
├── Settings/        CompanionSettings
├── Services/        pairing, keyboard, mirror, Symbiosis, handoff, tmux setup
└── Views/           root, onboarding, dashboard, settings, components
```

Shared window-mirror contract: [`../../protocol/window-mirror/`](../../protocol/window-mirror/).

## Design notes

- **No silent fallbacks** for IP or host fingerprint.
- **Buttons are 21:9 tiles** via `GxrButtonStyle` / `GxrTileRow`.
- **tmux `-L gxr` only** for handoff — never the user's default tmux socket;
  attach-only, never kill sessions.
- TCC: Accessibility (keyboard) + Screen Recording (mirror).
