import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { FillDoc, FillLimits } from "@/lib/sheets/fillTypes";
import { sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc } from "@/lib/sheets/types";
import { FillForm } from "./FillForm";
import { useFillEditor } from "./useFillEditor";

// The files themselves are the control of its own tests; here only the words around it matter.
vi.mock("./FillAttachments", () => ({ FillAttachments: () => <div data-testid="attach-control" /> }));

const LIMITS: FillLimits = { maxBytes: 1_000_000, maxRows: 50, maxCellChars: 1000 };
const EMPTY: FillDoc = { formatVersion: 3, sheetKey: "main", rows: [] };

/** A sheet in the new format whose cell r1:c2 is an attachment cell with a prompt (SH5.1). */
function grid(): GridDoc {
  const base = sampleDoc();
  return {
    ...base,
    formatVersion: 2,
    sheet: {
      key: "main",
      cells: {},
      inputs: { "r1:c2": { type: "attachment", prompt: "Please attach the evidence" } },
    },
  };
}

function Host({ readOnly = false }: { readOnly?: boolean }) {
  const editor = useFillEditor(grid(), EMPTY, LIMITS);
  return (
    <FillForm
      editor={editor}
      readOnly={readOnly}
      problems={new Map()}
      attach={{ object: "Fill", recordId: "f1", beforeRemove: async () => true, afterRemove: async () => undefined }}
    />
  );
}

describe("an attachment cell with a prompt in the form view (SH5.1)", () => {
  it("shows the prompt as the label of the attach control", () => {
    render(<Host />);

    const first = within(screen.getAllByTestId("fill-row")[0] as HTMLElement);
    expect(first.getByText("Please attach the evidence")).toBeInTheDocument();
    expect(first.getByTestId("attach-control")).toBeInTheDocument();
  });

  it("shows the prompt with the number of files when it is read only", () => {
    render(<Host readOnly />);

    const first = within(screen.getAllByTestId("fill-row")[0] as HTMLElement);
    expect(first.getByText(/Please attach the evidence/)).toBeInTheDocument();
    expect(first.queryByTestId("attach-control")).not.toBeInTheDocument();
  });
});
