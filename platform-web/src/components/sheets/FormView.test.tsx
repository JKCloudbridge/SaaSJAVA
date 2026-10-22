import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LIMITS, oneDimension, sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc, Problem, SheetLimits } from "@/lib/sheets/types";
import { FormView } from "./FormView";
import { useSheetEditor, type SheetEditor } from "./useSheetEditor";

function Host({
  initial = sampleDoc(),
  readOnly = false,
  problems = [],
  canChangeShape = false,
  limits = LIMITS,
  onEditor,
}: {
  initial?: GridDoc;
  readOnly?: boolean;
  problems?: Problem[];
  canChangeShape?: boolean;
  limits?: SheetLimits;
  onEditor: (editor: SheetEditor) => void;
}) {
  const editor = useSheetEditor(initial, limits);
  onEditor(editor);
  return <FormView editor={editor} readOnly={readOnly} problems={problems} canChangeShape={canChangeShape} />;
}

function draw(props: Omit<Parameters<typeof Host>[0], "onEditor"> = {}) {
  let latest: SheetEditor | undefined;
  render(<Host {...props} onEditor={(editor) => (latest = editor)} />);
  return { doc: (): GridDoc => (latest as SheetEditor).doc };
}

const type = (name: string | RegExp, value: string) => {
  const box = screen.getByRole("textbox", { name });
  fireEvent.change(box, { target: { value } });
  fireEvent.blur(box);
};
const spin = (name: string, value: string) => {
  const box = screen.getByRole("spinbutton", { name });
  fireEvent.change(box, { target: { value } });
  fireEvent.blur(box);
};

