"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useSession } from "@/lib/session/SessionProvider";

/**
 * The frame of every console page. The rail beside the content belongs to the shell (UA1, ADR-0167). It only checks
 * what it can know cheaply (is there a sign-in, is this the platform address, does the API list a platform role for the
 * person) to avoid showing a screen that cannot work; what each role may actually do is the API's answer, shown in its
 * own words by the panels. Nothing here decides a permission.
 */
export function ConsoleFrame({ children }: { children: ReactNode }) {
  const { state } = useSession();
  const platform = usePlatformAddress();

  if (state.status === "loading" || platform.isPlatformHost === undefined) {
    return <p aria-live="polite">Loading…</p>;
  }
  if (state.status !== "signedIn") {
    return (
      <p className="empty-state" data-testid="console-needs-sign-in">
        Sign in first. <Link href="/sign-in?continue=/console">Sign in</Link>
      </p>
    );
  }
  if (platform.isPlatformHost === false) {
    return (
      <p className="empty-state" data-testid="console-wrong-host">
        The console is at the platform address. <a href={`${platform.origin}/console`}>Go to the platform address</a>
      </p>
    );
  }
  if (state.user.platformRoles.length === 0) {
    return (
      <p className="empty-state" data-testid="console-no-role">
        This account holds no platform role.
      </p>
    );
  }
  return (
    <div className="stack">
      <p className="hint" data-testid="console-roles">
        Platform roles: {state.user.platformRoles.join(", ")}
      </p>
      {children}
    </div>
  );
}
