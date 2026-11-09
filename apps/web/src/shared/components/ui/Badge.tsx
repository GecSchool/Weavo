import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  badge:
    "inline-flex items-center [min-height:var(--size-badge-min-height)] py-ui-badge-y px-2 border border-solid border-transparent rounded-md text-[length:var(--text-body-xs)] [line-height:var(--leading-body-xs)] font-medium font-ui",
  badge_default: "bg-primary text-primary-foreground",
  badge_secondary: "bg-secondary text-foreground",
  badge_destructive: "bg-destructive text-neutral-white",
  badge_outline: "border-border text-foreground",
};

type BadgeProps = ComponentProps<"span"> & {
  variant?: "default" | "secondary" | "destructive" | "outline";
};

export function Badge({
  className,
  variant = "default",
  ...props
}: BadgeProps) {
  return (
    <span
      className={classes(styles.badge, styles[`badge_${variant}`], className)}
      {...props}
    />
  );
}
