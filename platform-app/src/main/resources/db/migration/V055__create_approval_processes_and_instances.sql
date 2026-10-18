-- Approval processes, their versions, the requests made under them, their steps, tasks and history, and delegations
-- (Sprint 24, ADR-0145 to ADR-0153).
--
-- Definitions are PUBLISHED metadata like workflow: a row of approval_process is a process of an object. Drafts stay in the
-- change-set tables of V033 (ADR-0066). The runtime tables belong to the approval module.
--
--   approval_process          The identity of a process (object, API name), its label, whether new requests may start under
--                             it and the number of the definition in force. One per object and API name.
--   approval_process_version  One immutable definition (entry criteria, steps, outcome updates, fallback). A new row only
--                             when the definition changed; old rows stay while an instance names them (ADR-0145).
--   approval_instance         One request for one record under one version of one process. At most one is PENDING for a
--                             record (partial unique index): a PENDING instance IS the lock of the record (ADR-0148), so
--                             an unlock cannot be lost and a crash cannot leave a record locked.
--   approval_step_instance    One step of an instance; exactly one is ACTIVE while the instance is PENDING.
--   approval_assignment       One task: a person asked to decide an active step. Resolved when the step becomes active and
--                             never changed by a later change of a group or a role (ADR-0146).
--   approval_history          Append-only: who did what and when, the stable reason, and the comment of a person (bounded,
--                             shown only to people who may see the history, never logged). Never a record value.
--   approval_delegation       A person names a delegate and a range of days; the delegate is added beside them (ADR-0151).
--
-- What must hold even if the application had a bug:
--   * the identity of a process (API name, object) never changes, and a version is never changed;
--   * at most one instance of a record is PENDING; a PENDING instance has a current step and an unfinished time, an ended
--     one has an end time and no current step;
--   * exactly one step of a PENDING instance is ACTIVE, and a step has at most one task per person;
--   * the history cannot be changed or removed, by anyone;
--   * the texts, the names and the reasons are bounded and shaped.
--
-- The change set table of V033 learns the three kinds of change of approval processes. Widening a check is backward
-- compatible; nothing is removed. No table here decides what a member may do, so none needs the security version
-- trigger (ADR-0053): a task is authority only while the instance is PENDING and the person holds an open assignment,
-- and that is read live.

-- ---------------------------------------------------------------------------------------------------------------
-- change sets learn the new kinds of change
-- ---------------------------------------------------------------------------------------------------------------

alter table metadata_change drop constraint metadata_change_kind_known;
alter table metadata_change add constraint metadata_change_kind_known check (kind in (
    'CREATE_OBJECT', 'UPDATE_OBJECT', 'DELETE_OBJECT', 'CREATE_FIELD', 'UPDATE_FIELD', 'DELETE_FIELD',
    'CREATE_RECORD_TYPE', 'UPDATE_RECORD_TYPE', 'DELETE_RECORD_TYPE',
    'CREATE_LAYOUT', 'UPDATE_LAYOUT', 'DELETE_LAYOUT',
    'CREATE_RECORD_PAGE', 'UPDATE_RECORD_PAGE', 'DELETE_RECORD_PAGE',
    'CREATE_APPLICATION', 'UPDATE_APPLICATION', 'DELETE_APPLICATION',
    'CREATE_LAYOUT_ASSIGNMENT', 'UPDATE_LAYOUT_ASSIGNMENT', 'DELETE_LAYOUT_ASSIGNMENT',
    'CREATE_VALIDATION_RULE', 'UPDATE_VALIDATION_RULE', 'DELETE_VALIDATION_RULE',
    'CREATE_RECORD_TYPE_ASSIGNMENT', 'UPDATE_RECORD_TYPE_ASSIGNMENT', 'DELETE_RECORD_TYPE_ASSIGNMENT',
    'CREATE_TAB', 'UPDATE_TAB', 'DELETE_TAB',
    'CREATE_CUSTOM_PAGE', 'UPDATE_CUSTOM_PAGE', 'DELETE_CUSTOM_PAGE',
    'CREATE_RECORD_PAGE_ASSIGNMENT', 'UPDATE_RECORD_PAGE_ASSIGNMENT', 'DELETE_RECORD_PAGE_ASSIGNMENT',
    'CREATE_WORKFLOW', 'UPDATE_WORKFLOW', 'DELETE_WORKFLOW',
    'CREATE_APPROVAL_PROCESS', 'UPDATE_APPROVAL_PROCESS', 'DELETE_APPROVAL_PROCESS'));

