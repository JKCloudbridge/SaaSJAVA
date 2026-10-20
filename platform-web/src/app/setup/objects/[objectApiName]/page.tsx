import type { Metadata } from "next";
import { Suspense } from "react";
import { ObjectFrame } from "@/components/setup/ObjectFrame";

export const metadata: Metadata = {
  title: "Object",
};

/** One object of the organization; the object is the address of this page and the tab is its ?section=. */
export default function ObjectPage() {
  return (
    // The tab and the open row are read from the address, which a statically built page may only do inside Suspense.
    <Suspense fallback={<p aria-live="polite">Loading…</p>}>
      <ObjectFrame />
    </Suspense>
  );
}
