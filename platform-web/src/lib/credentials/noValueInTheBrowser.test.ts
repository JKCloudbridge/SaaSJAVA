import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const src = path.resolve(__dirname, "..", "..");

/** The sources that draw or decide something about a credential. */
const FILES = ["components/setup/CredentialsPanel.tsx", "lib/credentials/model.ts"];

/**
 * The browser never holds a credential's value longer than the request that carries it, and the platform never sends one
 * back (Sprint 27, ADR-0212, ADR-0213). It also holds no list, limit or reason of its own: the API's words are shown for
 * every refusal.
 */
describe("the credential screens", () => {
  const sources = FILES.map((file) => ({ file, text: readFileSync(path.join(src, file), "utf8") }));
  const panel = sources[0]!.text;

  it("read the screens that exist (the scan is not looking at nothing)", () => {
    expect(sources.every((one) => one.text.length > 300)).toBe(true);
  });

  it("keep the value out of the page's state: the field is not controlled and is wiped when the form is sent", () => {
    expect(panel).not.toMatch(/useState[^;]*\b(value|secret|password)\b/i);
    expect(panel).toMatch(/useRef<HTMLInputElement>/);
    expect(panel).toMatch(/field\.value = ""/);
  });

  it("draw the field as a password input that browsers do not fill in", () => {
    expect(panel).toMatch(/type="password"/);
    expect(panel).toMatch(/autoComplete="new-password"/);
  });

  it("never write the value into an address, a log or storage", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/console\./);
      expect(text, file).not.toMatch(/localStorage|sessionStorage/);
      expect(text, file).not.toMatch(/\?value=|\$\{value\}|\$\{typed\}/);
    }
  });

  it("show no sentence of the platform's about a limit or a store failure: the API's words are shown", () => {
    for (const { file, text } of sources) {
      expect(text, file).not.toMatch(/4096|1 to 128|limit of credentials|key_unavailable|unreadable/i);
    }
  });

  it("have no response field that could hold a value", () => {
    const schema = readFileSync(path.join(src, "lib/api/generated/schema.d.ts"), "utf8");
    const start = schema.indexOf("        CredentialView: {");
    expect(start).toBeGreaterThan(0);
    const body = schema.slice(start, schema.indexOf("        };", start));
    expect(body).not.toMatch(/\b(value|secret|password|plaintext|ciphertext)\b\??:/i);
  });
});
