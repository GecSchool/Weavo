import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  separator:
    "[width:100%] [height:var(--border-width-ui)] bg-neutral-922 shrink-0",
  vertical: "[width:var(--border-width-ui)] [height:auto] [align-self:stretch]",
};

export function Separator({
  orientation = "horizontal",
  decorative = true,
  className,
  ...props
}: ComponentProps<"div"> & {
  orientation?: "horizontal" | "vertical";
  decorative?: boolean;
}) {
  return (
    <div
      role={decorative ? "none" : "separator"}
      aria-orientation={!decorative ? orientation : undefined}
      className={classes(
        styles.separator,
        orientation === "vertical" && styles.vertical,
        className,
      )}
      {...props}
    />
  );
}
