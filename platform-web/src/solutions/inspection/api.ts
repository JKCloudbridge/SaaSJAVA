import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { call, type Outcome } from "@/lib/records/call";

/**
 * The calls of the template lifecycle (Functional track FA4, ADR-0188): the master list, the lifecycle of one template and
 * the acts of publishing, taking back and retiring. Every call is made the way the record screens make theirs (`call`),
 * and what comes back is the API's own data or its own failure, in its own words. Nothing here decides a permission.
 */

export type TemplatePage = components["schemas"]["TemplatePage"];
export type TemplateRow = components["schemas"]["TemplateRow"];
export type TemplateLifecycle = components["schemas"]["TemplateLifecycle"];
export type TemplateLifecycleView = components["schemas"]["TemplateLifecycleView"];
export type AudienceOptions = components["schemas"]["AudienceOptionsView"];
export type AudiencePick = components["schemas"]["AudiencePick"];

/** What the master list may be narrowed by; an empty text means no narrowing. */
export interface ListFilters {
  search: string;
  category: string;
  subCategory: string;
  state: string;
  retired: "" | "true" | "false";
  sort: "name" | "number" | "newest";
}

export const NO_FILTERS: ListFilters = { search: "", category: "", subCategory: "", state: "", retired: "", sort: "name" };

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

function blankToUndefined(value: string): string | undefined {
  return value.trim() === "" ? undefined : value.trim();
}

/** One page of the templates the member may see; `cursor` continues the same question. */
export function listTemplates(filters: ListFilters, cursor?: string): Promise<Outcome<TemplatePage>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/templates", {
        params: {
          query: {
            search: blankToUndefined(filters.search),
            category: blankToUndefined(filters.category),
            subCategory: blankToUndefined(filters.subCategory),
            state: blankToUndefined(filters.state),
            retired: filters.retired === "" ? undefined : filters.retired === "true",
            sort: filters.sort,
            cursor,
          },
        },
      }),
    ),
  );
}

export function getLifecycle(id: string): Promise<Outcome<TemplateLifecycleView>> {
  return unwrap(
    call(() => api.GET("/api/v1/templates/{templateId}/lifecycle", { params: { path: { templateId: id } } })),
  );
}

export function getAudienceOptions(): Promise<Outcome<AudienceOptions>> {
  return unwrap(call(() => api.GET("/api/v1/templates/audience-options")));
}

export function publishTemplate(
  id: string,
  body: { versionNo?: number; audiences: AudiencePick[] },
): Promise<Outcome<TemplateLifecycleView>> {
  return unwrap(
    call(() => api.POST("/api/v1/templates/{templateId}/publish", { params: { path: { templateId: id } }, body })),
  );
}

export function unpublishTemplate(id: string): Promise<Outcome<TemplateLifecycleView>> {
  return unwrap(
    call(() => api.POST("/api/v1/templates/{templateId}/unpublish", { params: { path: { templateId: id } } })),
  );
}

export function retireTemplate(id: string): Promise<Outcome<TemplateLifecycleView>> {
  return unwrap(
    call(() => api.POST("/api/v1/templates/{templateId}/retire", { params: { path: { templateId: id } } })),
  );
}

export type LifecycleState =
  | { status: "loading" }
  | { status: "ready"; view: TemplateLifecycleView }
  | { status: "failed"; failure: ApiFailure };

/**
 * The lifecycle of one template, read again whenever `refresh` changes (after the approval panel or an act changed
 * something). A failure keeps the page usable: the lifecycle section says so and the rest of the page goes on.
 */
export function useLifecycle(id: string, refresh: number): { state: LifecycleState; reload: () => void } {
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<TemplateLifecycleView> } | null>(null);
  const key = `${id}|${refresh}|${attempt}`;

  useEffect(() => {
    let live = true;
    void getLifecycle(id).then((outcome) => {
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
