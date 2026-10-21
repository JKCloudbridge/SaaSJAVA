import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));

// The content security policy is set per run by lib/serve.mjs (a small proxy in front), so that the same build can be
// tried with no policy, with inline styles allowed and with the strict policy.
export default {
  turbopack: { root: here },
  outputFileTracingRoot: here,
  poweredByHeader: false,
};
