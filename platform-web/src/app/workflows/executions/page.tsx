import type { Metadata } from "next";
import { Suspense } from "react";
import { SetupFrame } from "@/components/setup/SetupFrame";
import { ExecutionsPanel } from "@/components/workflows/ExecutionsPanel";

export const metadata: Metadata = {
  title: "Workflow runs",
};

/** The runs of the organization's workflows: what ran, what became of it, and the ones that need a person. */
export default function ExecutionsPage() {
  return (
    <>
      <h1>Workflow runs</h1>
      <SetupFrame>
        {/* The panel reads its starting filters from the address, which needs a boundary on a statically built page. */}
        <Suspense fallback={<p aria-live="polite">Loading…</p>}>
          <ExecutionsPanel />
        </Suspense>
      </SetupFrame>
    </>
  );
}
