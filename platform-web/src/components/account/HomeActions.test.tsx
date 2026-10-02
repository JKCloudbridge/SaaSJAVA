import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { HomeActions } from "./HomeActions";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const UNAUTHENTICATED = { error: { code: "UNAUTHENTICATED", message: "Authentication is required." } };

function fakeApi(signedIn: boolean, platformHost: boolean) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      switch (key) {
        case "GET /api/v1/auth/csrf":
          return new Response(null, { status: 204 });
        case "GET /api/v1/auth/me":
          return signedIn
            ? json({ data: { id: "1", email: "user-a@example.test", displayName: "User A" } })
            : json(UNAUTHENTICATED, 401);
        case "POST /api/v1/auth/refresh":
          return json(UNAUTHENTICATED, 401);
        case "GET /api/v1/tenant/current":
          return platformHost
            ? json({ error: { code: "NOT_FOUND", message: "No organization exists at this address." } }, 404)
            : json({ data: { slug: "tenant-a", displayName: "Tenant A" } });
        default:
          throw new Error(`unexpected call ${key}`);
      }
    }),
  );
}

function renderActions() {
  return render(
    <SessionProvider>
      <HomeActions />
    </SessionProvider>,
  );
}

describe("HomeActions", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("offers to create an account to a signed-out visitor of the platform address", async () => {
    fakeApi(false, true);
    renderActions();

    expect(await screen.findByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/sign-up");
  });

  it("offers to create an organization to a signed-in person on the platform address", async () => {
    fakeApi(true, true);
    renderActions();

    expect(await screen.findByRole("link", { name: "Create an organization" })).toHaveAttribute(
      "href",
      "/organizations/new",
    );
  });

  it("offers nothing on an organization's address", async () => {
    fakeApi(false, false);
    renderActions();

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("home-actions")).toBeNull();
  });
});
