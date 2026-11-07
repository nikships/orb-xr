# Orb XR — Play Store listing copy (en-US)

Final copy for every text field in Play Console → **Grow users → Store presence →
Main store listing**. Character counts are exact (`len()` over the raw string,
newlines included for the full description). Limits per Google's
[metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842)
and the Play Console listing editor.

| Field | Limit | Used | Status |
|---|---|---|---|
| App name | 30 | **6** | ✅ |
| Short description | 80 | **78** | ✅ |
| Full description | 4000 | **3170** | ✅ |
| Release notes | 500 | **231** | ✅ |
| Alt text (per graphic) | 140 | **93–137** | ✅ |

---

## App name (6/30)

```
Orb XR
```

Do not append taglines or keywords to the title — Google's metadata policy
prohibits it, and `Orb XR` is the official, locked store name
(see root `AGENTS.md`).

## Short description (78/80)

```
Orchestrate your CLI coding agents in XR — floating SSH terminals to your Mac.
```

Leads with the differentiated value (multi-agent orchestration), names the form
factor (XR), and states the architecture truthfully (SSH to your Mac) in one
line. The em dash is a single character.

## Full description (3170/4000)

```
Orb XR turns your Android XR headset into a spatial workspace for orchestrating a fleet of CLI coding agents — Claude Code, Codex, Gemini, Aider, long builds, plain shells — each in its own floating terminal pane, all running on your Mac over SSH.

WHY A HEADSET?

Agentic coding is bottlenecked by attention, not typing. On a flat monitor, parallel agents hide behind tabs and shrinking splits; at any moment you see one and are blind to the rest. In XR the canvas is the space around you: up to six terminal panes float at a comfortable reading distance, each one a persistent place your eyes can flick to. The agent grinding through tests is over there. The one waiting on your approval is in front of you. You stop alt-tabbing and start looking around a room full of work.

HOW IT WORKS

1. Run the Orb XR menu-bar app on your Mac.
2. Put on the headset and scan the QR code it shows.
3. Terminal panes connect to your Mac over SSH on your local network.

Your Mac stays the source of truth. Shells, agents, toolchains, and files all live and run on the Mac — the headset renders terminals and routes input. Take the headset off and your agents keep working.

WHAT YOU GET

• Real terminals, rendered natively. Every cell draws on the headset: true color, real ANSI, the Kitty keyboard protocol. No web view, no screen mirroring — each pane is a first-class terminal with its own PTY.
• Up to 6 panes in a curved spatial arc. Drag panes where you want them; they turn to face you. Focused panes are outlined so peripheral vision keeps you oriented.
• Fast, secure pairing. QR pairing to macOS's built-in SSH server, with host keys pinned on first use and credentials stored in Android encrypted storage.
• Input that keeps up. Bluetooth keyboards, XR controllers, an on-screen keyboard, and push-to-talk voice dictation that lands in the pane you are looking at. Alt+1–6 jumps between panes. A palm-up gesture summons a voice pet that opens the mic; speak a command and flip your palm to send it.
• Focus mode. Swap passthrough for an immersive environment — black void, starry night, and more — and make the work the world.
• Your terminal, your look. 463 color themes built in, JetBrains Mono chrome, adjustable text size and pane opacity.
• Resilient sessions. A foreground service keeps your SSH sessions active while you work; dropped connections reconnect automatically with exponential backoff.

WORKS WITH YOUR EXISTING SETUP

Nothing about your dev environment changes. Your dotfiles, your git, your language servers, and your agents are exactly where they already live — Orb XR is a window onto them. If it runs in a terminal on your Mac, it runs here.

REQUIREMENTS

• An Android XR headset (immersive, spatial-capable device).
• A Mac on the same network with Remote Login (SSH) enabled.
• Your coding agents and toolchains installed on the Mac. Agents run on the Mac, not on the headset.
• Optional: voice dictation uses your own Deepgram API key; voice commands use Gemini.

Orb XR is built for developers who supervise agents for a living. The terminal is the primitive — the product is steering more work at once than a screen ever let you.
```

### Truthfulness audit (claim → source in this repo)

| Claim | Verified against |
|---|---|
| Up to 6 panes | `PaneNavShortcuts.MAX_PANES = 6` |
| Native rendering, true color, Kitty protocol, per-pane PTY | `VISION.md`, `docs/architecture.md`, `zig-src` (libghostty + libssh2) |
| Curved arc, draggable panes, face-the-user | `terminal/spatial/SpatialPaneGrid.kt` |
| QR pairing, host-key pinning (TOFU), encrypted storage | `terminal/ssh/`, `docs/architecture.md`, `EncryptedSharedPreferences` stores |
| Keyboard / controller / on-screen / voice input, Alt+1–6, palm-up voice pet | `terminal/input/`, `terminal/voice/`, `terminal/hands/` |
| Focus mode environments (black void, starry night, …) | `ImmersiveEnvironment.kt` (`TERMINAL_ENVIRONMENTS`) |
| 463 themes, JetBrains Mono, text size + opacity | `assets/themes/` (463 files), `res/font/`, `ControlPanel.kt` sliders |
| Foreground service + auto-reconnect with backoff | `SessionForegroundService`, `ReconnectPolicy` (manifest + architecture doc) |
| Agents run on the Mac, not the headset | `VISION.md` §"Thin client, heavy host" — stated explicitly in REQUIREMENTS |
| Deepgram key for dictation, Gemini for voice commands | `DeepgramKeyStore.kt`, `GeminiCommandSession.kt` (Firebase AI Logic) |

## Release notes — first release (231/500)

```
First release: up to 6 native SSH terminal panes in 3D space, QR pairing with the Orb XR Mac app, 463 color themes, focus-mode environments, keyboard/controller/on-screen/voice input, and automatic session reconnect.
```

## Graphic alt text (each ≤140)

Alt text for every uploaded graphic, per Google's asset guidance (enter in the
alt-text field of each graphic's upload dialog in Play Console). Factual
descriptions of what each image shows; counts are exact `len()`.

| Asset | Alt text | Chars |
|---|---|---|
| `assets/icon/icon_512.png` | App icon: luminous violet glass orb with internal stars on a pure black field. | 80 |
| `assets/feature/feature_graphic_1024x500.png` | Orb XR wordmark and tagline 'Orchestrate your coding agents in XR' beside two floating terminal panes on a dark starfield. | 129 |
| `assets/screenshots/xr/01_fleet_cockpit.png` | Six floating terminal panes running coding agents, builds and shells, arced around the viewer. Caption: 'Six agents. One field of view.' | 136 |
| `assets/screenshots/xr/02_native_terminal.png` | One large native terminal pane with true-color output. Caption: 'A real terminal, rendered natively.' | 101 |
| `assets/screenshots/xr/03_pair_with_mac.png` | Pairing panel showing a QR code that connects the headset to a Mac over SSH. Caption: 'Pair with your Mac in seconds.' | 118 |
| `assets/screenshots/xr/04_focus_mode.png` | Two terminal panes in a starry void above environment chips with 'Starry night' selected. Caption: 'Focus mode: make the work the world.' | 137 |
| `assets/screenshots/xr/05_themes.png` | Theme picker listing color themes with swatches beside a terminal pane. Caption: '463 themes, built in.' | 98 |
| `assets/screenshots/xr/06_voice_and_controls.png` | Terminal with green voice-listening border beside Voice, Layout, and Focus settings. Caption: 'Steer by voice, keys, or controller.' | 132 |

## Developer name

```
Orb XR
```

Product brand. No emoji, no decorations (metadata policy).
