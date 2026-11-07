# Mac companion — Orb XR (SwiftUI)

**Orb XR** is the standalone Dock app that pairs a Galaxy XR
(Orb XR) headset with this Mac, relays the host keyboard, mirrors a
window/display, and opens handed-off cockpit tmux sessions in local Mac
terminals. The **only** surface is a floating always-on-top **orb** — a Metal
galaxy marble in a transparent non-activating panel that fans out a radial menu
(QR · keyboard · mirror · settings) into small glass panels. Onboarding runs
inside the orb too; there is no main window or dashboard. A conventional
Settings window (⌘,) is still spawnable from the menu bar item. The app is
always a regular `.regular` Dock app (`LSUIElement=NO`); relay/handoff/mirror
are app-lifetime services, and clicking the Dock icon re-shows the orb.

Display name is **Orb XR**; binary/module remain `GhosttyXRCompanion`
and bundle ID `dev.ghosttyxr.companion` (TCC grants and install paths depend on
them).

**Pairing:** detects the Mac's LAN IPv4, rotates an ephemeral token, surfaces the SSH host fingerprint, and renders a QR encoding `ssh://user@ip:22?token=&fp=`. The headset scans it and SSHes into macOS Remote Login.

**Keyboard relay (port 45873):** `MacKeyboardRelayController` captures host keystrokes (when armed / during Symbiosis) and forwards them over the SSH tunnel to the focused headset terminal.

**Window / desktop mirror:** `MacWindowMirrorController` + `WindowMirrorRelayServer` + `WindowVideoEncoder` + `WindowMirrorMediaServer` stream a selected window or full display as hardware H.264/HEVC over a direct LAN TCP socket; `SymbiosisPointerService` and `RemoteInputInjectionService` handle pointer ownership and injected input.

**Terminal handoff:** `TerminalHandoffServer` listens on loopback port `45875`
behind a verified SSH direct-tcpip tunnel. `TerminalHandoffOpener` opens one
local terminal per live `tmux -L gxr` session; handoff attaches and never kills
the host session.

Full pairing/design notes: [`GhosttyXR-Companion/README.md`](GhosttyXR-Companion/README.md).

## Build

Xcode project is generated from `project.yml` by [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). `swift build` will not work: this is a `.app` bundle (Info.plist, assets, entitlements).

### Debug (ad-hoc)

```bash
cd GhosttyXR-Companion
xcodegen generate
xcodebuild -project GhosttyXRCompanion.xcodeproj -scheme GhosttyXRCompanion \
  -configuration Debug -derivedDataPath build \
  CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO build
open build/Build/Products/Debug/GhosttyXRCompanion.app
```

### Release (signed + notarized)

Requires Apple Developer Program membership. CI ships on push to `main` when `ghosttyxr/mac-companion/**` changes: [`.github/workflows/mac-release.yml`](../../.github/workflows/mac-release.yml).

```bash
cd GhosttyXR-Companion
./build-release.sh           # xcodegen + Release arm64, Developer ID, hardened runtime
./notarize-and-release.sh    # notarize, staple, zip, upload GitHub release
# RELEASE_TAG=v0.2.0 · SKIP_UPLOAD=1 keep artifacts local
```

One-time notary credentials: `xcrun notarytool store-credentials notarytool --apple-id <ID> --team-id <TEAM> --password <APP_SPECIFIC_PASSWORD>`.

## Local install after every change (default)

After **any** companion change, rebuild signed+notarized and install to `/Applications`. Do not stop at a source edit or Debug build. Full Xcode (not just CLT) is required for signing.

On this Mac:

```bash
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
cd GhosttyXR-Companion
./build-release.sh
NOTARY_PROFILE=gxr-notary SKIP_UPLOAD=1 ./notarize-and-release.sh
./install-local.sh
```

Prefer `install-local.sh` after `build-release.sh` + notarize with `SKIP_UPLOAD=1`. It quits the running app, replaces `/Applications/Orb XR.app` (and removes the old `/Applications/Agent Cockpit.app` and `/Applications/GhosttyXRCompanion.app` copies), and reopens it.

**`install-local.sh` intentionally does NOT run `tccutil reset`.** Same bundle ID + same Developer ID signature keeps Accessibility and Screen Recording grants across the rename. Do not add a TCC reset to the install path.

