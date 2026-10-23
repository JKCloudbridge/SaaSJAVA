"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type FormEvent } from "react";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListRoute } from "@/components/ui/lists/useListRoute";
import { fillStatusTag } from "@/lib/sheets/fillTags";
import { FillInDetail } from "./FillInDetail";
import { listFillIns, NO_FILL_FILTERS, type FillInFilters, type FillInRow } from "./fillIns";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

const STATUSES: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any status" },
  { value: "DRAFT", label: "Draft" },
  { value: "SUBMITTED", label: "Sent for review" },
  { value: "RETURNED", label: "Returned" },
  { value: "APPROVED", label: "Approved" },
];

/**
 * The page of the "Fill-ins" entry (Functional track FF1, ADR-0192): the fill-ins the member may see, newest first, with
 * the status of each and a filter by status, and one fill-in opened in the same pane. A person sees their own; a person who
 * may view every fill-in sees all of them. The list never carries the cells, and "Show more" continues from the last
 * fill-in seen, never from a count, so it is as quick at the thousandth as at the first. A fill-in is started from its
 * template, not here.
 *
 * <p>It asks the API for everything and decides nothing. A link from a template narrows the list to that template
 * (`?template=`); the narrowing is the API's filter, not a rule of the screen.
 */
export function FillInsPage() {
  return (
    <Suspense fallback={null}>
      <FillInsScreen />
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

function FillInsScreen() {
  const route = useListRoute({ keep: ["template"] });
  const params = useSearchParams();
  const templateId = params?.get("template") ?? "";
  const [draft, setDraft] = useState("");
  const [status, setStatus] = useState("");
  const [result, setResult] = useState<{ key: string; loaded: Loaded } | null>(null);
  const [more, setMore] = useState(false);

  const applied: FillInFilters = { ...NO_FILL_FILTERS, templateId, status };
  const key = JSON.stringify(applied);
  const loaded: Loaded = result && result.key === key ? result.loaded : { status: "loading" };

  useEffect(() => {
    if (route.openId) {
      return;
    }
    let live = true;
    void listFillIns({ templateId, status }).then((outcome) => {
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
  }, [templateId, status, key, route.openId]);

  const showMore = async () => {
    if (loaded.status !== "ready" || !loaded.next) {
      return;
    }
    setMore(true);
    const outcome = await listFillIns(applied, loaded.next);
    setMore(false);
    setResult({
      key,
      loaded: outcome.ok
        ? { status: "ready", rows: [...loaded.rows, ...outcome.data.rows], next: outcome.data.nextCursor ?? undefined }
        : { status: "failed", message: outcome.failure.message },
    });
  };

  const choose = (event: FormEvent) => {
    event.preventDefault();
    setStatus(draft);
  };

  if (route.openId) {
    return <FillInDetail id={route.openId} onBack={() => route.close()} />;
  }

  const filters = (
    <form onSubmit={choose} aria-label="Filter the fill-ins" className="filters" data-testid="fill-in-filters">
      <label>
        Status
        <select value={draft} onChange={(event) => setDraft(event.target.value)}>
          {STATUSES.map((choice) => (
            <option key={choice.value} value={choice.value}>
              {choice.label}
            </option>
          ))}
        </select>
      </label>
      <div className="filters-actions">
        <button type="submit" className="button">
          Show
        </button>
      </div>
    </form>
  );

  const ready = loaded.status === "ready" ? loaded : undefined;
  return (
    <ListPage<FillInRow>
      title="Fill-ins"
      intro="A fill-in is a template filled in by a person. You see the fill-ins you made, and the ones you are allowed to check."
      items={ready?.rows}
      failure={loaded.status === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.fillId}
      primaryHeader="Name"
      primary={(row) => row.name || "Fill-in"}
      primaryExtra={(row) => (row.filledBy ? <span className="hint">{row.filledBy}</span> : null)}
      columns={[
        {
          key: "version",
          header: "Version",
          cell: (row) => (row.versionInUse ? `v${row.versionNo}` : `v${row.versionNo} (a newer version is in use)`),
          kind: "muted",
        },
        { key: "rows", header: "Rows", cell: (row) => String(row.rowCount), kind: "muted" },
        { key: "started", header: "Started", cell: (row) => when(row.createdAt), kind: "muted" },
        { key: "sent", header: "Sent", cell: (row) => when(row.submittedAt) || "Not sent", kind: "muted" },
        {
          key: "status",
          header: "Status",
          cell: (row) => {
            const tag = fillStatusTag(row.status);
            return <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>;
          },
          kind: "tag",
        },
      ]}
      onOpen={(row) => route.open(row.fillId)}
      above={filters}
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
        status || templateId
          ? "No fill-in matches."
          : "There are no fill-ins you can see yet. Open a template that is published to you and start one."
      }
      testId="fill-ins-list"
    />
  );
}
