import type { ComponentProps } from "react";
import { twMerge } from "tailwind-merge";

type SpinnerProps = Omit<ComponentProps<"span">, "children"> & {
  size?: "sm" | "default" | "lg";
  label?: string;
  decorative?: boolean;
};
const sizes = { sm: "size-4", default: "size-5", lg: "size-6" };

export function Spinner({
  size = "default",
  label = "Loading",
  decorative = false,
  className,
  ...props
}: SpinnerProps) {
  return (
    <span
      {...props}
      role={decorative ? undefined : "status"}
      aria-label={decorative ? undefined : label}
      aria-hidden={decorative || undefined}
      className={twMerge(
        "inline-block shrink-0 rounded-full [border-width:var(--border-width-emphasis)] border-solid border-border border-t-primary animate-spin motion-reduce:animate-none",
        sizes[size],
        className,
      )}
    />
  );
}
