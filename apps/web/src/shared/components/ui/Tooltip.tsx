"use client";
import * as TooltipPrimitive from "@radix-ui/react-tooltip";
import type { ComponentProps, ReactElement, ReactNode } from "react";
import { twMerge } from "tailwind-merge";
import { overlayOffset } from "./ui-metrics";
export function TooltipProvider({
  delayDuration = 400,
  ...props
}: ComponentProps<typeof TooltipPrimitive.Provider>) {
  return <TooltipPrimitive.Provider delayDuration={delayDuration} {...props} />;
}
type TooltipProps = Pick<
  ComponentProps<typeof TooltipPrimitive.Root>,
  "open" | "defaultOpen" | "onOpenChange" | "delayDuration"
> & {
  children: ReactElement;
  content: ReactNode;
  side?: ComponentProps<typeof TooltipPrimitive.Content>["side"];
  className?: string;
};
/** The trigger must be focusable and have its own accessible name. */
export function Tooltip({
  children,
  content,
  side = "top",
  className,
  ...props
}: TooltipProps) {
  return (
    <TooltipPrimitive.Root {...props}>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Content
          side={side}
          sideOffset={overlayOffset.tooltip}
          className={twMerge(
            "z-50 rounded-md bg-primary px-3 py-2 font-ui text-primary-foreground text-[length:var(--text-body-xs)] [line-height:var(--leading-body-xs)] [max-width:var(--radix-tooltip-content-available-width)]",
            className,
          )}
        >
          {content}
        </TooltipPrimitive.Content>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  );
}
