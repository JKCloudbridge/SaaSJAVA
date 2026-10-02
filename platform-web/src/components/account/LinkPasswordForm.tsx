"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import { takeTokenFromAddress } from "@/lib/account/linkToken";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";
import { COMMON_TEXT, commonFailureText, problems } from "./messages";

/** The two screens a mailed link leads to: creating the account, and choosing a new password. */
export type LinkPasswordKind = "sign-up" | "reset";

type Token = { kind: "reading" } | { kind: "missing" } | { kind: "present"; value: string };

/**
 * The page a mailed link opens. It reads the one-time token from the address (the part after `#`, which no server
 * ever receives), removes it from the address bar and sends it to the API together with what the person chose. The
 * page decides nothing: whether the link is still good and whether the password is acceptable are the API's answers,
 * shown as the API words them. Opening the link changes nothing; only the button on this page does.
 */
export function LinkPasswordForm({ kind }: { kind: LinkPasswordKind }) {
  const [token, setToken] = useState<Token>({ kind: "reading" });
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | undefined>();
  const [done, setDone] = useState(false);
  // Reading the address removes the token from it, and development builds run an effect twice: the first reading is
  // kept, so the second one does not find an empty address and call the link invalid.
  const taken = useRef<{ value: string | undefined } | undefined>(undefined);

  useEffect(() => {
    // The address is read once, after the page is on screen, and in a callback: the token must not be read while the
    // page is rendered on the server, where there is no address bar.
    void Promise.resolve().then(() => {
      taken.current ??= { value: takeTokenFromAddress() };
      const value = taken.current.value;
      setToken(value === undefined ? { kind: "missing" } : { kind: "present", value });
    });
  }, []);

  async function send(value: string) {
    setSubmitting(true);
    setMessage(undefined);
    try {
      await ensureForgeryCookie();
      const { error, response } =
        kind === "sign-up"
          ? await api.POST("/api/v1/auth/sign-up/complete", { body: { token: value, displayName, password } })
          : await api.POST("/api/v1/auth/password/reset", { body: { token: value, newPassword: password } });
      if (response.status === 204) {
        setPassword("");
        setConfirm("");
        setDone(true);
        return;
      }
      if (response.status === 400) {
        const fields = failureFromResponse(error, response).fields;
        if (fields?.token) {
          setToken({ kind: "missing" });
        } else {
          setMessage(
            problems(fields, kind === "sign-up" ? "password" : "newPassword") ??
              problems(fields, "displayName") ??
              COMMON_TEXT.unavailable,
          );
        }
      } else {
        setMessage(commonFailureText(response.status));
      }
    } catch {
      setMessage(COMMON_TEXT.network);
    }
    setSubmitting(false);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || token.kind !== "present") {
      return;
    }
    // Only a convenience: the API checks the password itself, and never sees the second copy.
    if (password !== confirm) {
      setMessage(COMMON_TEXT.passwordsDiffer);
      return;
    }
    void send(token.value);
  }

  if (done) {
    return (
      <div data-testid="link-done">
        <p role="status">
          {kind === "sign-up"
            ? "Your account is ready. You can sign in now."
            : "Your password was changed and every device was signed out. Sign in with the new password."}
        </p>
        <p>
          <Link href="/sign-in">Go to sign in</Link>
        </p>
      </div>
    );
  }
  if (token.kind === "reading") {
    return <p aria-live="polite">Checking the link…</p>;
  }
  if (token.kind === "missing") {
    return (
      <div data-testid="link-invalid">
        <p className="form-message" role="alert">
          {COMMON_TEXT.invalidLink}
        </p>
        <p>
          <Link href={kind === "sign-up" ? "/sign-up" : "/forgot-password"}>
            {kind === "sign-up" ? "Start again" : "Ask for a new link"}
          </Link>
        </p>
      </div>
    );
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      {message ? (
        <p className="form-message" role="alert" data-testid="link-message">
          {message}
        </p>
      ) : null}
      {kind === "sign-up" ? (
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
      ) : null}
      <div className="field">
        <label htmlFor="password">{kind === "sign-up" ? "Password" : "New password"}</label>
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
        {submitting ? "Saving…" : kind === "sign-up" ? "Create my account" : "Set the new password"}
      </button>
    </form>
  );
}
