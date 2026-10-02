"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type Role = components["schemas"]["RoleView"];

interface Draft {
  id?: string;
  name: string;
  description: string;
  parentId: string;
}

const EMPTY: Draft = { name: "", description: "", parentId: "" };

/** A role with its depth in the tree, parents before their sub-roles. A loop (which the API refuses) cannot hang the page. */
function inTreeOrder(roles: Role[]): Array<{ role: Role; depth: number }> {
  const result: Array<{ role: Role; depth: number }> = [];
  const seen = new Set<string>();
  const add = (parentId: string | undefined, depth: number) => {
    for (const role of roles.filter((candidate) => (candidate.parentId ?? undefined) === parentId)) {
      if (seen.has(role.id)) {
        continue;
      }
      seen.add(role.id);
      result.push({ role, depth });
      add(role.id, depth + 1);
    }
  };
  add(undefined, 0);
  return result;
}

/**
 * The role hierarchy of the organization the address names. A role decides which records a member may see once records
 * exist; it gives no ability and changes nothing a member can do today. The page shows what the API answers and its own
 * words for every refusal (a move that would make a loop is refused by the API, not by this page).
 */
export function RolesPanel() {
  const [roles, setRoles] = useState<Role[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/roles");
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setRoles(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = { name: draft.name, description: draft.description, parentId: draft.parentId || undefined };
    void act(async () => {
      const result = draft.id
        ? await api.PUT("/api/v1/roles/{roleId}", { params: { path: { roleId: draft.id } }, body })
        : await api.POST("/api/v1/roles", { body });
      if (result.response.ok) {
        setDraft(EMPTY);
      }
      return { error: result.error, response: result.response, text: draft.id ? "The role was saved." : "The role was created." };
    });
  }

  const remove = (role: Role) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/roles/{roleId}", { params: { path: { roleId: role.id } } });
      return { error, response, text: "The role was removed." };
    });

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="roles-failed">
        {failure}
      </p>
    );
  }
  if (!roles) {
    return <p aria-live="polite">Loading…</p>;
  }

  return (
    <div className="stack">
      <p>
        Roles form a tree that says who works under whom. Once records exist, a role decides which records a member may see. A
        role gives no ability, so choosing one changes nothing a member can do today.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="roles" />

      <section aria-labelledby="role-form-heading">
        <h2 id="role-form-heading">{draft.id ? "Change the role" : "Create a role"}</h2>
        <form className="form" onSubmit={save} noValidate>
          <div className="field">
            <label htmlFor="role-name">Name</label>
            <input
              id="role-name"
              value={draft.name}
              maxLength={80}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="role-description">Description</label>
            <input
              id="role-description"
              value={draft.description}
              maxLength={500}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="role-parent">Works under</label>
            <select
              id="role-parent"
              value={draft.parentId}
              onChange={(event) => setDraft({ ...draft, parentId: event.target.value })}
            >
              <option value="">Nobody (a top role)</option>
              {roles
                .filter((role) => role.id !== draft.id)
                .map((role) => (
                  <option key={role.id} value={role.id}>
                    {role.name}
                  </option>
                ))}
            </select>
          </div>
          <button type="submit" className="button" disabled={busy}>
            {draft.id ? "Save the role" : "Create the role"}
          </button>{" "}
          {draft.id ? (
            <button type="button" className="link-button" onClick={() => setDraft(EMPTY)}>
              Cancel
            </button>
          ) : null}
        </form>
      </section>

      <section aria-labelledby="roles-heading">
        <h2 id="roles-heading">Roles</h2>
        {roles.length === 0 ? <p>There are no roles yet.</p> : null}
        {roles.length > 0 ? (
          <table className="table" data-testid="roles">
            <thead>
              <tr>
                <th>Role</th>
                <th>Members</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {inTreeOrder(roles).map(({ role, depth }) => (
                <tr key={role.id} data-testid="role-row">
                  <td style={{ paddingInlineStart: `calc(var(--space-4) * ${depth + 1})` }}>
                    {role.name}
                    {role.description ? <div className="hint">{role.description}</div> : null}
                  </td>
                  <td>{role.members}</td>
                  <td>
                    <button
                      type="button"
                      className="link-button"
                      disabled={busy}
                      onClick={() =>
                        setDraft({
                          id: role.id,
                          name: role.name,
                          description: role.description,
                          parentId: role.parentId ?? "",
                        })
                      }
                    >
                      Change
                    </button>{" "}
                    <button type="button" className="link-button" disabled={busy} onClick={() => void remove(role)}>
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </div>
  );
}
