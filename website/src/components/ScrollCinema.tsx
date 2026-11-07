"use client";

import { useLayoutEffect, useRef } from "react";
import Image from "next/image";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { AgentOrb } from "@/components/voice/AgentOrb";
import { MacBook } from "@/components/MacBook";
import { TerminalWindow } from "@/components/TerminalWindow";
import { cn } from "@/lib/utils";

gsap.registerPlugin(ScrollTrigger);

/* ------------------------------------------------------------------ */
/* Mini terminal panes that fly out of the MacBook                     */
/* ------------------------------------------------------------------ */

type MiniLine = { text: string; className: string };

const miniPanes: Array<{
  id: string;
  title: string;
  body: MiniLine[];
  bodyBg: string;
  /** final resting position, % of stage */
  left: string;
  top: string;
  rotate: number;
}> = [
  {
    id: "claude",
    title: "claude — ~/repos/gemdex",
    bodyBg: "bg-[#0b1f1c]",
    body: [
      { text: "> tighten the reconnect loop", className: "text-[#f3f3f1]/80" },
      { text: "✓ typecheck clean · 48 tests passing", className: "text-[#7fd7c4]" },
    ],
    left: "0.5%",
    top: "44%",
    rotate: -9,
  },
  {
    id: "codex",
    title: "codex — ~/code/orb-xr",
    bodyBg: "bg-black",
    body: [
      { text: "▸ run the auth suite before commit", className: "text-white/80" },
      { text: "· 12 of 14 passing — fixing tab-stop", className: "text-amber-300/90" },
    ],
    left: "7%",
    top: "22%",
    rotate: -6,
  },
  {
    id: "gemini",
    title: "gemini — zsh — 100×32",
    bodyBg: "bg-black",
    body: [
      { text: "✦ drafting the migration note", className: "text-[#a06bea]" },
      { text: "✓ WriteFile docs/migrations/token.md", className: "text-white/60" },
    ],
    left: "17.5%",
    top: "7%",
    rotate: -3,
  },
  {
    id: "aider",
    title: "aider — feat/auth",
    bodyBg: "bg-[#0d1117]",
    body: [
      { text: "+ applied 3 edits to auth/session.ts", className: "text-emerald-300/90" },
      { text: "commit? (y/n)", className: "text-white/60" },
    ],
    left: "66%",
    top: "7%",
    rotate: 3,
  },
  {
    id: "build",
    title: "gradlew — assembleRelease",
    bodyBg: "bg-black",
    body: [
      { text: "> Task :app:assembleRelease", className: "text-white/60" },
      { text: "BUILD SUCCESSFUL in 3m 41s", className: "text-[#7ee081]" },
    ],
    left: "76.5%",
    top: "22%",
    rotate: 6,
  },
  {
    id: "shell",
    title: "zsh — ~/dotfiles",
    bodyBg: "bg-[#101014]",
    body: [
      { text: "$ git push origin main", className: "text-sky-300/90" },
      { text: "main → main · done", className: "text-white/55" },
    ],
    left: "84.5%",
    top: "44%",
    rotate: 9,
  },
];

function MiniPane({ pane, className }: { pane: (typeof miniPanes)[number]; className?: string }) {
  return (
    <TerminalWindow
      title={pane.title}
      className={cn("w-full", className)}
      chromeClassName="px-2.5 py-1.5"
      bodyClassName={cn("gap-1 px-3 py-2.5 text-[10px] leading-relaxed", pane.bodyBg)}
    >
      {pane.body.map((line) => (
        <div key={line.text} className={cn("truncate", line.className)}>
          {line.text}
        </div>
      ))}
    </TerminalWindow>
  );
}

