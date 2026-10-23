"use client";

import { useId, useState, type ReactNode } from "react";
import { problemsAt } from "@/lib/sheets/hints";
import { COLUMN_TYPES, type ColumnType, type GridColumn, type GridLine, type Problem } from "@/lib/sheets/types";
import type { SheetEditor } from "./useSheetEditor";

const TYPE_WORDS: Record<ColumnType, string> = {
  text: "Text",
  number: "Number",
  date: "Date",
  picklist: "List of values",
  boolean: "Yes or no",
};

/**
 * What a person is typing, kept until they leave the box, and started again from the stored value whenever that changes
 * (an undo, a reload). Adjusting state while rendering is the pattern React recommends for this; an effect would draw the
 * old text first.
 */
function useDraft(value: string): [string, (draft: string) => void] {
  const [state, setState] = useState({ source: value, draft: value });
  if (state.source !== value) {
    setState({ source: value, draft: value });
    return [value, (draft) => setState({ source: value, draft })];
  }
  return [state.draft, (draft) => setState({ source: value, draft })];
}

/** A text box that keeps what is typed until the person leaves it, so that one edit is one step of the history. */
function DraftInput({
  id,
  value,
  onCommit,
  label,
  type = "text",
  inputMode,
  invalid,
  disabled,
  describedBy,
}: {
  id?: string;
  value: string;
  onCommit: (value: string) => void;
  label: string;
  type?: string;
  inputMode?: "decimal" | "numeric" | "text";
  invalid?: boolean;
  disabled?: boolean;
  describedBy?: string;
}) {
  const [draft, setDraft] = useDraft(value);
  const commit = () => {
    if (draft !== value) {
      onCommit(draft);
    }
  };
  return (
    <input
      id={id}
      type={type}
      inputMode={inputMode}
      value={draft}
      aria-label={label}
      aria-invalid={invalid || undefined}
      aria-describedby={describedBy}
      disabled={disabled}
      autoComplete="off"
      spellCheck={false}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          commit();
        }
      }}
    />
  );
}

function Errors({ messages, id }: { messages: string[]; id: string }) {
  return messages.length === 0 ? null : (
    <p className="field-error" id={id} role="alert">
      {messages.join(" ")}
    </p>
  );
}

function Field({ label, children, errors, errorId }: { label: string; children: ReactNode; errors?: string[]; errorId?: string }) {
  return (
    <div className="field">
      <label>
        <span>{label}</span>
        {children}
      </label>
      {errors && errorId ? <Errors messages={errors} id={errorId} /> : null}
    </div>
  );
}

export interface FormViewProps {
  editor: SheetEditor;
  readOnly: boolean;
  /** Hints and refusals, in the places and words of the API. */
  problems: readonly Problem[];
  /** Whether the shape of the sheet (who adds the rows) may still be chosen: the API's rule, shown only when it allows it. */
  canChangeShape: boolean;
}

/**
 * The form view of a sheet (FA3): the columns (label, type, default, whether the filler must fill it, the values of a
 * list), the header rows and the named rows, as ordinary form fields. It is the way to build a sheet on a narrow screen
 * and for a person who does not use a pointer. Every field is one of the model's operations; a field commits when the
 * person leaves it. It draws what the model holds and decides nothing; the server checks every rule again.
 */
