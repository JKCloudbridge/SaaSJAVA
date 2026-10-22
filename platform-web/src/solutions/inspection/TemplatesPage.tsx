"use client";

import { usePathname } from "next/navigation";
import { Suspense, useEffect, useMemo, useState, type FormEvent } from "react";
import { RecordFormPage } from "@/components/records/RecordFormPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListRoute } from "@/components/ui/lists/useListRoute";
import { useObjectForm } from "@/lib/records/hooks";
import type { FormField } from "@/lib/records/types";
import { listTemplates, NO_FILTERS, type ListFilters, type TemplateRow } from "./api";
import { lifecycleText, stateTag } from "./lifecycle";
import { TemplateDetail } from "./TemplateDetail";
import { TEMPLATE_OBJECT, text } from "./template";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

const STATES: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any status" },
  { value: "ACTIVE", label: "Active" },
  { value: "APPROVED", label: "Approved, not published" },
  { value: "IN_REVIEW", label: "In review" },
  { value: "DRAFT", label: "Draft" },
  { value: "NONE", label: "No version yet" },
  { value: "RETIRED", label: "Retired" },
];

/**
 * The page of the "Templates" entry, the master list (Functional track FA3, FA4): every template the member may see with
 * its number, name, category, sub-category and lifecycle ("Active v3, v4 in review"), the filters the API offers, a New
 * button when the API says the member may create one, and one template opened in the same pane.
 *
 * <p>It asks the API for everything and decides nothing. The list is the member's own question put to the record store,
 * so two people see different lists from the same data; the lifecycle comes from the API. A status filter is applied by
 * the API to each page after it is read, so a page can hold fewer rows than usual while "Show more" still has some: the
 * screen says so instead of hiding it.
 */
export function TemplatesPage() {
  return (
    <Suspense fallback={null}>
      <TemplatesScreen />
    </Suspense>
  );
}

type Loaded =
  | { status: "loading" }
  | { status: "ready"; rows: TemplateRow[]; next?: string; canCreate: boolean; filtered: boolean }
  | { status: "failed"; message: string };

function choices(field: FormField | undefined): ReadonlyArray<{ value: string; label: string }> {
  return (field?.values ?? []).filter((choice) => choice.active).map((choice) => ({ value: choice.value, label: choice.label }));
}

