"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { cn } from "@/lib/utils";

const playStore = "https://play.google.com/store/apps/details?id=com.ghosttyxr.app";

const navigation = [
  { label: "Get the app", href: playStore, external: true },
  { label: "Privacy", href: "/privacy/", external: false },
] as const;

export function Header() {
  const [scrolled, setScrolled] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    const update = () => setScrolled(window.scrollY > 20);
    update();
    window.addEventListener("scroll", update, { passive: true });
    return () => window.removeEventListener("scroll", update);
  }, []);

  return (
    <header className="fixed inset-x-0 top-0 z-50 h-16 transition-all duration-200">
      <div
        className={cn(
          "pointer-events-none absolute inset-0 -z-10 border-b transition-all duration-200",
          scrolled
            ? "border-white/10 bg-background/85 backdrop-blur-xl"
            : "border-transparent bg-transparent",
        )}
      />
      <nav className="mx-auto flex h-16 w-full max-w-7xl items-center px-4 lg:px-6" aria-label="Primary navigation">
        <Link href="/" className="flex shrink-0 items-center gap-2 text-primary" aria-label="Orb XR home">
          <Image src="/images/orbxr/orb-mascot.png" alt="" width={28} height={28} className="size-7" />
          <span className="font-display text-[15px] font-medium tracking-tight">Orb XR</span>
        </Link>

        <div className="ml-10 hidden h-full items-center gap-7 lg:flex">
          {navigation.map((item) =>
            item.external ? (
              <a
                key={item.label}
                href={item.href}
                target="_blank"
                rel="noopener noreferrer"
                className="text-[13px] text-primary/70 transition-colors hover:text-primary"
              >
                {item.label}
              </a>
            ) : (
              <Link
                key={item.label}
                href={item.href}
                className="text-[13px] text-primary/70 transition-colors hover:text-primary"
              >
                {item.label}
              </Link>
            ),
          )}
        </div>

        <div className="ml-auto flex items-center gap-2">
          <a
            href={playStore}
            target="_blank"
            rel="noopener noreferrer"
            className="flex h-9 items-center rounded-full bg-primary px-4 text-sm font-medium text-primary-foreground transition hover:bg-white"
          >
            Get the app
          </a>
          <button
            type="button"
            aria-label="Toggle navigation"
            aria-expanded={mobileOpen}
            onClick={() => setMobileOpen((open) => !open)}
            className="flex size-9 items-center justify-center rounded-full border border-white/15 lg:hidden"
          >
            <span className="flex w-4 flex-col gap-1">
              <span className={cn("h-px w-4 bg-white transition", mobileOpen && "translate-y-[2.5px] rotate-45")} />
              <span className={cn("h-px w-4 bg-white transition", mobileOpen && "-translate-y-[2.5px] -rotate-45")} />
            </span>
          </button>
        </div>
      </nav>

      {mobileOpen && (
        <div className="border-b border-white/10 bg-background/95 px-4 pb-5 pt-2 backdrop-blur-xl lg:hidden">
          {navigation.map((item) =>
            item.external ? (
              <a
                key={item.label}
                href={item.href}
                target="_blank"
                rel="noopener noreferrer"
                className="block border-b border-white/5 py-3 text-sm text-primary/75 last:border-0"
                onClick={() => setMobileOpen(false)}
              >
                {item.label}
              </a>
            ) : (
              <Link
                key={item.label}
                href={item.href}
                className="block border-b border-white/5 py-3 text-sm text-primary/75 last:border-0"
                onClick={() => setMobileOpen(false)}
              >
                {item.label}
              </Link>
            ),
          )}
        </div>
      )}
    </header>
  );
}
