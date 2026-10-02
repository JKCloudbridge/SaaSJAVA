import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { ShellNav } from "./ShellNav";

vi.mock("next/navigation", () => ({ usePathname: () => "/members" }));

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** An organization address with a signed-in member whose abilities the API lists as given. */
function organizationApi(abilities: string[]) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const path = new URL(request.url).pathname;
      if (path === "/api/v1/tenant/current") {
        return json({ data: { slug: "org-a", displayName: "Organization A", status: "ACTIVE" } });
      }
      if (path === "/api/v1/auth/me") {
        return json({ data: { id: "1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities } });
      }
      return new Response(null, { status: 204 });
    }),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderNav() {
  return render(
    <SessionProvider>
      <ShellNav />
    </SessionProvider>,
  );
}

describe("ShellNav", () => {
  it("offers the setup pages to a member the API lists the ability to manage access for", async () => {
    organizationApi(["access.manage", "members.view"]);
    renderNav();

    expect(await screen.findByRole("link", { name: "Setup" })).toHaveAttribute("href", "/setup/profiles");
    expect(screen.getByRole("link", { name: "Members" })).toHaveAttribute("href", "/members");
  });

  it("does not offer the setup pages to a member without that ability, whatever the page would answer", async () => {
    organizationApi(["members.view"]);
    renderNav();

    expect(await screen.findByRole("link", { name: "Members" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
  });
});
