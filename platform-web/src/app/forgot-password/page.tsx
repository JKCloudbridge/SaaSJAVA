import type { Metadata } from "next";
import { EmailRequestForm } from "@/components/account/EmailRequestForm";

export const metadata: Metadata = {
  title: "Forgot your password",
};

/** Asks for a password-reset link. The answer is the same whether or not the address has an account. */
export default function ForgotPasswordPage() {
  return (
    <>
      <h1>Forgot your password</h1>
      <p>Enter the email address of your account. If it has one, we send a link to choose a new password.</p>
      <div className="card">
        <EmailRequestForm kind="password-reset" />
      </div>
    </>
  );
}
