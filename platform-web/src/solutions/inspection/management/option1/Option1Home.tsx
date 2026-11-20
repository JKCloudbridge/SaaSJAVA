"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo } from "react";
import { useApplication, useApplicationLabel } from "../../application";
import { Filters } from "../Filters";
import { filtersFromParams, paramsFromFilters, type ManagementFilters } from "../model";
import { useRefreshOnReturn } from "../refreshOnReturn";
import { ActionPlanTracker } from "./ActionPlanTracker";
import { useBoard } from "./api";
import { BOARD_DEFAULT_SPAN, type PlantBoard } from "./model";
import { PendingReview } from "./PendingReview";
import { PlantKpis } from "./PlantKpis";
import { PlantTiles } from "./PlantTiles";
import { ScoreByMonth } from "./ScoreByMonth";

/**
 * Option 1 of the Home page of Management Core, the plant board (ADR-0266): a tile for each plant, the score by month and
 * the Action plan tracker. Everything on it is what the server counted for this person through the same record queries the
 * lists use, with every status decided there; the browser adds up nothing, decides nothing and draws a section only when
 * the server sent it. The filters live in the address, as in the other option, and the page asks again when it opens and
 * when the person comes back to it.
 */
export function Option1Home() {
  return (
    <Suspense fallback={<div className="pb" aria-busy="true" />}>
      <BoardScreen />
    </Suspense>
  );
}

function BoardScreen() {
  const application = useApplication();
  const applicationLabel = useApplicationLabel(application);
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const filters = useMemo(() => filtersFromParams(params, BOARD_DEFAULT_SPAN), [params]);
  const { state, refresh } = useBoard(filters);
  useRefreshOnReturn(refresh);
  const board = state.board;
  const refreshing = state.status === "ready" ? state.refreshing : false;

  const go = (next: ManagementFilters) => {
    const all = new URLSearchParams(paramsFromFilters(next, BOARD_DEFAULT_SPAN));
    const text = all.toString();
    router.push(text ? `${pathname}?${text}` : pathname, { scroll: false });
  };

  return (
    <div className="pb" data-testid="management-board" aria-busy={state.status === "loading" || refreshing}>
      <h1 className="visually-hidden">{applicationLabel}</h1>
      <Filters
        filters={filters}
        summary={board}
        onChange={go}
        onRefresh={refresh}
        refreshing={refreshing || state.status === "loading"}
        defaultSpan={BOARD_DEFAULT_SPAN}
      />
      {state.status === "failed" ? (
        <div className="empty-state" role="alert" data-testid="home-failed">
          <p>{state.message}</p>
          <button type="button" className="link-button" onClick={refresh}>
            Try again
          </button>
        </div>
      ) : null}
      {board ? <Board board={board} application={application} /> : null}
      {state.status === "loading" ? <span className="skeleton-line chart-loading-block" aria-hidden="true" /> : null}
    </div>
  );
}

function Board({ board, application }: { board: PlantBoard; application: string }) {
  const plants = board.plants;
  if (plants === undefined || plants === null) {
    // The fill-ins cannot be read by this person, or a section failed: nothing is drawn in its place, not a zero.
    return board.problems.length > 0 ? (
      <p className="pb-empty" role="alert" data-testid="board-failed">
        This page could not be worked out. Refresh to try again.
      </p>
    ) : (
      <p className="pb-empty" role="status">
        No data
      </p>
    );
  }
  return (
    <>
      {board.kpis ? <PlantKpis kpis={board.kpis} /> : null}
      {plants.length === 0 ? (
        <p className="pb-empty" role="status">
          No data
        </p>
      ) : (
        <PlantTiles plants={plants} />
      )}
      <ScoreByMonth months={board.months} plants={plants} loading={false} />
      <ActionPlanTracker board={board} application={application} />
      <PendingReview board={board} application={application} />
    </>
  );
}
