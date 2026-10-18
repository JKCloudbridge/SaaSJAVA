"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useSession } from "@/lib/session/SessionProvider";

const SECTIONS = [
  { href: "/console", label: "Organizations" },
  { href: "/console/organizations/new", label: "Set up an organization" },
  { href: "/console/plans", label: "Plans" },
  { href: "/console/people", label: "Platform people" },
  { href: "/console/sessions", label: "Sessions" },
  { href: "/console/automation-health", label: "Automation health" },
  { href: "/console/audit", label: "Audit trail" },
];

/** The list of organizations is also the page of one organization; every other section is its own address. */
function isCurrent(pathname: string, href: string): boolean {
  if (href === "/console") {
    return pathname === "/console" || (pathname.startsWith("/console/organizations/") && pathname !== SECTIONS[1]?.href);
  }
  return pathname === href;
}

/**
 * The frame of every console page. It only checks what it can know cheaply (is there a sign-in, is this the platform
 * address, does the API list a platform role for the person) to avoid showing a screen that cannot work; what each role
 * may actually do is the API's answer, shown in its own words by the panels. Nothing here decides a permission.
 */
export function ConsoleFrame({ children }: { children: ReactNode }) {
  const { state } = useSession();
  const platform = usePlatformAddress();
  const pathname = usePathname() ?? "";

  if (state.status === "loading" || platform.isPlatformHost === undefined) {
    return <p aria-live="polite">Loading…</p>;
  }
  if (state.status !== "signedIn") {
    return (
      <p className="empty-state" data-testid="console-needs-sign-in">
        Sign in first. <Link href="/sign-in?continue=/console">Sign in</Link>
      </p>
    );
  }
  if (platform.isPlatformHost === false) {
    return (
      <p className="empty-state" data-testid="console-wrong-host">
        The console is at the platform address. <a href={`${platform.origin}/console`}>Go to the platform address</a>
      </p>
    );
  }
  if (state.user.platformRoles.length === 0) {
    return (
      <p className="empty-state" data-testid="console-no-role">
        This account holds no platform role.
      </p>
    );
  }
  return (
    <div className="split">
      <nav className="rail" aria-label="Console">
        <p className="rail-label">Console</p>
        <ul>
          {SECTIONS.map((section) => (
            <li key={section.href}>
              <Link href={section.href} aria-current={isCurrent(pathname, section.href) ? "page" : undefined}>
                {section.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <div className="split-content stack">
        <p className="hint" data-testid="console-roles">
          Platform roles: {state.user.platformRoles.join(", ")}
        </p>
        {children}
      </div>
    </div>
  );
}
