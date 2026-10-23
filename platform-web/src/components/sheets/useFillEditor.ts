"use client";

import { useMemo, useReducer } from "react";
import {
  addFillRow,
  clearFillCells,
  displayDoc,
  gridDisplayRows,
  isInputCell,
  pasteFill,
  removeFillRow,
  sameFill,
  setFillValue,
} from "@/lib/sheets/fillModel";
import type { FillDoc, FillLimits, FillOp } from "@/lib/sheets/fillTypes";
import { normalized } from "@/lib/sheets/geometry";
import * as hist from "@/lib/sheets/history";
import type { GridDoc, Selection } from "@/lib/sheets/types";
import type { EditorMessage, SheetGridEditor } from "./useSheetEditor";

interface State {
  history: hist.History<FillDoc>;
  saved: FillDoc;
  selection: Selection;
  message: EditorMessage | null;
}

type Action =
  | { type: "run"; op: (fill: FillDoc) => FillOp; success?: string }
  | { type: "paste"; op: (fill: FillDoc) => { fill: FillDoc; message: EditorMessage } }
  | { type: "select"; selection: Selection }
  | { type: "undo" }
  | { type: "redo" }
  | { type: "say"; message: EditorMessage | null }
  | { type: "reset"; fill: FillDoc }
  | { type: "saved"; fill: FillDoc };

const START: Selection = { ar: 0, ac: 0, fr: 0, fc: 0 };

function reduce(state: State, action: Action): State {
  switch (action.type) {
    case "run": {
      const result = action.op(state.history.present);
      if (!result.ok) {
        return { ...state, message: { text: result.error, error: true } };
      }
      const text = result.note ?? action.success ?? "";
      return { ...state, history: hist.push(state.history, result.fill), message: text ? { text, error: false } : null };
    }
    case "paste": {
      const result = action.op(state.history.present);
      return { ...state, history: hist.push(state.history, result.fill), message: result.message };
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
      return { history: hist.start(action.fill), saved: action.fill, selection: START, message: null };
    case "saved":
      return { ...state, saved: action.fill };
  }
}

/** What a view of a fill-in is given: the grid's own editor surface over the cells, and what only a fill-in has. */
export interface FillEditor extends SheetGridEditor {
  /** The sheet the fill-in was made on. */
  grid: GridDoc;
  /** The cells the person has typed, saved or not. */
  fill: FillDoc;
  limits: FillLimits;
  canUndo: boolean;
  canRedo: boolean;
  /** Whether the cells differ from the last ones saved or loaded. */
  dirty: boolean;
  /** Whether a person may type into the cell of this row and column (the sheet's own rule). */
  canEditCell(rowId: string, columnId: string): boolean;
  addRow(): void;
  removeRow(rowId: string): void;
  /** Starts again from cells just loaded: no history, nothing unsaved. */
  reset(fill: FillDoc): void;
  /** The cells in the editor are what is stored now: nothing is unsaved. */
  markSaved(fill: FillDoc): void;
}

/**
 * The state of a session of filling in a sheet: the cells with their undo history, the selection and the last message
 * (FF1, ADR-0192). Every edit is one of the pure operations of `lib/sheets/fillModel`; this hook only keeps their results,
 * and shows the sheet with the cells laid over it as the document the grid draws. It decides no rule and no permission.
 */
export function useFillEditor(grid: GridDoc, initial: FillDoc, limits: FillLimits): FillEditor {
  const [state, dispatch] = useReducer(reduce, initial, (fill): State => ({
    history: hist.start(fill),
    saved: fill,
    selection: START,
    message: null,
  }));
  const fill = state.history.present;
  const doc = useMemo(() => displayDoc(grid, fill), [grid, fill]);
  const rows = useMemo(() => gridDisplayRows(grid, fill), [grid, fill]);
  // The selection stays inside the grid when rows were removed.
  const selection = useMemo<Selection>(() => {
    const last = (n: number) => Math.max(0, n - 1);
    const clamp = (value: number, max: number) => Math.min(Math.max(0, value), max);
    return {
      ar: clamp(state.selection.ar, last(rows.length)),
      ac: clamp(state.selection.ac, last(grid.columns.length)),
      fr: clamp(state.selection.fr, last(rows.length)),
      fc: clamp(state.selection.fc, last(grid.columns.length)),
    };
  }, [state.selection, rows.length, grid.columns.length]);
  const rect = useMemo(() => normalized(selection), [selection]);
  const dirty = useMemo(() => !sameFill(grid, fill, state.saved), [grid, fill, state.saved]);

  return {
    grid,
    fill,
    doc,
    rows,
    selection,
    rect,
    message: state.message,
    limits,
    canUndo: hist.canUndo(state.history),
    canRedo: hist.canRedo(state.history),
    dirty,
    select: (next) => dispatch({ type: "select", selection: next }),
    say: (text, error = false) => dispatch({ type: "say", message: text ? { text, error } : null }),
    undo: () => dispatch({ type: "undo" }),
    redo: () => dispatch({ type: "redo" }),
    canEditCell: (rowId, columnId) => isInputCell(grid, rowId, columnId),
    setText: (rowId, columnId, text) => {
      const result = setFillValue(grid, fill, rowId, columnId, text, limits);
      dispatch({ type: "run", op: (current) => setFillValue(grid, current, rowId, columnId, text, limits) });
      return result.ok ? null : result.error;
    },
    clear: (target) => {
      const ids = rows.slice(target.r0, target.r1 + 1).filter((row) => row.zone === "body").map((row) => row.id);
      const columns = grid.columns.slice(target.c0, target.c1 + 1).map((column) => column.id);
      dispatch({ type: "run", op: (current) => ({ ok: true, fill: clearFillCells(grid, current, ids, columns) }) });
    },
    paste: (row, column, matrix) =>
      dispatch({
        type: "paste",
        op: (current) => {
          const pasted = pasteFill(grid, current, gridDisplayRows(grid, current), row, column, matrix, limits);
          const said = `Pasted ${pasted.applied} cell${pasted.applied === 1 ? "" : "s"}${
            pasted.skipped > 0 ? `, ${pasted.skipped} skipped` : ""
          }${pasted.problems.length > 0 ? `. ${pasted.problems.join(" ")}` : "."}`;
          return { fill: pasted.fill, message: { text: said, error: pasted.problems.length > 0 } };
        },
      }),
    // A filler formats nothing: the keys that format in the author's grid do nothing here.
    bold: () => undefined,
    italic: () => undefined,
    addRow: () => dispatch({ type: "run", op: (current) => addFillRow(grid, current, limits), success: "Row added." }),
    removeRow: (rowId) =>
      dispatch({ type: "run", op: (current) => removeFillRow(grid, current, rowId), success: "Row removed." }),
    reset: (next) => dispatch({ type: "reset", fill: next }),
    markSaved: (next) => dispatch({ type: "saved", fill: next }),
  };
}
