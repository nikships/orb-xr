# Repository Guidelines

**Orb XR**: immersive cockpit for
orchestrating fleets of CLI coding agents (Claude Code, Codex, Gemini, Droid,
builds, shells). Each agent runs in its own floating SSH terminal pane in XR;
the terminal is the primitive, multi-agent orchestration is the product.
Spawning panes is the core gesture. The headset is a thin client; the Mac is the
source of truth (shells, agents, toolchains keep running when the headset
disconnects). Read **[VISION.md](VISION.md)** before product-shaping decisions
and **[docs/architecture.md](docs/architecture.md)** for the how. Keep changes
pointed at raising agent-orchestration bandwidth and preserving faithful,
low-latency libghostty terminal rendering.

## Architecture

| Component | Path | Stack | Role |
|---|---|---|---|
| Headset app | [`ghosttyxr/android/`](ghosttyxr/android/AGENTS.md) | Kotlin + Compose for XR | Panes, input routing, SSH client, voice pet, window mirror |
| Native core | [`ghosttyxr/zig-src/`](ghosttyxr/zig-src/AGENTS.md) | Zig 0.16.0 | `libghosttyxr.so` — libghostty (render) + libssh2 (transport) via JNI |
| Mac companion | [`ghosttyxr/mac-companion/`](ghosttyxr/mac-companion/AGENTS.md) | SwiftUI + Metal | Floating orb overlay: pairing QR, keyboard relay, window mirror, terminal handoff |
| Firebase backend | [`firebase/`](firebase/AGENTS.md) | Cloud Functions (Node 20) | Entitlement-gated STT/streaming TTS, onboarding access, Play verification + RTDN |
| Marketing site | [`website/`](website/AGENTS.md) | Next.js 16 static export | Product marketing → Firebase Hosting `ghosttyxr-headset` |
| Play listing | [`store/play/`](store/play/AGENTS.md) | Assets + copy | Play Console listing text, icons, XR screenshots |
| Asset tools | [`ghosttyxr/tools/`](ghosttyxr/tools/AGENTS.md) | Python + shell | Focus-mode skybox cubemaps; first-flight coach narration bake |
| Docs | [`docs/`](docs/AGENTS.md) | Markdown | Architecture, CWD routing, onboarding, monetization, streaming notes |
| Protocol contracts | [`ghosttyxr/protocol/`](ghosttyxr/protocol/window-mirror/) | JSON + vectors | Cross-platform wire contracts (window-mirror first) |

The removed Android `companion-protocol` and `shared-ui` modules belonged to a
retired phone companion. The Gradle graph is `:app` only; do not recreate them.
Empty leftover dirs under `ghosttyxr/android/` are not modules.

**Product surface agents should know about:**

- Host sessions on dedicated tmux socket `-L gxr` (empty conf, survives disconnect)
- Session resume / snapshots + host-side terminator from the resume picker
- `CockpitLifecycle` / `DefaultCockpitLifecycle` own pane identity, focus,
  fresh/resume selection, capture, and host-session termination (1..6 panes)
- Voice pet (palm-up) + Gemini tools; pet TTS via Firebase `petTts`; STT via `stt`
- **Monetization:** Free tier = cockpit with no AI; Pro (`pro_monthly`, Play
  Billing) unlocks all voice AI. Entitlement is server-side (Firestore
  `entitlements/{uid}`, written by `verifyPurchase`/`playRtdn` functions).
  See [`docs/monetization.md`](docs/monetization.md).
- First flight receives one bounded server-owned onboarding access window; it
  ends when onboarding completes and never replaces a paid Play entitlement
- Window mirror (H.264/HEVC video on a direct LAN TCP socket, port 45876; control
  and input over the SSH tunnel, port 45874)
- Host keyboard relay (Mac keystrokes → focused pane, port 45873)
- Terminal handoff opens the live `tmux -L gxr` sessions in local Mac terminals
  without killing host work (port 45875)
- New-pane CWD inheritance probes host `tmux pane_current_path`; OSC 7 is a
  fallback only. The PTY timing is delicate — see
  [`docs/cwd-routing-handoff.md`](docs/cwd-routing-handoff.md)

## Key commands

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd ghosttyxr/android
./gradlew assembleDebug                    # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest           # pure-JVM unit tests
./gradlew :app:connectedDebugAndroidTest   # instrumented; official XR AVD only
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Prebuilt `libghosttyxr.so` is checked into
`ghosttyxr/android/app/src/main/jniLibs/arm64-v8a/`. Android-only work needs
neither Zig nor the NDK. Rebuild native only when editing `ghosttyxr/zig-src/`:

