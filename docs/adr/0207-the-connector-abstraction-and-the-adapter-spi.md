# ADR-0207: The connector abstraction and the adapter SPI

- **Status:** Accepted (development decision of Sprint 26; the owner confirms or changes it when reading the sprint document)
- **Date:** 2026-10-30
- **Sprint:** S26
- **Related:** [ADR-0001](0001-modular-monolith.md), [ADR-0007](0007-secrets-store-interface.md), [ADR-0016](0016-transactional-outbox-and-idempotent-consumers.md), [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0211](0211-what-sprint-26-leaves-out.md)

## Context

Milestone M7 is the first that leaves the platform. Every external system has to be reached through one abstraction, and
the core must never name one (the standing rule; an architecture test already forbids any class outside `integration`
from referencing `app.platform.integration.adapter..`). Sprints 27 to 29 add secrets, mapping and other protocols on top,
so what Sprint 26 fixes must be small enough to be right and open enough not to need rewriting.

## Decision

**A connector type is a description plus two small behaviours, provided by an adapter bean.** The SPI is
`app.platform.integration.adapter.Connector`:

| Operation | What it does |
|-----------|--------------|
| `describe()` | A closed, versioned `ConnectorDescription`: key, label, one sentence, version, the ways of authenticating it supports (`AuthKind`), its settings (`SettingSpec`: kind `TEXT`, `PATH`, `CHOICE`, `INTEGER`, `BOOLEAN`, with default, bounds and choices) and the platform events a connection of this type may listen for |
| `probe(settings)` | The one safe request (`GET` or `HEAD`, a path, a wait) that shows the other system is reachable and accepts the credential |
| `accepts(settings, status)` | Whether an answer with this status shows the connection works |

**An adapter is declarative.** It does not open a socket, read a secret, touch the database, log or know the tenant; an
architecture test (`IntegrationArchitectureTest`) fails the build if a class in the adapter package depends on `java.net`,
`javax.net`, JDBC, the web layer, the secrets contract, the tenant module, metadata, data, security or the module's own
internals. The framework builds the call from the adapter's answer and makes it through the one outbound client
(`EgressClient`, ADR-0208, ADR-0210), so the address rules, the timeouts, the size cap and the credential handling cannot
be bypassed by an adapter and live in one place.

**How adapters are found.** Every `Connector` is a Spring bean; `ConnectorRegistry` collects them like the catalogue of
workflow actions (ADR-0138), by key, at start. A duplicate key, or an event the platform does not offer, stops the start
with a message naming the type. Adding a connector type is writing one class in `app.platform.integration.adapter..`;
nothing in the core, the API or the screens changes, because the catalogue endpoint
(`GET /api/v1/integrations/connector-types`) describes the type and the settings form is drawn from that description.

**Core never names an external system.** The sample connector (`adapter.rest.RestConnector`, key `rest`) is reached only
through the interface (a rule forbids any class outside its package from depending on it). The browser holds no connector
type, setting, event, way of signing in or reason of a test (a scan test, `noListsInTheBrowser.test.ts`).

**What the SPI offers now, and what it does not.** Describe, probe and judge the answer: that is everything a connection
test needs, and a test is everything Sprint 26 can carry out. *Send a record*, *receive a record*, *map a field* and *other
protocols* are not placeholders on the interface: they are added to `Connector` by Sprints 28 and 29 together with the code
that calls them. A change of the meaning of a setting raises the description's `version`; a connection remembers the
version it was checked under and the version is part of its fingerprint (ADR-0210), so a connection made under an older
description must be tested again.

**The sample connector.** `rest`: any web service that answers a plain request. Settings: the check path, the method
(`GET` or `HEAD`), which answers count as working (any `2xx`, or exactly `200`) and the seconds to wait (1 to 10). Ways of
signing in: none, a bearer token, an API key in a header, a user name and password. It is a real, usable type (it shows
whether a service is reachable and accepts the credential), and it is the exit test's connector.

**What "runs through the full framework" means.** `ConnectorFrameworkIT`: against a web service the test starts on this
machine, in one organization, (1) the catalogue describes the type; (2) a connection is defined with a credential by
reference, a subscription and a scope; (3) it cannot be switched on before a test; (4) a test makes one call with the secret
resolved at that moment and sent in a header, and passes; (5) it is switched on and the platform's two questions
(`Integrations.subscribersOf`, `Integrations.allows`) answer for it; (6) when the service fails the connection becomes
failing and unusable, and a passing test recovers it; (7) switching off stops all use at once; (8) removal. The "event
stub" is the subscription: which switched-on connections would be told about a record event. Nothing sends a record.

## Consequences

- A connector type is cheap to add and cannot weaken the platform's outbound rules.
- Because the adapter cannot test itself over the network, an adapter's own logic (its request and its judgement) is unit
  tested with no network, and its end-to-end behaviour is tested through the framework against a test double.
- Sprint 28 will add sending to the interface; the adapter will then describe an outbound request and the framework will
  make it the same way, so the address rules and the bounds keep one home.
