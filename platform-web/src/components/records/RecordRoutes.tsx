"use client";

import { useParams } from "next/navigation";
import { entryBase, objectListHref, objectNewHref, objectOfEntry, recordHref } from "@/lib/records/paths";
import { ObjectListPage } from "./ObjectListPage";
import { RecordFormPage } from "./RecordFormPage";

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

/** `/records/<object>`: the list of an object. */
export function ObjectListRoute() {
  const params = useParams<{ objectApiName: string }>();
  const object = segment(params.objectApiName);
  return (
    <ObjectListPage
      key={object}
      objectApiName={object}
      newHref={objectNewHref(object)}
      openHref={(id) => recordHref(object, id)}
      recordHref={recordHref}
      backHref="/"
    />
  );
}

/** `/records/<object>/new`: the create form. */
export function NewRecordRoute() {
  const params = useParams<{ objectApiName: string }>();
  const object = segment(params.objectApiName);
  return (
    <RecordFormPage key={object} objectApiName={object} listHref={objectListHref(object)} recordHref={recordHref} />
  );
}

/** `/records/<object>/<id>`: the edit form of one record. */
export function RecordRoute() {
  const params = useParams<{ objectApiName: string; recordId: string }>();
  const object = segment(params.objectApiName);
  const id = segment(params.recordId);
  return (
    <RecordFormPage
      key={`${object}/${id}`}
      objectApiName={object}
      recordId={id}
      listHref={objectListHref(object)}
      recordHref={recordHref}
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

/** `/apps/<application>/o-<object>/new`: the create form, inside the application. */
export function EntryNewRecordRoute() {
  const { object, base } = useEntry();
  if (!object) {
    return <Unavailable />;
  }
  return <RecordFormPage key={object} objectApiName={object} listHref={base} recordHref={recordHref} />;
}

/** `/apps/<application>/o-<object>/records/<id>`: the edit form of one record, inside the application. */
export function EntryRecordRoute() {
  const { object, base, recordId } = useEntry();
  if (!object || recordId === "") {
    return <Unavailable />;
  }
  return (
    <RecordFormPage
      key={`${object}/${recordId}`}
      objectApiName={object}
      recordId={recordId}
      listHref={base}
      recordHref={recordHref}
    />
  );
}
