"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import type { AnswerMaps } from "@/lib/sheets/look/sheetLook";
import { SheetGrid } from "./SheetGrid";
import { SheetPrintView } from "./SheetPrintView";
import type { FillEditor } from "./useFillEditor";

/**
 * The View page of a fill-in (SH_6): the form as a person knows it from a spreadsheet, to read and to fill in. It is the
 * same grid as the Sheet tab, over the same editor and the same save queue, drawn without the band of column letters and the
 * row numbers, with the footer stamp under it. Nothing is copied: what is typed here is in the tab, and the reverse.
 *
 * Which cells may be typed in is the editor's rule (`canEditCell`), never a rule of this page. The buttons are Save, Print and
 * Back. Print opens the print drawing (the paper size and Portrait or Landscape are chosen in the browser's print window).
 * Back saves what a filler typed first and stays, with the server's words, when that is refused; a reviewer, whose every save is
 * a counted edit, is asked. It knows no business word and decides no permission.
 */
export function FillViewPage({
  editor,
  label,
  stamp,
  answers,
  readOnly,
  reviewer,
  dirty,
  saving,
  statusWord,
  refusal,
  popups,
  suspended,
  onSave,
  onBack,
  openCell,
}: {
  editor: FillEditor;
  /** What the fill-in is called for a screen reader. */
  label: string;
  /** The footer stamp (SH5.2), already made by the caller. */
  stamp: string;
  /** The answers of the formulas to draw: the browser's preview while typing, the server's after a save. */
  answers: AnswerMaps | null;
  /** The person may only look (a reader, a sent or approved or archived fill-in, a narrow screen). */
  readOnly: boolean;
  /** The person reviews a sent fill-in: no autosave, Back asks before it leaves unsaved cells. */
  reviewer: boolean;
  /** The cells differ from the last ones saved. */
  dirty: boolean;
  saving: boolean;
  /** Saving…, Saved or Unsaved changes: the same words as the strip. */
  statusWord: string;
  /** The notice of a refused save, drawn by the caller with the one component both places use. */
  refusal: ReactNode;
  /** The popups of the cells (a Yes/No reason, the files of a cell), drawn inside the page so that they lie above it. */
  popups: ReactNode;
  /** A popup of a cell is open over the page: Escape belongs to it. */
  suspended: boolean;
  /** Saves through the queue of the Save button; answers whether the cells are saved now. */
  onSave(): Promise<boolean>;
  /** Leaves the page. */
  onBack(): void;
  openCell(rowId: string, columnId: string): boolean;
}) {
  const [printing, setPrinting] = useState(false);
  const [asking, setAsking] = useState(false);
  const [working, setWorking] = useState(false);
  const first = useRef(editor);
  // What had the focus when the page was drawn (the View button), read before the grid takes the focus.
  const [opener] = useState(() => (document.activeElement instanceof HTMLElement ? document.activeElement : null));
  // What the Escape key needs to know when it is pressed, read without drawing the page again.
  const now = useRef({ printing, asking, suspended, dirty, readOnly, reviewer });
  useEffect(() => {
    now.current = { printing, asking, suspended, dirty, readOnly, reviewer };
  });

  // The focus goes to the first cell the person may type in, and returns to what opened the page when it closes.
  useEffect(() => {
    const start = first.current;
    for (let r = 0; r < start.rows.length; r++) {
      const row = start.rows[r];
      if (row?.zone !== "body") continue;
      const c = start.grid.columns.findIndex((column) => start.canEditCell(row.id, column.id));
      if (c >= 0) {
        start.select({ ar: r, ac: c, fr: r, fc: c });
        break;
      }
    }
    return () => opener?.focus();
    // the opener is read once, when the page is drawn
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const leave = async (save: boolean) => {
    setAsking(false);
    if (save && now.current.dirty) {
      setWorking(true);
      const ok = await onSave();
      setWorking(false);
      if (!ok) {
        // Stay: the person keeps the cells, and the refusal is shown here in the server's words.
        return;
      }
    }
    onBack();
  };

  // Back: a filler's unsaved cells are saved first; a reviewer is asked, because every save of theirs is one counted edit.
  const goBack = () => {
    const state = now.current;
    if (state.readOnly || !state.dirty) {
      onBack();
    } else if (state.reviewer) {
      setAsking(true);
    } else {
      void leave(true);
    }
  };
  const goBackRef = useRef(goBack);
  useEffect(() => {
    goBackRef.current = goBack;
  });

  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      const state = now.current;
      // A popup, the print drawing or a cell being typed in takes the key first.
      if (state.suspended || state.printing) return;
      if (state.asking) {
        setAsking(false);
        return;
      }
      goBackRef.current();
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, []);

  const busy = saving || working;
  const page = (
    <div className="fill-view-root" role="dialog" aria-modal="true" aria-label={`Page view of ${label}`} data-testid="fill-view-page">
      <div className="fill-view-bar">
        {readOnly ? null : (
          <button type="button" className="button" onClick={() => void onSave()} disabled={busy || !dirty}>
            {saving ? "Saving…" : "Save"}
          </button>
        )}
        <button type="button" className="button button-secondary" onClick={() => setPrinting(true)}>
          Print
        </button>
        <button type="button" className="button button-secondary" onClick={goBack} disabled={working}>
          Back
        </button>
        {readOnly ? null : (
          <span className="hint" role="status" data-testid="fill-view-state">
            {statusWord}
          </span>
        )}
      </div>
      {asking ? (
        <div className="form-message fill-view-ask" role="alertdialog" aria-label="Unsaved entries" data-testid="fill-view-ask">
          <p>You have entries that are not saved.</p>
          <p>
            <button type="button" className="button" onClick={() => void leave(true)}>
              Save and go back
            </button>{" "}
            <button type="button" className="button button-secondary" onClick={() => void leave(false)}>
              Go back without saving
            </button>
          </p>
          <p className="hint">If you go back without saving, your entries stay on the Sheet tab, still unsaved.</p>
        </div>
      ) : null}
      {refusal}
      <div className="fill-view-body" data-testid="fill-view-body">
        <SheetGrid
          paper
          autoFocus
          liveInput
          editor={editor}
          readOnly={readOnly}
          canEditCell={editor.canEditCell}
          formulaAnswers={answers?.text}
          formulaKinds={answers?.kinds}
          footer={stamp === "" ? undefined : stamp}
          label={label}
          openCell={openCell}
        />
      </div>
      {popups}
      {printing ? (
        <SheetPrintView
          doc={editor.doc}
          showInput
          answers={answers ?? undefined}
          label={label}
          footer={stamp}
          onClose={() => setPrinting(false)}
        />
      ) : null}
    </div>
  );
  return createPortal(page, document.body);
}
