import type { Metadata } from "next";
import { LinkPasswordForm } from "@/components/account/LinkPasswordForm";

export const metadata: Metadata = {
  title: "Finish creating your account",
  // The address of this page carries a one-time token after the #; it must never be passed on to another site.
  referrer: "no-referrer",
};

/** Where the mailed sign-up link leads: choose a name and a password to create the account. */
export default function CompleteSignUpPage() {
  return (
    <>
      <h1>Finish creating your account</h1>
      <div className="card">
        <LinkPasswordForm kind="sign-up" />
      </div>
    </>
  );
}
