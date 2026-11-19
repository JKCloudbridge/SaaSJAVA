"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useId, useMemo } from "react";
import { Icon } from "@/components/ui/Icon";
import { useApplication, useApplicationLabel } from "../application";
import { ChartCard } from "../home/ChartCard";
import { clockTime } from "../home/model";
import { FailedTile, StatTile, TileSkeleton, type TileView } from "../home/StatTile";
import { PlansByPlant, RankList, ScoreBars, ScoreLines, StatusColumns, type LineSeries } from "./charts";
import { useSummary } from "./api";
import {
  filtersFromParams,
  formatCount,
  formatScore,
  isFiltered,
  legendWords,
  managementLinks,
  monthName,
  NO_FILTERS,
  paramsFromFilters,
  SPANS,
  type ManagementFilters,
  type ManagementLinks,
  type ManagementSummary,
  type ManagementTile,
} from "./model";

/**
 * The Home page of Management Core (MC3, ADR-0262): how each plant did in a month, how it moved over the months, who needs
 * an Action plan and what waits for a decision. Everything on it is what the server counted for this person, through the
 * same record queries the lists use, so a tile agrees with the list it opens; the browser adds up nothing, decides nothing
 * and draws a section only when the server sent it. The filters live in the address (`?month=&span=&plant=&form=`), so a
 * view can be shared and the back button undoes a choice.
 */
