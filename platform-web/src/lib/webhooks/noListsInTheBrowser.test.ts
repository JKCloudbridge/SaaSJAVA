import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..");

/** The sources of the webhook, usage and limits screens and the model of the webhooks. */
const FILES = [
  "components/setup/WebhooksPanel.tsx",
  "components/setup/WebhookForm.tsx",
  "components/setup/WebhookDeliveries.tsx",
  "components/setup/UsagePanel.tsx",
  "components/console/OrganizationLimitsPanel.tsx",
  "lib/webhooks/model.ts",
];

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
 * The browser holds no list of what a webhook can ask for, no state of a delivery, no reason and no limit of its own
 * (Sprint 32, ADR-0275 to ADR-0278, ADR-0280): the events, the states of the filter, the sentences for a reason, the
 * numbers of the limits and the words that explain whom a webhook acts as all come from the API, so a thing the server adds
 * is on the screen the day it ships. A test names them only with made-up ones.
 */
describe("the webhook, usage and limits screens", () => {
  const own = FILES.map((file) => ({ file, text: readFileSync(path.join(src, file), "utf8") }));

  it("read the screens that exist (the scan is not looking at nothing)", () => {
    expect(own.every((one) => one.text.length > 300)).toBe(true);
  });

  it("name no event type of the platform", () => {
    for (const { file, text } of own) {
      expect(text, file).not.toMatch(/["'`]record\.(created|updated|deleted|restored|purged)["'`]/);
      expect(text, file).not.toMatch(/["'`]ping["'`]/);
    }
  });

  it("name no state of a delivery but the one that may be sent again, and no reason", () => {
    for (const { file, text } of own) {
      expect(text, file).not.toMatch(/["'`](PENDING|SENDING|DONE|SKIPPED|SUPERSEDED)["'`]/);
      expect(text, file).not.toMatch(
        /not_visible|member_inactive|queue_full|circuit_open|redirect_refused|address_not_allowed|subscription_off|plan_off/,
      );
    }
  });

  it("hold no number of a limit and no header of the rate limits", () => {
    for (const { file, text } of own) {
      expect(text, file).not.toMatch(/RateLimit|Retry-After|Webhook-Signature|Webhook-Id/);
      expect(text, file).not.toMatch(/\b(3000|500000|600)\b/);
    }
  });

  it("hold no webhook, usage or limits address anywhere but through the generated client", () => {
    const all = sources(src);
    expect(all.length).toBeGreaterThan(50);
    for (const file of all) {
      const text = readFileSync(file, "utf8");
      expect(text, file).not.toMatch(/fetch\(\s*["'`]\/api\/v1\/(webhooks|usage)/);
    }
  });

  it("keep the signing secret out of every shared place: no storage, no log, no console, no cache, no address", () => {
    const panel = own.find((one) => one.file.endsWith("WebhooksPanel.tsx"))?.text ?? "";
    expect(panel).not.toMatch(/localStorage|sessionStorage|indexedDB|console\./);
    expect(panel).not.toMatch(/lib\/log/);
    expect(panel).not.toMatch(/useQuery|queryClient|swr/i);
    expect(panel).not.toMatch(/router\.(push|replace)\([^)]*secret/i);
  });
});
