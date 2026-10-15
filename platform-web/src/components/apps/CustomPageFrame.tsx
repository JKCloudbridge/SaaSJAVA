"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { log } from "@/lib/log";
import { buildFrameDocument, FRAME_SANDBOX } from "@/lib/customPage/frameDocument.ts";
import { openHostChannel, type HostChannel } from "@/lib/customPage/hostChannel.ts";
import { loadScript, requestToken, type CustomPageScript, type Outcome } from "@/lib/customPage/client";
import { currentScheme, readTheme, watchScheme } from "@/lib/customPage/theme";
import { useSession } from "@/lib/session/SessionProvider";

interface Props {
  /** The custom page to show. */
  customPage: string;
  /** What the page is called in the navigation (the title of the frame, for people who cannot see it). */
  label: string;
  /** The asset the member's list names for the page; a copy of it already held here is used without a request. */
  assetId?: string;
}

/** The words for what can go wrong after the script has been handed over, never repeating anything the script said. */
const PROBLEMS = {
  timeout: "The page did not start. Try again.",
  token: "The page could not be given access to your data. Reload to sign in again if this keeps happening.",
} as const;

/**
 * Shows a custom page: the script an organization uploaded, run in a sandboxed frame (Sprint 18, ADR-0127). This
 * component does four things and decides nothing about data. It fetches the script the member may open, builds the
 * sandboxed document, sets up the private channel to it ({@link openHostChannel}), and shows how it is going. The script
 * talks to the API itself with a short-lived token of the member's own, so every permission check the platform has still
 * decides; this component never reads a record and never holds the token longer than the channel does.
 *
 * <p>Everything it remembers is tied to one <em>run</em>: the page, the asset and the attempt. A new run (another page,
 * a new asset, or "Try again") starts from nothing, so no answer, problem or note of an earlier run can show up in the
 * next, and nothing here has to reset state by hand.
 */
export function CustomPageFrame({ customPage, label, assetId }: Props) {
  const { state: session } = useSession();
  const router = useRouter();
  const userId = session.status === "signedIn" ? session.user.id : undefined;
  const [attempt, setAttempt] = useState(0);
  const run = `${customPage}|${assetId ?? ""}|${attempt}`;
  const [fetched, setFetched] = useState<{ run: string; outcome: Outcome<CustomPageScript> } | null>(null);
  const [connected, setConnected] = useState<string | null>(null);
  const [failed, setFailed] = useState<{ run: string; message: string } | null>(null);
  const [noted, setNoted] = useState<string | null>(null);
  const frame = useRef<HTMLIFrameElement | null>(null);

  // 1. The script.
  useEffect(() => {
    let cancelled = false;
    void loadScript(customPage, assetId).then((outcome) => {
      if (!cancelled) {
        setFetched({ run, outcome });
      }
    });
    return () => {
      cancelled = true;
    };
  }, [customPage, assetId, run]);

  // 2. The frame and its channel. The channel listens before the frame gets its document, so the frame's first message
  //    cannot be missed.
  const script = fetched?.run === run && fetched.outcome.ok ? fetched.outcome.value : undefined;
  const gaveUp = failed?.run === run;
  const onNavigate = useCallback((path: string) => router.push(path), [router]);
  useEffect(() => {
    const element = frame.current;
    if (!script || !element || !userId || gaveUp) {
      return;
    }
    const host: HostChannel = openHostChannel({
      frame: element,
      hostWindow: window,
      context: {
        page: { apiName: customPage, label },
        user: { id: userId },
        locale: navigator.language || "en",
        scheme: currentScheme(),
      },
      theme: readTheme(),
      requestToken: () => requestToken(customPage),
      onConnected: () => setConnected(run),
      onNavigate,
      onProblem: (problem) => {
        log("warn", "a custom page reported a problem", { custom_page: customPage, problem });
        if (problem === "timeout" || problem === "token") {
          setFailed({ run, message: PROBLEMS[problem] });
        } else {
          setNoted(run);
        }
      },
    });
    const stopWatching = watchScheme((scheme) => host.setTheme(scheme, readTheme()));
    element.srcdoc = buildFrameDocument({ code: script.code, origin: window.location.origin, autoResize: false });
    return () => {
      stopWatching();
      host.dispose();
    };
  }, [script, customPage, label, userId, run, gaveUp, onNavigate]);

  // What the person sees: the first thing that went wrong in this run, else the page as far as it has come.
  const refusal = fetched?.run === run && !fetched.outcome.ok ? fetched.outcome.failure : undefined;
  const problem =
    failed && gaveUp
      ? { message: failed.message, retry: true }
      : refusal
        ? { message: refusal.message, retry: refusal.code !== "NOT_FOUND" }
        : undefined;
  if (problem) {
    return (
      <div className="custom-page-state">
        <p className="form-message" role="alert">
          {problem.message}
        </p>
        {problem.retry ? (
          <button type="button" className="button button-secondary" onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </button>
        ) : null}
      </div>
    );
  }

  const running = connected === run;
  return (
    <div className="custom-page" data-testid="custom-page" data-state={running ? "running" : "starting"}>
      {noted === run ? (
        <p className="custom-page-notice" role="status">
          This page ran into an error. What you see may be incomplete.
        </p>
      ) : null}
      {running ? null : (
        <div className="custom-page-loading" role="status" aria-live="polite">
          <span className="skeleton-line" />
          <span className="skeleton-line skeleton-short" />
          <span className="visually-hidden">Loading {label}</span>
        </div>
      )}
      {script ? (
        <iframe
          // A new frame for every run: nothing of an earlier page can leak into the next.
          key={run}
          ref={frame}
          title={label}
          sandbox={FRAME_SANDBOX}
          referrerPolicy="no-referrer"
          className={running ? "custom-page-frame" : "custom-page-frame custom-page-frame-hidden"}
        />
      ) : null}
    </div>
  );
}
