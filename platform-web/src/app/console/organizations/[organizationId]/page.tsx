import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { OrganizationDetail } from "@/components/console/OrganizationDetail";

export const metadata: Metadata = {
  title: "Organization",
};

/** One organization in the console. The organization is the address of this page, chosen by a platform person. */
export default function OrganizationPage() {
  return (
    <>
      <h1>Platform console</h1>
      <ConsoleFrame>
        <OrganizationDetail />
      </ConsoleFrame>
    </>
  );
}
