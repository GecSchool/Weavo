import Link from "next/link";
import {
  Badge,
  buttonClasses,
  CardDescription,
  CardTitle,
} from "@/shared/components/ui";
import { twMerge } from "tailwind-merge";

const statusDetails = {
  "needs-review": {
    label: "Needs review",
    variant: "destructive",
    action: "Review issue",
  },
  "awaiting-approval": {
    label: "Awaiting approval",
    variant: "outline",
    action: "Review draft",
  },
  analyzing: {
    label: "Analyzing",
    variant: "secondary",
    action: "View analysis",
  },
  ready: { label: "Ready", variant: "secondary", action: "Open project" },
  deleting: { label: "Deleting", variant: "outline", action: "View status" },
} as const;
export type ProjectCardStatus = keyof typeof statusDetails;

type ProjectCardProps = {
  name: string;
  purpose: string;
  status: ProjectCardStatus;
  lastSuccessfulUpdate: string;
  href: string;
  className?: string;
};

/** The caller chooses a real destination for the action associated with the status. */
export function ProjectCard({
  name,
  purpose,
  status,
  lastSuccessfulUpdate,
  href,
  className,
}: ProjectCardProps) {
  const details = statusDetails[status];
  return (
    <article
      className={twMerge(
        "flex min-h-[var(--size-project-card-min-height)] min-w-0 w-full self-start flex-col gap-3 rounded-lg border border-solid border-border bg-card p-6 font-ui",
        className,
      )}
    >
      <div className="flex min-h-7 flex-wrap items-center justify-between gap-2">
        <CardTitle className="min-w-0 break-words">{name}</CardTitle>
        <Badge
          variant={details.variant}
          className="max-w-full shrink-0 justify-center"
        >
          {details.label}
        </Badge>
      </div>
      <CardDescription className="break-words">{purpose}</CardDescription>
      <div className="mt-auto flex flex-wrap items-center justify-between gap-3">
        <p className="m-0 text-[length:var(--text-body-xs)] [line-height:var(--leading-body-xs)] text-muted-foreground">
          Last successful update · {lastSuccessfulUpdate}
        </p>
        <Link
          href={href}
          className={buttonClasses({
            variant: "outline",
            size: "sm",
            className: "no-underline",
          })}
        >
          {details.action}
        </Link>
      </div>
    </article>
  );
}
