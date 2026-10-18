import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..");

/** The sources of the approval screens and their model: everything that draws or decides something about an approval. */
const FILES = [
  "components/approvals/Inbox.tsx",
  "components/approvals/ApprovalActionForm.tsx",
  "components/approvals/PersonPicker.tsx",
  "components/approvals/DelegationsPanel.tsx",
  "components/setup/ApprovalBuilder.tsx",
  "components/setup/ApprovalProcessesPanel.tsx",
  "components/setup/ApprovalStepCard.tsx",
  "components/records/ApprovalPanel.tsx",
  "lib/builder/approvalModel.ts",
];

/**
 * The browser holds no list of what approvals can be made of (ADR-0132, ADR-0145, ADR-0152): the kinds of approver rule,
 * the completion rules, the outcomes, the states of a request and of a step and the limits are the catalogue the API
 * gives, so a kind the server adds is in the builder the day it ships. The screens hold words for what they show
 * (`lib/approvals/words.ts`, which is presentation and exempt) and decide nothing: what a person may do is the API's
 * `actions`. A test that names a kind does so in its own catalogue (`src/test/approvals.ts`).
 */
describe("the approval screens", () => {
  const sources = FILES.map((file) => ({ file, text: readFileSync(path.join(src, file), "utf8") }));

  it("read the screens that exist (the scan is not looking at nothing)", () => {
    expect(sources.every((one) => one.text.length > 500)).toBe(true);
    expect(sources.map((one) => path.basename(one.file))).toContain("ApprovalBuilder.tsx");
  });

  it("name no kind of approver rule", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/\b(OWNER_ROLE_MANAGER|RECORD_USER|OWNER)\b/);
      expect(text, file).not.toMatch(/kind\s*===?\s*"(USER|PROFILE|ROLE|GROUP)"/);
    }
  });

  it("name no completion rule and no state of a request or a step", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/["'](ANY|ALL|PENDING|WAITING|ACTIVE|SKIPPED|CANCELLED)["']/);
      expect(text, file).not.toMatch(/status\s*===?\s*["'](APPROVED|REJECTED|RECALLED|PENDING)["']/);
    }
  });

  it("hold no limit of the platform: a number is read from the catalogue", () => {
    for (const { file, text } of sources) {
      // The numbers of the settings of the platform (steps per process, approvers per step, fields per outcome, the days of
      // a delegation) written as a literal in a bound or a sentence.
      expect(text, file).not.toMatch(/\b(?:at most|up to|no more than) \d+ (?:steps|approvers|fields|days)\b/);
      expect(text, file).not.toMatch(/\bmax=\{\d+\}/);
    }
  });

  it("decide no permission from an ability name except to offer a choice the API judges again", () => {
    for (const { file, text } of sources) {
      const abilities = text.match(/["']approvals\.[a-z-]+["']/g) ?? [];
      expect(abilities.filter((one) => one !== '"approvals.manage"'), file).toEqual([]);
    }
  });
});
