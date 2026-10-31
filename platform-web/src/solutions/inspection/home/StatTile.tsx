"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { Icon, type IconName } from "@/components/ui/Icon";
import { areaPath, linePath, linePoints, niceMax, type Change, type Tone } from "./model";

/** What a tile says; the page decides it from the server's numbers and this only draws it. */
export interface TileView {
  /** A stable name for tests and for the address of the tile's anchor. */
  id: string;
  label: string;
  /** The number, already written ("1,284", "3.5 days", "12%"); undefined when there is nothing to show. */
  value?: string;
  /** What to say when there is no number: "No fill-ins in this period". */
  empty: string;
  /** A short line under the number: the age of the oldest, the period the number covers. */
  hint?: string;
  change?: Change;
  tone?: Tone;
  /** Where the tile leads, or undefined for a tile that only tells. */
  href?: string;
  /** Said under the number when a filter the person chose does not change it. */
  note?: string;
  /** A small line under the number for the days of the period, when the page has them. */
  spark?: readonly number[];
  /** True when the tile is a call for attention (work that waits for someone). */
  attention?: boolean;
  /** A picture drawn beside the label (the compact look); none in the other looks. */
  icon?: IconName;
}

/**
 * One number on top of the home page (FA9, ADR-0203): a large number, a short label and, where a period applies, the
 * change against the period before with an arrow and the words "up" or "down", so colour is never the only signal. A
 * tile with a place to go is one link; a tile that only tells is not drawn as a link at all. Honest when empty: it says
 * why there is no number instead of showing a zero that would mean something else.
 */
export function StatTile({ tile }: { tile: TileView }) {
  const body = (
    <>
      {tile.icon ? (
        <span className="stat-head">
          <span className="stat-icon" aria-hidden="true">
            <Icon name={tile.icon} className="stat-icon-glyph" />
          </span>
          <span className="stat-label">{tile.label}</span>
        </span>
      ) : (
        <span className="stat-label">{tile.label}</span>
      )}
      {tile.value !== undefined ? (
        <span className="stat-value" data-testid={`value-${tile.id}`}>
          {tile.value}
        </span>
      ) : (
        <span className="stat-empty">{tile.empty}</span>
      )}
      {tile.change && tile.change.direction !== "none" ? (
        <span className="stat-change" data-direction={tile.change.direction} data-tone={tile.tone ?? "neutral"}>
          {tile.change.direction === "up" ? <Icon name="arrowUp" className="stat-arrow" /> : null}
          {tile.change.direction === "down" ? <Icon name="arrowDown" className="stat-arrow" /> : null}
          {tile.change.words}
        </span>
      ) : null}
      {tile.hint ? <span className="stat-hint">{tile.hint}</span> : null}
      {tile.note ? <span className="stat-note">{tile.note}</span> : null}
      {tile.spark && tile.spark.length > 1 ? <Spark values={tile.spark} /> : null}
      {tile.href ? <Icon name="arrowRight" className="stat-go" /> : null}
    </>
  );
  const className = `stat-tile${tile.attention ? " stat-tile-attention" : ""}${tile.href ? " stat-tile-link" : ""}`;
  return (
    <li className="stat-item">
      {tile.href ? (
        <Link href={tile.href} className={className} data-testid={`tile-${tile.id}`}>
          {body}
        </Link>
      ) : (
        <div className={className} data-testid={`tile-${tile.id}`}>
          {body}
        </div>
      )}
    </li>
  );
}

/** A tile whose section could not be worked out: it names itself and says so, and the rest of the page goes on. */
export function FailedTile({ label }: { label: string }): ReactNode {
  return (
    <li className="stat-item">
      <div className="stat-tile stat-tile-failed" role="status" data-testid={`tile-failed`}>
        <span className="stat-label">{label}</span>
        <span className="stat-empty">Could not be worked out. Refresh to try again.</span>
      </div>
    </li>
  );
}

/** A tile-shaped placeholder for the first load. */
export function TileSkeleton() {
  return (
    <li className="stat-item" aria-hidden="true">
      <div className="stat-tile stat-tile-skeleton">
        <span className="skeleton-line skeleton-short" />
        <span className="skeleton-line stat-skeleton-value" />
        <span className="skeleton-line" />
      </div>
    </li>
  );
}

/** The days of the period as a tiny area: decoration for the eye, the number above says the same. */
function Spark({ values }: { values: readonly number[] }) {
  const width = 120;
  const height = 28;
  const points = linePoints(values, width, height, niceMax(Math.max(0, ...values)));
  return (
    <svg className="stat-spark" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden="true" focusable="false">
      <path d={areaPath(points, height)} className="stat-spark-area" />
      <path d={linePath(points)} className="stat-spark-line" />
    </svg>
  );
}
