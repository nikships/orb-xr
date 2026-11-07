# .github/ — CI workflows for agents

Workflows live in `workflows/`. Android CI and both Mac workflows use narrow
path filters; the debug-release and Junie workflows intentionally have broader
event triggers. Website deploy filters to `website/**`. Keep each trigger
aligned with the job's publishing and review behavior.

## Workflows

| File | Runner | Triggers | What it does |
|---|---|---|---|
| `workflows/android-ci.yml` | **self-hosted `mac-mini`** | push to `main`, PR, or manual dispatch; filtered to Android/window-mirror contract paths | JDK 21 + Android SDK 37 → `assembleDebug` + `:app:testDebugUnitTest` |
| `workflows/android-debug-release.yml` | **self-hosted `mac-mini`** | every push to `main` | Builds/tests and publishes a commit-tagged debug APK plus checksum as the latest GitHub release when appropriate |
| `workflows/mac-companion-tests.yml` | **self-hosted `mac-mini`** | push to `main`, PR, or manual dispatch; filtered to companion/window-mirror paths | Generates the Xcode project and runs unsigned macOS unit tests |
| `workflows/mac-release.yml` | **self-hosted `mac-mini`** | push to `main` under `ghosttyxr/mac-companion/**`, or manual dispatch | Builds, signs, notarizes, tags, and publishes the Apple-silicon companion zip |
| `workflows/website-deploy.yml` | `ubuntu-latest` | push to `main` under `website/**`, or manual dispatch | Node 24 → lint/typecheck/build static export → Firebase Hosting `ghosttyxr-headset` (`--force`) |
| `workflows/junie-review.yml` | `ubuntu-latest` | PR opened/synchronized against `main` | Runs Junie code review |
| `workflows/junie-tag.yml` | (Junie-managed) | issue/review/comment events containing `@junie-agent` | Runs Junie for explicitly mentioned work |

## Self-hosted runner (`mac-mini`)

Private-repo self-hosted runner on the Mac Mini (`nikhilanand@mac-mini.local`):

| | |
|---|---|
| Host labels | `self-hosted`, `macOS`, `ARM64`, `mac-mini` |
| Install path | `~/actions-runner` on the Mini |
| LaunchAgent | `actions.runner.nikships-ghostty-vibe-xr.mac-mini` (user agent, Aqua session — **not** a LaunchDaemon) |
| Toolchain env | `JAVA_HOME` (Homebrew OpenJDK 21), `ANDROID_HOME`/`ANDROID_SDK_ROOT` (`~/Library/Android/sdk`), `DEVELOPER_DIR` (`/Volumes/NVMe/Xcode.app/Contents/Developer`, Xcode 26.4.x), `PATH` via LaunchAgent + runner `.env` |
| Xcode | External always-mounted NVMe: `/Volumes/NVMe/Xcode.app` (not under `/Applications`) |
| XcodeGen | Homebrew `xcodegen` (preinstalled on the Mini) |

**Routed here:**

- Heavy Android build + unit tests (`android-ci`, `android-debug-release`)
- Mac companion unit tests (`mac-companion-tests`)
- Mac companion **release** (`mac-release`) — Developer ID sign + Apple notarize + GitHub Release

**Not routed here:**

- `website-deploy` and Junie jobs are light and stay on GitHub-hosted runners

**Mac release notes (self-hosted):**

- Xcode: `/Volumes/NVMe/Xcode.app` via `DEVELOPER_DIR` (same as unit tests)
- Codesign: prefer **login keychain** Developer ID already on the Mini (unlock with empty password — the login keychain password *is* empty); fall back to P12 secrets only if missing. Do **not** re-import the P12 when the cert is already present — codesign fails with “ambiguous”. Requires the Aqua-session LaunchAgent, or the keychain is invisible to `xcodebuild`
- Notarize: `APPLE_ID` / `APPLE_TEAM_ID` / `APPLE_APP_SPECIFIC_PASSWORD` secrets; interactive local profile remains `gxr-notary`
- Identity string: `APPLE_DEVELOPER_ID_APPLICATION` → `Developer ID Application: Nikhil Anand (NW6B3R27LQ)`

**Ops notes:**

