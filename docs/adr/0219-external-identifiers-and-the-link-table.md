# ADR-0219: External identifiers and the link table (S28)

- **Status:** Accepted
- **Date:** 2026-10-30
- **Sprint:** S28
- **Related:** [ADR-0123](0123-csv-import-jobs.md), [ADR-0111](0111-uniqueness-of-field-values.md)

## Decision

`integration_link` joins a record to the identifier it has on the other side, **per connection and object**, unique both ways (partial unique indexes), with the hash of what was last sent or stored and its direction. What a link joins never changes (guard). It is kept apart from the `externalId` field flag of imports: that flag is one identifier on the record itself and is unique for the whole object, while a record may be on several connections with a different identifier on each, and a link must be removable without touching the record.

- **Outbound:** no link means a create; the identifier comes from the answer's body, read by the adapter; an answer with none, or one over 200 characters, is the failure `no_identifier`. A link exists means an update with that identifier; a removal of the record sends a delete for a linked record and is skipped as `not_linked` otherwise.
- **Inbound:** the identifier is read from the payload by the adapter; none is `id_missing`. A link means update; none means create, if the mapping allows it. Two calls for one entity are serialized by a transaction advisory lock keyed by organization, connection and identifier, so one entity never becomes two records.
- **Hashes** decide only "did anything change since the last time"; an unchanged update is skipped as `unchanged`.
- The identifier is the one value of the other system this table keeps; it never reaches a log, an audit record, an event, a metric or an answer.
