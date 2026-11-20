"use client";

import {
  changeText,
  linePath,
  runsOfScores,
  scoreText,
  seriesVar,
  type BoardPlant,
} from "./model";

const SPARK_WIDTH = 110;
const SPARK_HEIGHT = 36;
const SPARK_PAD = 4;

/** A small line of a plant's months: runs of months with a score, a gap left open, the last month a larger dot. */
function Sparkline({ plant }: { plant: BoardPlant }) {
  const points = plant.points;
  const values = points.filter((score): score is number => score !== null && score !== undefined);
  if (values.length === 0) {
    return <svg className="pb-spark" width={SPARK_WIDTH} height={SPARK_HEIGHT} aria-hidden="true" />;
  }
  const lowest = Math.min(...values);
  const highest = Math.max(...values);
  const span = highest - lowest || 1;
  const x = (index: number) =>
    SPARK_PAD + (points.length <= 1 ? (SPARK_WIDTH - 2 * SPARK_PAD) / 2 : (index * (SPARK_WIDTH - 2 * SPARK_PAD)) / (points.length - 1));
  const y = (score: number) =>
    highest === lowest ? SPARK_HEIGHT / 2 : SPARK_HEIGHT - SPARK_PAD - ((score - lowest) / span) * (SPARK_HEIGHT - 2 * SPARK_PAD);
  const colour = seriesVar(plant.colour);
  return (
    <svg className="pb-spark" width={SPARK_WIDTH} height={SPARK_HEIGHT} aria-hidden="true">
      {runsOfScores(points).map((run) =>
        run.length > 1 ? (
          <path
            key={run[0]}
            d={linePath(run.map((index) => ({ x: x(index), y: y(points[index] as number) })))}
            fill="none"
            stroke={colour}
            strokeWidth={1.75}
            strokeLinejoin="round"
          />
        ) : null,
      )}
      {points.map((score, index) =>
        score === null || score === undefined ? null : (
          <circle key={index} cx={x(index)} cy={y(score)} r={index === points.length - 1 ? 3 : 1.6} fill={colour} />
        ),
      )}
    </svg>
  );
}

/** One tile for each plant: the latest score, how it moved, and four facts. Everything is the server's number. */
export function PlantTiles({ plants }: { plants: readonly BoardPlant[] }) {
  return (
    <ul className="pb-tiles" data-testid="board-tiles" aria-label="Latest score of each plant">
      {plants.map((plant) => {
        const change = changeText(plant);
        return (
          <li key={plant.plant} className="pb-tile" data-testid={`tile-${plant.plant}`}>
            <div className="pb-tile-name">
              <span className="pb-dot" style={{ background: seriesVar(plant.colour) }} aria-hidden="true" />
              {plant.label}
            </div>
            <div className="pb-tile-score">
              <div className="pb-big" data-testid={`score-${plant.plant}`}>
                {scoreText(plant.latestScore)}
                <small> / 100</small>
              </div>
              <Sparkline plant={plant} />
            </div>
            {change ? (
              <div className={`pb-delta pb-delta-${change.arrow}`} data-testid={`change-${plant.plant}`}>
                {change.arrow === "up" ? "▲ " : change.arrow === "down" ? "▼ " : ""}
                {change.text}
              </div>
            ) : (
              <div className="pb-delta" aria-hidden="true">
                &nbsp;
              </div>
            )}
            <dl className="pb-facts">
              <div>
                <dt>Scores submitted</dt>
                <dd>{`${plant.scoresSubmitted} of ${plant.scoresOf}`}</dd>
              </div>
              <div>
                <dt>Action plans sent</dt>
                <dd>{plant.plansSent}</dd>
              </div>
              <div>
                <dt>Submitted by plant head</dt>
                <dd>{`${plant.plansSubmitted} of ${plant.plansSent}`}</dd>
              </div>
              <div>
                <dt>Overdue</dt>
                <dd className={plant.overdue > 0 ? "pb-bad" : undefined} data-testid={`overdue-${plant.plant}`}>
                  {plant.overdue}
                </dd>
              </div>
            </dl>
          </li>
        );
      })}
    </ul>
  );
}