function TemplatesScreen() {
  const route = useListRoute();
  const pathname = usePathname() ?? "";
  const { state: form } = useObjectForm(TEMPLATE_OBJECT);
  const [draft, setDraft] = useState<ListFilters>(NO_FILTERS);
  const [applied, setApplied] = useState<ListFilters>(NO_FILTERS);
  const [result, setResult] = useState<{ key: string; loaded: Loaded } | null>(null);
  const [more, setMore] = useState(false);

  const category = form.status === "ready" ? form.form.fields.find((field) => field.apiName === "category") : undefined;
  const subCategory = form.status === "ready" ? form.form.fields.find((field) => field.apiName === "subCategory") : undefined;
  const categories = useMemo(() => choices(category), [category]);
  const subCategories = useMemo(() => {
    const all = choices(subCategory);
    const allowed = draft.category === "" ? undefined : subCategory?.dependency?.allowed?.[draft.category];
    return allowed ? all.filter((choice) => allowed.includes(choice.value)) : all;
  }, [subCategory, draft.category]);

  // The page that was read is kept with the question it answers; an answer to another question is no answer yet.
  const key = JSON.stringify(applied);
  const loaded: Loaded = result && result.key === key ? result.loaded : { status: "loading" };

  useEffect(() => {
    if (route.openId || route.creating) {
      return;
    }
    let live = true;
    void listTemplates(applied).then((outcome) => {
      if (live) {
        setResult({
          key,
          loaded: outcome.ok
            ? {
                status: "ready",
                rows: outcome.data.rows,
                next: outcome.data.nextCursor ?? undefined,
                canCreate: outcome.data.canCreate,
                filtered: outcome.data.filtered,
              }
            : { status: "failed", message: outcome.failure.message },
        });
      }
    });
    return () => {
      live = false;
    };
  }, [applied, key, route.openId, route.creating]);

  const showMore = async () => {
    if (loaded.status !== "ready" || !loaded.next) {
      return;
    }
    setMore(true);
    const outcome = await listTemplates(applied, loaded.next);
    setMore(false);
    setResult({
      key,
      loaded: outcome.ok
        ? {
            status: "ready",
            rows: [...loaded.rows, ...outcome.data.rows],
            next: outcome.data.nextCursor ?? undefined,
            canCreate: loaded.canCreate,
            filtered: outcome.data.filtered,
          }
        : { status: "failed", message: outcome.failure.message },
    });
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    setApplied(draft);
  };
  const clear = () => {
    setDraft(NO_FILTERS);
    setApplied(NO_FILTERS);
  };
  const narrowed = JSON.stringify({ ...applied, sort: "name" }) !== JSON.stringify({ ...NO_FILTERS, sort: "name" });

  if (route.creating) {
    return (
      <RecordFormPage
        objectApiName={TEMPLATE_OBJECT}
        listHref={pathname}
        recordHref={(object, id) => `/records/${object}/${id}`}
        savedHref={(id) => `${pathname}?open=${id}`}
      />
    );
  }
  if (route.openId) {
    return <TemplateDetail id={route.openId} onBack={() => route.close()} />;
  }

  const filters = (
    <form onSubmit={search} aria-label="Filter the templates" className="filters" data-testid="template-filters">
      <label>
        Number or name
        <input value={draft.search} onChange={(event) => setDraft({ ...draft, search: event.target.value })} />
      </label>
      <label>
        Category
        <select
          value={draft.category}
          onChange={(event) => setDraft({ ...draft, category: event.target.value, subCategory: "" })}
        >
          <option value="">Any category</option>
          {categories.map((choice) => (
            <option key={choice.value} value={choice.value}>
              {choice.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Sub-category
        <select value={draft.subCategory} onChange={(event) => setDraft({ ...draft, subCategory: event.target.value })}>
          <option value="">Any sub-category</option>
          {subCategories.map((choice) => (
            <option key={choice.value} value={choice.value}>
              {choice.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Status
        <select value={draft.state} onChange={(event) => setDraft({ ...draft, state: event.target.value })}>
          {STATES.map((choice) => (
            <option key={choice.value} value={choice.value}>
              {choice.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Retired
        <select
          value={draft.retired}
          onChange={(event) => setDraft({ ...draft, retired: event.target.value as ListFilters["retired"] })}
        >
          <option value="">Retired or not</option>
          <option value="false">Not retired</option>
          <option value="true">Retired only</option>
        </select>
      </label>
      <label>
        Order
        <select value={draft.sort} onChange={(event) => setDraft({ ...draft, sort: event.target.value as ListFilters["sort"] })}>
          <option value="name">By name</option>
          <option value="number">By number</option>
          <option value="newest">Newest first</option>
        </select>
      </label>
      <div className="filters-actions">
        <button type="submit" className="button">
          Show
        </button>
        <button type="button" className="button button-secondary" onClick={clear}>
          Clear
        </button>
      </div>
    </form>
  );

  const ready = loaded.status === "ready" ? loaded : undefined;
  return (
    <ListPage<TemplateRow>
      title="Templates"
      intro="A template is a sheet people fill in later. You see the templates you wrote, the ones you approve and the ones published to you."
      items={ready?.rows}
      failure={loaded.status === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => text(row.name) || "Untitled template"}
      columns={[
        { key: "number", header: "Number", cell: (row) => text(row.number) || "Not numbered yet", kind: "muted" },
        { key: "category", header: "Category", cell: (row) => text(row.category), kind: "muted" },
        { key: "subCategory", header: "Sub-category", cell: (row) => text(row.subCategory), kind: "muted" },
        { key: "lifecycle", header: "Versions", cell: (row) => lifecycleText(row.lifecycle), kind: "muted" },
        {
          key: "state",
          header: "Status",
          cell: (row) => {
            const tag = stateTag(row.lifecycle.state);
            return <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>;
          },
          kind: "tag",
        },
      ]}
      onOpen={(row) => route.open(row.id)}
      onNew={ready?.canCreate ? route.create : undefined}
      newLabel="New template"
      above={filters}
      below={
        ready ? (
          <>
            {ready.filtered && ready.next ? (
              <p className="hint" role="note">
                The status filter is applied to each page after it is read, so this page may show fewer templates than
                usual. Show more to keep looking.
              </p>
            ) : null}
            {ready.next ? (
              <div>
                <button type="button" className="button button-secondary" onClick={() => void showMore()} disabled={more}>
                  {more ? "Loading…" : "Show more"}
                </button>
              </div>
            ) : null}
          </>
        ) : undefined
      }
      emptyText={narrowed ? "No template matches." : "There are no templates you can see yet."}
      testId="templates-list"
    />
  );
}
