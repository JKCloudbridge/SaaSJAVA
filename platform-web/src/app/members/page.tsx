import type { Metadata } from "next";
import { Suspense } from "react";
import { MembersPanel } from "@/components/members/MembersPanel";

export const metadata: Metadata = {
  title: "Members",
};

/** The members and invitations of the organization this address belongs to (a list; a row opens in the same pane; support access is a section of the page). */
export default function MembersPage() {
  return (
    <Suspense fallback={null}>
      <MembersPanel />
    </Suspense>
  );
}
