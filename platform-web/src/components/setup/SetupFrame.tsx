"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { WorkingSetBar } from "./WorkingSetBar";

const SECTIONS = [
  { href: "/setup/profiles", label: "Profiles" },
  { href: "/setup/access-policies", label: "Access policies" },
  { href: "/setup/groups", label: "Groups" },
  { href: "/setup/roles", label: "Roles" },
  { href: "/setup/objects", label: "Objects" },
  { href: "/setup/changes", label: "Change sets" },
  { href: "/setup/history", label: "History" },
];

/** The pages where a change can be made, so the person can choose where it goes: live at once or into a change set. */
const OBJECT_PAGES = ["/setup/objects", "/setup/changes"];

/**
 * The frame of the setup pages (profiles, access policies, groups, roles, objects, change sets, history) of the
 * organization the address names. It only navigates: whether the person may open a page is the API's answer, which each page shows in the API's own words.
 */
export function SetupFrame({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  return (
    <div className="stack">
      <nav aria-label="Setup">
        <ul>
          {SECTIONS.map((section) => (
            <li key={section.href}>
              <Link href={section.href} aria-current={pathname.startsWith(section.href) ? "page" : undefined}>
                {section.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      {OBJECT_PAGES.some((page) => pathname.startsWith(page)) ? <WorkingSetBar /> : null}
      {children}
    </div>
  );
}
