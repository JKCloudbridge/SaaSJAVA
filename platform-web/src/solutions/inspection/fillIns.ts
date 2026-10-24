import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import type { ListRequest } from "@/components/ui/lists/useListViews";
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
export type FillInSubmitted = components["schemas"]["FillInSubmittedView"];
export type ReportColumns = components["schemas"]["ReportColumnsView"];
export type ReportColumn = components["schemas"]["ReportColumnView"];
export type ReportRequest = components["schemas"]["FillReportRequest"];

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

/** One page of the fill-ins for the question of a list view, optionally narrowed to one template (UI3A). */
export function queryFillIns(request: ListRequest, templateId: string, cursor?: string): Promise<Outcome<FillInPage>> {
  return unwrap(
    call(() =>
      api.POST("/api/v1/fill-ins/query", {
        body: {
          search: request.search === "" ? undefined : request.search,
          filter: request.filter,
          sort: request.sort,
          templateId: templateId === "" ? undefined : templateId,
          cursor,
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

/**
 * Sends the member's own fill-in (FA6A, ADR-0196). `SUBMITTED` means it was sent for good (no approval process applies);
 * `REVIEW_NEEDED` means a process applies, nothing was changed and the ordinary approval submit is the way.
 */
export function submitFillIn(fillId: string): Promise<Outcome<FillInSubmitted>> {
  return unwrap(call(() => api.POST("/api/v1/fill-ins/{fillId}/submit", { params: { path: { fillId } } })));
}

/** The columns the author of a version flagged reportable (the version in use when none is named). */
export function getReportColumns(templateId: string, versionNo?: number): Promise<Outcome<ReportColumns>> {
  return unwrap(
    call(() => api.GET("/api/v1/fill-ins/report/columns", { params: { query: { templateId, versionNo } } })),
  );
}

/**
 * One page of the fill-ins the member may see that hold a value in a reportable column (FF2, ADR-0193). The value goes in
 * the body, never in the address: it is text of the organization.
 */
export function runReport(request: ReportRequest): Promise<Outcome<FillInPage>> {
  return unwrap(call(() => api.POST("/api/v1/fill-ins/report", { body: request })));
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
    if (templateId === "") {
      return;
    }
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
