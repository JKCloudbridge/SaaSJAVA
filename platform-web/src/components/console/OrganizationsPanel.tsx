"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag, type TagTone } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { failureText } from "./useAction";

type Summary = components["schemas"]["PlatformOrganizationSummary"];

type Loaded =
  | { kind: "loading" }
  | { kind: "ok"; items: Summary[]; nextCursor?: string }
  | { kind: "failed"; message: string };

const STATUS_TONE: Record<string, TagTone> = { ACTIVE: "ok", TRIAL: "on", SUSPENDED: "error", DEACTIVATED: "quiet" };

/**
 * The organizations of the platform, by short name, one page at a time. The list is the API's: the search is the API's
 * too (it looks through every organization, not only those on the page), so it is a filter bar and not the box of the other
 * lists. A row opens the organization's page; New sets one up for a client. Shows what the API lists (state, plan, trial)
 * and nothing about members or business data: the API does not send it. A refusal is shown in the API's own words.
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

  const filter = (
    <form className="filters" onSubmit={submit} role="search" noValidate>
      <label>
        Search by name or short name
        <input type="search" value={search} onChange={(event) => setSearch(event.target.value)} />
      </label>
      <div className="filters-actions">
        <button type="submit" className="button">
          Search
        </button>
      </div>
    </form>
  );

  return (
    <ListPage<Summary>
      title="Organizations"
      items={loaded.kind === "ok" ? loaded.items : undefined}
      failure={loaded.kind === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => row.displayName}
      openHref={(row) => `/console/organizations/${row.id}`}
      columns={[
        { key: "slug", header: "Short name", kind: "muted", cell: (row) => row.slug },
        {
          key: "plan",
          header: "Plan",
          kind: "muted",
          cell: (row) => `${row.plan ?? "none"}${row.subscriptionStatus ? ` (${row.subscriptionStatus})` : ""}`,
        },
        {
          key: "trial",
          header: "Trial",
          kind: "muted",
          cell: (row) =>
            row.trialEndsAt
              ? `${row.trialExpired ? "expired" : "ends"} ${new Date(row.trialEndsAt).toLocaleDateString()}`
              : "",
        },
        {
          key: "state",
          header: "State",
          kind: "tag",
          cell: (row) => <Tag tone={STATUS_TONE[row.status] ?? "quiet"}>{row.status}</Tag>,
        },
      ]}
      newHref="/console/organizations/new"
      newLabel="Set up an organization"
      above={filter}
      below={
        loaded.kind === "ok" && loaded.nextCursor ? (
          <div>
            <button type="button" className="button button-secondary" disabled={loadingMore} onClick={() => void more()}>
              Show more
            </button>
          </div>
        ) : null
      }
      emptyText="No organizations found."
      testId="organizations"
      rowTestId="organization-row"
    />
  );
}
