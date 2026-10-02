"use client";

import { useEffect, useState, type ChangeEvent } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { beginSwitch } from "@/lib/organizations/switch";
import { useSession } from "@/lib/session/SessionProvider";

type Organization = components["schemas"]["OrganizationSummary"];

/**
 * The organization switcher in the header. It lists the organizations the API says the signed-in person belongs to and,
 * when one is chosen, asks the API to move there (see `beginSwitch`). It is shown only when there is something to switch
 * between. Which organizations a person belongs to, and whether a move is allowed, is the backend's answer, never this
 * component's.
 */
export function OrganizationSwitcher() {
  const { state } = useSession();
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [message, setMessage] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const signedIn = state.status === "signedIn";

  useEffect(() => {
    if (!signedIn) {
      return;
    }
    let cancelled = false;
    async function load() {
      try {
        const { data } = await api.GET("/api/v1/organizations");
        if (!cancelled) {
          setOrganizations(data?.data ?? []);
        }
      } catch {
        if (!cancelled) {
          setOrganizations([]);
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [signedIn]);

  async function choose(event: ChangeEvent<HTMLSelectElement>) {
    const slug = event.target.value;
    if (!slug || busy) {
      return;
    }
    setBusy(true);
    setMessage(undefined);
    const result = await beginSwitch(slug);
    if (!result.ok) {
      setMessage(result.message);
      setBusy(false);
    }
  }

  if (!signedIn || organizations.length < 2) {
    return null;
  }
  const here = organizations.find((organization) => organization.host === window.location.host);
  return (
    <span className="org-switcher" data-testid="org-switcher">
      <label htmlFor="org-switcher-select">Organization</label>
      <select id="org-switcher-select" value={here?.slug ?? ""} onChange={(event) => void choose(event)} disabled={busy}>
        {here ? null : <option value="">Switch to…</option>}
        {organizations.map((organization) => (
          <option key={organization.slug} value={organization.slug}>
            {organization.displayName}
          </option>
        ))}
      </select>
      {message ? (
        <span role="alert" className="form-message">
          {message}
        </span>
      ) : null}
    </span>
  );
}
