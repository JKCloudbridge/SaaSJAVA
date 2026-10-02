import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as navigation from "@/lib/navigation";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { InvitationAcceptance } from "./InvitationAcceptance";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abc";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function invalid(field: string, message: string) {
  return json(
    { error: { code: "VALIDATION_ERROR", message: "The request is invalid.", fields: { [field]: [message] } } },
    400,
  );
}

const preview = (existingAccount: boolean) =>
  json({ data: { organizationName: "Organization A", email: "user-a@example.test", existingAccount } });
const accepted = () => json({ data: { slug: "org-a", displayName: "Organization A", host: "org-a.localhost:3000" } });

interface Api {
  signedIn?: boolean;
  preview?: () => Response;
  acceptNew?: () => Response;
  accept?: () => Response;
}

function fakeApi(api: Api) {
  const calls: Array<{ key: string; body: string }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      if (key === "GET /api/v1/auth/csrf") {
        return new Response(null, { status: 204 });
      }
      if (key === "GET /api/v1/auth/me") {
        return api.signedIn
          ? json({ data: { id: "1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities: [] } })
          : json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);
      }
      if (key === "POST /api/v1/auth/refresh") {
        return json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);
      }
      calls.push({ key, body: await request.clone().text() });
      switch (key) {
        case "POST /api/v1/auth/invitations/preview":
          return (api.preview ?? (() => preview(false)))();
        case "POST /api/v1/auth/invitations/accept-new":
          return (api.acceptNew ?? accepted)();
        case "POST /api/v1/auth/invitations/accept":
          return (api.accept ?? accepted)();
        default:
          throw new Error(`unexpected call ${key}`);
      }
    }),
  );
  return calls;
}

