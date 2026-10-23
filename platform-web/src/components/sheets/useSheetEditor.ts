"use client";

import { useCallback, useMemo, useReducer } from "react";
import { sameDocument } from "@/lib/sheets/adapter";
import { displayRows, normalized } from "@/lib/sheets/geometry";
import {
  addColumn,
  addRow,
  clearCells,
  mergeRect,
  moveColumn,
  moveRow,
  pasteBlock,
  removeColumn,
  removeRow,
  selectionState,
  setCellKind,
  setCellText,
  setFontSize,
  setShape,
  toggleStyle,
  unmergeAt,
  updateColumn,
  updateRow,
  type ColumnChange,
  type SelectionState,
} from "@/lib/sheets/gridModel";
import * as hist from "@/lib/sheets/history";
import type { CellKind, DisplayRow, FontSize, GridDoc, GridLine, OpResult, Rect, RowsMode, Selection, SheetLimits } from "@/lib/sheets/types";

/** The sentence under the grid: what the last action did, or why it was refused. */
export interface EditorMessage {
  text: string;
  error: boolean;
}

interface State {
  history: hist.History<GridDoc>;
  saved: GridDoc;
  selection: Selection;
  message: EditorMessage | null;
}

type Action =
  | { type: "run"; op: (doc: GridDoc) => OpResult; success?: string }
  | { type: "paste"; row: number; column: number; matrix: string[][]; limits: SheetLimits }
  | { type: "select"; selection: Selection }
  | { type: "undo" }
  | { type: "redo" }
  | { type: "say"; message: EditorMessage | null }
  | { type: "reset"; doc: GridDoc }
  | { type: "saved"; doc: GridDoc };

const START: Selection = { ar: 0, ac: 0, fr: 0, fc: 0 };

function reduce(state: State, action: Action): State {
  switch (action.type) {
    case "run": {
      const result = action.op(state.history.present);
      if (!result.ok) {
        return { ...state, message: { text: result.error, error: true } };
      }
      const text = result.note ?? action.success ?? "";
      return {
        ...state,
        history: hist.push(state.history, result.doc),
        message: text ? { text, error: false } : null,
      };
    }
    case "paste": {
      const rows = displayRows(state.history.present);
      const pasted = pasteBlock(state.history.present, rows, action.row, action.column, action.matrix, action.limits);
      const said = `Pasted ${pasted.applied} cell${pasted.applied === 1 ? "" : "s"}${
        pasted.skipped > 0 ? `, ${pasted.skipped} skipped` : ""
      }${pasted.problems.length > 0 ? `. ${pasted.problems.join(" ")}` : "."}`;
      return {
        ...state,
        history: hist.push(state.history, pasted.doc),
        message: { text: said, error: pasted.problems.length > 0 },
      };
    }
    case "select":
      return { ...state, selection: action.selection };
    case "undo":
      return { ...state, history: hist.undo(state.history), message: null };
    case "redo":
      return { ...state, history: hist.redo(state.history), message: null };
    case "say":
      return { ...state, message: action.message };
    case "reset":
      return { history: hist.start(action.doc), saved: action.doc, selection: START, message: null };
    case "saved":
      return { ...state, saved: action.doc };
  }
}

/** What the editor offers a view. Every edit is one step of the history; a refused edit changes nothing. */
export interface SheetEditor {
  doc: GridDoc;
  rows: DisplayRow[];
  selection: Selection;
  rect: Rect;
  state: SelectionState;
  message: EditorMessage | null;
  canUndo: boolean;
  canRedo: boolean;
  /** Whether the document differs from the last one saved or loaded. */
  dirty: boolean;
  limits: SheetLimits;
  select(selection: Selection): void;
  say(text: string, error?: boolean): void;
  undo(): void;
  redo(): void;
  /** Types fixed text into a cell. Returns the reason in words when the text is refused, otherwise null. */
  setText(rowId: string, columnId: string, text: string): string | null;
  clear(rect: Rect): void;
  paste(row: number, column: number, matrix: string[][]): void;
  bold(): void;
  italic(): void;
  size(size: FontSize): void;
  kind(rowId: string, columnId: string, kind: CellKind): void;
  merge(): void;
  unmerge(): void;
  addColumn(afterId: string | null): void;
  removeColumn(id: string): void;
  moveColumn(id: string, delta: -1 | 1): void;
  updateColumn(id: string, change: ColumnChange): void;
  addRow(zone: "header" | "body", afterId: string | null): void;
  removeRow(id: string): void;
  moveRow(id: string, delta: -1 | 1): void;
  updateRow(id: string, change: Partial<Pick<GridLine, "label" | "height">>): void;
  shape(dimension: 1 | 2, rowsMode?: RowsMode): void;
  /** Starts again from a document just loaded: no history, nothing unsaved. */
  reset(doc: GridDoc): void;
  /** The document in the editor is what is stored now: nothing is unsaved. */
  markSaved(doc: GridDoc): void;
}

