"use client";

import { useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { DetailSection } from "@/components/ui/lists/DetailPage";
import { PageFrame } from "@/components/ui/lists/PageFrame";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";
import { failureText } from "./useAction";

type Session = components["schemas"]["SessionInfo"];

/**
 * Looks at the live sign-ins of a person and signs them out everywhere. It is a lookup, not a list, so it keeps its
 * content and takes the frame of the other screens. The address goes in the body of the request (never in a web address),
 * the answer for an unknown address is an empty list like for a person with nothing, and no token or secret is ever shown. A
 * reason is required and kept in the audit trail.
 */
export function SessionsPanel() {
  const [email, setEmail] = useState("");
  const [reason, setReason] = useState("");
  const [sessions, setSessions] = useState<Session[] | undefined>();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();

  async function run(kind: "lookup" | "signOut", event?: FormEvent) {
    event?.preventDefault();
    if (busy) {
      return;
    }
    setBusy(true);
    setNotice(undefined);
    setProblem(undefined);
    try {
      await ensureForgeryCookie();
      if (kind === "lookup") {
        const { data, error, response } = await api.POST("/api/v1/platform/sessions/lookup", {
          body: { email, reason },
        });
        if (data) {
          setSessions(data.data);
        } else {
          setProblem(await failureText(error, response));
        }
      } else {
        const { error, response } = await api.POST("/api/v1/platform/sessions/sign-out", { body: { email, reason } });
        if (response.ok) {
          setSessions([]);
          setNotice("The person was signed out everywhere.");
        } else {
          setProblem(await failureText(error, response));
        }
      }
    } catch {
      setProblem(COMMON_TEXT.network);
    }
    setBusy(false);
  }

  return (
    <PageFrame
      title="Sessions"
      intro="Look at the live sign-ins of a person, and sign them out everywhere. A reason is needed for both; it is kept in the audit trail."
      notices={
        <>
          {notice ? (
            <p role="status" data-testid="sessions-notice">
              {notice}
            </p>
          ) : null}
          {problem ? (
            <p className="form-message" role="alert" data-testid="sessions-problem">
              {problem}
            </p>
          ) : null}
        </>
      }
    >
      <DetailSection title="A person">
        <form className="form" onSubmit={(event) => void run("lookup", event)} noValidate>
          <div className="field">
            <label htmlFor="sessions-email">E-mail address of the person</label>
            <input
              id="sessions-email"
              type="email"
              autoComplete="off"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="sessions-reason">Reason (kept in the audit trail, no personal data)</label>
            <input
              id="sessions-reason"
              type="text"
              maxLength={200}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              required
            />
          </div>
          <div className="form-actions">
            <button type="submit" className="button" disabled={busy}>
              Look at the sign-ins
            </button>
            <button type="button" className="button button-danger" disabled={busy} onClick={() => void run("signOut")}>
              Sign the person out everywhere
            </button>
          </div>
        </form>
      </DetailSection>
      {sessions ? (
        <DetailSection title="Live sign-ins">
          {sessions.length === 0 ? (
            <p className="dl-value muted" data-testid="sessions-none">
              No live sign-ins.
            </p>
          ) : (
            <table className="table" data-testid="sessions">
              <thead>
                <tr>
                  <th>Kind</th>
                  <th>Where</th>
                  <th>Began</th>
                  <th>Ends at the latest</th>
                </tr>
              </thead>
              <tbody>
                {sessions.map((session, index) => (
                  <tr key={`${session.started}-${index}`}>
                    <td>{session.kind === "SIGN_IN" ? "Sign-in step" : "Signed in"}</td>
                    <td>{session.organization ?? "platform address"}</td>
                    <td>{new Date(session.started).toLocaleString()}</td>
                    <td>{new Date(session.expires).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </DetailSection>
      ) : null}
    </PageFrame>
  );
}
