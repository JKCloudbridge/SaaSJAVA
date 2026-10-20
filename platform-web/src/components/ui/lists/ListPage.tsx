"use client";

import Link from "next/link";
import { useState, type MouseEvent, type ReactNode } from "react";
import { Icon } from "@/components/ui/Icon";
import { PageFrame } from "./PageFrame";

/**
 * One column after the first. A "tag" column sits at the right end; "muted" is quiet text; "actions" holds the quiet buttons
 * of a row (switch on or off, remove) and has a hidden header.
 */
export interface ListColumn<T> {
  key: string;
  header: string;
  cell: (item: T) => ReactNode;
  kind?: "muted" | "tag" | "actions";
}

const endAligned = (kind: ListColumn<unknown>["kind"]) => kind === "tag" || kind === "actions";

export interface ListPageProps<T> {
  title: string;
  intro?: ReactNode;
  /** The rows the screen has loaded; undefined while they are still on the way. */
  items: readonly T[] | undefined;
  /** The API's words when the list could not be loaded: shown instead of the table. */
  failure?: string;
  rowKey: (item: T) => string;
  /** The first column: the name, bold; it is the link that opens the row. */
  primaryHeader: string;
  primary: (item: T) => string;
  /** Quiet extra under the name (a hint, a mark such as "default"). */
  primaryExtra?: (item: T) => ReactNode;
  /** Levels to indent the name (a tree shown by indentation, such as the roles). */
  primaryIndent?: (item: T) => number;
  columns: readonly ListColumn<T>[];
  /** Opens a row in the same pane; or a link for a row that has an address of its own. One of them, or none. */
  onOpen?: (item: T) => void;
  openHref?: (item: T) => string;
  /** A list that mixes rows that open with rows that do not (the tab of an object): false draws the name as plain text. */
  canOpen?: (item: T) => boolean;
  /** The search box filters the rows already loaded: the text of a row that it may match. Without it there is no box. */
  searchText?: (item: T) => string;
  searchLabel?: string;
  /** New is drawn only when the screen can create something and the API's answers allow it. */
  onNew?: () => void;
  newHref?: string;
  newLabel?: string;
  /** The screen's own extra buttons, next to New. */
  tools?: ReactNode;
  notices?: ReactNode;
  /** Content between the title band and the table, and after the table. */
  above?: ReactNode;
  below?: ReactNode;
  emptyText: string;
  testId: string;
  rowTestId?: string;
}

/**
 * The list pattern of the Home screens (ADR-0168): the title with a search box and New at the right, then a table with a
 * quiet header row, the name in bold as the first column, tags at the end and a hover on a row. The search filters the rows
 * the screen has loaded, never the server's; a list that the API pages or filters keeps its own filter bar instead.
 */
export function ListPage<T>(props: ListPageProps<T>) {
  const { items, failure, searchText, onOpen, openHref, onNew, newHref } = props;
  const [query, setQuery] = useState("");

  const needle = query.trim().toLowerCase();
  const shown =
    items && searchText && needle ? items.filter((item) => searchText(item).toLowerCase().includes(needle)) : items;
  const openable = Boolean(onOpen || openHref);
  const label = props.searchLabel ?? `Search ${props.title.toLowerCase()}`;
  const newLabel = props.newLabel ?? "New";

  const tools = (
    <>
      {searchText ? (
        <div className="search-box">
          <Icon name="search" className="search-box-icon" />
          <input
            type="search"
            aria-label={label}
            placeholder={`${label}…`}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
      ) : null}
      {props.tools}
      {onNew ? (
        <button type="button" className="button" onClick={onNew}>
          {newLabel}
        </button>
      ) : null}
      {newHref ? (
        <Link className="button" href={newHref}>
          {newLabel}
        </Link>
      ) : null}
    </>
  );

  // A click anywhere on the row opens it, unless it landed on something that does its own thing.
  function openFromRow(event: MouseEvent<HTMLTableRowElement>) {
    if ((event.target as HTMLElement).closest("a, button, input, select, textarea, label")) {
      return;
    }
    event.currentTarget.querySelector<HTMLElement>(".row-open")?.click();
  }

  return (
    <PageFrame title={props.title} intro={props.intro} tools={tools} notices={props.notices}>
      {failure ? (
        <p className="form-message" role="alert" data-testid={`${props.testId}-failed`}>
          {failure}
        </p>
      ) : null}
      {!failure && !items ? <p aria-live="polite">Loading…</p> : null}
      {props.above}
      {!failure && shown ? (
        <>
          <table className="table table-list" data-testid={props.testId}>
            <thead>
              <tr>
                <th scope="col">{props.primaryHeader}</th>
                {props.columns.map((column) => (
                  <th key={column.key} scope="col" className={endAligned(column.kind) ? "cell-tag" : undefined}>
                    {column.kind === "actions" ? <span className="visually-hidden">{column.header}</span> : column.header}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {shown.length === 0 ? (
                <tr className="table-list-empty">
                  <td colSpan={props.columns.length + 1}>
                    {items && items.length > 0 ? `Nothing matches “${query.trim()}”.` : props.emptyText}
                  </td>
                </tr>
              ) : (
                shown.map((item) => {
                  const opens = openable && (props.canOpen ? props.canOpen(item) : true);
                  return (
                    <tr
                      key={props.rowKey(item)}
                      data-testid={props.rowTestId}
                      data-openable={opens ? "true" : undefined}
                      onClick={opens ? openFromRow : undefined}
                    >
                      <td
                        style={
                          props.primaryIndent
                            ? { paddingInlineStart: `calc(var(--space-3-5) + var(--space-4) * ${props.primaryIndent(item)})` }
                            : undefined
                        }
                      >
                        {!opens ? (
                          <span className="row-name">{props.primary(item)}</span>
                        ) : openHref ? (
                          <Link className="row-open" href={openHref(item)}>
                            {props.primary(item)}
                          </Link>
                        ) : onOpen ? (
                          <button type="button" className="row-open" onClick={() => onOpen(item)}>
                            {props.primary(item)}
                          </button>
                        ) : null}
                        {props.primaryExtra ? <span className="row-extra">{props.primaryExtra(item)}</span> : null}
                      </td>
                      {props.columns.map((column) => (
                        <td
                          key={column.key}
                          className={endAligned(column.kind) ? "cell-tag" : column.kind === "muted" ? "cell-muted" : undefined}
                        >
                          {column.cell(item)}
                        </td>
                      ))}
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
          {items && needle ? (
            <p className="hint" aria-live="polite">
              Showing {shown.length} of {items.length}.
            </p>
          ) : null}
        </>
      ) : null}
      {props.below}
    </PageFrame>
  );
}
