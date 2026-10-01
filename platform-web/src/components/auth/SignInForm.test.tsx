import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLogSink } from "@/lib/log";
import * as navigation from "@/lib/navigation";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { SignInForm } from "./SignInForm";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const signedOut = () => json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);

type Handler = (request: Request) => Response | Promise<Response>;

function fakeApi(signIn: Handler) {
  const calls: Array<{ key: string; request: Request }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      calls.push({ key, request: request.clone() });
      switch (key) {
        case "GET /api/v1/auth/me":
        case "POST /api/v1/auth/refresh":
          return signedOut();
        case "GET /api/v1/auth/csrf":
          return new Response(null, { status: 204 });
        case "POST /api/v1/auth/sign-in":
          return signIn(request);
        default:
          throw new Error(`unexpected call ${key}`);
      }
    }),
  );
  return calls;
}

function renderForm(returnTo = "/", problem?: string) {
  return render(
    <SessionProvider>
      <SignInForm returnTo={returnTo} problem={problem} />
    </SessionProvider>,
  );
}

function fill(email: string, password: string) {
  fireEvent.change(screen.getByLabelText("Email address"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: password } });
}

describe("SignInForm", () => {
  let restoreLog: () => void;
  let lines: string[];
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    lines = [];
    restoreLog = setLogSink((line) => lines.push(line));
    navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => {});
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    restoreLog();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("sends the address and the password with the forgery header, then follows the API to finish the sign-in", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    renderForm("/reports?tab=open");
    await screen.findByRole("button", { name: "Sign in" });
    fill("user-a@example.test", "a long passphrase here");

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith("/api/v1/auth/start?continue=%2Freports%3Ftab%3Dopen"),
    );
    const signIn = calls.find((call) => call.key === "POST /api/v1/auth/sign-in")!;
    expect(signIn.request.headers.get("X-XSRF-TOKEN")).toBe("test-token");
    expect(await signIn.request.json()).toEqual({ email: "user-a@example.test", password: "a long passphrase here" });
  });

  it("fetches the forgery cookie first when the page does not hold one yet", async () => {
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    renderForm();
    await screen.findByRole("button", { name: "Sign in" });
    fill("user-a@example.test", "a long passphrase here");

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(navigate).toHaveBeenCalled());
    const keys = calls.map((call) => call.key);
    expect(keys.indexOf("GET /api/v1/auth/csrf")).toBeLessThan(keys.indexOf("POST /api/v1/auth/sign-in"));
  });

  it("shows one message for a wrong address or password and clears the password", async () => {
    fakeApi(() => json({ error: { code: "UNAUTHENTICATED", message: "x", requestId: "req_1" } }, 401));
    renderForm();
    await screen.findByRole("button", { name: "Sign in" });
    fill("user-a@example.test", "not the password at all");

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The email address or the password is not correct.");
    expect(screen.getByLabelText("Password")).toHaveValue("");
    expect(screen.getByLabelText("Email address")).toHaveValue("user-a@example.test");
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("says to wait when there have been too many attempts", async () => {
    fakeApi(() => json({ error: { code: "RATE_LIMITED", message: "x" } }, 429));
    renderForm();
    await screen.findByRole("button", { name: "Sign in" });
    fill("user-a@example.test", "a long passphrase here");

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Too many attempts");
  });

  it("says when sign-in is not available and when the server cannot be reached", async () => {
    fakeApi(() => json({ error: { code: "SERVICE_UNAVAILABLE", message: "x" } }, 503));
    renderForm();
    await screen.findByRole("button", { name: "Sign in" });
    fill("user-a@example.test", "a long passphrase here");

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("not available right now");

    fakeApi(() => {
      throw new TypeError("network down");
    });
    fill("user-a@example.test", "a long passphrase here");
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("could not be reached"));
  });

  it("explains a failed return from the sign-in flow without blaming the password", async () => {
    fakeApi(() => new Response(null, { status: 204 }));

    renderForm("/", "sign-in");

    expect(await screen.findByRole("alert")).toHaveTextContent("could not be completed");
  });

  it("never writes the password or the address into a log line", async () => {
    fakeApi(() => json({ error: { code: "UNAUTHENTICATED", message: "x" } }, 401));
    renderForm();
    await screen.findByRole("button", { name: "Sign in" });
    fill("user-a@example.test", "the-distinctive-password-9917");

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByRole("alert");

    expect(lines.join("\n")).not.toContain("the-distinctive-password-9917");
    expect(lines.join("\n")).not.toContain("user-a@example.test");
  });

  it("does not send a second request while the first is running", async () => {
    let release: (response: Response) => void = () => {};
    const calls = fakeApi(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve;
        }),
    );
    renderForm();
    await screen.findByRole("button", { name: "Sign in" });
    fill("user-a@example.test", "a long passphrase here");

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Signing in…" })).toBeDisabled());
    fireEvent.submit(screen.getByRole("button", { name: "Signing in…" }).closest("form")!);
    release(new Response(null, { status: 204 }));

    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    expect(calls.filter((call) => call.key === "POST /api/v1/auth/sign-in")).toHaveLength(1);
  });

  it("uses a password field that password managers recognise and does not offer a tenant choice", async () => {
    fakeApi(() => new Response(null, { status: 204 }));
    renderForm();

    const password = await screen.findByLabelText("Password");

    expect(password).toHaveAttribute("type", "password");
    expect(password).toHaveAttribute("autocomplete", "current-password");
    expect(screen.getByLabelText("Email address")).toHaveAttribute("autocomplete", "username");
    expect(screen.queryByLabelText(/organi[sz]ation|tenant/i)).not.toBeInTheDocument();
  });
});
