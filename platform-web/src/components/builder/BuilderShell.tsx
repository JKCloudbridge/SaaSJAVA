"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";

export interface Crumb {
  label: string;
  href?: string;
}

/**
 * The frame every builder shares: where the person is (breadcrumb), what they are building, and Save and Cancel. It
 * holds no draft. It only knows whether there is something unsaved: leaving then asks first (a closed tab is warned by
 * the browser), so a long layout is not lost to a stray click.
 */
export function BuilderShell({
  crumbs,
  title,
  backHref,
  dirty,
  busy,
  saveLabel,
  onSave,
  actions,
  children,
  testId,
}: {
  crumbs: Crumb[];
  title: ReactNode;
  backHref: string;
  dirty: boolean;
  busy: boolean;
  saveLabel: string;
  onSave: () => void;
  /** Extra buttons before Cancel and Save (for example Preview). */
  actions?: ReactNode;
  children: ReactNode;
  testId: string;
}) {
  const [leaving, setLeaving] = useState(false);

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

  return (
    <div className="stack builder-frame" data-testid={testId}>
      <nav aria-label="Where you are" className="breadcrumb">
        {crumbs.map((crumb, index) => (
          <span key={crumb.label}>
            {crumb.href ? <Link href={crumb.href}>{crumb.label}</Link> : <span aria-current="page">{crumb.label}</span>}
            {index < crumbs.length - 1 ? <span aria-hidden="true"> / </span> : null}
          </span>
        ))}
      </nav>
      <div className="builder-head">
        <h2>{title}</h2>
        {dirty ? (
          <span className="badge" data-testid="unsaved">
            Unsaved changes
          </span>
        ) : null}
        <div className="builder-actions">
          {actions}
          {leaving ? (
            <span className="inline-confirm" role="group" aria-label="Discard the changes?">
              Discard your changes?
              <Link className="button button-danger" href={backHref}>
                Yes, discard
              </Link>
              <button type="button" className="link-button" onClick={() => setLeaving(false)}>
                Keep editing
              </button>
            </span>
          ) : dirty ? (
            <button type="button" className="button button-secondary" onClick={() => setLeaving(true)}>
              Cancel
            </button>
          ) : (
            <Link className="button button-secondary" href={backHref}>
              Back
            </Link>
          )}
          <button type="button" className="button" disabled={busy} onClick={onSave}>
            {saveLabel}
          </button>
        </div>
      </div>
      {children}
    </div>
  );
}
