"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { ClaudeCode, Codex, GeminiCLI } from "@lobehub/icons";
import { steeringScenarios, carinaPalette, evePalette, zenithPalette } from "@/data/voice";
import type { RichText, SteeringCodexPane, SteeringGeminiPane, SteeringClaudePane } from "@/types/voice";
import { cn } from "@/lib/utils";
import { AgentOrb } from "@/components/voice/AgentOrb";
import { TerminalWindow } from "@/components/TerminalWindow";
import { PopBubble } from "@/components/voice/GlassBubble";

function ScaledStage({ width, height, children, className }: { width: number; height: number; children: ReactNode; className?: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const update = () => setScale(Math.min(1, container.clientWidth / width));
    update();
    const observer = new ResizeObserver(update);
    observer.observe(container);
    return () => observer.disconnect();
  }, [width]);

  return (
    <div ref={containerRef} className={cn("flex w-full justify-center", className)}>
      <div className="relative shrink-0" style={{ width: width * scale, height: height * scale }}>
        <div
          className="absolute left-0 top-0 origin-top-left"
          style={{ width, height, transform: `scale(${scale})` }}
        >
          {children}
        </div>
      </div>
    </div>
  );
}

const agentSeats = [
  {
    id: "claude",
    label: "claude",
    seed: "claude",
    palette: evePalette,
    archetype: "spiral" as const,
    transcript: "Refactor’s done — typecheck is clean across all 14 call sites.",
    tail: "tr" as const,
    desktop: { size: 244, left: 199, top: 180, bubbleLeft: 26, bubbleTop: 322, bubbleWidth: 237 },
    mobile: { size: 196, left: 124, top: 157, bubbleLeft: 5, bubbleTop: 268, bubbleWidth: 190 },
  },
  {
    id: "codex",
    label: "codex",
    seed: "codex",
    palette: carinaPalette,
    archetype: "nebula" as const,
    transcript: "12 of 14 tests passing. Fixing the tab-stop edge case now.",
    tail: "br" as const,
    desktop: { size: 186, left: 113, top: 6, bubbleLeft: 22, bubbleTop: -14, bubbleWidth: 218 },
    mobile: { size: 150, left: 55, top: 18, bubbleLeft: 0, bubbleTop: 0, bubbleWidth: 205 },
  },
  {
    id: "gemini",
    label: "gemini",
    seed: "gemini",
    palette: zenithPalette,
    archetype: "spiral" as const,
    transcript: "Build succeeded in 3m 41s — installing on the headset…",
    tail: "tl" as const,
    desktop: { size: 150, left: 317, top: 24, bubbleLeft: 416, bubbleTop: 127, bubbleWidth: 197 },
    mobile: { size: 120, left: 218, top: 33, bubbleLeft: 220, bubbleTop: 116, bubbleWidth: 175 },
  },
];

export function VoiceShowcase({ mobile = false }: { mobile?: boolean }) {
  const variant = mobile ? "mobile" : "desktop";
  const dimensions = mobile ? { width: 395, height: 353 } : { width: 620, height: 430 };

  return (
    <div className="relative flex size-full items-center justify-center">
      <ScaledStage width={dimensions.width} height={dimensions.height}>
        {agentSeats.map((seat, index) => {
          const position = seat[variant];
          return (
            <div
              key={seat.id}
              className="absolute animate-[agent-orb-enter_.6s_cubic-bezier(.22,1,.36,1)_both]"
              style={{
                left: position.left,
                top: position.top,
                width: position.size,
                height: position.size,
                animationDelay: `${index * 140}ms`,
              }}
            >
              <div
                className="relative animate-[agent-orb-float_7s_ease-in-out_infinite]"
                style={{
                  animationDelay: `${index * 400}ms`,
                  animationDuration: `${6.5 + index * 1.3}s`,
                }}
              >
                <AgentOrb seed={seat.seed} palette={seat.palette} archetype={seat.archetype} size={position.size} />
                <span className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white/90 px-3 py-1 font-mono text-[11px] font-medium text-jet shadow-sm ring-1 ring-black/5 backdrop-blur-sm">
                  {seat.label}
                </span>
              </div>
            </div>
          );
        })}
        {agentSeats.map((seat, index) => {
          const position = seat[variant];
          return (
            <div
              key={`${seat.id}-bubble`}
              className="pointer-events-none absolute z-10"
              style={{ left: position.bubbleLeft, top: position.bubbleTop, maxWidth: position.bubbleWidth }}
            >
              <PopBubble text={seat.transcript} tail={seat.tail} size="xs" delay={index * 120} />
            </div>
          );
        })}
      </ScaledStage>
    </div>
  );
}