```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358"
cd ghosttyxr/zig-src && zig build jni -Dssh=true   # copies .so into jniLibs
```

Mac companion local install (signed + notarized, replace `/Applications` — see
[`ghosttyxr/mac-companion/AGENTS.md`](ghosttyxr/mac-companion/AGENTS.md)):

```bash
cd ghosttyxr/mac-companion/GhosttyXR-Companion
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
./build-release.sh && NOTARY_PROFILE=gxr-notary SKIP_UPLOAD=1 ./notarize-and-release.sh
./install-local.sh
```

Website (marketing):

```bash
cd website && npm ci && npm run check   # lint + typecheck + static export → out/
```

Firebase functions:

```bash
cd firebase/functions && npm install && npm test
```

## Hard constraints / landmines

- **Package name** permanently `com.ghosttyxr.app`. Never rename `applicationId`.
- **SDK:** `compileSdk`/`targetSdk = 37`, `minSdk = 24`, arm64-only
  (`abiFilters += "arm64-v8a"`). x86 emulators will not run the app.
- **JDK 21 only** for Gradle. JDK 25 crashes the AGP Kotlin compiler with no
  useful error. On this Mac:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- **Terminal fidelity is sacred.** Do not degrade ANSI, Kitty keyboard protocol,
  true color, or scrollback to make something "XR-friendly." libghostty paints
  glyphs; chrome uses `GhosttyTokens`.
- **tmux socket is `-L gxr`.** Every host-side tmux call must use
  `HostSessionBootstrap.TMUX_SOCKET` / `tmux -L gxr`. Do not touch the user's
  default tmux server.
- **Mac is source of truth.** Agents and shells keep running when the headset
  disconnects; reconnect attaches, it does not restart work.
- **No silent pairing fallbacks.** Never invent an IP or host fingerprint. If
  the host key is missing, omit `fp` rather than guessing. Surface real errors.
- **No secrets in the repo, no AI keys in the APK — period.** Deepgram + Gemini
  keys live only as Firebase function secrets; Play SA JSON as `PLAY_SA_KEY`.
  There is no BYO-key path. Voice AI is Pro-gated server-side; never re-add a
  client-side key or bypass the entitlement check.
- **CWD inheritance is host-probed and timing-sensitive.** `PaneCwdProbe`
  queries `tmux -L gxr ... '#{pane_current_path}'` over a short-lived verified
  shell, then passes the concrete path to `new-session -c`; OSC 7 is fallback
  only. Do not collapse this into command substitution or inject a second
  command into the new pane. Read
  [`docs/cwd-routing-handoff.md`](docs/cwd-routing-handoff.md) first.
- **Native rebuilds require NDK.** Fail fast if `ANDROID_NDK_HOME` is unset;
  wrong sysroot only fails at `dlopen` on device.
- **Zig `-Dssh=true` is required** when building the JNI lib (single-`.so`
  design; Kotlin expects libssh2 exports in the same binary).
- **Firebase Hosting:** marketing site CI deploys `website/out` to project
  `ghosttyxr-headset` with `--force`. Do not casually redeploy `firebase/public`
  hosting over the live marketing root without coordinating.

## Where to put work

| Change | Put it here |
|---|---|
| UI chrome, panels, pairing scanner, pane chrome | `ghosttyxr/android/app/.../ui/` |
| First-flight setup / live coach | `ghosttyxr/android/app/.../intro/` |
| Billing, entitlement, onboarding-access clients | `ghosttyxr/android/app/.../billing/` |
| Cockpit lifecycle, resume, tmux bootstrap, reconnect, CWD | `ghosttyxr/android/app/.../terminal/session/` |
| SSH transport, host-key TOFU, pairing store | `ghosttyxr/android/app/.../terminal/ssh/` (Kotlin) or `ghosttyxr/zig-src/` (native) |
| Input mapping, controller, on-screen keyboard | `ghosttyxr/android/app/.../terminal/input/` |
| Voice STT, Gemini tools, voice pet | `ghosttyxr/android/app/.../terminal/voice/` + `hands/` |
| Window mirror (direct-TCP video) | `ghosttyxr/android/app/.../terminal/windowmirror/` |
| Host keyboard relay client | `ghosttyxr/android/app/.../terminal/hostkeyboard/` |
| Terminal handoff client | `ghosttyxr/android/app/.../terminal/handoff/` |
| Controller wiring / Android construction | `ghosttyxr/android/app/.../wiring/` |
| libghostty render or libssh2 JNI | `ghosttyxr/zig-src/src/bridge/` |
| Pairing QR, Mac capture, keyboard relay, handoff host | `ghosttyxr/mac-companion/` |
| Voice / entitlement Cloud Functions | `firebase/functions/` |
| Marketing site | `website/` |
| Play Store copy / screenshots / icon | `store/play/` |
| Focus-mode skybox cubemaps | `ghosttyxr/tools/build_environments.py` → android assets |
| First-flight coach narration bake | `ghosttyxr/tools/generate_narration.sh` → android `res/raw/` |
| Architecture / design notes | `docs/` |

