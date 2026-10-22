import { describe, expect, it } from "vitest";
import { canonicalText, fromApi, sameDocument, toApi } from "./adapter";
import { at, contains, displayRows, indexesOf, mergeRects, overlaps } from "./geometry";
import { LIMITS, oneDimension, sampleDoc } from "./fixtures";
import {
  addColumn,
  addRow,
  clearCells,
  mergeRect,
  pasteBlock,
  removeColumn,
  removeRow,
  setCellKind,
  setCellText,
  setFontSize,
  toggleStyle,
  unmergeAt,
  updateColumn,
} from "./gridModel";
import { hintsFor, problemsAt, problemsFromFields } from "./hints";
import * as history from "./history";
import { newId } from "./ids";
import { statusTag } from "./tags";
import type { GridDoc, OpResult } from "./types";
import { isRealDate, unsafeText, valueProblem } from "./values";

describe("history", () => {
  it("undoes and redoes exactly, and a new edit forgets what was undone", () => {
    let h = history.start("a");
    h = history.push(h, "b");
    h = history.push(h, "c");

    expect(history.canUndo(h)).toBe(true);
    h = history.undo(h);
    expect(h.present).toBe("b");
    expect(history.canRedo(h)).toBe(true);
    expect(history.redo(h).present).toBe("c");
    expect(history.push(h, "d").future).toEqual([]);
    expect(history.undo(history.start("only")).present).toBe("only");
    expect(history.redo(history.start("only")).present).toBe("only");
  });

  it("does not make a step of an edit that changed nothing, and forgets the oldest past the limit", () => {
    const h = history.start("a");
    expect(history.push(h, "a")).toBe(h);
    let long = h;
    for (let i = 0; i < 10; i++) {
      long = history.push(long, `v${i}`, 3);
    }
    expect(long.past).toHaveLength(3);
  });
});

describe("values", () => {
  it("accepts a number only as digits, a date only if it exists, a yes or no only as a boolean", () => {
    const number = { type: "number" as const };
    expect(valueProblem(number, "-12.5")).toBeNull();
    for (const bad of ["1,5", "1e5", "+5", ".5", "12.", "", "٣", "1".repeat(39), 12]) {
      expect(valueProblem(number, bad), String(bad)).not.toBeNull();
    }
    expect(isRealDate("2026-02-28")).toBe(true);
    expect(isRealDate("2026-02-30")).toBe(false);
    expect(isRealDate("2026-2-3")).toBe(false);
    expect(valueProblem({ type: "boolean" }, true)).toBeNull();
    expect(valueProblem({ type: "boolean" }, "yes")).not.toBeNull();
    expect(valueProblem({ type: "picklist", picklist: { values: ["a"] } }, "a")).toBeNull();
    expect(valueProblem({ type: "picklist", picklist: { values: ["a"] } }, "b")).not.toBeNull();
    expect(valueProblem({ type: "text" }, 5)).not.toBeNull();
  });

  it("refuses markup and control characters but not a comparison sign with a space", () => {
    for (const bad of ["<b>x", "</p>", "<!--", "<?x", "a\u0000", "a\u001b", "a\u007f", "a\u0085", "a ", "a‮", "a⁦"]) {
      expect(unsafeText(bad), JSON.stringify(bad)).toBe(true);
    }
    for (const fine of ["5 < 6", "a > b", "tab\there", "two\nlines", "café", "日本語"]) {
      expect(unsafeText(fine), fine).toBe(false);
    }
  });
});

describe("identifiers", () => {
  it("are new, of the server's shape, and never one already used", () => {
    const taken = new Set<string>();
    for (let i = 0; i < 500; i++) {
      const id = newId("c", taken);
      expect(id).toMatch(/^c[a-z0-9]{10}$/);
      expect(taken.has(id)).toBe(false);
      taken.add(id);
    }
  });
});

