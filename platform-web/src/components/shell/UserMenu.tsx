"use client";

import Link from "next/link";
import { useSession } from "@/lib/session/SessionProvider";

/**
 * The signed-in state in the header, as the API reports it: who is signed in and a way out, or a way in. It shows a
 * name and offers two links; whether the person may do anything is decided by the backend, never by this component.
 */
export function UserMenu() {
  const { state, signOut } = useSession();

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
          <span data-testid="user">{state.user.displayName}</span>
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
