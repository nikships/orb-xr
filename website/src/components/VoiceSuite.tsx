"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowRight, Braces, Cpu, Glasses, Laptop, Layers, Orbit, QrCode, SquareTerminal, Wifi } from "lucide-react";
import type { ComponentType, SVGProps } from "react";
import { cn } from "@/lib/utils";

type IconComponent = ComponentType<SVGProps<SVGSVGElement>>;

interface SuiteItem {
  title: string;
  description: string;
  price: string;
  from: IconComponent;
  to: IconComponent;
}

const suite: SuiteItem[] = [
  {
    title: "libghostty",
    description: "The terminal core behind Ghostty, rendering every pane natively on the headset.",
    price: "Real ANSI · Kitty protocol · true color",
    from: SquareTerminal,
    to: Layers,
  },
  {
    title: "libssh2",
    description: "Your Mac's shells, carried to the headset over your LAN.",
    price: "macOS built-in SSH server · no cloud relay",
    from: Laptop,
    to: Glasses,
  },
  {
    title: "Compose for XR",
    description: "Spatial panes arranged in a curved arc at reading distance.",
    price: "Jetpack XR · Kotlin + Compose",
    from: Layers,
    to: Orbit,
  },
  {
    title: "Zig native core",
    description: "libghostty and libssh2 compiled into a single JNI library.",
    price: "arm64-v8a · one ~28 MB .so",
    from: Braces,
    to: Cpu,
  },
  {
    title: "Mac companion",
    description: "Menu-bar app that shows the QR code the headset pairs with.",
    price: "SwiftUI · optional",
    from: QrCode,
    to: Wifi,
  },
];

export function VoiceSuite() {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [visible, setVisible] = useState(false);
  const words = "Explore the Orb XR stack".split(" ");

  useEffect(() => {
    const heading = headingRef.current;
    if (!heading) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setVisible(true);
          observer.disconnect();
        }
      },
      { threshold: 0.4 },
    );
    observer.observe(heading);
    return () => observer.disconnect();
  }, []);

  return (
    <section className="border-t border-border py-16">
      <div className="mx-auto w-full max-w-7xl px-4 lg:px-6">
        <h2 ref={headingRef} className="text-center font-display text-3xl tracking-tight [perspective:1200px]">
          {words.map((word, index) => (
            <span
              key={word}
              className={cn(
                "mr-[.24em] inline-block origin-bottom transition duration-700 ease-[cubic-bezier(.22,1,.36,1)] last:mr-0",
                visible ? "translate-y-0 rotate-x-0 opacity-100" : "translate-y-4 rotate-x-[-70deg] opacity-0",
              )}
              style={{ transitionDelay: `${index * 55}ms` }}
            >
              {word}
            </span>
          ))}
        </h2>
        <p className="mx-auto mt-4 max-w-xl text-center text-sm leading-relaxed text-primary/50">
          Five honest pieces of engineering — no web views, no VNC, no cloud in the middle.
        </p>
        <div className="mt-10 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {suite.map((item) => {
            const FromIcon = item.from;
            const ToIcon = item.to;
            return (
              <div
                key={item.title}
                className="flex min-h-52 flex-col rounded-[1.75rem] bg-card p-5"
              >
                <div className="flex items-center gap-2 text-primary/40">
                  <FromIcon className="size-5 sm:size-6" strokeWidth={1.75} />
                  <ArrowRight className="size-3.5 opacity-70" strokeWidth={1.75} />
                  <ToIcon className="size-5 sm:size-6" strokeWidth={1.75} />
                </div>
                <h3 className="mt-6 font-display text-base tracking-tight">
                  {item.title}
                </h3>
                <p className="mt-2 text-xs leading-relaxed text-primary/50 sm:text-sm">{item.description}</p>
                <p className="mt-auto pt-6 text-xs text-primary/60">{item.price}</p>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
