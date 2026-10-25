"use client";

import { useEffect, useState } from "react";

/** Success expires after three quiet seconds. Errors remain until replaced by the caller. */
export function ActionNotice({ text, error = false, testId, className, occurrence = text }: {
  text: string; error?: boolean; testId?: string; className?: string; occurrence?: unknown;
}) {
  const [expired, setExpired] = useState<{ occurrence: unknown } | null>(null);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  useEffect(() => {
    if (error || hovered || focused) return;
    const timer = setTimeout(() => setExpired({ occurrence }), 3000);
    return () => clearTimeout(timer);
  }, [text, error, hovered, focused, occurrence]);
  if (!error && expired?.occurrence === occurrence) return null;
  return <p role={error ? "alert" : "status"} aria-live={error ? "assertive" : "polite"}
    className={error ? "form-message" : className} data-testid={testId}
    onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
    onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}>
    {text}
  </p>;
}
