"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import {
  calculateVersion,
  getVersion,
  listVersions,
  type SheetOwner,
  type VersionSummary,
  type VersionsView,
  type VersionView,
} from "./api";

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

/** The server's answers of the formulas of a version, by cell key and by calculated column. */
export interface ServerAnswers {
  cells: Record<string, string>;
  columns: Record<string, string>;
}

/**
 * The answers the server calculates for the formulas of a version (SH2), asked for again whenever the version saved
 * changes (`stamp`). Nothing is asked for a version that has no formulas to show (`wanted` false), and a failure shows
 * nothing: the browser's own answers are only a preview and the page never claims more than it has.
 */
export function useServerAnswers(owner: SheetOwner, versionNo: number, stamp: number, wanted: boolean): ServerAnswers | null {
  const [loaded, setLoaded] = useState<{ key: string; answers: ServerAnswers | null } | null>(null);
  const key = `${owner.object}|${owner.id}|${versionNo}|${stamp}`;

  useEffect(() => {
    if (!wanted || versionNo <= 0) {
      return;
    }
    let live = true;
    void calculateVersion(owner, versionNo).then((outcome) => {
      if (!live) {
        return;
      }
      if (!outcome.ok) {
        setLoaded({ key, answers: null });
        return;
      }
      const cells: Record<string, string> = {};
      for (const one of outcome.data.cells) cells[one.cell] = one.answer.value;
      const columns: Record<string, string> = {};
      for (const one of outcome.data.columns) columns[one.column] = one.rows[0]?.value ?? "";
      setLoaded({ key, answers: { cells, columns } });
    });
    return () => {
      live = false;
    };
  }, [key, owner, versionNo, wanted]);

  return wanted && loaded?.key === key ? loaded.answers : null;
}

/** The version a person should be shown first: the draft if there is one, else the active one, else the newest. */
export function defaultVersion(versions: readonly VersionSummary[]): VersionSummary | undefined {
  return (
    versions.find((v) => v.status === "DRAFT") ?? versions.find((v) => v.status === "ACTIVE") ?? versions[0]
  );
}
