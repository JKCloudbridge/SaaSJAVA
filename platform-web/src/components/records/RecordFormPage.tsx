"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import { call, type Outcome } from "@/lib/records/call";
import { formatValue, recordValue } from "@/lib/records/fieldValue";
import { useObjectForm } from "@/lib/records/hooks";
import type { ObjectForm, RecordView } from "@/lib/records/types";
import { RecordForm } from "./RecordForm";

/** What reading the record to edit came to. */
type RecordState = { status: "loading" } | { status: "ready"; record: RecordView } | { status: "failed"; message: string };

function useRecord(objectApiName: string, recordId: string | undefined): RecordState {
  const key = `${objectApiName}|${recordId ?? ""}`;
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<{ data: RecordView }> } | null>(null);

  useEffect(() => {
    if (!recordId) {
      return;
    }
    let live = true;
    void call(() =>
      api.GET("/api/v1/objects/{objectApiName}/records/{id}", { params: { path: { objectApiName, id: recordId } } }),
    ).then((outcome) => {
      if (live) {
        setLoaded({ key, outcome });
      }
    });
    return () => {
      live = false;
    };
  }, [key, objectApiName, recordId]);

  if (!recordId) {
    return { status: "failed", message: "" };
  }
  if (!loaded || loaded.key !== key) {
    return { status: "loading" };
  }
  return loaded.outcome.ok
    ? { status: "ready", record: loaded.outcome.data.data }
    : { status: "failed", message: loaded.outcome.failure.message };
}

/** A record's heading: the value of the field that names it, else the object's label. */
function headingOf(form: ObjectForm, record: RecordView): string {
  const title = form.fields.find((field) => field.apiName === form.titleField);
  const text = title ? formatValue(title, recordValue(record, title.apiName)) : "";
  return text !== "" ? text : form.label;
}

/**
 * The screen of one record: the create form (no record) or the edit form of any object (Sprint 19, ADR-0129). It reads
 * what the member may see and do from the API (`ObjectForm`, and the record when there is one), hands both to
 * {@link RecordForm}, and goes back to the list when the work is done. An object, a record or a page that the API does
 * not give the member is shown as "not available" in the same words, whether it never existed or is out of reach.
 *
 * @param listHref the list of the object, where Cancel, a saved record and a deleted record lead
 * @param recordHref where a linked record is, by object and identifier
 */
export function RecordFormPage({
  objectApiName,
  recordId,
  listHref,
  recordHref,
}: {
  objectApiName: string;
  recordId?: string;
  listHref: string;
  recordHref: (objectApiName: string, id: string) => string;
}) {
  const router = useRouter();
  const [recordType, setRecordType] = useState<string | undefined>();
  const { state } = useObjectForm(objectApiName, recordType, recordId);
  const record = useRecord(objectApiName, recordId);
  const form = state.status === "ready" ? state.form : state.status === "loading" ? state.previous : undefined;
  const editing = recordId !== undefined;

  if (state.status === "failed" || (editing && record.status === "failed")) {
    return (
      <div className="empty-state" role="status" data-testid="record-unavailable">
        <p>
          <strong>This page is not available.</strong>
        </p>
        <p>It may have been removed, or it may not be part of your access.</p>
        <Link href={listHref}>Back to the list</Link>
      </div>
    );
  }
  if (!form || (editing && record.status !== "ready")) {
    return (
      <div className="custom-page-loading" role="status" aria-live="polite" data-testid="record-loading">
        <span className="skeleton-line" />
        <span className="skeleton-line skeleton-short" />
        <span className="visually-hidden">Loading</span>
      </div>
    );
  }
  const loadedRecord = editing && record.status === "ready" ? record.record : undefined;
  if (!editing && !form.actions.create) {
    return (
      <div className="empty-state" role="status" data-testid="record-not-allowed">
        <p>
          <strong>You cannot create {form.pluralLabel.toLowerCase()}.</strong>
        </p>
        <Link href={listHref}>Back to the list</Link>
      </div>
    );
  }

  return (
    <div className="stack">
      <p>
        <Link href={listHref}>← {form.pluralLabel}</Link>
      </p>
      <h1 className="entry-title">{loadedRecord ? headingOf(form, loadedRecord) : `New ${form.label.toLowerCase()}`}</h1>
      <RecordForm
        key={recordId ?? "new"}
        objectApiName={objectApiName}
        form={form}
        record={loadedRecord}
        recordHref={recordHref}
        onRecordTypeChange={setRecordType}
        onSaved={() => router.push(listHref)}
        onCancel={() => router.push(listHref)}
        onDeleted={editing ? () => router.push(listHref) : undefined}
      />
    </div>
  );
}
