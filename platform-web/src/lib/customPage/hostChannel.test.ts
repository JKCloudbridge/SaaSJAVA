import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  isSafeNavigationPath,
  MAX_RENEW_FAILURES,
  MIN_RENEW_GAP_MS,
  openHostChannel,
  RENEW_BEFORE_MS,
  RENEW_RETRY_MS,
  type HostChannelOptions,
  type TokenGrant,
} from "./hostChannel.ts";
import { FRAME_SOURCE, isThemeEntry, MAX_FRAME_HEIGHT, PROTOCOL_VERSION } from "./protocol.ts";

/** A window that records what it is sent, standing for the frame. */
class FakeFrameWindow {
  readonly sent: { message: unknown; target: string; transfer: Transferable[] }[] = [];

  postMessage(message: unknown, target: string, transfer: Transferable[] = []) {
    this.sent.push({ message, target, transfer });
  }
}

/** The page's own window: only the part the channel uses. */
class FakeHostWindow {
  private listeners: ((event: { source: unknown; data: unknown }) => void)[] = [];

  addEventListener(_type: string, listener: (event: { source: unknown; data: unknown }) => void) {
    this.listeners.push(listener);
  }

  removeEventListener(_type: string, listener: (event: { source: unknown; data: unknown }) => void) {
    this.listeners = this.listeners.filter((existing) => existing !== listener);
  }

  get listenerCount() {
    return this.listeners.length;
  }

  deliver(source: unknown, data: unknown) {
    for (const listener of [...this.listeners]) {
      listener({ source, data });
    }
  }
}

const HELLO = { source: FRAME_SOURCE, v: PROTOCOL_VERSION, type: "hello" };

function grant(token: string, endsInMs: number, now: number): TokenGrant {
  return { token, expiresAt: new Date(now + endsInMs).toISOString() };
}