function open(strict = false, withToken = true) {
  window.history.replaceState(null, "", `/invitations/accept${withToken ? `#token=${TOKEN}` : ""}`);
  const page = (
    <SessionProvider>
      <InvitationAcceptance />
    </SessionProvider>
  );
  return render(strict ? <StrictMode>{page}</StrictMode> : page);
}

describe("InvitationAcceptance", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
    window.history.replaceState(null, "", "/");
  });

  it("reads the token from the address, removes it from the address bar and shows what the API says it is for", async () => {
    const calls = fakeApi({});
    open();

    expect(await screen.findByTestId("invitation-new")).toHaveTextContent("Organization A");
    expect(screen.getByTestId("invitation-intro")).toHaveTextContent("user-a@example.test");
    expect(window.location.hash).toBe("");
    expect(calls).toEqual([{ key: "POST /api/v1/auth/invitations/preview", body: JSON.stringify({ token: TOKEN }) }]);
  });

  it("asks only for a password when the administrator already entered the person's name", async () => {
    const calls = fakeApi({
      preview: () =>
        json({
          data: { organizationName: "Organization A", email: "user-a@example.test", existingAccount: false, displayName: "Person A" },
        }),
    });
    open();
    await screen.findByTestId("invitation-new");

    expect(screen.getByTestId("invitation-name")).toHaveTextContent("Person A");
    expect(screen.queryByLabelText("Your name")).toBeNull();
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-long-and-unusual-passphrase-1" } });
    fireEvent.change(screen.getByLabelText("Repeat the password"), { target: { value: "a-long-and-unusual-passphrase-1" } });
    fireEvent.submit(screen.getByLabelText("Password").closest("form")!);

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/accept-new"))).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.endsWith("/accept-new"))!.body)).toEqual({
      token: TOKEN,
      password: "a-long-and-unusual-passphrase-1",
    });
  });

  it("still has the token, and asks the API only once, when the page runs its effects twice as development does", async () => {
    const calls = fakeApi({});
    open(true);

    expect(await screen.findByTestId("invitation-new")).toBeInTheDocument();
    expect(screen.queryByTestId("invitation-invalid")).toBeNull();
    expect(calls.filter((call) => call.key.endsWith("/preview"))).toHaveLength(1);
  });

  it("sends the chosen name and password for a person without an account and offers the organization's sign-in", async () => {
    const calls = fakeApi({});
    open();
    await screen.findByTestId("invitation-new");

    fireEvent.change(screen.getByLabelText("Your name"), { target: { value: "Person A" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a long passphrase here" } });
    fireEvent.change(screen.getByLabelText("Repeat the password"), { target: { value: "a long passphrase here" } });
    fireEvent.submit(screen.getByLabelText("Repeat the password").closest("form")!);

    expect(await screen.findByTestId("invitation-done")).toHaveTextContent("You joined Organization A");
    expect(screen.getByRole("link", { name: /Sign in to Organization A/ })).toHaveAttribute(
      "href",
      `${window.location.protocol}//org-a.localhost:3000/sign-in`,
    );
    expect(calls[1]).toEqual({
      key: "POST /api/v1/auth/invitations/accept-new",
      body: JSON.stringify({ token: TOKEN, displayName: "Person A", password: "a long passphrase here" }),
    });
  });

  it("shows the API's words for a weak password and keeps the form", async () => {
    fakeApi({ acceptNew: () => invalid("password", "Must be at least 12 characters.") });
    open();
    await screen.findByTestId("invitation-new");

    fireEvent.change(screen.getByLabelText("Your name"), { target: { value: "Person A" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "short" } });
    fireEvent.change(screen.getByLabelText("Repeat the password"), { target: { value: "short" } });
    fireEvent.submit(screen.getByLabelText("Repeat the password").closest("form")!);

    expect(await screen.findByTestId("invitation-message")).toHaveTextContent("Must be at least 12 characters.");
    expect(screen.getByLabelText("Your name")).toBeInTheDocument();
  });

  it("sends nothing when the two passwords differ", async () => {
    const calls = fakeApi({});
    open();
    await screen.findByTestId("invitation-new");

    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "one passphrase here" } });
    fireEvent.change(screen.getByLabelText("Repeat the password"), { target: { value: "another passphrase" } });
    fireEvent.submit(screen.getByLabelText("Repeat the password").closest("form")!);

    expect(await screen.findByTestId("invitation-message")).toHaveTextContent("not the same");
    expect(calls).toHaveLength(1);
  });

  it("lets a signed-in person with an account accept, without asking for a password", async () => {
    const calls = fakeApi({ signedIn: true, preview: () => preview(true) });
    open();

    expect(await screen.findByTestId("invitation-existing")).toHaveTextContent("Organization A");
    expect(screen.queryByLabelText("Password")).toBeNull();
    fireEvent.click(await screen.findByRole("button", { name: "Accept the invitation" }));

    expect(await screen.findByTestId("invitation-done")).toHaveTextContent("You joined Organization A");
    expect(calls[1]).toEqual({ key: "POST /api/v1/auth/invitations/accept", body: JSON.stringify({ token: TOKEN }) });
  });

  it("asks a person with an account who is not signed in to sign in first", async () => {
    fakeApi({ signedIn: false, preview: () => preview(true) });
    open();

    expect(await screen.findByTestId("invitation-existing")).toHaveTextContent("already has an account");
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/sign-in");
    expect(screen.queryByRole("button", { name: "Accept the invitation" })).toBeNull();
  });

  it("says the same thing for a link the API refuses, whatever the reason", async () => {
    fakeApi({ preview: () => invalid("token", "This link is not valid or has expired.") });
    open();

    expect(await screen.findByTestId("invitation-invalid")).toHaveTextContent("not valid or has expired");
  });

  it("says so when the address carries no token at all, without calling the API", async () => {
    const calls = fakeApi({});
    open(false, false);

    expect(await screen.findByTestId("invitation-invalid")).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it("explains a refusal of a signed-in person without revealing why", async () => {
    fakeApi({
      signedIn: true,
      preview: () => preview(true),
      accept: () => invalid("token", "This link is not valid or has expired."),
    });
    open();

    fireEvent.click(await screen.findByRole("button", { name: "Accept the invitation" }));

    await waitFor(() => expect(screen.getByTestId("invitation-message")).toHaveTextContent("different address"));
  });

  it("opens the organization through the switch after an existing person joined", async () => {
    const navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => {});
    const calls = fakeApi({ signedIn: true, preview: () => preview(true) });
    const original = globalThis.fetch;
    vi.stubGlobal("fetch", async (request: Request) =>
      new URL(request.url).pathname === "/api/v1/auth/switch"
        ? json({ data: { host: "org-a.localhost:3000", token: "proof-token-0123456789" } })
        : (original as (request: Request) => Promise<Response>)(request),
    );
    open();
    fireEvent.click(await screen.findByRole("button", { name: "Accept the invitation" }));
    fireEvent.click(await screen.findByRole("button", { name: "Open Organization A" }));

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith(
        `${window.location.protocol}//org-a.localhost:3000/switch#token=proof-token-0123456789`,
      ),
    );
    expect(calls.length).toBeGreaterThan(0);
  });
});
