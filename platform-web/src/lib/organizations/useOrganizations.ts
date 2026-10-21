"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import type { components } from "@/lib/api/generated/schema";

export type Organization = components["schemas"]["OrganizationSummary"];

export type OrganizationsState =
  | { status: "loading" }
  | { status: "ready"; organizations: Organization[] }
  | { status: "error"; message: string };

/**
 * The organizations the API says the signed-in person belongs to. The list is the API's answer and nothing else: no
 * organization or address is composed here. `reload` asks again after a failure.
 */
export function useOrganizations(enabled = true): { state: OrganizationsState; reload: () => void } {
  const [state, setState] = useState<OrganizationsState>({ status: "loading" });
  const [round, setRound] = useState(0);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    let cancelled = false;
    async function load() {
      try {
        const { data, error, response } = await api.GET("/api/v1/organizations");
        if (cancelled) {
          return;
        }
        setState(
          data
            ? { status: "ready", organizations: data.data }
            : { status: "error", message: failureFromResponse(error, response).message },
        );
      } catch {
        if (!cancelled) {
          setState({ status: "error", message: "The server could not be reached. Check your connection and try again." });
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [enabled, round]);

  const reload = useCallback(() => {
    setState({ status: "loading" });
    setRound((current) => current + 1);
  }, []);

  return { state, reload };
}
