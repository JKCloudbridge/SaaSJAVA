"use client";

import Link from "next/link";
import { useState } from "react";
import { fillInsHref } from "../../fillIns";
import {
  columnLabel,
  dayText,
  filterPlans,
  monthWithYear,
  scoreText,
  seriesVar,
  statusClass,
  type BoardCell,
  type PlanFilter,
  type PlantBoard,
} from "./model";

const CHIPS: readonly { key: PlanFilter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "overdue", label: "Overdue" },
  { key: "submitted", label: "Submitted" },
];

/** The cell of a plant and a month: the score and one word. A cell about a fill-in opens it for a person who may read it. */
function Cell({ cell, application }: { cell: BoardCell; application: string }) {
  const inside = (
    <>
      <b>{cell.status === "NO_SCORE" ? "×" : cell.score === null || cell.score === undefined ? "–" : scoreText(cell.score)}</b>
      <small>{cell.word}</small>
    </>
  );
  const className = `pb-cell pb-cell-${statusClass(cell.status)}`;
  return cell.fillId && cell.score !== null && cell.score !== undefined ? (
    <Link href={fillInsHref(cell.fillId, application)} className={className} data-status={cell.status} data-testid={`cell-${cell.month}`}>
      {inside}
    </Link>
  ) : (
    <div className={className} data-status={cell.status} data-testid={`cell-${cell.month}`}>
      {inside}
    </div>
  );
}


/**
 * The Action plan tracker: three numbers, a grid of the plants by month with one word under each score, and a detail
 * table of every plan with its days and status. The key under the grid is the only explanation. Every status and word is
 * the server's; overdue and late plans are shown here and nothing acts on them.
 */
export function ActionPlanTracker({ board, application }: { board: PlantBoard; application: string }) {
  const [filter, setFilter] = useState<PlanFilter>("all");
  const totals = board.totals;
  const grid = board.grid ?? [];
  const plans = filterPlans(board.plans ?? [], filter);
  const columns = [...board.months, ...(board.currentMonth ? [board.currentMonth] : [])];
  const limit = board.limit;
  return (
    <section className="pb-card" aria-labelledby="pb-tracker-title" data-testid="board-tracker">
      <div className="pb-tracker-head">
      <h2 id="pb-tracker-title" className="pb-title">
        Action plan tracker
      </h2>
      {totals ? (
        <div className="pb-stats">
          <div className="pb-stat" data-testid="stat-sent">
            <b>{totals.plansSent}</b>
            <span>Plans sent</span>
          </div>
          <div className="pb-stat" data-testid="stat-submitted">
            <b>{totals.plansSubmitted}</b>
            <span>Submitted by plant head</span>
          </div>
          <div className={`pb-stat${totals.overdue > 0 ? " pb-stat-bad" : ""}`} data-testid="stat-overdue">
            <b>{totals.overdue}</b>
            <span>Overdue</span>
          </div>
        </div>
      ) : null}
      </div>

      <h3 className="visually-hidden">By month</h3>
      {grid.length === 0 || columns.length === 0 ? (
        <p className="pb-empty" role="status">
          No data
        </p>
      ) : (
        <div className="pb-scroll">
          <table className="pb-grid-table" data-testid="board-grid">
            <thead>
              <tr>
                <th className="pb-who" scope="col">
                  <span className="visually-hidden">Plant</span>
                </th>
                {columns.map((month, index) => (
                  <th key={month} scope="col">
                    {columnLabel(columns, index)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {grid.map((row) => (
                <tr key={row.plant}>
                  <th className="pb-who" scope="row">
                    <span className="pb-dot" style={{ background: seriesVar(row.colour) }} aria-hidden="true" />
                    {row.label}
                  </th>
                  {row.cells.map((cell) => (
                    <td key={cell.month}>
                      <Cell cell={cell} application={application} />
                    </td>
                  ))}
                  {row.current ? (
                    <td>
                      <Cell cell={row.current} application={application} />
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="pb-key" data-testid="board-key">
        <span className="pb-cell pb-cell-ok">{limit === undefined || limit === null ? "Passed" : `Score ${limit} or above, passed`}</span>
        <span className="pb-cell pb-cell-done">Plan submitted on time</span>
        <span className="pb-cell pb-cell-late">Plan submitted late</span>
        <span className="pb-cell pb-cell-wait">Plan sent, awaiting plant head</span>
        <span className="pb-cell pb-cell-over">Plan overdue</span>
        <span className="pb-cell pb-cell-none">Score not submitted</span>
      </div>

      <h3 className="visually-hidden">Plan details</h3>
      <div className="pb-chips pb-chips-spaced" role="group" aria-label="Filter action plans">
        {CHIPS.map((chip) => (
          <button key={chip.key} type="button" aria-pressed={filter === chip.key} onClick={() => setFilter(chip.key)}>
            {chip.label}
          </button>
        ))}
      </div>
      {plans.length === 0 ? (
        <p className="pb-empty" role="status">
          No data
        </p>
      ) : (
        <div className="pb-scroll">
          <table className="pb-plans" data-testid="board-plans">
            <thead>
              <tr>
                <th scope="col">Plant</th>
                <th scope="col">Score month</th>
                <th scope="col" className="pb-num">
                  Score
                </th>
                <th scope="col">Plan sent</th>
                <th scope="col">Due</th>
                <th scope="col">Plant head submitted</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {plans.map((plan) => (
                <tr key={plan.fillId} data-status={plan.status}>
                  <td>
                    <span className="pb-dot" style={{ background: seriesVar(plan.colour) }} aria-hidden="true" />
                    {plan.label}
                  </td>
                  <td>
                    <Link href={fillInsHref(plan.planId ?? plan.fillId, application)}>{monthWithYear(plan.month)}</Link>
                  </td>
                  <td className="pb-num pb-low">{scoreText(plan.score)}</td>
                  <td>{dayText(plan.sentOn, board.today)}</td>
                  <td>{dayText(plan.dueOn, board.today)}</td>
                  <td>{plan.submittedOn ? dayText(plan.submittedOn, board.today) : "Not yet"}</td>
                  <td>
                    <span className={`pb-pill pb-pill-${statusClass(plan.status)}`}>{plan.statusText}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
