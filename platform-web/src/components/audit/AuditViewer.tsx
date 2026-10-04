"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type AuditEvent = components["schemas"]["AuditEventView"];

/** Which events to show: the organization's own, or the platform's. The API decides what each reader may see. */
export type AuditScope = "organization" | "platform";

interface Filters {
  kind: string;
  from: string;
  to: string;
  actor: string;
  target: string;
}

const NO_FILTERS: Filters = { kind: "", from: "", to: "", actor: "", target: "" };

/** The families of events the filter offers. An empty value means every kind. */
const FAMILIES: Record<AuditScope, { value: string; label: string }[]> = {
  organization: [
    { value: "", label: "Everything" },
    { value: "access", label: "Access changes" },
    { value: "membership", label: "Members and invitations" },
    { value: "auth", label: "Sign-ins" },
    { value: "support_access", label: "Support access" },
    { value: "tenant", label: "Organization status" },
    { value: "retention", label: "Clean-up" },
  ],
  platform: [
    { value: "", label: "Everything" },
    { value: "platform", label: "Platform actions" },
    { value: "support_access", label: "Support access" },
    { value: "tenant", label: "Organization status" },
    { value: "auth", label: "Sign-ins" },
    { value: "system", label: "System use" },
    { value: "audit", label: "Clean-up of the audit trail" },
  ],
};

/** A sentence for the kinds people meet most; every other kind is shown as it is named. */
const SENTENCES: Record<string, string> = {
  "access.profile.created": "A profile was created",
  "access.profile.updated": "A profile was changed",
  "access.profile.deleted": "A profile was removed",
  "access.policy.created": "An access policy was created",
  "access.policy.updated": "An access policy was changed",
  "access.policy.deleted": "An access policy was removed",
  "access.role.created": "A role was created",
  "access.role.updated": "A role was changed",
  "access.role.deleted": "A role was removed",
  "access.member.profile_set": "A member was given a profile",
  "access.member.role_set": "A member was given a role",
  "access.member.policy_assigned": "A member was given an access policy",
  "access.member.policy_unassigned": "An access policy was taken from a member",
  "access.member.grant_given": "A member was given an ability directly",
  "access.member.grant_revoked": "An ability given directly was taken back",
  "access.member.licence_taken_back": "A member's licence was taken back",
  "access.group.created": "A group was created",
  "access.group.updated": "A group was changed",
  "access.group.deleted": "A group was removed",
  "access.group.member_added": "Someone was added to a group",
  "access.group.member_removed": "Someone was taken out of a group",
  "access.group.policy_given": "An access policy was given to a group",
  "access.group.policy_taken": "An access policy was taken from a group",
  "access.data.changed": "Permissions on data were changed",
  "access.action.refused": "An action was refused",
  "membership.deactivated": "A member was deactivated",
  "membership.reactivated": "A member was let back in",
  "membership.left": "A member left the organization",
  "membership.action.refused": "An action was refused for lack of an ability",
  "auth.sign_in.succeeded": "Someone signed in",
  "auth.sign_in.failed": "A sign-in did not succeed",
  "tenant.lifecycle.provisioned": "The organization was set up",
  "tenant.lifecycle.activated": "The organization was opened",
  "tenant.lifecycle.suspended": "The organization was suspended",
  "tenant.lifecycle.reinstated": "The organization was reinstated",
  "tenant.lifecycle.deactivated": "The organization was closed",
  "retention.invitations.anonymised": "Addresses of old invitations were removed",
  "audit.records.purged": "Old audit events were removed",
};

function sentence(event: AuditEvent): string {
  return SENTENCES[event.type] ?? event.type;
}

function shortId(id: string): string {
  return id.slice(0, 8);
}

