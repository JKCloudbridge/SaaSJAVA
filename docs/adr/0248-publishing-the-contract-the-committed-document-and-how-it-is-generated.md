# ADR-0248: Publishing the contract: the committed document and how it is generated (S30)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S30
- **Related:** [ADR-0011](0011-api-conventions.md), [ADR-0244](0244-the-public-api-its-address-its-closed-table-and-the-rules-that-carry-over.md), [ADR-0247](0247-versioning-and-deprecation-policy-of-the-public-api.md)

## Decision

1. **A second document, `platform-public-api-v1.json`**, committed in `platform-api-contract` beside the internal one. It
   contains the public paths and the schemas they reach, and nothing else: a test fails if a path outside the prefix or a
   schema of the internal document (other than the shared error model, `ApiError`, `ApiErrorResponse`, `ErrorCode` and
   `Pagination`) appears, if it names a host or a time of generation, or uses the project's working vocabulary (sprint and
   decision numbers, package names). Its only security scheme is the one that is true today: a bearer token of a signed-in
   user. The flows of the next sprints are not claimed.
2. **How it is generated.** The generator has one list of paths for the whole application (`springdoc.paths-to-match`, which
   outranks the paths of any group, found by trying a group first), so the internal document and this one are two runs of
   it: `PublicOpenApiContractIT` starts an instance whose generator is pointed at `/api/public/v1/**`, with
   `platform.openapi.reachable-schemas-only=true`, which keeps only the schemas some path reaches (other modules add their
   own schemas to every document). That customizer is declared in the `web` module because the generator applies customizers
   in the order the beans are registered and it must come after the ones that add schemas; it is off in the application, so
   the internal document is unchanged (`OpenApiContractIT` still equals its committed copy). The customizers of the public
   module (title, security, the statuses and headers every answer carries, the deprecations) act only on the handlers of the
   module and on a document that holds public paths. `scripts/update-openapi` regenerates both documents.
3. **What is served.** `GET /api/public/v1/openapi`, in **every profile including `prod`**, to anyone, with no token,
   read-only, cacheable (`Cache-Control: public, max-age=600`, a strong `ETag`, `304` on `If-None-Match`). What is served is
   the **committed file**, the very bytes the contract tests compare with what the application generates, so what is
   published cannot differ from what is tested, and the production image needs no generator. The internal document stays
   unserved in `prod` (`springdoc.api-docs.enabled=false`); a test starts an instance with that setting and shows the
   public document served and the internal one gone.
4. **What the served document reveals** is shapes, limits, names of operations and words: the same things the
   documentation says. It holds no data, no organization, no host, no identifier and no secret, and it describes the
   operations of the *platform*, not of any organization's objects (those come from `describeObject`, to a signed-in
   caller, by their own rights). That is why it can be public.
5. **The kill switch** `platform.publicapi.enabled` (`PLATFORM_PUBLIC_API_ENABLED`, default true) answers `NOT_FOUND` for
   every address under the prefix, the document included, before the security chain runs.
6. **Contract tests in CI** run in the integration stage that already runs `OpenApiContractIT`
   (`PublicOpenApiContractIT`, `PublicContractBaselineTest` and `PublicContractContentTest` run in the unit stage because
   they read files). The frontend job also generates a TypeScript client from the public document with the tool already
   used for the internal one and type-checks a small sample that uses every public operation
   (`platform-web/contract-checks/public-api`, `npm run api:public:check`): a document a generator cannot turn into a
   usable client fails the build. The public document is not consumed by `platform-web`.

## Consequences

A customer can read the contract before holding a credential, and the committed file is the product. The generation is
two runs instead of one; the extra start costs about half a minute in the integration stage.
