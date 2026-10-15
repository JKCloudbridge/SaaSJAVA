import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FRAME_SANDBOX } from "@/lib/customPage/frameDocument.ts";
import type { HostChannelOptions } from "@/lib/customPage/hostChannel.ts";
import { setLogSink } from "@/lib/log";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { json } from "@/test/fakeApi";
import { CustomPageFrame } from "./CustomPageFrame";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));

const SCRIPT = {
  apiName: "Inspection__c",
  label: "Inspection",
  assetId: "11111111-2222-3333-4444-555555555555",
  sha256: "a".repeat(64),
  code: "document.body.textContent = 'hello'; /* </script> */",
};

const loadScript = vi.fn();
const requestToken = vi.fn();
vi.mock("@/lib/customPage/client", () => ({
  loadScript: (...args: unknown[]) => loadScript(...args),
  requestToken: (...args: unknown[]) => requestToken(...args),
}));

/** The channels the component opened, so a test can play the frame's part. */
const channels: { options: HostChannelOptions; dispose: ReturnType<typeof vi.fn>; setTheme: ReturnType<typeof vi.fn> }[] =
  [];
vi.mock("@/lib/customPage/hostChannel.ts", () => ({
  openHostChannel: (options: HostChannelOptions) => {
    const channel = { options, dispose: vi.fn(), setTheme: vi.fn() };
    channels.push(channel);
    return channel;
  },
}));

let restoreLog: () => void;
const logged: string[] = [];

beforeEach(() => {
  channels.length = 0;
  logged.length = 0;
  push.mockReset();
  loadScript.mockReset();
  requestToken.mockReset();
  loadScript.mockResolvedValue({ ok: true, value: SCRIPT });
  restoreLog = setLogSink((line) => logged.push(line));
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const path = new URL(request.url).pathname;
      if (path === "/api/v1/auth/me") {
        return json({ data: { id: "user-1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities: [] } });
      }
      return new Response(null, { status: 204 });
    }),
  );
});

afterEach(() => {
  restoreLog();
  vi.unstubAllGlobals();
});

function renderFrame(assetId?: string) {
  return render(
    <SessionProvider>
      <CustomPageFrame customPage="Inspection__c" label="Inspection" assetId={assetId} />
    </SessionProvider>,
  );
}

