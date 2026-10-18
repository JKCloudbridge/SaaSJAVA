# ADR-0138: Workflow actions that change the world, and the shape of their settings

- **Status:** Accepted
- **Date:** 2026-10-17
- **Sprint:** S23
- **Related:** [ADR-0136](0136-workflow-definitions-versions-and-lifecycle.md), [ADR-0137](0137-executions-asynchronous-after-commit-and-what-they-keep.md),
  [ADR-0024](0024-mail-queue-and-notification-v0.md), [ADR-0139](0139-background-writes-the-one-reviewed-path.md),
  [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md)

## Context

Sprint 22 shipped one harmless action (`PUBLISH_EVENT`). The delivery plan lists seven kinds for Sprint 23: update a record, create a
record, notify, send e-mail, start an approval, publish an event, call an integration. Three of them cannot be carried out yet: *start
approval* needs the approval engine (Sprint 24), *notify* (in-app) needs the notification module with templates and preferences
(Sprint 25), *call integration* needs the connector framework (Milestone M7). Since Sprint 22 the rule is that a type nothing can
carry out is not accepted (no inert placeholder).

## Decision

1. **Four types ship:** `UPDATE_RECORD`, `CREATE_RECORD`, `SEND_EMAIL` and `PUBLISH_EVENT`. The other three are named in
   [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md) with what they wait for. A definition that names them is refused with that
   list, as an unknown type is today.
2. **A value is closed data, never text that is run.** An action that sets fields carries `assignments`, a list of
   `{field, value}` or `{field, fromField}`: a **literal** or a **copy of a field of the triggering record**. A literal is written as
   text **exactly as the default value of a field is** (the same rules and the same parser: `true`, `2030-01-31`, a number as its
   digits, a multi-select as values separated by semicolons), so there is one way to write a typed constant in the platform and
   one check of it. A reference field (lookup, master-detail) is never given a literal: it is copied from a reference field of the
   same target object, or set by `linkField` of a create. A copy of a field that has no value empties the target (on an update) or
   leaves it out (on a create). No expression is a value in this sprint. A `WORKFLOW_VALUE` purpose of the expression engine would
   widen the sandbox (ADR-0076, ADR-0081) and the cost of a check; it is a decision of its own and is not taken here.
3. **The settings of the four types**, all checked by `WorkflowRules` at save and by the dependency graph after the change:

   | Type | Settings | Assignments | What it does |
   |------|----------|-------------|--------------|
   | `UPDATE_RECORD` | none | the fields of the triggering record's object to set (1 to the limit) | sets those fields on the triggering record |
   | `CREATE_RECORD` | `object` (API name), `linkField` (optional) | the fields of `object` to set | creates a record of `object`; `linkField`, a lookup or master-detail field of `object` that points to the triggering object, is set to the triggering record |
   | `SEND_EMAIL` | `recipientField` (a user reference or an e-mail field of the triggering object), `template` (`RECORD_NOTICE`) | none | queues one platform mail to the person the record names |
   | `PUBLISH_EVENT` | `event` | none | unchanged from Sprint 22 |

4. **Every action may carry `maxAttempts`** (1 up to the platform cap, [ADR-0142](0142-retry-policy-and-backoff.md)): a definition may lower
   the attempts of an action and never raise them.
5. **Every field an action names is an edge of the dependency graph** exactly as a condition's fields are: removing a field, or an
   object, that a workflow names is refused naming the workflow, live and in a change set, and a rollback restores in an order that
   satisfies them. That covers the assigned fields, the copied fields (`fromField`), `recipientField`, `linkField` and the `object` of
   a create. The *type* is checked against the value at save and after every change: a literal must fit the field's type and its
   picklist; a copy must be of a type the target accepts; the fields of a created record that are required (and have no default and no
   assignment) are named as a problem; `recipientField` must be a user reference or an e-mail field; a calculated, system, auto-number
   or retired field cannot be assigned.
6. **Limits** (`platform.workflow.limits.*`, also given to the browser by `GET /api/v1/metadata/workflows/action-types`, so the browser
   holds no list of types, no setting name and no limit): actions per workflow (10), assignments per action (25), length of a literal
   (1000 characters), workflow mails per organization per hour (500).
7. **The mail** is a new closed platform template `RECORD_NOTICE` ([ADR-0141](0141-idempotency-exactly-once-and-replay.md) for how it is
   queued). It says that a record of an object, named by the object's label, needs attention in an organization, named by its name,
   and links to the record on the organization's own host. **It carries no value of the record, not even its title**: the mail leaves
   the platform's control and the queue row would hold a value for as long as the mail is retained. The link opens the record, where
   the permissions of whoever signs in apply. The recipient is a **member** (a user reference whose user has an active membership) or
   an address in an e-mail field; the mail says nothing a stranger could use but the organization's name and the object's label.

## Consequences

- One executor class per type; the registry still refuses to start on a mismatch (a unit test holds the schema list and the executors
  together).
- The stored form of an action gains `assignments` and `maxAttempts` (additive; Sprint 22 definitions read as before, with none).
- An administrator writes values as typed data, never as formulas; richer values are a later, separate decision.
