import type { ComponentProps } from "react";
import { classes } from "./classNames";
import { controlStyle } from "./fieldStyles";

const styles = {
  textarea:
    "[min-height:var(--size-textarea-min-height)] [height:auto] resize-y",
};

export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      className={classes(controlStyle, styles.textarea, className)}
      {...props}
    />
  );
}
