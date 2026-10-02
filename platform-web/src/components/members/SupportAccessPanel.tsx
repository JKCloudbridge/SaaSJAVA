"use client";

import { useCallback, useEffect, useState } from "react";
import { ActionMessages, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type Grant = components["schemas"]["SupportAccessView"];

type Loaded = { kind: "loading" } | { kind: "ok"; items: Grant[] } | { kind: "failed" };

/**
 * The requests of platform support staff for access to this organization, for its administrators: who asked, why, for
 * how long, and what became of it. An administrator approves (a window of at most four hours that ends by itself),
 * denies, or ends an approved access at once. Nobody on the platform has access without an approval here. The page shows
 * what the API answers; a member who is not an administrator gets the API's refusal on the page above and this part
 * stays empty.
 */
export function SupportAccessPanel() {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });

  const reload = useCallback(async () => {
    try {
      const { data } = await api.GET("/api/v1/support-access");
      setLoaded(data ? { kind: "ok", items: data.data } : { kind: "failed" });
    } catch {
      setLoaded({ kind: "failed" });
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  const decide = (grantId: string, action: "approve" | "deny" | "revoke") =>
    act(async () => {
      const params = { params: { path: { grantId } } };
      const { error, response } =
        action === "approve"
          ? await api.POST("/api/v1/support-access/{grantId}/approve", { ...params, body: {} })
          : action === "deny"
            ? await api.POST("/api/v1/support-access/{grantId}/deny", params)
            : await api.POST("/api/v1/support-access/{grantId}/revoke", params);
      const text =
        action === "approve"
          ? "Support access was approved for a limited time."
          : action === "deny"
            ? "The request was denied."
            : "The access was ended.";
      return { error, response, text };
    });

  if (loaded.kind === "failed") {
    return null;
  }

  return (
    <section aria-labelledby="support-access-heading" className="stack">
      <h2 id="support-access-heading">Support access</h2>
      <p>
        Platform support staff can only look at this organization after you approve a request, for a limited time. You can
        end it at any time.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="support" />
      {loaded.kind === "loading" ? <p aria-live="polite">Loading…</p> : null}
      {loaded.kind === "ok" && loaded.items.length === 0 ? <p>No requests.</p> : null}
      {loaded.kind === "ok" && loaded.items.length > 0 ? (
        <table className="table" data-testid="support-requests">
          <thead>
            <tr>
              <th>Who</th>
              <th>Why</th>
              <th>Asked for</th>
              <th>State</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {loaded.items.map((grant) => (
              <tr key={grant.id} data-testid="support-request-row">
                <td>{grant.requester}</td>
                <td>{grant.reason}</td>
                <td>{grant.requestedMinutes} minutes</td>
                <td>
                  {grant.active ? "ACTIVE" : grant.status}
                  {grant.accessExpiresAt ? ` until ${new Date(grant.accessExpiresAt).toLocaleString()}` : ""}
                </td>
                <td>
                  {grant.status === "REQUESTED" ? (
                    <>
                      <button
                        type="button"
                        className="link-button"
                        disabled={busy}
                        onClick={() => void decide(grant.id, "approve")}
                      >
                        Approve
                      </button>{" "}
                      <button
                        type="button"
                        className="link-button"
                        disabled={busy}
                        onClick={() => void decide(grant.id, "deny")}
                      >
                        Deny
                      </button>
                    </>
                  ) : null}
                  {grant.active ? (
                    <button
                      type="button"
                      className="link-button"
                      disabled={busy}
                      onClick={() => void decide(grant.id, "revoke")}
                    >
                      End now
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}
