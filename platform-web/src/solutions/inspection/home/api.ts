import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import type { HomeFilters, HomeSummary } from "./model";

/**
 * The calls of the home page of the Quality Management System (FA9, ADR-0203). The page asks the server once for
 * everything on it, for one period and at most one location and one department, and draws what comes back. Nothing here
 * decides what a person may see.
 */

export type SummaryState =
  | { status: "loading"; summary?: undefined }
  | { status: "ready"; summary: HomeSummary; refreshing: boolean }
  | { status: "failed"; message: string; summary?: HomeSummary };

/** Asks for the page. The answer is the API's data or its own words for a refusal. */
export async function loadSummary(filters: HomeFilters): Promise<{ ok: true; data: HomeSummary } | { ok: false; message: string }> {
  const outcome = await call(() =>
    api.POST("/api/v1/quality-home/summary", {
      body: {
        period: filters.period,
        location: filters.location === "" ? undefined : filters.location,
        department: filters.department === "" ? undefined : filters.department,
      },
    }),
  );
  if (!outcome.ok) {
    return { ok: false, message: outcome.failure.message };
  }
  const data = outcome.data?.data;
  return data ? { ok: true, data } : { ok: false, message: "The server answered in a way this page does not understand." };
}

/**
 * The page's data for these filters. The first answer shows placeholders; a later one (another filter, or Refresh)
 * keeps the old numbers on screen, marked as refreshing, until the new ones arrive, so nothing jumps. An answer to a
 * question that has since changed is dropped.
 */
export function useSummary(filters: HomeFilters): { state: SummaryState; refresh: () => void } {
  const [state, setState] = useState<SummaryState>({ status: "loading" });
  const latest = useRef(0);
  const { period, location, department } = filters;

  const run = useCallback(() => {
    const ticket = ++latest.current;
    setState((before) =>
      before.status === "ready" ? { ...before, refreshing: true } : before.summary
        ? { status: "ready", summary: before.summary, refreshing: true }
        : { status: "loading" },
    );
    void loadSummary({ period, location, department }).then((outcome) => {
      if (ticket !== latest.current) {
        return;
      }
      setState((before) =>
        outcome.ok
          ? { status: "ready", summary: outcome.data, refreshing: false }
          : { status: "failed", message: outcome.message, summary: before.summary },
      );
    });
  }, [period, location, department]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- starting the request marks the screen as loading
    run();
    return () => {
      latest.current += 1;
    };
  }, [run]);

  return { state, refresh: run };
}

/** How many approval tasks wait for the person, from the endpoint the header link uses. Null when it cannot be had. */
export async function loadMyTasks(): Promise<number | null> {
  try {
    const { data } = await api.GET("/api/v1/approvals/inbox/summary");
    return data && data.data.available ? data.data.openTasks : null;
  } catch {
    return null;
  }
}
