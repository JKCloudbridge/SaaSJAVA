import type { Metadata } from "next";
import { SignInForm } from "@/components/auth/SignInForm";
import { SignInLayout } from "@/components/auth/SignInLayout";
import { safeReturnPath } from "@/lib/session/returnPath";
import { returnPathAfterSignIn } from "@/lib/session/signedInMarker";

export const metadata: Metadata = {
  title: "Sign in",
};

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

/** The sign-in screen. The address to return to and a failed return are carried in the query of the address. */
export default async function SignInPage({ searchParams }: { searchParams: SearchParams }) {
  const query = await searchParams;
  const problem = typeof query.problem === "string" ? query.problem : undefined;
  return (
    <SignInLayout title="Sign in">
      <div className="card">
        <SignInForm returnTo={returnPathAfterSignIn(safeReturnPath(query.continue))} problem={problem} />
      </div>
    </SignInLayout>
  );
}
