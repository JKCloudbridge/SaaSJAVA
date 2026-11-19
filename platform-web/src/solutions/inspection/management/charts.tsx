"use client";

import { type CSSProperties } from "react";
import { Swatch } from "../home/ChartCard";
import {
  formatScore,
  markOf,
  monthShort,
  monthX,
  runsOf,
  scaleShare,
  scoreY,
  seriesOf,
  type Mark,
  type MonthStatus,
  type PlantPlans,
  type PlantScore,
} from "./model";

/**
 * The graphs of the Home page that compares the plants (MC3, ADR-0262), drawn from the numbers the server counted: plain
 * SVG and HTML, no chart library. Each can be read three ways: by sight (a value written at every mark, a shape or a
 * pattern as well as a colour), by a screen reader (every mark has its words) and as a table (the card around it offers
 * that, from the same numbers). Colours are the series tokens; the scale of a score is always 0 to 100, so a bar is never
 * stretched to look better than it is. A month a plant did not report is a gap in a line, never a zero.
 */

const WIDTH = 640;
const HEIGHT = 240;
const LEFT = 44;
const RIGHT = 16;
const TOP = 14;
const BOTTOM = 30;
const SCALE = [0, 25, 50, 75, 100] as const;

// ---- the score of each plant: bars on a 0 to 100 scale ----

/**
 * One row for each plant, a bar on a 0 to 100 scale and the score written at its end with the band's word. The limit is
 * a line across every bar. A plant with no approved fill-in has an empty bar and says so: it is a gap, not a zero.
 */
