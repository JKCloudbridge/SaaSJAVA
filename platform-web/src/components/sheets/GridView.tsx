"use client";

import type { AnnotationMarks } from "@/lib/sheets/annotate";
import type { FontSize } from "@/lib/sheets/types";
import { SheetGrid } from "./SheetGrid";
import type { SheetEditor } from "./useSheetEditor";

const SIZES: { value: FontSize; label: string }[] = [
  { value: "SMALL", label: "Small" },
  { value: "NORMAL", label: "Normal" },
  { value: "LARGE", label: "Large" },
  { value: "TITLE", label: "Title" },
];

export interface GridViewProps {
  editor: SheetEditor;
  /** True when the grid may be looked at but not changed: a version that is not a draft, a caller who may only read, a narrow screen. */
  readOnly: boolean;
  /** Why the grid is read-only, in words, shown above it. */
  readOnlyReason?: string;
  label: string;
  /** Annotate mode (FA5): the places with notes are marked and Enter on a cell asks for a note on it. */
  annotation?: { marks: AnnotationMarks; onActivate(): void };
}

/**
 * The grid view of a sheet: the formatting toolbar and the grid (FA3). The toolbar offers bold, italic, one of four named
 * sizes, merge and unmerge, fixed text or an input cell, undo and redo, and adding a row or a column; each is one of the
 * model's operations on the selection, and a refusal is shown in its words under the grid. A button that the model would
 * refuse is not hidden, it is tried, so the person is told why.
 */
export function GridView({ editor, readOnly, readOnlyReason, label, annotation }: GridViewProps) {
  const { state, selection, rows, doc } = editor;
  const focusRow = rows[selection.fr];
  const focusColumn = doc.columns[selection.fc];
  const canAddRow = doc.dimension === 2;

  return (
    <div className="sheet-view" data-testid="grid-view">
      {readOnly && readOnlyReason ? (
        <p className="hint" role="note">
          {readOnlyReason}
        </p>
      ) : null}
      {readOnly ? null : (
        <div className="sheet-toolbar" role="toolbar" aria-label="Formatting">
          <button type="button" className="button button-secondary" aria-label="Bold" aria-pressed={state.bold} onClick={editor.bold}>
            <b aria-hidden="true">B</b>
          </button>
          <button type="button" className="button button-secondary" aria-label="Italic" aria-pressed={state.italic} onClick={editor.italic}>
            <i aria-hidden="true">I</i>
          </button>
          <label className="sheet-toolbar-field">
            <span className="visually-hidden">Text size</span>
            <select
              value={state.size ?? ""}
              aria-label="Text size"
              onChange={(event) => {
                if (event.target.value) {
                  editor.size(event.target.value as FontSize);
                }
              }}
            >
              {state.size === null ? <option value="">Mixed</option> : null}
              {SIZES.map((size) => (
                <option key={size.value} value={size.value}>
                  {size.label}
                </option>
              ))}
            </select>
          </label>
          <span className="sheet-toolbar-gap" aria-hidden="true" />
          <button type="button" className="button button-secondary" onClick={editor.merge} disabled={!state.canMerge}>
            Merge
          </button>
          <button type="button" className="button button-secondary" onClick={editor.unmerge} disabled={!state.canUnmerge}>
            Unmerge
          </button>
          <span className="sheet-toolbar-gap" aria-hidden="true" />
          <button
            type="button"
            className="button button-secondary"
            aria-pressed={state.kind === "fixed"}
            disabled={!focusRow || focusRow.zone === "blank"}
            onClick={() => focusRow && focusColumn && editor.kind(focusRow.id, focusColumn.id, "fixed")}
          >
            Fixed text
          </button>
          <button
            type="button"
            className="button button-secondary"
            aria-pressed={state.kind === "input"}
            disabled={!focusRow || focusRow.zone === "blank"}
            onClick={() => focusRow && focusColumn && editor.kind(focusRow.id, focusColumn.id, "input")}
          >
            Input cell
          </button>
          <span className="sheet-toolbar-gap" aria-hidden="true" />
          <button type="button" className="button button-secondary" onClick={editor.undo} disabled={!editor.canUndo}>
            Undo
          </button>
          <button type="button" className="button button-secondary" onClick={editor.redo} disabled={!editor.canRedo}>
            Redo
          </button>
          <span className="sheet-toolbar-gap" aria-hidden="true" />
          {canAddRow ? (
            <button
              type="button"
              className="button button-secondary"
              onClick={() => {
                const zone = focusRow?.zone === "header" ? "header" : "body";
                editor.addRow(zone, focusRow && focusRow.zone !== "blank" ? focusRow.id : null);
              }}
            >
              Add row
            </button>
          ) : null}
          <button type="button" className="button button-secondary" onClick={() => editor.addColumn(focusColumn?.id ?? null)}>
            Add column
          </button>
        </div>
      )}
      <SheetGrid
        editor={editor}
        readOnly={readOnly}
        label={label}
        annotation={annotation}
        onResizeColumn={readOnly || annotation ? undefined : (id, width) => editor.updateColumn(id, { width })}
        onResizeRow={readOnly || annotation ? undefined : (id, height) => editor.updateRow(id, { height })}
      />
      <p className="hint">
        {annotation
          ? "Arrow keys move; click a column label to stand on a column. Press Enter on a cell to write a comment about it, or use the buttons under the grid. Marked places already have comments."
          : readOnly
          ? "Arrow keys move; Ctrl+C copies."
          : "Type to write fixed text, Enter to edit, Delete to clear, Ctrl+B and Ctrl+I for bold and italic, Ctrl+Z to undo. Drag the right edge of a column label to change its width, or the bottom edge of a row to change its height. Cells with no text are cells the filler types into."}
      </p>
    </div>
  );
}
