"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { DetailSection } from "@/components/ui/lists/DetailPage";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { failureFromResponse } from "@/lib/api/errors";
import { ActionWithReason } from "./ActionWithReason";
import { ActionMessages, failureText, useAction } from "./useAction";

type Limits = components["schemas"]["OrganizationLimitsView"];

/** A number typed in a box, or undefined for an empty or unreadable box (the default stays in force). */
function numberOf(text: string): number | undefined {
  const parsed = Number.parseInt(text.trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

/**
 * The limits on the public API of one organization, for a platform person (Sprint 32, ADR-0283): what is in force, the
 * platform's defaults beside it, and a form that sets other numbers with a reason or returns them to the defaults. The
 * numbers, the defaults and every refusal come from the API (it holds the ceilings): the page decides nothing, and the
 * reason is kept in the audit trail only. Numbers only: the page shows nothing of the organization's data.
 */
export function OrganizationLimitsPanel({ organizationId }: { organizationId: string }) {
  const [limits, setLimits] = useState<Limits | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [perMinute, setPerMinute] = useState("");
  const [perDay, setPerDay] = useState("");
  const [perApplication, setPerApplication] = useState("");
  const [reason, setReason] = useState("");

  const reload = useCallback(async () => {
    try {
      const answer = await api.GET("/api/v1/platform/organizations/{organizationId}/limits", {
        params: { path: { organizationId } },
      });
      if (!answer.data) {
        // An answer without a body is not a refusal: there is nothing to show yet.
        setFailure(answer.response.ok ? undefined : await failureText(answer.error, answer.response));
        return;
      }
      setFailure(undefined);
      setLimits(answer.data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [organizationId]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  const set = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void act(async () => {
      const result = await api.PUT("/api/v1/platform/organizations/{organizationId}/limits", {
        params: { path: { organizationId } },
        body: {
          organizationPerMinute: numberOf(perMinute),
          organizationPerDay: numberOf(perDay),
          applicationPerMinute: numberOf(perApplication),
          reason,
        },
      });
      if (result.response.ok) {
        setReason("");
      }
      const failed = result.response.ok ? undefined : failureFromResponse(result.error, result.response);
      return {
        error: result.error,
        response: result.response,
        text: "The limits were set.",
        problem: failed ? [failed.message, ...Object.values(failed.fields ?? {}).flat()].join(" ") : undefined,
      };
    });
  };

  const reset = (why: string) =>
    void act(async () => {
      const { error, response } = await api.POST("/api/v1/platform/organizations/{organizationId}/limits/reset", {
        params: { path: { organizationId } },
        body: { reason: why },
      });
      return { error, response, text: "The limits are the defaults again." };
    });

  return (
    <DetailSection title="Limits on the public API" stack>
      <div data-testid="organization-limits">
        <ActionMessages notice={notice} problem={problem} testId="organization-limits" />
        {failure ? (
          <p className="form-message" role="alert">
            {failure}
          </p>
        ) : null}
        {limits ? (
          <>
            <table className="table" data-testid="limits-table">
              <thead>
                <tr>
                  <th scope="col">Limit</th>
                  <th scope="col">In force</th>
                  <th scope="col">Default</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <th scope="row">Requests a minute, whole organization</th>
                  <td>{limits.organizationPerMinute}</td>
                  <td>{limits.defaultOrganizationPerMinute}</td>
                </tr>
                <tr>
                  <th scope="row">Requests a day, whole organization</th>
                  <td>{limits.organizationPerDay}</td>
                  <td>{limits.defaultOrganizationPerDay}</td>
                </tr>
                <tr>
                  <th scope="row">Requests a minute, each application and each person's token</th>
                  <td>{limits.applicationPerMinute}</td>
                  <td>{limits.defaultApplicationPerMinute}</td>
                </tr>
              </tbody>
            </table>
            <p className="hint">{limits.overridden ? "These numbers were set for this organization." : "The defaults are in force."}</p>
            <form className="form" onSubmit={set} noValidate aria-label="Set the limits">
              <div className="field">
                <label htmlFor="limit-minute">Requests a minute, whole organization</label>
                <input
                  id="limit-minute"
                  type="number"
                  min={1}
                  placeholder={String(limits.defaultOrganizationPerMinute ?? "")}
                  value={perMinute}
                  onChange={(event) => setPerMinute(event.target.value)}
                />
              </div>
              <div className="field">
                <label htmlFor="limit-day">Requests a day, whole organization</label>
                <input
                  id="limit-day"
                  type="number"
                  min={1}
                  placeholder={String(limits.defaultOrganizationPerDay ?? "")}
                  value={perDay}
                  onChange={(event) => setPerDay(event.target.value)}
                />
              </div>
              <div className="field">
                <label htmlFor="limit-application">Requests a minute, each application and each person's token</label>
                <input
                  id="limit-application"
                  type="number"
                  min={1}
                  placeholder={String(limits.defaultApplicationPerMinute ?? "")}
                  value={perApplication}
                  onChange={(event) => setPerApplication(event.target.value)}
                />
                <span className="hint">A box left empty keeps the default of that limit.</span>
              </div>
              <div className="field">
                <label htmlFor="limit-reason">Reason (kept in the audit trail, no personal data)</label>
                <input
                  id="limit-reason"
                  type="text"
                  maxLength={200}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                />
              </div>
              <div>
                <button type="submit" className="button" disabled={busy}>
                  Set the limits
                </button>
              </div>
            </form>
            {limits.overridden ? <ActionWithReason label="Return to the defaults" busy={busy} onSubmit={(why) => reset(why)} /> : null}
          </>
        ) : failure ? null : (
          <p aria-live="polite">Loading…</p>
        )}
      </div>
    </DetailSection>
  );
}
