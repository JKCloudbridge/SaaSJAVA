-- Reminders and escalation of approvals, and the end of a trial (Sprint 25, ADR-0159 and ADR-0162). Expand only: columns
-- with defaults, new tables, widened checks; nothing is removed or renamed, so the previous release keeps working.
--
--   approval_process_version  gains "remind after" and "escalate after" (hours). A change of either is a new, immutable
--                             version of the process like any other change of the definition (ADR-0145).
--   approval_step_timer       The timer of an active step: when it next looks at the step, how many reminders were
--                             sent, when the step was escalated and when it was marked overdue. A table of its own, not
--                             columns of the step: claiming a timer raises a version, and the step's version is what the
--                             decisions of people compare, so a claim must never make a decision fail.
--   approval_reminder         One row per reminder, escalation or overdue mark of a step. The unique key is what makes
--                             "never twice for the same step and time" true whichever instance runs the timer and
--                             however often it runs.
--   subscription_notice       One row per subscription and stage of the end of a trial (platform-level, like the
--                             subscription): the marker that makes each notice, and the suspension, happen once.
--
-- The timer is claimed across organizations like a schedule of a workflow is (ADR-0134, ADR-0135): by a named system
-- scope, `approval_timers`, that the policies of approval_step_timer admit for reading and claiming and that no other
-- table admits (ADR-0159). The work itself always runs inside the organization's own tenant context.

-- ---------------------------------------------------------------------------------------------------------------
-- approval_process_version
-- ---------------------------------------------------------------------------------------------------------------

alter table approval_process_version
    add column remind_after_hours   integer,
    add column escalate_after_hours integer;

alter table approval_process_version add constraint approval_process_version_remind_bounded
    check (remind_after_hours is null or remind_after_hours between 1 and 8760);
alter table approval_process_version add constraint approval_process_version_escalate_bounded
    check (escalate_after_hours is null or escalate_after_hours between 1 and 8760);
-- An escalation asks the fallback approver, so a process that escalates names one.
alter table approval_process_version add constraint approval_process_version_escalate_needs_fallback
    check (escalate_after_hours is null or fallback is not null);

comment on column approval_process_version.remind_after_hours is
    'Hours an active step may wait before the people asked are reminded; null for never (ADR-0159).';
comment on column approval_process_version.escalate_after_hours is
    'Hours an active step may wait before the fallback approver is asked as well, once; null for never (ADR-0159).';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_step_timer
-- ---------------------------------------------------------------------------------------------------------------

create table approval_step_timer (
    id             uuid        primary key default uuidv7(),
    tenant_id      uuid        not null default platform_current_tenant() references tenant (id),
    instance_id    uuid        not null references approval_instance (id),
    step_id        uuid        not null references approval_step_instance (id),
    -- When the timer next looks at the step; null once nothing remains to do or the step has ended. Moving it ahead
    -- when the row is claimed is the lease (ADR-0159).
    next_timer_at  timestamptz,
    reminders_sent integer     not null default 0,
    escalated_at   timestamptz,
    overdue_at     timestamptz,
    -- When the step stopped being active: the timer row stays as the record of what was done, and this tells apart an
    -- overdue step that is still waiting from one that was decided late.
    ended_at       timestamptz,
    version        bigint      not null default 0,
    created_at     timestamptz not null default now(),
    created_by     uuid        not null,
    updated_at     timestamptz not null default now(),
    updated_by     uuid        not null,
    deleted_at     timestamptz,
    deleted_by     uuid,
    constraint approval_step_timer_reminders_bounded check (reminders_sent between 0 and 20)
);

create unique index approval_step_timer_tenant_step on approval_step_timer (tenant_id, step_id)
    where deleted_at is null;
-- What the timer claims: the steps whose time has come, oldest first, of whichever organization has them.
create index approval_step_timer_due on approval_step_timer (next_timer_at, tenant_id)
    where next_timer_at is not null and deleted_at is null;
-- The overdue steps of one organization (the failure view and the lists of an administrator).
create index approval_step_timer_tenant_overdue on approval_step_timer (tenant_id, overdue_at)
    where overdue_at is not null and ended_at is null and deleted_at is null;

create trigger approval_step_timer_row_guard before insert or update on approval_step_timer
    for each row execute function platform_row_guard();
create trigger approval_step_timer_tenant_guard before update on approval_step_timer
    for each row execute function platform_tenant_guard();

alter table approval_step_timer enable row level security;
alter table approval_step_timer force row level security;
create policy approval_step_timer_insert on approval_step_timer for insert
    with check (tenant_id = (select platform_current_tenant()));
-- The scope may read and claim the timer rows of every organization, and nothing else (ADR-0159).
create policy approval_step_timer_select on approval_step_timer for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('approval_timers'));
create policy approval_step_timer_update on approval_step_timer for update
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('approval_timers'))
    with check (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('approval_timers'));
