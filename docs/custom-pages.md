# Custom pages: how a tenant's script runs inside the platform

Short form of ADR-0127 (the mechanism) and ADR-0128 (the `Tab` kind and security-aware navigation), written for whoever next touches this
code. Read the ADRs for the *why*; this page is the *how*, with where everything lives.

## The shape in one picture

```
Browser tab (origin: https://tenant-a.example.test)
┌──────────────────────────────────────────────────────────────────────────┐
│ CustomPageFrame.tsx                                                       │
│   1. loadScript()  ──GET /api/v1/applications/custom-pages/{page}──▶ API  │
│   2. openHostChannel() starts listening for "hello"                       │
│   3. <iframe sandbox="allow-scripts" srcdoc={buildFrameDocument(...)}>    │
│                                                                            │
│      <iframe> (opaque origin: "null")                                    │
│      ┌──────────────────────────────────────────────────────────────┐   │
│      │ bootstrap script (part of the srcdoc, never a 2nd request)    │   │
│      │   window.parent.postMessage({type:"hello"}, origin)  ───┐     │   │
│      │                                                          │     │   │
│      │        ◀── MessagePort (one end) ────────────────────────┘     │   │
│      │                                                                │   │
│      │   defines window.platform { ready, api, json, resize,         │   │
│      │     navigate, onThemeChange }                                 │   │
│      │                                                                │   │
│      │   the organization's uploaded script runs here, calling       │   │
│      │   platform.api("/objects/Deal__c/records") etc.                │   │
│      └──────────────────────────────────────────────────────────────┘   │
│                      │ fetch, Origin: null, Authorization: Bearer pcs_…  │
└──────────────────────┼────────────────────────────────────────────────┘
                        ▼
                 /api/v1/... (EmbeddedCors answers Origin "null" only,
                 no credentials; EmbeddedAccessFilter checks the path
                 against EmbeddedAccessPolicy; every call still goes
                 through Decisions/Permissions as the signed-in member)
```

## Where the code lives

| Piece | Path |
|---|---|
| The message protocol (shapes only, no logic) | `platform-web/src/lib/customPage/protocol.ts` |
| The frame's document and its bootstrap script | `platform-web/src/lib/customPage/frameDocument.ts` |
| The host's half of the conversation | `platform-web/src/lib/customPage/hostChannel.ts` |
| Fetching/caching a script, asking for a token | `platform-web/src/lib/customPage/client.ts` |
| Reading the design tokens for the frame | `platform-web/src/lib/customPage/theme.ts` |
| The component that puts it all together | `platform-web/src/components/apps/CustomPageFrame.tsx` |
| `Tab` / `CustomPage` metadata types | `platform-app/.../metadata/Tab.java`, `CustomPage.java` |
| The lifecycle wiring (store, codec, service, controller, dependencies) | `platform-app/.../metadata/internal/Definition*.java`, `ChangeApplier.java`, `ChangeCodec.java` |
| Uploading a script | `platform-app/.../metadata/internal/CustomPageAsset{Store,s,Controller}.java` |
| Security-aware navigation, serving a script to a member | `platform-app/.../application/internal/MyApplications.java`, `CustomPageScripts.java` |
| The scoped token | `platform-app/.../identity/internal/EmbeddedToken{s,Controller}.java`, `AuthorizationStore.insertEmbedded`/`introspectEmbedded` |
| What the token may reach | `platform-app/.../identity/internal/EmbeddedAccessPolicy.java` (the one list), `EmbeddedAccessFilter.java` |
| The CORS rule | `platform-app/.../identity/internal/EmbeddedCors.java` |
| The migration | `platform-app/src/main/resources/db/migration/V050__create_tabs_custom_pages_and_embedded_tokens.sql` |
| The real-browser proof | `spikes/custom-page-sandbox/` (`run-spike.mjs` against a hostile script; `live-check.mjs` against the real running application) |
| The working example | `platform-app/src/main/resources/local/objects-overview.js`, seeded for `tenant-a` by `LocalCustomPageSeeder` |

## How to change something

**Add a path the script may call.** Edit the one list in `EmbeddedAccessPolicy.RULES`. Each entry says what it's for — write that sentence
honestly; `EmbeddedAccessPolicyTest.everyRuleSaysWhyItIsThere` only checks it's non-blank, but a reviewer will read it. Add a case to
`EmbeddedAccessPolicyTest` proving both the new path is reachable and that it still isn't reachable with the wrong method. The policy can
only *narrow* what the signed-in member could already do — never route around `Decisions`/`Permissions`.

**Something a script must never do for a person.** The one list is an allow-list with a carve-out: `EmbeddedAccessPolicy.isApprovalRequest` refuses every method but a read on `/objects/{object}/records/{id}/approval`, because submitting a record for approval locks it and asks people to decide, which is a person's own act (ADR-0152). Do the same, with a case in `EmbeddedAccessPolicyTest`, for any later action that commits other people to something.

**Change the token's lifetime.** `platform.identity.tokens.embedded` (default `5m`). The hard ceiling is in the database
(`embedded_token_short_lived`, 15 minutes) — raise that constraint first (a new migration) if you need to go past it, and update
`IdentityProperties.Tokens` validation to match. Also update `RENEW_BEFORE_MS` in `hostChannel.ts` if the new lifetime is short enough that
renewing a minute early would renew almost immediately.

**Add a message kind to the protocol.** Add it to `HostMessage` or `FrameMessage` in `protocol.ts` first (one place, both sides import it),
bump `PROTOCOL_VERSION` only if old and new sides must not talk to each other at all (normally you don't need to: an unknown message type is
silently ignored on both ends, so you can add one without breaking anything already deployed). Implement the sender and the handler, add a
case to `hostChannel.test.ts` with a fake port, and add a case to the hostile-script test in `spikes/custom-page-sandbox/cases.mjs` if the
new message could plausibly be abused (anything the page can *ask the host to do* is worth trying to abuse).

**Change the `sandbox` attribute.** Don't, without re-reading ADR-0127 §1 and updating `spikes/custom-page-sandbox` first. Every flag beyond
`allow-scripts` removes a layer of protection the ADR documents by name; prove in the real-browser spike that what you're adding back is
still safe before changing `FRAME_SANDBOX`.

**Add a design token the frame may use.** Add the CSS custom property name to `THEME_VARIABLE_NAMES` in `protocol.ts`. `isThemeEntry`
already filters the *value* (length, no `;{}<>`, no `url(`/`@import`/`expression(`) so a new name doesn't need its own validation.

**Let a record page's tab show a custom page (S20).** Reuse `CustomPageFrame` as-is; render it wherever a `custom`-kind tab of a record page
resolves, exactly like an application's `TAB` navigation item does today in `ApplicationEntryView.tsx`. Do not build a second rendering
path — the whole point of `Tab` being independent of `ApplicationDefinition` (ADR-0128) is that this is a placement decision, not a new
mechanism.

## Running the proofs yourself

```powershell
# The hostile-script test (no backend needed, starts its own two Node servers)
cd spikes/custom-page-sandbox
npm install
npm run spike

# The real stack (needs the local services, the backend on --spring.profiles.active=local, and `npm run dev`)
node live-check.mjs
```

Both exit non-zero on any failure and print one `PASS`/`FAIL` line per check, so a regression is visible without reading screenshots.
