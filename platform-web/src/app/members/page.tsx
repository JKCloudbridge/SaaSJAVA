import type { Metadata } from "next";
import { MembersPanel } from "@/components/members/MembersPanel";
import { SupportAccessPanel } from "@/components/members/SupportAccessPanel";

export const metadata: Metadata = {
  title: "Members",
};

/** The members and invitations of the organization this address belongs to. */
export default function MembersPage() {
  return (
    <>
      <h1>Members</h1>
      <MembersPanel />
      <SupportAccessPanel />
    </>
  );
}
