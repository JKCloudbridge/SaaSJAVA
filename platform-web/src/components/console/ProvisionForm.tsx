"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT, commonFailureText, problems } from "@/components/account/messages";
import { suggestSlug } from "@/components/account/CreateOrganizationForm";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";

type Plan = components["schemas"]["PlanInfo"];

/**
 * A platform administrator sets up an organization for a client: its name, short name, plan and the address of its
 * first administrator. The page sends them; the API creates the organization closed and mails the invitation. The
 * answer is the same whether or not the address has an account, and the page says so: it never shows or guesses the
 * state of an account.
 */
export function ProvisionForm() {
  const [plans, setPlans] = useState<Plan[]>([]);
  const [displayName, setDisplayName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [planKey, setPlanKey] = useState("");
  const [email, setEmail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | undefined>();
  const [created, setCreated] = useState<{ id: string; name: string } | undefined>();

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const { data } = await api.GET("/api/v1/platform/plans");
        if (!cancelled && data) {
          setPlans(data.data);
          setPlanKey((current) => current || data.data[0]?.key || "");
        }
      } catch {
        // The form still works with a typed plan key; the API decides.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setMessage(undefined);
    try {
      await ensureForgeryCookie();
      const { data, error, response } = await api.POST("/api/v1/platform/organizations", {
        body: { displayName, slug, planKey, email },
      });
      if (data) {
        setCreated({ id: data.data.id, name: data.data.displayName });
        return;
      }
      if (response.status === 400) {
        const fields = failureFromResponse(error, response).fields;
        setMessage(
          problems(fields, "slug") ??
            problems(fields, "displayName") ??
            problems(fields, "planKey") ??
            problems(fields, "email") ??
            COMMON_TEXT.unavailable,
        );
      } else {
        setMessage(
          response.status === 403 || response.status === 404
            ? failureFromResponse(error, response).message
            : commonFailureText(response.status),
        );
      }
    } catch {
      setMessage(COMMON_TEXT.network);
    }
    setSubmitting(false);
  }

  if (created) {
    return (
      <div data-testid="provisioned" className="stack">
        <p role="status">
          {created.name} was set up. It stays closed until its first administrator accepts the invitation that was sent.
        </p>
        <p>
          <Link href={`/console/organizations/${created.id}`}>Open the organization</Link>
        </p>
      </div>
    );
  }

  return (
    <form className="form" onSubmit={(event) => void submit(event)} noValidate>
      <h2>Set up an organization for a client</h2>
      {message ? (
        <p className="form-message" role="alert" data-testid="provision-message">
          {message}
        </p>
      ) : null}
      <div className="field">
        <label htmlFor="provision-name">Organization name</label>
        <input
          id="provision-name"
          type="text"
          value={displayName}
          onChange={(event) => {
            setDisplayName(event.target.value);
            if (!slugEdited) {
              setSlug(suggestSlug(event.target.value));
            }
          }}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="provision-slug">Short name for the web address</label>
        <input
          id="provision-slug"
          type="text"
          autoComplete="off"
          value={slug}
          onChange={(event) => {
            setSlugEdited(true);
            setSlug(event.target.value);
          }}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="provision-plan">Plan</label>
        <select id="provision-plan" value={planKey} onChange={(event) => setPlanKey(event.target.value)} required>
          {plans.map((plan) => (
            <option key={plan.key} value={plan.key}>
              {plan.name}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="provision-email">E-mail address of the first administrator</label>
        <input
          id="provision-email"
          type="email"
          autoComplete="off"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          required
        />
      </div>
      <p>
        An invitation is sent to this address. The answer is the same whether or not the address already has an account.
      </p>
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? "Setting up…" : "Set up the organization"}
      </button>
    </form>
  );
}
