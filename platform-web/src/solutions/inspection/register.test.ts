import { afterEach, describe, expect, it, vi } from "vitest";
import { setScope } from "./scope";
import { FORGERY_COOKIE, FORGERY_HEADER } from "@/lib/api/forgery";
import {
  EMPTY_REGISTER_INPUT,
  REGISTER_LIMITS,
  looksLikeWorkbook,
  readRegisterFile,
  registerFileName,
} from "./register";

vi.mock("@/lib/session/SessionProvider", () => ({ ensureForgeryCookie: () => Promise.resolve() }));

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = `${FORGERY_COOKIE}=; path=/; max-age=0`;
});

describe("the register form's boxes (FA8)", () => {
  it("start empty and carry the bounds the server also checks", () => {
    expect(Object.values(EMPTY_REGISTER_INPUT).every((value) => value === "")).toBe(true);
    expect(REGISTER_LIMITS.requestNo).toBe(30);
    expect(REGISTER_LIMITS.changeRequested).toBe(4000);
    expect(REGISTER_LIMITS.signatureNote).toBe(500);
  });
});

describe("the name a register is saved under", () => {
  it("is the request number with anything odd made a hyphen", () => {
    expect(registerFileName("DCN-12")).toBe("DCN-12.xlsx");
    expect(registerFileName(" DCN/12 a ")).toBe("DCN-12-a.xlsx");
  });

  it("is a plain name when no number is typed yet, and never longer than forty characters before the extension", () => {
    expect(registerFileName("  ")).toBe("Change-request-register.xlsx");
    expect(registerFileName("9".repeat(80))).toBe(`${"9".repeat(40)}.xlsx`);
  });

  it("can never hold a path or a break", () => {
    expect(registerFileName("../../x\r\nSet-Cookie: a=b")).not.toMatch(/[/\\\r\n: ]/);
  });
});

describe("which files are offered to the reader of a register", () => {
  it("are workbooks by their name or their type, and the server checks the bytes", () => {
    expect(looksLikeWorkbook(new File(["x"], "Register.XLSX"))).toBe(true);
    expect(
      looksLikeWorkbook(
        new File(["x"], "register", { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }),
      ),
    ).toBe(true);
    expect(looksLikeWorkbook(new File(["x"], "register.csv", { type: "text/csv" }))).toBe(false);
  });
});

describe("sending a filled register file to be read", () => {
  it("posts the file as it is, as one raw body, with the forgery header, and returns the typed boxes", async () => {
    setScope("inspection");
    document.cookie = `${FORGERY_COOKIE}=token-1; path=/`;
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ data: { input: { requestNo: "DCN-1" }, notes: [] } }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const file = new Blob(["bytes"]);

    const outcome = await readRegisterFile("t 1", file);

    expect(outcome.ok).toBe(true);
    const [address, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(address).toBe("/api/v1/templates/t%201/change-requests/register/read?application=inspection");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(file);
    expect(init.headers).toMatchObject({ "Content-Type": "application/octet-stream", [FORGERY_HEADER]: "token-1" });
  });

  it("shows the server's own words when it refuses the file", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ error: { code: "VALIDATION_ERROR", message: "That file is not a register." } }), {
            status: 400,
            headers: { "Content-Type": "application/json" },
          }),
      ),
    );

    const outcome = await readRegisterFile("t-1", new Blob(["bytes"]));

    expect(outcome.ok).toBe(false);
    if (!outcome.ok) {
      expect(outcome.failure.message).toBe("That file is not a register.");
    }
  });
});
