"use client";

import { Fragment, useCallback, useEffect, useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { navigate } from "@/lib/navigation";
import type { components } from "@/lib/api/generated/schema";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { MemberAccessPanel } from "./MemberAccessPanel";

type Member = components["schemas"]["MemberView"];
type Invitation = components["schemas"]["InvitationView"];
type Pool = components["schemas"]["LicencePoolView"];
type Profile = components["schemas"]["ProfileView"];
type Role = components["schemas"]["RoleView"];

type Loaded<T> = { kind: "loading" } | { kind: "ok"; items: T[] } | { kind: "failed"; message: string };

/**
 * Members and invitations of the organization the address names, with the actions of someone who may do them: create a new
 * member (name, address, profile, role, and whether the link is sent now), send an invitation again, withdraw it, deactivate
 * and reactivate a member, give or take back a licence, open what decides a member's access, and leave the organization. The
 * page shows what the API answers and the API's own words for every refusal. It never decides who may do what (the API
 * answers 403 to a member whose abilities do not allow it, and the page then says so) and never names an organization: the
 * organization is the address of the page.
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
  const [opened, setOpened] = useState<string | undefined>();

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

  const { busy, notice, problem, act } = useAction(reload);

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
      return { error, response, text: action === "deactivate" ? "The member was deactivated." : "The member was reactivated." };
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

  if (members.kind === "failed") {
    return (
      <p className="form-message" role="alert" data-testid="members-failed">
        {members.message}
      </p>
    );
  }

  return (
    <div className="stack">
      <ActionMessages notice={notice} problem={problem} testId="members" />

      <section aria-labelledby="invite-heading">
        <h2 id="invite-heading">Create a new member</h2>
        <p>
          The person gets an e-mail with a link where they choose a password. You choose their profile and role; they get no
          access until they accept.
        </p>
        <form className="form" onSubmit={invite} noValidate>
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
          <label className="check">
            <input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} /> Active: send the
            link now
          </label>
          <button type="submit" className="button" disabled={busy}>
            Create the member
          </button>
        </form>
      </section>

      <section aria-labelledby="invitations-heading">
        <h2 id="invitations-heading">Invitations</h2>
        {invitations.kind === "ok" && invitations.items.length === 0 ? <p>No invitations yet.</p> : null}
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
      </section>

      <section aria-labelledby="licences-heading">
        <h2 id="licences-heading">Licences</h2>
        {pools.length === 0 ? <p>This organization holds no licences yet.</p> : null}
        {pools.length > 0 ? (
          <table className="table" data-testid="licence-pools">
            <thead>
              <tr>
                <th>Type</th>
                <th>Held</th>
                <th>Assigned</th>
                <th>Free</th>
              </tr>
            </thead>
            <tbody>
              {pools.map((pool) => (
                <tr key={pool.licenceType}>
                  <td>{pool.name}</td>
                  <td>{pool.quantity}</td>
                  <td>{pool.assigned}</td>
                  <td>{pool.available}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        <p>
          A profile uses one licence of its type, and an access policy that needs a licence uses one more. A licence counts who
          may be given something; it grants no permission by itself.
        </p>
      </section>

      <section aria-labelledby="members-heading">
        <h2 id="members-heading">Members</h2>
        {members.kind === "loading" ? <p aria-live="polite">Loading…</p> : null}
        {members.kind === "ok" ? (
          <table className="table" data-testid="members">
            <thead>
              <tr>
                <th>Name</th>
                <th>Address</th>
                <th>State</th>
                <th>Profile, role and access policies</th>
                <th>Licence</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {members.items.map((member) => (
                <Fragment key={member.id}>
                  <tr data-testid="member-row">
                    <td>
                      {member.displayName}
                      {member.you ? " (you)" : ""}
                    </td>
                    <td>{member.email}</td>
                    <td>
                      {member.status === "ACTIVE" ? "Active" : "Deactivated"}
                      {member.foundingAdministrator ? ", founded the organization" : ""}
                    </td>
                    <td data-testid="member-profile">
                      {member.profileName ?? "no profile"}
                      {member.roleName ? `, ${member.roleName}` : ""}
                      {member.policies.length > 0 ? `, ${member.policies.map((policy) => policy.name).join(", ")}` : ""}
                      {member.status === "ACTIVE" && member.profileId && !member.licensed ? (
                        <div className="hint">Waiting for a licence: the profile gives no abilities yet.</div>
                      ) : null}
                    </td>
                    <td data-testid="member-licence">
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
                    </td>
                    <td>
                      {member.status === "ACTIVE" ? (
                        <>
                          <button
                            type="button"
                            className="link-button"
                            disabled={busy}
                            onClick={() => setOpened(opened === member.id ? undefined : member.id)}
                          >
                            {opened === member.id ? "Close access" : "Access"}
                          </button>{" "}
                          <button
                            type="button"
                            className="link-button"
                            disabled={busy}
                            onClick={() => void memberAction(member.id, "deactivate")}
                          >
                            Deactivate
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          className="link-button"
                          disabled={busy}
                          onClick={() => void memberAction(member.id, "reactivate")}
                        >
                          Reactivate
                        </button>
                      )}
                    </td>
                  </tr>
                  {opened === member.id ? (
                    <tr>
                      <td colSpan={6}>
                        <MemberAccessPanel membershipId={member.id} name={member.displayName} onChanged={reload} />
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>

      <section aria-labelledby="leave-heading">
        <h2 id="leave-heading">Leave this organization</h2>
        <p>
          You are signed out of this organization and your licences go back to it. An administrator can let you back in. The
          last member who can manage access cannot leave.
        </p>
        <button type="button" className="button" disabled={busy} onClick={() => void leave()}>
          Leave the organization
        </button>
      </section>
    </div>
  );
}
