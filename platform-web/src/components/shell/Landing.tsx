"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { HomeActions } from "@/components/account/HomeActions";
import { PlatformStatusCard } from "@/components/PlatformStatusCard";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { useSession } from "@/lib/session/SessionProvider";
import { landingFor } from "@/lib/shell/areas";

/**
 * The start page (UA1, ADR-0167). A signed-in member of an organization is sent on to where they land: the first
 * application the API lists for them; with none, a person who may set things up lands on General, and anybody else
 * stays here on a plain page that says no application is available yet. Everyone else (nobody signed in, the platform's
 * address) sees the welcome page with the first things they can do.
 *
 * <p>Nothing is decided before the data has arrived: while the session or the list of applications is on its way the
 * page says it is loading, so there is no flash of the wrong page and no redirect that is taken back.
 */
export function Landing() {
  const { state } = useSession();
  const platform = usePlatformAddress();
  if (state.status === "loading" || (state.status === "signedIn" && platform.isPlatformHost === undefined)) {
    return <p aria-live="polite">Loading…</p>;
  }
  if (state.status === "signedIn" && platform.isPlatformHost === false) {
    return <OrganizationLanding abilities={state.user.abilities} />;
  }
  return <Welcome />;
}

function Welcome() {
  return (
    <>
      <h1>Welcome</h1>
      <p>
        This is the web frontend of the platform. It shows what the platform API tells it; the API decides everything
        else. The panel below asks the API for its status.
      </p>
      <HomeActions />
      <PlatformStatusCard />
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
