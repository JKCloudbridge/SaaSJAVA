"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { loadMyTasks, useSummary } from "./api";
import { ActivityList } from "./ActivityList";
import { ChartCard } from "./ChartCard";
import { AreaChart, Columns, HBars, RingChart, StackedBars, type Bar, type Slice } from "./charts";
import { FilterBar } from "./FilterBar";
import {
  activityWords,
  describeChange,
  filtersFromParams,
  formatCount,
  formatDays,
  formatPercent,
  HOME_LINKS,
  momentLabel,
  monthLabel,
  paramsFromFilters,
  periodWords,
  stateForList,
  toneOf,
  dayLabel,
  type HomeFilters,
  type HomeSummary,
  type HomeTile,
} from "./model";
import { FailedTile, StatTile, TileSkeleton, type TileView } from "./StatTile";

/**
 * The home page of the Quality Management System (FA9, ADR-0203): numbers on top, graphs below, one bar of filters over
 * both. It is the dashboard of one application, not a general dashboard feature. Everything on it is what the server
 * counted for this person (the same record queries the lists use), so a number agrees with the list it opens; the
 * browser adds up nothing, decides nothing and draws a section only when the server sent it.
 *
 * <p>The filters live in the address (`?period=&location=&department=`): a view can be shared, and the back button
 * undoes a choice. A click on a department bar narrows the page; a click on a tile, a ring segment or a template bar opens
 * the list it counts.
 */
export function QualityHomePage() {
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
        {Array.from({ length: 9 }, (_, index) => (
          <TileSkeleton key={index} />
        ))}
      </ul>
    </div>
  );
}

function HomeScreen() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const filters = useMemo(() => filtersFromParams(params), [params]);
  const { state, refresh } = useSummary(filters);
  const [tasks, setTasks] = useState<number | null | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void loadMyTasks().then((count) => {
      if (live) {
        setTasks(count);
      }
    });
    return () => {
      live = false;
    };
  }, []);

  const go = (next: HomeFilters) => {
    const query = paramsFromFilters(next);
    router.push(query ? `${pathname}?${query}` : pathname, { scroll: false });
  };

  const summary = state.summary;
  const refreshing = state.status === "ready" ? state.refreshing : false;
  return (
    <div className="dash" data-testid="quality-home" aria-busy={state.status === "loading" || refreshing}>
      <header className="dash-head">
        <p className="dash-eyebrow">Quality Management System</p>
        <h1 className="dash-title">Overview</h1>
        <p className="dash-lead">Where documents and records stand. Every number is what you may see.</p>
      </header>

      <FilterBar
        filters={filters}
        locations={summary?.filters.locations ?? []}
        onChange={go}
        onRefresh={refresh}
        refreshing={refreshing || state.status === "loading"}
        updatedAt={summary?.generatedAt}
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
          {tilesOf(summary, tasks, filters).map((entry) =>
            entry.failed ? <FailedTile key={entry.label} label={entry.label} /> : <StatTile key={entry.view.id} tile={entry.view} />,
          )}
        </ul>
      ) : state.status === "loading" ? (
        <ul className="dash-tiles" aria-hidden="true" data-testid="home-tiles-loading">
          {Array.from({ length: 9 }, (_, index) => (
            <TileSkeleton key={index} />
          ))}
        </ul>
      ) : null}

      <h2 className="visually-hidden">Graphs</h2>
      <div className="dash-charts" data-refreshing={refreshing ? "true" : undefined}>
        <Charts
          summary={summary}
          loading={state.status === "loading"}
          filters={filters}
          onNarrow={go}
          onOpen={(href) => router.push(href)}
        />
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

