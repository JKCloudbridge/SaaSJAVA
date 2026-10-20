"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

/**
 * Which row of a list is open, kept in the address of the page (`?open=<id>` or `?new=1`): every address stays the same page,
 * a row can be linked to, and the back button returns to the list (ADR-0168). Whatever reads it sits under Suspense.
 */
export function useListRoute() {
  const params = useSearchParams();
  const pathname = usePathname() ?? "";
  const router = useRouter();

  const creating = params?.get("new") === "1";
  const openId = creating ? undefined : (params?.get("open") ?? undefined);

  return {
    openId,
    creating,
    open: (id: string) => router.push(`${pathname}?open=${encodeURIComponent(id)}`),
    create: () => router.push(`${pathname}?new=1`),
    /** Back to the list; `replace` when the detail page just finished and should not stay in the history. */
    close: (replace = false) => (replace ? router.replace(pathname) : router.push(pathname)),
  };
}