export function FormView({ editor, readOnly, problems, canChangeShape }: FormViewProps) {
  const { doc, limits } = editor;
  const base = useId();
  return (
    <div className="sheet-view sheet-form" data-testid="form-view">
      <section aria-label="Shape of the sheet" className="stack">
        <h3>Who adds the rows</h3>
        {canChangeShape && !readOnly ? (
          <div className="split">
            <Field label="Rows">
              <select
                value={doc.dimension}
                onChange={(event) => editor.shape(Number(event.target.value) === 1 ? 1 : 2)}
              >
                <option value={1}>The filler adds the rows</option>
                <option value={2}>The sheet names the rows</option>
              </select>
            </Field>
            {doc.dimension === 2 ? (
              <Field label="Rows can be">
                <select
                  value={doc.rowsMode}
                  onChange={(event) => editor.shape(2, event.target.value === "FIXED_AND_REPEATABLE" ? "FIXED_AND_REPEATABLE" : "FIXED")}
                >
                  <option value="FIXED">Only the rows named here</option>
                  <option value="FIXED_AND_REPEATABLE">The named rows, and more added by the filler</option>
                </select>
              </Field>
            ) : null}
          </div>
        ) : (
          <p>
            {doc.dimension === 1
              ? "The filler adds the rows."
              : doc.rowsMode === "FIXED"
                ? "The sheet names the rows; the filler adds none."
                : "The sheet names the rows, and the filler may add more."}{" "}
            <span className="hint">The shape is chosen with the first version and does not change.</span>
          </p>
        )}
      </section>

      <section aria-label="Columns" className="stack">
        <h3>Columns</h3>
        <ol className="sheet-form-list">
          {doc.columns.map((column, index) => (
            <ColumnEditor
              key={column.id}
              column={column}
              index={index}
              count={doc.columns.length}
              editor={editor}
              readOnly={readOnly}
              problems={problems}
              id={`${base}-c${index}`}
            />
          ))}
        </ol>
        {readOnly ? null : (
          <button
            type="button"
            className="button button-secondary"
            onClick={() => editor.addColumn(doc.columns[doc.columns.length - 1]?.id ?? null)}
            disabled={doc.columns.length >= limits.maxColumns}
          >
            Add a column
          </button>
        )}
        <Errors messages={problemsAt(problems, "grid.columns").filter((m) => /more columns/.test(m))} id={`${base}-columns`} />
      </section>

      <section aria-label="Header rows" className="stack">
        <h3>Header rows</h3>
        <p className="hint">Bands above the first row, for a title over several columns. Write in them in the grid view.</p>
        <LineList lines={doc.headerRows} zone="header" editor={editor} readOnly={readOnly} problems={problems} id={`${base}-h`} />
      </section>

      {doc.dimension === 2 ? (
        <section aria-label="Rows" className="stack">
          <h3>Rows</h3>
          <LineList lines={doc.rows} zone="body" editor={editor} readOnly={readOnly} problems={problems} id={`${base}-r`} />
        </section>
      ) : (
        <section aria-label="Rows" className="stack">
          <h3>Rows</h3>
          <p className="hint">The filler adds the rows, so the sheet names none. The grid view shows a few blank rows as a sample.</p>
        </section>
      )}
    </div>
  );
}

