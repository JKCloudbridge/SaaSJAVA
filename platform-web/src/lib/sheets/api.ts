import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { call, type Outcome } from "@/lib/records/call";
import { toApi } from "./adapter";
import type { GridDoc } from "./types";

/**
 * The calls of the sheet endpoints (FA3, ADR-0186). A sheet belongs to one record of one object, named by the address;
 * the browser never names the organization. Every call is made the way the record screens make theirs (`call`: the
 * forgery cookie first, one silent refresh on a 401), and what comes back is the API's own data or its own failure.
 */

export type VersionsView = components["schemas"]["SheetVersionsView"];
export type VersionSummary = components["schemas"]["SheetVersionSummaryView"];
export type VersionView = components["schemas"]["SheetVersionView"];

/** The record a sheet belongs to. */
export interface SheetOwner {
  object: string;
  id: string;
}

/** An answer that is a success but carries no body is not one this screen can use. */
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

export function listVersions(owner: SheetOwner): Promise<Outcome<VersionsView>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/sheets/{ownerObject}/{ownerId}/versions", {
        params: { path: { ownerObject: owner.object, ownerId: owner.id } },
      }),
    ),
  );
}

export function getVersion(owner: SheetOwner, versionNo: number): Promise<Outcome<VersionView>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/sheets/{ownerObject}/{ownerId}/versions/{versionNo}", {
        params: { path: { ownerObject: owner.object, ownerId: owner.id, versionNo } },
      }),
    ),
  );
}

/** What a new draft is made from. The first version of a sheet names its shape; every later one is a copy. */
export interface NewVersion {
  basedOn?: number;
  dimension?: 1 | 2;
  rowsMode?: "FIXED" | "REPEATABLE" | "FIXED_AND_REPEATABLE";
}

export function startVersion(owner: SheetOwner, body: NewVersion): Promise<Outcome<VersionSummary>> {
  return unwrap(
    call(() =>
      api.POST("/api/v1/sheets/{ownerObject}/{ownerId}/versions", {
        params: { path: { ownerObject: owner.object, ownerId: owner.id } },
        body,
      }),
    ),
  );
}

/** Saves the grid of a draft. `version` is the row version that was read; a draft changed since is a conflict. */
export function saveGrid(
  owner: SheetOwner,
  versionNo: number,
  version: number,
  doc: GridDoc,
): Promise<Outcome<VersionSummary>> {
  return unwrap(
    call(() =>
      api.PUT("/api/v1/sheets/{ownerObject}/{ownerId}/versions/{versionNo}/grid", {
        params: { path: { ownerObject: owner.object, ownerId: owner.id, versionNo }, query: { version } },
        body: toApi(doc),
      }),
    ),
  );
}
