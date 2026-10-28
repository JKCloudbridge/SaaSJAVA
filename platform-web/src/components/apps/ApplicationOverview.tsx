"use client";

import Link from "next/link";
import { AppIcon } from "@/components/ui/AppIcon";
import { Icon } from "@/components/ui/Icon";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import {
  entryHref,
  findApplication,
  functionEntries,
  isComingSoon,
  isOpenable,
  isPlaceholder,
} from "@/lib/applications/entries";

/**
 * The Home of an application that has no Home page of its own (FA9, ADR-0203): its name, a line saying whether it is
 * being prepared, and a card for each of its tabs, each card a link to that tab. It is drawn from the entries the API
 * lists for the member, never from a list in the browser, so making a placeholder real later changes data and not this
 * page. A tab that is only announced says so on its card and still opens, on the page that says it is being prepared.
 */
export function ApplicationOverview({ applicationApiName }: { applicationApiName: string }) {
  const { state } = useApplications();
  const application = findApplication(state.applications, applicationApiName);
  if (!application) {
    return (
      <div className="custom-page-loading" role="status" aria-live="polite">
        <span className="skeleton-line" />
        <span className="visually-hidden">Loading</span>
      </div>
    );
  }
  const tabs = functionEntries(application);
  const preparing = isComingSoon(application);
  return (
    <div className="overview" data-testid="application-overview">
      <header className="overview-head">
        <AppIcon iconKey={application.iconKey} label={application.label} />
        <div>
          <h1 className="overview-title">{application.label}</h1>
          <p className="overview-lead">
            {preparing
              ? "This application is being prepared. Its tabs are listed below so you can see what is coming."
              : "Open one of its tabs to start."}
          </p>
        </div>
        {application.group ? <span className="overview-group">{application.group}</span> : null}
      </header>
      {tabs.length > 0 ? (
        <ul className="overview-cards">
          {tabs.map((entry, index) => {
            const href = isOpenable(entry) ? entryHref(application, entry) : null;
            const soon = isPlaceholder(entry);
            const body = (
              <>
                <span className="overview-card-name">{entry.label}</span>
                {soon ? <span className="overview-card-soon">Coming soon</span> : null}
                {href ? <Icon name="arrowRight" className="overview-card-go" /> : null}
              </>
            );
            return (
              <li key={`${entry.type}-${entry.label}-${index}`}>
                {href ? (
                  <Link href={href} className="overview-card">
                    {body}
                  </Link>
                ) : (
                  <span className="overview-card overview-card-off">{body}</span>
                )}
              </li>
            );
          })}
        </ul>
      ) : (
        <div className="empty-state" role="status">
          <p>This application has no tabs yet.</p>
        </div>
      )}
    </div>
  );
}
