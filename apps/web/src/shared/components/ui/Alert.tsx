import type { ComponentProps } from "react";
import { classes } from "./classNames";
import { hintStyle } from "./fieldStyles";

const styles = {
  alert:
    "grid gap-1 py-3 px-4 [border:var(--border-width-ui)_solid_var(--color-border)] rounded-lg bg-background text-foreground text-[length:var(--text-body-sm)] [line-height:var(--leading-body-sm)] [min-height:var(--size-alert-min-height)] [align-content:start] font-ui",
  alert_destructive:
    "text-destructive border-border [&_[data-alert-description]]:text-destructive",
  alertTitle: "font-medium",
};

type AlertProps = ComponentProps<"div"> & {
  variant?: "default" | "destructive";
};

export function Alert({
  className,
  variant = "default",
  ...props
}: AlertProps) {
  return (
    <div
      className={classes(
        styles.alert,
        variant === "destructive" && styles.alert_destructive,
        className,
      )}
      {...props}
    />
  );
}

export function AlertTitle({ className, ...props }: ComponentProps<"div">) {
  return <div className={classes(styles.alertTitle, className)} {...props} />;
}

export function AlertDescription({
  className,
  ...props
}: ComponentProps<"div">) {
  return (
    <div
      data-alert-description=""
      className={classes(hintStyle, className)}
      {...props}
    />
  );
}
