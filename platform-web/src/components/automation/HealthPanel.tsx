"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { safePath } from "@/lib/notifications/model";
import { call } from "@/lib/records/call";

type Health = components["schemas"]["AutomationHealthView"];

/** Which health the panel draws: the organization's own, or the platform's across organizations (the console). */
export type HealthScope = "organization" | "platform";

/**
 * The health of automation (Sprint 25, ADR-0163): what waits, what failed and what is overdue, as counts. The sections, the
 * words, whether a count needs attention and where to look all come from the API; the page holds no list of them and
 * draws a section the server adds the day it ships. Nothing here is a name or a value of a record. The platform's version
 * (for the console) names no organization at all. A refusal is shown in the API's words, so a member without the ability
 * is told by the server.
 */
export function HealthPanel({ scope }: { scope: HealthScope }) {
  const [health, setHealth] = useState<Health | undefined>();
  const [failure, setFailure] = useState<string | undefined>();

  const load = useCallback(async () => {
    const outcome = await call(() =>
      scope === "platform" ? api.GET("/api/v1/platform/automation/health") : api.GET("/api/v1/automation/health"),
    );
    if (!outcome.ok) {
      setFailure(outcome.failure.message);
      return;
    }
    setFailure(undefined);
    setHealth(outcome.data.data);
  }, [scope]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="health-failed">
        {failure}
      </p>
    );
  }
  if (!health) {
    return <p aria-live="polite">Loading…</p>;
  }
  const attention = health.sections.flatMap((section) => section.items).filter((item) => item.needsAttention).length;
  return (
    <section aria-labelledby="health-heading" className="stack" data-testid="automation-health">
      <div className="list-head">
        <h2 id="health-heading">Health of automation</h2>
        <button type="button" className="button button-secondary" onClick={() => void load()}>
          Refresh
        </button>
      </div>
      <p role="status" data-testid="health-summary">
        {attention === 0
          ? "Nothing needs attention."
          : `${attention} ${attention === 1 ? "thing needs" : "things need"} attention.`}
      </p>
      {health.sections.map((section) => (
        <article key={section.key} className="card stack" data-testid={`health-${section.key}`}>
          <h3>{section.title}</h3>
          <table className="table">
            <tbody>
              {section.items.map((item) => {
                const path = safePath(item.href);
                return (
                  <tr key={item.key} data-attention={item.needsAttention ? "true" : "false"}>
                    <th scope="row">{path ? <Link href={path}>{item.label}</Link> : item.label}</th>
                    <td>
                      {item.count}
                      {item.needsAttention ? <span className="badge"> Look at this</span> : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </article>
      ))}
    </section>
  );
}
