"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api } from "@/lib/api/client";
import { FORGERY_COOKIE, readCookie } from "@/lib/api/forgery";
import { components } from "@/lib/api/generated/schema";
import { log } from "@/lib/log";
import { navigate } from "@/lib/navigation";

export type CurrentUser = components["schemas"]["CurrentUser"];

/**
 * What the page knows about who is signed in. It only ever shows what the API reports: the session lives in cookies
 * the page cannot read, and nothing here decides what a person may do (the backend does, on every request).
 */
export type SessionState =
  | { status: "loading" }
  | { status: "signedOut" }
  | { status: "signedIn"; user: CurrentUser }
  | { status: "unavailable" };

interface SessionApi {
  state: SessionState;
  /** Ends the sign-in on the server and goes to the sign-in page. */
  signOut: () => Promise<void>;
}

const SessionContext = createContext<SessionApi | undefined>(undefined);

/** Makes sure the page holds the forgery-protection cookie before it changes something. */
export async function ensureForgeryCookie(): Promise<void> {
  if (!readCookie(FORGERY_COOKIE)) {
    await api.GET("/api/v1/auth/csrf");
  }
}

/**
 * Asks the API who the caller is. An access token that has run out is replaced once through the refresh cookie before
 * the page gives up and reports "signed out".
 */
export async function fetchSession(): Promise<SessionState> {
  try {
    const first = await api.GET("/api/v1/auth/me");
    if (first.data) {
      return { status: "signedIn", user: first.data.data };
    }
    if (first.response.status !== 401) {
      return { status: "unavailable" };
    }
    await ensureForgeryCookie();
    const refreshed = await api.POST("/api/v1/auth/refresh");
    if (refreshed.response.status === 401) {
      return { status: "signedOut" };
    }
    if (refreshed.response.status !== 204) {
      return { status: "unavailable" };
    }
    const second = await api.GET("/api/v1/auth/me");
    if (second.data) {
      return { status: "signedIn", user: second.data.data };
    }
    return second.response.status === 401 ? { status: "signedOut" } : { status: "unavailable" };
  } catch {
    return { status: "unavailable" };
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    void fetchSession().then((next) => {
      if (!cancelled) {
        setState(next);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const signOut = useCallback(async () => {
    try {
      await ensureForgeryCookie();
      await api.POST("/api/v1/auth/sign-out");
    } catch {
      // The cookies are removed by the answer when it arrives; if it does not, the sign-in page still opens.
      log("warn", "sign-out request did not complete");
    }
    setState({ status: "signedOut" });
    navigate("/sign-in");
  }, []);

  const value = useMemo(() => ({ state, signOut }), [state, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

/** The current sign-in state. Must be used inside {@link SessionProvider}. */
export function useSession(): SessionApi {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error("useSession must be used inside a SessionProvider");
  }
  return context;
}
