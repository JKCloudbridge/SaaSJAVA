"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { setupHref as setupLink } from "@/lib/applications/setupLink";
import { useSession } from "@/lib/session/SessionProvider";
import { Icon } from "@/components/ui/Icon";
import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";

/**
 * The link to the approvals inbox, with the number of tasks that wait. The API says whether to show it at all (the plan has
 * approvals, or the member was asked or submitted something) and how many tasks wait; the link is a convenience and the
 * inbox page answers for itself. A failure to ask shows no link and no error: nothing about approvals is lost by it.
 */
function ApprovalsItem({ pathname }: { pathname: string }) {
  const [summary, setSummary] = useState<{ open: number; available: boolean } | undefined>();
  useEffect(() => {
    let live = true;
    void (async () => {
      try {
        const { data } = await api.GET("/api/v1/approvals/inbox/summary");
        if (live && data) {
          setSummary({ open: data.data.openTasks, available: data.data.available });
        }
      } catch {
        // No link, no error.
      }
    })();
    return () => {
      live = false;
    };
  }, [pathname]);
  if (!summary?.available) {
    return null;
  }
  return (
    <li>
      <Link href="/approvals" aria-current={pathname.startsWith("/approvals") ? "page" : undefined}>
        <Icon name="clipboard" />
        Approvals
        {summary.open > 0 ? (
          <span className="badge" data-testid="approvals-count" aria-label={`${summary.open} waiting`}>
            {summary.open}
          </span>
        ) : null}
      </Link>
    </li>
  );
}

/**
 * The main navigation. "Members" is offered to a signed-in person on an organization's address; whether the person may
 * open it is the API's answer (the page says so when the API refuses), never this component's decision.
 *
 * <p>The links written here (Home, Console, Members, Audit trail, Setup) are the platform's own pages, the same for every
 * organization, so they stay fixed. What an organization configured for its members, its applications and their
 * navigation, is drawn from the API by the control bar and the launcher (Sprint 18), not here.
 */
export function ShellNav() {
  const { state } = useSession();
  const platform = usePlatformAddress();
  const pathname = usePathname();
  const showMembers = state.status === "signedIn" && platform.isPlatformHost === false;
  // The setup pages are offered to someone the API lists the ability to manage access, or to view objects, for; the API
  // decides again on every call. The link opens the first page the listed ability is for.
  const abilities = state.status === "signedIn" ? state.user.abilities : [];
  const setupHref = setupLink(abilities);
  const showSetup = showMembers && setupHref !== null;
  // The audit trail is offered to someone the API lists the ability to view it for; the API decides again on every call.
  const showAudit = showMembers && state.status === "signedIn" && state.user.abilities.includes("audit.view");
  // The console link is a convenience for a person the API lists a platform role for; the API refuses the rest.
  const showConsole =
    state.status === "signedIn" && platform.isPlatformHost === true && state.user.platformRoles.length > 0;
  return (
    <ul>
      <li>
        <Link href="/" aria-current={pathname === "/" ? "page" : undefined}>
          <Icon name="home" />
          Home
        </Link>
      </li>
      {showConsole ? (
        <li>
          <Link href="/console" aria-current={pathname.startsWith("/console") ? "page" : undefined}>
            <Icon name="console" />
            Console
          </Link>
        </li>
      ) : null}
      {showMembers ? (
        <li>
          <Link href="/members" aria-current={pathname === "/members" ? "page" : undefined}>
            <Icon name="members" />
            Members
          </Link>
        </li>
      ) : null}
      {showMembers ? <ApprovalsItem pathname={pathname} /> : null}
      {showAudit ? (
        <li>
          <Link href="/audit" aria-current={pathname === "/audit" ? "page" : undefined}>
            <Icon name="audit" />
            Audit trail
          </Link>
        </li>
      ) : null}
      {showSetup ? (
        <li>
          <Link href={setupHref ?? "/setup/objects"} aria-current={pathname.startsWith("/setup") ? "page" : undefined}>
            <Icon name="setup" />
            Setup
          </Link>
        </li>
      ) : null}
    </ul>
  );
}
