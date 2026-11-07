"use client";

import { useEffect, useRef } from "react";
import type { OrbArchetype, OrbPalette } from "@/types/voice";
import { createOrbState, hashSeed, OrbRenderer, setOrbAudioLevel } from "./orb-renderer";

interface AgentOrbProps {
  size?: number;
  seed?: string;
  palette?: OrbPalette;
  archetype?: OrbArchetype;
  animate?: boolean;
  className?: string;
}

const generatedPalettes: OrbPalette[] = [
  { anchor: "#2A0F22", accents: ["#FF7ECB", "#D14FFF", "#FFDCF2"] },
  { anchor: "#0A1B2E", accents: ["#38BDF8", "#0E7BD1", "#CFF2FF"] },
  { anchor: "#241627", accents: ["#FFC9E0", "#E4A8FF", "#FFE9F4"] },
  { anchor: "#0A2220", accents: ["#63D8C2", "#2E9E8C", "#DFFCF4"] },
  { anchor: "#2E1E04", accents: ["#FFD54A", "#FFB300", "#FFF3B0"] },
  { anchor: "#101632", accents: ["#4CC9F0", "#7B5CFF", "#B8F1FF"] },
];

const archetypes: OrbArchetype[] = ["spiral", "nebula", "core", "deep"];

const sharedRenderSize = 512;

interface SharedOrbRenderer {
  canvas: HTMLCanvasElement;
  renderer: OrbRenderer;
}

let sharedOrbRenderer: SharedOrbRenderer | null = null;

function getSharedOrbRenderer() {
  if (sharedOrbRenderer) return sharedOrbRenderer;
  const canvas = document.createElement("canvas");
  canvas.width = sharedRenderSize;
  canvas.height = sharedRenderSize;
  const gl = canvas.getContext("webgl", {
    alpha: true,
    antialias: true,
    premultipliedAlpha: true,
    preserveDrawingBuffer: true,
  });
  if (!gl) return null;
  sharedOrbRenderer = {
    canvas,
    renderer: new OrbRenderer(gl),
  };
  return sharedOrbRenderer;
}

export function AgentOrb({
  size = 46,
  seed = "grok",
  palette,
  archetype,
  animate = true,
  className,
}: AgentOrbProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const seedHash = hashSeed(seed);
  const resolvedPalette = palette ?? generatedPalettes[seedHash % generatedPalettes.length];
  const resolvedArchetype = archetype ?? archetypes[(seedHash >>> 16) % archetypes.length];

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const pixelSize = Math.min(1280, Math.round(size * dpr));
    canvas.width = pixelSize;
    canvas.height = pixelSize;
    const context = canvas.getContext("2d");
    const shared = getSharedOrbRenderer();
    if (!context || !shared) return;
    const state = createOrbState(seed, resolvedPalette, resolvedArchetype);
    const timeOffset = (seedHash % 4000) / 1000;
    let visible = true;
    let frame = 0;
    let lastFrame = 0;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    const draw = (timestamp: number) => {
      if (visible && timestamp - lastFrame >= 1000 / 60 - 1) {
        lastFrame = timestamp;
        shared.renderer.render(state, sharedRenderSize, timestamp / 1000 + timeOffset);
        context.clearRect(0, 0, pixelSize, pixelSize);
        context.drawImage(shared.canvas, 0, 0, pixelSize, pixelSize);
      }
      if (animate && !reducedMotion) frame = requestAnimationFrame(draw);
    };

    shared.renderer.render(state, sharedRenderSize, performance.now() / 1000 + timeOffset);
    context.drawImage(shared.canvas, 0, 0, pixelSize, pixelSize);
    if (animate && !reducedMotion) frame = requestAnimationFrame(draw);
    const observer = new IntersectionObserver(
      ([entry]) => {
        visible = entry.isIntersecting;
      },
      { threshold: 0 },
    );
    observer.observe(canvas);

    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
      setOrbAudioLevel(seed, null);
    };
  }, [animate, resolvedArchetype, resolvedPalette, seed, seedHash, size]);

  return (
    <div
      aria-hidden="true"
      className={`relative block shrink-0 ${className ?? ""}`}
      style={{ width: size, height: size }}
    >
      <div className="absolute inset-0 overflow-hidden rounded-full [clip-path:circle(50%_at_50%_50%)] [mask-image:radial-gradient(closest-side,#000_calc(100%_-_.5px),transparent)]">
        <canvas
          ref={canvasRef}
          className="relative block size-full [clip-path:circle(50%_at_50%_50%)]"
          style={{ animation: "orb-boot-in 460ms cubic-bezier(.2,.8,.2,1) both" }}
        />
      </div>
      {size >= 48 && (
        <div
          className="pointer-events-none absolute inset-0 rounded-full opacity-35"
          style={{
            boxShadow: `inset 0 1px 1px rgba(255,255,255,.7), inset 0 -1px 1px rgba(255,255,255,.45), inset 0 0 0 1px rgba(255,255,255,.22), inset 0 0 ${(size * 0.06).toFixed(1)}px rgba(255,255,255,.18)`,
          }}
        />
      )}
    </div>
  );
}

interface AudioGraph {
  context: AudioContext;
  analyser: AnalyserNode;
}

const audioGraphs = new WeakMap<HTMLAudioElement, AudioGraph>();

export function attachAgentOrbAudio(source: HTMLAudioElement | AnalyserNode, seed: string) {
  let analyser: AnalyserNode;
  if (source instanceof AnalyserNode) {
    analyser = source;
  } else {
    let graph = audioGraphs.get(source);
    if (!graph) {
      const context = new AudioContext();
      const mediaSource = context.createMediaElementSource(source);
      analyser = context.createAnalyser();
      analyser.fftSize = 512;
      mediaSource.connect(analyser);
      analyser.connect(context.destination);
      graph = { context, analyser };
      audioGraphs.set(source, graph);
    }
    void graph.context.resume();
    analyser = graph.analyser;
  }

  const samples = new Uint8Array(analyser.fftSize);
  let frame = 0;
  const update = () => {
    frame = requestAnimationFrame(update);
    analyser.getByteTimeDomainData(samples);
    let energy = 0;
    for (const sample of samples) {
      const normalized = (sample - 128) / 128;
      energy += normalized * normalized;
    }
    setOrbAudioLevel(seed, Math.min(1, 3.2 * Math.sqrt(energy / samples.length)));
  };
  frame = requestAnimationFrame(update);

  return () => {
    cancelAnimationFrame(frame);
    setOrbAudioLevel(seed, null);
  };
}

export function releaseAgentOrbAudio(source: HTMLAudioElement) {
  const graph = audioGraphs.get(source);
  if (!graph) return;
  audioGraphs.delete(source);
  void graph.context.close();
}
