# Keywords & positioning guidance — Orb XR

Google Play has no keyword field: ranking signals come from the title, short
description, and full description (plus engagement). This doc explains the
term choices already baked into `en-US.md` and how to evolve them.

## Positioning statement

**For developers who supervise multiple CLI coding agents, Orb XR is an
Android XR workspace that gives them the spatial bandwidth a monitor can't —
floating native SSH terminals to their Mac, so they can watch and steer six
workstreams at once.**

Core value: **multi-agent orchestration bandwidth**. The terminal is the
primitive, not the product. Never position as "a terminal app for XR" — the
category is agent orchestration; the wedge is XR's field of view.

## Primary terms (already in the copy, in priority order)

| Term | Where | Why |
|---|---|---|
| coding agents / CLI coding agents | title-adjacent (short desc), full desc | The category-defining query for the target user |
| Claude Code, Codex, Gemini, Aider | full desc, first sentence | High-intent brand searches; truthful (they run over SSH) |
| SSH terminal / SSH client | short + full desc | The established query this app genuinely satisfies |
| Android XR / headset | short + full desc | Form-factor qualifier |
| Mac / macOS | short + full desc | Disambiguates the thin-client architecture |
| terminal emulator, Ghostty | full desc ("Ghostty terminal core", themes) | Long-tail; Ghostty carries developer credibility |
| Kitty keyboard protocol, true color, PTY | full desc | Terminal-nerd credibility terms; low volume, high conversion |

## Terms deliberately avoided

- **"#1", "best", "fastest"** — metadata policy violations.
- **"AI pair programmer" / "AI assistant on your headset"** — implies agents
  run locally; they run on the Mac. Keep every phrasing host-side.
- **"remote desktop", "screen mirroring", "VNC"** — factually wrong (native
  rendering) and attracts the wrong intent.
- **Keyword lists / repetition** — metadata policy prohibits stuffing; each
  term appears where it reads naturally.

## Competitive frame (for ads/landing copy, not the listing)

- vs. **tmux/terminal multiplexers**: panes stop being useful past ~4 splits;
  XR keeps six readable and glanceable.
- vs. **XR remote-desktop/virtual-monitor apps**: those stream pixels of one
  flat screen; Orb XR renders real terminals natively, one PTY each.
- vs. **web dashboards for agents**: the terminal is the agent's native UI —
  spinners, TUIs, colors survive intact.

## Category & tags (Play Console)

- **Category:** Tools (developer tooling lives here; "Productivity" is the
  fallback if Tools underperforms).
- **Tags:** pick the closest available: Tools, Productivity. Re-evaluate after
  launch against actual acquisition queries in Play Console's search-terms
  report.

## Localization

Ship en-US only at first. If localizing later, re-verify counted lengths per
language (character limits are enforced per locale) and keep agent brand names
untranslated.
