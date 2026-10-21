# ADR-0184: Number series: where they are declared, the format language and the counters

- **Status:** Accepted (the product owner approved the FA2A plan and its recommendations on 2026-10-21)
- **Date:** 2026-10-21
- **Sprint:** FA2A (Functional track)
- **Related:** [ADR-0176](0176-number-series-and-the-first-approvers-number.md) (sections 1 and 3; the engine hook of section 2 is FA4A and FA4), [ADR-0096](0096-auto-number.md) (stays as it is), [ADR-0111](0111-uniqueness-of-field-values.md), [ADR-0181](0181-the-solution-framework.md), [ADR-0182](0182-the-organizations-own-list-of-a-platform-picklist.md), [ADR-0183](0183-a-field-only-the-platform-writes.md)

## Context

[ADR-0176](0176-number-series-and-the-first-approvers-number.md) decided the closed format language, the counter per scope and that a bundle file `numbering.yml` declares a series. Building it settled four points the ADR left open, and the product owner's real numbering scheme (2026-10-21) added a fifth: **the format depends on the category** (`IMS/01`, `QSP-01`, `WI/PRD/01`, `F/EHS/MNT/01`), some numbers have a sub-category part and some do not, and a new abbreviation added later starts again at 1.

## Decision

### 1. Where a series is declared

`data` cannot depend on `solution` (a generic module knows no solution). The declaration is therefore a **shared-kernel contract**, the same inversion as `ShippedApplications`: `NumberSeriesDeclarations` / `NumberSeriesDeclaration` in `app.platform.sharedkernel.solution`. **`solution` implements it** (`ShippedNumberSeries`) from `solutions/<key>/numbering.yml` (`NumberingLoader`), checks that a series numbers an object **its own solution brings**, and **`data` collects it** through an optional bean (`NumberSeriesCatalogue`) and validates every series against `PlatformObjects` **when the application starts**. A mistake stops the start with a message that names the series and the part and never repeats a text.
`SolutionBoundaryTest` needed one line: only the solution module may implement the new contract. No place was added for the cluster's words: the series of the shipped solution is in its own bundle folder.

### 2. A format may depend on a selector

A series has either **one format** (`format:`) or a **selector** (a short-code picklist of the object) and **a format for each of its values** (`selector:` and `formats:`): the category chooses `WI/{field:subCategory}/{seq:2}` or `IMS/{seq:2}`. A value of the selector with no format (a category whose last part is typed, not counted) cannot be numbered: the reason is `NO_FORMAT`, the series refuses it, and nothing is used.

### 3. The format language (closed)

- Literal text from `[A-Za-z0-9/_-]` (a `/` is allowed in a format; the prefix of an auto-number is not), **at most 10 literal characters in all**;
- `{field:<apiName>}`: the stored value of a **short-code picklist** (`codeValues`) or a **text field** of the object, which must match `[A-Za-z0-9_-]{1,10}` **when the number is made** (`PART_MISSING` or `PART_NOT_A_CODE`, fixed words, never the value);
- exactly one `{seq:N}`: the counter, N from 1 to 9, padded with zeros and **never cut** (`WI/PRD/100` after 99);
- at most four parts in braces, 100 characters in all; no clock, no arithmetic, no expression, no executable text.
The loader also checks that the field that holds the number is a **unique text field flagged `platformWritten`** and long enough for the longest number a format can make (the counter counted as 12 digits and each field part as 10 characters), that a format reads only fields the object has that are not the number field, and that every format belongs to a value of the selector. The `number` of the shipped object is 40 characters.

### 4. The counter

`number_series_counter` (V062): `(tenant_id, series_key, scope_key, next_value, version)`, forced row level security, a partial unique index on `(tenant_id, series_key, scope_key)`; no security-version or metadata-version trigger (it feeds neither). **The scope is the value of the selector followed by the values of the fields the format reads, joined with `/`** (`WI/PRD`). One statement takes a number: `insert ... on conflict ... do update set next_value = next_value + 1 returning next_value - 1`; **a scope never numbered has no row and starts at 1**, which is how a newly added abbreviation starts again at 1 without anyone doing anything, and one chosen from the list goes on from the last number. The row stays locked until the transaction ends: two instances numbering one scope queue, other scopes and other organizations do not wait for it; a number taken in a transaction that rolls back is never issued (the counter goes back with it); a number issued is never issued again; gaps are allowed. Every part of the format is checked **before** the counter moves, so a record that cannot be numbered uses no number.
`NumberSeries` (public contract in `data`) is `declared`, `prefix` (the fixed part in front of the serial, all a preview may show, because showing the serial means taking it and its lock) and `next` (inside a transaction only). **No endpoint and no screen takes a number** in this release; the engine of FA4A/FA4 calls it from inside its own transaction and writes the number with `BackgroundWrites` ([ADR-0183](0183-a-field-only-the-platform-writes.md)).

### 5. Things worth knowing

- **The backstop starts after the index policy has run.** The unique field is enforced by a partial unique index once its slot is complete (`IndexPolicy` after a published change, or the sweep every five minutes); in the first minutes after an organization gains the feature the counter alone prevents a duplicate. The tests drive the policy explicitly where they need the index; the counter is what the two-instance test proves.
- **Typed last parts are not numbered yet.** Control plan, process flow chart and FMEA end in a part code or name and the business plan in a year; the fields for those arrive with the template page. They have no format in `numbering.yml`, so they are not numbered by the series. Adding one is a format and a field, not a change to the language.
- **A scope holds the organization's codes**, so it is never written to a log, an audit record or an event; the tests (`NumberSeriesIT`) read all three after a run.

## Consequences

- Migration **V062**, the contract, `NumberSeries`, `NumberFormat`, `NumberSeriesCatalogue`, `DefaultNumberSeries`, `NumberingLoader`, `ShippedNumberSeries`, `numbering.yml` of the shipped solution.
- Tests: `NumberFormatTest` (the language and a hostile corpus), `NumberSeriesCatalogueTest` (what stops the start), `NumberingLoaderTest`, `NumberSeriesIT` (two real instances, a rollback, locks and scopes, the leak test), the isolation harness lists `number_series_counter`.
