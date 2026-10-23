import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Modal } from "./Modal";

describe("Modal", () => {
  it("is a named dialog that puts the focus on its first field", () => {
    render(
      <Modal title="New thing" onClose={vi.fn()}>
        <input aria-label="Name" />
      </Modal>,
    );

    expect(screen.getByRole("dialog", { name: "New thing" })).toHaveAttribute("aria-modal", "true");
    expect(screen.getByLabelText("Name")).toHaveFocus();
  });

  it("closes on Escape and on the close button, but not on a click beside it", () => {
    const onClose = vi.fn();
    render(
      <Modal title="New thing" onClose={onClose} testId="veil">
        <input aria-label="Name" />
      </Modal>,
    );

    fireEvent.click(screen.getByTestId("veil"));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("keeps the Tab key inside the popup", () => {
    render(
      <Modal title="New thing" onClose={vi.fn()}>
        <input aria-label="Name" />
        <button type="button">Save</button>
      </Modal>,
    );
    screen.getByRole("button", { name: "Save" }).focus();

    fireEvent.keyDown(document, { key: "Tab" });

    expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
  });
});
