"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { SignInForm } from "@/components/auth/SignInForm";
import { SignInLayout } from "@/components/auth/SignInLayout";
import { ApplicationGrid, type GridStatus } from "@/components/apps/ApplicationGrid";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { PLATFORM_BRAND_NAME } from "@/lib/brand";
import { useSession } from "@/lib/session/SessionProvider";
import { returnPathAfterSignIn, takeSignedInMarker } from "@/lib/session/signedInMarker";
import { canSetUp, landingFor } from "@/lib/shell/areas";
import { PlatformLanding } from "./PlatformLanding";

/**
 * The start page (UA1, ADR-0167; the platform's address since UA4, ADR-0171; the grid since FA9, ADR-0203). A signed-in
 * member of an organization sees the grid of the applications they may open (or, with none, General for a person who
 * may set things up and a plain page for anybody else). A signed-in person on the platform's address sees
 * `PlatformLanding` (their organizations, or straight into the only one right after signing in). A visitor who is not
 * signed in sees the sign-in form right here, so there is no extra click to `/sign-in`; that address still exists (a
 * deep link, and where a failed return lands).
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
    <SignInLayout title={`Welcome to ${PLATFORM_BRAND_NAME}`}>
      <div className="card">
        <SignInForm returnTo={returnPathAfterSignIn("/")} />
      </div>
    </SignInLayout>
  );
}

/**
 * The start page of an organization (FA9, ADR-0203): the grid of the applications the member may open, drawn right here
 * so `/` never redirects to itself and nothing flashes. A person with no application who may set things up is sent to
 * General; anybody else sees the plain page that says nothing is available yet. Nothing is decided until the list has
 * arrived.
 */
function OrganizationLanding({ abilities }: { abilities: readonly string[] }) {
  const { state, refresh } = useApplications();
  const router = useRouter();
  const from = useCameFrom();
  const known = state.applications.length > 0 || state.status === "ready";
  const landing = known ? landingFor(abilities, state.applications) : null;
  const setUp = canSetUp(abilities);

  useEffect(() => {
    if (landing?.kind === "setup") {
      router.replace(landing.href);
    }
  }, [landing, router]);

  if (landing?.kind === "setup") {
    return (
      <p role="status" aria-live="polite">
        Opening…
      </p>
    );
  }
  if (landing?.kind === "none") {
    return (
      <>
        <h1>Welcome</h1>
        <div className="empty-state" role="status" data-testid="no-application">
          <p>
            <strong>No application is available to you yet.</strong>
          </p>
          <p>When an administrator sets one up for you, it appears here.</p>
        </div>
      </>
    );
  }
  const status: GridStatus =
    state.status === "error" && !known
      ? { kind: "error", message: state.message }
      : known
        ? { kind: state.status === "error" ? "error" : "ready", message: state.status === "error" ? state.message : undefined }
        : { kind: "loading" };
  return (
    <ApplicationGrid
      applications={state.applications}
      status={status}
      current={from}
      canSetUp={setUp}
      onRetry={() => void refresh()}
    />
  );
}

/** The application the person came from, when the strip sent them here (`/?from=<name>`); read once, never trusted. */
function useCameFrom(): string | undefined {
  const [from, setFrom] = useState<string | undefined>();
  useEffect(() => {
    const value = new URLSearchParams(window.location.search).get("from");
    // eslint-disable-next-line react-hooks/set-state-in-effect -- the address is read after mount, so the server and first draw agree
    setFrom(value ?? undefined);
  }, []);
  return from;
}
