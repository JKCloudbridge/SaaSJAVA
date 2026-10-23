"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { FieldPicker } from "@/components/records/FieldPicker";
import { FilterBuilder } from "@/components/records/FilterBuilder";
import { SaveViewPanel } from "@/components/records/ViewsRail";
import { Modal } from "@/components/ui/Modal";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { useObjectForm } from "@/lib/records/hooks";
import {
  definitionOf,
  filterNodeOf,
  queryOfView,
  sortableFields,
  type ListQuery,
} from "@/lib/records/queryModel";
import type { ListView, ObjectForm, QueryFilterNode } from "@/lib/records/types";
import { useOffers } from "@/lib/session/SessionProvider";
import type { ListMenuItem, ListRemote } from "./ListPage";
import { ViewMenu } from "./ViewMenu";

/** What a list asks the server for: the search text, the conditions and the order of the view in use. */
export interface ListRequest {
  search: string;
  filter?: QueryFilterNode;
  sort: { field: string; descending: boolean }[];
}

export interface ListViewsOptions {
  /** The object whose records the list shows; its saved views and its field description are the server's. */
  objectApiName: string;
  /** The fields a row carries besides the one of the first column: what "Select fields" may offer. */
  displayable: readonly string[];
  /** The fields shown before a view is chosen. */
  defaults: readonly string[];
  /** The field of the first column (always shown). */
  primaryField: string;
  /** The name of the list with no view chosen. */
  allLabel: string;
}

const NO_QUERY = (columns: readonly string[]): ListQuery => ({ filters: [], sort: [], columns: [...columns] });

