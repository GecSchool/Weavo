import { twMerge } from "tailwind-merge";

export function classes(...values: (string | undefined | false)[]) {
  return twMerge(values.filter(Boolean).join(" "));
}
