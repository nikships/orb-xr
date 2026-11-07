# Android headset app

Thin client cockpit for **Orb XR**. Kotlin + Jetpack Compose for XR. Renders floating terminal panes in 3D, routes input, and SSHes into the user's Mac. The Mac is the source of truth (shells, agents, toolchains, tmux). The headset owns display, input routing, pairing, and orchestration UI only.

See [`../../docs/architecture.md`](../../docs/architecture.md) for the data-flow map and [`TESTING.md`](TESTING.md) for XR AVD setup. Product intent lives in [`../../VISION.md`](../../VISION.md).

## Build & test

From `ghosttyxr/android/`:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"  # JDK 21 required; JDK 25 crashes AGP Kotlin
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug                             # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                    # pure-JVM unit tests
./gradlew :app:connectedDebugAndroidTest           # instrumented tests on the XR AVD only
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Publish a debug build to GitHub Releases (debug-keystore signed, sideload-only):

```bash
./release-debug.sh                # build app-debug.apk + create/upload GitHub release
SKIP_UPLOAD=1 ./release-debug.sh  # build + checksum only, no upload
RELEASE_TAG=android-debug-foo ./release-debug.sh   # override the release tag
```

Prebuilt `libghosttyxr.so` is checked into `app/src/main/jniLibs/arm64-v8a/`. App-only builds need neither Zig nor the NDK. Rebuild the native lib only when editing `ghosttyxr/zig-src/` (see its AGENTS.md).

## Gradle notes

