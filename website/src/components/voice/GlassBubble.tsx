"use client";

import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";

export type BubbleTail = "tl" | "tr" | "bl" | "br" | "none";
export type BubbleSize = "xs" | "sm" | "md";

interface GlassBubbleProps {
  children: ReactNode;
  tail?: BubbleTail;
  size?: BubbleSize;
  className?: string;
  contentClassName?: string;
}

const tailClasses: Record<BubbleTail, string> = {
  tl: "rounded-tl-md",
  tr: "rounded-tr-md",
  bl: "rounded-bl-md",
  br: "rounded-br-md",
  none: "",
};

const sizeClasses: Record<BubbleSize, string> = {
  xs: "px-3 py-2 text-xs",
  sm: "px-3.5 py-2 text-sm",
  md: "px-4 py-2.5 text-sm",
};

function createDisplacementMap(width: number, height: number, tail: BubbleTail) {
  const mapSize = 256;
  const canvas = document.createElement("canvas");
  canvas.width = mapSize;
  canvas.height = mapSize;
  const context = canvas.getContext("2d");
  if (!context) return "";
  const image = context.createImageData(mapSize, mapSize);
  const data = image.data;
  const depth = Math.max(2, Math.min(width, height) * 0.075);
  const corner = Math.min(16, width / 2, height / 2);

  for (let y = 0; y < mapSize; y += 1) {
    for (let x = 0; x < mapSize; x += 1) {
      const px = ((x + 0.5) / mapSize) * width;
      const py = ((y + 0.5) / mapSize) * height;
      let radius = corner;
      const top = py < corner;
      const bottom = py > height - corner;
      const left = px < corner;
      const right = px > width - corner;
      if ((tail === "tl" && top && left) || (tail === "tr" && top && right) || (tail === "bl" && bottom && left) || (tail === "br" && bottom && right)) {
        radius = Math.min(6, corner);
      }

      const centerX = Math.min(Math.max(px, radius), width - radius);
      const centerY = Math.min(Math.max(py, radius), height - radius);
      const outsideX = px - centerX;
      const outsideY = py - centerY;
      const distanceToRoundedEdge = radius - Math.hypot(outsideX, outsideY);
      const straightDistance = Math.min(px, width - px, py, height - py);
      const edgeDistance = Math.min(distanceToRoundedEdge, straightDistance);
      const falloff = Math.max(0, Math.min(1, 1 - edgeDistance / depth));
      const nx = (px / width) * 2 - 1;
      const ny = (py / height) * 2 - 1;
      const magnitude = Math.hypot(nx, ny) || 1;
      const displacementX = (nx / magnitude) * falloff;
      const displacementY = (ny / magnitude) * falloff;
      const diagonal = Math.max(Math.abs(nx * 0.766 + ny * 0.643), Math.abs(nx * 0.766 - ny * 0.643));
      const specular = Math.pow(Math.max(0, diagonal - 0.05) / 1.36, 2.4) * falloff;
      const index = (y * mapSize + x) * 4;
      data[index] = Math.round(128 + displacementX * 127);
      data[index + 1] = Math.round(128 + displacementY * 127);
      data[index + 2] = Math.round(128 + Math.min(1, specular) * 127);
      data[index + 3] = 255;
    }
  }

  context.putImageData(image, 0, 0);
  return canvas.toDataURL();
}

