import { render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as navigation from "@/lib/navigation";
import { SwitchArrival } from "./SwitchArrival";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abc";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function fakeApi(answer: () => Response) {
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      if (key === "GET /api/v1/auth/csrf") {
        return new Response(null, { status: 204 });
      }
      calls.push(`${key} ${await request.clone().text()}`);
      return answer();
    }),
  );
  return calls;
}

describe("SwitchArrival", () => {
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
    navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
    window.history.replaceState(null, "", "/");
  });

  it("hands the proof to the API once, removes it from the address bar and follows the sign-in navigation", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    window.history.replaceState(null, "", `/switch#token=${TOKEN}`);

    render(<SwitchArrival />);

    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/api/v1/auth/start?continue=%2F"));
    expect(window.location.hash).toBe("");
    expect(calls).toEqual([`POST /api/v1/auth/switch/complete ${JSON.stringify({ token: TOKEN })}`]);
  });

  it("sends the proof only once when the page runs its effects twice, as development does", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    window.history.replaceState(null, "", `/switch#token=${TOKEN}`);

    render(
      <StrictMode>
        <SwitchArrival />
      </StrictMode>,
    );

    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    expect(calls).toHaveLength(1);
    expect(screen.queryByTestId("switch-failed")).toBeNull();
  });

  it("says the link is not valid when the API refuses the proof, and does not navigate", async () => {
    fakeApi(() =>
      json(
        { error: { code: "VALIDATION_ERROR", message: "x", fields: { token: ["This link is not valid or has expired."] } } },
        400,
      ),
    );
    window.history.replaceState(null, "", `/switch#token=${TOKEN}`);

    render(<SwitchArrival />);

    expect(await screen.findByTestId("switch-failed")).toHaveTextContent("not valid or has expired");
    expect(navigate).not.toHaveBeenCalled();
  });

  it("says so, without calling the API, when the address carries no proof", async () => {
    const calls = fakeApi(() => new Response(null, { status: 204 }));
    window.history.replaceState(null, "", "/switch");

    render(<SwitchArrival />);

    expect(await screen.findByTestId("switch-failed")).toBeInTheDocument();
    expect(calls).toEqual([]);
  });
});
