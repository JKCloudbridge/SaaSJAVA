"use client";

import { useId, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { Swatch } from "./ChartCard";
import {
  areaPath,
  axisIndexes,
  barPercents,
  dayLabel,
  formatCount,
  linePath,
  linePoints,
  monthLabel,
  niceMax,
  ringArcs,
  seriesNumber,
  shareOf,
  ticks,
} from "./model";

/**
 * The graphs of the home page (FA9, ADR-0203), drawn here from the numbers the server counted: plain SVG and HTML, no
 * chart library. Each one can be read three ways: by sight (labels at the marks, a pattern as well as a colour where
 * series touch), by pointer or keyboard (a tooltip that follows the pointer or the arrow keys), and as a table (the card
 * around it offers that, from the same numbers). Colours are the series tokens and nothing else.
 */

const WIDTH = 640;
const HEIGHT = 220;
const LEFT = 44;
const RIGHT = 12;
const TOP = 12;
const BOTTOM = 28;

// ---- a value for each day: the line of fill-ins per day ----

export interface DayValue {
  day: string;
  count: number;
}

/**
 * An area under a line, one point for each day. The arrow keys move along the days (Home and End jump), the pointer
 * reads the nearest day, and either one shows "5 Oct: 12 fill-ins" in a tooltip and to a screen reader.
 */
export function AreaChart({ days, unit }: { days: readonly DayValue[]; unit: string }) {
  const [active, setActive] = useState<number | null>(null);
  const gradient = `area${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  const values = days.map((day) => day.count);
  const top = niceMax(Math.max(0, ...values));
  const plotWidth = WIDTH - LEFT - RIGHT;
  const plotHeight = HEIGHT - TOP - BOTTOM;
  const points = linePoints(values, plotWidth, plotHeight, top);
  const labelled = axisIndexes(days.length, 6);
  const at = active === null ? null : points[active];
  const day = active === null ? null : days[active];
  const sentence = day ? `${dayLabel(day.day)}: ${formatCount(day.count)} ${unit}` : "";

  function onKey(event: KeyboardEvent<HTMLDivElement>) {
    const last = days.length - 1;
    const next =
      event.key === "ArrowRight" ? Math.min(last, (active ?? -1) + 1)
      : event.key === "ArrowLeft" ? Math.max(0, (active ?? days.length) - 1)
      : event.key === "Home" ? 0
      : event.key === "End" ? last
      : null;
    if (next !== null) {
      event.preventDefault();
      setActive(next);
    }
    if (event.key === "Escape") {
      setActive(null);
    }
  }

  function onPointer(event: PointerEvent<HTMLDivElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    const x = ((event.clientX - box.left) / box.width) * WIDTH - LEFT;
    const share = Math.min(1, Math.max(0, x / plotWidth));
    setActive(Math.round(share * Math.max(0, days.length - 1)));
  }

  return (
    <div
      className="chart-area"
      tabIndex={0}
      role="group"
      aria-label={`${unit} for each day. Use the left and right arrow keys to read one day at a time.`}
      onKeyDown={onKey}
      onPointerMove={onPointer}
      onPointerLeave={() => setActive(null)}
      onBlur={() => setActive(null)}
    >
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="chart-svg" role="img" aria-label={`A line of ${unit} for each day`}>
        <defs>
          <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="var(--series-1)" stopOpacity="0.38" />
            <stop offset="100%" stopColor="var(--series-1)" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        {ticks(top, 4).map((tick) => {
          const y = TOP + plotHeight - (tick / top) * plotHeight;
          return (
            <g key={tick}>
              <line x1={LEFT} x2={WIDTH - RIGHT} y1={y} y2={y} className="chart-grid" />
              <text x={LEFT - 8} y={y + 4} textAnchor="end" className="chart-axis">
                {formatCount(tick)}
              </text>
            </g>
          );
        })}
        <g transform={`translate(${LEFT} ${TOP})`}>
          <path d={areaPath(points, plotHeight)} fill={`url(#${gradient})`} />
          <path d={linePath(points)} className="chart-line" />
          {at ? (
            <g>
              <line x1={at.x} x2={at.x} y1={0} y2={plotHeight} className="chart-guide" />
              <circle cx={at.x} cy={at.y} r={5} className="chart-dot" />
            </g>
          ) : null}
        </g>
        {labelled.map((index) => (
          <text
            key={index}
            x={LEFT + (points[index]?.x ?? 0)}
            y={HEIGHT - 8}
            textAnchor={index === 0 ? "start" : index === days.length - 1 ? "end" : "middle"}
            className="chart-axis"
          >
            {dayLabel(days[index]?.day ?? "")}
          </text>
        ))}
      </svg>
      {at && day ? (
        <div
          className="chart-tip chart-tip-float"
          style={{ left: `${((LEFT + at.x) / WIDTH) * 100}%`, top: `${((TOP + at.y) / HEIGHT) * 100}%` } as CSSProperties}
          aria-hidden="true"
        >
          {sentence}
        </div>
      ) : null}
      <p className="visually-hidden" role="status" aria-live="polite">
        {sentence}
      </p>
    </div>
  );
}

// ---- parts of a whole: the ring ----

export interface Slice {
  key: string;
  label: string;
  count: number;
}

/**
 * A ring of how many there are in each state. The total is in the middle (and the state under the pointer or the
 * keyboard replaces it), every state is named in the legend with its count and share, and a state is a button that
 * opens the matching list.
 */
export function RingChart({
  slices,
  unit,
  onSelect,
}: {
  slices: readonly Slice[];
  unit: string;
  onSelect?: (key: string) => void;
}) {
  const [active, setActive] = useState<number | null>(null);
  const total = slices.reduce((sum, slice) => sum + slice.count, 0);
  const arcs = ringArcs(slices.map((slice) => slice.count));
  const radius = 62;
  const circumference = 2 * Math.PI * radius;
  const shown = active === null ? null : slices[active];
  return (
    <div className="chart-ring">
      <div className="chart-ring-figure">
        <svg viewBox="0 0 180 180" className="chart-ring-svg" role="img" aria-label={`${formatCount(total)} ${unit} by state`}>
          <circle cx="90" cy="90" r={radius} fill="none" className="chart-ring-track" strokeWidth="26" />
          <g transform="rotate(-90 90 90)">
            {arcs.map((arc, index) =>
              arc.share === 0 ? null : (
                <circle
                  key={slices[index]?.key}
                  cx="90"
                  cy="90"
                  r={radius}
                  fill="none"
                  stroke={`var(--series-${(index % 6) + 1})`}
                  strokeWidth={active === index ? 30 : 26}
                  strokeDasharray={`${Math.max(0, arc.share * circumference - 2)} ${circumference}`}
                  strokeDashoffset={-arc.start * circumference}
                  className="chart-ring-arc"
                  onPointerEnter={() => setActive(index)}
                  onPointerLeave={() => setActive(null)}
                  onClick={() => onSelect?.(slices[index]?.key ?? "")}
                />
              ),
            )}
          </g>
        </svg>
        <div className="chart-ring-centre" aria-hidden="true">
          <strong>{formatCount(shown ? shown.count : total)}</strong>
          <span>{shown ? shown.label : unit}</span>
        </div>
      </div>
      <ul className="chart-legend">
        {slices.map((slice, index) => {
          const body = (
            <>
              <Swatch index={index} solid />
              <span className="chart-legend-name">{slice.label}</span>
              <span className="chart-legend-count">{formatCount(slice.count)}</span>
              <span className="chart-legend-share">{shareOf(slice.count, total)}%</span>
            </>
          );
          return (
            <li key={slice.key} data-active={active === index ? "true" : undefined}>
              {onSelect ? (
                <button
                  type="button"
                  className="chart-legend-row"
                  onClick={() => onSelect(slice.key)}
                  onPointerEnter={() => setActive(index)}
                  onPointerLeave={() => setActive(null)}
                  onFocus={() => setActive(index)}
                  onBlur={() => setActive(null)}
                  aria-label={`${slice.label}: ${formatCount(slice.count)} ${unit}. Open the list.`}
                >
                  {body}
                </button>
              ) : (
                <span className="chart-legend-row">{body}</span>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// ---- horizontal bars ----

export interface Bar {
  key: string;
  label: string;
  count: number;
  /** What the tooltip and a screen reader say; the label and the count by default. */
  words?: string;
  /** The value as written at the end of the bar; the count by default. */
  shown?: string;
}

/**
 * Horizontal bars, one row each, the longest filling the track. The value is written at the end of every bar (a direct
 * label, so nothing depends on colour or on hovering). A row is a button when it leads somewhere or narrows the page.
 */
export function HBars({
  bars,
  unit,
  onSelect,
  selectWords,
  seriesIndex = 0,
}: {
  bars: readonly Bar[];
  unit: string;
  onSelect?: (key: string) => void;
  /** What pressing a row does, for a screen reader: "Show only this department". */
  selectWords?: string;
  seriesIndex?: number;
}) {
  const widths = barPercents(bars.map((bar) => bar.count));
  return (
    <ul className="chart-bars">
      {bars.map((bar, index) => {
        const words = bar.words ?? `${bar.label}: ${formatCount(bar.count)} ${unit}`;
        const inside = (
          <>
            <span className="chart-bar-label" title={bar.label}>
              {bar.label}
            </span>
            <span className="chart-bar-track" aria-hidden="true">
              <span
                className="chart-bar-fill"
                style={{ width: `${widths[index]}%`, "--bar-series": `var(--series-${seriesNumber(seriesIndex)})` } as CSSProperties}
              />
            </span>
            <span className="chart-bar-value">{bar.shown ?? formatCount(bar.count)}</span>
            <span className="chart-tip chart-tip-bar" aria-hidden="true">
              {words}
            </span>
          </>
        );
        return (
          <li key={bar.key}>
            {onSelect ? (
              <button
                type="button"
                className="chart-bar-row chart-hit"
                onClick={() => onSelect(bar.key)}
                aria-label={`${words}.${selectWords ? ` ${selectWords}.` : ""}`}
              >
                {inside}
              </button>
            ) : (
              <div className="chart-bar-row chart-hit" tabIndex={0} aria-label={words}>
                {inside}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

// ---- stacked bars: a total split by a second thing ----

export interface StackRow {
  key: string;
  label: string;
  parts: readonly { label: string; count: number }[];
}

/**
 * Stacked horizontal bars: one row for each level, split by document type. The types are told apart by a solid colour and named in the legend; every part carries its own tooltip and the row's total is written at its end.
 */
export function StackedBars({ rows, kinds, unit }: { rows: readonly StackRow[]; kinds: readonly string[]; unit: string }) {
  const totals = rows.map((row) => row.parts.reduce((sum, part) => sum + part.count, 0));
  const most = Math.max(1, ...totals);
  return (
    <div className="chart-stack">
      <ul className="chart-legend chart-legend-inline">
        {kinds.map((kind, index) => (
          <li key={kind}>
            <span className="chart-legend-row">
              <Swatch index={index} solid />
              <span className="chart-legend-name">{kind}</span>
            </span>
          </li>
        ))}
      </ul>
      <ul className="chart-bars">
        {rows.map((row, at) => (
          <li key={row.key}>
            <div className="chart-bar-row" aria-label={`${row.label}: ${formatCount(totals[at] ?? 0)} ${unit}`} tabIndex={0}>
              <span className="chart-bar-label" title={row.label}>
                {row.label}
              </span>
              <span className="chart-bar-track chart-bar-track-stack">
                <svg className="chart-stack-svg" viewBox="0 0 100 10" preserveAspectRatio="none" aria-hidden="true">
                  {row.parts.reduce<{ x: number; marks: ReactNode[] }>(
                    (state, part) => {
                      const width = ((part.count / most) * 100);
                      const index = Math.max(0, kinds.indexOf(part.label));
                      state.marks.push(
                        <rect
                          key={part.label}
                          x={state.x}
                          y={0}
                          width={Math.max(0, width - 0.4)}
                          height={10}
                          fill={`var(--series-${(index % 6) + 1})`}
                        >
                          <title>{`${row.label}, ${part.label}: ${formatCount(part.count)} ${unit}`}</title>
                        </rect>,
                      );
                      return { x: state.x + width, marks: state.marks };
                    },
                    { x: 0, marks: [] },
                  ).marks}
                </svg>
              </span>
              <span className="chart-bar-value">{formatCount(totals[at] ?? 0)}</span>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ---- columns: a value for each month ----

export interface MonthValue {
  month: string;
  count: number;
}

/**
 * One column for each month. The value stands above every column, the month under it, and the tooltip says both. The
 * columns can be walked with Tab.
 */
export function Columns({ months, unit }: { months: readonly MonthValue[]; unit: string }) {
  const top = niceMax(Math.max(0, ...months.map((month) => month.count)));
  return (
    <ul className="chart-columns" aria-label={`${unit} for each month`}>
      {months.map((month) => {
        const words = `${monthLabel(month.month)}: ${formatCount(month.count)} ${unit}`;
        return (
          <li key={month.month} className="chart-column chart-hit" tabIndex={0} aria-label={words}>
            <span className="chart-column-value">{month.count > 0 ? formatCount(month.count) : ""}</span>
            <span className="chart-column-track" aria-hidden="true">
              <span className="chart-column-fill" style={{ height: `${(month.count / top) * 100}%` }} />
            </span>
            <span className="chart-column-label" aria-hidden="true">
              {monthLabel(month.month)}
            </span>
            <span className="chart-tip chart-tip-bar" aria-hidden="true">
              {words}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
