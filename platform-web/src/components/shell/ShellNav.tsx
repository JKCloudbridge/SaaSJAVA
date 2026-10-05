"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useSession } from "@/lib/session/SessionProvider";

/**
 * The main navigation. "Members" is offered to a signed-in person on an organization's address; whether the person may
 * open it is the API's answer (the page says so when the API refuses), never this component's decision.
 */
export function ShellNav() {
  const { state } = useSession();
  const platform = usePlatformAddress();
  const pathname = usePathname();
  const showMembers = state.status === "signedIn" && platform.isPlatformHost === false;
  // The setup pages are offered to someone the API lists the ability to manage access, or to view objects, for; the API
  // decides again on every call. The link opens the first page the listed ability is for.
  const abilities = state.status === "signedIn" ? state.user.abilities : [];
  const setupHref = abilities.includes("access.manage") ? "/setup/profiles" : "/setup/objects";
  const showSetup = showMembers && (abilities.includes("access.manage") || abilities.includes("metadata.view"));
  // The audit trail is offered to someone the API lists the ability to view it for; the API decides again on every call.
  const showAudit = showMembers && state.status === "signedIn" && state.user.abilities.includes("audit.view");
  // The console link is a convenience for a person the API lists a platform role for; the API refuses the rest.
  const showConsole =
    state.status === "signedIn" && platform.isPlatformHost === true && state.user.platformRoles.length > 0;
  return (
    <ul>
      <li>
        <Link href="/" aria-current={pathname === "/" ? "page" : undefined}>
          Home
        </Link>
      </li>
      {showConsole ? (
        <li>
          <Link href="/console" aria-current={pathname.startsWith("/console") ? "page" : undefined}>
            Console
          </Link>
        </li>
      ) : null}
      {showMembers ? (
        <li>
          <Link href="/members" aria-current={pathname === "/members" ? "page" : undefined}>
            Members
          </Link>
        </li>
      ) : null}
      {showAudit ? (
        <li>
          <Link href="/audit" aria-current={pathname === "/audit" ? "page" : undefined}>
            Audit trail
          </Link>
        </li>
      ) : null}
      {showSetup ? (
        <li>
          <Link href={setupHref} aria-current={pathname.startsWith("/setup") ? "page" : undefined}>
            Setup
          </Link>
        </li>
      ) : null}
    </ul>
  );
}
