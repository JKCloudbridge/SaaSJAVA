import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

export default defineConfig([
  ...nextVitals,
  ...nextTypescript,
  globalIgnores([
    ".next/**",
    "out/**",
    "build/**",
    "coverage/**",
    "next-env.d.ts",
    // Generated from the OpenAPI document; never edited by hand.
    "src/lib/api/generated/**",
  ]),
  {
    rules: {
      // Logging goes through src/lib/log.ts so every line carries the request and trace identifiers.
      "no-console": "error",
    },
  },
  {
    files: ["src/lib/log.ts", "scripts/**"],
    rules: { "no-console": "off" },
  },
]);
