/**
 * The answer a container platform asks to know the web server is alive. It touches nothing (no API, no session, no
 * organization) so a slow or unreachable API never makes the web server look dead; the API has its own probes.
 */
export const dynamic = "force-dynamic";

export function GET(): Response {
  return new Response("ok", { headers: { "Cache-Control": "no-store", "Content-Type": "text/plain" } });
}
