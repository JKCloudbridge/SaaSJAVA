"use client";

import { useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type FieldView = components["schemas"]["FieldView"];
type CheckView = components["schemas"]["ExpressionCheckView"];
type TestView = components["schemas"]["ExpressionTestView"];
/** What trying an expression gave: the engine's own answer, or (for a workflow condition) the workflow check's. */
type TryView = Pick<TestView, "outcome"> & Partial<Pick<TestView, "message" | "value" | "problem">>;

/** The words for the type an expression gives, from the type the API names. */
const TYPE_WORDS: Record<string, string> = {
  BOOLEAN: "true or false",
  NUMBER: "a number",
  TEXT: "text",
  DATE: "a date",
  DATETIME: "a date and time",
  TIME: "a time",
  NONE: "no value",
};

/** What a sample value looks like for a field type; the API reads it by the type of its field and refuses what does not fit. */
const SAMPLE_HINT: Record<string, string> = {
  BOOLEAN: "true or false",
  NUMBER: "a number such as 12",
  DECIMAL: "a number such as 12.5",
  CURRENCY: "a number such as 12.50",
  PERCENT: "a number such as 7.5",
  DATE: "a date such as 2030-01-31",
  DATETIME: "a date and time such as 2030-01-31T09:30:00Z",
  TIME: "a time such as 09:30",
};

interface Answer<T> {
  /** What the answer is for: it is shown only while the text it was asked about is still the text in the box. */
  key: string;
  result?: T;
  failure?: string;
}

/**
 * Asks the real expression engine whether a formula or the condition of a validation rule is valid, and tries it on sample
 * values the person types. The page holds no grammar, no list of functions and no evaluator: it sends the text and shows the
 * API's own words, the place that is wrong (line and column), the type it gives and the fields it uses. Nothing is stored
 * and nothing is decided here; the API checks again when the formula or the rule is saved.
 */
export function ExpressionCheck({
  objectApiName,
  expression,
  purpose,
  resultType,
  ruleMessage,
  fields,
  testId,
}: {
  objectApiName: string;
  expression: string;
  purpose: "FORMULA" | "VALIDATION_RULE" | "WORKFLOW_CONDITION";
  /** For a formula: the type it must give, when one is chosen. */
  resultType?: string;
  /** For a rule: the message of the rejection, shown when the sample values would be rejected. */
  ruleMessage?: string;
  /** The fields of the object, for the labels and the types of the sample values. */
  fields: FieldView[];
  testId: string;
}) {
  const key = `${purpose}|${resultType ?? ""}|${expression}`;
  const [checked, setChecked] = useState<Answer<CheckView> | undefined>();
  const [tried, setTried] = useState<Answer<TryView> | undefined>();
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const check = checked?.key === key ? checked : undefined;
  const outcome = tried?.key === key ? tried : undefined;
  const used = (check?.result?.valid ? check.result.fields : []).flatMap((name) => {
    const field = fields.find((candidate) => candidate.apiName === name);
    return field ? [field] : [];
  });

  async function runCheck() {
    setBusy(true);
    try {
      const { data, error, response } = await api.POST("/api/v1/metadata/expressions/check", {
        body: {
          objectApiName,
          expression,
          purpose,
          resultType: purpose === "FORMULA" && resultType ? resultType : undefined,
        },
      });
      setChecked(data ? { key, result: data.data } : { key, failure: await failureText(error, response) });
    } catch {
      setChecked({ key, failure: COMMON_TEXT.network });
    } finally {
      setBusy(false);
    }
  }

  async function runTry() {
    setBusy(true);
    const given = Object.fromEntries(
      used.flatMap((field) => {
        const text = values[field.apiName] ?? "";
        return text === "" ? [] : [[field.apiName, text] as const];
      }),
    );
    try {
      if (purpose === "WORKFLOW_CONDITION") {
        // The condition of a workflow is tried by the workflow's own check, which answers what the workflow would do.
        const { data, error, response } = await api.POST("/api/v1/metadata/workflows/{objectApiName}/check", {
          params: { path: { objectApiName } },
          body: { condition: expression, values: given },
        });
        setTried(
          data
            ? { key, result: { outcome: data.data.outcome, problem: data.data.problem } }
            : { key, failure: await failureText(error, response) },
        );
        return;
      }
      const { data, error, response } = await api.POST("/api/v1/metadata/expressions/test", {
        body: { objectApiName, expression, purpose, values: given },
      });
      setTried(data ? { key, result: data.data } : { key, failure: await failureText(error, response) });
    } catch {
      setTried({ key, failure: COMMON_TEXT.network });
    } finally {
      setBusy(false);
    }
  }

  const verb = purpose === "FORMULA" ? "Check the formula" : "Check the condition";

  return (
    <div className="stack" data-testid={testId}>
      <p>
        <button type="button" className="button" disabled={busy || expression.trim() === ""} onClick={() => void runCheck()}>
          {verb}
        </button>
      </p>
      <div role="status" aria-live="polite">
        {check?.failure ? (
          <p className="form-message" role="alert" data-testid={`${testId}-failed`}>
            {check.failure}
          </p>
        ) : null}
        {check?.result?.valid ? (
          <p data-testid={`${testId}-valid`}>
            It is valid. It gives {TYPE_WORDS[check.result.type ?? "NONE"] ?? check.result.type}.{" "}
            {check.result.fields.length > 0 ? `It uses ${check.result.fields.join(", ")}.` : "It uses no fields."}
            {check.result.usesClock ? " It asks for the moment of the save." : ""}
          </p>
        ) : null}
        {check?.result && !check.result.valid ? (
          <p className="form-message" role="alert" data-testid={`${testId}-invalid`}>
            It is not valid. {check.result.problem?.message}
            {check.result.fields.length > 0 ? ` It names ${check.result.fields.join(", ")}.` : ""}
          </p>
        ) : null}
      </div>

      {check?.result?.valid ? (
        <fieldset className="field" data-testid={`${testId}-try`}>
          <legend>Try it with sample values</legend>
          <p className="hint">
            There are no records yet, so type the values a record might have. A box left empty means the field has no value.
          </p>
          {used.map((field) => (
            <div className="field" key={field.apiName}>
              <label htmlFor={`${testId}-value-${field.apiName}`}>
                {field.label} <code>{field.apiName}</code>
              </label>
              <input
                id={`${testId}-value-${field.apiName}`}
                value={values[field.apiName] ?? ""}
                onChange={(event) => setValues({ ...values, [field.apiName]: event.target.value })}
                aria-describedby={`${testId}-hint-${field.apiName}`}
              />
              <p className="hint" id={`${testId}-hint-${field.apiName}`}>
                {SAMPLE_HINT[field.type] ?? "any text"}
              </p>
            </div>
          ))}
          <button type="button" className="button" disabled={busy} onClick={() => void runTry()}>
            Try it
          </button>
          <div role="status" aria-live="polite">
            {outcome?.failure ? (
              <p className="form-message" role="alert" data-testid={`${testId}-try-failed`}>
                {outcome.failure}
              </p>
            ) : null}
            {outcome?.result ? <p data-testid={`${testId}-outcome`}>{outcomeText(outcome.result, ruleMessage)}</p> : null}
          </div>
        </fieldset>
      ) : null}
    </div>
  );
}

/** What the sample values would do, in words, from what the API answered. */
function outcomeText(result: TryView, ruleMessage: string | undefined): string {
  switch (result.outcome) {
    case "RUNS":
      return "The workflow runs: the condition is true for these values.";
    case "SKIPPED":
      return "The workflow is skipped: the condition is false, or has no value, for these values.";
    case "REJECTED":
      return `Rejected: ${result.message || ruleMessage || "the condition is true for these values."}`;
    case "PASSED":
      return "Passes: the condition is false, or has no value, for these values.";
    case "VALUE":
      return result.value == null ? "The formula gives no value for these values." : `The formula gives: ${result.value}`;
    default:
      return `It could not be worked out. ${result.problem?.message ?? ""}`.trim();
  }
}
