import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Spinner } from "./index";

describe("Spinner", () => {
  it("로딩 인디케이터는 상태 이름을 제공하고 장식용이면 숨긴다", () => {
    const { rerender } = render(<Spinner label="Analyzing" />);
    expect(
      screen.getByRole("status", { name: "Analyzing" }),
    ).toBeInTheDocument();
    rerender(<Spinner decorative />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
