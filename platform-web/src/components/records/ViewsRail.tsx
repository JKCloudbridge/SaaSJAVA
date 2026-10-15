"use client";

import { useState, type FormEvent } from "react";
import type { ListView } from "@/lib/records/types";

/**
 * The saved list views of an object, as a rail beside the list (Sprint 19, ADR-0130). "All records" is the list with no
 * filter; the shared views (everyone who may read the object sees them) come next, then the person's own. A view is a
 * stored question, never a permission: opening it runs it as an ordinary query for whoever opens it. The mockup shows a
 * count on each view; the query engine deliberately gives no total (ADR-0105), so none is shown.
 */
export function ViewsRail({
  views,
  failure,
  activeId,
  modified,
  onSelect,
}: {
  views: readonly ListView[] | undefined;
  failure?: string;
  activeId: string | null;
  /** Whether the list was changed since the active view was opened. */
  modified: boolean;
  onSelect: (view: ListView | null) => void;
}) {
  const shared = (views ?? []).filter((view) => view.scope === "SHARED");
  const mine = (views ?? []).filter((view) => view.scope === "PERSONAL");
  const item = (label: string, id: string | null, view: ListView | null) => (
    <li key={id ?? "all"}>
      <button
        type="button"
        className="rail-item"
        aria-current={activeId === id ? "true" : undefined}
        onClick={() => onSelect(view)}
      >
        <span>{label}</span>
        {activeId === id && modified ? <span className="badge">changed</span> : null}
      </button>
    </li>
  );
  return (
    <nav className="rail views-rail" aria-label="List views" data-testid="views-rail">
      <p className="rail-label">Views</p>
      <ul>{item("All records", null, null)}</ul>
      {failure ? (
        <p className="hint" role="status">
          {failure}
        </p>
      ) : null}
      {shared.length > 0 ? (
        <>
          <p className="rail-label">Shared</p>
          <ul>{shared.map((view) => item(view.label, view.id, view))}</ul>
        </>
      ) : null}
      {mine.length > 0 ? (
        <>
          <p className="rail-label">My views</p>
          <ul>{mine.map((view) => item(view.label, view.id, view))}</ul>
        </>
      ) : null}
    </nav>
  );
}

/**
 * Saves the list as it is, as a new view or over the one that is open. A view is personal (only its maker sees it) or
 * shared (everyone who may read the object); the choice of shared is offered only to a member who holds the ability to
 * share list views, and the server decides again.
 */
export function SaveViewPanel({
  existing,
  canShare,
  busy,
  problem,
  onSave,
  onCancel,
}: {
  /** The view that is open and could be overwritten, or undefined to save a new one. */
  existing?: ListView;
  canShare: boolean;
  busy: boolean;
  problem?: string;
  onSave: (label: string, scope: "PERSONAL" | "SHARED", overwrite: boolean) => void;
  onCancel: () => void;
}) {
  const [label, setLabel] = useState(existing?.label ?? "");
  const [scope, setScope] = useState<"PERSONAL" | "SHARED">((existing?.scope as "PERSONAL" | "SHARED") ?? "PERSONAL");
  const [overwrite, setOverwrite] = useState(existing !== undefined);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (label.trim() !== "") {
      onSave(label.trim(), scope, overwrite);
    }
  }

  return (
    <section aria-labelledby="save-view-heading" data-testid="save-view-panel">
      <h2 id="save-view-heading">Save this list as a view</h2>
      <form className="form" onSubmit={submit}>
        {existing ? (
          <fieldset className="field">
            <legend>What to do</legend>
            <label>
              <input type="radio" name="save-mode" checked={overwrite} onChange={() => setOverwrite(true)} />
              Replace the view “{existing.label}”
            </label>
            <label>
              <input type="radio" name="save-mode" checked={!overwrite} onChange={() => setOverwrite(false)} />
              Save as a new view
            </label>
          </fieldset>
        ) : null}
        <div className="field">
          <label htmlFor="view-name">Name</label>
          <input id="view-name" type="text" value={label} maxLength={80} onChange={(event) => setLabel(event.target.value)} />
        </div>
        <fieldset className="field">
          <legend>Who sees it</legend>
          <label>
            <input type="radio" name="view-scope" checked={scope === "PERSONAL"} onChange={() => setScope("PERSONAL")} />
            Only me
          </label>
          {canShare ? (
            <label>
              <input type="radio" name="view-scope" checked={scope === "SHARED"} onChange={() => setScope("SHARED")} />
              Everyone who can see these records
            </label>
          ) : null}
        </fieldset>
        {problem ? (
          <p className="form-message" role="alert">
            {problem}
          </p>
        ) : null}
        <div className="filters-actions">
          <button type="submit" className="button" disabled={busy || label.trim() === ""}>
            {busy ? "Saving…" : "Save view"}
          </button>
          <button type="button" className="button button-secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
        </div>
      </form>
    </section>
  );
}
