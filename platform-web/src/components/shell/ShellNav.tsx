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
  // The setup pages are offered to someone the API lists the ability to manage access for; the API decides again on every call.
  const showSetup = showMembers && state.status === "signedIn" && state.user.abilities.includes("access.manage");
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
      {showSetup ? (
        <li>
          <Link href="/setup/profiles" aria-current={pathname.startsWith("/setup") ? "page" : undefined}>
            Setup
          </Link>
        </li>
      ) : null}
    </ul>
  );
}
