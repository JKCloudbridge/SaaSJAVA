"use client";

import { usePathname } from "next/navigation";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { useSession } from "@/lib/session/SessionProvider";
import { areaOf, consoleRail, homeRail } from "@/lib/shell/areas";
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
  return null;
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
