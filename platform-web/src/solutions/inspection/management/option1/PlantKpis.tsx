"use client";

import { monthAbbreviation, scoreText, type BoardKpis } from "./model";

/**
 * Four numbers over the plants shown, all of equal size in one line: the average days a plant head takes to send an
 * Action plan, the share sent on time, how many plants reported this month, and the longest run of months passed. A number
 * with nothing to be made of is a dash, never a zero. Every figure is the server's.
 */
export function PlantKpis({ kpis }: { kpis: BoardKpis }) {
  const tiles: { id: string; label: string; value: string; note?: string }[] = [
    {
      id: "avg-days",
      label: "Average days to submit plan",
      value: kpis.avgDaysToSubmit === undefined || kpis.avgDaysToSubmit === null ? "–" : scoreText(kpis.avgDaysToSubmit),
      note: kpis.avgDaysOf > 0 ? `${kpis.avgDaysOf} plan${kpis.avgDaysOf === 1 ? "" : "s"}` : undefined,
    },
    {
      id: "on-time",
      label: "Plans on time",
      value: kpis.onTimePercent === undefined || kpis.onTimePercent === null ? "–" : `${kpis.onTimePercent}%`,
      note: kpis.avgDaysOf > 0 ? `${kpis.onTimeCount} of ${kpis.avgDaysOf}` : undefined,
    },
    {
      id: "reporting",
      label: kpis.reportingMonth ? `Plants reported, ${monthAbbreviation(kpis.reportingMonth)}` : "Plants reported",
      value: `${kpis.reportingSent} of ${kpis.reportingOf}`,
    },
    {
      id: "streak",
      label: "Months passed in a row",
      value: kpis.streakMonths === undefined || kpis.streakMonths === null ? "–" : String(kpis.streakMonths),
      note: kpis.streakMonths && kpis.streakPlant ? kpis.streakPlant : undefined,
    },
  ];
  return (
    <ul className="pb-kpis" data-testid="board-kpis" aria-label="Key numbers">
      {tiles.map((tile) => (
        <li key={tile.id} className="pb-kpi" data-testid={`kpi-${tile.id}`}>
          <span className="pb-kpi-label">{tile.label}</span>
          <b className="pb-kpi-value">{tile.value}</b>
          <span className="pb-kpi-note">{tile.note ?? "\u00a0"}</span>
        </li>
      ))}
    </ul>
  );
}