## Verification

- **Unit tests** for pure logic (`:app:testDebugUnitTest`): cockpit lifecycle,
  reconnect, key maps, voice controllers, onboarding, billing, tmux
  bootstrap/terminator/CWD probe parsing, pairing, pane focus, relay protocols.
  Prefer injection + fakes; no emulator required.
- **Instrumented tests** only on the official Android XR AVD named
  **`GhosttyXR_Headset`** (one emulator at a time). Confirm with `adb devices`
  before `:app:connectedDebugAndroidTest`. See
  [`ghosttyxr/android/TESTING.md`](ghosttyxr/android/TESTING.md).
- **Companion:** a Debug build is not enough for real testing. Rebuild signed +
  notarized and reinstall to `/Applications` after companion changes.
- **Native:** after zig-src edits, rebuild with `-Dssh=true`, confirm the
  checked-in `.so` under jniLibs updated, then smoke the bridge on device/AVD.
- **Website:** `cd website && npm run check` before landing marketing changes.
- **Firebase:** `cd firebase/functions && npm test` before function deploys.

## Commit & PR conventions

- Conventional Commits with scopes: `feat(xr):`, `fix(session):`,
  `feat(voice):`, `feat(companion):`, `feat(website):`, `build(openssl):`,
  `fix(ssh):`, `refactor(theme):`, `docs:`.
- PRs target `main`, describe the change and verification run, and link
  issues. Mac companion releases ship via signed/notarized CI on push to
  `main` touching `ghosttyxr/mac-companion/**`. Website deploys on push to
  `main` touching `website/**`.

## Google Play publishing

- Play Store app name: **Orb XR**
- Package name: **`com.ghosttyxr.app`**
- GCP project: **`ghosttyxr-play-2026`**
- Service account: **`play-console-cli@ghosttyxr-play-2026.iam.gserviceaccount.com`**
- Local `gplay` config: **`~/.gplay/config.json`**
- Local service-account key: **`~/.gplay/play-console-cli.json`**

The GCP project has `androidpublisher.googleapis.com` and
`playdeveloperreporting.googleapis.com` enabled. The app must already exist in
Play Console before the Publisher API can manage it; Google does not allow
creating a Play app listing through the API.

```bash
gplay apps list
gplay auth setup --auto --project ghosttyxr-play-2026
```

If `gplay` returns 401/403, check Play Console user permissions for the service
account (needs release-management on **Orb XR**).

### Mandatory Android internal deployment

Whenever an agent pushes changes touching `ghosttyxr/android/**` directly to the
default branch (`main`), or merges a PR containing such changes into
`main`, it must also deploy that resulting revision to Google Play internal
testing. Do not stop after the push or merge and merely suggest deployment.

Before the push/merge, ensure `versionCode` in
`ghosttyxr/android/app/build.gradle.kts` is higher than the currently published
Android XR internal version:

```bash
gplay tracks releases list \
  --package com.ghosttyxr.app \
  --track android_xr:internal \
  --output json
```

After the push/merge succeeds, build and validate the resulting `main`
revision with JDK 21 and the Play upload signing configuration from
`ghosttyxr/android/local.properties`:

```bash
cd ghosttyxr/android
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease
```

Deploy the signed bundle to the dedicated Android XR internal track:

```bash
gplay release \
  --package com.ghosttyxr.app \
  --track android_xr:internal \
  --bundle app/build/outputs/bundle/release/app-release.aab \
  --skip-metadata \
  --skip-screenshots \
  --wait
```

Then verify that the new version is published:

```bash
gplay tracks releases list \
  --package com.ghosttyxr.app \
  --track android_xr:internal \
  --output json
```

The track must be **`android_xr:internal`**, not the standard `internal` track.
Google Play rejects bundles requiring `android.software.xr.api.spatial` on the
standard mobile internal track. If build, upload, processing, or verification
fails, report the failure and do not claim the push/merge workflow is complete.
