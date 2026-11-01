# Runbook: connections to external systems

Sprint 26 (ADR-0207 to ADR-0211). For the operator and for an organization's administrator. The framework can describe, test
and switch on a connection; a credential is written under Setup, Credentials (Sprint 27, [secrets.md](secrets.md)); it cannot yet map data or send a record (Sprint 28).

## Who may do what

| Ability | What it allows |
|---------|----------------|
| `integrations.view` | see the connections, their state, the last test and the connector types |
| `integrations.manage` | create, change, switch on or off and remove (needs `integrations.view` too) |
| `integrations.test` | call the external system once (needs `integrations.view` too) |

The organization also needs the plan feature `integrations` (it is in the trial plan; a platform administrator adds it to a
plan or gives one organization an override in the console). Without it every endpoint answers `409`.

## The life of a connection

`UNTESTED` (made, or changed since the last good test) → test → `TESTED` → switch on → `ENABLED`.
`ENABLED` → switch off → `DISABLED`. A failed test of a connection that is on makes it `FAILING` (not usable) until a test
passes or it is switched off. A connection that is on cannot be changed or removed. A change of the address, the sign-in, the
name of the credential or a setting voids the last test.

## Reading the result of a test

The answer is a stable reason and a fixed sentence; the platform never shows what the other system said.

| Reason | What to check |
|--------|---------------|
| `ok` | nothing |
| `address_not_allowed` | the name resolves to a private, loopback, link-local or otherwise non-public address; the platform will not connect there |
| `name_not_found` | the name does not resolve from the platform's network |
| `connect_failed` | nothing listens on that port, or a firewall drops the connection |
| `tls_failed` | the certificate is not trusted, has expired, or does not match the name |
| `timed_out` | the service did not answer within the connection's wait (at most 10 seconds) |
| `response_invalid`, `response_too_large` | the service did not answer in HTTP, or its headers exceed 16 KiB |
| `redirect_refused` | the check path redirects; point the connection at the final address (redirects are never followed) |
| `unexpected_status` | the service answered, but with a status the connection does not count as working |
| `authentication_rejected` | the service answered 401 or 403 to the credential |
| `credential_missing` | no secret exists under that name for this organization |
| `credential_unusable` | the secret cannot be sent as a header (a space in a bearer token, a line break, no `:` in a user name and password) |
| `credential_store_unavailable` | the secrets store cannot be read, or its key cannot be opened by this instance (see [secrets.md](secrets.md)) |
| `changed_during_test` | the connection was changed while it was tested; test again |
| `credential_changed` | not the result of a call: the credential this connection names was replaced, rolled back or removed after the last test, so that test no longer counts (the result shows as voided); test the connection again |

A test is limited to one per connection every `platform.integration.test.min-interval` (default 5 s); a second answers `429`.

## Settings (`platform.integration.*`)

| Setting | Default | Meaning |
|---------|---------|---------|
| `limits.max-connections` | 20 | live connections per organization |
| `egress.connect-timeout`, `egress.read-timeout` | 3 s, 5 s | per step; one overall deadline applies as well |
| `egress.max-header-bytes` | 16384 | the most that is read of an answer |
| `egress.development-hosts` | empty | exact hosts that may be used over plain HTTP and may be private. **The `prod` profile refuses to start when this is not empty.** For a developer machine and automated tests only |
| `test.timeout` | 10 s | the longest a whole test may take |
| `test.min-interval` | 5 s | the shortest time between two tests of one connection |
| `test.max-concurrent` | 4 | tests at once on an instance |

Environment variables are the usual Spring forms of these names (for example `PLATFORM_INTEGRATION_TEST_TIMEOUT`).

## Network

The platform opens connections from the application instances. Allow outbound HTTPS from them to the external systems an
organization uses and nothing else you do not want reachable: the platform refuses private addresses itself, but a network rule
is a second line. Do not put a proxy that follows redirects between the platform and the internet unless you have
read ADR-0208. Instance metadata services must remain unreachable from the application (the platform also refuses the
link-local address).

## Metrics and audit

`platform.integration.test{result,reason}` and `platform.integration.test.duration{result}`; `platform.integration.change{action}`.
Audit kinds `integration.created`, `.updated`, `.enabled`, `.disabled`, `.removed`, `.tested` (identifiers, keys and counts; never
an address, a label or a credential name). A rise of `reason=tls_failed` or `timed_out` across organizations points at the
network of the platform, not at one organization.

## Sending and receiving records (Sprint 28)

**What the platform does.** For a connection that is switched on, listens for `record.created|updated|deleted` on an object and has a live
mapping, the platform makes **one attempt** for each event: it maps the record as it is when the send is prepared, calls the external
system and records the result as a delivery (`DONE`, `FAILED` or `SKIPPED` with a stable reason). It does **not** retry, queue or break a
circuit (Sprint 29). A send that was under way when an instance stopped stays `PENDING` and the list shows it as interrupted; it is not
repeated, because it may have reached the other side. Deliveries are kept 30 days (`platform.integration.flow.delivery-retention`).

