import type { ReactNode } from "react";
import { PLATFORM_BRAND_NAME } from "@/lib/brand";
import { ControlBar } from "./ControlBar";
import { OrganizationName } from "./OrganizationName";
import { OrganizationSwitcher } from "./OrganizationSwitcher";
import { ShellNav } from "./ShellNav";
import { UserMenu } from "./UserMenu";

/**
 * The stable frame of every page: header (product, organization and user context), navigation and the content
 * area. The organization name is what the API reports for the address of the page (Sprint 2); the signed-in user is
 * what the API reports about the caller (Sprint 3); the organization switcher lists what the API says the caller
 * belongs to (Sprint 5). Under the header, a signed-in member of an organization gets the control bar (Sprint 18): the
 * application launcher, the open application's pages and the way to Setup, all drawn from the API's list of
 * applications. The navigation beside the content keeps the platform's own pages.
 *
 * The brand slot at the start of the header shows the product name (a placeholder, see `lib/brand.ts`). It is where an
 * organization's own logo will go once a later sprint stores one.
 */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="shell-header">
        <span className="shell-brand">
          <span className="shell-brand-mark" aria-hidden="true">
            {PLATFORM_BRAND_NAME.charAt(0)}
          </span>
          {PLATFORM_BRAND_NAME}
        </span>
        <div className="shell-context" aria-label="Session">
          <OrganizationName />
          <OrganizationSwitcher />
          <UserMenu />
        </div>
      </header>
      <ControlBar />
      <nav className="shell-nav" aria-label="Main">
        <ShellNav />
      </nav>
      <main id="main" className="shell-main">
        {children}
      </main>
    </div>
  );
}
