"use client";

import * as SelectPrimitive from "@radix-ui/react-select";
import type { ReactNode } from "react";
import { twMerge } from "tailwind-merge";
import { overlayOffset } from "./ui-metrics";

const styles = {
  selectTrigger:
    "focus-visible:[outline:var(--border-width-ui)_solid_var(--color-neutral-708)] focus-visible:[outline-offset:0] inline-flex items-center justify-between gap-3 w-full [height:var(--size-control-default)] py-0 px-3 [border:var(--border-width-ui)_solid_var(--color-border)] rounded-md bg-background text-foreground font-ui text-[length:var(--text-body-sm)] [font-weight:inherit] [line-height:var(--leading-body-sm)] text-left cursor-pointer [&[data-placeholder]]:text-muted-foreground [&[aria-invalid='true']]:border-destructive focus-visible:border-ring disabled:[opacity:0.5] disabled:cursor-not-allowed",
  selectSmall: "[height:var(--size-control-sm)]",
  selectIcon: "text-muted-foreground [line-height:var(--leading-tight)]",
  selectContent:
    "[z-index:100] overflow-hidden [min-width:var(--radix-select-trigger-width)] [max-height:var(--radix-select-content-available-height)] [border:var(--border-width-ui)_solid_var(--color-border)] rounded-md bg-background [box-shadow:var(--shadow-ui-popover)] font-ui",
  selectViewport: "p-1",
  selectItem:
    "relative flex items-center [justify-content:flex-start] gap-2 [min-height:var(--size-control-default)] py-0 px-2 [border-radius:var(--radius-option)] text-foreground text-[length:var(--text-body-sm)] [line-height:var(--leading-body-sm)] [outline:none] cursor-default [&[data-highlighted]]:bg-secondary [&[data-disabled]]:[opacity:0.5]",
  selectIndicator: "[flex:none]",
};

type SelectOption = {
  value: string;
  label: ReactNode;
  disabled?: boolean;
  textValue?: string;
};
type SelectProps = {
  id?: string;
  name?: string;
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  options: SelectOption[];
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
  size?: "default" | "sm";
  className?: string;
  "aria-label"?: string;
  "aria-labelledby"?: string;
  "aria-describedby"?: string;
  "aria-invalid"?: boolean | "true" | "false" | "grammar" | "spelling";
};

export function Select({
  id,
  name,
  value,
  defaultValue,
  onValueChange,
  options,
  placeholder = "Select an option",
  disabled,
  required,
  size = "default",
  className,
  ...aria
}: SelectProps) {
  return (
    <SelectPrimitive.Root
      name={name}
      value={value}
      defaultValue={defaultValue}
      onValueChange={onValueChange}
      disabled={disabled}
      required={required}
    >
      <SelectPrimitive.Trigger
        id={id}
        className={twMerge(
          [styles.selectTrigger, size === "sm" && styles.selectSmall, className]
            .filter(Boolean)
            .join(" "),
        )}
        {...aria}
      >
        <SelectPrimitive.Value placeholder={placeholder} />
        <SelectPrimitive.Icon className={styles.selectIcon} aria-hidden="true">
          ⌄
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          className={styles.selectContent}
          position="popper"
          sideOffset={overlayOffset.select}
        >
          <SelectPrimitive.Viewport className={styles.selectViewport}>
            {options.map((option) => (
              <SelectPrimitive.Item
                className={styles.selectItem}
                key={option.value}
                value={option.value}
                disabled={option.disabled}
                textValue={option.textValue}
              >
                <SelectPrimitive.ItemText>
                  {option.label}
                </SelectPrimitive.ItemText>
                <SelectPrimitive.ItemIndicator
                  className={styles.selectIndicator}
                >
                  ✓
                </SelectPrimitive.ItemIndicator>
              </SelectPrimitive.Item>
            ))}
          </SelectPrimitive.Viewport>
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  );
}
