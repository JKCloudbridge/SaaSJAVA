"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo } from "react";
import { useApplication, useApplicationLabel } from "../../application";
import { ChartCard } from "../../home/ChartCard";
import { Filters } from "../Filters";
import { useRefreshOnReturn } from "../refreshOnReturn";
import { FailedTile, StatTile, TileSkeleton, type TileView } from "../../home/StatTile";
import { PlansByPlant, RankList, ScoreBars, ScoreLines, StatusColumns, type LineSeries } from "./charts";
import { useSummary } from "./api";
import {
  filtersFromParams,
  formatCount,
  formatScore,
  managementLinks,
  monthName,
  paramsFromFilters,
  type ManagementFilters,
  type ManagementLinks,
  type ManagementSummary,
  type ManagementTile,
} from "../model";

/**
* Option 2 of the Home page of Management Core (MC3, ADR-0262; ADR-0266 named it Option 2): how each plant did in a month, how it moved over the months, who needs
 * an Action plan and what waits for a decision. Everything on it is what the server counted for this person, through the
 * same record queries the lists use, so a tile agrees with the list it opens; the browser adds up nothing, decides nothing
 * and draws a section only when the server sent it. The filters live in the address (`?month=&span=&plant=&form=`), so a
 * view can be shared and the back button undoes a choice.
 */
export function Option2Home() {
  return (
    <Suspense fallback={<HomeLoading />}>
      <HomeScreen />
    </Suspense>
  );
}

function HomeLoading() {
  return (
    <div className="dash" aria-busy="true">
      <ul className="dash-tiles" aria-hidden="true">
        {Array.from({ length: 6 }, (_, index) => (
          <TileSkeleton key={index} />
        ))}
      </ul>
    </div>
  );
}

function HomeScreen() {
  const application = useApplication();
  const applicationLabel = useApplicationLabel(application);
  const links = useMemo(() => managementLinks(application), [application]);
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const filters = useMemo(() => filtersFromParams(params), [params]);
  const { state, refresh } = useSummary(filters);
  useRefreshOnReturn(refresh);
  const summary = state.summary;
  const refreshing = state.status === "ready" ? state.refreshing : false;

  const go = (next: ManagementFilters) => {
    const all = new URLSearchParams(paramsFromFilters(next));
    // The address keeps the other choice of the page (the layout) while the filters change.
    const view = params?.get("view");
    if (view) {
      all.set("view", view);
    }
    const query = all.toString();
    router.push(query ? `${pathname}?${query}` : pathname, { scroll: false });
  };
  const narrowToPlant = (plant: string) => go({ ...filters, plant });

  return (
    <div className="dash" data-testid="management-home" aria-busy={state.status === "loading" || refreshing}>
      <header className="dash-head">
        <h1 className="visually-hidden">{applicationLabel}</h1>
      </header>

      <Filters
        filters={filters}
        summary={summary}
        onChange={go}
        onRefresh={refresh}
        refreshing={refreshing || state.status === "loading"}
      />

      {state.status === "failed" ? (
        <div className="empty-state" role="alert" data-testid="home-failed">
          <p>{state.message}</p>
          <button type="button" className="link-button" onClick={refresh}>
            Try again
          </button>
        </div>
      ) : null}

      <h2 className="visually-hidden">Numbers</h2>
      {summary ? (
        <ul className="dash-tiles" data-testid="home-tiles">
          {tilesOf(summary, links).map((entry) =>
            entry.failed ? <FailedTile key={entry.label} label={entry.label} /> : <StatTile key={entry.view.id} tile={entry.view} />,
          )}
        </ul>
      ) : state.status === "loading" ? (
        <ul className="dash-tiles" aria-hidden="true" data-testid="home-tiles-loading">
          {Array.from({ length: 6 }, (_, index) => (
            <TileSkeleton key={index} />
          ))}
        </ul>
      ) : null}

      <h2 className="visually-hidden">Graphs</h2>
      <div className="dash-charts" data-refreshing={refreshing ? "true" : undefined}>
        <Charts summary={summary} loading={state.status === "loading"} onPlant={narrowToPlant} />
      </div>
    </div>
  );
}

// ---- the tiles ----

interface TileEntry {
  failed: boolean;
  label: string;
  view: TileView;
}

