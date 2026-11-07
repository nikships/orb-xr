import Image from "next/image";
import { Sun } from "lucide-react";

const playStore = "https://play.google.com/store/apps/details?id=com.ghosttyxr.app";

const columns = [
  [
    {
      title: "Product",
      links: [
        ["Google Play", playStore],
        ["Privacy", "/privacy/"],
      ],
    },
  ],
  [
    {
      title: "Download",
      links: [["Google Play", playStore]],
    },
    {
      title: "Runs on",
      links: [["Galaxy XR", "https://www.samsung.com/galaxy-xr/"]],
    },
  ],
  [
    {
      title: "Stack",
      links: [
        ["libssh2", "https://libssh2.org"],
        ["Jetpack XR", "https://developer.android.com/xr"],
        ["Zig", "https://ziglang.org"],
      ],
    },
  ],
] as const;

export function Footer() {
  return (
    <footer className="border-t border-border">
      <div className="mx-auto w-full max-w-7xl px-4 py-12 lg:px-6 lg:py-16">
        <div className="flex flex-col gap-12 lg:flex-row lg:gap-0">
          <div className="flex shrink-0 flex-col lg:w-[280px] lg:pr-10">
            <div>
              <Image
                src="/images/orbxr/coach-ready-badge.png"
                alt="Orb XR first-flight ready badge"
                width={900}
                height={631}
                className="-ml-2 h-20 w-auto"
              />
              <div className="mt-2 font-display text-sm font-medium tracking-tight text-primary/40">Orb XR</div>
              <p className="mt-3 text-[10px] text-primary/35">© 2026 Orb XR</p>
            </div>
            <div className="mt-8 flex items-center gap-2 lg:mt-auto">
              <button
                type="button"
                aria-label="Switch to light mode"
                className="flex size-7 items-center justify-center rounded-full text-primary/30 transition-colors hover:text-primary/60"
              >
                <Sun className="size-4" />
              </button>
            </div>
          </div>
          <div className="hidden border-l border-dashed border-primary/10 lg:block" />
          <div className="grid flex-1 grid-cols-2 gap-x-8 gap-y-10 lg:grid-cols-3 lg:pl-12">
            {columns.map((column, columnIndex) => (
              <div key={columnIndex} className="space-y-8">
                {column.map((group) => (
                  <div key={group.title}>
                    <h3 className="mb-1.5 text-[13px] font-medium text-primary/70">{group.title}</h3>
                    <ul className="space-y-1.5">
                      {group.links.map(([label, href]) => {
                        const external = href.startsWith("http");
                        return (
                          <li key={`${group.title}-${label}`}>
                            <a
                              href={href}
                              target={external ? "_blank" : undefined}
                              rel={external ? "noopener noreferrer" : undefined}
                              className="text-[13px] text-primary/50 transition-colors hover:text-primary"
                            >
                              {label}
                            </a>
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                ))}
              </div>
            ))}
          </div>
        </div>
      </div>
    </footer>
  );
}
