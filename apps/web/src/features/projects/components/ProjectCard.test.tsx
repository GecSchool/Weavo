import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ProjectCard, type ProjectCardStatus } from "./ProjectCard";

describe("ProjectCard", () => {
  it.each<[ProjectCardStatus, string, string]>([
    ["needs-review", "Needs review", "Review issue"],
    ["awaiting-approval", "Awaiting approval", "Review draft"],
    ["analyzing", "Analyzing", "View analysis"],
    ["ready", "Ready", "Open project"],
    ["deleting", "Deleting", "View status"],
  ])(
    "shows the status and real destination for %s",
    (status, statusLabel, action) => {
      render(
        <ProjectCard
          name="Grants"
          purpose="Public opportunities"
          status={status}
          lastSuccessfulUpdate="Today"
          href="/example"
        />,
      );
      expect(screen.getByText(statusLabel)).toBeInTheDocument();
      expect(screen.getByText(statusLabel)).toHaveClass("max-w-full");
      expect(screen.getByText(statusLabel).className).not.toMatch(/\bw-\[/);
      expect(screen.getByRole("link", { name: action })).toHaveAttribute(
        "href",
        "/example",
      );
      expect(
        screen.getByText("Last successful update · Today"),
      ).toBeInTheDocument();
    },
  );
});
