"use client";

import { useParams, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { entryBase, objectListHref, objectNewHref, objectOfEntry, recordHref, whereOf } from "@/lib/records/paths";
import { ObjectListPage } from "./ObjectListPage";
import { RecordFormPage } from "./RecordFormPage";
import { RecordPageView } from "./RecordPageView";

/** A path segment as the router hands it over, decoded; a malformed one is the empty string (which is no object). */
function segment(value: string | string[] | undefined): string {
  const text = Array.isArray(value) ? (value[0] ?? "") : (value ?? "");
  try {
    return decodeURIComponent(text);
  } catch {
    return "";
  }
}

/** The routes of the record screens. Each is a thin reading of the address; the pages below decide nothing from it. */

function ObjectList() {
  const params = useParams<{ objectApiName: string }>();
  const object = segment(params.objectApiName);
  // A related list's "view all" starts the list narrowed to one record (Sprint 20): an API name and an identifier only.
  const initialFilter = whereOf(useSearchParams().get("where"));
  return (
    <ObjectListPage
      key={`${object}|${initialFilter?.field ?? ""}|${initialFilter?.value ?? ""}`}
      objectApiName={object}
      newHref={objectNewHref(object)}
      openHref={(id) => recordHref(object, id)}
      recordHref={recordHref}
      backHref="/"
      initialFilter={initialFilter}
    />
  );
}

/** `/records/<object>`: the list of an object. The address is read in a boundary so the page can still be prepared ahead. */
export function ObjectListRoute() {
  return (
    <Suspense fallback={null}>
      <ObjectList />
    </Suspense>
  );
}

function NewRecord() {
  const params = useParams<{ objectApiName: string }>();
  const object = segment(params.objectApiName);
  const cloneFrom = useSearchParams().get("cloneFrom") ?? undefined;
  return (
    <RecordFormPage
      key={`${object}|${cloneFrom ?? ""}`}
      objectApiName={object}
      listHref={objectListHref(object)}
      recordHref={recordHref}
      cloneFromId={cloneFrom}
      savedHref={(id) => recordHref(object, id)}
    />
  );
}

/** `/records/<object>/new`: the create form (`?cloneFrom=<id>` starts it from a copy of a record). */
export function NewRecordRoute() {
  return (
    <Suspense fallback={null}>
      <NewRecord />
    </Suspense>
  );
}

/** `/records/<object>/<id>`: the record page of one record (Sprint 20). */
export function RecordRoute() {
  const params = useParams<{ objectApiName: string; recordId: string }>();
  const object = segment(params.objectApiName);
  const id = segment(params.recordId);
  return (
    <RecordPageView
      key={`${object}/${id}`}
      objectApiName={object}
      recordId={id}
      listHref={objectListHref(object)}
      recordHref={recordHref}
      newHref={objectNewHref(object)}
    />
  );
}

/** The object an application entry address names, with the address of the entry (for the links of its screens). */
function useEntry() {
  const params = useParams<{ application: string; entry: string; recordId?: string }>();
  const application = segment(params.application);
  const entry = segment(params.entry);
  return { object: objectOfEntry(entry), base: entryBase(application, entry), recordId: segment(params.recordId) };
}

function Unavailable() {
  return (
    <div className="empty-state" role="status" data-testid="entry-unavailable">
      <p>
        <strong>This page is not available.</strong>
      </p>
    </div>
  );
}

function EntryNewRecord() {
  const { object, base } = useEntry();
  const cloneFrom = useSearchParams().get("cloneFrom") ?? undefined;
  if (!object) {
    return <Unavailable />;
  }
  return (
    <RecordFormPage
      key={`${object}|${cloneFrom ?? ""}`}
      objectApiName={object}
      listHref={base}
      recordHref={recordHref}
      cloneFromId={cloneFrom}
      savedHref={(id) => `${base}/records/${encodeURIComponent(id)}`}
    />
  );
}

/** `/apps/<application>/o-<object>/new`: the create form, inside the application. */
export function EntryNewRecordRoute() {
  return (
    <Suspense fallback={null}>
      <EntryNewRecord />
    </Suspense>
  );
}

/** `/apps/<application>/o-<object>/records/<id>`: the record page of one record, inside the application. */
export function EntryRecordRoute() {
  const { object, base, recordId } = useEntry();
  if (!object || recordId === "") {
    return <Unavailable />;
  }
  return (
    <RecordPageView
      key={`${object}/${recordId}`}
      objectApiName={object}
      recordId={recordId}
      listHref={base}
      recordHref={recordHref}
      newHref={`${base}/new`}
    />
  );
}
