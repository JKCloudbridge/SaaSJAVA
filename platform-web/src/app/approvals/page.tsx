import type { Metadata } from "next";
import { Suspense } from "react";
import { Inbox } from "@/components/approvals/Inbox";

export const metadata: Metadata = {
  title: "Approvals",
};

/** The inbox of the signed-in member: the tasks they were asked to decide, and what they decided. */
export default function ApprovalsPage() {
  return (
    // The inbox reads its filters from the address, which needs a Suspense boundary on a statically built page.
    <Suspense fallback={<p aria-live="polite">Loading…</p>}>
      <Inbox />
    </Suspense>
  );
}