**What the external system must do to send a change.** `POST /api/v1/inbound/<inboundId>/<entity>` (the `inboundId` is shown on the
connection; it is an address, not a secret) with the headers `X-Platform-Timestamp` (whole seconds since 1970, within five minutes of now),
`X-Platform-Nonce` (8 to 64 letters, digits, `-` or `_`, never repeated) and `X-Platform-Signature: sha256=<hex>`, where the hex is the HMAC-SHA256,
keyed with the **value** of the credential named in the connection's setting `inboundCredential`, of the text `v1`, a line feed, the time, a line
feed, the nonce, a line feed, the entity, a line feed and then the exact bytes of the body. Any failure of the proof (unknown connection,
switched off, malformed headers, stale time, replay, wrong signature) is the same `401`. To rotate the signing value, write a new version of the
credential and give the external system the new value; the connection keeps running and the old value stops working at once.

**Public endpoint.** The receiver needs no sign-in and sits on the platform's own address. Put a size limit and an address-based rate limit at the
proxy as for every public path; the application also refuses a body over `platform.integration.flow.max-inbound-bytes` (256 KiB) and allows
`inbound-per-minute-per-source` (120) calls a minute from one address (per instance).

**Settings** `platform.integration.flow.*`: `max-concurrent-sends` (16), `send-timeout` (10s), `max-request-bytes` (256 KiB),
`max-response-bytes` (64 KiB), `max-inbound-bytes` (256 KiB), `replay-window` (5m), `inbound-per-minute-per-source` (120),
`delivery-retention` (30d), `stale-after` (10m).