function toIso(local: string): string | undefined {
  if (!local) {
    return undefined;
  }
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

/**
 * The audit viewer (Sprint 9): what happened in the organization (or on the platform), newest first, with filters and
 * "Show more". It shows exactly what the API answers, in the API's own words when it refuses, and says so plainly when
 * there is nothing to show. Who may open it is the API's decision; nothing here decides a permission or a tenant.
 */
export function AuditViewer({ scope }: { scope: AuditScope }) {
  const [events, setEvents] = useState<AuditEvent[] | undefined>();
  const [next, setNext] = useState<string | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<Filters>(NO_FILTERS);
  const [applied, setApplied] = useState<Filters>(NO_FILTERS);
  // A page that arrives after the filters changed again must not replace the newer one.
  const generation = useRef(0);

  const load = useCallback(
    async (filters: Filters, cursor: string | undefined, append: boolean) => {
      const mine = ++generation.current;
      setBusy(true);
      const query = {
        from: toIso(filters.from),
        to: toIso(filters.to),
        actor: filters.actor || undefined,
        kind: filters.kind || undefined,
        target: filters.target || undefined,
        cursor,
      };
      try {
        const result =
          scope === "organization"
            ? await api.GET("/api/v1/audit-events", { params: { query } })
            : await api.GET("/api/v1/platform/audit-events", { params: { query } });
        if (mine !== generation.current) {
          return;
        }
        if (!result.data) {
          setFailure(await failureText(result.error, result.response));
          setEvents(append ? undefined : []);
          setNext(undefined);
        } else {
          setFailure(undefined);
          const page = result.data.data;
          setEvents((previous) => (append ? [...(previous ?? []), ...page] : page));
          setNext(result.data.pagination.hasMore ? result.data.pagination.nextCursor : undefined);
        }
      } catch {
        if (mine === generation.current) {
          setFailure(COMMON_TEXT.network);
        }
      }
      if (mine === generation.current) {
        setBusy(false);
      }
    },
    [scope],
  );

  useEffect(() => {
    void Promise.resolve().then(() => load(NO_FILTERS, undefined, false));
  }, [load]);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setApplied(draft);
    void load(draft, undefined, false);
  }

  function clear() {
    setDraft(NO_FILTERS);
    setApplied(NO_FILTERS);
    void load(NO_FILTERS, undefined, false);
  }

  const filtered = Object.values(applied).some((value) => value !== "");

  return (
    <div className="stack">
      <form onSubmit={search} aria-label="Filter the audit events" className="stack">
        <label>
          Kind
          <select value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value })}>
            {FAMILIES[scope].map((family) => (
              <option key={family.value} value={family.value}>
                {family.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          From
          <input type="datetime-local" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </label>
        <label>
          To
          <input type="datetime-local" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </label>
        <label>
          Person (identifier)
          <input value={draft.actor} onChange={(e) => setDraft({ ...draft, actor: e.target.value.trim() })} />
        </label>
        <label>
          About (identifier)
          <input value={draft.target} onChange={(e) => setDraft({ ...draft, target: e.target.value.trim() })} />
        </label>
        <div>
          <button type="submit" disabled={busy}>
            Show
          </button>{" "}
          <button type="button" onClick={clear} disabled={busy}>
            Clear
          </button>
        </div>
      </form>

      {failure ? (
        <p className="form-message" role="alert" data-testid="audit-problem">
          {failure}
        </p>
      ) : null}
      {events === undefined && !failure ? <p aria-live="polite">Loading…</p> : null}
      {events !== undefined && events.length === 0 && !failure ? (
        <p data-testid="audit-empty">{filtered ? "No events match." : "No events yet."}</p>
      ) : null}
      {events !== undefined && events.length > 0 ? (
        <ol aria-label="Audit events" className="stack">
          {events.map((event) => (
            <li key={event.id} data-testid="audit-event">
              <strong>{sentence(event)}</strong>{" "}
              <span data-testid="audit-outcome">{event.outcome === "SUCCESS" ? "" : `(${event.outcome.toLowerCase()})`}</span>
              <br />
              <time dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString()}</time>
              {event.actorUserId ? <span data-testid="audit-actor"> · person {shortId(event.actorUserId)}</span> : null}
              <span data-testid="audit-source"> · {event.source.toLowerCase()}</span>
              {event.objectKey ? (
                <span data-testid="audit-object">
                  {" "}
                  · {event.objectKey}
                  {event.recordId ? ` ${event.recordId}` : ""}
                </span>
              ) : null}
              {event.oldValue || event.newValue ? (
                <span data-testid="audit-change">
                  {" "}
                  · {event.oldValue ?? "—"} → {event.newValue ?? "—"}
                </span>
              ) : null}
              {event.reason ? <span data-testid="audit-reason"> · {event.reason}</span> : null}
              {Object.keys(event.attributes).length > 0 ? (
                <ul aria-label="Details" data-testid="audit-details">
                  {Object.entries(event.attributes).map(([key, value]) => (
                    <li key={key}>
                      {key}: {value}
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          ))}
        </ol>
      ) : null}
      {next ? (
        <p>
          <button type="button" disabled={busy} onClick={() => void load(applied, next, true)}>
            Show more
          </button>
        </p>
      ) : null}
    </div>
  );
}