describe("the adapter", () => {
  it("reads an answer into a complete document and drops what it does not know", () => {
    const doc = fromApi({
      formatVersion: 1,
      dimension: 2,
      rowsMode: "FIXED",
      columns: [{ id: "c1", label: "A", type: "text", unknown: 1 }, { id: "bad", type: "nope" }, "text"],
      rows: [{ id: "r1" }],
      cells: { "r1:c1": { value: "x", extra: true }, "nope:c1": { value: "gone" } },
      merges: [{ from: "r1:c1", to: "r1:c1" }, { from: 1 }],
    });

    expect(doc.columns).toEqual([{ id: "c1", label: "A", type: "text" }]);
    expect(doc.headerRows).toEqual([]);
    expect(doc.cells).toEqual({ "r1:c1": { value: "x" } });
    expect(doc.merges).toEqual([{ from: "r1:c1", to: "r1:c1" }]);
  });

  it("reads nothing as an empty sheet that is well formed", () => {
    expect(fromApi(undefined)).toMatchObject({ dimension: 2, rowsMode: "FIXED", columns: [], rows: [], cells: {} });
    expect(fromApi({ dimension: 1 })).toMatchObject({ dimension: 1, rowsMode: "REPEATABLE" });
  });

  it("writes a document back as it read it: the round trip is the identity", () => {
    for (const doc of [sampleDoc(), oneDimension()]) {
      expect(fromApi(JSON.parse(JSON.stringify(toApi(doc))))).toEqual(doc);
    }
  });

  it("reads the same meaning from different spellings and gives one canonical text", () => {
    const plain = sampleDoc();
    const padded: GridDoc = {
      ...sampleDoc(),
      columns: [{ id: "c1", label: "Item", type: "text", width: 160, required: false, default: null, picklist: null }, ...sampleDoc().columns.slice(1)],
      cells: {
        ...sampleDoc().cells,
        "r1:c1": { value: "Fire exit", kind: "fixed", style: { bold: false, fontSize: "NORMAL" } },
        "r1:c2": { kind: "input" },
        "h2:c1": {},
      },
    };

    expect(canonicalText(padded)).toBe(canonicalText(plain));
    expect(sameDocument(padded, plain)).toBe(true);
  });

  it("tells a changed document from an unchanged one, whatever the order of keys and merges", () => {
    const start = sampleDoc();
    const rows = displayRows(start);
    const a = ok(mergeRect(start, rows, { r0: 0, c0: 0, r1: 0, c1: 1 }, LIMITS));
    const b = ok(mergeRect(a, rows, { r0: 2, c0: 0, r1: 2, c1: 1 }, LIMITS));
    const reordered: GridDoc = { ...b, merges: [...b.merges].reverse() };

    expect(sameDocument(b, reordered)).toBe(true);
    expect(sameDocument(a, b)).toBe(false);
    expect(sameDocument(start, ok(setCellText(start, "r1", "c2", "x", LIMITS)))).toBe(false);
  });

  it("does not change a document by sending it", () => {
    const start = sampleDoc();
    const copy = JSON.stringify(start);
    toApi(start);
    canonicalText(start);

    expect(JSON.stringify(start)).toBe(copy);
  });
});

describe("hints", () => {
  it("say, in the places and words of the server, what would be refused", () => {
    const doc: GridDoc = {
      ...sampleDoc(),
      columns: [
        { id: "c1", label: "<b>x", type: "text" },
        { id: "c2", label: "Amount", type: "number", default: "twelve" },
        { id: "c3", label: "Result", type: "picklist", picklist: { values: ["a", "a", " "] } },
      ],
    };
    const hints = hintsFor(doc, LIMITS);

    expect(problemsAt(hints, "grid.columns[0]")).toEqual(["A text contains markup or a control character."]);
    expect(problemsAt(hints, "grid.columns[1].default")).toEqual(["A number column holds digits, with an optional sign and decimals."]);
    expect(problemsAt(hints, "grid.columns[2]")).toEqual(["A list has the same value twice.", "A list value cannot be empty."]);
    expect(hintsFor(sampleDoc(), LIMITS)).toEqual([]);
  });

  it("know the limits the API stated and no others", () => {
    const hints = hintsFor(sampleDoc(), { ...LIMITS, maxColumns: 2, maxRows: 2, maxHeaderRows: 1 });

    expect(hints.map((h) => h.place)).toEqual(["grid.columns", "grid.rows", "grid.headerRows"]);
  });

  it("turn the fields of an answer into problems", () => {
    expect(problemsFromFields({ "grid.columns[0].label": ["a", "b"], grid: ["c"] })).toEqual([
      { place: "grid.columns[0].label", message: "a" },
      { place: "grid.columns[0].label", message: "b" },
      { place: "grid", message: "c" },
    ]);
    expect(problemsFromFields(undefined)).toEqual([]);
  });
});

describe("the words of a status", () => {
  it("name the active version Active and show an unknown status as it is", () => {
    expect(statusTag("ACTIVE")).toEqual({ text: "Active", tone: "active" });
    expect(statusTag("SUPERSEDED").text).toBe("Replaced");
    expect(statusTag("IN_REVIEW").text).toBe("In review");
    expect(statusTag("SOMETHING").text).toBe("SOMETHING");
  });
});

// ---- properties over many random edits: a seeded generator, so a failure can be repeated ----

function ok(result: OpResult): GridDoc {
  if (!result.ok) {
    throw new Error(result.error);
  }
  return result.doc;
}

