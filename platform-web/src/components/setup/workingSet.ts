"use client";

import { useCallback, useSyncExternalStore } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

export type ChangeRequest = components["schemas"]["ChangeRequest"];

const KEY = "platform.workingChangeSet";
const EVENT = "platform-working-change-set";

/** What the pages say when a change went into the chosen change set instead of going live. */
export const DRAFTED_TEXT = "The change was added to the change set. It goes live when the set is published.";

function read(): string | undefined {
  try {
    return window.sessionStorage.getItem(KEY) ?? undefined;
  } catch {
    return undefined;
  }
}

function subscribe(listener: () => void): () => void {
  window.addEventListener(EVENT, listener);
  return () => window.removeEventListener(EVENT, listener);
}

/**
 * The change set the person is working in, kept for the browser tab only (sessionStorage; the page works without it).
 * With one chosen, the object pages record their changes in it instead of making them live. It is a convenience of the
 * page: the API decides whether the person may draft or publish, and checks every change again when the set is checked.
 */
export function useWorkingSet(): { id: string | undefined; select: (id: string | undefined) => void } {
  const id = useSyncExternalStore(subscribe, read, () => undefined);
  const select = useCallback((next: string | undefined) => {
    try {
      if (next) {
        window.sessionStorage.setItem(KEY, next);
      } else {
        window.sessionStorage.removeItem(KEY);
      }
    } catch {
      // The tab keeps working without remembering the choice.
    }
    window.dispatchEvent(new Event(EVENT));
  }, []);
  return { id, select };
}

/** Records a change in an open change set; nothing is applied until the set is published. */
export function addToSet(setId: string, change: ChangeRequest) {
  return api.POST("/api/v1/metadata/change-sets/{id}/changes", { params: { path: { id: setId } }, body: change });
}
