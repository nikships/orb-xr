# Orb XR

## Download

Get the Android XR headset APK and the signed, notarized Mac companion from
[GitHub Releases](https://github.com/nikships/orb-xr/releases).
The initial APK is a release-mode, **debug-signed sideload build**, not a Play
Store release. It cannot update an installation signed with a different key.
Firebase-backed features depend on the deployed service and device attestation;
they may not work in a sideloaded build.

This public repository starts from a clean source snapshot. The earlier private
repository and its history are not published.

## License

Orb XR source is available under the [MIT License](LICENSE). Third-party
software and assets retain their own terms; see [third-party notices](third-party-licenses/README.md).

**Orb XR** is an
immersive cockpit for orchestrating fleets of CLI coding agents. Put on a
Samsung Galaxy XR headset, scan the QR code on your Mac, and a grid of
floating SSH terminal panes appears in 3D space, each one a seat for an
agent (`claude`, `codex`, `gemini`, `droid`, a build, a plain shell). You
supervise several at once by *looking around* instead of alt-tabbing.

libghostty renders each terminal natively on the headset (real ANSI, Kitty
keyboard protocol, true color); libssh2 talks to macOS Remote Login. The Mac
is where your shells, agents, files, and CPU live — the headset is the window
you steer them through. Host sessions run on a dedicated `tmux -L gxr` socket
and keep running when the headset disconnects.

> **Why a headset?** Agentic coding is bottlenecked by attention, not typing.
> A flat monitor hides parallel agents behind tabs; XR gives you the spatial
> bandwidth to keep several of them glanceable at once. See
> **[VISION.md](VISION.md)** for the full thesis and
> **[docs/architecture.md](docs/architecture.md)** for how the system is wired.

Contributor / agent map: **[AGENTS.md](AGENTS.md)**.

## Repo layout

| Path | What |
|---|---|
| [`ghosttyxr/android/`](ghosttyxr/android/AGENTS.md) | Headset app (Kotlin + Compose for XR) |
| [`ghosttyxr/zig-src/`](ghosttyxr/zig-src/AGENTS.md) | Native `libghosttyxr.so` (libghostty + libssh2 JNI) |
| [`ghosttyxr/mac-companion/`](ghosttyxr/mac-companion/AGENTS.md) | Mac SwiftUI companion (pairing, keyboard, window mirror, handoff) |
| [`firebase/`](firebase/AGENTS.md) | Cloud Functions: STT/TTS, entitlements, Play verification |
| [`website/`](website/AGENTS.md) | Marketing site (Next.js → Firebase Hosting) |
| [`store/play/`](store/play/AGENTS.md) | Play listing copy + graphics |
| [`docs/`](docs/AGENTS.md) | Architecture, monetization, CWD, onboarding notes |
| [`ghosttyxr/tools/`](ghosttyxr/tools/AGENTS.md) | Focus-mode skybox baker + coach narration generator |
| [`ghosttyxr/protocol/window-mirror/`](ghosttyxr/protocol/window-mirror/) | Shared window-mirror wire contract |
| `chuchu/` | Reference Android terminal (gitignored locally; see below) |

## Quickstart: Android headset app

The prebuilt native library (`libghosttyxr.so`, arm64) is checked in, so an
Android-only build does **not** require Zig or the NDK.

### Prerequisites

- **JDK 21** (not 25) on `JAVA_HOME`
- Android SDK with platform 37; `ANDROID_HOME` set
- Galaxy XR headset or the official Android XR AVD `GhosttyXR_Headset`
  (see [`ghosttyxr/android/TESTING.md`](ghosttyxr/android/TESTING.md))

### Build & run

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd ghosttyxr/android

./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release / Play bundle (needs upload signing keys in `local.properties`):

```bash
./gradlew :app:lintRelease :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

The app is **arm64-only** (`abiFilters += "arm64-v8a"`); x86 emulators will
not run it. Package name is locked: `com.ghosttyxr.app`.

## Google Play publishing

| Item | Value |
|---|---|
| Store name | **Orb XR** |
| Package | **`com.ghosttyxr.app`** |
| GCP project | `ghosttyxr-play-2026` |
| Service account | `play-console-cli@ghosttyxr-play-2026.iam.gserviceaccount.com` |
| Local config | `~/.gplay/config.json` |
| Local key | `~/.gplay/play-console-cli.json` |
| Internal track | **`android_xr:internal`** (not plain `internal`) |

```bash
gplay apps list
gplay auth setup --auto --project ghosttyxr-play-2026

# list current XR internal releases
gplay tracks releases list \
  --package com.ghosttyxr.app \
  --track android_xr:internal \
  --output json

# after android/** lands on main (see AGENTS.md mandate)
cd ghosttyxr/android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease
gplay release \
  --package com.ghosttyxr.app \
  --track android_xr:internal \
  --bundle app/build/outputs/bundle/release/app-release.aab \
  --skip-metadata --skip-screenshots --wait
```

Listing copy and screenshots live under [`store/play/`](store/play/).

## Rebuild the native library

Only when editing `ghosttyxr/zig-src/`:

```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358"
cd ghosttyxr/zig-src
zig build jni -Dssh=true
# copies libghosttyxr.so → ../android/app/src/main/jniLibs/arm64-v8a/
```

`-Dssh=true` is required (single-`.so` design). Commit the jniLibs binary when
it should ship.

## Mac companion

Dock app: pairing QR, host keyboard relay (port 45873), window/desktop mirror
(control 45874, direct-TCP video 45876), terminal handoff to live `tmux -L gxr`
sessions (45875).

**Local install after companion changes** (signed + notarized — Debug is not
enough for real testing):

```bash
cd ghosttyxr/mac-companion/GhosttyXR-Companion
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
./build-release.sh
NOTARY_PROFILE=gxr-notary SKIP_UPLOAD=1 ./notarize-and-release.sh
./install-local.sh
```

Debug-only scaffold:

```bash
xcodegen generate
xcodebuild -project GhosttyXRCompanion.xcodeproj -scheme GhosttyXRCompanion \
  -configuration Debug -derivedDataPath build \
  CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO build
open build/Build/Products/Debug/GhosttyXRCompanion.app
```

See [`ghosttyxr/mac-companion/AGENTS.md`](ghosttyxr/mac-companion/AGENTS.md).

## Firebase backend & marketing site

- **Functions** (`firebase/`): entitlement-gated `stt` / `petTts`, onboarding
  access, Play `verifyPurchase` + RTDN. Project `ghosttyxr-headset`.
- **Marketing site** (`website/`): Next.js 16 static export deployed by CI to
  Firebase Hosting (`https://ghosttyxr-headset.web.app/`).
- Companion download mini-site artifacts still live under `firebase/public/`;
  website CI hosting deploys own the live site root. See each folder's AGENTS.md.

```bash
# functions
cd firebase/functions && npm install && npm test

# website
cd website && npm ci && npm run check
```

## Toolchain pins

| Component | Version | Pinned where |
|---|---|---|
| Zig | `0.16.0` | `ghosttyxr/zig-src/.zig-version` |
| Android NDK | r28c (`28.2.13676358`) | verified install path |
| JDK | **21** (Gradle) | `JAVA_HOME`; bytecode target JVM 17 |
| AGP | `9.4.1` (built-in Kotlin; no `kotlin-android` plugin) | `gradle/libs.versions.toml` |
| Gradle | `9.7.1` | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | `2.4.20` | `gradle/libs.versions.toml` |
| Compose BOM | `2026.09.00` | `gradle/libs.versions.toml` |
| Android `compileSdk` / `targetSdk` | `37` | `app/build.gradle.kts` |
| Android `minSdk` | `24` | `app/build.gradle.kts` |
| Website Node | `24` | `website/.nvmrc` |
| Firebase Functions | Node 20 | `firebase/functions/package.json` |

**JDK 21 vs 25.** AGP's Kotlin compiler crashes under JDK 25 with no useful
error. If Gradle dies immediately, switch `JAVA_HOME` back to 21.

**AGP 9 built-in Kotlin.** Apply only `com.android.application` + Compose
compiler plugin. JVM target comes from `compileOptions` + top-level
`kotlin { compilerOptions { … } }`.

**CI.** Android CI builds `assembleDebug` + unit tests on PRs. XR instrumented
tests are local-only (no XR image on GitHub runners). Companion tests and
signed release, plus website deploy, are separate workflows under `.github/`.

## Upstream Zig dependencies

`build.zig.zon` pins Zig **0.16.0**, matching upstream ghostty `main`:

- `ghostty-org/ghostty` @ pinned `main` commit
- `zigimg/zigimg` @ pinned `master` commit (the 0.16 line)
- `anand-92/openssl-zig` @ pinned `zig-0.16` commit (via libssh2) — upstream
  `jossephus/openssl-zig` is still 0.15.2-only
- libssh2 upstream 1.11.1 vendored under `zig-src/src/vendor/libssh2`

The openssl pin is duplicated in `zig-src/build.zig.zon` and
`zig-src/src/vendor/libssh2/build.zig.zon`; keep them identical.

## Reference codebase: `chuchu/`

`chuchu/` (gitignored) is a daily-driver Android terminal that uses the same
libghostty + libssh2 + Compose-Canvas pattern. Do not commit `chuchu/`.

## Status

Headset app ships on Play internal track `android_xr:internal`. Companion
ships signed/notarized via CI. Voice AI is Free cockpit + Pro (`pro_monthly`)
server entitlement; first flight gets a bounded onboarding access window.

**Caveats:**

- Native lib (~28 MB) is checked in directly (not Git LFS).
- Compose-for-XR is still alpha; some surface APIs may shift.
- Always use track `android_xr:internal` for XR bundles; plain `internal`
  rejects `android.software.xr.api.spatial`.
