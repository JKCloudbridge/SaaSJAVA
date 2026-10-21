// The spike styles itself with the platform's design tokens. The copy is git-ignored; this refreshes it before a build.
import { copyFileSync } from "node:fs";
copyFileSync(new URL("../../../platform-web/src/styles/tokens.css", import.meta.url), new URL("../app/tokens.css", import.meta.url));
console.log("tokens.css copied from platform-web");