describe("the form view", () => {
  it("lists the columns with what each holds, and the header rows and the rows", () => {
    draw();

    expect(screen.getByRole("textbox", { name: "Label of column 1" })).toHaveValue("Item");
    expect(screen.getByRole("combobox", { name: "What column 2 holds" })).toHaveValue("number");
    expect(screen.getByRole("checkbox", { name: "The filler must fill column 2" })).toBeChecked();
    expect(screen.getByRole("textbox", { name: "Values of column 3, one on each line" })).toHaveValue("Pass\nFail");
    expect(screen.getByRole("textbox", { name: "Label of row 2" })).toHaveValue("Second");
    expect(screen.getAllByRole("listitem", { name: /^Header row/ })).toHaveLength(2);
  });

  it("changes a label when the person leaves the box, as one step", () => {
    const { doc } = draw();

    type("Label of column 1", "Thing");

    expect(doc().columns[0]?.label).toBe("Thing");
  });

  it("changes the type and drops what no longer fits", () => {
    const { doc } = draw();

    fireEvent.change(screen.getByRole("combobox", { name: "What column 3 holds" }), { target: { value: "boolean" } });

    expect(doc().columns[2]).toEqual({ id: "c3", label: "Result", type: "boolean" });
    expect(screen.queryByRole("textbox", { name: /Values of column 3/ })).toBeNull();
  });

  it("sets the values of a list one on each line, and a blank line is nothing", () => {
    const { doc } = draw();

    type(/Values of column 3/, "Pass\n\n Fail \nSkip");

    expect(doc().columns[2]?.picklist).toEqual({ values: ["Pass", "Fail", "Skip"] });
  });

  it("offers a starting value that fits the type of the column", () => {
    const { doc } = draw();

    type("Starts as, in column 2", "12.5");
    expect(doc().columns[1]?.default).toBe("12.5");
    fireEvent.change(screen.getByRole("combobox", { name: "Starts as, in column 3" }), { target: { value: "Fail" } });
    expect(doc().columns[2]?.default).toBe("Fail");
    fireEvent.change(screen.getByRole("combobox", { name: "Starts as, in column 3" }), { target: { value: "" } });
    expect(doc().columns[2]?.default).toBeUndefined();
  });

  it("marks a column the filler must fill", () => {
    const { doc } = draw();

    fireEvent.click(screen.getByRole("checkbox", { name: "The filler must fill column 1" }));

    expect(doc().columns[0]?.required).toBe(true);
  });

  it("sets a width in pixels inside the range of the format", () => {
    const { doc } = draw();

    spin("Width of column 2, in pixels", "5000");
    expect(doc().columns[1]?.width).toBe(1000);
    spin("Width of column 2, in pixels", "10");
    expect(doc().columns[1]?.width).toBe(40);
    spin("Width of column 2, in pixels", "");
    expect(doc().columns[1]?.width).toBeUndefined();
  });

  it("adds, moves and removes columns", () => {
    const { doc } = draw();

    fireEvent.click(screen.getByRole("button", { name: "Add a column after column 1" }));
    expect(doc().columns).toHaveLength(4);
    expect(doc().columns[1]?.label).toBe("Column 4");
    fireEvent.click(screen.getByRole("button", { name: "Move column 2 up" }));
    expect(doc().columns[0]?.label).toBe("Column 4");
    fireEvent.click(screen.getByRole("button", { name: "Remove column 1" }));
    expect(doc().columns.map((c) => c.id)).toEqual(["c1", "c2", "c3"]);
    expect(screen.getByRole("button", { name: "Move column 1 up" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Move column 3 down" })).toBeDisabled();
  });

  it("does not let the last column go", () => {
    const only = sampleDoc().columns[0];
    draw({ initial: { ...sampleDoc(), columns: only ? [only] : [], cells: {} } });

    expect(screen.getByRole("button", { name: "Remove column 1" })).toBeDisabled();
  });

  it("stops offering to add at the limit the API stated", () => {
    draw({ limits: { ...LIMITS, maxColumns: 3, maxRows: 3, maxHeaderRows: 2 } });

    expect(screen.getByRole("button", { name: "Add a column" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Add a row" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Add a header row" })).toBeDisabled();
  });

  it("adds, labels, moves and removes rows and header rows", () => {
    const { doc } = draw();

    fireEvent.click(screen.getByRole("button", { name: "Add a row" }));
    expect(doc().rows).toHaveLength(4);
    type("Label of row 4", "Fourth");
    expect(doc().rows[3]?.label).toBe("Fourth");
    fireEvent.click(screen.getByRole("button", { name: "Move row 4 up" }));
    expect(doc().rows[2]?.label).toBe("Fourth");
    fireEvent.click(screen.getByRole("button", { name: "Remove row 1" }));
    expect(doc().rows).toHaveLength(3);
    fireEvent.click(screen.getByRole("button", { name: "Add a header row" }));
    expect(doc().headerRows).toHaveLength(3);
    spin("Height of row 1, in pixels", "60");
    expect(doc().rows[0]?.height).toBe(60);
    fireEvent.click(screen.getByRole("button", { name: "Remove header row 3" }));
    expect(doc().headerRows).toHaveLength(2);
  });

  it("says the filler adds the rows where it does, and offers no named row", () => {
    draw({ initial: oneDimension() });

    expect(screen.getByText(/The filler adds the rows\./)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add a row" })).toBeNull();
    expect(screen.getByText(/names none/)).toBeInTheDocument();
  });

  it("lets the person choose how the rows come about only where the API allows it", () => {
    const { doc } = draw({ initial: { ...sampleDoc(), rows: [], cells: {} }, canChangeShape: true });

    fireEvent.change(screen.getByRole("combobox", { name: "Rows" }), { target: { value: "1" } });
    expect(doc().dimension).toBe(1);
    expect(screen.queryByRole("combobox", { name: "Rows can be" })).toBeNull();
    fireEvent.change(screen.getByRole("combobox", { name: "Rows" }), { target: { value: "2" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Rows can be" }), { target: { value: "FIXED_AND_REPEATABLE" } });
    expect(doc().rowsMode).toBe("FIXED_AND_REPEATABLE");
  });

  it("states the shape without a control when it cannot change", () => {
    draw({ canChangeShape: false });

    expect(screen.queryByRole("combobox", { name: "Rows" })).toBeNull();
    expect(screen.getByText(/The sheet names the rows; the filler adds none\./)).toBeInTheDocument();
    expect(screen.getByText(/chosen with the first version/)).toBeInTheDocument();
  });

  it("shows the words and the place of a refusal next to the field", () => {
    draw({
      problems: [
        { place: "grid.columns[0].label", message: "A text contains markup or a control character." },
        { place: "grid.columns[2].picklist", message: "A list has the same value twice." },
        { place: "grid.columns[1].default", message: "A number column holds digits, with an optional sign and decimals." },
      ],
    });

    const first = within(screen.getByRole("listitem", { name: "Column 1" }));
    expect(first.getByRole("alert")).toHaveTextContent("markup or a control character");
    expect(first.getByRole("textbox", { name: "Label of column 1" })).toBeInvalid();
    expect(within(screen.getByRole("listitem", { name: "Column 3" })).getByRole("alert")).toHaveTextContent("same value twice");
    expect(within(screen.getByRole("listitem", { name: "Column 2" })).getByRole("alert")).toHaveTextContent("holds digits");
  });

  it("is only to be read when read-only", () => {
    draw({ readOnly: true });

    expect(screen.getByRole("textbox", { name: "Label of column 1" })).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "What column 2 holds" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: /Remove|Add a|Move/ })).toBeNull();
  });
});
