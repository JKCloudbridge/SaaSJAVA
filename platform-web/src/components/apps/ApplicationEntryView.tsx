"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { describeKind, findApplication, findEntry, type NavigationEntry } from "@/lib/applications/entries";
import { entryBase, recordHref } from "@/lib/records/paths";
import { ObjectListPage } from "@/components/records/ObjectListPage";
import { CustomPageFrame } from "./CustomPageFrame";

/** What the address names, decoded: the application and the entry inside it. */
function useAddress(): { application: string; entry: string } {
  const params = useParams<{ application: string; entry: string }>();
  return { application: decodeURIComponent(params.application ?? ""), entry: decodeURIComponent(params.entry ?? "") };
}

/**
 * One page of an application (Sprint 18, ADR-0128): what the address names, drawn from the member's list of applications.
 * The list is the API's answer; this component adds no rule to it. An entry the list does not have is shown as not
 * available, in the same words whether it never existed, was removed, or is out of the member's reach, after asking the
 * API once more in case the list is simply old. What each kind of entry shows:
 *
 * <ul>
 *   <li>a custom page: the sandboxed frame ({@link CustomPageFrame});</li>
 *   <li>an object: its list of records, with the create and edit forms behind it (Sprint 19);</li>
 *   <li>a record page: a note that the screen is not built yet. Record pages arrive with Sprint 20; the entry is already
 *       part of the navigation so the application looks complete.</li>
 * </ul>
 */
export function ApplicationEntryView() {
  const address = useAddress();
  const { state, refresh } = useApplications();
  const application = findApplication(state.applications, address.application);
  const entry = application ? findEntry(application, address.entry) : undefined;
  const key = `${address.application}/${address.entry}`;
  // The address the list was last asked about again. An address that is not in the list may be in a newer one, so
  // the API is asked once more before the page says it is not available.
  const [lookedAgain, setLookedAgain] = useState<string | null>(null);
  const requested = useRef<string | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const settled = state.status === "ready" || state.status === "error";
  useEffect(() => {
    // Once per address: asking changes the status of the list, which runs this effect again before the answer is in.
    if (!settled || entry || lookedAgain === key || requested.current === key) {
      return;
    }
    requested.current = key;
    void refresh().then(() => {
      if (mounted.current) {
        setLookedAgain(key);
      }
    });
  }, [settled, entry, key, lookedAgain, refresh]);

  if (!application || !entry) {
    if (state.status === "idle" || state.status === "loading" || lookedAgain !== key) {
      return <PageLoading />;
    }
    return (
      <div className="empty-state" role="status" data-testid="entry-unavailable">
        <p>
          <strong>This page is not available.</strong>
        </p>
        <p>It may have been removed, or it may not be part of your applications.</p>
        <Link href="/">Back to the start</Link>
      </div>
    );
  }
  return <EntryBody applicationLabel={application.label} entry={entry} base={entryBase(address.application, address.entry)} />;
}

function EntryBody({ applicationLabel, entry, base }: { applicationLabel: string; entry: NavigationEntry; base: string }) {
  if (entry.type === "OBJECT" && entry.objectApiName) {
    // The list of the object (Sprint 19): drawn from what the API says this member may see; the records open inside
    // this application, so the control bar stays where it is.
    return (
      <ObjectListPage
        objectApiName={entry.objectApiName}
        newHref={`${base}/new`}
        openHref={(id) => `${base}/records/${encodeURIComponent(id)}`}
        recordHref={recordHref}
        backHref="/"
      />
    );
  }
  if (entry.type === "CUSTOM" && entry.customPageApiName) {
    if (entry.available === false) {
      return (
        <section className="custom-page-state" aria-labelledby="entry-title">
          <h1 id="entry-title">{entry.label}</h1>
          <p role="status">This page cannot be opened right now. Try again in a few minutes.</p>
        </section>
      );
    }
    return (
      <>
        <h1 className="entry-title">{entry.label}</h1>
        <CustomPageFrame customPage={entry.customPageApiName} label={entry.label} assetId={entry.assetId ?? undefined} />
      </>
    );
  }
  return (
    <>
      <h1 className="entry-title">{entry.label}</h1>
      <div className="empty-state" role="status" data-testid="entry-not-built">
        <p>
          <strong>This page is being prepared.</strong>
        </p>
        <p>
          The {describeKind(entry)} of {applicationLabel} will open here.
        </p>
      </div>
    </>
  );
}

function PageLoading() {
  return (
    <div className="custom-page-loading" role="status" aria-live="polite" data-testid="entry-loading">
      <span className="skeleton-line" />
      <span className="skeleton-line skeleton-short" />
      <span className="visually-hidden">Loading</span>
    </div>
  );
}
