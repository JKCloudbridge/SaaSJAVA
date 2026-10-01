import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLogSink } from "@/lib/log";
import { PlatformStatusCard } from "./PlatformStatusCard";

const okBody = {
  data: {
    service: "platform",
    apiVersion: "v1",
    serverTime: "2026-10-01T10:00:00Z",
    databaseTime: "2026-10-01T10:00:01Z",
  },
};

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  });
}

describe("PlatformStatusCard", () => {
  let restoreLog: () => void;

  beforeEach(() => {
    restoreLog = setLogSink(() => {});
  });

  afterEach(() => {
    restoreLog();
    vi.unstubAllGlobals();
  });

  it("shows what the API reports, with the request and trace ids to quote to support", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => json(okBody, { headers: { "X-Request-ID": "req_visible", "X-Trace-Id": "trace_visible" } })),
    );

    render(<PlatformStatusCard />);

    expect(await screen.findByText("Up")).toBeInTheDocument();
    expect(screen.getByText("platform")).toBeInTheDocument();
    expect(screen.getByText("v1")).toBeInTheDocument();
    expect(screen.getByText("req_visible")).toBeInTheDocument();
    expect(screen.getByText("trace_visible")).toBeInTheDocument();
  });

  it("shows the API's error code and message and the request id when the API says no", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        json(
          {
            error: {
              code: "SERVICE_UNAVAILABLE",
              message: "The service is temporarily unavailable.",
              requestId: "req_failed",
            },
          },
          { status: 503 },
        ),
      ),
    );

    render(<PlatformStatusCard />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The service is temporarily unavailable.");
    expect(alert).toHaveTextContent("SERVICE_UNAVAILABLE");
    expect(alert).toHaveTextContent("req_failed");
  });

  it("shows a calm message when the server cannot be reached at all", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );

    render(<PlatformStatusCard />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("NETWORK_ERROR");
    expect(alert).not.toHaveTextContent("Failed to fetch");
  });

  it("does not show a gateway page's content", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>502 bad gateway nginx</html>", { status: 502 })));

    render(<PlatformStatusCard />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("UNREADABLE_RESPONSE");
    expect(alert).not.toHaveTextContent("nginx");
  });

  it("asks again when the button is pressed", async () => {
    const fetchMock = vi.fn(async () => json(okBody));
    vi.stubGlobal("fetch", fetchMock);

    render(<PlatformStatusCard />);
    await screen.findByText("Up");
    fireEvent.click(screen.getByRole("button", { name: "Check again" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });

  it("calls the status path of the versioned API", async () => {
    const fetchMock = vi.fn(async (request: Request) => {
      void request;
      return json(okBody);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<PlatformStatusCard />);
    await screen.findByText("Up");

    const request = fetchMock.mock.calls[0]![0];
    expect(new URL(request.url).pathname).toBe("/api/v1/platform/status");
    expect(request.method).toBe("GET");
  });
});
