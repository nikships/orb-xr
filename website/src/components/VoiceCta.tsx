"use client";

import { useEffect, useRef, useState } from "react";
import { AgentOrb } from "@/components/voice/AgentOrb";
import { PopBubble, readingTimeMs } from "@/components/voice/GlassBubble";
import { ctaPalette } from "@/data/voice";
import { cn } from "@/lib/utils";

const messages = [
  "Put the headset on. Your agents are already working.",
  "What do you want to orchestrate?",
];

const presets = [
  { id: "claude-code", label: "Claude Code" },
  { id: "codex", label: "Codex" },
  { id: "gemini-cli", label: "Gemini CLI" },
  { id: "plain-shell", label: "Plain shell" },
];

export function VoiceCta() {
  const sectionRef = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const [step, setStep] = useState(0);
  const [orbSize, setOrbSize] = useState(220);

  useEffect(() => {
    const section = sectionRef.current;
    if (!section) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setVisible(true);
          observer.disconnect();
        }
      },
      { threshold: 0.4 },
    );
    observer.observe(section);
    const resize = new ResizeObserver(() => {
      setOrbSize(Math.round(Math.min(264, Math.max(180, section.clientWidth * 0.3))));
    });
    resize.observe(section);
    return () => {
      observer.disconnect();
      resize.disconnect();
    };
  }, []);

  useEffect(() => {
    if (!visible || step >= messages.length + 1) return;
    const previous = step > 0 ? messages[step - 1] : null;
    const timeout = window.setTimeout(
      () => setStep((value) => value + 1),
      previous ? Math.min(1200, Math.round(readingTimeMs(previous) * 0.55)) : 350,
    );
    return () => window.clearTimeout(timeout);
  }, [step, visible]);

  return (
    <section className="border-t border-border py-20 sm:py-28">
      <div className="mx-auto w-full max-w-7xl px-4 lg:px-6">
        <div ref={sectionRef} className="relative mx-auto max-w-4xl">
          <div
            className={cn(
              "pointer-events-none absolute left-0 top-1/2 z-0 -translate-y-1/2 transition-transform duration-700 ease-[cubic-bezier(.22,1,.36,1)]",
              visible ? "scale-100" : "scale-0",
            )}
            style={{ width: orbSize, height: orbSize }}
          >
            <div className="animate-[agent-orb-float_7s_ease-in-out_infinite] [--orb-float-y:-10px]">
              <AgentOrb seed="cta" palette={ctaPalette} archetype="core" size={orbSize} />
            </div>
          </div>
          <div
            className="relative z-10 flex h-[24rem] flex-col justify-center gap-3 sm:h-72"
            style={{ paddingLeft: orbSize * 0.62 }}
          >
            <PopBubble text={messages[0]} show={step > 0} tail="bl" size="md" className="max-w-2xl" />
            <PopBubble text={messages[1]} show={step > 1} tail="bl" size="md" className="max-w-2xl" />
            <div
              aria-hidden={step <= messages.length}
              className={cn(
                "mt-3 flex flex-col items-end gap-2.5 transition duration-400 ease-[cubic-bezier(.34,1.56,.64,1)] sm:flex-row sm:flex-wrap sm:justify-end",
                step > messages.length ? "translate-y-0 opacity-100" : "pointer-events-none translate-y-3 opacity-0",
              )}
            >
              {presets.map((preset) => (
                <span
                  key={preset.id}
                  className="inline-flex h-8 items-center justify-center rounded-full border border-white/15 bg-background px-3.5 text-xs text-primary/80"
                >
                  {preset.label}
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
