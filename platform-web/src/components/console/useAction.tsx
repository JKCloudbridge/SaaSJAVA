"use client";

import { useCallback, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureFromResponse } from "@/lib/api/errors";
import { ensureForgeryCookie } from "@/lib/session/SessionProvider";

/** The API's own words for a refusal (a 429 gets the common text), never the page's guess. */
export async function failureText(error: unknown, response: Response): Promise<string> {
  return response.status === 429 ? COMMON_TEXT.tooMany : failureFromResponse(error, response).message;
}

/** What a call returned: the answer and, when it worked, the sentence to show. */
export interface ActionResult {
  error?: unknown;
  response: Response;
  text?: string;
}

/**
 * The shared shape of an administrator's action on a console or members page: one call at a time, the forgery cookie
 * first, the API's own words when it refuses, a short notice when it worked, and a reload afterwards. The page never
 * decides whether an action is allowed; it only reports what the API answered.
 */
export function useAction(onDone: () => Promise<void>) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | undefined>();
  const [problem, setProblem] = useState<string | undefined>();

  const act = useCallback(
    async (run: () => Promise<ActionResult>) => {
      if (busy) {
        return;
      }
      setBusy(true);
      setNotice(undefined);
      setProblem(undefined);
      try {
        await ensureForgeryCookie();
        const result = await run();
        if (result.response.ok) {
          setNotice(result.text);
          await onDone();
        } else {
          setProblem(await failureText(result.error, result.response));
        }
      } catch {
        setProblem(COMMON_TEXT.network);
      }
      setBusy(false);
    },
    [busy, onDone],
  );

  return { busy, notice, problem, act };
}

/** A notice and a problem line, in the same words and roles on every page. */
export function ActionMessages({
  notice,
  problem,
  testId,
}: {
  notice: string | undefined;
  problem: string | undefined;
  testId: string;
}) {
  return (
    <>
      {notice ? (
        <p role="status" data-testid={`${testId}-notice`}>
          {notice}
        </p>
      ) : null}
      {problem ? (
        <p className="form-message" role="alert" data-testid={`${testId}-problem`}>
          {problem}
        </p>
      ) : null}
    </>
  );
}