/** The tiles in the order they are drawn: what waits for someone first, then the numbers of the period. */
function tilesOf(summary: HomeSummary, tasks: number | null | undefined, filters: HomeFilters): TileEntry[] {
  const { tiles, problems, applied } = summary;
  const period = summary.period.key;
  const words = periodWords(period);
  const templatesFailed = problems.includes("templates");
  const fillsFailed = problems.includes("fill-ins");
  const out: TileEntry[] = [];
  const add = (tile: HomeTile | undefined | null, failed: boolean, label: string, build: (tile: HomeTile) => TileView) => {
    if (tile) {
      out.push({ failed: false, label, view: build(tile) });
    } else if (failed) {
      out.push({ failed: true, label, view: { id: label, label, empty: "" } });
    }
  };
  const ignored = (tile: HomeTile): string | undefined => {
    const left = [
      applied.location && !tile.byLocation ? "location" : null,
      applied.department && !tile.byDepartment ? "department" : null,
    ].filter((name): name is string => name !== null);
    return left.length > 0 ? `Not filtered by ${left.join(" or ")}` : undefined;
  };
  const atLeast = (tile: HomeTile) => tile.atLeast;

  add(tiles.waitingApproval, templatesFailed, "Waiting for approval", (tile) => ({
    id: "waiting-approval",
    label: "Waiting for approval",
    value: formatCount(tile.value, atLeast(tile)),
    empty: "",
    hint: tile.extra !== undefined && (tile.value ?? 0) > 0 ? `Oldest has waited ${formatDays(tile.extra)}` : undefined,
    href: HOME_LINKS.templates("IN_REVIEW"),
    note: ignored(tile),
    attention: true,
  }));
  if (typeof tasks === "number") {
    out.push({
      failed: false,
      label: "My approval tasks",
      view: {
        id: "my-tasks",
        label: "My approval tasks",
        value: formatCount(tasks),
        empty: "",
        hint: tasks === 0 ? "Nothing waits for you" : undefined,
        href: HOME_LINKS.approvals,
        note: filters.location || filters.department ? "Not filtered by location or department" : undefined,
        attention: true,
      },
    });
  }
  add(tiles.openChangeRequests, templatesFailed, "Open change requests", (tile) => ({
    id: "open-requests",
    label: "Open change requests",
    value: formatCount(tile.value, atLeast(tile)),
    empty: "",
    hint: "Open or accepted",
    href: HOME_LINKS.requests,
    note: ignored(tile),
    attention: true,
  }));
  add(tiles.fillsWaiting, false, "Fill-ins waiting for review", (tile) => ({
    id: "fills-waiting",
    label: "Fill-ins waiting for review",
    value: formatCount(tile.value, atLeast(tile)),
    empty: "",
    href: HOME_LINKS.fills("SUBMITTED"),
    note: ignored(tile),
    attention: true,
  }));
  add(tiles.activeTemplates, templatesFailed, "Active templates", (tile) => ({
    id: "active-templates",
    label: "Active templates",
    value: formatCount(tile.value, atLeast(tile)),
    empty: "",
    hint: "In use now",
    href: HOME_LINKS.templates("ACTIVE"),
    note: ignored(tile),
  }));
  add(tiles.fillsSubmitted, fillsFailed, "Fill-ins sent", (tile) => {
    const change = describeChange(tile.value, tile.previous, period);
    return {
      id: "fills-sent",
      label: "Fill-ins sent",
      value: formatCount(tile.value, atLeast(tile)),
      empty: "No fill-ins in this period",
      hint: `In the last ${words}`,
      change,
      tone: toneOf(change.direction, "better"),
      href: HOME_LINKS.fills("SUBMITTED"),
      note: ignored(tile),
      spark: summary.charts.fillsPerDay?.days.map((day) => day.count),
    };
  });
  add(tiles.templatesPublished, templatesFailed, "Templates published", (tile) => {
    const change = describeChange(tile.value, tile.previous, period);
    return {
      id: "published",
      label: "Templates published",
      value: formatCount(tile.value, atLeast(tile)),
      empty: "No templates published in this period",
      hint: `In the last ${words}`,
      change,
      tone: toneOf(change.direction, "better"),
      href: HOME_LINKS.templates("ACTIVE"),
      note: ignored(tile),
    };
  });
  add(tiles.averageApprovalDays, templatesFailed, "Average days to decide", (tile) => {
    const change = describeChange(tile.value, tile.previous, period, "days");
    return {
      id: "average-days",
      label: "Average days to decide",
      value: tile.value === undefined ? undefined : formatDays(tile.value),
      empty: "No templates were decided in this period",
      hint: tile.value === undefined ? undefined : "From sending to the decision",
      change,
      tone: toneOf(change.direction, "worse"),
      note: ignored(tile),
    };
  });
  add(tiles.rejectedShare, templatesFailed, "Rejected", (tile) => {
    const change = describeChange(tile.value, tile.previous, period, "points");
    return {
      id: "rejected-share",
      label: "Rejected",
      value: tile.value === undefined ? undefined : formatPercent(tile.value),
      empty: "No templates were decided in this period",
      hint: tile.value === undefined ? undefined : "Of the templates decided",
      change,
      tone: toneOf(change.direction, "worse"),
      note: ignored(tile),
    };
  });
  return out;
}