export function ScoreBars({
  plants,
  limit,
  onSelect,
}: {
  plants: readonly PlantScore[];
  limit?: number;
  onSelect?: (plant: string) => void;
}) {
  return (
    <ul className="chart-bars" data-testid="score-bars">
      {plants.map((plant) => {
        const words =
          plant.score === undefined || plant.score === null
            ? `${plant.label}: no approved fill-in`
            : `${plant.label}: ${formatScore(plant.score)}, ${plant.bandWord ?? ""}${
                plant.needsActionPlan ? ", needs an Action plan" : ""
              }`;
        const body = (
          <>
            <span className="chart-bar-label" title={plant.label}>
              {plant.label}
            </span>
            <span className="chart-bar-track score-track" aria-hidden="true">
              {plant.score !== undefined && plant.score !== null ? (
                <span
                  className="chart-bar-fill"
                  style={{ width: `${scaleShare(plant.score)}%`, "--bar-series": "var(--series-1)" } as CSSProperties}
                />
              ) : null}
              {limit !== undefined ? <span className="score-limit" style={{ left: `${scaleShare(limit)}%` }} /> : null}
            </span>
            <span className="chart-bar-value score-value">
              {plant.score === undefined || plant.score === null ? "No score" : formatScore(plant.score)}
              {plant.bandWord ? <span className="band-word"> {plant.bandWord}</span> : null}
            </span>
          </>
        );
        return (
          <li key={plant.plant}>
            {onSelect ? (
              <button
                type="button"
                className="chart-bar-row chart-hit"
                onClick={() => onSelect(plant.plant)}
                aria-label={`${words}. Show only this plant.`}
              >
                {body}
              </button>
            ) : (
              <div className="chart-bar-row chart-hit" tabIndex={0} aria-label={words}>
                {body}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

// ---- lines over the months ----

export interface LineSeries {
  key: string;
  label: string;
  /** One score for each month of the chart; null is a gap. */
  scores: readonly (number | null | undefined)[];
}

/** One mark of a series: a shape at a point, drawn the same size for every series. */
function Marker({ mark, x, y, series, words }: { mark: Mark; x: number; y: number; series: number; words: string }) {
  const style = { fill: "var(--color-surface)", stroke: `var(--series-${series})`, strokeWidth: 2 };
  const r = 4.5;
  return (
    <g>
      <title>{words}</title>
      {mark === "circle" ? <circle cx={x} cy={y} r={r} style={style} /> : null}
      {mark === "square" ? <rect x={x - r} y={y - r} width={2 * r} height={2 * r} style={style} /> : null}
      {mark === "triangle" ? <path d={`M${x} ${y - r - 1} L${x + r + 1} ${y + r} L${x - r - 1} ${y + r} Z`} style={style} /> : null}
      {mark === "diamond" ? <path d={`M${x} ${y - r - 1} L${x + r + 1} ${y} L${x} ${y + r + 1} L${x - r - 1} ${y} Z`} style={style} /> : null}
      {mark === "cross" ? <path d={`M${x - r} ${y - r} L${x + r} ${y + r} M${x + r} ${y - r} L${x - r} ${y + r}`} style={style} /> : null}
      {mark === "plus" ? <path d={`M${x} ${y - r - 1} L${x} ${y + r + 1} M${x - r - 1} ${y} L${x + r + 1} ${y}`} style={style} /> : null}
    </g>
  );
}

// Solid lines in solid colours (owner's choice, 2026-11-17, Management Core only); the marker shape at each point still
// tells the plants apart without colour.
const DASHES = [""];

/**
 * Lines of scores on a 0 to 100 scale, one for each series, with a month on the axis. A month with no score ends the
 * line and starts it again later: it is a gap. Series differ by shape and dash as well as by colour, and the limit is a
 * dashed line across the chart.
 */
export function ScoreLines({
  months,
  series,
  limit,
  label,
}: {
  months: readonly string[];
  series: readonly LineSeries[];
  limit?: number;
  label: string;
}) {
  const plotWidth = WIDTH - LEFT - RIGHT;
  const plotHeight = HEIGHT - TOP - BOTTOM;
  return (
    <div className="score-lines">
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="chart-svg" role="img" aria-label={label}>
        {SCALE.map((tick) => {
          const y = TOP + scoreY(tick, plotHeight);
          return (
            <g key={tick}>
              <line x1={LEFT} x2={WIDTH - RIGHT} y1={y} y2={y} className="chart-grid" />
              <text x={LEFT - 8} y={y + 4} textAnchor="end" className="chart-axis">
                {tick}
              </text>
            </g>
          );
        })}
        {limit !== undefined ? (
          <line
            x1={LEFT}
            x2={WIDTH - RIGHT}
            y1={TOP + scoreY(limit, plotHeight)}
            y2={TOP + scoreY(limit, plotHeight)}
            className="score-limit-line"
          />
        ) : null}
        {months.map((month, index) => (
          <text
            key={month}
            x={LEFT + monthX(index, months.length, plotWidth)}
            y={HEIGHT - 8}
            textAnchor={index === 0 && months.length > 1 ? "start" : index === months.length - 1 && months.length > 1 ? "end" : "middle"}
            className="chart-axis"
          >
            {monthShort(month)}
          </text>
        ))}
        <g transform={`translate(${LEFT} ${TOP})`}>
          {series.map((one, at) => {
            const colour = seriesOf(at);
            const points = one.scores.map((score, index) => ({ score, index }));
            return (
              <g key={one.key} data-testid={`line-${one.key}`}>
                {runsOf(points).map((run) => {
                  const path = run
                    .map(({ index, point }, step) =>
                      `${step === 0 ? "M" : "L"}${monthX(index, months.length, plotWidth)} ${scoreY(point.score ?? 0, plotHeight)}`,
                    )
                    .join(" ");
                  return run.length > 1 ? (
                    <path
                      key={run[0]?.index ?? 0}
                      d={path}
                      className="score-line"
                      style={{ stroke: `var(--series-${colour})`, strokeDasharray: DASHES[at % DASHES.length] }}
                    />
                  ) : null;
                })}
                {points.map(({ score, index }) =>
                  score === null || score === undefined ? null : (
                    <Marker
                      key={index}
                      mark={markOf(at)}
                      series={colour}
                      x={monthX(index, months.length, plotWidth)}
                      y={scoreY(score, plotHeight)}
                      words={`${one.label}, ${monthShort(months[index] ?? "")}: ${formatScore(score)}`}
                    />
                  ),
                )}
              </g>
            );
          })}
        </g>
      </svg>
      {series.length > 1 ? (
        <ul className="score-lines-legend" aria-label="Plants">
          {series.map((one, at) => (
            <li key={one.key}>
              <svg viewBox="0 0 24 12" className="score-lines-key" aria-hidden="true" focusable="false">
                <path d="M0 6 H24" style={{ stroke: `var(--series-${seriesOf(at)})`, strokeWidth: 2, strokeDasharray: DASHES[at % DASHES.length] }} />
                <Marker mark={markOf(at)} series={seriesOf(at)} x={12} y={6} words="" />
              </svg>
              {one.label}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

// ---- the ranking ----

/** The plants that have a score, best first, with their rank and the band's word. A shared score shares a rank. */
export function RankList({ plants }: { plants: readonly PlantScore[] }) {
  return (
    <ol className="rank-list" data-testid="rank-list">
      {plants.map((plant) => (
        <li key={plant.plant} className="rank-row">
          <span className="rank-number">{plant.rank}</span>
          <span className="rank-name">{plant.label}</span>
          <span className="rank-score">{formatScore(plant.score)}</span>
          <span className="band-word">{plant.bandWord}</span>
        </li>
      ))}
    </ol>
  );
}

// ---- Action plans by plant: parts of a whole, side by side ----

// The parts of a plant's Action plans, in the order they are drawn. "Owed" (2026-11-17) is a fill-in below the limit whose
// Action plan was not started: the reviewer's Submit finishes the fill-in first, so "not filled" must be seen. It takes the
// danger colour; the others take the series colours. Solid fills only.
const PLAN_PARTS = [
  { key: "owed", label: "Owed, not started", colour: "var(--color-danger)" },
  { key: "open", label: "Being written or returned", colour: `var(--series-${seriesOf(0)})` },
  { key: "sent", label: "Sent, waiting for a decision", colour: `var(--series-${seriesOf(1)})` },
  { key: "approved", label: "Approved", colour: `var(--series-${seriesOf(2)})` },
] as const;

/** How many of a part a plant has; a server that does not send "owed" yet counts none. */
function partOf(plant: PlantPlans, key: (typeof PLAN_PARTS)[number]["key"]): number {
  return plant[key] ?? 0;
}

/** One row for each plant: its Action plans owed and the ones it has, split by where they stand, with the numbers written out. */
export function PlansByPlant({ plants }: { plants: readonly PlantPlans[] }) {
  const totalOf = (plant: PlantPlans) => PLAN_PARTS.reduce((sum, part) => sum + partOf(plant, part.key), 0);
  const most = Math.max(1, ...plants.map(totalOf));
  return (
    <div className="plan-stack" data-testid="plans-by-plant">
      <ul className="chart-legend chart-legend-inline" aria-label="Where an Action plan stands">
        {PLAN_PARTS.map((part) => (
          <li key={part.key} className="chart-legend-row">
            <svg className="chart-swatch" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
              <rect width="12" height="12" rx="2" style={{ fill: part.colour }} />
            </svg>
            <span className="chart-legend-name">{part.label}</span>
          </li>
        ))}
      </ul>
      <ul className="chart-bars">
        {plants.map((plant) => {
          const owed = partOf(plant, "owed");
          return (
            <li key={plant.plant} className="chart-bar-row" tabIndex={0} data-testid={`plans-${plant.plant}`}
              aria-label={`${plant.label}: ${owed} owed and not started, ${plant.open} being written or returned, ${plant.sent} sent, ${plant.approved} approved`}>
              <span className="chart-bar-label" title={plant.label}>
                {plant.label}
              </span>
              <span className="chart-bar-track plan-track" aria-hidden="true" style={{ width: `${(totalOf(plant) / most) * 100}%` }}>
                {PLAN_PARTS.map((part) =>
                  partOf(plant, part.key) > 0 ? (
                    <span key={part.key} className="plan-part" style={{ flexGrow: partOf(plant, part.key), background: part.colour }} />
                  ) : null,
                )}
              </span>
              <span className="chart-bar-value">
                {owed} · {plant.open} · {plant.sent} · {plant.approved}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// ---- fill-ins by month and status: stacked columns ----

const STATUS_PARTS = [
  { key: "draft", label: "Being written" },
  { key: "submitted", label: "Sent, waiting" },
  { key: "returned", label: "Sent back" },
  { key: "approved", label: "Approved" },
] as const;

/**
 * A column for each month, split by status, in solid colours (owner's choice, 2026-11-17: no patterns in Management Core),
 * the total above the column and every part in the column's words and the table view.
 */
export function StatusColumns({ months }: { months: readonly MonthStatus[] }) {
  const totals = months.map((month) => month.draft + month.submitted + month.returned + month.approved);
  const top = Math.max(1, ...totals);
  const plotWidth = WIDTH - LEFT - RIGHT;
  const plotHeight = HEIGHT - TOP - BOTTOM;
  const slot = plotWidth / Math.max(1, months.length);
  const width = Math.min(48, slot * 0.6);
  return (
    <div className="status-columns" data-testid="status-columns">
      <ul className="chart-legend chart-legend-inline" aria-label="Status">
        {STATUS_PARTS.map((part, index) => (
          <li key={part.key} className="chart-legend-row">
            <Swatch index={index} solid />
            <span className="chart-legend-name">{part.label}</span>
          </li>
        ))}
      </ul>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="chart-svg" role="img" aria-label="Fill-ins of each month by status">
        <line x1={LEFT} x2={WIDTH - RIGHT} y1={TOP + plotHeight} y2={TOP + plotHeight} className="chart-grid" />
        {months.map((month, index) => {
          const x = LEFT + index * slot + (slot - width) / 2;
          let used = 0;
          return (
            <g key={month.month}>
              <title>
                {`${monthShort(month.month)}: ${month.draft} being written, ${month.submitted} sent, ${month.returned} sent back, ${month.approved} approved`}
              </title>
              {STATUS_PARTS.map((part, at) => {
                const value = month[part.key];
                if (value === 0) {
                  return null;
                }
                const height = (value / top) * plotHeight;
                const y = TOP + plotHeight - used - height;
                used += height;
                return <rect key={part.key} x={x} y={y} width={width} height={height} style={{ fill: `var(--series-${seriesOf(at)})` }} />;
              })}
              {(totals[index] ?? 0) > 0 ? (
                <text x={x + width / 2} y={TOP + plotHeight - used - 4} textAnchor="middle" className="chart-axis">
                  {totals[index]}
                </text>
              ) : null}
              <text x={x + width / 2} y={HEIGHT - 8} textAnchor="middle" className="chart-axis">
                {monthShort(month.month)}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
