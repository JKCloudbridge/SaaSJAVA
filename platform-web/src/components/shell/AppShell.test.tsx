import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLogSink } from "@/lib/log";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { AppShell } from "./AppShell";

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** The API as an address with no organization and no signed-in caller would see it. */
function anonymousApi(request: Request): Response {
  const path = new URL(request.url).pathname;
  if (path === "/api/v1/tenant/current") {
    return json({ error: { code: "NOT_FOUND", message: "No organization exists." } }, 404);
  }
  if (path === "/api/v1/auth/csrf") {
    return new Response(null, { status: 204 });
  }
  return json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);
}

function renderShell(content: string | React.ReactNode) {
  return render(
    <SessionProvider>
      <AppShell>{content}</AppShell>
    </SessionProvider>,
  );
}

describe("AppShell", () => {
  let restoreLog: () => void;

  beforeEach(() => {
    restoreLog = setLogSink(() => {});
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) => anonymousApi(request)),
    );
  });

  afterEach(() => {
    restoreLog();
    vi.unstubAllGlobals();
  });

  it("has the landmarks of the application frame and renders its content", async () => {
    renderShell(<p>page content</p>);

    expect(screen.getByRole("banner")).toHaveTextContent("Platform");
    expect(screen.getByRole("navigation", { name: "Main" })).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveTextContent("page content");
    // The header and the navigation ask the API in the background: let them finish before the test ends.
    await screen.findByText("Not signed in");
  });

  it("offers a way past the navigation for keyboard users", async () => {
    renderShell("content");

    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
    await screen.findByText("Not signed in");
  });

  it("is honest that no organization or user is established when the API knows none", async () => {
    renderShell("content");

    expect(await screen.findByText("No organization selected")).toBeInTheDocument();
    expect(await screen.findByText("Not signed in")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/sign-in");
  });
});
