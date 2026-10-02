import type { Metadata } from "next";
import { InvitationAcceptance } from "@/components/invitations/InvitationAcceptance";

export const metadata: Metadata = {
  title: "Accept your invitation",
  // The address of this page carries a one-time token after the #; it must never be passed on to another site.
  referrer: "no-referrer",
};

/** Where the mailed invitation link leads: accept it, as a new person or as someone who already has an account. */
export default function AcceptInvitationPage() {
  return (
    <>
      <h1>Accept your invitation</h1>
      <div className="card">
        <InvitationAcceptance />
      </div>
    </>
  );
}
