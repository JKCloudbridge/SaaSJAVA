import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..");

/** The sources of the integration screens and their model: everything that draws or decides something about a connection. */
const FILES = ["components/setup/IntegrationsPanel.tsx", "lib/integrations/model.ts"];

/**
 * The browser holds no list of what a connection can be made of (Sprint 26, ADR-0207, ADR-0210): the connector types, their
 * settings, the ways of signing in, the events, the states and the reasons a test can end with are what the API describes
 * and says, so a connector type or a reason the server adds is on the screen the day it ships. The screens decide nothing:
 * the API's flags say what can be switched on or changed, and its words are shown for every refusal. A test that names a
 * type does so with a made-up one (`src/test/integrations.ts`).
 */
describe("the integration screens", () => {
  const sources = FILES.map((file) => ({ file, text: readFileSync(path.join(src, file), "utf8") }));

  it("read the screens that exist (the scan is not looking at nothing)", () => {
    expect(sources.every((one) => one.text.length > 500)).toBe(true);
    expect(sources.map((one) => path.basename(one.file))).toContain("IntegrationsPanel.tsx");
  });

  it("name no connector type, no platform event and no reason of a test", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/["'`](rest|record\.created|record\.updated|record\.deleted)["'`]/);
      expect(text, file).not.toMatch(
        /\b(address_not_allowed|name_not_found|connect_failed|tls_failed|timed_out|redirect_refused|unexpected_status|authentication_rejected|credential_missing|changed_during_test)\b/,
      );
    }
  });

  it("hold no setting of a connector type", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/\b(healthPath|probeMethod|timeoutSeconds)\b/);
    }
  });

  it("decide nothing from the state: they read the flags the API sends", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/state\s*===?\s*"(TESTED|UNTESTED|DISABLED)"/);
      expect(text, file).not.toMatch(/\b(TESTED|UNTESTED|DISABLED)\b.*\?.*:/);
    }
  });

  it("show no sentence of the platform's about a test: the API's words are shown", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/service answered|could not connect|did not answer in time/i);
    }
  });
});
