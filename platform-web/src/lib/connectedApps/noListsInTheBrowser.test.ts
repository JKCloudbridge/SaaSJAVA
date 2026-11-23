import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..");

/** The sources of the connected application screens and their model. */
const FILES = ["components/setup/ConnectedAppsPanel.tsx", "lib/connectedApps/model.ts"];

/** Every source file of the browser that is not generated and not a test. */
function sources(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const full = path.join(directory, entry);
    if (statSync(full).isDirectory()) {
      return entry === "generated" ? [] : sources(full);
    }
    return /\.(ts|tsx)$/.test(entry) && !/\.test\.tsx?$/.test(entry) ? [full] : [];
  });
}

/**
 * The browser holds no list of what a connected application can be given or told (Sprint 31, ADR-0270): the scopes, the
 * limits, the address of the token endpoint, the worked request and the words that explain whom an application acts as
 * all come from the API's catalogue, so a scope the server adds is on the screen the day it ships. A test names scopes
 * only with made-up ones.
 */
describe("the connected application screens", () => {
  const own = FILES.map((file) => ({ file, text: readFileSync(path.join(src, file), "utf8") }));

  it("read the screens that exist (the scan is not looking at nothing)", () => {
    expect(own.every((one) => one.text.length > 500)).toBe(true);
  });

  it("name no scope of the public API", () => {
    for (const { file, text } of own) {
      expect(text, file).not.toMatch(/["'`](objects|records)\.(read|write)["'`]/);
    }
  });

  it("name no protocol error and hold no address of the authorization server", () => {
    for (const { file, text } of own) {
      expect(text, file).not.toMatch(/invalid_client|invalid_scope|temporarily_unavailable|client_credentials/);
      expect(text, file).not.toContain("/api/v1/oauth2");
    }
  });

  it("hold no scope, protocol error or authorization address anywhere in the browser's own sources", () => {
    const all = sources(src);
    expect(all.length).toBeGreaterThan(50);
    for (const file of all) {
      const text = readFileSync(file, "utf8");
      expect(text, file).not.toMatch(/["'`](objects|records)\.(read|write)["'`]/);
      expect(text, file).not.toMatch(/invalid_client|invalid_scope/);
      expect(text, file).not.toContain("/api/v1/oauth2");
    }
  });

  it("keep the secret out of every shared place: no storage, no log, no console, no cache", () => {
    const panel = own.find((one) => one.file.endsWith("ConnectedAppsPanel.tsx"))?.text ?? "";
    expect(panel).not.toMatch(/localStorage|sessionStorage|indexedDB|console\./);
    expect(panel).not.toMatch(/lib\/log/);
    expect(panel).not.toMatch(/useQuery|queryClient|swr/i);
  });
});
