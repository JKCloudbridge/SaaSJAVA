"use client";

import { useEffect, useState } from "react";
import type { ChoiceFormProps } from "@/lib/approvals/choiceForm";
import { AudienceChoiceForm } from "./AudienceChoiceForm";
import { getChangeRequestByReview, type ChangeRequestView } from "./changeRequests";
import { PublishChoiceForm } from "./PublishChoiceForm";

type Loaded =
  | { status: "loading" }
  | { status: "ready"; request: ChangeRequestView }
  | { status: "failed"; message: string };

/**
 * The choice of a step of the approval of a change request (Functional track FA8, ADR-0202). The approval knows the record
 * under review, not the template, so the request is read first. A step that asks for a choice gets the suggestion of the
 * audience (Location required, Department optional); the last step gets the Publish window, which gives the last approval
 * and publishes. The server judges both again.
 */
export function ChangeRequestChoiceForm({ recordId, onChange, lastStep }: ChoiceFormProps) {
  const [loaded, setLoaded] = useState<Loaded>({ status: "loading" });

  useEffect(() => {
    let live = true;
    onChange(null);
    void getChangeRequestByReview(recordId).then((outcome) => {
      if (live) {
        setLoaded(
          outcome.ok ? { status: "ready", request: outcome.data } : { status: "failed", message: outcome.failure.message },
        );
      }
    });
    return () => {
      live = false;
    };
  }, [recordId, onChange]);

  if (loaded.status === "loading") {
    return (
      <p role="status" className="hint">
        Opening the request…
      </p>
    );
  }
  if (loaded.status === "failed") {
    return (
      <p role="alert" className="form-message">
        {loaded.message}
      </p>
    );
  }
  const request = loaded.request;
  return lastStep === true ? (
    <PublishChoiceForm templateId={request.templateId} prefill={request.publishPrefill ?? undefined} onChange={onChange} />
  ) : (
    <AudienceChoiceForm recordId={request.templateId} onChange={onChange} />
  );
}
