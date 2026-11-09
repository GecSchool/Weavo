"use client";

import type { ComponentProps, ReactNode } from "react";
import { twMerge } from "tailwind-merge";

type RadioOption = { value: string; label: ReactNode; disabled?: boolean };
type RadioGroupProps = Omit<ComponentProps<"fieldset">, "onChange"> & {
  name: string;
  legend: ReactNode;
  options: RadioOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  required?: boolean;
};

export function RadioGroup({
  name,
  legend,
  options,
  value,
  defaultValue,
  onValueChange,
  required,
  className,
  ...props
}: RadioGroupProps) {
  return (
    <fieldset
      className={twMerge(
        "m-0 grid gap-2 border-0 p-0 font-ui text-foreground",
        className,
      )}
      {...props}
    >
      <legend className="mb-2 p-0 text-[length:var(--text-body-sm)] font-medium [line-height:var(--leading-body-sm)]">
        {legend}
      </legend>
      {options.map((option) => (
        <label
          key={option.value}
          className="inline-flex items-center gap-2 text-[length:var(--text-body-sm)] [line-height:var(--leading-body-sm)] has-[:disabled]:opacity-50"
        >
          <input
            type="radio"
            name={name}
            value={option.value}
            checked={value === undefined ? undefined : value === option.value}
            defaultChecked={
              value === undefined ? defaultValue === option.value : undefined
            }
            disabled={option.disabled}
            required={required}
            onChange={(event) => onValueChange?.(event.currentTarget.value)}
            className="m-0 inline-grid size-5 shrink-0 appearance-none place-content-center rounded-full border border-solid border-border bg-background cursor-pointer before:size-2 before:rounded-full before:bg-primary before:content-[''] before:scale-0 checked:border-primary checked:before:scale-100 focus-visible:outline focus-visible:outline-1 focus-visible:outline-ring disabled:cursor-not-allowed"
          />
          {option.label}
        </label>
      ))}
    </fieldset>
  );
}
