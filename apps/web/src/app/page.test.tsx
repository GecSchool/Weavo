import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import Home from "@/app/page";

describe("Home", () => {
  it("renders the Weavo home heading", () => {
    render(<Home />);
    expect(screen.getByRole("heading", { name: "Weavo" })).toBeInTheDocument();
  });
});
