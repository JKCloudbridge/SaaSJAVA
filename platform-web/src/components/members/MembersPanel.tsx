"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";
import { COMMON_TEXT } from "@/components/account/messages";

type Member = components["schemas"]["MemberView"];
type Invitation = components["schemas"]["InvitationView"];
type Pool = components["schemas"]["LicencePoolView"];

type Loaded<T> = { kind: "loading" } | { kind: "ok"; items: T[] } | { kind: "failed"; message: string };

async function failureText(error: unknown, response: Response): Promise<string> {
  return response.status === 429 ? COMMON_TEXT.tooMany : failureFromResponse(error, response).message;
}

/**
 * Members and invitations of the organization the address names, with the actions of an administrator: invite, send an
 * invitation again, withdraw it, deactivate and reactivate a member, name or release an administrator. The page shows
 * what the API answers and the API's own words for every refusal. It never decides who may do what (the API answers
 * 403 to a member who is not an administrator, and the page then says so) and never names an organization: the
 * organization is the address of the page.
 */
export function MembersPanel() {
  const [members, setMembers] = useState<Loaded<Member>>({ kind: "loading" });
  const [invitations, setInvitations] = useState<Loaded<Invitation>>({ kind: "loading" });
  const [pools, setPools] = useState<Pool[]>([]);
  const [email, setEmail] = useState("");
  const [asAdministrator, setAsAdministrator] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();

  const reload = useCallback(async () => {
    try {
      const [m, i, p] = await Promise.all([
        api.GET("/api/v1/members"),
        api.GET("/api/v1/invitations"),
        api.GET("/api/v1/licences"),
      ]);
      setPools(p.data?.data ?? []);
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

  async function act(run: () => Promise<{ error?: unknown; response: Response; text?: string }>) {
    if (busy) {
      return;
    }
    setBusy(true);
    setNotice(undefined);
    setProblem(undefined);
    try {
      await ensureForgeryCookie();
      const result = await run();
      if (result.response.ok) {
        setNotice(result.text);
        await reload();
      } else {
        setProblem(await failureText(result.error, result.response));
      }
    } catch {
      setProblem(COMMON_TEXT.network);
    }
    setBusy(false);
  }

  function invite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/invitations", {
        body: { email, administrator: asAdministrator },
      });
      if (data) {
        setEmail("");
        setAsAdministrator(false);
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

  const setAdministrator = (membershipId: string, administrator: boolean) =>
    act(async () => {
      const { error, response } = await api.PUT("/api/v1/members/{membershipId}/administrator", {
        params: { path: { membershipId } },
        body: { administrator },
      });
      return { error, response, text: administrator ? "The member is now an administrator." : "The member is no longer an administrator." };
    });

  const assignLicence = (membershipId: string, licenceType: string) =>
    act(async () => {
      const { error, response } = await api.PUT("/api/v1/members/{membershipId}/licence", {
        params: { path: { membershipId } },
        body: { licenceType },
      });
      return { error, response, text: "The licence was assigned." };
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

  if (members.kind === "failed") {
    return (
      <p className="form-message" role="alert" data-testid="members-failed">
        {members.message}
      </p>
    );
  }

  return (
    <div className="stack">
      {notice ? (
        <p role="status" data-testid="members-notice">
          {notice}
        </p>
      ) : null}
      {problem ? (
        <p className="form-message" role="alert" data-testid="members-problem">
          {problem}
        </p>
      ) : null}

      <section aria-labelledby="invite-heading">
        <h2 id="invite-heading">Invite someone</h2>
        <form className="form" onSubmit={invite} noValidate>
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
          <label className="check">
            <input
              type="checkbox"
              checked={asAdministrator}
              onChange={(event) => setAsAdministrator(event.target.checked)}
            />{" "}
            Make this person an administrator
          </label>
          <button type="submit" className="button" disabled={busy}>
            Send the invitation
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
                  <td>{invitation.email}</td>
                  <td>
                    {invitation.status}
                    {invitation.administrator ? " (administrator)" : ""}
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
                          Send again
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
        <p>A licence counts who may be given something; it grants no permission.</p>
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
                <th>Licence</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {members.items.map((member) => (
                <tr key={member.id} data-testid="member-row">
                  <td>
                    {member.displayName}
                    {member.you ? " (you)" : ""}
                  </td>
                  <td>{member.email}</td>
                  <td>
                    {member.status === "ACTIVE" ? "Active" : "Deactivated"}
                    {member.administrator ? ", administrator" : ""}
                    {member.foundingAdministrator ? ", founded the organization" : ""}
                  </td>
                  <td data-testid="member-licence">
                    {member.licence ?? "none"}
                    {member.status === "ACTIVE" ? (
                      <>
                        {" "}
                        {pools.map((pool) => (
                          <button
                            key={pool.licenceType}
                            type="button"
                            className="link-button"
                            disabled={busy || member.licence === pool.licenceType}
                            onClick={() => void assignLicence(member.id, pool.licenceType)}
                          >
                            Give {pool.name}
                          </button>
                        ))}
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
                          onClick={() => void memberAction(member.id, "deactivate")}
                        >
                          Deactivate
                        </button>{" "}
                        <button
                          type="button"
                          className="link-button"
                          disabled={busy}
                          onClick={() => void setAdministrator(member.id, !member.administrator)}
                        >
                          {member.administrator ? "Remove administrator" : "Make administrator"}
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
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </div>
  );
}
