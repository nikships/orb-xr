# New-pane CWD routing

## Contract

When the user opens a terminal from Settings or a Gemini tool, the new pane
should start in the selected source pane's current host directory. The same
rule applies after restoring a saved cockpit.

The current implementation works through host tmux. Do not replace it with an
OSC-only shortcut or inline shell command substitution: both approaches have
failed on a real headset.

## Current implementation

`DefaultCockpitLifecycle.addPane(launchCommand, inheritFrom)` resolves the
source pane before inserting the destination engine. It records:

- the source pane's stable host-session key; and
- normalized engine-side OSC 7 metadata as a fallback.

Pane creation then moves to the lifecycle's IO dispatcher:

1. `PaneCwdProbe` opens a short-lived shell through the cockpit's shared
   `VerifiedHostAccess`.
2. It waits for interactive shell startup output to become quiet. Writing
   earlier can lose the line when zsh/ZLE initializes and flushes type-ahead.
3. It types a marker-framed command that runs:

   ```text
   tmux -L gxr display-message -p -t <source-session> '#{pane_current_path}'
   ```

4. The parser extracts and normalizes an absolute Unix path. Marker strings
   are deliberately split in the typed command so PTY echo cannot be mistaken
   for the real result.
5. If the probe fails or times out, the lifecycle falls back to the source
   engine's normalized OSC 7 directory; if neither exists, the pane starts in
   the host shell's default directory.
6. `HostSessionBootstrap.command()` embeds the concrete directory as a safely
   quoted `new-session -c` argument when it creates the destination tmux
   session.
7. A requested agent launch is submitted after connection. The `cd` fallback
   is used only for a plain shell path with no tmux bootstrap; the tmux path
   must not inject a second `cd` command into the new pane.

The authoritative query is out-of-band. It never tries to reuse the source
pane's live PTY or execute command substitution inside the destination's typed
bootstrap line.

## Why OSC 7 is fallback only

`TerminalSessionEngine.workingDirectory()` is populated by libghostty's
`nativePollPwd()` metadata channel. Common shells and coding agents do not emit
OSC 7 consistently inside tmux, so this value is often `null` even though tmux
knows the live directory.

Host inspection established the reliable source:

```bash
tmux -L gxr list-panes -a \
  -F '#S #{pane_id} #{pane_current_path} #{pane_current_command}'
```

That is why `#{pane_current_path}` is primary and OSC 7 remains a best-effort
fallback.

## Safety invariants

- Every tmux call uses the dedicated `-L gxr` socket. Never query or mutate the
  user's default tmux server.
- Session names must match `[A-Za-z0-9_-]{1,64}` before entering a shell line.
- Only normalized absolute Unix paths are accepted. Relative paths, NULs, and
  malformed values are rejected.
- Embed the probed path with `WorkingDirectory.shellQuote()` and
  `tmux new-session -c`; do not concatenate an unquoted host path.
- Preserve the shell-readiness quiet window, bounded retry, marker splitting,
  and escape-tolerant parser. Prompt/title hooks can prefix the result with OSC
  sequences and PTYs can expand tabs to spaces.
- Keep the probe bounded. A failed query may delay pane creation only through
  the configured readiness/probe timeouts, after which fallback proceeds.
- Do not send a post-connect `cd` into a tmux-backed destination pane. That
  previously exposed bootstrap text instead of opening a usable terminal.

## Reverted approaches

These approaches are historical failures, not alternatives to revive casually:

1. **Snapshot/OSC-only last-known CWD.** Saved and live metadata were commonly
   absent.
2. **Post-connect `tmux send-keys` / typed `cd`.** This regressed startup and
   surfaced the bootstrap command in the pane.
3. **Inline `tmux display-message` command substitution in the destination
   bootstrap.** The host query itself was correct, but combining discovery and
   destination startup made the headset path unreliable.

The current design separates discovery (short-lived verified shell) from
destination creation (concrete `new-session -c` path).

## Verification

Host-JVM coverage:

- `PaneCwdProbeTest` checks the authoritative `tmux -L gxr` query, marker
  echo-safety, prompt escape/tab parsing, unsafe session rejection, and path
  normalization.
- `WorkingDirectoryTest` checks file-URL normalization and shell quoting.
- `HostSessionBootstrapTest` checks the `new-session -c` command.
- `DefaultCockpitLifecycleTest` covers pane creation, limits, focus, and teardown.

These tests cannot reproduce real interactive-shell timing. Any change to
readiness, markers, PTY reads, bootstrap composition, or quoting must also be
smoked on a physical headset against a real Mac SSH/tmux session. Log only the
generated probe/bootstrap diagnostics; never log general terminal input or
credentials.

## Relevant files

- `ghosttyxr/android/app/src/main/kotlin/com/ghosttyxr/app/terminal/session/DefaultCockpitLifecycle.kt`
- `ghosttyxr/android/app/src/main/kotlin/com/ghosttyxr/app/terminal/session/PaneCwdProbe.kt`
- `ghosttyxr/android/app/src/main/kotlin/com/ghosttyxr/app/terminal/session/HostSessionBootstrap.kt`
- `ghosttyxr/android/app/src/main/kotlin/com/ghosttyxr/app/terminal/session/WorkingDirectory.kt`
- `ghosttyxr/android/app/src/main/kotlin/com/ghosttyxr/app/terminal/session/TerminalSessionEngine.kt`
- `ghosttyxr/zig-src/src/bridge/ghosttyxr_snapshot.zig`