function tilesOf(summary: ManagementSummary, links: ManagementLinks): TileEntry[] {
  const { tiles, problems } = summary;
  const month = summary.month;
  const plant = summary.applied.plant ?? "";
  const monthText = monthName(month);
  const previousText = monthName(summary.previousMonth);
  const out: TileEntry[] = [];
  const add = (tile: ManagementTile | undefined, failed: boolean, label: string, build: (tile: ManagementTile) => TileView) => {
    if (tile) {
      out.push({ failed: false, label, view: build(tile) });
    } else if (failed) {
      out.push({ failed: true, label, view: { id: label, label, empty: "" } });
    }
  };
  const fillsFailed = problems.includes("fill-ins");
  const waitingFailed = problems.includes("waiting");
  const plansFailed = problems.includes("action-plans");

  add(tiles.plantsSubmitted, fillsFailed, "Plants that sent their fill-in", (tile) => ({
    id: "plants-sent",
    label: "Plants that sent their fill-in",
    value: tile.whole === undefined ? formatCount(tile.value, tile.atLeast) : `${formatCount(tile.value, tile.atLeast)} of ${formatCount(tile.whole)}`,
    empty: "",
    hint: `${monthText}. ${previousText}: ${formatCount(tile.previous)}`,
    href: links.sent(month, plant),
  }));
  add(tiles.averageScore, fillsFailed, "Average score", (tile) => ({
    id: "average-score",
    label: "Average score",
    value: tile.value === undefined ? undefined : formatScore(tile.value),
    empty: "No data",
    hint: tile.value === undefined ? undefined : `${monthText}. ${previousText}: ${formatScore(tile.previous)}`,
    href: links.scored(month, plant),
  }));
  add(tiles.plantsBelowLimit, fillsFailed, "Plants below the limit", (tile) => ({
    id: "below-limit",
    label: "Plants below the limit",
    value: formatCount(tile.value, tile.atLeast),
    empty: "",
    hint:
      tile.extra !== undefined && tile.extra > 0
        ? `No Action plan yet: ${formatCount(tile.extra)}`
        : `${monthText}. ${previousText}: ${formatCount(tile.previous)}`,
    href: links.needs(month, plant),
    attention: (tile.value ?? 0) > 0,
  }));
  add(tiles.waitingForReview, waitingFailed, "Waiting for review", (tile) => ({
    id: "waiting-review",
    label: "Waiting for review",
    value: formatCount(tile.value, tile.atLeast),
    empty: "",
    // The review is one step (2026-11-17): every fill-in sent and not yet submitted by its reviewer, in every month.
    // The server no longer sends a tile for top management.
    href: links.waiting("review", plant),
    attention: true,
  }));
  add(tiles.actionPlansOpen, plansFailed, "Action plans open", (tile) => ({
    id: "plans-open",
    label: "Action plans open",
    value: formatCount(tile.value, tile.atLeast),
    empty: "",
    href: links.plansOpen(plant),
  }));
  return out;
}

// ---- the graphs ----

/**
 * Whether a plant needs an Action plan, in the table of the score chart: the decision made at the review (ADR-0266: the
 * table says Yes or No, not a sentence; a score under a limit changed since is still the decision of the review).
 */
export function needsWords(decided: boolean): string {
  return decided ? "Yes" : "No";
}

