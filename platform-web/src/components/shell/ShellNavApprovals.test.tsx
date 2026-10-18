import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { ShellNav } from "./ShellNav";

vi.mock("next/navigation", () => ({ usePathname: () => "/members" }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

afterEach(() => {
  vi.unstubAllGlobals();
});

function organizationApi(summary: () => Response) {
  fakeApi({
    "GET /api/v1/tenant/current": () => json({ data: { slug: "org-a", displayName: "Organization A", status: "ACTIVE" } }),
    "GET /api/v1/auth/me": () =>
      json({ data: { id: "1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities: [] } }),
    "GET /api/v1/approvals/inbox/summary": summary,
  });
}

function renderNav() {
  return render(
    <SessionProvider>
      <ShellNav />
    </SessionProvider>,
  );
}

describe("the approvals link of the navigation", () => {
  it("is offered, with the number of tasks that wait, when the API says the member has to do with approvals", async () => {
    organizationApi(() => json({ data: { openTasks: 3, available: true } }));

    renderNav();

    expect(await screen.findByRole("link", { name: /Approvals/ })).toHaveAttribute("href", "/approvals");
    expect(await screen.findByTestId("approvals-count")).toHaveTextContent("3");
  });

  it("shows no count when nothing waits", async () => {
    organizationApi(() => json({ data: { openTasks: 0, available: true } }));

    renderNav();

    expect(await screen.findByRole("link", { name: /Approvals/ })).toBeInTheDocument();
    expect(screen.queryByTestId("approvals-count")).not.toBeInTheDocument();
  });

  it("is not offered when the API says there is nothing for the member, and not when it cannot be asked", async () => {
    organizationApi(() => json({ data: { openTasks: 0, available: false } }));
    const first = renderNav();
    await screen.findByRole("link", { name: "Members" });
    expect(screen.queryByRole("link", { name: /Approvals/ })).not.toBeInTheDocument();
    first.unmount();

    organizationApi(() => refusal("FORBIDDEN", "No.", 403));
    renderNav();
    await screen.findByRole("link", { name: "Members" });
    expect(screen.queryByRole("link", { name: /Approvals/ })).not.toBeInTheDocument();
  });
});
