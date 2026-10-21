"use client";

import { useEffect, useSyncExternalStore } from "react";

/** The object whose settings are open, as the page loaded it: what the shell needs to name the rail (UA3, ADR-0170). */
export interface OpenObject {
  apiName: string;
  label: string;
}

let current: OpenObject | undefined;
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * The page of an object tells the shell which object is open, so the rail beside it can carry the object's name; `undefined`
 * when the page goes away. The page is the one source: the shell asks the API for nothing of its own.
 */
export function publishOpenObject(next: OpenObject | undefined): void {
  if (current?.apiName === next?.apiName && current?.label === next?.label) {
    return;
  }
  current = next;
  listeners.forEach((listener) => listener());
}

/** The object the open page published, or undefined while it is still loading. */
export function useOpenObject(): OpenObject | undefined {
  return useSyncExternalStore(
    subscribe,
    () => current,
    () => undefined,
  );
}

/**
 * Publishes the open object while the calling page shows it (the object page and the two builders do): the rail beside the
 * page then carries the object's name. Pass undefined while it is still loading.
 */
export function usePublishOpenObject(apiName: string, label: string | undefined): void {
  useEffect(() => {
    publishOpenObject(label === undefined ? undefined : { apiName, label });
    return () => publishOpenObject(undefined);
  }, [apiName, label]);
}