/**
 * What the grid itself needs of an editor: the document, the rows to draw, the selection, the last message, and the few
 * edits a key or a paste can make. The author's editor offers all of it; the editor of a fill-in (FF1) offers it over the
 * cells a filler types, so one grid draws both.
 */
export type SheetGridEditor = Pick<
  SheetEditor,
  | "doc"
  | "rows"
  | "selection"
  | "rect"
  | "message"
  | "select"
  | "say"
  | "setText"
  | "clear"
  | "paste"
  | "undo"
  | "redo"
  | "bold"
  | "italic"
>;

/**
 * The state of an edit session over one grid: the document with its undo history, the selection and the last message
 * (FA3, ADR-0172 section 4, ADR-0186). Every edit is one of the pure operations of `lib/sheets/gridModel`; this hook
 * only keeps their results. It decides no rule and no permission.
 */
export function useSheetEditor(initial: GridDoc, limits: SheetLimits): SheetEditor {
  const [state, dispatch] = useReducer(reduce, initial, (doc): State => ({
    history: hist.start(doc),
    saved: doc,
    selection: START,
    message: null,
  }));
  const doc = state.history.present;
  const rows = useMemo(() => displayRows(doc), [doc]);
  // The selection stays inside the grid when rows or columns were removed.
  const selection = useMemo<Selection>(() => {
    const last = (n: number) => Math.max(0, n - 1);
    const clamp = (value: number, max: number) => Math.min(Math.max(0, value), max);
    return {
      ar: clamp(state.selection.ar, last(rows.length)),
      ac: clamp(state.selection.ac, last(doc.columns.length)),
      fr: clamp(state.selection.fr, last(rows.length)),
      fc: clamp(state.selection.fc, last(doc.columns.length)),
    };
  }, [state.selection, rows.length, doc.columns.length]);
  const rect = useMemo(() => normalized(selection), [selection]);
  const selected = useMemo(() => selectionState(doc, rows, rect), [doc, rows, rect]);
  const dirty = useMemo(() => !sameDocument(doc, state.saved), [doc, state.saved]);

  const run = useCallback((op: (d: GridDoc) => OpResult, success?: string) => dispatch({ type: "run", op, success }), []);

  const rowAt = (index: number) => rows[index];
  const keys = selected.keys;

  return {
    doc,
    rows,
    selection,
    rect,
    state: selected,
    message: state.message,
    canUndo: hist.canUndo(state.history),
    canRedo: hist.canRedo(state.history),
    dirty,
    limits,
    select: (next) => dispatch({ type: "select", selection: next }),
    say: (text, error = false) => dispatch({ type: "say", message: text ? { text, error } : null }),
    undo: () => dispatch({ type: "undo" }),
    redo: () => dispatch({ type: "redo" }),
    setText: (rowId, columnId, text) => {
      const result = setCellText(doc, rowId, columnId, text, limits);
      dispatch({ type: "run", op: (d) => setCellText(d, rowId, columnId, text, limits) });
      return result.ok ? null : result.error;
    },
    clear: (target) => run((d) => clearCells(d, displayRows(d), target)),
    paste: (row, column, matrix) => dispatch({ type: "paste", row, column, matrix, limits }),
    bold: () => run((d) => toggleStyle(d, keys, "bold")),
    italic: () => run((d) => toggleStyle(d, keys, "italic")),
    size: (next) => run((d) => setFontSize(d, keys, next)),
    kind: (rowId, columnId, next) => run((d) => setCellKind(d, rowId, columnId, next)),
    merge: () => run((d) => mergeRect(d, displayRows(d), rect, limits), "Merged."),
    unmerge: () => {
      const anchor = rowAt(selection.fr) ? { row: selection.fr, column: selection.fc } : { row: 0, column: 0 };
      run((d) => unmergeAt(d, displayRows(d), anchor.row, anchor.column), "Unmerged.");
    },
    addColumn: (after) => run((d) => addColumn(d, after, limits)),
    removeColumn: (id) => run((d) => removeColumn(d, id)),
    moveColumn: (id, delta) => run((d) => moveColumn(d, id, delta)),
    updateColumn: (id, change) => run((d) => updateColumn(d, id, change, limits)),
    addRow: (zone, after) => run((d) => addRow(d, zone, after, limits)),
    removeRow: (id) => run((d) => removeRow(d, id)),
    moveRow: (id, delta) => run((d) => moveRow(d, id, delta)),
    updateRow: (id, change) => run((d) => updateRow(d, id, change, limits)),
    shape: (dimension, rowsMode) => run((d) => setShape(d, dimension, rowsMode)),
    reset: (next) => dispatch({ type: "reset", doc: next }),
    markSaved: (next) => dispatch({ type: "saved", doc: next }),
  };
}