function Charts({
  summary,
  loading,
  onPlant,
}: {
  summary: ManagementSummary | undefined;
  loading: boolean;
  onPlant: (plant: string) => void;
}) {
  const problems = summary?.problems ?? [];
  const failed = "This graph could not be worked out. Refresh to try again.";
  const charts = summary?.charts;
  const monthText = summary ? monthName(summary.month) : "";
  const limit = summary?.legend.limit;
  const emptyFor = (section: "fill-ins" | "action-plans", present: boolean, words: string): string | undefined =>
    problems.includes(section) ? failed : present ? undefined : words;

  const scores = charts?.scoresByPlant ?? [];
  const scored = scores.filter((plant) => plant.score !== undefined && plant.score !== null);
  const lines: LineSeries[] = (charts?.plantsOverMonths ?? []).map((one) => ({
    key: one.plant,
    label: one.label,
    scores: one.points.map((point) => point.score ?? null),
  }));
  const averages = charts?.averageByMonth ?? [];
  const months = summary?.months ?? [];
  const plans = charts?.actionPlansByPlant ?? [];
  const status = charts?.fillsByMonth ?? [];

  // A section the person cannot read at all is not drawn, as a tile that is null is not drawn.
  const hasFills = charts?.scoresByPlant !== undefined;
  const hasPlans = charts?.actionPlansByPlant !== undefined;
  if (summary && !hasFills && !hasPlans && problems.length === 0) {
    return (
      <p className="empty-state" role="status">
        No data
      </p>
    );
  }

  return (
    <>
      {hasFills || problems.includes("fill-ins") ? (
        <>
          <ChartCard
            testId="chart-plant-scores"
            title={`Score of each plant, ${monthText}`}
            loading={loading && !charts}
            empty={emptyFor("fill-ins", scored.length > 0, "No data")}
            table={{
              caption: `Score of each plant, ${monthText}`,
              columns: ["Plant", "Score", "Band", "Fill-ins", "Needs an Action plan"],
              rows: scores.map((plant) => [
                plant.label,
                formatScore(plant.score),
                plant.bandWord ?? "—",
                plant.forms,
                needsWords(plant.needsActionPlan),
              ]),
            }}
          >
            <ScoreBars plants={scores} limit={limit} onSelect={onPlant} />
          </ChartCard>

          <ChartCard
            testId="chart-plant-lines"
            title="The plants over the months"
            loading={loading && !charts}
            empty={emptyFor("fill-ins", lines.length > 0, "No data")}
            table={{
              caption: "Score of each plant in each month",
              columns: ["Plant", ...months.map((month) => monthName(month))],
              rows: lines.map((one) => [one.label, ...one.scores.map((score) => formatScore(score))]),
            }}
            wide
          >
            <ScoreLines months={months} series={lines} limit={limit} label="Score of each plant in each month" />
          </ChartCard>

          <ChartCard
            testId="chart-average"
            title="Average of the plants by month"
            loading={loading && !charts}
            empty={emptyFor("fill-ins", averages.some((one) => one.average !== undefined && one.average !== null), "No data")}
            table={{
              caption: "Average of the plants by month",
              columns: ["Month", "Average", "Plants"],
              rows: averages.map((one) => [monthName(one.month), formatScore(one.average), one.plants]),
            }}
          >
            <ScoreLines
              months={months}
              series={[{ key: "average", label: "Average", scores: averages.map((one) => one.average ?? null) }]}
              limit={limit}
              label="Average of the plants by month"
            />
          </ChartCard>

          <ChartCard
            testId="chart-rank"
            title={`The plants ranked, ${monthText}`}
            loading={loading && !charts}
            empty={emptyFor("fill-ins", scored.length > 0, "No data")}
            table={{
              caption: `The plants ranked, ${monthText}`,
              columns: ["Rank", "Plant", "Score", "Band"],
              rows: scored.map((plant) => [plant.rank ?? "", plant.label, formatScore(plant.score), plant.bandWord ?? "—"]),
            }}
          >
            <RankList plants={scored} />
          </ChartCard>

          <ChartCard
            testId="chart-status"
            title="Fill-ins by month and status"
            loading={loading && !charts}
            empty={emptyFor("fill-ins", status.some((one) => one.draft + one.submitted + one.returned + one.approved > 0), "No data")}
            table={{
              caption: "Fill-ins by month and status",
              columns: ["Month", "Being written", "Sent, waiting", "Sent back", "Approved"],
              rows: status.map((one) => [monthName(one.month), one.draft, one.submitted, one.returned, one.approved]),
            }}
          >
            <StatusColumns months={status} />
          </ChartCard>
        </>
      ) : null}

      {hasPlans || problems.includes("action-plans") ? (
        <ChartCard
          testId="chart-plans"
          title="Action plans by plant"
          loading={loading && !charts}
          empty={emptyFor("action-plans", plans.length > 0, "No data")}
          table={{
            caption: "Action plans by plant",
            columns: ["Plant", "Owed, not started", "Being written or returned", "Sent, waiting", "Approved"],
            rows: plans.map((plant) => [plant.label, plant.owed ?? 0, plant.open, plant.sent, plant.approved]),
          }}
        >
          <PlansByPlant plants={plans} />
        </ChartCard>
      ) : null}
    </>
  );
}
