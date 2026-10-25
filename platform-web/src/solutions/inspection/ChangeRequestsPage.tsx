"use client";

import { Suspense, useEffect, useState } from "react";
import { ListPage, type ListColumn, type ListRemote } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListRoute } from "@/components/ui/lists/useListRoute";
import { ChangeRequestPage } from "./ChangeRequestPage";
import {
  queryChangeRequests,
  statusWords,
  type ChangeRequestListRequest,
  type ChangeRequestRow,
} from "./changeRequests";
import { revisionNumber, revisionWords } from "@/lib/sheets/revision";

/**
 * The list of change requests, for the whole application (the entry "Change requests") or for one template (its tab of the
 * same name, with `templateId`). The page of the "Change requests" entry (Functional track FA6E, ADR-0199): the change requests of every template the member
 * may see, newest first. A person sees their own; a person who may change a template sees all the requests of it. Each row
 * says what the request is about (the template, its number and the title), who asked, from which department, which version
 * they read, when, and where the request stands. One search box (the title and the name of the person who asked), a click on
 * Version, Date or Status to order the whole result, and "Show more" continues from the last row seen. A row opens the
 * request on this page.
 *
 * <p>It asks the API for everything and decides nothing: who sees which request is the server's rule, and a page may arrive
 * short with a cursor because the server skips the requests the member may not see.
 */
export function ChangeRequestsPage() {
  return (
    <Suspense fallback={null}>
      <ChangeRequestsScreen />
    </Suspense>
  );
}

function ChangeRequestsScreen() {
  const route = useListRoute();
  return <ChangeRequestsList openId={route.openId} onOpen={route.open} onClose={route.close} />;
}

type Loaded =
  | { status: "loading" }
  | { status: "ready"; rows: ChangeRequestRow[]; next?: string }
  | { status: "failed"; message: string };

/** The columns the server can order by, and the word it knows each by. */
const ORDER_KEYS: Record<string, ChangeRequestListRequest["sort"]> = { date: "date", version: "version", status: "status" };
const PRIMARY_KEY = "template";

function when(iso: string): string {
  return new Date(iso).toLocaleDateString();
}

/**
 * The list itself and the request page it opens. Which request is open is the caller's: the entry keeps it in its address,
 * the tab of a template keeps it in the template's address. With `templateId` the list holds one template's requests and
 * does not repeat the template on every row.
 */
export function ChangeRequestsList({
  templateId,
  openId,
  onOpen,
  onClose,
  onChanged,
}: {
  templateId?: string;
  openId?: string | null;
  onOpen: (id: string) => void;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const [search, setSearch] = useState("");
  const [order, setOrder] = useState<ListRemote["order"]>(null);
  const [result, setResult] = useState<{ key: string; loaded: Loaded } | null>(null);
  const [more, setMore] = useState(false);

  const sort = order ? (ORDER_KEYS[order.key] ?? "date") : "date";
  // With no order chosen the newest comes first; a chosen order is high to low on the first click, as everywhere.
  const descending = order ? order.direction === "desc" : true;
  const request: ChangeRequestListRequest = { search, sort, descending, templateId };
  const key = `${templateId ?? ""}|${search}|${sort}|${descending}`;
  const loaded: Loaded = result && result.key === key ? result.loaded : { status: "loading" };

  useEffect(() => {
    if (openId) {
      return;
    }
    let live = true;
    void queryChangeRequests({ search, sort, descending, templateId }).then((outcome) => {
      if (live) {
        setResult({
          key,
          loaded: outcome.ok
            ? { status: "ready", rows: outcome.data.rows, next: outcome.data.nextCursor ?? undefined }
            : { status: "failed", message: outcome.failure.message },
        });
      }
    });
    return () => {
      live = false;
    };
  }, [search, sort, descending, templateId, key, openId]);

  const showMore = async () => {
    if (loaded.status !== "ready" || !loaded.next) {
      return;
    }
    setMore(true);
    const outcome = await queryChangeRequests(request, loaded.next);
    setMore(false);
    setResult({
      key,
      loaded: outcome.ok
        ? { status: "ready", rows: [...loaded.rows, ...outcome.data.rows], next: outcome.data.nextCursor ?? undefined }
        : { status: "failed", message: outcome.failure.message },
    });
  };

  if (openId) {
    return <ChangeRequestPage id={openId} onBack={onClose} onChanged={onChanged} />;
  }

  const columns: ListColumn<ChangeRequestRow>[] = [
    { key: "by", header: "Change requested by", cell: (row) => (row.mine ? "You" : (row.requesterName ?? "Someone who has left")) },
    { key: "department", header: "Department", cell: (row) => row.requesterDepartment ?? "—", kind: "muted" },
    { key: "version", header: "Revision", cell: (row) => `Rev ${revisionNumber(row.versionNo)}`, kind: "muted", sort: (row) => row.versionNo },
    { key: "date", header: "Date", cell: (row) => when(row.createdAt), kind: "muted", sort: (row) => row.createdAt },
    {
      key: "status",
      header: "Status",
      cell: (row) => {
        const tag = statusWords(row.status);
        return <Tag tone={tag.tone}>{tag.text}</Tag>;
      },
      kind: "tag",
      sort: (row) => row.status,
    },
  ];
  const ready = loaded.status === "ready" ? loaded : undefined;
  return (
    <ListPage<ChangeRequestRow>
      title="Change requests"
      remote={{ search, onSearch: setSearch, order, onOrder: setOrder, primaryKey: PRIMARY_KEY }}
      items={ready?.rows}
      failure={loaded.status === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.id}
      primaryHeader={templateId ? "Request" : "Template"}
      primary={(row) => (templateId ? row.title : (row.templateName ?? "Template"))}
      primaryExtra={
        templateId
          ? undefined
          : (row) => (
              <span className="hint">
                {row.templateNumber ? `${row.templateNumber} · ` : ""}
                {row.title}
              </span>
            )
      }
      columns={columns}
      searchLabel="Search change requests by title or by the person who asked"
      onOpen={(row) => onOpen(row.id)}
      embedded={templateId !== undefined}
      below={
        ready?.next ? (
          <div>
            <button type="button" className="button button-secondary" onClick={() => void showMore()} disabled={more}>
              {more ? "Loading…" : "Show more"}
            </button>
          </div>
        ) : undefined
      }
      emptyText={
        search.trim() === ""
          ? templateId
            ? "There are no change requests on this template that you can see yet."
            : "There are no change requests you can see yet. Open a template that is published to you and ask for a change."
          : "No change request matches."
      }
      testId="change-requests-list"
    />
  );
}
