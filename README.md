# Orb XR

## Download and install

**Latest release: [Orb XR 0.1.15](https://github.com/nikships/orb-xr/releases/tag/v0.1.15)**

| Device | Download | Requirements |
|---|---|---|
| Mac | [Download Orb XR for macOS](https://github.com/nikships/orb-xr/releases/download/v0.1.15/OrbXR-arm64.zip) | Apple silicon, macOS 14 or later. Signed with Developer ID and notarized by Apple. |
| Headset | [Download the Android XR APK](https://github.com/nikships/orb-xr/releases/download/v0.1.15/orb-xr-0.1.15-sideload.apk) | Samsung Galaxy XR or another compatible arm64 Android XR headset. |

Checksums and third-party notices are available on the
[release page](https://github.com/nikships/orb-xr/releases/tag/v0.1.15).

### 1. Set up the Mac

1. Download and unzip `OrbXR-arm64.zip`, move **Orb XR.app** to Applications,
   and open it.
2. Follow the companion's setup. Enable **Remote Login** in System Settings →
   General → Sharing and allow your Mac user account. The app can open the
   settings page for you.
3. Install `tmux` when prompted. The companion can guide you through installing
   Homebrew and `tmux` if needed. Keep the Mac and headset on the same local
   network.
4. Enter and confirm your Mac login password in the companion, then leave its
   pairing QR code visible.

> **Keep the pairing QR private.** The current companion encodes your Mac login
> password in the QR for initial SSH pairing. Anyone who can read the QR can
> recover that password. Don't share screenshots or show it in a public place.

### 2. Install the headset app

1. Download the APK to the Mac. Download
   [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)
   for Mac and unzip it in Downloads, creating `~/Downloads/platform-tools`.
2. Enable developer options and USB debugging on the headset. Connect it to the
   Mac with a USB data cable and approve the debugging prompt in the headset.
3. In Terminal, check that the headset is listed as `device`:

   ```bash
   "$HOME/Downloads/platform-tools/adb" devices
   ```

   Then install the downloaded APK:

   ```bash
   "$HOME/Downloads/platform-tools/adb" install -r "$HOME/Downloads/orb-xr-0.1.15-sideload.apk"
   ```

4. Open **Orb XR** on the headset, grant the requested permissions, and scan the
   QR shown by the Mac companion. Follow the in-app setup to enter the cockpit.
   Grant macOS Accessibility and Screen Recording permissions when prompted if
   you want keyboard relay or window mirroring.

The APK is **debug-signed for sideloading**, not Play-signed. Android will not
install it over an existing copy signed with a different key. Removing an
existing app can also remove its local data.

### 3. Optional: enable voice

Voice is bring-your-own-key. Create a Gemini API key in
[Google AI Studio](https://aistudio.google.com/api-keys), then enter it in
**Settings → Voice** on the headset. The key is stored in encrypted Android
preferences. Voice audio and terminal context requested by voice tools are sent
directly to Gemini under your account; Google's terms and charges apply.
The terminal cockpit works without a key. No Orb XR subscription or Firebase
project is required.

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
| `firebase/` | Legacy backend and hosting files, not used by the app |
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

## Legacy backend & marketing site

- **Functions** (`firebase/`): legacy code, not used by the BYOK app. Do not deploy.
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

The open-source headset build has no subscription. Voice requires a
user-supplied Gemini key; the terminal cockpit works without one.

**Caveats:**

- Native lib (~28 MB) is checked in directly (not Git LFS).
- Compose-for-XR is still alpha; some surface APIs may shift.
- Always use track `android_xr:internal` for XR bundles; plain `internal`
  rejects `android.software.xr.api.spatial`.
