"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import type { CurrentUser } from "@/lib/session/SessionProvider";
import { beginSwitch } from "@/lib/organizations/switch";
import { useOrganizations, type Organization } from "@/lib/organizations/useOrganizations";

/**
 * What a signed-in person sees on the platform's address (UA4, ADR-0171). Right after signing in (`justSignedIn`):
 * a person with a platform role goes to the console; a person with exactly one organization goes straight in through
 * the one-time handoff of the switcher. Otherwise, and on every later visit, they see the plain pages below: their
 * organizations to choose from, or a note that they belong to none yet.
 *
 * <p>The list comes from the API and the handoff is `beginSwitch`; the address of an organization is never composed
 * here. A refused handoff is shown in the API's words and is never retried by itself, so there is no loop.
 */
export function PlatformLanding({ user, justSignedIn }: { user: CurrentUser; justSignedIn: boolean }) {
  const router = useRouter();
  const hasConsole = user.platformRoles.length > 0;
  const toConsole = justSignedIn && hasConsole;
  const { state, reload } = useOrganizations(!toConsole);
  const [failure, setFailure] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const entering = useRef(false);

  const only =
    justSignedIn && !hasConsole && state.status === "ready" && state.organizations.length === 1
      ? state.organizations[0]
      : undefined;

  const enter = useCallback(async (slug: string) => {
    setBusy(true);
    setFailure(undefined);
    const result = await beginSwitch(slug);
    if (!result.ok) {
      setFailure(result.message);
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    if (toConsole) {
      router.replace("/console");
    }
  }, [toConsole, router]);

  useEffect(() => {
    // Effects run twice in development and the handoff proof is single use: ask for it once.
    if (only && !entering.current) {
      entering.current = true;
      void enter(only.slug);
    }
  }, [only, enter]);

  if (toConsole) {
    return (
      <p role="status" aria-live="polite">
        Opening the console…
      </p>
    );
  }
  if (state.status === "loading") {
    return (
      <p role="status" aria-live="polite">
        Loading your organizations…
      </p>
    );
  }
  if (state.status === "error") {
    return (
      <div className="empty-state" role="alert" data-testid="organizations-failed">
        <p>{state.message}</p>
        <button type="button" className="link-button" onClick={reload}>
          Try again
        </button>
      </div>
    );
  }
  if (only && !failure) {
    return (
      <p role="status" aria-live="polite">
        Opening your organization…
      </p>
    );
  }
  return (
    <>
      {state.organizations.length === 0 ? (
        <NoOrganization />
      ) : (
        <ChooseOrganization organizations={state.organizations} busy={busy} onChoose={(slug) => void enter(slug)} />
      )}
      {failure ? (
        <p className="form-message" role="alert" data-testid="enter-failed">
          {failure}
        </p>
      ) : null}
      {hasConsole ? (
        <p>
          <Link href="/console">Platform console</Link>
        </p>
      ) : null}
    </>
  );
}

function NoOrganization() {
  return (
    <>
      <h1>Welcome</h1>
      <div className="empty-state" role="status" data-testid="no-organization">
        <p>
          <strong>You are not a member of an organization yet.</strong>
        </p>
        <p>
          An administrator of an organization can invite you. The invitation arrives by e-mail; open the link in it to
          join.
        </p>
      </div>
    </>
  );
}

function ChooseOrganization({
  organizations,
  busy,
  onChoose,
}: {
  organizations: readonly Organization[];
  busy: boolean;
  onChoose: (slug: string) => void;
}) {
  return (
    <>
      <h1>Choose your organization</h1>
      <div className="card">
        <ul className="stack" data-testid="organization-list">
          {organizations.map((organization) => (
            <li key={organization.slug}>
              <button
                type="button"
                className="button button-secondary"
                disabled={busy}
                onClick={() => onChoose(organization.slug)}
              >
                {organization.displayName}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </>
  );
}
