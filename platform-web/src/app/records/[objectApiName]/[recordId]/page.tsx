import type { Metadata } from "next";
import { RecordRoute } from "@/components/records/RecordRoutes";

export const metadata: Metadata = {
  title: "Record",
};

/** The edit form of one record (Sprint 19); the full record page with related data is Sprint 20. */
export default function RecordPage() {
  return <RecordRoute />;
}
