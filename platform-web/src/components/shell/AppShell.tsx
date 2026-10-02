import type { ReactNode } from "react";
import { OrganizationName } from "./OrganizationName";
import { OrganizationSwitcher } from "./OrganizationSwitcher";
import { ShellNav } from "./ShellNav";
import { UserMenu } from "./UserMenu";

/**
 * The stable frame of every page: header (product, organization and user context), navigation and the content
 * area. The organization name is what the API reports for the address of the page (Sprint 2); the signed-in user is
 * what the API reports about the caller (Sprint 3); the organization switcher lists what the API says the caller
 * belongs to (Sprint 5). Later sprints build the navigation from application metadata (Sprint 18); the frame itself
 * stays.
 */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="shell-header">
        <span className="shell-brand">Platform</span>
        <div className="shell-context" aria-label="Session">
          <OrganizationName />
          <OrganizationSwitcher />
          <UserMenu />
        </div>
      </header>
      <nav className="shell-nav" aria-label="Main">
        <ShellNav />
      </nav>
      <main id="main" className="shell-main">
        {children}
      </main>
    </div>
  );
}
