"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { AppIcon } from "@/components/ui/AppIcon";
import { Icon } from "@/components/ui/Icon";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import {
  entryHref,
  entrySlug,
  findApplication,
  type MyApplication,
  type NavigationEntry,
} from "@/lib/applications/entries";
import { areaOf, canSetUp, settingsLanding, settingsTabs, type Area } from "@/lib/shell/areas";
import { useSession } from "@/lib/session/SessionProvider";
import { AppLauncher } from "./AppLauncher";

/**
 * The strip under the header (Sprint 18, ADR-0128; reshaped in UA1, ADR-0167). Its content follows where the person is:
 *
 * <ul>
 *   <li>In settings (the Home pages and the object pages): the launcher and two tabs, Home and Object settings. A tab
 *       the person has no ability for is not drawn. No gear: they are already in settings.</li>
 *   <li>In an application: the launcher, the application's name, its pages as tabs and the gear back to settings.</li>
 *   <li>On every other page (the approvals inbox, notifications, a record screen outside an application, switching
 *       organization): the launcher, the name of the area and the gear.</li>
 *   <li>The gear is drawn only for a person with at least one ability the setup pages are for.</li>
 * </ul>
 *
 * It is drawn from the API's list of applications and the abilities the API lists, and decides nothing. It appears only
 * for a signed-in member on an organization's address, and on the platform's console (where it names the area).
 */
export function ControlBar() {
  const pathname = usePathname() ?? "";
  const area = areaOf(pathname);
  return area.kind === "console" ? <ConsoleStrip /> : <OrganizationStrip area={area} />;
}

/** The console's strip: the name of the area. No launcher (the platform has no applications) and no setup tabs. */
function ConsoleStrip() {
  const { state } = useSession();
  const platform = usePlatformAddress();
  if (state.status !== "signedIn" || platform.isPlatformHost !== true || state.user.platformRoles.length === 0) {
    return null;
  }
  return (
    <div className="control-bar" data-testid="control-bar">
      <div className="control-app" data-testid="control-app">
        <span className="control-app-name">Platform console</span>
      </div>
      <span className="control-spacer" />
    </div>
  );
}

function OrganizationStrip({ area }: { area: Area }) {
  const { enabled, state } = useApplications();
  const { state: session } = useSession();
  if (!enabled) {
    return null;
  }
  const abilities = session.status === "signedIn" ? session.user.abilities : [];
  const setUp = canSetUp(abilities);
  const application =
    area.kind === "application" ? findApplication(state.applications, area.application) : undefined;
  const inSettings = area.kind === "home" || area.kind === "objects";
  const gear = !inSettings && setUp ? settingsLanding(abilities) : null;
  return (
    <div className="control-bar" data-testid="control-bar">
      <AppLauncher current={application?.apiName} canSetUp={setUp} />
      {inSettings ? (
        <SettingsTabs area={area} abilities={abilities} />
      ) : (
        <>
          <div className="control-app" data-testid="control-app">
            {application ? (
              <>
                <AppIcon iconKey={application.iconKey} label={application.label} />
                <span className="control-app-name">{application.label}</span>
              </>
            ) : (
              <AreaName area={area} />
            )}
          </div>
          {application ? (
            <PageTabs application={application} current={area.kind === "application" ? area.entry : undefined} />
          ) : (
            <span className="control-spacer" />
          )}
        </>
      )}
      {gear ? (
        <Link href={gear} className="control-gear" aria-label="Setup" title="Setup">
          <Icon name="setup" className="control-gear-icon" />
        </Link>
      ) : null}
    </div>
  );
}

/** What the strip calls the place when it is not an application that is open. */
function AreaName({ area }: { area: Area }): ReactNode {
  if (area.kind === "application") {
    return <span className="control-app-none">Application</span>;
  }
  if (area.kind === "neutral" && area.name) {
    return <span className="control-app-name">{area.name}</span>;
  }
  return <span className="control-app-none">No application open</span>;
}

/** The two tabs of settings. Each is drawn only when the abilities the API lists give the person a page behind it. */
function SettingsTabs({ area, abilities }: { area: Area; abilities: readonly string[] }) {
  const tabs = settingsTabs(abilities);
  if (!tabs.home && !tabs.objects) {
    // A person who typed a settings address without any setup ability: the pages answer in the API's words.
    return <span className="control-spacer" />;
  }
  return (
    <nav className="control-tabs" aria-label="Settings" data-testid="settings-tabs">
      <ul>
        {tabs.home ? (
          <li>
            <Link href={tabs.home} className="control-tab" aria-current={area.kind === "home" ? "page" : undefined}>
              Home
            </Link>
          </li>
        ) : null}
        {tabs.objects ? (
          <li>
            <Link
              href={tabs.objects}
              className="control-tab"
              aria-current={area.kind === "objects" ? "page" : undefined}
            >
              Object settings
            </Link>
          </li>
        ) : null}
      </ul>
    </nav>
  );
}

function PageTabs({ application, current }: { application: MyApplication; current?: string }) {
  return (
    <nav className="control-tabs" aria-label={`${application.label} pages`}>
      <ul>
        {application.items.map((entry, index) => (
          <li key={`${entry.type}-${entry.label}-${index}`}>
            <Tab application={application} entry={entry} current={current} />
          </li>
        ))}
      </ul>
    </nav>
  );
}

function Tab({
  application,
  entry,
  current,
}: {
  application: MyApplication;
  entry: NavigationEntry;
  current?: string;
}) {
  if (entry.type === "HEADING") {
    return <span className="control-tab-heading">{entry.label}</span>;
  }
  if (entry.available === false) {
    // The entry exists but cannot be opened now: shown dimmed with the reason, never hidden and never a dead link.
    return (
      <span className="control-tab control-tab-unavailable" aria-disabled="true" title="Not available right now">
        {entry.label}
      </span>
    );
  }
  const href = entryHref(application, entry);
  if (!href) {
    return <span className="control-tab control-tab-unavailable">{entry.label}</span>;
  }
  // A link leaves the application, so it is never the page that is open.
  const isCurrent = entry.type !== "LINK" && entrySlug(entry) === current;
  return (
    <Link href={href} className="control-tab" aria-current={isCurrent ? "page" : undefined}>
      {entry.label}
    </Link>
  );
}
