"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ActionWithReason } from "./ActionWithReason";
import { ActionMessages, failureText, useAction } from "./useAction";

type Detail = components["schemas"]["PlatformOrganizationDetail"];
type Plan = components["schemas"]["PlanInfo"];
type Grant = components["schemas"]["SupportAccessView"];

type Loaded<T> = { kind: "loading" } | { kind: "ok"; value: T } | { kind: "failed"; message: string };

const STATUSES = ["TRIAL", "ACTIVE", "SUSPENDED", "CANCELLED"] as const;

/**
 * One organization as the console shows it: its state, subscription, licence pools, features, the first-administrator
 * invitation (never its address), the lifecycle actions and support access. It shows what the API answers and the API's
 * own words for every refusal; which of these a role may use is the API's decision (a role that may not gets the
 * refusal), never the page's. Nothing here shows a member or business data: the API does not send any.
 */
export function OrganizationDetail() {
  const params = useParams<{ organizationId: string }>();
  const organizationId = params.organizationId;
  const [detail, setDetail] = useState<Loaded<Detail>>({ kind: "loading" });
  const [plans, setPlans] = useState<Plan[]>([]);
  const [grants, setGrants] = useState<Grant[]>([]);
  const [reason, setReason] = useState("");

  const reload = useCallback(async () => {
    try {
      const [d, p, g] = await Promise.all([
        api.GET("/api/v1/platform/organizations/{organizationId}", { params: { path: { organizationId } } }),
        api.GET("/api/v1/platform/plans"),
        api.GET("/api/v1/platform/organizations/{organizationId}/support-access", {
          params: { path: { organizationId } },
        }),
      ]);
      setDetail(
        d.data
          ? { kind: "ok", value: d.data.data }
          : { kind: "failed", message: await failureText(d.error, d.response) },
      );
      setPlans(p.data?.data ?? []);
      setGrants(g.data?.data ?? []);
    } catch {
      setDetail({ kind: "failed", message: COMMON_TEXT.network });
    }
  }, [organizationId]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  if (detail.kind === "loading") {
    return <p aria-live="polite">Loading…</p>;
  }
  if (detail.kind === "failed") {
    return (
      <p className="form-message" role="alert" data-testid="organization-failed">
        {detail.message}
      </p>
    );
  }
  const organization = detail.value;
  const path = { organizationId };

  const lifecycle = (action: "suspend" | "reinstate" | "deactivate", text: string) => (why: string, confirm: string) =>
    void act(async () => {
      const call =
        action === "suspend"
          ? api.POST("/api/v1/platform/organizations/{organizationId}/suspend", {
              params: { path },
              body: { reason: why },
            })
          : action === "reinstate"
            ? api.POST("/api/v1/platform/organizations/{organizationId}/reinstate", {
                params: { path },
                body: { reason: why },
              })
            : api.POST("/api/v1/platform/organizations/{organizationId}/deactivate", {
                params: { path },
                body: { reason: why, confirm },
              });
      const { error, response } = await call;
      return { error, response, text };
    });

  const setPool = (licenceType: string, quantity: number) =>
    act(async () => {
      const { error, response } = await api.PUT("/api/v1/platform/organizations/{organizationId}/pools/{licenceType}", {
        params: { path: { organizationId, licenceType } },
        body: { quantity, reason },
      });
      return { error, response, text: "The pool was changed." };
    });

  const setEntitlement = (feature: string, enabled: boolean | undefined) =>
    act(async () => {
      const { error, response } = await api.PUT(
        "/api/v1/platform/organizations/{organizationId}/entitlements/{feature}",
        { params: { path: { organizationId, feature } }, body: { enabled, reason } },
      );
      return { error, response, text: "The feature was changed." };
    });

  const resend = () =>
    act(async () => {
      const { data, error, response } = await api.POST(
        "/api/v1/platform/organizations/{organizationId}/first-administrator/resend",
        { params: { path } },
      );
      return { error, response, text: data?.data.message };
    });

  return (
    <div className="stack" data-testid="organization-detail">
      <h2>{organization.displayName}</h2>
      <p>
        <span className="mono">{organization.slug}</span> · {organization.status} since{" "}
        {new Date(organization.statusChangedAt).toLocaleString()}
      </p>
      <ActionMessages notice={notice} problem={problem} testId="organization" />

      <div className="field">
        <label htmlFor="change-reason">Reason for the changes below (kept in the audit trail, no personal data)</label>
        <input
          id="change-reason"
          type="text"
          maxLength={200}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      </div>

      <SubscriptionSection
        subscription={organization.subscription}
        plans={plans}
        busy={busy}
        reason={reason}
        onChange={(body) =>
          void act(async () => {
            const { error, response } = await api.PUT("/api/v1/platform/organizations/{organizationId}/subscription", {
              params: { path },
              body,
            });
            return { error, response, text: "The subscription was changed." };
          })
        }
      />

      <section aria-labelledby="pools-heading">
        <h3 id="pools-heading">Licences</h3>
        {organization.pools.length === 0 ? <p>No licence pools.</p> : null}
        {organization.pools.length > 0 ? (
          <table className="table" data-testid="pools">
            <thead>
              <tr>
                <th>Type</th>
                <th>Held</th>
                <th>Assigned</th>
                <th>Free</th>
                <th>
                  <span className="visually-hidden">Change</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {organization.pools.map((pool) => (
                <PoolRow key={pool.licenceType} pool={pool} busy={busy} onSet={setPool} />
              ))}
            </tbody>
          </table>
        ) : null}
      </section>

      <section aria-labelledby="features-heading">
        <h3 id="features-heading">Features</h3>
        <table className="table" data-testid="entitlements">
          <thead>
            <tr>
              <th>Feature</th>
              <th>In the plan</th>
              <th>Switch</th>
              <th>Result</th>
              <th>
                <span className="visually-hidden">Change</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {organization.entitlements.map((feature) => (
              <tr key={feature.key}>
                <td>{feature.name}</td>
                <td>{feature.inPlan ? "yes" : "no"}</td>
                <td>{feature.override === undefined ? "none" : feature.override ? "on" : "off"}</td>
                <td>{feature.enabled ? "enabled" : "not enabled"}</td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => void setEntitlement(feature.key, true)}
                  >
                    Switch on
                  </button>{" "}
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => void setEntitlement(feature.key, false)}
                  >
                    Switch off
                  </button>{" "}
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => void setEntitlement(feature.key, undefined)}
                  >
                    Use the plan
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <FirstAdministratorSection
        info={organization.firstAdministrator}
        busy={busy}
        onResend={() => void resend()}
        onInvite={(email) =>
          void act(async () => {
            const { data, error, response } = await api.POST(
              "/api/v1/platform/organizations/{organizationId}/first-administrator",
              { params: { path }, body: { email } },
            );
            return { error, response, text: data?.data.message };
          })
        }
      />

      <section aria-labelledby="lifecycle-heading" className="stack">
        <h3 id="lifecycle-heading">Lifecycle</h3>
        <div>
          <ActionWithReason
            label="Suspend"
            busy={busy}
            onSubmit={lifecycle("suspend", "The organization was suspended. Everybody was signed out.")}
          />{" "}
          <ActionWithReason
            label="Reinstate"
            busy={busy}
            onSubmit={lifecycle("reinstate", "The organization was reinstated. People sign in again.")}
          />
        </div>
        <div>
          <ActionWithReason
            label="Close for good"
            busy={busy}
            confirmWith={organization.slug}
            onSubmit={lifecycle("deactivate", "The organization was closed for good.")}
          />
        </div>
        <div>
          <ActionWithReason
            label="Sign everybody out"
            busy={busy}
            onSubmit={(why) =>
              void act(async () => {
                const { error, response } = await api.POST(
                  "/api/v1/platform/organizations/{organizationId}/sign-out-all",
                  { params: { path }, body: { reason: why } },
                );
                return { error, response, text: "Everybody was signed out of the organization." };
              })
            }
          />
        </div>
      </section>

      <SupportAccessSection
        grants={grants}
        busy={busy}
        onRequest={(why, minutes) =>
          void act(async () => {
            const { error, response } = await api.POST(
              "/api/v1/platform/organizations/{organizationId}/support-access",
              { params: { path }, body: { reason: why, minutes } },
            );
            return { error, response, text: "The request was sent. Nothing is granted until the organization approves it." };
          })
        }
      />
    </div>
  );
}

function SubscriptionSection({
  subscription,
  plans,
  busy,
  reason,
  onChange,
}: {
  subscription: Detail["subscription"];
  plans: Plan[];
  busy: boolean;
  reason: string;
  onChange: (body: components["schemas"]["ChangeSubscriptionRequest"]) => void;
}) {
  const [planKey, setPlanKey] = useState("");
  const [status, setStatus] = useState("");
  const [trialEndsAt, setTrialEndsAt] = useState("");

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onChange({
      planKey: planKey || undefined,
      status: status || undefined,
      trialEndsAt: trialEndsAt ? new Date(trialEndsAt).toISOString() : undefined,
      reason,
    });
  }

  return (
    <section aria-labelledby="subscription-heading" className="stack">
      <h3 id="subscription-heading">Subscription</h3>
      {subscription ? (
        <p data-testid="subscription">
          Plan {subscription.planName} ({subscription.status})
          {subscription.trialEndsAt
            ? `, trial ${subscription.trialExpired ? "expired" : "ends"} ${new Date(subscription.trialEndsAt).toLocaleDateString()}`
            : ""}
          . A trial that is over is shown here; nothing switches off by itself.
        </p>
      ) : (
        <p data-testid="subscription">No plan yet.</p>
      )}
      <form className="form" onSubmit={submit} noValidate aria-label="Change the subscription">
        <div className="field">
          <label htmlFor="subscription-plan">Plan</label>
          <select id="subscription-plan" value={planKey} onChange={(event) => setPlanKey(event.target.value)}>
            <option value="">Keep</option>
            {plans.map((plan) => (
              <option key={plan.key} value={plan.key}>
                {plan.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="subscription-status">Status</label>
          <select id="subscription-status" value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">Keep</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="subscription-trial">End of the trial</label>
          <input
            id="subscription-trial"
            type="date"
            value={trialEndsAt}
            onChange={(event) => setTrialEndsAt(event.target.value)}
          />
        </div>
        <button type="submit" className="button" disabled={busy}>
          Change the subscription
        </button>
      </form>
    </section>
  );
}

function PoolRow({
  pool,
  busy,
  onSet,
}: {
  pool: components["schemas"]["LicencePoolView"];
  busy: boolean;
  onSet: (licenceType: string, quantity: number) => Promise<void>;
}) {
  const [quantity, setQuantity] = useState(String(pool.quantity));
  return (
    <tr>
      <td>{pool.name}</td>
      <td>{pool.quantity}</td>
      <td>{pool.assigned}</td>
      <td>{pool.available}</td>
      <td>
        <label className="visually-hidden" htmlFor={`pool-${pool.licenceType}`}>
          New number of {pool.name} licences
        </label>
        <input
          id={`pool-${pool.licenceType}`}
          type="number"
          min={0}
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
        />{" "}
        <button
          type="button"
          className="link-button"
          disabled={busy}
          onClick={() => void onSet(pool.licenceType, Number(quantity))}
        >
          Set
        </button>
      </td>
    </tr>
  );
}

function FirstAdministratorSection({
  info,
  busy,
  onResend,
  onInvite,
}: {
  info: Detail["firstAdministrator"];
  busy: boolean;
  onResend: () => void;
  onInvite: (email: string) => void;
}) {
  const [email, setEmail] = useState("");
  return (
    <section aria-labelledby="first-admin-heading" className="stack">
      <h3 id="first-admin-heading">First administrator</h3>
      {info ? (
        <p data-testid="first-administrator">
          Invitation {info.status.toLowerCase()}, {info.sentCount} sent, link works until{" "}
          {new Date(info.expiresAt).toLocaleString()}. The address is not shown.
        </p>
      ) : (
        <p data-testid="first-administrator">No invitation was made by the platform.</p>
      )}
      {info ? (
        <div>
          <button type="button" className="button" disabled={busy} onClick={onResend}>
            Send the invitation again
          </button>
        </div>
      ) : null}
      <form
        className="form"
        noValidate
        aria-label="Invite a new first administrator"
        onSubmit={(event) => {
          event.preventDefault();
          onInvite(email);
          setEmail("");
        }}
      >
        <div className="field">
          <label htmlFor="first-admin-email">Invite a new first administrator</label>
          <input
            id="first-admin-email"
            type="email"
            autoComplete="off"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </div>
        <button type="submit" className="button" disabled={busy}>
          Send the invitation
        </button>
      </form>
    </section>
  );
}

function SupportAccessSection({
  grants,
  busy,
  onRequest,
}: {
  grants: Grant[];
  busy: boolean;
  onRequest: (reason: string, minutes: number) => void;
}) {
  const [reason, setReason] = useState("");
  const [minutes, setMinutes] = useState("60");
  return (
    <section aria-labelledby="support-heading" className="stack">
      <h3 id="support-heading">Support access</h3>
      <p>
        Nobody on the platform has access to an organization&apos;s data. Ask the organization; an administrator of
        theirs approves for a limited time.
      </p>
      {grants.length > 0 ? (
        <table className="table" data-testid="support-grants">
          <thead>
            <tr>
              <th>Who</th>
              <th>Reason</th>
              <th>State</th>
              <th>Until</th>
            </tr>
          </thead>
          <tbody>
            {grants.map((grant) => (
              <tr key={grant.id}>
                <td>{grant.requester}</td>
                <td>{grant.reason}</td>
                <td>{grant.active ? "ACTIVE" : grant.status}</td>
                <td>{grant.accessExpiresAt ? new Date(grant.accessExpiresAt).toLocaleString() : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No requests.</p>
      )}
      <form
        className="form"
        noValidate
        aria-label="Ask for support access"
        onSubmit={(event) => {
          event.preventDefault();
          onRequest(reason, Number(minutes));
          setReason("");
        }}
      >
        <div className="field">
          <label htmlFor="support-reason">Why access is needed (no personal data)</label>
          <input
            id="support-reason"
            type="text"
            maxLength={200}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="support-minutes">How long (15 to 240 minutes)</label>
          <input
            id="support-minutes"
            type="number"
            min={15}
            max={240}
            value={minutes}
            onChange={(event) => setMinutes(event.target.value)}
          />
        </div>
        <button type="submit" className="button" disabled={busy}>
          Ask for access
        </button>
      </form>
    </section>
  );
}
