import Image from "next/image";
import { ArrowUpRight } from "lucide-react";

const playStore = "https://play.google.com/store/apps/details?id=com.ghosttyxr.app";

export function HardwareStrip() {
  return (
    <section className="border-t border-border py-16 sm:py-24">
      <div className="mx-auto w-full max-w-7xl px-4 lg:px-6">
        <div className="grid grid-cols-1 gap-3 sm:gap-4 lg:grid-cols-2">
          <a
            href={playStore}
            target="_blank"
            rel="noopener noreferrer"
            className="group relative isolate flex flex-col overflow-hidden rounded-[1.75rem] bg-card p-8 transition-colors hover:bg-[#1b1b1b] sm:p-10"
          >
            <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(80%_60%_at_70%_100%,rgba(56,225,255,.07),transparent)]" />
            <div className="flex items-center gap-2 text-[11px] font-medium uppercase tracking-[.14em] text-sunset">
              Shipping now
            </div>
            <h3 className="mt-3 flex items-center gap-1.5 font-display text-2xl tracking-tight sm:text-3xl">
              Runs on Samsung Galaxy XR
              <ArrowUpRight className="size-5 text-primary/40 transition-transform group-hover:-translate-y-0.5 group-hover:translate-x-0.5" />
            </h3>
            <p className="mt-3 max-w-md text-sm leading-relaxed text-primary/55">
              A native arm64 Android XR build — terminal rendering and SSH transport in a single Zig JNI core. Install
              it from Google Play, scan the QR from your Mac, and you&apos;re flying.
            </p>
            <div className="mt-6 flex-1">
              <Image
                src="/images/products/samsung-galaxy-xr.webp"
                alt="Samsung Galaxy XR headset"
                width={1400}
                height={819}
                className="mx-auto w-full max-w-md drop-shadow-[0_40px_90px_rgba(56,225,255,.16)] transition-transform duration-500 group-hover:scale-[1.03]"
              />
            </div>
          </a>

          <div className="relative isolate flex flex-col overflow-hidden rounded-[1.75rem] bg-card p-8 sm:p-10">
            <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(80%_60%_at_30%_100%,rgba(162,77,255,.08),transparent)]" />
            <div className="flex items-center gap-2 text-[11px] font-medium uppercase tracking-[.14em] text-primary/40">
              Built on Android XR
            </div>
            <h3 className="mt-3 font-display text-2xl tracking-tight sm:text-3xl">Aimed at lighter glass</h3>
            <p className="mt-3 max-w-md text-sm leading-relaxed text-primary/55">
              Android XR is the same platform Samsung and Google are pushing toward lighter, glasses-class hardware.
              Orb XR is a native citizen of that platform — no forks, no wrappers — so it moves where the hardware
              goes.
            </p>
            <div className="mt-6 flex flex-1 items-center">
              <Image
                src="/images/products/xr-glasses.webp"
                alt="Lightweight XR glasses"
                width={1400}
                height={565}
                className="mx-auto w-full max-w-md drop-shadow-[0_40px_90px_rgba(162,77,255,.18)]"
              />
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