Verify: `spctl -a -vvv -t install "/Applications/Orb XR.app"` (expect `source=Notarized Developer ID`).

## Layout (`Sources/GhosttyXRCompanion/`)

- `GhosttyXRCompanionApp.swift` — entry: `MenuBarExtra` (status + Show/Hide Orb, Reset Orb Position, Settings…, Quit) + `Settings` (⌘,). There is no `Window` scene; the orb is an AppKit panel. Delegate owns `CompanionCore` and starts the orb on launch; `applicationShouldHandleReopen` re-shows the orb; `applicationShouldTerminateAfterLastWindowClosed → false`.
- `CompanionCore.swift` — `@MainActor` owner of every app-lifetime service (settings, pairing, tmux setup, keyboard relay, window mirror, terminal handoff, orb state) plus the relay ↔ mirror wiring (Symbiosis pointer ownership, emergency stop). Constructed eagerly: the relay and handoff listeners must be up before the headset connects, so this cannot be lazily tied to a visible window.
- `PairingController.swift` — `@MainActor` ObservableObject: token rotation + pairing state refresh.
- `Settings/CompanionSettings.swift` — `@MainActor` ObservableObject for app prefs (launch-at-login via `SMAppService`).
- `Info.plist` — usage strings (local network, input monitoring, screen capture); deployment macOS 14+. `LSUIElement=NO` (regular Dock app).
- `Models/`
  - `PairingToken` — base64url + TTL
  - `ConnectionInfo` — builds the `ssh://` URL
- `Services/`
  - **Pairing:** `NetworkInterfaceService` (ranked IPv4), `RemoteLoginService` (sshd detection), `SSHKeyService` (keygen + authorized_keys + host fingerprint), `QRCodeService`
  - **Keyboard:** `MacKeyboardRelayController` (relay server, capture arming, Symbiosis capture follow)
  - **Mirror:** `MacWindowMirrorController` (orchestrator), `WindowMirrorRelayServer` (SSH-authenticated control/input channel, mints the media session token), `WindowVideoEncoder` (VideoToolbox H.264/HEVC, infinite GOP, `AverageBitRate` + `DataRateLimits`, reconfigured in place), `WindowMirrorMediaServer` (direct LAN TCP video, optional AES-GCM), `WindowCaptureService`, `WindowSelectionService`, `WindowMirrorProtocol`
  - **Input / Symbiosis:** `SymbiosisPointerService` (pointer ownership, deltas), `RemoteInputInjectionService` (inject into Mac)
  - **Terminal handoff:** `TerminalHandoffServer`, `TerminalHandoffProtocol`, `TerminalHandoffOpener`, `TmuxService`, `TmuxSetupController`
  - **Models also include** `CompanionOnboarding`; mirror resolution ladder lives beside mirror services
- `Views/`
  - `SettingsView` (⌘, — General + Pairing + Mirror tabs; General holds orb opacity, Pairing holds full URL / host fingerprint / dev SSH password), `DesignSystem` (color tokens only)
  - `Orb/` — the whole product surface:
    - `OrbShellView` composes orb + radial menu + panel layer and drives drag-to-move; `OrbShellState` holds menu/panel/onboarding state; `OrbShellMetrics` is the single source of geometry shared by layout and hit-testing
    - `OrbPanelController` / `OrbPanel` — borderless transparent `.nonactivatingPanel` at `.floating` level, joins all Spaces, position persisted and clamped back on-screen
    - `OrbView` + `OrbRenderer` + `OrbShader.metal` + `OrbMotion` — the Metal orb
    - `OrbMenu`, `OrbPanelShell`, `OrbQrPanel`, `OrbKeyboardPanel`, `OrbMirrorPanel`, `OrbSettingsPanel`, `OrbOnboarding`
  - `Components/FullScreenQR` (`FullScreenQRController` / `QRImageView`)

## Conventions

- **No silent fallbacks** for IP or host fingerprint. Surface errnos / "unknown"; never fabricate an address or fingerprint. If the host key file is absent, omit `fp` rather than guessing.
- Swift 5.9, macOS 14+ (`project.yml`), hardened runtime enabled for release.
- Commits: Conventional Commits with companion scope, e.g. `feat(companion):`, `fix(companion):`, `refactor(companion):`.

