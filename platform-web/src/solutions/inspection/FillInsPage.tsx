"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { ListPage, type ListColumn } from "@/components/ui/lists/ListPage";
import { useListViews } from "@/components/ui/lists/useListViews";
import { Tag } from "@/components/ui/lists/Tag";
import { useListRoute } from "@/components/ui/lists/useListRoute";
import { fillStatusTag } from "@/lib/sheets/fillTags";
import { FillInDetail } from "./FillInDetail";
import { fillInsHref, queryFillIns, startFillIn, useFillInOptions, type FillInRow } from "./fillIns";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

/**
 * The page of the "Fill-ins" entry (Functional track FF1, ADR-0192): the fill-ins the member may see, newest first, with
 * the status of each, one search box, a click on a header to order the whole result, a view menu, and one fill-in opened
 * in the same pane. A person sees their own; a person who
 * may view every fill-in sees all of them. The list never carries the cells, and "Show more" continues from the last
 * fill-in seen, never from a count, so it is as quick at the thousandth as at the first. A fill-in is started from its
 * template, not here.
 *
 * <p>It asks the API for everything and decides nothing. A link from a template narrows the list to that template
 * (`?template=`); the narrowing is the API's filter, not a rule of the screen. The search, the order, the filter and the
 * columns belong to the view in use (`useListViews`, UI3A) and are answered by the server for the whole result.
 */
export function FillInsPage() {
  return (
    <Suspense fallback={null}>
      <FillInsList />
    </Suspense>
  );
}

type Loaded =
  | { status: "loading" }
  | { status: "ready"; rows: FillInRow[]; next?: string }
  | { status: "failed"; message: string };

function when(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "";
}

/**
 * The list of fill-ins. As a page it opens a row in the same pane; as the "Fill-ins" tab of one template (`embedded`) it
 * has no title band, links each row to the fill-in's own address, and puts "Start a fill-in" where New is when the API
 * says a fill-in can be started on the version in use.
 */
export function FillInsList({ templateId: given, embedded = false }: { templateId?: string; embedded?: boolean }) {
  const route = useListRoute({ keep: ["template"] });
  const params = useSearchParams();
  const router = useRouter();
  const templateId = given ?? params?.get("template") ?? "";
  const options = useFillInOptions(embedded ? templateId : "", 0);
  const [startMessage, setStartMessage] = useState<string | undefined>();
  const [starting, setStarting] = useState(false);
  const [result, setResult] = useState<{ key: string; loaded: Loaded } | null>(null);
  const [more, setMore] = useState(false);

  const views = useListViews({
    objectApiName: "InspectionFill",
    displayable: ["status"],
    defaults: ["status"],
    primaryField: "name",
    allLabel: "All fill-ins",
  });
  const request = views.request;
  const key = `${templateId}|${views.requestKey}`;
  const loaded: Loaded = result && result.key === key ? result.loaded : { status: "loading" };

  useEffect(() => {
    if ((!embedded && route.openId) || !views.ready) {
      return;
    }
    let live = true;
    void queryFillIns(request, templateId).then((outcome) => {
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
  }, [request, templateId, key, route.openId, embedded, views.ready]);

  const showMore = async () => {
    if (loaded.status !== "ready" || !loaded.next) {
      return;
    }
    setMore(true);
    const outcome = await queryFillIns(request, templateId, loaded.next);
    setMore(false);
    setResult({
      key,
      loaded: outcome.ok
        ? { status: "ready", rows: [...loaded.rows, ...outcome.data.rows], next: outcome.data.nextCursor ?? undefined }
        : { status: "failed", message: outcome.failure.message },
    });
  };

  const start = async () => {
    setStarting(true);
    setStartMessage(undefined);
    const outcome = await startFillIn(templateId);
    setStarting(false);
    if (outcome.ok) {
      router.push(fillInsHref(outcome.data.fillId));
      return;
    }
    setStartMessage(outcome.failure.message);
  };
  const canStart = embedded && options.status === "ready" && options.options.canStart;

  if (!embedded && route.openId) {
    return <FillInDetail id={route.openId} onBack={() => route.close()} />;
  }

  const statusColumn: ListColumn<FillInRow> = {
    key: "status",
    header: "Status",
    cell: (row) => {
      const tag = fillStatusTag(row.status);
      return (
        <>
          <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>
          {row.archivedAt ? <Tag tone="quiet">Archived</Tag> : null}
        </>
      );
    },
    kind: "tag",
    sort: (row) => row.status,
  };
  const ready = loaded.status === "ready" ? loaded : undefined;
  return (
    <>
    <ListPage<FillInRow>
      title="Fill-ins"
      titleControl={views.titleControl}
      remote={views.remote}
      items={ready?.rows}
      failure={loaded.status === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.fillId}
      primaryHeader="Name"
      sortPrimary
      searchLabel="Search fill-ins by name or status"
      primary={(row) => row.name || "Fill-in"}
      primaryExtra={(row) => (row.filledBy ? <span className="hint">{row.filledBy}</span> : null)}
      menu={views.menu}
      columns={[
        {
          key: "version",
          header: "Version",
          cell: (row) => (row.versionInUse ? `v${row.versionNo}` : `v${row.versionNo} (a newer version is in use)`),
          kind: "muted",
        },
        { key: "rows", header: "Rows", cell: (row) => String(row.rowCount), kind: "muted" },
        {
          key: "createdAt",
          header: "Started",
          cell: (row) => when(row.createdAt),
          kind: "muted",
          sort: (row) => row.createdAt ?? "",
        },
        {
          key: "sent",
          header: "Sent",
          cell: (row) => when(row.submittedAt) || "Not sent",
          kind: "muted",
        },
        ...(views.columns.includes("status") ? [statusColumn] : []),
      ]}
      embedded={embedded}
      onOpen={embedded ? undefined : (row) => route.open(row.fillId)}
      openHref={embedded ? (row) => fillInsHref(row.fillId) : undefined}
      onNew={canStart && !starting ? () => void start() : undefined}
      newLabel="Start a fill-in"
      notices={
        startMessage ? (
          <p className="form-message" role="alert">
            {startMessage}
          </p>
        ) : undefined
      }
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
        embedded
          ? "No fill-ins on this template yet."
          : templateId
          ? "No fill-in matches."
          : "There are no fill-ins you can see yet. Open a template that is published to you and start one."
      }
      testId="fill-ins-list"
    />
    {views.dialogs}
    </>
  );
}
