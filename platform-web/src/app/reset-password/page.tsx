import type { Metadata } from "next";
import { LinkPasswordForm } from "@/components/account/LinkPasswordForm";

export const metadata: Metadata = {
  title: "Choose a new password",
  // The address of this page carries a one-time token after the #; it must never be passed on to another site.
  referrer: "no-referrer",
};

/** Where the mailed reset link leads: choose a new password. */
export default function ResetPasswordPage() {
  return (
    <>
      <h1>Choose a new password</h1>
      <div className="card">
        <LinkPasswordForm kind="reset" />
      </div>
    </>
  );
}