describe("the host's half of the conversation with a custom page", () => {
  let frameWindow: FakeFrameWindow;
  let hostWindow: FakeHostWindow;
  let clock: number;
  let tokens: TokenGrant[];
  let requests: number;
  let problems: string[];
  let resizes: number[];
  let navigations: string[];
  let connected: number;
  const opened: { dispose(): void }[] = [];
  const ports: MessagePort[] = [];

  function open(overrides: Partial<HostChannelOptions> = {}) {
    const host = openHostChannel({
      frame: { contentWindow: frameWindow } as unknown as HTMLIFrameElement,
      hostWindow: hostWindow as unknown as Window,
      context: { page: { apiName: "Page__c", label: "Page" }, user: { id: "u-1" }, locale: "en", scheme: "light" },
      theme: { "--color-accent": "teal", "--color-text": "black", "--evil": "red", "--radius-sm": "url(http://x/y)" },
      requestToken: async () => {
        requests += 1;
        const next = tokens.shift();
        if (!next) {
          throw new Error("no token");
        }
        return next;
      },
      onResize: (height) => resizes.push(height),
      onNavigate: (path) => navigations.push(path),
      onProblem: (problem) => problems.push(problem),
      onConnected: () => {
        connected += 1;
      },
      now: () => clock,
      ...overrides,
    });
    opened.push(host);
    return host;
  }

  /** The port the host handed over and the messages it has sent on its own end so far. */
  async function connectFrame(): Promise<{ port: MessagePort; messages: unknown[] }> {
    hostWindow.deliver(frameWindow, HELLO);
    await vi.waitFor(() => expect(frameWindow.sent).toHaveLength(1));
    const port = frameWindow.sent[0]?.transfer[0] as MessagePort;
    ports.push(port);
    const messages: unknown[] = [];
    port.onmessage = (event: MessageEvent) => messages.push(event.data);
    await vi.waitFor(() => expect(messages.length).toBeGreaterThan(0));
    return { port, messages };
  }

  /** Lets the test control the channel's timers; the other tests run on the real clock. */
  function controlTimers() {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  }

  /** Lets messages that are already on their way arrive, to prove that nothing more arrives. */
  async function settle() {
    for (let i = 0; i < 5; i += 1) {
      await new Promise((resolve) => setImmediate(resolve));
    }
  }

  beforeEach(() => {
    frameWindow = new FakeFrameWindow();
    hostWindow = new FakeHostWindow();
    clock = Date.parse("2026-10-15T10:00:00Z");
    tokens = [grant("token-1", 300_000, clock)];
    requests = 0;
    problems = [];
    resizes = [];
    navigations = [];
    connected = 0;
  });

  afterEach(() => {
    opened.splice(0).forEach((host) => host.dispose());
    ports.splice(0).forEach((port) => port.close());
    vi.useRealTimers();
  });

  it("answers the frame's hello with one end of a private channel and nothing secret on the window", async () => {
    open();

    const { messages } = await connectFrame();

    const [connect] = frameWindow.sent;
    expect(connect?.message).toEqual({ source: "platform-host", v: PROTOCOL_VERSION, type: "connect" });
    expect(connect?.target).toBe("*");
    expect(JSON.stringify(connect?.message)).not.toContain("token");
    expect(messages[0]).toMatchObject({ v: PROTOCOL_VERSION, type: "init" });
    expect(connected).toBe(1);
  });

  it("gives the token, the person and the page over the channel, with only the design values it allows", async () => {
    open();

    const { messages } = await connectFrame();
    const init = messages[0] as { context: Record<string, unknown>; theme: Record<string, string> };

    expect(init.context).toMatchObject({
      token: "token-1",
      page: { apiName: "Page__c", label: "Page" },
      user: { id: "u-1" },
      locale: "en",
      scheme: "light",
    });
    expect(init.context.expiresAt).toBe(new Date(clock + 300_000).toISOString());
    expect(init.theme).toEqual({ "--color-accent": "teal", "--color-text": "black" });
  });

  it("ignores a hello from any window but its own frame, and any message that is not a hello", () => {
    open();
    const stranger = new FakeFrameWindow();

    hostWindow.deliver(stranger, HELLO);
    hostWindow.deliver(frameWindow, { ...HELLO, source: "somebody-else" });
    hostWindow.deliver(frameWindow, { ...HELLO, v: 2 });
    hostWindow.deliver(frameWindow, { ...HELLO, type: "init" });
    hostWindow.deliver(frameWindow, "hello");
    hostWindow.deliver(frameWindow, null);

    expect(frameWindow.sent).toHaveLength(0);
    expect(stranger.sent).toHaveLength(0);
    expect(requests).toBe(0);
  });

  it("answers only the first hello: a frame that navigated away gets no second channel", async () => {
    open();
    await connectFrame();

    hostWindow.deliver(frameWindow, HELLO);
    hostWindow.deliver(frameWindow, HELLO);

    expect(frameWindow.sent).toHaveLength(1);
    expect(requests).toBe(1);
  });

  it("reports a frame that never says hello as a timeout", () => {
    controlTimers();
    open({ connectTimeoutMs: 5000 });

    vi.advanceTimersByTime(4999);
    expect(problems).toEqual([]);
    vi.advanceTimersByTime(1);

    expect(problems).toEqual(["timeout"]);
  });

  it("does not report a timeout once the frame has connected", async () => {
    controlTimers();
    open({ connectTimeoutMs: 5000 });
    await connectFrame();

    vi.advanceTimersByTime(60_000);

    expect(problems).not.toContain("timeout");
  });

  it("renews the token a minute before it ends and gives the page the new one", async () => {
    controlTimers();
    tokens.push(grant("token-2", 300_000, clock + 240_000));
    open();
    const { messages } = await connectFrame();

    clock += 240_000;
    await vi.advanceTimersByTimeAsync(300_000 - RENEW_BEFORE_MS);

    await vi.waitFor(() => expect(messages).toHaveLength(2));
    expect(messages[1]).toMatchObject({ type: "token", token: "token-2" });
    expect(requests).toBe(2);
  });

  it("tries again after a failed renewal and gives up with a problem after three", async () => {
    controlTimers();
    open();
    await connectFrame();

    await vi.advanceTimersByTimeAsync(300_000 - RENEW_BEFORE_MS);
    for (let i = 1; i < MAX_RENEW_FAILURES; i += 1) {
      expect(problems).toEqual([]);
      await vi.advanceTimersByTimeAsync(RENEW_RETRY_MS);
    }

    await vi.waitFor(() => expect(problems).toEqual(["token"]));
    expect(requests).toBe(1 + MAX_RENEW_FAILURES);
  });

  it("reports a problem when the first token cannot be had", async () => {
    tokens = [];
    open();

    hostWindow.deliver(frameWindow, HELLO);

    await vi.waitFor(() => expect(problems).toEqual(["token"]));
    expect(connected).toBe(0);
  });

  it("sends a new token when the page asks, never more often than the minimum gap", async () => {
    tokens.push(grant("token-2", 300_000, clock + 10_000), grant("token-3", 300_000, clock + 20_000));
    open();
    const { port, messages } = await connectFrame();
    const ask = () => port.postMessage({ v: PROTOCOL_VERSION, type: "token-request" });

    ask();
    await settle();
    expect(requests).toBe(1);

    clock += MIN_RENEW_GAP_MS;
    ask();
    await vi.waitFor(() => expect(messages).toHaveLength(2));

    expect(messages[1]).toMatchObject({ type: "token", token: "token-2" });
    expect(requests).toBe(2);
  });

  it("passes on a height after limiting it, and ignores one that is not a height", async () => {
    open();
    const { port } = await connectFrame();

    for (const height of [120, 1.2, MAX_FRAME_HEIGHT * 5, -1, Number.NaN, "tall", null]) {
      port.postMessage({ v: PROTOCOL_VERSION, type: "resize", height });
    }

    await vi.waitFor(() => expect(resizes).toEqual([120, 2, MAX_FRAME_HEIGHT]));
  });

  it("passes on a path inside the organization and nothing that leaves it", async () => {
    open();
    const { port } = await connectFrame();

    for (const path of [
      "/apps/Sales__c/Home?tab=1",
      "//evil.example.test/x",
      "https://evil.example.test/",
      "javascript:alert(1)",
      "/a/../b",
      "/a b",
      "relative",
      "/" + "a".repeat(400),
      42,
      null,
    ]) {
      port.postMessage({ v: PROTOCOL_VERSION, type: "navigate", path });
    }

    await vi.waitFor(() => expect(navigations).toEqual(["/apps/Sales__c/Home?tab=1"]));
  });

  it("reports a failed script by its kind alone and ignores any other kind", async () => {
    open();
    const { port } = await connectFrame();

    port.postMessage({ v: PROTOCOL_VERSION, type: "error", kind: "script" });
    port.postMessage({ v: PROTOCOL_VERSION, type: "error", kind: "promise", message: "a customer's name" });
    port.postMessage({ v: PROTOCOL_VERSION, type: "error", kind: "anything" });
    port.postMessage({ v: 99, type: "error", kind: "script" });
    port.postMessage({ v: PROTOCOL_VERSION, type: "unknown" });

    await vi.waitFor(() => expect(problems).toEqual(["script", "promise"]));
    expect(JSON.stringify(problems)).not.toContain("customer");
  });

  it("gives the page the new design values when the scheme changes, filtered the same way", async () => {
    const host = open();
    const { messages } = await connectFrame();

    host.setTheme("dark", { "--color-accent": "mint", "--other": "x" });

    await vi.waitFor(() => expect(messages).toHaveLength(2));
    expect(messages[1]).toEqual({ v: PROTOCOL_VERSION, type: "theme", scheme: "dark", theme: { "--color-accent": "mint" } });
  });

  it("stops listening, stops renewing and closes the channel when it is disposed", async () => {
    controlTimers();
    const host = open();
    const { messages } = await connectFrame();

    host.dispose();
    host.dispose();
    await vi.advanceTimersByTimeAsync(10 * 60_000);
    host.setTheme("dark", {});
    await settle();

    expect(hostWindow.listenerCount).toBe(0);
    expect(requests).toBe(1);
    expect(messages).toHaveLength(1);
  });

  it("does not answer a hello that comes after it was disposed", () => {
    const host = open();
    host.dispose();

    hostWindow.deliver(frameWindow, HELLO);

    expect(frameWindow.sent).toHaveLength(0);
  });
});

