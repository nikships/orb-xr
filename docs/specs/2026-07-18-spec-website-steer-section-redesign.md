# Spec: Terminal-native redesign of the "Steer, don't alt-tab" website section

## Goal

Replace the chat-bubble mockup currently powering the **"Steer, don't
alt-tab"** feature section on the marketing site
(`website/src/components/feature-visuals.tsx` → `ConversationDemo`) with a
terminal-native visual: three real agent CLIs (Claude Code, Codex, Gemini
CLI) rendered **simultaneously** as separate panes, one of them flagged
"needs input," matching the actual product (spatial terminal panes, no chat
UI anywhere in Orb XR).

## Product outcome

- The section's visual finally matches the product it's selling: real
  terminal panes, not an invented conversation thread with `role: user /
  agent / tool` bubbles.
- The copy's "peripheral vision" framing ("Look at the one that needs you,
  type a one-line steer, look away again") is depicted literally: three
  panes visible at once, exactly one showing a "needs input" cue.
- Each pane is recreated from the real CLI's own look (colors, chrome,
  layout conventions) instead of a generic terminal skin, so the section
  doubles as implicit proof that Orb XR runs your actual tools
  unmodified.

## Current implementation (the problem)

`FeatureRail.tsx` renders `ConversationDemo` (from `feature-visuals.tsx`) for
feature index `1` — id `"steering"` in `website/src/data/voice.ts`.
`ConversationDemo`:

- Reads `conversationScenarios` (5 scenarios: Refactor / Tests / Build /
  Debug / Migration) from `website/src/data/voice.ts`.
- Renders a single centered transcript with `role: "agent" | "user" |
  "tool"` messages inside `GlassBubble` chat bubbles, one message revealed
  at a time.

This is a chat UI. Orb XR has no chat surface anywhere — every pane
is a real libghostty terminal over SSH rendering ANSI output, not a message
thread. See `docs/specs/2026-07-18-spec-website-steer-section-redesign/assets/current-steer-section.png`
for a screenshot of the current state.

## Design reference

A working HTML/CSS/JS mockup and the reference screenshots used to build it
are committed alongside this spec:

- `docs/specs/2026-07-18-spec-website-steer-section-redesign/mockup.html` —
  open directly in a browser. Three simultaneous panes (Claude Code / Codex
  / Gemini CLI), a shared scenario tab bar driving all three at once, and a
  short caption per scenario. This is the **visual source of truth** for the
  new component; adapt it into the site's existing component conventions
  (Tailwind, `cn()`, the `cardShell`/`raisedShell` style used elsewhere) —
  it does not need to be a literal copy-paste of the mockup's hand-written
  CSS.
- `assets/mockup-preview.png` — static screenshot of the mockup's default
  ("Refactor") state, for quick reference without opening the HTML file.
- `assets/claude-demo-frame.png`, `assets/codex-splash.png`,
  `assets/gemini-cli.png` — official reference screenshots pulled from
  `anthropics/claude-code`, `openai/codex`, and `google-gemini/gemini-cli`
  respectively. Used to match each CLI's real chrome (macOS traffic-light
  titlebar, Claude's dark-teal session view + pixel mascot, Codex's boxed
  status header + `Updated Plan` checklist, Gemini's gradient wordmark +
  tool-call cards). These are reference-only, not production assets to
  ship.
- `assets/current-steer-section.png` — screenshot of the current chat-bubble
  implementation, for before/after comparison.

## Target implementation

### Component changes

- Add a new component in `website/src/components/feature-visuals.tsx`
  (e.g. `SteeringDemo`) that renders three panes side by side:
  1. **Claude Code** — dark-teal body, pixel mascot, `Claude Code` title +
     model/enterprise subtitle, path, a highlighted prompt row, and a
     status row (idle/live) with a colored dot.
  2. **Codex** — black body, bordered status box (`model:` / `directory:`
     rows), a `Tip:` line, a highlighted prompt row, a response bullet with
     an inline highlighted phrase, an `Updated Plan` checklist, and — for
     this pane only — the "needs input" state: amber ring/badge on the
     window chrome plus a one-line steer typed into a bottom input row.
  3. **Gemini CLI** — black body, small gradient "GEMINI" wordmark, prompt
     line, `Responding with <model>` line, a response bullet, a bordered
     tool-call card (`✓ ToolName  description` + a code/result line), and a
     bottom input placeholder + status footer.
  - All three panes render at once, every scenario. Exactly one pane (Codex,
    per the mockup) carries the "needs input" treatment; the other two show
    an ambient/idle or still-working state.
  - Reuse the site's macOS-style window chrome pattern (traffic lights +
    title) consistently across all three panes; CLI-specific body colors
    are an intentional exception to stay faithful to each tool.
