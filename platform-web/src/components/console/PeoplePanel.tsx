"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ActionMessages, failureText, useAction } from "./useAction";

type Person = components["schemas"]["PlatformPersonView"];

const ROLES = ["PLATFORM_ADMIN", "PLATFORM_SUPPORT", "PLATFORM_BILLING"] as const;

type Loaded = { kind: "loading" } | { kind: "ok"; items: Person[] } | { kind: "failed"; message: string };

/**
 * Who holds a platform role, with granting and revoking. Platform administrators only: anybody else gets the API's
 * refusal, shown in its words. The last platform administrator cannot be removed; the API says so and the page repeats
 * it.
 */
export function PeoplePanel() {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<string>(ROLES[1]);

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/platform/people");
      setLoaded(
        data
          ? { kind: "ok", items: data.data }
          : { kind: "failed", message: await failureText(error, response) },
      );
    } catch {
      setLoaded({ kind: "failed", message: COMMON_TEXT.network });
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  function grant(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/platform/people", { body: { email, role } });
      if (data) {
        setEmail("");
      }
      return { error, response, text: "The role was granted." };
    });
  }

  const revoke = (assignmentId: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/platform/people/{assignmentId}", {
        params: { path: { assignmentId } },
      });
      return { error, response, text: "The role was taken away." };
    });

  if (loaded.kind === "failed") {
    return (
      <p className="form-message" role="alert" data-testid="people-failed">
        {loaded.message}
      </p>
    );
  }

  return (
    <section aria-labelledby="people-heading" className="stack">
      <h2 id="people-heading">Platform people</h2>
      <ActionMessages notice={notice} problem={problem} testId="people" />
      {loaded.kind === "loading" ? <p aria-live="polite">Loading…</p> : null}
      {loaded.kind === "ok" ? (
        <table className="table" data-testid="people">
          <thead>
            <tr>
              <th>Name</th>
              <th>Address</th>
              <th>Role</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {loaded.items.map((person) => (
              <tr key={person.id} data-testid="person-row">
                <td>{person.displayName}</td>
                <td>{person.email}</td>
                <td>{person.role}</td>
                <td>
                  <button type="button" className="link-button" disabled={busy} onClick={() => void revoke(person.id)}>
                    Take away
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      <form className="form" onSubmit={grant} noValidate aria-label="Give a platform role">
        <h3>Give a platform role</h3>
        <p>The person must already have an account.</p>
        <div className="field">
          <label htmlFor="people-email">E-mail address</label>
          <input
            id="people-email"
            type="email"
            autoComplete="off"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="people-role">Role</label>
          <select id="people-role" value={role} onChange={(event) => setRole(event.target.value)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button" disabled={busy}>
          Give the role
        </button>
      </form>
    </section>
  );
}
