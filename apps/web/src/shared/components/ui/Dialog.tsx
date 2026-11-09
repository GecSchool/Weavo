"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";
import { Button } from "./Button";
import { Icon } from "./Icon";

const styles = {
  dialog:
    "fixed [width:min(var(--size-dialog-max-width),_calc(100%_-_var(--size-dialog-viewport-inset)))] [max-height:calc(100%_-_var(--size-dialog-viewport-inset))] m-auto p-6 [border:var(--border-width-ui)_solid_var(--color-border)] rounded-md bg-background text-foreground [box-shadow:var(--shadow-ui-dialog)] backdrop:bg-black-53 [min-height:var(--size-dialog-min-height)] [&[open]]:flex [&[open]]:flex-col font-ui",
  dialogTitle:
    "mt-0 mr-8 mb-0 ml-0 text-[length:var(--text-title-lg)] [line-height:var(--leading-title-lg)] font-semibold",
  dialogDescription:
    "mt-3 mx-0 mb-0 text-muted-foreground text-[length:var(--text-body-sm)] [line-height:var(--leading-body-sm)]",
  close: "absolute top-3 right-3 text-[length:var(--text-showcase-icon)]",
  actions: "flex flex-wrap justify-end gap-2 mt-auto pt-8",
};

type DialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children?: ReactNode;
  actions?: ReactNode;
  kind?: "dialog" | "alertdialog";
};
export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  actions,
  kind = "dialog",
}: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      role={kind}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      className={styles.dialog}
      onCancel={(event) => {
        event.preventDefault();
        onOpenChange(false);
      }}
      onClose={() => {
        if (open) onOpenChange(false);
      }}
    >
      <h2 id={titleId} className={styles.dialogTitle}>
        {title}
      </h2>
      {description && (
        <p id={descriptionId} className={styles.dialogDescription}>
          {description}
        </p>
      )}
      {kind === "dialog" && (
        <Button
          variant="ghost"
          size="icon"
          className={styles.close}
          aria-label="닫기"
          onClick={() => onOpenChange(false)}
        >
          <Icon name="close" />
        </Button>
      )}
      {children}
      {actions && <div className={styles.actions}>{actions}</div>}
    </dialog>
  );
}
export function AlertDialog(props: Omit<DialogProps, "kind">) {
  return <Dialog {...props} kind="alertdialog" />;
}
