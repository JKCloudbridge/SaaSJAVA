# ADR-0275: The event catalogue and the payload of a webhook (S32)

- **Status:** Accepted
- **Date:** 2026-11-23
- **Sprint:** S32
- **Related:** [ADR-0082](0082-storage-benchmark-specification-gate-g1.md), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0245](0245-the-public-contract-shapes-numbers-versions-queries-and-bulk.md), [ADR-0274](0274-what-a-webhook-subscription-is-and-where-it-lives.md)

## Decision

1. **The events are a closed catalogue in one place** (`WebhookEvents`): `record.created`, `record.updated`, `record.deleted`,
   `record.restored`; and `ping`, which is not asked for but is the message of the verification and of the test. The catalogue
   endpoint (`GET /api/v1/webhooks/catalogue`) tells the screens, which hold no list; the database holds the same four keys in a
   check, and `WebhookCatalogueTest` holds the two together. An event of an approval, a workflow or an import is a later
   addition to the list, never a change to a message that exists. (`record.purged` is a clean-up of the platform, not something a
   customer asked to hear about, and is left out.)
2. **The payload carries identifiers only.** The exact body, in a fixed order:
   `{"payloadVersion":1,"id":"<delivery>","type":"record.updated","createdAt":"<time, whole seconds>","object":"Employee__c",
   "recordId":"<record>","version":5}`. The delivery identifier, the event key, the time, the object's API name, the record's
   identifier and the record's version, and nothing else. **No field name and no value:** the names of the changed fields can
   reveal a field the member may not read, and a value is the record's, not the message's. `WebhookPayload` builds it from typed
   parts only, each checked against the shape of what it is (an event of the catalogue, an API name), so no text of a record can
   enter it however a caller tries; `WebhookPayloadTest` fails if a property or a text can get in. `version` is left out when the
   event did not carry one; a ping has no object and no record.
3. **The receiver fetches the record** through the public API under its own credentials and scopes. What it learns is decided by
   the permissions of the member behind that credential and never by the message.
4. **The message is versioned** with `payloadVersion`; a property is only ever added, as in the contract.
5. **What is documented:** the message, its headers, the signature and a worked verification are in `docs/public-api.md` (a
   "Webhooks" section), without a host.

## Consequences

- The platform can promise a receiver nothing about record values and nothing about field names, which is also why a receiver of
  a stricter member sees no more than the member could see.
- A receiver that wants the new values must call back, which spends its own rate limit (ADR-0280): that is intended.
