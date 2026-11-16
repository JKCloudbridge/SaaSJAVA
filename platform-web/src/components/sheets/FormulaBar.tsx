"use client";

import { useId, useState } from "react";
import { cellAddress } from "@/lib/sheets/addresses";
import type { SheetEditor } from "./useSheetEditor";

export interface FormulaBarProps {
  editor: SheetEditor;
  /** The answer of the formula of the selected cell, as plain text (the server's when the document is the saved one). */
  answer?: string;
}

/**
 * The formula bar of the author's grid (SH2, ADR-0252): it shows the spreadsheet-style text of the selected cell, its
 * formula with the equals sign or its fixed text, and edits it. Typing a text that starts with an equals sign gives the
 * cell a formula; Enter or leaving the box applies it. The browser checks the formula at once with its own gate and shows
 * the reason in the server's words; the server decides on save and its answer is the one that counts. The answer is
 * shown as plain text and an error as its code. Nothing here picks cells with the mouse, fills down or lists functions.
 */
export function FormulaBar({ editor, answer }: FormulaBarProps) {
  const { rows, selection, doc } = editor;
  const row = rows[selection.fr];
  const column = doc.columns[selection.fc];
  const editable = row !== undefined && column !== undefined && row.zone !== "blank";
  const key = row && column ? `${row.id}:${column.id}` : "";
  const formula = row && column ? editor.formulaText(row.id, column.id) : "";
  const cellValue = row && column ? doc.cells[key]?.value : undefined;
  const current = formula !== "" ? formula : typeof cellValue === "string" ? cellValue : "";
  return <FormulaBarInput key={`${key}|${current}`} editor={editor} rowId={row?.id} columnId={column?.id} current={current}
    editable={editable} answer={formula !== "" ? answer : undefined}
    address={row && column ? cellAddress(selection.fr, selection.fc) : ""} />;
}

function FormulaBarInput({
  editor,
  rowId,
  columnId,
  current,
  editable,
  answer,
  address,
}: {
  editor: SheetEditor;
  rowId: string | undefined;
  columnId: string | undefined;
  current: string;
  editable: boolean;
  answer?: string;
  /** The address of the selected cell as the formulas write it, such as C6 (SH5.1). */
  address: string;
}) {
  const id = useId();
  const [draft, setDraft] = useState(current);
  const problem = rowId ? editor.checkFormula(rowId, draft) : null;
  const apply = () => {
    if (!rowId || !columnId || draft === current) return;
    editor.setText(rowId, columnId, draft);
  };
  return (
    <div className="sheet-formula-bar" data-testid="formula-bar">
      {/* One line that never wraps (SH5.1): choosing a formula cell adds its answer and the score box, and a line that
          grew or wrapped moved the grid under the pointer and made the page shake. */}
      <div className="sheet-formula-line">
        {/* The cell chosen, before the formula, as a spreadsheet's name box (SH5.1): the same address the formulas use. */}
        <output className="sheet-name-box" aria-label="Selected cell" data-testid="name-box">
          {address}
        </output>
        <label htmlFor={id}>
          <span className="visually-hidden">Formula bar</span>
          <span aria-hidden="true">fx</span>
        </label>
        <input
          id={id}
          type="text"
          value={draft}
          disabled={!editable}
          autoComplete="off"
          spellCheck={false}
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? `${id}-problem` : undefined}
          placeholder={editable ? "Type text, or = and a formula" : ""}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={apply}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              apply();
            } else if (event.key === "Escape") {
              event.preventDefault();
              setDraft(current);
            }
          }}
        />
        {answer !== undefined ? (
          <span className="hint sheet-formula-answer" data-testid="formula-answer" title={answer}>
            Answer: {answer === "" ? "(empty)" : answer}
          </span>
        ) : null}
        {rowId && columnId && editor.canMarkScore(rowId, columnId) ? (
          <label className="hint" data-testid="score-mark">
            <input
              type="checkbox"
              checked={editor.isScore(rowId, columnId)}
              onChange={(event) => editor.markScore(rowId, columnId, event.target.checked)}
            />{" "}
            Use as the score
          </label>
        ) : null}
      </div>
      {problem ? (
        <p className="field-error" id={`${id}-problem`} role="alert">
          {problem}
        </p>
      ) : null}
    </div>
  );
}
