"use client";

import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { PageFrame } from "@/components/ui/lists/PageFrame";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type Usage = components["schemas"]["UsageView"];
type LimitUsage = components["schemas"]["LimitUsageView"];

/** A time for a person; a window that has counted nothing has none. Interface wording only. */
function resets(limit: LimitUsage): string {
  return limit.resetsAt ? new Date(limit.resetsAt).toLocaleString() : "Nothing counted yet";
}

function LimitRows({ limits }: { limits: readonly LimitUsage[] }) {
  return (
    <>
      {limits.map((limit) => (
        <tr key={`${limit.key}-${limit.title}`} data-testid="usage-limit">
          <th scope="row">{limit.title}</th>
          <td>{`${limit.used ?? 0} of ${limit.limit ?? 0}`}</td>
          <td>{limit.remaining ?? 0}</td>
          <td>{resets(limit)}</td>
          <td>{limit.overridden ? "Set for this organization" : "Default"}</td>
        </tr>
      ))}
    </>
  );
}

/**
 * What the organization may ask of the public API and what it has used (Sprint 32, ADR-0280): the organization's own
 * limits and one limit for each connected application, in numbers. Everything on the page comes from the API, including
 * the limits themselves and the words that say what counts and what to do when a limit is reached; the page holds no
 * number of its own and decides nothing. Reading it costs nothing against the limits.
 */
export function UsagePanel() {
  const [usage, setUsage] = useState<Usage | undefined>();
  const [failure, setFailure] = useState<string | undefined>();

  const reload = useCallback(async () => {
    try {
      const answer = await api.GET("/api/v1/usage");
      if (!answer.data) {
        setFailure(await failureText(answer.error, answer.response));
        return;
      }
      setFailure(undefined);
      setUsage(answer.data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  return (
    <PageFrame title="API usage">
      <div data-testid="usage">
        {failure ? (
          <p className="form-message" role="alert">
            {failure}
          </p>
        ) : null}
        {!usage && !failure ? <p aria-live="polite">Loading…</p> : null}
        {usage ? (
          <>
            {usage.enabled ? null : (
              <p className="hint" data-testid="usage-off">
                The limits are switched off by the platform: nothing is refused for a limit.
              </p>
            )}
            <table className="table" data-testid="usage-organization">
              <thead>
                <tr>
                  <th scope="col">Limit</th>
                  <th scope="col">Used</th>
                  <th scope="col">Left</th>
                  <th scope="col">Window ends</th>
                  <th scope="col">Set by</th>
                </tr>
              </thead>
              <tbody>
                <LimitRows limits={usage.organization} />
              </tbody>
            </table>
            <h2>Connected applications</h2>
            {usage.applications.length === 0 ? (
              <p className="hint" data-testid="usage-no-applications">
                There are no connected applications.
              </p>
            ) : (
              usage.applications.map((application) => (
                <table className="table" key={application.name} data-testid="usage-application">
                  <caption>{application.name}</caption>
                  <tbody>
                    <LimitRows limits={application.limits} />
                  </tbody>
                </table>
              ))
            )}
            <p className="hint" data-testid="usage-words">
              {usage.words}
            </p>
          </>
        ) : null}
      </div>
    </PageFrame>
  );
}
