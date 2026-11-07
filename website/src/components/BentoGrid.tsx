import Image from "next/image";
import {
  Check,
  FileText,
  GitBranch,
  Hammer,
  Plus,
  QrCode,
  Wifi,
} from "lucide-react";
import type { CSSProperties, ReactNode } from "react";
import { cn } from "@/lib/utils";

const cardShell = "rounded-2xl bg-white/[.055] shadow-none ring-1 ring-inset ring-white/[.05]";
const raisedShell = "rounded-2xl bg-[#222] shadow-[0_18px_44px_-10px_rgba(0,0,0,.65)] ring-1 ring-inset ring-white/[.06]";

function waveform(seed: number, count = 20) {
  return Array.from({ length: count }, (_, index) => {
    const envelope = Math.sin((index / (count - 1)) * Math.PI) ** 0.5;
    return Math.max(0.18, envelope * (0.55 + 0.45 * Math.sin(1.7 * index + seed) * Math.sin(0.53 * index + 2 * seed)));
  });
}

function PairingSnippetArt() {
  return (
    <div className="relative -mb-12 w-full max-w-[330px]">
      <div className={cn(cardShell, "overflow-hidden")}>
        <div className="flex items-center justify-between border-b border-white/[.05] px-4 py-2.5">
          <span className="flex gap-1.5">
            <span className="size-2 rounded-full bg-[#ec6a5f]" />
            <span className="size-2 rounded-full bg-[#f4bf4f]" />
            <span className="size-2 rounded-full bg-[#61c454]" />
          </span>
          <span className="text-[11px] font-medium text-primary/35">Session</span>
          <span className="rounded-full bg-emerald-400/[.15] px-2 py-0.5 text-[10px] font-medium text-emerald-200/90">Paired</span>
        </div>
        <div className="space-y-2.5 px-5 pb-5 pt-4 text-[13px] leading-relaxed">
          <div className="font-mono text-[10px] uppercase tracking-[.14em] text-indigo-300/70">## Pair</div>
          <div className="text-primary/75">Scan the QR from your Mac&apos;s menu bar.</div>
          <div className="pt-1 font-mono text-[10px] uppercase tracking-[.14em] text-indigo-300/70">## Fly</div>
          <div className="text-primary/75">
            Six panes spawn right where you left them.
            <span className="ml-1 inline-block h-3.5 w-1.5 animate-[blink_1s_steps(1)_infinite] bg-primary/40 align-middle" />
          </div>
        </div>
      </div>
    </div>
  );
}

function SourceOfTruthArt() {
  return (
    <div className="relative w-full max-w-[330px]">
      <div className="flex flex-col items-center gap-1.5 rounded-2xl border border-dashed border-white/[.13] px-6 pb-5 pt-6">
        <Image src="/images/orbxr/gxr-ic-mirror.png" alt="" width={36} height={36} className="-my-1 size-9" />
        <span className="text-[13px] font-medium text-primary/65">Your Mac stays the source of truth</span>
        <span className="text-[10px] uppercase tracking-[.08em] text-primary/30">dotfiles · toolchains · repos</span>
      </div>
      <div className={cn(raisedShell, "absolute -top-2 right-2 flex rotate-[7deg] items-center gap-2 px-3 py-2.5")}>
        <FileText className="size-3.5 text-cyan-200/80" />
        <span className="font-mono text-[11px] font-medium text-primary/80">~/.claude/CLAUDE.md</span>
      </div>
      <div className={cn(cardShell, "mt-3.5 px-4 py-3.5")}>
        <div className="flex items-center justify-between text-[11px]">
          <span className="font-medium text-primary/75">Reattaching panes</span>
          <span className="font-mono text-cyan-200/80">72%</span>
        </div>
        <div className="mt-2 h-1 overflow-hidden rounded-full bg-white/[.08]">
          <div className="h-full w-[72%] rounded-full bg-cyan-300/70" />
        </div>
      </div>
    </div>
  );
}

