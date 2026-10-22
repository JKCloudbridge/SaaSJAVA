"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import { getVersion, listVersions, type SheetOwner, type VersionSummary, type VersionsView, type VersionView } from "./api";

/** What the versions of a sheet came to, with a way to ask again. */
export type VersionsState =
  | { status: "loading" }
  | { status: "ready"; view: VersionsView }
  | { status: "failed"; failure: ApiFailure };

/**
 * The versions of a sheet the member may see (`GET .../versions`). Asked again on demand, after a change; the answer is
 * the server's and the page draws from it without adding a rule.
 */
export function useVersions(owner: SheetOwner): { state: VersionsState; reload: () => void } {
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{ key: string; state: VersionsState } | null>(null);
  const key = `${owner.object}|${owner.id}|${attempt}`;

  useEffect(() => {
    let live = true;
    void listVersions(owner).then((outcome) => {
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
  }, [key, owner]);

  const reload = useCallback(() => setAttempt((value) => value + 1), []);
  // While the next answer is on its way the last good one stays, so a screen being used is not torn down.
  const state: VersionsState = useMemo(() => {
    if (loaded?.state.status === "ready" || loaded?.key === key) {
      return loaded.state;
    }
    return { status: "loading" };
  }, [loaded, key]);
  return { state, reload };
}

/** One version with its grid, asked for when its number changes; the number 0 asks for nothing. */
export type VersionState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; version: VersionView }
  | { status: "failed"; failure: ApiFailure };

export function useVersion(owner: SheetOwner, versionNo: number, refresh: number = 0): VersionState {
  const [loaded, setLoaded] = useState<{ key: string; state: VersionState } | null>(null);
  const key = `${owner.object}|${owner.id}|${versionNo}|${refresh}`;

  useEffect(() => {
    if (versionNo <= 0) {
      return;
    }
    let live = true;
    void getVersion(owner, versionNo).then((outcome) => {
      if (live) {
        setLoaded({
          key,
          state: outcome.ok ? { status: "ready", version: outcome.data } : { status: "failed", failure: outcome.failure },
        });
      }
    });
    return () => {
      live = false;
    };
  }, [key, owner, versionNo]);

  if (versionNo <= 0) {
    return { status: "idle" };
  }
  return loaded?.key === key ? loaded.state : { status: "loading" };
}

/** The version a person should be shown first: the draft if there is one, else the active one, else the newest. */
export function defaultVersion(versions: readonly VersionSummary[]): VersionSummary | undefined {
  return (
    versions.find((v) => v.status === "DRAFT") ?? versions.find((v) => v.status === "ACTIVE") ?? versions[0]
  );
}
