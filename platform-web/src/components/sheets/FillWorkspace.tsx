"use client";

import { useEffect, useId, useMemo, useState } from "react";
import { fromApi } from "@/lib/sheets/adapter";
import { saveFillCells, useFill, type FillOwner, type FillSummary, type FillView } from "@/lib/sheets/fillApi";
import { explainFillPlace, fromApiFill, problemsByCell } from "@/lib/sheets/fillModel";
import { fillStatusTag, fillStatusWords, versionWords } from "@/lib/sheets/fillTags";
import type { FillDoc } from "@/lib/sheets/fillTypes";
import { problemsFromFields } from "@/lib/sheets/hints";
import type { GridDoc, Problem } from "@/lib/sheets/types";
import { FillForm } from "./FillForm";
import { SheetGrid } from "./SheetGrid";
import { useFillEditor } from "./useFillEditor";
import { useNarrowScreen } from "./useNarrowScreen";

export interface FillWorkspaceProps {
  /** The header record the fill-in belongs to. */
  owner: FillOwner;
  /** What the fill-in is called for a screen reader. */
  label: string;
  /**
   * A number the screen raises when something outside the cells changed what the fill-in may do (it was sent, decided or
   * returned). The workspace reads the fill-in again, unless there are unsaved changes: those are never thrown away.
   */
  refreshToken?: number;
  /** Told whenever the cells differ from the last ones saved, so that the page can warn before a fill-in is sent. */
  onDirty?(dirty: boolean): void;
  /** Told after the cells were saved. */
  onSaved?(summary: FillSummary): void;
  /** Told whenever a fill-in has been read: the facts of it, for a page that shows more than the cells (FF2). */
  onLoaded?(summary: FillSummary): void;
}

/**
 * Everything a person does with the cells of one fill-in (Functional track FF1, ADR-0192): see the sheet version it was
 * made on with what they typed, type into the input cells in the grid view or, on a narrow screen, the form view, add
 * rows where the sheet allows it, and save with the row version that was read. It draws what the API answers, in the
 * API's words with the place that is wrong; it decides no permission: it offers typing only when the API says the caller
 * may, and every request is decided again by the server.
 *
 * It knows no business word: the header record is a parameter.
 */
export function FillWorkspace({ owner: given, label, refreshToken = 0, onDirty, onSaved, onLoaded }: FillWorkspaceProps) {
  const owner = useMemo<FillOwner>(() => ({ object: given.object, id: given.id }), [given.object, given.id]);
  const [dirty, setDirty] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [seen, setSeen] = useState(refreshToken);
  const { state, reload } = useFill(owner, refresh);

  // Something outside the cells changed what the fill-in may do: read it again, but never over unsaved changes.
  useEffect(() => {
    if (refreshToken === seen) {
      return;
    }
    void Promise.resolve().then(() => {
      setSeen(refreshToken);
      if (!dirty) {
        setRefresh((n) => n + 1);
      }
    });
  }, [refreshToken, seen, dirty]);

  useEffect(() => {
    onDirty?.(dirty);
  }, [dirty, onDirty]);

  if (state.status === "loading") {
    return (
      <p role="status" className="hint">
        Opening the fill-in…
      </p>
    );
  }
  if (state.status === "failed") {
    return (
      <div role="alert" className="form-message">
        <p>{state.failure.message}</p>
        <button type="button" className="button button-secondary" onClick={reload}>
          Try again
        </button>
      </div>
    );
  }
  return (
    <FillPane
      key={`${state.view.fill.fillId}-${refresh}-${seen}`}
      owner={owner}
      label={label}
      view={state.view}
      onDirty={setDirty}
      onSaved={onSaved}
      onLoaded={onLoaded}
      onReload={() => setRefresh((n) => n + 1)}
    />
  );
}

