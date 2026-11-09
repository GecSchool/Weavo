import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  skeleton:
    "[min-height:var(--size-skeleton-min-height)] rounded-md bg-secondary animate-pulse motion-reduce:[animation:none]",
};

export function Skeleton({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      aria-hidden="true"
      className={classes(styles.skeleton, className)}
      {...props}
    />
  );
}
