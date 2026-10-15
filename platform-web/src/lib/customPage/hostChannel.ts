import {
  FRAME_SOURCE,
  HOST_SOURCE,
  MAX_FRAME_HEIGHT,
  MAX_NAVIGATE_PATH,
  PROTOCOL_VERSION,
  isThemeEntry,
  type FrameContext,
  type HostConnect,
  type HostMessage,
  type ThemeVariables,
} from "./protocol.ts";

/**
 * The host's half of the conversation with a custom page's frame (Sprint 18, ADR-0127). It does four things and
 * nothing else: it recognises the frame that it made, hands it one end of a private channel, keeps its access token
 * fresh, and passes on the few requests the page may make (a height, a place to go, a failure) after checking them.
 * It decides nothing about data or permissions: the page talks to the API itself, with its own token, and the API
 * answers as for any caller.
 *
 * Only erasable TypeScript, no path aliases: the real-browser test in `spikes/custom-page-sandbox` loads this file.
 */

/** A token and when it ends. */
export interface TokenGrant {
  token: string;
  /** ISO 8601 instant. */
  expiresAt: string;
}

/** What the host gives the page when it connects. The token comes from {@link HostChannelOptions.requestToken}. */
export type HostContext = Omit<FrameContext, "token" | "expiresAt">;

/** How a conversation is set up. */
export interface HostChannelOptions {
  /** The frame to talk to. */
  frame: HTMLIFrameElement;
  /** The window that receives the frame's first message (the page's own window). */
  hostWindow: Window;
  /** Who the page is shown to and where. */
  context: HostContext;
  /** The design values to give the page. */
  theme: ThemeVariables;
  /** Asks for a token. Called at the start, before the token ends, and when the page asks. */
  requestToken: () => Promise<TokenGrant>;
  /** The page asked for a height (already limited to a sane range). */
  onResize?: (height: number) => void;
  /** The page asked to open a place inside the organization (a path that starts with one slash). */
  onNavigate?: (path: string) => void;
  /** The conversation is up and the page has its token. */
  onConnected?: () => void;
  /** Something went wrong: the page's script failed, no token could be had, or the frame never answered. */
  onProblem?: (problem: "script" | "promise" | "token" | "timeout") => void;
  /** How long to wait for the frame's first message (milliseconds). */
  connectTimeoutMs?: number;
  /** The clock, for tests. */
  now?: () => number;
}

/** A running conversation. */
export interface HostChannel {
  /** Tells the page the design values or the colour scheme changed. */
  setTheme(scheme: "light" | "dark", theme: ThemeVariables): void;
  /** Ends the conversation: stops the timers and closes the channel. Safe to call twice. */
  dispose(): void;
}

/** How long before a token ends the host asks for the next one. */
export const RENEW_BEFORE_MS = 60_000;

/** The fewest milliseconds between two renewals, so a broken clock or answer cannot make a loop. */
export const MIN_RENEW_GAP_MS = 5_000;

/** How long to wait before trying again after a renewal failed. */
export const RENEW_RETRY_MS = 15_000;

/** After this many renewals in a row failed, the host gives up and reports a problem. */
export const MAX_RENEW_FAILURES = 3;

/** The default wait for the frame's first message. */
export const DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

function isHello(data: unknown): boolean {
  if (typeof data !== "object" || data === null) {
    return false;
  }
  const message = data as Record<string, unknown>;
  return message.source === FRAME_SOURCE && message.v === PROTOCOL_VERSION && message.type === "hello";
}

function cleanTheme(theme: ThemeVariables): ThemeVariables {
  const clean: ThemeVariables = {};
  for (const [name, value] of Object.entries(theme)) {
    if (isThemeEntry(name, value)) {
      clean[name] = value;
    }
  }
  return clean;
}

