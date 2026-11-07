"use client";

import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import { CheckIcon } from "@/components/icons";
import { features } from "@/data/voice";
import { cn } from "@/lib/utils";
import {
  EnterpriseDemo,
  IntegrationsDemo,
  LeaderboardDemo,
  SteeringDemo,
  VoiceShowcase,
} from "@/components/feature-visuals";

const featureIcons = [
  "/images/orbxr/gxr-ic-terminals.png",
  "/images/orbxr/gxr-ic-keyboard.png",
  "/images/orbxr/gxr-ic-space.png",
  "/images/orbxr/gxr-ic-mirror.png",
  "/images/orbxr/gxr-ic-pro.png",
];

function FeatureVisual({ index, mobile = false }: { index: number; mobile?: boolean }) {
  switch (index) {
    case 0:
      return <VoiceShowcase mobile={mobile} />;
    case 1:
      return <SteeringDemo mobile={mobile} />;
    case 2:
      return <LeaderboardDemo />;
    case 3:
      return <IntegrationsDemo />;
    default:
      return <EnterpriseDemo />;
  }
}

export function FeatureRail() {
  const [active, setActive] = useState(0);
  const sections = useRef<Array<HTMLDivElement | null>>([]);

  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (!visible) return;
        const index = Number((visible.target as HTMLElement).dataset.featureIndex);
        if (Number.isFinite(index)) setActive(index);
      },
      { rootMargin: "-35% 0px -45% 0px", threshold: [0, 0.1, 0.4, 0.8] },
    );
    for (const section of sections.current) {
      if (section) observer.observe(section);
    }
    return () => observer.disconnect();
  }, []);

  return (
    <section className="border-t border-border">
      <div className="mx-auto w-full max-w-7xl border-x border-border px-4 py-16 sm:py-24 lg:px-6">
        <div className="lg:grid lg:grid-cols-[1fr_1.3fr] lg:gap-16">
          <div>
            {features.map((feature, index) => {
              const icon = featureIcons[index];
              return (
                <div
                  key={feature.id}
                  ref={(element) => {
                    sections.current[index] = element;
                  }}
                  data-feature-index={index}
                  className="lg:flex lg:min-h-[50vh] lg:flex-col lg:justify-center"
                >
                  <div className={cn("py-10 transition-opacity duration-500 lg:py-12", active === index ? "opacity-100" : "lg:opacity-20")}>
                    <div className="flex items-center gap-2">
                      <Image src={icon} alt="" width={40} height={40} className="-ml-1.5 size-10" />
                      <h2 className="font-display text-2xl tracking-tight sm:text-3xl">{feature.title}</h2>
                    </div>
                    <p className="mt-4 max-w-md text-base leading-relaxed text-primary/55">{feature.description}</p>
                    <ul className="mt-5 space-y-2 text-primary/50">
                      {feature.benefits.map((benefit) => (
                        <li key={benefit} className="flex items-start gap-2.5 text-sm leading-relaxed">
                          <CheckIcon className="mt-0.5 size-4 shrink-0 text-primary/30" />
                          <span>{benefit}</span>
                        </li>
                      ))}
                    </ul>
                    <div className="mt-8 lg:hidden">
                      <div className="min-h-[360px] overflow-hidden rounded-[28px] bg-white/[.015]">
                        <FeatureVisual index={index} mobile />
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          <div className="relative hidden lg:block">
            <div className="sticky top-[calc(50vh-220px)] h-[440px]">
              {features.map((feature, index) => (
                <div
                  key={feature.id}
                  aria-hidden={active !== index}
                  className={cn(
                    "absolute inset-0 transition-opacity duration-500",
                    active === index ? "z-10 opacity-100" : "pointer-events-none opacity-0",
                  )}
                >
                  <FeatureVisual index={index} />
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
