import type { FeatureItem, OrbPalette, SteeringScenario } from "@/types/voice";

export const evePalette: OrbPalette = {
  anchor: "#2A0F22",
  accents: ["#FF7ECB", "#D14FFF", "#FFDCF2"],
};

export const carinaPalette: OrbPalette = {
  anchor: "#241627",
  accents: ["#FFC9E0", "#E4A8FF", "#FFE9F4"],
};

export const zenithPalette: OrbPalette = {
  anchor: "#0A1B2E",
  accents: ["#38BDF8", "#0E7BD1", "#CFF2FF"],
};

export const ctaPalette: OrbPalette = {
  anchor: "#07262B",
  accents: ["#00C2A8", "#38E1FF", "#FFC65C"],
};

export const features: FeatureItem[] = [
  {
    id: "seats",
    title: "A seat for every agent",
    description:
      "Spawn a pane, seat an agent — claude, codex, gemini, aider, a build, a plain shell. Arrange them in a curved arc at reading distance.",
    benefits: [
      "Six panes glanceable at once, arranged around you.",
      "Each pane is a first-class terminal with its own PTY.",
      "Pinch, drag, release — a new pane inherits from the one you're looking at.",
    ],
  },
  {
    id: "steering",
    title: "Steer, don’t alt-tab",
    description:
      "Supervising agents is a peripheral-vision problem. Look at the one that needs you, type a one-line steer, look away again.",
    benefits: [
      "Catch “waiting for your input” out of the corner of your eye.",
      "Keyboard, controller, and accessory keys all route to the focused pane.",
    ],
  },
  {
    id: "bandwidth",
    title: "More agents than a monitor allows",
    description:
      "Tabs hide state, and tmux shrinks past useful around four splits. XR gives you your whole field of view back.",
    benefits: [
      "The canvas is the space around you, not a 27-inch rectangle.",
      "Six full-size panes at reading distance — not six postage stamps.",
      "One developer, more concurrent workstreams — comfortably.",
    ],
  },
  {
    id: "thinclient",
    title: "Your whole toolchain, one room",
    description:
      "The headset is a thin client. Your Mac runs the shells, the agents, the toolchains, and the files — nothing about your setup changes.",
    benefits: [
      "SSH over your LAN into macOS’s built-in server.",
      "Your dotfiles, your claude, your git — exactly where they already live.",
      "Take the headset off and your agents keep running on the host.",
    ],
  },
  {
    id: "terminal",
    title: "The terminal is sacred",
    description:
      "Every pane renders natively on the headset. No web views, no VNC, no screen-scraping a Mac display.",
    benefits: [
      "Real ANSI, Kitty keyboard protocol, true color.",
      "Spinners, TUIs, and progress bars render exactly as they should.",
      "If it runs in a terminal on your Mac, it renders right here in XR.",
    ],
  },
];

