import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * The web server's health answer (Sprint 33, ADR-0289): "ok" normally, 503 once the image's entry point has said the
 * server is being stopped, so the load balancer moves away before the server stops listening.
 */
describe("healthz", () => {
  const folders: string[] = [];

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
    folders.splice(0).forEach((folder) => rmSync(folder, { recursive: true, force: true }));
  });

  async function route(marker: string) {
    vi.stubEnv("WEB_DRAINING_MARKER", marker);
    vi.resetModules();
    return import("./route");
  }

  it("answers ok and is never cached while the server is running", async () => {
    const folder = mkdtempSync(join(tmpdir(), "healthz-"));
    folders.push(folder);
    const { GET } = await route(join(folder, "draining"));

    const answer = GET();

    expect(answer.status).toBe(200);
    expect(await answer.text()).toBe("ok");
    expect(answer.headers.get("Cache-Control")).toBe("no-store");
  });

  it("answers 503 as soon as the marker of a stopping server exists", async () => {
    const folder = mkdtempSync(join(tmpdir(), "healthz-"));
    folders.push(folder);
    const marker = join(folder, "draining");
    const { GET } = await route(marker);
    expect(GET().status).toBe(200);

    writeFileSync(marker, "");

    const answer = GET();
    expect(answer.status).toBe(503);
    expect(await answer.text()).toBe("draining");
    expect(answer.headers.get("Cache-Control")).toBe("no-store");
  });
});
