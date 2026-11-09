import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  button:
    "font-ui text-[length:var(--text-body-sm)] font-medium [line-height:var(--leading-body-sm)] inline-flex items-center justify-center gap-2 border border-solid border-transparent rounded-md whitespace-nowrap cursor-pointer [transition:background-color_var(--duration-ui-fast),_box-shadow_var(--duration-ui-fast)] focus-visible:[outline:var(--border-width-ui)_solid_var(--color-neutral-708)] focus-visible:[outline-offset:0] disabled:[opacity:0.5] disabled:cursor-not-allowed disabled:hover:[filter:none]",
  size_default: "[height:var(--size-control-default)] py-0 px-4",
  size_sm:
    "[height:var(--size-control-sm)] py-0 px-3 text-[length:var(--text-body-xs)] [line-height:var(--leading-body-xs)]",
  size_lg: "[height:var(--size-control-lg)] py-0 px-8",
  size_icon:
    "[width:var(--size-control-default)] [height:var(--size-control-default)] p-0",
  button_default:
    "bg-primary text-primary-foreground [box-shadow:var(--shadow-ui-control)] hover:bg-primary-hover disabled:hover:bg-primary",
  button_destructive:
    "bg-destructive text-destructive-foreground [box-shadow:var(--shadow-ui-control)] hover:bg-destructive-hover disabled:hover:bg-destructive",
  button_outline:
    "bg-background border-border text-foreground [box-shadow:var(--shadow-ui-control)] hover:bg-secondary disabled:hover:bg-background",
  button_ghost:
    "hover:bg-secondary [background:transparent] text-secondary-foreground disabled:hover:[background:transparent]",
  button_secondary:
    "bg-secondary text-secondary-foreground hover:bg-secondary-hover disabled:hover:bg-secondary",
  button_link:
    "[background:transparent] text-secondary-foreground hover:[text-decoration:underline] hover:[text-underline-offset:var(--spacing-1)]",
};

export type ButtonProps = ComponentProps<"button"> & {
  variant?:
    "default" | "destructive" | "outline" | "secondary" | "ghost" | "link";
  size?: "default" | "sm" | "lg" | "icon";
};

export function buttonClasses({
  variant = "default",
  size = "default",
  className,
}: Pick<ButtonProps, "variant" | "size" | "className"> = {}) {
  return classes(
    styles.button,
    styles[`button_${variant}`],
    styles[`size_${size}`],
    className,
  );
}

export function Button({
  variant = "default",
  size = "default",
  className,
  type = "button",
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      className={buttonClasses({ variant, size, className })}
      {...props}
    />
  );
}
