"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { navigate } from "@/lib/navigation";
import type { components } from "@/lib/api/generated/schema";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { useOffers } from "@/lib/session/SessionProvider";
import { MemberAccessPanel } from "./MemberAccessPanel";
import { SupportAccessPanel } from "./SupportAccessPanel";

type Member = components["schemas"]["MemberView"];
type Invitation = components["schemas"]["InvitationView"];
type Pool = components["schemas"]["LicencePoolView"];
type Profile = components["schemas"]["ProfileView"];
type Role = components["schemas"]["RoleView"];

type Loaded<T> = { kind: "loading" } | { kind: "ok"; items: T[] } | { kind: "failed"; message: string };

/**
 * Members and invitations of the organization the address names, as a list. A row opens the member's page: their details,
 * licence (give, take back), profile, role, access policies and individual abilities, and deactivate or reactivate. New opens
 * the form that creates a member (name, address, profile, role, and whether the link is sent now). Below the list: the
 * licence numbers, the invitations (send again, withdraw), the support access requests of the platform's staff, and leaving
 * the organization. The page shows what the API answers and the API's own words for every refusal. It never decides who may do
 * what (the API answers 403 to a member whose abilities do not allow it, and the page then says so) and never names an
 * organization: the organization is the address of the page.
 */
