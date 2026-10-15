"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { AppIcon } from "@/components/ui/AppIcon";
import { Icon } from "@/components/ui/Icon";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { entryHref, entrySlug, findApplication, type MyApplication, type NavigationEntry } from "@/lib/applications/entries";
import { setupHref } from "@/lib/applications/setupLink";
import { useSession } from "@/lib/session/SessionProvider";
import { AppLauncher } from "./AppLauncher";

/** The application and the page the address names, from `/apps/<application>/<entry>`; empty elsewhere. */
function addressOf(pathname: string): { application?: string; entry?: string } {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] !== "apps" || !parts[1]) {
    return {};
  }
  try {
    return { application: decodeURIComponent(parts[1]), entry: parts[2] ? decodeURIComponent(parts[2]) : undefined };
  } catch {
    return {};
  }
}

/**
 * The row under the header that holds the member's place in the applications (Sprint 18, ADR-0128): the launcher, the
 * name of the application that is open, its pages as a row of links, and the way to Setup. It is drawn from the API's list
 * of applications and nothing else; a member with no applications still gets the launcher, which says so. It appears only
 * for a signed-in member on an organization's address, and leaves the platform's own pages to the navigation beside the
 * content.
 */
export function ControlBar() {
  const { enabled, state } = useApplications();
  const { state: session } = useSession();
  const pathname = usePathname();
  if (!enabled) {
    return null;
  }
  const address = addressOf(pathname);
  const application = address.application ? findApplication(state.applications, address.application) : undefined;
  const setup = session.status === "signedIn" ? setupHref(session.user.abilities) : null;
  return (
    <div className="control-bar" data-testid="control-bar">
      <AppLauncher current={application?.apiName} setupHref={setup} />
      <div className="control-app" data-testid="control-app">
        {application ? (
          <>
            <AppIcon iconKey={application.iconKey} label={application.label} />
            <span className="control-app-name">{application.label}</span>
          </>
        ) : (
          <span className="control-app-none">{address.application ? "Application" : "No application open"}</span>
        )}
      </div>
      {application ? <PageTabs application={application} current={address.entry} /> : <span className="control-spacer" />}
      {setup ? (
        <Link href={setup} className="control-gear" aria-label="Setup" title="Setup">
          <Icon name="setup" className="control-gear-icon" />
        </Link>
      ) : null}
    </div>
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
