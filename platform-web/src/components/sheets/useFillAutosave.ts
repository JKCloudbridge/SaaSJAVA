"use client";

import { useEffect, useRef, useState } from "react";
import { saveFillCells, type FillOwner, type FillSummary } from "@/lib/sheets/fillApi";
import { canonicalFillText } from "@/lib/sheets/fillModel";
import type { FillDoc } from "@/lib/sheets/fillTypes";
import type { GridDoc } from "@/lib/sheets/types";
import type { ApiFailure } from "@/lib/api/errors";

/** One serialized queue for manual Save, Submit, navigation and the two-second draft debounce. */
export function useFillAutosave(options: {
  owner: FillOwner; grid: GridDoc; fill: FillDoc; initial: FillDoc; version: number; enabled: boolean; dirty: boolean;
  accepted(sent: FillDoc, summary: FillSummary): void; refused(sent: FillDoc, failure: ApiFailure): void;
}) {
  const latest = useRef(options);
  useEffect(() => { latest.current = options; });
  const version = useRef(options.version);
  const baseline = useRef(options.initial);
  const acknowledged = useRef(canonicalFillText(options.grid, options.initial));
  const queue = useRef<Promise<boolean> | null>(null);
  const paused = useRef(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const flush = async (manual = false): Promise<boolean> => {
    if (queue.current) {
      const ok = await queue.current;
      return ok ? flush(manual) : false;
    }
    if (paused.current && !manual) return false;
    if (manual) paused.current = false;
    const now = latest.current;
    const fingerprint = canonicalFillText(now.grid, now.fill);
    if (fingerprint === acknowledged.current) return true;
    if (!manual && !now.enabled) return false;
    setSaving(true);
    setSaved(false);
    const run = async () => {
      for (;;) {
        const snapshot = latest.current;
        const sent = snapshot.fill;
        const key = canonicalFillText(snapshot.grid, sent);
        if (key === acknowledged.current) return true;
        if (!manual && !snapshot.enabled) return false;
        const answer = await saveFillCells(snapshot.owner, version.current, snapshot.grid, sent);
        if (!answer.ok) {
          paused.current = true;
          latest.current.refused(sent, answer.failure);
          return false;
        }
        version.current = answer.data.version;
        baseline.current = sent;
        acknowledged.current = key;
        latest.current.accepted(sent, answer.data);
        setSaved(true);
      }
    };
    queue.current = run();
    try { return await queue.current; }
    finally { queue.current = null; setSaving(false); }
  };
  const flushRef = useRef(flush);
  useEffect(() => { flushRef.current = flush; });
  useEffect(() => {
    if (!options.enabled || !options.dirty || paused.current) return;
    const timer = setTimeout(() => { void flushRef.current(); }, 2000);
    return () => clearTimeout(timer);
  }, [options.fill, options.enabled, options.dirty, saving]);

  return { saving, saved, baseline, flush,
    rebase(next: FillDoc, nextVersion: number) {
      version.current = nextVersion;
      baseline.current = next;
      acknowledged.current = canonicalFillText(latest.current.grid, next);
      paused.current = false;
    },
  };
}
