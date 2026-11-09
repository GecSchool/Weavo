import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Button, Badge } from "./index";

describe("Button", () => {
  it("버튼은 기본적으로 폼을 제출하지 않고 비활성을 지원한다", () => {
    const submit = vi.fn();
    render(
      <form onSubmit={submit}>
        <Button>취소</Button>
        <Button disabled>저장</Button>
      </form>,
    );
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(submit).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
  });
  it("작은 버튼의 글자 크기와 전경색 토큰을 함께 유지한다", () => {
    render(<Button size="sm">Small</Button>);
    const button = screen.getByRole("button", { name: "Small" });
    expect(button).toHaveClass("text-primary-foreground");
    expect(button).toHaveClass("text-[length:var(--text-body-xs)]");
    expect(button).toHaveClass("[line-height:var(--leading-body-xs)]");
  });
  it("outline 버튼과 배지가 투명 테두리를 덮어쓴다", () => {
    render(
      <>
        <Button variant="outline">Outline</Button>
        <Badge variant="outline">Pending</Badge>
      </>,
    );
    for (const element of [
      screen.getByRole("button", { name: "Outline" }),
      screen.getByText("Pending"),
    ]) {
      expect(element).toHaveClass("border-border");
      expect(element).not.toHaveClass("border-transparent");
    }
  });
});
