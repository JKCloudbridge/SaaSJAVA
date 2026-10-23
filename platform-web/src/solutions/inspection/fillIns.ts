import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { call, type Outcome } from "@/lib/records/call";

/**
 * The calls of the fill-ins of the Audit maintenance application (Functional track FF1, ADR-0192): whether a fill-in can be
 * started on a template, starting one, and the list of fill-ins. Every call is made the way the record screens make theirs
 * (`call`), and what comes back is the API's own data or its own failure, in its own words. Nothing here decides a
 * permission. The cells are the generic sheet fill-in endpoints (`lib/sheets/fillApi`).
 */

export type FillInPage = components["schemas"]["FillInPage"];
export type FillInRow = components["schemas"]["FillInRow"];
export type FillInOptions = components["schemas"]["FillInOptionsView"];
export type FillInStarted = components["schemas"]["FillInStartedView"];

/** The object that holds the fill-ins of the application (the header record; its cells are kept apart). */
export const FILL_OBJECT = "InspectionFill";

/** The address of the fill-ins page of the application, with one fill-in open when an identifier is given. */
export function fillInsHref(open?: string): string {
  return open ? `/apps/inspection/s-fill-ins?open=${open}` : "/apps/inspection/s-fill-ins";
}

/** What the list of fill-ins may be narrowed by; an empty text means no narrowing. */
export interface FillInFilters {
  templateId: string;
  status: string;
}

export const NO_FILL_FILTERS: FillInFilters = { templateId: "", status: "" };

const UNREADABLE: ApiFailure = {
  code: "UNREADABLE_RESPONSE",
  message: "The server answered in a way this application does not understand.",
};

function unwrap<T>(answer: Promise<Outcome<{ data: T } | undefined>>): Promise<Outcome<T>> {
  return answer.then((outcome): Outcome<T> => {
    if (!outcome.ok) {
      return outcome;
    }
    return outcome.data?.data === undefined ? { ok: false, failure: UNREADABLE } : { ok: true, data: outcome.data.data };
  });
}

/** One page of the fill-ins the member may see, newest first; `cursor` continues the same question. */
export function listFillIns(filters: FillInFilters, cursor?: string): Promise<Outcome<FillInPage>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/fill-ins", {
        params: {
          query: {
            templateId: filters.templateId === "" ? undefined : filters.templateId,
            status: filters.status === "" ? undefined : filters.status,
            cursor,
          },
        },
      }),
    ),
  );
}

export function getFillInOptions(templateId: string): Promise<Outcome<FillInOptions>> {
  return unwrap(
    call(() => api.GET("/api/v1/templates/{templateId}/fill-in-options", { params: { path: { templateId } } })),
  );
}

export function startFillIn(templateId: string): Promise<Outcome<FillInStarted>> {
  return unwrap(
    call(() => api.POST("/api/v1/templates/{templateId}/fill-ins", { params: { path: { templateId } } })),
  );
}

export type OptionsState =
  | { status: "loading" }
  | { status: "ready"; options: FillInOptions }
  | { status: "failed"; failure: ApiFailure };

/** Whether a fill-in can be started on this template, read again whenever `refresh` changes. */
export function useFillInOptions(templateId: string, refresh: number): OptionsState {
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<FillInOptions> } | null>(null);
  const key = `${templateId}|${refresh}`;

  useEffect(() => {
    let live = true;
    void getFillInOptions(templateId).then((outcome) => {
      if (live) {
        setLoaded({ key, outcome });
      }
    });
    return () => {
      live = false;
    };
  }, [templateId, key]);

  if (!loaded || loaded.key !== key) {
    return { status: "loading" };
  }
  return loaded.outcome.ok
    ? { status: "ready", options: loaded.outcome.data }
    : { status: "failed", failure: loaded.outcome.failure };
}