export function MembersPanel() {
  const [members, setMembers] = useState<Loaded<Member>>({ kind: "loading" });
  const [invitations, setInvitations] = useState<Loaded<Invitation>>({ kind: "loading" });
  const [pools, setPools] = useState<Pool[]>([]);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [profileId, setProfileId] = useState("");
  const [roleId, setRoleId] = useState("");
  const [active, setActive] = useState(true);
  const offersInvite = useOffers("members.invite");

  const reload = useCallback(async () => {
    try {
      const [m, i, p, pr, r] = await Promise.all([
        api.GET("/api/v1/members"),
        api.GET("/api/v1/invitations"),
        api.GET("/api/v1/licences"),
        api.GET("/api/v1/profiles"),
        api.GET("/api/v1/roles"),
      ]);
      setPools(p.data?.data ?? []);
      setProfiles(pr.data?.data ?? []);
      setRoles(r.data?.data ?? []);
      setMembers(
        m.data
          ? { kind: "ok", items: m.data.data }
          : { kind: "failed", message: await failureText(m.error, m.response) },
      );
      setInvitations(
        i.data
          ? { kind: "ok", items: i.data.data }
          : { kind: "failed", message: await failureText(i.error, i.response) },
      );
    } catch {
      setMembers({ kind: "failed", message: COMMON_TEXT.network });
      setInvitations({ kind: "failed", message: COMMON_TEXT.network });
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;

  function invite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/invitations", {
        body: {
          email,
          displayName: name || undefined,
          profileId: profileId || undefined,
          roleId: roleId || undefined,
          active,
        },
      });
      if (data) {
        setEmail("");
        setName("");
        setProfileId("");
        setRoleId("");
        setActive(true);
        screen.back(true);
      }
      return { error, response, text: data?.data.message };
    });
  }

  const memberAction = (membershipId: string, action: "deactivate" | "reactivate") =>
    act(async () => {
      const { error, response } =
        action === "deactivate"
          ? await api.POST("/api/v1/members/{membershipId}/deactivate", { params: { path: { membershipId } } })
          : await api.POST("/api/v1/members/{membershipId}/reactivate", { params: { path: { membershipId } } });
      return {
        error,
        response,
        text: action === "deactivate" ? "The member was deactivated." : "The member was reactivated.",
      };
    });

  const giveLicence = (membershipId: string) =>
    act(async () => {
      const { error, response } = await api.PUT("/api/v1/members/{membershipId}/licence", {
        params: { path: { membershipId } },
      });
      return { error, response, text: "The licence was given." };
    });

  const releaseLicence = (membershipId: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/members/{membershipId}/licence", {
        params: { path: { membershipId } },
      });
      return { error, response, text: "The licence was taken back." };
    });

  const invitationAction = (invitationId: string, action: "resend" | "revoke") =>
    act(async () => {
      if (action === "resend") {
        const { data, error, response } = await api.POST("/api/v1/invitations/{invitationId}/resend", {
          params: { path: { invitationId } },
        });
        return { error, response, text: data?.data.message };
      }
      const { error, response } = await api.POST("/api/v1/invitations/{invitationId}/revoke", {
        params: { path: { invitationId } },
      });
      return { error, response, text: "The invitation was withdrawn." };
    });

  const leave = () =>
    act(async () => {
      const { error, response } = await api.POST("/api/v1/organization/leave");
      if (response.ok) {
        // The sessions of this organization ended with the membership: go to the start page of this site.
        navigate("/");
      }
      return { error, response, text: "You left the organization." };
    });

  const messages = <ActionMessages notice={notice} problem={problem} testId="members" />;

  if (screen.creating && members.kind === "ok") {
    const cancel = () => screen.back();
    return (
      <DetailPage
        listLabel="Members"
        onBack={cancel}
        title="New member"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="invite-form" className="button" disabled={busy}>
              Create the member
            </button>
          </>
        }
      >
        <form id="invite-form" onSubmit={invite} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
              <div className="field">
                <label htmlFor="invite-name">Name</label>
                <input
                  id="invite-name"
                  type="text"
                  autoComplete="off"
                  maxLength={200}
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                />
              </div>
              <div className="field">
                <label htmlFor="invite-email">E-mail address</label>
                <input
                  id="invite-email"
                  type="email"
                  autoComplete="off"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="invite-profile">Profile</label>
                <select id="invite-profile" value={profileId} onChange={(event) => setProfileId(event.target.value)}>
                  <option value="">The default profile</option>
                  {profiles.map((profile) => (
                    <option key={profile.id} value={profile.id}>
                      {profile.name} ({profile.licenceType})
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="invite-role">Role</label>
                <select id="invite-role" value={roleId} onChange={(event) => setRoleId(event.target.value)}>
                  <option value="">No role</option>
                  {roles.map((role) => (
                    <option key={role.id} value={role.id}>
                      {role.name}
                    </option>
                  ))}
                </select>
              </div>
              <label className="check span-2">
                <input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} /> Active:
                send the link now
              </label>
            </DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const member = members.kind === "ok" ? members.items.find((candidate) => candidate.id === screen.openId) : undefined;
  if (screen.openId && members.kind === "ok" && !member) {
    return (
      <MissingDetail listLabel="Members" onBack={() => screen.back()} what="This member does not exist any more." />
    );
  }

  if (member) {
    const waiting = member.status === "ACTIVE" && Boolean(member.profileId) && !member.licensed;
    return (
      <DetailPage
        listLabel="Members"
        onBack={() => screen.back()}
        title={member.displayName}
        notices={messages}
        testId="member-detail"
        actions={
          member.status === "ACTIVE" ? (
            <button
              type="button"
              className="button button-danger"
              disabled={busy}
              onClick={() => void memberAction(member.id, "deactivate")}
            >
              Deactivate
            </button>
          ) : (
            <button
              type="button"
              className="button"
              disabled={busy}
              onClick={() => void memberAction(member.id, "reactivate")}
            >
              Reactivate
            </button>
          )
        }
      >
        <DetailSection title="Detail">
          <DetailGrid>
            <DetailItem label="Name">
              {member.displayName}
              {member.you ? " (you)" : ""}
            </DetailItem>
            <DetailItem label="E-mail address">{member.email}</DetailItem>
            <DetailItem label="Status">
              <Tag tone={member.status === "ACTIVE" ? "ok" : "quiet"}>
                {member.status === "ACTIVE" ? "Active" : "Deactivated"}
              </Tag>
              {member.foundingAdministrator ? " Founded the organization" : ""}
            </DetailItem>
            <DetailItem label="Member since">{new Date(member.since).toLocaleDateString()}</DetailItem>
            <DetailItem label="Profile, role and access policies" wide>
              <span data-testid="member-profile">
                {member.profileName ?? "no profile"}
                {member.roleName ? `, ${member.roleName}` : ""}
                {member.policies.length > 0 ? `, ${member.policies.map((policy) => policy.name).join(", ")}` : ""}
              </span>
              {waiting ? <div className="hint">Waiting for a licence: the profile gives no abilities yet.</div> : null}
            </DetailItem>
          </DetailGrid>
        </DetailSection>

        <DetailSection title="Licence">
          <p className="dl-value" data-testid="member-licence">
            {member.licence ?? "none"}
            {member.status === "ACTIVE" ? (
              <>
                {" "}
                {member.licensed ? null : (
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => void giveLicence(member.id)}
                  >
                    Give a licence
                  </button>
                )}
                {member.licence ? (
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => void releaseLicence(member.id)}
                  >
                    Take back
                  </button>
                ) : null}
              </>
            ) : null}
          </p>
        </DetailSection>

        {member.status === "ACTIVE" ? (
          <MemberAccessPanel key={member.id} membershipId={member.id} onChanged={reload} />
        ) : null}
      </DetailPage>
    );
  }

  const band =
    pools.length > 0 ? (
      <ul className="stat-band" data-testid="licence-pools" aria-label="Licences">
        {pools.map((pool) => (
          <li key={pool.licenceType} data-testid="licence-pool">
            <span className="stat-label">{pool.name}</span>
            <span className="stat-value">
              {pool.assigned} of {pool.quantity}
            </span>
            <span className="stat-note">in use, {pool.available} free</span>
          </li>
        ))}
      </ul>
    ) : null;

  const below = (
    <>
      <DetailSection title="Invitations">
        {invitations.kind === "ok" && invitations.items.length === 0 ? (
          <p className="dl-value muted">No invitations yet.</p>
        ) : null}
        {invitations.kind === "ok" && invitations.items.length > 0 ? (
          <table className="table" data-testid="invitations">
            <thead>
              <tr>
                <th>Address</th>
                <th>Profile</th>
                <th>State</th>
                <th>Expires</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {invitations.items.map((invitation) => (
                <tr key={invitation.id}>
                  <td>
                    {invitation.email}
                    {invitation.displayName ? <div className="hint">{invitation.displayName}</div> : null}
                  </td>
                  <td>
                    {invitation.profileName ?? "default profile"}
                    {invitation.roleName ? `, ${invitation.roleName}` : ""}
                  </td>
                  <td>
                    {invitation.status}
                    {invitation.status === "OPEN" && invitation.sentCount === 0 ? " (saved, not sent)" : ""}
                  </td>
                  <td>{new Date(invitation.expiresAt).toLocaleString()}</td>
                  <td>
                    {invitation.status === "OPEN" || invitation.status === "EXPIRED" ? (
                      <>
                        {invitation.status === "OPEN" ? (
                          <button
                            type="button"
                            className="link-button"
                            disabled={busy}
                            onClick={() => void invitationAction(invitation.id, "revoke")}
                          >
                            Withdraw
                          </button>
                        ) : null}{" "}
                        <button
                          type="button"
                          className="link-button"
                          disabled={busy}
                          onClick={() => void invitationAction(invitation.id, "resend")}
                        >
                          {invitation.sentCount === 0 ? "Send" : "Send again"}
                        </button>
                      </>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </DetailSection>

      <SupportAccessPanel />

      <DetailSection title="Leave this organization">
        <p>
          You are signed out of this organization and your licences go back to it. An administrator can let you back in.
          The last member who can manage access cannot leave.
        </p>
        <button type="button" className="button button-danger" disabled={busy} onClick={() => void leave()}>
          Leave the organization
        </button>
      </DetailSection>
    </>
  );

  return (
    <ListPage<Member>
      title="Members"
      items={members.kind === "ok" ? members.items : undefined}
      failure={members.kind === "failed" ? members.message : undefined}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => `${row.displayName}${row.you ? " (you)" : ""}`}
      primaryExtra={(row) => row.email}
      columns={[
        {
          key: "profile",
          header: "Profile",
          kind: "muted",
          cell: (row) => row.profileName ?? "no profile",
        },
        {
          key: "licence",
          header: "Licence",
          kind: "muted",
          cell: (row) => <span data-testid="member-licence">{row.licence ?? "none"}</span>,
        },
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => (
            <>
              {row.status === "ACTIVE" && row.profileId && !row.licensed ? <Tag tone="error">Waiting for a licence</Tag> : null}{" "}
              <Tag tone={row.status === "ACTIVE" ? "ok" : "quiet"}>{row.status === "ACTIVE" ? "Active" : "Deactivated"}</Tag>
            </>
          ),
        },
      ]}
      onOpen={(row) => screen.open(row.id)}
      searchText={(row) => `${row.displayName} ${row.email} ${row.profileName ?? ""} ${row.roleName ?? ""}`}
      onNew={offersInvite ? screen.create : undefined}
      newLabel="New member"
      notices={messages}
      above={
        members.kind === "failed" ? null : (
          <>
            {band}
            {pools.length === 0 && members.kind === "ok" ? (
              <p className="hint">This organization holds no licences yet.</p>
            ) : (
              <p className="hint">
                A profile uses one licence of its type, and an access policy that needs a licence uses one more. A licence
                counts who may be given something; it grants no permission by itself.
              </p>
            )}
          </>
        )
      }
      below={members.kind === "failed" ? null : below}
      emptyText="This organization has no members yet."
      testId="members"
      rowTestId="member-row"
    />
  );
}
