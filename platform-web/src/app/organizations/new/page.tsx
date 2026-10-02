import type { Metadata } from "next";
import { CreateOrganizationForm } from "@/components/account/CreateOrganizationForm";

export const metadata: Metadata = {
  title: "Create an organization",
};

/** A signed-in person founds an organization and becomes its first administrator. */
export default function NewOrganizationPage() {
  return (
    <>
      <h1>Create an organization</h1>
      <div className="card">
        <CreateOrganizationForm />
      </div>
    </>
  );
}
