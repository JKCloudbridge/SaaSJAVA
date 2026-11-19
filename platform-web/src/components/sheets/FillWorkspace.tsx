"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { fromApi } from "@/lib/sheets/adapter";
import {
  getFill,
  useFill,
  useFillCalculation,
  type FillOwner,
  type FillSummary,
  type FillView,
} from "@/lib/sheets/fillApi";
import {
  answersByCell,
  fromApiFill,
  hasFormulas,
  isInputCell,
  previewAnswers,
  problemsByCell,
  reapplyFill,
  toApiFill,
} from "@/lib/sheets/fillModel";
import { specOf } from "@/lib/sheets/formula/edit";
import { fillStatusTag, fillStatusWords, versionWords } from "@/lib/sheets/fillTags";
import type { FillDoc } from "@/lib/sheets/fillTypes";
import { problemsFromFields } from "@/lib/sheets/hints";
import type { GridDoc, Problem } from "@/lib/sheets/types";
import { promptOf } from "@/lib/sheets/look/display";
import { ChoiceDialog } from "./ChoiceField";
import { AttachmentDialog } from "./AttachmentDialog";
import { cellAddress } from "@/lib/sheets/addresses";
import type { FillValue } from "@/lib/sheets/fillTypes";
import { FillForm } from "./FillForm";
import { SheetGrid } from "./SheetGrid";
import { FillRefusal } from "./FillRefusal";
import { FillViewPage } from "./FillViewPage";
import type { EditAs } from "@/lib/sheets/fillModel";
import { useFillEditor } from "./useFillEditor";
import { useNarrowScreen } from "./useNarrowScreen";
import { FillToolbar } from "./FillToolbar";
import { useFillAutosave } from "./useFillAutosave";
import { useSaveBeforeLeave } from "./useSaveBeforeLeave";
import { revisionWords, submittedLine, withSubmittedLine } from "@/lib/sheets/revision";