describe("a custom page", () => {
  it("is put in a frame that can run scripts and nothing else", async () => {
    renderFrame();

    const frame = await screen.findByTitle("Inspection");

    expect(frame.getAttribute("sandbox")).toBe(FRAME_SANDBOX);
    expect(frame.getAttribute("sandbox")).toBe("allow-scripts");
    expect(frame.getAttribute("referrerpolicy")).toBe("no-referrer");
    expect(frame.hasAttribute("src")).toBe(false);
  });

  it("gives the frame its document only after the host is listening, with the script as data", async () => {
    renderFrame();
    const frame = (await screen.findByTitle("Inspection")) as HTMLIFrameElement;

    await waitFor(() => expect(channels).toHaveLength(1));

    expect(frame.srcdoc).toContain("Content-Security-Policy");
    expect(frame.srcdoc).toContain(JSON.stringify(SCRIPT.code).replace(/</g, "\\u003c").replace(/>/g, "\\u003e"));
    expect(frame.srcdoc.match(/<\/script>/g)).toHaveLength(2);
  });

  it("asks the host for the page, the person and the connection, and for a token for this page only", async () => {
    requestToken.mockResolvedValue({ token: "t", expiresAt: "2026-10-15T10:05:00Z" });
    renderFrame("11111111-2222-3333-4444-555555555555");
    await waitFor(() => expect(channels).toHaveLength(1));
    const { options } = channels[0]!;

    expect(loadScript).toHaveBeenCalledWith("Inspection__c", "11111111-2222-3333-4444-555555555555");
    expect(options.context).toMatchObject({ page: { apiName: "Inspection__c", label: "Inspection" }, user: { id: "user-1" } });
    expect(options.hostWindow).toBe(window);
    await options.requestToken();
    expect(requestToken).toHaveBeenCalledWith("Inspection__c");
  });

  it("is hidden and shows that it is loading until the page has connected", async () => {
    renderFrame();
    const frame = await screen.findByTitle("Inspection");
    await waitFor(() => expect(channels).toHaveLength(1));

    expect(frame.className).toContain("custom-page-frame-hidden");
    expect(screen.getByRole("status")).toHaveTextContent("Loading Inspection");

    act(() => channels[0]!.options.onConnected?.());

    await waitFor(() => expect(screen.getByTitle("Inspection").className).not.toContain("hidden"));
    expect(screen.queryByText("Loading Inspection")).toBeNull();
    expect(screen.getByTestId("custom-page")).toHaveAttribute("data-state", "running");
  });

  it("opens a place the page asks for inside the organization", async () => {
    renderFrame();
    await waitFor(() => expect(channels).toHaveLength(1));

    channels[0]!.options.onNavigate?.("/apps/Sales__c");

    expect(push).toHaveBeenCalledWith("/apps/Sales__c");
  });

  it("offers to try again when the page did not start, and starts over with a new frame", async () => {
    renderFrame();
    await waitFor(() => expect(channels).toHaveLength(1));

    act(() => channels[0]!.options.onProblem?.("timeout"));

    expect(await screen.findByText("The page did not start. Try again.")).toBeInTheDocument();
    expect(channels[0]!.dispose).toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    await waitFor(() => expect(channels).toHaveLength(2));
    expect(loadScript).toHaveBeenCalledTimes(2);
  });

  it("says when the page could not be given access to the member's data", async () => {
    renderFrame();
    await waitFor(() => expect(channels).toHaveLength(1));

    act(() => channels[0]!.options.onProblem?.("token"));

    expect(await screen.findByText(/could not be given access to your data/)).toBeInTheDocument();
  });

  it("keeps showing a page whose script failed, with a note, and logs the kind of failure only", async () => {
    renderFrame();
    await waitFor(() => expect(channels).toHaveLength(1));
    act(() => channels[0]!.options.onConnected?.());

    act(() => channels[0]!.options.onProblem?.("script"));

    expect(await screen.findByText("This page ran into an error. What you see may be incomplete.")).toBeInTheDocument();
    expect(screen.getByTitle("Inspection")).toBeInTheDocument();
    const line = logged.find((entry) => entry.includes("custom page reported a problem")) ?? "";
    expect(JSON.parse(line)).toMatchObject({ custom_page: "Inspection__c", problem: "script" });
  });

  it("shows the API's own words when the script cannot be had, and offers no retry for a page that does not exist", async () => {
    loadScript.mockResolvedValue({
      ok: false,
      failure: { code: "NOT_FOUND", message: "This page does not exist.", status: 404 },
    });
    renderFrame();

    expect(await screen.findByText("This page does not exist.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
    expect(screen.queryByTitle("Inspection")).toBeNull();
    expect(channels).toHaveLength(0);
  });

  it("offers a retry when the script is only temporarily out of reach", async () => {
    loadScript.mockResolvedValueOnce({
      ok: false,
      failure: { code: "SERVICE_UNAVAILABLE", message: "The service is temporarily unavailable.", status: 503 },
    });
    renderFrame();

    fireEvent.click(await screen.findByRole("button", { name: "Try again" }));

    expect(await screen.findByTitle("Inspection")).toBeInTheDocument();
  });

  it("follows a change of the colour scheme", async () => {
    const listeners: (() => void)[] = [];
    const query = {
      matches: false,
      addEventListener: (_: string, listener: () => void) => listeners.push(listener),
      removeEventListener: vi.fn(),
    };
    vi.stubGlobal("matchMedia", () => query);
    window.matchMedia = (() => query) as unknown as typeof window.matchMedia;
    renderFrame();
    await waitFor(() => expect(channels).toHaveLength(1));

    query.matches = true;
    listeners.forEach((listener) => listener());

    expect(channels[0]!.setTheme).toHaveBeenCalledWith("dark", expect.any(Object));
  });

  it("closes the channel when the page is left", async () => {
    const { unmount } = renderFrame();
    await waitFor(() => expect(channels).toHaveLength(1));

    unmount();

    expect(channels[0]!.dispose).toHaveBeenCalled();
  });
});
