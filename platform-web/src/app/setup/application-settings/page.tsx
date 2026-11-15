import type { Metadata } from "next";
import { ApplicationSettingsPanel } from "@/components/setup/ApplicationSettingsPanel";

export const metadata: Metadata = { title: "Application settings" };

/** What each application the organization has lets it choose, such as the limit of a score (MC2B). */
export default function ApplicationSettingsPage() {
  return <ApplicationSettingsPanel />;
}
