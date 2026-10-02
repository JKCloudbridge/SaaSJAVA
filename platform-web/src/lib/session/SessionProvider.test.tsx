import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UserMenu } from "@/components/shell/UserMenu";
import { setLogSink } from "@/lib/log";
import * as navigation from "@/lib/navigation";
import { fetchSession, SessionProvider } from "./SessionProvider";

const user = { id: "u1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities: [] };

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const unauthenticated = () => json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);

type Handler = (request: Request) => Response | Promise<Response>;

/** A fake API: routes by "METHOD path" and records every call. */
function fakeApi(routes: Record<string, Handler>) {
  const calls: string[] = [];
  const fetchMock = vi.fn(async (request: Request) => {
    const key = `${request.method} ${new URL(request.url).pathname}`;
    calls.push(key);
    const handler = routes[key];
    if (!handler) {
      throw new Error(`unexpected call ${key}`);
    }
    return handler(request);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls, fetchMock };
}

describe("fetchSession", () => {
  let restoreLog: () => void;
  beforeEach(() => {
    restoreLog = setLogSink(() => {});
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });
  afterEach(() => {
    restoreLog();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("reports the signed-in user the API names", async () => {
    fakeApi({ "GET /api/v1/auth/me": () => json({ data: user }) });

    expect(await fetchSession()).toEqual({ status: "signedIn", user });
  });

  it("replaces an expired access token once through the refresh cookie before giving up", async () => {
    let meCalls = 0;
    const api = fakeApi({
      "GET /api/v1/auth/me": () => (++meCalls === 1 ? unauthenticated() : json({ data: user })),
      "POST /api/v1/auth/refresh": () => new Response(null, { status: 204 }),
    });

    expect(await fetchSession()).toEqual({ status: "signedIn", user });
    expect(api.calls).toEqual(["GET /api/v1/auth/me", "POST /api/v1/auth/refresh", "GET /api/v1/auth/me"]);
  });

  it("is signed out when the refresh is refused too", async () => {
    fakeApi({
      "GET /api/v1/auth/me": unauthenticated,
      "POST /api/v1/auth/refresh": unauthenticated,
    });

    expect(await fetchSession()).toEqual({ status: "signedOut" });
  });

  it("says it could not tell, rather than signed out, when the server fails", async () => {
    fakeApi({ "GET /api/v1/auth/me": () => json({ error: { code: "SERVICE_UNAVAILABLE", message: "x" } }, 503) });

    expect(await fetchSession()).toEqual({ status: "unavailable" });
  });

  it("says it could not tell when there is no answer at all", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("network down");
      }),
    );

    expect(await fetchSession()).toEqual({ status: "unavailable" });
  });
});

describe("UserMenu with the session", () => {
  let restoreLog: () => void;
  beforeEach(() => {
    restoreLog = setLogSink(() => {});
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });
  afterEach(() => {
    restoreLog();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  function renderMenu() {
    return render(
      <SessionProvider>
        <UserMenu />
      </SessionProvider>,
    );
  }

  it("shows the name of the signed-in user and a way to sign out", async () => {
    fakeApi({ "GET /api/v1/auth/me": () => json({ data: user }) });

    renderMenu();

    expect(await screen.findByTestId("user")).toHaveTextContent("User A");
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    expect(screen.queryByText("Not signed in")).not.toBeInTheDocument();
  });

  it("shows a way in when nobody is signed in", async () => {
    fakeApi({ "GET /api/v1/auth/me": unauthenticated, "POST /api/v1/auth/refresh": unauthenticated });

    renderMenu();

    expect(await screen.findByText("Not signed in")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/sign-in");
  });

  it("says so, and does not claim to be signed out, when the check fails", async () => {
    fakeApi({ "GET /api/v1/auth/me": () => json({ error: { code: "INTERNAL_ERROR", message: "x" } }, 500) });

    renderMenu();

    expect(await screen.findByText("Sign-in status could not be checked")).toBeInTheDocument();
    expect(screen.queryByText("Not signed in")).not.toBeInTheDocument();
  });

  it("signs out through the API, with the forgery header, and goes to the sign-in page", async () => {
    const navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => {});
    let signOutRequest: Request | undefined;
    fakeApi({
      "GET /api/v1/auth/me": () => json({ data: user }),
      "POST /api/v1/auth/sign-out": (request) => {
        signOutRequest = request;
        return new Response(null, { status: 204 });
      },
    });
    renderMenu();

    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/sign-in"));
    expect(signOutRequest?.headers.get("X-XSRF-TOKEN")).toBe("test-token");
    expect(await screen.findByText("Not signed in")).toBeInTheDocument();
  });

  it("still leaves for the sign-in page when the sign-out call itself fails", async () => {
    const navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => {});
    fakeApi({
      "GET /api/v1/auth/me": () => json({ data: user }),
      "POST /api/v1/auth/sign-out": () => {
        throw new TypeError("network down");
      },
    });
    renderMenu();

    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/sign-in"));
  });
});
