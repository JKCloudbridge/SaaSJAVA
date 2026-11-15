import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { call, type Outcome } from "@/lib/records/call";
import { scopeQuery } from "./scope";

/**
 * The calls and the small model of publishing a document to its plants (Functional track MC2A, ADR-0260). The server
 * decides who may do it, which plants can receive a document (those with a group of plant heads) and who may be a
 * reviewer; this file only carries the choices to it and keeps the person's choices while the dialog is open. The
 * people and groups a reviewer can be chosen from are the API's answer to a search, never a list kept here.
 */

export type PlantState = components["schemas"]["PlantPublicationState"];
export type PlantView = components["schemas"]["PlantPublicationView"];
export type PlantOptions = components["schemas"]["AudienceOptionsView"];
export type ReviewerChoices = components["schemas"]["ReviewerChoices"];
export type ReviewerChoice = components["schemas"]["ReviewerChoice"];
export type PublishPlantsRequest = components["schemas"]["PublishPlantsRequest"];

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

/** Whether the document can be published to plants by this person, and what it is published to now. */
export function getPlantState(templateId: string): Promise<Outcome<PlantState>> {
  return unwrap(
    call(() => api.GET("/api/v1/templates/{templateId}/plants", { params: { path: { templateId }, query: scopeQuery() } })),
  );
}

/** The places the document can be published to (each with whether its group of plant heads is set up). */
export function getPlantOptions(templateId: string): Promise<Outcome<PlantOptions>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/templates/{templateId}/plants/options", { params: { path: { templateId }, query: scopeQuery() } }),
    ),
  );
}

/** The people and groups whose name contains the text; the text is never kept. */
export function searchReviewers(templateId: string, text: string): Promise<Outcome<ReviewerChoices>> {
  return unwrap(
    call(() =>
      api.POST("/api/v1/templates/{templateId}/plants/reviewer-choices", {
        params: { path: { templateId }, query: scopeQuery() },
        body: { text },
      }),
    ),
  );
}

/** Sets the plants the document is published to and the reviewers of each. */
export function publishToPlants(templateId: string, body: PublishPlantsRequest): Promise<Outcome<PlantState>> {
  return unwrap(
    call(() =>
      api.PUT("/api/v1/templates/{templateId}/plants", { params: { path: { templateId }, query: scopeQuery() }, body }),
    ),
  );
}

export type PlantStateLoaded =
  | { status: "loading" }
  | { status: "ready"; state: PlantState }
  | { status: "failed"; failure: ApiFailure };

