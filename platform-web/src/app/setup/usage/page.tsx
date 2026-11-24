import type { Metadata } from "next";
import { UsagePanel } from "@/components/setup/UsagePanel";

export const metadata: Metadata = { title: "API usage" };

/** The limits on the public API and how much of them is used (Sprint 32). */
export default function UsagePage() {
  return <UsagePanel />;
}
