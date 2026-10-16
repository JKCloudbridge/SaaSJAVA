"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { call, type Outcome } from "@/lib/records/call";
import { formatValue, recordValue } from "@/lib/records/fieldValue";
import { useLookupLabels } from "@/lib/records/hooks";
import {
  defaultColumns,
  definitionOf,
  describeRow,
  queryOfView,
  queryRequestOf,
  type ListQuery,
} from "@/lib/records/queryModel";
import type { FormField, ListView, ObjectForm, QueriedRecord, RecordView } from "@/lib/records/types";
import { useSession } from "@/lib/session/SessionProvider";
import { BulkDeletePanel, BulkEditPanel, BulkSummary, type BulkReport, type Picked } from "./BulkPanels";
import { FilterBuilder } from "./FilterBuilder";
import { ImportPanel } from "./ImportPanel";
import { ListTable } from "./ListTable";
import { SaveViewPanel, ViewsRail } from "./ViewsRail";

type Panel = "filters" | "columns" | "save" | "bulk-edit" | "bulk-delete" | "import" | null;

interface Page {
  records: QueriedRecord[];
  hasMore: boolean;
  nextCursor?: string;
}

const PAGE_SIZES = [25, 50, 100];

/**
 * The list of the records of one object, for any object (Sprint 19, ADR-0129, ADR-0130). It draws what the API describes
 * for this member (`ObjectForm`) and asks the records through the query engine (`POST .../records/query`) or, when the
 * person types in the search box, through the ranked search (`POST .../records/search`); it decides nothing about which
 * records or fields the member may see, because the server only sends those.
 *
 * <p>What it offers: filters (only the fields and comparisons the server says exist), ordering by a column, the choice of
 * columns, paging by cursor (the engine gives no total), saved views (personal and shared), search, selecting rows for
 * a bulk change or delete, and a CSV import. The state of the list lives here and nowhere else; a saved view only fills
 * it in.
 *
 * @param newHref where the create form of the object is, or undefined when this screen has none
 * @param openHref where a record of the object opens
 * @param recordHref where a linked record of another object opens
 */
