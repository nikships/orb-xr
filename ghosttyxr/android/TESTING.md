# Orb XR — Android Test Setup

Canonical test infrastructure for the Orb XR Android headset
app. The golden path uses the **official Google Android XR emulator system
image**, not a generic phone or tablet AVD. See also [`AGENTS.md`](AGENTS.md).

## Test layers

| Layer | Sourceset | Command | Notes |
|---|---|---|---|
| JVM unit | `app/src/test/kotlin/**` | `./gradlew :app:testDebugUnitTest` | Broad pure-logic coverage: cockpit lifecycle/snapshots, reconnect, tmux/CWD, key maps, pairing, billing/onboarding, voice/gesture controllers, handoff/keyboard/window-mirror codecs, pane layout. `unitTests.isReturnDefaultValues = true` covers `KeyEvent` constants. |
| Instrumented JNI | `androidTest/.../BridgeInstrumentedTest.kt` | `./gradlew :app:connectedDebugAndroidTest` | Loads aarch64 `libghosttyxr.so` on device/AVD |
| Compose XR semantics | `androidTest/.../SpatialPanelSemanticsTest.kt` | same | Spatial Subspace / panel semantics |
| Flat E2E | `androidTest/.../FlatAppE2ETest.kt` | same | Home Space / flat-mode smoke |
| SSH / session | `androidTest/...` pairing store, provisioner, engine integration | same | Encrypted pairing + session engine on device |

Prefer injection + fakes for unit tests (no emulator). Put Android-touching
construction in `wiring/`. Public Compose `testTag`s live only in
`ui/UiTestTags.kt`.

Terminal grid pixels are painted by libghostty / `nativeCanvas` — those frames
do not materialize under Robolectric; exercise paint paths on device/AVD.

## Official Android XR AVD setup

### 1. Install the XR system image

```text
system-images;android-34;google-xr;arm64-v8a
```

```bash
ls ~/Library/Android/sdk/system-images/android-34/google-xr/arm64-v8a/
# expect: system.img, build.prop, package.xml, ramdisk.img, ...
```

### 2. Create the `GhosttyXR_Headset` AVD

**Preferred — Android Studio Device Manager:** Form Factor XR → XR Headset
profile → `google-xr` arm64 image → name the AVD **`GhosttyXR_Headset`**.

**Hand-rolled config** (if needed). Key entries:

```ini
abi.type        = arm64-v8a
hw.cpu.arch     = arm64
hw.lcd.width    = 2064
hw.lcd.height   = 2208
hw.lcd.density  = 320
hw.ramSize      = 8192
image.sysdir.1  = system-images/android-34/google-xr/arm64-v8a/
tag.id          = android-xr
tag.display     = Android XR
```

### 3. System requirements

- macOS 13.3+ on Apple Silicon, 16 GB+ RAM
- Free disk ≥ 8 GB for the emulator userdata partition on first boot

### 4. Boot

```bash
$ANDROID_HOME/emulator/emulator @GhosttyXR_Headset
```

### 5. JDK pin

Gradle must run on **JDK 21** (JDK 25 crashes AGP Kotlin):

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

### 6. Confirm adb

```bash
adb devices
# expect a single XR emulator (e.g. emulator-5554 device)
```

## Running tests

```bash
cd ghosttyxr/android
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

# Unit (host JVM)
./gradlew :app:testDebugUnitTest

# Instrumented — only GhosttyXR_Headset connected
./gradlew :app:connectedDebugAndroidTest
```

**One emulator at a time.** Gradle fans out to every connected device; kill
other AVDs first so XR assertions are not wasted on non-XR targets.

## CI

- `.github/workflows/android-ci.yml` runs `assembleDebug` + unit tests only.
- XR instrumented tests are **local-only** (no XR system image on GitHub runners).
- Do not add `:app:connectedDebugAndroidTest` to CI.

## Gaps (current)

- No screenshot harness (Roborazzi / Paparazzi / Compose Preview Screenshot)
  wired yet.
- No DI framework (Hilt/Koin); fakes are constructor/lambda injection.
- `TerminalCanvas` has limited accessibility semantics for the cell grid.
