"use client";

import Link from "next/link";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent,
} from "react";
import { AppIcon } from "@/components/ui/AppIcon";
import { Icon } from "@/components/ui/Icon";
import { applicationHref, type MyApplication } from "@/lib/applications/entries";
import {
  filterApplications,
  groupSlots,
  groupsOf,
  readRecent,
  recentApplications,
  rememberRecent,
  resultsLine,
  showsComingSoon,
  tabsLine,
} from "@/lib/applications/grid";

/** What the grid needs to say about the list it was given. */
export interface GridStatus {
  readonly kind: "loading" | "ready";
}

/**
 * The grid of applications (FA9, ADR-0203): the start page of an organization and, through the strip's button, a page
 * any member can come back to. It draws exactly the list the API gave, in the API's order, and hardcodes no application.
 *
 * <p>Every tile is a link, so it opens in a new tab, can be bookmarked and works without a script. The arrow keys, Home
 * and End move between the tiles by what is drawn (the columns are measured, not assumed), the search box takes the
 * focus on "/" and filters as the person types, and the chips narrow to one group. The application that was open when
 * the person came here is marked, and the last few they opened are offered above the grid.
 *
 * @param applications the member's applications, as the API lists them
 * @param status whether the list is still on its way, is here, or could not be had
 * @param current the API name of the application the person came from, marked in the grid
 * @param canSetUp whether the member may set things up, which adds the "New application" tile and "Manage applications"
 */
