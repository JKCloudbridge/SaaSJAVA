"use client";

import { useCallback, useEffect, useState } from "react";
import { api, REQUEST_ID_HEADER, TRACE_ID_HEADER } from "@/lib/api/client";
import { failureFromNetworkError, failureFromResponse, type ApiFailure } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";

type PlatformStatus = components["schemas"]["PlatformStatus"];

type State =
  | { kind: "loading" }
  | { kind: "ok"; status: PlatformStatus; requestId?: string; traceId?: string }
  | { kind: "failed"; failure: ApiFailure };

/**
 * Asks the API whether it is up and can reach its database, and shows the answer. It runs in the browser on purpose:
 * the call carries the trace context and request ID that tie this page view to the API's logs and the database.
 * Nothing here decides anything; it displays what the backend says.
 */
export function PlatformStatusCard() {
  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback(async () => {
    setState({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/platform/status");
      if (data) {
        setState({
          kind: "ok",
          status: data.data,
          requestId: response.headers.get(REQUEST_ID_HEADER) ?? undefined,
          traceId: response.headers.get(TRACE_ID_HEADER) ?? undefined,
        });
      } else {
        setState({ kind: "failed", failure: failureFromResponse(error, response) });
      }
    } catch {
      setState({ kind: "failed", failure: failureFromNetworkError() });
    }
  }, []);

  useEffect(() => {
    // Fetching on mount is the point of this component; the state it sets is the loading and result state.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  return (
    <section className="card" aria-labelledby="status-heading" aria-live="polite">
      <h2 id="status-heading">Platform status</h2>
      {state.kind === "loading" && <p>Checking the platform…</p>}
      {state.kind === "ok" && (
        <>
          <p>
            <span className="status status-ok">Up</span>
          </p>
          <dl>
            <dt>Service</dt>
            <dd>{state.status.service}</dd>
            <dt>API version</dt>
            <dd>{state.status.apiVersion}</dd>
            <dt>Server time</dt>
            <dd>
              <time dateTime={state.status.serverTime}>{state.status.serverTime}</time>
            </dd>
            <dt>Database time</dt>
            <dd>
              <time dateTime={state.status.databaseTime}>{state.status.databaseTime}</time>
            </dd>
            {state.requestId && (
              <>
                <dt>Request ID</dt>
                <dd className="mono">{state.requestId}</dd>
              </>
            )}
            {state.traceId && (
              <>
                <dt>Trace ID</dt>
                <dd className="mono">{state.traceId}</dd>
              </>
            )}
          </dl>
        </>
      )}
      {state.kind === "failed" && (
        <div role="alert">
          <p>
            <span className="status status-error">Unavailable</span>
          </p>
          <p>{state.failure.message}</p>
          <dl>
            <dt>Code</dt>
            <dd className="mono">{state.failure.code}</dd>
            {state.failure.requestId && (
              <>
                <dt>Request ID</dt>
                <dd className="mono">{state.failure.requestId}</dd>
              </>
            )}
          </dl>
        </div>
      )}
      <button type="button" className="button" onClick={() => void load()} disabled={state.kind === "loading"}>
        Check again
      </button>
    </section>
  );
}
