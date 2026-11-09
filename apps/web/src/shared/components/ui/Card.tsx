import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  card: "flex flex-col gap-ui-card-gap p-6 [border:var(--border-width-ui)_solid_var(--color-border)] rounded-xl bg-background [box-shadow:var(--shadow-ui-control)] font-ui",
  cardTitle:
    "m-0 text-foreground text-[length:var(--text-title-base)] font-semibold [line-height:var(--leading-body-xs)]",
  cardDescription:
    "m-0 text-muted-foreground text-[length:var(--text-body-sm)] [line-height:var(--leading-body-sm)]",
};

export function Card({ className, ...props }: ComponentProps<"div">) {
  return <div className={classes(styles.card, className)} {...props} />;
}

export function CardTitle({ className, ...props }: ComponentProps<"h3">) {
  return <h3 className={classes(styles.cardTitle, className)} {...props} />;
}

export function CardDescription({ className, ...props }: ComponentProps<"p">) {
  return (
    <p className={classes(styles.cardDescription, className)} {...props} />
  );
}
