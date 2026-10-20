"use client";

import { usePathname, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { useSession } from "@/lib/session/SessionProvider";
import { areaOf, consoleRail, homeRail, objectRail } from "@/lib/shell/areas";
import { useOpenObject } from "@/lib/shell/objectContext";
import { SideRail } from "./SideRail";

/**
 * The rail beside the content (UA1, ADR-0167): the Home rail on the setup and administration pages of an organization,
 * the console rail on the platform's console, and nothing on every other page. The entries come from `homeRail`, which
 * keeps only what the abilities the API lists allow; a person with nothing to open has no rail at all.
 */
export function ShellRail() {
  const pathname = usePathname() ?? "";
  const area = areaOf(pathname);
  if (area.kind === "console") {
    return <PlatformRail pathname={pathname} />;
  }
  if (area.kind === "home") {
    return <HomeRail pathname={pathname} />;
  }
  if (area.kind === "objects") {
    // The tab is in the query, which a statically built page may read only inside Suspense.
    return (
      <Suspense fallback={null}>
        <ObjectRail pathname={pathname} />
      </Suspense>
    );
  }
  return null;
}

/**
 * The rail of one object (UA3, ADR-0170): its tabs, named after the object the page loaded. The list of objects has no
 * rail. The rail draws for anybody who reaches the page; the page asks the API and shows its words when it refuses.
 */
function ObjectRail({ pathname }: { pathname: string }) {
  const search = useSearchParams()?.toString() ?? "";
  const { enabled } = useApplications();
  const { state } = useSession();
  const open = useOpenObject();
  const apiName = objectOf(pathname);
  if (!enabled || state.status !== "signedIn" || apiName === null) {
    return null;
  }
  const label = open && open.apiName === apiName ? open.label : apiName;
  return <SideRail label={label} entries={objectRail(apiName)} pathname={pathname} search={search} />;
}

/** The API name an address under Object settings names, or null on the list itself. */
function objectOf(pathname: string): string | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] !== "setup" || parts[1] !== "objects" || !parts[2]) {
    return null;
  }
  try {
    return decodeURIComponent(parts[2]);
  } catch {
    return null;
  }
}

function HomeRail({ pathname }: { pathname: string }) {
  const { enabled } = useApplications();
  const { state } = useSession();
  if (!enabled || state.status !== "signedIn") {
    return null;
  }
  const entries = homeRail(state.user.abilities);
  return entries.length > 0 ? <SideRail label="Home" entries={entries} pathname={pathname} /> : null;
}

/** The console's rail: only for a person the API lists a platform role for, on the platform address. */
function PlatformRail({ pathname }: { pathname: string }) {
  const { state } = useSession();
  const platform = usePlatformAddress();
  if (state.status !== "signedIn" || platform.isPlatformHost !== true || state.user.platformRoles.length === 0) {
    return null;
  }
  return <SideRail label="Console" entries={consoleRail()} pathname={pathname} />;
}
