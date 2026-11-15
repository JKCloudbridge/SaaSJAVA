"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import { DetailSection } from "@/components/ui/lists/DetailPage";
import { useApplication } from "./application";
import { fillInsHref, getActionPlan, startActionPlan, type ActionPlanState } from "./fillIns";

/**
 * The Action plan of a fill-in that scored below the limit (MC2B, ADR-0261). It asks the server whether this fill-in needs
 * one, whether the person may start it and which one answers it, and draws that: a note that one is needed with a Start
 * button for a plant head, or a link to the Action plan that exists. It decides nothing: Start is decided again by the
 * server, and its refusal is shown in its own words. It names no score and no limit.
 */
export function ActionPlanSection({ fillId, refresh }: { fillId: string; refresh: number }) {
  const application = useApplication();
  const router = useRouter();
  const [state, setState] = useState<ActionPlanState | undefined>();
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();

  useEffect(() => {
    let current = true;
    void getActionPlan(fillId).then((outcome) => {
      if (current) setState(outcome.ok ? outcome.data : undefined);
    });
    return () => {
      current = false;
    };
  }, [fillId, refresh]);

  if (!state?.needed) return null;

  const start = async () => {
    setBusy(true);
    setRefusal(undefined);
    const outcome = await startActionPlan(fillId);
    setBusy(false);
    if (outcome.ok) {
      router.push(fillInsHref(outcome.data.fillId, application));
      return;
    }
    setRefusal(outcome.failure);
  };

  return (
    <DetailSection title="Action plan" stack>
      <p>This fill-in is below the limit, so an Action plan is needed. It must be sent before this fill-in can be approved.</p>
      {state.actionPlanId ? (
        <p>
          <Link href={fillInsHref(state.actionPlanId, application)}>Open the Action plan</Link>
        </p>
      ) : state.started ? (
        <p className="hint">An Action plan was started for this fill-in.</p>
      ) : state.canStart ? (
        <button type="button" className="button" disabled={busy} onClick={() => void start()}>
          Start the Action plan
        </button>
      ) : state.reason ? (
        <p className="hint">{state.reason}</p>
      ) : null}
      {refusal ? (
        <p role="alert" className="form-message">
          {refusal.message}
        </p>
      ) : null}
    </DetailSection>
  );
}