function FillPane({
  owner,
  label,
  view,
  onDirty,
  onSaved,
  onLoaded,
  onReload,
}: {
  owner: FillOwner;
  label: string;
  view: FillView;
  onDirty(dirty: boolean): void;
  onSaved: ((summary: FillSummary) => void) | undefined;
  onLoaded: ((summary: FillSummary) => void) | undefined;
  onReload(): void;
}) {
  const grid = useMemo<GridDoc>(() => fromApi(view.grid), [view.grid]);
  const initial = useMemo<FillDoc>(() => fromApiFill(view.cells), [view.cells]);
  const editor = useFillEditor(grid, initial, view.limits);
  const narrow = useNarrowScreen();
  const [tab, setTab] = useState<"form" | "grid">(narrow ? "form" : "grid");
  const [summary, setSummary] = useState(view.fill);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [refused, setRefused] = useState<{ sent: FillDoc; problems: Problem[]; message: string; stale: boolean } | null>(
    null,
  );
  const tabs = useId();

  const readOnly = !view.canEdit;
  const tag = fillStatusTag(summary.status);
  const sheetWords = versionWords(summary.versionNo, view.fill.versionStatus);
  const { dirty } = editor;

  useEffect(() => {
    onDirty(dirty);
  }, [dirty, onDirty]);
  useEffect(() => {
    onLoaded?.(view.fill);
  }, [view.fill, onLoaded]);
  useEffect(() => {
    if (!dirty) {
      return;
    }
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const showing = refused && refused.sent === editor.fill ? refused : null;
  const targets = useMemo(
    () => (showing ? problemsByCell(grid, showing.sent, showing.problems) : null),
    [grid, showing],
  );

  const save = async () => {
    setSaving(true);
    setSaved(false);
    const sent = editor.fill;
    const outcome = await saveFillCells(owner, summary.version, grid, sent);
    setSaving(false);
    if (outcome.ok) {
      setSummary(outcome.data);
      editor.markSaved(sent);
      setRefused(null);
      setSaved(true);
      onSaved?.(outcome.data);
      return;
    }
    setRefused({
      sent,
      problems: problemsFromFields(outcome.failure.fields),
      message: outcome.failure.message,
      stale: outcome.failure.code === "CONCURRENT_MODIFICATION" || outcome.failure.code === "CONFLICT",
    });
  };

  const narrowNote = narrow && !readOnly ? "On a narrow screen the grid is for reading. Fill in the form view." : undefined;

  return (
    <div className="stack sheet-editor" data-testid="fill-editor">
      <div className="sheet-bar">
        <p className="sheet-bar-title">
          <strong>{tag.text}</strong> · version {summary.versionNo} of the template · {summary.rowCount}{" "}
          {summary.rowCount === 1 ? "row" : "rows"}
          {summary.corrections > 0
            ? ` · corrected ${summary.corrections === 1 ? "once" : `${summary.corrections} times`} after it was sent`
            : ""}
        </p>
        {readOnly ? null : (
          <div className="sheet-bar-actions">
            <button type="button" className="button" onClick={() => void save()} disabled={saving || !dirty}>
              {saving ? "Saving…" : "Save"}
            </button>
            <span className="hint" role="status">
              {saved && !dirty ? "All changes are saved." : dirty ? "There are changes that are not saved." : ""}
            </span>
          </div>
        )}
      </div>
      <p className="hint" role="note" data-testid="fill-status-note">
        {fillStatusWords(summary.status)}
      </p>
      {summary.archivedAt ? (
        <p className="hint" role="note" data-testid="fill-archived-note">
          This fill-in is archived. It is kept exactly as it was, read back when you open it, and it cannot be changed.
        </p>
      ) : null}
      {sheetWords ? (
        <p className="hint" role="note" data-testid="fill-version-note">
          {sheetWords}
        </p>
      ) : null}
      {view.correction && !readOnly ? (
        <p className="hint" role="note" data-testid="fill-correction-note">
          This fill-in was already sent. You can correct it because you may edit a fill-in after it is sent. Every
          correction is recorded in the audit trail, with its size and a fingerprint, never its content.
        </p>
      ) : null}
      {showing ? (
        <div role="alert" className="form-message" data-testid="save-refused">
          <p>{showing.message}</p>
          {showing.problems.length > 0 ? (
            <ul>
              {showing.problems.map((problem, i) => (
                <li key={`${problem.place}-${i}`}>
                  <strong>{explainFillPlace(grid, showing.sent, problem.place)}:</strong> {problem.message}
                </li>
              ))}
            </ul>
          ) : null}
          {showing.stale ? (
            <p>
              <button type="button" className="button button-secondary" onClick={onReload}>
                Reload this fill-in
              </button>{" "}
              <span className="hint">Your unsaved changes are lost when you reload.</span>
            </p>
          ) : null}
        </div>
      ) : null}

      <div role="tablist" aria-label="Views of the fill-in" className="sheet-tabs">
        <button
          type="button"
          role="tab"
          id={`${tabs}-form-tab`}
          aria-selected={tab === "form"}
          aria-controls={`${tabs}-form`}
          className={tab === "form" ? "button" : "button button-secondary"}
          onClick={() => setTab("form")}
        >
          Form view
        </button>
        <button
          type="button"
          role="tab"
          id={`${tabs}-grid-tab`}
          aria-selected={tab === "grid"}
          aria-controls={`${tabs}-grid`}
          className={tab === "grid" ? "button" : "button button-secondary"}
          onClick={() => setTab("grid")}
        >
          Grid view
        </button>
      </div>
      {tab === "form" ? (
        <div role="tabpanel" id={`${tabs}-form`} aria-labelledby={`${tabs}-form-tab`}>
          <FillForm editor={editor} readOnly={readOnly} problems={targets?.cells ?? new Map()} />
        </div>
      ) : (
        <div role="tabpanel" id={`${tabs}-grid`} aria-labelledby={`${tabs}-grid-tab`}>
          <div className="sheet-view" data-testid="fill-grid-view">
            {narrowNote ? (
              <p className="hint" role="note">
                {narrowNote}
              </p>
            ) : null}
            {readOnly || narrow ? null : (
              <div className="sheet-toolbar" role="toolbar" aria-label="Fill-in">
                <button type="button" className="button button-secondary" onClick={editor.undo} disabled={!editor.canUndo}>
                  Undo
                </button>
                <button type="button" className="button button-secondary" onClick={editor.redo} disabled={!editor.canRedo}>
                  Redo
                </button>
                {grid.rowsMode === "FIXED" ? null : (
                  <>
                    <span className="sheet-toolbar-gap" aria-hidden="true" />
                    <button type="button" className="button button-secondary" onClick={editor.addRow}>
                      Add row
                    </button>
                    <button
                      type="button"
                      className="button button-secondary"
                      disabled={!editor.rows[editor.selection.fr] || editor.rows[editor.selection.fr]?.zone !== "body"}
                      onClick={() => {
                        const row = editor.rows[editor.selection.fr];
                        if (row) {
                          editor.removeRow(row.id);
                        }
                      }}
                    >
                      Remove row
                    </button>
                  </>
                )}
              </div>
            )}
            <SheetGrid
              editor={editor}
              readOnly={readOnly || narrow}
              canEditCell={editor.canEditCell}
              label={`${label}, version ${summary.versionNo}`}
            />
            <p className="hint">
              {readOnly
                ? "Arrow keys move; Ctrl+C copies."
                : "Arrow keys move, type or press Enter to fill a cell, Delete clears it. Cells with fixed text from the sheet cannot be typed into. Ctrl+Z undoes."}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
