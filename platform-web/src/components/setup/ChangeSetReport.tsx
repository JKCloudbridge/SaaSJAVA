import type { components } from "@/lib/api/generated/schema";
import { ACTION_TEXT, ITEM_TEXT } from "./objectText";

type Report = components["schemas"]["ChangeSetReportView"];
type Item = components["schemas"]["ReleaseItemView"];

const PROBLEM_TEXT: Record<string, string> = {
  RULE: "Breaks a rule",
  DEPENDENCY: "Something still needs it",
  CONFLICT: "Changed by someone else",
  RECORDS: "Records would be lost",
};

/** What an item adds, changes or removes, in words: "Added field Employee__c.code__c". */
export function itemText(item: Item): string {
  const what = ITEM_TEXT[item.kind] ?? item.kind.toLowerCase();
  const name = item.itemApiName ? `${item.objectApiName}.${item.itemApiName}` : item.objectApiName;
  return `${ACTION_TEXT[item.action] ?? item.action} ${what} ${name}`;
}

/**
 * The answer to "what would happen?" for a change set or a rollback: every problem with what depends on it, and what
 * would be added, changed or removed. It only shows what the API answered.
 */
export function ChangeSetReport({ report, testId }: { report: Report; testId: string }) {
  return (
    <div data-testid={testId} className="stack">
      {report.valid ? (
        <p role="status">Nothing stops this from going ahead.</p>
      ) : (
        <>
          <p role="alert">It cannot go ahead yet. {report.problems.length} problem{report.problems.length === 1 ? "" : "s"}:</p>
          <ul data-testid={`${testId}-problems`}>
            {report.problems.map((problem, index) => (
              <li key={index}>
                <strong>{PROBLEM_TEXT[problem.kind] ?? problem.kind}</strong>
                {problem.position != null ? ` (change ${problem.position})` : ""}: {problem.message}
                {problem.fields.length > 0 ? <div className="hint">{problem.fields.join("; ")}</div> : null}
              </li>
            ))}
          </ul>
        </>
      )}
      {report.items.length > 0 ? (
        <>
          <p>It would:</p>
          <ul data-testid={`${testId}-items`}>
            {report.items.map((item, index) => (
              <li key={index}>{itemText(item)}</li>
            ))}
          </ul>
        </>
      ) : null}
      {report.objects.length > 0 ? (
        <>
          <p>The objects as they would be:</p>
          <ul data-testid={`${testId}-objects`}>
            {report.objects.map((object) => (
              <li key={object.apiName}>
                {object.label} <code>{object.apiName}</code>: {object.fields.length} fields
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}