-- ---------------------------------------------------------------------------------------------------------------
-- workflows learn the trigger "an approval ended" (ADR-0149)
-- ---------------------------------------------------------------------------------------------------------------

-- Added "not valid" and then validated, so the table is scanned without blocking writers (expand only, ADR-0009).
alter table workflow_version drop constraint workflow_version_trigger_known;
alter table workflow_version add constraint workflow_version_trigger_known check (trigger_kind in (
    'RECORD_CREATED', 'RECORD_UPDATED', 'RECORD_DELETED', 'FIELD_CHANGED',
    'EVERY_DAY', 'EVERY_WEEK', 'DATE_TIME', 'DAYS_AFTER_DATE', 'APPROVAL_OUTCOME')) not valid;
alter table workflow_version validate constraint workflow_version_trigger_known;

alter table workflow_execution drop constraint workflow_execution_trigger_known;
alter table workflow_execution add constraint workflow_execution_trigger_known check (trigger_kind in (
    'RECORD_CREATED', 'RECORD_UPDATED', 'RECORD_DELETED', 'FIELD_CHANGED',
    'EVERY_DAY', 'EVERY_WEEK', 'DATE_TIME', 'DAYS_AFTER_DATE', 'APPROVAL_OUTCOME')) not valid;
alter table workflow_execution validate constraint workflow_execution_trigger_known;

-- ---------------------------------------------------------------------------------------------------------------
-- approval_process
-- ---------------------------------------------------------------------------------------------------------------

create table approval_process (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name     text        not null,
    api_name            text        not null,
    label               text        not null,
    description         text        not null default '',
    active              boolean     not null default true,
    definition_version  integer     not null default 1,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint approval_process_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint approval_process_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint approval_process_label_length check (char_length(label) between 1 and 80),
    constraint approval_process_description_length check (char_length(description) <= 500),
    constraint approval_process_definition_version_positive check (definition_version > 0)
);

create unique index approval_process_tenant_object_api_name
    on approval_process (tenant_id, lower(object_api_name), lower(api_name)) where deleted_at is null;
-- The cheap question of the lock guard: does this object have any process at all (ADR-0148).
create index approval_process_tenant_object on approval_process (tenant_id, lower(object_api_name))
    where deleted_at is null;

create trigger approval_process_row_guard before insert or update on approval_process
    for each row execute function platform_row_guard();
create trigger approval_process_tenant_guard before update on approval_process
    for each row execute function platform_tenant_guard();
create trigger approval_process_identity_guard before insert or update on approval_process
    for each row execute function platform_definition_identity_guard();
create trigger approval_process_metadata_version after insert or update or delete on approval_process
    for each row execute function platform_metadata_version_bump();

alter table approval_process enable row level security;
alter table approval_process force row level security;
create policy approval_process_insert on approval_process for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_process_select on approval_process for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_process_update on approval_process for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_process_delete on approval_process for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_process is
    'An approval process of an object: entry criteria, ordered steps, approvers, outcome updates. Published metadata, tenant-scoped (ADR-0015, ADR-0145).';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_process_version
-- ---------------------------------------------------------------------------------------------------------------

create function platform_approval_process_version_immutable() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'approval version guard: a version of an approval process is never changed'
        using errcode = 'check_violation';
end;
$guard$;

comment on function platform_approval_process_version_immutable() is
    'A version of an approval process definition is never changed; a changed definition is a new version (ADR-0145).';

