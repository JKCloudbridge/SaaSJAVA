import Link from "next/link";
import type { ReactNode } from "react";
import { NotificationBell } from "@/components/notifications/NotificationBell";
import { PLATFORM_BRAND_NAME } from "@/lib/brand";
import { ApprovalsLink } from "./ApprovalsLink";
import { ControlBar } from "./ControlBar";
import { OrganizationName } from "./OrganizationName";
import { OrganizationSwitcher } from "./OrganizationSwitcher";
import { ShellRail } from "./ShellRail";
import { UserMenu } from "./UserMenu";
import { WorkingSetSlot } from "./WorkingSetSlot";

/**
 * The stable frame of every page: header (product, organization and user context), the strip, the side rail where the
 * area has one, and the content area. The organization name is what the API reports for the address of the page
 * (Sprint 2); the signed-in user is what the API reports about the caller (Sprint 3); the organization switcher lists
 * what the API says the caller belongs to (Sprint 5); the approvals link and the bell show what waits for the member.
 *
 * <p>Under the header, a signed-in member of an organization gets the strip (Sprint 18, reshaped in UA1, ADR-0167): the
 * application launcher and, by where the person is, the settings tabs, the open application's pages, or the name of the
 * area, with the gear to settings for a person who may set things up. The side rail (the Home rail, the console rail)
 * sits beside the content on the pages that belong to it. There is no permanent list of links: every page has its place
 * in the strip, the rail, the header or the launcher.
 *
 * The brand slot at the start of the header shows the product name (a placeholder, see `lib/brand.ts`); it leads to the
 * start page, which sends each person to where they land. It is where an organization's own logo will go once a later
 * sprint stores one.
 */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="shell-header">
        <Link href="/" className="shell-brand">
          <span className="shell-brand-mark" aria-hidden="true">
            {PLATFORM_BRAND_NAME.charAt(0)}
          </span>
          {PLATFORM_BRAND_NAME}
        </Link>
        <div className="shell-context" aria-label="Session">
          <OrganizationName />
          <OrganizationSwitcher />
          <ApprovalsLink />
          <NotificationBell />
          <UserMenu />
        </div>
      </header>
      <ControlBar />
      <ShellRail />
      <main id="main" className="shell-main">
        <WorkingSetSlot />
        {children}
      </main>
    </div>
  );
}
