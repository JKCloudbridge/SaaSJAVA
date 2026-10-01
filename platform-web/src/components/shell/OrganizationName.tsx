"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import { failureFromNetworkError, failureFromResponse, type ApiFailure } from "@/lib/api/errors";

type State = { kind: "loading" } | { kind: "ok"; name: string } | { kind: "failed"; failure: ApiFailure };

/** What a person sees for each way the question "which organization is this address?" can be answered. */
function describe(state: State): string {
  switch (state.kind) {
    case "loading":
      return "Checking organization…";
    case "ok":
      return state.name;
    case "failed":
      // The API's codes decide; the words here are only presentation.
      if (state.failure.code === "NOT_FOUND") {
        return "No organization selected";
      }
      if (state.failure.code === "TENANT_UNAVAILABLE") {
        return "This organization is not available";
      }
      return "Organization unknown";
  }
}

/**
 * Shows the name of the organization the address of this page belongs to, as the API reports it. The organization is
 * decided by the server from the host name; this component never says which organization to ask about, and nothing
 * it displays grants anything (the backend decides access, from sign-in in Sprint 3 and membership in Sprint 5).
 */
export function OrganizationName() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const { data, error, response } = await api.GET("/api/v1/tenant/current");
        if (cancelled) {
          return;
        }
        setState(
          data
            ? { kind: "ok", name: data.data.displayName }
            : { kind: "failed", failure: failureFromResponse(error, response) },
        );
      } catch {
        if (!cancelled) {
          setState({ kind: "failed", failure: failureFromNetworkError() });
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <span data-testid="organization" aria-live="polite">
      {describe(state)}
    </span>
  );
}
