"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

/**
 * Says that a list was narrowed by its address (FA9, ADR-0203): the home page's tiles open a list "already filtered to
 * what the number counts", and a person who arrives that way must see why some rows are missing and be able to take
 * the narrowing off. The words are what the page's own filter calls the slice; the address is the page without it.
 */
export function AddressFilterNote({ what }: { what: string }) {
  const pathname = usePathname() ?? "";
  return (
    <p className="hint address-filter" role="status" data-testid="address-filter">
      Showing {what} only. <Link href={pathname}>Show everything</Link>
    </p>
  );
}
