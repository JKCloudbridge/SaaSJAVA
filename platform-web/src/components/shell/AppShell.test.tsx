import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLogSink } from "@/lib/log";
import { AppShell } from "./AppShell";

describe("AppShell", () => {
  let restoreLog: () => void;

  beforeEach(() => {
    restoreLog = setLogSink(() => {});
    // The shell asks the API which organization this address belongs to; here the answer is "none".
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "No organization exists." } }), {
            status: 404,
            headers: { "Content-Type": "application/json" },
          }),
      ),
    );
  });

  afterEach(() => {
    restoreLog();
    vi.unstubAllGlobals();
  });

  it("has the landmarks of the application frame and renders its content", () => {
    render(
      <AppShell>
        <p>page content</p>
      </AppShell>,
    );

    expect(screen.getByRole("banner")).toHaveTextContent("Platform");
    expect(screen.getByRole("navigation", { name: "Main" })).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveTextContent("page content");
  });

  it("offers a way past the navigation for keyboard users", () => {
    render(<AppShell>content</AppShell>);

    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
  });

  it("is honest that no organization or user is established yet", async () => {
    render(<AppShell>content</AppShell>);

    expect(await screen.findByText("No organization selected")).toBeInTheDocument();
    expect(screen.getByText("Not signed in")).toBeInTheDocument();
  });
});
