"use client";

import { useEffect } from "react";
import { log } from "@/lib/log";

/** Last line of defence for an unexpected rendering failure: a calm message, no technical detail. */
export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    // Only the digest (an opaque identifier of the server-side failure) is logged, never the message.
    log("error", "page failed to render", { digest: error.digest });
  }, [error]);

  return (
    <section className="card" role="alert">
      <h1>Something went wrong</h1>
      <p>The page could not be shown. Try again; if it keeps happening, contact support.</p>
      <button type="button" className="button" onClick={reset}>
        Try again
      </button>
    </section>
  );
}