export const steeringScenarios: SteeringScenario[] = [
  {
    id: "refactor",
    label: "Refactor",
    claude: {
      win: "Refactor Session",
      task: "extract token logic into TokenService",
      status: "Typecheck clean \u2014 48 tests passing",
      done: true,
      foot: "refactor",
    },
    codex: {
      win: "ghosttyxr \u2014 codex \u2014 119x30",
      model: "gpt-5.6-sol medium",
      dir: "~/code/orb-xr",
      promptTask: "Run the auth tests before you commit.",
      bullet: ["Two call sites used the old ", { hl: "refresh flow" }, ", I adapted them and left a note."],
      plan: ["Extract TokenService from the auth module", "Update 14 call sites", "Run type checker + auth suite"],
      steer: "commit it",
    },
    gemini: {
      task: "@auth Write a migration note for the TokenService extraction",
      model: "gemini-3.6-flash",
      bullet: ["Drafted a short migration note and linked it in the ", { hl: "PR description" }, "."],
      tool: { name: "WriteFile", desc: "Writing to docs/migrations/token-service.md" },
      code: '"Public API unchanged."',
      status: "Awaiting your next command or request.",
    },
  },
  {
    id: "tests",
    label: "Tests",
    claude: {
      win: "Coverage Session",
      task: "raise coverage on the parser",
      status: "Writing parser_escape_test.ts (esc to interrupt)",
      done: false,
      foot: "tests",
    },
    codex: {
      win: "ghosttyxr \u2014 codex \u2014 119x30",
      model: "gpt-5.6-sol medium",
      dir: "~/code/orb-xr",
      promptTask: "Fix the tab-stop bug, keep the new tests.",
      bullet: ["10 of 12 new tests passed \u2014 the ", { hl: "tab-stop edge case" }, " looks like a real bug."],
      plan: ["Add escape-sequence + malformed-input cases", "Isolate the failing tab-stop case", "Patch tabstops.ts and re-run"],
      steer: "ship it once green",
    },
    gemini: {
      task: "@parser Summarize today's coverage delta",
      model: "gemini-3.6-flash",
      bullet: ["Coverage on the parser moved from 61% to ", { hl: "84%" }, " after the fix."],
      tool: { name: "RunTests", desc: "npm test parser -q" },
      code: "12 passed in 0.8s",
      status: "Awaiting your next command or request.",
    },
  },
  {
    id: "build",
    label: "Build",
    claude: {
      win: "Build Watch",
      task: "watch the release build and flag failures",
      status: "Idle \u2014 last build succeeded (3m 41s)",
      done: true,
      foot: "build",
    },
    codex: {
      win: "ghosttyxr \u2014 codex \u2014 119x30",
      model: "gpt-5.6-sol medium",
      dir: "~/code/orb-xr",
      promptTask: "Install the release build on the headset once it's done.",
      bullet: ["BUILD SUCCESSFUL in 3m 41s \u2014 APK is ", { hl: "41MB" }, "."],
      plan: ["./gradlew assembleRelease", "Package APK", "adb install on Galaxy XR"],
      steer: "install it",
    },
    gemini: {
      task: "@play Draft the internal-track release notes",
      model: "gemini-3.6-flash",
      bullet: ["Release notes drafted from the last ", { hl: "6 commits" }, "."],
      tool: { name: "WriteFile", desc: "Writing to RELEASE_NOTES.md" },
      code: '"fix(session): silence tmux bootstrap noise"',
      status: "Awaiting your next command or request.",
    },
  },
  {
    id: "debug",
    label: "Debug",
    claude: {
      win: "Repro Session",
      task: "reproduce the paste freeze with a 2MB buffer",
      status: "Profiling render loop (esc to interrupt)",
      done: false,
      foot: "debug",
    },
    codex: {
      win: "ghosttyxr \u2014 codex \u2014 119x30",
      model: "gpt-5.6-sol medium",
      dir: "~/code/orb-xr",
      promptTask: "Move the parser off the main thread.",
      bullet: ["Found it \u2014 the parser drains the channel on the ", { hl: "main thread" }, " past 256KB."],
      plan: ["Reproduce with a 2MB paste", "Profile the render loop", "Move parsing to a coroutine with backpressure"],
      steer: "ship the fix",
    },
    gemini: {
      task: "@logs Check logcat for related OOM warnings",
      model: "gemini-3.6-flash",
      bullet: ["No OOM warnings \u2014 this was a ", { hl: "threading issue" }, ", not memory."],
      tool: { name: "Shell", desc: "adb logcat | grep Terminal" },
      code: "I/Terminal: paste buffer 2.1MB",
      status: "Awaiting your next command or request.",
    },
  },
  {
    id: "migration",
    label: "Migration",
    claude: {
      win: "Backfill Session",
      task: "verify checksums after the SQLite backfill",
      status: "1,842 / 1,842 records verified",
      done: true,
      foot: "migration",
    },
    codex: {
      win: "ghosttyxr \u2014 codex \u2014 119x30",
      model: "gpt-5.6-sol medium",
      dir: "~/code/orb-xr",
      promptTask: "Flip the sqlite_reads flag.",
      bullet: ["Reads are on SQLite behind a flag \u2014 ", { hl: "JSON fallback" }, " stays until you flip it."],
      plan: ["Create schema", "Backfill 1,842 records", "Swap the read path behind a flag"],
      steer: "flip it",
    },
    gemini: {
      task: "@config Benchmark read latency before/after",
      model: "gemini-3.6-flash",
      bullet: ["Config reads dropped from 12ms to ", { hl: "3ms" }, " after the flip."],
      tool: { name: "Shell", desc: "export FLAG_sqlite_reads=1" },
      code: "reads: 12ms -> 3ms",
      status: "Awaiting your next command or request.",
    },
  },
];