function Rich({ parts, hlClass }: { parts: RichText; hlClass: string }) {
  return (
    <>
      {parts.map((part, index) =>
        typeof part === "string" ? (
          <span key={index}>{part}</span>
        ) : (
          <span key={index} className={hlClass}>
            {part.hl}
          </span>
        ),
      )}
    </>
  );
}

function ClaudeMascot() {
  return (
    <svg viewBox="0 0 10 8" className="size-7 shrink-0" style={{ imageRendering: "pixelated" }} aria-hidden="true">
      <g fill="#e8927c">
        <rect x="1" y="0" width="8" height="1" />
        <rect x="0" y="1" width="10" height="4" />
        <rect x="1" y="5" width="1" height="2" />
        <rect x="3" y="5" width="1" height="2" />
        <rect x="6" y="5" width="1" height="2" />
        <rect x="8" y="5" width="1" height="2" />
      </g>
      <g fill="#0b1f1c">
        <rect x="2.4" y="2" width="1.2" height="1.4" />
        <rect x="6.4" y="2" width="1.2" height="1.4" />
      </g>
    </svg>
  );
}

function ClaudePane({ data }: { data: SteeringClaudePane }) {
  return (
    <TerminalWindow title={data.win} bodyClassName="bg-[#0b1f1c] px-3.5 py-3">
      <div className="flex items-start gap-2.5">
        <ClaudeMascot />
        <div className="min-w-0">
          <div className="flex items-center gap-1.5 font-sans text-[13px] font-bold text-[#f3f3f1]">
            <ClaudeCode size={14} aria-hidden="true" />
            Claude Code
          </div>
          <div className="mt-0.5 text-[10px] text-[#f3f3f1]/45">Fable 5 · Claude Enterprise</div>
          <div className="mt-0.5 truncate text-[9px] text-[#f3f3f1]/35">~/repos/orb-xr</div>
        </div>
      </div>
      <div className="my-3 h-px bg-white/10" />
      <div className="rounded-md bg-white/[.07] px-2.5 py-2 text-[11px] text-[#f3f3f1]">
        <span className="mr-1.5 text-[#7fd7c4]">&gt;</span>
        {data.task}
      </div>
      <div className={cn("mt-2.5 flex items-center gap-1.5 text-[10px]", data.done ? "text-[#7fd7c4]" : "text-[#e8927c]")}>
        <span className={cn("size-1.5 rounded-full bg-current", !data.done && "animate-pulse")} />
        {data.done ? "✓ " : "· "}
        {data.status}
      </div>
      <div className="mt-auto flex items-center justify-between gap-2 border-t border-white/10 pt-2 text-[9px] text-[#f3f3f1]/35">
        <span className="truncate">
          demo (<b className="font-medium text-[#e8927c]">{data.foot}</b>)
        </span>
        <span className="hidden shrink-0 sm:inline">Thinking on (tab to toggle)</span>
      </div>
    </TerminalWindow>
  );
}

