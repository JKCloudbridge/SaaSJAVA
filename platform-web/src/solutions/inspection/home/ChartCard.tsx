"use client";

import { useId, useState, type ReactNode } from "react";
import { Icon, type IconName } from "@/components/ui/Icon";
import { PATTERNS, seriesNumber } from "./model";

/** One way to look at a graph, offered as a button while it is not the one shown. */
export interface ChartViewChoice {
  key: string;
  label: string;
  icon: IconName;
}

/**
 * The views of a graph that has more than one (ADR-0266): the key shown, every choice and what a click does. The key
 * "table" shows the table of the card; any other key shows the graph, which the caller draws for that key.
 */
export interface ChartViews {
  current: string;
  choices: readonly ChartViewChoice[];
  onChange: (key: string) => void;
}

/** The numbers of a graph as a table: what a screen reader reads and what a person copies. */
export interface ChartTable {
  caption: string;
  columns: readonly string[];
  rows: readonly (readonly (string | number)[])[];
}

/**
 * The frame every graph of the home page sits in (FA9, ADR-0203): a title, a short line of what it adds up to, a
 * "View as table" switch that shows the very numbers of the graph (so the table can never differ from the picture), an
 * honest sentence when there is nothing to draw, and a placeholder of the same size while the page loads. The graph
 * itself is the child.
 */
export function ChartCard({
  title,
  note,
  table,
  empty,
  loading = false,
  wide = false,
  views,
  testId,
  children,
}: {
  title: string;
  note?: string;
  table: ChartTable;
  /** A sentence to show instead of the graph, or undefined when there is something to draw. */
  empty?: string;
  loading?: boolean;
  wide?: boolean;
  /** More than one way to look at the numbers; without it the card has its one "View as table" switch. */
  views?: ChartViews;
  testId: string;
  children: ReactNode;
}) {
  const [asTable, setAsTable] = useState(false);
  const headingId = useId();
  const showTable = (views ? views.current === "table" : asTable) && empty === undefined && !loading;
  return (
    <article
      className={`chart-card${wide ? " chart-card-wide" : ""}`}
      aria-labelledby={headingId}
      aria-busy={loading}
      data-testid={testId}
    >
      <header className="chart-head">
        <div>
          <h3 id={headingId} className="chart-title">
            {title}
          </h3>
          {note ? <p className="chart-note">{note}</p> : null}
        </div>
        {empty === undefined && !loading && views ? (
          <div className="chart-switches">
            {views.choices
              .filter((choice) => choice.key !== views.current)
              .map((choice) => (
                <button key={choice.key} type="button" className="chart-switch" onClick={() => views.onChange(choice.key)}>
                  <Icon name={choice.icon} className="chart-switch-icon" />
                  {choice.label}
                </button>
              ))}
          </div>
        ) : null}
        {empty === undefined && !loading && !views ? (
          <button
            type="button"
            className="chart-switch"
            aria-pressed={asTable}
            onClick={() => setAsTable((value) => !value)}
          >
            <Icon name="table" className="chart-switch-icon" />
            View as table
          </button>
        ) : null}
      </header>
      {loading ? (
        <div className="chart-loading" aria-hidden="true">
          <span className="skeleton-line chart-loading-block" />
        </div>
      ) : empty !== undefined ? (
        <p className="chart-empty" role="status">
          {empty}
        </p>
      ) : showTable ? (
        <div className="chart-table-wrap">
          <table className="table chart-table">
            <caption className="visually-hidden">{table.caption}</caption>
            <thead>
              <tr>
                {table.columns.map((column) => (
                  <th key={column} scope="col">
                    {column}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {table.rows.map((row, index) => (
                <tr key={index}>
                  {row.map((cell, at) => (at === 0 ? (
                    <th key={at} scope="row">
                      {cell}
                    </th>
                  ) : (
                    <td key={at}>{cell}</td>
                  )))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        children
      )}
    </article>
  );
}

/**
 * The patterns that tell series apart where colour alone would not (WCAG 1.4.1): one definition for a chart, referenced
 * by id. The first series is plain; the others are stripes, dots, a cross-hatch, waves and a grid, each drawn in the
 * series colour over a pale ground of the same colour.
 */
export function ChartPatterns({ prefix }: { prefix: string }) {
  return (
    <defs>
      {PATTERNS.map((pattern, index) => {
        const colour = `var(--series-${seriesNumber(index)})`;
        const ground = `color-mix(in srgb, ${colour} 28%, var(--color-surface))`;
        return (
          <pattern key={pattern} id={`${prefix}-${index}`} width="8" height="8" patternUnits="userSpaceOnUse">
            <rect width="8" height="8" fill={index === 0 ? colour : ground} />
            {pattern === "stripes" ? <path d="M-2 2 L2 -2 M0 8 L8 0 M6 10 L10 6" stroke={colour} strokeWidth="2" /> : null}
            {pattern === "dots" ? <circle cx="4" cy="4" r="1.8" fill={colour} /> : null}
            {pattern === "cross" ? <path d="M0 0 L8 8 M8 0 L0 8" stroke={colour} strokeWidth="1.4" /> : null}
            {pattern === "wave" ? <path d="M0 4 Q2 0 4 4 T8 4" fill="none" stroke={colour} strokeWidth="1.6" /> : null}
            {pattern === "grid" ? <path d="M4 0 V8 M0 4 H8" stroke={colour} strokeWidth="1.4" /> : null}
          </pattern>
        );
      })}
    </defs>
  );
}

/** A small square in the paint of series n, for a legend. */
export function Swatch({ index, solid = false }: { index: number; solid?: boolean }) {
  const at = index % PATTERNS.length;
  const id = `sw${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  if (solid) {
    return (
      <svg className="chart-swatch" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
        <rect width="12" height="12" rx="2" fill={`var(--series-${at + 1})`} />
      </svg>
    );
  }
  return (
    <svg className="chart-swatch" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
      <ChartPatterns prefix={id} />
      <rect width="12" height="12" rx="2" fill={`url(#${id}-${at})`} />
    </svg>
  );
}
