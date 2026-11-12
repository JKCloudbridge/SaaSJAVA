// POC 1: starts the production build of the harness and serves the two fixtures from a folder OUTSIDE the repository, so that you can look at
// the pages by hand in your own browser. Nothing is written. Stop it with Ctrl+C.
//   cd platform/spikes/sheet-grid ; npm run build
//   POC1_DATA=<folder with plant.json and spm.json> node poc1-serve.mjs [relaxed|strict|none]
//   then open  http://localhost:3100/poc1/univer?fixture=spm&sheet=2&full=1     (Univer 1.4.0, the supplier data sheet)
//              http://localhost:3100/poc1/own?fixture=spm&sheet=2                (our own grid, extended)
//              http://localhost:3100/poc1/univer?classes=3&role=filler&guard=events   (cells of three classes, with our guard)
import { createServer, request } from "node:http";
import { spawn } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { POLICIES } from "./lib/serve.mjs";

const DATA = process.env.POC1_DATA;
if (!DATA || !existsSync(`${DATA}/spm.json`)) { console.error("Set POC1_DATA to the folder that holds plant.json and spm.json (see POC 1 manual steps)."); process.exit(2); }
const policy = POLICIES[process.argv[2] ?? "relaxed"];
const root = fileURLToPath(new URL(".", import.meta.url));
const inner = 3101;
const child = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", String(inner)], { cwd: root, stdio: "inherit" });
createServer((req, res) => {
  const m = /^\/poc1-fixture\/(plant|spm)\.json$/.exec(req.url.split("?")[0]);
  if (m) { res.writeHead(200, { "content-type": "application/json" }); res.end(readFileSync(`${DATA}/${m[1]}.json`)); return; }
  const up = request({ host: "localhost", port: inner, path: req.url, method: req.method, headers: req.headers }, (r) => {
    const headers = { ...r.headers }; delete headers["content-security-policy"]; if (policy) headers["content-security-policy"] = policy;
    res.writeHead(r.statusCode, headers); r.pipe(res);
  });
  up.on("error", () => { res.writeHead(502); res.end(); });
  req.pipe(up);
}).listen(3100, () => console.log("open http://localhost:3100/poc1/univer?fixture=spm&sheet=2&full=1 and http://localhost:3100/poc1/own?fixture=spm&sheet=2"));
process.on("SIGINT", () => { child.kill(); process.exit(0); });
