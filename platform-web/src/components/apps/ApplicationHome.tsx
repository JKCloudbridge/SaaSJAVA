"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { entryHref, findApplication, firstPage } from "@/lib/applications/entries";
import { useSession } from "@/lib/session/SessionProvider";
import { setupHref } from "@/lib/applications/setupLink";

/**
 * The address of an application without a page (Sprint 18, ADR-0128): it opens the application on its first page, so
 * choosing an application in the launcher lands somewhere useful. The first page is the API's order, not a choice made
 * here. An application with nothing to open says so, and offers the way to set it up to someone who has one.
 */
export function ApplicationHome() {
  const params = useParams<{ application: string }>();
  const apiName = decodeURIComponent(params.application ?? "");
  const router = useRouter();
  const { state: session } = useSession();
  const { state } = useApplications();
  const application = findApplication(state.applications, apiName);
  const target = application ? firstPage(application) : undefined;
  const href = application && target ? entryHref(application, target) : null;

  useEffect(() => {
    if (href) {
      router.replace(href);
    }
  }, [href, router]);

  if (href) {
    return (
      <div className="custom-page-loading" role="status" aria-live="polite">
        <span className="skeleton-line" />
        <span className="visually-hidden">Opening {application?.label}</span>
      </div>
    );
  }
  if (!application) {
    return state.status === "idle" || state.status === "loading" ? (
      <div className="custom-page-loading" role="status" aria-live="polite">
        <span className="skeleton-line" />
        <span className="visually-hidden">Loading</span>
      </div>
    ) : (
      <div className="empty-state" role="status">
        <p>
          <strong>This application is not available.</strong>
        </p>
        <p>It may have been removed, or it may not be part of your applications.</p>
        <Link href="/">Back to the start</Link>
      </div>
    );
  }
  const setup = session.status === "signedIn" ? setupHref(session.user.abilities) : null;
  return (
    <>
      <h1 className="entry-title">{application.label}</h1>
      <div className="empty-state" role="status" data-testid="application-empty">
        <p>
          <strong>This application has no pages yet.</strong>
        </p>
        {setup ? (
          <p>
            Add some in <Link href="/setup/applications">Setup</Link>.
          </p>
        ) : (
          <p>Ask an administrator to add pages to it.</p>
        )}
      </div>
    </>
  );
}
