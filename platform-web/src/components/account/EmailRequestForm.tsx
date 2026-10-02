"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";
import { COMMON_TEXT, commonFailureText } from "./messages";

/** The two screens that ask only for an address and answer with one fixed sentence. */
export type EmailRequestKind = "sign-up" | "password-reset";

const COPY: Record<EmailRequestKind, { button: string; sending: string; sentTitle: string }> = {
  "sign-up": { button: "Send me the link", sending: "Sending…", sentTitle: "Check your e-mail" },
  "password-reset": { button: "Send me a reset link", sending: "Sending…", sentTitle: "Check your e-mail" },
};

/**
 * Asks for an address and sends it to the API; the API answers 202 with one sentence that is the same for every
 * address, and this page shows that sentence. It never learns, and never shows, whether the address has an account.
 * "Send again" is the same request, which the API treats as a new link that replaces the old one.
 */
export function EmailRequestForm({ kind }: { kind: EmailRequestKind }) {
  const [email, setEmail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | undefined>();
  const [answer, setAnswer] = useState<string | undefined>();

  async function send() {
    setSubmitting(true);
    setMessage(undefined);
    try {
      await ensureForgeryCookie();
      const { data, response } =
        kind === "sign-up"
          ? await api.POST("/api/v1/auth/sign-up", { body: { email } })
          : await api.POST("/api/v1/auth/password/forgot", { body: { email } });
      if (data) {
        setAnswer(data.data.message);
      } else {
        setMessage(response.status === 400 ? "Enter a valid email address." : commonFailureText(response.status));
      }
    } catch {
      setMessage(COMMON_TEXT.network);
    }
    setSubmitting(false);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!submitting) {
      void send();
    }
  }

  if (answer !== undefined) {
    return (
      <div data-testid="request-sent">
        <h2>{COPY[kind].sentTitle}</h2>
        <p role="status">{answer}</p>
        <p>
          The link works for a limited time. If nothing arrives, look in the spam folder, or{" "}
          <button type="button" className="link-button" disabled={submitting} onClick={() => void send()}>
            send it again
          </button>
          .
        </p>
        {message ? (
          <p className="form-message" role="alert">
            {message}
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      {message ? (
        <p className="form-message" role="alert" data-testid="request-message">
          {message}
        </p>
      ) : null}
      <div className="field">
        <label htmlFor="email">Email address</label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          required
        />
      </div>
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? COPY[kind].sending : COPY[kind].button}
      </button>
    </form>
  );
}
