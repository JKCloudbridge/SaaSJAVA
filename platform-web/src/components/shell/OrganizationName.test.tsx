import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLogSink } from "@/lib/log";
import { OrganizationName } from "./OrganizationName";

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  });
}

function errorBody(code: string, message: string, status: number): Response {
  return json({ error: { code, message, requestId: "req_org" } }, { status });
}

describe("OrganizationName", () => {
  let restoreLog: () => void;

  beforeEach(() => {
    restoreLog = setLogSink(() => {});
  });

  afterEach(() => {
    restoreLog();
    vi.unstubAllGlobals();
  });

  it("shows the name of the organization the API reports for this address", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ data: { slug: "tenant-a", displayName: "Tenant A" } })));

    render(<OrganizationName />);

    expect(await screen.findByText("Tenant A")).toBeInTheDocument();
  });

  it("asks for the current organization without naming one", async () => {
    const fetchMock = vi.fn(async (request: Request) => {
      void request;
      return json({ data: { slug: "tenant-a", displayName: "Tenant A" } });
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<OrganizationName />);
    await screen.findByText("Tenant A");

    const request = fetchMock.mock.calls[0]![0];
    expect(new URL(request.url).pathname).toBe("/api/v1/tenant/current");
    expect(request.method).toBe("GET");
    expect(new URL(request.url).search).toBe("");
    const names = Array.from(request.headers.keys()).map((name) => name.toLowerCase());
    expect(names.filter((name) => name.includes("tenant"))).toEqual([]);
  });

  it("says no organization is selected when the address names none", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => errorBody("NOT_FOUND", "No organization exists at this address.", 404)));

    render(<OrganizationName />);

    expect(await screen.findByText("No organization selected")).toBeInTheDocument();
  });

  it("says so when the organization is not available, without saying why", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => errorBody("TENANT_UNAVAILABLE", "This organization is not available.", 403)),
    );

    render(<OrganizationName />);

    expect(await screen.findByText("This organization is not available")).toBeInTheDocument();
    expect(screen.queryByText(/suspended|deactivated/i)).not.toBeInTheDocument();
  });

  it("does not guess when something else goes wrong", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => errorBody("INTERNAL_ERROR", "An unexpected error occurred.", 500)));

    render(<OrganizationName />);

    expect(await screen.findByText("Organization unknown")).toBeInTheDocument();
  });

  it("copes with a server that cannot be reached and with answers that are not the error model", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    const { unmount } = render(<OrganizationName />);
    expect(await screen.findByText("Organization unknown")).toBeInTheDocument();
    unmount();

    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>502 bad gateway</html>", { status: 502 })));
    render(<OrganizationName />);
    expect(await screen.findByText("Organization unknown")).toBeInTheDocument();
  });

  it("shows a placeholder while the answer is on its way", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => {})));

    render(<OrganizationName />);

    expect(screen.getByText("Checking organization…")).toBeInTheDocument();
  });
});