export function ApplicationGrid({
  applications,
  status,
  current,
  canSetUp,
}: {
  applications: readonly MyApplication[];
  status: GridStatus;
  current?: string;
  canSetUp: boolean;
}) {
  const [search, setSearch] = useState("");
  const [group, setGroup] = useState<string | null>(null);
  const [recent, setRecent] = useState<string[]>([]);
  const list = useRef<HTMLUListElement | null>(null);
  const input = useRef<HTMLInputElement | null>(null);
  const resultsId = useId();

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- browser storage is read after mount, so the server and first draw agree
    setRecent(readRecent());
  }, []);

  useEffect(() => {
    function onKey(event: globalThis.KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const typing = target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
      if (event.key === "/" && !typing && !event.metaKey && !event.ctrlKey && !event.altKey) {
        event.preventDefault();
        input.current?.focus();
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const slots = useMemo(() => groupSlots(applications), [applications]);
  const groups = useMemo(() => groupsOf(applications), [applications]);
  const shown = useMemo(() => filterApplications(applications, search, group), [applications, search, group]);
  const recents = useMemo(() => recentApplications(applications, recent), [applications, recent]);
  const searching = search.trim() !== "" || group !== null;

  const opened = useCallback((apiName: string) => rememberRecent(apiName), []);

  /** How many tiles sit on the first row, measured from what is drawn: the grid has as many columns as that. */
  function columns(tiles: HTMLElement[]): number {
    const first = tiles[0];
    if (!first) {
      return 1;
    }
    return Math.max(1, tiles.filter((tile) => tile.offsetTop === first.offsetTop).length);
  }

  function onGridKey(event: KeyboardEvent<HTMLUListElement>) {
    const keys = ["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Home", "End"];
    if (!keys.includes(event.key)) {
      return;
    }
    const tiles = Array.from(event.currentTarget.querySelectorAll<HTMLElement>("a.app-tile"));
    const at = tiles.indexOf(document.activeElement as HTMLElement);
    if (at < 0) {
      return;
    }
    const across = columns(tiles);
    const next =
      event.key === "Home" ? 0
      : event.key === "End" ? tiles.length - 1
      : event.key === "ArrowRight" ? at + 1
      : event.key === "ArrowLeft" ? at - 1
      : event.key === "ArrowDown" ? at + across
      : at - across;
    if (next >= 0 && next < tiles.length) {
      event.preventDefault();
      tiles[next]?.focus();
    }
  }

  function follow(event: PointerEvent<HTMLElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    event.currentTarget.style.setProperty("--glow-x", `${event.clientX - box.left}px`);
    event.currentTarget.style.setProperty("--glow-y", `${event.clientY - box.top}px`);
  }

  const loading = status.kind === "loading" && applications.length === 0;

  return (
    <div className="app-grid-page" data-testid="application-grid">
      <header className="app-grid-head">
        <h1 className="visually-hidden">Applications</h1>
        {canSetUp ? (
          <Link href="/setup/applications" className="app-grid-manage">
            <Icon name="setup" className="app-grid-manage-icon" />
            Manage applications
          </Link>
        ) : null}
      </header>

      <div className="app-grid-tools">
        <div className="app-grid-search">
          <Icon name="search" className="app-grid-search-icon" />
          <input
            ref={input}
            type="search"
            className="app-grid-search-input"
            placeholder="Search applications"
            aria-label="Search applications"
            aria-controls={resultsId}
            autoComplete="off"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape" && search) {
                setSearch("");
              }
              if (event.key === "ArrowDown") {
                event.preventDefault();
                list.current?.querySelector<HTMLElement>("a.app-tile")?.focus();
              }
            }}
          />
          <kbd className="app-grid-kbd" aria-hidden="true">
            /
          </kbd>
        </div>

        {groups.length > 1 ? (
          <div className="app-grid-chips" role="group" aria-label="Show one group of applications">
            <button type="button" className="app-chip" aria-pressed={group === null} onClick={() => setGroup(null)}>
              All
            </button>
            {groups.map((name) => (
              <button
                key={name}
                type="button"
                className="app-chip"
                aria-pressed={group === name}
                style={{ "--tile-tint": `var(--series-${slots.get(name) ?? 1})` } as CSSProperties}
                onClick={() => setGroup(group === name ? null : name)}
              >
                <span className="app-chip-dot" aria-hidden="true" />
                {name}
              </button>
            ))}
          </div>
        ) : null}

        {recents.length > 0 && !searching ? (
          <div className="app-grid-recent">
            <span className="app-grid-eyebrow">Recent</span>
            {recents.map((application) => (
              <Link
                key={application.apiName}
                href={applicationHref(application)}
                className="app-recent"
                onClick={() => opened(application.apiName)}
              >
                <AppIcon iconKey={application.iconKey} label={application.label} />
                {application.label}
              </Link>
            ))}
          </div>
        ) : null}
      </div>

      <p id={resultsId} className="visually-hidden" role="status" aria-live="polite">
        {loading ? "Loading your applications" : resultsLine(shown.length, applications.length)}
      </p>

      {loading ? (
        <ul className="app-grid" aria-hidden="true" data-testid="grid-loading">
          {Array.from({ length: 8 }, (_, index) => (
            <li key={index} className="app-tile app-tile-skeleton">
              <span className="skeleton-line app-skeleton-icon" />
              <span className="skeleton-line" />
              <span className="skeleton-line skeleton-short" />
            </li>
          ))}
        </ul>
      ) : null}

      {!loading && (shown.length > 0 || (canSetUp && !searching)) ? (
        <ul className="app-grid" ref={list} onKeyDown={onGridKey}>
          {shown.map((application, index) => {
            const slot = application.group ? slots.get(application.group.trim()) : undefined;
            const isCurrent = application.apiName === current;
            return (
              <li key={application.apiName} className="app-grid-item" style={{ "--tile-order": index } as CSSProperties}>
                <Link
                  href={applicationHref(application)}
                  className="app-tile"
                  aria-current={isCurrent ? "page" : undefined}
                  style={{ "--tile-tint": `var(--series-${slot ?? 1})` } as CSSProperties}
                  data-grouped={slot ? "true" : "false"}
                  onPointerMove={follow}
                  onClick={() => opened(application.apiName)}
                >
                  <span className="app-tile-top">
                    <AppIcon iconKey={application.iconKey} label={application.label} />
                    {isCurrent ? (
                      <span className="app-tile-open">
                        <span className="app-tile-open-dot" aria-hidden="true" />
                        Open now
                      </span>
                    ) : showsComingSoon(application) ? (
                      <span className="app-tile-soon">Coming soon</span>
                    ) : null}
                  </span>
                  <span className="app-tile-name">{application.label}</span>
                  <span className="app-tile-meta">
                    <span>{tabsLine(application)}</span>
                    {application.group ? <span className="app-tile-group">{application.group}</span> : null}
                  </span>
                  <Icon name="arrowRight" className="app-tile-go" />
                </Link>
              </li>
            );
          })}
          {canSetUp && !searching ? (
            <li className="app-grid-item" style={{ "--tile-order": shown.length } as CSSProperties}>
              <Link href="/setup/applications/new" className="app-tile app-tile-new" onPointerMove={follow}>
                <span className="app-tile-top">
                  <span className="app-icon">
                    <Icon name="plus" className="app-icon-glyph" />
                  </span>
                </span>
                <span className="app-tile-name">New application</span>
                <span className="app-tile-meta">
                  <span>Build one in Setup</span>
                </span>
              </Link>
            </li>
          ) : null}
        </ul>
      ) : null}

      {!loading && applications.length > 0 && shown.length === 0 ? (
        <div className="empty-state" role="status" data-testid="grid-no-match">
          <p>
            <strong>No application matches.</strong>
          </p>
          <p>Try a shorter word, or show all applications.</p>
          <button
            type="button"
            className="link-button"
            onClick={() => {
              setSearch("");
              setGroup(null);
            }}
          >
            Show all
          </button>
        </div>
      ) : null}
    </div>
  );
}
