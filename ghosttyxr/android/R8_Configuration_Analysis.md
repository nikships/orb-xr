# R8 Configuration Analysis

**Project:** `ghosttyxr/android` (`:app` only)  
**Package:** `com.ghosttyxr.app`  
**AGP:** 9.3.0  
**Date:** 2026-07-29 (updated after enabling release minify)

---

## Configuration status

### App module (`app/build.gradle.kts`)

| Setting | Current value |
|---|---|
| `isMinifyEnabled` (release) | `true` |
| `isMinifyEnabled` (debug) | `false` |
| `isShrinkResources` (release) | `true` |
| `proguardFiles` | `proguard-android-optimize.txt` + `proguard-rules.pro` |
| App keep-rules file | `app/proguard-rules.pro` |

### `gradle.properties`

| Flag | Status |
|---|---|
| `android.enableR8.fullMode=false` | **Absent** — full mode stays on (AGP 9 default) |
| `android.r8.optimizedResourceShrinking` | Not required on AGP 9.3.0 |

### AGP version

AGP is **9.3.0**. No upgrade required.

---

## Keep rules in `app/proguard-rules.pro`

| Rule target | Action taken | Why |
|---|---|---|
| `GhosttyBridge` native methods | **Keep** (class + native members) | Zig JNI mangled names `Java_com_ghosttyxr_app_terminal_GhosttyBridge_*` |
| `NativeSshBridge` native methods | **Keep** (class + native members) | Zig JNI mangled names `Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_*` |
| `org.webrtc.**` | **Keep** package members | WebRTC AAR ships **no** consumer ProGuard rules; window mirror depends on it |
| Source/line attributes | **Keep** | Readable Play / crash stack traces |
| AndroidX / Firebase / Billing / ML Kit / Kotlin | **Not re-kept** | Libraries ship their own consumer rules |

No redundant Google/AndroidX/Kotlin/Room/Gson/Retrofit blanket rules were added.

---

## Notes

- Debug builds remain unminified for fast local iteration.
- `Class.forName` for App Check debug provider is debug-only (`BuildConfig.DEBUG` + `debugImplementation`); release never loads it.
- Assets under `assets/` (themes, ASCII animation) are not subject to resource shrinking the same way as unused `res/`; code uses `R.raw.*` / `R.drawable.*` statically for coach narration.
