"use client";

import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Modal } from "@/components/ui/Modal";
import { fromApi } from "@/lib/sheets/adapter";
import {
  saveGrid,
  startVersion,
  type NewVersion,
  type SheetOwner,
  type VersionSummary,
} from "@/lib/sheets/api";
import { explainPlace, hintsFor, problemsFromFields } from "@/lib/sheets/hints";
import { defaultVersion, useServerAnswers, useVersion, useVersions } from "@/lib/sheets/hooks";
import { revisionWords, versionTitle } from "@/lib/sheets/revision";
import { isDraft } from "@/lib/sheets/tags";
import type { GridDoc, Problem, SheetLimits } from "@/lib/sheets/types";
import { FormView } from "./FormView";
import { GridView } from "./GridView";
import { SheetPrintView } from "./SheetPrintView";
import { useNarrowScreen } from "./useNarrowScreen";
import { useSheetEditor } from "./useSheetEditor";
import { UploadSheet } from "./UploadSheet";
import { VersionList } from "./VersionList";

/**
 * What a version is called at the start of a sentence: its revision once it was in use, else "This copy" (a draft, a copy in
 * review and a copy that was rejected are not revisions, so "Draft is a draft" and the like are never written).
 */
function sentenceName(summary: { versionNo: number; status: string; revisionNo?: number | null }): string {
  const title = versionTitle(summary);
  return title.startsWith("Revision ") ? title : "This copy";
}

export interface SheetWorkspaceProps {
  /** The record the sheet belongs to. */
  owner: SheetOwner;
  /** What the sheet is called for a screen reader (the name of the record it belongs to). */
  label: string;
  /**
   * Whether the screen believes the member may correct a version that is in review (the approver's edit, which the server
   * audits, FA4). It only decides whether the grid is drawn editable; the server judges every save again.
   */
  correctInReview?: boolean;
  /**
   * A number the screen raises when something outside the sheet changed what the sheet may do (an approval was
   * submitted, decided or ended, a version was published). The workspace reads the versions again, unless there are
   * unsaved changes: those are never thrown away, the person is told instead.
   */
  refreshToken?: number;
  /** The words for the start of a new version, which a person who works on the sheet may begin (FA4). */
  startLabel?: string;
  /**
   * Show the list of versions first and a version only after the person opens it, with a way back to the list inside the
   * workspace. Without it the list and the version in use are drawn together (the first behaviour of the screen).
   */
  listFirst?: boolean;
  /**
   * Where the row of buttons of an open version (back to the versions, Form, Grid, Save) is drawn when the page has a place
   * for it beside its own tabs. `null` means the place is not there yet (nothing is drawn until it is); without the property
   * the row is drawn above the grid.
   */
  toolbarSlot?: HTMLElement | null;
  /**
   * The footer row (FA7, ADR-0201): the text at the right of the last row of the sheet in the grid and in the view, empty
   * for a blank row. The caller says what it reads (a number and a revision); the workspace knows no business word and the
   * row is never part of the stored grid.
   */
  footer?: string | ((versionNo: number) => string);
  /** Extra buttons beside View for the revision that is open (a download, for a caller that offers one). */
  actions?: (open: { versionNo: number }) => ReactNode;
  /**
   * False hides every way to start a new draft by hand: a sheet whose owner takes its new revisions from somewhere else
   * (a change request, FA8) offers none, and the server refuses one anyway. The first draft of a sheet that has none is
   * not affected. Default true.
   */
  allowNewDraft?: boolean;
  /**
   * The version to open first (FA8: the revised copy of a change request). With the list first, naming a version opens it
   * at once (FA10). A number the caller may not read is answered like any unknown one by the server.
   */
  focusVersion?: number;
  /**
   * Called after a version was started, uploaded, saved or its grid replaced (FA10), so a page around the sheet can read what
   * depends on the versions again (whether the template can be sent for approval).
   */
  onVersionsChanged?: () => void;
}

interface Notice {
  text: string;
  error: boolean;
}

