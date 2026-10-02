"use client";

import Link from "next/link";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useSession } from "@/lib/session/SessionProvider";

/**
 * The first things a person can do from the home page, as the API's answers allow: on the platform host, create an
 * account when signed out and create an organization when signed in. Presentation only; the backend refuses whatever
 * the person may not do.
 */
export function HomeActions() {
  const { state } = useSession();
  const platform = usePlatformAddress();

  if (platform.isPlatformHost !== true) {
    return null;
  }
  if (state.status === "signedIn") {
    return (
      <p data-testid="home-actions">
        <Link href="/organizations/new">Create an organization</Link>
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
