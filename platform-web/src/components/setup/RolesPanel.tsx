"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";

type Role = components["schemas"]["RoleView"];

interface Draft {
  name: string;
  description: string;
  parentId: string;
}

const EMPTY: Draft = { name: "", description: "", parentId: "" };

interface TreeRow {
  role: Role;
  depth: number;
}

/** A role with its depth in the tree, parents before their sub-roles. A loop (which the API refuses) cannot hang the page. */
function inTreeOrder(roles: Role[]): TreeRow[] {
  const result: TreeRow[] = [];
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
 * The role hierarchy of the organization the address names, a list with the tree shown by indentation. A role decides which
 * records a member may see once records exist; it gives no ability and changes nothing a member can do today. A row opens
 * the role (its parent, sub-roles, members); New opens the same page as a form. The page shows what the API answers and its
 * own words for every refusal (a move that would make a loop is refused by the API, not by this page).
 */
export function RolesPanel() {
  const [roles, setRoles] = useState<Role[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const offersManage = useOffers("access.manage");

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

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;
  const tree = useMemo(() => (roles ? inTreeOrder(roles) : undefined), [roles]);

  const bodyOf = () => ({ name: draft.name, description: draft.description, parentId: draft.parentId || undefined });

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const result = await api.POST("/api/v1/roles", { body: bodyOf() });
      if (result.response.ok) {
        screen.back(true);
      }
      return { error: result.error, response: result.response, text: "The role was created." };
    });
  }

  const save = (role: Role) => (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void act(async () => {
      const result = await api.PUT("/api/v1/roles/{roleId}", { params: { path: { roleId: role.id } }, body: bodyOf() });
      if (result.response.ok) {
        setEditing(false);
      }
      return { error: result.error, response: result.response, text: "The role was saved." };
    });
  };

  const remove = (role: Role) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/roles/{roleId}", { params: { path: { roleId: role.id } } });
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The role was removed." };
    });

  const messages = <ActionMessages notice={notice} problem={problem} testId="roles" />;

  const fields = (current: Role | undefined) => (
    <>
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
        <label htmlFor="role-parent">Works under</label>
        <select
          id="role-parent"
          value={draft.parentId}
          onChange={(event) => setDraft({ ...draft, parentId: event.target.value })}
        >
          <option value="">Nobody (a top role)</option>
          {(roles ?? [])
            .filter((role) => role.id !== current?.id)
            .map((role) => (
              <option key={role.id} value={role.id}>
                {role.name}
              </option>
            ))}
        </select>
      </div>
      <div className="field span-2">
        <label htmlFor="role-description">Description</label>
        <input
          id="role-description"
          value={draft.description}
          maxLength={500}
          onChange={(event) => setDraft({ ...draft, description: event.target.value })}
        />
      </div>
    </>
  );

  if (screen.creating && roles) {
    const cancel = () => screen.back();
    return (
      <DetailPage
        listLabel="Roles"
        onBack={cancel}
        title="New role"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="role-form" className="button" disabled={busy}>
              Create the role
            </button>
          </>
        }
      >
        <form id="role-form" onSubmit={create} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>{fields(undefined)}</DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const role = roles?.find((candidate) => candidate.id === screen.openId);
  if (screen.openId && roles && !role) {
    return <MissingDetail listLabel="Roles" onBack={() => screen.back()} what="This role does not exist any more." />;
  }

  if (roles && role) {
    const parent = roles.find((candidate) => candidate.id === role.parentId);
    const below = roles.filter((candidate) => candidate.parentId === role.id);
    return (
      <DetailPage
        listLabel="Roles"
        onBack={() => screen.back()}
        title={role.name}
        notices={messages}
        testId="role-detail"
        actions={
          editing ? (
            <>
              <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
                Cancel
              </button>
              <button type="submit" form="role-form" className="button" disabled={busy}>
                Save the role
              </button>
            </>
          ) : (
            <>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(role)}>
                Remove
              </button>
              <button
                type="button"
                className="button"
                disabled={busy}
                onClick={() => {
                  setDraft({ name: role.name, description: role.description, parentId: role.parentId ?? "" });
                  setEditing(true);
                }}
              >
                Change
              </button>
            </>
          )
        }
      >
        <form id="role-form" onSubmit={save(role)} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
              {editing ? (
                fields(role)
              ) : (
                <>
                  <DetailItem label="Name">{role.name}</DetailItem>
                  <DetailItem label="Works under" tone={parent ? undefined : "muted"}>
                    {parent ? parent.name : "Nobody (a top role)"}
                  </DetailItem>
                  <DetailItem label="Description" wide tone={role.description ? undefined : "muted"}>
                    {role.description || "—"}
                  </DetailItem>
                  <DetailItem label="Members">{role.members}</DetailItem>
                </>
              )}
            </DetailGrid>
          </DetailSection>
        </form>
        <DetailSection title="Roles below this one">
          {below.length === 0 ? (
            <p className="dl-value muted">No role works under this one.</p>
          ) : (
            <ul data-testid="role-below">
              {below.map((candidate) => (
                <li key={candidate.id}>
                  <button type="button" className="link-button" onClick={() => screen.open(candidate.id)}>
                    {candidate.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </DetailSection>
      </DetailPage>
    );
  }

  return (
    <ListPage<TreeRow>
      title="Roles"
      intro="Roles form a tree that says who works under whom. Once records exist, a role decides which records a member may see. A role gives no ability, so choosing one changes nothing a member can do today."
      items={tree}
      failure={failure}
      rowKey={(row) => row.role.id}
      primaryHeader="Role"
      primary={(row) => row.role.name}
      primaryExtra={(row) => row.role.description || null}
      primaryIndent={(row) => row.depth}
      columns={[{ key: "members", header: "Members", kind: "muted", cell: (row) => row.role.members }]}
      onOpen={(row) => {
        setEditing(false);
        screen.open(row.role.id);
      }}
      searchText={(row) => `${row.role.name} ${row.role.description}`}
      onNew={
        offersManage
          ? () => {
              setDraft(EMPTY);
              screen.create();
            }
          : undefined
      }
      newLabel="New role"
      notices={messages}
      emptyText="There are no roles yet."
      testId="roles"
      rowTestId="role-row"
    />
  );
}
