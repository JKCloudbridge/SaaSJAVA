"use client";

import Link from "next/link";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useSession } from "@/lib/session/SessionProvider";

/**
 * The first things a person can do from the home page, as the API's answers allow: on the platform host, create an
 * account when signed out, create an organization when signed in, and open the console when the API lists a platform
 * role for the person. Presentation only; the backend refuses whatever the person may not do.
 */
export function HomeActions() {
  const { state } = useSession();
  const platform = usePlatformAddress();

  if (platform.isPlatformHost !== true) {
    return null;
  }
  if (state.status === "signedIn") {
    // The console is for a person the API lists a platform role for; the console itself checks again (UA1, ADR-0167).
    const hasConsole = state.user.platformRoles.length > 0;
    return (
      <p data-testid="home-actions">
        <Link href="/organizations/new">Create an organization</Link>
        {hasConsole ? (
          <>
            {" · "}
            <Link href="/console">Platform console</Link>
          </>
        ) : null}
      </p>
    );
  }
  if (state.status === "signedOut") {
    return (
      <p data-testid="home-actions">
        <Link href="/sign-up">Create an account</Link> · <Link href="/sign-in">Sign in</Link>
      </p>
    );
  }
  return null;
}