function CodexPane({ data }: { data: SteeringCodexPane }) {
  return (
    <TerminalWindow title={data.win} needsInput bodyClassName="bg-black px-3 py-3">
      <div className="rounded-lg border border-white/15 px-2.5 py-2">
        <div className="flex items-center gap-1.5 font-mono text-[12px] font-bold text-[#f3f3f3]">
          <Codex.Color size={13} aria-hidden="true" />
          <span>OpenAI Codex</span> <span className="font-normal text-white/35">(v0.0.0)</span>
        </div>
        <div className="mt-1.5 text-[10px] text-white/40">
          <span className="inline-block w-14 text-white/40">model:</span>
          <span className="text-white/85">{data.model}</span>
          <span className="ml-2 text-[#6ecbf5]">/model to change</span>
        </div>
        <div className="mt-0.5 text-[10px] text-white/40">
          <span className="inline-block w-14 text-white/40">directory:</span>
          <span className="text-white/85">{data.dir}</span>
        </div>
      </div>
      <div className="mt-2.5 text-[10px] font-semibold text-[#f3f3f3]">
        <b className="font-bold">Tip:</b> Use /feedback to send logs to the maintainers when something looks off.
      </div>
      <div className="mt-2.5 rounded bg-white/[.09] px-2 py-1.5 text-[10px] text-[#f3f3f3]">
        <span className="mr-1.5 text-white/50">&gt;</span>
        {data.promptTask}
      </div>
      <div className="mt-2.5 flex gap-1.5 text-[10px] leading-relaxed text-white/55">
        <span className="mt-1 shrink-0 text-[7px] text-white/35">●</span>
        <span className="min-w-0">
          <Rich parts={data.bullet} hlClass="text-[#6ecbf5]" />
        </span>
      </div>
      <div className="mt-2.5 font-mono text-[10px] font-bold text-[#f3f3f3]">Updated Plan</div>
      <div className="mt-1 flex flex-col gap-1 pl-1.5">
        {data.plan.map((item) => (
          <div key={item} className="flex gap-1.5 text-[10px] text-white/45">
            <span className="text-white/25">☐</span>
            <span className="truncate">{item}</span>
          </div>
        ))}
      </div>
      <div className="mt-auto flex items-center gap-2 border-t border-white/10 pt-2.5">
        <span className="text-[12px] font-bold text-amber-400">▸</span>
        <span className="text-[11px] text-white">{data.steer}</span>
        <span className="ml-0.5 inline-block h-3 w-[5px] animate-[blink_1s_step-end_infinite] bg-white" />
      </div>
    </TerminalWindow>
  );
}

function GeminiPane({ data }: { data: SteeringGeminiPane }) {
  return (
    <TerminalWindow title="gemini — zsh — 100×32" bodyClassName="bg-black px-3 py-3">
      <div className="flex items-center">
        <GeminiCLI.Combine size={20} type="color" aria-hidden="true" />
      </div>
      <div className="mt-2 text-[10px] text-[#e58bd0]">&gt; {data.task}</div>
      <div className="mt-0.5 text-[9px] italic text-white/35">Responding with {data.model}</div>
      <div className="mt-2 flex gap-1.5 text-[10px] leading-relaxed text-white/65">
        <GeminiCLI.Color size={11} className="mt-0.5 shrink-0" aria-hidden="true" />
        <span className="min-w-0">
          <Rich parts={data.bullet} hlClass="text-[#6ecbf5]" />
        </span>
      </div>
      <div className="mt-2.5 rounded-md border border-white/15 px-2 py-1.5">
        <div className="flex gap-1.5 text-[10px] text-white/80">
          <span className="text-[#7ee081]">✓</span>
          <b className="font-semibold">{data.tool.name}</b>
          <span className="text-white/55">{data.tool.desc}</span>
        </div>
        <div className="mt-1.5 rounded bg-white/[.03] px-1.5 py-1 text-[10px] text-[#e8b34d]">{data.code}</div>
      </div>
      <div className="mt-2.5 flex gap-1.5 text-[10px] text-white/50">
        <GeminiCLI.Color size={11} className="mt-0.5 shrink-0" aria-hidden="true" />
        {data.status}
      </div>
      <div className="mt-auto mb-2.5 rounded-md border border-white/15 px-2.5 py-1.5 text-[10px] text-white/30">
        &gt; Type your message or @path/to/file
      </div>
      <div className="flex items-center justify-between gap-2 border-t border-white/10 pt-2 text-[9px] text-white/30">
        <span className="truncate">~/orb-xr</span>
        <span className="shrink-0 text-[#e58bd0]">no sandbox (see /docs)</span>
      </div>
    </TerminalWindow>
  );
}

