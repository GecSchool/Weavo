import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Switch } from "./index";

describe("Switch", () => {
  it("스위치는 이름과 상태를 노출하고 변경과 비활성을 지원한다", () => {
    const onChange = vi.fn();
    render(
      <>
        <label>
          <Switch onChange={onChange} /> Notifications
        </label>
        <Switch aria-label="Unavailable" disabled />
      </>,
    );
    const toggle = screen.getByRole("switch", { name: "Notifications" });
    expect(toggle).not.toBeChecked();
    fireEvent.click(toggle);
    expect(toggle).toBeChecked();
    expect(onChange).toHaveBeenCalledOnce();
    expect(screen.getByRole("switch", { name: "Unavailable" })).toBeDisabled();
  });
});