function GlanceSteerArt() {
  const bars = waveform(4, 24);
  return (
    <div className={cn(cardShell, "w-full max-w-[330px] overflow-hidden")}>
      <div className="flex items-center justify-between border-b border-white/[.05] px-4 py-2.5">
        <span className="font-mono text-[11px] font-medium text-primary/50">pane 3 · claude</span>
        <span className="flex items-center gap-1.5 rounded-full bg-emerald-400/10 px-2 py-1 text-[9px] font-medium uppercase tracking-wider text-emerald-300">
          <span className="size-1.5 animate-pulse rounded-full bg-emerald-300" /> Live
        </span>
      </div>
      <div className="space-y-3 px-4 py-4">
        <div className="w-fit max-w-[88%] rounded-2xl rounded-bl-md bg-violet-400/10 px-3 py-2 text-[11px] text-primary/75">
          All 48 tests pass. Ship it?
        </div>
        <div className="ml-auto w-fit max-w-[82%] rounded-2xl rounded-br-md bg-white/[.06] px-3 py-2 text-[11px] text-primary/55">
          Ship it.
        </div>
      </div>
      <div className="flex h-12 items-center gap-[3px] border-t border-white/[.05] px-4">
        <span className="mr-2 flex size-7 shrink-0 items-center justify-center rounded-full bg-white/[.06] ring-1 ring-inset ring-white/10">
          <Image src="/images/orbxr/gxr-ic-voice.png" alt="" width={26} height={26} className="size-[26px]" />
        </span>
        {bars.map((height, index) => (
          <span
            key={index}
            className="max-w-[3px] flex-1 animate-[waveform_1.1s_ease-in-out_infinite] rounded-full bg-violet-300/60"
            style={{ "--bar-height": `${Math.round(height * 100)}%`, animationDelay: `${index * 35}ms` } as CSSProperties}
          />
        ))}
      </div>
    </div>
  );
}

function SeatCard({ name, seed, active }: { name: string; seed: number; active?: boolean }) {
  return (
    <div className={cn(cardShell, "w-full px-4 py-3.5 sm:w-[156px]", active && "bg-white/[.08] ring-white/[.12]")}>
      <div className="flex items-center justify-between">
        <span className="font-mono text-[13px] font-medium">{name}</span>
        {active ? (
          <span className="flex items-center gap-[2.5px]">
            <span className="h-2.5 w-[3px] rounded-full bg-primary/80" />
            <span className="h-2.5 w-[3px] rounded-full bg-primary/80" />
          </span>
        ) : <span className="text-[9px] uppercase tracking-wider text-primary/35">idle</span>}
      </div>
      <div className="mt-3 flex h-5 items-center gap-[3px]">
        {waveform(seed).map((height, index) => (
          <span
            key={index}
            className={cn("max-w-[3px] flex-1 rounded-full", active ? "bg-primary/60" : "bg-primary/[.14]")}
            style={{ height: `${Math.round(height * 100)}%` }}
          />
        ))}
      </div>
    </div>
  );
}

function PanesArt() {
  return (
    <div className="flex w-full flex-col items-center justify-center gap-8 sm:flex-row sm:items-end sm:justify-between sm:px-2">
      <div className="text-center sm:text-left">
        <div className="font-display text-7xl leading-none tracking-tight sm:text-8xl">6+</div>
        <div className="mt-2 text-sm text-primary/55">panes in view · one seat per agent</div>
      </div>
      <div className="grid w-full grid-cols-2 gap-2.5 sm:w-auto">
        <SeatCard name="claude" seed={2} active />
        <SeatCard name="codex" seed={5} />
        <SeatCard name="gemini" seed={9} />
        <div className="flex w-full flex-col items-center justify-center gap-1.5 rounded-2xl px-4 py-3.5 text-center ring-1 ring-inset ring-white/[.09] sm:w-[156px]">
          <span className="flex items-center gap-1.5 text-[13px] font-medium text-primary/75"><Plus className="size-3 text-primary/60" />Spawn a pane</span>
          <span className="text-[10px] leading-tight text-primary/35">one gesture, one PTY</span>
        </div>
      </div>
    </div>
  );
}

function PersistenceArt() {
  const bars = waveform(7, 44);
  return (
    <div className="flex w-full flex-col gap-5">
      <div className="relative pt-9">
        <div className="absolute left-[60.2%] top-0 -translate-x-1/2">
          <span className={cn(raisedShell, "whitespace-nowrap rounded-lg px-2 py-1 font-mono text-[10px] text-violet-200/90")}>
            headset detached · 4:12
          </span>
          <span className="mx-auto block h-3 w-px bg-violet-300/40" />
        </div>
        <div className="flex h-14 items-center gap-[3px]">
          {bars.map((height, index) => (
            <span
              key={index}
              className={cn("min-w-0 flex-1 rounded-full", index < 17 ? "bg-orange-300/90" : index === 26 ? "bg-violet-300/70" : "bg-primary/[.12]")}
              style={{ height: `${Math.round(height * 100)}%` }}
            />
          ))}
        </div>
      </div>
      <div className="flex items-center justify-between gap-4">
        <span className="flex min-w-0 items-center gap-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-white/[.06] ring-1 ring-inset ring-white/10"><Image src="/images/orbxr/gxr-ic-session.png" alt="" width={34} height={34} className="size-[34px]" /></span>
          <span className="truncate text-[13px] text-primary/50">Your agents <span className="font-medium text-orange-300/90">kept running</span> the whole time.</span>
        </span>
        <span className="shrink-0 font-mono text-xs text-primary/45"><span className="text-orange-300/90">4:12</span> / 6:03</span>
      </div>
    </div>
  );
}

