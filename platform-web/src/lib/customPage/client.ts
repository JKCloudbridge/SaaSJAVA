import { api } from "@/lib/api/client";
import { failureFromNetworkError, failureFromResponse, type ApiFailure } from "@/lib/api/errors";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";
import type { TokenGrant } from "./hostChannel.ts";

/** A script the member's applications list, checked by the server against the hash that was published. */
export interface CustomPageScript {
  apiName: string;
  label: string;
  assetId: string;
  sha256: string;
  code: string;
}

/** The answer of a call that can fail in the API's own words. */
export type Outcome<T> = { ok: true; value: T } | { ok: false; failure: ApiFailure };

/** How many scripts the browser keeps. A script is at most a megabyte, and a member opens a handful of pages. */
const MAX_CACHED_SCRIPTS = 12;

/**
 * Scripts by the identifier of the asset (Sprint 18, ADR-0127). The key is the content's identity: a script never
 * changes, so a copy under its identifier is never stale. What can go stale is the answer to "which asset does this page
 * show now?", and that comes with every list of the member's applications (`assetId` of the entry); a page therefore
 * asks for the script again exactly when the list names another asset.
 */
const scripts = new Map<string, CustomPageScript>();

/** Forgets every kept script (a test, and the end of a sign-in). */
export function forgetScripts(): void {
  scripts.clear();
}

/**
 * The script of a custom page the member may open. When the list of applications named the asset and the browser holds
 * that very asset, no request is made.
 *
 * @param apiName the custom page
 * @param assetId the asset the member's list names for it, if known
 */
export async function loadScript(apiName: string, assetId?: string): Promise<Outcome<CustomPageScript>> {
  const kept = assetId ? scripts.get(assetId) : undefined;
  if (kept && kept.apiName === apiName) {
    scripts.delete(kept.assetId);
    scripts.set(kept.assetId, kept);
    return { ok: true, value: kept };
  }
  try {
    const { data, error, response } = await api.GET("/api/v1/applications/custom-pages/{customPageApiName}", {
      params: { path: { customPageApiName: apiName } },
    });
    if (!data) {
      return { ok: false, failure: failureFromResponse(error, response) };
    }
    const script: CustomPageScript = data.data;
    scripts.set(script.assetId, script);
    while (scripts.size > MAX_CACHED_SCRIPTS) {
      const oldest = scripts.keys().next();
      if (oldest.done) {
        break;
      }
      scripts.delete(oldest.value);
    }
    return { ok: true, value: script };
  } catch {
    return { ok: false, failure: failureFromNetworkError() };
  }
}

/**
 * Asks for the access token of a custom page's script with the member's own session. Throws when the API refuses, so the
 * host channel can count it as a failed renewal.
 */
export async function requestToken(customPage: string): Promise<TokenGrant> {
  await ensureForgeryCookie();
  const { data } = await api.POST("/api/v1/embedded-tokens", { body: { customPage } });
  if (!data) {
    throw new Error("The access token for the page could not be had.");
  }
  return { token: data.data.token, expiresAt: data.data.expiresAt };
}
