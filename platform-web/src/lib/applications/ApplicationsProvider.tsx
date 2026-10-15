"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { api } from "@/lib/api/client";
import { failureFromNetworkError } from "@/lib/api/errors";
import { log } from "@/lib/log";
import { useSession } from "@/lib/session/SessionProvider";
import type { MyApplication } from "./entries";

/** What the page knows about the member's applications. */
export type ApplicationsState =
  | { status: "idle"; applications: readonly MyApplication[] }
  | { status: "loading"; applications: readonly MyApplication[] }
  | { status: "ready"; applications: readonly MyApplication[] }
  | { status: "error"; applications: readonly MyApplication[]; message: string };

interface ApplicationsApi {
  /** Whether the shell offers applications at all: a signed-in member on an organization's address. */
  enabled: boolean;
  state: ApplicationsState;
  /** Asks the API again. What was shown stays on screen until the answer arrives. */
  refresh: () => Promise<void>;
}

/** A list older than this is asked for again when the member comes back to the tab (milliseconds). */
export const STALE_AFTER_MS = 60_000;

const ApplicationsContext = createContext<ApplicationsApi | undefined>(undefined);

/**
 * The member's applications and their navigation, as the API lists them, kept once for the whole shell (Sprint 18,
 * ADR-0128). It is the browser's copy of the metadata the shell is built from, and it follows the staleness rules the
 * server's own caches follow: a copy is never trusted to be current, only recent.
 *
 * <ul>
 *   <li>It is asked for when a member is signed in on an organization host, and for nobody else.</li>
 *   <li>It is asked for again when the member returns to the page and the copy is older than a minute, when a page
 *       reports that something it opened is gone or refused ({@link ApplicationsApi.refresh}), and on demand.</li>
 *   <li>While it is being asked for again, the old copy stays on screen; a failed attempt keeps it too and says so.</li>
 *   <li>It decides nothing. An entry that is in the list can still be refused when opened, and then the page shows the
 *       API's words; an entry that is not in the list cannot be reached by a link the shell offers.</li>
 * </ul>
 */
export function ApplicationsProvider({ children }: { children: ReactNode }) {
  const { state: session } = useSession();
  const platform = usePlatformAddress();
  const enabled = session.status === "signedIn" && platform.isPlatformHost === false;
  const [state, setState] = useState<ApplicationsState>({ status: "idle", applications: [] });
  const loadedAt = useRef(0);
  const inFlight = useRef<Promise<void> | null>(null);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const refresh = useCallback((): Promise<void> => {
    if (inFlight.current) {
      return inFlight.current;
    }
    setState((current) => ({ status: "loading", applications: current.applications }));
    const request = (async () => {
      try {
        const { data, response } = await api.GET("/api/v1/applications");
        if (!alive.current) {
          return;
        }
        if (data) {
          loadedAt.current = Date.now();
          setState((current) =>
            // The same answer again changes nothing, so a screen that is open does not redraw for no reason.
            current.status !== "error" && sameApplications(current.applications, data.data)
              ? { status: "ready", applications: current.applications }
              : { status: "ready", applications: data.data },
          );
          return;
        }
        log("warn", "the applications could not be loaded", { http_status: response.status });
        setState((current) => ({
          status: "error",
          applications: current.applications,
          message: "Your applications could not be loaded.",
        }));
      } catch {
        if (alive.current) {
          setState((current) => ({
            status: "error",
            applications: current.applications,
            message: failureFromNetworkError().message,
          }));
        }
      } finally {
        inFlight.current = null;
      }
    })();
    inFlight.current = request;
    return request;
  }, []);

  useEffect(() => {
    if (enabled) {
      void refresh();
    }
  }, [enabled, refresh]);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    function onVisible() {
      if (document.visibilityState === "visible" && Date.now() - loadedAt.current > STALE_AFTER_MS) {
        void refresh();
      }
    }
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [enabled, refresh]);

  const value = useMemo(
    () => ({ enabled, state: enabled ? state : { status: "idle" as const, applications: [] }, refresh }),
    [enabled, state, refresh],
  );
  return <ApplicationsContext.Provider value={value}>{children}</ApplicationsContext.Provider>;
}

/** The member's applications. Must be used inside {@link ApplicationsProvider}. */
export function useApplications(): ApplicationsApi {
  const context = useContext(ApplicationsContext);
  if (!context) {
    throw new Error("useApplications must be used inside an ApplicationsProvider");
  }
  return context;
}

function sameApplications(a: readonly MyApplication[], b: readonly MyApplication[]): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
