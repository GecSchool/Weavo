import type { ComponentProps } from "react";
import { classes } from "./classNames";
import { controlStyle } from "./fieldStyles";

export function Input({ className, ...props }: ComponentProps<"input">) {
  return <input className={classes(controlStyle, className)} {...props} />;
}
