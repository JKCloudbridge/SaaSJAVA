import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api/client";
import type { ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { call, type Outcome } from "@/lib/records/call";
import { toApiFill } from "./fillModel";
import type { FillDoc } from "./fillTypes";
import type { GridDoc } from "./types";

/**
 * The calls of the cells of a fill-in (Functional track FF1, ADR-0192). A fill-in belongs to one header record of one
 * object, named by the address; the browser never names the organization. Every call is made the way the record screens
 * make theirs (`call`: the forgery cookie first, one silent refresh on a 401), and what comes back is the API's own data or
 * its own failure.
 */

export type FillView = components["schemas"]["SheetFillView"];
export type FillSummary = components["schemas"]["SheetFillSummaryView"];

/** The record a fill-in belongs to. */
export interface FillOwner {
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

export function getFill(owner: FillOwner): Promise<Outcome<FillView>> {
  return unwrap(
    call(() =>
      api.GET("/api/v1/sheet-fills/{fillObject}/{fillId}", {
        params: { path: { fillObject: owner.object, fillId: owner.id } },
      }),
    ),
  );
}

/** Saves the cells of a fill-in. `version` is the row version that was read; a fill-in changed since is a conflict. */
export function saveFillCells(
  owner: FillOwner,
  version: number,
  grid: GridDoc,
  fill: FillDoc,
): Promise<Outcome<FillSummary>> {
  return unwrap(
    call(() =>
      api.PUT("/api/v1/sheet-fills/{fillObject}/{fillId}/cells", {
        params: { path: { fillObject: owner.object, fillId: owner.id }, query: { version } },
        body: toApiFill(grid, fill) as never,
      }),
    ),
  );
}

/** What a fill-in came to, with a way to ask again. */
export type FillState =
  | { status: "loading" }
  | { status: "ready"; view: FillView }
  | { status: "failed"; failure: ApiFailure };

/** One fill-in with its sheet and cells, read again whenever `refresh` or `reload` changes. */
export function useFill(owner: FillOwner, refresh = 0): { state: FillState; reload: () => void } {
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{ key: string; state: FillState } | null>(null);
  const stable = useMemo<FillOwner>(() => ({ object: owner.object, id: owner.id }), [owner.object, owner.id]);
  const key = `${stable.object}|${stable.id}|${refresh}|${attempt}`;

  useEffect(() => {
    let live = true;
    void getFill(stable).then((outcome) => {
      if (live) {
        setLoaded({
          key,
          state: outcome.ok ? { status: "ready", view: outcome.data } : { status: "failed", failure: outcome.failure },
        });
      }
    });
    return () => {
      live = false;
    };
  }, [key, stable]);

  const reload = useCallback(() => setAttempt((value) => value + 1), []);
  return { state: loaded?.key === key ? loaded.state : { status: "loading" }, reload };
}
