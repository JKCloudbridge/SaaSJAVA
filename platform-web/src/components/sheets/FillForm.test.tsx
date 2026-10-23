import { fireEvent, render, screen, within } from "@testing-library/react";
import { useEffect } from "react";
import { describe, expect, it } from "vitest";
import type { FillDoc, FillLimits } from "@/lib/sheets/fillTypes";
import { oneDimension, sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc } from "@/lib/sheets/types";
import { FillForm } from "./FillForm";
import { useFillEditor, type FillEditor } from "./useFillEditor";

const LIMITS: FillLimits = { maxBytes: 1_000_000, maxRows: 50, maxCellChars: 1000 };
const EMPTY: FillDoc = { formatVersion: 1, rows: [] };

function Host({
  grid,
  initial = EMPTY,
  readOnly = false,
  problems = new Map<string, readonly string[]>(),
  onEditor,
}: {
  grid: GridDoc;
  initial?: FillDoc;
  readOnly?: boolean;
  problems?: ReadonlyMap<string, readonly string[]>;
  onEditor?: (editor: FillEditor) => void;
}) {
  const editor = useFillEditor(grid, initial, LIMITS);
  useEffect(() => {
    onEditor?.(editor);
  });
  return <FillForm editor={editor} readOnly={readOnly} problems={problems} />;
}

describe("the form view of a fill-in", () => {
  it("draws a card for each row of the sheet with a box for each input column and text for what the sheet fixes", () => {
    render(<Host grid={sampleDoc()} />);

    const cards = screen.getAllByTestId("fill-row");
    expect(cards).toHaveLength(3);
    const first = within(cards[0] as HTMLElement);
    expect(first.getByText("First")).toBeInTheDocument(); // the legend
    expect(first.getByText("Fire exit")).toBeInTheDocument(); // fixed text, no box
    expect(first.queryByRole("textbox", { name: /Item/ })).not.toBeInTheDocument();
    expect(first.getByRole("textbox", { name: /Amount/ })).toBeInTheDocument();
    expect(first.getByRole("combobox", { name: "Result" })).toBeInTheDocument();
    expect(first.getByText("(required)")).toBeInTheDocument();
  });

  it("keeps what is typed until the box is left, then holds it as the value of the cell", () => {
    let seen: FillEditor | undefined;
    render(<Host grid={sampleDoc()} onEditor={(editor) => (seen = editor)} />);
    const box = within(screen.getAllByTestId("fill-row")[0] as HTMLElement).getByRole("textbox", { name: /Amount/ });

    fireEvent.change(box, { target: { value: "12.5" } });
    expect(seen?.fill.rows).toEqual([]);
    fireEvent.blur(box);

    expect(seen?.fill.rows).toEqual([{ id: "r1", cells: { c2: "12.5" } }]);
    expect(seen?.dirty).toBe(true);
  });

  it("tells a value the model refuses in words under the box and keeps what was typed", () => {
    let seen: FillEditor | undefined;
    render(<Host grid={sampleDoc()} onEditor={(editor) => (seen = editor)} />);
    const box = within(screen.getAllByTestId("fill-row")[0] as HTMLElement).getByRole("textbox", { name: /Amount/ });

    fireEvent.change(box, { target: { value: "12a" } });
    fireEvent.blur(box);

    expect(screen.getByRole("alert")).toHaveTextContent("A number column holds digits");
    expect(box).toHaveValue("12a");
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(seen?.fill.rows).toEqual([]);
  });

  it("makes a choice at once", () => {
    let seen: FillEditor | undefined;
    render(<Host grid={sampleDoc()} onEditor={(editor) => (seen = editor)} />);

    fireEvent.change(within(screen.getAllByTestId("fill-row")[1] as HTMLElement).getByRole("combobox", { name: "Result" }), {
      target: { value: "Fail" },
    });

    expect(seen?.fill.rows).toEqual([{ id: "r2", cells: { c3: "Fail" } }]);
  });

  it("offers yes and no for a yes or no column and keeps a no", () => {
    let seen: FillEditor | undefined;
    const grid: GridDoc = { ...sampleDoc(), columns: [...sampleDoc().columns, { id: "c4", label: "Done", type: "boolean" }] };
    render(<Host grid={grid} onEditor={(editor) => (seen = editor)} />);

    fireEvent.change(within(screen.getAllByTestId("fill-row")[2] as HTMLElement).getByRole("combobox", { name: "Done" }), {
      target: { value: "No" },
    });

    expect(seen?.fill.rows).toEqual([{ id: "r3", cells: { c4: false } }]);
  });

  it("adds a row where the sheet lets the filler and removes only a row the filler added", () => {
    render(<Host grid={oneDimension()} />);
    expect(screen.getByText("There are no rows yet. Add the first row.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Add a row" }));
    fireEvent.click(screen.getByRole("button", { name: "Add a row" }));

    expect(screen.getAllByTestId("fill-row")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Remove row 1" }));
    expect(screen.getAllByTestId("fill-row")).toHaveLength(1);
  });

  it("offers no way to add a row where the sheet names its rows", () => {
    render(<Host grid={sampleDoc()} />);

    expect(screen.queryByRole("button", { name: "Add a row" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
  });

  it("counts what is still to fill in before the fill-in can be sent", () => {
    render(<Host grid={sampleDoc()} initial={{ formatVersion: 1, rows: [{ id: "r1", cells: { c2: "1" } }] }} />);

    expect(screen.getByTestId("fill-missing")).toHaveTextContent("2 values are still to fill in");
  });

  it("draws no box and no button when it may not be changed, and shows the values as text", () => {
    render(<Host grid={sampleDoc()} readOnly initial={{ formatVersion: 1, rows: [{ id: "r1", cells: { c2: "7", c3: "Pass" } }] }} />);

    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByTestId("fill-missing")).not.toBeInTheDocument();
    const first = within(screen.getAllByTestId("fill-row")[0] as HTMLElement);
    expect(first.getByText("7")).toBeInTheDocument();
    expect(first.getByText("Pass")).toBeInTheDocument();
  });

  it("shows what the server refused under the box of its cell", () => {
    render(
      <Host
        grid={sampleDoc()}
        problems={new Map([["r2:c2", ["A number column holds digits, with an optional sign and decimals."]]])}
      />,
    );

    const second = within(screen.getAllByTestId("fill-row")[1] as HTMLElement);
    expect(second.getByRole("alert")).toHaveTextContent("A number column holds digits");
  });
});
