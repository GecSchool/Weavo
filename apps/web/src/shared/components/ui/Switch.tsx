import type { ComponentProps } from "react";
import { twMerge } from "tailwind-merge";

type SwitchProps = Omit<ComponentProps<"input">, "type" | "role">;

/** Label the input with a wrapping label, htmlFor, or aria-label. */
export function Switch({ className, ...props }: SwitchProps) {
  return (
    <input
      type="checkbox"
      role="switch"
      className={twMerge(
        "relative m-0 inline-block h-6 w-10 shrink-0 appearance-none rounded-full border-0 bg-neutral-922 cursor-pointer transition-colors before:absolute before:left-ui-badge-y before:top-ui-badge-y before:size-5 before:rounded-full before:bg-background before:content-[''] before:transition-transform checked:bg-primary checked:before:translate-x-4 focus-visible:outline focus-visible:outline-1 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 motion-reduce:transition-none motion-reduce:before:transition-none",
        className,
      )}
      {...props}
    />
  );
}
