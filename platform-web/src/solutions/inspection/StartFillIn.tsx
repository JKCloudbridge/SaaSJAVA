"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { fillInsHref, startFillIn, useFillInOptions } from "./fillIns";

/**
 * The fill-ins section of one template (Functional track FF1, ADR-0192): the button that starts a fill-in on the version in
 * use, when the API says the member may, the reason in the API's words when they may not, and the way to the fill-ins of
 * this template. Starting opens the new fill-in in the fill-ins page. It decides nothing: the flag only shows or hides the
 * button, and the start is decided again by the server, whose refusal is shown in its own words.
 */
export function StartFillIn({ templateId, refresh }: { templateId: string; refresh: number }) {
  const router = useRouter();
  const options = useFillInOptions(templateId, refresh);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | undefined>();

  if (options.status === "loading") {
    return (
      <p role="status" className="hint">
        Checking whether a fill-in can be started…
      </p>
    );
  }
  if (options.status === "failed") {
    return (
      <p className="form-message" role="alert">
        {options.failure.message}
      </p>
    );
  }

  const { canStart, activeVersion, reason } = options.options;
  const start = async () => {
    setBusy(true);
    setMessage(undefined);
    const outcome = await startFillIn(templateId);
    setBusy(false);
    if (outcome.ok) {
      router.push(fillInsHref(outcome.data.fillId));
      return;
    }
    setMessage(outcome.failure.message);
  };

  return (
    <div className="stack" data-testid="start-fill-in">
      {message ? (
        <p className="form-message" role="alert">
          {message}
        </p>
      ) : null}
      {canStart ? (
        <p className="hint">
          A fill-in is made on the version in use, version {activeVersion}, and stays on it even when a newer version is
          published later.
        </p>
      ) : reason ? (
        <p className="hint" data-testid="fill-in-reason">
          {reason}
        </p>
      ) : null}
      <div className="split">
        {canStart ? (
          <button type="button" className="button" onClick={() => void start()} disabled={busy}>
            {busy ? "Starting…" : "Start a fill-in"}
          </button>
        ) : null}
        <Link className="button button-secondary" href={`${fillInsHref()}?template=${templateId}`}>
          See the fill-ins of this template
        </Link>
      </div>
    </div>
  );
}