- **Service:** user **LaunchAgent**
  `~/Library/LaunchAgents/actions.runner.nikships-ghostty-vibe-xr.mac-mini.plist`
  (`RunAtLoad` + `KeepAlive` + **`SessionCreate`** + **`LimitLoadToSessionType
  = Aqua`**). It **must** run in the GUI (Aqua) session — see the LaunchDaemon
  landmine below. Reboot survival comes from **auto-login**
  (`autoLoginUser = nikhilanand`), not from a boot daemon.
- **Do NOT convert this runner to a system LaunchDaemon.** It was tried
  (`ddc2487`) to get boot-start without a login and it broke **both** companion
  workflows, because a daemon has no security session and no Aqua session:
  - `mac-companion-tests` → `Failed to establish communication with the test
    runner … com.apple.testmanagerd.control … No such process` (all Swift
    compiles fine first, so it looks like a code failure but is not).
  - `mac-release` → the signing step honestly prints `1 valid identities found`
    while `xcodebuild` fails with `No certificate matching '***' found`: the
    per-user login keychain is not in the daemon's search domain. A healthy
    Aqua-session runner reports **3 valid identities**.
  Xcode testing and codesigning both require a logged-in GUI session, so the
  daemon buys nothing here. Tradeoff: if auto-login is disabled or the Mini
  reboots to a locked login window, the runner will not come up.
- **Power:** `pmset autorestart=1`, sleep/disksleep/displaysleep off, `womp=1`
  (wake on LAN) so the Mini comes back after power loss.
- **NVMe must stay mounted** at `/Volumes/NVMe` — companion jobs fail fast if Xcode is missing.
- Only one job runs at a time on this runner (single agent). Android + Mac jobs queue.
- Status: `gh api repos/nikships/ghostty-vibe-xr/actions/runners`
- Service control (GUI domain, **no sudo**):
  `launchctl print gui/$(id -u)/actions.runner.nikships-ghostty-vibe-xr.mac-mini`,
  restart with `launchctl kickstart -k gui/$(id -u)/actions.runner.…`. Re-load
  after editing the plist: `launchctl bootout gui/$(id -u)/<label>` then
  `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/<label>.plist`.
- Keep `~/actions-runner/.path` **deleted** — some runner versions join it with
  newlines and corrupt `PATH` (`tar: command not found`). `PATH` belongs in the
  plist `EnvironmentVariables` and `.env`, colon-separated.
- Do not register this runner on public forks / do not enable “run on fork PRs” without isolation — the repo is private, which is the safe default.

## Rules

1. **Instrumented XR tests are local-only.** Neither GitHub-hosted nor the
   `mac-mini` self-hosted runner ships the Android XR system image. Never add
   `:app:connectedDebugAndroidTest` to CI.
2. **Never move the `mac-mini` runner out of the Aqua session.** No system
   LaunchDaemon, no `LimitLoadToSessionType` other than `Aqua`, and keep
   `SessionCreate`. Losing the GUI session silently breaks Xcode `testmanagerd`
   and login-keychain codesigning while making both look like source failures.
3. **No Zig/NDK in Android CI.** The prebuilt `libghosttyxr.so` is checked in.
   Only rebuild native on machines with NDK r28c; commit the binary when it
   changes.
4. **JDK 21** for all Android jobs. Never pin JDK 25.
5. **Prefer SHA-pinned actions** (supply-chain). Android CI and companion tests
   already pin actions with a version comment. Some release/Junie steps still
   use floating tags; pin them when those workflows are hardened or touched.
6. **Least privilege:** Android CI uses `contents: read`. Mac release needs
   secrets for signing/notarization; website deploy needs
   `FIREBASE_SERVICE_ACCOUNT_GHOSTTYXR_HEADSET`. Do not echo secrets in logs.
7. Keep existing path filters tight so unrelated docs/firebase edits do not
   rebuild Android CI or the Mac jobs. `android-debug-release.yml` currently
   has no path filter, so changing that behavior is a release-policy decision.
8. **Website Hosting deploys overwrite** the entire `ghosttyxr-headset` Hosting
   site root. Coordinate with any companion-download Hosting content under
   `firebase/public/` before dual-deploying Hosting from both packages.

## Related

- Android test setup: [`../ghosttyxr/android/TESTING.md`](../ghosttyxr/android/TESTING.md)
- Companion release: [`../ghosttyxr/mac-companion/AGENTS.md`](../ghosttyxr/mac-companion/AGENTS.md)
- Website: [`../website/AGENTS.md`](../website/AGENTS.md)
- Root: [`../AGENTS.md`](../AGENTS.md)