export function SteeringDemo({ mobile = false }: { mobile?: boolean }) {
  const [scenarioId, setScenarioId] = useState(steeringScenarios[0].id);
  const scenario = steeringScenarios.find((item) => item.id === scenarioId) ?? steeringScenarios[0];

  return (
    <div className={cn("flex h-full flex-col gap-3", mobile ? "px-2 py-3" : "px-3 py-4")}>
      <div className="flex flex-wrap justify-center gap-1 rounded-full bg-white/[.04] p-1 ring-1 ring-white/5">
        {steeringScenarios.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => setScenarioId(item.id)}
            className={cn(
              "rounded-full px-3 py-1.5 text-xs transition",
              scenario.id === item.id ? "bg-white/10 text-primary" : "text-primary/40 hover:text-primary/70",
            )}
          >
            {item.label}
          </button>
        ))}
      </div>
      <p className="text-center text-[12px] text-primary/55">
        <b className="font-semibold text-primary">claude</b> is idle, <b className="font-semibold text-primary">gemini</b>{" "}
        is still working, and <b className="font-semibold text-primary">codex</b> is the one waiting on you — type{" "}
        <b className="font-semibold text-primary">&quot;{scenario.codex.steer}&quot;</b> and look away again.
      </p>
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-3 lg:gap-4">
        <ClaudePane data={scenario.claude} />
        <CodexPane data={scenario.codex} />
        <GeminiPane data={scenario.gemini} />
      </div>
      <p className="hidden text-center text-[11px] text-primary/40 lg:block">
        The amber ring is the one that needs you — everything else keeps running in the periphery.
      </p>
    </div>
  );
}

const setups = [
  {
    name: "Monitor + tabs",
    detail: "One pane in focus. Every other agent lives behind a tab you have to remember to check.",
    panes: 6,
    visible: 1,
    note: "1 in view",
  },
  {
    name: "tmux splits",
    detail: "Four splits in, every terminal is a postage stamp — and TUIs start to wrap and break.",
    panes: 4,
    visible: 4,
    cramped: true,
    note: "4 splits, shrinking",
  },
  {
    name: "Orb XR (XR)",
    detail: "Six full-size panes in a curved arc at reading distance. All of them glanceable at once.",
    panes: 6,
    visible: 6,
    highlight: true,
    note: "6 in view, full size",
  },
];

