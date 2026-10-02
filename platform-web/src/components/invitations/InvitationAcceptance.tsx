"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";
import { takeTokenFromAddress } from "@/lib/account/linkToken";
import { beginSwitch } from "@/lib/organizations/switch";
import { ensureForgeryCookie, useSession } from "@/lib/session/SessionProvider";
import { COMMON_TEXT, commonFailureText, problems } from "@/components/account/messages";

type Preview = components["schemas"]["InvitationPreview"];
type Accepted = components["schemas"]["InvitationAccepted"];

type Step =
  | { kind: "reading" }
  | { kind: "invalid" }
  | { kind: "unavailable"; message: string }
  | { kind: "ready"; token: string; preview: Preview }
  | { kind: "done"; accepted: Accepted; newAccount: boolean };

/**
 * The page an invitation link opens. It reads the one-time token from the address (after the `#`, which no server ever
 * receives), removes it from the address bar and asks the API what the link is for. The API answers with the
 * organization's name and whether the invited address already has an account:
 *
 * - no account: the person chooses a name and a password; the API creates the account and the membership;
 * - an account: the person signs in as that address and accepts (when the page is open without a sign-in it says so; the
 *   link in the e-mail is opened again afterwards, because the token is deliberately kept nowhere).
 *
 * The page never names or chooses the organization (the token resolves to it on the server), and it decides nothing:
 * whether the link is good is the API's answer, shown as the API words it.
 */
export function InvitationAcceptance() {
  const { state } = useSession();
  const [step, setStep] = useState<Step>({ kind: "reading" });
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | undefined>();
  // Reading the address removes the token from it, and development builds run an effect twice: the first reading is
  // kept and the preview is requested only once.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;
    async function read() {
      const token = takeTokenFromAddress();
      if (token === undefined) {
        setStep({ kind: "invalid" });
        return;
      }
      try {
        await ensureForgeryCookie();
        const { data, response } = await api.POST("/api/v1/auth/invitations/preview", { body: { token } });
        if (data) {
          setStep({ kind: "ready", token, preview: data.data });
        } else if (response.status === 400) {
          setStep({ kind: "invalid" });
        } else {
          setStep({ kind: "unavailable", message: commonFailureText(response.status) });
        }
      } catch {
        setStep({ kind: "unavailable", message: COMMON_TEXT.network });
      }
    }
    void read();
  }, []);

  async function acceptAsNew(token: string) {
    setSubmitting(true);
    setMessage(undefined);
    try {
      await ensureForgeryCookie();
      const { data, error, response } = await api.POST("/api/v1/auth/invitations/accept-new", {
        body: { token, displayName, password },
      });
      if (data) {
        setPassword("");
        setConfirm("");
        setStep({ kind: "done", accepted: data.data, newAccount: true });
        return;
      }
      if (response.status === 400) {
        const fields = failureFromResponse(error, response).fields;
        if (fields?.token) {
          setStep({ kind: "invalid" });
        } else {
          setMessage(problems(fields, "password") ?? problems(fields, "displayName") ?? COMMON_TEXT.unavailable);
        }
      } else {
        setMessage(commonFailureText(response.status));
      }
    } catch {
      setMessage(COMMON_TEXT.network);
    }
    setSubmitting(false);
  }

  async function acceptAsSignedIn(token: string) {
    setSubmitting(true);
    setMessage(undefined);
    try {
      await ensureForgeryCookie();
      const { data, response } = await api.POST("/api/v1/auth/invitations/accept", { body: { token } });
      if (data) {
        setStep({ kind: "done", accepted: data.data, newAccount: false });
        return;
      }
      setMessage(
        response.status === 400
          ? "This link is not valid, has expired, or was sent to a different address. Sign in with the address the invitation was sent to."
          : commonFailureText(response.status),
      );
    } catch {
      setMessage(COMMON_TEXT.network);
    }
    setSubmitting(false);
  }

  function submitNew(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || step.kind !== "ready") {
      return;
    }
    // Only a convenience: the API checks the password itself, and never sees the second copy.
    if (password !== confirm) {
      setMessage(COMMON_TEXT.passwordsDiffer);
      return;
    }
    void acceptAsNew(step.token);
  }

  if (step.kind === "reading") {
    return <p aria-live="polite">Checking the invitation…</p>;
  }
  if (step.kind === "unavailable") {
    return (
      <p className="form-message" role="alert">
        {step.message}
      </p>
    );
  }
  if (step.kind === "invalid") {
    return (
      <div data-testid="invitation-invalid">
        <p className="form-message" role="alert">
          {COMMON_TEXT.invalidLink} Ask the person who invited you to send it again.
        </p>
        <p>
          <Link href="/sign-in">Go to sign in</Link>
        </p>
      </div>
    );
  }
  if (step.kind === "done") {
    return (
      <div data-testid="invitation-done">
        <p role="status">You joined {step.accepted.displayName}.</p>
        {step.newAccount ? (
          <p>
            <a href={`${window.location.protocol}//${step.accepted.host}/sign-in`}>
              Sign in to {step.accepted.displayName}
            </a>
          </p>
        ) : (
          <p>
            <button
              type="button"
              className="button"
              onClick={() => {
                void beginSwitch(step.accepted.slug).then((result) => {
                  if (!result.ok) {
                    setMessage(result.message);
                  }
                });
              }}
            >
              Open {step.accepted.displayName}
            </button>
          </p>
        )}
        {message ? (
          <p className="form-message" role="alert">
            {message}
          </p>
        ) : null}
      </div>
    );
  }

  const { preview, token } = step;
  const intro = (
    <p data-testid="invitation-intro">
      You were invited to join <strong>{preview.organizationName}</strong> with the address{" "}
      <strong>{preview.email}</strong>. The invitation gives no access until you accept it.
    </p>
  );
  if (preview.existingAccount) {
    return (
      <div data-testid="invitation-existing">
        {intro}
        {state.status === "signedIn" ? (
          <>
            <p>You are signed in as {state.user.displayName}. Accept the invitation to join the organization.</p>
            {message ? (
              <p className="form-message" role="alert" data-testid="invitation-message">
                {message}
              </p>
            ) : null}
            <button type="button" className="button" disabled={submitting} onClick={() => void acceptAsSignedIn(token)}>
              {submitting ? "Joining…" : "Accept the invitation"}
            </button>
          </>
        ) : state.status === "loading" ? (
          <p aria-live="polite">Checking sign-in…</p>
        ) : (
          <p>
            This address already has an account. <Link href="/sign-in">Sign in</Link> with it, then open the link in your
            e-mail again to accept.
          </p>
        )}
      </div>
    );
  }
  return (
    <div data-testid="invitation-new">
      {intro}
      <form className="form" onSubmit={submitNew} noValidate>
        {message ? (
          <p className="form-message" role="alert" data-testid="invitation-message">
            {message}
          </p>
        ) : null}
        <div className="field">
          <label htmlFor="displayName">Your name</label>
          <input
            id="displayName"
            name="displayName"
            type="text"
            autoComplete="name"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="confirm">Repeat the password</label>
          <input
            id="confirm"
            name="confirm"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            required
          />
        </div>
        <button type="submit" className="button" disabled={submitting}>
          {submitting ? "Saving…" : "Create my account and join"}
        </button>
      </form>
    </div>
  );
}