/**
 * Everything a person does with the versions of one sheet (FA3): see the history with its tags, open a version
 * (read-only unless it is a draft they may change), start a new draft from another version, build it in the form view or
 * the grid view, and save it with the row version that was read. It draws what the API answers, in the API's words, with
 * the place that is wrong; it decides no permission: the buttons it offers follow what the API said the caller may do, and
 * every request is decided again by the server.
 *
 * It knows no business word: the record is a parameter.
 */
export function SheetWorkspace({
  owner: given,
  label,
  correctInReview = false,
  startLabel,
  refreshToken,
  listFirst = false,
  toolbarSlot,
  footer,
  actions,
  allowNewDraft = true,
  focusVersion,
  onVersionsChanged,
}: SheetWorkspaceProps) {
  const owner = useMemo<SheetOwner>(() => ({ object: given.object, id: given.id }), [given.object, given.id]);
  const { state, reload } = useVersions(owner);
  const [selected, setSelected] = useState<number | null>(focusVersion ?? null);
  const [pending, setPending] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [starting, setStarting] = useState(false);
  const [opened, setOpened] = useState(!listFirst || focusVersion !== undefined);
  const heading = useId();

  const versions = state.status === "ready" ? state.view.versions : [];
  const current = selected ?? defaultVersion(versions)?.versionNo ?? 0;
  const version = useVersion(owner, opened ? current : 0, refresh);

  // Something outside the sheet changed what it may do (FA4): read the versions again, but never over unsaved changes.
  const seenToken = useRef(refreshToken);
  useEffect(() => {
    if (refreshToken === seenToken.current) {
      return;
    }
    seenToken.current = refreshToken;
    // In a microtask, like the other screens that read again, so that no state is set while the effect runs.
    void Promise.resolve().then(() => {
      if (dirty) {
        setNotice({
          text: "Something changed on this template while you were editing. Save or discard your changes, then reload the page to see it.",
          error: false,
        });
        return;
      }
      reload();
      setRefresh((n) => n + 1);
    });
  }, [refreshToken, dirty, reload]);

  const open = (versionNo: number) => {
    if (versionNo === current) {
      setOpened(true);
      return;
    }
    if (dirty) {
      setPending(versionNo);
      return;
    }
    setNotice(null);
    setSelected(versionNo);
    setOpened(true);
  };

  /** Back to the list of versions (the list-first layout); changes that are not saved are asked about first. */
  const backToList = () => {
    if (dirty) {
      setPending(0);
      return;
    }
    setNotice(null);
    setOpened(false);
  };

  const start = async (body: NewVersion) => {
    setStarting(true);
    setNotice(null);
    const outcome = await startVersion(owner, body);
    setStarting(false);
    if (!outcome.ok) {
      setNotice({ text: outcome.failure.message, error: true });
      return;
    }
    afterNewDraft(outcome.data.versionNo);
  };

  /** A new draft exists (started here, or made from an uploaded file): open it and read the versions again. */
  const afterNewDraft = (versionNo: number) => {
    setDirty(false);
    setNotice(null);
    setSelected(versionNo);
    setOpened(true);
    reload();
    onVersionsChanged?.();
  };

  if (state.status === "loading") {
    return (
      <p role="status" className="hint">
        Loading the revisions…
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

  const { canChange, limits, canChangeHeld } = state.view;
  const draft = versions.find((entry) => isDraft(entry.status));

  return (
    <section className="stack sheet-workspace" aria-labelledby={heading} data-testid="sheet-workspace">
      <h2 id={heading} className={listFirst ? "visually-hidden" : undefined}>
        Revisions
      </h2>
      {notice ? (
        <p className={notice.error ? "form-message" : "hint"} role={notice.error ? "alert" : "status"}>
          {notice.text}
        </p>
      ) : null}
      {pending !== null ? (
        <Modal title="Unsaved changes" onClose={() => setPending(null)}>
          <div role="alertdialog" aria-label="Unsaved changes" className="stack">
            <p>
              {pending === 0
                ? "You have changes that are not saved. Go back to the revisions and lose them?"
                : `You have changes that are not saved. Open ${revisionWords(pending).toLowerCase()} and lose them?`}
            </p>
            <div className="split">
              <button
                type="button"
                className="button button-danger"
                onClick={() => {
                  setDirty(false);
                  if (pending === 0) {
                    setOpened(false);
                  } else {
                    setSelected(pending);
                  }
                  setPending(null);
                }}
              >
                {pending === 0 ? "Discard the changes and go back" : "Discard the changes and open it"}
              </button>
              <button type="button" className="button button-secondary" onClick={() => setPending(null)}>
                Keep editing
              </button>
            </div>
          </div>
        </Modal>
      ) : null}

      {versions.length === 0 ? (
        canChange ? (
          <div className="sheet-start-actions">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void start({ dimension: 2 });
            }}
          >
              <button type="submit" className="button" disabled={starting}>
                Start first revision
              </button>
          </form>
          <div>
            <UploadSheet
              owner={owner}
              label="Start from a spreadsheet"
              firstVersion
              maxBytes={state.view.limits.maxImportBytes}
              disabled={starting}
              onSaved={(created) => afterNewDraft(created.versionNo)}
            />
          </div>
          </div>
        ) : (
          <p className="empty-state" role="status">
            No revision is in use yet.
          </p>
        )
      ) : listFirst && opened ? null : (
        <>
          <VersionList
            versions={versions}
            current={listFirst ? 0 : current}
            onOpen={open}
            onStartFrom={allowNewDraft && canChange && !draft ? (no) => void start({ basedOn: no }) : undefined}
          />
          {(canChange || canChangeHeld) && draft ? (
            <p className="hint">
              {draft.held
                ? "The draft is the revised copy of a change request. Only the people asked to decide the request change it; it becomes the next revision when the last step approves."
                : "This is a draft. Only one draft can exist at a time: continue with it, or save it first."}
            </p>
          ) : null}
          {allowNewDraft && canChange && !draft ? (
            <div className="split">
              <button type="button" className="button button-secondary" onClick={() => void start({})} disabled={starting}>
                {startLabel ?? (versions.some((entry) => entry.status === "ACTIVE") ? "Start a new revision from the active one" : "Start a new revision")}
              </button>
              <UploadSheet
                owner={owner}
                label="Upload a spreadsheet"
                maxBytes={state.view.limits.maxImportBytes}
                disabled={starting}
                onSaved={(created) => afterNewDraft(created.versionNo)}
              />
            </div>
          ) : null}
        </>
      )}

      {listFirst && !opened ? null : version.status === "loading" ? (
        <p role="status" className="hint">
          Opening {revisionWords(current).toLowerCase()}…
        </p>
      ) : null}
      {opened && version.status === "failed" ? (
        <p role="alert" className="form-message">
          {version.failure.message}
        </p>
      ) : null}
      {opened && version.status === "ready" ? (
        <VersionEditor
          key={`${version.version.version.versionNo}-${refresh}`}
          owner={owner}
          label={label}
          summary={version.version.version}
          initial={fromApi(version.version.grid)}
          limits={limits}
          canChange={canChange}
          canChangeHeld={canChangeHeld}
          correctInReview={correctInReview}
          onBack={listFirst ? backToList : undefined}
          toolbarSlot={toolbarSlot}
          onDirty={setDirty}
          onSaved={() => {
            reload();
            onVersionsChanged?.();
          }}
          onReload={() => setRefresh((n) => n + 1)}
          footer={footer}
          actions={actions}
        />
      ) : null}
    </section>
  );
}

