import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  label:
    "text-foreground text-[length:var(--text-body-sm)] font-medium [line-height:var(--leading-body-sm)] font-ui",
};

export function Label({ className, ...props }: ComponentProps<"label">) {
  return <label className={classes(styles.label, className)} {...props} />;
}
