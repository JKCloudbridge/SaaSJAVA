"use client";

import { useAction } from "@/components/console/useAction";
import { useListRoute } from "./useListRoute";

/**
 * What a list-and-detail screen needs in one place: which row is open (kept in the address), and the one-call-at-a-time
 * action runner with its notice and refusal. Moving to another page of the list forgets the last notice, so a message about
 * one row is never left standing over another.
 */
export function useListScreen(reload: () => Promise<void>) {
  const route = useListRoute();
  const action = useAction(reload);
  const { clear } = action;

  return {
    ...action,
    openId: route.openId,
    creating: route.creating,
    open: (id: string) => {
      clear();
      route.open(id);
    },
    create: () => {
      clear();
      route.create();
    },
    /** Back to the list; a finished create or remove replaces the history entry and keeps its notice. */
    back: (keepNotice = false) => {
      if (!keepNotice) {
        clear();
      }
      route.close(keepNotice);
    },
  };
}
