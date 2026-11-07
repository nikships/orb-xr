# Orb XR — Vision

## The one-sentence version

**Orb XR turns a Samsung Galaxy XR headset into an immersive cockpit for
orchestrating fleets of CLI coding agents** — Claude Code, Codex, Gemini,
Aider, and whatever you run next — each in its own floating terminal, all
driven by the real compute on your Mac.

## Why this exists

The interesting unit of work in software has shifted. A year ago you typed
into one terminal. Now you supervise several agents at once: one is refactoring
a module, one is writing tests, one is chewing through a migration, one is
tailing logs and waiting for you to make a call. The bottleneck is no longer
typing — it's **attention and orchestration**. How many parallel workstreams
can a single developer actually hold in their head and steer at the same time?

A 2D monitor is a bad answer to that question. Tabs hide state. Tmux panes
shrink past the point of usefulness around four splits. You context-switch by
alt-tabbing, which means at any moment you can see one agent and you're blind
to the other three. The screen is a scarce, flat resource, and agent
supervision is inherently a *spatial, peripheral-vision* problem.

A headset is a different kind of answer. In XR the canvas is your whole field
of view and the space around you. Six terminal panes can live at a comfortable
reading distance, arranged in a curved arc, each one a persistent place your
eyes can flick to. The agent grinding through tests is *over there*. The one
waiting on your approval is *in front of you*. You stop alt-tabbing and start
**looking around a room full of work**.

That's the bet: **agentic coding wants more spatial bandwidth than a monitor
can give it, and a headset is the cheapest way to buy that bandwidth.**

## What Orb XR is

- **An orchestration surface, not a terminal app.** The terminal is the
  primitive; the product is the ability to run and watch *many* agents at
  once. Spawning panes is the core gesture — each pane is a seat for an agent.
- **Thin client, heavy host.** The headset renders and routes input. Your Mac
  runs the shells, the agents, the toolchains, the file system, the GPU-bound
  builds. We SSH into macOS's built-in server over your LAN. Nothing about your
  dev environment changes — your dotfiles, your `claude`, your `git`, your
  language servers are all exactly where they already live.
- **Native where it counts.** libghostty renders the terminal grid natively on
  the headset (real ANSI, real Kitty keyboard protocol, real colors), libssh2
  carries the bytes. No web view, no VNC, no screen-scraping a Mac display —
  each pane is a first-class terminal with its own PTY.
- **Immersive when you want it.** Drop the passthrough view of your room for a
  dark skybox and the agents become the only thing in the world. Focus mode is
  a toggle, not a tax.

## What "good" looks like

A developer puts on the headset, scans the QR on their Mac, and within seconds
has four-to-six terminals floating in front of them. Pane 1: `claude` building
a feature. Pane 2: `claude` writing the tests for it. Pane 3: a long-running
build. Pane 4: a shell they drive by hand. They glance from one to the next the
way you'd glance around a workshop. When an agent stops and asks a question,
they look at it, speak or type a one-line steer, and look away again. The
headset disappears; the orchestration is the whole experience.

The win condition is simple: **a single developer comfortably supervising more
concurrent agents than they ever could on a flat screen — and enjoying it.**

## Design principles

1. **The terminal is sacred.** Agents emit real terminal output — spinners,
   TUIs, colors, progress bars, Kitty graphics. We render it faithfully and
   natively. Anything that degrades the terminal to make it "XR-friendly" is
   the wrong trade.
2. **Spatial is the feature, skeuomorphism is not.** Depth, arrangement, and
   peripheral glanceability earn their place because they raise orchestration
   bandwidth. Floating chrome that's spatial for its own sake does not.
3. **Your Mac is the source of truth.** We never fork your environment. The
   headset is a window; close it and your agents keep running on the host.
4. **Input must keep up with supervision.** Steering six agents means input
   can't be the bottleneck — physical keyboard, controller, on-screen
   accessory keys, and (next) voice all route to the focused pane. Talking to
   an agent should be as fast as glancing at it.
5. **Comfort is a hard requirement.** Jank is nausea in XR. Frame stability,
   readable type at distance, and low-latency input aren't polish — they're the
   difference between a tool you wear all day and one you take off in ten
   minutes.

## Where this is headed

- **Voice-driven steering.** Typing on a floating keyboard is the weakest link
  in XR. Push-to-talk speech-to-text routed to the focused pane turns "approve
  / redirect / ask a follow-up" into a spoken sentence — the natural input mode
  for supervising agents you're mostly *watching*.
- **Agent-aware panes.** A pane that knows it's running an agent can do more
  than render bytes: surface "waiting for your input" as a spatial cue you can
  catch in peripheral vision, badge which agents are idle vs. working, let you
  arrange them by project.
- **Session persistence.** Detach the headset, the agents keep running on the
  Mac; put it back on and the room is exactly as you left it.
- **Beyond one Mac.** The same thin-client model extends to a remote dev box or
  a cloud workspace — the headset doesn't care where the host lives, only that
  it speaks SSH.

The terminal panes were never the point. **The point is being inside the work,
steering more of it at once than a screen ever let you.**
