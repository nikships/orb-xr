# docs/ — design notes for agents

Cross-cutting architecture, product behavior, and handoff records. Prefer the
component `AGENTS.md` files for day-to-day build/test commands; use this folder
for system-wide "how it works" guidance and investigation history.

## Files

| Doc | Use when |
|---|---|
| [`architecture.md`](architecture.md) | Mapping runtime components, Android packages, cockpit ownership, terminal data flow, and shared contracts |
| [`cwd-routing-handoff.md`](cwd-routing-handoff.md) | Touching new-pane CWD inheritance. Read before changing the working host-probe path; PTY timing is delicate |
| [`monetization.md`](monetization.md) | Free/Pro boundaries, onboarding access, entitlement truth, Play verification, and backend gates |
| [`onboarding/README.md`](onboarding/README.md) | First-run setup flow, live cockpit coach, bundled narration, and temporary onboarding access |
| [`streaming-performance-follow-ups.md`](streaming-performance-follow-ups.md) | Resolved record of the native SSH read-path and WebRTC window-mirror migration |
| [`specs/`](specs/) | Dated design specs (historical unless verified against shipping code) |

## Rules

1. **Do not simplify CWD casually.** See `cwd-routing-handoff.md`. The current
   `PaneCwdProbe` waits for a real interactive shell, queries host
   `tmux pane_current_path`, and safely embeds the concrete result into
   `new-session -c`; OSC 7 is only fallback. Validate changes on a real headset
   SSH session.
2. **Keep architecture.md honest.** When you move packages or change the data
   flow, update `architecture.md` in the same PR as the code. Root and
   android `AGENTS.md` also list package maps; keep them aligned.
3. **Product intent is not here.** Product-shaping decisions go through
   [`../VISION.md`](../VISION.md). This folder records implementation behavior
   and technical history.
4. **No secrets, no keys, no machine-local paths** beyond already-public
   workspace examples.
5. Prefer Conventional Commits with `docs:` for doc-only PRs.

## Related

- Root map: [`../AGENTS.md`](../AGENTS.md)
- Headset app: [`../ghosttyxr/android/AGENTS.md`](../ghosttyxr/android/AGENTS.md)
- Native: [`../ghosttyxr/zig-src/AGENTS.md`](../ghosttyxr/zig-src/AGENTS.md)
- Companion: [`../ghosttyxr/mac-companion/AGENTS.md`](../ghosttyxr/mac-companion/AGENTS.md)
- Firebase: [`../firebase/AGENTS.md`](../firebase/AGENTS.md)
- Website: [`../website/AGENTS.md`](../website/AGENTS.md)
- Play listing: [`../store/play/AGENTS.md`](../store/play/AGENTS.md)
