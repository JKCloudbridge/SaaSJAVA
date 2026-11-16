import { describe, expect, it } from "vitest";
import {
  canonicalFillText,
  displayDoc,
  fromApiFill,
  missingRequired,
  setFillChoice,
  setFillValue,
  toWire,
} from "./fillModel";
import type { FillDoc, FillLimits } from "./fillTypes";
import { canStateInputs, kindWords, readInput, setInputs, type InputSettings } from "./inputs";
import { displayOf } from "./look/display";
import { NO_LOOK } from "./look/look";
import type { GridDoc } from "./types";

/**
 * The Yes/No cell with a reason and the author's choice of the kind of a cell (SH5.1, ADR-0263), on the browser's side:
 * the author sets the kind of selected cells and the text a cell holds becomes its prompt; a fill-in keeps an answer and
 * an optional reason; typing a yes or a no keeps the reason; Submit asks for the answer, never the reason. The server
 * decides all of it again.
 */

const LIMITS: FillLimits = { maxBytes: 1_000_000, maxRows: 1000, maxCellChars: 20 };

function sheet(): GridDoc {
  return {
    formatVersion: 2,
    dimension: 2,
    rowsMode: "FIXED",
    columns: [
      { id: "c1", label: "Represented", type: "text" },
      { id: "c2", label: "Notes", type: "text" },
    ],
    headerRows: [{ id: "h1" }],
    rows: [{ id: "r1" }, { id: "r2" }],
    cells: { "h1:c1": { value: "Question" }, "r1:c1": { value: "If not represented, give reason", style: { bold: true } } },
    merges: [],
    sheet: { key: "main", cells: { "r1:c1": { look: { fill: "#c6efce" } } }, inputs: {} },
  };
}

function settings(change: Partial<InputSettings>): InputSettings {
  return { kind: null, required: false, cls: "filler", values: [], maxFiles: 5, prompt: null, ...change };
}

function authored(): GridDoc {
  const made = setInputs(sheet(), ["r1:c1"], settings({ kind: "boolean_reason", required: true }), 100);
  if (!made.ok) throw new Error(made.error);
  return made.doc;
}

describe("the author sets the kind of a cell", () => {
  it("makes a cell with text a Yes/No cell with a reason whose prompt is that text, and keeps its look", () => {
    const doc = authored();

    expect(doc.cells["r1:c1"]).toBeUndefined();
    expect(doc.sheet?.inputs?.["r1:c1"]).toEqual({
      type: "boolean_reason",
      required: true,
      prompt: "If not represented, give reason",
    });
    expect(doc.sheet?.cells["r1:c1"]?.look).toEqual({ fill: "#c6efce" });
    expect(kindWords(doc, "r1", "c1")).toBe("Input cell: Yes/No with a reason");
    expect(readInput(doc, "r1", "c1").prompt).toBe("If not represented, give reason");
  });

  it("keeps the prompt when the settings are applied again, and writes only what differs from the column", () => {
    const again = setInputs(authored(), ["r1:c1"], settings({ kind: "boolean_reason" }), 100);
    const plain = setInputs(authored(), ["r1:c1"], settings({}), 100);

    expect(again.ok && again.doc.sheet?.inputs?.["r1:c1"]).toEqual({ type: "boolean_reason", prompt: "If not represented, give reason" });
    expect(plain.ok && plain.doc.sheet?.inputs?.["r1:c1"]).toBeUndefined();
  });

  it("sets a block of cells and leaves a header cell as it is", () => {
    const made = setInputs(sheet(), ["h1:c1", "r2:c1", "r2:c2"], settings({ kind: "attachment", maxFiles: 3, cls: "reviewer" }), 100);

    expect(made.ok).toBe(true);
    if (!made.ok) return;
    expect(made.doc.sheet?.inputs?.["r2:c1"]).toEqual({ type: "attachment", class: "reviewer", maxFiles: 3 });
    expect(made.doc.sheet?.inputs?.["r2:c2"]).toEqual({ type: "attachment", class: "reviewer", maxFiles: 3 });
    expect(made.doc.sheet?.inputs?.["h1:c1"]).toBeUndefined();
    expect(made.note).toContain("1 could not change");
  });

  it("refuses a list without values, a limit of files out of range, and a sheet in the old format", () => {
    const old: GridDoc = { ...sheet(), formatVersion: 1, sheet: undefined };

    expect(setInputs(sheet(), ["r2:c1"], settings({ kind: "picklist", values: [] }), 100).ok).toBe(false);
    expect(setInputs(sheet(), ["r2:c1"], settings({ kind: "attachment", maxFiles: 6 }), 100).ok).toBe(false);
    expect(canStateInputs(old)).toBe(false);
    expect(setInputs(old, ["r2:c1"], settings({ kind: "boolean" }), 100).ok).toBe(false);
  });

  it("draws the prompt and a quiet Yes / No in the template", () => {
    const doc = authored();

    const shown = displayOf(doc, "r1", "c1", "body", doc.cells["r1:c1"], NO_LOOK, undefined, true);

    expect(shown.text).toBe("Yes / No\nIf not represented, give reason");
    expect(shown.list).toBe("List: Yes, No");
  });
});

