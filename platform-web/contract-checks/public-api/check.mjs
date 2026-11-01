// Proves that the published contract of the public API can be turned into a usable client (Sprint 30, ADR-0248).
//
//   node contract-checks/public-api/check.mjs
//
// It generates TypeScript types from platform-public-api-v1.json with the tool the internal client already uses, then
// type-checks sample.ts, which calls every operation of the public API with the types the document describes. A document
// that a generator cannot turn into types, or whose operations cannot be called as the contract says, fails here. The
// generated file is written to .generated/ (ignored by git); the public document is not consumed by the web application.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../..");
const spec = resolve(root, "../platform-api-contract/src/main/resources/openapi/platform-public-api-v1.json");
const generated = join(here, ".generated");

const require = createRequire(import.meta.url);
const generator = join(dirname(require.resolve("openapi-typescript/package.json")), "bin/cli.js");
const compiler = join(dirname(require.resolve("typescript/package.json")), "bin/tsc");

if (!existsSync(spec)) {
  console.error(`The public OpenAPI document was not found: ${spec}`);
  process.exit(1);
}

mkdirSync(generated, { recursive: true });
execFileSync(process.execPath, [generator, spec, "--output", join(generated, "schema.d.ts")], {
  stdio: ["ignore", "ignore", "inherit"],
});

// Every operation the document describes must be called by the sample, so a new operation cannot be added unseen.
const document = JSON.parse(readFileSync(spec, "utf8"));
const operations = [];
for (const [path, item] of Object.entries(document.paths)) {
  for (const method of Object.keys(item)) {
    operations.push(`${method.toUpperCase()} ${path}`);
  }
}
const sample = readFileSync(join(here, "sample.ts"), "utf8");
const missing = operations.filter((operation) => {
  const [method, path] = operation.split(" ");
  return !sample.includes(`client.${method}("${path}"`);
});
if (missing.length > 0) {
  console.error(`sample.ts does not call these operations of the public API:\n  ${missing.join("\n  ")}`);
  process.exit(1);
}

writeFileSync(
  join(generated, "tsconfig.json"),
  JSON.stringify(
    {
      compilerOptions: {
        target: "ES2022",
        lib: ["dom", "esnext"],
        strict: true,
        noUncheckedIndexedAccess: true,
        noEmit: true,
        module: "esnext",
        moduleResolution: "bundler",
        skipLibCheck: true,
        typeRoots: [],
        types: [],
      },
      files: ["../sample.ts"],
    },
    null,
    2,
  ),
);

try {
  execFileSync(process.execPath, [compiler, "-p", join(generated, "tsconfig.json")], { stdio: "inherit", cwd: root });
} catch {
  console.error("The client generated from the public contract does not type-check with the sample.");
  process.exit(1);
}
console.log(`The public contract produced a usable client: ${operations.length} operations called by sample.ts.`);
