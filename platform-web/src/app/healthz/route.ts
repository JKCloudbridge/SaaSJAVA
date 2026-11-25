import { existsSync } from "node:fs";

/**
 * The answer a container platform asks to know the web server is alive and may be sent requests. It touches nothing (no
 * API, no session, no organization) so a slow or unreachable API never makes the web server look dead; the API has its
 * own probes.
 *
 * While the server is being stopped for a release, the image's entry point (entrypoint.sh) creates a marker file at
 * once and keeps the server running for a few seconds. This route then answers 503, so the load balancer takes the
 * instance out of rotation before it stops listening and no request meets a closed door (Sprint 33, ADR-0289).
 */
export const dynamic = "force-dynamic";

const DRAINING_MARKER = process.env.WEB_DRAINING_MARKER ?? "/tmp/web-draining";

export function GET(): Response {
  const headers = { "Cache-Control": "no-store", "Content-Type": "text/plain" };
  if (existsSync(DRAINING_MARKER)) {
    return new Response("draining", { status: 503, headers });
  }
  return new Response("ok", { headers });
}
