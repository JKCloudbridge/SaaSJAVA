"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { readHomeLook, writeHomeLook, type HomeLook } from "./look";
import { loadMyTasks, useSummary } from "./api";
import { ActivityList } from "./ActivityList";
import { ChartCard } from "./ChartCard";
import { AreaChart, DepartmentGroups, HBars, RingChart, type Bar, type DepartmentGroup, type Slice } from "./charts";
import { creamSeries, HeatMap, HRows, VBars } from "./creamCharts";
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
  const [look, setLook] = useState<HomeLook>(1);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- browser storage is read after mount, so the server and first draw agree
    setLook(readHomeLook());
  }, []);

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
    <div className={look === 2 ? "dash dash-cream" : "dash"} data-look={look} data-testid="quality-home" aria-busy={state.status === "loading" || refreshing}>
      <header className="dash-head">
        {look === 2 ? (
          <div className="dash-head-text">
            <h1 className="dash-title">Template approvals</h1>
            <p className="dash-lead">{headline(summary)}</p>
          </div>
        ) : (
          <h1 className="visually-hidden">Quality Management System</h1>
        )}
        <div className="dash-look" role="radiogroup" aria-label="Look of this page">
          {([1, 2] as const).map((option) => (
            <button
              key={option}
              type="button"
              role="radio"
              aria-checked={look === option}
              className="dash-look-option"
              onClick={() => {
                setLook(option);
                writeHomeLook(option);
              }}
            >
              Option {option}
            </button>
          ))}
        </div>
      </header>

      <FilterBar
        filters={filters}
        locations={summary?.filters.locations ?? []}
        onChange={go}
        onRefresh={refresh}
        refreshing={refreshing || state.status === "loading"}
        updatedAt={summary?.generatedAt}
        variant={look === 2 ? "select" : "bars"}
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
          {orderedTiles(tilesOf(summary, tasks, filters), look).map((entry) =>
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
          look={look}
        />
      </div>
    </div>
  );
}

/** The tiles of the second look: the nine of its design, in its order; the first look keeps all of them. */
function orderedTiles(entries: TileEntry[], look: HomeLook): TileEntry[] {
  if (look !== 2) {
    return entries;
  }
  const order = ["active-templates", "waiting-approval", "my-tasks", "open-requests", "fills-sent", "fills-waiting", "published", "average-days", "rejected-share", "drafts", "approved-unpublished"];
  return entries
    .filter((entry) => entry.failed || order.includes(entry.view.id))
    .sort((a, b) => (order.indexOf(a.view.id) + 1 || 99) - (order.indexOf(b.view.id) + 1 || 99));
}