create table approval_process_version (
    id                    uuid        primary key default uuidv7(),
    tenant_id             uuid        not null default platform_current_tenant() references tenant (id),
    process_id            uuid        not null references approval_process (id),
    version_number        integer     not null,
    entry_criteria        text,
    allow_self_approval   boolean     not null default false,
    steps                 jsonb       not null,
    outcomes              jsonb       not null,
    fallback              jsonb,
    version               bigint      not null default 0,
    created_at            timestamptz not null default now(),
    created_by            uuid        not null,
    updated_at            timestamptz not null default now(),
    updated_by            uuid        not null,
    deleted_at            timestamptz,
    deleted_by            uuid,
    constraint approval_process_version_number_positive check (version_number > 0),
    constraint approval_process_version_criteria_length
        check (entry_criteria is null or char_length(entry_criteria) between 1 and 4000),
    constraint approval_process_version_steps_is_array check (jsonb_typeof(steps) = 'array'
        and jsonb_array_length(steps) between 1 and 20),
    constraint approval_process_version_outcomes_is_object check (jsonb_typeof(outcomes) = 'object'),
    constraint approval_process_version_fallback_is_object check (fallback is null or jsonb_typeof(fallback) = 'object'),
    constraint approval_process_version_size
        check (octet_length(steps::text) + octet_length(outcomes::text) <= 200000)
);

create unique index approval_process_version_tenant_number
    on approval_process_version (tenant_id, process_id, version_number) where deleted_at is null;

create trigger approval_process_version_row_guard before insert or update on approval_process_version
    for each row execute function platform_row_guard();
create trigger approval_process_version_tenant_guard before update on approval_process_version
    for each row execute function platform_tenant_guard();
create trigger approval_process_version_immutable before update on approval_process_version
    for each row execute function platform_approval_process_version_immutable();

alter table approval_process_version enable row level security;
alter table approval_process_version force row level security;
create policy approval_process_version_insert on approval_process_version for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_process_version_select on approval_process_version for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_process_version_update on approval_process_version for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_process_version_delete on approval_process_version for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_process_version is
    'One immutable definition of an approval process. A new row only when the criteria, steps, outcomes or fallback changed (ADR-0145).';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_instance
-- ---------------------------------------------------------------------------------------------------------------