function ColumnEditor({
  column,
  index,
  count,
  editor,
  readOnly,
  problems,
  id,
}: {
  column: GridColumn;
  index: number;
  count: number;
  editor: SheetEditor;
  readOnly: boolean;
  problems: readonly Problem[];
  id: string;
}) {
  const place = `grid.columns[${index}]`;
  const number = index + 1;
  const labelErrors = problemsAt(problems, `${place}.label`);
  const listErrors = problemsAt(problems, `${place}.picklist`);
  const defaultErrors = problemsAt(problems, `${place}.default`);
  const otherErrors = problemsAt(problems, place).filter(
    (message) => ![...labelErrors, ...listErrors, ...defaultErrors].includes(message),
  );
  const values = column.picklist?.values ?? [];

  return (
    <li className="sheet-form-item" aria-label={`Column ${number}`}>
      <div className="sheet-form-grid">
        <div className="field">
          <label htmlFor={`${id}-label`}>Label of column {number}</label>
          <DraftInput
            id={`${id}-label`}
            value={column.label}
            label={`Label of column ${number}`}
            invalid={labelErrors.length > 0}
            describedBy={labelErrors.length > 0 ? `${id}-label-error` : undefined}
            disabled={readOnly}
            onCommit={(label) => editor.updateColumn(column.id, { label })}
          />
          <Errors messages={labelErrors} id={`${id}-label-error`} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-type`}>What column {number} holds</label>
          <select
            id={`${id}-type`}
            value={column.type}
            disabled={readOnly}
            onChange={(event) => editor.updateColumn(column.id, { type: event.target.value as ColumnType })}
          >
            {COLUMN_TYPES.map((type) => (
              <option key={type} value={type}>
                {TYPE_WORDS[type]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-default`}>Starts as, in column {number}</label>
          <DefaultControl id={`${id}-default`} column={column} number={number} disabled={readOnly} editor={editor} invalid={defaultErrors.length > 0} />
          <Errors messages={defaultErrors} id={`${id}-default-error`} />
        </div>
        <div className="field">
          <label htmlFor={`${id}-width`}>Width of column {number}, in pixels</label>
          <DraftInput
            id={`${id}-width`}
            value={column.width === undefined ? "" : String(column.width)}
            label={`Width of column ${number}, in pixels`}
            type="number"
            inputMode="numeric"
            disabled={readOnly}
            onCommit={(raw) => {
              const width = Math.round(Number(raw));
              editor.updateColumn(column.id, { width: raw === "" || !Number.isFinite(width) ? undefined : Math.min(1000, Math.max(40, width)) });
            }}
          />
        </div>
        <label className="sheet-check">
          <input
            type="checkbox"
            checked={column.required === true}
            disabled={readOnly}
            aria-label={`The filler must fill column ${number}`}
            onChange={(event) => editor.updateColumn(column.id, { required: event.target.checked })}
          />
          <span>The filler must fill it</span>
        </label>
        <label className="sheet-check">
          <input
            type="checkbox"
            checked={column.reportable === true}
            disabled={readOnly}
            aria-label={`Column ${number} can be used in a report`}
            onChange={(event) => editor.updateColumn(column.id, { reportable: event.target.checked })}
          />
          <span>Can be used in a report</span>
        </label>
      </div>
      {column.type === "picklist" ? (
        <div className="field">
          <label htmlFor={`${id}-values`}>Values of column {number}, one on each line</label>
          <PicklistBox
            id={`${id}-values`}
            values={values}
            disabled={readOnly}
            invalid={listErrors.length > 0}
            onCommit={(next) => editor.updateColumn(column.id, { picklist: { values: next } })}
          />
          <Errors messages={listErrors} id={`${id}-values-error`} />
        </div>
      ) : null}
      <Errors messages={otherErrors} id={`${id}-error`} />
      {readOnly ? null : (
        <div className="sheet-form-actions" role="group" aria-label={`Column ${number}`}>
          <button type="button" className="link-button" onClick={() => editor.moveColumn(column.id, -1)} disabled={index === 0}>
            Move column {number} up
          </button>
          <button type="button" className="link-button" onClick={() => editor.moveColumn(column.id, 1)} disabled={index === count - 1}>
            Move column {number} down
          </button>
          <button type="button" className="link-button" onClick={() => editor.addColumn(column.id)} disabled={count >= editor.limits.maxColumns}>
            Add a column after column {number}
          </button>
          <button type="button" className="link-button" onClick={() => editor.removeColumn(column.id)} disabled={count === 1}>
            Remove column {number}
          </button>
        </div>
      )}
    </li>
  );
}

function DefaultControl({
  id,
  column,
  number,
  disabled,
  editor,
  invalid,
}: {
  id: string;
  column: GridColumn;
  number: number;
  disabled: boolean;
  editor: SheetEditor;
  invalid: boolean;
}) {
  const label = `Starts as, in column ${number}`;
  const current = column.default;
  if (column.type === "boolean") {
    return (
      <select
        id={id}
        aria-label={label}
        value={current === true ? "yes" : current === false ? "no" : ""}
        disabled={disabled}
        onChange={(event) => editor.updateColumn(column.id, { default: event.target.value === "" ? undefined : event.target.value === "yes" })}
      >
        <option value="">Empty</option>
        <option value="yes">Yes</option>
        <option value="no">No</option>
      </select>
    );
  }
  if (column.type === "picklist") {
    return (
      <select
        id={id}
        aria-label={label}
        value={typeof current === "string" ? current : ""}
        disabled={disabled}
        onChange={(event) => editor.updateColumn(column.id, { default: event.target.value === "" ? undefined : event.target.value })}
      >
        <option value="">Empty</option>
        {(column.picklist?.values ?? []).map((value) => (
          <option key={value} value={value}>
            {value}
          </option>
        ))}
      </select>
    );
  }
  return (
    <DraftInput
      id={id}
      value={typeof current === "string" ? current : ""}
      label={label}
      type={column.type === "date" ? "date" : "text"}
      inputMode={column.type === "number" ? "decimal" : "text"}
      invalid={invalid}
      disabled={disabled}
      onCommit={(next) => editor.updateColumn(column.id, { default: next === "" ? undefined : next })}
    />
  );
}