/** Whether the path is a place inside the organization the page may ask for: one slash, no host, no traversal. */
export function isSafeNavigationPath(path: unknown): path is string {
  return (
    typeof path === "string" &&
    path.length <= MAX_NAVIGATE_PATH &&
    /^\/(?!\/)[A-Za-z0-9_\-./?=&%#,:~+@]*$/.test(path) &&
    !path.split(/[?#]/)[0]!.split("/").includes("..")
  );
}

/** Starts listening for the frame's first message. Call before the frame gets its `srcdoc`. */
export function openHostChannel(options: HostChannelOptions): HostChannel {
  const { frame, hostWindow, context, requestToken } = options;
  const now = options.now ?? Date.now;
  let port: MessagePort | null = null;
  let disposed = false;
  let renewTimer: ReturnType<typeof setTimeout> | undefined;
  let failures = 0;
  let lastRenewalAt = 0;

  function send(message: HostMessage): void {
    port?.postMessage(message);
  }

  function schedule(grant: TokenGrant): void {
    clearTimeout(renewTimer);
    const ends = Date.parse(grant.expiresAt);
    const wait = Math.max(MIN_RENEW_GAP_MS, ends - now() - RENEW_BEFORE_MS);
    renewTimer = setTimeout(() => void renew(), Number.isFinite(wait) ? wait : RENEW_RETRY_MS);
  }

  async function renew(): Promise<void> {
    if (disposed || !port) {
      return;
    }
    lastRenewalAt = now();
    try {
      const grant = await requestToken();
      if (disposed) {
        return;
      }
      failures = 0;
      send({ v: PROTOCOL_VERSION, type: "token", token: grant.token, expiresAt: grant.expiresAt });
      schedule(grant);
    } catch {
      failures += 1;
      if (failures >= MAX_RENEW_FAILURES) {
        options.onProblem?.("token");
        return;
      }
      clearTimeout(renewTimer);
      renewTimer = setTimeout(() => void renew(), RENEW_RETRY_MS);
    }
  }

  function onFramePort(event: MessageEvent): void {
    if (disposed) {
      return;
    }
    const data: unknown = event.data;
    if (typeof data !== "object" || data === null) {
      return;
    }
    const message = data as Record<string, unknown>;
    if (message.v !== PROTOCOL_VERSION) {
      return;
    }
    switch (message.type) {
      case "resize": {
        const height = typeof message.height === "number" ? message.height : Number.NaN;
        if (Number.isFinite(height) && height >= 0) {
          options.onResize?.(Math.min(Math.ceil(height), MAX_FRAME_HEIGHT));
        }
        break;
      }
      case "navigate":
        if (isSafeNavigationPath(message.path)) {
          options.onNavigate?.(message.path);
        }
        break;
      case "token-request":
        // A page whose token was refused asks for another one; never more often than the minimum gap.
        if (now() - lastRenewalAt >= MIN_RENEW_GAP_MS) {
          void renew();
        }
        break;
      case "error":
        if (message.kind === "script" || message.kind === "promise") {
          options.onProblem?.(message.kind);
        }
        break;
      default:
        break;
    }
  }

  async function connect(): Promise<void> {
    const channel = new MessageChannel();
    port = channel.port1;
    port.onmessage = onFramePort;
    const connect: HostConnect = { source: HOST_SOURCE, v: PROTOCOL_VERSION, type: "connect" };
    // The frame's origin is opaque, so "*" is the only target there is; the message holds nothing secret, and the
    // window it goes to is the frame this host made.
    frame.contentWindow?.postMessage(connect, "*", [channel.port2]);
    try {
      const grant = await requestToken();
      if (disposed) {
        return;
      }
      send({
        v: PROTOCOL_VERSION,
        type: "init",
        context: { ...context, token: grant.token, expiresAt: grant.expiresAt },
        theme: cleanTheme(options.theme),
      });
      lastRenewalAt = now();
      schedule(grant);
      options.onConnected?.();
    } catch {
      options.onProblem?.("token");
    }
  }

  function onWindowMessage(event: MessageEvent): void {
    if (disposed || port || event.source !== frame.contentWindow || !isHello(event.data)) {
      return;
    }
    clearTimeout(connectTimer);
    void connect();
  }

  const connectTimer = setTimeout(() => {
    if (!port && !disposed) {
      options.onProblem?.("timeout");
    }
  }, options.connectTimeoutMs ?? DEFAULT_CONNECT_TIMEOUT_MS);
  hostWindow.addEventListener("message", onWindowMessage);

  return {
    setTheme(scheme, theme) {
      send({ v: PROTOCOL_VERSION, type: "theme", scheme, theme: cleanTheme(theme) });
    },
    dispose() {
      if (disposed) {
        return;
      }
      disposed = true;
      clearTimeout(renewTimer);
      clearTimeout(connectTimer);
      hostWindow.removeEventListener("message", onWindowMessage);
      port?.close();
      port = null;
    },
  };
}