function ToolsArt() {
  return (
    <div className="relative h-[210px] w-full max-w-[330px]">
      <svg className="absolute inset-0 size-full text-violet-300/55" viewBox="0 0 330 204" fill="none">
        <path d="M165 52v25M165 77H78v28M165 77h87v28M165 77v59" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 5" />
      </svg>
      <div className={cn(raisedShell, "absolute left-1/2 top-1 flex size-12 -translate-x-1/2 items-center justify-center")}>
        <Image src="/images/orbxr/gxr-ic-terminals.png" alt="" width={44} height={44} className="size-11" />
      </div>
      <div className={cn(cardShell, "absolute left-2 top-[104px] flex items-center gap-2 px-3 py-2 font-mono text-[10px] text-primary/65")}><GitBranch className="size-3" />claude · feat/auth</div>
      <div className={cn(cardShell, "absolute right-1 top-[104px] flex items-center gap-2 px-3 py-2 font-mono text-[10px] text-primary/65")}><Hammer className="size-3" />gradlew assemble</div>
      <div className={cn(raisedShell, "absolute bottom-0 left-1/2 flex -translate-x-1/2 items-center gap-2 p-2")}>
        {[["Z", "Zig core"], ["G", "libghostty"], ["S", "libssh2"]].map(([label, name]) => (
          <span key={name} title={name} className="flex size-9 items-center justify-center rounded-lg bg-white/[.06] text-[11px] font-medium">{label}</span>
        ))}
      </div>
    </div>
  );
}

function FocusModeArt() {
  return (
    <div className="w-full max-w-[330px] space-y-3">
      <div className={cn(cardShell, "ml-auto max-w-[82%] px-4 py-3 text-[12px] text-primary/65")}>
        Too much going on in the room?
      </div>
      <div className={cn(raisedShell, "max-w-[90%] px-4 py-3")}>
        <div className="flex items-center gap-2 text-[12px] text-primary/80">
          <Image src="/images/orbxr/gxr-ic-focus.png" alt="" width={24} height={24} className="-m-1 size-6 shrink-0" />
          Dark skybox on — just you and the agents.
        </div>
        <div className="mt-3 flex w-fit items-center gap-1.5 rounded-full bg-emerald-400/10 px-2.5 py-1 text-[10px] font-medium text-emerald-300">
          <Check className="size-3" /> Passthrough off
        </div>
      </div>
    </div>
  );
}

function CoachArt({ src, alt }: { src: string; alt: string }) {
  return <Image src={src} alt={alt} width={1200} height={718} className="w-full max-w-[330px]" />;
}

function PairHostArt() {
  return (
    <div className={cn(raisedShell, "w-full max-w-[330px] overflow-hidden p-5")}>
      <div className="flex items-center justify-between">
        <div>
          <div className="text-[11px] text-primary/40">Your host</div>
          <div className="mt-1 text-sm font-medium">MacBook Pro · LAN</div>
        </div>
        <span className="flex size-9 items-center justify-center rounded-xl bg-emerald-400/10 text-emerald-300"><QrCode className="size-4" /></span>
      </div>
      <div className="mt-6 font-mono text-xl tracking-tight text-primary/80">ssh you@macbook.local</div>
      <div className="mt-6 flex gap-2">
        <button type="button" className="flex h-9 flex-1 items-center justify-center gap-2 rounded-full border border-white/10 text-[11px] text-primary/55"><QrCode className="size-3" />Rescan QR</button>
        <button type="button" className="flex h-9 flex-1 items-center justify-center gap-2 rounded-full bg-emerald-400 text-[11px] font-medium text-emerald-950"><Wifi className="size-3" />Connect</button>
      </div>
    </div>
  );
}

