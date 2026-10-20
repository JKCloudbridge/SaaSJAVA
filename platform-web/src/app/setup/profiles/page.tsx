import type { Metadata } from "next";
import { Suspense } from "react";
import { ProfilesPanel } from "@/components/setup/ProfilesPanel";

export const metadata: Metadata = {
  title: "Profiles",
};

/** The profiles of the organization this address belongs to (a list; a row opens in the same pane). */
export default function ProfilesPage() {
  return (
    <Suspense fallback={null}>
      <ProfilesPanel />
    </Suspense>
  );
}
