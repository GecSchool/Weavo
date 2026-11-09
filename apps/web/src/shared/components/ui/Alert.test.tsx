import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Alert, AlertDescription } from "./index";

describe("Alert", () => {
  it("위험 알림의 설명에도 위험 색상을 적용한다", () => {
    render(
      <Alert variant="destructive">
        <AlertDescription>Action required</AlertDescription>
      </Alert>,
    );
    const description = screen.getByText("Action required");
    expect(description).toHaveAttribute("data-alert-description");
    expect(description.parentElement).toHaveClass(
      "[&_[data-alert-description]]:text-destructive",
    );
  });
});
