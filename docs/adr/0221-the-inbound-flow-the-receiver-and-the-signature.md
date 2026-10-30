# ADR-0221: The inbound flow: the receiver and the signature (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0215](0215-what-sprint-27-leaves-out.md), [ADR-0213](0213-versions-rotation-the-credential-endpoints-and-the-audit-of-use.md), [ADR-0003](0003-rls-defence-in-depth.md)

## Decision

`POST /api/v1/inbound/{inboundId}/{entity}` is the platform's first unauthenticated endpoint that finds an organization. It is added to the short list in `SecurityConfiguration` (`isInbound`, an exact shape, outside the CSRF check because it has no cookie) and to `EndpointExposureIT` with its reason. **The organization comes from the connection**, found by the unguessable `inbound_id` (V083) in the system scope `inbound_lookup` (read only, one policy on `integration_connection`); the host, a header and the body never name it.

**Order, fixed in code and tested:** (1) per-source allowance (in memory, bounded, 120 a minute) and the size of the body, by the announced length and by the bytes read, **before** anything is parsed (413 above 256 KiB); (2) the connection by identity; (3) the signature over the exact bytes; (4) the nonce, once (the unique `integration_inbound_seen` row decides, so two instances cannot both accept it); (5) only then the payload is read, mapped and written.

**Signature (S28):** a shared-secret HMAC. Headers `X-Platform-Timestamp` (seconds), `X-Platform-Nonce` (8 to 64 of letters, digits, `-`, `_`) and `X-Platform-Signature` = `sha256=` + hex of HMAC-SHA256 over `v1\n<time>\n<nonce>\n<entity>\n<exact body bytes>`, keyed with the value of a **credential of the organization** whose name is the connection's `inboundCredential` setting (resolved at the moment of use, wiped). The entity is signed so a signed body cannot be sent to another entity; the time must be within five minutes either side; the nonce hash is stored for twice the window. The one new reader of a secret's value is `InboundSignatures`; the architecture rule that allowed two classes names it (and `CredentialHeaders`, which holds the code `ConnectionTesting` held), and `InboundService`, the sender and the controller are forbidden to touch `SecretValue`.

**No oracle.** An unknown connection, a connection that is not switched on, a malformed header, a stale time, a replay and a wrong signature are one answer, `401 UNAUTHENTICATED`, and the work is the same: with no credential to key it a fixed key is used and the result thrown away, and the comparison is `MessageDigest.isEqual`. After the signature the sender is known and gets precise fixed words (`404` nothing to receive for this entity, `409` record locked or changed, `400` the change cannot be applied).

**What S32 adds:** signing the platform's **own outgoing** webhooks and their secrets (ADR-0215 left webhook signing secrets to it). S28 verifies incoming; it does not sign anything outgoing. Mutual TLS and a bearer-only scheme were not chosen: a bearer token travels with every call and proves nothing about the body.
