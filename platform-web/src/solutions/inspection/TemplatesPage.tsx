"use client";

import { Suspense, useEffect, useState } from "react";
import { RecordFormPage } from "@/components/records/RecordFormPage";
import { Modal } from "@/components/ui/Modal";
import { ListPage, type ListColumn } from "@/components/ui/lists/ListPage";
import { useListViews } from "@/components/ui/lists/useListViews";
import { Tag } from "@/components/ui/lists/Tag";
import { useListRoute } from "@/components/ui/lists/useListRoute";
import { queryTemplates, type TemplateRow } from "./api";
import { lifecycleText, stateTag } from "./lifecycle";
import { TemplateDetail } from "./TemplateDetail";
import { TEMPLATE_OBJECT, text } from "./template";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

/**
 * The page of the "Templates" entry, the master list (Functional track FA3, FA4): every template the member may see with
 * its number, name, category, sub-category and lifecycle ("Active v3, v4 in review"), one search box over those columns,
 * a click on a header to order by it, a settings menu with New when the API says the member may create one, and one
 * template opened in the same pane.
 *
 * <p>It asks the API for everything and decides nothing. The list is the member's own question put to the record store,
 * so two people see different lists from the same data; the lifecycle comes from the API. The search, the order, the
 * filter and the columns belong to the view in use (`useListViews`, UI3A) and are answered by the server for the whole
 * result; "Show more" reads the next page of the same question. Versions and Status are always shown: they come from the
 * versions of a template, not from a field, so a view cannot hide or order them.
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
  | { status: "ready"; rows: TemplateRow[]; next?: string; canCreate: boolean }
  | { status: "failed"; message: string };

const FIELD_COLUMNS = ["number", "level", "documentType", "category", "subCategory"] as const;
const PRIMARY_FIELD = "name";

function TemplatesScreen() {
  const route = useListRoute();
  const views = useListViews({
    objectApiName: TEMPLATE_OBJECT,
    displayable: FIELD_COLUMNS,
    defaults: FIELD_COLUMNS,
    primaryField: PRIMARY_FIELD,
    allLabel: "All templates",
  });
  const [result, setResult] = useState<{ key: string; loaded: Loaded } | null>(null);
  const [more, setMore] = useState(false);

  // The page that was read is kept with the question it answers; an answer to another question is no answer yet.
  const key = views.requestKey;
  const loaded: Loaded = result && result.key === key ? result.loaded : { status: "loading" };
  const request = views.request;

  useEffect(() => {
    if (route.openId || !views.ready) {
      return;
    }
    let live = true;
    void queryTemplates(request).then((outcome) => {
      if (live) {
        setResult({
          key,
          loaded: outcome.ok
            ? {
                status: "ready",
                rows: outcome.data.rows,
                next: outcome.data.nextCursor ?? undefined,
                canCreate: outcome.data.canCreate,
              }
            : { status: "failed", message: outcome.failure.message },
        });
      }
    });
    return () => {
      live = false;
    };
  }, [request, key, route.openId, views.ready]);

  const showMore = async () => {
    if (loaded.status !== "ready" || !loaded.next) {
      return;
    }
    setMore(true);
    const outcome = await queryTemplates(request, loaded.next);
    setMore(false);
    setResult({
      key,
      loaded: outcome.ok
        ? {
            status: "ready",
            rows: [...loaded.rows, ...outcome.data.rows],
            next: outcome.data.nextCursor ?? undefined,
            canCreate: loaded.canCreate,
          }
        : { status: "failed", message: outcome.failure.message },
    });
  };

  if (route.openId) {
    return <TemplateDetail id={route.openId} onBack={() => route.close()} />;
  }

  const cells: Record<string, ListColumn<TemplateRow>> = {
    number: {
      key: "number",
      header: "Number",
      cell: (row) => text(row.number) || "Not numbered yet",
      kind: "muted",
      sort: (row) => text(row.number),
    },
    level: { key: "level", header: "Level", cell: (row) => text(row.level), kind: "muted", sort: (row) => text(row.level) },
    documentType: {
      key: "documentType",
      header: "Document",
      cell: (row) => text(row.documentType),
      kind: "muted",
      sort: (row) => text(row.documentType),
    },
    category: {
      key: "category",
      header: "Identity category",
      cell: (row) => text(row.category),
      kind: "muted",
      sort: (row) => text(row.category),
    },
    subCategory: {
      key: "subCategory",
      header: "Identity sub-category",
      cell: (row) => text(row.subCategory),
      kind: "muted",
      sort: (row) => text(row.subCategory),
    },
  };
  const chosen = views.columns.map((name) => cells[name]).filter((column): column is ListColumn<TemplateRow> => Boolean(column));

  const ready = loaded.status === "ready" ? loaded : undefined;
  return (
    <>
      <ListPage<TemplateRow>
        title="Templates"
        titleControl={views.titleControl}
        remote={views.remote}
        items={ready?.rows}
        failure={loaded.status === "failed" ? loaded.message : undefined}
        rowKey={(row) => row.id}
        primaryHeader="Name"
        sortPrimary
        primary={(row) => text(row.name) || "Untitled template"}
        columns={[
          ...chosen,
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
        searchLabel="Search templates by number, name, category or sub-category"
        onOpen={(row) => route.open(row.id)}
        onNew={ready?.canCreate ? route.create : undefined}
        newLabel="New"
        menu={views.menu}
        below={
          ready?.next ? (
            <div>
              <button type="button" className="button button-secondary" onClick={() => void showMore()} disabled={more}>
                {more ? "Loading…" : "Show more"}
              </button>
            </div>
          ) : undefined
        }
        emptyText="There are no templates you can see yet."
        testId="templates-list"
      />
      {views.dialogs}
      {route.creating ? (
        <Modal title="New template" onClose={() => route.close()}>
          <RecordFormPage
            objectApiName={TEMPLATE_OBJECT}
            listHref=""
            recordHref={(object, id) => `/records/${object}/${id}`}
            dialog={{ onSaved: (id) => route.open(id), onCancel: () => route.close() }}
          />
        </Modal>
      ) : null}
    </>
  );
}
