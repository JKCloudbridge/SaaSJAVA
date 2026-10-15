/**
 * The message protocol between the page that hosts a custom page and the sandboxed frame that runs a tenant's
 * JavaScript (Sprint 18, ADR-0127). This file is the one place the shapes are written down: the frame's bootstrap script
 * (`frameDocument.ts`) and the host (`hostChannel.ts`) both follow it, and `docs/custom-pages.md` explains it for people.
 *
 * Only erasable TypeScript is used here, no imports and no path aliases, so that the real-browser test in
 * `spikes/custom-page-sandbox` can load this file with Node directly.
 *
 * <p>How a conversation goes:
 * <ol>
 *   <li>The frame loads and posts {@link FrameHello} to its parent window (only to the page's own origin).</li>
 *   <li>The host checks that the sender is the frame it made, makes a private {@link MessageChannel} and sends
 *       {@link HostConnect} with one end of it. Nothing secret travels on that first message.</li>
 *   <li>Everything else, including the access token, travels over the private channel as {@link HostMessage} and
 *       {@link FrameMessage}. A document that did not receive the end of the channel (another window, or the frame after
 *       it navigated somewhere else) can never read or write it.</li>
 * </ol>
 */

/** Version of this protocol. A frame and a host that disagree on it do not talk; bump it on any breaking change. */
export const PROTOCOL_VERSION = 1;

/** Value of `source` on the first message the frame posts to its parent window. */
export const FRAME_SOURCE = "platform-custom-page";

/** Value of `source` on the first message the host posts to the frame window (the one that carries the channel). */
export const HOST_SOURCE = "platform-host";

/** The first message, frame to host, over the window: "I am loaded". It carries no data. */
export interface FrameHello {
  source: typeof FRAME_SOURCE;
  v: typeof PROTOCOL_VERSION;
  type: "hello";
}

/** The reply, host to frame, over the window, with the host's end of a private channel as the transferred port. */
export interface HostConnect {
  source: typeof HOST_SOURCE;
  v: typeof PROTOCOL_VERSION;
  type: "connect";
}

/** What the host tells the custom page about where it runs. Nothing in it is secret except the token. */
export interface FrameContext {
  /** The page being shown. */
  page: { apiName: string; label: string };
  /** The signed-in member: the identifier only, never an e-mail address or a role. */
  user: { id: string };
  /** The access token of the page: short-lived and limited to the data interface (ADR-0127). */
  token: string;
  /** When the token ends, as an ISO 8601 instant. The host sends a fresh one before that. */
  expiresAt: string;
  /** The language of the person, a BCP 47 tag. */
  locale: string;
  /** `light` or `dark`. */
  scheme: "light" | "dark";
}

/** Design values the page may use as CSS custom properties; names are checked on both sides. */
export type ThemeVariables = Record<string, string>;

/** Host to frame, over the private channel. */
export type HostMessage =
  | { v: typeof PROTOCOL_VERSION; type: "init"; context: FrameContext; theme: ThemeVariables }
  | { v: typeof PROTOCOL_VERSION; type: "token"; token: string; expiresAt: string }
  | { v: typeof PROTOCOL_VERSION; type: "theme"; scheme: "light" | "dark"; theme: ThemeVariables };

/** Frame to host, over the private channel. */
export type FrameMessage =
  /** The page wants a height (CSS pixels); the host decides whether to apply it. */
  | { v: typeof PROTOCOL_VERSION; type: "resize"; height: number }
  /** The page asks to open another page inside the organization; the host checks the path and decides. */
  | { v: typeof PROTOCOL_VERSION; type: "navigate"; path: string }
  /** The access token was refused or is about to end; the host sends a new one. */
  | { v: typeof PROTOCOL_VERSION; type: "token-request" }
  /** The page's script failed. The kind only: the text of an error may hold a person's data and is not sent. */
  | { v: typeof PROTOCOL_VERSION; type: "error"; kind: "script" | "promise" };

/** The CSS custom properties the host copies from the design tokens into the frame, and nothing else. */
export const THEME_VARIABLE_NAMES: readonly string[] = [
  "--color-background",
  "--color-surface",
  "--color-surface-sunken",
  "--color-border",
  "--color-border-strong",
  "--color-text",
  "--color-text-muted",
  "--color-accent",
  "--color-accent-hover",
  "--color-accent-contrast",
  "--color-accent-soft",
  "--color-success",
  "--color-success-surface",
  "--color-danger",
  "--color-danger-surface",
  "--color-focus-ring",
  "--radius-sm",
  "--radius-md",
  "--radius-lg",
  "--radius-pill",
  "--border-width",
  "--shadow-1",
  "--font-size-xs",
  "--font-size-sm",
  "--font-size-md",
  "--font-size-lg",
  "--font-size-xl",
  "--line-height",
  "--space-1",
  "--space-2",
  "--space-3",
  "--space-4",
  "--space-5",
  "--space-6",
];

/**
 * Values the frame will take as a design value: a custom property name from the list above and a short value without
 * anything that could close a declaration or start a function that loads something.
 */
export function isThemeEntry(name: string, value: string): boolean {
  return (
    THEME_VARIABLE_NAMES.includes(name) &&
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= 200 &&
    !/[;{}<>\\]|url\s*\(|@import|expression\s*\(/i.test(value)
  );
}

/** Longest height a page may ask for: a page that wants more scrolls inside its frame. */
export const MAX_FRAME_HEIGHT = 20000;

/** Longest path of a `navigate` request. */
export const MAX_NAVIGATE_PATH = 300;
