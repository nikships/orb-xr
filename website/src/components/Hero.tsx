"use client";

import Image from "next/image";
import { CheckIcon, GridIcon } from "@/components/icons";

const firstLine = "Supervise a fleet of coding agents";
const secondLine = "across your whole field of view";
const benefits = [
  "Native terminal rendering",
  "Real SSH to your Mac",
  "6 agents in view at once",
  "Passthrough or focus mode",
];

function AnimatedWords({ text, muted = false, offset = 0 }: { text: string; muted?: boolean; offset?: number }) {
  return (
    <span className={muted ? "text-[#7d8187]" : "text-primary"}>
      {text.split(" ").map((word, index) => (
        <span key={`${word}-${index}`} className="inline-block overflow-hidden align-bottom">
          <span
            className="inline-block animate-[hero-word-in_650ms_cubic-bezier(.22,1,.36,1)_both] [transform-origin:50%_100%]"
            style={{ animationDelay: `${(index + offset) * 55}ms` }}
          >
            {word}
          </span>
          {index < text.split(" ").length - 1 ? "\u00a0" : ""}
        </span>
      ))}
    </span>
  );
}

export function Hero() {
  return (
    <section className="relative pb-16 pt-32 sm:pb-24 sm:pt-40 lg:pt-48">
      <div className="mx-auto w-full max-w-7xl px-4 text-center lg:px-6">
        <div className="mb-4 flex flex-wrap items-center justify-center gap-1.5">
          <GridIcon className="size-5 text-primary/40" />
          <span className="text-sm font-medium text-primary/40">Orb XR for Android XR</span>
          <span className="rounded-full border border-sunset/30 px-2 py-0.5 text-[10px] font-medium text-sunset">
            Beta
          </span>
        </div>

        <h1 className="mx-auto max-w-5xl font-display text-4xl font-medium leading-[1.1] tracking-tight [perspective:1000px] sm:text-5xl lg:text-6xl lg:tracking-[-.025em]">
          <span className="block">
            <AnimatedWords text={firstLine} />
          </span>
          <span className="block">
            <AnimatedWords text={secondLine} muted offset={firstLine.split(" ").length} />
          </span>
        </h1>

        <p className="mx-auto mt-6 max-w-xl text-lg leading-relaxed text-primary/50">
          Floating SSH terminals in 3D space — one seat per agent. Your Mac does the work. Scan a QR and
          you&apos;re in.
        </p>

        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <a
            href="https://play.google.com/store/apps/details?id=com.ghosttyxr.app"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex h-11 items-center justify-center rounded-full bg-primary px-5 text-sm font-medium text-primary-foreground transition hover:bg-white"
          >
            Get it on Google Play
          </a>
        </div>

        <div className="mt-8 flex flex-wrap justify-center gap-x-4 gap-y-2 text-xs text-primary/40">
          {benefits.map((benefit) => (
            <span key={benefit} className="flex items-center gap-1.5">
              <CheckIcon className="size-3 shrink-0 text-primary/30" />
              {benefit}
            </span>
          ))}
        </div>

        <div className="mx-auto mt-14 max-w-5xl animate-[agent-orb-enter_.8s_cubic-bezier(.22,1,.36,1)_both] [animation-delay:400ms] sm:mt-20">
          <div className="overflow-hidden rounded-[1.75rem] shadow-[0_60px_160px_-60px_rgba(56,225,255,.35)] ring-1 ring-inset ring-white/10">
            <Image
              src="/images/orbxr/cockpit.png"
              alt="Orb XR: six terminal panes floating in XR — six agents, one field of view"
              width={1920}
              height={1200}
              priority
              className="w-full"
            />
          </div>
        </div>
      </div>
    </section>
  );
}
