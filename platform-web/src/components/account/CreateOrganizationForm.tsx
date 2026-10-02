"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import { usePlatformAddress } from "@/lib/account/usePlatformAddress";
import { ensureForgeryCookie, useSession } from "@/lib/session/SessionProvider";
import { COMMON_TEXT, commonFailureText, problems } from "./messages";

/** A short name suggested from the organization's name. A suggestion only: the API decides what is acceptable. */
export function suggestSlug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40)
    .replace(/-+$/g, "");
}

/**
 * A signed-in person founds an organization: its name and its short name (the first part of its web address). The
 * page sends them; the API creates the organization, makes the person its founding administrator and answers with the
 * address to use. The page never chooses a tenant and shows the address the API built, not one it composed.
 */
export function CreateOrganizationForm() {
  const { state } = useSession();
  const platform = usePlatformAddress();
  const [displayName, setDisplayName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | undefined>();
  const [created, setCreated] = useState<{ host: string; name: string } | undefined>();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setMessage(undefined);
    try {
      await ensureForgeryCookie();
      const { data, error, response } = await api.POST("/api/v1/organizations", { body: { displayName, slug } });
      if (data) {
        setCreated({ host: data.data.host, name: data.data.displayName });
        return;
      }
      if (response.status === 400) {
        const fields = failureFromResponse(error, response).fields;
        const slugProblem = problems(fields, "slug");
        setMessage(
          slugProblem
            ? `The web address: ${slugProblem}`
            : (problems(fields, "displayName") ?? COMMON_TEXT.unavailable),
        );
      } else if (response.status === 401) {
        setMessage("Your sign-in has ended. Sign in again.");
      } else if (response.status === 403) {
        setMessage("You have reached the number of organizations one person may create.");
      } else {
        setMessage(commonFailureText(response.status));
      }
    } catch {
      setMessage(COMMON_TEXT.network);
    }
    setSubmitting(false);
  }

  if (created) {
    const address = `${window.location.protocol}//${created.host}/sign-in`;
    return (
      <div data-testid="organization-created">
        <p role="status">{created.name} is ready. You are its first administrator.</p>
        <p>
          <a href={address}>Sign in to {created.host}</a>
        </p>
      </div>
    );
  }
  if (state.status === "loading") {
    return <p aria-live="polite">Checking sign-in…</p>;
  }
  if (state.status !== "signedIn") {
    return (
      <p data-testid="needs-sign-in">
        Sign in first, then create your organization. <Link href="/sign-in?continue=/organizations/new">Sign in</Link>
      </p>
    );
  }
  if (platform.isPlatformHost === false) {
    return (
      <p data-testid="wrong-host">
        Organizations are created from the platform address.{" "}
        <a href={`${platform.origin}/organizations/new`}>Go to the platform address</a>
      </p>
    );
  }

  return (
    <form className="form" onSubmit={(event) => void submit(event)} noValidate>
      {message ? (
        <p className="form-message" role="alert" data-testid="organization-message">
          {message}
        </p>
      ) : null}
      <div className="field">
        <label htmlFor="displayName">Organization name</label>
        <input
          id="displayName"
          name="displayName"
          type="text"
          autoComplete="organization"
          value={displayName}
          onChange={(event) => {
            setDisplayName(event.target.value);
            if (!slugEdited) {
              setSlug(suggestSlug(event.target.value));
            }
          }}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="slug">Short name for the web address</label>
        <input
          id="slug"
          name="slug"
          type="text"
          autoComplete="off"
          value={slug}
          onChange={(event) => {
            setSlugEdited(true);
            setSlug(event.target.value);
          }}
          required
        />
      </div>
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? "Creating…" : "Create the organization"}
      </button>
    </form>
  );
}
