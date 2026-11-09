import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Copy,
  ExternalLink,
  Filter,
  Info,
  MoreHorizontal,
  Plus,
  Search,
  Settings,
  Trash2,
  X,
} from "lucide-react";
import type { SVGProps } from "react";
import { twMerge } from "tailwind-merge";

const icons = {
  back: ArrowLeft,
  forward: ArrowRight,
  check: Check,
  "chevron-down": ChevronDown,
  "chevron-right": ChevronRight,
  alert: CircleAlert,
  copy: Copy,
  "external-link": ExternalLink,
  filter: Filter,
  info: Info,
  more: MoreHorizontal,
  add: Plus,
  search: Search,
  settings: Settings,
  delete: Trash2,
  close: X,
} as const;
export type IconName = keyof typeof icons;
type IconProps = Omit<SVGProps<SVGSVGElement>, "name" | "children"> & {
  name: IconName;
  size?: "sm" | "md" | "lg";
  /** Omit when the enclosing control already has a name. */ label?: string;
};
const sizes = { sm: "size-4", md: "size-5", lg: "size-6" };
export function Icon({
  name,
  size = "md",
  label,
  className,
  ...props
}: IconProps) {
  const Glyph = icons[name];
  return (
    <Glyph
      {...props}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
      strokeWidth="var(--stroke-icon)"
      className={twMerge("shrink-0", sizes[size], className)}
    />
  );
}
