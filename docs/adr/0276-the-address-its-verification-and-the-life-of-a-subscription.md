# ADR-0276: The address, its verification, and the life of a subscription (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0208](0208-the-integration-definition-and-the-address-rules.md), [ADR-0210](0210-the-connection-lifecycle-and-connection-testing.md), [ADR-0274](0274-what-a-webhook-subscription-is-and-where-it-lives.md)

## Context

The address of a webhook is attacker-controlled input to a server that has a network. A subscription must therefore be
checked when it is made, verified before it is used, and held to the same rules at every call.

## Decision

1. **Every rule of `EndpointRules` applies:** https only, a public host, no user information, no path tricks, no query or
   fragment; and at the call `EgressClient` resolves the name, requires **every** address to be public, connects to the address
   it checked (a name that answers differently a moment later gets no second chance) and follows no redirect. A webhook calls
   through `WebhookCaller`, the one class of the webhooks that reaches `EgressClient` (an architecture test). A development host
   (plain HTTP, private) exists only for a developer machine and the tests, and the `prod` profile refuses to start with one.
   A refusal never repeats what was typed.
2. **Only the status of the answer is read.** The body is never read, held or logged (`EgressClient.Call.statusOnly`), so a
   receiver that answers with a page is not a failure and tells the platform nothing; one deadline for the whole attempt.
3. **The lifecycle is a pure table** (`SubscriptionMachine`, held by a database guard): `DISABLED` (new, switched off by an
   administrator, or by the platform), `ACTIVE`, `FAILING` (on, with the latest attempts failing). `DISABLED → ACTIVE` needs a
   verification of the address *as it stands*; `ACTIVE ⇄ FAILING` is the platform's; either on state may go to `DISABLED`.
   **What a subscription is (name, address, events, objects) changes only while it is switched off** (the database says the
   same), and a change makes the last verification void, because the verification belongs to one fingerprint of the address,
   the events and the objects.
4. **The verification is a signed `ping`** sent by `POST …/test` (it needs `webhooks.manage`): success means a status from 200 to 299
   within the deadline. It is rate limited (one test of a subscription every few seconds, a few at once on an instance), made
   outside any transaction, signed with the subscription's current secrets, and answered with a stable reason and the platform's
   own sentence, never anything the receiver said. A test that finds the subscription changed meanwhile drops its result.
5. **A bound on the number of subscriptions** per organization and on the calls in flight to one address (ADR-0279).

## Consequences

- An administrator cannot switch on an address nobody tested, and cannot change an address without testing it again.
- A subscription whose member is gone is not a state: it is read at every delivery (ADR-0274).
