# ADR-0208: The integration definition, the credential reference and the address rules

- **Status:** Accepted (development decision of Sprint 26; **question 4 of the prompt is decided differently from its recommendation, see below**)
- **Date:** 2026-10-30
- **Sprint:** S26
- **Related:** [ADR-0003](0003-rls-defence-in-depth.md), [ADR-0006](0006-no-static-api-keys.md), [ADR-0007](0007-secrets-store-interface.md), [ADR-0207](0207-the-connector-abstraction-and-the-adapter-spi.md), [ADR-0210](0210-the-connection-lifecycle-and-connection-testing.md)

## Decision

### A definition is a row of the organization, not metadata

The prompt recommended publishing the definition through change sets and releases. It is **not** done that way, for these
reasons, and the owner can reverse the choice at the cost of one new change kind family:

1. An address and a credential reference belong to **one environment**. Every other kind of metadata describes what an
   organization's data and processes *are* and may travel in a release to another environment; an endpoint must not.
2. A **rollback** of a release would move the place the platform sends records to, silently, with a state (`ENABLED`) that
   rests on a test of a different address. The lifecycle of a connection (ADR-0210) is already its own draft: made, tested,
   switched on, frozen while on. A second draft mechanism on top would have two sources of truth about "is this the
   definition in use".
3. The metadata module cannot depend on `integration` (the edge points the other way), so validating a connection's settings
   against its connector type's schema would have needed a shared-kernel catalogue contract and a dependency contributor for
   a definition that no other definition depends on.

So the definition and the state are **one tenant-scoped table, `integration_connection`** (V083), with the platform's base
columns, forced row level security, the tenant guard, an optimistic `version`, a tenant-first partial unique name, and a
database guard of the lifecycle. A change is audited (`integration.*`) and carries the version read.

### What the definition holds

Name (permanent), label, connector type (permanent) and the version of its description, **base address**, **way of
authenticating** (`NONE`, `BEARER_TOKEN`, `API_KEY_HEADER`, `BASIC`), the **name of the header** for an API key, the **name
of the credential**, the **settings** (checked against the type), the **events** it listens for (`record.created`,
`record.updated`, `record.deleted` for a named object of the organization) and its **scope** (ADR-0209). All of it is a
closed typed model: keys, names, numbers and a few bounded strings. **No executable text, no arbitrary SQL, no expression**
is accepted or stored; the safe expression engine is used by Sprint 28 for mappings, not here.

### The credential reference

The definition stores **the name of a secret, never a secret** (ADR-0007, decision D4). The name has the shape of the
shared kernel's `SecretRef.name` (`[a-z0-9][a-z0-9._-]{0,127}`); the **scope is never stored or accepted**: it is always
derived from the current organization (`SecretRef.forTenant(tenant, name)`), so a reference cannot point into another
organization or the platform by construction. The secret is resolved **at the moment of use, in memory, on the thread that
makes the call**, put into one header, and the copy destroyed after (`SecretValue.destroy`). It is never in a column, a log,
a response, an audit record, a metric, an event or an exception message. Whether a secret by that name exists is only ever
visible as the stable test reason `credential_missing`.

Until Sprint 27 delivers a store there is **no implementation of `SecretsStore`** in the application: a test of a connection
that needs a credential ends with `credential_store_unavailable`, and a connection that needs none (`NONE`) works. The tests
import a test-only in-memory store. This is the honest state and is written in the manual steps.

### The address rules (the SSRF defence)

The base address is read **by hand** (`EndpointRules`) and accepts only a narrow, boring form, by shape rather than by
interpreting tricks:

- `https` only; `http` only for an **exact development host** listed in `platform.integration.egress.development-hosts`,
  which is empty by default, **refuses to start in the `prod` profile**, and exists for a developer's machine and the
  automated tests;
- ASCII only, no whitespace or control characters, no backslash, at most 2048 characters;
- **no user information**, **no query**, **no fragment**; a path of a small character set with **no `.` or `..` segment
  (also not percent-encoded), no `%2f`, `%5c`, `%00`, `%25`, and no empty segment**;
- a port from 1 to 65535 with no leading zero;
- the host is a **host name with at least two labels and an alphabetic last label** (which keeps every spelling of a
  number — `2130706433`, `0x7f.1`, `0177.0.0.1`, `127.1` — out), or a **canonical** dotted IPv4 address, or a bracketed IPv6
  address with no zone; no `localhost`, `.local`, `.internal`, `.localdomain`, `.home.arpa`, `.lan`, `.intranet`, `.corp`,
  `.private`, `.invalid`, `.test`, `.example`, `.onion`;
- an IP address (a literal, or what a name resolves to) must be **public global unicast** (`HostAddresses`): not
  `0.0.0.0/8`, `10/8`, `100.64/10`, `127/8`, `169.254/16` (the instance metadata address), `172.16/12`, `192.0.0/24`,
  `192.0.2/24`, `192.88.99/24`, `192.168/16`, `198.18/15`, `198.51.100/24`, `203.0.113/24`, multicast or reserved; IPv6 only in
  `2000::/3` and not `2001::/32`, `2001:db8::/32`, `2002::/16` or the documentation prefix; an IPv4-mapped IPv6 address is
  judged as its IPv4 address.

The same rules run **again at the time of the call, on the addresses the name resolves to** (`EgressClient`): the name is
resolved once, **every** resulting address must be public, and the socket is opened to the address that was checked, so a
name that answers differently a moment later gains nothing (the rebinding gap that opening a connection by name would
leave). A redirect is **never followed**: a `3xx` is the reason `redirect_refused`. A secure connection checks the
certificate against the name and sends the name for server selection (`HTTPS` endpoint identification, TLS 1.2 and 1.3).

A hostile corpus (`src/test/resources/integration/hostile-addresses.txt`, 87 lines, one way round the rules each) is tried by
`EndpointRulesTest`, which fails the build when a line is accepted or when a refusal repeats what was typed. A refusal is a
fixed sentence at the field `baseUrl`; it never echoes the address.

### Settings

Settings are validated against the connector type's schema (`SettingsValidator`): unknown keys are refused without naming
them, every kind has a check (a path is the same path rule as above), defaults are filled in, an integer is inside its
bounds, and a problem is a fixed sentence at the place `settings.<key>`.

### Fingerprint

`Fingerprints.of(type, version, canonical address, way of authenticating, header name, credential name, settings)` is a
length-prefixed SHA-256 of exactly the parts that decide whether a successful test still means something. The label, the
events and the scope are not part of it (changing them does not need a new test).

## Consequences

- An address typed once is written one way (`https://host[:port]/path`), so the same address is the same fingerprint.
- The residual gap, stated: a **name** whose records change between the check and the connect is closed by connecting to the
  checked address; an administrator who points a connection at a **public** host of their own that then redirects or answers
  badly gets a stable reason, nothing more. The platform does not run an outbound proxy; a deployment may add one in front of
  the application, and the rules above still apply.
- `platform.integration.egress.development-hosts` is a configuration setting, not a feature: it is not reachable from the
  API, and the production profile will not start with it set.
