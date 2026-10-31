"use client";

import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { BOARD_CLOSING, BOARD_TAKEAWAYS, boardCards, boardMessage, boardTiles, type BoardEntry } from "./board";
import { ChartCard } from "./ChartCard";
import { AreaChart, RingChart, type Slice } from "./charts";
import { dayLabel, formatCount, stateForList, periodWords, type HomeLinks, type HomeSummary } from "./model";
import { TileSkeleton } from "./StatTile";

/**
 * The third look of the home page, the board (FA13C): a title with a tagline, six tiles with a picture, three graphs, six
 * cards with two figures each, a message panel and four reminders. It draws exactly the numbers the other looks draw
 * (the page hands them over), adds up nothing and decides nothing; a click opens the same list as in the other looks.
 */
export function BoardHeading({ label }: { label: string }) {
  return (
    <div className="board-heading">
      <div>
        <h1 className="board-title">{label} dashboard</h1>
        <p className="board-tagline">Controlled documents. Complete records. Decisions on time.</p>
      </div>
      <p className="board-quote">A strong system is not about closing records, it is about preventing repeats.</p>
    </div>
  );
}

export function BoardTiles({ entries, loading }: { entries: readonly BoardEntry[] | undefined; loading: boolean }) {
  if (!entries) {
    return loading ? (
      <ul className="board-tiles" aria-hidden="true" data-testid="home-tiles-loading">
        {Array.from({ length: 6 }, (_, index) => (
          <TileSkeleton key={index} />
        ))}
      </ul>
    ) : null;
  }
  return (
    <ul className="board-tiles" data-testid="home-tiles">
      {boardTiles(entries).map(({ entry, icon }) => {
        if (entry.failed) {
          return (
            <li key={entry.label} className="board-item">
              <div className="board-tile board-tile-failed" role="status" data-testid="tile-failed">
                <span className="board-tile-label">{entry.label}</span>
                <span className="board-tile-empty">Could not be worked out. Refresh to try again.</span>
              </div>
            </li>
          );
        }
        const view = entry.view;
        const body = (
          <>
            <span className="board-tile-head">
              <span className="board-icon" aria-hidden="true">
                <Icon name={icon} className="board-icon-glyph" />
              </span>
              <span className="board-tile-label">{view.label}</span>
            </span>
            {view.value !== undefined ? (
              <span className="board-tile-value" data-testid={`value-${view.id}`}>
                {view.value}
              </span>
            ) : (
              <span className="board-tile-empty">{view.empty}</span>
            )}
            {view.change && view.change.direction !== "none" ? (
              <span className="board-tile-sub" data-tone={view.tone ?? "neutral"}>
                {view.change.direction === "up" ? <Icon name="arrowUp" className="board-arrow" /> : null}
                {view.change.direction === "down" ? <Icon name="arrowDown" className="board-arrow" /> : null}
                {view.change.words}
              </span>
            ) : view.hint ? (
              <span className="board-tile-sub">{view.hint}</span>
            ) : null}
            {view.note ? <span className="board-tile-sub">{view.note}</span> : null}
          </>
        );
        return (
          <li key={view.id} className="board-item">
            {view.href ? (
              <Link href={view.href} className="board-tile board-tile-link" data-testid={`tile-${view.id}`}>
                {body}
              </Link>
            ) : (
              <div className="board-tile" data-testid={`tile-${view.id}`}>
                {body}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** The three graphs of the board: fill-ins per day, templates by status, templates by level and document type. */
export function BoardCharts({
  summary,
  loading,
  links,
  onOpen,
}: {
  summary: HomeSummary | undefined;
  loading: boolean;
  links: HomeLinks;
  onOpen: (href: string) => void;
}) {
  if (!summary) {
    return (
      <div className="board-charts">
        {["Fill-ins per day", "Templates by status", "Templates by level and document type"].map((title, index) => (
          <ChartCard
            key={title}
            title={title}
            table={{ caption: title, columns: [], rows: [] }}
            loading={loading}
            testId={`chart-loading-${index}`}
          >
            {null}
          </ChartCard>
        ))}
      </div>
    );
  }
  const { charts, problems } = summary;
  const templatesFailed = problems.includes("templates");
  const fillsFailed = problems.includes("fill-ins");
  const failedWords = "Could not be worked out. Refresh to try again.";
  const perDay = charts.fillsPerDay;
  const statuses: Slice[] | undefined = charts.templatesByStatus?.map((one) => ({
    key: one.key,
    label: one.label,
    count: one.count,
  }));
  const kinds = [...(charts.templatesByLevelAndType ?? [])].sort((a, b) => b.count - a.count).slice(0, 8);
  return (
    <div className="board-charts">
      <ChartCard
        testId="chart-fills-per-day"
        title="Fill-ins per day"
        note={`Last ${periodWords(summary.period.key)}`}
        empty={fillsFailed ? failedWords : !perDay ? undefined : perDay.total === 0 ? "No fill-ins in this period" : undefined}
        loading={loading && !perDay && !fillsFailed}
        table={{
          caption: "Fill-ins sent on each day of the period",
          columns: ["Day", "Fill-ins"],
          rows: (perDay?.days ?? []).map((one) => [dayLabel(one.day), one.count]),
        }}
      >
        {perDay ? <AreaChart days={perDay.days} unit="fill-ins" /> : null}
      </ChartCard>

      <ChartCard
        testId="chart-templates-by-status"
        title="Templates by status"
        note="All templates you can open, as of today"
        empty={
          templatesFailed
            ? failedWords
            : statuses && statuses.every((one) => one.count === 0)
              ? "There are no templates yet"
              : undefined
        }
        table={{
          caption: "Templates by status",
          columns: ["Status", "Templates"],
          rows: (statuses ?? []).map((one) => [one.label, one.count]),
        }}
      >
        <RingChart slices={statuses ?? []} unit="templates" onSelect={(key) => onOpen(links.templates(stateForList(key)))} />
      </ChartCard>

      <ChartCard
        testId="chart-level-and-type"
        title="Templates by level and document type"
        note="The largest groups first"
        empty={templatesFailed ? failedWords : kinds.length === 0 ? "There are no templates yet" : undefined}
        table={{
          caption: "Templates by level and document type",
          columns: ["Level", "Document type", "Templates"],
          rows: (charts.templatesByLevelAndType ?? []).map((one) => [one.levelLabel, one.typeLabel, one.count]),
        }}
      >
        <table className="table board-table">
          <thead>
            <tr>
              <th scope="col">Level</th>
              <th scope="col">Document type</th>
              <th scope="col" className="board-number">
                Templates
              </th>
            </tr>
          </thead>
          <tbody>
            {kinds.map((one) => (
              <tr key={`${one.levelLabel}|${one.typeLabel}`}>
                <td>{one.levelLabel}</td>
                <td>{one.typeLabel}</td>
                <td className="board-number">{formatCount(one.count)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </ChartCard>
    </div>
  );
}

/** The six cards, the message panel and the four reminders. */
export function BoardFoot({
  entries,
  summary,
  tasks,
}: {
  entries: readonly BoardEntry[] | undefined;
  summary: HomeSummary | undefined;
  tasks: number | null | undefined;
}) {
  if (!entries || !summary) {
    return null;
  }
  const cards = boardCards(entries, summary);
  const message = boardMessage(summary, tasks);
  return (
    <>
      <ul className="board-cards" data-testid="board-cards">
        {cards.map((card) => (
          <li key={card.key} className="board-card" data-testid={`card-${card.key}`}>
            <h3 className="board-card-title">{card.title}</h3>
            <span className="board-icon" aria-hidden="true">
              <Icon name={card.icon} className="board-icon-glyph" />
            </span>
            <ul className="board-figures">
              {card.figures.map((figure) => (
                <li key={figure.label}>
                  {figure.href ? (
                    <Link href={figure.href} className="board-figure">
                      <span className="board-figure-value">{figure.value}</span>
                      <span className="board-figure-label">{figure.label}</span>
                    </Link>
                  ) : (
                    <span className="board-figure">
                      <span className="board-figure-value">{figure.value}</span>
                      <span className="board-figure-label">{figure.label}</span>
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>

      <div className="board-foot">
        <section className="board-takeaways" aria-label="Key takeaways">
          <h2 className="board-foot-title">Key takeaways</h2>
          <ul>
            {BOARD_TAKEAWAYS.map((one) => (
              <li key={one.title}>
                <span className="board-icon" aria-hidden="true">
                  <Icon name={one.icon} className="board-icon-glyph" />
                </span>
                <span>
                  <strong>{one.title}</strong>
                  <span className="board-takeaway-text">{one.text}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>
        <section className="board-message" aria-label="What the numbers say" data-testid="board-message">
          <h2 className="board-foot-title">What the numbers say</h2>
          <ul>
            {message.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <p className="board-closing">{BOARD_CLOSING}</p>
        </section>
      </div>
    </>
  );
}
