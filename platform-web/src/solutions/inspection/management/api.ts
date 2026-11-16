import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { scopeQuery } from "../scope";
import type { ManagementFilters, ManagementSummary } from "./model";

/**
 * The call of the Home page that compares the plants (MC3, ADR-0262). The page asks the server once for everything on
 * it, for one month, how many months to compare and at most one plant and one form, and draws what comes back. Nothing
 * here decides what a person may see.
 */

export type SummaryState =
  | { status: "loading"; summary?: undefined }
  | { status: "ready"; summary: ManagementSummary; refreshing: boolean }
  | { status: "failed"; message: string; summary?: ManagementSummary };

/** Asks for the page. The answer is the API's data or its own words for a refusal. */
export async function loadSummary(
  filters: ManagementFilters,
): Promise<{ ok: true; data: ManagementSummary } | { ok: false; message: string }> {
  const outcome = await call(() =>
    api.POST("/api/v1/management-home/summary", {
      params: { query: scopeQuery() },
      body: {
        month: filters.month,
        span: filters.span,
        plant: filters.plant === "" ? undefined : filters.plant,
        form: filters.form === "" ? undefined : filters.form,
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
 * The page's data for these filters. The first answer shows placeholders; a later one keeps the old numbers on screen,
 * marked as refreshing, until the new ones arrive, so nothing jumps. An answer to a question that has since changed is
 * dropped.
 */
export function useSummary(filters: ManagementFilters): { state: SummaryState; refresh: () => void } {
  const [state, setState] = useState<SummaryState>({ status: "loading" });
  const latest = useRef(0);
  const { month, span, plant, form } = filters;

  const run = useCallback(() => {
    const ticket = ++latest.current;
    setState((before) =>
      before.status === "ready"
        ? { ...before, refreshing: true }
        : before.summary
          ? { status: "ready", summary: before.summary, refreshing: true }
          : { status: "loading" },
    );
    void loadSummary({ month, span, plant, form }).then((outcome) => {
      if (ticket !== latest.current) {
        return;
      }
      setState((before) =>
        outcome.ok
          ? { status: "ready", summary: outcome.data, refreshing: false }
          : { status: "failed", message: outcome.message, summary: before.summary },
      );
    });
  }, [month, span, plant, form]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- starting the request marks the screen as loading
    run();
    return () => {
      latest.current += 1;
    };
  }, [run]);

  return { state, refresh: run };
}
