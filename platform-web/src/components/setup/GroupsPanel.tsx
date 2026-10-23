"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";

type Group = components["schemas"]["GroupView"];
type Policy = components["schemas"]["AccessPolicyView"];
type Member = components["schemas"]["MemberView"];

interface Loaded {
  groups: Group[];
  policies: Policy[];
  /** The people of the organization, by membership (empty when the caller may not see members: names then fall back). */
  members: Member[];
}

/**
 * The public groups of the organization the address names: named sets of people and of other groups, which can be given
 * access policies; everyone in a group, directly or through nested groups, holds what its policies give. Anyone who manages
 * access creates, fills and removes them. A list; a row opens the group (its people, nested groups and access policies);
 * New opens a small form. The page shows what the API answers and its own words for every refusal (a group that would
 * contain itself, a policy that needs a licence, the last member who can manage access); it never decides who may do what.
 */
export function GroupsPanel() {
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [person, setPerson] = useState<Record<string, string>>({});
  const [inner, setInner] = useState<Record<string, string>>({});
  const [policy, setPolicy] = useState<Record<string, string>>({});
  const offersManage = useOffers("access.manage");

  const reload = useCallback(async () => {
    try {
      const [groups, policies, members] = await Promise.all([
        api.GET("/api/v1/groups"),
        api.GET("/api/v1/access-policies"),
        api.GET("/api/v1/members"),
      ]);
      if (!groups.data) {
        setFailure(await failureText(groups.error, groups.response));
        return;
      }
      setFailure(undefined);
      setLoaded({
        groups: groups.data.data,
        policies: policies.data?.data ?? [],
        members: members.data?.data ?? [],
      });
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const result = await api.POST("/api/v1/groups", { body: { name, description } });
      if (result.response.ok) {
        setName("");
        setDescription("");
        screen.back(true);
      }
      return { error: result.error, response: result.response, text: "The group was created." };
    });
  }

  const groupPath = (group: Group) => ({ params: { path: { groupId: group.id } } });

  const addPerson = (group: Group) =>
    act(async () => {
      const result = await api.POST("/api/v1/groups/{groupId}/members", {
        ...groupPath(group),
        body: { membershipId: person[group.id] ?? "" },
      });
      if (result.response.ok) {
        setPerson((previous) => ({ ...previous, [group.id]: "" }));
      }
      return { error: result.error, response: result.response, text: "The person is in the group." };
    });

  const addGroup = (group: Group) =>
    act(async () => {
      const result = await api.POST("/api/v1/groups/{groupId}/members", {
        ...groupPath(group),
        body: { groupId: inner[group.id] ?? "" },
      });
      if (result.response.ok) {
        setInner((previous) => ({ ...previous, [group.id]: "" }));
      }
      return { error: result.error, response: result.response, text: "The group is inside the group." };
    });

  const givePolicy = (group: Group) =>
    act(async () => {
      const result = await api.POST("/api/v1/groups/{groupId}/policies", {
        ...groupPath(group),
        body: { policyId: policy[group.id] ?? "" },
      });
      if (result.response.ok) {
        setPolicy((previous) => ({ ...previous, [group.id]: "" }));
      }
      return { error: result.error, response: result.response, text: "The access policy was given to the group." };
    });

  const removePerson = (group: Group, membershipId: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/groups/{groupId}/members/people/{membershipId}", {
        params: { path: { groupId: group.id, membershipId } },
      });
      return { error, response, text: "The person was taken out of the group." };
    });

  const removeGroup = (group: Group, innerGroupId: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/groups/{groupId}/members/groups/{innerGroupId}", {
        params: { path: { groupId: group.id, innerGroupId } },
      });
      return { error, response, text: "The group was taken out of the group." };
    });

  const takePolicy = (group: Group, policyId: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/groups/{groupId}/policies/{policyId}", {
        params: { path: { groupId: group.id, policyId } },
      });
      return { error, response, text: "The access policy was taken from the group." };
    });

  const remove = (group: Group) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/groups/{groupId}", groupPath(group));
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The group was removed." };
    });

  const messages = <ActionMessages notice={notice} problem={problem} testId="groups" />;

  if (screen.creating && loaded) {
    const cancel = () => screen.back();
    return (
      <DetailPage
        listLabel="Groups"
        onBack={cancel}
        title="New group"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="group-form" className="button" disabled={busy}>
              Create the group
            </button>
          </>
        }
      >
        <form id="group-form" onSubmit={create} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
              <div className="field">
                <label htmlFor="group-name">Name</label>
                <input
                  id="group-name"
                  value={name}
                  maxLength={80}
                  onChange={(event) => setName(event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="group-description">Description</label>
                <input
                  id="group-description"
                  value={description}
                  maxLength={500}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </div>
            </DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const group = loaded?.groups.find((candidate) => candidate.id === screen.openId);
  if (screen.openId && loaded && !group) {
    return <MissingDetail listLabel="Groups" onBack={() => screen.back()} what="This group does not exist any more." />;
  }

  if (loaded && group) {
    const memberName = (membershipId: string) => {
      const member = loaded.members.find((candidate) => candidate.id === membershipId);
      return member ? member.displayName : `Member ${membershipId.slice(0, 8)}`;
    };
    const insideIds = new Set(group.groups.map((entry) => entry.id));
    const givenIds = new Set(group.policies.map((entry) => entry.id));
    return (
      <DetailPage
        listLabel="Groups"
        onBack={() => screen.back()}
        title={group.name}
        notices={messages}
        testId="group"
        actions={
          <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(group)}>
            Remove this group
          </button>
        }
      >
        <DetailSection title="Detail">
          <DetailGrid>
            <DetailItem label="Name">{group.name}</DetailItem>
            <DetailItem label="Description" tone={group.description ? undefined : "muted"}>
              {group.description || "—"}
            </DetailItem>
          </DetailGrid>
        </DetailSection>

        <DetailSection title="People">
          {group.people.length === 0 ? <p className="dl-value muted">None.</p> : null}
          <ul data-testid="group-people">
            {group.people.map((membershipId) => (
              <li key={membershipId}>
                {memberName(membershipId)}{" "}
                <button
                  type="button"
                  className="link-button"
                  disabled={busy}
                  onClick={() => void removePerson(group, membershipId)}
                >
                  Take out
                </button>
              </li>
            ))}
          </ul>
          <div className="field">
            <label htmlFor={`person-${group.id}`}>Add a person</label>
            <div className="form-actions">
              <select
                id={`person-${group.id}`}
                value={person[group.id] ?? ""}
                onChange={(event) => setPerson({ ...person, [group.id]: event.target.value })}
              >
                <option value="">Choose a person</option>
                {loaded.members
                  .filter((member) => member.status === "ACTIVE" && !group.people.includes(member.id))
                  .map((member) => (
                    <option key={member.id} value={member.id}>
                      {member.displayName}
                    </option>
                  ))}
              </select>
              <button
                type="button"
                className="button"
                disabled={busy || !person[group.id]}
                onClick={() => void addPerson(group)}
              >
                Add the person
              </button>
            </div>
          </div>
        </DetailSection>

        <DetailSection title="Groups inside this group">
          {group.groups.length === 0 ? <p className="dl-value muted">None.</p> : null}
          <ul data-testid="group-groups">
            {group.groups.map((entry) => (
              <li key={entry.id}>
                {entry.name}{" "}
                <button
                  type="button"
                  className="link-button"
                  disabled={busy}
                  onClick={() => void removeGroup(group, entry.id)}
                >
                  Take out
                </button>
              </li>
            ))}
          </ul>
          <div className="field">
            <label htmlFor={`inner-${group.id}`}>Put a group inside</label>
            <div className="form-actions">
              <select
                id={`inner-${group.id}`}
                value={inner[group.id] ?? ""}
                onChange={(event) => setInner({ ...inner, [group.id]: event.target.value })}
              >
                <option value="">Choose a group</option>
                {loaded.groups
                  .filter((other) => other.id !== group.id && !insideIds.has(other.id))
                  .map((other) => (
                    <option key={other.id} value={other.id}>
                      {other.name}
                    </option>
                  ))}
              </select>
              <button
                type="button"
                className="button"
                disabled={busy || !inner[group.id]}
                onClick={() => void addGroup(group)}
              >
                Put the group inside
              </button>
            </div>
          </div>
        </DetailSection>

        <DetailSection title="Access policies of this group">
          {group.policies.length === 0 ? <p className="dl-value muted">None.</p> : null}
          <ul data-testid="group-policies">
            {group.policies.map((entry) => (
              <li key={entry.id}>
                {entry.name}{" "}
                <button
                  type="button"
                  className="link-button"
                  disabled={busy}
                  onClick={() => void takePolicy(group, entry.id)}
                >
                  Take away
                </button>
              </li>
            ))}
          </ul>
          <div className="field">
            <label htmlFor={`policy-${group.id}`}>Give an access policy</label>
            <div className="form-actions">
              <select
                id={`policy-${group.id}`}
                value={policy[group.id] ?? ""}
                onChange={(event) => setPolicy({ ...policy, [group.id]: event.target.value })}
              >
                <option value="">Choose an access policy</option>
                {loaded.policies
                  .filter((candidate) => !givenIds.has(candidate.id))
                  .map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>
                      {candidate.name}
                      {candidate.requiredLicenceType ? " (needs a licence)" : ""}
                    </option>
                  ))}
              </select>
              <button
                type="button"
                className="button"
                disabled={busy || !policy[group.id]}
                onClick={() => void givePolicy(group)}
              >
                Give the access policy
              </button>
            </div>
          </div>
        </DetailSection>
      </DetailPage>
    );
  }

  return (
    <ListPage<Group>
      title="Groups"
      items={loaded?.groups}
      failure={failure}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => row.name}
      primaryExtra={(row) => row.description || null}
      columns={[
        { key: "people", header: "People", kind: "muted", cell: (row) => row.people.length },
        { key: "groups", header: "Groups inside", kind: "muted", cell: (row) => row.groups.length },
        { key: "policies", header: "Access policies", kind: "muted", cell: (row) => row.policies.length },
      ]}
      onOpen={(row) => screen.open(row.id)}
      searchText={(row) => `${row.name} ${row.description}`}
      onNew={offersManage ? screen.create : undefined}
      newLabel="New group"
      notices={messages}
      emptyText="There are no groups yet."
      testId="groups"
      rowTestId="group-row"
    />
  );
}
