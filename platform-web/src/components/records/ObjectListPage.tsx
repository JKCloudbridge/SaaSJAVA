"use client";

import Link from "next/link";
import { useObjectForm } from "@/lib/records/hooks";
import { RecordList } from "./RecordList";

/**
 * The list screen of an object: loads the member's description of it and draws {@link RecordList}, or says the page is
 * not available. An object that does not exist, one the member may not read and one a platform module owns all give the
 * API's same answer (not found), so all three read the same here (Sprint 19, ADR-0129). Nothing about the object is
 * known to the page before the API tells it.
 *
 * @param newHref where the create form is, or undefined
 * @param openHref where a record of the object opens
 * @param recordHref where a linked record of another object opens
 * @param backHref where "not available" leads
 */
export function ObjectListPage({
  objectApiName,
  newHref,
  openHref,
  recordHref,
  backHref,
}: {
  objectApiName: string;
  newHref?: string;
  openHref: (id: string) => string;
  recordHref: (objectApiName: string, id: string) => string;
  backHref: string;
}) {
  const { state, reload } = useObjectForm(objectApiName);
  if (state.status === "loading") {
    return (
      <div className="custom-page-loading" role="status" aria-live="polite" data-testid="list-page-loading">
        <span className="skeleton-line" />
        <span className="skeleton-line skeleton-short" />
        <span className="visually-hidden">Loading</span>
      </div>
    );
  }
  if (state.status === "failed") {
    return (
      <div className="empty-state" role="status" data-testid="list-unavailable">
        <p>
          <strong>This page is not available.</strong>
        </p>
        <p>{state.notFound ? "It may have been removed, or it may not be part of your access." : state.message}</p>
        {state.notFound ? null : (
          <button type="button" className="button button-secondary" onClick={reload}>
            Try again
          </button>
        )}
        <Link href={backHref}>Back to the start</Link>
      </div>
    );
  }
  return (
    <RecordList
      key={objectApiName}
      objectApiName={objectApiName}
      form={state.form}
      newHref={newHref}
      openHref={openHref}
      recordHref={recordHref}
    />
  );
}
