"use client";

import { TextStyleControls } from "./TextStyleControls";

import { useMemo, useState } from "react";
import { canStateInputs, kindWords, KIND_WORDS, readInput } from "@/lib/sheets/inputs";
import type { FontSize } from "@/lib/sheets/types";
import { FormulaBar } from "./FormulaBar";
import { InputCellDialog } from "./InputCellDialog";
import { answerMaps } from "@/lib/sheets/look/sheetLook";
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
  /** The footer row (FA7): the text at its right, empty for a blank row, absent for no row. */
  footer?: string;
  /**
   * The server's answers of the formulas of the version shown (SH2, ADR-0252), by cell key and by calculated column. They
   * are shown while the document is the saved one; while an author types, the browser's own answers are shown instead.
   */
  serverAnswers?: {
    cells: Record<string, string>;
    columns: Record<string, string>;
    kinds: { cells: Record<string, string>; columns: Record<string, string> };
  } | null;
}

/**
 * The grid view of a sheet: the formatting toolbar and the grid (FA3). The toolbar offers bold, italic, one of four named
 * sizes, merge and unmerge, fixed text or an input cell, undo and redo, and adding a row or a column; each is one of the
 * model's operations on the selection, and a refusal is shown in its words under the grid. A button that the model would
 * refuse is not hidden, it is tried, so the person is told why.
 */
export function GridView({ editor, readOnly, readOnlyReason, label, footer, serverAnswers }: GridViewProps) {
  const { state, selection, rows, doc } = editor;
  const answers = serverAnswers && !editor.dirty ? serverAnswers : editor.previews;
  const maps = useMemo(() => answerMaps(answers, rows), [answers, rows]);
  const formulaAnswers = maps.text;
  const focusRow = rows[selection.fr];
  const focusColumn = doc.columns[selection.fc];
  const canAddRow = doc.dimension === 2;
  // Whether the kind of a cell can be chosen here (SH5.1): a sheet in the new format that names its rows.
  const stateInputs = canStateInputs(doc);
  const [settingsOpen, setSettingsOpen] = useState(false);

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
          <TextStyleControls underline={state.underline} alignment={state.alignment} colour={state.colour}
            onUnderline={editor.underline} onAlignment={editor.alignment} onColour={editor.colour} />
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
            aria-haspopup={stateInputs ? "dialog" : undefined}
            disabled={!focusRow || focusRow.zone === "blank"}
            onClick={() => {
              if (!focusRow || !focusColumn) return;
              // In the new format the button opens the settings of the cell (SH5.1); in the old one it makes an input cell.
              if (stateInputs) setSettingsOpen(true);
              else editor.kind(focusRow.id, focusColumn.id, "input");
            }}
          >
            {stateInputs ? "Input cell…" : "Input cell"}
          </button>
          {stateInputs && focusRow && focusColumn ? (
            <span className="hint" data-testid="cell-kind" aria-live="polite">
              {kindWords(doc, focusRow.id, focusColumn.id)}
            </span>
          ) : null}
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
      {readOnly || !editor.canWriteFormulas ? null : (
        <FormulaBar
          editor={editor}
          answer={focusRow && focusColumn ? formulaAnswers[`${focusRow.id}:${focusColumn.id}`] : undefined}
        />
      )}
      {settingsOpen && !readOnly && focusRow && focusColumn ? (
        <InputCellDialog
          initial={readInput(doc, focusRow.id, focusColumn.id)}
          cells={Math.max(1, state.keys.length)}
          columnKind={KIND_WORDS[focusColumn.type]}
          onApply={(settings) => editor.setInputs(settings)}
          onClose={() => setSettingsOpen(false)}
        />
      ) : null}
      <SheetGrid
        editor={editor}
        readOnly={readOnly}
        label={label}
        footer={footer}
        formulaAnswers={formulaAnswers}
        formulaKinds={maps.kinds}
        onResizeColumn={readOnly ? undefined : (id, width) => editor.updateColumn(id, { width })}
        onResizeRow={readOnly ? undefined : (id, height) => editor.updateRow(id, { height })}
      />
      <p className="hint">
        {readOnly
          ? "Arrow keys move; Ctrl+C copies."
          : "Type to write fixed text (start with an equals sign for a formula), Enter to edit, Delete to clear, Ctrl+B and Ctrl+I for bold and italic, Ctrl+Z to undo. Drag the right edge of a column label to change its width, or the bottom edge of a row to change its height. Cells with no text are cells the filler types into."}
      </p>
    </div>
  );
}
