import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..");

/** The sources of the workflow screens and their models: everything that draws or decides something about a workflow. */
const FILES = [
  ...readdirSync(path.join(src, "components", "setup"))
    .filter((name) => /^Workflow.*\.tsx$/.test(name) && !/\.test\./.test(name))
    .map((name) => path.join(src, "components", "setup", name)),
  ...readdirSync(path.join(src, "components", "workflows"))
    .filter((name) => /\.tsx$/.test(name) && !/\.test\./.test(name))
    .map((name) => path.join(src, "components", "workflows", name)),
  path.join(src, "lib", "builder", "workflowModel.ts"),
  path.join(src, "lib", "workflows", "runsModel.ts"),
];

/**
 * The browser holds no list of what the platform can do (ADR-0132, ADR-0138): the types of action, the kinds of trigger,
 * the names of their settings, the closed list of mail templates and the limits are the catalogue the API gives, so a type the
 * server adds is in the palette the day it ships and a type it drops is gone. The screens only hold words for what they
 * show (the reasons of a run) and presentation. A test that names an action type does so in its own catalogue
 * (`src/test/workflows.ts`), which is where the screens must read it from.
 */
describe("the workflow screens", () => {
  const sources = FILES.map((file) => ({ file: path.relative(src, file), text: readFileSync(file, "utf8") }));

  it("read the screens that exist (the scan is not looking at nothing)", () => {
    const names = sources.map((one) => path.basename(one.file));
    expect(names).toEqual(expect.arrayContaining(["WorkflowBuilder.tsx", "WorkflowActionCard.tsx", "ExecutionsPanel.tsx",
      "ExecutionDetail.tsx", "workflowModel.ts", "runsModel.ts"]));
  });

  it("name no type of action", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/\b(UPDATE_RECORD|CREATE_RECORD|SEND_EMAIL|PUBLISH_EVENT)\b/);
    }
  });

  it("name no kind of trigger", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(
        /\b(RECORD_CREATED|RECORD_UPDATED|RECORD_DELETED|FIELD_CHANGED|EVERY_DAY|EVERY_WEEK|DATE_TIME|DAYS_AFTER_DATE)\b/,
      );
    }
  });

  it("name no setting of an action and no mail template", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/\b(recipientField|linkField|RECORD_NOTICE)\b/);
    }
  });

  it("hold no limit of the platform: a number is read from the catalogue", () => {
    for (const { file, text } of sources) {
      // The numbers of the settings of the platform (actions per workflow, fields per action, the length of a value, the
      // attempts, the depth of a chain, the runs per minute) written as a literal in a bound or a sentence.
      expect(text, file).not.toMatch(/\bmax=\{\d+\}|maxLength=\{(?:25|1000|10)\}|\bat most \d+ (?:actions|fields)\b/);
    }
  });

  it("send only what the catalogue's own properties say: the trigger is built from the kind's list", () => {
    const model = sources.find((one) => one.file.endsWith("workflowModel.ts"))?.text ?? "";
    expect(model).toContain("kind?.properties");
    expect(model).not.toMatch(/trigger\.kind\s*===/);
  });
});