/** The plant state of a document, read again whenever `refresh` changes. */
export function usePlantState(templateId: string, refresh: number): PlantStateLoaded {
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<PlantState> } | null>(null);
  const key = `${templateId}|${refresh}`;

  useEffect(() => {
    let live = true;
    void getPlantState(templateId).then((outcome) => {
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
    ? { status: "ready", state: loaded.outcome.data }
    : { status: "failed", failure: loaded.outcome.failure };
}

// ---- the choices made in the dialog ----

/** One reviewer chosen for a plant. The name is only for the screen; the request carries the kind and the identifier. */
export interface ReviewerEntry {
  kind: "USER" | "GROUP";
  id: string;
  name: string;
}

/** One plant ticked, with the reviewers chosen for it. */
export interface PlantSelection {
  plant: string;
  reviewers: ReviewerEntry[];
}

const GONE = "A person or group that is no longer available";

/** The choices the document has now, as the dialog's starting point. */
export function selectionFrom(plants: readonly PlantView[]): PlantSelection[] {
  return plants.map((plant) => ({
    plant: plant.plant,
    reviewers: plant.reviewers.map((reviewer) => ({
      kind: reviewer.kind === "GROUP" ? "GROUP" : "USER",
      id: reviewer.id,
      name: reviewer.name ?? GONE,
    })),
  }));
}

export function isPlantChosen(selection: readonly PlantSelection[], plant: string): boolean {
  return selection.some((entry) => entry.plant === plant);
}

/** Ticks or unticks a plant; a plant just ticked has no reviewer yet. */
export function togglePlant(selection: readonly PlantSelection[], plant: string): PlantSelection[] {
  return isPlantChosen(selection, plant)
    ? selection.filter((entry) => entry.plant !== plant)
    : [...selection, { plant, reviewers: [] }];
}

/** Adds a reviewer to a plant; the same person or group is never listed twice for one plant. */
export function addReviewer(selection: readonly PlantSelection[], plant: string, reviewer: ReviewerEntry): PlantSelection[] {
  return selection.map((entry) =>
    entry.plant !== plant || entry.reviewers.some((one) => one.kind === reviewer.kind && one.id === reviewer.id)
      ? entry
      : { ...entry, reviewers: [...entry.reviewers, reviewer] },
  );
}

export function removeReviewer(
  selection: readonly PlantSelection[],
  plant: string,
  kind: string,
  id: string,
): PlantSelection[] {
  return selection.map((entry) =>
    entry.plant === plant
      ? { ...entry, reviewers: entry.reviewers.filter((one) => !(one.kind === kind && one.id === id)) }
      : entry,
  );
}

/** The plants that have no reviewer yet: the dialog says so, and the server refuses them in any case. */
export function plantsWithoutReviewer(selection: readonly PlantSelection[]): string[] {
  return selection.filter((entry) => entry.reviewers.length === 0).map((entry) => entry.plant);
}

/** The request the choices make: the plants in the order ticked, each with its reviewers. */
export function toRequest(selection: readonly PlantSelection[]): PublishPlantsRequest {
  return {
    plants: selection.map((entry) => ({
      plant: entry.plant,
      reviewers: entry.reviewers.map((reviewer) => ({ kind: reviewer.kind, id: reviewer.id })),
    })),
  };
}

/** Whether the choices are what the document has already (nothing to save). */
export function sameAsHeld(selection: readonly PlantSelection[], held: readonly PlantSelection[]): boolean {
  const key = (entries: readonly PlantSelection[]) =>
    JSON.stringify(
      [...entries]
        .map((entry) => [entry.plant, entry.reviewers.map((one) => `${one.kind}:${one.id}`).sort()] as const)
        .sort((a, b) => a[0].localeCompare(b[0])),
    );
  return key(selection) === key(held);
}

/**
 * The place a refusal of the server names, `plants[2]` or `plants[2].reviewers[1]`, as the plant and the reviewer it
 * means in the request that was sent (the order of the selection).
 */
export function refusedPlace(place: string, selection: readonly PlantSelection[]): { plant?: string; reviewer?: string } {
  const found = /^plants\[(\d+)\](?:\.reviewers\[(\d+)\])?$/.exec(place);
  if (!found) {
    return {};
  }
  const entry = selection[Number(found[1])];
  if (!entry) {
    return {};
  }
  const reviewer = found[2] === undefined ? undefined : entry.reviewers[Number(found[2])]?.name;
  return { plant: entry.plant, reviewer };
}

export type ActionPlanFormState = components["schemas"]["ActionPlanFormView"];
export type ActionPlanChoice = components["schemas"]["ActionPlanChoice"];

/** The document named as the Action plan form, and the documents it can be chosen from (MC2B, ADR-0261). */
export function getActionPlanForm(templateId: string): Promise<Outcome<ActionPlanFormState>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/templates/{templateId}/plants/action-plan-form", {
        params: { path: { templateId }, query: scopeQuery() },
      }),
    ),
  );
}

/** Names the Action plan form of the document, or takes it back (no document). The server decides who may. */
export function setActionPlanForm(templateId: string, actionTemplateId: string | null): Promise<Outcome<ActionPlanFormState>> {
  return unwrap(
    call(() =>
      api.PUT("/api/v1/templates/{templateId}/plants/action-plan-form", {
        params: { path: { templateId }, query: scopeQuery() },
        body: { actionTemplateId: actionTemplateId ?? undefined },
      }),
    ),
  );
}
