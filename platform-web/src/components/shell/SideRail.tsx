"use client";

import Link from "next/link";
import { useId, useState } from "react";
import { Icon } from "@/components/ui/Icon";
import { isCurrent, type RailEntry, type RailLink } from "@/lib/shell/areas";

/** The key of the group that holds the page the address names, or null when the page stands alone or is in none. */
function groupOfCurrent(entries: readonly RailEntry[], pathname: string, search: string): string | null {
  const group = entries.find(
    (entry) => entry.kind === "group" && entry.items.some((item) => isCurrent(item, pathname, search)),
  );
  return group?.kind === "group" ? group.key : null;
}

/** The label of the page the address names, for the button of the narrow-screen menu. */
function currentLabel(entries: readonly RailEntry[], pathname: string, search: string): string | null {
  const links: RailLink[] = entries.flatMap((entry) => (entry.kind === "group" ? entry.items : [entry]));
  return links.find((item) => isCurrent(item, pathname, search))?.label ?? null;
}

function RailItem({ item, pathname, search }: { item: RailLink; pathname: string; search: string }) {
  if (item.unavailable) {
    // Nothing is built behind it yet: a name with its badge, not a link and not a button.
    return (
      <span className="side-rail-link side-rail-soon" aria-disabled="true" data-testid="rail-unavailable">
        {item.label}
        <span className="badge">Soon</span>
      </span>
    );
  }
  return (
    <Link
      href={item.href}
      className="side-rail-link"
      aria-current={isCurrent(item, pathname, search) ? "page" : undefined}
    >
      {item.label}
      {item.soon ? <span className="badge">Soon</span> : null}
    </Link>
  );
}

/**
 * The left rail shared by every area that has one (the Home tab, the platform console, later the page of one object).
 * Entries are plain links; some are grouped, one level deep: the group's main item is a button with a small arrow that
 * opens the links below it and closes them again. It is an accordion (opening one closes the others), and when the
 * person arrives on a page the group that holds it is the one that is open. A closed group is inert and hidden from
 * assistive technology, so its links cannot be reached by keyboard until it is opened.
 *
 * <p>It draws exactly the entries it is given: which entries a person may see is decided before (`homeRail`), from the
 * abilities the API lists, and this component decides nothing. On a narrow screen the rail becomes a button that opens
 * the same list below it.
 *
 * @param label the name of the rail, shown above it and used as the name of the navigation
 * @param entries the links and groups, in order
 * @param pathname the address of the page, to mark the current page
 * @param search the query of the address (`section=fields`), for a rail whose entries differ only by it
 */
export function SideRail({
  label,
  entries,
  pathname,
  search = "",
}: {
  label: string;
  entries: readonly RailEntry[];
  pathname: string;
  search?: string;
}) {
  const base = useId();
  const arrival = groupOfCurrent(entries, pathname, search);
  const [open, setOpen] = useState<string | null>(arrival);
  const [menuOpen, setMenuOpen] = useState(false);
  const place = `${pathname}?${search}`;
  const [seen, setSeen] = useState(place);
  if (seen !== place) {
    // A new page: the group that holds it opens (and the others close); a page that stands alone leaves the groups
    // as they are. The narrow-screen menu closes. Adjusting state while rendering is how React derives it from the
    // address.
    setSeen(place);
    setMenuOpen(false);
    if (arrival !== null) {
      setOpen(arrival);
    }
  }
  const listId = `${base}-list`;
  const here = currentLabel(entries, pathname, search);
  return (
    <nav className="side-rail" aria-label={label} data-testid="side-rail">
      <p className="side-rail-label">{label}</p>
      <button
        type="button"
        className="side-rail-toggle"
        aria-expanded={menuOpen}
        aria-controls={listId}
        onClick={() => setMenuOpen((value) => !value)}
      >
        <span>
          {label}
          {here ? <span className="side-rail-toggle-here"> · {here}</span> : null}
        </span>
        <Icon name="chevron" className="side-rail-arrow" />
      </button>
      <div id={listId} className="side-rail-body" data-open={menuOpen}>
        <ul>
          {entries.map((entry) =>
            entry.kind === "link" ? (
              <li key={entry.href}>
                <RailItem item={entry} pathname={pathname} search={search} />
              </li>
            ) : (
              <li key={entry.key} className="side-rail-group" data-open={open === entry.key}>
                <button
                  type="button"
                  className="side-rail-group-button"
                  data-current={entry.items.some((item) => isCurrent(item, pathname, search)) ? "true" : undefined}
                  aria-expanded={open === entry.key}
                  aria-controls={`${base}-${entry.key}`}
                  onClick={() => setOpen((current) => (current === entry.key ? null : entry.key))}
                >
                  {entry.label}
                  <Icon name="chevron" className="side-rail-arrow" />
                </button>
                <div
                  id={`${base}-${entry.key}`}
                  className="side-rail-sub"
                  inert={open !== entry.key}
                  aria-hidden={open !== entry.key}
                >
                  <ul>
                    {entry.items.map((item) => (
                      <li key={item.href}>
                        <RailItem item={item} pathname={pathname} search={search} />
                      </li>
                    ))}
                  </ul>
                </div>
              </li>
            ),
          )}
        </ul>
      </div>
    </nav>
  );
}
