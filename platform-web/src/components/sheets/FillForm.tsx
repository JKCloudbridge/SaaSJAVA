"use client";

import { useId, useState } from "react";
import { canAddRows, fillDisplayRows, isInputCell, missingRequired, needsARow } from "@/lib/sheets/fillModel";
import type { FillValue } from "@/lib/sheets/fillTypes";
import type { GridColumn } from "@/lib/sheets/types";
import type { FillEditor } from "./useFillEditor";

/** What a value says on a screen: text as it is, a yes or no in words. */
function shown(value: FillValue | undefined): string {
  if (value === true) {
    return "Yes";
  }
  if (value === false) {
    return "No";
  }
  return value ?? "";
}

/**
 * One value in the form view. The text the person types is kept in the box until they leave it (one step of the history,
 * like a cell of the grid); a choice is made at once. A value the editor refuses is told in words under the box and the
 * box keeps what was typed.
 */
function FillField({
  column,
  value,
  name,
  commit,
  serverProblems,
}: {
  column: GridColumn;
  value: FillValue | undefined;
  name: string;
  commit(text: string): string | null;
  serverProblems: readonly string[];
}) {
  const id = useId();
  const [draft, setDraft] = useState(shown(value));
  const [refused, setRefused] = useState<string | null>(null);
  const words = [...(refused ? [refused] : []), ...serverProblems];
  const label = (
    <label htmlFor={id}>
      {column.label || "Untitled column"}
      {column.required ? <span aria-hidden="true"> *</span> : null}
      {column.required ? <span className="visually-hidden"> (required)</span> : null}
    </label>
  );
  const apply = (text: string) => setRefused(commit(text));
  let control;
  if (column.type === "picklist" || column.type === "boolean") {
    const choices = column.type === "boolean" ? ["Yes", "No"] : (column.picklist?.values ?? []);
    control = (
      <select
        id={id}
        value={draft}
        aria-invalid={words.length > 0}
        onChange={(event) => {
          setDraft(event.target.value);
          apply(event.target.value);
        }}
      >
        <option value="">No answer</option>
        {choices.map((choice) => (
          <option key={choice} value={choice}>
            {choice}
          </option>
        ))}
      </select>
    );
  } else if (column.type === "text") {
    control = (
      <textarea
        id={id}
        rows={2}
        value={draft}
        aria-invalid={words.length > 0}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={() => apply(draft)}
      />
    );
  } else {
    control = (
      <input
        id={id}
        type={column.type === "date" ? "date" : "text"}
        inputMode={column.type === "number" ? "decimal" : undefined}
        autoComplete="off"
        value={draft}
        aria-label={undefined}
        aria-invalid={words.length > 0}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={() => apply(draft)}
        data-name={name}
      />
    );
  }
  return (
    <div className="field">
      {label}
      {control}
      {words.map((word) => (
        <p key={word} className="field-error" role="alert">
          {word}
        </p>
      ))}
    </div>
  );
}

export interface FillFormProps {
  editor: FillEditor;
  /** True when the form may be looked at but not changed. */
  readOnly: boolean;
  /** What the server refused on the last save, in words, by the cell it is about (`rowId:columnId`). */
  problems: ReadonlyMap<string, readonly string[]>;
}

/**
 * The form view of a fill-in (FF1, ADR-0172 section 3): every row of the sheet and every row the filler added as a card
 * with one box for each input column, the way to fill in on a narrow screen where the grid is for reading. A cell the sheet
 * fixes is shown as text, never as a box. It uses the editor of the grid view, so the two views are one fill-in, and it
 * decides nothing: the types, the choices and the required marks are the sheet's, and the server checks again on a save.
 */
export function FillForm({ editor, readOnly, problems }: FillFormProps) {
  const { grid, fill } = editor;
  const rows = fillDisplayRows(grid, fill);
  const missing = missingRequired(grid, fill);
  const mayAdd = !readOnly && canAddRows(grid);
  return (
    <div className="stack" data-testid="fill-form">
      {missing.length > 0 && !readOnly ? (
        <p className="hint" role="note" data-testid="fill-missing">
          {missing.length === 1
            ? "1 value is still to fill in before this can be sent."
            : `${missing.length} values are still to fill in before this can be sent.`}
        </p>
      ) : null}
      {needsARow(grid, fill) && !readOnly ? (
        <p className="hint" role="note">
          There are no rows yet. Add the first row.
        </p>
      ) : null}
      {rows.map((row, index) => {
        const stored = fill.rows.find((item) => item.id === row.id);
        const title = row.label || `Row ${index + 1}`;
        return (
          <fieldset key={row.id} className="card stack" data-testid="fill-row">
            <legend>{title}</legend>
            {grid.columns.map((column) => {
              const value = stored?.cells[column.id];
              const fixed = grid.cells[`${row.id}:${column.id}`]?.value;
              const serverProblems = problems.get(`${row.id}:${column.id}`) ?? [];
              if (!readOnly && isInputCell(grid, row.id, column.id)) {
                return (
                  <FillField
                    key={`${column.id}-${shown(value)}`}
                    column={column}
                    value={value}
                    name={`${row.id}:${column.id}`}
                    commit={(text) => editor.setText(row.id, column.id, text)}
                    serverProblems={serverProblems}
                  />
                );
              }
              return (
                <p key={column.id}>
                  <strong>{column.label || "Untitled column"}</strong> {shown((isInputCell(grid, row.id, column.id) ? value : fixed) ?? undefined) || "—"}
                </p>
              );
            })}
            {mayAdd && row.added ? (
              <div>
                <button type="button" className="button button-secondary" onClick={() => editor.removeRow(row.id)}>
                  Remove {title.toLowerCase()}
                </button>
              </div>
            ) : null}
          </fieldset>
        );
      })}
      {mayAdd ? (
        <div>
          <button type="button" className="button button-secondary" onClick={editor.addRow}>
            Add a row
          </button>
        </div>
      ) : null}
    </div>
  );
}
