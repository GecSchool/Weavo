import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  checkbox:
    '[font-family:inherit] [font-size:inherit] [font-weight:inherit] [line-height:inherit] focus-visible:[outline:var(--border-width-ui)_solid_var(--color-neutral-708)] focus-visible:[outline-offset:0] disabled:[opacity:0.5] disabled:cursor-not-allowed [width:var(--size-control-indicator)] [height:var(--size-control-indicator)] [accent-color:var(--color-neutral-205)] appearance-none [display:inline-grid] [place-content:center] [flex:none] m-0 [border:var(--border-width-ui)_solid_var(--color-border)] rounded-sm bg-background cursor-pointer checked:border-neutral-205 checked:bg-primary checked:before:[content:"✓"] checked:before:text-neutral-white checked:before:text-[length:var(--text-body-sm)] checked:before:[line-height:var(--leading-tight)] checked:before:font-semibold',
};

export function Checkbox({
  className,
  ...props
}: Omit<ComponentProps<"input">, "type">) {
  return (
    <input
      type="checkbox"
      className={classes(styles.checkbox, className)}
      {...props}
    />
  );
}
