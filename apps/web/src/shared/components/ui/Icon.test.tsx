import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Icon, IconButton } from "./index";

describe("Icon", () => {
  it("아이콘 버튼은 동작 이름을 제공하고 아이콘은 중복 읽기에서 제외한다", () => {
    render(<IconButton icon="copy" label="Copy API URL" />);
    const button = screen.getByRole("button", { name: "Copy API URL" });
    expect(button.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });
  it("의미를 가진 단독 아이콘은 레이블을 노출한다", () => {
    render(<Icon name="alert" label="Warning" />);
    expect(screen.getByRole("img", { name: "Warning" })).toBeInTheDocument();
  });
});
