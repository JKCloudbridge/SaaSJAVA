# Runbook: webhooks

For the person who operates the platform and for an organization's administrators. What a customer does with a webhook is in
[../public-api.md](../public-api.md) (section 13); the decisions are in ADR-0274 to ADR-0279 and ADR-0284.

## Settings

| Setting (environment variable) | Default | What it does |
|---|---|---|
| `platform.webhooks.enabled` (`PLATFORM_WEBHOOKS_ENABLED`) | `true` | `false` is the kill switch: this instance queues nothing and sends nothing, and the screens still work. Events that happen while it is off are **not** queued for webhooks and are not delivered later |
| `platform.webhooks.max-subscriptions` | `50` | webhooks one organization may have |
| `platform.webhooks.disable-after` | `7d` | an address that fails without one success for this long is switched off by the platform (1 hour to 90 days) |
| `platform.webhooks.max-overlap` | `24h` | the longest an old signing secret may keep signing after a replacement (the database holds nothing longer, the API refuses more) |
| `platform.webhooks.worker.poll-interval`, `.batch-size`, `.per-organization`, `.lease`, `.max-concurrent-sends` | `1s`, `100`, `20`, `2m`, `16` | how the queue is worked; `per-organization` is the fair share of one organization in one claim |
| `platform.webhooks.delivery.send-timeout`, `.max-in-flight`, `.per-second` | `10s`, `10`, `20` | one attempt's deadline, calls to one address at once, deliveries a second to one address |
| `platform.webhooks.retry.*`, `platform.webhooks.circuit.*` | Sprint 29's | max attempts 5, 5 s, x2, 15 min, jitter 0.2; the breaker opens after 5 failures in a row for 60 s |
| `platform.webhooks.queue.max-per-subscription`, `.idle-recheck` | `10000`, `30s` | the queue bound of one webhook, and how soon a held delivery is looked at again |
| `platform.webhooks.retention.deliveries`, `.batch` | `30d`, `500` | how long an ended delivery and its attempts are kept (never fewer than 1 day) and the size of one clean-up |
| `platform.webhooks.test.min-interval`, `.max-concurrent` | `5s`, `4` | one test of a webhook every so often; tests at once on an instance |

Every setting has a safe default; the `local` profile starts unchanged. The plan feature `integrations` must be on for the
organization; the abilities are `webhooks.view`, `webhooks.manage` and `webhooks.replay`.

## Switching it off, and back

Set `PLATFORM_WEBHOOKS_ENABLED=false` and restart (or roll) the instances. Nothing is sent. Switch it on again the same way;
deliveries queued before are sent then (events of the pause are not). To stop **one** organization, switch the plan feature
`integrations` off for it in the console: the endpoints refuse and what is due is held (`plan_off`). To stop **one** webhook, an
administrator of the organization switches it off.

## Reading it

Metrics (micrometer), **never a tag of an organization, a webhook or an address**:

- `platform.webhook.events{result}`: what the handler did with an event (`handled`, `no_subscriber`, `no_feature`, `switched_off`,
  `malformed`). A steady `malformed` is a defect of the publisher.
- `platform.webhook.deliveries{result}`: `queued`, `duplicate`, `queue_full`.
- `platform.webhook.attempts{result,reason}`: `done`, `retry`, `dead_lettered`, `skipped`, with a stable reason.
- `platform.webhook.skipped{reason}`: `not_visible`, `member_inactive`, `object_not_listed`, `subscription_removed`.
- `platform.webhook.queue.depth`, `.queue.age` (seconds the oldest due delivery has waited): the health of the worker. A depth
  that grows with an age over ten minutes is a worker that is not keeping up or is not running.
- `platform.webhook.gate{result}` (`circuit_open`, `busy`, `throttled`), `.circuit{state}`, `.disabled` (subscriptions switched off
  by the platform), `.superseded`, `.replayed`, `.purged`, `.send.duration{result}`, `.tests{result}`, `.secrets{result}`.

The failure view of automation (Setup, Automation health, and the platform console) has a "Webhooks" section: what waits, what
is set aside, how many webhooks are failing or were switched off, and how old the longest wait is.

The audit trail has `webhooks.created|updated|tested|enabled|disabled|removed|secret_rotated|replayed|auto_disabled` and
`webhooks.delivery.dead_lettered`, by identifiers. No address, header, signature, body or secret is ever in a log, a record or a
metric.

## A webhook that keeps failing

1. Open the webhook: the state (`Failing`), the circuit, the last failure and the delivery log with the platform's sentence for
   each reason. `connect_failed`, `timed_out`, `name_not_found` and `server_error` are the receiver's; `rejected` (a `4xx`) is the
   receiver refusing the message; `address_not_allowed` is an address the platform may not call.
2. Send a test. If it fails the same way, the receiver is down or refuses; the customer fixes it.
3. When it answers again, the breaker closes by itself after its probe and the queue drains. Deliveries that were set aside
   (`Set aside`) are sent again with the replay buttons (needs `webhooks.replay`).
4. A webhook the platform switched off after seven days (`auto_disabled`) is not deleted: send a test, then switch it on.

## A webhook whose member left

The webhook shows `Stopped: its member is no longer active` and sends nothing; deliveries that become due are set aside as
`member_inactive`. If the person is made active again it runs again. Otherwise remove it and make a new one as a member who can
act (the practice is a dedicated integration member).

## A leaked signing secret

Make a new signing secret with an overlap of **0**: the old one is removed from the store at once and no later delivery carries
a signature of it. The new one is shown once; give it to the receiver. If the receiver needs time, use an overlap and stop the old
secret by hand when the change is made (Stop the old secret now).

## What a Redis outage does

Nothing to webhooks: the queue is in PostgreSQL. (The limits on the public API degrade, see rate-limits.md.)
