"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { failureText } from "./useAction";

type Summary = components["schemas"]["PlatformOrganizationSummary"];

type Loaded =
  | { kind: "loading" }
  | { kind: "ok"; items: Summary[]; nextCursor?: string }
  | { kind: "failed"; message: string };

/**
 * The organizations of the platform, by short name, one page at a time, with a search. Shows what the API lists (state,
 * plan, trial) and nothing about members or business data: the API does not send it. A refusal is shown in the API's
 * own words.
 */
export function OrganizationsPanel() {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });
  const [search, setSearch] = useState("");
  const [active, setActive] = useState("");
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(async (text: string, cursor?: string, previous: Summary[] = []) => {
    try {
      const { data, error, response } = await api.GET("/api/v1/platform/organizations", {
        params: { query: { search: text || undefined, cursor, limit: 50 } },
      });
      if (!data) {
        setLoaded({ kind: "failed", message: await failureText(error, response) });
        return;
      }
      setLoaded({
        kind: "ok",
        items: [...previous, ...data.data],
        nextCursor: data.pagination.hasMore ? data.pagination.nextCursor : undefined,
      });
    } catch {
      setLoaded({ kind: "failed", message: COMMON_TEXT.network });
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(() => load(""));
  }, [load]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setActive(search);
    setLoaded({ kind: "loading" });
    void load(search);
  }

  async function more() {
    if (loaded.kind !== "ok" || !loaded.nextCursor || loadingMore) {
      return;
    }
    setLoadingMore(true);
    await load(active, loaded.nextCursor, loaded.items);
    setLoadingMore(false);
  }

  return (
    <section aria-labelledby="organizations-heading" className="stack">
      <h2 id="organizations-heading">Organizations</h2>
      <form className="form" onSubmit={submit} role="search" noValidate>
        <div className="field">
          <label htmlFor="organization-search">Search by name or short name</label>
          <input
            id="organization-search"
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <button type="submit" className="button">
          Search
        </button>
      </form>
      {loaded.kind === "loading" ? <p aria-live="polite">Loading…</p> : null}
      {loaded.kind === "failed" ? (
        <p className="form-message" role="alert" data-testid="organizations-failed">
          {loaded.message}
        </p>
      ) : null}
      {loaded.kind === "ok" && loaded.items.length === 0 ? <p>No organizations found.</p> : null}
      {loaded.kind === "ok" && loaded.items.length > 0 ? (
        <table className="table" data-testid="organizations">
          <thead>
            <tr>
              <th>Name</th>
              <th>Short name</th>
              <th>State</th>
              <th>Plan</th>
              <th>Trial</th>
            </tr>
          </thead>
          <tbody>
            {loaded.items.map((organization) => (
              <tr key={organization.id} data-testid="organization-row">
                <td>
                  <Link href={`/console/organizations/${organization.id}`}>{organization.displayName}</Link>
                </td>
                <td>{organization.slug}</td>
                <td>{organization.status}</td>
                <td>
                  {organization.plan ?? "none"}
                  {organization.subscriptionStatus ? ` (${organization.subscriptionStatus})` : ""}
                </td>
                <td>
                  {organization.trialEndsAt
                    ? `${organization.trialExpired ? "expired" : "ends"} ${new Date(organization.trialEndsAt).toLocaleDateString()}`
                    : ""}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {loaded.kind === "ok" && loaded.nextCursor ? (
        <button type="button" className="button" disabled={loadingMore} onClick={() => void more()}>
          Show more
        </button>
      ) : null}
    </section>
  );
}
