import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { scopeQuery } from "../../scope";
import type { ManagementFilters } from "../model";
import type { PlantBoard } from "./model";

/**
 * The call of Option 1 of the Home page (ADR-0266). One question for the whole board, for one month, how many months to
 * compare and at most one plant and one form; the answer is drawn as it comes. Nothing here decides what a person may see.
 */

export type BoardState =
  | { status: "loading"; board?: undefined }
  | { status: "ready"; board: PlantBoard; refreshing: boolean }
  | { status: "failed"; message: string; board?: PlantBoard };

/** Asks for the board. The answer is the API's data or its own words for a refusal. */
export async function loadBoard(
  filters: ManagementFilters,
): Promise<{ ok: true; data: PlantBoard } | { ok: false; message: string }> {
  const outcome = await call(() =>
    api.POST("/api/v1/management-home/plant-board", {
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
 * The board for these filters. The first answer shows placeholders; a later one keeps the old numbers on screen, marked as
 * refreshing, until the new ones arrive. An answer to a question that has since changed is dropped.
 */
export function useBoard(filters: ManagementFilters): { state: BoardState; refresh: () => void } {
  const [state, setState] = useState<BoardState>({ status: "loading" });
  const latest = useRef(0);
  const { month, span, plant, form } = filters;

  const run = useCallback(() => {
    const ticket = ++latest.current;
    setState((before) =>
      before.status === "ready"
        ? { ...before, refreshing: true }
        : before.board
          ? { status: "ready", board: before.board, refreshing: true }
          : { status: "loading" },
    );
    void loadBoard({ month, span, plant, form }).then((outcome) => {
      if (ticket !== latest.current) {
        return;
      }
      setState((before) =>
        outcome.ok
          ? { status: "ready", board: outcome.data, refreshing: false }
          : { status: "failed", message: outcome.message, board: before.board },
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
