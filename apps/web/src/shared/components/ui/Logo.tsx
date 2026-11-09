import "@fontsource/inter/400.css";
import type { ComponentProps } from "react";
import { twMerge } from "tailwind-merge";

type LogoProps = Omit<ComponentProps<"span">, "children"> & {
  variant?: "full" | "mark";
};

/** The mark is decorative within a single accessible brand name. */
export function Logo({ variant = "full", className, ...props }: LogoProps) {
  return (
    <span
      role="img"
      aria-label="Weavo"
      className={twMerge(
        "inline-flex items-center gap-brand font-ui text-foreground",
        className,
      )}
      {...props}
    >
      <svg
        aria-hidden="true"
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="currentColor"
        className="shrink-0"
      >
        <rect x="4.5" y="6" width="6" height="3" rx="0.25" />
        <rect x="13.5" y="6" width="6" height="3" rx="0.25" />
        <rect x="4.5" y="15" width="6" height="3" rx="0.25" />
        <rect x="13.5" y="15" width="6" height="3" rx="0.25" />
        <rect x="8" y="10.5" width="8" height="3" rx="0.25" />
      </svg>
      {variant === "full" && (
        <span
          aria-hidden="true"
          className="text-[length:var(--text-brand)] font-normal [line-height:normal] tracking-brand"
        >
          weavo
        </span>
      )}
    </span>
  );
}
