import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Logo } from "./index";

describe("Logo", () => {
  it("로고는 전체와 심볼 형태에서 하나의 접근 가능한 이름을 제공한다", () => {
    const { rerender } = render(<Logo />);
    expect(screen.getByRole("img", { name: "Weavo" })).toBeInTheDocument();
    rerender(<Logo variant="mark" />);
    expect(screen.getByRole("img", { name: "Weavo" })).toBeInTheDocument();
  });
});
