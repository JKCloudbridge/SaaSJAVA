"use client";

import { Suspense, useEffect, useState } from "react";
import { RecordFormPage } from "@/components/records/RecordFormPage";
import { Modal } from "@/components/ui/Modal";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListRoute } from "@/components/ui/lists/useListRoute";
import { listTemplates, NO_FILTERS, type TemplateRow } from "./api";
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
 * so two people see different lists from the same data; the lifecycle comes from the API. The search and the ordering
 * work on the rows read so far; "Show more" reads the next page.
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

function TemplatesScreen() {
  const route = useListRoute();
  const [result, setResult] = useState<{ key: string; loaded: Loaded } | null>(null);
  const [more, setMore] = useState(false);
  const applied = NO_FILTERS;

  // The page that was read is kept with the question it answers; an answer to another question is no answer yet.
  const key = JSON.stringify(applied);
  const loaded: Loaded = result && result.key === key ? result.loaded : { status: "loading" };

  useEffect(() => {
    if (route.openId) {
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
  }, [applied, key, route.openId]);

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

  if (route.openId) {
    return <TemplateDetail id={route.openId} onBack={() => route.close()} />;
  }

  const ready = loaded.status === "ready" ? loaded : undefined;
  return (
    <>
      <ListPage<TemplateRow>
        title="Templates"
        items={ready?.rows}
        failure={loaded.status === "failed" ? loaded.message : undefined}
        rowKey={(row) => row.id}
        primaryHeader="Name"
        sortPrimary
        primary={(row) => text(row.name) || "Untitled template"}
        columns={[
          {
            key: "number",
            header: "Number",
            cell: (row) => text(row.number) || "Not numbered yet",
            kind: "muted",
            sort: (row) => text(row.number),
          },
          { key: "category", header: "Category", cell: (row) => text(row.category), kind: "muted", sort: (row) => text(row.category) },
          {
            key: "subCategory",
            header: "Sub-category",
            cell: (row) => text(row.subCategory),
            kind: "muted",
            sort: (row) => text(row.subCategory),
          },
          {
            key: "lifecycle",
            header: "Versions",
            cell: (row) => lifecycleText(row.lifecycle),
            kind: "muted",
            sort: (row) => lifecycleText(row.lifecycle),
          },
          {
            key: "state",
            header: "Status",
            cell: (row) => {
              const tag = stateTag(row.lifecycle.state);
              return <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>;
            },
            kind: "tag",
            sort: (row) => stateTag(row.lifecycle.state).text,
          },
        ]}
        searchText={(row) =>
          [text(row.number), text(row.name), text(row.category), text(row.subCategory), stateTag(row.lifecycle.state).text].join(" ")
        }
        searchLabel="Search templates by number, name, category, sub-category or status"
        onOpen={(row) => route.open(row.id)}
        onNew={ready?.canCreate ? route.create : undefined}
        newLabel="New template"
        below={
          ready ? (
            <>
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
        emptyText="There are no templates you can see yet."
        testId="templates-list"
      />
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
