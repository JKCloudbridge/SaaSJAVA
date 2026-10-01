"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { navigate } from "@/lib/navigation";
import { ensureForgeryCookie, useSession } from "@/lib/session/SessionProvider";

/** What a person reads for each way a sign-in can end. The server's answer decides; these words only present it. */
const FAILURE_TEXT = {
  rejected: "The email address or the password is not correct.",
  invalid: "Enter your email address and your password.",
  tooMany: "Too many attempts. Wait a few minutes and try again.",
  unavailable: "Sign-in is not available right now. Try again in a moment.",
  network: "The server could not be reached. Check your connection and try again.",
  returned: "The sign-in could not be completed. Please try again.",
} as const;

/** Page-level text for the `problem` the API's redirect leaves in the address after a failed return. */
function problemText(problem: string | undefined): string | undefined {
  return problem === "sign-in" ? FAILURE_TEXT.returned : undefined;
}

/**
 * The sign-in form. It sends the address and the password to the API and, when the API accepts them, follows the API's
 * redirects (a full navigation) so the browser ends up with its session cookies and back on the page it came from. The
 * page never holds a token, never says which organization to use (the address of the page decides that on the server),
 * and shows the same text for every kind of wrong answer, as the server does.
 */
export function SignInForm({ returnTo, problem }: { returnTo: string; problem?: string }) {
  const { state } = useSession();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | undefined>(problemText(problem));

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setMessage(undefined);
    try {
      await ensureForgeryCookie();
      const { response } = await api.POST("/api/v1/auth/sign-in", { body: { email, password } });
      if (response.status === 204) {
        navigate(`/api/v1/auth/start?continue=${encodeURIComponent(returnTo)}`);
        return;
      }
      setPassword("");
      setMessage(failureText(response.status));
    } catch {
      setPassword("");
      setMessage(FAILURE_TEXT.network);
    }
    setSubmitting(false);
  }

  if (state.status === "signedIn") {
    return (
      <p data-testid="already-signed-in">
        You are signed in as {state.user.displayName}. <Link href="/">Go to the home page</Link>.
      </p>
    );
  }

  return (
    <form className="form" onSubmit={(event) => void submit(event)} noValidate>
      {message ? (
        <p className="form-message" role="alert" data-testid="sign-in-message">
          {message}
        </p>
      ) : null}
      <div className="field">
        <label htmlFor="email">Email address</label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="password">Password</label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
      </div>
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}

function failureText(status: number): string {
  switch (status) {
    case 400:
      return FAILURE_TEXT.invalid;
    case 401:
      return FAILURE_TEXT.rejected;
    case 429:
      return FAILURE_TEXT.tooMany;
    case 503:
      return FAILURE_TEXT.unavailable;
    default:
      return FAILURE_TEXT.unavailable;
  }
}
