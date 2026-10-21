"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { SignInForm } from "@/components/auth/SignInForm";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { PLATFORM_BRAND_NAME } from "@/lib/brand";
import { useSession } from "@/lib/session/SessionProvider";
import { returnPathAfterSignIn, takeSignedInMarker } from "@/lib/session/signedInMarker";
import { landingFor } from "@/lib/shell/areas";
import { PlatformLanding } from "./PlatformLanding";

/**
 * The start page (UA1, ADR-0167; the platform's address since UA4, ADR-0171). A signed-in member of an organization is
 * sent on to where they land: the first application the API lists for them; with none, a person who may set things up
 * lands on General, and anybody else stays here on a plain page that says no application is available yet. A signed-in
 * person on the platform's address sees `PlatformLanding` (their organizations, or straight into the only one right
 * after signing in). A visitor who is not signed in sees the sign-in form right here, so there is no extra click to
 * `/sign-in` for the ordinary case; that address still exists (a deep link, and where a failed return lands).
 *
 * <p>Nothing is decided before the data has arrived: while the session, the address or the "just signed in" fact is on
 * its way the page says it is loading, so there is no flash of the wrong page and no redirect that is taken back.
 */
export function Landing() {
  const { state } = useSession();
  const platform = usePlatformAddress();
  const justSignedIn = useJustSignedIn();
  if (
    state.status === "loading" ||
    justSignedIn === undefined ||
    (state.status === "signedIn" && platform.isPlatformHost === undefined)
  ) {
    return <p aria-live="polite">Loading…</p>;
  }
  if (state.status === "unavailable") {
    return (
      <div className="empty-state" role="alert" data-testid="platform-unreachable">
        <p>
          <strong>The platform could not be reached.</strong>
        </p>
        <p>Check your connection and try again in a moment.</p>
        <button type="button" className="link-button" onClick={() => window.location.reload()}>
          Try again
        </button>
      </div>
    );
  }
  if (state.status === "signedIn" && platform.isPlatformHost === false) {
    return <OrganizationLanding abilities={state.user.abilities} />;
  }
  if (state.status === "signedIn") {
    return <PlatformLanding user={state.user} justSignedIn={justSignedIn} />;
  }
  return <Welcome />;
}

/**
 * Whether this load follows a sign-in. Read once and removed from the address; the first reading is kept in a ref
 * because development builds run effects twice and the second run would find the marker already gone.
 */
function useJustSignedIn(): boolean | undefined {
  const [justSignedIn, setJustSignedIn] = useState<boolean | undefined>();
  const taken = useRef<boolean | undefined>(undefined);
  useEffect(() => {
    taken.current ??= takeSignedInMarker();
    setJustSignedIn(taken.current);
  }, []);
  return justSignedIn;
}

function Welcome() {
  return (
    <>
      <h1>Welcome to {PLATFORM_BRAND_NAME}</h1>
      <div className="card">
        <SignInForm returnTo={returnPathAfterSignIn("/")} />
      </div>
    </>
  );
}

function OrganizationLanding({ abilities }: { abilities: readonly string[] }) {
  const { state, refresh } = useApplications();
  const router = useRouter();
  const known = state.applications.length > 0 || state.status === "ready";
  const target = known ? landingFor(abilities, state.applications) : null;

  useEffect(() => {
    if (target) {
      router.replace(target);
    }
  }, [target, router]);

  if (target) {
    return (
      <p role="status" aria-live="polite">
        Opening…
      </p>
    );
  }
  if (!known && state.status === "error") {
    return (
      <div className="empty-state" role="alert" data-testid="landing-failed">
        <p>{state.message}</p>
        <button type="button" className="link-button" onClick={() => void refresh()}>
          Try again
        </button>
      </div>
    );
  }
  if (!known) {
    return (
      <p role="status" aria-live="polite">
        Loading…
      </p>
    );
  }
  return (
    <>
      <h1>Welcome</h1>
      <div className="empty-state" role="status" data-testid="no-application">
        <p>
          <strong>No application is available to you yet.</strong>
        </p>
        <p>When an administrator sets one up for you, it appears in the menu at the top left.</p>
      </div>
    </>
  );
}
