# Native core (Zig)

Cross-compiles `libghosttyxr.so` for `aarch64-linux-android` (API 24, arm64-only).

One shared library (~28 MB) that packs:

- **libghostty** (ANSI/CSI parse + cell grid + kitty images + snapshot)
- **libssh2 + OpenSSL** (SSH transport, auth, PTY/channel I/O)
- **JNI bridge** (`Java_*` exports for Kotlin)
- **zigimg** (PNG decode for kitty images)

Android loads it via `System.loadLibrary("ghosttyxr")` from both `GhosttyBridge`
(render) and `NativeSshBridge` (transport). Same `.so`, two Kotlin entry points.

## Build

```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358"
cd ghosttyxr/zig-src
zig build jni -Dssh=true
# → compile + copy to ../android/app/src/main/jniLibs/arm64-v8a/libghosttyxr.so
```

| Step | Command | Effect |
|---|---|---|
| **jni** (primary) | `zig build jni -Dssh=true` | Build `.so` + copy into Android jniLibs |
| **native** | `zig build native -Dssh=true` | Build only (no jniLibs copy) |
| **fmt** | `zig build fmt` | Format `src`, `build.zig`, `build.zig.zon` |

- `-Dssh=true` is **required** for shipping. Without it, libssh2 is not linked
  and `NativeSshBridge` JNI exports are missing.
- **Only rebuild when editing files under `zig-src/`.** Android-only work uses
  the checked-in `.so`. No Zig/NDK needed for app builds.
- After a rebuild that should ship, **commit the binary** at
  `ghosttyxr/android/app/src/main/jniLibs/arm64-v8a/libghosttyxr.so`.

## Layout

```text
zig-src/
├─ build.zig              cross-compile + jni copy + fmt steps
├─ build.zig.zon          dependency pins (ghostty, openssl, libssh2, zigimg)
├─ .zig-version           0.16.0 (anyzig resolves this)
└─ src/
   ├─ bridge/             JNI surface (root of the .so)
   │  ├─ zignal_png.zig   build root; force-imports snapshot (+ ssh when -Dssh)
   │  ├─ ghosttyxr_snapshot.zig   GhosttyBridge JNI (libghostty render/snapshot)
   │  ├─ ghosttyxr_ssh.zig        NativeSshBridge JNI (libssh2 transport)
   │  ├─ ipc.zig
   │  ├─ ghosttyxr_jni_internal.h
   │  └─ version-script.map       exported symbols only
   ├─ vendor/libssh2/     build wrapper; upstream 1.11.1 + OpenSSL path
   ├─ vendor/mbedtls/     present but unused by the current OpenSSL path
   └─ ndk.zig             NDK triple / sysroot helpers
```

Local caches only (do not treat as ship artifacts): `.zig-cache/`, `zig-pkg/`.
Any nested leftover `.so` under `src/android/` is not the ship path — Android
loads `android/app/src/main/jniLibs/arm64-v8a/libghosttyxr.so`.

Kotlin mirrors:

- `GhosttyBridge` → `Java_com_ghosttyxr_app_terminal_GhosttyBridge_*` in `ghosttyxr_snapshot.zig`
- `NativeSshBridge` → `Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_*` in `ghosttyxr_ssh.zig`

## Toolchain

| Piece | Pin |
|---|---|
| Zig | **0.16.0** via `.zig-version` |
| NDK | **r28c** (`28.2.13676358`) |
| Target | `aarch64-linux-android` API **24**, arm64-only |
| Max page size | 16 KB (`link_z_max_page_size = 16384`) for Android 15+ |

NDK r28 still ships only a `darwin-x86_64` prebuilt; on Apple Silicon it runs
under Rosetta 2 (`build.zig` hard-codes that host tag). `zig build jni` copies
the `.so` with `UpdateSourceFiles` rather than `mkdir`/`cp`, so Windows hosts
(`windows-x86_64` prebuilt) work too.

### macOS host-link workaround

macOS 26+ SDKs drop the `arm64-macos` slice from `libSystem.tbd`, which makes
the Zig build runner fail to link with `undefined symbol: _getenv`. If you hit
that, put an `xcrun` shim on `PATH` that answers `--show-sdk-path` with an SDK
that still carries the slice (e.g. `MacOSX15.4.sdk`):

```bash
mkdir -p /tmp/zig-sdk-shim
cat > /tmp/zig-sdk-shim/xcrun <<'SH'
#!/bin/sh
for a in "$@"; do case "$a" in
  --show-sdk-path) echo /Library/Developer/CommandLineTools/SDKs/MacOSX15.4.sdk; exit 0;;
  --show-sdk-version) echo 15.4; exit 0;;
esac; done
exec /usr/bin/xcrun "$@"
SH
chmod +x /tmp/zig-sdk-shim/xcrun
export PATH=/tmp/zig-sdk-shim:$PATH
```

## Upstream pins

`build.zig.zon` carries per-dependency rationale; the shape is:

- **`ghostty-org/ghostty` @ `main`** (pinned commit) — requires Zig 0.16
- **`zigimg/zigimg` @ `master`** (pinned commit) — the 0.16 line
- **`anand-92/openssl-zig` @ `zig-0.16`** (pinned commit) — upstream
  `jossephus/openssl-zig` is still 0.15.2-only; this branch is that tree plus
  the 0.16 stdlib renames. Move back to upstream once it lands 0.16.
- libssh2 vendored at `src/vendor/libssh2` (upstream 1.11.1)

The openssl pin appears twice — top-level `build.zig.zon` and
`src/vendor/libssh2/build.zig.zon`. Both must stay identical or the graph
resolves two openssl artifacts.

## Landmines

- **NDK env is mandatory.** Build panics if neither `ANDROID_NDK_HOME` nor
  `ANDROID_NDK_ROOT` is set.
- **Single-`.so` design.** SSH JNI exports must stay in the same library Kotlin
  loads as `ghosttyxr`.
- **`version-script.map` is the ABI gate.** Only `Java_*` and `ghosttyxr_*`
  are global.
- **Not thread-safe.** Kotlin serializes all JNI calls per pane on
  `terminal-pane-<id>` in `TerminalSessionEngine`.
- **Do not casually bump Zig or NDK.** Verify on device/AVD after any bump.
- **Ship the binary.** Rebuilding without committing jniLibs leaves CI on old
  native code.
- Snapshot wire format must stay byte-compatible with Kotlin `TerminalSnapshot`.

## Commits

Conventional Commits with scopes, e.g. `build(openssl):`, `fix(ssh):`,
`feat(bridge):`, `refactor(snapshot):`. PRs target `main`.
