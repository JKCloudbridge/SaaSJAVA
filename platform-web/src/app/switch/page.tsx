import type { Metadata } from "next";
import { SwitchArrival } from "@/components/auth/SwitchArrival";

export const metadata: Metadata = {
  title: "Opening the organization",
  // The address of this page carries a one-time proof after the #; it must never be passed on to another site.
  referrer: "no-referrer",
};

/** Where a person arrives after choosing another of their organizations. */
export default function SwitchPage() {
  return (
    <>
      <h1>Opening the organization</h1>
      <div className="card">
        <SwitchArrival />
      </div>
    </>
  );
}
