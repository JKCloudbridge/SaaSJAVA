import type { Metadata } from "next";
import { DelegationsPanel } from "@/components/approvals/DelegationsPanel";

export const metadata: Metadata = {
  title: "Delegations",
};

/** The delegations of the signed-in member, and for an administrator everybody's. */
export default function DelegationsPage() {
  return <DelegationsPanel />;
}