create table approval_instance (
    id                       uuid        primary key default uuidv7(),
    tenant_id                uuid        not null default platform_current_tenant() references tenant (id),
    process_id               uuid        not null references approval_process (id),
    process_version_id       uuid        not null references approval_process_version (id),
    -- The names the screens show, kept with the instance so that reading it never touches the definition tables (which the
    -- metadata module owns) and so that it still reads right after the process was removed.
    process_api_name         text        not null,
    process_label            text        not null,
    process_version_number   integer     not null,
    object_api_name          text        not null,
    record_id                uuid        not null,
    record_version           bigint      not null,
    -- The person who submitted it; null when the platform did (a workflow, ADR-0150).
    submitted_by             uuid,
    status                   text        not null default 'PENDING',
    current_step             integer,
    -- Why nobody can decide the active step right now (ADR-0145): a stable key, never a sentence.
    attention                text,
    -- Why the outcome updates were refused after the decision was taken (ADR-0149): a stable key.
    outcome_failure          text,
    -- The chain of the workflow execution that started this request, so that a loop that has people in it is still
    -- seen (ADR-0149); null for a request a person submitted.
    chain_root               uuid,
    chain_depth              integer     not null default 0,
    chain_workflows          text[]      not null default '{}',
    started_at               timestamptz not null default now(),
    finished_at              timestamptz,
    version                  bigint      not null default 0,
    created_at               timestamptz not null default now(),
    created_by               uuid        not null,
    updated_at               timestamptz not null default now(),
    updated_by               uuid        not null,
    deleted_at               timestamptz,
    deleted_by               uuid,
    constraint approval_instance_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint approval_instance_process_name_shape check (process_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$'),
    constraint approval_instance_label_length check (char_length(process_label) between 1 and 80),
    constraint approval_instance_version_number_positive check (process_version_number > 0),
    constraint approval_instance_status_known check (status in (
        'PENDING', 'APPROVED', 'REJECTED', 'RECALLED', 'CANCELLED')),
    constraint approval_instance_attention_shape check (attention is null or attention ~ '^[a-z][a-z_]{0,59}$'),
    constraint approval_instance_outcome_failure_shape
        check (outcome_failure is null or outcome_failure ~ '^[a-z][a-z_]{0,59}$'),
    constraint approval_instance_chain_depth_positive check (chain_depth >= 0),
    -- Pending: a current step and no end. Ended: an end and no current step and nothing to pay attention to.
    constraint approval_instance_pending_shape check (
        (status = 'PENDING' and current_step is not null and current_step > 0 and finished_at is null)
        or (status <> 'PENDING' and current_step is null and finished_at is not null and attention is null))
);

-- THE LOCK (ADR-0148): at most one pending request per record. A second submit waits on an uncommitted one and then
-- fails; the service takes the record's row lock first so that this wait never becomes a deadlock.
create unique index approval_instance_one_pending
    on approval_instance (tenant_id, object_api_name, record_id)
    where status = 'PENDING' and deleted_at is null;
-- The history of one record, newest first; the list of an administrator.
create index approval_instance_tenant_record on approval_instance (tenant_id, record_id, created_at desc, id desc);
create index approval_instance_tenant_status on approval_instance (tenant_id, status, created_at desc, id desc);
-- A version or a process is removed only when nothing names it; a process with a pending instance is never removed.
create index approval_instance_version on approval_instance (process_version_id);
create index approval_instance_process_pending on approval_instance (tenant_id, process_id)
    where status = 'PENDING' and deleted_at is null;
-- What needs an administrator.
create index approval_instance_attention on approval_instance (tenant_id, created_at desc)
    where attention is not null and deleted_at is null;

create trigger approval_instance_row_guard before insert or update on approval_instance
    for each row execute function platform_row_guard();
create trigger approval_instance_tenant_guard before update on approval_instance
    for each row execute function platform_tenant_guard();

alter table approval_instance enable row level security;
alter table approval_instance force row level security;
create policy approval_instance_insert on approval_instance for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_instance_select on approval_instance for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_instance_update on approval_instance for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_instance_delete on approval_instance for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_instance is
    'One approval request for one record under one version of one process. A PENDING instance is the lock of the record (ADR-0148). Never holds a record value.';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_step_instance
-- ---------------------------------------------------------------------------------------------------------------

create table approval_step_instance (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    instance_id     uuid        not null references approval_instance (id),
    position        integer     not null,
    name            text        not null,
    label           text        not null,
    completion      text        not null,
    status          text        not null default 'WAITING',
    activated_at    timestamptz,
    finished_at     timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint approval_step_position_positive check (position > 0),
    constraint approval_step_name_shape check (name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint approval_step_label_length check (char_length(label) between 1 and 80),
    constraint approval_step_completion_known check (completion in ('ANY', 'ALL')),
    constraint approval_step_status_known check (status in ('WAITING', 'ACTIVE', 'APPROVED', 'REJECTED', 'SKIPPED')),
    constraint approval_step_times_known check (
        (status = 'WAITING' and activated_at is null and finished_at is null)
        or (status = 'ACTIVE' and activated_at is not null and finished_at is null)
        or (status in ('APPROVED', 'REJECTED') and activated_at is not null and finished_at is not null)
        or (status = 'SKIPPED' and finished_at is not null))
);

create unique index approval_step_tenant_position on approval_step_instance (tenant_id, instance_id, position)
    where deleted_at is null;
-- Exactly one active step per instance.
create unique index approval_step_one_active on approval_step_instance (tenant_id, instance_id)
    where status = 'ACTIVE' and deleted_at is null;

create trigger approval_step_instance_row_guard before insert or update on approval_step_instance
    for each row execute function platform_row_guard();
create trigger approval_step_instance_tenant_guard before update on approval_step_instance
    for each row execute function platform_tenant_guard();

alter table approval_step_instance enable row level security;
alter table approval_step_instance force row level security;
create policy approval_step_instance_insert on approval_step_instance for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_step_instance_select on approval_step_instance for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_step_instance_update on approval_step_instance for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_step_instance_delete on approval_step_instance for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_step_instance is
    'One step of an approval request: waiting, active, approved, rejected or skipped (ADR-0145).';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_assignment
-- ---------------------------------------------------------------------------------------------------------------

create table approval_assignment (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    instance_id      uuid        not null references approval_instance (id),
    step_id          uuid        not null references approval_step_instance (id),
    assignee_id      uuid        not null,
    -- How the person came to hold the task, and for whom: the delegator, or the person who handed it over.
    via              text        not null default 'DIRECT',
    for_user_id      uuid,
    status           text        not null default 'OPEN',
    decided_at       timestamptz,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint approval_assignment_via_known check (via in ('DIRECT', 'DELEGATED', 'FALLBACK', 'REASSIGNED', 'ADDED')),
    constraint approval_assignment_status_known check (status in ('OPEN', 'APPROVED', 'REJECTED', 'REASSIGNED', 'CLOSED')),
    constraint approval_assignment_decided_known check ((status = 'OPEN') = (decided_at is null)),
    constraint approval_assignment_for_known check (
        (via in ('DELEGATED', 'REASSIGNED')) = (for_user_id is not null))
);

-- A step has at most one task per person; this is also what stops a task going back to a person who held it.
create unique index approval_assignment_tenant_step_assignee on approval_assignment (tenant_id, step_id, assignee_id)
    where deleted_at is null;
-- The inbox: the open tasks of a person.
create index approval_assignment_inbox on approval_assignment (tenant_id, assignee_id, created_at desc, id desc)
    where status = 'OPEN' and deleted_at is null;
-- What a person decided.
create index approval_assignment_decided on approval_assignment (tenant_id, assignee_id, decided_at desc, id desc)
    where decided_at is not null and deleted_at is null;
create index approval_assignment_instance on approval_assignment (tenant_id, instance_id);

create trigger approval_assignment_row_guard before insert or update on approval_assignment
    for each row execute function platform_row_guard();
create trigger approval_assignment_tenant_guard before update on approval_assignment
    for each row execute function platform_tenant_guard();

alter table approval_assignment enable row level security;
alter table approval_assignment force row level security;
create policy approval_assignment_insert on approval_assignment for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_assignment_select on approval_assignment for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_assignment_update on approval_assignment for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_assignment_delete on approval_assignment for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_assignment is
    'One task: a person asked to decide an active step. The assignment is the authority to decide (ADR-0147).';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_history (append-only)
-- ---------------------------------------------------------------------------------------------------------------

create function platform_approval_history_append_only() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'approval history is append-only: % is refused', tg_op using errcode = 'insufficient_privilege';
end;
$guard$;

comment on function platform_approval_history_append_only() is
    'The history of an approval cannot be changed or removed, by anyone (ADR-0145).';

create table approval_history (
    id                       uuid        primary key default uuidv7(),
    tenant_id                uuid        not null default platform_current_tenant() references tenant (id),
    instance_id              uuid        not null references approval_instance (id),
    step_position            integer,
    kind                     text        not null,
    -- The person who did it; null when the platform did.
    actor_id                 uuid,
    -- The person the actor acted for (a delegation), and the person a task was handed to.
    on_behalf_of             uuid,
    target_id                uuid,
    reason                   text,
    -- The comment of a person: bounded, shown only to people who may see the history, never logged.
    comment                  text,
    process_version_number   integer     not null,
    version                  bigint      not null default 0,
    created_at               timestamptz not null default now(),
    created_by               uuid        not null,
    updated_at               timestamptz not null default now(),
    updated_by               uuid        not null,
    deleted_at               timestamptz,
    deleted_by               uuid,
    constraint approval_history_kind_known check (kind in (
        'SUBMITTED', 'STEP_ACTIVATED', 'TASK_APPROVED', 'STEP_APPROVED', 'APPROVED', 'REJECTED', 'RECALLED',
        'CANCELLED',
        'ASSIGNEE_DROPPED', 'REASSIGNED', 'ASSIGNED', 'ATTENTION_RAISED', 'ATTENTION_CLEARED',
        'OUTCOME_APPLIED', 'OUTCOME_FAILED', 'OUTCOME_REAPPLIED', 'LOCK_OVERRIDDEN')),
    constraint approval_history_reason_shape check (reason is null or reason ~ '^[a-z][a-z_]{0,59}$'),
    constraint approval_history_comment_length check (comment is null or char_length(comment) <= 2000),
    constraint approval_history_step_positive check (step_position is null or step_position > 0),
    constraint approval_history_version_number_positive check (process_version_number > 0)
);

create index approval_history_tenant_instance on approval_history (tenant_id, instance_id, created_at, id);

create trigger approval_history_row_guard before insert or update on approval_history
    for each row execute function platform_row_guard();
create trigger approval_history_tenant_guard before update on approval_history
    for each row execute function platform_tenant_guard();
create trigger approval_history_append_only before update or delete on approval_history
    for each row execute function platform_approval_history_append_only();

alter table approval_history enable row level security;
alter table approval_history force row level security;
create policy approval_history_insert on approval_history for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_history_select on approval_history for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_history_update on approval_history for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_history_delete on approval_history for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_history is
    'Append-only history of an approval: who did what, when, the stable reason, and the comment of a person. Never a record value (ADR-0145).';

-- ---------------------------------------------------------------------------------------------------------------
-- approval_delegation
-- ---------------------------------------------------------------------------------------------------------------

create table approval_delegation (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    delegator_id        uuid        not null,
    delegate_id         uuid        not null,
    -- Optionally one process; both or neither.
    process_object      text,
    process_api_name    text,
    starts_on           date        not null,
    ends_on             date        not null,
    time_zone           text        not null default 'UTC',
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint approval_delegation_not_self check (delegator_id <> delegate_id),
    constraint approval_delegation_range check (ends_on >= starts_on and ends_on - starts_on <= 365),
    constraint approval_delegation_process_pair check ((process_object is null) = (process_api_name is null)),
    constraint approval_delegation_object_shape
        check (process_object is null or process_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint approval_delegation_process_shape
        check (process_api_name is null or process_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$'),
    constraint approval_delegation_zone_length check (char_length(time_zone) between 1 and 64)
);

create index approval_delegation_delegator on approval_delegation (tenant_id, delegator_id, ends_on desc)
    where deleted_at is null;
create index approval_delegation_delegate on approval_delegation (tenant_id, delegate_id, ends_on desc)
    where deleted_at is null;

create trigger approval_delegation_row_guard before insert or update on approval_delegation
    for each row execute function platform_row_guard();
create trigger approval_delegation_tenant_guard before update on approval_delegation
    for each row execute function platform_tenant_guard();

alter table approval_delegation enable row level security;
alter table approval_delegation force row level security;
create policy approval_delegation_insert on approval_delegation for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_delegation_select on approval_delegation for select
    using (tenant_id = (select platform_current_tenant()));
create policy approval_delegation_update on approval_delegation for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy approval_delegation_delete on approval_delegation for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table approval_delegation is
    'A person names a delegate and a range of days; the delegate is added beside them when a step becomes active (ADR-0151).';

-- ---------------------------------------------------------------------------------------------------------------
-- clean-up of old definitions
-- ---------------------------------------------------------------------------------------------------------------

-- Removes old definitions that no instance names: not the current one of a process, and not one an instance still
-- points at. The foreign key is the truth about "named". Runs as the caller, so row level security shows only the
-- caller's organization.
create function platform_approval_version_cleanup(older_than interval, batch integer) returns integer
    language plpgsql
as $cleanup$
declare
    candidate uuid;
    removed integer := 0;
begin
    for candidate in
        select v.id
          from approval_process_version v join approval_process p on p.id = v.process_id
         where (p.deleted_at is not null or v.version_number < p.definition_version)
           and v.created_at < now() - older_than
           and not exists (select 1 from approval_instance i where i.process_version_id = v.id)
         order by v.created_at, v.id
         limit batch
    loop
        begin
            delete from approval_process_version where id = candidate;
            removed := removed + 1;
        exception when foreign_key_violation then
            null;
        end;
    end loop;
    return removed;
end;
$cleanup$;

comment on function platform_approval_version_cleanup(interval, integer) is
    'Removes old approval process definitions that no instance names (ADR-0145). Runs as the caller; one batch.';
