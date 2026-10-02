import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import { navigate } from "@/lib/navigation";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";

/** What happened when a person asked to continue in another of their organizations. */
export type SwitchResult = { ok: true } | { ok: false; message: string };

/**
 * Asks the API to prepare a move to another organization of the signed-in person and, when it agrees, opens that
 * organization's own address with the one-time proof after the `#` (the part of an address no server receives). The
 * destination is a short name taken from the list the API gave; the API checks it against the person's memberships and
 * builds the address itself, so this page decides nothing and composes no organization address.
 */
export async function beginSwitch(slug: string): Promise<SwitchResult> {
  try {
    await ensureForgeryCookie();
    const { data, error, response } = await api.POST("/api/v1/auth/switch", { body: { slug } });
    if (data) {
      navigate(`${window.location.protocol}//${data.data.host}/switch#token=${encodeURIComponent(data.data.token)}`);
      return { ok: true };
    }
    return { ok: false, message: failureFromResponse(error, response).message };
  } catch {
    return { ok: false, message: "The server could not be reached. Check your connection and try again." };
  }
}
