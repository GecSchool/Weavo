import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Tooltip, TooltipProvider, IconButton } from "./index";

describe("Tooltip", () => {
  it("툴팁은 키보드 포커스에서 열리고 Escape로 닫힌다", async () => {
    render(
      <TooltipProvider delayDuration={0}>
        <Tooltip content="Copy the endpoint">
          <IconButton icon="copy" label="Copy API URL" />
        </Tooltip>
      </TooltipProvider>,
    );
    const button = screen.getByRole("button", { name: "Copy API URL" });
    fireEvent.focus(button);
    expect(await screen.findByRole("tooltip")).toHaveTextContent(
      "Copy the endpoint",
    );
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });
});
