"use client";

import Link from "next/link";
import { fillInsHref } from "../../fillIns";
import { dayText, monthWithYear, scoreText, seriesVar, type BoardPending, type PlantBoard } from "./model";

/**
 * Pending with reviewer: the fill-ins and Action plans that were sent and wait for a decision, longest waiting first. No
 * reviewer is named here; the fill-in opens for the person who may decide it. Everything is the server's.
 */
export function PendingReview({ board, application }: { board: PlantBoard; application: string }) {
  const rows: readonly BoardPending[] = board.pending ?? [];
  return (
    <section className="pb-card" aria-labelledby="pb-pending-title" data-testid="board-pending">
      <h2 id="pb-pending-title" className="pb-title">
        Pending with reviewer
      </h2>
      {rows.length === 0 ? (
        <p className="pb-empty" role="status">
          No data
        </p>
      ) : (
        <div className="pb-scroll">
          <table className="pb-plans">
            <thead>
              <tr>
                <th scope="col">Plant</th>
                <th scope="col">Month</th>
                <th scope="col">Fill-in</th>
                <th scope="col" className="pb-num">
                  Score
                </th>
                <th scope="col">Sent</th>
                <th scope="col" className="pb-num">
                  Days waiting
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.fillId}>
                  <td>
                    <span className="pb-dot" style={{ background: seriesVar(row.colour) }} aria-hidden="true" />
                    {row.label}
                  </td>
                  <td>{monthWithYear(row.month)}</td>
                  <td>
                    <Link href={fillInsHref(row.fillId, application)}>{row.name || "Fill-in"}</Link>
                    {row.actionPlan ? " (Action plan)" : ""}
                  </td>
                  <td className="pb-num">{scoreText(row.score)}</td>
                  <td>{dayText(row.sentOn, board.today)}</td>
                  <td className="pb-num">{row.daysWaiting ?? "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
