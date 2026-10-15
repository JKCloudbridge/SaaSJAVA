# ADR-0127: Custom page scripts, the sandboxed iframe and the scoped access token

- **Status:** Accepted
- **Date:** 2026-10-15
- **Sprint:** S18
- **Related:** ADR-0128 (the `Tab` metadata kind that lists a custom page), ADR-0019 to ADR-0021 (tokens and sessions), ADR-0120 (the object
  store), decision D6 of `docs/delivery-plan.md` (server-side tenant code stays deferred to a V2 design spike; this ADR is about
  client-side tenant code, which D6's clarification of 2026-10-15 puts in scope for V1)

## Context

A tenant needs to put its own screen inside the platform: a page that reads and writes records with the viewer's own permissions but whose
layout and logic the tenant writes, not us. Running tenant-authored code has one hard requirement the rest of the platform already states as
a standing rule: no arbitrary executable code from tenant metadata runs on our servers (`docs/engineering-guide.md`, "Standing architecture
rules"). D6 defers exactly that problem — tenant code inside our own server process — to a V2 design spike, because it needs real execution
isolation (an isolated worker process, WASM, or a container sandbox).

Client-side code is a different problem. A script that runs in the member's own browser never touches our server process, our database, or
another tenant; the browser is already a sandbox designed to run untrusted script from an arbitrary origin safely. The question this ADR
answers is how to let that sandbox do its job without accidentally handing the script something it should not have: the session cookie, the
rest of the page's DOM, or more API surface than drawing a screen needs.

## Decision

### 1. The mechanism: a sandboxed `iframe`, built from `srcdoc`, never from a URL

The custom page's script is fetched by the host page through the authenticated API (`GET /api/v1/applications/custom-pages/{apiName}`) and
assembled into a whole HTML document string, which is given to the frame's `srcdoc` attribute. The frame's `sandbox` attribute is exactly
`allow-scripts` — scripts only. In particular:

- **Never `allow-same-origin`.** Adding it would give the document the origin of the page that holds it (because `srcdoc` without
  `allow-same-origin` gets the opaque origin `"null"` instead), which would let the script read `document.cookie`, `localStorage` and the
  DOM of the host page. The frame must stay opaque-origin so none of that is reachable; the real-browser test in
  `spikes/custom-page-sandbox` proves this (`cookie`, `localStorage`, `parentDocument` all throw `SecurityError`).
- **No `allow-top-navigation`, `allow-popups`, `allow-forms` or `allow-modals`.** The script has no way to navigate the real page, open a
  window, submit a form outside the frame, or show a native dialog. `FRAME_SANDBOX` in `frameDocument.ts` is the one constant; a test fails
  if it is widened without a reason recorded here.
- **A document, not a URL.** Building the document from `srcdoc` means the script's origin is always `"null"`, on every browser, regardless
  of where the backend happens to serve the asset from — there is no second origin to configure, register with CORS, or keep in step with
  deployments. The alternative (a dedicated serving host for scripts) was rejected: it is more infrastructure for no security benefit, since
  `srcdoc` already gives an opaque origin for free.
- **A content security policy inside the document**, restricting `connect-src` to the platform's own origin, forbidding `eval`/`unsafe-eval`,
  frames, workers, forms and plugins. This is a second line of defence behind the `sandbox` attribute: even a browser bug or a
  misconfiguration of the sandbox would still have this policy in the way.

### 2. The message protocol: one private `MessageChannel`, established once

The frame's document loads a small bootstrap script (part of the same `srcdoc`, never a separate request) that defines `window.platform`
(the interface a tenant's script is written against) and posts one `hello` message to `window.parent`. The host, which was listening before
the frame ever got its `srcdoc`, replies with one end of a `MessageChannel`; everything after that — the access token, resizes, navigation
requests, theme changes — travels over that private channel, never over the window's `postMessage` (which any script on the page, or a
malicious page the frame later navigated to, could also listen to or spoof).

- The whole shape is written once, in `src/lib/customPage/protocol.ts`, and both sides (`frameDocument.ts`, the bootstrap; `hostChannel.ts`,
  the host) import from it. Nothing is duplicated.
- The host answers only the *first* `hello`; a second one (the frame navigated away and a different page sent its own `hello`) gets nothing
  — proven in the real-browser test (`spikes/custom-page-sandbox`, "a hello from the host window itself is ignored" and "the page it
  navigated to never received a channel").
- The frame has no page of its own to navigate *to* in the first place (the sandbox has no `allow-top-navigation`, `allow-popups` or
  `allow-forms`), so this is defence in depth, not the primary protection.

### 3. The access token: the member's own session, narrowed, never a new kind of login

The token the script uses is obtained by `POST /api/v1/embedded-tokens`, called by the **host page**, with the member's own session (cookie
+ CSRF header), never by the script itself (a request made with the token of a script is refused with `FORBIDDEN` if it tries). It is:

- **Bound to the sign-in grant it came from**: stored in a new table, `embedded_token`, that references `oauth2_authorization.id` with
  `on delete cascade` and is checked against the same aliveness condition as an ordinary access token (not revoked, the grant not past its
  absolute lifetime, the user's security version unchanged). Signing out ends it immediately; nothing extra had to be built for that.
- **Short-lived**: `platform.identity.tokens.embedded`, default five minutes, with a database check constraint
  (`embedded_token_short_lived`) that refuses a row older than fifteen minutes whatever the application setting says — the database is the
  backstop, not just the configuration.
- **Stored only as a hash** (`token_hash`, `sha256:` + hex), exactly like every other bearer secret in `oauth2_authorization` (ADR-0019).
- **Scoped to the data interface only**, by `EmbeddedAccessPolicy`: a short, explicit allow-list of paths (read the organization's
  definitions, read the member's applications and permissions, and the whole `/objects/...` tree — records, files, search, bulk, import).
  Everything that sets the organization up or decides who may do what (members, profiles, access policies, groups, sharing rules, licences,
  platform administration, every auth and token endpoint, including asking for another embedded token) is out of reach, whatever ability the
  viewer personally holds. The check runs as one servlet filter, `EmbeddedAccessFilter`, right after the token is authenticated and before
  any controller runs, so a refusal is the ordinary 403 of the error model and nothing downstream is touched.
- **Never widens what the member could already do.** The token identifies the same person; every call it makes is decided by
  `Decisions`/`Permissions` exactly as a call from the member's own session would be. `EmbeddedAccessPolicy` can only narrow the member's own
  abilities, never add to them — proved by `EmbeddedTokenIT.theViewersOwnAbilitiesDoNotWidenIt` (an administrator can read `/members` with
  their session and cannot with the token).

The host renews the token a minute before it ends (`RENEW_BEFORE_MS` in `hostChannel.ts`) and whenever the script's own call is refused with
401 (the bootstrap's `token-request` message), with a minimum gap between renewals so a confused or hostile page cannot cause a renewal
storm. Three failed renewals in a row are reported as a problem and the page is shown as needing a reload, rather than the host retrying
forever on a broken connection.

### 4. The cross-origin rule: `Origin: null`, no credentials, nothing else

Because the frame's origin is `"null"`, its `fetch` calls to the API are cross-origin from the browser's point of view and need a CORS
answer. `EmbeddedCors` is the **only** CORS configuration source in the application and the **only** origin it ever answers is the literal
string `"null"`:

- `Access-Control-Allow-Origin: null`, **`Access-Control-Allow-Credentials` never set** (so even if a script tried `credentials: "include"`,
  the browser would refuse to let it read the answer — the session cookie is never usable from inside the frame, proven in
  `EmbeddedTokenIT.aRequestFromASandboxedFrameNeverGetsItsSessionCookieTurnedIntoAToken`).
- Every other `Origin` (a real address, `http://localhost:3000` from the development proxy, or none at all) gets **no CORS configuration**
  at all, which is what keeps the application's own page — reached through the proxy under a different host than the API sees — working as
  an ordinary same-origin request; adding a blanket CORS policy would have broken that.
- `TokenAuthentication.CookieTokenFilter` additionally refuses to turn the session cookie into a bearer header for a request whose `Origin`
  is `"null"`, so a sandboxed frame can never ride on the member's cookie even if it somehow reached a public endpoint.

## Consequences

- One new backend surface (`EmbeddedTokenController`, `EmbeddedTokens`, `EmbeddedAccessPolicy`, `EmbeddedAccessFilter`, `EmbeddedCors`) and
  one new table (`embedded_token`, V050), all inside the `identity` module (tokens and the authorization server already live there; no new
  module edge).
- `ObjectStore` (previously internal to `data`) moved to `app.platform.sharedkernel.storage`, because both `data` (files) and `metadata`
  (custom page scripts) need it and neither should depend on the other for it. The contract did not change, only its package.
- The mechanism is built once here and reused without change by S19/S20 (an object or record-page custom tab) and S21 (the admin screens
  that create one): nothing about the sandbox, the protocol or the token changes for those; only who gets to author a `CustomPage` and where
  a `Tab` of kind `CUSTOM` is offered changes.
- **Tested before being trusted.** Two layers of proof, both real browsers, neither a unit test asserting on a mock:
  1. `spikes/custom-page-sandbox` is a throwaway Playwright harness (not part of the Maven or npm build) that drives real Chrome against
     the actual `frameDocument.ts`/`hostChannel.ts` source and a hostile script that tries every escape this ADR claims is blocked (reading
     the parent, cookies, storage, IndexedDB, `eval`, a string `setTimeout`, workers, WebSockets, dynamic `import`, a nested frame, a form
     post, `sendBeacon`, an image/stylesheet from another origin, overwriting `window.platform`, path traversal through `platform.api`,
     `credentials: "include"`) — every one of them is refused, and the real wire protocol (token renewal, a refused call retried once with
     a new token, theme/dark-mode propagation) is exercised end to end.
  2. `spikes/custom-page-sandbox/live-check.mjs` drives the whole real stack (the actual backend, the actual `npm run dev` frontend, the
     actual local database) through a real sign-in, opens the real sample custom page seeded for `tenant-a`
     (`LocalCustomPageSeeder`, `platform-app/src/main/resources/local/objects-overview.js`), and repeats a subset of the same probes from
     inside that live page, plus checks the shell around it (the launcher, the control bar, light/dark, a narrow window, a 403 from the
     scoped token against an out-of-policy endpoint).
- **Documentation for later change**: `docs/custom-pages.md` is the contributor-facing write-up of the protocol, the sandbox and the token,
  written so that extending the allow-list, changing the token lifetime, or adding a new message kind can be done by reading one page.

## What is deliberately not solved here

- **Server-side tenant code (D6)** is untouched. `CustomPage.backend` is accepted, stored, and never read by any class in the application —
  enforced by `CustomPageBackendReferenceIsInertTest`, which scans every source file for the literal property name.
- **Which tabs and applications may show a custom page** is ADR-0128, not this one.
- **Building, uploading or linking a script through a UI** is Sprint 21; this sprint's upload endpoint
  (`POST /api/v1/metadata/custom-page-assets`) exists so that a `CustomPage` has something to name, and `curl`/the integration tests are
  today's only callers.
