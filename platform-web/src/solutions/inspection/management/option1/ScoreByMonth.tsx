"use client";

import { useState, type KeyboardEvent } from "react";
import { ChartCard } from "../../home/ChartCard";
import {
  bridgePath,
  bridges,
  columnLabel,
  fitScale,
  linePath,
  monthAbbreviation,
  runsOfScores,
  scoreText,
  seriesVar,
  type BoardPlant,
} from "./model";

const WIDTH = 720;
const HEIGHT = 292;
const LEFT = 40;
const RIGHT = 16;
const TOP = 14;
const PLOT = 220;
const STRIP = 28;
const EDGE = 8;

/**
 * Score by month: one line with a dot for each plant over the months that have ended, on a scale that fits the scores. A
 * month with no score leaves the line open, a cross in the strip under the chart says so, and a thin dotted piece joins
 * the two points either side of the gap. There is no limit line and no label at the end of a line.
 * Hovering a dot (or the arrow keys) opens a tip above it, or below it near the top of the chart, with the scores of that month, and leaving it closes the tip; the card offers every number as a table.
 */
export function ScoreByMonth({ months, plants, loading }: { months: readonly string[]; plants: readonly BoardPlant[]; loading: boolean }) {
  const [tip, setTip] = useState<{ index: number; px: number; py: number } | undefined>(undefined);
  const count = months.length;
  const selected = tip !== undefined && tip.index < count ? tip.index : undefined;
  const anyScore = plants.some((plant) => plant.points.some((score) => score !== null && score !== undefined));
  const scale = fitScale(plants.flatMap((plant) => plant.points));
  const inner = WIDTH - LEFT - RIGHT - 2 * EDGE;
  const x = (index: number, plant: number) =>
    LEFT + EDGE + (count <= 1 ? inner / 2 : (index * inner) / (count - 1)) + (plant - (plants.length - 1) / 2) * 3;
  const y = (score: number) => TOP + ((scale.high - score) / (scale.high - scale.low)) * PLOT;
  const stripY = TOP + PLOT + 6 + (STRIP - 6) / 2;

  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      setTip(undefined);
      return;
    }
    const from = selected ?? count - 1;
    const keys: Record<string, number> = { ArrowLeft: from - 1, ArrowRight: from + 1, Home: 0, End: count - 1 };
    const next = keys[event.key];
    if (next !== undefined) {
      event.preventDefault();
      const month = Math.max(0, Math.min(count - 1, next));
      setTip({ index: month, px: x(month, (plants.length - 1) / 2), py: TOP });
    }
  };

  return (
    <ChartCard
      testId="board-chart"
      title="Score by month"
      loading={loading}
      empty={count === 0 || !anyScore ? "No data" : undefined}
      table={{
        caption: "Score of each plant in each month",
        columns: ["Plant", ...months.map((_, index) => columnLabel(months, index))],
        rows: plants.map((plant) => [plant.label, ...plant.points.map((score) => (score === null || score === undefined ? "No score" : scoreText(score)))]),
      }}
      wide
    >
      <div className="pb-legend">
        {plants.map((plant) => (
          <span key={plant.plant}>
            <span className="pb-dot" style={{ background: seriesVar(plant.colour) }} aria-hidden="true" /> {plant.label}
          </span>
        ))}
        <span>
          <span className="pb-cross-key" aria-hidden="true">
            ×
          </span>{" "}
          Score not submitted
        </span>
      </div>
      <div
        className="pb-chart"
        tabIndex={0}
        role="group"
        aria-label="Score of each plant by month. Click a month, or use the left and right arrow keys, to read it. Escape closes it."
        onKeyDown={move}
        data-testid="board-chart-area"
      >
        <div className="pb-chart-inner">
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="pb-chart-svg" role="img" aria-label="Line chart of the monthly score of each plant, with months without a score marked under the chart">
          {scale.ticks.map((tick) => (
            <g key={tick}>
              <line x1={LEFT} x2={WIDTH - RIGHT} y1={y(tick)} y2={y(tick)} className="pb-grid" />
              <text x={LEFT - 6} y={y(tick) + 4} textAnchor="end" className="pb-axis">
                {tick}
              </text>
            </g>
          ))}
          <rect x={LEFT} y={TOP + PLOT + 6} width={WIDTH - LEFT - RIGHT} height={STRIP - 6} rx={4} className="pb-strip" />
          {months.map((month, index) =>
            count > 12 && index % 2 === 1 ? null : (
              <text key={month} x={x(index, (plants.length - 1) / 2)} y={HEIGHT - 6} textAnchor="middle" className="pb-axis">
                {count > 12 ? monthAbbreviation(month) : columnLabel(months, index)}
              </text>
            ),
          )}
          {plants.map((plant, k) => {
            const colour = seriesVar(plant.colour);
            const runs = runsOfScores(plant.points);
            const at = (index: number) => ({ x: x(index, k), y: y(plant.points[index] as number) });
            return (
              <g key={plant.plant} data-testid={`line-${plant.plant}`}>
                {bridges(runs).map(([from, to]) => (
                  <path key={`b${from}`} d={bridgePath(at(from), at(to))} className="pb-bridge" stroke={colour} data-testid={`bridge-${plant.plant}`} />
                ))}
                {runs.map((run) =>
                  run.length > 1 ? <path key={run[0]} d={linePath(run.map(at))} className="pb-line" stroke={colour} /> : null,
                )}
                {plant.points.map((score, index) =>
                  score === null || score === undefined ? (
                    <path
                      key={index}
                      d={`M${x(index, k) - 3.5},${stripY - 3.5} L${x(index, k) + 3.5},${stripY + 3.5} M${x(index, k) - 3.5},${stripY + 3.5} L${x(index, k) + 3.5},${stripY - 3.5}`}
                      className="pb-cross"
                      stroke={colour}
                      data-testid={`cross-${plant.plant}`}
                    />
                  ) : (
                    <g
                      key={index}
                      onPointerEnter={() => setTip({ index, px: x(index, k), py: y(score) })}
                      onPointerLeave={() => setTip(undefined)}
                    >
                      <circle cx={x(index, k)} cy={y(score)} r={4.5} fill={colour} className="pb-point" />
                      <circle cx={x(index, k)} cy={y(score)} r={10} fill="transparent" className="pb-hit" />
                    </g>
                  ),
                )}
              </g>
            );
          })}
        </svg>
        {selected === undefined ? null : (
          <div
            className={`pb-tip${(tip?.py ?? 0) < HEIGHT * 0.5 ? " pb-tip-below" : ""}`}
            role="status"
            style={{ left: `${Math.min(88, Math.max(12, ((tip?.px ?? 0) / WIDTH) * 100))}%`, top: `${((tip?.py ?? 0) / HEIGHT) * 100}%` }}
            data-testid="board-tip"
          >
            <b>{monthAbbreviation(months[selected] ?? "")}</b>
            {plants.map((plant) => {
              const score = plant.points[selected];
              return (
                <span key={plant.plant}>
                  <span className="pb-dot" style={{ background: seriesVar(plant.colour) }} aria-hidden="true" />{" "}
                  {score === null || score === undefined ? `${plant.label} - Missed` : `${plant.label} ${scoreText(score)}`}
                </span>
              );
            })}
          </div>
        )}
        </div>
      </div>
    </ChartCard>
  );
}
