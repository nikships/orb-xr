import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * Shared native-style terminal window chrome.
 *
 * One dark titlebar to rule them all: traffic lights on the left, a centered
 * truncating title, and an optional status slot on the right. Built on a
 * 3-column grid so the title can never collide with the lights or the badge.
 */
export function TerminalWindow({
  title,
  status,
  needsInput = false,
  className,
  chromeClassName,
  bodyClassName,
  children,
}: {
  title: string;
  status?: ReactNode;
  needsInput?: boolean;
  className?: string;
  chromeClassName?: string;
  bodyClassName?: string;
  children: ReactNode;
}) {
  return (
    <div
      className={cn(
        "flex min-h-0 flex-col overflow-hidden rounded-xl border border-white/[.08] bg-[#101014] font-mono shadow-[0_22px_55px_-18px_rgba(0,0,0,.75)]",
        needsInput && "ring-2 ring-amber-400/90",
        className,
      )}
    >
      <div
        className={cn(
          "grid shrink-0 grid-cols-[1fr_minmax(0,2.5fr)_1fr] items-center gap-2 border-b border-white/[.06] bg-[#1b1b21] px-3 py-2",
          chromeClassName,
        )}
      >
        <span className="flex gap-1.5">
          <span className="size-2 rounded-full bg-[#ec6a5f]" />
          <span className="size-2 rounded-full bg-[#f4bf4f]" />
          <span className="size-2 rounded-full bg-[#61c454]" />
        </span>
        <span className="truncate text-center font-sans text-[11px] font-medium text-white/50">{title}</span>
        <span className="flex justify-end">
          {needsInput ? (
            <span className="flex items-center gap-1 whitespace-nowrap rounded-full bg-amber-400/15 px-2 py-0.5 font-mono text-[8px] font-bold uppercase tracking-[.06em] text-amber-300">
              <span className="size-1 animate-pulse rounded-full bg-amber-300" />
              needs input
            </span>
          ) : (
            status ?? null
          )}
        </span>
      </div>
      <div className={cn("flex min-h-0 flex-1 flex-col", bodyClassName)}>{children}</div>
    </div>
  );
}
