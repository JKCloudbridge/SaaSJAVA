"use client";

import { useId, useMemo } from "react";
import { Icon } from "@/components/ui/Icon";
import { clockTime, isFiltered, PERIODS, type HomeFilters, type HomeSummary } from "./model";

type Locations = HomeSummary["filters"]["locations"];

/**
 * The one bar of filters over the home page (FA9, ADR-0203): the period, a location, a department and a Clear button,
 * and Refresh with the time of the answer. What the bar offers is what the server said the organization keeps; the
 * browser holds no list of its own. A choice goes to the address (the page does that), so a view can be shared and the
 * back button undoes it.
 *
 * <p>A department belongs to a location in the organization's lists, so the department list follows the location; with
 * no location chosen it offers every department once.
 */
export function FilterBar({
  filters,
  locations,
  onChange,
  onRefresh,
  refreshing,
  updatedAt,
}: {
  filters: HomeFilters;
  /** The locations and departments the server offers; empty while the first answer is on its way. */
  locations: Locations;
  onChange: (next: HomeFilters) => void;
  onRefresh: () => void;
  refreshing: boolean;
  /** The moment of the answer on screen, or undefined before there is one. */
  updatedAt?: string;
}) {
  const locationId = useId();
  const departmentId = useId();
  const departments = useMemo(() => {
    if (filters.location !== "") {
      return locations.find((place) => place.code === filters.location)?.departments ?? [];
    }
    const seen = new Map<string, string>();
    locations.forEach((place) => place.departments.forEach((one) => seen.set(one.code, one.label)));
    return [...seen].map(([code, label]) => ({ code, label }));
  }, [locations, filters.location]);

  return (
    <div className="dash-filters" role="search" aria-label="Filter the home page" data-testid="home-filters">
      <div className="dash-periods" role="radiogroup" aria-label="Period">
        {PERIODS.map((period) => (
          <button
            key={period.key}
            type="button"
            role="radio"
            aria-checked={filters.period === period.key}
            className="dash-period"
            onClick={() => onChange({ ...filters, period: period.key })}
          >
            {period.label}
          </button>
        ))}
      </div>

      <label className="dash-select" htmlFor={locationId}>
        <span className="dash-select-label">Location</span>
        <select
          id={locationId}
          value={filters.location}
          onChange={(event) => {
            const location = event.target.value;
            const stillThere =
              location === "" ||
              (locations.find((place) => place.code === location)?.departments ?? []).some(
                (one) => one.code === filters.department,
              );
            onChange({ ...filters, location, department: stillThere ? filters.department : "" });
          }}
        >
          <option value="">All locations</option>
          {locations.map((place) => (
            <option key={place.code} value={place.code}>
              {place.label}
            </option>
          ))}
        </select>
      </label>

      <label className="dash-select" htmlFor={departmentId}>
        <span className="dash-select-label">Department</span>
        <select
          id={departmentId}
          value={filters.department}
          onChange={(event) => onChange({ ...filters, department: event.target.value })}
        >
          <option value="">All departments</option>
          {departments.map((one) => (
            <option key={one.code} value={one.code}>
              {one.label}
            </option>
          ))}
        </select>
      </label>

      {isFiltered(filters) ? (
        <button type="button" className="link-button dash-clear" onClick={() => onChange({ period: "30d", location: "", department: "" })}>
          Clear
        </button>
      ) : null}

      <span className="dash-spacer" />
      <span className="dash-updated" role="status" aria-live="polite">
        {refreshing ? "Updating…" : updatedAt ? `Updated at ${clockTime(updatedAt)}` : ""}
      </span>
      <button type="button" className="dash-refresh" onClick={onRefresh} disabled={refreshing}>
        <Icon name="refresh" className="dash-refresh-icon" />
        Refresh
      </button>
    </div>
  );
}
