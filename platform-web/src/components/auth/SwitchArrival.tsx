"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { takeTokenFromAddress } from "@/lib/account/linkToken";
import { navigate } from "@/lib/navigation";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";
import { COMMON_TEXT, commonFailureText } from "@/components/account/messages";

type Step = { kind: "working" } | { kind: "failed"; message: string };

/**
 * Where a person arrives after choosing another organization: the one-time proof is in the address after the `#`. The page
 * reads it once, removes it from the address bar, hands it to the API (which checks it against this organization's host
 * and the person's membership) and, when the API agrees, follows the usual sign-in navigation, which sets this host's
 * own cookies. The page decides nothing.
 */
export function SwitchArrival() {
  const [step, setStep] = useState<Step>({ kind: "working" });
  // Development builds run effects twice and reading the address removes the proof: keep the first reading, and make
  // sure the proof is sent only once.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;
    async function arrive() {
      const token = takeTokenFromAddress();
      if (token === undefined) {
        setStep({ kind: "failed", message: COMMON_TEXT.invalidLink });
        return;
      }
      try {
        await ensureForgeryCookie();
        const { response } = await api.POST("/api/v1/auth/switch/complete", { body: { token } });
        if (response.status === 204) {
          navigate("/api/v1/auth/start?continue=%2F");
          return;
        }
        setStep({ kind: "failed", message: response.status === 400 ? COMMON_TEXT.invalidLink : commonFailureText(response.status) });
      } catch {
        setStep({ kind: "failed", message: COMMON_TEXT.network });
      }
    }
    void arrive();
  }, []);

  if (step.kind === "failed") {
    return (
      <div data-testid="switch-failed">
        <p className="form-message" role="alert">
          {step.message}
        </p>
        <p>
          <Link href="/sign-in">Sign in again</Link>
        </p>
      </div>
    );
  }
  return <p aria-live="polite">Opening the organization…</p>;
}
