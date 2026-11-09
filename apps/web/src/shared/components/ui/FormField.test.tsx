import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FormField, Input } from "./index";

describe("FormField", () => {
  it("폼 필드의 레이블과 오류 설명을 입력에 연결한다", () => {
    render(
      <FormField
        id="email"
        label="이메일"
        hint="계정에 사용할 주소"
        error="이메일을 확인하세요"
        required
      >
        {(props) => <Input type="email" {...props} />}
      </FormField>,
    );
    const input = screen.getByRole("textbox", { name: "이메일" });
    expect(input).toBeRequired();
    expect(input).toBeInvalid();
    expect(input).toHaveAccessibleDescription(
      "계정에 사용할 주소 이메일을 확인하세요",
    );
  });
});
