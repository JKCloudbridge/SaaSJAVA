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
  return (
    <ul>
      <li>
        <Link href="/" aria-current={pathname === "/" ? "page" : undefined}>
          Home
        </Link>
      </li>
      {showMembers ? (
        <li>
          <Link href="/members" aria-current={pathname === "/members" ? "page" : undefined}>
            Members
          </Link>
        </li>
      ) : null}
    </ul>
  );
}