/** The "desktop" rendered inside the MacBook's screen — six cramped panes. */
function MacScreenContent() {
  return (
    <div className="flex size-full flex-col bg-[#0b0b0d] p-2">
      <div className="mb-1.5 flex items-center justify-between rounded bg-white/[.04] px-2 py-1">
        <span className="font-mono text-[7px] text-white/55">Orb XR — 6 sessions</span>
        <span className="font-mono text-[7px] text-white/25">macbook.local</span>
      </div>
      <div className="grid min-h-0 flex-1 grid-cols-3 grid-rows-2 gap-1.5">
        {miniPanes.map((pane) => (
          <div
            key={pane.id}
            data-mac-screen-pane
            className={cn("flex min-h-0 flex-col overflow-hidden rounded-md ring-1 ring-white/[.12]", pane.bodyBg)}
          >
            <div className="flex items-center gap-1 bg-white/[.06] px-1.5 py-[3px]">
              <span className="size-[3px] rounded-full bg-[#ec6a5f]" />
              <span className="size-[3px] rounded-full bg-[#f4bf4f]" />
              <span className="size-[3px] rounded-full bg-[#61c454]" />
              <span className="ml-0.5 truncate font-sans text-[6px] font-medium text-white/40">{pane.title}</span>
            </div>
            <div className="flex-1 space-y-0.5 px-1.5 py-1">
              {pane.body.map((line) => (
                <div key={line.text} className={cn("truncate font-mono text-[6px] leading-snug", line.className)}>
                  {line.text}
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Captions                                                            */
/* ------------------------------------------------------------------ */

const captions = [
  {
    id: "mac",
    title: "Your Mac does the work",
    body: "Every shell, agent, toolchain, and repo stays on the machine that already has them. The headset is a thin client over your LAN.",
  },
  {
    id: "lift",
    title: "The room becomes the monitor",
    body: "Panes lift off the laptop and settle into a curved arc at reading distance — one seat per agent, every one a real PTY.",
  },
  {
    id: "room",
    title: "Put the room on",
    body: "Galaxy XR turns your whole field of view into terminal real estate. Six agents, glanceable at once, rendered natively on the headset.",
  },
  {
    id: "pet",
    title: "Palm up. That's the interface.",
    body: "Hold a palm up and the orb floats in, listening. Speak, flip your palm down, and your voice steers the focused terminal.",
  },
];

/* ------------------------------------------------------------------ */
/* The pinned cinema                                                   */
/* ------------------------------------------------------------------ */

export function ScrollCinema() {
  const sectionRef = useRef<HTMLElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const section = sectionRef.current;
    const stage = stageRef.current;
    if (!section || !stage) return;

    const q = gsap.utils.selector(stage);
    const ctx = gsap.context(() => {
      const mm = gsap.matchMedia();

      /* Desktop: pinned, scroll-scrubbed cinema */
      mm.add("(min-width: 768px) and (prefers-reduced-motion: no-preference)", () => {
        const terminals = q<HTMLDivElement>("[data-cinema-pane]");
        const mac = q<HTMLDivElement>("[data-cinema-mac]")[0];
        const body = q<HTMLDivElement>("[data-mac-body]")[0];
        const lid = q<HTMLDivElement>("[data-mac-lid]")[0];
        const screen = q<HTMLDivElement>("[data-mac-screen]")[0];
        const screenPanes = q<HTMLDivElement>("[data-mac-screen-pane]");
        const headset = q<HTMLDivElement>("[data-cinema-headset]")[0];
        const hand = q<HTMLDivElement>("[data-cinema-hand]")[0];
        const orb = q<HTMLDivElement>("[data-cinema-orb]")[0];
        const caps = q<HTMLDivElement>("[data-cinema-caption]");

        const macCenter = () => {
          const m = screen.getBoundingClientRect();
          return { x: m.left + m.width / 2, y: m.top + m.height * 0.5 };
        };

        const tl = gsap.timeline({
          defaults: { ease: "none" },
          scrollTrigger: {
            trigger: section,
            start: "top top",
            end: "+=3200",
            scrub: 1,
            pin: true,
            anticipatePin: 1,
            invalidateOnRefresh: true,
          },
        });

        gsap.set([headset, hand, orb], { autoAlpha: 0 });
        gsap.set(caps, { autoAlpha: 0, y: 28 });
        gsap.set(caps[0], { autoAlpha: 1, y: 0 });
        /* Closed clamshell: lid folded flat over the deck (back cover up),
           whole machine tipped back as if seen from above. */
        gsap.set(body, { rotateX: 30 });
        gsap.set(lid, { rotateX: -178 });
        gsap.set(screenPanes, { autoAlpha: 0, scale: 0.7 });

        /* Stage 0 — the Mac sits up and opens */
        tl.fromTo(mac, { scale: 0.94, yPercent: 6 }, { scale: 1, yPercent: 0, duration: 0.12 }, 0);
        tl.to(body, { rotateX: 0, duration: 0.15, ease: "power2.inOut" }, 0.01);
        tl.to(lid, { rotateX: 0, duration: 0.15, ease: "power2.inOut" }, 0.01);
        tl.to(
          screenPanes,
          { autoAlpha: 1, scale: 1, duration: 0.05, stagger: 0.012, ease: "back.out(1.4)" },
          0.1,
        );
        tl.to(caps[0], { autoAlpha: 0, y: -24, duration: 0.06 }, 0.15);

        /* Stage 1 — terminals fly out of the screen into the arc */
        terminals.forEach((terminal, index) => {
          tl.fromTo(
            terminal,
            {
              x: () => {
                const c = macCenter();
                const t = terminal.getBoundingClientRect();
                return c.x - (t.left + t.width / 2);
              },
              y: () => {
                const c = macCenter();
                const t = terminal.getBoundingClientRect();
                return c.y - (t.top + t.height / 2);
              },
              scale: 0.12,
              autoAlpha: 0,
              rotate: 0,
            },
            {
              x: 0,
              y: 0,
              scale: 1,
              autoAlpha: 1,
              rotate: miniPanes[index].rotate,
              duration: 0.24,
              ease: "power2.out",
            },
            0.18 + index * 0.04,
          );
        });
        tl.to(screenPanes, { autoAlpha: 0.12, duration: 0.15 }, 0.2);
        tl.to(mac, { scale: 0.82, yPercent: 10, autoAlpha: 0.5, duration: 0.3 }, 0.22);
        tl.to(caps[1], { autoAlpha: 1, y: 0, duration: 0.07 }, 0.26);
        tl.to(caps[1], { autoAlpha: 0, y: -24, duration: 0.06 }, 0.48);

        /* Stage 2 — the headset arrives */
        tl.fromTo(
          headset,
          { scale: 0.55, yPercent: 18, autoAlpha: 0 },
          { scale: 1, yPercent: 0, autoAlpha: 1, duration: 0.16, ease: "power2.out" },
          0.55,
        );
        tl.to(caps[2], { autoAlpha: 1, y: 0, duration: 0.07 }, 0.64);
        tl.to(caps[2], { autoAlpha: 0, y: -24, duration: 0.06 }, 0.76);

        /* Stage 3 — palm up, the pet floats in */
        tl.to(mac, { autoAlpha: 0.2, duration: 0.12 }, 0.8);
        tl.to(terminals, { autoAlpha: 0.35, duration: 0.12 }, 0.8);
        tl.to(headset, { yPercent: -8, autoAlpha: 0.4, duration: 0.14 }, 0.8);
        tl.fromTo(
          hand,
          { yPercent: 120, autoAlpha: 0 },
          { yPercent: 0, autoAlpha: 1, duration: 0.16, ease: "power2.out" },
          0.82,
        );
        tl.fromTo(
          orb,
          { scale: 0.2, yPercent: 40, autoAlpha: 0 },
          { scale: 1, yPercent: 0, autoAlpha: 1, duration: 0.12, ease: "back.out(1.6)" },
          0.9,
        );
        tl.to(caps[3], { autoAlpha: 1, y: 0, duration: 0.08 }, 0.92);
        /* breathing room at the end of the pin */
        tl.to({}, { duration: 0.08 }, 1.0);
      });

      /* Mobile / reduced motion: gentle reveals, no pin */
      mm.add("(max-width: 767px), (prefers-reduced-motion: reduce)", () => {
        const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        const blocks = q<HTMLDivElement>("[data-cinema-stack] > *");
        if (reduced) {
          gsap.set(blocks, { clearProps: "all" });
          return;
        }
        for (const block of blocks) {
          gsap.fromTo(
            block,
            { autoAlpha: 0, y: 34 },
            {
              autoAlpha: 1,
              y: 0,
              duration: 0.7,
              ease: "power2.out",
              scrollTrigger: { trigger: block, start: "top 82%" },
            },
          );
        }
      });

      return () => mm.revert();
    }, section);

    return () => ctx.revert();
  }, []);

  return (
    <section ref={sectionRef} aria-label="From your Mac to Orb XR" className="relative border-t border-border">
      {/* ---------------- Desktop pinned stage ---------------- */}
      <div ref={stageRef} className="relative hidden h-screen overflow-hidden md:block">
        <div className="pointer-events-none absolute inset-x-0 top-0 h-40 bg-[radial-gradient(60%_120%_at_50%_0%,rgba(56,225,255,.06),transparent)]" />

        {/* captions */}
        {captions.map((caption) => (
          <div
            key={caption.id}
            data-cinema-caption
            className="absolute left-1/2 top-28 z-30 w-full max-w-xl -translate-x-1/2 px-6 text-center opacity-0"
          >
            <h2 className="font-display text-3xl tracking-tight lg:text-4xl">{caption.title}</h2>
            <p className="mx-auto mt-3 max-w-lg text-sm leading-relaxed text-primary/55">{caption.body}</p>
          </div>
        ))}

        {/* MacBook — a real DOM MacBook Pro whose lid GSAP opens */}
        <div data-cinema-mac className="absolute bottom-[-4%] left-1/2 z-10 -translate-x-1/2">
          <div className="origin-bottom scale-[0.78] drop-shadow-[0_40px_80px_rgba(56,225,255,.10)] lg:scale-[0.88]">
            <MacBook>
              <MacScreenContent />
            </MacBook>
          </div>
        </div>

        {/* terminals in their final arc positions */}
        {miniPanes.map((pane) => (
          <div
            key={pane.id}
            data-cinema-pane
            className="absolute z-20 w-48 opacity-0 lg:w-56"
            style={{ left: pane.left, top: pane.top }}
          >
            <MiniPane pane={pane} />
          </div>
        ))}

        {/* Galaxy XR headset */}
        <div
          data-cinema-headset
          className="absolute left-1/2 top-[30%] z-20 w-[340px] -translate-x-1/2 opacity-0 lg:w-[400px]"
        >
          <div className="animate-[agent-orb-float_8s_ease-in-out_infinite] [--orb-float-y:-12px]">
            <Image
              src="/images/products/samsung-galaxy-xr.webp"
              alt="Samsung Galaxy XR headset"
              width={1400}
              height={819}
              className="w-full drop-shadow-[0_50px_110px_rgba(162,77,255,.22)]"
            />
          </div>
        </div>

        {/* palm + voice pet */}
        <div data-cinema-hand className="absolute -right-6 bottom-0 z-30 w-[360px] opacity-0 lg:w-[420px]">
          <Image
            src="/images/products/palm-hand.webp"
            alt="Open palm held up to summon the voice pet"
            width={1400}
            height={1002}
            className="w-full"
          />
        </div>
        <div data-cinema-orb className="absolute bottom-[44%] right-[7%] z-40 opacity-0 lg:right-[9%]">
          <div className="relative animate-[agent-orb-float_5.5s_ease-in-out_infinite] [--orb-float-y:-14px]">
            <div className="absolute left-1/2 top-1/2 size-52 -translate-x-1/2 -translate-y-1/2 animate-[pet-glow_4s_ease-in-out_infinite] rounded-full bg-[radial-gradient(closest-side,rgba(162,77,255,.35),rgba(56,225,255,.12),transparent)]" />
            {[0, 1, 2, 3, 4].map((particle) => (
              <span
                key={particle}
                className="absolute left-1/2 top-1/2 size-1 rounded-full bg-violet-300/80 animate-[pet-particle_3.2s_ease-in_infinite]"
                style={{
                  animationDelay: `${particle * 0.64}s`,
                  marginLeft: `${[-38, 26, -12, 44, 6][particle]}px`,
                }}
              />
            ))}
            <AgentOrb
              seed="scroll-pet"
              palette={{ anchor: "#1C0A2B", accents: ["#A24DFF", "#FF4DD8", "#6E8BFF"] }}
              archetype="nebula"
              size={132}
            />
          </div>
        </div>
      </div>

      {/* ---------------- Mobile stacked narrative ---------------- */}
      <div data-cinema-stack className="mx-auto w-full max-w-7xl space-y-12 px-4 py-16 md:hidden">
        <div className="text-center">
          <h2 className="font-display text-3xl tracking-tight">{captions[0].title}</h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-primary/55">{captions[0].body}</p>
        </div>
        <div className="flex h-[26rem] justify-center overflow-hidden">
          <div className="origin-top scale-[0.62]">
            <MacBook>
              <MacScreenContent />
            </MacBook>
          </div>
        </div>
        <div className="text-center">
          <h2 className="font-display text-3xl tracking-tight">{captions[1].title}</h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-primary/55">{captions[1].body}</p>
        </div>
        <div className="space-y-3">
          {miniPanes.slice(0, 3).map((pane, index) => (
            <div key={pane.id} className={cn(index === 1 ? "translate-x-4" : index === 2 ? "-translate-x-4" : "")}>
              <MiniPane pane={pane} />
            </div>
          ))}
        </div>
        <div className="text-center">
          <h2 className="font-display text-3xl tracking-tight">{captions[2].title}</h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-primary/55">{captions[2].body}</p>
        </div>
        <Image
          src="/images/products/samsung-galaxy-xr.webp"
          alt="Samsung Galaxy XR headset"
          width={1400}
          height={819}
          className="mx-auto w-full max-w-xs drop-shadow-[0_50px_110px_rgba(162,77,255,.22)]"
        />
        <div className="text-center">
          <h2 className="font-display text-3xl tracking-tight">{captions[3].title}</h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-primary/55">{captions[3].body}</p>
        </div>
        <div className="relative mx-auto w-full max-w-xs">
          <Image
            src="/images/products/palm-hand.webp"
            alt="Open palm held up to summon the voice pet"
            width={1400}
            height={1002}
            className="w-full"
          />
          <div className="absolute -top-10 left-1/2 -translate-x-1/2">
            <div className="relative animate-[agent-orb-float_5.5s_ease-in-out_infinite] [--orb-float-y:-10px]">
              <div className="absolute left-1/2 top-1/2 size-36 -translate-x-1/2 -translate-y-1/2 rounded-full bg-[radial-gradient(closest-side,rgba(162,77,255,.35),rgba(56,225,255,.12),transparent)]" />
              <AgentOrb
                seed="scroll-pet"
                palette={{ anchor: "#1C0A2B", accents: ["#A24DFF", "#FF4DD8", "#6E8BFF"] }}
                archetype="nebula"
                size={96}
              />
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