describe("a fill-in answers a Yes/No cell with a reason", () => {
  const empty = (): FillDoc => ({ formatVersion: 3, sheetKey: "main", rows: [] });

  it("keeps an answer and a reason, writes them in one form, and reads them back", () => {
    const grid = authored();
    const made = setFillChoice(grid, empty(), "r1", "c1", { reason: "Away", answer: false }, LIMITS);

    expect(made.ok).toBe(true);
    if (!made.ok) return;
    const wire = toWire(grid, made.fill) as { sheets: { rows: { cells: Record<string, unknown> }[] }[] };
    expect(wire.sheets[0]?.rows[0]?.cells.c1).toEqual({ answer: false, reason: "Away" });
    expect(fromApiFill(wire)).toEqual(made.fill);
    expect(canonicalFillText(grid, made.fill)).toContain("{\"answer\":false,\"reason\":\"Away\"}");
  });

  it("takes a typed yes or no as the answer and keeps the reason the cell holds", () => {
    const grid = authored();
    const reasoned = setFillChoice(grid, empty(), "r1", "c1", { reason: "Away" }, LIMITS);
    if (!reasoned.ok) throw new Error(reasoned.error);

    const typed = setFillValue(grid, reasoned.fill, "r1", "c1", "yes", LIMITS);
    const wrong = setFillValue(grid, reasoned.fill, "r1", "c1", "maybe", LIMITS);

    expect(typed.ok && typed.fill.rows[0]?.cells.c1).toEqual({ answer: true, reason: "Away" });
    expect(wrong.ok).toBe(false);
  });

  it("asks a required cell for its answer and never for the reason", () => {
    const grid = authored();
    const reasonOnly = setFillChoice(grid, empty(), "r1", "c1", { reason: "Away" }, LIMITS);
    const answered = setFillChoice(grid, empty(), "r1", "c1", { answer: true }, LIMITS);
    if (!reasonOnly.ok || !answered.ok) throw new Error("refused");

    expect(missingRequired(grid, reasonOnly.fill)).toEqual([{ rowId: "r1", columnId: "c1" }]);
    expect(missingRequired(grid, answered.fill)).toEqual([]);
  });

  it("clears a cell with neither, and refuses a reason that is too long or holds markup", () => {
    const grid = authored();

    const cleared = setFillChoice(grid, empty(), "r1", "c1", { reason: "" }, LIMITS);
    expect(cleared.ok && cleared.fill.rows).toEqual([]);
    expect(setFillChoice(grid, empty(), "r1", "c1", { reason: "x".repeat(21) }, LIMITS).ok).toBe(false);
    expect(setFillChoice(grid, empty(), "r1", "c1", { reason: "<b>bold" }, LIMITS).ok).toBe(false);
    expect(setFillChoice(grid, empty(), "r2", "c2", { answer: true }, LIMITS).ok).toBe(false);
  });

  it("draws the answer on the top line and the prompt followed by the reason below it", () => {
    const grid = authored();
    const made = setFillChoice(grid, empty(), "r1", "c1", { answer: true, reason: "Away" }, LIMITS);
    if (!made.ok) throw new Error(made.error);
    const doc = displayDoc(grid, made.fill);

    const shown = displayOf(doc, "r1", "c1", "body", doc.cells["r1:c1"], NO_LOOK, undefined, false);

    expect(shown.text).toBe("Yes\nIf not represented, give reason Away");

    const reasonOnly = setFillChoice(grid, empty(), "r1", "c1", { reason: "Away" }, LIMITS);
    if (!reasonOnly.ok) throw new Error(reasonOnly.error);
    const unanswered = displayDoc(grid, reasonOnly.fill);
    expect(displayOf(unanswered, "r1", "c1", "body", unanswered.cells["r1:c1"], NO_LOOK, undefined, false).text)
      .toBe("Yes / No\nIf not represented, give reason Away");
  });
});
