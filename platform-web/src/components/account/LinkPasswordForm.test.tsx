import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { LinkPasswordForm, type LinkPasswordKind } from "./LinkPasswordForm";

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

function fakeApi(answer: () => Response | Promise<Response>) {
  const calls: Array<{ key: string; body: string }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      if (key === "GET /api/v1/auth/csrf") {
        return new Response(null, { status: 204 });
      }
      if (key === "GET /api/v1/auth/me" || key === "POST /api/v1/auth/refresh") {
        return json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);
      }
      calls.push({ key, body: await request.clone().text() });
      return answer();
    }),
  );
  return calls;
}

function open(kind: LinkPasswordKind, withToken = true) {
  window.history.replaceState(null, "", `/x${withToken ? `#token=${TOKEN}` : ""}`);
  return render(
    <SessionProvider>
      <LinkPasswordForm kind={kind} />
    </SessionProvider>,
  );
}

function fillAndSubmit(fields: { name?: string; password: string; confirm?: string }) {
  if (fields.name !== undefined) {
    fireEvent.change(screen.getByLabelText("Your name"), { target: { value: fields.name } });
  }
  fireEvent.change(screen.getByLabelText(/^(New p|P)assword$/), { target: { value: fields.password } });
  fireEvent.change(screen.getByLabelText("Repeat the password"), {
    target: { value: fields.confirm ?? fields.password },
  });
  fireEvent.submit(screen.getByLabelText("Repeat the password").closest("form")!);
}

describe("LinkPasswordForm", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
    window.history.replaceState(null, "", "/");
  });

  it("takes the token from the address, removes it from the address bar and sends it with the choices", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    open("sign-up");
    await screen.findByLabelText("Your name");
    expect(window.location.hash).toBe("");

    fillAndSubmit({ name: "Person A", password: "a long passphrase here" });

    expect(await screen.findByTestId("link-done")).toHaveTextContent("Your account is ready");
    expect(calls).toEqual([
      {
        key: "POST /api/v1/auth/sign-up/complete",
        body: JSON.stringify({ token: TOKEN, displayName: "Person A", password: "a long passphrase here" }),
      },
    ]);
  });

  it("still has the token when the page runs its effects twice, as a development build does", async () => {
    fakeApi(() => new Response(null, { status: 204 }));
    window.history.replaceState(null, "", `/x#token=${TOKEN}`);
    render(
      <StrictMode>
        <SessionProvider>
          <LinkPasswordForm kind="reset" />
        </SessionProvider>
      </StrictMode>,
    );

    expect(await screen.findByLabelText("New password")).toBeInTheDocument();
    expect(screen.queryByTestId("link-invalid")).toBeNull();
  });

  it("sends the new password of a reset and says every device was signed out", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    open("reset");
    await screen.findByLabelText("New password");

    fillAndSubmit({ password: "another long passphrase" });

    expect(await screen.findByTestId("link-done")).toHaveTextContent("every device was signed out");
    expect(calls[0]).toEqual({
      key: "POST /api/v1/auth/password/reset",
      body: JSON.stringify({ token: TOKEN, newPassword: "another long passphrase" }),
    });
  });

  it("does not send anything when the two passwords differ", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    open("reset");
    await screen.findByLabelText("New password");

    fillAndSubmit({ password: "a long passphrase here", confirm: "a different one entirely" });

    expect(screen.getByTestId("link-message")).toHaveTextContent("not the same");
    expect(calls).toHaveLength(0);
  });

  it("offers a new link when the address carries no token", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    open("reset", false);

    expect(await screen.findByTestId("link-invalid")).toHaveTextContent("not valid or has expired");
    expect(screen.getByRole("link", { name: "Ask for a new link" })).toHaveAttribute("href", "/forgot-password");
    expect(calls).toHaveLength(0);
  });

  it("shows the invalid-link text when the API refuses the token", async () => {
    fakeApi(() => invalid("token", "This link is not valid or has expired."));
    open("sign-up");
    await screen.findByLabelText("Your name");

    fillAndSubmit({ name: "Person A", password: "a long passphrase here" });

    expect(await screen.findByTestId("link-invalid")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Start again" })).toHaveAttribute("href", "/sign-up");
  });

  it("shows the API's words for a password it refuses and keeps the form", async () => {
    fakeApi(() => invalid("newPassword", "Must have at least 12 characters."));
    open("reset");
    await screen.findByLabelText("New password");

    fillAndSubmit({ password: "short" });

    await waitFor(() => expect(screen.getByTestId("link-message")).toHaveTextContent("at least 12 characters"));
    expect(screen.getByLabelText("New password")).toBeInTheDocument();
  });

  it("says so when there are too many attempts", async () => {
    fakeApi(() => json({ error: { code: "RATE_LIMITED", message: "Too many requests. Try again later." } }, 429));
    open("reset");
    await screen.findByLabelText("New password");

    fillAndSubmit({ password: "a long passphrase here" });

    await waitFor(() => expect(screen.getByTestId("link-message")).toHaveTextContent("Too many requests"));
  });
});
