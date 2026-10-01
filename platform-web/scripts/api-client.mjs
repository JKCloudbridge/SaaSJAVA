// Generates the TypeScript types of the API from the committed OpenAPI document (the contract owned by
// platform-api-contract). The generated file is committed, so a pull request shows exactly how the contract changed.
//
//   node scripts/api-client.mjs           regenerate src/lib/api/generated/schema.d.ts
//   node scripts/api-client.mjs --check   fail (exit 1) when the committed file differs from a fresh generation;
//                                         changes nothing. CI runs this, so a stale client cannot be merged.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const spec = resolve(root, "../platform-api-contract/src/main/resources/openapi/platform-api-v1.json");
const target = resolve(root, "src/lib/api/generated/schema.d.ts");

const require = createRequire(import.meta.url);
const cli = join(dirname(require.resolve("openapi-typescript/package.json")), "bin/cli.js");

const HEADER = `/**
 * Types of the platform API, generated from platform-api-contract/src/main/resources/openapi/platform-api-v1.json.
 * Do not edit by hand: change the API, regenerate the document, then run \`npm run api:generate\`.
 */
`;

if (!existsSync(spec)) {
  console.error(`OpenAPI document not found: ${spec}`);
  process.exit(1);
}

const lineFeeds = (text) => text.replace(/\r\n/g, "\n");

/** Runs the generator and returns its output with our own header and LF line endings. */
function generate(scratch) {
  const raw = join(scratch, "raw.d.ts");
  execFileSync(process.execPath, [cli, spec, "--output", raw], { stdio: ["ignore", "ignore", "inherit"] });
  const body = lineFeeds(readFileSync(raw, "utf8")).replace(/^\/\*\*[\s\S]*?\*\/\n/, "");
  return HEADER + body;
}

const scratch = mkdtempSync(join(tmpdir(), "api-client-"));
try {
  const fresh = generate(scratch);
  if (process.argv.includes("--check")) {
    const committed = existsSync(target) ? lineFeeds(readFileSync(target, "utf8")) : null;
    if (committed !== fresh) {
      console.error(
        "The generated TypeScript client is out of date with the OpenAPI document.\n" +
          "Run `npm run api:generate` in platform-web and commit src/lib/api/generated/schema.d.ts.",
      );
      process.exit(1);
    }
    console.log("The generated TypeScript client is up to date.");
  } else {
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, fresh);
    console.log(`Generated ${target}`);
  }
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
