import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { refusal } from "@/test/fakeApi";
import { shellApi } from "@/test/shellApi";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { UserMenu } from "./UserMenu";

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

function renderMenu() {
  return render(
    <SessionProvider>
      <UserMenu />
    </SessionProvider>,
  );
}

describe("UserMenu and creating an organization", () => {
  it("offers 'Create an organization' to a signed-in person on the platform's address", async () => {
    shellApi({ platformHost: true });
    renderMenu();

    expect(await screen.findByRole("link", { name: "Create an organization" })).toHaveAttribute(
      "href",
      "/organizations/new",
    );
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
  });

  it("offers it on no organization's address", async () => {
    const calls = shellApi();
    renderMenu();

    expect(await screen.findByTestId("user")).toHaveTextContent("User A");
    await waitFor(() => expect(calls.some((call) => call.key === "GET /api/v1/tenant/current")).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole("link", { name: "Create an organization" })).toBeNull();
  });

  it("offers it to nobody who is not signed in", async () => {
    shellApi({
      platformHost: true,
      more: {
        "GET /api/v1/auth/me": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
        "POST /api/v1/auth/refresh": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
      },
    });
    renderMenu();

    expect(await screen.findByText("Not signed in")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Create an organization" })).toBeNull();
  });
});
