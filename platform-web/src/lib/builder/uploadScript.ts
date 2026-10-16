import { FORGERY_COOKIE, FORGERY_HEADER, readCookie } from "@/lib/api/forgery";
import type { components } from "@/lib/api/generated/schema";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";

type CustomPageAssetView = components["schemas"]["CustomPageAssetView"];

export type UploadOutcome = { ok: true; asset: CustomPageAssetView } | { ok: false; message: string };

const FAILED = "The script could not be stored.";

/**
 * Stores the script of a custom page (Sprint 18, ADR-0127): the file's text goes up as it is, the platform keeps it and
 * never runs it. The same text uploaded twice is one asset, so this is safe to repeat. A script cannot be part of the
 * generated client's typed calls (the body is a file, not JSON), so the one raw request of the page lives here.
 */
export async function uploadScript(file: File): Promise<UploadOutcome> {
  try {
    await ensureForgeryCookie();
    const token = readCookie(FORGERY_COOKIE);
    const response = await fetch("/api/v1/metadata/custom-page-assets", {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "text/javascript",
        ...(token ? { [FORGERY_HEADER]: token } : {}),
      },
      body: file,
    });
    const body: unknown = await response.json().catch(() => undefined);
    if (response.ok) {
      const asset = (body as { data?: CustomPageAssetView } | undefined)?.data;
      return asset ? { ok: true, asset } : { ok: false, message: FAILED };
    }
    const message = (body as { error?: { message?: string } } | undefined)?.error?.message;
    return { ok: false, message: message ?? FAILED };
  } catch {
    return { ok: false, message: FAILED };
  }
}
