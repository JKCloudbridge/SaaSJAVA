import Link from "next/link";
import type { ReactNode } from "react";

/**
 * The stable frame of every page: header (product, organization and user context), navigation and the content
 * area. It is a static placeholder in this sprint. Later sprints fill the organization switcher (Sprint 5) and
 * build the navigation from application metadata (Sprint 18); the frame itself stays.
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
          <span>No organization selected</span>
          <span>Not signed in</span>
        </div>
      </header>
      <nav className="shell-nav" aria-label="Main">
        <ul>
          <li>
            <Link href="/" aria-current="page">
              Home
            </Link>
          </li>
        </ul>
      </nav>
      <main id="main" className="shell-main">
        {children}
      </main>
    </div>
  );
}
