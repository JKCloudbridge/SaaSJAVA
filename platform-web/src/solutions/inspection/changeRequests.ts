import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { call, type Outcome } from "@/lib/records/call";
import { marksOf, type AnnotationMarks, type Place } from "@/lib/sheets/annotate";

/**
 * The calls of the change requests on a template (Functional track FA5, ADR-0191) and how a request is written on a screen.
 * Every call is made the way the record screens make theirs (`call`), and what comes back is the API's own data or its own
 * failure, in its own words. Nothing here decides a permission: the flags are the API's, and every request is decided again.
 */

export type ChangeRequestsView = components["schemas"]["ChangeRequestsView"];
export type ChangeRequestView = components["schemas"]["ChangeRequestView"];
export type ChangeCommentView = components["schemas"]["ChangeCommentView"];
export type NewChangeComment = components["schemas"]["NewChangeComment"];
export type TurnedView = components["schemas"]["TurnedView"];

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

export function getChangeRequests(templateId: string): Promise<Outcome<ChangeRequestsView>> {
  return unwrap(
    call(() => api.GET("/api/v1/templates/{templateId}/change-requests", { params: { path: { templateId } } })),
  );
}

export function createChangeRequest(
  templateId: string,
  body: { title: string; versionNo: number; comments: NewChangeComment[] },
): Promise<Outcome<ChangeRequestView>> {
  return unwrap(
    call(() => api.POST("/api/v1/templates/{templateId}/change-requests", { params: { path: { templateId } }, body })),
  );
}

export function addChangeComment(
  templateId: string,
  requestId: string,
  body: NewChangeComment,
): Promise<Outcome<ChangeRequestView>> {
  return unwrap(
    call(() =>
      api.POST("/api/v1/templates/{templateId}/change-requests/{requestId}/comments", {
        params: { path: { templateId, requestId } },
        body,
      }),
    ),
  );
}

export type Answer = "ACCEPT" | "DECLINE" | "REOPEN";

export function answerChangeRequest(
  templateId: string,
  requestId: string,
  action: Answer,
  note?: string,
): Promise<Outcome<ChangeRequestView>> {
  return unwrap(
    call(() =>
      api.POST("/api/v1/templates/{templateId}/change-requests/{requestId}/answer", {
        params: { path: { templateId, requestId } },
        body: { action, note },
      }),
    ),
  );
}

export function turnIntoVersion(templateId: string, requestIds: string[]): Promise<Outcome<TurnedView>> {
  return unwrap(
    call(() =>
      api.POST("/api/v1/templates/{templateId}/change-requests/turn-into-version", {
        params: { path: { templateId } },
        body: { requestIds },
      }),
    ),
  );
}

export type ChangeRequestsState =
  | { status: "loading" }
  | { status: "ready"; view: ChangeRequestsView }
  | { status: "failed"; failure: ApiFailure };

/**
 * The requests of one template the member may see, read again whenever `refresh` changes (a request was made or answered, a
 * version was published). A failure keeps the page usable: the section says so and the rest of the page goes on.
 */
export function useChangeRequests(id: string, refresh: number): { state: ChangeRequestsState; reload: () => void } {
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<ChangeRequestsView> } | null>(null);
  const key = `${id}|${refresh}|${attempt}`;

  useEffect(() => {
    let live = true;
    void getChangeRequests(id).then((outcome) => {
      if (live) {
        setLoaded({ key, outcome });
      }
    });
    return () => {
      live = false;
    };
  }, [id, key]);

  const reload = useCallback(() => setAttempt((value) => value + 1), []);
  if (!loaded || loaded.key !== key) {
    return { state: { status: "loading" }, reload };
  }
  return {
    state: loaded.outcome.ok
      ? { status: "ready", view: loaded.outcome.data }
      : { status: "failed", failure: loaded.outcome.failure },
    reload,
  };
}

// ---- how a request is written ----

export const STATUS_WORDS: Record<string, { text: string; tone: "quiet" | "on" | "ok" | "error" }> = {
  OPEN: { text: "Open", tone: "on" },
  ACCEPTED: { text: "Accepted", tone: "ok" },
  DECLINED: { text: "Declined", tone: "error" },
  DONE: { text: "Done", tone: "quiet" },
};

export function statusWords(status: string): { text: string; tone: "quiet" | "on" | "ok" | "error" } {
  return STATUS_WORDS[status] ?? { text: status, tone: "quiet" };
}

/** Where a comment points, in words, from what the API kept when it was made (the label it had then). */
export function placeWords(comment: ChangeCommentView): string {
  const label = comment.label.trim();
  switch (comment.kind) {
    case "ROW":
      return label ? `Row “${label}”` : "A row";
    case "COLUMN":
      return label ? `Column “${label}”` : "A column";
    case "CELL":
      return label ? `Cell: ${label}` : "A cell";
    default:
      return "The whole template";
  }
}

/** The place of a comment as the grid knows it, or null for a comment on the whole template. */
export function placeOf(comment: ChangeCommentView): Place | null {
  return comment.kind === "TEMPLATE" ? null : { rowId: comment.rowId ?? null, columnId: comment.columnId ?? null };
}

/**
 * What to mark in the grid of the version in use: the places of the comments of requests that are still open or accepted
 * and whose place is still in that version. Declined and finished requests do not clutter the grid.
 */
export function marksFromRequests(view: ChangeRequestsView | undefined): AnnotationMarks {
  const places: Place[] = [];
  for (const request of view?.requests ?? []) {
    if (request.status !== "OPEN" && request.status !== "ACCEPTED") {
      continue;
    }
    for (const comment of request.comments) {
      const place = placeOf(comment);
      if (place && comment.inActiveVersion) {
        places.push(place);
      }
    }
  }
  return marksOf(places);
}

/** The words for the version a request became: "Version 3 (a draft)". */
export function becameWords(request: ChangeRequestView): string | null {
  if (request.resultingVersion === undefined || request.resultingVersion === null) {
    return null;
  }
  const status = request.resultingVersionStatus;
  const what =
    status === "DRAFT"
      ? "a draft"
      : status === "IN_REVIEW"
        ? "in review"
        : status === "APPROVED"
          ? "approved, not published"
          : status === "ACTIVE"
            ? "in use"
            : status === "SUPERSEDED"
              ? "replaced"
              : status === "REJECTED"
                ? "rejected"
                : status === "WITHDRAWN"
                  ? "withdrawn"
                  : status === "RETIRED"
                    ? "retired"
                    : null;
  return what ? `Version ${request.resultingVersion} (${what})` : `Version ${request.resultingVersion}`;
}