**Metrics and audit.** `platform.integration.delivery{direction,result,reason}`, `platform.integration.send.duration{result}`,
`platform.integration.outbound.events{result}`, `platform.integration.inbound{result}`, `platform.integration.mapping{action}`. Audit
`integration.mapping.drafted|discarded|published|stopped` (identifiers and the number of fields) and the `data.record.*` records the receiver's
writes leave (source `channel=inbound`, the connection's identifier). Never a record value, a payload, an external identifier, a header or a signature.

## Reliability: the queue, an outage, the log, replay and the circuit (Sprint 29)

Decisions in ADR-0227 to ADR-0236. A change to a record becomes a **delivery**; the worker (any instance) sends it, tries
again after a failure that may pass, and sets it aside when it will not succeed. Nothing is dropped.

**What a delivery can be.** Waiting (with the reason it waits for), being sent, waiting for the other side (a long-running
job), done, skipped (nothing to do, with the reason), replaced (a later change of the same record took its place), or set
aside (a dead letter). Order within one record is kept: a later change never overtakes an earlier one that still waits,
except that a waiting *update* is replaced by a newer update or removal, because a send always reads the record as it is
when it is made.

**An outage of the other system.** Failures in a row (5) open the circuit; while it is open nothing is sent and every change
waits with the reason *circuit open*. After 60 seconds one probe is sent; if it works the circuit closes and the queue drains in
order, if not it opens again. The screen of the connection (Setup, Integrations, the connection, *Monitoring*) shows how
sending stands in words, how many changes wait, are being sent or are set aside, when the longest wait began, and the last
success and failure.

1. **See it:** the Monitoring section, or `GET /api/v1/integrations/<name>/health` (one connection) and
   `GET /api/v1/integration-health` (all). The same counts are in the metrics and in the automation view
   (Setup, Automation health; the platform console sees them without any name).
2. **Pause on purpose:** *Pause sending* (ability `integrations.operate`) forces the circuit open. Nothing is sent, nothing is
   lost. *Back to automatic* returns control to the platform and forgets the failures counted so far. *Keep sending* forces it
   closed (use it only when a failure is known to be on the platform's side).
3. **Read the log of a change:** *Attempts* on a change set aside (or `GET .../deliveries/<id>/attempts`). Each attempt has its
   time, duration, how it ended, a stable reason in the platform's words and a class of status (`2xx` to `5xx`, or none). It holds
   no payload, value, address or identifier of the other system, by design.
4. **Send a change again:** *Send again* on one change set aside, or *Send all again* for the oldest 100 (ability
   `integrations.replay`). A replay reads the record as it is now, makes a new attempt series, and does not repeat what the link
   says was done on the other side. Two clicks make one. A change set aside as `queue_full` is replayable too, once the queue has room.
5. **Slow a connection down:** the limits form (ability `integrations.operate`) sets sends per minute and sends at once, never
   above the platform's ceilings. A change that waits for its turn shows the reason *throttled*; it does not fail.

**What is promised, and what is not.** Every event a connection listens to ends in a delivery that is done, skipped, replaced,
set aside or still waiting; a restart loses nothing (a held delivery comes back when its lease of 2 minutes ends and is sent with the
same key). With a service that honours the repeat-safety header (the REST connector sends `Idempotency-Key`; the SOAP connector
sends the one the connection names) a create whose answer was lost makes one entity. With a service that does not, a create
whose answer was lost *may* make a second entity, because the platform learns the identifier only from the answer.

**Settings** `platform.integration.reliability.*` (every number inside a ceiling checked at start): `enabled` (true),
`poll-interval` (1s), `batch-size` (100), `per-organization` (20), `lease` (2m); `retry.max-attempts` (5), `initial-delay` (5s),
`multiplier` (2), `max-delay` (15m), `jitter` (0.2), `retry-after-ceiling` (1h); `circuit.failure-threshold` (5), `open-for` (60s),
`probes` (1), `probe-timeout` (2m); `queue.max-per-connection` (10,000), `idle-recheck` (30s); `rate.default-per-minute` (6,000),
`max-per-minute` (60,000), `default-concurrent` (4), `max-concurrent` (32); `remote.poll-interval` (15s), `deadline` (1h).
Run the worker on one instance only by setting `enabled=false` on the others; the queue is shared, so one is enough.

**Metrics.** `platform.integration.attempts{direction,result,reason}`, `circuit{state}`, `gate{result}`, `queue.depth`, `queue.age`,
`throttled`, `dead`, `superseded`, `replayed{mode}`, `send.duration{result}`. Alert on `queue.age` growing while `circuit` is closed
(a stuck worker), on `dead` rising (changes set aside), and on `circuit{state=open}` for a connection that matters.

**Abilities.** `integrations.view` (see), `integrations.replay` (send again), `integrations.operate` (force the circuit, set the
limits), all apart from `integrations.manage`. An organization administrator holds all of them; a platform role holds none.

## File transfer: sending and picking up files (Sprint 29A)

Decisions in ADR-0237 to ADR-0243. A connection of the type *File transfer (SFTP)* exchanges files with a server over a
secure shell session. It writes **one file for each change** into an outbox directory and **picks up the files** that arrive
in an inbox directory. It uses the same queue, retries, breaker, limits and log as every other connection.

**Setting one up.**

1. Get from the server's owner the **fingerprint** of its public key (`SHA256:` and 43 characters, as the usual tools print
   it), the five directories (outbox, inbox, processing, done, error: all must exist, all different) and a user with
   read and write rights in them. The platform never deletes anything on the server.
2. Write the sign-in as a credential (Setup, Credentials): `user:password`, or the user name on the first line and the private
   key (text form, no passphrase) on the lines after it.
3. Make the connection: address `sftp://host` (or `sftp://host:port`), the way to sign in, the fingerprint, the format (CSV
   with a header line, or one JSON object per line), the directories, and the **entity** the inbox holds (leave it empty to
   send only). Make a mapping of that entity (Setup, the connection, Mappings) and publish it: files are not picked up
   until there is a live mapping.
4. **Test** it, then switch it on. A test visits the server, checks the key, signs in and looks into every directory; it
   writes nothing. A missing right to write shows at the first send, as `permission_denied`.

**Files going out.** A name is `d-<delivery>-g<generation>.csv` (or `.jsonl`), never made from a value of a record. The file
is written first under a name that starts with a dot and ends in `.part` and is renamed when it is complete, so **the
receiver must ignore names that start with a dot**. A repeat after a lost answer finds the file already there with the right
size and does not write it again; a receiver that removes files promptly should deduplicate on the name. The first columns
are the identifier (the record's own) and the operation (`create`, `update`, `delete`).

**Files coming in.** Every `pollMinutes` (default 5, never under the platform's floor of 1 minute) the platform looks at the
inbox. A file whose name is plain, has the ending of the format and is an ordinary file is renamed into the processing
directory (so two instances never take one), read, **checked as a whole against the mapping**, and applied in batches. A file
that is fine ends in the **done** directory; a file that is bad ends in the **error** directory and **nothing of it is
applied**. The name there is `<id>__<original name>`.

1. **See it:** the Monitoring section of the connection (*Inbox last looked at*, *What the last look found*, *Next look*,
   files waiting, being applied, applied, set aside), or `GET /api/v1/integrations/<name>/health` (`files`). The automation
   view counts files waiting and set aside across the organization.
2. **A file in the error directory:** the connection's count *Files set aside* says how many; the reason is in the audit trail
   (`integration.file.processed`, with counts) and never names the file. Open the file on the server, find what is wrong
   (no identifier column, a row without an identifier, too many rows, not text, a value the mapping refuses), correct it
   and put it **back in the inbox**: a file that was set aside is tried again. A file with the reason
   `file_rows_refused` was applied except for the rows the record store refused (a locked record, a rule of the
   organization); correcting and putting back is safe because a repeated row is an update.
3. **The same file twice** (same name, size and time) is applied once and moved to done; to apply it again, change it.
4. **An inbox that stops moving:** check, in order: the connection is on (not *failing*); its mapping is live; the last look's
   reason (*circuit open* means the server failed repeatedly: the platform looks again by itself; *no mapping*; *throttled*);
   files that are not picked up by design (a name with a space, a dot first, another ending, a link, a directory) stay
   where they are; the size of the file against the platform's limit (25 MiB) and the rows against 10,000.
5. **A file stuck in the processing directory:** a look that was cut off leaves it there on purpose; the next look resumes at
   the first row that was not written and then moves it. Do not move it by hand. If the connection stays off for long, the
   file waits.

**A host key change.** If the server's key changes (a rebuilt server, a new certificate authority, a man in the middle) the
platform stops the connection: it signs in to no server whose key is not the pinned one and does not try again. The connection
shows *on, but failing* with the reason *host key mismatch*; what was to be sent waits. Find out why the key changed. If it is
expected: switch the connection off, edit it and pin the new fingerprint, test it, switch it on. If not, leave it off.

**Rotating the credential.** Write the new value under the same name (Setup, Credentials); the connection keeps running and
shows the last test as voided. If the old credential is revoked first, the platform stops the connection after one refusal
(*authentication rejected*) and does not try again with it; write the new one, run a test, and the queue resumes.

**Sizes and limits.** `platform.integration.files.*`: `enabled` (true; env `PLATFORM_INTEGRATION_FILES_ENABLED`),
`max-concurrent-polls` (4), `lease` (10m), `deadline` (5m), `min-interval` (1m), `max-files` (50 a look), `max-listed` (2000),
`max-file-bytes` (25 MiB), `max-rows` (10,000), `batch-rows` (200), `ledger-retention` (30d). **Done and error directories
grow:** the platform deletes nothing on the server, so tidy them on your own schedule (the platform recognises a file that
returns only within the ledger retention).

**Network.** The server must be reachable from the platform on its port (default 22) from the addresses the platform uses.
The address must be a public host; only a development host named in `platform.integration.egress.development-hosts` may be
private, and the `prod` profile refuses to start with any.

**Metrics.** `platform.integration.files.polls{result,reason}` (looked, held, failed, duplicate, error), `files{result,reason}`
(done, set_aside), `files.rows{result}` (applied, skipped, refused), `stopped{reason}`. Alert on `files{result=set_aside}`
rising, on `polls{result=failed}` for a connection that matters, and on `stopped`.

## What to do when

- **Changes are `waiting` with *circuit open*:** the service failed repeatedly. Check the service; the platform tries one probe
  every 60 seconds and resumes by itself. Nothing needs to be done unless the service stays down: then the queue fills towards
  its bound (10,000), after which new changes are set aside as `queue_full` and can be sent again later.
- **Changes are set aside with `rejected`, `authentication_rejected` or `payload_unwritable`:** the other side refuses them or the
  mapping cannot write them. Mend the cause (the service, the credential, the mapping), then *Send again*.
- **Changes wait with *throttled* for long:** the limit is lower than the load; raise it (inside the ceiling) or accept the delay.
- **Changes wait with *connection off*:** the connection is switched off and holds its queue; switch it on to go on.
- **Many `unauthenticated` on `platform.integration.inbound`:** a sender with the wrong signing value, a clock far from true time, or a probe. The answer is the same for all, by design; check the credential's last change and the sender's clock.
- **Deliveries set aside with `rejected` (older ones: `FAILED`):** the external system answered with a refusal; look at its side. `authentication_rejected` is a 401 or 403.
- **Deliveries `SKIPPED` with `echo`:** expected: a change that came from this connection is not sent back.
- **A change refused with `write_record_locked`:** the record is under approval; the sender may try again after the approval ends.

- **Every test of every organization fails with `name_not_found`:** the application's DNS. Check the resolver of the host.
- **`credential_store_unavailable`:** the store could not open the credential; see [secrets.md](secrets.md). A test whose last result reads `credential_changed` only needs to be run again after a credential was replaced.
- **A connection is stuck `FAILING`:** run a test; if it passes it is `ENABLED` again; if the service is gone, switch it off.
- **An administrator cannot change a connection:** it is on. Switch it off first.
- **A file connection is `failing` with *host key mismatch* or *authentication rejected*:** the platform stopped it on purpose
  and waits for a person; see *File transfer* above. Nothing waiting is lost.
- **Files wait in the inbox and the last look says *no mapping*:** publish a mapping of the entity the connection names.
