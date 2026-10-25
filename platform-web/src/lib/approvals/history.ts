import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { call } from "@/lib/records/call";

export type ApprovalInstance = components["schemas"]["ApprovalInstanceView"];

/** The requests of one record, newest first (the API's own order). */
export type RecordHistory = Readonly<Record<string, readonly ApprovalInstance[]>>;

/**
 * Every approval request of a few records of one object (FA6E, ADR-0199), read in one call. A record the member cannot read
 * is not in the answer. A failure gives no history: the page around it goes on, and the card of the record still shows the
 * latest request. Nothing here decides anything.
 */
export function useApprovalHistory(objectApiName: string, recordIds: readonly string[], refresh: number): RecordHistory {
  const [loaded, setLoaded] = useState<{ key: string; history: RecordHistory } | null>(null);
  const key = `${objectApiName}|${recordIds.join(",")}|${refresh}`;

  useEffect(() => {
    if (recordIds.length === 0) {
      return;
    }
    let live = true;
    void call(() =>
      api.POST("/api/v1/objects/{objectApiName}/records/approval-history", {
        params: { path: { objectApiName } },
        body: { recordIds: [...recordIds] },
      }),
    ).then((outcome) => {
      if (!live) {
        return;
      }
      const history: Record<string, readonly ApprovalInstance[]> = {};
      if (outcome.ok) {
        for (const one of outcome.data.data) {
          history[one.recordId] = one.requests;
        }
      }
      setLoaded({ key, history });
    });
    return () => {
      live = false;
    };
    // The identifiers are in the key; the array itself changes identity on every render of the page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [objectApiName, key]);

  return loaded && loaded.key === key ? loaded.history : {};
}