export function GlassBubble({
  children,
  tail = "none",
  size = "sm",
  className,
  contentClassName,
}: GlassBubbleProps) {
  const wrapperRef = useRef<HTMLDivElement>(null);
  const filterId = `glass-bubble-${useId().replace(/[^a-zA-Z0-9-]/g, "")}`;
  const [mapHref, setMapHref] = useState("");
  const [dimensions, setDimensions] = useState({ width: 1, height: 1 });

  useEffect(() => {
    const element = wrapperRef.current;
    if (!element) return;
    let frame = 0;
    const update = () => {
      const width = Math.max(1, element.clientWidth);
      const height = Math.max(1, element.clientHeight);
      setDimensions({ width, height });
      setMapHref(createDisplacementMap(width, height, tail));
    };
    update();
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(update);
    });
    observer.observe(element);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [tail]);

  return (
    <div ref={wrapperRef} className={cn("relative w-fit", className)}>
      <div
        aria-hidden="true"
        className={cn(
          "pointer-events-none absolute inset-0 rounded-2xl bg-card/70 shadow-sm backdrop-blur-xl",
          tailClasses[tail],
        )}
        style={mapHref ? { backdropFilter: `url(#${filterId})`, WebkitBackdropFilter: `url(#${filterId})` } : undefined}
      >
        {mapHref && (
          <svg aria-hidden="true" focusable="false" className="absolute size-0">
            <defs>
              <filter
                id={filterId}
                filterUnits="userSpaceOnUse"
                primitiveUnits="userSpaceOnUse"
                x="0"
                y="0"
                width={dimensions.width}
                height={dimensions.height}
                colorInterpolationFilters="sRGB"
              >
                <feFlood floodColor="rgb(128,128,128)" floodOpacity="1" result="mapBg" />
                <feImage href={mapHref} preserveAspectRatio="none" width={dimensions.width} height={dimensions.height} result="rawMap" />
                <feComposite in="rawMap" in2="mapBg" operator="over" result="map" />
                <feGaussianBlur in="SourceGraphic" stdDeviation="2.6" result="blurred" />
                <feDisplacementMap in="blurred" in2="map" scale="19" xChannelSelector="R" yChannelSelector="G" result="lensResult" />
                <feColorMatrix
                  in="map"
                  type="matrix"
                  values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 1 0 -0.50196"
                  result="specMask"
                />
                <feComposite in="specMask" in2="lensResult" operator="arithmetic" k2=".6" k3="1" result="lensResult" />
                <feComposite in="lensResult" in2="SourceGraphic" operator="over" />
              </filter>
            </defs>
          </svg>
        )}
      </div>
      <div className={cn("relative leading-snug text-primary", sizeClasses[size], contentClassName)}>
        {children}
      </div>
    </div>
  );
}

interface WordFlowProps {
  text: string;
  show?: boolean;
  className?: string;
}

export function WordFlow({ text, show = true, className }: WordFlowProps) {
  return (
    <span className={className}>
      {text.split(" ").map((word, index) => (
        <span key={`${word}-${index}`}>
          {index > 0 ? " " : ""}
          <span
            className={cn(
              "inline-block",
              show ? "animate-[word-flow-in_.5s_cubic-bezier(.22,1,.36,1)_both]" : "translate-y-[.5em] opacity-0 blur-[3px]",
            )}
            style={show ? { animationDelay: `${index * 50}ms` } : undefined}
          >
            {word}
          </span>
        </span>
      ))}
    </span>
  );
}

interface PopBubbleProps {
  text?: string;
  children?: ReactNode;
  show?: boolean;
  tail?: BubbleTail;
  size?: BubbleSize;
  delay?: number;
  className?: string;
}

const origins: Record<BubbleTail, string> = {
  tl: "left top",
  tr: "right top",
  bl: "left bottom",
  br: "right bottom",
  none: "center",
};

export function PopBubble({
  text,
  children,
  show = true,
  tail = "none",
  size = "sm",
  delay = 0,
  className,
}: PopBubbleProps) {
  return (
    <div
      className={cn(show ? "animate-[pop-bubble-in_380ms_cubic-bezier(.2,1.5,.4,1)_both]" : "scale-0", className)}
      style={{ transformOrigin: origins[tail], animationDelay: `${delay}ms` }}
    >
      <GlassBubble tail={tail} size={size}>
        {text ? <WordFlow text={text} show={show} /> : children}
      </GlassBubble>
    </div>
  );
}

export function readingTimeMs(text: string) {
  const wordCount = text.trim().split(/\s+/).filter(Boolean).length;
  return Math.min(3000, Math.max(800, 400 + 90 * wordCount));
}
