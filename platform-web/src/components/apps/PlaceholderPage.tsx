"use client";

import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { applicationHref, entryHref, isHomeEntry, type MyApplication, type NavigationEntry } from "@/lib/applications/entries";

/**
 * The page of a tab that is only announced (FA9, ADR-0203): the application it belongs to, its own name as the title,
 * the words "This function is being prepared", and the way back to Home. No fake data and no button that does
 * nothing: the page promises nothing it cannot do.
 */
export function PlaceholderPage({ application, entry }: { application: MyApplication; entry: NavigationEntry }) {
  const home = application.items.find(isHomeEntry);
  const back = (home ? entryHref(application, home) : null) ?? applicationHref(application);
  return (
    <div className="placeholder-page" data-testid="placeholder-page">
      <p className="placeholder-app">{application.label}</p>
      <h1 className="placeholder-title">{entry.label}</h1>
      <div className="placeholder-card" role="status">
        <Icon name="clock" className="placeholder-icon" />
        <div>
          <p className="placeholder-line">This function is being prepared</p>
          <p className="placeholder-hint">It will appear here when it is ready. Nothing is lost in the meantime.</p>
        </div>
      </div>
      <Link href={back} className="placeholder-back">
        <Icon name="arrowRight" className="placeholder-back-icon" />
        Back to Home
      </Link>
    </div>
  );
}