export function LeaderboardDemo() {
  return (
    <div className="flex h-[440px] flex-col justify-center px-8">
      <div className="rounded-[26px] border border-white/[.06] bg-white/[.025] p-6 shadow-2xl shadow-black/25">
        <div className="mb-6">
          <div className="font-display text-xl">The same six agents, three ways</div>
          <div className="mt-1 text-xs text-primary/40">What your setup actually lets you see at once</div>
        </div>
        <div className="space-y-4">
          {setups.map((setup) => (
            <div key={setup.name}>
              <div className="mb-2 flex items-center justify-between gap-3 text-xs">
                <span className={setup.highlight ? "font-medium text-primary" : "text-primary/55"}>{setup.name}</span>
                <span className={cn("font-mono text-[10px]", setup.highlight ? "text-sunset" : "text-primary/40")}>
                  {setup.note}
                </span>
              </div>
              <div className="flex h-9 items-stretch gap-1.5">
                {Array.from({ length: setup.panes }, (_, index) => {
                  const inView = index < setup.visible;
                  return (
                    <span
                      key={index}
                      className={cn(
                        "flex-1 rounded-md ring-1 ring-inset transition",
                        setup.highlight
                          ? "bg-[linear-gradient(120deg,rgba(95,70,232,.55),rgba(188,76,230,.45),rgba(255,139,95,.4))] ring-white/20"
                          : inView
                            ? "bg-white/[.14] ring-white/[.14]"
                            : "bg-white/[.03] ring-white/[.06]",
                        setup.cramped && "max-w-8 self-end",
                      )}
                      style={setup.cramped ? { height: `${100 - index * 16}%` } : undefined}
                    />
                  );
                })}
              </div>
              <p className="mt-1.5 text-[11px] leading-relaxed text-primary/40">{setup.detail}</p>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

const connectors = [
  { id: "claude", mark: <ClaudeCode.Color size={28} aria-hidden="true" />, name: "Claude Code", x: 82, y: 88, path: "M 212 142 H 176 V 88 H 110" },
  { id: "codex", mark: <Codex.Color size={28} aria-hidden="true" />, name: "Codex", x: 70, y: 190, path: "M 212 190 H 98" },
  { id: "gemini", mark: <GeminiCLI.Color size={28} aria-hidden="true" />, name: "Gemini CLI", x: 466, y: 88, path: "M 348 142 H 394 V 88 H 438" },
  { id: "aider", mark: <span className="font-mono text-[11px] font-semibold tracking-tight text-emerald-300">Aider</span>, name: "Aider", x: 86, y: 292, path: "M 212 238 H 160 V 292 H 114" },
  { id: "build", mark: <span className="font-display text-xl font-medium text-amber-300">⚙</span>, name: "Build", x: 478, y: 190, path: "M 348 190 H 450" },
  { id: "shell", mark: <span className="font-display text-xl font-medium text-sky-400">$</span>, name: "Shell", x: 474, y: 292, path: "M 348 238 H 412 V 292 H 446" },
];

export function IntegrationsDemo() {
  return (
    <div className="flex min-h-[360px] size-full items-center justify-center">
      <ScaledStage width={560} height={380}>
        <svg className="absolute inset-0 size-full text-violet-300/55" viewBox="0 0 560 380" fill="none">
          {connectors.map((connector, index) => (
            <path
              key={connector.id}
              d={connector.path}
              stroke="currentColor"
              strokeWidth="1.5"
              strokeDasharray="5 5"
              className="animate-[dash-flow_1.4s_linear_infinite]"
              style={{ animationDelay: `${index * 50}ms` }}
            />
          ))}
        </svg>
        <div className="absolute left-[176px] top-[86px] z-20 animate-[agent-orb-float_7s_ease-in-out_infinite] [--orb-float-y:-9px]">
          <AgentOrb
            seed="orb-host-mac"
            palette={{ anchor: "#101B2E", accents: ["#7DBED4", "#6E8BFF", "#A24DFF"] }}
            archetype="spiral"
            size={208}
          />
        </div>
        {connectors.map((connector, index) => (
          <div
            key={connector.id}
            title={connector.name}
            className="absolute z-30 flex size-14 animate-[agent-orb-enter_.7s_cubic-bezier(.22,1,.36,1)_both] items-center justify-center rounded-2xl border border-white/[.08] bg-white/[.06] shadow-[0_18px_44px_-14px_rgba(0,0,0,.72)]"
            style={{ left: connector.x - 28, top: connector.y - 28, animationDelay: `${300 + index * 50}ms` }}
          >
            {connector.mark}
          </div>
        ))}
      </ScaledStage>
    </div>
  );
}

const roomFeatures = [
  "Real ANSI + true color",
  "Kitty keyboard protocol",
  "QR pairing in seconds",
  "libssh2 over your LAN",
  "Agents keep running when you take it off",
  "Dark skybox focus mode",
  "Physical keyboard, controller, or accessory keys",
  "Nothing leaves your Mac",
  "Curved arc layout at reading distance",
  "Per-pane PTYs, not screen mirrors",
  "arm64-native on Galaxy XR",
  "Session persistence",
  "Passthrough when you want your room back",
  "Spawn a pane in one gesture",
  "Zig-built native core",
  "Palm-up voice pet for spoken steers",
  "Prayer hold locks the layout",
  "Frame-stable, comfort-first rendering",
];

export function EnterpriseDemo() {
  const [offset, setOffset] = useState(0);
  useEffect(() => {
    const interval = window.setInterval(() => setOffset((value) => (value + 1) % roomFeatures.length), 1800);
    return () => window.clearInterval(interval);
  }, []);
  const bubbles = [
    { left: 22, top: 66, tail: "br" as const, feature: roomFeatures[offset] },
    { left: 346, top: 32, tail: "bl" as const, feature: roomFeatures[(offset + 1) % roomFeatures.length] },
    { left: 334, top: 294, tail: "tl" as const, feature: roomFeatures[(offset + 2) % roomFeatures.length] },
  ];

  return (
    <div className="flex min-h-[360px] size-full items-center justify-center">
      <ScaledStage width={560} height={380}>
        <div className="absolute left-[150px] top-[60px] animate-[agent-orb-float_7s_ease-in-out_infinite] [--orb-float-y:-9px]">
          <AgentOrb
            seed="terminal-orb"
            palette={{ anchor: "#1C0A2B", accents: ["#A24DFF", "#FF4DD8", "#6E8BFF"] }}
            archetype="nebula"
            size={260}
          />
        </div>
        {bubbles.map((bubble, index) => (
          <div key={`${bubble.feature}-${index}`} className="absolute z-20" style={{ left: bubble.left, top: bubble.top }}>
            <PopBubble text={bubble.feature} tail={bubble.tail} delay={index * 120} className="max-w-[248px]" />
          </div>
        ))}
      </ScaledStage>
    </div>
  );
}
