"use client";

import Link from "next/link";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useSession } from "@/lib/session/SessionProvider";

/**
 * The signed-in state in the header, as the API reports it: who is signed in and a way out, or a way in. On the
 * platform's address a signed-in person can also found an organization (UA4, ADR-0171; the API enforces the limit).
 * Whether the person may do anything is decided by the backend, never by this component.
 */
export function UserMenu() {
  const { state, signOut } = useSession();
  const platform = usePlatformAddress();

  switch (state.status) {
    case "loading":
      return (
        <span data-testid="session" aria-live="polite">
          Checking sign-in…
        </span>
      );
    case "signedIn":
      return (
        <span className="user-menu" data-testid="session">
          <span className="user-avatar" aria-hidden="true">
            {state.user.displayName.charAt(0).toUpperCase()}
          </span>
          <span className="user-name" data-testid="user">
            {state.user.displayName}
          </span>
          {platform.isPlatformHost === true ? <Link href="/organizations/new">Create an organization</Link> : null}
          <button type="button" className="link-button" onClick={() => void signOut()}>
            Sign out
          </button>
        </span>
      );
    case "signedOut":
      return (
        <span className="user-menu" data-testid="session">
          <span>Not signed in</span>
          <Link href="/sign-in">Sign in</Link>
        </span>
      );
    case "unavailable":
      return (
        <span data-testid="session" role="status">
          Sign-in status could not be checked
        </span>
      );
  }
}
