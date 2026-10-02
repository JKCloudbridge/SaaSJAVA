import type { Metadata } from "next";
import { ProfilesPanel } from "@/components/setup/ProfilesPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Profiles",
};

/** The profiles of the organization this address belongs to. */
export default function ProfilesPage() {
  return (
    <>
      <h1>Profiles</h1>
      <SetupFrame>
        <ProfilesPanel />
      </SetupFrame>
    </>
  );
}
