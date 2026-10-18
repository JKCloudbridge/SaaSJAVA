import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..");

/** The sources of the notification, text and health screens and their model. */
const FILES = [
  "components/notifications/NotificationBell.tsx",
  "components/notifications/NotificationsPanel.tsx",
  "components/notifications/PreferencesPanel.tsx",
  "components/setup/NotificationTextPanel.tsx",
  "components/automation/HealthPanel.tsx",
  "lib/notifications/model.ts",
];

/**
 * The browser holds no list of what notifications are made of (ADR-0154 to ADR-0156, ADR-0163): the kinds of notice, the
 * categories and channels of the preferences, the placeholders of a text, the limits of a text and the sections of the
 * health are what the API gives, so a kind the server adds is on the screen the day it ships. The screens hold words for
 * what they show and decide nothing. A test that names a kind does so in its own fixture.
 */
describe("the notification screens", () => {
  const sources = FILES.map((file) => ({ file, text: readFileSync(path.join(src, file), "utf8") }));

  it("read the screens that exist (the scan is not looking at nothing)", () => {
    expect(sources.every((one) => one.text.length > 300)).toBe(true);
    expect(sources.map((one) => path.basename(one.file))).toContain("NotificationTextPanel.tsx");
  });

  it("name no kind of notice, no category and no channel", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/\b(APPROVAL_TASK|APPROVAL_REMINDER|APPROVAL_DECIDED|WORKFLOW_NOTICE|TRIAL_NOTICE)\b/);
      expect(text, file).not.toMatch(/["'](APPROVALS|WORKFLOWS|ACCOUNT|IN_APP|MAIL)["']/);
    }
  });

  it("name no placeholder of a text and hold no limit of a text", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/\{(organization|object|process|step|workflow|outcome)\}/);
      expect(text, file).not.toMatch(/\b(?:at most|up to|no more than) \d+ (?:characters|reminders|days)\b/);
      expect(text, file).not.toMatch(/\bmaxLength=\{\d+\}/);
    }
  });

  it("name no section of the health of automation", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/["'](workflows|approvals|mail)\.(?:queued|deadLettered|overdue|dead|nobody)["']/);
    }
  });

  it("decide no permission from an ability name", () => {
    for (const { file, text } of sources) {
      expect(text.match(/["'](?:automation|metadata|notifications)\.[a-z-]+["']/g) ?? [], file).toEqual([]);
    }
  });
});
