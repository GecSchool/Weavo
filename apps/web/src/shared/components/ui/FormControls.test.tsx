import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Select, Checkbox } from "./index";

describe("FormControls", () => {
  it("선택값과 체크박스를 표시한다", () => {
    render(
      <>
        <label htmlFor="type">타입</label>
        <Select
          id="type"
          defaultValue="text"
          options={[
            { value: "text", label: "문자" },
            { value: "number", label: "숫자" },
          ]}
        />
        <label>
          <Checkbox /> 알림
        </label>
      </>,
    );
    fireEvent.click(screen.getByLabelText("알림"));
    expect(screen.getByRole("combobox", { name: "타입" })).toHaveTextContent(
      "문자",
    );
    expect(screen.getByLabelText("알림")).toBeChecked();
  });
});
