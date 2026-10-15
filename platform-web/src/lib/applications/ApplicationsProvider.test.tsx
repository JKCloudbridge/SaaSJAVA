import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { setLogSink } from "@/lib/log";
import { FIELD, SALES, shellApi, ShellProviders } from "@/test/shellApi";
import { json, refusal } from "@/test/fakeApi";
import { STALE_AFTER_MS, useApplications } from "./ApplicationsProvider";

function Probe() {
  const { enabled, state, refresh } = useApplications();
  return (
    <div>
      <p data-testid="enabled">{String(enabled)}</p>
      <p data-testid="status">{state.status}</p>
      <p data-testid="names">{state.applications.map((application) => application.label).join(",")}</p>
      <p data-testid="message">{state.status === "error" ? state.message : ""}</p>
      <button type="button" onClick={() => void refresh()}>
        refresh
      </button>
    </div>
  );
}

function renderProbe() {
  return render(
    <ShellProviders>
      <Probe />
    </ShellProviders>,
  );
}

const asked = (calls: { key: string }[]) => calls.filter((call) => call.key === "GET /api/v1/applications").length;

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("the member's applications", () => {
  it("are asked for once a member is signed in on an organization's address", async () => {
    const calls = shellApi();
    renderProbe();

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("ready"));

    expect(screen.getByTestId("enabled")).toHaveTextContent("true");
    expect(screen.getByTestId("names")).toHaveTextContent("Field Service,Sales");
    expect(asked(calls)).toBe(1);
  });

  it("are not asked for on the platform's address, where there is no member", async () => {
    const calls = shellApi({ platformHost: true });
    renderProbe();

    await waitFor(() => expect(calls.some((call) => call.key === "GET /api/v1/tenant/current")).toBe(true));
    await waitFor(() => expect(calls.some((call) => call.key === "GET /api/v1/auth/me")).toBe(true));

    expect(screen.getByTestId("enabled")).toHaveTextContent("false");
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    expect(asked(calls)).toBe(0);
  });

  it("are not asked for without a sign-in", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => {
        const path = new URL(request.url).pathname;
        if (path === "/api/v1/tenant/current") {
          return json({ data: { slug: "org-a", displayName: "Organization A", status: "ACTIVE" } });
        }
        return refusal("UNAUTHENTICATED", "Authentication is required.", 401);
      }),
    );
    renderProbe();

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(screen.getByTestId("enabled")).toHaveTextContent("false");
    expect(screen.getByTestId("names")).toHaveTextContent("");
  });

  it("keep what was shown when asking again fails, and say so", async () => {
    const restore = setLogSink(() => {});
    let healthy = true;
    shellApi({
      applicationsAnswer: () =>
        healthy ? json({ data: [FIELD] }) : refusal("SERVICE_UNAVAILABLE", "Try again later.", 503),
    });
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("ready"));

    healthy = false;
    await act(async () => {
      screen.getByRole("button", { name: "refresh" }).click();
    });

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("error"));
    expect(screen.getByTestId("names")).toHaveTextContent("Field Service");
    expect(screen.getByTestId("message")).toHaveTextContent("Your applications could not be loaded.");
    restore();
  });

  it("follow a change the next time they are asked for", async () => {
    let list = [FIELD];
    shellApi({ applicationsAnswer: () => json({ data: list }) });
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("names")).toHaveTextContent("Field Service"));

    list = [FIELD, SALES];
    await act(async () => {
      screen.getByRole("button", { name: "refresh" }).click();
    });

    await waitFor(() => expect(screen.getByTestId("names")).toHaveTextContent("Field Service,Sales"));
  });

  it("do not ask twice at once", async () => {
    const calls = shellApi();
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("ready"));

    await act(async () => {
      const button = screen.getByRole("button", { name: "refresh" });
      button.click();
      button.click();
      button.click();
    });

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("ready"));
    expect(asked(calls)).toBe(2);
  });

  it("are asked for again when the member returns to a page that has gone stale", async () => {
    const calls = shellApi();
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("ready"));
    const now = Date.now();
    vi.spyOn(Date, "now").mockReturnValue(now + STALE_AFTER_MS + 1);

    await act(async () => {
      Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
    });

    await waitFor(() => expect(asked(calls)).toBe(2));
    vi.restoreAllMocks();
  });

  it("are not asked for again when the member returns to a page that is still fresh", async () => {
    const calls = shellApi();
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("ready"));

    await act(async () => {
      Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(asked(calls)).toBe(1);
  });
});