create policy approval_step_timer_delete on approval_step_timer for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_step_timer is
    'The timer of an active approval step: the next reminder or escalation, what was done so far (ADR-0159). Tenant-scoped; the approval_timers system scope may read and claim across organizations. Never holds a value of a record.';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_history learns the three new lines
-- ---------------------------------------------------------------------------------------------------------------

alter table approval_history drop constraint approval_history_kind_known;
alter table approval_history add constraint approval_history_kind_known check (kind in (
    'SUBMITTED', 'STEP_ACTIVATED', 'TASK_APPROVED', 'STEP_APPROVED', 'APPROVED', 'REJECTED', 'RECALLED',
    'CANCELLED',
    'ASSIGNEE_DROPPED', 'REASSIGNED', 'ASSIGNED', 'ATTENTION_RAISED', 'ATTENTION_CLEARED',
    'OUTCOME_APPLIED', 'OUTCOME_FAILED', 'OUTCOME_REAPPLIED', 'LOCK_OVERRIDDEN',
    'REMINDER_SENT', 'ESCALATED', 'OVERDUE_MARKED')) not valid;
alter table approval_history validate constraint approval_history_kind_known;

-- ---------------------------------------------------------------------------------------------------------------
-- approval_reminder
-- ---------------------------------------------------------------------------------------------------------------

create table approval_reminder (
    id           uuid        primary key default uuidv7(),
    tenant_id    uuid        not null default platform_current_tenant() references tenant (id),
    instance_id  uuid        not null references approval_instance (id),
    step_id      uuid        not null references approval_step_instance (id),
    kind         text        not null,
    sequence     integer     not null,
    version      bigint      not null default 0,
    created_at   timestamptz not null default now(),
    created_by   uuid        not null,
    updated_at   timestamptz not null default now(),
    updated_by   uuid        not null,
    deleted_at   timestamptz,
    deleted_by   uuid,
    constraint approval_reminder_kind_known check (kind in ('REMINDER', 'ESCALATION', 'OVERDUE')),
    constraint approval_reminder_sequence_positive check (sequence between 1 and 20)
);

-- THE ONCE (ADR-0159): one reminder of a number, one escalation, one overdue mark, per step.
create unique index approval_reminder_tenant_step_once on approval_reminder (tenant_id, step_id, kind, sequence)
    where deleted_at is null;
create index approval_reminder_tenant_instance on approval_reminder (tenant_id, instance_id);

create trigger approval_reminder_row_guard before insert or update on approval_reminder
    for each row execute function platform_row_guard();
create trigger approval_reminder_tenant_guard before update on approval_reminder
    for each row execute function platform_tenant_guard();

alter table approval_reminder enable row level security;
alter table approval_reminder force row level security;
create policy approval_reminder_insert on approval_reminder for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_reminder_select on approval_reminder for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_reminder_update on approval_reminder for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_reminder_delete on approval_reminder for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_reminder is
    'What the timer did to a step of an approval, once: a numbered reminder, the escalation, the overdue mark (ADR-0159). Never holds a value of a record.';

-- ---------------------------------------------------------------------------------------------------------------
-- subscription_notice (platform-level)
-- ---------------------------------------------------------------------------------------------------------------

create table subscription_notice (
    id              uuid        primary key default uuidv7(),
    bound_tenant_id uuid        not null references tenant (id),
    subscription_id uuid        not null references subscription (id),
    stage           text        not null,
    -- The end of the trial this marker belongs to: a trial that is extended starts its notices again (ADR-0162).
    trial_ends_at   timestamptz not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint subscription_notice_stage_known check (stage in ('NOTICE_FIRST', 'NOTICE_FINAL', 'SUSPENDED'))
);

create trigger subscription_notice_row_guard before insert or update on subscription_notice
    for each row execute function platform_row_guard();
-- THE ONCE (ADR-0162): each stage of the end of a trial happens once per subscription, whichever instance runs the job.
create unique index subscription_notice_once on subscription_notice (subscription_id, stage, trial_ends_at)
    where deleted_at is null;
create index subscription_notice_tenant on subscription_notice (bound_tenant_id);

comment on table subscription_notice is
    'Which stage of the end of a trial happened for a subscription: the notices and the suspension, once each. Platform-level like subscription (ADR-0031, ADR-0162).';

-- ---------------------------------------------------------------------------------------------------------------
-- workflow_execution: the index of the fair share (ADR-0164)
-- ---------------------------------------------------------------------------------------------------------------

-- The claim visits the organizations that have something due, then takes each one's oldest rows: one probe by organization
-- and age. The index of V053 (by age alone) keeps serving the counts.
create index workflow_execution_due_by_tenant on workflow_execution (tenant_id, run_at, id)
    where status in ('QUEUED', 'RUNNING') and deleted_at is null;
