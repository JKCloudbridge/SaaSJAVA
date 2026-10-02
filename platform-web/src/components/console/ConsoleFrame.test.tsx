import { render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLogSink } from "@/lib/log";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ConsoleFrame } from "./ConsoleFrame";

vi.mock("next/navigation", () => ({ usePathname: () => "/console" }));

const PERSON = { id: "u-1", email: "platform-a@example.test", displayName: "Platform A", abilities: [] };

function renderFrame(strict = false) {
  const tree = (
    <SessionProvider>
      <ConsoleFrame>
        <p>console content</p>
      </ConsoleFrame>
    </SessionProvider>
  );
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree);
}

describe("ConsoleFrame", () => {
  let restoreLog: () => void;

  beforeEach(() => {
    restoreLog = setLogSink(() => {});
  });

  afterEach(() => {
    restoreLog();
    vi.unstubAllGlobals();
  });

  it("shows the console to a person the API lists a platform role for, on the platform address", async () => {
    fakeApi({
      "GET /api/v1/tenant/current": () => refusal("NOT_FOUND", "No organization exists.", 404),
      "GET /api/v1/auth/me": () => json({ data: { ...PERSON, platformRoles: ["PLATFORM_ADMIN"] } }),
    });

    renderFrame();

    expect(await screen.findByText("console content")).toBeInTheDocument();
    expect(screen.getByTestId("console-roles")).toHaveTextContent("PLATFORM_ADMIN");
    expect(screen.getByRole("link", { name: "Set up an organization" })).toHaveAttribute(
      "href",
      "/console/organizations/new",
    );
  });

  it("does the same once under StrictMode, which runs effects twice in development", async () => {
    const calls = fakeApi({
      "GET /api/v1/tenant/current": () => refusal("NOT_FOUND", "No organization exists.", 404),
      "GET /api/v1/auth/me": () => json({ data: { ...PERSON, platformRoles: ["PLATFORM_SUPPORT"] } }),
    });

    renderFrame(true);

    expect(await screen.findByText("console content")).toBeInTheDocument();
    expect(calls.filter((call) => call.key === "GET /api/v1/auth/me").length).toBeGreaterThanOrEqual(1);
  });

  it("asks for a sign-in when nobody is signed in", async () => {
    fakeApi({
      "GET /api/v1/tenant/current": () => refusal("NOT_FOUND", "No organization exists.", 404),
      "GET /api/v1/auth/me": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
      "POST /api/v1/auth/refresh": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
    });

    renderFrame();

    expect(await screen.findByTestId("console-needs-sign-in")).toHaveTextContent("Sign in first");
    expect(screen.queryByText("console content")).toBeNull();
  });

  it("points an organization address to the platform address instead of showing the console", async () => {
    fakeApi({
      "GET /api/v1/tenant/current": () => json({ data: { slug: "tenant-a", displayName: "Tenant A" } }),
      "GET /api/v1/auth/me": () => json({ data: { ...PERSON, platformRoles: [] } }),
    });

    renderFrame();

    expect(await screen.findByTestId("console-wrong-host")).toHaveTextContent("platform address");
    expect(screen.queryByText("console content")).toBeNull();
  });

  it("says plainly that an account without a platform role has nothing here", async () => {
    fakeApi({
      "GET /api/v1/tenant/current": () => refusal("NOT_FOUND", "No organization exists.", 404),
      "GET /api/v1/auth/me": () => json({ data: { ...PERSON, platformRoles: [] } }),
    });

    renderFrame();

    expect(await screen.findByTestId("console-no-role")).toBeInTheDocument();
    expect(screen.queryByText("console content")).toBeNull();
  });
});
