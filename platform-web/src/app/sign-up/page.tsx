import type { Metadata } from "next";
import { EmailRequestForm } from "@/components/account/EmailRequestForm";

export const metadata: Metadata = {
  title: "Create an account",
};

/** The first step of a sign-up: only an address. The rest is asked after the mailed link is opened. */
export default function SignUpPage() {
  return (
    <>
      <h1>Create an account</h1>
      <p>Enter your email address. We send a link; you choose your name and password after opening it.</p>
      <div className="card">
        <EmailRequestForm kind="sign-up" />
      </div>
    </>
  );
}
