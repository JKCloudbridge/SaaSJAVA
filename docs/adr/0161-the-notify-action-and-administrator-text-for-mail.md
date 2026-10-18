# ADR-0161: The `NOTIFY` action, and administrator text for `SEND_EMAIL`

- **Status:** Accepted
- **Date:** 2026-10-18
- **Sprint:** S25
- **Related:** [ADR-0138](0138-workflow-actions-and-the-shape-of-their-settings.md), [ADR-0141](0141-idempotency-exactly-once-and-replay.md),
  [ADR-0142](0142-retry-policy-and-backoff.md), [ADR-0144](0144-what-sprint-23-leaves-out-and-why.md), [ADR-0154](0154-the-notification-model-in-app-notices.md),
  [ADR-0155](0155-the-text-of-notices-and-mails.md)

## Decision

1. **`NOTIFY` is one more action type of the closed list** (`WorkflowActionTypes`, executor `NotifyAction`): it makes one in-app notice,
   kind `WORKFLOW_NOTICE`, about the triggering record, in the transaction of the step, through the `Notices` contract. Settings: `recipient`
   (`OWNER`, the owner of the record, or `FIELD`) and `recipientField` (for `FIELD`: a field of the workflow's object that links to a user;
   required for `FIELD`, refused for `OWNER`). Every field it names is an edge of the dependency graph, so removing the field is refused
   naming the workflow.
2. **A named person or a group is not a recipient.** Either would put an identifier of a person or a group inside a definition, and the
   owner and a user field already say "whoever the record names", which survives people leaving. If a requirement names a group, it is a new
   setting with its own bounded expansion.
3. **Facts only.** The notice holds the kind, the API name of the workflow, and the object and identifier of the record. The words come from
   the template of `WORKFLOW_NOTICE` (ADR-0155), built when read. No value of the record, not even its title.
4. **Once across retry and replay.** The key is `wf:` and the idempotency key of the step (`execution:position:generation`, ADR-0141): a
   retry makes the same request and gets nothing new (and the failed attempt had rolled back anyway); a replay is a new execution and makes a
   new, intended notice. A recipient who switched these notices off gets none and the step **completes** (a preference is not a failure).
5. **Stable failure reasons**, all permanent (no retry can mend a data fact): `object_unavailable`, `record_gone`, `recipient_field_gone`,
   `recipient_missing`, `recipient_not_member`. The reason, never a message, is stored on the step.
6. **`SEND_EMAIL` uses the administrator's text when there is one.** The mail request gains the label of the workflow (a fact); at send time
   the relay uses the body written for `WORKFLOW_NOTICE` if any, else the platform's own text, under the recipient's mail preference. The
   subject, the link and the footer stay the platform's (ADR-0155). The record notice mail and the workflow notice share one template on
   purpose: a person is told one thing in two channels.
7. **No limit of notices per hour of its own.** A notice is a small row with no external effect; the rate guard of executions (ADR-0140)
   and the retention bound it. The mail keeps its hourly limit (ADR-0138).

## Consequences

- A workflow can tell a member in the application and by mail, in words an administrator chose, without ever carrying a value.
- The catalogue endpoint draws the new type and its settings; the screen holds no list.

## Alternatives considered

- **A notice and a mail from one action:** two actions are clearer to read and to retry independently.
- **The title of the record in the notice:** rejected for the reason of ADR-0155.

## References

- `platform-app/.../workflow/internal/{NotifyAction,SendEmailAction}.java`; `platform-api-contract/.../WorkflowActionTypes.java`