describe("the paths a page may ask the host to open", () => {
  it("accepts a path inside the organization, with a query or a fragment", () => {
    for (const path of ["/", "/members", "/apps/Sales__c/c-Inspections__c", "/a/b?x=1&y=2", "/a#top", "/a%20b"]) {
      expect(isSafeNavigationPath(path), path).toBe(true);
    }
  });

  it("refuses everything that could lead elsewhere or climb out of the application", () => {
    for (const path of [
      "",
      "members",
      "//host",
      "///x",
      "/..",
      "/a/..",
      "/a/../b",
      "/a/./../b",
      "http://x",
      "javascript:1",
      "/a\\b",
      "/a b",
      "/a\nb",
      "/<script>",
      "/a;b",
    ]) {
      expect(isSafeNavigationPath(path), JSON.stringify(path)).toBe(false);
    }
  });
});

describe("the design values a page may be given", () => {
  it("are the names on the list with a short value that cannot close a declaration or load anything", () => {
    expect(isThemeEntry("--color-accent", "teal")).toBe(true);
    expect(isThemeEntry("--radius-sm", "0.4375rem")).toBe(true);
    expect(isThemeEntry("--shadow-1", "0 1px 2px rgb(16 24 40 / 6%), 0 1px 3px rgb(16 24 40 / 5%)")).toBe(true);
    expect(isThemeEntry("--font-sans", "Arial")).toBe(false);
    expect(isThemeEntry("--color-accent", "")).toBe(false);
    expect(isThemeEntry("--color-accent", "x".repeat(201))).toBe(false);
    for (const value of ["red; color: blue", "url(http://x)", "URL (x)", "a } b", "<b>", "@import 'x'", "expression(1)", "a\\b"]) {
      expect(isThemeEntry("--color-accent", value), value).toBe(false);
    }
  });
});
