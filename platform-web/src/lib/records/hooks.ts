"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call, type Outcome } from "./call";
import { linkIdentifiers } from "./fieldValue";
import type { FormField, ObjectForm, RecordPageDescriptor, RecordValues } from "./types";

/** What the form description of an object came to, with a way to ask again. */
export type FormState =
  | { status: "loading"; previous?: ObjectForm }
  | { status: "ready"; form: ObjectForm }
  | { status: "failed"; message: string; notFound: boolean };

/**
 * The member's description of an object (`GET /objects/{object}/form`): its fields, record types, layout and what they
 * may do. Asked again whenever the object, the record type or the record changes, and on demand; the answer is the
 * server's, and the page draws from it without adding a rule.
 *
 * @param recordType the record type to describe the object for, or undefined for the member's default (or the held one)
 * @param recordId the record being edited: its own record type decides when none is given
 * @param layout the layout a record page names for its details (Sprint 20); without it the member's own resolution decides
 * @param enabled false while something this description depends on (the record page, which names the layout) is not
 *        known yet: nothing is asked and the last good description, if any, stays on screen
 */
export function useObjectForm(
  objectApiName: string,
  recordType?: string,
  recordId?: string,
  layout?: string,
  enabled = true,
) {
  const [attempt, setAttempt] = useState(0);
  const key = `${objectApiName}|${recordType ?? ""}|${recordId ?? ""}|${layout ?? ""}|${attempt}`;
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<{ data: ObjectForm }> } | null>(null);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    let live = true;
    void call(() =>
      api.GET("/api/v1/objects/{objectApiName}/form", {
        params: { path: { objectApiName }, query: { recordType, recordId, layout } },
      }),
    ).then((outcome) => {
      if (live) {
        setLoaded({ key, outcome });
      }
    });
    return () => {
      live = false;
    };
  }, [key, objectApiName, recordType, recordId, layout, enabled]);

  const reload = useCallback(() => setAttempt((value) => value + 1), []);
  // While another description is on its way (another record type) the last good one stays, so a form being filled in
  // is not torn down and the person's typing is not lost.
  const last = loaded?.outcome.ok ? loaded.outcome.data.data : undefined;
  let state: FormState = { status: "loading", previous: last?.objectApiName === objectApiName ? last : undefined };
  if (enabled && loaded && loaded.key === key) {
    state = loaded.outcome.ok
      ? { status: "ready", form: loaded.outcome.data.data }
      : {
          status: "failed",
          message: loaded.outcome.failure.message,
          notFound: loaded.outcome.failure.code === "NOT_FOUND",
        };
  }
  return { state, reload };
}

/** What the record page of an object came to, with a way to ask again. */
export type RecordPageState =
  | { status: "loading"; previous?: RecordPageDescriptor }
  | { status: "ready"; page: RecordPageDescriptor }
  | { status: "failed"; message: string; notFound: boolean };

/**
 * The member's own record page of an object (`GET /objects/{object}/record-page`, Sprint 20, ADR-0131): the header,
 * the tabs and the actions, each already filtered to what the caller may reach. Asked again whenever the object, the
 * record type or the record changes, and on demand.
 *
 * @param recordType overrides the record's own type (rarely needed: a loaded record already carries one)
 * @param recordId the record it is open on, or undefined for a create form
 */
export function useRecordPage(objectApiName: string, recordType?: string, recordId?: string) {
  const [attempt, setAttempt] = useState(0);
  const key = `${objectApiName}|${recordType ?? ""}|${recordId ?? ""}|${attempt}`;
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<{ data: RecordPageDescriptor }> } | null>(
    null,
  );

  useEffect(() => {
    let live = true;
    void call(() =>
      api.GET("/api/v1/objects/{objectApiName}/record-page", {
        params: { path: { objectApiName }, query: { recordType, recordId } },
      }),
    ).then((outcome) => {
      if (live) {
        setLoaded({ key, outcome });
      }
    });
    return () => {
      live = false;
    };
  }, [key, objectApiName, recordType, recordId]);

  const reload = useCallback(() => setAttempt((value) => value + 1), []);
  // While another page is on its way (another record type) the last good one stays, so a form being filled in is not
  // torn down and the person's typing is not lost (the same discipline as `useObjectForm`).
  const last = loaded?.outcome.ok ? loaded.outcome.data.data : undefined;
  let state: RecordPageState = { status: "loading", previous: last };
  if (loaded && loaded.key === key) {
    state = loaded.outcome.ok
      ? { status: "ready", page: loaded.outcome.data.data }
      : {
          status: "failed",
          message: loaded.outcome.failure.message,
          notFound: loaded.outcome.failure.code === "NOT_FOUND",
        };
  }
  return { state, reload };
}

/** The names of linked records, by link field and then by identifier. A name that could not be given is simply absent. */
export type LabelMap = Record<string, Record<string, string>>;

/**
 * The names behind the identifiers the records in front of the person hold in link fields (`POST .../lookups/{field}/
 * labels`). Each identifier is asked for once; the answer says nothing about why a name is missing (an unreadable
 * record, a deleted one and a former member look alike), so the screen writes "Linked record" for those and never shows
 * an identifier. The page decides nothing from a name.
 */
export function useLookupLabels(
  objectApiName: string,
  fields: readonly FormField[],
  records: readonly { id: string; ownerId: string; fields: RecordValues }[],
): LabelMap {
  const [labels, setLabels] = useState<LabelMap>({});
  const asked = useRef<Record<string, Set<string>>>({});
  const mounted = useRef(true);
  const wanted = useMemo(() => linkIdentifiers(fields, records), [fields, records]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    // An answer is kept even when a newer page of records was asked for meanwhile: the identifier is marked as asked
    // the moment the call is made, so dropping its answer would leave that link unnamed for good.
    for (const [field, ids] of Object.entries(wanted)) {
      const known = (asked.current[`${objectApiName}|${field}`] ??= new Set());
      const fresh = ids.filter((id) => !known.has(id));
      fresh.forEach((id) => known.add(id));
      for (let start = 0; start < fresh.length; start += 100) {
        const batch = fresh.slice(start, start + 100);
        void call(() =>
          api.POST("/api/v1/objects/{objectApiName}/lookups/{fieldApiName}/labels", {
            params: { path: { objectApiName, fieldApiName: field } },
            body: { ids: batch },
          }),
        ).then((outcome) => {
          if (mounted.current && outcome.ok) {
            setLabels((current) => ({ ...current, [field]: { ...current[field], ...outcome.data.data.labels } }));
          }
        });
      }
    }
  }, [objectApiName, wanted]);

  return labels;
}
