import { describe, expect, it } from "vitest";
import {
  buildFrameDocument,
  contentSecurityPolicy,
  FRAME_SANDBOX,
  MAX_CODE_LENGTH,
  normalizeOrigin,
} from "./frameDocument.ts";

const ORIGIN = "https://tenant-a.example.test";

/** The two scripts of the document: the tenant's code as data, and the bootstrap. */
function scriptsOf(html: string): { data: string; bootstrap: string } {
  const data = /<script id="platform-code" type="application\/json">([\s\S]*?)<\/script>/.exec(html);
  const bootstrap = /<\/script><script>([\s\S]*?)<\/script><\/body>/.exec(html);
  if (!data || !bootstrap) {
    throw new Error("the document does not have the shape the frame expects");
  }
  return { data: data[1] ?? "", bootstrap: bootstrap[1] ?? "" };
}

describe("the sandbox of a custom page", () => {
  it("gives a frame scripts and nothing else: never the origin of the page around it", () => {
    expect(FRAME_SANDBOX).toBe("allow-scripts");
    expect(FRAME_SANDBOX).not.toContain("allow-same-origin");
    expect(FRAME_SANDBOX).not.toContain("allow-top-navigation");
    expect(FRAME_SANDBOX).not.toContain("allow-popups");
    expect(FRAME_SANDBOX).not.toContain("allow-forms");
    expect(FRAME_SANDBOX).not.toContain("allow-modals");
  });
});

describe("the content security policy of the document", () => {
  const policy = contentSecurityPolicy(ORIGIN);
  const directive = (name: string) => policy.split("; ").find((part) => part.startsWith(name + " "));

  it("lets the script talk to the platform and to no other address", () => {
    expect(directive("connect-src")).toBe(`connect-src ${ORIGIN}`);
    expect(directive("default-src")).toBe("default-src 'none'");
  });

  it("does not allow eval, frames, workers, forms, plug-ins or a changed base address", () => {
    expect(policy).not.toContain("unsafe-eval");
    expect(directive("script-src")).toBe("script-src 'unsafe-inline'");
    expect(directive("frame-src")).toBe("frame-src 'none'");
    expect(directive("worker-src")).toBe("worker-src 'none'");
    expect(directive("form-action")).toBe("form-action 'none'");
    expect(directive("object-src")).toBe("object-src 'none'");
    expect(directive("base-uri")).toBe("base-uri 'none'");
  });

  it("loads images and fonts only from the document itself", () => {
    expect(directive("img-src")).toBe("img-src data: blob:");
    expect(directive("font-src")).toBe("font-src data:");
  });
});

describe("the origin of the platform", () => {
  it("is accepted only as a plain origin", () => {
    expect(normalizeOrigin(ORIGIN)).toBe(ORIGIN);
    expect(normalizeOrigin("http://tenant-a.localhost:3000")).toBe("http://tenant-a.localhost:3000");
  });

  it("is refused with a path, a query, another scheme or a trailing slash", () => {
    for (const bad of [
      `${ORIGIN}/app`,
      `${ORIGIN}/`,
      `${ORIGIN}?x=1`,
      "javascript:alert(1)",
      "ftp://example.test",
      "data:text/html,x",
      "//example.test",
      "not a url",
    ]) {
      expect(() => normalizeOrigin(bad), bad).toThrow();
    }
  });
});

describe("the document of a custom page", () => {
  it("carries the policy in the document and the tenant's code as data, not as markup", () => {
    const html = buildFrameDocument({ code: "console.log(1)", origin: ORIGIN, autoResize: false });

    expect(html).toContain(`<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(ORIGIN)}">`);
    expect(scriptsOf(html).data).toBe(JSON.stringify("console.log(1)"));
    expect(html.toLowerCase()).not.toContain("<script src");
  });

  it("keeps any text from ending the element that holds the code", () => {
    const hostile = "</script><script>window.broken = 1</script><!--     &amp; ]]>";

    const html = buildFrameDocument({ code: hostile, origin: ORIGIN, autoResize: false });
    const { data } = scriptsOf(html);

    expect(html.match(/<\/script>/g)).toHaveLength(2);
    expect(html).not.toContain("<script>window.broken");
    expect(data).not.toContain("<");
    expect(data).not.toContain(" ");
    expect(data).not.toContain(" ");
    expect(JSON.parse(data)).toBe(hostile);
  });

  it("builds a bootstrap that is valid JavaScript", () => {
    const html = buildFrameDocument({ code: "", origin: ORIGIN, autoResize: true });

    // Parsed, not run: the real-browser test in spikes/custom-page-sandbox runs it.
    expect(() => new Function(scriptsOf(html).bootstrap)).not.toThrow();
  });

  it("tells the bootstrap who it talks to and what it may ask for, from one source", () => {
    const html = buildFrameDocument({ code: "", origin: ORIGIN, autoResize: true });
    const config = /var CONFIG = (\{[\s\S]*?\});/.exec(scriptsOf(html).bootstrap)?.[1] ?? "{}";
    const parsed = JSON.parse(config) as Record<string, unknown>;

    expect(parsed.origin).toBe(ORIGIN);
    expect(parsed.autoResize).toBe(true);
    expect(parsed.protocol).toBe(1);
    expect(parsed.frameSource).toBe("platform-custom-page");
    expect(parsed.hostSource).toBe("platform-host");
    expect(parsed.themeNames).toContain("--color-accent");
    expect(parsed.themeNames).not.toContain("--font-sans");
  });

  it("hides the page until the host has connected, so it is never seen unstyled", () => {
    const html = buildFrameDocument({ code: "", origin: ORIGIN, autoResize: false });

    expect(html).toContain("html:not([data-platform-ready]) body{visibility:hidden}");
  });

  it("uses design tokens for its own look and no literal colour", () => {
    const html = buildFrameDocument({ code: "", origin: ORIGIN, autoResize: false });

    expect(html).toContain("var(--color-text)");
    expect(html).not.toMatch(/#[0-9a-fA-F]{3,8}\b|\b(rgb|rgba|hsl|hsla)\(/);
  });

  it("refuses a script larger than the platform allows", () => {
    expect(() => buildFrameDocument({ code: "x".repeat(MAX_CODE_LENGTH + 1), origin: ORIGIN, autoResize: false }))
      .toThrow();
    expect(() => buildFrameDocument({ code: "x".repeat(MAX_CODE_LENGTH), origin: ORIGIN, autoResize: false }))
      .not.toThrow();
  });

  it("does not turn a dollar pattern in the code into a replacement", () => {
    const code = "var a = '$&'; var b = '$1'; var c = \"$`\";";
    const html = buildFrameDocument({ code, origin: ORIGIN, autoResize: false });

    expect(JSON.parse(scriptsOf(html).data)).toBe(code);
  });
});
