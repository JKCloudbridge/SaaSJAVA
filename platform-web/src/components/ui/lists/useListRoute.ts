"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

/** How a screen names the parts of the address it owns; the defaults suit a screen that is a whole page. */
export interface ListRouteOptions {
  /** The query parameter that holds the open row (`?open=<id>`). A tab of an object names its own (`?field=<name>`). */
  openParam?: string;
  /** Query parameters of the page around the list that must survive opening and closing a row (`section`). */
  keep?: readonly string[];
}

/**
 * Which row of a list is open, kept in the address of the page (`?open=<id>` or `?new=1`): every address stays the same page,
 * a row can be linked to, and the back button returns to the list (ADR-0168). Whatever reads it sits under Suspense.
 *
 * <p>A list that is one tab of a larger page (the tabs of an object, UA3) keeps the parameters that choose the tab, and
 * names its open row after what it is, so two tabs never read each other's row.
 */
export function useListRoute(options: ListRouteOptions = {}) {
  const { openParam = "open", keep = [] } = options;
  const params = useSearchParams();
  const pathname = usePathname() ?? "";
  const router = useRouter();

  const creating = params?.get("new") === "1";
  const openId = creating ? undefined : (params?.get(openParam) ?? undefined);

  /** The address of this page with the kept parameters and, when given, one more. */
  const address = (extra?: readonly [string, string]) => {
    const query = new URLSearchParams();
    for (const name of keep) {
      const value = params?.get(name);
      if (value !== null && value !== undefined) {
        query.set(name, value);
      }
    }
    if (extra) {
      query.set(extra[0], extra[1]);
    }
    const text = query.toString();
    return text ? `${pathname}?${text}` : pathname;
  };

  return {
    openId,
    creating,
    open: (id: string) => router.push(address([openParam, id])),
    create: () => router.push(address(["new", "1"])),
    /** Back to the list; `replace` when the detail page just finished and should not stay in the history. */
    close: (replace = false) => (replace ? router.replace(address()) : router.push(address())),
  };
}
