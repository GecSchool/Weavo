import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RadioGroup } from "./index";

describe("RadioGroup", () => {
  it("라디오 그룹은 하나의 값을 선택하고 비활성 항목을 구분한다", () => {
    const onChange = vi.fn();
    render(
      <RadioGroup
        name="location"
        legend="Field location"
        defaultValue="list"
        onValueChange={onChange}
        options={[
          { value: "list", label: "List" },
          { value: "detail", label: "Detail" },
          { value: "missing", label: "Unavailable", disabled: true },
        ]}
      />,
    );
    expect(
      screen.getByRole("group", { name: "Field location" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "List" })).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: "Detail" }));
    expect(onChange).toHaveBeenCalledWith("detail");
    expect(screen.getByRole("radio", { name: "Detail" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "List" })).not.toBeChecked();
    expect(screen.getByRole("radio", { name: "Unavailable" })).toBeDisabled();
  });
});