- Update `website/src/components/FeatureRail.tsx`'s `FeatureVisual` switch
  (`case 1`) to render the new component instead of `ConversationDemo`.
  Keep it wired the same way (desktop sticky visual + mobile inline
  fallback) as the other feature visuals.
- `ConversationDemo` and the chat-bubble-only pieces of `GlassBubble` usage
  it depended on can be removed once nothing else references them — check
  `VoiceShowcase` and other consumers of `GlassBubble`/`PopBubble` before
  deleting anything from `voice/GlassBubble.tsx` itself, since those are
  still used elsewhere (`VoiceShowcase`, `EnterpriseDemo`).

### Data changes

- Restructure `website/src/data/voice.ts`'s `conversationScenarios` export
  (or add a new export, e.g. `steeringScenarios`) so each of the 5 scenarios
  carries **per-pane** content instead of a flat message list. Shape used by
  the mockup (see `mockup.html`'s `SCENARIOS` array) — adapt field names to
  match site conventions:

  ```ts
  interface SteeringScenario {
    id: string;
    label: string;
    claude: { win: string; task: string; status: string; done: boolean; foot: string };
    codex: {
      win: string; model: string; dir: string; promptTask: string;
      bullet: (string | { hl: string })[];
      plan: string[];
      steer: string;
    };
    gemini: {
      task: string; model: string;
      bullet: (string | { hl: string })[];
      tool: { name: string; desc: string };
      code: string;
      status: string;
    };
  }
  ```

  Content for all 5 scenarios (Refactor / Tests / Build / Debug /
  Migration) is already written out in `mockup.html`'s `SCENARIOS` constant
  — port it directly rather than rewriting from scratch.

### Scenario → pane assignment

Keep the mockup's fixed assignment: **Claude Code is idle/ambient**,
**Gemini CLI is still working**, **Codex is the one that needs you** in
every scenario. This keeps the "look at the one that needs you" cue
consistent and easy to follow across tab switches, rather than rotating
which pane needs input (which tested as confusing during mockup review).

## Acceptance criteria

- [ ] `ConversationDemo`'s chat-bubble UI is no longer used anywhere on the
      site.
- [ ] The "steering" feature section renders three terminal panes
      simultaneously (Claude Code, Codex, Gemini CLI), each visually
      distinct per its real CLI's colors/chrome/typography.
- [ ] Exactly one pane (Codex) shows a "needs input" indicator plus a
      one-line steer per scenario; the other two panes never show it.
- [ ] The existing 5 scenario tabs (Refactor / Tests / Build / Debug /
      Migration) still work and drive all three panes' content together.
- [ ] No chat/message-role semantics (`role: "user" | "agent" | "tool"`)
      remain in the new component or its data.
- [ ] Layout is responsive: 3 panes side by side on desktop, stacked on
      narrow viewports, no horizontal overflow at any breakpoint (check with
      the site's existing `sm`/`lg` breakpoints).
- [ ] Desktop sticky-visual and mobile inline-fallback wiring in
      `FeatureRail.tsx` both render the new component correctly (parity with
      how the other 4 feature visuals are wired).
- [ ] `pnpm/npm run build` (or the site's existing lint/build command) passes
      with no new TypeScript or lint errors.
- [ ] Visual result matches `mockup.html` closely enough that a side-by-side
      comparison reads as "the same design," even if exact CSS values are
      adapted to the site's Tailwind conventions.

## Non-goals

- Pixel-perfect recreation of each CLI's full ASCII/branding (e.g. Claude's
  full pixel-art mascot at native resolution, Gemini's full blocky ASCII
  wordmark) — a simplified but recognizably faithful version is enough.
- Making the steer input pane interactive/typable by the site visitor — it
  stays a scripted demo like the other `FeatureRail` visuals.
- Changing any other `FeatureRail` section (seats, bandwidth, thin client,
  terminal-is-sacred) — this spec is scoped to the "steering" section only.
- Touching the Android app, Mac companion, or any non-website surface —
  this is a `website/` only change.

## References

- Mockup: `docs/specs/2026-07-18-spec-website-steer-section-redesign/mockup.html`
- Current implementation: `website/src/components/feature-visuals.tsx`
  (`ConversationDemo`), `website/src/components/FeatureRail.tsx`,
  `website/src/data/voice.ts` (`conversationScenarios`)
- Reference CLIs recreated in the mockup:
  - Claude Code — <https://github.com/anthropics/claude-code>
  - Codex — <https://github.com/openai/codex>
  - Gemini CLI — <https://github.com/google-gemini/gemini-cli>
