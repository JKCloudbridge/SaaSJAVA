"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { DetailSection } from "@/components/ui/lists/DetailPage";
import { PageFrame } from "@/components/ui/lists/PageFrame";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { safePath } from "@/lib/notifications/model";
import { call } from "@/lib/records/call";

type Health = components["schemas"]["AutomationHealthView"];

/** Which health the panel draws: the organization's own, or the platform's across organizations (the console). */
export type HealthScope = "organization" | "platform";

/**
 * The health of automation (Sprint 25, ADR-0163): what waits, what failed and what is overdue, as counts. It is not a list,
 * so it keeps its content and takes the frame of the Home screens: the title band, then a band of cards per section. The
 * sections, the words, whether a count needs attention and where to look all come from the API; the page holds no list of
 * them and draws a section the server adds the day it ships. Nothing here is a name or a value of a record. The platform's
 * version (for the console) names no organization at all. A refusal is shown in the API's words, so a member without the
 * ability is told by the server.
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

  const refresh = (
    <button type="button" className="button button-secondary" onClick={() => void load()}>
      Refresh
    </button>
  );

  if (failure) {
    return (
      <PageFrame title="Automation health" tools={refresh}>
        <p className="form-message" role="alert" data-testid="health-failed">
          {failure}
        </p>
      </PageFrame>
    );
  }
  if (!health) {
    return (
      <PageFrame title="Automation health">
        <p aria-live="polite">Loading…</p>
      </PageFrame>
    );
  }
  const attention = health.sections.flatMap((section) => section.items).filter((item) => item.needsAttention).length;
  return (
    <PageFrame title="Automation health" tools={refresh} testId="automation-health">
      <p role="status" data-testid="health-summary">
        {attention === 0
          ? "Nothing needs attention."
          : `${attention} ${attention === 1 ? "thing needs" : "things need"} attention.`}
      </p>
      {health.sections.map((section) => (
        <div key={section.key} data-testid={`health-${section.key}`}>
          <DetailSection title={section.title}>
            <ul className="stat-band">
              {section.items.map((item) => {
                const path = safePath(item.href);
                return (
                  <li key={item.key} data-attention={item.needsAttention ? "true" : "false"}>
                    <span className="stat-label">{path ? <Link href={path}>{item.label}</Link> : item.label}</span>
                    <span className="stat-value">{item.count}</span>
                    {item.needsAttention ? (
                      <span className="stat-note">
                        <Tag tone="error">Look at this</Tag>
                      </span>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </DetailSection>
        </div>
      ))}
    </PageFrame>
  );
}