- **Single module:** `app` only. Namespace / `applicationId` = `com.ghosttyxr.app` (Play Store locked; do not rename).
- **AGP 9:** Kotlin is compiled by AGP itself. Apply only `com.android.application` + Compose compiler plugin. No `kotlin-android` plugin, no `kotlinOptions{}`; set JVM target via top-level `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.
- **SDK:** `compileSdk`/`targetSdk = 37`, `minSdk = 24`, arm64-only (`abiFilters += "arm64-v8a"`). x86 emulators will not run the APK.
- **Bytecode vs toolchain:** JVM 17 bytecode; Gradle must run on **JDK 21**.
- **BYOK voice:** Users enter their own Gemini key in Settings → Voice. The key
  is stored in encrypted Android preferences; the Live WebSocket connects
  directly to Gemini. Never bundle keys or add Firebase or subscription gates.
- **Release signing:** `release.storeFile` / `storePassword` / `keyAlias` / `keyPassword` from `local.properties`. Missing values fall back to the debug keystore so `assembleRelease` still produces a sideloadable APK.
- **BuildConfig** is explicitly enabled (AGP 9 does not generate it by default).

## Package map (`com.ghosttyxr.app`)

```
MainActivity                 Android coordinator: lifecycle, permissions, key dispatch, controller graph
GhosttyXRApplication         Firebase init + App Check provider install (Play Integrity / debug)
billing/                     Entitlement, Play Billing, purchase verification, onboarding-access client
ImmersiveEnvironment         Focus-mode skybox selection + apply
intro/                       Two-step first-run setup + five-step live cockpit coach
qr/                          Pairing payload, QR scanner, pending/debug pairing
ui/                          All cockpit Compose UI
wiring/                      Factories for Android-touching controllers (voice, pet surface)
util/                        GxrLog (debug-gated logging)
terminal/                    Engine + VerifiedHostAccess + input + spatial layers
```

### `ui/`

| Path | Purpose |
|---|---|
| `GhosttyXRApp` | Root composable: state hoisting, top-level layout, mode switches |
| `UiTestTags` | Public `testTag` contract + permission constants (instrumented tests match these only) |
| `chrome/` | Design-system primitives (`GxrPanel`, `GxrButton`, `GxrChip`, `GxrHeader`, `GxrSection`, `GxrSlider`, `GxrSwitch`, `GxrText`, `GxrTextField`, `GlowIndication`). Use these instead of Material3 surfaces. |
| `pairing/` | QR scanner + startup/reconnect status overlay |
| `pane/` | `PaneSurface` (one terminal: status bar + `TerminalCanvas`); `WindowMirrorSurface` (desktop share video) |
| `panel/` | Control panel, theme picker, voice settings |
| `pointer/` | Symbiosis XR cursor: `CockpitSpace`, `XrPointerRouter`, `XrCursorOverlay`, `PointerSequenceTracker` |
| `resume/` | Saved-session resume picker (`SessionResumePanel`), including host-side end/terminate |

### `terminal/` (engine core)

| Path | Purpose |
|---|---|
| `GhosttyBridge` | JNI render/snapshot surface in the shared `libghosttyxr.so`; SSH uses `NativeSshBridge` from the same library |
| `TerminalCanvas` | Compose canvas that paints cell-grid snapshots from the engine |
| `TerminalSnapshot` / `DemoSnapshot` | Snapshot wire format + offline demo grid |
| `session/` | `CockpitLifecycle`, pane engines, tmux bootstrap, reconnect, snapshots/resume, CWD |
| `ssh/` | VerifiedHostAccess (handshake/verify/auth/cleanup, `withShell` helper), `ChannelGenerationGuard` for reconnect-race safety, QR/persisted fingerprint pin check, RSA keygen, paired-Mac store |
| `input/` | Key/controller mapping, on-screen keyboard, scrollback, shortcuts, voice hotkey |
| `voice/` | Live voice commands + dictation (`gemini-3.8-live` duplex) + pet speech output |
| `hands/` | Palm-up 3D voice orb (not the old palm menu chrome) |
| `spatial/` | Adaptive pane grid (spatial Subspace + 2D fallback) |
| `theme/` | Ghostty theme registry + `GhosttyTokens` chrome design tokens |
| `hostkeyboard/` | Mac keyboard relay over a verified direct-tcpip tunnel |
| `windowmirror/` | Desktop/window share (direct-TCP video; control over verified direct-tcpip tunnel) |
| `handoff/` | "Send terminals to Mac": attach handoff to local Mac terminals over a verified direct-tcpip tunnel; never kills host sessions |
| `settings/` | `GhosttySettingsStore` (prefs-backed `StateFlow` settings) |

### `terminal/session/` (high traffic)

| Type | Purpose |
|---|---|
| `CockpitLifecycle` | Public capability seam for cockpit state, pane lifecycle, focus, fresh/resume, termination, capture, handoff |
| `DefaultCockpitLifecycle` | Production owner of 1..6 engines, stable `PaneId`s, shared `VerifiedHostAccess`, snapshots, agent/CWD probes |
| `CockpitState` / `CockpitPane` | Top-level NeedsPairing/ResumePicker/Active state and narrow UI-facing per-pane capability |
| `TerminalSessionEngine` | Per-pane engine: libghostty + established shell on its own dispatcher |
| `HostSessionBootstrap` | Post-connect shell: `tmux -L gxr` server + named session + attach. Delicate lifecycle. |
| `HostSessionTerminator` | Ends saved host sessions from the resume picker |
| `SessionSnapshot` / `SessionSnapshotStore` | Persist/restore cockpit layout and host session names |
| `SnapshotPersistence` | Injectable persistence port; production adapter delegates to `SessionSnapshotStore` |
| `AgentLaunch` / `AgentResume` | Spawn or reattach CLI agents inside a pane |
| `AgentSessionProbe` | Host-side probe of agent/tmux session state |
| `WorkingDirectory` / `PaneCwdProbe` | Safe path normalization/quoting + authoritative host `tmux pane_current_path` probe; OSC 7 fallback |
| `NetworkMonitor` | Connectivity callbacks that interrupt reconnect backoff |
| `ReconnectPolicy` | Jittered exponential backoff; interruptible on network restore |
| `SessionState` | Connection state machine labels for UI |

### `terminal/voice/`

| Path | Purpose |
|---|---|
| `VoiceMessages` | Shared Pro-gate / no-speech strings + transcript normalization |
| `LiveCommandSession` / `LiveCommandEngine` | Firebase-/audio-touching Live duplex session + pure dispatch core (tools, speech, turns) |
| `LiveAudioStreamer` | Continuous 16 kHz mic chunk streamer for the Live session |
| `LiveDictationController` | Live input-transcriptions → focused pane (replaces batch STT dictation) |
| `command/VoiceCommandController` | Pure cockpit dispatcher (unchanged logic, now driven by the Live engine) |
| `command/CommandFunctions` / `VoiceCommandState` | Live tool definitions + UI state |
| `command/PlayerLiveSpeechOutput` | Model PCM → pet `PetSpeechPlayer` (spatialized onto the pet entity) |
| `tts/PetSpeechPlayer` / `PcmChunkAssembler` | Incremental PCM frame assembly, short playback priming, and streaming `AudioTrack` output |
| `tts/SilentSpeechSynthesizer` | Toast-only fallback for transcript-without-audio turns (no cloud TTS left) |
| `tts/PrebakedPetSpeechSynthesizer` | Bundled clips for fixed notices (no-speech); delegates to the silent fallback |
| `tts/PetSpeechCoordinator` | Speaks voice-command results + selected pet notices (single-flight, latest-wins) |

### `terminal/hands/`

Palm-up gesture machine + pose math (`HandMenuController`, `HandMenuGestureMachine`, `HandGestures`, `HandMenuPlacement`) and prayer-lock recognition (`PrayerGestures`, `PrayerToggleMachine`). This is the holographic voice orb, not a floating palm button strip.

The orb itself is a stereo orb. Its decision maths lives here as pure, host-JVM-testable modules — `AgentOrb` (palette/archetype/spin), `OrbSphereMesh`, `OrbEyeProjection` (per-eye off-axis frustum), `OrbBodyDynamics` (spring lag, lean, squash/stretch, energy), `OrbPaletteRamp` (baked jewel-tone colour wheel) — while GL and SceneCore stay in `wiring/AgentOrbRenderer` + `wiring/OrbEyeTracker` + `wiring/VoicePetSurfaceFactory`.

The orb is an **opaque black body with sparse colour burning in and under its surface**, drawn in a single pass with nothing outside its silhouette (no halo shell, no orbiting motes). Coverage thresholds in `orb_fragment.glsl` are high on purpose: fill more of the surface and it stops being a black object with light trapped in it and becomes a multi-coloured lava lamp.

**The pet must be a `SurfaceEntity` in `SIDE_BY_SIDE` stereo, not a `PanelEntity`.** A panel shows both eyes the same image; with zero disparity the orb reads as a flat picture of a sphere no matter how it is shaded. `AgentOrbRenderer` renders each half from that eye's real `RenderViewpoint` pose with an off-axis frustum whose image plane is the quad. Do not "simplify" this back to a panel.

### `terminal/windowmirror/`

Desktop/window share. The SSH tunnel authenticates and carries control, input, and metadata; video arrives on a separate direct LAN TCP socket (port 45876) because the tunnel does one synchronous JNI round-trip per read and cannot carry video bitrates. `WindowMirrorRelayController` owns control/session state, `WindowMirrorMediaClient` reads slices and reassembles frames with no jitter buffer, `WindowVideoDecoder` feeds MediaCodec, and `ui/pane/WindowMirrorSurface` renders through a plain `SurfaceView`.

Two fixes in `WindowVideoDecoder` are load-bearing and easy to regress: it must drain output buffers *before* the keyframe gate (skipping P-frames without releasing outputs livelocks the codec at ~1fps), and it must `flush()` when a keyframe arrives with no input buffer available rather than dropping it.

Wire constants, frame IDs, and payload defaults are owned by the shared contract
at [`../protocol/window-mirror/`](../protocol/window-mirror/) (`contract-v1.json`
+ frozen vectors). `WindowMirrorProtocol` / `WindowMirrorFrameDecoder` are the
Kotlin adapter; JVM tests load the same fixtures as the Mac companion suite.

### `terminal/hostkeyboard/`

Mac physical keyboard relay (companion loopback **45873**): companion listener → authenticated SSH direct-tcpip → `HostKeyboardRelayController` → mapped keys into the focused pane / app shortcuts.

### `terminal/handoff/`

Voice-pet "send terminals to Mac": `TerminalHandoffController` collects live host session names, asks the companion's loopback listener (`127.0.0.1:45875` via authenticated direct-tcpip) to open one local terminal window per session (`tmux -L gxr attach-session`), and only on `ok:true` stops capture, drops the cockpit's resume snapshot, and resets the grid to one disconnected pane under a fresh cockpit id. Attach handoff only — never calls `HostSessionTerminator`/`kill-session`.

## Conventions

- **Kotlin style:** 4-space indent, package `com.ghosttyxr.app.*`, PascalCase types, camelCase members. Compose UI in `*Screen` / `*Canvas` / `*Grid` / `*Panel` files.
- **Injection for testability.** Controllers take Android/Firebase deps as lambdas or small interfaces so logic unit-tests with fakes (no emulator). Put Android-touching construction in `wiring/`.
- **Settings store pattern.** One pattern: `object` with `MutableStateFlow` fields, `init(context)`, prefs-backed setters, read-only `StateFlow`s. Extend `GhosttySettingsStore` for user prefs. Secrets go in EncryptedSharedPreferences (`PairedMacStore`).
- **Design tokens.** Chrome colours/typography/geometry live in `GhosttyTokens` (`terminal/theme/`). Use `ui/chrome/*` components. Do not scatter `Color(0x…)` literals. Terminal **glyphs** are painted by libghostty, not Compose text.
- **Logging.** Use `GxrLog`. `GxrLog.d(tag) { … }` is debug-only and compiles out of release; `w`/`e`/`i` pass through.
- **Spatial + 2D fallback.** Spatial composables gate on `LocalSpatialCapabilities` / `spatialEnabledOverride` and provide a flat fallback so the app works in HomeSpace, on the XR AVD, and in Compose tests.
- **Test contract.** Public `testTag`s live only in `ui/UiTestTags.kt`. Do not inline tag strings in composables or tests.

## Hard constraints

- `compileSdk` / `targetSdk = 37`, `minSdk = 24`, arm64-only.
- JVM 17 bytecode; Gradle on **JDK 21** (`JAVA_HOME` path above).
- Play package name permanently `com.ghosttyxr.app`.
- One official XR AVD at a time for instrumented tests (`GhosttyXR_Headset`).

## Landmines / do-nots

1. **Do not call JNI from multiple threads per pane.** Each `TerminalSessionEngine` owns a single-thread dispatcher (`terminal-pane-<id>`) that serializes libghostty and shell JNI. libssh2 is not internally thread-safe; `VerifiedHostAccess` manages its setup, but the engine owns the established shell's lifetime.
2. **Do not rewrite `HostSessionBootstrap` lightly.** Host sessions use `tmux -L gxr` with `-f /dev/null`. Server options and the first pane must be created in a **single** tmux client invocation (`start-server` with exit-empty dies before follow-up `set-option`). Small edits have dropped sessions and broken resume.
3. **Do not simplify CWD routing without headset validation.** New-pane inheritance now queries host `tmux -L gxr ... '#{pane_current_path}'` through `PaneCwdProbe`, waits for the interactive shell to become ready, and passes the concrete safely quoted path to `new-session -c`; OSC 7 is fallback only. Do not inline discovery into the destination bootstrap or inject a second `cd` into a tmux-backed pane. Read [`../../docs/cwd-routing-handoff.md`](../../docs/cwd-routing-handoff.md).
4. **Do not inline test tags.** Use `UiTestTags` only.
5. **Do not put API keys in source or the APK — at all.** There is no BYO-key path and no
   server voice endpoint: the Live session authenticates with Firebase ID + App Check through
   Firebase AI Logic. Voice AI is Pro-gated: keep `isProProvider` gates on client paths and
   never bypass the Firestore entitlement check (see `../../docs/monetization.md`).
6. **libghostty paints glyphs; chrome uses `GhosttyTokens`.** Do not restyle terminal cells in Compose or invent a second colour system for panels.
7. **Empty pane slots must not be interactive.** Unoccupied grid positions should not steal focus, accept pointer, or show active chrome.

## Tests

| Layer | Sourceset | Command | What belongs here |
|---|---|---|---|
| JVM unit | `app/src/test/kotlin` | `./gradlew :app:testDebugUnitTest` | Cockpit lifecycle/snapshots, reconnect, tmux/CWD logic, key mapping, onboarding/billing, voice/gesture controllers, pane layout math, pairing, handoff/keyboard/window-mirror codecs. Names end in `*Test.kt`. |
| Instrumented | `app/src/androidTest/kotlin` | `./gradlew :app:connectedDebugAndroidTest` | JNI bridge smoke (`BridgeInstrumentedTest`), engine integration, encrypted pairing/provisioning, flat-mode E2E (`FlatAppE2ETest`), Compose spatial semantics. Requires device/emulator (aarch64 `.so`). |

**XR AVD only** for instrumented runs. Confirm `adb devices` shows only `GhosttyXR_Headset` before starting (kill other emulators first). See `TESTING.md`. One emulator at a time; Gradle fans out to every connected device.

## Assets of note

Under `app/src/main/assets/`:

| Path | Contents |
|---|---|
| `themes/` | 463 Ghostty theme definitions (catalog for theme picker / bridge colours) |
| `environments/` | Unlit cubemap-geometry `.glb` environments for immersive focus mode |
| `fonts/` | `JetBrainsMonoNerdFontMono-Regular.ttf` (chrome typeface; terminal face is native) |

Native library: `app/src/main/jniLibs/arm64-v8a/libghosttyxr.so`.

## Quick architecture reminder

`DefaultCockpitLifecycle` owns the live engines and exposes them only as `CockpitPane`s. Each pane = one `TerminalSessionEngine`: verified shell read loop → `GhosttyBridge.nativeWriteRemote` → `nativeSnapshot` → `StateFlow<TerminalSnapshot>` → `TerminalCanvas`. Tick ~60fps with idle backoff. `VerifiedHostAccess` owns the handshake/verify/auth ladder. Half-open links die via keepalive; drops use `ReconnectPolicy` (interruptible by `NetworkMonitor`). Host `tmux -L gxr` sessions, not an Android foreground service, preserve agent work across headset disconnects.
