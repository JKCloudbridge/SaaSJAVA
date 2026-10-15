"use client";

import Link from "next/link";
import { useEffect, useRef } from "react";
import type { LabelMap } from "@/lib/records/hooks";
import { formatValue, recordValue } from "@/lib/records/fieldValue";
import type { SortKey } from "@/lib/records/queryModel";
import type { FormField, QueriedRecord, RecordView } from "@/lib/records/types";
import { FieldReadValue } from "./FieldRegistry";

/**
 * The rows of a list (Sprint 19, ADR-0129). Every cell is drawn by the field registry from the value the API sent; a
 * cell holds no rule. The first column is the way into the record. The header of a column the server says can be
 * ordered by is a button that cycles ascending, descending and off (at most two keys, the server's limit); a column that
 * cannot be ordered by (a link, a long text, a formula of such a type) is plain text. The checkbox column is for the
 * bulk operations and is offered only when the member may use any (the caller says so).
 */
export function ListTable({
  columns,
  records,
  labels,
  sort,
  onSort,
  selected,
  onToggle,
  onToggleAll,
  selectable,
  recordHref,
  openHref,
  searching,
}: {
  columns: readonly FormField[];
  records: readonly QueriedRecord[];
  labels: LabelMap;
  sort: readonly SortKey[];
  /** Called with `additive` when shift is held: the column becomes the second key of the order instead of the only one. */
  onSort: (field: FormField, additive: boolean) => void;
  selected: ReadonlyMap<string, RecordView>;
  onToggle: (record: RecordView) => void;
  onToggleAll: (records: readonly RecordView[], on: boolean) => void;
  selectable: boolean;
  recordHref: (objectApiName: string, id: string) => string;
  openHref: (id: string) => string;
  /** Results of a search are ranked, so the column order does not apply and the headers are plain. */
  searching: boolean;
}) {
  const views = records.map((entry) => entry.record);
  const everySelected = views.length > 0 && views.every((record) => selected.has(record.id));
  const someSelected = views.some((record) => selected.has(record.id));
  const header = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (header.current) {
      header.current.indeterminate = someSelected && !everySelected;
    }
  }, [someSelected, everySelected]);

  return (
    <table className="table list-table" data-testid="list-table">
      <thead>
        <tr>
          {selectable ? (
            <th scope="col" className="select-cell">
              <input
                ref={header}
                type="checkbox"
                aria-label="Select all rows on this page"
                checked={everySelected}
                onChange={(event) => onToggleAll(views, event.target.checked)}
              />
            </th>
          ) : null}
          {columns.map((field) => {
            const position = sort.findIndex((key) => key.field === field.apiName);
            const key = position >= 0 ? sort[position] : undefined;
            const ariaSort = key ? (key.descending ? "descending" : "ascending") : undefined;
            return (
              <th key={field.apiName} scope="col" aria-sort={ariaSort}>
                {field.sortable && !searching ? (
                  <button type="button" className="sort-button" onClick={(event) => onSort(field, event.shiftKey)}>
                    {field.label}
                    <span aria-hidden="true">{key ? (key.descending ? " ▼" : " ▲") : ""}</span>
                  </button>
                ) : (
                  field.label
                )}
              </th>
            );
          })}
        </tr>
      </thead>
      <tbody>
        {records.map((entry) => {
          const record = entry.record;
          return (
            <tr key={record.id} aria-selected={selected.has(record.id) || undefined}>
              {selectable ? (
                <td className="select-cell">
                  <input
                    type="checkbox"
                    aria-label="Select this row"
                    checked={selected.has(record.id)}
                    onChange={() => onToggle(record)}
                  />
                </td>
              ) : null}
              {columns.map((field, index) => {
                const value = recordValue(record, field.apiName);
                return (
                  <td key={field.apiName} className={field.type === "LONG_TEXT" ? "cell-truncate" : undefined}>
                    {index === 0 ? (
                      // The way into the record is the first column; its text is plain so a link is never inside a link.
                      <Link href={openHref(record.id)} className="record-link">
                        {formatValue(field, value, labels[field.apiName]) || <span className="hint">Open</span>}
                      </Link>
                    ) : (
                      <FieldReadValue
                        field={field}
                        value={value}
                        labels={labels[field.apiName]}
                        recordHref={recordHref}
                      />
                    )}
                  </td>
                );
              })}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