## Landmines

1. **Window mirror wire protocol** is owned by the shared contract at
   [`ghosttyxr/protocol/window-mirror/`](../protocol/window-mirror/)
   (`contract-v1.json` + frozen vectors). Swift `WindowMirrorProtocol.swift` and
   Android `WindowMirrorProtocol.kt` are adapters — do not change IDs, port
   `45874`, media port `45876`, wire version `1`, feature version `5`, or defaults on one side only.
   Update the contract + both test suites in the same change.
2. **TCC permissions:** Accessibility (keyboard capture / input injection) and Screen Recording (mirror) are required. Do not casually `tccutil reset`; `install-local.sh` preserves grants on purpose.
3. **Hardened runtime / entitlements** matter for release (`build-release.sh` + `entitlements.plist`). Unsigned Debug is fine locally; shipping / `/Applications` path is signed+notarized.
4. **Pairing QR format:** `ssh://user@ip:22?token=<base64url>&fp=SHA256:<host-fp>`. Omitting `fp` when no host key exists is correct; do not invent one.
5. **Do not break token rotation or Remote Login detection.** Token TTL ~30s with auto-rotate; Remote Login uses `systemsetup` when allowed, else TCP probe of `127.0.0.1:22`. Never auto-enable sshd without user action (open Sharing pane only).
6. **The orb panel must never steal focus.** It is a `.nonactivatingPanel` and
   `canBecomeKey` is true *only* while a panel actually needs typing. Making it
   activate/key by default breaks the keyboard relay: arming forwards host
   keystrokes from whatever app you were using, so a focus steal on orb click
   would silently retarget or drop them. Verify with a synthetic click on the
   orb while another app is frontmost — the frontmost app must not change.
7. **The orb panel is a full-rect transparent window, so hit-testing is
   explicit.** `OrbContainerView.hitTest` returns nil outside the drawn chrome
   (orb circle, menu item circles, measured panel rect) or the panel would eat
   clicks across a 480×780 area of the desktop. Anything newly drawn must also
   register its region in `OrbHitRegions`.
8. **The orb is a port, not an original.** `website/src/components/voice/orb-renderer.ts`
   is the source of truth for motion + shader; `OrbMotion.swift` and
   `OrbShader.metal` mirror it, and `OrbMotionTests` pins the Swift motion to
   reference vectors generated from that TS function. Keep the three in sync and
   leave `MTL_FAST_MATH: NO` alone — the starfield's
   `fract(sin(x) * 43758.5453)` turns a 1-ULP `sin` difference into a visibly
   different star field.
9. **Dragging the orb must use screen coordinates, and one gesture must own both
   tap and drag.** Two bugs live here, both already fixed in `OrbShellView`:
   every SwiftUI coordinate space (including `.global`) is anchored to the
   window, so using `DragGesture`'s `translation` to move that same window makes
   the orb crawl at a fraction of the cursor — diff `NSEvent.mouseLocation`
   instead. And a `Button` alongside a drag fires on every mouse-up, so ending a
   drag would open the menu; the gesture tracks `didMove` past a slop threshold
   and only taps when the press never travelled.
10. **`showSettingsWindow:` does not work on macOS 26+.** `NSApp.sendAction`
    returns `true` and no window opens, so it looks like it succeeded. The orb
    is AppKit and can't use SwiftUI's `openSettings`, so
    `CompanionAppDelegate.openSettingsWindow` fires the app menu's own Settings
    item, found by its ⌘, key equivalent (the title is localized). If you touch
    this, verify a window actually appears — do not trust the return value.

## CI

- [`.github/workflows/mac-companion-tests.yml`](../../.github/workflows/mac-companion-tests.yml):
  PR/push path filter on `ghosttyxr/mac-companion/**` and
  `ghosttyxr/protocol/window-mirror/**`. Runs XcodeGen + unit tests only
  (`contents: read`; no signing, notarization, tags, or releases).
- [`.github/workflows/mac-release.yml`](../../.github/workflows/mac-release.yml):
  on `push` to `main` with path filter `ghosttyxr/mac-companion/**` (and the
  workflow file itself), plus `workflow_dispatch`. Builds, signs, notarizes, and
  publishes `OrbXR-arm64.zip` (contains `Orb XR.app`) to GitHub Releases.
