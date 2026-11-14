"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { NumberingPanel } from "./NumberingPanel";
import { RoutingPanel, useRouting } from "./RoutingPanel";

const ROUTING = "routing";

/**
 * Setup, Numbering (FA6B) and, beside it, Application routing (MC1.1, ADR-0259): which application a document also
 * belongs to, by the number it is given. The second tab is drawn only when the API says the organization has an
 * application a number can route to, or has rules already, and the person may manage numbering; the page decides
 * nothing. The tab is in the address (`?section=routing`), so a link and a reload keep it.
 */
export function NumberingTabs() {
  const router = useRouter();
  const params = useSearchParams();
  const { view, setView, failure } = useRouting();
  const offered = Boolean(view && (view.applications.length > 0 || view.rules.length > 0));
  const routing = offered && params.get("section") === ROUTING;
  const choose = (section: string | null) => router.replace(section ? `/setup/numbering?section=${section}` : "/setup/numbering");

  return (
    <div className="stack">
      {offered ? (
        <div className="detail-tabs-row">
          <div role="tablist" aria-label="Numbering" className="detail-tabs">
            <button type="button" role="tab" className="detail-tab" aria-selected={!routing} onClick={() => choose(null)}>
              Formats
            </button>
            <button type="button" role="tab" className="detail-tab" aria-selected={routing} onClick={() => choose(ROUTING)}>
              Application routing
            </button>
          </div>
        </div>
      ) : null}
      {failure ? <p role="alert" className="form-message">{failure}</p> : null}
      {routing && view ? <RoutingPanel view={view} onChanged={setView} /> : <NumberingPanel />}
    </div>
  );
}