export function RecordList({
  objectApiName,
  form,
  newHref,
  openHref,
  recordHref,
}: {
  objectApiName: string;
  form: ObjectForm;
  newHref?: string;
  openHref: (id: string) => string;
  recordHref: (objectApiName: string, id: string) => string;
}) {
  const { state: session } = useSession();
  const canShare = session.status === "signedIn" && session.user.abilities.includes("views.share");
  const byName = useMemo(() => new Map(form.fields.map((field) => [field.apiName, field])), [form.fields]);

  const [query, setQuery] = useState<ListQuery>(() => ({ filters: [], sort: [], columns: defaultColumns(form) }));
  const [draft, setDraft] = useState<ListQuery>(query);
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState("");
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0] ?? 25);
  const [cursors, setCursors] = useState<string[]>([]);
  const [tick, setTick] = useState(0);
  const [panel, setPanel] = useState<Panel>(null);
  const [selected, setSelected] = useState<ReadonlyMap<string, RecordView>>(new Map());
  const [report, setReport] = useState<BulkReport | undefined>();
  const [views, setViews] = useState<ListView[] | undefined>();
  const [viewsFailure, setViewsFailure] = useState<string | undefined>();
  const [activeView, setActiveView] = useState<ListView | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveProblem, setSaveProblem] = useState<string | undefined>();

  // ---- the records ----

  const cursor = cursors[cursors.length - 1];
  const requestKey = JSON.stringify([query, search, pageSize, cursor ?? null, tick]);
  const [loaded, setLoaded] = useState<{ key: string; outcome: Outcome<Page> } | null>(null);

  useEffect(() => {
    let live = true;
    void call(() =>
      search
        ? api.POST("/api/v1/objects/{objectApiName}/records/search", {
            params: { path: { objectApiName } },
            body: { text: search, limit: pageSize, ...(cursor ? { cursor } : {}) },
          })
        : api.POST("/api/v1/objects/{objectApiName}/records/query", {
            params: { path: { objectApiName } },
            body: queryRequestOf(query, form, pageSize, cursor),
          }),
    ).then((outcome) => {
      if (live) {
        setLoaded({
          key: requestKey,
          outcome: outcome.ok
            ? {
                ok: true,
                data: {
                  records: outcome.data.data,
                  hasMore: outcome.data.pagination.hasMore,
                  nextCursor: outcome.data.pagination.nextCursor,
                },
              }
            : outcome,
        });
      }
    });
    return () => {
      live = false;
    };
  }, [requestKey, objectApiName, query, search, pageSize, cursor, form]);

  const current = loaded && loaded.key === requestKey ? loaded.outcome : undefined;
  // While the next answer is on its way the last page stays on screen (dimmed), so the list does not jump.
  const shown = current ?? loaded?.outcome;
  const page = shown?.ok ? shown.data : undefined;
  const pending = current === undefined;

  const columnFields = useMemo(
    () => query.columns.map((name) => byName.get(name)).filter((field): field is FormField => field !== undefined),
    [query.columns, byName],
  );
  const pageRecords = useMemo(() => (page ? page.records.map((entry) => entry.record) : []), [page]);
  const labels = useLookupLabels(objectApiName, columnFields, pageRecords);

  const titleField = form.titleField ? byName.get(form.titleField) : undefined;
  const titleOf = useCallback(
    (record: RecordView) => {
      const text = titleField ? formatValue(titleField, recordValue(record, titleField.apiName)) : "";
      return text !== "" ? text : `${form.label} ${record.id.slice(0, 8)}`;
    },
    [titleField, form.label],
  );

  // ---- saved views ----

  const loadViews = useCallback(async () => {
    const outcome = await call(() =>
      api.GET("/api/v1/objects/{objectApiName}/views", { params: { path: { objectApiName } } }),
    );
    if (outcome.ok) {
      setViews(outcome.data.data);
      setViewsFailure(undefined);
    } else {
      setViewsFailure("Saved views could not be read.");
    }
  }, [objectApiName]);

  useEffect(() => {
    void Promise.resolve().then(loadViews);
  }, [loadViews]);

  const modified = useMemo(() => {
    if (!activeView) {
      return false;
    }
    const stored = definitionOf(queryOfView(activeView.definition, form), form);
    return JSON.stringify(stored) !== JSON.stringify(definitionOf(query, form));
  }, [activeView, query, form]);

  function restart(next: ListQuery) {
    setQuery(next);
    setDraft(next);
    setCursors([]);
    setSelected(new Map());
  }

  function selectView(view: ListView | null) {
    setActiveView(view);
    setSearch("");
    setSearchText("");
    setChosen({});
    restart(view ? queryOfView(view.definition, form) : { filters: [], sort: [], columns: defaultColumns(form) });
  }

  async function saveView(label: string, scope: "PERSONAL" | "SHARED", overwrite: boolean) {
    setSaving(true);
    setSaveProblem(undefined);
    const definition = definitionOf(query, form);
    const outcome =
      overwrite && activeView
        ? await call(
            () =>
              api.PUT("/api/v1/objects/{objectApiName}/views/{viewId}", {
                params: { path: { objectApiName, viewId: activeView.id } },
                body: { label, scope, definition, version: activeView.version },
              }),
          )
        : await call(
            () =>
              api.POST("/api/v1/objects/{objectApiName}/views", {
                params: { path: { objectApiName } },
                body: { label, scope, definition },
              }),
          );
    setSaving(false);
    if (outcome.ok) {
      setActiveView(outcome.data.data);
      setPanel(null);
      await loadViews();
    } else {
      const problems = outcome.failure.fields ? Object.values(outcome.failure.fields).flat().join(" ") : "";
      setSaveProblem(outcome.failure.code === "CONCURRENT_MODIFICATION" ? "This view was changed by someone else. Reload the views and try again." : problems || outcome.failure.message);
    }
  }

  async function removeView() {
    if (!activeView) {
      return;
    }
    const outcome = await call(
      () =>
        api.DELETE("/api/v1/objects/{objectApiName}/views/{viewId}", {
          params: { path: { objectApiName, viewId: activeView.id }, query: { version: activeView.version } },
        }),
    );
    if (outcome.ok) {
      selectView(null);
      await loadViews();
    } else {
      setSaveProblem(outcome.failure.message);
      setPanel("save");
    }
  }

  // ---- what the person does ----

  function sortBy(field: FormField, additive: boolean) {
    const existing = query.sort.find((key) => key.field === field.apiName);
    const next = existing ? (existing.descending ? undefined : { field: field.apiName, descending: true }) : { field: field.apiName, descending: false };
    const others = query.sort.filter((key) => key.field !== field.apiName);
    // The engine takes two keys: when a third is added the oldest goes, so the column just clicked always counts.
    const sort = additive ? [...others, ...(next ? [next] : [])].slice(-2) : next ? [next] : [];
    restart({ ...query, sort });
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    setSearch(searchText.trim());
    setCursors([]);
    setSelected(new Map());
  }

  function toggleOne(record: RecordView) {
    setSelected((current) => {
      const next = new Map(current);
      if (next.has(record.id)) {
        next.delete(record.id);
      } else {
        next.set(record.id, record);
      }
      return next;
    });
  }

  function toggleAll(records: readonly RecordView[], on: boolean) {
    setSelected((current) => {
      const next = new Map(current);
      records.forEach((record) => (on ? next.set(record.id, record) : next.delete(record.id)));
      return next;
    });
  }

  function afterBulk(result: BulkReport) {
    setPanel(null);
    setReport(result);
    setSelected(new Map());
    setTick((value) => value + 1);
  }

  const picked: Picked[] = [...selected.values()].map((record) => ({ record, label: titleOf(record) }));
  const canChange = form.actions.update;
  const canDelete = form.actions.delete;
  const selectable = canChange || canDelete;
  const chips = query.raw
    ? [{ key: "raw", text: "A filter of the view" }]
    : query.filters.flatMap((row) => {
        const field = byName.get(row.field);
        return field ? [{ key: row.key, text: describeRow(row, field) }] : [];
      });

  return (
    <div className="stack record-list" data-testid="record-list">
      <div className="list-head">
        <h1 className="entry-title">{form.pluralLabel}</h1>
        <div className="filters-actions">
          {form.actions.create && newHref ? (
            <Link href={newHref} className="button" data-testid="new-record">
              New {form.label.toLowerCase()}
            </Link>
          ) : null}
          {form.actions.create ? (
            <button type="button" className="button button-secondary" onClick={() => setPanel(panel === "import" ? null : "import")}>
              Import
            </button>
          ) : null}
        </div>
      </div>
      {report ? <BulkSummary report={report} form={form} onClose={() => setReport(undefined)} /> : null}
      <div className="split">
        <ViewsRail views={views} failure={viewsFailure} activeId={activeView?.id ?? null} modified={modified} onSelect={selectView} />
        <div className="split-content">
          <div className="list-toolbar">
            {form.searchEnabled ? (
              <form className="search-row" role="search" onSubmit={submitSearch}>
                <label className="visually-hidden" htmlFor="record-search">
                  Search {form.pluralLabel}
                </label>
                <input
                  id="record-search"
                  type="search"
                  placeholder={`Search ${form.pluralLabel.toLowerCase()}`}
                  value={searchText}
                  onChange={(event) => setSearchText(event.target.value)}
                />
                <button type="submit" className="button button-secondary">
                  Search
                </button>
                {search ? (
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => {
                      setSearch("");
                      setSearchText("");
                      setCursors([]);
                    }}
                  >
                    Clear search
                  </button>
                ) : null}
              </form>
            ) : null}
            <div className="filters-actions">
              <button
                type="button"
                className="button button-secondary"
                aria-expanded={panel === "filters"}
                onClick={() => {
                  setDraft(query);
                  setPanel(panel === "filters" ? null : "filters");
                }}
              >
                Filters{chips.length > 0 ? ` (${chips.length})` : ""}
              </button>
              <button
                type="button"
                className="button button-secondary"
                aria-expanded={panel === "columns"}
                onClick={() => setPanel(panel === "columns" ? null : "columns")}
              >
                Columns
              </button>
              <button
                type="button"
                className="button button-secondary"
                onClick={() => {
                  setSaveProblem(undefined);
                  setPanel("save");
                }}
              >
                Save view
              </button>
              {activeView && (activeView.mine || canShare) ? (
                <button type="button" className="link-button" onClick={() => void removeView()}>
                  Delete this view
                </button>
              ) : null}
            </div>
          </div>
          {search ? (
            <p className="hint" data-testid="search-note">
              Showing the best matches for “{search}”. Filters and ordering apply again when the search is cleared.
            </p>
          ) : null}
          {chips.length > 0 && !search ? (
            <ul className="chips" aria-label="Active filters">
              {chips.map((chip) => (
                <li key={chip.key} className="badge badge-on">
                  {chip.text}
                </li>
              ))}
            </ul>
          ) : null}
          {panel === "filters" ? (
            <FilterBuilder
              objectApiName={objectApiName}
              form={form}
              draft={draft}
              chosen={chosen}
              onChosen={(key, label) => setChosen((current) => ({ ...current, [key]: label }))}
              onChange={setDraft}
              onApply={() => {
                restart(draft);
                setPanel(null);
              }}
              onClear={() => {
                const cleared = { ...query, filters: [], raw: undefined };
                restart(cleared);
                setPanel(null);
              }}
            />
          ) : null}
          {panel === "columns" ? (
            <section aria-labelledby="columns-heading" data-testid="columns-panel">
              <h2 id="columns-heading">Columns</h2>
              <div className="check-list">
                {form.fields.map((field) => (
                  <label key={field.apiName}>
                    <input
                      type="checkbox"
                      checked={query.columns.includes(field.apiName)}
                      onChange={(event) => {
                        const columns = event.target.checked
                          ? [...query.columns, field.apiName]
                          : query.columns.filter((name) => name !== field.apiName);
                        if (columns.length > 0) {
                          setQuery({ ...query, columns });
                          setDraft({ ...query, columns });
                        }
                      }}
                    />
                    {field.label}
                  </label>
                ))}
              </div>
              <button type="button" className="button button-secondary" onClick={() => setPanel(null)}>
                Done
              </button>
            </section>
          ) : null}
          {panel === "save" ? (
            <SaveViewPanel
              key={activeView?.id ?? "new"}
              existing={activeView ?? undefined}
              canShare={canShare}
              busy={saving}
              problem={saveProblem}
              onSave={(label, scope, overwrite) => void saveView(label, scope, overwrite)}
              onCancel={() => setPanel(null)}
            />
          ) : null}
          {panel === "import" ? (
            <ImportPanel
              objectApiName={objectApiName}
              form={form}
              onFinished={() => setTick((value) => value + 1)}
              onClose={() => setPanel(null)}
            />
          ) : null}
          {selected.size > 0 ? (
            <div className="bulk-bar" role="region" aria-label="Selected records" data-testid="bulk-bar">
              <strong>
                {selected.size} selected
              </strong>
              {canChange ? (
                <button type="button" className="button button-secondary" onClick={() => setPanel("bulk-edit")}>
                  Change…
                </button>
              ) : null}
              {canDelete ? (
                <button type="button" className="button button-danger" onClick={() => setPanel("bulk-delete")}>
                  Delete…
                </button>
              ) : null}
              <button type="button" className="link-button" onClick={() => setSelected(new Map())}>
                Clear selection
              </button>
            </div>
          ) : null}
          {panel === "bulk-edit" && selected.size > 0 ? (
            <BulkEditPanel objectApiName={objectApiName} form={form} picked={picked} onDone={afterBulk} onCancel={() => setPanel(null)} />
          ) : null}
          {panel === "bulk-delete" && selected.size > 0 ? (
            <BulkDeletePanel objectApiName={objectApiName} picked={picked} onDone={afterBulk} onCancel={() => setPanel(null)} />
          ) : null}
          {shown && !shown.ok ? (
            <div className="form-message" role="alert" data-testid="list-failed">
              <p>{shown.failure.message}</p>
              <button type="button" className="link-button" onClick={() => setTick((value) => value + 1)}>
                Try again
              </button>
            </div>
          ) : null}
          {page === undefined && shown === undefined ? (
            <div className="custom-page-loading" role="status" aria-live="polite" data-testid="list-loading">
              <span className="skeleton-line" />
              <span className="skeleton-line skeleton-short" />
              <span className="visually-hidden">Loading</span>
            </div>
          ) : null}
          {page && page.records.length === 0 ? (
            <div className="empty-state" role="status" data-testid="list-empty">
              <p>
                <strong>{search || chips.length > 0 ? "No records match." : `No ${form.pluralLabel.toLowerCase()} yet.`}</strong>
              </p>
              {form.actions.create && newHref && !search && chips.length === 0 ? <Link href={newHref}>Create the first one</Link> : null}
            </div>
          ) : null}
          {page && page.records.length > 0 ? (
            <div className={pending ? "list-pending" : undefined} aria-busy={pending}>
              <ListTable
                columns={columnFields}
                records={page.records}
                labels={labels}
                sort={query.sort}
                onSort={sortBy}
                selected={selected}
                onToggle={toggleOne}
                onToggleAll={toggleAll}
                selectable={selectable}
                recordHref={recordHref}
                openHref={openHref}
                searching={search !== ""}
              />
              <div className="pager" role="navigation" aria-label="Pages">
                <button
                  type="button"
                  className="button button-secondary"
                  disabled={cursors.length === 0}
                  onClick={() => setCursors((current) => current.slice(0, -1))}
                >
                  Previous
                </button>
                <span className="hint">Page {cursors.length + 1}</span>
                <button
                  type="button"
                  className="button button-secondary"
                  disabled={!page.hasMore || !page.nextCursor}
                  onClick={() => setCursors((current) => (page.nextCursor ? [...current, page.nextCursor] : current))}
                >
                  Next
                </button>
                <label className="page-size">
                  Rows per page
                  <select
                    value={pageSize}
                    onChange={(event) => {
                      setPageSize(Number(event.target.value));
                      setCursors([]);
                    }}
                  >
                    {PAGE_SIZES.map((size) => (
                      <option key={size} value={size}>
                        {size}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
