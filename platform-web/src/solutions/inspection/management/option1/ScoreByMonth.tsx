"use client";

import { useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { ChartCard } from "../../home/ChartCard";
import {
  bridgePath,
  bridges,
  columnLabel,
  fitScale,
  linePath,
  monthAbbreviation,
  nearestMonth,
  runsOfScores,
  scoreText,
  seriesVar,
  shortName,
  spreadLabels,
  type BoardPlant,
} from "./model";

const WIDTH = 720;
const HEIGHT = 292;
const LEFT = 40;
const RIGHT = 72;
const TOP = 14;
const PLOT = 220;
const STRIP = 28;
const EDGE = 8;

/**
 * Score by month: one line with a dot for each plant over the months that have ended, on a scale that fits the scores. A
 * month with no score leaves the line open, a cross in the strip under the chart says so, and a thin dotted piece joins
 * the two points either side of the gap. The last month's score is written at the end of each line. There is no limit line.
 * A month can be read by pointer and by keyboard (the arrow keys, Home and End), and the card offers every number as a table.
 */
export function ScoreByMonth({ months, plants, loading }: { months: readonly string[]; plants: readonly BoardPlant[]; loading: boolean }) {
  const [chosen, setChosen] = useState<number | undefined>(undefined);
  const area = useRef<SVGSVGElement | null>(null);
  const count = months.length;
  const selected = Math.min(chosen ?? count - 1, count - 1);
  const anyScore = plants.some((plant) => plant.points.some((score) => score !== null && score !== undefined));
  const scale = fitScale(plants.flatMap((plant) => plant.points));
  const inner = WIDTH - LEFT - RIGHT - 2 * EDGE;
  const x = (index: number, plant: number) =>
    LEFT + EDGE + (count <= 1 ? inner / 2 : (index * inner) / (count - 1)) + (plant - (plants.length - 1) / 2) * 3;
  const y = (score: number) => TOP + ((scale.high - score) / (scale.high - scale.low)) * PLOT;
  const stripY = TOP + PLOT + 6 + (STRIP - 6) / 2;

  const labelYs = spreadLabels(
    plants.map((plant) => {
      const last = plant.points[count - 1];
      return last === null || last === undefined ? Number.POSITIVE_INFINITY : y(last) + 4;
    }),
    13,
  );

  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    const keys: Record<string, number> = { ArrowLeft: selected - 1, ArrowRight: selected + 1, Home: 0, End: count - 1 };
    const next = keys[event.key];
    if (next !== undefined) {
      event.preventDefault();
      setChosen(Math.max(0, Math.min(count - 1, next)));
    }
  };
  const point = (event: PointerEvent<SVGRectElement>) => {
    const box = area.current?.getBoundingClientRect();
    if (!box || box.width === 0) {
      return;
    }
    const along = ((event.clientX - box.left) * (WIDTH / box.width) - (LEFT + EDGE)) / inner;
    setChosen(nearestMonth(along, count));
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
        aria-label="Score of each plant by month. Use the left and right arrow keys to read a month."
        onKeyDown={move}
        data-testid="board-chart-area"
      >
        <svg ref={area} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="pb-chart-svg" role="img" aria-label="Line chart of the monthly score of each plant, with months without a score marked under the chart">
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
          <rect
            x={x(selected, (plants.length - 1) / 2) - 14}
            y={TOP}
            width={28}
            height={PLOT + STRIP}
            rx={4}
            className="pb-selected"
            data-testid="board-selected"
          />
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
                    <circle key={index} cx={x(index, k)} cy={y(score)} r={4.5} fill={colour} className="pb-point" />
                  ),
                )}
                {plant.points[count - 1] !== null && plant.points[count - 1] !== undefined ? (
                  <text x={x(count - 1, k) + 9} y={labelYs[k]} className="pb-end" style={{ fill: colour }}>
                    {`${shortName(plant.label)} ${scoreText(plant.points[count - 1])}`}
                  </text>
                ) : null}
              </g>
            );
          })}
          <rect x={0} y={0} width={WIDTH} height={HEIGHT} fill="transparent" onPointerMove={point} onPointerDown={point} />
        </svg>
      </div>
      <div className="pb-readout" aria-live="polite" data-testid="board-readout">
        <span>
          <b>{monthAbbreviation(months[selected] ?? "")}</b>
        </span>
        {plants.map((plant) => {
          const score = plant.points[selected];
          return (
            <span key={plant.plant}>
              <span className="pb-dot" style={{ background: seriesVar(plant.colour) }} aria-hidden="true" /> {plant.label}{" "}
              {score === null || score === undefined ? <span className="pb-muted">No score</span> : <b>{scoreText(score)}</b>}
            </span>
          );
        })}
      </div>
    </ChartCard>
  );
}
