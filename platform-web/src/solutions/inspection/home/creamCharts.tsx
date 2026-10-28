"use client";

import type { CSSProperties } from "react";
import { formatCount, niceMax } from "./model";

/** The paint of the n-th series of the second look: eight muted hues, as in its design. */
export const creamSeries = (index: number): string => `var(--cream-s${index % 8})`;

export interface HeatRow {
  key: string;
  label: string;
}

/**
 * The heat map of the templates: a row for each level, a column for each document type, the count in each cell and a
 * deeper tint where there are more. One hue (`tone`) so it reads as a single scale; a cell with none is plain. Every cell
 * says its level, type and count to a screen reader, and shows the same in a tooltip.
 */
export function HeatMap({
  levels,
  kinds,
  counts,
  tone,
  unit,
}: {
  levels: readonly HeatRow[];
  kinds: readonly string[];
  /** The count at "level|type". */
  counts: ReadonlyMap<string, number>;
  /** A colour the tint is made of (a token). */
  tone: string;
  unit: string;
}) {
  const most = Math.max(1, ...counts.values());
  return (
    <div className="heat" style={{ "--heat-columns": kinds.length } as CSSProperties}>
      <div />
      {kinds.map((kind) => (
        <div key={kind} className="heat-head">
          {kind}
        </div>
      ))}
      {levels.map((level) => (
        <HeatLine key={level.key} level={level} kinds={kinds} counts={counts} most={most} tone={tone} unit={unit} />
      ))}
    </div>
  );
}

function HeatLine({
  level,
  kinds,
  counts,
  most,
  tone,
  unit,
}: {
  level: HeatRow;
  kinds: readonly string[];
  counts: ReadonlyMap<string, number>;
  most: number;
  tone: string;
  unit: string;
}) {
  return (
    <>
      <div className="heat-row">{level.label}</div>
      {kinds.map((kind) => {
        const count = counts.get(`${level.key}|${kind}`) ?? 0;
        const words = `${level.label}, ${kind}: ${formatCount(count)} ${unit}`;
        const style: CSSProperties | undefined =
          count > 0
            ? { background: `color-mix(in srgb, ${tone} ${Math.round(14 + (count / most) * 46)}%, var(--color-surface))` }
            : undefined;
        return (
          <div key={kind} className={`heat-cell${count === 0 ? " heat-zero" : ""}`} style={style} tabIndex={0} aria-label={words} title={words}>
            {count}
          </div>
        );
      })}
    </>
  );
}

export interface VBar {
  key: string;
  short: string;
  label: string;
  count: number;
}

/**
 * Columns with a light grid and a scale at the left, as in the second look. A label is written under every n-th column
 * so they never overlap; each column carries its own words (tooltip and screen reader) and can be walked with Tab.
 */
export function VBars({ bars, unit, aria }: { bars: readonly VBar[]; unit: string; aria: string }) {
  const width = 640;
  const height = 240;
  const top = 10;
  const bottom = 26;
  const peak = niceMax(Math.max(0, ...bars.map((bar) => bar.count)));
  const ticks = [0, 1, 2, 3, 4].map((step) => (peak / 4) * step);
  const left = 14 + String(formatCount(peak)).length * 7;
  const slot = (width - 6 - left) / Math.max(1, bars.length);
  const bandWidth = Math.min(slot * 0.72, 28);
  const every = Math.max(1, Math.ceil(48 / slot));
  const y = (value: number) => height - bottom - (value / peak) * (height - bottom - top);
  return (
    <svg className="vbars" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={aria}>
      {ticks.map((tick) => (
        <g key={tick}>
          <line x1={left} x2={width - 6} y1={y(tick)} y2={y(tick)} className={tick === 0 ? "vbars-axis" : "vbars-grid"} />
          <text x={left - 6} y={y(tick) + 4} textAnchor="end" className="vbars-text">
            {formatCount(Math.round(tick * 10) / 10)}
          </text>
        </g>
      ))}
      {bars.map((bar, index) => {
        const centre = left + slot * index + slot / 2;
        const words = `${bar.label}: ${formatCount(bar.count)} ${unit}`;
        return (
          <g key={bar.key} tabIndex={0} role="img" aria-label={words} className="vbars-hit">
            <title>{words}</title>
            {bar.count > 0 ? (
              <rect x={centre - bandWidth / 2} y={y(bar.count)} width={bandWidth} height={y(0) - y(bar.count)} rx={3} className="vbars-bar" />
            ) : null}
            {(bars.length - 1 - index) % every === 0 ? (
              <text x={centre} y={height - 8} textAnchor="middle" className="vbars-text">
                {bar.short}
              </text>
            ) : null}
            <rect x={centre - slot / 2} y={top} width={slot} height={height - bottom - top} fill="transparent" />
          </g>
        );
      })}
    </svg>
  );
}

export interface HRow {
  key: string;
  label: string;
  count: number;
  shown?: string;
  /** A paint, or undefined for the first series. */
  paint?: string;
  words: string;
}

/** Rows of a name, a flat bar and the value, as in the second look; a row is a button when it can be pressed. */
export function HRows({
  rows,
  onSelect,
  selected,
}: {
  rows: readonly HRow[];
  onSelect?: (key: string) => void;
  selected?: string;
}) {
  const most = Math.max(1, ...rows.map((row) => row.count));
  return (
    <div className="hrows">
      {rows.map((row) => {
        const inside = (
          <>
            <span className="hrow-label">{row.label}</span>
            <span className="hrow-track">
              <span
                className="hrow-fill"
                style={{ width: `${(row.count / most) * 100}%`, background: row.paint ?? creamSeries(0) }}
              />
            </span>
            <span className="hrow-value">{row.shown ?? formatCount(row.count)}</span>
          </>
        );
        return onSelect ? (
          <button
            key={row.key}
            type="button"
            className={`hrow${selected === row.key ? " hrow-selected" : ""}`}
            aria-label={row.words}
            title={row.words}
            onClick={() => onSelect(row.key)}
          >
            {inside}
          </button>
        ) : (
          <div key={row.key} className="hrow" tabIndex={0} aria-label={row.words} title={row.words}>
            {inside}
          </div>
        );
      })}
    </div>
  );
}
