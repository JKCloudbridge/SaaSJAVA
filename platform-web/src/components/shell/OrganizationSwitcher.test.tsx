import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as navigation from "@/lib/navigation";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { OrganizationSwitcher } from "./OrganizationSwitcher";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const ORGANIZATIONS = [
  { slug: "org-a", displayName: "Organization A", host: "org-a.localhost:3000", administrator: true },
  { slug: "org-b", displayName: "Organization B", host: "org-b.localhost:3000", administrator: false },
];

function fakeApi(options: { signedIn: boolean; organizations: unknown[]; switchAnswer?: () => Response }) {
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      calls.push(key);
      switch (key) {
        case "GET /api/v1/auth/csrf":
          return new Response(null, { status: 204 });
        case "GET /api/v1/auth/me":
          return options.signedIn
            ? json({ data: { id: "1", email: "user-a@example.test", displayName: "User A" } })
            : json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);
        case "POST /api/v1/auth/refresh":
          return json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);
        case "GET /api/v1/organizations":
          return json({ data: options.organizations });
        case "POST /api/v1/auth/switch":
          return (options.switchAnswer ?? (() => json({ data: { host: "org-b.localhost:3000", token: "proof-01234567890123456789" } })))();
        default:
          throw new Error(`unexpected call ${key}`);
      }
    }),
  );
  return calls;
}

function renderSwitcher() {
  return render(
    <SessionProvider>
      <OrganizationSwitcher />
    </SessionProvider>,
  );
}

describe("OrganizationSwitcher", () => {
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
    navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("lists the organizations the API reports when there is more than one", async () => {
    fakeApi({ signedIn: true, organizations: ORGANIZATIONS });

    renderSwitcher();

    const select = await screen.findByLabelText("Organization");
    expect(select).toHaveTextContent("Organization A");
    expect(select).toHaveTextContent("Organization B");
  });

  it("shows nothing to a person with a single organization or none, and nothing when signed out", async () => {
    const calls = fakeApi({ signedIn: true, organizations: [ORGANIZATIONS[0]] });
    const { unmount } = renderSwitcher();
    await waitFor(() => expect(calls).toContain("GET /api/v1/organizations"));
    expect(screen.queryByTestId("org-switcher")).toBeNull();
    unmount();

    const signedOutCalls = fakeApi({ signedIn: false, organizations: ORGANIZATIONS });
    renderSwitcher();
    await waitFor(() => expect(signedOutCalls).toContain("POST /api/v1/auth/refresh"));
    expect(screen.queryByTestId("org-switcher")).toBeNull();
    expect(signedOutCalls).not.toContain("GET /api/v1/organizations");
  });

  it("asks the API to move and opens the address the API gave, with the proof after the #", async () => {
    fakeApi({ signedIn: true, organizations: ORGANIZATIONS });
    renderSwitcher();

    fireEvent.change(await screen.findByLabelText("Organization"), { target: { value: "org-b" } });

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith(
        `${window.location.protocol}//org-b.localhost:3000/switch#token=proof-01234567890123456789`,
      ),
    );
  });

  it("shows the API's words when it refuses and does not navigate", async () => {
    fakeApi({
      signedIn: true,
      organizations: ORGANIZATIONS,
      switchAnswer: () => json({ error: { code: "NOT_FOUND", message: "That organization is not available." } }, 404),
    });
    renderSwitcher();

    fireEvent.change(await screen.findByLabelText("Organization"), { target: { value: "org-b" } });

    expect(await screen.findByRole("alert")).toHaveTextContent("That organization is not available.");
    expect(navigate).not.toHaveBeenCalled();
  });
});
