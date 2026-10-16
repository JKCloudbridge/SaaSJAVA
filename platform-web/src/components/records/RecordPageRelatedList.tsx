"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api/client";
import { call, type Outcome } from "@/lib/records/call";
import { useLookupLabels } from "@/lib/records/hooks";
import { relatedListHref } from "@/lib/records/paths";
import type { FormField, QueriedRecord, RecordPageBlock } from "@/lib/records/types";
import { FieldReadValue } from "./FieldRegistry";

/** How many rows a list shows when the page does not say (the server sends the page's own, 5 unless set). */
const DEFAULT_LIMIT = 5;

/** What the query for a related list answered: its rows, and whether there are more than were asked for. */
interface Rows {
  data: QueriedRecord[];
  pagination: { hasMore: boolean };
}

/** What asking an object for its own fields, to describe a related list's columns, came to. */
function useColumns(objectApiName: string, names: readonly string[]) {
  const [loaded, setLoaded] = useState<{ key: string; fields: FormField[] } | null>(null);
  const key = `${objectApiName}|${names.join(",")}`;
  useEffect(() => {
    let live = true;
    void call(() => api.GET("/api/v1/objects/{objectApiName}/form", { params: { path: { objectApiName } } })).then(
      (outcome) => {
        if (live && outcome.ok) {
          const byName = new Map(outcome.data.data.fields.map((field) => [field.apiName, field]));
          setLoaded({ key, fields: names.map((name) => byName.get(name)).filter((field): field is FormField => !!field) });
        }
      },
    );
    return () => {
      live = false;
    };
  }, [key, objectApiName, names]);
  return loaded?.key === key ? loaded.fields : undefined;
}

/**
 * One related list of a record page (Sprint 20, ADR-0131): the records of {@link RecordPageBlock.object} whose
 * {@link RecordPageBlock.field} points at the open record, read through the same query the list screen uses
 * (`POST .../records/query`), so the security and the field permissions of the related object apply exactly as they
 * would on its own list. The columns are already the ones the caller may read (the server dropped the others).
 *
 * <p>It shows the first rows ({@link RecordPageBlock.limit}) and, when there are more, "View all", which opens the
 * related object's own list already narrowed to this record. The first column leads each row as the link to the
 * related record: a configured list has columns an administrator chose (the first is what they want it led by), and the
 * platform's own list starts with the name of the record, so no second rule for "what names a row" is needed here.
 */
export function RecordPageRelatedList({ block, recordId, recordHref }: {
  block: RecordPageBlock;
  recordId: string;
  recordHref: (objectApiName: string, id: string) => string;
}) {
  const object = block.object ?? "";
  const field = block.field ?? "";
  const names = useMemo(() => block.columns ?? [], [block.columns]);
  const columns = useColumns(object, names);
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<Rows> } | null>(null);
  const limit = block.limit ?? DEFAULT_LIMIT;
  const key = `${object}|${field}|${recordId}|${limit}`;

  useEffect(() => {
    if (!object || !field) {
      return;
    }
    let live = true;
    void call(() =>
      api.POST("/api/v1/objects/{objectApiName}/records/query", {
        params: { path: { objectApiName: object } },
        body: { filter: { op: "EQ", field, values: [recordId] }, fields: names, limit },
      }),
    ).then((outcome) => {
      if (live) {
        setLoaded({ key, outcome });
      }
    });
    return () => {
      live = false;
    };
  }, [key, object, field, recordId, names, limit]);

  const records = loaded?.key === key && loaded.outcome.ok ? loaded.outcome.data.data : undefined;
  const hasMore = loaded?.key === key && loaded.outcome.ok ? loaded.outcome.data.pagination.hasMore : false;
  const labels = useLookupLabels(object, columns ?? [], (records ?? []).map((entry) => entry.record));

  if (!columns || !records) {
    return (
      <div className="custom-page-loading" role="status" aria-live="polite">
        <span className="skeleton-line" />
        <span className="visually-hidden">Loading</span>
      </div>
    );
  }
  if (loaded && !loaded.outcome.ok) {
    return (
      <p className="form-message" role="alert">
        {loaded.outcome.failure.message}
      </p>
    );
  }
  if (records.length === 0) {
    return <p className="hint">No related records.</p>;
  }
  return (
    <>
    <table className="table" data-testid="related-list-table">
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.apiName}>{column.label}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {records.map(({ record }) => (
          <tr key={record.id}>
            {columns.map((column, index) => (
              <td key={column.apiName}>
                {index === 0 ? (
                  <Link href={recordHref(object, record.id)}>
                    <FieldReadValue field={column} value={record.fields[column.apiName]} labels={labels[column.apiName]} />
                  </Link>
                ) : (
                  <FieldReadValue
                    field={column}
                    value={record.fields[column.apiName]}
                    labels={labels[column.apiName]}
                    recordHref={recordHref}
                  />
                )}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
    {hasMore ? (
      <p>
        <Link href={relatedListHref(object, field, recordId)} data-testid="related-view-all">
          View all
        </Link>
      </p>
    ) : null}
    </>
  );
}
