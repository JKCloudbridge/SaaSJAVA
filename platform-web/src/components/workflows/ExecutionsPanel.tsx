"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { DetailSection } from "@/components/ui/lists/DetailPage";
import { PageFrame } from "@/components/ui/lists/PageFrame";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useSession } from "@/lib/session/SessionProvider";
import {
  bulkReplayConfirmation,
  offersCancel,
  offersReplay,
  reasonText,
  replayResultText,
  statusText,
  STATUS_TEXT,
} from "@/lib/workflows/runsModel";

type Execution = components["schemas"]["WorkflowExecutionView"];
type Summary = components["schemas"]["WorkflowDeadLetterSummaryView"];
type Parked = components["schemas"]["ParkedEventView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type ReplayResult = components["schemas"]["WorkflowReplayResultView"];

interface Filters {
  workflow: string;
  object: string;
  status: string;
  dead: boolean;
  from: string;
  to: string;
}

const PAGE = 25;

/**
 * The runs of workflows (Sprint 23, ADR-0141, ADR-0143): a table with filters and paging, the dead letters that wait for a
 * person with their counts, the events the platform gave up on, and Replay and Cancel (one, and many at once). A button
 * appears only when the API's list of the caller's abilities names it, and it says exactly what it will do before it does it;
 * the API decides again, and a refusal is shown in its words. A run is named by its workflow and a link to the record; the
 * page shows no value of a record.
 */
export function ExecutionsPanel() {
  const search = useSearchParams();
  const { state: session } = useSession();
  const abilities = session.status === "signedIn" ? session.user.abilities : [];
  const canReplay = abilities.includes("workflows.replay");
  const canManage = abilities.includes("workflows.manage");

  const [filters, setFilters] = useState<Filters>({
    workflow: search.get("workflow") ?? "",
    object: search.get("object") ?? "",
    status: search.get("status") ?? "",
    dead: search.get("dead") === "true",
    from: "",
    to: "",
  });
  const [applied, setApplied] = useState<Filters>(filters);
  const [rows, setRows] = useState<Execution[] | undefined>();
  const [more, setMore] = useState<string | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [summary, setSummary] = useState<Summary | undefined>();
  const [parked, setParked] = useState<Parked[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const [picked, setPicked] = useState<string[]>([]);
  const [confirming, setConfirming] = useState<"replay" | undefined>();
  const [results, setResults] = useState<ReplayResult[] | undefined>();

  const ask = useCallback(
    async (cursor?: string) => {
      return api.GET("/api/v1/workflows/executions", {
        params: {
          query: {
            limit: PAGE,
            cursor,
            workflow: applied.workflow || undefined,
            object: applied.object || undefined,
            status: applied.dead ? undefined : applied.status || undefined,
            dead: applied.dead || undefined,
            from: applied.from ? new Date(applied.from).toISOString() : undefined,
            to: applied.to ? new Date(applied.to).toISOString() : undefined,
          },
        },
      });
    },
    [applied],
  );

  const reload = useCallback(async () => {
    try {
      const [page, objectList, dead, events] = await Promise.all([
        ask(),
        api.GET("/api/v1/metadata/objects"),
        api.GET("/api/v1/workflows/executions/dead-letters/summary"),
        api.GET("/api/v1/workflows/parked-events"),
      ]);
      if (!page.data) {
        setFailure(await failureText(page.error, page.response));
        return;
      }
      setFailure(undefined);
      setRows(page.data.data);
      setMore(page.data.pagination.hasMore ? page.data.pagination.nextCursor : undefined);
      setObjects(objectList.data?.data ?? []);
      setSummary(dead.data?.data);
      setParked(events.data?.data ?? []);
      setPicked([]);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [ask]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);
  const labelOfObject = useMemo(() => new Map(objects.map((one) => [one.apiName, one.label])), [objects]);

  if (failure) {
    return (
      <PageFrame title="Workflow runs">
        <p className="form-message" role="alert" data-testid="runs-failed">
          {failure}
        </p>
      </PageFrame>
    );
  }
  if (!rows) {
    return (
      <PageFrame title="Workflow runs">
        <p aria-live="polite">Loading…</p>
      </PageFrame>
    );
  }

  async function loadMore() {
    if (!more) {
      return;
    }
    try {
      const page = await ask(more);
      if (page.data) {
        setRows((now) => [...(now ?? []), ...page.data.data]);
        setMore(page.data.pagination.hasMore ? page.data.pagination.nextCursor : undefined);
      } else {
        setFailure(await failureText(page.error, page.response));
      }
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }

  function replayPicked() {
    setConfirming(undefined);
    void act(async () => {
      const result = await api.POST("/api/v1/workflows/executions/replay", { body: { executions: picked } });
      setResults(result.data?.data.results);
      const made = result.data?.data.results.filter((one) => one.outcome === "REPLAYED").length ?? 0;
      return {
        error: result.error,
        response: result.response,
        text: `${made} ${made === 1 ? "run was" : "runs were"} replayed. See the result of each below.`,
      };
    });
  }

  function cancel(execution: Execution) {
    void act(async () => {
      const result = await api.POST("/api/v1/workflows/executions/{executionId}/cancel", {
        params: { path: { executionId: execution.id } },
      });
      return { error: result.error, response: result.response, text: "The run was cancelled." };
    });
  }

  function redrive(event: Parked) {
    void act(async () => {
      const result = await api.POST("/api/v1/workflows/parked-events/{eventId}/redrive", {
        params: { path: { eventId: event.id } },
      });
      return { error: result.error, response: result.response, text: "The event was put back." };
    });
  }

  const replayable = rows.filter(offersReplay);
  const allPicked = replayable.length > 0 && replayable.every((row) => picked.includes(row.id));

  return (
    <PageFrame
      title="Workflow runs"
      testId="runs"
      tools={
        <Link className="button button-secondary" href="/setup/workflows">
          Workflows
        </Link>
      }
      notices={<ActionMessages notice={notice} problem={problem} testId="runs" />}
    >

      {summary && summary.total > 0 ? (
        <div data-testid="dead-letters">
          <DetailSection title={`${summary.total} ${summary.total === 1 ? "run needs" : "runs need"} a person`}>
          <table className="table">
            <thead>
              <tr>
                <th>Workflow</th>
                <th>Why</th>
                <th>Runs</th>
              </tr>
            </thead>
            <tbody>
              {summary.groups.map((group) => (
                <tr key={`${group.workflow}.${group.reason}`} data-testid="dead-letter-group">
                  <td>
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => {
                        const next = { ...filters, workflow: group.workflow, dead: true };
                        setFilters(next);
                        setApplied(next);
                      }}
                    >
                      <code>{group.workflow}</code>
                    </button>
                  </td>
                  <td>{reasonText(group.reason)}</td>
                  <td>{group.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </DetailSection>
        </div>
      ) : null}

      {parked.length > 0 ? (
        <div data-testid="parked-events">
          <DetailSection title="Events the platform gave up on">
          <p className="hint">
            These changes were saved, but the part of the platform that starts workflows kept failing on them. Putting one back
            lets it try again; nothing is done twice.
          </p>
          <ul>
            {parked.map((event) => (
              <li key={event.id} data-testid="parked-event">
                <code>{event.type}</code>, {new Date(event.occurredAt).toLocaleString()}, {event.attempts}{" "}
                {event.attempts === 1 ? "attempt" : "attempts"}
                {event.lastFailureType ? `, ${event.lastFailureType}` : ""}{" "}
                {canReplay ? (
                  <button type="button" className="link-button" disabled={busy} onClick={() => redrive(event)}>
                    Put it back
                  </button>
                ) : null}
              </li>
            ))}
          </ul>
          </DetailSection>
        </div>
      ) : null}

      <form
        className="filters"
        aria-label="Filter the runs"
        onSubmit={(event) => {
          event.preventDefault();
          setApplied(filters);
        }}
      >
        <div className="field">
          <label htmlFor="runs-workflow">Workflow</label>
          <input
            id="runs-workflow"
            value={filters.workflow}
            placeholder="API name"
            onChange={(event) => setFilters({ ...filters, workflow: event.target.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="runs-object">Object</label>
          <select
            id="runs-object"
            value={filters.object}
            onChange={(event) => setFilters({ ...filters, object: event.target.value })}
          >
            <option value="">Any object</option>
            {objects.map((one) => (
              <option key={one.apiName} value={one.apiName}>
                {one.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="runs-status">State</label>
          <select
            id="runs-status"
            value={filters.status}
            disabled={filters.dead}
            onChange={(event) => setFilters({ ...filters, status: event.target.value })}
          >
            <option value="">Any state</option>
            {Object.entries(STATUS_TEXT).map(([code, words]) => (
              <option key={code} value={code}>
                {words}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="runs-from">From</label>
          <input
            id="runs-from"
            type="datetime-local"
            value={filters.from}
            onChange={(event) => setFilters({ ...filters, from: event.target.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="runs-to">Until</label>
          <input
            id="runs-to"
            type="datetime-local"
            value={filters.to}
            onChange={(event) => setFilters({ ...filters, to: event.target.value })}
          />
        </div>
        <label>
          <input
            type="checkbox"
            checked={filters.dead}
            onChange={(event) => setFilters({ ...filters, dead: event.target.checked })}
          />{" "}
          Only runs that need a person
        </label>
        <button type="submit" className="button">
          Apply filters
        </button>
      </form>

      {canReplay && picked.length > 0 ? (
        <div className="card" role="group" aria-label="Replay the selected runs" data-testid="bulk-bar">
          {confirming ? (
            <>
              <p data-testid="bulk-confirmation">{bulkReplayConfirmation(picked.length)}</p>
              <button type="button" className="button" disabled={busy} onClick={replayPicked}>
                Yes, replay {picked.length}
              </button>{" "}
              <button type="button" className="link-button" onClick={() => setConfirming(undefined)}>
                Not now
              </button>
            </>
          ) : (
            <button type="button" className="button" disabled={busy} onClick={() => setConfirming("replay")}>
              Replay the {picked.length} selected
            </button>
          )}
        </div>
      ) : null}

      {results ? (
        <div data-testid="replay-results">
          <DetailSection title="Result of the replay">
          <ul>
            {results.map((result) => (
              <li key={result.execution} data-testid="replay-result">
                <Link href={`/workflows/executions/${result.execution}`}>Run</Link>: {replayResultText(result)}{" "}
                {result.replay ? <Link href={`/workflows/executions/${result.replay}`}>See the new run</Link> : null}
              </li>
            ))}
          </ul>
          </DetailSection>
        </div>
      ) : null}

      {rows.length === 0 ? (
        <p data-testid="runs-empty">No runs match.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              {canReplay ? (
                <th>
                  <input
                    type="checkbox"
                    aria-label="Select every run on this page that can be replayed"
                    checked={allPicked}
                    disabled={replayable.length === 0}
                    onChange={(event) => setPicked(event.target.checked ? replayable.map((row) => row.id) : [])}
                  />
                </th>
              ) : null}
              <th>Workflow</th>
              <th>Record</th>
              <th>State</th>
              <th>Queued</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-testid="run-row">
                {canReplay ? (
                  <td>
                    {offersReplay(row) ? (
                      <input
                        type="checkbox"
                        aria-label={`Select the run of ${row.workflow}`}
                        checked={picked.includes(row.id)}
                        onChange={(event) =>
                          setPicked(event.target.checked ? [...picked, row.id] : picked.filter((id) => id !== row.id))
                        }
                      />
                    ) : null}
                  </td>
                ) : null}
                <td>
                  <Link className="table-row-link" href={`/workflows/executions/${row.id}`}>
                    {row.workflow}
                  </Link>
                  {row.replayOf ? <div className="hint">A replay</div> : null}
                </td>
                <td>
                  <Link href={`/records/${encodeURIComponent(row.objectApiName)}/${row.recordId}`}>
                    Open the {labelOfObject.get(row.objectApiName) ?? row.objectApiName}
                  </Link>
                </td>
                <td>
                  <Tag>{statusText(row.status)}</Tag>
                  {row.reason ? <div className="hint">{reasonText(row.reason)}</div> : null}
                  {row.status === "QUEUED" && row.reason ? (
                    <div className="hint">Due {new Date(row.runAt).toLocaleString()}</div>
                  ) : null}
                </td>
                <td>{new Date(row.queuedAt).toLocaleString()}</td>
                <td>
                  {canManage && offersCancel(row) ? (
                    <button type="button" className="link-button" disabled={busy} onClick={() => cancel(row)}>
                      Cancel
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {more ? (
        <p>
          <button type="button" className="button button-secondary" onClick={() => void loadMore()}>
            Show more
          </button>
        </p>
      ) : null}
    </PageFrame>
  );
}