function remembered(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function remember(key: string, id: string | null) {
  try {
    if (id) {
      window.localStorage.setItem(key, id);
    } else {
      window.localStorage.removeItem(key);
    }
  } catch {
    // Remembering the last view is a convenience; a browser that refuses it just opens the list with no view.
  }
}

/**
 * The views of a list (UI3A, ADR-0194): the saved views of the object (Sprint 19), the person's choice of columns, the
 * filter, the order and the search text, as one question the screen puts to the server. It owns the view menu that
 * replaces the title, the two popups behind the settings menu (the fields to show, the filter) and the popup that saves
 * a view. It decides nothing: the fields and comparisons offered are the server's description of the object, a view
 * grants nothing, and every request is judged again by the record store.
 */
export function useListViews(options: ListViewsOptions) {
  const { objectApiName, displayable, defaults, primaryField, allLabel } = options;
  // Only a courtesy: the button is drawn unless the API's own list of abilities is known and lacks it, and the API decides.
  const canShare = useOffers("views.share");
  const { state } = useObjectForm(objectApiName);
  const form: ObjectForm | undefined = state.status === "ready" ? state.form : undefined;

  const [views, setViews] = useState<ListView[] | undefined>();
  const [viewsFailure, setViewsFailure] = useState<string | undefined>();
  const [active, setActive] = useState<ListView | null>(null);
  const [query, setQuery] = useState<ListQuery>(NO_QUERY(defaults));
  const [search, setSearch] = useState("");
  const [dialog, setDialog] = useState<"fields" | "filter" | "save" | "delete" | null>(null);
  const [draft, setDraft] = useState<ListQuery>(NO_QUERY(defaults));
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState<string | undefined>();
  const restored = useRef(false);
  const storageKey = `list-view:${objectApiName}`;

  const loadViews = useCallback(async () => {
    const outcome = await call(() =>
      api.GET("/api/v1/objects/{objectApiName}/views", { params: { path: { objectApiName } } }),
    );
    if (outcome.ok && outcome.data?.data) {
      setViews(outcome.data.data);
      setViewsFailure(undefined);
    } else {
      setViews([]);
      setViewsFailure("Saved views could not be read.");
    }
  }, [objectApiName]);

  useEffect(() => {
    void Promise.resolve().then(loadViews);
  }, [loadViews]);

  const open = useCallback(
    (view: ListView | null, current: ObjectForm | undefined) => {
      setActive(view);
      setChosen({});
      setSearch("");
      remember(storageKey, view?.id ?? null);
      setQuery(view && current ? queryOfView(view.definition, current) : NO_QUERY(defaults));
    },
    [defaults, storageKey],
  );

  // The view the person had open last time opens again, once, when both the views and the description have arrived.
  useEffect(() => {
    if (restored.current || !views || !form) {
      return;
    }
    restored.current = true;
    const id = remembered(storageKey);
    const view = id ? views.find((one) => one.id === id) : undefined;
    if (view) {
      void Promise.resolve().then(() => open(view, form));
    }
  }, [views, form, storageKey, open]);

  const fields = useMemo(() => new Map((form?.fields ?? []).map((field) => [field.apiName, field])), [form]);
  const offered = useMemo(
    () => displayable.filter((name) => fields.has(name)),
    [displayable, fields],
  );
  const columns = useMemo(() => {
    const wanted = query.columns.filter((name) => offered.includes(name));
    return wanted;
  }, [query.columns, offered]);
  const sortable = useMemo(() => new Set(form ? sortableFields(form).map((field) => field.apiName) : []), [form]);

  const request = useMemo<ListRequest>(() => {
    const filter = filterNodeOf(query.filters, fields, query.raw);
    return {
      search: search.trim(),
      ...(filter ? { filter } : {}),
      sort: query.sort.filter((key) => sortable.has(key.field)).slice(0, 2),
    };
  }, [query, fields, sortable, search]);
  const requestKey = JSON.stringify(request);

  const modified = useMemo(() => {
    if (!form) {
      return false;
    }
    const base = active ? definitionOf(queryOfView(active.definition, form), form) : definitionOf(NO_QUERY(defaults), form);
    const now = definitionOf(query, form);
    // The columns a view never named fall back to the defaults, so only a real difference counts as a change.
    return JSON.stringify({ ...base, columns: base.columns?.length ? base.columns : [...defaults] }) !==
      JSON.stringify({ ...now, columns: now.columns?.length ? now.columns : [...defaults] });
  }, [active, query, form, defaults]);

  const order = query.sort[0]
    ? { key: query.sort[0].field, direction: query.sort[0].descending ? ("desc" as const) : ("asc" as const) }
    : null;

  const remote: ListRemote = {
    search,
    onSearch: setSearch,
    order,
    onOrder: (next) =>
      setQuery((current) => ({
        ...current,
        sort: next ? [{ field: next.key, descending: next.direction === "desc" }] : [],
      })),
    primaryKey: primaryField,
  };

  async function save(label: string, scope: "PERSONAL" | "SHARED", overwrite: boolean) {
    if (!form) {
      return;
    }
    setSaving(true);
    setProblem(undefined);
    const definition = definitionOf({ ...query, columns: columns.length > 0 ? columns : [...defaults] }, form);
    const outcome =
      overwrite && active
        ? await call(() =>
            api.PUT("/api/v1/objects/{objectApiName}/views/{viewId}", {
              params: { path: { objectApiName, viewId: active.id } },
              body: { label, scope, definition, version: active.version },
            }),
          )
        : await call(() =>
            api.POST("/api/v1/objects/{objectApiName}/views", {
              params: { path: { objectApiName } },
              body: { label, scope, definition },
            }),
          );
    setSaving(false);
    if (outcome.ok) {
      setActive(outcome.data.data);
      remember(storageKey, outcome.data.data.id);
      setDialog(null);
      await loadViews();
    } else {
      const places = outcome.failure.fields ? Object.values(outcome.failure.fields).flat().join(" ") : "";
      setProblem(
        outcome.failure.code === "CONCURRENT_MODIFICATION"
          ? "This view was changed by someone else. Reopen the list and try again."
          : places || outcome.failure.message,
      );
    }
  }

  async function remove() {
    if (!active) {
      return;
    }
    setSaving(true);
    const outcome = await call(() =>
      api.DELETE("/api/v1/objects/{objectApiName}/views/{viewId}", {
        params: { path: { objectApiName, viewId: active.id }, query: { version: active.version } },
      }),
    );
    setSaving(false);
    if (outcome.ok) {
      setDialog(null);
      open(null, form);
      await loadViews();
    } else {
      setProblem(outcome.failure.message);
    }
  }

  const menu: ListMenuItem[] = form
    ? [
        {
          label: "Select fields to display",
          onSelect: () => setDialog("fields"),
        },
        {
          label: "Filter",
          onSelect: () => {
            setDraft(query);
            setDialog("filter");
          },
        },
      ]
    : [];

  const titleControl: ReactNode = (
    <ViewMenu
      allLabel={allLabel}
      views={views}
      failure={viewsFailure}
      activeId={active?.id ?? null}
      activeLabel={active?.label}
      modified={modified}
      canEdit={Boolean(active && (active.mine || canShare))}
      onSelect={(view) => open(view, form)}
      onSave={() => {
        setProblem(undefined);
        setDialog("save");
      }}
      onDelete={() => {
        setProblem(undefined);
        setDialog("delete");
      }}
    />
  );

  const available = offered.map((name) => ({ apiName: name, label: fields.get(name)?.label ?? name }));
  const dialogs: ReactNode =
    dialog === "fields" ? (
      <Modal title="Select fields to display" onClose={() => setDialog(null)} testId="fields-dialog">
        <FieldPicker
          available={available}
          selected={columns.length > 0 ? columns : defaults.filter((name) => offered.includes(name))}
          onSave={(names) => {
            setQuery((current) => ({ ...current, columns: names }));
            setDialog(null);
          }}
          onCancel={() => setDialog(null)}
        />
      </Modal>
    ) : dialog === "filter" && form ? (
      <Modal title="Filter" onClose={() => setDialog(null)} testId="filter-dialog">
        <FilterBuilder
          objectApiName={objectApiName}
          form={form}
          draft={draft}
          chosen={chosen}
          onChosen={(rowKey, label) => setChosen((current) => ({ ...current, [rowKey]: label }))}
          onChange={setDraft}
          onApply={() => {
            setQuery((current) => ({ ...current, filters: draft.filters, raw: draft.raw }));
            setDialog(null);
          }}
          onClear={() => {
            setDraft({ ...draft, filters: [], raw: undefined });
            setChosen({});
          }}
        />
      </Modal>
    ) : dialog === "save" ? (
      <Modal title="Save this view" onClose={() => setDialog(null)} testId="save-view-dialog">
        <SaveViewPanel
          key={active?.id ?? "new"}
          existing={active ?? undefined}
          canShare={canShare}
          busy={saving}
          problem={problem}
          onSave={(label, scope, overwrite) => void save(label, scope, overwrite)}
          onCancel={() => setDialog(null)}
        />
      </Modal>
    ) : dialog === "delete" && active ? (
      <Modal title="Remove this view" onClose={() => setDialog(null)} testId="delete-view-dialog">
        <p>
          Remove the view “{active.label}”? The records are not touched; only this saved way of looking at them goes.
        </p>
        {problem ? (
          <p className="form-message" role="alert">
            {problem}
          </p>
        ) : null}
        <div className="filters-actions">
          <button type="button" className="button button-danger" onClick={() => void remove()} disabled={saving}>
            {saving ? "Removing…" : "Remove view"}
          </button>
          <button type="button" className="button button-secondary" onClick={() => setDialog(null)} disabled={saving}>
            Cancel
          </button>
        </div>
      </Modal>
    ) : null;

  return {
    /** False until the field description has arrived: the first question waits for it, so the first page is the right one. */
    ready: state.status !== "loading" || state.previous !== undefined,
    failure: state.status === "failed" ? state.message : undefined,
    columns: columns.length > 0 ? columns : defaults.filter((name) => offered.includes(name)),
    request,
    requestKey,
    remote,
    titleControl,
    menu,
    dialogs,
    form,
  };
}
