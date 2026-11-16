import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { InputSettings } from "@/lib/sheets/inputs";
import { InputCellDialog } from "./InputCellDialog";

/** The settings of an input cell (SH5.1): the form starts from what the cell states and hands back what was chosen. */

const START: InputSettings = { kind: null, required: false, cls: "filler", values: [], maxFiles: 5, prompt: null };

describe("the settings of an input cell", () => {
  it("offers the kinds, the prompt of a Yes/No cell with a reason, and hands the choice back", () => {
    const apply = vi.fn<(settings: InputSettings) => string | null>(() => null);
    const close = vi.fn();
    render(<InputCellDialog initial={START} cells={1} columnKind="Text" onApply={apply} onClose={close} />);

    fireEvent.change(screen.getByLabelText("Kind"), { target: { value: "boolean_reason" } });
    fireEvent.change(screen.getByLabelText("Prompt (shown above Yes and No)"), { target: { value: "Represented?" } });
    fireEvent.click(screen.getByLabelText("Required: the answer must be given before sending"));
    fireEvent.click(screen.getByLabelText("The reviewer"));
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    expect(apply).toHaveBeenCalledWith({ ...START, kind: "boolean_reason", required: true, cls: "reviewer", prompt: "Represented?" });
    expect(close).toHaveBeenCalled();
  });

  it("keeps the popup open with the editor's words when the settings are refused", () => {
    const close = vi.fn();
    render(
      <InputCellDialog
        initial={START}
        cells={3}
        columnKind="Text"
        onApply={() => "A list cell needs its values (at most 100), each different."}
        onClose={close}
      />,
    );

    fireEvent.change(screen.getByLabelText("Kind"), { target: { value: "picklist" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    expect(screen.getByRole("alert")).toHaveTextContent("A list cell needs its values");
    expect(close).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toHaveAccessibleName("Input cells (3)");
  });
});