// ---- the graphs ----

function Charts({
  summary,
  loading,
  filters,
  onNarrow,
  onOpen,
}: {
  summary: HomeSummary | undefined;
  loading: boolean;
  filters: HomeFilters;
  onNarrow: (next: HomeFilters) => void;
  onOpen: (href: string) => void;
}) {
  if (!summary) {
    return (
      <>
        {["Fill-ins per day", "Templates by status", "Templates by level and document type", "Fill-ins by template"].map(
          (title, index) => (
            <ChartCard
              key={title}
              title={title}
              table={{ caption: title, columns: [], rows: [] }}
              loading
              wide={index === 0}
              testId={`chart-loading-${index}`}
            >
              {null}
            </ChartCard>
          ),
        )}
      </>
    );
  }
  const { charts, problems } = summary;
  const templatesFailed = problems.includes("templates");
  const fillsFailed = problems.includes("fill-ins");
  const failedWords = "Could not be worked out. Refresh to try again.";

  const perDay = charts.fillsPerDay;
  const statuses: Slice[] | undefined = charts.templatesByStatus?.map((one) => ({
    key: one.key,
    label: one.label,
    count: one.count,
  }));
  const kinds = charts.templatesByLevelAndType
    ? [...new Set(charts.templatesByLevelAndType.map((one) => one.typeLabel))]
    : [];
  const levels = charts.templatesByLevelAndType
    ? [...new Set(charts.templatesByLevelAndType.map((one) => one.levelLabel))]
    : [];
  const stackRows = levels.map((level) => ({
    key: level,
    label: level,
    parts: (charts.templatesByLevelAndType ?? [])
      .filter((one) => one.levelLabel === level)
      .map((one) => ({ label: one.typeLabel, count: one.count })),
  }));
  const byTemplate: Bar[] | undefined = charts.fillsByTemplate?.map((one) => ({
    key: one.templateId,
    label: one.name || "Template",
    count: one.count,
  }));
  const byDepartment: Bar[] | undefined = charts.fillsByDepartment?.map((one) => ({
    key: one.location && one.department ? `${one.location}/${one.department}` : "none",
    label: one.label,
    count: one.count,
  }));
  const steps = charts.approvalStepDays;
  const months = charts.publishedPerMonth;
  const monthTotal = months?.reduce((sum, one) => sum + one.count, 0) ?? 0;
  const period = periodWords(summary.period.key);

  return (
    <>
      <ChartCard
        wide
        testId="chart-fills-per-day"
        title="Fill-ins per day"
        note={perDay ? `${formatCount(perDay.total, perDay.atLeast)} in the last ${period}` : undefined}
        empty={fillsFailed ? failedWords : !perDay ? undefined : perDay.total === 0 ? "No fill-ins in this period" : undefined}
        loading={loading && !perDay && !fillsFailed}
        table={{
          caption: "Fill-ins sent on each day of the period",
          columns: ["Day", "Fill-ins"],
          rows: (perDay?.days ?? []).map((one) => [dayLabel(one.day), one.count]),
        }}
      >
        {perDay ? <AreaChart days={perDay.days} unit="fill-ins" /> : null}
      </ChartCard>

      {statuses || templatesFailed ? (
        <ChartCard
          testId="chart-templates-by-status"
          title="Templates by status"
          note={statuses ? `${formatCount(statuses.reduce((sum, one) => sum + one.count, 0))} templates` : undefined}
          empty={templatesFailed ? failedWords : statuses && statuses.every((one) => one.count === 0) ? "There are no templates yet" : undefined}
          table={{
            caption: "Templates by status",
            columns: ["Status", "Templates"],
            rows: (statuses ?? []).map((one) => [one.label, one.count]),
          }}
        >
          {statuses ? (
            <RingChart
              slices={statuses}
              unit="templates"
              onSelect={(key) => onOpen(HOME_LINKS.templates(stateForList(key)))}
            />
          ) : null}
        </ChartCard>
      ) : null}

      {charts.templatesByLevelAndType || templatesFailed ? (
        <ChartCard
          testId="chart-level-and-type"
          title="Templates by level and document type"
          empty={templatesFailed ? failedWords : stackRows.length === 0 ? "There are no templates yet" : undefined}
          table={{
            caption: "Templates by level and document type",
            columns: ["Level", "Document type", "Templates"],
            rows: (charts.templatesByLevelAndType ?? []).map((one) => [one.levelLabel, one.typeLabel, one.count]),
          }}
        >
          <StackedBars rows={stackRows} kinds={kinds} unit="templates" />
        </ChartCard>
      ) : null}

      {byTemplate || fillsFailed ? (
        <ChartCard
          testId="chart-fills-by-template"
          title="Fill-ins by template"
          note="The ten most filled"
          empty={fillsFailed ? failedWords : byTemplate && byTemplate.length === 0 ? "No fill-ins in this period" : undefined}
          table={{
            caption: "Fill-ins by template, the ten most filled",
            columns: ["Template", "Fill-ins"],
            rows: (byTemplate ?? []).map((one) => [one.label, one.count]),
          }}
        >
          <HBars
            bars={byTemplate ?? []}
            unit="fill-ins"
            seriesIndex={1}
            onSelect={(key) => onOpen(HOME_LINKS.fillsOfTemplate(key))}
            selectWords="Open the fill-ins of this template"
          />
        </ChartCard>
      ) : null}

      {byDepartment || fillsFailed ? (
        <ChartCard
          testId="chart-fills-by-department"
          title="Fill-ins by department"
          note={filters.department ? "Showing the chosen department's place; press a bar to narrow the page" : "Press a bar to narrow the page"}
          empty={fillsFailed ? failedWords : byDepartment && byDepartment.length === 0 ? "No fill-ins in this period" : undefined}
          table={{
            caption: "Fill-ins by department",
            columns: ["Department", "Fill-ins"],
            rows: (byDepartment ?? []).map((one) => [one.label, one.count]),
          }}
        >
          <HBars
            bars={byDepartment ?? []}
            unit="fill-ins"
            seriesIndex={2}
            onSelect={(key) => {
              if (key === "none") {
                return;
              }
              const [location = "", department = ""] = key.split("/");
              onNarrow({ ...filters, location, department });
            }}
            selectWords="Show only this department"
          />
        </ChartCard>
      ) : null}

      {steps || templatesFailed ? (
        <ChartCard
          testId="chart-approval-steps"
          title="Approvals: days at each step"
          note="Average, over the approvals decided in this period"
          empty={templatesFailed ? failedWords : steps && steps.length === 0 ? "No approvals were decided in this period" : undefined}
          table={{
            caption: "Average days at each approval step",
            columns: ["Step", "Average days", "Approvals"],
            rows: (steps ?? []).map((one) => [`${one.position}. ${one.name}`, one.averageDays, one.samples]),
          }}
        >
          <HBars
            seriesIndex={3}
            unit="days"
            bars={(steps ?? []).map((one) => ({
              key: String(one.position),
              label: `${one.position}. ${one.name}`,
              count: one.averageDays,
              shown: formatDays(one.averageDays),
              words: `Step ${one.position}, ${one.name}: ${formatDays(one.averageDays)} on average over ${formatCount(one.samples)} approvals`,
            }))}
          />
        </ChartCard>
      ) : null}

      {months || templatesFailed ? (
        <ChartCard
          testId="chart-published-per-month"
          title="Templates published per month"
          note="The last twelve months"
          empty={templatesFailed ? failedWords : months && monthTotal === 0 ? "No templates were published in these months" : undefined}
          table={{
            caption: "Templates published in each of the last twelve months",
            columns: ["Month", "Templates"],
            rows: (months ?? []).map((one) => [monthLabel(one.month), one.count]),
          }}
        >
          <Columns months={months ?? []} unit="templates" />
        </ChartCard>
      ) : null}

      <ChartCard
        wide
        testId="chart-recent-activity"
        title="Recent activity"
        note="Only what you may see"
        empty={charts.recentActivity && charts.recentActivity.length === 0 ? "Nothing has happened yet" : undefined}
        table={{
          caption: "The latest things that happened",
          columns: ["What", "Template or fill-in", "By", "When"],
          rows: (charts.recentActivity ?? []).map((one) => [
            activityWords(one.kind),
            one.name,
            one.who ?? "",
            momentLabel(one.at),
          ]),
        }}
      >
        <ActivityList items={charts.recentActivity ?? []} />
      </ChartCard>
    </>
  );
}