const features = [
  { title: "Scan and you’re in", description: "Open the companion app on your Mac, scan the QR from the headset, and your room of agents assembles itself.", span: "lg:col-span-4", art: <PairingSnippetArt />, min: "min-h-[296px] sm:min-h-[256px]" },
  { title: "Your Mac is the source of truth", description: "We never fork your environment. Dotfiles, toolchains, repos — everything stays on the host.", span: "lg:col-span-4", art: <SourceOfTruthArt />, min: "min-h-[256px]" },
  { title: "Glance, steer, look away", description: "Agents mostly need a word, not a session. Answer in one line and get back to the rest of the room.", span: "lg:col-span-4", art: <GlanceSteerArt />, min: "min-h-[256px]" },
  { title: "Six panes and counting", description: "Spawn a seat for every agent, arranged in a curved arc at reading distance. Spatial layout is the feature.", span: "lg:col-span-7", art: <PanesArt />, min: "min-h-[380px] sm:min-h-[256px]" },
  { title: "Session persistence", description: "Take the headset off and the agents keep running on your Mac. Put it back on and the room is as you left it.", span: "lg:col-span-5", art: <PersistenceArt />, min: "min-h-[256px]" },
  { title: "Every CLI you already run", description: "If it runs in a terminal on your Mac, it runs here — agents, builds, TUIs, plain shells.", span: "lg:col-span-4", art: <ToolsArt />, min: "min-h-[256px]" },
  { title: "Focus mode", description: "Swap passthrough for a dark skybox and the agents become the only thing in the world. A toggle, not a tax.", span: "lg:col-span-4", art: <FocusModeArt />, min: "min-h-[256px]" },
  { title: "Pair with your Mac", description: "libssh2 into macOS’s built-in SSH server over your LAN. No cloud relay, no account, nothing leaves your network.", span: "lg:col-span-4", art: <PairHostArt />, min: "min-h-[256px]" },
  { title: "Pinch to spawn a pane", description: "A new pane inherits from the one you’re looking at. Pinch, drag, release — then seat an agent in it.", span: "lg:col-span-4", art: <CoachArt src="/images/orbxr/coach-spawn-pane.png" alt="Hand pinching to spawn a new terminal pane" />, min: "min-h-[256px]" },
  { title: "Prayer hold to lock the room", description: "Palms together freezes every pane exactly where you parked it. Same gesture to unlock.", span: "lg:col-span-4", art: <CoachArt src="/images/orbxr/coach-prayer-hold.png" alt="Hands held together to lock the pane layout" />, min: "min-h-[256px]" },
  { title: "Palm up for the voice pet", description: "Hold a palm up and the orb floats in, listening. Flip it down to send the command to the focused pane.", span: "lg:col-span-4", art: <CoachArt src="/images/orbxr/coach-palm-pet.png" alt="Orb voice pet hovering over an open palm" />, min: "min-h-[256px]" },
] as const;

function BentoCard({ title, description, span, art, min }: { title: string; description: string; span: string; art: ReactNode; min: string }) {
  return (
    <article className={cn("relative isolate flex flex-col overflow-hidden rounded-[1.75rem] bg-card", span)}>
      <div className="relative z-10 p-7 pb-0 sm:p-8 sm:pb-0">
        <h3 className="font-display text-lg tracking-tight sm:text-xl">{title}</h3>
        <p className="mt-1.5 max-w-xl text-sm leading-relaxed text-primary/50">{description}</p>
      </div>
      <div className={cn("relative flex-1", min)}>
        <div className="absolute inset-0 flex items-center justify-center px-7 pb-6 pt-10 sm:px-8 sm:pb-7 sm:pt-11">{art}</div>
      </div>
    </article>
  );
}

export function BentoGrid() {
  return (
    <section className="border-t border-border py-16 sm:py-24">
      <div className="mx-auto w-full max-w-7xl px-4 lg:px-6">
        <div className="mx-auto mb-12 max-w-2xl text-center sm:mb-16">
          <h2 className="font-display text-3xl tracking-tight sm:text-4xl">Everything you need to run the room</h2>
          <p className="mt-4 text-base leading-relaxed text-primary/55">
            From the first QR scan to a locked room full of working agents — every piece is native, local, and already
            yours. No cloud relay, no account, no environment drift.
          </p>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:gap-4 lg:grid-cols-12">
          {features.map((feature) => <BentoCard key={feature.title} {...feature} />)}
        </div>
      </div>
    </section>
  );
}