function random(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** One random edit of the document; an edit the model refuses changes nothing. */
function randomEdit(doc: GridDoc, next: () => number): GridDoc {
  const rows = displayRows(doc);
  if (rows.length === 0 || doc.columns.length === 0) {
    return doc;
  }
  const pick = <T>(list: T[]): T => list[Math.floor(next() * list.length)] as T;
  const r = Math.floor(next() * rows.length);
  const c = Math.floor(next() * doc.columns.length);
  const r1 = Math.min(rows.length - 1, r + Math.floor(next() * 3));
  const c1 = Math.min(doc.columns.length - 1, c + Math.floor(next() * 3));
  const key = `${at(rows, r).id}:${at(doc.columns, c).id}`;
  const attempts: OpResult[] = [
    setCellText(doc, at(rows, r).id, at(doc.columns, c).id, pick(["", "a", "Label", "x y"]), LIMITS),
    mergeRect(doc, rows, { r0: r, c0: c, r1, c1 }, LIMITS),
    unmergeAt(doc, rows, r, c),
    toggleStyle(doc, [key], pick(["bold", "italic"] as const)),
    setFontSize(doc, [key], pick(["SMALL", "NORMAL", "LARGE", "TITLE"] as const)),
    setCellKind(doc, at(rows, r).id, at(doc.columns, c).id, pick(["fixed", "input"] as const)),
    addColumn(doc, null, LIMITS),
    addRow(doc, pick(["body", "header"] as const), null, LIMITS),
    removeColumn(doc, at(doc.columns, c).id),
    removeRow(doc, at(rows, r).id),
    updateColumn(doc, at(doc.columns, c).id, { type: pick(["text", "number", "date", "boolean"] as const) }, LIMITS),
    clearCells(doc, rows, { r0: r, c0: c, r1, c1 }),
    ok2(pasteBlock(doc, rows, r, c, [["p", "q"], ["r", "s"]], LIMITS).doc),
  ];
  const result = pick(attempts);
  return result.ok ? result.doc : doc;
}

function ok2(doc: GridDoc): OpResult {
  return { ok: true, doc };
}

function noMergeOverlaps(doc: GridDoc): boolean {
  const rects = mergeRects(doc, indexesOf(doc, displayRows(doc)));
  return rects.every((a, i) => rects.every((b, j) => i === j || !overlaps(a, b)));
}

function noContentUnderAMerge(doc: GridDoc): boolean {
  const rows = displayRows(doc);
  const rects = mergeRects(doc, indexesOf(doc, rows));
  return Object.keys(doc.cells).every((key) => {
    const [rowId, columnId] = key.split(":");
    const r = rows.findIndex((row) => row.id === rowId);
    const c = doc.columns.findIndex((column) => column.id === columnId);
    return !rects.some((m) => contains(m, r, c) && !(m.r0 === r && m.c0 === c));
  });
}

describe("many random edits", () => {
  it("never leave two merged areas on one cell, never leave content under a merge, and always match the server's form", () => {
    for (let seed = 1; seed <= 40; seed++) {
      const next = random(seed);
      let doc = sampleDoc();
      for (let step = 0; step < 60; step++) {
        doc = randomEdit(doc, next);
        expect(noMergeOverlaps(doc), `seed ${seed} step ${step}: overlap`).toBe(true);
        expect(noContentUnderAMerge(doc), `seed ${seed} step ${step}: content under a merge`).toBe(true);
        // A document the model made is already in the one form the server writes.
        expect(fromApi(JSON.parse(JSON.stringify(doc)))).toEqual(doc);
      }
    }
  });

  it("are undone one by one back to the first document, and redone forward to the last", () => {
    for (let seed = 100; seed < 120; seed++) {
      const next = random(seed);
      const first = sampleDoc();
      let h = history.start(first);
      for (let step = 0; step < 40; step++) {
        h = history.push(h, randomEdit(h.present, next));
      }
      const last = h.present;
      while (history.canUndo(h)) {
        h = history.undo(h);
      }
      expect(h.present, `seed ${seed}`).toBe(first);
      expect(JSON.stringify(h.present)).toBe(JSON.stringify(sampleDoc()));
      while (history.canRedo(h)) {
        h = history.redo(h);
      }
      expect(h.present, `seed ${seed}`).toBe(last);
    }
  });

  it("never change the document they were given", () => {
    const next = random(7);
    let doc = sampleDoc();
    for (let step = 0; step < 50; step++) {
      const before = JSON.stringify(doc);
      const after = randomEdit(doc, next);
      expect(JSON.stringify(doc)).toBe(before);
      doc = after;
    }
  });
});