/** The file identifiers an attachment cell names now (an empty list when it names none). */
function filesIn(value: FillValue | undefined): string[] {
  return Array.isArray(value) ? value.filter((one): one is string => typeof one === "string") : [];
}

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
  /**
   * The page's Submit (FA6A): drawn beside Save while the person may edit. The cells are saved first, and nothing is sent
   * when they cannot be; then the page's call runs and answers with words to show, and whether it worked.
   */
  submit?(): Promise<{ ok: boolean; message?: string }>;
  /** Cancel saves a draft before leaving. A refusal requires an explicit second click to discard. */
  onCancel?(): void;
  registerLeave?(leave: (next: () => void) => Promise<void>): void;
  /**
   * Lets the page save what was typed before it sends a decision on the fill-in (SH5.1): the page keeps the function and
   * calls it first. It saves through the same queue as the Save button and answers whether the cells are saved now; when
   * they are not (the server refused), `message` says why in the server's words and nothing may be sent.
   */
  registerBeforeDecision?(save: () => Promise<{ ok: boolean; message?: string }>): void;
  /**
   * The text at the right of the footer row of the View page: the caller's words (the template number and the revision the
   * fill-in was made on). A function is asked for the stored number of that revision. Empty leaves the row blank.
   */
  footer?: string | ((versionNo: number) => string);
  /**
   * Where the one strip of buttons (the two views, View, Save, Submit, Cancel and the saved state) is drawn: the row of the
   * page's tabs. Without it the strip is drawn above the sheet; `null` draws it nowhere (the sheet's tab is not open).
   */
  toolbarSlot?: HTMLElement | null;
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
export function FillWorkspace({
  owner: given,
  label,
  refreshToken = 0,
  onDirty,
  onSaved,
  onLoaded,
  submit,
  onCancel,
  registerLeave,
  registerBeforeDecision,
  footer,
  toolbarSlot,
}: FillWorkspaceProps) {
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
      key={`${state.view.fill.fillId}-${refresh}`}
      owner={owner}
      label={label}
      view={state.view}
      onDirty={setDirty}
      onSaved={onSaved}
      onLoaded={onLoaded}
      submit={submit}
      onCancel={onCancel}
      registerLeave={registerLeave}
      registerBeforeDecision={registerBeforeDecision}
      footer={footer}
      toolbarSlot={toolbarSlot}
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
  submit,
  onCancel,
  onReload,
  registerLeave,
  registerBeforeDecision,
  footer,
  toolbarSlot,
}: {
  footer: FillWorkspaceProps["footer"];
  toolbarSlot: FillWorkspaceProps["toolbarSlot"];
  owner: FillOwner;
  label: string;
  view: FillView;
  onDirty(dirty: boolean): void;
  onSaved: ((summary: FillSummary) => void) | undefined;
  onLoaded: ((summary: FillSummary) => void) | undefined;
  submit: FillWorkspaceProps["submit"];
  onCancel: FillWorkspaceProps["onCancel"];
  onReload(): void;
  registerLeave: FillWorkspaceProps["registerLeave"];
  registerBeforeDecision: FillWorkspaceProps["registerBeforeDecision"];
}) {
  const grid = useMemo<GridDoc>(() => fromApi(view.grid), [view.grid]);
  const initial = useMemo<FillDoc>(() => fromApiFill(view.cells), [view.cells]);
  const editor = useFillEditor(grid, initial, view.limits, view.editAs as EditAs);
  const narrow = useNarrowScreen();
  const [tab, setTab] = useState<"form" | "grid">(narrow ? "form" : "grid");
  const [summary, setSummary] = useState(view.fill);
  const [sending, setSending] = useState(false);
  const [sendNote, setSendNote] = useState<{ error: boolean; text: string } | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [viewing, setViewing] = useState(false);
  // The cell whose own popup is open in the grid (SH5.1): a Yes/No cell with a reason, or an attachment cell.
  const [popup, setPopup] = useState<{ rowId: string; columnId: string; kind: "choice" | "files" } | null>(null);
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

  // The words of the last refusal of a save, for the page's decision to say why nothing was sent (SH5.1).
  const refusedWords = useRef("");
  const showing = refused;
  const targets = useMemo(
    () => (showing ? problemsByCell(grid, showing.sent, showing.problems) : null),
    [grid, showing],
  );

  const autosave = useFillAutosave({
    owner, grid, fill: editor.fill, initial, version: summary.version, dirty,
    enabled: !readOnly && !summary.archivedAt && (summary.status === "DRAFT" || summary.status === "RETURNED"),
    accepted: (sent, result) => {
      setSummary(result);
      editor.markSaved(sent);
      setRefused(null);
      onSaved?.(result);
    },
    refused: (sent, failure) => {
      refusedWords.current = failure.message;
      setRefused({
        sent,
        problems: problemsFromFields(failure.fields), message: failure.message,
        stale: failure.code === "CONCURRENT_MODIFICATION" || failure.code === "CONFLICT",
      });
    },
  });
  const { saving, saved } = autosave;
  // The server's answers of the formulas (SH5): asked for again after every save. The row an answer belongs to is read
  // from the cells as they were saved, because that is the document the server calculated.
  const { answer: calculation, pending } = useFillCalculation(owner, summary.version, hasFormulas(grid));
  const answers = useMemo(
    () =>
      calculation
        ? answersByCell(calculation, toApiFill(grid, autosave.baseline.current).rows.map((row) => row.id))
        : null,
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the baseline is a ref read once when an answer arrives
    [calculation, grid],
  );
  // While there are changes that are not saved, and while the server's answers for what was just saved are on their way,
  // the browser's own answers are drawn (SH5.1). They come from the cells as they are and the server's table of functions,
  // and they are only a preview: the server calculates again at every save and its answers replace them.
  const spec = useMemo(() => specOf(editor.limits.formulas), [editor.limits.formulas]);
  const preview = useMemo(
    () => (dirty || pending ? previewAnswers(grid, editor.fill, spec) : null),
    [dirty, pending, grid, editor.fill, spec],
  );
  const shown = dirty || pending ? preview : answers;
  // Taking a file off changes the stored cells on the server, so the page reads the fill-in again afterwards; what was
  // typed before was saved first (`beforeRemove`), so nothing is lost.
  const afterRemove = async () => {
    const answer = await getFill(owner);
    if (!answer.ok) {
      setRefused((held) => held ?? { sent: editor.fill, problems: [], message: answer.failure.message, stale: false });
      return;
    }
    const remote = fromApiFill(answer.data.cells);
    autosave.rebase(remote, answer.data.fill.version);
    editor.reset(remote);
    setSummary(answer.data.fill);
  };
  const save = () => autosave.flush(true);
  const leave = async (next: () => void) => {
    if (!dirty || await autosave.flush()) next();
  };
  useSaveBeforeLeave(dirty, () => autosave.flush());
  useEffect(() => { registerLeave?.(leave); });
  // A decision on the fill-in saves what was typed first, through the queue of the Save button (SH5.1): the score is worked
  // out at approval from the cells that are saved. One decision is at most one save, and a refused save sends nothing.
  const beforeDecision = async (): Promise<{ ok: boolean; message?: string }> => {
    if (!dirty || await autosave.flush(true)) return { ok: true };
    return {
      ok: false,
      message: `Your entries could not be saved, so nothing was decided. ${refusedWords.current} `
        + "They are kept on the Sheet tab.",
    };
  };
  useEffect(() => { registerBeforeDecision?.(beforeDecision); });

  const reloadAndReapply = async () => {
    const answer = await getFill(owner);
    if (!answer.ok) {
      setRefused((held) => held && { ...held, message: answer.failure.message });
      return;
    }
    const mayStill = ["DRAFT", "RETURNED"].includes(answer.data.fill.status) || answer.data.editAs === "REVIEWER";
    if (!answer.data.canEdit || answer.data.fill.archivedAt || !mayStill) {
      setRefused((held) => held && { ...held, message: "This fill-in can no longer be edited. Your changes are kept here." });
      return;
    }
    const remote = fromApiFill(answer.data.cells);
    const reapplied = reapplyFill(autosave.baseline.current, editor.fill, remote, grid);
    autosave.rebase(remote, answer.data.fill.version);
    editor.reset(remote);
    editor.reapply(reapplied);
    setSummary(answer.data.fill);
    setRefused(null);
  };

  // Submit saves what was typed, then sends it. Nothing is sent when the save is refused, and what was typed stays.
  const send = async () => {
    if (!submit) {
      return;
    }
    setSending(true);
    setSendNote(null);
    setLeaving(false);
    if (dirty && !(await save())) {
      setSending(false);
      return;
    }
    const answer = await submit();
    setSending(false);
    setSendNote(answer.message ? { error: !answer.ok, text: answer.message } : null);
  };

  const cancel = async () => {
    if (dirty && !leaving && await autosave.flush()) {
      onCancel?.();
      return;
    }
    if (dirty && !leaving) {
      setLeaving(true);
      return;
    }
    onCancel?.();
  };

  // The stamp that closes the sheet in the grid, the form and View (SH5.2): the number and Revision (and the publish day when
  // the caller knows it), then who sent it and when once it was sent. Made at show time; never a cell, never stored.
  const stamp = withSubmittedLine(
    (typeof footer === "function" ? footer(summary.versionNo) : footer) ?? "",
    submittedLine(summary.submittedBy, summary.submittedAt),
  );

  const narrowNote =narrow && !readOnly ? "On a narrow screen the grid is for reading. Fill in the form view." : undefined;

  // What the removed sentences said stays one hover away on the status, so nothing a person may need is lost with the lines.
  const hover = [
    fillStatusWords(summary.status),
    summary.corrections > 0
      ? `Corrected ${summary.corrections === 1 ? "once" : `${summary.corrections} times`} after it was sent.`
      : "",
    view.correction && !readOnly
      ? "You may correct a fill-in after it is sent. Every correction is recorded in the audit trail, with its size and a fingerprint, never its content."
      : "",
    dirty && preview ? "Answers are a preview until you save." : "",
    `${revisionWords(summary.versionNo)} of the template, ${summary.rowCount} ${summary.rowCount === 1 ? "row" : "rows"}.`,
    readOnly ? "" : "Changes save after a short pause. Submit and Cancel save them first.",
  ]
    .filter(Boolean)
    .join(" ");
  const savedWord = saving ? "Saving…" : saved && !dirty ? "Saved" : dirty ? "Unsaved changes" : "";

  // One strip: the two views, View, Save, Submit, Cancel and the saved state. It is drawn in the row of the page's tabs, so
  // nothing above the sheet takes room.
  const strip = (
    <div className="sheet-strip" data-testid="fill-strip">
      <span className="badge" title={hover} data-testid="fill-status-tag">
        {tag.text}
      </span>
      {readOnly ? null : (
        <span className="hint" role="status" title={hover} data-testid="fill-saved-state">
          {savedWord}
        </span>
      )}
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
      <button type="button" className="button button-secondary" data-testid="fill-view" onClick={() => setViewing(true)}>
        View
      </button>
      {readOnly ? null : (
        <>
          <button
            type="button"
            className="button button-secondary"
            onClick={() => void save()}
            disabled={saving || sending || !dirty}
          >
            {saving ? "Saving…" : "Save"}
          </button>
          {submit ? (
            <button type="button" className="button" onClick={() => void send()} disabled={saving || sending}>
              {sending ? "Submitting…" : "Submit"}
            </button>
          ) : null}
          {onCancel ? (
            <button type="button" className="button button-secondary" onClick={() => void cancel()} disabled={sending || saving}>
              {leaving ? "Leave without saving" : "Cancel"}
            </button>
          ) : null}
        </>
      )}
    </div>
  );

  // The notice of a refused save: under the strip on the Sheet tab, and on the View page while that is open (SH_6).
  const refusalNotice = showing ? (
    <FillRefusal grid={grid} refusal={showing} onReload={onReload} onReloadAndReapply={() => void reloadAndReapply()} />
  ) : null;

  // The cell that is filled through its own popup is opened from the Sheet tab and from the View page alike.
  const openPopup = (rowId: string, columnId: string): boolean => {
    if (!isInputCell(grid, rowId, columnId)) return false;
    const type = editor.spec(rowId, columnId).type;
    if (type !== "boolean_reason" && type !== "attachment") return false;
    setPopup({ rowId, columnId, kind: type === "attachment" ? "files" : "choice" });
    return true;
  };

  // The popups of the cells. While the View page is open they are drawn inside it, so that they lie above it.
  const popups = (
    <>
      {popup?.kind === "files" ? (
        <AttachmentDialog
          object={owner.object}
          recordId={owner.id}
          place={cellAddress(
            Math.max(0, editor.rows.findIndex((row) => row.id === popup.rowId)),
            Math.max(0, grid.columns.findIndex((column) => column.id === popup.columnId)),
          )}
          value={filesIn(editor.fill.rows.find((row) => row.id === popup.rowId)?.cells[popup.columnId])}
          maxFiles={editor.spec(popup.rowId, popup.columnId).maxFiles}
          required={editor.spec(popup.rowId, popup.columnId).required}
          readOnly={readOnly || narrow || !!summary.archivedAt || !editor.canEditCell(popup.rowId, popup.columnId)}
          onChange={(files) => editor.setFiles(popup.rowId, popup.columnId, files)}
          beforeRemove={() => autosave.flush(true)}
          afterRemove={afterRemove}
          onClose={() => setPopup(null)}
        />
      ) : null}
      {popup?.kind === "choice" ? (
        <ChoiceDialog
          prompt={promptOf(editor.grid, popup.rowId, popup.columnId)}
          value={editor.fill.rows.find((row) => row.id === popup.rowId)?.cells[popup.columnId]}
          required={editor.spec(popup.rowId, popup.columnId).required}
          readOnly={readOnly || narrow || !editor.canEditCell(popup.rowId, popup.columnId)}
          onApply={(choice) => editor.setChoice(popup.rowId, popup.columnId, choice)}
          onClose={() => setPopup(null)}
        />
      ) : null}
    </>
  );

  return (
    <div className="stack sheet-editor" data-testid="fill-editor">
      {toolbarSlot === undefined ? strip : toolbarSlot ? createPortal(strip, toolbarSlot) : null}
      {sendNote ? (
        <p
          className={sendNote.error ? "form-message" : "hint"}
          role={sendNote.error ? "alert" : "status"}
          data-testid="send-note"
        >
          {sendNote.text}
        </p>
      ) : null}
      {leaving ? (
        <p className="hint" role="note">
          The changes could not be saved. Press Cancel again to leave without them, or press Save to try again.
        </p>
      ) : null}
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
      {view.editAs === "REVIEWER" && !readOnly ? (
        <p className="hint" role="note" data-testid="fill-review-note">
          You are asked to review this fill-in. You can fill the cells meant for the reviewer; everything else is read
          only. Your entries are kept when you press Save, and a decision saves them first. Every change you save is
          recorded in the audit trail, with its size and a fingerprint, never its content.
        </p>
      ) : null}
      {viewing ? null : refusalNotice}

      {/* The View page is a modal over the tab: the tab stays mounted (nothing typed is lost) but is out of reach. */}
      <div inert={viewing}>
      {tab === "form" ? (
        <div role="tabpanel" id={`${tabs}-form`} aria-labelledby={`${tabs}-form-tab`}>
          <FillForm
            editor={editor}
            readOnly={readOnly}
            problems={targets?.cells ?? new Map()}
            answers={shown}
            footer={stamp}
            attach={{
              object: owner.object,
              recordId: owner.id,
              beforeRemove: () => autosave.flush(true),
              afterRemove,
            }}
          />
        </div>
      ) : (
        <div role="tabpanel" id={`${tabs}-grid`} aria-labelledby={`${tabs}-grid-tab`}>
          <div className="sheet-view" data-testid="fill-grid-view">
            {narrowNote ? (
              <p className="hint" role="note">
                {narrowNote}
              </p>
            ) : null}
            <FillToolbar editor={editor} readOnly={readOnly || narrow} />
            <SheetGrid
              liveInput
              editor={editor}
              readOnly={readOnly || narrow}
              canEditCell={editor.canEditCell}
              formulaAnswers={shown?.text}
              formulaKinds={shown?.kinds}
              footer={stamp === "" ? undefined : stamp}
              label={`${label}, ${revisionWords(summary.versionNo).toLowerCase()}`}
              openCell={openPopup}
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
      {viewing ? null : popups}
      {viewing ? (
        <FillViewPage
          editor={editor}
          label={label}
          stamp={stamp}
          answers={shown}
          readOnly={readOnly || narrow}
          reviewer={view.editAs === "REVIEWER"}
          dirty={dirty}
          saving={saving}
          statusWord={savedWord}
          refusal={refusalNotice}
          popups={popups}
          suspended={popup !== null}
          onSave={save}
          onBack={() => setViewing(false)}
          openCell={openPopup}
        />
      ) : null}
    </div>
  );
}
