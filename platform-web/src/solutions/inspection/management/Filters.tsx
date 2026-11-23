"use client";

import { useId } from "react";
import { Icon } from "@/components/ui/Icon";
import { clockTime } from "../home/model";
import { DEFAULT_SPAN, isFiltered, monthName, NO_FILTERS, SPANS, type ManagementFilters, type Span } from "./model";

/** What the filter bar needs of an answer; both options of the Home page send these parts. */
export interface FilterChoices {
  generatedAt: string;
  monthChoices: string[];
  filters: { plants: { code: string; label: string }[]; forms: { id: string; name: string }[] };
}

export function Filters({
  filters,
  summary,
  onChange,
  onRefresh,
  refreshing,
  defaultSpan = DEFAULT_SPAN,
}: {
  filters: ManagementFilters;
  summary: FilterChoices | undefined;
  onChange: (next: ManagementFilters) => void;
  onRefresh: () => void;
  refreshing: boolean;
  /** The months compared when the address names none (the options differ). */
  defaultSpan?: Span;
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

      {isFiltered(filters, defaultSpan) ? (
        <button type="button" className="link-button dash-clear" onClick={() => onChange({ ...NO_FILTERS, span: defaultSpan })}>
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

