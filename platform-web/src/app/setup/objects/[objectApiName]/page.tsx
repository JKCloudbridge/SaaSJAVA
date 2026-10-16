import type { Metadata } from "next";
import { Suspense } from "react";
import { ObjectDetailPanel } from "@/components/setup/ObjectDetailPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Object",
};

/** One object of the organization with its fields. The object is the address of this page. */
export default function ObjectPage() {
  return (
    <>
      <h1>Objects</h1>
      <SetupFrame>
        {/* The section is read from the address (?section=), which a statically built page may only do inside Suspense. */}
        <Suspense fallback={<p aria-live="polite">Loading…</p>}>
          <ObjectDetailPanel />
        </Suspense>
      </SetupFrame>
    </>
  );
}
