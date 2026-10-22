"use client";

import { useEffect, useId, useMemo, useState } from "react";
import { fromApi } from "@/lib/sheets/adapter";
import {
  saveGrid,
  startVersion,
  type NewVersion,
  type SheetOwner,
  type VersionSummary,
} from "@/lib/sheets/api";
import { explainPlace, hintsFor, problemsFromFields } from "@/lib/sheets/hints";
import { defaultVersion, useVersion, useVersions } from "@/lib/sheets/hooks";
import { isDraft, statusTag } from "@/lib/sheets/tags";
import type { GridDoc, Problem, SheetLimits } from "@/lib/sheets/types";
import { FormView } from "./FormView";
import { GridView } from "./GridView";
import { useNarrowScreen } from "./useNarrowScreen";
import { useSheetEditor } from "./useSheetEditor";
import { sizeWords, VersionList } from "./VersionList";

export interface SheetWorkspaceProps {
  /** The record the sheet belongs to. */
  owner: SheetOwner;
  /** What the sheet is called for a screen reader (the name of the record it belongs to). */
  label: string;
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
export function SheetWorkspace({ owner: given, label }: SheetWorkspaceProps) {
  const owner = useMemo<SheetOwner>(() => ({ object: given.object, id: given.id }), [given.object, given.id]);
  const { state, reload } = useVersions(owner);
  const [selected, setSelected] = useState<number | null>(null);
  const [pending, setPending] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [starting, setStarting] = useState(false);
  const [dimension, setDimension] = useState<1 | 2>(2);
  const heading = useId();

  const versions = state.status === "ready" ? state.view.versions : [];
  const current = selected ?? defaultVersion(versions)?.versionNo ?? 0;
  const version = useVersion(owner, current, refresh);

  const open = (versionNo: number) => {
    if (versionNo === current) {
      return;
    }
    if (dirty) {
      setPending(versionNo);
      return;
    }
    setNotice(null);
    setSelected(versionNo);
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
    setDirty(false);
    setSelected(outcome.data.versionNo);
    reload();
  };

  if (state.status === "loading") {
    return (
      <p role="status" className="hint">
        Loading the versions…
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

  const { canChange, limits } = state.view;
  const draft = versions.find((entry) => isDraft(entry.status));

  return (
    <section className="stack sheet-workspace" aria-labelledby={heading} data-testid="sheet-workspace">
      <h2 id={heading}>Versions</h2>
      {notice ? (
        <p className={notice.error ? "form-message" : "hint"} role={notice.error ? "alert" : "status"}>
          {notice.text}
        </p>
      ) : null}
      {pending !== null ? (
        <div role="alertdialog" aria-label="Unsaved changes" className="card sheet-confirm">
          <p>You have changes that are not saved. Open version {pending} and lose them?</p>
          <div className="split">
            <button
              type="button"
              className="button button-danger"
              onClick={() => {
                setDirty(false);
                setSelected(pending);
                setPending(null);
              }}
            >
              Discard the changes and open it
            </button>
            <button type="button" className="button button-secondary" onClick={() => setPending(null)}>
              Keep editing
            </button>
          </div>
        </div>
      ) : null}

      {versions.length === 0 ? (
        canChange ? (
          <form
            className="form stack"
            onSubmit={(event) => {
              event.preventDefault();
              void start({ dimension });
            }}
          >
            <p>There is no version yet. Choose how the rows of this sheet come about, then start the first version.</p>
            <fieldset className="field">
              <legend>Who adds the rows</legend>
              <label className="sheet-check">
                <input type="radio" name="dimension" checked={dimension === 2} onChange={() => setDimension(2)} />
                <span>The sheet names the rows</span>
              </label>
              <label className="sheet-check">
                <input type="radio" name="dimension" checked={dimension === 1} onChange={() => setDimension(1)} />
                <span>The filler adds the rows</span>
              </label>
            </fieldset>
            <div>
              <button type="submit" className="button" disabled={starting}>
                Start the first version
              </button>
            </div>
          </form>
        ) : (
          <p className="empty-state" role="status">
            No version is in use yet.
          </p>
        )
      ) : (
        <>
          <VersionList
            versions={versions}
            current={current}
            onOpen={open}
            onStartFrom={canChange && !draft ? (no) => void start({ basedOn: no }) : undefined}
          />
          {canChange && draft ? (
            <p className="hint">
              Version {draft.versionNo} is a draft. Only one draft can exist at a time: continue with it, or save it first.
            </p>
          ) : null}
        </>
      )}

      {version.status === "loading" ? (
        <p role="status" className="hint">
          Opening version {current}…
        </p>
      ) : null}
      {version.status === "failed" ? (
        <p role="alert" className="form-message">
          {version.failure.message}
        </p>
      ) : null}
      {version.status === "ready" ? (
        <VersionEditor
          key={`${version.version.version.versionNo}-${refresh}`}
          owner={owner}
          label={label}
          summary={version.version.version}
          initial={fromApi(version.version.grid)}
          limits={limits}
          canChange={canChange}
          onDirty={setDirty}
          onSaved={reload}
          onReload={() => setRefresh((n) => n + 1)}
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
  onDirty,
  onSaved,
  onReload,
}: {
  owner: SheetOwner;
  label: string;
  summary: VersionSummary;
  initial: GridDoc;
  limits: SheetLimits;
  canChange: boolean;
  onDirty(dirty: boolean): void;
  onSaved(): void;
  onReload(): void;
}) {
  const editor = useSheetEditor(initial, limits);
  const narrow = useNarrowScreen();
  const [summary, setSummary] = useState(first);
  const [tab, setTab] = useState<"form" | "grid">(narrow ? "form" : "grid");
  const [saving, setSaving] = useState(false);
  const [refused, setRefused] = useState<{ doc: GridDoc; problems: Problem[]; message: string; stale: boolean } | null>(null);
  const [saved, setSaved] = useState(false);
  const tabs = useId();

  const draft = isDraft(summary.status);
  const readOnly = !canChange || !draft;
  const tag = statusTag(summary.status);
  const readOnlyReason = !draft
    ? `Version ${summary.versionNo} is ${tag.text.toLowerCase()} and cannot be changed. Start a new version from it to make changes.`
    : !canChange
      ? "You can read this sheet but not change it."
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

  return (
    <div className="stack sheet-editor" data-testid="version-editor">
      <div className="sheet-bar">
        <p className="sheet-bar-title">
          <strong>Version {summary.versionNo}</strong> · {tag.text} · {sizeWords(summary.gridBytes)}
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
      {readOnly && readOnlyReason ? (
        <p className="hint" role="note">
          {readOnlyReason}
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
                Reload this version
              </button>{" "}
              <span className="hint">Your unsaved changes are lost when you reload.</span>
            </p>
          ) : null}
        </div>
      ) : null}

      <div role="tablist" aria-label="Views of the sheet" className="sheet-tabs">
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
          <FormView
            editor={editor}
            readOnly={readOnly}
            problems={problems}
            canChangeShape={summary.versionNo === 1 && draft}
          />
        </div>
      ) : (
        <div role="tabpanel" id={`${tabs}-grid`} aria-labelledby={`${tabs}-grid-tab`}>
          <GridView editor={editor} readOnly={gridReadOnly} readOnlyReason={!readOnly && narrow ? readOnlyReason : undefined} label={`${label}, version ${summary.versionNo}`} />
        </div>
      )}
    </div>
  );
}
