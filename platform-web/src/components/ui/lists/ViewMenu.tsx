"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Icon } from "@/components/ui/Icon";
import type { ListView } from "@/lib/records/types";

/**
 * The name of the view in use with a menu of the others, at the left of the title row of a list (UI3A, ADR-0194). The
 * first entry is the list with no view; the shared views come next, then the person's own. "Save view" keeps the list as
 * it is now; "Remove view" is offered only for a view the person may remove. A view is a saved question and grants
 * nothing, so the menu draws what the server listed and decides nothing.
 */
export function ViewMenu({
  allLabel,
  views,
  failure,
  activeId,
  activeLabel,
  modified,
  canEdit,
  onSelect,
  onSave,
  onDelete,
}: {
  allLabel: string;
  views: readonly ListView[] | undefined;
  failure?: string;
  activeId: string | null;
  activeLabel?: string;
  /** Whether the list was changed since the view was opened. */
  modified: boolean;
  /** Whether the view in use may be removed by this person (their own, or a person who may share views). */
  canEdit: boolean;
  onSelect: (view: ListView | null) => void;
  onSave: () => void;
  onDelete: () => void;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) {
      return;
    }
    const click = (event: MouseEvent) => {
      if (!box.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", click);
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("mousedown", click);
      document.removeEventListener("keydown", key);
    };
  }, [open]);

  const shared = (views ?? []).filter((view) => view.scope === "SHARED");
  const mine = (views ?? []).filter((view) => view.scope === "PERSONAL");
  const choose = (view: ListView | null) => {
    setOpen(false);
    onSelect(view);
  };
  const entry = (label: string, key: string, current: boolean, view: ListView | null) => (
    <li key={key} role="none">
      <button type="button" role="menuitemradio" aria-checked={current} onClick={() => choose(view)}>
        {label}
      </button>
    </li>
  );
  return (
    <div className="list-menu view-menu" ref={box}>
      <button
        type="button"
        className="view-menu-button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((value) => !value)}
        data-testid="view-menu"
      >
        <span>{activeLabel ?? allLabel}</span>
        {modified ? <span className="badge">changed</span> : null}
        <Icon name="chevron" className="view-menu-icon" />
      </button>
      {open ? (
        <ul id={id} role="menu" className="list-menu-items view-menu-items" aria-label="Views">
          {entry(allLabel, "all", activeId === null, null)}
          {shared.length > 0 ? (
            <li role="presentation" className="menu-group">
              Shared
            </li>
          ) : null}
          {shared.map((view) => entry(view.label, view.id, activeId === view.id, view))}
          {mine.length > 0 ? (
            <li role="presentation" className="menu-group">
              My views
            </li>
          ) : null}
          {mine.map((view) => entry(view.label, view.id, activeId === view.id, view))}
          {failure ? (
            <li role="presentation" className="menu-group">
              {failure}
            </li>
          ) : null}
          <li role="presentation" className="menu-divider" />
          <li role="none">
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false);
                onSave();
              }}
            >
              Save view…
            </button>
          </li>
          {activeId && canEdit ? (
            <li role="none">
              <button
                type="button"
                role="menuitem"
                className="menu-danger"
                onClick={() => {
                  setOpen(false);
                  onDelete();
                }}
              >
                Remove this view…
              </button>
            </li>
          ) : null}
        </ul>
      ) : null}
    </div>
  );
}