function VersionEditor({
  owner,
  label,
  summary: first,
  initial,
  limits,
  canChange,
  canChangeHeld,
  correctInReview,
  onBack,
  toolbarSlot,
  onDirty,
  onSaved,
  onReload,
  footer,
  actions,
}: {
  owner: SheetOwner;
  label: string;
  summary: VersionSummary;
  initial: GridDoc;
  limits: SheetLimits;
  canChange: boolean;
  canChangeHeld: boolean;
  correctInReview: boolean;
  onBack?: () => void;
  toolbarSlot?: HTMLElement | null;
  onDirty(dirty: boolean): void;
  onSaved(): void;
  onReload(): void;
  footer?: string | ((versionNo: number) => string);
  actions?: (open: { versionNo: number }) => ReactNode;
}) {
  const editor = useSheetEditor(initial, limits);
  const narrow = useNarrowScreen();
  const [summary, setSummary] = useState(first);
  // The server's answers of the formulas (SH2): asked for when the sheet has any and again after every save.
  const hasFormulas = editor.doc.formatVersion === 2 && editor.canWriteFormulas;
  const serverAnswers = useServerAnswers(owner, summary.versionNo, summary.version, hasFormulas);
  const [tab, setTab] = useState<"form" | "grid">(narrow ? "form" : "grid");
  const [saving, setSaving] = useState(false);
  const [refused, setRefused] = useState<{ doc: GridDoc; problems: Problem[]; message: string; stale: boolean } | null>(null);
  const [saved, setSaved] = useState(false);
  const [viewing, setViewing] = useState(false);
  const footerText = typeof footer === "function" ? footer(first.versionNo) : footer;
  const tabs = useId();

  const draft = isDraft(summary.status);
  // The approver's correction of a version in review (FA4): the one change a version that has left draft allows, and the
  // server audits and counts each one. Only drawn editable when the screen believes the member may; the server decides.
  const inReview = summary.status === "IN_REVIEW";
  const correcting = inReview && canChange && correctInReview;
  // A draft held for a change request is changed by the people asked to decide it, not by whoever may change the record.
  const mayEdit = draft && summary.held ? canChangeHeld : canChange;
  const readOnly = !mayEdit || !(draft || correcting);
  const readOnlyReason = !(draft || correcting)
    ? inReview
      ? `${sentenceName(summary)} is in review. Only the people who approve it can correct it; everyone else reads it.`
      : undefined
    : !mayEdit
      ? summary.held
        ? "This is the revised copy of a change request. Only the people asked to decide the request can change it."
        : "You can read this sheet but not change it."
      : narrow
        ? "On a narrow screen the grid is for reading. Build in the form view, or use a wider screen."
        : undefined;
  const gridReadOnly = readOnly || narrow;

  const hints = useMemo(() => hintsFor(editor.doc, limits), [editor.doc, limits]);
  // What the server refused applies to the document that was sent; after any further edit it is out of date.
  const problems = useMemo(() => {
    const server = refused && refused.doc === editor.doc ? refused.problems : [];
    const seen = new Set<string>();
    return [...server, ...hints].filter((problem) => {
      const key = `${problem.place}|${problem.message}`;
      return seen.has(key) ? false : (seen.add(key), true);
    });
  }, [refused, hints, editor.doc]);

  const { dirty } = editor;
  useEffect(() => onDirty(dirty), [dirty, onDirty]);
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

  const save = async () => {
    setSaving(true);
    setSaved(false);
    const sent = editor.doc;
    const outcome = await saveGrid(owner, summary.versionNo, summary.version, sent);
    setSaving(false);
    if (outcome.ok) {
      setSummary(outcome.data);
      editor.markSaved(sent);
      setRefused(null);
      setSaved(true);
      onSaved();
      return;
    }
    const stale = outcome.failure.code === "CONCURRENT_MODIFICATION" || outcome.failure.code === "CONFLICT";
    setRefused({
      doc: sent,
      problems: problemsFromFields(outcome.failure.fields),
      message: outcome.failure.message,
      stale,
    });
  };

  const serverProblems = refused && refused.doc === editor.doc ? refused.problems : [];

  const bar = (
    <div className="sheet-bar">
      {onBack ? (
        <button type="button" className="link-button" onClick={onBack}>
          ‹ Revisions
        </button>
      ) : null}
      <div role="tablist" aria-label="Views of the sheet" className="sheet-tabs">
        <button
          type="button"
          role="tab"
          id={`${tabs}-form-tab`}
          aria-selected={tab === "form"}
          aria-controls={`${tabs}-form`}
          aria-label="Form view"
          className={tab === "form" ? "button" : "button button-secondary"}
          onClick={() => setTab("form")}
        >
          Form
        </button>
        <button
          type="button"
          role="tab"
          id={`${tabs}-grid-tab`}
          aria-selected={tab === "grid"}
          aria-controls={`${tabs}-grid`}
          aria-label="Grid view"
          className={tab === "grid" ? "button" : "button button-secondary"}
          onClick={() => setTab("grid")}
        >
          Grid
        </button>
      </div>
      <div className="sheet-bar-actions">
        <button type="button" className="button button-secondary" onClick={() => setViewing(true)} data-testid="sheet-view">
          View
        </button>
        {actions ? actions({ versionNo: summary.versionNo }) : null}
        {draft && summary.held && canChangeHeld ? (
          <UploadSheet
            owner={owner}
            label="Replace from a spreadsheet"
            replace
            onSaved={() => {
              onSaved();
              onReload();
            }}
          />
        ) : null}
      </div>
      {readOnly ? null : (
        <div className="sheet-bar-actions">
          <button type="button" className="button" onClick={() => void save()} disabled={saving || !dirty}>
            {saving ? "Saving…" : "Save"}
          </button>
          <span className="visually-hidden" role="status">
            {saved && !dirty ? "All changes are saved." : dirty ? "There are changes that are not saved." : ""}
          </span>
        </div>
      )}
    </div>
  );

  return (
    <div className="stack sheet-editor" data-testid="version-editor">
      {toolbarSlot === undefined ? bar : toolbarSlot ? createPortal(bar, toolbarSlot) : null}
      {readOnly && readOnlyReason ? (
        <p className="hint" role="note">
          {readOnlyReason}
        </p>
      ) : null}
      {correcting ? (
        <p className="hint" role="note" data-testid="review-edit-note">
          This revision is in review. You can correct it because you approve it. Every correction is recorded in the audit
          trail, with its size and a fingerprint, never its content.
        </p>
      ) : null}
      {refused && refused.doc === editor.doc ? (
        <div role="alert" className="form-message" data-testid="save-refused">
          <p>{refused.message}</p>
          {serverProblems.length > 0 ? (
            <ul>
              {serverProblems.map((problem, i) => (
                <li key={`${problem.place}-${i}`}>
                  <strong>{explainPlace(editor.doc, problem.place)}:</strong> {problem.message}
                </li>
              ))}
            </ul>
          ) : null}
          {refused.stale ? (
            <p>
              <button type="button" className="button button-secondary" onClick={onReload}>
                Reload this revision
              </button>{" "}
              <span className="hint">Your unsaved changes are lost when you reload.</span>
            </p>
          ) : null}
        </div>
      ) : null}

      {tab === "form" ? (
        <div role="tabpanel" id={`${tabs}-form`} aria-labelledby={`${tabs}-form-tab`}>
          <FormView
            editor={editor}
            readOnly={readOnly}
            problems={problems}
            canChangeShape={summary.versionNo === 1 && draft}
            footer={footerText}
          />
        </div>
      ) : (
        <div role="tabpanel" id={`${tabs}-grid`} aria-labelledby={`${tabs}-grid-tab`}>
          <GridView
            editor={editor}
            readOnly={gridReadOnly}
            readOnlyReason={!readOnly && narrow ? readOnlyReason : undefined}
            label={`${label}, ${versionTitle(summary).toLowerCase()}`}
            footer={footerText}
            serverAnswers={serverAnswers}
          />
        </div>
      )}
      {viewing ? (
        <SheetPrintView
          doc={editor.doc}
          footer={footerText ?? ""}
          label={`${label}, ${versionTitle(summary).toLowerCase()}`}
          onClose={() => setViewing(false)}
        />
      ) : null}
    </div>
  );
}