export function ManagementHomePage() {
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
  const summary = state.summary;
  const refreshing = state.status === "ready" ? state.refreshing : false;

  const go = (next: ManagementFilters) => {
    const query = paramsFromFilters(next);
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

      {summary ? <LegendLine summary={summary} form={filters.form} /> : null}

      <h2 className="visually-hidden">Numbers</h2>
      {summary ? (
        <ul className="dash-tiles" data-testid="home-tiles">
          {tilesOf(summary, links, filters).map((entry) =>
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

// ---- the filters ----

function Filters({
  filters,
  summary,
  onChange,
  onRefresh,
  refreshing,
}: {
  filters: ManagementFilters;
  summary: ManagementSummary | undefined;
  onChange: (next: ManagementFilters) => void;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const monthId = useId();
  const spanId = useId();
  const plantId = useId();
  const formId = useId();
  const choices = summary?.monthChoices ?? [];
  // The month the server worked out stands for "this month" and "last month", so the list says the name of each.
  const thisMonth = choices[0];
  const lastMonth = choices[1];
  return (
    <div className="dash-filters" role="search" aria-label="Filter the home page" data-testid="home-filters">
      <label className="dash-select" htmlFor={monthId}>
        <span className="dash-select-label">Month</span>
        <select id={monthId} value={filters.month} onChange={(event) => onChange({ ...filters, month: event.target.value })}>
          <option value="this">This month{thisMonth ? ` (${monthName(thisMonth)})` : ""}</option>
          <option value="last">Last month{lastMonth ? ` (${monthName(lastMonth)})` : ""}</option>
          {choices.slice(2).map((month) => (
            <option key={month} value={month}>
              {monthName(month)}
            </option>
          ))}
        </select>
      </label>

      <label className="dash-select" htmlFor={spanId}>
        <span className="dash-select-label">Compare over</span>
        <select
          id={spanId}
          value={String(filters.span)}
          onChange={(event) => onChange({ ...filters, span: Number(event.target.value) as ManagementFilters["span"] })}
        >
          {SPANS.map((span) => (
            <option key={span} value={span}>
              {span} months
            </option>
          ))}
        </select>
      </label>

      <label className="dash-select" htmlFor={plantId}>
        <span className="dash-select-label">Plant</span>
        <select id={plantId} value={filters.plant} onChange={(event) => onChange({ ...filters, plant: event.target.value })}>
          <option value="">All plants</option>
          {(summary?.filters.plants ?? []).map((plant) => (
            <option key={plant.code} value={plant.code}>
              {plant.label}
            </option>
          ))}
        </select>
      </label>

      <label className="dash-select" htmlFor={formId}>
        <span className="dash-select-label">Form</span>
        <select id={formId} value={filters.form} onChange={(event) => onChange({ ...filters, form: event.target.value })}>
          <option value="">All forms</option>
          {(summary?.filters.forms ?? []).map((form) => (
            <option key={form.id} value={form.id}>
              {form.name}
            </option>
          ))}
        </select>
      </label>

      {isFiltered(filters) ? (
        <button type="button" className="link-button dash-clear" onClick={() => onChange(NO_FILTERS)}>
          Clear
        </button>
      ) : null}

      <span className="dash-spacer" />
      <span className="dash-updated" role="status" aria-live="polite">
        {refreshing ? "Updating…" : summary ? `Updated at ${clockTime(summary.generatedAt)}` : ""}
      </span>
      <button type="button" className="dash-refresh" onClick={onRefresh} disabled={refreshing}>
        <Icon name="refresh" className="dash-refresh-icon" />
        Refresh
      </button>
    </div>
  );
}

/** The legend in the server's numbers, and the notes that say what a filter leaves alone. */
function LegendLine({ summary, form }: { summary: ManagementSummary; form: string }) {
  const words = legendWords(summary.legend);
  return (
    <div className="dash-notes">
      {words ? (
        <p className="hint" data-testid="legend-words">
          {words}
        </p>
      ) : null}
      <p className="hint">
        Months are months in UTC. A score counts when its fill-in is approved; a month a plant did not report is a gap, not a zero.
        {form ? " The waiting and Action plan numbers are not narrowed by form." : ""}
      </p>
    </div>
  );
}

// ---- the tiles ----

interface TileEntry {
  failed: boolean;
  label: string;
  view: TileView;
}

function tilesOf(summary: ManagementSummary, links: ManagementLinks, filters: ManagementFilters): TileEntry[] {
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
  const formNote = filters.form ? "Not narrowed by form" : undefined;

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
    empty: `No approved fill-in for ${monthText} yet`,
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
        ? `${formatCount(tile.extra)} fill-in${tile.extra === 1 ? " has" : "s have"} no Action plan yet`
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
    hint: "Sent, waiting for the reviewer's Submit, in every month",
    href: links.waiting("review", plant),
    note: formNote,
    attention: true,
  }));
  add(tiles.actionPlansOpen, plansFailed, "Action plans open", (tile) => ({
    id: "plans-open",
    label: "Action plans open",
    value: formatCount(tile.value, tile.atLeast),
    empty: "",
    hint: "Not approved yet, in every month",
    href: links.plansOpen(plant),
    note: formNote,
  }));
  return out;
}

// ---- the graphs ----

/**
 * Whether a plant needs an Action plan, in the table of the score chart: the decision made at the review, and a note where the limit in force now would say
 * otherwise (the limit was changed after the review), so a bar under the line is never left unexplained.
 */
export function needsWords(decided: boolean, belowNow: boolean): string {
  if (decided) {
    return belowNow ? "Yes" : "Yes (decided under an earlier limit)";
  }
  return belowNow ? "No (below the limit now; decided under an earlier limit)" : "No";
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
        There is nothing to show here yet.
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
            note={limit !== undefined ? `The line is the limit, ${limit}%.` : undefined}
            loading={loading && !charts}
            empty={emptyFor("fill-ins", scored.length > 0, `No plant has an approved fill-in for ${monthText} yet.`)}
            table={{
              caption: `Score of each plant, ${monthText}`,
              columns: ["Plant", "Score", "Band", "Fill-ins", "Needs an Action plan"],
              rows: scores.map((plant) => [
                plant.label,
                formatScore(plant.score),
                plant.bandWord ?? "—",
                plant.forms,
                needsWords(plant.needsActionPlan, plant.belowCurrentLimit),
              ]),
            }}
          >
            <ScoreBars plants={scores} limit={limit} onSelect={onPlant} />
          </ChartCard>

          <ChartCard
            testId="chart-plant-lines"
            title="The plants over the months"
            note="A month a plant did not report is a gap in its line."
            loading={loading && !charts}
            empty={emptyFor("fill-ins", lines.length > 0, "No plant has an approved fill-in in these months.")}
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
            empty={emptyFor("fill-ins", averages.some((one) => one.average !== undefined && one.average !== null), "No plant has an approved fill-in in these months.")}
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
            empty={emptyFor("fill-ins", scored.length > 0, `No plant has an approved fill-in for ${monthText} yet.`)}
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
            empty={emptyFor("fill-ins", status.some((one) => one.draft + one.submitted + one.returned + one.approved > 0), "No fill-in has been started in these months.")}
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
          note="Owed: fill-ins of the months shown that scored below the limit and have no Action plan yet. The others: every month, as they stand now."
          loading={loading && !charts}
          empty={emptyFor("action-plans", plans.length > 0, "No Action plan is owed or has been started yet.")}
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
