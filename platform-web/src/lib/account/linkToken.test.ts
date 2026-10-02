import { afterEach, describe, expect, it } from "vitest";
import { takeTokenFromAddress } from "./linkToken";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abc";

function openAddress(address: string) {
  window.history.replaceState(null, "", address);
}

describe("takeTokenFromAddress", () => {
  afterEach(() => openAddress("/"));

  it("reads the token after the # and removes it from the address bar", () => {
    openAddress(`/reset-password#token=${TOKEN}`);

    expect(takeTokenFromAddress()).toBe(TOKEN);
    expect(window.location.hash).toBe("");
    expect(window.location.pathname).toBe("/reset-password");
  });

  it("keeps the query of the address and drops only the fragment", () => {
    openAddress(`/reset-password?from=mail#token=${TOKEN}`);

    takeTokenFromAddress();

    expect(window.location.search).toBe("?from=mail");
  });

  it("finds nothing when the address has no token", () => {
    openAddress("/reset-password");

    expect(takeTokenFromAddress()).toBeUndefined();
  });

  it.each([
    "short",
    "has spaces in it and is long enough to pass",
    "<script>alert(1)</script>-padding-padding",
    "a".repeat(201),
  ])("refuses something that cannot be a token: %s", (candidate) => {
    openAddress(`/reset-password#token=${encodeURIComponent(candidate)}`);

    expect(takeTokenFromAddress()).toBeUndefined();
    expect(window.location.hash).toBe("");
  });
});
