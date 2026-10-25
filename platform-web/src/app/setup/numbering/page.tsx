import type { Metadata } from "next";
import { NumberingPanel } from "@/components/setup/NumberingPanel";

export const metadata: Metadata = { title: "Numbering" };

/** The organization's number formats and next counts (FA6B). */
export default function NumberingPage() {
  return <NumberingPanel />;
}
