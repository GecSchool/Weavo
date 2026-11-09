import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { AlertDialog } from "./index";

describe("Dialog", () => {
  it("확인 대화상자는 취소 요청을 부모에 전달한다", () => {
    const showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    });
    const close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute("open");
    });
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
      configurable: true,
      value: showModal,
    });
    Object.defineProperty(HTMLDialogElement.prototype, "close", {
      configurable: true,
      value: close,
    });
    function Example() {
      const [open, setOpen] = useState(true);
      return (
        <AlertDialog
          open={open}
          onOpenChange={setOpen}
          title="삭제할까요?"
          description="되돌릴 수 없습니다."
        />
      );
    }
    render(<Example />);
    const dialog = screen.getByRole("alertdialog", { name: "삭제할까요?" });
    expect(dialog).toHaveAccessibleDescription("되돌릴 수 없습니다.");
    fireEvent(
      dialog,
      new Event("cancel", { bubbles: false, cancelable: true }),
    );
    expect(close).toHaveBeenCalled();
    Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
    Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
  });
});
