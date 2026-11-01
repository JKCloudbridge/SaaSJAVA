# ADR-0247: Versioning and deprecation policy of the public API (S30)

- **Status:** Accepted
- **Date:** 2026-10-31
- **Sprint:** S30
- **Related:** [ADR-0011](0011-api-conventions.md), [ADR-0244](0244-the-public-api-its-address-its-closed-table-and-the-rules-that-carry-over.md), [ADR-0248](0248-publishing-the-contract-the-committed-document-and-how-it-is-generated.md)

## Decision

1. **The major version is in the address** (`/api/public/v1`) and in every answer (`API-Version: v1`, set before the
   security chain so that a refusal or an error carries it too).
2. **Inside a version, a change only adds**: a new optional property of a response, a new optional parameter or header, a
   new operation, a new value of an enumeration that the contract tells clients to expect (error codes, field types,
   comparisons), a new status. Nothing is removed, renamed, retyped, made required, or given a narrower range. A change
   that cannot be made that way is **version 2, beside version 1**.
3. **A test holds the promise** (`PublicContractBaselineTest`). The committed document is compared with a baseline copy: a
   removed path, operation, status or parameter, a removed or retyped property, a request property that became required, a
   new required parameter and a removed value of an enumeration each fail the build with their place. Additive change passes.
   The detector itself is tested against each kind of break. When an addition is released the baseline is replaced by the
   new document in the same change as the addition; **a break is never fixed by replacing the baseline**. The same stage of
   the integration job runs `PublicOpenApiContractIT`, which requires the committed document to equal what the running
   application generates.
4. **Deprecation is announced, then waited out.** An operation is deprecated by registering it, with the day it is announced,
   the day it may first be removed and the address of its successor, in `ApiVersioning.shipped()`. From then on:
   the contract marks it `deprecated: true`, and every answer of it carries `Deprecation: @<announcement day as epoch
   seconds>` (RFC 9745), `Sunset: <HTTP date>` (RFC 8594) and `Link: <successor>; rel="successor-version"` (RFC 8288).
   It is removed no earlier than the sunset day, and **the sunset day may be no earlier than 12 months after the
   announcement**: the registry refuses to be built with a shorter notice (`ApiVersioningTest`, with a property over
   dates), so the promise to customers is a tested fact. A property of a response is deprecated in the contract with
   `deprecated: true` on the property and the same notice; it keeps being sent until its sunset day.
5. **A human document** (`docs/public-api.md`) states this policy in a customer's words, with the numbers, and the runbook
   (`docs/runbooks/public-api.md`) says how an operator announces a deprecation.
6. **The version of a deployment**: the platform serves `v1` while any customer may call it; version 2 appears beside it
   and version 1 is retired only through step 4.

## Consequences

Customers get a date they can plan with; the platform commits to carrying a deprecated operation for a year. The price is
that a mistake in version 1 can only be corrected additively or in version 2.
