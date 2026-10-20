import type { Metadata } from "next";
import { ApprovalBuilder } from "@/components/setup/ApprovalBuilder";

export const metadata: Metadata = {
  title: "Approval process",
};

/** The builder of one approval process; the address names its object and it ("new" starts one). */
export default function ApprovalBuilderPage() {
  return <ApprovalBuilder />;
}