/** The sentence under the title of the second look: what waits, and the slowest step of the approvals. */
function headline(summary: HomeSummary | undefined): string {
  if (!summary) {
    return "";
  }
  const waiting = summary.tiles.waitingApproval?.value;
  const steps = summary.charts.approvalStepDays ?? [];
  const slowest = steps.reduce<(typeof steps)[number] | undefined>(
    (worst, one) => (worst === undefined || one.averageDays > worst.averageDays ? one : worst),
    undefined,
  );
  const first =
    waiting === undefined
      ? "Where templates stand."
      : `${formatCount(waiting)} ${waiting === 1 ? "template is" : "templates are"} waiting for approval.`;
  return slowest ? `${first} ${slowest.name} is the slowest step, at ${formatDays(slowest.averageDays)} on average.` : first;
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
  const byStatus = summary.charts.templatesByStatus;
  const statusCount = (key: string) => byStatus?.find((one) => one.key === key)?.count;
  const drafts = statusCount("DRAFT");
  const approved = statusCount("APPROVED");
  if (drafts !== undefined && !filters.location && !filters.department) {
    out.push({
      failed: false,
      label: "Drafts in progress",
      view: {
        id: "drafts",
        label: "Drafts in progress",
        value: formatCount(drafts),
        empty: "",
        hint: "Not yet sent for approval",
        href: HOME_LINKS.templates("DRAFT"),
      },
    });
  }
  if (approved !== undefined && !filters.location && !filters.department) {
    out.push({
      failed: false,
      label: "Approved, not published",
      view: {
        id: "approved-unpublished",
        label: "Approved, not published",
        value: formatCount(approved),
        empty: "",
        hint: "Waiting for the Publisher",
        href: HOME_LINKS.templates("APPROVED"),
        attention: true,
      },
    });
  }
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
  look,
}: {
  look: HomeLook;
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
  const heatLevels = levels.map((level) => ({ key: level, label: level }));
  const heatCounts = new Map((charts.templatesByLevelAndType ?? []).map((one) => [`${one.levelLabel}|${one.typeLabel}`, one.count]));
  const departmentGroups: DepartmentGroup[] = [];
  for (const one of charts.fillsByDepartmentAndTemplate ?? []) {
    const key = one.location && one.department ? `${one.location}/${one.department}` : "none";
    let group = departmentGroups.find((candidate) => candidate.key === key);
    if (!group) {
      group = {
        key,
        label: one.label,
        narrow: one.location && one.department ? { location: one.location, department: one.department } : undefined,
        rows: [],
      };
      departmentGroups.push(group);
    }
    (group.rows as { key: string; label: string; count: number }[]).push({
      key: one.templateId,
      label: one.templateName,
      count: one.count,
    });
  }
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

  if (look === 2) {
    const slowest = (steps ?? []).reduce<number>((worst, one, index, all) => (one.averageDays > (all[worst]?.averageDays ?? -1) ? index : worst), 0);
    return (
      <>
        <ChartCard
          wide
          testId="chart-fills-per-day"
          title="Fill-ins per day"
          note={perDay ? `Last ${period}, by day` : undefined}
          empty={fillsFailed ? failedWords : !perDay ? undefined : perDay.total === 0 ? "No fill-ins in this period" : undefined}
          loading={loading && !perDay && !fillsFailed}
          table={{ caption: "Fill-ins sent on each day of the period", columns: ["Day", "Fill-ins"], rows: (perDay?.days ?? []).map((one) => [dayLabel(one.day), one.count]) }}
        >
          {perDay ? (
            <VBars
              aria="Fill-ins per day"
              unit="fill-ins"
              bars={perDay.days.map((one) => ({ key: one.day, short: dayLabel(one.day), label: dayLabel(one.day), count: one.count }))}
            />
          ) : null}
        </ChartCard>

        {statuses || templatesFailed ? (
          <ChartCard
            testId="chart-templates-by-status"
            title="Templates by status"
            note="All templates you can open, as of today"
            empty={templatesFailed ? failedWords : statuses && statuses.every((one) => one.count === 0) ? "There are no templates yet" : undefined}
            table={{ caption: "Templates by status", columns: ["Status", "Templates"], rows: (statuses ?? []).map((one) => [one.label, one.count]) }}
          >
            <HRows
              rows={(statuses ?? []).map((one, index) => ({
                key: one.key,
                label: one.label,
                count: one.count,
                paint: creamSeries(index),
                words: `${one.label}: ${formatCount(one.count)} templates`,
              }))}
              onSelect={(key) => onOpen(HOME_LINKS.templates(stateForList(key)))}
            />
          </ChartCard>
        ) : null}

        {charts.templatesByLevelAndType || templatesFailed ? (
          <ChartCard
            testId="chart-level-and-type"
            title="Templates by level and document type"
            note="All templates you can open, by level and document type"
            empty={templatesFailed ? failedWords : stackRows.length === 0 ? "There are no templates yet" : undefined}
            table={{
              caption: "Templates by level and document type",
              columns: ["Level", "Document type", "Templates"],
              rows: (charts.templatesByLevelAndType ?? []).map((one) => [one.levelLabel, one.typeLabel, one.count]),
            }}
          >
            <HeatMap levels={heatLevels} kinds={kinds} counts={heatCounts} tone={creamSeries(0)} unit="templates" />
          </ChartCard>
        ) : null}

        {byTemplate || byDepartment || fillsFailed ? (
          <ChartCard
            wide
            testId="chart-fills-by-template"
            title="Fill-ins by template and by department"
            note={`Last ${period}. Press a department to filter the whole page`}
            empty={fillsFailed ? failedWords : (byTemplate?.length ?? 0) === 0 && (byDepartment?.length ?? 0) === 0 ? "No fill-ins in this period" : undefined}
            table={{
              caption: "Fill-ins by template and by department",
              columns: ["Group", "Name", "Fill-ins"],
              rows: [
                ...(byTemplate ?? []).map((one) => ["Template", one.label, one.count]),
                ...(byDepartment ?? []).map((one) => ["Department", one.label, one.count]),
              ],
            }}
          >
            <div className="hrows-two">
              <div>
                <h4 className="hrows-heading">Top templates</h4>
                <HRows
                  rows={(byTemplate ?? []).map((one) => ({ ...one, words: `${one.label}: ${formatCount(one.count)} fill-ins` }))}
                  onSelect={(key) => onOpen(HOME_LINKS.fillsOfTemplate(key))}
                />
              </div>
              <div>
                <h4 className="hrows-heading">Departments</h4>
                <HRows
                  rows={(byDepartment ?? []).map((one) => ({ ...one, words: `${one.label}: ${formatCount(one.count)} fill-ins` }))}
                  selected={filters.location && filters.department ? `${filters.location}/${filters.department}` : undefined}
                  onSelect={(key) => {
                    if (key === "none") {
                      return;
                    }
                    const [location = "", department = ""] = key.split("/");
                    const same = filters.location === location && filters.department === department;
                    onNarrow({ ...filters, location: same ? "" : location, department: same ? "" : department });
                  }}
                />
              </div>
            </div>
          </ChartCard>
        ) : null}

        {steps || templatesFailed ? (
          <ChartCard
            testId="chart-approval-steps"
            title="Average days at each approval step"
            note="Steps finished in this period. The darkest bar is the slowest"
            empty={templatesFailed ? failedWords : steps && steps.length === 0 ? "No approvals were decided in this period" : undefined}
            table={{
              caption: "Average days at each approval step",
              columns: ["Step", "Average days", "Approvals"],
              rows: (steps ?? []).map((one) => [`${one.position}. ${one.name}`, one.averageDays, one.samples]),
            }}
          >
            <HRows
              rows={(steps ?? []).map((one, index) => ({
                key: String(one.position),
                label: `${one.position}. ${one.name}`,
                count: one.averageDays,
                shown: formatDays(one.averageDays),
                paint: index === slowest ? creamSeries(0) : "var(--cream-muted-bar)",
                words: `Step ${one.position}, ${one.name}: ${formatDays(one.averageDays)} on average over ${formatCount(one.samples)} approvals`,
              }))}
            />
          </ChartCard>
        ) : null}

        {months || templatesFailed ? (
          <ChartCard
            testId="chart-published-per-month"
            title="Published per month"
            note="The last twelve months"
            empty={templatesFailed ? failedWords : months && monthTotal === 0 ? "No templates were published in these months" : undefined}
            table={{ caption: "Templates published in each of the last twelve months", columns: ["Month", "Templates"], rows: (months ?? []).map((one) => [monthLabel(one.month), one.count]) }}
          >
            <VBars
              aria="Templates published per month"
              unit="templates"
              bars={(months ?? []).map((one) => ({ key: one.month, short: monthLabel(one.month), label: monthLabel(one.month), count: one.count }))}
            />
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
            rows: (charts.recentActivity ?? []).map((one) => [activityWords(one.kind), one.name, one.who ?? "", momentLabel(one.at)]),
          }}
        >
          <ActivityList items={charts.recentActivity ?? []} />
        </ChartCard>
      </>
    );
  }

  return (
    <>
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
          <HeatMap levels={heatLevels} kinds={kinds} counts={heatCounts} tone="var(--series-2)" unit="templates" />
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

      {charts.fillsByDepartmentAndTemplate || fillsFailed ? (
        <ChartCard
          wide
          testId="chart-fills-by-department"
          title="Fill-ins by department by template"
          note="Every department and its templates. Press a department to narrow the page"
          empty={fillsFailed ? failedWords : departmentGroups.length === 0 ? "No fill-ins in this period" : undefined}
          table={{
            caption: "Fill-ins by department and template",
            columns: ["Department", "Template", "Fill-ins"],
            rows: (charts.fillsByDepartmentAndTemplate ?? []).map((one) => [one.label, one.templateName, one.count]),
          }}
        >
          <DepartmentGroups
            groups={departmentGroups}
            unit="fill-ins"
            onNarrow={(narrow) => onNarrow({ ...filters, ...narrow })}
          />
        </ChartCard>
      ) : null}

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