function PicklistBox({
  id,
  values,
  disabled,
  invalid,
  onCommit,
}: {
  id: string;
  values: string[];
  disabled: boolean;
  invalid: boolean;
  onCommit: (values: string[]) => void;
}) {
  const text = values.join("\n");
  const [draft, setDraft] = useDraft(text);
  return (
    <textarea
      id={id}
      rows={Math.min(8, Math.max(3, values.length + 1))}
      value={draft}
      disabled={disabled}
      aria-invalid={invalid || undefined}
      spellCheck={false}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={() => {
        if (draft !== text) {
          // A blank line is nothing; a repeated value is kept so that the server's hint can say so.
          onCommit(draft.split("\n").map((line) => line.trim()).filter((line) => line !== ""));
        }
      }}
    />
  );
}

function LineList({
  lines,
  zone,
  editor,
  readOnly,
  problems,
  id,
}: {
  lines: GridLine[];
  zone: "header" | "body";
  editor: SheetEditor;
  readOnly: boolean;
  problems: readonly Problem[];
  id: string;
}) {
  const noun = zone === "header" ? "Header row" : "Row";
  const name = zone === "header" ? "headerRows" : "rows";
  const limit = zone === "header" ? editor.limits.maxHeaderRows : editor.limits.maxRows;
  return (
    <>
      {lines.length === 0 ? <p className="hint">{zone === "header" ? "No header rows." : "No rows yet."}</p> : null}
      <ol className="sheet-form-list">
        {lines.map((line, index) => {
          const number = index + 1;
          const errors = problemsAt(problems, `grid.${name}[${index}]`);
          return (
            <li key={line.id} className="sheet-form-item" aria-label={`${noun} ${number}`}>
              <div className="sheet-form-grid">
                {zone === "body" ? (
                  <div className="field">
                    <label htmlFor={`${id}${index}-label`}>Label of row {number}</label>
                    <DraftInput
                      id={`${id}${index}-label`}
                      value={line.label ?? ""}
                      label={`Label of row ${number}`}
                      disabled={readOnly}
                      invalid={errors.length > 0}
                      onCommit={(label) => editor.updateRow(line.id, { label })}
                    />
                  </div>
                ) : null}
                <div className="field">
                  <label htmlFor={`${id}${index}-height`}>Height of {noun.toLowerCase()} {number}, in pixels</label>
                  <DraftInput
                    id={`${id}${index}-height`}
                    value={line.height === undefined ? "" : String(line.height)}
                    label={`Height of ${noun.toLowerCase()} ${number}, in pixels`}
                    type="number"
                    inputMode="numeric"
                    disabled={readOnly}
                    onCommit={(raw) => {
                      const height = Math.round(Number(raw));
                      editor.updateRow(line.id, {
                        height: raw === "" || !Number.isFinite(height) ? undefined : Math.min(400, Math.max(16, height)),
                      });
                    }}
                  />
                </div>
              </div>
              <Errors messages={errors} id={`${id}${index}-error`} />
              {readOnly ? null : (
                <div className="sheet-form-actions" role="group" aria-label={`${noun} ${number}`}>
                  <button type="button" className="link-button" onClick={() => editor.moveRow(line.id, -1)} disabled={index === 0}>
                    Move {noun.toLowerCase()} {number} up
                  </button>
                  <button type="button" className="link-button" onClick={() => editor.moveRow(line.id, 1)} disabled={index === lines.length - 1}>
                    Move {noun.toLowerCase()} {number} down
                  </button>
                  <button type="button" className="link-button" onClick={() => editor.removeRow(line.id)}>
                    Remove {noun.toLowerCase()} {number}
                  </button>
                </div>
              )}
            </li>
          );
        })}
      </ol>
      {readOnly ? null : (
        <button
          type="button"
          className="button button-secondary"
          onClick={() => editor.addRow(zone, lines[lines.length - 1]?.id ?? null)}
          disabled={lines.length >= limit}
        >
          {zone === "header" ? "Add a header row" : "Add a row"}
        </button>
      )}
    </>
  );
}
