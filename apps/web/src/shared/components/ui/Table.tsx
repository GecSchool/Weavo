import type { ComponentProps } from "react";
import { classes } from "./classNames";

const styles = {
  tableContainer: "[width:100%] overflow-x-auto",
  table:
    "[width:100%] border-collapse text-foreground text-[length:var(--text-body-sm)] [line-height:var(--leading-body-sm)] text-left [&_th]:[height:var(--size-table-row-height)] [&_th]:p-2 [&_td]:[height:var(--size-table-row-height)] [&_td]:p-2 [&_th]:text-muted-foreground [&_th]:font-medium [&_tr[data-state='selected']]:bg-secondary [&_caption]:[caption-side:bottom] [&_caption]:text-muted-foreground [&_caption]:p-2 font-ui",
};

export function Table({ className, ...props }: ComponentProps<"table">) {
  return (
    <div className={styles.tableContainer}>
      <table className={classes(styles.table, className)} {...props} />
    </div>
  );
}

export function TableHeader(props: ComponentProps<"thead">) {
  return <thead {...props} />;
}

export function TableBody(props: ComponentProps<"tbody">) {
  return <tbody {...props} />;
}

export function TableRow(props: ComponentProps<"tr">) {
  return <tr {...props} />;
}

export function TableHead(props: ComponentProps<"th">) {
  return <th scope="col" {...props} />;
}

export function TableCell(props: ComponentProps<"td">) {
  return <td {...props} />;
}

export function TableCaption(props: ComponentProps<"caption">) {
  return <caption {...props} />;
}
