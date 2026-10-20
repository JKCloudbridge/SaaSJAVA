import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, refusal, type Handler } from "@/test/fakeApi";
import { shellApi, ShellProviders } from "@/test/shellApi";
import { ApprovalsLink } from "./ApprovalsLink";

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

function organizationApi(summary: Handler, platformHost = false) {
  shellApi({ platformHost, more: { "GET /api/v1/approvals/inbox/summary": summary } });
}

function renderLink() {
  return render(
    <ShellProviders>
      <ApprovalsLink />
    </ShellProviders>,
  );
}

/** The summary is asked for once the sign-in is known, so a link that is absent means something only after that. */
async function settled() {
  await waitFor(() => {
    const paths = vi.mocked(globalThis.fetch).mock.calls.map(([request]) => new URL((request as Request).url).pathname);
    expect(paths).toContain("/api/v1/approvals/inbox/summary");
  });
  await new Promise((resolve) => setTimeout(resolve, 20));
}

describe("the approvals link of the header", () => {
  it("is offered, with the number of tasks that wait, when the API says the member has approvals", async () => {
    organizationApi(() => json({ data: { openTasks: 3, available: true } }));

    renderLink();

    expect(await screen.findByRole("link", { name: /Approvals/ })).toHaveAttribute("href", "/approvals");
    expect(await screen.findByTestId("approvals-count")).toHaveTextContent("3");
  });

  it("shows no count when nothing waits", async () => {
    organizationApi(() => json({ data: { openTasks: 0, available: true } }));

    renderLink();

    expect(await screen.findByRole("link", { name: /Approvals/ })).toBeInTheDocument();
    expect(screen.queryByTestId("approvals-count")).not.toBeInTheDocument();
  });

  it("is not offered when the API says there is nothing for the member", async () => {
    organizationApi(() => json({ data: { openTasks: 0, available: false } }));

    renderLink();
    await settled();

    expect(screen.queryByRole("link", { name: /Approvals/ })).not.toBeInTheDocument();
  });

  it("is not offered when it cannot be asked, and shows no error", async () => {
    organizationApi(() => refusal("FORBIDDEN", "No.", 403));

    renderLink();
    await settled();

    expect(screen.queryByRole("link", { name: /Approvals/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("is not offered on the platform's address, where there are no approvals", async () => {
    organizationApi(() => json({ data: { openTasks: 5, available: true } }), true);

    renderLink();
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 30));

    expect(screen.queryByRole("link", { name: /Approvals/ })).not.toBeInTheDocument();
  });
});
